package com.paicli.mcp.lifecycle;

public final class McpLifecycleException extends Exception {
    private final String category;

    public McpLifecycleException(String category, String message) { super(message); this.category = category; }
    public McpLifecycleException(String category, String message, Throwable cause) { super(message, cause); this.category = category; }
    public String category() { return category; }
}
