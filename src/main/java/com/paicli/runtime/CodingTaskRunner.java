package com.paicli.runtime;

import com.paicli.agent.AgentLoop;
import com.paicli.agent.AgentSegmentRequest;
import com.paicli.agent.AgentSegmentResult;
import com.paicli.config.Config;
import com.paicli.identity.CanonicalId;
import com.paicli.hook.HookEvent;
import com.paicli.hook.HookRuntime;
import com.paicli.model.AgentMessage;
import com.paicli.skill.SkillRuntime;
import com.paicli.state.CodingRunStore;
import com.paicli.state.ContextRebuilder;
import com.paicli.state.FencedMutationStore;
import com.paicli.state.RepairAttemptStore;
import com.paicli.state.RootAdmissionTransaction;
import com.paicli.state.SkillSelectionStore;
import com.paicli.state.StateStore;
import com.paicli.state.TaskPlanStore;
import com.paicli.state.TaskStore;
import com.paicli.state.VerificationStore;
import com.paicli.state.PlanAuthorityStore;
import com.paicli.state.StructuredPlanModels.ApprovedPlan;
import com.paicli.state.StructuredPlanModels.PlanApplicability;
import com.paicli.state.StructuredPlanModels.Revision;
import com.paicli.state.StructuredPlanModels.StepDefinition;
import com.paicli.state.PlanExecutionModels.Attempt;
import com.paicli.state.PlanExecutionModels.Outcome;
import com.paicli.state.PlanExecutionStore;
import com.paicli.state.PlanCompletionStore;
import com.paicli.state.PlanCompletionModels.CompletionResult;
import com.paicli.state.R1aStoreResults.OwnershipResult;

import java.sql.SQLException;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.UUID;

import static com.paicli.runtime.TaskContinuityModels.*;

/** Coordinates Plan -> Execute -> Verify -> Repair for initial and resumed coding runs. */
public final class CodingTaskRunner {

    private static final int MAX_PERSISTED_SUMMARY_BYTES = 4096;
    private static final int MAX_AGENT_PROMPT_BYTES = 64 * 1024;

    private final StateStore stateStore;
    private final PlanService planService;
    private final VerificationService verificationService;
    private final AgentLoopFactory agentLoopFactory;
    private final Config config;
    private final PersistenceSanitizer sanitizer;
    private final SkillRuntime skillRuntime;
    private final HookRuntime hookRuntime;
    private final RuntimeManifest runtimeManifest;
    private final CodingTaskObserver observer;
    private final CandidateVerificationGate candidateVerificationGate;
    private final InstructionManifestRuntime instructionManifestRuntime;
    private PlanApprovalPort planApprovalPort = PlanApprovalPort.DENY;
    private PlanRuntimeMode planRuntimeMode=PlanRuntimeMode.DEFAULT_ADVISORY;
    private TaskContinuityPolicy taskContinuityPolicy=TaskContinuityPolicy.disabled();
    private BudgetEvidenceSource continuityBudgetEvidence=BudgetEvidenceSource.UNAVAILABLE;
    private TaskProviderBudgetGate taskProviderBudgetGate=TaskProviderBudgetGate.ALLOW_ALL;
    private TaskContinuityCoordinator taskContinuityCoordinator;

    public CodingTaskRunner(StateStore stateStore, PlanService planService,
                            VerificationService verificationService,
                            AgentLoopFactory agentLoopFactory, Config config) {
        this(stateStore, planService, verificationService, agentLoopFactory,
                config, new PersistenceSanitizer(), null, null, null);
    }

    public CodingTaskRunner(StateStore stateStore, PlanService planService,
                            VerificationService verificationService,
                             AgentLoopFactory agentLoopFactory, Config config,
                             PersistenceSanitizer sanitizer) {
        this(stateStore, planService, verificationService, agentLoopFactory,
                config, sanitizer, null, null, null);
    }

    public CodingTaskRunner(StateStore stateStore, PlanService planService,
                            VerificationService verificationService,
                            AgentLoopFactory agentLoopFactory, Config config,
                            PersistenceSanitizer sanitizer,
                            SkillRuntime skillRuntime) {
        this(stateStore, planService, verificationService, agentLoopFactory,
                config, sanitizer, skillRuntime, null, null);
    }

    public CodingTaskRunner(StateStore stateStore, PlanService planService,
                            VerificationService verificationService,
                            AgentLoopFactory agentLoopFactory, Config config,
                            PersistenceSanitizer sanitizer,
                            SkillRuntime skillRuntime,
                            HookRuntime hookRuntime) {
        this(stateStore, planService, verificationService, agentLoopFactory,
                config, sanitizer, skillRuntime, hookRuntime, null);
    }

