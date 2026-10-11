package com.paicli.mcp.transport;

import com.paicli.mcp.config.McpServerConfig;
import com.paicli.mcp.jsonrpc.McpJsonRpcSession;
import com.paicli.mcp.manifest.McpConfigManifest;
import com.paicli.state.McpSessionStore;

import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class McpPersistedSession implements AutoCloseable {
    private final String sessionId;
    private final McpSessionStore store;
    private final McpStdioTransport transport;
    private final McpJsonRpcSession session;
    private final AtomicBoolean closed = new AtomicBoolean();

    private McpPersistedSession(String sessionId, McpSessionStore store, McpStdioTransport transport,
                                McpJsonRpcSession session) {
        this.sessionId = sessionId;
        this.store = store;
        this.transport = transport;
        this.session = session;
    }

    public static McpPersistedSession start(String sessionId, String manifestId, String runScope,
                                             String occurrence, int attempt, McpServerConfig config,
                                             McpConfigManifest persisted, McpSessionStore store,
                                             Map<String, String> hostEnvironment)
            throws SQLException, McpTransportException {
        if (!store.manifestMatches(manifestId, config.serverId(), persisted.configHash(), persisted.executableContentHash())) {
            throw new McpTransportException("CONFIG_DRIFT", "specified persisted manifest does not match trusted MCP configuration snapshot");
        }
        store.createSession(sessionId, manifestId, config.serverId(), runScope, occurrence, attempt);
        store.markStarting(sessionId);
        final McpStdioTransport transport;
        try { transport = McpStdioTransport.start(config, persisted, hostEnvironment); }
        catch (McpTransportException e) { store.markFailed(sessionId, e.category(), e.getMessage()); throw e; }
        try {
            store.markInitializing(sessionId, transport.processIdentityHash());
            McpJsonRpcSession session = new McpJsonRpcSession(transport, config, (category, detail) -> {
                try { store.markUnhealthy(sessionId, category, detail); }
                catch (SQLException ignored) { /* Transport remains fail-closed even if audit persistence fails. */ }
            });
            return new McpPersistedSession(sessionId, store, transport, session);
        } catch (SQLException | RuntimeException e) {
            transport.shutdown();
            try { store.markFailed(sessionId, "SESSION_INITIALIZATION_FAILED", "unable to initialize MCP session"); }
            catch (SQLException ignored) { }
            throw e;
        }
    }

    public McpJsonRpcSession jsonRpc() { return session; }
    public String sessionId() { return sessionId; }
    public McpSessionStore store() { return store; }
    public boolean isProcessAlive() { return transport.isAlive(); }
    public String processIdentityHash() { return transport.processIdentityHash(); }

    public void markReady(String protocolVersion, String serverInfoHash, String capabilitySummary) throws SQLException, McpTransportException {
        store.markReady(sessionId, protocolVersion, serverInfoHash, capabilitySummary);
        session.markReady();
    }

    @Override public void close() throws SQLException {
        if (!closed.compareAndSet(false, true)) return;
        boolean persistClosed = true;
        try {
        try { store.markShuttingDown(sessionId); }
        catch (SQLException transitionFailure) {
            var current = store.findById(sessionId);
            if (current.isEmpty() || !("FAILED".equals(current.get().status()) || "CLOSED".equals(current.get().status()))) {
                throw transitionFailure;
            }
            persistClosed = false;
        }
        } finally { session.close(); }
        if (persistClosed) {
            McpStderrSummary stderr = transport.stderrSummary();
            store.markClosed(sessionId, transport.exitCode(), stderr.hash(), stderr.excerpt());
        }
    }
}
