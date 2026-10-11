package com.paicli.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.Config;
import com.paicli.config.ConfigLoader;
import com.paicli.provider.ModelProvider;
import com.paicli.provider.ProviderFactory;
import com.paicli.provider.telemetry.AppendOnlyProviderCallLedger;
import com.paicli.provider.telemetry.ProviderCallLedgerContext;
import com.paicli.provider.telemetry.ProviderCallLedgerModelProvider;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.fail;

/** One specialized PaiCLI-only continue/compact/recovery formal or offline-readiness run. */
class FormalRuntimeContractRunIT {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void executeOneRuntimeContractRun() throws Exception {
        Path project = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path evidenceRoot = project.resolve("target/autumn-evidence").normalize();
        boolean providerDisabled = Boolean.parseBoolean(required(
                "paicli.benchmark.providerDisabled"));
        if (providerDisabled && Boolean.getBoolean("paicli.benchmark.authorized")) {
            fail("Provider-disabled readiness cannot carry paid authorization");
        }
        if (!providerDisabled && !Boolean.getBoolean("paicli.benchmark.authorized")) {
            fail("paid runtime-contract benchmark requires explicit authorization");
        }
        Path output = requiredPath("paicli.benchmark.output", evidenceRoot);
        Path workspace = requiredPath("paicli.benchmark.workspace", evidenceRoot);
        Path controlHome = requiredPath("paicli.benchmark.controlHome", evidenceRoot);
        Path providerLedger = requiredPath("paicli.benchmark.providerLedger", evidenceRoot);
        Path planLedger = requiredPath("paicli.benchmark.planCompatibilityLedger", evidenceRoot);
        Path taskPath = Path.of(required("paicli.benchmark.task"))
                .toAbsolutePath().normalize();
        Path fixtureRoot = project.resolve("benchmarks/coding/fixtures").normalize();
        if (!taskPath.startsWith(fixtureRoot)
                || !"task.json".equals(taskPath.getFileName().toString())) {
            fail("task manifest must be a frozen benchmark fixture");
        }
        JsonNode task = JSON.readTree(taskPath.toFile());
        if (!"PAICLI_RUNTIME_CONTRACT".equals(task.path("cohort").asText())
                || !Set.of("CONTINUE", "COMPACT", "RECOVERY")
                .contains(task.path("category").asText())) {
            fail("specialized driver accepts only PAICLI_RUNTIME_CONTRACT fixtures");
        }
        if (!"PAICLI".equals(required("paicli.benchmark.runtime"))) {
            fail("runtime-contract execution is PaiCLI-only");
        }
        String resultVersion = required("paicli.benchmark.resultVersion");
        String candidateIdentity = required("paicli.benchmark.candidateIdentity");
        String protocolHash = required("paicli.benchmark.protocolHash");
        String processSafeRunToken = required("paicli.benchmark.processSafeRunToken");
        int trial = Integer.parseInt(required("paicli.benchmark.trial"));
        if (trial < 1 || trial > 3) fail("runtime-contract trial must be 1..3");
        String contractTestMode = System.getProperty(
                "paicli.benchmark.contractTestMode", "ACTUAL");
        if (!Set.of("ACTUAL", "FORCED_TYPED_ASSERTION_FAILURE",
                "FORCED_TYPED_ASSERTION_FAILURE_NO_PLAN")
                .contains(contractTestMode)) {
            fail("unknown runtime driver contract-test mode");
        }
        if (!providerDisabled && !"ACTUAL".equals(contractTestMode)) {
            fail("paid runtime execution cannot inject a contract-test outcome");
        }
        if (!processSafeRunToken.matches("[A-Za-z0-9._-]+")) {
            fail("process-safe run token contains a forbidden character");
        }
        Path candidateManifest = project.resolve(required(
                "paicli.benchmark.candidateManifest")).normalize();
        if (!candidateManifest.startsWith(project.resolve("benchmarks/coding/formal"))) {
            fail("candidate manifest must be frozen under benchmarks/coding/formal");
        }
        JsonNode candidate = JSON.readTree(candidateManifest.toFile());
        if (!candidateIdentity.equals(candidate.path("candidateIdentity").asText())
                || !resultVersion.equals(candidate.path("resultVersion").asText())
                || !protocolHash.equals(candidate.path(
                "providerProtocolManifestSha256").asText())) {
            fail("runtime-contract launch identity differs from frozen candidate");
        }
        if (Files.exists(output)) fail("runtime-contract output already exists");

