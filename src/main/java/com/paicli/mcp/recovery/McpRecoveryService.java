package com.paicli.mcp.recovery;

import com.paicli.mcp.config.McpServerConfig;
import com.paicli.mcp.manifest.*;
import com.paicli.mcp.runtime.*;
import com.paicli.state.*;

import java.sql.SQLException;
import java.util.*;

public final class McpRecoveryService {
    private static final Set<String> TERMINAL_CALLS = Set.of("DENIED","SUCCEEDED","TOOL_ERROR","TIMED_OUT","PROTOCOL_ERROR","PROCESS_EXITED","SANITIZER_REJECTED","CANCELLED","UNKNOWN_OUTCOME");
    private final McpSessionStore sessions; private final McpToolCallStore calls;
    private final McpToolCatalogStore catalogs; private final McpLiveSessionRegistry live;
    private final McpDriftGuard driftGuard = new McpDriftGuard();

    public McpRecoveryService(McpSessionStore sessions, McpToolCallStore calls,
                              McpToolCatalogStore catalogs, McpLiveSessionRegistry live) {
        this.sessions=Objects.requireNonNull(sessions); this.calls=Objects.requireNonNull(calls);
        this.catalogs=Objects.requireNonNull(catalogs); this.live=Objects.requireNonNull(live);
    }

    public McpSessionRecoveryReport inspectRecovery(String sessionId, McpServerConfig current,
                                                     McpConfigManifest bound) throws SQLException {
        var session=sessions.findById(sessionId).orElseThrow();
        String drift;
        try { driftGuard.requireManifestMatch(current,bound); drift="MATCH"; }
        catch (IllegalStateException e) { drift=e.getMessage(); }
        var handle=live.find(sessionId);
        String liveStatus=handle.isEmpty()?"MISSING":handle.get().isAliveAndHealthy()?"LIVE":"UNHEALTHY";
        boolean alive=handle.map(McpRuntimeSessionHandle::isAliveAndHealthy).orElse(false);
        String catalog=catalogs.findBySession(sessionId).map(McpToolCatalogStore.CatalogRecord::status).orElse("NONE");
        String recommendation=alive&&"MATCH".equals(drift)?"NONE":"EXPLICIT_RECONCILIATION_REQUIRED";
        return new McpSessionRecoveryReport(session.serverId(),McpSessionRecoveryReport.shortId(sessionId),session.status(),liveStatus,drift,alive,catalog,recommendation);
    }

    public List<McpToolCallRecoveryReport> reconcileInterruptedSession(String sessionId,
                                                                       McpServerConfig current,
                                                                       McpConfigManifest bound) throws SQLException {
        var session=sessions.findById(sessionId).orElseThrow();
        driftGuard.requireManifestMatch(current,bound);
        if (live.find(sessionId).map(McpRuntimeSessionHandle::isAliveAndHealthy).orElse(false))
            throw new IllegalStateException("LIVE_SESSION_STILL_ACTIVE");
        List<McpToolCallRecoveryReport> reports=reconcileToolCallResidues(sessionId);
        switch(session.status()) {
            case "CONFIGURED","STARTING","INITIALIZING" -> sessions.failResidue(sessionId,session.status(),"RUNTIME_RESTART");
            case "READY" -> sessions.failResidue(sessionId,"READY","LIVE_SESSION_LOST");
            case "SHUTTING_DOWN" -> sessions.closeShuttingDownResidue(sessionId,"RUNTIME_RESTART_AFTER_SHUTDOWN");
            case "UNHEALTHY","CLOSED","FAILED" -> { }
            default -> throw new IllegalStateException("UNKNOWN_SESSION_STATUS");
        }
        return reports;
    }

    public List<McpToolCallRecoveryReport> reconcileToolCallResidues(String sessionId) throws SQLException {
        List<McpToolCallRecoveryReport> out=new ArrayList<>();
        for (var call:calls.findResiduesBySession(sessionId)) {
            if ("PENDING".equals(call.status())) calls.cancelPendingForRecovery(call.id());
            else calls.markUnknownOutcome(call.id());
            out.add(report(calls.findById(call.id()).orElseThrow()));
        }
        return List.copyOf(out);
    }

    public McpToolCallRecoveryReport inspectToolCall(String callId) throws SQLException {
        return report(calls.findById(callId).orElseThrow());
    }

    private static McpToolCallRecoveryReport report(McpToolCallStore.CallRecord call) {
        var certainty = switch(call.status()) {
            case "PENDING","CANCELLED","DENIED" -> McpToolCallRecoveryReport.OutcomeCertainty.CONFIRMED_NOT_SENT;
            case "RUNNING","UNKNOWN_OUTCOME","TIMED_OUT","PROCESS_EXITED","PROTOCOL_ERROR" -> McpToolCallRecoveryReport.OutcomeCertainty.UNKNOWN_EXTERNAL_OUTCOME;
            default -> TERMINAL_CALLS.contains(call.status()) ? McpToolCallRecoveryReport.OutcomeCertainty.CONFIRMED_TERMINAL : McpToolCallRecoveryReport.OutcomeCertainty.UNKNOWN_EXTERNAL_OUTCOME;
        };
        return new McpToolCallRecoveryReport(McpSessionRecoveryReport.shortId(call.id()),call.tool(),call.status(),certainty,call.approval(),call.errorCategory(),false);
    }
}
