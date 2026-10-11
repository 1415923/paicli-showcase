package com.paicli.state;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

public final class WorkerTaskStore {

    private final Connection connection;

    public WorkerTaskStore(Connection connection) {
        this.connection = connection;
    }

    public void create(String taskItemId, String parentTaskId,
                       String specificationSummary, String specificationHash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO worker_task_items(
                    task_item_id, parent_task_id, specification_summary,
                    specification_hash, status
                ) VALUES (?, ?, ?, ?, 'PENDING')
                """)) {
            statement.setString(1, WorkerStoreSupport.id(taskItemId, "taskItemId"));
            statement.setString(2, parentTaskId);
            statement.setString(3, WorkerStoreSupport.required(
                    specificationSummary, "specificationSummary", WorkerStoreSupport.MAX_EXCERPT_CHARS));
            statement.setString(4, WorkerStoreSupport.hash(specificationHash, "specificationHash"));
            statement.executeUpdate();
        }
    }

    public Optional<WorkerTaskItemRecord> findById(String taskItemId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT task_item_id, parent_task_id, specification_summary,
                       specification_hash, status, created_at, completed_at
                FROM worker_task_items WHERE task_item_id = ?
                """)) {
            statement.setString(1, taskItemId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(new WorkerTaskItemRecord(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7))) : Optional.empty();
            }
        }
    }

    public void fail(String taskItemId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE worker_task_items SET status = 'FAILED', completed_at = datetime('now') "
                        + "WHERE task_item_id = ? AND status IN ('PENDING', 'CLAIMED')")) {
            statement.setString(1, taskItemId);
            WorkerStoreSupport.requireUpdated(statement,
                    "Task item not found or invalid state: " + taskItemId);
        }
    }
    void markClaimed(String taskItemId) throws SQLException {
        transition(taskItemId, "PENDING", "CLAIMED", false);
    }

    void markPendingAfterClaim(String taskItemId) throws SQLException {
        transition(taskItemId, "CLAIMED", "PENDING", false);
    }

    void markCompleted(String taskItemId) throws SQLException {
        transition(taskItemId, "CLAIMED", "COMPLETED", true);
    }

    private void transition(String id, String from, String to, boolean complete) throws SQLException {
        String sql = "UPDATE worker_task_items SET status = ?"
                + (complete ? ", completed_at = datetime('now')" : "")
                + " WHERE task_item_id = ? AND status = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, to);
            statement.setString(2, id);
            statement.setString(3, from);
            WorkerStoreSupport.requireUpdated(statement, "Task item not found or invalid state: " + id);
        }
    }

    public record WorkerTaskItemRecord(String taskItemId, String parentTaskId,
            String specificationSummary, String specificationHash, String status,
            String createdAt, String completedAt) { }
}

