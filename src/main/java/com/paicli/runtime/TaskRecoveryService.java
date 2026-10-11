package com.paicli.runtime;

import com.paicli.config.Config;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.model.AgentMessage;
import com.paicli.state.CheckpointStore;
import com.paicli.state.InstructionManifest;
import com.paicli.state.R1aDurableModels.DurableRun;
import com.paicli.state.R1aDurableModels.OwnershipLease;
import com.paicli.state.R1aDurableModels.SideEffectOccurrence;
import com.paicli.state.R1aDurableModels.TaskContextGeneration;
import com.paicli.state.R1aStoreResults.ResumeRunAdmissionResult;
import com.paicli.state.StateStore;
import com.paicli.state.TaskStore;
import com.paicli.state.TaskPlanStore;
import com.paicli.state.VerificationStore;
import com.paicli.state.FileSnapshotStore;
import com.paicli.state.R1aDurableModels.ConstraintActivation;
import com.paicli.state.StructuredPlanModels.ApprovedPlan;
import com.paicli.state.StructuredPlanModels.PlanApplicability;
import com.paicli.state.PlanExecutionStore;
import com.paicli.state.PlanCompletionStore;
import com.paicli.state.CodingRunStore;
import com.paicli.state.FencedMutationStore;
import com.paicli.state.R1aStoreResults.OwnershipResult;

import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Internal same-Task Recovery use case. No CLI or Saved Session route calls it in R1B-A. */
public final class TaskRecoveryService {
    private final StateStore stateStore;
    private final RecoveryPreflightValidator preflight;

    public TaskRecoveryService(StateStore stateStore,InstructionManifestRuntime manifests){
        this.stateStore=Objects.requireNonNull(stateStore);
        InstructionManifestRuntime checked=Objects.requireNonNull(manifests);
        this.preflight=new RecoveryPreflightValidator(stateStore,checked,
                ()->{throw new IllegalStateException("explicit Recovery inputs required");});
    }

    public RecoveryResult recover(CanonicalId taskId,Config currentConfig,
            RuntimeManifest currentRuntime,int maxInstructionChars,LeaseRequest lease)
            throws SQLException,IOException{
        requireTask(taskId);Objects.requireNonNull(currentConfig);
        Objects.requireNonNull(lease);
        RecoveryPreflightValidator.Result preflightResult=preflight.validate(
                taskId,currentConfig,currentRuntime,maxInstructionChars,lease.acquiredAt());
        if(preflightResult.eligibility()!=RecoveryPreflightValidator.Eligibility.ELIGIBLE){
            return new Rejected(preflightResult.status(),preflightResult.reasons());
        }
        RecoveryPreflightValidator.Material material=Objects.requireNonNull(
                preflightResult.material());
        CheckpointStore.CheckpointRecord checkpoint=material.checkpoint();
        InstructionManifestRuntime.FrozenInstructionContext frozen=material.frozen();
        TaskContextGeneration generation=material.generation();
        R1bRuntimeContract original=material.original();
        R1bRuntimeContract.Compatibility compatibility=material.compatibility();
        InstructionManifestRuntime.DriftResult drift=material.drift();
        DurableRun previous=material.previous();
        InstructionManifest manifest=frozen.manifest();
        com.paicli.state.PlanAuthorityStore.Read activePlan=
                stateStore.planAuthorities.readActive(taskId);
        RecoveryResult acceptedFinal=resumeAcceptedFinal(taskId,lease,material,activePlan);
        if(acceptedFinal!=null)return acceptedFinal;
        if(activePlan.status()==com.paicli.state.PlanAuthorityStore.Status.SUCCESS
                &&activePlan.plan().revision().canonicalSchemaVersion()
                    ==com.paicli.state.StructuredPlanModels.Revision.CANONICAL_SCHEMA_V2){
            return new Rejected(RecoveryStatus.PLAN_TOOL_SCOPE_UNAVAILABLE,
                    List.of("PLAN_TOOL_SCOPE_UNAVAILABLE"));
        }
        DurableRun resume=new DurableRun(CanonicalId.newId(EntityFamily.RUN),taskId,
                previous.threadId(),"RESUME",previous.id(),manifest.id(),
                manifest.canonicalHash(),lease.acquiredAt(),null);
        ResumeRunAdmissionResult admitted=stateStore.r1aDurable.admitResumeRunForRecovery(
                resume,lease.ownerInstanceId(),lease.leaseToken(),lease.acquiredAt(),
                lease.expiresAt(),lease.grace());
        if(admitted instanceof ResumeRunAdmissionResult.Rejected rejected)
            return new Rejected(map(rejected.reason()),List.of(rejected.reason().name()));
        ResumeRunAdmissionResult.Success success=(ResumeRunAdmissionResult.Success)admitted;
        TaskExecutionAuthority authority=TaskExecutionAuthority.recovery(success.ownership());
        PlanExecutionStore.RecoveryRead planExecution=success.approvedPlan()==null?null:
                stateStore.planExecutions.readRecovery(taskId,
                        success.approvedPlan().revision().planRevisionId());
        PlanCompletionStore.RecoveryRead planCompletion=success.approvedPlan()==null?null:
                stateStore.planCompletions.readRecovery(taskId,
                        success.approvedPlan().revision().planRevisionId());
        RecoveryContext context=new RecoveryContext(checkpoint.messages(),checkpoint.iteration(),
                frozen,generation,stateStore.r1aDurable.readConstraintActivation(taskId)
                .orElse(null),stateStore.plans.findByRun(previous.id().value()).orElse(null),
                stateStore.verifications.findLatestByTask(taskId.value()).orElse(null),
                stateStore.snapshots.findByTask(taskId.value()),true,
                success.planApplicability(),success.approvedPlan(),planExecution,planCompletion);
        RecoveryStatus status=drift.status()==InstructionManifestRuntime.RecoveryMaterialStatus
                .INSTRUCTION_DRIFT_INFORMATIONAL
                ?RecoveryStatus.INSTRUCTION_DRIFT_INFORMATIONAL:RecoveryStatus.NO_DRIFT;
        return new Success(status,taskId,success.run(),success.ownership(),authority,
                checkpoint.messages(),checkpoint.iteration(),frozen,original,
                compatibility,drift,generation,context);
    }

