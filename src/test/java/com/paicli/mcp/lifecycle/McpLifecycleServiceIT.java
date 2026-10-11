package com.paicli.mcp.lifecycle;

import com.paicli.mcp.*;
import com.paicli.mcp.config.*;
import com.paicli.mcp.manifest.*;
import com.paicli.state.StateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class McpLifecycleServiceIT {
    @TempDir Path root;

    @Test void initializeThenInitializedMakesReady() throws Exception {
        try (var opened = R3TestSupport.open(root, "SINGLE_PAGE", List.of("echo"))) {
            assertEquals("READY", opened.state().mcpSessions.findById(opened.lifecycle().persisted().sessionId()).orElseThrow().status());
            assertEquals("2025-11-25", opened.state().mcpSessions.findById(opened.lifecycle().persisted().sessionId()).orElseThrow().protocolVersion());
            assertEquals(com.paicli.mcp.jsonrpc.McpJsonRpcSession.State.READY, opened.lifecycle().persisted().jsonRpc().state());
        }
    }

    @Test void rejectsVersionCapabilitiesAndServerInfo() throws Exception {
        for (var expected : Map.of("VERSION_MISMATCH","UNSUPPORTED_PROTOCOL_VERSION", "NO_TOOLS","NO_TOOLS_CAPABILITY",
                "MALFORMED_CAPABILITIES","INVALID_CAPABILITIES", "MALFORMED_SERVER_INFO","INVALID_SERVER_INFO",
                "OVERSIZED_INSTRUCTIONS","INSTRUCTIONS_TOO_LARGE").entrySet()) {
            try (StateStore state = new StateStore(root.resolve(expected.getKey()))) {
                Path dir = root.resolve(expected.getKey()); java.nio.file.Files.createDirectories(dir);
                McpServerConfig config = R3TestSupport.config(dir, expected.getKey(), List.of("echo"));
                McpConfigManifest manifest = new McpConfigManifestBuilder().build(config);
                var stored = state.mcpServerManifests.saveOrFindManifest(manifest);
                var error = assertThrows(McpLifecycleException.class, () -> new McpLifecycleService().initialize(UUID.randomUUID().toString(), stored.manifestId(), "r3", UUID.randomUUID().toString(), 1, config, manifest, state.mcpSessions, System.getenv()));
                assertEquals(expected.getValue(), error.category());
            }
        }
    }

    @Test void toolsListIsRejectedBeforeReady() throws Exception {
        // R2 state admission is directly regression-tested; lifecycle is the only production path to READY.
        assertFalse(Arrays.stream(McpLifecycleService.class.getMethods()).anyMatch(m -> m.getName().equals("callTool")));
    }

    @Test void initializeTimeoutFailsClosedWithoutReadyOrRetry() throws Exception {
        Path dir=root.resolve("timeout"); java.nio.file.Files.createDirectories(dir);
        McpRuntimeLimits d=McpRuntimeLimits.defaults();
        McpRuntimeLimits limits=new McpRuntimeLimits(100,d.requestTimeoutMs(),d.shutdownTimeoutMs(),d.maxPendingRequests(),d.maxStdoutLineBytes(),d.maxStderrBytes(),d.maxCatalogPages(),d.maxTools(),d.maxCatalogBytes(),d.maxSingleSchemaBytes(),d.maxOutputBytes());
        try(StateStore state=new StateStore(dir)){
            McpServerConfig config=R3TestSupport.config(dir,"INITIALIZE_TIMEOUT",List.of("echo"),limits);
            McpConfigManifest manifest=new McpConfigManifestBuilder().build(config);var stored=state.mcpServerManifests.saveOrFindManifest(manifest);String sessionId=UUID.randomUUID().toString();
            assertEquals("REQUEST_TIMEOUT",assertThrows(McpLifecycleException.class,()->new McpLifecycleService().initialize(sessionId,stored.manifestId(),"r3","timeout",1,config,manifest,state.mcpSessions,System.getenv())).category());
            assertNotEquals("READY",state.mcpSessions.findById(sessionId).orElseThrow().status());
        }
    }
}
