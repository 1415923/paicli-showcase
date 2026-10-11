package com.paicli.worktree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.paicli.state.ManagedWorktreeStore;
import com.paicli.state.StateStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedWorktreeServiceIT {
    @TempDir Path tempDir;
    private Path repository;
    private Path managedRoot;
    private StateStore stateStore;

    @BeforeEach
    void setUp() throws Exception {
        repository = GitTestSupport.initialize(tempDir.resolve("main"));
        managedRoot = tempDir.resolve("managed");
        stateStore = new StateStore(tempDir.resolve("coordinator"));
    }

    @AfterEach
    void tearDown() throws Exception {
        GitTestSupport.cleanup(repository, tempDir);
        if (stateStore != null) stateStore.close();
    }

    @Test
    void createsAndVerifiesCleanLinkedWorktreeWithoutCoordinatorSideEffects() throws Exception {
        String mainHead = GitTestSupport.gitText(repository, "rev-parse", "HEAD");
        String mainBranch = GitTestSupport.gitText(repository, "branch", "--show-current");
        var result = service().createLinkedWorktree(
                ManagedWorktreeService.CreateRequest.linked(repository, managedRoot, "worker-1"));

        assertEquals(ManagedWorktreeStore.STATUS_ACTIVE, result.record().status());
        assertEquals("paicli/worker/worker-1", result.branch());
        assertEquals(mainHead, GitTestSupport.gitText(result.workspace(), "rev-parse", "HEAD"));
        assertEquals(mainHead, result.record().baseCommitSha());
        assertEquals(mainHead, GitTestSupport.gitText(repository, "rev-parse", "HEAD"));
        assertEquals(mainBranch, GitTestSupport.gitText(repository, "branch", "--show-current"));
        assertEquals("base\n", Files.readString(result.workspace().resolve("base.txt")));
        assertEquals(ManagedWorktreeService.Finding.MATCHED,
                service().inspectManagedWorktree("worker-1", repository).finding());
        service().assertUsable("worker-1", repository);
        assertTrue(stateStore.workerRuns.findById("worker-1").isEmpty());
        assertTrue(stateStore.workerCandidates.findById("candidate-1").isEmpty());
        assertTrue(stateStore.workerIntegrationDecisions.findByProposal("candidate-1").isEmpty());
    }

    @Test
    void dirtyPolicyBFreezesHeadAndExcludesUncommittedContent() throws Exception {
        String baseHead = GitTestSupport.gitText(repository, "rev-parse", "HEAD");
        Files.writeString(repository.resolve("base.txt"), "unstaged main change\n");
        Files.writeString(repository.resolve("staged.txt"), "staged only\n");
        GitTestSupport.require(GitTestSupport.git(repository, "add", "staged.txt"));
        Files.writeString(repository.resolve("untracked.txt"), "untracked only\n");

        var result = service().createLinkedWorktree(
                ManagedWorktreeService.CreateRequest.linked(repository, managedRoot, "dirty-worker"));

        assertTrue(result.repositoryState().dirty());
        assertEquals("DIRTY", result.record().dirtyState());
        assertEquals(baseHead, result.record().baseCommitSha());
        assertEquals("base\n", Files.readString(result.workspace().resolve("base.txt")));
        assertFalse(Files.exists(result.workspace().resolve("staged.txt")));
        assertFalse(Files.exists(result.workspace().resolve("untracked.txt")));
        assertEquals("unstaged main change\n", Files.readString(repository.resolve("base.txt")));
    }

    @Test
    void runtimeDerivesNamesAndRejectsUnsafeIdsNestedRootsAndSymlinkRoots() throws Exception {
        assertCode(ManagedWorktreeException.Code.INVALID_REQUEST,
                () -> service().createLinkedWorktree(ManagedWorktreeService.CreateRequest.linked(
                        repository, managedRoot, "../escape")));
        assertThrows(ManagedWorktreeException.class,
                () -> service().createLinkedWorktree(ManagedWorktreeService.CreateRequest.linked(
                        repository, repository.resolve("workers"), "nested")));

        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Path linkedRoot = tempDir.resolve("linked-root");
        try {
            Files.createSymbolicLink(linkedRoot, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assumptions.assumeTrue(false, "symlink creation unavailable: " + e.getMessage());
        }
        assertThrows(ManagedWorktreeException.class,
                () -> service().createLinkedWorktree(ManagedWorktreeService.CreateRequest.linked(
                        repository, linkedRoot, "linked")));
    }

    @Test
    void allCollisionLayersFailClosed() throws Exception {
        service().createLinkedWorktree(ManagedWorktreeService.CreateRequest.linked(
                repository, managedRoot, "db-collision"));
        assertCode(ManagedWorktreeException.Code.COLLISION,
                () -> service().createLinkedWorktree(ManagedWorktreeService.CreateRequest.linked(
                        repository, managedRoot, "db-collision")));

        GitTestSupport.require(GitTestSupport.git(repository, "branch",
                "paicli/worker/branch-collision"));
        assertCode(ManagedWorktreeException.Code.COLLISION,
                () -> service().createLinkedWorktree(ManagedWorktreeService.CreateRequest.linked(
                        repository, managedRoot, "branch-collision")));

        Files.createDirectories(managedRoot.resolve("workspaces").resolve("path-collision"));
        assertCode(ManagedWorktreeException.Code.COLLISION,
                () -> service().createLinkedWorktree(ManagedWorktreeService.CreateRequest.linked(
                        repository, managedRoot, "path-collision")));

        Path residue = managedRoot.resolve("workspaces").resolve("porcelain-residue");
        GitTestSupport.require(GitTestSupport.git(repository, "worktree", "add", "-b",
                "paicli/worker/porcelain-residue", residue.toString(), "HEAD"));
        assertCode(ManagedWorktreeException.Code.COLLISION,
                () -> service().createLinkedWorktree(ManagedWorktreeService.CreateRequest.linked(
                        repository, managedRoot, "porcelain-residue")));
    }

    @Test
    void inProgressOperationIsRejected() throws Exception {
        String head = GitTestSupport.gitText(repository, "rev-parse", "HEAD");
        Files.writeString(repository.resolve(".git").resolve("MERGE_HEAD"), head + "\n");
        assertCode(ManagedWorktreeException.Code.REPOSITORY_BUSY,
                () -> service().createLinkedWorktree(ManagedWorktreeService.CreateRequest.linked(
                        repository, managedRoot, "busy")));
    }

    @Test
    void directoryExportAndGitErrorsNeverFallback() throws Exception {
        assertCode(ManagedWorktreeException.Code.UNSUPPORTED_BACKEND,
                () -> service().createLinkedWorktree(new ManagedWorktreeService.CreateRequest(
                        repository, managedRoot, "export", ManagedWorktreeStore.BACKEND_DIRECTORY)));
        assertTrue(stateStore.managedWorktrees.findById("export").isEmpty());

        Path nonRepository = Files.createDirectories(tempDir.resolve("not-a-repository"));
        ManagedWorktreeException error = assertThrows(ManagedWorktreeException.class,
                () -> service().createLinkedWorktree(ManagedWorktreeService.CreateRequest.linked(
                        nonRepository, managedRoot.resolve("other"), "git-error")));
        assertNotEquals(ManagedWorktreeException.Code.UNSUPPORTED_BACKEND, error.code());
        assertTrue(stateStore.managedWorktrees.findById("git-error").isEmpty());
    }

    @Test
    void unsupportedRepositoryFeaturesFailClosed() throws Exception {
        Files.writeString(repository.resolve(".gitmodules"), "[submodule \"x\"]\n\tpath=x\n\turl=x\n");
        GitTestSupport.require(GitTestSupport.git(repository, "add", ".gitmodules"));
        GitTestSupport.require(GitTestSupport.git(repository, "commit", "-m", "gitmodules"));
        assertCode(ManagedWorktreeException.Code.UNSUPPORTED_REPOSITORY,
                () -> service().createLinkedWorktree(ManagedWorktreeService.CreateRequest.linked(
                        repository, managedRoot, "submodule")));

        GitTestSupport.require(GitTestSupport.git(repository, "reset", "--hard", "HEAD~1"));
        GitTestSupport.require(GitTestSupport.git(repository, "config",
                "filter.danger.smudge", "danger"));
        assertCode(ManagedWorktreeException.Code.UNSUPPORTED_REPOSITORY,
                () -> service().createLinkedWorktree(ManagedWorktreeService.CreateRequest.linked(
                        repository, managedRoot, "filter")));
        GitTestSupport.require(GitTestSupport.git(repository, "config", "--unset-all",
                "filter.danger.smudge"));
        GitTestSupport.require(GitTestSupport.git(repository, "config", "core.sparseCheckout", "true"));
        assertCode(ManagedWorktreeException.Code.UNSUPPORTED_REPOSITORY,
                () -> service().createLinkedWorktree(ManagedWorktreeService.CreateRequest.linked(
                        repository, managedRoot, "sparse")));
    }

    @Test
    void committedFilterAttributesAreRejected() throws Exception {
        Files.writeString(repository.resolve(".gitattributes"), "*.txt filter=danger\n");
        GitTestSupport.require(GitTestSupport.git(repository, "add", ".gitattributes"));
        GitTestSupport.require(GitTestSupport.git(repository, "commit", "-m", "attributes"));
        assertCode(ManagedWorktreeException.Code.UNSUPPORTED_REPOSITORY,
                () -> service().createLinkedWorktree(ManagedWorktreeService.CreateRequest.linked(
                        repository, managedRoot, "attributes")));
    }

    @Test
    void repositoryHookIsNotExecuted() throws Exception {
        Path hook = repository.resolve(".git").resolve("hooks").resolve("post-checkout");
        Files.writeString(hook, "#!/bin/sh\necho executed > hook-ran.txt\n",
                StandardCharsets.UTF_8);
        hook.toFile().setExecutable(true, false);
        var result = service().createLinkedWorktree(
                ManagedWorktreeService.CreateRequest.linked(repository, managedRoot, "no-hooks"));
        assertFalse(Files.exists(repository.resolve("hook-ran.txt")));
        assertFalse(Files.exists(result.workspace().resolve("hook-ran.txt")));
    }

    @Test
    void safeFailureCompensatesOnlyNewCleanResources() throws Exception {
        assertThrows(ManagedWorktreeException.class,
                () -> serviceWithPostAddFailure(false).createLinkedWorktree(
                        ManagedWorktreeService.CreateRequest.linked(
                                repository, managedRoot, "compensated")));
        Path workspace = managedRoot.resolve("workspaces").resolve("compensated");
        assertFalse(Files.exists(workspace));
        assertNotEquals(0, GitTestSupport.git(repository, "show-ref", "--verify", "--quiet",
                "refs/heads/paicli/worker/compensated").exitCode());
        var record = stateStore.managedWorktrees.findById("compensated").orElseThrow();
        assertEquals(ManagedWorktreeStore.STATUS_ABANDONED, record.status());
        assertTrue(record.lastErrorExcerpt().contains("safe create compensation completed"));
    }

    @Test
    void unsafeFailureRetainsDirtyResidueAndMarksAbandoned() throws Exception {
        assertThrows(ManagedWorktreeException.class,
                () -> serviceWithPostAddFailure(true).createLinkedWorktree(
                        ManagedWorktreeService.CreateRequest.linked(
                                repository, managedRoot, "retained")));
        Path workspace = managedRoot.resolve("workspaces").resolve("retained");
        assertTrue(Files.exists(workspace.resolve("valuable.txt")));
        var record = stateStore.managedWorktrees.findById("retained").orElseThrow();
        assertEquals(ManagedWorktreeStore.STATUS_ABANDONED, record.status());
        assertTrue(record.lastErrorExcerpt().contains("residue retained"));
    }

    @Test
    void compensationDoesNotDeleteBranchWithoutOwnedWorktreeEvidence() throws Exception {
        ManagedWorktreeService service = new ManagedWorktreeService(
                stateStore.managedWorktrees, Duration.ofSeconds(30), 65_536, hooks -> {
            HardenedGitProcess delegate = new HardenedGitProcess(
                    hooks, Duration.ofSeconds(30), 65_536);
            return (directory, operation, values) -> {
                if (operation == HardenedGitProcess.Operation.WORKTREE_ADD) {
                    try {
                        GitTestSupport.require(GitTestSupport.git(directory, "branch", values[0], values[2]));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    return new HardenedGitProcess.Result(HardenedGitProcess.Status.NON_ZERO, 99,
                            1, null, "injected collision after preflight", "worktree");
                }
                return delegate.execute(directory, operation, values);
            };
        });

        assertThrows(ManagedWorktreeException.class,
                () -> service.createLinkedWorktree(ManagedWorktreeService.CreateRequest.linked(
                        repository, managedRoot, "unowned-branch")));
        assertEquals(0, GitTestSupport.git(repository, "show-ref", "--verify", "--quiet",
                "refs/heads/paicli/worker/unowned-branch").exitCode());
        assertTrue(stateStore.managedWorktrees.findById("unowned-branch").orElseThrow()
                .lastErrorExcerpt().contains("residue retained"));
        GitTestSupport.require(GitTestSupport.git(repository, "branch", "-D",
                "paicli/worker/unowned-branch"));
    }

    @Test
    void postCreateCanonicalEscapeFailsClosed() throws Exception {
        final Path[] movedWorkspace = new Path[1];
        final Path[] junction = new Path[1];
        ManagedWorktreeService service = new ManagedWorktreeService(
                stateStore.managedWorktrees, Duration.ofSeconds(30), 65_536, hooks -> {
            HardenedGitProcess delegate = new HardenedGitProcess(
                    hooks, Duration.ofSeconds(30), 65_536);
            return (directory, operation, values) -> {
                HardenedGitProcess.Result result = delegate.execute(directory, operation, values);
                if (operation == HardenedGitProcess.Operation.WORKTREE_ADD && result.succeeded()) {
                    Path original = Path.of(values[1]);
                    Path moved = managedRoot.resolve("escaped-canonical");
                    try {
                        Files.move(original, moved);
                        createDirectoryLink(original, moved);
                        Files.writeString(moved.resolve("valuable.txt"), "retain\n");
                        movedWorkspace[0] = moved;
                        junction[0] = original;
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }
                return result;
            };
        });

        try {
            ManagedWorktreeException error = assertThrows(ManagedWorktreeException.class,
                    () -> service.createLinkedWorktree(ManagedWorktreeService.CreateRequest.linked(
                            repository, managedRoot, "canonical-escape")));
            assertEquals(ManagedWorktreeException.Code.POST_CREATE_VERIFICATION_FAILED,
                    error.code());
            assertEquals(ManagedWorktreeStore.STATUS_ABANDONED,
                    stateStore.managedWorktrees.findById("canonical-escape").orElseThrow().status());
        } finally {
            if (junction[0] != null && Files.exists(junction[0])) Files.delete(junction[0]);
            if (movedWorkspace[0] != null && Files.exists(movedWorkspace[0])) {
                Files.move(movedWorkspace[0], managedRoot.resolve("workspaces")
                        .resolve("canonical-escape"));
            }
        }
    }

    @Test
    void reconciliationReportsMissingAndUnknownResidueWithoutCleanup() throws Exception {
        var created = service().createLinkedWorktree(
                ManagedWorktreeService.CreateRequest.linked(repository, managedRoot, "recover"));
        GitTestSupport.require(GitTestSupport.git(repository, "worktree", "remove",
                created.workspace().toString()));
        List<ManagedWorktreeService.Inspection> missing = service().recoverManagedWorktrees(repository);
        assertTrue(missing.stream().anyMatch(value -> value.record() != null
                && value.record().managedWorktreeId().equals("recover")
                && value.finding() == ManagedWorktreeService.Finding.MISSING));
        assertEquals(ManagedWorktreeStore.STATUS_LOST,
                stateStore.managedWorktrees.findById("recover").orElseThrow().status());

        Path unknown = managedRoot.resolve("workspaces").resolve("unknown");
        GitTestSupport.require(GitTestSupport.git(repository, "worktree", "add", "-b",
                "paicli/worker/unknown", unknown.toString(), "HEAD"));
        List<ManagedWorktreeService.Inspection> findings = service().recoverManagedWorktrees(repository);
        assertTrue(findings.stream().anyMatch(value ->
                value.finding() == ManagedWorktreeService.Finding.UNMANAGED_GIT_WORKTREE
                        && value.entry().path().equals(unknown.toAbsolutePath().normalize())));
        assertTrue(Files.exists(unknown));
    }

    @Test
    void reconciliationPersistenceFailureIsNotReportedAsRecovered() throws Exception {
        var created = service().createLinkedWorktree(
                ManagedWorktreeService.CreateRequest.linked(repository, managedRoot, "db-failure"));
        GitTestSupport.require(GitTestSupport.git(repository, "worktree", "remove",
                created.workspace().toString()));
        ManagedWorktreeService service = service();
        stateStore.close();
        stateStore = null;

        ManagedWorktreeException error = assertThrows(ManagedWorktreeException.class,
                () -> service.recoverManagedWorktrees(repository));
        assertEquals(ManagedWorktreeException.Code.PERSISTENCE_FAILED, error.code());
    }

    private ManagedWorktreeService service() {
        return new ManagedWorktreeService(stateStore.managedWorktrees);
    }

    private ManagedWorktreeService serviceWithPostAddFailure(boolean makeDirty) {
        return new ManagedWorktreeService(stateStore.managedWorktrees, Duration.ofSeconds(30),
                65_536, hooks -> {
            HardenedGitProcess delegate = new HardenedGitProcess(hooks,
                    Duration.ofSeconds(30), 65_536);
            return (directory, operation, values) -> {
                HardenedGitProcess.Result result = delegate.execute(directory, operation, values);
                if (operation == HardenedGitProcess.Operation.WORKTREE_ADD && result.succeeded()) {
                    if (makeDirty) {
                        try {
                            Files.writeString(Path.of(values[1]).resolve("valuable.txt"), "retain\n");
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    return new HardenedGitProcess.Result(HardenedGitProcess.Status.NON_ZERO, 99,
                            result.durationMillis(), result.stdoutExcerpt(),
                            "injected post-add failure", result.operation());
                }
                return result;
            };
        });
    }

    private void assertCode(ManagedWorktreeException.Code code, ThrowingAction action) {
        ManagedWorktreeException error = assertThrows(ManagedWorktreeException.class, action::run);
        assertEquals(code, error.code(), error.getMessage());
    }

    private void createDirectoryLink(Path link, Path target) throws Exception {
        if (System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            Process process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J",
                    link.toString(), target.toString()).redirectErrorStream(true).start();
            if (process.waitFor() != 0) {
                String output = new String(process.getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8);
                throw new IOException("junction creation failed: " + output);
            }
        } else {
            Files.createSymbolicLink(link, target);
        }
    }

    @FunctionalInterface
    private interface ThrowingAction { void run() throws Exception; }
}
