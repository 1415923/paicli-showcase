package com.paicli.mcp.spike;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.runtime.PersistenceSanitizer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Test-only stdio/JSON-RPC protocol spike. Not a production MCP client. */
final class McpStdioProtocolSpike implements AutoCloseable {
    static final String VERSION = "2025-11-25";
    private static final Set<String> SUPPORTED_VERSIONS = Set.of(VERSION);
    private final ObjectMapper json;
    private final Process process;
    private final Writer stdin;
    private final int maxLineBytes;
    private final int maxStderrBytes;
    private final AtomicLong ids = new AtomicLong();
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Set<Long> completed = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private final Thread stdoutReader;
    private final Thread stderrReader;
    private volatile State state = State.STARTING;
    private volatile String failure;

    McpStdioProtocolSpike(String mode, int maxLineBytes, int maxDepth, int maxStderrBytes) throws IOException {
        this.maxLineBytes = maxLineBytes;
        this.maxStderrBytes = maxStderrBytes;
        this.json = new ObjectMapper(JsonFactory.builder().streamReadConstraints(
                StreamReadConstraints.builder().maxNestingDepth(maxDepth)
                        .maxStringLength(maxLineBytes).build()).build());
        String java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
                .toAbsolutePath().toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        ProcessBuilder builder = new ProcessBuilder(List.of(java, "-cp", classpath,
                FakeMcpServerMain.class.getName(), mode));
        builder.redirectErrorStream(false);
        builder.environment().clear();
        this.process = builder.start();
        this.stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        this.stdoutReader = new Thread(this::readStdout, "mcp-spike-stdout");
        this.stderrReader = new Thread(this::readStderr, "mcp-spike-stderr");
        stdoutReader.setDaemon(true);
        stderrReader.setDaemon(true);
        stdoutReader.start();
        stderrReader.start();
    }

    void initialize(Duration timeout) {
        state = State.INITIALIZING;
        ObjectNode params = json.createObjectNode();
        params.put("protocolVersion", VERSION);
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", "PaiCLI-R0-spike").put("version", "0");
        JsonNode result = request("initialize", params, timeout);
        String version = result.path("protocolVersion").asText();
        if (!SUPPORTED_VERSIONS.contains(version)) {
            unhealthy("unsupported protocol version");
            throw new ProtocolException("Unsupported protocol version");
        }
        if (!result.path("capabilities").has("tools")) {
            unhealthy("tools capability missing");
            throw new ProtocolException("Server did not negotiate tools capability");
        }
        if (state == State.UNHEALTHY) {
            throw new ProtocolException("Session became unhealthy during initialization");
        }
        notify("notifications/initialized", json.createObjectNode());
        synchronized (this) {
            if (state == State.UNHEALTHY) {
                throw new ProtocolException("Session became unhealthy during initialization");
            }
            state = State.READY;
        }
    }

    JsonNode request(String method, JsonNode params, Duration timeout) {
        if (!accepting.get()) throw new ProtocolException("Session is not accepting requests");
        if (pending.size() >= 8) throw new ProtocolException("Pending request limit exceeded");
        long id = ids.incrementAndGet();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        ObjectNode request = json.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.set("params", params == null ? json.createObjectNode() : params);
        send(request);
        try {
            JsonNode response = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.has("error")) throw new RpcError(response.path("error").path("message").asText("RPC error"));
            return response.path("result");
        } catch (TimeoutException e) {
            pending.remove(id);
            completed.add(id);
            notify("notifications/cancelled", json.createObjectNode().put("requestId", id)
                    .put("reason", "timeout"));
            unhealthy("request timeout");
            throw new RequestTimeout("MCP request timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.remove(id);
            unhealthy("request interrupted");
            throw new ProtocolException("Request interrupted", e);
        } catch (ExecutionException e) {
            throw new ProtocolException("Request failed", e.getCause());
        }
    }

