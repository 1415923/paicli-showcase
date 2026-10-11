package com.paicli.mcp.transport;

import com.paicli.mcp.config.McpEnvironmentReference;
import com.paicli.mcp.config.McpServerConfig;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class McpProcessEnvironment {
    private static final String[] WINDOWS_BASELINE = {"SystemRoot", "WINDIR", "TEMP", "TMP"};

    public Map<String, String> resolve(McpServerConfig config, Map<String, String> host) throws McpTransportException {
        Map<String, String> result = new LinkedHashMap<>();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            for (String name : WINDOWS_BASELINE) copyIfPresent(host, result, name);
        }
        for (McpEnvironmentReference reference : config.environmentReferences()) {
            String value = host.get(reference.name());
            if (value == null) throw new McpTransportException("ENVIRONMENT_REFERENCE_MISSING", "configured environment reference is unavailable");
            result.put(reference.name(), value);
        }
        result.put("GIT_TERMINAL_PROMPT", "0");
        return result;
    }

    private static void copyIfPresent(Map<String, String> host, Map<String, String> target, String name) {
        String value = host.get(name);
        if (value != null) target.put(name, value);
    }
}
