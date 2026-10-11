package com.paicli.benchmark;

import com.paicli.agent.AgentLoopControl;
import com.paicli.config.Config;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolCall;
import com.paicli.provider.ModelProvider;
import com.paicli.runtime.CodingTaskObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PaiCliBenchmarkTreatmentTest {
    private static final String PLAN = """
            {"acceptanceCriteria":["README inspected"],"assumptions":[],"risks":[],
             "verificationStrategy":"read-only evidence","steps":[
               {"title":"Inspect","definition":"Read README.md","dependsOn":[],
                "completionRule":"ALL_REQUIRED_EVIDENCE",
                "requiredEvidenceKinds":["TOOL_CALL"],"effectClass":"READ",
                "toolRequirements":[{"requirementId":"read-readme","requirementOrder":0,
                "toolName":"read_file","effectClass":"READ",
                "authoritySelector":{"kind":"EXACT_PATH","path":"README.md"},
                "minSuccesses":1,"maxOccurrences":1,
                "freshnessPolicy":"RESUME_REUSE_ONLY"}]}]}
            """;

    @TempDir Path workspace;

    @Test
    void productionTreatmentPersistsOneFormalResultAroundSharedRawProvider() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "benchmark fixture");
        ModelProvider raw = (messages, definitions) -> {
            if (definitions.isEmpty()) return ModelResponse.finalAnswer(PLAN);
            boolean observed = messages.stream().anyMatch(message ->
                    message.type() == AgentMessage.MessageType.OBSERVATION);
            return observed ? ModelResponse.finalAnswer("README benchmark fixture confirmed")
                    : ModelResponse.toolCallResponse("",
                            new ToolCall("read_file", Map.of("path", "README.md")));
        };
        Config config = new Config(workspace.toString(), 30, 50_000, 200_000,
                Config.MODE_READ_ONLY, 10_000, Config.PROVIDER_MOCK, "model", "", "",
                "system", 8, "", 120, 50_000, 1, true, false, false);

        var result = new PaiCliBenchmarkTreatment(workspace, raw, config,
                AgentLoopControl.NONE, CodingTaskObserver.NOOP)
                .run("Read README.md and report its marker.");

        assertTrue(result.taskCompleted());
        assertEquals("COMPLETED", result.runPhase());
        assertTrue(result.durableFormalResultPresent());
        assertEquals("README benchmark fixture confirmed", result.durableFormalResult());
        assertEquals(1, result.toolCalls());
        assertEquals(0, result.duplicateOccurrenceIdentities());
        assertTrue(result.providerTurns() >= 3);
    }

    @Test
    void finalInternalV4AllowsAuthoritativeAndConservativeAttemptsInOneRow() {
        var attempts = List.of(
                new PaiCliBenchmarkTreatment.ProviderAttemptUsage(
                        15_380, 0, "PROVIDER_REPORTED", "COMPLETE", 421L, 723L),
                new PaiCliBenchmarkTreatment.ProviderAttemptUsage(
                        120_025, 0, "NOT_EXPOSED", "NONE", null, null),
                new PaiCliBenchmarkTreatment.ProviderAttemptUsage(
                        120_026, 0, "NOT_EXPOSED", "NONE", null, null),
                new PaiCliBenchmarkTreatment.ProviderAttemptUsage(
                        51_885, 2, "PROVIDER_REPORTED", "COMPLETE", 660L, 1_576L));

        var summary = PaiCliBenchmarkTreatment.summarizeProviderAttemptUsage(attempts, true);

        assertEquals(4, summary.providerTurns());
        assertEquals(307_316, summary.providerLatencyMillis());
        assertEquals(2, summary.retryCount());
        assertEquals(1_081L, summary.inputTokens());
        assertEquals(2_299L, summary.outputTokens());
        assertEquals(2, summary.authoritativeAttemptCount());
        assertEquals(2, summary.conservativeAttemptCount());
        assertEquals("MIXED_AUTHORITATIVE_AND_CONSERVATIVE", summary.usageSource());
        assertThrows(IllegalStateException.class,
                () -> PaiCliBenchmarkTreatment.summarizeProviderAttemptUsage(attempts, false));
    }

    @Test
    void finalInternalV4RejectsSelfContradictoryAttemptUsage() {
        var invalid = List.of(new PaiCliBenchmarkTreatment.ProviderAttemptUsage(
                1, 0, "PROVIDER_REPORTED", "PARTIAL", 10L, null));
        var error = assertThrows(IllegalStateException.class,
                () -> PaiCliBenchmarkTreatment.summarizeProviderAttemptUsage(invalid, true));
        assertEquals("Provider attempt usage authority is invalid", error.getMessage());
    }
}
