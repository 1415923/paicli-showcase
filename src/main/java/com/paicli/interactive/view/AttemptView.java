package com.paicli.interactive.view;

import java.time.Instant;

public record AttemptView(String stepId, String runId, int attemptNumber,
        String state, int startIteration, Integer endIteration,
        Instant startedAt, Instant finishedAt, String failureClass) { }
