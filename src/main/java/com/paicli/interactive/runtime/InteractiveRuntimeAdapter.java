package com.paicli.interactive.runtime;

import com.paicli.event.AgentEventPublisher;
import com.paicli.event.AgentEventSink;
import com.paicli.event.AgentEventType;
import com.paicli.event.AgentEvents;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.approval.ApprovalInteractionResult;
import com.paicli.interactive.approval.InteractiveApprovalCoordinator;
import com.paicli.interactive.approval.InteractiveApprovalRequest;
import com.paicli.interactive.cancel.InteractiveCancellationController;
import com.paicli.runtime.CancellationToken;
import com.paicli.interactive.task.InteractiveTaskRequest;
import com.paicli.interactive.task.TaskSubmissionPort;
import com.paicli.interactive.task.TaskSubmissionResult;
import com.paicli.runtime.CodingTaskResult;
import com.paicli.runtime.FencedExecutionGuard;
import com.paicli.state.StateStore;
import com.paicli.state.TaskStore;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.interactive.session.SavedSessionService;
import com.paicli.interactive.session.SessionBinding;
import com.paicli.runtime.TaskRecoveryRejectedException;
import com.paicli.runtime.SteeringClassifier;
import com.paicli.state.SteeringModels;
import com.paicli.state.SteeringStore;
import com.paicli.interactive.steering.SteeringConfirmationRequest;
import com.paicli.interactive.followup.FollowUpConfirmationRequest;
import com.paicli.runtime.FollowUpService;
import com.paicli.runtime.InstructionManifestRuntime;
import com.paicli.runtime.RuntimeManifestHasher;
import com.paicli.config.ConfigLoader;
import com.paicli.state.FollowUpModels;
import com.paicli.provider.ProviderFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Session-owned asynchronous adapter from shell task submissions to the production runtime.
 * It exposes no Provider, AgentLoop, ToolRegistry, process or database handle.
 */
public final class InteractiveRuntimeAdapter implements TaskSubmissionPort, AutoCloseable {
    private static final Duration CLOSE_WAIT = Duration.ofSeconds(5);
    private final Path workspace;
    private final InteractiveRuntimeTaskExecutor executor;
    private final StateStore stateStore;
    private final com.paicli.mcp.interactive.InteractiveMcpService mcp;
    private final InteractiveRuntimeView view;
    private final AgentEventSink sink;
    private volatile AgentEventSink lifecycleSink;
    private volatile AgentEventPublisher publisher;
    private final ExecutorService worker;
    private final AtomicReference<InteractiveTaskHandle> active = new AtomicReference<>();
    private final AtomicReference<InteractiveTaskResult> last = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Runnable> terminalListener =
            new AtomicReference<>(() -> { });
    private final AtomicReference<Runnable> promptRefreshListener =
            new AtomicReference<>(() -> { });
    private volatile InteractiveSession session;
    private volatile RuntimeEventBridge bridge;
    private volatile Future<?> activeFuture;
    private volatile InteractiveApprovalCoordinator approvals;
    private volatile InteractiveCancellationController cancellation;
    private final AtomicReference<PendingFollowUpAdmissionKey> blockedPendingFollowUpAdmission =
            new AtomicReference<>();
    private final AtomicReference<PreparedPendingChild> preparedPendingChild =
            new AtomicReference<>();

    public InteractiveRuntimeAdapter(Path workspace,
            InteractiveRuntimeTaskExecutor executor, AgentEventSink sink,
            InteractiveRuntimeView view, StateStore stateStore) {
        this(workspace,executor,sink,view,stateStore,
                java.util.concurrent.Executors.newSingleThreadExecutor(
                        Thread.ofVirtual().name("paicli-interactive-task-", 0).factory()));
    }

    InteractiveRuntimeAdapter(Path workspace,
            InteractiveRuntimeTaskExecutor executor, AgentEventSink sink,
            InteractiveRuntimeView view, StateStore stateStore,ExecutorService worker) {
        this.workspace = Objects.requireNonNull(workspace, "workspace")
                .toAbsolutePath().normalize();
        this.executor = Objects.requireNonNull(executor, "executor");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.view = Objects.requireNonNull(view, "view");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.mcp = new com.paicli.mcp.interactive.InteractiveMcpService(this.workspace, stateStore);
        if (executor instanceof ProductionInteractiveTaskExecutor production) production.bindMcp(mcp);
        this.worker = Objects.requireNonNull(worker, "worker");
    }

    public com.paicli.interactive.command.ShellCommandResult mcpCommand(
            java.util.List<String> args, InteractiveSession session) {
        if (closed.get()) throw new IllegalStateException("interactive runtime is closed");
        return mcp.command(args, session);
    }

    public com.paicli.interactive.command.ShellCommandResult skillCommand(
            java.util.List<String> args,InteractiveSession session) {
        if(closed.get())throw new IllegalStateException("interactive runtime is closed");
        try {return new com.paicli.skill.SkillCommands(workspace,new ConfigLoader(workspace).load().effectiveSkillsDirs())
                .command(args,active.get()!=null||session.snapshot().currentTaskId()!=null);}
        catch(Exception error){return com.paicli.interactive.command.ShellCommandResult.continueWith(
                "SKILL_COMMAND_REJECTED",java.util.List.of("Skill configuration could not be loaded."));}
    }

    public static InteractiveRuntimeAdapter open(Path workspace,
            InteractiveRuntimeTaskExecutor executor, AgentEventSink sink) throws Exception {
        com.paicli.diagnostic.StageTrace.event(
                "NATIVE_COMPONENT_INIT_START", false,
                "component", "SQLITE_JDBC");
        StateStore openedState;
        try {
            openedState = new StateStore(workspace);
            com.paicli.diagnostic.StageTrace.event(
                    "NATIVE_COMPONENT_INIT_END", false,
                    "component", "SQLITE_JDBC",
                    "outcome", "COMPLETED");
        } catch (Exception | Error failure) {
            com.paicli.diagnostic.StageTrace.event(
                    "NATIVE_COMPONENT_INIT_END", false,
                    "component", "SQLITE_JDBC",
                    "outcome", "ERROR",
                    "error_type", failure.getClass().getName());
            throw failure;
        }
        return new InteractiveRuntimeAdapter(workspace, executor, sink,
                new InteractiveRuntimeView(), openedState);
    }

    /** Current in-process R1 authority for compaction mutation; never persisted or rendered. */
    public java.util.Optional<com.paicli.state.R1aDurableModels.ExecutionAuthoritySnapshot>
            currentCompactionAuthority(){
        RuntimeEventBridge current=bridge;
        FencedExecutionGuard guard=current==null?null:current.executionGuard();
        return guard==null?java.util.Optional.empty():
                java.util.Optional.of(guard.current().snapshot());
    }

    public void contextCompacted(
            com.paicli.context.ContextModels.MaintenanceAuthority authority,
            com.paicli.context.CompactionCoordinator.Outcome outcome){
        RuntimeEventBridge current=bridge;
        if(current==null)throw new IllegalStateException("interactive lifecycle bridge unavailable");
        current.contextCompacted(authority.taskId(),authority.runId(),outcome);
    }

