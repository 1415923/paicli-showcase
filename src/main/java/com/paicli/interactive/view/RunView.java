package com.paicli.interactive.view;

import java.time.Instant;

public record RunView(String runId, String kind, String status,
        String resumedFromRunId, Instant createdAt) { }
