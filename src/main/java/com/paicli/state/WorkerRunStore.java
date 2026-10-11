package com.paicli.state;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public final class WorkerRunStore {

    private static final Set<String> TERMINAL = Set.of("COMPLETED", "FAILED", "TIMED_OUT", "INTERRUPTED");
    private final Connection connection;

    public WorkerRunStore(Connection connection) {
        this.connection = connection;
    }

    public void create(String workerRunId, String taskId, String taskItemId,
                       String worktreeId, String occurrenceKey, int attemptNumber,
                       String providerName, String inputHash) throws SQLException {
        if (attemptNumber < 1) throw new IllegalArgumentException("attemptNumber must be >= 1");
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO worker_runs(
                    worker_run_id, task_id, task_item_id, worktree_id,
                    occurrence_key, attempt_number, status, provider_name, input_hash
                ) VALUES (?, ?, ?, ?, ?, ?, 'CREATED', ?, ?)
                """)) {
            statement.setString(1, WorkerStoreSupport.id(workerRunId, "workerRunId"));
            statement.setString(2, taskId);
            statement.setString(3, taskItemId);
            statement.setString(4, WorkerStoreSupport.id(worktreeId, "worktreeId"));
            statement.setString(5, WorkerStoreSupport.required(occurrenceKey, "occurrenceKey", 512));
            statement.setInt(6, attemptNumber);
            statement.setString(7, WorkerStoreSupport.optional(providerName, 256));
            statement.setString(8, WorkerStoreSupport.optionalHash(inputHash, "inputHash"));
            statement.executeUpdate();
        }
    }

    public void markPreparing(String id) throws SQLException { transition(id, "PREPARING", null, null, Set.of("CREATED")); }
    public void markRunning(String id) throws SQLException { transition(id, "RUNNING", null, null, Set.of("PREPARING")); }
    public void complete(String id, String outputHash) throws SQLException { transition(id, "COMPLETED", outputHash, null, Set.of("RUNNING")); }
    public void fail(String id, String error) throws SQLException { transition(id, "FAILED", null, error, Set.of("CREATED", "PREPARING", "RUNNING")); }
    public void timeOut(String id, String error) throws SQLException { transition(id, "TIMED_OUT", null, error, Set.of("PREPARING", "RUNNING")); }
    public void interrupt(String id, String error) throws SQLException { transition(id, "INTERRUPTED", null, error, Set.of("PREPARING", "RUNNING")); }

    public Optional<WorkerRunRecord> findById(String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT + " WHERE worker_run_id = ?")) {
            statement.setString(1, id);
            try (ResultSet rs = statement.executeQuery()) { return rs.next() ? Optional.of(map(rs)) : Optional.empty(); }
        }
    }

    public List<WorkerRunRecord> findByOccurrence(String worktreeId, String occurrenceKey) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT + """
                WHERE worktree_id = ? AND occurrence_key = ?
                ORDER BY attempt_number
                """)) {
            statement.setString(1, worktreeId);
            statement.setString(2, occurrenceKey);
            List<WorkerRunRecord> records = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) { while (rs.next()) records.add(map(rs)); }
            return records;
        }
    }

    private void transition(String id, String status, String outputHash, String error,
                            Set<String> allowedFrom) throws SQLException {
        String placeholders = String.join(",", allowedFrom.stream().map(value -> "?").toList());
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE worker_runs SET status = ?, output_hash = ?, error_excerpt = ?,
                    completed_at = CASE WHEN ? THEN datetime('now') ELSE completed_at END
                WHERE worker_run_id = ? AND status IN (%s)
                """.formatted(placeholders))) {
            statement.setString(1, status);
            statement.setString(2, WorkerStoreSupport.optionalHash(outputHash, "outputHash"));
            statement.setString(3, WorkerStoreSupport.optional(error, WorkerStoreSupport.MAX_EXCERPT_CHARS));
            statement.setBoolean(4, TERMINAL.contains(status));
            statement.setString(5, id);
            int i = 6;
            for (String from : allowedFrom) statement.setString(i++, from);
            WorkerStoreSupport.requireUpdated(statement, "Worker run not found or invalid state: " + id);
        }
    }

    private WorkerRunRecord map(ResultSet rs) throws SQLException {
        return new WorkerRunRecord(rs.getString("worker_run_id"), rs.getString("task_id"),
                rs.getString("task_item_id"), rs.getString("worktree_id"), rs.getString("occurrence_key"),
                rs.getInt("attempt_number"), rs.getString("status"), rs.getString("provider_name"),
                rs.getString("input_hash"), rs.getString("output_hash"), rs.getString("started_at"),
                rs.getString("completed_at"), rs.getString("error_excerpt"));
    }

    private static final String SELECT = """
            SELECT worker_run_id, task_id, task_item_id, worktree_id, occurrence_key,
                   attempt_number, status, provider_name, input_hash, output_hash,
                   started_at, completed_at, error_excerpt FROM worker_runs
            """;

    public record WorkerRunRecord(String workerRunId, String taskId, String taskItemId,
            String worktreeId, String occurrenceKey, int attemptNumber, String status,
            String providerName, String inputHash, String outputHash, String startedAt,
            String completedAt, String errorExcerpt) { }
}