    @Override
    public synchronized void openSession(InteractiveSession openedSession) {
        if (closed.get()) throw new IllegalStateException("runtime adapter is closed");
        if (session != null) throw new IllegalStateException("runtime session already opened");
        if (!openedSession.workspace().equals(workspace)) {
            throw new IllegalArgumentException("interactive workspace identity mismatch");
        }
        session = openedSession;
        AgentEventPublisher openedPublisher = new AgentEventPublisher(
                openedSession.snapshot().sessionId(), Clock.systemUTC());
        AgentEventSink openedLifecycleSink = event -> {
            AgentEventType type = event.metadata().eventType();
            if (type == AgentEventType.TASK_STARTED) {
                InteractiveTaskHandle handle = active.get();
                if (handle != null
                        && handle.taskId().equals(event.metadata().taskId())) {
                    handle.tryAdmit(event.metadata().taskId(), event.metadata().runId());
                }
            } else if (type == AgentEventType.APPROVAL_REQUESTED) {
                promptRefreshListener.get().run();
            } else if (type == AgentEventType.TASK_COMPLETED
                    || type == AgentEventType.TASK_FAILED
                    || type == AgentEventType.TASK_CANCELLED
                    || type == AgentEventType.TASK_INCONCLUSIVE) {
                terminalListener.get().run();
            }
        };
        lifecycleSink = openedLifecycleSink;
        openedPublisher.addSink(openedLifecycleSink);
        openedPublisher.addSink(sink);
        publisher = openedPublisher;
        cancellation = new InteractiveCancellationController();
        approvals = new InteractiveApprovalCoordinator(
                openedSession.snapshot().sessionId(), cancellation, Clock.systemUTC());
        bridge = new RuntimeEventBridge(
                openedPublisher, openedSession, view, stateStore, workspace,
                approvals, cancellation);
        bridge.sessionStarted();
    }

    @Override
    public TaskSubmissionResult submit(InteractiveTaskRequest request) {
        InteractiveSession current = requireOpenSession();
        if (closed.get()) {
            return TaskSubmissionResult.unavailable(
                    "INTERACTIVE_RUNTIME_CLOSED", "Interactive runtime is closed.");
        }
        if (!request.sessionId().equals(current.snapshot().sessionId())
                || !request.workspace().equals(workspace)) {
            return TaskSubmissionResult.rejected(
                    "INTERACTIVE_IDENTITY_MISMATCH", "Interactive task identity does not match.");
        }
        if (active.get() != null) {
            return TaskSubmissionResult.rejected(
                    "INTERACTIVE_TASK_ACTIVE", "Another task is already active.");
        }

        String taskId = CanonicalId.newId(EntityFamily.TASK).value();
        InteractiveTaskHandle handle = new InteractiveTaskHandle(
                taskId, new CompletableFuture<>());
        if (!active.compareAndSet(null, handle)) {
            return TaskSubmissionResult.rejected(
                    "INTERACTIVE_TASK_ACTIVE", "Another task is already active.");
        }

        try {
            current.startTask(taskId);
            com.paicli.diagnostic.StageTrace.event(
                    "FOREGROUND_OWNERSHIP_END", false,
                    "transition", "FOREGROUND_LOCAL_SUBMISSION_OWNER",
                    "task_id", taskId);
            activeFuture = worker.submit(() -> runTask(handle, request));
            return TaskSubmissionResult.preparing();
        } catch (RuntimeException error) {
            active.compareAndSet(handle, null);
            finishSessionTask(taskId, null, "FAILED");
            return TaskSubmissionResult.rejected(
                    "TASK_SUBMISSION_FAILED", "Task submission failed.");
        }
    }

    @Override
    public TaskSubmissionResult recoverTask(String taskId) {
        InteractiveSession current = requireOpenSession();
        if (closed.get()) return TaskSubmissionResult.unavailable(
                "INTERACTIVE_RUNTIME_CLOSED", "Interactive runtime is closed.");
        try {
            CanonicalId parsed = CanonicalId.parse(taskId);
            if (parsed.family() != EntityFamily.TASK) {
                return TaskSubmissionResult.rejected(
                        "TASK_NOT_FOUND", "Task Recovery requires a canonical Task ID.");
            }
        } catch (IllegalArgumentException error) {
            return TaskSubmissionResult.rejected(
                    "TASK_NOT_FOUND", "Task Recovery requires a canonical Task ID.");
        }
        SessionBinding binding = current.snapshot().binding();
        if (binding.state() == SessionBinding.State.UNBOUND) {
            return TaskSubmissionResult.rejected(
                    "NO_BOUND_SESSION", "Bind a Saved Session before Task Recovery.");
        }
        if (!taskId.equals(binding.headTaskId().value())) {
            return TaskSubmissionResult.rejected(
                    "TASK_NOT_FOUND", "Task is not the bound Thread Head.");
        }
        if (active.get() != null) return TaskSubmissionResult.rejected(
                "INTERACTIVE_TASK_ACTIVE", "Another task is already active.");
        InteractiveTaskHandle handle = new InteractiveTaskHandle(taskId,
                new CompletableFuture<>());
        if (!active.compareAndSet(null, handle)) return TaskSubmissionResult.rejected(
                "INTERACTIVE_TASK_ACTIVE", "Another task is already active.");
        try {
            current.startTask(taskId);
            activeFuture = worker.submit(() -> runResume(handle));
            return TaskSubmissionResult.preparing();
        } catch (RuntimeException error) {
            active.compareAndSet(handle, null);
            finishSessionTask(taskId, null, "FAILED");
            return TaskSubmissionResult.rejected(
                    "TASK_RECOVERY_SUBMISSION_FAILED", "Task Recovery submission failed.");
        }
    }

    @Override
    public TaskSubmissionResult admitOrdinaryInput() {
        InteractiveSession current = requireOpenSession();
        SessionBinding binding = current.snapshot().binding();
        if (binding.state() == SessionBinding.State.UNBOUND) {
            return TaskSubmissionResult.accepted("Root Task input admitted.");
        }
        try {
            SavedSessionService.SelectionResult refreshed =
                    new SavedSessionService(stateStore, workspace, Clock.systemUTC())
                            .refresh(current);
            if (refreshed.view() == null) {
                return TaskSubmissionResult.rejected(refreshed.status().name(),
                        refreshed.message());
            }
            return switch (refreshed.view().binding().state()) {
                case BOUND_RECOVERABLE -> TaskSubmissionResult.rejected(
                        "TASK_RECOVERY_REQUIRED",
                        "This Task requires /task recover before ordinary input.");
                case BOUND_ACTIVE -> TaskSubmissionResult.rejected(
                        "OWNERSHIP_CONFLICT",
                        "The bound Head Task has a healthy owner.");
                case BOUND_TERMINAL -> TaskSubmissionResult.rejected(
                        "FOLLOW_UP_INPUT_REQUIRED",
                        "Submit terminal input through the R1E Follow-up route.");
                case BOUND_CONTINUATION_UNAVAILABLE -> TaskSubmissionResult.rejected(
                        "CONTINUATION_UNAVAILABLE",
                        "The bound Head Task cannot be continued; use /new.");
                case BOUND_IDLE -> TaskSubmissionResult.rejected(
                        "BOUND_SESSION_IDLE",
                        "The bound Saved Session does not accept ordinary input.");
                case UNBOUND -> TaskSubmissionResult.accepted(
                        "Root Task input admitted.");
            };
        } catch (Exception error) {
            return TaskSubmissionResult.rejected(
                    "SESSION_BINDING_REFRESH_FAILED",
                    "Saved Session state could not be refreshed.");
        }
    }

    @Override
    public TaskSubmissionResult admitOrdinaryInput(String input){
        InteractiveSession current=requireOpenSession();SessionBinding binding=current.snapshot().binding();
        if(binding.state()==SessionBinding.State.UNBOUND)return TaskSubmissionResult.accepted("Root Task input admitted.");
        try{
            var refreshed=new SavedSessionService(stateStore,workspace,Clock.systemUTC()).refresh(current);
            if(refreshed.view()==null)return TaskSubmissionResult.rejected(refreshed.status().name(),refreshed.message());
            return switch(refreshed.view().binding().state()){
                case BOUND_TERMINAL -> stateStore.followUps.currentEligibleParent(refreshed.view().threadId()).isPresent()
                        ?new TaskSubmissionResult(TaskSubmissionResult.Status.ACCEPTED,"FOLLOW_UP_INPUT","Completed-task input admitted to R1E.")
                        :TaskSubmissionResult.rejected("FOLLOW_UP_NOT_ELIGIBLE","This terminal state is not eligible for Follow-up; use /new or Recovery.");
                case BOUND_RECOVERABLE -> TaskSubmissionResult.rejected("TASK_RECOVERY_REQUIRED","This Task requires /task recover.");
                case BOUND_ACTIVE -> TaskSubmissionResult.rejected("OWNERSHIP_CONFLICT","The bound Head Task has a healthy owner.");
                case BOUND_CONTINUATION_UNAVAILABLE,BOUND_IDLE -> TaskSubmissionResult.rejected("CONTINUATION_UNAVAILABLE","Use /new for an independent Thread.");
                case UNBOUND -> TaskSubmissionResult.accepted("Root Task input admitted.");
            };
        }catch(Exception e){return TaskSubmissionResult.rejected("SESSION_BINDING_REFRESH_FAILED","Saved Session state could not be refreshed.");}
    }

