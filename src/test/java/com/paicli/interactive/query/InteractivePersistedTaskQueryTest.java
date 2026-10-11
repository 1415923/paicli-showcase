package com.paicli.interactive.query;

import com.paicli.agent.ToolFactProjection;
import com.paicli.interactive.runtime.InteractiveRuntimeView;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.model.AgentMessage;
import com.paicli.state.CodingRunStore;
import com.paicli.state.StateStore;
import com.paicli.state.TaskStore;
import com.paicli.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InteractivePersistedTaskQueryTest {
    @TempDir Path temp;

    @Test
    void latestReportProjectsAuthenticatedListFactsWithoutPrivateEnvelope() throws Exception {
        List<String> report=new java.util.ArrayList<>();
        appendListFiles(report,"call-root","README.md\ndocs/\nnotes.txt\n",3,1);
        appendListFiles(report,"call-docs","docs/guide.md\n",1,0);
        String text=String.join("\n",report);
        assertTrue(report.contains("    ├─ Succeeded · 3 visible line(s)"),text);
        assertTrue(report.contains("    ├─ Succeeded · 1 visible line(s)"),text);
        assertTrue(report.contains("    ├─ Excluded controls: 1"),text);
        assertTrue(report.contains("    │  .paicli/"),text);
        assertFalse(text.contains("[PAICLI_TOOL_FACTS]"),text);
        assertFalse(text.contains("factId")||text.contains("payloadDigest")
                ||text.contains("toolCallIdHash"),text);
    }

    @Test
    void latestReportDoesNotTreatMarkerShapedReadContentAsPrivateMetadata() throws Exception {
        String marker="[PAICLI_TOOL_FACTS] ordinary README content";
        List<String> lines=new java.util.ArrayList<>();
        StateStoreInteractiveQueryFacade.appendToolResultReplay(lines,
                AgentMessage.observationForToolCall(marker,"call-read"),
                new com.paicli.state.ToolCallStore.ToolCallRecord(1,"task",1,
                        "call-read","read_file","{}",true,marker,null,"now"));
        String report=String.join("\n",lines);
        assertTrue(report.contains(marker),report);
    }

    private static void appendListFiles(List<String> report,String callId,String output,
            int visibleCount,int excludedCount){
        ToolResult result=ToolFactProjection.bind(ToolResult.success(output,Map.of(
                ToolFactProjection.VISIBLE_ENTRY_COUNT,visibleCount,
                ToolFactProjection.EXCLUDED_CONTROL_COUNT,excludedCount,
                ToolFactProjection.EXCLUDED_CONTROLS,
                        excludedCount==0?"":".paicli/",
                ToolFactProjection.CONTROL_DIRECTORY_POLICY_APPLIED,true)),callId,
                "list_files");
        String durable=ToolFactProjection.appendCanonicalFacts(result.output(),result);
        StateStoreInteractiveQueryFacade.appendToolResultReplay(report,
                AgentMessage.observationForToolCall(durable,callId),
                new com.paicli.state.ToolCallStore.ToolCallRecord(1,"task",1,callId,
                        "list_files","{}",true,durable,null,"now"));
    }

    @Test
    void latestAndExplicitReportsResolveFromWorkspaceStoreNotSessionMemory() throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace);
        try (StateStore state = new StateStore(workspace)) {
            state.tasks.createTask("task-a", "first", "ollama", "model-a",
                    "on-request", 3);
            state.tasks.markDone("task-a");
            state.tasks.markInteractiveTerminal(
                    "task-a", TaskStore.STATUS_COMPLETED_UNVERIFIED);
            state.tasks.createTask("task-z", "latest", "openai", "model-z",
                    "on-request", 3);
            state.tasks.markRunning("task-z");
            state.codingRuns.create("run-z", "task-z", CodingRunStore.KIND_INITIAL,
                    null, 1, 0);
            state.interactiveTaskFacts.recordCodeIntelligence(
                    "task-z", "run-z", "USED", 7, 3, 2);
            state.codingRuns.markCancelled("run-z", false);
            state.tasks.markCancelled("task-z", false,
                    "Cancellation reason: user requested");
        }

        InteractiveSession session = session("session-1", workspace);
        StateStoreInteractiveQueryFacade query = new StateStoreInteractiveQueryFacade(
                workspace, new InteractiveRuntimeView());

        assertEquals("task-z", query.latestTask().orElseThrow().taskId());
        assertTrue(query.status(session.snapshot(), List.of("--details")).contains(
                "Latest persisted status: CANCELLED"));
        List<String> latest = query.report(session.snapshot(), "latest");
        assertTrue(latest.contains("  latest"));
        assertTrue(latest.contains("  [Task] Cancelled"));
        assertFalse(latest.stream().anyMatch(line->line.contains("task-z")));

        List<String> explicit = query.report(session.snapshot(), "task-a");
        assertEquals(List.of("Report selector invalid."), explicit);
        assertEquals(List.of("No active task report."),
                query.report(session.snapshot(), "current"));
    }

    @Test
    void aDifferentWorkspaceCannotResolvePersistedTaskIdentity() throws Exception {
        Path first = temp.resolve("first");
        Path second = temp.resolve("second");
        Files.createDirectories(first);
        Files.createDirectories(second);
        try (StateStore state = new StateStore(first)) {
            state.tasks.createTask("task-private", "private", "ollama", "model",
                    "on-request", 3);
            state.tasks.markDone("task-private");
        }

        StateStoreInteractiveQueryFacade query = new StateStoreInteractiveQueryFacade(
                second, new InteractiveRuntimeView());
        assertTrue(query.latestTask().isEmpty());
        assertEquals(List.of("No current or recent task report."),
                query.report(session("session-2", second).snapshot(), "latest"));
    }

    @Test
    void legacyCodeIntelligenceIsReconstructedOnlyFromTaskRunBoundAudits()
            throws Exception {
        Path workspace = temp.resolve("legacy-code-facts");
        Files.createDirectories(workspace);
        try (StateStore state = new StateStore(workspace)) {
            state.tasks.createTask("task-linked", "linked audit", "ollama", "model",
                    "on-request", 3);
            state.tasks.markRunning("task-linked");
            state.codingRuns.create("run-linked", "task-linked",
                    CodingRunStore.KIND_INITIAL, null, 1, 0);
            state.codeSearchAudits.startAudit("audit-linked",
                    com.paicli.state.CodeSearchAuditStore.TYPE_CONTEXT,
                    "bounded query", "task-linked", "run-linked");
            state.codeSearchAudits.completeAudit("audit-linked", 2,
                    false, com.paicli.state.CodeSearchAuditStore.CONFIDENCE_HIGH,
                    "context-hash", 1);
            state.codingRuns.markCompleted("run-linked");
            state.tasks.markDone("task-linked");
        }

        List<String> report = new StateStoreInteractiveQueryFacade(
                workspace, new InteractiveRuntimeView()).report(
                        session("session-linked", workspace).snapshot(), "task-linked");
        assertEquals(List.of("Report selector invalid."), report);
    }

    @Test
    void unlinkedLegacyCodeIntelligenceIsUnknownRatherThanNotUsed() throws Exception {
        Path workspace = temp.resolve("unknown-code-facts");
        Files.createDirectories(workspace);
        try (StateStore state = new StateStore(workspace)) {
            state.tasks.createTask("task-unknown", "unlinked audit", "ollama", "model",
                    "on-request", 3);
            state.tasks.markRunning("task-unknown");
            state.codingRuns.create("run-unknown", "task-unknown",
                    CodingRunStore.KIND_INITIAL, null, 1, 0);
            state.codeSearchAudits.startAudit("audit-unlinked",
                    com.paicli.state.CodeSearchAuditStore.TYPE_CONTEXT,
                    "bounded query");
            state.codeSearchAudits.completeAudit("audit-unlinked", 2,
                    false, com.paicli.state.CodeSearchAuditStore.CONFIDENCE_HIGH,
                    "context-hash", 1);
            state.codingRuns.markCompleted("run-unknown");
            state.tasks.markDone("task-unknown");
        }

        StateStoreInteractiveQueryFacade query = new StateStoreInteractiveQueryFacade(
                workspace, new InteractiveRuntimeView());
        List<String> report = query.report(
                session("session-unknown", workspace).snapshot(), "task-unknown");
        assertEquals(List.of("Report selector invalid."), report);
        assertTrue(query.status(session("status-unknown", workspace).snapshot(),
                List.of("--details")).contains(
                "Latest persisted Code Intelligence: UNKNOWN_LEGACY"));
    }

    @Test
    void reportUsesOnePersistedRepairCountingDefinitionAcrossRuns() throws Exception {
        Path workspace = temp.resolve("repairs");
        Files.createDirectories(workspace);
        try (StateStore state = new StateStore(workspace)) {
            state.tasks.createTask("task-repair", "repair", "ollama", "model",
                    "on-request", 4);
            state.tasks.markRunning("task-repair");

            state.codingRuns.create("run-first", "task-repair",
                    CodingRunStore.KIND_INITIAL, "MAVEN_TEST_V1", 2, 0);
            state.verifications.recordStarted("verify-first", "run-first",
                    "task-repair", 0, "MAVEN_TEST_V1","WORKSPACE_CONTENT_SHA256_V1",
                    "0".repeat(64));
            state.verifications.markFailed("verify-first", 1, "failed", null, 1);
            state.repairAttempts.recordStarted("repair-completed", "run-first",
                    "task-repair", 1, "verify-first", "failure");
            state.repairAttempts.markCompleted("repair-completed");
            state.codingRuns.markFailed("run-first", "interrupted after repair");

            state.codingRuns.create("run-resume", "task-repair",
                    CodingRunStore.KIND_RESUME, "MAVEN_TEST_V1", 2, 1);
            state.verifications.recordStarted("verify-resume", "run-resume",
                    "task-repair", 1, "MAVEN_TEST_V1","WORKSPACE_CONTENT_SHA256_V1",
                    "1".repeat(64));
            state.verifications.markFailed("verify-resume", 1, "failed", null, 1);
            state.repairAttempts.recordStarted("repair-cancelled", "run-resume",
                    "task-repair", 2, "verify-resume", "failure");
            state.codingRuns.markCancelled("run-resume", false);
            state.tasks.markCancelled("task-repair", false,
                    "Cancellation reason: user requested");
        }

        List<String> report = new StateStoreInteractiveQueryFacade(
                workspace, new InteractiveRuntimeView()).report(
                        session("session-repair", workspace).snapshot(), "latest");
        assertTrue(report.contains("──────────────── PaiCLI Report ─────────────────────────"));
        assertTrue(report.contains("  repair"));
        assertTrue(report.contains("  [Task] Cancelled"));
        assertTrue(report.stream().noneMatch(line -> line.contains("task-repair")
                || line.contains("run-resume")));
    }

    @Test
    void reportKeepsHistoricalVerificationAndMarksRewoundWorkspaceStale()
            throws Exception {
        Path workspace = temp.resolve("rewind-stale");
        Path source = workspace.resolve("Sample.java");
        Files.createDirectories(workspace);
        Files.writeString(source, "before");
        try (StateStore state = new StateStore(workspace)) {
            state.tasks.createTask("task-stale", "stale", "ollama", "model",
                    "on-request", 2);
            state.tasks.markRunning("task-stale");
            state.codingRuns.create("run-stale", "task-stale",
                    CodingRunStore.KIND_INITIAL, "MAVEN_TEST_V1", 1, 0);
            com.paicli.state.FileSnapshotService snapshots =
                    new com.paicli.state.FileSnapshotService(workspace, state.snapshots);
            String snapshotId = snapshots.captureBeforeWrite(
                    "task-stale", null, Path.of("Sample.java"));
            Files.writeString(source, "after");
            snapshots.markAppliedAfterWrite(snapshotId, Path.of("Sample.java"));
            state.verifications.recordStarted("verify-stale", "run-stale",
                    "task-stale", 0, "MAVEN_TEST_V1","WORKSPACE_CONTENT_SHA256_V1",
                    "2".repeat(64));
            state.verifications.markPassed("verify-stale", 0, "passed", 1);
            new com.paicli.state.RewindService(workspace, state.snapshots)
                    .rewind(snapshotId);
            state.codingRuns.markCompleted("run-stale");
            state.tasks.markDone("task-stale");
            state.tasks.markInteractiveTerminal(
                    "task-stale", TaskStore.STATUS_COMPLETED_VERIFIED);
        }

        StateStoreInteractiveQueryFacade query = new StateStoreInteractiveQueryFacade(
                workspace, new InteractiveRuntimeView());
        InteractiveSession session = session("session-stale", workspace);
        List<String> report = query.report(session.snapshot(), "latest");
        assertTrue(report.contains("──────────────── PaiCLI Report ─────────────────────────"));
        assertTrue(report.contains("  stale"));
        assertTrue(report.stream().anyMatch(line -> line.startsWith("  [Task] completed")));
        assertTrue(report.stream().noneMatch(line -> line.contains("task-stale")
                || line.contains("run-stale")));
        assertTrue(query.status(session.snapshot(), List.of("--details")).contains(
                "Current workspace verification: STALE_AFTER_REWIND"));
    }
    private static InteractiveSession session(String id, Path workspace) {
        InteractiveSession session = new InteractiveSession(id, workspace,
                "unconfigured", null, "on-request",
                InteractiveSession.TerminalMode.PLAIN, Instant.EPOCH);
        session.start();
        return session;
    }
}
