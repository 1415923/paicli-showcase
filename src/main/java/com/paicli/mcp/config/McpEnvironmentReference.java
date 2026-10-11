package com.paicli.mcp.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.regex.Pattern;

public record McpEnvironmentReference(
        @JsonProperty("source") String source,
        @JsonProperty("name") String name) {
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");

    public McpEnvironmentReference {
        if (!"HOST_ENV".equals(source)) throw new IllegalArgumentException("environment source must be HOST_ENV");
        if (name == null || !NAME.matcher(name).matches()) throw new IllegalArgumentException("invalid environment reference name");
    }
}
