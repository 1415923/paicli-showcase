package com.paicli.worker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.Config;
import com.paicli.runtime.PersistenceSanitizer;
import com.paicli.runtime.VerificationResult;
import com.paicli.runtime.VerificationService;
import com.paicli.state.*;
import com.paicli.state.ManagedWorktreeStore.ManagedWorktreeRecord;
import com.paicli.state.WorkerCandidateStore.WorkerCandidateRecord;
import com.paicli.state.WorkerMergeProposalStore.CandidateRef;
import com.paicli.worktree.HardenedGitProcess;
import com.paicli.worktree.HardenedGitProcess.Operation;
import com.paicli.worktree.ManagedWorktreeService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/** Builds and verifies immutable one/two-candidate merge proposals outside the target workspace. */
public final class MergeProposalService {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern FORBIDDEN_VERIFICATION_GIT = Pattern.compile(
            "(?i)\\bgit(?:\\.exe)?\\b[^;&|\\r\n]{0,512}\\b(?:worktree|branch|checkout|switch|"
                    + "commit|merge|rebase|cherry-pick|reset|clean|tag|push)\\b" );
    private final StateStore state;
    private final ManagedWorktreeService worktrees;
    private final Path repositoryRoot;
    private final Path managedRoot;
    private final Config config;
    private final HardenedGitProcess git;
    private final PersistenceSanitizer sanitizer = new PersistenceSanitizer();
    private final FaultInjector faults;

    public MergeProposalService(StateStore state, ManagedWorktreeService worktrees,
                                Path repositoryRoot, Path managedRoot, Config config)
            throws IOException {
        this(state, worktrees, repositoryRoot, managedRoot, config, FaultInjector.NONE);
    }

    MergeProposalService(StateStore state, ManagedWorktreeService worktrees,
                         Path repositoryRoot, Path managedRoot, Config config,
                         FaultInjector faults) throws IOException {
        this.state = Objects.requireNonNull(state);
        this.worktrees = Objects.requireNonNull(worktrees);
        this.repositoryRoot = repositoryRoot.toAbsolutePath().normalize();
        this.managedRoot = managedRoot.toAbsolutePath().normalize();
        this.config = Objects.requireNonNull(config);
        this.faults = Objects.requireNonNull(faults);
        if (FORBIDDEN_VERIFICATION_GIT.matcher(config.effectiveVerificationCommand()).find())
            throw new IllegalArgumentException("Configured verification cannot contain Git control operations");
        Path hooks = Files.createDirectories(this.managedRoot.resolve("runtime-empty-hooks"));
        this.git = new HardenedGitProcess(hooks, Duration.ofSeconds(120), 262_144);
    }

