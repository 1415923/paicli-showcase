package com.paicli.subagent;

import com.paicli.subagent.verdict.StructuredVerdict;

public record SubagentRunnerResult(
        String subagentRunId,
        String verdictId,
        String status,
        int attemptNumber,
        StructuredVerdict verdict,
        String extractionStrategy,
        boolean skippedExisting,
        boolean outputTruncated,
        String message
) {
}