    @Override
    public TaskSubmissionResult submitFollowUp(String input,boolean explicit){
        return TaskSubmissionResult.rejected("FROZEN_HEAD_REQUIRED","Interactive Follow-up requires a frozen Parent/head token.");
    }

    @Override
    public TaskSubmissionResult submitFollowUp(String input,boolean explicit,String expectedHeadToken){
        InteractiveSession current=requireOpenSession();SessionBinding binding=current.snapshot().binding();
        if(active.get()!=null)return TaskSubmissionResult.rejected("INTERACTIVE_TASK_ACTIVE","Active Task input belongs to Steering.");
        if(binding.state()==SessionBinding.State.UNBOUND)return TaskSubmissionResult.rejected("NO_BOUND_SESSION","Follow-up requires a bound terminal Thread.");
        try{
            Instant now=Clock.systemUTC().instant();
            var refreshed=new SavedSessionService(stateStore,workspace,Clock.systemUTC())
                    .refresh(current);
            if(refreshed.view()==null)return TaskSubmissionResult.rejected(
                    refreshed.status().name(),refreshed.message());
            binding=refreshed.view().binding();
            var recoverableAuthority=stateStore.followUps.peekRecoverableAdmittedChild(binding.threadId(),now);
            if(recoverableAuthority.isPresent()){
                var authority=recoverableAuthority.get();
                var executionBinding=executor.prepareAdmittedChildExecution(
                        authority.child().taskId().value(),stateStore,bridge,authority.child());
                requirePreparedBinding(authority.child(),executionBinding);
                var recoverable=followUps().recoverAdmittedChildStart(binding.threadId(),now);
                if(recoverable.isPresent())return handleFollowUpOutcome(
                        recoverable.get(),current,executionBinding);
            }
            if(binding.state()==SessionBinding.State.BOUND_RECOVERABLE){
                var plan=stateStore.planAuthorities.readActive(binding.headTaskId());
                if(plan.status()==com.paicli.state.PlanAuthorityStore.Status.SUCCESS
                        &&plan.plan().revision().canonicalSchemaVersion()
                        ==com.paicli.state.StructuredPlanModels.Revision.CANONICAL_SCHEMA_V2){
                    return TaskSubmissionResult.rejected("PLAN_TOOL_SCOPE_UNAVAILABLE",
                            "PLAN_TOOL_SCOPE_UNAVAILABLE");
                }
                return TaskSubmissionResult.rejected("TASK_RECOVERY_REQUIRED",
                        "This Task requires /task recover before Follow-up.");
            }
            CanonicalId expectedParent=null;long expectedVersion=-1;
            if(expectedHeadToken==null)throw new IllegalArgumentException("Frozen Follow-up head is required");
            String[] token=expectedHeadToken.split("\\|",2);
            if(token.length!=2)throw new IllegalArgumentException("Invalid frozen Follow-up head");
            expectedParent=CanonicalId.parse(token[0]);expectedVersion=Long.parseLong(token[1]);
            FollowUpService.Outcome outcome=followUps().begin(binding.threadId(),input,explicit,
                    "interactive:"+current.snapshot().sessionId(),current.snapshot().sessionId(),Clock.systemUTC().instant(),
                    expectedParent,expectedVersion);
            return handleFollowUpOutcome(outcome,current);
        }catch(TaskRecoveryRejectedException e){return TaskSubmissionResult.rejected(e.code(),bounded(e.getMessage()));}
        catch(ProviderFactory.UnsupportedRuntimeProviderException e){return TaskSubmissionResult.rejected(e.code(),bounded(e.getMessage()));}
        catch(Exception e){return TaskSubmissionResult.rejected("FOLLOW_UP_ADMISSION_FAILED",bounded(e.getMessage()));}
    }

    @Override
    public Optional<String> currentFollowUpHeadToken(){
        InteractiveSession current=session;if(current==null)return Optional.empty();
        SessionBinding binding=current.snapshot().binding();
        if(binding.state()!=SessionBinding.State.BOUND_TERMINAL&&binding.state()!=SessionBinding.State.BOUND_ACTIVE)return Optional.empty();
        try{return stateStore.followUps.currentHeadToken(binding.threadId());}
        catch(Exception ignored){return Optional.empty();}
    }

    @Override
    public Optional<FollowUpConfirmationRequest> pendingFollowUpConfirmation(){
        InteractiveSession current=session;if(current==null||current.snapshot().binding().state()==SessionBinding.State.UNBOUND)return Optional.empty();
        try{
            CanonicalId thread=current.snapshot().binding().threadId();
            Instant now=Clock.systemUTC().instant();
            var recoverableAuthority=stateStore.followUps.peekRecoverableAdmittedChild(thread,now);
            if(recoverableAuthority.isPresent()){
                var authority=recoverableAuthority.get();
                var durableTask=stateStore.tasks.findById(authority.child().taskId().value())
                        .orElseThrow(()->new TaskRecoveryRejectedException(
                                "EXECUTION_BINDING_TASK_MISSING","Durable Child Task is unavailable."));
                var durableKey=new PendingFollowUpAdmissionKey(durableTask.provider(),
                        durableTask.model(),authority.child());
                if(pendingFollowUpBlocked(durableKey))return Optional.empty();
                InteractiveRuntimeTaskExecutor.AdmittedChildExecutionBinding binding;
                try{
                    var key=durableKey;
                    ProviderFactory.requirePublicRuntimeProvider(durableKey.provider());
                    var cached=preparedPendingChild.get();
                    binding=cached!=null&&cached.key().equals(key)?cached.binding():
                            executor.prepareAdmittedChildExecution(authority.child().taskId().value(),
                                    stateStore,bridge,authority.child());
                    requirePreparedBinding(durableKey,binding);
                    preparedPendingChild.set(new PreparedPendingChild(key,binding));
                    if(!admitPendingFollowUp(durableKey,binding))return Optional.empty();
                }catch(ProviderFactory.UnsupportedRuntimeProviderException error){
                    blockPendingFollowUp(durableKey);throw error;
                }catch(TaskRecoveryRejectedException error){
                    blockPendingFollowUp(durableKey);throw error;
                }
                var recoverable=followUps().recoverAdmittedChildStart(thread,now);
                if(recoverable.isPresent())handleFollowUpOutcome(recoverable.get(),current,binding);
                return Optional.empty();
            }
            var pending=stateStore.followUps.peekPending(thread);
            if(pending.isPresent()&&(pending.get().state()==FollowUpModels.CandidateState.PREPARING
                    ||pending.get().state()==FollowUpModels.CandidateState.READY_TO_ADMIT)){
                var candidate=pending.get();
                ProviderFactory.requirePublicRuntimeProvider(current.snapshot().provider());
                var resumed=followUps().resume(thread,now);
                if(resumed.child()!=null){handleFollowUpOutcome(resumed,current);return Optional.empty();}
                pending=Optional.of(resumed.candidate());
            }
            if(pending.isEmpty())return Optional.empty();
            var candidate=pending.get();
            String kind=candidate.state()==FollowUpModels.CandidateState.NEEDS_INTENT_CONFIRMATION?"INTENT"
                    :candidate.state()==FollowUpModels.CandidateState.NEEDS_RISK_CONFIRMATION?"RISK":null;
            if(kind==null)return Optional.empty();
            blockedPendingFollowUpAdmission.set(null);
            String preview="INTENT".equals(kind)?"Choose whether this input continues the current Thread or starts a new Thread."
                    :stateStore.followUps.manifestDiffPreview(candidate.capsuleId());
            return Optional.of(new FollowUpConfirmationRequest(candidate.capsuleId().value(),kind,candidate.version(),
                    candidate.parentTaskId().value(),preview));
        }catch(ProviderFactory.UnsupportedRuntimeProviderException error){throw error;}
        catch(TaskRecoveryRejectedException error){throw error;}
        catch(Exception e){return Optional.empty();}
    }

