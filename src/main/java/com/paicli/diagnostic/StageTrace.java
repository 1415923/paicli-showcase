package com.paicli.diagnostic;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Synchronous, bounded diagnostic stage trace for the Windows UAT blocker.
 *
 * <p>This trace is deliberately independent of asynchronous logging. Callers provide only
 * non-sensitive aggregate fields; values are control-character stripped and bounded before
 * being written. A failed trace never changes the application operation being diagnosed.</p>
 */
public final class StageTrace {
    public static final String FILE_NAME = "paicli-stage-trace.log";
    private static final int MAX_VALUE_CHARS = 1024;
    private static final Object LOCK = new Object();
    private static final AtomicBoolean FAILURE_REPORTED = new AtomicBoolean();
    private static volatile TraceSink sink;

    private StageTrace() {
    }

    public static void initialize(String[] arguments) {
        synchronized (LOCK) {
            if (sink != null) return;
            try {
                sink = TraceSink.open(resolveDiagnosticRoot());
            } catch (IOException | RuntimeException error) {
                reportFailure(error);
                return;
            }
        }
        event("PROCESS_START", true,
                "cwd", canonicalPath(Path.of(System.getProperty("user.dir", "."))),
                "argument_count", arguments == null ? 0 : arguments.length,
                "argument_hash", hashArguments(arguments == null
                        ? List.of() : Arrays.asList(arguments.clone())),
                "java_version", System.getProperty("java.version", "unknown"));
    }

    public static void event(String stage, boolean durable, Object... fields) {
        TraceSink current = sink;
        if (current == null) return;
        try {
            current.write(stage, durable, fields);
        } catch (IOException | RuntimeException error) {
            reportFailure(error);
        }
    }

    public static String canonicalPath(Path path) {
        if (path == null) return "<unavailable>";
        Path absolute = path.toAbsolutePath().normalize();
        try {
            return absolute.toRealPath(LinkOption.NOFOLLOW_LINKS).toString();
        } catch (IOException | RuntimeException ignored) {
            return absolute.toString();
        }
    }

    public static String executableBasename(List<String> command) {
        if (command == null || command.isEmpty() || command.getFirst() == null) {
            return "<unavailable>";
        }
        String executable = command.getFirst();
        try {
            Path name = Path.of(executable).getFileName();
            return name == null ? "<unavailable>" : name.toString();
        } catch (RuntimeException ignored) {
            int slash = Math.max(executable.lastIndexOf('/'), executable.lastIndexOf('\\'));
            return slash >= 0 ? executable.substring(slash + 1) : executable;
        }
    }

    public static String hashArguments(List<String> arguments) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (arguments != null) {
                for (String argument : arguments) {
                    byte[] bytes = Objects.toString(argument, "<null>")
                            .getBytes(StandardCharsets.UTF_8);
                    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                    digest.update(bytes);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            return "<sha256-unavailable>";
        }
    }

    public static long processId(Process process) {
        if (process == null) return -1L;
        try {
            return process.pid();
        } catch (RuntimeException ignored) {
            return -1L;
        }
    }

    public static String safeException(Throwable error) {
        if (error == null) return "<none>";
        String message = error.getMessage();
        return error.getClass().getName() + ":"
                + (message == null || message.isBlank() ? "<no-message>" : safe(message));
    }

    static void resetForTests(Path diagnosticRoot) throws IOException {
        synchronized (LOCK) {
            if (sink != null) sink.close();
            sink = TraceSink.open(diagnosticRoot);
            FAILURE_REPORTED.set(false);
        }
    }

    static void closeForTests() throws IOException {
        synchronized (LOCK) {
            if (sink != null) sink.close();
            sink = null;
            FAILURE_REPORTED.set(false);
        }
    }

    private static Path resolveDiagnosticRoot() {
        String override = System.getProperty("paicli.diagnostics.root");
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        try {
            URI location = StageTrace.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI();
            Path source = Path.of(location).toAbsolutePath().normalize();
            if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                Path parent = source.getParent();
                if (parent != null && parent.getFileName() != null
                        && "lib".equalsIgnoreCase(parent.getFileName().toString())
                        && parent.getParent() != null) {
                    return parent.getParent();
                }
                if (parent != null) return parent;
            }
        } catch (Exception ignored) {
            // The normalized process cwd is the final diagnostic-only fallback.
        }
        return Path.of(System.getProperty("user.dir", "."))
                .toAbsolutePath().normalize();
    }

    private static String safe(Object value) {
        String text = Objects.toString(value, "<null>")
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replace('\t', ' ')
                .replace("|", "%7C");
        StringBuilder clean = new StringBuilder(Math.min(text.length(), MAX_VALUE_CHARS));
        text.codePoints().limit(MAX_VALUE_CHARS).forEach(codePoint -> {
            if (!Character.isISOControl(codePoint)) clean.appendCodePoint(codePoint);
        });
        return clean.toString();
    }

    private static String stage(String value) {
        String normalized = Objects.requireNonNull(value, "stage")
                .trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z][A-Z0-9_]{1,63}")) {
            throw new IllegalArgumentException("invalid diagnostic stage");
        }
        return normalized;
    }

    private static void reportFailure(Throwable error) {
        if (FAILURE_REPORTED.compareAndSet(false, true)) {
            System.err.println("[Diagnostic] Stage trace unavailable: "
                    + error.getClass().getSimpleName());
        }
    }

    private static final class TraceSink {
        private final FileChannel channel;
        private final long startedNanos = System.nanoTime();

        private TraceSink(FileChannel channel) {
            this.channel = channel;
        }

        static TraceSink open(Path root) throws IOException {
            Path diagnostics = Objects.requireNonNull(root, "root")
                    .toAbsolutePath().normalize().resolve("diagnostics");
            Files.createDirectories(diagnostics);
            Path trace = diagnostics.resolve(FILE_NAME);
            FileChannel channel = FileChannel.open(trace,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
            return new TraceSink(channel);
        }

        void write(String stage, boolean durable, Object... fields) throws IOException {
            if (fields == null || fields.length % 2 != 0) {
                throw new IllegalArgumentException("diagnostic fields must be key/value pairs");
            }
            Instant utc = Instant.now();
            StringBuilder line = new StringBuilder(512)
                    .append("utc=").append(utc)
                    .append("|local=").append(OffsetDateTime.now())
                    .append("|mono_ms=")
                    .append((System.nanoTime() - startedNanos) / 1_000_000L)
                    .append("|pid=").append(ProcessHandle.current().pid())
                    .append("|stage=").append(stage(stage));
            for (int index = 0; index < fields.length; index += 2) {
                String key = Objects.toString(fields[index], "").trim();
                if (!key.matches("[a-z][a-z0-9_]{0,63}")) {
                    throw new IllegalArgumentException("invalid diagnostic field");
                }
                line.append('|').append(key).append('=').append(safe(fields[index + 1]));
            }
            line.append(System.lineSeparator());
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(line.toString());
            synchronized (LOCK) {
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(durable);
            }
        }

        void close() throws IOException {
            synchronized (LOCK) {
                channel.force(true);
                channel.close();
            }
        }
    }
}
