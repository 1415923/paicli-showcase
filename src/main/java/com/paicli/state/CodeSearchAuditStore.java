package com.paicli.state;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** Persists bounded code intelligence query audit metadata. */
public class CodeSearchAuditStore {

    public static final String TYPE_SYMBOL = "SYMBOL";
    public static final String TYPE_CALL_SITE = "CALL_SITE";
    public static final String TYPE_CONTEXT = "CONTEXT";

    public static final String STATUS_STARTED = "STARTED";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";

    public static final String CONFIDENCE_HIGH = "HIGH";
    public static final String CONFIDENCE_MEDIUM = "MEDIUM";
    public static final String CONFIDENCE_LOW = "LOW";

    private static final int MAX_QUERY_INPUT_CHARS = 4096;
    private static final int MAX_ERROR_EXCERPT_CHARS = 4096;

    private final Connection connection;

    public CodeSearchAuditStore(Connection connection) {
        this.connection = connection;
    }

    public void startAudit(String auditId, String queryType, String queryInput)
            throws SQLException {
        startAudit(auditId, queryType, queryInput, null, null);
    }

    public void startAudit(String auditId, String queryType, String queryInput,
                           String taskId, String runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO code_search_audits(
                    audit_id, query_type, query_input, query_input_hash,
                    status, task_id, run_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, auditId);
            statement.setString(2, queryType);
            statement.setString(3, truncate(queryInput, MAX_QUERY_INPUT_CHARS));
            statement.setString(4, sha256(queryInput));
            statement.setString(5, STATUS_STARTED);
            statement.setString(6, taskId);
            statement.setString(7, runId);
            statement.executeUpdate();
        }
    }

    public void completeAudit(String auditId, int resultCount,
                              boolean ambiguous, String confidence,
                              String selectedContextHash,
                              long durationMillis) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE code_search_audits
                SET status = ?, result_count = ?, ambiguity = ?,
                    confidence = ?, selected_context_hash = ?,
                    duration_millis = ?, completed_at = datetime('now')
                WHERE audit_id = ? AND status = 'STARTED'
                """)) {
            statement.setString(1, STATUS_SUCCEEDED);
            statement.setInt(2, resultCount);
            statement.setInt(3, ambiguous ? 1 : 0);
            statement.setString(4, confidence);
            statement.setString(5, selectedContextHash);
            statement.setLong(6, durationMillis);
            statement.setString(7, auditId);
            if (statement.executeUpdate() == 0) {
                throw new SQLException("Code search audit not found or invalid state: " + auditId);
            }
        }
    }

    public void failAudit(String auditId, String errorExcerpt,
                          long durationMillis) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE code_search_audits
                SET status = ?, error_excerpt = ?, duration_millis = ?,
                    completed_at = datetime('now')
                WHERE audit_id = ? AND status = 'STARTED'
                """)) {
            statement.setString(1, STATUS_FAILED);
            statement.setString(2, truncate(errorExcerpt, MAX_ERROR_EXCERPT_CHARS));
            statement.setLong(3, durationMillis);
            statement.setString(4, auditId);
            if (statement.executeUpdate() == 0) {
                throw new SQLException("Code search audit not found or invalid state: " + auditId);
            }
        }
    }

    public Optional<CodeSearchAuditRecord> findAuditById(String auditId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_COLUMNS + " WHERE audit_id = ?")) {
            statement.setString(1, auditId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(toRecord(resultSet)) : Optional.empty();
            }
        }
    }

    public List<CodeSearchAuditRecord> findRecentAudits(int limit)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_COLUMNS + """
                        ORDER BY created_at DESC, rowid DESC
                        LIMIT ?
                        """)) {
            statement.setInt(1, Math.max(0, limit));
            List<CodeSearchAuditRecord> records = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    records.add(toRecord(resultSet));
                }
            }
            return records;
        }
    }

    public List<CodeSearchAuditRecord> findByTaskAndRun(String taskId, String runId,
            int limit) throws SQLException {
        int bounded = Math.max(1, Math.min(limit, 100));
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_COLUMNS + """
                        WHERE task_id = ? AND run_id = ?
                        ORDER BY created_at, rowid
                        LIMIT ?
                        """)) {
            statement.setString(1, taskId);
            statement.setString(2, runId);
            statement.setInt(3, bounded);
            List<CodeSearchAuditRecord> records = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) records.add(toRecord(resultSet));
            }
            return List.copyOf(records);
        }
    }

    private CodeSearchAuditRecord toRecord(ResultSet resultSet)
            throws SQLException {
        return new CodeSearchAuditRecord(
                resultSet.getString("audit_id"),
                resultSet.getString("created_at"),
                resultSet.getString("completed_at"),
                resultSet.getString("query_type"),
                resultSet.getString("query_input"),
                resultSet.getString("query_input_hash"),
                resultSet.getString("status"),
                resultSet.getInt("result_count"),
                resultSet.getInt("ambiguity") == 1,
                resultSet.getString("confidence"),
                resultSet.getString("selected_context_hash"),
                nullableLong(resultSet, "duration_millis"),
                resultSet.getString("error_excerpt"),
                resultSet.getString("task_id"),
                resultSet.getString("run_id")
        );
    }

    private Long nullableLong(ResultSet resultSet, String column)
            throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars);
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = value == null ? new byte[0]
                    : value.getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static final String SELECT_COLUMNS = """
            SELECT audit_id, created_at, completed_at, query_type, query_input,
                   query_input_hash, status, result_count, ambiguity, confidence,
                   selected_context_hash, duration_millis, error_excerpt,
                   task_id, run_id
            FROM code_search_audits
            """;

    public record CodeSearchAuditRecord(
            String auditId,
            String createdAt,
            String completedAt,
            String queryType,
            String queryInput,
            String queryInputHash,
            String status,
            int resultCount,
            boolean ambiguous,
            String confidence,
            String selectedContextHash,
            Long durationMillis,
            String errorExcerpt,
            String taskId,
            String runId
    ) {
    }
}
