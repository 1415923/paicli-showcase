package com.paicli.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.Config;
import com.paicli.runtime.PersistenceSanitizer;
import com.paicli.runtime.VerificationResult;
import com.paicli.runtime.VerificationService;
import com.paicli.state.CodingRunStore;
import com.paicli.state.ManagedWorktreeStore;
import com.paicli.state.ManagedWorktreeStore.ManagedWorktreeRecord;
import com.paicli.state.StateStore;
import com.paicli.state.WorkerCandidateStore.WorkerCandidateRecord;
import com.paicli.state.WorkerRunStore.WorkerRunRecord;
import com.paicli.worktree.HardenedGitProcess;
import com.paicli.worktree.HardenedGitProcess.Operation;
import com.paicli.worktree.HardenedGitProcess.Result;
import com.paicli.worktree.ManagedWorktreeService;
import com.paicli.worktree.WorkerWorkspacePathGuard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Runtime-owned candidate commit and configured verification boundary. */
public final class WorkerCandidateService {
    public static final int MAX_CHANGED_FILES = 500;
    public static final long MAX_SINGLE_FILE_BYTES = 10L * 1024 * 1024;
    public static final long MAX_TOTAL_CANDIDATE_BYTES = 50L * 1024 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int GIT_OUTPUT_BYTES = 262_144;
    private static final Pattern FORBIDDEN_VERIFICATION_GIT = Pattern.compile(
            "(?i)\\bgit(?:\\.exe)?\\b[^;&|\\r\\n]{0,512}\\b(?:worktree|branch|checkout|switch|"
                    + "commit|merge|rebase|cherry-pick|reset|clean|tag|push)\\b");

    private final StateStore coordinator;
    private final ManagedWorktreeService worktrees;
    private final Path repositoryRoot;
    private final Path managedRoot;
    private final Config config;
    private final PersistenceSanitizer sanitizer = new PersistenceSanitizer();
    private final HardenedGitProcess git;
    private final FaultInjector faults;
    private final CandidateLimits limits;
    private final VerificationExecutor verificationExecutor;

    public WorkerCandidateService(StateStore coordinator,
                                  ManagedWorktreeService worktrees,
                                  Path repositoryRoot,
                                  Path managedRoot,
                                  Config config) throws IOException {
        this(coordinator, worktrees, repositoryRoot, managedRoot, config,
                FaultInjector.NONE, CandidateLimits.PRODUCTION, null);
    }

    WorkerCandidateService(StateStore coordinator,
                           ManagedWorktreeService worktrees,
                           Path repositoryRoot,
                           Path managedRoot,
                           Config config,
                           FaultInjector faults,
                           CandidateLimits limits,
                           VerificationExecutor verificationExecutor) throws IOException {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.worktrees = Objects.requireNonNull(worktrees, "worktrees");
        this.repositoryRoot = Objects.requireNonNull(repositoryRoot, "repositoryRoot")
                .toAbsolutePath().normalize();
        this.managedRoot = Objects.requireNonNull(managedRoot, "managedRoot")
                .toAbsolutePath().normalize();
        this.config = Objects.requireNonNull(config, "config");
        this.faults = Objects.requireNonNull(faults, "faults");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.verificationExecutor = verificationExecutor == null
                ? this::executeVerification : verificationExecutor;
        if (FORBIDDEN_VERIFICATION_GIT.matcher(config.effectiveVerificationCommand()).find()) {
            throw new IllegalArgumentException(
                    "Configured verification cannot contain Git control/publish operations");
        }
        Path hooks = this.managedRoot.resolve("runtime-empty-hooks");
        Files.createDirectories(hooks);
        this.git = new HardenedGitProcess(hooks, Duration.ofSeconds(120), GIT_OUTPUT_BYTES);
    }

