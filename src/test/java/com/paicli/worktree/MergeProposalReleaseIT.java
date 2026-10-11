package com.paicli.worktree;

import com.paicli.config.Config;
import com.paicli.model.*;
import com.paicli.provider.ModelProvider;
import com.paicli.safety.ApprovalPolicy;
import com.paicli.state.StateStore;
import com.paicli.worker.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MergeProposalReleaseIT {
    @TempDir Path tempDir;
    private Path repository;
    private Path managedRoot;
    private StateStore state;
    private ManagedWorktreeService worktrees;

    @BeforeEach void setUp() throws Exception {
        repository = GitTestSupport.initialize(tempDir.resolve("main"));
        managedRoot = tempDir.resolve("managed");
        state = new StateStore(tempDir.resolve("coordinator"));
        worktrees = new ManagedWorktreeService(state.managedWorktrees);
    }

    @AfterEach void tearDown() throws Exception {
        GitTestSupport.cleanup(repository, tempDir);
        if (state != null) state.close();
    }

    @Test void disjointCandidatesCreateVerifiedProposalWithoutChangingMain() throws Exception {
        String base = GitTestSupport.gitText(repository, "rev-parse", "HEAD");
        var team = readyTeam("disjoint", writer("one.txt", "one\n"),
                writer("two.txt", "two\n"));
        var proposal = proposalService(successCommand()).createAndVerify(candidateIds(team));

        assertEquals("READY_FOR_APPROVAL", proposal.status(), proposal.toString());
        assertEquals("DISJOINT", proposal.overlapStatus());
        assertEquals("CLEAN_MERGE", proposal.mergeStatus());
        assertEquals("PASSED", proposal.verificationStatus());
        assertEquals(2, proposal.candidates().size());
        assertEquals(3, GitTestSupport.gitText(repository, "rev-list", "--parents", "-n", "1",
                proposal.proposalCommitSha()).split("\\s+").length);
        assertEquals(base, GitTestSupport.gitText(repository, "rev-parse", "HEAD"));
        assertFalse(Files.exists(repository.resolve("one.txt")));
        assertFalse(Files.exists(repository.resolve("two.txt")));
        assertTrue(state.workerIntegrationDecisions.findByProposal(proposal.proposalId()).isEmpty());
    }

    @Test void overlappingSameRegionIsTextConflictAndNeverVerified() throws Exception {
        Files.writeString(repository.resolve("base.txt"), "line one\nline two\n");
        GitTestSupport.git(repository, "add", "base.txt");
        GitTestSupport.git(repository, "commit", "-m", "fixture");
        var team = readyTeam("conflict", writer("shared.txt", "worker one\n"),
                writer("shared.txt", "worker two\n"));

        var proposal = proposalService(successCommand()).createAndVerify(candidateIds(team));

        assertEquals("CONFLICTED", proposal.status());
        assertEquals("OVERLAP", proposal.overlapStatus());
        assertEquals("TEXT_CONFLICT", proposal.mergeStatus());
        assertEquals("NOT_RUN", proposal.verificationStatus());
        assertTrue(state.workerIntegrationDecisions.findByProposal(proposal.proposalId()).isEmpty());
    }

    @Test void overlapCanMergeCleanlyButStillRequiresCombinedVerification() throws Exception {
        Files.writeString(repository.resolve("base.txt"), "first\nmiddle\nlast\n");
        GitTestSupport.git(repository, "add", "base.txt");
        GitTestSupport.git(repository, "commit", "-m", "fixture");
        String first = manualCandidate("overlap-a", "base.txt", "first-a\nmiddle\nlast\n");
        String second = manualCandidate("overlap-b", "base.txt", "first\nmiddle\nlast-b\n");

        var proposal = proposalService(successCommand()).createAndVerify(List.of(first, second));

        assertEquals("OVERLAP", proposal.overlapStatus());
        assertEquals("CLEAN_MERGE", proposal.mergeStatus());
        assertEquals("PASSED", proposal.verificationStatus());
        assertEquals("READY_FOR_APPROVAL", proposal.status());
    }

    @Test void cleanGitMergeWithSemanticCompileFailureCannotBeApproved() throws Exception {
        Files.writeString(repository.resolve(".gitignore"), "target/\n");
        GitTestSupport.git(repository, "add", ".gitignore");
        GitTestSupport.git(repository, "commit", "-m", "ignore build output");
        var team = readyTeam("semantic",
                writer("A.java", "class A {}\n"),
                writer("B.java", "class B { void call() { new A().missing(); } }\n"));

        var proposal = proposalService("javac -d target/classes A.java B.java").createAndVerify(candidateIds(team));

        assertEquals("CLEAN_MERGE", proposal.mergeStatus());
        assertEquals("FAILED", proposal.verificationStatus());
        assertEquals("VERIFICATION_FAILED", proposal.status());
        var integration = new ManualIntegrationService(state, worktrees, repository, managedRoot,
                verificationConfig(successCommand()));
        assertThrows(IllegalStateException.class,
                () -> integration.approve(proposal.proposalId(), currentBranch(), null));
    }

    @Test void userApprovalDoesNotIntegrateAndExplicitCallFastForwards() throws Exception {
        String base = GitTestSupport.gitText(repository, "rev-parse", "HEAD");
        var team = readyTeam("single", writer("single.txt", "single\n"));
        var proposal = proposalService(successCommand()).createAndVerify(candidateIds(team));
        var integration = new ManualIntegrationService(state, worktrees, repository, managedRoot,
                verificationConfig(successCommand()));

        var decision = integration.approve(proposal.proposalId(), currentBranch(), "reviewed");
        assertEquals("APPROVED", decision.decision());
        assertEquals(base, GitTestSupport.gitText(repository, "rev-parse", "HEAD"));
        assertFalse(Files.exists(repository.resolve("single.txt")));

        var result = integration.integrateApprovedProposal(proposal.proposalId(), currentBranch());
        assertEquals("INTEGRATED", result.status());
        assertEquals(proposal.proposalCommitSha(),
                GitTestSupport.gitText(repository, "rev-parse", "HEAD"));
        assertEquals("single\n", Files.readString(repository.resolve("single.txt")));
        assertEquals("", GitTestSupport.gitText(repository, "status", "--porcelain"));
    }

    @Test void dirtyOrDriftedTargetFailsClosedAndPreservesUserState() throws Exception {
        var team = readyTeam("target-guard", writer("guarded.txt", "candidate\n"));
        var proposal = proposalService(successCommand()).createAndVerify(candidateIds(team));
        var integration = new ManualIntegrationService(state, worktrees, repository, managedRoot,
                verificationConfig(successCommand()));
        integration.approve(proposal.proposalId(), currentBranch(), null);

        Files.writeString(repository.resolve("user.txt"), "valuable\n");
        var dirty = integration.integrateApprovedProposal(proposal.proposalId(), currentBranch());
        assertEquals("TARGET_WORKSPACE_DIRTY", dirty.status());
        assertEquals("valuable\n", Files.readString(repository.resolve("user.txt")));
        assertFalse(Files.exists(repository.resolve("guarded.txt")));
    }

    @Test void verificationConfigurationDriftBlocksApprovedIntegration() throws Exception {
        String base = GitTestSupport.gitText(repository, "rev-parse", "HEAD");
        var team = readyTeam("config-drift", writer("drift.txt", "candidate\n"));
        var proposal = proposalService(successCommand()).createAndVerify(candidateIds(team));
        var correct = new ManualIntegrationService(state, worktrees, repository, managedRoot,
                verificationConfig(successCommand()));
        correct.approve(proposal.proposalId(), currentBranch(), null);
        var drifted = new ManualIntegrationService(state, worktrees, repository, managedRoot,
                verificationConfig(isWindows() ? "exit /b 7" : "false"));

        var error = assertThrows(IllegalStateException.class,
                () -> drifted.integrateApprovedProposal(proposal.proposalId(), currentBranch()));
        assertTrue(error.getMessage().contains("configuration drift"));
        assertEquals(base, GitTestSupport.gitText(repository, "rev-parse", "HEAD"));
        assertFalse(Files.exists(repository.resolve("drift.txt")));
    }
    private TwoWorkerCoordinator.TeamResult readyTeam(String occurrence,
                                                       SequenceProvider... providers)
            throws Exception {
        List<TwoWorkerCoordinator.TeamTask> tasks = new ArrayList<>();
        for (int i = 0; i < providers.length; i++) {
            String name = "worker-" + i;
            tasks.add(new TwoWorkerCoordinator.TeamTask("Implement " + name, providers[i],
                    "mock", new WorktreeWorkerConfig(name, "Use assigned tools only.",
                    6, 30, 4096, true), null));
        }
        Config parent = new Config(".", 30, 50_000, 200_000,
                Config.MODE_WORKSPACE_WRITE, 10_000);
        Config candidateConfig = verificationConfig(successCommand());
        var coordinator = new TwoWorkerCoordinator(state, worktrees, repository, managedRoot,
                new ApprovalPolicy(parent, "team-parent"),
                new WorkerCandidateService(state, worktrees, repository, managedRoot,
                        candidateConfig));
        var result = coordinator.run(new TwoWorkerCoordinator.TeamRequest(
                occurrence, tasks, 120));
        assertTrue(Set.of("ALL_READY", "COMPLETED").contains(result.status()), result.toString());
        return result;
    }


    private String manualCandidate(String runtimeId, String relativePath, String content)
            throws Exception {
        worktrees.createLinkedWorktree(ManagedWorktreeService.CreateRequest.linked(
                repository, managedRoot, runtimeId));
        var worktree = state.managedWorktrees.findById(runtimeId).orElseThrow();
        Path workspace = Path.of(worktree.canonicalWorkspacePath());
        String runId = "manual-" + runtimeId;
        state.workerRuns.create(runId, null, null, runtimeId, runtimeId, 1,
                "fixture", "a".repeat(64));
        state.workerRuns.markPreparing(runId);
        state.workerRuns.markRunning(runId);
        state.workerRuns.complete(runId, "b".repeat(64));
        state.managedWorktrees.assignWorker(runtimeId, runId);
        WorkerStateLocation.Location location = new WorkerStateLocation(managedRoot, workspace)
                .locate(runId);
        try (StateStore worker = StateStore.openWorker(
                location.stateDatabase(), location.traceDirectory())) {
            worker.tasks.createTask(runId, "fixture", "mock", null,
                    Config.MODE_WORKSPACE_WRITE, 3);
            worker.tasks.markRunning(runId);
        }
        Files.writeString(workspace.resolve(relativePath), content);
        state.managedWorktrees.markDirty(runtimeId, runId);
        return new WorkerCandidateService(state, worktrees, repository, managedRoot,
                verificationConfig(successCommand())).freezeAndVerify(runId).candidateId();
    }    private MergeProposalService proposalService(String command) throws Exception {
        return new MergeProposalService(state, worktrees, repository, managedRoot,
                verificationConfig(command));
    }

    private Config verificationConfig(String command) {
        return new Config(".", 30, 50_000, 200_000, Config.MODE_WORKSPACE_WRITE,
                10_000, "mock", null, null, null, null, 10, command, 20, 8192,
                1, true, true, true);
    }

    private List<String> candidateIds(TwoWorkerCoordinator.TeamResult result) {
        return result.workers().stream().map(TwoWorkerCoordinator.WorkerOutcome::candidateId)
                .filter(Objects::nonNull).toList();
    }

    private SequenceProvider writer(String path, String content) {
        return new SequenceProvider(
                ModelResponse.toolCallResponse("write", new ToolCall(
                        "write_file", Map.of("path", path, "content", content))),
                ModelResponse.finalAnswer("done"));
    }

    private String currentBranch() throws Exception {
        return GitTestSupport.gitText(repository, "branch", "--show-current");
    }
    private static String successCommand() { return isWindows() ? "exit /b 0" : "true"; }
    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }

    private static final class SequenceProvider implements ModelProvider {
        private final List<ModelResponse> responses;
        private final AtomicInteger calls = new AtomicInteger();
        SequenceProvider(ModelResponse... responses) { this.responses = List.of(responses); }
        @Override public ModelResponse chat(List<AgentMessage> messages,
                                            List<ToolDefinition> tools) {
            int index = calls.getAndIncrement();
            if (index >= responses.size()) throw new IllegalStateException("provider called too often");
            return responses.get(index);
        }
    }
}
