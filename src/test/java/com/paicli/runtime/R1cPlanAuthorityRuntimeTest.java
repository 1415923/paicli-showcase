package com.paicli.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.agent.AgentLoop;
import com.paicli.config.Config;
import com.paicli.context.ContextModels.CapabilityProfile;
import com.paicli.context.ContextModels.LimitProvenance;
import com.paicli.context.ContextModels.OperationKind;
import com.paicli.context.ContextualModelProvider;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.ModelProvider;
import com.paicli.interactive.approval.InteractiveApprovalCoordinator;
import com.paicli.interactive.approval.InteractiveApprovalRequest;
import com.paicli.interactive.cancel.InteractiveCancellationController;
import com.paicli.interactive.diff.PreWriteDiffService;
import com.paicli.safety.SafetyPolicy;
import com.paicli.state.PlanAuthorityStore;
import com.paicli.state.PlanCanonicalizer;
import com.paicli.state.PlanToolRequirementPolicy;
import com.paicli.state.R1aDurableModels.ExecutionAuthoritySnapshot;
import com.paicli.state.StateStore;
import com.paicli.state.TaskStore;
import com.paicli.state.StructuredPlanModels.Revision;
import com.paicli.state.StructuredPlanModels.StepDefinition;
import com.paicli.state.StructuredPlanModels.PlanApplicability;
import com.paicli.state.StructuredPlanModels.FreshnessPolicy;
import com.paicli.state.StructuredPlanModels.ToolRequirement;
import com.paicli.tool.Tool;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolResult;
import com.paicli.tool.ReadFileTool;
import com.paicli.tool.ToolAuthorityProjection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class R1cPlanAuthorityRuntimeTest {
    @TempDir Path workspace;
    private static final String PLAN_JSON = """
            {"acceptanceCriteria":["implemented"],"assumptions":[],"risks":[],
             "verificationStrategy":"run tests","steps":[
               {"title":"Inspect","definition":"Inspect the relevant code","dependsOn":[],
                "completionRule":"ALL_REQUIRED_EVIDENCE","requiredEvidenceKinds":["TOOL_CALL"],"effectClass":"READ",
                "toolRequirements":[{"requirementId":"inspect-agents","requirementOrder":0,"toolName":"read_file","effectClass":"READ","authoritySelector":{"kind":"EXACT_PATH","path":"AGENTS.md"},"minSuccesses":1,"maxOccurrences":1,"freshnessPolicy":"RESUME_REUSE_ONLY"}]},
               {"title":"Implement","definition":"Implement the scoped change","dependsOn":[0],
                "completionRule":"ALL_REQUIRED_EVIDENCE","requiredEvidenceKinds":["TOOL_CALL"],"effectClass":"READ",
                "toolRequirements":[{"requirementId":"confirm-agents","requirementOrder":0,"toolName":"read_file","effectClass":"READ","authoritySelector":{"kind":"EXACT_PATH","path":"AGENTS.md"},"minSuccesses":1,"maxOccurrences":1,"freshnessPolicy":"RESUME_REUSE_ONLY"}]}]}
            """;
    private static final String STEERING_PLAN_JSON = """
            {"acceptanceCriteria":["updated"],"assumptions":[],"risks":[],
             "verificationStrategy":"inspect updated scope","steps":[
               {"title":"Inspect updated scope","definition":"Inspect the Steering-updated code boundary","dependsOn":[],
                "completionRule":"ALL_REQUIRED_EVIDENCE","requiredEvidenceKinds":["TOOL_CALL"],"effectClass":"READ",
                "toolRequirements":[{"requirementId":"steer-inspect","requirementOrder":0,"toolName":"read_file","effectClass":"READ","authoritySelector":{"kind":"EXACT_PATH","path":"AGENTS.md"},"minSuccesses":1,"maxOccurrences":1,"freshnessPolicy":"RESUME_REUSE_ONLY"}]},
               {"title":"Implement updated scope","definition":"Implement only the Steering-updated change","dependsOn":[0],
                "completionRule":"ALL_REQUIRED_EVIDENCE","requiredEvidenceKinds":["TOOL_CALL"],"effectClass":"READ",
                "toolRequirements":[{"requirementId":"steer-confirm","requirementOrder":0,"toolName":"read_file","effectClass":"READ","authoritySelector":{"kind":"EXACT_PATH","path":"AGENTS.md"},"minSuccesses":1,"maxOccurrences":1,"freshnessPolicy":"RESUME_REUSE_ONLY"}]}]}
            """;
    private static final String VERIFICATION_PLAN_JSON = """
            {"acceptanceCriteria":["verified"],"assumptions":[],"risks":[],
             "verificationStrategy":"run configured verification","steps":[
               {"title":"Verify","definition":"Complete when configured verification passes","dependsOn":[],
                "completionRule":"VERIFICATION_PASSED",
                "requiredEvidenceKinds":["VERIFICATION_RESULT"],"effectClass":"VERIFY",
                "toolRequirements":[]}]}
            """;
    private static final String GENERIC_VERIFICATION_PLAN_JSON = """
            {"acceptanceCriteria":["verified"],"assumptions":[],"risks":[],
             "verificationStrategy":"run configured verification","steps":[
               {"title":"Verify","definition":"Complete from Verification Evidence","dependsOn":[],
                "completionRule":"ALL_REQUIRED_EVIDENCE",
                "requiredEvidenceKinds":["VERIFICATION_RESULT"],"effectClass":"VERIFY",
                "toolRequirements":[]}]}
            """;
    private static final String ANY_VERIFICATION_PLAN_JSON = """
            {"acceptanceCriteria":["verified"],"assumptions":[],"risks":[],
             "verificationStrategy":"run configured verification","steps":[
               {"title":"Verify","definition":"Complete from either declared Evidence kind","dependsOn":[],
                "completionRule":"ANY_REQUIRED_EVIDENCE",
                "requiredEvidenceKinds":["VERIFICATION_RESULT","TOOL_CALL"],"effectClass":"VERIFY",
                "toolRequirements":[{"requirementId":"verify-or-inspect-agents","requirementOrder":0,
                "toolName":"read_file","effectClass":"READ",
                "authoritySelector":{"kind":"EXACT_PATH","path":"AGENTS.md"},
                "minSuccesses":1,"maxOccurrences":1,
                "freshnessPolicy":"RESUME_REUSE_ONLY"}]}]}
            """;
    private static final String FIVE_STEP_EXACT_READ_PLAN_JSON = """
            {"acceptanceCriteria":["five exact reads"],"assumptions":[],"risks":[],
             "verificationStrategy":"durable exact Tool evidence","steps":[
               {"title":"Read 01","definition":"Read exact item 01","dependsOn":[],
                "completionRule":"ALL_REQUIRED_EVIDENCE","requiredEvidenceKinds":["TOOL_CALL"],
                "effectClass":"READ","toolRequirements":[{"requirementId":"read-01","requirementOrder":0,"toolName":"read_file","effectClass":"READ","authoritySelector":{"kind":"EXACT_PATH","path":"scope/item-01.txt"},"minSuccesses":1,"maxOccurrences":1,"freshnessPolicy":"RESUME_REUSE_ONLY"}]},
               {"title":"Read 02","definition":"Read exact item 02","dependsOn":[0],
                "completionRule":"ALL_REQUIRED_EVIDENCE","requiredEvidenceKinds":["TOOL_CALL"],
                "effectClass":"READ","toolRequirements":[{"requirementId":"read-02","requirementOrder":0,"toolName":"read_file","effectClass":"READ","authoritySelector":{"kind":"EXACT_PATH","path":"scope/item-02.txt"},"minSuccesses":1,"maxOccurrences":1,"freshnessPolicy":"RESUME_REUSE_ONLY"}]},
               {"title":"Read 03","definition":"Read exact item 03","dependsOn":[1],
                "completionRule":"ALL_REQUIRED_EVIDENCE","requiredEvidenceKinds":["TOOL_CALL"],
                "effectClass":"READ","toolRequirements":[{"requirementId":"read-03","requirementOrder":0,"toolName":"read_file","effectClass":"READ","authoritySelector":{"kind":"EXACT_PATH","path":"scope/item-03.txt"},"minSuccesses":1,"maxOccurrences":1,"freshnessPolicy":"RESUME_REUSE_ONLY"}]},
               {"title":"Read 04","definition":"Read exact item 04","dependsOn":[2],
                "completionRule":"ALL_REQUIRED_EVIDENCE","requiredEvidenceKinds":["TOOL_CALL"],
                "effectClass":"READ","toolRequirements":[{"requirementId":"read-04","requirementOrder":0,"toolName":"read_file","effectClass":"READ","authoritySelector":{"kind":"EXACT_PATH","path":"scope/item-04.txt"},"minSuccesses":1,"maxOccurrences":1,"freshnessPolicy":"RESUME_REUSE_ONLY"}]},
               {"title":"Read 05","definition":"Read exact item 05","dependsOn":[3],
                "completionRule":"ALL_REQUIRED_EVIDENCE","requiredEvidenceKinds":["TOOL_CALL"],
                "effectClass":"READ","toolRequirements":[{"requirementId":"read-05","requirementOrder":0,"toolName":"read_file","effectClass":"READ","authoritySelector":{"kind":"EXACT_PATH","path":"scope/item-05.txt"},"minSuccesses":1,"maxOccurrences":1,"freshnessPolicy":"RESUME_REUSE_ONLY"}]}]}
            """;

    @Test void unapprovedPlanStopsBeforeExecutionProviderOrTools() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "project rule");
        SequencedProvider provider = new SequencedProvider();
        try (StateStore state = new StateStore(workspace)) {
            CodingTaskRunner runner = runner(state, provider, PlanApprovalPort.DENY);
            CanonicalId task = CanonicalId.newId(EntityFamily.TASK);
            CodingTaskResult result = runner.run(task.value(), "implement safely");
            assertEquals(1, provider.calls);
            assertEquals(com.paicli.state.TaskStore.STATUS_FAILED, result.finalStatus());
            assertEquals(0, state.toolCalls.findByTask(task.value()).size());
            assertEquals(PlanAuthorityStore.Status.NO_APPROVED_PLAN,
                    state.planAuthorities.readActive(task).status());
            assertEquals(1, count("plan_revisions"));
            assertEquals(1, count("plan_approvals"));
            assertEquals(1, count("plan_revision_lifecycle WHERE state='REJECTED'"));
            assertEquals(0,count("plan_step_executions"));
            assertEquals(0,count("plan_step_attempts"));
        }
    }

    @Test void planPresentationFailurePreservesValidatedRevisionWithoutAuthorityOrExecution()
            throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "project rule");
        SequencedProvider provider = new SequencedProvider();
        PlanApprovalPort presentationFailure = (revision, summary) -> {
            throw new com.paicli.interactive.approval.InteractiveApprovalCoordinator
                    .PlanApprovalPresentationException(
                            new IllegalStateException("injected presentation failure"));
        };
        CanonicalId task = CanonicalId.newId(EntityFamily.TASK);
        try (StateStore state = new StateStore(workspace)) {
            CodingTaskResult result = runner(state, provider, presentationFailure)
                    .run(task.value(), "implement safely");

            assertEquals(TaskStore.STATUS_FAILED, result.finalStatus(), result.summary());
            assertEquals("PLAN_APPROVAL_PRESENTATION_FAILED", result.summary());
            assertEquals(1, provider.calls);
            assertEquals(1, count("plan_revisions"));
            assertEquals(1, count("plan_revision_lifecycle WHERE state='VALIDATED'"));
            assertEquals(0, count("plan_approvals"));
            assertEquals(0, count("task_plan_authorities"));
            assertEquals(0, count("plan_run_bindings"));
            assertEquals(0, count("plan_step_executions"));
            assertEquals(0, count("plan_step_attempts"));
            assertEquals(0, count("plan_evidence"));
            assertEquals(0, state.toolCalls.findByTask(task.value()).size());
        }
    }

    @Test void materialSteeringPlanPresentationFailureTerminalizesWithoutNewAuthority()
            throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "project rule");
        CanonicalId task = CanonicalId.newId(EntityFamily.TASK);
        try (StateStore state = new StateStore(workspace)) {
            AtomicInteger providerCalls = new AtomicInteger();
            ModelProvider provider = (messages, tools) -> {
                int call = providerCalls.incrementAndGet();
                if (call == 1) return ModelResponse.finalAnswer(PLAN_JSON);
                if (call == 2) return ModelResponse.finalAnswer(STEERING_PLAN_JSON);
                throw new AssertionError("unexpected Provider call " + call);
            };
            PlanService planService = new PlanService(provider, state.plans);
            AtomicReference<String> steeringAdmission = new AtomicReference<>();
            AtomicReference<String> steeringApplication = new AtomicReference<>();
            AtomicReference<FencedExecutionGuard> guard = new AtomicReference<>();
            AtomicInteger approvalEvents = new AtomicInteger();
            AtomicReference<InteractiveApprovalCoordinator> coordinatorRef =
                    new AtomicReference<>();
            InteractiveApprovalCoordinator.ApprovalEvents events =
                    new InteractiveApprovalCoordinator.ApprovalEvents() {
                        @Override public void requested(InteractiveApprovalRequest request) {
                            if (approvalEvents.incrementAndGet() == 1) {
                                coordinatorRef.get().respond(request.requestId(), "y");
                            } else {
                                coordinatorRef.get().respond(request.requestId(), "d");
                            }
                        }
                        @Override public List<String> planDetails(
                                InteractiveApprovalRequest request) {
                            throw new IllegalStateException("steering presentation failure");
                        }
                    };
            InteractiveApprovalCoordinator coordinator = new InteractiveApprovalCoordinator(
                    "session-steering-presentation", new InteractiveCancellationController(),
                    Clock.systemUTC());
            coordinatorRef.set(coordinator);
            CodingTaskObserver observer = new CodingTaskObserver() {
                @Override public void executionAuthorityBound(FencedExecutionGuard value) {
                    guard.set(value);
                    coordinator.beginTask(value.current().taskId().value(),
                            value.current().runId().value(), "on-request",
                            new PreWriteDiffService(
                                    new SafetyPolicy(workspace, 5, 8192), null), events);
                }
                @Override public void planCreated(String taskId, String runId, PlanResult plan) {
                    try {
                        var target = state.steerings.captureTarget(
                                CanonicalId.parse(taskId), CanonicalId.parse(runId));
                        var admitted = state.steerings.admit(
                                target.target(),
                                new SteeringClassifier().classify("change plan scope"),
                                com.paicli.state.SteeringModels.Provenance.INTERACTIVE_EXPLICIT,
                                "interactive:test", "steering-test", Instant.now());
                        steeringAdmission.set(target.status() + "/" + admitted.status());
                        var applied = new SteeringSafePointCoordinator(state, guard.get(),
                                () -> false,
                                request -> planService.generateSteeredStructured(
                                        request.sourceRevision(), request.definition().steeringId(),
                                        request.definition().content(), "", guard.get(),
                                        request.revisionNumber(), Instant.now(),
                                        new com.paicli.state.PlanCompletionProfilePolicy.Capabilities(
                                                false)),
                                iteration -> null).check(
                                        SteeringSafePointCoordinator.Candidate
                                                .NEXT_STEP_ATTEMPT_BEFORE);
                        steeringApplication.set(applied.status() + "/" + applied.applied());
                    } catch (Exception error) {
                        steeringAdmission.set(error.getClass().getSimpleName());
                        throw new IllegalStateException(error);
                    }
                }
            };
            Config config = config(true);
            CodingTaskRunner runner = new CodingTaskRunner(state, planService,
                    new VerificationService(workspace, state.verifications,
                            new PersistenceSanitizer(), 120, 50_000),
                    taskId -> new AgentLoop(provider, new ToolRegistry(),
                            config.effectiveMaxIterations(), null, null, state, taskId,
                            config.effectiveProvider(), config.model()), config,
                    new PersistenceSanitizer(), null, null, RuntimeManifest.disabled(),
                    observer, (taskId, runId) -> false,
                    new InstructionManifestRuntime(state, workspace), coordinator)
                    .strictPlanAuthorityForTesting();
            CodingTaskResult result = runner.run(task.value(), "implement safely");

            assertEquals("SUCCESS/SUCCESS", steeringAdmission.get());
            assertEquals("SUCCESS/true", steeringApplication.get());
            assertEquals(2, providerCalls.get());
            assertEquals(2, approvalEvents.get());
            assertTrue(coordinator.pendingRequest().isEmpty());
            assertEquals(TaskStore.STATUS_FAILED, result.finalStatus(), result.summary());
            assertEquals("PLAN_APPROVAL_PRESENTATION_FAILED", result.summary());
            assertEquals("FAILED", state.r1aDurable.findTask(task).orElseThrow().status());
            assertEquals("FAILED", state.r1aDurable.findRunsByTask(task)
                    .getFirst().terminalStatus());
            assertEquals(2, count("plan_revisions"));
            assertEquals(1, count("plan_revision_lifecycle WHERE state='APPROVED'"));
            assertEquals(1, count("plan_revision_lifecycle WHERE state='VALIDATED'"));
            assertEquals(1, count("plan_approvals"));
            assertEquals(1, count("task_plan_authorities"));
            assertEquals(1, count("plan_run_bindings"));
            assertEquals(0, count("plan_evidence"));
            assertEquals(0, state.toolCalls.findByTask(task.value()).size());
        }
    }

    @Test void authorityConflictAfterUserApprovalTerminalizesTaskAndRun() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "project rule");
        CanonicalId task = CanonicalId.newId(EntityFamily.TASK);
        try (StateStore state = new StateStore(workspace)) {
            SequencedProvider provider = new SequencedProvider();
            PlanApprovalPort approveAfterConflictingWrite = (revision, summary) -> {
                try {
                    var lease = state.r1aDurable.readOwnership(task).orElseThrow();
                    ExecutionAuthoritySnapshot authority = new ExecutionAuthoritySnapshot(task,
                            lease.ownerRunId(), lease.ownerInstanceId(), lease.leaseToken(),
                            lease.fencingToken(), lease.casVersion());
                    assertEquals(PlanAuthorityStore.Status.SUCCESS,
                            state.planAuthorities.reject(authority, Instant.now(),
                                    revision.planRevisionId(), 1, "competing-decision",
                                    "other-user", "test", "injected conflict").status());
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
                return new PlanApprovalPort.Decision(
                        true, "USER_APPROVED", "test-user", "r1c-test");
            };

            CodingTaskResult result = runner(state, provider,
                    approveAfterConflictingWrite).run(task.value(), "implement safely");

            assertEquals(com.paicli.state.TaskStore.STATUS_FAILED,
                    result.finalStatus(), result.summary());
            assertTrue(result.summary().contains("PLAN_AUTHORITY_CONFLICT"));
            assertEquals("FAILED", state.r1aDurable.findTask(task).orElseThrow().status());
            assertEquals("FAILED", state.r1aDurable.findRunsByTask(task)
                    .getFirst().terminalStatus());
            assertEquals(PlanAuthorityStore.Status.NO_APPROVED_PLAN,
                    state.planAuthorities.readActive(task).status());
            assertEquals(0, state.toolCalls.findByTask(task.value()).size());
        }
    }

    @Test void explicitApprovalDrivesExecutionFromDurablePointerAndSurvivesNewProcess() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "project rule");
        CanonicalId task = CanonicalId.newId(EntityFamily.TASK);
        CanonicalId active;
        try (StateStore state = new StateStore(workspace)) {
            SequencedProvider provider = new SequencedProvider();
            PlanApprovalPort approve = (revision, summary) ->
                    new PlanApprovalPort.Decision(true, "USER_APPROVED", "test-user", "r1c-a-test");
            CodingTaskResult result = runner(state, provider, approve).run(task.value(), "implement safely");
            assertEquals(com.paicli.state.TaskStore.STATUS_DONE,
                    result.finalStatus(), result.summary());
            var approved = state.planAuthorities.readActive(task);
            assertEquals(PlanAuthorityStore.Status.SUCCESS, approved.status());
            active = approved.plan().revision().planRevisionId();
            assertEquals(active.value(), result.planId());
            assertEquals(2, approved.plan().revision().steps().size());
            assertEquals(2,provider.executionPrompts.size());
            for(int index=0;index<2;index++){
                assertTrue(provider.executionPrompts.get(index).contains(
                        approved.plan().revision().steps().get(index).stepRevisionId().value()));
                assertTrue(provider.executionPrompts.get(index).contains("attempt: 1"));
                assertTrue(provider.executionPrompts.get(index).contains(
                        "Tool calls only for this durable Step"));
                assertTrue(provider.executionPrompts.get(index).contains(
                        "Prefer list_files for directory enumeration"));
                assertTrue(provider.executionPrompts.get(index).contains(
                        "will terminalize this Step and activate the next durable Step"));
            }
            assertEquals(2,state.planExecutions.readStates(task,active).stream()
                    .filter(step->step.state()
                            ==com.paicli.state.PlanExecutionModels.StepState.SUCCEEDED)
                    .count());
            assertEquals(2,state.planExecutions.readAttempts(task,active).size());
        }
        try (StateStore reopened = new StateStore(workspace)) {
            var approved = reopened.planAuthorities.readActive(task);
            assertEquals(PlanAuthorityStore.Status.SUCCESS, approved.status());
            assertEquals(active, approved.plan().revision().planRevisionId());
            assertEquals(List.of("Inspect", "Implement"), approved.plan().revision().steps()
                    .stream().map(value -> value.title()).toList());
            assertEquals(1, count("plan_run_bindings"));
        }
    }

    @Test void verificationRequiredStepCompletesOnlyAfterPassedConfiguredVerification()
            throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "project rule");
        SequencedJsonProvider provider = new SequencedJsonProvider(VERIFICATION_PLAN_JSON);
        try (StateStore state = new StateStore(workspace)) {
            CodingTaskResult result = runner(state, provider, provider,
                    (revision, summary) -> new PlanApprovalPort.Decision(
                            true, "USER_APPROVED", "test-user", "r1c-test"),
                    config(true, "echo verification-passed")).run(
                    CanonicalId.newId(EntityFamily.TASK).value(), "verify");
            assertEquals(com.paicli.state.TaskStore.STATUS_DONE, result.finalStatus());
            assertEquals(1, count("plan_verification_evidence"));
            assertEquals(1, count("task_completion_decisions"));
        }
    }

    @Test void failedConfiguredVerificationCannotCompleteStructuredTask() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "project rule");
        SequencedJsonProvider provider = new SequencedJsonProvider(VERIFICATION_PLAN_JSON);
        String fail = System.getProperty("os.name", "").toLowerCase().contains("windows")
                ? "echo verification-failed 1>&2 & exit /b 7"
                : "echo verification-failed >&2; exit 7";
        try (StateStore state = new StateStore(workspace)) {
            CodingTaskResult result = runner(state, provider, provider,
                    (revision, summary) -> new PlanApprovalPort.Decision(
                            true, "USER_APPROVED", "test-user", "r1c-test"),
                    config(true, fail)).run(CanonicalId.newId(EntityFamily.TASK).value(), "verify");
            assertEquals(com.paicli.state.TaskStore.STATUS_FAILED, result.finalStatus());
            assertEquals(0, count("plan_verification_evidence"));
            assertEquals(0, count("task_completion_decisions"));
        }
    }

    @Test void genericAllAndAnyRulesCompleteFromVerificationEvidence() throws Exception {
        for (String plan : List.of(GENERIC_VERIFICATION_PLAN_JSON,
                ANY_VERIFICATION_PLAN_JSON)) {
            Path caseWorkspace = workspace.resolve(Integer.toHexString(plan.hashCode()));
            Files.createDirectories(caseWorkspace);
            Files.writeString(caseWorkspace.resolve("AGENTS.md"), "project rule");
            SequencedJsonProvider provider = new SequencedJsonProvider(plan);
            try (StateStore state = new StateStore(caseWorkspace)) {
                Config config = config(caseWorkspace, true, "echo verification-passed");
                CodingTaskResult result = runner(caseWorkspace, state, provider, provider,
                        (revision, summary) -> new PlanApprovalPort.Decision(
                                true, "USER_APPROVED", "test-user", "r1c-test"),
                        config, new ToolRegistry()).run(
                        CanonicalId.newId(EntityFamily.TASK).value(), "verify");
                assertEquals(com.paicli.state.TaskStore.STATUS_DONE,
                        result.finalStatus(), result.summary());
                assertEquals(1, count(caseWorkspace, "plan_verification_evidence"));
                assertEquals(1, count(caseWorkspace, "task_completion_decisions"));
            }
        }
    }

    @Test void recoveryConsumesPersistedPassedVerificationBeforeExternalExecution()
            throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "project rule");
        CanonicalId task = CanonicalId.newId(EntityFamily.TASK);
        CanonicalId revisionId;
        Instant at = Instant.parse("2026-07-18T03:00:00Z");
        try (StateStore state = new StateStore(workspace)) {
            Config config = config(true, "echo must-not-run-on-recovery");
            var root = new InstructionManifestRuntime(state, workspace)
                    .strictPlanAuthorityForTesting().admitRoot(
                    task, "goal", 10_000, config, RuntimeManifest.disabled(), at);
            state.checkpoints.save(task.value(), 1, "RUNNING",
                    List.of(AgentMessage.userGoal("goal")));
            assertEquals(com.paicli.state.R1aStoreResults.OwnershipResult.SUCCESS,
                    state.r1aDurable.acquireOwnership(task, "runtime-a", root.initialRun().id(),
                            "lease-a", at, at.plusSeconds(5)));
            ExecutionAuthoritySnapshot authority = new ExecutionAuthoritySnapshot(task,
                    root.initialRun().id(), "runtime-a", "lease-a", 1, 1);
            Revision revision = verificationRevision(task, root.initialRun().id());
            revisionId = revision.planRevisionId();
            assertEquals(PlanAuthorityStore.Status.SUCCESS,
                    state.planAuthorities.propose(authority, at.plusSeconds(1), revision).status());
            assertEquals(PlanAuthorityStore.Status.SUCCESS,
                    state.planAuthorities.validateRevision(authority, at.plusSeconds(2),
                            revisionId, 0).status());
            assertEquals(PlanAuthorityStore.Status.SUCCESS,
                    state.planAuthorities.approveAndActivate(authority, at.plusSeconds(3),
                            revisionId, 1, 0, "approval", "user", "v1", null).status());
            var started = state.planExecutions.startNextReadyAttempt(
                    authority, at.plusSeconds(4), 1, "runtime-a");
            assertEquals(com.paicli.state.PlanExecutionStore.Status.SUCCESS, started.status());
            assertEquals(com.paicli.state.PlanExecutionStore.Status.SUCCESS,
                    state.planExecutions.completeAttempt(authority, at.plusSeconds(4),
                            started.value().attempt(),
                            com.paicli.state.PlanExecutionModels.Outcome.SUCCEEDED,
                            1, started.value().step().transitionVersion(), null, "done").status());
            assertEquals(com.paicli.state.R1aStoreResults.FencedMutationResult.SUCCESS,
                    state.fencedMutations.startVerification(authority, at.plusSeconds(4),
                            "verify-persisted", 0, "verify", "RUNNING",
                            WorkspaceCandidateDigest.KIND,"a".repeat(64),null));
            assertEquals(com.paicli.state.R1aStoreResults.FencedMutationResult.SUCCESS,
                    state.fencedMutations.finishVerification(authority, at.plusSeconds(4),
                            "verify-persisted", "PASSED", 0, "ok", "", 1));
            assertEquals(1, count("verification_runs"));
            assertEquals(1, count("plan_verification_evidence"));
            assertEquals(0, count("task_completion_decisions"));
        }
        try (StateStore state = new StateStore(workspace)) {
            var recovered = assertInstanceOf(TaskRecoveryService.Success.class,
                    new TaskRecoveryService(state, new InstructionManifestRuntime(state, workspace)
                            .strictPlanAuthorityForTesting())
                            .recover(task, config(true, "echo must-not-run-on-recovery"),
                                    RuntimeManifest.disabled(), 10_000,
                                    new TaskRecoveryService.LeaseRequest("runtime-b", "lease-b",
                                            at.plusSeconds(20), at.plusSeconds(50),
                                            Duration.ZERO)));
            CountingFinalProvider executionProvider = new CountingFinalProvider();
            FencedExecutionGuard guard = new FencedExecutionGuard(state,
                    recovered.executionAuthority(), Duration.ofSeconds(30),
                    () -> at.plusSeconds(21));
            CodingTaskResult result = runner(state, new SequencedProvider(), executionProvider,
                    PlanApprovalPort.DENY,
                    config(true, "echo must-not-run-on-recovery"))
                    .recover(recovered, "goal", guard);
            assertEquals(com.paicli.state.TaskStore.STATUS_DONE,
                    result.finalStatus(), result.summary());
            assertEquals(1, executionProvider.calls,
                    "final user result synthesis is the only recovery Provider call");
            assertEquals(1, count("verification_runs"));
            assertEquals(1, count("plan_verification_evidence"));
            assertEquals(1, count("task_completion_decisions"));
            assertEquals("echo must-not-run-on-recovery", state.codingRuns
                    .findById(recovered.resumeRun().id().value()).orElseThrow()
                    .verificationCommand());
            assertEquals(revisionId.value(), result.planId());
        }
    }

    @Test void failedAgentSegmentTerminatesOnlyItsAttemptAndPreservesHistory() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"),"project rule");
        CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
        try(StateStore state=new StateStore(workspace)){
            FailingExecutionProvider provider=new FailingExecutionProvider();
            PlanApprovalPort approve=(revision,summary)->new PlanApprovalPort.Decision(
                    true,"USER_APPROVED","test-user","r1c-b1-test");
            CodingTaskResult result=runner(state,provider,approve).run(task.value(),"goal");
            assertEquals(com.paicli.state.TaskStore.STATUS_FAILED,result.finalStatus());
            CanonicalId revision=state.planAuthorities.readActive(task)
                    .plan().revision().planRevisionId();
            assertEquals(com.paicli.state.PlanExecutionModels.StepState.FAILED,
                    state.planExecutions.readStates(task,revision).get(0).state());
            assertEquals(com.paicli.state.PlanExecutionModels.AttemptState.FAILED,
                    state.planExecutions.readAttempts(task,revision).get(0).state());
            assertEquals(2,provider.calls);
        }
    }

    @Test void providerFailureFailsCurrentAttemptAndBlocksDependentStep() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"),"project rule");
        CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
        try(StateStore state=new StateStore(workspace)){
            SequencedProvider planningProvider=new SequencedProvider();
            TypedFailureProvider executionProvider=new TypedFailureProvider();
            PlanApprovalPort approve=(revision,summary)->new PlanApprovalPort.Decision(
                    true,"USER_APPROVED","test-user","r1c-b1-test");
            CodingTaskResult result=runner(state,planningProvider,executionProvider,
                    approve,config(true)).run(task.value(),"goal");
            assertEquals(com.paicli.state.TaskStore.STATUS_FAILED,result.finalStatus());
            CanonicalId revision=state.planAuthorities.readActive(task)
                    .plan().revision().planRevisionId();
            assertEquals(List.of(com.paicli.state.PlanExecutionModels.StepState.FAILED,
                            com.paicli.state.PlanExecutionModels.StepState.BLOCKED),
                    state.planExecutions.readStates(task,revision).stream()
                            .map(com.paicli.state.PlanExecutionModels.StepExecution::state)
                            .toList());
            var attempts=state.planExecutions.readAttempts(task,revision);
            assertEquals(1,attempts.size());
            assertEquals(com.paicli.state.PlanExecutionModels.AttemptState.FAILED,
                    attempts.getFirst().state());
            assertEquals("PROVIDER_TIMEOUT",attempts.getFirst().failureClass());
            assertEquals(1,executionProvider.calls);
            assertTrue(state.toolCalls.findByTask(task.value()).isEmpty());
            assertTrue(state.planCompletions.readEvidence(task,revision).isEmpty());
        }
    }

    @Test void durableToolEvidenceSatisfiesRulesAndOnlyCompletionGateFinishesTask()
            throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "project rule");
        CanonicalId task = CanonicalId.newId(EntityFamily.TASK);
        try (StateStore state = new StateStore(workspace)) {
            EvidenceProvider provider = new EvidenceProvider();
            RecordingTool tool = new RecordingTool();
            ToolRegistry registry = new ToolRegistry();
            registry.register(tool);
            PlanApprovalPort approve = (revision, summary) -> new PlanApprovalPort.Decision(
                    true, "USER_APPROVED", "test-user", "r1c-completion-test");
            CodingTaskResult result = runner(state, provider, provider, approve, config(true),
                    registry).run(task.value(), "implement safely");
            assertEquals(com.paicli.state.TaskStore.STATUS_DONE,
                    result.finalStatus(), result.summary());
            CanonicalId revision = state.planAuthorities.readActive(task)
                    .plan().revision().planRevisionId();
            assertEquals(2, tool.executions);
            assertEquals(2, state.planCompletions.readEvidence(task, revision).size());
            var userResult=state.userResults.find(task,CanonicalId.parse(result.runId()))
                    .orElseThrow();
            assertEquals("VALID",userResult.validationStatus());
            assertEquals("RENDERED",userResult.renderStatus());
            assertFalse(userResult.content().isBlank());
            assertEquals(userResult.content(),result.summary());
            assertEquals(1, count("task_completion_decisions"));
            assertEquals(2, count("plan_completion_evaluations"));
            assertEquals(4, count("plan_completion_evaluation_evidence"));
        }
    }

    @Test void REAL_PROVIDER_SHAPED_SEQUENTIAL_DEVIATION_REJECTED_before_effect()
            throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "project rule");
        Path scope = Files.createDirectory(workspace.resolve("scope"));
        for (int index = 1; index <= 5; index++) {
            Files.writeString(scope.resolve("item-%02d.txt".formatted(index)),
                    "EXACT-%02d\n".formatted(index));
        }
        Map<String,String> before = new java.util.LinkedHashMap<>();
        try (var files = Files.list(scope)) {
            files.sorted().forEach(path -> {
                try { before.put(path.getFileName().toString(), Files.readString(path)); }
                catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
            });
        }
        CanonicalId task = CanonicalId.newId(EntityFamily.TASK);
        try (StateStore state = new StateStore(workspace)) {
            SequentialDeviationProvider provider = new SequentialDeviationProvider();
            ToolRegistry registry = new ToolRegistry();
            registry.register(new ReadFileTool(new SafetyPolicy(workspace)));
            PlanApprovalPort approve = (revision, summary) -> new PlanApprovalPort.Decision(
                    true, "USER_APPROVED", "test-user", "real-provider-shaped-deviation");

            CodingTaskResult result = runner(state, provider, provider, approve, config(true),
                    registry).run(task.value(), "Read five exact files in Plan order");

            assertEquals(TaskStore.STATUS_FAILED, result.finalStatus(), result.summary());
            assertEquals(6, provider.calls,
                    "rejected duplicate must end the loop before another Provider turn");
            assertEquals(3, provider.executionCandidates);
            assertEquals(2, count("side_effect_occurrences"));
            assertEquals(2, count("plan_evidence"));
            assertEquals(0, count("task_user_results"));
            var active = state.planAuthorities.readActive(task);
            assertEquals(PlanAuthorityStore.Status.SUCCESS, active.status());
            String frozenHash = active.plan().revision().canonicalHash();
            assertEquals(frozenHash, state.planAuthorities.readRevisionDefinition(task,
                    active.plan().revision().planRevisionId()).revision().canonicalHash());
            assertEquals(5, active.plan().revision().steps().size());
            assertEquals(5, count("plan_tool_requirements"));
        }
        Map<String,String> after = new java.util.LinkedHashMap<>();
        try (var files = Files.list(scope)) {
            files.sorted().forEach(path -> {
                try { after.put(path.getFileName().toString(), Files.readString(path)); }
                catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
            });
        }
        assertEquals(before, after);
    }

    @Test void finalSynthesisFailureCannotCompleteTaskOrRun() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "project rule");
        CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
        try(StateStore state=new StateStore(workspace)){
            FinalStageProvider provider=new FinalStageProvider(false);
            RecordingTool tool=new RecordingTool();ToolRegistry registry=new ToolRegistry();
            registry.register(tool);
            CodingTaskResult result=runner(state,provider,provider,
                    (revision,summary)->new PlanApprovalPort.Decision(true,"USER_APPROVED",
                            "test-user","r1c-final-gate"),config(true),registry)
                    .run(task.value(),"inspect safely");
            assertEquals(TaskStore.STATUS_FAILED,result.finalStatus());
            assertEquals("FAILED",state.r1aDurable.findTask(task).orElseThrow().status());
            assertEquals("FAILED",state.r1aDurable.findRunsByTask(task).getFirst().terminalStatus());
            assertTrue(state.userResults.find(task,CanonicalId.parse(result.runId())).isEmpty());
            assertEquals(0,count("task_completion_decisions"));
        }
    }

    @Test void synchronousFinalRenderingFailureCannotCompleteTaskOrRun() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "project rule");
        CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
        try(StateStore state=new StateStore(workspace)){
            EvidenceProvider provider=new EvidenceProvider();
            RecordingTool tool=new RecordingTool();ToolRegistry registry=new ToolRegistry();
            registry.register(tool);
            CodingTaskObserver renderFailure=new CodingTaskObserver(){
                @Override public boolean finalResponse(String taskId,String runId,String mode,
                        String content,String contentHash){return false;}
            };
            CodingTaskRunner runner=new CodingTaskRunner(state,
                    new PlanService(provider,state.plans),
                    new VerificationService(workspace,state.verifications,
                            new PersistenceSanitizer(),120,50_000),
                    taskId->new AgentLoop(provider,registry,config(true).effectiveMaxIterations(),
                            null,null,state,taskId,config(true).effectiveProvider(),
                            config(true).model()),config(true),new PersistenceSanitizer(),null,null,
                    RuntimeManifest.disabled(),renderFailure,(taskId,runId)->false,
                    new InstructionManifestRuntime(state,workspace),
                    (revision,summary)->new PlanApprovalPort.Decision(true,"USER_APPROVED",
                            "test-user","r1c-render-gate")).strictPlanAuthorityForTesting();
            CodingTaskResult result=runner.run(task.value(),"inspect safely");
            assertEquals(TaskStore.STATUS_FAILED,result.finalStatus());
            var userResult=state.userResults.find(task,CanonicalId.parse(result.runId())).orElseThrow();
            assertEquals("PENDING",userResult.renderStatus());
            assertEquals(0,count("task_completion_decisions"));
        }
    }

    @Test void emptyFinalResponseCannotSatisfyCompletionGate() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "project rule");
        CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
        try(StateStore state=new StateStore(workspace)){
            FinalStageProvider provider=new FinalStageProvider(true);
            RecordingTool tool=new RecordingTool();ToolRegistry registry=new ToolRegistry();
            registry.register(tool);
            CodingTaskResult result=runner(state,provider,provider,
                    (revision,summary)->new PlanApprovalPort.Decision(true,"USER_APPROVED",
                            "test-user","r1c-empty-final"),config(true),registry)
                    .run(task.value(),"inspect safely");
            assertEquals(TaskStore.STATUS_FAILED,result.finalStatus());
            assertTrue(state.userResults.find(task,CanonicalId.parse(result.runId())).isEmpty());
            assertEquals(0,count("task_completion_decisions"));
        }
    }

    @Test void explicitSilentIntentUsesMinimalModeButContentSuppressionDoesNot() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "project rule");
        try(StateStore state=new StateStore(workspace)){
            CanonicalId silentTask=CanonicalId.newId(EntityFamily.TASK);
            EvidenceProvider silentProvider=new EvidenceProvider();
            ToolRegistry firstRegistry=new ToolRegistry();firstRegistry.register(new RecordingTool());
            CodingTaskResult silent=runner(state,silentProvider,silentProvider,
                    (revision,summary)->new PlanApprovalPort.Decision(true,"USER_APPROVED",
                            "test-user","r1c-minimal"),config(true),firstRegistry)
                    .run(silentTask.value(),"静默执行，只执行，不解释");
            assertEquals(TaskStore.STATUS_DONE,silent.finalStatus());
            assertEquals("MINIMAL",state.userResults.find(silentTask,
                    CanonicalId.parse(silent.runId())).orElseThrow().mode());

            CanonicalId summaryTask=CanonicalId.newId(EntityFamily.TASK);
            EvidenceProvider summaryProvider=new EvidenceProvider();
            ToolRegistry secondRegistry=new ToolRegistry();secondRegistry.register(new RecordingTool());
            CodingTaskResult summary=runner(state,summaryProvider,summaryProvider,
                    (revision,rendered)->new PlanApprovalPort.Decision(true,"USER_APPROVED",
                            "test-user","r1c-required"),config(true),secondRegistry)
                    .run(summaryTask.value(),"不要输出文件内容，只给安全摘要");
            assertEquals(TaskStore.STATUS_DONE,summary.finalStatus());
            assertEquals("REQUIRED",state.userResults.find(summaryTask,
                    CanonicalId.parse(summary.runId())).orElseThrow().mode());
        }
    }

    @Test void runtimeCreatedAuthorityCanAtomicallySwitchToApprovedNextRevision()
            throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"project rule");
        CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
        try(StateStore state=new StateStore(workspace)){
            Instant now=Instant.parse("2026-07-18T04:00:00Z");
            var root=new InstructionManifestRuntime(state,workspace).admitRoot(task,
                    "implement safely",10_000,config(true),RuntimeManifest.disabled(),now);
            assertEquals(com.paicli.state.R1aStoreResults.OwnershipResult.SUCCESS,
                    state.r1aDurable.acquireOwnership(task,"runtime-a",root.initialRun().id(),
                            "lease-a",now,now.plusSeconds(60)));
            ExecutionAuthoritySnapshot authority=new ExecutionAuthoritySnapshot(task,
                    root.initialRun().id(),"runtime-a","lease-a",1,1);
            Revision firstRevision=revision(task,root.initialRun().id(),1,"Initial revision");
            CanonicalId first=firstRevision.planRevisionId();
            assertEquals(PlanAuthorityStore.Status.SUCCESS,
                    state.planAuthorities.propose(authority,now.plusSeconds(1),firstRevision).status());
            assertEquals(PlanAuthorityStore.Status.SUCCESS,state.planAuthorities
                    .validateRevision(authority,now.plusSeconds(2),first,0).status());
            assertEquals(PlanAuthorityStore.Status.SUCCESS,state.planAuthorities.approveAndActivate(
                    authority,now.plusSeconds(3),first,1,0,"approval-1","test-user",
                    "r1c-a-test",null).status());
            Revision second=revision(task,root.initialRun().id(),2,"Next revision");
            assertEquals(PlanAuthorityStore.Status.SUCCESS,
                    state.planAuthorities.propose(authority,now.plusSeconds(4),second).status());
            assertEquals(PlanAuthorityStore.Status.SUCCESS,state.planAuthorities
                    .validateRevision(authority,now.plusSeconds(5),second.planRevisionId(),0).status());
            assertEquals(PlanAuthorityStore.Status.SUCCESS,state.planAuthorities.approveAndActivate(
                    authority,now.plusSeconds(6),second.planRevisionId(),1,1,"approval-2","test-user",
                    "r1c-a-test",null).status());
            assertEquals(second.planRevisionId(),state.planAuthorities.readActive(task)
                    .plan().revision().planRevisionId());
            assertEquals(PlanAuthorityStore.Status.SUCCESS,
                    state.planAuthorities.readRevisionDefinition(task,first).status());
        }
    }

    @Test void recoveryUsesDurableApprovedRevisionAcrossProcessWithoutReplanning()
            throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"project rule");
        CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
        CanonicalId approvedPlan;
        ExecutionAuthoritySnapshot oldAuthority;
        Instant at=Instant.parse("2026-07-18T01:00:00Z");
        try(StateStore state=new StateStore(workspace)){
            var root=new InstructionManifestRuntime(state,workspace)
                    .strictPlanAuthorityForTesting().admitRoot(task,"goal",10_000,
                    config(true),RuntimeManifest.disabled(),at);
            state.checkpoints.save(task.value(),1,"RUNNING",List.of(AgentMessage.userGoal("goal")));
            assertEquals(com.paicli.state.R1aStoreResults.OwnershipResult.SUCCESS,
                    state.r1aDurable.acquireOwnership(task,"runtime-a",root.initialRun().id(),
                            "lease-a",at,at.plusSeconds(5)));
            ExecutionAuthoritySnapshot authority=new ExecutionAuthoritySnapshot(task,
                    root.initialRun().id(),"runtime-a","lease-a",1,1);
            oldAuthority=authority;
            Revision first=revision(task,root.initialRun().id(),1,"Approved");
            approvedPlan=first.planRevisionId();
            assertEquals(PlanAuthorityStore.Status.SUCCESS,
                    state.planAuthorities.propose(authority,at.plusSeconds(1),first).status());
            assertEquals(PlanAuthorityStore.Status.SUCCESS,state.planAuthorities
                    .validateRevision(authority,at.plusSeconds(2),approvedPlan,0).status());
            assertEquals(PlanAuthorityStore.Status.SUCCESS,state.planAuthorities.approveAndActivate(
                    authority,at.plusSeconds(3),approvedPlan,1,0,"approval-1","user","v1",null).status());
            var firstAttempt=state.planExecutions.startNextReadyAttempt(authority,
                    at.plusSeconds(4),1,"runtime-a");
            assertEquals(com.paicli.state.PlanExecutionStore.Status.SUCCESS,
                    firstAttempt.status());
            assertEquals(com.paicli.state.PlanExecutionStore.Status.SUCCESS,
                    state.planExecutions.completeAttempt(authority,at.plusSeconds(4),
                            firstAttempt.value().attempt(),
                            com.paicli.state.PlanExecutionModels.Outcome.RETRYABLE_FAILURE,
                            1,firstAttempt.value().step().transitionVersion(),
                            "RETRYABLE","preserve attempt history").status());
            Revision newer=revision(task,root.initialRun().id(),2,"Unapproved");
            assertEquals(PlanAuthorityStore.Status.SUCCESS,
                    state.planAuthorities.propose(authority,at.plusSeconds(4),newer).status());
        }
        try(StateStore state=new StateStore(workspace)){
            var recovered=assertInstanceOf(TaskRecoveryService.Success.class,
                    new TaskRecoveryService(state,new InstructionManifestRuntime(state,workspace)
                            .strictPlanAuthorityForTesting())
                            .recover(task,config(true),RuntimeManifest.disabled(),10_000,
                                    new TaskRecoveryService.LeaseRequest("runtime-b","lease-b",
                                            at.plusSeconds(20),at.plusSeconds(50),Duration.ZERO)));
            assertEquals(PlanApplicability.PLAN_REQUIRED,
                    recovered.context().planApplicability());
            assertEquals(approvedPlan,recovered.context().structuredPlan().revision()
                    .planRevisionId());
            assertEquals(com.paicli.state.PlanCompletionStore.Status.SUCCESS,
                    recovered.context().planCompletion().status());
            assertEquals(1, recovered.context().planCompletion().ruleCount());
            assertEquals(com.paicli.state.PlanExecutionStore.Status.OWNER_MISMATCH,
                    state.planExecutions.startNextReadyAttempt(oldAuthority,
                            at.plusSeconds(21),0,"old-runtime").status());
            SequencedProvider planningProvider=new SequencedProvider();
            CountingFinalProvider executionProvider=new CountingFinalProvider();
            FencedExecutionGuard guard=new FencedExecutionGuard(state,
                    recovered.executionAuthority(),Duration.ofSeconds(30),
                    ()->at.plusSeconds(21));
            CodingTaskResult result=runner(state,planningProvider,executionProvider,
                    PlanApprovalPort.DENY,config(true)).recover(recovered,"goal",guard);
            assertEquals(com.paicli.state.TaskStore.STATUS_DONE,
                    result.finalStatus(),result.summary());
            assertEquals(0,planningProvider.calls);
            assertEquals(approvedPlan.value(),result.planId());
            assertEquals(2,count("plan_run_bindings"));
            var attempts=state.planExecutions.readAttempts(task,approvedPlan);
            assertEquals(List.of(1,2),attempts.stream()
                    .map(com.paicli.state.PlanExecutionModels.Attempt::attemptNumber).toList());
            assertEquals(oldAuthority.runId(),attempts.get(0).runId());
            assertEquals(recovered.resumeRun().id(),attempts.get(1).runId());
        }
    }

    @Test void planDisabledRecoveryDoesNotFallBackToCurrentRuntimePlanning()
            throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"project rule");
        CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
        Instant at=Instant.now();
        try(StateStore state=new StateStore(workspace)){
            var root=new InstructionManifestRuntime(state,workspace).admitRoot(task,"goal",10_000,
                    config(false),RuntimeManifest.disabled(),at);
            state.events.append(task.value(),1,AgentMessage.userGoal("goal"));
            state.checkpoints.save(task.value(),1,"RUNNING",List.of(AgentMessage.userGoal("goal")));
            assertEquals(com.paicli.state.R1aStoreResults.OwnershipResult.SUCCESS,
                    state.r1aDurable.acquireOwnership(task,"runtime-a",root.initialRun().id(),
                            "lease-a",at,at.plusSeconds(5)));
        }
        try(StateStore state=new StateStore(workspace)){
            var recovered=assertInstanceOf(TaskRecoveryService.Success.class,
                    new TaskRecoveryService(state,new InstructionManifestRuntime(state,workspace))
                            .recover(task,config(false),RuntimeManifest.disabled(),10_000,
                                    new TaskRecoveryService.LeaseRequest("runtime-b","lease-b",
                                            at.plusSeconds(20),at.plusSeconds(50),Duration.ZERO)));
            assertEquals(PlanApplicability.PLAN_DISABLED,
                    recovered.context().planApplicability());
            assertNull(recovered.context().structuredPlan());
            SequencedProvider planningProvider=new SequencedProvider();
            CountingFinalProvider executionProvider=new CountingFinalProvider();
            FencedExecutionGuard guard=new FencedExecutionGuard(state,
                    recovered.executionAuthority(),Duration.ofSeconds(30),
                    ()->at.plusSeconds(21));
            ModelProvider contextualExecution=new ContextualModelProvider(executionProvider,
                    state,workspace,task.value(),config(false).effectiveProvider(),
                    config(false).model(),OperationKind.CHAT,
                    new CapabilityProfile("test",30_000,
                            LimitProvenance.CONFIGURED_LIMIT,256,64),false,"runtime-b");
            CodingTaskResult result=runner(state,planningProvider,contextualExecution,
                    PlanApprovalPort.DENY,config(false)).recover(recovered,"goal",guard);
            assertEquals(com.paicli.state.TaskStore.STATUS_DONE,result.finalStatus(),result.summary());
            assertEquals(0,planningProvider.calls);
            assertEquals(1,executionProvider.calls);
            assertNull(result.planId());
            assertEquals(0,count("plan_run_bindings"));
        }
    }

    private CodingTaskRunner runner(StateStore state, ModelProvider provider, PlanApprovalPort approval)
            throws java.io.IOException {
        return runner(state,provider,provider,approval,config(true));
    }

    private Config config(boolean planEnabled){
        return config(planEnabled, "");
    }

    private Config config(boolean planEnabled, String verificationCommand){
        return config(workspace, planEnabled, verificationCommand);
    }

    private Config config(Path targetWorkspace, boolean planEnabled, String verificationCommand){
        return new Config(targetWorkspace.toString(), 30, 50_000, 200_000,
                Config.MODE_DENY, 10_000, Config.PROVIDER_MOCK, "model-a", "", "",
                "system", 3, verificationCommand, 120, 50_000, 1,
                planEnabled, !verificationCommand.isBlank(), false);
    }

    private CodingTaskRunner runner(StateStore state,ModelProvider planningProvider,
            ModelProvider executionProvider,PlanApprovalPort approval,Config config)
            throws java.io.IOException{
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool(new SafetyPolicy(workspace)));
        return runner(state,planningProvider,executionProvider,approval,config,registry);
    }

    private CodingTaskRunner runner(StateStore state,ModelProvider planningProvider,
            ModelProvider executionProvider,PlanApprovalPort approval,Config config,
            ToolRegistry registry) throws java.io.IOException{
        return runner(workspace, state, planningProvider, executionProvider, approval, config,
                registry);
    }

    private CodingTaskRunner runner(Path targetWorkspace, StateStore state,
            ModelProvider planningProvider, ModelProvider executionProvider,
            PlanApprovalPort approval, Config config, ToolRegistry registry)
            throws java.io.IOException {
        return new CodingTaskRunner(state,new PlanService(planningProvider,state.plans),
                new VerificationService(targetWorkspace, state.verifications,
                        new PersistenceSanitizer(), 120, 50_000),
                taskId -> new AgentLoop(executionProvider, registry,
                        config.effectiveMaxIterations(), null, null, state, taskId,
                        config.effectiveProvider(), config.model()), config,
                new PersistenceSanitizer(), null, null, RuntimeManifest.disabled(),
                CodingTaskObserver.NOOP, (taskId, runId) -> false,
                new InstructionManifestRuntime(state, targetWorkspace), approval)
                .strictPlanAuthorityForTesting();
    }

    private static Revision revision(CanonicalId task,CanonicalId run,int number,String title)
            throws Exception{
        CanonicalId plan=CanonicalId.newId(EntityFamily.PLAN);
        var selector=PlanToolRequirementPolicy.normalize("read_file",new ObjectMapper().readTree(
                "{\"kind\":\"EXACT_PATH\",\"path\":\"AGENTS.md\"}"));
        ToolRequirement requirement=new ToolRequirement("revision-read-"+number,0,
                "read_file",com.paicli.state.PlanCompletionModels.EffectClass.READ,selector,
                1,1,FreshnessPolicy.RESUME_REUSE_ONLY);
        StepDefinition step=new StepDefinition(CanonicalId.newId(EntityFamily.PLAN_STEP),
                CanonicalId.newId(EntityFamily.PLAN_STEP),0,title,"definition",
                com.paicli.state.PlanCompletionModels.CompletionRuleType.ALL_REQUIRED_EVIDENCE,
                List.of(com.paicli.state.PlanCompletionModels.EvidenceKind.TOOL_CALL),
                com.paicli.state.PlanCompletionModels.EffectClass.READ,List.of(requirement),
                "b".repeat(64));
        Revision candidate=new Revision(plan,task,number,"goal","accept","","","verify",
                "0".repeat(64),"USER","test",run,Instant.parse("2026-07-18T01:00:00Z"),
                List.of(step),List.of(),List.of(),Revision.CANONICAL_SCHEMA_V3);
        return new Revision(plan,task,number,candidate.goal(),candidate.acceptanceCriteria(),
                candidate.assumptions(),candidate.risks(),candidate.verificationStrategy(),
                PlanCanonicalizer.hash(candidate),candidate.sourceType(),candidate.provenance(),run,
                candidate.createdAt(),candidate.steps(),candidate.dependencies(),candidate.lineage(),
                Revision.CANONICAL_SCHEMA_V3);
    }

    private static Revision verificationRevision(CanonicalId task, CanonicalId run) {
        CanonicalId plan = CanonicalId.newId(EntityFamily.PLAN);
        StepDefinition step = new StepDefinition(CanonicalId.newId(EntityFamily.PLAN_STEP),
                CanonicalId.newId(EntityFamily.PLAN_STEP), 0, "Verify", "definition",
                com.paicli.state.PlanCompletionModels.CompletionRuleType.VERIFICATION_PASSED,
                List.of(com.paicli.state.PlanCompletionModels.EvidenceKind.VERIFICATION_RESULT),
                com.paicli.state.PlanCompletionModels.EffectClass.VERIFY, "b".repeat(64));
        Revision candidate = new Revision(plan, task, 1, "goal", "accept", "", "",
                "verify", "0".repeat(64), "USER", "test", run,
                Instant.parse("2026-07-18T03:00:00Z"), List.of(step), List.of(), List.of(),
                Revision.CANONICAL_SCHEMA_V3);
        return new Revision(plan, task, 1, candidate.goal(), candidate.acceptanceCriteria(),
                candidate.assumptions(), candidate.risks(), candidate.verificationStrategy(),
                PlanCanonicalizer.hash(candidate), candidate.sourceType(), candidate.provenance(),
                run, candidate.createdAt(), candidate.steps(), candidate.dependencies(),
                candidate.lineage(),Revision.CANONICAL_SCHEMA_V3);
    }

    private long count(String table) throws Exception {
        return count(workspace, table);
    }

    private long count(Path targetWorkspace, String table) throws Exception {
        try (var manager = new com.paicli.state.DatabaseManager(
                targetWorkspace.resolve(".paicli/state"))) {
            try (var statement = manager.getConnection().createStatement();
                 var result = statement.executeQuery("SELECT count(*) FROM " + table)) {
                result.next(); return result.getLong(1);
            }
        }
    }

    private static final class SequencedProvider implements ModelProvider {
        private int calls;
        private final List<String> executionPrompts=new java.util.ArrayList<>();
        @Override public ModelResponse chat(List<AgentMessage> messages,
                List<com.paicli.model.ToolDefinition> tools) {
            calls++;
            if (calls == 1) return ModelResponse.finalAnswer(PLAN_JSON);
            String latest = messages.isEmpty() ? "" : messages.getLast().content();
            if (latest.contains("Use Tool calls only for this durable Step")) {
                executionPrompts.add(messages.stream().map(AgentMessage::content)
                        .collect(java.util.stream.Collectors.joining("\n")));
                return ModelResponse.toolCallResponse("inspect exact approved target",
                        new ToolCall("read_file", Map.of("path", "AGENTS.md"),
                                "sequenced-read-" + calls));
            }
            return ModelResponse.finalAnswer("completed");
        }
    }

    private static final class SequencedJsonProvider implements ModelProvider {
        private final String plan;
        private int calls;
        private SequencedJsonProvider(String plan) { this.plan = plan; }
        @Override public ModelResponse chat(List<AgentMessage> messages,
                List<ToolDefinition> tools) {
            calls++;
            return ModelResponse.finalAnswer(calls == 1 ? plan : "completed");
        }
    }

    private static final class CountingFinalProvider implements ModelProvider{
        private int calls;
        @Override public ModelResponse chat(List<AgentMessage> messages,
                List<com.paicli.model.ToolDefinition> tools){
            calls++;
            String latest = messages.isEmpty() ? "" : messages.getLast().content();
            if (latest.contains("Use Tool calls only for this durable Step")) {
                return ModelResponse.toolCallResponse("recover exact approved target",
                        new ToolCall("read_file", Map.of("path", "AGENTS.md"),
                                "recovery-read-" + calls));
            }
            return ModelResponse.finalAnswer("done");
        }
    }

    private static final class FailingExecutionProvider implements ModelProvider{
        private int calls;
        @Override public ModelResponse chat(List<AgentMessage> messages,
                List<com.paicli.model.ToolDefinition> tools){
            calls++;
            if(calls==1)return ModelResponse.finalAnswer(PLAN_JSON);
            throw new IllegalStateException("bounded execution failure");
        }
    }

    private static final class TypedFailureProvider implements ModelProvider{
        private int calls;
        @Override public ModelResponse chat(List<AgentMessage> messages,
                List<com.paicli.model.ToolDefinition> tools){
            calls++;
            return ModelResponse.providerError(
                    ModelResponse.ProviderErrorKind.PROVIDER_TIMEOUT);
        }
    }

    private static final class EvidenceProvider implements ModelProvider {
        private int calls;
        @Override public ModelResponse chat(List<AgentMessage> messages,
                List<ToolDefinition> tools) {
            calls++;
            if(calls>1&&tools.isEmpty())return ModelResponse.finalAnswer(
                    "Final result based on the recorded durable evidence.");
            if (calls == 1) return ModelResponse.finalAnswer(PLAN_JSON);
            return calls % 2 == 0
                    ? ModelResponse.toolCallResponse("record",
                            new ToolCall("read_file", Map.of("path","AGENTS.md")))
                    : ModelResponse.finalAnswer("step complete");
        }
    }

    private static final class SequentialDeviationProvider implements ModelProvider {
        private int calls;
        private int executionCandidates;
        @Override public ModelResponse chat(List<AgentMessage> messages,
                List<ToolDefinition> tools) {
            calls++;
            if (calls == 1) return ModelResponse.finalAnswer(FIVE_STEP_EXACT_READ_PLAN_JSON);
            if (calls == 2) return tool("provider-read-01", "scope/item-01.txt");
            if (calls == 3) return ModelResponse.finalAnswer("step 01 complete");
            if (calls == 4) return tool("provider-read-02", "scope/item-02.txt");
            if (calls == 5) return ModelResponse.finalAnswer("step 02 complete");
            if (calls == 6) return tool("provider-duplicate-01", "scope/item-01.txt");
            return ModelResponse.finalAnswer("must not continue after rejected candidate");
        }
        private ModelResponse tool(String id, String path) {
            executionCandidates++;
            return ModelResponse.toolCallResponse("", new ToolCall(
                    "read_file", Map.of("path", path), id));
        }
    }

    private static final class FinalStageProvider implements ModelProvider {
        private final boolean empty;
        private int calls;
        private FinalStageProvider(boolean empty){this.empty=empty;}
        @Override public ModelResponse chat(List<AgentMessage> messages,List<ToolDefinition> tools){
            calls++;
            if(calls==1)return ModelResponse.finalAnswer(PLAN_JSON);
            if(tools.isEmpty())return empty?ModelResponse.finalAnswer(""):
                    ModelResponse.providerError(ModelResponse.ProviderErrorKind.PROVIDER_TIMEOUT);
            return calls%2==0?ModelResponse.toolCallResponse("record durable evidence",
                    new ToolCall("read_file",Map.of("path","AGENTS.md"))):
                    ModelResponse.finalAnswer("step evidence confirmed");
        }
    }

    private static final class RecordingTool implements Tool {
        private int executions;
        @Override public String name() { return "read_file"; }
        @Override public String description() { return "record bounded execution"; }
        @Override public ToolDefinition toDefinition() {
            return new ToolDefinition(name(), description(),
                    new ObjectMapper().createObjectNode());
        }
        @Override public java.util.Optional<ToolAuthorityProjection> authorityProjection(
                ToolCall call) {
            if(!"AGENTS.md".equals(call.arguments().get("path")))
                return java.util.Optional.empty();
            return java.util.Optional.of(ToolAuthorityProjection.exactPath(name(),
                    com.paicli.state.PlanCompletionModels.EffectClass.READ,"AGENTS.md",
                    null,false,null));
        }
        @Override public ToolResult execute(ToolCall call) {
            executions++;
            return ToolResult.success("recorded");
        }
    }
}
