package com.paicli.session;

import com.paicli.state.CodingRunStore;
import com.paicli.state.LeadDecisionStore;
import com.paicli.state.StateStore;
import com.paicli.state.SubagentRunStore;
import com.paicli.subagent.verdict.EvidenceSourceType;
import com.paicli.subagent.verdict.RequiredAction;
import com.paicli.subagent.verdict.Severity;
import com.paicli.subagent.verdict.StructuredVerdict;
import com.paicli.subagent.verdict.Verdict;
import com.paicli.subagent.verdict.VerdictEvidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SubagentReportServiceTest {

    @TempDir
    Path workspace;

    @Test
    void summarizesVerdictsDecisionsAndConfiguredVerificationWithoutPayloads()
            throws Exception {
        try (StateStore state = state()) {
            createCompletedRun(state, "accepted-run", "reviewer",
                    SubagentRunStore.KIND_REVIEWER, null);
            saveVerdict(state, "accepted-verdict", "accepted-run",
                    Verdict.INCONCLUSIVE, Severity.MEDIUM,
                    RequiredAction.HUMAN_REVIEW, "private-model-output");
            state.leadDecisions.saveLeadDecision(
                    "accepted-decision", "accepted-run", "accepted-verdict",
                    LeadDecisionStore.DECISION_ACCEPT, "private decision reason",
                    null, LeadDecisionStore.DECIDED_BY_USER);

            createCompletedRun(state, "rejected-run", "verifier",
                    SubagentRunStore.KIND_VERIFIER, "FAILED");
            saveVerdict(state, "rejected-verdict", "rejected-run",
                    Verdict.FAIL, Severity.HIGH, RequiredAction.FIX,
                    "full-verification-output");
            state.leadDecisions.saveLeadDecision(
                    "rejected-decision", "rejected-run", "rejected-verdict",
                    LeadDecisionStore.DECISION_REJECT, "private rejection reason",
                    null, LeadDecisionStore.DECIDED_BY_POLICY);

            createCompletedRun(state, "pending-run", "scout",
                    SubagentRunStore.KIND_SCOUT, null);
            saveVerdict(state, "pending-verdict", "pending-run",
                    Verdict.PASS, Severity.NONE, RequiredAction.NONE,
                    "untrusted-code-context");

            MarkdownReport.SubagentReportSummary summary =
                    new SubagentReportService(state).summarizeTask("task-001");

            assertEquals(3, summary.runs().size());
            assertEquals("INCONCLUSIVE", summary.runs().get(0).verdict());
            assertEquals("ACCEPT", summary.runs().get(0).leadDecision());
            assertEquals("REJECT", summary.runs().get(1).leadDecision());
            assertEquals("FAILED", summary.runs().get(1).verificationStatus());
            assertEquals("pending", summary.runs().get(2).leadDecision());
            assertFalse(summary.toString().contains("private-model-output"));
            assertFalse(summary.toString().contains("private decision reason"));
            assertFalse(summary.toString().contains("full-verification-output"));
            assertFalse(summary.toString().contains("untrusted-code-context"));
        }
    }

    @Test
    void keepsOnlyMostRecentBoundedRunSummaries() throws Exception {
        try (StateStore state = state()) {
            createCompletedRun(state, "run-1", "first",
                    SubagentRunStore.KIND_SCOUT, null);
            createCompletedRun(state, "run-2", "second",
                    SubagentRunStore.KIND_SCOUT, null);
            createCompletedRun(state, "run-3", "third",
                    SubagentRunStore.KIND_SCOUT, null);

            MarkdownReport.SubagentReportSummary summary =
                    new SubagentReportService(state).summarizeTask("task-001", 2);

            assertTrue(summary.truncated());
            assertEquals(List.of("second", "third"), summary.runs().stream()
                    .map(MarkdownReport.SubagentSummary::name).toList());
            assertThrows(IllegalArgumentException.class,
                    () -> new SubagentReportService(state)
                            .summarizeTask("task-001", 0));
        }
    }

    private StateStore state() throws Exception {
        StateStore state = new StateStore(workspace);
        state.tasks.createTask(
                "task-001", "review", "mock", null, "deny", 10);
        state.codingRuns.create(
                "coding-run", "task-001", CodingRunStore.KIND_INITIAL,
                null, 1, 0);
        return state;
    }

    private void createCompletedRun(
            StateStore state, String runId, String name, String kind,
            String verificationStatus) throws Exception {
        state.subagentRuns.createSubagentRun(
                runId, "task-001", "coding-run", name, kind,
                "REVIEW", "occurrence-" + runId, 1, "mock", true,
                "tools=[read_file]", "input-hash");
        if (verificationStatus == null) {
            state.subagentRuns.completeSubagentRun(runId, "output-hash");
        } else {
            state.subagentRuns.completeConfiguredVerification(
                    runId, "output-hash", verificationStatus);
        }
    }

    private void saveVerdict(
            StateStore state, String verdictId, String runId,
            Verdict verdict, Severity severity, RequiredAction action,
            String hiddenMessage) throws Exception {
        state.structuredVerdicts.saveStructuredVerdict(
                verdictId, runId,
                new StructuredVerdict(
                        verdict, severity, 0.7d,
                        List.of(new VerdictEvidence(
                                EvidenceSourceType.REVIEWER_MODEL,
                                "Example.java", 10, hiddenMessage)),
                        action, "hidden summary", true),
                "fixture", "raw-output-hash");
    }
}
