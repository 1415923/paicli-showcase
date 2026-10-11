package com.paicli.provider;

import java.util.List;

public record ProviderHealthReport(
        String provider, String endpointLabel, boolean endpointValid,
        boolean reachable, boolean modelConfigured, boolean modelDiscovered,
        boolean environmentPresent, boolean ready, List<String> models,
        String errorCategory) {
    public ProviderHealthReport {
        models = models == null ? List.of() : List.copyOf(models);
        errorCategory = errorCategory == null ? "" : errorCategory;
    }
}
