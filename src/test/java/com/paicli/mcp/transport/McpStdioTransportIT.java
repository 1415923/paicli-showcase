package com.paicli.mcp.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.mcp.config.*;
import com.paicli.mcp.jsonrpc.*;
import com.paicli.mcp.manifest.McpConfigManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class McpStdioTransportIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path root;

    @Test void startsTrustedProcessWithSeparateArgumentsWorkingDirectoryAndMinimalEnvironment() throws Exception {
        var refs = List.of(new McpEnvironmentReference("HOST_ENV", "MCP_R2_ALLOWED"));
        var config = McpTransportTestSupport.config(root, "ENV", refs, McpRuntimeLimits.defaults());
        Map<String,String> host = new HashMap<>(System.getenv());
        host.put("MCP_R2_ALLOWED", "R2_SECRET_SENTINEL_84721"); host.put("MCP_R2_BLOCKED", "must-not-pass");
        try (var transport = McpStdioTransport.start(config, McpTransportTestSupport.manifest(config), host);
             var session = new McpJsonRpcSession(transport, config)) {
            var result = session.sendRequest("test/env", JSON.createObjectNode(), Duration.ofSeconds(2)).get(3, TimeUnit.SECONDS);
            assertTrue(result.result().path("allowed").asBoolean());
            assertFalse(result.result().path("blocked").asBoolean());
        }
    }

    @Test void manifestAndExecutableDriftPreventProcessStart() throws Exception {
        var config = McpTransportTestSupport.config(root, "NORMAL", List.of(), McpRuntimeLimits.defaults());
        McpConfigManifest manifest = McpTransportTestSupport.manifest(config);
        var changed = new McpServerConfig(config.serverId(), true, "STDIO", config.executable(),
                List.of("-cp", System.getProperty("java.class.path"), "changed.Main"), config.workingDirectory(),
                List.of(), config.limits(), config.allowedToolNames(), config.supportedProtocolVersions());
        McpTransportException drift = assertThrows(McpTransportException.class,
                () -> McpStdioTransport.start(changed, manifest, System.getenv()));
        assertEquals("ARGUMENTS_DRIFT", drift.category());

        Path copied = Files.copy(config.executable(), root.resolve(McpTransportTestSupport.isWindows() ? "java-copy.exe" : "java-copy"));
        var copiedConfig = new McpServerConfig("copy", true, "STDIO", copied, config.args(), root, List.of(),
                config.limits(), config.allowedToolNames(), config.supportedProtocolVersions());
        McpConfigManifest copiedManifest = McpTransportTestSupport.manifest(copiedConfig);
        Files.write(copied, new byte[]{0}, StandardOpenOption.APPEND);
        McpTransportException executable = assertThrows(McpTransportException.class,
                () -> McpStdioTransport.start(copiedConfig, copiedManifest, System.getenv()));
        assertTrue(Set.of("EXECUTABLE_DRIFT", "CONFIG_DRIFT").contains(executable.category()));
    }

    @Test void stderrIsRedactedBoundedAndDoesNotPolluteStdout() throws Exception {
        var config = McpTransportTestSupport.config(root, "STDERR",
                List.of(new McpEnvironmentReference("HOST_ENV", "MCP_R2_ALLOWED")), McpTransportTestSupport.limits(2000, 500, 4, 4096, 1024));
        Map<String,String> host = new HashMap<>(System.getenv()); host.put("MCP_R2_ALLOWED", "R2_SECRET_SENTINEL_84721");
        McpStdioTransport transport = McpStdioTransport.start(config, McpTransportTestSupport.manifest(config), host);
        try (var session = new McpJsonRpcSession(transport, config)) {
            assertEquals(McpSessionResult.Status.SUCCEEDED, session.sendRequest("test/echo", JSON.createObjectNode(), Duration.ofSeconds(1)).get().status());
            Thread.sleep(100);
            assertFalse(transport.stderrSummary().excerpt().contains("R2_SECRET_SENTINEL_84721"));
            assertTrue(transport.stderrSummary().redacted());
        }
    }

    @Test void malformedOversizedAndInvalidUtf8StdoutAreProtocolFailures() throws Exception {
        for (String mode : List.of("INVALID_STDOUT", "OVERSIZED_LINE", "INVALID_UTF8")) {
            var config = McpTransportTestSupport.config(root, mode, List.of(), McpTransportTestSupport.limits(1000, 300, 2, 1024, 1024));
            try (var transport = McpStdioTransport.start(config, McpTransportTestSupport.manifest(config), System.getenv());
                 var session = new McpJsonRpcSession(transport, config)) {
                var result = session.sendRequest("test/echo", JSON.createObjectNode(), Duration.ofMillis(500)).get(2, TimeUnit.SECONDS);
                assertEquals(McpSessionResult.Status.PROTOCOL_ERROR, result.status(), mode);
                assertEquals(McpJsonRpcSession.State.UNHEALTHY, session.state(), mode);
            }
        }
    }

    @Test void shutdownIsBoundedIdempotentAndLeavesNoChild() throws Exception {
        var config = McpTransportTestSupport.config(root, "IGNORE_EOF", List.of(), McpTransportTestSupport.limits(1000, 150, 2, 4096, 1024));
        var transport = McpStdioTransport.start(config, McpTransportTestSupport.manifest(config), System.getenv());
        assertTrue(transport.isAlive());
        transport.shutdown(); transport.shutdown();
        assertFalse(transport.isAlive());
    }
}
