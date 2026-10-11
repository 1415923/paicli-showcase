package com.paicli.provider.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.ModelProvider;
import com.paicli.provider.PreparedProviderTurn;
import com.paicli.provider.PreparedTurnProvider;
import com.paicli.provider.ProviderRetrySignal;
import com.paicli.provider.ProviderRetrySignalSource;
import com.paicli.provider.ProviderResponseEnvelope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ProviderCallLedgerModelProviderTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PROTOCOL_HASH = "a".repeat(64);

    @TempDir Path root;

    @Test
    void responseUsageSurvivesDownstreamDriverFailureWithoutResultInference() throws Exception {
        Path path = root.resolve("ledger.jsonl");
        ProviderCallObservation observation = reportedObservation();
        ModelProvider delegate = new ObservedProvider(observation);
        ProviderCallLedgerModelProvider provider = provider(delegate, path, "run-1", "PAICLI");

        ModelResponse response = provider.chat(List.of(AgentMessage.userGoal("fixture")), List.of());
        assertEquals(ModelResponse.ResponseType.FINAL, response.type());
        assertThrows(IllegalStateException.class, () -> {
            throw new IllegalStateException("synthetic downstream driver failure");
        });

        var recovered = AppendOnlyProviderCallLedger.recover(path);
        assertEquals(1, recovered.exactCallCount());
        assertEquals(1, recovered.terminalCallCount());
        assertEquals(1, recovered.providerReportedUsageCount());
        assertEquals(10, recovered.inputTokens());
        assertEquals(4, recovered.outputTokens());
        List<JsonNode> rows = rows(path);
        assertEquals(List.of("REQUEST_ADMITTED", "RESPONSE_RECEIVED"), rows.stream()
                .map(row -> row.path("ledgerEvent").asText()).toList());
        JsonNode received = rows.getLast();
        assertEquals("provider-evidence-hardening-v2",
                received.path("evidenceContractVersion").asText());
        assertEquals("PROVIDER_REPORTED", received.path("usageProvenance").asText());
        assertEquals(3, received.path("cacheReadTokens").asInt());
        assertEquals(200, received.path("httpStatus").asInt());
        assertEquals("resp-safe-1", received.path("responseId").asText());
        assertEquals("PROVIDER_RETURNED",
                received.path("responseIdentityProvenance").asText());
        assertTrue(received.path("responseIdentityUnavailableReason").isNull());
        assertEquals("stop", received.path("finishReason").asText());
        assertEquals("RESPONSE_RECEIVED", received.path("transportState").asText());
        assertEquals("PENDING_PRICE_SNAPSHOT",
                received.path("actualCostStatus").asText());
        assertEquals("PRICE_SNAPSHOT_REQUIRED",
                received.path("actualCostUnavailableReason").asText());
    }

    @Test
    void unavailableUsageIsExplicitAndAppendOnlyAcrossCalls() throws Exception {
        Path path = root.resolve("ledger.jsonl");
        ModelProvider delegate = new ObservedProvider(ProviderCallObservation.notExposed()
                .withResponseIdentity(null, ProviderCallObservation
                        .ResponseIdentityProvenance.PROVIDER_NOT_RETURNED), 200,
                ModelResponse.finalAnswer("done"), ProviderTransportEvidence.responseReceived(
                        200, ProviderTransportEvidence.UsageProvenance.PROVIDER_NOT_RETURNED,
                        "PROVIDER_RESPONSE_USAGE_ABSENT"));
        ProviderCallLedgerModelProvider provider = provider(delegate, path, "run-2",
                "SIMPLE_TOOL_LOOP");

        provider.chat(List.of(), List.of());
        provider.chatForOperation("TOOL_CONTINUATION", List.of(), List.of());

        var recovered = AppendOnlyProviderCallLedger.recover(path);
        assertEquals(2, recovered.exactCallCount());
        assertEquals(2, recovered.terminalCallCount());
        assertEquals(0, recovered.providerReportedUsageCount());
        assertEquals(2, recovered.usageNotAvailableCount());
        List<JsonNode> rows = rows(path);
        assertEquals(4, rows.size());
        List<JsonNode> responses = rows.stream().filter(row -> "RESPONSE_RECEIVED".equals(
                row.path("ledgerEvent").asText())).toList();
        assertEquals(2, responses.size());
        assertTrue(responses.stream()
                .allMatch(row -> "PROVIDER_NOT_RETURNED".equals(
                        row.path("usageProvenance").asText())));
        assertTrue(responses.stream()
                .allMatch(row -> "PROVIDER_RESPONSE_USAGE_ABSENT".equals(
                        row.path("actualCostUnavailableReason").asText())));
        assertTrue(responses.stream().allMatch(row -> "PROVIDER_NOT_RETURNED".equals(
                row.path("responseIdentityProvenance").asText())));
        assertTrue(responses.stream().allMatch(row -> "PROVIDER_RESPONSE_ID_ABSENT".equals(
                row.path("responseIdentityUnavailableReason").asText())));
    }

    @Test
    void adapterNotExposedResponseIdentityIsTypedAndNeverSynthesized() throws Exception {
        Path path = root.resolve("ledger.jsonl");
        ProviderCallLedgerModelProvider provider = provider(new ObservedProvider(
                ProviderCallObservation.notExposed(), 200,
                ModelResponse.finalAnswer("done"),
                ProviderTransportEvidence.responseReceived(200,
                        ProviderTransportEvidence.UsageProvenance.NOT_AVAILABLE,
                        "ADAPTER_USAGE_EVIDENCE_UNAVAILABLE")), path,
                "run-identity-not-exposed", "SIMPLE_TOOL_LOOP");

        provider.chat(List.of(), List.of());

        JsonNode terminal = rows(path).getLast();
        assertTrue(terminal.path("responseId").isNull());
        assertEquals("ADAPTER_NOT_EXPOSED",
                terminal.path("responseIdentityProvenance").asText());
        assertEquals("ADAPTER_RESPONSE_ID_NOT_EXPOSED",
                terminal.path("responseIdentityUnavailableReason").asText());
    }

    @Test
    void transportFailureStillSettlesExactAdmittedCallCount() throws Exception {
        Path path = root.resolve("ledger.jsonl");
        ModelProvider delegate = new FailedTransportProvider();
        ProviderCallLedgerModelProvider provider = provider(delegate, path, "run-3", "PAICLI");

        assertThrows(IllegalStateException.class, () -> provider.chat(List.of(), List.of()));

        var recovered = AppendOnlyProviderCallLedger.recover(path);
        assertEquals(1, recovered.exactCallCount());
        assertEquals(1, recovered.terminalCallCount());
        List<JsonNode> rows = rows(path);
        assertEquals("ATTEMPT_TERMINATED",
                rows.getLast().path("terminalLedgerState").asText());
        assertEquals("TRANSPORT_FAILED", rows.getLast().path("transportState").asText());
        assertEquals("NOT_AVAILABLE", rows.getLast().path("usageProvenance").asText());
        assertEquals("TRANSPORT_TIMEOUT",
                rows.getLast().path("actualCostUnavailableReason").asText());
    }

    @Test
    void parserFailureHasReceivedTransportAndExplicitUnavailableReason() throws Exception {
        Path path = root.resolve("ledger.jsonl");
        ModelProvider delegate = new ObservedProvider(ProviderCallObservation.notExposed()
                .withError("PROVIDER_RESPONSE_PARSE_FAILURE", false), 200,
                ModelResponse.providerError(
                        ModelResponse.ProviderErrorKind.PROVIDER_RESPONSE_PARSE_FAILURE),
                ProviderTransportEvidence.responseReceived(200,
                        ProviderTransportEvidence.UsageProvenance.NOT_AVAILABLE,
                        "RESPONSE_PARSE_FAILURE"));

        provider(delegate, path, "run-parser", "PAICLI").chat(List.of(), List.of());

        JsonNode terminal = rows(path).getLast();
        assertEquals("RESPONSE_RECEIVED", terminal.path("transportState").asText());
        assertEquals("NOT_AVAILABLE", terminal.path("usageProvenance").asText());
        assertEquals("RESPONSE_PARSE_FAILURE",
                terminal.path("usageUnavailableReason").asText());
        assertEquals("RESPONSE_PARSE_FAILURE",
                terminal.path("actualCostUnavailableReason").asText());
    }

    @Test
    void adapterPreparationFailureIsNotMisclassifiedAsProviderResponse() throws Exception {
        Path path = root.resolve("ledger.jsonl");
        ProviderCallObservation observation = ProviderCallObservation.notExposed()
                .withError("TOOL_RESULT_BATCH_CONTINUATION_MISMATCH", false);
        ModelProvider delegate = new ObservedProvider(observation, null,
                ModelResponse.providerError(ModelResponse.ProviderErrorKind
                        .TOOL_RESULT_BATCH_CONTINUATION_MISMATCH),
                ProviderTransportEvidence.preTransportRejected(
                        "TOOL_RESULT_BATCH_CONTINUATION_MISMATCH"));

        provider(delegate, path, "run-pre-transport", "PAICLI")
                .chatForOperation("TOOL_CONTINUATION", List.of(), List.of());

        JsonNode terminal = rows(path).getLast();
        assertEquals("ATTEMPT_TERMINATED", terminal.path("ledgerEvent").asText());
        assertEquals("PRE_TRANSPORT_REJECTED", terminal.path("transportState").asText());
        assertTrue(terminal.path("httpStatus").isNull());
        assertEquals("NOT_APPLICABLE_NO_REQUEST_SENT",
                terminal.path("usageProvenance").asText());
        assertEquals(0.0d, terminal.path("actualCostUsd").asDouble());
        assertEquals("EXACT_ZERO_NO_REQUEST_SENT",
                terminal.path("actualCostStatus").asText());
        assertEquals("NOT_APPLICABLE_NO_REQUEST_SENT",
                terminal.path("actualCostUnavailableReason").asText());
        assertEquals("TOOL_RESULT_BATCH_CONTINUATION_MISMATCH",
                terminal.path("errorCategory").asText());
    }

    @Test
    void processInterruptionRecoverySynthesizesUnknownTerminalEvidence() throws Exception {
        Path path = root.resolve("ledger.jsonl");
        AppendOnlyProviderCallLedger ledger = new AppendOnlyProviderCallLedger(path, root);
        ProviderCallLedgerContext context = new ProviderCallLedgerContext(
                "run-interrupted", "PAICLI", "openai/deepseek",
                "deepseek-v4-flash", PROTOCOL_HASH);
        ledger.requestAdmitted("pcall-" + "1".repeat(32), context, 1,
                "CHAT", Instant.parse("2026-08-09T00:00:00Z"));

        var recovered = AppendOnlyProviderCallLedger.recoverEvidence(path);

        assertEquals(1, recovered.exactCallCount());
        var attempt = recovered.attempts().getFirst();
        assertEquals("pcall-" + "1".repeat(32), attempt.providerCallId());
        assertEquals("UNKNOWN_AFTER_PROCESS_INTERRUPTION", attempt.transportState());
        assertEquals("NOT_AVAILABLE", attempt.usageProvenance());
        assertEquals("PROCESS_INTERRUPTION_BEFORE_TERMINAL_EVIDENCE",
                attempt.actualCostUnavailableReason());
        assertEquals("ADAPTER_NOT_EXPOSED", attempt.responseIdentityProvenance());
        assertNull(attempt.responseId());
        assertFalse(attempt.terminalEvidencePersisted());
    }

    @Test
    void ledgerNeverPersistsPromptResponseOrPrivateReasoning() throws Exception {
        Path path = root.resolve("ledger.jsonl");
        ProviderCallLedgerModelProvider provider = provider(
                new ObservedProvider(reportedObservation()), path, "run-4", "PAICLI");

        provider.chat(List.of(AgentMessage.userGoal("private-prompt-sentinel")), List.of());

        String persisted = Files.readString(path);
        assertFalse(persisted.contains("private-prompt-sentinel"));
        assertFalse(persisted.contains("reasoning_content"));
        assertFalse(persisted.contains("Bearer"));
        assertFalse(persisted.contains("api_key"));
    }

    @Test
    void transportHttpStatusIsPersistedEvenForTypedProviderFailure() throws Exception {
        Path path = root.resolve("ledger.jsonl");
        ObservedProvider delegate = new ObservedProvider(
                ProviderCallObservation.notExposed(), 429,
                ModelResponse.providerError(
                        ModelResponse.ProviderErrorKind.PROVIDER_RATE_LIMITED));
        ProviderCallLedgerModelProvider provider = provider(delegate, path, "run-5", "PAICLI");

        provider.chat(List.of(), List.of());

        assertEquals(429, rows(path).getLast().path("httpStatus").asInt());
    }

    @Test
    void preparedRequestDigestAndTypedRetrySignalAreNotHiddenByLedger() {
        Path path = root.resolve("ledger.jsonl");
        PreparedObservedProvider delegate = new PreparedObservedProvider();
        ProviderCallLedgerModelProvider provider = provider(delegate, path, "run-6", "PAICLI");

        PreparedProviderTurn prepared = provider.prepareTurn("CHAT", List.of(), List.of());
        assertEquals("b".repeat(64), prepared.requestDigest());
        assertEquals(1, delegate.prepares.get());
        prepared.execute();
        prepared.execute();

        assertEquals(2, delegate.executions.get());
        assertEquals(2, AppendOnlyProviderCallLedger.recover(path).exactCallCount());
        assertEquals(ProviderRetrySignal.Reason.HTTP_503,
                provider.takeRetrySignal().reason());
    }

    private ProviderCallLedgerModelProvider provider(ModelProvider delegate, Path path,
            String runId, String runtime) {
        return new ProviderCallLedgerModelProvider(delegate,
                new AppendOnlyProviderCallLedger(path, root),
                new ProviderCallLedgerContext(runId, runtime, "openai/deepseek",
                        "deepseek-v4-flash", PROTOCOL_HASH));
    }

    private static ProviderCallObservation reportedObservation() {
        return new ProviderCallObservation(10, 4, 14, 3, 0, 2,
                ProviderCallObservation.UsageSource.PROVIDER_REPORTED,
                ProviderCallObservation.UsageCoverage.COMPLETE, 0,
                ProviderResponseEnvelope.ToolCallCardinality.ZERO, "stop", "resp-safe-1",
                null, false);
    }

    private static List<JsonNode> rows(Path path) throws Exception {
        return Files.readAllLines(path).stream().filter(line -> !line.isBlank()).map(line -> {
            try { return JSON.readTree(line); }
            catch (Exception error) { throw new IllegalStateException(error); }
        }).toList();
    }

    private static final class ObservedProvider
            implements ModelProvider, ProviderObservationSource, ProviderHttpStatusSource,
            ProviderTransportEvidenceSource {
        private final ProviderCallObservation observation;
        private final Integer httpStatus;
        private final ProviderTransportEvidence transportEvidence;
        private final ModelResponse response;
        private ObservedProvider(ProviderCallObservation observation) {
            this(observation, 200, ModelResponse.finalAnswer("private-response"),
                    ProviderTransportEvidence.responseReceived(200,
                            ProviderTransportEvidence.UsageProvenance.PROVIDER_REPORTED,
                            null));
        }
        private ObservedProvider(ProviderCallObservation observation, Integer httpStatus,
                ModelResponse response) {
            this(observation, httpStatus, response,
                    ProviderTransportEvidence.responseReceived(httpStatus,
                            ProviderTransportEvidence.UsageProvenance.NOT_AVAILABLE,
                            "HTTP_ERROR_USAGE_NOT_INSPECTED"));
        }
        private ObservedProvider(ProviderCallObservation observation, Integer httpStatus,
                ModelResponse response, ProviderTransportEvidence transportEvidence) {
            this.observation = observation;
            this.httpStatus = httpStatus;
            this.response = response;
            this.transportEvidence = transportEvidence;
        }
        @Override public ModelResponse chat(List<AgentMessage> messages,
                List<ToolDefinition> tools) { return response; }
        @Override public ProviderCallObservation takeObservation() {
            return observation;
        }
        @Override public Integer takeHttpStatus() {
            return httpStatus;
        }
        @Override public ProviderTransportEvidence takeTransportEvidence() {
            return transportEvidence;
        }
    }

    private static final class PreparedObservedProvider implements PreparedTurnProvider,
            ProviderObservationSource, ProviderRetrySignalSource, ProviderHttpStatusSource,
            ProviderTransportEvidenceSource {
        private final AtomicInteger prepares = new AtomicInteger();
        private final AtomicInteger executions = new AtomicInteger();
        @Override public PreparedProviderTurn prepareTurn(String operation,
                List<AgentMessage> messages, List<ToolDefinition> tools) {
            prepares.incrementAndGet();
            return new PreparedProviderTurn("b".repeat(64), () -> {
                executions.incrementAndGet();
                return ModelResponse.providerError(
                        ModelResponse.ProviderErrorKind.PROVIDER_SERVER_FAILURE);
            });
        }
        @Override public ProviderCallObservation takeObservation() {
            return ProviderCallObservation.notExposed();
        }
        @Override public ProviderRetrySignal takeRetrySignal() {
            return new ProviderRetrySignal(ProviderRetrySignal.Reason.HTTP_503,
                    true, null, "HTTP_503");
        }
        @Override public Integer takeHttpStatus() { return 503; }
        @Override public ProviderTransportEvidence takeTransportEvidence() {
            return ProviderTransportEvidence.responseReceived(503,
                    ProviderTransportEvidence.UsageProvenance.NOT_AVAILABLE,
                    "HTTP_ERROR_USAGE_NOT_INSPECTED");
        }
    }

    private static final class FailedTransportProvider
            implements ModelProvider, ProviderTransportEvidenceSource {
        @Override public ModelResponse chat(List<AgentMessage> messages,
                List<ToolDefinition> tools) {
            throw new IllegalStateException("synthetic transport timeout");
        }
        @Override public ProviderTransportEvidence takeTransportEvidence() {
            return ProviderTransportEvidence.transportFailed("TRANSPORT_TIMEOUT");
        }
    }
}