    public WorkerCandidateResult freezeAndVerify(String workerRunId) {
        WorkerRunRecord run = workerRun(workerRunId);
        WorkerCandidateRecord existing = candidateForRun(workerRunId);
        String configHash = verificationConfigHash(Path.of(worktree(run.worktreeId())
                .canonicalWorkspacePath()));
        if (existing != null) return reuse(existing, configHash);

        requireCompleted(run);
        ManagedWorktreeRecord worktree = worktree(run.worktreeId());
        requireFreezeable(worktree, run);
        assertFreezeable(worktree.managedWorktreeId());

        String token = sha256(UUID.randomUUID().toString());
        String candidateId = "candidate-" + UUID.randomUUID();
        beginFreeze(worktree.managedWorktreeId(), workerRunId, token);
        WorkerTrace trace = trace(worktree, workerRunId);
        trace.record("candidate_freeze_started", workerRunId, "candidate_id=" + candidateId);
        try {
            Path workspace = Path.of(worktree.canonicalWorkspacePath()).toRealPath();
            requireHead(workspace, worktree.baseCommitSha());
            requireEmpty(runGit(workspace, Operation.UNMERGED), "Unmerged index is not freezeable");
            String preflightStatus = runGit(workspace, Operation.STATUS).stdoutExcerpt();
            requireMachineOutputComplete(preflightStatus, "worktree status");
            rejectCheckoutControlChanges(preflightStatus);
            if (isBlank(preflightStatus)) {
                throw new IllegalStateException("Clean worktree has no candidate changes");
            }

            runOk(workspace, Operation.STAGE_ALL);
            String rawIndex = runOk(workspace, Operation.CACHED_RAW).stdoutExcerpt();
            requireMachineOutputComplete(rawIndex, "cached index");
            if (rawIndex != null && rawIndex.contains("160000")) {
                throw new SecurityException("Candidate gitlink/submodule is forbidden");
            }
            List<String> changed = changedFiles(workspace, worktree);
            CandidateBudget budget = validateBudget(workspace, changed);
            String tree = output(runOk(workspace, Operation.WRITE_TREE));
            String message = "PaiCLI candidate " + workerRunId + " " + candidateId;
            String commit = output(runOk(workspace, Operation.COMMIT_TREE,
                    tree, worktree.baseCommitSha(), message));
            verifyCommit(workspace, commit, worktree.baseCommitSha(), tree);
            faults.hit(FaultPoint.AFTER_COMMIT_OBJECT);
            runOk(workspace, Operation.UPDATE_REF, worktree.workerBranch(), commit,
                    worktree.baseCommitSha());
            requireHead(workspace, commit);
            faults.hit(FaultPoint.AFTER_BRANCH_UPDATE);

            String diffHash = sha256(worktree.baseCommitSha() + "\n" + tree + "\n"
                    + String.join("\n", changed));
            freezeCandidate(candidateId, run, worktree, commit, diffHash, changed,
                    configHash, budget);
            faults.hit(FaultPoint.AFTER_CANDIDATE_PERSIST);
            markFrozen(worktree.managedWorktreeId(), workerRunId, token);
            trace.record("candidate_freeze_completed", workerRunId,
                    "candidate_id=" + candidateId + "; files=" + changed.size()
                            + "; diff_hash=" + diffHash);
            return verifyCandidate(candidateId, run, worktree, changed, commit,
                    diffHash, configHash, trace);
        } catch (Exception e) {
            if (candidateForRun(workerRunId) == null) {
                abandonFreeze(worktree.managedWorktreeId(), workerRunId, token, e);
            } else {
                abandonCandidateFailure(worktree.managedWorktreeId(), workerRunId, token, e);
            }
            trace.record("candidate_freeze_failed", workerRunId, safe(e.getMessage()));
            throw new IllegalStateException("Candidate freeze failed: " + safe(e.getMessage()), e);
        }
    }