    public ProposalResult createAndVerify(List<String> requestedCandidateIds) {
        List<WorkerCandidateRecord> candidates = loadAndSort(requestedCandidateIds);
        String repositoryIdentity = validateCandidates(candidates);
        String base = candidates.getFirst().baseCommitSha();
        String idMaterial = candidates.stream()
                .map(c -> c.candidateId() + ":" + c.candidateCommitSha())
                .reduce((a, b) -> a + "\n" + b).orElseThrow();
        String runtimeKey = sha256(repositoryIdentity + "\n" + base + "\n" + idMaterial);
        String proposalId = "proposal-" + runtimeKey.substring(0, 24);
        String branch = "paicli/proposal/" + runtimeKey.substring(0, 24);
        Path workspace = proposalWorkspace(runtimeKey.substring(0, 24));
        String configHash = configurationHash(workspace);
        String proposalKey = sha256(runtimeKey + "\n" + configHash);
        WorkerMergeProposalStore.Record existing = findByKey(proposalKey);
        if (existing != null) return reuse(existing, candidates, configHash);
        String overlap = overlap(candidates);
        begin(proposalId, proposalKey, repositoryIdentity, base, branch, workspace,
                overlap, candidates);
        try {
            ensureNoCollision(branch, workspace);
            runOk(repositoryRoot, Operation.WORKTREE_ADD, branch,
                    workspace.toString(), candidates.getFirst().candidateCommitSha());
            faults.hit(FaultPoint.AFTER_WORKTREE_CREATE);
            String commit;
            String tree;
            if (candidates.size() == 1) {
                commit = candidates.getFirst().candidateCommitSha();
                tree = output(runOk(workspace, Operation.TREE_SHA, commit));
            } else {
                var merge = git.execute(workspace, Operation.MERGE_NO_COMMIT,
                        candidates.get(1).candidateCommitSha());
                if (!merge.succeeded()) {
                    List<String> conflicts = nulList(runOk(workspace, Operation.UNMERGED).stdoutExcerpt());
                    if (!conflicts.isEmpty()) {
                        markConflicted(proposalId, conflicts);
                        return result(find(proposalId), false);
                    }
                    throw new IllegalStateException("Proposal merge failed: " + safe(merge.stderrExcerpt()));
                }
                tree = output(runOk(workspace, Operation.WRITE_TREE));
                String message = "PaiCLI merge proposal " + proposalId;
                commit = output(runOk(workspace, Operation.COMMIT_TREE_TWO_PARENTS,
                        tree, candidates.getFirst().candidateCommitSha(),
                        candidates.get(1).candidateCommitSha(), message));
                verifyParents(workspace, commit, candidates);
                runOk(workspace, Operation.UPDATE_REF, branch, commit,
                        candidates.getFirst().candidateCommitSha());
            }
            requireHead(workspace, commit);
            String artifactHash = artifactHash(base, candidates, tree);
            faults.hit(FaultPoint.AFTER_BRANCH_UPDATE_BEFORE_PROPOSAL_PERSIST);
            markMerged(proposalId, commit, tree, artifactHash);
            faults.hit(FaultPoint.AFTER_PROPOSAL_PERSIST);
            return verify(proposalId, workspace, commit, tree, configHash);
        } catch (RuntimeException e) {
            markInvalidBestEffort(proposalId, "INTERNAL_ERROR", e);
            throw e;
        }
    }

    private ProposalResult verify(String proposalId, Path workspace, String commit,
                                  String tree, String configHash) {
        beginVerification(proposalId, configHash);
        faults.hit(FaultPoint.BEFORE_VERIFICATION);
        VerificationResult verification;
        Path runtime = managedRoot.resolve("proposals").resolve("runtime").resolve(proposalId);
        try {
            Files.createDirectories(runtime);
            try (StateStore proposalState = StateStore.openWorker(
                    runtime.resolve("paicli.db"), runtime.resolve("traces"))) {
                proposalState.tasks.createTask(proposalId, "merge proposal verification",
                        "runtime", null, Config.MODE_DENY, 1);
                proposalState.tasks.markRunning(proposalId);
                String run = "proposal-verification-" + proposalId;
                proposalState.codingRuns.create(run, proposalId,
                        CodingRunStore.KIND_INITIAL, config.effectiveVerificationCommand(), 1, 0);
                VerificationService service = new VerificationService(workspace,
                        proposalState.verifications, sanitizer,
                        config.effectiveVerificationTimeoutSeconds(),
                        config.effectiveMaxVerificationOutputBytes());
                if (!service.configurationHash(config.effectiveVerificationCommand()).equals(configHash))
                    throw new IllegalStateException("Proposal verification configuration drift");
                verification = service.verify(UUID.randomUUID().toString(), run,
                        proposalId, 0, config.effectiveVerificationCommand());
            }
        } catch (Exception e) {
            verification = new VerificationResult("proposal-error", proposalId, proposalId,
                    0, null, "ERROR", null, 0, null, safe(e.getMessage()),null,null);
        }
        faults.hit(FaultPoint.AFTER_VERIFICATION);
        boolean intact = integrity(workspace, commit, tree);
        String status = intact ? verification.status() : "ERROR";
        String resultHash = sha256(status + "\n" + nullSafe(verification.exitCode()) + "\n"
                + safe(verification.outputExcerpt()) + "\n" + safe(verification.errorExcerpt()));
        String verificationHash = sha256(commit + "\n" + configHash + "\n"
                + config.effectiveVerificationTimeoutSeconds() + "\n"
                + config.effectiveMaxVerificationOutputBytes() + "\n" + resultHash);
        finishVerification(proposalId, configHash, status, verificationHash,
                intact ? verification.errorExcerpt() : "VERIFICATION_MUTATED_PROPOSAL");
        return result(find(proposalId), false);
    }

