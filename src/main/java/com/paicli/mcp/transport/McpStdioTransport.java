package com.paicli.mcp.transport;

import com.paicli.mcp.config.McpServerConfig;
import com.paicli.mcp.manifest.McpConfigManifest;
import com.paicli.mcp.manifest.McpConfigManifestBuilder;
import com.paicli.mcp.manifest.McpManifestComparator;
import com.paicli.mcp.manifest.McpManifestDrift;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public final class McpStdioTransport implements AutoCloseable {
    private static final Pattern SECRET = Pattern.compile("(?i)(bearer\\s+|token\\s*[=:]\\s*|password\\s*[=:]\\s*|api[_-]?key\\s*[=:]\\s*)\\S+");
    private static final Pattern ANSI = Pattern.compile("\\x1B\\[[0-?]*[ -/]*[@-~]");
    private static final Pattern ABSOLUTE_PATH = Pattern.compile("(?<![A-Za-z0-9])(?:[A-Za-z]:\\\\[^\\s]+|/(?:[^\\s/]+/)+[^\\s]*)");
    private final McpServerConfig config;
    private final Process process;
    private final InputStream stdout;
    private final OutputStream stdin;
    private final Thread stderrThread;
    private final StderrCollector stderr;
    private final java.util.concurrent.locks.ReentrantLock writeLock = new java.util.concurrent.locks.ReentrantLock();
    private final AtomicBoolean closed = new AtomicBoolean();

    private McpStdioTransport(McpServerConfig config, Process process, java.util.Collection<String> sensitiveValues) throws McpTransportException {
        this.config = config;
        this.process = process;
        stdout = process.getInputStream();
        stdin = process.getOutputStream();
        stderr = new StderrCollector(config.limits().maxStderrBytes(), sensitiveValues);
        stderrThread = Thread.ofPlatform().daemon(true).name("mcp-stderr-" + config.serverId()).start(() -> stderr.drain(process.getErrorStream()));
    }

    public static McpStdioTransport start(McpServerConfig config, McpConfigManifest persisted,
                                           Map<String, String> hostEnvironment) throws McpTransportException {
        final McpConfigManifest current;
        try { current = new McpConfigManifestBuilder().build(config); }
        catch (IOException e) { throw new McpTransportException("CONFIG_DRIFT", "trusted MCP executable or working directory is unavailable", e); }
        McpManifestDrift drift = new McpManifestComparator().compare(persisted, current);
        if (drift != McpManifestDrift.MATCH) throw new McpTransportException(drift.name(), "trusted MCP configuration no longer matches persisted manifest");
        try {
            ProcessBuilder builder = new ProcessBuilder();
            var command = new java.util.ArrayList<String>();
            command.add(config.executable().toRealPath().toString());
            command.addAll(config.args());
            builder.command(command);
            builder.directory(config.workingDirectory().toRealPath().toFile());
            builder.redirectErrorStream(false);
            Map<String, String> environment = builder.environment();
            environment.clear();
            Map<String, String> resolved = new McpProcessEnvironment().resolve(config, hostEnvironment);
            environment.putAll(resolved);
            java.util.List<String> sensitiveValues = config.environmentReferences().stream()
                    .map(reference -> resolved.get(reference.name())).filter(java.util.Objects::nonNull).toList();
            com.paicli.diagnostic.StageTrace.event(
                    "CHILD_PROCESS_CREATE_START", false,
                    "purpose", "MCP_STDIO",
                    "executable_basename",
                    com.paicli.diagnostic.StageTrace.executableBasename(command),
                    "argument_count", Math.max(0, command.size() - 1),
                    "argument_hash",
                    com.paicli.diagnostic.StageTrace.hashArguments(command));
            Process process;
            try {
                process = builder.start();
                com.paicli.diagnostic.StageTrace.event(
                        "CHILD_PROCESS_CREATE_END", false,
                        "purpose", "MCP_STDIO",
                        "outcome", "STARTED",
                        "pid", com.paicli.diagnostic.StageTrace.processId(process));
            } catch (IOException error) {
                com.paicli.diagnostic.StageTrace.event(
                        "CHILD_PROCESS_CREATE_END", false,
                        "purpose", "MCP_STDIO",
                        "outcome", "ERROR",
                        "error_type", error.getClass().getName());
                throw error;
            }
            return new McpStdioTransport(config, process, sensitiveValues);
        } catch (IOException e) {
            throw new McpTransportException("PROCESS_START_FAILED", "unable to start trusted MCP process", e);
        }
    }

    public byte[] readLine() throws McpTransportException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        try {
            while (true) {
                int b = stdout.read();
                if (b < 0) throw new McpTransportException("PROCESS_EXITED", "MCP stdout reached EOF");
                if (b == '\n') break;
                if (b != '\r') line.write(b);
                if (line.size() > config.limits().maxStdoutLineBytes()) throw new McpTransportException("OVERSIZED_STDOUT_LINE", "MCP stdout line exceeds configured limit");
            }
            if (line.size() == 0) throw new McpTransportException("EMPTY_STDOUT_LINE", "empty MCP stdout line is not allowed");
            return strictUtf8(line.toByteArray()).getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) { throw new McpTransportException("STDOUT_READ_FAILED", "unable to read MCP stdout", e); }
    }

    public void writeLine(byte[] json) throws McpTransportException {
        if (closed.get()) throw new McpTransportException("TRANSPORT_CLOSED", "MCP transport is closed");
        if (json.length > config.limits().maxStdoutLineBytes()) throw new McpTransportException("MESSAGE_TOO_LARGE", "JSON-RPC message exceeds configured limit");
        for (byte b : json) if (b == '\n' || b == '\r') throw new McpTransportException("INVALID_FRAME", "JSON-RPC frame must be one physical line");
        writeLock.lock();
        try {
            if (closed.get()) throw new McpTransportException("TRANSPORT_CLOSED", "MCP transport is closed");
            try { stdin.write(json); stdin.write('\n'); stdin.flush(); }
            catch (IOException e) { throw new McpTransportException("STDIN_WRITE_FAILED", "unable to write MCP stdin", e); }
        } finally {
            if (closed.get()) try { stdin.close(); } catch (IOException ignored) { }
            writeLock.unlock();
        }
    }

    public boolean isAlive() { return process.isAlive(); }
    public long pid() { return process.pid(); }
    public String processIdentityHash() { return McpConfigManifestBuilder.hash(config.serverId() + ":" + process.pid()); }
    public Integer exitCode() { try { return process.exitValue(); } catch (IllegalThreadStateException e) { return null; } }
    public McpStderrSummary stderrSummary() { return stderr.summary(); }

    public synchronized ShutdownResult shutdown() {
        if (!closed.compareAndSet(false, true)) return new ShutdownResult(exitCode(), false);
        // Capture owned children before the server exits and they are reparented.
        var children = process.descendants().toList();
        boolean forced = false;
        if (writeLock.tryLock()) {
            try { stdin.close(); } catch (IOException ignored) { }
            finally { writeLock.unlock(); }
        } else {
            // Kill through ProcessHandle without waiting for a writer-owned stream monitor.
            forced = true;
            process.toHandle().destroyForcibly();
            for (var child : children) if (child.isAlive()) child.destroyForcibly();
        }
        long wait = config.limits().shutdownTimeoutMs();
        try {
            if (!process.waitFor(wait, TimeUnit.MILLISECONDS)) {
                process.destroy();
                if (!process.waitFor(Math.max(100, wait / 2), TimeUnit.MILLISECONDS)) {
                    forced = true;
                    process.destroyForcibly();
                    process.waitFor(Math.max(100, wait / 2), TimeUnit.MILLISECONDS);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            forced = true;
            process.destroyForcibly();
        }
        for (var child : children) if (child.isAlive()) child.destroy();
        long childDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.min(1000, wait));
        for (var child : children) if (child.isAlive()) {
            try { child.onExit().get(Math.max(1, childDeadline-System.nanoTime()), TimeUnit.NANOSECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException ignored) { }
            if (child.isAlive()) { forced=true; child.destroyForcibly(); }
        }
        if (writeLock.tryLock()) {
            try { stdin.close(); } catch (IOException ignored) { }
            finally { writeLock.unlock(); }
        }
        try { stdout.close(); } catch (IOException ignored) {}
        try { stderrThread.join(Math.min(1000, wait)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return new ShutdownResult(exitCode(), forced);
    }

    @Override public void close() { shutdown(); }
    public record ShutdownResult(Integer exitCode, boolean forciblyDestroyed) {}

    private static String strictUtf8(byte[] bytes) throws McpTransportException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) { throw new McpTransportException("INVALID_UTF8", "MCP stdout is not valid UTF-8"); }
    }

    private static final class StderrCollector {
        private final int max;
        private final java.util.List<String> sensitiveValues;
        private final MessageDigest digest;
        private final ByteArrayOutputStream excerpt = new ByteArrayOutputStream();
        private volatile long total;
        private volatile boolean redacted;
        private volatile String finding;
        StderrCollector(int max, java.util.Collection<String> sensitiveValues) throws McpTransportException {
            this.max = max;
            this.sensitiveValues = sensitiveValues.stream().filter(value -> !value.isEmpty()).toList();
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (Exception e) { throw new McpTransportException("HASH_UNAVAILABLE", "unable to initialize stderr digest"); }
        }
        void drain(InputStream input) {
            byte[] buffer = new byte[4096];
            try {
                for (int n; (n = input.read(buffer)) >= 0;) {
                    synchronized (this) {
                        digest.update(buffer, 0, n); total += n;
                        int remaining = max - excerpt.size();
                        if (remaining > 0) excerpt.write(buffer, 0, Math.min(n, remaining));
                    }
                }
            } catch (IOException e) { finding = "STDERR_READ_FAILED"; }
        }
        synchronized McpStderrSummary summary() {
            String text = new String(excerpt.toByteArray(), StandardCharsets.UTF_8);
            String clean = ANSI.matcher(text).replaceAll("").replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "");
            String safe = clean;
            for (String value : sensitiveValues) safe = safe.replace(value, "[REDACTED]");
            safe = SECRET.matcher(safe).replaceAll("$1[REDACTED]");
            safe = ABSOLUTE_PATH.matcher(safe).replaceAll("[ABSOLUTE_PATH_REDACTED]");
            redacted |= !safe.equals(clean);
            return new McpStderrSummary(HexFormat.of().formatHex(copyDigest()), safe, total > max, redacted, finding);
        }
        private byte[] copyDigest() {
            try { return ((MessageDigest) digest.clone()).digest(); }
            catch (CloneNotSupportedException e) { return new byte[0]; }
        }
    }
}
