package com.paicli.mcp.transport;

public final class McpTransportException extends Exception {
    private final String category;

    public McpTransportException(String category, String message) {
        super(message);
        this.category = category;
    }

    public McpTransportException(String category, String message, Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    public String category() { return category; }
}
