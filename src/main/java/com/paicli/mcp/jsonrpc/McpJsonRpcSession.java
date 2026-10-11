package com.paicli.mcp.jsonrpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.paicli.mcp.config.McpServerConfig;
import com.paicli.mcp.transport.McpStdioTransport;
import com.paicli.mcp.transport.McpTransportException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class McpJsonRpcSession implements AutoCloseable {
    public enum State { INITIALIZING, READY, UNHEALTHY, CLOSED }

    private final McpStdioTransport transport;
    private final McpServerConfig config;
    private final McpJsonRpcCodec codec = new McpJsonRpcCodec(64);
    private final AtomicLong ids = new AtomicLong();
    private final ConcurrentMap<Long, Pending> pending = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> recentlyCompleted;
    private final ScheduledExecutorService scheduler;
    private final Thread reader;
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private volatile State state = State.INITIALIZING;
    private volatile BiConsumer<String, String> unhealthyListener = (category, detail) -> {};
    private volatile Consumer<McpJsonRpcMessage.Notification> notificationListener = ignored -> {};

    public McpJsonRpcSession(McpStdioTransport transport, McpServerConfig config) {
        this(transport, config, null);
    }

    public McpJsonRpcSession(McpStdioTransport transport, McpServerConfig config,
                             BiConsumer<String, String> unhealthyListener) {
        this.transport = transport;
        this.config = config;
        this.unhealthyListener = unhealthyListener == null ? (category, detail) -> {} : unhealthyListener;
        int recentLimit = Math.max(32, config.limits().maxPendingRequests() * 4);
        recentlyCompleted = java.util.Collections.synchronizedMap(new LinkedHashMap<>(recentLimit + 1, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) { return size() > recentLimit; }
        });
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true).name("mcp-timeout-" + config.serverId()).factory());
        reader = Thread.ofPlatform().daemon(true).name("mcp-stdout-" + config.serverId()).start(this::readLoop);
    }

    public CompletableFuture<McpSessionResult> sendRequest(String method, JsonNode params, Duration timeout)
            throws McpTransportException, McpProtocolException {
        return sendRequest(method, params, timeout, true, State.INITIALIZING, config.limits().requestTimeoutMs());
    }

    public CompletableFuture<McpSessionResult> sendInitialize(JsonNode params, Duration timeout)
            throws McpTransportException, McpProtocolException {
        return sendRequest("initialize", params, timeout, false, State.INITIALIZING, config.limits().startupTimeoutMs());
    }

    public CompletableFuture<McpSessionResult> sendToolsList(JsonNode params, Duration timeout)
            throws McpTransportException, McpProtocolException {
        return sendRequest("tools/list", params, timeout, true, State.READY, config.limits().requestTimeoutMs());
    }

    public PreparedRequest prepareToolsCall(JsonNode params, Duration timeout)
            throws McpProtocolException, McpTransportException {
        return prepareRequest("tools/call", params, timeout, true, State.READY,
                config.limits().requestTimeoutMs());
    }

    public final class PreparedRequest {
        private final Pending pendingRequest;
        private final byte[] frame;
        private final long timeoutMillis;
        private final State requiredState;
        private final AtomicBoolean sent = new AtomicBoolean();

        private PreparedRequest(Pending pendingRequest, byte[] frame, long timeoutMillis, State requiredState) {
            this.pendingRequest = pendingRequest; this.frame = frame; this.timeoutMillis = timeoutMillis; this.requiredState = requiredState;
        }
        public long requestId() { return pendingRequest.id; }
        public CompletableFuture<McpSessionResult> send() throws McpTransportException {
            if (!sent.compareAndSet(false, true)) throw new IllegalStateException("prepared request already sent");
            if (!accepting.get() || state != requiredState) throw new McpTransportException("SESSION_NOT_AVAILABLE", "MCP session is not in the required state");
            synchronized (pending) {
                if (pending.size() >= config.limits().maxPendingRequests())
                    throw new McpTransportException("MAX_PENDING_REQUESTS", "MCP pending request limit reached");
                if (pending.putIfAbsent(pendingRequest.id, pendingRequest) != null) throw new IllegalStateException("request id collision");
            }
            pendingRequest.timeout = scheduler.schedule(() -> timeout(pendingRequest), timeoutMillis, TimeUnit.MILLISECONDS);
            try { transport.writeLine(frame); }
            catch (McpTransportException e) {
                pending.remove(pendingRequest.id);
                complete(pendingRequest, new McpSessionResult(pendingRequest.id, McpSessionResult.Status.PROCESS_EXITED, null, null, e.category()));
                unhealthy(e.category(), e.getMessage()); throw e;
            }
            return pendingRequest.future;
        }
    }

    public void sendInitialized() throws McpTransportException, McpProtocolException {
        if (!accepting.get() || state != State.INITIALIZING)
            throw new McpTransportException("SESSION_NOT_INITIALIZING", "MCP session cannot send initialized notification");
        transport.writeLine(codec.notification("notifications/initialized", null));
    }

    public void markReady() throws McpTransportException {
        if (!accepting.get() || state != State.INITIALIZING)
            throw new McpTransportException("SESSION_NOT_INITIALIZING", "MCP session cannot enter READY");
        state = State.READY;
    }

    public void onNotification(Consumer<McpJsonRpcMessage.Notification> listener) {
        notificationListener = listener == null ? ignored -> {} : listener;
    }

    public void markUnhealthy(String category, String detail) {
        unhealthy(category, detail);
        failAll(McpSessionResult.Status.PROTOCOL_ERROR, category);
    }

    private CompletableFuture<McpSessionResult> sendRequest(String method, JsonNode params, Duration timeout,
                                                             boolean cancelOnTimeout, State requiredState,
                                                             long maximumTimeoutMillis)
            throws McpTransportException, McpProtocolException {
        if (!accepting.get() || state != requiredState) throw new McpTransportException("SESSION_NOT_AVAILABLE", "MCP session is not accepting this request");
        return prepareRequest(method, params, timeout, cancelOnTimeout, requiredState, maximumTimeoutMillis).send();
    }

    private PreparedRequest prepareRequest(String method, JsonNode params, Duration timeout,
                                           boolean cancelOnTimeout, State requiredState,
                                           long maximumTimeoutMillis)
            throws McpTransportException, McpProtocolException {
        if (!accepting.get() || state != requiredState) throw new McpTransportException("SESSION_NOT_AVAILABLE", "MCP session is not accepting this request");
        long timeoutMillis = timeout == null ? config.limits().requestTimeoutMs() : timeout.toMillis();
        if (timeoutMillis < 1 || timeoutMillis > maximumTimeoutMillis) throw new IllegalArgumentException("request timeout exceeds trusted limit");
        if (pending.size() >= config.limits().maxPendingRequests()) throw new McpTransportException("MAX_PENDING_REQUESTS", "MCP pending request limit reached");
        long id = ids.incrementAndGet();
        byte[] frame = codec.request(id, method, params);
        if (frame.length > config.limits().maxStdoutLineBytes()) throw new McpProtocolException("MESSAGE_TOO_LARGE", "JSON-RPC request exceeds configured limit");
        return new PreparedRequest(new Pending(id, cancelOnTimeout), frame, timeoutMillis, requiredState);
    }

    public long lastIssuedRequestId() { return ids.get(); }
    public int pendingCount() { return pending.size(); }
    public State state() { return state; }

    private void readLoop() {
        while (accepting.get()) {
            try { dispatch(codec.parse(transport.readLine())); }
            catch (McpTransportException e) { failAll(e.category().equals("PROCESS_EXITED") ? McpSessionResult.Status.PROCESS_EXITED : McpSessionResult.Status.PROTOCOL_ERROR, e.category()); unhealthy(e.category(), e.getMessage()); return; }
            catch (McpProtocolException e) { failAll(McpSessionResult.Status.PROTOCOL_ERROR, e.category()); unhealthy(e.category(), e.getMessage()); return; }
            catch (RuntimeException e) { failAll(McpSessionResult.Status.PROTOCOL_ERROR, "INTERNAL_PROTOCOL_ERROR"); unhealthy("INTERNAL_PROTOCOL_ERROR", "JSON-RPC reader failed"); return; }
        }
    }

    private void dispatch(McpJsonRpcMessage message) throws McpProtocolException, McpTransportException {
        if (message instanceof McpJsonRpcMessage.Response response) {
            Pending item = pending.remove(response.id());
            if (item == null) {
                String category = recentlyCompleted.containsKey(response.id()) ? "DUPLICATE_RESPONSE_ID" : "UNKNOWN_RESPONSE_ID";
                throw new McpProtocolException(category, "response id does not match a pending request");
            }
            if (item.timeout != null) item.timeout.cancel(false);
            complete(item, McpSessionResult.response(response));
        } else if (message instanceof McpJsonRpcMessage.ServerRequest request) {
            transport.writeLine(codec.methodNotSupported(request.id()));
        } else if (message instanceof McpJsonRpcMessage.Notification notification) {
            notificationListener.accept(notification);
        }
    }

    private void timeout(Pending item) {
        if (!pending.remove(item.id, item)) return;
        unhealthy("REQUEST_TIMEOUT", "MCP request timed out");
        if (item.cancelOnTimeout) {
            Thread notification=Thread.ofVirtual().name("mcp-timeout-notification").start(()->{
                try {
                    var params=codec.objectNode().put("requestId",item.id).put("reason","request timeout");
                    transport.writeLine(codec.notification("notifications/cancelled",params));
                } catch(Exception ignored) { }
            });
            try { notification.join(100); }
            catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        complete(item, new McpSessionResult(item.id, McpSessionResult.Status.TIMED_OUT, null, null, "REQUEST_TIMEOUT"));
        // Notification is best effort and bounded; shutdown unblocks any remaining pipe writer.
        transport.shutdown();
        failAll(McpSessionResult.Status.CANCELLED, "SESSION_UNHEALTHY");
    }

    private void complete(Pending item, McpSessionResult result) {
        recentlyCompleted.put(item.id, Boolean.TRUE);
        item.future.complete(result);
    }

    private void unhealthy(String category, String detail) {
        if (state == State.CLOSED) return;
        accepting.set(false);
        state = State.UNHEALTHY;
        unhealthyListener.accept(category, detail);
    }

    private void failAll(McpSessionResult.Status status, String category) {
        for (Pending item : pending.values()) {
            if (pending.remove(item.id, item)) {
                if (item.timeout != null) item.timeout.cancel(false);
                complete(item, new McpSessionResult(item.id, status, null, null, category));
            }
        }
    }

    @Override public void close() {
        accepting.set(false);
        failAll(McpSessionResult.Status.CANCELLED, "SESSION_CLOSED");
        state = State.CLOSED;
        transport.shutdown();
        scheduler.shutdownNow();
        reader.interrupt();
        try { reader.join(Math.min(1000, config.limits().shutdownTimeoutMs())); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static final class Pending {
        private final long id;
        private final boolean cancelOnTimeout;
        private final CompletableFuture<McpSessionResult> future = new CompletableFuture<>();
        private volatile ScheduledFuture<?> timeout;
        private Pending(long id, boolean cancelOnTimeout) { this.id = id; this.cancelOnTimeout = cancelOnTimeout; }
    }
}
