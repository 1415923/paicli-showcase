package com.paicli.runtime;

import com.paicli.config.Config;
import com.paicli.identity.CanonicalId;
import com.paicli.state.CheckpointStore;
import com.paicli.state.InstructionManifest;
import com.paicli.state.R1aDurableModels.DurableRun;
import com.paicli.state.R1aDurableModels.TaskContextGeneration;
import com.paicli.state.StateStore;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Shared, non-mutating Recovery admission preflight used by status and recover. */
public final class RecoveryPreflightValidator {
    private static final java.util.Set<String> TRUSTED_CHECKPOINT_STATES =
            java.util.Set.of("RUNNING","SEGMENT_FAILED","FINAL_ACCEPTED");
    private final StateStore state;
    private final InstructionManifestRuntime manifests;
    private final RuntimeInputsSupplier currentInputs;

    public RecoveryPreflightValidator(StateStore state, InstructionManifestRuntime manifests,
            RuntimeInputsSupplier currentInputs) {
        this.state=Objects.requireNonNull(state);
        this.manifests=Objects.requireNonNull(manifests);
        this.currentInputs=Objects.requireNonNull(currentInputs);
    }

    public Result validate(CanonicalId taskId, Instant at) {
        RuntimeInputs inputs;
        try { inputs=currentInputs.get(); }
        catch (Exception unavailable) { inputs=null; }
        try { return validate(taskId,inputs,at); }
        catch (java.sql.SQLException unavailable) {
            return new Result(Eligibility.UNAVAILABLE,TaskRecoveryService.RecoveryStatus
                    .CONTINUATION_UNAVAILABLE,List.of("RECOVERY_PREFLIGHT_UNAVAILABLE"),null);
        }
    }

    public Result validate(CanonicalId taskId, Config config, RuntimeManifest runtime,
            int maxInstructionChars, Instant at) throws java.sql.SQLException {
        return validate(taskId,new RuntimeInputs(config,runtime,maxInstructionChars),at);
    }

    private Result validate(CanonicalId taskId, RuntimeInputs inputs, Instant at)
            throws java.sql.SQLException {
        try {
            var durable=state.r1aDurable.findTask(taskId).orElse(null);
            if(durable==null)return rejected(TaskRecoveryService.RecoveryStatus
                    .CONTINUATION_UNAVAILABLE,"TASK_NOT_FOUND");
            if(durable.isTerminal())return rejected(
                    TaskRecoveryService.RecoveryStatus.CONTINUATION_UNAVAILABLE,
                    "TASK_TERMINAL");
            if(state.tasks.findById(taskId.value()).isEmpty())return rejected(
                    TaskRecoveryService.RecoveryStatus.CONTINUATION_UNAVAILABLE,
                    "TASK_DURABLE_FACTS_UNAVAILABLE");
            if(!state.followUps.peekChildOriginIntegrity(taskId))return integrity(
                    TaskRecoveryService.RecoveryStatus.CAPSULE_INTEGRITY_FAILURE,
                    "CHILD_CAPSULE_OR_BINDING_INTEGRITY_FAILURE");
            var lease=state.r1aDurable.readOwnership(taskId).orElse(null);
            if(lease!=null&&"ACTIVE".equals(lease.state())&&at.isBefore(lease.expiresAt()))
                return rejected(TaskRecoveryService.RecoveryStatus.OWNERSHIP_CONFLICT,
                        "OWNERSHIP_ACTIVE");
            CheckpointStore.CheckpointRecord checkpoint;
            try { checkpoint=state.checkpoints.loadLatest(taskId.value()).orElse(null); }
            catch (java.io.IOException corrupt) { return rejected(TaskRecoveryService
                    .RecoveryStatus.CONTINUATION_UNAVAILABLE,"TRUSTED_CHECKPOINT_INVALID"); }
            if(checkpoint==null||!TRUSTED_CHECKPOINT_STATES.contains(checkpoint.status()))
                return rejected(TaskRecoveryService.RecoveryStatus.CONTINUATION_UNAVAILABLE,
                        "TRUSTED_CHECKPOINT_UNAVAILABLE");
            InstructionManifestRuntime.FrozenInstructionContext frozen;
            try { frozen=manifests.loadFrozen(taskId); }
            catch(InstructionManifestRuntime.RecoveryMaterialException material){
                return new Result(material.status()==InstructionManifestRuntime
                        .RecoveryMaterialStatus.ARTIFACT_INTEGRITY_FAILURE
                        ?Eligibility.INTEGRITY_FAILURE:Eligibility.NOT_ELIGIBLE,
                        map(material.status()),List.of(safe(material.getMessage())),null);
            }
            var pointer=state.r1aDurable.readContextPointer(taskId).orElse(null);
            if(pointer==null)return rejected(TaskRecoveryService.RecoveryStatus
                    .CONTINUATION_UNAVAILABLE,"TASK_CONTEXT_POINTER_UNAVAILABLE");
            TaskContextGeneration generation=state.r1aDurable
                    .findGeneration(pointer.generationId()).orElse(null);
            if(generation==null||generation.tokenMetadata()==null)return rejected(
                    TaskRecoveryService.RecoveryStatus.CONTINUATION_UNAVAILABLE,
                    "FROZEN_RUNTIME_CONTRACT_UNAVAILABLE");
            R1bRuntimeContract original;
            try { original=R1bRuntimeContract.fromJson(generation.tokenMetadata()); }
            catch(IllegalArgumentException invalid){return rejected(TaskRecoveryService
                    .RecoveryStatus.CONTINUATION_UNAVAILABLE,
                    "FROZEN_RUNTIME_CONTRACT_INVALID");}
            DurableRun previous=state.r1aDurable.findRun(pointer.ownerRunId()).orElse(null);
            if(previous==null)return rejected(TaskRecoveryService.RecoveryStatus
                    .CONTINUATION_UNAVAILABLE,"PREDECESSOR_RUN_UNAVAILABLE");
            if(state.r1aDurable.hasStartedOrUnknownOccurrence(taskId))return rejected(
                    TaskRecoveryService.RecoveryStatus.UNRESOLVED_OCCURRENCE,
                    "STARTED_OR_UNKNOWN_OCCURRENCE");
            if(inputs==null)return new Result(Eligibility.UNKNOWN,
                    TaskRecoveryService.RecoveryStatus.CONTINUATION_UNAVAILABLE,
                    List.of("RECOVERY_RUNTIME_INPUTS_UNAVAILABLE"),null);
            InstructionManifestRuntime.DriftResult drift=manifests.detectDrift(
                    taskId,inputs.maxInstructionChars());
            if(drift.status()==InstructionManifestRuntime.RecoveryMaterialStatus
                    .ARTIFACT_UNAVAILABLE)return new Result(Eligibility.NOT_ELIGIBLE,
                    map(drift.status()),drift.reasons(),null);
            if(drift.status()==InstructionManifestRuntime.RecoveryMaterialStatus
                    .ARTIFACT_INTEGRITY_FAILURE)return new Result(Eligibility.INTEGRITY_FAILURE,
                    map(drift.status()),drift.reasons(),null);
            if(drift.status()==InstructionManifestRuntime.RecoveryMaterialStatus
                    .INSTRUCTION_DRIFT_INCOMPATIBLE)return new Result(Eligibility.NOT_ELIGIBLE,
                    map(drift.status()),drift.reasons(),null);
            InstructionManifest manifest=frozen.manifest();
            R1bRuntimeContract current=R1bRuntimeContract.capture(inputs.config(),inputs.runtime(),
                    manifest.id(),manifest.canonicalHash());
            R1bRuntimeContract.Compatibility compatibility=original.compareForRecovery(current);
            if(!compatibility.compatible())return new Result(Eligibility.NOT_ELIGIBLE,
                    TaskRecoveryService.RecoveryStatus.TASK_RECOVERY_RUNTIME_DRIFT,
                    compatibility.driftCodes(),null);
            Material material=new Material(checkpoint,frozen,generation,original,
                    compatibility,drift,previous);
            return new Result(Eligibility.ELIGIBLE,
                    drift.status()==InstructionManifestRuntime.RecoveryMaterialStatus
                            .INSTRUCTION_DRIFT_INFORMATIONAL
                            ?TaskRecoveryService.RecoveryStatus.INSTRUCTION_DRIFT_INFORMATIONAL
                            :TaskRecoveryService.RecoveryStatus.NO_DRIFT,List.of(),material);
        } catch (RuntimeException failure) {
            return new Result(Eligibility.UNAVAILABLE,TaskRecoveryService.RecoveryStatus
                    .CONTINUATION_UNAVAILABLE,List.of("RECOVERY_PREFLIGHT_UNAVAILABLE"),null);
        }
    }

