package com.paicli.interactive.query;

import com.paicli.interactive.session.InteractiveSession;
import com.paicli.state.StateStore;
import com.paicli.state.TaskStore;

import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Optional;

/** Resolves current/latest task identity exclusively inside one canonical workspace store. */
public final class PersistedRecentTaskResolver {
    private final Path workspaceIdentity;

    public PersistedRecentTaskResolver(Path workspace) {
        try {
            Path supplied = workspace.toAbsolutePath().normalize();
            this.workspaceIdentity = supplied.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!java.nio.file.Files.isDirectory(workspaceIdentity, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("workspace is not a directory");
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("workspace identity is unavailable", error);
        }
    }

    public Path workspaceIdentity() {
        return workspaceIdentity;
    }

    public Optional<TaskStore.TaskRecord> latest(StateStore state) throws SQLException {
        return state.tasks.findLatest();
    }

    public Optional<TaskStore.TaskRecord> resolve(
            StateStore state, InteractiveSession.Snapshot session, String selector)
            throws SQLException {
        if ("current".equals(selector)) {
            return session.currentTaskId() == null ? Optional.empty()
                    : state.tasks.findById(session.currentTaskId());
        }
        if (selector == null || selector.isBlank()) {
            return session.currentTaskId() == null ? latest(state)
                    : state.tasks.findById(session.currentTaskId());
        }
        if ("latest".equals(selector)) return latest(state);
        return state.tasks.findById(selector);
    }

}
