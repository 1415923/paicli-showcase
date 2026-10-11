package com.paicli.worker;

import com.paicli.agent.AgentLoop;
import com.paicli.agent.AgentSegmentRequest;
import com.paicli.agent.AgentSegmentResult;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.ModelProvider;
import com.paicli.runtime.PersistenceSanitizer;
import com.paicli.safety.ApprovalPolicy;
import com.paicli.state.ManagedWorktreeStore;
import com.paicli.state.ManagedWorktreeStore.ManagedWorktreeRecord;
import com.paicli.state.StateStore;
import com.paicli.state.WorkerRunStore;
import com.paicli.state.WorkerRunStore.WorkerRunRecord;
import com.paicli.worktree.ManagedWorktreeException;
import com.paicli.worktree.ManagedWorktreeService;
import com.paicli.worktree.WorkerWorkspacePathGuard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Executes one bounded coding Worker inside a runtime-managed linked worktree. */
public final class WorktreeWorkerRunner {
    public static final String STATUS_SKIPPED_EXISTING = "SKIPPED_EXISTING";
    private static final int MAX_ERROR_BYTES = 4_096;

    private final StateStore coordinator;
    private final ManagedWorktreeService worktrees;
    private final Path repositoryRoot;
    private final Path managedRoot;
    private final ApprovalPolicy approvalPolicy;
    private final WorkerStateStoreFactory workerStores;
    private final WorkerCoordinatorBridge coordinatorBridge;
    private final PersistenceSanitizer sanitizer = new PersistenceSanitizer();

    public WorktreeWorkerRunner(StateStore coordinator,
                                ManagedWorktreeService worktrees,
                                Path repositoryRoot,
                                Path managedRoot,
                                ApprovalPolicy approvalPolicy) {
        this(coordinator, worktrees, repositoryRoot, managedRoot, approvalPolicy,
                StateStore::openWorker, WorkerCoordinatorBridge.NONE);
    }

    WorktreeWorkerRunner(StateStore coordinator,
                         ManagedWorktreeService worktrees,
                         Path repositoryRoot,
                         Path managedRoot,
                         ApprovalPolicy approvalPolicy,
                         WorkerStateStoreFactory workerStores) {
        this(coordinator, worktrees, repositoryRoot, managedRoot, approvalPolicy,
                workerStores, WorkerCoordinatorBridge.NONE);
    }

    public WorktreeWorkerRunner(StateStore coordinator,
                         ManagedWorktreeService worktrees,
                         Path repositoryRoot,
                         Path managedRoot,
                         ApprovalPolicy approvalPolicy,
                         WorkerCoordinatorBridge coordinatorBridge) {
        this(coordinator, worktrees, repositoryRoot, managedRoot, approvalPolicy,
                StateStore::openWorker, coordinatorBridge);
    }

