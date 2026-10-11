package com.paicli.interactive.runtime;

import com.paicli.agent.AgentLoopObserver;
import com.paicli.event.AgentEventPublisher;
import com.paicli.event.AgentEvent;
import com.paicli.event.AgentEventType;
import com.paicli.event.AgentEvents;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.session.SavedSessionService;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.interactive.approval.InteractiveApprovalCoordinator;
import com.paicli.interactive.approval.InteractiveApprovalRequest;
import com.paicli.interactive.cancel.InteractiveCancellationController;
import com.paicli.runtime.CodingTaskObserver;
import com.paicli.runtime.CodingTaskResult;
import com.paicli.runtime.PlanResult;
import com.paicli.runtime.VerificationLifecycleObserver;
import com.paicli.runtime.VerificationResult;
import com.paicli.runtime.FencedExecutionGuard;
import com.paicli.safety.ApprovalDecision;
import com.paicli.state.FileSnapshotStore;
import com.paicli.state.StateStore;
import com.paicli.state.TaskStore;
import com.paicli.safety.SafetyPolicy;
import com.paicli.safety.SafetyViolationException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HexFormat;

/** Maps persisted runtime facts to bounded typed interactive events. */
public final class RuntimeEventBridge implements CodingTaskObserver,
        AgentLoopObserver, VerificationLifecycleObserver,
        com.paicli.provider.telemetry.ProviderCallProgressSink {
    private static final int MAX_PLAN_STEPS = com.paicli.state.PlanContentBounds.MAX_STEPS;
    private static final String INTERACTIVE_TASK_CANCELLED_REASON =
            "interactive user cancelled task";
    private static final String PLAN_NOT_APPROVED_REASON =
            "interactive user did not approve Plan revision";
    private final AgentEventPublisher publisher;
    private final InteractiveSession session;
    private final InteractiveRuntimeView view;
    private final StateStore stateStore;
    private final Path workspace;
    private final InteractiveApprovalCoordinator approvals;
    private final InteractiveCancellationController cancellation;
    private final SafetyPolicy pathPolicy;
    private InteractiveCodeIntelligenceStage.CodeFacts pendingCodeFacts;
    private final java.util.concurrent.atomic.AtomicReference<FencedExecutionGuard>
            executionGuard=new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.ConcurrentHashMap<String,Long>
            providerQuietSequences=new java.util.concurrent.ConcurrentHashMap<>();

    public RuntimeEventBridge(AgentEventPublisher publisher, InteractiveSession session,
            InteractiveRuntimeView view, StateStore stateStore, Path workspace) {
        this(publisher, session, view, stateStore, workspace,
                new InteractiveApprovalCoordinator(session.snapshot().sessionId(),
                        new InteractiveCancellationController(), java.time.Clock.systemUTC()),
                new InteractiveCancellationController());
    }

    public RuntimeEventBridge(AgentEventPublisher publisher, InteractiveSession session,
            InteractiveRuntimeView view, StateStore stateStore, Path workspace,
            InteractiveApprovalCoordinator approvals,
            InteractiveCancellationController cancellation) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.session = Objects.requireNonNull(session, "session");
        this.view = Objects.requireNonNull(view, "view");
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.workspace = Objects.requireNonNull(workspace, "workspace")
                .toAbsolutePath().normalize();
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.pathPolicy = new SafetyPolicy(this.workspace, 1, 1024);
    }

    public InteractiveApprovalCoordinator approvals() { return approvals; }
    public InteractiveCancellationController cancellation() { return cancellation; }
    public String approvalMode() { return session.snapshot().approvalMode(); }
    public String verificationMode() { return session.snapshot().verificationMode(); }
    public String verificationPreset() { return session.snapshot().verificationPreset(); }
    public String sessionId() { return session.snapshot().sessionId(); }
    public String activeRunId() { return view.snapshot().activeRunId(); }
    public void bindExecutionGuard(FencedExecutionGuard guard){executionGuard.set(guard);}
    public FencedExecutionGuard executionGuard(){return executionGuard.get();}
    public void clearExecutionGuard(FencedExecutionGuard guard){executionGuard.compareAndSet(guard,null);}
    public void clearExecutionGuard(){executionGuard.set(null);}
    public void contextCompacted(com.paicli.context.CompactionCoordinator.Outcome outcome){
        Objects.requireNonNull(outcome);
        FencedExecutionGuard guard=executionGuard();
        contextCompacted(guard==null?null:guard.current().taskId().value(),
                guard==null?null:guard.current().runId().value(),outcome);
    }
    public void contextCompacted(String taskId,String runId,
            com.paicli.context.CompactionCoordinator.Outcome outcome){
        Objects.requireNonNull(outcome);
        publisher.publish(AgentEventType.CONTEXT_LIFECYCLE,taskId,runId,
                metadata->new AgentEvents.ContextLifecycle(metadata,outcome.safeSummary()));
    }
    @Override public void executionAuthorityBound(FencedExecutionGuard guard){
        bindExecutionGuard(Objects.requireNonNull(guard));
    }

    public InteractiveApprovalCoordinator.ApprovalEvents approvalEvents() {
        java.util.Set<String> cancelledPlanRequestIds =
                java.util.concurrent.ConcurrentHashMap.newKeySet();
        return new InteractiveApprovalCoordinator.ApprovalEvents() {
            @Override public String activeRunId() { return view.snapshot().activeRunId(); }
            @Override public String approvalMode() { return RuntimeEventBridge.this.approvalMode(); }
            @Override public String readHash(String relativePath) {
                return view.readHash(relativePath);
            }
            @Override public void cancellationRequested(boolean forced) {
                RuntimeEventBridge.this.cancellationRequested(forced, 0);
            }
            @Override public void proposed(String proposalId, String taskId, String runId,
                    com.paicli.interactive.diff.PreWriteDiff diff) {
                try {
                    FencedExecutionGuard guard=executionGuard();
                    if(guard!=null)guard.recordProposal(proposalId,diff.relativePath(),
                            diff.afterHash(),diff.additions(),diff.deletions(),diff.truncated(),
                            diff.redacted());
                    else stateStore.interactiveProposals.recordProposed(proposalId,taskId,runId,
                            diff.relativePath(),diff.afterHash(),diff.additions(),diff.deletions(),
                            diff.truncated(),diff.redacted());
                } catch (SQLException error) {
                    throw new IllegalStateException("proposal persistence failed", error);
                }
                view.proposal(diff);
                publisher.publish(AgentEventType.DIFF_PROPOSED,
                        taskId, currentRun(taskId),
                        metadata -> new AgentEvents.DiffProposed(metadata,
                                diff.relativePath(), diff.additions(),
                                diff.deletions(), "pre-write proposal",
                                diff.truncated()));
            }
            @Override public void proposalResolved(
                    InteractiveApprovalRequest request, String status) {
                if (request == null || request.diff() == null) return;
                try {
                    FencedExecutionGuard guard=executionGuard();
                    if(guard!=null)guard.transitionProposal(request.requestId(),
                            com.paicli.state.InteractiveProposalStore.PROPOSED,status);
                    else stateStore.interactiveProposals.resolve(request.requestId(),
                            com.paicli.state.InteractiveProposalStore.PROPOSED,status);
                } catch (SQLException error) {
                    throw new IllegalStateException(
                            "proposal resolution persistence failed", error);
                }
                view.proposalResolved(request.relativePath(),
                        request.diff().afterHash(), status);
            }
            @Override public void requested(InteractiveApprovalRequest request) {
                view.confirmationPending(true);
                List<AgentEvents.ApprovalRequested.PlanStepPreview> planSteps=
                        request.boundedPlanSteps().stream()
                        .map(step->new AgentEvents.ApprovalRequested.PlanStepPreview(
                                step.order(),step.boundedTitle(),step.boundedDescription(),
                                step.requirements().stream().map(requirement ->
                                        new AgentEvents.ApprovalRequested.RequirementPreview(
                                                requirement.requirementId(),
                                                requirement.requirementOrder(),
                                                requirement.toolName(),
                                                requirement.selectorKind(),
                                                requirement.safeSelectorSummary(),
                                                requirement.requiredSlots())).toList()))
                        .toList();
                publisher.publish(AgentEventType.APPROVAL_REQUESTED,
                        request.taskId(), currentRun(request.taskId()),
                        metadata -> new AgentEvents.ApprovalRequested(metadata,
                                request.toolName(), request.relativePath(),
                                request.diff() == null ? 0 : request.diff().additions(),
                                request.diff() == null ? 0 : request.diff().deletions(),
                                request.diff() != null && request.diff().truncated(),
                                request.kind().name(), request.boundedSummary(), request.warning(),
                                request.planRevisionId(),request.planStepCount(),
                                request.planSchemaVersion(),planSteps));
            }
            @Override public List<String> planDetails(InteractiveApprovalRequest request) {
                if(request==null||request.kind()!=InteractiveApprovalRequest.Kind.PLAN
                        ||request.planRevisionId()==null)
                    throw new InteractiveApprovalCoordinator.PlanApprovalPresentationException(
                            new IllegalArgumentException("typed Plan approval request required"));
                CanonicalId taskId=CanonicalId.parse(request.taskId());
                CanonicalId revisionId=CanonicalId.parse(request.planRevisionId());
                var read=stateStore.planAuthorities.readRevisionDefinition(taskId,revisionId);
                if(read.status()!=com.paicli.state.PlanAuthorityStore.Status.SUCCESS
                        ||read.revision()==null
                        ||!read.revision().createdByRunId().value().equals(request.runId()))
                    throw new InteractiveApprovalCoordinator.PlanApprovalPresentationException(
                            new IllegalStateException("Plan revision details integrity failure"));
                return com.paicli.interactive.approval.CanonicalPlanPresentation
                        .advisoryDetails(read.revision());
            }
            @Override public void resolved(InteractiveApprovalRequest request,
                    ApprovalDecision decision, boolean taskScoped) {
                view.confirmationPending(false);
                String taskId = request == null
                        ? view.snapshot().activeTaskId() : request.taskId();
                if (taskId == null) return;
                boolean cancelled = decision.decision() == ApprovalDecision.Decision.DENY
                        && INTERACTIVE_TASK_CANCELLED_REASON.equals(decision.reason());
                if (request != null && cancelled) {
                    try {
                        FencedExecutionGuard guard=executionGuard();
                        if(guard!=null)guard.recordApproval(request.iteration(),request.requestId(),
                                decision.toolName(),decision.riskLevel().name(),
                                decision.decision().name(),decision.reason());
                        else stateStore.approvals.record(taskId,request.iteration(),
                                request.requestId(),decision.toolName(),decision.riskLevel().name(),
                                decision.decision().name(),decision.reason());
                    } catch (SQLException error) {
                        throw new IllegalStateException(
                                "approval cancellation persistence failed", error);
                    }
                }
                boolean planRequest = request != null
                        && request.kind() == InteractiveApprovalRequest.Kind.PLAN;
                if (planRequest && cancelled) {
                    cancelledPlanRequestIds.add(request.requestId());
                } else if (planRequest
                        && decision.decision() == ApprovalDecision.Decision.DENY
                        && PLAN_NOT_APPROVED_REASON.equals(decision.reason())
                        && cancelledPlanRequestIds.remove(request.requestId())) {
                    return;
                }
                String presentationDecision = cancelled
                        ? "CANCELLED" : decision.decision().name();
                publisher.publish(AgentEventType.APPROVAL_RESOLVED,
                        taskId, currentRun(taskId),
                        metadata -> new AgentEvents.ApprovalResolved(metadata,
                                decision.toolName(), presentationDecision, taskScoped));
                if (request != null && !planRequest && cancelled) {
                    publisher.publish(AgentEventType.TOOL_CANCELLED,
                            taskId, currentRun(taskId),
                            metadata -> new AgentEvents.ToolCancelled(metadata,
                                    decision.toolName(), decision.reason()));
                }
            }
        };
    }

    public void cancellationRequested(boolean forced, int terminated) {
        InteractiveRuntimeView.Snapshot snapshot = view.snapshot();
        if (snapshot.activeTaskId() == null) return;
        if (forced) {
            publisher.publish(AgentEventType.CANCELLATION_FORCED,
                    snapshot.activeTaskId(), snapshot.activeRunId(),
                    metadata -> new AgentEvents.CancellationForced(
                            metadata, terminated, true));
        } else {
            publisher.publish(AgentEventType.CANCELLATION_REQUESTED,
                    snapshot.activeTaskId(), snapshot.activeRunId(),
                    metadata -> new AgentEvents.CancellationRequested(
                            metadata, "COOPERATIVE"));
        }
    }
    public void sessionStarted() {
        InteractiveSession.Snapshot snapshot = session.snapshot();
        publisher.publish(AgentEventType.SESSION_STARTED, null, null,
                metadata -> new AgentEvents.SessionStarted(metadata,
                        snapshot.workspaceLabel(), snapshot.provider(),
                        snapshot.model(), snapshot.approvalMode()));
    }

    public void sessionEnded(String reason) {
        publisher.publish(AgentEventType.SESSION_ENDED, null, null,
                metadata -> new AgentEvents.SessionEnded(metadata, bounded(reason, 128)));
    }

    public synchronized void stageCodeContext(
            InteractiveCodeIntelligenceStage.CodeFacts facts) {
        if (pendingCodeFacts != null) {
            throw new IllegalStateException("code context already staged");
        }
        pendingCodeFacts = Objects.requireNonNull(facts, "facts");
    }
    public void codeContextSelected(String taskId, String runId,
            List<String> files, List<String> symbols, int contextItems,
            int indexedJavaFiles, int queryCount) {
        List<String> boundedFiles = files.stream().limit(32).toList();
        List<String> boundedSymbols = symbols.stream().limit(32)
                .map(value -> bounded(value, 256)).toList();
        view.codeContext(indexedJavaFiles, contextItems);
        view.knownPaths(boundedFiles);
        publisher.publish(AgentEventType.CODE_CONTEXT_SELECTED, taskId, runId,
                metadata -> new AgentEvents.CodeContextSelected(metadata,
                        boundedFiles, boundedSymbols, contextItems,
                        indexedJavaFiles, queryCount, true));
    }

    @Override
    public void taskStarted(String taskId, String runId, String boundedGoal) {
        session.bindRun(taskId, runId);
        InteractiveSession.Snapshot snapshot=session.snapshot();
        String threadId=snapshot.binding()==null||snapshot.binding().threadId()==null
                ?null:snapshot.binding().threadId().value();
        String generationId=null;
        try {
            generationId=stateStore.r1aDurable.readContextPointer(CanonicalId.parse(taskId))
                    .map(pointer->pointer.generationId().value()).orElse(null);
        } catch (SQLException|IllegalArgumentException ignored) {
            // A missing correlation component makes the preview stale; durable truth still wins.
        }
        view.taskStarted(SavedSessionService.workspaceIdentity(workspace),threadId,
                taskId,runId,generationId);
        String runKind = authoritativeRunKind(runId);
        publisher.publish(AgentEventType.TASK_STARTED, taskId, runId,
                metadata -> new AgentEvents.TaskStarted(
                        metadata, bounded(boundedGoal, 2048), runKind));
        com.paicli.diagnostic.StageTrace.event(
                "TASK_ACCEPTED", true,
                "task_id", taskId,
                "run_id", runId,
                "run_kind", runKind);
        InteractiveCodeIntelligenceStage.CodeFacts facts;
        synchronized (this) {
            facts = pendingCodeFacts;
            pendingCodeFacts = null;
        }
        if (facts != null) {
            try {
                FencedExecutionGuard guard=executionGuard();
                if(guard!=null)guard.recordInteractiveFacts(
                        facts.queryCount()>0?"USED":"NOT_USED",facts.indexedJavaFiles(),
                        facts.contextItemCount(),facts.queryCount());
                else stateStore.interactiveTaskFacts.recordCodeIntelligence(taskId,runId,
                        facts.queryCount()>0?"USED":"NOT_USED",facts.indexedJavaFiles(),
                        facts.contextItemCount(),facts.queryCount());
            } catch (SQLException error) {
                throw new IllegalStateException(
                        "code intelligence fact persistence failed", error);
            }
            codeContextSelected(taskId, runId, facts.relativeFiles(), facts.symbols(),
                    facts.contextItemCount(), facts.indexedJavaFiles(),
                    facts.queryCount());
        }
    }

    private String authoritativeRunKind(String runId) {
        try {
            String runKind = stateStore.codingRuns.findById(runId)
                    .map(com.paicli.state.CodingRunStore.CodingRunRecord::runKind)
                    .orElse(null);
            if (com.paicli.state.CodingRunStore.KIND_INITIAL.equals(runKind)
                    || com.paicli.state.CodingRunStore.KIND_RESUME.equals(runKind)) {
                return runKind;
            }
        } catch (SQLException ignored) {
            // Missing presentation correlation must not change durable recovery.
        }
        return "UNKNOWN";
    }

    @Override
    public void planCreated(String taskId, String runId, PlanResult plan) {
        PlanFacts facts=canonicalPlanFacts(taskId,plan);
        view.plan(facts.summary(), facts.steps());
        publisher.publish(AgentEventType.PLAN_CREATED, taskId, runId,
                metadata -> new AgentEvents.PlanCreated(metadata, facts.summary(),
                        facts.stepCount(), facts.steps(),facts.revisionId()));
    }

    @Override
    public void planStepStarted(String taskId,String runId,
            com.paicli.state.StructuredPlanModels.Revision revision,
            com.paicli.state.StructuredPlanModels.StepDefinition step,int attempt){
        publishPlanProgress(taskId,runId,revision,step,"→");
    }

    @Override
    public void planStepCompleted(String taskId,String runId,
            com.paicli.state.StructuredPlanModels.Revision revision,
            com.paicli.state.StructuredPlanModels.StepDefinition step,int attempt){
        publishPlanProgress(taskId,runId,revision,step,"✓");
    }

    private void publishPlanProgress(String taskId,String runId,
            com.paicli.state.StructuredPlanModels.Revision revision,
            com.paicli.state.StructuredPlanModels.StepDefinition current,String marker){
        java.util.List<String> steps=new java.util.ArrayList<>();
        for(var step:revision.steps()){
            String prefix=step.order()<current.order()?"✓":
                    step.order()==current.order()?marker:"□";
            steps.add(prefix+" "+bounded(step.title(),256));
        }
        publisher.publish(AgentEventType.PLAN_UPDATED,taskId,runId,
                metadata->new AgentEvents.PlanUpdated(metadata,steps.size(),steps));
    }

    @Override
    public void modelMessage(String taskId,int iteration,String kind,String boundedContent,
            com.paicli.agent.AgentSegmentRequest.PlanExecutionContext planExecution){
        if(boundedContent==null||boundedContent.isBlank())return;
        publisher.publish(AgentEventType.MODEL_MESSAGE,taskId,currentRun(taskId),
                metadata->new AgentEvents.ModelMessage(metadata,kind,bounded(boundedContent,2048)));
    }

    @Override
    public synchronized void started(String correlationId,
            com.paicli.provider.telemetry.ProviderCallContext context,String operation){
        if(context!=null&&context.taskId()!=null)
            providerQuietSequences.put(correlationId,publisher.lastSequence());
    }

    @Override
    public synchronized void elapsed(String correlationId,
            com.paicli.provider.telemetry.ProviderCallContext context,String operation,
            long elapsedSeconds){
        if(context==null||context.taskId()==null)return;
        Long expected=providerQuietSequences.get(correlationId);
        if(expected==null)return;
        String label=switch(operation==null?"":operation){
            case "PLAN"->"Planning";
            case "FINAL_SYNTHESIS"->"Final synthesis";
            case "TOOL_CONTINUATION"->"Continuing";
            default->"Chat";
        };
        AgentEvent event=publisher.publishIfLastSequence(expected,
                AgentEventType.MODEL_MESSAGE,context.taskId(),context.runId(),
                metadata->new AgentEvents.ModelMessage(metadata,"MODEL_HEARTBEAT",
                        "[Model] "+label+" · "+elapsedSeconds+"s elapsed"));
        providerQuietSequences.put(correlationId,event==null
                ?publisher.lastSequence():event.metadata().sequence());
    }

    @Override
    public void retrying(com.paicli.provider.telemetry.ProviderCallContext context,
            String operation,int attempt,String reason){
        if(context==null||context.taskId()==null)return;
        int retry=Math.max(1,Math.min(2,attempt-1));
        publisher.publish(AgentEventType.MODEL_MESSAGE,context.taskId(),context.runId(),
                metadata->new AgentEvents.ModelMessage(metadata,"MODEL_PROVIDER_RETRY",
                        "[Model] Provider response incomplete. Retrying provider request "
                                +retry+"/2."));
    }

    @Override
    public void retrying(
            com.paicli.provider.telemetry.ProviderCallProgressSink.RetryProgress progress){
        if(progress==null||progress.context()==null
                ||progress.context().taskId()==null)return;
        long delay=Math.max(0,Math.min(60_000,progress.backoffMillis()));
        String wait=delay==0?"now":"in "+delay+" ms";
        String interruption=publicInterruptionLabel(progress.reason());
        publisher.publish(AgentEventType.MODEL_MESSAGE,progress.context().taskId(),
                progress.context().runId(),metadata->new AgentEvents.ModelMessage(metadata,
                        "MODEL_PROVIDER_RETRY","[Model] "+interruption+" "
                                +"Retrying provider request "+progress.retryCount()+"/2 "
                                +wait+"."));
    }

    @Override
    public void restored(String logicalCallId,
            com.paicli.provider.telemetry.ProviderCallContext context,String operation,
            int successfulAttempt){
        if(context==null||context.taskId()==null)return;
        publisher.publish(AgentEventType.MODEL_MESSAGE,context.taskId(),context.runId(),
                metadata->new AgentEvents.ModelMessage(metadata,"MODEL_PROVIDER_RESTORED",
                        "[Model] Provider connection restored."));
    }

    @Override
    public void exhausted(String logicalCallId,
            com.paicli.provider.telemetry.ProviderCallContext context,String operation,
            int attempts,String reason,String requestDigest){
        if(context==null||context.taskId()==null)return;
        int boundedAttempts=Math.max(1,Math.min(3,attempts));
        String category=publicRetryCategory(reason);
        publisher.publish(AgentEventType.MODEL_MESSAGE,context.taskId(),context.runId(),
                metadata->new AgentEvents.ModelMessage(metadata,"MODEL_PROVIDER_EXHAUSTED",
                        "[Model] Provider request failed after "+boundedAttempts
                                +" attempts. Category: "+category
                                +". Durable task state was preserved."));
    }

    @Override
    public synchronized void finished(String correlationId){
        providerQuietSequences.remove(correlationId);
    }

    private static String publicRetryCategory(String reason){
        if(reason==null)return "provider_request_failed";
        return switch(reason){
            case "TRANSPORT_TLS_EOF","TRANSPORT_CONNECT_TIMEOUT",
                    "TRANSPORT_CONNECTION_RESET","TRANSPORT_READ_TIMEOUT",
                    "TRANSPORT_INTERRUPTED"->
                    "transport_interrupted";
            case "HTTP_429"->"rate_limited";
            case "HTTP_502","HTTP_503","HTTP_504"->"provider_unavailable";
            case "PRIVATE_CONTINUATION_INCOMPLETE",
                    "PROVIDER_PRIVATE_CONTINUATION_INCOMPLETE"->
                    "provider_protocol_incomplete";
            default->"provider_request_failed";
        };
    }

    private static String publicInterruptionLabel(String reason){
        return "PRIVATE_CONTINUATION_INCOMPLETE".equals(reason)
                ||"PROVIDER_PRIVATE_CONTINUATION_INCOMPLETE".equals(reason)
                ?"Provider response incomplete.":"Provider connection interrupted.";
    }

    @Override
    public void phaseChanged(String taskId, String runId, String phase) {
        view.phase(phase);
        publisher.publish(AgentEventType.PHASE_CHANGED, taskId, runId,
                metadata -> new AgentEvents.PhaseChanged(metadata, phase));
    }

    @Override
    public void repairStarted(String taskId, String runId, int attempt,
            VerificationResult trigger) {
        publisher.publish(AgentEventType.REPAIR_STARTED, taskId, runId,
                metadata -> new AgentEvents.RepairStarted(metadata, attempt));
    }

    @Override
    public void taskCompleted(CodingTaskResult result) {
        // The adapter classifies DONE against durable approval/write/verification
        // facts after CodingTaskRunner returns. Do not publish a premature success.
    }

    public void taskCompleted(CodingTaskResult result, InteractiveTaskOutcome outcome) {
        view.taskFinished(result.taskId(), result.runId(), outcome.persistedStatus());
        finishSessionTask(result.taskId());
        if (com.paicli.state.TaskStore.STATUS_REJECTED.equals(outcome.persistedStatus())) {
            publisher.publish(AgentEventType.TASK_FAILED, result.taskId(), result.runId(),
                    metadata -> new AgentEvents.TaskFailed(metadata, "REJECTED",
                            bounded(outcome.message(), 2048)));
        } else {
            publisher.publish(AgentEventType.TASK_COMPLETED, result.taskId(), result.runId(),
                    metadata -> new AgentEvents.TaskCompleted(metadata,
                            outcome.persistedStatus(),
                            result.verificationStatus() == null
                                    ? "NOT_RUN" : result.verificationStatus(),
                            bounded(outcome.message(), 2048)));
        }
    }

    @Override
    public void taskFailed(CodingTaskResult result) {
        view.taskFinished(result.taskId(), result.runId(), result.finalStatus());
        finishSessionTask(result.taskId());
        String category = approvalRequired(result.summary())
                ? "INTERACTIVE_APPROVAL_NOT_CONNECTED" : "TASK_FAILED";
        publisher.publish(AgentEventType.TASK_FAILED, result.taskId(), result.runId(),
                metadata -> new AgentEvents.TaskFailed(metadata, category,
                        bounded(result.summary(), 4096)));
    }

    @Override
    public void toolBatchStarted(String taskId,int iteration,ToolBatchFact fact){
        publisher.publish(AgentEventType.TOOL_BATCH_STARTED,taskId,currentRun(taskId),
                metadata->new AgentEvents.ToolBatchStarted(metadata,fact.batchId(),
                        fact.size(),fact.effectClass()));
    }

    @Override
    public void toolRequested(String taskId, int iteration, ToolRequestFact fact) {
        String runId = currentRun(taskId);
        String stepLabel=stepLabel(fact.planExecution());
        publisher.publish(AgentEventType.TOOL_REQUESTED, taskId, runId,
                metadata -> new AgentEvents.ToolRequested(metadata,
                        fact.toolName(), fact.boundedArgumentsSummary(),fact.relativePath(),
                        fact.effectClass()==null?"unknown":fact.effectClass(),stepLabel,
                        fact.planExecution()==null?1:fact.planExecution().attemptNumber(),
                        fact.sameRequestOrdinal(),fact.toolCallId(),fact.batchIndex(),
                        fact.batchSize()));
    }

    @Override
    public void toolDecision(String taskId, int iteration, ToolRequestFact fact,
            ApprovalDecision decision) {
        String runId = currentRun(taskId);
        if (decision.decision() == ApprovalDecision.Decision.NEEDS_APPROVAL) {
            return;
        }
        if (decision.isAllowed()) {
            publisher.publish(AgentEventType.TOOL_APPROVED, taskId, runId,
                    metadata -> new AgentEvents.ToolApproved(metadata,
                            fact.toolName(), decision.decision().name()));
        } else {
            String reason = bounded(decision.reason(), 2048);
            publisher.publish(AgentEventType.TOOL_REJECTED, taskId, runId,
                    metadata -> new AgentEvents.ToolRejected(
                            metadata, fact.toolName(), reason));
        }
    }

    @Override
    public boolean failOnApprovalRequired() {
        return true;
    }
    @Override
    public void toolCompleted(String taskId, int iteration, ToolRequestFact request,
            ToolCompletionFact result) {
        String runId = currentRun(taskId);
        if (!result.success()) {
            String error = result.boundedError() == null
                    || result.boundedError().isBlank()
                    ? "tool execution failed" : result.boundedError();
            publisher.publish(AgentEventType.TOOL_FAILED, taskId, runId,
                    event -> new AgentEvents.ToolFailed(
                            event, request.toolName(), error,result.elapsedMillis(),
                            stepLabel(request.planExecution()),request.planExecution()==null?1:
                            request.planExecution().attemptNumber(),request.sameRequestOrdinal(),
                            request.batchIndex(),request.batchSize()));
            return;
        }
        Map<String, Object> metadata = result.metadata();
        if ("read_file".equals(request.toolName()) && request.relativePath() != null) {
            view.fileRead(request.relativePath(), currentFileHash(request.relativePath()));
            long bytes = number(metadata.get("bytesRead"));
            publisher.publish(AgentEventType.FILE_READ, taskId, runId,
                    event -> new AgentEvents.FileRead(
                            event, request.relativePath(), bytes));
            publishToolCompleted(taskId, runId, request,result);
            return;
        }
        if (!"write_file".equals(request.toolName()) || request.relativePath() == null) {
            publishToolCompleted(taskId, runId, request,result);
            return;
        }


        String snapshotId = string(metadata.get("snapshotId"));
        if (snapshotId != null && appliedSnapshot(taskId, snapshotId)) {
            publisher.publish(AgentEventType.SNAPSHOT_CREATED, taskId, runId,
                    event -> new AgentEvents.SnapshotCreated(
                            event, request.relativePath(), safeReference(snapshotId)));
        }

        try {
            FencedExecutionGuard guard=executionGuard();
            if(guard!=null)guard.applyLatestProposal(request.relativePath(),snapshotId);
            else stateStore.interactiveProposals.markLatestApplied(
                    taskId,runId,request.relativePath(),snapshotId);
        } catch (SQLException error) {
            throw new IllegalStateException("applied proposal persistence failed", error);
        }
        view.applied(request.relativePath(), snapshotId == null ? null : safeReference(snapshotId));
        long bytes = number(metadata.get("bytesWritten"));
        publisher.publish(AgentEventType.FILE_WRITTEN, taskId, runId,
                event -> new AgentEvents.FileWritten(
                        event, request.relativePath(), bytes));
        publishToolCompleted(taskId, runId, request,result);
    }

    @Override
    public void toolCancelled(String taskId,int iteration,ToolRequestFact request,String reason){
        publisher.publish(AgentEventType.TOOL_CANCELLED,taskId,currentRun(taskId),
                metadata->new AgentEvents.ToolCancelled(metadata,request.toolName(),
                        bounded(reason,512),request.toolCallId(),request.batchIndex(),
                        request.batchSize()));
    }

    @Override
    public void toolBatchCompleted(String taskId,int iteration,ToolBatchFact fact,
            int succeeded,int failed){
        publisher.publish(AgentEventType.TOOL_BATCH_COMPLETED,taskId,currentRun(taskId),
                metadata->new AgentEvents.ToolBatchCompleted(metadata,fact.batchId(),
                        fact.size(),succeeded,failed));
    }

    private void publishToolCompleted(String taskId, String runId,ToolRequestFact request,
            ToolCompletionFact result) {
        boolean showBoundedItems=java.util.Set.of("list_files","grep","run_command")
                .contains(request.toolName());
        java.util.List<String> items;
        if("run_command".equals(request.toolName())){
            items=commandItems(result);
        }else{
            items=!showBoundedItems||result.boundedOutput()==null
                    ?java.util.List.of():result.boundedOutput().lines()
                            .filter(line->!line.isBlank()).limit(8).toList();
        }
        java.util.List<String> facts=new java.util.ArrayList<>(result.metadata().entrySet().stream()
                .filter(entry->java.util.Set.of("exitCode","commandStatus",
                                "stdoutTruncated","stderrTruncated",
                                "bytesRead","bytesWritten","matchCount",
                                "excludedControlCount","excludedControls",
                                "controlDirectoryPolicyApplied")
                        .contains(entry.getKey()))
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(entry->friendlyMetadata(entry.getKey())+": "+bounded(
                        String.valueOf(entry.getValue()),128)).toList());
        if(Boolean.TRUE.equals(result.metadata().get("reusedEvidence"))){
            facts.add("Evidence: cached reuse");
            if(request.relativePath()!=null)facts.add("Target: "+bounded(request.relativePath(),256));
            facts.add("Reason: "+bounded(String.valueOf(
                    result.metadata().getOrDefault("reuseReason","identical read-only call")),128));
        }
        publisher.publish(AgentEventType.TOOL_COMPLETED, taskId, runId,
                event -> new AgentEvents.ToolCompleted(event, request.toolName(),
                        result.boundedOutput(),items,result.itemCount(),facts,result.elapsedMillis(),
                        result.truncated(),stepLabel(request.planExecution()),
                        request.planExecution()==null?1:request.planExecution().attemptNumber(),
                        request.sameRequestOrdinal(),request.toolCallId(),request.batchIndex(),
                        request.batchSize()));
    }

    private java.util.List<String> commandItems(ToolCompletionFact result){
        java.util.List<String> items=new java.util.ArrayList<>();
        appendCommandStream(items,"stdout",result.metadata().get("stdout"));
        appendCommandStream(items,"stderr",result.metadata().get("stderr"));
        if(items.isEmpty()&&result.boundedOutput()!=null)
            result.boundedOutput().lines().filter(line->!line.isBlank()).limit(8)
                    .forEach(line->items.add("stdout | "+bounded(line,512)));
        return java.util.List.copyOf(items);
    }

    private void appendCommandStream(java.util.List<String> items,String stream,Object value){
        if(!(value instanceof String text)||text.isBlank())return;
        text.lines().filter(line->!line.isBlank()).limit(4)
                .forEach(line->items.add(stream+" | "+bounded(line,512)));
    }

    private static String friendlyMetadata(String key){
        return switch(key){
            case "exitCode"->"Exit code";
            case "commandStatus"->"Status";
            case "stdoutTruncated"->"Stdout truncated";
            case "stderrTruncated"->"Stderr truncated";
            case "bytesRead"->"Bytes read";
            case "bytesWritten"->"Bytes written";
            case "matchCount"->"Matches";
            case "excludedControlCount"->"Excluded controls";
            case "excludedControls"->"Excluded";
            case "controlDirectoryPolicyApplied"->"Control policy";
            default->"Fact";
        };
    }

    private String stepLabel(com.paicli.agent.AgentSegmentRequest.PlanExecutionContext context){
        if(context==null)return null;
        try{
            var read=stateStore.planAuthorities.readRevisionDefinition(
                    context.taskId(),context.planRevisionId());
            if(read.status()!=com.paicli.state.PlanAuthorityStore.Status.SUCCESS)return null;
            var steps=read.revision().steps();
            var step=steps.stream().filter(s->s.stepRevisionId().equals(context.stepRevisionId()))
                    .findFirst().orElse(null);
            return step==null?null:(step.order()+1)+" of "+steps.size()+" · "+step.title();
        }catch(RuntimeException error){return null;}
    }

    @Override
    public boolean finalResponse(String taskId,String runId,String responseMode,String content,
            String contentHash){
        long failures=publisher.sinkFailureCount();
        publisher.publish(AgentEventType.FINAL_RESPONSE,taskId,runId,
                metadata->new AgentEvents.FinalResponse(metadata,content,responseMode,contentHash));
        return publisher.sinkFailureCount()==failures;
    }

    private String currentFileHash(String relativePath) {
        try {
            Path target = pathPolicy.resolveWorkspacePath(Path.of(relativePath));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var channel = pathPolicy.openFileForRead(target)) {
                ByteBuffer buffer = ByteBuffer.allocate(8192);
                while (channel.read(buffer) != -1) {
                    buffer.flip();
                    digest.update(buffer);
                    buffer.clear();
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException | SafetyViolationException
                 | RuntimeException error) {
            return null;
        }
    }

    @Override
    public void started(String verificationId, String runId, String taskId,
            int repairAttempt, String policyLabel) {
        publisher.publish(AgentEventType.VERIFICATION_STARTED, taskId, runId,
                metadata -> new AgentEvents.VerificationStarted(
                        metadata, bounded(policyLabel, 256)));
    }

    @Override
    public void completed(VerificationResult result) {
        VerificationEventFacts facts = VerificationEventFacts.from(result);
        if (facts.skippedVerification()) {
            publisher.publish(AgentEventType.VERIFICATION_SKIPPED,
                    result.taskId(), result.runId(),
                    metadata -> new AgentEvents.VerificationSkipped(metadata,
                            "Structured verification was disabled."));
        } else if (facts.passed()) {
            publisher.publish(AgentEventType.VERIFICATION_PASSED,
                    result.taskId(), result.runId(),
                    metadata -> new AgentEvents.VerificationPassed(metadata,
                            facts.tests(), facts.failures(), facts.errors(),
                            facts.skipped(), facts.cleanupComplete(),
                            facts.boundedSummary()));
        } else {
            publisher.publish(AgentEventType.VERIFICATION_FAILED,
                    result.taskId(), result.runId(),
                    metadata -> new AgentEvents.VerificationFailed(metadata,
                            facts.category(), facts.tests(), facts.failures(),
                            facts.errors(), facts.skipped(), facts.timedOut(),
                            facts.cleanupComplete(), facts.boundedSummary()));
        }
    }

    private boolean appliedSnapshot(String taskId, String snapshotId) {
        try {
            FileSnapshotStore.FileSnapshotRecord record = stateStore.snapshots
                    .findById(snapshotId).orElse(null);
            return record != null
                    && taskId.equals(record.taskId())
                    && FileSnapshotStore.STATUS_APPLIED.equals(record.status())
                    && !Path.of(record.filePath()).isAbsolute()
                    && workspace.resolve(record.filePath()).normalize()
                            .startsWith(workspace);
        } catch (SQLException | RuntimeException ignored) {
            return false;
        }
    }

    private void finishSessionTask(String taskId) {
        try {
            FencedExecutionGuard guard=executionGuard();
            if(guard!=null)guard.supersedeProposals();
            else stateStore.interactiveProposals.supersedePending(taskId);
        } catch (FencedExecutionGuard.AuthorityLostException lost) {
            // Authority loss is already a durable fail-closed result. Do not fall back
            // to the unfenced proposal store; only release process-local UI state.
        } catch (SQLException error) {
            throw new IllegalStateException("proposal terminal persistence failed", error);
        }
        InteractiveSession.Snapshot snapshot = session.snapshot();
        if (taskId.equals(snapshot.currentTaskId())) session.finishTask(taskId);
    }
    private String currentRun(String taskId) {
        InteractiveRuntimeView.Snapshot snapshot = view.snapshot();
        if (!Objects.equals(taskId, snapshot.activeTaskId())
                || snapshot.activeRunId() == null) {
            throw new IllegalStateException("event task is not active");
        }
        return snapshot.activeRunId();
    }

    private static boolean approvalRequired(String value) {
        return value != null && (value.contains("APPROVAL_REQUIRED")
                || value.contains("approval channel is not connected"));
    }

    private static long number(Object value) {
        return value instanceof Number number ? Math.max(0, number.longValue()) : 0;
    }

    private static int integer(Object value) {
        long number = number(value);
        return (int) Math.min(number, Integer.MAX_VALUE);
    }

    private static String string(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static String safeReference(String value) {
        return value.length() <= 64 ? value : value.substring(0, 64);
    }

    private PlanFacts canonicalPlanFacts(String taskId,PlanResult plan){
        CanonicalId planRevisionId;
        try{
            planRevisionId=CanonicalId.parse(plan.planId());
            if(planRevisionId.family()!=EntityFamily.PLAN)
                return planFacts(plan.planText(),plan.planId());
        }catch(RuntimeException legacyPlanId){
            return planFacts(plan.planText(),plan.planId());
        }
        try{
            var read=stateStore.planAuthorities.readRevisionDefinition(
                    CanonicalId.parse(taskId),planRevisionId);
            if(read.status()==com.paicli.state.PlanAuthorityStore.Status.SUCCESS
                    &&read.revision()!=null){
                var revision=read.revision();
                List<String> steps=revision.steps().stream().limit(MAX_PLAN_STEPS)
                        .map(step->bounded(step.title(),256)).toList();
                return new PlanFacts(bounded(revision.goal(),256),revision.steps().size(),
                        steps,revision.planRevisionId().value());
            }
        }catch(RuntimeException ignored){
            // Canonical Plan presentation is derived only from its durable immutable revision.
        }
        return new PlanFacts("Plan projection unavailable",0,List.of(),
                planRevisionId.value());
    }

    private static PlanFacts planFacts(String plan,String revisionId) {
        List<String> steps = new ArrayList<>();
        String source = plan == null ? "" : plan;
        for (String line : source.split("\\R")) {
            String step = line.strip().replaceFirst("^(?:[-*]|\\d+[.)])\\s*", "");
            if (!step.isBlank() && steps.size() < MAX_PLAN_STEPS) {
                steps.add(bounded(step, 256));
            }
        }
        String summary = steps.isEmpty() ? "Plan created" : steps.getFirst();
        return new PlanFacts(summary, steps.size(), List.copyOf(steps),revisionId);
    }

    private static String bounded(String value, int max) {
        if (value == null || value.isBlank()) return "not available";
        String clean = value.replace('\r', ' ').replace('\n', ' ')
                .replaceAll("[\\p{Cntrl}&&[^\\t]]", "?");
        return clean.length() <= max
                ? clean : clean.substring(0, Math.max(0, max - 15)) + " [truncated]";
    }

    private record PlanFacts(String summary, int stepCount, List<String> steps,
            String revisionId) { }
}
