package com.paicli.interactive.view;

import java.time.Instant;

public record TaskView(String taskId, String parentTaskId, String summary, String status,
        String manifestId, String manifestHash, Instant createdAt, Instant terminalAt) { }
