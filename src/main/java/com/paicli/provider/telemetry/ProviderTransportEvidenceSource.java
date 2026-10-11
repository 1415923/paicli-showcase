package com.paicli.provider.telemetry;

/** One-shot source for redacted evidence from the adapter/transport boundary. */
public interface ProviderTransportEvidenceSource {
    ProviderTransportEvidence takeTransportEvidence();
}
