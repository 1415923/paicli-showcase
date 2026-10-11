package com.paicli.state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Map;
import java.util.Set;

public final class WorkerCandidateStore {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> BACKENDS = Set.of("GIT_LINKED_WORKTREE", "DIRECTORY_EXPORT");
    private static final Set<String> VERIFICATION = Set.of("NOT_RUN", "PASSED", "FAILED", "TIMED_OUT", "ERROR", "SKIPPED");
    private static final Set<String> STATUSES = Set.of("DRAFT", "FROZEN", "VERIFYING", "VERIFIED",
            "CONFLICT_CHECKED", "READY_FOR_DECISION", "VERIFICATION_FAILED", "CONFLICTED",
            "BASE_DRIFTED", "INVALID", "INTEGRATED", "REJECTED", "REWORK_REQUESTED");
    private static final Map<String, Set<String>> TRANSITIONS = Map.of(
            "FROZEN", Set.of("VERIFYING", "INVALID", "BASE_DRIFTED"),
            "VERIFYING", Set.of("VERIFIED", "READY_FOR_DECISION", "VERIFICATION_FAILED", "INVALID"),
            "VERIFIED", Set.of("CONFLICT_CHECKED", "CONFLICTED", "BASE_DRIFTED", "INVALID"),
            "CONFLICT_CHECKED", Set.of("READY_FOR_DECISION", "CONFLICTED", "INVALID"),
            "READY_FOR_DECISION", Set.of("INTEGRATED", "REJECTED", "REWORK_REQUESTED")
    );
    private final Connection connection;

    public WorkerCandidateStore(Connection connection) { this.connection = connection; }

