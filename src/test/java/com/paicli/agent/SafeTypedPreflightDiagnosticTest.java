package com.paicli.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.paicli.runtime.CandidateVerificationGate;
import com.paicli.runtime.CodingTaskObserver;
import com.paicli.runtime.CodingTaskResult;
import com.paicli.runtime.CodingTaskRunner;
import com.paicli.runtime.InstructionManifestRuntime;
import com.paicli.runtime.PersistenceSanitizer;
import com.paicli.runtime.PlanService;
import com.paicli.runtime.RuntimeManifest;
import com.paicli.runtime.VerificationService;
import com.paicli.safety.SafetyPolicy;
import com.paicli.state.CodingRunStore;
import com.paicli.state.DatabaseManager;
import com.paicli.state.StateStore;
import com.paicli.state.TaskStore;
import com.paicli.tool.Tool;
import com.paicli.tool.ToolAuthorityProjection;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolResult;
import com.paicli.tool.WriteFileTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static com.paicli.agent.SafePreflightRejectionDiagnostic.SafeReason.*;
import static org.junit.jupiter.api.Assertions.*;

class SafeTypedPreflightDiagnosticTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SENTINEL = "SYNTHETIC_SAFE_PREFLIGHT_SECRET_5d93";

    @TempDir Path workspace;

    @Test
    void rejectedWholeBatchesPersistOnlyClosedTypedFactsAndAcceptedBatchDoesNot()
            throws Exception {
        Outcome schema = reject("schema", registry("synthetic_schema", Mode.SCHEMA),
                call("synthetic_schema", SENTINEL), ARGUMENT_SCHEMA_REJECTED);
        assertEquals(ToolRegistry.PreflightFailureKind.MODEL_CORRECTABLE,
                schema.fact().failureKind());

        Outcome policy = reject("policy", registry("synthetic_policy", Mode.POLICY),
                call("synthetic_policy", SENTINEL), POLICY_REJECTED);
        assertEquals(ToolRegistry.PreflightFailureKind.POLICY_DENIED,
                policy.fact().failureKind());

        Path protectedRoot = workspace.resolve("protected");
        Files.createDirectories(protectedRoot);
        ToolRegistry protectedRegistry = new ToolRegistry();
        protectedRegistry.register(new WriteFileTool(new SafetyPolicy(protectedRoot)));
        Outcome protectedPath = reject("protected", protectedRegistry,
                new ToolCall("write_file", Map.of("path", ".git/config",
                        "content", SENTINEL, "overwrite", true), "protected-1"),
                POLICY_REJECTED);
        assertEquals("TOOL_PREFLIGHT_REJECTED", protectedPath.fact().preflightCode());
        assertFalse(Files.exists(protectedRoot.resolve(".git/config")));

        Outcome approval = reject("approval", registry("synthetic_approval", Mode.APPROVAL),
                call("synthetic_approval", SENTINEL), APPROVAL_REQUIRED);
        assertEquals(ToolRegistry.PreflightFailureKind.APPROVAL_REQUIRED,
                approval.fact().failureKind());

        Outcome authority = reject("authority", registry("synthetic_authority", Mode.AUTHORITY),
                call("synthetic_authority", SENTINEL), AUTHORITY_REJECTED);
        assertEquals(ToolRegistry.PreflightFailureKind.INTEGRITY_FAILURE,
                authority.fact().failureKind());

        Outcome integrity = reject("integrity", registry("synthetic_integrity", Mode.INTEGRITY),
                call("synthetic_integrity", SENTINEL), INTEGRITY_REJECTED);
        assertEquals(ToolRegistry.PreflightFailureKind.INTEGRITY_FAILURE,
                integrity.fact().failureKind());

        Path acceptedRoot = workspace.resolve("accepted");
        Files.createDirectories(acceptedRoot);
        ToolRegistry acceptedRegistry = registry("read_file", Mode.ACCEPT);
        SyntheticTool acceptedTool = (SyntheticTool) acceptedRegistry.lookup("read_file")
                .orElseThrow();
        CodingTaskResult accepted = run(acceptedRoot, acceptedRegistry,
                new ToolCall("read_file", Map.of("value", 1), "accepted-1"), true);
        try (StateStore state = new StateStore(acceptedRoot)) {
            assertEquals(TaskStore.STATUS_DONE,
                    state.tasks.findById(accepted.taskId()).orElseThrow().status());
            assertEquals(CodingRunStore.PHASE_COMPLETED,
                    state.codingRuns.findById(accepted.runId()).orElseThrow().phase());
            assertTrue(diagnostics(state, accepted.taskId()).isEmpty());
            assertEquals(2, state.toolCalls.findByTask(accepted.taskId()).size());
        }
        assertEquals(2, acceptedTool.effects.get());
    }

    @Test
    void diagnosticWriteFailureCannotAdmitRejectedToolOrChangeTerminalClassification()
            throws Exception {
        Path root = workspace.resolve("diagnostic-write-failure");
        Files.createDirectories(root);
        ToolRegistry registry = registry("synthetic_policy", Mode.POLICY);
        SyntheticTool tool = (SyntheticTool) registry.lookup("synthetic_policy").orElseThrow();
        CodingTaskResult result;
        try (StateStore state = new StateStore(root)) {
            try (DatabaseManager manager = new DatabaseManager(root.resolve(".paicli/state"));
                    var statement = manager.getConnection().createStatement()) {
                statement.execute("""
                        CREATE TRIGGER reject_safe_preflight_diagnostic
                        BEFORE INSERT ON events
                        WHEN NEW.content LIKE '%SAFE_TYPED_PREFLIGHT_REJECTION_V1 %'
                        BEGIN SELECT RAISE(ABORT,'synthetic diagnostic write failure'); END
                        """);
            }
            CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
            result = runner(root,state,registry,provider(
                    call("synthetic_policy",SENTINEL),false),task)
                    .run(task.value(),
                            "synthetic diagnostic failure goal");
            assertEquals(TaskStore.STATUS_FAILED, result.finalStatus());
            assertTrue(result.summary().startsWith(
                    "Agent execution failed: TOOL_CALL_BATCH_PREFLIGHT_FAILED"), result.summary());
            assertTrue(diagnostics(state, result.taskId()).isEmpty());
        }
        assertEquals(0, tool.effects.get());
        assertTreeDoesNotContain(root, SENTINEL);
    }

    private Outcome reject(String name, ToolRegistry registry, ToolCall call,
            SafePreflightRejectionDiagnostic.SafeReason reason) throws Exception {
        Path root = workspace.resolve(name);
        Files.createDirectories(root);
        SyntheticTool synthetic = registry.lookup(call.name())
                .filter(SyntheticTool.class::isInstance)
                .map(SyntheticTool.class::cast).orElse(null);
        CodingTaskResult result = run(root, registry, call, false);
        SafePreflightRejectionDiagnostic first;
        try (StateStore state = new StateStore(root)) {
            boolean correctable=reason==ARGUMENT_SCHEMA_REJECTED
                    ||reason==REGISTRATION_REJECTED||reason==POLICY_REJECTED;
            assertEquals(correctable?TaskStore.STATUS_DONE:TaskStore.STATUS_FAILED,
                    state.tasks.findById(result.taskId()).orElseThrow().status(),result.summary());
            assertEquals(correctable?CodingRunStore.PHASE_COMPLETED:CodingRunStore.PHASE_FAILED,
                    state.codingRuns.findById(result.runId()).orElseThrow().phase());
            assertEquals(0, state.toolCalls.findByTask(result.taskId()).size());
            List<SafePreflightRejectionDiagnostic> firstRead = diagnostics(
                    state, result.taskId());
            List<SafePreflightRejectionDiagnostic> secondRead = diagnostics(
                    state, result.taskId());
            assertEquals(firstRead, secondRead, "durable typed read-back must be stable");
            assertEquals(1, firstRead.size());
            first = firstRead.getFirst();
            assertEquals(result.runId(), first.runId());
            assertEquals(call.name(), first.toolName());
            assertEquals(1, first.batchOrdinal());
            assertEquals(reason, first.safeReason());
            assertEquals(SafePreflightRejectionDiagnostic.SCHEMA_VERSION,
                    first.durableText().split(" ", 2)[0]);
            assertFalse(first.durableText().contains(SENTINEL));
        }
        if (synthetic != null) assertEquals(0, synthetic.effects.get());
        assertTreeDoesNotContain(root, SENTINEL);
        return new Outcome(result, first);
    }

    private CodingTaskResult run(Path root, ToolRegistry registry, ToolCall first,
            boolean accepted) throws Exception {
        try (StateStore state = new StateStore(root)) {
            CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
            return runner(root,state,registry,provider(first,accepted),task)
                    .run(task.value(),
                            "synthetic safe preflight goal");
        }
    }

    private static List<SafePreflightRejectionDiagnostic> diagnostics(
            StateStore state, String taskId) throws Exception {
        return state.events.findMessages(taskId).stream()
                .map(AgentMessage::content)
                .map(SafePreflightRejectionDiagnostic::parse)
                .flatMap(Optional::stream)
                .toList();
    }

    private CodingTaskRunner runner(Path root, StateStore state, ToolRegistry registry,
            ModelProvider provider,CanonicalId task) throws Exception {
        PersistenceSanitizer sanitizer = new PersistenceSanitizer();
        Config config = new Config(root.toString(), 5, 4096, 16_384,
                Config.MODE_DENY, 10_000, Config.PROVIDER_MOCK, "synthetic-model",
                "", "", "system", 3, "", 5, 4096, 1,
                false, false, false);
        ModelProvider contextual=new ContextualModelProvider(provider,state,root,task.value(),
                "mock","synthetic-model",OperationKind.CHAT,
                new CapabilityProfile("test",30_000,LimitProvenance.CONFIGURED_LIMIT,
                        256,64),false);
        return new CodingTaskRunner(state, new PlanService(provider, state.plans),
                new VerificationService(root, state.verifications, sanitizer, 5, 4096),
                ignored -> new AgentLoop(contextual,registry,config.effectiveMaxIterations(),
                        null,null,state,task.value(),config.effectiveProvider(),config.model()),
                config, sanitizer, null, null, RuntimeManifest.disabled(),
                CodingTaskObserver.NOOP, CandidateVerificationGate.ALLOW,
                new InstructionManifestRuntime(state, root));
    }

    private static ModelProvider provider(ToolCall first, boolean accepted) {
        AtomicInteger calls = new AtomicInteger();
        return (messages, definitions) -> {
            if (calls.getAndIncrement() > 0) return ModelResponse.finalAnswer("synthetic done");
            ToolCall second = new ToolCall(first.name(),
                    accepted ? Map.of("value", 2) : first.arguments(),
                    first.providerCallId() + "-2");
            return ModelResponse.toolCallBatchResponse("synthetic batch",
                    List.of(first, second), ProviderResponseEnvelope.inspect(
                            List.of(first.providerCallId(), second.providerCallId()), false),
                    false);
        };
    }

    private static ToolCall call(String name, Object value) {
        return new ToolCall(name, Map.of("value", value), name + "-1");
    }

    private static ToolRegistry registry(String name, Mode mode) {
        ToolRegistry registry = switch (mode) {
            case APPROVAL -> new ToolRegistry() {
                @Override public PreflightResult preflight(ToolCall call) {
                    return PreflightResult.rejected("SYNTHETIC_APPROVAL_REQUIRED",
                            "free text must not be persisted",
                            PreflightFailureKind.APPROVAL_REQUIRED);
                }
            };
            case INTEGRITY -> new ToolRegistry() {
                @Override public PreflightResult preflight(ToolCall call) {
                    return PreflightResult.rejected("SYNTHETIC_INTEGRITY_REJECTED",
                            "free text must not be persisted",
                            PreflightFailureKind.INTEGRITY_FAILURE);
                }
            };
            default -> new ToolRegistry();
        };
        registry.register(new SyntheticTool(name, mode));
        return registry;
    }

    private static void assertTreeDoesNotContain(Path root, String sentinel) throws Exception {
        try (var files = Files.walk(root)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                String material = new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
                assertFalse(material.contains(sentinel), "sentinel persisted in " + path);
            }
        }
    }

    private enum Mode { SCHEMA, POLICY, APPROVAL, AUTHORITY, INTEGRITY, ACCEPT }

    private record Outcome(CodingTaskResult result,
            SafePreflightRejectionDiagnostic fact) { }

    private static final class SyntheticTool implements Tool {
        private final String name;
        private final Mode mode;
        private final AtomicInteger effects = new AtomicInteger();
        private SyntheticTool(String name, Mode mode) {
            this.name = name;
            this.mode = mode;
        }
        @Override public String name() { return name; }
        @Override public String description() { return "synthetic safe diagnostic tool"; }
        @Override public ToolDefinition toDefinition() {
            ObjectNode schema = JSON.createObjectNode();
            schema.put("type", "object");
            schema.putObject("properties").putObject("value").put("type",
                    mode == Mode.SCHEMA ? "integer" : "string");
            if (mode == Mode.ACCEPT) {
                schema.withObject("properties").withObject("value").put("type", "integer");
            }
            schema.putArray("required").add("value");
            return new ToolDefinition(name, description(), schema);
        }
        @Override public ToolResult preflightCall(ToolCall call) {
            return mode == Mode.POLICY
                    ? ToolResult.error("argument-derived free text " + call.arguments().get("value"))
                    : ToolResult.success("");
        }
        @Override public Optional<ToolAuthorityProjection> authorityProjection(ToolCall call) {
            if (mode == Mode.AUTHORITY) throw new IllegalStateException(
                    "argument-derived free text " + call.arguments().get("value"));
            return Optional.empty();
        }
        @Override public ToolResult execute(ToolCall call) {
            effects.incrementAndGet();
            return ToolResult.success("synthetic effect");
        }
    }
}
