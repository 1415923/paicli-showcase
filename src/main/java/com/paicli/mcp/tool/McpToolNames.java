package com.paicli.mcp.tool;

import com.paicli.mcp.manifest.McpConfigManifestBuilder;

/** Provider-safe stable alias; original server/tool identity never depends on lossy sanitizing. */
public final class McpToolNames {
    private McpToolNames() { }
    public static String alias(String server, String tool) {
        String readable = (server + "_" + tool).replaceAll("[^A-Za-z0-9_-]", "_");
        return "mcp_" + readable.substring(0, Math.min(35, readable.length())) + "_"
                + McpConfigManifestBuilder.hash(server + "\0" + tool).substring(0, 24);
    }
}
