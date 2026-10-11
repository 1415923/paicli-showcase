package com.paicli.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.agent.AgentLoopControl;
import com.paicli.config.Config;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.ModelProvider;
import com.paicli.runtime.CodingTaskObserver;
import com.paicli.runtime.TaskContinuityPolicy;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Provider-disabled launch-shape control over the production PaiCLI benchmark composition. */
class ExternalJavaValidationProviderDisabledLaunchIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PLAN = """
            {"acceptanceCriteria":["candidate-visible task was inspected"],
             "assumptions":[],"risks":[],"verificationStrategy":"official evaluator is authoritative",
             "steps":[{"title":"Inspect control marker","definition":"Read launch-control.txt",
             "dependsOn":[],"completionRule":"ALL_REQUIRED_EVIDENCE",
             "requiredEvidenceKinds":["TOOL_CALL"],"effectClass":"READ",
             "toolRequirements":[{"requirementId":"read-task","requirementOrder":0,
             "toolName":"read_file","effectClass":"READ",
             "authoritySelector":{"kind":"EXACT_PATH","path":"launch-control.txt"},
             "minSuccesses":1,"maxOccurrences":1,"freshnessPolicy":"RESUME_REUSE_ONLY"}]}]}
            """;

    @Test
    void frozenTenInstanceLaunchShapeUsesProductionRuntimeWithNoExternalProvider() throws Exception {
        Path project = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path inputRoot = requiredPath("paicli.external.candidateInputRoot");
        Path workspaceRoot = requiredPath("paicli.external.controlWorkspaceRoot");
        Path output = requiredPath("paicli.external.productionControlOutput");
        Path allowedInputs = project.resolve("docs/evidence/benchmark/external-java-validation")
                .normalize();
        Path allowedWorkspaces = project.resolve("target/external-java-validation-control")
                .normalize();
        Path allowedOutput = allowedInputs.resolve(
                "provider-disabled-launch-rehearsal-final-v1/production-candidate-control.json");
        assertTrue(inputRoot.startsWith(allowedInputs), "candidate inputs must be frozen evidence");
        assertTrue(workspaceRoot.startsWith(allowedWorkspaces), "control workspaces must stay in target");
        assertEquals(allowedOutput, output, "control output path drift");
        assertFalse(Files.exists(workspaceRoot), "control workspace root must be fresh");
        assertFalse(Files.exists(output), "production control evidence is append-only");

        JsonNode subset = JSON.readTree(project.resolve(
                "benchmarks/external/swebench-multilingual-java-v1/fixed-subset-v1.json").toFile());
        assertEquals(10, subset.path("instances").size());
        Files.createDirectories(workspaceRoot);
        List<Map<String, Object>> runs = new ArrayList<>();
        TaskContinuityPolicy policy = new TaskContinuityPolicy(
                true, 3, 3, 40, 80, 600_000, 64_000, 1_200_000);

        for (JsonNode frozen : subset.path("instances")) {
            String instanceId = frozen.path("instanceId").asText();
            Path input = inputRoot.resolve(instanceId + ".json");
            JsonNode candidate = JSON.readTree(input.toFile());
            assertEquals(instanceId, candidate.path("instance_id").asText());
            assertFalse(candidate.has("patch"));
            assertFalse(candidate.has("test_patch"));
            Path workspace = workspaceRoot.resolve(instanceId);
            Files.createDirectories(workspace);
            Files.copy(input, workspace.resolve("candidate-task.json"));
            Files.writeString(workspace.resolve("launch-control.txt"),
                    "provider-disabled production-path control\n", StandardCharsets.UTF_8);

            DeterministicControlProvider provider = new DeterministicControlProvider(instanceId);
            Config config = config(workspace);
            var options = new PaiCliBenchmarkTreatment.Options(null,
                    (safety, snapshots, managedTask, controlPaths) ->
                            new com.paicli.tool.WriteFileTool(
                                    safety, snapshots, managedTask, null, controlPaths),
                    ignored -> { }, 1, policy, true);
            String task = "Solve the following public Java software-engineering task using the "
                    + "shared PaiCLI policy.\n\n" + candidate.path("problem_statement").asText();
            var run = new PaiCliBenchmarkTreatment(workspace, provider, config,
                    AgentLoopControl.NONE, CodingTaskObserver.NOOP,
                    com.paicli.runtime.PlanCompatibilityObserver.NOOP, options).run(task);

            assertTrue(run.taskCompleted(), instanceId);
            assertEquals("COMPLETED", run.runPhase(), instanceId);
            assertTrue(run.durableFormalResultPresent(), instanceId);
            assertEquals(1, run.toolCalls(), instanceId);
            assertEquals(0, run.duplicateOccurrenceIdentities(), instanceId);
            assertTrue(provider.observationSeen, instanceId);
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("instanceId", instanceId);
            evidence.put("candidateInputSha256", sha256(input));
            evidence.put("taskId", run.taskId());
            evidence.put("runId", run.runId());
            evidence.put("taskCompleted", run.taskCompleted());
            evidence.put("runPhase", run.runPhase());
            evidence.put("durableFormalResultPresent", run.durableFormalResultPresent());
            evidence.put("toolCalls", run.toolCalls());
            evidence.put("localDeterministicProviderTurns", run.providerTurns());
            evidence.put("externalProviderCalls", 0);
            evidence.put("duplicateOccurrenceIdentities", run.duplicateOccurrenceIdentities());
            evidence.put("provider", config.effectiveProvider());
            evidence.put("providerPreset", config.providerPreset());
            evidence.put("model", config.model());
            evidence.put("thinking", config.effectiveThinking());
            evidence.put("reasoningEffort", config.effectiveReasoningEffort());
            evidence.put("contextWindowTokens", config.effectiveContextWindowTokens());
            evidence.put("approvalMode", config.approvalMode());
            evidence.put("maxIterationsPerSegment", config.effectiveMaxIterations());
            evidence.put("continuityPolicyHash", policy.policyHash());
            runs.add(evidence);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", "paicli-external-production-candidate-control-v1");
        result.put("mode", "DETERMINISTIC_STUB_PROVIDER_DISABLED");
        result.put("productionPath", "com.paicli.runtime.CodingTaskRunner via PaiCliBenchmarkTreatment");
        result.put("selectedInstances", 10);
        result.put("completedProductionControlRuns", 10);
        result.put("externalProviderCalls", 0);
        result.put("paidCandidateRuns", 0);
        result.put("officialEvaluatorRuns", 0);
        result.put("continuityPolicyHash", policy.policyHash());
        result.put("runs", runs);
        Files.createDirectories(output.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
    }

    private static Config config(Path workspace) {
        var continuity = new Config.TaskContinuityConfig(
                true, 3, 3, 40, 80, 600_000L, 64_000L, 1_200_000L);
        return new Config(workspace.toString(), 30, 50_000, 200_000,
                Config.MODE_WORKSPACE_WRITE, 10_000,
                Config.PROVIDER_OPENAI, "deepseek-v4-flash", "https://api.deepseek.com",
                "DEEPSEEK_API_KEY", Config.DEFAULT_SYSTEM_PROMPT, 10,
                "", 120, 50_000, 1, true, false, false,
                false, Config.DEFAULT_SKILLS_DIRS, Config.DEFAULT_MAX_SKILL_CONTEXT_CHARS,
                Config.DEFAULT_MAX_SELECTED_SKILLS, false, Config.DEFAULT_HOOKS_CONFIG_PATH,
                Config.DEFAULT_HOOK_TIMEOUT_SECONDS, Config.DEFAULT_MAX_HOOK_OUTPUT_BYTES,
                Config.DEFAULT_HOOK_FAILURE_POLICY, 65_536, "deepseek", "enabled", "max",
                null, continuity);
    }

    private static Path requiredPath(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(property + " required");
        return Path.of(value).toAbsolutePath().normalize();
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path)));
    }

    private static final class DeterministicControlProvider implements ModelProvider {
        private final String instanceId;
        private boolean observationSeen;

        private DeterministicControlProvider(String instanceId) {
            this.instanceId = instanceId;
        }

        @Override
        public ModelResponse chat(List<AgentMessage> messages, List<ToolDefinition> tools) {
            return chatForOperation("CHAT", messages, tools);
        }

        @Override
        public ModelResponse chatForOperation(String operation, List<AgentMessage> messages,
                List<ToolDefinition> tools) {
            if ("PLAN".equals(operation) || tools.isEmpty()) {
                return ModelResponse.finalAnswer(PLAN);
            }
            observationSeen = messages.stream().anyMatch(message ->
                    message.type() == AgentMessage.MessageType.OBSERVATION);
            if (!observationSeen) {
                return ModelResponse.toolCallResponse("inspect shared launch control marker",
                        new ToolCall("read_file", Map.of("path", "launch-control.txt"),
                                "external-control-" + instanceId));
            }
            return ModelResponse.finalAnswer("Provider-disabled production control completed for "
                    + instanceId + ".");
        }
    }
}
