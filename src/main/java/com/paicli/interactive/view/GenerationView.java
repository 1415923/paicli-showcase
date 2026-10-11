package com.paicli.interactive.view;

import java.time.Instant;

public record GenerationView(String generationId, String state, long pointerVersion,
        String ownerRunId, Instant createdAt, Instant committedAt) { }
