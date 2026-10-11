package com.paicli.worker;

import com.paicli.state.*;
import com.paicli.state.ManagedWorktreeStore.ManagedWorktreeRecord;
import com.paicli.state.WorkerCandidateStore.WorkerCandidateRecord;
import com.paicli.state.WorkerCleanupStore.WorkerCleanupRecord;
import com.paicli.state.WorkerMergeProposalStore.CandidateRef;
import com.paicli.worktree.HardenedGitProcess;
import com.paicli.worktree.HardenedGitProcess.Operation;

import java.io.IOException;
import java.nio.file.*;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;

/** Explicit ownership-checked cleanup for retained Worker and Proposal resources. */
public final class WorkerCleanupService {
    private final StateStore state;
    private final Path repositoryRoot;
    private final Path managedRoot;
    private final HardenedGitProcess git;
    private final CleanupFileOps files;

    public WorkerCleanupService(StateStore state, Path repositoryRoot, Path managedRoot)
            throws IOException {
        this(state, repositoryRoot, managedRoot, CleanupFileOps.REAL);
    }

    WorkerCleanupService(StateStore state, Path repositoryRoot, Path managedRoot,
                         CleanupFileOps files) throws IOException {
        this.state = Objects.requireNonNull(state);
        this.repositoryRoot = repositoryRoot.toAbsolutePath().normalize();
        this.managedRoot = managedRoot.toAbsolutePath().normalize();
        this.files = Objects.requireNonNull(files);
        Path hooks = Files.createDirectories(this.managedRoot.resolve("runtime-empty-hooks"));
        this.git = new HardenedGitProcess(hooks, Duration.ofSeconds(120), 262_144);
    }

