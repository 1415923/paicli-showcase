package com.paicli.state;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class WorkerRunStoreTest {
    @TempDir Path tempDir;
    private StateStore store;

    @BeforeEach void setUp() throws Exception {
        store = new StateStore(tempDir);
        WorkerStoreTestFixtures.createGitWorktree(store, "wt", "paicli/worker/a");
    }
    @AfterEach void tearDown() throws Exception { if (store != null) store.close(); }

    @Test void completeFailAndInterruptAreTerminal() throws Exception {
        WorkerStoreTestFixtures.createTaskAndRun(store, "complete", "wt", 1);
        store.workerRuns.markPreparing("worker-complete");
        store.workerRuns.markRunning("worker-complete");
        store.workerRuns.complete("worker-complete", WorkerStoreTestFixtures.HASH);
        assertEquals("COMPLETED", store.workerRuns.findById("worker-complete").orElseThrow().status());

        store.workerTasks.create("item-fail", null, "failure", WorkerStoreTestFixtures.HASH);
        store.workerRuns.create("worker-fail", null, "item-fail", "wt", "fail", 1, null, null);
        store.workerRuns.fail("worker-fail", "Authorization: secret");
        var failed = store.workerRuns.findById("worker-fail").orElseThrow();
        assertEquals("FAILED", failed.status());
        assertFalse(failed.errorExcerpt().contains("secret"));

        store.workerTasks.create("item-int", null, "interrupt", WorkerStoreTestFixtures.HASH);
        store.workerRuns.create("worker-int", null, "item-int", "wt", "int", 1, null, null);
        store.workerRuns.markPreparing("worker-int");
        store.workerRuns.interrupt("worker-int", "restart");
        assertEquals("INTERRUPTED", store.workerRuns.findById("worker-int").orElseThrow().status());
    }

    @Test void occurrenceAttemptIsUniqueAndQueryable() throws Exception {
        WorkerStoreTestFixtures.createTaskAndRun(store, "one", "wt", 1);
        assertThrows(SQLException.class, () -> store.workerRuns.create(
                "worker-duplicate", null, "item-one", "wt",
                "occurrence", 1, null, null));
        assertEquals(1, store.workerRuns.findByOccurrence("wt", "occurrence").size());
    }

    @Test void errorExcerptIsBounded() throws Exception {
        WorkerStoreTestFixtures.createTaskAndRun(store, "long", "wt", 1);
        store.workerRuns.fail("worker-long", "x".repeat(10000));
        assertTrue(store.workerRuns.findById("worker-long").orElseThrow().errorExcerpt().length() <= 4096);
    }

    @Test void timeoutIsTerminal() throws Exception {
        WorkerStoreTestFixtures.createTaskAndRun(store, "timeout", "wt", 1);
        store.workerRuns.markPreparing("worker-timeout");
        store.workerRuns.markRunning("worker-timeout");
        store.workerRuns.timeOut("worker-timeout", "bounded timeout");
        assertEquals("TIMED_OUT", store.workerRuns.findById(
                "worker-timeout").orElseThrow().status());
        assertThrows(SQLException.class, () -> store.workerRuns.complete(
                "worker-timeout", WorkerStoreTestFixtures.HASH));
    }
}
