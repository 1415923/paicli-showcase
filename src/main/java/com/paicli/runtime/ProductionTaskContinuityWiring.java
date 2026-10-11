package com.paicli.runtime;

import com.paicli.config.Config;
import com.paicli.state.StateStore;

import java.util.Objects;

/** Minimal production entry wiring for explicit-only autonomous continuity. */
public final class ProductionTaskContinuityWiring {
    private ProductionTaskContinuityWiring() { }

    public static Status configure(CodingTaskRunner runner, Config config,
            StateStore stateStore) {
        Objects.requireNonNull(runner);
        Objects.requireNonNull(config);
        Objects.requireNonNull(stateStore);
        if (!config.isTaskContinuityEnabled()) return Status.disabled();
        Config.TaskContinuityConfig value = Objects.requireNonNull(config.taskContinuity(),
                "TASK_CONTINUITY_CONFIG_MISSING");
        TaskContinuityPolicy policy = new TaskContinuityPolicy(true,
                value.maxAdditionalSegments(), value.maxPlanAttempts(),
                value.maxTotalProviderTurns(), value.maxTotalToolCalls(),
                value.maxInputTokens(), value.maxOutputTokens(),
                value.maxActiveElapsedMillis());
        runner.withTaskContinuityPolicy(policy,
                new DurableTaskBudgetEvidenceSource(stateStore));
        return new Status(true, policy.policyHash());
    }

    public record Status(boolean enabled, String policyIdentity) {
        public static Status disabled() { return new Status(false, null); }
        public String displayState() { return enabled ? "ENABLED_EXPLICITLY" : "DISABLED"; }
    }
}
