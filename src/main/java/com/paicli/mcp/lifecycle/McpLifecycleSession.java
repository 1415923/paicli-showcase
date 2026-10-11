package com.paicli.mcp.lifecycle;

import com.paicli.mcp.config.McpServerConfig;
import com.paicli.mcp.jsonrpc.McpJsonRpcMessage;
import com.paicli.mcp.manifest.McpConfigManifest;
import com.paicli.mcp.transport.McpPersistedSession;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public final class McpLifecycleSession implements AutoCloseable {
    private final McpPersistedSession persisted;
    private final McpServerConfig config;
    private final McpConfigManifest manifest;
    private final boolean listChangedDeclared;
    private final String capabilitySummary;
    private final AtomicBoolean listChangedObserved = new AtomicBoolean();
    private final List<Consumer<McpJsonRpcMessage.Notification>> notificationListeners = new CopyOnWriteArrayList<>();

    McpLifecycleSession(McpPersistedSession persisted, McpServerConfig config, McpConfigManifest manifest,
                        boolean listChangedDeclared, String capabilitySummary) {
        this.persisted = persisted; this.config = config; this.manifest = manifest;
        this.listChangedDeclared = listChangedDeclared; this.capabilitySummary = capabilitySummary;
    }

    synchronized void acceptNotification(McpJsonRpcMessage.Notification notification) {
        if ("notifications/tools/list_changed".equals(notification.method())) {
            listChangedObserved.set(true);
            if (!listChangedDeclared) persisted.jsonRpc().markUnhealthy("UNDECLARED_LIST_CHANGED", "server sent undeclared tools/list_changed");
        }
        for (Consumer<McpJsonRpcMessage.Notification> listener : notificationListeners) listener.accept(notification);
    }

    public synchronized java.util.concurrent.CompletableFuture<com.paicli.mcp.jsonrpc.McpSessionResult>
    sendPreparedToolsCall(com.paicli.mcp.jsonrpc.McpJsonRpcSession.PreparedRequest request)
            throws com.paicli.mcp.transport.McpTransportException {
        if (listChangedObserved.get())
            throw new com.paicli.mcp.transport.McpTransportException("CATALOG_STALE", "catalog changed before tools/call send");
        return request.send();
    }

    public void addNotificationListener(Consumer<McpJsonRpcMessage.Notification> listener) {
        notificationListeners.add(listener);
    }
    public boolean listChangedObserved() { return listChangedObserved.get(); }
    public McpPersistedSession persisted() { return persisted; }
    public McpServerConfig config() { return config; }
    public McpConfigManifest manifest() { return manifest; }
    public boolean listChangedDeclared() { return listChangedDeclared; }
    public String capabilitySummary() { return capabilitySummary; }
    @Override public void close() throws Exception { persisted.close(); }
}
