package com.paicli.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.agent.AgentLoop;
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
import com.paicli.state.DatabaseManager;
import com.paicli.state.RepairAttemptStore;
import com.paicli.state.StateStore;
import com.paicli.state.TaskStore;
import com.paicli.state.VerificationStore;
import com.paicli.tool.Tool;
import com.paicli.tool.ToolAuthorityProjection;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static com.paicli.state.PlanCompletionModels.EffectClass.WRITE;
import static org.junit.jupiter.api.Assertions.*;

/** Benchmark-independent C3 regression through the durable, fenced production path. */
class C3ContextContinuationProgressRegressionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path workspace;

    @Test void ledgerFinalRejectionClosesProviderOccurrenceAndPreservesProgress()throws Exception{
        AtomicInteger calls=new AtomicInteger();
        ModelProvider raw=(messages,definitions)->{
            calls.incrementAndGet();
            return ModelResponse.finalAnswer("kind=TOOL_OCCURRENCE;source_id=occ_fixture;revision=hash;status=COMPLETED;");
        };
        try(var state=new StateStore(workspace)){
            var result=runner(state,raw,new ToolRegistry(),config(3),false)
                    .run(CanonicalId.newId(EntityFamily.TASK).value(),"Fix pagination");
            assertEquals(TaskStore.STATUS_FAILED,result.finalStatus());
            assertTrue(result.summary().contains("FINAL_RESPONSE_EXECUTION_LEDGER_REJECTED"));
            assertEquals(1,calls.get());
            assertEquals(1,state.codingRuns.findById(result.runId()).orElseThrow().lastIteration());
            assertTrue(state.userResults.find(CanonicalId.parse(result.taskId()),CanonicalId.parse(result.runId())).isEmpty());
            assertTrue(state.events.findMessages(result.taskId()).stream().noneMatch(m->m.type()==AgentMessage.MessageType.FINAL));
            try(var database=new DatabaseManager(workspace.resolve(".paicli/state"));var statement=database.getConnection().prepareStatement(
                    "SELECT status FROM side_effect_occurrences WHERE task_id=? AND operation_type='PROVIDER'")){
                statement.setString(1,result.taskId());
                try(var rows=statement.executeQuery()){
                    assertTrue(rows.next());assertEquals("FAILED",rows.getString(1));assertFalse(rows.next());
                }
            }
        }
    }

    @Test void controlDenialReachesNextContextualProviderTurnWithoutExecutingTools()throws Exception{
        AtomicInteger calls=new AtomicInteger();
        ModelProvider raw=(messages,definitions)->{
            if(calls.incrementAndGet()==1)return ModelResponse.toolCallResponse("inspect",
                    new ToolCall("read_file",Map.of("path",".paicli/skills/PRIVATE_CANARY",
                            "include_internal",true),"denied-call"));
            var last=messages.getLast();
            assertEquals(AgentMessage.MessageType.OBSERVATION,last.type());
            assertEquals("denied-call",last.toolCallId());
            assertTrue(last.content().contains("CONTROL_DIRECTORY_ACCESS_NOT_USER_AUTHORIZED"));
            assertFalse(messages.toString().contains("PRIVATE_CANARY"));
            return ModelResponse.finalAnswer("The review is incomplete because the resource was unavailable.");
        };
        try(var state=new StateStore(workspace)){
            var result=runner(state,raw,new ToolRegistry(),config(3),false)
                    .run(CanonicalId.newId(EntityFamily.TASK).value(),"Review React performance");
            assertEquals(TaskStore.STATUS_DONE,result.finalStatus(),result.summary());
            assertEquals(2,calls.get());assertTrue(state.toolCalls.findByTask(result.taskId()).isEmpty());
        }
    }

    @Test
    void twoRejectedBatchesHaveCompleteClosuresAndReachThirdProvider()
            throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger effects = new AtomicInteger();
        ModelProvider raw = (messages, definitions) -> switch (providerCalls.incrementAndGet()) {
            case 1 -> rejectedBatch("first");
            case 2 -> {
                assertLatestTypedNonExecutionResults(messages, "first-a", "first-b");
                yield rejectedBatch("second");
            }
            case 3 -> {
                assertLatestTypedNonExecutionResults(messages, "second-a", "second-b");
                yield ModelResponse.finalAnswer("synthetic done");
            }
            default -> throw new AssertionError("unexpected Provider call");
        };

        try (StateStore state = new StateStore(workspace)) {
            CodingTaskResult result = runner(state, raw, registry(effects), config(3), false)
                    .run(CanonicalId.newId(EntityFamily.TASK).value(), "synthetic continuation goal");

            assertEquals(TaskStore.STATUS_DONE, result.finalStatus(), result.summary());
            assertEquals(3, providerCalls.get(),
                    "two TOOL_CONTINUATION closures must reach a third Provider turn");
            assertEquals(0, effects.get(), "both rejected batches must have zero Tool effects");
        }
    }

    @Test
    void repairToolContinuationRetainsExactRunningRepairAuthorityAndReverifiesNewCandidate()
            throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger effects = new AtomicInteger();
        ModelProvider raw = (messages, definitions) -> switch (providerCalls.incrementAndGet()) {
            case 1 -> ModelResponse.finalAnswer("initial candidate");
            case 2 -> ModelResponse.toolCallResponse("repair candidate",
                    new ToolCall("write_file", Map.of(
                            "path", "repaired.txt", "content", "fixed"), "repair-write"));
            case 3 -> {
                assertTrue(messages.stream().anyMatch(message ->
                        message.type() == AgentMessage.MessageType.OBSERVATION
                                && "repair-write".equals(message.toolCallId())
                                && message.content().contains("synthetic effect")));
                yield ModelResponse.finalAnswer("repaired candidate");
            }
            default -> throw new AssertionError("unexpected Provider call");
        };

        try (StateStore state = new StateStore(workspace)) {
            CodingTaskResult result = runner(state, raw, registry(effects), repairConfig(5), false)
                    .run(CanonicalId.newId(EntityFamily.TASK).value(), "repair continuation goal");

            assertEquals(TaskStore.STATUS_DONE, result.finalStatus(), result.summary());
            assertEquals(VerificationStore.STATUS_PASSED, result.verificationStatus());
            assertEquals(1, result.repairAttempts());
            assertEquals(3, providerCalls.get());
            assertEquals(1, effects.get(), "the Repair Tool effect must execute exactly once");
            assertEquals("fixed", Files.readString(workspace.resolve("repaired.txt")));
            assertEquals(RepairAttemptStore.STATUS_COMPLETED,
                    state.repairAttempts.findByRun(result.runId()).getFirst().status());
            var verifications = state.verifications.findByRun(result.runId());
            assertEquals(List.of(VerificationStore.STATUS_FAILED, VerificationStore.STATUS_PASSED),
                    verifications.stream().map(VerificationStore.VerificationRecord::status).toList());
            assertNotEquals(verifications.getFirst().candidateDigest(),
                    verifications.getLast().candidateDigest(),
                    "Repair Verification must bind the new H5 candidate");
            var userResult = state.userResults.find(CanonicalId.parse(result.taskId()),
                    CanonicalId.parse(result.runId())).orElseThrow();
            assertNull(userResult.planRevisionId());
            assertEquals(List.of(OperationKind.CHAT, OperationKind.REPAIR,
                            OperationKind.TOOL_CONTINUATION),
                    providerOperations(result.taskId(), result.runId()));
            assertEquals(2, providerFinalCandidateCount(result.taskId(), result.runId()));
        }
    }

    @Test
    void repairSupportsTwoLegalToolContinuationsBeforeFinal() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger effects = new AtomicInteger();
        ModelProvider raw = (messages, definitions) -> switch (providerCalls.incrementAndGet()) {
            case 1 -> ModelResponse.finalAnswer("initial candidate");
            case 2 -> ModelResponse.toolCallResponse("first repair Tool",
                    new ToolCall("write_file", Map.of(
                            "path", "first.txt", "content", "first"), "repair-first"));
            case 3 -> {
                assertObservation(messages, "repair-first", "synthetic effect");
                yield ModelResponse.toolCallResponse("second repair Tool",
                        new ToolCall("write_file", Map.of(
                                "path", "repaired.txt", "content", "second"), "repair-second"));
            }
            case 4 -> {
                assertObservation(messages, "repair-second", "synthetic effect");
                yield ModelResponse.finalAnswer("repaired candidate");
            }
            default -> throw new AssertionError("unexpected Provider call");
        };

        try (StateStore state = new StateStore(workspace)) {
            CodingTaskResult result = runner(state, raw, registry(effects), repairConfig(6), false)
                    .run(CanonicalId.newId(EntityFamily.TASK).value(), "two Tool repair goal");

            assertEquals(TaskStore.STATUS_DONE, result.finalStatus(), result.summary());
            assertEquals(VerificationStore.STATUS_PASSED, result.verificationStatus());
            assertEquals(4, providerCalls.get());
            assertEquals(2, effects.get(), "each Repair Tool must execute exactly once");
            assertEquals(List.of(OperationKind.CHAT, OperationKind.REPAIR,
                            OperationKind.TOOL_CONTINUATION, OperationKind.TOOL_CONTINUATION),
                    providerOperations(result.taskId(), result.runId()));
        }
    }

    @Test
    void deniedRepairBatchHasZeroEffectsAndRetainsRepairContinuationAuthority() throws Exception {
        String unsafePath = "UNSAFE_REPAIR_PATH_SENTINEL";
        String unsafeContent = "UNSAFE_REPAIR_CONTENT_SENTINEL";
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger effects = new AtomicInteger();
        ModelProvider raw = (messages, definitions) -> switch (providerCalls.incrementAndGet()) {
            case 1 -> ModelResponse.finalAnswer("initial candidate");
            case 2 -> ModelResponse.toolCallBatchResponse("denied repair batch", List.of(
                    new ToolCall("write_file", Map.of(
                            "path", unsafePath, "content", unsafeContent), "repair-denied"),
                    new ToolCall("write_file", Map.of(
                            "path", "related.txt", "content", "related"), "repair-related")),
                    ProviderResponseEnvelope.inspect(List.of("repair-denied", "repair-related"),
                            false), false);
            case 3 -> {
                assertObservation(messages, "repair-denied", "SAFE_TYPED_PREFLIGHT_REJECTION_V1");
                assertObservation(messages, "repair-related", "RELATED_BATCH_MEMBER_NOT_EXECUTED");
                assertTrue(messages.stream().noneMatch(message ->
                        message.content().contains(unsafePath)
                                || message.content().contains(unsafeContent)));
                yield ModelResponse.toolCallResponse("legal repair Tool",
                        new ToolCall("write_file", Map.of(
                                "path", "repaired.txt", "content", "safe"), "repair-legal"));
            }
            case 4 -> {
                assertObservation(messages, "repair-legal", "synthetic effect");
                yield ModelResponse.finalAnswer("safe repaired candidate");
            }
            default -> throw new AssertionError("unexpected Provider call");
        };

        try (StateStore state = new StateStore(workspace)) {
            CodingTaskResult result = runner(state, raw, registry(effects), repairConfig(6), false)
                    .run(CanonicalId.newId(EntityFamily.TASK).value(), "denied Tool repair goal");

            assertEquals(TaskStore.STATUS_DONE, result.finalStatus(), result.summary());
            assertEquals(4, providerCalls.get());
            assertEquals(1, effects.get(), "the denied batch must have zero effects");
            assertFalse(Files.exists(workspace.resolve(unsafePath)));
            assertFalse(Files.exists(workspace.resolve("related.txt")));
            assertNoPersistedSentinel(result.taskId(), unsafePath, unsafeContent);
            assertEquals(List.of(OperationKind.CHAT, OperationKind.REPAIR,
                            OperationKind.TOOL_CONTINUATION, OperationKind.TOOL_CONTINUATION),
                    providerOperations(result.taskId(), result.runId()));
        }
    }

    @Test
    void repairObservationPersistenceFailureStopsBeforeAnotherProviderTurn() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger effects = new AtomicInteger();
        ModelProvider raw = (messages, definitions) -> switch (providerCalls.incrementAndGet()) {
            case 1 -> ModelResponse.finalAnswer("initial candidate");
            case 2 -> ModelResponse.toolCallResponse("repair persistence failure",
                    new ToolCall("write_file", Map.of(
                            "path", "repair-effect.txt", "content", "effect"),
                            "repair-persist-fail"));
            default -> throw new AssertionError("Provider continuation must not be admitted");
        };

        try (StateStore state = new StateStore(workspace)) {
            try (DatabaseManager database = new DatabaseManager(workspace.resolve(".paicli/state"));
                 var statement = database.getConnection().createStatement()) {
                statement.execute("""
                        CREATE TRIGGER reject_repair_observation
                        BEFORE INSERT ON events
                        WHEN NEW.type='OBSERVATION' AND NEW.tool_call_id='repair-persist-fail'
                        BEGIN SELECT RAISE(ABORT,'synthetic Repair C3 persistence failure'); END
                        """);
            }
            String taskId=CanonicalId.newId(EntityFamily.TASK).value();
            assertThrows(FencedExecutionGuard.AuthorityLostException.class,
                    ()->runner(state,raw,registry(effects),repairConfig(5),false)
                            .run(taskId,"repair persistence failure goal"));
            String runId=state.codingRuns.findByTask(taskId).getFirst().runId();
            assertEquals(2, providerCalls.get());
            assertEquals(1, effects.get(), "the admitted Tool effect must not replay");
            assertEquals(RepairAttemptStore.STATUS_RUNNING,
                    state.repairAttempts.findByRun(runId).getFirst().status(),
                    "authority loss leaves the unresolved Repair durable and fail closed");
            assertEquals(List.of(OperationKind.CHAT, OperationKind.REPAIR),
                    providerOperations(taskId,runId));
            assertTrue(state.userResults.find(CanonicalId.parse(taskId),
                    CanonicalId.parse(runId)).isEmpty());
        }
    }

    @Test
    void initialContextFailureHandsExactObservedProgressToCurrentRun() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger effects = new AtomicInteger();
        ModelProvider raw = (messages, definitions) -> rejectedBatch(
                "initial-" + providerCalls.incrementAndGet());

        try (StateStore state = new StateStore(workspace)) {
            CodingTaskResult result = runner(state, raw, registry(effects), config(3), true)
                    .run(CanonicalId.newId(EntityFamily.TASK).value(), "synthetic initial progress goal");

            assertEquals(TaskStore.STATUS_FAILED, result.finalStatus());
            assertTrue(result.summary().contains("CONTEXT_BUDGET_EXCEEDED"),result.summary());
            assertEquals(1, providerCalls.get(), "context failure must precede second Provider admission");
            var run = state.codingRuns.findById(result.runId()).orElseThrow();
            var checkpoint = state.checkpoints.loadLatest(result.taskId()).orElseThrow();
            assertEquals(2, checkpoint.iteration());
            assertEquals(2, run.lastIteration(),
                    "current Run must receive the exact AgentLoop context-failure coordinate");
            assertTrue(run.lastIteration() >= run.startedIteration());
            assertEquals(0, effects.get());
        }
    }

    @Test
    void resumedContextFailureUsesTaskGlobalCoordinateAndLeavesPredecessorImmutable()
            throws Exception {
        Files.writeString(workspace.resolve("AGENTS.md"), "synthetic frozen rule");
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger effects = new AtomicInteger();
        try (StateStore state = new StateStore(workspace)) {
            Config config = config(8);
            CanonicalId task = CanonicalId.newId(EntityFamily.TASK);
            Instant now = Instant.now();
            var root = new InstructionManifestRuntime(state, workspace).admitRoot(
                    task, "synthetic resume progress goal", 10_000, config,
                    RuntimeManifest.disabled(), now.minusSeconds(10));
            String predecessorId = root.initialRun().id().value();
            state.tasks.updateIterations(task.value(), 4);
            state.checkpoints.save(task.value(), 4, "RUNNING",
                    List.of(AgentMessage.userGoal("synthetic resume progress goal")));
            state.events.append(task.value(), 0,
                    AgentMessage.userGoal("synthetic resume progress goal"));
            state.codingRuns.updateLastIteration(predecessorId, 4);

            TaskRecoveryService.Success recovery = assertInstanceOf(TaskRecoveryService.Success.class,
                    new TaskRecoveryService(state, new InstructionManifestRuntime(state, workspace))
                            .recover(task, config, RuntimeManifest.disabled(), 10_000,
                                    new TaskRecoveryService.LeaseRequest("synthetic-owner",
                                            "synthetic-lease", now, now.plusSeconds(30),
                                            Duration.ZERO)));
            ModelProvider raw = (messages, definitions) -> rejectedBatch(
                    "resume-" + providerCalls.incrementAndGet());
            CodingTaskResult result = runner(state, raw, registry(effects), config, true)
                    .recover(recovery, "synthetic resume progress goal");

            var predecessor = state.codingRuns.findById(predecessorId).orElseThrow();
            var resumed = state.codingRuns.findById(result.runId()).orElseThrow();
            assertEquals(CodingRunStore.KIND_INITIAL, predecessor.runKind());
            assertEquals(4, predecessor.lastIteration(), "RESUME must not mutate its predecessor");
            assertEquals(CodingRunStore.KIND_RESUME, resumed.runKind());
            assertEquals(4, resumed.startedIteration());
            int checkpoint = state.checkpoints.loadLatest(task.value()).orElseThrow().iteration();
            assertAll(
                    () -> assertEquals(1, providerCalls.get(), result.summary()),
                    () -> assertTrue(result.summary().contains("CONTEXT_BUDGET_EXCEEDED"),
                            result.summary()),
                    () -> assertEquals(6, checkpoint),
                    () -> assertEquals(6, resumed.lastIteration(),
                            "RESUME context failure must use the Task-global AgentLoop coordinate"),
                    () -> assertTrue(resumed.lastIteration() >= resumed.startedIteration()));
            assertEquals(0, effects.get());
            assertEquals(6, new ContextRebuilder(state, workspace, 4096, 4096)
                    .rebuild(task.value()).startIteration());
        }
    }

    private CodingTaskRunner runner(StateStore state, ModelProvider raw, ToolRegistry registry,
            Config config, boolean failContinuationContext) throws Exception {
        PersistenceSanitizer sanitizer = new PersistenceSanitizer();
        return new CodingTaskRunner(state, new PlanService(raw, state.plans),
                new VerificationService(workspace, state.verifications, sanitizer, 5, 4096),
                task -> {
                    ContextualModelProvider contextual = new ContextualModelProvider(raw, state,
                            workspace, task, "mock", "synthetic-model", OperationKind.CHAT,
                            operation -> operation == OperationKind.TOOL_CONTINUATION
                                    && failContinuationContext
                                    ? new CapabilityProfile("synthetic-failure", 1,
                                            LimitProvenance.CONFIGURED_LIMIT, 128, 32)
                                    : new CapabilityProfile("synthetic", 131_072,
                                            LimitProvenance.PROVIDER_REPORTED_LIMIT, 128, 32),
                            false, "synthetic C3 runtime contract", ignored -> { });
                    return new AgentLoop(contextual, registry, config.effectiveMaxIterations(),
                            null, null, state, task, "mock", "synthetic-model");
                }, config, sanitizer, null, null, RuntimeManifest.disabled(),
                CodingTaskObserver.NOOP, CandidateVerificationGate.ALLOW,
                new InstructionManifestRuntime(state, workspace));
    }

    private Config config(int maxIterations) {
        return new Config(workspace.toString(), 5, 4096, 16_384,
                Config.MODE_DENY, 10_000, Config.PROVIDER_MOCK, "synthetic-model",
                "", "", "system", maxIterations, "", 5, 4096, 1,
                false, false, false);
    }

    private Config repairConfig(int maxIterations) {
        return new Config(workspace.toString(), 5, 4096, 16_384,
                Config.MODE_DENY, 10_000, Config.PROVIDER_MOCK, "synthetic-model",
                "", "", "system", maxIterations, fileMustExistCommand("repaired.txt"),
                5, 4096, 1, false, true, true);
    }

    private ToolRegistry registry(AtomicInteger effects) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new Tool() {
            @Override public String name() { return "write_file"; }
            @Override public String description() { return "synthetic effectful Tool"; }
            @Override public ToolDefinition toDefinition() {
                return new ToolDefinition(name(), description(), JSON.createObjectNode()
                        .put("type", "object"));
            }
            @Override public Optional<ToolAuthorityProjection> authorityProjection(ToolCall call) {
                return Optional.of(ToolAuthorityProjection.exactPath(name(), WRITE,
                        String.valueOf(call.arguments().get("path")), null, false, true));
            }
            @Override public ToolResult preflightCall(ToolCall call) {
                return String.valueOf(call.arguments().get("path")).startsWith("UNSAFE_REPAIR_")
                        ? ToolResult.error("synthetic policy denial") : ToolResult.success("");
            }
            @Override public ToolResult execute(ToolCall call) {
                effects.incrementAndGet();
                try {
                    String path = String.valueOf(call.arguments().get("path"));
                    String content = String.valueOf(call.arguments().getOrDefault("content", ""));
                    Files.writeString(workspace.resolve(path), content);
                    return ToolResult.success("synthetic effect");
                } catch (Exception error) {
                    return ToolResult.error("synthetic write failed");
                }
            }
        });
        return registry;
    }

    private List<OperationKind> providerOperations(String taskId, String runId) throws Exception {
        var operations = new ArrayList<OperationKind>();
        try (DatabaseManager database = new DatabaseManager(workspace.resolve(".paicli/state"));
             var statement = database.getConnection().prepareStatement("""
                     SELECT target FROM side_effect_occurrences
                     WHERE task_id=? AND run_id=? AND operation_type='PROVIDER'
                     ORDER BY started_at, rowid
                     """)) {
            statement.setString(1, taskId);
            statement.setString(2, runId);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) operations.add(OperationKind.valueOf(rows.getString(1)));
            }
        }
        return List.copyOf(operations);
    }

    private void assertNoPersistedSentinel(String taskId, String... sentinels) throws Exception {
        try (DatabaseManager database = new DatabaseManager(workspace.resolve(".paicli/state"))) {
            for (String sentinel : sentinels) {
                for (String sql : List.of(
                        "SELECT count(*) FROM events WHERE task_id=? AND "
                                + "(coalesce(content,'')||coalesce(tool_call_json,'')) LIKE ?",
                        "SELECT count(*) FROM checkpoints WHERE task_id=? AND messages_json LIKE ?",
                        "SELECT count(*) FROM tool_calls WHERE task_id=? AND "
                                + "(coalesce(arguments,'')||coalesce(result_output,'')"
                                + "||coalesce(result_error,'')) LIKE ?")) {
                    try (var statement = database.getConnection().prepareStatement(sql)) {
                        statement.setString(1, taskId);
                        statement.setString(2, "%" + sentinel + "%");
                        try (var row = statement.executeQuery()) {
                            assertTrue(row.next());
                            assertEquals(0, row.getLong(1), "unsafe arguments must stay redacted");
                        }
                    }
                }
            }
        }
    }

    private long providerFinalCandidateCount(String taskId, String runId) throws Exception {
        try (DatabaseManager database = new DatabaseManager(workspace.resolve(".paicli/state"));
             var statement = database.getConnection().prepareStatement("""
                     SELECT count(*) FROM side_effect_occurrences
                     WHERE task_id=? AND run_id=? AND operation_type='PROVIDER'
                       AND status='COMPLETED' AND final_candidate_digest IS NOT NULL
                     """)) {
            statement.setString(1, taskId);
            statement.setString(2, runId);
            try (var row = statement.executeQuery()) {
                return row.next() ? row.getLong(1) : 0;
            }
        }
    }

    private String fileMustExistCommand(String file) {
        return System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "if exist " + file + " (exit /b 0) else (echo missing 1>&2 & exit /b 1)"
                : "if [ -f " + file + " ]; then exit 0; else echo missing >&2; exit 1; fi";
    }

    private static void assertObservation(List<AgentMessage> messages, String callId,
            String expectedContent) {
        assertTrue(messages.stream().anyMatch(message ->
                message.type() == AgentMessage.MessageType.OBSERVATION
                        && callId.equals(message.toolCallId())
                        && message.content().contains(expectedContent)),
                () -> "missing Observation for " + callId);
    }

    private static ModelResponse rejectedBatch(String prefix) {
        String first = prefix + "-a";
        String second = prefix + "-b";
        return ModelResponse.toolCallBatchResponse("synthetic effectful batch", List.of(
                        new ToolCall("write_file", Map.of("path", first), first),
                        new ToolCall("write_file", Map.of("path", second), second)),
                ProviderResponseEnvelope.inspect(List.of(first, second), false), false);
    }

    private static void assertLatestTypedNonExecutionResults(List<AgentMessage> messages,
            String first, String second) {
        List<AgentMessage> results = messages.stream()
                .filter(message -> message.type() == AgentMessage.MessageType.OBSERVATION)
                .filter(message -> first.equals(message.toolCallId())
                        || second.equals(message.toolCallId()))
                .toList();
        assertEquals(List.of(first, second), results.stream().map(AgentMessage::toolCallId).toList());
        assertTrue(results.stream().allMatch(message ->
                message.content().contains("\"status\":\"NOT_EXECUTED\"")
                        && message.content().contains(
                                "\"code\":\"EFFECTFUL_BATCH_REQUIRES_SERIALIZATION\"")));
    }
}
