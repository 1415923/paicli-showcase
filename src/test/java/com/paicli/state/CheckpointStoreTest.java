package com.paicli.state;

import com.paicli.model.AgentMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckpointStoreTest {

    @TempDir
    Path tempDir;

    private DatabaseManager databaseManager;
    private Connection connection;
    private TaskStore tasks;
    private CheckpointStore checkpoints;
    private Path checkpointsDir;

    @BeforeEach
    void setUp() throws Exception {
        databaseManager = new DatabaseManager(tempDir.resolve(".paicli").resolve("state"));
        connection = databaseManager.getConnection();
        new MigrationRunner(connection).runMigrations();
        tasks = new TaskStore(connection);
        checkpointsDir = tempDir.resolve(".paicli").resolve("checkpoints");
        checkpoints = new CheckpointStore(connection, checkpointsDir);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (databaseManager != null) {
            databaseManager.close();
        }
    }

    @Test
    void saveThenLoadLatestRestoresMessages() throws Exception {
        createTask("task-001");
        List<AgentMessage> messages = List.of(
                AgentMessage.userGoal("inspect project"),
                AgentMessage.action("list files"),
                AgentMessage.finalAnswer("done")
        );

        checkpoints.save("task-001", 2, TaskStore.STATUS_RUNNING, messages);

        CheckpointStore.CheckpointRecord checkpoint = checkpoints.loadLatest("task-001").orElseThrow();
        assertEquals("task-001", checkpoint.taskId());
        assertEquals(2, checkpoint.iteration());
        assertEquals(TaskStore.STATUS_RUNNING, checkpoint.status());
        assertEquals(messages, checkpoint.messages());
        assertFalse(checkpoint.createdAt().isBlank());
    }

    @Test
    void savingSameTaskTwiceKeepsOnlyLatestCheckpoint() throws Exception {
        createTask("task-002");
        checkpoints.save("task-002", 1, TaskStore.STATUS_RUNNING,
                List.of(AgentMessage.action("first")));

        checkpoints.save("task-002", 3, TaskStore.STATUS_DONE,
                List.of(AgentMessage.finalAnswer("latest")));

        CheckpointStore.CheckpointRecord checkpoint = checkpoints.loadLatest("task-002").orElseThrow();
        assertEquals(3, checkpoint.iteration());
        assertEquals(TaskStore.STATUS_DONE, checkpoint.status());
        assertEquals(List.of(AgentMessage.finalAnswer("latest")), checkpoint.messages());
        assertEquals(1, checkpointRowCount("task-002"));
    }

    @Test
    void toolCallMetadataRoundTrips() throws Exception {
        createTask("task-003");
        AgentMessage action = AgentMessage.actionWithToolCall(
                "call read_file", "call-003", "{\"name\":\"read_file\",\"arguments\":{\"path\":\"README.md\"}}");
        AgentMessage observation = AgentMessage.observationForToolCall("contents", "call-003");

        checkpoints.save("task-003", 1, TaskStore.STATUS_RUNNING, List.of(action, observation));

        List<AgentMessage> restored = checkpoints.loadLatest("task-003").orElseThrow().messages();
        assertEquals(List.of(action, observation), restored);
        assertEquals("call-003", restored.getFirst().toolCallId());
        assertEquals(action.toolCallJson(), restored.getFirst().toolCallJson());
    }

    @Test
    void markdownMirrorIsGeneratedAndReplacedWithLatestSummary() throws Exception {
        createTask("task-004");
        checkpoints.save("task-004", 1, TaskStore.STATUS_RUNNING,
                List.of(AgentMessage.action("old summary")));
        checkpoints.save("task-004", 2, TaskStore.STATUS_DONE,
                List.of(AgentMessage.finalAnswer("latest summary")));

        Path markdownFile = checkpointsDir.resolve("task-004.md");
        assertTrue(Files.exists(markdownFile));
        String markdown = Files.readString(markdownFile);
        assertTrue(markdown.contains("# PaiCLI Checkpoint"));
        assertTrue(markdown.contains("Iteration: 2"));
        assertTrue(markdown.contains("Status: `DONE`"));
        assertTrue(markdown.contains("latest summary"));
        assertFalse(markdown.contains("old summary"));
    }

    @Test
    void savingCheckpointForMissingTaskFailsForeignKey() {
        assertThrows(SQLException.class, () -> checkpoints.save(
                "missing-task", 0, TaskStore.STATUS_CREATED, List.of(AgentMessage.userGoal("goal"))));
        assertFalse(Files.exists(checkpointsDir.resolve("missing-task.md")));
    }

    @Test
    void loadLatestReturnsEmptyWhenNoCheckpointExists() throws Exception {
        createTask("task-005");

        assertTrue(checkpoints.loadLatest("task-005").isEmpty());
    }

    @Test
    void taskIdCannotEscapeCheckpointDirectory() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> checkpoints.save(
                "../outside", 0, TaskStore.STATUS_CREATED, List.of(AgentMessage.userGoal("goal"))));
        assertFalse(Files.exists(tempDir.resolve(".paicli").resolve("outside.md")));
    }

    @Test
    void sensitiveValuesAreRejectedBeforeAnythingIsPersisted() throws Exception {
        createTask("task-006");
        AgentMessage unsafe = AgentMessage.actionWithToolCall(
                "invoke provider", "call-006", "{\"arguments\":{\"apiKey\":\"live-value\"}}");

        assertThrows(IllegalArgumentException.class, () -> checkpoints.save(
                "task-006", 1, TaskStore.STATUS_RUNNING, List.of(unsafe)));

        assertTrue(checkpoints.loadLatest("task-006").isEmpty());
        assertFalse(Files.exists(checkpointsDir.resolve("task-006.md")));

        assertThrows(IllegalArgumentException.class, () -> checkpoints.save(
                "task-006", 1, "authorization=Bearer-value", List.of(AgentMessage.action("safe"))));
        assertTrue(checkpoints.loadLatest("task-006").isEmpty());
        assertFalse(Files.exists(checkpointsDir.resolve("task-006.md")));
    }

    @Test
    void oversizedMessageContentIsRejectedBeforeAnythingIsPersisted() throws Exception {
        createTask("task-007");
        AgentMessage oversized = AgentMessage.observation("x".repeat(65 * 1024));

        assertThrows(IllegalArgumentException.class, () -> checkpoints.save(
                "task-007", 1, TaskStore.STATUS_RUNNING, List.of(oversized)));

        assertTrue(checkpoints.loadLatest("task-007").isEmpty());
        assertFalse(Files.exists(checkpointsDir.resolve("task-007.md")));
    }

    @Test
    void rawHttpHeadersAreRejectedBeforeAnythingIsPersisted() throws Exception {
        createTask("task-008");
        AgentMessage rawRequest = AgentMessage.observation(
                "CONNECT private.example:443 HTTP/1.1\r\nHost: private.example\r\nCookie: session=value\r\n\r\n");

        assertThrows(IllegalArgumentException.class, () -> checkpoints.save(
                "task-008", 1, TaskStore.STATUS_RUNNING, List.of(rawRequest)));

        assertTrue(checkpoints.loadLatest("task-008").isEmpty());
        assertFalse(Files.exists(checkpointsDir.resolve("task-008.md")));
    }

    private void createTask(String taskId) throws Exception {
        tasks.createTask(taskId, "checkpoint test", "mock", null, "on-request", 10);
    }

    private int checkpointRowCount(String taskId) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM checkpoints WHERE task_id = ?")) {
            statement.setString(1, taskId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        }
    }
}
