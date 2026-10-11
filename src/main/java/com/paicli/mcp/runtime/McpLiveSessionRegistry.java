package com.paicli.mcp.runtime;

import com.paicli.state.McpSessionStore;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class McpLiveSessionRegistry implements AutoCloseable {
    private final ConcurrentHashMap<String, McpRuntimeSessionHandle> handles = new ConcurrentHashMap<>();

    public void bind(McpRuntimeSessionHandle handle, McpSessionStore.SessionRecord persisted) {
        Objects.requireNonNull(handle); Objects.requireNonNull(persisted);
        if (!handle.sessionId().equals(persisted.sessionId())
                || !handle.processIdentityHash().equals(persisted.processIdentityHash())) {
            closeQuietly(handle);
            throw new IllegalStateException("LIVE_SESSION_IDENTITY_MISMATCH");
        }
        McpRuntimeSessionHandle existing = handles.putIfAbsent(handle.sessionId(), handle);
        if (existing != null) {
            if (existing != handle) closeQuietly(handle);
            throw new IllegalStateException("LIVE_SESSION_ALREADY_BOUND");
        }
    }

    public Optional<McpRuntimeSessionHandle> find(String sessionId) { return Optional.ofNullable(handles.get(sessionId)); }

    public McpRuntimeSessionHandle requireUsable(McpSessionStore.SessionRecord persisted) {
        if (!"READY".equals(persisted.status())) throw new IllegalStateException("PERSISTED_SESSION_NOT_READY");
        McpRuntimeSessionHandle handle = handles.get(persisted.sessionId());
        if (handle == null) throw new IllegalStateException("LIVE_SESSION_MISSING");
        if (!Objects.equals(handle.processIdentityHash(), persisted.processIdentityHash())) {
            handles.remove(persisted.sessionId(), handle); closeQuietly(handle);
            throw new IllegalStateException("LIVE_SESSION_IDENTITY_MISMATCH");
        }
        if (!handle.isAliveAndHealthy()) throw new IllegalStateException("LIVE_SESSION_UNHEALTHY");
        return handle;
    }

    public boolean closeSession(String sessionId) {
        McpRuntimeSessionHandle handle = handles.remove(sessionId);
        if (handle == null) return false;
        closeQuietly(handle); return true;
    }

    public int size() { return handles.size(); }
    @Override public void close() { closeAll(); }
    public void closeAll() {
        List<McpRuntimeSessionHandle> snapshot = new ArrayList<>(handles.values());
        handles.clear();
        snapshot.forEach(McpLiveSessionRegistry::closeQuietly);
    }
    private static void closeQuietly(McpRuntimeSessionHandle handle) { try { handle.close(); } catch (Exception ignored) { } }
}