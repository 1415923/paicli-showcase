package com.paicli.state;

import java.sql.*;
import java.util.*;

/** Audit record for one explicit fast-forward integration attempt. */
public final class WorkerIntegrationRunStore {
    private static final Set<String> TERMINAL = Set.of("INTEGRATED", "FAILED", "INTERRUPTED",
            "BASE_DRIFT", "TARGET_WORKSPACE_DIRTY");
    private final Connection connection;

    public WorkerIntegrationRunStore(Connection connection) { this.connection = connection; }

    public void start(String id, String proposalId, String decisionId, String targetBranch,
                      String expectedBase, String actualHead) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement("""
                INSERT INTO worker_integration_runs(
                  integration_run_id, proposal_id, decision_id, target_branch,
                  expected_base_sha, status, actual_head_before
                ) VALUES (?, ?, ?, ?, ?, 'RUNNING', ?)
                """)) {
            s.setString(1, WorkerStoreSupport.id(id, "integrationRunId"));
            s.setString(2, WorkerStoreSupport.id(proposalId, "proposalId"));
            s.setString(3, WorkerStoreSupport.id(decisionId, "decisionId"));
            s.setString(4, WorkerStoreSupport.required(targetBranch, "targetBranch", 512));
            s.setString(5, WorkerStoreSupport.commitSha(expectedBase, "expectedBaseSha"));
            s.setString(6, WorkerStoreSupport.commitSha(actualHead, "actualHead"));
            s.executeUpdate();
        }
    }

    public void finish(String id, String status, String actualHeadAfter, String error)
            throws SQLException {
        WorkerStoreSupport.oneOf(status, "integrationStatus", TERMINAL);
        try (PreparedStatement s = connection.prepareStatement("""
                UPDATE worker_integration_runs SET status = ?, actual_head_after = ?,
                  error_excerpt = ?, completed_at = datetime('now')
                WHERE integration_run_id = ? AND status = 'RUNNING'
                """)) {
            s.setString(1, status);
            s.setString(2, WorkerStoreSupport.optionalCommitSha(actualHeadAfter, "actualHeadAfter"));
            s.setString(3, WorkerStoreSupport.optional(error, 4096));
            s.setString(4, id);
            WorkerStoreSupport.requireUpdated(s, "Integration run not found or terminal: " + id);
        }
    }

    public Optional<Record> findById(String id) throws SQLException {
        return find("integration_run_id", id);
    }

    public Optional<Record> findByProposal(String proposalId) throws SQLException {
        return find("proposal_id", proposalId);
    }

    private Optional<Record> find(String column, String value) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement(SELECT + " WHERE " + column + " = ?")) {
            s.setString(1, value);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(new Record(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                        rs.getString(9), rs.getString(10))) : Optional.empty();
            }
        }
    }

    private static final String SELECT = """
            SELECT integration_run_id, proposal_id, decision_id, target_branch,
              expected_base_sha, status, actual_head_before, actual_head_after,
              started_at, completed_at FROM worker_integration_runs
            """;

    public record Record(String integrationRunId, String proposalId, String decisionId,
            String targetBranch, String expectedBaseSha, String status,
            String actualHeadBefore, String actualHeadAfter, String startedAt,
            String completedAt) { }
}