    private static Result rejected(TaskRecoveryService.RecoveryStatus status,String reason){
        return new Result(Eligibility.NOT_ELIGIBLE,status,List.of(reason),null);
    }
    private static Result integrity(TaskRecoveryService.RecoveryStatus status,String reason){
        return new Result(Eligibility.INTEGRITY_FAILURE,status,List.of(reason),null);
    }
    private static TaskRecoveryService.RecoveryStatus map(
            InstructionManifestRuntime.RecoveryMaterialStatus status){
        return switch(status){
            case ARTIFACT_UNAVAILABLE->TaskRecoveryService.RecoveryStatus.ARTIFACT_UNAVAILABLE;
            case ARTIFACT_INTEGRITY_FAILURE->TaskRecoveryService.RecoveryStatus.ARTIFACT_INTEGRITY_FAILURE;
            case INSTRUCTION_DRIFT_INCOMPATIBLE->TaskRecoveryService.RecoveryStatus.TASK_RECOVERY_RUNTIME_DRIFT;
            case INSTRUCTION_DRIFT_INFORMATIONAL->TaskRecoveryService.RecoveryStatus.INSTRUCTION_DRIFT_INFORMATIONAL;
            case NO_DRIFT->TaskRecoveryService.RecoveryStatus.NO_DRIFT;
        };
    }
    private static String safe(String value){
        return value!=null&&value.matches("[A-Za-z0-9._:-]{1,128}")
                ?value:"RECOVERY_MATERIAL_UNAVAILABLE";
    }

    public enum Eligibility { ELIGIBLE, NOT_ELIGIBLE, UNKNOWN, UNAVAILABLE, INTEGRITY_FAILURE }
    public record RuntimeInputs(Config config,RuntimeManifest runtime,int maxInstructionChars){
        public RuntimeInputs{Objects.requireNonNull(config);Objects.requireNonNull(runtime);}
    }
    @FunctionalInterface public interface RuntimeInputsSupplier{RuntimeInputs get()throws Exception;}
    public record Result(Eligibility eligibility,TaskRecoveryService.RecoveryStatus status,
            List<String> reasons,Material material){
        public Result{reasons=List.copyOf(reasons);}
    }
    public record Material(CheckpointStore.CheckpointRecord checkpoint,
            InstructionManifestRuntime.FrozenInstructionContext frozen,
            TaskContextGeneration generation,R1bRuntimeContract original,
            R1bRuntimeContract.Compatibility compatibility,
            InstructionManifestRuntime.DriftResult drift,DurableRun previous){ }
}
