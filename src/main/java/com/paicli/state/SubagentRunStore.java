package com.paicli.state;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class SubagentRunStore {

    public static final String KIND_SCOUT = "SCOUT";
    public static final String KIND_REVIEWER = "REVIEWER";
    public static final String KIND_VERIFIER = "VERIFIER";
    public static final String KIND_CUSTOM = "CUSTOM";

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_TIMED_OUT = "TIMED_OUT";
    public static final String STATUS_INTERRUPTED = "INTERRUPTED";

    private static final int MAX_ERROR_EXCERPT_CHARS = 4096;
    private static final int MAX_TOOL_POLICY_CHARS = 4096;

    private final Connection connection;

    public SubagentRunStore(Connection connection) {
        this.connection = connection;
    }

    public void createSubagentRun(String subagentRunId, String taskId,
                                  String codingRunId, String subagentName,
                                  String subagentKind, String stage,
                                  String occurrenceKey, int attemptNumber,
                                  String providerName, boolean readOnly,
                                  String toolPolicySummary, String inputHash)
            throws SQLException {
        createSubagentRun(
                subagentRunId, taskId, codingRunId, subagentName,
                subagentKind, stage, occurrenceKey, attemptNumber,
                providerName, readOnly, toolPolicySummary, inputHash, null);
    }

    public void createSubagentRun(String subagentRunId, String taskId,
                                  String codingRunId, String subagentName,
                                  String subagentKind, String stage,
                                  String occurrenceKey, int attemptNumber,
                                  String providerName, boolean readOnly,
                                  String toolPolicySummary, String inputHash,
                                  String contextHash)
            throws SQLException {
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be >= 1");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO subagent_runs(
                    subagent_run_id, task_id, coding_run_id, subagent_name,
                    subagent_kind, stage, occurrence_key, attempt_number,
                    status, provider_name, read_only, tool_policy_summary,
                    input_hash, context_hash
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, subagentRunId);
            statement.setString(2, taskId);
            statement.setString(3, codingRunId);
            statement.setString(4, subagentName);
            statement.setString(5, subagentKind);
            statement.setString(6, stage);
            statement.setString(7, occurrenceKey);
            statement.setInt(8, attemptNumber);
            statement.setString(9, STATUS_RUNNING);
            statement.setString(10, providerName);
            statement.setInt(11, readOnly ? 1 : 0);
            statement.setString(12, truncate(toolPolicySummary, MAX_TOOL_POLICY_CHARS));
            statement.setString(13, inputHash);
            statement.setString(14, contextHash);
            statement.executeUpdate();
        }
    }

    public void updateInputAndContextHashes(
            String subagentRunId, String inputHash, String contextHash)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE subagent_runs
                SET input_hash = ?, context_hash = ?
                WHERE subagent_run_id = ? AND status = 'RUNNING'
                """)) {
            statement.setString(1, inputHash);
            statement.setString(2, contextHash);
            statement.setString(3, subagentRunId);
            if (statement.executeUpdate() == 0) {
                throw new SQLException("Subagent run not found or invalid state: "
                        + subagentRunId);
            }
        }
    }

    public void completeSubagentRun(String subagentRunId, String outputHash)
            throws SQLException {
        complete(subagentRunId, STATUS_SUCCEEDED, outputHash, null, null);
    }

    public void completeConfiguredVerification(
            String subagentRunId, String outputHash, String verificationStatus)
            throws SQLException {
        if (!isVerificationStatus(verificationStatus)) {
            throw new IllegalArgumentException(
                    "invalid configured verification status: " + verificationStatus);
        }
        complete(subagentRunId, STATUS_SUCCEEDED, outputHash,
                verificationStatus, null);
    }

    public void failSubagentRun(String subagentRunId, String errorExcerpt)
            throws SQLException {
        complete(subagentRunId, STATUS_FAILED, null, null, errorExcerpt);
    }

    public void markTimedOut(String subagentRunId, String errorExcerpt)
            throws SQLException {
        complete(subagentRunId, STATUS_TIMED_OUT, null, null, errorExcerpt);
    }

    public void markInterrupted(String subagentRunId, String errorExcerpt)
            throws SQLException {
        complete(subagentRunId, STATUS_INTERRUPTED, null, null, errorExcerpt);
    }

    public Optional<SubagentRunRecord> findSubagentRunById(String subagentRunId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_COLUMNS + " WHERE subagent_run_id = ?")) {
            statement.setString(1, subagentRunId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(toRecord(resultSet)) : Optional.empty();
            }
        }
    }

    public List<SubagentRunRecord> findRunsByTask(String taskId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_COLUMNS + """
                        WHERE task_id = ?
                        ORDER BY started_at, rowid
                        """)) {
            statement.setString(1, taskId);
            return findMany(statement);
        }
    }

    public List<SubagentRunRecord> findRecentRunsByTask(
            String taskId, int limit) throws SQLException {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_COLUMNS + """
                        WHERE task_id = ?
                        ORDER BY started_at DESC, rowid DESC
                        LIMIT ?
                        """)) {
            statement.setString(1, taskId);
            statement.setInt(2, limit);
            List<SubagentRunRecord> records = findMany(statement);
            Collections.reverse(records);
            return records;
        }
    }

    public List<SubagentRunRecord> findRunsByOccurrence(
            String codingRunId, String stage, String occurrenceKey)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_COLUMNS + """
                        WHERE coding_run_id = ? AND stage = ? AND occurrence_key = ?
                        ORDER BY attempt_number, started_at, rowid
                        """)) {
            statement.setString(1, codingRunId);
            statement.setString(2, stage);
            statement.setString(3, occurrenceKey);
            return findMany(statement);
        }
    }

    private void complete(String subagentRunId, String status,
                          String outputHash, String verificationStatus,
                          String errorExcerpt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE subagent_runs
                SET status = ?, output_hash = ?, verification_status = ?,
                    error_excerpt = ?,
                    completed_at = datetime('now')
                WHERE subagent_run_id = ? AND status = 'RUNNING'
                """)) {
            statement.setString(1, status);
            statement.setString(2, outputHash);
            statement.setString(3, verificationStatus);
            statement.setString(4, truncate(errorExcerpt, MAX_ERROR_EXCERPT_CHARS));
            statement.setString(5, subagentRunId);
            if (statement.executeUpdate() == 0) {
                throw new SQLException("Subagent run not found or invalid state: " + subagentRunId);
            }
        }
    }

    private List<SubagentRunRecord> findMany(PreparedStatement statement)
            throws SQLException {
        List<SubagentRunRecord> records = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                records.add(toRecord(resultSet));
            }
        }
        return records;
    }

    private SubagentRunRecord toRecord(ResultSet resultSet) throws SQLException {
        return new SubagentRunRecord(
                resultSet.getString("subagent_run_id"),
                resultSet.getString("task_id"),
                resultSet.getString("coding_run_id"),
                resultSet.getString("subagent_name"),
                resultSet.getString("subagent_kind"),
                resultSet.getString("stage"),
                resultSet.getString("occurrence_key"),
                resultSet.getInt("attempt_number"),
                resultSet.getString("status"),
                resultSet.getString("provider_name"),
                resultSet.getInt("read_only") == 1,
                resultSet.getString("tool_policy_summary"),
                resultSet.getString("started_at"),
                resultSet.getString("completed_at"),
                resultSet.getString("input_hash"),
                resultSet.getString("context_hash"),
                resultSet.getString("output_hash"),
                resultSet.getString("verification_status"),
                resultSet.getString("error_excerpt")
        );
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars);
    }

    private static boolean isVerificationStatus(String status) {
        return "SKIPPED".equals(status)
                || "PASSED".equals(status)
                || "FAILED".equals(status)
                || "TIMED_OUT".equals(status)
                || "ERROR".equals(status);
    }

    private static final String SELECT_COLUMNS = """
            SELECT subagent_run_id, task_id, coding_run_id, subagent_name,
                   subagent_kind, stage, occurrence_key, attempt_number,
                   status, provider_name, read_only, tool_policy_summary,
                   started_at, completed_at, input_hash, context_hash,
                   output_hash, verification_status,
                   error_excerpt
            FROM subagent_runs
            """;

    public record SubagentRunRecord(
            String subagentRunId,
            String taskId,
            String codingRunId,
            String subagentName,
            String subagentKind,
            String stage,
            String occurrenceKey,
            int attemptNumber,
            String status,
            String providerName,
            boolean readOnly,
            String toolPolicySummary,
            String startedAt,
            String completedAt,
            String inputHash,
            String contextHash,
            String outputHash,
            String verificationStatus,
            String errorExcerpt
    ) {
    }
}
