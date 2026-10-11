package com.paicli.mcp.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public record McpRuntimeLimits(
        @JsonProperty("startup_timeout_ms") int startupTimeoutMs,
        @JsonProperty("request_timeout_ms") int requestTimeoutMs,
        @JsonProperty("shutdown_timeout_ms") int shutdownTimeoutMs,
        @JsonProperty("max_pending_requests") int maxPendingRequests,
        @JsonProperty("max_stdout_line_bytes") int maxStdoutLineBytes,
        @JsonProperty("max_stderr_bytes") int maxStderrBytes,
        @JsonProperty("max_catalog_pages") int maxCatalogPages,
        @JsonProperty("max_tools") int maxTools,
        @JsonProperty("max_catalog_bytes") int maxCatalogBytes,
        @JsonProperty("max_single_schema_bytes") int maxSingleSchemaBytes,
        @JsonProperty("max_output_bytes") int maxOutputBytes) {
    public static McpRuntimeLimits defaults() {
        return new McpRuntimeLimits(10_000, 30_000, 5_000, 16, 1024 * 1024,
                64 * 1024, 32, 256, 4 * 1024 * 1024, 256 * 1024, 256 * 1024);
    }

    public McpRuntimeLimits {
        range(startupTimeoutMs, 100, 120_000, "startup_timeout_ms");
        range(requestTimeoutMs, 100, 300_000, "request_timeout_ms");
        range(shutdownTimeoutMs, 100, 60_000, "shutdown_timeout_ms");
        range(maxPendingRequests, 1, 128, "max_pending_requests");
        range(maxStdoutLineBytes, 1024, 8 * 1024 * 1024, "max_stdout_line_bytes");
        range(maxStderrBytes, 1024, 1024 * 1024, "max_stderr_bytes");
        range(maxCatalogPages, 1, 128, "max_catalog_pages");
        range(maxTools, 1, 2048, "max_tools");
        range(maxCatalogBytes, 4096, 32 * 1024 * 1024, "max_catalog_bytes");
        range(maxSingleSchemaBytes, 256, 2 * 1024 * 1024, "max_single_schema_bytes");
        range(maxOutputBytes, 1024, 4 * 1024 * 1024, "max_output_bytes");
    }

    private static void range(int value, int min, int max, String field) {
        if (value < min || value > max) throw new IllegalArgumentException(field + " outside safe bounds");
    }
}
