package com.paicli.mcp.jsonrpc;

public final class McpProtocolException extends Exception {
    private final String category;

    public McpProtocolException(String category, String message) {
        super(message);
        this.category = category;
    }

    public String category() { return category; }
}
