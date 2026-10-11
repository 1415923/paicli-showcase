package com.paicli.worker;

import com.paicli.config.Config;
import com.paicli.state.*;
import com.paicli.state.WorkerIntegrationDecisionStore.WorkerIntegrationDecisionRecord;
import com.paicli.state.WorkerIntegrationRunStore.Record;
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

/** Explicit USER decision and fast-forward-only target integration boundary. */
public final class ManualIntegrationService {
    private final StateStore state;
    private final ManagedWorktreeService worktrees;
    private final Path repositoryRoot;
    private final HardenedGitProcess git;
    private final Config config;
    private final FaultInjector faults;

    public ManualIntegrationService(StateStore state, ManagedWorktreeService worktrees,
                                    Path repositoryRoot, Path managedRoot, Config config)
            throws IOException {
        this(state, worktrees, repositoryRoot, managedRoot, config, FaultInjector.NONE);
    }

    ManualIntegrationService(StateStore state, ManagedWorktreeService worktrees,
                             Path repositoryRoot, Path managedRoot, Config config, FaultInjector faults)
            throws IOException {
        this.state = Objects.requireNonNull(state);
        this.worktrees = Objects.requireNonNull(worktrees);
        this.repositoryRoot = repositoryRoot.toAbsolutePath().normalize();
        this.config = Objects.requireNonNull(config);
        this.faults = Objects.requireNonNull(faults);
        Path hooks = Files.createDirectories(managedRoot.toAbsolutePath().normalize()
                .resolve("runtime-empty-hooks"));
        this.git = new HardenedGitProcess(hooks, Duration.ofSeconds(120), 262_144);
    }

    public WorkerIntegrationDecisionRecord approve(String proposalId, String targetBranch,
                                                    String reason) {
        return decide(proposalId, "APPROVED", "USER", reason, targetBranch);
    }

    public WorkerIntegrationDecisionRecord reject(String proposalId, String decidedBy,
                                                   String reason) {
        return decide(proposalId, "REJECTED", decidedBy, reason, null);
    }

    public WorkerIntegrationDecisionRecord requestRework(String proposalId, String decidedBy,
                                                          String reason) {
        return decide(proposalId, "REQUEST_REWORK", decidedBy, reason, null);
    }

