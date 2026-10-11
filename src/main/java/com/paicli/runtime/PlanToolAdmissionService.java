package com.paicli.runtime;

import com.paicli.agent.AgentSegmentRequest;
import com.paicli.identity.CanonicalId;
import com.paicli.model.ToolCall;
import com.paicli.state.FencedMutationStore.PlanToolOccurrenceRequest;
import com.paicli.state.PlanExecutionModels.StepExecution;
import com.paicli.state.PlanExecutionModels.StepState;
import com.paicli.state.PlanRequirementStore.OccurrenceSlotBinding;
import com.paicli.state.PlanRequirementStore.RequirementRecord;
import com.paicli.state.R1aStoreResults.FencedMutationResult;
import com.paicli.state.StateStore;
import com.paicli.state.StructuredPlanModels.ToolRequirement;
import com.paicli.tool.ToolAuthorityProjection;
import com.paicli.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Exact, whole-batch admission for Tool calls under one active approved V3 Plan Step. */
public final class PlanToolAdmissionService {
    public enum Status {
        SUCCESS, PLAN_TOOL_SCOPE_UNAVAILABLE, PREFLIGHT_REJECTED,
        AUTHORITY_PROJECTION_UNAVAILABLE, TOOL_NOT_AUTHORIZED, EFFECT_MISMATCH,
        SELECTOR_MISMATCH, CARDINALITY_EXHAUSTED, DUPLICATE_CALL,
        FUTURE_STEP, COMPLETED_STEP_REPLAY, PLAN_ATTEMPT_INVALID,
        FENCING_REJECTED, INTEGRITY_FAILURE
    }

    public record Candidate(String toolCallId,ToolCall call){
        public Candidate{
            if(toolCallId==null||toolCallId.isBlank())
                throw new IllegalArgumentException("Durable Tool call ID is required");
            Objects.requireNonNull(call,"call");
        }
    }

    public record Reservation(String toolCallId,CanonicalId occurrenceId,
            String requirementId,int slotOrdinal){
        public Reservation{
            if(toolCallId==null||toolCallId.isBlank()||occurrenceId==null
                    ||requirementId==null||requirementId.isBlank()||slotOrdinal<1)
                throw new IllegalArgumentException("Invalid Plan Tool reservation");
        }
    }

    public record Admission(Status status,List<Reservation> reservations,
            String rejectionCode){
        public Admission{
            Objects.requireNonNull(status);
            reservations=reservations==null?List.of():List.copyOf(reservations);
        }
        public boolean admitted(){return status==Status.SUCCESS;}
        static Admission rejected(Status status){return new Admission(status,List.of(),status.name());}
    }

    public static String publicRejectionCode(Status status,boolean batch){
        if(batch)return "PLAN_TOOL_BATCH_SCOPE_VIOLATION";
        return switch(status){
            case PLAN_TOOL_SCOPE_UNAVAILABLE -> "PLAN_TOOL_SCOPE_UNAVAILABLE";
            case EFFECT_MISMATCH -> "PLAN_STEP_TOOL_MISMATCH";
            case SELECTOR_MISMATCH -> "PLAN_STEP_TARGET_MISMATCH";
            case CARDINALITY_EXHAUSTED -> "PLAN_STEP_CARDINALITY_EXHAUSTED";
            case FUTURE_STEP -> "PLAN_STEP_FUTURE_TARGET_REJECTED";
            case COMPLETED_STEP_REPLAY,DUPLICATE_CALL -> "PLAN_STEP_DUPLICATE_TOOL_REJECTED";
            case PREFLIGHT_REJECTED,AUTHORITY_PROJECTION_UNAVAILABLE,TOOL_NOT_AUTHORIZED,
                    PLAN_ATTEMPT_INVALID,FENCING_REJECTED,INTEGRITY_FAILURE ->
                    "PLAN_STEP_TOOL_SCOPE_VIOLATION";
            case SUCCESS -> throw new IllegalArgumentException("Successful admission has no rejection code");
        };
    }

