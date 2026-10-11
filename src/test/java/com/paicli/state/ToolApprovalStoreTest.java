package com.paicli.state;

import com.paicli.safety.ApprovalDecision;
import com.paicli.tool.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolApprovalStoreTest {

    @TempDir
    Path tempDir;

    private DatabaseManager databaseManager;
    private TaskStore tasks;
    private ToolCallStore toolCalls;
    private ApprovalStore approvals;

    @BeforeEach
    void setUp() throws Exception {
        databaseManager = new DatabaseManager(tempDir.resolve(".paicli").resolve("state"));
        Connection connection = databaseManager.getConnection();
        new MigrationRunner(connection).runMigrations();
        tasks = new TaskStore(connection);
        toolCalls = new ToolCallStore(connection);
        approvals = new ApprovalStore(connection);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    @Test
    void recordSuccessfulToolCallAndFindByTask() throws Exception {
        tasks.createTask("task-001", "list files", "mock", null, "on-request", 10);

        toolCalls.record("task-001", 1, "list_files", "{\"path\":\".\"}", ToolResult.success("a.txt\nb.txt"));

        List<ToolCallStore.ToolCallRecord> records = toolCalls.findByTask("task-001");
        assertEquals(1, records.size());
        ToolCallStore.ToolCallRecord record = records.getFirst();
        assertEquals("task-001", record.taskId());
        assertEquals(1, record.iteration());
        assertNull(record.toolCallId());
        assertEquals("list_files", record.toolName());
        assertEquals("{\"path\":\".\"}", record.argumentsJson());
        assertTrue(record.success());
        assertEquals("a.txt\nb.txt", record.output());
        assertNull(record.error());
        assertNotNull(record.createdAt());
    }

    @Test
    void recordToolCallIdAndReadBack() throws Exception {
        tasks.createTask("task-010", "tool call id", "ollama", "qwen3:14b", "on-request", 10);

        toolCalls.record("task-010", 4, "call-123", "grep", "{\"pattern\":\"TODO\"}",
                ToolResult.success("no matches"));

        ToolCallStore.ToolCallRecord record = toolCalls.findByTask("task-010").getFirst();
        assertEquals("call-123", record.toolCallId());
        assertEquals(4, record.iteration());
        assertEquals("grep", record.toolName());
    }

    @Test
    void recordErroredToolCallAndFindByToolName() throws Exception {
        tasks.createTask("task-002", "read missing", "mock", null, "on-request", 10);

        toolCalls.record("task-002", 2, "read_file", "{\"path\":\"missing.txt\"}",
                ToolResult.error("File not found"));

        List<ToolCallStore.ToolCallRecord> byTask = toolCalls.findByTask("task-002");
        assertEquals(1, byTask.size());
        assertFalse(byTask.getFirst().success());
        assertNull(byTask.getFirst().output());
        assertEquals("File not found", byTask.getFirst().error());

        List<ToolCallStore.ToolCallRecord> byTool = toolCalls.findByToolName("read_file");
        assertEquals(1, byTool.size());
        assertEquals("task-002", byTool.getFirst().taskId());
    }

    @Test
    void findRecentToolCallsReturnsAscendingSubset() throws Exception {
        tasks.createTask("task-003", "tools", "mock", null, "on-request", 10);
        toolCalls.record("task-003", 1, "list_files", "{}", ToolResult.success("one"));
        toolCalls.record("task-003", 2, "grep", "{}", ToolResult.success("two"));
        toolCalls.record("task-003", 3, "git_diff", "{}", ToolResult.success("three"));

        List<ToolCallStore.ToolCallRecord> recent = toolCalls.findRecentByTask("task-003", 2);

        assertEquals(List.of("grep", "git_diff"), recent.stream().map(ToolCallStore.ToolCallRecord::toolName).toList());
    }

    @Test
    void recordingToolCallForMissingTaskFailsForeignKey() {
        assertThrows(SQLException.class,
                () -> toolCalls.record("missing-task", 1, "list_files", "{}", ToolResult.success("ok")));
    }

    @Test
    void recordApprovalDecisionAndFindByTask() throws Exception {
        tasks.createTask("task-004", "approve", "mock", null, "on-request", 10);

        approvals.record("task-004", "list_files", "LOW", "ALLOW", "low risk allowed");

        List<ApprovalStore.ApprovalRecord> records = approvals.findByTask("task-004");
        assertEquals(1, records.size());
        ApprovalStore.ApprovalRecord record = records.getFirst();
        assertEquals("task-004", record.taskId());
        assertNull(record.iteration());
        assertNull(record.toolCallId());
        assertEquals("list_files", record.toolName());
        assertEquals("LOW", record.riskLevel());
        assertEquals("ALLOW", record.decision());
        assertEquals("low risk allowed", record.reason());
        assertNotNull(record.createdAt());
    }

    @Test
    void recordApprovalIterationAndToolCallIdAndReadBack() throws Exception {
        tasks.createTask("task-011", "approval correlation", "mock", null, "on-request", 10);

        approvals.record("task-011", 5, "call-approval-1", "run_command", "HIGH",
                "NEEDS_APPROVAL", "high risk needs approval");

        ApprovalStore.ApprovalRecord record = approvals.findByTask("task-011").getFirst();
        assertEquals(5, record.iteration());
        assertEquals("call-approval-1", record.toolCallId());
        assertEquals("run_command", record.toolName());
        assertEquals("NEEDS_APPROVAL", record.decision());
    }

    @Test
    void allApprovalDecisionTypesCanBeRecordedAndQueried() throws Exception {
        tasks.createTask("task-005", "approval matrix", "mock", null, "on-request", 10);

        approvals.record("task-005", "list_files", "LOW", "ALLOW", "allowed");
        approvals.record("task-005", "write_file", "MEDIUM", "DENY", "denied");
        approvals.record("task-005", "run_command", "HIGH", "NEEDS_APPROVAL", "needs human");

        assertEquals(3, approvals.findByTask("task-005").size());
        assertEquals("list_files", approvals.findByDecision("ALLOW").getFirst().toolName());
        assertEquals("write_file", approvals.findByDecision("DENY").getFirst().toolName());
        assertEquals("run_command", approvals.findByDecision("NEEDS_APPROVAL").getFirst().toolName());
    }

    @Test
    void approvalDecisionRecordCanBePersistedDirectly() throws Exception {
        tasks.createTask("task-006", "approval object", "mock", null, "deny", 10);

        approvals.record(ApprovalDecision.deny(
                "run_command", ApprovalDecision.RiskLevel.HIGH, "task-006", "deny mode"));

        ApprovalStore.ApprovalRecord record = approvals.findByTask("task-006").getFirst();
        assertEquals("DENY", record.decision());
        assertEquals("HIGH", record.riskLevel());
        assertEquals("run_command", record.toolName());
    }

    @Test
    void findRecentApprovalsReturnsAscendingSubset() throws Exception {
        tasks.createTask("task-007", "recent approvals", "mock", null, "on-request", 10);
        approvals.record("task-007", "list_files", "LOW", "ALLOW", "one");
        approvals.record("task-007", "write_file", "MEDIUM", "ALLOW", "two");
        approvals.record("task-007", "run_command", "HIGH", "NEEDS_APPROVAL", "three");

        List<ApprovalStore.ApprovalRecord> recent = approvals.findRecentByTask("task-007", 2);

        assertEquals(List.of("write_file", "run_command"),
                recent.stream().map(ApprovalStore.ApprovalRecord::toolName).toList());
    }

    @Test
    void recordingApprovalForMissingTaskFailsForeignKey() {
        assertThrows(SQLException.class,
                () -> approvals.record("missing-task", "list_files", "LOW", "ALLOW", "allowed"));
    }
}
