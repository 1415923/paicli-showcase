package com.paicli.state;

import com.paicli.state.PlanCompletionModels.EffectClass;
import com.paicli.state.PlanCompletionModels.EvidenceKind;
import com.paicli.state.StructuredPlanModels.Revision;
import com.paicli.state.StructuredPlanModels.StepDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Runtime capability check for immutable Structured Plan completion profiles. */
public final class PlanCompletionProfilePolicy {
    private PlanCompletionProfilePolicy() { }

    public record Capabilities(boolean verificationAvailable) { }

    public record Validation(List<String> errors) {
        public Validation {
            errors = List.copyOf(Objects.requireNonNull(errors, "errors"));
        }
        public boolean producible() { return errors.isEmpty(); }
    }

    public static Validation validate(Revision revision, Capabilities capabilities) {
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(capabilities, "capabilities");
        List<String> errors = new ArrayList<>();
        for (StepDefinition step : revision.steps()) {
            String path = "steps[" + step.order() + "]";
            boolean verificationRequired = switch (step.completionRule()) {
                case VERIFICATION_PASSED -> true;
                case ALL_REQUIRED_EVIDENCE ->
                        step.requiredEvidenceKinds().contains(EvidenceKind.VERIFICATION_RESULT);
                case ANY_REQUIRED_EVIDENCE -> step.requiredEvidenceKinds().stream()
                        .allMatch(kind -> kind == EvidenceKind.VERIFICATION_RESULT);
                default -> false;
            };
            if (verificationRequired && !capabilities.verificationAvailable()) {
                errors.add(path + ".completionProfile: VERIFICATION_UNAVAILABLE");
            }

            boolean snapshotRequired = switch (step.completionRule()) {
                case SNAPSHOT_CREATED -> true;
                case ALL_REQUIRED_EVIDENCE ->
                        step.requiredEvidenceKinds().contains(EvidenceKind.SNAPSHOT);
                case ANY_REQUIRED_EVIDENCE -> step.requiredEvidenceKinds().stream()
                        .allMatch(kind -> kind == EvidenceKind.SNAPSHOT);
                default -> false;
            };
            if (snapshotRequired && step.effectClass() != EffectClass.WRITE) {
                errors.add(path + ".completionProfile: SNAPSHOT_REQUIRES_WRITE_EFFECT");
            }
        }
        return new Validation(errors);
    }
}
