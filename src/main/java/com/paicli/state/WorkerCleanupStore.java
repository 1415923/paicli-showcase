package com.paicli.state;

import java.sql.*;
import java.util.*;

/** Audits explicit component-by-component cleanup without deleting resources itself. */
public final class WorkerCleanupStore {
    private static final Set<String> ACTORS = Set.of("USER", "RUNTIME");
    private static final Set<String> COMPONENT = Set.of("PENDING", "SUCCEEDED", "FAILED",
            "SKIPPED", "RETAINED");
    private final Connection connection;

    public WorkerCleanupStore(Connection connection) { this.connection = connection; }

    public void start(String id, String worktreeId, String requestedBy) throws SQLException {
        startTarget(id, "WORKTREE", worktreeId, requestedBy);
    }

    public void startProposal(String id, String proposalId, String requestedBy) throws SQLException {
        startTarget(id, "PROPOSAL", proposalId, requestedBy);
    }

    private void startTarget(String id, String type, String targetId, String actor)
            throws SQLException {
        WorkerStoreSupport.oneOf(actor, "requestedBy", ACTORS);
        try (PreparedStatement s = connection.prepareStatement("""
                INSERT INTO worker_cleanup_runs(
                  cleanup_run_id, worktree_id, proposal_id, target_type, requested_by,
                  status, workspace_cleanup_status, state_cleanup_status, trace_cleanup_status
                ) VALUES (?, ?, ?, ?, ?, 'RUNNING', 'PENDING', 'PENDING', 'PENDING')
                """)) {
            s.setString(1, WorkerStoreSupport.id(id, "cleanupRunId"));
            s.setString(2, "WORKTREE".equals(type)
                    ? WorkerStoreSupport.id(targetId, "worktreeId") : null);
            s.setString(3, "PROPOSAL".equals(type)
                    ? WorkerStoreSupport.id(targetId, "proposalId") : null);
            s.setString(4, type);
            s.setString(5, actor);
            s.executeUpdate();
        }
    }

    public void complete(String id, String workspace, String state, String trace)
            throws SQLException {
        updateTerminal(id, "COMPLETED", workspace, state, trace, null, true);
    }

    public void fail(String id, String workspace, String state, String trace, String error)
            throws SQLException {
        boolean partial = "SUCCEEDED".equals(workspace) || "SUCCEEDED".equals(state)
                || "SUCCEEDED".equals(trace);
        updateTerminal(id, partial ? "PARTIAL" : "FAILED",
                workspace, state, trace, error, false);
    }

    public Optional<WorkerCleanupRecord> findById(String id) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement(SELECT + " WHERE cleanup_run_id = ?")) {
            s.setString(1, id);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public Optional<WorkerCleanupRecord> findLatestForTarget(String type, String id)
            throws SQLException {
        if (!Set.of("WORKTREE", "PROPOSAL").contains(type))
            throw new IllegalArgumentException("Unknown cleanup target type");
        String column = "WORKTREE".equals(type) ? "worktree_id" : "proposal_id";
        try (PreparedStatement s = connection.prepareStatement(
                SELECT + " WHERE target_type = ? AND " + column
                        + " = ? ORDER BY started_at DESC LIMIT 1")) {
            s.setString(1, type);
            s.setString(2, id);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    private void updateTerminal(String id, String status, String workspace, String state,
                                String trace, String error, boolean requireAllSuccess)
            throws SQLException {
        WorkerStoreSupport.oneOf(workspace, "workspaceCleanupStatus", COMPONENT);
        WorkerStoreSupport.oneOf(state, "stateCleanupStatus", COMPONENT);
        WorkerStoreSupport.oneOf(trace, "traceCleanupStatus", COMPONENT);
        if (requireAllSuccess && !("SUCCEEDED".equals(workspace)
                && "SUCCEEDED".equals(state) && "SUCCEEDED".equals(trace)))
            throw new IllegalArgumentException("Completed cleanup requires all components to succeed");
        try (PreparedStatement s = connection.prepareStatement("""
                UPDATE worker_cleanup_runs SET status = ?, workspace_cleanup_status = ?,
                  state_cleanup_status = ?, trace_cleanup_status = ?, error_excerpt = ?,
                  completed_at = datetime('now')
                WHERE cleanup_run_id = ? AND status = 'RUNNING'
                """)) {
            s.setString(1, status);
            s.setString(2, workspace);
            s.setString(3, state);
            s.setString(4, trace);
            s.setString(5, WorkerStoreSupport.optional(error, 4096));
            s.setString(6, id);
            WorkerStoreSupport.requireUpdated(s, "Cleanup run not found or invalid state: " + id);
        }
    }

    private WorkerCleanupRecord map(ResultSet rs) throws SQLException {
        return new WorkerCleanupRecord(rs.getString("cleanup_run_id"),
                rs.getString("target_type"), rs.getString("worktree_id"),
                rs.getString("proposal_id"), rs.getString("requested_by"),
                rs.getString("status"), rs.getString("workspace_cleanup_status"),
                rs.getString("state_cleanup_status"), rs.getString("trace_cleanup_status"),
                rs.getString("started_at"), rs.getString("completed_at"),
                rs.getString("error_excerpt"));
    }

    private static final String SELECT = """
            SELECT cleanup_run_id, target_type, worktree_id, proposal_id, requested_by,
              status, workspace_cleanup_status, state_cleanup_status, trace_cleanup_status,
              started_at, completed_at, error_excerpt FROM worker_cleanup_runs
            """;

    public record WorkerCleanupRecord(String cleanupRunId, String targetType,
            String worktreeId, String proposalId, String requestedBy, String status,
            String workspaceCleanupStatus, String stateCleanupStatus,
            String traceCleanupStatus, String startedAt, String completedAt,
            String errorExcerpt) { }
}