    private boolean admitPendingFollowUp(PendingFollowUpAdmissionKey key,
            InteractiveRuntimeTaskExecutor.AdmittedChildExecutionBinding binding){
        if(key.equals(blockedPendingFollowUpAdmission.get()))return false;
        try{
            ProviderFactory.requirePublicRuntimeProvider(key.provider());
            blockedPendingFollowUpAdmission.set(null);
            return true;
        }catch(ProviderFactory.UnsupportedRuntimeProviderException error){
            blockedPendingFollowUpAdmission.set(key);
            throw error;
        }
    }

    private boolean pendingFollowUpBlocked(PendingFollowUpAdmissionKey key){
        return key.equals(blockedPendingFollowUpAdmission.get());
    }

    private void blockPendingFollowUp(PendingFollowUpAdmissionKey key){
        blockedPendingFollowUpAdmission.set(key);
    }

    private static void requirePreparedBinding(FollowUpModels.ChildAuthority child,
            InteractiveRuntimeTaskExecutor.AdmittedChildExecutionBinding binding){
        if(binding==null||!child.equals(binding.child()))throw new TaskRecoveryRejectedException(
                "EXECUTION_BINDING_IDENTITY_MISMATCH","Prepared Child execution authority is inconsistent.");
    }

    private static void requirePreparedBinding(PendingFollowUpAdmissionKey key,
            InteractiveRuntimeTaskExecutor.AdmittedChildExecutionBinding binding){
        requirePreparedBinding(key.child(),binding);
        if(!java.util.Objects.equals(key.provider(),binding.provider())
                ||!java.util.Objects.equals(key.model(),binding.model()))
            throw new TaskRecoveryRejectedException("EXECUTION_BINDING_DURABLE_CONFIG_MISMATCH",
                    "Prepared Child provider/model does not match the durable Task.");
    }

    private record PendingFollowUpAdmissionKey(String provider,String model,
            FollowUpModels.ChildAuthority child){ }
    private record PreparedPendingChild(PendingFollowUpAdmissionKey key,
            InteractiveRuntimeTaskExecutor.AdmittedChildExecutionBinding binding){ }

    @Override
    public TaskSubmissionResult respondFollowUpConfirmation(String capsuleId,String kind,long version,String input){
        InteractiveSession current=requireOpenSession();String value=input==null?"":input.strip().toLowerCase(java.util.Locale.ROOT);
        try{
            FollowUpModels.ConfirmationKind confirmationKind=FollowUpModels.ConfirmationKind.valueOf(kind);
            FollowUpModels.ConfirmationDecision decision;
            if(confirmationKind==FollowUpModels.ConfirmationKind.INTENT)decision=switch(value){
                case "c","continue"->FollowUpModels.ConfirmationDecision.CONTINUE_CURRENT_THREAD;
                case "n","new"->FollowUpModels.ConfirmationDecision.CREATE_NEW_THREAD;
                case "x","cancel"->FollowUpModels.ConfirmationDecision.CANCEL;default->null;};
            else decision=switch(value){case "y","yes"->FollowUpModels.ConfirmationDecision.ACCEPT_RISK;
                case "x","cancel","n","no"->FollowUpModels.ConfirmationDecision.CANCEL;default->null;};
            if(decision==null)return TaskSubmissionResult.rejected("FOLLOW_UP_CONFIRMATION_AMBIGUOUS","Use one of the choices shown by the frozen confirmation prompt.");
            CanonicalId id=CanonicalId.parse(capsuleId);var candidate=stateStore.followUps.findCandidate(id).orElseThrow();
            if(decision==FollowUpModels.ConfirmationDecision.CREATE_NEW_THREAD){
                stateStore.followUps.confirm(id,confirmationKind,version,"interactive:"+current.snapshot().sessionId(),
                        current.snapshot().sessionId(),decision,Clock.systemUTC().instant());
                current.clearThreadBinding(Clock.systemUTC().instant());
                return submit(new com.paicli.interactive.task.InteractiveTaskRequest(current.snapshot().sessionId(),workspace,
                        candidate.instruction(),java.util.List.of()));
            }
            var outcome=followUps().confirm(id,confirmationKind,version,"interactive:"+current.snapshot().sessionId(),
                    current.snapshot().sessionId(),decision,Clock.systemUTC().instant());
            return handleFollowUpOutcome(outcome,current);
        }catch(Exception e){return TaskSubmissionResult.rejected("FOLLOW_UP_CONFIRMATION_FAILED",bounded(e.getMessage()));}
    }

    private FollowUpService followUps() throws java.io.IOException{
        var config=new ConfigLoader(workspace).load();
        config=com.paicli.interactive.verification.InteractiveVerificationPolicy.apply(config,workspace,bridge.verificationMode())
                .withApprovalMode(bridge.approvalMode());
        var manifest=new RuntimeManifestHasher(workspace).compute(config);
        return new FollowUpService(stateStore,new InstructionManifestRuntime(stateStore,workspace),config,manifest,
                config.maxProjectInstructionChars());
    }

    private TaskSubmissionResult handleFollowUpOutcome(FollowUpService.Outcome outcome,InteractiveSession current){
        return handleFollowUpOutcome(outcome,current,null);
    }

