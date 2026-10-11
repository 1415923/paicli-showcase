package com.paicli.state;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Persists bounded proposal lifecycle metadata; diff/source content is never stored. */
public final class InteractiveProposalStore {
    public static final String PROPOSED = "PROPOSED";
    public static final String REJECTED = "REJECTED";
    public static final String CANCELLED = "CANCELLED";
    public static final String APPLIED = "APPLIED";
    public static final String SUPERSEDED = "SUPERSEDED";
    public static final String REWOUND = "REWOUND";

    private final Connection connection;

    public InteractiveProposalStore(Connection connection) {
        this.connection = connection;
    }

    public void recordProposed(String proposalId, String taskId, String runId,
            String relativePath, String afterHash, int additions, int deletions,
            boolean truncated, boolean redacted) throws SQLException {
        String portablePath = portableRelativePath(relativePath);
        try (PreparedStatement supersede = connection.prepareStatement("""
                UPDATE interactive_edit_proposals
                SET status = 'SUPERSEDED', updated_at = datetime('now'),
                    resolved_at = datetime('now')
                WHERE task_id = ?
                  AND replace(relative_path, char(92), '/') = ?
                  AND status = 'PROPOSED'
                  AND after_hash <> ?
                """)) {
            supersede.setString(1, taskId);
            supersede.setString(2, portablePath);
            supersede.setString(3, afterHash);
            supersede.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO interactive_edit_proposals(
                    proposal_id, task_id, run_id, relative_path, after_hash,
                    additions, deletions, status, truncated, redacted
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'PROPOSED', ?, ?)
                """)) {
            statement.setString(1, proposalId);
            statement.setString(2, taskId);
            statement.setString(3, runId);
            statement.setString(4, portablePath);
            statement.setString(5, afterHash);
            statement.setInt(6, additions);
            statement.setInt(7, deletions);
            statement.setInt(8, truncated ? 1 : 0);
            statement.setInt(9, redacted ? 1 : 0);
            statement.executeUpdate();
        }
    }

    public void resolve(String proposalId, String expected, String status) throws SQLException {
        requireStatus(status);
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE interactive_edit_proposals
                SET status = ?, updated_at = datetime('now'), resolved_at = datetime('now')
                WHERE proposal_id = ? AND status = ?
                """)) {
            statement.setString(1, status);
            statement.setString(2, proposalId);
            statement.setString(3, expected);
            if (statement.executeUpdate() == 0) {
                throw new SQLException("Proposal not found or invalid state: " + proposalId);
            }
        }
    }

    public void markLatestApplied(String taskId, String runId, String relativePath,
            String snapshotId) throws SQLException {
        String portablePath = portableRelativePath(relativePath);
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE interactive_edit_proposals
                SET status = 'APPLIED', snapshot_id = ?, updated_at = datetime('now'),
                    resolved_at = datetime('now')
                WHERE proposal_id = (
                    SELECT proposal_id FROM interactive_edit_proposals
                    WHERE task_id = ? AND run_id = ?
                      AND replace(relative_path, char(92), '/') = ?
                      AND status = 'PROPOSED'
                    ORDER BY created_at DESC, rowid DESC LIMIT 1
                )
                """)) {
            statement.setString(1, snapshotId);
            statement.setString(2, taskId);
            statement.setString(3, runId);
            statement.setString(4, portablePath);
            if (statement.executeUpdate() == 0) {
                throw new SQLException("No proposed edit matches applied write");
            }
        }
    }

    public void markRewound(String taskId, String relativePath, String snapshotId)
            throws SQLException {
        String portablePath = portableRelativePath(relativePath);
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE interactive_edit_proposals
                SET status = 'REWOUND', updated_at = datetime('now'),
                    resolved_at = datetime('now')
                WHERE task_id = ?
                  AND replace(relative_path, char(92), '/') = ?
                  AND snapshot_id = ?
                """)) {
            statement.setString(1, taskId);
            statement.setString(2, portablePath);
            statement.setString(3, snapshotId);
            if (statement.executeUpdate() == 0) {
                throw new SQLException("Snapshot-linked proposal not found for rewind");
            }
        }
    }

    public void recordLegacyLinked(String proposalId, String taskId, String runId,
            String relativePath, String afterHash, String snapshotId, String status)
            throws SQLException {
        if (!Set.of(APPLIED, REWOUND).contains(status)) {
            throw new IllegalArgumentException("legacy linked status must be APPLIED or REWOUND");
        }
        String portablePath = portableRelativePath(relativePath);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO interactive_edit_proposals(
                    proposal_id, task_id, run_id, relative_path, after_hash,
                    additions, deletions, status, snapshot_id, truncated, redacted,
                    resolved_at
                ) VALUES (?, ?, ?, ?, ?, 0, 0, ?, ?, 1, 1, datetime('now'))
                ON CONFLICT(proposal_id) DO NOTHING
                """)) {
            statement.setString(1, proposalId);
            statement.setString(2, taskId);
            statement.setString(3, runId);
            statement.setString(4, portablePath);
            statement.setString(5, afterHash);
            statement.setString(6, status);
            statement.setString(7, snapshotId);
            statement.executeUpdate();
        }
        List<Record> matches = findByTask(taskId, 100).stream()
                .filter(item -> proposalId.equals(item.proposalId()))
                .filter(item -> runId.equals(item.runId()))
                .filter(item -> snapshotId.equals(item.snapshotId()))
                .filter(item -> portablePath.equals(portableRelativePath(item.relativePath())))
                .filter(item -> status.equals(item.status())).toList();
        if (matches.size() != 1) {
            throw new SQLException("Legacy proposal linkage is ambiguous or inconsistent");
        }
    }

    public void supersedePending(String taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE interactive_edit_proposals
                SET status = 'SUPERSEDED', updated_at = datetime('now'),
                    resolved_at = datetime('now')
                WHERE task_id = ? AND status = 'PROPOSED'
                """)) {
            statement.setString(1, taskId);
            statement.executeUpdate();
        }
    }

    public List<Record> findByTask(String taskId, int limit) throws SQLException {
        int bounded = Math.max(1, Math.min(limit, 100));
        try (PreparedStatement statement = connection.prepareStatement(SELECT + """
                WHERE task_id = ? ORDER BY created_at, rowid LIMIT ?
                """)) {
            statement.setString(1, taskId);
            statement.setInt(2, bounded);
            List<Record> records = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) records.add(toRecord(result));
            }
            return List.copyOf(records);
        }
    }

    private static Record toRecord(ResultSet result) throws SQLException {
        return new Record(result.getString("proposal_id"), result.getString("task_id"),
                result.getString("run_id"), result.getString("relative_path"),
                result.getString("after_hash"), result.getInt("additions"),
                result.getInt("deletions"), result.getString("status"),
                result.getString("snapshot_id"), result.getInt("truncated") == 1,
                result.getInt("redacted") == 1, result.getString("created_at"),
                result.getString("updated_at"), result.getString("resolved_at"));
    }

    private static void requireStatus(String status) {
        if (!java.util.Set.of(PROPOSED, REJECTED, CANCELLED, APPLIED,
                SUPERSEDED, REWOUND).contains(status)) {
            throw new IllegalArgumentException("invalid proposal status");
        }
    }

    private static String portableRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("relativePath is required");
        }
        return relativePath.replace('\\', '/');
    }

    private static final String SELECT = """
            SELECT proposal_id, task_id, run_id, relative_path, after_hash,
                   additions, deletions, status, snapshot_id, truncated, redacted,
                   created_at, updated_at, resolved_at
            FROM interactive_edit_proposals
            """;

    public record Record(String proposalId, String taskId, String runId,
            String relativePath, String afterHash, int additions, int deletions,
            String status, String snapshotId, boolean truncated, boolean redacted,
            String createdAt, String updatedAt, String resolvedAt) { }
}
