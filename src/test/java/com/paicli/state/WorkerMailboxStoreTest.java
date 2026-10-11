package com.paicli.state;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorkerMailboxStoreTest {
    @TempDir Path tempDir;
    private StateStore store;

    @BeforeEach void setUp() throws Exception {
        store = new StateStore(tempDir);
        WorkerStoreTestFixtures.createGitWorktree(store, "wt", "paicli/worker/a");
        WorkerStoreTestFixtures.createTaskAndRun(store, "mail", "wt", 1);
    }
    @AfterEach void tearDown() throws Exception { if (store != null) store.close(); }

    @Test void appendQueryAndAcknowledgePreservePayload() throws Exception {
        store.workerMailbox.append("msg-1", "WORKER", "worker-mail",
                "LEAD", "lead", "worker-mail", "item-mail", "STATUS",
                "Bearer secret status", WorkerStoreTestFixtures.HASH);
        var before = store.workerMailbox.findByRecipient("LEAD", "lead").getFirst();
        assertFalse(before.boundedPayload().contains("secret"));
        assertNull(before.acknowledgedAt());
        String payload = before.boundedPayload();
        store.workerMailbox.acknowledge("msg-1");
        var after = store.workerMailbox.findByWorkerRun("worker-mail").getFirst();
        assertEquals(payload, after.boundedPayload());
        assertNotNull(after.acknowledgedAt());
        assertEquals(1, store.workerMailbox.findByTaskItem("item-mail").size());
    }

    @Test void payloadIsBoundedAndForbiddenKindsFailClosed() throws Exception {
        store.workerMailbox.append("msg-long", "WORKER", "worker-mail",
                "LEAD", "lead", "worker-mail", "item-mail", "HANDOFF",
                "x".repeat(10000), WorkerStoreTestFixtures.HASH);
        assertTrue(store.workerMailbox.findByWorkerRun("worker-mail").getFirst()
                .boundedPayload().length() <= 4096);
        assertThrows(IllegalArgumentException.class, () -> store.workerMailbox.append(
                "msg-command", "WORKER", "worker-mail", "LEAD", "lead",
                "worker-mail", "item-mail", "COMMAND", "git push",
                WorkerStoreTestFixtures.HASH));
    }
}
