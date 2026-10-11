package com.paicli.provider.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.agent.AgentLoop;
import com.paicli.agent.AgentSegmentRequest;
import com.paicli.agent.AgentSegmentResult;
import com.paicli.config.Config;
import com.paicli.context.ContextModels.CapabilityProfile;
import com.paicli.context.ContextModels.LimitProvenance;
import com.paicli.context.ContextModels.OperationKind;
import com.paicli.context.ContextualModelProvider;
import com.paicli.context.ProviderContextRuntime;
import com.paicli.event.AgentEvent;
import com.paicli.event.AgentEventPublisher;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.interactive.query.StateStoreInteractiveQueryFacade;
import com.paicli.interactive.runtime.InteractiveRuntimeView;
import com.paicli.interactive.runtime.RuntimeEventBridge;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.PreparedProviderTurn;
import com.paicli.provider.PreparedTurnProvider;
import com.paicli.provider.ProviderRetrySignal;
import com.paicli.provider.ProviderRetrySignalSource;
import com.paicli.runtime.CandidateVerificationGate;
import com.paicli.runtime.CodingTaskObserver;
import com.paicli.runtime.CodingTaskResult;
import com.paicli.runtime.CodingTaskRunner;
import com.paicli.runtime.InstructionManifestRuntime;
import com.paicli.runtime.PersistenceSanitizer;
import com.paicli.runtime.PlanApprovalPort;
import com.paicli.runtime.PlanService;
import com.paicli.runtime.RuntimeManifest;
import com.paicli.runtime.VerificationService;
import com.paicli.state.ProviderCallTelemetryStore;
import com.paicli.state.StateStore;
import com.paicli.state.TaskStore;
import com.paicli.support.R3ContextTestFixtures;
import com.paicli.tool.Tool;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ProviderRetryRuntimeAcceptanceTest {
    private static final String DIGEST =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String PLAN_JSON = """
            {"acceptanceCriteria":["README evidence acquired"],"assumptions":[],"risks":[],
             "verificationStrategy":"read-only evidence","steps":[
               {"title":"Inspect","definition":"Read README.md once","dependsOn":[],
                "completionRule":"ALL_REQUIRED_EVIDENCE","requiredEvidenceKinds":["TOOL_CALL"],
                "effectClass":"READ",
                "toolRequirements":[{"requirementId":"retry-read-readme","requirementOrder":0,
                "toolName":"read_file","effectClass":"READ",
                "authoritySelector":{"kind":"EXACT_PATH","path":"README.md"},
                "minSuccesses":1,"maxOccurrences":1,
                "freshnessPolicy":"RESUME_REUSE_ONLY"}]}]}
            """;

    @TempDir Path workspace;

    @Test void RETRY_NO_TOOL_OCCURRENCE_BEFORE_COMPLETE_RESPONSE() throws Exception {
        try (StateStore state = new StateStore(workspace)) {
            var admitted = R3ContextTestFixtures.admit(state, workspace);
            AtomicInteger toolExecutions = new AtomicInteger();
            ScriptedPreparedProvider raw = new ScriptedPreparedProvider(List.of(
                    timeout(() -> assertEquals(0, countOccurrencesUnchecked("TOOL"),
                            "partial/failed Provider attempts cannot admit Tool occurrences")),
                    toolCall("call-read-before-complete"),
                    success("done")));
            AgentSegmentResult result = loop(state, admitted, raw, toolExecutions)
                    .runSegment(new AgentSegmentRequest("read README", List.of(), null, 0, 5));

            assertTrue(result.completed(), "PASS: the complete Tool response may proceed");
            assertEquals(1, toolExecutions.get());
            assertEquals(1, countOccurrences(state, "TOOL"));
            assertEquals(1, state.toolCalls.findByTask(
                    admitted.root().taskId().value()).size());
        }
    }

    @Test void RETRY_DOES_NOT_REEXECUTE_COMPLETED_TOOL() throws Exception {
        try (StateStore state = new StateStore(workspace)) {
            var admitted = R3ContextTestFixtures.admit(state, workspace);
            AtomicInteger toolExecutions = new AtomicInteger();
            ScriptedPreparedProvider raw = new ScriptedPreparedProvider(List.of(
                    toolCall("call-read-once"), timeout(null), success("grounded")));
            AgentSegmentResult result = loop(state, admitted, raw, toolExecutions)
                    .runSegment(new AgentSegmentRequest("read README", List.of(), null, 0, 5));

            assertTrue(result.completed());
            assertEquals(1, toolExecutions.get(),
                    "PASS: retry resends Provider continuation and never replays the Tool");
            assertEquals(1, state.toolCalls.findByTask(
                    admitted.root().taskId().value()).size());
            assertEquals(1, countOccurrences(state, "TOOL"));
            assertEquals(raw.seenMessages.get(1), raw.seenMessages.get(2),
                    "the continuation snapshot must be byte-for-byte structurally stable");
            assertTrue(raw.seenMessages.get(1).stream().anyMatch(message ->
                    message.type() == AgentMessage.MessageType.OBSERVATION
                            && "call-read-once".equals(message.toolCallId())));
        }
    }

    @Test void RETRY_BLOCKED_FOR_UNKNOWN_EFFECT() throws Exception {
        try (StateStore state = new StateStore(workspace)) {
            var admitted = R3ContextTestFixtures.admit(state, workspace);
            Runnable injectUnknown = () -> {
                CanonicalId occurrence = admitted.guard().admitOperationOccurrence(
                        "unknown-fixture", "unknown-write-fixture", "TOOL", "write_file",
                        "b".repeat(64));
                admitted.guard().unknownOccurrence(occurrence, "injected unknown effect");
            };
            ScriptedPreparedProvider raw = new ScriptedPreparedProvider(List.of(
                    timeout(injectUnknown), success("must-not-run")));
            AgentSegmentResult result = loop(state, admitted, raw, new AtomicInteger())
                    .runSegment(new AgentSegmentRequest("read README", List.of(), null, 0, 3));

            assertEquals(AgentSegmentResult.Outcome.PROVIDER_ERROR, result.outcome());
            assertEquals(ModelResponse.ProviderErrorKind.PROVIDER_RETRY_ADMISSION_REJECTED,
                    result.providerErrorKind());
            assertEquals(1, raw.executions.get(),
                    "PASS: the UNKNOWN effect blocks the second actual Provider request");
            assertEquals(1, state.r1aDurable.findRecoveryBlockingOccurrences(
                    admitted.root().taskId(), admitted.root().initialRun().id(), "TOOL").size());
        }
    }

    @Test void RETRY_COMPACT_MUTUAL_EXCLUSION() throws Exception {
        try (StateStore state = new StateStore(workspace)) {
            var admitted = R3ContextTestFixtures.admit(state, workspace);
            List<AgentMessage> durable = new ArrayList<>();
            AgentMessage goal = AgentMessage.userGoal("retain the compact retry contract");
            durable.add(goal);
            admitted.guard().appendEvent(0, goal);
            for (int i = 1; i <= 11; i++) {
                AgentMessage history = AgentMessage.finalAnswer(
                        "completed durable history " + i + " " + "x".repeat(4_800));
                durable.add(history);
                admitted.guard().appendEvent(i, history);
            }
            ScriptedPreparedProvider raw = new ScriptedPreparedProvider(List.of(
                    timeout(null), success("same turn restored")));
            AtomicInteger compactEvents = new AtomicInteger();
            List<String> attemptGenerations = raw.attemptGenerations;
            ContextualModelProvider contextual = contextual(state, admitted, raw,
                    new CapabilityProfile("configured", 18_000,
                            LimitProvenance.CONFIGURED_LIMIT, 128, 32),
                    ignored -> compactEvents.incrementAndGet(), null);

            ModelResponse response = contextual.chat(OperationKind.CHAT, "retry-compact",
                    durable, List.of(), admitted.guard());

            assertEquals("same turn restored", response.content());
            assertEquals(1, compactEvents.get(),
                    "PASS: auto compact occurs before, never during, the logical retry turn");
            assertEquals(1, raw.prepares.get(),
                    "retry must not re-enter ContextualModelProvider or prepare a new turn");
            assertEquals(2, attemptGenerations.size());
            assertEquals(attemptGenerations.get(0), attemptGenerations.get(1));
        }
    }

    @Test void RETRY_EXHAUSTED_DURABLE_STATE_PRESERVED() throws Exception {
        try (StateStore state = new StateStore(workspace)) {
            Files.writeString(workspace.resolve("README.md"), "fixed retry evidence\n");
            ScriptedPreparedProvider raw = new ScriptedPreparedProvider(List.of(
                    toolCall("call-before-exhaustion"), timeout(null), timeout(null), timeout(null)));
            AtomicInteger toolExecutions = new AtomicInteger();
            CanonicalId task = CanonicalId.newId(EntityFamily.TASK);
            CodingTaskResult result = runner(state, task, raw, toolExecutions,
                    CodingTaskObserver.NOOP, ProviderCallProgressSink.NOOP)
                    .run(task.value(), "Read README.md and report its purpose.");

            assertEquals(TaskStore.STATUS_FAILED, result.finalStatus());
            assertEquals("FAILED", state.r1aDurable.findTask(task).orElseThrow().status());
            assertEquals("FAILED", state.r1aDurable.findRunsByTask(task)
                    .getFirst().terminalStatus());
            assertEquals(1, toolExecutions.get());
            assertEquals(1, state.toolCalls.findByTask(task.value()).size());
            assertTrue(countTable(state, "plan_revisions") >= 1);
            assertTrue(countTable(state, "plan_approvals") >= 1);
            assertTrue(countTable(state, "side_effect_occurrences") >= 2);
            assertEquals(0,countTable(state,"plan_evidence"),
                    "advisory Plan Evidence is not a retry or completion authority");
            assertEquals(0,countTable(state,"plan_run_bindings"));
            assertEquals(4, raw.executions.get(),
                    "one complete Tool turn plus exactly three exhausted continuation attempts");
            assertTrue(state.r1aDurable.findTask(task).isPresent(),
                    "PASS: terminalization preserves the durable Task; same-Task recovery "
                            + "remains unsupported and is not claimed here");
        }
    }

    @Test void RETRY_PRIVATE_METADATA_NON_DISCLOSURE() throws Exception {
        resetStageTrace(workspace);
        try (StateStore state = new StateStore(workspace)) {
            Files.writeString(workspace.resolve("README.md"), "fixed retry evidence\n");
            String privateMarker = "reasoning_content=PRIVATE_RETRY_SENTINEL";
            ScriptedPreparedProvider raw = new ScriptedPreparedProvider(List.of(
                    toolCall("call-private-scan"), privateIncomplete(privateMarker),
                    success("grounded final"), success("safe synthesis")));
            CanonicalId task = CanonicalId.newId(EntityFamily.TASK);
            InteractiveSession session = new InteractiveSession("retry-private-session",
                    workspace, "mock", "model", Config.MODE_DENY,
                    InteractiveSession.TerminalMode.PLAIN, Instant.now());
            session.start();
            List<AgentEvent> events = new ArrayList<>();
            AgentEventPublisher publisher = new AgentEventPublisher(
                    session.snapshot().sessionId(), Clock.systemUTC());
            publisher.addSink(events::add);
            RuntimeEventBridge bridge = new RuntimeEventBridge(publisher, session,
                    new InteractiveRuntimeView(), state, workspace);

            CodingTaskResult result = runner(state, task, raw, new AtomicInteger(),
                    bridge, bridge).run(task.value(), "Read README.md safely.");
            assertEquals(TaskStore.STATUS_DONE, result.finalStatus(), result.summary());

            String report = String.join("\n", new StateStoreInteractiveQueryFacade(
                    workspace, new InteractiveRuntimeView()).report(session.snapshot(),
                    task.value()));
            closeStageTrace();
            String trace = Files.readString(workspace.resolve("diagnostics")
                    .resolve("paicli-stage-trace.log"));
            String publicProjection = events + "\n" + report + "\n" + trace;
            for (String forbidden : List.of(privateMarker, "PRIVATE_RETRY_SENTINEL",
                    "Authorization:", "Bearer synthetic", "\"reasoning_content\"")) {
                assertFalse(publicProjection.contains(forbidden), publicProjection);
            }
            assertTrue(publicProjection.contains("Provider connection restored"));
            assertFalse(events.toString().contains(DIGEST),
                    "request digest may be durable telemetry but is not a public UI field");
        } finally {
            closeStageTrace();
        }
    }

    private AgentLoop loop(StateStore state, R3ContextTestFixtures.Admitted admitted,
            ScriptedPreparedProvider raw, AtomicInteger toolExecutions) {
        ToolRegistry tools = tools(toolExecutions);
        ContextualModelProvider contextual = contextual(state, admitted, raw,
                capability(), ignored -> { }, null);
        return new AgentLoop(contextual, tools, 8, null, null, state,
                admitted.root().taskId().value(), "mock", "model")
                .bindExecutionGuard(admitted.guard());
    }

    private ContextualModelProvider contextual(StateStore state,
            R3ContextTestFixtures.Admitted admitted, ScriptedPreparedProvider raw,
            CapabilityProfile capability,
            java.util.function.Consumer<com.paicli.context.CompactionCoordinator.Outcome> events,
            ProviderCallProgressSink sink) {
        String task = admitted.root().taskId().value();
        String run = admitted.root().initialRun().id().value();
        TelemetryModelProvider telemetry = telemetry(raw, state, task, run,
                sink == null ? ProviderCallProgressSink.NOOP : sink);
        return new ContextualModelProvider(telemetry, state, workspace, task, "mock", "model",
                OperationKind.CHAT, capability, false, "retry-runtime-contract", events);
    }

    private CodingTaskRunner runner(StateStore state, CanonicalId task,
            ScriptedPreparedProvider raw, AtomicInteger toolExecutions,
            CodingTaskObserver observer, ProviderCallProgressSink progress) throws Exception {
        Config config = new Config(workspace.toString(), 30, 50_000, 200_000,
                Config.MODE_DENY, 10_000, Config.PROVIDER_MOCK, "model", "", "",
                "system", 8, "", 120, 50_000, 1, true, false, false);
        ToolRegistry tools = tools(toolExecutions);
        return new CodingTaskRunner(state,
                new PlanService((messages, definitions) ->
                        ModelResponse.finalAnswer(PLAN_JSON), state.plans),
                new VerificationService(workspace, state.verifications,
                        new PersistenceSanitizer(), 120, 50_000),
                taskId -> {
                    String runId;
                    try {
                        runId = state.r1aDurable.readContextPointer(
                                CanonicalId.parse(taskId)).orElseThrow()
                                .ownerRunId().value();
                    } catch (Exception error) {
                        throw new IllegalStateException(error);
                    }
                    TelemetryModelProvider telemetry = telemetry(raw, state, taskId, runId,
                            progress);
                    ContextualModelProvider contextual = new ContextualModelProvider(
                            telemetry, state, workspace, taskId, "mock", "model",
                            OperationKind.CHAT, capability(), false,
                            "retry-runtime-contract", outcome -> {
                                if (observer instanceof RuntimeEventBridge bridge) {
                                    bridge.contextCompacted(taskId, runId, outcome);
                                }
                            });
                    return new AgentLoop(contextual, tools, config.effectiveMaxIterations(),
                            null, null, state, taskId, "mock", "model",
                            observer instanceof com.paicli.agent.AgentLoopObserver loopObserver
                                    ? loopObserver
                                    : com.paicli.agent.AgentLoopObserver.NOOP);
                }, config, new PersistenceSanitizer(), null, null,
                RuntimeManifest.disabled(), observer, CandidateVerificationGate.ALLOW,
                new InstructionManifestRuntime(state, workspace),
                (revision, summary) -> new PlanApprovalPort.Decision(
                        true, "USER_APPROVED", "retry-test-user", "test"));
    }

    private TelemetryModelProvider telemetry(ScriptedPreparedProvider raw, StateStore state,
            String task, String run, ProviderCallProgressSink progress) {
        return new TelemetryModelProvider(raw, state.providerTelemetry, "mock", "model",
                ProviderCallTelemetryStore.OperationKind.CHAT,
                () -> {
                    ProviderContextRuntime.ProviderTurnIdentity turn =
                            ProviderContextRuntime.currentTurn();
                    return new ProviderCallContext("retry-session", task, run,
                            turn == null ? null : turn.contextGenerationId());
                }, (ignored, response) -> ProviderCallObservation.notExposed(),
                (messages, tools, response) -> null, progress, Duration.ofSeconds(30),
                new ProviderRetryPolicy(duration -> { }, upper -> 0),
                expected -> {
                    assertNotNull(expected);
                    ProviderContextRuntime.requireRetryAdmission();
                    assertEquals(expected, ProviderContextRuntime.currentTurn());
                });
    }

    private static ToolRegistry tools(AtomicInteger executions) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override public String name() { return "read_file"; }
            @Override public String description() { return "read fixed evidence"; }
            @Override public ToolDefinition toDefinition() {
                return new ToolDefinition(name(), description(),
                        new ObjectMapper().createObjectNode());
            }
            @Override public java.util.Optional<com.paicli.tool.ToolAuthorityProjection>
                    authorityProjection(ToolCall call) {
                if (!"README.md".equals(call.arguments().get("path"))) {
                    return java.util.Optional.empty();
                }
                return java.util.Optional.of(com.paicli.tool.ToolAuthorityProjection.exactPath(
                        name(), com.paicli.state.PlanCompletionModels.EffectClass.READ,
                        "README.md", null, false, null));
            }
            @Override public ToolResult execute(ToolCall call) {
                executions.incrementAndGet();
                return ToolResult.success("fixed retry evidence");
            }
        });
        return registry;
    }

    private static CapabilityProfile capability() {
        return new CapabilityProfile("reported", 131_072,
                LimitProvenance.PROVIDER_REPORTED_LIMIT, 128, 32);
    }

    private static Attempt timeout(Runnable beforeReturn) {
        return new Attempt(ModelResponse.providerError(
                ModelResponse.ProviderErrorKind.PROVIDER_TIMEOUT),
                new ProviderRetrySignal(ProviderRetrySignal.Reason.TRANSPORT_READ_TIMEOUT,
                        true, Duration.ZERO, "TIMEOUT"), beforeReturn, null);
    }

    private static Attempt privateIncomplete(String privatePayload) {
        return new Attempt(ModelResponse.providerError(ModelResponse.ProviderErrorKind
                .PROVIDER_PRIVATE_CONTINUATION_INCOMPLETE),
                new ProviderRetrySignal(ProviderRetrySignal.Reason
                        .PRIVATE_CONTINUATION_INCOMPLETE, true, Duration.ZERO, null),
                null, privatePayload);
    }

    private static Attempt toolCall(String id) {
        return new Attempt(ModelResponse.toolCallResponse("read",
                new ToolCall("read_file", Map.of("path", "README.md"), id)),
                null, null, null);
    }

    private static Attempt success(String content) {
        return new Attempt(ModelResponse.finalAnswer(content), null, null, null);
    }

    private long countOccurrences(StateStore state, String type) throws Exception {
        return count(state, "SELECT count(*) FROM side_effect_occurrences "
                + "WHERE operation_type='" + type + "'");
    }

    private long countOccurrencesUnchecked(String type) {
        try {
            return count(null, "SELECT count(*) FROM side_effect_occurrences "
                    + "WHERE operation_type='" + type + "'");
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private long countTable(StateStore state, String table) throws Exception {
        return count(state, "SELECT count(*) FROM " + table);
    }

    private long count(StateStore state, String sql) throws Exception {
        try (var database = new com.paicli.state.DatabaseManager(
                    workspace.resolve(".paicli/state"));
             var statement = database.getConnection().createStatement();
             var row = statement.executeQuery(sql)) {
            assertTrue(row.next());
            return row.getLong(1);
        }
    }

    private static void resetStageTrace(Path root) throws Exception {
        invokeStageTrace("resetForTests", new Class<?>[]{Path.class}, root);
    }

    private static void closeStageTrace() throws Exception {
        invokeStageTrace("closeForTests", new Class<?>[0]);
    }

    private static void invokeStageTrace(String name, Class<?>[] types, Object... args)
            throws Exception {
        Class<?> type = Class.forName("com.paicli.diagnostic.StageTrace");
        Method method = type.getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(null, args);
    }

    private record Attempt(ModelResponse response, ProviderRetrySignal signal,
            Runnable beforeReturn, String privatePayload) { }

    private static final class ScriptedPreparedProvider implements PreparedTurnProvider,
            ProviderRetrySignalSource {
        private final ArrayDeque<Attempt> attempts;
        private final AtomicInteger prepares = new AtomicInteger();
        private final AtomicInteger executions = new AtomicInteger();
        private final List<List<AgentMessage>> seenMessages = new ArrayList<>();
        private final List<String> attemptGenerations = new ArrayList<>();
        private ProviderRetrySignal currentSignal;

        private ScriptedPreparedProvider(List<Attempt> attempts) {
            this.attempts = new ArrayDeque<>(attempts);
        }

        @Override public PreparedProviderTurn prepareTurn(String operation,
                List<AgentMessage> messages, List<ToolDefinition> tools) {
            prepares.incrementAndGet();
            List<AgentMessage> snapshot = List.copyOf(messages);
            return new PreparedProviderTurn(DIGEST, () -> {
                executions.incrementAndGet();
                seenMessages.add(snapshot);
                ProviderContextRuntime.ProviderTurnIdentity turn =
                        ProviderContextRuntime.currentTurn();
                attemptGenerations.add(turn == null ? "none" : turn.contextGenerationId());
                Attempt attempt = attempts.removeFirst();
                if (attempt.beforeReturn() != null) attempt.beforeReturn().run();
                currentSignal = attempt.signal();
                return attempt.response();
            });
        }

        @Override public ProviderRetrySignal takeRetrySignal() {
            ProviderRetrySignal value = currentSignal;
            currentSignal = null;
            return value;
        }

        @Override public String toString() {
            return attempts.stream().map(Attempt::privatePayload)
                    .filter(java.util.Objects::nonNull)
                    .reduce("", String::concat);
        }
    }
}