    List<JsonNode> listTools(int maxPages, int maxTools, int maxCatalogBytes, Duration timeout) {
        requireReady();
        List<JsonNode> tools = new ArrayList<>();
        Set<String> cursors = new HashSet<>();
        Set<String> names = new HashSet<>();
        String cursor = null;
        int bytes = 0;
        for (int page = 0; page < maxPages; page++) {
            ObjectNode params = json.createObjectNode();
            if (cursor != null) params.put("cursor", cursor);
            JsonNode result = request("tools/list", params, timeout);
            for (JsonNode tool : result.path("tools")) {
                String name = tool.path("name").asText();
                if (!name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}") || !names.add(name)) {
                    unhealthy("invalid or duplicate tool name");
                    throw new ProtocolException("Invalid or duplicate tool name");
                }
                JsonNode schema = tool.path("inputSchema");
                if (!schema.isObject()) throw new ProtocolException("Tool inputSchema must be an object");
                bytes += tool.toString().getBytes(StandardCharsets.UTF_8).length;
                if (tools.size() >= maxTools || bytes > maxCatalogBytes) {
                    unhealthy("catalog budget exceeded");
                    throw new ProtocolException("Tool catalog budget exceeded");
                }
                tools.add(tool);
            }
            cursor = result.path("nextCursor").asText(null);
            if (cursor == null) return List.copyOf(tools);
            if (!cursors.add(cursor)) {
                unhealthy("cursor loop");
                throw new ProtocolException("Repeated tools/list cursor");
            }
        }
        unhealthy("page budget exceeded");
        throw new ProtocolException("tools/list page budget exceeded");
    }

    State state() { return state; }
    String failure() { return failure; }
    boolean alive() { return process.isAlive(); }
    String stderrExcerpt() {
        return new PersistenceSanitizer().sanitizeAndTruncate(
                stderr.toString(StandardCharsets.UTF_8), maxStderrBytes);
    }

    private void requireReady() {
        if (state != State.READY) throw new ProtocolException("MCP session is not READY");
    }

    private void notify(String method, JsonNode params) {
        ObjectNode notification = json.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        notification.set("params", params);
        send(notification);
    }

    private synchronized void send(JsonNode message) {
        if (!process.isAlive()) {
            unhealthy("process exited");
            throw new ProtocolException("MCP process exited");
        }
        try {
            String serialized = json.writeValueAsString(message);
            if (serialized.indexOf('\n') >= 0 || serialized.indexOf('\r') >= 0) {
                throw new ProtocolException("Physical newline in JSON-RPC message");
            }
            stdin.write(serialized);
            stdin.write('\n');
            stdin.flush();
        } catch (IOException e) {
            unhealthy("broken pipe");
            throw new ProtocolException("Failed to write JSON-RPC message", e);
        }
    }

    private void readStdout() {
        try {
            while (accepting.get()) {
                String line = readBoundedLine(process.getInputStream(), maxLineBytes);
                if (line == null) {
                    if (state != State.CLOSED && state != State.SHUTTING_DOWN) unhealthy("stdout EOF");
                    return;
                }
                JsonNode message;
                try {
                    message = json.readTree(line);
                } catch (Exception e) {
                    unhealthy("non-JSON stdout");
                    return;
                }
                if (!message.isObject() || !"2.0".equals(message.path("jsonrpc").asText())) {
                    unhealthy("invalid JSON-RPC envelope");
                    return;
                }
                if (message.has("id") && (message.has("result") || message.has("error"))) {
                    if (message.has("result") && message.has("error")) {
                        unhealthy("result and error both present");
                        return;
                    }
                    long id = message.path("id").asLong(Long.MIN_VALUE);
                    CompletableFuture<JsonNode> future = pending.remove(id);
                    if (future == null || !completed.add(id)) {
                        unhealthy("unknown or duplicate response id");
                        return;
                    }
                    future.complete(message);
                } else if (message.has("method") && !message.has("id")) {
                    // Notifications are data-only in R0.
                } else {
                    unhealthy("request/response type confusion");
                    return;
                }
            }
        } catch (Exception e) {
            if (state != State.CLOSED && state != State.SHUTTING_DOWN) unhealthy("stdout read failure");
        }
    }

    private void readStderr() {
        byte[] buffer = new byte[512];
        try (InputStream input = process.getErrorStream()) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                synchronized (stderr) {
                    int remaining = maxStderrBytes - stderr.size();
                    if (remaining > 0) stderr.write(buffer, 0, Math.min(remaining, count));
                }
            }
        } catch (IOException ignored) { }
    }

    private String readBoundedLine(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') break;
            if (value == '\r') throw new IOException("CR framing is not allowed");
            if (bytes.size() >= limit) throw new IOException("MCP stdout line limit exceeded");
            bytes.write(value);
        }
        if (value == -1 && bytes.size() == 0) return null;
        return bytes.toString(StandardCharsets.UTF_8);
    }

    private synchronized void unhealthy(String reason) {
        failure = reason;
        state = State.UNHEALTHY;
        accepting.set(false);
        ProtocolException error = new ProtocolException(reason);
        pending.values().forEach(future -> future.completeExceptionally(error));
        pending.clear();
    }

    @Override public void close() {
        if (state == State.CLOSED) return;
        state = State.SHUTTING_DOWN;
        accepting.set(false);
        pending.values().forEach(future -> future.completeExceptionally(
                new ProtocolException("Session closed")));
        pending.clear();
        try { stdin.close(); } catch (IOException ignored) { }
        try {
            if (!process.waitFor(200, TimeUnit.MILLISECONDS)) process.destroy();
            if (!process.waitFor(200, TimeUnit.MILLISECONDS)) process.destroyForcibly();
            process.waitFor(1, TimeUnit.SECONDS);
            stdoutReader.join(500);
            stderrReader.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        state = State.CLOSED;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    enum State { STARTING, INITIALIZING, READY, UNHEALTHY, SHUTTING_DOWN, CLOSED }
    static class ProtocolException extends RuntimeException {
        ProtocolException(String message) { super(message); }
        ProtocolException(String message, Throwable cause) { super(message, cause); }
    }
    static final class RequestTimeout extends ProtocolException { RequestTimeout(String message) { super(message); } }
    static final class RpcError extends ProtocolException { RpcError(String message) { super(message); } }
}
