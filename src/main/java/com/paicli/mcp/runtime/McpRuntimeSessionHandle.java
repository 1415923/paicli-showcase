package com.paicli.mcp.runtime;

import com.paicli.mcp.jsonrpc.McpJsonRpcSession;
import com.paicli.mcp.lifecycle.McpLifecycleSession;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class McpRuntimeSessionHandle implements AutoCloseable {
    private final String sessionId;
    private final String processIdentityHash;
    private final String manifestHash;
    private final String catalogId;
    private final String catalogHash;
    private final McpLifecycleSession lifecycle;
    private final Instant createdAt = Instant.now();
    private final AtomicBoolean closing = new AtomicBoolean();

    public McpRuntimeSessionHandle(String sessionId, String processIdentityHash, String manifestHash,
                                   String catalogId, String catalogHash, McpLifecycleSession lifecycle) {
        this.sessionId = Objects.requireNonNull(sessionId);
        this.processIdentityHash = Objects.requireNonNull(processIdentityHash);
        this.manifestHash = Objects.requireNonNull(manifestHash);
        this.catalogId = catalogId;
        this.catalogHash = catalogHash;
        this.lifecycle = Objects.requireNonNull(lifecycle);
    }

    public String sessionId() { return sessionId; }
    public String processIdentityHash() { return processIdentityHash; }
    public String manifestHash() { return manifestHash; }
    public String catalogId() { return catalogId; }
    public String catalogHash() { return catalogHash; }
    public Instant createdAt() { return createdAt; }
    public boolean closing() { return closing.get(); }
    public boolean isAliveAndHealthy() {
        return !closing.get() && lifecycle.persisted().isProcessAlive()
                && lifecycle.persisted().jsonRpc().state() == McpJsonRpcSession.State.READY;
    }
    public McpLifecycleSession lifecycle() { return lifecycle; }

    @Override public void close() throws Exception {
        if (closing.compareAndSet(false, true)) lifecycle.close();
    }
}