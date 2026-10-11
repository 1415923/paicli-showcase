package com.paicli.provider.telemetry;

import java.util.Objects;

/** Stable public correlation for a shared Provider transport call ledger. */
public record ProviderCallLedgerContext(String runId, String runtime, String provider,
        String model, String protocolHash) {
    public ProviderCallLedgerContext {
        runId = bounded(runId, "runId", 256);
        runtime = bounded(runtime, "runtime", 64);
        provider = bounded(provider, "provider", 128);
        model = bounded(model, "model", 128);
        protocolHash = bounded(protocolHash, "protocolHash", 128);
        if (!protocolHash.matches("[a-fA-F0-9]{64}")) {
            throw new IllegalArgumentException("protocolHash must be SHA-256 hex");
        }
    }

    private static String bounded(String value, String field, int max) {
        Objects.requireNonNull(value, field);
        String clean = value.trim();
        if (clean.isEmpty() || clean.length() > max
                || !clean.matches("[A-Za-z0-9._:/-]+")) {
            throw new IllegalArgumentException("unsafe " + field);
        }
        return clean;
    }
}
