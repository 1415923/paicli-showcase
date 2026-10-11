package com.paicli.mcp.schema;

public final class McpArgumentValidationException extends Exception {
    private final String category;
    public McpArgumentValidationException(String category, String message) { super(message); this.category = category; }
    public String category() { return category; }
}
