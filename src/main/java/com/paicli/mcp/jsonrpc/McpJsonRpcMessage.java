package com.paicli.mcp.jsonrpc;

import com.fasterxml.jackson.databind.JsonNode;

public sealed interface McpJsonRpcMessage {
    record Response(long id, JsonNode result, JsonNode error) implements McpJsonRpcMessage {
        public boolean isError() { return error != null; }
    }
    record Notification(String method, JsonNode params) implements McpJsonRpcMessage {}
    record ServerRequest(long id, String method, JsonNode params) implements McpJsonRpcMessage {}
}