    private TaskSubmissionResult handleFollowUpOutcome(FollowUpService.Outcome outcome,InteractiveSession current,
            InteractiveRuntimeTaskExecutor.AdmittedChildExecutionBinding preparedBinding){
        if("CREATE_NEW_THREAD".equals(outcome.code())){
            current.clearThreadBinding(Clock.systemUTC().instant());
            return submit(new com.paicli.interactive.task.InteractiveTaskRequest(current.snapshot().sessionId(),workspace,
                    outcome.candidate().instruction(),java.util.List.of()));
        }
        if(outcome.child()==null){promptRefreshListener.get().run();return TaskSubmissionResult.accepted(
                "Follow-up "+outcome.candidate().capsuleId().shortDisplay()+" is "+outcome.code()+".");}
        String taskId=outcome.child().taskId().value();
        InteractiveRuntimeTaskExecutor.AdmittedChildExecutionBinding executionBinding;
        try{
            executionBinding=preparedBinding==null?executor.prepareAdmittedChildExecution(taskId,
                    stateStore,bridge,outcome.child()):preparedBinding;
            if(!executionBinding.child().equals(outcome.child()))throw new TaskRecoveryRejectedException(
                    "EXECUTION_BINDING_IDENTITY_MISMATCH","Prepared Child execution authority is inconsistent.");
        }catch(TaskRecoveryRejectedException rejected){
            return TaskSubmissionResult.rejected(rejected.code(),bounded(rejected.getMessage()));
        }catch(ProviderFactory.UnsupportedRuntimeProviderException rejected){
            return TaskSubmissionResult.rejected(rejected.code(),bounded(rejected.getMessage()));
        }catch(Exception error){
            return TaskSubmissionResult.rejected("EXECUTION_BINDING_PREPARATION_FAILED",
                    "Child execution binding could not be prepared.");
        }
        InteractiveTaskHandle handle=new InteractiveTaskHandle(taskId,new CompletableFuture<>());
        if(!active.compareAndSet(null,handle))return TaskSubmissionResult.rejected("INTERACTIVE_TASK_ACTIVE","Another Task became active.");
        FollowUpModels.ChildStartClaim startClaim=null;
        try{
            var config=new ConfigLoader(workspace).load();
            long leaseSeconds=Math.multiplyExact((long)config.commandTimeoutSeconds(),(long)config.effectiveMaxIterations());
            Instant claimedAt=Clock.systemUTC().instant();
            var claimed=stateStore.followUps.claimInitialChildStart(outcome.child(),
                    "child-initial-"+java.util.UUID.randomUUID(),java.util.UUID.randomUUID().toString(),
                    claimedAt,claimedAt.plusSeconds(leaseSeconds),java.time.Duration.ZERO);
            if(claimed.status()!=FollowUpModels.ChildStartStatus.CLAIMED){
                active.compareAndSet(handle,null);
                if(claimed.status()==FollowUpModels.ChildStartStatus.ALREADY_CLAIMED
                        ||claimed.status()==FollowUpModels.ChildStartStatus.ALREADY_STARTED
                        ||claimed.status()==FollowUpModels.ChildStartStatus.TERMINAL)
                    return TaskSubmissionResult.accepted("Existing Child Task is "+outcome.child().taskId().shortDisplay()+".");
                return TaskSubmissionResult.rejected("CHILD_AUTHORITY_INTEGRITY_FAILURE","Child Initial Run start authority was rejected.");
            }
            startClaim=claimed.claim();
            var claimedBinding=executionBinding.bindClaim(claimed.claim());
            var refreshed=new SavedSessionService(stateStore,workspace,Clock.systemUTC()).refresh(current);
            if(refreshed.view()!=null)current.bindThread(refreshed.view().binding());
            current.startTask(taskId);activeFuture=worker.submit(()->runChild(handle,
                    outcome.candidate().instruction(),claimed.claim(),claimedBinding));
            return TaskSubmissionResult.preparing();
        }catch(java.sql.SQLException|java.io.IOException|RuntimeException e){
            if(startClaim!=null)try{stateStore.followUps.releaseInitialChildStartClaim(
                    startClaim,"WORKER_SUBMISSION_FAILED",Clock.systemUTC().instant());}catch(Exception ignored){ }
            current.finishTask(taskId);
            active.compareAndSet(handle,null);
            return TaskSubmissionResult.rejected("CHILD_EXECUTION_START_FAILED","Child authority is admitted; execution can be recovered.");
        }
    }

    public Optional<InteractiveTaskHandle> activeHandle() {
        return Optional.ofNullable(active.get());
    }

    @Override
    public Optional<com.paicli.interactive.task.ForegroundTask> activeForegroundTask() {
        return activeHandle().map(InteractiveTaskHandle::foreground);
    }

    public Optional<InteractiveTaskResult> lastResult() {
        return Optional.ofNullable(last.get());
    }

    public InteractiveRuntimeView view() {
        return view;
    }

    @Override public String currentPhase() {
        String phase = view.snapshot().phase();
        return phase == null || phase.isBlank() || "IDLE".equals(phase)
                ? "STARTING" : phase;
    }

    @Override public void onTerminal(Runnable listener) {
        terminalListener.set(listener == null ? () -> { } : listener);
    }

    @Override public void onPromptRefresh(Runnable listener) {
        promptRefreshListener.set(listener == null ? () -> { } : listener);
    }

    @Override
    public void requestCancellation() {
        InteractiveTaskHandle handle = active.get();
        if (handle == null) return;
        InteractiveSession current = session;
        if (current != null && current.snapshot().currentTaskId() != null) {
            current.requestCancellation();
        }

        InteractiveCancellationController controller = cancellation;
        InteractiveCancellationController.RequestResult cancellationResult =
                controller == null
                        ? new InteractiveCancellationController.RequestResult(
                                InteractiveCancellationController.Stage.COOPERATIVE, 0)
                        : controller.request(stage -> {
                            RuntimeEventBridge currentBridge = bridge;
                            if (currentBridge != null) {
                                currentBridge.cancellationRequested(
                                        stage == InteractiveCancellationController.Stage.FORCED, 0);
                            }
                        });
        Future<?> future = activeFuture;
        if (future != null) future.cancel(true);
        InteractiveTaskResult terminal = new InteractiveTaskResult(
                handle.taskId(), null, TaskSubmissionResult.Status.CANCELLED,
                "CANCELLED", "Task cancelled before execution.");
        if (handle.cancelBeforeStart(terminal)) {
            last.set(terminal);
            publishFallbackTerminal(handle, terminal);
            active.compareAndSet(handle, null);
            if (controller != null) controller.resetAfterTerminal();
        }
    }

    @Override
    public Optional<InteractiveApprovalRequest> pendingApproval() {
        InteractiveApprovalCoordinator value = approvals;
        return value == null ? Optional.empty() : value.pendingRequest();
    }

    @Override
    public ApprovalInteractionResult respondApproval(String requestId, String input) {
        InteractiveApprovalCoordinator value = approvals;
        ApprovalInteractionResult result = value == null
                ? new ApprovalInteractionResult(false, false,
                        java.util.List.of("No approval request is active."))
                : value.respond(requestId, input);
        return result;
    }

    @Override
    public TaskSubmissionResult submitSteering(String input,boolean explicit){
        InteractiveSession current=requireOpenSession();
        String taskId=current.snapshot().currentTaskId();
        String runId=view.snapshot().activeRunId();
        if(taskId==null||runId==null)return TaskSubmissionResult.rejected(
                "STEERING_TARGET_UNAVAILABLE","The active durable Task/Run target is not ready.");
        try{
            var target=stateStore.steerings.captureTarget(CanonicalId.parse(taskId),CanonicalId.parse(runId));
            if(target.status()!=SteeringStore.Status.SUCCESS)return TaskSubmissionResult.rejected(
                    "STEERING_TARGET_CONFLICT","Steering target authority is unavailable: "+target.status());
            var classified=new SteeringClassifier().classify(input);
            String sessionId=current.snapshot().sessionId();
            String pendingApproval=approvals==null?null:approvals.pendingRequest()
                    .map(InteractiveApprovalRequest::requestId).orElse(null);
            var result=stateStore.steerings.admit(target.target(),classified,
                    explicit?SteeringModels.Provenance.INTERACTIVE_EXPLICIT
                            :SteeringModels.Provenance.INTERACTIVE_ACTIVE_TEXT,
                    "interactive:"+sessionId,sessionId,pendingApproval,Clock.systemUTC().instant());
            if(result.status()==SteeringStore.Status.NEEDS_CONFIRMATION){
                promptRefreshListener.get().run();
                return TaskSubmissionResult.accepted("Steering recorded; owner confirmation is required before queueing.");
            }
            if(result.status()==SteeringStore.Status.SUCCESS){
                InteractiveApprovalCoordinator coordinator=approvals;
                if(coordinator!=null)coordinator.invalidatePendingForSteering();
                return TaskSubmissionResult.accepted(
                    "Steering queued as "+result.steeringId().shortDisplay()+".");
            }
            return TaskSubmissionResult.rejected("STEERING_REJECTED","Steering rejected: "+result.status());
        }catch(RuntimeException error){
            return TaskSubmissionResult.rejected("STEERING_ADMISSION_FAILED","Steering admission failed closed.");
        }
    }

    @Override
    public TaskSubmissionResult steeringStatus(){return steeringSummary(false);}

    @Override
    public TaskSubmissionResult steeringHistory(){return steeringSummary(true);}

