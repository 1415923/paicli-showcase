package com.paicli.interactive.view;

/** Bounded, non-authoritative V3 Tool requirement projection for interactive views. */
public record PlanRequirementView(String requirementId, int requirementOrder,
        String toolName, String selectorKind, String safeSelectorSummary,
        int requiredSlots, int satisfiedSlots) {
    public PlanRequirementView {
        if (requirementId == null || requirementId.isBlank() || requirementOrder < 0
                || toolName == null || toolName.isBlank()
                || selectorKind == null || selectorKind.isBlank()
                || safeSelectorSummary == null || safeSelectorSummary.isBlank()
                || requiredSlots < 1 || satisfiedSlots < 0
                || satisfiedSlots > requiredSlots) {
            throw new IllegalArgumentException("invalid Plan requirement view");
        }
    }
}
