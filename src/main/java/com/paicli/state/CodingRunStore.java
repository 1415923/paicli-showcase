package com.paicli.state;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public class CodingRunStore {

    public static final String KIND_INITIAL = "INITIAL";
    public static final String KIND_RESUME = "RESUME";

    public static final String PHASE_PLANNING = "PLANNING";
    public static final String PHASE_EXECUTING = "EXECUTING";
    public static final String PHASE_VERIFYING = "VERIFYING";
    public static final String PHASE_REPAIRING = "REPAIRING";
    public static final String PHASE_COMPLETED = "COMPLETED";
    public static final String PHASE_FAILED = "FAILED";
    /** Effective API phase backed by FAILED + a bounded cancellation category in V10 storage. */
    public static final String PHASE_CANCELLED = "CANCELLED";

    public static final int DEFAULT_MAX_REPAIR_ATTEMPTS = 1;
    public static final int MAX_REPAIR_ATTEMPTS = 5;

    private final Connection connection;

    public CodingRunStore(Connection connection) {
        this.connection = connection;
    }

    public void create(String runId, String taskId, String runKind,
                       String verificationCommand, int maxRepairAttempts,
                       int startedIteration) throws SQLException {
        create(runId,taskId,runKind,PHASE_PLANNING,verificationCommand,maxRepairAttempts,
                startedIteration);
    }

    public void create(String runId, String taskId, String runKind, String initialPhase,
                       String verificationCommand, int maxRepairAttempts,
                       int startedIteration) throws SQLException {
        if(!PHASE_PLANNING.equals(initialPhase)&&!PHASE_EXECUTING.equals(initialPhase))
            throw new IllegalArgumentException("Invalid initial coding run phase: "+initialPhase);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO coding_runs(
                    run_id, task_id, run_kind, phase, verification_command,
                    max_repair_attempts, started_iteration, last_iteration
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, runId);
            statement.setString(2, taskId);
            statement.setString(3, runKind);
            statement.setString(4, initialPhase);
            statement.setString(5, verificationCommand);
            statement.setInt(6, maxRepairAttempts);
            statement.setInt(7, startedIteration);
            statement.setInt(8, startedIteration);
            statement.executeUpdate();
        }
    }

    public void transitionPhase(String runId, String expectedPhase, String nextPhase)
            throws SQLException {
        if (!isAllowedTransition(expectedPhase, nextPhase)) {
            throw new IllegalArgumentException(
                    "Invalid coding run phase transition: " + expectedPhase + " -> " + nextPhase);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE coding_runs
                SET phase = ?, updated_at = datetime('now')
                WHERE run_id = ? AND phase = ?
                """)) {
            statement.setString(1, nextPhase);
            statement.setString(2, runId);
            statement.setString(3, expectedPhase);
            requireUpdated(statement, runId);
        }
    }

    private boolean isAllowedTransition(String expectedPhase, String nextPhase) {
        return (PHASE_PLANNING.equals(expectedPhase) && PHASE_EXECUTING.equals(nextPhase))
                || (PHASE_EXECUTING.equals(expectedPhase) && PHASE_VERIFYING.equals(nextPhase))
                || (PHASE_VERIFYING.equals(expectedPhase) && PHASE_REPAIRING.equals(nextPhase))
                || (PHASE_REPAIRING.equals(expectedPhase) && PHASE_VERIFYING.equals(nextPhase));
    }

    public void updateLastIteration(String runId, int lastIteration) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE coding_runs
                SET last_iteration = ?, updated_at = datetime('now')
                WHERE run_id = ? AND ? >= last_iteration
                """)) {
            statement.setInt(1, lastIteration);
            statement.setString(2, runId);
            statement.setInt(3, lastIteration);
            requireUpdated(statement, runId);
        }
    }

    public int incrementRepairAttempts(String runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE coding_runs
                SET repair_attempts_completed = repair_attempts_completed + 1,
                    updated_at = datetime('now')
                WHERE run_id = ?
                  AND repair_attempts_completed < max_repair_attempts
                  AND phase NOT IN ('COMPLETED', 'FAILED')
                """)) {
            statement.setString(1, runId);
            requireUpdated(statement, runId);
        }
        return findById(runId).orElseThrow().repairAttemptsCompleted();
    }

    public void updateRuntimeManifestHashes(
            String runId, String skillsManifestSha256,
            String hooksManifestSha256) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE coding_runs
                SET skills_manifest_sha256 = ?, hooks_manifest_sha256 = ?,
                    updated_at = datetime('now')
                WHERE run_id = ? AND phase NOT IN ('COMPLETED', 'FAILED')
                """)) {
            statement.setString(1, skillsManifestSha256);
            statement.setString(2, hooksManifestSha256);
            statement.setString(3, runId);
            requireUpdated(statement, runId);
        }
    }

    public void markCompleted(String runId) throws SQLException {
        markTerminal(runId, PHASE_COMPLETED, null);
    }

    public void markFailed(String runId, String error) throws SQLException {
        markTerminal(runId, PHASE_FAILED, error);
    }

    public void markCancelled(String runId, boolean outcomeUnknown) throws SQLException {
        markTerminal(runId, PHASE_FAILED, outcomeUnknown
                ? "CANCELLED_UNKNOWN_OUTCOME: user requested"
                : "CANCELLED: user requested");
    }

    public Optional<CodingRunRecord> findById(String runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_COLUMNS + " WHERE run_id = ?")) {
            statement.setString(1, runId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(toRecord(resultSet)) : Optional.empty();
            }
        }
    }

    public Optional<CodingRunRecord> findActiveByTask(String taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_COLUMNS + """
                WHERE task_id = ?
                  AND phase IN ('PLANNING', 'EXECUTING', 'VERIFYING', 'REPAIRING')
                ORDER BY created_at DESC, rowid DESC
                LIMIT 1
                """)) {
            statement.setString(1, taskId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(toRecord(resultSet)) : Optional.empty();
            }
        }
    }

    public List<CodingRunRecord> findRecentByTask(String taskId, int limit)
            throws SQLException {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        try (PreparedStatement statement = connection.prepareStatement(SELECT_COLUMNS + """
                WHERE task_id = ?
                ORDER BY created_at DESC, rowid DESC
                LIMIT ?
                """)) {
            statement.setString(1, taskId);
            statement.setInt(2, limit);
            List<CodingRunRecord> records = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) records.add(toRecord(resultSet));
            }
            return records;
        }
    }
    public List<CodingRunRecord> findByTask(String taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT_COLUMNS + """
                WHERE task_id = ?
                ORDER BY created_at, rowid
                """)) {
            statement.setString(1, taskId);
            List<CodingRunRecord> records = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    records.add(toRecord(resultSet));
                }
            }
            return records;
        }
    }

    /** Conservative durable Run-active-window total across INITIAL and RESUME Runs. */
    public long cumulativeElapsedMillis(String taskId, Instant now) throws SQLException {
        long total = 0;
        for (CodingRunRecord run : findByTask(taskId)) {
            Instant start = sqliteInstant(run.createdAt());
            boolean active = List.of(PHASE_PLANNING,PHASE_EXECUTING,PHASE_VERIFYING,
                    PHASE_REPAIRING).contains(run.phase());
            String endText = run.completedAt() != null ? run.completedAt() : run.updatedAt();
            Instant end = active ? now : endText == null ? now : sqliteInstant(endText);
            if (end.isBefore(start)) throw new SQLException("CODING_RUN_TIMESTAMP_REGRESSION");
            total = Math.addExact(total, end.toEpochMilli() - start.toEpochMilli());
        }
        return total;
    }

    private static Instant sqliteInstant(String value) throws SQLException {
        try {
            if (value.endsWith("Z")) return Instant.parse(value);
            return LocalDateTime.parse(value,
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .toInstant(ZoneOffset.UTC);
        } catch (RuntimeException error) {
            throw new SQLException("INVALID_CODING_RUN_TIMESTAMP", error);
        }
    }

    private void markTerminal(String runId, String phase, String error) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE coding_runs
                SET phase = ?, last_error = ?, updated_at = datetime('now'),
                    completed_at = datetime('now')
                WHERE run_id = ? AND phase NOT IN ('COMPLETED', 'FAILED')
                """)) {
            statement.setString(1, phase);
            statement.setString(2, error);
            statement.setString(3, runId);
            requireUpdated(statement, runId);
        }
    }

    private void requireUpdated(PreparedStatement statement, String runId) throws SQLException {
        if (statement.executeUpdate() == 0) {
            throw new SQLException("Coding run not found or invalid state: " + runId);
        }
    }

    private CodingRunRecord toRecord(ResultSet resultSet) throws SQLException {
        String storedPhase = resultSet.getString("phase");
        String lastError = resultSet.getString("last_error");
        String effectivePhase = PHASE_FAILED.equals(storedPhase) && lastError != null
                && lastError.startsWith("CANCELLED") ? PHASE_CANCELLED : storedPhase;
        return new CodingRunRecord(
                resultSet.getString("run_id"), resultSet.getString("task_id"),
                resultSet.getString("run_kind"), effectivePhase,
                resultSet.getString("verification_command"),
                resultSet.getInt("max_repair_attempts"),
                resultSet.getInt("repair_attempts_completed"),
                resultSet.getInt("started_iteration"), resultSet.getInt("last_iteration"),
                resultSet.getString("skills_manifest_sha256"),
                resultSet.getString("hooks_manifest_sha256"),
                lastError, resultSet.getString("created_at"),
                resultSet.getString("updated_at"), resultSet.getString("completed_at")
        );
    }

    private static final String SELECT_COLUMNS = """
            SELECT run_id, task_id, run_kind, phase, verification_command,
                   max_repair_attempts, repair_attempts_completed,
                   started_iteration, last_iteration,
                   skills_manifest_sha256, hooks_manifest_sha256, last_error,
                   created_at, updated_at, completed_at
            FROM coding_runs
            """;

    public record CodingRunRecord(
            String runId, String taskId, String runKind, String phase,
            String verificationCommand, int maxRepairAttempts,
            int repairAttemptsCompleted, int startedIteration, int lastIteration,
            String skillsManifestSha256, String hooksManifestSha256,
            String lastError, String createdAt, String updatedAt, String completedAt
    ) {
    }
}
