package com.paicli.runtime;

import java.util.List;
import java.util.Objects;

/** Typed, non-scheduling contracts for bounded execution-segment continuity. */
public final class TaskContinuityModels {
    private TaskContinuityModels() { }

    public enum SegmentTerminalReason {
        STRUCTURED_PLAN_REPAIR_FAILED,
        EFFECTFUL_BATCH_SERIALIZATION_REPEATED,
        INVALID_TOOL_CALL_RESPONSE,
        TOOL_PREFLIGHT_MODEL_CORRECTABLE,
        TOOL_PREFLIGHT_APPROVAL_REQUIRED,
        TOOL_PREFLIGHT_POLICY_DENIED,
        TOOL_PREFLIGHT_INTEGRITY_FAILURE,
        ITERATION_EXHAUSTED,
        VERIFICATION_REPAIR_EXHAUSTED,
        UNKNOWN_EFFECT,
        OTHER_TYPED_TERMINAL
    }

    public enum ContinuationDecision {
        START_NEXT_SEGMENT,
        WAIT_FOR_USER,
        TERMINAL_FAILURE,
        TERMINAL_SUCCESS
    }

    /** Fixed behavioral hints. They never participate in Tool authority projection. */
    public enum RecoveryDirective {
        NONE(""),
        REPLAN_WITH_REQUIRED_ADVISORY_FIELDS(
                "Produce a fresh Advisory Plan using the required wire fields. This instruction "
                        + "does not grant Tool authority."),
        ONE_EFFECTFUL_TOOL_CALL_PER_TURN(
                "Continue the task and issue at most one effectful Tool call per model turn. "
                        + "This instruction does not grant Tool authority."),
        CORRECT_INVALID_TOOL_RESPONSE(
                "Continue from the durable checkpoint and emit a valid Tool response. This "
                        + "instruction does not grant Tool authority."),
        CONTINUE_FROM_DURABLE_CHECKPOINT(
                "Continue from the durable checkpoint without replaying completed Tool effects. "
                        + "This instruction does not grant Tool authority."),
        ADDRESS_VERIFICATION_EVIDENCE(
                "Continue from the durable verification evidence without replaying completed "
                        + "Tool effects. This instruction does not grant Tool authority.");

        private final String prompt;
        RecoveryDirective(String prompt) { this.prompt = prompt; }
        public String prompt() { return prompt; }
    }

    /** Authoritative cumulative counters supplied by the execution evidence boundary. */
    public record BudgetEvidence(boolean complete, int providerTurns, int requestedToolCalls,
            int admittedToolCalls, int toolCalls, long inputTokens, long outputTokens,
            long activeElapsedMillis,
            List<String> missingEvidence) {
        public BudgetEvidence {
            missingEvidence = List.copyOf(Objects.requireNonNull(missingEvidence));
            if (providerTurns < 0 || requestedToolCalls < 0 || admittedToolCalls < 0
                    || toolCalls < 0 || inputTokens < 0 || outputTokens < 0
                    || activeElapsedMillis < 0) {
                throw new IllegalArgumentException("Continuity budget counters cannot be negative");
            }
            if (complete && !missingEvidence.isEmpty()) {
                throw new IllegalArgumentException("Complete budget evidence cannot list gaps");
            }
        }

        /** Compatibility constructor: historic callers supplied only executed Tool calls. */
        public BudgetEvidence(boolean complete, int providerTurns, int toolCalls,
                long inputTokens, long outputTokens, long activeElapsedMillis,
                List<String> missingEvidence) {
            this(complete, providerTurns, toolCalls, toolCalls, toolCalls, inputTokens,
                    outputTokens, activeElapsedMillis, missingEvidence);
        }

        public static BudgetEvidence unavailable(String reason) {
            return new BudgetEvidence(false, 0, 0, 0, 0, 0, 0, 0, List.of(reason));
        }
    }

    @FunctionalInterface
    public interface BudgetEvidenceSource {
        BudgetEvidence snapshot(String taskId, String runId);

        BudgetEvidenceSource UNAVAILABLE = (taskId, runId) ->
                BudgetEvidence.unavailable("AUTHORITATIVE_TASK_BUDGET_EVIDENCE_UNAVAILABLE");
    }

    public record Decision(ContinuationDecision decision, String code,
            int currentSegmentOrdinal, Integer admittedSegmentOrdinal,
            int planningAttemptOrdinal, RecoveryDirective directive,
            List<String> eligibilityFailures, BudgetEvidence budgetEvidence) {
        public Decision {
            Objects.requireNonNull(decision);
            Objects.requireNonNull(code);
            Objects.requireNonNull(directive);
            eligibilityFailures = List.copyOf(Objects.requireNonNull(eligibilityFailures));
            Objects.requireNonNull(budgetEvidence);
        }

        public boolean startsNextSegment() {
            return decision == ContinuationDecision.START_NEXT_SEGMENT;
        }
    }
}
