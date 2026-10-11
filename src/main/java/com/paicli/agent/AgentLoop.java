package com.paicli.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.model.*;
import com.paicli.model.ModelResponse.ResponseType;
import com.paicli.context.ContextModels.OperationKind;
import com.paicli.context.ContextualModelProvider;
import com.paicli.provider.ModelProvider;
import com.paicli.provider.ProviderResponseEnvelope;
import com.paicli.runtime.FencedExecutionGuard;
import com.paicli.runtime.PlanToolAdmissionService;
import com.paicli.runtime.SteeringSafePointCoordinator;
import com.paicli.runtime.TaskProviderBudgetGate;
import com.paicli.safety.ApprovalDecision;
import com.paicli.safety.ApprovalPolicy;
import com.paicli.session.AuditLog;
import com.paicli.state.StateStore;
import com.paicli.state.TaskStore;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Core agent loop: User Goal → Model → Action/Observation → ... → Final.
 * <p>
 * The loop runs up to {@code maxIterations} times. Each iteration:
 * <ol>
 *   <li>Send conversation history + tool definitions to the model</li>
 *   <li>If response is FINAL → return the answer</li>
 *   <li>If response is TOOL_CALL → check approval, then execute tool, append observation, continue</li>
 * </ol>
 * <p>
 * When an {@link ApprovalPolicy} is provided, every tool call is checked before execution.
 * DENY → tool is not executed, error returned. NEEDS_APPROVAL → fail-closed, not executed.
 */
public class AgentLoop {
    private com.paicli.skill.SkillRuntime skillRuntime;
    private String skillRunId;

