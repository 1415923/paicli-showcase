package com.paicli.runtime;

import com.paicli.identity.CanonicalId;
import com.paicli.runtime.TaskContinuityModels.BudgetEvidence;
import com.paicli.runtime.TaskContinuityModels.BudgetEvidenceSource;
import com.paicli.state.StateStore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reconstructs continuity budgets from the existing Provider telemetry, Tool occurrence,
 * Tool-call, Run and continuity-segment authorities. It owns no counters or parallel ledger.
 */
public final class DurableTaskBudgetEvidenceSource implements BudgetEvidenceSource {
    private final StateStore stateStore;

    public DurableTaskBudgetEvidenceSource(StateStore stateStore) {
        this.stateStore = Objects.requireNonNull(stateStore);
    }

    @Override
    public BudgetEvidence snapshot(String taskId, String runId) {
        List<String> missing = new ArrayList<>();
        try {
            CanonicalId task = CanonicalId.parse(taskId);
            CanonicalId run = CanonicalId.parse(runId);
            var runs = stateStore.r1aDurable.findRunsByTask(task);
            if (runs.stream().noneMatch(value -> value.id().equals(run))) {
                missing.add("RUN_NOT_IN_TASK_CHAIN");
            }
            var usage = stateStore.providerTelemetry.taskUsage(taskId);
            if (!usage.complete()) missing.add(usage.missingEvidence());

            int minimumExecutionTurns = stateStore.codingRuns.findByTask(taskId).stream()
                    .mapToInt(value -> Math.max(0,
                            value.lastIteration() - value.startedIteration())).sum();
            if (usage.providerTurns() < minimumExecutionTurns) {
                missing.add("PROVIDER_LEDGER_BELOW_DURABLE_RUN_ITERATIONS");
            }

            int admitted = stateStore.r1aDurable.countToolOccurrencesByTask(task);
            int executed = stateStore.toolCalls.findByTask(taskId).size();
            if (executed > admitted) missing.add("EXECUTED_TOOL_WITHOUT_OCCURRENCE");
            if (admitted > usage.requestedToolCalls()) {
                missing.add("TOOL_ADMISSION_EXCEEDS_PROVIDER_REQUESTS");
            }

            long elapsed = stateStore.codingRuns.cumulativeElapsedMillis(taskId, Instant.now());
            return new BudgetEvidence(missing.isEmpty(), usage.providerTurns(),
                    usage.requestedToolCalls(), admitted, executed, usage.inputTokens(),
                    usage.outputTokens(), elapsed, List.copyOf(missing));
        } catch (Exception error) {
            return BudgetEvidence.unavailable("DURABLE_TASK_BUDGET_RECONSTRUCTION_FAILED");
        }
    }
}
