package com.paicli.state;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class VerificationStore {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_PASSED = "PASSED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_TIMED_OUT = "TIMED_OUT";
    public static final String STATUS_ERROR = "ERROR";
    public static final String STATUS_SKIPPED = "SKIPPED";
    /** Typed runtime/recovery outcome backed by steering_producer_pause_facts. */
    public static final String STATUS_PAUSED_BY_STEERING = "PAUSED_BY_STEERING";

    private final Connection connection;

    public VerificationStore(Connection connection) {
        this.connection = connection;
    }

    public void recordStarted(String verificationId, String runId, String taskId,
                              int repairAttempt, String command, String candidateDigestKind,
                              String candidateDigest) throws SQLException {
        insert(verificationId, runId, taskId, repairAttempt, command, STATUS_RUNNING, false,
                candidateDigestKind, candidateDigest, null);
    }

    public void recordSkipped(String verificationId, String runId, String taskId,
                              int repairAttempt) throws SQLException {
        insert(verificationId, runId, taskId, repairAttempt, null, STATUS_SKIPPED, true,
                null, null, null);
    }

    public void recordPreflightError(String verificationId, String runId, String taskId,
                                     int repairAttempt, String command, String errorExcerpt)
            throws SQLException {
        insert(verificationId, runId, taskId, repairAttempt, command, STATUS_ERROR, true,
                null, null, errorExcerpt);
    }

    public void markPassed(String verificationId, int exitCode,
                           String outputExcerpt, long durationMs) throws SQLException {
        markPassed(verificationId, exitCode, outputExcerpt, null, durationMs);
    }

    public void markPassed(String verificationId, int exitCode,
                           String outputExcerpt, String errorExcerpt, long durationMs)
            throws SQLException {
        complete(verificationId, STATUS_PASSED, exitCode,
                outputExcerpt, errorExcerpt, durationMs);
    }

    public void markFailed(String verificationId, int exitCode,
                           String outputExcerpt, String errorExcerpt, long durationMs)
            throws SQLException {
        complete(verificationId, STATUS_FAILED, exitCode, outputExcerpt, errorExcerpt, durationMs);
    }

    public void markTimedOut(String verificationId, String outputExcerpt,
                             String errorExcerpt, long durationMs) throws SQLException {
        complete(verificationId, STATUS_TIMED_OUT, null, outputExcerpt, errorExcerpt, durationMs);
    }

    public void markError(String verificationId, String errorExcerpt, long durationMs)
            throws SQLException {
        markError(verificationId, null, errorExcerpt, durationMs);
    }

    public void markError(String verificationId, String outputExcerpt,
                          String errorExcerpt, long durationMs) throws SQLException {
        complete(verificationId, STATUS_ERROR, null, outputExcerpt, errorExcerpt, durationMs);
    }

    public Optional<VerificationRecord> findById(String verificationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_COLUMNS + " WHERE verification_id = ?")) {
            statement.setString(1, verificationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(toRecord(resultSet)) : Optional.empty();
            }
        }
    }

    public boolean wasPausedBySteering(String verificationId) throws SQLException {
        try(PreparedStatement statement=connection.prepareStatement("""
                SELECT 1 FROM steering_producer_pause_facts
                WHERE producer_kind='VERIFICATION' AND producer_identity=?
                """)){
            statement.setString(1,verificationId);
            try(ResultSet rows=statement.executeQuery()){return rows.next();}
        }
    }

    public List<VerificationRecord> findByRun(String runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_COLUMNS + """
                WHERE run_id = ?
                ORDER BY started_at, rowid
                """)) {
            statement.setString(1, runId);
            List<VerificationRecord> records = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    records.add(toRecord(resultSet));
                }
            }
            return records;
        }
    }

    public Optional<VerificationRecord> findLatestByRun(String runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_COLUMNS + """
                WHERE run_id = ?
                ORDER BY started_at DESC, rowid DESC
                LIMIT 1
                """)) {
            statement.setString(1, runId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(toRecord(resultSet)) : Optional.empty();
            }
        }
    }

    public Optional<VerificationRecord> findLatestByTask(String taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_COLUMNS + """
                WHERE task_id = ?
                ORDER BY started_at DESC, rowid DESC
                LIMIT 1
                """)) {
            statement.setString(1, taskId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(toRecord(resultSet)) : Optional.empty();
            }
        }
    }

    private void insert(String verificationId, String runId, String taskId,
                        int repairAttempt, String command, String status,
                        boolean completed, String candidateDigestKind,
                        String candidateDigest, String errorExcerpt) throws SQLException {
        String sql = completed
                ? """
                INSERT INTO verification_runs(
                    verification_id, run_id, task_id, repair_attempt, command,
                    status, duration_ms, completed_at, candidate_digest_kind,
                    candidate_digest, error_excerpt
                ) VALUES (?, ?, ?, ?, ?, ?, 0, datetime('now'), ?, ?, ?)
                """
                : """
                INSERT INTO verification_runs(
                    verification_id, run_id, task_id, repair_attempt, command, status,
                    candidate_digest_kind, candidate_digest
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, verificationId);
            statement.setString(2, runId);
            statement.setString(3, taskId);
            statement.setInt(4, repairAttempt);
            statement.setString(5, command);
            statement.setString(6, status);
            statement.setString(7, candidateDigestKind);
            statement.setString(8, candidateDigest);
            if (completed) statement.setString(9, errorExcerpt);
            statement.executeUpdate();
        }
    }

    private void complete(String verificationId, String status, Integer exitCode,
                          String outputExcerpt, String errorExcerpt, long durationMs)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE verification_runs
                SET status = ?, exit_code = ?, output_excerpt = ?, error_excerpt = ?,
                    duration_ms = ?, completed_at = datetime('now')
                WHERE verification_id = ? AND status = 'RUNNING'
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
            statement.setString(6, verificationId);
            if (statement.executeUpdate() == 0) {
                throw new SQLException("Verification not found or invalid state: " + verificationId);
            }
        }
    }

    private VerificationRecord toRecord(ResultSet resultSet) throws SQLException {
        Integer exitCode = nullableInt(resultSet, "exit_code");
        Long durationMs = nullableLong(resultSet, "duration_ms");
        return new VerificationRecord(
                resultSet.getString("verification_id"), resultSet.getString("run_id"),
                resultSet.getString("task_id"), resultSet.getInt("repair_attempt"),
                resultSet.getString("command"), resultSet.getString("status"), exitCode,
                resultSet.getString("output_excerpt"), resultSet.getString("error_excerpt"),
                durationMs, resultSet.getString("started_at"),
                resultSet.getString("completed_at"),
                resultSet.getString("candidate_digest_kind"),
                resultSet.getString("candidate_digest")
        );
    }

    private Integer nullableInt(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static final String SELECT_COLUMNS = """
            SELECT verification_id, run_id, task_id, repair_attempt, command,
                   status, exit_code, output_excerpt, error_excerpt, duration_ms,
                   started_at, completed_at, candidate_digest_kind, candidate_digest
            FROM verification_runs
            """;

    public record VerificationRecord(
            String verificationId, String runId, String taskId, int repairAttempt,
            String command, String status, Integer exitCode,
            String outputExcerpt, String errorExcerpt, Long durationMs,
            String startedAt, String completedAt, String candidateDigestKind,
            String candidateDigest
    ) {
    }
}
