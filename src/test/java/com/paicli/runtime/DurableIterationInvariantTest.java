package com.paicli.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.agent.AgentLoop;
import com.paicli.agent.SafePreflightRejectionDiagnostic;
import com.paicli.config.Config;
import com.paicli.context.ContextModels.CapabilityProfile;
import com.paicli.context.ContextModels.LimitProvenance;
import com.paicli.context.ContextModels.OperationKind;
import com.paicli.context.ContextualModelProvider;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.ModelProvider;
import com.paicli.provider.ProviderResponseEnvelope;
import com.paicli.state.CodingRunStore;
import com.paicli.state.ContextRebuilder;
import com.paicli.state.R1aDurableModels.DurableRun;
import com.paicli.state.StateStore;
import com.paicli.state.TaskStore;
import com.paicli.tool.Tool;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Benchmark-independent regression contract for terminal durable Agent progress. */
class DurableIterationInvariantTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path workspace;

    @Test
    void typedProtocolFailurePreservesTerminalProgress() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger effects = new AtomicInteger();
        ModelProvider provider = (messages, definitions) -> switch (calls.incrementAndGet()) {
            case 1 -> read("protocol-read-1");
            default -> invalidProtocol();
        };

        try (StateStore state = new StateStore(workspace)) {
            CodingTaskResult result = runner(state, provider, registry(effects, false), config(3, false))
                    .run(CanonicalId.newId(EntityFamily.TASK).value(), "synthetic protocol goal");

            assertTerminalInvariant(state, result, 3, 3);
            assertEquals(1, effects.get());
            int terminalProgress = state.codingRuns.findById(result.runId())
                    .orElseThrow().lastIteration();
            assertThrows(FencedExecutionGuard.AuthorityLostException.class,
                    () -> result.executionGuard().terminalize(null, false, "repeated"));
            assertEquals(terminalProgress, state.codingRuns.findById(result.runId())
                    .orElseThrow().lastIteration(),
                    "rejected repeated terminalization cannot regress progress");
        }
    }

    @Test
    void repeatedEffectfulBatchPreservesTerminalProgressAndExecutesNothing() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger effects = new AtomicInteger();
        ModelProvider provider = (messages, definitions) -> effectfulBatch(calls.incrementAndGet());

        try (StateStore state = new StateStore(workspace)) {
            CodingTaskResult result = runner(state, provider, registry(effects, false), config(3, false))
                    .run(CanonicalId.newId(EntityFamily.TASK).value(), "synthetic batch goal");

            assertTerminalInvariant(state, result, 3, 3);
            assertEquals(3, calls.get(),
                    "each rejected batch must consume the existing iteration coordinate");
            assertTrue(result.summary().contains("iteration limit"), result.summary());
            assertEquals(0, effects.get(), "serialized effectful batches must have zero effects");
        }
    }

    @Test
    void preflightPolicyDenialUsesCheckpointProgressAndExecutesNoDeniedTool() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger effects = new AtomicInteger();
        AtomicReference<List<AgentMessage>> deniedContinuation = new AtomicReference<>();
        String deniedPath = "unsafe-denied-path-7e41.txt";
        ModelProvider provider = (messages, definitions) -> switch (calls.incrementAndGet()) {
            case 1 -> read("preflight-read-1");
            case 2 -> ModelResponse.toolCallBatchResponse("denied batch", List.of(
                    new ToolCall("read_file", Map.of("path", "synthetic-a"), "preflight-read-2"),
                    new ToolCall("write_file", Map.of("path", deniedPath), "preflight-denied")),
                    ProviderResponseEnvelope.inspect(List.of("preflight-read-2", "preflight-denied"), false), false);
            case 3 -> {
                deniedContinuation.set(List.copyOf(messages));
                yield ModelResponse.finalAnswer("completed after policy feedback");
            }
            default -> throw new AssertionError("synthetic Provider script exceeded three turns");
        };

        try (StateStore state = new StateStore(workspace)) {
            CodingTaskResult result = runner(state, provider, registry(effects, true), config(3, false))
                    .run(CanonicalId.newId(EntityFamily.TASK).value(), "synthetic preflight goal");

            var task = state.tasks.findById(result.taskId()).orElseThrow();
            var run = state.codingRuns.findById(result.runId()).orElseThrow();
            var checkpoint = state.checkpoints.loadLatest(result.taskId()).orElseThrow();
            int authoritative = Math.max(task.iterationsCompleted(), checkpoint.iteration());
            assertEquals(3, authoritative,
                    "the denial closure consumes iteration 2 and FINAL consumes one normal continuation iteration");
            assertEquals(3, task.iterationsCompleted(),
                    "Task, checkpoint, and Run retain one authoritative global coordinate");
            assertEquals(3, checkpoint.iteration());
            assertEquals(3, run.lastIteration());
            assertEquals(TaskStore.STATUS_DONE, task.status());
            assertEquals(CodingRunStore.PHASE_COMPLETED, run.phase());
            assertEquals("FINAL_ACCEPTED", checkpoint.status(),
                    "H5 retains its accepted-FINAL checkpoint authority before terminal Result projection");
            assertEquals(3, calls.get(), "the denied batch must reach exactly one next Provider turn");
            assertEquals(1, effects.get(), "only the first admitted read may execute");
            assertEquals(List.of("preflight-read-1"), state.toolCalls.findByTask(result.taskId())
                    .stream().map(record -> record.toolCallId()).toList(),
                    "the denied batch must create no Tool occurrence");
            assertPolicyDeniedClosure(deniedContinuation.get(), deniedPath);
            assertPolicyDeniedClosure(state.events.findMessages(result.taskId()), deniedPath);
            assertPolicyDeniedClosure(checkpoint.messages(), deniedPath);
            assertFalse(Files.exists(workspace.resolve(deniedPath)));
        }
    }

    @Test
    void normalMaxIterationReturnKeepsExistingProgressContract() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger effects = new AtomicInteger();
        ModelProvider provider = (messages, definitions) -> read("limit-read-" + calls.incrementAndGet());

        try (StateStore state = new StateStore(workspace)) {
            CodingTaskResult result = runner(state, provider, registry(effects, false), config(2, false))
                    .run(CanonicalId.newId(EntityFamily.TASK).value(), "synthetic bounded goal");

            assertTerminalInvariant(state, result, 2, 2);
            assertEquals(2, effects.get());
        }
    }

    @Test
    void failureBeforeAgentLoopKeepsZeroCoordinate() throws Exception {
        ModelProvider provider = (messages, definitions) -> { throw new IllegalStateException("synthetic planning failure"); };

        try (StateStore state = new StateStore(workspace)) {
            CodingTaskResult result = runner(state, provider, new ToolRegistry(), config(2, true))
                    .run(CanonicalId.newId(EntityFamily.TASK).value(), "synthetic planning goal");

            assertTerminalInvariant(state, result, 0, 0);
        }
    }

    @Test
    void successPathKeepsExistingCoordinateAndTerminalState() throws Exception {
        ModelProvider provider = (messages, definitions) -> ModelResponse.finalAnswer("synthetic done");

        try (StateStore state = new StateStore(workspace)) {
            CodingTaskResult result = runner(state, provider, new ToolRegistry(), config(2, false))
                    .run(CanonicalId.newId(EntityFamily.TASK).value(), "synthetic success goal");

            assertEquals(TaskStore.STATUS_DONE, result.finalStatus(), result.summary());
            assertEquals(1, state.tasks.findById(result.taskId()).orElseThrow().iterationsCompleted());
            var run = state.codingRuns.findById(result.runId()).orElseThrow();
            assertEquals(0, run.startedIteration());
            assertEquals(1, run.lastIteration());
            assertEquals(CodingRunStore.PHASE_COMPLETED, run.phase());
        }
    }

    @Test
    void resumeRunUsesTaskGlobalCoordinatesWithoutMutatingPredecessor() throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "synthetic frozen rule");
        AtomicInteger effects = new AtomicInteger();
        try (StateStore state = new StateStore(workspace)) {
            Config config = config(8, false);
            CanonicalId task = CanonicalId.newId(EntityFamily.TASK);
            Instant now = Instant.now();
            var root = new InstructionManifestRuntime(state, workspace).admitRoot(
                    task, "synthetic recoverable goal", 10_000, config,
                    RuntimeManifest.disabled(), now.minusSeconds(10));
            String predecessorId = root.initialRun().id().value();
            state.tasks.updateIterations(task.value(), 4);
            state.checkpoints.save(task.value(), 4, "RUNNING", List.of(
                    AgentMessage.userGoal("synthetic recoverable goal")));
            state.codingRuns.updateLastIteration(predecessorId, 4);

            TaskRecoveryService.Success recovery = assertInstanceOf(TaskRecoveryService.Success.class,
                    new TaskRecoveryService(state, new InstructionManifestRuntime(state, workspace))
                            .recover(task, config, RuntimeManifest.disabled(), 10_000,
                                    new TaskRecoveryService.LeaseRequest("synthetic-owner", "synthetic-lease",
                                            now, now.plusSeconds(30), Duration.ZERO)));
            ModelProvider provider = (messages, definitions) -> invalidProtocol();
            CodingTaskResult result = runner(state, provider, registry(effects, false), config)
                    .recover(recovery, "synthetic recoverable goal");

            var predecessor = state.codingRuns.findById(predecessorId).orElseThrow();
            var resumed = state.codingRuns.findById(result.runId()).orElseThrow();
            assertEquals(CodingRunStore.KIND_INITIAL, predecessor.runKind());
            assertEquals(0, predecessor.startedIteration());
            assertEquals(4, predecessor.lastIteration(), "RESUME must not mutate predecessor progress");
            assertEquals(CodingRunStore.KIND_RESUME, resumed.runKind());
            assertEquals(4, resumed.startedIteration(), "RESUME starts at the Task-global checkpoint coordinate");
            assertEquals(5,resumed.lastIteration(),"local correction preserves one durable progress coordinate");
            assertEquals(1, resumed.lastIteration() - resumed.startedIteration(),
                    "Run-local progress is the delta between global coordinates");
            assertTerminalInvariant(state, result, 5, 5);
            assertEquals(5, new ContextRebuilder(state, workspace, 4096, 4096)
                    .rebuild(task.value()).startIteration(),
                    "recovery/status context reads the same global progress coordinate");
        }
    }

    private CodingTaskRunner runner(StateStore state, ModelProvider provider,
            ToolRegistry registry, Config config) throws Exception {
        PersistenceSanitizer sanitizer = new PersistenceSanitizer();
        return new CodingTaskRunner(state, new PlanService(provider, state.plans),
                new VerificationService(workspace, state.verifications, sanitizer, 5, 4096),
                task -> new AgentLoop(new ContextualModelProvider(provider,state,workspace,task,
                        config.effectiveProvider(),config.model(),OperationKind.CHAT,
                        new CapabilityProfile("test",30_000,
                                LimitProvenance.CONFIGURED_LIMIT,256,64),false),
                        registry,config.effectiveMaxIterations(),null,null,state,task,
                        config.effectiveProvider(),config.model()),
                config, sanitizer, null, null, RuntimeManifest.disabled(),
                CodingTaskObserver.NOOP, CandidateVerificationGate.ALLOW,
                new InstructionManifestRuntime(state, workspace));
    }

    private Config config(int maxIterations, boolean planEnabled) {
        return new Config(workspace.toString(), 5, 4096, 16_384,
                Config.MODE_DENY, 10_000, Config.PROVIDER_MOCK, "synthetic-model",
                "", "", "system", maxIterations, "", 5, 4096, 1,
                planEnabled, false, false);
    }

    private ToolRegistry registry(AtomicInteger effects, boolean denyWrite) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool("read_file", effects, false));
        registry.register(tool("write_file", effects, denyWrite));
        return registry;
    }

    private Tool tool(String name, AtomicInteger effects, boolean deny) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "synthetic " + name; }
            @Override public ToolDefinition toDefinition() {
                return new ToolDefinition(name, description(), JSON.createObjectNode());
            }
            @Override public ToolResult preflightCall(ToolCall call) {
                return deny ? ToolResult.error("synthetic policy denial") : ToolResult.success("");
            }
            @Override public ToolResult execute(ToolCall call) {
                effects.incrementAndGet();
                return ToolResult.success("synthetic observation");
            }
        };
    }

    private static ModelResponse read(String id) {
        return ModelResponse.toolCallResponse("synthetic read",
                new ToolCall("read_file", Map.of("path", id), id));
    }

    private static ModelResponse effectfulBatch(int ordinal) {
        String a = "batch-" + ordinal + "-a";
        String b = "batch-" + ordinal + "-b";
        return ModelResponse.toolCallBatchResponse("synthetic effectful batch", List.of(
                new ToolCall("write_file", Map.of("path", a), a),
                new ToolCall("write_file", Map.of("path", b), b)),
                ProviderResponseEnvelope.inspect(List.of(a, b), false), false);
    }

    private static ModelResponse invalidProtocol() {
        return ModelResponse.protocolError(ProviderResponseEnvelope.inspect(
                java.util.Arrays.asList((String) null), false));
    }

    private static void assertPolicyDeniedClosure(List<AgentMessage> messages,
            String unsafeArgument) throws Exception {
        AgentMessage action = messages.stream().filter(message ->
                message.type() == AgentMessage.MessageType.ACTION
                        && "preflight-read-2".equals(message.toolCallId()))
                .findFirst().orElseThrow();
        var payload = JSON.readTree(action.toolCallJson());
        assertTrue(payload.isArray());
        assertEquals(2, payload.size());
        for (var call : payload) {
            assertEquals("{}", call.path("function").path("arguments").asText(),
                    "rejected ACTION arguments must be redacted");
        }
        assertFalse(action.toolCallJson().contains(unsafeArgument));
        List<AgentMessage> observations = messages.stream().filter(message ->
                message.type() == AgentMessage.MessageType.OBSERVATION
                        && List.of("preflight-read-2", "preflight-denied")
                                .contains(message.toolCallId())).toList();
        assertEquals(List.of("preflight-read-2", "preflight-denied"), observations.stream()
                .map(AgentMessage::toolCallId).toList());
        assertTrue(observations.getFirst().content().contains(
                "RELATED_BATCH_MEMBER_NOT_EXECUTED"));
        assertTrue(observations.get(1).content().contains(
                SafePreflightRejectionDiagnostic.SCHEMA_VERSION));
        assertTrue(messages.indexOf(action) < messages.indexOf(observations.getFirst()));
        assertTrue(messages.indexOf(observations.getFirst()) < messages.indexOf(observations.get(1)));
        assertFalse(messages.toString().contains(unsafeArgument));
    }

    private static void assertTerminalInvariant(StateStore state, CodingTaskResult result,
            int expectedAuthoritativeProgress, int expectedLastIteration) throws Exception {
        var task = state.tasks.findById(result.taskId()).orElseThrow();
        var run = state.codingRuns.findById(result.runId()).orElseThrow();
        var checkpoint = state.checkpoints.loadLatest(result.taskId()).orElse(null);
        int authoritative = Math.max(task.iterationsCompleted(),
                checkpoint == null ? 0 : checkpoint.iteration());
        assertEquals(expectedAuthoritativeProgress, authoritative);
        assertEquals(expectedLastIteration, run.lastIteration());
        assertEquals(TaskStore.STATUS_FAILED, task.status());
        assertEquals(CodingRunStore.PHASE_FAILED, run.phase());
        assertEquals("FAILED", state.r1aDurable.findTask(CanonicalId.parse(result.taskId()))
                .orElseThrow().status());
        DurableRun durableRun = state.r1aDurable.findRun(CanonicalId.parse(result.runId())).orElseThrow();
        assertEquals("FAILED", durableRun.terminalStatus());
    }
}
