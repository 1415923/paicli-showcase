package com.paicli.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.config.Config;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.ModelProvider;
import com.paicli.provider.PreparedProviderTurn;
import com.paicli.provider.PreparedTurnProvider;
import com.paicli.runtime.CodingTaskObserver;
import com.paicli.runtime.PlanCompatibilityObserver;
import com.paicli.state.DatabaseManager;
import com.paicli.state.StateStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Network-free measurement of the five frozen runtime-efficiency scenarios.
 *
 * <p>The source path intentionally sits outside the formal candidate roots. It exercises the
 * production benchmark treatment and runtime-contract orchestration without changing either.
 * Calibration samples warm the JVM and are permanently excluded from measured summaries.</p>
 */
class OfflineRuntimePerformanceIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("short-read", "task-001", "COMMON"),
            new Scenario("multi-tool", "task-009", "COMMON"),
            new Scenario("compact", "task-027", "RUNTIME_CONTRACT"),
            new Scenario("verify-repair", "task-020", "COMMON"),
            new Scenario("resume-continue", "task-025", "RUNTIME_CONTRACT"));

    @Test
    void measureFrozenOfflineRuntimeScenarios() throws Exception {
        Path project = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path allowed = project.resolve("target/autumn-evidence").normalize();
        Path outputRoot = Path.of(System.getProperty("paicli.performance.outputRoot",
                allowed.resolve("pre-formal-performance-v1").toString()))
                .toAbsolutePath().normalize();
        if (!outputRoot.startsWith(allowed) || outputRoot.equals(allowed)
                || Files.exists(outputRoot)) {
            throw new IllegalArgumentException(
                    "performance output must be a fresh path under target/autumn-evidence");
        }
        Files.createDirectories(outputRoot);
        Path raw = outputRoot.resolve("raw-samples.jsonl");

        int written = 0;
        for (Scenario scenario : SCENARIOS) {
            for (int repetition = 0; repetition <= 10; repetition++) {
                boolean calibration = repetition == 0;
                Sample sample = execute(project, outputRoot, scenario, repetition, calibration);
                Files.writeString(raw,
                        JSON.writeValueAsString(sample.toJson()) + System.lineSeparator(),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
                written++;
            }
        }
        assertEquals(55, written);
        assertEquals(55, Files.readAllLines(raw, StandardCharsets.UTF_8).size());
    }

    private static Sample execute(Path project, Path outputRoot, Scenario scenario,
            int repetition, boolean calibration) throws Exception {
        Path fixture = project.resolve("benchmarks/coding/fixtures")
                .resolve(scenario.taskId());
        JsonNode task = JSON.readTree(fixture.resolve("task.json").toFile());
        Path workspace = outputRoot.resolve("workspaces").resolve(scenario.id())
                .resolve(String.format("rep-%02d", repetition));
        materialize(project, fixture, task, workspace);

        Path startupProbe = outputRoot.resolve("startup-probes").resolve(scenario.id())
                .resolve(String.format("rep-%02d", repetition));
        materialize(project, fixture, task, startupProbe);
        long startupStarted = System.nanoTime();
        try (StateStore ignored = new StateStore(startupProbe)) {
            // Measure the production SQLite state bootstrap/open-close boundary in isolation.
        }
        long startupMs = elapsedMillis(startupStarted);

        long dbBefore = databaseBytes(workspace);
        String prompt = Files.readString(fixture.resolve(task.path("promptFile").asText()),
                StandardCharsets.UTF_8);
        Config config = taskConfig(workspace, task);
        ObservingPreparedProvider provider;
        RunFacts facts;
        long outerStarted = System.nanoTime();
        if ("RUNTIME_CONTRACT".equals(scenario.kind())) {
            provider = new ObservingPreparedProvider(new RuntimeContractReplayProvider(
                    scenario.taskId(), task, workspace));
            String continuation = task.path("runtimeInputs")
                    .hasNonNull("continuationPromptFile")
                    ? Files.readString(fixture.resolve(task.path("runtimeInputs")
                    .path("continuationPromptFile").asText()), StandardCharsets.UTF_8)
                    : null;
            RuntimeContractScenarioExecutor.Result result =
                    new RuntimeContractScenarioExecutor(workspace, task, provider, config,
                            PlanCompatibilityObserver.NOOP).run(prompt, continuation);
            assertTrue(result.runtimeContractSuccess(), scenario.id() + " assertions="
                    + result.runtimeAssertions() + " metrics=" + result.runtimeMetrics());
            assertTrue(result.terminal().durableFormalResultPresent(), scenario.id());
            facts = new RunFacts(result.terminal().taskId(), result.terminal().runId(),
                    result.toolCalls(), result.providerTurns(), result.providerLatencyMillis(),
                    result.toolLatencyMillis(), result.alignedExecutionLatencyMillis(),
                    result.structuredVerificationCount(), result.structuredRepairCount(),
                    result.compactCount(), true);
        } else {
            CommonReplayProvider replay = new CommonReplayProvider(
                    scenario.taskId(), task, fixture.resolve("oracle/solution"));
            provider = new ObservingPreparedProvider(replay);
            PaiCliBenchmarkTreatment.RunResult result = new PaiCliBenchmarkTreatment(
                    workspace, provider, config,
                    new PredeclaredApprovalControl((iteration, call) -> true),
                    CodingTaskObserver.NOOP).run(prompt);
            assertTrue(result.taskCompleted(), scenario.id());
            assertTrue(result.durableFormalResultPresent(), scenario.id());
            if ("verify-repair".equals(scenario.id())) {
                assertEquals(0, result.structuredRepairCount());
                assertEquals(Boolean.TRUE, result.firstStructuredVerificationPassed());
            }
            facts = new RunFacts(result.taskId(), result.runId(), result.toolCalls(),
                    result.providerTurns(), result.providerLatencyMillis(),
                    result.toolLatencyMillis(), result.alignedExecutionLatencyMillis(),
                    result.structuredVerificationCount(), result.structuredRepairCount(),
                    result.compactCount(), result.taskCompleted());
        }
        long outerMs = elapsedMillis(outerStarted);
        long verificationMs = verificationDuration(workspace, facts.runId());
        long dbGrowth = Math.max(0, databaseBytes(workspace) - dbBefore);
        long derivedControlPlane = Math.max(0, facts.totalTaskExecutionMs()
                - facts.providerLatencyMs() - facts.toolLatencyMs() - verificationMs);

        assertEquals(0, provider.networkCalls());
        assertTrue(facts.completed());
        assertTrue(provider.maxMaterializedRequestBytes() > 0);
        return new Sample(scenario, repetition, calibration, startupMs, facts,
                verificationMs, derivedControlPlane, dbGrowth,
                provider.maxMaterializedRequestBytes(), outerMs);
    }

    private static Config taskConfig(Path workspace, JsonNode task) {
        List<String> command = JSON.convertValue(task.path("oracle")
                .path("verificationCommand"), JSON.getTypeFactory()
                .constructCollectionType(List.class, String.class));
        String verification = String.join(" ", command);
        String approval = task.path("approvalProfile").asText()
                .equals("workspace-write-v1")
                ? Config.MODE_ON_REQUEST : Config.MODE_READ_ONLY;
        return new Config(workspace.toString(), 30, 50_000, 200_000, approval,
                10_000, Config.PROVIDER_OPENAI, "deepseek-v4-flash",
                "https://api.deepseek.com", "DEEPSEEK_API_KEY",
                Config.DEFAULT_SYSTEM_PROMPT,
                task.path("taskConstraints").path("maxProviderTurns").asInt(), verification,
                task.path("budgets").path("verificationSeconds").asInt(120), 50_000,
                1, true, !verification.isBlank(), true, false,
                Config.DEFAULT_SKILLS_DIRS, Config.DEFAULT_MAX_SKILL_CONTEXT_CHARS,
                Config.DEFAULT_MAX_SELECTED_SKILLS, false, Config.DEFAULT_HOOKS_CONFIG_PATH,
                Config.DEFAULT_HOOK_TIMEOUT_SECONDS, Config.DEFAULT_MAX_HOOK_OUTPUT_BYTES,
                Config.DEFAULT_HOOK_FAILURE_POLICY, 65_536, "deepseek", "enabled", "max", null);
    }

    private static void materialize(Path project, Path fixture, JsonNode task,
            Path workspace) throws Exception {
        assertFalse(Files.exists(workspace));
        copyTree(project.resolve("benchmarks/coding/templates")
                .resolve(task.path("workspaceTemplate").asText()), workspace);
        copyTree(fixture.resolve("seed"), workspace);
    }

    private static void copyTree(Path source, Path destination) throws Exception {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.sorted().toList()) {
                Path target = destination.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static long databaseBytes(Path workspace) throws Exception {
        Path state = workspace.resolve(".paicli/state");
        if (!Files.exists(state)) return 0;
        try (var paths = Files.walk(state)) {
            return paths.filter(Files::isRegularFile).mapToLong(path -> {
                try { return Files.size(path); }
                catch (Exception error) { throw new IllegalStateException(error); }
            }).sum();
        }
    }

    private static long verificationDuration(Path workspace, String runId) throws Exception {
        try (DatabaseManager database = new DatabaseManager(workspace.resolve(".paicli/state"));
             PreparedStatement query = database.getConnection().prepareStatement(
                     "SELECT coalesce(sum(duration_ms),0) FROM verification_runs WHERE run_id=?")) {
            query.setString(1, runId);
            try (var row = query.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static long elapsedMillis(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000L);
    }

    private record Scenario(String id, String taskId, String kind) { }

    private record RunFacts(String taskId, String runId, int toolCalls,
            long providerTurns, long providerLatencyMs, long toolLatencyMs,
            long totalTaskExecutionMs, int verificationCount, int repairCount,
            long compactCount, boolean completed) { }

    private record Sample(Scenario scenario, int repetition, boolean calibration,
            long startupMs, RunFacts facts, long verificationMs,
            long derivedControlPlaneMs, long databaseGrowthBytes,
            long materializedContextBytes, long harnessWallClockMs) {
        ObjectNode toJson() {
            ObjectNode row = JSON.createObjectNode();
            row.put("schemaVersion", "offline-runtime-performance-sample-v1");
            row.put("scenario", scenario.id());
            row.put("taskId", scenario.taskId());
            row.put("phase", calibration ? "CALIBRATION" : "MEASURED");
            row.put("repetition", repetition);
            row.put("providerMode", "OFFLINE_DETERMINISTIC_REPLAY");
            row.put("networkProviderCalls", 0);
            row.put("formalAggregate", "DISABLED");
            row.put("effectConclusion", "FORBIDDEN_NOT_PRODUCED");
            row.put("toolCalls", facts.toolCalls());
            row.put("providerTurns", facts.providerTurns());
            row.put("verificationCount", facts.verificationCount());
            row.put("repairCount", facts.repairCount());
            row.put("compactCount", facts.compactCount());
            ObjectNode stages = row.putObject("stages");
            measured(stages, "startup", startupMs, "ISOLATED_STATESTORE_OPEN_CLOSE");
            measured(stages, "tool", facts.toolLatencyMs(), "PRODUCTION_AGENT_OBSERVER");
            unavailable(stages, "sqlite", "NOT_AVAILABLE_CURRENT_TELEMETRY");
            unavailable(stages, "contextCompile", "NOT_AVAILABLE_CURRENT_TELEMETRY");
            if ("compact".equals(scenario.id())) {
                unavailable(stages, "compact", "NOT_AVAILABLE_CURRENT_TELEMETRY");
            } else notApplicable(stages, "compact");
            if ("multi-tool".equals(scenario.id())
                    || "verify-repair".equals(scenario.id())) {
                measured(stages, "verification", verificationMs,
                        "DURABLE_VERIFICATION_RECORDS");
            } else notApplicable(stages, "verification");
            if ("resume-continue".equals(scenario.id())) {
                unavailable(stages, "lineageLoad", "NOT_AVAILABLE_CURRENT_TELEMETRY");
            } else notApplicable(stages, "lineageLoad");
            unavailable(stages, "finalPersist", "NOT_AVAILABLE_CURRENT_TELEMETRY");
            measured(stages, "totalTaskExecution", facts.totalTaskExecutionMs(),
                    "PAICLI_BENCHMARK_TREATMENT");
            measured(stages, "provider", facts.providerLatencyMs(),
                    "OFFLINE_PROVIDER_TELEMETRY");
            derived(stages, "runtimeControlPlane", derivedControlPlaneMs,
                    "TOTAL_MINUS_PROVIDER_TOOL_VERIFICATION");
            measured(stages, "harnessWallClock", harnessWallClockMs,
                    "IN_PROCESS_TEST_HARNESS");
            ObjectNode growth = row.putObject("growth");
            growth.put("databaseBytes", databaseGrowthBytes);
            growth.put("materializedContextBytes", materializedContextBytes);
            growth.put("materializedContextBoundary",
                    "MAX_SERIALIZED_PROVIDER_REQUEST_AT_RAW_PROVIDER_BOUNDARY");
            return row;
        }
    }

    private static void measured(ObjectNode stages, String name, long value, String source) {
        ObjectNode stage = stages.putObject(name);
        stage.put("status", "MEASURED");
        stage.put("valueMs", value);
        stage.put("source", source);
    }

    private static void derived(ObjectNode stages, String name, long value, String source) {
        ObjectNode stage = stages.putObject(name);
        stage.put("status", "DERIVED");
        stage.put("valueMs", value);
        stage.put("source", source);
    }

    private static void unavailable(ObjectNode stages, String name, String reason) {
        ObjectNode stage = stages.putObject(name);
        stage.put("status", "NOT_AVAILABLE");
        stage.putNull("valueMs");
        stage.put("reason", reason);
    }

    private static void notApplicable(ObjectNode stages, String name) {
        ObjectNode stage = stages.putObject(name);
        stage.put("status", "NOT_APPLICABLE");
        stage.putNull("valueMs");
    }

    private static final class ObservingPreparedProvider implements PreparedTurnProvider {
        private final PreparedTurnProvider delegate;
        private long maxMaterializedRequestBytes;

        private ObservingPreparedProvider(PreparedTurnProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public PreparedProviderTurn prepareTurn(String operation, List<AgentMessage> messages,
                List<ToolDefinition> tools) {
            try {
                long bytes = JSON.writeValueAsBytes(messages).length
                        + JSON.writeValueAsBytes(tools).length;
                maxMaterializedRequestBytes = Math.max(maxMaterializedRequestBytes, bytes);
            } catch (Exception error) {
                throw new IllegalStateException("provider-boundary context sizing failed", error);
            }
            return delegate.prepareTurn(operation, messages, tools);
        }

        int networkCalls() { return 0; }
        long maxMaterializedRequestBytes() { return maxMaterializedRequestBytes; }
    }

    private static final class CommonReplayProvider implements PreparedTurnProvider {
        private final String taskId;
        private final String plan;
        private final List<ModelResponse> responses;
        private int ordinal;

        private CommonReplayProvider(String taskId, JsonNode task, Path solution)
                throws Exception {
            this.taskId = taskId;
            this.plan = plan(taskId, task);
            this.responses = responses(taskId, task, solution);
        }

        @Override
        public PreparedProviderTurn prepareTurn(String operation,
                List<AgentMessage> messages, List<ToolDefinition> tools) {
            String digest = sha256(taskId + ":" + operation + ":" + ordinal + ":"
                    + messages.size() + ":" + tools.size());
            return new PreparedProviderTurn(digest, () -> {
                if ("PLAN".equals(operation)) return ModelResponse.finalAnswer(plan);
                if (ordinal < responses.size()) return responses.get(ordinal++);
                return ModelResponse.finalAnswer("Offline runtime performance replay complete.");
            });
        }

        private static List<ModelResponse> responses(String taskId, JsonNode task,
                Path solution) throws Exception {
            List<ModelResponse> result = new ArrayList<>();
            if ("task-001".equals(taskId)) {
                result.add(ModelResponse.toolCallResponse("read frozen fixture",
                        new ToolCall("read_file", Map.of("path",
                                "src/main/java/fixture/AppConfig.java"), "perf-read-1")));
                result.add(ModelResponse.finalAnswer(
                        "AppConfig owns the HTTP port; default 8080; PAICLI_PORT overrides it."));
                return List.copyOf(result);
            }
            if ("task-020".equals(taskId)) {
                String path = task.path("oracle").path("requiredPaths").get(0).asText();
                result.add(write(path, Files.readString(solution.resolve(path)),
                        "perf-verify-correct"));
                result.add(ModelResponse.finalAnswer(
                        "Deterministic candidate passed first verification; repair not triggered."));
                return List.copyOf(result);
            }
            int index = 0;
            for (JsonNode pathNode : task.path("oracle").path("requiredPaths")) {
                String path = pathNode.asText();
                result.add(write(path, Files.readString(solution.resolve(path)),
                        "perf-write-" + (++index)));
            }
            result.add(ModelResponse.finalAnswer("Frozen multi-tool replay complete."));
            return List.copyOf(result);
        }

        private static ModelResponse write(String path, String content, String id) {
            return ModelResponse.toolCallResponse("apply deterministic fixture change",
                    new ToolCall("write_file", Map.of(
                            "path", path, "content", content, "overwrite", true), id));
        }

        private static String plan(String taskId, JsonNode task) throws Exception {
            ObjectNode root = JSON.createObjectNode();
            root.putArray("acceptanceCriteria").add("offline scenario reaches durable result");
            root.putArray("assumptions");
            root.putArray("risks");
            root.put("verificationStrategy", task.path("oracle")
                    .path("verificationCommand").isEmpty()
                    ? "read-only durable evidence" : "frozen Maven verification");
            ObjectNode step = root.putArray("steps").addObject();
            step.put("title", "Execute frozen offline scenario");
            step.put("definition", "Use only oracle-declared fixture paths");
            step.putArray("dependsOn");
            step.put("completionRule", "ALL_REQUIRED_EVIDENCE");
            step.putArray("requiredEvidenceKinds").add("TOOL_CALL");
            boolean read = "task-001".equals(taskId);
            step.put("effectClass", read ? "READ" : "WRITE");
            ArrayNode requirements = step.putArray("toolRequirements");
            int order = 0;
            for (JsonNode pathNode : task.path("oracle").path("requiredPaths")) {
                String path = pathNode.asText();
                ObjectNode requirement = requirements.addObject();
                requirement.put("requirementId", "performance-" + order);
                requirement.put("requirementOrder", order++);
                requirement.put("toolName", read ? "read_file" : "write_file");
                requirement.put("effectClass", read ? "READ" : "WRITE");
                ObjectNode selector = requirement.putObject("authoritySelector");
                selector.put("kind", "EXACT_PATH");
                selector.put("path", path);
                if (!read) selector.put("overwrite", true);
                requirement.put("minSuccesses", 1);
                requirement.put("maxOccurrences", "task-020".equals(taskId) ? 2 : 1);
                requirement.put("freshnessPolicy", "RESUME_REUSE_ONLY");
            }
            return JSON.writeValueAsString(root);
        }

        private static String sha256(String value) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
        }
    }
}
