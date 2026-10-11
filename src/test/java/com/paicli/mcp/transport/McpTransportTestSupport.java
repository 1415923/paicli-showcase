package com.paicli.mcp.transport;

import com.paicli.mcp.config.*;
import com.paicli.mcp.manifest.*;
import com.paicli.mcp.spike.FakeMcpServerMain;

import java.nio.file.Path;
import java.util.*;

public final class McpTransportTestSupport {
    private McpTransportTestSupport() {}

    public static McpServerConfig config(Path root, String mode, List<McpEnvironmentReference> refs, McpRuntimeLimits limits) {
        Path java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toAbsolutePath();
        String classpath = System.getProperty("java.class.path");
        return new McpServerConfig("fake", true, "STDIO", java,
                List.of("-cp", classpath, FakeMcpServerMain.class.getName(), mode), root.toAbsolutePath(), refs,
                limits, List.of("echo"), List.of(McpServerConfig.PROTOCOL_VERSION));
    }

    public static McpRuntimeLimits limits(int requestMs, int shutdownMs, int pending, int line, int stderr) {
        McpRuntimeLimits d = McpRuntimeLimits.defaults();
        return new McpRuntimeLimits(d.startupTimeoutMs(), requestMs, shutdownMs, pending, line, stderr,
                d.maxCatalogPages(), d.maxTools(), d.maxCatalogBytes(), d.maxSingleSchemaBytes(), d.maxOutputBytes());
    }

    public static McpConfigManifest manifest(McpServerConfig config) throws Exception { return new McpConfigManifestBuilder().build(config); }
    public static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase().contains("win"); }
}
