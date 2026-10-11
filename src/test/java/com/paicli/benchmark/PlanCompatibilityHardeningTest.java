package com.paicli.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.Config;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.ModelProvider;
import com.paicli.runtime.CodingTaskObserver;
import com.paicli.runtime.PlanCompatibilityObserver;
import com.paicli.safety.ApprovalPolicy;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Offline, value-free replay of the v3 trace-derived Plan validation signature. */
class PlanCompatibilityHardeningTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir Path temporaryRoot;

    @Test
    void traceDerivedPayloadShowsStrictHistoricalFailureAndAdvisoryCompatibility()
            throws Exception {
        Path project = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path evidence = evidenceRoot(project);
        JsonNode replay = JSON.readTree(getClass().getResourceAsStream(
                "/com/paicli/benchmark/v3-plan-compatibility-redacted-replay.json"));
        String initial = JSON.writeValueAsString(replay.path("initialPayload"));
        String repaired = JSON.writeValueAsString(replay.path("repairPayload"));
        assertEquals("MODEL_PLAN_QUALITY_FAILURE",
                replay.path("rootCauseClassification").asText());

        Path fixture = project.resolve("benchmarks/coding/fixtures/task-005");
        Path paiWorkspace = evidence.resolve("paicli-workspace");
        copyTree(project.resolve("benchmarks/coding/templates/java21-maven-v1"), paiWorkspace);
        copyTree(fixture.resolve("seed"), paiWorkspace);
        Config paiConfig = config(paiWorkspace);
        var paiEvidence = new AppendOnlyPlanCompatibilityEvidence(
                evidence.resolve("paicli-plan-compatibility.jsonl"), evidence,
                "offline-plan-compatibility/PAICLI", "PAICLI");
        var paiProvider = new ScriptedProvider(initial, "offline diagnostic complete");
        var pai = new PaiCliBenchmarkTreatment(paiWorkspace, paiProvider, paiConfig,
                new PredeclaredApprovalControl((iteration, call) -> true),
                CodingTaskObserver.NOOP, paiEvidence).run("offline compatibility replay");

        // The trace-derived payload is now admitted by the DEFAULT_ADVISORY_PLAN wire. The
        // Verification is disabled for this parser-only diagnostic. Its task completion must not
        // be interpreted as benchmark task effectiveness.
        assertTrue(pai.taskCompleted());
        assertEquals(2, paiProvider.calls());
        assertEquals(0, pai.toolCalls());
        assertEquals(1, paiEvidence.facts().size());
        PlanCompatibilityObserver.Fact first = paiEvidence.facts().get(0);
        assertEquals(PlanCompatibilityObserver.ParseStatus.ACCEPTED,
                first.planParseStatus());
        assertEquals(0, first.missingRequiredFieldCount());
        assertEquals(0, first.unknownFieldCount());
        assertEquals(List.of(), first.unknownFields());
        assertEquals(0, first.normalizationCount());
        assertEquals(0, first.repairAttemptCount());
        assertEquals("ACCEPTED", first.terminalReason());

        Path repairedWorkspace = evidence.resolve("paicli-repaired-workspace");
        copyTree(project.resolve("benchmarks/coding/templates/java21-maven-v1"), repairedWorkspace);
        copyTree(fixture.resolve("seed"), repairedWorkspace);
        var repairedEvidence = new AppendOnlyPlanCompatibilityEvidence(
                evidence.resolve("paicli-repaired-plan-compatibility.jsonl"), evidence,
                "offline-plan-compatibility/PAICLI_REPAIRED", "PAICLI");
        var repairedProvider = new ScriptedProvider(repaired, "offline diagnostic complete");
        var repairedRun = new PaiCliBenchmarkTreatment(repairedWorkspace, repairedProvider,
                config(repairedWorkspace),
                new PredeclaredApprovalControl((iteration, call) -> true),
                CodingTaskObserver.NOOP, repairedEvidence)
                .run("offline repaired compatibility replay");
        assertTrue(repairedRun.taskCompleted());
        assertEquals(2, repairedProvider.calls());
        assertEquals(0, repairedRun.toolCalls());
        assertEquals(1, repairedEvidence.facts().size());
        assertEquals(PlanCompatibilityObserver.ParseStatus.ACCEPTED,
                repairedEvidence.facts().getFirst().planParseStatus());
        assertEquals("ACCEPTED", repairedEvidence.facts().getFirst().terminalReason());

        Path simpleWorkspace = evidence.resolve("simple-workspace");
        Files.createDirectories(simpleWorkspace);
        Config simpleConfig = config(simpleWorkspace);
        var simpleEvidence = new AppendOnlyPlanCompatibilityEvidence(
                evidence.resolve("simple-plan-compatibility.jsonl"), evidence,
                "offline-plan-compatibility/SIMPLE_TOOL_LOOP", "SIMPLE_TOOL_LOOP");
        var simpleProvider = new ScriptedProvider(initial);
        var simple = new SimpleToolLoopBaseline(simpleProvider, new ToolRegistry(),
                new ApprovalPolicy(simpleConfig, "offline-simple"),
                new PredeclaredApprovalControl((iteration, call) -> true), 3)
                .run("offline compatibility replay");
        simpleEvidence.observed(PlanCompatibilityObserver.Fact.notApplicable());

        assertTrue(simple.agentResult().completed());
        assertEquals(1, simpleProvider.calls());
        assertEquals(0, simple.toolCalls());
        assertEquals(PlanCompatibilityObserver.ParseStatus.NOT_APPLICABLE,
                simpleEvidence.facts().getFirst().planParseStatus());
        assertEquals("NOT_APPLICABLE_NO_STRUCTURED_PLAN_STAGE",
                simpleEvidence.facts().getFirst().terminalReason());

        if (System.getProperty("paicli.planCompatibility.outputRoot") != null) {
            Path output = evidence.resolve("replay-results.json");
            assertFalse(Files.exists(output), "compatibility replay evidence is append-only");
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("schemaVersion", "plan-compatibility-offline-replay-result-v1");
            result.put("rootCauseClassification", "MODEL_PLAN_QUALITY_FAILURE");
            result.put("payloadProvenance",
                    "TRACE_DERIVED_SCHEMA_SIGNATURE_NOT_ORIGINAL_PROVIDER_CONTENT");
            result.put("initialPayloadSha256", sha(initial));
            result.put("repairPayloadSha256", sha(repaired));
            result.put("networkProviderCalls", 0);
            result.put("offlineReplayModelInvocations", 5);
            result.put("paiCli", Map.of(
                    "outcome", "ADVISORY_PLAN_ACCEPTED_FOR_BOTH_TRACE_DERIVED_PAYLOADS",
                    "toolCalls", 0,
                    "initialTelemetry", paiEvidence.facts().stream()
                            .map(AppendOnlyPlanCompatibilityEvidence::map).toList(),
                    "repairedTelemetry", repairedEvidence.facts().stream()
                            .map(AppendOnlyPlanCompatibilityEvidence::map).toList()));
            result.put("simpleToolLoop", Map.of(
                    "outcome", "FINAL_ACCEPTED_WITHOUT_STRUCTURED_PLAN_STAGE",
                    "toolCalls", 0,
                    "telemetry", simpleEvidence.facts().stream()
                            .map(AppendOnlyPlanCompatibilityEvidence::map).toList()));
            result.put("sharedProtocolVerdict", "PAYLOAD_TRANSPORT_SHAPE_ACCEPTED");
            result.put("runtimeApplicabilityVerdict", "PAICLI_PLAN_SPECIFIC");
            result.put("formalAggregate", "DISABLED");
            result.put("effectConclusion", "FORBIDDEN_NOT_PRODUCED");
            JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), result);
        }
    }

    private Path evidenceRoot(Path project) throws Exception {
        String configured = System.getProperty("paicli.planCompatibility.outputRoot");
        if (configured == null) return temporaryRoot;
        Path value = Path.of(configured).toAbsolutePath().normalize();
        Path allowed = project.resolve("target/autumn-evidence").normalize();
        if (!value.startsWith(allowed) || value.equals(allowed) || Files.exists(value)) {
            throw new IllegalArgumentException("compatibility output must be a fresh evidence path");
        }
        Files.createDirectories(value);
        return value;
    }

    private static Config config(Path workspace) {
        return new Config(workspace.toString(), 30, 50_000, 200_000,
                Config.MODE_ON_REQUEST, 10_000, Config.PROVIDER_MOCK, "offline-replay",
                "", "", "offline Plan compatibility", 8,
                "mvn -q -o test", 120, 50_000, 1,
                true, false, false);
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

    private static String sha(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static final class ScriptedProvider implements ModelProvider {
        private final ArrayDeque<String> responses = new ArrayDeque<>();
        private int calls;

        private ScriptedProvider(String... values) {
            responses.addAll(List.of(values));
        }

        @Override
        public ModelResponse chat(List<AgentMessage> messages, List<ToolDefinition> tools) {
            calls++;
            String response = responses.poll();
            if (response == null) throw new AssertionError("offline replay exhausted");
            return ModelResponse.finalAnswer(response);
        }

        private int calls() { return calls; }
    }
}
