package com.paicli.interactive.cancel;

import com.paicli.runtime.CancellationToken;
import com.paicli.runtime.OwnedProcessRegistry;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class InteractiveCancellationController implements CancellationToken {
    private final AtomicInteger stage = new AtomicInteger();
    private final OwnedProcessRegistry processes = new OwnedProcessRegistry();
    private volatile Runnable approvalCancellation = () -> { };

    public RequestResult request() {
        return request(ignored -> { });
    }

    public RequestResult request(Consumer<Stage> afterTokenSet) {
        int next = stage.updateAndGet(value -> Math.min(2, value + 1));
        Stage requestedStage = next == 1 ? Stage.COOPERATIVE : Stage.FORCED;
        afterTokenSet.accept(requestedStage);
        approvalCancellation.run();
        int terminated = next == 2
                ? processes.forceTerminateAll() : processes.requestTerminateAll();
        return new RequestResult(requestedStage, terminated);
    }

    /** Sets cooperative cancellation before an approval Future is released. */
    public RequestResult requestFromApprovalDecision() {
        return requestFromApprovalDecision(ignored -> { });
    }

    public RequestResult requestFromApprovalDecision(Consumer<Stage> afterTokenSet) {
        int next = stage.updateAndGet(value -> value == 0 ? 1 : value);
        Stage requestedStage = next >= 2 ? Stage.FORCED : Stage.COOPERATIVE;
        afterTokenSet.accept(requestedStage);
        int terminated = processes.requestTerminateAll();
        return new RequestResult(requestedStage, terminated);
    }

    public void onApprovalCancellation(Runnable action) {
        approvalCancellation = action == null ? () -> { } : action;
    }

    public OwnedProcessRegistry processes() { return processes; }
    /** Called only after the foreground Task has reached a durable terminal state. */
    public void resetAfterTerminal() {
        stage.set(0);
    }
    @Override public boolean cancellationRequested() { return stage.get() >= 1; }
    @Override public boolean forceRequested() { return stage.get() >= 2; }

    public enum Stage { COOPERATIVE, FORCED }
    public record RequestResult(Stage stage, int terminatedOwnedProcesses) { }
}
