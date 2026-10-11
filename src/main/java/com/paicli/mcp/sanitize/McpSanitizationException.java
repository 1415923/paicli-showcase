package com.paicli.mcp.sanitize;

public final class McpSanitizationException extends Exception {
    private final String category;
    public McpSanitizationException(String category, String message) { super(message); this.category=category; }
    public String category(){ return category; }
}
