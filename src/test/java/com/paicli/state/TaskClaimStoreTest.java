package com.paicli.state;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class TaskClaimStoreTest {
    @TempDir Path tempDir;
    private StateStore store;

    @BeforeEach void setUp() throws Exception {
        store = new StateStore(tempDir);
        WorkerStoreTestFixtures.createGitWorktree(store, "wt", "paicli/worker/a");
        store.workerTasks.create("item", null, "task", WorkerStoreTestFixtures.HASH);
        store.workerRuns.create("worker-1", null, "item", "wt", "occ", 1, null, null);
        store.workerRuns.create("worker-2", null, "item", "wt", "occ", 2, null, null);
    }
    @AfterEach void tearDown() throws Exception { if (store != null) store.close(); }

    @Test void activeClaimIsAtomicAndUnique() throws Exception {
        store.taskClaims.acquireTaskClaim("claim-1", "item", "worker-1", "occ", 1);
        assertThrows(SQLException.class, () -> store.taskClaims.acquireTaskClaim(
                "claim-2", "item", "worker-2", "occ", 2));
        assertTrue(store.taskClaims.findById("claim-2").isEmpty());
    }

    @Test void releaseAllowsExplicitNextClaimAndCompleteFinishesTask() throws Exception {
        store.taskClaims.acquireTaskClaim("claim-1", "item", "worker-1", "occ", 1);
        store.taskClaims.release("claim-1");
        store.taskClaims.acquireTaskClaim("claim-2", "item", "worker-2", "occ", 2);
        store.taskClaims.complete("claim-2");
        assertEquals("COMPLETED", store.taskClaims.findById("claim-2").orElseThrow().status());
        assertEquals("COMPLETED", store.workerTasks.findById("item").orElseThrow().status());
    }

    @Test void interruptedClaimIsNotAutomaticallyReassigned() throws Exception {
        store.taskClaims.acquireTaskClaim("claim-1", "item", "worker-1", "occ", 1);
        store.taskClaims.interrupt("claim-1", "crash");
        assertEquals("INTERRUPTED", store.taskClaims.findById("claim-1").orElseThrow().status());
        assertThrows(SQLException.class, () -> store.taskClaims.acquireTaskClaim(
                "claim-2", "item", "worker-2", "occ", 2));
    }

    @Test void taskSpecificationIsBoundedAndHashed() throws Exception {
        store.workerTasks.create("bounded-item", null, "x".repeat(10000),
                WorkerStoreTestFixtures.HASH);
        var item = store.workerTasks.findById("bounded-item").orElseThrow();
        assertTrue(item.specificationSummary().length() <= 4096);
        assertEquals(WorkerStoreTestFixtures.HASH, item.specificationHash());
    }
}
