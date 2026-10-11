package com.paicli.mcp.jsonrpc;

import com.fasterxml.jackson.databind.JsonNode;

public record McpSessionResult(long requestId, Status status, JsonNode result, JsonNode error, String category) {
    public enum Status { SUCCEEDED, RPC_ERROR, TIMED_OUT, PROCESS_EXITED, PROTOCOL_ERROR, CANCELLED }

    public static McpSessionResult response(McpJsonRpcMessage.Response response) {
        return response.isError()
                ? new McpSessionResult(response.id(), Status.RPC_ERROR, null, response.error(), "JSON_RPC_ERROR")
                : new McpSessionResult(response.id(), Status.SUCCEEDED, response.result(), null, null);
    }
}