        Config config = taskConfig(new ConfigLoader(workspace, controlHome).load(),
                workspace, task);
        requireFrozenProvider(config);
        Files.createDirectories(providerLedger.getParent());
        if (!Files.exists(providerLedger)) Files.createFile(providerLedger);
        try (var ignored = Files.newBufferedReader(providerLedger)) {
            // Opening the exact path proves that the empty offline ledger is recoverable.
        }
        ModelProvider provider;
        int networkProviderCalls;
        if (providerDisabled) {
            provider = new RuntimeContractReplayProvider(
                    task.path("taskId").asText(), task, workspace);
            networkProviderCalls = 0;
        } else {
            provider = new ProviderCallLedgerModelProvider(ProviderFactory.create(config),
                    new AppendOnlyProviderCallLedger(providerLedger, evidenceRoot),
                    new ProviderCallLedgerContext(processSafeRunToken, "PAICLI",
                            config.effectiveProvider() + "/" + config.providerPreset(),
                            config.model(), protocolHash));
            networkProviderCalls = -1;
        }
        var planCompatibility = new AppendOnlyPlanCompatibilityEvidence(
                planLedger, evidenceRoot, processSafeRunToken, "PAICLI");
        Path taskDirectory = taskPath.getParent();
        String prompt = Files.readString(taskDirectory.resolve(
                task.path("promptFile").asText()));
        String continuation = task.path("runtimeInputs").hasNonNull("continuationPromptFile")
                ? Files.readString(taskDirectory.resolve(task.path("runtimeInputs")
                .path("continuationPromptFile").asText())) : null;
        RuntimeContractScenarioExecutor.Result result = new RuntimeContractScenarioExecutor(
                workspace, task, provider, config, planCompatibility).run(prompt, continuation);