    public static final class AdmissionRejectedException extends RuntimeException{
        private final String code;
        private final Status status;
        public AdmissionRejectedException(String code,Status status){
            super(code);this.code=code;this.status=status;
        }
        public String code(){return code;}
        public Status status(){return status;}
    }

    private final StateStore state;
    private final ToolRegistry tools;
    private final FencedExecutionGuard guard;

    public PlanToolAdmissionService(StateStore state,ToolRegistry tools,
            FencedExecutionGuard guard){
        this.state=Objects.requireNonNull(state);
        this.tools=Objects.requireNonNull(tools);
        this.guard=Objects.requireNonNull(guard);
    }

    public Admission admitBatch(AgentSegmentRequest.PlanExecutionContext context,int iteration,
            List<Candidate> candidates){
        Objects.requireNonNull(context);
        List<Candidate> batch=List.copyOf(candidates);
        if(batch.isEmpty())return Admission.rejected(Status.PREFLIGHT_REJECTED);
        try{guard.validatePlanExecutionContext(context);}
        catch(FencedExecutionGuard.AuthorityLostException error){
            return Admission.rejected("PLAN_TOOL_SCOPE_UNAVAILABLE".equals(error.code())
                    ?Status.PLAN_TOOL_SCOPE_UNAVAILABLE:Status.PLAN_ATTEMPT_INVALID);
        }
        var activePlan=state.planAuthorities.readActive(context.taskId());
        if(activePlan.status()!=com.paicli.state.PlanAuthorityStore.Status.SUCCESS
                ||!activePlan.plan().revision().planRevisionId()
                        .equals(context.planRevisionId())
                ||activePlan.plan().revision().canonicalSchemaVersion()
                        !=com.paicli.state.StructuredPlanModels.Revision.CANONICAL_SCHEMA_V3)
            return Admission.rejected(Status.PLAN_TOOL_SCOPE_UNAVAILABLE);
        Set<String> callIds=new HashSet<>();
        List<ProjectedCandidate> projected=new ArrayList<>();
        for(Candidate candidate:batch){
            if(!callIds.add(candidate.toolCallId()))
                return Admission.rejected(Status.DUPLICATE_CALL);
            ToolRegistry.PreflightResult preflight=tools.preflight(candidate.call());
            if(!preflight.accepted())return Admission.rejected(Status.PREFLIGHT_REJECTED);
            if(preflight.authorityProjection()==null)
                return Admission.rejected(Status.AUTHORITY_PROJECTION_UNAVAILABLE);
            projected.add(new ProjectedCandidate(candidate,preflight.authorityProjection()));
        }

        List<RequirementRecord> active=state.planRequirements.requirements(context.taskId(),
                context.planRevisionId(),context.stepRevisionId());
        if(active.isEmpty())return Admission.rejected(Status.PLAN_TOOL_SCOPE_UNAVAILABLE);
        List<OccurrenceSlotBinding> existing=state.planRequirements.bindings(context.taskId(),
                context.planRevisionId(),context.stepRevisionId());
        Map<String,Set<Integer>> occupied=new HashMap<>();
        for(OccurrenceSlotBinding binding:existing)
            occupied.computeIfAbsent(binding.requirementId(),ignored->new HashSet<>())
                    .add(binding.slotOrdinal());
        List<Allocation> allocations=new ArrayList<>();
        for(ProjectedCandidate candidate:projected){
            List<RequirementRecord> exact=active.stream().filter(record->matches(
                    record.requirement(),candidate.projection())).sorted(Comparator.comparingInt(
                            record->record.requirement().requirementOrder())).toList();
            if(exact.isEmpty())return Admission.rejected(classify(context,candidate.projection(),active));
            Allocation allocation=null;
            for(RequirementRecord record:exact){
                Set<Integer> used=occupied.computeIfAbsent(
                        record.requirement().requirementId(),ignored->new HashSet<>());
                for(int slot=1;slot<=record.requirement().maxOccurrences();slot++){
                    if(used.add(slot)){
                        allocation=new Allocation(candidate,record,slot);break;
                    }
                }
                if(allocation!=null)break;
            }
            if(allocation==null)return Admission.rejected(Status.CARDINALITY_EXHAUSTED);
            allocations.add(allocation);
        }

        List<PlanToolOccurrenceRequest> requests=new ArrayList<>();
        for(int index=0;index<allocations.size();index++){
            Allocation allocation=allocations.get(index);
            ToolRequirement requirement=allocation.requirement().requirement();
            requests.add(new PlanToolOccurrenceRequest(allocation.candidate().candidate().toolCallId(),
                    requirement.toolName(),requirement.effectClass().name(),
                    requirement.authoritySelector().digest(),requirement.requirementId(),
                    allocation.slotOrdinal(),Integer.toString(iteration),
                    "plan-tool:"+context.runId().value()+":"+iteration+":"+(index+1),
                    FencedExecutionGuard.canonicalToolCallHash(
                            allocation.candidate().candidate().call())));
        }
        var durable=guard.reservePlanToolBatch(context,requests);
        if(durable.result()!=FencedMutationResult.SUCCESS)
            return Admission.rejected(durable.result()==FencedMutationResult.ALREADY_EXISTS
                    ?Status.DUPLICATE_CALL:durable.result()==FencedMutationResult.INVALID_STATE
                    ?Status.PLAN_ATTEMPT_INVALID:Status.FENCING_REJECTED);
        List<Reservation> reservations=new ArrayList<>();
        for(int index=0;index<allocations.size();index++){
            Allocation allocation=allocations.get(index);
            reservations.add(new Reservation(allocation.candidate().candidate().toolCallId(),
                    durable.occurrenceIds().get(index),
                    allocation.requirement().requirement().requirementId(),
                    allocation.slotOrdinal()));
        }
        return new Admission(Status.SUCCESS,reservations,null);
    }

