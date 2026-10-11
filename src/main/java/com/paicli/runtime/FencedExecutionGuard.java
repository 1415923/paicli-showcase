package com.paicli.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.paicli.agent.AgentSegmentRequest;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.model.ToolCall;
import com.paicli.state.R1aDurableModels.SideEffectOccurrence;
import com.paicli.state.R1aStoreResults.OwnershipResult;
import com.paicli.state.R1aStoreResults.FencedMutationResult;
import com.paicli.state.R1aStoreResults.FencedValueResult;
import com.paicli.model.AgentMessage;
import com.paicli.state.StateStore;
import com.paicli.state.PlanExecutionModels.Attempt;
import com.paicli.state.PlanExecutionModels.Outcome;
import com.paicli.state.PlanExecutionStore;
import com.paicli.state.PlanCompletionStore;
import com.paicli.tool.ToolResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.List;
import com.paicli.state.SkillSelectionStore;
import com.paicli.state.SkillLoadStore;
import java.util.concurrent.atomic.AtomicReference;

/** Synchronous Recovery execution fence. No background lease policy is introduced. */
public final class FencedExecutionGuard {
    private static final ObjectMapper CANONICAL_JSON=new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true);
    private final StateStore stateStore;
    private final AtomicReference<TaskExecutionAuthority> authority;
    private final Duration leaseDuration;
    private final TimeSource timeSource;
    private final TestCoordinator testCoordinator;

    public FencedExecutionGuard(StateStore stateStore,TaskExecutionAuthority authority,
            Duration leaseDuration,TimeSource timeSource){
        this(stateStore,authority,leaseDuration,timeSource,TestCoordinator.NOOP);
    }

    FencedExecutionGuard(StateStore stateStore,TaskExecutionAuthority authority,
            Duration leaseDuration,TimeSource timeSource,TestCoordinator testCoordinator){
        this.stateStore=Objects.requireNonNull(stateStore);
        this.authority=new AtomicReference<>(Objects.requireNonNull(authority));
        if(leaseDuration==null||leaseDuration.isZero()||leaseDuration.isNegative())
            throw new IllegalArgumentException("Explicit positive lease duration is required");
        this.leaseDuration=leaseDuration;this.timeSource=Objects.requireNonNull(timeSource);
        this.testCoordinator=Objects.requireNonNull(testCoordinator);
    }

    public static FencedExecutionGuard system(StateStore store,TaskExecutionAuthority authority){
        return new FencedExecutionGuard(store,authority,
                Duration.between(authority.acquiredAt(),authority.expiresAt()),Instant::now);
    }

    public TaskExecutionAuthority current(){return authority.get();}

    public void heartbeat(String boundary){
        TaskExecutionAuthority before=authority.get();Instant now=timeSource.now();
        validate(before,now,boundary);
        OwnershipResult result;
        try{result=stateStore.r1aDurable.heartbeatOwnership(before.taskId(),
                before.ownerInstanceId(),before.runId(),before.leaseToken(),before.casVersion(),
                now,now.plus(leaseDuration));}
        catch(SQLException e){throw new AuthorityLostException(
                "EXECUTION_AUTHORITY_PERSISTENCE_FAILURE",boundary,e);}
        if(result!=OwnershipResult.SUCCESS)throw rejected(result,boundary);
        try{
            var lease=stateStore.r1aDurable.readOwnership(before.taskId()).orElseThrow();
            TaskExecutionAuthority updated=TaskExecutionAuthority.fromLease(lease,before.kind());
            if(!updated.runId().equals(before.runId())
                    ||updated.fencingToken()!=before.fencingToken())
                throw new AuthorityLostException("EXECUTION_AUTHORITY_CHANGED",boundary,null);
            authority.set(updated);
        }catch(SQLException e){throw new AuthorityLostException(
                "EXECUTION_AUTHORITY_PERSISTENCE_FAILURE",boundary,e);}
    }

    public void validate(String boundary){validate(authority.get(),timeSource.now(),boundary);}

    public CanonicalId admitOccurrence(int iteration,ToolCall call){
        return admitOccurrence(iteration,null,call);
    }

    public CanonicalId admitOccurrence(int iteration,String toolCallId,ToolCall call){
        return admitOperationOccurrence(Integer.toString(iteration),
                "recovery-tool:"+current().runId().value()+":"+iteration,
                "TOOL",call.name(),hash(call),toolCallId);
    }

    /** Admits one lazily executed member of a deterministic sequential Tool batch. */
    public CanonicalId admitOccurrence(int iteration,int siblingOrdinal,ToolCall call){
        return admitOccurrence(iteration,siblingOrdinal,null,call);
    }

    public CanonicalId admitOccurrence(int iteration,int siblingOrdinal,String toolCallId,
            ToolCall call){
        if(siblingOrdinal<1)
            throw new IllegalArgumentException("Tool batch sibling ordinal must be positive");
        validate("TOOL_BATCH_SIBLING_ADMISSION");
        return admitOperationOccurrence(Integer.toString(iteration),
                "recovery-tool:"+current().runId().value()+":"+iteration+":"+siblingOrdinal,
                "TOOL",call.name(),hash(call),toolCallId);
    }

    public CanonicalId admitOperationOccurrence(String iteration,String key,String type,
            String target,String inputHash){
        return admitOperationOccurrence(iteration,key,type,target,inputHash,null);
    }

    private CanonicalId admitOperationOccurrence(String iteration,String key,String type,
            String target,String inputHash,String toolCallId){
        TaskExecutionAuthority current=authority.get();
        try{
            var task=stateStore.r1aDurable.findTask(current.taskId()).orElseThrow();
            CanonicalId occurrenceId=CanonicalId.newId(EntityFamily.OCCURRENCE);
            SideEffectOccurrence occurrence=new SideEffectOccurrence(occurrenceId,task.threadId(),
                    current.taskId(),current.runId(),iteration,key,toolCallId,
                    type,target,"STARTED",inputHash,null,null,null,timeSource.now(),null);
            testCoordinator.before("OCCURRENCE_ADMISSION");
            FencedMutationResult result=stateStore.fencedMutations.admitOccurrence(
                    current.snapshot(),timeSource.now(),occurrence);
            require(result,"OCCURRENCE_ADMISSION");
            return occurrenceId;
        }catch(SQLException e){throw new AuthorityLostException(
                "OCCURRENCE_ADMISSION_PERSISTENCE_FAILURE","OCCURRENCE_ADMISSION",e);}
    }

    public void beforeToolExecution(){heartbeat("TOOL_EXECUTION");}

    public void revalidateBeforeExternalEffect(CanonicalId occurrenceId,String effectKind){
        revalidateBeforeExternalEffect(occurrenceId,effectKind,null);
    }

    public void revalidateBeforeExternalEffect(CanonicalId occurrenceId,String effectKind,
            String producerIdentity){
        testCoordinator.before("EXTERNAL_EFFECT_"+effectKind);
        FencedMutationResult result=stateStore.fencedMutations.validateEffectLaunch(current().snapshot(),
                timeSource.now(),occurrenceId,effectKind,producerIdentity);
        if(result==FencedMutationResult.STEERING_BLOCKED)
            throw new SteeringEffectBlockedException(effectKind,producerIdentity);
        require(result,"EXTERNAL_EFFECT_"+effectKind);
    }

    public void completeOccurrence(CanonicalId occurrenceId,ToolResult result){
        FencedMutationResult transitioned=stateStore.fencedMutations.transitionOccurrence(
                current().snapshot(),timeSource.now(),occurrenceId,
                result.success()?"COMPLETED":"FAILED",
                hash(result.success()?result.output():result.error()),
                result.success()?"KNOWN_SUCCESS":"KNOWN_FAILURE");
        require(transitioned,"OCCURRENCE_COMPLETION");
    }

    public void failOccurrence(CanonicalId occurrenceId,String detail){
        FencedMutationResult transitioned=stateStore.fencedMutations.transitionOccurrence(
                current().snapshot(),timeSource.now(),occurrenceId,"FAILED",hash(detail),
                "KNOWN_FAILURE");
        require(transitioned,"OCCURRENCE_FAILURE");
    }

    public void unknownOccurrence(CanonicalId occurrenceId,String detail){
        FencedMutationResult transitioned=stateStore.fencedMutations.transitionOccurrence(
                current().snapshot(),timeSource.now(),occurrenceId,"UNKNOWN",hash(detail),
                "UNKNOWN");
        require(transitioned,"OCCURRENCE_UNKNOWN");
    }

    public static String canonicalInputHash(String value){return hash(value);}
    public static String canonicalToolCallHash(ToolCall call){return hash(call);}

    public void prepareTaskForResume(){require(stateStore.fencedMutations.prepareTaskForResume(
            current().snapshot(),timeSource.now()),"TASK_PREPARE_FOR_RESUME_WRITE");}
    public void createCodingRun(String command,int repairs,int iteration){require(
            stateStore.fencedMutations.createCodingRun(current().snapshot(),timeSource.now(),
                    command,repairs,iteration),"CODING_RUN_CREATE");}
    public void createCodingRun(String phase,String command,int repairs,int iteration){require(
            stateStore.fencedMutations.createCodingRun(current().snapshot(),timeSource.now(),
                    phase,command,repairs,iteration),"CODING_RUN_CREATE");}
    public void updateRuntimeManifest(String skills,String hooks){require(
            stateStore.fencedMutations.updateRuntimeManifest(current().snapshot(),timeSource.now(),
                    skills,hooks),"RUNTIME_MANIFEST_WRITE");}
    public void markTaskRunning(){require(stateStore.fencedMutations.markTaskRunning(
            current().snapshot(),timeSource.now()),"TASK_RUNNING_WRITE");}
    public void updateTaskIterations(int iteration){require(
            stateStore.fencedMutations.updateTaskIterations(current().snapshot(),timeSource.now(),
                    iteration),"TASK_ITERATION_WRITE");}
    public void appendEvent(int iteration,AgentMessage message){
        testCoordinator.before("EVENT_WRITE");require(
            stateStore.fencedMutations.appendEvent(current().snapshot(),timeSource.now(),iteration,
                    message),"EVENT_WRITE");}
    public void recordToolCall(int iteration,String callId,String name,String arguments,
            ToolResult result){require(stateStore.fencedMutations.recordToolCall(current().snapshot(),
                    timeSource.now(),iteration,callId,name,arguments,result),"TOOL_RESULT_WRITE");}
    public void recordApproval(Integer iteration,String callId,String name,String risk,
            String decision,String reason){require(stateStore.fencedMutations.recordApproval(
                    current().snapshot(),timeSource.now(),iteration,callId,name,risk,decision,reason),
                    "APPROVAL_WRITE");}

    public com.paicli.state.PlanCompletionStore.GateResult evaluatePlanCompletion(
            CanonicalId revisionId){
        return stateStore.planCompletions.evaluate(current().snapshot(),timeSource.now(),revisionId);
    }
    public void recordApproval(Integer iteration,String callId,String name,String risk,
            String decision,String reason,String intentHash){require(
                    stateStore.fencedMutations.recordApproval(current().snapshot(),timeSource.now(),
                            iteration,callId,name,risk,decision,reason,intentHash),
                    "APPROVAL_WRITE");}
    public void saveCheckpoint(int iteration,String status,List<AgentMessage> messages){
        try{require(stateStore.fencedMutations.saveCheckpoint(current().snapshot(),timeSource.now(),
                iteration,status,CANONICAL_JSON.writeValueAsString(messages)),"CHECKPOINT_WRITE");}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalArgumentException(
                "Checkpoint messages cannot be serialized",e);}
    }
    public void updateLastIteration(int iteration){require(
            stateStore.fencedMutations.updateLastIteration(current().snapshot(),timeSource.now(),iteration),
            "ITERATION_WRITE");}
    public void transitionRunPhase(String expected,String next){require(
            stateStore.fencedMutations.transitionRunPhase(current().snapshot(),timeSource.now(),
                    expected,next),"PHASE_WRITE");}
    public void acceptProviderFinal(CanonicalId occurrenceId,int iteration,String expectedPhase,
            String content,String finalCandidateDigest){require(
            stateStore.fencedMutations.acceptProviderFinal(current().snapshot(),timeSource.now(),
                    occurrenceId,iteration,expectedPhase,content,finalCandidateDigest),
            "PROVIDER_FINAL_COMMIT");}
    public void recordPlan(String id,String text){require(stateStore.fencedMutations.recordPlan(
            current().snapshot(),timeSource.now(),id,text),"PLAN_WRITE");}
    public void transitionPlan(String id,String expected,String next){require(
            stateStore.fencedMutations.transitionPlan(current().snapshot(),timeSource.now(),id,
                    expected,next),"PLAN_STATE_WRITE");}
    public void startRepair(String id,int attempt,String verification,String summary){require(
            stateStore.fencedMutations.startRepair(current().snapshot(),timeSource.now(),id,attempt,
                    verification,summary),"REPAIR_START_WRITE");}
    public long finishRepair(String id,String status){FencedValueResult result=
            stateStore.fencedMutations.finishRepair(current().snapshot(),timeSource.now(),id,status);
        if(result instanceof FencedValueResult.Success success)return success.value();
        require(((FencedValueResult.Rejected)result).reason(),"REPAIR_COMPLETION_WRITE");return -1;}
    public void startVerification(String id,int attempt,String command,String status,
            String candidateDigestKind,String candidateDigest,String error){require(
            stateStore.fencedMutations.startVerification(current().snapshot(),timeSource.now(),id,
                    attempt,command,status,candidateDigestKind,candidateDigest,error),
            "VERIFICATION_START_WRITE");}
    public void finishVerification(String id,String status,Integer exit,String output,String error,
            long duration){require(stateStore.fencedMutations.finishVerification(current().snapshot(),
                    timeSource.now(),id,status,exit,output,error,duration),
                    "VERIFICATION_RESULT_WRITE");}
    public com.paicli.state.UserResultStore.Result persistUserResult(CanonicalId revisionId,
            String mode,String content){
        var result=stateStore.fencedMutations.persistUserResult(current().snapshot(),timeSource.now(),
                revisionId,mode,content);
        require(result.status(),"USER_RESULT_WRITE");
        if(result.result()==null)throw new AuthorityLostException(
                "EXECUTION_MUTATION_INVALID_STATE","USER_RESULT_WRITE",null);
        return result.result();
    }
    public com.paicli.state.UserResultStore.Result persistPlanlessUserResult(
            CanonicalId providerOccurrenceId,String candidateDigest,String verificationId,
            String mode,String content){
        var result=stateStore.fencedMutations.persistPlanlessUserResult(current().snapshot(),
                timeSource.now(),providerOccurrenceId,candidateDigest,verificationId,mode,content);
        require(result.status(),"USER_RESULT_WRITE");
        if(result.result()==null)throw new AuthorityLostException(
                "EXECUTION_MUTATION_INVALID_STATE","USER_RESULT_WRITE",null);
        return result.result();
    }
    public void markUserResultRendered(String contentHash){require(
            stateStore.fencedMutations.markUserResultRendered(current().snapshot(),timeSource.now(),
                    contentHash),"USER_RESULT_RENDER_WRITE");}
    public void terminalize(String planId,boolean success,String message){require(
            stateStore.fencedMutations.terminalize(current().snapshot(),timeSource.now(),planId,
                    success,message),"TERMINALIZATION_WRITE");}
    public void refineInteractiveTerminal(String status){require(
            stateStore.fencedMutations.refineInteractiveTerminal(current().snapshot(),timeSource.now(),
                    status),"INTERACTIVE_TERMINAL_WRITE");}
    public void cancel(String planId,boolean unknown,String reason){require(
            stateStore.fencedMutations.cancel(current().snapshot(),timeSource.now(),planId,unknown,
                    reason),"CANCELLATION_WRITE");}
    public void recordSnapshot(String id,String callId,String path,String snapshotPath,
            String operation,boolean existed,long size,String beforeHash){require(
            stateStore.fencedMutations.recordSnapshot(current().snapshot(),timeSource.now(),id,
                    callId,path,snapshotPath,operation,existed,size,beforeHash),"SNAPSHOT_CAPTURE_WRITE");}
    public void transitionSnapshot(String id,String expected,String status,String afterHash){require(
            stateStore.fencedMutations.transitionSnapshot(current().snapshot(),timeSource.now(),id,
                    expected,status,afterHash),"SNAPSHOT_STATE_WRITE");}
    public void startHook(String id,String hookId,String event,String occurrence,int sequence,
            String command,String policy){require(stateStore.fencedMutations.startHook(
                    current().snapshot(),timeSource.now(),id,hookId,event,occurrence,sequence,
                    command,policy),"HOOK_START_WRITE");}
    public void finishHook(String id,String status,Integer exit,String output,String error,
            long duration){require(stateStore.fencedMutations.finishHook(current().snapshot(),
                    timeSource.now(),id,status,exit,output,error,duration),"HOOK_RESULT_WRITE");}
    public void recordSkillLoad(String id,String skillId,String name,String path,String description,
            String hash,String status,String error){require(stateStore.fencedMutations.recordSkillLoad(
                    current().snapshot(),timeSource.now(),id,skillId,name,path,description,hash,status,
                    error),"SKILL_LOAD_WRITE");}
    public void bindSkillPackage(String id,String hash,String manifest){require(
            stateStore.fencedMutations.bindSkillPackage(current().snapshot(),timeSource.now(),id,
                    hash,manifest),"SKILL_PACKAGE_BINDING");}
    public void recordSkillAccess(SkillLoadStore.Access fact){require(
            stateStore.fencedMutations.recordSkillAccess(current().snapshot(),timeSource.now(),fact),
            "SKILL_ACCESS_WRITE");}
    public void recordSkillSelection(String id,String stage,String occurrence,String inputHash,
            List<SkillSelectionStore.SelectedSkillInput> selected){require(
            stateStore.fencedMutations.recordSkillSelection(current().snapshot(),timeSource.now(),id,
                    stage,occurrence,inputHash,selected),"SKILL_SELECTION_WRITE");}
    public void recordProposal(String id,String path,String hash,int additions,int deletions,
            boolean truncated,boolean redacted){require(stateStore.fencedMutations.recordProposal(
                    current().snapshot(),timeSource.now(),id,path,hash,additions,deletions,truncated,
                    redacted),"PROPOSAL_WRITE");}
    public void transitionProposal(String id,String expected,String status){require(
            stateStore.fencedMutations.transitionProposal(current().snapshot(),timeSource.now(),id,
                    expected,status),"PROPOSAL_STATE_WRITE");}
    public void applyLatestProposal(String path,String snapshot){require(
            stateStore.fencedMutations.applyLatestProposal(current().snapshot(),timeSource.now(),
                    path,snapshot),"PROPOSAL_APPLY_WRITE");}
    public void supersedeProposals(){require(stateStore.fencedMutations.supersedeProposals(
            current().snapshot(),timeSource.now()),"PROPOSAL_SUPERSEDE_WRITE");}
    public void recordInteractiveFacts(String status,int files,int contexts,int queries){require(
            stateStore.fencedMutations.recordInteractiveFacts(current().snapshot(),timeSource.now(),
                    status,files,contexts,queries),"INTERACTIVE_FACT_WRITE");}

    public PlanExecutionStore.StartResult startNextPlanAttempt(int iteration){
        return stateStore.planExecutions.startNextReadyAttempt(current().snapshot(),timeSource.now(),
                iteration,"R1C_B1_RUNTIME");
    }

    public void startContinuitySegment(int segmentOrdinal,int planningAttemptOrdinal,
            String trigger,String policyHash,int startIteration,String directive){
        require(stateStore.fencedMutations.startContinuitySegment(current().snapshot(),
                timeSource.now(),segmentOrdinal,planningAttemptOrdinal,trigger,policyHash,
                startIteration,directive),"CONTINUITY_SEGMENT_ADMISSION");
    }

    public void finishContinuitySegment(int segmentOrdinal,String state,int endIteration,
            int providerTurns,int toolCalls,long inputTokens,long outputTokens,
            long activeElapsedMillis,String terminalReason){
        require(stateStore.fencedMutations.finishContinuitySegment(current().snapshot(),
                timeSource.now(),segmentOrdinal,state,endIteration,providerTurns,toolCalls,
                inputTokens,outputTokens,activeElapsedMillis,terminalReason),
                "CONTINUITY_SEGMENT_TERMINAL");
    }

    public PlanExecutionStore.Mutation completePlanAttempt(Attempt attempt,Outcome outcome,
            int endIteration,long expectedStepVersion,String failureClass,String boundedReason){
        return stateStore.planExecutions.completeAttempt(current().snapshot(),timeSource.now(),
                attempt,outcome,endIteration,expectedStepVersion,failureClass,boundedReason);
    }

    public PlanCompletionStore.GateResult evaluateAndCompletePlan(CanonicalId revisionId) {
        return stateStore.planCompletions.evaluateAndComplete(
                current().snapshot(), timeSource.now(), revisionId);
    }

    public void validatePlanExecutionContext(
            AgentSegmentRequest.PlanExecutionContext context) {
        validate("PLAN_STEP_RUNTIME_ENTRY");
        var snapshot=current().snapshot();
        if(!context.taskId().equals(snapshot.taskId())
                || !context.runId().equals(snapshot.runId())) {
            throw new AuthorityLostException("PLAN_EXECUTION_CONTEXT_MISMATCH",
                    "PLAN_STEP_RUNTIME_ENTRY",null);
        }
        var revision=stateStore.planAuthorities.readRevisionDefinition(
                snapshot.taskId(),context.planRevisionId());
        if(revision.status()!=com.paicli.state.PlanAuthorityStore.Status.SUCCESS
                ||revision.revision().canonicalSchemaVersion()
                    !=com.paicli.state.StructuredPlanModels.Revision.CANONICAL_SCHEMA_V3){
            throw new AuthorityLostException("PLAN_TOOL_SCOPE_UNAVAILABLE",
                    "PLAN_STEP_RUNTIME_ENTRY",null);
        }
        PlanExecutionStore.Status status=stateStore.planExecutions.validateRunningAttempt(
                snapshot,context.planRevisionId(),context.stepRevisionId(),
                context.attemptNumber());
        if(status!=PlanExecutionStore.Status.SUCCESS) {
            throw new AuthorityLostException("PLAN_ATTEMPT_"+status,
                    "PLAN_STEP_RUNTIME_ENTRY",null);
        }
    }

    public com.paicli.state.FencedMutationStore.PlanToolBatchReservation reservePlanToolBatch(
            AgentSegmentRequest.PlanExecutionContext context,
            java.util.List<com.paicli.state.FencedMutationStore.PlanToolOccurrenceRequest> requests){
        validatePlanExecutionContext(context);
        return stateStore.fencedMutations.reservePlanToolBatch(current().snapshot(),
                timeSource.now(),context.planRevisionId(),context.stepRevisionId(),
                context.attemptNumber(),requests);
    }

    private void validate(TaskExecutionAuthority expected,Instant now,String boundary){
        OwnershipResult result;
        try{result=stateStore.r1aDurable.validateExecutionAuthority(expected.taskId(),
                expected.ownerInstanceId(),expected.runId(),expected.leaseToken(),
                expected.fencingToken(),expected.casVersion(),now);}
        catch(SQLException e){throw new AuthorityLostException(
                "EXECUTION_AUTHORITY_PERSISTENCE_FAILURE",boundary,e);}
        if(result!=OwnershipResult.SUCCESS)throw rejected(result,boundary);
    }

    private static AuthorityLostException rejected(OwnershipResult result,String boundary){
        return new AuthorityLostException("EXECUTION_AUTHORITY_"+result,boundary,null);
    }
    private static void require(FencedMutationResult result,String boundary){
        if(result!=FencedMutationResult.SUCCESS)throw new AuthorityLostException(
                "EXECUTION_MUTATION_"+result,boundary,null);
    }
    private static String hash(ToolCall call){
        try{return hash(CANONICAL_JSON.writeValueAsString(
                java.util.Map.of("name",call.name(),"arguments",call.arguments())));}
        catch(Exception e){throw new IllegalArgumentException("Tool call cannot be canonicalized",e);}
    }
    private static String hash(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException("SHA-256 unavailable",e);}
    }

    @FunctionalInterface public interface TimeSource{Instant now();}

    @FunctionalInterface
    interface TestCoordinator{
        TestCoordinator NOOP=boundary->{};
        void before(String boundary);
    }

    public static final class AuthorityLostException extends RuntimeException{
        private final String code;private final String boundary;
        AuthorityLostException(String code,String boundary,Throwable cause){
            super(code+" at "+boundary,cause);this.code=code;this.boundary=boundary;
        }
        public String code(){return code;}
        public String boundary(){return boundary;}
    }
    public static final class SteeringEffectBlockedException extends RuntimeException{
        private final String effectKind;
        private final String producerIdentity;
        public SteeringEffectBlockedException(){this("EXTERNAL",null);}
        public SteeringEffectBlockedException(String effectKind,String producerIdentity){
            super("STEERING_BLOCKED_BEFORE_EXTERNAL_EFFECT");
            this.effectKind=effectKind;this.producerIdentity=producerIdentity;
        }
        public String effectKind(){return effectKind;}
        public String producerIdentity(){return producerIdentity;}
    }
}
