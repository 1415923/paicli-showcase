package com.paicli.worker;

import java.nio.file.Path;

/** Bounded coordinator result; it is not a frozen or verified Candidate. */
public record WorktreeWorkerResult(
        String workerRunId,
        String status,
        int attemptNumber,
        String outputExcerpt,
        String outputHash,
        boolean outputTruncated,
        boolean dirty,
        boolean reusedExisting,
        Path stateDatabase,
        Path traceDirectory,
        String message
) { }
