package com.paicli.state;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class WorkerCandidateStoreTest {
    @TempDir Path tempDir;
    private StateStore store;

    @BeforeEach void setUp() throws Exception { store = new StateStore(tempDir); }
    @AfterEach void tearDown() throws Exception { if (store != null) store.close(); }

    @Test void gitCandidateRoundTripsAndOneAttemptHasOneCandidate() throws Exception {
        setup("git", true);
        store.workerCandidates.freeze("candidate-1", "worker-git", "wt-git",
                "claim-git", "GIT_LINKED_WORKTREE", WorkerStoreTestFixtures.BASE_SHA,
                "paicli/worker/git", WorkerStoreTestFixtures.CANDIDATE_SHA,
                WorkerStoreTestFixtures.HASH, "[\"src/App.java\"]", 1,
                "PASSED", WorkerStoreTestFixtures.HASH, WorkerStoreTestFixtures.HASH,
                "candidate summary");
        var record = store.workerCandidates.findById("candidate-1").orElseThrow();
        assertEquals("FROZEN", record.status());
        assertEquals(WorkerStoreTestFixtures.CANDIDATE_SHA, record.candidateCommitSha());
        assertThrows(SQLException.class, () -> store.workerCandidates.freeze(
                "candidate-2", "worker-git", "wt-git", "claim-git",
                "GIT_LINKED_WORKTREE", WorkerStoreTestFixtures.BASE_SHA,
                "paicli/worker/git", WorkerStoreTestFixtures.CANDIDATE_SHA,
                WorkerStoreTestFixtures.HASH, "[]", 0, "PASSED",
                WorkerStoreTestFixtures.HASH, WorkerStoreTestFixtures.HASH, "replacement"));
        assertEquals(WorkerStoreTestFixtures.HASH, store.workerCandidates
                .findById("candidate-1").orElseThrow().diffHash());
        assertThrows(IllegalArgumentException.class, () -> store.workerCandidates
                .updateStatus("candidate-1", "FROZEN", "READY_FOR_DECISION"));
    }

    @Test void directoryCandidateCannotForgeBranchOrCommit() throws Exception {
        setup("export", false);
        store.workerCandidates.freeze("candidate-export", "worker-export", "wt-export",
                "claim-export", "DIRECTORY_EXPORT", WorkerStoreTestFixtures.BASE_SHA,
                null, null, WorkerStoreTestFixtures.HASH, "[]", 0,
                "NOT_RUN", null, null, "manifest candidate");
        assertNull(store.workerCandidates.findById("candidate-export").orElseThrow().candidateCommitSha());
        assertThrows(IllegalArgumentException.class, () -> store.workerCandidates.freeze(
                "candidate-bad", "worker-export", "wt-export", "claim-export",
                "DIRECTORY_EXPORT", WorkerStoreTestFixtures.BASE_SHA,
                "fake", WorkerStoreTestFixtures.CANDIDATE_SHA,
                WorkerStoreTestFixtures.HASH, "[]", 0, "NOT_RUN", null, null, "bad"));
    }

    @Test void changedFilesAndSummaryAreBounded() throws Exception {
        setup("bounded", true);
        assertThrows(IllegalArgumentException.class, () -> store.workerCandidates.freeze(
                "candidate-too-large", "worker-bounded", "wt-bounded",
                "claim-bounded", "GIT_LINKED_WORKTREE", WorkerStoreTestFixtures.BASE_SHA,
                "paicli/worker/bounded", null, WorkerStoreTestFixtures.HASH,
                "[\"" + "x".repeat(20000) + "\"]", 1, "FAILED", null, null, "summary"));
        store.workerCandidates.freeze("candidate-bounded", "worker-bounded", "wt-bounded",
                "claim-bounded", "GIT_LINKED_WORKTREE", WorkerStoreTestFixtures.BASE_SHA,
                "paicli/worker/bounded", null, WorkerStoreTestFixtures.HASH,
                "[\"src/App.java\"]", 1, "FAILED", null, null,
                "summary ".repeat(2000));
        var record = store.workerCandidates.findById("candidate-bounded").orElseThrow();
        assertEquals("[\"src/App.java\"]", record.changedFilesJson());
        assertTrue(record.boundedSummary().length() <= 4096);
    }

    @Test void verificationTerminalUpdateRequiresMatchingConfigHash() throws Exception {
        setup("verify", true);
        store.workerCandidates.freeze("candidate-verify", "worker-verify", "wt-verify",
                null, "GIT_LINKED_WORKTREE", WorkerStoreTestFixtures.BASE_SHA,
                "paicli/worker/verify", WorkerStoreTestFixtures.CANDIDATE_SHA,
                WorkerStoreTestFixtures.HASH, "[\"src/App.java\"]", 1,
                "NOT_RUN", null, WorkerStoreTestFixtures.HASH, "candidate");
        store.workerCandidates.updateStatus("candidate-verify", "FROZEN", "VERIFYING");
        assertThrows(SQLException.class, () -> store.workerCandidates.finishVerification(
                "candidate-verify", "b".repeat(64), "PASSED", "c".repeat(64),
                "READY_FOR_DECISION"));
        store.workerCandidates.finishVerification("candidate-verify",
                WorkerStoreTestFixtures.HASH, "PASSED", "c".repeat(64),
                "READY_FOR_DECISION");
        var record = store.workerCandidates.findByWorkerRunId("worker-verify").orElseThrow();
        assertEquals("READY_FOR_DECISION", record.status());
        assertEquals("PASSED", record.verificationStatus());
    }

    private void setup(String suffix, boolean git) throws Exception {
        if (git) WorkerStoreTestFixtures.createGitWorktree(
                store, "wt-" + suffix, "paicli/worker/" + suffix);
        else WorkerStoreTestFixtures.createDirectoryWorktree(store, "wt-" + suffix);
        WorkerStoreTestFixtures.createTaskAndRun(store, suffix, "wt-" + suffix, 1);
        WorkerStoreTestFixtures.createClaim(store, suffix, 1);
    }
}