    private TaskSubmissionResult steeringSummary(boolean history){
        InteractiveSession current=requireOpenSession();String taskId=current.snapshot().currentTaskId();
        if(taskId==null)return TaskSubmissionResult.rejected("STEERING_TARGET_UNAVAILABLE","No active Task owns Steering state.");
        try{
            var rows=stateStore.steerings.list(CanonicalId.parse(taskId),history?10:1);
            if(rows.isEmpty())return TaskSubmissionResult.accepted("No Steering facts exist for the active Task.");
            String summary=rows.stream().map(row->row.steeringId().shortDisplay()+"="+row.state().name()
                    +(row.sequence()==null?"":"#"+row.sequence()))
                    .collect(java.util.stream.Collectors.joining(", "));
            return TaskSubmissionResult.accepted((history?"Steering history: ":"Latest Steering: ")+summary);
        }catch(RuntimeException error){return TaskSubmissionResult.rejected(
                "STEERING_STATUS_FAILED","Steering status failed closed.");}
    }

    @Override
    public TaskSubmissionResult cancelSteering(String steeringId){
        InteractiveSession current=requireOpenSession();String taskId=current.snapshot().currentTaskId();
        try{
            CanonicalId id=CanonicalId.parse(steeringId);var queue=stateStore.steerings.readQueue(id).orElse(null);
            if(queue==null||taskId==null||!queue.taskId().value().equals(taskId))
                return TaskSubmissionResult.rejected("STEERING_NOT_FOUND","Steering is not in the active Task scope.");
            String sessionId=current.snapshot().sessionId();var result=stateStore.steerings.cancel(id,
                    "interactive:"+sessionId,sessionId,queue.version(),Clock.systemUTC().instant());
            return result.status()==SteeringStore.Status.SUCCESS
                    ?TaskSubmissionResult.accepted("Steering cancelled.")
                    :TaskSubmissionResult.rejected("STEERING_CANCEL_REJECTED","Steering cancellation rejected: "+result.status());
        }catch(Exception error){return TaskSubmissionResult.rejected(
                "STEERING_CANCEL_FAILED","Steering cancellation failed closed.");}
    }

    @Override
    public Optional<SteeringConfirmationRequest> pendingSteeringConfirmation(){
        InteractiveSession current=session;if(current==null)return Optional.empty();
        String taskId=current.snapshot().currentTaskId();if(taskId==null)return Optional.empty();
        try{
            var queue=stateStore.steerings.oldestOpen(CanonicalId.parse(taskId));
            if(queue.isEmpty())return Optional.empty();
            boolean unknown=queue.get().state()==SteeringModels.State.NEEDS_CONFIRMATION;
            String activeRun=view.snapshot().activeRunId();
            boolean rebind=activeRun!=null&&!queue.get().target().runId().value().equals(activeRun)
                    &&(queue.get().state()==SteeringModels.State.QUEUED
                       ||queue.get().state()==SteeringModels.State.DEFERRED);
            if(!unknown&&!rebind)return Optional.empty();
            return Optional.of(new SteeringConfirmationRequest(queue.get().steeringId().value(),taskId,
                    rebind?"REBIND_REQUIRED":queue.get().reasonCode()));
        }catch(Exception error){return Optional.empty();}
    }

