package com.paicli.provider.telemetry;

import java.util.Objects;

/** Redacted terminal transport evidence for exactly one Provider attempt. */
public record ProviderTransportEvidence(
        TransportState transportState,
        UsageProvenance usageProvenance,
        String unavailableReason,
        Integer httpStatus) {

    public enum TransportState {
        NOT_STARTED,
        PRE_TRANSPORT_REJECTED,
        REQUEST_SENT,
        RESPONSE_RECEIVED,
        TRANSPORT_FAILED,
        INTERRUPTED,
        UNKNOWN_TERMINAL,
        UNKNOWN_AFTER_PROCESS_INTERRUPTION
    }

    public enum UsageProvenance {
        PROVIDER_REPORTED,
        PROVIDER_NOT_RETURNED,
        NOT_APPLICABLE_NO_REQUEST_SENT,
        NOT_AVAILABLE
    }

    public ProviderTransportEvidence {
        Objects.requireNonNull(transportState, "transportState");
        Objects.requireNonNull(usageProvenance, "usageProvenance");
        unavailableReason = safeReason(unavailableReason);
        if (transportState == TransportState.RESPONSE_RECEIVED && httpStatus == null) {
            throw new IllegalArgumentException("received response requires HTTP status");
        }
        if (usageProvenance == UsageProvenance.PROVIDER_REPORTED
                && transportState != TransportState.RESPONSE_RECEIVED) {
            throw new IllegalArgumentException("Provider-reported usage requires a response");
        }
        if (usageProvenance != UsageProvenance.PROVIDER_REPORTED
                && unavailableReason == null) {
            throw new IllegalArgumentException("unavailable usage requires a typed reason");
        }
    }

    public static ProviderTransportEvidence notStarted(String reason) {
        return new ProviderTransportEvidence(TransportState.NOT_STARTED,
                UsageProvenance.NOT_AVAILABLE, reason, null);
    }

    public static ProviderTransportEvidence preTransportRejected(String reason) {
        return new ProviderTransportEvidence(TransportState.PRE_TRANSPORT_REJECTED,
                UsageProvenance.NOT_APPLICABLE_NO_REQUEST_SENT, reason, null);
    }

    public static ProviderTransportEvidence requestSent() {
        return new ProviderTransportEvidence(TransportState.REQUEST_SENT,
                UsageProvenance.NOT_AVAILABLE, "RESPONSE_PENDING", null);
    }

    public static ProviderTransportEvidence responseReceived(int status,
            UsageProvenance provenance, String reason) {
        return new ProviderTransportEvidence(TransportState.RESPONSE_RECEIVED,
                provenance, provenance == UsageProvenance.PROVIDER_REPORTED ? null : reason,
                status);
    }

    public static ProviderTransportEvidence transportFailed(String reason) {
        return new ProviderTransportEvidence(TransportState.TRANSPORT_FAILED,
                UsageProvenance.NOT_AVAILABLE, reason, null);
    }

    public static ProviderTransportEvidence interrupted(String reason) {
        return new ProviderTransportEvidence(TransportState.INTERRUPTED,
                UsageProvenance.NOT_AVAILABLE, reason, null);
    }

    public static ProviderTransportEvidence unknownTerminal(String reason) {
        return new ProviderTransportEvidence(TransportState.UNKNOWN_TERMINAL,
                UsageProvenance.NOT_AVAILABLE, reason, null);
    }

    private static String safeReason(String value) {
        if (value == null || value.isBlank()) return null;
        String clean = value.trim();
        if (clean.length() > 96 || !clean.matches("[A-Z0-9_]+")) {
            throw new IllegalArgumentException("unsafe transport evidence reason");
        }
        return clean;
    }
}
