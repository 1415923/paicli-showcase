package com.paicli.mcp;

import com.paicli.mcp.config.*;
import com.paicli.mcp.lifecycle.*;
import com.paicli.mcp.manifest.*;
import com.paicli.mcp.spike.FakeMcpServerMain;
import com.paicli.state.StateStore;

import java.nio.file.Path;
import java.util.*;

public final class R3TestSupport {
    private R3TestSupport() { }

    public static McpServerConfig config(Path root, String mode, List<String> allowed) {
        return config(root, mode, allowed, McpRuntimeLimits.defaults());
    }

    public static McpServerConfig config(Path root, String mode, List<String> allowed, McpRuntimeLimits limits) {
        Path java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toAbsolutePath();
        return new McpServerConfig("fake", true, "STDIO", java,
                List.of("-cp", System.getProperty("java.class.path"), FakeMcpServerMain.class.getName(), mode),
                root.toAbsolutePath(), List.of(), limits, allowed,
                List.of(McpServerConfig.PROTOCOL_VERSION));
    }

    public static Opened open(Path root, String mode, List<String> allowed) throws Exception {
        return open(root, mode, allowed, McpRuntimeLimits.defaults());
    }

    public static Opened open(Path root, String mode, List<String> allowed, McpRuntimeLimits limits) throws Exception {
        StateStore state = new StateStore(root);
        McpServerConfig config = config(root, mode, allowed, limits);
        McpConfigManifest manifest = new McpConfigManifestBuilder().build(config);
        var stored = state.mcpServerManifests.saveOrFindManifest(manifest);
        McpLifecycleSession lifecycle = new McpLifecycleService().initialize(UUID.randomUUID().toString(),
                stored.manifestId(), "r3", UUID.randomUUID().toString(), 1, config, manifest,
                state.mcpSessions, System.getenv());
        return new Opened(state, lifecycle);
    }

    private static boolean isWindows() { return System.getProperty("os.name", "").toLowerCase().contains("win"); }
    public record Opened(StateStore state, McpLifecycleSession lifecycle) implements AutoCloseable {
        @Override public void close() throws Exception { try { lifecycle.close(); } finally { state.close(); } }
    }
}
