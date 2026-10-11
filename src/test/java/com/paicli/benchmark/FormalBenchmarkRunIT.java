package com.paicli.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.agent.AgentLoopControl;
import com.paicli.config.Config;
import com.paicli.config.ConfigLoader;
import com.paicli.provider.ProviderFactory;
import com.paicli.provider.telemetry.ProviderCallObservation;
import com.paicli.provider.telemetry.AppendOnlyProviderCallLedger;
import com.paicli.provider.telemetry.ProviderCallLedgerContext;
import com.paicli.provider.telemetry.ProviderCallLedgerModelProvider;
import com.paicli.runtime.CodingTaskObserver;
import com.paicli.runtime.PlanCompatibilityObserver;
import com.paicli.state.CodingRunStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.fail;

/** One explicitly gated paid benchmark run. It is excluded from ordinary Maven test discovery. */
class FormalBenchmarkRunIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> IGNORED = Set.of(".git", ".paicli", "target");

    @Test
    void executeOneAuthorizedRun() throws Exception {
        String executionKind = System.getProperty(
                "paicli.benchmark.executionKind", "FORMAL");
        if (!Set.of("FORMAL", "CALIBRATION").contains(executionKind))
            fail("unsupported benchmark execution kind");
        boolean providerDisabled = Boolean.parseBoolean(System.getProperty(
                "paicli.benchmark.providerDisabled", "false"));
        if ("FORMAL".equals(executionKind) && providerDisabled
                && Boolean.getBoolean("paicli.benchmark.authorized"))
            fail("Provider-disabled formal readiness cannot carry paid authorization");
        if ("FORMAL".equals(executionKind) && !providerDisabled
                && !Boolean.getBoolean("paicli.benchmark.authorized"))
            fail("paid formal benchmark requires -Dpaicli.benchmark.authorized=true");
        if ("CALIBRATION".equals(executionKind)
                && !Boolean.getBoolean("paicli.calibration.authorized"))
            fail("calibration requires -Dpaicli.calibration.authorized=true");
        Path project = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path evidenceRoot = project.resolve("target/autumn-evidence").normalize();
        Path gatePath = requiredPath("paicli.benchmark.gate", evidenceRoot);
        JsonNode gate = JSON.readTree(gatePath.toFile());

        String runtime = required("paicli.benchmark.runtime");
        if (!Set.of("PAICLI", "SIMPLE_TOOL_LOOP").contains(runtime)) {
            fail("unsupported benchmark runtime");
        }
        Path workspace = requiredPath("paicli.benchmark.workspace", evidenceRoot);
        Path controlHome = requiredPath("paicli.benchmark.controlHome", evidenceRoot);
        Path output = requiredPath("paicli.benchmark.output", evidenceRoot);
        Path firstCandidate = requiredPath("paicli.benchmark.firstCandidate", evidenceRoot);
        Path providerLedger = requiredPath("paicli.benchmark.providerLedger", evidenceRoot);
        Path planCompatibilityLedger = requiredPath(
                "paicli.benchmark.planCompatibilityLedger", evidenceRoot);
        String processSafeRunToken = required("paicli.benchmark.processSafeRunToken");
        if (!processSafeRunToken.matches("[A-Za-z0-9._-]+"))
            fail("process-safe run token contains a forbidden character");
        String protocolHash = required("paicli.benchmark.protocolHash");
        if (Files.exists(output) || Files.exists(firstCandidate)) {
            fail("benchmark output/candidate path already exists");
        }
        Path taskPath = Path.of(required("paicli.benchmark.task"))
                .toAbsolutePath().normalize();
        Path fixtureRoot = project.resolve("benchmarks/coding/fixtures").normalize();
        if (!taskPath.startsWith(fixtureRoot) || !taskPath.getFileName().toString().equals("task.json")) {
            fail("task manifest must be a frozen benchmark fixture");
        }
        JsonNode task = JSON.readTree(taskPath.toFile());
        if (!"COMMON_PAIRED".equals(task.path("cohort").asText())) {
            fail("runtime-contract cohort requires its specialized continue/compact/recovery driver");
        }
        requireAuthorizedGate(gate, executionKind, task.path("taskId").asText(),
                protocolHash, providerDisabled);
        String prompt = Files.readString(taskPath.getParent().resolve(
                task.path("promptFile").asText()));
        Config config = taskConfig(new ConfigLoader(workspace, controlHome).load(),
                workspace, task);
        requireFrozenProvider(config);
        var rawProvider = providerDisabled
                ? (isFreshCommonGate(gate)
                    ? new FreshCommonReadinessReplayProvider(
                            task.path("taskId").asText(), task)
                    : isAdvisoryCalibrationGate(gate)
                        ? new AdvisoryPlanGateReplayProvider(
                                task.path("taskId").asText(), task)
                        : new ProcessBoundaryReplayProvider(task.path("taskId").asText()))
                : new ProviderCallLedgerModelProvider(ProviderFactory.create(config),
                    new AppendOnlyProviderCallLedger(providerLedger, evidenceRoot),
                    new ProviderCallLedgerContext(processSafeRunToken, runtime,
                            config.effectiveProvider() + "/" + config.providerPreset(),
                            config.model(), protocolHash));
        AgentLoopControl approval = new PredeclaredApprovalControl(
                (iteration, call) -> task.path("approvalProfile").asText()
                        .equals("workspace-write-v1"));
        var planCompatibility = new AppendOnlyPlanCompatibilityEvidence(
                planCompatibilityLedger, evidenceRoot, processSafeRunToken, runtime);

        Map<String, Object> facts;
        if (runtime.equals("SIMPLE_TOOL_LOOP")) {
            var run = new SimpleToolLoopBaseline(rawProvider,
                    BenchmarkToolRegistryFactory.create(workspace),
                    new com.paicli.safety.ApprovalPolicy(config, "benchmark-simple"),
                    approval, task.path("taskConstraints").path("maxProviderTurns").asInt())
                    .run(prompt);
            planCompatibility.observed(PlanCompatibilityObserver.Fact.notApplicable());
            copyCandidate(workspace, firstCandidate);
            facts = simpleFacts(run);
        } else {
            FirstCandidateObserver capture = new FirstCandidateObserver(workspace, firstCandidate);
            var run = new PaiCliBenchmarkTreatment(workspace, rawProvider, config,
                    approval, capture, planCompatibility).run(prompt);
            if (!Files.exists(firstCandidate)) copyCandidate(workspace, firstCandidate);
            capture.throwIfFailed();
            facts = paicliFacts(run);
        }
        facts.put("schemaVersion", "benchmark-driver-result-v1");
        facts.put("runtime", runtime);
        facts.put("taskId", task.path("taskId").asText());
        facts.put("candidateIdentity", gate.path("candidateIdentity").asText());
        facts.put("executionKind", executionKind);
        facts.put("resultVersion", gate.path("resultVersion").asText(null));
        facts.put("providerDisabled", providerDisabled);
        facts.put("networkProviderCalls", providerDisabled ? 0 : -1);
        facts.put("processSafeRunToken", processSafeRunToken);
        facts.put("formalAggregateEligible", "FORMAL".equals(executionKind)
                && !providerDisabled);
        facts.put("measurementBoundary", "IN_PROCESS_MAVEN_TEST_HARNESS");
        facts.put("providerCallLedger", "PERSISTED_BEFORE_AGENT_INTERPRETATION");
        facts.put("sharedEffectfulBatchTelemetryHash",
                com.paicli.agent.AgentLoop.sharedToolBatchTelemetryHash());
        facts.put("planCompatibilityTelemetry", planCompatibility.facts().stream()
                .map(AppendOnlyPlanCompatibilityEvidence::map).toList());
        Files.createDirectories(output.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), facts);
    }

    private static Map<String, Object> simpleFacts(SimpleToolLoopBaseline.RunResult run) {
        Map<String, Object> facts = new LinkedHashMap<>();
        boolean completed = run.formalDriverResult().outcomeClass()
                == FormalDriverResult.OutcomeClass.TASK_SUCCESS;
        facts.put("logicalTaskState", completed ? "COMPLETED" : "FAILED");
        facts.put("logicalRunState", completed ? "COMPLETED" : "FAILED");
        facts.put("finalAnswer", run.agentResult() == null ? null : run.agentResult().answer());
        facts.put("durableFormalResult", null);
        facts.put("toolCalls", run.toolCalls());
        facts.put("providerTurns", run.providerTurns());
        facts.put("providerLatencyMs", run.providerLatencyMillis());
        facts.put("toolLatencyMs", run.toolLatencyMillis());
        facts.put("alignedExecutionLatencyMs", run.harnessLatencyMillis());
        facts.put("approvalDecisionFacts", run.approvalDecisionFacts());
        facts.put("sharedEffectfulBatchTelemetry",
                batchTelemetry(run.effectfulBatchTelemetry()));
        usage(facts, run.providerObservations());
        facts.put("paicliRuntimeSpecific", null);
        facts.put("formalDriverResult", run.formalDriverResult().toMap());
        return facts;
    }

    private static Map<String, Object> paicliFacts(PaiCliBenchmarkTreatment.RunResult run) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("logicalTaskState", run.taskCompleted() ? "COMPLETED" : "FAILED");
        facts.put("logicalRunState", run.runPhase());
        facts.put("finalAnswer", run.durableFormalResult() == null
                ? run.summary() : run.durableFormalResult());
        facts.put("durableFormalResult", run.durableFormalResultPresent());
        facts.put("toolCalls", run.toolCalls());
        facts.put("firstToolAdmissionReached", run.toolCalls() > 0);
        facts.put("verificationStageReached", run.structuredVerificationCount() > 0);
        facts.put("providerTurns", run.providerTurns());
        facts.put("providerLatencyMs", run.providerLatencyMillis());
        facts.put("toolLatencyMs", run.toolLatencyMillis());
        facts.put("alignedExecutionLatencyMs", run.alignedExecutionLatencyMillis());
        facts.put("approvalDecisionFacts", run.approvalDecisionFacts());
        facts.put("sharedEffectfulBatchTelemetry",
                batchTelemetry(run.effectfulBatchTelemetry()));
        facts.put("inputTokens", run.inputTokens());
        facts.put("outputTokens", run.outputTokens());
        facts.put("usageSource", run.usageSource());
        Map<String, Object> specific = new LinkedHashMap<>();
        specific.put("structuredVerificationCount", run.structuredVerificationCount());
        specific.put("structuredVerificationPass", run.firstStructuredVerificationPassed());
        specific.put("structuredRepairCount", run.structuredRepairCount());
        specific.put("structuredRepairSuccess", run.structuredRepairSuccess());
        specific.put("repairLifecycleState", run.repairLifecycleState());
        specific.put("durableRetryCount", run.durableRetryCount());
        specific.put("compactCount", run.compactCount());
        specific.put("duplicateOccurrenceIdentities", run.duplicateOccurrenceIdentities());
        facts.put("paicliRuntimeSpecific", specific);
        facts.put("formalDriverResult", paicliDriverResult(run).toMap());
        return facts;
    }

    private static FormalDriverResult paicliDriverResult(
            PaiCliBenchmarkTreatment.RunResult run) {
        if (run.taskCompleted()) return FormalDriverResult.taskSucceeded();
        if ("PROVIDER_PROTOCOL_FAILURE".equals(run.terminalFailureClass())) {
            return FormalDriverResult.typedTaskFailure("PROVIDER_PROTOCOL_FAILURE",
                    FormalDriverResult.FailureCategory.PROVIDER_PROTOCOL_FAILURE);
        }
        return FormalDriverResult.typedTaskFailure("PAICLI_TASK_TERMINAL_FAILURE",
                FormalDriverResult.FailureCategory
                        .INCOMPLETE_RESULT_OR_FORMAL_RESULT_MISSING);
    }

    private static List<Map<String, Object>> batchTelemetry(
            List<com.paicli.agent.AgentLoopObserver.EffectfulBatchFact> facts) {
        return facts.stream().map(fact -> {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("effectfulBatchReceived", fact.effectfulBatchReceived());
            value.put("toolCallCount", fact.toolCallCount());
            value.put("normalizationApplied", fact.normalizationApplied());
            value.put("normalizationOutcome", fact.normalizationOutcome().name());
            return value;
        }).toList();
    }

    private static void usage(Map<String, Object> facts,
            List<ProviderCallObservation> observations) {
        if (observations.isEmpty() || observations.stream().anyMatch(value ->
                value.usageSource() != ProviderCallObservation.UsageSource.PROVIDER_REPORTED
                        || value.inputTokens() == null || value.outputTokens() == null)) {
            facts.put("inputTokens", null);
            facts.put("outputTokens", null);
            facts.put("usageSource", "NOT_AVAILABLE");
            return;
        }
        facts.put("inputTokens", observations.stream()
                .mapToInt(ProviderCallObservation::inputTokens).sum());
        facts.put("outputTokens", observations.stream()
                .mapToInt(ProviderCallObservation::outputTokens).sum());
        facts.put("usageSource", "PROVIDER_REPORTED");
    }

    private static Config taskConfig(Config base, Path workspace, JsonNode task) {
        List<String> verificationCommand = JSON.convertValue(
                task.path("oracle").path("verificationCommand"),
                JSON.getTypeFactory().constructCollectionType(List.class, String.class));
        String verification = String.join(" ", verificationCommand);
        int maxTurns = task.path("taskConstraints").path("maxProviderTurns").asInt();
        String approval = task.path("approvalProfile").asText().equals("workspace-write-v1")
                ? Config.MODE_ON_REQUEST : Config.MODE_READ_ONLY;
        return new Config(workspace.toString(), base.commandTimeoutSeconds(),
                base.maxOutputBytes(), base.maxWriteFileBytes(), approval,
                base.maxProjectInstructionChars(), base.provider(), base.model(), base.baseUrl(),
                base.apiKeyEnv(), base.systemPrompt(), maxTurns, verification,
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

    private static void requireAuthorizedGate(JsonNode gate, String executionKind,
            String taskId, String protocolHash, boolean providerDisabled) {
        if ("CALIBRATION".equals(executionKind)) {
            if (isAdvisoryCalibrationGate(gate)) {
                String advisoryResultVersion = isAdvisoryCalibrationV3Gate(gate)
                        ? "advisory-plan-gate-calibration-result-v3"
                        : "advisory-plan-gate-calibration-result-v2";
                boolean selected = false;
                for (JsonNode value : gate.path("selectedTaskIds"))
                    selected |= taskId.equals(value.asText());
                boolean authorizationMatches = providerDisabled
                        ? !gate.path("paidProviderAuthorized").asBoolean(true)
                            && "OFFLINE_READINESS".equals(gate.path("status").asText())
                        : gate.path("paidProviderAuthorized").asBoolean(false)
                            && "AUTHORIZED_FOR_CALIBRATION_ONLY".equals(
                                    gate.path("status").asText())
                            && !gate.path("authorizationReference").asText().isBlank();
                if (!authorizationMatches
                        || gate.path("maxCalibrationRuns").asInt() != 10
                        || gate.path("formalAggregateEligible").asBoolean(true)
                        || !advisoryResultVersion.equals(gate.path("resultVersion").asText())
                        || !protocolHash.equals(gate.path(
                                "providerProtocolManifestSha256").asText())
                        || gate.path("candidateIdentity").asText().isBlank()
                        || gate.path("launchManifestSha256").asText().isBlank()
                        || gate.path("harnessIdentity").asText().isBlank()
                        || gate.path("harnessManifestSha256").asText().isBlank()
                        || gate.path("freshHardMaximumUsd").asDouble(-1) != 0.60
                        || gate.path("nextRunReserveUsd").asDouble(-1) != 0.15
                        || !selected)
                    fail("Advisory Plan calibration gate is incomplete or unauthorized");
                return;
            }
            boolean selected = false;
            for (JsonNode value : gate.path("selectedTaskIds"))
                selected |= taskId.equals(value.asText());
            String resultVersion = gate.path("resultVersion").asText();
            if (!gate.path("paidProviderAuthorized").asBoolean(false)
                    || gate.path("maxCalibrationRuns").asInt() != 10
                    || gate.path("formalAggregateEligible").asBoolean(true)
                    || !Set.of("calibration-result-v1", "calibration-result-v2",
                            "calibration-result-v3")
                            .contains(resultVersion)
                    || (Set.of("calibration-result-v2", "calibration-result-v3")
                            .contains(resultVersion)
                            && !protocolHash.equals(gate.path(
                                    "providerProtocolManifestSha256").asText()))
                    || gate.path("authorizationReference").asText().isBlank()
                    || gate.path("candidateIdentity").asText().isBlank()
                    || gate.path("candidateManifestSha256").asText().isBlank()
                    || gate.path("hardBudgetUsd").asDouble(-1) != 2.0
                    || gate.path("preRunWorstCaseReserveUsd").asDouble(-1) != 0.6
                    || !selected
                    || !"AUTHORIZED_FOR_CALIBRATION_ONLY".equals(
                            gate.path("status").asText()))
                fail("calibration gate is incomplete or unauthorized");
            return;
        }
        if (isFreshCommonGate(gate)) {
            boolean authorizationMatches = providerDisabled
                    ? !gate.path("paidProviderAuthorized").asBoolean(true)
                        && "OFFLINE_READINESS".equals(gate.path("status").asText())
                    : gate.path("paidProviderAuthorized").asBoolean(false)
                        && "AUTHORIZED_FOR_FRESH_COMMON_ONLY".equals(
                                gate.path("status").asText())
                        && !gate.path("authorizationReference").asText().isBlank();
            if (!authorizationMatches
                    || gate.path("formalRunCount").asInt() != 144
                    || gate.path("pairedRunCount").asInt() != 144
                    || gate.path("paicliRunCount").asInt() != 72
                    || gate.path("simpleToolLoopRunCount").asInt() != 72
                    || gate.path("runtimeContractRunCount").asInt(-1) != 0
                    || gate.path("maxPaidFormalRuns").asInt() != 144
                    || gate.path("formalAggregateEligible").asBoolean(true)
                    || !"benchmark-v13-advisory-plan-wire-common-refresh".equals(
                            gate.path("benchmarkVersion").asText())
                    || !"formal-common-result-v7".equals(
                            gate.path("resultVersion").asText())
                    || !"formal-common-evidence-v7".equals(
                            gate.path("evidenceContractVersion").asText())
                    || !protocolHash.equals(gate.path(
                            "providerProtocolManifestSha256").asText())
                    || gate.path("candidateIdentity").asText().isBlank()
                    || gate.path("launchManifestSha256").asText().isBlank()
                    || gate.path("harnessIdentity").asText().isBlank()
                    || gate.path("harnessManifestSha256").asText().isBlank()
                    || gate.path("freshHardMaximumUsd").asDouble(-1) != 2.50
                    || gate.path("nextRunReserveUsd").asDouble(-1) != 0.60
                    || gate.path("accountedBeforeCampaignUsd").asDouble(-1)
                            != 1.96478467
                    || gate.path("cumulativeAuthorizationAccountingCeilingUsd")
                            .asDouble(-1) != 5.68077689
                    || !"USD".equals(gate.path("budgetCurrency").asText())
                    || !"ENVIRONMENT".equals(gate.path("credentialSource").asText())
                    || gate.path("imports").path("totalRows").asInt(-1) != 0)
                fail("fresh COMMON gate is incomplete or unauthorized");
            return;
        }
        if ((!providerDisabled && !gate.path("paidProviderAuthorized").asBoolean(false))
                || (providerDisabled && gate.path("paidProviderAuthorized").asBoolean(true))
                || gate.path("formalRunCount").asInt() != 162
                || gate.path("maxPaidFormalRuns").asInt(0) < 162
                || gate.path("maxCalibrationRuns").asInt(0) < 1
                || gate.path("authorizationReference").asText().isBlank()
                || gate.path("calibrationResultReference").asText().isBlank()
                || gate.path("candidateIdentity").asText().isBlank()
                || !"benchmark-v9-typed-outcome-boundary".equals(
                gate.path("benchmarkVersion").asText())
                || !"formal-result-v6".equals(gate.path("resultVersion").asText())
                || !"formal-benchmark-evidence-v6".equals(
                gate.path("evidenceContractVersion").asText())
                || !protocolHash.equals(
                        gate.path("providerProtocolManifestSha256").asText())
                || gate.path("formalEvidenceManifestSha256").asText().isBlank()
                || gate.path("freshFormalHardMaximumUsd").asDouble(-1) != 4.75
                || gate.path("nextRunWorstCaseReserveUsd").asDouble(-1) != 0.6
                || gate.path("accountedBeforeFormalUsd").asDouble(-1) != 0.91855103
                || gate.path("cumulativeAuthorizationAccountingCeilingUsd")
                        .asDouble(-1) != 5.68077689
                || !"USD".equals(gate.path("budgetCurrency").asText())
                || !"ENVIRONMENT".equals(gate.path("credentialSource").asText()))
            fail("formal benchmark gate is incomplete or unauthorized");
    }

    private static boolean isAdvisoryCalibrationGate(JsonNode gate) {
        return Set.of("advisory-plan-gate-calibration-execution-gate-v2",
                "advisory-plan-gate-calibration-execution-gate-v3")
                .contains(gate.path("schemaVersion").asText());
    }

    private static boolean isFreshCommonGate(JsonNode gate) {
        return "fresh-common-144-execution-gate-v1".equals(
                gate.path("schemaVersion").asText());
    }

    private static boolean isAdvisoryCalibrationV3Gate(JsonNode gate) {
        return "advisory-plan-gate-calibration-execution-gate-v3".equals(
                gate.path("schemaVersion").asText());
    }

    private static String required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) fail("missing system property: " + property);
        return value;
    }

    private static Path requiredPath(String property, Path root) {
        Path value = Path.of(required(property)).toAbsolutePath().normalize();
        if (!value.startsWith(root)) fail(property + " must be under target/autumn-evidence");
        return value;
    }

    private static void copyCandidate(Path workspace, Path destination) throws Exception {
        Files.createDirectories(destination);
        try (var sources = Files.walk(workspace)) {
            for (Path source : sources.sorted().toList()) {
                Path relative = workspace.relativize(source);
                if (relative.getNameCount() > 0
                        && IGNORED.contains(relative.getName(0).toString())) continue;
                Path target = destination.resolve(relative);
                if (Files.isDirectory(source)) Files.createDirectories(target);
                else if (Files.isRegularFile(source)) {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static final class FirstCandidateObserver implements CodingTaskObserver {
        private final Path workspace;
        private final Path destination;
        private final AtomicReference<Exception> failure = new AtomicReference<>();

        private FirstCandidateObserver(Path workspace, Path destination) {
            this.workspace = workspace;
            this.destination = destination;
        }

        @Override
        public void phaseChanged(String taskId, String runId, String phase) {
            if (!CodingRunStore.PHASE_VERIFYING.equals(phase) || Files.exists(destination)) return;
            try {
                copyCandidate(workspace, destination);
            } catch (Exception error) {
                failure.compareAndSet(null, error);
            }
        }

        private void throwIfFailed() throws Exception {
            if (failure.get() != null) throw failure.get();
        }
    }
}
