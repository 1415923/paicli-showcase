package com.paicli.demo.artifact;

import com.paicli.demo.baseline.CoreDemoBaselineResult;
import com.paicli.demo.fixture.CoreDemoFixtureManifest;
import com.paicli.demo.runtime.CoreDemoExitCode;
import com.paicli.demo.runtime.CoreDemoWorkflowResult;
import java.util.Objects;

public record CoreDemoArtifactInput(String runId, CoreDemoFixtureManifest fixture,
        String fixtureManifestHash, CoreDemoBaselineResult baseline,
        CoreDemoWorkflowResult workflow, CoreDemoExitCode exitCode,
        String terminalStatus, String cleanupStatus) {
    public CoreDemoArtifactInput {
        Objects.requireNonNull(runId); Objects.requireNonNull(fixture);
        Objects.requireNonNull(fixtureManifestHash); Objects.requireNonNull(baseline);
        Objects.requireNonNull(exitCode); Objects.requireNonNull(terminalStatus);
        Objects.requireNonNull(cleanupStatus);
        if (!fixtureManifestHash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid fixture manifest hash");
        if (runId.isBlank() || runId.length() > 64 || terminalStatus.length() > 128
                || cleanupStatus.length() > 256)
            throw new IllegalArgumentException("Invalid artifact metadata bounds");
        if (baseline.sourceTreeHash() != null
                && !baseline.sourceTreeHash().equals(fixture.expectedBaselineTreeHash()))
            throw new IllegalArgumentException("Baseline tree identity mismatch");
        if (workflow != null && (!runId.equals(workflow.runId())
                || !fixture.scenarioId().equals(workflow.scenarioId())))
            throw new IllegalArgumentException("Workflow artifact identity mismatch");
    }
}
