package com.paicli.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.agent.AgentSegmentRequest;
import com.paicli.config.Config;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.session.SessionBinding;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.model.ToolCall;
import com.paicli.state.PlanAuthorityStore;
import com.paicli.state.DatabaseManager;
import com.paicli.state.PlanCanonicalizer;
import com.paicli.state.PlanCompletionModels.CompletionRuleType;
import com.paicli.state.PlanCompletionModels.EffectClass;
import com.paicli.state.PlanCompletionModels.EvidenceKind;
import com.paicli.state.PlanExecutionModels;
import com.paicli.state.PlanExecutionStore;
import com.paicli.state.PlanToolRequirementPolicy;
import com.paicli.state.StateStore;
import com.paicli.state.StructuredPlanModels.FreshnessPolicy;
import com.paicli.state.StructuredPlanModels.Revision;
import com.paicli.state.StructuredPlanModels.StepDefinition;
import com.paicli.state.StructuredPlanModels.ToolRequirement;
import com.paicli.tool.ReadFileTool;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolResult;
import com.paicli.safety.SafetyPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlanToolAdmissionServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant AT = Instant.parse("2026-08-02T00:00:00Z");
    @TempDir Path workspace;

    @Test
    @DisplayName("RUN-VERIFY-01 satisfied snapshot does not block deferred verification")
    void satisfiedSnapshotLeavesOnlyVerificationPending() {
        var stepId=CanonicalId.newId(EntityFamily.PLAN_STEP);
        var snapshot=new com.paicli.state.PlanEvidenceSemanticEvaluator.EvidenceKindProgress(
                EvidenceKind.SNAPSHOT,1,1);
        var verification=new com.paicli.state.PlanEvidenceSemanticEvaluator.EvidenceKindProgress(
                EvidenceKind.VERIFICATION_RESULT,1,0);
        var progress=new com.paicli.state.PlanEvidenceSemanticEvaluator.StepProgress(
                stepId,3,2,1,0,List.of(),List.of(snapshot,verification));

        assertTrue(CodingTaskRunner.onlyVerificationEvidencePending(progress));

        var missingSnapshot=new com.paicli.state.PlanEvidenceSemanticEvaluator.StepProgress(
                stepId,3,1,1,0,List.of(),List.of(
                        new com.paicli.state.PlanEvidenceSemanticEvaluator.EvidenceKindProgress(
                                EvidenceKind.SNAPSHOT,1,0),verification));
        assertFalse(CodingTaskRunner.onlyVerificationEvidencePending(missingSnapshot));
    }

    @Test
    @DisplayName("ADM-01 active-Step exact Tool/target rejects wrong future duplicate and exhausted calls")
    void exactWrongFutureDuplicateAndCardinalityAreDeterministic() throws Exception {
        try (Fixture fixture = fixture(List.of(
                List.of(requirement("step-one", 0, "one.txt", 1, 1)),
                List.of(requirement("step-two", 0, "two.txt", 1, 1))))) {
            assertRejected(fixture.service.admitBatch(fixture.context, 1, List.of(
                    candidate("wrong", "wrong.txt"))),
                    PlanToolAdmissionService.Status.SELECTOR_MISMATCH);
            assertRejected(fixture.service.admitBatch(fixture.context, 1, List.of(
                    candidate("future", "two.txt"))),
                    PlanToolAdmissionService.Status.FUTURE_STEP);
            assertRejected(fixture.service.admitBatch(fixture.context, 1, List.of(
                    candidate("same-id", "one.txt"), candidate("same-id", "one.txt"))),
                    PlanToolAdmissionService.Status.DUPLICATE_CALL);
            assertEquals(0, fixture.state.planRequirements.bindings(
                    fixture.task, fixture.revision.planRevisionId()).size());

            var exact = fixture.service.admitBatch(fixture.context, 1,
                    List.of(candidate("exact", "one.txt")));
            assertTrue(exact.admitted());
            assertEquals(1, exact.reservations().size());
            assertEquals("step-one", exact.reservations().getFirst().requirementId());
            assertEquals(1, exact.reservations().getFirst().slotOrdinal());
            assertRejected(fixture.service.admitBatch(fixture.context, 2,
                    List.of(candidate("exhausted", "one.txt"))),
                    PlanToolAdmissionService.Status.CARDINALITY_EXHAUSTED);
            assertEquals(1, fixture.state.planRequirements.bindings(
                    fixture.task, fixture.revision.planRevisionId()).size());
        }
    }

    @Test
    @DisplayName("ADM-02 whole multi-Tool batch is atomic with zero occurrences on rejection")
    void invalidBatchRollsBackEveryOccurrenceAndValidMultiToolBatchReservesAtomically()
            throws Exception {
        try (Fixture fixture = fixture(List.of(List.of(
                requirement("alpha", 0, "one.txt", 1, 1),
                requirement("beta", 1, "two.txt", 1, 1))))) {
            var invalid = fixture.service.admitBatch(fixture.context, 1, List.of(
                    candidate("alpha-call", "one.txt"),
                    candidate("wrong-call", "wrong.txt")));
            assertRejected(invalid, PlanToolAdmissionService.Status.SELECTOR_MISMATCH);
            assertTrue(fixture.state.planRequirements.bindings(
                    fixture.task, fixture.revision.planRevisionId()).isEmpty());
            assertTrue(fixture.state.r1aDurable.findRecoveryBlockingOccurrences(
                    fixture.task, fixture.run, "TOOL").isEmpty());

            var valid = fixture.service.admitBatch(fixture.context, 2, List.of(
                    candidate("alpha-call", "one.txt"),
                    candidate("beta-call", "two.txt")));
            assertTrue(valid.admitted());
            assertEquals(List.of("alpha", "beta"), valid.reservations().stream()
                    .map(PlanToolAdmissionService.Reservation::requirementId).toList());
            assertEquals(2, fixture.state.planRequirements.bindings(
                    fixture.task, fixture.revision.planRevisionId()).size());
        }
    }

    @Test
    @DisplayName("ADM-03 / EVD-01 polling freshness binds exact semantic Evidence slots")
    void explicitSameTargetPollingConsumesTwoDistinctFreshSlots() throws Exception {
        try (Fixture fixture = fixture(List.of(List.of(requirement(
                "poll", 0, "poll.txt", 2, 2))))) {
            var admitted = fixture.service.admitBatch(fixture.context, 1, List.of(
                    candidate("poll-one", "poll.txt"),
                    candidate("poll-two", "poll.txt")));
            assertTrue(admitted.admitted());
            assertEquals(List.of(1, 2), admitted.reservations().stream()
                    .map(PlanToolAdmissionService.Reservation::slotOrdinal).toList());
            fixture.guard.completeOccurrence(
                    admitted.reservations().getFirst().occurrenceId(), ToolResult.success("one"));
            var oneOfTwo = fixture.state.planCompletions.evaluateToolEvidence(
                    fixture.task, fixture.revision.planRevisionId(), fixture.run);
            assertEquals(2, oneOfTwo.requiredSuccesses());
            assertEquals(1, oneOfTwo.satisfiedSuccesses());
            assertEquals(PlanExecutionStore.Status.PLAN_ATTEMPT_UNRESOLVED,
                    fixture.state.planExecutions.readRecovery(
                            fixture.task, fixture.revision.planRevisionId()).status(),
                    "live progress projection must not relax Recovery's unresolved-attempt gate");
            InteractiveSession runningSession = evidenceSession(fixture, "running-evidence");
            var runningEvidence = fixture.state.viewModels.read(
                    runningSession.snapshot(), null).evidence();
            assertTrue(runningEvidence.isPresent(), runningEvidence::toString);
            assertEquals(2, runningEvidence.value().total());
            assertEquals(1, runningEvidence.value().succeeded());
            assertEquals(1, runningEvidence.value().unknown());
            assertFalse(oneOfTwo.satisfied());

            assertThrows(java.sql.SQLException.class, () -> insertUnboundRawEvidence(
                    fixture, "occ_00000000000040008000000000000009"),
                    "raw unbound Evidence inflation is rejected at the durable boundary");
            var rawRowIgnored = fixture.state.planCompletions.evaluateToolEvidence(
                    fixture.task, fixture.revision.planRevisionId(), fixture.run);
            assertEquals(1, rawRowIgnored.satisfiedSuccesses(),
                    "raw Evidence rows cannot inflate the semantic numerator");

            fixture.guard.completeOccurrence(
                    admitted.reservations().get(1).occurrenceId(), ToolResult.success("two"));
            var twoOfTwo = fixture.state.planCompletions.evaluateToolEvidence(
                    fixture.task, fixture.revision.planRevisionId(), fixture.run);
            assertEquals(2, twoOfTwo.requiredSuccesses());
            assertEquals(2, twoOfTwo.satisfiedSuccesses());
            assertTrue(twoOfTwo.satisfied());
            var differentRun = fixture.state.planCompletions.evaluateToolEvidence(
                    fixture.task, fixture.revision.planRevisionId(),
                    CanonicalId.newId(EntityFamily.RUN));
            assertEquals(0, differentRun.satisfiedSuccesses(),
                    "fresh polling slots do not satisfy a different resume Run");
            assertEquals(2, fixture.state.planRequirements.evidenceBindings(
                    fixture.task, fixture.revision.planRevisionId(),
                    fixture.context.stepRevisionId()).size());
            assertRejected(fixture.service.admitBatch(fixture.context, 2,
                    List.of(candidate("poll-three", "poll.txt"))),
                    PlanToolAdmissionService.Status.CARDINALITY_EXHAUSTED);
        }
    }

    @Test
    @DisplayName("EVD-02 completion and interactive UI share required-slot x/y")
    void completedAttemptProjectsSemanticRequiredSlotsToInteractiveView() throws Exception {
        try (Fixture fixture = fixture(List.of(List.of(requirement(
                "ui-poll", 0, "poll.txt", 2, 2))))) {
            var admitted = fixture.service.admitBatch(fixture.context, 1, List.of(
                    candidate("ui-poll-one", "poll.txt"),
                    candidate("ui-poll-two", "poll.txt")));
            assertTrue(admitted.admitted());
            fixture.guard.completeOccurrence(
                    admitted.reservations().getFirst().occurrenceId(), ToolResult.success("one"));
            assertThrows(java.sql.SQLException.class, () -> insertUnboundRawEvidence(
                    fixture, "occ_00000000000040008000000000000008"));
            var authority = leaseSnapshot(fixture.state.r1aDurable
                    .readOwnership(fixture.task).orElseThrow());
            assertEquals(PlanExecutionStore.Status.SUCCESS,
                    fixture.state.planExecutions.completeAttempt(authority, AT.plusSeconds(7),
                            fixture.attempt, PlanExecutionModels.Outcome.SUCCEEDED, 2,
                            fixture.stepVersion, null, "attempt finished").status());

            var semantic = fixture.state.planCompletions.evaluateToolEvidence(
                    fixture.task, fixture.revision.planRevisionId(), fixture.run);
            assertEquals(2, semantic.requiredSuccesses());
            assertEquals(1, semantic.satisfiedSuccesses());
            assertEquals(com.paicli.state.R1AuthorityPeek.PeekStatus.PRESENT,
                    fixture.state.authorityPeeks.peekPlanAuthority(fixture.task).status());

            InteractiveSession session = evidenceSession(fixture, "terminal-evidence");
            var evidence = fixture.state.viewModels.read(session.snapshot(), null).evidence();
            assertTrue(evidence.isPresent(), evidence::toString);
            assertEquals(2, evidence.value().total());
            assertEquals(1, evidence.value().succeeded());
            assertEquals(1, evidence.value().unknown());
        }
    }

    @Test
    @DisplayName("EVD-03 V3 completion ignores authoritative but unbound raw Evidence")
    void v3CompletionCannotBeInflatedByAuthoritativeUnboundEvidence() throws Exception {
        try (Fixture fixture = fixture(List.of(List.of(requirement(
                "completion-poll", 0, "poll.txt", 2, 2))))) {
            var admitted = fixture.service.admitBatch(fixture.context, 1, List.of(
                    candidate("completion-one", "poll.txt"),
                    candidate("completion-two", "poll.txt")));
            assertTrue(admitted.admitted());
            fixture.guard.completeOccurrence(admitted.reservations().getFirst().occurrenceId(),
                    ToolResult.success("one"));
            insertAuthoritativeUnboundEvidence(fixture,
                    admitted.reservations().get(1).occurrenceId());
            assertEquals(PlanExecutionStore.Status.SUCCESS, fixture.guard.completePlanAttempt(
                    fixture.attempt, PlanExecutionModels.Outcome.SUCCEEDED, 2,
                    fixture.stepVersion, null, "attempt finished").status());

            var semantic = fixture.state.planCompletions.evaluateToolEvidence(
                    fixture.task, fixture.revision.planRevisionId(), fixture.run);
            assertEquals(2, semantic.requiredSuccesses());
            assertEquals(1, semantic.satisfiedSuccesses());
            var authority = leaseSnapshot(fixture.state.r1aDurable
                    .readOwnership(fixture.task).orElseThrow());
            var completion = fixture.state.planCompletions.evaluateAndComplete(authority,
                    AT.plusSeconds(9), fixture.revision.planRevisionId());
            assertEquals(com.paicli.state.PlanCompletionStore.Status.SUCCESS,
                    completion.status());
            assertEquals(com.paicli.state.PlanCompletionModels.CompletionResult.EVIDENCE_MISSING,
                    completion.result());
        }
    }

    @Test
    @DisplayName("EVD-04 V3 ANY verification alternative completes without Tool Evidence")
    void v3AnyVerificationAlternativeCompletesWithoutToolEvidence() throws Exception {
        try (Fixture fixture = fixture(List.of(List.of(requirement(
                "any-read", 0, "poll.txt", 1, 1))),
                CompletionRuleType.ANY_REQUIRED_EVIDENCE,
                List.of(EvidenceKind.TOOL_CALL, EvidenceKind.VERIFICATION_RESULT),
                EffectClass.READ)) {
            assertEquals(PlanExecutionStore.Status.SUCCESS, fixture.guard.completePlanAttempt(
                    fixture.attempt, PlanExecutionModels.Outcome.SUCCEEDED, 1,
                    fixture.stepVersion, null, "verification alternative").status());
            fixture.guard.startVerification("any-verification", 0, "verify", "RUNNING",
                    WorkspaceCandidateDigest.KIND,"a".repeat(64),null);
            fixture.guard.finishVerification("any-verification", "PASSED", 0, "ok", "", 1);

            assertTrue(fixture.state.planRequirements.bindings(fixture.task,
                    fixture.revision.planRevisionId()).isEmpty());
            assertTrue(fixture.state.planRequirements.evidenceBindings(fixture.task,
                    fixture.revision.planRevisionId(), fixture.context.stepRevisionId()).isEmpty());
            var semantic = fixture.state.planCompletions.evaluateToolEvidence(
                    fixture.task, fixture.revision.planRevisionId(), fixture.run);
            assertEquals(1, semantic.requiredSuccesses());
            assertEquals(1, semantic.satisfiedSuccesses());
            assertTrue(semantic.satisfied());
            var view = fixture.state.viewModels.read(
                    evidenceSession(fixture, "any-verification-view").snapshot(), null).evidence();
            assertTrue(view.isPresent(), view::toString);
            assertEquals(1, view.value().total());
            assertEquals(1, view.value().succeeded());
            var authority = leaseSnapshot(fixture.state.r1aDurable
                    .readOwnership(fixture.task).orElseThrow());
            assertEquals(com.paicli.state.PlanCompletionModels.CompletionResult.COMPLETE,
                    fixture.state.planCompletions.evaluateAndComplete(authority,
                            AT.plusSeconds(9), fixture.revision.planRevisionId()).result());
        }
    }

    @Test
    @DisplayName("EVD-05 V3 ANY Tool alternative completes without verification")
    void v3AnyToolAlternativeCompletesWithoutVerification() throws Exception {
        try (Fixture fixture = fixture(List.of(List.of(requirement(
                "any-read", 0, "poll.txt", 1, 1))),
                CompletionRuleType.ANY_REQUIRED_EVIDENCE,
                List.of(EvidenceKind.TOOL_CALL, EvidenceKind.VERIFICATION_RESULT),
                EffectClass.READ)) {
            var admitted = fixture.service.admitBatch(fixture.context, 1,
                    List.of(candidate("any-tool", "poll.txt")));
            assertTrue(admitted.admitted());
            fixture.guard.completeOccurrence(admitted.reservations().getFirst().occurrenceId(),
                    ToolResult.success("tool alternative"));
            assertEquals(PlanExecutionStore.Status.SUCCESS, fixture.guard.completePlanAttempt(
                    fixture.attempt, PlanExecutionModels.Outcome.SUCCEEDED, 1,
                    fixture.stepVersion, null, "tool alternative").status());

            var semantic = fixture.state.planCompletions.evaluateToolEvidence(
                    fixture.task, fixture.revision.planRevisionId(), fixture.run);
            assertEquals(1, semantic.requiredSuccesses());
            assertEquals(1, semantic.satisfiedSuccesses());
            assertTrue(semantic.satisfied());
            assertEquals(0, semantic.step(fixture.context.stepRevisionId()).typedEvidence()
                    .stream().filter(value -> value.evidenceKind()
                            == EvidenceKind.VERIFICATION_RESULT)
                    .findFirst().orElseThrow().satisfiedSuccesses());
            var view = fixture.state.viewModels.read(
                    evidenceSession(fixture, "any-tool-view").snapshot(), null).evidence();
            assertTrue(view.isPresent(), view::toString);
            assertEquals(1, view.value().total());
            assertEquals(1, view.value().succeeded());
            var authority = leaseSnapshot(fixture.state.r1aDurable
                    .readOwnership(fixture.task).orElseThrow());
            assertEquals(com.paicli.state.PlanCompletionModels.CompletionResult.COMPLETE,
                    fixture.state.planCompletions.evaluateAndComplete(authority,
                            AT.plusSeconds(9), fixture.revision.planRevisionId()).result());
        }
    }

    private InteractiveSession evidenceSession(Fixture fixture, String sessionId)
            throws Exception {
        InteractiveSession session = new InteractiveSession(sessionId, workspace,
                "mock", "model", "on-request", InteractiveSession.TerminalMode.PLAIN, AT);
        session.start();
        var durableTask = fixture.state.r1aDurable.findTask(fixture.task).orElseThrow();
        session.bindThread(new SessionBinding(SessionBinding.State.BOUND_ACTIVE,
                durableTask.threadId(), fixture.task, durableTask.status(), AT));
        session.startTask(fixture.task.value(), fixture.run.value());
        return session;
    }

    @Test
    @DisplayName("REC-01 resume and same-target sequential Steps use only current unmet scope")
    void sameTargetAcrossSequentialStepsKeepsEvidenceAndRecoveryScopePerStep()
            throws Exception {
        try (Fixture fixture = fixture(List.of(
                List.of(requirement("first-read", 0, "one.txt", 1, 1)),
                List.of(requirement("second-read", 0, "one.txt", 1, 1))))) {
            var first = fixture.service.admitBatch(fixture.context, 1,
                    List.of(candidate("first-call", "one.txt")));
            assertTrue(first.admitted());
            fixture.guard.completeOccurrence(first.reservations().getFirst().occurrenceId(),
                    ToolResult.success("first"));
            var reusableOnResume = fixture.state.planCompletions.evaluateToolEvidence(
                    fixture.task, fixture.revision.planRevisionId(),
                    CanonicalId.newId(EntityFamily.RUN));
            assertEquals(1, reusableOnResume.satisfiedSuccesses(),
                    "RESUME_REUSE_ONLY retains a bound success across Runs");
            assertTrue(fixture.state.planRequirements.unmetSlots(fixture.task,
                    fixture.revision.planRevisionId(), fixture.context.stepRevisionId()).isEmpty());
            assertEquals(PlanExecutionStore.Status.SUCCESS, fixture.guard.completePlanAttempt(
                    fixture.attempt, PlanExecutionModels.Outcome.SUCCEEDED, 1,
                    fixture.stepVersion, null, null).status());

            PlanExecutionStore.StartResult next = fixture.guard.startNextPlanAttempt(2);
            assertEquals(PlanExecutionStore.Status.SUCCESS, next.status());
            var nextAttempt = next.value().attempt();
            var nextContext = new AgentSegmentRequest.PlanExecutionContext(fixture.task,
                    fixture.revision.planRevisionId(), nextAttempt.stepRevisionId(), fixture.run,
                    nextAttempt.attemptNumber());
            var unmet = fixture.state.planRequirements.unmetSlots(fixture.task,
                    fixture.revision.planRevisionId(), nextAttempt.stepRevisionId());
            assertEquals(1, unmet.size());
            assertEquals("second-read", unmet.getFirst().requirement()
                    .requirement().requirementId());
            assertEquals("one.txt", JSON.readTree(unmet.getFirst().requirement().requirement()
                    .authoritySelector().canonicalJson()).path("path").asText());

            var second = fixture.service.admitBatch(nextContext, 2,
                    List.of(candidate("second-call", "one.txt")));
            assertTrue(second.admitted());
            assertEquals("second-read", second.reservations().getFirst().requirementId());
            assertEquals(1, second.reservations().getFirst().slotOrdinal());
        }
    }

    private Fixture fixture(List<List<ToolRequirement>> requirements) throws Exception {
        return fixture(requirements, CompletionRuleType.ALL_REQUIRED_EVIDENCE,
                List.of(EvidenceKind.TOOL_CALL), EffectClass.READ);
    }

    private Fixture fixture(List<List<ToolRequirement>> requirements,
            CompletionRuleType rule, List<EvidenceKind> evidenceKinds, EffectClass effect)
            throws Exception {
        for (String path : List.of("one.txt", "two.txt", "poll.txt", "wrong.txt")) {
            Files.writeString(workspace.resolve(path), path);
        }
        StateStore state = new StateStore(workspace);
        CanonicalId task = CanonicalId.newId(EntityFamily.TASK);
        Config config = new Config(workspace.toString(), 30, 50_000, 200_000,
                Config.MODE_WORKSPACE_WRITE, 10_000, Config.PROVIDER_MOCK, "model", "", "",
                "system", 10, "", 120, 50_000, 1, true, false, false);
        var root = new InstructionManifestRuntime(state, workspace)
                .strictPlanAuthorityForTesting().admitRoot(
                task, "goal", 10_000, config, RuntimeManifest.disabled(), AT);
        assertEquals(com.paicli.state.R1aStoreResults.OwnershipResult.SUCCESS,
                state.r1aDurable.acquireOwnership(task, "owner", root.initialRun().id(),
                        "lease", AT, AT.plusSeconds(120)));
        var lease = state.r1aDurable.readOwnership(task).orElseThrow();
        var authority = leaseSnapshot(lease);
        Revision revision = revision(task, root.initialRun().id(), requirements,
                rule, evidenceKinds, effect);
        assertEquals(PlanAuthorityStore.Status.SUCCESS,
                state.planAuthorities.propose(authority, AT.plusSeconds(1), revision).status());
        assertEquals(PlanAuthorityStore.Status.SUCCESS,
                state.planAuthorities.validateRevision(authority, AT.plusSeconds(2),
                        revision.planRevisionId(), 0).status());
        assertEquals(PlanAuthorityStore.Status.SUCCESS,
                state.planAuthorities.approveAndActivate(authority, AT.plusSeconds(3),
                        revision.planRevisionId(), 1, 0, "approval", "user", "v1", null).status());
        PlanExecutionStore.StartResult started = state.planExecutions.startNextReadyAttempt(
                authority, AT.plusSeconds(4), 0, "test");
        assertEquals(PlanExecutionStore.Status.SUCCESS, started.status());
        var attempt = started.value().attempt();
        var context = new AgentSegmentRequest.PlanExecutionContext(task,
                revision.planRevisionId(), attempt.stepRevisionId(), root.initialRun().id(),
                attempt.attemptNumber());
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool(new SafetyPolicy(workspace)));
        FencedExecutionGuard guard = new FencedExecutionGuard(state,
                TaskExecutionAuthority.initial(lease), Duration.ofSeconds(120),
                () -> AT.plusSeconds(5));
        return new Fixture(state, task, root.initialRun().id(), revision, context,
                attempt, started.value().step().transitionVersion(), guard,
                new PlanToolAdmissionService(state, registry, guard));
    }

    private static Revision revision(CanonicalId task, CanonicalId run,
            List<List<ToolRequirement>> requirementSets) {
        return revision(task, run, requirementSets,
                CompletionRuleType.ALL_REQUIRED_EVIDENCE,
                List.of(EvidenceKind.TOOL_CALL), EffectClass.READ);
    }

    private static Revision revision(CanonicalId task, CanonicalId run,
            List<List<ToolRequirement>> requirementSets, CompletionRuleType rule,
            List<EvidenceKind> evidenceKinds, EffectClass effect) {
        CanonicalId plan = CanonicalId.newId(EntityFamily.PLAN);
        List<StepDefinition> steps = new java.util.ArrayList<>();
        for (int index = 0; index < requirementSets.size(); index++) {
            steps.add(new StepDefinition(CanonicalId.newId(EntityFamily.PLAN_STEP),
                    CanonicalId.newId(EntityFamily.PLAN_STEP), index, "Step " + index,
                    "Read exact files", rule, evidenceKinds, effect,
                    requirementSets.get(index), "b".repeat(64)));
        }
        Revision draft = new Revision(plan, task, 1, "goal", "accept", "", "", "verify",
                "0".repeat(64), "MODEL", "test", run, AT, steps, List.of(), List.of(),
                Revision.CANONICAL_SCHEMA_V3);
        return new Revision(plan, task, 1, draft.goal(), draft.acceptanceCriteria(),
                draft.assumptions(), draft.risks(), draft.verificationStrategy(),
                PlanCanonicalizer.hash(draft), draft.sourceType(), draft.provenance(), run, AT,
                steps, List.of(), List.of(), Revision.CANONICAL_SCHEMA_V3);
    }

    private static ToolRequirement requirement(String id, int order, String path,
            int min, int max) throws Exception {
        var selector = PlanToolRequirementPolicy.normalize("read_file", JSON.readTree(
                "{\"kind\":\"EXACT_PATH\",\"path\":\"" + path + "\"}"));
        return new ToolRequirement(id, order, "read_file", EffectClass.READ, selector,
                min, max, max > 1 ? FreshnessPolicy.FRESH_EXECUTION_REQUIRED
                        : FreshnessPolicy.RESUME_REUSE_ONLY);
    }

    private static PlanToolAdmissionService.Candidate candidate(String id, String path) {
        return new PlanToolAdmissionService.Candidate(id,
                new ToolCall("read_file", Map.of("path", path)));
    }

    private static com.paicli.state.R1aDurableModels.ExecutionAuthoritySnapshot leaseSnapshot(
            com.paicli.state.R1aDurableModels.OwnershipLease lease) {
        return new com.paicli.state.R1aDurableModels.ExecutionAuthoritySnapshot(
                lease.taskId(), lease.ownerRunId(), lease.ownerInstanceId(), lease.leaseToken(),
                lease.fencingToken(), lease.casVersion());
    }

    private static void assertRejected(PlanToolAdmissionService.Admission admission,
            PlanToolAdmissionService.Status expected) {
        assertFalse(admission.admitted());
        assertEquals(expected, admission.status());
        assertTrue(admission.reservations().isEmpty());
    }

    private void insertUnboundRawEvidence(Fixture fixture, String producerIdentity)
            throws Exception {
        try (DatabaseManager manager = new DatabaseManager(workspace.resolve(".paicli/state"));
             var statement = manager.getConnection().prepareStatement("""
                     INSERT INTO plan_evidence(task_id,plan_revision_id,step_revision_id,
                       run_id,attempt_number,evidence_kind,producer_kind,producer_identity,
                       observed_status,bounded_summary,protected_reference,canonical_hash,
                       provenance,created_at)
                     VALUES(?,?,?,?,?,'TOOL_CALL','OCCURRENCE',?,'SUCCEEDED',NULL,NULL,?,?,?)
                     """)) {
            statement.setString(1, fixture.task.value());
            statement.setString(2, fixture.revision.planRevisionId().value());
            statement.setString(3, fixture.context.stepRevisionId().value());
            statement.setString(4, fixture.run.value());
            statement.setInt(5, fixture.context.attemptNumber());
            statement.setString(6, producerIdentity);
            statement.setString(7, "f".repeat(64));
            statement.setString(8, "test-unbound-raw-row");
            statement.setString(9, AT.plusSeconds(6).toString());
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void insertAuthoritativeUnboundEvidence(Fixture fixture, CanonicalId occurrenceId)
            throws Exception {
        Instant at = AT.plusSeconds(6);
        String provenance = "test-authoritative-unbound-row";
        String hash = com.paicli.state.PlanCompletionModels.evidenceHash(
                fixture.task, fixture.revision.planRevisionId(),
                fixture.context.stepRevisionId(), fixture.run,
                fixture.context.attemptNumber(), EvidenceKind.TOOL_CALL,
                com.paicli.state.PlanCompletionModels.ProducerKind.OCCURRENCE,
                occurrenceId.value(), "SUCCEEDED", null, null, provenance, at);
        try (DatabaseManager manager = new DatabaseManager(workspace.resolve(".paicli/state"))) {
            var connection = manager.getConnection();
            try (var update = connection.prepareStatement("""
                    UPDATE side_effect_occurrences
                    SET status='COMPLETED',external_outcome='KNOWN_SUCCESS',
                        result_hash=?,completed_at=?
                    WHERE occurrence_id=? AND status='STARTED'
                    """)) {
                update.setString(1, "a".repeat(64));
                update.setString(2, at.toString());
                update.setString(3, occurrenceId.value());
                assertEquals(1, update.executeUpdate());
            }
            try (var insert = connection.prepareStatement("""
                    INSERT INTO plan_evidence(task_id,plan_revision_id,step_revision_id,
                      run_id,attempt_number,evidence_kind,producer_kind,producer_identity,
                      observed_status,bounded_summary,protected_reference,canonical_hash,
                      provenance,created_at)
                    VALUES(?,?,?,?,?,'TOOL_CALL','OCCURRENCE',?,'SUCCEEDED',NULL,NULL,?,?,?)
                    """)) {
                insert.setString(1, fixture.task.value());
                insert.setString(2, fixture.revision.planRevisionId().value());
                insert.setString(3, fixture.context.stepRevisionId().value());
                insert.setString(4, fixture.run.value());
                insert.setInt(5, fixture.context.attemptNumber());
                insert.setString(6, occurrenceId.value());
                insert.setString(7, hash);
                insert.setString(8, provenance);
                insert.setString(9, at.toString());
                assertEquals(1, insert.executeUpdate());
            }
        }
    }

    private record Fixture(StateStore state, CanonicalId task, CanonicalId run,
            Revision revision, AgentSegmentRequest.PlanExecutionContext context,
            PlanExecutionModels.Attempt attempt, long stepVersion,
            FencedExecutionGuard guard,
            PlanToolAdmissionService service) implements AutoCloseable {
        @Override public void close() throws Exception { state.close(); }
    }
}
