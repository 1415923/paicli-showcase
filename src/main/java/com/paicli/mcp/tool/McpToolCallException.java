package com.paicli.mcp.tool;

public final class McpToolCallException extends Exception {
    private final String category;
    private boolean unknownOutcome;
    public McpToolCallException(String category,String message){super(message);this.category=category;}
    public McpToolCallException(String category,String message,Throwable cause){super(message,cause);this.category=category;}
    public String category(){return category;}
    public McpToolCallException withUnknownOutcome(){unknownOutcome=true;return this;}
    public boolean hasUnknownOutcome(){return unknownOutcome;}
}