    public Eligibility inspectCleanupEligibility(String worktreeId) {
        ManagedWorktreeRecord worktree = worktree(worktreeId);
        WorkerCandidateRecord candidate = candidate(worktreeId);
        if (!ManagedWorktreeStore.BACKEND_GIT.equals(worktree.backendType()))
            return new Eligibility(false, "UNSUPPORTED_BACKEND", worktree.status());
        if (!Set.of("ABANDONED", "INTEGRATED").contains(worktree.status()))
            return new Eligibility(false, "WORKTREE_LIFECYCLE_NOT_CLEANABLE", worktree.status());
        if (candidate == null || candidate.candidateCommitSha() == null)
            return new Eligibility(false, "UNFROZEN_DIRTY_WORKTREE_RETAINED", worktree.status());
        try {
            Path workspace = safeManagedPath(Path.of(worktree.canonicalWorkspacePath()),
                    managedRoot.resolve("workspaces"));
            if (!Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS))
                return new Eligibility(false, "WORKSPACE_MISSING", worktree.status());
            if (!output(runOk(workspace, Operation.HEAD)).equals(candidate.candidateCommitSha()))
                return new Eligibility(false, "WORKTREE_HEAD_DRIFT", worktree.status());
            if (!blank(runOk(workspace, Operation.STATUS).stdoutExcerpt())
                    || !blank(runOk(workspace, Operation.UNMERGED).stdoutExcerpt()))
                return new Eligibility(false, "VALUABLE_DIRTY_WORKTREE_RETAINED", worktree.status());
            String ref = output(runOk(repositoryRoot, Operation.REF_SHA, worktree.workerBranch()));
            if (!ref.equals(candidate.candidateCommitSha()))
                return new Eligibility(false, "BRANCH_REF_DRIFT", worktree.status());
            return new Eligibility(true, "ELIGIBLE", worktree.status());
        } catch (RuntimeException e) {
            return new Eligibility(false, "OWNERSHIP_UNPROVEN", worktree.status());
        }
    }

    public CleanupResult cleanupAbandonedWorktree(String worktreeId) {
        if (!"ABANDONED".equals(worktree(worktreeId).status()))
            throw new IllegalStateException("Explicit abandoned cleanup requires ABANDONED worktree");
        return cleanupWorktree(worktreeId, "USER");
    }

    public CleanupResult cleanupIntegratedWorktree(String worktreeId, String requestedBy) {
        if (!"INTEGRATED".equals(worktree(worktreeId).status()))
            throw new IllegalStateException("Integrated cleanup requires INTEGRATED worktree");
        return cleanupWorktree(worktreeId, requestedBy);
    }

    public List<CleanupResult> cleanupIntegratedResources(String proposalId) {
        var proposal = proposal(proposalId);
        if (!"INTEGRATED".equals(proposal.status()))
            throw new IllegalStateException("Proposal must be INTEGRATED before resource cleanup");
        List<CleanupResult> results = new ArrayList<>();
        for (CandidateRef ref : candidates(proposalId)) {
            WorkerCandidateRecord candidate = candidateById(ref.candidateId());
            results.add(cleanupIntegratedWorktree(candidate.worktreeId(), "RUNTIME"));
        }
        results.add(cleanupProposal(proposalId, "RUNTIME"));
        return List.copyOf(results);
    }

    public CleanupResult cleanupProposal(String proposalId, String requestedBy) {
        var proposal = proposal(proposalId);
        if (!Set.of("INTEGRATED", "CONFLICTED", "VERIFICATION_FAILED", "INVALID")
                .contains(proposal.status()))
            throw new IllegalStateException("Proposal lifecycle is not cleanup-eligible");
        String cleanupId = "cleanup-proposal-" + UUID.randomUUID();
        startProposal(cleanupId, proposalId, requestedBy);
        String workspaceStatus = "PENDING";
        String stateStatus = "PENDING";
        String traceStatus = "PENDING";
        List<String> errors = new ArrayList<>();
        try {
            Path workspace = safeManagedPath(Path.of(proposal.canonicalWorkspacePath()),
                    managedRoot.resolve("proposals").resolve("workspaces"));
            if (proposal.proposalCommitSha() == null
                    || !Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS)
                    || !blank(runOk(workspace, Operation.STATUS).stdoutExcerpt())
                    || !output(runOk(workspace, Operation.HEAD))
                            .equals(proposal.proposalCommitSha())) {
                workspaceStatus = "RETAINED";
                errors.add("Proposal workspace ownership/integrity not cleanly provable");
            } else {
                runOk(repositoryRoot, Operation.WORKTREE_REMOVE, workspace.toString());
                runOk(repositoryRoot, Operation.DELETE_REF_SAFE, proposal.proposalBranch(),
                        proposal.proposalCommitSha());
                workspaceStatus = "SUCCEEDED";
            }
        } catch (RuntimeException e) {
            workspaceStatus = "FAILED";
            errors.add(e.getMessage());
        }
        Path runtime = managedRoot.resolve("proposals").resolve("runtime").resolve(proposalId);
        Path statePath = runtime.resolve("paicli.db");
        Path tracePath = runtime.resolve("traces");
        if ("SUCCEEDED".equals(workspaceStatus)) {
            stateStatus = deleteComponent(statePath);
            traceStatus = deleteComponent(tracePath);
        } else {
            stateStatus = "RETAINED";
            traceStatus = "RETAINED";
        }
        if ("FAILED".equals(stateStatus)) errors.add("State cleanup failed");
        if ("FAILED".equals(traceStatus)) errors.add("Trace cleanup failed");
        finishCleanup(cleanupId, workspaceStatus, stateStatus, traceStatus, errors);
        if ("SUCCEEDED".equals(workspaceStatus) && "SUCCEEDED".equals(stateStatus)
                && "SUCCEEDED".equals(traceStatus)) {
            try { state.workerMergeProposals.markCleaned(proposalId); }
            catch (SQLException e) { throw new IllegalStateException("Proposal cleanup state failed", e); }
        }
        return cleanupResult(cleanupId);
    }

    public CleanupResult resumeCleanup(String cleanupRunId) {
        WorkerCleanupRecord previous = cleanupRecord(cleanupRunId);
        if ("COMPLETED".equals(previous.status())) return cleanupResult(cleanupRunId);
        if (!"PARTIAL".equals(previous.status())
                || !"SUCCEEDED".equals(previous.workspaceCleanupStatus())) {
            throw new IllegalStateException(
                    "Cleanup can resume only after a proven workspace cleanup");
        }
        String nextId = cleanupRunId + "-resume-" + UUID.randomUUID();
        String stateStatus;
        String traceStatus;
        if ("WORKTREE".equals(previous.targetType())) {
            ManagedWorktreeRecord worktree = worktree(previous.worktreeId());
            WorkerCandidateRecord candidate = candidate(previous.worktreeId());
            start(nextId, previous.worktreeId(), previous.requestedBy());
            WorkerStateLocation.Location location = new WorkerStateLocation(managedRoot,
                    Path.of(worktree.canonicalWorkspacePath())).locate(candidate.workerRunId());
            stateStatus = "SUCCEEDED".equals(previous.stateCleanupStatus()) ? "SUCCEEDED"
                    : deleteComponent(location.stateDatabase().getParent());
            traceStatus = "SUCCEEDED".equals(previous.traceCleanupStatus()) ? "SUCCEEDED"
                    : deleteComponent(location.traceDirectory());
            finishCleanup(nextId, "SUCCEEDED", stateStatus, traceStatus, List.of());
            if ("SUCCEEDED".equals(stateStatus) && "SUCCEEDED".equals(traceStatus)) {
                try { state.managedWorktrees.markCleaned(previous.worktreeId()); }
                catch (SQLException e) { throw new IllegalStateException("Cleanup state failed", e); }
            }
        } else {
            startProposal(nextId, previous.proposalId(), previous.requestedBy());
            Path runtime = managedRoot.resolve("proposals").resolve("runtime")
                    .resolve(previous.proposalId());
            stateStatus = "SUCCEEDED".equals(previous.stateCleanupStatus()) ? "SUCCEEDED"
                    : deleteComponent(runtime.resolve("paicli.db"));
            traceStatus = "SUCCEEDED".equals(previous.traceCleanupStatus()) ? "SUCCEEDED"
                    : deleteComponent(runtime.resolve("traces"));
            finishCleanup(nextId, "SUCCEEDED", stateStatus, traceStatus, List.of());
            if ("SUCCEEDED".equals(stateStatus) && "SUCCEEDED".equals(traceStatus)) {
                try { state.workerMergeProposals.markCleaned(previous.proposalId()); }
                catch (SQLException e) { throw new IllegalStateException("Cleanup state failed", e); }
            }
        }
        return cleanupResult(nextId);
    }

    private CleanupResult cleanupWorktree(String worktreeId, String actor) {
        Eligibility eligibility = inspectCleanupEligibility(worktreeId);
        if (!eligibility.eligible())
            throw new IllegalStateException("Cleanup denied: " + eligibility.reason());
        ManagedWorktreeRecord worktree = worktree(worktreeId);
        WorkerCandidateRecord candidate = candidate(worktreeId);
        String cleanupId = "cleanup-worktree-" + UUID.randomUUID();
        start(cleanupId, worktreeId, actor);
        String workspaceStatus = "PENDING";
        String stateStatus = "PENDING";
        String traceStatus = "PENDING";
        List<String> errors = new ArrayList<>();
        try {
            Path workspace = safeManagedPath(Path.of(worktree.canonicalWorkspacePath()),
                    managedRoot.resolve("workspaces"));
            runOk(repositoryRoot, Operation.WORKTREE_REMOVE, workspace.toString());
            runOk(repositoryRoot, Operation.DELETE_REF_SAFE, worktree.workerBranch(),
                    candidate.candidateCommitSha());
            workspaceStatus = "SUCCEEDED";
        } catch (RuntimeException e) {
            workspaceStatus = "FAILED";
            errors.add(e.getMessage());
        }
        if ("SUCCEEDED".equals(workspaceStatus)) {
            WorkerStateLocation.Location location = new WorkerStateLocation(managedRoot,
                    Path.of(worktree.canonicalWorkspacePath())).locate(candidate.workerRunId());
            stateStatus = deleteComponent(location.stateDatabase().getParent());
            traceStatus = deleteComponent(location.traceDirectory());
        } else {
            stateStatus = "RETAINED";
            traceStatus = "RETAINED";
        }
        if ("FAILED".equals(stateStatus)) errors.add("State cleanup failed");
        if ("FAILED".equals(traceStatus)) errors.add("Trace cleanup failed");
        finishCleanup(cleanupId, workspaceStatus, stateStatus, traceStatus, errors);
        if ("SUCCEEDED".equals(workspaceStatus) && "SUCCEEDED".equals(stateStatus)
                && "SUCCEEDED".equals(traceStatus)) {
            try { state.managedWorktrees.markCleaned(worktreeId); }
            catch (SQLException e) { throw new IllegalStateException("Worktree cleanup state failed", e); }
        }
        return cleanupResult(cleanupId);
    }

    private String deleteComponent(Path path) {
        try {
            Path safe = safeManagedPath(path, managedRoot);
            if (!Files.exists(safe, LinkOption.NOFOLLOW_LINKS)) return "SUCCEEDED";
            files.deleteTree(safe);
            return Files.exists(safe, LinkOption.NOFOLLOW_LINKS) ? "FAILED" : "SUCCEEDED";
        } catch (Exception e) {
            return "FAILED";
        }
    }

    private Path safeManagedPath(Path path, Path allowedRoot) {
        try {
            Path root = allowedRoot.toAbsolutePath().normalize();
            Path normalized = path.toAbsolutePath().normalize();
            if (!normalized.startsWith(root) || normalized.equals(root)
                    || normalized.startsWith(repositoryRoot))
                throw new SecurityException("Cleanup path is outside owned managed root");
            Path current = normalized;
            while (current != null && current.startsWith(root)) {
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                        && Files.isSymbolicLink(current))
                    throw new SecurityException("Cleanup path contains symlink");
                if (current.equals(root)) break;
                current = current.getParent();
            }
            return normalized;
        } catch (RuntimeException e) { throw e; }
    }

    private void finishCleanup(String id, String workspace, String stateStatus,
                               String trace, List<String> errors) {
        try {
            if ("SUCCEEDED".equals(workspace) && "SUCCEEDED".equals(stateStatus)
                    && "SUCCEEDED".equals(trace)) {
                state.workerCleanups.complete(id, workspace, stateStatus, trace);
            } else {
                state.workerCleanups.fail(id, workspace, stateStatus, trace,
                        String.join("; ", errors));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cleanup audit persistence failed", e);
        }
    }

    private void start(String id, String worktreeId, String actor) {
        try { state.workerCleanups.start(id, worktreeId, actor); }
        catch (SQLException e) { throw new IllegalStateException("Cleanup start persistence failed", e); }
    }
    private void startProposal(String id, String proposalId, String actor) {
        try { state.workerCleanups.startProposal(id, proposalId, actor); }
        catch (SQLException e) { throw new IllegalStateException("Cleanup start persistence failed", e); }
    }

    private CleanupResult cleanupResult(String id) {
        WorkerCleanupRecord r = cleanupRecord(id);
        return new CleanupResult(r.cleanupRunId(), r.targetType(),
                r.worktreeId() == null ? r.proposalId() : r.worktreeId(), r.status(),
                r.workspaceCleanupStatus(), r.stateCleanupStatus(), r.traceCleanupStatus(),
                r.errorExcerpt());
    }

    private WorkerCleanupRecord cleanupRecord(String id) {
        try { return state.workerCleanups.findById(id).orElseThrow(); }
        catch (SQLException e) { throw new IllegalStateException("Cleanup persistence failed", e); }
    }
    private ManagedWorktreeRecord worktree(String id) {
        try { return state.managedWorktrees.findById(id).orElseThrow(); }
        catch (SQLException e) { throw new IllegalStateException("Worktree persistence failed", e); }
    }
    private WorkerCandidateRecord candidate(String worktreeId) {
        try { return state.workerCandidates.findByWorktreeId(worktreeId).orElse(null); }
        catch (SQLException e) { throw new IllegalStateException("Candidate persistence failed", e); }
    }
    private WorkerCandidateRecord candidateById(String id) {
        try { return state.workerCandidates.findById(id).orElseThrow(); }
        catch (SQLException e) { throw new IllegalStateException("Candidate persistence failed", e); }
    }
    private WorkerMergeProposalStore.Record proposal(String id) {
        try { return state.workerMergeProposals.findById(id).orElseThrow(); }
        catch (SQLException e) { throw new IllegalStateException("Proposal persistence failed", e); }
    }
    private List<CandidateRef> candidates(String id) {
        try { return state.workerMergeProposals.candidates(id); }
        catch (SQLException e) { throw new IllegalStateException("Proposal Candidate persistence failed", e); }
    }

    private HardenedGitProcess.Result runOk(Path cwd, Operation op, String... values) {
        var result = git.execute(cwd, op, values);
        if (!result.succeeded())
            throw new IllegalStateException("Runtime Git " + op + " failed: "
                    + (result.stderrExcerpt() == null ? "" : result.stderrExcerpt()));
        return result;
    }
    private static String output(HardenedGitProcess.Result r) {
        if (r.stdoutExcerpt() == null || r.stdoutExcerpt().isBlank())
            throw new IllegalStateException("Git output missing");
        return r.stdoutExcerpt().strip();
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }

    @FunctionalInterface interface CleanupFileOps {
        CleanupFileOps REAL = root -> {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
            }
        };
        void deleteTree(Path root) throws IOException;
    }

    public record Eligibility(boolean eligible, String reason, String lifecycleStatus) { }
    public record CleanupResult(String cleanupRunId, String targetType, String targetId,
            String status, String workspaceStatus, String stateStatus, String traceStatus,
            String errorExcerpt) { }
}
