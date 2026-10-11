package com.paicli.interactive.task;

import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.approval.ApprovalInteractionResult;
import com.paicli.interactive.approval.InteractiveApprovalRequest;
import java.util.Optional;
import com.paicli.interactive.steering.SteeringConfirmationRequest;
import com.paicli.interactive.followup.FollowUpConfirmationRequest;

import java.time.Duration;

@FunctionalInterface
public interface TaskSubmissionPort {
    TaskSubmissionResult submit(InteractiveTaskRequest request);

    default TaskSubmissionResult recoverTask(String taskId) {
        return TaskSubmissionResult.unavailable(
                "TASK_RECOVERY_UNAVAILABLE", "Task Recovery is unavailable.");
    }

    default TaskSubmissionResult admitOrdinaryInput() {
        return TaskSubmissionResult.accepted("Ordinary input is admitted.");
    }
    default TaskSubmissionResult admitOrdinaryInput(String input){return admitOrdinaryInput();}
    default TaskSubmissionResult submitFollowUp(String input,boolean explicit){return TaskSubmissionResult.unavailable(
            "FROZEN_HEAD_REQUIRED","Completed-task Follow-up requires a frozen Parent/head token.");}
    default TaskSubmissionResult submitFollowUp(String input,boolean explicit,String expectedHeadToken){
        return TaskSubmissionResult.unavailable("FOLLOW_UP_UNAVAILABLE","Completed-task Follow-up is unavailable.");
    }
    default Optional<String> currentFollowUpHeadToken(){return Optional.empty();}
    default Optional<FollowUpConfirmationRequest> pendingFollowUpConfirmation(){return Optional.empty();}
    default TaskSubmissionResult respondFollowUpConfirmation(String capsuleId,String kind,long version,String input){
        return TaskSubmissionResult.rejected("FOLLOW_UP_CONFIRMATION_UNAVAILABLE","No Follow-up confirmation is active.");
    }

    default void openSession(InteractiveSession session) { }

    default void requestCancellation() { }

    /** Active process-local Task that the v1.2 shell must await in the foreground. */
    default Optional<ForegroundTask> activeForegroundTask() { return Optional.empty(); }

    default Optional<InteractiveApprovalRequest> pendingApproval() { return Optional.empty(); }

    default String currentPhase() { return "RUNNING"; }

    default ApprovalInteractionResult respondApproval(String requestId, String input) {
        return new ApprovalInteractionResult(false, false,
                java.util.List.of("No approval request is active."));
    }

    default TaskSubmissionResult submitSteering(String input, boolean explicit) {
        return TaskSubmissionResult.unavailable("STEERING_UNAVAILABLE","Steering is unavailable.");
    }
    default TaskSubmissionResult steeringStatus(){return TaskSubmissionResult.unavailable(
            "STEERING_UNAVAILABLE","Steering is unavailable.");}
    default TaskSubmissionResult steeringHistory(){return steeringStatus();}
    default TaskSubmissionResult cancelSteering(String steeringId){return TaskSubmissionResult.unavailable(
            "STEERING_UNAVAILABLE","Steering is unavailable.");}
    default Optional<SteeringConfirmationRequest> pendingSteeringConfirmation(){return Optional.empty();}
    default TaskSubmissionResult respondSteeringConfirmation(String steeringId,String input){
        return TaskSubmissionResult.rejected("STEERING_CONFIRMATION_UNAVAILABLE","No Steering confirmation is active.");
    }

    default boolean awaitIdle(Duration timeout) {
        return true;
    }

    default void onTerminal(Runnable listener) { }
    default void onPromptRefresh(Runnable listener) { }

    default void closeSession() { }

    static TaskSubmissionPort runtimeNotConnected() {
        return request -> TaskSubmissionResult.unavailable(
                "INTERACTIVE_PREVIEW_UNAVAILABLE",
                "Interactive task runtime is unavailable in preview mode.");
    }
}
