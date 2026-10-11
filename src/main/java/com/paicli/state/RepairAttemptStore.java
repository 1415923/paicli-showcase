package com.paicli.state;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class RepairAttemptStore {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    private final Connection connection;

    public RepairAttemptStore(Connection connection) {
        this.connection = connection;
    }

    public void recordStarted(String repairId, String runId, String taskId,
                              int attemptNo, String triggerVerificationId,
                              String failureSummary) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO repair_attempts(
                    repair_id, run_id, task_id, attempt_no,
                    trigger_verification_id, status, failure_summary
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, repairId);
            statement.setString(2, runId);
            statement.setString(3, taskId);
            statement.setInt(4, attemptNo);
            statement.setString(5, triggerVerificationId);
            statement.setString(6, STATUS_RUNNING);
            statement.setString(7, failureSummary);
            statement.executeUpdate();
        }
    }

    public void markCompleted(String repairId) throws SQLException {
        complete(repairId, STATUS_COMPLETED);
    }

    public void markFailed(String repairId) throws SQLException {
        complete(repairId, STATUS_FAILED);
    }

    public Optional<RepairAttemptRecord> findById(String repairId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_COLUMNS + " WHERE repair_id = ?")) {
            statement.setString(1, repairId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(toRecord(resultSet)) : Optional.empty();
            }
        }
    }

    public List<RepairAttemptRecord> findByRun(String runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_COLUMNS + """
                WHERE run_id = ?
                ORDER BY attempt_no
                """)) {
            statement.setString(1, runId);
            List<RepairAttemptRecord> records = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    records.add(toRecord(resultSet));
                }
            }
            return records;
        }
    }

    private void complete(String repairId, String status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE repair_attempts
                SET status = ?, completed_at = datetime('now')
                WHERE repair_id = ? AND status = 'RUNNING'
                """)) {
            statement.setString(1, status);
            statement.setString(2, repairId);
            if (statement.executeUpdate() == 0) {
                throw new SQLException("Repair attempt not found or invalid state: " + repairId);
            }
        }
    }

    private RepairAttemptRecord toRecord(ResultSet resultSet) throws SQLException {
        return new RepairAttemptRecord(
                resultSet.getString("repair_id"), resultSet.getString("run_id"),
                resultSet.getString("task_id"), resultSet.getInt("attempt_no"),
                resultSet.getString("trigger_verification_id"),
                resultSet.getString("status"), resultSet.getString("failure_summary"),
                resultSet.getString("started_at"), resultSet.getString("completed_at")
        );
    }

    private static final String SELECT_COLUMNS = """
            SELECT repair_id, run_id, task_id, attempt_no, trigger_verification_id,
                   status, failure_summary, started_at, completed_at
            FROM repair_attempts
            """;

    public record RepairAttemptRecord(
            String repairId, String runId, String taskId, int attemptNo,
            String triggerVerificationId, String status, String failureSummary,
            String startedAt, String completedAt
    ) {
    }
}
