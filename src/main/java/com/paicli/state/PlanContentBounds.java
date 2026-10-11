package com.paicli.state;

import com.paicli.state.StructuredPlanModels.Revision;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Semantic admission bounds for immutable Structured Plan content. */
public final class PlanContentBounds {
    public static final int MIN_STEPS = 1;
    public static final int MAX_STEPS = 16;
    public static final int TITLE_MAX_CODE_POINTS = 256;
    public static final int TITLE_MAX_UTF8_BYTES = 1024;
    public static final int DEFINITION_MAX_CODE_POINTS = 2048;
    public static final int DEFINITION_MAX_UTF8_BYTES = 8192;
    public static final int GLOBAL_FIELD_MAX_CODE_POINTS = 2048;
    public static final int GLOBAL_FIELD_MAX_UTF8_BYTES = 8192;
    public static final int AGGREGATE_MAX_CODE_POINTS = 16384;
    public static final int AGGREGATE_MAX_UTF8_BYTES = 65536;

    private PlanContentBounds() { }

    public static Validation validate(Revision revision) {
        Objects.requireNonNull(revision, "revision");
        if (revision.steps().size() < MIN_STEPS || revision.steps().size() > MAX_STEPS) {
            return new Validation(false, List.of("steps:COUNT_OUT_OF_RANGE"));
        }
        List<StepText> steps = revision.steps().stream()
                .map(step -> new StepText(step.title(), step.definition(),
                        step.toolRequirements().stream().map(requirement -> String.join("\n",
                                requirement.requirementId(),
                                Integer.toString(requirement.requirementOrder()),
                                requirement.toolName(), requirement.effectClass().name(),
                                requirement.authoritySelector().kind().name(),
                                requirement.authoritySelector().canonicalJson(),
                                requirement.authoritySelector().digest(),
                                Integer.toString(requirement.minSuccesses()),
                                Integer.toString(requirement.maxOccurrences()),
                                requirement.freshnessPolicy().name())).collect(
                                        java.util.stream.Collectors.joining("\n--\n"))))
                .toList();
        return validateFields(revision.acceptanceCriteria(), revision.assumptions(),
                revision.risks(), revision.verificationStrategy(), steps);
    }

    public static Validation validateFields(String acceptanceCriteria, String assumptions,
            String risks, String verificationStrategy, List<StepText> steps) {
        List<String> errors = new ArrayList<>();
        List<StepText> safeSteps = steps == null ? List.of() : steps;
        if (safeSteps.size() < MIN_STEPS || safeSteps.size() > MAX_STEPS) {
            errors.add("steps:COUNT_OUT_OF_RANGE");
        }
        field("acceptanceCriteria", acceptanceCriteria, GLOBAL_FIELD_MAX_CODE_POINTS,
                GLOBAL_FIELD_MAX_UTF8_BYTES, errors);
        field("assumptions", assumptions, GLOBAL_FIELD_MAX_CODE_POINTS,
                GLOBAL_FIELD_MAX_UTF8_BYTES, errors);
        field("risks", risks, GLOBAL_FIELD_MAX_CODE_POINTS,
                GLOBAL_FIELD_MAX_UTF8_BYTES, errors);
        field("verificationStrategy", verificationStrategy, GLOBAL_FIELD_MAX_CODE_POINTS,
                GLOBAL_FIELD_MAX_UTF8_BYTES, errors);
        for (int index = 0; index < safeSteps.size(); index++) {
            StepText step = safeSteps.get(index);
            field("steps[" + index + "].title", step.title(), TITLE_MAX_CODE_POINTS,
                    TITLE_MAX_UTF8_BYTES, errors);
            field("steps[" + index + "].definition", step.definition(),
                    DEFINITION_MAX_CODE_POINTS, DEFINITION_MAX_UTF8_BYTES, errors);
        }
        long aggregateCodePoints = (long) codePoints(acceptanceCriteria) + codePoints(assumptions)
                + codePoints(risks) + codePoints(verificationStrategy);
        long aggregateBytes = (long) utf8Bytes(acceptanceCriteria) + utf8Bytes(assumptions)
                + utf8Bytes(risks) + utf8Bytes(verificationStrategy);
        for (StepText step : safeSteps) {
            aggregateCodePoints += codePoints(step.title()) + codePoints(step.definition())
                    + codePoints(step.requirementMaterial());
            aggregateBytes += utf8Bytes(step.title()) + utf8Bytes(step.definition())
                    + utf8Bytes(step.requirementMaterial());
        }
        if (aggregateCodePoints > AGGREGATE_MAX_CODE_POINTS) {
            errors.add("canonical:CODE_POINT_LIMIT_EXCEEDED");
        }
        if (aggregateBytes > AGGREGATE_MAX_UTF8_BYTES) {
            errors.add("canonical:UTF8_BYTE_LIMIT_EXCEEDED");
        }
        return new Validation(errors.isEmpty(), errors);
    }

    public static void requireValid(Revision revision) {
        Validation validation = validate(revision);
        if (!validation.valid()) throw new Violation(validation.errors());
    }

    private static void field(String name, String value, int maxCodePoints, int maxBytes,
            List<String> errors) {
        if (value == null) {
            errors.add(name + ":NULL");
            return;
        }
        if (codePoints(value) > maxCodePoints) errors.add(name + ":CODE_POINT_LIMIT_EXCEEDED");
        if (utf8Bytes(value) > maxBytes) errors.add(name + ":UTF8_BYTE_LIMIT_EXCEEDED");
    }

    public static int codePoints(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }

    public static int utf8Bytes(String value) {
        return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
    }

    public record StepText(String title, String definition, String requirementMaterial) {
        public StepText(String title, String definition) { this(title, definition, ""); }
        public StepText { requirementMaterial = requirementMaterial == null ? "" : requirementMaterial; }
    }

    public record Validation(boolean valid, List<String> errors) {
        public Validation { errors = List.copyOf(errors); }
    }

    public static final class Violation extends IllegalArgumentException {
        private final List<String> errors;
        public Violation(List<String> errors) {
            super("PLAN_CONTENT_BOUNDS_VIOLATION: " + String.join(",", errors.stream()
                    .limit(8).toList()));
            this.errors = List.copyOf(errors);
        }
        public List<String> errors() { return errors; }
    }
}
