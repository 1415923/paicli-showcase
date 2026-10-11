package com.paicli.state;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class ManagedWorktreeStore {

    public static final String BACKEND_GIT = "GIT_LINKED_WORKTREE";
    public static final String BACKEND_DIRECTORY = "DIRECTORY_EXPORT";
    public static final String CAPABILITY_GIT = "GIT_BRANCH_COMMIT";
    public static final String CAPABILITY_EXPORT = "EXPORT_MANIFEST";
    public static final String STATUS_ALLOCATING = "ALLOCATING";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DIRTY = "DIRTY";
    public static final String STATUS_FREEZING = "FREEZING";
    public static final String STATUS_CANDIDATE_FROZEN = "CANDIDATE_FROZEN";
    public static final String STATUS_ABANDONED = "ABANDONED";
    public static final String STATUS_LOST = "LOST";
    public static final String STATUS_CLEANED = "CLEANED";

    private static final Set<String> BACKENDS = Set.of(BACKEND_GIT, BACKEND_DIRECTORY);
    private static final Set<String> DIRTY_STATES = Set.of("CLEAN", "DIRTY", "UNKNOWN");
    private final Connection connection;

    public ManagedWorktreeStore(Connection connection) {
        this.connection = connection;
    }

    public void create(String id, String backendType, String repositoryIdentity,
                       String baseCommitSha, String workerBranch,
                       String workspacePath, String statePath, String tracePath,
                       String dirtyState) throws SQLException {
        WorkerStoreSupport.id(id, "managedWorktreeId");
        WorkerStoreSupport.oneOf(backendType, "backendType", BACKENDS);
        WorkerStoreSupport.oneOf(dirtyState, "dirtyState", DIRTY_STATES);
        boolean git = BACKEND_GIT.equals(backendType);
        if (git && (workerBranch == null || workerBranch.isBlank())) {
            throw new IllegalArgumentException("Git linked worktree requires a branch");
        }
        if (!git && workerBranch != null) {
            throw new IllegalArgumentException("Directory export cannot have a worker branch");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO managed_worktrees(
                    managed_worktree_id, backend_type, backend_capability,
                    repository_identity, base_commit_sha, worker_branch,
                    canonical_workspace_path, canonical_state_path,
                    canonical_trace_path, status, dirty_state
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ALLOCATING', ?)
                """)) {
            statement.setString(1, id);
            statement.setString(2, backendType);
            statement.setString(3, git ? CAPABILITY_GIT : CAPABILITY_EXPORT);
            statement.setString(4, WorkerStoreSupport.required(repositoryIdentity, "repositoryIdentity", 512));
            statement.setString(5, WorkerStoreSupport.commitSha(baseCommitSha, "baseCommitSha"));
            statement.setString(6, workerBranch == null ? null
                    : WorkerStoreSupport.required(workerBranch, "workerBranch", 512));
            statement.setString(7, WorkerStoreSupport.required(workspacePath, "workspacePath", 4096));
            statement.setString(8, WorkerStoreSupport.required(statePath, "statePath", 4096));
            statement.setString(9, WorkerStoreSupport.required(tracePath, "tracePath", 4096));
            statement.setString(10, dirtyState);
            statement.executeUpdate();
        }
    }

    public void markActive(String id, String ownerWorkerRunId) throws SQLException {
        updateStatus(id, STATUS_ACTIVE, ownerWorkerRunId, null, Set.of(STATUS_ALLOCATING));
    }

    public void assignWorker(String id, String ownerWorkerRunId) throws SQLException {
        updateRuntimeState(id, STATUS_ACTIVE, "CLEAN", ownerWorkerRunId, null,
                Set.of(STATUS_ACTIVE));
    }

    public void markDirty(String id, String ownerWorkerRunId) throws SQLException {
        updateRuntimeState(id, STATUS_DIRTY, "DIRTY", ownerWorkerRunId, null,
                Set.of(STATUS_ACTIVE, STATUS_DIRTY));
    }

    /** Atomically owns the dirty worktree for one candidate freeze operation. */
    public void beginCandidateFreeze(String id, String ownerWorkerRunId, String freezeToken)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE managed_worktrees
                SET status = 'FREEZING', freeze_token = ?, updated_at = datetime('now')
                WHERE managed_worktree_id = ? AND status = 'DIRTY'
                  AND dirty_state = 'DIRTY' AND owner_worker_run_id = ?
                  AND freeze_token IS NULL
                """)) {
            statement.setString(1, WorkerStoreSupport.hash(freezeToken, "freezeToken"));
            statement.setString(2, id);
            statement.setString(3, WorkerStoreSupport.id(ownerWorkerRunId, "ownerWorkerRunId"));
            WorkerStoreSupport.requireUpdated(statement,
                    "Managed worktree is not exclusively freezeable: " + id);
        }
    }

    public void markCandidateFrozen(String id, String ownerWorkerRunId, String freezeToken)
            throws SQLException {
        finishFreeze(id, ownerWorkerRunId, freezeToken, STATUS_CANDIDATE_FROZEN,
                "CLEAN", null);
    }

    public void markFreezeAbandoned(String id, String ownerWorkerRunId,
                                    String freezeToken, String error)
            throws SQLException {
        finishFreeze(id, ownerWorkerRunId, freezeToken, STATUS_ABANDONED,
                "UNKNOWN", error);
    }

    private void finishFreeze(String id, String ownerWorkerRunId, String freezeToken,
                              String status, String dirtyState, String error) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE managed_worktrees
                SET status = ?, dirty_state = ?, freeze_token = NULL,
                    last_error_excerpt = ?, updated_at = datetime('now'),
                    abandoned_at = CASE WHEN ? = 'ABANDONED' THEN datetime('now')
                                        ELSE abandoned_at END
                WHERE managed_worktree_id = ? AND status = 'FREEZING'
                  AND owner_worker_run_id = ? AND freeze_token = ?
                """)) {
            statement.setString(1, status);
            statement.setString(2, dirtyState);
            statement.setString(3, WorkerStoreSupport.optional(
                    error, WorkerStoreSupport.MAX_EXCERPT_CHARS));
            statement.setString(4, status);
            statement.setString(5, id);
            statement.setString(6, WorkerStoreSupport.id(ownerWorkerRunId, "ownerWorkerRunId"));
            statement.setString(7, WorkerStoreSupport.hash(freezeToken, "freezeToken"));
            WorkerStoreSupport.requireUpdated(statement,
                    "Managed worktree freeze ownership changed: " + id);
        }
    }

    public void markActiveClean(String id, String ownerWorkerRunId) throws SQLException {
        updateRuntimeState(id, STATUS_ACTIVE, "CLEAN", ownerWorkerRunId, null,
                Set.of(STATUS_ACTIVE));
    }

    public void markAbandonedForWorker(String id, String ownerWorkerRunId,
                                       String error, boolean dirty) throws SQLException {
        updateRuntimeState(id, STATUS_ABANDONED, dirty ? "DIRTY" : "UNKNOWN",
                ownerWorkerRunId, error, Set.of(STATUS_ACTIVE, "DIRTY"));
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE managed_worktrees SET abandoned_at = datetime('now')
                WHERE managed_worktree_id = ? AND status = 'ABANDONED'
                """)) {
            statement.setString(1, id);
            WorkerStoreSupport.requireUpdated(statement,
                    "Managed worktree abandonment timestamp failed: " + id);
        }
    }

    public void markAbandoned(String id, String error) throws SQLException {
        updateStatus(id, STATUS_ABANDONED, null, error,
                Set.of(STATUS_ALLOCATING, STATUS_ACTIVE, STATUS_DIRTY, STATUS_FREEZING,
                        "CANDIDATE_FROZEN", "LOST"));
    }

    public void markLost(String id, String error) throws SQLException {
        updateStatus(id, STATUS_LOST, null, error,
                Set.of(STATUS_ALLOCATING, STATUS_ACTIVE, "DIRTY",
                        "CANDIDATE_FROZEN", STATUS_ABANDONED));
    }

    public void markIntegrated(String id) throws SQLException {
        updateStatus(id, "INTEGRATED", null, null,
                Set.of(STATUS_CANDIDATE_FROZEN));
    }
    public void markCleaned(String id) throws SQLException {
        updateStatus(id, STATUS_CLEANED, null, null,
                Set.of(STATUS_ABANDONED, "INTEGRATED", "LOST"));
    }

    public Optional<ManagedWorktreeRecord> findById(String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SELECT + " WHERE managed_worktree_id = ?")) {
            statement.setString(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        }
    }

    public Optional<ManagedWorktreeRecord> findByWorkspacePath(String canonicalPath)
            throws SQLException {
        return findOne("canonical_workspace_path", canonicalPath);
    }

    public Optional<ManagedWorktreeRecord> findByRepositoryAndBranch(
            String repositoryIdentity, String branch) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT + " WHERE repository_identity = ? AND worker_branch = ?")) {
            statement.setString(1, repositoryIdentity);
            statement.setString(2, branch);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        }
    }

    public List<ManagedWorktreeRecord> findAll() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT + " ORDER BY created_at, managed_worktree_id");
             ResultSet resultSet = statement.executeQuery()) {
            List<ManagedWorktreeRecord> records = new ArrayList<>();
            while (resultSet.next()) records.add(map(resultSet));
            return List.copyOf(records);
        }
    }

    private Optional<ManagedWorktreeRecord> findOne(String column, String value)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT + " WHERE " + column + " = ?")) {
            statement.setString(1, value);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(map(resultSet)) : Optional.empty();
            }
        }
    }

    private void updateStatus(String id, String status, String owner, String error,
                              Set<String> allowedFrom) throws SQLException {
        String timestampColumn = STATUS_ABANDONED.equals(status) ? "abandoned_at"
                : STATUS_CLEANED.equals(status) ? "cleaned_at" : null;
        String placeholders = String.join(",",
                allowedFrom.stream().map(value -> "?").toList());
        String sql = "UPDATE managed_worktrees SET status = ?, owner_worker_run_id = "
                + (owner == null ? "owner_worker_run_id" : "?")
                + ", last_error_excerpt = ?, updated_at = datetime('now')"
                + (timestampColumn == null ? "" : ", " + timestampColumn + " = datetime('now')")
                + " WHERE managed_worktree_id = ? AND status IN (" + placeholders + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, status);
            if (owner != null) {
                statement.setString(index++, WorkerStoreSupport.id(owner, "ownerWorkerRunId"));
            }
            statement.setString(index++, WorkerStoreSupport.optional(error, WorkerStoreSupport.MAX_EXCERPT_CHARS));
            statement.setString(index++, id);
            for (String from : allowedFrom) statement.setString(index++, from);
            WorkerStoreSupport.requireUpdated(statement, "Managed worktree not found or invalid state: " + id);
        }
    }

    private void updateRuntimeState(String id, String status, String dirtyState,
                                    String ownerWorkerRunId, String error,
                                    Set<String> allowedFrom) throws SQLException {
        String placeholders = String.join(",",
                allowedFrom.stream().map(value -> "?").toList());
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE managed_worktrees
                SET status = ?, dirty_state = ?, owner_worker_run_id = ?,
                    last_error_excerpt = ?, updated_at = datetime('now')
                WHERE managed_worktree_id = ? AND status IN (%s)
                """.formatted(placeholders))) {
            statement.setString(1, status);
            statement.setString(2, dirtyState);
            statement.setString(3, WorkerStoreSupport.id(ownerWorkerRunId, "ownerWorkerRunId"));
            statement.setString(4, WorkerStoreSupport.optional(
                    error, WorkerStoreSupport.MAX_EXCERPT_CHARS));
            statement.setString(5, id);
            int index = 6;
            for (String from : allowedFrom) statement.setString(index++, from);
            WorkerStoreSupport.requireUpdated(statement,
                    "Managed worktree not found or invalid runtime state: " + id);
        }
    }

    private ManagedWorktreeRecord map(ResultSet rs) throws SQLException {
        return new ManagedWorktreeRecord(rs.getString("managed_worktree_id"), rs.getString("backend_type"),
                rs.getString("backend_capability"), rs.getString("repository_identity"),
                rs.getString("base_commit_sha"), rs.getString("worker_branch"),
                rs.getString("canonical_workspace_path"), rs.getString("canonical_state_path"),
                rs.getString("canonical_trace_path"), rs.getString("status"), rs.getString("dirty_state"),
                rs.getString("owner_worker_run_id"), rs.getString("freeze_token"),
                rs.getString("created_at"), rs.getString("updated_at"),
                rs.getString("abandoned_at"), rs.getString("cleaned_at"), rs.getString("last_error_excerpt"));
    }

    private static final String SELECT = """
            SELECT managed_worktree_id, backend_type, backend_capability,
                   repository_identity, base_commit_sha, worker_branch,
                   canonical_workspace_path, canonical_state_path, canonical_trace_path,
                   status, dirty_state, owner_worker_run_id, freeze_token, created_at, updated_at,
                   abandoned_at, cleaned_at, last_error_excerpt
            FROM managed_worktrees
            """;

    public record ManagedWorktreeRecord(String managedWorktreeId, String backendType,
            String backendCapability, String repositoryIdentity, String baseCommitSha,
            String workerBranch, String canonicalWorkspacePath, String canonicalStatePath,
            String canonicalTracePath, String status, String dirtyState, String ownerWorkerRunId,
            String freezeToken,
            String createdAt, String updatedAt, String abandonedAt, String cleanedAt,
            String lastErrorExcerpt) { }
}