    @Override
    public TaskSubmissionResult respondSteeringConfirmation(String steeringId,String input){
        InteractiveSession current=requireOpenSession();String normalized=input==null?"":input.strip().toLowerCase(java.util.Locale.ROOT);
        SteeringModels.ConfirmationDecision decision=switch(normalized){
            case "n","non-material","non_material"->SteeringModels.ConfirmationDecision.CONFIRM_NON_MATERIAL;
            case "m","material"->SteeringModels.ConfirmationDecision.CONFIRM_MATERIAL;
            case "r","reject"->SteeringModels.ConfirmationDecision.REJECT;
            case "c","cancel"->SteeringModels.ConfirmationDecision.CANCEL;
            default->null;
        };
        if(decision==null)return TaskSubmissionResult.rejected("STEERING_CONFIRMATION_AMBIGUOUS",
                "Use n, m, r, or c; UNKNOWN Steering is never accepted implicitly.");
        try{
            CanonicalId id=CanonicalId.parse(steeringId);var queue=stateStore.steerings.readQueue(id).orElse(null);
            if(queue==null)return TaskSubmissionResult.rejected("STEERING_NOT_FOUND","Steering no longer exists.");
            String sessionId=current.snapshot().sessionId();
            SteeringStore.Mutation result;
            String activeRun=view.snapshot().activeRunId();
            if(activeRun!=null&&!queue.target().runId().value().equals(activeRun)
                    &&(queue.state()==SteeringModels.State.QUEUED||queue.state()==SteeringModels.State.DEFERRED)){
                if(decision==SteeringModels.ConfirmationDecision.REJECT
                        ||decision==SteeringModels.ConfirmationDecision.CANCEL)
                    result=stateStore.steerings.cancel(id,"interactive:"+sessionId,sessionId,
                            queue.version(),Clock.systemUTC().instant());
                else{
                    var target=stateStore.steerings.captureTarget(CanonicalId.parse(queue.taskId().value()),
                            CanonicalId.parse(activeRun));
                    if(target.status()!=SteeringStore.Status.SUCCESS)return TaskSubmissionResult.rejected(
                            "STEERING_REBIND_CONFLICT","Current Resume Run cannot own this Steering.");
                    result=stateStore.steerings.retarget(id,target.target(),"interactive:"+sessionId,
                            sessionId,queue.version(),Clock.systemUTC().instant());
                }
            }else result=stateStore.steerings.confirm(id,decision,"interactive:"+sessionId,sessionId,
                    queue.version(),Clock.systemUTC().instant());
            if(result.status()==SteeringStore.Status.SUCCESS){
                InteractiveApprovalCoordinator coordinator=approvals;
                if(coordinator!=null)coordinator.invalidatePendingForSteering();
                return TaskSubmissionResult.accepted("Steering confirmation committed.");
            }
            return TaskSubmissionResult.rejected("STEERING_CONFIRMATION_REJECTED",
                    "Steering confirmation rejected: "+result.status());
        }catch(Exception error){return TaskSubmissionResult.rejected(
                "STEERING_CONFIRMATION_FAILED","Steering confirmation failed closed.");}
    }
    public boolean awaitIdle(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        long deadline = System.nanoTime() + timeout.toNanos();
        while (active.get() != null && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return active.get() == null;
    }

    @Override
    public void closeSession() {
        close();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        terminalListener.set(() -> { });
        promptRefreshListener.set(() -> { });
        requestCancellation();
        if (!awaitIdle(CLOSE_WAIT)) {
            requestCancellation();
            awaitIdle(CLOSE_WAIT);
        }
        worker.shutdownNow();
        boolean terminated = false;
        try {
            terminated = worker.awaitTermination(
                    CLOSE_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        RuntimeEventBridge currentBridge = bridge;
        if (currentBridge != null) {
            currentBridge.sessionEnded("interactive session closed");
        }
        AgentEventPublisher currentPublisher = publisher;
        if (currentPublisher != null) {
            currentPublisher.removeSink(sink);
            AgentEventSink currentLifecycleSink = lifecycleSink;
            if (currentLifecycleSink != null) {
                currentPublisher.removeSink(currentLifecycleSink);
            }
            currentPublisher.lastSequence();
        }
        try {
            mcp.close();
            stateStore.close();
        } catch (Exception ignored) {
            // Session close remains bounded; task terminal state already owns persistence errors.
        }
        if (!terminated) {
            throw new IllegalStateException(
                    "interactive task worker did not terminate before terminal shutdown");
        }
    }

    private void runTask(InteractiveTaskHandle handle, InteractiveTaskRequest request) {
        if (!handle.tryStart()) return;
        String taskId = handle.taskId();
        com.paicli.diagnostic.StageTrace.event(
                "TASK_PREPARATION_START", true,
                "task_id", taskId,
                "workspace", com.paicli.diagnostic.StageTrace.canonicalPath(workspace));
        try {
            CodingTaskResult result = executor.execute(
                    taskId, request, stateStore, bridge);
            if (cancellation != null && cancellation.cancellationRequested()) {
                throw new CancellationToken.TaskCancellationException(
                        cancellation.forceRequested());
            }
            InteractiveTaskOutcome outcome = InteractiveTaskOutcome.classify(result, stateStore);
            if (TaskStore.STATUS_DONE.equals(result.finalStatus())) {
                if(result.executionGuard()!=null)
                    result.executionGuard().refineInteractiveTerminal(outcome.persistedStatus());
                else stateStore.tasks.markInteractiveTerminal(taskId, outcome.persistedStatus());
                bridge.taskCompleted(result, outcome);
            } else if (TaskStore.STATUS_RUNNING.equals(result.finalStatus())) {
                publishInconclusiveTerminal(result, outcome);
            }
            bindCreatedRootThread(taskId);
            completeTerminal(handle, new InteractiveTaskResult(
                    taskId, result.runId(), outcome.resultStatus(), outcome.code(),
                    bounded(outcome.message())), false);
        } catch (CancellationToken.TaskCancellationException error) {
            String runId = view.snapshot().activeRunId();
            boolean unknown = error.forced();
            boolean persisted = persistCancellation(taskId, runId, unknown);
            InteractiveTaskResult terminal = cancellationTerminal(
                    taskId, runId, unknown, persisted);
            completeTerminal(handle, terminal, true);
        } catch (Throwable error) {
            String runId = view.snapshot().activeRunId();
            if (Thread.currentThread().isInterrupted()
                    || cancellation != null && cancellation.cancellationRequested()) {
                boolean unknown = cancellation != null && cancellation.forceRequested();
                boolean persisted = persistCancellation(taskId, runId, unknown);
                completeTerminal(handle, cancellationTerminal(
                        taskId, runId, unknown, persisted), true);
                return;
            }
            InteractiveTaskResult terminal = failureTerminal(
                    taskId, runId, failureCode(error.getMessage()), error);
            completeTerminal(handle, terminal, true);
        } finally {
            bridge.clearExecutionGuard();
            activeFuture = null;
            active.compareAndSet(handle, null);
        }
    }

    private void runChild(InteractiveTaskHandle handle,String goal,FollowUpModels.ChildStartClaim claim,
            InteractiveRuntimeTaskExecutor.AdmittedChildExecutionBinding binding){
        if(!handle.tryStart())return;String taskId=handle.taskId();
        try{
            if(!stateStore.followUps.markInitialChildStarted(claim,Clock.systemUTC().instant()))
                throw new IllegalStateException("Child Initial Run launch authority is stale");
            CodingTaskResult result=executor.executeAdmittedChild(taskId,goal,stateStore,bridge,claim,binding);
            if(cancellation!=null&&cancellation.cancellationRequested())
                throw new CancellationToken.TaskCancellationException(cancellation.forceRequested());
            InteractiveTaskOutcome outcome=InteractiveTaskOutcome.classify(result,stateStore);
            if(TaskStore.STATUS_DONE.equals(result.finalStatus())){
                if(result.executionGuard()!=null)result.executionGuard().refineInteractiveTerminal(outcome.persistedStatus());
                else stateStore.tasks.markInteractiveTerminal(taskId,outcome.persistedStatus());
                bridge.taskCompleted(result,outcome);
            }else if(TaskStore.STATUS_RUNNING.equals(result.finalStatus())){
                publishInconclusiveTerminal(result,outcome);
            }
            completeTerminal(handle,new InteractiveTaskResult(taskId,result.runId(),outcome.resultStatus(),
                    "FOLLOW_UP_"+outcome.code(),bounded(outcome.message())),false);
        }catch(CancellationToken.TaskCancellationException error){
            String runId=view.snapshot().activeRunId();boolean persisted=persistCancellation(taskId,runId,error.forced());
            completeTerminal(handle,cancellationTerminal(taskId,runId,error.forced(),persisted),true);
        }catch(Throwable error){
            String runId=view.snapshot().activeRunId();
            if(Thread.currentThread().isInterrupted()||cancellation!=null&&cancellation.cancellationRequested()){
                boolean unknown=cancellation!=null&&cancellation.forceRequested();boolean persisted=persistCancellation(taskId,runId,unknown);
                completeTerminal(handle,cancellationTerminal(taskId,runId,unknown,persisted),true);
            }else completeTerminal(handle,failureTerminal(taskId,runId,
                    "FOLLOW_UP_CHILD_EXECUTION_FAILED",error),true);
        }finally{bridge.clearExecutionGuard();activeFuture=null;active.compareAndSet(handle,null);}
    }

    private void runResume(InteractiveTaskHandle handle) {
        if (!handle.tryStart()) return;
        String taskId = handle.taskId();
        try {
            CodingTaskResult result = executor.resume(taskId, stateStore, bridge);
            if (cancellation != null && cancellation.cancellationRequested()) {
                throw new CancellationToken.TaskCancellationException(
                        cancellation.forceRequested());
            }
            InteractiveTaskOutcome outcome = InteractiveTaskOutcome.classify(result, stateStore);
            if (TaskStore.STATUS_DONE.equals(result.finalStatus())) {
                if(result.executionGuard()!=null)
                    result.executionGuard().refineInteractiveTerminal(outcome.persistedStatus());
                else stateStore.tasks.markInteractiveTerminal(taskId,outcome.persistedStatus());
                bridge.taskCompleted(result, outcome);
            } else if (TaskStore.STATUS_RUNNING.equals(result.finalStatus())) {
                publishInconclusiveTerminal(result, outcome);
            }
            completeTerminal(handle, new InteractiveTaskResult(taskId, result.runId(),
                    outcome.resultStatus(), "RESUME_" + outcome.code(),
                    bounded(outcome.message())), false);
        } catch (CancellationToken.TaskCancellationException error) {
            String runId = view.snapshot().activeRunId();
            boolean persisted = persistCancellation(taskId, runId, error.forced());
            completeTerminal(handle, cancellationTerminal(
                    taskId, runId, error.forced(), persisted), true);
        } catch (Throwable error) {
            String runId = view.snapshot().activeRunId();
            if (Thread.currentThread().isInterrupted()
                    || cancellation != null && cancellation.cancellationRequested()) {
                boolean unknown = cancellation != null && cancellation.forceRequested();
                boolean persisted = persistCancellation(taskId, runId, unknown);
                completeTerminal(handle, cancellationTerminal(
                        taskId, runId, unknown, persisted), true);
            } else {
                String code = error instanceof TaskRecoveryRejectedException rejected
                        ? rejected.code() : "TASK_RECOVERY_FAILED";
                completeTerminal(handle, failureTerminal(
                        taskId, runId, code, error), true);
            }
        } finally {
            bridge.clearExecutionGuard();
            activeFuture = null;
            active.compareAndSet(handle, null);
        }
    }

    private void completeTerminal(InteractiveTaskHandle handle,
            InteractiveTaskResult terminal, boolean publishFallback) {
        if (!handle.tryComplete(terminal)) return;
        last.set(terminal);
        if (publishFallback) publishFallbackTerminal(handle, terminal);
        active.compareAndSet(handle, null);
        InteractiveCancellationController controller = cancellation;
        if (controller != null) controller.resetAfterTerminal();
    }

    private void publishInconclusiveTerminal(CodingTaskResult result,
            InteractiveTaskOutcome outcome) {
        finishSessionTask(result.taskId(), result.runId(), outcome.code());
        String summary = outcome.message() == null || outcome.message().isBlank()
                ? null : bounded(outcome.message());
        publisher.publish(AgentEventType.TASK_INCONCLUSIVE,
                result.taskId(), result.runId(),
                metadata -> new AgentEvents.TaskInconclusive(metadata,
                        outcome.code(), summary));
    }

    private void publishFallbackTerminal(InteractiveTaskHandle handle,
            InteractiveTaskResult terminal) {
        if (!taskIsStillActive(handle.taskId())) return;
        finishSessionTask(handle.taskId(), terminal.runId(), terminal.status().name());
        if (terminal.status() == TaskSubmissionResult.Status.CANCELLED) {
            publisher.publish(AgentEventType.TASK_CANCELLED,
                    handle.taskId(), terminal.runId(),
                    metadata -> new AgentEvents.TaskCancelled(
                            metadata, cancellation != null && cancellation.forceRequested()
                                    ? "FORCED_CANCELLATION_OUTCOME_UNKNOWN" : "CANCELLED",
                            cancellation != null && cancellation.forceRequested()));
        } else {
            publisher.publish(AgentEventType.TASK_FAILED,
                    handle.taskId(), terminal.runId(),
                    metadata -> new AgentEvents.TaskFailed(metadata,
                            terminal.code(), terminal.boundedMessage()));
        }
    }

    private boolean persistCancellation(String taskId, String runId,
            boolean outcomeUnknown) {
        try {
            var guard=bridge.executionGuard();
            if(guard!=null){
                var plan=runId==null?null:stateStore.plans.findByRun(runId).orElse(null);
                guard.cancel(plan==null?null:plan.planId(),outcomeUnknown,
                        "Cancellation reason: user requested");
                return true;
            }
            if (runId != null) {
                var plan = stateStore.plans.findByRun(runId).orElse(null);
                if (plan != null && com.paicli.state.TaskPlanStore.STATUS_ACTIVE.equals(plan.status())) {
                    stateStore.plans.markCompleted(plan.planId());
                }
                var run = stateStore.codingRuns.findById(runId).orElse(null);
                if (run != null && !java.util.Set.of(
                        com.paicli.state.CodingRunStore.PHASE_COMPLETED,
                        com.paicli.state.CodingRunStore.PHASE_FAILED,
                        com.paicli.state.CodingRunStore.PHASE_CANCELLED).contains(run.phase())) {
                    stateStore.codingRuns.markCancelled(runId, outcomeUnknown);
                }
            }
            if (stateStore.tasks.findById(taskId).isPresent()) {
                stateStore.tasks.markCancelled(taskId, outcomeUnknown,
                        "Cancellation reason: user requested");
            }
            return true;
        } catch (com.paicli.runtime.FencedExecutionGuard.AuthorityLostException lost) {
            return false;
        } catch (Exception persistenceFailure) {
            throw new IllegalStateException("cancellation terminal persistence failed",
                    persistenceFailure);
        }
    }

    /**
     * Closes an admitted failure before foreground ownership is released. The
     * fenced mutation remains the authority whenever durable ownership exists;
     * the direct stores are only the legacy pre-authority compatibility path.
     */
    private InteractiveTaskResult failureTerminal(String taskId, String runId,
            String code, Throwable error) {
        String message = bounded(error == null ? null : error.getMessage());
        try {
            persistFailure(taskId, runId, message);
            return new InteractiveTaskResult(taskId, runId,
                    TaskSubmissionResult.Status.FAILED, code, message);
        } catch (FencedExecutionGuard.AuthorityLostException lost) {
            return new InteractiveTaskResult(taskId, runId,
                    TaskSubmissionResult.Status.FAILED,
                    "EXECUTION_AUTHORITY_LOST",
                    "Task failure could not be committed because execution authority changed.");
        } catch (Exception persistenceFailure) {
            return new InteractiveTaskResult(taskId, runId,
                    TaskSubmissionResult.Status.FAILED,
                    "TERMINAL_PERSISTENCE_FAILED",
                    "Task failure terminalization did not converge.");
        }
    }

    private void persistFailure(String taskId, String runId, String message)
            throws Exception {
        var task = stateStore.tasks.findById(taskId).orElse(null);
        var run = runId == null ? null : stateStore.codingRuns.findById(runId).orElse(null);
        boolean taskTerminal = task != null && TaskStore.isTerminal(task.status());
        boolean runTerminal = run == null || java.util.Set.of(
                com.paicli.state.CodingRunStore.PHASE_COMPLETED,
                com.paicli.state.CodingRunStore.PHASE_FAILED)
                .contains(run.phase());
        if (taskTerminal && runTerminal) return;

        FencedExecutionGuard guard = bridge.executionGuard();
        if (guard != null) {
            String planId = runId == null ? null : stateStore.plans.findByRun(runId)
                    .map(com.paicli.state.TaskPlanStore.TaskPlanRecord::planId)
                    .orElse(null);
            guard.terminalize(planId, false, message);
            return;
        }
        if (run != null && !runTerminal) {
            stateStore.codingRuns.markFailed(runId, message);
        }
        if (task != null && !taskTerminal) {
            stateStore.tasks.markFailed(taskId, message);
        }
    }

    private static InteractiveTaskResult cancellationTerminal(String taskId,
            String runId, boolean outcomeUnknown, boolean persisted) {
        if (!persisted) {
            return new InteractiveTaskResult(taskId, runId,
                    TaskSubmissionResult.Status.FAILED,
                    "EXECUTION_AUTHORITY_LOST",
                    "Cancellation was not committed because execution authority changed");
        }
        return new InteractiveTaskResult(taskId, runId,
                TaskSubmissionResult.Status.CANCELLED,
                outcomeUnknown ? "CANCELLED_UNKNOWN_OUTCOME" : "CANCELLED",
                "Cancellation reason: user requested");
    }

    private InteractiveSession requireOpenSession() {
        InteractiveSession current = session;
        if (current == null) throw new IllegalStateException("runtime session is not open");
        return current;
    }

    private void bindCreatedRootThread(String taskId) {
        InteractiveSession current = session;
        if (current == null || current.snapshot().currentTaskId() != null
                || current.snapshot().binding().state() != SessionBinding.State.UNBOUND) {
            return;
        }
        try {
            var task = stateStore.r1aDurable.findTask(CanonicalId.parse(taskId)).orElse(null);
            if (task == null) return;
            SavedSessionService.SelectionResult selected =
                    new SavedSessionService(stateStore, workspace, Clock.systemUTC())
                            .select(task.threadId().value());
            if (selected.view() != null) current.bindThread(selected.view().binding());
        } catch (Exception ignored) {
            // Root completion remains authoritative even if the process-local binding cannot refresh.
        }
    }

    private boolean taskIsStillActive(String taskId) {
        InteractiveSession current = session;
        return current != null && taskId.equals(current.snapshot().currentTaskId());
    }

    private void finishSessionTask(String taskId, String runId, String status) {
        InteractiveSession current = session;
        if (current == null) return;
        InteractiveSession.Snapshot snapshot = current.snapshot();
        if (taskId.equals(snapshot.currentTaskId())) {
            current.finishTask(taskId);
            view.taskFinished(taskId,
                    runId == null ? snapshot.currentRunId() : runId, status);
        }
    }

    private static String failureCode(String summary) {
        if (summary != null && (summary.contains("APPROVAL_REQUIRED")
                || summary.contains("approval channel is not connected"))) {
            return "INTERACTIVE_APPROVAL_NOT_CONNECTED";
        }
        if (summary != null) {
            java.util.regex.Matcher provider = java.util.regex.Pattern
                    .compile("\\b(PROVIDER_[A-Z0-9_]{1,56})\\b")
                    .matcher(summary);
            if (provider.find()) return provider.group(1);
        }
        return "INTERACTIVE_RUNTIME_FAILURE";
    }

    private static String bounded(String value) {
        if (value == null || value.isBlank()) return "Task failed.";
        String clean = value.replace('\r', ' ').replace('\n', ' ')
                .replaceAll("[\\p{Cntrl}&&[^\\t]]", "?");
        return clean.length() <= 512
                ? clean : clean.substring(0, 497) + " [truncated]";
    }

}
