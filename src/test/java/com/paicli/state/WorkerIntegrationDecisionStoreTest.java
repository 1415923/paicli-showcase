package com.paicli.state;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class WorkerIntegrationDecisionStoreTest {
    @TempDir Path tempDir;
    private StateStore store;

    @BeforeEach void setUp() throws Exception {
        store = new StateStore(tempDir);
        WorkerStoreTestFixtures.createGitWorktree(store, "wt", "paicli/worker/a");
        WorkerStoreTestFixtures.createTaskAndRun(store, "decision", "wt", 1);
        WorkerStoreTestFixtures.createClaim(store, "decision", 1);
        store.workerCandidates.freeze("candidate", "worker-decision", "wt",
                "claim-decision", "GIT_LINKED_WORKTREE", WorkerStoreTestFixtures.BASE_SHA,
                "paicli/worker/a", WorkerStoreTestFixtures.CANDIDATE_SHA,
                WorkerStoreTestFixtures.HASH, "[]", 0, "PASSED",
                WorkerStoreTestFixtures.HASH, WorkerStoreTestFixtures.HASH, "ready");
    }
    @AfterEach void tearDown() throws Exception { if (store != null) store.close(); }

    @Test void userApprovalIsIdempotent() throws Exception {
        advanceCandidateToReady();
        WorkerStoreTestFixtures.createReadyProposal(store, "proposal", "candidate");
        var first = store.workerIntegrationDecisions.save("decision-1", "proposal",
                "APPROVED", "USER", null, "main", WorkerStoreTestFixtures.BASE_SHA);
        var second = store.workerIntegrationDecisions.save("decision-ignored", "proposal",
                "APPROVED", "USER", null, "main", WorkerStoreTestFixtures.BASE_SHA);
        assertEquals(first.integrationDecisionId(), second.integrationDecisionId());
    }

    @Test void nonUserApprovalAndConflictingDecisionFailClosed() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> store.workerIntegrationDecisions.save(
                "bad", "proposal", "APPROVED", "RUNTIME", null, null, null));
        assertThrows(SQLException.class, () -> store.workerIntegrationDecisions.save(
                "bad-user", "proposal", "APPROVED", "USER", null, null, null));
        advanceCandidateToReady();
        WorkerStoreTestFixtures.createReadyProposal(store, "proposal", "candidate");
        store.workerIntegrationDecisions.save("decision-1", "proposal",
                "REJECTED", "POLICY", "verification risk", null, null);
        assertThrows(SQLException.class, () -> store.workerIntegrationDecisions.save(
                "decision-2", "proposal", "REQUEST_REWORK", "USER",
                "rework it", null, null));
    }

    @Test void rejectAndReworkRequireReason() {
        assertThrows(IllegalArgumentException.class, () -> store.workerIntegrationDecisions.save(
                "bad", "proposal", "REJECTED", "USER", " ", null, null));
    }

    private void advanceCandidateToReady() throws Exception {
        store.workerCandidates.updateStatus("candidate", "FROZEN", "VERIFYING");
        store.workerCandidates.updateStatus("candidate", "VERIFYING", "VERIFIED");
        store.workerCandidates.updateStatus("candidate", "VERIFIED", "CONFLICT_CHECKED");
        store.workerCandidates.updateStatus(
                "candidate", "CONFLICT_CHECKED", "READY_FOR_DECISION");
    }
}
