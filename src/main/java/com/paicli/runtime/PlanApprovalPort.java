package com.paicli.runtime;

import com.paicli.state.StructuredPlanModels.Revision;

/** Explicit human/product approval boundary. The default is fail-closed. */
@FunctionalInterface
public interface PlanApprovalPort {
    Decision requestApproval(Revision revision, String boundedSummary);

    PlanApprovalPort DENY = (revision, summary) ->
            new Decision(false, "PLAN_APPROVAL_REQUIRED", "none", "r1c-a-v1");

    record Decision(boolean approved, String reason, String approvedBy, String policyVersion) {
        public Decision {
            if (reason == null || reason.isBlank() || approvedBy == null || approvedBy.isBlank()
                    || policyVersion == null || policyVersion.isBlank()) {
                throw new IllegalArgumentException("Complete Plan approval decision is required");
            }
        }
    }
}