    private RecoveryResult resumeAcceptedFinal(CanonicalId taskId,LeaseRequest request,
            RecoveryPreflightValidator.Material material,
            com.paicli.state.PlanAuthorityStore.Read activePlan)throws SQLException{
        DurableRun run=material.previous();
        CodingRunStore.CodingRunRecord coding=stateStore.codingRuns
                .findById(run.id().value()).orElse(null);
        if(coding==null||!CodingRunStore.PHASE_VERIFYING.equals(coding.phase()))return null;
        SideEffectOccurrence occurrence=stateStore.r1aDurable.findAcceptedProviderFinal(
                taskId,run.id(),coding.lastIteration()).orElse(null);
        if(occurrence==null)return null;
        AgentMessage claim=stateStore.events.findFinalAtIteration(
                taskId.value(),coding.lastIteration()).orElse(null);
        if(claim==null||!Objects.equals(occurrence.resultHash(),
                FencedMutationStore.providerFinalResultHash(claim.content()))){
            return new Rejected(RecoveryStatus.CONTINUATION_UNAVAILABLE,
                    List.of("FINAL_EVIDENCE_INTEGRITY_FAILURE"));
        }
        OwnershipLease previousLease=stateStore.r1aDurable.readOwnership(taskId).orElse(null);
        var pointer=stateStore.r1aDurable.readContextPointer(taskId).orElse(null);
        if(previousLease==null||pointer==null){
            return new Rejected(RecoveryStatus.CONTINUATION_UNAVAILABLE,
                    List.of("FINAL_RECOVERY_AUTHORITY_UNAVAILABLE"));
        }
        OwnershipResult takeover=stateStore.r1aDurable.takeoverOwnershipAndContextPointer(
                taskId,run.id(),request.ownerInstanceId(),request.leaseToken(),
                previousLease.casVersion(),pointer.generationId(),pointer.ownerRunId(),
                pointer.version(),request.acquiredAt(),request.expiresAt(),request.grace());
        if(takeover!=OwnershipResult.SUCCESS){
            RecoveryStatus status=takeover==OwnershipResult.OWNERSHIP_CONFLICT
                    ?RecoveryStatus.OWNERSHIP_CONFLICT:takeover==OwnershipResult.VERSION_CONFLICT
                    ?RecoveryStatus.VERSION_CONFLICT:RecoveryStatus.RECOVERY_ADMISSION_FAILED;
            return new Rejected(status,List.of(takeover.name()));
        }
        OwnershipLease ownership=stateStore.r1aDurable.readOwnership(taskId).orElseThrow();
        TaskExecutionAuthority authority=TaskExecutionAuthority.recovery(ownership);
        PlanApplicability applicability=stateStore.r1aDurable.readPlanApplicability(taskId);
        ApprovedPlan approved=activePlan.status()==com.paicli.state.PlanAuthorityStore.Status.SUCCESS
                ?activePlan.plan():null;
        PlanExecutionStore.RecoveryRead planExecution=applicability==PlanApplicability.PLAN_REQUIRED
                &&approved!=null?stateStore.planExecutions.readRecovery(
                        taskId,approved.revision().planRevisionId()):null;
        PlanCompletionStore.RecoveryRead planCompletion=applicability==PlanApplicability.PLAN_REQUIRED
                &&approved!=null?stateStore.planCompletions.readRecovery(
                        taskId,approved.revision().planRevisionId()):null;
        List<AgentMessage> conversation=stateStore.events.findMessages(taskId.value());
        RecoveryContext context=new RecoveryContext(conversation,coding.lastIteration(),
                material.frozen(),material.generation(),
                stateStore.r1aDurable.readConstraintActivation(taskId).orElse(null),
                stateStore.plans.findByRun(run.id().value()).orElse(null),
                stateStore.verifications.findLatestByTask(taskId.value()).orElse(null),
                stateStore.snapshots.findByTask(taskId.value()),true,applicability,approved,
                planExecution,planCompletion);
        RecoveryStatus status=material.drift().status()==InstructionManifestRuntime
                .RecoveryMaterialStatus.INSTRUCTION_DRIFT_INFORMATIONAL
                ?RecoveryStatus.INSTRUCTION_DRIFT_INFORMATIONAL:RecoveryStatus.NO_DRIFT;
        return new Success(status,taskId,run,ownership,authority,conversation,
                coding.lastIteration(),material.frozen(),material.original(),
                material.compatibility(),material.drift(),material.generation(),context);
    }

