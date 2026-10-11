package com.paicli.mcp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.paicli.mcp.approval.*;
import com.paicli.mcp.catalog.*;
import com.paicli.mcp.config.McpServerConfig;
import com.paicli.mcp.lifecycle.*;
import com.paicli.mcp.manifest.*;
import com.paicli.mcp.recovery.*;
import com.paicli.mcp.tool.*;
import com.paicli.safety.ApprovalPolicy;
import com.paicli.session.McpReportService;
import com.paicli.state.*;
import com.paicli.tool.ToolRegistry;

import java.util.*;

/** High-level MCP client composition that never exposes transport or process handles. */
public final class McpClientRuntime implements AutoCloseable {
    private final StateStore state;
    private final McpLiveSessionRegistry live = new McpLiveSessionRegistry();
    private final McpLifecycleService lifecycle = new McpLifecycleService();
    private final McpToolCatalogService catalogs;
    private final McpCatalogRefreshService refresh;
    private final McpRecoveryService recovery;
    private final Map<String, McpServerConfig> trustedConfigs = new java.util.concurrent.ConcurrentHashMap<>();

    public McpClientRuntime(StateStore state) {
        this.state = Objects.requireNonNull(state);
        this.catalogs = new McpToolCatalogService(state.mcpToolCatalogs, state.mcpTools);
        this.refresh = new McpCatalogRefreshService(lifecycle, catalogs, state.mcpServerManifests,
                state.mcpSessions, state.mcpToolCatalogs, live);
        this.recovery = new McpRecoveryService(state.mcpSessions, state.mcpToolCalls,
                state.mcpToolCatalogs, live);
    }

    public StartedSession startTrustedSession(McpServerConfig config, String runScope,
            String occurrence, int attempt, Map<String, String> hostEnvironment) throws Exception {
        McpConfigManifest manifest = new McpConfigManifestBuilder().build(Objects.requireNonNull(config));
        var stored = state.mcpServerManifests.saveOrFindManifest(manifest);
        String sessionId = UUID.randomUUID().toString();
        McpLifecycleSession session = lifecycle.initialize(sessionId, stored.manifestId(), runScope,
                occurrence, attempt, config, manifest, state.mcpSessions, hostEnvironment);
        try {
            McpCatalogBuildResult built = catalogs.build(session);
            var persisted = state.mcpSessions.findById(sessionId).orElseThrow();
            live.bind(new McpRuntimeSessionHandle(sessionId, persisted.processIdentityHash(),
                    manifest.configHash(), built.catalog().catalogId(), built.catalog().catalogHash(), session), persisted);
            trustedConfigs.put(sessionId, config);
            return new StartedSession(sessionId, built.catalog().catalogId(), manifest.configHash(),
                    built.catalog().catalogHash());
        } catch (Exception e) {
            try { session.close(); } catch (Exception close) { e.addSuppressed(close); }
            throw e;
        }
    }

    public ToolRegistry deriveToolRegistry(String sessionId, ToolRegistry parent,
            ApprovalPolicy policy, McpApprovalChannel channel, String occurrencePrefix) throws Exception {
        var handle = requireHandle(sessionId);
        var calls = new McpToolCallService(handle.lifecycle(), state.mcpToolCatalogs,
                state.mcpTools, state.mcpToolCalls, new McpApprovalGate(policy, channel), live);
        return new McpToolRegistryAdapter(state.mcpToolCatalogs, state.mcpTools, live)
                .derive(parent, handle.lifecycle(), calls, occurrencePrefix);
    }

    public McpToolExecutionResult executeApprovedTool(String sessionId, String exposedName,
            JsonNode arguments, ApprovalPolicy policy, McpApprovalChannel channel,
            String occurrence, int attempt) throws Exception {
        var handle = requireHandle(sessionId);
        var catalog = state.mcpToolCatalogs.findFrozenCatalog(sessionId)
                .orElseThrow(() -> new IllegalStateException("MCP catalog is not FROZEN"));
        var tool = state.mcpTools.findByExposedName(catalog.catalogId(), exposedName)
                .orElseThrow(() -> new IllegalArgumentException("MCP tool is not exposed"));
        return new McpToolCallService(handle.lifecycle(), state.mcpToolCatalogs, state.mcpTools,
                state.mcpToolCalls, new McpApprovalGate(policy, channel), live)
                .execute(tool, arguments, occurrence, attempt, null, null);
    }

    public McpSessionRecoveryReport inspectRecovery(String sessionId, McpServerConfig currentConfig) throws Exception {
        return recovery.inspectRecovery(sessionId, currentConfig,
                new McpConfigManifestBuilder().build(currentConfig));
    }

    public List<McpToolCallRecoveryReport> reconcileMetadata(String sessionId,
            McpServerConfig currentConfig) throws Exception {
        return recovery.reconcileInterruptedSession(sessionId, currentConfig,
                new McpConfigManifestBuilder().build(currentConfig));
    }

    public McpSessionRecoveryReport inspectRecovery(String sessionId) throws Exception {
        McpServerConfig config = requireTrustedConfig(sessionId);
        return recovery.inspectRecovery(sessionId, config, new McpConfigManifestBuilder().build(config));
    }

    public List<McpToolCallRecoveryReport> reconcileMetadata(String sessionId) throws Exception {
        McpServerConfig config = requireTrustedConfig(sessionId);
        return recovery.reconcileInterruptedSession(sessionId, config, new McpConfigManifestBuilder().build(config));
    }

    public McpCatalogRefreshService.RefreshResult refreshCatalogExplicitly(String staleCatalogId,
            String sessionId, String runScope, String occurrence, int attempt,
            Map<String, String> hostEnvironment) throws Exception {
        McpServerConfig config = requireTrustedConfig(sessionId);
        var result = refresh.refreshCatalogExplicitly(staleCatalogId, config, runScope, occurrence,
                attempt, hostEnvironment);
        trustedConfigs.put(result.newSessionId(), config);
        return result;
    }

    public McpReportService.McpReportSummary summarizeSession(String sessionId, int limit) throws Exception {
        McpServerConfig config = requireTrustedConfig(sessionId);
        return new McpReportService(state, live).summarizeSession(sessionId,
                new McpConfigManifestBuilder().build(config), limit);
    }

    public boolean closeSession(String sessionId) {
        trustedConfigs.remove(sessionId);
        return live.closeSession(sessionId);
    }

    public int liveSessionCount() { return live.size(); }
    public boolean isUsable(String sessionId) {
        return live.find(sessionId).map(handle -> handle.isAliveAndHealthy()
                && !handle.lifecycle().listChangedObserved()).orElse(false);
    }
    @Override public void close() { trustedConfigs.clear(); live.closeAll(); }

    private McpRuntimeSessionHandle requireHandle(String sessionId) throws Exception {
        return live.requireUsable(state.mcpSessions.findById(sessionId).orElseThrow());
    }

    private McpServerConfig requireTrustedConfig(String sessionId) {
        McpServerConfig config = trustedConfigs.get(sessionId);
        if (config == null) throw new IllegalStateException("trusted MCP config is unavailable for session");
        return config;
    }

    public record StartedSession(String sessionId, String catalogId, String manifestHash, String catalogHash) { }
}
