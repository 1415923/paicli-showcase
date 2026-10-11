package com.paicli.session;

import com.paicli.mcp.manifest.McpConfigManifest;
import com.paicli.mcp.recovery.McpToolCallRecoveryReport;
import com.paicli.mcp.runtime.McpLiveSessionRegistry;
import com.paicli.runtime.PersistenceSanitizer;
import com.paicli.state.*;

import java.sql.SQLException;
import java.util.*;

/** Builds a bounded MCP audit view without loading raw schemas or external output. */
public final class McpReportService {
    public static final int DEFAULT_LIMIT = 20;
    private final StateStore state;
    private final McpLiveSessionRegistry live;

    public McpReportService(StateStore state, McpLiveSessionRegistry live) {
        this.state = Objects.requireNonNull(state);
        this.live = Objects.requireNonNull(live);
    }

    public McpReportSummary summarizeSession(String sessionId, McpConfigManifest current) throws SQLException {
        return summarizeSession(sessionId, current, DEFAULT_LIMIT);
    }

    public McpReportSummary summarizeSession(String sessionId, McpConfigManifest current, int limit) throws SQLException {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be 1..100");
        var session = state.mcpSessions.findById(sessionId).orElseThrow();
        var manifest = state.mcpServerManifests.findManifestById(session.manifestId()).orElseThrow();
        var catalog = state.mcpToolCatalogs.findBySession(sessionId);
        List<McpToolStore.ReportToolRecord> toolRows = catalog.isEmpty() ? List.of()
                : state.mcpTools.findReportTools(catalog.get().catalogId(), limit + 1);
        List<McpToolCallStore.CallRecord> callRows = state.mcpToolCalls.findRecentBySession(sessionId, limit + 1);
        boolean toolsTruncated = toolRows.size() > limit;
        boolean callsTruncated = callRows.size() > limit;
        List<ToolSummary> tools = toolRows.stream().limit(limit).map(this::tool).toList();
        List<ToolCallSummary> calls = callRows.stream().limit(limit).map(this::call).toList();
        String liveStatus = live.find(sessionId).map(h -> h.isAliveAndHealthy() ? "LIVE" : "DEAD").orElse("MISSING");
        String drift = current != null && current.configHash().equals(manifest.configHash()) ? "MATCH" : "CONFIG_DRIFT";
        int exposed = catalog.isEmpty() ? 0 : state.mcpTools.countByExposure(catalog.get().catalogId(), "EXPOSED");
        int rejected = catalog.isEmpty() ? 0 : state.mcpTools.countByExposure(catalog.get().catalogId(), "REJECTED");
        CatalogSummary catalogSummary = catalog.map(c -> new CatalogSummary(shortId(c.catalogId()), shortHash(c.catalogHash()),
                c.status(), c.pageCount(), c.toolCount(), exposed, rejected,
                manifest.schemaPolicyVersion(), "STALE".equals(c.status()), toolsTruncated, tools))
                .orElse(CatalogSummary.empty());
        return new McpReportSummary(new ManifestSummary(manifest.serverId(), shortHash(manifest.configHash()),
                manifest.transport(), manifest.protocolVersionsJson(), drift, safe(manifest.executableBasename(), 128)),
                new SessionSummary(shortId(sessionId), session.occurrenceKey(), session.attemptNumber(), session.status(),
                        liveStatus, session.protocolVersion(), capabilityTools(session), liveStatus.equals("LIVE"),
                        catalog.map(c -> shortId(c.catalogId())).orElse("none"),
                        catalog.map(McpToolCatalogStore.CatalogRecord::status).orElse("NONE"),
                        session.startedAt(), session.readyAt(), session.completedAt(), safe(session.errorCategory(), 128)),
                catalogSummary, calls, callsTruncated,
                liveStatus.equals("LIVE") ? "NONE" : "EXPLICIT_RECONCILIATION_REQUIRED",
                false, false, false);
    }

