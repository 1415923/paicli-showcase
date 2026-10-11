package com.paicli.state;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class TaskStore {

    public static final String STATUS_CREATED = "CREATED";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_COMPLETED_VERIFIED = "COMPLETED_VERIFIED";
    public static final String STATUS_COMPLETED_UNVERIFIED = "COMPLETED_UNVERIFIED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_CANCELLED_UNKNOWN_OUTCOME = "CANCELLED_UNKNOWN_OUTCOME";

    private final Connection connection;

    public TaskStore(Connection connection) {
        this.connection = connection;
    }

    public void createTask(String taskId, String goal, String provider, String model,
                           String approvalMode, int maxIterations) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO tasks(task_id, goal, provider, model, approval_mode, max_iterations)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, taskId);
            statement.setString(2, goal);
            statement.setString(3, provider);
            statement.setString(4, model);
            statement.setString(5, approvalMode);
            statement.setInt(6, maxIterations);
            statement.executeUpdate();
        }
    }

    public void markRunning(String taskId) throws SQLException {
        updateStatus(taskId, STATUS_RUNNING, null, false);
    }

    public void prepareForResume(String taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE tasks
                SET status = ?, last_error = NULL, completed_at = NULL,
                    updated_at = datetime('now')
                WHERE task_id = ?
                """)) {
            statement.setString(1, STATUS_RUNNING);
            statement.setString(2, taskId);
            requireUpdated(taskId, statement.executeUpdate());
        }
    }

    public void updateIterations(String taskId, int iterationsCompleted) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE tasks
                SET iterations_completed = ?, updated_at = datetime('now')
                WHERE task_id = ?
                """)) {
            statement.setInt(1, iterationsCompleted);
            statement.setString(2, taskId);
            requireUpdated(taskId, statement.executeUpdate());
        }
    }

    public void markDone(String taskId) throws SQLException {
        updateStatus(taskId, STATUS_DONE, null, true);
    }

    public void markInteractiveTerminal(String taskId, String status) throws SQLException {
        if (!java.util.Set.of(STATUS_DONE, STATUS_COMPLETED_VERIFIED,
                STATUS_COMPLETED_UNVERIFIED, STATUS_REJECTED).contains(status)) {
            throw new IllegalArgumentException("invalid interactive terminal status");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE tasks
                SET status = ?, updated_at = datetime('now'), completed_at = datetime('now')
                WHERE task_id = ? AND status = ?
                """)) {
            statement.setString(1, status);
            statement.setString(2, taskId);
            statement.setString(3, STATUS_DONE);
            requireUpdated(taskId, statement.executeUpdate());
        }
    }

    public static boolean isTerminal(String status) {
        return java.util.Set.of(STATUS_DONE, STATUS_FAILED,
                STATUS_COMPLETED_VERIFIED, STATUS_COMPLETED_UNVERIFIED,
                STATUS_REJECTED, STATUS_CANCELLED,
                STATUS_CANCELLED_UNKNOWN_OUTCOME).contains(status);
    }

    public void markCancelled(String taskId, boolean outcomeUnknown, String reason)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE tasks
                SET status = ?, last_error = ?, updated_at = datetime('now'),
                    completed_at = datetime('now')
                WHERE task_id = ? AND status IN ('CREATED', 'RUNNING')
                """)) {
            statement.setString(1, outcomeUnknown ? STATUS_CANCELLED_UNKNOWN_OUTCOME
                    : STATUS_CANCELLED);
            statement.setString(2, reason);
            statement.setString(3, taskId);
            requireUpdated(taskId, statement.executeUpdate());
        }
    }

    public void markFailed(String taskId, String error) throws SQLException {
        updateStatus(taskId, STATUS_FAILED, error, true);
    }

    public Optional<TaskRecord> findById(String taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT task_id, goal, status, provider, model, approval_mode,
                       iterations_completed, max_iterations, last_error,
                       created_at, updated_at, completed_at
                FROM tasks
                WHERE task_id = ?
                """)) {
            statement.setString(1, taskId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                return Optional.of(toRecord(resultSet));
            }
        }
    }

    public List<TaskRecord> findAll(int limit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT task_id, goal, status, provider, model, approval_mode,
                       iterations_completed, max_iterations, last_error,
                       created_at, updated_at, completed_at
                FROM tasks
                ORDER BY updated_at DESC, task_id DESC
                LIMIT ?
                """)) {
            statement.setInt(1, limit);
            return findMany(statement);
        }
    }

    public Optional<TaskRecord> findLatest() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT task_id, goal, status, provider, model, approval_mode,
                       iterations_completed, max_iterations, last_error,
                       created_at, updated_at, completed_at
                FROM tasks
                ORDER BY updated_at DESC, task_id DESC
                LIMIT 1
                """)) {
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(toRecord(resultSet)) : Optional.empty();
            }
        }
    }
    public List<TaskRecord> findByStatus(String status, int limit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT task_id, goal, status, provider, model, approval_mode,
                       iterations_completed, max_iterations, last_error,
                       created_at, updated_at, completed_at
                FROM tasks
                WHERE status = ?
                ORDER BY updated_at DESC, task_id DESC
                LIMIT ?
                """)) {
            statement.setString(1, status);
            statement.setInt(2, limit);
            return findMany(statement);
        }
    }

    private void updateStatus(String taskId, String status, String error, boolean completed) throws SQLException {
        String sql = completed
                ? """
                UPDATE tasks
                SET status = ?, last_error = ?, updated_at = datetime('now'), completed_at = datetime('now')
                WHERE task_id = ?
                """
                : """
                UPDATE tasks
                SET status = ?, last_error = ?, updated_at = datetime('now')
                WHERE task_id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, status);
            statement.setString(2, error);
            statement.setString(3, taskId);
            requireUpdated(taskId, statement.executeUpdate());
        }
    }

    private void requireUpdated(String taskId, int updatedRows) throws SQLException {
        if (updatedRows == 0) {
            throw new SQLException("Task not found: " + taskId);
        }
    }

    private List<TaskRecord> findMany(PreparedStatement statement) throws SQLException {
        List<TaskRecord> records = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                records.add(toRecord(resultSet));
            }
        }
        return records;
    }

    private TaskRecord toRecord(ResultSet resultSet) throws SQLException {
        return new TaskRecord(
                resultSet.getString("task_id"),
                resultSet.getString("goal"),
                resultSet.getString("status"),
                resultSet.getString("provider"),
                resultSet.getString("model"),
                resultSet.getString("approval_mode"),
                resultSet.getInt("iterations_completed"),
                resultSet.getInt("max_iterations"),
                resultSet.getString("last_error"),
                resultSet.getString("created_at"),
                resultSet.getString("updated_at"),
                resultSet.getString("completed_at")
        );
    }

    public record TaskRecord(String taskId, String goal, String status,
                             String provider, String model, String approvalMode,
                             int iterationsCompleted, int maxIterations, String lastError,
                             String createdAt, String updatedAt, String completedAt) {
    }
}
