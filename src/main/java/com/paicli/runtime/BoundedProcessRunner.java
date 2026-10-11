package com.paicli.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Executes a shell command with bounded parallel stream capture and process-tree cleanup. */
public final class BoundedProcessRunner {

    private final Path workspace;
    private final PersistenceSanitizer sanitizer;
    private final int maxOutputBytes;
    private final ProcessLauncher launcher;

    public BoundedProcessRunner(Path workspace, PersistenceSanitizer sanitizer,
                                int maxOutputBytes) throws IOException {
        this(workspace, sanitizer, maxOutputBytes, BoundedProcessRunner::startShellProcess);
    }

    BoundedProcessRunner(Path workspace, PersistenceSanitizer sanitizer,
                         int maxOutputBytes, ProcessLauncher launcher) throws IOException {
        Path candidate = Objects.requireNonNull(workspace, "workspace")
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(candidate)) {
            throw new IOException("Process workspace is not a directory");
        }
        this.workspace = candidate.toRealPath();
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer");
        if (maxOutputBytes < 1) {
            throw new IllegalArgumentException("maxOutputBytes must be positive");
        }
        this.maxOutputBytes = maxOutputBytes;
        this.launcher = Objects.requireNonNull(launcher, "launcher");
    }

    public ProcessResult execute(String command, int timeoutSeconds) {
        if (command == null || command.isBlank()) {
            throw new IllegalArgumentException("command must not be blank");
        }
        if (timeoutSeconds < 1 || timeoutSeconds > 300) {
            throw new IllegalArgumentException("timeoutSeconds must be between 1 and 300");
        }
        long startedAt = System.nanoTime();
        Process process;
        String shellExecutable = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).contains("windows")
                ? System.getenv().getOrDefault("ComSpec", "cmd.exe") : "/bin/sh";
        java.util.List<String> diagnosticCommand = java.util.List.of(
                shellExecutable, "<shell-command-hash-input>", command);
        com.paicli.diagnostic.StageTrace.event(
                "CHILD_PROCESS_CREATE_START", false,
                "purpose", "HOOK_COMMAND",
                "executable_basename",
                com.paicli.diagnostic.StageTrace.executableBasename(diagnosticCommand),
                "argument_count", 1,
                "argument_hash",
                com.paicli.diagnostic.StageTrace.hashArguments(diagnosticCommand));
        try {
            process = Objects.requireNonNull(launcher.start(workspace, command),
                    "process launcher returned null");
            com.paicli.diagnostic.StageTrace.event(
                    "CHILD_PROCESS_CREATE_END", false,
                    "purpose", "HOOK_COMMAND",
                    "outcome", "STARTED",
                    "pid", com.paicli.diagnostic.StageTrace.processId(process));
            process.getOutputStream().close();
        } catch (IOException | RuntimeException e) {
            com.paicli.diagnostic.StageTrace.event(
                    "CHILD_PROCESS_CREATE_END", false,
                    "purpose", "HOOK_COMMAND",
                    "outcome", "ERROR",
                    "error_type", e.getClass().getName());
            return result(Status.ERROR, null, startedAt, null,
                    "Hook command could not start: " + safeMessage(e));
        }

        AtomicReference<Capture> stdout = new AtomicReference<>();
        AtomicReference<Capture> stderr = new AtomicReference<>();
        Thread outThread = capture(process.getInputStream(), stdout, "hook-stdout");
        Thread errThread = capture(process.getErrorStream(), stderr, "hook-stderr");
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                terminate(process);
                closeStreams(process);
                join(outThread);
                join(errThread);
                return result(Status.TIMED_OUT, null, startedAt,
                        excerpt(stdout.get()), merge(excerpt(stderr.get()),
                                "Hook command timed out after " + timeoutSeconds + " seconds"));
            }
            join(outThread);
            join(errThread);
            IOException captureError = firstError(stdout.get(), stderr.get());
            if (captureError != null) {
                return result(Status.ERROR, process.exitValue(), startedAt,
                        excerpt(stdout.get()), merge(excerpt(stderr.get()),
                                "Hook output capture failed: " + safeMessage(captureError)));
            }
            return result(process.exitValue() == 0 ? Status.PASSED : Status.FAILED,
                    process.exitValue(), startedAt, excerpt(stdout.get()), excerpt(stderr.get()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            terminate(process);
            closeStreams(process);
            joinQuietly(outThread);
            joinQuietly(errThread);
            return result(Status.ERROR, null, startedAt, excerpt(stdout.get()),
                    merge(excerpt(stderr.get()), "Hook command was interrupted"));
        } finally {
            closeStreams(process);
            if (process.isAlive()) {
                terminate(process);
            }
        }
    }

    private Thread capture(InputStream stream, AtomicReference<Capture> destination, String name) {
        Thread thread = new Thread(() -> destination.set(read(stream)), name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private Capture read(InputStream stream) {
        ByteArrayOutputStream kept = new ByteArrayOutputStream();
        long observed = 0;
        byte[] buffer = new byte[4096];
        try (stream) {
            int read;
            while ((read = stream.read(buffer)) != -1) {
                observed += read;
                int remaining = maxOutputBytes - kept.size();
                if (remaining > 0) {
                    kept.write(buffer, 0, Math.min(read, remaining));
                }
            }
            return new Capture(kept.toByteArray(), observed, null);
        } catch (IOException e) {
            return new Capture(kept.toByteArray(), observed, e);
        }
    }

    private String excerpt(Capture capture) {
        if (capture == null || (capture.bytes().length == 0 && capture.observedBytes() == 0)) {
            return null;
        }
        String value = new String(capture.bytes(), StandardCharsets.UTF_8);
        if (capture.observedBytes() > capture.bytes().length) {
            value += "\n...[stream truncated; observed_bytes=" + capture.observedBytes() + "]";
        }
        return sanitizer.sanitizeAndTruncate(value, maxOutputBytes);
    }

    private ProcessResult result(Status status, Integer exitCode, long startedAt,
                                 String output, String error) {
        return new ProcessResult(status, exitCode,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
                output, sanitize(error));
    }

    private String sanitize(String value) {
        return value == null ? null : sanitizer.sanitizeAndTruncate(value, maxOutputBytes);
    }

    private String merge(String first, String second) {
        if (first == null || first.isBlank()) {
            return sanitize(second);
        }
        return sanitize(first + "\n" + second);
    }

    private IOException firstError(Capture stdout, Capture stderr) {
        if (stdout != null && stdout.error() != null) return stdout.error();
        return stderr == null ? null : stderr.error();
    }

    private void join(Thread thread) throws InterruptedException {
        thread.join();
    }

    private void joinQuietly(Thread thread) {
        try {
            thread.join(1000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void terminate(Process process) {
        process.descendants().forEach(handle -> {
            if (handle.isAlive()) handle.destroyForcibly();
        });
        process.destroyForcibly();
    }

    private void closeStreams(Process process) {
        try { process.getInputStream().close(); } catch (IOException ignored) { }
        try { process.getErrorStream().close(); } catch (IOException ignored) { }
        try { process.getOutputStream().close(); } catch (IOException ignored) { }
    }

    private String safeMessage(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static Process startShellProcess(Path workspace, String command) throws IOException {
        ProcessBuilder builder;
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            builder = new ProcessBuilder(System.getenv().getOrDefault("ComSpec", "cmd.exe"),
                    "/d", "/s", "/c", command);
        } else {
            builder = new ProcessBuilder("/bin/sh", "-c", command);
        }
        return builder.directory(workspace.toFile()).start();
    }

    public enum Status { PASSED, FAILED, TIMED_OUT, ERROR }

    public record ProcessResult(Status status, Integer exitCode, long durationMs,
                                String outputExcerpt, String errorExcerpt) { }

    @FunctionalInterface
    interface ProcessLauncher {
        Process start(Path workspace, String command) throws IOException;
    }

    private record Capture(byte[] bytes, long observedBytes, IOException error) { }
}
