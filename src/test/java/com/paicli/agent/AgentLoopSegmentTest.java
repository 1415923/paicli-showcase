package com.paicli.agent;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.paicli.config.Config;
import com.paicli.context.ContextModels.OperationKind;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.MockModelProvider;
import com.paicli.provider.ModelProvider;
import com.paicli.safety.ApprovalPolicy;
import com.paicli.safety.SafetyPolicy;
import com.paicli.state.FileSnapshotService;
import com.paicli.state.FileSnapshotStore;
import com.paicli.state.StateStore;
import com.paicli.state.TaskStore;
import com.paicli.tool.Tool;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolResult;
import com.paicli.tool.WriteFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopSegmentTest {

    @TempDir
    Path workspace;

    private StateStore stateStore;

    @BeforeEach
    void setUp() throws Exception {
        stateStore = new StateStore(workspace);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (stateStore != null) {
            stateStore.close();
        }
    }

    @Test
    void legacyRunStillOwnsTaskTerminalLifecycle() throws Exception {
        String taskId = "legacy-segment-control";
        AgentLoop loop = loop(taskId,
                new MockModelProvider(List.of(ModelResponse.finalAnswer("done"))),
                new ToolRegistry(), Config.MODE_ON_REQUEST);

        AgentResult result = loop.run("legacy goal");

        assertTrue(result.completed());
        assertEquals(TaskStore.STATUS_DONE,
                stateStore.tasks.findById(taskId).orElseThrow().status());
        assertEquals("DONE", stateStore.checkpoints.loadLatest(taskId).orElseThrow().status());
    }

    @Test
    void segmentContinuesConversationWithAdditionalPromptWithoutCompletingTask() throws Exception {
        String taskId = "segment-continue";
        prepareRunningTask(taskId);
        List<AgentMessage> conversation = List.of(
                AgentMessage.userGoal("original goal"),
                AgentMessage.action("inspect"),
                AgentMessage.observation("existing context"));
        AtomicReference<List<AgentMessage>> observedMessages = new AtomicReference<>();
        ModelProvider provider = (messages, tools) -> {
            observedMessages.set(List.copyOf(messages));
            return ModelResponse.finalAnswer("segment done");
        };
        AgentLoop loop = loop(taskId, provider, new ToolRegistry(), Config.MODE_ON_REQUEST);

        AgentSegmentResult result = loop.runSegment(new AgentSegmentRequest(
                "original goal", conversation, "Follow the approved plan", 2, 3));

        assertTrue(result.completed());
        assertEquals(2, result.startIteration());
        assertEquals(3, result.endIteration());
        assertEquals(1, result.iterationsExecuted());
        assertEquals("Follow the approved plan", observedMessages.get().getLast().content());
        assertEquals(AgentMessage.MessageType.USER,observedMessages.get().getLast().type(),
                "wire-facing provider projection stays a user message");
        assertEquals(5, result.conversation().size());
        assertEquals(AgentMessage.MessageType.RUNTIME_STEP_PROMPT,
                result.conversation().get(result.conversation().size()-2).type());
        assertEquals(AgentMessage.MessageType.FINAL, result.conversation().getLast().type());
        assertEquals(TaskStore.STATUS_RUNNING,
                stateStore.tasks.findById(taskId).orElseThrow().status());
        assertEquals(3, stateStore.tasks.findById(taskId).orElseThrow().iterationsCompleted());
        assertEquals(List.of(AgentMessage.MessageType.RUNTIME_STEP_PROMPT,
                        AgentMessage.MessageType.FINAL),
                stateStore.events.findMessages(taskId).stream().map(AgentMessage::type).toList());
        assertEquals("SEGMENT_COMPLETE",
                stateStore.checkpoints.loadLatest(taskId).orElseThrow().status());
    }

    @Test
    void finalSynthesisPromptHasExplicitDurableOriginAndUserWireProjection() throws Exception {
        String taskId="segment-final-origin";prepareRunningTask(taskId);
        AtomicReference<List<AgentMessage>> observed=new AtomicReference<>();
        ModelProvider provider=(messages,tools)->{
            observed.set(List.copyOf(messages));assertTrue(tools.isEmpty());
            return ModelResponse.finalAnswer("grounded final");
        };
        AgentSegmentResult result=loop(taskId,provider,new ToolRegistry(),Config.MODE_ON_REQUEST)
                .runSegment(new AgentSegmentRequest("goal",List.of(AgentMessage.userGoal("goal")),
                        "Synthesize from durable evidence",0,1,null,
                        OperationKind.FINAL_SYNTHESIS,true));
        assertTrue(result.completed());
        assertEquals(AgentMessage.MessageType.USER,observed.get().getLast().type());
        assertEquals(List.of(AgentMessage.MessageType.RUNTIME_FINAL_PROMPT,
                        AgentMessage.MessageType.FINAL),
                stateStore.events.findMessages(taskId).stream().map(AgentMessage::type).toList());
    }

    @Test
    void segmentIterationLimitReturnsControlWithoutFailingTask() throws Exception {
        String taskId = "segment-limit";
        prepareRunningTask(taskId);
        RecordingTool tool = new RecordingTool("list_files", ToolResult.success("again"));
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        AgentLoop loop = loop(taskId, new MockModelProvider(List.of(
                ModelResponse.toolCallResponse("list",
                        new ToolCall("list_files", Map.of("path", "."))))),
                registry, Config.MODE_ON_REQUEST);

        AgentSegmentResult result = loop.runSegment(new AgentSegmentRequest(
                "goal", List.of(AgentMessage.userGoal("goal")), null, 0, 1));

        assertFalse(result.completed());
        assertEquals(1, result.endIteration());
        assertEquals(1, result.iterationsExecuted());
        assertTrue(tool.executed);
        assertEquals(TaskStore.STATUS_RUNNING,
                stateStore.tasks.findById(taskId).orElseThrow().status());
        assertEquals("RUNNING", stateStore.checkpoints.loadLatest(taskId).orElseThrow().status());
    }

    @Test
    void segmentPersistsContinuousToolApprovalEventsAndCheckpoint() throws Exception {
        String taskId = "segment-persistence";
        prepareRunningTask(taskId);
        RecordingTool tool = new RecordingTool("list_files", ToolResult.success("files"));
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        AgentLoop loop = loop(taskId, new MockModelProvider(List.of(
                ModelResponse.toolCallResponse("list",
                        new ToolCall("list_files", Map.of("path", "."))),
                ModelResponse.finalAnswer("done"))), registry, Config.MODE_ON_REQUEST);

        AgentSegmentResult result = loop.runSegment(new AgentSegmentRequest(
                "goal", List.of(AgentMessage.userGoal("goal")), null, 4, 3));

        assertTrue(result.completed());
        assertEquals(6, result.endIteration());
        assertEquals(2, result.iterationsExecuted());
        assertEquals(6, stateStore.tasks.findById(taskId).orElseThrow().iterationsCompleted());
        assertEquals(5, stateStore.approvals.findByTask(taskId).getFirst().iteration());
        assertEquals("ALLOW", stateStore.approvals.findByTask(taskId).getFirst().decision());
        assertEquals(5, stateStore.toolCalls.findByTask(taskId).getFirst().iteration());
        assertEquals(List.of(
                        AgentMessage.MessageType.ACTION,
                        AgentMessage.MessageType.OBSERVATION,
                        AgentMessage.MessageType.FINAL),
                stateStore.events.findMessages(taskId).stream().map(AgentMessage::type).toList());
        assertEquals(6, stateStore.checkpoints.loadLatest(taskId).orElseThrow().iteration());
        assertEquals(TaskStore.STATUS_RUNNING,
                stateStore.tasks.findById(taskId).orElseThrow().status());
    }

    @Test
    void segmentDenyAndNeedsApprovalRemainFailClosed() throws Exception {
        assertBlockedSegment("segment-deny", "list_files", Config.MODE_DENY, "DENY");
        assertBlockedSegment("segment-needs", "run_command",
                Config.MODE_ON_REQUEST, "NEEDS_APPROVAL");
    }

    @Test
    void allowedSegmentWriteStillCreatesAppliedSnapshot() throws Exception {
        String taskId = "segment-write";
        prepareRunningTask(taskId);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new WriteFileTool(
                new SafetyPolicy(workspace),
                new FileSnapshotService(workspace, stateStore.snapshots),
                taskId));
        AgentLoop loop = loop(taskId, new MockModelProvider(List.of(
                ModelResponse.toolCallResponse("write", new ToolCall("write_file", Map.of(
                        "path", "segment.txt", "content", "segment content"))),
                ModelResponse.finalAnswer("written"))), registry, Config.MODE_ON_REQUEST);

        AgentSegmentResult result = loop.runSegment(new AgentSegmentRequest(
                "write file", List.of(AgentMessage.userGoal("write file")), null, 0, 2));

        assertTrue(result.completed());
        assertEquals("segment content", Files.readString(workspace.resolve("segment.txt")));
        FileSnapshotStore.FileSnapshotRecord snapshot = stateStore.snapshots
                .findByTask(taskId).getFirst();
        assertEquals(FileSnapshotStore.STATUS_APPLIED, snapshot.status());
        assertEquals(FileSnapshotStore.OPERATION_CREATE, snapshot.operation());
        assertEquals(TaskStore.STATUS_RUNNING,
                stateStore.tasks.findById(taskId).orElseThrow().status());
    }

    @Test
    void segmentProviderFailureDoesNotMarkTaskFailed() throws Exception {
        String taskId = "segment-provider-error";
        prepareRunningTask(taskId);
        AgentLoop loop = loop(taskId, (messages, tools) -> {
            throw new IllegalStateException("provider failed");
        }, new ToolRegistry(), Config.MODE_ON_REQUEST);

        assertThrows(IllegalStateException.class, () -> loop.runSegment(new AgentSegmentRequest(
                "goal", List.of(AgentMessage.userGoal("goal")), null, 3, 1)));

        TaskStore.TaskRecord task = stateStore.tasks.findById(taskId).orElseThrow();
        assertEquals(TaskStore.STATUS_RUNNING, task.status());
        assertEquals("SEGMENT_FAILED",
                stateStore.checkpoints.loadLatest(taskId).orElseThrow().status());
    }

    @Test
    void segmentRejectsTerminalTaskBeforeProviderInvocation() throws Exception {
        String taskId = "segment-terminal";
        prepareRunningTask(taskId);
        stateStore.tasks.markDone(taskId);
        AtomicBoolean providerInvoked = new AtomicBoolean();
        AgentLoop loop = loop(taskId, (messages, tools) -> {
            providerInvoked.set(true);
            return ModelResponse.finalAnswer("must not run");
        }, new ToolRegistry(), Config.MODE_ON_REQUEST);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> loop.runSegment(new AgentSegmentRequest(
                        "goal", List.of(AgentMessage.userGoal("goal")), null, 0, 1)));

        assertTrue(error.getMessage().contains("terminal task"));
        assertFalse(providerInvoked.get());
        assertEquals(TaskStore.STATUS_DONE,
                stateStore.tasks.findById(taskId).orElseThrow().status());
        assertTrue(stateStore.events.findMessages(taskId).isEmpty());
    }

    private void assertBlockedSegment(String taskId, String toolName,
                                      String approvalMode, String expectedDecision) throws Exception {
        prepareRunningTask(taskId);
        RecordingTool tool = new RecordingTool(toolName, ToolResult.success("must not execute"));
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        Map<String, Object> arguments = "run_command".equals(toolName)
                ? Map.of("command", "echo test") : Map.of("path", ".");
        AgentLoop loop = loop(taskId, new MockModelProvider(List.of(
                ModelResponse.toolCallResponse("call", new ToolCall(toolName, arguments)),
                ModelResponse.finalAnswer("blocked"))), registry, approvalMode);

        loop.runSegment(new AgentSegmentRequest(
                "goal", List.of(AgentMessage.userGoal("goal")), null, 0, 2));

        assertFalse(tool.executed);
        assertEquals(expectedDecision,
                stateStore.approvals.findByTask(taskId).getFirst().decision());
        assertFalse(stateStore.toolCalls.findByTask(taskId).getFirst().success());
        assertEquals(TaskStore.STATUS_RUNNING,
                stateStore.tasks.findById(taskId).orElseThrow().status());
    }

    private void prepareRunningTask(String taskId) throws Exception {
        stateStore.tasks.createTask(taskId, "goal", "mock", null,
                Config.MODE_ON_REQUEST, 20);
        stateStore.tasks.markRunning(taskId);
    }

    private AgentLoop loop(String taskId, ModelProvider provider,
                           ToolRegistry registry, String approvalMode) {
        Config config = new Config(workspace.toString(), 30, 50_000, 200_000,
                approvalMode, 10_000);
        return new AgentLoop(provider, registry, 20,
                new ApprovalPolicy(config, taskId), null,
                stateStore, taskId, "mock", null);
    }

    private static final class RecordingTool implements Tool {
        private final String name;
        private final ToolResult result;
        private boolean executed;

        private RecordingTool(String name, ToolResult result) {
            this.name = name;
            this.result = result;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "Segment test tool";
        }

        @Override
        public ToolDefinition toDefinition() {
            return new ToolDefinition(name, description(), JsonNodeFactory.instance.objectNode());
        }

        @Override
        public ToolResult execute(ToolCall call) {
            executed = true;
            return result;
        }
    }
}