    public IntegrationResult integrateApprovedProposal(String proposalId,
                                                       String expectedTargetBranch) {
        var proposal = proposal(proposalId);
        var decision = decision(proposalId);
        requireApproved(proposal, decision, expectedTargetBranch);
        validateProposalArtifact(proposal);
        String runId = "integration-" + sha256(proposalId + "\n" + expectedTargetBranch)
                .substring(0, 24);
        Record existing = integrationRun(proposalId);
        String actualHead = output(runOk(Operation.HEAD));
        if (existing != null) {
            if (!existing.integrationRunId().equals(runId)
                    || !existing.expectedBaseSha().equals(proposal.baseCommitSha()))
                throw new IllegalStateException("Integration run identity drift");
            if ("INTEGRATED".equals(existing.status()))
                return verifyIntegrated(existing, proposal, expectedTargetBranch, true);
            if ("RUNNING".equals(existing.status())
                    && actualHead.equals(proposal.proposalCommitSha())) {
                assertTargetPostState(proposal, expectedTargetBranch);
                finish(runId, "INTEGRATED", actualHead, null);
                markIntegrated(proposalId);
                return new IntegrationResult(runId, proposalId, "INTEGRATED", actualHead,
                        true, "Recovered persisted integration after fast-forward");
            }
            if (!"RUNNING".equals(existing.status()))
                throw new IllegalStateException("Integration already terminal: " + existing.status());
        }

        var repository = inspect();
        if (!repository.repositoryIdentity().equals(proposal.repositoryIdentity()))
            throw new IllegalStateException("REPOSITORY_IDENTITY_MISMATCH");
        String branch = output(runOk(Operation.CURRENT_BRANCH));
        if (!branch.equals(expectedTargetBranch))
            throw new IllegalStateException("TARGET_BRANCH_MISMATCH");
        if (repository.dirty()) {
            if (existing == null) start(runId, proposal, decision, branch, repository.headSha());
            finish(runId, "TARGET_WORKSPACE_DIRTY", repository.headSha(),
                    "Target workspace has staged, unstaged or untracked changes");
            return new IntegrationResult(runId, proposalId, "TARGET_WORKSPACE_DIRTY",
                    repository.headSha(), false, "Target workspace dirty");
        }
        if (!repository.headSha().equals(proposal.baseCommitSha())) {
            if (existing == null) start(runId, proposal, decision, branch, repository.headSha());
            finish(runId, "BASE_DRIFT", repository.headSha(), "Target HEAD drift");
            return new IntegrationResult(runId, proposalId, "BASE_DRIFT",
                    repository.headSha(), false, "Target HEAD differs from Proposal base");
        }
        if (existing == null) start(runId, proposal, decision, branch, repository.headSha());
        try {
            faults.hit(FaultPoint.BEFORE_FAST_FORWARD);
            runOk(Operation.FAST_FORWARD_ONLY, proposal.proposalCommitSha());
            faults.hit(FaultPoint.AFTER_FAST_FORWARD);
            assertTargetPostState(proposal, expectedTargetBranch);
            finish(runId, "INTEGRATED", proposal.proposalCommitSha(), null);
            markIntegrated(proposalId);
            markCandidateWorktreesIntegrated(proposalId);
            return new IntegrationResult(runId, proposalId, "INTEGRATED",
                    proposal.proposalCommitSha(), false, "Fast-forward integration completed");
        } catch (RuntimeException e) {
            String head = safeHead();
            if (!proposal.proposalCommitSha().equals(head))
                finishBestEffort(runId, "FAILED", head, e.getMessage());
            throw e;
        }
    }

    private WorkerIntegrationDecisionRecord decide(String proposalId, String decision,
                                                    String decidedBy, String reason,
                                                    String targetBranch) {
        var proposal = proposal(proposalId);
        String id = "decision-" + sha256(proposalId + "\n" + decision + "\n"
                + decidedBy + "\n" + nullSafe(reason) + "\n" + nullSafe(targetBranch))
                .substring(0, 24);
        try {
            return state.workerIntegrationDecisions.save(id, proposalId, decision,
                    decidedBy, reason, targetBranch, proposal.baseCommitSha());
        } catch (SQLException e) {
            throw new IllegalStateException("Integration decision persistence failed", e);
        }
    }

    private void requireApproved(WorkerMergeProposalStore.Record proposal,
                                 WorkerIntegrationDecisionRecord decision,
                                 String targetBranch) {
        if (!Set.of("READY_FOR_APPROVAL", "INTEGRATED").contains(proposal.status())
                || !"CLEAN_MERGE".equals(proposal.mergeStatus())
                || !"PASSED".equals(proposal.verificationStatus()))
            throw new IllegalStateException("Proposal is not READY_FOR_APPROVAL");
        if (!"APPROVED".equals(decision.decision()) || !"USER".equals(decision.decidedBy()))
            throw new IllegalStateException("USER APPROVED decision required");
        if (!Objects.equals(targetBranch, decision.targetBranch()))
            throw new IllegalStateException("Approved target branch drift");
        if (!Objects.equals(proposal.baseCommitSha(), decision.targetHeadSha()))
            throw new IllegalStateException("Approved target base drift");
    }

