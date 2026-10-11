package com.paicli.interactive.view;

public record ThreadView(String threadId, String status, String headTaskId,
        long headVersion) { }