    WorktreeWorkerRunner(StateStore coordinator,
                         ManagedWorktreeService worktrees,
                         Path repositoryRoot,
                         Path managedRoot,
                         ApprovalPolicy approvalPolicy,
                         WorkerStateStoreFactory workerStores,
                         WorkerCoordinatorBridge coordinatorBridge) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.worktrees = Objects.requireNonNull(worktrees, "worktrees");
        this.repositoryRoot = Objects.requireNonNull(repositoryRoot, "repositoryRoot")
                .toAbsolutePath().normalize();
        this.managedRoot = Objects.requireNonNull(managedRoot, "managedRoot")
                .toAbsolutePath().normalize();
        this.approvalPolicy = Objects.requireNonNull(approvalPolicy, "approvalPolicy");
        this.workerStores = Objects.requireNonNull(workerStores, "workerStores");
        this.coordinatorBridge = Objects.requireNonNull(coordinatorBridge, "coordinatorBridge");
    }

    public WorktreeWorkerResult run(WorktreeWorkerRunRequest request) {
        Objects.requireNonNull(request, "request");
        String inputHash = inputHash(request);
        ManagedWorktreeRecord worktree = findWorktree(request.managedWorktreeId());
        requireGitBackend(worktree);
        verifyManagedRoot(worktree);

        List<WorkerRunRecord> occurrence = findOccurrence(request);
        WorkerRunRecord completed = occurrence.stream()
                .filter(record -> "COMPLETED".equals(record.status()))
                .max(Comparator.comparingInt(WorkerRunRecord::attemptNumber))
                .orElse(null);
        if (completed != null) {
            if (!Objects.equals(inputHash, completed.inputHash())) {
                throw new IllegalStateException("Worker occurrence input hash drift");
            }
            WorkerStateLocation.Location location = location(
                    worktree, completed.workerRunId());
            return new WorktreeWorkerResult(
                    completed.workerRunId(), STATUS_SKIPPED_EXISTING,
                    completed.attemptNumber(), null, completed.outputHash(), false,
                    "DIRTY".equals(worktree.dirtyState()), true,
                    location.stateDatabase(), location.traceDirectory(),
                    "completed Worker occurrence reused without Provider execution");
        }
        requireActive(worktree);
        for (WorkerRunRecord terminal : occurrence) {
            if (!Objects.equals(inputHash, terminal.inputHash())) {
                throw new IllegalStateException("Worker occurrence input hash drift");
            }
        }

        int attempt = occurrence.stream().mapToInt(WorkerRunRecord::attemptNumber)
                .max().orElse(0) + 1;
        boolean hadRunningResidue = false;
        String residueRunId = null;
        for (WorkerRunRecord record : occurrence) {
            if ("RUNNING".equals(record.status()) || "PREPARING".equals(record.status())) {
                interrupt(record.workerRunId(), "interrupted Worker residue before retry");
                hadRunningResidue = true;
                residueRunId = record.workerRunId();
            }
        }

        assertUsable(request.managedWorktreeId());
        if (hadRunningResidue && inspectDirty(request.managedWorktreeId())) {
            abandon(request.managedWorktreeId(), residueRunId,
                    "dirty interrupted Worker residue requires explicit review");
            throw new IllegalStateException(
                    "Dirty interrupted Worker worktree cannot be retried automatically");
        }

        String workerRunId = UUID.randomUUID().toString();
        WorkerStateLocation.Location location = location(worktree, workerRunId);
        synchronized (coordinator) {
            createRun(request, workerRunId, attempt, inputHash);
            try {
                coordinatorBridge.beforeProvider(request, workerRunId, attempt, inputHash);
            } catch (RuntimeException e) {
                fail(workerRunId, "Worker claim preparation failed");
                throw e;
            }
            assignWorker(request.managedWorktreeId(), workerRunId);
            markPreparing(workerRunId);
        }

        AtomicBoolean cancelled = new AtomicBoolean();
        WorkerTrace trace;
        try {
            trace = new WorkerTrace(location.traceDirectory());
        } catch (RuntimeException | IOException e) {
            return failBeforeProvider(workerRunId, attempt, location,
                    "Worker trace initialization failed", e);
        }

        try (StateStore workerState = workerStores.open(
                location.stateDatabase(), location.traceDirectory())) {
            initializeWorkerTask(workerState, workerRunId, request);
            WorkerWorkspacePathGuard guard = createGuard(worktree, location);
            WorktreeWorkerToolRegistry tools = WorktreeWorkerToolRegistry.create(
                    guard, location.traceDirectory(), request.config().timeoutSeconds(),
                    request.config().maxOutputChars(), cancelled,
                    coordinatorBridge.mailboxTool(request, workerRunId));
            markRunning(workerRunId);
            trace.record("worker_started", workerRunId,
                    "provider=" + safe(request.providerName())
                            + "; tools=" + tools.toolNames());

            BoundedWorkerProvider provider = new BoundedWorkerProvider(
                    request.provider(), tools.toolNames(), request.config().maxOutputChars(),
                    cancelled, trace, workerRunId);
            Execution execution = execute(request, workerState, workerRunId,
                    tools, provider, cancelled);
            if (execution.timedOut()) {
                workerState.tasks.markFailed(workerRunId, execution.error());
                workerState.close();
                timeOut(workerRunId, execution.error());
                boolean dirty = retainAfterFailure(request.managedWorktreeId(), workerRunId,
                        execution.error());
                trace.record("worker_timed_out", workerRunId, execution.error());
                return result(workerRunId, "TIMED_OUT", attempt, null,
                        provider.rawOutputHash(), provider.outputTruncated(), dirty,
                        location, execution.error());
            }
            if (execution.error() != null) {
                workerState.tasks.markFailed(workerRunId, execution.error());
                workerState.close();
                fail(workerRunId, execution.error());
                boolean dirty = retainAfterFailure(request.managedWorktreeId(), workerRunId,
                        execution.error());
                trace.record("worker_failed", workerRunId, execution.error());
                return result(workerRunId, "FAILED", attempt, null,
                        provider.rawOutputHash(), provider.outputTruncated(), dirty,
                        location, execution.error());
            }

            AgentSegmentResult segment = execution.segment();
            String toolFailure = tools.toolFailure();
            if (toolFailure == null) {
                toolFailure = workerState.toolCalls.findByTask(workerRunId).stream()
                        .filter(call -> !call.success()).map(call -> call.error())
                        .findFirst().orElse(null);
            }
            if (toolFailure != null || !segment.completed()) {
                String error = toolFailure != null ? "Worker tool failed: " + toolFailure
                        : "Worker reached maxTurns";
                workerState.tasks.markFailed(workerRunId, safe(error));
                workerState.close();
                fail(workerRunId, error);
                boolean dirty = retainAfterFailure(request.managedWorktreeId(), workerRunId, error);
                trace.record("worker_failed", workerRunId, error);
                return result(workerRunId, "FAILED", attempt, null,
                        provider.rawOutputHash(), provider.outputTruncated(), dirty,
                        location, error);
            }

            String output = bound(segment.answer(), request.config().maxOutputChars());
            String outputHash = provider.rawOutputHash() == null
                    ? sha256(segment.answer()) : provider.rawOutputHash();
            workerState.tasks.markDone(workerRunId);
            workerState.close();
            boolean dirty = inspectDirty(request.managedWorktreeId());
            if (dirty) markDirty(request.managedWorktreeId(), workerRunId);
            else markClean(request.managedWorktreeId(), workerRunId);
            trace.record("worker_completed", workerRunId,
                    "dirty=" + dirty + "; output_hash=" + outputHash);
            complete(workerRunId, outputHash);
            return result(workerRunId, "COMPLETED", attempt, output, outputHash,
                    provider.outputTruncated(), dirty, location, "Worker execution completed");
        } catch (Exception e) {
            String error = safeError("Worker runtime failed", e);
            failIfNonTerminal(workerRunId, error);
            boolean dirty = retainAfterFailure(request.managedWorktreeId(), workerRunId, error);
            trace.record("worker_failed", workerRunId, error);
            return result(workerRunId, "FAILED", attempt, null, null, false,
                    dirty, location, error);
        }
    }

    private Execution execute(WorktreeWorkerRunRequest request, StateStore workerState,
                              String workerRunId, WorktreeWorkerToolRegistry tools,
                              BoundedWorkerProvider provider, AtomicBoolean cancelled) {
        AgentLoop loop = new AgentLoop(provider, tools.runtimeRegistry(),
                request.config().maxTurns(), approvalPolicy, null,
                workerState, workerRunId, request.providerName(), null);
        AgentSegmentRequest segment = new AgentSegmentRequest(
                request.taskSpecification(), List.<AgentMessage>of(),
                workerPrompt(request.config(), coordinatorBridge.startupContext(request, workerRunId)),
                0, request.config().maxTurns());
        ExecutorService executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("paicli-worktree-worker-" + workerRunId + "-", 0).factory());
        Future<AgentSegmentResult> future = executor.submit(() -> loop.runSegment(segment));
        try {
            return Execution.success(future.get(
                    request.config().timeoutSeconds(), TimeUnit.SECONDS));
        } catch (TimeoutException e) {
            cancelled.set(true);
            future.cancel(true);
            return Execution.timeout("WorktreeWorker timed out");
        } catch (InterruptedException e) {
            cancelled.set(true);
            future.cancel(true);
            Thread.currentThread().interrupt();
            return Execution.failure("WorktreeWorker runner interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return Execution.failure(safeError("WorktreeWorker execution failed", cause));
        } finally {
            executor.shutdownNow();
            try {
                executor.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private WorkerWorkspacePathGuard createGuard(ManagedWorktreeRecord worktree,
                                                  WorkerStateLocation.Location location)
            throws ManagedWorktreeException, IOException {
        Path commonGit = worktrees.inspectRepositoryState(repositoryRoot).commonGitDirectory();
        return new WorkerWorkspacePathGuard(Path.of(worktree.canonicalWorkspacePath()),
                commonGit, List.of(managedRoot.resolve("state"), managedRoot.resolve("traces"),
                location.stateDatabase(), location.traceDirectory()));
    }

    private String workerPrompt(WorktreeWorkerConfig config, String coordinationContext) {
        return """
                [WORKTREE WORKER RUNTIME BOUNDARY]
                You may modify only the assigned isolated workspace through the provided tools.
                Git lifecycle/control commands, shell commands, recursive workers, coordinator state,
                Worker state, traces, .git and paths outside the workspace are forbidden.
                Tool approval remains enforced by the parent ApprovalPolicy.
                [WORKER INSTRUCTION]
                %s
                %s
                """.formatted(config.systemInstruction(),
                coordinationContext == null ? "" : coordinationContext);
    }

    private WorktreeWorkerResult failBeforeProvider(String runId, int attempt,
                                                     WorkerStateLocation.Location location,
                                                     String prefix, Throwable error) {
        String message = safeError(prefix, error);
        fail(runId, message);
        return result(runId, "FAILED", attempt, null, null, false, false,
                location, message);
    }

    private boolean retainAfterFailure(String worktreeId, String runId, String error) {
        boolean dirty;
        try {
            dirty = inspectDirty(worktreeId);
        } catch (RuntimeException e) {
            dirty = true;
        }
        if (dirty) abandon(worktreeId, runId, error);
        else markClean(worktreeId, runId);
        return dirty;
    }

    private void initializeWorkerTask(StateStore state, String workerRunId,
                                      WorktreeWorkerRunRequest request) throws SQLException {
        state.tasks.createTask(workerRunId, request.taskSpecification(), request.providerName(),
                null, approvalPolicy.getApprovalMode(), request.config().maxTurns());
        state.tasks.markRunning(workerRunId);
    }

    private ManagedWorktreeRecord findWorktree(String id) {
        try {
            return coordinator.managedWorktrees.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Managed worktree not found: " + id));
        } catch (SQLException e) {
            throw new IllegalStateException("Managed worktree persistence failed", e);
        }
    }

    private void requireGitBackend(ManagedWorktreeRecord record) {
        if (!ManagedWorktreeStore.BACKEND_GIT.equals(record.backendType())) {
            throw new IllegalStateException("UNSUPPORTED_BACKEND: DIRECTORY_EXPORT Worker is disabled");
        }
    }

    private void requireActive(ManagedWorktreeRecord record) {
        if (!ManagedWorktreeStore.STATUS_ACTIVE.equals(record.status())) {
            throw new IllegalStateException("Managed worktree is not ACTIVE: " + record.status());
        }
    }

    private List<WorkerRunRecord> findOccurrence(WorktreeWorkerRunRequest request) {
        try {
            return coordinator.workerRuns.findByOccurrence(
                    request.managedWorktreeId(), request.occurrenceKey());
        } catch (SQLException e) {
            throw new IllegalStateException("Worker occurrence lookup failed", e);
        }
    }

    private void createRun(WorktreeWorkerRunRequest request, String id, int attempt,
                           String inputHash) {
        try {
            coordinator.workerRuns.create(id, null, request.taskItemId(),
                    request.managedWorktreeId(), request.occurrenceKey(), attempt,
                    request.providerName(), inputHash);
        } catch (SQLException e) {
            throw new IllegalStateException("Worker run creation failed", e);
        }
    }

    private void assignWorker(String worktreeId, String runId) {
        try { coordinator.managedWorktrees.assignWorker(worktreeId, runId); }
        catch (SQLException e) { fail(runId, "Worker ownership persistence failed"); throw new IllegalStateException(e); }
    }
    private void markPreparing(String id) { store(() -> coordinator.workerRuns.markPreparing(id), "mark PREPARING"); }
    private void markRunning(String id) { store(() -> coordinator.workerRuns.markRunning(id), "mark RUNNING"); }
    private void complete(String id, String hash) { store(() -> coordinator.workerRuns.complete(id, hash), "complete Worker"); }
    private void fail(String id, String error) { store(() -> coordinator.workerRuns.fail(id, safe(error)), "fail Worker"); }
    private void timeOut(String id, String error) { store(() -> coordinator.workerRuns.timeOut(id, safe(error)), "timeout Worker"); }
    private void interrupt(String id, String error) { store(() -> coordinator.workerRuns.interrupt(id, safe(error)), "interrupt Worker"); }
    private void markDirty(String id, String runId) { store(() -> coordinator.managedWorktrees.markDirty(id, runId), "mark worktree dirty"); }
    private void markClean(String id, String runId) { store(() -> coordinator.managedWorktrees.markActiveClean(id, runId), "mark worktree clean"); }
    private void abandon(String id, String runId, String error) {
        store(() -> coordinator.managedWorktrees.markAbandonedForWorker(
                id, runId, safe(error), true), "abandon worktree");
    }

    private void failIfNonTerminal(String id, String error) {
        try {
            WorkerRunRecord record = coordinator.workerRuns.findById(id).orElse(null);
            if (record != null && List.of("CREATED", "PREPARING", "RUNNING").contains(record.status())) {
                coordinator.workerRuns.fail(id, safe(error));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Worker terminal persistence failed", e);
        }
    }

    private void assertUsable(String id) {
        try { worktrees.assertUsable(id, repositoryRoot); }
        catch (ManagedWorktreeException e) { throw new IllegalStateException("Managed worktree drift: " + e.getMessage(), e); }
    }
    private boolean inspectDirty(String id) {
        try { return worktrees.isWorktreeDirty(id, repositoryRoot); }
        catch (ManagedWorktreeException e) { throw new IllegalStateException("Worktree dirty inspection failed", e); }
    }

    private WorkerStateLocation.Location location(ManagedWorktreeRecord record, String runId) {
        return new WorkerStateLocation(managedRoot,
                Path.of(record.canonicalWorkspacePath())).locate(runId);
    }

    private void verifyManagedRoot(ManagedWorktreeRecord record) {
        Path workspace = Path.of(record.canonicalWorkspacePath())
                .toAbsolutePath().normalize();
        Path derived = workspace.getParent() == null ? null : workspace.getParent().getParent();
        if (derived == null || !samePath(derived, managedRoot)) {
            throw new IllegalStateException(
                    "Managed root does not match worktree metadata");
        }
    }

    private static boolean samePath(Path left, Path right) {
        try {
            return Files.isSameFile(left, right);
        } catch (IOException e) {
            return left.toAbsolutePath().normalize().equals(
                    right.toAbsolutePath().normalize());
        }
    }

    private WorktreeWorkerResult result(String id, String status, int attempt,
                                        String output, String hash, boolean truncated,
                                        boolean dirty, WorkerStateLocation.Location location,
                                        String message) {
        return new WorktreeWorkerResult(id, status, attempt, output, hash, truncated,
                dirty, false, location.stateDatabase(), location.traceDirectory(), safe(message));
    }

    private String inputHash(WorktreeWorkerRunRequest request) {
        return sha256(request.managedWorktreeId() + "\n" + request.occurrenceKey()
                + "\n" + request.taskSpecification() + "\n" + request.config().name()
                + "\n" + request.config().systemInstruction() + "\n"
                + request.config().maxTurns() + ":" + request.config().timeoutSeconds()
                + ":" + request.config().maxOutputChars());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String safe(String value) {
        return sanitizer.sanitizeAndTruncate(value == null ? "" : value, MAX_ERROR_BYTES);
    }
    private static String safeError(String prefix, Throwable error) {
        String detail = error.getMessage();
        return prefix + (detail == null || detail.isBlank() ? "" : ": " + detail);
    }
    private static String bound(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }

    private void store(SqlAction action, String operation) {
        try { action.run(); }
        catch (SQLException e) { throw new IllegalStateException("Could not " + operation, e); }
    }

    @FunctionalInterface
    interface WorkerStateStoreFactory {
        StateStore open(Path stateDatabase, Path traceDirectory) throws IOException, SQLException;
    }
    @FunctionalInterface private interface SqlAction { void run() throws SQLException; }

    private record Execution(AgentSegmentResult segment, String error, boolean timedOut) {
        static Execution success(AgentSegmentResult segment) { return new Execution(segment, null, false); }
        static Execution failure(String error) { return new Execution(null, error, false); }
        static Execution timeout(String error) { return new Execution(null, error, true); }
    }

    private static final class BoundedWorkerProvider implements ModelProvider {
        private final ModelProvider delegate;
        private final List<String> allowedTools;
        private final int maxOutputChars;
        private final AtomicBoolean cancelled;
        private final WorkerTrace trace;
        private final String workerRunId;
        private volatile String rawOutputHash;
        private volatile boolean outputTruncated;

        private BoundedWorkerProvider(ModelProvider delegate, List<String> allowedTools,
                                      int maxOutputChars, AtomicBoolean cancelled,
                                      WorkerTrace trace, String workerRunId) {
            this.delegate = delegate;
            this.allowedTools = List.copyOf(allowedTools);
            this.maxOutputChars = maxOutputChars;
            this.cancelled = cancelled;
            this.trace = trace;
            this.workerRunId = workerRunId;
        }

        @Override
        public ModelResponse chat(List<AgentMessage> messages,
                                  List<ToolDefinition> definitions) {
            if (cancelled.get()) throw new IllegalStateException("Worker execution cancelled");
            trace.record("provider_request", workerRunId,
                    "messages=" + messages.size() + "; tools=" + definitions.size());
            ModelResponse response = Objects.requireNonNull(
                    delegate.chat(messages, definitions), "Provider response is required");
            if (cancelled.get()) throw new IllegalStateException("Worker execution cancelled");
            if (response.type() == ModelResponse.ResponseType.TOOL_CALL) {
                List<String> tools=response.toolCalls().stream()
                        .map(com.paicli.model.ToolCall::name).toList();
                String forbidden=tools.stream().filter(tool->tool==null
                        ||!allowedTools.contains(tool)).findFirst().orElse(null);
                if (tools.isEmpty() || forbidden != null) {
                    throw new SecurityException("Forbidden WorktreeWorker tool request: "
                            +(forbidden==null?"<missing>":forbidden));
                }
                trace.record("provider_response", workerRunId,
                        "tool_call_count="+tools.size()+"; tools="+String.join(",",tools));
                return ModelResponse.toolCallBatchResponse(truncate(response.content()),
                        response.toolCalls(),response.providerEnvelope(),
                        response.privateContinuationRequired());
            }
            if (response.type() == ModelResponse.ResponseType.PROTOCOL_ERROR) {
                trace.record("provider_response", workerRunId,
                        "type=PROTOCOL_ERROR; code="
                                + response.providerEnvelope().diagnostic().code());
                return ModelResponse.protocolError(response.providerEnvelope());
            }
            if (response.type() == ModelResponse.ResponseType.PROVIDER_ERROR) {
                trace.record("provider_response", workerRunId,
                        "type=PROVIDER_ERROR; kind=" + response.providerErrorKind());
                return ModelResponse.providerError(response.providerErrorKind());
            }
            String raw = response.content() == null ? "" : response.content();
            rawOutputHash = sha256(raw);
            trace.record("provider_response", workerRunId,
                    "type=" + response.type() + "; output_hash=" + rawOutputHash);
            return ModelResponse.finalAnswer(truncate(raw));
        }

        private String truncate(String value) {
            if (value == null || value.length() <= maxOutputChars) return value;
            outputTruncated = true;
            return value.substring(0, maxOutputChars);
        }
        String rawOutputHash() { return rawOutputHash; }
        boolean outputTruncated() { return outputTruncated; }
    }
}




