package com.paicli.state;

import java.sql.*;
import java.util.*;

/** Persistence and CAS transitions for immutable merge-proposal artifacts. */
public final class WorkerMergeProposalStore {
    private static final Set<String> OVERLAP = Set.of("UNKNOWN", "DISJOINT", "OVERLAP");
    private static final Set<String> MERGE = Set.of("PENDING", "CLEAN_MERGE", "TEXT_CONFLICT",
            "INVALID_CANDIDATE", "BASE_MISMATCH", "INTERNAL_ERROR");
    private static final Set<String> VERIFY = Set.of("NOT_RUN", "PASSED", "FAILED",
            "TIMED_OUT", "ERROR", "SKIPPED");
    private final Connection connection;

    public WorkerMergeProposalStore(Connection connection) { this.connection = connection; }

    public void begin(String proposalId, String proposalKey, String repositoryIdentity,
                      String baseSha, String branch, String workspace, String overlap,
                      String summary, List<CandidateRef> candidates) throws SQLException {
        if (candidates == null || candidates.isEmpty() || candidates.size() > 2)
            throw new IllegalArgumentException("proposal requires one or two candidates");
        WorkerStoreSupport.oneOf(overlap, "overlap", OVERLAP);
        boolean auto = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (PreparedStatement s = connection.prepareStatement("""
                    INSERT INTO worker_merge_proposals(
                      proposal_id, proposal_key, repository_identity, base_commit_sha,
                      proposal_branch, canonical_workspace_path, overlap_status,
                      merge_status, verification_status, status, bounded_summary
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 'NOT_RUN', 'PREPARING', ?)
                    """)) {
                s.setString(1, WorkerStoreSupport.id(proposalId, "proposalId"));
                s.setString(2, WorkerStoreSupport.hash(proposalKey, "proposalKey"));
                s.setString(3, WorkerStoreSupport.required(repositoryIdentity, "repositoryIdentity", 512));
                s.setString(4, WorkerStoreSupport.commitSha(baseSha, "baseSha"));
                s.setString(5, WorkerStoreSupport.required(branch, "proposalBranch", 512));
                s.setString(6, WorkerStoreSupport.required(workspace, "proposalWorkspace", 4096));
                s.setString(7, overlap);
                s.setString(8, WorkerStoreSupport.required(summary, "summary", 4096));
                s.executeUpdate();
            }
            try (PreparedStatement s = connection.prepareStatement("""
                    INSERT INTO worker_merge_proposal_candidates(
                      proposal_id, candidate_id, candidate_order, candidate_commit_sha
                    ) VALUES (?, ?, ?, ?)
                    """)) {
                for (int i = 0; i < candidates.size(); i++) {
                    CandidateRef ref = candidates.get(i);
                    s.setString(1, proposalId);
                    s.setString(2, WorkerStoreSupport.id(ref.candidateId(), "candidateId"));
                    s.setInt(3, i);
                    s.setString(4, WorkerStoreSupport.commitSha(ref.commitSha(), "candidateCommitSha"));
                    s.addBatch();
                }
                s.executeBatch();
            }
            connection.commit();
        } catch (SQLException | RuntimeException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(auto);
        }
    }

    public void markMerged(String id, String commit, String tree, String artifactHash)
            throws SQLException {
        update("""
                UPDATE worker_merge_proposals SET proposal_commit_sha = ?,
                  proposal_tree_sha = ?, artifact_hash = ?, merge_status = 'CLEAN_MERGE',
                  status = 'MERGED', updated_at = datetime('now')
                WHERE proposal_id = ? AND status = 'PREPARING'
                """, id, WorkerStoreSupport.commitSha(commit, "proposalCommitSha"),
                WorkerStoreSupport.commitSha(tree, "proposalTreeSha"),
                WorkerStoreSupport.hash(artifactHash, "artifactHash"));
    }

    public void markConflicted(String id, String conflictFilesJson, String summary)
            throws SQLException {
        update("""
                UPDATE worker_merge_proposals SET merge_status = 'TEXT_CONFLICT',
                  conflict_files_json = ?, bounded_summary = ?, status = 'CONFLICTED',
                  updated_at = datetime('now') WHERE proposal_id = ? AND status = 'PREPARING'
                """, id, WorkerStoreSupport.required(conflictFilesJson, "conflictFilesJson", 16384),
                WorkerStoreSupport.required(summary, "summary", 4096));
    }

    public void markInvalid(String id, String mergeStatus, String error) throws SQLException {
        if (!Set.of("INVALID_CANDIDATE", "BASE_MISMATCH", "INTERNAL_ERROR").contains(mergeStatus))
            throw new IllegalArgumentException("invalid proposal failure category");
        update("""
                UPDATE worker_merge_proposals SET merge_status = ?, status = 'INVALID',
                  last_error_excerpt = ?, updated_at = datetime('now')
                WHERE proposal_id = ? AND status IN ('PREPARING','MERGED','VERIFYING')
                """, id, mergeStatus, WorkerStoreSupport.optional(error, 4096));
    }

    public void beginVerification(String id, String configHash) throws SQLException {
        update("""
                UPDATE worker_merge_proposals SET status = 'VERIFYING',
                  verification_config_hash = ?, updated_at = datetime('now')
                WHERE proposal_id = ? AND status = 'MERGED'
                """, id, WorkerStoreSupport.hash(configHash, "verificationConfigHash"));
    }

    public void finishVerification(String id, String configHash, String status,
                                   String verificationHash, String error) throws SQLException {
        WorkerStoreSupport.oneOf(status, "verificationStatus", VERIFY);
        String next = "PASSED".equals(status) ? "READY_FOR_APPROVAL" : "VERIFICATION_FAILED";
        try (PreparedStatement s = connection.prepareStatement("""
                UPDATE worker_merge_proposals SET verification_status = ?,
                  verification_hash = ?, status = ?, last_error_excerpt = ?,
                  updated_at = datetime('now')
                WHERE proposal_id = ? AND status = 'VERIFYING'
                  AND verification_config_hash = ?
                """)) {
            s.setString(1, status);
            s.setString(2, WorkerStoreSupport.hash(verificationHash, "verificationHash"));
            s.setString(3, next);
            s.setString(4, WorkerStoreSupport.optional(error, 4096));
            s.setString(5, id);
            s.setString(6, WorkerStoreSupport.hash(configHash, "verificationConfigHash"));
            WorkerStoreSupport.requireUpdated(s, "Proposal verification state/config drift: " + id);
        }
    }

    public void markIntegrated(String id) throws SQLException {
        update("""
                UPDATE worker_merge_proposals SET status = 'INTEGRATED',
                  updated_at = datetime('now')
                WHERE proposal_id = ? AND status = 'READY_FOR_APPROVAL'
                """, id);
    }

    public void markCleaned(String id) throws SQLException {
        update("""
                UPDATE worker_merge_proposals SET status = 'CLEANED',
                  updated_at = datetime('now')
                WHERE proposal_id = ? AND status IN (
                  'INTEGRATED','CONFLICTED','VERIFICATION_FAILED','INVALID')
                """, id);
    }

    public Optional<Record> findById(String id) throws SQLException { return find("proposal_id", id); }
    public Optional<Record> findByKey(String key) throws SQLException { return find("proposal_key", key); }

    public List<Record> findAll() throws SQLException {
        try (PreparedStatement s = connection.prepareStatement(
                SELECT + " ORDER BY created_at, proposal_id");
             ResultSet rs = s.executeQuery()) {
            List<Record> result = new ArrayList<>();
            while (rs.next()) result.add(map(rs));
            return List.copyOf(result);
        }
    }
    public List<CandidateRef> candidates(String proposalId) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement("""
                SELECT candidate_id, candidate_commit_sha
                FROM worker_merge_proposal_candidates WHERE proposal_id = ?
                ORDER BY candidate_order
                """)) {
            s.setString(1, proposalId);
            try (ResultSet rs = s.executeQuery()) {
                List<CandidateRef> result = new ArrayList<>();
                while (rs.next()) result.add(new CandidateRef(rs.getString(1), rs.getString(2)));
                return List.copyOf(result);
            }
        }
    }

    private Optional<Record> find(String column, String value) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement(SELECT + " WHERE " + column + " = ?")) {
            s.setString(1, value);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    private void update(String sql, String id, String... values) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement(sql)) {
            int index = 1;
            for (String value : values) s.setString(index++, value);
            s.setString(index, id);
            WorkerStoreSupport.requireUpdated(s, "Merge proposal not found or invalid state: " + id);
        }
    }

    private Record map(ResultSet rs) throws SQLException {
        return new Record(rs.getString("proposal_id"), rs.getString("proposal_key"),
                rs.getString("repository_identity"), rs.getString("base_commit_sha"),
                rs.getString("proposal_branch"), rs.getString("canonical_workspace_path"),
                rs.getString("proposal_commit_sha"), rs.getString("proposal_tree_sha"),
                rs.getString("artifact_hash"), rs.getString("overlap_status"),
                rs.getString("merge_status"), rs.getString("conflict_files_json"),
                rs.getString("verification_status"), rs.getString("verification_config_hash"),
                rs.getString("verification_hash"), rs.getString("status"),
                rs.getString("bounded_summary"), rs.getString("created_at"),
                rs.getString("updated_at"), rs.getString("last_error_excerpt"));
    }

    private static final String SELECT = """
            SELECT proposal_id, proposal_key, repository_identity, base_commit_sha,
              proposal_branch, canonical_workspace_path, proposal_commit_sha,
              proposal_tree_sha, artifact_hash, overlap_status, merge_status,
              conflict_files_json, verification_status, verification_config_hash,
              verification_hash, status, bounded_summary, created_at, updated_at,
              last_error_excerpt FROM worker_merge_proposals
            """;

    public record CandidateRef(String candidateId, String commitSha) { }
    public record Record(String proposalId, String proposalKey, String repositoryIdentity,
            String baseCommitSha, String proposalBranch, String canonicalWorkspacePath,
            String proposalCommitSha, String proposalTreeSha, String artifactHash,
            String overlapStatus, String mergeStatus, String conflictFilesJson,
            String verificationStatus, String verificationConfigHash, String verificationHash,
            String status, String boundedSummary, String createdAt, String updatedAt,
            String lastErrorExcerpt) { }
}
