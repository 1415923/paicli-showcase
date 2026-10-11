package com.paicli.mcp.jsonrpc;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class McpJsonRpcCodecTest {
    private final McpJsonRpcCodec codec = new McpJsonRpcCodec(16);

    @Test void classifiesResponseNotificationAndServerRequest() throws Exception {
        assertInstanceOf(McpJsonRpcMessage.Response.class, parse("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"));
        assertInstanceOf(McpJsonRpcMessage.Notification.class, parse("{\"jsonrpc\":\"2.0\",\"method\":\"notice\",\"params\":{}}"));
        assertInstanceOf(McpJsonRpcMessage.ServerRequest.class, parse("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"server/request\"}"));
    }

    @Test void rejectsMixedMissingAndUnsupportedShapes() {
        for (String value : new String[]{
                "[]", "{\"jsonrpc\":\"1.0\",\"id\":1,\"result\":{}}",
                "{\"jsonrpc\":\"2.0\",\"result\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":1}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{},\"error\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":\"x\",\"result\":{}}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notice\",\"params\":1}"}) {
            assertThrows(McpProtocolException.class, () -> parse(value), value);
        }
    }

    @Test void rejectsOverlyDeepOutboundParamsBeforeSerialization() {
        var node = codec.objectNode();
        var cursor = node;
        for (int i = 0; i < 20; i++) cursor = cursor.putObject("nested");
        assertThrows(McpProtocolException.class, () -> codec.request(1, "test/deep", node));
    }

    private McpJsonRpcMessage parse(String value) throws McpProtocolException {
        return codec.parse(value.getBytes(StandardCharsets.UTF_8));
    }
}
