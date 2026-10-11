package com.paicli.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.paicli.safety.SafetyPolicy;
import com.paicli.state.CodingRunStore;
import com.paicli.state.DatabaseManager;
import com.paicli.state.StateStore;
import com.paicli.state.TaskStore;
import com.paicli.tool.Tool;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolResult;
import com.paicli.tool.WriteFileTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Offline, benchmark-independent evidence for the Pre-V3 product-gap investigation. */
class PreV3OfflineProductGapInvestigationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SENTINEL = "SYNTHETIC_PRE_V3_SECRET_SENTINEL_7e41";

    @TempDir Path workspace;

    @Test
    void advisoryPlanMatrixSeparatesWireAcceptanceFromDomainAndAuthorityAdmission()
            throws Exception {
        PlanOutcome valid = runPlanCase("valid-dag", plan(DependencyShape.VALID_DAG), 1);
        assertEquals(List.of(PlanCompatibilityObserver.ParseStatus.ACCEPTED), valid.statuses());
        assertEquals(TaskStore.STATUS_DONE, valid.taskStatus());
        assertEquals(CodingRunStore.PHASE_COMPLETED, valid.runStatus());
        assertEquals(1, valid.persistedRevisions());
        assertEquals("APPROVED", valid.planLifecycle());

        PlanOutcome self = runPlanCase("self-dependency", plan(DependencyShape.SELF), 1);
        assertEquals(List.of(PlanCompatibilityObserver.ParseStatus.ACCEPTED), self.statuses());
        assertTrue(self.summary().contains("STRUCTURED_PLAN_SCHEMA_MAPPING_FAILED"), self.summary());
        assertTerminalPlanFailure(self);

        PlanOutcome cycle = runPlanCase("cycle", plan(DependencyShape.CYCLE), 1);
        assertEquals(List.of(PlanCompatibilityObserver.ParseStatus.ACCEPTED), cycle.statuses());
        assertTrue(cycle.summary().contains("PLAN_PROPOSAL_REJECTED: STRUCTURE_INVALID"),
                cycle.summary());
        assertTerminalPlanFailure(cycle);

        PlanOutcome missing = runPlanCase("missing-dependency",
                plan(DependencyShape.MISSING), 2);
        assertEquals(List.of(PlanCompatibilityObserver.ParseStatus.SCHEMA_INVALID,
                PlanCompatibilityObserver.ParseStatus.SCHEMA_INVALID), missing.statuses());
        assertTrue(missing.summary().contains("STRUCTURED_PLAN_REPAIR_FAILED"), missing.summary());
        assertTerminalPlanFailure(missing);

        PlanOutcome logical = runPlanCase("wire-logical-id",
                plan(DependencyShape.DUPLICATE_LOGICAL_ID), 2);
        assertEquals(List.of(PlanCompatibilityObserver.ParseStatus.SCHEMA_INVALID,
                PlanCompatibilityObserver.ParseStatus.SCHEMA_INVALID), logical.statuses());
        assertTrue(logical.summary().contains("STRUCTURED_PLAN_REPAIR_FAILED"), logical.summary());
        assertTerminalPlanFailure(logical);
    }

    @Test
    void preflightMatrixPreservesTypedFailClosedFactsWithoutPersistingRejectedArguments()
            throws Exception {
        PreflightOutcome schema = runPreflightCase("schema-invalid",
                registry(PreflightMode.ACCEPT), call(SENTINEL), false);
        assertEquals(ToolRegistry.PreflightFailureKind.MODEL_CORRECTABLE, schema.kind());
        assertTrue(schema.code().startsWith("TOOL_ARGUMENT_SCHEMA_"), schema.code());
        assertLocalizedWithoutLeak(schema);

        PreflightOutcome policy = runPreflightCase("policy-denied",
                registry(PreflightMode.POLICY_DENIED), call(SENTINEL), false);
        assertEquals(ToolRegistry.PreflightFailureKind.POLICY_DENIED, policy.kind());
        assertEquals("TOOL_PREFLIGHT_REJECTED", policy.code());
        assertLocalizedWithoutLeak(policy);

        Path protectedRoot = workspace.resolve("protected-path");
        Files.createDirectories(protectedRoot);
        ToolRegistry protectedRegistry = new ToolRegistry();
        protectedRegistry.register(new WriteFileTool(new SafetyPolicy(protectedRoot)));
        ToolCall protectedCall = new ToolCall("write_file", Map.of(
                "path", ".git/config", "content", SENTINEL, "overwrite", true),
                "protected-path-call");
        PreflightOutcome protectedPath = runPreflightCase("protected-path",
                protectedRegistry, protectedCall, false);
        assertEquals(ToolRegistry.PreflightFailureKind.POLICY_DENIED, protectedPath.kind());
        assertEquals("TOOL_PREFLIGHT_REJECTED", protectedPath.code());
        assertLocalizedWithoutLeak(protectedPath);
        assertFalse(Files.exists(protectedRoot.resolve(".git/config")));

        PreflightOutcome approval = runPreflightCase("approval-required",
                registry(PreflightMode.APPROVAL_REQUIRED), call(SENTINEL), false);
        assertEquals(ToolRegistry.PreflightFailureKind.APPROVAL_REQUIRED, approval.kind());
        assertEquals("SYNTHETIC_APPROVAL_REQUIRED", approval.code());
        assertFatalRejectedWithoutLeak(approval);

        PreflightOutcome accepted = runPreflightCase("accepted",
                registry(PreflightMode.ACCEPT), call(7), true);
        assertNull(accepted.kind());
        assertNull(accepted.code());
        assertEquals(TaskStore.STATUS_DONE, accepted.taskStatus());
        assertEquals(CodingRunStore.PHASE_COMPLETED, accepted.runStatus());
        assertEquals(1, accepted.effects());
        assertEquals(2, accepted.providerCalls());

        Path directRoot = workspace.resolve("direct");
        Files.createDirectories(directRoot);
        ToolResult direct = new WriteFileTool(new SafetyPolicy(directRoot))
                .execute(new ToolCall("write_file", Map.of("path", "safe.txt"), "direct"));
        assertFalse(direct.success(), "direct execute must retain defensive argument validation");
    }

    private PlanOutcome runPlanCase(String name, String document, int planningCalls)
            throws Exception {
        Path root = workspace.resolve("plan-" + name);
        List<PlanCompatibilityObserver.Fact> facts = new ArrayList<>();
        ModelProvider planning = new SequenceProvider(java.util.stream.IntStream
                .range(0, planningCalls).mapToObj(ignored -> ModelResponse.finalAnswer(document))
                .toList());
        ModelProvider execution = new SequenceProvider(List.of(ModelResponse.finalAnswer("done")));
        CanonicalId taskId = CanonicalId.newId(EntityFamily.TASK);
        CodingTaskResult result;
        String taskStatus;
        String runStatus;
        long revisions;
        String lifecycle;
        try (StateStore state = new StateStore(root)) {
            PlanService plans = new PlanService(planning, state.plans, facts::add);
            CodingTaskRunner runner = runner(root, state, taskId, plans, execution,
                    new ToolRegistry(), config(root, true));
            result = runner.run(taskId.value(), "synthetic plan contract goal");
            taskStatus = state.tasks.findById(result.taskId()).orElseThrow().status();
            runStatus = state.codingRuns.findById(result.runId()).orElseThrow().phase();
            revisions = count(root, "plan_revisions");
            lifecycle = revisions == 0 ? null : scalar(root,
                    "SELECT state FROM plan_revision_lifecycle");
        }
        return new PlanOutcome(facts.stream().map(PlanCompatibilityObserver.Fact::planParseStatus)
                .toList(), result.summary(), taskStatus, runStatus, revisions, lifecycle);
    }

    private PreflightOutcome runPreflightCase(String name, ToolRegistry registry,
            ToolCall call, boolean accepted) throws Exception {
        Path root = workspace.resolve(name);
        Files.createDirectories(root);
        ToolRegistry.PreflightResult projection = registry.preflight(call);
        assertEquals(accepted, projection.accepted());
        assertFalse(String.valueOf(projection.message()).contains(SENTINEL));
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<List<AgentMessage>> continuation = new AtomicReference<>(List.of());
        ModelProvider provider = (messages, definitions) -> {
            if (calls.getAndIncrement() == 0) {
                if (accepted) return ModelResponse.toolCallResponse("", call);
                ToolCall companion = new ToolCall(call.name(), call.arguments(),
                        call.providerCallId() + "-companion");
                return ModelResponse.toolCallBatchResponse("synthetic rejected batch",
                        List.of(call, companion), ProviderResponseEnvelope.inspect(
                                List.of(call.providerCallId(), companion.providerCallId()),
                                false), false);
            }
            continuation.set(List.copyOf(messages));
            return ModelResponse.finalAnswer("synthetic complete");
        };
        CanonicalId taskId = CanonicalId.newId(EntityFamily.TASK);
        CodingTaskResult result;
        String taskStatus;
        String runStatus;
        int effects;
        List<AgentMessage> durableMessages;
        List<AgentMessage> checkpointMessages;
        int checkpointIteration;
        String checkpointStatus;
        try (StateStore state = new StateStore(root)) {
            SyntheticTool tool = registry.lookup("synthetic_tool")
                    .filter(SyntheticTool.class::isInstance)
                    .map(SyntheticTool.class::cast).orElse(null);
            CodingTaskRunner runner = runner(root, state, taskId,
                    new PlanService(provider, state.plans), provider, registry,
                    config(root, false));
            result = runner.run(taskId.value(), "synthetic preflight goal");
            taskStatus = state.tasks.findById(result.taskId()).orElseThrow().status();
            runStatus = state.codingRuns.findById(result.runId()).orElseThrow().phase();
            effects = tool == null ? 0 : tool.effects.get();
            durableMessages = List.copyOf(state.events.findMessages(taskId.value()));
            var checkpoint = state.checkpoints.loadLatest(taskId.value()).orElseThrow();
            checkpointMessages = List.copyOf(checkpoint.messages());
            checkpointIteration = checkpoint.iteration();
            checkpointStatus = checkpoint.status();
            assertEquals(accepted ? 1 : 0, state.toolCalls.findByTask(taskId.value()).size());
            String durable = result.summary()
                    + state.tasks.findById(result.taskId()).orElseThrow().lastError()
                    + state.codingRuns.findById(result.runId()).orElseThrow().lastError();
            assertFalse(durable.contains(SENTINEL));
        }
        if (!accepted) assertTreeDoesNotContain(root, SENTINEL);
        return new PreflightOutcome(projection.code(), projection.failureKind(),
                result.summary(), taskStatus, runStatus, effects, root, calls.get(),
                continuation.get(), durableMessages, checkpointMessages,
                checkpointIteration, checkpointStatus);
    }

    private CodingTaskRunner runner(Path root, StateStore state, CanonicalId taskId,
            PlanService plans, ModelProvider execution, ToolRegistry tools, Config config)
            throws Exception {
        PersistenceSanitizer sanitizer = new PersistenceSanitizer();
        return new CodingTaskRunner(state, plans,
                new VerificationService(root, state.verifications, sanitizer, 5, 4096),
                ignored -> new AgentLoop(new ContextualModelProvider(execution,state,root,
                        taskId.value(),config.effectiveProvider(),config.model(),OperationKind.CHAT,
                        new CapabilityProfile("test",30_000,
                                LimitProvenance.CONFIGURED_LIMIT,256,64),false),
                        tools, config.effectiveMaxIterations(),
                        null, null, state, taskId.value(), config.effectiveProvider(),
                        config.model()),
                config, sanitizer, null, null, RuntimeManifest.disabled(),
                CodingTaskObserver.NOOP, CandidateVerificationGate.ALLOW,
                new InstructionManifestRuntime(state, root),
                (revision, summary) -> new PlanApprovalPort.Decision(true,
                        "USER_CONFIRMED", "synthetic-user", "synthetic-policy-v1"));
    }

    private static Config config(Path root, boolean planEnabled) {
        return new Config(root.toString(), 5, 4096, 16_384,
                Config.MODE_DENY, 10_000, Config.PROVIDER_MOCK, "synthetic-model",
                "", "", "system", 3, "", 5, 4096, 1,
                planEnabled, false, false);
    }

    private static ToolRegistry registry(PreflightMode mode) {
        ToolRegistry registry = mode == PreflightMode.APPROVAL_REQUIRED
                ? new ToolRegistry() {
                    @Override public PreflightResult preflight(ToolCall call) {
                        return PreflightResult.rejected("SYNTHETIC_APPROVAL_REQUIRED",
                                "synthetic approval is required",
                                PreflightFailureKind.APPROVAL_REQUIRED);
                    }
                } : new ToolRegistry();
        registry.register(new SyntheticTool(mode));
        return registry;
    }

    private static ToolCall call(Object value) {
        return new ToolCall("synthetic_tool", Map.of("value", value), "synthetic-call");
    }

    private static String plan(DependencyShape shape) {
        ObjectNode root = JSON.createObjectNode();
        root.putArray("acceptanceCriteria").add("synthetic outcome recorded");
        root.putArray("assumptions");
        root.putArray("risks");
        root.put("verificationStrategy", "inspect synthetic state");
        ObjectNode first = root.putArray("steps").addObject();
        first.put("title", "First");
        first.put("definition", "Perform first synthetic action");
        first.putArray("dependsOn");
        ObjectNode second = root.withArray("steps").addObject();
        second.put("title", "Second");
        second.put("definition", "Perform second synthetic action");
        second.putArray("dependsOn").add(0);
        switch (shape) {
            case VALID_DAG -> { }
            case SELF -> first.withArray("dependsOn").add(0);
            case CYCLE -> first.withArray("dependsOn").add(1);
            case MISSING -> second.set("dependsOn", JSON.createArrayNode().add(9));
            case DUPLICATE_LOGICAL_ID -> {
                first.put("logicalId", "duplicate");
                second.put("logicalId", "duplicate");
            }
        }
        return root.toString();
    }

    private static void assertTerminalPlanFailure(PlanOutcome value) {
        assertEquals(TaskStore.STATUS_FAILED, value.taskStatus());
        assertEquals(CodingRunStore.PHASE_FAILED, value.runStatus());
        assertEquals(0, value.persistedRevisions());
        assertNull(value.planLifecycle());
    }

    private static void assertLocalizedWithoutLeak(PreflightOutcome value) throws Exception {
        assertEquals(TaskStore.STATUS_DONE, value.taskStatus());
        assertEquals(CodingRunStore.PHASE_COMPLETED, value.runStatus());
        assertEquals(0, value.effects());
        assertEquals(2, value.providerCalls(),
                "a local preflight rejection must reach exactly one next Provider turn");
        assertEquals(2, value.checkpointIteration());
        assertEquals("FINAL_ACCEPTED", value.checkpointStatus(),
                "H5 retains its accepted-FINAL checkpoint authority before terminal Result projection");
        assertTrue(value.summary().contains("synthetic complete"), value.summary());
        assertLocalizedClosure(value.continuation());
        assertLocalizedClosure(value.durableMessages());
        assertLocalizedClosure(value.checkpointMessages());
    }

    private static void assertLocalizedClosure(List<AgentMessage> messages) throws Exception {
        AgentMessage action = messages.stream().filter(message ->
                message.type() == AgentMessage.MessageType.ACTION
                        && "Tool batch rejected before execution".equals(message.content()))
                .findFirst().orElseThrow();
        var payload = JSON.readTree(action.toolCallJson());
        assertTrue(payload.isArray());
        assertEquals(2, payload.size());
        List<String> ids = new ArrayList<>();
        for (var call : payload) {
            ids.add(call.path("id").asText());
            assertEquals("{}", call.path("function").path("arguments").asText(),
                    "rejected ACTION arguments must be redacted");
        }
        List<AgentMessage> observations = messages.stream().filter(message ->
                message.type() == AgentMessage.MessageType.OBSERVATION
                        && ids.contains(message.toolCallId())).toList();
        assertEquals(ids, observations.stream().map(AgentMessage::toolCallId).toList());
        assertTrue(observations.getFirst().content().contains(
                SafePreflightRejectionDiagnostic.SCHEMA_VERSION));
        assertTrue(observations.get(1).content().contains(
                "RELATED_BATCH_MEMBER_NOT_EXECUTED"));
        assertTrue(messages.indexOf(action) < messages.indexOf(observations.getFirst()));
        assertTrue(messages.indexOf(observations.getFirst()) < messages.indexOf(observations.get(1)));
        assertFalse(messages.toString().contains(SENTINEL));
    }

    private static void assertFatalRejectedWithoutLeak(PreflightOutcome value) {
        assertEquals(TaskStore.STATUS_FAILED, value.taskStatus());
        assertEquals(CodingRunStore.PHASE_FAILED, value.runStatus());
        assertEquals(0, value.effects());
        assertEquals(1, value.providerCalls(),
                "fatal preflight failures must not reach another Provider turn");
        assertTrue(value.continuation().isEmpty());
        assertTrue(value.summary().contains(value.code()), value.summary());
    }

    private static void assertTreeDoesNotContain(Path root, String sentinel) throws Exception {
        try (var files = Files.walk(root)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                String material = new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
                assertFalse(material.contains(sentinel), "sentinel persisted in " + path);
            }
        }
    }

    private static long count(Path root, String table) throws Exception {
        try (DatabaseManager manager = new DatabaseManager(root.resolve(".paicli/state"));
                var statement = manager.getConnection().createStatement();
                var result = statement.executeQuery("SELECT count(*) FROM " + table)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static String scalar(Path root, String sql) throws Exception {
        try (DatabaseManager manager = new DatabaseManager(root.resolve(".paicli/state"));
                var statement = manager.getConnection().createStatement();
                var result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private enum DependencyShape {
        VALID_DAG, SELF, CYCLE, MISSING, DUPLICATE_LOGICAL_ID
    }

    private enum PreflightMode { ACCEPT, POLICY_DENIED, APPROVAL_REQUIRED }

    private record PlanOutcome(List<PlanCompatibilityObserver.ParseStatus> statuses,
            String summary, String taskStatus, String runStatus, long persistedRevisions,
            String planLifecycle) { }

    private record PreflightOutcome(String code, ToolRegistry.PreflightFailureKind kind,
            String summary, String taskStatus, String runStatus, int effects, Path root,
            int providerCalls, List<AgentMessage> continuation,
            List<AgentMessage> durableMessages, List<AgentMessage> checkpointMessages,
            int checkpointIteration, String checkpointStatus) { }

    private static final class SequenceProvider implements ModelProvider {
        private final java.util.ArrayDeque<ModelResponse> responses;
        private SequenceProvider(List<ModelResponse> values) {
            responses = new java.util.ArrayDeque<>(values);
        }
        @Override public ModelResponse chat(List<AgentMessage> messages,
                List<ToolDefinition> tools) {
            ModelResponse response = responses.poll();
            if (response == null) throw new AssertionError("synthetic Provider script exhausted");
            return response;
        }
    }

    private static final class SyntheticTool implements Tool {
        private final PreflightMode mode;
        private final AtomicInteger effects = new AtomicInteger();
        private SyntheticTool(PreflightMode mode) { this.mode = mode; }
        @Override public String name() { return "synthetic_tool"; }
        @Override public String description() { return "synthetic offline tool"; }
        @Override public ToolDefinition toDefinition() {
            ObjectNode schema = JSON.createObjectNode();
            schema.put("type", "object");
            schema.putObject("properties").putObject("value").put("type",
                    mode == PreflightMode.POLICY_DENIED ? "string" : "integer");
            schema.putArray("required").add("value");
            return new ToolDefinition(name(), description(), schema);
        }
        @Override public ToolResult preflightCall(ToolCall call) {
            return mode == PreflightMode.POLICY_DENIED
                    ? ToolResult.error("synthetic policy denied") : ToolResult.success("");
        }
        @Override public ToolResult execute(ToolCall call) {
            if (!(call.arguments().get("value") instanceof Integer)) {
                return ToolResult.error("synthetic_tool: invalid value");
            }
            effects.incrementAndGet();
            return ToolResult.success("synthetic effect completed");
        }
    }
}
