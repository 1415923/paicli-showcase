package com.paicli.mcp.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashSet;
import java.util.List;

public record McpClientConfig(@JsonProperty("servers") List<McpServerConfig> servers) {
    public McpClientConfig {
        servers = servers == null ? List.of() : List.copyOf(servers);
        var ids = servers.stream().map(McpServerConfig::serverId).toList();
        if (new HashSet<>(ids).size() != ids.size()) throw new IllegalArgumentException("duplicate server_id");
    }
}
