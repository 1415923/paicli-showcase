package com.paicli.interactive.view;

import java.util.List;

public record StepView(String stepId, int order, String title, String state,
        int currentAttemptNumber, long transitionVersion,
        List<PlanRequirementView> requirements) {
    public StepView {
        requirements = List.copyOf(requirements == null ? List.of() : requirements);
    }
}
