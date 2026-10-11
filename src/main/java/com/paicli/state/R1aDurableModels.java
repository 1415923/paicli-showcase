package com.paicli.state;

import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;

import java.time.Instant;

/** Typed R1A durable records. Canonical IDs prevent selectors from reaching authoritative writes. */
public final class R1aDurableModels {
    private R1aDurableModels() { }

    public record WorkThread(CanonicalId id, String workspaceIdentity, String goal, String status,
                             Instant createdAt, Instant updatedAt) {
        public WorkThread { requireFamily(id, EntityFamily.THREAD); }
    }

    public record DurableTask(CanonicalId id, CanonicalId threadId, CanonicalId parentTaskId,
                              String goal, String status, String manifestId, String manifestHash,
                              Instant createdAt, Instant terminalAt) {
        public DurableTask {
            requireFamily(id, EntityFamily.TASK);
            requireFamily(threadId, EntityFamily.THREAD);
            if (parentTaskId != null) requireFamily(parentTaskId, EntityFamily.TASK);
        }

        /** Canonical durable Task terminal vocabulary. */
        public boolean isTerminal() {
            return switch (status) {
                case "COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED", "UNKNOWN" -> true;
                default -> false;
            };
        }
    }

    public record ThreadHead(CanonicalId threadId, CanonicalId headTaskId, long version) {
        public ThreadHead {
            requireFamily(threadId, EntityFamily.THREAD);
            requireFamily(headTaskId, EntityFamily.TASK);
        }
    }

    public record DurableRun(CanonicalId id, CanonicalId taskId, CanonicalId threadId,
                             String kind, CanonicalId resumedFromRunId, String manifestId,
                             String manifestHash, Instant createdAt, String terminalStatus) {
        public DurableRun {
            requireFamily(id, EntityFamily.RUN);
            requireFamily(taskId, EntityFamily.TASK);
            requireFamily(threadId, EntityFamily.THREAD);
            if (resumedFromRunId != null) requireFamily(resumedFromRunId, EntityFamily.RUN);
        }
    }

    public record TaskContextGeneration(CanonicalId id, CanonicalId taskId,
                                        CanonicalId parentGenerationId, CanonicalId createdByRunId,
                                        String manifestId, String manifestHash, String state,
                                        String tokenMetadata, Instant createdAt, Instant committedAt) {
        public TaskContextGeneration {
            requireFamily(id, EntityFamily.GENERATION);
            requireFamily(taskId, EntityFamily.TASK);
            if (parentGenerationId != null) requireFamily(parentGenerationId, EntityFamily.GENERATION);
            requireFamily(createdByRunId, EntityFamily.RUN);
        }
    }

    public record TaskContextPointer(CanonicalId taskId, CanonicalId generationId,
                                     CanonicalId ownerRunId, long version) {
        public TaskContextPointer {
            requireFamily(taskId, EntityFamily.TASK);
            requireFamily(generationId, EntityFamily.GENERATION);
            requireFamily(ownerRunId, EntityFamily.RUN);
        }
    }

    public record ConstraintRevision(String id, CanonicalId taskId, long revisionNumber,
                                     String canonicalContent, String canonicalHash, Instant createdAt) {
        public ConstraintRevision { requireFamily(taskId, EntityFamily.TASK); }
    }

    public record ConstraintActivation(CanonicalId taskId, String revisionId,
                                       CanonicalId activatedByRunId, long version, Instant activatedAt) {
        public ConstraintActivation {
            requireFamily(taskId, EntityFamily.TASK);
            if (activatedByRunId != null) requireFamily(activatedByRunId, EntityFamily.RUN);
        }
    }

    public record SideEffectOccurrence(CanonicalId id, CanonicalId threadId, CanonicalId taskId,
                                       CanonicalId runId, String iterationId, String logicalOperationKey,
                                       String toolCallId, String operationType, String target, String status,
                                       String canonicalInputHash, String resultHash, String externalOutcome,
                                       String finalCandidateDigest, Instant startedAt,
                                       Instant completedAt) {
        public SideEffectOccurrence(CanonicalId id, CanonicalId threadId, CanonicalId taskId,
                CanonicalId runId, String iterationId, String logicalOperationKey,
                String toolCallId, String operationType, String target, String status,
                String canonicalInputHash, String resultHash, String externalOutcome,
                Instant startedAt, Instant completedAt) {
            this(id,threadId,taskId,runId,iterationId,logicalOperationKey,toolCallId,
                    operationType,target,status,canonicalInputHash,resultHash,externalOutcome,
                    null,startedAt,completedAt);
        }

        public SideEffectOccurrence {
            requireFamily(id, EntityFamily.OCCURRENCE);
            requireFamily(threadId, EntityFamily.THREAD);
            requireFamily(taskId, EntityFamily.TASK);
            requireFamily(runId, EntityFamily.RUN);
        }
    }

    public record OwnershipLease(CanonicalId taskId, String ownerInstanceId, CanonicalId ownerRunId,
                                 String leaseToken, long fencingToken, long casVersion, String state,
                                 Instant acquiredAt, Instant heartbeatAt, Instant expiresAt) {
        public OwnershipLease {
            requireFamily(taskId, EntityFamily.TASK);
            requireFamily(ownerRunId, EntityFamily.RUN);
        }
        @Override public String toString() {
            return "OwnershipLease[taskId=" + taskId + ", ownerInstanceId=" + ownerInstanceId
                    + ", ownerRunId=" + ownerRunId + ", leaseToken=REDACTED, fencingToken="
                    + fencingToken + ", casVersion=" + casVersion + ", state=" + state
                    + ", acquiredAt=" + acquiredAt + ", heartbeatAt=" + heartbeatAt
                    + ", expiresAt=" + expiresAt + "]";
        }
    }

    /** Immutable Store-facing authority snapshot. The lease token is never rendered. */
    public record ExecutionAuthoritySnapshot(CanonicalId taskId, CanonicalId runId,
            String ownerInstanceId, String leaseToken, long fencingToken, long casVersion) {
        public ExecutionAuthoritySnapshot {
            if (taskId == null || taskId.family() != EntityFamily.TASK
                    || runId == null || runId.family() != EntityFamily.RUN
                    || ownerInstanceId == null || ownerInstanceId.isBlank()
                    || leaseToken == null || leaseToken.isBlank()
                    || fencingToken < 1 || casVersion < 1) {
                throw new IllegalArgumentException("Complete execution authority is required");
            }
        }

        @Override public String toString() {
            return "ExecutionAuthoritySnapshot[taskId=" + taskId + ", runId=" + runId
                    + ", ownerInstanceId=" + ownerInstanceId
                    + ", leaseToken=<redacted>, fencingToken=" + fencingToken
                    + ", casVersion=" + casVersion + "]";
        }
    }

    private static void requireFamily(CanonicalId id, EntityFamily family) {
        if (id == null || id.family() != family) {
            throw new IllegalArgumentException("Expected " + family + " canonical ID");
        }
    }
}
