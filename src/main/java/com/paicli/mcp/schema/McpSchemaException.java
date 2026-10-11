package com.paicli.mcp.schema;

public final class McpSchemaException extends Exception {
    private final String category;

    public McpSchemaException(String category, String message) {
        super(message);
        this.category = category;
    }

    public String category() { return category; }
}
