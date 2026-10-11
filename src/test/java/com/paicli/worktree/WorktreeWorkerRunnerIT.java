package com.paicli.worktree;

import com.paicli.config.Config;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.ModelProvider;
import com.paicli.safety.ApprovalPolicy;
import com.paicli.state.ManagedWorktreeStore;
import com.paicli.state.StateStore;
import com.paicli.worker.WorktreeWorkerConfig;
import com.paicli.worker.WorktreeWorkerRunRequest;
import com.paicli.worker.WorktreeWorkerRunner;
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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WorktreeWorkerRunnerIT {
    @TempDir Path tempDir;
    private Path repository;
    private Path managedRoot;
    private Path coordinatorRoot;
    private StateStore coordinator;
    private ManagedWorktreeService service;

    @BeforeEach
    void setUp() throws Exception {
        repository = GitTestSupport.initialize(tempDir.resolve("main"));
        managedRoot = tempDir.resolve("managed");
        coordinatorRoot = tempDir.resolve("coordinator");
        coordinator = new StateStore(coordinatorRoot);
        service = new ManagedWorktreeService(coordinator.managedWorktrees);
        service.createLinkedWorktree(ManagedWorktreeService.CreateRequest.linked(
                repository, managedRoot, "worker-one"));
    }

    @AfterEach
    void tearDown() throws Exception {
        GitTestSupport.cleanup(repository, tempDir);
        if (coordinator != null) coordinator.close();
    }

    @Test
    void workerWritesOnlyAssignedWorktreeAndPersistsIndependentTrace() throws Exception {
        String mainHead = GitTestSupport.gitText(repository, "rev-parse", "HEAD");
        String mainBranch = GitTestSupport.gitText(repository, "branch", "--show-current");
        SequenceProvider provider = new SequenceProvider(
                ModelResponse.toolCallResponse("read", new ToolCall(
                        "read_file", Map.of("path", "base.txt"))),
                ModelResponse.toolCallResponse("write", new ToolCall(
                        "write_file", Map.of("path", "worker.txt", "content", "worker\n"))),
                ModelResponse.toolCallResponse("diff", new ToolCall(
                        "git_diff", Map.of("mode", "status"))),
                ModelResponse.finalAnswer("done"));

        var result = runner().run(request("write", provider, 6, 30, 4_096));
        Path workspace = Path.of(coordinator.managedWorktrees.findById("worker-one")
                .orElseThrow().canonicalWorkspacePath());

        assertEquals("COMPLETED", result.status());
        assertTrue(result.dirty());
        assertEquals("worker\n", Files.readString(workspace.resolve("worker.txt")));
        assertFalse(Files.exists(repository.resolve("worker.txt")));
        assertEquals("base\n", Files.readString(repository.resolve("base.txt")));
        assertEquals(mainHead, GitTestSupport.gitText(repository, "rev-parse", "HEAD"));
        assertEquals(mainBranch, GitTestSupport.gitText(repository, "branch", "--show-current"));
        assertTrue(result.stateDatabase().startsWith(managedRoot.resolve("state")));
        assertTrue(result.traceDirectory().startsWith(managedRoot.resolve("traces")));
        assertFalse(result.stateDatabase().startsWith(workspace));
        assertTrue(Files.exists(result.stateDatabase()));
        assertTrue(Files.exists(result.traceDirectory().resolve("worker.jsonl")));

        try (StateStore worker = StateStore.openWorker(
                result.stateDatabase(), result.traceDirectory())) {
            assertEquals(3, worker.toolCalls.findByTask(result.workerRunId()).size());
            assertFalse(worker.events.findMessages(result.workerRunId()).isEmpty());
        }
        assertTrue(coordinator.toolCalls.findByTask(result.workerRunId()).isEmpty());
        assertTrue(coordinator.events.findMessages(result.workerRunId()).isEmpty());
        assertTrue(coordinator.workerCandidates.findById("candidate-none").isEmpty());
        assertTrue(coordinator.workerIntegrationDecisions.findByProposal("candidate-none").isEmpty());
        assertTrue(coordinator.workerMailbox.findByWorkerRun(result.workerRunId()).isEmpty());
    }

    @Test
    void forbiddenAndUnknownToolsFailClosedWithoutExecution() throws Exception {
        for (String tool : List.of("run_command", "shell", "git", "write_file_raw")) {
            SequenceProvider provider = new SequenceProvider(
                    ModelResponse.toolCallResponse("forbidden", new ToolCall(tool, Map.of())));
            var result = runner().run(request("forbidden-" + tool, provider, 2, 30, 1_024));
            assertEquals("FAILED", result.status());
            assertTrue(result.message().contains("Forbidden WorktreeWorker tool"));
        }
    }

    @Test
    void guardedWriteRejectsAbsoluteTraversalAndGitPaths() throws Exception {
        Path workspace = Path.of(coordinator.managedWorktrees.findById("worker-one")
                .orElseThrow().canonicalWorkspacePath());
        List<String> paths = List.of(
                tempDir.resolve("outside.txt").toString(),
                "../outside.txt", ".git/config");
        int index = 0;
        for (String path : paths) {
            SequenceProvider provider = new SequenceProvider(
                    ModelResponse.toolCallResponse("write", new ToolCall("write_file",
                            Map.of("path", path, "content", "blocked"))),
                    ModelResponse.finalAnswer("ignored"));
            var result = runner().run(request("bad-path-" + index++, provider, 3, 30, 1_024));
            assertEquals("FAILED", result.status());
        }
        assertFalse(Files.exists(tempDir.resolve("outside.txt")));
        assertFalse(Files.exists(workspace.resolve("outside.txt")));
    }

    @Test
    void driftAndUnsupportedBackendStopBeforeProvider() throws Exception {
        Path workspace = Path.of(coordinator.managedWorktrees.findById("worker-one")
                .orElseThrow().canonicalWorkspacePath());
        GitTestSupport.require(GitTestSupport.git(workspace, "checkout", "--detach"));
        SequenceProvider driftProvider = new SequenceProvider(ModelResponse.finalAnswer("must not run"));
        assertThrows(IllegalStateException.class,
                () -> runner().run(request("drift", driftProvider, 2, 30, 1_024)));
        assertEquals(0, driftProvider.calls());

        String sha = GitTestSupport.gitText(repository, "rev-parse", "HEAD");
        coordinator.managedWorktrees.create("directory-worker",
                ManagedWorktreeStore.BACKEND_DIRECTORY, "repo", sha, null,
                tempDir.resolve("export").toString(), tempDir.resolve("state").toString(),
                tempDir.resolve("trace").toString(), "CLEAN");
        coordinator.managedWorktrees.markActive("directory-worker", null);
        SequenceProvider directoryProvider = new SequenceProvider(ModelResponse.finalAnswer("must not run"));
        assertThrows(IllegalStateException.class, () -> runner().run(new WorktreeWorkerRunRequest(
                "directory-worker", "directory", "task", null, directoryProvider,
                "mock", config(2, 30, 1_024))));
        assertEquals(0, directoryProvider.calls());
    }

    @Test
    void timeoutAndProviderFailureLeaveTerminalRunsWithoutLateSuccess() throws Exception {
        ModelProvider slow = (messages, tools) -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException ignored) {
                // Simulate a provider that ignores cooperative interruption briefly.
            }
            return ModelResponse.finalAnswer("late success");
        };
        var timedOut = runner().run(request("timeout", slow, 2, 1, 1_024));
        assertEquals("TIMED_OUT", timedOut.status());
        Thread.sleep(100);
        assertEquals("TIMED_OUT", coordinator.workerRuns.findById(
                timedOut.workerRunId()).orElseThrow().status());

        ModelProvider broken = (messages, tools) -> {
            throw new IllegalStateException("provider unavailable");
        };
        var failed = runner().run(request("provider-failure", broken, 2, 30, 1_024));
        assertEquals("FAILED", failed.status());
        assertTrue(failed.message().contains("provider unavailable"));
        assertTrue(coordinator.workerRuns.findByOccurrence("worker-one", "provider-failure")
                .stream().noneMatch(run -> "RUNNING".equals(run.status())));
    }

    @Test
    void maxTurnsOutputLimitAndOccurrenceIdempotenceAreEnforced() throws Exception {
        AtomicInteger toolCalls = new AtomicInteger();
        ModelProvider looping = (messages, tools) -> {
            toolCalls.incrementAndGet();
            return ModelResponse.toolCallResponse("list", new ToolCall(
                    "list_files", Map.of("path", ".")));
        };
        var maxed = runner().run(request("max-turns", looping, 2, 30, 1_024));
        assertEquals("FAILED", maxed.status());
        assertEquals(2, toolCalls.get());

        SequenceProvider provider = new SequenceProvider(
                ModelResponse.finalAnswer("x".repeat(2_000)));
        WorktreeWorkerRunRequest request = request("idempotent", provider, 2, 30, 128);
        var first = runner().run(request);
        var second = runner().run(request);
        assertEquals("COMPLETED", first.status());
        assertFalse(first.dirty());
        assertEquals(128, first.outputExcerpt().length());
        assertTrue(first.outputTruncated());
        assertEquals(WorktreeWorkerRunner.STATUS_SKIPPED_EXISTING, second.status());
        assertTrue(second.reusedExisting());
        assertEquals(1, provider.calls());

        WorktreeWorkerRunRequest drift = new WorktreeWorkerRunRequest(
                "worker-one", "idempotent", "changed task", null,
                new SequenceProvider(ModelResponse.finalAnswer("unused")), "mock",
                config(2, 30, 128));
        assertThrows(IllegalStateException.class, () -> runner().run(drift));
    }

    @Test
    void runningResidueIsInterruptedAndDirtyWorkspaceIsNotRetried() throws Exception {
        String stale = "stale-worker-run";
        coordinator.workerRuns.create(stale, null, null, "worker-one",
                "residue", 1, "mock", hashFor(request(
                        "residue", new SequenceProvider(), 2, 30, 1_024)));
        coordinator.workerRuns.markPreparing(stale);
        coordinator.workerRuns.markRunning(stale);
        Path workspace = Path.of(coordinator.managedWorktrees.findById("worker-one")
                .orElseThrow().canonicalWorkspacePath());
        Files.writeString(workspace.resolve("valuable.txt"), "retain");
        SequenceProvider provider = new SequenceProvider(ModelResponse.finalAnswer("must not run"));

        assertThrows(IllegalStateException.class,
                () -> runner().run(request("residue", provider, 2, 30, 1_024)));
        assertEquals("INTERRUPTED", coordinator.workerRuns.findById(stale).orElseThrow().status());
        assertEquals(ManagedWorktreeStore.STATUS_ABANDONED,
                coordinator.managedWorktrees.findById("worker-one").orElseThrow().status());
        assertEquals("DIRTY", coordinator.managedWorktrees.findById(
                "worker-one").orElseThrow().dirtyState());
        assertTrue(Files.exists(workspace.resolve("valuable.txt")));
        assertEquals(0, provider.calls());
    }

    @Test
    void cleanRunningResidueCreatesMonotonicNewAttempt() throws Exception {
        WorktreeWorkerRunRequest request = request(
                "clean-residue", new SequenceProvider(), 2, 30, 1_024);
        coordinator.workerRuns.create("clean-stale", null, null, "worker-one",
                request.occurrenceKey(), 1, "mock", hashFor(request));
        coordinator.workerRuns.markPreparing("clean-stale");
        coordinator.workerRuns.markRunning("clean-stale");
        SequenceProvider provider = new SequenceProvider(ModelResponse.finalAnswer("completed"));

        var result = runner().run(request(
                "clean-residue", provider, 2, 30, 1_024));

        assertEquals("INTERRUPTED", coordinator.workerRuns.findById(
                "clean-stale").orElseThrow().status());
        assertEquals("COMPLETED", result.status());
        assertEquals(2, result.attemptNumber());
        assertEquals(1, provider.calls());
        assertEquals(List.of(1, 2), coordinator.workerRuns.findByOccurrence(
                        "worker-one", "clean-residue").stream()
                .map(record -> record.attemptNumber()).toList());
    }

    @Test
    void workerStateInitializationFailureStopsBeforeProvider() throws Exception {
        Files.writeString(managedRoot.resolve("state"), "blocks-directory-creation");
        SequenceProvider provider = new SequenceProvider(ModelResponse.finalAnswer("must not run"));

        var result = runner().run(request("state-failure", provider, 2, 30, 1_024));

        assertEquals("FAILED", result.status());
        assertEquals(0, provider.calls());
        assertEquals("FAILED", coordinator.workerRuns.findById(
                result.workerRunId()).orElseThrow().status());
    }

    @Test
    void separateWorkerRunsUseDifferentStateAndTraceLocations() throws Exception {
        var first = runner().run(request("isolation-one",
                new SequenceProvider(ModelResponse.finalAnswer("one")), 2, 30, 1_024));
        var second = runner().run(request("isolation-two",
                new SequenceProvider(ModelResponse.finalAnswer("two")), 2, 30, 1_024));

        assertNotEquals(first.stateDatabase(), second.stateDatabase());
        assertNotEquals(first.traceDirectory(), second.traceDirectory());
        assertTrue(Files.exists(first.stateDatabase()));
        assertTrue(Files.exists(second.stateDatabase()));
    }

    private WorktreeWorkerRunner runner() {
        Config config = new Config(".", 30, 50_000, 200_000,
                Config.MODE_WORKSPACE_WRITE, 10_000);
        return new WorktreeWorkerRunner(coordinator, service, repository, managedRoot,
                new ApprovalPolicy(config, "worker-parent"));
    }

    private WorktreeWorkerRunRequest request(String occurrence, ModelProvider provider,
                                             int turns, int timeout, int output) {
        return new WorktreeWorkerRunRequest("worker-one", occurrence,
                "Implement the bounded worker task.", null, provider, "mock",
                config(turns, timeout, output));
    }

    private WorktreeWorkerConfig config(int turns, int timeout, int output) {
        return new WorktreeWorkerConfig("worker", "Use only assigned tools.",
                turns, timeout, output, true);
    }

    private String hashFor(WorktreeWorkerRunRequest request) throws Exception {
        String value = request.managedWorktreeId() + "\n" + request.occurrenceKey()
                + "\n" + request.taskSpecification() + "\n" + request.config().name()
                + "\n" + request.config().systemInstruction() + "\n"
                + request.config().maxTurns() + ":" + request.config().timeoutSeconds()
                + ":" + request.config().maxOutputChars();
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest
                .getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static final class SequenceProvider implements ModelProvider {
        private final List<ModelResponse> responses;
        private final AtomicInteger index = new AtomicInteger();

        private SequenceProvider(ModelResponse... responses) {
            this.responses = new ArrayList<>(Arrays.asList(responses));
        }

        @Override
        public ModelResponse chat(List<AgentMessage> messages, List<ToolDefinition> tools) {
            int current = index.getAndIncrement();
            if (current >= responses.size()) {
                throw new IllegalStateException("provider called too many times");
            }
            return responses.get(current);
        }

        int calls() { return index.get(); }
    }
}
