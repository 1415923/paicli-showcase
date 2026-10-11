package com.paicli.mcp.jsonrpc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;

public final class McpJsonRpcCodec {
    private final ObjectMapper json;
    private final int maxDepth;

    public McpJsonRpcCodec(int maxDepth) {
        if (maxDepth < 1) throw new IllegalArgumentException("maxDepth must be positive");
        this.maxDepth = maxDepth;
        json = new ObjectMapper();
        json.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(maxDepth).build());
    }

    public McpJsonRpcMessage parse(byte[] line) throws McpProtocolException {
        final JsonNode value;
        try { value = json.readTree(line); }
        catch (IOException e) { throw new McpProtocolException("MALFORMED_JSON", "stdout is not valid JSON-RPC"); }
        if (value == null || !value.isObject()) throw new McpProtocolException("INVALID_MESSAGE", "JSON-RPC message must be an object");
        if (!"2.0".equals(value.path("jsonrpc").asText(null))) throw new McpProtocolException("INVALID_VERSION", "jsonrpc must be 2.0");
        boolean method = value.has("method");
        boolean id = value.has("id");
        boolean result = value.has("result");
        boolean error = value.has("error");
        if (method) {
            if (!value.get("method").isTextual() || value.get("method").asText().isBlank() || result || error)
                throw new McpProtocolException("INVALID_REQUEST", "invalid JSON-RPC request or notification");
            JsonNode params = value.get("params");
            if (params != null && !params.isObject() && !params.isArray())
                throw new McpProtocolException("INVALID_PARAMS", "params must be an object or array");
            if (id) return new McpJsonRpcMessage.ServerRequest(parseId(value.get("id")), value.get("method").asText(), params);
            return new McpJsonRpcMessage.Notification(value.get("method").asText(), params);
        }
        if (!id) throw new McpProtocolException("MISSING_ID", "response id is required");
        if (result == error) throw new McpProtocolException("INVALID_RESPONSE", "response must contain exactly one of result or error");
        return new McpJsonRpcMessage.Response(parseId(value.get("id")), value.get("result"), value.get("error"));
    }

    public byte[] request(long id, String method, JsonNode params) throws McpProtocolException {
        if (id <= 0 || method == null || method.isBlank()) throw new McpProtocolException("INVALID_REQUEST", "request id and method are required");
        validateParams(params);
        ObjectNode node = json.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method);
        if (params != null) node.set("params", params);
        return write(node);
    }

    public byte[] notification(String method, JsonNode params) throws McpProtocolException {
        if (method == null || method.isBlank()) throw new McpProtocolException("INVALID_NOTIFICATION", "notification method is required");
        validateParams(params);
        ObjectNode node = json.createObjectNode().put("jsonrpc", "2.0").put("method", method);
        if (params != null) node.set("params", params);
        return write(node);
    }

    public byte[] methodNotSupported(long id) throws McpProtocolException {
        ObjectNode node = json.createObjectNode().put("jsonrpc", "2.0").put("id", id);
        node.putObject("error").put("code", -32601).put("message", "Method not supported");
        return write(node);
    }

    public ObjectNode objectNode() { return json.createObjectNode(); }

    private byte[] write(JsonNode node) throws McpProtocolException {
        try { return json.writeValueAsBytes(node); }
        catch (JsonProcessingException e) { throw new McpProtocolException("SERIALIZATION", "unable to serialize JSON-RPC message"); }
    }

    private static long parseId(JsonNode id) throws McpProtocolException {
        if (!id.isIntegralNumber() || !id.canConvertToLong() || id.asLong() <= 0)
            throw new McpProtocolException("INVALID_ID", "only positive integer ids are supported");
        return id.asLong();
    }

    private void validateParams(JsonNode params) throws McpProtocolException {
        if (params == null) return;
        if (!params.isObject() && !params.isArray()) throw new McpProtocolException("INVALID_PARAMS", "params must be an object or array");
        if (depth(params, 1) > maxDepth) throw new McpProtocolException("MESSAGE_TOO_DEEP", "JSON-RPC params exceed configured depth");
    }

    private static int depth(JsonNode node, int current) {
        if (!node.isContainerNode()) return current;
        int max = current;
        for (JsonNode child : node) max = Math.max(max, depth(child, current + 1));
        return max;
    }
}
