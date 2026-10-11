package com.paicli.interactive.view;

import java.util.List;

public record PlanView(String applicability, String revisionId, int revisionNumber,
        int canonicalSchemaVersion, String lifecycle, boolean approved,
        long pointerVersion, List<StepView> steps) {
    public PlanView { steps = steps == null ? List.of() : List.copyOf(steps); }
}
