package com.paicli.state;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

public final class TaskClaimStore {

    private final Connection connection;

    public TaskClaimStore(Connection connection) {
        this.connection = connection;
    }

    public void acquireTaskClaim(String claimId, String taskItemId, String workerRunId,
                                 String occurrenceKey, int attemptNumber) throws SQLException {
        if (attemptNumber < 1) throw new IllegalArgumentException("attemptNumber must be >= 1");
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement claim = connection.prepareStatement("""
                    INSERT INTO task_claims(
                        task_claim_id, task_item_id, worker_run_id, occurrence_key,
                        attempt_number, status
                    ) VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                    """)) {
                claim.setString(1, WorkerStoreSupport.id(claimId, "taskClaimId"));
                claim.setString(2, WorkerStoreSupport.id(taskItemId, "taskItemId"));
                claim.setString(3, WorkerStoreSupport.id(workerRunId, "workerRunId"));
                claim.setString(4, WorkerStoreSupport.required(occurrenceKey, "occurrenceKey", 512));
                claim.setInt(5, attemptNumber);
                claim.executeUpdate();
            }
            try (PreparedStatement item = connection.prepareStatement("""
                    UPDATE worker_task_items SET status = 'CLAIMED'
                    WHERE task_item_id = ? AND status = 'PENDING'
                    """)) {
                item.setString(1, taskItemId);
                WorkerStoreSupport.requireUpdated(item, "Task item not found or unavailable: " + taskItemId);
            }
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    public void release(String claimId) throws SQLException { finish(claimId, "RELEASED", null, true, false); }
    public void interrupt(String claimId, String reason) throws SQLException { finish(claimId, "INTERRUPTED", reason, false, false); }
    public void complete(String claimId) throws SQLException { finish(claimId, "COMPLETED", null, false, true); }

    public Optional<TaskClaimRecord> findById(String claimId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT task_claim_id, task_item_id, worker_run_id, occurrence_key,
                       attempt_number, status, claimed_at, released_at,
                       completed_at, interruption_reason
                FROM task_claims WHERE task_claim_id = ?
                """)) {
            statement.setString(1, claimId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(new TaskClaimRecord(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getInt(5), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getString(9), rs.getString(10))) : Optional.empty();
            }
        }
    }

    private void finish(String claimId, String status, String reason,
                        boolean returnTaskToPending, boolean completeTask) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            String timeColumn = "COMPLETED".equals(status) ? "completed_at" : "released_at";
            String taskItemId;
            try (PreparedStatement statement = connection.prepareStatement("UPDATE task_claims SET status = ?, "
                    + timeColumn + " = datetime('now'), interruption_reason = ? "
                    + "WHERE task_claim_id = ? AND status = 'ACTIVE' RETURNING task_item_id")) {
                statement.setString(1, status);
                statement.setString(2, WorkerStoreSupport.optional(reason, WorkerStoreSupport.MAX_EXCERPT_CHARS));
                statement.setString(3, claimId);
                try (ResultSet rs = statement.executeQuery()) {
                    if (!rs.next()) throw new SQLException("Task claim not found or invalid state: " + claimId);
                    taskItemId = rs.getString(1);
                }
            }
            if (returnTaskToPending) {
                updateTask(taskItemId, "PENDING", false);
            } else if (completeTask) {
                updateTask(taskItemId, "COMPLETED", true);
            }
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private void updateTask(String taskItemId, String status, boolean complete) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE worker_task_items SET status = ?"
                + (complete ? ", completed_at = datetime('now')" : "")
                + " WHERE task_item_id = ? AND status = 'CLAIMED'")) {
            statement.setString(1, status);
            statement.setString(2, taskItemId);
            WorkerStoreSupport.requireUpdated(statement, "Task item claim state mismatch: " + taskItemId);
        }
    }

    public record TaskClaimRecord(String taskClaimId, String taskItemId, String workerRunId,
            String occurrenceKey, int attemptNumber, String status, String claimedAt,
            String releasedAt, String completedAt, String interruptionReason) { }
}
