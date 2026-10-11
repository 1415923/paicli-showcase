package com.paicli.runtime;

import java.util.List;

/** Value-free Structured Plan compatibility facts; response content is never exposed here. */
@FunctionalInterface
public interface PlanCompatibilityObserver {
    PlanCompatibilityObserver NOOP = fact -> { };

    void observed(Fact fact);

    enum ParseStatus {
        ACCEPTED,
        SCHEMA_INVALID,
        CONTENT_BOUNDS_INVALID,
        PARSE_INVALID,
        NOT_APPLICABLE
    }

    record Fact(ParseStatus planParseStatus, String schemaVersion,
            int unknownFieldCount, int missingRequiredFieldCount,
            int normalizationCount, int repairAttemptCount, String terminalReason,
            List<String> requiredFields, List<String> missingRequiredFields,
            List<String> unknownFields, List<String> validationErrors) {
        public Fact {
            if (planParseStatus == null || schemaVersion == null || schemaVersion.isBlank()
                    || terminalReason == null || terminalReason.isBlank()
                    || unknownFieldCount < 0 || missingRequiredFieldCount < 0
                    || normalizationCount < 0 || repairAttemptCount < 0) {
                throw new IllegalArgumentException("invalid Plan compatibility fact");
            }
            requiredFields = List.copyOf(requiredFields);
            missingRequiredFields = List.copyOf(missingRequiredFields);
            unknownFields = List.copyOf(unknownFields);
            validationErrors = List.copyOf(validationErrors);
            if (unknownFieldCount != unknownFields.size()
                    || missingRequiredFieldCount != missingRequiredFields.size()) {
                throw new IllegalArgumentException("Plan compatibility counts do not match paths");
            }
        }

        public static Fact notApplicable() {
            return new Fact(ParseStatus.NOT_APPLICABLE,
                    StructuredPlanResponseParser.WIRE_SCHEMA_VERSION,
                    0, 0, 0, 0, "NOT_APPLICABLE_NO_STRUCTURED_PLAN_STAGE",
                    StructuredPlanResponseParser.REQUIRED_SCHEMA_FIELDS,
                    List.of(), List.of(), List.of());
        }
    }
}
