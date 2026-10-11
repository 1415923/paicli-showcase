package com.paicli.interactive.runtime;

import com.paicli.state.CodingRunStore;
import com.paicli.state.StateStore;
import com.paicli.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CandidateVerificationGateTest {
    @TempDir Path workspace;

    @Test
    void onlySuccessfulWriteFromTheSamePersistedRunAdmitsVerification() throws Exception {
        try (StateStore state = new StateStore(workspace)) {
            state.tasks.createTask("task-1", "edit", "mock", "model",
                    "on-request", 4);
            state.codingRuns.create("run-1", "task-1", CodingRunStore.KIND_INITIAL,
                    "MAVEN_TEST_V1", 1, 3);
            PersistedAppliedCandidateGate gate = new PersistedAppliedCandidateGate(state);

            assertFalse(gate.hasAppliedCandidate("task-1", "run-1"));

            state.toolCalls.record("task-1", 4, "failed-write", "write_file",
                    "{}", ToolResult.error("rejected"));
            assertFalse(gate.hasAppliedCandidate("task-1", "run-1"));

            state.toolCalls.record("task-1", 4, "read-1", "read_file",
                    "{}", ToolResult.success("read"));
            assertFalse(gate.hasAppliedCandidate("task-1", "run-1"));

            state.toolCalls.record("task-1", 4, "write-1", "write_file",
                    "{}", ToolResult.success("written"));
            assertTrue(gate.hasAppliedCandidate("task-1", "run-1"));
            assertThrows(IllegalStateException.class,
                    () -> gate.hasAppliedCandidate("other-task", "run-1"));
        }
    }

    @Test
    void writesAtOrBeforeTheRunStartDoNotAdmitCandidateVerification() throws Exception {
        try (StateStore state = new StateStore(workspace)) {
            state.tasks.createTask("task-2", "resume edit", "mock", "model",
                    "on-request", 4);
            state.codingRuns.create("run-2", "task-2", CodingRunStore.KIND_RESUME,
                    "MAVEN_TEST_V1", 1, 5);
            state.toolCalls.record("task-2", 5, "old-write", "write_file",
                    "{}", ToolResult.success("old"));
            assertFalse(new PersistedAppliedCandidateGate(state)
                    .hasAppliedCandidate("task-2", "run-2"));
        }
    }
}