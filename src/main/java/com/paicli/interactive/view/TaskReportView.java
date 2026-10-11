package com.paicli.interactive.view;

import java.util.List;
import java.util.Objects;

/** Complete immutable input for the /report renderer. */
public record TaskReportView(
        TaskProjectionKey key,
        ScopedFact<WorkspaceView> workspace,
        ScopedFact<ThreadView> thread,
        ScopedFact<TaskView> task,
        ScopedFact<RunView> run,
        ScopedFact<GenerationView> generation,
        ScopedFact<ManifestView> manifest,
        ScopedFact<PlanView> plan,
        ScopedFact<StepView> currentStep,
        ScopedFact<AttemptView> currentAttempt,
        ScopedFact<EvidenceView> evidence,
        ScopedFact<Boolean> completion,
        ScopedFact<LegacyTaskView> legacyTask,
        ScopedFact<LegacyRunView> legacyRun,
        ScopedFact<LegacyPlanView> legacyPlan,
        ScopedFact<VerificationView> verification,
        ScopedFact<RepairView> repair,
        ScopedFact<CodeFactsView> codeFacts,
        ScopedFact<ChangeFactsView> changes,
        ScopedFact<ActivityFactsView> activity,
        ScopedFact<RecoveryView> recovery,
        ScopedFact<SteeringView> steering,
        ScopedFact<FollowUpView> followUp,
        ScopedFact<ContextGenerationView> context,
        ScopedFact<ProviderView> provider,
        ScopedFact<ProviderUsageView> providerUsage,
        OperationalStatusView operationalStatus,
        List<String> warnings) {
    public TaskReportView {
        Objects.requireNonNull(key);
        Objects.requireNonNull(workspace);
        Objects.requireNonNull(thread);
        Objects.requireNonNull(task);
        Objects.requireNonNull(run);
        Objects.requireNonNull(generation);
        Objects.requireNonNull(manifest);
        Objects.requireNonNull(plan);
        Objects.requireNonNull(currentStep);
        Objects.requireNonNull(currentAttempt);
        Objects.requireNonNull(evidence);
        Objects.requireNonNull(completion);
        Objects.requireNonNull(legacyTask);
        Objects.requireNonNull(legacyRun);
        Objects.requireNonNull(legacyPlan);
        Objects.requireNonNull(verification);
        Objects.requireNonNull(repair);
        Objects.requireNonNull(codeFacts);
        Objects.requireNonNull(changes);
        Objects.requireNonNull(activity);
        Objects.requireNonNull(recovery);
        Objects.requireNonNull(steering);
        Objects.requireNonNull(followUp);
        Objects.requireNonNull(context);
        Objects.requireNonNull(provider);
        Objects.requireNonNull(providerUsage);
        Objects.requireNonNull(operationalStatus);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public enum Scope { RUN_SCOPED, TASK_SCOPED, NOT_APPLICABLE, UNAVAILABLE }

    public record ScopedFact<T>(Scope scope, ViewFact.State state, T value,
            String diagnosticRef) {
        public ScopedFact {
            Objects.requireNonNull(scope);
            Objects.requireNonNull(state);
            boolean present = state == ViewFact.State.PRESENT;
            if (present != (value != null)) throw new IllegalArgumentException("invalid scoped fact");
            if (scope == Scope.NOT_APPLICABLE && state != ViewFact.State.NOT_APPLICABLE
                    || scope == Scope.UNAVAILABLE && state != ViewFact.State.UNAVAILABLE) {
                throw new IllegalArgumentException("scope and state disagree");
            }
        }
        public static <T> ScopedFact<T> present(Scope scope, T value) {
            if (scope != Scope.RUN_SCOPED && scope != Scope.TASK_SCOPED)
                throw new IllegalArgumentException("present scope required");
            return new ScopedFact<>(scope, ViewFact.State.PRESENT,
                    Objects.requireNonNull(value), null);
        }
        public static <T> ScopedFact<T> absent(Scope scope) {
            if (scope != Scope.RUN_SCOPED && scope != Scope.TASK_SCOPED)
                throw new IllegalArgumentException("fact scope required");
            return new ScopedFact<>(scope, ViewFact.State.ABSENT, null, null);
        }
        public static <T> ScopedFact<T> notApplicable() {
            return new ScopedFact<>(Scope.NOT_APPLICABLE, ViewFact.State.NOT_APPLICABLE,
                    null, null);
        }
        public static <T> ScopedFact<T> unavailable(String diagnosticRef) {
            return new ScopedFact<>(Scope.UNAVAILABLE, ViewFact.State.UNAVAILABLE,
                    null, diagnosticRef);
        }
        public boolean isPresent() { return state == ViewFact.State.PRESENT; }
    }

    public record ManifestView(String manifestId, String manifestHash) { }
    public record LegacyTaskView(String provider, String model, int iterationsCompleted,
            int maxIterations) { }
    public record LegacyRunView(String runId, String phase, String lastError,
            int lastIteration) { }
    public record LegacyPlanView(String planId, String status, String planText) { }
    public record VerificationView(String verificationId, String status,
            String completedAt) { }
    public record RepairView(int started, int completed, int failed, int running) { }
    public record CodeFactsView(String status, String indexedJavaFiles, int contextItems) { }
    public record ChangeFactsView(int proposals, int applied, int rejected, int cancelled,
            int additions, int deletions, int snapshots, int restoredSnapshots) { }
    public record ActivityFactsView(int toolCalls, int successfulWrites, int approvals) { }
    public record ProviderUsageView(int callCount, Long inputTokens, Long outputTokens,
            Long totalTokens, String coverage) { }
}
