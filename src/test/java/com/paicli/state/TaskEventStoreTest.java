package com.paicli.state;

import com.paicli.model.AgentMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaskEventStoreTest {

    @TempDir
    Path tempDir;

    private DatabaseManager databaseManager;
    private Connection connection;
    private TaskStore tasks;
    private EventStore events;

    @BeforeEach
    void setUp() throws Exception {
        databaseManager = new DatabaseManager(tempDir.resolve(".paicli").resolve("state"));
        connection = databaseManager.getConnection();
        new MigrationRunner(connection).runMigrations();
        tasks = new TaskStore(connection);
        events = new EventStore(connection);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    @Test
    void createTaskThenFindById() throws Exception {
        tasks.createTask("task-001", "list files", "mock", null, "on-request", 10);

        TaskStore.TaskRecord record = tasks.findById("task-001").orElseThrow();

        assertEquals("task-001", record.taskId());
        assertEquals("list files", record.goal());
        assertEquals(TaskStore.STATUS_CREATED, record.status());
        assertEquals("mock", record.provider());
        assertNull(record.model());
        assertEquals("on-request", record.approvalMode());
        assertEquals(0, record.iterationsCompleted());
        assertEquals(10, record.maxIterations());
    }

    @Test
    void taskStatusCanMoveFromCreatedToRunningToDone() throws Exception {
        tasks.createTask("task-002", "read file", "ollama", "qwen3:14b", "workspace-write", 20);

        tasks.markRunning("task-002");
        assertEquals(TaskStore.STATUS_RUNNING, tasks.findById("task-002").orElseThrow().status());

        tasks.markDone("task-002");
        TaskStore.TaskRecord done = tasks.findById("task-002").orElseThrow();
        assertEquals(TaskStore.STATUS_DONE, done.status());
        assertNotNull(done.completedAt());
    }

    @Test
    void failedTaskRecordsLastError() throws Exception {
        tasks.createTask("task-003", "broken task", "mock", null, "deny", 3);

        tasks.markFailed("task-003", "provider failed");

        TaskStore.TaskRecord failed = tasks.findById("task-003").orElseThrow();
        assertEquals(TaskStore.STATUS_FAILED, failed.status());
        assertEquals("provider failed", failed.lastError());
        assertNotNull(failed.completedAt());
    }

    @Test
    void cancelledTaskIsTerminalAndCannotOverwriteCompletedTask() throws Exception {
        tasks.createTask("task-cancel", "cancel me", "mock", null, "on-request", 3);
        tasks.markRunning("task-cancel");
        tasks.markCancelled("task-cancel", false, "Cancellation reason: user requested");
        TaskStore.TaskRecord cancelled = tasks.findById("task-cancel").orElseThrow();
        assertEquals(TaskStore.STATUS_CANCELLED, cancelled.status());
        assertEquals("Cancellation reason: user requested", cancelled.lastError());
        assertTrue(TaskStore.isTerminal(cancelled.status()));

        tasks.createTask("task-complete", "done", "mock", null, "on-request", 3);
        tasks.markRunning("task-complete");
        tasks.markDone("task-complete");
        assertThrows(SQLException.class, () -> tasks.markCancelled(
                "task-complete", false, "late cancellation"));
        assertEquals(TaskStore.STATUS_DONE,
                tasks.findById("task-complete").orElseThrow().status());
    }

    @Test
    void updateIterationsPersistsCount() throws Exception {
        tasks.createTask("task-004", "iterate", "mock", null, "on-request", 10);

        tasks.updateIterations("task-004", 4);

        assertEquals(4, tasks.findById("task-004").orElseThrow().iterationsCompleted());
    }

    @Test
    void findByStatusReturnsMatchingTasks() throws Exception {
        tasks.createTask("task-005", "done", "mock", null, "on-request", 10);
        tasks.createTask("task-006", "failed", "mock", null, "on-request", 10);
        tasks.markDone("task-005");
        tasks.markFailed("task-006", "failed");

        List<TaskStore.TaskRecord> done = tasks.findByStatus(TaskStore.STATUS_DONE, 10);

        assertEquals(1, done.size());
        assertEquals("task-005", done.getFirst().taskId());
        assertEquals("task-005", tasks.findAll(10).stream()
                .filter(task -> task.status().equals(TaskStore.STATUS_DONE))
                .findFirst()
                .orElseThrow()
                .taskId());
    }

    @Test
    void duplicateTaskIdFails() throws Exception {
        tasks.createTask("task-007", "first", "mock", null, "on-request", 10);

        assertThrows(SQLException.class,
                () -> tasks.createTask("task-007", "second", "mock", null, "on-request", 10));
    }

    @Test
    void updatingMissingTaskFails() {
        assertThrows(SQLException.class, () -> tasks.markRunning("missing-task"));
        assertThrows(SQLException.class, () -> tasks.updateIterations("missing-task", 1));
        assertThrows(SQLException.class, () -> tasks.markDone("missing-task"));
        assertThrows(SQLException.class, () -> tasks.markFailed("missing-task", "failed"));
    }

    @Test
    void appendAndReadMessagesInStableOrder() throws Exception {
        tasks.createTask("task-008", "conversation", "mock", null, "on-request", 10);

        AgentMessage user = AgentMessage.userGoal("list files");
        AgentMessage action = AgentMessage.actionWithToolCall(
                "call list_files", "call-001", "{\"name\":\"list_files\",\"arguments\":{}}");
        AgentMessage observation = AgentMessage.observationForToolCall("file list", "call-001");
        AgentMessage finalAnswer = AgentMessage.finalAnswer("done");

        events.append("task-008", 0, user);
        events.append("task-008", 1, action);
        events.append("task-008", 1, observation);
        events.append("task-008", 2, finalAnswer);

        List<AgentMessage> messages = events.findMessages("task-008");

        assertEquals(List.of(user, action, observation, finalAnswer), messages);
        assertEquals("call-001", messages.get(1).toolCallId());
        assertEquals("{\"name\":\"list_files\",\"arguments\":{}}", messages.get(1).toolCallJson());
        assertEquals("call-001", messages.get(2).toolCallId());
        assertNull(messages.get(2).toolCallJson());
        assertEquals(3, events.countIterations("task-008"));
    }

    @Test
    void findRecentMessagesReturnsRecentMessagesInAscendingOrder() throws Exception {
        tasks.createTask("task-009", "recent", "mock", null, "on-request", 10);
        events.append("task-009", 0, AgentMessage.userGoal("goal"));
        events.append("task-009", 1, AgentMessage.action("action"));
        events.append("task-009", 1, AgentMessage.observation("observation"));
        events.append("task-009", 2, AgentMessage.finalAnswer("final"));

        List<AgentMessage> recent = events.findRecentMessages("task-009", 2);

        assertEquals(List.of(
                AgentMessage.observation("observation"),
                AgentMessage.finalAnswer("final")
        ), recent);
    }

    @Test
    void appendEventForMissingTaskFailsForeignKey() {
        assertThrows(SQLException.class,
                () -> events.append("missing-task", 0, AgentMessage.userGoal("goal")));
    }
}