    public void bindSkills(com.paicli.skill.SkillRuntime runtime,String runId) {
        if(this.skillRuntime!=null)throw new IllegalStateException("Skills already bound");
        this.skillRuntime=java.util.Objects.requireNonNull(runtime);this.skillRunId=runId;
        runtime.registerTools(toolRegistry,taskId,runId);
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SAFE_TASK_ID = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern RAW_HTTP_MESSAGE = Pattern.compile(
            "(?is)^(?:HTTP/\\d(?:\\.\\d)?\\s+\\d{3}|"
                    + "[!#$%&'*+.^_`|~0-9A-Za-z-]+\\s+\\S+\\s+HTTP/\\d(?:\\.\\d)?).*"
    );
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
            "(?i)((?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret)"
                    + "\\s*[:=]\\s*)(?:\"[^\"]*\"|'[^']*'|\\S+)");
    private static final Pattern SENSITIVE_HEADER = Pattern.compile(
            "(?im)((?:authorization|cookie)\\s*[:=]\\s*)[^\\r\\n]+");
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)\\bBearer\\s+\\S+");
    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "apikey", "token", "accesstoken", "refreshtoken", "password", "secret",
            "authorization", "cookie");
    private static final int MAX_PERSISTED_FIELD_LENGTH = 64 * 1024;

    private final ModelProvider modelProvider;
    private final ToolRegistry toolRegistry;
    private final int maxIterations;
    private final ApprovalPolicy approvalPolicy;  // nullable
    private final AuditLog auditLog;              // nullable
    private final StateStore stateStore;          // nullable
    private final String taskId;                  // nullable when stateStore is null
    private final String providerName;
    private final String modelName;
    private final AgentLoopObserver observer;
    private final AgentLoopControl control;
    private final Map<String,Integer> requestOrdinals = new java.util.HashMap<>();
    private final Map<String,CachedToolEvidence> readOnlyEvidence = new java.util.HashMap<>();
    private final Set<String> noProgressRecoveryIssued = new java.util.HashSet<>();
    private final Map<String,com.paicli.identity.CanonicalId> planToolReservations =
            new java.util.HashMap<>();
    private FencedExecutionGuard executionGuard;
    private SteeringSafePointCoordinator steeringCoordinator;
    private boolean segmentUserDeniedTool;
    private TaskToolBudgetGate taskToolBudgetGate=TaskToolBudgetGate.ALLOW_ALL;
    private TaskProviderBudgetGate taskProviderBudgetGate=TaskProviderBudgetGate.ALLOW_ALL;

    /** Full constructor with approval gate. */
    public AgentLoop(ModelProvider modelProvider, ToolRegistry toolRegistry,
                     int maxIterations, ApprovalPolicy approvalPolicy, AuditLog auditLog) {
        this(modelProvider, toolRegistry, maxIterations, approvalPolicy, auditLog,
                null, null, null, null);
    }

    /** Full constructor with optional SQLite state persistence. */
    public AgentLoop(ModelProvider modelProvider, ToolRegistry toolRegistry,
                     int maxIterations, ApprovalPolicy approvalPolicy, AuditLog auditLog,
                     StateStore stateStore, String taskId, String providerName, String modelName) {
        this(modelProvider, toolRegistry, maxIterations, approvalPolicy, auditLog,
                stateStore, taskId, providerName, modelName,
                AgentLoopObserver.NOOP, AgentLoopControl.NONE);
    }

    public AgentLoop(ModelProvider modelProvider, ToolRegistry toolRegistry,
                     int maxIterations, ApprovalPolicy approvalPolicy, AuditLog auditLog,
                     StateStore stateStore, String taskId, String providerName, String modelName,
                     AgentLoopObserver observer) {
        this(modelProvider, toolRegistry, maxIterations, approvalPolicy, auditLog,
                stateStore, taskId, providerName, modelName, observer, AgentLoopControl.NONE);
    }

    public AgentLoop(ModelProvider modelProvider, ToolRegistry toolRegistry,
                     int maxIterations, ApprovalPolicy approvalPolicy, AuditLog auditLog,
                     StateStore stateStore, String taskId, String providerName, String modelName,
                     AgentLoopObserver observer, AgentLoopControl control) {
        this.modelProvider = modelProvider;
        this.toolRegistry = toolRegistry;
        this.maxIterations = maxIterations;
        this.approvalPolicy = approvalPolicy;
        this.auditLog = auditLog;
        this.stateStore = stateStore;
        this.taskId = taskId;
        this.providerName = providerName;
        this.modelName = modelName;
        this.observer = Objects.requireNonNull(observer, "observer");
        this.control = Objects.requireNonNull(control, "control");
        if (stateStore != null && (taskId == null || !SAFE_TASK_ID.matcher(taskId).matches()
                || ".".equals(taskId) || "..".equals(taskId))) {
            throw new IllegalArgumentException("A safe task ID is required when StateStore is enabled");
        }
    }

    /** Without approval gate (v0.1.0 compatible). */
    public AgentLoop(ModelProvider modelProvider, ToolRegistry toolRegistry, int maxIterations) {
        this(modelProvider, toolRegistry, maxIterations, null, null);
    }

    /** Without approval gate, default max iterations. */
    public AgentLoop(ModelProvider modelProvider, ToolRegistry toolRegistry) {
        this(modelProvider, toolRegistry, 10);
    }

    public AgentLoop bindExecutionGuard(FencedExecutionGuard guard) {
        Objects.requireNonNull(guard,"guard");
        if(stateStore==null||taskId==null||!guard.current().taskId().value().equals(taskId))
            throw new IllegalArgumentException("Execution guard Task mismatch");
        if(executionGuard!=null&&executionGuard!=guard)
            throw new IllegalStateException("Execution guard is already bound");
        executionGuard=guard;return this;
    }

    public AgentLoop bindSteeringCoordinator(SteeringSafePointCoordinator coordinator) {
        Objects.requireNonNull(coordinator,"coordinator");
        if(executionGuard==null)throw new IllegalStateException("Steering requires a bound execution guard");
        if(steeringCoordinator!=null&&steeringCoordinator!=coordinator)
            throw new IllegalStateException("Steering coordinator is already bound");
        steeringCoordinator=coordinator;return this;
    }

    /** Optional runner-owned Task-total budget gate; ordinary AgentLoop construction is unchanged. */
    public AgentLoop bindTaskToolBudgetGate(TaskToolBudgetGate gate) {
        Objects.requireNonNull(gate,"gate");
        if(taskToolBudgetGate!=TaskToolBudgetGate.ALLOW_ALL&&taskToolBudgetGate!=gate)
            throw new IllegalStateException("Task Tool budget gate is already bound");
        taskToolBudgetGate=gate;
        return this;
    }

    /** Optional runner-owned Task-total Provider gate; ordinary AgentLoop behavior is unchanged. */
    public AgentLoop bindTaskProviderBudgetGate(TaskProviderBudgetGate gate) {
        Objects.requireNonNull(gate,"gate");
        if(taskProviderBudgetGate!=TaskProviderBudgetGate.ALLOW_ALL
                &&taskProviderBudgetGate!=gate)
            throw new IllegalStateException("Task Provider budget gate is already bound");
        taskProviderBudgetGate=gate;
        return this;
    }

    public boolean hasPendingApproval() { return control.hasPendingApproval(); }

    /**
     * Run the agent loop for the given user goal.
     */
    public AgentResult run(String goal) {
        if (stateStore == null) {
            return toAgentResult(runLoop(
                    goal, false, new RunState(), maxIterations, true, null,OperationKind.CHAT,
                    null,false));
        }

        RunState runState = new RunState();
        try {
            initializeTask(goal);
            return toAgentResult(runLoop(
                    goal, true, runState, maxIterations, true, null,OperationKind.CHAT,
                    null,false));
        } catch (RuntimeException e) {
            persistFailure(runState, e);
            throw e;
        }
    }

    /** Continue a prior conversation while preserving absolute iteration numbering. */
    public AgentResult run(String goal, List<AgentMessage> resumedMessages, int startIteration) {
        Objects.requireNonNull(resumedMessages, "resumedMessages");
        if (startIteration < 0) {
            throw new IllegalArgumentException("startIteration must not be negative");
        }

        RunState runState = new RunState(resumedMessages, startIteration);
        if (stateStore == null) {
            return toAgentResult(runLoop(
                    goal, false, runState, maxIterations, true, null,OperationKind.CHAT,
                    null,false));
        }

        try {
            initializeTask(goal);
            return toAgentResult(runLoop(
                    goal, true, runState, maxIterations, true, null,OperationKind.CHAT,
                    null,false));
        } catch (RuntimeException e) {
            persistFailure(runState, e);
            throw e;
        }
    }

    /**
     * Run one execution segment while leaving task terminal status to an outer runner.
     * When SQLite persistence is enabled, the task must already exist.
     */
    public AgentSegmentResult runSegment(AgentSegmentRequest request) {
        Objects.requireNonNull(request, "request");
        segmentUserDeniedTool=false;
        if (request.planExecution() != null) {
            if (executionGuard == null) {
                throw new IllegalStateException("Plan execution context requires fencing");
            }
            executionGuard.validatePlanExecutionContext(request.planExecution());
        }
        boolean persistState = stateStore != null;
        if (persistState) {
            requireManagedTask();
        }

        RunState runState = new RunState(request.conversation(), request.startIteration());
        int iterationLimit = request.startIteration() + request.maxSegmentIterations();
        try {
            LoopExecution execution = runLoop(
                    request.goal(), persistState, runState, iterationLimit,
                    false, request.additionalPrompt(),request.providerOperation(),
                    request.planExecution(),request.finalResponseOnly());
            return new AgentSegmentResult(
                    execution.answer(), request.startIteration(), execution.iteration(),
                    execution.iteration() - request.startIteration(),
                    execution.conversation(), execution.log(), execution.outcome(),
                    execution.providerErrorKind(),segmentUserDeniedTool,
                    execution.providerOccurrenceId());
        } catch (RuntimeException e) {
            if (e instanceof com.paicli.runtime.CancellationToken.TaskCancellationException) {
                throw e;
            }
            RuntimeException propagated=observedAt(e,runState.iteration);
            if (persistState) {
                persistSegmentFailure(runState, propagated);
            }
            throw propagated;
        }
    }

    private LoopExecution runLoop(String goal, boolean persistState, RunState runState,
                                   int iterationLimit, boolean manageTaskLifecycle,
                                   String additionalPrompt,OperationKind initialOperation,
                                   AgentSegmentRequest.PlanExecutionContext planExecution,
                                   boolean finalResponseOnly) {
        List<AgentMessage> messages = runState.messages;
        List<String> log = runState.log;
        List<ToolDefinition> toolDefs = finalResponseOnly
                ? List.of() : toolRegistry.allDefinitions();

        boolean messagesChanged = false;
        int checkpointIteration = runState.iteration;
        if (messages.isEmpty()) {
            AgentMessage userMessage = AgentMessage.userGoal(goal);
            messages.add(userMessage);
            log.add("USER: " + (persistState ? safePersistedText(goal) : goal));
            if (persistState) {
                persistEvent(0, userMessage);
            }
            messagesChanged = true;
            checkpointIteration = 0;
        }
        if (additionalPrompt != null && !additionalPrompt.isBlank()) {
            AgentMessage promptMessage = finalResponseOnly
                    ? AgentMessage.runtimeFinalPrompt(additionalPrompt)
                    : AgentMessage.runtimeStepPrompt(additionalPrompt);
            messages.add(promptMessage);
            log.add("USER: " + (persistState
                    ? safePersistedText(additionalPrompt) : additionalPrompt));
            if (persistState) {
                persistEvent(runState.iteration, promptMessage);
            }
            messagesChanged = true;
            checkpointIteration = runState.iteration;
        }
        if (persistState && messagesChanged) {
            saveCheckpoint(checkpointIteration, "RUNNING", messages);
        }

        int firstIteration = Math.max(1, runState.iteration + 1);
        OperationKind nextOperation=initialOperation;
        int protocolCorrections=0;
        for (int i = firstIteration; i <= iterationLimit; i++) {
            runState.iteration = i;
            var beforeProvider=steeringSafePoint(
                    SteeringSafePointCoordinator.Candidate.PROVIDER_REQUEST_BEFORE,i);
            if(beforeProvider.pauseExecution())return new LoopExecution(null,i-1,
                    AgentSegmentResult.Outcome.PAUSED,null,messages,log);
            admitTaskProviderBudget();
            heartbeat("PROVIDER_REQUEST");
            List<AgentMessage> providerMessages=messages;
            if(steeringCoordinator!=null&&!(modelProvider instanceof ContextualModelProvider)){
                String constraints=steeringCoordinator.activeConstraintContext();
                if(constraints!=null&&!constraints.isBlank()){
                    providerMessages=new ArrayList<>(messages);
                    providerMessages.add(AgentMessage.userGoal(
                            "Current durable active Constraints (runtime authority):\n"+constraints));
                }
            }
            ModelResponse response = modelProvider instanceof ContextualModelProvider contextual
                    ?contextual.chat(nextOperation,Integer.toString(i),providerMessages,toolDefs,executionGuard)
                    :modelProvider.chat(providerProjection(providerMessages), toolDefs);
            heartbeat("PROVIDER_RESPONSE");
            var afterProvider=steeringSafePoint(
                    SteeringSafePointCoordinator.Candidate.PROVIDER_RESPONSE_BEFORE_TOOL,i);
            if(afterProvider.pauseExecution())return new LoopExecution(null,i,
                    AgentSegmentResult.Outcome.PAUSED,null,messages,log);
            if(afterProvider.applied())continue;

            ModelResponse.ProviderErrorKind providerError = providerError(response);
            if (providerError != null) {
                String diagnostic = providerError.name();
                log.add("PROVIDER_ERROR: " + diagnostic);
                if (persistState) {
                    updateIterations(i);
                    saveCheckpoint(i, "PROVIDER_ERROR", messages);
                    if (manageTaskLifecycle) {
                        persistState(() -> stateStore.tasks.markFailed(taskId, diagnostic));
                    }
                }
                return new LoopExecution(null, i, AgentSegmentResult.Outcome.PROVIDER_ERROR,
                        providerError, messages, log);
            }

            if (response.type() == ResponseType.PROTOCOL_ERROR) {
                ProviderResponseEnvelope envelope = response.providerEnvelope();
                if (envelope == null || envelope.diagnostic().code() == null) {
                    throw new ProviderProtocolException("INVALID_TOOL_CALL_RESPONSE",
                            "INVALID_TOOL_CALL_RESPONSE count=unknown ids=[]");
                }
                String diagnostic = envelope.diagnostic().code()
                        + " count=" + envelope.diagnostic().callCount()
                        + " ids=" + envelope.safeCallIds();
                if(protocolCorrections++==0){
                    AgentMessage correction=AgentMessage.observation(
                            "[ZERO_EFFECT_PROTOCOL_CORRECTION] "+diagnostic
                                    +". Return one valid response shape; no Tool was executed.");
                    messages.add(correction);log.add("OBSERVATION: PROTOCOL_CORRECTION");
                    if(persistState){persistEvent(i,correction);updateIterations(i);
                        saveCheckpoint(i,"RUNNING",messages);}
                    nextOperation=OperationKind.CHAT;
                    continue;
                }
                throw new ProviderProtocolException(envelope.diagnostic().code(),diagnostic);
            }

            if (response.type() == ResponseType.FINAL) {
                String answer = response.content() != null ? response.content() : "";
                if(persistState)answer=safePersistedText(answer);
                try{com.paicli.runtime.FinalResponseContent.requireNotExecutionLedger(answer);}
                catch(IllegalStateException rejected){
                    String code="FINAL_RESPONSE_EXECUTION_LEDGER_REJECTED";
                    if(executionGuard!=null&&response.providerOccurrenceId()!=null)
                        executionGuard.completeOccurrence(
                                com.paicli.identity.CanonicalId.parse(response.providerOccurrenceId()),
                                ToolResult.error(code));
                    throw new ProviderProtocolException(code,code).observedAt(i);
                }
                AgentMessage finalMessage = AgentMessage.finalAnswer(answer);
                messages.add(finalMessage);
                log.add("FINAL: " + (persistState ? safePersistedText(answer) : answer));
                boolean deferredFinalCommit=persistState&&!manageTaskLifecycle
                        &&executionGuard!=null&&planExecution==null
                        &&initialOperation!=OperationKind.FINAL_SYNTHESIS
                        &&initialOperation!=OperationKind.PLAN
                        &&response.providerOccurrenceId()!=null;
                if(!deferredFinalCommit&&response.providerOccurrenceId()!=null
                        &&executionGuard!=null)
                    executionGuard.completeOccurrence(
                            com.paicli.identity.CanonicalId.parse(response.providerOccurrenceId()),
                            ToolResult.success("PROVIDER_RESPONSE_FINAL"));
                if (persistState&&!deferredFinalCommit) {
                    persistEvent(i, finalMessage);
                    updateIterations(i);
                    saveCheckpoint(i, manageTaskLifecycle ? "DONE" : "SEGMENT_COMPLETE", messages);
                    if (manageTaskLifecycle) {
                        persistState(() -> stateStore.tasks.markDone(taskId));
                    }
                }
                return new LoopExecution(answer, i, AgentSegmentResult.Outcome.SUCCESS,
                        null, messages, log,response.providerOccurrenceId());
            }

            if (response.type() == ResponseType.TOOL_CALL && response.toolCall() != null) {
                if(finalResponseOnly)throw new ProviderProtocolException(
                        "FINAL_RESPONSE_TOOL_CALL_NOT_ALLOWED",
                        "FINAL_RESPONSE_TOOL_CALL_NOT_ALLOWED count="
                                +response.toolCalls().size()+" ids=[]");
                // A denied request is a zero-effect Tool result, not a broken Provider protocol.
                // Precheck the entire turn so no earlier batch member can execute first.
                ProviderProtocolException controlDenial=null;
                int deniedOrdinal=0;
                for(int index=0;index<response.toolCalls().size();index++){
                    try{enforceControlDirectoryIntent(response.toolCalls().get(index),goal);}
                    catch(ProviderProtocolException denied){
                        controlDenial=denied;deniedOrdinal=index+1;break;
                    }
                }
                if(controlDenial!=null){
                    var denial=ToolRegistry.PreflightResult.rejected(controlDenial.code(),
                            "Internal control access requires explicit user intent",
                            ToolRegistry.PreflightFailureKind.POLICY_DENIED);
                    String diagnostic=SafePreflightRejectionDiagnostic.from(
                            executionGuard==null?null:executionGuard.current().runId().value(),
                            response.toolCalls().get(deniedOrdinal-1).name(),deniedOrdinal,
                            denial).durableText();
                    rejectPreflightBatchForLocalCorrection(response,i,persistState,messages,log,
                            planExecution,diagnostic,ToolRegistry.PreflightFailureKind.POLICY_DENIED);
                    nextOperation=OperationKind.TOOL_CONTINUATION;
                    continue;
                }
                if(response.toolCalls().size()>1){
                    try{
                        if(effectfulBatch(response.toolCalls(),goal,i,persistState)){
                            rejectEffectfulToolBatch(response,i,persistState,messages,log,
                                    planExecution);
                        }else executeReadOnlyToolBatch(response,i,persistState,messages,log,
                                planExecution,goal);
                    }catch(ProviderProtocolException rejection){
                        ToolRegistry.PreflightFailureKind failureKind=
                                rejection.preflightFailureKind();
                        if(failureKind==ToolRegistry.PreflightFailureKind.MODEL_CORRECTABLE
                                ||failureKind==ToolRegistry.PreflightFailureKind.POLICY_DENIED){
                            try{
                                rejectPreflightBatchForLocalCorrection(response,i,persistState,
                                        messages,log,planExecution,rejection.preflightDiagnostic(),
                                        failureKind);
                            }catch(RuntimeException persistenceFailure){
                                rejection.addSuppressed(persistenceFailure);
                                throw rejection;
                            }
                            nextOperation=OperationKind.TOOL_CONTINUATION;
                            continue;
                        }
                        if(rejection.preflightFailureKind()!=null&&persistState){
                            try{persistEvent(i,AgentMessage.observation(
                                    rejection.preflightDiagnostic()));}
                            catch(RuntimeException persistenceFailure){
                                rejection.addSuppressed(persistenceFailure);
                            }
                        }
                        throw rejection;
                    }
                    var afterBatch=steeringSafePoint(
                            SteeringSafePointCoordinator.Candidate.TOOL_OCCURRENCE_TERMINAL,i);
                    if(afterBatch.pauseExecution())return new LoopExecution(null,i,
                            AgentSegmentResult.Outcome.PAUSED,null,messages,log);
                    nextOperation=OperationKind.TOOL_CONTINUATION;
                    continue;
                }
                ToolCall toolCall = response.toolCall();
                enforceControlDirectoryIntent(toolCall,goal);
                String toolCallId = durableToolCallId(toolCall, i);
                admitPlanToolBatch(planExecution,i,List.of(toolCall),List.of(toolCallId));
                String actionDesc = toolCall.name() + "(" + toolCall.arguments() + ")";
                String publicMessage = response.content()==null?null:safePersistedText(response.content());
                AgentMessage actionMessage = AgentMessage.actionWithToolCall(
                        publicMessage==null||publicMessage.isBlank()?actionDesc:publicMessage,
                        toolCallId, toolCallJson(toolCall, toolCallId,
                                response.privateContinuationRequired()));
                messages.add(actionMessage);
                log.add("ACTION: " + (persistState ? safePersistedAction(actionDesc) : actionDesc));
                if (persistState) {
                    persistEvent(i, actionMessage);
                }
                int observedIteration = i;
                if(publicMessage!=null&&!publicMessage.isBlank()){
                    String bounded=truncate(publicMessage,2048);
                    notifyObserver(()->observer.modelMessage(taskId,observedIteration,
                            "PURPOSE",bounded,planExecution));
                }
                AgentLoopObserver.ToolRequestFact requestFact = requestFact(
                        toolCall,toolCallId,planExecution);
                admitTaskToolBudget(toolCallId);
                String progressKey = progressKey(toolCall, planExecution);
                CachedToolEvidence cached = planExecution==null&&isCacheableRead(toolCall)
                        ? readOnlyEvidence.get(progressKey) : null;
                if(cached != null){
                    if(noProgressRecoveryIssued.contains(progressKey)){
                        throw new AgentNoProgressException(
                                "AGENT_NO_PROGRESS: IDENTICAL_TOOL_LOOP");
                    }
                    noProgressRecoveryIssued.add(progressKey);
                    ToolResult reused = ToolFactProjection.bind(
                            reusedEvidence(cached.result()), toolCallId,toolCall.name());
                    if(persistState)persistToolCall(i,toolCallId,toolCall,reused);
                    AgentLoopObserver.ToolCompletionFact reusedFact=completionFact(reused,0L);
                    notifyObserver(()->observer.toolCompleted(
                            taskId,observedIteration,requestFact,reusedFact));
                    String observation=cachedToolObservation(reused);
                    AgentMessage observationMessage=AgentMessage.observationForToolCall(
                            persistState?safePersistedText(observation):observation,toolCallId);
                    messages.add(observationMessage);
                    log.add("OBSERVATION: IDENTICAL_READ_ONLY_EVIDENCE_REUSED");
                    if(persistState){
                        persistEvent(i,observationMessage);updateIterations(i);
                        saveCheckpoint(i,"RUNNING",messages);
                    }
                    nextOperation=OperationKind.TOOL_CONTINUATION;
                    continue;
                }
                notifyObserver(() -> observer.toolRequested(
                        taskId, observedIteration, requestFact));

                // Pre-execution approval gate
                ToolResult toolResult;
                long toolStarted=System.nanoTime();
                try{toolResult = executeWithApprovalGate(
                        toolCall, i, persistState, requestFact);}
                catch(FencedExecutionGuard.SteeringEffectBlockedException blocked){
                    AgentMessage observation=AgentMessage.observation("Tool launch blocked by durable Steering before external effect.");
                    messages.add(observation);log.add("OBSERVATION: STEERING_BLOCKED_BEFORE_EXTERNAL_EFFECT");
                    if(persistState){persistEvent(i,observation);updateIterations(i);saveCheckpoint(i,"RUNNING",messages);}
                    var steering=steeringSafePoint(
                            SteeringSafePointCoordinator.Candidate.TOOL_OCCURRENCE_TERMINAL,i);
                    if(steering.pauseExecution())return new LoopExecution(null,i,
                            AgentSegmentResult.Outcome.PAUSED,null,messages,log);
                    continue;
                }
                if(!isCacheableRead(toolCall)){
                    // A launched effectful tool may have changed external state even when it
                    // reports failure (for example, a command can write before exiting non-zero).
                    resetNoProgressAfterRealProgress(planExecution,true);
                }else if(toolResult.success()){
                    resetNoProgressAfterRealProgress(planExecution,false);
                    if(planExecution==null&&isCacheableRead(toolCall))readOnlyEvidence.put(progressKey,
                            new CachedToolEvidence(evidenceHash(toolResult),toolResult));
                }
                toolResult=ToolFactProjection.bind(toolResult,toolCallId,toolCall.name());
                if (persistState) {
                    persistToolCall(i, toolCallId, toolCall, toolResult);
                }
                long elapsedMillis=Math.max(0L,(System.nanoTime()-toolStarted)/1_000_000L);
                AgentLoopObserver.ToolCompletionFact completionFact = completionFact(
                        toolResult,elapsedMillis);
                notifyObserver(() -> observer.toolCompleted(
                        taskId, observedIteration, requestFact, completionFact));

                String observation;
                if (toolResult.success()) {
                    observation = durableToolObservation(toolResult);
                } else {
                    observation = "ERROR: " + toolResult.error();
                }
                AgentMessage observationMessage = AgentMessage.observationForToolCall(
                        persistState?safePersistedText(observation):observation, toolCallId);
                messages.add(observationMessage);
                String loggedObservation = persistState ? safePersistedText(observation) : observation;
                log.add("OBSERVATION: " + truncate(loggedObservation, 200));
                if (persistState) {
                    persistEvent(i, observationMessage);
                    updateIterations(i);
                    saveCheckpoint(i, "RUNNING", messages);
                }
                var afterTool=steeringSafePoint(
                        SteeringSafePointCoordinator.Candidate.TOOL_OCCURRENCE_TERMINAL,i);
                if(afterTool.pauseExecution())return new LoopExecution(null,i,
                        AgentSegmentResult.Outcome.PAUSED,null,messages,log);
                nextOperation=OperationKind.TOOL_CONTINUATION;
                continue;
            }

            if (persistState) {
                updateIterations(i);
                saveCheckpoint(i, "RUNNING", messages);
            }
        }

        if (persistState) {
            if (manageTaskLifecycle) {
                String error = "Max iterations reached";
                persistState(() -> stateStore.tasks.markFailed(taskId, error));
                saveCheckpoint(iterationLimit, "FAILED", messages);
            } else {
                saveCheckpoint(iterationLimit, "RUNNING", messages);
            }
        }
        return new LoopExecution(null, iterationLimit, AgentSegmentResult.Outcome.MAX_ITERATIONS,
                null, messages, log);
    }

    private AgentResult toAgentResult(LoopExecution execution) {
        return switch (execution.outcome()) {
            case SUCCESS -> AgentResult.completed(
                    execution.answer(), execution.iteration(), execution.log());
            case MAX_ITERATIONS -> AgentResult.maxIterationsReached(
                    execution.iteration(), execution.log());
            case PROVIDER_ERROR -> AgentResult.providerError(
                    execution.iteration(), execution.log(), execution.providerErrorKind());
            case PAUSED -> AgentResult.maxIterationsReached(
                    execution.iteration(), execution.log());
        };
    }

    private void executeReadOnlyToolBatch(ModelResponse response,int iteration,
            boolean persistState,List<AgentMessage> messages,List<String> log,
            AgentSegmentRequest.PlanExecutionContext planExecution,String userGoal){
        List<ToolCall> calls=response.toolCalls();
        if(calls.size()>ProviderResponseEnvelope.MAX_TOOL_CALLS_PER_ASSISTANT_TURN)
            throw new ProviderProtocolException("TOOL_CALL_BATCH_LIMIT_EXCEEDED",
                    "TOOL_CALL_BATCH_LIMIT_EXCEEDED count="
                    +calls.size());
        List<String> callIds=new ArrayList<>(calls.size());
        Set<String> uniqueIds=new java.util.HashSet<>();
        for(int index=0;index<calls.size();index++){
            ToolCall call=calls.get(index);
            enforceControlDirectoryIntent(call,userGoal);
            String callId=durableToolCallId(call,iteration,index+1);
            if(!uniqueIds.add(callId))throw new ProviderProtocolException(
                    "TOOL_CALL_ID_DUPLICATE","TOOL_CALL_ID_DUPLICATE count="+calls.size());
            ToolRegistry.PreflightResult preflight=toolRegistry.preflight(call);
            if(!preflight.accepted())throw preflightFailure(
                    iteration,index+1,call,preflight,persistState);
            String progressKey=progressKey(call,planExecution);
            if(readOnlyEvidence.containsKey(progressKey)
                    &&noProgressRecoveryIssued.contains(progressKey))
                throw new AgentNoProgressException("AGENT_NO_PROGRESS: IDENTICAL_TOOL_LOOP");
            callIds.add(callId);
            traceBatchCall(iteration,index+1,calls.size(),call,callId,planExecution);
        }
        admitPlanToolBatch(planExecution,iteration,calls,callIds);
        if(executionGuard!=null)executionGuard.validate("TOOL_BATCH_PREFLIGHT");

        String batchId=batchId(callIds);
        AgentLoopObserver.ToolBatchFact batchFact=new AgentLoopObserver.ToolBatchFact(
                batchId,calls.size(),"read-only",planExecution);
        int observedIteration=iteration;

        String publicMessage=response.content()==null?null:safePersistedText(response.content());
        String actionSummary=publicMessage==null||publicMessage.isBlank()
                ?calls.size()+" read-only Tool calls":publicMessage;
        AgentMessage actionMessage=AgentMessage.actionWithToolCall(actionSummary,
                callIds.getFirst(),toolCallJsonBatch(calls,callIds,
                        response.privateContinuationRequired()));
        messages.add(actionMessage);
        log.add("ACTION_BATCH: "+calls.size()+" read-only Tool calls");
        if(persistState)persistEvent(iteration,actionMessage);
        if(publicMessage!=null&&!publicMessage.isBlank()){
            String bounded=truncate(publicMessage,2048);
            notifyObserver(()->observer.modelMessage(taskId,observedIteration,
                    "PURPOSE",bounded,planExecution));
        }
        notifyObserver(()->observer.toolBatchStarted(taskId,observedIteration,batchFact));
        com.paicli.diagnostic.StageTrace.event("TOOL_BATCH_PREFLIGHT_COMPLETED",true,
                "batch_id",batchId,"size",calls.size(),"effect_class","read-only");

        int succeeded=0;
        int failed=0;
        int nextIndex=0;
        try{
            for(;nextIndex<calls.size();nextIndex++){
                control.cancellationToken().throwIfCancellationRequested();
                ToolCall call=calls.get(nextIndex);
                String callId=callIds.get(nextIndex);
                boolean success=executeReadOnlyBatchMember(call,callId,iteration,persistState,
                        messages,log,planExecution,nextIndex+1,calls.size());
                if(success)succeeded++;else failed++;
            }
        }catch(com.paicli.runtime.CancellationToken.TaskCancellationException cancelled){
            for(int index=nextIndex;index<calls.size();index++){
                AgentLoopObserver.ToolRequestFact request=requestFact(calls.get(index),
                        callIds.get(index),planExecution,index+1,calls.size());
                notifyObserver(()->observer.toolCancelled(taskId,observedIteration,request,
                        "not executed because the Tool batch was cancelled"));
            }
            throw cancelled;
        }
        int completedSucceeded=succeeded;
        int completedFailed=failed;
        notifyObserver(()->observer.toolBatchCompleted(taskId,observedIteration,batchFact,
                completedSucceeded,completedFailed));
        com.paicli.diagnostic.StageTrace.event("TOOL_BATCH_COMPLETED",true,
                "batch_id",batchId,"size",calls.size(),"succeeded",succeeded,
                "failed",failed);
    }

    private boolean executeReadOnlyBatchMember(ToolCall toolCall,String toolCallId,int iteration,
            boolean persistState,List<AgentMessage> messages,List<String> log,
            AgentSegmentRequest.PlanExecutionContext planExecution,int batchIndex,int batchSize){
        admitTaskToolBudget(toolCallId);
        AgentLoopObserver.ToolRequestFact requestFact=requestFact(toolCall,toolCallId,
                planExecution,batchIndex,batchSize);
        String progressKey=progressKey(toolCall,planExecution);
        CachedToolEvidence cached=planExecution==null?readOnlyEvidence.get(progressKey):null;
        ToolResult toolResult;
        long elapsedMillis;
        if(cached!=null){
            noProgressRecoveryIssued.add(progressKey);
            toolResult=ToolFactProjection.bind(
                    reusedEvidence(cached.result()),toolCallId,toolCall.name());
            elapsedMillis=0L;
        }else{
            notifyObserver(()->observer.toolRequested(taskId,iteration,requestFact));
            long started=System.nanoTime();
            toolResult=executeWithApprovalGate(toolCall,iteration,persistState,requestFact);
            elapsedMillis=Math.max(0L,(System.nanoTime()-started)/1_000_000L);
            if(toolResult.success()){
                resetNoProgressAfterRealProgress(planExecution,false);
                if(planExecution==null)readOnlyEvidence.put(progressKey,
                        new CachedToolEvidence(evidenceHash(toolResult),toolResult));
            }
            toolResult=ToolFactProjection.bind(toolResult,toolCallId,toolCall.name());
        }
        if(persistState)persistToolCall(iteration,toolCallId,toolCall,toolResult);
        AgentLoopObserver.ToolCompletionFact fact=completionFact(toolResult,elapsedMillis);
        notifyObserver(()->observer.toolCompleted(taskId,iteration,requestFact,fact));
        String observation=toolResult.success()?durableToolObservation(toolResult):
                "ERROR: "+toolResult.error();
        if(cached!=null)observation=cachedToolObservation(toolResult);
        AgentMessage observationMessage=AgentMessage.observationForToolCall(
                persistState?safePersistedText(observation):observation,toolCallId);
        messages.add(observationMessage);
        log.add("OBSERVATION_BATCH: "+truncate(
                persistState?safePersistedText(observation):observation,200));
        if(persistState){
            persistEvent(iteration,observationMessage);
            updateIterations(iteration);
            saveCheckpoint(iteration,"RUNNING",messages);
        }
        return toolResult.success();
    }

    private SteeringSafePointCoordinator.Result steeringSafePoint(
            SteeringSafePointCoordinator.Candidate candidate,int endIteration){
        return steeringCoordinator==null
                ?SteeringSafePointCoordinator.Result.none(com.paicli.state.SteeringStore.Status.NOT_FOUND)
                :steeringCoordinator.check(candidate,endIteration);
    }

    private static ModelResponse.ProviderErrorKind providerError(ModelResponse response) {
        if (response.type() == ResponseType.PROVIDER_ERROR) {
            return response.providerErrorKind();
        }
        if (response.type() == ResponseType.FINAL && response.content() != null
                && response.content().stripLeading().startsWith("[MODEL_ERROR]")) {
            return ModelResponse.ProviderErrorKind.PROVIDER_LEGACY_ERROR;
        }
        return null;
    }

    private static List<AgentMessage> providerProjection(List<AgentMessage> messages) {
        return messages.stream().map(AgentMessage::providerProjection).toList();
    }

    /**
     * Check approval before executing a tool call.
     * If no approval policy is configured, executes directly (v0.1.0 behavior).
     */
    private ToolResult executeWithApprovalGate(ToolCall toolCall, int iteration,
            boolean persistState, AgentLoopObserver.ToolRequestFact requestFact) {
        if(skillRuntime!=null)skillRuntime.validateFrozen(skillRunId);
        // No gate → direct execution (v0.1.0 compatible)
        if (approvalPolicy == null) {
            return invokeFenced(toolCall,iteration,requestFact);
        }

        // Determine overwrite flag for write_file
        boolean overwrite = false;
        if ("write_file".equals(toolCall.name())) {
            Object ov = toolCall.arguments().get("overwrite");
            if (ov instanceof Boolean b) {
                overwrite = b;
            }
        }

        // Evaluate approval
        com.paicli.mcp.approval.McpApprovalGrant.Binding mcpBinding = null;
        if(toolCall.name().startsWith("mcp_") && toolRegistry.lookup(toolCall.name()).isEmpty())
            return ToolResult.error("MCP_TOOL_NOT_REGISTERED");
        if (toolRegistry.lookup(toolCall.name()).orElse(null) instanceof com.paicli.mcp.tool.McpRegisteredTool mcp) {
            if (executionGuard == null) return ToolResult.error("MCP_EXECUTION_AUTHORITY_REQUIRED");
            try { mcpBinding = mcp.prepareApproval(toolCall); }
            catch (com.paicli.mcp.tool.McpToolCallException unavailable) { return ToolResult.error(unavailable.category()); }
        }
        ApprovalDecision decision = approvalPolicy.evaluate(toolCall.name(), overwrite);
        ApprovalDecision policyDecision = decision;

        // Log to audit
        if (auditLog != null) {
            try {
                auditLog.record(decision);
            } catch (IOException e) {
                // Audit log failure does not block execution
            }
        }

        if (persistState) {
            persistApproval(iteration,toolCall,policyDecision);
        }
        notifyObserver(() -> observer.toolDecision(
                taskId, iteration, requestFact, policyDecision));
        ApprovalDecision resolved = control.resolveApproval(
                taskId, iteration, toolCall, decision);
        if (resolved == null
                || !resolved.toolName().equals(decision.toolName())
                || !resolved.taskId().equals(decision.taskId())
                || resolved.riskLevel() != decision.riskLevel()) {
            throw new IllegalStateException("interactive approval identity mismatch");
        }
        if (resolved != decision) {
            decision = resolved;
            if (persistState) {
                ApprovalDecision persistedDecision = decision;
                persistApproval(iteration,toolCall,persistedDecision);
            }
            ApprovalDecision observedDecision = decision;
            notifyObserver(() -> observer.toolDecision(
                    taskId, iteration, requestFact, observedDecision));
        }
        if (decision.decision() == ApprovalDecision.Decision.NEEDS_APPROVAL
                && failOnApprovalRequired()) {
            failReservedPlanOccurrence(requestFact.toolCallId(),"APPROVAL_CHANNEL_UNAVAILABLE");
            throw new ApprovalChannelUnavailableException(
                    "Interactive approval channel is not connected");
        }
        control.cancellationToken().throwIfCancellationRequested();

        // Gate: only ALLOW proceeds
        if (decision.isAllowed()) {
            return invokeFenced(toolCall,iteration,requestFact,mcpBinding,policyDecision,decision);
        }

        // DENY or NEEDS_APPROVAL → fail-closed, do not execute
        if(decision.decision()==ApprovalDecision.Decision.DENY)segmentUserDeniedTool=true;
        String errorType = switch (decision.decision()) {
            case DENY -> "DENIED";
            case NEEDS_APPROVAL -> "APPROVAL_REQUIRED";
            default -> "BLOCKED";
        };
        failReservedPlanOccurrence(requestFact.toolCallId(),errorType);
        return ToolResult.error("[" + errorType + "] " + decision.reason());
    }

    private ToolResult invokeFenced(ToolCall toolCall,int iteration,
            AgentLoopObserver.ToolRequestFact requestFact){
        return invokeFenced(toolCall,iteration,requestFact,null,null,null);
    }

    private ToolResult invokeFenced(ToolCall toolCall,int iteration,
            AgentLoopObserver.ToolRequestFact requestFact,
            com.paicli.mcp.approval.McpApprovalGrant.Binding mcpBinding,
            ApprovalDecision requested, ApprovalDecision resolved){
        if(skillRuntime!=null)skillRuntime.validateFrozen(skillRunId);
        if(executionGuard==null)return skillRuntime==null?toolRegistry.invoke(toolCall)
                :skillRuntime.executeTool(skillRunId,requestFact.toolCallId(),null,toolCall,()->toolRegistry.invoke(toolCall),control.cancellationToken()::cancellationRequested);
        var occurrenceId=planToolReservations.remove(requestFact.toolCallId());
        if(requestFact.planExecution()!=null&&occurrenceId==null)
            throw new ProviderProtocolException("PLAN_TOOL_RESERVATION_MISSING",
                    "PLAN_TOOL_RESERVATION_MISSING");
        if(occurrenceId==null)occurrenceId=requestFact.batchSize()>1
                ?executionGuard.admitOccurrence(iteration,requestFact.batchIndex(),
                        requestFact.toolCallId(),toolCall)
                :executionGuard.admitOccurrence(iteration,requestFact.toolCallId(),toolCall);
        executionGuard.beforeToolExecution();
        executionGuard.revalidateBeforeExternalEffect(occurrenceId,"TOOL");
        ToolResult result;
        if (toolRegistry.lookup(toolCall.name()).orElse(null) instanceof com.paicli.mcp.tool.McpRegisteredTool mcp) {
            if (mcpBinding == null) result=ToolResult.error("MCP_EXPLICIT_APPROVAL_REQUIRED");
            else {
                final var effectId=occurrenceId;
                String run=executionGuard.current().runId().value();
                var grant=com.paicli.mcp.approval.McpApprovalGrant.fromAgentLoop(
                        mcpBinding,taskId,run,effectId.value(),requested,resolved);
                result=mcp.executeApproved(toolCall,grant,taskId,run,effectId.value(),
                        ()->executionGuard.revalidateBeforeExternalEffect(effectId,"TOOL"),control.cancellationToken());
            }
        } else result=skillRuntime==null?toolRegistry.invoke(toolCall)
                :skillRuntime.executeTool(skillRunId,requestFact.toolCallId(),occurrenceId.value(),toolCall,()->toolRegistry.invoke(toolCall),control.cancellationToken()::cancellationRequested);
        if(Boolean.TRUE.equals(result.metadata().get("mcp_unknown_outcome"))
                ||Boolean.TRUE.equals(result.metadata().get("skill_unknown_outcome")))
            executionGuard.unknownOccurrence(occurrenceId,result.error());
        else executionGuard.completeOccurrence(occurrenceId,result);
        return result;
    }

    private void admitPlanToolBatch(AgentSegmentRequest.PlanExecutionContext context,int iteration,
            List<ToolCall> calls,List<String> callIds){
        if(context==null)return;
        if(executionGuard==null||stateStore==null)
            throw new ProviderProtocolException("PLAN_TOOL_ADMISSION_FENCING_REQUIRED",
                    "PLAN_TOOL_ADMISSION_FENCING_REQUIRED");
        List<PlanToolAdmissionService.Candidate> candidates=new ArrayList<>();
        for(int index=0;index<calls.size();index++)
            candidates.add(new PlanToolAdmissionService.Candidate(callIds.get(index),calls.get(index)));
        var admission=new PlanToolAdmissionService(stateStore,toolRegistry,executionGuard)
                .admitBatch(context,iteration,candidates);
        if(!admission.admitted())throw new PlanToolAdmissionService.AdmissionRejectedException(
                PlanToolAdmissionService.publicRejectionCode(admission.status(),calls.size()>1),
                admission.status());
        for(var reservation:admission.reservations())
            planToolReservations.put(reservation.toolCallId(),reservation.occurrenceId());
    }

    private void failReservedPlanOccurrence(String toolCallId,String reason){
        var occurrenceId=planToolReservations.remove(toolCallId);
        if(occurrenceId!=null&&executionGuard!=null)
            executionGuard.failOccurrence(occurrenceId,"PLAN_TOOL_NOT_LAUNCHED_"+reason);
    }

    private void admitTaskToolBudget(String toolCallId){
        TaskToolBudgetAdmission admission;
        try{admission=Objects.requireNonNull(taskToolBudgetGate.evaluate(),"admission");}
        catch(TaskToolBudgetAdmissionException rejected){
            failReservedPlanOccurrence(toolCallId,rejected.code());
            throw rejected;
        }catch(RuntimeException unavailable){
            failReservedPlanOccurrence(toolCallId,"TASK_BUDGET_EVIDENCE_INCOMPLETE");
            throw new TaskToolBudgetAdmissionException(
                    "TASK_BUDGET_EVIDENCE_INCOMPLETE",unavailable);
        }
        if(admission.admitted())return;
        failReservedPlanOccurrence(toolCallId,admission.code());
        throw new TaskToolBudgetAdmissionException(admission.code());
    }

    private void admitTaskProviderBudget(){
        TaskProviderBudgetGate.Admission admission;
        try{
            String activeRunId=executionGuard==null?"ACTIVE_RUN":
                    executionGuard.current().runId().value();
            admission=Objects.requireNonNull(
                    taskProviderBudgetGate.evaluate(taskId,activeRunId),"admission");
        }catch(TaskProviderBudgetGate.AdmissionException rejected){
            throw rejected;
        }catch(RuntimeException unavailable){
            throw new TaskProviderBudgetGate.AdmissionException(
                    "TASK_BUDGET_EVIDENCE_INCOMPLETE",unavailable);
        }
        if(!admission.admitted())
            throw new TaskProviderBudgetGate.AdmissionException(admission.code());
    }

    @FunctionalInterface
    public interface TaskToolBudgetGate {
        TaskToolBudgetGate ALLOW_ALL=()->TaskToolBudgetAdmission.allowed();
        TaskToolBudgetAdmission evaluate();
    }

    public record TaskToolBudgetAdmission(boolean admitted,String code){
        public TaskToolBudgetAdmission{
            if(code==null||code.isBlank())throw new IllegalArgumentException("code is required");
            if(admitted&&!"ADMITTED".equals(code))
                throw new IllegalArgumentException("admitted Tool budget must use ADMITTED");
        }
        public static TaskToolBudgetAdmission allowed(){
            return new TaskToolBudgetAdmission(true,"ADMITTED");
        }
        public static TaskToolBudgetAdmission rejected(String code){
            return new TaskToolBudgetAdmission(false,code);
        }
    }

    public static final class TaskToolBudgetAdmissionException extends RuntimeException{
        private final String code;
        public TaskToolBudgetAdmissionException(String code){this(code,null);}
        public TaskToolBudgetAdmissionException(String code,Throwable cause){
            super(Objects.requireNonNull(code,"code"),cause);this.code=code;
        }
        public String code(){return code;}
    }

    private void heartbeat(String boundary){
        if(executionGuard!=null)executionGuard.heartbeat(boundary);
    }

    private void authorizeWrite(){
        if(executionGuard!=null)executionGuard.validate("AUTHORITATIVE_WRITE");
    }


    private AgentLoopObserver.ToolRequestFact requestFact(ToolCall call) {
        return requestFact(call,null,null);
    }

    private AgentLoopObserver.ToolRequestFact requestFact(ToolCall call,String toolCallId,
            AgentSegmentRequest.PlanExecutionContext planExecution) {
        return requestFact(call,toolCallId,planExecution,1,1);
    }

    private AgentLoopObserver.ToolRequestFact requestFact(ToolCall call,String toolCallId,
            AgentSegmentRequest.PlanExecutionContext planExecution,int batchIndex,int batchSize) {
        Object path = call.arguments().get("path");
        String relativePath = path instanceof String text
                ? safePersistedText(text).replace('\\', '/') : null;
        String signature=call.name()+"\n"+safeArgumentsJson(call.name(),call.arguments());
        int ordinal=requestOrdinals.merge(signature,1,Integer::sum);
        return new AgentLoopObserver.ToolRequestFact(
                safePersistedText(call.name()),
                safeArgumentsJson(call.name(), call.arguments()),
                relativePath,safePersistedText(toolCallId),effectClass(call.name()),
                planExecution,ordinal,batchIndex,batchSize);
    }

    private AgentLoopObserver.ToolCompletionFact completionFact(ToolResult result,
            long elapsedMillis) {
        Map<String, Object> safeMetadata = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : result.metadata().entrySet()) {
            if (safeMetadata.size() >= 16) break;
            Object value = entry.getValue();
            if (value instanceof String text) {
                safeMetadata.put(safePersistedText(entry.getKey()),
                        safePersistedText(text));
            } else if (value instanceof Number || value instanceof Boolean) {
                safeMetadata.put(safePersistedText(entry.getKey()), value);
            }
        }
        String output=safePersistedText(result.output());
        boolean truncated=output!=null&&output.contains("[truncated]");
        int items=output==null||output.isBlank()?0:(int)output.lines()
                .filter(line->!line.isBlank()).count();
        return new AgentLoopObserver.ToolCompletionFact(
                result.success(), safePersistedText(result.error()), safeMetadata,
                truncate(output,4096),elapsedMillis,items,truncated);
    }

    private static String effectClass(String toolName){
        return switch(toolName){
            case "list_files","read_file","grep","git_diff"->"read-only";
            case "write_file"->"workspace write";
            case "run_command"->"process execution";
            default->"tool effect";
        };
    }

    private boolean failOnApprovalRequired() {
        try {
            return observer.failOnApprovalRequired();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private boolean effectfulBatch(List<ToolCall> calls,String userGoal,int iteration,
            boolean persistState){
        if(calls.size()>ProviderResponseEnvelope.MAX_TOOL_CALLS_PER_ASSISTANT_TURN)
            throw new ProviderProtocolException("TOOL_CALL_BATCH_LIMIT_EXCEEDED",
                    "TOOL_CALL_BATCH_LIMIT_EXCEEDED count="
                    +calls.size());
        boolean effectful=false;
        Set<String> uniqueIds=new java.util.HashSet<>();
        for(int index=0;index<calls.size();index++){
            ToolCall call=calls.get(index);
            enforceControlDirectoryIntent(call,userGoal);
            String callId=durableToolCallId(call,iteration,index+1);
            if(!uniqueIds.add(callId))throw new ProviderProtocolException(
                    "TOOL_CALL_ID_DUPLICATE","TOOL_CALL_ID_DUPLICATE count="+calls.size());
            ToolRegistry.PreflightResult preflight=toolRegistry.preflight(call);
            if(!preflight.accepted())throw preflightFailure(
                    iteration,index+1,call,preflight,persistState);
            var projection=preflight.authorityProjection();
            boolean readOnly=projection==null?isCacheableRead(call):
                    projection.effectClass()==com.paicli.state.PlanCompletionModels.EffectClass.READ;
            effectful|=!readOnly;
        }
        return effectful;
    }

    private void rejectEffectfulToolBatch(ModelResponse response,int iteration,
            boolean persistState,List<AgentMessage> messages,List<String> log,
            AgentSegmentRequest.PlanExecutionContext planExecution){
        List<ToolCall> calls=response.toolCalls();
        List<String> callIds=new ArrayList<>(calls.size());
        for(int index=0;index<calls.size();index++)
            callIds.add(durableToolCallId(calls.get(index),iteration,index+1));
        String publicMessage=response.content()==null?null:safePersistedText(response.content());
        String actionSummary=publicMessage==null||publicMessage.isBlank()
                ?calls.size()+" Tool calls rejected for shared serialization":publicMessage;
        AgentMessage actionMessage=AgentMessage.actionWithToolCall(actionSummary,
                callIds.getFirst(),toolCallJsonBatch(calls,callIds,
                        response.privateContinuationRequired()));
        messages.add(actionMessage);
        log.add("ACTION_BATCH_NOT_EXECUTED: "+calls.size()+" Tool calls");
        if(persistState)persistEvent(iteration,actionMessage);
        int observedIteration=iteration;
        for(int index=0;index<calls.size();index++){
            String callId=callIds.get(index);
            AgentMessage result=AgentMessage.observationForToolCall(
                    SharedToolBatchPolicy.notExecutedResult(index+1,calls.size()),callId);
            messages.add(result);
            AgentLoopObserver.ToolRequestFact fact=requestFact(calls.get(index),callId,
                    planExecution,index+1,calls.size());
            notifyObserver(()->observer.toolCancelled(taskId,observedIteration,fact,
                    SharedToolBatchPolicy.NOT_EXECUTED_CODE));
            if(persistState)persistEvent(iteration,result);
        }
        if(persistState){
            updateIterations(iteration);
            saveCheckpoint(iteration,"RUNNING",messages);
        }
        notifyObserver(() -> observer.effectfulBatchNormalized(taskId, observedIteration,
                new AgentLoopObserver.EffectfulBatchFact(true, calls.size(), true,
                        AgentLoopObserver.EffectfulBatchFact.NormalizationOutcome
                                .SERIALIZATION_CONTINUATION_ISSUED)));
    }

    public static String sharedToolBatchPolicyHash(){
        return SharedToolBatchPolicy.policyHash();
    }

    public static String sharedToolBatchTelemetryHash(){
        return SharedToolBatchPolicy.telemetryHash();
    }
    private void notifyObserver(Runnable notification) {
        try {
            notification.run();
        } catch (FencedExecutionGuard.AuthorityLostException lost) {
            throw lost;
        } catch (RuntimeException ignored) {
            // Observability must not alter the AgentLoop state machine.
        }
    }

    private static RuntimeException observedAt(RuntimeException failure,int iteration){
        if(failure instanceof ProviderProtocolException protocol)return protocol.observedAt(iteration);
        if(failure instanceof com.paicli.context.ContextualModelProvider.ContextGenerationException context)
            return context.observedAt(iteration);
        return failure;
    }
    private void initializeTask(String goal) {
        persistState(() -> {
            if (stateStore.tasks.findById(taskId).isEmpty()) {
                stateStore.tasks.createTask(
                        taskId,
                        safePersistedText(goal),
                        safePersistedText(providerName),
                        safePersistedText(modelName),
                        approvalPolicy == null ? null : approvalPolicy.getApprovalMode(),
                        maxIterations
                );
            }
            stateStore.tasks.markRunning(taskId);
        });
    }

    private void requireManagedTask() {
        try {
            if(executionGuard!=null)executionGuard.validate("MANAGED_TASK_READ");
            TaskStore.TaskRecord task = stateStore.tasks.findById(taskId).orElseThrow(
                    () -> new IllegalStateException(
                            "Runner-managed AgentLoop segment requires an existing task: " + taskId));
            if (TaskStore.STATUS_DONE.equals(task.status())
                    || TaskStore.STATUS_FAILED.equals(task.status())) {
                throw new IllegalStateException(
                        "Runner-managed AgentLoop segment cannot run a terminal task: " + taskId);
            }
        }catch(SQLException e){throw new IllegalStateException("Failed to read managed task",e);}
    }

    private void persistEvent(int iteration, AgentMessage message) {
        AgentMessage persisted = safePersistedMessage(message);
        if(executionGuard!=null){executionGuard.appendEvent(iteration,persisted);return;}
        persistState(() -> stateStore.events.append(taskId, iteration, persisted));
    }

    private ProviderProtocolException preflightFailure(int iteration,int batchOrdinal,
            ToolCall call,ToolRegistry.PreflightResult result,boolean persistState){
        String runId=executionGuard==null?null:executionGuard.current().runId().value();
        SafePreflightRejectionDiagnostic diagnostic=
                SafePreflightRejectionDiagnostic.from(runId,call.name(),batchOrdinal,result);
        return ProviderProtocolException.preflight(batchOrdinal,result,
                diagnostic.durableText());
    }

    private void rejectPreflightBatchForLocalCorrection(ModelResponse response,int iteration,
            boolean persistState,List<AgentMessage> messages,List<String> log,
            AgentSegmentRequest.PlanExecutionContext planExecution,String diagnostic,
            ToolRegistry.PreflightFailureKind failureKind){
        List<ToolCall> calls=response.toolCalls();
        String disposition=failureKind==ToolRegistry.PreflightFailureKind.POLICY_DENIED
                ?"POLICY_DENIED_PREFLIGHT":"MODEL_CORRECTABLE_PREFLIGHT";
        String observationKind=failureKind==ToolRegistry.PreflightFailureKind.POLICY_DENIED
                ?"ZERO_EFFECT_POLICY_DENIAL":"ZERO_EFFECT_MODEL_CORRECTION";
        List<ToolCall> safeCalls=calls.stream().map(call->new ToolCall(call.name(),Map.of(),
                call.providerCallId())).toList();
        List<String> callIds=new ArrayList<>(calls.size());
        for(int index=0;index<calls.size();index++)
            callIds.add(durableToolCallId(calls.get(index),iteration,index+1));
        AgentMessage action=AgentMessage.actionWithToolCall(
                "Tool batch rejected before execution",callIds.getFirst(),
                toolCallJsonBatch(safeCalls,callIds,response.privateContinuationRequired()));
        messages.add(action);log.add("ACTION_BATCH_NOT_EXECUTED: "+disposition);
        if(persistState)persistEvent(iteration,action);
        int rejectedOrdinal=SafePreflightRejectionDiagnostic.parse(diagnostic)
                .map(SafePreflightRejectionDiagnostic::batchOrdinal).orElse(1);
        for(int index=0;index<calls.size();index++){
            String callId=callIds.get(index);
            String correction=index+1==rejectedOrdinal?diagnostic:
                    "RELATED_BATCH_MEMBER_NOT_EXECUTED";
            AgentMessage observation=AgentMessage.observationForToolCall(
                    index+1==rejectedOrdinal
                            ?"["+observationKind+"] "+(failureKind==ToolRegistry.PreflightFailureKind.POLICY_DENIED
                                ?"Respect the denied boundary. Use allowed evidence or report the limitation; "
                                :"Correct the Tool name or arguments; ")
                                +"no Tool was executed. "+correction
                            :"["+observationKind+"] "+correction
                                +". No Tool was executed.",callId);
            messages.add(observation);
            if(persistState)persistEvent(iteration,observation);
            int batchIndex=index+1;
            AgentLoopObserver.ToolRequestFact fact=requestFact(calls.get(index),callId,
                    planExecution,batchIndex,calls.size());
            notifyObserver(()->observer.toolCancelled(taskId,iteration,fact,disposition));
        }
        // Rejected arguments may contain credentials or unsafe paths. The durable ACTION retains
        // only Tool names/IDs; the ordered zero-effect observations remain exactly bound to it.
        if(persistState){updateIterations(iteration);saveCheckpoint(iteration,"RUNNING",messages);}
    }

    private void persistToolCall(int iteration,String toolCallId,ToolCall toolCall,ToolResult result) {
        String argumentsJson = safeArgumentsJson(toolCall.name(), toolCall.arguments());
        ToolResult persistedResult = new ToolResult(
                result.success(),
                safePersistedText(durableToolObservation(result)),
                safePersistedText(result.error()),
                Map.of()
        );
        if(executionGuard!=null){executionGuard.recordToolCall(iteration,toolCallId,
                safePersistedText(toolCall.name()),argumentsJson,persistedResult);return;}
        persistState(() -> stateStore.toolCalls.record(
                taskId,
                iteration,
                toolCallId,
                safePersistedText(toolCall.name()),
                argumentsJson,
                persistedResult
        ));
    }

    private String durableToolObservation(ToolResult result){
        return durableToolObservation(result,MAX_PERSISTED_FIELD_LENGTH);
    }

    private String cachedToolObservation(ToolResult result){
        String prefix="[NO_PROGRESS_RECOVERY] Identical read-only evidence was reused. "
                +"Do not repeat this call. Choose the next Plan step, request different "
                +"evidence, or provide the grounded result.\n\n";
        return prefix+durableToolObservation(result,MAX_PERSISTED_FIELD_LENGTH-prefix.length());
    }

    private String durableToolObservation(ToolResult result,int limit){
        String output=result.output()==null?"":result.output();
        if(stateStore!=null)output=safePersistedText(output);
        StringBuilder facts=new StringBuilder();
        for(String key:List.of("exitCode","bytesRead","bytesWritten","matchCount")){
            Object fact=result.metadata().get(key);
            if(!(fact instanceof Number)&&!(fact instanceof Boolean))continue;
            facts.append('\n').append("[tool fact: ").append(key).append('=').append(fact).append(']');
        }
        String suffix=ToolFactProjection.appendCanonicalFacts(facts.toString(),result);
        if(!output.isEmpty()&&!output.endsWith("\n")&&!suffix.isEmpty()&&!suffix.startsWith("\n"))suffix="\n"+suffix;
        if(stateStore!=null&&output.length()+suffix.length()>limit){
            int available=limit-suffix.length();
            String marker="... (truncated)";
            if(available<marker.length())throw new IllegalStateException("TOOL_FACTS_EXCEED_OBSERVATION_BUDGET");
            output=output.substring(0,Math.min(output.length(),available-marker.length()))+marker;
        }
        if(output.endsWith("\n")&&suffix.startsWith("\n"))suffix=suffix.substring(1);
        return output+suffix;
    }

    /** Stable durable correlation for legacy providers that expose no usable call identity. */
    private String durableToolCallId(ToolCall toolCall,int iteration){
        return durableToolCallId(toolCall,iteration,1);
    }

    private String durableToolCallId(ToolCall toolCall,int iteration,int batchIndex){
        if(toolCall.providerCallId()!=null&&!toolCall.providerCallId().isBlank())
            return safePersistedText(toolCall.providerCallId());
        String owner=executionGuard==null?taskId:executionGuard.current().runId().value();
        return owner+":"+iteration+":"+batchIndex+":"
                +FencedExecutionGuard.canonicalToolCallHash(toolCall).substring(0,16);
    }

    private String toolCallJson(ToolCall call,String callId,boolean privateContinuationRequired){
        try{
            ObjectNode root=MAPPER.createObjectNode();
            root.put("id",callId);root.put("type","function");
            if(privateContinuationRequired)root.put("x_paicli_private_continuation",true);
            ObjectNode function=root.putObject("function");
            function.put("name",call.name());
            function.put("arguments",safeArgumentsJson(call.name(),call.arguments()));
            return MAPPER.writeValueAsString(root);
        }catch(JsonProcessingException error){
            throw new ProviderProtocolException("TOOL_CALL_CONTINUATION_SERIALIZATION_FAILED",
                    "TOOL_CALL_CONTINUATION_SERIALIZATION_FAILED");
        }
    }

    private String toolCallJsonBatch(List<ToolCall> calls,List<String> callIds,
            boolean privateContinuationRequired){
        try{
            ArrayNode array=MAPPER.createArrayNode();
            for(int index=0;index<calls.size();index++){
                ToolCall call=calls.get(index);
                ObjectNode root=array.addObject();
                root.put("id",callIds.get(index));root.put("type","function");
                if(privateContinuationRequired)
                    root.put("x_paicli_private_continuation",true);
                ObjectNode function=root.putObject("function");
                function.put("name",call.name());
                function.put("arguments",safeArgumentsJson(call.name(),call.arguments()));
            }
            return MAPPER.writeValueAsString(array);
        }catch(JsonProcessingException error){
            throw new ProviderProtocolException("TOOL_CALL_BATCH_SERIALIZATION_FAILED",
                    "TOOL_CALL_BATCH_SERIALIZATION_FAILED");
        }
    }

    private String batchId(List<String> callIds){
        try{
            String joined=String.join("\n",callIds);
            return "batch-"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(joined.getBytes(StandardCharsets.UTF_8)),0,8);
        }catch(Exception error){throw new IllegalStateException(
                "Tool batch identity cannot be generated",error);}
    }

    private void traceBatchCall(int iteration,int index,int size,ToolCall call,String callId,
            AgentSegmentRequest.PlanExecutionContext context){
        Object target=call.arguments().get("path");
        String argumentsHash=com.paicli.diagnostic.StageTrace.hashArguments(
                List.of(safeArgumentsJson(call.name(),call.arguments())));
        String stepHash=context==null?"<none>":com.paicli.diagnostic.StageTrace.hashArguments(
                List.of(context.stepRevisionId().value()));
        com.paicli.diagnostic.StageTrace.event("TOOL_BATCH_CALL_PREFLIGHT",false,
                "iteration",iteration,"index",index,"size",size,
                "function_name",safePersistedText(call.name()),
                "call_id_hash",com.paicli.diagnostic.StageTrace.hashArguments(List.of(callId)),
                "arguments_hash",argumentsHash,
                "target",target instanceof String text?safePersistedText(text):"<none>",
                "effect_class",effectClass(call.name()),"plan_step_hash",stepHash);
    }

    private String progressKey(ToolCall call,
            AgentSegmentRequest.PlanExecutionContext context){
        return progressScope(context)+call.name()+"\n"
                +FencedExecutionGuard.canonicalToolCallHash(call);
    }

    private String progressScope(AgentSegmentRequest.PlanExecutionContext context){
        String authority=context==null?String.valueOf(taskId):
                context.planRevisionId().value()+"\n"+context.stepRevisionId().value();
        return authority+"\n";
    }

    private void resetNoProgressAfterRealProgress(
            AgentSegmentRequest.PlanExecutionContext context,boolean invalidateReadEvidence){
        String scope=progressScope(context);
        noProgressRecoveryIssued.removeIf(key->key.startsWith(scope));
        if(invalidateReadEvidence)readOnlyEvidence.keySet().removeIf(key->key.startsWith(scope));
    }

    private static boolean isCacheableRead(ToolCall call){
        return Set.of("list_files","read_file","grep","git_diff").contains(call.name());
    }

    private static void enforceControlDirectoryIntent(ToolCall call,String userGoal){
        if(!Set.of("list_files","read_file","grep").contains(call.name())
                ||!Boolean.TRUE.equals(call.arguments().get("include_internal")))return;
        String normalized=userGoal==null?"":userGoal.toLowerCase(Locale.ROOT);
        boolean namesControlDirectory=normalized.contains(".paicli");
        boolean explicitlyRequestsAccess=normalized.matches(
                "(?s).*(?:include|inspect|show|list|read|查看|读取|列出|显示|包含).*\\.paicli.*")
                ||normalized.matches(
                "(?s).*\\.paicli.*(?:include|inspect|show|list|read|查看|读取|列出|显示|包含).*");
        boolean explicitlyExcludes=normalized.matches(
                "(?s).*(?:ignore|exclude|omit|skip|忽略|排除|跳过|不要|不包含).*\\.paicli.*")
                ||normalized.matches(
                "(?s).*\\.paicli.*(?:ignore|exclude|omit|skip|忽略|排除|跳过|不要|不包含).*");
        if(!namesControlDirectory||!explicitlyRequestsAccess||explicitlyExcludes)
            throw new ProviderProtocolException("CONTROL_DIRECTORY_ACCESS_NOT_USER_AUTHORIZED",
                    "CONTROL_DIRECTORY_ACCESS_NOT_USER_AUTHORIZED");
    }

    private ToolResult reusedEvidence(ToolResult source){
        Map<String,Object> metadata=new java.util.LinkedHashMap<>(source.metadata());
        metadata.put("reusedEvidence",true);
        metadata.put("reuseReason","identical read-only call");
        return new ToolResult(source.success(),source.output(),source.error(),metadata);
    }

    private String evidenceHash(ToolResult result){
        try{
            String normalized=MAPPER.writeValueAsString(java.util.Map.of(
                    "success",result.success(),"output",String.valueOf(result.output()),
                    "error",String.valueOf(result.error()),"metadata",result.metadata()));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8)));
        }catch(Exception error){throw new IllegalStateException(
                "Tool evidence cannot be normalized",error);}
    }

    private void updateIterations(int iteration) {
        if(executionGuard!=null){executionGuard.updateTaskIterations(iteration);return;}
        persistState(() -> stateStore.tasks.updateIterations(taskId, iteration));
    }

    private void saveCheckpoint(int iteration, String status, List<AgentMessage> messages) {
        List<AgentMessage> persistedMessages = messages.stream()
                .map(this::safePersistedMessage)
                .toList();
        if(executionGuard!=null){executionGuard.saveCheckpoint(iteration,status,persistedMessages);return;}
        persistState(() -> stateStore.checkpoints.save(taskId, iteration, status, persistedMessages));
    }

    private void persistApproval(int iteration,ToolCall toolCall,ApprovalDecision decision){
        String intentHash=FencedExecutionGuard.canonicalToolCallHash(toolCall);
        if(executionGuard!=null){executionGuard.recordApproval(iteration,null,
                safePersistedText(decision.toolName()),decision.riskLevel().name(),
                decision.decision().name(),safePersistedText(decision.reason()),intentHash);return;}
        persistState(() -> stateStore.approvals.record(taskId,iteration,null,
                safePersistedText(decision.toolName()),decision.riskLevel().name(),
                decision.decision().name(),safePersistedText(decision.reason()),intentHash));
    }

    private void persistFailure(RunState runState, RuntimeException failure) {
        if(failure instanceof FencedExecutionGuard.AuthorityLostException)return;
        String error = safePersistedText(failure.getClass().getSimpleName() + ": " + failure.getMessage());
        try {
            List<AgentMessage> safe=runState.messages.stream().map(this::safePersistedMessage).toList();
            if(executionGuard!=null){executionGuard.saveCheckpoint(runState.iteration,"FAILED",safe);}
            else{stateStore.tasks.markFailed(taskId,error);stateStore.checkpoints.save(
                    taskId,runState.iteration,"FAILED",safe);}
        } catch (SQLException | IOException | RuntimeException persistenceFailure) {
            failure.addSuppressed(persistenceFailure);
        }
    }

    private void persistSegmentFailure(RunState runState, RuntimeException failure) {
        if(failure instanceof FencedExecutionGuard.AuthorityLostException)return;
        try {
            List<AgentMessage> safe=runState.messages.stream().map(this::safePersistedMessage).toList();
            if(executionGuard!=null)executionGuard.saveCheckpoint(
                    runState.iteration,"SEGMENT_FAILED",safe);
            else stateStore.checkpoints.save(taskId,runState.iteration,"SEGMENT_FAILED",safe);
        } catch (SQLException | IOException | RuntimeException persistenceFailure) {
            failure.addSuppressed(persistenceFailure);
        }
    }

    private AgentMessage safePersistedMessage(AgentMessage message) {
        return new AgentMessage(message.role(),
                message.type() == AgentMessage.MessageType.ACTION
                        ? safePersistedAction(message.content())
                        : safePersistedText(message.content()),
                message.type(), safePersistedText(message.toolCallId()),
                safePersistedText(message.toolCallJson()));
    }

    private String safePersistedAction(String action) {
        if (action != null && action.startsWith("write_file(")) {
            return "write_file([arguments redacted; content not persisted])";
        }
        return safePersistedText(action);
    }

    private String safeArgumentsJson(String toolName, Map<String, Object> arguments) {
        try {
            JsonNode node = MAPPER.valueToTree(arguments == null ? Map.of() : arguments);
            if ("write_file".equals(toolName) && node.isObject()
                    && node.get("content") != null && node.get("content").isTextual()) {
                String content = node.get("content").textValue();
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                String hash = HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(bytes));
                ((ObjectNode) node).put("content",
                        "<redacted sha256=" + hash + " bytes=" + bytes.length + ">");
            }
            redactSensitiveJson(node);
            String sanitizedJson = MAPPER.writeValueAsString(node);
            int originalBytes = sanitizedJson.getBytes(StandardCharsets.UTF_8).length;
            if (originalBytes <= MAX_PERSISTED_FIELD_LENGTH) {
                return sanitizedJson;
            }
            return argumentsSummary("tool arguments too large", originalBytes);
        } catch (JsonProcessingException | IllegalArgumentException | java.security.NoSuchAlgorithmException e) {
            return argumentsSummary("tool arguments could not be serialized", -1);
        }
    }

    private String argumentsSummary(String reason, int originalBytes) {
        ObjectNode summary = MAPPER.createObjectNode();
        summary.put("_truncated", true);
        summary.put("_reason", reason);
        summary.put("_original_bytes", originalBytes);
        return summary.toString();
    }

    private String safePersistedText(String value) {
        if (value == null) {
            return null;
        }
        StringBuilder safe=new StringBuilder();int offset=0;
        // Bound attempts on malformed bracket-heavy input. Every complete JSON
        // object/array, including those in a trailer, gets decoded-key redaction.
        for(int attempts=0;offset<value.length()&&attempts<256;attempts++){
            int object=value.indexOf('{',offset),array=value.indexOf('[',offset);
            int start=object<0?array:array<0?object:Math.min(object,array);
            if(start<0){safe.append(sanitizeScalarText(value.substring(offset)));offset=value.length();break;}
            safe.append(sanitizeScalarText(value.substring(offset,start)));
            try(var parser=MAPPER.getFactory().createParser(value.substring(start))){
                JsonNode node=MAPPER.readTree(parser);
                int consumed=Math.toIntExact(parser.currentLocation().getCharOffset());
                if(node!=null&&(node.isObject()||node.isArray())&&consumed>0){
                    redactSensitiveJson(node);safe.append(MAPPER.writeValueAsString(node));
                    offset=start+consumed;continue;
                }
            }catch(IOException ignored){
                // A prose bracket is not automatically JSON or a trusted fact marker.
            }
            safe.append(value.charAt(start));offset=start+1;
        }
        if(offset<value.length())safe.append("[redacted: structured text scan limit]");
        return sanitizeScalarText(new com.paicli.runtime.PersistenceSanitizer().sanitize(safe.toString()));
    }

    private String sanitizeScalarText(String value) {
        if (RAW_HTTP_MESSAGE.matcher(value.stripLeading()).matches()) {
            return "[redacted HTTP message]";
        }
        String sanitized = value;
        sanitized = SENSITIVE_HEADER.matcher(sanitized).replaceAll("$1<redacted>");
        sanitized = SENSITIVE_ASSIGNMENT.matcher(sanitized).replaceAll("$1<redacted>");
        sanitized = BEARER_TOKEN.matcher(sanitized).replaceAll("Bearer <redacted>");
        return truncatePersisted(sanitized);
    }

    private void redactSensitiveJson(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            List<String> fieldNames = new ArrayList<>();
            object.fieldNames().forEachRemaining(fieldNames::add);
            for (String fieldName : fieldNames) {
                String normalized = fieldName.replace("_", "").replace("-", "")
                        .toLowerCase(Locale.ROOT);
                if (SENSITIVE_FIELDS.contains(normalized)) {
                    object.put(fieldName, "<redacted>");
                } else if (object.get(fieldName).isTextual()) {
                    object.put(fieldName, sanitizeScalarText(object.get(fieldName).asText()));
                } else {
                    redactSensitiveJson(object.get(fieldName));
                }
            }
        } else if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int index = 0; index < array.size(); index++) {
                JsonNode child = array.get(index);
                if (child.isTextual()) {
                    array.set(index, MAPPER.getNodeFactory().textNode(sanitizeScalarText(child.asText())));
                } else {
                    redactSensitiveJson(child);
                }
            }
        }
    }

    private String truncatePersisted(String value) {
        if (value.length() <= MAX_PERSISTED_FIELD_LENGTH) {
            return value;
        }
        String suffix = "... (truncated)";
        return value.substring(0, MAX_PERSISTED_FIELD_LENGTH - suffix.length()) + suffix;
    }

    private void persistState(StateOperation operation) {
        if(executionGuard!=null)throw new IllegalStateException(
                "Recovery authoritative writes require an operation-specific fenced mutation");
        try {
            authorizeWrite();
            operation.run();
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("Failed to persist agent state", e);
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        if (maxLen <= 3) return s.substring(0, maxLen);
        return s.substring(0, maxLen - 3) + "...";
    }

    @FunctionalInterface
    private interface StateOperation {
        void run() throws SQLException, IOException;
    }

    private static final class RunState {
        private final List<AgentMessage> messages = new ArrayList<>();
        private final List<String> log = new ArrayList<>();
        private int iteration;

        private RunState() {
        }

        private RunState(List<AgentMessage> messages, int iteration) {
            this.messages.addAll(messages);
            this.iteration = iteration;
        }
    }

    private record LoopExecution(
            String answer,
            int iteration,
            AgentSegmentResult.Outcome outcome,
            ModelResponse.ProviderErrorKind providerErrorKind,
            List<AgentMessage> conversation,
            List<String> log,
            String providerOccurrenceId
    ) {
        private LoopExecution(String answer,int iteration,AgentSegmentResult.Outcome outcome,
                ModelResponse.ProviderErrorKind providerErrorKind,
                List<AgentMessage> conversation,List<String> log){
            this(answer,iteration,outcome,providerErrorKind,conversation,log,null);
        }
    }

    private record CachedToolEvidence(String evidenceHash,ToolResult result){ }

    public static final class ApprovalChannelUnavailableException
            extends RuntimeException {
        public ApprovalChannelUnavailableException(String message) {
            super(message);
        }
    }

    public static final class ProviderProtocolException extends RuntimeException
            implements com.paicli.runtime.ObservedIterationFailure {
        private final String code;
        private final String preflightCode;
        private final com.paicli.tool.ToolRegistry.PreflightFailureKind preflightFailureKind;
        private final String preflightDiagnostic;
        private final int observedIteration;

        public ProviderProtocolException(String code,String diagnostic) {
            this(code,diagnostic,null,null,null,-1,null);
        }

        private ProviderProtocolException(String code,String diagnostic,String preflightCode,
                com.paicli.tool.ToolRegistry.PreflightFailureKind preflightFailureKind,
                String preflightDiagnostic,int observedIteration,Throwable cause) {
            super(diagnostic,cause);
            if(code==null||code.isBlank())throw new IllegalArgumentException(
                    "Typed Provider protocol code is required");
            if(observedIteration < -1)throw new IllegalArgumentException(
                    "Observed iteration must be non-negative when present");
            this.code=code;
            this.preflightCode=preflightCode;
            this.preflightFailureKind=preflightFailureKind;
            this.preflightDiagnostic=preflightDiagnostic;
            this.observedIteration=observedIteration;
        }

        public static ProviderProtocolException preflight(int index,
                com.paicli.tool.ToolRegistry.PreflightResult result,String safeDiagnostic){
            return new ProviderProtocolException("TOOL_CALL_BATCH_PREFLIGHT_FAILED",
                    "TOOL_CALL_BATCH_PREFLIGHT_FAILED index="+index+" code="+result.code(),
                    result.code(),result.failureKind(),Objects.requireNonNull(safeDiagnostic),
                    -1,null);
        }
        public String code(){return code;}
        public String preflightCode(){return preflightCode;}
        public com.paicli.tool.ToolRegistry.PreflightFailureKind preflightFailureKind(){
            return preflightFailureKind;
        }
        public String preflightDiagnostic(){return preflightDiagnostic;}
        public java.util.OptionalInt observedIteration(){
            return observedIteration<0?java.util.OptionalInt.empty():
                    java.util.OptionalInt.of(observedIteration);
        }
        private ProviderProtocolException observedAt(int iteration){
            if(iteration<0)throw new IllegalArgumentException("Observed iteration cannot be negative");
            if(observedIteration>=iteration)return this;
            return new ProviderProtocolException(code,getMessage(),preflightCode,
                    preflightFailureKind,preflightDiagnostic,iteration,this);
        }
    }

    public static final class AgentNoProgressException extends RuntimeException {
        public AgentNoProgressException(String diagnostic) { super(diagnostic); }
    }
}
