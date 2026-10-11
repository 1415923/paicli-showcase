package com.paicli.interactive.view;

import java.time.Instant;
import java.util.List;

/** Immutable top-level R2 read model. It carries no execution authority or mutation token. */
public record InteractiveSessionViewModel(
        String snapshotId,
        Instant observedAt,
        String sessionId,
        TaskProjectionKey projectionKey,
        ViewFact<Boolean> durableAuthorityIntegrity,
        WorkspaceView workspace,
        ViewFact<ThreadView> thread,
        ViewFact<TaskView> task,
        ViewFact<RunView> run,
        ViewFact<GenerationView> generation,
        ViewFact<PlanView> plan,
        ViewFact<StepView> currentStep,
        ViewFact<AttemptView> currentAttempt,
        ViewFact<EvidenceView> evidence,
        ViewFact<Boolean> completion,
        ViewFact<RecoveryView> recovery,
        ViewFact<SteeringView> steering,
        ViewFact<FollowUpView> followUp,
        ViewFact<ContextGenerationView> context,
        ProviderView provider,
        OperationalStatusView operationalStatus,
        List<String> warnings) {
    public InteractiveSessionViewModel {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public SelectedTaskIdentity selectedTaskIdentity() {
        return projectionKey == null ? null : projectionKey.identity();
    }

    public TaskProjectionProfile projectionProfile() {
        return projectionKey == null ? TaskProjectionProfile.CURRENT_INTERACTIVE
                : projectionKey.profile();
    }
}