    private static RecoveryStatus map(
            InstructionManifestRuntime.RecoveryMaterialStatus status){
        return switch(status){
            case ARTIFACT_UNAVAILABLE -> RecoveryStatus.ARTIFACT_UNAVAILABLE;
            case ARTIFACT_INTEGRITY_FAILURE -> RecoveryStatus.ARTIFACT_INTEGRITY_FAILURE;
            case INSTRUCTION_DRIFT_INCOMPATIBLE ->
                    RecoveryStatus.TASK_RECOVERY_RUNTIME_DRIFT;
            case INSTRUCTION_DRIFT_INFORMATIONAL ->
                    RecoveryStatus.INSTRUCTION_DRIFT_INFORMATIONAL;
            case NO_DRIFT -> RecoveryStatus.NO_DRIFT;
        };
    }
    private static RecoveryStatus map(ResumeRunAdmissionResult.Reason reason){
        return switch(reason){
            case OWNERSHIP_CONFLICT -> RecoveryStatus.OWNERSHIP_CONFLICT;
            case OCCURRENCE_UNRESOLVED -> RecoveryStatus.UNRESOLVED_OCCURRENCE;
            case VERSION_CONFLICT -> RecoveryStatus.VERSION_CONFLICT;
            case NOT_FOUND,TASK_TERMINAL -> RecoveryStatus.CONTINUATION_UNAVAILABLE;
            case ALREADY_EXISTS,INVALID_STATE,INTEGRITY_VIOLATION ->
                    RecoveryStatus.RECOVERY_ADMISSION_FAILED;
            case NO_APPROVED_PLAN -> RecoveryStatus.NO_APPROVED_PLAN;
            case PLAN_AUTHORITY_UNAVAILABLE -> RecoveryStatus.PLAN_AUTHORITY_UNAVAILABLE;
            case PLAN_REVISION_INTEGRITY_FAILURE ->
                    RecoveryStatus.PLAN_REVISION_INTEGRITY_FAILURE;
            case PLAN_STEP_STATE_UNAVAILABLE -> RecoveryStatus.PLAN_STEP_STATE_UNAVAILABLE;
            case PLAN_STEP_STATE_INTEGRITY_FAILURE ->
                    RecoveryStatus.PLAN_STEP_STATE_INTEGRITY_FAILURE;
            case PLAN_ATTEMPT_UNRESOLVED -> RecoveryStatus.PLAN_ATTEMPT_UNRESOLVED;
            case PLAN_EVIDENCE_INTEGRITY_FAILURE ->
                    RecoveryStatus.PLAN_EVIDENCE_INTEGRITY_FAILURE;
            case COMPLETION_AUTHORITY_INTEGRITY_FAILURE ->
                    RecoveryStatus.COMPLETION_AUTHORITY_INTEGRITY_FAILURE;
            case COMPLETION_RULES_UNAVAILABLE -> RecoveryStatus.COMPLETION_RULES_UNAVAILABLE;
            case STEERING_AUTHORITY_INTEGRITY_FAILURE ->
                    RecoveryStatus.STEERING_AUTHORITY_INTEGRITY_FAILURE;
            case LEGACY_PLAN_CONTINUATION_UNAVAILABLE ->
                    RecoveryStatus.LEGACY_PLAN_CONTINUATION_UNAVAILABLE;
        };
    }
    private static void requireTask(CanonicalId id){
        if(id==null||id.family()!=EntityFamily.TASK)
            throw new IllegalArgumentException("Canonical Task ID required");
    }

