package com.paicli.identity;

/** Adds the frozen logical/revision column context to the shared stp_ identity family. */
public record PlanStepId(CanonicalId id, Kind kind) {
    public PlanStepId {
        if (id == null || id.family() != EntityFamily.PLAN_STEP || kind == null) {
            throw new IllegalArgumentException("Plan Step identity and column context are required");
        }
    }

    public enum Kind { LOGICAL_STEP, STEP_REVISION }
}
