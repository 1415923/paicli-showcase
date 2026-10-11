package com.paicli.state;

import com.paicli.model.AgentMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextRebuilderTest {

    @TempDir
    Path workspace;

    private StateStore stateStore;
    private ContextRebuilder rebuilder;

    @BeforeEach
    void setUp() throws Exception {
        stateStore = new StateStore(workspace);
        rebuilder = new ContextRebuilder(stateStore, workspace, 2_000, 2_000);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (stateStore != null) {
            stateStore.close();
        }
    }

    @Test
    void checkpointMessagesTakePriorityAndPreserveToolMetadata() throws Exception {
        createTask("resume-001", "resume from checkpoint");
        stateStore.events.append("resume-001", 0, AgentMessage.userGoal("event message"));
        AgentMessage action = AgentMessage.actionWithToolCall(
                "checkpoint action", "call-001", "{\"name\":\"read_file\",\"arguments\":{}}");
        AgentMessage observation = AgentMessage.observationForToolCall("checkpoint result", "call-001");
        List<AgentMessage> checkpointMessages = List.of(
                AgentMessage.userGoal("checkpoint message"), action, observation);
        stateStore.checkpoints.save("resume-001", 4, TaskStore.STATUS_RUNNING, checkpointMessages);

        ContextRebuilder.ResumedContext context = rebuilder.rebuild("resume-001");

        assertEquals("resume-001", context.taskId());
        assertEquals("resume from checkpoint", context.goal());
        assertEquals(checkpointMessages, context.conversation());
        assertEquals(4, context.startIteration());
        assertEquals("call-001", context.conversation().get(1).toolCallId());
        assertEquals(action.toolCallJson(), context.conversation().get(1).toolCallJson());
    }

    @Test
    void eventsAreUsedWhenCheckpointDoesNotExist() throws Exception {
        createTask("resume-002", "resume from events");
        AgentMessage user = AgentMessage.userGoal("goal");
        AgentMessage action = AgentMessage.actionWithToolCall(
                "event action", "call-002", "{\"name\":\"list_files\",\"arguments\":{}}");
        AgentMessage observation = AgentMessage.observationForToolCall("files", "call-002");
        AgentMessage finalMessage = AgentMessage.finalAnswer("done");
        stateStore.events.append("resume-002", 0, user);
        stateStore.events.append("resume-002", 1, action);
        stateStore.events.append("resume-002", 1, observation);
        stateStore.events.append("resume-002", 2, finalMessage);

        ContextRebuilder.ResumedContext context = rebuilder.rebuild("resume-002");

        assertEquals(List.of(user, action, observation, finalMessage), context.conversation());
        assertEquals(2, context.startIteration());
        assertEquals("call-002", context.conversation().get(1).toolCallId());
        assertEquals(action.toolCallJson(), context.conversation().get(1).toolCallJson());
        assertTrue(stateStore.toolCalls.findByTask("resume-002").isEmpty());
        assertTrue(stateStore.approvals.findByTask("resume-002").isEmpty());
    }

    @Test
    void missingTaskFailsClearly() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> rebuilder.rebuild("missing-task"));

        assertTrue(error.getMessage().contains("Task not found: missing-task"));
    }

    @Test
    void projectMemoryAndInstructionsAreIncluded() throws Exception {
        createTask("resume-003", "load project context");
        stateStore.memory.append("architecture", "SQLite remains the primary state source.");
        Files.writeString(workspace.resolve("PAICLI.md"), "Use checkpoint state when resuming.");
        Files.writeString(workspace.resolve("AGENTS.md"), "Never bypass approval policy.");

        ContextRebuilder.ResumedContext context = rebuilder.rebuild("resume-003");

        assertTrue(context.projectMemoryContext().contains("SQLite remains the primary state source."));
        assertTrue(context.projectInstructions().content().contains("Use checkpoint state when resuming."));
        assertTrue(context.projectInstructions().content().contains("Never bypass approval policy."));
        assertEquals(List.of("PAICLI.md", "AGENTS.md"), context.projectInstructions().sources());
        assertTrue(context.conversation().isEmpty());
        assertEquals(0, context.startIteration());
    }

    private void createTask(String taskId, String goal) throws Exception {
        stateStore.tasks.createTask(taskId, goal, "mock", null, "on-request", 10);
    }
}
