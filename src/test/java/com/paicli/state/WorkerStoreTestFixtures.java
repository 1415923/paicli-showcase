package com.paicli.state;

final class WorkerStoreTestFixtures {
    static final String BASE_SHA = "a".repeat(40);
    static final String CANDIDATE_SHA = "b".repeat(40);
    static final String HASH = "c".repeat(64);

    private WorkerStoreTestFixtures() { }

    static void createGitWorktree(StateStore store, String id, String branch) throws Exception {
        store.managedWorktrees.create(id, ManagedWorktreeStore.BACKEND_GIT,
                "repo-1", BASE_SHA, branch, "C:/managed/" + id,
                "C:/managed/state/" + id, "C:/managed/traces/" + id, "UNKNOWN");
    }

    static void createDirectoryWorktree(StateStore store, String id) throws Exception {
        store.managedWorktrees.create(id, ManagedWorktreeStore.BACKEND_DIRECTORY,
                "repo-1", BASE_SHA, null, "C:/managed/" + id,
                "C:/managed/state/" + id, "C:/managed/traces/" + id, "UNKNOWN");
    }

    static void createTaskAndRun(StateStore store, String suffix, String worktreeId,
                                 int attempt) throws Exception {
        String item = "item-" + suffix;
        store.workerTasks.create(item, null, "Implement bounded worker task", HASH);
        store.workerRuns.create("worker-" + suffix, null, item, worktreeId,
                "occurrence", attempt, null, HASH);
    }

    static void createReadyProposal(StateStore store, String proposalId, String candidateId)
            throws Exception {
        store.workerMergeProposals.begin(proposalId, HASH, "repo-1", BASE_SHA,
                "paicli/proposal/test", "C:/managed/proposals/test", "DISJOINT",
                "ready proposal", java.util.List.of(
                        new WorkerMergeProposalStore.CandidateRef(candidateId, CANDIDATE_SHA)));
        store.workerMergeProposals.markMerged(proposalId, CANDIDATE_SHA,
                "d".repeat(40), HASH);
        store.workerMergeProposals.beginVerification(proposalId, HASH);
        store.workerMergeProposals.finishVerification(
                proposalId, HASH, "PASSED", HASH, null);
    }
    static void createClaim(StateStore store, String suffix, int attempt) throws Exception {
        store.taskClaims.acquireTaskClaim("claim-" + suffix, "item-" + suffix,
                "worker-" + suffix, "occurrence", attempt);
    }
}