    public sealed interface RecoveryResult permits Success,Rejected{ }
    public record Success(RecoveryStatus status,CanonicalId taskId,DurableRun resumeRun,
                          OwnershipLease ownership,TaskExecutionAuthority executionAuthority,
                          List<AgentMessage> conversation,
                          int startIteration,
                          InstructionManifestRuntime.FrozenInstructionContext instructions,
                          R1bRuntimeContract runtimeContract,
                          R1bRuntimeContract.Compatibility compatibility,
                          InstructionManifestRuntime.DriftResult instructionDrift,
                          TaskContextGeneration sourceGeneration,
                          RecoveryContext context)implements RecoveryResult{
        public Success{
            Objects.requireNonNull(executionAuthority);
            if(!executionAuthority.taskId().equals(taskId)
                    ||!executionAuthority.runId().equals(resumeRun.id()))
                throw new IllegalArgumentException("Recovery execution authority mismatch");
            conversation=List.copyOf(conversation);
        }
    }
    public record RecoveryContext(List<AgentMessage> checkpointConversation,
            int checkpointIteration,
            InstructionManifestRuntime.FrozenInstructionContext instructions,
            TaskContextGeneration sourceGeneration,
            ConstraintActivation activeConstraint,
            TaskPlanStore.TaskPlanRecord activePlan,
            VerificationStore.VerificationRecord latestVerification,
            List<FileSnapshotStore.FileSnapshotRecord> snapshots,
            boolean occurrenceSafetyChecked,
            PlanApplicability planApplicability,
            ApprovedPlan structuredPlan,
            PlanExecutionStore.RecoveryRead planExecution,
            PlanCompletionStore.RecoveryRead planCompletion){
        public RecoveryContext{
            checkpointConversation=List.copyOf(checkpointConversation);
            snapshots=List.copyOf(snapshots);
            Objects.requireNonNull(planApplicability);
            if(planApplicability==PlanApplicability.PLAN_REQUIRED){
                Objects.requireNonNull(planExecution);
                if(planExecution.status()!=PlanExecutionStore.Status.SUCCESS)
                    throw new IllegalArgumentException("Recovery Plan Step state is unavailable");
                Objects.requireNonNull(planCompletion);
                if(planCompletion.status()!=PlanCompletionStore.Status.SUCCESS)
                    throw new IllegalArgumentException("Recovery Plan Evidence authority is unavailable");
            }else if(planExecution!=null||planCompletion!=null){
                throw new IllegalArgumentException("Non-Plan Recovery cannot carry Step state");
            }
        }
    }
    public record Rejected(RecoveryStatus status,List<String> reasons)
            implements RecoveryResult{
        public Rejected{reasons=List.copyOf(reasons);}
    }
    public enum RecoveryStatus{
        NO_DRIFT,INSTRUCTION_DRIFT_INFORMATIONAL,TASK_RECOVERY_RUNTIME_DRIFT,
        ARTIFACT_UNAVAILABLE,ARTIFACT_INTEGRITY_FAILURE,CONTINUATION_UNAVAILABLE,
        UNRESOLVED_OCCURRENCE,OWNERSHIP_CONFLICT,VERSION_CONFLICT,
        RECOVERY_ADMISSION_FAILED,NO_APPROVED_PLAN,PLAN_AUTHORITY_UNAVAILABLE,
        PLAN_AUTHORITY_CONFLICT,PLAN_REVISION_INTEGRITY_FAILURE,
        PLAN_STEP_STATE_UNAVAILABLE,PLAN_STEP_STATE_INTEGRITY_FAILURE,
        PLAN_ATTEMPT_UNRESOLVED,PLAN_EVIDENCE_INTEGRITY_FAILURE,
        COMPLETION_AUTHORITY_INTEGRITY_FAILURE,
        COMPLETION_RULES_UNAVAILABLE,STEERING_AUTHORITY_INTEGRITY_FAILURE,
        LEGACY_PLAN_CONTINUATION_UNAVAILABLE,PLAN_TOOL_SCOPE_UNAVAILABLE,
        CAPSULE_INTEGRITY_FAILURE
    }
    public record LeaseRequest(String ownerInstanceId,String leaseToken,Instant acquiredAt,
            Instant expiresAt,Duration grace){
        public LeaseRequest{
            if(ownerInstanceId==null||ownerInstanceId.isBlank()
                    ||leaseToken==null||leaseToken.isBlank()
                    ||acquiredAt==null||expiresAt==null||grace==null||grace.isNegative()
                    ||!expiresAt.isAfter(acquiredAt))
                throw new IllegalArgumentException("Explicit valid Recovery lease values required");
        }

        @Override public String toString(){
            return "LeaseRequest[ownerInstanceId="+ownerInstanceId
                    +", leaseToken=<redacted>, acquiredAt="+acquiredAt
                    +", expiresAt="+expiresAt+", grace="+grace+"]";
        }
    }
}
