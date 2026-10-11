package com.paicli.runtime;

/** Final persisted outcome of one ordinary CodingTaskRunner invocation. */
public record CodingTaskResult(
        String taskId,
        String runId,
        String planId,
        String finalStatus,
        String verificationStatus,
        int repairAttempts,
        String summary,
        FencedExecutionGuard executionGuard
) {
    public CodingTaskResult(String taskId,String runId,String planId,String finalStatus,
            String verificationStatus,int repairAttempts,String summary){
        this(taskId,runId,planId,finalStatus,verificationStatus,repairAttempts,summary,null);
    }
}