    /** Freezes the one immutable candidate artifact allowed for a worker attempt. */
    public void freeze(String candidateId, String workerRunId, String worktreeId,
                       String taskClaimId, String backendType, String baseCommitSha,
                       String workerBranch, String candidateCommitSha, String diffHash,
                       String changedFilesJson, int changedFileCount,
                       String verificationStatus, String verificationHash,
                       String verificationConfigHash,
                       String summary) throws SQLException {
        WorkerStoreSupport.oneOf(backendType, "backendType", BACKENDS);
        WorkerStoreSupport.oneOf(verificationStatus, "verificationStatus", VERIFICATION);
        if (changedFileCount < 0) throw new IllegalArgumentException("changedFileCount must be >= 0");
        boolean git = "GIT_LINKED_WORKTREE".equals(backendType);
        if (git && (workerBranch == null || workerBranch.isBlank()))
            throw new IllegalArgumentException("Git candidate requires workerBranch");
        if (!git && (workerBranch != null || candidateCommitSha != null))
            throw new IllegalArgumentException("Directory export cannot have branch or candidate commit");
        String boundedChangedFiles = validateChangedFiles(changedFilesJson, changedFileCount);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO worker_candidates(
                    candidate_id, worker_run_id, worktree_id, task_claim_id,
                    backend_type, base_commit_sha, worker_branch, candidate_commit_sha,
                    diff_hash, changed_files_json, changed_file_count,
                    verification_status, verification_hash, verification_config_hash,
                    bounded_summary, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'FROZEN')
                """)) {
            statement.setString(1, WorkerStoreSupport.id(candidateId, "candidateId"));
            statement.setString(2, WorkerStoreSupport.id(workerRunId, "workerRunId"));
            statement.setString(3, WorkerStoreSupport.id(worktreeId, "worktreeId"));
            statement.setString(4, taskClaimId == null ? null : WorkerStoreSupport.id(taskClaimId, "taskClaimId"));
            statement.setString(5, backendType);
            statement.setString(6, WorkerStoreSupport.commitSha(baseCommitSha, "baseCommitSha"));
            statement.setString(7, workerBranch == null ? null : WorkerStoreSupport.required(workerBranch, "workerBranch", 512));
            statement.setString(8, WorkerStoreSupport.optionalCommitSha(candidateCommitSha, "candidateCommitSha"));
            statement.setString(9, WorkerStoreSupport.hash(diffHash, "diffHash"));
            statement.setString(10, boundedChangedFiles);
            statement.setInt(11, changedFileCount);
            statement.setString(12, verificationStatus);
            statement.setString(13, WorkerStoreSupport.optionalHash(verificationHash, "verificationHash"));
            statement.setString(14, WorkerStoreSupport.optionalHash(
                    verificationConfigHash, "verificationConfigHash"));
            statement.setString(15, WorkerStoreSupport.required(summary, "summary", WorkerStoreSupport.MAX_EXCERPT_CHARS));
            statement.executeUpdate();
        }
    }

    public void finishVerification(String candidateId, String expectedConfigHash,
                                   String verificationStatus, String verificationHash,
                                   String nextStatus) throws SQLException {
        WorkerStoreSupport.oneOf(verificationStatus, "verificationStatus", VERIFICATION);
        if (!Set.of("READY_FOR_DECISION", "VERIFICATION_FAILED").contains(nextStatus)) {
            throw new IllegalArgumentException("invalid verification terminal candidate status");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE worker_candidates
                SET verification_status = ?, verification_hash = ?, status = ?,
                    updated_at = datetime('now')
                WHERE candidate_id = ? AND status = 'VERIFYING'
                  AND verification_config_hash = ?
                """)) {
            statement.setString(1, verificationStatus);
            statement.setString(2, WorkerStoreSupport.hash(verificationHash, "verificationHash"));
            statement.setString(3, nextStatus);
            statement.setString(4, candidateId);
            statement.setString(5, WorkerStoreSupport.hash(expectedConfigHash, "verificationConfigHash"));
            WorkerStoreSupport.requireUpdated(statement,
                    "Candidate verification state/config drift: " + candidateId);
        }
    }

    public Optional<WorkerCandidateRecord> findByWorkerRunId(String workerRunId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT + " WHERE worker_run_id = ?")) {
            statement.setString(1, workerRunId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    public void updateStatus(String candidateId, String expectedStatus, String nextStatus) throws SQLException {
        WorkerStoreSupport.oneOf(expectedStatus, "expectedStatus", STATUSES);
        WorkerStoreSupport.oneOf(nextStatus, "nextStatus", STATUSES);
        if (!TRANSITIONS.getOrDefault(expectedStatus, Set.of()).contains(nextStatus))
            throw new IllegalArgumentException(
                    "invalid candidate transition: " + expectedStatus + " -> " + nextStatus);
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE worker_candidates SET status = ?, updated_at = datetime('now')
                WHERE candidate_id = ? AND status = ?
                """)) {
            statement.setString(1, nextStatus);
            statement.setString(2, candidateId);
            statement.setString(3, expectedStatus);
            WorkerStoreSupport.requireUpdated(statement, "Candidate not found or invalid state: " + candidateId);
        }
    }

    public Optional<WorkerCandidateRecord> findByWorktreeId(String worktreeId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT + " WHERE worktree_id = ? ORDER BY created_at DESC LIMIT 1")) {
            statement.setString(1, worktreeId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }
    public Optional<WorkerCandidateRecord> findById(String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT + " WHERE candidate_id = ?")) {
            statement.setString(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    private WorkerCandidateRecord map(ResultSet rs) throws SQLException {
        return new WorkerCandidateRecord(rs.getString("candidate_id"), rs.getString("worker_run_id"),
                rs.getString("worktree_id"), rs.getString("task_claim_id"), rs.getString("backend_type"),
                rs.getString("base_commit_sha"), rs.getString("worker_branch"), rs.getString("candidate_commit_sha"),
                rs.getString("diff_hash"), rs.getString("changed_files_json"), rs.getInt("changed_file_count"),
                rs.getString("verification_status"), rs.getString("verification_hash"),
                rs.getString("verification_config_hash"), rs.getString("bounded_summary"),
                rs.getString("status"), rs.getString("created_at"), rs.getString("updated_at"));
    }

    private String validateChangedFiles(String value, int expectedCount) {
        try {
            JsonNode node = MAPPER.readTree(value);
            if (node == null || !node.isArray() || node.size() != expectedCount)
                throw new IllegalArgumentException(
                        "changedFilesJson must be an array matching changedFileCount");
            for (JsonNode entry : node) {
                if (!entry.isTextual() || entry.asText().isBlank() || entry.asText().length() > 1024)
                    throw new IllegalArgumentException(
                            "changedFilesJson entries must be bounded nonblank paths");
            }
            String normalized = MAPPER.writeValueAsString(node);
            if (normalized.length() > WorkerStoreSupport.MAX_CHANGED_FILES_CHARS)
                throw new IllegalArgumentException("changedFilesJson exceeds persistence limit");
            return normalized;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("changedFilesJson must be valid JSON", e);
        }
    }

    private static final String SELECT = """
            SELECT candidate_id, worker_run_id, worktree_id, task_claim_id,
                   backend_type, base_commit_sha, worker_branch, candidate_commit_sha,
                   diff_hash, changed_files_json, changed_file_count,
                   verification_status, verification_hash, verification_config_hash, bounded_summary,
                   status, created_at, updated_at FROM worker_candidates
            """;

    public record WorkerCandidateRecord(String candidateId, String workerRunId,
            String worktreeId, String taskClaimId, String backendType, String baseCommitSha,
            String workerBranch, String candidateCommitSha, String diffHash,
            String changedFilesJson, int changedFileCount, String verificationStatus,
            String verificationHash, String verificationConfigHash, String boundedSummary, String status,
            String createdAt, String updatedAt) { }
}
