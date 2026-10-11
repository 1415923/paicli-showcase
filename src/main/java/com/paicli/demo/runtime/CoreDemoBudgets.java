package com.paicli.demo.runtime;

public record CoreDemoBudgets(int maxWorkflowTurns, int maxRepairAttempts,
        long verificationTimeoutSeconds, long totalTimeoutSeconds,
        int maxArtifactBytes, int maxReportBytes) {
    public static CoreDemoBudgets fixed() {
        return new CoreDemoBudgets(12, 1, 90, 300, 512 * 1024, 128 * 1024);
    }
    public CoreDemoBudgets {
        if (maxWorkflowTurns < 1 || maxWorkflowTurns > 20
                || maxRepairAttempts != 1
                || verificationTimeoutSeconds < 1 || verificationTimeoutSeconds > 120
                || totalTimeoutSeconds < 60 || totalTimeoutSeconds > 600
                || maxArtifactBytes < 16 * 1024 || maxArtifactBytes > 1024 * 1024
                || maxReportBytes < 4096 || maxReportBytes > 256 * 1024) {
            throw new IllegalArgumentException("Invalid Core Demo budgets");
        }
    }
}
