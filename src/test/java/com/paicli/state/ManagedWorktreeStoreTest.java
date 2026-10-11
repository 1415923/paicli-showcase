package com.paicli.state;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class ManagedWorktreeStoreTest {
    @TempDir Path tempDir;
    private StateStore store;

    @BeforeEach void setUp() throws Exception { store = new StateStore(tempDir); }
    @AfterEach void tearDown() throws Exception { if (store != null) store.close(); }

    @Test void gitAndDirectoryBackendsRoundTripWithDistinctCapabilities() throws Exception {
        WorkerStoreTestFixtures.createGitWorktree(store, "wt-git", "paicli/worker/a");
        WorkerStoreTestFixtures.createDirectoryWorktree(store, "wt-export");

        var git = store.managedWorktrees.findById("wt-git").orElseThrow();
        var export = store.managedWorktrees.findById("wt-export").orElseThrow();
        assertEquals(ManagedWorktreeStore.CAPABILITY_GIT, git.backendCapability());
        assertEquals("paicli/worker/a", git.workerBranch());
        assertEquals(ManagedWorktreeStore.CAPABILITY_EXPORT, export.backendCapability());
        assertNull(export.workerBranch());
    }

    @Test void canonicalPathAndGitBranchAreUnique() throws Exception {
        WorkerStoreTestFixtures.createGitWorktree(store, "wt-a", "paicli/worker/a");
        assertThrows(SQLException.class, () -> store.managedWorktrees.create(
                "wt-b", ManagedWorktreeStore.BACKEND_GIT, "repo-1",
                WorkerStoreTestFixtures.BASE_SHA, "paicli/worker/b",
                "C:/managed/wt-a", "C:/state/b", "C:/trace/b", "CLEAN"));
        assertThrows(SQLException.class, () -> store.managedWorktrees.create(
                "wt-c", ManagedWorktreeStore.BACKEND_GIT, "repo-1",
                WorkerStoreTestFixtures.BASE_SHA, "paicli/worker/a",
                "C:/managed/wt-c", "C:/state/c", "C:/trace/c", "CLEAN"));
    }

    @Test void directoryBackendRejectsBranchSemanticsAndTracksLifecycle() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> store.managedWorktrees.create(
                "bad", ManagedWorktreeStore.BACKEND_DIRECTORY, "repo-1",
                WorkerStoreTestFixtures.BASE_SHA, "fake-branch",
                "C:/managed/bad", "C:/state/bad", "C:/trace/bad", "UNKNOWN"));
        WorkerStoreTestFixtures.createDirectoryWorktree(store, "wt-export");
        store.managedWorktrees.markAbandoned("wt-export", "token=secret");
        var abandoned = store.managedWorktrees.findById("wt-export").orElseThrow();
        assertEquals("ABANDONED", abandoned.status());
        assertFalse(abandoned.lastErrorExcerpt().contains("secret"));
        assertNotNull(abandoned.abandonedAt());
        store.managedWorktrees.markCleaned("wt-export");
        assertNotNull(store.managedWorktrees.findById("wt-export").orElseThrow().cleanedAt());
    }

    @Test void closeInvalidatesStore() throws Exception {
        ManagedWorktreeStore managed = store.managedWorktrees;
        store.close();
        store = null;
        assertThrows(SQLException.class, () -> managed.findById("missing"));
    }

    @Test void serviceQueriesAndLostTransitionAreAvailableWithoutFilesystemActions()
            throws Exception {
        WorkerStoreTestFixtures.createGitWorktree(store, "wt-query", "paicli/worker/query");
        assertEquals("wt-query", store.managedWorktrees
                .findByWorkspacePath("C:/managed/wt-query").orElseThrow().managedWorktreeId());
        assertEquals("wt-query", store.managedWorktrees
                .findByRepositoryAndBranch("repo-1", "paicli/worker/query")
                .orElseThrow().managedWorktreeId());
        assertEquals(1, store.managedWorktrees.findAll().size());
        store.managedWorktrees.markLost("wt-query", "porcelain metadata missing");
        assertEquals(ManagedWorktreeStore.STATUS_LOST,
                store.managedWorktrees.findById("wt-query").orElseThrow().status());
    }

    @Test void workerOwnershipAndDirtyStateUseSeparatedWorktreeLifecycle() throws Exception {
        WorkerStoreTestFixtures.createGitWorktree(store, "wt-runtime", "paicli/worker/runtime");
        store.workerTasks.create("item-runtime", null, "runtime", WorkerStoreTestFixtures.HASH);
        store.workerRuns.create("worker-runtime", null, "item-runtime", "wt-runtime",
                "runtime", 1, "mock", WorkerStoreTestFixtures.HASH);
        store.managedWorktrees.markActive("wt-runtime", null);
        store.managedWorktrees.assignWorker("wt-runtime", "worker-runtime");
        store.managedWorktrees.markDirty("wt-runtime", "worker-runtime");
        var dirty = store.managedWorktrees.findById("wt-runtime").orElseThrow();
        assertEquals("DIRTY", dirty.status());
        assertEquals("DIRTY", dirty.dirtyState());
        assertEquals("worker-runtime", dirty.ownerWorkerRunId());
    }

    @Test void candidateFreezeOwnershipUsesTokenCas() throws Exception {
        WorkerStoreTestFixtures.createGitWorktree(store, "wt-freeze", "paicli/worker/freeze");
        store.workerRuns.create("worker-freeze", null, null, "wt-freeze",
                "freeze", 1, "mock", WorkerStoreTestFixtures.HASH);
        store.managedWorktrees.markActive("wt-freeze", null);
        store.managedWorktrees.assignWorker("wt-freeze", "worker-freeze");
        store.managedWorktrees.markDirty("wt-freeze", "worker-freeze");
        String token = "d".repeat(64);
        store.managedWorktrees.beginCandidateFreeze("wt-freeze", "worker-freeze", token);
        assertEquals(ManagedWorktreeStore.STATUS_FREEZING,
                store.managedWorktrees.findById("wt-freeze").orElseThrow().status());
        assertThrows(SQLException.class, () -> store.managedWorktrees.beginCandidateFreeze(
                "wt-freeze", "worker-freeze", "e".repeat(64)));
        assertThrows(SQLException.class, () -> store.managedWorktrees.markCandidateFrozen(
                "wt-freeze", "worker-freeze", "e".repeat(64)));
        store.managedWorktrees.markCandidateFrozen("wt-freeze", "worker-freeze", token);
        var frozen = store.managedWorktrees.findById("wt-freeze").orElseThrow();
        assertEquals(ManagedWorktreeStore.STATUS_CANDIDATE_FROZEN, frozen.status());
        assertNull(frozen.freezeToken());
    }
}
