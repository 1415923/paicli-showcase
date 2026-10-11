package com.paicli.worker;

import com.paicli.tool.Tool;

/**
 * Narrow lifecycle bridge used by the bounded team coordinator.
 * The default keeps the standalone R3 Worker behavior unchanged.
 */
public interface WorkerCoordinatorBridge {
    WorkerCoordinatorBridge NONE = new WorkerCoordinatorBridge() { };

    default void beforeProvider(WorktreeWorkerRunRequest request, String workerRunId,
                                int attemptNumber, String inputHash) { }

    default String startupContext(WorktreeWorkerRunRequest request, String workerRunId) {
        return "";
    }

    default Tool mailboxTool(WorktreeWorkerRunRequest request, String workerRunId) {
        return null;
    }
}
