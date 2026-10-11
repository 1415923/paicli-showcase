package com.paicli.mcp.spike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class McpStdioProtocolSpikeIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration SECOND = Duration.ofSeconds(2);

    @Test
    void initializeInitializedAndPaginatedToolsListUseSupportedVersion() {
        try (McpStdioProtocolSpike client = client("NORMAL")) {
            client.initialize(SECOND);
            assertEquals(McpStdioProtocolSpike.State.READY, client.state());
            List<JsonNode> tools = client.listTools(4, 10, 8_192, SECOND);
            assertEquals(List.of("echo", "sum"), tools.stream()
                    .map(tool -> tool.path("name").asText()).toList());
        }
    }

    @Test
    void toolsCallSuccessAndToolErrorRemainDistinctFromJsonRpcError() {
        try (McpStdioProtocolSpike success = client("NORMAL")) {
            success.initialize(SECOND);
            assertFalse(success.request("tools/call", JSON.createObjectNode(), SECOND)
                    .path("isError").asBoolean());
        }
        try (McpStdioProtocolSpike toolError = client("TOOL_ERROR")) {
            toolError.initialize(SECOND);
            assertTrue(toolError.request("tools/call", JSON.createObjectNode(), SECOND)
                    .path("isError").asBoolean());
        }
        try (McpStdioProtocolSpike rpcError = client("JSON_RPC_ERROR")) {
            rpcError.initialize(SECOND);
            assertThrows(McpStdioProtocolSpike.RpcError.class,
                    () -> rpcError.request("tools/call", JSON.createObjectNode(), SECOND));
        }
    }

    @Test
    void concurrentOutOfOrderResponsesPairByRequestId() throws Exception {
        try (McpStdioProtocolSpike client = client("OUT_OF_ORDER")) {
            client.initialize(SECOND);
            CompletableFuture<JsonNode> first = CompletableFuture.supplyAsync(() ->
                    client.request("test/echo", JSON.createObjectNode().put("value", "first"), SECOND));
            CompletableFuture<JsonNode> second = CompletableFuture.supplyAsync(() ->
                    client.request("test/echo", JSON.createObjectNode().put("value", "second"), SECOND));
            assertEquals("first", first.get(3, TimeUnit.SECONDS).path("value").asText());
            assertEquals("second", second.get(3, TimeUnit.SECONDS).path("value").asText());
        }
    }

    @Test
    void stderrIsBoundedRedactedAndDoesNotFailSuccessfulCall() throws Exception {
        try (McpStdioProtocolSpike client = client("STDERR")) {
            client.initialize(SECOND);
            assertFalse(client.request("tools/call", JSON.createObjectNode(), SECOND)
                    .path("isError").asBoolean());
            Thread.sleep(100);
            assertEquals(McpStdioProtocolSpike.State.READY, client.state());
            assertTrue(client.stderrExcerpt().contains("<redacted>"));
            assertFalse(client.stderrExcerpt().contains("server-secret"));
        }
    }

    @Test
    void nonJsonStdoutUnknownAndDuplicateIdsFailClosed() throws Exception {
        for (String mode : List.of("INVALID_STDOUT", "UNKNOWN_ID")) {
            try (McpStdioProtocolSpike client = client(mode)) {
                assertThrows(McpStdioProtocolSpike.ProtocolException.class,
                        () -> client.initialize(Duration.ofMillis(800)), mode);
                Thread.sleep(50);
                assertEquals(McpStdioProtocolSpike.State.UNHEALTHY, client.state(), mode);
            }
        }
        try (McpStdioProtocolSpike duplicate = client("DUPLICATE")) {
            try {
                duplicate.initialize(SECOND);
            } catch (McpStdioProtocolSpike.ProtocolException expectedRaceWinner) {
                // The duplicate may be consumed before initialize returns; both orderings must fail closed.
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (duplicate.state() != McpStdioProtocolSpike.State.UNHEALTHY
                    && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(McpStdioProtocolSpike.State.UNHEALTHY, duplicate.state());
            assertThrows(McpStdioProtocolSpike.ProtocolException.class,
                    () -> duplicate.request("tools/list", JSON.createObjectNode(), SECOND));
        }
    }

    @Test
    void unsupportedVersionAndMissingToolsCapabilityCloseInitialization() {
        for (String mode : List.of("VERSION_MISMATCH", "NO_TOOLS")) {
            try (McpStdioProtocolSpike client = client(mode)) {
                assertThrows(McpStdioProtocolSpike.ProtocolException.class,
                        () -> client.initialize(SECOND), mode);
                assertEquals(McpStdioProtocolSpike.State.UNHEALTHY, client.state());
            }
        }
    }

    @Test
    void timeoutMarksSessionUnhealthyAndLateResponseCannotBecomeSuccess() throws Exception {
        try (McpStdioProtocolSpike client = client("LATE")) {
            client.initialize(SECOND);
            assertThrows(McpStdioProtocolSpike.RequestTimeout.class,
                    () -> client.request("tools/call", JSON.createObjectNode(), Duration.ofMillis(80)));
            assertEquals(McpStdioProtocolSpike.State.UNHEALTHY, client.state());
            Thread.sleep(700);
            assertNotEquals(McpStdioProtocolSpike.State.READY, client.state());
            assertThrows(McpStdioProtocolSpike.ProtocolException.class,
                    () -> client.request("tools/call", JSON.createObjectNode(), SECOND));
        }
    }

    @Test
    void processCrashTerminatesPendingRequestAndShutdownCleansChild() throws Exception {
        McpStdioProtocolSpike client = client("CRASH");
        client.initialize(SECOND);
        assertThrows(McpStdioProtocolSpike.ProtocolException.class,
                () -> client.request("tools/call", JSON.createObjectNode(), SECOND));
        client.close();
        assertEquals(McpStdioProtocolSpike.State.CLOSED, client.state());
        assertFalse(client.alive());
    }

    @Test
    void cursorLoopAndOversizedCatalogAreRejected() {
        try (McpStdioProtocolSpike loop = client("CURSOR_LOOP")) {
            loop.initialize(SECOND);
            assertThrows(McpStdioProtocolSpike.ProtocolException.class,
                    () -> loop.listTools(4, 10, 8_192, SECOND));
        }
        try (McpStdioProtocolSpike huge = client("OVERSIZED_CATALOG")) {
            huge.initialize(SECOND);
            assertThrows(McpStdioProtocolSpike.ProtocolException.class,
                    () -> huge.listTools(2, 10, 1_024, SECOND));
        }
    }

    @Test
    void sanitizerBoundsRedactsPathsSecretsAnsiAndMarksUntrustedData() {
        String raw = "\u001B[31mAuthorization: Bearer abc123\u001B[0m C:\\Users\\me\\secret /home/me/key "
                + "password=hunter2 " + "x".repeat(2_000);
        McpToolOutputSanitizerSpike.Result result = new McpToolOutputSanitizerSpike().sanitize(raw, 256);
        assertTrue(result.content().startsWith(McpToolOutputSanitizerSpike.BOUNDARY));
        assertTrue(result.truncated());
        assertTrue(result.redacted());
        assertFalse(result.content().contains("abc123"));
        assertFalse(result.content().contains("hunter2"));
        assertFalse(result.content().contains("C:\\Users"));
        assertFalse(result.content().contains("/home/me"));
        assertEquals(64, result.originalHash().length());
    }

    @Test
    void fakeServerInstructionsRemainUntrustedMetadataNotRuntimeInstructions() {
        try (McpStdioProtocolSpike client = client("NORMAL")) {
            client.initialize(SECOND);
            assertEquals(McpStdioProtocolSpike.State.READY, client.state());
            // The spike validates capability/version only; instructions are never returned as policy.
            assertFalse(client.stderrExcerpt().contains("ignore approval"));
        }
    }

    private McpStdioProtocolSpike client(String mode) {
        try {
            return new McpStdioProtocolSpike(mode, 16_384, 32, 1_024);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
