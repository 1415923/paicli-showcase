package com.paicli.subagent;

import com.paicli.config.Config;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.ModelProvider;
import com.paicli.runtime.PersistenceSanitizer;
import com.paicli.runtime.VerificationResult;
import com.paicli.runtime.VerificationService;
import com.paicli.safety.ApprovalPolicy;
import com.paicli.state.CodingRunStore;
import com.paicli.state.StateStore;
import com.paicli.state.SubagentRunStore;
import com.paicli.state.TaskStore;
import com.paicli.state.VerificationStore;
import com.paicli.subagent.verdict.EvidenceSourceType;
import com.paicli.subagent.verdict.RequiredAction;
import com.paicli.subagent.verdict.Verdict;
import com.paicli.subagent.verdict.VerdictExtractionMode;
import com.paicli.subagent.verdict.VerdictExtractor;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SubagentConfiguredVerifierIT {

    private static final String TASK_ID = "task-verifier";
    private static final String RUN_ID = "run-verifier";
    private static final String STATIC_COMMAND = "trusted-static-command";

    @TempDir
    Path workspace;

    private StateStore stateStore;

    @BeforeEach
    void setUp() throws Exception {
        stateStore = new StateStore(workspace);
        stateStore.tasks.createTask(
                TASK_ID, "verify code", "mock", null, Config.MODE_READ_ONLY, 10);
        stateStore.codingRuns.create(
                RUN_ID, TASK_ID, CodingRunStore.KIND_INITIAL, null, 1, 0);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (stateStore != null) {
            stateStore.close();
        }
    }

    @Test
    void mapsEveryVerificationStatusDeterministicallyWithoutCallingModel()
            throws Exception {
        List<StatusExpectation> expectations = List.of(
                new StatusExpectation(VerificationStore.STATUS_PASSED,
                        Verdict.PASS, RequiredAction.NONE),
                new StatusExpectation(VerificationStore.STATUS_FAILED,
                        Verdict.FAIL, RequiredAction.FIX),
                new StatusExpectation(VerificationStore.STATUS_TIMED_OUT,
                        Verdict.INCONCLUSIVE, RequiredAction.HUMAN_REVIEW),
                new StatusExpectation(VerificationStore.STATUS_ERROR,
                        Verdict.INCONCLUSIVE, RequiredAction.HUMAN_REVIEW),
                new StatusExpectation(VerificationStore.STATUS_SKIPPED,
                        Verdict.INCONCLUSIVE, RequiredAction.HUMAN_REVIEW));

        for (StatusExpectation expectation : expectations) {
            FakeVerificationExecutor executor = new FakeVerificationExecutor(
                    expectation.status(), expectation.status().equals(
                    VerificationStore.STATUS_FAILED) ? 1 : 0,
                    "bounded output", "bounded error");
            CountingProvider provider = new CountingProvider(validMarker("model pass"));
            SubagentRunnerResult result = runner(new ConfiguredVerifier(
                    executor, STATIC_COMMAND, "config-" + expectation.status()))
                    .run(request("status-" + expectation.status(), verifierDefinition(), provider));

            assertEquals(SubagentRunStore.STATUS_SUCCEEDED, result.status());
            assertEquals(expectation.verdict(), result.verdict().verdict());
            assertEquals(expectation.action(), result.verdict().requiredAction());
            assertEquals(0, provider.calls());
            assertEquals(1, executor.calls());
            assertEquals(STATIC_COMMAND, executor.command());
            assertTrue(result.verdict().evidence().stream().allMatch(evidence ->
                    evidence.sourceType() == EvidenceSourceType.VERIFIER_COMMAND_OUTPUT));
            assertTrue(stateStore.leadDecisions
                    .findDecisionsByRun(result.subagentRunId()).isEmpty());
            assertTrue(stateStore.subagentRuns.findRunsByTask(TASK_ID).stream()
                    .noneMatch(run -> SubagentRunStore.STATUS_RUNNING.equals(run.status())));
            assertEquals(expectation.status(), stateStore.subagentRuns
                    .findSubagentRunById(result.subagentRunId()).orElseThrow()
                    .verificationStatus());
        }
    }

    @Test
    void failedVerificationCannotBeOverriddenByModelPass() {
        FakeVerificationExecutor executor = new FakeVerificationExecutor(
                VerificationStore.STATUS_FAILED, 2, "tests failed", null);
        CountingProvider provider = new CountingProvider(validMarker("PASS anyway"));

        SubagentRunnerResult result = runner(new ConfiguredVerifier(
                executor, STATIC_COMMAND, "failed-config"))
                .run(request("failed", verifierDefinition(), provider));

        assertEquals(Verdict.FAIL, result.verdict().verdict());
        assertEquals(RequiredAction.FIX, result.verdict().requiredAction());
        assertEquals(0, provider.calls());
    }

    @Test
    void nonVerifierOrMissingCapabilityDoesNotExecuteConfiguredVerification() {
        FakeVerificationExecutor executor = new FakeVerificationExecutor(
                VerificationStore.STATUS_PASSED, 0, "ok", null);
        ConfiguredVerifier configured = new ConfiguredVerifier(
                executor, STATIC_COMMAND, "static-config");
        CountingProvider reviewerProvider = new CountingProvider(validMarker("reviewed"));
        CountingProvider verifierProvider = new CountingProvider(validMarker("inspected"));

        SubagentRunnerResult reviewer = runner(configured).run(request(
                "reviewer-no-capability", reviewerDefinition(), reviewerProvider));
        SubagentRunnerResult verifier = runner(configured).run(request(
                "verifier-no-capability", verifierWithoutCapability(), verifierProvider));

        assertEquals(SubagentRunStore.STATUS_SUCCEEDED, reviewer.status());
        assertEquals(SubagentRunStore.STATUS_SUCCEEDED, verifier.status());
        assertEquals(0, executor.calls());
        assertEquals(1, reviewerProvider.calls());
        assertEquals(1, verifierProvider.calls());
    }

    @Test
    void requestAndModelHaveNoVerificationCommandChannel() {
        assertTrue(Arrays.stream(SubagentRunRequest.class.getRecordComponents())
                .noneMatch(component -> component.getName().toLowerCase().contains("command")));
        assertTrue(Arrays.stream(SubagentDefinition.class.getRecordComponents())
                .noneMatch(component -> component.getName().toLowerCase().contains("command")));
        assertEquals(Set.of(SubagentCapability.CONFIGURED_VERIFICATION),
                verifierDefinition().config().capabilities());
        assertFalse(verifierDefinition().config().allowedTools().contains("run_command"));
        assertFalse(verifierDefinition().config().allowedTools().contains("write_file"));
    }

    @Test
    void outputIsUntrustedSanitizedBoundedAndNotStoredInFull() throws Exception {
        String rawTail = "RAW_VERIFICATION_TAIL_SHOULD_NOT_PERSIST";
        FakeVerificationExecutor executor = new FakeVerificationExecutor(
                VerificationStore.STATUS_FAILED, 1,
                "Authorization: Bearer private-token\n" + "output ".repeat(2_000) + rawTail,
                "Cookie: session=private-cookie");

        SubagentRunnerResult result = runner(new ConfiguredVerifier(
                executor, STATIC_COMMAND, "bounded-config"))
                .run(request("bounded-output", verifierDefinition(),
                        new CountingProvider(validMarker("unused"))));

        var stored = stateStore.structuredVerdicts
                .findVerdictById(result.verdictId()).orElseThrow();
        assertTrue(stored.evidenceJson().contains("UNTRUSTED VERIFICATION OUTPUT"));
        assertTrue(stored.evidenceJson().contains("VERIFIER_COMMAND_OUTPUT"));
        assertFalse(stored.evidenceJson().contains("private-token"));
        assertFalse(stored.evidenceJson().contains("private-cookie"));
        assertFalse(stored.evidenceJson().contains(rawTail));
        assertNotNull(stateStore.subagentRuns
                .findSubagentRunById(result.subagentRunId()).orElseThrow().outputHash());
    }

    @Test
    void sameConfigIsIdempotentAndConfigDriftFailsClosed() throws Exception {
        FakeVerificationExecutor firstExecutor = new FakeVerificationExecutor(
                VerificationStore.STATUS_PASSED, 0, "ok", null);
        SubagentRunRequest request = request(
                "stable-occurrence", verifierDefinition(),
                new CountingProvider(validMarker("unused")));

        SubagentRunnerResult first = runner(new ConfiguredVerifier(
                firstExecutor, STATIC_COMMAND, "config-a")).run(request);
        FakeVerificationExecutor sameExecutor = new FakeVerificationExecutor(
                VerificationStore.STATUS_PASSED, 0, "must not run", null);
        SubagentRunnerResult repeated = runner(new ConfiguredVerifier(
                sameExecutor, STATIC_COMMAND, "config-a")).run(request);
        FakeVerificationExecutor driftedExecutor = new FakeVerificationExecutor(
                VerificationStore.STATUS_PASSED, 0, "must not run", null);

        assertEquals(SubagentRunStore.STATUS_SUCCEEDED, first.status());
        assertTrue(repeated.skippedExisting());
        assertEquals(0, sameExecutor.calls());
        assertThrows(IllegalStateException.class, () -> runner(new ConfiguredVerifier(
                driftedExecutor, "different-command", "config-b")).run(request));
        assertEquals(0, driftedExecutor.calls());
        assertEquals(1, stateStore.subagentRuns
                .findRunsByOccurrence(RUN_ID, "VERIFY", "stable-occurrence").size());
    }

    @Test
    void runningResidueWithConfigDriftIsInterruptedThenFailsClosed()
            throws Exception {
        stateStore.subagentRuns.createSubagentRun(
                "stale-verifier", TASK_ID, RUN_ID, "configured-verifier", "VERIFIER",
                "VERIFY", "running-drift", 1, "mock", true,
                "tools=[]; capabilities=[CONFIGURED_VERIFICATION]", "old-input-hash");
        FakeVerificationExecutor executor = new FakeVerificationExecutor(
                VerificationStore.STATUS_PASSED, 0, "must not run", null);

        assertThrows(IllegalStateException.class, () -> runner(new ConfiguredVerifier(
                executor, STATIC_COMMAND, "new-config")).run(request(
                "running-drift", verifierDefinition(),
                new CountingProvider(validMarker("unused")))));

        assertEquals(0, executor.calls());
        assertEquals(SubagentRunStore.STATUS_INTERRUPTED, stateStore.subagentRuns
                .findSubagentRunById("stale-verifier").orElseThrow().status());
    }

    @Test
    void skippedRealVerificationPersistsResultWithoutChangingTaskOrCodingPhase()
            throws Exception {
        VerificationService verificationService = new VerificationService(
                workspace, stateStore.verifications, new PersistenceSanitizer(),
                5, 1_024);
        ConfiguredVerifier configuredVerifier = new ConfiguredVerifier(
                verificationService, Config.defaults());

        SubagentRunnerResult result = runner(configuredVerifier).run(request(
                "real-skipped", verifierDefinition(),
                new CountingProvider(validMarker("unused"))));

        assertEquals(Verdict.INCONCLUSIVE, result.verdict().verdict());
        assertEquals(VerificationStore.STATUS_SKIPPED,
                stateStore.verifications.findLatestByRun(RUN_ID).orElseThrow().status());
        assertEquals(VerificationStore.STATUS_SKIPPED, stateStore.subagentRuns
                .findSubagentRunById(result.subagentRunId()).orElseThrow()
                .verificationStatus());
        assertEquals(TaskStore.STATUS_CREATED,
                stateStore.tasks.findById(TASK_ID).orElseThrow().status());
        assertEquals(CodingRunStore.PHASE_PLANNING,
                stateStore.codingRuns.findById(RUN_ID).orElseThrow().phase());
        assertTrue(stateStore.leadDecisions
                .findDecisionsByRun(result.subagentRunId()).isEmpty());
    }

    @Test
    void verifierRuntimeFailureTerminatesFailedWithoutVerdictOrRunningResidue()
            throws Exception {
        ConfiguredVerifier configuredVerifier = new ConfiguredVerifier(
                (verificationId, codingRunId, taskId, repairAttempt, command) -> {
                    throw new SQLException("verification store unavailable");
                }, STATIC_COMMAND, "failing-config");

        SubagentRunnerResult result = runner(configuredVerifier).run(request(
                "runtime-failure", verifierDefinition(),
                new CountingProvider(validMarker("unused"))));

        assertEquals(SubagentRunStore.STATUS_FAILED, result.status());
        assertNull(result.verdictId());
        assertTrue(stateStore.structuredVerdicts
                .findVerdictsByRun(result.subagentRunId()).isEmpty());
        assertTrue(stateStore.subagentRuns.findRunsByTask(TASK_ID).stream()
                .noneMatch(run -> SubagentRunStore.STATUS_RUNNING.equals(run.status())));
    }

    private SubagentRunner runner(ConfiguredVerifier configuredVerifier) {
        Config config = new Config(
                ".", 30, 50_000, 200_000, Config.MODE_READ_ONLY, 10_000);
        return new SubagentRunner(
                stateStore, new ToolRegistry(), new ApprovalPolicy(config, TASK_ID),
                new VerdictExtractor(), null, configuredVerifier);
    }

    private SubagentRunRequest request(
            String occurrence,
            SubagentDefinition definition,
            ModelProvider provider) {
        return new SubagentRunRequest(
                TASK_ID, RUN_ID, "VERIFY", occurrence, 1,
                definition, "verify the configured gate", provider, "mock",
                VerdictExtractionMode.AUTO);
    }

    private SubagentDefinition verifierDefinition() {
        return new SubagentDefinition(
                "configured-verifier", SubagentKind.VERIFIER,
                "Use only the deterministic configured verification result.",
                new SubagentConfig(
                        List.of(), Set.of(SubagentCapability.CONFIGURED_VERIFICATION),
                        SubagentPermissionMode.READ_ONLY, 2, 30, 4_096, true));
    }

    private SubagentDefinition verifierWithoutCapability() {
        return new SubagentDefinition(
                "verifier-no-capability", SubagentKind.VERIFIER,
                "Inspect without executing configured verification.",
                new SubagentConfig(
                        List.of(), SubagentPermissionMode.READ_ONLY,
                        2, 30, 4_096, true));
    }

    private SubagentDefinition reviewerDefinition() {
        return new SubagentDefinition(
                "reviewer", SubagentKind.REVIEWER,
                "Review without executing configured verification.",
                new SubagentConfig(
                        List.of(), SubagentPermissionMode.READ_ONLY,
                        2, 30, 4_096, true));
    }

    private static String validMarker(String summary) {
        return """
                [VERDICT: PASS]
                [SEVERITY: NONE]
                [CONFIDENCE: 0.9]
                [EVIDENCE: REVIEWER_MODEL:App.java:1:model evidence]
                [REQUIRED_ACTION: NONE]
                [SUMMARY: %s]
                """.formatted(summary);
    }

    private record StatusExpectation(
            String status, Verdict verdict, RequiredAction action) {
    }

    private static final class CountingProvider implements ModelProvider {
        private final String output;
        private final AtomicInteger calls = new AtomicInteger();

        private CountingProvider(String output) {
            this.output = output;
        }

        @Override
        public ModelResponse chat(List<AgentMessage> messages, List<ToolDefinition> tools) {
            calls.incrementAndGet();
            return ModelResponse.finalAnswer(output);
        }

        private int calls() {
            return calls.get();
        }
    }

    private static final class FakeVerificationExecutor
            implements ConfiguredVerifier.VerificationExecutor {
        private final String status;
        private final Integer exitCode;
        private final String output;
        private final String error;
        private final AtomicInteger calls = new AtomicInteger();
        private volatile String command;

        private FakeVerificationExecutor(
                String status, Integer exitCode, String output, String error) {
            this.status = status;
            this.exitCode = exitCode;
            this.output = output;
            this.error = error;
        }

        @Override
        public VerificationResult verify(
                String verificationId,
                String codingRunId,
                String taskId,
                int repairAttempt,
                String command) {
            calls.incrementAndGet();
            this.command = command;
            return new VerificationResult(
                    verificationId, codingRunId, taskId, repairAttempt,
                    "<trusted-static-command>", status, exitCode, 10, output, error,
                    "WORKSPACE_CONTENT_SHA256_V1","a".repeat(64));
        }

        private int calls() {
            return calls.get();
        }

        private String command() {
            return command;
        }
    }
}
