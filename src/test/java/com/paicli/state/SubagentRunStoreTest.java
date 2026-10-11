package com.paicli.state;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SubagentRunStoreTest {

    @TempDir
    Path tempDir;

    private StateStore stateStore;

    @BeforeEach
    void setUp() throws Exception {
        stateStore = new StateStore(tempDir);
        stateStore.tasks.createTask("task-001", "review code", "mock", null, "deny", 10);
        stateStore.codingRuns.create("run-001", "task-001", CodingRunStore.KIND_INITIAL, null, 1, 0);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (stateStore != null) {
            stateStore.close();
        }
    }

    @Test
    void createAndCompleteRunRoundTrip() throws Exception {
        stateStore.subagentRuns.createSubagentRun(
                "sub-001", "task-001", "run-001", "reviewer",
                SubagentRunStore.KIND_REVIEWER, "AFTER_EXECUTE",
                "stage-1", 1, "mock", true, "read_file,code_intelligence", "input-sha");
        stateStore.subagentRuns.completeSubagentRun("sub-001", "output-sha");

        SubagentRunStore.SubagentRunRecord record = stateStore.subagentRuns
                .findSubagentRunById("sub-001").orElseThrow();

        assertEquals("task-001", record.taskId());
        assertEquals("run-001", record.codingRunId());
        assertEquals(SubagentRunStore.KIND_REVIEWER, record.subagentKind());
        assertEquals(SubagentRunStore.STATUS_SUCCEEDED, record.status());
        assertTrue(record.readOnly());
        assertEquals("output-sha", record.outputHash());
        assertNotNull(record.startedAt());
        assertNotNull(record.completedAt());
        assertNull(record.verificationStatus());
    }

    @Test
    void configuredVerificationStatusRoundTripsAndRejectsUnknownStatus()
            throws Exception {
        stateStore.subagentRuns.createSubagentRun(
                "sub-verifier", "task-001", "run-001", "verifier",
                SubagentRunStore.KIND_VERIFIER, "VERIFY",
                "verify-1", 1, null, true,
                "capabilities=[CONFIGURED_VERIFICATION]", "input-sha");

        stateStore.subagentRuns.completeConfiguredVerification(
                "sub-verifier", "result-sha", "TIMED_OUT");

        SubagentRunStore.SubagentRunRecord record = stateStore.subagentRuns
                .findSubagentRunById("sub-verifier").orElseThrow();
        assertEquals("TIMED_OUT", record.verificationStatus());
        assertThrows(IllegalArgumentException.class,
                () -> stateStore.subagentRuns.completeConfiguredVerification(
                        "missing", "hash", "UNKNOWN"));
    }

    @Test
    void failRunTruncatesErrorExcerpt() throws Exception {
        stateStore.subagentRuns.createSubagentRun(
                "sub-002", "task-001", "run-001", "reviewer",
                SubagentRunStore.KIND_REVIEWER, "AFTER_EXECUTE",
                "stage-2", 1, null, true, null, null);
        stateStore.subagentRuns.failSubagentRun("sub-002", "failure ".repeat(1000));

        SubagentRunStore.SubagentRunRecord record = stateStore.subagentRuns
                .findSubagentRunById("sub-002").orElseThrow();

        assertEquals(SubagentRunStore.STATUS_FAILED, record.status());
        assertEquals(4096, record.errorExcerpt().length());
    }

    @Test
    void markRunningResidueInterrupted() throws Exception {
        stateStore.subagentRuns.createSubagentRun(
                "sub-003", "task-001", "run-001", "scout",
                SubagentRunStore.KIND_SCOUT, "BEFORE_PLAN",
                "plan-1", 1, null, true, null, null);

        stateStore.subagentRuns.markInterrupted("sub-003", "interrupted after restart");

        SubagentRunStore.SubagentRunRecord record = stateStore.subagentRuns
                .findSubagentRunById("sub-003").orElseThrow();
        assertEquals(SubagentRunStore.STATUS_INTERRUPTED, record.status());
        assertEquals("interrupted after restart", record.errorExcerpt());
    }

    @Test
    void updateInputAndContextHashesPersistsOnlyBoundedHashes() throws Exception {
        stateStore.subagentRuns.createSubagentRun(
                "sub-context", "task-001", "run-001", "reviewer",
                SubagentRunStore.KIND_REVIEWER, "REVIEW",
                "context-1", 1, "mock", true, "tools=[read_file]", "input-old");

        stateStore.subagentRuns.updateInputAndContextHashes(
                "sub-context", "input-new", "context-sha");

        SubagentRunStore.SubagentRunRecord record = stateStore.subagentRuns
                .findSubagentRunById("sub-context").orElseThrow();
        assertEquals("input-new", record.inputHash());
        assertEquals("context-sha", record.contextHash());
    }

    @Test
    void findByTaskAndOccurrence() throws Exception {
        stateStore.subagentRuns.createSubagentRun(
                "sub-004", "task-001", "run-001", "reviewer",
                SubagentRunStore.KIND_REVIEWER, "AFTER_EXECUTE",
                "occ-1", 1, "mock", true, null, null);
        stateStore.subagentRuns.createSubagentRun(
                "sub-005", "task-001", "run-001", "reviewer",
                SubagentRunStore.KIND_REVIEWER, "AFTER_EXECUTE",
                "occ-1", 2, "mock", true, null, null);

        List<SubagentRunStore.SubagentRunRecord> byTask = stateStore.subagentRuns.findRunsByTask("task-001");
        List<SubagentRunStore.SubagentRunRecord> byOccurrence = stateStore.subagentRuns
                .findRunsByOccurrence("run-001", "AFTER_EXECUTE", "occ-1");

        assertEquals(2, byTask.size());
        assertEquals(2, byOccurrence.size());
        assertEquals(1, byOccurrence.getFirst().attemptNumber());
        assertEquals(2, byOccurrence.getLast().attemptNumber());
        assertEquals(List.of("sub-005"), stateStore.subagentRuns
                .findRecentRunsByTask("task-001", 1).stream()
                .map(SubagentRunStore.SubagentRunRecord::subagentRunId).toList());
        assertThrows(IllegalArgumentException.class,
                () -> stateStore.subagentRuns
                        .findRecentRunsByTask("task-001", 0));
    }

    @Test
    void closeInvalidatesStoreConnection() throws Exception {
        SubagentRunStore runs = stateStore.subagentRuns;
        stateStore.close();
        stateStore = null;

        assertThrows(SQLException.class, () -> runs.findRunsByTask("task-001"));
    }
}
