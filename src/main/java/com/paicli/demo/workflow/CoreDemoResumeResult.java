package com.paicli.demo.workflow;

import com.paicli.demo.verification.CoreDemoVerificationEvidence;
import java.util.List;

public record CoreDemoResumeResult(
        String taskId,
        String codingRunId,
        String resumeOccurrenceKey,
        String planHash,
        String codeIntelligenceHash,
        String checkpointSemanticHash,
        String initialTreeHash,
        String finalTreeHash,
        CoreDemoVerificationEvidence firstVerification,
        CoreDemoVerificationEvidence finalVerification,
        List<String> snapshotIds,
        List<String> toolSequence,
        SideEffectCounts before,
        SideEffectCounts after,
        boolean duplicateSideEffectsPrevented) {
    public CoreDemoResumeResult {
        snapshotIds=List.copyOf(snapshotIds);
        toolSequence=List.copyOf(toolSequence);
    }
    public record SideEffectCounts(int plans,int codeAudits,int initialWrites,
            int firstVerifications,int checkpoints,int snapshots,int repairAttempts,
            int repairWrites,int finalVerifications) { }
}