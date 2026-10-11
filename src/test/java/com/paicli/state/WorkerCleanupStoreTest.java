package com.paicli.state;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorkerCleanupStoreTest {
    @TempDir Path tempDir;
    private StateStore store;

    @BeforeEach void setUp() throws Exception {
        store = new StateStore(tempDir);
        WorkerStoreTestFixtures.createGitWorktree(store, "wt", "paicli/worker/a");
    }
    @AfterEach void tearDown() throws Exception { if (store != null) store.close(); }

    @Test void startPartialFailureAndCompleteAreDistinct() throws Exception {
        store.workerCleanups.start("cleanup-partial", "wt", "USER");
        store.workerCleanups.fail("cleanup-partial", "SUCCEEDED", "FAILED",
                "RETAINED", "state locked");
        var partial = store.workerCleanups.findById("cleanup-partial").orElseThrow();
        assertEquals("PARTIAL", partial.status());
        assertEquals("FAILED", partial.stateCleanupStatus());

        store.workerCleanups.start("cleanup-complete", "wt", "RUNTIME");
        store.workerCleanups.complete("cleanup-complete", "SUCCEEDED", "SUCCEEDED", "SUCCEEDED");
        assertEquals("COMPLETED", store.workerCleanups
                .findById("cleanup-complete").orElseThrow().status());
    }

    @Test void partialComponentsCannotBeReportedComplete() throws Exception {
        store.workerCleanups.start("cleanup", "wt", "USER");
        assertThrows(IllegalArgumentException.class, () -> store.workerCleanups.complete(
                "cleanup", "SUCCEEDED", "FAILED", "SUCCEEDED"));
        assertEquals("RUNNING", store.workerCleanups.findById("cleanup").orElseThrow().status());
    }
}