    private List<WorkerCandidateRecord> loadAndSort(List<String> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 2 || new HashSet<>(ids).size() != ids.size())
            throw new IllegalArgumentException("Proposal requires one or two distinct candidates");
        return ids.stream().map(this::candidate)
                .sorted(Comparator.comparing(WorkerCandidateRecord::candidateId)).toList();
    }

    private String validateCandidates(List<WorkerCandidateRecord> candidates) {
        String inspectedIdentity;
        try { inspectedIdentity = worktrees.inspectRepositoryState(repositoryRoot).repositoryIdentity(); }
        catch (Exception e) { throw new IllegalStateException("Repository inspection failed", e); }
        String repo = null;
        String base = null;
        for (WorkerCandidateRecord candidate : candidates) {
            if (!"READY_FOR_DECISION".equals(candidate.status())
                    || !"PASSED".equals(candidate.verificationStatus())
                    || !"GIT_LINKED_WORKTREE".equals(candidate.backendType())
                    || candidate.candidateCommitSha() == null
                    || candidate.verificationConfigHash() == null
                    || candidate.verificationHash() == null)
                throw new IllegalStateException("Candidate is not READY/PASSED Git artifact: "
                        + candidate.candidateId());
            ManagedWorktreeRecord worktree = worktree(candidate.worktreeId());
            if (repo == null) repo = worktree.repositoryIdentity();
            if (base == null) base = candidate.baseCommitSha();
            if (!repo.equals(worktree.repositoryIdentity()))
                throw new IllegalStateException("Candidate repository identity mismatch");
            if (!base.equals(candidate.baseCommitSha())
                    || !base.equals(worktree.baseCommitSha()))
                throw new IllegalStateException("Candidate base mismatch");
            Path workspace = Path.of(worktree.canonicalWorkspacePath());
            runOk(workspace, Operation.VERIFY_COMMIT, candidate.candidateCommitSha());
            requireHead(workspace, candidate.candidateCommitSha());
            String ref = output(runOk(workspace, Operation.REF_SHA, candidate.workerBranch()));
            if (!candidate.candidateCommitSha().equals(ref))
                throw new IllegalStateException("Candidate branch drift");
            String tree = output(runOk(workspace, Operation.TREE_SHA,
                    candidate.candidateCommitSha()));
            String[] parents = output(runOk(workspace, Operation.COMMIT_PARENTS,
                    candidate.candidateCommitSha())).split("\\s+");
            if (parents.length != 2 || !candidate.baseCommitSha().equals(parents[1]))
                throw new IllegalStateException("Candidate parent/base mismatch");
            List<String> changed = changedFiles(candidate);
            String expected = sha256(candidate.baseCommitSha() + "\n" + tree + "\n"
                    + String.join("\n", changed));
            if (!expected.equals(candidate.diffHash()))
                throw new IllegalStateException("Candidate diff metadata drift");
            if (!blank(runOk(workspace, Operation.STATUS).stdoutExcerpt())
                    || !blank(runOk(workspace, Operation.UNMERGED).stdoutExcerpt()))
                throw new IllegalStateException("Frozen Candidate workspace drift");
        }
        if (!Objects.equals(repo, inspectedIdentity))
            throw new IllegalStateException("Candidate repository identity drift");
        return repo;
    }

    private void ensureNoCollision(String branch, Path workspace) {
        if (Files.exists(workspace, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalStateException("Proposal workspace collision");
        var ref = git.execute(repositoryRoot, Operation.REF_EXISTS, branch);
        if (ref.succeeded()) throw new IllegalStateException("Proposal branch collision");
        if (ref.status() != HardenedGitProcess.Status.NON_ZERO)
            throw new IllegalStateException("Proposal branch inspection failed");
    }

    private Path proposalWorkspace(String safeId) {
        try {
            Path parent = Files.createDirectories(managedRoot.resolve("proposals")
                    .resolve("workspaces")).toRealPath();
            Path target = parent.resolve(safeId).toAbsolutePath().normalize();
            if (!target.startsWith(parent)) throw new SecurityException("Proposal path escape");
            return target;
        } catch (IOException e) {
            throw new IllegalStateException("Proposal workspace unavailable", e);
        }
    }

    private String overlap(List<WorkerCandidateRecord> candidates) {
        if (candidates.size() == 1) return "DISJOINT";
        Set<String> first = new HashSet<>(changedFiles(candidates.get(0)));
        return changedFiles(candidates.get(1)).stream().anyMatch(first::contains)
                ? "OVERLAP" : "DISJOINT";
    }

    private List<String> changedFiles(WorkerCandidateRecord candidate) {
        try {
            List<String> paths = MAPPER.readValue(candidate.changedFilesJson(),
                    new TypeReference<List<String>>() { });
            return paths.stream().sorted().toList();
        } catch (IOException e) {
            throw new IllegalStateException("Candidate changed-files metadata invalid", e);
        }
    }

    private void verifyParents(Path workspace, String commit,
                               List<WorkerCandidateRecord> candidates) {
        String[] parts = output(runOk(workspace, Operation.COMMIT_PARENTS, commit)).split("\\s+");
        if (parts.length != 3 || !parts[1].equals(candidates.get(0).candidateCommitSha())
                || !parts[2].equals(candidates.get(1).candidateCommitSha()))
            throw new IllegalStateException("Proposal ancestry mismatch");
    }

    private boolean integrity(Path workspace, String commit, String tree) {
        try {
            requireHead(workspace, commit);
            return tree.equals(output(runOk(workspace, Operation.TREE_SHA, commit)))
                    && tree.equals(output(runOk(workspace, Operation.WRITE_TREE)))
                    && blank(runOk(workspace, Operation.STATUS).stdoutExcerpt())
                    && blank(runOk(workspace, Operation.UNMERGED).stdoutExcerpt());
        } catch (RuntimeException e) { return false; }
    }

    private ProposalResult reuse(WorkerMergeProposalStore.Record proposal,
                                 List<WorkerCandidateRecord> candidates, String configHash) {
        List<CandidateRef> refs = proposalCandidates(proposal.proposalId());
        List<String> expected = candidates.stream().map(WorkerCandidateRecord::candidateId).toList();
        if (!refs.stream().map(CandidateRef::candidateId).toList().equals(expected))
            throw new IllegalStateException("Proposal candidate drift");
        if (proposal.verificationConfigHash() != null
                && !Objects.equals(proposal.verificationConfigHash(), configHash))
            throw new IllegalStateException("Proposal verification configuration drift");
        if (!Set.of("READY_FOR_APPROVAL", "CONFLICTED", "VERIFICATION_FAILED", "INVALID")
                .contains(proposal.status()))
            throw new IllegalStateException("Proposal has non-terminal residue: " + proposal.status());
        return result(proposal, true);
    }

    private ProposalResult result(WorkerMergeProposalStore.Record p, boolean reused) {
        return new ProposalResult(p.proposalId(), p.status(), p.overlapStatus(),
                p.mergeStatus(), p.verificationStatus(), p.proposalCommitSha(),
                p.proposalTreeSha(), p.artifactHash(), proposalCandidates(p.proposalId()),
                reused, p.boundedSummary());
    }

    private String configurationHash(Path workspace) {
        String material = "workspace=" + workspace.toAbsolutePath().normalize()
                + "\ntimeout_seconds=" + config.effectiveVerificationTimeoutSeconds()
                + "\nmax_output_bytes=" + config.effectiveMaxVerificationOutputBytes()
                + "\ncommand=" + config.effectiveVerificationCommand();
        return sha256(material);
    }

    private String artifactHash(String base, List<WorkerCandidateRecord> candidates, String tree) {
        return sha256(base + "\n" + tree + "\n" + candidates.stream()
                .map(c -> c.candidateId() + ":" + c.candidateCommitSha())
                .reduce((a, b) -> a + "\n" + b).orElse(""));
    }

    private void requireHead(Path workspace, String expected) {
        if (!expected.equals(output(runOk(workspace, Operation.HEAD))))
            throw new IllegalStateException("Proposal/candidate HEAD drift");
    }

    private HardenedGitProcess.Result runOk(Path cwd, Operation op, String... values) {
        var result = git.execute(cwd, op, values);
        if (!result.succeeded())
            throw new IllegalStateException("Runtime Git " + op + " failed: " + safe(result.stderrExcerpt()));
        return result;
    }

    private static String output(HardenedGitProcess.Result result) {
        if (result.stdoutExcerpt() == null || result.stdoutExcerpt().isBlank())
            throw new IllegalStateException("Git output missing");
        return result.stdoutExcerpt().strip();
    }

    private static List<String> nulList(String value) {
        if (value == null || value.isBlank()) return List.of();
        return Arrays.stream(value.split("\u0000")).filter(s -> !s.isBlank())
                .map(s -> s.replace('\\', '/')).distinct().sorted().toList();
    }

    private WorkerCandidateRecord candidate(String id) {
        try { return state.workerCandidates.findById(id).orElseThrow(); }
        catch (SQLException e) { throw new IllegalStateException("Candidate persistence failed", e); }
    }
    private ManagedWorktreeRecord worktree(String id) {
        try { return state.managedWorktrees.findById(id).orElseThrow(); }
        catch (SQLException e) { throw new IllegalStateException("Worktree persistence failed", e); }
    }
    private WorkerMergeProposalStore.Record find(String id) {
        try { return state.workerMergeProposals.findById(id).orElseThrow(); }
        catch (SQLException e) { throw new IllegalStateException("Proposal persistence failed", e); }
    }
    private WorkerMergeProposalStore.Record findByKey(String key) {
        try { return state.workerMergeProposals.findByKey(key).orElse(null); }
        catch (SQLException e) { throw new IllegalStateException("Proposal persistence failed", e); }
    }
    private List<CandidateRef> proposalCandidates(String id) {
        try { return state.workerMergeProposals.candidates(id); }
        catch (SQLException e) { throw new IllegalStateException("Proposal persistence failed", e); }
    }
    private void begin(String id, String key, String repo, String base, String branch,
                       Path workspace, String overlap, List<WorkerCandidateRecord> candidates) {
        try {
            state.workerMergeProposals.begin(id, key, repo, base, branch, workspace.toString(),
                    overlap, "Merge proposal for " + candidates.size() + " candidate(s)",
                    candidates.stream().map(c -> new CandidateRef(
                            c.candidateId(), c.candidateCommitSha())).toList());
        } catch (SQLException e) {
            WorkerMergeProposalStore.Record existing = findByKey(key);
            if (existing != null) return;
            throw new IllegalStateException("Proposal ownership persistence failed", e);
        }
    }
    private void markMerged(String id, String commit, String tree, String hash) {
        sql(() -> state.workerMergeProposals.markMerged(id, commit, tree, hash));
    }
    private void markConflicted(String id, List<String> conflicts) {
        try {
            state.workerMergeProposals.markConflicted(id, MAPPER.writeValueAsString(conflicts),
                    "Text conflict in " + conflicts.size() + " file(s)");
        } catch (Exception e) { throw new IllegalStateException("Conflict persistence failed", e); }
    }
    private void beginVerification(String id, String hash) {
        sql(() -> state.workerMergeProposals.beginVerification(id, hash));
    }
    private void finishVerification(String id, String configHash, String status,
                                    String verificationHash, String error) {
        sql(() -> state.workerMergeProposals.finishVerification(
                id, configHash, status, verificationHash, error));
    }
    private void markInvalidBestEffort(String id, String category, Throwable e) {
        try { state.workerMergeProposals.markInvalid(id, category, safe(e.getMessage())); }
        catch (Exception ignored) { }
    }
    private void sql(Sql action) {
        try { action.run(); }
        catch (SQLException e) { throw new IllegalStateException("Coordinator persistence failed", e); }
    }

    private String safe(String value) {
        return sanitizer.sanitizeAndTruncate(value == null ? "" : value, 4096);
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String nullSafe(Object value) { return value == null ? "" : value.toString(); }
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }

    enum FaultPoint { AFTER_WORKTREE_CREATE, AFTER_BRANCH_UPDATE_BEFORE_PROPOSAL_PERSIST,
        AFTER_PROPOSAL_PERSIST,
        BEFORE_VERIFICATION, AFTER_VERIFICATION }
    @FunctionalInterface interface FaultInjector {
        FaultInjector NONE = point -> { };
        void hit(FaultPoint point);
    }
    @FunctionalInterface private interface Sql { void run() throws SQLException; }

    public record ProposalResult(String proposalId, String status, String overlapStatus,
            String mergeStatus, String verificationStatus, String proposalCommitSha,
            String proposalTreeSha, String artifactHash, List<CandidateRef> candidates,
            boolean reused, String summary) { }
}