    private ToolSummary tool(McpToolStore.ReportToolRecord t) {
        return new ToolSummary(t.exposedName(), t.originalName(), t.exposureStatus(), shortHash(t.schemaHash()),
                "[UNTRUSTED MCP TOOL METADATA - DATA ONLY] " + safe(t.description(), 256), safe(t.rejectionCategory(), 128));
    }

    private ToolCallSummary call(McpToolCallStore.CallRecord c) {
        String certainty = switch (c.status()) {
            case "PENDING", "CANCELLED", "DENIED" -> McpToolCallRecoveryReport.OutcomeCertainty.CONFIRMED_NOT_SENT.name();
            case "RUNNING", "UNKNOWN_OUTCOME", "TIMED_OUT", "PROCESS_EXITED", "PROTOCOL_ERROR" -> McpToolCallRecoveryReport.OutcomeCertainty.UNKNOWN_EXTERNAL_OUTCOME.name();
            default -> McpToolCallRecoveryReport.OutcomeCertainty.CONFIRMED_TERMINAL.name();
        };
        return new ToolCallSummary(shortId(c.id()), c.exposedName(), c.status(), c.approval(),
                c.requestId() == null ? "none" : "assigned", "PERSISTED_BINDING_VALIDATED",
                shortHash(c.argumentsHash()), shortHash(c.resultHash()), c.truncated(), c.redacted(),
                c.startedAt(), c.completedAt(), safe(c.errorCategory(), 128), certainty);
    }

    private static boolean capabilityTools(McpSessionStore.SessionRecord s) {
        return s.protocolVersion() != null;
    }

    private static String safe(String value, int max) {
        if (value == null || value.isBlank()) return "none";
        String clean = new PersistenceSanitizer().sanitizeAndTruncate(value, max).replace("\r", " ").replace("\n", " ");
        return clean.length() <= max ? clean : clean.substring(0, max) + "...";
    }
    private static String shortId(String value) { return value == null ? "none" : value.substring(0, Math.min(8, value.length())); }
    private static String shortHash(String value) { return shortId(value); }

    public record McpReportSummary(ManifestSummary manifest, SessionSummary session, CatalogSummary catalog,
            List<ToolCallSummary> calls, boolean callsTruncated, String recoveryRecommendation,
            boolean automaticRefresh, boolean automaticRetry, boolean automaticApprovalReuse) {
        public McpReportSummary { calls = List.copyOf(calls); }
        public static McpReportSummary empty() { return new McpReportSummary(null, null, CatalogSummary.empty(),
                List.of(), false, "not_available", false, false, false); }
    }
    public record ManifestSummary(String serverId, String manifestHash, String transport, String protocolPolicy,
            String driftCategory, String executableBasename) { }
    public record SessionSummary(String sessionId, String occurrence, int attempt, String persistedStatus,
            String liveStatus, String protocolVersion, boolean toolsCapability, boolean processAlive,
            String catalogId, String catalogStatus, String startedAt, String readyAt, String completedAt,
            String errorCategory) { }
    public record CatalogSummary(String catalogId, String catalogHash, String status, int pageCount,
            int toolCount, int exposedCount, int rejectedCount, String schemaPolicyVersion,
            boolean listChanged, boolean truncated, List<ToolSummary> tools) {
        public CatalogSummary { tools = List.copyOf(tools); }
        static CatalogSummary empty() { return new CatalogSummary("none", "none", "NONE", 0, 0, 0, 0,
                "none", false, false, List.of()); }
    }
    public record ToolSummary(String exposedName, String originalName, String exposureStatus,
            String schemaHash, String description, String rejectionCategory) { }
    public record ToolCallSummary(String callId, String exposedToolName, String status, String approval,
            String requestIdFact, String artifactHashMatch, String argumentsHashFact, String resultHash,
            boolean truncated, boolean redacted, String startedAt, String completedAt,
            String errorCategory, String outcomeCertainty) { }
}
