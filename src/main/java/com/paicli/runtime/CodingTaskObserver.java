package com.paicli.runtime;

/**
 * Optional post-persistence observer for the coding task state machine.
 * Runtime state remains authoritative; observer failures are isolated.
 */
public interface CodingTaskObserver {
    CodingTaskObserver NOOP = new CodingTaskObserver() { };

    default void executionAuthorityBound(FencedExecutionGuard guard) { }

    default void taskStarted(String taskId, String runId, String boundedGoal) { }

    default void planCreated(String taskId, String runId, PlanResult plan) { }

    default void planStepStarted(String taskId,String runId,
            com.paicli.state.StructuredPlanModels.Revision revision,
            com.paicli.state.StructuredPlanModels.StepDefinition step,int attempt) { }

    default void planStepCompleted(String taskId,String runId,
            com.paicli.state.StructuredPlanModels.Revision revision,
            com.paicli.state.StructuredPlanModels.StepDefinition step,int attempt) { }

    default void modelMessage(String taskId,int iteration,String kind,String boundedContent,
            com.paicli.agent.AgentSegmentRequest.PlanExecutionContext planExecution) { }

    default void phaseChanged(String taskId, String runId, String phase) { }

    default void repairStarted(String taskId, String runId, int attempt,
                               VerificationResult trigger) { }

    default void taskCompleted(CodingTaskResult result) { }

    default void taskFailed(CodingTaskResult result) { }

    /** Synchronous user-result handoff; false means terminal rendering did not accept it. */
    default boolean finalResponse(String taskId,String runId,String responseMode,
            String content,String contentHash) { return true; }
}
