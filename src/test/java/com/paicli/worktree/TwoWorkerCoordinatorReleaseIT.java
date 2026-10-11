package com.paicli.worktree;

import com.paicli.config.Config;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.ModelProvider;
import com.paicli.safety.ApprovalPolicy;
import com.paicli.state.StateStore;
import com.paicli.worker.TwoWorkerCoordinator;
import com.paicli.worker.WorkerCandidateService;
import com.paicli.worker.WorktreeWorkerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TwoWorkerCoordinatorReleaseIT {
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

    @Test void twoWorkersShareFrozenBaseAndProduceIndependentReadyCandidates() throws Exception {
        String mainHead = GitTestSupport.gitText(repository, "rev-parse", "HEAD");
        String mainBranch = GitTestSupport.gitText(repository, "branch", "--show-current");
        CyclicBarrier overlap = new CyclicBarrier(2);
        SequenceProvider first = writer("one.txt", "one\n", overlap);
        SequenceProvider second = writer("two.txt", "two\n", overlap);

        var result = coordinator().run(new TwoWorkerCoordinator.TeamRequest(
                "team-ready", List.of(task("first", first), task("second", second)), 120));

        assertEquals("ALL_READY", result.status(), result.toString());
        assertEquals(2, result.workers().size());
        assertEquals("DISJOINT", result.changedFileOverlap());
        assertTrue(result.workers().stream().allMatch(
                outcome -> "READY_FOR_DECISION".equals(outcome.candidateStatus())));
        assertTrue(result.workers().stream().allMatch(
                outcome -> "PASSED".equals(outcome.verificationStatus())));
        assertEquals(2, result.workers().stream().map(TwoWorkerCoordinator.WorkerOutcome::worktreeId)
                .distinct().count());
        assertEquals(2, state.managedWorktrees.findAll().stream()
                .map(record -> record.workerBranch()).distinct().count());
        assertTrue(state.managedWorktrees.findAll().stream()
                .allMatch(record -> mainHead.equals(record.baseCommitSha())));
        assertTrue(result.workers().stream().allMatch(outcome -> {
            try { return state.taskClaims.findById(outcome.taskClaimId())
                    .map(claim -> "COMPLETED".equals(claim.status())).orElse(false); }
            catch (Exception e) { throw new AssertionError(e); }
        }));
        assertFalse(Files.exists(repository.resolve("one.txt")));
        assertFalse(Files.exists(repository.resolve("two.txt")));
        assertEquals(mainHead, GitTestSupport.gitText(repository, "rev-parse", "HEAD"));
        assertEquals(mainBranch, GitTestSupport.gitText(repository, "branch", "--show-current"));
        assertEquals(0, state.workerIntegrationDecisions.findByProposal("none").stream().count());
        assertEquals(2, first.calls());
        assertEquals(2, second.calls());
    }

    @Test void sameOccurrenceReusesWorkersCandidatesAndVerification() throws Exception {
        SequenceProvider first = new SequenceProvider(
                ModelResponse.toolCallResponse("write", new ToolCall(
                        "write_file", Map.of("path", "one.txt", "content", "one\n"))),
                ModelResponse.finalAnswer("done"));
        SequenceProvider second = new SequenceProvider(
                ModelResponse.toolCallResponse("write", new ToolCall(
                        "write_file", Map.of("path", "two.txt", "content", "two\n"))),
                ModelResponse.finalAnswer("done"));
        var request = new TwoWorkerCoordinator.TeamRequest("team-resume",
                List.of(task("first", first), task("second", second)), 120);
        var initial = coordinator().run(request);
        var resumed = coordinator().run(request);
        assertEquals("ALL_READY", initial.status(), initial.toString());
        assertEquals("ALL_READY", resumed.status(), resumed.toString());
        assertEquals(2, first.calls());
        assertEquals(2, second.calls());
        assertTrue(resumed.workers().stream().allMatch(
                outcome -> "SKIPPED_EXISTING".equals(outcome.workerStatus())));
        assertEquals(initial.workers().stream().map(TwoWorkerCoordinator.WorkerOutcome::candidateId).toList(),
                resumed.workers().stream().map(TwoWorkerCoordinator.WorkerOutcome::candidateId).toList());
    }

    @Test void oneWorkerFailureDoesNotCancelOtherReadyCandidate() throws Exception {
        SequenceProvider failing = new SequenceProvider(
                ModelResponse.toolCallResponse("forbidden", new ToolCall("run_command", Map.of())));
        SequenceProvider successful = new SequenceProvider(
                ModelResponse.toolCallResponse("write", new ToolCall(
                        "write_file", Map.of("path", "kept.txt", "content", "kept\n"))),
                ModelResponse.finalAnswer("done"));
        var result = coordinator().run(new TwoWorkerCoordinator.TeamRequest(
                "team-partial", List.of(task("fail", failing), task("keep", successful)), 120));
        assertEquals("PARTIAL", result.status(), result.toString());
        assertEquals(1, result.workers().stream()
                .filter(outcome -> "READY_FOR_DECISION".equals(outcome.candidateStatus())).count());
        assertEquals(1, result.workers().stream()
                .filter(outcome -> "FAILED".equals(outcome.workerStatus())).count());
        assertEquals(2, successful.calls());
        assertTrue(state.workerIntegrationDecisions.findByProposal("none").isEmpty());
    }
    @Test void mailboxPostIsRuntimeBoundAndPersistedAsData() throws Exception {
        SequenceProvider provider = new SequenceProvider(
                ModelResponse.toolCallResponse("mail", new ToolCall("mailbox_post",
                        Map.of("kind", "PROGRESS", "payload", "reviewed assigned file"))),
                ModelResponse.finalAnswer("done"));
        var result = coordinator().run(new TwoWorkerCoordinator.TeamRequest(
                "team-mail", List.of(task("mail", provider, "Lead fact only")), 120));

        assertEquals("COMPLETED", result.status(), result.toString());
        assertEquals(2, result.mailboxMessageCount());
        String runId = result.workers().getFirst().workerRunId();
        var messages = state.workerMailbox.findByWorkerRun(runId);
        assertTrue(messages.stream().anyMatch(message -> "WORKER".equals(message.senderType())
                && runId.equals(message.senderId()) && "LEAD".equals(message.recipientType())
                && "lead".equals(message.recipientId())));
        assertTrue(messages.stream().allMatch(message -> !message.boundedPayload().contains("[WORKER INSTRUCTION]")));
    }

    @Test void requestOverTwoTasksFailsClosed() {
        SequenceProvider provider = new SequenceProvider(ModelResponse.finalAnswer("done"));
        assertThrows(IllegalArgumentException.class, () -> new TwoWorkerCoordinator.TeamRequest(
                "too-many", List.of(task("a", provider), task("b", provider),
                task("c", provider)), 30));
    }

    @Test void expectedBaseDriftFailsBeforeSecondWorktreeCreation() throws Exception {
        String frozen = GitTestSupport.gitText(repository, "rev-parse", "HEAD");
        Files.writeString(repository.resolve("drift.txt"), "drift\n");
        GitTestSupport.git(repository, "add", "drift.txt");
        GitTestSupport.git(repository, "commit", "-m", "drift");
        var error = assertThrows(ManagedWorktreeException.class,
                () -> worktrees.createLinkedWorktree(
                        ManagedWorktreeService.CreateRequest.linkedAtBase(
                                repository, managedRoot, "drift-worker", frozen)));
        assertEquals(ManagedWorktreeException.Code.BASE_DRIFT, error.code());
        assertTrue(state.managedWorktrees.findById("drift-worker").isEmpty());
    }

    private TwoWorkerCoordinator coordinator() throws Exception {
        Config approvalConfig = new Config(".", 30, 50_000, 200_000,
                Config.MODE_WORKSPACE_WRITE, 10_000);
        Config verificationConfig = new Config(".", 30, 50_000, 200_000,
                Config.MODE_WORKSPACE_WRITE, 10_000, "mock", null, null, null,
                null, 10, isWindows() ? "exit /b 0" : "true", 10, 4096,
                1, true, true, true);
        return new TwoWorkerCoordinator(state, worktrees, repository, managedRoot,
                new ApprovalPolicy(approvalConfig, "team-parent"),
                new WorkerCandidateService(state, worktrees, repository, managedRoot,
                        verificationConfig));
    }

    private TwoWorkerCoordinator.TeamTask task(String name, ModelProvider provider) {
        return task(name, provider, null);
    }
    private TwoWorkerCoordinator.TeamTask task(String name, ModelProvider provider, String note) {
        return new TwoWorkerCoordinator.TeamTask("Implement " + name + ".", provider, "mock",
                new WorktreeWorkerConfig(name, "Use only assigned tools.", 6, 30, 4096, true), note);
    }

    private SequenceProvider writer(String path, String content, CyclicBarrier barrier) {
        return new SequenceProvider(() -> {
            try { barrier.await(); } catch (Exception e) { throw new IllegalStateException(e); }
            return ModelResponse.toolCallResponse("write", new ToolCall(
                    "write_file", Map.of("path", path, "content", content)));
        }, () -> ModelResponse.finalAnswer("done"));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }

    private static final class SequenceProvider implements ModelProvider {
        private final List<java.util.function.Supplier<ModelResponse>> responses;
        private final AtomicInteger calls = new AtomicInteger();
        @SafeVarargs SequenceProvider(java.util.function.Supplier<ModelResponse>... responses) {
            this.responses = List.of(responses);
        }
        SequenceProvider(ModelResponse... responses) {
            this.responses = new ArrayList<>();
            Arrays.stream(responses).forEach(response -> this.responses.add(() -> response));
        }
        @Override public ModelResponse chat(List<AgentMessage> messages,
                                            List<ToolDefinition> tools) {
            int index = calls.getAndIncrement();
            if (index >= responses.size()) throw new IllegalStateException("provider called too often");
            return responses.get(index).get();
        }
        int calls() { return calls.get(); }
    }
}



