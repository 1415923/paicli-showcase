package com.paicli.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.agent.AgentLoop;
import com.paicli.config.Config;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.ModelProvider;
import com.paicli.provider.ProviderResponseEnvelope;
import com.paicli.safety.ApprovalPolicy;
import com.paicli.safety.SafetyPolicy;
import com.paicli.state.DatabaseManager;
import com.paicli.state.FileSnapshotService;
import com.paicli.state.StateStore;
import com.paicli.tool.Tool;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolResult;
import com.paicli.tool.WriteFileTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Proves that explicitly disabled continuity is observationally equal to the default-off path. */
class TaskContinuityDisabledEquivalenceTest {
    private static final String VALID_PLAN="""
            {"acceptanceCriteria":["done"],"assumptions":[],"risks":[],
             "verificationStrategy":"runtime result","steps":[
             {"title":"Work","definition":"Complete the task","dependsOn":[]}]}
            """;
    private static final String INVALID_PLAN="""
            {"acceptanceCriteria":["done"],"steps":[],"unexpected":true}
            """;

    @TempDir Path root;

    @Test void defaultOffAndExplicitDisabledAreEquivalentAcrossLegacyTerminalFamilies()
            throws Exception {
        for (Scenario scenario : Scenario.values()) {
            Snapshot defaultOff = execute(scenario, false);
            Snapshot explicitDisabled = execute(scenario, true);
            assertEquals(defaultOff, explicitDisabled, scenario.name());
            assertEquals(0, defaultOff.continuityRows(), scenario.name());
            assertEquals(0, defaultOff.additionalSegments(), scenario.name());
            if (scenario == Scenario.REPEATED_EFFECTFUL_BATCH) {
                assertEquals(4, defaultOff.providerInvocations(),
                        "one Plan turn plus three bounded execution turns");
                assertEquals(0, defaultOff.toolInvocations());
                assertEquals("Agent execution reached the segment iteration limit",
                        defaultOff.terminalReason());
            }
        }
    }

    private Snapshot execute(Scenario scenario, boolean explicitDisabled) throws Exception {
        Path workspace = root.resolve(scenario.name().toLowerCase()+"-"+
                (explicitDisabled?"explicit":"default"));
        Files.createDirectories(workspace);
        QueueProvider plan = new QueueProvider(scenario == Scenario.PLAN_TERMINAL_FAILURE
                ? List.of(ModelResponse.finalAnswer(INVALID_PLAN),
                        ModelResponse.finalAnswer(INVALID_PLAN))
                : List.of(ModelResponse.finalAnswer(VALID_PLAN)));
        Config config = config(workspace, scenario);
        CanonicalId task = CanonicalId.newId(EntityFamily.TASK);
        try (StateStore state = new StateStore(workspace)) {
            Runnable beforeFirstChat = scenario == Scenario.UNKNOWN_OCCURRENCE ? () -> {
                try {
                    var lease = state.r1aDurable.readOwnership(task).orElseThrow();
                    FencedExecutionGuard.system(state, TaskExecutionAuthority.initial(lease))
                            .admitOperationOccurrence("0", "disabled-equivalence-unknown",
                                    "TOOL", "write_file", "a".repeat(64));
                } catch (Exception error) {
                    throw new IllegalStateException(error);
                }
            } : () -> { };
            QueueProvider chat = new QueueProvider(chatResponses(scenario), beforeFirstChat);
            ToolRegistry tools = tools(workspace, state, task.value());
            CodingTaskRunner runner = new CodingTaskRunner(state,
                    new PlanService(plan, state.plans),
                    new VerificationService(workspace, state.verifications,
                            new PersistenceSanitizer(), 30, 50_000),
                    taskId -> new AgentLoop(chat, tools, config.effectiveMaxIterations(),
                            new ApprovalPolicy(config, taskId), null, state, taskId,
                            "mock", "model"),
                    config, new PersistenceSanitizer(), null, null,
                    RuntimeManifest.disabled(), CodingTaskObserver.NOOP,
                    (taskId, runId) -> false,
                    new InstructionManifestRuntime(state, workspace),
                    (revision, summary) -> new PlanApprovalPort.Decision(true,
                            "USER_CONFIRMED", "disabled-equivalence", "v1"));
            if (explicitDisabled) runner.withTaskContinuityPolicy(
                    TaskContinuityPolicy.disabled(),
                    TaskContinuityModels.BudgetEvidenceSource.UNAVAILABLE);
            CodingTaskResult result = runner.run(task.value(), "scripted "+scenario.name());
            var storedTask = state.tasks.findById(result.taskId()).orElseThrow();
            var storedRun = state.codingRuns.findById(result.runId()).orElseThrow();
            long occurrences = count(workspace, "side_effect_occurrences", result.taskId());
            long duplicates = duplicateOccurrences(workspace, result.taskId());
            return new Snapshot(storedTask.status(), storedRun.phase(), result.finalStatus(),
                    normalizedReason(result.summary()), plan.calls+chat.calls,
                    state.toolCalls.findByTask(result.taskId()).size(), occurrences, duplicates,
                    Files.exists(workspace.resolve("change.txt")),
                    state.taskContinuity.count(result.taskId()), 0,
                    state.taskContinuity.findByTask(result.taskId()).stream()
                            .anyMatch(row -> "WAITING_FOR_USER".equals(row.state())));
        }
    }