        Map<String, Boolean> emittedAssertions = new LinkedHashMap<>(
                result.runtimeAssertions());
        boolean emittedSuccess = result.runtimeContractSuccess();
        String emittedTerminalReason = result.terminalReason();
        if (contractTestMode.startsWith("FORCED_TYPED_ASSERTION_FAILURE")) {
            String first = emittedAssertions.keySet().stream().findFirst().orElseThrow();
            emittedAssertions.put(first, false);
            emittedSuccess = false;
            emittedTerminalReason = result.category() + "_ASSERTION_FAILURE";
        }
        List<Map<String, Object>> planEvents =
                "FORCED_TYPED_ASSERTION_FAILURE_NO_PLAN".equals(contractTestMode)
                ? List.of() : planCompatibility.facts().stream()
                .map(AppendOnlyPlanCompatibilityEvidence::map).toList();
        String planApplicability = planEvents.isEmpty()
                ? "NOT_APPLICABLE" : "APPLICABLE";

        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("schemaVersion", "formal-runtime-driver-result-v2");
        facts.put("runtime", "PAICLI");
        facts.put("taskId", task.path("taskId").asText());
        facts.put("trial", trial);
        facts.put("cohort", "PAICLI_RUNTIME_CONTRACT");
        facts.put("runtimeContractType", task.path("category").asText());
        facts.put("candidateIdentity", candidateIdentity);
        facts.put("resultVersion", resultVersion);
        facts.put("processSafeRunToken", processSafeRunToken);
        facts.put("providerDisabled", providerDisabled);
        facts.put("formalAggregateEligible", !providerDisabled);
        facts.put("pairedAggregateEligible", false);
        facts.put("logicalTaskState", result.terminal().taskCompleted()
                ? "COMPLETED" : "FAILED");
        facts.put("logicalRunState", result.terminal().runPhase());
        facts.put("finalAnswer", result.terminal().durableFormalResult() == null
                ? result.terminal().summary() : result.terminal().durableFormalResult());
        facts.put("durableFormalResult", result.terminal().durableFormalResultPresent());
        facts.put("toolCalls", result.toolCalls());
        facts.put("providerTurns", result.providerTurns());
        facts.put("providerLatencyMs", result.providerLatencyMillis());
        facts.put("toolLatencyMs", result.toolLatencyMillis());
        facts.put("alignedExecutionLatencyMs", result.alignedExecutionLatencyMillis());
        facts.put("inputTokens", combinedTokens(result, true));
        facts.put("outputTokens", combinedTokens(result, false));
        facts.put("usageSource", combinedUsageSource(result));
        facts.put("approvalDecisionFacts", combinedApprovals(result));
        facts.put("sharedEffectfulBatchTelemetry", combinedBatchTelemetry(result));
        facts.put("sharedEffectfulBatchTelemetryHash",
                com.paicli.agent.AgentLoop.sharedToolBatchTelemetryHash());
        facts.put("planCompatibilityTelemetry", Map.of(
                "applicability", planApplicability,
                "events", planEvents));
        Map<String, Object> specific = new LinkedHashMap<>();
        specific.put("structuredVerificationCount", result.structuredVerificationCount());
        specific.put("structuredVerificationPass", combinedVerificationPass(result));
        specific.put("structuredRepairCount", result.structuredRepairCount());
        specific.put("structuredRepairSuccess", combinedRepairSuccess(result));
        specific.put("repairLifecycleState", result.terminal().repairLifecycleState());
        specific.put("durableRetryCount", result.durableRetryCount());
        specific.put("compactCount", result.compactCount());
        specific.put("duplicateOccurrenceIdentities", result.duplicateOccurrenceIdentities());
        specific.put("runtimeContractSuccess", emittedSuccess);
        specific.put("runtimeAssertions", emittedAssertions);
        Map<String, Boolean> assertionApplicability = new LinkedHashMap<>();
        emittedAssertions.keySet().forEach(name -> assertionApplicability.put(name, true));
        specific.put("runtimeAssertionApplicability", assertionApplicability);
        Map<String, Object> runtimeMetrics = new LinkedHashMap<>(result.runtimeMetrics());
        runtimeMetrics.put("planCompatibilityApplicability", planApplicability);
        specific.put("runtimeContractMetrics", runtimeMetrics);
        facts.put("paicliRuntimeSpecific", specific);
        FormalDriverResult driverResult = RuntimeContractDriverOutcome.from(
                result.taskId(), result.category(), emittedSuccess,
                emittedTerminalReason);
        facts.put("formalDriverResult", driverResult.toMap());
        facts.put("measurementBoundary", providerDisabled
                ? "OFFLINE_DETERMINISTIC_RUNTIME_CONTRACT_REPLAY"
                : "IN_PROCESS_MAVEN_TEST_HARNESS");
        facts.put("providerEvidenceReferences", Map.of(
                "providerLedgerIdentity", processSafeRunToken,
                "providerLedgerState", providerDisabled
                        ? "EMPTY_ZERO_NETWORK_CALLS"
                        : "PERSISTED_BEFORE_AGENT_INTERPRETATION",
                "usageProvenanceState", providerDisabled
                        ? "NOT_APPLICABLE_NO_REQUEST_SENT" : combinedUsageSource(result),
                "networkProviderCalls", networkProviderCalls,
                "evidenceReferences", List.of(
                        "provider-calls.jsonl", "plan-compatibility.jsonl")));
        facts.put("contractTestMode", contractTestMode);
        Files.createDirectories(output.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), facts);
    }

    private static Config taskConfig(Config base, Path workspace, JsonNode task) {
        List<String> command = JSON.convertValue(task.path("oracle")
                .path("verificationCommand"), JSON.getTypeFactory()
                .constructCollectionType(List.class, String.class));
        String verification = String.join(" ", command);
        String approval = task.path("approvalProfile").asText()
                .equals("workspace-write-v1")
                ? Config.MODE_ON_REQUEST : Config.MODE_READ_ONLY;
        return new Config(workspace.toString(), base.commandTimeoutSeconds(),
                base.maxOutputBytes(), base.maxWriteFileBytes(), approval,
                base.maxProjectInstructionChars(), base.provider(), base.model(), base.baseUrl(),
                base.apiKeyEnv(), base.systemPrompt(),
                task.path("taskConstraints").path("maxProviderTurns").asInt(), verification,
                base.verificationTimeoutSeconds(), base.maxVerificationOutputBytes(),
                base.maxRepairAttempts(), true, !verification.isBlank(), true, false,
                base.skillsDirs(), base.maxSkillContextChars(), base.maxSelectedSkills(), false,
                base.hooksConfigPath(), base.hookTimeoutSeconds(), base.maxHookOutputBytes(),
                base.hookFailurePolicy(), base.contextWindowTokens(), base.providerPreset(),
                base.thinking(), base.reasoningEffort(), base.credentialRef());
    }

    private static void requireFrozenProvider(Config config) {
        if (!Config.PROVIDER_OPENAI.equals(config.effectiveProvider())
                || !"deepseek".equals(config.providerPreset())
                || !"deepseek-v4-flash".equals(config.model())
                || !"enabled".equals(config.effectiveThinking())
                || !"max".equals(config.effectiveReasoningEffort())
                || !Integer.valueOf(65_536).equals(config.effectiveContextWindowTokens())) {
            fail("control config does not match deepseek-shared-v1");
        }
    }

    private static Long combinedTokens(RuntimeContractScenarioExecutor.Result result,
            boolean input) {
        List<Long> values = new ArrayList<>();
        Long primary = input ? result.primary().inputTokens() : result.primary().outputTokens();
        if (primary != null) values.add(primary);
        if (result.followUp() != null) {
            Long child = input ? result.followUp().child().inputTokens()
                    : result.followUp().child().outputTokens();
            if (child != null) values.add(child);
        }
        int expected = result.followUp() == null ? 1 : 2;
        return values.size() == expected ? values.stream().mapToLong(Long::longValue).sum() : null;
    }

    private static String combinedUsageSource(RuntimeContractScenarioExecutor.Result result) {
        if (!"PROVIDER_REPORTED".equals(result.primary().usageSource())) {
            return "NOT_AVAILABLE";
        }
        if (result.followUp() != null
                && !"PROVIDER_REPORTED".equals(result.followUp().child().usageSource())) {
            return "NOT_AVAILABLE";
        }
        return "PROVIDER_REPORTED";
    }

    private static List<String> combinedApprovals(
            RuntimeContractScenarioExecutor.Result result) {
        List<String> values = new ArrayList<>(result.primary().approvalDecisionFacts());
        if (result.followUp() != null) {
            values.addAll(result.followUp().child().approvalDecisionFacts());
        }
        return List.copyOf(values);
    }

    private static List<Map<String, Object>> combinedBatchTelemetry(
            RuntimeContractScenarioExecutor.Result result) {
        var values = new ArrayList<com.paicli.agent.AgentLoopObserver.EffectfulBatchFact>(
                result.primary().effectfulBatchTelemetry());
        if (result.followUp() != null) {
            values.addAll(result.followUp().child().effectfulBatchTelemetry());
        }
        return values.stream().map(fact -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("effectfulBatchReceived", fact.effectfulBatchReceived());
            value.put("toolCallCount", fact.toolCallCount());
            value.put("normalizationApplied", fact.normalizationApplied());
            value.put("normalizationOutcome", fact.normalizationOutcome().name());
            return value;
        }).toList();
    }

    private static Boolean combinedVerificationPass(
            RuntimeContractScenarioExecutor.Result result) {
        List<Boolean> values = new ArrayList<>();
        if (result.primary().firstStructuredVerificationPassed() != null) {
            values.add(result.primary().firstStructuredVerificationPassed());
        }
        if (result.followUp() != null
                && result.followUp().child().firstStructuredVerificationPassed() != null) {
            values.add(result.followUp().child().firstStructuredVerificationPassed());
        }
        return values.isEmpty() ? null : values.stream().allMatch(Boolean.TRUE::equals);
    }

    private static Boolean combinedRepairSuccess(
            RuntimeContractScenarioExecutor.Result result) {
        List<Boolean> values = new ArrayList<>();
        if (result.primary().structuredRepairSuccess() != null) {
            values.add(result.primary().structuredRepairSuccess());
        }
        if (result.followUp() != null
                && result.followUp().child().structuredRepairSuccess() != null) {
            values.add(result.followUp().child().structuredRepairSuccess());
        }
        return values.isEmpty() ? null : values.stream().allMatch(Boolean.TRUE::equals);
    }

    private static String required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) fail("missing system property: " + property);
        return value;
    }

    private static Path requiredPath(String property, Path root) {
        Path value = Path.of(required(property)).toAbsolutePath().normalize();
        if (!value.startsWith(root) || value.equals(root)) {
            fail(property + " must be under target/autumn-evidence");
        }
        return value;
    }
}
