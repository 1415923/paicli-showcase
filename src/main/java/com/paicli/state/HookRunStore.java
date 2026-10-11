package com.paicli.state;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Persists hook lifecycle results; callers must sanitize commands and excerpts. */
public class HookRunStore {

    public static final String EVENT_BEFORE_PLAN = "BEFORE_PLAN";
    public static final String EVENT_AFTER_PLAN = "AFTER_PLAN";
    public static final String EVENT_BEFORE_EXECUTE = "BEFORE_EXECUTE";
    public static final String EVENT_AFTER_EXECUTE = "AFTER_EXECUTE";
    public static final String EVENT_BEFORE_VERIFY = "BEFORE_VERIFY";
    public static final String EVENT_AFTER_VERIFY = "AFTER_VERIFY";
    public static final String EVENT_BEFORE_REPAIR = "BEFORE_REPAIR";
    public static final String EVENT_AFTER_REPAIR = "AFTER_REPAIR";
    public static final String EVENT_BEFORE_FINISH = "BEFORE_FINISH";

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_PASSED = "PASSED";
    public static final String STATUS_WARNED = "WARNED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_BLOCKED = "BLOCKED";
    public static final String STATUS_TIMED_OUT = "TIMED_OUT";
    public static final String STATUS_ERROR = "ERROR";
    public static final String STATUS_INTERRUPTED = "INTERRUPTED";
    /** Typed runtime/recovery outcome backed by steering_producer_pause_facts. */
    public static final String STATUS_PAUSED_BY_STEERING = "PAUSED_BY_STEERING";

    public static final String POLICY_WARN = "warn";
    public static final String POLICY_FAIL = "fail";

    private final Connection connection;

    public HookRunStore(Connection connection) {
        this.connection = connection;
    }

