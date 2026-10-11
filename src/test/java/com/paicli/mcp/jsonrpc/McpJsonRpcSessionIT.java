package com.paicli.mcp.jsonrpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.mcp.config.McpRuntimeLimits;
import com.paicli.mcp.transport.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class McpJsonRpcSessionIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path root;

    @Test void monotonicIdsAndOutOfOrderResponsesPairExactly() throws Exception {
        var config = McpTransportTestSupport.config(root, "OUT_OF_ORDER", List.of(), McpRuntimeLimits.defaults());
        try (var transport = McpStdioTransport.start(config, McpTransportTestSupport.manifest(config), System.getenv());
             var session = new McpJsonRpcSession(transport, config)) {
            var first = session.sendRequest("test/echo", JSON.createObjectNode().put("value", "first"), Duration.ofSeconds(2));
            var second = session.sendRequest("test/echo", JSON.createObjectNode().put("value", "second"), Duration.ofSeconds(2));
            assertEquals("first", first.get().result().path("value").asText());
            assertEquals("second", second.get().result().path("value").asText());
            assertEquals(2, session.lastIssuedRequestId());
        }
    }

    @Test void pendingLimitRejectsBeforeWriting() throws Exception {
        var config = McpTransportTestSupport.config(root, "NO_RESPONSE", List.of(), McpTransportTestSupport.limits(1000, 200, 1, 4096, 1024));
        try (var transport = McpStdioTransport.start(config, McpTransportTestSupport.manifest(config), System.getenv());
             var session = new McpJsonRpcSession(transport, config)) {
            session.sendRequest("test/one", JSON.createObjectNode(), Duration.ofSeconds(1));
            assertEquals("MAX_PENDING_REQUESTS", assertThrows(McpTransportException.class,
                    () -> session.sendRequest("test/two", JSON.createObjectNode(), Duration.ofSeconds(1))).category());
            assertEquals(1, session.lastIssuedRequestId());
        }
    }

    @Test void invalidResponsesAndUnknownOrDuplicateIdsFailClosed() throws Exception {
        for (String mode : List.of("UNKNOWN_ID", "RESULT_AND_ERROR", "MISSING_RESULT", "WRONG_VERSION")) {
            var config = McpTransportTestSupport.config(root, mode, List.of(), McpRuntimeLimits.defaults());
            try (var transport = McpStdioTransport.start(config, McpTransportTestSupport.manifest(config), System.getenv());
                 var session = new McpJsonRpcSession(transport, config)) {
                assertEquals(McpSessionResult.Status.PROTOCOL_ERROR,
                        session.sendRequest("test/echo", JSON.createObjectNode(), Duration.ofSeconds(1)).get().status(), mode);
                assertEquals(McpJsonRpcSession.State.UNHEALTHY, session.state(), mode);
            }
        }
        var duplicateConfig = McpTransportTestSupport.config(root, "DUPLICATE", List.of(), McpRuntimeLimits.defaults());
        try (var transport = McpStdioTransport.start(duplicateConfig, McpTransportTestSupport.manifest(duplicateConfig), System.getenv());
             var session = new McpJsonRpcSession(transport, duplicateConfig)) {
            assertEquals(McpSessionResult.Status.SUCCEEDED,
                    session.sendRequest("test/echo", JSON.createObjectNode(), Duration.ofSeconds(1)).get().status());
            awaitUnhealthy(session);
        }
    }

    @Test void serverRequestReceivesMethodNotSupportedAndExecutesNothing() throws Exception {
        var config = McpTransportTestSupport.config(root, "SERVER_REQUEST", List.of(), McpRuntimeLimits.defaults());
        try (var transport = McpStdioTransport.start(config, McpTransportTestSupport.manifest(config), System.getenv());
             var session = new McpJsonRpcSession(transport, config)) {
            var result = session.sendRequest("test/echo", JSON.createObjectNode(), Duration.ofSeconds(2)).get();
            assertEquals(-32601, result.result().path("code").asInt());
        }
    }

    @Test void timeoutIsTerminalSendsCancellationAndRejectsLateOrNewRequests() throws Exception {
        var config = McpTransportTestSupport.config(root, "LATE_RESPONSE", List.of(), McpTransportTestSupport.limits(1000, 200, 4, 4096, 1024));
        try (var transport = McpStdioTransport.start(config, McpTransportTestSupport.manifest(config), System.getenv());
             var session = new McpJsonRpcSession(transport, config)) {
            var future = session.sendRequest("test/slow", JSON.createObjectNode(), Duration.ofMillis(100));
            assertEquals(McpSessionResult.Status.TIMED_OUT, future.get(1, TimeUnit.SECONDS).status());
            assertEquals(McpJsonRpcSession.State.UNHEALTHY, session.state());
            assertThrows(McpTransportException.class,
                    () -> session.sendRequest("test/again", JSON.createObjectNode(), Duration.ofMillis(100)));
            for (int i = 0; i < 50 && !transport.stderrSummary().excerpt().contains("CANCEL_OBSERVED"); i++) Thread.sleep(20);
            assertEquals(McpSessionResult.Status.TIMED_OUT, future.get().status());
            assertEquals(0, session.pendingCount());
            assertTrue(transport.stderrSummary().excerpt().contains("CANCEL_OBSERVED"));
        }
    }

    @Test void processExitAndShutdownTerminateAllPending() throws Exception {
        var exitConfig = McpTransportTestSupport.config(root, "EXIT", List.of(), McpRuntimeLimits.defaults());
        try (var transport = McpStdioTransport.start(exitConfig, McpTransportTestSupport.manifest(exitConfig), System.getenv());
             var session = new McpJsonRpcSession(transport, exitConfig)) {
            assertEquals(McpSessionResult.Status.PROCESS_EXITED,
                    session.sendRequest("test/exit", JSON.createObjectNode(), Duration.ofSeconds(2)).get().status());
        }
        var waitConfig = McpTransportTestSupport.config(root, "NO_RESPONSE", List.of(), McpRuntimeLimits.defaults());
        var transport = McpStdioTransport.start(waitConfig, McpTransportTestSupport.manifest(waitConfig), System.getenv());
        var session = new McpJsonRpcSession(transport, waitConfig);
        var pending = session.sendRequest("test/wait", JSON.createObjectNode(), Duration.ofSeconds(2));
        session.close();
        assertEquals(McpSessionResult.Status.CANCELLED, pending.get().status());
        assertThrows(McpTransportException.class,
                () -> session.sendRequest("test/new", JSON.createObjectNode(), Duration.ofSeconds(1)));
    }

    private static void awaitUnhealthy(McpJsonRpcSession session) throws InterruptedException {
        for (int i = 0; i < 50 && session.state() != McpJsonRpcSession.State.UNHEALTHY; i++) Thread.sleep(10);
        assertEquals(McpJsonRpcSession.State.UNHEALTHY, session.state());
    }
}
