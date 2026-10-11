package com.paicli.interactive.query;

import com.paicli.interactive.session.InteractiveSession;

import java.util.List;
import java.util.Optional;
import com.paicli.interactive.view.InteractiveSessionViewModel;
import com.paicli.interactive.view.OperationalStatusView;

public interface InteractiveQueryFacade {
    List<String> status(InteractiveSession.Snapshot session);

    default List<String> status(InteractiveSession.Snapshot session, List<String> arguments) {
        return status(session);
    }

    default Optional<InteractiveSessionViewModel> viewModel(
            InteractiveSession.Snapshot session) {
        ViewModelReadResult result = readViewModel(session);
        return result.state() == ViewModelReadResult.State.PRESENT
                ? Optional.of(result.value()) : Optional.empty();
    }

    default ViewModelReadResult readViewModel(InteractiveSession.Snapshot session) {
        return ViewModelReadResult.absent();
    }

    default Optional<OperationalStatusView> operationalStatus(
            InteractiveSession.Snapshot session) {
        ViewModelReadResult result = readViewModel(session);
        return result.state() == ViewModelReadResult.State.PRESENT
                ? Optional.of(result.value().operationalStatus()) : Optional.empty();
    }

    default List<String> providerStatus(InteractiveSession.Snapshot session) {
        return List.of("Provider status is unavailable.");
    }

    List<String> plan(InteractiveSession.Snapshot session);

    default List<String> plan(InteractiveSession.Snapshot session, boolean details) {
        return plan(session);
    }

    List<String> tasks(int limit);

    default List<String> tasks(List<String> arguments) {
        return tasks(10);
    }

    default List<String> tasks(InteractiveSession.Snapshot session,
            List<String> arguments) {
        java.util.ArrayList<String> lines=new java.util.ArrayList<>(
                status(session,List.of("task")));
        lines.addAll(tasks(arguments));
        return List.copyOf(lines);
    }

    default List<InteractiveTaskView> taskViews(String filter, int limit) {
        return List.of();
    }

    default List<InteractiveSnapshotView> snapshotViews(String taskId, int limit) {
        return List.of();
    }

    default Optional<InteractiveSnapshotView> snapshotView(String snapshotId) {
        return Optional.empty();
    }

    default Optional<InteractiveTaskView> latestTask() {
        return Optional.empty();
    }

    default Optional<InteractiveTaskView> latestTaskWithSnapshots() {
        return Optional.empty();
    }

    default boolean hasRecoverableTask() {
        return !taskViews("recoverable", 1).isEmpty();
    }

    default void rewindRecorded(String taskId, String relativePath, String snapshotId) { }

    default List<String> diff(InteractiveSession.Snapshot session) {
        return List.of("No proposed or applied changes.");
    }

    List<String> report(InteractiveSession.Snapshot session, String taskId);

    static InteractiveQueryFacade unavailable() {
        return new InteractiveQueryFacade() {
            private List<String> result() {
                return List.of("Interactive persisted queries are unavailable in preview mode.");
            }
            public ViewModelReadResult readViewModel(InteractiveSession.Snapshot session) {
                return ViewModelReadResult.unavailable("INTERACTIVE_QUERY_UNAVAILABLE");
            }
            public List<String> status(InteractiveSession.Snapshot session) { return List.of(); }
            public List<String> plan(InteractiveSession.Snapshot session) { return result(); }
            public List<String> tasks(int limit) { return result(); }
            public List<String> tasks(List<String> arguments) { return result(); }
            public List<String> diff(InteractiveSession.Snapshot session) { return result(); }
            public List<String> report(InteractiveSession.Snapshot session, String taskId) {
                return result();
            }
        };
    }
}
