package com.paicli.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.Config;
import com.paicli.provider.ModelProvider;
import com.paicli.provider.ProviderFactory;
import com.paicli.provider.telemetry.AppendOnlyProviderCallLedger;
import com.paicli.provider.telemetry.ProviderCallLedgerContext;
import com.paicli.provider.telemetry.ProviderCallLedgerModelProvider;
import com.paicli.runtime.CodingTaskObserver;
import com.paicli.runtime.PlanCompatibilityObserver;
import com.paicli.runtime.TaskContinuityPolicy;
import com.paicli.tool.WriteFileTool;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.fail;

/** Exactly one authorized public-external candidate run through the production PaiCLI composition. */
class ExternalJavaValidationPaidRunIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AUTHORIZATION =
            "AUTHORIZE_PUBLIC_EXTERNAL_JAVA_VALIDATION_PAID_V1_ON_FROZEN_10_INSTANCE_SUBSET_WITH_USD_1_20_HARD_MAX";

    @Test
    void executeOneFrozenPaidInstance() throws Exception {
        if (!AUTHORIZATION.equals(required("paicli.external.paidAuthorization"))) {
            fail("exact paid authorization mismatch");
        }
        Path project = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path executionRoot = project.resolve("target/external-java-validation-paid-v1").normalize();
        Path evidenceRoot = project.resolve(
                "docs/evidence/benchmark/external-java-validation/paid-v1").normalize();
        Path workspace = requiredPath("paicli.external.workspace", executionRoot);
        Path input = requiredPath("paicli.external.candidateInput", evidenceRoot);
        Path output = requiredPath("paicli.external.driverOutput", evidenceRoot);
        Path ledger = requiredPath("paicli.external.providerLedger", evidenceRoot);
        if (Files.exists(output) || Files.exists(ledger)) fail("paid evidence path is not fresh");

        JsonNode candidate = JSON.readTree(input.toFile());
        if (candidate.size() != 4 || !candidate.has("instance_id") || !candidate.has("repo")
                || !candidate.has("base_commit") || !candidate.has("problem_statement")
                || candidate.has("patch") || candidate.has("test_patch")) {
            fail("candidate-visible input contract drift or oracle leakage");
        }
        String instanceId = required("paicli.external.instanceId");
        if (!instanceId.equals(candidate.path("instance_id").asText())) {
            fail("candidate instance identity drift");
        }

        Config config = frozenConfig(workspace);
        requireFrozenProvider(config);
        TaskContinuityPolicy policy = new TaskContinuityPolicy(
                true, 3, 3, 40, 80, 600_000, 64_000, 1_200_000);
        String runToken = required("paicli.external.runToken");
        ModelProvider provider = new ProviderCallLedgerModelProvider(
                ProviderFactory.create(config),
                new AppendOnlyProviderCallLedger(ledger, evidenceRoot),
                new ProviderCallLedgerContext(runToken, "PAICLI",
                        config.effectiveProvider() + "/" + config.providerPreset(),
                        config.model(), required("paicli.external.protocolHash")));
        String promptTemplate = Files.readString(project.resolve(
                "benchmarks/external/swebench-multilingual-java-v1/task-prompt-template.txt"));
        String prompt = promptTemplate.replace("{{problem_statement}}",
                candidate.path("problem_statement").asText());
        var options = new PaiCliBenchmarkTreatment.Options(null,
                (safety, snapshots, managedTask, controlPaths) ->
                        new WriteFileTool(safety, snapshots, managedTask, null, controlPaths),
                ignored -> { }, 1, policy, false);

        Instant started = Instant.now();
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("schemaVersion", "paicli-public-external-java-driver-v1");
        facts.put("instanceId", instanceId);
        facts.put("taskStartedAt", started.toString());
        try {
            var run = new PaiCliBenchmarkTreatment(workspace, provider, config,
                    new PredeclaredApprovalControl((iteration, call) -> true),
                    CodingTaskObserver.NOOP, PlanCompatibilityObserver.NOOP, options).run(prompt);
            facts.put("taskId", run.taskId());
            facts.put("runId", run.runId());
            facts.put("taskCompleted", run.taskCompleted());
            facts.put("runPhase", run.runPhase());
            facts.put("terminalFailureClass", run.terminalFailureClass());
            facts.put("durableFormalResultPresent", run.durableFormalResultPresent());
            facts.put("providerTurns", run.providerTurns());
            facts.put("toolCalls", run.toolCalls());
            facts.put("inputTokens", run.inputTokens());
            facts.put("outputTokens", run.outputTokens());
            facts.put("usageSource", run.usageSource());
            facts.put("latencyMillis", run.alignedExecutionLatencyMillis());
            facts.put("providerLatencyMillis", run.providerLatencyMillis());
            facts.put("toolLatencyMillis", run.toolLatencyMillis());
            facts.put("durableRetryCount", run.durableRetryCount());
            facts.put("compactCount", run.compactCount());
            facts.put("duplicateOccurrenceIdentities", run.duplicateOccurrenceIdentities());
            facts.put("approvalDecisionFacts", run.approvalDecisionFacts());
            facts.put("driverException", null);
        } catch (Exception error) {
            facts.put("taskCompleted", false);
            facts.put("runPhase", "DRIVER_EXCEPTION");
            facts.put("terminalFailureClass", error.getClass().getSimpleName());
            facts.put("durableFormalResultPresent", false);
            facts.put("providerTurns", 0);
            facts.put("toolCalls", 0);
            facts.put("inputTokens", null);
            facts.put("outputTokens", null);
            facts.put("usageSource", "NOT_AVAILABLE");
            facts.put("latencyMillis", Math.max(0L,
                    java.time.Duration.between(started, Instant.now()).toMillis()));
            facts.put("providerLatencyMillis", 0);
            facts.put("toolLatencyMillis", 0);
            facts.put("durableRetryCount", 0);
            facts.put("compactCount", 0);
            facts.put("duplicateOccurrenceIdentities", 0);
            facts.put("approvalDecisionFacts", java.util.List.of());
            facts.put("driverException", error.getClass().getName());
        }
        facts.put("taskEndedAt", Instant.now().toString());
        facts.put("provider", config.effectiveProvider());
        facts.put("providerPreset", config.providerPreset());
        facts.put("model", config.model());
        facts.put("thinking", config.effectiveThinking());
        facts.put("reasoningEffort", config.effectiveReasoningEffort());
        facts.put("contextWindowTokens", config.effectiveContextWindowTokens());
        facts.put("approvalMode", config.approvalMode());
        facts.put("continuityPolicyHash", policy.policyHash());
        Files.createDirectories(output.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), facts);
    }

    private static Config frozenConfig(Path workspace) {
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

    private static void requireFrozenProvider(Config config) {
        if (!Config.PROVIDER_OPENAI.equals(config.effectiveProvider())
                || !"deepseek".equals(config.providerPreset())
                || !"deepseek-v4-flash".equals(config.model())
                || !"https://api.deepseek.com".equals(config.baseUrl())
                || !"enabled".equals(config.effectiveThinking())
                || !"max".equals(config.effectiveReasoningEffort())
                || !Integer.valueOf(65_536).equals(config.effectiveContextWindowTokens())
                || config.isVerifyEnabled()) {
            fail("frozen Provider or task policy drift");
        }
    }

    private static String required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) fail("missing system property: " + property);
        return value;
    }

    private static Path requiredPath(String property, Path root) {
        Path value = Path.of(required(property)).toAbsolutePath().normalize();
        if (!value.startsWith(root) || value.equals(root)) {
            fail(property + " is outside its frozen root");
        }
        return value;
    }
}
