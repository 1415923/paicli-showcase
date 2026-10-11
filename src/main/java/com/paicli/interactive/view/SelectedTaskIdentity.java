package com.paicli.interactive.view;

import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;

import java.time.Instant;
import java.util.Objects;

/** Snapshot-local identity anchor for every task-scoped R2 projection. */
public record SelectedTaskIdentity(
        String workspaceIdentity,
        String sessionId,
        CanonicalId threadId,
        CanonicalId taskId,
        RunSelectionRule runSelectionRule,
        CanonicalId selectedRunId,
        CanonicalId selectedGenerationId,
        SelectorKind selectorKind,
        String selectorSource,
        String resolutionToken,
        Instant observedAt,
        CanonicalId observedHeadTaskId,
        long observedHeadVersion,
        long selectedPointerVersion,
        Relation relation) {
    public SelectedTaskIdentity {
        Objects.requireNonNull(workspaceIdentity);
        Objects.requireNonNull(threadId);
        Objects.requireNonNull(taskId);
        Objects.requireNonNull(runSelectionRule);
        Objects.requireNonNull(selectorKind);
        Objects.requireNonNull(selectorSource);
        Objects.requireNonNull(resolutionToken);
        Objects.requireNonNull(observedAt);
        Objects.requireNonNull(relation);
        if(threadId.family()!=EntityFamily.THREAD||taskId.family()!=EntityFamily.TASK
                ||selectedRunId!=null&&selectedRunId.family()!=EntityFamily.RUN
                ||selectedGenerationId!=null&&selectedGenerationId.family()!=EntityFamily.GENERATION
                ||observedHeadTaskId!=null&&observedHeadTaskId.family()!=EntityFamily.TASK)
            throw new IllegalArgumentException("invalid selected task identity family");
        if (observedHeadVersion < 0 || selectedPointerVersion < -1) {
            throw new IllegalArgumentException("invalid projection version");
        }
    }

    public enum RunSelectionRule { POINTER_OWNER, LATEST_TASK_RUN, NONE }
    public enum SelectorKind { CURRENT_INTERACTIVE, CURRENT, LATEST, EXPLICIT, THREAD_ROW }
    public enum Relation { CURRENT_HEAD, HISTORICAL, STALE_SESSION_BINDING, CROSS_THREAD_INVALID }
}