    private void validateProposalArtifact(WorkerMergeProposalStore.Record proposal) {
        runOk(Operation.VERIFY_COMMIT, proposal.proposalCommitSha());
        String tree = output(runOk(Operation.TREE_SHA, proposal.proposalCommitSha()));
        if (!tree.equals(proposal.proposalTreeSha()))
            throw new IllegalStateException("Proposal tree drift");
        List<CandidateRef> refs = candidates(proposal.proposalId());
        String[] ancestry = output(runOk(Operation.COMMIT_PARENTS,
                proposal.proposalCommitSha())).split("\\s+");
        if (refs.size() == 1) {
            if (!refs.getFirst().commitSha().equals(proposal.proposalCommitSha()))
                throw new IllegalStateException("Single Candidate Proposal artifact drift");
        } else if (ancestry.length != 3
                || !ancestry[1].equals(refs.get(0).commitSha())
                || !ancestry[2].equals(refs.get(1).commitSha())) {
            throw new IllegalStateException("Proposal parent order drift");
        }
        String hash = sha256(proposal.baseCommitSha() + "\n" + tree + "\n"
                + refs.stream().map(r -> r.candidateId() + ":" + r.commitSha())
                .reduce((a, b) -> a + "\n" + b).orElse(""));
        if (!hash.equals(proposal.artifactHash()))
            throw new IllegalStateException("Proposal artifact hash drift");
        if (proposal.verificationConfigHash() == null || proposal.verificationHash() == null)
            throw new IllegalStateException("Proposal verification evidence missing");
        String currentConfigHash = sha256("workspace="
                + Path.of(proposal.canonicalWorkspacePath()).toAbsolutePath().normalize()
                + "\ntimeout_seconds=" + config.effectiveVerificationTimeoutSeconds()
                + "\nmax_output_bytes=" + config.effectiveMaxVerificationOutputBytes()
                + "\ncommand=" + config.effectiveVerificationCommand());
        if (!currentConfigHash.equals(proposal.verificationConfigHash()))
            throw new IllegalStateException("Proposal verification configuration drift");
        for (CandidateRef ref : refs) {
            String[] candidateParents = output(runOk(Operation.COMMIT_PARENTS,
                    ref.commitSha())).split("\\s+");
            if (candidateParents.length != 2
                    || !proposal.baseCommitSha().equals(candidateParents[1]))
                throw new IllegalStateException("Candidate ancestry/base drift");
        }
    }

    private void assertTargetPostState(WorkerMergeProposalStore.Record proposal,
                                       String targetBranch) {
        if (!output(runOk(Operation.HEAD)).equals(proposal.proposalCommitSha()))
            throw new IllegalStateException("Target HEAD is not Proposal commit");
        if (!output(runOk(Operation.CURRENT_BRANCH)).equals(targetBranch))
            throw new IllegalStateException("Target branch changed during integration");
        if (!output(runOk(Operation.TREE_SHA, "HEAD")).equals(proposal.proposalTreeSha()))
            throw new IllegalStateException("Target tree differs from Proposal tree");
        var repository = inspect();
        if (repository.dirty()) throw new IllegalStateException("Target workspace not clean after integration");
        if (!repository.repositoryIdentity().equals(proposal.repositoryIdentity()))
            throw new IllegalStateException("Repository identity changed during integration");
    }

    private IntegrationResult verifyIntegrated(Record run,
                                               WorkerMergeProposalStore.Record proposal,
                                               String targetBranch, boolean reused) {
        assertTargetPostState(proposal, targetBranch);
        if (!"INTEGRATED".equals(proposal.status())) markIntegrated(proposal.proposalId());
        markCandidateWorktreesIntegrated(proposal.proposalId());
        return new IntegrationResult(run.integrationRunId(), proposal.proposalId(),
                run.status(), proposal.proposalCommitSha(), reused,
                "Existing integrated result verified");
    }

    private ManagedWorktreeService.RepositoryState inspect() {
        try { return worktrees.inspectRepositoryState(repositoryRoot); }
        catch (Exception e) { throw new IllegalStateException("Target repository inspection failed", e); }
    }

