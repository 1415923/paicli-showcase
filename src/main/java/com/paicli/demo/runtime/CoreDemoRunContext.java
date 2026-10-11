package com.paicli.demo.runtime;

import com.paicli.demo.approval.CoreDemoApprovalProfile;
import com.paicli.demo.artifact.CoreDemoArtifactScope;
import com.paicli.demo.baseline.CoreDemoBaselineResult;
import com.paicli.demo.fixture.CoreDemoFixtureManifest;
import com.paicli.demo.workspace.CoreDemoWorkspace;
import com.paicli.state.StateStore;

import java.util.Objects;

/** Trusted runtime context. It is never serialized or exposed as a model/tool argument. */
public record CoreDemoRunContext(
        String runId,
        String scenarioId,
        String fixtureManifestHash,
        String seedTreeHash,
        CoreDemoWorkspace workspace,
        String workspaceIdentityHash,
        StateStore stateStore,
        String taskSpecificationId,
        CoreDemoApprovalProfile approvalProfile,
        CoreDemoBudgets budgets,
        CoreDemoBaselineResult baselineEvidence,
        CoreDemoFixtureManifest fixtureManifest,
        CoreDemoArtifactScope artifactScope,
        boolean headless) {
    public static final String TASK_SPECIFICATION_ID = "core-java-member-discount-task-v1";

    public CoreDemoRunContext {
        Objects.requireNonNull(runId);
        Objects.requireNonNull(scenarioId);
        Objects.requireNonNull(fixtureManifestHash);
        Objects.requireNonNull(seedTreeHash);
        Objects.requireNonNull(workspace);
        Objects.requireNonNull(workspaceIdentityHash);
        Objects.requireNonNull(stateStore);
        if (!TASK_SPECIFICATION_ID.equals(taskSpecificationId)) {
            throw new IllegalArgumentException("Unknown Core Demo task specification");
        }
        Objects.requireNonNull(approvalProfile);
        Objects.requireNonNull(budgets);
        Objects.requireNonNull(baselineEvidence);
        Objects.requireNonNull(fixtureManifest);
        Objects.requireNonNull(artifactScope);
        if (!runId.equals(artifactScope.runId())
                || !fixtureManifestHash.equals(artifactScope.fixtureManifestHash())) {
            throw new IllegalArgumentException("Artifact scope identity mismatch");
        }
        if (!headless) throw new IllegalArgumentException("Core Demo context must be headless");
    }
}
