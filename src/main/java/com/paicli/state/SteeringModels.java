package com.paicli.state;

import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;

import java.time.Instant;

/** Durable R1D Steering definition and authority facts. */
public final class SteeringModels {
    private SteeringModels() { }

    public enum Classification { NON_MATERIAL, MATERIAL, UNKNOWN, REJECT }
    public enum ClassificationOutcome { ACCEPT, ACCEPT_MATERIAL, NEEDS_CONFIRMATION, REJECT }
    public enum ConfirmationDecision { CONFIRM_NON_MATERIAL, CONFIRM_MATERIAL, REJECT, CANCEL }
    public enum State { CANDIDATE, NEEDS_CONFIRMATION, QUEUED, DEFERRED, APPLYING, APPLIED, REJECTED, CANCELLED }
    public enum Provenance { INTERACTIVE_EXPLICIT, INTERACTIVE_ACTIVE_TEXT }
    public enum ApplicationKind { CONSTRAINT_REVISION, PLAN_DRAFT, PLAN_DISABLED_CONSTRAINT }

    public record Target(CanonicalId threadId, CanonicalId taskId, CanonicalId runId,
            CanonicalId planRevisionId, String planHash, Long planPointerVersion,
            CanonicalId generationId, long contextPointerVersion) {
        public Target {
            require(threadId, EntityFamily.THREAD); require(taskId, EntityFamily.TASK);
            require(runId, EntityFamily.RUN); require(generationId, EntityFamily.GENERATION);
            if (contextPointerVersion < 0) throw new IllegalArgumentException("Invalid Context pointer version");
            if (planRevisionId == null) {
                if (planHash != null || planPointerVersion != null) {
                    throw new IllegalArgumentException("Partial Plan target is forbidden");
                }
            } else {
                require(planRevisionId, EntityFamily.PLAN);
                if (!hash(planHash) || planPointerVersion == null || planPointerVersion < 0) {
                    throw new IllegalArgumentException("Complete Plan target is required");
                }
            }
        }
    }

    public record Definition(CanonicalId steeringId, Target target, String content,
            String contentHash, Provenance provenance, String inputOwner,
            String inputSessionId, Instant createdAt) {
        public Definition {
            require(steeringId, EntityFamily.STEERING);
            if (target == null || content == null || content.isBlank() || content.length() > 4096
                    || !hash(contentHash) || provenance == null || blank(inputOwner)
                    || blank(inputSessionId) || createdAt == null) {
                throw new IllegalArgumentException("Invalid immutable Steering definition");
            }
        }
    }

    public record ClassificationFact(CanonicalId steeringId, int factNumber,
            Classification classification, ClassificationOutcome outcome,
            String classifierVersion, String reasonCode, Instant createdAt) {
        public ClassificationFact {
            require(steeringId, EntityFamily.STEERING);
            if (factNumber < 1 || classification == null || outcome == null
                    || blank(classifierVersion) || blank(reasonCode) || createdAt == null) {
                throw new IllegalArgumentException("Invalid Steering classification fact");
            }
        }
    }

    public record Queue(CanonicalId steeringId, CanonicalId taskId, Target target, State state,
            Long sequence, long version, String reasonCode, Instant updatedAt) {
        public Queue {
            require(steeringId, EntityFamily.STEERING); require(taskId, EntityFamily.TASK);
            if (target == null || state == null || version < 0 || updatedAt == null) {
                throw new IllegalArgumentException("Invalid Steering queue authority");
            }
        }
        public boolean terminal() { return state == State.APPLIED || state == State.REJECTED || state == State.CANCELLED; }
    }

    public record Application(CanonicalId steeringId, CanonicalId taskId,
            CanonicalId appliedByRunId, long queueVersion, String safePoint,
            ApplicationKind kind, String constraintRevisionId, CanonicalId planRevisionId,
            String applicationHash, Instant appliedAt) {
        public Application {
            require(steeringId,EntityFamily.STEERING);require(taskId,EntityFamily.TASK);
            require(appliedByRunId,EntityFamily.RUN);
            if(queueVersion<0||blank(safePoint)||kind==null||!hash(applicationHash)||appliedAt==null)
                throw new IllegalArgumentException("Invalid Steering application fact");
            if((kind==ApplicationKind.PLAN_DRAFT)!=(planRevisionId!=null)
                    ||(kind!=ApplicationKind.PLAN_DRAFT)!=(constraintRevisionId!=null))
                throw new IllegalArgumentException("Steering application artifact mismatch");
            if(planRevisionId!=null)require(planRevisionId,EntityFamily.PLAN);
        }
    }

    private static void require(CanonicalId id, EntityFamily family) {
        if (id == null || id.family() != family) throw new IllegalArgumentException("Expected " + family);
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
}