    private WorkerCandidateResult verifyCandidate(String candidateId, WorkerRunRecord run,
                                                   ManagedWorktreeRecord worktree,
                                                   List<String> changed, String commit,
                                                   String diffHash, String configHash,
                                                   WorkerTrace trace) throws Exception {
        transition(candidateId, "FROZEN", "VERIFYING");
        faults.hit(FaultPoint.BEFORE_VERIFICATION);
        Path workspace = Path.of(worktree.canonicalWorkspacePath());
        WorkerStateLocation.Location location = new WorkerStateLocation(managedRoot, workspace)
                .locate(run.workerRunId());
        VerificationResult result;
        try (StateStore workerState = StateStore.openWorker(
                location.stateDatabase(), location.traceDirectory())) {
            String verificationRun = "candidate-verification-" + candidateId;
            workerState.codingRuns.create(verificationRun, run.workerRunId(),
                    CodingRunStore.KIND_INITIAL, config.effectiveVerificationCommand(), 1, 0);
            trace.record("candidate_verification_started", run.workerRunId(),
                    "candidate_id=" + candidateId + "; config_hash=" + configHash);
            result = verificationExecutor.verify(workspace, workerState, verificationRun,
                    run.workerRunId(), configHash);
        }
        faults.hit(FaultPoint.AFTER_VERIFICATION);
        boolean integrity = verifyPostVerification(workspace, worktree, commit);
        String status = integrity ? result.status() : "ERROR";
        String resultHash = sha256(status + "\n" + nullSafe(result.exitCode()) + "\n"
                + safe(result.outputExcerpt()) + "\n" + safe(result.errorExcerpt()));
        String verificationHash = sha256(commit + "\n" + configHash + "\n"
                + config.effectiveVerificationTimeoutSeconds() + "\n"
                + config.effectiveMaxVerificationOutputBytes() + "\n" + status + "\n"
                + resultHash);
        boolean passed = integrity && "PASSED".equals(status);
        finishVerification(candidateId, configHash, status, verificationHash,
                passed ? "READY_FOR_DECISION" : "VERIFICATION_FAILED");
        if (!passed) abandonFrozen(worktree.managedWorktreeId(),
                integrity ? "Configured verification did not pass: " + status
                        : "VERIFICATION_MUTATED_WORKTREE");
        trace.record("candidate_verification_completed", run.workerRunId(),
                "candidate_id=" + candidateId + "; status=" + status
                        + "; verification_hash=" + verificationHash
                        + "; output_chars=" + length(result.outputExcerpt())
                        + "; error_chars=" + length(result.errorExcerpt()));
        return new WorkerCandidateResult(candidateId, run.workerRunId(),
                passed ? "READY_FOR_DECISION" : "VERIFICATION_FAILED", status,
                commit, diffHash, changed, verificationHash, false,
                passed ? "Configured verification passed"
                        : integrity ? "Configured verification did not pass"
                        : "Verification mutated candidate workspace");
    }

    private VerificationResult executeVerification(Path workspace, StateStore workerState,
                                                   String verificationRun, String workerRunId,
                                                   String configHash) throws Exception {
        VerificationService verification = new VerificationService(workspace,
                workerState.verifications, sanitizer,
                config.effectiveVerificationTimeoutSeconds(),
                config.effectiveMaxVerificationOutputBytes());
        if (!verification.configurationHash(config.effectiveVerificationCommand())
                .equals(configHash)) {
            throw new IllegalStateException("Verification configuration drift detected");
        }
        return verification.verify(UUID.randomUUID().toString(), verificationRun,
                workerRunId, 0, config.effectiveVerificationCommand());
    }

