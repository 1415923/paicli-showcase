package com.paicli.state;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class FileSnapshotStore {

    public static final String OPERATION_CREATE = "CREATE";
    public static final String OPERATION_OVERWRITE = "OVERWRITE";
    public static final String OPERATION_MODIFY = "MODIFY";

    public static final String STATUS_CAPTURED = "CAPTURED";
    public static final String STATUS_APPLIED = "APPLIED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_RESTORED = "RESTORED";

    private final Connection connection;

    public FileSnapshotStore(Connection connection) {
        this.connection = connection;
    }

    public void recordCaptured(String snapshotId, String taskId, String toolCallId,
                               String filePath, String snapshotPath, String operation,
                               boolean originalExists, long originalSize, String sha256Before)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO file_snapshots(
                    snapshot_id, task_id, tool_call_id, file_path, snapshot_path,
                    operation, original_exists, original_size, sha256_before, status
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, snapshotId);
            statement.setString(2, taskId);
            statement.setString(3, toolCallId);
            statement.setString(4, filePath);
            statement.setString(5, snapshotPath);
            statement.setString(6, operation);
            statement.setInt(7, originalExists ? 1 : 0);
            statement.setLong(8, originalSize);
            statement.setString(9, sha256Before);
            statement.setString(10, STATUS_CAPTURED);
            statement.executeUpdate();
        }
    }

    public void markApplied(String snapshotId, String sha256After) throws SQLException {
        updateState(snapshotId, STATUS_CAPTURED, STATUS_APPLIED, sha256After, false);
    }

    public void markFailed(String snapshotId, String reason) throws SQLException {
        updateState(snapshotId, STATUS_CAPTURED, STATUS_FAILED, null, false);
    }

    public void markRestored(String snapshotId) throws SQLException {
        updateState(snapshotId, STATUS_APPLIED, STATUS_RESTORED, null, true);
    }

    public Optional<FileSnapshotRecord> findById(String snapshotId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT snapshot_id, task_id, tool_call_id, file_path, snapshot_path,
                       operation, original_exists, original_size, sha256_before,
                       sha256_after, status, created_at, restored_at
                FROM file_snapshots
                WHERE snapshot_id = ?
                """)) {
            statement.setString(1, snapshotId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(toRecord(resultSet)) : Optional.empty();
            }
        }
    }

    public List<FileSnapshotRecord> findByTask(String taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT snapshot_id, task_id, tool_call_id, file_path, snapshot_path,
                       operation, original_exists, original_size, sha256_before,
                       sha256_after, status, created_at, restored_at
                FROM file_snapshots
                WHERE task_id = ?
                ORDER BY created_at, snapshot_id
                """)) {
            statement.setString(1, taskId);
            return findMany(statement);
        }
    }

    public List<FileSnapshotRecord> findRecentByTask(String taskId, int limit)
            throws SQLException {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT snapshot_id, task_id, tool_call_id, file_path, snapshot_path,
                       operation, original_exists, original_size, sha256_before,
                       sha256_after, status, created_at, restored_at
                FROM file_snapshots
                WHERE task_id = ?
                ORDER BY created_at DESC, rowid DESC
                LIMIT ?
                """)) {
            statement.setString(1, taskId);
            statement.setInt(2, limit);
            return findMany(statement);
        }
    }

    public int countByTask(String taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) AS snapshot_count
                FROM file_snapshots
                WHERE task_id = ?
                """)) {
            statement.setString(1, taskId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt("snapshot_count") : 0;
            }
        }
    }
    public List<FileSnapshotRecord> findByStatus(String status) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT snapshot_id, task_id, tool_call_id, file_path, snapshot_path,
                       operation, original_exists, original_size, sha256_before,
                       sha256_after, status, created_at, restored_at
                FROM file_snapshots
                WHERE status = ?
                ORDER BY created_at, snapshot_id
                """)) {
            statement.setString(1, status);
            return findMany(statement);
        }
    }

    private void updateState(String snapshotId, String expectedStatus, String newStatus,
                             String sha256After, boolean restored) throws SQLException {
        String sql = restored
                ? """
                UPDATE file_snapshots
                SET status = ?, restored_at = datetime('now')
                WHERE snapshot_id = ? AND status = ?
                """
                : """
                UPDATE file_snapshots
                SET status = ?, sha256_after = COALESCE(?, sha256_after)
                WHERE snapshot_id = ? AND status = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, newStatus);
            int snapshotParameter;
            if (restored) {
                snapshotParameter = 2;
            } else {
                statement.setString(2, sha256After);
                snapshotParameter = 3;
            }
            statement.setString(snapshotParameter, snapshotId);
            statement.setString(snapshotParameter + 1, expectedStatus);
            if (statement.executeUpdate() == 0) {
                throw new SQLException("Snapshot not found or invalid state: " + snapshotId);
            }
        }
    }

    private List<FileSnapshotRecord> findMany(PreparedStatement statement) throws SQLException {
        List<FileSnapshotRecord> records = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                records.add(toRecord(resultSet));
            }
        }
        return records;
    }

    private FileSnapshotRecord toRecord(ResultSet resultSet) throws SQLException {
        return new FileSnapshotRecord(
                resultSet.getString("snapshot_id"),
                resultSet.getString("task_id"),
                resultSet.getString("tool_call_id"),
                resultSet.getString("file_path"),
                resultSet.getString("snapshot_path"),
                resultSet.getString("operation"),
                resultSet.getInt("original_exists") == 1,
                resultSet.getLong("original_size"),
                resultSet.getString("sha256_before"),
                resultSet.getString("sha256_after"),
                resultSet.getString("status"),
                resultSet.getString("created_at"),
                resultSet.getString("restored_at")
        );
    }

    public record FileSnapshotRecord(
            String snapshotId,
            String taskId,
            String toolCallId,
            String filePath,
            String snapshotPath,
            String operation,
            boolean originalExists,
            long originalSize,
            String sha256Before,
            String sha256After,
            String status,
            String createdAt,
            String restoredAt
    ) {
    }
}
