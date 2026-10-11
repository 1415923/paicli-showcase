package com.paicli.state;

import java.sql.*;
import java.util.*;

/** USER/runtime decision records for merge proposals; decisions never execute Git. */
public final class WorkerIntegrationDecisionStore {
    private static final Set<String> DECISIONS = Set.of("APPROVED", "REJECTED", "REQUEST_REWORK");
    private static final Set<String> ACTORS = Set.of("USER", "RUNTIME", "POLICY");
    private final Connection connection;

    public WorkerIntegrationDecisionStore(Connection connection) { this.connection = connection; }

    public WorkerIntegrationDecisionRecord save(String id, String proposalId,
            String decision, String decidedBy, String reason,
            String targetBranch, String targetHeadSha) throws SQLException {
        WorkerStoreSupport.oneOf(decision, "decision", DECISIONS);
        WorkerStoreSupport.oneOf(decidedBy, "decidedBy", ACTORS);
        if ("APPROVED".equals(decision) && !"USER".equals(decidedBy))
            throw new IllegalArgumentException("APPROVED integration requires USER authority");
        if ("APPROVED".equals(decision) && !proposalIsReady(proposalId))
            throw new SQLException("Merge proposal is not ready for approval: " + proposalId);
        if (!"APPROVED".equals(decision) && (reason == null || reason.isBlank()))
            throw new IllegalArgumentException(decision + " requires a reason");
        String boundedReason = WorkerStoreSupport.optional(reason, 4096);
        String boundedBranch = WorkerStoreSupport.optional(targetBranch, 512);
        String boundedHead = WorkerStoreSupport.optionalCommitSha(targetHeadSha, "targetHeadSha");
        Optional<WorkerIntegrationDecisionRecord> existing = findByProposal(proposalId);
        if (existing.isPresent()) {
            WorkerIntegrationDecisionRecord record = existing.get();
            if (record.decision().equals(decision) && record.decidedBy().equals(decidedBy)
                    && Objects.equals(record.reason(), boundedReason)
                    && Objects.equals(record.targetBranch(), boundedBranch)
                    && Objects.equals(record.targetHeadSha(), boundedHead)) return record;
            throw new SQLException("Conflicting integration decision for proposal: " + proposalId);
        }
        try (PreparedStatement s = connection.prepareStatement("""
                INSERT INTO worker_integration_decisions(
                  integration_decision_id, proposal_id, decision, decided_by,
                  reason, target_branch, target_head_sha
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            s.setString(1, WorkerStoreSupport.id(id, "integrationDecisionId"));
            s.setString(2, WorkerStoreSupport.id(proposalId, "proposalId"));
            s.setString(3, decision);
            s.setString(4, decidedBy);
            s.setString(5, boundedReason);
            s.setString(6, boundedBranch);
            s.setString(7, boundedHead);
            s.executeUpdate();
        }
        return findByProposal(proposalId).orElseThrow();
    }

    public Optional<WorkerIntegrationDecisionRecord> findByProposal(String proposalId)
            throws SQLException {
        try (PreparedStatement s = connection.prepareStatement("""
                SELECT integration_decision_id, proposal_id, decision, decided_by,
                  reason, target_branch, target_head_sha, created_at
                FROM worker_integration_decisions WHERE proposal_id = ?
                """)) {
            s.setString(1, proposalId);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(new WorkerIntegrationDecisionRecord(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8)))
                        : Optional.empty();
            }
        }
    }

    private boolean proposalIsReady(String proposalId) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement("""
                SELECT 1 FROM worker_merge_proposals
                WHERE proposal_id = ? AND status = 'READY_FOR_APPROVAL'
                  AND merge_status = 'CLEAN_MERGE' AND verification_status = 'PASSED'
                """)) {
            s.setString(1, proposalId);
            try (ResultSet rs = s.executeQuery()) { return rs.next(); }
        }
    }

    public record WorkerIntegrationDecisionRecord(String integrationDecisionId,
            String proposalId, String decision, String decidedBy, String reason,
            String targetBranch, String targetHeadSha, String createdAt) { }
}
