package com.paicli.mcp.catalog;

public final class McpCatalogException extends Exception {
    private final String category;
    public McpCatalogException(String category, String message) { super(message); this.category = category; }
    public McpCatalogException(String category, String message, Throwable cause) { super(message, cause); this.category = category; }
    public String category() { return category; }
}
