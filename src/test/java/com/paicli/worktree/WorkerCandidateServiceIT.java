package com.paicli.worktree;

import com.paicli.config.Config;
import com.paicli.state.CodingRunStore;
import com.paicli.state.ManagedWorktreeStore;
import com.paicli.state.StateStore;
import com.paicli.worker.WorkerCandidateService;
import com.paicli.worker.WorkerStateLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorkerCandidateServiceIT {
    @TempDir Path tempDir;
    private Path repository;
    private Path managedRoot;
    private Path workspace;
    private StateStore coordinator;
    private ManagedWorktreeService worktrees;
    private String workerRunId;

    @BeforeEach void setUp() throws Exception {
        repository = GitTestSupport.initialize(tempDir.resolve("main"));
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

    @Test void freezesRuntimeOwnedCommitAndPassesConfiguredVerification() throws Exception {
        String base = GitTestSupport.gitText(repository, "rev-parse", "HEAD");
        String mainBranch = GitTestSupport.gitText(repository, "branch", "--show-current");
        Files.writeString(workspace.resolve("base.txt"), "candidate\n");
        Files.writeString(workspace.resolve("new.java"), "class NewFile {}\n");
        coordinator.managedWorktrees.markDirty("candidate-worker", workerRunId);

        var result = service(successCommand()).freezeAndVerify(workerRunId);

        assertEquals("READY_FOR_DECISION", result.status());
        assertEquals("PASSED", result.verificationStatus());
        assertEquals(base, GitTestSupport.gitText(workspace, "rev-parse",
                result.candidateCommitSha() + "^"));
        assertEquals(result.candidateCommitSha(), GitTestSupport.gitText(workspace,
                "rev-parse", "HEAD"));
        assertEquals("base\n", Files.readString(repository.resolve("base.txt")));
        assertEquals(base, GitTestSupport.gitText(repository, "rev-parse", "HEAD"));
        assertEquals(mainBranch, GitTestSupport.gitText(repository, "branch", "--show-current"));
        assertEquals(2, result.changedFiles().size());
        var candidate = coordinator.workerCandidates.findById(result.candidateId()).orElseThrow();
        assertNotNull(candidate.verificationConfigHash());
        assertEquals("CANDIDATE_FROZEN", coordinator.managedWorktrees
                .findById("candidate-worker").orElseThrow().status());
        assertTrue(coordinator.workerIntegrationDecisions.findByProposal(
                result.candidateId()).isEmpty());
    }

    @Test void failedAndSkippedVerificationNeverBecomeReady() throws Exception {
        Files.writeString(workspace.resolve("base.txt"), "failed candidate\n");
        coordinator.managedWorktrees.markDirty("candidate-worker", workerRunId);
        var failed = service(failureCommand()).freezeAndVerify(workerRunId);
        assertEquals("VERIFICATION_FAILED", failed.status());
        assertEquals("FAILED", failed.verificationStatus());
        assertEquals(ManagedWorktreeStore.STATUS_ABANDONED, coordinator.managedWorktrees
                .findById("candidate-worker").orElseThrow().status());
    }

    @Test void unconfiguredVerificationIsSkippedAndCannotBecomeReady() throws Exception {
        Files.writeString(workspace.resolve("base.txt"), "skipped candidate\n");
        coordinator.managedWorktrees.markDirty("candidate-worker", workerRunId);
        var skipped = service("").freezeAndVerify(workerRunId);
        assertEquals("VERIFICATION_FAILED", skipped.status());
        assertEquals("SKIPPED", skipped.verificationStatus());
    }

    @Test void terminalCandidateIsIdempotentButConfigDriftFailsClosed() throws Exception {
        Files.writeString(workspace.resolve("base.txt"), "candidate\n");
        coordinator.managedWorktrees.markDirty("candidate-worker", workerRunId);
        var first = service(successCommand()).freezeAndVerify(workerRunId);
        var second = service(successCommand()).freezeAndVerify(workerRunId);
        assertTrue(second.reused());
        assertEquals(first.candidateCommitSha(), second.candidateCommitSha());
        assertThrows(IllegalStateException.class,
                () -> service(failureCommand()).freezeAndVerify(workerRunId));
    }

    @Test void verificationMutationFailsIntegrityEvenWithZeroExit() throws Exception {
        Files.writeString(workspace.resolve("base.txt"), "candidate\n");
        coordinator.managedWorktrees.markDirty("candidate-worker", workerRunId);
        String mutate = isWindows()
                ? "echo mutation>base.txt"
                : "printf mutation > base.txt";
        var result = service(mutate).freezeAndVerify(workerRunId);
        assertEquals("VERIFICATION_FAILED", result.status());
        assertEquals("ERROR", result.verificationStatus());
        assertTrue(result.message().contains("mutated"));
    }

    @Test void nonCompletedAndCleanRunsCannotFreezeAndFrozenWorktreeCannotRunWorker() throws Exception {
        assertThrows(IllegalStateException.class,
                () -> service(successCommand()).freezeAndVerify(workerRunId));
        Files.writeString(workspace.resolve("base.txt"), "candidate\n");
        coordinator.managedWorktrees.markDirty("candidate-worker", workerRunId);
        service(successCommand()).freezeAndVerify(workerRunId);
        assertNotEquals(ManagedWorktreeStore.STATUS_ACTIVE,
                coordinator.managedWorktrees.findById("candidate-worker").orElseThrow().status());
    }

    @Test void checkoutControlChangesAreRejectedBeforeStaging() throws Exception {
        Files.writeString(workspace.resolve(".gitattributes"),
                "*.txt filter=untrusted\n");
        Files.writeString(workspace.resolve("base.txt"), "candidate\n");
        coordinator.managedWorktrees.markDirty("candidate-worker", workerRunId);
        var error = assertThrows(IllegalStateException.class,
                () -> service(successCommand()).freezeAndVerify(workerRunId));
        assertTrue(error.getMessage().contains("Candidate freeze failed"));
        assertTrue(coordinator.workerCandidates.findByWorkerRunId(workerRunId).isEmpty());
        assertEquals(ManagedWorktreeStore.STATUS_ABANDONED, coordinator.managedWorktrees
                .findById("candidate-worker").orElseThrow().status());
    }

    @Test void configuredVerificationCannotContainGitControlOrPush() {
        assertThrows(IllegalArgumentException.class,
                () -> service("git push origin main"));
        assertThrows(IllegalArgumentException.class,
                () -> service("git commit -am candidate"));
    }

    private WorkerCandidateService service(String command) throws Exception {
        return new WorkerCandidateService(coordinator, worktrees, repository, managedRoot,
                new Config(".", 30, 50_000, 200_000, Config.MODE_WORKSPACE_WRITE, 10_000,
                        "mock", null, null, null, null, 10,
                        command, 10, 4096, 1, true, true, true));
    }

    private static String successCommand() { return isWindows() ? "exit /b 0" : "true"; }
    private static String failureCommand() { return isWindows() ? "exit /b 7" : "false"; }
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }
}
