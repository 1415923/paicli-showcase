package com.paicli.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.Config;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.ModelProvider;
import com.paicli.safety.ApprovalPolicy;
import com.paicli.safety.SafetyPolicy;
import com.paicli.state.CodingRunStore;
import com.paicli.state.StateStore;
import com.paicli.state.SubagentRunStore;
import com.paicli.subagent.verdict.Verdict;
import com.paicli.subagent.verdict.VerdictExtractionMode;
import com.paicli.subagent.verdict.VerdictExtractionRequest;
import com.paicli.subagent.verdict.VerdictExtractionStrategy;
import com.paicli.subagent.verdict.VerdictExtractor;
import com.paicli.tool.GrepTool;
import com.paicli.tool.ReadFileTool;
import com.paicli.tool.Tool;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SubagentRunnerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TASK_ID = "task-subagent";
    private static final String RUN_ID = "run-subagent";

    @TempDir
    Path workspace;

    private StateStore stateStore;

    @BeforeEach
    void setUp() throws Exception {
        stateStore = new StateStore(workspace);
        stateStore.tasks.createTask(TASK_ID, "review code", "mock", null, "read-only", 10);
        stateStore.codingRuns.create(
                RUN_ID, TASK_ID, CodingRunStore.KIND_INITIAL, null, 1, 0);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (stateStore != null) {
            stateStore.close();
        }
    }

    @Test
    void scoutUsesReadFileAndGrepThenPersistsVerdict() throws Exception {
        Files.writeString(workspace.resolve("Sample.java"), "class Sample { int value = 1; }");
        ToolRegistry parent = new ToolRegistry();
        SafetyPolicy safety = new SafetyPolicy(workspace);
        parent.register(new ReadFileTool(safety));
        parent.register(new GrepTool(safety));
        SequenceProvider provider = new SequenceProvider(
                ModelResponse.toolCallResponse("read", new ToolCall(
                        "read_file", Map.of("path", "Sample.java"))),
                ModelResponse.toolCallResponse("search", new ToolCall(
                        "grep", Map.of("pattern", "value", "path", "."))),
                ModelResponse.finalAnswer(validMarker("read-only review complete")));

        SubagentRunnerResult result = runner(parent, Config.MODE_READ_ONLY).run(request(
                "read-grep", definition(SubagentKind.SCOUT,
                        List.of("read_file", "grep"), 3, 30, 4_096), provider));

        assertEquals(SubagentRunStore.STATUS_SUCCEEDED, result.status());
        assertEquals(Verdict.PASS, result.verdict().verdict());
        assertEquals(3, provider.calls());
        assertEquals(List.of("grep", "read_file"), provider.firstToolNames());
        assertEquals(1, stateStore.structuredVerdicts
                .findVerdictsByRun(result.subagentRunId()).size());
        assertNoRunningRuns();
    }

    @Test
    void boundedProviderPreservesAndExecutesEveryAllowedReadOnlyBatchCall() throws Exception {
        CountingTool read=new CountingTool("read_file");
        ToolRegistry parent=new ToolRegistry();parent.register(read);
        SequenceProvider provider=new SequenceProvider(
                ModelResponse.toolCallBatchResponse("read both",List.of(
                        new ToolCall("read_file",Map.of("path","a"),"call-a"),
                        new ToolCall("read_file",Map.of("path","b"),"call-b")),
                        com.paicli.provider.ProviderResponseEnvelope.inspect(
                                List.of("call-a","call-b"),false),false),
                ModelResponse.finalAnswer(validMarker("batch review complete")));

        SubagentRunnerResult result=runner(parent,Config.MODE_READ_ONLY).run(request(
                "batch",definition(SubagentKind.SCOUT,List.of("read_file"),3,30,4_096),
                provider));

        assertEquals(SubagentRunStore.STATUS_SUCCEEDED,result.status());
        assertEquals(2,read.executions());
        assertEquals(2,provider.calls());
        assertNoRunningRuns();
    }

    @Test
    void malformedVerdictIsPersistedAsInconclusiveAndRunSucceeds() throws Exception {
        SequenceProvider provider = new SequenceProvider(ModelResponse.finalAnswer("not a verdict"));

        SubagentRunnerResult result = runner(parentReadOnly(), Config.MODE_READ_ONLY).run(request(
                "malformed", definition(SubagentKind.REVIEWER,
                        List.of("read_file"), 2, 30, 1_024), provider));

        assertEquals(SubagentRunStore.STATUS_SUCCEEDED, result.status());
        assertEquals(Verdict.INCONCLUSIVE, result.verdict().verdict());
        assertFalse(result.verdict().parsed());
        var stored = stateStore.structuredVerdicts
                .findVerdictsByRun(result.subagentRunId()).getFirst();
        assertEquals("INCONCLUSIVE", stored.verdict());
        assertNotEquals("PASS", stored.verdict());
        assertNoRunningRuns();
    }

    @Test
    void forbiddenWriteAndRunCommandRequestsFailBeforeToolExecution() throws Exception {
        for (String toolName : List.of("write_file", "run_command")) {
            CountingTool forbidden = new CountingTool(toolName);
            ToolRegistry parent = parentReadOnly();
            parent.register(forbidden);
            SequenceProvider provider = new SequenceProvider(ModelResponse.toolCallResponse(
                    "attempt forbidden tool", new ToolCall(toolName, Map.of())));
            SubagentDefinition definition = toolName.equals("run_command")
                    ? definition(SubagentKind.VERIFIER,
                            List.of("read_file"), 2, 30, 1_024)
                    : definition(SubagentKind.REVIEWER,
                            List.of("read_file"), 2, 30, 1_024);

            SubagentRunnerResult result = runner(parent, Config.MODE_ON_REQUEST).run(request(
                    "forbidden-" + toolName, definition, provider));

            assertEquals(SubagentRunStore.STATUS_FAILED, result.status());
            assertEquals(0, forbidden.executions());
            assertTrue(result.message().contains("forbidden subagent tool"));
        }
        assertNoRunningRuns();
    }

    @Test
    void unknownToolRequestFailsClosed() throws Exception {
        SequenceProvider provider = new SequenceProvider(ModelResponse.toolCallResponse(
                "unknown", new ToolCall("unknown_tool", Map.of())));

        SubagentRunnerResult result = runner(parentReadOnly(), Config.MODE_READ_ONLY).run(request(
                "unknown", definition(SubagentKind.CUSTOM,
                        List.of("read_file"), 2, 30, 1_024), provider));

        assertEquals(SubagentRunStore.STATUS_FAILED, result.status());
        assertTrue(result.message().contains("unknown_tool"));
        assertNoRunningRuns();
    }

    @Test
    void identicalReadLoopStopsWithoutReexecutingOrFabricatingVerdict() throws Exception {
        CountingTool read = new CountingTool("read_file");
        ToolRegistry parent = new ToolRegistry();
        parent.register(read);
        ModelProvider provider = (messages, tools) -> ModelResponse.toolCallResponse(
                "keep reading", new ToolCall("read_file", Map.of("path", "x")));

        SubagentRunnerResult result = runner(parent, Config.MODE_READ_ONLY).run(request(
                "max-turns", definition(SubagentKind.SCOUT,
                        List.of("read_file"), 2, 30, 1_024), provider));

        assertEquals(SubagentRunStore.STATUS_FAILED, result.status());
        assertEquals(1, read.executions(),"identical read evidence must be reused");
        assertNull(result.verdict());
        assertNull(result.verdictId());
        assertTrue(stateStore.structuredVerdicts
                .findVerdictsByRun(result.subagentRunId()).isEmpty());
        assertNotNull(result.message());
        assertNoRunningRuns();
    }

    @Test
    void timeoutWritesTimedOutAndLeavesNoRunningRecord() throws Exception {
        ModelProvider provider = (messages, tools) -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("provider interrupted");
            }
            return ModelResponse.finalAnswer(validMarker("too late"));
        };

        SubagentRunnerResult result = runner(parentReadOnly(), Config.MODE_READ_ONLY).run(request(
                "timeout", definition(SubagentKind.REVIEWER,
                        List.of("read_file"), 2, 1, 1_024), provider));

        assertEquals(SubagentRunStore.STATUS_TIMED_OUT, result.status());
        assertEquals(SubagentRunStore.STATUS_TIMED_OUT, stateStore.subagentRuns
                .findSubagentRunById(result.subagentRunId()).orElseThrow().status());
        assertNoRunningRuns();
    }

    @Test
    void providerExceptionWritesFailed() throws Exception {
        ModelProvider provider = (messages, tools) -> {
            throw new IllegalStateException("provider unavailable");
        };

        SubagentRunnerResult result = runner(parentReadOnly(), Config.MODE_READ_ONLY).run(request(
                "provider-error", definition(SubagentKind.REVIEWER,
                        List.of("read_file"), 2, 30, 1_024), provider));

        assertEquals(SubagentRunStore.STATUS_FAILED, result.status());
        assertTrue(result.message().contains("provider unavailable"));
        assertNoRunningRuns();
    }

    @Test
    void extractionExceptionWritesFailed() throws Exception {
        VerdictExtractionStrategy throwing = new VerdictExtractionStrategy() {
            @Override
            public String strategyName() {
                return "throwing-test";
            }

            @Override
            public boolean supports(VerdictExtractionRequest request) {
                throw new IllegalStateException("extractor unavailable");
            }

            @Override
            public com.paicli.subagent.verdict.VerdictExtractionResult extract(
                    VerdictExtractionRequest request) {
                throw new AssertionError("must not reach extract");
            }
        };
        SubagentRunner runner = runner(
                parentReadOnly(), Config.MODE_READ_ONLY,
                new VerdictExtractor(List.of(throwing)));

        SubagentRunnerResult result = runner.run(request(
                "extract-error", definition(SubagentKind.REVIEWER,
                        List.of("read_file"), 2, 30, 1_024),
                new SequenceProvider(ModelResponse.finalAnswer(validMarker("review")))));

        assertEquals(SubagentRunStore.STATUS_FAILED, result.status());
        assertTrue(result.message().contains("extractor unavailable"));
        assertTrue(stateStore.structuredVerdicts
                .findVerdictsByRun(result.subagentRunId()).isEmpty());
        assertNoRunningRuns();
    }

    @Test
    void outputIsTruncatedBeforeExtractionAndRawTextIsNotPersisted() throws Exception {
        String promptMarker = "PROMPT_SHOULD_NOT_BE_STORED_9f2c";
        String rawTail = "RAW_TAIL_SHOULD_NOT_BE_STORED_7a1d";
        String output = validMarker("bounded summary") + "\n" + rawTail.repeat(100);
        SequenceProvider provider = new SequenceProvider(ModelResponse.finalAnswer(output));
        SubagentRunRequest request = request(
                "bounded-output", definition(SubagentKind.REVIEWER,
                        List.of("read_file"), 2, 30, 256), provider, promptMarker);

        SubagentRunnerResult result = runner(
                parentReadOnly(), Config.MODE_READ_ONLY).run(request);

        assertEquals(SubagentRunStore.STATUS_SUCCEEDED, result.status());
        assertTrue(result.outputTruncated());
        var run = stateStore.subagentRuns
                .findSubagentRunById(result.subagentRunId()).orElseThrow();
        var verdict = stateStore.structuredVerdicts
                .findVerdictById(result.verdictId()).orElseThrow();
        assertNotNull(run.inputHash());
        assertNotNull(run.outputHash());
        assertFalse(run.toString().contains(promptMarker));
        assertFalse(run.toString().contains(rawTail));
        assertFalse(verdict.toString().contains(rawTail));
        assertEquals(outputHash(output), run.outputHash());
        assertTrue(stateStore.leadDecisions
                .findDecisionsByRun(result.subagentRunId()).isEmpty());
    }

    @Test
    void terminalOccurrenceIsNotRepeated() throws Exception {
        SequenceProvider provider = new SequenceProvider(
                ModelResponse.finalAnswer(validMarker("first run")));
        SubagentRunRequest request = request(
                "same-occurrence", definition(SubagentKind.REVIEWER,
                        List.of("read_file"), 2, 30, 1_024), provider);
        SubagentRunner runner = runner(parentReadOnly(), Config.MODE_READ_ONLY);

        SubagentRunnerResult first = runner.run(request);
        SubagentRunnerResult second = runner.run(request);

        assertEquals(SubagentRunStore.STATUS_SUCCEEDED, first.status());
        assertTrue(second.skippedExisting());
        assertEquals("SKIPPED_EXISTING", second.status());
        assertEquals(1, provider.calls());
        assertEquals(1, stateStore.subagentRuns
                .findRunsByOccurrence(RUN_ID, "REVIEW", "same-occurrence").size());
    }

    @Test
    void runningResidueIsInterruptedBeforeNewAttempt() throws Exception {
        stateStore.subagentRuns.createSubagentRun(
                "stale-subagent", TASK_ID, RUN_ID, "reviewer-test", "REVIEWER",
                "REVIEW", "residue", 1, "mock", true, "tools=[]", "input-hash");
        SequenceProvider provider = new SequenceProvider(
                ModelResponse.finalAnswer(validMarker("new attempt")));

        SubagentRunnerResult result = runner(parentReadOnly(), Config.MODE_READ_ONLY).run(request(
                "residue", definition(SubagentKind.REVIEWER,
                        List.of("read_file"), 2, 30, 1_024), provider));

        assertEquals(2, result.attemptNumber());
        assertEquals(SubagentRunStore.STATUS_INTERRUPTED, stateStore.subagentRuns
                .findSubagentRunById("stale-subagent").orElseThrow().status());
        assertEquals(SubagentRunStore.STATUS_SUCCEEDED, stateStore.subagentRuns
                .findSubagentRunById(result.subagentRunId()).orElseThrow().status());
        assertNoRunningRuns();
    }

    private SubagentRunner runner(ToolRegistry tools, String approvalMode) {
        return runner(tools, approvalMode, new VerdictExtractor());
    }

    private SubagentRunner runner(
            ToolRegistry tools,
            String approvalMode,
            VerdictExtractor extractor) {
        Config config = new Config(".", 30, 50_000, 200_000, approvalMode, 10_000);
        return new SubagentRunner(
                stateStore, tools, new ApprovalPolicy(config, TASK_ID), extractor);
    }

    private SubagentRunRequest request(
            String occurrence,
            SubagentDefinition definition,
            ModelProvider provider) {
        return request(occurrence, definition, provider, "review the task");
    }

    private SubagentRunRequest request(
            String occurrence,
            SubagentDefinition definition,
            ModelProvider provider,
            String taskInput) {
        return new SubagentRunRequest(
                TASK_ID, RUN_ID, "REVIEW", occurrence, 1,
                definition, taskInput, provider, "mock", VerdictExtractionMode.AUTO);
    }

    private SubagentDefinition definition(
            SubagentKind kind,
            List<String> tools,
            int maxTurns,
            int timeout,
            int maxOutput) {
        return new SubagentDefinition(
                kind.name().toLowerCase() + "-test",
                kind,
                "Inspect only the supplied workspace evidence.",
                new SubagentConfig(
                        tools, SubagentPermissionMode.READ_ONLY,
                        maxTurns, timeout, maxOutput, true));
    }

    private SubagentDefinition verifierDefinitionWithMarker(
            int maxTurns,
            int timeout,
            int maxOutput) {
        return new SubagentDefinition(
                "verifier-test",
                SubagentKind.VERIFIER,
                "Inspect verification evidence without executing commands.",
                new SubagentConfig(
                        List.of("read_file"),
                        Set.of(SubagentCapability.CONFIGURED_VERIFICATION),
                        SubagentPermissionMode.READ_ONLY,
                        maxTurns, timeout, maxOutput, true));
    }

    private ToolRegistry parentReadOnly() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new CountingTool("read_file"));
        return registry;
    }

    private void assertNoRunningRuns() throws Exception {
        assertTrue(stateStore.subagentRuns.findRunsByTask(TASK_ID).stream()
                .noneMatch(run -> SubagentRunStore.STATUS_RUNNING.equals(run.status())));
    }

    private static String validMarker(String summary) {
        return """
                [VERDICT: PASS]
                [SEVERITY: NONE]
                [CONFIDENCE: 0.9]
                [EVIDENCE: REVIEWER_MODEL:Sample.java:1:bounded review evidence]
                [REQUIRED_ACTION: NONE]
                [SUMMARY: %s]
                """.formatted(summary);
    }

    private static String outputHash(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static final class SequenceProvider implements ModelProvider {
        private final List<ModelResponse> responses;
        private final AtomicInteger index = new AtomicInteger();
        private volatile List<String> firstToolNames = List.of();

        private SequenceProvider(ModelResponse... responses) {
            this.responses = new ArrayList<>(Arrays.asList(responses));
        }

        @Override
        public ModelResponse chat(List<AgentMessage> messages, List<ToolDefinition> tools) {
            if (index.get() == 0) {
                firstToolNames = tools.stream().map(ToolDefinition::name).toList();
            }
            int current = index.getAndIncrement();
            if (current >= responses.size()) {
                throw new IllegalStateException("provider called too many times");
            }
            return responses.get(current);
        }

        private int calls() {
            return index.get();
        }

        private List<String> firstToolNames() {
            return firstToolNames;
        }
    }

    private static final class CountingTool implements Tool {
        private final String name;
        private final AtomicInteger executions = new AtomicInteger();

        private CountingTool(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "counting test tool";
        }

        @Override
        public ToolDefinition toDefinition() {
            return new ToolDefinition(name, description(), MAPPER.createObjectNode());
        }

        @Override
        public ToolResult execute(ToolCall call) {
            executions.incrementAndGet();
            return ToolResult.success("ok");
        }

        private int executions() {
            return executions.get();
        }
    }
}
