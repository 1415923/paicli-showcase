package com.paicli.provider.telemetry;

/** Optional one-shot HTTP response status exposed without response content. */
public interface ProviderHttpStatusSource {
    Integer takeHttpStatus();
}
