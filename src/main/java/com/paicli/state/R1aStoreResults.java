package com.paicli.state;

/** Operation-specific durable-store outcomes. SQL infrastructure failures remain SQLExceptions. */
public final class R1aStoreResults {
    private R1aStoreResults() { }

    public enum CreateResult {
        SUCCESS, NOT_FOUND, ALREADY_EXISTS, INVALID_STATE, INTEGRITY_VIOLATION
    }

    public enum PointerCasResult {
        SUCCESS, NOT_FOUND, VERSION_CONFLICT, EXPECTATION_MISMATCH,
        INVALID_STATE, INTEGRITY_VIOLATION
    }

    public enum OccurrenceAdmissionResult {
        SUCCESS, NOT_FOUND, ALREADY_EXISTS, INVALID_STATE, INTEGRITY_VIOLATION
    }

    public enum OccurrenceTransitionResult {
        SUCCESS, NOT_FOUND, INVALID_STATE, INTEGRITY_VIOLATION
    }

    public enum OwnershipResult {
        SUCCESS, NOT_FOUND, VERSION_CONFLICT, OWNERSHIP_CONFLICT, FENCING_REJECTED,
        INVALID_STATE, EXPIRED, INTEGRITY_VIOLATION
    }

    /** Explicit result for one authority-checked mutation. SQL failures are not mapped here. */
    public enum FencedMutationResult {
        SUCCESS, AUTHORITY_NOT_FOUND, OWNER_MISMATCH, RUN_MISMATCH,
        LEASE_TOKEN_MISMATCH, FENCING_REJECTED, VERSION_CONFLICT,
        LEASE_EXPIRED, TARGET_NOT_FOUND, INVALID_STATE, INTEGRITY_VIOLATION,
        ALREADY_EXISTS, STEERING_BLOCKED
    }

    public sealed interface FencedValueResult permits
            FencedValueResult.Success, FencedValueResult.Rejected {
        record Success(long value) implements FencedValueResult { }
        record Rejected(FencedMutationResult reason) implements FencedValueResult { }
    }

    public sealed interface ResumeRunAdmissionResult permits
            ResumeRunAdmissionResult.Success, ResumeRunAdmissionResult.Rejected {
        record Success(R1aDurableModels.DurableRun run,
                       R1aDurableModels.OwnershipLease ownership,
                       R1aDurableModels.TaskContextPointer pointer,
                       StructuredPlanModels.PlanApplicability planApplicability,
                       StructuredPlanModels.ApprovedPlan approvedPlan)
                implements ResumeRunAdmissionResult {
            public Success {
                java.util.Objects.requireNonNull(planApplicability);
                if (planApplicability == StructuredPlanModels.PlanApplicability.PLAN_REQUIRED
                        && approvedPlan == null) {
                    throw new IllegalArgumentException("PLAN_REQUIRED Resume requires approved Plan");
                }
                if (planApplicability != StructuredPlanModels.PlanApplicability.PLAN_REQUIRED
                        && approvedPlan != null) {
                    throw new IllegalArgumentException("Non-Plan Resume cannot bind approved Plan");
                }
            }
        }
        record Rejected(Reason reason) implements ResumeRunAdmissionResult { }
        enum Reason {
            NOT_FOUND, ALREADY_EXISTS, OWNERSHIP_CONFLICT, VERSION_CONFLICT,
            OCCURRENCE_UNRESOLVED, INVALID_STATE, INTEGRITY_VIOLATION, TASK_TERMINAL,
            NO_APPROVED_PLAN, PLAN_AUTHORITY_UNAVAILABLE,
            PLAN_REVISION_INTEGRITY_FAILURE, PLAN_STEP_STATE_UNAVAILABLE,
            PLAN_STEP_STATE_INTEGRITY_FAILURE, PLAN_ATTEMPT_UNRESOLVED,
            PLAN_EVIDENCE_INTEGRITY_FAILURE, COMPLETION_AUTHORITY_INTEGRITY_FAILURE,
            COMPLETION_RULES_UNAVAILABLE,
            STEERING_AUTHORITY_INTEGRITY_FAILURE,
            LEGACY_PLAN_CONTINUATION_UNAVAILABLE
        }
    }

    public sealed interface ArtifactReadResult permits
            ArtifactReadResult.Available, ArtifactReadResult.Unavailable {
        record Available(ProtectedInstructionArtifact artifact) implements ArtifactReadResult { }
        record Unavailable(Reason reason) implements ArtifactReadResult { }
        enum Reason { NOT_FOUND, INTEGRITY_FAILURE, EXTERNAL_REFERENCE_UNAVAILABLE }
    }

    public sealed interface ManifestCommitResult permits ManifestCommitResult.Success,
            ManifestCommitResult.Rejected {
        record Success(InstructionManifest manifest) implements ManifestCommitResult { }
        record Rejected(Reason reason) implements ManifestCommitResult { }
        enum Reason { NOT_FOUND, ALREADY_EXISTS, INVALID_STATE, INTEGRITY_VIOLATION }
    }
}
