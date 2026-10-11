package com.paicli.state;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

public class TaskPlanStore {

    public static final String STATUS_GENERATED = "GENERATED";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    private final Connection connection;

    public TaskPlanStore(Connection connection) {
        this.connection = connection;
    }

    public void recordGenerated(String planId, String runId, String taskId, String planText)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO task_plans(plan_id, run_id, task_id, plan_text, status)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, planId);
            statement.setString(2, runId);
            statement.setString(3, taskId);
            statement.setString(4, planText);
            statement.setString(5, STATUS_GENERATED);
            statement.executeUpdate();
        }
    }

    public void markActive(String planId) throws SQLException {
        transition(planId, STATUS_GENERATED, STATUS_ACTIVE);
    }

    public void markCompleted(String planId) throws SQLException {
        transition(planId, STATUS_ACTIVE, STATUS_COMPLETED);
    }

    public void markFailed(String planId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE task_plans
                SET status = ?, updated_at = datetime('now')
                WHERE plan_id = ? AND status IN ('GENERATED', 'ACTIVE')
                """)) {
            statement.setString(1, STATUS_FAILED);
            statement.setString(2, planId);
            requireUpdated(statement, planId);
        }
    }

    public Optional<TaskPlanRecord> findById(String planId) throws SQLException {
        return findOne("plan_id", planId);
    }

    public Optional<TaskPlanRecord> findByRun(String runId) throws SQLException {
        return findOne("run_id", runId);
    }

    private Optional<TaskPlanRecord> findOne(String column, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_COLUMNS
                + " WHERE " + column + " = ?")) {
            statement.setString(1, value);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(toRecord(resultSet)) : Optional.empty();
            }
        }
    }

    private void transition(String planId, String expectedStatus, String nextStatus)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE task_plans
                SET status = ?, updated_at = datetime('now')
                WHERE plan_id = ? AND status = ?
                """)) {
            statement.setString(1, nextStatus);
            statement.setString(2, planId);
            statement.setString(3, expectedStatus);
            requireUpdated(statement, planId);
        }
    }

    private void requireUpdated(PreparedStatement statement, String planId) throws SQLException {
        if (statement.executeUpdate() == 0) {
            throw new SQLException("Plan not found or invalid state: " + planId);
        }
    }

    private TaskPlanRecord toRecord(ResultSet resultSet) throws SQLException {
        return new TaskPlanRecord(
                resultSet.getString("plan_id"), resultSet.getString("run_id"),
                resultSet.getString("task_id"), resultSet.getString("plan_text"),
                resultSet.getString("status"), resultSet.getString("created_at"),
                resultSet.getString("updated_at")
        );
    }

    private static final String SELECT_COLUMNS = """
            SELECT plan_id, run_id, task_id, plan_text, status, created_at, updated_at
            FROM task_plans
            """;

    public record TaskPlanRecord(
            String planId, String runId, String taskId, String planText,
            String status, String createdAt, String updatedAt
    ) {
    }
}
