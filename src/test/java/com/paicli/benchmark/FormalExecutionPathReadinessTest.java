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
import com.paicli.runtime.CodingTaskObserver;
import com.paicli.state.DatabaseManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Provider-disabled proof of the PaiCLI benchmark execution path on frozen fixtures. */
class FormalExecutionPathReadinessTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> TASKS = List.of("task-005", "task-016", "task-009");

    @TempDir Path temporaryRoot;

    @Test
    void commonPairedFixturesReachPlanOccurrenceMutationAndOracleInputs() throws Exception {
        Path project = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path evidenceRoot = evidenceRoot(project);
        List<Map<String, Object>> results = new ArrayList<>();

        for (String taskId : TASKS) {
            Path fixture = project.resolve("benchmarks/coding/fixtures").resolve(taskId);
            JsonNode task = JSON.readTree(fixture.resolve("task.json").toFile());
            assertEquals("COMMON_PAIRED", task.path("cohort").asText());
            Path workspace = evidenceRoot.resolve(taskId).resolve("workspace");
            copyTree(project.resolve("benchmarks/coding/templates/java21-maven-v1"), workspace);
            copyTree(fixture.resolve("seed"), workspace);
            List<Map<String, Object>> initialManifest = manifest(workspace);

            ReplayProvider provider = new ReplayProvider(taskId, task,
                    fixture.resolve("oracle/solution"));
            Config config = new Config(workspace.toString(), 30, 50_000, 200_000,
                    Config.MODE_ON_REQUEST, 10_000, Config.PROVIDER_MOCK, "offline-replay",
                    "", "", "offline formal readiness", 20,
                    "mvn -q -o test", 120, 50_000, 1,
                    true, true, false);
            var run = new PaiCliBenchmarkTreatment(workspace, provider, config,
                    new PredeclaredApprovalControl((iteration, call) -> true),
                    CodingTaskObserver.NOOP).run(Files.readString(
                            fixture.resolve(task.path("promptFile").asText()),
                            StandardCharsets.UTF_8));

            OccurrenceFacts occurrences = occurrenceFacts(workspace, run.taskId());
            int expectedWrites = task.path("oracle").path("requiredPaths").size();
            List<String> changed = changedPaths(initialManifest, manifest(workspace));
            assertTrue(run.taskCompleted(), taskId + " Task did not complete");
            assertEquals("COMPLETED", run.runPhase());
            assertTrue(run.durableFormalResultPresent());
            assertTrue(run.toolCalls() >= expectedWrites);
            assertTrue(occurrences.total() >= expectedWrites);
            assertEquals(expectedWrites, occurrences.completedKnownSuccessToolWrites());
            assertFalse(changed.isEmpty());
            assertTrue(run.structuredVerificationCount() >= 1);
            assertEquals(Boolean.TRUE, run.firstStructuredVerificationPassed());
            assertEquals(1, provider.planResponses());
            assertEquals(expectedWrites, provider.writeCalls());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("taskId", taskId);
            result.put("category", task.path("category").asText());
            result.put("cohort", task.path("cohort").asText());
            result.put("providerMode", "OFFLINE_DETERMINISTIC_REPLAY");
            result.put("planResponses", provider.planResponses());
            result.put("writeToolCallsIssued", provider.writeCalls());
            result.put("durableToolCalls", run.toolCalls());
            result.put("durableToolOccurrences", occurrences.total());
            result.put("durableWriteToolOccurrences",
                    occurrences.completedKnownSuccessToolWrites());
            result.put("taskState", run.taskCompleted() ? "COMPLETED" : "FAILED");
            result.put("runState", run.runPhase());
            result.put("durableFormalResult", run.durableFormalResultPresent());
            result.put("formalResult", run.durableFormalResult());
            result.put("structuredVerificationCount", run.structuredVerificationCount());
            result.put("structuredVerificationPass", run.firstStructuredVerificationPassed());
            result.put("duplicateOccurrenceIdentities", run.duplicateOccurrenceIdentities());
            result.put("changedPaths", changed);
            result.put("initialManifest", initialManifest);
            result.put("workspace", (workspace.startsWith(project)
                    ? project.relativize(workspace) : workspace).toString()
                    .replace('\\', '/'));
            results.add(result);
        }

        if (System.getProperty("paicli.formalReadiness.outputRoot") != null) {
            Path output = evidenceRoot.resolve("driver-results.json");
            assertFalse(Files.exists(output), "readiness evidence is append-only");
            ObjectNode root = JSON.createObjectNode();
            root.put("schemaVersion", "formal-execution-path-readiness-driver-v1");
            root.put("providerCalls", 0);
            root.put("formalAggregate", "DISABLED");
            root.put("effectConclusion", "FORBIDDEN_NOT_PRODUCED");
            root.set("runs", JSON.valueToTree(results));
            Files.createDirectories(output.getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), root);
        }
    }

    private Path evidenceRoot(Path project) throws Exception {
        String configured = System.getProperty("paicli.formalReadiness.outputRoot");
        if (configured == null) return temporaryRoot;
        Path value = Path.of(configured).toAbsolutePath().normalize();
        Path allowed = project.resolve("target/autumn-evidence").normalize();
        if (!value.startsWith(allowed) || value.equals(allowed) || Files.exists(value)) {
            throw new IllegalArgumentException("readiness output must be a fresh target evidence path");
        }
        Files.createDirectories(value);
        return value;
    }

    private static OccurrenceFacts occurrenceFacts(Path workspace, String taskId)
            throws Exception {
        try (DatabaseManager database = new DatabaseManager(workspace.resolve(".paicli/state"));
             PreparedStatement query = database.getConnection().prepareStatement(
                     "SELECT count(*), sum(CASE WHEN operation_type='TOOL' "
                             + "AND target='write_file' AND status='COMPLETED' "
                             + "AND external_outcome='KNOWN_SUCCESS' THEN 1 ELSE 0 END) "
                             + "FROM side_effect_occurrences WHERE task_id=?")) {
            query.setString(1, taskId);
            try (var row = query.executeQuery()) {
                row.next();
                return new OccurrenceFacts(row.getLong(1), row.getLong(2));
            }
        }
    }

    private record OccurrenceFacts(long total, long completedKnownSuccessToolWrites) { }

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

    private static List<Map<String, Object>> manifest(Path workspace) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        try (var paths = Files.walk(workspace)) {
            for (Path path : paths.filter(Files::isRegularFile).sorted().toList()) {
                Path relative = workspace.relativize(path);
                if (relative.startsWith(".paicli") || relative.startsWith("target")) continue;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("path", relative.toString().replace('\\', '/'));
                entry.put("sizeBytes", Files.size(path));
                entry.put("sha256", HexFormat.of().formatHex(MessageDigest
                        .getInstance("SHA-256").digest(Files.readAllBytes(path))));
                result.add(entry);
            }
        }
        return result;
    }

    private static List<String> changedPaths(List<Map<String, Object>> before,
            List<Map<String, Object>> after) {
        Map<String, Object> left = new LinkedHashMap<>();
        Map<String, Object> right = new LinkedHashMap<>();
        before.forEach(entry -> left.put((String) entry.get("path"), entry.get("sha256")));
        after.forEach(entry -> right.put((String) entry.get("path"), entry.get("sha256")));
        return java.util.stream.Stream.concat(left.keySet().stream(), right.keySet().stream())
                .distinct().filter(path -> !java.util.Objects.equals(left.get(path), right.get(path)))
                .sorted().toList();
    }

    private static final class ReplayProvider implements ModelProvider {
        private final String taskId;
        private final String plan;
        private final ArrayDeque<ToolCall> writes = new ArrayDeque<>();
        private int planResponses;
        private int writeCalls;

        private ReplayProvider(String taskId, JsonNode task, Path solution) throws Exception {
            this.taskId = taskId;
            ArrayNode requirements = JSON.createArrayNode();
            int order = 0;
            for (JsonNode path : task.path("oracle").path("requiredPaths")) {
                String relative = path.asText();
                ObjectNode requirement = requirements.addObject();
                requirement.put("requirementId", "write-" + (++order));
                requirement.put("requirementOrder", order - 1);
                requirement.put("toolName", "write_file");
                requirement.put("effectClass", "WRITE");
                requirement.putObject("authoritySelector").put("kind", "EXACT_PATH")
                        .put("path", relative).put("overwrite", true);
                requirement.put("minSuccesses", 1);
                requirement.put("maxOccurrences", 1);
                requirement.put("freshnessPolicy", "RESUME_REUSE_ONLY");
                writes.add(new ToolCall("write_file", Map.of(
                        "path", relative,
                        "overwrite", true,
                        "content", Files.readString(solution.resolve(relative),
                                StandardCharsets.UTF_8)),
                        "offline-" + taskId + "-" + order));
            }
            ObjectNode root = JSON.createObjectNode();
            root.putArray("acceptanceCriteria").add("frozen external oracle passes");
            root.putArray("assumptions");
            root.putArray("risks");
            root.put("verificationStrategy", "run frozen Maven oracle");
            ObjectNode step = root.putArray("steps").addObject();
            step.put("title", "Apply frozen fixture change");
            step.put("definition", "Mutate only the oracle-declared production paths");
            step.putArray("dependsOn");
            step.put("completionRule", "ALL_REQUIRED_EVIDENCE");
            step.putArray("requiredEvidenceKinds").add("TOOL_CALL");
            step.put("effectClass", "WRITE");
            step.set("toolRequirements", requirements);
            plan = JSON.writeValueAsString(root);
        }

        @Override
        public ModelResponse chat(List<AgentMessage> messages, List<ToolDefinition> tools) {
            return chatForOperation("CHAT", messages, tools);
        }

        @Override
        public ModelResponse chatForOperation(String operation, List<AgentMessage> messages,
                List<ToolDefinition> tools) {
            if ("PLAN".equals(operation)) {
                planResponses++;
                return ModelResponse.finalAnswer(plan);
            }
            if (!writes.isEmpty()) {
                writeCalls++;
                return ModelResponse.toolCallResponse("offline readiness write", writes.remove());
            }
            return ModelResponse.finalAnswer(
                    "Offline readiness completed for " + taskId + ".");
        }

        private int planResponses() { return planResponses; }
        private int writeCalls() { return writeCalls; }
    }
}
