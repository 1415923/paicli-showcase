package com.paicli.provider.telemetry;

/** Synchronous adapter boundary for consuming one normalized observation. */
public interface ProviderObservationSource {
    ProviderCallObservation takeObservation();
}
