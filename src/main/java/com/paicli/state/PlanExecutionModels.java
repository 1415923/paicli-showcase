package com.paicli.state;

import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;

import java.time.Instant;
import java.util.Objects;

/** R1C-B1 mutable Step state and immutable composite Attempt history. */
public final class PlanExecutionModels {
    private PlanExecutionModels() { }

    public enum StepState {
        PENDING, READY, RUNNING, SUCCEEDED, FAILED, BLOCKED, CANCELLED, SKIPPED
    }

    public enum AttemptState {
        RUNNING, SUCCEEDED, FAILED, BLOCKED, CANCELLED, UNKNOWN
    }

    public enum Outcome {
        SUCCEEDED, FAILED, RETRYABLE_FAILURE, BLOCKED, CANCELLED, UNKNOWN
    }

    public record StepExecution(CanonicalId taskId, CanonicalId planRevisionId,
            CanonicalId stepRevisionId, StepState state, long transitionVersion,
            int currentAttemptNumber, CanonicalId initializedByRunId,
            CanonicalId lastUpdatedByRunId, Instant initializedAt, Instant updatedAt,
            Instant startedAt, Instant finishedAt, String boundedReason, String provenance) {
        public StepExecution {
            require(taskId, EntityFamily.TASK);
            require(planRevisionId, EntityFamily.PLAN);
            require(stepRevisionId, EntityFamily.PLAN_STEP);
            require(initializedByRunId, EntityFamily.RUN);
            require(lastUpdatedByRunId, EntityFamily.RUN);
            Objects.requireNonNull(state);
            Objects.requireNonNull(initializedAt);
            Objects.requireNonNull(updatedAt);
            if (transitionVersion < 0 || currentAttemptNumber < 0
                    || blank(provenance) || tooLong(boundedReason, 512)) {
                throw new IllegalArgumentException("Invalid Plan Step execution state");
            }
        }
    }

    public record Attempt(CanonicalId taskId, CanonicalId planRevisionId,
            CanonicalId stepRevisionId, CanonicalId runId, int attemptNumber,
            AttemptState state, long transitionVersion, int startIteration,
            Integer endIteration, Instant startedAt, Instant finishedAt,
            String failureClass, String boundedReason, String provenance) {
        public Attempt {
            require(taskId, EntityFamily.TASK);
            require(planRevisionId, EntityFamily.PLAN);
            require(stepRevisionId, EntityFamily.PLAN_STEP);
            require(runId, EntityFamily.RUN);
            Objects.requireNonNull(state);
            Objects.requireNonNull(startedAt);
            if (attemptNumber < 1 || transitionVersion < 0 || startIteration < 0
                    || (endIteration != null && endIteration < startIteration)
                    || tooLong(failureClass, 64) || tooLong(boundedReason, 512)
                    || blank(provenance)) {
                throw new IllegalArgumentException("Invalid Plan Step attempt");
            }
        }

        public String safeIdentity() {
            return taskId.value() + "/" + planRevisionId.value() + "/"
                    + stepRevisionId.value() + "/" + runId.value() + "#" + attemptNumber;
        }
    }

    public record AttemptStart(StepExecution step, Attempt attempt) {
        public AttemptStart {
            Objects.requireNonNull(step);
            Objects.requireNonNull(attempt);
            if (!step.taskId().equals(attempt.taskId())
                    || !step.planRevisionId().equals(attempt.planRevisionId())
                    || !step.stepRevisionId().equals(attempt.stepRevisionId())) {
                throw new IllegalArgumentException("Attempt does not match Step state");
            }
        }
    }

    private static void require(CanonicalId id, EntityFamily family) {
        if (id == null || id.family() != family) {
            throw new IllegalArgumentException("Expected " + family + " canonical ID");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean tooLong(String value, int limit) {
        return value != null && value.length() > limit;
    }
}
