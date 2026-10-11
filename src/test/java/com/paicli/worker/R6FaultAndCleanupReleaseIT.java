package com.paicli.worker;

import com.paicli.config.Config;
import com.paicli.state.StateStore;
import com.paicli.worktree.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class R6FaultAndCleanupReleaseIT {
    @TempDir Path tempDir;
    private Path repository;
    private Path managedRoot;
    private StateStore state;
    private ManagedWorktreeService worktrees;

    @BeforeEach void setUp() throws Exception {
        repository = GitTestSupport.initialize(tempDir.resolve("main"));
        managedRoot = tempDir.resolve("managed");
        state = new StateStore(tempDir.resolve("coordinator"));
        worktrees = new ManagedWorktreeService(state.managedWorktrees);
    }

    @AfterEach void tearDown() throws Exception {
        GitTestSupport.cleanup(repository, tempDir);
        if (state != null) state.close();
    }

    @Test void proposalPersistenceFaultRetainsOneOwnedArtifactAndRetryDoesNotDuplicate()
            throws Exception {
        String candidate = manualCandidate("fault-proposal", "fault.txt", "value\n");
        var failing = new MergeProposalService(state, worktrees, repository, managedRoot,
                config(), point -> {
                    if (point == MergeProposalService.FaultPoint.AFTER_BRANCH_UPDATE_BEFORE_PROPOSAL_PERSIST)
                        throw new IllegalStateException("injected proposal persistence window");
                });

        assertThrows(IllegalStateException.class,
                () -> failing.createAndVerify(List.of(candidate)));
        assertEquals(1, state.workerMergeProposals.findAll().size());
        var residue = state.workerMergeProposals.findAll().getFirst();
        assertEquals("INVALID", residue.status());
        assertNull(residue.proposalCommitSha());
        String retainedCommit = GitTestSupport.gitText(repository, "rev-parse",
                residue.proposalBranch());
        assertNotEquals(residue.baseCommitSha(), retainedCommit);

        var retried = new MergeProposalService(state, worktrees, repository, managedRoot,
                config()).createAndVerify(List.of(candidate));
        assertTrue(retried.reused());
        assertNull(retried.proposalCommitSha());
        assertEquals(1, state.workerMergeProposals.findAll().size());
    }

    @Test void concurrentProposalCreationHasOneDatabaseOwnerAndOneProposalRef()
            throws Exception {
        String candidate = manualCandidate("concurrent-proposal", "one.txt", "one\n");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var firstService = new MergeProposalService(state, worktrees, repository, managedRoot,
                config(), point -> {
                    if (point == MergeProposalService.FaultPoint.AFTER_WORKTREE_CREATE) {
                        entered.countDown();
                        try { release.await(10, TimeUnit.SECONDS); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }
                });
        var secondService = new MergeProposalService(state, worktrees, repository, managedRoot,
                config());
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> first = executor.submit(() -> firstService.createAndVerify(List.of(candidate)));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            Future<?> second = executor.submit(() -> {
                assertThrows(IllegalStateException.class,
                        () -> secondService.createAndVerify(List.of(candidate)));
            });
            second.get(20, TimeUnit.SECONDS);
            release.countDown();
            first.get(60, TimeUnit.SECONDS);
        }
        assertEquals(1, state.workerMergeProposals.findAll().size());
        var proposal = state.workerMergeProposals.findAll().getFirst();
        assertEquals(proposal.proposalCommitSha(),
                GitTestSupport.gitText(repository, "rev-parse", proposal.proposalBranch()));
    }

    @Test void fastForwardPersistenceWindowRecoversWithoutRepeatingGit() throws Exception {
        String candidate = manualCandidate("integration-fault", "integrated.txt", "done\n");
        var proposal = new MergeProposalService(state, worktrees, repository, managedRoot,
                config()).createAndVerify(List.of(candidate));
        String target = GitTestSupport.gitText(repository, "branch", "--show-current");
        var failing = new ManualIntegrationService(state, worktrees, repository, managedRoot, config(),
                point -> {
                    if (point == ManualIntegrationService.FaultPoint.AFTER_FAST_FORWARD)
                        throw new IllegalStateException("injected post-fast-forward DB window");
                });
        failing.approve(proposal.proposalId(), target, null);

        assertThrows(IllegalStateException.class,
                () -> failing.integrateApprovedProposal(proposal.proposalId(), target));
        assertEquals(proposal.proposalCommitSha(),
                GitTestSupport.gitText(repository, "rev-parse", "HEAD"));
        assertEquals("RUNNING", state.workerIntegrationRuns
                .findByProposal(proposal.proposalId()).orElseThrow().status());

        var recovered = new ManualIntegrationService(state, worktrees, repository, managedRoot, config())
                .integrateApprovedProposal(proposal.proposalId(), target);
        assertEquals("INTEGRATED", recovered.status());
        assertTrue(recovered.reused());
        assertEquals("INTEGRATED", state.workerIntegrationRuns
                .findByProposal(proposal.proposalId()).orElseThrow().status());
    }

    @Test void cleanupPartialFailureIsAuditedAndResumeOnlyRetriesRetainedState()
            throws Exception {
        String candidate = manualCandidate("cleanup", "cleanup.txt", "done\n");
        var proposal = new MergeProposalService(state, worktrees, repository, managedRoot,
                config()).createAndVerify(List.of(candidate));
        String target = GitTestSupport.gitText(repository, "branch", "--show-current");
        var integration = new ManualIntegrationService(state, worktrees, repository, managedRoot, config());
        integration.approve(proposal.proposalId(), target, null);
        integration.integrateApprovedProposal(proposal.proposalId(), target);
        String worktreeId = state.workerCandidates.findById(candidate).orElseThrow().worktreeId();

        var failing = new WorkerCleanupService(state, repository, managedRoot,
                root -> { throw new IOException("injected component cleanup failure"); });
        var partial = failing.cleanupIntegratedWorktree(worktreeId, "USER");
        assertEquals("PARTIAL", partial.status());
        assertEquals("SUCCEEDED", partial.workspaceStatus());
        assertFalse(Files.exists(Path.of(state.managedWorktrees.findById(worktreeId)
                .orElseThrow().canonicalWorkspacePath())));

        var resumed = new WorkerCleanupService(state, repository, managedRoot)
                .resumeCleanup(partial.cleanupRunId());
        assertEquals("COMPLETED", resumed.status());
        assertEquals("CLEANED", state.managedWorktrees.findById(worktreeId)
                .orElseThrow().status());
        assertEquals(GitTestSupport.gitText(repository, "rev-parse", "HEAD"),
                proposal.proposalCommitSha());
    }

    @Test void approvalDoesNotBypassHeadDrift() throws Exception {
        String candidate = manualCandidate("head-drift", "candidate.txt", "candidate\n");
        var proposal = new MergeProposalService(state, worktrees, repository, managedRoot,
                config()).createAndVerify(List.of(candidate));
        String target = GitTestSupport.gitText(repository, "branch", "--show-current");
        var integration = new ManualIntegrationService(state, worktrees, repository,
                managedRoot, config());
        integration.approve(proposal.proposalId(), target, null);
        Files.writeString(repository.resolve("later.txt"), "later\n");
        GitTestSupport.git(repository, "add", "later.txt");
        GitTestSupport.git(repository, "commit", "-m", "head drift");
        String driftedHead = GitTestSupport.gitText(repository, "rev-parse", "HEAD");

        var result = integration.integrateApprovedProposal(proposal.proposalId(), target);
        assertEquals("BASE_DRIFT", result.status());
        assertEquals(driftedHead, GitTestSupport.gitText(repository, "rev-parse", "HEAD"));
        assertFalse(Files.exists(repository.resolve("candidate.txt")));
    }

    @Test void integratedResourcesCleanupIsExplicitAndOwnershipChecked() throws Exception {
        String candidate = manualCandidate("cleanup-all", "cleanup-all.txt", "done\n");
        var proposal = new MergeProposalService(state, worktrees, repository, managedRoot,
                config()).createAndVerify(List.of(candidate));
        String target = GitTestSupport.gitText(repository, "branch", "--show-current");
        var integration = new ManualIntegrationService(state, worktrees, repository,
                managedRoot, config());
        integration.approve(proposal.proposalId(), target, null);
        integration.integrateApprovedProposal(proposal.proposalId(), target);

        var results = new WorkerCleanupService(state, repository, managedRoot)
                .cleanupIntegratedResources(proposal.proposalId());
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(result -> "COMPLETED".equals(result.status())));
        assertEquals("CLEANED", state.workerMergeProposals.findById(proposal.proposalId())
                .orElseThrow().status());
        assertEquals("done\n", Files.readString(repository.resolve("cleanup-all.txt")));
    }
    private String manualCandidate(String runtimeId, String path, String content)
            throws Exception {
        worktrees.createLinkedWorktree(ManagedWorktreeService.CreateRequest.linked(
                repository, managedRoot, runtimeId));
        var worktree = state.managedWorktrees.findById(runtimeId).orElseThrow();
        Path workspace = Path.of(worktree.canonicalWorkspacePath());
        String runId = "run-" + runtimeId;
        state.workerRuns.create(runId, null, null, runtimeId, runtimeId, 1,
                "fixture", "a".repeat(64));
        state.workerRuns.markPreparing(runId);
        state.workerRuns.markRunning(runId);
        state.workerRuns.complete(runId, "b".repeat(64));
        state.managedWorktrees.assignWorker(runtimeId, runId);
        WorkerStateLocation.Location location = new WorkerStateLocation(managedRoot, workspace)
                .locate(runId);
        try (StateStore worker = StateStore.openWorker(
                location.stateDatabase(), location.traceDirectory())) {
            worker.tasks.createTask(runId, "fixture", "mock", null,
                    Config.MODE_WORKSPACE_WRITE, 3);
            worker.tasks.markRunning(runId);
        }
        Files.writeString(workspace.resolve(path), content);
        state.managedWorktrees.markDirty(runtimeId, runId);
        return new WorkerCandidateService(state, worktrees, repository, managedRoot, config())
                .freezeAndVerify(runId).candidateId();
    }

    private Config config() {
        return new Config(".", 30, 50_000, 200_000, Config.MODE_WORKSPACE_WRITE,
                10_000, "mock", null, null, null, null, 10,
                isWindows() ? "exit /b 0" : "true", 20, 8192,
                1, true, true, true);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }
}