    private WorkerMergeProposalStore.Record proposal(String id) {
        try { return state.workerMergeProposals.findById(id).orElseThrow(); }
        catch (SQLException e) { throw new IllegalStateException("Proposal persistence failed", e); }
    }
    private WorkerIntegrationDecisionRecord decision(String proposalId) {
        try { return state.workerIntegrationDecisions.findByProposal(proposalId).orElseThrow(
                () -> new IllegalStateException("Proposal has no IntegrationDecision")); }
        catch (SQLException e) { throw new IllegalStateException("Decision persistence failed", e); }
    }
    private Record integrationRun(String proposalId) {
        try { return state.workerIntegrationRuns.findByProposal(proposalId).orElse(null); }
        catch (SQLException e) { throw new IllegalStateException("Integration persistence failed", e); }
    }
    private List<CandidateRef> candidates(String proposalId) {
        try { return state.workerMergeProposals.candidates(proposalId); }
        catch (SQLException e) { throw new IllegalStateException("Proposal Candidate persistence failed", e); }
    }

    private void start(String id, WorkerMergeProposalStore.Record proposal,
                       WorkerIntegrationDecisionRecord decision, String branch, String head) {
        try { state.workerIntegrationRuns.start(id, proposal.proposalId(),
                decision.integrationDecisionId(), branch, proposal.baseCommitSha(), head); }
        catch (SQLException e) { throw new IllegalStateException("Integration start persistence failed", e); }
    }
    private void finish(String id, String status, String head, String error) {
        try { state.workerIntegrationRuns.finish(id, status, head, error); }
        catch (SQLException e) { throw new IllegalStateException("Integration result persistence failed", e); }
    }
    private void finishBestEffort(String id, String status, String head, String error) {
        try { state.workerIntegrationRuns.finish(id, status, head, error); }
        catch (Exception ignored) { }
    }
    private void markIntegrated(String proposalId) {
        try { state.workerMergeProposals.markIntegrated(proposalId); }
        catch (SQLException e) { throw new IllegalStateException("Proposal integration persistence failed", e); }
    }
    private void markCandidateWorktreesIntegrated(String proposalId) {
        for (CandidateRef ref : candidates(proposalId)) {
            try {
                var candidate = state.workerCandidates.findById(ref.candidateId()).orElseThrow();
                var worktree = state.managedWorktrees.findById(candidate.worktreeId()).orElseThrow();
                if (!"INTEGRATED".equals(worktree.status()))
                    state.managedWorktrees.markIntegrated(candidate.worktreeId());
                if (!"INTEGRATED".equals(candidate.status()))
                    state.workerCandidates.updateStatus(candidate.candidateId(),
                            "READY_FOR_DECISION", "INTEGRATED");
            } catch (SQLException e) {
                throw new IllegalStateException("Integrated resource state persistence failed", e);
            }
        }
    }

    private HardenedGitProcess.Result runOk(Operation operation, String... values) {
        var result = git.execute(repositoryRoot, operation, values);
        if (!result.succeeded())
            throw new IllegalStateException("Runtime Git " + operation + " failed: "
                    + nullSafe(result.stderrExcerpt()));
        return result;
    }
    private static String output(HardenedGitProcess.Result result) {
        if (result.stdoutExcerpt() == null || result.stdoutExcerpt().isBlank())
            throw new IllegalStateException("Git output missing");
        return result.stdoutExcerpt().strip();
    }
    private String safeHead() {
        try { return output(runOk(Operation.HEAD)); } catch (Exception e) { return null; }
    }
    private static String nullSafe(Object value) { return value == null ? "" : value.toString(); }
    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }

    enum FaultPoint { BEFORE_FAST_FORWARD, AFTER_FAST_FORWARD }
    @FunctionalInterface interface FaultInjector {
        FaultInjector NONE = point -> { };
        void hit(FaultPoint point);
    }

    public record IntegrationResult(String integrationRunId, String proposalId,
            String status, String actualHead, boolean reused, String summary) { }
}
