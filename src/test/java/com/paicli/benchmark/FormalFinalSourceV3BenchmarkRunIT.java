package com.paicli.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.agent.AgentLoopControl;
import com.paicli.config.Config;
import com.paicli.config.ConfigLoader;
import com.paicli.provider.ModelProvider;
import com.paicli.provider.ProviderFactory;
import com.paicli.provider.telemetry.AppendOnlyProviderCallLedger;
import com.paicli.provider.telemetry.ProviderCallLedgerContext;
import com.paicli.provider.telemetry.ProviderCallLedgerModelProvider;
import com.paicli.provider.telemetry.ProviderCallObservation;
import com.paicli.runtime.CodingTaskObserver;
import com.paicli.runtime.PlanCompatibilityObserver;
import com.paicli.state.CodingRunStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.fail;

/** Versioned final-source driver; excluded from ordinary Maven test discovery. */
class FormalFinalSourceV3BenchmarkRunIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> IGNORED = Set.of(".git", ".paicli", "target");

    @Test
    void executeOneFinalSourceRun() throws Exception {
        Path project = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path materializationRoot = project.getParent().toAbsolutePath().normalize();
        Path authorityPath = Path.of(required("paicli.finalSourceV3.authority"))
                .toAbsolutePath().normalize();
        if (!authorityPath.startsWith(materializationRoot)
                || !"provider-gate.json".equals(authorityPath.getFileName().toString()))
            fail("paicli.finalSourceV3.authority escaped its bounded evidence root");
        Path evidenceRoot = authorityPath.getParent();
        Path artifactPath = requiredPath("paicli.finalSourceV3.artifact", project.getParent());
        Path overlayPath = project.resolve(required("paicli.finalSourceV3.overlay")).normalize();
        Path evaluatorPath = project.resolve(required("paicli.finalSourceV3.evaluator")).normalize();

        // This is deliberately the first benchmark authority action. No Provider object exists yet.
        JsonNode authority = FinalSourceV3Authority.verify(
                project, authorityPath, artifactPath, overlayPath, evaluatorPath);
        boolean providerDisabled = Boolean.parseBoolean(
                required("paicli.finalSourceV3.providerDisabled"));
        requireGate(authority, providerDisabled,
                FormalFinalSourceV3BenchmarkRunIT::credentialPresent);

        String runtime = required("paicli.finalSourceV3.runtime");
        if (!Set.of("PAICLI", "SIMPLE_TOOL_LOOP").contains(runtime))
            fail("unsupported final-source runtime");
        Path workspace = requiredPath("paicli.finalSourceV3.workspace", evidenceRoot);
        Path controlHome = requiredPath("paicli.finalSourceV3.controlHome", evidenceRoot);
        Path output = requiredPath("paicli.finalSourceV3.output", evidenceRoot);
        Path firstCandidate = requiredPath("paicli.finalSourceV3.firstCandidate", evidenceRoot);
        Path providerLedger = requiredPath("paicli.finalSourceV3.providerLedger", evidenceRoot);
        Path planLedger = requiredPath("paicli.finalSourceV3.planLedger", evidenceRoot);
        if (Files.exists(output) || Files.exists(firstCandidate))
            fail("final-source output/candidate path already exists");
        Path taskPath = project.resolve(required("paicli.finalSourceV3.task")).normalize();
        Path fixtureRoot = project.resolve("benchmarks/coding/fixtures").normalize();
        if (!taskPath.startsWith(fixtureRoot)
                || !"task.json".equals(taskPath.getFileName().toString()))
            fail("task manifest is outside the frozen fixture root");
        JsonNode task = JSON.readTree(taskPath.toFile());
        String taskId = task.path("taskId").asText();
        if (!taskId.matches("task-(00[1-9]|01[0-9]|02[0-4])"))
            fail("task identity is outside the frozen COMMON suite");
        if (providerDisabled && !"task-005".equals(taskId))
            fail("Provider-disabled pair rehearsal is frozen to task-005");
        String runKey = required("paicli.finalSourceV3.runKey");
        if (!runKey.contains(":" + taskId + ":"))
            fail("run key/task fixture identity mismatch");
        String prompt = Files.readString(taskPath.getParent().resolve(
                task.path("promptFile").asText()));
        Config config = taskConfig(new ConfigLoader(workspace, controlHome).load(),
                workspace, task);
        requireFrozenProvider(config);

        ModelProvider rawProvider;
        if (providerDisabled) {
            rawProvider = new FinalSourceV3ReplayProvider();
        } else {
            rawProvider = new ProviderCallLedgerModelProvider(ProviderFactory.create(config),
                    new AppendOnlyProviderCallLedger(providerLedger, evidenceRoot),
                    new ProviderCallLedgerContext(runKey,
                            runtime, config.effectiveProvider() + "/" + config.providerPreset(),
                            config.model(), authority.path("providerProtocolSha256").asText()));
        }
        AgentLoopControl approval = new PredeclaredApprovalControl(
                (iteration, call) -> "workspace-write-v1".equals(
                        task.path("approvalProfile").asText()));
        var planCompatibility = new FinalSourceV3EvidenceProjection(
                planLedger, evidenceRoot, runKey, runtime);

        Map<String, Object> facts;
        if ("SIMPLE_TOOL_LOOP".equals(runtime)) {
            var run = new SimpleToolLoopBaseline(rawProvider,
                    BenchmarkToolRegistryFactory.create(workspace),
                    new com.paicli.safety.ApprovalPolicy(config, "final-source-simple"),
                    approval, task.path("taskConstraints").path("maxProviderTurns").asInt())
                    .run(prompt);
            planCompatibility.observed(PlanCompatibilityObserver.Fact.notApplicable());
            copyCandidate(workspace, firstCandidate);
            facts = simpleFacts(run);
            facts.put("planStageOutcome", "NOT_APPLICABLE_NO_STRUCTURED_PLAN_STAGE");
            facts.put("safePreflightRejections", List.of());
            facts.put("continuityTerminalReasons", List.of());
        } else {
            FirstCandidateObserver capture = new FirstCandidateObserver(workspace, firstCandidate);
            var run = new PaiCliBenchmarkTreatment(workspace, rawProvider, config,
                    approval, capture, planCompatibility).run(prompt);
            if (!Files.exists(firstCandidate)) copyCandidate(workspace, firstCandidate);
            capture.throwIfFailed();
            facts = paicliFacts(run);
            facts.put("planStageOutcome",
                    FinalSourceV3EvidenceProjection.planningStageOutcome(run));
            facts.put("safePreflightRejections",
                    FinalSourceV3EvidenceProjection.safePreflight(workspace, run.taskId()));
            facts.put("continuityTerminalReasons",
                    FinalSourceV3EvidenceProjection.continuityTerminalReasons(
                            workspace, run.taskId()));
        }
        facts.put("schemaVersion", "formal-final-source-v3-driver-result-v1");
        facts.put("runtime", runtime);
        facts.put("taskId", task.path("taskId").asText());
        facts.put("candidateIdentity", authority.path("candidateIdentity").asText());
        facts.put("executionKind", providerDisabled ? "PAIR_REHEARSAL" : "FORMAL");
        facts.put("resultVersion", "formal-final-source-result-v3");
        facts.put("providerDisabled", providerDisabled);
        facts.put("networkProviderCalls", providerDisabled ? 0 : -1);
        facts.put("formalAggregateEligible", !providerDisabled);
        facts.put("measurementBoundary", "PRECOMPILED_ISOLATED_TEST_CLASSPATH");
        facts.put("sharedEffectfulBatchTelemetryHash",
                com.paicli.agent.AgentLoop.sharedToolBatchTelemetryHash());
        facts.put("planStageTelemetry", planCompatibility.planFacts());
        Files.createDirectories(output.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), facts);
    }

    static void requireGate(JsonNode gate, boolean providerDisabled,
            BooleanSupplier credentialPresence) {
        boolean common = "final-source-internal-controlled-regression-v3".equals(
                gate.path("campaignIdentity").asText())
                && "formal-final-source-result-v3".equals(
                        gate.path("resultIdentity").asText())
                && "formal-final-source-evidence-v3".equals(
                        gate.path("evidenceIdentity").asText())
                && gate.path("artifactSha256").asText().matches("[a-f0-9]{64}")
                && gate.path("harnessIdentity").asText()
                        .startsWith("final-source-harness-v3-")
                && gate.path("evaluatorIdentity").asText()
                        .startsWith("final-source-evaluator-v3-")
                && "final-source-v3-fixed-chunks-v1".equals(
                        gate.path("chunkScheduleIdentity").asText());
        if (!common) fail("final-source Provider gate identity is incomplete");
        JsonNode runtime = gate.path("runtimeGateEvidence");
        if (providerDisabled) {
            if (!"PAIR_REHEARSAL".equals(gate.path("mode").asText())
                    || !runtime.path("simulation").asBoolean(false)
                    || !runtime.path("networkDisabled").asBoolean(false)
                    || runtime.path("authorizationPresent").asBoolean(true)
                    || runtime.path("credentialPresent").asBoolean(true))
                fail("Provider-disabled pair gate carries paid authorization");
        } else if (!"FORMAL".equals(gate.path("mode").asText())
                || runtime.path("simulation").asBoolean(true)
                || !runtime.path("authorizationPresent").asBoolean(false)
                || !runtime.path("credentialPresent").asBoolean(false)
                || !runtime.path("preAdmissionReady").asBoolean(false)
                || runtime.path("persistentDefault").asBoolean(true)
                || runtime.path("reusable").asBoolean(true)
                || !"EXPLICIT_ONE_SHOT_RUNTIME_FLAG".equals(
                        runtime.path("authorizationKind").asText())
                || !credentialPresence.getAsBoolean()) {
            fail("paid final-source V3 Provider gate is not specifically authorized");
        }
    }

    private static boolean credentialPresent() {
        String value = System.getenv("DEEPSEEK_API_KEY");
        return value != null && !value.isBlank();
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
        facts.put("sharedEffectfulBatchTelemetry", batchTelemetry(run.effectfulBatchTelemetry()));
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
        facts.put("sharedEffectfulBatchTelemetry", batchTelemetry(run.effectfulBatchTelemetry()));
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
        if ("PROVIDER_PROTOCOL_FAILURE".equals(run.terminalFailureClass()))
            return FormalDriverResult.typedTaskFailure("PROVIDER_PROTOCOL_FAILURE",
                    FormalDriverResult.FailureCategory.PROVIDER_PROTOCOL_FAILURE);
        String summary = run.summary() == null ? "" : run.summary();
        if (summary.contains("STRUCTURED_PLAN_SCHEMA_MAPPING_FAILED")
                || summary.contains("PLAN_PROPOSAL_REJECTED"))
            return FormalDriverResult.typedTaskFailure("PLAN_COMPATIBILITY_FAILURE",
                    FormalDriverResult.FailureCategory.PLAN_COMPATIBILITY_FAILURE);
        if (run.firstStructuredVerificationPassed() == Boolean.FALSE
                || "EXHAUSTED".equals(run.repairLifecycleState()))
            return FormalDriverResult.typedTaskFailure("VERIFICATION_FAILURE",
                    FormalDriverResult.FailureCategory.VERIFICATION_FAILURE);
        return FormalDriverResult.typedTaskFailure("PAICLI_TASK_TERMINAL_FAILURE",
                FormalDriverResult.FailureCategory.INCOMPLETE_RESULT_OR_FORMAL_RESULT_MISSING);
    }

    private static List<Map<String, Object>> batchTelemetry(
            List<com.paicli.agent.AgentLoopObserver.EffectfulBatchFact> facts) {
        return facts.stream().map(fact -> Map.<String, Object>of(
                "effectfulBatchReceived", fact.effectfulBatchReceived(),
                "toolCallCount", fact.toolCallCount(),
                "normalizationApplied", fact.normalizationApplied(),
                "normalizationOutcome", fact.normalizationOutcome().name())).toList();
    }

    private static void usage(Map<String, Object> facts,
            List<ProviderCallObservation> observations) {
        if (observations.isEmpty() || observations.stream().anyMatch(value ->
                value.usageSource() != ProviderCallObservation.UsageSource.PROVIDER_REPORTED
                        || value.inputTokens() == null || value.outputTokens() == null)) {
            facts.put("inputTokens", null);
            facts.put("outputTokens", null);
            facts.put("usageSource", "NOT_AVAILABLE");
        } else {
            facts.put("inputTokens", observations.stream()
                    .mapToInt(ProviderCallObservation::inputTokens).sum());
            facts.put("outputTokens", observations.stream()
                    .mapToInt(ProviderCallObservation::outputTokens).sum());
            facts.put("usageSource", "PROVIDER_REPORTED");
        }
    }

    private static Config taskConfig(Config base, Path workspace, JsonNode task) {
        List<String> verificationCommand = JSON.convertValue(
                task.path("oracle").path("verificationCommand"),
                JSON.getTypeFactory().constructCollectionType(List.class, String.class));
        String verification = String.join(" ", verificationCommand);
        int maxTurns = task.path("taskConstraints").path("maxProviderTurns").asInt();
        return new Config(workspace.toString(), base.commandTimeoutSeconds(),
                base.maxOutputBytes(), base.maxWriteFileBytes(), Config.MODE_ON_REQUEST,
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
                || !Integer.valueOf(65_536).equals(config.effectiveContextWindowTokens()))
            fail("control config does not match frozen Provider profile");
    }

    private static String required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) fail("missing system property: " + property);
        return value;
    }

    private static Path requiredPath(String property, Path root) {
        Path value = Path.of(required(property)).toAbsolutePath().normalize();
        if (!value.startsWith(root)) fail(property + " escaped its bounded evidence root");
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