    private boolean verifyPostVerification(Path workspace, ManagedWorktreeRecord worktree,
                                           String commit) {
        try {
            requireHead(workspace, commit);
            String tree = output(runOk(workspace, Operation.TREE_SHA, commit));
            String indexTree = output(runOk(workspace, Operation.WRITE_TREE));
            if (!tree.equals(indexTree)) return false;
            return isBlank(runOk(workspace, Operation.STATUS).stdoutExcerpt())
                    && isBlank(runOk(workspace, Operation.UNMERGED).stdoutExcerpt());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private List<String> changedFiles(Path workspace, ManagedWorktreeRecord worktree)
            throws Exception {
        String raw = runOk(workspace, Operation.CACHED_NAMES).stdoutExcerpt();
        requireMachineOutputComplete(raw, "changed file list");
        List<String> paths = new ArrayList<>();
        if (raw != null) {
            for (String value : raw.split("\\u0000")) {
                if (value.isBlank()) continue;
                String normalized = value.replace('\\', '/');
                if (!paths.contains(normalized)) paths.add(normalized);
            }
        }
        paths.sort(Comparator.naturalOrder());
        if (paths.isEmpty()) throw new IllegalStateException("No staged candidate files");
        Path commonGit = worktrees.inspectRepositoryState(repositoryRoot).commonGitDirectory();
        WorkerStateLocation.Location location = new WorkerStateLocation(managedRoot, workspace)
                .locate(worktree.ownerWorkerRunId());
        WorkerWorkspacePathGuard guard = new WorkerWorkspacePathGuard(workspace, commonGit,
                List.of(managedRoot.resolve("state"), managedRoot.resolve("traces"),
                        location.stateDatabase(), location.traceDirectory()));
        for (String path : paths) {
            Path target = workspace.resolve(path).normalize();
            guard.resolve(path);
            if (Files.isSymbolicLink(target)) {
                throw new SecurityException("Candidate symlink is forbidden: " + path);
            }
        }
        return List.copyOf(paths);
    }

    private CandidateBudget validateBudget(Path workspace, List<String> changed) throws IOException {
        if (changed.size() > limits.maxChangedFiles()) {
            throw new IllegalStateException("Candidate changed-file limit exceeded");
        }
        long total = 0;
        for (String path : changed) {
            Path file = workspace.resolve(path);
            long size = Files.isRegularFile(file) ? Files.size(file) : 0;
            if (size > limits.maxSingleFileBytes()) {
                throw new IllegalStateException("Candidate single-file limit exceeded: " + path);
            }
            total = Math.addExact(total, size);
            if (total > limits.maxTotalBytes()) {
                throw new IllegalStateException("Candidate total-byte limit exceeded");
            }
        }
        return new CandidateBudget(total);
    }

    private void freezeCandidate(String candidateId, WorkerRunRecord run,
                                 ManagedWorktreeRecord worktree, String commit,
                                 String diffHash, List<String> changed,
                                 String configHash, CandidateBudget budget) throws Exception {
        coordinator.workerCandidates.freeze(candidateId, run.workerRunId(), run.worktreeId(),
                null, worktree.backendType(), worktree.baseCommitSha(), worktree.workerBranch(),
                commit, diffHash, MAPPER.writeValueAsString(changed), changed.size(),
                "NOT_RUN", null, configHash,
                "runtime frozen candidate; changed_files=" + changed.size()
                        + "; candidate_bytes=" + budget.totalBytes());
    }

    private WorkerCandidateResult reuse(WorkerCandidateRecord candidate, String configHash) {
        if (!Objects.equals(candidate.verificationConfigHash(), configHash)) {
            throw new IllegalStateException("Candidate verification configuration drift");
        }
        if (!List.of("READY_FOR_DECISION", "VERIFICATION_FAILED").contains(candidate.status())) {
            throw new IllegalStateException("Candidate has non-terminal freeze/verification residue: "
                    + candidate.status());
        }
        try {
            @SuppressWarnings("unchecked")
            List<String> changed = MAPPER.readValue(candidate.changedFilesJson(), List.class);
            return new WorkerCandidateResult(candidate.candidateId(), candidate.workerRunId(),
                    candidate.status(), candidate.verificationStatus(),
                    candidate.candidateCommitSha(), candidate.diffHash(), changed,
                    candidate.verificationHash(), true, "Existing terminal candidate reused");
        } catch (IOException e) {
            throw new IllegalStateException("Persisted candidate file list is invalid", e);
        }
    }

    private void requireFreezeable(ManagedWorktreeRecord worktree, WorkerRunRecord run) {
        if (!ManagedWorktreeStore.BACKEND_GIT.equals(worktree.backendType()))
            throw new IllegalStateException("UNSUPPORTED_BACKEND: DIRECTORY_EXPORT");
        if (!ManagedWorktreeStore.STATUS_DIRTY.equals(worktree.status())
                || !"DIRTY".equals(worktree.dirtyState()))
            throw new IllegalStateException("Managed worktree is not DIRTY/freezeable");
        if (!run.workerRunId().equals(worktree.ownerWorkerRunId()))
            throw new IllegalStateException("Worker/worktree ownership mismatch");
    }

    private void requireCompleted(WorkerRunRecord run) {
        if (!"COMPLETED".equals(run.status()))
            throw new IllegalStateException("Only COMPLETED WorkerRun can freeze a candidate");
    }

    private void verifyCommit(Path workspace, String commit, String base, String tree) {
        runOk(workspace, Operation.VERIFY_COMMIT, commit);
        String actualTree = output(runOk(workspace, Operation.TREE_SHA, commit));
        if (!tree.equals(actualTree)) throw new IllegalStateException("Candidate tree mismatch");
        String[] ancestry = output(runOk(workspace, Operation.COMMIT_PARENTS, commit)).split("\\s+");
        if (ancestry.length != 2 || !commit.equalsIgnoreCase(ancestry[0])
                || !base.equalsIgnoreCase(ancestry[1])) {
            throw new IllegalStateException("Candidate parent is not the frozen base");
        }
    }

    private void requireHead(Path workspace, String expected) {
        String actual = output(runOk(workspace, Operation.HEAD));
        if (!expected.equalsIgnoreCase(actual))
            throw new IllegalStateException("Worker branch HEAD drift");
    }

    private String verificationConfigHash(Path workspace) {
        try {
            VerificationService service = new VerificationService(workspace,
                    coordinator.verifications, sanitizer,
                    config.effectiveVerificationTimeoutSeconds(),
                    config.effectiveMaxVerificationOutputBytes());
            return service.configurationHash(config.effectiveVerificationCommand());
        } catch (IOException e) {
            throw new IllegalStateException("Verification configuration is invalid", e);
        }
    }

    private Result runOk(Path workspace, Operation operation, String... values) {
        Result result = git.execute(workspace, operation, values);
        if (result.status() != HardenedGitProcess.Status.SUCCEEDED) {
            throw new IllegalStateException("Runtime Git " + operation + " failed: "
                    + safe(result.stderrExcerpt()));
        }
        return result;
    }

    private Result runGit(Path workspace, Operation operation, String... values) {
        return runOk(workspace, operation, values);
    }

    private static String output(Result result) {
        String value = result.stdoutExcerpt();
        if (value == null || value.isBlank()) throw new IllegalStateException("Git output is missing");
        return value.strip();
    }

    private static void requireEmpty(Result result, String message) {
        if (!isBlank(result.stdoutExcerpt())) throw new IllegalStateException(message);
    }

    private static void requireMachineOutputComplete(String value, String label) {
        if (value != null && value.contains("...[stream truncated;")) {
            throw new IllegalStateException(label + " exceeded the bounded Git output");
        }
    }

    private static void rejectCheckoutControlChanges(String status) {
        if (status == null) return;
        String normalized = status.replace('\\', '/').toLowerCase();
        for (String field : normalized.split("\\u0000")) {
            if (field.endsWith(".gitattributes") || field.contains("/.gitattributes")
                    || field.endsWith(".gitmodules") || field.contains("/.gitmodules")) {
                throw new SecurityException(
                        "Candidate cannot modify checkout control files");
            }
        }
    }

    private void assertUsable(String id) {
        try { worktrees.assertUsable(id, repositoryRoot); }
        catch (Exception e) { throw new IllegalStateException("Managed worktree drift", e); }
    }

    private void assertFreezeable(String id) {
        try { worktrees.assertFreezeable(id, repositoryRoot); }
        catch (Exception e) { throw new IllegalStateException("Managed worktree drift", e); }
    }

    private WorkerRunRecord workerRun(String id) {
        try { return coordinator.workerRuns.findById(id).orElseThrow(
                () -> new IllegalArgumentException("WorkerRun not found: " + id)); }
        catch (SQLException e) { throw new IllegalStateException("WorkerRun persistence failed", e); }
    }

    private ManagedWorktreeRecord worktree(String id) {
        try { return coordinator.managedWorktrees.findById(id).orElseThrow(
                () -> new IllegalArgumentException("Managed worktree not found: " + id)); }
        catch (SQLException e) { throw new IllegalStateException("Worktree persistence failed", e); }
    }

    private WorkerCandidateRecord candidateForRun(String id) {
        try { return coordinator.workerCandidates.findByWorkerRunId(id).orElse(null); }
        catch (SQLException e) { throw new IllegalStateException("Candidate persistence failed", e); }
    }

    private void beginFreeze(String worktreeId, String runId, String token) {
        store(() -> coordinator.managedWorktrees.beginCandidateFreeze(worktreeId, runId, token));
    }
    private void markFrozen(String worktreeId, String runId, String token) {
        store(() -> coordinator.managedWorktrees.markCandidateFrozen(worktreeId, runId, token));
    }
    private void abandonFreeze(String worktreeId, String runId, String token, Throwable error) {
        try { coordinator.managedWorktrees.markFreezeAbandoned(
                worktreeId, runId, token, safe(error.getMessage())); }
        catch (SQLException ignored) { }
    }
    private void abandonCandidateFailure(String worktreeId, String runId,
                                         String token, Throwable error) {
        try {
            coordinator.managedWorktrees.markFreezeAbandoned(
                    worktreeId, runId, token, safe(error.getMessage()));
            return;
        } catch (SQLException ignored) {
            // The token transition no longer applies after CANDIDATE_FROZEN.
        }
        try {
            abandonFrozen(worktreeId,
                    "Candidate verification/persistence failed: " + safe(error.getMessage()));
        } catch (RuntimeException ignored) {
            // Preserve the original failure; reconciliation will expose persistence drift.
        }
    }

    private void abandonFrozen(String worktreeId, String error) {
        store(() -> coordinator.managedWorktrees.markAbandoned(worktreeId, safe(error)));
    }
    private void transition(String id, String from, String to) {
        store(() -> coordinator.workerCandidates.updateStatus(id, from, to));
    }
    private void finishVerification(String id, String configHash, String status,
                                    String hash, String next) {
        store(() -> coordinator.workerCandidates.finishVerification(
                id, configHash, status, hash, next));
    }
    private void store(SqlAction action) {
        try { action.run(); }
        catch (SQLException e) { throw new IllegalStateException("Coordinator persistence failed", e); }
    }

    private WorkerTrace trace(ManagedWorktreeRecord worktree, String runId) {
        try {
            return new WorkerTrace(new WorkerStateLocation(managedRoot,
                    Path.of(worktree.canonicalWorkspacePath())).locate(runId).traceDirectory());
        } catch (IOException e) {
            throw new IllegalStateException("Worker trace unavailable", e);
        }
    }

    private String safe(String value) {
        return sanitizer.sanitizeAndTruncate(value == null ? "" : value, 4096);
    }
    private static boolean isBlank(String value) { return value == null || value.isBlank(); }
    private static int length(String value) { return value == null ? 0 : value.length(); }
    private static String nullSafe(Object value) { return value == null ? "" : value.toString(); }
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }

    enum FaultPoint {
        AFTER_COMMIT_OBJECT,
        AFTER_BRANCH_UPDATE,
        AFTER_CANDIDATE_PERSIST,
        BEFORE_VERIFICATION,
        AFTER_VERIFICATION
    }

    @FunctionalInterface
    interface FaultInjector {
        FaultInjector NONE = point -> { };
        void hit(FaultPoint point);
    }

    record CandidateLimits(int maxChangedFiles, long maxSingleFileBytes,
                           long maxTotalBytes) {
        static final CandidateLimits PRODUCTION = new CandidateLimits(
                MAX_CHANGED_FILES, MAX_SINGLE_FILE_BYTES, MAX_TOTAL_CANDIDATE_BYTES);

        CandidateLimits {
            if (maxChangedFiles <= 0 || maxSingleFileBytes <= 0 || maxTotalBytes <= 0
                    || maxSingleFileBytes > maxTotalBytes) {
                throw new IllegalArgumentException(
                        "Candidate limits must be positive and coherent");
            }
        }
    }

    @FunctionalInterface
    interface VerificationExecutor {
        VerificationResult verify(Path workspace, StateStore workerState,
                                  String verificationRun, String workerRunId,
                                  String configHash) throws Exception;
    }

    @FunctionalInterface private interface SqlAction { void run() throws SQLException; }
    private record CandidateBudget(long totalBytes) { }
}
