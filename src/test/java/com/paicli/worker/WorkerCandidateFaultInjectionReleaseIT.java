package com.paicli.worker;

import com.paicli.config.Config;
import com.paicli.runtime.VerificationResult;
import com.paicli.state.ManagedWorktreeStore;
import com.paicli.state.StateStore;
import com.paicli.worktree.GitTestSupport;
import com.paicli.worktree.ManagedWorktreeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class WorkerCandidateFaultInjectionReleaseIT {
    @TempDir Path tempDir;
    private Path repository;
    private Path managedRoot;
    private Path workspace;
    private StateStore coordinator;
    private ManagedWorktreeService worktrees;
    private String workerRunId;
    private int baseCommitCount;

    @BeforeEach void setUp() throws Exception {
        repository = GitTestSupport.initialize(tempDir.resolve("main"));
        Files.writeString(repository.resolve("rename-me.txt"), "rename\n");
        Files.writeString(repository.resolve("delete-me.txt"), "delete\n");
        Files.writeString(repository.resolve(".gitignore"), "target/\nbuild/\n");
        GitTestSupport.require(GitTestSupport.git(repository, "add", "."));
        GitTestSupport.require(GitTestSupport.git(repository, "commit", "-m", "candidate fixtures"));
        baseCommitCount = Integer.parseInt(GitTestSupport.gitText(
                repository, "rev-list", "--count", "HEAD"));
        managedRoot = tempDir.resolve("managed");
        coordinator = new StateStore(tempDir.resolve("coordinator"));
        worktrees = new ManagedWorktreeService(coordinator.managedWorktrees);
        worktrees.createLinkedWorktree(ManagedWorktreeService.CreateRequest.linked(
                repository, managedRoot, "candidate-worker"));
        var record = coordinator.managedWorktrees.findById("candidate-worker").orElseThrow();
        workspace = Path.of(record.canonicalWorkspacePath());
        workerRunId = "worker-candidate";
        coordinator.workerRuns.create(workerRunId, null, null, "candidate-worker",
                "candidate", 1, "mock", "a".repeat(64));
        coordinator.workerRuns.markPreparing(workerRunId);
        coordinator.workerRuns.markRunning(workerRunId);
        coordinator.workerRuns.complete(workerRunId, "b".repeat(64));
        coordinator.managedWorktrees.assignWorker("candidate-worker", workerRunId);
        WorkerStateLocation.Location location = new WorkerStateLocation(managedRoot, workspace)
                .locate(workerRunId);
        try (StateStore worker = StateStore.openWorker(
                location.stateDatabase(), location.traceDirectory())) {
            worker.tasks.createTask(workerRunId, "candidate task", "mock", null,
                    Config.MODE_WORKSPACE_WRITE, 3);
            worker.tasks.markRunning(workerRunId);
        }
    }

    @AfterEach void tearDown() throws Exception {
        GitTestSupport.cleanup(repository, tempDir);
        if (coordinator != null) coordinator.close();
    }

    @Test void failureAfterCommitObjectKeepsBaseRefAndNoCandidate() throws Exception {
        prepareChange();
        String base = head();
        assertThrows(IllegalStateException.class,
                () -> service(failAt(WorkerCandidateService.FaultPoint.AFTER_COMMIT_OBJECT),
                        production(), passed()).freezeAndVerify(workerRunId));
        assertEquals(base, head());
        assertTrue(coordinator.workerCandidates.findByWorkerRunId(workerRunId).isEmpty());
        assertAbandonedWithoutToken();
        assertEquals(baseCommitCount, commitCount());
        assertThrows(IllegalStateException.class,
                () -> service().freezeAndVerify(workerRunId));
        assertEquals(baseCommitCount, commitCount());
    }

    @Test void failureAfterBranchUpdatePreservesRefResidueWithoutCandidateOrRetryCommit()
            throws Exception {
        prepareChange();
        String base = head();
        assertThrows(IllegalStateException.class,
                () -> service(failAt(WorkerCandidateService.FaultPoint.AFTER_BRANCH_UPDATE),
                        production(), passed()).freezeAndVerify(workerRunId));
        String candidate = head();
        assertNotEquals(base, candidate);
        assertEquals(base, GitTestSupport.gitText(workspace, "rev-parse", candidate + "^"));
        assertTrue(coordinator.workerCandidates.findByWorkerRunId(workerRunId).isEmpty());
        assertAbandonedWithoutToken();
        assertEquals(baseCommitCount + 1, commitCount());
        assertThrows(IllegalStateException.class,
                () -> service().freezeAndVerify(workerRunId));
        assertEquals(candidate, head());
        assertEquals(baseCommitCount + 1, commitCount());
    }

    @Test void candidatePersistenceAndVerificationWindowsRemainNonReadyAndDoNotRecommit()
            throws Exception {
        for (WorkerCandidateService.FaultPoint point : List.of(
                WorkerCandidateService.FaultPoint.AFTER_CANDIDATE_PERSIST,
                WorkerCandidateService.FaultPoint.BEFORE_VERIFICATION,
                WorkerCandidateService.FaultPoint.AFTER_VERIFICATION)) {
            if (point != WorkerCandidateService.FaultPoint.AFTER_CANDIDATE_PERSIST) {
                resetFixtureForNextWindow();
            }
            prepareChange();
            assertThrows(IllegalStateException.class,
                    () -> service(failAt(point), production(), passed())
                            .freezeAndVerify(workerRunId));
            var candidate = coordinator.workerCandidates.findByWorkerRunId(workerRunId)
                    .orElseThrow();
            assertNotEquals("READY_FOR_DECISION", candidate.status());
            assertEquals(candidate.candidateCommitSha(), head());
            assertAbandonedWithoutToken();
            int commits = commitCount();
            assertThrows(IllegalStateException.class,
                    () -> service().freezeAndVerify(workerRunId));
            assertEquals(commits, commitCount());
            assertEquals(1, coordinator.workerCandidates.findByWorkerRunId(workerRunId).stream()
                    .count());
        }
    }

    @Test void concurrentFreezeHasOneOwnerOneCandidateAndOneCandidateCommit() throws Exception {
        prepareChange();
        CountDownLatch reachedCommit = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        WorkerCandidateService.FaultInjector blocking = point -> {
            if (point == WorkerCandidateService.FaultPoint.AFTER_COMMIT_OBJECT) {
                reachedCommit.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service(blocking, production(), passed())
                    .freezeAndVerify(workerRunId));
            assertTrue(reachedCommit.await(10, TimeUnit.SECONDS));
            var second = executor.submit(() -> assertThrows(IllegalStateException.class,
                    () -> service().freezeAndVerify(workerRunId)));
            second.get(10, TimeUnit.SECONDS);
            release.countDown();
            var result = first.get(20, TimeUnit.SECONDS);
            assertEquals("READY_FOR_DECISION", result.status());
            assertEquals(result.candidateCommitSha(), head());
            assertEquals(baseCommitCount + 1, commitCount());
            assertNull(coordinator.managedWorktrees.findById("candidate-worker")
                    .orElseThrow().freezeToken());
            assertTrue(coordinator.workerCandidates.findByWorkerRunId(workerRunId).isPresent());
        }
    }

    @Test void failedConcurrentOwnerCannotBeTakenOverOrCreateSecondCommit() throws Exception {
        prepareChange();
        CountDownLatch ownerReachedCommit = new CountDownLatch(1);
        CountDownLatch failOwner = new CountDownLatch(1);
        WorkerCandidateService.FaultInjector failingOwner = point -> {
            if (point == WorkerCandidateService.FaultPoint.AFTER_COMMIT_OBJECT) {
                ownerReachedCommit.countDown();
                try {
                    assertTrue(failOwner.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                throw new InjectedFailure("concurrent owner");
            }
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> assertThrows(IllegalStateException.class,
                    () -> service(failingOwner, production(), passed())
                            .freezeAndVerify(workerRunId)));
            assertTrue(ownerReachedCommit.await(10, TimeUnit.SECONDS));
            var second = executor.submit(() -> assertThrows(IllegalStateException.class,
                    () -> service().freezeAndVerify(workerRunId)));
            second.get(10, TimeUnit.SECONDS);
            failOwner.countDown();
            first.get(20, TimeUnit.SECONDS);
        }
        assertEquals(baseCommitCount, commitCount());
        assertTrue(coordinator.workerCandidates.findByWorkerRunId(workerRunId).isEmpty());
        assertAbandonedWithoutToken();
        assertThrows(IllegalStateException.class,
                () -> service().freezeAndVerify(workerRunId));
        assertEquals(baseCommitCount, commitCount());
    }

    @Test void completedVerificationPersistenceFailureNeverBecomesReadyOrRecommits()
            throws Exception {
        prepareChange();
        assertThrows(IllegalStateException.class,
                () -> service(failAt(WorkerCandidateService.FaultPoint.AFTER_VERIFICATION),
                        production(), null).freezeAndVerify(workerRunId));
        var candidate = coordinator.workerCandidates.findByWorkerRunId(workerRunId)
                .orElseThrow();
        assertEquals("VERIFYING", candidate.status());
        assertEquals("NOT_RUN", candidate.verificationStatus());
        assertNotEquals("READY_FOR_DECISION", candidate.status());
        String commit = candidate.candidateCommitSha();
        assertEquals(commit, head());
        assertAbandonedWithoutToken();
        assertThrows(IllegalStateException.class,
                () -> service().freezeAndVerify(workerRunId));
        assertEquals(commit, head());
        assertEquals(baseCommitCount + 1, commitCount());
    }

    @Test void protectedControlPlanePathCannotEnterCandidate() throws Exception {
        Files.createDirectories(workspace.resolve(".paicli"));
        Files.writeString(workspace.resolve(".paicli/secret.txt"), "blocked");
        coordinator.managedWorktrees.markDirty("candidate-worker", workerRunId);
        String base = head();
        assertThrows(IllegalStateException.class,
                () -> service().freezeAndVerify(workerRunId));
        assertEquals(base, head());
        assertTrue(coordinator.workerCandidates.findByWorkerRunId(workerRunId).isEmpty());
        assertTrue(Files.exists(workspace.resolve(".paicli/secret.txt")));
    }

    @Test void actualGitlinkIndexEntryFailsClosedBeforeCommitOrVerification() throws Exception {
        Path nested = workspace.resolve("nested");
        GitTestSupport.initialize(nested);
        GitTestSupport.require(GitTestSupport.git(workspace, "add", "nested"));
        coordinator.managedWorktrees.markDirty("candidate-worker", workerRunId);
        String base = head();
        var calls = new int[1];
        assertThrows(IllegalStateException.class,
                () -> service(WorkerCandidateService.FaultInjector.NONE, production(),
                        (a,b,c,d,e) -> { calls[0]++; return passed().verify(a,b,c,d,e); })
                        .freezeAndVerify(workerRunId));
        assertEquals(0, calls[0]);
        assertEquals(base, head());
        assertTrue(coordinator.workerCandidates.findByWorkerRunId(workerRunId).isEmpty());
        assertAbandonedWithoutToken();
    }

    @Test void renameDeleteUntrackedAndIgnoredArtifactMatchCandidateTree() throws Exception {
        Files.move(workspace.resolve("rename-me.txt"), workspace.resolve("renamed.txt"));
        Files.delete(workspace.resolve("delete-me.txt"));
        Files.writeString(workspace.resolve("new.java"), "class NewFile {}\n");
        Files.createDirectories(workspace.resolve("target"));
        Files.writeString(workspace.resolve("target/generated.bin"), "ignored");
        coordinator.managedWorktrees.markDirty("candidate-worker", workerRunId);

        var result = service().freezeAndVerify(workerRunId);

        assertEquals(List.of("delete-me.txt", "new.java", "renamed.txt"),
                result.changedFiles());
        assertFalse(result.changedFiles().stream().anyMatch(p -> p.startsWith("target/")));
        assertFalse(Files.exists(workspace.resolve("delete-me.txt")));
        assertTrue(Files.exists(workspace.resolve("renamed.txt")));
        assertTrue(Files.exists(workspace.resolve("target/generated.bin")));
        assertEquals("", GitTestSupport.gitText(workspace, "status", "--porcelain",
                "--untracked-files=normal"));
    }

    @Test void fileCountBudgetExactPassesAndPlusOneFailsWithoutCommit() throws Exception {
        Files.writeString(workspace.resolve("one.java"), "1");
        Files.writeString(workspace.resolve("two.java"), "2");
        coordinator.managedWorktrees.markDirty("candidate-worker", workerRunId);
        assertEquals("READY_FOR_DECISION",
                service(none(), new WorkerCandidateService.CandidateLimits(2, 10, 20), passed())
                        .freezeAndVerify(workerRunId).status());

        resetFixtureForNextWindow();
        Files.writeString(workspace.resolve("one.java"), "1");
        Files.writeString(workspace.resolve("two.java"), "2");
        Files.writeString(workspace.resolve("three.java"), "3");
        coordinator.managedWorktrees.markDirty("candidate-worker", workerRunId);
        String base = head();
        assertThrows(IllegalStateException.class,
                () -> service(none(), new WorkerCandidateService.CandidateLimits(2, 10, 20),
                        passed()).freezeAndVerify(workerRunId));
        assertEquals(base, head());
        assertTrue(coordinator.workerCandidates.findByWorkerRunId(workerRunId).isEmpty());
    }

    @Test void singleAndTotalByteBudgetsHonorExactBoundaryAndRejectPlusOne() throws Exception {
        Files.writeString(workspace.resolve("exact.bin"), "1234");
        coordinator.managedWorktrees.markDirty("candidate-worker", workerRunId);
        assertEquals("READY_FOR_DECISION",
                service(none(), new WorkerCandidateService.CandidateLimits(5, 4, 4), passed())
                        .freezeAndVerify(workerRunId).status());

        resetFixtureForNextWindow();
        Files.writeString(workspace.resolve("too-big.bin"), "12345");
        coordinator.managedWorktrees.markDirty("candidate-worker", workerRunId);
        assertThrows(IllegalStateException.class,
                () -> service(none(), new WorkerCandidateService.CandidateLimits(5, 4, 20),
                        passed()).freezeAndVerify(workerRunId));
        assertEquals(baseCommitCount, commitCount());

        resetFixtureForNextWindow();
        Files.writeString(workspace.resolve("a.bin"), "123");
        Files.writeString(workspace.resolve("b.bin"), "456");
        coordinator.managedWorktrees.markDirty("candidate-worker", workerRunId);
        assertEquals("READY_FOR_DECISION",
                service(none(), new WorkerCandidateService.CandidateLimits(5, 4, 6), passed())
                        .freezeAndVerify(workerRunId).status());

        resetFixtureForNextWindow();
        Files.writeString(workspace.resolve("a.bin"), "123");
        Files.writeString(workspace.resolve("b.bin"), "4567");
        coordinator.managedWorktrees.markDirty("candidate-worker", workerRunId);
        assertThrows(IllegalStateException.class,
                () -> service(none(), new WorkerCandidateService.CandidateLimits(5, 4, 6),
                        passed()).freezeAndVerify(workerRunId));
        assertEquals(baseCommitCount, commitCount());
    }

    @Test void timedOutAndErrorVerificationArePersistedAndNeverReady() throws Exception {
        for (String status : List.of("TIMED_OUT", "ERROR")) {
            prepareChange();
            WorkerCandidateService.VerificationExecutor executor = (workspace, store, run, task, hash) ->
                    new VerificationResult("verification", run, task, 0, "trusted",
                            status, null, 12, null, status.toLowerCase(),null,null);
            var result = service(none(), production(), executor).freezeAndVerify(workerRunId);
            assertEquals("VERIFICATION_FAILED", result.status());
            assertEquals(status, result.verificationStatus());
            var candidate = coordinator.workerCandidates.findByWorkerRunId(workerRunId)
                    .orElseThrow();
            assertEquals(status, candidate.verificationStatus());
            assertNotEquals("READY_FOR_DECISION", candidate.status());
            assertTrue(coordinator.workerIntegrationDecisions.findByProposal(
                    candidate.candidateId()).isEmpty());
            if ("TIMED_OUT".equals(status)) {
                resetFixtureForNextWindow();
            }
        }
    }

    private void prepareChange() throws Exception {
        Files.writeString(workspace.resolve("base.txt"), "candidate\n");
        coordinator.managedWorktrees.markDirty("candidate-worker", workerRunId);
    }

    private void resetFixtureForNextWindow() throws Exception {
        coordinator.close();
        coordinator = null;
        GitTestSupport.cleanup(repository, tempDir);
        try (var paths = Files.walk(tempDir)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                path.toFile().setWritable(true, false);
                try { Files.deleteIfExists(path); }
                catch (java.nio.file.DirectoryNotEmptyException ignored) { }
            }
        }
        Files.createDirectories(tempDir);
        setUp();
    }

    private WorkerCandidateService service() throws Exception {
        return service(none(), production(), passed());
    }

    private WorkerCandidateService service(WorkerCandidateService.FaultInjector faults,
                                           WorkerCandidateService.CandidateLimits limits,
                                           WorkerCandidateService.VerificationExecutor executor)
            throws Exception {
        return new WorkerCandidateService(coordinator, worktrees, repository, managedRoot,
                config(), faults, limits, executor);
    }

    private Config config() {
        return new Config(".", 30, 50_000, 200_000, Config.MODE_WORKSPACE_WRITE, 10_000,
                "mock", null, null, null, null, 10,
                successCommand(), 10, 4096, 1, true, true, true);
    }

    private WorkerCandidateService.FaultInjector failAt(WorkerCandidateService.FaultPoint target) {
        return point -> {
            if (point == target) throw new InjectedFailure(target.name());
        };
    }

    private static WorkerCandidateService.FaultInjector none() {
        return WorkerCandidateService.FaultInjector.NONE;
    }

    private static WorkerCandidateService.CandidateLimits production() {
        return WorkerCandidateService.CandidateLimits.PRODUCTION;
    }

    private static WorkerCandidateService.VerificationExecutor passed() {
        return (workspace, store, run, task, hash) ->
                new VerificationResult("verification", run, task, 0, "trusted",
                        "PASSED", 0, 1, null, null,
                        "WORKSPACE_CONTENT_SHA256_V1","a".repeat(64));
    }

    private void assertAbandonedWithoutToken() throws Exception {
        var worktree = coordinator.managedWorktrees.findById("candidate-worker").orElseThrow();
        assertEquals(ManagedWorktreeStore.STATUS_ABANDONED, worktree.status());
        assertNull(worktree.freezeToken());
    }

    private String head() throws Exception {
        return GitTestSupport.gitText(workspace, "rev-parse", "HEAD");
    }

    private int commitCount() throws Exception {
        return Integer.parseInt(GitTestSupport.gitText(workspace, "rev-list", "--count", "HEAD"));
    }

    private static String successCommand() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows")
                ? "exit /b 0" : "true";
    }

    private static final class InjectedFailure extends RuntimeException {
        private InjectedFailure(String point) {
            super("injected failure at " + point);
        }
    }
}