    private Status classify(AgentSegmentRequest.PlanExecutionContext context,
            ToolAuthorityProjection projection,List<RequirementRecord> active){
        var approved=state.planAuthorities.readActive(context.taskId());
        if(approved.status()==com.paicli.state.PlanAuthorityStore.Status.SUCCESS){
            List<StepExecution> executions=state.planExecutions.readStates(context.taskId(),
                    context.planRevisionId());
            for(var step:approved.plan().revision().steps()){
                if(step.stepRevisionId().equals(context.stepRevisionId()))continue;
                boolean match=step.toolRequirements().stream().anyMatch(requirement->
                        matches(requirement,projection));
                if(!match)continue;
                StepState stepState=executions.stream().filter(value->value.stepRevisionId()
                        .equals(step.stepRevisionId())).map(StepExecution::state).findFirst()
                        .orElse(StepState.PENDING);
                return stepState==StepState.SUCCEEDED
                        ?Status.COMPLETED_STEP_REPLAY:Status.FUTURE_STEP;
            }
        }
        boolean tool=active.stream().anyMatch(record->record.requirement().toolName()
                .equals(projection.toolName()));
        boolean selector=active.stream().anyMatch(record->record.requirement().toolName()
                .equals(projection.toolName())&&record.requirement().authoritySelector()
                .equals(projection.authoritySelector()));
        if(tool)return selector?Status.EFFECT_MISMATCH:Status.SELECTOR_MISMATCH;
        return Status.TOOL_NOT_AUTHORIZED;
    }

    private static boolean matches(ToolRequirement requirement,
            ToolAuthorityProjection projection){
        return requirement.toolName().equals(projection.toolName())
                &&requirement.effectClass()==projection.effectClass()
                &&requirement.authoritySelector().equals(projection.authoritySelector());
    }

    private record ProjectedCandidate(Candidate candidate,ToolAuthorityProjection projection){ }
    private record Allocation(ProjectedCandidate candidate,RequirementRecord requirement,
            int slotOrdinal){ }
}
