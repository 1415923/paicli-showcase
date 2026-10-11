package com.paicli.worker;

import java.util.List;

public record WorkerCandidateResult(
        String candidateId,
        String workerRunId,
        String status,
        String verificationStatus,
        String candidateCommitSha,
        String diffHash,
        List<String> changedFiles,
        String verificationHash,
        boolean reused,
        String message
) { }
