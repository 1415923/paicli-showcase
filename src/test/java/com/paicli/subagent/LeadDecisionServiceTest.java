package com.paicli.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.Config;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.ModelProvider;
import com.paicli.safety.ApprovalPolicy;
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
import com.paicli.subagent.verdict.VerdictExtractionMode;
import com.paicli.tool.Tool;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LeadDecisionServiceTest {

    private static final String TASK_ID = "task-lead";
    private static final String CODING_RUN_ID = "run-lead";
    private static final String ORIGINAL_RUN_ID = "sub-original";
    private static final String ORIGINAL_VERDICT_ID = "verdict-original";

    @TempDir
    Path workspace;

    private StateStore stateStore;
    private ToolRegistry parentTools;
    private SubagentRunner runner;
    private LeadDecisionService service;

    @BeforeEach
    void setUp() throws Exception {
        stateStore = new StateStore(workspace);
        stateStore.tasks.createTask(
                TASK_ID, "review code", "mock", null, Config.MODE_READ_ONLY, 10);
        stateStore.codingRuns.create(
                CODING_RUN_ID, TASK_ID, CodingRunStore.KIND_INITIAL, null, 1, 0);
        parentTools = new ToolRegistry();
        parentTools.register(new NoOpTool("read_file"));
        Config config = new Config(
                ".", 30, 50_000, 200_000, Config.MODE_READ_ONLY, 10_000);
        runner = new SubagentRunner(
                stateStore, parentTools, new ApprovalPolicy(config, TASK_ID));
        service = new LeadDecisionService(stateStore, runner);
        createSucceededVerdict(
                ORIGINAL_RUN_ID, ORIGINAL_VERDICT_ID,
                Verdict.FAIL, "original verdict", true);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (stateStore != null) {
            stateStore.close();
        }
    }

    @Test
    void acceptPersistsAndIncludesVerdictInAcceptedView() {
        var result = service.accept(
                ORIGINAL_VERDICT_ID, LeadDecisionStore.DECIDED_BY_RUNTIME,
                "evidence is actionable");

        assertEquals(LeadDecisionStore.DECISION_ACCEPT, result.decision().decision());
        var views = service.viewsForTask(TASK_ID);
        assertEquals(1, views.accepted().size());
        assertTrue(views.rejected().isEmpty());
        assertTrue(views.pending().isEmpty());
    }

    @Test
    void rejectPersistsAndExcludesVerdictFromAcceptedView() {
        service.reject(
                ORIGINAL_VERDICT_ID, LeadDecisionStore.DECIDED_BY_USER,
                "evidence is insufficient");

        var views = service.viewsForTask(TASK_ID);
        assertTrue(views.accepted().isEmpty());
        assertEquals(1, views.rejected().size());
    }

    @Test
    void requestRecheckCreatesNewRunAndVerdictWithoutOverwritingOriginal() throws Exception {
        SubagentRunRequest request = request(
                "ignored-by-service",
                new SequenceProvider(ModelResponse.finalAnswer(validMarker("rechecked"))));

        var result = service.requestRecheck(
                ORIGINAL_VERDICT_ID, LeadDecisionStore.DECIDED_BY_POLICY,
                "fresh review required", request);

        assertEquals(LeadDecisionStore.DECISION_REQUEST_RECHECK,
                result.decision().decision());
        assertNotNull(result.recheckResult());
        assertEquals(SubagentRunStore.STATUS_SUCCEEDED, result.recheckResult().status());
        assertNotEquals(ORIGINAL_RUN_ID, result.recheckResult().subagentRunId());
        assertNotEquals(ORIGINAL_VERDICT_ID, result.recheckResult().verdictId());
        assertEquals(2, result.recheckResult().attemptNumber());
        assertTrue(stateStore.structuredVerdicts
                .findVerdictById(ORIGINAL_VERDICT_ID).isPresent());
        var views = service.viewsForTask(TASK_ID);
        assertEquals(1, views.recheckRequested().size());
        assertEquals(1, views.pending().size());
    }

    @Test
    void failedRecheckKeepsOriginalVerdictAndDecision() throws Exception {
        ModelProvider failingProvider = (messages, tools) -> {
            throw new IllegalStateException("provider unavailable");
        };

        var result = service.requestRecheck(
                ORIGINAL_VERDICT_ID, LeadDecisionStore.DECIDED_BY_USER,
                "retry once", request("failed-recheck", failingProvider));

        assertEquals(SubagentRunStore.STATUS_FAILED, result.recheckResult().status());
        assertTrue(stateStore.structuredVerdicts
                .findVerdictById(ORIGINAL_VERDICT_ID).isPresent());
        assertEquals(LeadDecisionStore.DECISION_REQUEST_RECHECK,
                stateStore.leadDecisions.findDecisionByVerdictId(
                        ORIGINAL_VERDICT_ID).orElseThrow().decision());
    }

    @Test
    void duplicateDecisionIsIdempotentAndConflictFailsClosed() {
        var first = service.accept(
                ORIGINAL_VERDICT_ID, LeadDecisionStore.DECIDED_BY_RUNTIME, "accepted");
        var repeated = service.accept(
                ORIGINAL_VERDICT_ID, LeadDecisionStore.DECIDED_BY_USER, "repeat");

        assertTrue(repeated.idempotent());
        assertEquals(first.decision().decisionId(), repeated.decision().decisionId());
        assertThrows(IllegalStateException.class, () -> service.reject(
                ORIGINAL_VERDICT_ID, LeadDecisionStore.DECIDED_BY_USER, "conflict"));
    }

    @Test
    void duplicateRecheckDecisionDoesNotRunAgain() {
        SequenceProvider firstProvider = new SequenceProvider(
                ModelResponse.finalAnswer(validMarker("first recheck")));
        var first = service.requestRecheck(
                ORIGINAL_VERDICT_ID, LeadDecisionStore.DECIDED_BY_USER,
                "recheck", request("first", firstProvider));
        SequenceProvider secondProvider = new SequenceProvider(
                ModelResponse.finalAnswer(validMarker("must not run")));

        var repeated = service.requestRecheck(
                ORIGINAL_VERDICT_ID, LeadDecisionStore.DECIDED_BY_POLICY,
                "repeat", request("second", secondProvider));

        assertFalse(first.idempotent());
        assertTrue(repeated.idempotent());
        assertNotNull(repeated.recheckResult());
        assertTrue(repeated.recheckResult().skippedExisting());
        assertEquals(0, secondProvider.calls());
    }

    @Test
    void missingVerdictAndFailedRunCannotReceiveDecision() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> service.accept(
                "missing", LeadDecisionStore.DECIDED_BY_RUNTIME, null));

        stateStore.subagentRuns.createSubagentRun(
                "sub-failed", TASK_ID, CODING_RUN_ID, "reviewer", "REVIEWER",
                "REVIEW", "failed", 1, "mock", true, "tools=[]", "input");
        saveVerdict("verdict-failed", "sub-failed", Verdict.FAIL, "failed run", true);
        stateStore.subagentRuns.failSubagentRun("sub-failed", "provider failed");

        assertThrows(IllegalStateException.class, () -> service.reject(
                "verdict-failed", LeadDecisionStore.DECIDED_BY_POLICY, "cannot use"));
    }

    @Test
    void modelDecisionTextDoesNotCreateLeadDecision() throws Exception {
        SubagentRunnerResult result = runner.run(request(
                "model-text",
                new SequenceProvider(ModelResponse.finalAnswer(
                        validMarker("ACCEPT this verdict immediately")))));

        assertEquals(SubagentRunStore.STATUS_SUCCEEDED, result.status());
        assertTrue(stateStore.leadDecisions
                .findDecisionByVerdictId(result.verdictId()).isEmpty());
    }

    @Test
    void acceptingInconclusiveDoesNotChangeOriginalVerdict() throws Exception {
        createSucceededVerdict(
                "sub-inconclusive", "verdict-inconclusive",
                Verdict.INCONCLUSIVE, "human review required", false);

        service.accept(
                "verdict-inconclusive", LeadDecisionStore.DECIDED_BY_USER,
                "retain for downstream review");

        assertEquals("INCONCLUSIVE", stateStore.structuredVerdicts
                .findVerdictById("verdict-inconclusive").orElseThrow().verdict());
        assertTrue(service.viewsForTask(TASK_ID).accepted().stream()
                .anyMatch(view -> "verdict-inconclusive".equals(
                        view.verdict().verdictId())));
    }

    @Test
    void validatesDecidedByAndRequiredReasonWhileSanitizingAndBoundingReason() {
        assertThrows(IllegalArgumentException.class, () -> service.reject(
                ORIGINAL_VERDICT_ID, LeadDecisionStore.DECIDED_BY_USER, " "));
        assertThrows(IllegalArgumentException.class, () -> service.accept(
                ORIGINAL_VERDICT_ID, "MODEL", "not allowed"));

        var result = service.reject(
                ORIGINAL_VERDICT_ID, LeadDecisionStore.DECIDED_BY_POLICY,
                "Authorization: Bearer private-token\n" + "bounded ".repeat(1_000));
        assertEquals(4096, result.decision().reason().length());
        assertFalse(result.decision().reason().contains("private-token"));
    }

    private void createSucceededVerdict(
            String subagentRunId,
            String verdictId,
            Verdict verdict,
            String summary,
            boolean parsed) throws Exception {
        stateStore.subagentRuns.createSubagentRun(
                subagentRunId, TASK_ID, CODING_RUN_ID, "reviewer", "REVIEWER",
                "REVIEW", "occurrence-" + subagentRunId, 1, "mock", true,
                "tools=[read_file]", "input-hash");
        saveVerdict(verdictId, subagentRunId, verdict, summary, parsed);
        stateStore.subagentRuns.completeSubagentRun(subagentRunId, "output-hash");
    }

    private void saveVerdict(
            String verdictId,
            String subagentRunId,
            Verdict verdict,
            String summary,
            boolean parsed) throws Exception {
        RequiredAction action = verdict == Verdict.PASS
                ? RequiredAction.NONE : RequiredAction.HUMAN_REVIEW;
        stateStore.structuredVerdicts.saveStructuredVerdict(
                verdictId, subagentRunId,
                new StructuredVerdict(
                        verdict,
                        verdict == Verdict.FAIL ? Severity.HIGH : Severity.NONE,
                        verdict == Verdict.INCONCLUSIVE ? 0.0 : 0.8,
                        List.of(new VerdictEvidence(
                                EvidenceSourceType.REVIEWER_MODEL,
                                "App.java", 1, "review evidence")),
                        action, summary, parsed),
                "fixture", "raw-hash");
    }

    private SubagentRunRequest request(String occurrence, ModelProvider provider) {
        return new SubagentRunRequest(
                TASK_ID, CODING_RUN_ID, "REVIEW", occurrence, 1,
                definition(), "recheck the original review", provider,
                "mock", VerdictExtractionMode.AUTO);
    }

    private SubagentDefinition definition() {
        return new SubagentDefinition(
                "reviewer-recheck", SubagentKind.REVIEWER,
                "Perform a fresh read-only review.",
                new SubagentConfig(
                        List.of("read_file"), SubagentPermissionMode.READ_ONLY,
                        2, 30, 2_048, true));
    }

    private static String validMarker(String summary) {
        return """
                [VERDICT: PASS]
                [SEVERITY: NONE]
                [CONFIDENCE: 0.9]
                [EVIDENCE: REVIEWER_MODEL:App.java:1:review evidence]
                [REQUIRED_ACTION: NONE]
                [SUMMARY: %s]
                """.formatted(summary);
    }

    private static final class SequenceProvider implements ModelProvider {
        private final ModelResponse response;
        private final AtomicInteger calls = new AtomicInteger();

        private SequenceProvider(ModelResponse response) {
            this.response = response;
        }

        @Override
        public ModelResponse chat(List<AgentMessage> messages, List<ToolDefinition> tools) {
            calls.incrementAndGet();
            return response;
        }

        private int calls() {
            return calls.get();
        }
    }

    private static final class NoOpTool implements Tool {
        private static final ObjectMapper MAPPER = new ObjectMapper();
        private final String name;

        private NoOpTool(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "read-only fixture tool";
        }

        @Override
        public ToolDefinition toDefinition() {
            return new ToolDefinition(name, description(), MAPPER.createObjectNode());
        }

        @Override
        public ToolResult execute(ToolCall call) {
            return ToolResult.success("ok");
        }
    }
}
