package com.paicli.mcp.runtime;

import com.paicli.state.McpSessionStore;
import com.paicli.state.McpToolCallStore;

import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class McpRuntimeSupervisor implements AutoCloseable {
    private final McpLiveSessionRegistry registry;
    private final McpSessionStore sessions;
    private final McpToolCallStore calls;
    private final AtomicBoolean closed = new AtomicBoolean();

    public McpRuntimeSupervisor(McpLiveSessionRegistry registry, McpSessionStore sessions, McpToolCallStore calls) {
        this.registry=Objects.requireNonNull(registry); this.sessions=Objects.requireNonNull(sessions); this.calls=Objects.requireNonNull(calls);
    }

    public void reconcileLostProcess(String sessionId) throws SQLException {
        var session=sessions.findById(sessionId).orElseThrow();
        if (registry.find(sessionId).map(McpRuntimeSessionHandle::isAliveAndHealthy).orElse(false)) return;
        for (var call:calls.findResiduesBySession(sessionId)) {
            if ("PENDING".equals(call.status())) calls.cancelPendingForRecovery(call.id());
            else calls.markUnknownOutcome(call.id());
        }
        if (SetLike.live(session.status())) sessions.failResidue(sessionId,session.status(),"LIVE_SESSION_LOST");
    }

    public void closeSession(String sessionId) throws SQLException {
        registry.closeSession(sessionId);
        var session=sessions.findById(sessionId).orElseThrow();
        if (SetLike.live(session.status())) sessions.failResidue(sessionId,session.status(),"EXPLICIT_RUNTIME_CLOSE");
    }

    public void closeAll() {
        if (!closed.compareAndSet(false,true)) return;
        registry.closeAll();
    }
    @Override public void close(){closeAll();}
    private static final class SetLike { static boolean live(String s){return "STARTING".equals(s)||"INITIALIZING".equals(s)||"READY".equals(s)||"SHUTTING_DOWN".equals(s);} }
}