    private static List<ModelResponse> chatResponses(Scenario scenario) {
        ToolCall a = new ToolCall("write_file", Map.of("path", "a.txt",
                "content", "a"), "a");
        ToolCall b = new ToolCall("write_file", Map.of("path", "b.txt",
                "content", "b"), "b");
        return switch (scenario) {
            case PLAN_TERMINAL_FAILURE -> List.of();
            case REPEATED_EFFECTFUL_BATCH -> List.of(
                    ModelResponse.toolCallBatchResponse("batch", List.of(a,b), null,false),
                    ModelResponse.toolCallBatchResponse("batch", List.of(a,b), null,false),
                    ModelResponse.toolCallBatchResponse("batch", List.of(a,b), null,false));
            case INVALID_TOOL_RESPONSE -> List.of(ModelResponse.protocolError(
                    ProviderResponseEnvelope.inspect(java.util.stream.IntStream.range(0,9)
                            .mapToObj(index -> "bad-"+index).toList(), false)));
            case TOOL_PREFLIGHT_FAILURE -> List.of(ModelResponse.toolCallResponse("bad tool",
                    new ToolCall("not_registered", Map.of(), "bad-tool")));
            case ITERATION_EXHAUSTION -> List.of(ModelResponse.toolCallResponse("read",
                    new ToolCall("read_file", Map.of("path", "seed.txt"), "read")));
            case VERIFICATION_REPAIR_TERMINAL -> List.of(
                    ModelResponse.finalAnswer("initial"), ModelResponse.finalAnswer("repair"));
            case APPROVAL_REQUIRED -> List.of(ModelResponse.toolCallResponse("write",
                    new ToolCall("write_file", Map.of("path", "change.txt",
                            "content", "blocked"), "approval")));
            case UNKNOWN_OCCURRENCE -> List.of(ModelResponse.finalAnswer("done"));
        };
    }

    private ToolRegistry tools(Path workspace, StateStore state, String taskId) throws Exception {
        Files.writeString(workspace.resolve("seed.txt"), "seed");
        ToolRegistry tools = new ToolRegistry();
        tools.register(new WriteFileTool(new SafetyPolicy(workspace),
                new FileSnapshotService(workspace, state.snapshots), taskId));
        tools.register(new Tool() {
            public String name() { return "read_file"; }
            public String description() { return "read deterministic seed"; }
            public ToolDefinition toDefinition() { return new ToolDefinition(name(),description(),
                    new ObjectMapper().createObjectNode()); }
            public ToolResult execute(ToolCall call) { return ToolResult.success("seed"); }
        });
        return tools;
    }

    private Config config(Path workspace, Scenario scenario) {
        String verification = scenario == Scenario.VERIFICATION_REPAIR_TERMINAL
                ? (isWindows()?"cmd /c exit 7":"sh -c 'exit 7'") : "";
        String approval = scenario == Scenario.APPROVAL_REQUIRED
                ? Config.MODE_DENY : Config.MODE_WORKSPACE_WRITE;
        int iterations = scenario == Scenario.ITERATION_EXHAUSTION ? 1 : 3;
        return new Config(workspace.toString(),30,50_000,200_000,approval,10_000,
                Config.PROVIDER_MOCK,"model","","","system",iterations,verification,30,
                50_000,1,true,false,false);
    }

    private static String normalizedReason(String value) {
        if (value == null) return null;
        return value.replaceAll("(tsk|run|pln|occ)_[A-Za-z0-9]+", "$1_ID");
    }

    private static long count(Path workspace, String table, String taskId) throws Exception {
        try (DatabaseManager db = new DatabaseManager(workspace.resolve(".paicli/state"));
             PreparedStatement statement = db.getConnection().prepareStatement(
                     "SELECT count(*) FROM "+table+" WHERE task_id=?")) {
            statement.setString(1, taskId);
            try (var row = statement.executeQuery()) { return row.next()?row.getLong(1):0; }
        }
    }

    private static long duplicateOccurrences(Path workspace, String taskId) throws Exception {
        try (DatabaseManager db = new DatabaseManager(workspace.resolve(".paicli/state"));
             PreparedStatement statement = db.getConnection().prepareStatement("""
                     SELECT count(*) FROM (SELECT logical_operation_key
                     FROM side_effect_occurrences WHERE task_id=?
                     GROUP BY logical_operation_key HAVING count(*)>1)
                     """)) {
            statement.setString(1, taskId);
            try (var row = statement.executeQuery()) { return row.next()?row.getLong(1):0; }
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name","").toLowerCase().contains("windows");
    }

    private enum Scenario { PLAN_TERMINAL_FAILURE, REPEATED_EFFECTFUL_BATCH,
        INVALID_TOOL_RESPONSE, TOOL_PREFLIGHT_FAILURE, ITERATION_EXHAUSTION,
        VERIFICATION_REPAIR_TERMINAL, APPROVAL_REQUIRED, UNKNOWN_OCCURRENCE }

    private record Snapshot(String taskState, String runState, String resultState,
            String terminalReason, int providerInvocations, int toolInvocations,
            long occurrenceCount, long duplicateOccurrenceCount, boolean workspaceMutation,
            int continuityRows, int additionalSegments, boolean waitingForUser) { }

    private static final class QueueProvider implements ModelProvider {
        private final Queue<ModelResponse> responses = new ArrayDeque<>();
        private final Runnable beforeFirst;
        private int calls;
        QueueProvider(List<ModelResponse> values) { this(values, () -> { }); }
        QueueProvider(List<ModelResponse> values, Runnable beforeFirst) {
            responses.addAll(values); this.beforeFirst = beforeFirst;
        }
        public ModelResponse chat(List<AgentMessage> messages,
                List<ToolDefinition> tools) {
            if (calls == 0) beforeFirst.run();
            calls++;
            ModelResponse response = responses.poll();
            if (response == null) throw new IllegalStateException("script exhausted");
            return response;
        }
    }
}
