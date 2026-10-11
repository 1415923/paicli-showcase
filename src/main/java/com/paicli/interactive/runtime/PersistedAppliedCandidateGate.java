package com.paicli.interactive.runtime;

import com.paicli.runtime.CandidateVerificationGate;
import com.paicli.state.StateStore;

import java.sql.SQLException;
import java.util.Objects;

/** Admits candidate verification only after a successful persisted write in this run. */
final class PersistedAppliedCandidateGate implements CandidateVerificationGate {
    private final StateStore state;

    PersistedAppliedCandidateGate(StateStore state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    @Override
    public boolean hasAppliedCandidate(String taskId, String runId) throws SQLException {
        var run = state.codingRuns.findById(runId)
                .orElseThrow(() -> new IllegalStateException("candidate run is missing"));
        if (!taskId.equals(run.taskId())) {
            throw new IllegalStateException("candidate run/task identity mismatch");
        }
        return state.toolCalls.countSuccessfulByTaskAndToolAfterIteration(
                taskId, "write_file", run.startedIteration()) > 0;
    }
}