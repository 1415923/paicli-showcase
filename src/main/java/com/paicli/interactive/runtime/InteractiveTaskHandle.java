package com.paicli.interactive.runtime;

import com.paicli.interactive.task.ForegroundTask;
import com.paicli.interactive.task.TaskSubmissionResult;

import java.util.concurrent.CompletableFuture;

/** Single-owner lifecycle for one submitted interactive task. */
public final class InteractiveTaskHandle {
    private final String taskId;
    private final CompletableFuture<InteractiveTaskResult> completion;
    private final ForegroundTask foreground;
    private boolean started;
    private boolean terminal;

    public InteractiveTaskHandle(String taskId,
            CompletableFuture<InteractiveTaskResult> completion) {
        if (taskId == null || taskId.isBlank() || completion == null) {
            throw new IllegalArgumentException("invalid interactive task handle");
        }
        this.taskId = taskId;
        this.completion = completion;
        this.foreground = new ForegroundTask(taskId, new CompletableFuture<>(),
                new CompletableFuture<>());
    }

    public String taskId() {
        return taskId;
    }

    public CompletableFuture<InteractiveTaskResult> completion() {
        return completion;
    }

    public ForegroundTask foreground() {
        return foreground;
    }

    public synchronized boolean tryAdmit(String admittedTaskId, String runId) {
        if (terminal || !taskId.equals(admittedTaskId)) return false;
        return foreground.admission().complete(
                new ForegroundTask.DurableAdmission(admittedTaskId, runId));
    }

    public synchronized boolean tryStart() {
        if (started || terminal) return false;
        started = true;
        return true;
    }

    public synchronized boolean cancelBeforeStart(InteractiveTaskResult result) {
        if (started || terminal) return false;
        terminal = true;
        completion.complete(result);
        completeForeground(result);
        return true;
    }

    public synchronized boolean tryComplete(InteractiveTaskResult result) {
        if (terminal) return false;
        terminal = true;
        completion.complete(result);
        completeForeground(result);
        return true;
    }

    public synchronized boolean started() {
        return started;
    }

    private void completeForeground(InteractiveTaskResult result) {
        foreground.terminal().complete(new TaskSubmissionResult(
                result.status(), result.code(), result.boundedMessage()));
    }
}