    public void recordStarted(String hookRunId, String runId, String taskId,
                              String hookId, String event,
                              String occurrenceKey, int sequenceNo,
                              String command, String failurePolicy)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO hook_runs(
                    hook_run_id, run_id, task_id, hook_id, event,
                    occurrence_key, sequence_no, command, status, failure_policy
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, hookRunId);
            statement.setString(2, runId);
            statement.setString(3, taskId);
            statement.setString(4, hookId);
            statement.setString(5, event);
            statement.setString(6, occurrenceKey);
            statement.setInt(7, sequenceNo);
            statement.setString(8, command);
            statement.setString(9, STATUS_RUNNING);
            statement.setString(10, failurePolicy);
            statement.executeUpdate();
        }
    }

    public void markPassed(String hookRunId, int exitCode,
                           String outputExcerpt, String errorExcerpt,
                           long durationMs) throws SQLException {
        complete(hookRunId, STATUS_PASSED, exitCode,
                outputExcerpt, errorExcerpt, durationMs);
    }

    public void markWarned(String hookRunId, Integer exitCode,
                           String outputExcerpt, String errorExcerpt,
                           long durationMs) throws SQLException {
        complete(hookRunId, STATUS_WARNED, exitCode,
                outputExcerpt, errorExcerpt, durationMs);
    }

    public void markFailed(String hookRunId, Integer exitCode,
                           String outputExcerpt, String errorExcerpt,
                           long durationMs) throws SQLException {
        complete(hookRunId, STATUS_FAILED, exitCode,
                outputExcerpt, errorExcerpt, durationMs);
    }

    public void markBlocked(String hookRunId, String errorExcerpt,
                            long durationMs) throws SQLException {
        complete(hookRunId, STATUS_BLOCKED, null,
                null, errorExcerpt, durationMs);
    }

    public void markTimedOut(String hookRunId, String outputExcerpt,
                             String errorExcerpt, long durationMs)
            throws SQLException {
        complete(hookRunId, STATUS_TIMED_OUT, null,
                outputExcerpt, errorExcerpt, durationMs);
    }

    public void markError(String hookRunId, String outputExcerpt,
                          String errorExcerpt, long durationMs)
            throws SQLException {
        complete(hookRunId, STATUS_ERROR, null,
                outputExcerpt, errorExcerpt, durationMs);
    }

    public void markInterrupted(String hookRunId, String errorExcerpt,
                                long durationMs) throws SQLException {
        complete(hookRunId, STATUS_INTERRUPTED, null,
                null, errorExcerpt, durationMs);
    }

    public Optional<HookRunRecord> findById(String hookRunId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_COLUMNS + " WHERE hook_run_id = ?")) {
            statement.setString(1, hookRunId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(toRecord(resultSet)) : Optional.empty();
            }
        }
    }

    public boolean wasPausedBySteering(String hookRunId) throws SQLException {
        try(PreparedStatement statement=connection.prepareStatement("""
                SELECT 1 FROM steering_producer_pause_facts
                WHERE producer_kind='HOOK' AND producer_identity=?
                """)){
            statement.setString(1,hookRunId);
            try(ResultSet rows=statement.executeQuery()){return rows.next();}
        }
    }

    public Optional<HookRunRecord> findByOccurrence(
            String runId, String event, String occurrenceKey, String hookId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_COLUMNS + """
                        WHERE run_id = ? AND event = ?
                          AND occurrence_key = ? AND hook_id = ?
                        """)) {
            statement.setString(1, runId);
            statement.setString(2, event);
            statement.setString(3, occurrenceKey);
            statement.setString(4, hookId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? Optional.of(toRecord(resultSet)) : Optional.empty();
            }
        }
    }

    public List<HookRunRecord> findByRun(String runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_COLUMNS + """
                        WHERE run_id = ?
                        ORDER BY started_at, rowid
                        """)) {
            statement.setString(1, runId);
            return findMany(statement);
        }
    }

    public List<HookRunRecord> findByEvent(String event) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_COLUMNS + """
                        WHERE event = ?
                        ORDER BY started_at, sequence_no, rowid
                        """)) {
            statement.setString(1, event);
            return findMany(statement);
        }
    }

    public List<HookRunRecord> findByStatus(String status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_COLUMNS + """
                        WHERE status = ?
                        ORDER BY started_at, sequence_no, rowid
                        """)) {
            statement.setString(1, status);
            return findMany(statement);
        }
    }

    private void complete(String hookRunId, String status, Integer exitCode,
                          String outputExcerpt, String errorExcerpt,
                          long durationMs) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE hook_runs
                SET status = ?, exit_code = ?, output_excerpt = ?,
                    error_excerpt = ?, duration_ms = ?,
                    completed_at = datetime('now')
                WHERE hook_run_id = ? AND status = 'RUNNING'
                """)) {
            statement.setString(1, status);
            if (exitCode == null) {
                statement.setNull(2, Types.INTEGER);
            } else {
                statement.setInt(2, exitCode);
            }
            statement.setString(3, outputExcerpt);
            statement.setString(4, errorExcerpt);
            statement.setLong(5, durationMs);
            statement.setString(6, hookRunId);
            if (statement.executeUpdate() == 0) {
                throw new SQLException(
                        "Hook run not found or invalid state: " + hookRunId);
            }
        }
    }

    private List<HookRunRecord> findMany(PreparedStatement statement)
            throws SQLException {
        List<HookRunRecord> records = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                records.add(toRecord(resultSet));
            }
        }
        return records;
    }

    private HookRunRecord toRecord(ResultSet resultSet) throws SQLException {
        return new HookRunRecord(
                resultSet.getString("hook_run_id"),
                resultSet.getString("run_id"),
                resultSet.getString("task_id"),
                resultSet.getString("hook_id"),
                resultSet.getString("event"),
                resultSet.getString("occurrence_key"),
                resultSet.getInt("sequence_no"),
                resultSet.getString("command"),
                resultSet.getString("status"),
                nullableInt(resultSet, "exit_code"),
                resultSet.getString("output_excerpt"),
                resultSet.getString("error_excerpt"),
                nullableLong(resultSet, "duration_ms"),
                resultSet.getString("failure_policy"),
                resultSet.getString("started_at"),
                resultSet.getString("completed_at")
        );
    }

    private Integer nullableInt(ResultSet resultSet, String column)
            throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private Long nullableLong(ResultSet resultSet, String column)
            throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static final String SELECT_COLUMNS = """
            SELECT hook_run_id, run_id, task_id, hook_id, event,
                   occurrence_key, sequence_no, command, status, exit_code,
                   output_excerpt, error_excerpt, duration_ms, failure_policy,
                   started_at, completed_at
            FROM hook_runs
            """;

    public record HookRunRecord(
            String hookRunId,
            String runId,
            String taskId,
            String hookId,
            String event,
            String occurrenceKey,
            int sequenceNo,
            String command,
            String status,
            Integer exitCode,
            String outputExcerpt,
            String errorExcerpt,
            Long durationMs,
            String failurePolicy,
            String startedAt,
            String completedAt
    ) {
    }
}
