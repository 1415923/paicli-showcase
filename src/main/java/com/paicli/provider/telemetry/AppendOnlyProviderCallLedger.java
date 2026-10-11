package com.paicli.provider.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;

/** Durable append-only Provider-call facts; it never stores prompts, responses or credentials. */
public final class AppendOnlyProviderCallLedger {
    public static final String SCHEMA_VERSION = "provider-call-ledger-v2";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path ledger;

    public AppendOnlyProviderCallLedger(Path ledger, Path allowedRoot) {
        Path resolved = ledger.toAbsolutePath().normalize();
        Path allowed = allowedRoot.toAbsolutePath().normalize();
        if (!resolved.startsWith(allowed) || resolved.equals(allowed)) {
            throw new IllegalArgumentException("Provider ledger must be below its allowed root");
        }
        this.ledger = resolved;
    }

    public void requestAdmitted(String providerCallId, ProviderCallLedgerContext context,
            int requestOrdinal, String operation, Instant requestTimestamp) {
        ObjectNode row = base(providerCallId, context, requestOrdinal, operation);
        row.put("requestTimestamp", requestTimestamp.toString());
        row.putNull("responseTimestamp");
        row.putNull("responseId");
        row.putNull("responseIdentityProvenance");
        row.put("responseIdentityUnavailableReason", "ATTEMPT_IN_FLIGHT");
        row.putNull("httpStatus");
        row.putNull("finishReason");
        usage(row, ProviderCallObservation.notExposed(), "NOT_AVAILABLE");
        row.put("transportState", ProviderTransportEvidence.TransportState.NOT_STARTED.name());
        row.put("usageUnavailableReason", "ATTEMPT_IN_FLIGHT");
        row.putNull("actualCostUsd");
        row.put("actualCostStatus", "NOT_AVAILABLE");
        row.put("actualCostUnavailableReason", "ATTEMPT_IN_FLIGHT");
        row.put("ledgerEvent", "REQUEST_ADMITTED");
        row.put("terminalLedgerState", "IN_FLIGHT");
        append(row);
    }

    public void attemptTerminated(String providerCallId, ProviderCallLedgerContext context,
            int requestOrdinal, String operation, Instant requestTimestamp,
            Instant responseTimestamp, ProviderTransportEvidence transport,
            ProviderCallObservation observation, String terminalErrorCategory) {
        ProviderCallObservation safe = observation == null
                ? ProviderCallObservation.notExposed() : observation;
        ProviderTransportEvidence evidence = transport == null
                ? ProviderTransportEvidence.unknownTerminal(
                        "TRANSPORT_EVIDENCE_SOURCE_UNAVAILABLE") : transport;
        ObjectNode row = base(providerCallId, context, requestOrdinal, operation);
        row.put("requestTimestamp", requestTimestamp.toString());
        row.put("responseTimestamp", responseTimestamp.toString());
        putNullable(row, "responseId", safe.safeRequestId());
        row.put("responseIdentityProvenance",
                safe.responseIdentityProvenance().name());
        putNullable(row, "responseIdentityUnavailableReason",
                responseIdentityUnavailableReason(safe));
        if (evidence.httpStatus() == null) row.putNull("httpStatus");
        else row.put("httpStatus", evidence.httpStatus());
        putNullable(row, "finishReason", safe.finishReason());
        String provenance = settledUsageProvenance(evidence, safe);
        String unavailableReason = settledUnavailableReason(evidence, safe, provenance);
        usage(row, safe, provenance);
        row.put("transportState", evidence.transportState().name());
        putNullable(row, "usageUnavailableReason", unavailableReason);
        boolean noRequestSent = evidence.transportState()
                == ProviderTransportEvidence.TransportState.PRE_TRANSPORT_REJECTED;
        if (noRequestSent) {
            row.put("actualCostUsd", 0.0d);
            row.put("actualCostStatus", "EXACT_ZERO_NO_REQUEST_SENT");
            row.put("actualCostUnavailableReason", "NOT_APPLICABLE_NO_REQUEST_SENT");
        } else if ("PROVIDER_REPORTED".equals(provenance)) {
            row.putNull("actualCostUsd");
            row.put("actualCostStatus", "PENDING_PRICE_SNAPSHOT");
            row.put("actualCostUnavailableReason", "PRICE_SNAPSHOT_REQUIRED");
        } else {
            row.putNull("actualCostUsd");
            row.put("actualCostStatus", "NOT_AVAILABLE");
            putNullable(row, "actualCostUnavailableReason", unavailableReason);
        }
        String category = safe.errorCategory() != null
                ? safe.errorCategory() : safeEnum(terminalErrorCategory, 64);
        putNullable(row, "errorCategory", category);
        boolean received = evidence.transportState()
                == ProviderTransportEvidence.TransportState.RESPONSE_RECEIVED;
        row.put("ledgerEvent", received ? "RESPONSE_RECEIVED" : "ATTEMPT_TERMINATED");
        row.put("terminalLedgerState", received ? "RESPONSE_RECEIVED" : "ATTEMPT_TERMINATED");
        append(row);
    }

