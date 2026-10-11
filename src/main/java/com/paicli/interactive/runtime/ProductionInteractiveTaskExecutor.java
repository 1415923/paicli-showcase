package com.paicli.interactive.runtime;

import com.paicli.agent.AgentLoop;
import com.paicli.config.Config;
import com.paicli.config.ConfigLoader;
import com.paicli.hook.HookConfigLoader;
import com.paicli.hook.HookDefinition;
import com.paicli.hook.HookRunner;
import com.paicli.hook.HookRuntime;
import com.paicli.provider.ModelProvider;
import com.paicli.provider.telemetry.ProviderCallContext;
import com.paicli.provider.telemetry.ExecutionProviderTelemetry;
import com.paicli.provider.ProviderFactory;
import com.paicli.runtime.BoundedProcessRunner;
import com.paicli.runtime.CodingTaskResult;
import com.paicli.runtime.CodingTaskRunner;
import com.paicli.runtime.PersistenceSanitizer;
import com.paicli.runtime.PlanService;
import com.paicli.runtime.RuntimeManifest;
import com.paicli.runtime.RuntimeManifestHasher;
import com.paicli.runtime.R1bRuntimeContract;
import com.paicli.runtime.InstructionManifestRuntime;
import com.paicli.runtime.TaskRecoveryService;
import com.paicli.runtime.FencedExecutionGuard;
import com.paicli.runtime.TaskRecoveryRejectedException;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.runtime.VerificationService;
import com.paicli.safety.ApprovalPolicy;
import com.paicli.safety.SafetyPolicy;
import com.paicli.session.AuditLog;
import com.paicli.skill.ControlPlanePaths;
import com.paicli.skill.SkillContextBuilder;
import com.paicli.skill.SkillLoader;
import com.paicli.skill.SkillRuntime;
import com.paicli.skill.SkillSelector;
import com.paicli.state.FileSnapshotService;
import com.paicli.state.StateStore;
import com.paicli.state.TaskStore;
import com.paicli.tool.GitDiffTool;
import com.paicli.tool.GrepTool;
import com.paicli.tool.ListFilesTool;
import com.paicli.tool.ReadFileTool;
import com.paicli.tool.RunCommandTool;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.WriteFileTool;
import com.paicli.interactive.task.InteractiveTaskRequest;
import com.paicli.interactive.diff.PreWriteDiffService;
import com.paicli.interactive.approval.InteractiveApprovalPolicy;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Production composition used only by the internal interactive runtime adapter in R2. */
public final class ProductionInteractiveTaskExecutor
        implements InteractiveRuntimeTaskExecutor {
    private final Path workspace;
    private com.paicli.mcp.interactive.InteractiveMcpService mcp;
    void bindMcp(com.paicli.mcp.interactive.InteractiveMcpService service) {
        if (mcp != null) throw new IllegalStateException("MCP already bound");
        mcp = java.util.Objects.requireNonNull(service);
    }
    private final ProviderResolver providerResolver;
    private final java.util.function.Supplier<Config> configSupplier;
    private final String ownerInstanceId = "interactive-"
            + UUID.randomUUID().toString().replace("-", "");

    public ProductionInteractiveTaskExecutor(Path workspace) {
        this(workspace, () -> new ConfigLoader(workspace).load(), ProviderFactory::create);
    }

    public ProductionInteractiveTaskExecutor(Path workspace,
            java.util.function.Supplier<Config> configSupplier) {
        this(workspace, configSupplier, ProviderFactory::create);
    }

    ProductionInteractiveTaskExecutor(Path workspace, ProviderResolver providerResolver) {
        this(workspace, () -> new ConfigLoader(workspace).load(), providerResolver);
    }

    ProductionInteractiveTaskExecutor(Path workspace,
            java.util.function.Supplier<Config> configSupplier,
            ProviderResolver providerResolver) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.configSupplier = java.util.Objects.requireNonNull(configSupplier, "configSupplier");
        this.providerResolver = java.util.Objects.requireNonNull(providerResolver, "providerResolver");
    }

    @Override
    public CodingTaskResult execute(String taskId, InteractiveTaskRequest request,
            StateStore stateStore, RuntimeEventBridge bridge) throws Exception {
        if (!request.workspace().equals(workspace)) {
            throw new IllegalArgumentException("interactive workspace identity mismatch");
        }

        Config config = java.util.Objects.requireNonNull(configSupplier.get(), "interactive config");
        config = com.paicli.interactive.verification.InteractiveVerificationPolicy
                .apply(config, workspace, bridge.verificationMode());
        config = config.withApprovalMode(bridge.approvalMode());
        InteractiveCodeIntelligenceStage.CodeFacts codeFacts =
                new InteractiveCodeIntelligenceStage().inspect(
                        workspace, request.input(), request.fileReferences(), stateStore,
                        () -> {
                            if (Thread.currentThread().isInterrupted()
                                    || bridge.cancellation().cancellationRequested()) {
                                throw new com.paicli.runtime.CancellationToken
                                        .TaskCancellationException(
                                        bridge.cancellation().forceRequested());
                            }
                        });
        bridge.stageCodeContext(codeFacts);
        return run(taskId, request.input(), config, stateStore, bridge);
    }

    @Override
    public CodingTaskResult resume(String taskId, StateStore stateStore,
            RuntimeEventBridge bridge) throws Exception {
        if (mcp != null) mcp.verifyRecovery(taskId);
        CanonicalId canonicalTask = CanonicalId.parse(taskId);
        if (canonicalTask.family() != EntityFamily.TASK) {
            throw new TaskRecoveryRejectedException(
                    "TASK_NOT_FOUND", "Task Recovery requires a canonical Task ID.");
        }
        Config config = java.util.Objects.requireNonNull(configSupplier.get(), "interactive config");
        config = com.paicli.interactive.verification.InteractiveVerificationPolicy
                .apply(config, workspace, bridge.verificationMode());
        config = config.withApprovalMode(bridge.approvalMode());
        TaskStore.TaskRecord task = stateStore.tasks.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found"));
        if (java.util.Set.of(TaskStore.STATUS_DONE,
                TaskStore.STATUS_COMPLETED_VERIFIED,
                TaskStore.STATUS_COMPLETED_UNVERIFIED,
                TaskStore.STATUS_REJECTED,
                TaskStore.STATUS_CANCELLED,
                TaskStore.STATUS_CANCELLED_UNKNOWN_OUTCOME).contains(task.status())) {
            throw new TaskRecoveryRejectedException(
                    "TASK_NOT_RECOVERABLE", "Task is already terminal.");
        }
        java.util.function.Predicate<com.paicli.model.AgentMessage> privateContinuation=message->
                message.type()==com.paicli.model.AgentMessage.MessageType.ACTION
                        &&message.toolCallJson()!=null
                        &&message.toolCallJson().contains(
                                "\"x_paicli_private_continuation\":true");
        var pointer=stateStore.r1aDurable.readContextPointer(canonicalTask).orElse(null);
        var currentRun=pointer==null?null:stateStore.codingRuns
                .findById(pointer.ownerRunId().value()).orElse(null);
        boolean acceptedFinalRecovery=currentRun!=null
                &&com.paicli.state.CodingRunStore.PHASE_VERIFYING.equals(currentRun.phase())
                &&stateStore.r1aDurable.findAcceptedProviderFinal(canonicalTask,
                        pointer.ownerRunId(),currentRun.lastIteration()).isPresent();
        boolean privateContinuationLost=stateStore.events.findMessages(taskId).stream()
                .anyMatch(privateContinuation)
                ||stateStore.checkpoints.loadLatest(taskId)
                        .map(checkpoint->checkpoint.messages().stream()
                                .anyMatch(privateContinuation)).orElse(false);
        if(privateContinuationLost&&!acceptedFinalRecovery){
            throw new TaskRecoveryRejectedException(
                    "PROVIDER_PRIVATE_CONTINUATION_UNAVAILABLE",
                    "This interrupted thinking Tool chain cannot be resumed safely. "
                            +"Start a new Task so PaiCLI can establish a complete Provider turn.");
        }
        RuntimeManifest runtimeManifest = new RuntimeManifestHasher(workspace).compute(config);
        Instant acquiredAt = Instant.now();
        long leaseSeconds = Math.multiplyExact((long) config.commandTimeoutSeconds(),
                config.effectiveMaxIterations());
        TaskRecoveryService.RecoveryResult result = new TaskRecoveryService(
                stateStore, new InstructionManifestRuntime(stateStore, workspace)).recover(
                canonicalTask, config, runtimeManifest, config.maxProjectInstructionChars(),
                new TaskRecoveryService.LeaseRequest(ownerInstanceId,
                        UUID.randomUUID().toString().replace("-", ""), acquiredAt,
                        acquiredAt.plusSeconds(leaseSeconds), Duration.ZERO));
        if (result instanceof TaskRecoveryService.Rejected rejected) {
            throw rejection(rejected);
        }
        TaskRecoveryService.Success success = (TaskRecoveryService.Success) result;
        return run(taskId, task.goal(), config, stateStore, bridge,
                runtimeManifest, success);
    }

    @Override
    public CodingTaskResult executeAdmittedChild(String taskId,String goal,StateStore stateStore,
            RuntimeEventBridge bridge,com.paicli.state.FollowUpModels.ChildStartClaim claim)throws Exception{
        var binding=prepareAdmittedChildExecution(taskId,stateStore,bridge,claim.child());
        return executeAdmittedChild(taskId,goal,stateStore,bridge,claim,binding);
    }

    @Override
    public AdmittedChildExecutionBinding prepareAdmittedChildExecution(String taskId,
            StateStore stateStore,RuntimeEventBridge bridge,
            com.paicli.state.FollowUpModels.ChildAuthority child)throws Exception{
        Config supplied=java.util.Objects.requireNonNull(configSupplier.get(),"interactive config");
        var task=stateStore.tasks.findById(taskId).orElseThrow(()->bindingFailure(
                "EXECUTION_BINDING_TASK_MISSING","Durable Child Task is unavailable."));
        if(!taskId.equals(child.taskId().value()))throw bindingFailure(
                "EXECUTION_BINDING_IDENTITY_MISMATCH","Child Task binding is inconsistent.");
        ProviderFactory.requirePublicRuntimeProvider(task.provider());
        if(!java.util.Set.of(Config.PROVIDER_OLLAMA,Config.PROVIDER_OPENAI)
                .contains(task.provider()))throw bindingFailure(
                "EXECUTION_BINDING_PROVIDER_UNSUPPORTED","Durable Child provider is unsupported for public execution.");
        if(!supplied.effectiveProvider().equals(task.provider()))throw bindingFailure(
                "EXECUTION_BINDING_PROVIDER_MISMATCH","Current provider configuration is incompatible with the durable Child Task.");
        var run=stateStore.r1aDurable.findRun(child.runId()).orElseThrow(()->bindingFailure(
                "EXECUTION_BINDING_RUN_MISSING","Durable Child Run is unavailable."));
        var generation=stateStore.r1aDurable.findGeneration(child.generationId()).orElseThrow(()->bindingFailure(
                "EXECUTION_BINDING_GENERATION_MISSING","Durable Child generation is unavailable."));
        var pointer=stateStore.r1aDurable.readContextPointer(child.taskId()).orElseThrow(()->bindingFailure(
                "EXECUTION_BINDING_POINTER_MISSING","Durable Child context pointer is unavailable."));
        R1bRuntimeContract frozen;
        try{frozen=R1bRuntimeContract.fromJson(generation.tokenMetadata());}
        catch(RuntimeException error){throw bindingFailure("EXECUTION_BINDING_R1B_MISSING",
                "Durable Child runtime binding is unavailable.");}
        String durableModel=task.model()==null||task.model().isBlank()
                ?task.provider()+":default":task.model();
        if(!run.taskId().equals(child.taskId())||!generation.taskId().equals(child.taskId())
                ||!generation.createdByRunId().equals(child.runId())
                ||!pointer.generationId().equals(child.generationId())
                ||!pointer.ownerRunId().equals(child.runId())
                ||!task.provider().equals(frozen.providerFamily())
                ||!durableModel.equals(frozen.modelIdentity()))throw bindingFailure(
                "EXECUTION_BINDING_R1B_MISMATCH","Durable Child execution identities are inconsistent.");
        Config effective=com.paicli.interactive.verification.InteractiveVerificationPolicy
                .apply(supplied.withModel(task.model()),workspace,bridge.verificationMode())
                .withApprovalMode(bridge.approvalMode());
        PersistenceSanitizer sanitizer=new PersistenceSanitizer();
        effective=effective.withSystemPrompt(sanitizer.sanitizeAndTruncate(
                effective.effectiveSystemPrompt(),PersistenceSanitizer.DEFAULT_MAX_TEXT_BYTES));
        return new AdmittedChildExecutionBinding(effective,child,task.provider(),task.model());
    }

    @Override
    public CodingTaskResult executeAdmittedChild(String taskId,String goal,StateStore stateStore,
            RuntimeEventBridge bridge,com.paicli.state.FollowUpModels.ChildStartClaim claim,
            AdmittedChildExecutionBinding binding)throws Exception{
        try{binding.requireClaim(claim);}catch(IllegalArgumentException error){throw bindingFailure(
                "EXECUTION_BINDING_CLAIM_MISMATCH","Child start claim does not match the prepared execution binding.");
        }
        return run(taskId,goal,java.util.Objects.requireNonNull(binding.config()),stateStore,
                bridge,null,null,true,claim);
    }

    private static com.paicli.runtime.TaskRecoveryRejectedException bindingFailure(
            String code,String message){return new com.paicli.runtime.TaskRecoveryRejectedException(code,message);}

    private CodingTaskResult run(String taskId, String goal,
            Config config, StateStore stateStore, RuntimeEventBridge bridge)
            throws Exception {
        return run(taskId, goal, config, stateStore, bridge,null,null,false,null);
    }

    private CodingTaskResult run(String taskId, String goal,
            Config config, StateStore stateStore, RuntimeEventBridge bridge,
            RuntimeManifest suppliedManifest,
            TaskRecoveryService.Success recovery) throws Exception {
        return run(taskId,goal,config,stateStore,bridge,suppliedManifest,recovery,false,null);
    }

    private CodingTaskResult run(String taskId,String goal,Config config,StateStore stateStore,
            RuntimeEventBridge bridge,RuntimeManifest suppliedManifest,
            TaskRecoveryService.Success recovery,boolean admittedChild,
            com.paicli.state.FollowUpModels.ChildStartClaim childStartClaim)throws Exception{
        try (AutoCloseable scope = mcp == null ? () -> {} : mcp.acquireTask(taskId, recovery != null)) {
            return runInMcpScope(taskId, goal, config, stateStore, bridge, suppliedManifest,
                    recovery, admittedChild, childStartClaim);
        }
    }

    private CodingTaskResult runInMcpScope(String taskId,String goal,Config config,StateStore stateStore,
            RuntimeEventBridge bridge,RuntimeManifest suppliedManifest,
            TaskRecoveryService.Success recovery,boolean admittedChild,
            com.paicli.state.FollowUpModels.ChildStartClaim childStartClaim)throws Exception{
        PersistenceSanitizer sanitizer = new PersistenceSanitizer();
        String sanitizedPrompt=sanitizer.sanitizeAndTruncate(config.effectiveSystemPrompt(),
                PersistenceSanitizer.DEFAULT_MAX_TEXT_BYTES);
        Config providerConfig=admittedChild&&sanitizedPrompt.equals(config.effectiveSystemPrompt())
                ?config:config.withSystemPrompt(sanitizedPrompt);
        com.paicli.diagnostic.StageTrace.event(
                "PROVIDER_PREPARATION_START", false,
                "provider", providerConfig.effectiveProvider(),
                "model", providerConfig.model());
        ModelProvider rawProvider;
        try {
            rawProvider = providerResolver.create(providerConfig);
            com.paicli.diagnostic.StageTrace.event(
                    "PROVIDER_PREPARATION_END", false,
                    "outcome", "COMPLETED",
                    "provider", providerConfig.effectiveProvider(),
                    "model", providerConfig.model());
        } catch (RuntimeException error) {
            com.paicli.diagnostic.StageTrace.event(
                    "PROVIDER_PREPARATION_END", false,
                    "outcome", "ERROR",
                    "error_type", error.getClass().getName());
            throw error;
        }
        java.util.function.Supplier<ProviderCallContext> telemetryContext = () -> {
            try {
                var pointer = stateStore.r1aDurable.readContextPointer(
                        com.paicli.identity.CanonicalId.parse(taskId)).orElse(null);
                return new ProviderCallContext(bridge.sessionId(), taskId,
                        pointer == null ? bridge.activeRunId() : pointer.ownerRunId().value(),
                        pointer == null ? null : pointer.generationId().value());
            } catch (RuntimeException | java.sql.SQLException ignored) {
                return new ProviderCallContext(bridge.sessionId(), taskId,
                        bridge.activeRunId(), null);
            }
        };
        FencedExecutionGuard recoveryGuard=recovery==null?null:
                FencedExecutionGuard.system(stateStore,recovery.executionAuthority());
        if(recoveryGuard!=null)bridge.bindExecutionGuard(recoveryGuard);

        SafetyPolicy policy = new SafetyPolicy(
                workspace, config.commandTimeoutSeconds(), config.maxOutputBytes());
        AuditLog auditLog = new AuditLog(
                workspace.resolve(".paicli").resolve("audit"), taskId);
        ControlPlanePaths controlPlanePaths = new ControlPlanePaths(
                workspace, config.effectiveSkillsDirs(),
                List.of(config.effectiveHooksConfigPath()));

        SkillRuntime skills = config.isSkillsEnabled()
                ? new SkillRuntime(
                        new SkillLoader(workspace,stateStore.skillLoads,sanitizer,recoveryGuard),
                        new SkillSelector(stateStore.skillSelections,sanitizer,recoveryGuard),
                        new SkillContextBuilder(sanitizer),
                        stateStore.skillSelections, config)
                : null;
        HookRuntime hooks = hooks(config, stateStore, sanitizer,recoveryGuard);
        com.paicli.diagnostic.StageTrace.event(
                "MANIFEST_BUILD_START", false,
                "manifest_kind", "RUNTIME_SKILLS_HOOKS",
                "source", suppliedManifest == null ? "COMPUTE" : "SUPPLIED");
        RuntimeManifest manifest;
        try {
            manifest = suppliedManifest == null
                    ? new RuntimeManifestHasher(workspace).compute(config) : suppliedManifest;
            com.paicli.diagnostic.StageTrace.event(
                    "MANIFEST_BUILD_END", false,
                    "manifest_kind", "RUNTIME_SKILLS_HOOKS",
                    "outcome", "COMPLETED",
                    "source", suppliedManifest == null ? "COMPUTED" : "SUPPLIED");
        } catch (RuntimeException error) {
            com.paicli.diagnostic.StageTrace.event(
                    "MANIFEST_BUILD_END", false,
                    "manifest_kind", "RUNTIME_SKILLS_HOOKS",
                    "outcome", "ERROR",
                    "error_type", error.getClass().getName());
            throw error;
        }
        bridge.approvals().beginTask(taskId, null, bridge.approvalMode(), goal,
                new PreWriteDiffService(policy, controlPlanePaths), bridge.approvalEvents());

        ExecutionProviderTelemetry.Providers instrumented =
                ExecutionProviderTelemetry.instrument(rawProvider, rawProvider,
                        stateStore.providerTelemetry, config.effectiveProvider(), config.model(),
                        telemetryContext,bridge,config.isTaskContinuityEnabled());
        Integer providerReportedContext = rawProvider instanceof com.paicli.provider.OllamaProvider ollama
                ? ollama.reportedContextWindowTokens() : null;
        com.paicli.context.ContextModels.CapabilityProfile contextCapability =
                com.paicli.context.ContextualModelProvider.capability(
                        config.effectiveProvider(), config.model(), providerReportedContext,
                        config.effectiveContextWindowTokens());
        ModelProvider provider = new com.paicli.context.ContextualModelProvider(
                instrumented.chat(), stateStore, workspace, taskId,
                config.effectiveProvider(), config.model(),
                com.paicli.context.ContextModels.OperationKind.CHAT,
                contextCapability, admittedChild,
                config.effectiveSystemPrompt(),bridge::contextCompacted);
        ModelProvider planningProvider = new com.paicli.context.ContextualModelProvider(
                instrumented.plan(), stateStore, workspace, taskId,
                config.effectiveProvider(), config.model(),
                com.paicli.context.ContextModels.OperationKind.PLAN,
                contextCapability, admittedChild,
                config.effectiveSystemPrompt(),bridge::contextCompacted);

        VerificationService verification = new VerificationService(
                workspace, stateStore.verifications, sanitizer,
                config.effectiveVerificationTimeoutSeconds(),
                config.effectiveMaxVerificationOutputBytes(), bridge,
                bridge.cancellation(), bridge.cancellation().processes());
        Config effective = config;
        CodingTaskRunner runner = new CodingTaskRunner(
                stateStore,
                new PlanService(planningProvider, stateStore.plans),
                verification,
                managedTaskId -> new AgentLoop(
                        provider,
                        tools(policy, snapshots(stateStore,bridge,managedTaskId), managedTaskId,
                                controlPlanePaths,
                                bridge.cancellation().processes(),
                                new InteractiveApprovalPolicy(bridge::approvalMode, managedTaskId)),
                        effective.effectiveMaxIterations(),
                        new InteractiveApprovalPolicy(bridge::approvalMode, managedTaskId),
                        auditLog,
                        stateStore,
                        managedTaskId,
                        effective.effectiveProvider(),
                        effective.model(),
                        bridge, bridge.approvals()),
                config, sanitizer, skills, hooks, manifest, bridge,
                new PersistedAppliedCandidateGate(stateStore),
                new InstructionManifestRuntime(stateStore, workspace),
                bridge.approvals());
        com.paicli.runtime.ProductionTaskContinuityWiring.configure(
                runner, config, stateStore);
        try {
            if(admittedChild){
                var frozen=new InstructionManifestRuntime(stateStore,workspace)
                        .loadFrozen(CanonicalId.parse(taskId));
                String capsuleContext=stateStore.followUps.childCapsuleContext(CanonicalId.parse(taskId));
                String childContext=frozen.prompt()+(capsuleContext.isBlank()?"":"\n\n--- Parent Capsule Facts (context only) ---\n"+capsuleContext);
                var record=stateStore.codingRuns.findById(
                        stateStore.r1aDurable.findRunsByTask(CanonicalId.parse(taskId)).getFirst().id().value())
                        .orElseThrow();
                var admitted=new com.paicli.state.RootAdmissionTransaction.RuntimeRun(record.runId(),taskId,
                        record.runKind(),record.phase(),record.verificationCommand(),record.maxRepairAttempts(),
                        record.startedIteration(),record.skillsManifestSha256(),record.hooksManifestSha256());
                return runner.runAdmittedChild(taskId,goal,
                        sanitizer.sanitizeAndTruncate(childContext,64*1024),admitted,
                        java.util.Objects.requireNonNull(childStartClaim,"Child Initial Run claim"));
            }
            return recovery == null ? runner.run(taskId, goal):runner.recover(recovery, goal,recoveryGuard);
        } finally {
            bridge.approvals().endTask(taskId);
        }
    }

    private static TaskRecoveryRejectedException rejection(
            TaskRecoveryService.Rejected rejected) {
        String code = switch (rejected.status()) {
            case CONTINUATION_UNAVAILABLE -> "CONTINUATION_UNAVAILABLE";
            case TASK_RECOVERY_RUNTIME_DRIFT -> "TASK_RECOVERY_RUNTIME_DRIFT";
            case ARTIFACT_UNAVAILABLE -> "ARTIFACT_UNAVAILABLE";
            case ARTIFACT_INTEGRITY_FAILURE -> "ARTIFACT_INTEGRITY_FAILURE";
            case UNRESOLVED_OCCURRENCE -> "OCCURRENCE_REQUIRES_REVIEW";
            case OWNERSHIP_CONFLICT -> "OWNERSHIP_CONFLICT";
            case VERSION_CONFLICT -> "TASK_RECOVERY_VERSION_CONFLICT";
            case RECOVERY_ADMISSION_FAILED -> "TASK_RECOVERY_ADMISSION_FAILED";
            case NO_APPROVED_PLAN -> "NO_APPROVED_PLAN";
            case PLAN_AUTHORITY_UNAVAILABLE -> "PLAN_AUTHORITY_UNAVAILABLE";
            case PLAN_AUTHORITY_CONFLICT -> "PLAN_AUTHORITY_CONFLICT";
            case PLAN_REVISION_INTEGRITY_FAILURE -> "PLAN_REVISION_INTEGRITY_FAILURE";
            case PLAN_STEP_STATE_UNAVAILABLE -> "PLAN_STEP_STATE_UNAVAILABLE";
            case PLAN_STEP_STATE_INTEGRITY_FAILURE -> "PLAN_STEP_STATE_INTEGRITY_FAILURE";
            case PLAN_ATTEMPT_UNRESOLVED -> "PLAN_ATTEMPT_UNRESOLVED";
            case PLAN_EVIDENCE_INTEGRITY_FAILURE -> "PLAN_EVIDENCE_INTEGRITY_FAILURE";
            case COMPLETION_AUTHORITY_INTEGRITY_FAILURE ->
                    "COMPLETION_AUTHORITY_INTEGRITY_FAILURE";
            case COMPLETION_RULES_UNAVAILABLE -> "COMPLETION_RULES_UNAVAILABLE";
            case STEERING_AUTHORITY_INTEGRITY_FAILURE ->
                    "STEERING_AUTHORITY_INTEGRITY_FAILURE";
            case LEGACY_PLAN_CONTINUATION_UNAVAILABLE ->
                    "LEGACY_PLAN_CONTINUATION_UNAVAILABLE";
            case PLAN_TOOL_SCOPE_UNAVAILABLE -> "PLAN_TOOL_SCOPE_UNAVAILABLE";
            case CAPSULE_INTEGRITY_FAILURE -> "CAPSULE_INTEGRITY_FAILURE";
            case INSTRUCTION_DRIFT_INFORMATIONAL, NO_DRIFT ->
                    "TASK_RECOVERY_ADMISSION_FAILED";
        };
        String reason = rejected.reasons().isEmpty()
                ? rejected.status().name() : rejected.reasons().getFirst();
        return new TaskRecoveryRejectedException(code, reason);
    }

    private HookRuntime hooks(Config config, StateStore stateStore,
            PersistenceSanitizer sanitizer,FencedExecutionGuard executionGuard) throws Exception {
        if (!config.isHooksEnabled()) return null;
        List<HookDefinition> definitions = new HookConfigLoader(
                workspace, config.effectiveHookTimeoutSeconds(),
                config.effectiveHookFailurePolicy())
                .load(Path.of(config.effectiveHooksConfigPath()));
        HookRunner runner = new HookRunner(
                stateStore.hookRuns,
                new BoundedProcessRunner(
                        workspace, sanitizer, config.effectiveMaxHookOutputBytes()),
                sanitizer, config.effectiveMaxHookOutputBytes(),executionGuard);
        return new HookRuntime(definitions, runner, stateStore.hookRuns,executionGuard);
    }

    @FunctionalInterface
    interface ProviderResolver {
        ModelProvider create(Config config);
    }

    private ToolRegistry tools(SafetyPolicy policy, FileSnapshotService snapshots,
            String taskId, ControlPlanePaths controlPlanePaths,
            com.paicli.runtime.OwnedProcessRegistry processes, ApprovalPolicy approvals) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ListFilesTool(policy));
        registry.register(new ReadFileTool(policy));
        registry.register(new GrepTool(policy));
        registry.register(new RunCommandTool(policy, processes));
        registry.register(new GitDiffTool(policy, processes));
        registry.register(new WriteFileTool(
                policy, snapshots, taskId, null, controlPlanePaths));
        try { return mcp == null ? registry : mcp.derive(taskId, registry, approvals); }
        catch (Exception error) { throw new IllegalStateException("MCP task binding failed", error); }
    }

    private FileSnapshotService snapshots(StateStore stateStore,RuntimeEventBridge bridge,
            String managedTaskId){
        FencedExecutionGuard guard=bridge.executionGuard();
        if(guard==null)throw new IllegalStateException(
                "EXECUTION_AUTHORITY_REQUIRED_FOR_SNAPSHOT");
        if(!guard.current().taskId().value().equals(managedTaskId))
            throw new IllegalStateException("SNAPSHOT_EXECUTION_AUTHORITY_MISMATCH");
        try{return new FileSnapshotService(workspace,stateStore.snapshots,guard);}
        catch(java.io.IOException error){throw new IllegalStateException(
                "SNAPSHOT_SERVICE_INITIALIZATION_FAILED",error);}
    }
}
