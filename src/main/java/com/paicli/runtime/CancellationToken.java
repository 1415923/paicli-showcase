package com.paicli.runtime;

public interface CancellationToken {
    CancellationToken NONE = new CancellationToken() {
        public boolean cancellationRequested() { return false; }
        public boolean forceRequested() { return false; }
    };

    boolean cancellationRequested();
    boolean forceRequested();

    default void throwIfCancellationRequested() {
        if (cancellationRequested() || Thread.currentThread().isInterrupted()) {
            throw new TaskCancellationException(forceRequested());
        }
    }

    final class TaskCancellationException extends RuntimeException {
        private final boolean forced;
        public TaskCancellationException(boolean forced) {
            super(forced ? "Task cancellation forced" : "Task cancellation requested");
            this.forced = forced;
        }
        public boolean forced() { return forced; }
    }
}