    public Path path() { return ledger; }

    public static RecoverySummary recover(Path ledger) {
        if (!Files.isRegularFile(ledger)) return new RecoverySummary(0, 0, 0, 0, 0, 0);
        Set<String> admitted = new HashSet<>();
        Set<String> terminal = new HashSet<>();
        long input = 0;
        long output = 0;
        int reported = 0;
        int unavailable = 0;
        try {
            for (String line : Files.readAllLines(ledger, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                JsonNode row = JSON.readTree(line);
                String id = row.path("providerCallId").asText();
                if ("REQUEST_ADMITTED".equals(row.path("ledgerEvent").asText())) admitted.add(id);
                if (!"IN_FLIGHT".equals(row.path("terminalLedgerState").asText())) terminal.add(id);
                if (!"IN_FLIGHT".equals(row.path("terminalLedgerState").asText())) {
                    if ("PROVIDER_REPORTED".equals(row.path("usageProvenance").asText())) {
                        reported++;
                        if (row.path("inputTokens").isNumber()) input += row.path("inputTokens").asLong();
                        if (row.path("outputTokens").isNumber()) output += row.path("outputTokens").asLong();
                    } else unavailable++;
                }
            }
        } catch (IOException error) {
            throw new IllegalStateException("Provider ledger cannot be recovered", error);
        }
        return new RecoverySummary(admitted.size(), terminal.size(), reported, unavailable,
                input, output);
    }

    public record RecoverySummary(int exactCallCount, int terminalCallCount,
            int providerReportedUsageCount, int usageNotAvailableCount,
            long inputTokens, long outputTokens) { }

    /** Recovery view gives every admitted identity a terminal transport classification. */
    public static EvidenceRecoverySummary recoverEvidence(Path ledger) {
        if (!Files.isRegularFile(ledger)) return new EvidenceRecoverySummary(List.of());
        Map<String, JsonNode> admitted = new LinkedHashMap<>();
        Map<String, JsonNode> terminal = new HashMap<>();
        try {
            for (String line : Files.readAllLines(ledger, StandardCharsets.UTF_8)) {
                if (line.isBlank()) continue;
                JsonNode row = JSON.readTree(line);
                String id = row.path("providerCallId").asText();
                if ("REQUEST_ADMITTED".equals(row.path("ledgerEvent").asText())) {
                    admitted.putIfAbsent(id, row);
                }
                if (!"IN_FLIGHT".equals(row.path("terminalLedgerState").asText())) {
                    terminal.put(id, row);
                }
            }
        } catch (IOException error) {
            throw new IllegalStateException("Provider ledger cannot be recovered", error);
        }
        List<RecoveredAttempt> attempts = admitted.keySet().stream().map(id -> {
            JsonNode row = terminal.get(id);
            if (row == null) return new RecoveredAttempt(id,
                    ProviderTransportEvidence.TransportState
                            .UNKNOWN_AFTER_PROCESS_INTERRUPTION.name(),
                    "NOT_AVAILABLE", "PROCESS_INTERRUPTION_BEFORE_TERMINAL_EVIDENCE",
                    "NOT_AVAILABLE", "PROCESS_INTERRUPTION_BEFORE_TERMINAL_EVIDENCE",
                    null, "ADAPTER_NOT_EXPOSED",
                    "PROCESS_INTERRUPTION_BEFORE_TERMINAL_EVIDENCE", false);
            String identityProvenance = row.path("responseIdentityProvenance")
                    .asText("ADAPTER_NOT_EXPOSED");
            String identityReason = nullableText(row,
                    "responseIdentityUnavailableReason");
            if (!row.has("responseIdentityProvenance")) {
                identityReason = "LEGACY_LEDGER_IDENTITY_PROVENANCE_UNAVAILABLE";
            }
            return new RecoveredAttempt(id, row.path("transportState").asText(
                    ProviderTransportEvidence.TransportState.UNKNOWN_TERMINAL.name()),
                    row.path("usageProvenance").asText("NOT_AVAILABLE"),
                    nullableText(row, "usageUnavailableReason"),
                    row.path("actualCostStatus").asText("NOT_AVAILABLE"),
                    nullableText(row, "actualCostUnavailableReason"),
                    nullableText(row, "responseId"), identityProvenance,
                    identityReason, true);
        }).toList();
        return new EvidenceRecoverySummary(attempts);
    }

    public record EvidenceRecoverySummary(List<RecoveredAttempt> attempts) {
        public EvidenceRecoverySummary {
            attempts = List.copyOf(attempts);
        }
        public int exactCallCount() { return attempts.size(); }
    }

    public record RecoveredAttempt(String providerCallId, String transportState,
            String usageProvenance, String usageUnavailableReason,
            String actualCostStatus, String actualCostUnavailableReason,
            String responseId, String responseIdentityProvenance,
            String responseIdentityUnavailableReason,
            boolean terminalEvidencePersisted) { }

    private static ObjectNode base(String providerCallId, ProviderCallLedgerContext context,
            int requestOrdinal, String operation) {
        if (providerCallId == null || !providerCallId.matches("pcall-[a-f0-9]{32}")) {
            throw new IllegalArgumentException("unsafe providerCallId");
        }
        if (requestOrdinal < 1) throw new IllegalArgumentException("requestOrdinal must be positive");
        ObjectNode row = JSON.createObjectNode();
        row.put("schemaVersion", SCHEMA_VERSION);
        row.put("evidenceContractVersion", "provider-evidence-hardening-v2");
        row.put("providerCallId", providerCallId);
        row.put("runId", context.runId());
        row.put("runtime", context.runtime());
        row.put("requestOrdinal", requestOrdinal);
        row.put("operation", safeEnum(operation, 64));
        row.put("provider", context.provider());
        row.put("model", context.model());
        row.put("protocolHash", context.protocolHash());
        row.putNull("errorCategory");
        return row;
    }

    private static void usage(ObjectNode row, ProviderCallObservation value, String provenance) {
        putNullable(row, "inputTokens", value.inputTokens());
        putNullable(row, "outputTokens", value.outputTokens());
        putNullable(row, "cacheReadTokens", value.cacheReadTokens());
        putNullable(row, "cacheWriteTokens", value.cacheWriteTokens());
        putNullable(row, "reasoningTokens", value.reasoningTokens());
        row.put("usageProvenance", provenance);
    }

    private static String settledUsageProvenance(ProviderTransportEvidence evidence,
            ProviderCallObservation value) {
        if (evidence.usageProvenance()
                == ProviderTransportEvidence.UsageProvenance.PROVIDER_REPORTED
                && value.usageSource() == ProviderCallObservation.UsageSource.PROVIDER_REPORTED
                && value.usageCoverage() == ProviderCallObservation.UsageCoverage.COMPLETE
                && value.inputTokens() != null && value.outputTokens() != null) {
            return "PROVIDER_REPORTED";
        }
        if (evidence.usageProvenance()
                == ProviderTransportEvidence.UsageProvenance.PROVIDER_NOT_RETURNED) {
            return "PROVIDER_NOT_RETURNED";
        }
        if (evidence.usageProvenance()
                == ProviderTransportEvidence.UsageProvenance.NOT_APPLICABLE_NO_REQUEST_SENT) {
            return "NOT_APPLICABLE_NO_REQUEST_SENT";
        }
        return "NOT_AVAILABLE";
    }

    private static String settledUnavailableReason(ProviderTransportEvidence evidence,
            ProviderCallObservation value, String provenance) {
        if ("PROVIDER_REPORTED".equals(provenance)) return null;
        if (evidence.usageProvenance()
                == ProviderTransportEvidence.UsageProvenance.PROVIDER_REPORTED
                && (value.usageSource() != ProviderCallObservation.UsageSource.PROVIDER_REPORTED
                    || value.usageCoverage() != ProviderCallObservation.UsageCoverage.COMPLETE
                    || value.inputTokens() == null || value.outputTokens() == null)) {
            return "PROVIDER_USAGE_CAPTURE_INCOMPLETE";
        }
        return evidence.unavailableReason();
    }

    private static String nullableText(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value == null || value.isNull() || !value.isTextual() ? null : value.textValue();
    }

    private static String responseIdentityUnavailableReason(
            ProviderCallObservation observation) {
        if (observation.safeRequestId() != null) return null;
        return switch (observation.responseIdentityProvenance()) {
            case PROVIDER_RETURNED -> "PROVIDER_RESPONSE_ID_UNSAFE_NOT_PERSISTED";
            case PROVIDER_NOT_RETURNED -> "PROVIDER_RESPONSE_ID_ABSENT";
            case ADAPTER_NOT_EXPOSED -> "ADAPTER_RESPONSE_ID_NOT_EXPOSED";
        };
    }

    private synchronized void append(ObjectNode row) {
        try {
            Files.createDirectories(ledger.getParent());
            byte[] payload = (JSON.writeValueAsString(row) + "\n").getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(ledger, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                 var ignored = channel.lock()) {
                channel.write(ByteBuffer.wrap(payload));
                channel.force(true);
            }
        } catch (IOException error) {
            throw new IllegalStateException("Provider ledger append failed", error);
        }
    }

    private static void putNullable(ObjectNode row, String field, String value) {
        if (value == null) row.putNull(field); else row.put(field, value);
    }

    private static void putNullable(ObjectNode row, String field, Integer value) {
        if (value == null) row.putNull(field); else row.put(field, value);
    }

    private static String safeEnum(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String clean = value.trim();
        return clean.length() <= max && clean.matches("[A-Za-z0-9._:-]+") ? clean : null;
    }
}
