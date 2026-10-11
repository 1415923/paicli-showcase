package com.paicli.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.paicli.config.Config;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.MockModelProvider;
import com.paicli.provider.ModelProvider;
import com.paicli.safety.ApprovalPolicy;
import com.paicli.safety.SafetyPolicy;
import com.paicli.state.ApprovalStore;
import com.paicli.state.FileSnapshotService;
import com.paicli.state.FileSnapshotStore;
import com.paicli.state.StateStore;
import com.paicli.state.TaskStore;
import com.paicli.state.ToolCallStore;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopStateStoreTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"object","array","prefix","multiple","embedded","large"})
    void jsonObservationProjectionIsStableInMemoryAndAllDurableCopies(String shape)throws Exception {
        String json="{\"to\\u006ben\":\"PRIVATE_CANARY\",\"nested\":[{\"password\":\"PASSWORD_CANARY\"}],\"value\":\"中文🙂\"}";
        String raw=switch(shape){
            case "array"->"[\r\n"+json+"\r\n]";
            case "prefix"->json+"\r\ntrailing evidence";
            case "multiple"->"{\"safe\":1}\n"+json;
            case "embedded"->"{\"safe\":1}\ntrailing text\n"+json;
            case "large"->"{\"token\":\"PRIVATE_CANARY\",\"value\":\""+"x".repeat(70*1024)+"\"}";
            default->json;
        };
        String task="json-"+shape;
        var registry=new ToolRegistry();registry.register(new RecordingTool("read_file",
                ToolResult.success(raw,Map.of("bytesRead",raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length))));
        var seen=new java.util.ArrayList<AgentMessage>();
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        ModelProvider provider=(messages,tools)->{
            int index=calls.getAndIncrement();
            seen.clear();seen.addAll(messages);
            if(index<2)return ModelResponse.toolCallResponse("Read fixture",new ToolCall("read_file",Map.of("path","sample.json"),"read-"+index));
            return ModelResponse.finalAnswer("Evidence inspected.");
        };
        assertTrue(statefulLoop(task,provider,registry,Config.MODE_ON_REQUEST).run("Inspect fixture").completed());
        var observations=seen.stream().filter(m->m.type()==AgentMessage.MessageType.OBSERVATION).toList();
        assertEquals(2,observations.size());
        var durable=stateStore.events.findMessages(task);
        for(var observation:observations){
            assertFalse(observation.content().contains("PRIVATE_CANARY"));
            assertFalse(observation.content().contains("PASSWORD_CANARY"));
            assertTrue(observation.content().contains("[tool fact: bytesRead="),observation.content());
            assertTrue(observation.content().length()<=64*1024);
            assertEquals(observation.content(),durable.stream().filter(m->m.type()==AgentMessage.MessageType.OBSERVATION
                    &&java.util.Objects.equals(m.toolCallId(),observation.toolCallId())).findFirst().orElseThrow().content());
        }
        String first=observations.getFirst().content();
        assertEquals(first,stateStore.toolCalls.findByTask(task).getFirst().output());
        var reloaded=stateStore.checkpoints.loadLatest(task).orElseThrow().messages();
        String checkpoint=MAPPER.writeValueAsString(reloaded);
        assertEquals(observations,reloaded.stream().filter(m->m.type()==AgentMessage.MessageType.OBSERVATION).toList());
        assertFalse(checkpoint.contains("PRIVATE_CANARY"));assertFalse(checkpoint.contains("PASSWORD_CANARY"));
        assertTrue(checkpoint.contains("bytesRead"));
        if(shape.equals("prefix"))assertTrue(first.contains("trailing evidence"));
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    void nullStateStoreKeepsLegacyBehavior() throws Exception {
        MockModelProvider provider = new MockModelProvider(List.of(ModelResponse.finalAnswer("legacy")));
        AgentLoop loop = new AgentLoop(provider, new ToolRegistry(), 3, null, null,
                null, null, null, null);

        AgentResult result = loop.run("legacy goal");

        assertTrue(result.completed());
        assertEquals("legacy", result.answer());
        assertTrue(stateStore.tasks.findAll(10).isEmpty());
    }

    @Test
    void enabledStateStorePersistsCompletedToolRun() throws Exception {
        String taskId = "state-task-001";
        RecordingTool tool = new RecordingTool("list_files", ToolResult.success("tool output"));
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        MockModelProvider provider = new MockModelProvider(List.of(
                ModelResponse.toolCallResponse("list", new ToolCall("list_files",
                        Map.of("path", ".", "apiKey", "synthetic-value"))),
                ModelResponse.finalAnswer("done")
        ));
        AgentLoop loop = statefulLoop(taskId, provider, registry, Config.MODE_ON_REQUEST);

        AgentResult result = loop.run("inspect workspace");

        assertTrue(result.completed());
        assertTrue(tool.executed);
        TaskStore.TaskRecord task = stateStore.tasks.findById(taskId).orElseThrow();
        assertEquals(TaskStore.STATUS_DONE, task.status());
        assertEquals(2, task.iterationsCompleted());

        List<AgentMessage> events = stateStore.events.findMessages(taskId);
        assertEquals(List.of(
                        AgentMessage.MessageType.USER,
                        AgentMessage.MessageType.ACTION,
                        AgentMessage.MessageType.OBSERVATION,
                        AgentMessage.MessageType.FINAL),
                events.stream().map(AgentMessage::type).toList());
        assertEquals("list", events.get(1).content());
        assertFalse(events.get(1).content().contains("synthetic-value"));

        ToolCallStore.ToolCallRecord toolCall = stateStore.toolCalls.findByTask(taskId).getFirst();
        assertTrue(toolCall.success());
        assertEquals("tool output", toolCall.output());
        assertTrue(toolCall.argumentsJson().contains("<redacted>"));
        assertFalse(toolCall.argumentsJson().contains("synthetic-value"));
        var smallArguments = MAPPER.readTree(toolCall.argumentsJson());
        assertEquals(".", smallArguments.get("path").asText());
        assertEquals("<redacted>", smallArguments.get("apiKey").asText());

        ApprovalStore.ApprovalRecord approval = stateStore.approvals.findByTask(taskId).getFirst();
        assertEquals("ALLOW", approval.decision());
        assertEquals(1, approval.iteration());
        assertNull(approval.toolCallId());

        var checkpoint = stateStore.checkpoints.loadLatest(taskId).orElseThrow();
        assertEquals("DONE", checkpoint.status());
        assertEquals(2, checkpoint.iteration());
        assertEquals(4, checkpoint.messages().size());
    }

    @Test
    void readOnlyBatchPersistsEveryCallAndObservationUnderOneAssistantTurn() throws Exception {
        String taskId="state-batch-001";
        RecordingTool tool=new RecordingTool("list_files",ToolResult.success("entry"));
        ToolRegistry registry=new ToolRegistry();registry.register(tool);
        MockModelProvider provider=new MockModelProvider(List.of(
                ModelResponse.toolCallBatchResponse("batch",List.of(
                        new ToolCall("list_files",Map.of("path","a"),"call-a"),
                        new ToolCall("list_files",Map.of("path","b"),"call-b"),
                        new ToolCall("list_files",Map.of("path","c"),"call-c")),
                        com.paicli.provider.ProviderResponseEnvelope.inspect(
                                List.of("call-a","call-b","call-c"),false),false),
                ModelResponse.finalAnswer("done")));

        AgentResult result=statefulLoop(taskId,provider,registry,Config.MODE_ON_REQUEST)
                .run("list three paths");

        assertTrue(result.completed());
        assertEquals(3,stateStore.toolCalls.findByTask(taskId).size());
        assertEquals(1,stateStore.events.findMessages(taskId).stream().filter(message->
                message.type()==AgentMessage.MessageType.ACTION).count());
        assertEquals(List.of("call-a","call-b","call-c"),stateStore.events
                .findMessages(taskId).stream().filter(message->
                        message.type()==AgentMessage.MessageType.OBSERVATION)
                .map(AgentMessage::toolCallId).toList());
    }

    @Test
    void rejectedEffectfulBatchPersistsTypedResultsWithoutToolOccurrenceAdmission() throws Exception {
        String taskId="state-effectful-batch-rejected";
        RecordingTool tool=new RecordingTool("write_file",ToolResult.success("should-not-run"));
        ToolRegistry registry=new ToolRegistry();registry.register(tool);
        MockModelProvider provider=new MockModelProvider(List.of(
                ModelResponse.toolCallBatchResponse("batch",List.of(
                        new ToolCall("write_file",Map.of("path","a.txt","content","a"),"call-a"),
                        new ToolCall("write_file",Map.of("path","b.txt","content","b"),"call-b")),
                        com.paicli.provider.ProviderResponseEnvelope.inspect(
                                List.of("call-a","call-b"),false),false),
                ModelResponse.finalAnswer("serialized")));

        AgentResult result=statefulLoop(taskId,provider,registry,Config.MODE_ON_REQUEST)
                .run("write safely");

        assertTrue(result.completed());
        assertFalse(tool.executed);
        assertTrue(stateStore.toolCalls.findByTask(taskId).isEmpty(),
                "rejected batch must not admit a Tool occurrence");
        assertTrue(stateStore.approvals.findByTask(taskId).isEmpty(),
                "rejected batch must not enter approval admission");
        List<AgentMessage> observations=stateStore.events.findMessages(taskId).stream()
                .filter(message->message.type()==AgentMessage.MessageType.OBSERVATION).toList();
        assertEquals(List.of("call-a","call-b"),observations.stream()
                .map(AgentMessage::toolCallId).toList());
        assertTrue(observations.stream().allMatch(message->message.content().contains(
                SharedToolBatchPolicy.NOT_EXECUTED_CODE)));
    }

    @Test
    void listFilesControlFactsReachContinuationAndDurableToolRecord() throws Exception {
        String taskId="state-list-facts-001";
        ToolResult result=ToolResult.success("README.md\ndocs/\nnotes.txt\n",Map.of(
                ToolFactProjection.VISIBLE_ENTRY_COUNT,3,
                ToolFactProjection.EXCLUDED_CONTROL_COUNT,1,
                ToolFactProjection.EXCLUDED_CONTROLS,".paicli/",
                ToolFactProjection.CONTROL_DIRECTORY_POLICY_APPLIED,true));
        RecordingTool tool=new RecordingTool("list_files",result);
        ToolRegistry registry=new ToolRegistry();registry.register(tool);
        AtomicReference<List<AgentMessage>> continuation=new AtomicReference<>();
        java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        ModelProvider provider=(messages,tools)->{
            if(calls.getAndIncrement()==0)return ModelResponse.toolCallResponse("list",
                    new ToolCall("list_files",Map.of("path","."),"call-list-facts"));
            continuation.set(List.copyOf(messages));
            return ModelResponse.finalAnswer("done");
        };

        assertTrue(statefulLoop(taskId,provider,registry,Config.MODE_ON_REQUEST)
                .run("list user files and ignore .paicli").completed());
        AgentMessage observationMessage=continuation.get().stream().filter(message->
                message.type()==AgentMessage.MessageType.OBSERVATION).findFirst()
                .orElseThrow();
        String observation=observationMessage.content();
        assertNull(observationMessage.toolCallJson(),
                "canonical Tool facts must not occupy ACTION-only toolCallJson");
        String durable=stateStore.toolCalls.findByTask(taskId).getFirst().output();
        for(String projected:List.of(observation,durable)){
            assertTrue(projected.contains("\"excludedControlCount\":1"),projected);
            assertTrue(projected.contains("\"excludedControls\":[\".paicli/\"]"),projected);
            assertTrue(projected.contains("README.md"),projected);
            assertFalse(projected.contains("state.db"),projected);
        }
        var providerFact=ToolFactProjection.fromObservation(
                observation,observationMessage.toolCallId());
        var durableFact=ToolFactProjection.fromObservation(
                durable,observationMessage.toolCallId());
        assertNotNull(providerFact);
        assertNotNull(durableFact);
        assertEquals(providerFact.factId(),durableFact.factId());
        assertEquals(providerFact.payloadDigest(),durableFact.payloadDigest());
    }

    @Test
    void needsApprovalIsRecordedWithoutExecutingTool() throws Exception {
        String taskId = "state-task-002";
        RecordingTool tool = new RecordingTool("run_command", ToolResult.success("must not execute"));
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        MockModelProvider provider = new MockModelProvider(List.of(
                ModelResponse.toolCallResponse("run", new ToolCall("run_command", Map.of("command", "echo test"))),
                ModelResponse.finalAnswer("blocked")
        ));
        AgentLoop loop = statefulLoop(taskId, provider, registry, Config.MODE_ON_REQUEST);

        AgentResult result = loop.run("run command");

        assertTrue(result.completed());
        assertFalse(tool.executed);
        ApprovalStore.ApprovalRecord approval = stateStore.approvals.findByTask(taskId).getFirst();
        assertEquals("NEEDS_APPROVAL", approval.decision());
        ToolCallStore.ToolCallRecord toolCall = stateStore.toolCalls.findByTask(taskId).getFirst();
        assertFalse(toolCall.success());
        assertTrue(toolCall.error().contains("APPROVAL_REQUIRED"));
        assertEquals(TaskStore.STATUS_DONE, stateStore.tasks.findById(taskId).orElseThrow().status());
    }

    @Test
    void providerFailureMarksTaskFailed() throws Exception {
        String taskId = "state-task-003";
        ModelProvider provider = (messages, tools) -> {
            throw new IllegalStateException("provider failed");
        };
        AgentLoop loop = new AgentLoop(provider, new ToolRegistry(), 3, null, null,
                stateStore, taskId, "failing", null);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> loop.run("fail safely"));

        assertEquals("provider failed", failure.getMessage());
        TaskStore.TaskRecord task = stateStore.tasks.findById(taskId).orElseThrow();
        assertEquals(TaskStore.STATUS_FAILED, task.status());
        assertNotNull(task.lastError());
        assertTrue(task.lastError().contains("provider failed"));
        assertEquals("FAILED", stateStore.checkpoints.loadLatest(taskId).orElseThrow().status());
    }

    @Test
    void taskIsRunningBeforeProviderInvocation() throws Exception {
        String taskId = "state-task-004";
        AtomicReference<String> observedStatus = new AtomicReference<>();
        ModelProvider provider = (messages, tools) -> {
            try {
                observedStatus.set(stateStore.tasks.findById(taskId).orElseThrow().status());
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return ModelResponse.finalAnswer("done");
        };
        AgentLoop loop = new AgentLoop(provider, new ToolRegistry(), 3, null, null,
                stateStore, taskId, "test", null);

        loop.run("observe running status");

        assertEquals(TaskStore.STATUS_RUNNING, observedStatus.get());
        assertEquals(TaskStore.STATUS_DONE, stateStore.tasks.findById(taskId).orElseThrow().status());
    }

    @Test
    void sensitiveHeadersAndOversizedOutputAreSanitizedBeforePersistence() throws Exception {
        String taskId = "state-task-005";
        String unsafeOutput = "Authorization: Bearer synthetic-secret\n"
                + "Cookie: session=synthetic-cookie; other=value\n"
                + "x".repeat(70 * 1024);
        RecordingTool tool = new RecordingTool("list_files", ToolResult.success(unsafeOutput));
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        MockModelProvider provider = new MockModelProvider(List.of(
                ModelResponse.toolCallResponse("list", new ToolCall("list_files", Map.of("path", "."))),
                ModelResponse.finalAnswer("done")
        ));
        AgentLoop loop = statefulLoop(taskId, provider, registry, Config.MODE_ON_REQUEST);

        AgentResult result = loop.run("sanitize output");

        String storedOutput = stateStore.toolCalls.findByTask(taskId).getFirst().output();
        assertTrue(storedOutput.length() <= 64 * 1024);
        assertTrue(storedOutput.contains("Authorization: <redacted>"));
        assertTrue(storedOutput.contains("Cookie: <redacted>"));
        assertTrue(storedOutput.endsWith("... (truncated)"));
        assertFalse(storedOutput.contains("synthetic-secret"));
        assertFalse(storedOutput.contains("synthetic-cookie"));
        assertFalse(stateStore.events.findMessages(taskId).stream()
                .map(AgentMessage::content)
                .anyMatch(content -> content != null
                        && (content.contains("synthetic-secret") || content.contains("synthetic-cookie"))));
        assertFalse(result.messages().stream()
                .anyMatch(message -> message.contains("synthetic-secret")
                        || message.contains("synthetic-cookie")));
    }

    @Test
    void maxIterationsMarksTaskAndCheckpointFailed() throws Exception {
        String taskId = "state-task-006";
        RecordingTool tool = new RecordingTool("list_files", ToolResult.success("again"));
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        MockModelProvider provider = new MockModelProvider(List.of(
                ModelResponse.toolCallResponse("loop", new ToolCall("list_files", Map.of("path", ".")))
        ));
        Config config = new Config(workspace.toString(), 30, 50_000, 200_000,
                Config.MODE_ON_REQUEST, 10_000);
        AgentLoop loop = new AgentLoop(provider, registry, 1, new ApprovalPolicy(config, taskId), null,
                stateStore, taskId, "mock", null);

        AgentResult result = loop.run("reach limit");

        assertFalse(result.completed());
        TaskStore.TaskRecord task = stateStore.tasks.findById(taskId).orElseThrow();
        assertEquals(TaskStore.STATUS_FAILED, task.status());
        assertEquals("Max iterations reached", task.lastError());
        assertEquals("FAILED", stateStore.checkpoints.loadLatest(taskId).orElseThrow().status());
    }

    @Test
    void sensitiveValuesInOrdinaryArgumentFieldsAreRedactedAsValidJson() throws Exception {
        String taskId = "state-task-007";
        RecordingTool tool = new RecordingTool("list_files", ToolResult.success("ok"));
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        MockModelProvider provider = new MockModelProvider(List.of(
                ModelResponse.toolCallResponse("list", new ToolCall("list_files", Map.of(
                        "path", ".",
                        "header", "Authorization: Bearer argument-secret",
                        "metadata", List.of("Cookie: session=argument-cookie", "safe")
                ))),
                ModelResponse.finalAnswer("done")
        ));
        AgentLoop loop = statefulLoop(taskId, provider, registry, Config.MODE_ON_REQUEST);

        loop.run("sanitize arguments");

        String argumentsJson = stateStore.toolCalls.findByTask(taskId).getFirst().argumentsJson();
        var arguments = MAPPER.readTree(argumentsJson);
        assertEquals("Authorization: <redacted>", arguments.get("header").asText());
        assertEquals("Cookie: <redacted>", arguments.get("metadata").get(0).asText());
        assertEquals("safe", arguments.get("metadata").get(1).asText());
        assertFalse(argumentsJson.contains("argument-secret"));
        assertFalse(argumentsJson.contains("argument-cookie"));
    }

    @Test
    void oversizedArgumentsBecomeParseableJsonSummary() throws Exception {
        String taskId = "state-task-008";
        RecordingTool tool = new RecordingTool("list_files", ToolResult.success("ok"));
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        MockModelProvider provider = new MockModelProvider(List.of(
                ModelResponse.toolCallResponse("list", new ToolCall("list_files", Map.of(
                        "path", ".",
                        "payload", "x".repeat(70 * 1024)
                ))),
                ModelResponse.finalAnswer("done")
        ));
        AgentLoop loop = statefulLoop(taskId, provider, registry, Config.MODE_ON_REQUEST);

        loop.run("summarize oversized arguments");

        String argumentsJson = stateStore.toolCalls.findByTask(taskId).getFirst().argumentsJson();
        var summary = MAPPER.readTree(argumentsJson);
        assertTrue(summary.isObject());
        assertTrue(summary.get("_truncated").asBoolean());
        assertEquals("tool arguments too large", summary.get("_reason").asText());
        assertTrue(summary.get("_original_bytes").asInt() > 64 * 1024);
        assertNull(summary.get("payload"));
    }

    @Test
    void allowedWriteFileCreatesAppliedSnapshot() throws Exception {
        String taskId = "state-write-allow";
        ToolRegistry registry = snapshotWriteRegistry(taskId);
        MockModelProvider provider = new MockModelProvider(List.of(
                ModelResponse.toolCallResponse("write", new ToolCall("write_file", Map.of(
                        "path", "allowed.txt", "content", "allowed"
                ))),
                ModelResponse.finalAnswer("done")
        ));
        AgentLoop loop = statefulLoop(taskId, provider, registry, Config.MODE_ON_REQUEST);

        loop.run("write allowed file");

        assertEquals("allowed", Files.readString(workspace.resolve("allowed.txt")));
        FileSnapshotStore.FileSnapshotRecord snapshot = stateStore.snapshots.findByTask(taskId).getFirst();
        assertEquals(FileSnapshotStore.OPERATION_CREATE, snapshot.operation());
        assertEquals(FileSnapshotStore.STATUS_APPLIED, snapshot.status());
        assertEquals("ALLOW", stateStore.approvals.findByTask(taskId).getFirst().decision());
    }

    @Test
    void deniedWriteFileDoesNotExecuteOrCreateSnapshot() throws Exception {
        String taskId = "state-write-deny";
        ToolRegistry registry = snapshotWriteRegistry(taskId);
        MockModelProvider provider = new MockModelProvider(List.of(
                ModelResponse.toolCallResponse("write", new ToolCall("write_file", Map.of(
                        "path", "denied.txt", "content", "denied"
                ))),
                ModelResponse.finalAnswer("blocked")
        ));
        AgentLoop loop = statefulLoop(taskId, provider, registry, Config.MODE_DENY);

        loop.run("deny write");

        assertFalse(Files.exists(workspace.resolve("denied.txt")));
        assertTrue(stateStore.snapshots.findByTask(taskId).isEmpty());
        assertEquals("DENY", stateStore.approvals.findByTask(taskId).getFirst().decision());
    }

    @Test
    void needsApprovalWriteFileDoesNotExecuteOrCreateSnapshot() throws Exception {
        String taskId = "state-write-needs-approval";
        Path target = workspace.resolve("existing.txt");
        Files.writeString(target, "original");
        ToolRegistry registry = snapshotWriteRegistry(taskId);
        MockModelProvider provider = new MockModelProvider(List.of(
                ModelResponse.toolCallResponse("overwrite", new ToolCall("write_file", Map.of(
                        "path", "existing.txt", "content", "replacement", "overwrite", true
                ))),
                ModelResponse.finalAnswer("blocked")
        ));
        AgentLoop loop = statefulLoop(taskId, provider, registry, Config.MODE_ON_REQUEST);

        loop.run("request overwrite");

        assertEquals("original", Files.readString(target));
        assertTrue(stateStore.snapshots.findByTask(taskId).isEmpty());
        assertEquals("NEEDS_APPROVAL", stateStore.approvals.findByTask(taskId).getFirst().decision());
    }

    private ToolRegistry snapshotWriteRegistry(String taskId) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new WriteFileTool(
                new SafetyPolicy(workspace),
                new FileSnapshotService(workspace, stateStore.snapshots),
                taskId
        ));
        return registry;
    }

    private AgentLoop statefulLoop(String taskId, ModelProvider provider, ToolRegistry registry,
                                   String approvalMode) {
        Config config = new Config(workspace.toString(), 30, 50_000, 200_000, approvalMode, 10_000);
        ApprovalPolicy approvalPolicy = new ApprovalPolicy(config, taskId);
        return new AgentLoop(provider, registry, 5, approvalPolicy, null,
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
            return "Test tool";
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
