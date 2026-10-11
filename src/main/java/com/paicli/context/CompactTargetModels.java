package com.paicli.context;

import com.paicli.context.ContextModels.OperationKind;
import java.util.List;
import java.util.Objects;

/** Immutable /compact target identity and fail-closed resolution outcomes. */
public final class CompactTargetModels {
    private CompactTargetModels() { }

    public enum ResolutionStatus {
        PRESENT,
        AMBIGUOUS,
        NOT_FOUND,
        NOT_APPLICABLE,
        STALE_SESSION_BINDING,
        CROSS_TASK_INVALID,
        CROSS_RUN_INVALID,
        INTEGRITY_FAILURE,
        UNAVAILABLE
    }

    public enum TargetRelation { CURRENT_SESSION_RUN, EXPLICIT_TARGET }

    public record CompactTargetIdentity(String workspaceId, String sessionId, String threadId,
            String taskId, String runId, OperationKind operation,
            String sourceContextGenerationId, long sourcePointerVersion,
            String sourceAuthorityGenerationId, String provider, String model,
            String capabilityProfile, String snapshotToken, TargetRelation relation) {
        public CompactTargetIdentity {
            Objects.requireNonNull(workspaceId); Objects.requireNonNull(threadId);
            Objects.requireNonNull(taskId); Objects.requireNonNull(runId);
            Objects.requireNonNull(operation); Objects.requireNonNull(sourceContextGenerationId);
            Objects.requireNonNull(sourceAuthorityGenerationId); Objects.requireNonNull(provider);
            Objects.requireNonNull(model); Objects.requireNonNull(capabilityProfile);
            Objects.requireNonNull(snapshotToken); Objects.requireNonNull(relation);
            if (sourcePointerVersion < 1) throw new IllegalArgumentException("invalid pointer version");
            if (relation==TargetRelation.CURRENT_SESSION_RUN
                    &&(sessionId==null||sessionId.isBlank()))
                throw new IllegalArgumentException("current Session target requires session identity");
        }
    }

    /** Safe ambiguity diagnostic; never contains provider configuration or filesystem data. */
    public record CandidateSummary(String taskId, String runId, OperationKind operation,
            String sourceContextGenerationId) {
        public CandidateSummary {
            taskId=shortId(taskId); runId=shortId(runId);
            sourceContextGenerationId=shortId(sourceContextGenerationId);
            Objects.requireNonNull(operation);
        }
    }

    public record CompactTargetResolution(ResolutionStatus status,
            CompactTargetIdentity identity, List<CandidateSummary> candidates) {
        public CompactTargetResolution {
            Objects.requireNonNull(status); candidates=List.copyOf(candidates);
            if ((status==ResolutionStatus.PRESENT)!=(identity!=null))
                throw new IllegalArgumentException("only PRESENT may contain target identity");
            if (status!=ResolutionStatus.AMBIGUOUS&&!candidates.isEmpty())
                throw new IllegalArgumentException("only AMBIGUOUS may contain candidates");
        }
        public static CompactTargetResolution present(CompactTargetIdentity identity) {
            return new CompactTargetResolution(ResolutionStatus.PRESENT,
                    Objects.requireNonNull(identity),List.of());
        }
        public static CompactTargetResolution failure(ResolutionStatus status) {
            if (status==ResolutionStatus.PRESENT||status==ResolutionStatus.AMBIGUOUS)
                throw new IllegalArgumentException("invalid failure status");
            return new CompactTargetResolution(status,null,List.of());
        }
        public static CompactTargetResolution ambiguous(List<CandidateSummary> candidates) {
            if (candidates==null||candidates.size()<2)
                throw new IllegalArgumentException("ambiguous target requires candidates");
            return new CompactTargetResolution(ResolutionStatus.AMBIGUOUS,null,candidates);
        }
    }

    private static String shortId(String value) {
        Objects.requireNonNull(value);
        return value.substring(0,Math.min(16,value.length()));
    }
}