    public CodingTaskRunner(StateStore stateStore, PlanService planService,
                            VerificationService verificationService,
                            AgentLoopFactory agentLoopFactory, Config config,
                            PersistenceSanitizer sanitizer,
                            SkillRuntime skillRuntime,
                            HookRuntime hookRuntime,
                            RuntimeManifest runtimeManifest) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.planService = Objects.requireNonNull(planService, "planService");
        this.verificationService = Objects.requireNonNull(
                verificationService, "verificationService");
        this.agentLoopFactory = Objects.requireNonNull(agentLoopFactory, "agentLoopFactory");
        this.config = Objects.requireNonNull(config, "config");
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer");
        this.skillRuntime = skillRuntime;
        this.hookRuntime = hookRuntime;
        this.runtimeManifest = runtimeManifest;
        this.observer = CodingTaskObserver.NOOP;
        this.candidateVerificationGate = CandidateVerificationGate.ALLOW;
        this.instructionManifestRuntime = null;
    }

    public CodingTaskRunner(StateStore stateStore, PlanService planService,
                            VerificationService verificationService,
                            AgentLoopFactory agentLoopFactory, Config config,
                            PersistenceSanitizer sanitizer,
                            SkillRuntime skillRuntime,
                            HookRuntime hookRuntime,
                            RuntimeManifest runtimeManifest,
                            CodingTaskObserver observer) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.planService = Objects.requireNonNull(planService, "planService");
        this.verificationService = Objects.requireNonNull(
                verificationService, "verificationService");
        this.agentLoopFactory = Objects.requireNonNull(agentLoopFactory, "agentLoopFactory");
        this.config = Objects.requireNonNull(config, "config");
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer");
        this.skillRuntime = skillRuntime;
        this.hookRuntime = hookRuntime;
        this.runtimeManifest = runtimeManifest;
        this.observer = Objects.requireNonNull(observer, "observer");
        this.candidateVerificationGate = CandidateVerificationGate.ALLOW;
        this.instructionManifestRuntime = null;
    }

    public CodingTaskRunner(StateStore stateStore, PlanService planService,
                            VerificationService verificationService,
                            AgentLoopFactory agentLoopFactory, Config config,
                            PersistenceSanitizer sanitizer,
                            SkillRuntime skillRuntime,
                            HookRuntime hookRuntime,
                            RuntimeManifest runtimeManifest,
                            CodingTaskObserver observer,
                            CandidateVerificationGate candidateVerificationGate) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.planService = Objects.requireNonNull(planService, "planService");
        this.verificationService = Objects.requireNonNull(
                verificationService, "verificationService");
        this.agentLoopFactory = Objects.requireNonNull(agentLoopFactory, "agentLoopFactory");
        this.config = Objects.requireNonNull(config, "config");
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer");
        this.skillRuntime = skillRuntime;
        this.hookRuntime = hookRuntime;
        this.runtimeManifest = runtimeManifest;
        this.observer = Objects.requireNonNull(observer, "observer");
        this.candidateVerificationGate = Objects.requireNonNull(
                candidateVerificationGate, "candidateVerificationGate");
        this.instructionManifestRuntime = null;
    }

    public CodingTaskRunner(StateStore stateStore, PlanService planService,
                            VerificationService verificationService,
                            AgentLoopFactory agentLoopFactory, Config config,
                            PersistenceSanitizer sanitizer, SkillRuntime skillRuntime,
                            HookRuntime hookRuntime, RuntimeManifest runtimeManifest,
                            CodingTaskObserver observer,
                            CandidateVerificationGate candidateVerificationGate,
                            InstructionManifestRuntime instructionManifestRuntime) {
        this(stateStore,planService,verificationService,agentLoopFactory,config,sanitizer,
                skillRuntime,hookRuntime,runtimeManifest,observer,candidateVerificationGate,
                instructionManifestRuntime,PlanApprovalPort.DENY);
    }

    public CodingTaskRunner(StateStore stateStore, PlanService planService,
                            VerificationService verificationService,
                            AgentLoopFactory agentLoopFactory, Config config,
                            PersistenceSanitizer sanitizer, SkillRuntime skillRuntime,
                            HookRuntime hookRuntime, RuntimeManifest runtimeManifest,
                            CodingTaskObserver observer,
                            CandidateVerificationGate candidateVerificationGate,
                            InstructionManifestRuntime instructionManifestRuntime,
                            PlanApprovalPort planApprovalPort) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.planService = Objects.requireNonNull(planService, "planService");
        this.verificationService = Objects.requireNonNull(verificationService, "verificationService");
        this.agentLoopFactory = Objects.requireNonNull(agentLoopFactory, "agentLoopFactory");
        this.config = Objects.requireNonNull(config, "config");
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer");
        this.skillRuntime = skillRuntime;
        this.hookRuntime = hookRuntime;
        this.runtimeManifest = runtimeManifest;
        this.observer = Objects.requireNonNull(observer, "observer");
        this.candidateVerificationGate = Objects.requireNonNull(candidateVerificationGate, "candidateVerificationGate");
        this.instructionManifestRuntime = Objects.requireNonNull(instructionManifestRuntime, "instructionManifestRuntime");
        this.planApprovalPort = Objects.requireNonNull(planApprovalPort, "planApprovalPort");
        this.planService.advisoryPlanMode();
    }

    /** Package-private seam that keeps the unfinished Strict contract reachable by tests only. */
    CodingTaskRunner strictPlanAuthorityForTesting(){
        planRuntimeMode=PlanRuntimeMode.STRICT_INTERNAL;
        planService.strictPlanModeForTesting();
        if(instructionManifestRuntime!=null){
            instructionManifestRuntime.strictPlanAuthorityForTesting();
        }
        return this;
    }

    private boolean strictPlanAuthority(){
        return planRuntimeMode==PlanRuntimeMode.STRICT_INTERNAL;
    }

    /**
     * Explicit opt-in seam. Ordinary production construction remains disabled until a separately
     * reviewed policy and authoritative budget evidence source are supplied.
     */
    public CodingTaskRunner withTaskContinuityPolicy(TaskContinuityPolicy policy,
            BudgetEvidenceSource evidenceSource){
        this.taskContinuityPolicy=Objects.requireNonNull(policy,"policy");
        this.continuityBudgetEvidence=Objects.requireNonNull(evidenceSource,"evidenceSource");
        this.taskContinuityCoordinator=new TaskContinuityCoordinator(
                stateStore,taskContinuityPolicy,continuityBudgetEvidence);
        if(policy.enabled()){
            this.taskProviderBudgetGate=this::providerBudgetAdmission;
            this.planService.bindTaskProviderBudgetGate(taskProviderBudgetGate);
        }
        return this;
    }

    private boolean autonomousContinuityEnabled(){
        return taskContinuityPolicy.enabled()&&!strictPlanAuthority();
    }

    private TaskContinuityCoordinator continuityCoordinator(){
        if(taskContinuityCoordinator==null)taskContinuityCoordinator=
                new TaskContinuityCoordinator(stateStore,taskContinuityPolicy,
                        continuityBudgetEvidence);
        return taskContinuityCoordinator;
    }

    public CodingTaskResult run(String taskId, String goal) throws SQLException {
        requireText(taskId, "taskId");
        requireText(goal, "goal");

        if(instructionManifestRuntime==null){
            stateStore.tasks.createTask(taskId,
                    sanitizer.sanitizeAndTruncate(goal,MAX_AGENT_PROMPT_BYTES),
                    config.effectiveProvider(),config.model(),config.approvalMode(),
                    config.effectiveMaxIterations());
            return startNewRun(taskId,goal,CodingRunStore.KIND_INITIAL,List.of(),0);
        }
        var admission = instructionManifestRuntime.admitRoot(
                com.paicli.identity.CanonicalId.parse(taskId), goal,
                config.maxProjectInstructionChars(), config, runtimeManifest, Instant.now());
        return startAdmittedRootRun(taskId,goal,admission.instructions().prompt(),
                admission.runtimeRun());
    }

    /** Starts an already atomically admitted Root or R1E Child; no admission or Plan is inherited. */
    public CodingTaskResult runAdmittedChild(String taskId,String goal,String instructionContext,
            RootAdmissionTransaction.RuntimeRun admitted,
            com.paicli.state.FollowUpModels.ChildStartClaim claim)throws SQLException{
        return startAdmittedRun(taskId,goal,instructionContext,admitted,claim.lease());
    }

    private CodingTaskResult startAdmittedRootRun(String taskId,String goal,
            String instructionContext,RootAdmissionTransaction.RuntimeRun admitted)
            throws SQLException{
        return startAdmittedRun(taskId,goal,instructionContext,admitted,null);
    }

    private CodingTaskResult startAdmittedRun(String taskId,String goal,
            String instructionContext,RootAdmissionTransaction.RuntimeRun admitted,
            com.paicli.state.R1aDurableModels.OwnershipLease preclaimedLease)throws SQLException{
        if(!taskId.equals(admitted.taskId())
                ||!CodingRunStore.KIND_INITIAL.equals(admitted.runKind())
                ||!(CodingRunStore.PHASE_PLANNING.equals(admitted.phase())
                    ||CodingRunStore.PHASE_EXECUTING.equals(admitted.phase()))){
            throw new IllegalStateException("Committed Initial runtime authority mismatch");
        }
        Instant acquiredAt=Instant.now();
        Duration leaseDuration=Duration.ofSeconds(Math.multiplyExact(
                (long)config.commandTimeoutSeconds(),(long)config.effectiveMaxIterations()));
        com.paicli.state.R1aDurableModels.OwnershipLease lease=preclaimedLease;
        if(lease==null){
            String owner="initial-"+UUID.randomUUID();String token=UUID.randomUUID().toString();
            OwnershipResult acquired=stateStore.r1aDurable.acquireOwnership(
                    com.paicli.identity.CanonicalId.parse(taskId),owner,
                    com.paicli.identity.CanonicalId.parse(admitted.runId()),token,acquiredAt,
                    acquiredAt.plus(leaseDuration));
            if(acquired!=OwnershipResult.SUCCESS)throw new IllegalStateException(
                    "Initial Plan execution ownership rejected: "+acquired);
            lease=stateStore.r1aDurable.readOwnership(
                    com.paicli.identity.CanonicalId.parse(taskId)).orElseThrow();
        }else if(!lease.taskId().value().equals(taskId)||!lease.ownerRunId().value().equals(admitted.runId())
                ||stateStore.r1aDurable.validateExecutionAuthority(lease.taskId(),lease.ownerInstanceId(),
                    lease.ownerRunId(),lease.leaseToken(),lease.fencingToken(),lease.casVersion(),acquiredAt)
                    !=OwnershipResult.SUCCESS){
            throw new IllegalStateException("Preclaimed Child Initial Run authority rejected");
        }
        FencedExecutionGuard guard=FencedExecutionGuard.system(
                stateStore,TaskExecutionAuthority.initial(lease));
        notifyObserver(() -> observer.executionAuthorityBound(guard));
        notifyObserver(() -> observer.taskStarted(taskId,admitted.runId(),
                sanitizer.sanitizeAndTruncate(goal,MAX_PERSISTED_SUMMARY_BYTES)));
        notifyObserver(() -> observer.phaseChanged(
                taskId,admitted.runId(),admitted.phase()));
        String verificationCommand=config.isVerifyEnabled()
                ?config.effectiveVerificationCommand():"";
        ExecutionState state=new ExecutionState(taskId,admitted.runId(),goal,
                verificationCommand,admitted.maxRepairAttempts(),List.of(),
                admitted.startedIteration(),0,instructionContext,guard,
                config.isPlanEnabled());
        return CodingRunStore.PHASE_PLANNING.equals(admitted.phase())
                ?continuePlanning(state):continueExecuting(state);
    }

    public CodingTaskResult resume(ContextRebuilder.ResumedContext context) throws SQLException {
        Objects.requireNonNull(context, "context");
        requireText(context.taskId(), "taskId");
        requireText(context.goal(), "goal");

        stateStore.tasks.findById(context.taskId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Task not found: " + context.taskId()));
        CodingRunStore.CodingRunRecord active = stateStore.codingRuns
                .findActiveByTask(context.taskId()).orElse(null);
        if (active == null) {
            stateStore.tasks.prepareForResume(context.taskId());
            return startNewRun(
                    context.taskId(), context.goal(), CodingRunStore.KIND_RESUME,
                    context.conversation(), context.startIteration());
        }
        validateOrBindRuntimeManifest(active);
        stateStore.tasks.prepareForResume(context.taskId());

        ExecutionState state = new ExecutionState(
                context.taskId(), active.runId(), context.goal(),
                active.verificationCommand(), active.maxRepairAttempts(),
                context.conversation(),
                Math.max(context.startIteration(), active.lastIteration()),
                active.repairAttemptsCompleted(),"",null,config.isPlanEnabled());
        loadExistingPlan(state);

        return switch (active.phase()) {
            case CodingRunStore.PHASE_PLANNING -> continuePlanning(state);
            case CodingRunStore.PHASE_EXECUTING -> continueExecuting(state);
            case CodingRunStore.PHASE_VERIFYING -> state.verificationCommand.isBlank()
                    ?completeWithoutCandidateVerification(state):continueVerifying(state, true);
            case CodingRunStore.PHASE_REPAIRING -> continueInterruptedRepair(state);
            default -> throw new IllegalStateException(
                    "Unsupported active coding run phase: " + active.phase());
        };
    }

    public CodingTaskResult recover(TaskRecoveryService.Success recovery, String goal)
            throws SQLException {
        return recover(recovery,goal,FencedExecutionGuard.system(
                stateStore,recovery.executionAuthority()));
    }

    public CodingTaskResult recover(TaskRecoveryService.Success recovery,String goal,
            FencedExecutionGuard guard)throws SQLException{
        Objects.requireNonNull(recovery, "recovery");
        requireText(goal, "goal");
        String taskId = recovery.taskId().value();
        stateStore.tasks.findById(taskId).orElseThrow(
                () -> new IllegalArgumentException("Task not found: " + taskId));
        CodingRunStore.CodingRunRecord existing=stateStore.codingRuns
                .findById(recovery.resumeRun().id().value()).orElse(null);
        if(existing!=null){
            if(!CodingRunStore.PHASE_VERIFYING.equals(existing.phase()))
                throw new IllegalStateException("Resume Run already entered the execution runtime");
            guard.heartbeat("FINAL_RECOVERY_RUNTIME_ENTRY");
            guard.prepareTaskForResume();
            ExecutionState state=new ExecutionState(taskId,existing.runId(),goal,
                    existing.verificationCommand(),existing.maxRepairAttempts(),
                    recovery.conversation(),Math.max(recovery.startIteration(),
                            existing.lastIteration()),existing.repairAttemptsCompleted(),
                    recovery.instructions().prompt(),guard,config.isPlanEnabled());
            loadExistingPlan(state);
            restoreAcceptedFinal(state,existing);
            return state.verificationCommand.isBlank()?completeWithoutCandidateVerification(state)
                    :continueVerifying(state,true);
        }
        guard.heartbeat("RECOVERY_RUNTIME_ENTRY");
        guard.prepareTaskForResume();
        PlanApplicability applicability=recovery.context().planApplicability();
        ApprovedPlan recoveredPlan=recovery.context().structuredPlan();
        if(applicability==PlanApplicability.PLAN_REQUIRED&&!strictPlanAuthority())
            throw new IllegalStateException("STRICT_PLAN_AUTHORITY_INTERNAL_ONLY");
        if(applicability==PlanApplicability.PLAN_REQUIRED&&recoveredPlan==null)
            throw new IllegalStateException("PLAN_REQUIRED Recovery has no approved Plan authority");
        if(applicability==PlanApplicability.PLAN_DISABLED&&recoveredPlan!=null)
            throw new IllegalStateException("PLAN_DISABLED Recovery cannot bind a Plan authority");
        if(applicability==PlanApplicability.LEGACY_PLAN_ONLY)
            throw new IllegalStateException("Legacy Plan Recovery is unavailable");
        CodingRunStore.CodingRunRecord predecessor = stateStore.codingRuns
                .findById(recovery.resumeRun().resumedFromRunId().value())
                .orElseThrow(() -> new IllegalStateException(
                        "Recovery predecessor has no durable runtime contract"));
        return startNewRun(taskId, goal, CodingRunStore.KIND_RESUME,
                recovery.conversation(), recovery.startIteration(),
                recovery.resumeRun().id().value(), recovery.instructions().prompt(),guard,
                recoveredPlan,config.isPlanEnabled(),
                predecessor.verificationCommand());
    }

    private void restoreAcceptedFinal(ExecutionState state,
            CodingRunStore.CodingRunRecord run)throws SQLException{
        var occurrence=stateStore.r1aDurable.findAcceptedProviderFinal(
                CanonicalId.parse(state.taskId),CanonicalId.parse(state.runId),
                run.lastIteration()).orElseThrow(()->new IllegalStateException(
                        "ACCEPTED_FINAL_OCCURRENCE_UNAVAILABLE"));
        AgentMessage claim=stateStore.events.findFinalAtIteration(
                state.taskId,run.lastIteration()).orElseThrow(()->new IllegalStateException(
                        "ACCEPTED_FINAL_EVENT_UNAVAILABLE"));
        if(!Objects.equals(occurrence.resultHash(),
                FencedMutationStore.providerFinalResultHash(claim.content())))
            throw new IllegalStateException("ACCEPTED_FINAL_HASH_MISMATCH");
        state.answer=claim.content();
        state.providerOccurrenceId=occurrence.id().value();
        state.finalCandidateDigest=occurrence.finalCandidateDigest();
        state.iteration=run.lastIteration();
    }

    private CodingTaskResult startNewRun(String taskId, String goal, String runKind,
                                         List<AgentMessage> conversation,
                                         int startIteration) throws SQLException {
        return startNewRun(taskId, goal, runKind, conversation, startIteration, null, "",null,
                null,config.isPlanEnabled(),null);
    }

    private CodingTaskResult startNewRun(String taskId, String goal, String runKind,
                                         List<AgentMessage> conversation, int startIteration,
                                         String requestedRunId, String instructionContext)
            throws SQLException {
        return startNewRun(taskId,goal,runKind,conversation,startIteration,requestedRunId,
                instructionContext,null,null,config.isPlanEnabled(),null);
    }

    private CodingTaskResult startNewRun(String taskId, String goal, String runKind,
                                         List<AgentMessage> conversation, int startIteration,
                                         String requestedRunId, String instructionContext,
                                         FencedExecutionGuard executionGuard,
                                         ApprovedPlan recoveredPlan,
                                         boolean planEnabled,
                                         String durableVerificationCommand)
            throws SQLException {
        String runId = requestedRunId == null ? newId() : requestedRunId;
        String verificationCommand = durableVerificationCommand != null
                ? durableVerificationCommand
                : config.isVerifyEnabled() ? config.effectiveVerificationCommand() : "";
        int maxRepairAttempts = config.effectiveMaxRepairAttempts();

        String persistedVerification=sanitizer.sanitizeAndTruncate(
                verificationCommand,config.effectiveMaxVerificationOutputBytes());
        String initialPhase=planEnabled||recoveredPlan!=null
                ?CodingRunStore.PHASE_PLANNING:CodingRunStore.PHASE_EXECUTING;
        if(executionGuard!=null)executionGuard.createCodingRun(initialPhase,
                persistedVerification,maxRepairAttempts,startIteration);
        else stateStore.codingRuns.create(runId,taskId,runKind,initialPhase,
                persistedVerification,maxRepairAttempts,startIteration);
        if (runtimeManifest != null) {
            if(executionGuard!=null)executionGuard.updateRuntimeManifest(
                    runtimeManifest.skillsSha256(),runtimeManifest.hooksSha256());
            else stateStore.codingRuns.updateRuntimeManifestHashes(runId,
                    runtimeManifest.skillsSha256(),runtimeManifest.hooksSha256());
        }
        if(executionGuard!=null)executionGuard.markTaskRunning();
        else stateStore.tasks.markRunning(taskId);
        if(executionGuard!=null)
            notifyObserver(() -> observer.executionAuthorityBound(executionGuard));
        notifyObserver(() -> observer.taskStarted(
                taskId, runId,
                sanitizer.sanitizeAndTruncate(goal, MAX_PERSISTED_SUMMARY_BYTES)));
        notifyObserver(() -> observer.phaseChanged(
                taskId, runId, initialPhase));

        ExecutionState state = new ExecutionState(
                taskId, runId, goal, verificationCommand, maxRepairAttempts,
                conversation, startIteration, 0, instructionContext,executionGuard,planEnabled);
        if(recoveredPlan!=null){
            state.planId=recoveredPlan.revision().planRevisionId().value();
            state.planText=PlanService.render(recoveredPlan.revision());
            state.structuredPlanBound=true;
            state.structuredRevision=recoveredPlan.revision();
        }else if(planEnabled&&executionGuard!=null&&!strictPlanAuthority()){
            loadExistingPlan(state);
        }
        return CodingRunStore.PHASE_PLANNING.equals(initialPhase)
                ?continuePlanning(state):continueExecuting(state);
    }

    private CodingTaskResult continuePlanning(ExecutionState state) throws SQLException {
        heartbeat(state,"PLANNING_BOUNDARY");
        ensureContinuitySegment(state);
        HookRuntime.LifecycleResult before = runHooks(
                state, HookEvent.BEFORE_PLAN, "plan");
        if(steeringPausedByHook(state,before))return handleMaterialSteering(state);
        if (before != null && before.shouldFail()) {
            return fail(state, null, before.failureMessage());
        }
        try {
            if (state.planId == null && state.planEnabled && state.executionGuard!=null
                    && instructionManifestRuntime!=null) {
                SkillRuntime.SkillContext skills=selectSkills(
                        state,SkillSelectionStore.STAGE_PLAN,"plan",null);
                var revision=planService.generateStructured(
                        com.paicli.identity.CanonicalId.parse(state.taskId),
                        com.paicli.identity.CanonicalId.parse(state.runId),state.goal,
                        instructionAndSkillContext(state,skills.context()),state.executionGuard,1,
                        state.planningAttemptOrdinal,Instant.now(),
                        new com.paicli.state.PlanCompletionProfilePolicy.Capabilities(
                                !state.verificationCommand.isBlank()));
                PlanAuthorityStore.Mutation proposed=strictPlanAuthority()
                        ?stateStore.planAuthorities.propose(
                                state.executionGuard.current().snapshot(),Instant.now(),revision)
                        :stateStore.planAuthorities.proposeAdvisory(
                                state.executionGuard.current().snapshot(),Instant.now(),revision);
                requirePlanSuccess(proposed,"PLAN_PROPOSAL_REJECTED");
                PlanAuthorityStore.Mutation validated=strictPlanAuthority()
                        ?stateStore.planAuthorities.validateRevision(
                                state.executionGuard.current().snapshot(),Instant.now(),
                                revision.planRevisionId(),0)
                        :stateStore.planAuthorities.validateRevisionAdvisory(
                                state.executionGuard.current().snapshot(),Instant.now(),
                                revision.planRevisionId(),0);
                requirePlanSuccess(validated,"PLAN_VALIDATION_REJECTED");
                PlanApprovalPort.Decision decision=planApprovalPort.requestApproval(
                        revision,PlanService.render(revision));
                if(!decision.approved()){
                    requirePlanSuccess(stateStore.planAuthorities.reject(
                            state.executionGuard.current().snapshot(),Instant.now(),
                            revision.planRevisionId(),1,"approval-"+UUID.randomUUID(),
                            decision.approvedBy(),decision.policyVersion(),decision.reason()),
                            "PLAN_REJECTION_PERSISTENCE_FAILED");
                    return fail(state,null,"PLAN_APPROVAL_REQUIRED");
                }
                String approvalId="approval-"+UUID.randomUUID();
                PlanAuthorityStore.Mutation activated=strictPlanAuthority()
                        ?stateStore.planAuthorities.approveAndActivate(
                                state.executionGuard.current().snapshot(),Instant.now(),
                                revision.planRevisionId(),1,0,approvalId,decision.approvedBy(),
                                decision.policyVersion(),decision.reason())
                        :stateStore.planAuthorities.approveAdvisory(
                                state.executionGuard.current().snapshot(),Instant.now(),
                                revision.planRevisionId(),1,approvalId,decision.approvedBy(),
                                decision.policyVersion(),decision.reason());
                requirePlanSuccess(activated,"PLAN_AUTHORITY_CONFLICT");
                state.planId=revision.planRevisionId().value();
                state.planText=PlanService.render(revision);
                state.structuredPlanBound=strictPlanAuthority();
                state.structuredRevision=revision;
                PlanResult plan=new PlanResult(state.planId,state.runId,state.taskId,state.planText);
                notifyObserver(()->observer.planCreated(state.taskId,state.runId,plan));
            } else if (state.planId == null && state.planEnabled) {
                SkillRuntime.SkillContext skills = selectSkills(
                        state, SkillSelectionStore.STAGE_PLAN, "plan", null);
                PlanResult plan = planService.generate(
                        state.taskId, state.runId, state.goal,
                        instructionAndSkillContext(state, skills.context()),
                        state.executionGuard);
                state.planId = plan.planId();
                state.planText = plan.planText();
                transitionPlan(state,state.planId,TaskPlanStore.STATUS_GENERATED,
                        TaskPlanStore.STATUS_ACTIVE);
                notifyObserver(() -> observer.planCreated(
                        state.taskId, state.runId, plan));
            } else if (state.planId != null && !state.structuredPlanBound) {
                activateGeneratedPlan(state);
            }
        } catch (com.paicli.interactive.approval.InteractiveApprovalCoordinator
                .PlanApprovalPresentationException e) {
            return fail(state, null, "PLAN_APPROVAL_PRESENTATION_FAILED");
        } catch (PlanService.PlanGenerationException e) {
            TaskProviderBudgetGate.AdmissionException providerBudget=
                    providerBudgetFailure(e);
            if(autonomousContinuityEnabled()&&providerBudget!=null){
                Decision decision=continuityCoordinator().terminateProviderAdmission(
                        new TaskContinuityCoordinator.Request(state.taskId,state.runId,
                                state.segmentOrdinal,state.planningAttemptOrdinal,state.iteration,
                                SegmentTerminalReason.OTHER_TYPED_TERMINAL,
                                state.executionGuard),providerBudget.code());
                return fail(state,null,"Planning failed: "+decision.code());
            }
            if(autonomousContinuityEnabled()
                    &&"STRUCTURED_PLAN_REPAIR_FAILED".equals(e.code())){
                persistContinuityCheckpoint(state,"SEGMENT_FAILED");
                Decision decision=continuationDecision(state,
                        SegmentTerminalReason.STRUCTURED_PLAN_REPAIR_FAILED);
                if(decision.startsNextSegment())return continuePlanning(state);
                return fail(state,null,"Planning failed: "+decision.code());
            }
            return fail(state, null, "Planning failed: " + safeMessage(e));
        } catch (SkillRuntime.SkillRuntimeException | IllegalArgumentException e) {
            return fail(state, null, "Planning failed: " + safeMessage(e));
        }

        HookRuntime.LifecycleResult after = runHooks(
                state, HookEvent.AFTER_PLAN, "plan");
        if(steeringPausedByHook(state,after))return handleMaterialSteering(state);
        if (after != null && after.shouldFail()) {
            return fail(state, null, after.failureMessage());
        }

        transitionPhase(state, CodingRunStore.PHASE_PLANNING,
                CodingRunStore.PHASE_EXECUTING);
        return continueExecuting(state);
    }

    private static void requirePlanSuccess(PlanAuthorityStore.Mutation result,String code){
        if(result.status()!=PlanAuthorityStore.Status.SUCCESS)
            throw new PlanService.PlanGenerationException(code+": "+result.status());
    }

    private CodingTaskResult continueExecuting(ExecutionState state) throws SQLException {
        heartbeat(state,"EXECUTION_BOUNDARY");
        if(state.executionGuard!=null&&strictPlanAuthority()){
            var pendingDraft=stateStore.steerings.latestAppliedPlanDraft(
                    CanonicalId.parse(state.taskId),CanonicalId.parse(state.runId));
            if(pendingDraft.isPresent()){
                var activePlan=stateStore.planAuthorities.readActive(CanonicalId.parse(state.taskId));
                if(activePlan.status()!=PlanAuthorityStore.Status.SUCCESS
                        ||!activePlan.plan().revision().planRevisionId().equals(pendingDraft.get()))
                    return handleMaterialSteering(state);
            }
        }
        var beforeAttempt=steeringSafePoint(state,
                SteeringSafePointCoordinator.Candidate.NEXT_STEP_ATTEMPT_BEFORE);
        if(beforeAttempt.pauseExecution())return handleMaterialSteering(state);
        Attempt planAttempt=null;
        StepDefinition planStep=null;
        long planStepVersion=-1;
        if(state.structuredPlanBound){
            PlanExecutionStore.StartResult started=state.executionGuard
                    .startNextPlanAttempt(state.iteration);
            if(started.status()==PlanExecutionStore.Status.NO_READY_PLAN_STEP){
                if(stateStore.planExecutions.allSucceeded(
                        com.paicli.identity.CanonicalId.parse(state.taskId),
                        com.paicli.identity.CanonicalId.parse(state.planId))){
                    return awaitingCompletionEvidence(state);
                }
                return fail(state,null,"NO_READY_PLAN_STEP");
            }
            if(started.status()!=PlanExecutionStore.Status.SUCCESS)
                throw new FencedExecutionGuard.AuthorityLostException(
                        "PLAN_ATTEMPT_"+started.status(),"PLAN_ATTEMPT_START",null);
            planAttempt=started.value().attempt();
            planStepVersion=started.value().step().transitionVersion();
            state.currentPlanStepVersion=planStepVersion;
            CanonicalId currentStepId=planAttempt.stepRevisionId();
            planStep=state.structuredRevision.steps().stream()
                    .filter(step->step.stepRevisionId().equals(currentStepId))
                    .findFirst().orElseThrow(()->new IllegalStateException(
                            "PLAN_STEP_STATE_INTEGRITY_FAILURE"));
            state.currentPlanAttempt=planAttempt;
            state.currentPlanStep=planStep;
            StepDefinition startedStep=planStep;
            int startedAttempt=planAttempt.attemptNumber();
            notifyObserver(()->observer.planStepStarted(state.taskId,state.runId,
                    state.structuredRevision,startedStep,startedAttempt));
        }
        String occurrenceKey = "execute:" + state.iteration;
        HookRuntime.LifecycleResult before = runHooks(
                state, HookEvent.BEFORE_EXECUTE, occurrenceKey);
        if(steeringPausedByHook(state,before))return handleMaterialSteering(state);
        if (before != null && before.shouldFail()) {
            if(planAttempt!=null)requirePlanAttemptMutation(state.executionGuard
                    .completePlanAttempt(planAttempt,Outcome.FAILED,state.iteration,
                            planStepVersion,"BEFORE_EXECUTE_HOOK",before.failureMessage()));
            return fail(state, null, before.failureMessage());
        }
        AgentSegmentResult segment;
        try {
            SkillRuntime.SkillContext skills = selectSkills(
                    state, SkillSelectionStore.STAGE_EXECUTE,
                    "execute:" + state.iteration, null);
            String stepContext=planStep==null?"":"""

                    Current durable Plan execution authority:
                    revision: %s
                    step: %s
                    attempt: %d
                    title: %s
                    definition: %s
                    Execute only this Step. Step/Task completion is owned by the runtime.
                    Use Tool calls only for this durable Step. When this Step is complete,
                    return its result without issuing Tool calls for a later Step; the runtime
                    will terminalize this Step and activate the next durable Step.
                    Prefer list_files for directory enumeration, read_file for file inspection,
                    and grep for content search. Use run_command only when the dedicated
                    read-only Tools cannot express the required operation.
                    """.formatted(state.planId,planStep.stepRevisionId().value(),
                            planAttempt.attemptNumber(),planStep.title(),planStep.definition());
            segment = agentLoop(state).runSegment(new AgentSegmentRequest(
                    state.goal,
                    state.conversation,
                    executionPrompt(state.goal, state.planText,
                            instructionAndSkillContext(state, skills.context()))+stepContext,
                    state.iteration,
                    config.effectiveMaxIterations(),
                    planAttempt==null?null:new AgentSegmentRequest.PlanExecutionContext(
                            planAttempt.taskId(),planAttempt.planRevisionId(),
                            planAttempt.stepRevisionId(),planAttempt.runId(),
                            planAttempt.attemptNumber())));
        } catch (RuntimeException e) {
            if(e instanceof FencedExecutionGuard.AuthorityLostException)throw e;
            if (e instanceof CancellationToken.TaskCancellationException){
                if(planAttempt!=null)requirePlanAttemptMutation(state.executionGuard
                        .completePlanAttempt(planAttempt,Outcome.CANCELLED,state.iteration,
                                planStepVersion,"CANCELLED","Task execution was cancelled"));
                throw e;
            }
            if(e instanceof ObservedIterationFailure observed
                    &&observed.observedIteration().isPresent()){
                state.iteration=Math.max(state.iteration,
                        observed.observedIteration().getAsInt());
                updateLastIteration(state);
            }
            if(autonomousContinuityEnabled()
                    &&e instanceof AgentLoop.TaskToolBudgetAdmissionException budget){
                restoreContinuityCheckpoint(state);
                if(planAttempt!=null)requirePlanAttemptMutation(state.executionGuard
                        .completePlanAttempt(planAttempt,Outcome.FAILED,state.iteration,
                                planStepVersion,budget.code(),budget.code()));
                Decision decision=continuityCoordinator().terminateToolAdmission(
                        new TaskContinuityCoordinator.Request(state.taskId,state.runId,
                                state.segmentOrdinal,state.planningAttemptOrdinal,state.iteration,
                                SegmentTerminalReason.OTHER_TYPED_TERMINAL,
                                state.executionGuard),budget.code());
                return fail(state,null,"Agent execution failed: "+decision.code());
            }
            if(autonomousContinuityEnabled()
                    &&e instanceof TaskProviderBudgetGate.AdmissionException budget){
                restoreContinuityCheckpoint(state);
                if(planAttempt!=null)requirePlanAttemptMutation(state.executionGuard
                        .completePlanAttempt(planAttempt,Outcome.FAILED,state.iteration,
                                planStepVersion,budget.code(),budget.code()));
                Decision decision=continuityCoordinator().terminateProviderAdmission(
                        new TaskContinuityCoordinator.Request(state.taskId,state.runId,
                                state.segmentOrdinal,state.planningAttemptOrdinal,state.iteration,
                                SegmentTerminalReason.OTHER_TYPED_TERMINAL,
                                state.executionGuard),budget.code());
                return fail(state,null,"Agent execution failed: "+decision.code());
            }
            if(autonomousContinuityEnabled()
                    &&e instanceof AgentLoop.ProviderProtocolException protocol){
                SegmentTerminalReason reason=continuityReason(protocol);
                if(reason!=null){
                    restoreContinuityCheckpoint(state);
                    Decision decision=continuationDecision(state,reason);
                    if(decision.startsNextSegment())return continueExecuting(state);
                    if(decision.decision()==ContinuationDecision.WAIT_FOR_USER)
                        return waitingForUser(state,decision.code());
                    return fail(state,null,"Agent execution failed: "+decision.code());
                }
            }
            String failureClass=e instanceof PlanToolAdmissionService.AdmissionRejectedException rejected
                    ?rejected.code():e instanceof AgentLoop.ProviderProtocolException
                    ?"PROVIDER_PROTOCOL_FAILURE":e instanceof AgentLoop.AgentNoProgressException
                    ?"AGENT_NO_PROGRESS":"AGENT_EXECUTION_FAILURE";
            String failureReason=e instanceof AgentLoop.ProviderProtocolException
                    ?failureClass:e instanceof AgentLoop.AgentNoProgressException
                    ?"IDENTICAL_TOOL_LOOP":safeMessage(e);
            if(planAttempt!=null)requirePlanAttemptMutation(state.executionGuard
                    .completePlanAttempt(planAttempt,Outcome.FAILED,state.iteration,
                            planStepVersion,failureClass,failureReason));
            rethrowPersistenceFailure(e);
            return fail(state, null, "Agent execution failed: " + safeMessage(e));
        }

        heartbeat(state,"EXECUTION_SEGMENT_RESULT");

        state.answer = segment.answer();
        state.iteration = segment.endIteration();
        state.conversation = segment.conversation();
        if(segment.providerOccurrenceId()==null)updateLastIteration(state);
        if(segment.outcome()==AgentSegmentResult.Outcome.PROVIDER_ERROR){
            String failureClass=segment.providerErrorKind().name();
            if(planAttempt!=null)requirePlanAttemptMutation(state.executionGuard
                    .completePlanAttempt(planAttempt,Outcome.FAILED,state.iteration,
                            planStepVersion,failureClass,failureClass));
            return fail(state,null,"Provider execution failed: "+failureClass);
        }
        if(segment.outcome()==AgentSegmentResult.Outcome.PAUSED)return handleMaterialSteering(state);
        if (!segment.completed()) {
            if(planAttempt!=null)requirePlanAttemptMutation(state.executionGuard
                    .completePlanAttempt(planAttempt,Outcome.FAILED,state.iteration,
                            planStepVersion,"SEGMENT_LIMIT",
                            "Agent execution reached the segment iteration limit"));
            if(autonomousContinuityEnabled()){
                Decision decision=continuationDecision(state,
                        SegmentTerminalReason.ITERATION_EXHAUSTED);
                if(decision.startsNextSegment())return continueExecuting(state);
                return fail(state,null,"Agent execution failed: "+decision.code());
            }
            return fail(state, null, "Agent execution reached the segment iteration limit");
        }

        if(planAttempt==null&&segment.providerOccurrenceId()!=null&&state.executionGuard!=null){
            try{acceptFinalClaim(state,segment,CodingRunStore.PHASE_EXECUTING);}
            catch(RuntimeException error){return fail(state,null,
                    "FINAL claim commit failed: "+safeMessage(error));}
        }else if(planAttempt==null&&state.executionGuard!=null
                &&state.structuredRevision==null){
            return fail(state,null,"Provider execution completed without a durable FINAL occurrence");
        }

        if(autonomousContinuityEnabled()&&segment.userDeniedTool()){
            Decision decision=continuationDecision(state,
                    SegmentTerminalReason.TOOL_PREFLIGHT_APPROVAL_REQUIRED);
            return waitingForUser(state,decision.code());
        }

        if(!completeContinuitySegment(state))
            return fail(state,null,"TASK_BUDGET_EVIDENCE_INCOMPLETE");

        if(planAttempt!=null&&segment.userDeniedTool()){
            requirePlanAttemptMutation(state.executionGuard.completePlanAttempt(
                    planAttempt,Outcome.BLOCKED,state.iteration,planStepVersion,
                    "TOOL_APPROVAL_DENIED",
                    "A required Plan Tool was denied by the user"));
            return new CodingTaskResult(state.taskId,state.runId,state.planId,
                    TaskStore.STATUS_RUNNING,null,state.repairAttempts,
                    "Required Plan Tool approval was denied; Task remains incomplete.",
                    state.executionGuard);
        }

        if(planAttempt!=null&&planStep.requiredEvidenceKinds().contains(
                com.paicli.state.PlanCompletionModels.EvidenceKind.TOOL_CALL)){
            var semantic=stateStore.planCompletions.evaluateToolEvidence(planAttempt.taskId(),
                    planAttempt.planRevisionId(),planAttempt.runId());
            var progress=semantic.step(planAttempt.stepRevisionId());
            if(!semantic.integrityValid()||progress==null||progress.requirements().isEmpty()){
                requirePlanAttemptMutation(state.executionGuard.completePlanAttempt(
                        planAttempt,Outcome.FAILED,state.iteration,planStepVersion,
                        "PLAN_STEP_EVIDENCE_SCOPE_UNAVAILABLE",
                        "Structured per-Step Tool Evidence scope is unavailable"));
                return fail(state,null,"PLAN_STEP_EVIDENCE_SCOPE_UNAVAILABLE");
            }
            boolean deferredAnyVerification=planStep.completionRule()
                    ==com.paicli.state.PlanCompletionModels.CompletionRuleType
                            .ANY_REQUIRED_EVIDENCE
                    &&planStep.requiredEvidenceKinds().contains(
                            com.paicli.state.PlanCompletionModels.EvidenceKind
                                    .VERIFICATION_RESULT)
                    &&!state.verificationCommand.isBlank();
            if(!progress.satisfied()&&!deferredAnyVerification){
                if(progress.unmet().isEmpty()){
                    boolean verificationPending=onlyVerificationEvidencePending(progress);
                    if(verificationPending){
                        // The configured verification phase produces this typed slot after the
                        // execution Step is durably SUCCEEDED.
                    }else{
                        requirePlanAttemptMutation(state.executionGuard.completePlanAttempt(
                                planAttempt,Outcome.FAILED,state.iteration,planStepVersion,
                                "EVIDENCE_MISSING","Typed Step Evidence is missing"));
                        return fail(state,null,"EVIDENCE_MISSING: typed Step Evidence remains unsatisfied");
                    }
                }else{
                String budgetKey=planAttempt.stepRevisionId().value();
                if(!state.evidenceRecoverySteps.add(budgetKey)
                        ||progress.unmet().stream().anyMatch(value->
                                value.remainingOccurrences()<value.remainingSuccesses())){
                    requirePlanAttemptMutation(state.executionGuard.completePlanAttempt(
                            planAttempt,Outcome.FAILED,state.iteration,planStepVersion,
                            "EVIDENCE_MISSING","Per-Step Tool Evidence recovery budget exhausted"));
                    return fail(state,null,"EVIDENCE_MISSING: Step Evidence remains unsatisfied");
                }
                String recoveryPrompt=stepEvidenceRecoveryPrompt(progress);
                AgentSegmentResult recovery=agentLoop(state).runSegment(new AgentSegmentRequest(
                        state.goal,state.conversation,recoveryPrompt,state.iteration,
                        Math.min(3,config.effectiveMaxIterations()),
                        new AgentSegmentRequest.PlanExecutionContext(planAttempt.taskId(),
                                planAttempt.planRevisionId(),planAttempt.stepRevisionId(),
                                planAttempt.runId(),planAttempt.attemptNumber())));
                state.answer=recovery.answer();state.iteration=recovery.endIteration();
                state.conversation=recovery.conversation();updateLastIteration(state);
                var recovered=stateStore.planCompletions.evaluateToolEvidence(
                        planAttempt.taskId(),planAttempt.planRevisionId(),planAttempt.runId());
                var recoveredStep=recovered.step(planAttempt.stepRevisionId());
                if(!recovery.completed()||!recovered.integrityValid()||recoveredStep==null
                        ||!recoveredStep.satisfied()){
                    requirePlanAttemptMutation(state.executionGuard.completePlanAttempt(
                            planAttempt,Outcome.FAILED,state.iteration,planStepVersion,
                            "EVIDENCE_MISSING","Required per-Step Tool Evidence was not acquired"));
                    return fail(state,null,"EVIDENCE_MISSING: Step Evidence remains unsatisfied");
                }
                }
            }
        }

        if(planAttempt!=null&&state.answer!=null&&!state.answer.isBlank()){
            String conclusion=sanitizer.sanitizeAndTruncate(state.answer,2048);
            AgentSegmentRequest.PlanExecutionContext context=
                    new AgentSegmentRequest.PlanExecutionContext(planAttempt.taskId(),
                            planAttempt.planRevisionId(),planAttempt.stepRevisionId(),
                            planAttempt.runId(),planAttempt.attemptNumber());
            notifyObserver(()->observer.modelMessage(state.taskId,state.iteration,
                    "CONCLUSION",conclusion,context));
        }

        HookRuntime.LifecycleResult after = runHooks(
                state, HookEvent.AFTER_EXECUTE, occurrenceKey);
        if(steeringPausedByHook(state,after))return handleMaterialSteering(state);
        if (after != null && after.shouldFail()) {
            if(planAttempt!=null)requirePlanAttemptMutation(state.executionGuard
                    .completePlanAttempt(planAttempt,Outcome.FAILED,state.iteration,
                            planStepVersion,"AFTER_EXECUTE_HOOK",after.failureMessage()));
            return fail(state, null, after.failureMessage());
        }

        if(planAttempt!=null){
            requirePlanAttemptMutation(state.executionGuard.completePlanAttempt(
                    planAttempt,Outcome.SUCCEEDED,state.iteration,planStepVersion,null,null));
            state.currentPlanAttempt=null;
            state.currentPlanStep=null;
            state.currentPlanStepVersion=-1;
            StepDefinition completedStep=planStep;
            int completedAttempt=planAttempt.attemptNumber();
            notifyObserver(()->observer.planStepCompleted(state.taskId,state.runId,
                    state.structuredRevision,completedStep,completedAttempt));
            var afterAttempt=steeringSafePoint(state,
                    SteeringSafePointCoordinator.Candidate.STEP_ATTEMPT_TERMINAL);
            if(afterAttempt.pauseExecution())return handleMaterialSteering(state);
            return continueExecuting(state);
        }

        if(state.verificationCommand.isBlank())return completeWithoutCandidateVerification(state);
        if(state.providerOccurrenceId!=null){
            return continueVerifying(state,false);
        }
        if (!candidateVerificationGate.hasAppliedCandidate(state.taskId, state.runId))
            return completeWithoutCandidateVerification(state);
        transitionPhase(state,CodingRunStore.PHASE_EXECUTING,CodingRunStore.PHASE_VERIFYING);
        return continueVerifying(state, false);
    }

    private void acceptFinalClaim(ExecutionState state,AgentSegmentResult segment,
            String expectedPhase){
        WorkspaceCandidateDigest.Result candidate;
        try{candidate=verificationService.currentCandidateDigest();}
        catch(WorkspaceCandidateDigest.Failure failure){throw new IllegalStateException(
                "CANDIDATE_DIGEST_UNAVAILABLE_FINAL:"+failure.code().name(),failure);}
        if(!WorkspaceCandidateDigest.KIND.equals(candidate.kind()))
            throw new IllegalStateException("CANDIDATE_DIGEST_KIND_INVALID");
        CanonicalId occurrence=CanonicalId.parse(segment.providerOccurrenceId());
        state.executionGuard.acceptProviderFinal(occurrence,segment.endIteration(),expectedPhase,
                segment.answer(),candidate.digest());
        state.providerOccurrenceId=occurrence.value();
        state.finalCandidateDigest=candidate.digest();
        state.executionGuard.saveCheckpoint(state.iteration,"FINAL_ACCEPTED",state.conversation);
        notifyObserver(()->observer.phaseChanged(state.taskId,state.runId,
                CodingRunStore.PHASE_VERIFYING));
    }

    private static void requirePlanAttemptMutation(PlanExecutionStore.Mutation result){
        if(result.status()!=PlanExecutionStore.Status.SUCCESS)
            throw new FencedExecutionGuard.AuthorityLostException(
                    "PLAN_ATTEMPT_"+result.status(),"PLAN_ATTEMPT_OUTCOME",null);
    }

    static boolean onlyVerificationEvidencePending(
            com.paicli.state.PlanEvidenceSemanticEvaluator.StepProgress progress){
        var missing=progress.typedEvidence().stream().filter(value->
                value.satisfiedSuccesses()<value.requiredSuccesses()).toList();
        return !missing.isEmpty()&&missing.stream().allMatch(value->
                value.evidenceKind()==com.paicli.state.PlanCompletionModels.EvidenceKind
                        .VERIFICATION_RESULT);
    }

    private String stepEvidenceRecoveryPrompt(
            com.paicli.state.PlanEvidenceSemanticEvaluator.StepProgress progress){
        StringBuilder prompt=new StringBuilder("The current approved Plan Step has unmet exact Tool requirements.\n");
        for(var unmet:progress.unmet()){
            var requirement=unmet.requirement().requirement();
            prompt.append("- tool=").append(requirement.toolName())
                    .append(" effect=").append(requirement.effectClass())
                    .append(" selector=").append(requirement.authoritySelector().canonicalJson())
                    .append(" remainingSuccesses=").append(unmet.remainingSuccesses())
                    .append(" remainingOccurrences=").append(unmet.remainingOccurrences())
                    .append('\n');
        }
        prompt.append("Call only the exact listed Tool/selector scope. Do not repeat completed or future Step targets.");
        return sanitizer.sanitizeAndTruncate(prompt.toString(),MAX_AGENT_PROMPT_BYTES);
    }

    private CodingTaskResult awaitingCompletionEvidence(ExecutionState state) throws SQLException {
        PlanCompletionStore.GateResult gate = evaluateStructuredPlanPreview(state);
        boolean verificationRequired = stateStore.planCompletions.needsVerificationEvidence(
                com.paicli.identity.CanonicalId.parse(state.taskId),
                state.structuredRevision.planRevisionId());
        if (gate.result() == CompletionResult.COMPLETE && verificationRequired) {
            if (state.verificationCommand == null || state.verificationCommand.isBlank()) {
                return fail(state,null,"CANDIDATE_IDENTITY_UNAVAILABLE_RECOVERY");
            }
            transitionPhase(state, CodingRunStore.PHASE_EXECUTING,
                    CodingRunStore.PHASE_VERIFYING);
            return continueVerifying(state,true);
        }
        boolean evidenceMissing = gate.result() == CompletionResult.VERIFICATION_REQUIRED
                || gate.result() == CompletionResult.EVIDENCE_MISSING;
        if (evidenceMissing && state.verificationCommand != null
                && !state.verificationCommand.isBlank()
                && verificationRequired) {
            transitionPhase(state, CodingRunStore.PHASE_EXECUTING,
                    CodingRunStore.PHASE_VERIFYING);
            return continueVerifying(state, false);
        }
        if(gate.result()==CompletionResult.COMPLETE&&!state.finalResponseReady){
            try{prepareFinalResponse(state);}
            catch(RuntimeException error){
                return fail(state,null,"Final user response failed: "+safeMessage(error));
            }
            gate=evaluateStructuredPlan(state);
        }
        return completeStructuredPlan(state, null, gate);
    }

    private CodingTaskResult continueVerifying(ExecutionState state,
                                                boolean recoverExisting)
            throws SQLException {
        heartbeat(state,"VERIFICATION_BOUNDARY");
        VerificationResult verification;
        try {
            verification = recoverExisting
                    ? recoverLatestVerificationWithHooks(state) : null;
            if (verification == null) {
                verification = runVerification(state);
            }
        } catch (HookLifecycleException e) {
            if(e.pausedBySteering())return handleMaterialSteering(state);
            return fail(state, e.verificationStatus(), e.getMessage());
        }
        if(VerificationStore.STATUS_PAUSED_BY_STEERING.equals(verification.status())){
            steeringSafePoint(state,
                    SteeringSafePointCoordinator.Candidate.VERIFICATION_FACTS_COMMITTED);
            return handleMaterialSteering(state);
        }
        if (isCandidateBindingFailure(verification)) {
            return fail(state, verification.status(), candidateBindingFailure(verification));
        }
        if (isSuccessfulVerification(verification)) {
            var afterVerification=steeringSafePoint(state,
                    SteeringSafePointCoordinator.Candidate.VERIFICATION_FACTS_COMMITTED);
            if(afterVerification.pauseExecution())return handleMaterialSteering(state);
            return complete(state, verification);
        }
        if (state.structuredPlanBound) {
            return fail(state, verification.status(),
                    "Configured Verification did not pass: " + verification.status());
        }
        return repairLoop(state, verification);
    }

    private CodingTaskResult continueInterruptedRepair(ExecutionState state)
            throws SQLException {
        VerificationStore.VerificationRecord trigger = stateStore.verifications
                .findLatestByRun(state.runId).orElse(null);
        if (trigger == null) {
            return fail(state, null,
                    "Active repair has no triggering verification record");
        }
        VerificationResult verification = toVerificationResult(trigger);
        if (isCandidateBindingFailure(verification)) {
            return fail(state, verification.status(), candidateBindingFailure(verification));
        }
        if (isSuccessfulVerification(verification)) {
            return complete(state, verification);
        }

        RepairAttemptStore.RepairAttemptRecord runningRepair = stateStore.repairAttempts
                .findByRun(state.runId).stream()
                .filter(record -> RepairAttemptStore.STATUS_RUNNING.equals(record.status()))
                .reduce((first, second) -> second)
                .orElse(null);
        if (runningRepair == null) {
            return fail(state, verification.status(),
                    "Active repair has no RUNNING repair attempt");
        }

        RepairExecution execution = performRepair(
                state, verification, runningRepair.repairId());
        if (execution.terminalResult() != null) {
            return execution.terminalResult();
        }
        if (isSuccessfulVerification(execution.verification())) {
            return complete(state, execution.verification());
        }
        return repairLoop(state, execution.verification());
    }

    private CodingTaskResult repairLoop(ExecutionState state,
                                        VerificationResult verification)
            throws SQLException {
        if (isCandidateBindingFailure(verification)) {
            return fail(state, verification.status(), candidateBindingFailure(verification));
        }
        if (!config.isRepairEnabled()) {
            return fail(state, verification.status(),
                    "Verification failed and repair is disabled");
        }

        while (state.repairAttempts < state.maxRepairAttempts) {
            transitionPhase(state, CodingRunStore.PHASE_VERIFYING,
                    CodingRunStore.PHASE_REPAIRING);
            int attemptNo = state.repairAttempts + 1;
            String repairId = newId();
            if(state.executionGuard!=null)state.executionGuard.startRepair(repairId,attemptNo,
                    verification.verificationId(),verificationFailureSummary(verification));
            else stateStore.repairAttempts.recordStarted(repairId,state.runId,state.taskId,attemptNo,
                    verification.verificationId(),verificationFailureSummary(verification));
            VerificationResult repairTrigger = verification;
            notifyObserver(() -> observer.repairStarted(
                    state.taskId, state.runId, attemptNo, repairTrigger));

            RepairExecution execution = performRepair(
                    state, verification, repairId);
            if (execution.terminalResult() != null) {
                return execution.terminalResult();
            }
            verification = execution.verification();
            if (isCandidateBindingFailure(verification)) {
                return fail(state, verification.status(), candidateBindingFailure(verification));
            }
            if (isSuccessfulVerification(verification)) {
                return complete(state, verification);
            }
        }

        return fail(state, verification.status(),
                "Verification still failed after " + state.repairAttempts
                        + " repair attempt(s)");
    }

    private RepairExecution performRepair(ExecutionState state,
                                          VerificationResult verification,
                                          String repairId)
            throws SQLException {
        heartbeat(state,"REPAIR_BOUNDARY");
        String occurrenceKey = "repair:" + (state.repairAttempts + 1);
        HookRuntime.LifecycleResult before = runHooks(
                state, HookEvent.BEFORE_REPAIR, occurrenceKey);
        if(steeringPausedByHook(state,before))
            return new RepairExecution(null,handleMaterialSteering(state));
        if (before != null && before.shouldFail()) {
            state.repairAttempts=finishRepair(state,repairId,RepairAttemptStore.STATUS_FAILED);
            return new RepairExecution(null, fail(
                    state, verification.status(), before.failureMessage()));
        }
        AgentSegmentResult repairSegment;
        try {
            SkillRuntime.SkillContext skills = selectSkills(
                    state, SkillSelectionStore.STAGE_REPAIR,
                    "repair:" + (state.repairAttempts + 1),
                    verificationFailureSummary(verification));
            repairSegment = agentLoop(state).runSegment(new AgentSegmentRequest(
                    state.goal,
                    state.conversation,
                    repairPrompt(state.goal, state.planText, verification,
                            skills.context()),
                    state.iteration,
                    config.effectiveMaxIterations(),null,
                    com.paicli.context.ContextModels.OperationKind.REPAIR));
        } catch (RuntimeException e) {
            if(e instanceof FencedExecutionGuard.AuthorityLostException)throw e;
            if (e instanceof CancellationToken.TaskCancellationException) throw e;
            rethrowPersistenceFailure(e);
            state.repairAttempts=finishRepair(state,repairId,RepairAttemptStore.STATUS_FAILED);
            return new RepairExecution(null, fail(
                    state, verification.status(),
                    "Repair agent failed: " + safeMessage(e)));
        }

        heartbeat(state,"REPAIR_SEGMENT_RESULT");

        state.answer = repairSegment.answer();
        state.iteration = repairSegment.endIteration();
        state.conversation = repairSegment.conversation();
        if(repairSegment.providerOccurrenceId()==null)updateLastIteration(state);
        if (!repairSegment.completed()) {
            state.repairAttempts=finishRepair(state,repairId,RepairAttemptStore.STATUS_FAILED);
            return new RepairExecution(null, fail(
                    state, verification.status(),
                    "Repair agent reached the segment iteration limit"));
        }
        if(state.executionGuard!=null&&repairSegment.providerOccurrenceId()==null){
            state.repairAttempts=finishRepair(state,repairId,RepairAttemptStore.STATUS_FAILED);
            return new RepairExecution(null,fail(state,verification.status(),
                    "Repair completed without a durable FINAL claim"));
        }
        if(state.executionGuard!=null)
            acceptFinalClaim(state,repairSegment,CodingRunStore.PHASE_REPAIRING);

        HookRuntime.LifecycleResult after = runHooks(
                state, HookEvent.AFTER_REPAIR, occurrenceKey);
        if(steeringPausedByHook(state,after))
            return new RepairExecution(null,handleMaterialSteering(state));
        if (after != null && after.shouldFail()) {
            state.repairAttempts=finishRepair(state,repairId,RepairAttemptStore.STATUS_FAILED);
            return new RepairExecution(null, fail(
                    state, verification.status(), after.failureMessage()));
        }

        state.repairAttempts=finishRepair(state,repairId,RepairAttemptStore.STATUS_COMPLETED);
        if(state.executionGuard==null)transitionPhase(state,CodingRunStore.PHASE_REPAIRING,
                CodingRunStore.PHASE_VERIFYING);
        try {
            return new RepairExecution(runVerification(state), null);
        } catch (HookLifecycleException e) {
            if(e.pausedBySteering())
                return new RepairExecution(null,handleMaterialSteering(state));
            return new RepairExecution(null, fail(
                    state, e.verificationStatus(), e.getMessage()));
        }
    }

    private VerificationResult recoverLatestVerification(ExecutionState state)
            throws SQLException {
        VerificationStore.VerificationRecord record = stateStore.verifications
                .findLatestByRun(state.runId).orElse(null);
        if (record == null) {
            return null;
        }
        if (VerificationStore.STATUS_RUNNING.equals(record.status())) {
            String message = sanitizer.sanitizeAndTruncate(
                    "Verification interrupted before resume",
                    config.effectiveMaxVerificationOutputBytes());
            if(state.executionGuard!=null)state.executionGuard.finishVerification(
                    record.verificationId(),VerificationStore.STATUS_ERROR,null,null,message,0);
            else stateStore.verifications.markError(record.verificationId(),null,message,0);
            record = stateStore.verifications.findById(record.verificationId())
                    .orElseThrow();
        }
        if (VerificationStore.STATUS_PASSED.equals(record.status())
                && !freshness(record.candidateDigestKind(), record.candidateDigest(),
                        "RECOVERY").fresh()) {
            return null;
        }
        return toVerificationResult(record);
    }

    private VerificationResult recoverLatestVerificationWithHooks(ExecutionState state)
            throws SQLException {
        VerificationResult verification = recoverLatestVerification(state);
        if (verification != null) {
            HookRuntime.LifecycleResult after = runHooks(
                    state, HookEvent.AFTER_VERIFY,
                    "verify:" + verification.repairAttempt());
            if(steeringPausedByHook(state,after))
                throw HookLifecycleException.steeringPaused(verification.status());
            if (after != null && after.shouldFail()) {
                throw new HookLifecycleException(
                        after.failureMessage(), verification.status());
            }
        }
        return verification;
    }

    private VerificationResult runVerification(ExecutionState state)
            throws SQLException {
        String occurrenceKey = "verify:" + state.repairAttempts;
        HookRuntime.LifecycleResult before = runHooks(
                state, HookEvent.BEFORE_VERIFY, occurrenceKey);
        if(steeringPausedByHook(state,before))
            throw HookLifecycleException.steeringPaused(null);
        if (before != null && before.shouldFail()) {
            throw new HookLifecycleException(before.failureMessage(), null);
        }
        VerificationResult verification = verificationService.verify(
                newId(), state.runId, state.taskId,
                state.repairAttempts, state.verificationCommand,state.executionGuard);
        if(VerificationStore.STATUS_PAUSED_BY_STEERING.equals(verification.status()))
            return verification;
        HookRuntime.LifecycleResult after = runHooks(
                state, HookEvent.AFTER_VERIFY, occurrenceKey);
        if(steeringPausedByHook(state,after))
            throw HookLifecycleException.steeringPaused(verification.status());
        if (after != null && after.shouldFail()) {
            throw new HookLifecycleException(
                    after.failureMessage(), verification.status());
        }
        return verification;
    }

    private VerificationResult toVerificationResult(
            VerificationStore.VerificationRecord record) {
        String status=record.status();
        try {
            if(stateStore.verifications.wasPausedBySteering(record.verificationId()))
                status=VerificationStore.STATUS_PAUSED_BY_STEERING;
        } catch(SQLException error) {
            throw new IllegalStateException("Verification pause integrity read failed",error);
        }
        return new VerificationResult(
                record.verificationId(), record.runId(), record.taskId(),
                record.repairAttempt(), record.command(), status,
                record.exitCode(),
                record.durationMs() == null ? 0 : record.durationMs(),
                record.outputExcerpt(), record.errorExcerpt(),
                record.candidateDigestKind(), record.candidateDigest());
    }

    private CodingTaskResult completeWithoutCandidateVerification(ExecutionState state)
            throws SQLException {
        if (state.structuredPlanBound) return completeStructuredPlan(state, null);
        heartbeat(state,"COMPLETION_BOUNDARY");
        HookRuntime.LifecycleResult finish = runHooks(
                state, HookEvent.BEFORE_FINISH, "finish");
        if(steeringPausedByHook(state,finish))return handleMaterialSteering(state);
        if (finish != null && finish.shouldFail()) {
            return failWithoutFinishHook(state, null, finish.failureMessage());
        }
        if (state.planId != null) {
            TaskPlanStore.TaskPlanRecord plan = stateStore.plans
                    .findById(state.planId).orElse(null);
            if (plan != null && TaskPlanStore.STATUS_ACTIVE.equals(plan.status())) {
                if(state.executionGuard==null)stateStore.plans.markCompleted(state.planId);
            }
        }
        if(state.providerOccurrenceId!=null){
            FreshnessCheck claim=freshness(WorkspaceCandidateDigest.KIND,
                    state.finalCandidateDigest,"BEFORE_RESULT");
            if(!claim.fresh())return failWithoutFinishHook(state,null,claim.reason());
        }
        try{prepareExecutionFinalResponse(state,null);}
        catch(RuntimeException error){return failWithoutFinishHook(state,null,
                "Final user response failed: "+safeMessage(error));}
        if(state.providerOccurrenceId!=null){
            FreshnessCheck claim=freshness(WorkspaceCandidateDigest.KIND,
                    state.finalCandidateDigest,"BEFORE_TERMINALIZATION");
            if(!claim.fresh())return failWithoutFinishHook(state,null,claim.reason());
        }
        terminalize(state,true,null);
        String summary=state.answer==null||state.answer.isBlank()
                ?"Coding task completed":state.answer;
        CodingTaskResult result = new CodingTaskResult(
                state.taskId, state.runId, state.planId, TaskStore.STATUS_DONE,
                VerificationStore.STATUS_SKIPPED, state.repairAttempts,
                sanitizer.sanitizeAndTruncate(summary,MAX_PERSISTED_SUMMARY_BYTES),
                state.executionGuard);
        notifyObserver(() -> observer.taskCompleted(result));
        return result;
    }
    private CodingTaskResult complete(ExecutionState state,
                                      VerificationResult verification)
            throws SQLException {
        if (state.structuredPlanBound) {
            return completeStructuredPlanWithFreshness(state, verification);
        }
        heartbeat(state,"COMPLETION_BOUNDARY");
        HookRuntime.LifecycleResult finish = runHooks(
                state, HookEvent.BEFORE_FINISH, "finish");
        if(steeringPausedByHook(state,finish))return handleMaterialSteering(state);
        if (finish != null && finish.shouldFail()) {
            return failWithoutFinishHook(
                    state, verification.status(), finish.failureMessage());
        }
        FreshnessCheck beforeResult = freshness(verification,"BEFORE_RESULT");
        if (!beforeResult.fresh()) {
            return failWithoutFinishHook(state, verification.status(), beforeResult.reason());
        }
        if(state.providerOccurrenceId!=null
                &&(!WorkspaceCandidateDigest.KIND.equals(verification.candidateDigestKind())
                    ||!Objects.equals(state.finalCandidateDigest,
                            verification.candidateDigest()))){
            return failWithoutFinishHook(state,verification.status(),
                    "FINAL_CLAIM_CANDIDATE_MISMATCH_BEFORE_RESULT");
        }
        if (state.planId != null) {
            TaskPlanStore.TaskPlanRecord plan = stateStore.plans
                    .findById(state.planId).orElse(null);
            if (plan != null && TaskPlanStore.STATUS_ACTIVE.equals(plan.status())) {
                if(state.executionGuard==null)stateStore.plans.markCompleted(state.planId);
            }
        }
        try{prepareExecutionFinalResponse(state,verification);}
        catch(RuntimeException error){return failWithoutFinishHook(state,verification.status(),
                 "Final user response failed: "+safeMessage(error));}
        FreshnessCheck beforeTerminalization = freshness(
                verification,"BEFORE_TERMINALIZATION");
        if (!beforeTerminalization.fresh()) {
            return failWithoutFinishHook(state, verification.status(),
                    beforeTerminalization.reason());
        }
        terminalize(state,true,null);
        String summary = state.answer == null || state.answer.isBlank()
                ? "Coding task completed" : state.answer;
        CodingTaskResult result = new CodingTaskResult(
                state.taskId, state.runId, state.planId, TaskStore.STATUS_DONE,
                verification.status(), state.repairAttempts,
                sanitizer.sanitizeAndTruncate(summary, MAX_PERSISTED_SUMMARY_BYTES),
                state.executionGuard);
        notifyObserver(() -> observer.taskCompleted(result));
        return result;
    }

    private CodingTaskResult completeStructuredPlanWithFreshness(ExecutionState state,
            VerificationResult verification) throws SQLException {
        String status = verification == null ? null : verification.status();
        PlanCompletionStore.GateResult preview = evaluateStructuredPlanPreview(state);
        if (preview.result() != CompletionResult.COMPLETE) {
            return completeStructuredPlan(state,status,preview);
        }
        FreshnessCheck beforeResult = freshness(verification,"BEFORE_RESULT");
        if (!beforeResult.fresh()) {
            return fail(state,status,beforeResult.reason());
        }
        if (!state.finalResponseReady) {
            try { prepareFinalResponse(state); }
            catch (RuntimeException error) {
                return fail(state,status,"Final user response failed: "+safeMessage(error));
            }
        }
        FreshnessCheck beforeTerminalization = freshness(
                verification,"BEFORE_TERMINALIZATION");
        if (!beforeTerminalization.fresh()) {
            return fail(state,status,beforeTerminalization.reason());
        }
        return completeStructuredPlan(state,status,evaluateStructuredPlan(state));
    }

    private CodingTaskResult completeStructuredPlan(ExecutionState state,
            String verificationStatus) throws SQLException {
        PlanCompletionStore.GateResult preview=evaluateStructuredPlanPreview(state);
        if(preview.result()==CompletionResult.COMPLETE&&!state.finalResponseReady){
            try{prepareFinalResponse(state);}
            catch(RuntimeException error){return fail(state,verificationStatus,
                    "Final user response failed: "+safeMessage(error));}
        }
        return completeStructuredPlan(state, verificationStatus,
                state.finalResponseReady?evaluateStructuredPlan(state):preview);
    }

    private PlanCompletionStore.GateResult evaluateStructuredPlanPreview(ExecutionState state){
        if(state.executionGuard==null||state.structuredRevision==null)
            throw new IllegalStateException("Structured completion requires durable execution authority");
        return state.executionGuard.evaluatePlanCompletion(
                state.structuredRevision.planRevisionId());
    }

    private PlanCompletionStore.GateResult evaluateStructuredPlan(ExecutionState state) {
        if (state.executionGuard == null || state.structuredRevision == null) {
            throw new IllegalStateException("Structured completion requires durable execution authority");
        }
        var steering=steeringSafePoint(state,
                SteeringSafePointCoordinator.Candidate.COMPLETION_GATE_BEFORE);
        if(steering.pauseExecution() || state.steeringCoordinator.blocksCompletion()) {
            return new PlanCompletionStore.GateResult(PlanCompletionStore.Status.SUCCESS,
                    CompletionResult.INCOMPLETE,0,"STEERING_PENDING_OR_PLAN_DRAFT");
        }
        if(!state.finalResponseReady||state.finalResponseHash==null)
            throw new IllegalStateException("USER_RESULT_COMPLETION_GATE_NOT_SATISFIED");
        try{
            if(!stateStore.userResults.completionReady(CanonicalId.parse(state.taskId),
                    CanonicalId.parse(state.runId),state.structuredRevision.planRevisionId(),
                    state.finalResponseHash))
                throw new IllegalStateException("USER_RESULT_COMPLETION_GATE_NOT_SATISFIED");
        }catch(SQLException error){throw new IllegalStateException(
                "USER_RESULT_COMPLETION_GATE_READ_FAILED",error);}
        PlanCompletionStore.GateResult gate = state.executionGuard.evaluateAndCompletePlan(
                state.structuredRevision.planRevisionId());
        if (gate.status() != PlanCompletionStore.Status.SUCCESS) {
            throw new FencedExecutionGuard.AuthorityLostException(
                    "PLAN_COMPLETION_" + gate.status(), "TASK_COMPLETION_GATE", null);
        }
        return gate;
    }

    private CodingTaskResult completeStructuredPlan(ExecutionState state,
            String verificationStatus, PlanCompletionStore.GateResult gate) {
        if (gate.result() != CompletionResult.COMPLETE) {
            return new CodingTaskResult(state.taskId, state.runId, state.planId,
                    TaskStore.STATUS_RUNNING, verificationStatus, state.repairAttempts,
                    "Plan Steps executed; completion evidence is not yet sufficient: "
                            + gate.result(),
                    state.executionGuard);
        }
        if(!state.finalResponseReady||state.finalResponseHash==null){
            throw new IllegalStateException("USER_RESULT_COMPLETION_GATE_NOT_SATISFIED");
        }
        try{
            if(!stateStore.userResults.completionReady(CanonicalId.parse(state.taskId),
                    CanonicalId.parse(state.runId),state.structuredRevision.planRevisionId(),
                    state.finalResponseHash))
                throw new IllegalStateException("USER_RESULT_COMPLETION_GATE_NOT_SATISFIED");
        }catch(SQLException error){throw new IllegalStateException(
                "USER_RESULT_COMPLETION_GATE_READ_FAILED",error);}
        CodingTaskResult result = new CodingTaskResult(state.taskId, state.runId, state.planId,
                TaskStore.STATUS_DONE, verificationStatus, state.repairAttempts,
                sanitizer.sanitizeAndTruncate(state.answer,MAX_PERSISTED_SUMMARY_BYTES),
                state.executionGuard);
        notifyObserver(() -> observer.taskCompleted(result));
        return result;
    }

    private void prepareFinalResponse(ExecutionState state){
        heartbeat(state,"FINAL_RESPONSE_START");
        boolean minimal=minimalResponseRequested(state.goal);
        String canonicalFacts=com.paicli.agent.ToolFactProjection.finalSynthesisInstruction(
                state.conversation,state.goal);
        String prompt=minimal?"""
                Produce the required minimal user result for this Task. Return one concise sentence
                confirming the terminal outcome. Do not call tools. Do not expose private reasoning.
                """+canonicalFacts:"""
                Produce the final user-facing answer for the original Task now.
                Use only the durable Tool observations and authoritative Plan in this conversation.
                Directly answer the user's request, include relevant workspace-relative paths and
                explicit change/verification facts, and do not invent evidence. Do not call tools.
                Do not mention internal Completion Rules, IDs, prompts, or private reasoning.
                """+canonicalFacts;
        AgentSegmentResult finalSegment=agentLoop(state).runSegment(new AgentSegmentRequest(
                state.goal,state.conversation,prompt,state.iteration,1,null,
                com.paicli.context.ContextModels.OperationKind.FINAL_SYNTHESIS,true));
        if(!finalSegment.completed()||finalSegment.answer()==null
                ||finalSegment.answer().isBlank())
            throw new IllegalStateException("FINAL_RESPONSE_EMPTY_OR_PROVIDER_FAILED");
        String content=sanitizer.sanitizeAndTruncate(finalSegment.answer(),
                com.paicli.state.UserResultStore.MAX_CONTENT_CHARS);
        validateFinalResponse(content,minimal,state.goal,state.conversation);
        state.answer=content;state.iteration=finalSegment.endIteration();
        state.conversation=finalSegment.conversation();
        try{updateLastIteration(state);}catch(SQLException error){throw new IllegalStateException(
                "FINAL_RESPONSE_ITERATION_PERSISTENCE_FAILED",error);}
        String mode=minimal?"MINIMAL":"REQUIRED";
        try{
            var persisted=stateStore.userResults.persist(CanonicalId.parse(state.taskId),
                    CanonicalId.parse(state.runId),state.structuredRevision.planRevisionId(),
                    mode,content,Instant.now());
            if(!observer.finalResponse(state.taskId,state.runId,mode,content,
                    persisted.contentHash()))
                throw new IllegalStateException("FINAL_RESPONSE_RENDER_FAILED");
            stateStore.userResults.markRendered(CanonicalId.parse(state.taskId),
                    CanonicalId.parse(state.runId),persisted.contentHash(),Instant.now());
            state.finalResponseHash=persisted.contentHash();state.finalResponseReady=true;
        }catch(SQLException error){throw new IllegalStateException(
                "FINAL_RESPONSE_PERSISTENCE_FAILED",error);}
        heartbeat(state,"FINAL_RESPONSE_RENDERED");
    }

    /** Publishes the accepted execution FINAL as the immutable user result. */
    private void prepareExecutionFinalResponse(ExecutionState state,
            VerificationResult verification){
        if(state.structuredPlanBound)return;
        if(state.executionGuard==null&&state.structuredRevision==null)return;
        if(state.answer==null||state.answer.isBlank())
            throw new IllegalStateException("FINAL_RESPONSE_EMPTY_OR_PROVIDER_FAILED");
        String content=sanitizer.sanitizeAndTruncate(state.answer,
                com.paicli.state.UserResultStore.MAX_CONTENT_CHARS);
        boolean minimal=minimalResponseRequested(state.goal);
        validateFinalResponse(content,minimal,state.goal,state.conversation);
        String mode=minimal?"MINIMAL":"REQUIRED";
        try{
            if(state.executionGuard==null){
                throw new IllegalStateException("USER_RESULT_EXECUTION_AUTHORITY_REQUIRED");
            }
            var persisted=state.structuredRevision!=null
                    ?state.executionGuard.persistUserResult(
                            state.structuredRevision.planRevisionId(),mode,content)
                    :state.executionGuard.persistPlanlessUserResult(
                            CanonicalId.parse(state.providerOccurrenceId),
                            state.finalCandidateDigest,
                            verification==null?null:verification.verificationId(),mode,content);
            if(!"RENDERED".equals(persisted.renderStatus())){
                if(!observer.finalResponse(state.taskId,state.runId,mode,content,
                        persisted.contentHash()))
                    throw new IllegalStateException("FINAL_RESPONSE_RENDER_FAILED");
                state.executionGuard.markUserResultRendered(persisted.contentHash());
            }
            boolean ready=state.structuredRevision!=null
                    ?stateStore.userResults.completionReady(CanonicalId.parse(state.taskId),
                            CanonicalId.parse(state.runId),state.structuredRevision.planRevisionId(),
                            persisted.contentHash())
                    :stateStore.userResults.completionReadyPlanless(
                            CanonicalId.parse(state.taskId),CanonicalId.parse(state.runId),
                            persisted.contentHash(),state.providerOccurrenceId,
                            state.finalCandidateDigest,verification==null?"UNVERIFIED"
                                    :"PASSED:"+verification.verificationId());
            if(!ready)
                throw new IllegalStateException("USER_RESULT_COMPLETION_GATE_NOT_SATISFIED");
            state.answer=content;state.finalResponseHash=persisted.contentHash();
            state.finalResponseReady=true;
        }catch(SQLException error){throw new IllegalStateException(
                "FINAL_RESPONSE_PERSISTENCE_FAILED",error);}
        heartbeat(state,"FINAL_RESPONSE_RENDERED");
    }

    private static boolean minimalResponseRequested(String goal){
        String value=goal==null?"":goal.toLowerCase(java.util.Locale.ROOT);
        return value.contains("静默执行")||value.contains("只执行，不解释")
                ||value.contains("不要给详细结果")||value.contains("不展示结果正文")
                ||value.contains("silent execution")||value.contains("no detailed result");
    }

    private static void validateFinalResponse(String content,boolean minimal,String goal,
            java.util.List<com.paicli.model.AgentMessage> conversation){
        if(content==null||content.isBlank())throw new IllegalStateException("FINAL_RESPONSE_EMPTY");
        FinalResponseContent.requireNoHostDrivePath(content);
        com.paicli.agent.ToolFactProjection.validateFinalResponse(
                content,goal,conversation);
    }

    private CodingTaskResult fail(ExecutionState state, String verificationStatus,
                                  String message) throws SQLException {
        HookRuntime.LifecycleResult finish = runHooks(
                state, HookEvent.BEFORE_FINISH, "finish");
        if (finish != null && finish.shouldFail()) {
            message = finish.failureMessage();
        }
        return failWithoutFinishHook(state, verificationStatus, message);
    }

    private CodingTaskResult failWithoutFinishHook(
            ExecutionState state, String verificationStatus,
            String message) throws SQLException {
        heartbeat(state,"FAILURE_BOUNDARY");
        String safeMessage = sanitizer.sanitizeAndTruncate(
                message, MAX_PERSISTED_SUMMARY_BYTES);
        if (state.planId != null) {
            TaskPlanStore.TaskPlanRecord plan = stateStore.plans
                    .findById(state.planId).orElse(null);
            if (plan != null && (TaskPlanStore.STATUS_GENERATED.equals(plan.status())
                    || TaskPlanStore.STATUS_ACTIVE.equals(plan.status()))) {
                if(state.executionGuard==null)stateStore.plans.markFailed(state.planId);
            }
        }
        terminalize(state,false,safeMessage);
        CodingTaskResult result = new CodingTaskResult(
                state.taskId, state.runId, state.planId, TaskStore.STATUS_FAILED,
                verificationStatus, state.repairAttempts, safeMessage,state.executionGuard);
        notifyObserver(() -> observer.taskFailed(result));
        return result;
    }

    private void loadExistingPlan(ExecutionState state) throws SQLException {
        if(state.executionGuard!=null&&state.planEnabled){
            CanonicalId taskId=CanonicalId.parse(state.taskId);
            if(strictPlanAuthority()){
                PlanAuthorityStore.Read active=stateStore.planAuthorities.readActive(taskId);
                if(active.status()==PlanAuthorityStore.Status.SUCCESS){
                    state.planId=active.plan().revision().planRevisionId().value();
                    state.planText=PlanService.render(active.plan().revision());
                    state.structuredRevision=active.plan().revision();
                    state.structuredPlanBound=true;
                    return;
                }
            }else{
                PlanAuthorityStore.Read active=stateStore.planAuthorities.readActive(taskId);
                if(active.status()==PlanAuthorityStore.Status.SUCCESS){
                    throw new IllegalStateException("STRICT_PLAN_AUTHORITY_INTERNAL_ONLY");
                }
                PlanAuthorityStore.RevisionRead advisory=
                        stateStore.planAuthorities.readLatestApprovedRevision(taskId);
                if(advisory.status()==PlanAuthorityStore.Status.SUCCESS){
                    state.planId=advisory.revision().planRevisionId().value();
                    state.planText=PlanService.render(advisory.revision());
                    state.structuredRevision=advisory.revision();
                    state.structuredPlanBound=false;
                    return;
                }
                if(advisory.status()!=PlanAuthorityStore.Status.NOT_FOUND){
                    throw new IllegalStateException("ADVISORY_PLAN_"+advisory.status());
                }
            }
        }
        TaskPlanStore.TaskPlanRecord plan = stateStore.plans
                .findByRun(state.runId).orElse(null);
        if (plan != null) {
            state.planId = plan.planId();
            state.planText = plan.planText();
        }
    }

    private void activateGeneratedPlan(ExecutionState state) throws SQLException {
        if(state.planId.startsWith("pln_")){
            CanonicalId taskId=CanonicalId.parse(state.taskId);
            Revision revision;
            if(strictPlanAuthority()){
                PlanAuthorityStore.Read active=stateStore.planAuthorities.readActive(taskId);
                if(active.status()!=PlanAuthorityStore.Status.SUCCESS
                        ||!active.plan().revision().planRevisionId().value().equals(state.planId))
                    throw new IllegalStateException("PLAN_AUTHORITY_CONFLICT");
                revision=active.plan().revision();
                state.structuredPlanBound=true;
            }else{
                PlanAuthorityStore.RevisionRead advisory=
                        stateStore.planAuthorities.readLatestApprovedRevision(taskId);
                if(advisory.status()!=PlanAuthorityStore.Status.SUCCESS
                        ||!advisory.revision().planRevisionId().value().equals(state.planId))
                    throw new IllegalStateException("ADVISORY_PLAN_INTEGRITY_FAILURE");
                revision=advisory.revision();
                state.structuredPlanBound=false;
            }
            state.structuredRevision=revision;
            state.planText=PlanService.render(revision);
            return;
        }
        TaskPlanStore.TaskPlanRecord plan = stateStore.plans
                .findById(state.planId).orElseThrow();
        if (TaskPlanStore.STATUS_GENERATED.equals(plan.status())) {
            transitionPlan(state,state.planId,TaskPlanStore.STATUS_GENERATED,
                    TaskPlanStore.STATUS_ACTIVE);
        } else if (!TaskPlanStore.STATUS_ACTIVE.equals(plan.status())) {
            throw new IllegalStateException(
                    "Active coding run has terminal plan status: " + plan.status());
        }
    }

    private void transitionPhase(
            ExecutionState state, String expectedPhase, String nextPhase)
            throws SQLException {
        if(state.executionGuard!=null)state.executionGuard.transitionRunPhase(expectedPhase,nextPhase);
        else stateStore.codingRuns.transitionPhase(state.runId,expectedPhase,nextPhase);
        notifyObserver(() -> observer.phaseChanged(
                state.taskId, state.runId, nextPhase));
    }

    private void notifyObserver(Runnable notification) {
        try {
            notification.run();
        } catch (FencedExecutionGuard.AuthorityLostException lost) {
            throw lost;
        } catch (RuntimeException ignored) {
            // Observability must not alter persisted runtime state.
        }
    }
    private AgentLoop agentLoop(ExecutionState state) {
        if (state.agentLoop == null) {
            state.agentLoop = Objects.requireNonNull(
                    agentLoopFactory.create(state.taskId),
                    "agentLoopFactory returned null");
            if(skillRuntime!=null)state.agentLoop.bindSkills(skillRuntime,state.runId);
            if(state.executionGuard!=null){
                state.agentLoop.bindExecutionGuard(state.executionGuard);
                state.steeringCoordinator=strictPlanAuthority()
                        ?new SteeringSafePointCoordinator(stateStore,
                                state.executionGuard,state.agentLoop::hasPendingApproval,
                                request->planService.generateSteeredStructured(
                                        request.sourceRevision(),request.definition().steeringId(),
                                        request.definition().content(),state.instructionContext,
                                        state.executionGuard,request.revisionNumber(),Instant.now(),
                                        new com.paicli.state.PlanCompletionProfilePolicy.Capabilities(
                                                !state.verificationCommand.isBlank())),
                                endIteration->state.currentPlanAttempt==null?null:
                                        new com.paicli.state.SteeringStore.AttemptPause(
                                                state.currentPlanAttempt,state.currentPlanStepVersion,
                                                endIteration))
                        :new SteeringSafePointCoordinator(stateStore,state.executionGuard,
                                state.agentLoop::hasPendingApproval);
                state.agentLoop.bindSteeringCoordinator(state.steeringCoordinator);
            }
            if(autonomousContinuityEnabled()){
                state.agentLoop.bindTaskProviderBudgetGate(taskProviderBudgetGate);
                state.agentLoop.bindTaskToolBudgetGate(()->{
                    BudgetEvidence evidence=continuityBudgetEvidence.snapshot(
                            state.taskId,state.runId);
                    if(!evidence.complete())return AgentLoop.TaskToolBudgetAdmission.rejected(
                            "TASK_BUDGET_EVIDENCE_INCOMPLETE");
                    return evidence.toolCalls()>=taskContinuityPolicy.maxTotalToolCalls()
                            ?AgentLoop.TaskToolBudgetAdmission.rejected(
                                    "MAX_TOOL_CALLS_EXHAUSTED")
                            :AgentLoop.TaskToolBudgetAdmission.allowed();
                });
            }
        }
        return state.agentLoop;
    }

    private TaskProviderBudgetGate.Admission providerBudgetAdmission(String taskId,String runId){
        BudgetEvidence evidence=continuityBudgetEvidence.snapshot(taskId,runId);
        if(!evidence.complete())return TaskProviderBudgetGate.Admission.rejected(
                "TASK_BUDGET_EVIDENCE_INCOMPLETE");
        if(evidence.providerTurns()>=taskContinuityPolicy.maxTotalProviderTurns())
            return TaskProviderBudgetGate.Admission.rejected("MAX_PROVIDER_TURNS_EXHAUSTED");
        if(evidence.inputTokens()>=taskContinuityPolicy.maxTotalInputTokens())
            return TaskProviderBudgetGate.Admission.rejected("MAX_INPUT_TOKENS_EXHAUSTED");
        if(evidence.outputTokens()>=taskContinuityPolicy.maxTotalOutputTokens())
            return TaskProviderBudgetGate.Admission.rejected("MAX_OUTPUT_TOKENS_EXHAUSTED");
        if(evidence.activeElapsedMillis()>=taskContinuityPolicy.maxActiveElapsedMillis())
            return TaskProviderBudgetGate.Admission.rejected("MAX_ACTIVE_ELAPSED_EXHAUSTED");
        return TaskProviderBudgetGate.Admission.allowed();
    }

    private static TaskProviderBudgetGate.AdmissionException providerBudgetFailure(
            Throwable error){
        for(Throwable current=error;current!=null;current=current.getCause())
            if(current instanceof TaskProviderBudgetGate.AdmissionException admission)
                return admission;
        return null;
    }

    private SteeringSafePointCoordinator.Result steeringSafePoint(ExecutionState state,
            SteeringSafePointCoordinator.Candidate candidate){
        if(state.executionGuard==null)return SteeringSafePointCoordinator.Result.none(
                com.paicli.state.SteeringStore.Status.NOT_FOUND);
        agentLoop(state);
        return state.steeringCoordinator.check(candidate,state.iteration);
    }

    private CodingTaskResult steeringPaused(ExecutionState state){
        return new CodingTaskResult(state.taskId,state.runId,state.planId,TaskStore.STATUS_RUNNING,
                null,state.repairAttempts,"Execution paused for durable material Steering Plan approval.",
                state.executionGuard);
    }

    private CodingTaskResult handleMaterialSteering(ExecutionState state){
        if(!strictPlanAuthority()){
            return steeringPaused(state);
        }
        CanonicalId task=CanonicalId.parse(state.taskId),run=CanonicalId.parse(state.runId);
        CanonicalId draftId=stateStore.steerings.latestAppliedPlanDraft(task,run).orElse(null);
        if(draftId==null)return steeringPaused(state);
        var active=stateStore.planAuthorities.readActive(task);
        if(active.status()==PlanAuthorityStore.Status.SUCCESS
                &&active.plan().revision().planRevisionId().equals(draftId)){
            bindSteeringPlan(state,active.plan().revision());
            try{return continueExecuting(state);}catch(SQLException error){throw new IllegalStateException(error);}
        }
        var read=stateStore.planAuthorities.readRevisionDefinition(task,draftId);
        if(read.status()!=PlanAuthorityStore.Status.SUCCESS)
            throw new IllegalStateException("STEERING_PLAN_DRAFT_"+read.status());
        Revision draft=read.revision();
        var lifecycle=stateStore.steerings.planDraftState(task,draftId).orElseThrow();
        if("DRAFT".equals(lifecycle.state()))requirePlanSuccess(stateStore.planAuthorities.validateRevision(
                state.executionGuard.current().snapshot(),Instant.now(),draftId,lifecycle.version()),
                "STEERING_PLAN_VALIDATION_REJECTED");
        else if(!"VALIDATED".equals(lifecycle.state()))
            throw new IllegalStateException("STEERING_PLAN_LIFECYCLE_"+lifecycle.state());
        PlanApprovalPort.Decision decision;
        try {
            decision=planApprovalPort.requestApproval(draft,PlanService.render(draft));
        } catch (com.paicli.interactive.approval.InteractiveApprovalCoordinator
                .PlanApprovalPresentationException error) {
            try {
                return fail(state,null,"PLAN_APPROVAL_PRESENTATION_FAILED");
            } catch (SQLException persistenceFailure) {
                throw new IllegalStateException(persistenceFailure);
            }
        }
        if(!decision.approved()){
            requirePlanSuccess(stateStore.planAuthorities.reject(state.executionGuard.current().snapshot(),
                    Instant.now(),draftId,1,"approval-"+UUID.randomUUID(),decision.approvedBy(),
                    decision.policyVersion(),decision.reason()),"STEERING_PLAN_REJECTION_PERSISTENCE_FAILED");
            return steeringPaused(state);
        }
        if(active.status()!=PlanAuthorityStore.Status.SUCCESS)
            throw new IllegalStateException("STEERING_SOURCE_PLAN_AUTHORITY_"+active.status());
        requirePlanSuccess(stateStore.planAuthorities.approveAndActivate(
                state.executionGuard.current().snapshot(),Instant.now(),draftId,1,
                active.plan().authority().pointerVersion(),"approval-"+UUID.randomUUID(),
                decision.approvedBy(),decision.policyVersion(),decision.reason()),
                "STEERING_PLAN_AUTHORITY_CONFLICT");
        bindSteeringPlan(state,draft);
        try{return continueExecuting(state);}catch(SQLException error){throw new IllegalStateException(error);}
    }

    private void bindSteeringPlan(ExecutionState state,Revision revision){
        state.planId=revision.planRevisionId().value();state.planText=PlanService.render(revision);
        state.structuredPlanBound=true;state.structuredRevision=revision;
        state.currentPlanAttempt=null;state.currentPlanStep=null;state.conversation=List.of();
        state.currentPlanStepVersion=-1;
    }

    private boolean isSuccessfulVerification(VerificationResult verification) {
        return VerificationStore.STATUS_PASSED.equals(verification.status());
    }

    private FreshnessCheck freshness(VerificationResult verification,String gate) {
        if (verification == null
                || !VerificationStore.STATUS_PASSED.equals(verification.status())) {
            return FreshnessCheck.FRESH;
        }
        return freshness(verification.candidateDigestKind(),verification.candidateDigest(),gate);
    }

    private FreshnessCheck freshness(String kind,String digest,String gate) {
        if (!WorkspaceCandidateDigest.KIND.equals(kind) || digest == null
                || !digest.matches("[0-9a-f]{64}")) {
            return new FreshnessCheck(false,"CANDIDATE_IDENTITY_UNAVAILABLE_"+gate);
        }
        try {
            WorkspaceCandidateDigest.Result current=verificationService.currentCandidateDigest();
            if (!kind.equals(current.kind()) || !digest.equals(current.digest())) {
                return new FreshnessCheck(false,"STALE_CANDIDATE_"+gate);
            }
            return FreshnessCheck.FRESH;
        } catch (WorkspaceCandidateDigest.Failure failure) {
            return new FreshnessCheck(false,"CANDIDATE_DIGEST_UNAVAILABLE_"+gate+":"
                    +failure.code().name());
        }
    }

    private boolean isCandidateBindingFailure(VerificationResult verification) {
        String error=verification==null?null:verification.errorExcerpt();
        return error!=null&&(error.contains("CANDIDATE_CHANGED_DURING_VERIFICATION")
                ||error.contains("CANDIDATE_DIGEST_UNAVAILABLE:"));
    }

    private String candidateBindingFailure(VerificationResult verification) {
        String error=verification.errorExcerpt();
        if(error!=null&&error.contains("CANDIDATE_CHANGED_DURING_VERIFICATION"))
            return "CANDIDATE_CHANGED_DURING_VERIFICATION";
        int marker=error==null?-1:error.indexOf("CANDIDATE_DIGEST_UNAVAILABLE:");
        if(marker>=0){
            String bounded=error.substring(marker).lines().findFirst().orElse(
                    "CANDIDATE_DIGEST_UNAVAILABLE");
            return sanitizer.sanitizeAndTruncate(bounded,MAX_PERSISTED_SUMMARY_BYTES);
        }
        return "CANDIDATE_BINDING_FAILED";
    }

    private String executionPrompt(String goal, String planText,
                                   String skillContext) {
        String planContract=strictPlanAuthority()?"""
                Execute the approved coding plan for the original goal.
                """:"""
                Execute the original goal using the Plan as advisory intent and progress only.
                The Plan does not grant Tool authority and does not gate Task completion.
                Tool execution remains governed by workspace safety, effect classification,
                runtime approval policy, and durable occurrence/replay protection.
                Continue directly from a durable Tool Result to the next needed Tool call;
                no intermediate natural-language update is required.
                """;
        String prompt = planContract+"""

                Original goal:
                %s

                Plan:
                %s
                """.formatted(goal, planText);
        return sanitizer.sanitizeAndTruncate(
                appendSkillContext(prompt, skillContext), MAX_AGENT_PROMPT_BYTES);
    }

    private HookRuntime.LifecycleResult runHooks(
            ExecutionState state, HookEvent event, String occurrenceKey)
            throws SQLException {
        if (hookRuntime == null) {
            return null;
        }
        heartbeat(state,"HOOK_"+event+"_BEFORE");
        HookRuntime activeHooks=state.executionGuard==null?hookRuntime:
                hookRuntime.withExecutionGuard(state.executionGuard);
        HookRuntime.LifecycleResult result=activeHooks.run(
                state.taskId, state.runId, event, occurrenceKey);
        heartbeat(state,"HOOK_"+event+"_AFTER");
        return result;
    }

    private boolean steeringPausedByHook(ExecutionState state,
            HookRuntime.LifecycleResult result){
        if(result==null||!result.pausedBySteering())return false;
        steeringSafePoint(state,SteeringSafePointCoordinator.Candidate.HOOK_OCCURRENCE_TERMINAL);
        return true;
    }

    private String repairPrompt(String goal, String planText,
                                VerificationResult verification,
                                String skillContext) {
        String prompt = """
                Repair the implementation so the configured verification succeeds.
                Continue to obey the existing ApprovalPolicy and tool safety rules.

                Original goal:
                %s

                Plan summary:
                %s

                Verification command:
                %s

                Exit code: %s

                Output excerpt:
                %s

                Error excerpt:
                %s
                """.formatted(
                goal,
                planText,
                valueOrPlaceholder(verification.command()),
                verification.exitCode() == null ? "<none>" : verification.exitCode(),
                valueOrPlaceholder(verification.outputExcerpt()),
                valueOrPlaceholder(verification.errorExcerpt()));
        return sanitizer.sanitizeAndTruncate(
                appendSkillContext(prompt, skillContext), MAX_AGENT_PROMPT_BYTES);
    }

    private SkillRuntime.SkillContext selectSkills(
            ExecutionState state, String stage, String occurrenceKey,
            String repairContext) throws SQLException {
        if (skillRuntime == null) {
            if(!SkillRuntime.explicitNames(state.goal).isEmpty())
                throw new SkillRuntime.SkillRuntimeException("Explicit Skill requested while Skills are disabled");
            return SkillRuntime.SkillContext.empty();
        }
        skillRuntime.bindExecutionGuard(state.executionGuard);
        authorize(state.executionGuard,"SKILL_SELECTION_WRITE");
        return skillRuntime.select(
                state.taskId, state.runId, stage, occurrenceKey,
                state.goal, state.planText, repairContext);
    }

    private String appendSkillContext(String prompt, String skillContext) {
        if (skillContext == null || skillContext.isBlank()) {
            return prompt;
        }
        return prompt + "\n\n" + skillContext;
    }

    private String instructionAndSkillContext(ExecutionState state, String skillContext) {
        StringBuilder context=new StringBuilder();
        if(!state.instructionContext.isBlank())context.append(
                "Frozen project instructions:\n").append(state.instructionContext);
        if(skillContext!=null&&!skillContext.isBlank()){
            if(!context.isEmpty())context.append("\n\n");
            context.append(skillContext);
        }
        if(state.recoveryDirective!=RecoveryDirective.NONE){
            if(!context.isEmpty())context.append("\n\n");
            context.append("Non-authoritative recovery guidance:\n")
                    .append(state.recoveryDirective.prompt());
        }
        return context.toString();
    }

    private String verificationFailureSummary(VerificationResult verification) {
        return sanitizer.sanitizeAndTruncate("status=" + verification.status()
                        + ", exitCode=" + (verification.exitCode() == null
                        ? "<none>" : verification.exitCode())
                        + ", output=" + valueOrPlaceholder(verification.outputExcerpt())
                        + ", error=" + valueOrPlaceholder(verification.errorExcerpt()),
                MAX_PERSISTED_SUMMARY_BYTES);
    }

    private String valueOrPlaceholder(String value) {
        return value == null || value.isBlank() ? "<none>" : value;
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName() : message;
    }

    private void rethrowPersistenceFailure(RuntimeException failure) throws SQLException {
        if (failure instanceof IllegalStateException
                && "Failed to persist agent state".equals(failure.getMessage())) {
            SQLException sqlException = findSqlException(failure.getCause());
            if (sqlException != null) {
                throw sqlException;
            }
            throw failure;
        }
        for (Throwable suppressed : failure.getSuppressed()) {
            SQLException sqlException = findSqlException(suppressed);
            if (sqlException != null) {
                throw sqlException;
            }
        }
    }

    private SQLException findSqlException(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
            for (Throwable suppressed : current.getSuppressed()) {
                SQLException sqlException = findSqlException(suppressed);
                if (sqlException != null) {
                    return sqlException;
                }
            }
            current = current.getCause();
        }
        return null;
    }

    private String newId() {
        return UUID.randomUUID().toString();
    }

    private void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    private static final class ExecutionState {
        private final String taskId;
        private final String runId;
        private final String goal;
        private final String verificationCommand;
        private final int maxRepairAttempts;
        private String planId;
        private String planText = "Planning disabled by configuration.";
        private List<AgentMessage> conversation;
        private int iteration;
        private int repairAttempts;
        private String answer;
        private AgentLoop agentLoop;
        private SteeringSafePointCoordinator steeringCoordinator;
        private final String instructionContext;
        private final FencedExecutionGuard executionGuard;
        private final boolean planEnabled;
        private boolean structuredPlanBound;
        private Revision structuredRevision;
        private StepDefinition currentPlanStep;
        private Attempt currentPlanAttempt;
        private long currentPlanStepVersion=-1;
        private boolean finalResponseReady;
        private String finalResponseHash;
        private String providerOccurrenceId;
        private String finalCandidateDigest;
        private final Set<String> evidenceRecoverySteps=new java.util.HashSet<>();
        private int segmentOrdinal;
        private int planningAttemptOrdinal=1;
        private RecoveryDirective recoveryDirective=RecoveryDirective.NONE;

        private ExecutionState(String taskId, String runId, String goal,
                               String verificationCommand, int maxRepairAttempts,
                               List<AgentMessage> conversation, int iteration,
                               int repairAttempts) {
            this(taskId, runId, goal, verificationCommand, maxRepairAttempts,
                    conversation, iteration, repairAttempts, "",null,true);
        }

        private ExecutionState(String taskId, String runId, String goal,
                               String verificationCommand, int maxRepairAttempts,
                               List<AgentMessage> conversation, int iteration,
                               int repairAttempts, String instructionContext,
                               FencedExecutionGuard executionGuard,
                               boolean planEnabled) {
            this.taskId = taskId;
            this.runId = runId;
            this.goal = goal;
            this.verificationCommand = verificationCommand == null
                    ? "" : verificationCommand;
            this.maxRepairAttempts = maxRepairAttempts;
            this.conversation = List.copyOf(conversation);
            this.iteration = iteration;
            this.repairAttempts = repairAttempts;
            this.instructionContext = instructionContext == null ? "" : instructionContext;
            this.executionGuard=executionGuard;
            this.planEnabled=planEnabled;
        }
    }

    private static void heartbeat(ExecutionState state,String boundary){
        if(state.executionGuard!=null)state.executionGuard.heartbeat(boundary);
    }

    private void ensureContinuitySegment(ExecutionState state)throws SQLException{
        if(!autonomousContinuityEnabled()||state.segmentOrdinal>0)return;
        state.segmentOrdinal=continuityCoordinator().beginInitialSegment(state.taskId,state.runId,
                state.planningAttemptOrdinal,state.iteration,state.executionGuard);
    }

    private Decision continuationDecision(ExecutionState state,SegmentTerminalReason reason)
            throws SQLException{
        Decision decision=continuityCoordinator().decide(new TaskContinuityCoordinator.Request(
                state.taskId,state.runId,state.segmentOrdinal,state.planningAttemptOrdinal,
                state.iteration,reason,state.executionGuard));
        if(decision.startsNextSegment()){
            state.segmentOrdinal=decision.admittedSegmentOrdinal();
            state.planningAttemptOrdinal=decision.planningAttemptOrdinal();
            state.recoveryDirective=decision.directive();
        }
        return decision;
    }

    private boolean completeContinuitySegment(ExecutionState state)throws SQLException{
        if(!autonomousContinuityEnabled()||state.segmentOrdinal<1)return true;
        var segment=stateStore.taskContinuity.find(state.taskId,state.segmentOrdinal);
        if(segment.isPresent()&&com.paicli.state.TaskContinuityStore.STARTED.equals(
                segment.get().state()))
            return continuityCoordinator().complete(state.taskId,state.segmentOrdinal,
                    state.iteration,state.executionGuard);
        return true;
    }

    private void persistContinuityCheckpoint(ExecutionState state,String status){
        if(!autonomousContinuityEnabled())return;
        try{state.executionGuard.saveCheckpoint(state.iteration,status,state.conversation);}
        catch(RuntimeException error){
            throw new IllegalStateException("CONTINUITY_CHECKPOINT_PERSISTENCE_FAILED",error);
        }
    }

    private void restoreContinuityCheckpoint(ExecutionState state){
        try{
            var checkpoint=stateStore.checkpoints.loadLatest(state.taskId).orElseThrow(()->
                    new IllegalStateException("CONTINUITY_CHECKPOINT_MISSING"));
            state.iteration=Math.max(state.iteration,checkpoint.iteration());
            state.conversation=checkpoint.messages();
        }catch(java.io.IOException|SQLException error){
            throw new IllegalStateException("CONTINUITY_CHECKPOINT_READ_FAILED",error);
        }
    }

    private CodingTaskResult waitingForUser(ExecutionState state,String reason){
        return new CodingTaskResult(state.taskId,state.runId,state.planId,TaskStore.STATUS_RUNNING,
                null,state.repairAttempts,"WAITING_FOR_USER: "+reason,state.executionGuard);
    }

    private static SegmentTerminalReason continuityReason(
            AgentLoop.ProviderProtocolException failure){
        if(com.paicli.agent.SharedToolBatchPolicy.REPEATED_BATCH_FAILURE_CODE.equals(failure.code()))
            return SegmentTerminalReason.EFFECTFUL_BATCH_SERIALIZATION_REPEATED;
        if("INVALID_TOOL_CALL_RESPONSE".equals(failure.code()))
            return SegmentTerminalReason.INVALID_TOOL_CALL_RESPONSE;
        if(!"TOOL_CALL_BATCH_PREFLIGHT_FAILED".equals(failure.code()))return null;
        if(failure.preflightFailureKind()==null)
            return SegmentTerminalReason.TOOL_PREFLIGHT_INTEGRITY_FAILURE;
        return switch(failure.preflightFailureKind()){
            case MODEL_CORRECTABLE->SegmentTerminalReason.TOOL_PREFLIGHT_MODEL_CORRECTABLE;
            case APPROVAL_REQUIRED->SegmentTerminalReason.TOOL_PREFLIGHT_APPROVAL_REQUIRED;
            case POLICY_DENIED->SegmentTerminalReason.TOOL_PREFLIGHT_POLICY_DENIED;
            case INTEGRITY_FAILURE->SegmentTerminalReason.TOOL_PREFLIGHT_INTEGRITY_FAILURE;
        };
    }

    private static void authorize(FencedExecutionGuard guard,String boundary){
        if(guard!=null)guard.validate(boundary);
    }

    private void updateLastIteration(ExecutionState state)throws SQLException{
        if(state.executionGuard!=null)state.executionGuard.updateLastIteration(state.iteration);
        else stateStore.codingRuns.updateLastIteration(state.runId,state.iteration);
    }

    private int finishRepair(ExecutionState state,String repairId,String status)throws SQLException{
        if(state.executionGuard!=null)return Math.toIntExact(
                state.executionGuard.finishRepair(repairId,status));
        if(RepairAttemptStore.STATUS_COMPLETED.equals(status))
            stateStore.repairAttempts.markCompleted(repairId);
        else stateStore.repairAttempts.markFailed(repairId);
        return stateStore.codingRuns.incrementRepairAttempts(state.runId);
    }

    private void transitionPlan(ExecutionState state,String planId,String expected,String next)
            throws SQLException{
        if(state.executionGuard!=null)state.executionGuard.transitionPlan(planId,expected,next);
        else if(TaskPlanStore.STATUS_ACTIVE.equals(next))stateStore.plans.markActive(planId);
        else if(TaskPlanStore.STATUS_COMPLETED.equals(next))stateStore.plans.markCompleted(planId);
        else stateStore.plans.markFailed(planId);
    }

    private void terminalize(ExecutionState state,boolean success,String message)throws SQLException{
        if(state.executionGuard!=null){state.executionGuard.terminalize(state.planId,success,message);return;}
        if(success){stateStore.codingRuns.markCompleted(state.runId);stateStore.tasks.markDone(state.taskId);}
        else{stateStore.codingRuns.markFailed(state.runId,message);stateStore.tasks.markFailed(
                state.taskId,message);}
    }

    private record RepairExecution(
            VerificationResult verification,
            CodingTaskResult terminalResult
    ) {
    }

    private record FreshnessCheck(boolean fresh,String reason) {
        private static final FreshnessCheck FRESH=new FreshnessCheck(true,null);
    }

    private static final class HookLifecycleException extends RuntimeException {
        private final String verificationStatus;
        private final boolean pausedBySteering;

        private HookLifecycleException(String message, String verificationStatus) {
            this(message,verificationStatus,false);
        }
        private HookLifecycleException(String message,String verificationStatus,
                boolean pausedBySteering){
            super(message);
            this.verificationStatus = verificationStatus;
            this.pausedBySteering=pausedBySteering;
        }
        private static HookLifecycleException steeringPaused(String verificationStatus){
            return new HookLifecycleException("PAUSED_BY_STEERING",
                    verificationStatus,true);
        }

        private String verificationStatus() {
            return verificationStatus;
        }
        private boolean pausedBySteering(){return pausedBySteering;}
    }

    private void validateOrBindRuntimeManifest(
            CodingRunStore.CodingRunRecord active) throws SQLException {
        if (runtimeManifest == null) {
            return;
        }
        if (active.skillsManifestSha256() == null
                && active.hooksManifestSha256() == null) {
            stateStore.codingRuns.updateRuntimeManifestHashes(
                    active.runId(), runtimeManifest.skillsSha256(),
                    runtimeManifest.hooksSha256());
            return;
        }
        List<String> changed = new java.util.ArrayList<>();
        if (!Objects.equals(active.skillsManifestSha256(),
                runtimeManifest.skillsSha256())) {
            changed.add("skills");
        }
        if (!Objects.equals(active.hooksManifestSha256(),
                runtimeManifest.hooksSha256())) {
            changed.add("hooks");
        }
        if (!changed.isEmpty()) {
            throw new IllegalStateException(
                    "Runtime manifest changed for active resume: "
                            + String.join(", ", changed));
        }
    }

    @FunctionalInterface
    public interface AgentLoopFactory {
        AgentLoop create(String taskId);
    }
}
