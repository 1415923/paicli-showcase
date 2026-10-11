package com.paicli.benchmark;

import com.paicli.agent.AgentLoop;
import com.paicli.agent.AgentLoopControl;
import com.paicli.agent.AgentLoopObserver;
import com.paicli.config.Config;
import com.paicli.context.ContextModels.OperationKind;
import com.paicli.context.ContextualModelProvider;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.provider.ModelProvider;
import com.paicli.provider.telemetry.ExecutionProviderTelemetry;
import com.paicli.provider.telemetry.ProviderCallContext;
import com.paicli.runtime.CandidateVerificationGate;
import com.paicli.runtime.CodingTaskObserver;
import com.paicli.runtime.CodingTaskResult;
import com.paicli.runtime.CodingTaskRunner;
import com.paicli.runtime.InstructionManifestRuntime;
import com.paicli.runtime.PersistenceSanitizer;
import com.paicli.runtime.PlanApprovalPort;
import com.paicli.runtime.PlanCompatibilityObserver;
import com.paicli.runtime.PlanService;
import com.paicli.runtime.RuntimeManifest;
import com.paicli.runtime.RuntimeManifestHasher;
import com.paicli.runtime.TaskContinuityModels.BudgetEvidence;
import com.paicli.runtime.TaskContinuityPolicy;
import com.paicli.runtime.VerificationService;
import com.paicli.safety.ApprovalPolicy;
import com.paicli.safety.SafetyPolicy;
import com.paicli.skill.ControlPlanePaths;
import com.paicli.state.DatabaseManager;
import com.paicli.state.FileSnapshotService;
import com.paicli.state.StateStore;
import com.paicli.state.TaskStore;
import com.paicli.tool.GitDiffTool;
import com.paicli.tool.GrepTool;
import com.paicli.tool.ListFilesTool;
import com.paicli.tool.ReadFileTool;
import com.paicli.tool.RunCommandTool;
import com.paicli.tool.Tool;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.WriteFileTool;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Benchmark-only composition of PaiCLI's production treatment boundaries. The caller supplies
 * the same raw Provider adapter used by the Simple Tool Loop; context, retry telemetry,
 * durability, Plan, verification/repair, snapshots and formal Result remain PaiCLI treatment.
 */
public final class PaiCliBenchmarkTreatment {
    public static String sharedToolBatchPolicyHash() {
        return AgentLoop.sharedToolBatchPolicyHash();
    }

    public static String sharedToolBatchTelemetryHash() {
        return AgentLoop.sharedToolBatchTelemetryHash();
    }

    private final Path workspace;
    private final ModelProvider rawProvider;
    private final Config config;
    private final AgentLoopControl toolApprovalControl;
    private final CodingTaskObserver observer;
    private final PlanCompatibilityObserver planCompatibilityObserver;
    private final Options options;

    public PaiCliBenchmarkTreatment(Path workspace, ModelProvider rawProvider, Config config,
            AgentLoopControl toolApprovalControl, CodingTaskObserver observer) {
        this(workspace, rawProvider, config, toolApprovalControl, observer,
                PlanCompatibilityObserver.NOOP, Options.defaults());
    }

    public PaiCliBenchmarkTreatment(Path workspace, ModelProvider rawProvider, Config config,
            AgentLoopControl toolApprovalControl, CodingTaskObserver observer,
            PlanCompatibilityObserver planCompatibilityObserver) {
        this(workspace, rawProvider, config, toolApprovalControl, observer,
                planCompatibilityObserver, Options.defaults());
    }

    public PaiCliBenchmarkTreatment(Path workspace, ModelProvider rawProvider, Config config,
            AgentLoopControl toolApprovalControl, CodingTaskObserver observer,
            PlanCompatibilityObserver planCompatibilityObserver, Options options) {
        this.workspace = Objects.requireNonNull(workspace).toAbsolutePath().normalize();
        this.rawProvider = Objects.requireNonNull(rawProvider);
        this.config = Objects.requireNonNull(config);
        this.toolApprovalControl = Objects.requireNonNull(toolApprovalControl);
        this.observer = Objects.requireNonNull(observer);
        this.planCompatibilityObserver = Objects.requireNonNull(planCompatibilityObserver);
        this.options = Objects.requireNonNull(options);
    }

    public RunResult run(String userTask) throws Exception {
        long started = System.nanoTime();
        MetricsObserver metrics = new MetricsObserver();
        CanonicalId taskId = CanonicalId.newId(EntityFamily.TASK);
        CodingTaskResult result;
        RunResult captured;
        try (StateStore state = new StateStore(workspace)) {
            PersistenceSanitizer sanitizer = new PersistenceSanitizer();
            RuntimeManifest runtimeManifest = new RuntimeManifestHasher(workspace).compute(config);
            java.util.function.Supplier<ProviderCallContext> telemetryContext = () -> {
                try {
                    var pointer = state.r1aDurable.readContextPointer(taskId).orElse(null);
                    return new ProviderCallContext(null, taskId.value(),
                            pointer == null ? null : pointer.ownerRunId().value(),
                            pointer == null ? null : pointer.generationId().value());
                } catch (Exception ignored) {
                    return new ProviderCallContext(null, taskId.value(), null, null);
                }
            };
            var instrumented = ExecutionProviderTelemetry.instrument(rawProvider, rawProvider,
                    state.providerTelemetry, config.effectiveProvider(), config.model(),
                    telemetryContext, config.isTaskContinuityEnabled());
            ModelProvider chat = contextual(instrumented.chat(), state, taskId,
                    OperationKind.CHAT, false, config);
            ModelProvider plan = contextual(instrumented.plan(), state, taskId,
                    OperationKind.PLAN, false, config);
            SafetyPolicy safety = new SafetyPolicy(workspace);
            FileSnapshotService snapshots = new FileSnapshotService(workspace, state.snapshots);
            ControlPlanePaths controlPaths = new ControlPlanePaths(workspace,
                    config.effectiveSkillsDirs(), List.of(config.effectiveHooksConfigPath(),
                            ".paicli/config.json"));

            CodingTaskRunner runner = new CodingTaskRunner(state,
                    new PlanService(plan, state.plans, planCompatibilityObserver),
                    new VerificationService(workspace, state.verifications, sanitizer,
                            config.effectiveVerificationTimeoutSeconds(),
                            config.effectiveMaxVerificationOutputBytes()),
                    managedTask -> new AgentLoop(chat,
                            tools(safety, snapshots, managedTask, controlPaths),
                            config.effectiveMaxIterations(),
                            new ApprovalPolicy(config, managedTask), null, state, managedTask,
                            config.effectiveProvider(), config.model(), metrics,
                            toolApprovalControl),
                    config, sanitizer, null, null, runtimeManifest, observer,
                    CandidateVerificationGate.ALLOW,
                    new InstructionManifestRuntime(state, workspace),
                    (revision, summary) -> new PlanApprovalPort.Decision(
                            true, "BENCHMARK_PREDECLARED_PLAN_CONFIRMATION",
                            "benchmark-user", "benchmark-plan-v1"));
            if (options.continuityPolicy().enabled()) {
                long continuityStarted = started;
                runner.withTaskContinuityPolicy(options.continuityPolicy(),
                        (managedTask, managedRun) -> continuityBudgetEvidence(
                                state, managedTask, options.providerDisabledContinuityBudget(),
                                continuityStarted, workspace));
            } else if (config.isTaskContinuityEnabled()) {
                com.paicli.runtime.ProductionTaskContinuityWiring.configure(
                        runner, config, state);
            }
            result = runner.run(taskId.value(), userTask);
            captured = capture(state, taskId, result, metrics,
                    (System.nanoTime() - started) / 1_000_000L);
        }
        return captured;
    }

    /** Executes an explicitly admitted same-Thread Child using the production Follow-up path. */
    public FollowUpRunResult continueTask(RunResult parent, String continuation,
            Config childConfig) throws Exception {
        Objects.requireNonNull(parent);
        Objects.requireNonNull(continuation);
        Objects.requireNonNull(childConfig);
        long started = System.nanoTime();
        MetricsObserver metrics = new MetricsObserver();
        try (StateStore state = new StateStore(workspace)) {
            CanonicalId parentTask = CanonicalId.parse(parent.taskId());
            var durableParent = state.r1aDurable.findTask(parentTask).orElseThrow();
            InstructionManifestRuntime manifests = new InstructionManifestRuntime(state, workspace);
            RuntimeManifest runtimeManifest = new RuntimeManifestHasher(workspace).compute(childConfig);
            var followUps = new com.paicli.runtime.FollowUpService(state, manifests,
                    childConfig, runtimeManifest, childConfig.maxProjectInstructionChars());
            Instant admittedAt = Instant.now();
            var admitted = followUps.begin(durableParent.threadId(), continuation, true,
                    "benchmark-runtime-contract", "benchmark-runtime-contract", admittedAt);
            if (admitted.child() == null) {
                throw new IllegalStateException("FOLLOW_UP_NOT_ADMITTED:" + admitted.code());
            }
            Duration leaseDuration = Duration.ofSeconds(Math.multiplyExact(
                    (long) childConfig.commandTimeoutSeconds(),
                    (long) childConfig.effectiveMaxIterations()));
            var claimed = state.followUps.claimInitialChildStart(admitted.child(),
                    "runtime-child-" + UUID.randomUUID(), "runtime-child-" + UUID.randomUUID(),
                    admittedAt.plusMillis(1), admittedAt.plus(leaseDuration), Duration.ZERO);
            if (claimed.status() != com.paicli.state.FollowUpModels.ChildStartStatus.CLAIMED
                    || claimed.claim() == null) {
                throw new IllegalStateException("FOLLOW_UP_CHILD_CLAIM_" + claimed.status());
            }
            if (!state.followUps.markInitialChildStarted(
                    claimed.claim(), admittedAt.plusMillis(2))) {
                throw new IllegalStateException("FOLLOW_UP_CHILD_START_REJECTED");
            }
            CanonicalId childTask = admitted.child().taskId();
            var storedRun = state.codingRuns.findById(admitted.child().runId().value())
                    .orElseThrow();
            var runtimeRun = new com.paicli.state.RootAdmissionTransaction.RuntimeRun(
                    storedRun.runId(), storedRun.taskId(), storedRun.runKind(), storedRun.phase(),
                    storedRun.verificationCommand(), storedRun.maxRepairAttempts(),
                    storedRun.startedIteration(), storedRun.skillsManifestSha256(),
                    storedRun.hooksManifestSha256());
            PersistenceSanitizer sanitizer = new PersistenceSanitizer();
            java.util.function.Supplier<ProviderCallContext> telemetryContext = () -> {
                try {
                    var pointer = state.r1aDurable.readContextPointer(childTask).orElse(null);
                    return new ProviderCallContext(null, childTask.value(),
                            pointer == null ? null : pointer.ownerRunId().value(),
                            pointer == null ? null : pointer.generationId().value());
                } catch (Exception ignored) {
                    return new ProviderCallContext(null, childTask.value(), null, null);
                }
            };
            var instrumented = ExecutionProviderTelemetry.instrument(rawProvider, rawProvider,
                    state.providerTelemetry, childConfig.effectiveProvider(), childConfig.model(),
                    telemetryContext);
            ModelProvider chat = contextual(instrumented.chat(), state, childTask,
                    OperationKind.CHAT, true, childConfig);
            ModelProvider plan = contextual(instrumented.plan(), state, childTask,
                    OperationKind.PLAN, true, childConfig);
            SafetyPolicy safety = new SafetyPolicy(workspace);
            FileSnapshotService snapshots = new FileSnapshotService(workspace, state.snapshots);
            ControlPlanePaths controlPaths = new ControlPlanePaths(workspace,
                    childConfig.effectiveSkillsDirs(), List.of(
                    childConfig.effectiveHooksConfigPath(), ".paicli/config.json"));
            CodingTaskRunner runner = new CodingTaskRunner(state,
                    new PlanService(plan, state.plans, planCompatibilityObserver),
                    new VerificationService(workspace, state.verifications, sanitizer,
                            childConfig.effectiveVerificationTimeoutSeconds(),
                            childConfig.effectiveMaxVerificationOutputBytes()),
                    managedTask -> new AgentLoop(chat,
                            tools(safety, snapshots, managedTask, controlPaths),
                            childConfig.effectiveMaxIterations(),
                            new ApprovalPolicy(childConfig, managedTask), null, state, managedTask,
                            childConfig.effectiveProvider(), childConfig.model(), metrics,
                            toolApprovalControl),
                    childConfig, sanitizer, null, null, runtimeManifest, observer,
                    CandidateVerificationGate.ALLOW, manifests,
                    (revision, summary) -> new PlanApprovalPort.Decision(
                            true, "BENCHMARK_PREDECLARED_PLAN_CONFIRMATION",
                            "benchmark-user", "benchmark-plan-v1"));
            CodingTaskResult result = runner.runAdmittedChild(childTask.value(), continuation,
                    manifests.loadFrozen(childTask).prompt(), runtimeRun, claimed.claim());
            RunResult child = capture(state, childTask, result, metrics,
                    (System.nanoTime() - started) / 1_000_000L);
            return new FollowUpRunResult(parent, child, durableParent.threadId().value(),
                    admitted.candidate().capsuleId().value());
        }
    }

    private ModelProvider contextual(ModelProvider provider, StateStore state,
            CanonicalId taskId, OperationKind operation, boolean child,
            Config runtimeConfig) {
        var normal = ContextualModelProvider.capability(runtimeConfig.effectiveProvider(),
                runtimeConfig.model(), runtimeConfig.effectiveContextWindowTokens());
        if (operation == OperationKind.PLAN || options.chatContextWindowTokens() == null) {
            return new ContextualModelProvider(provider, state, workspace, taskId.value(),
                    runtimeConfig.effectiveProvider(), runtimeConfig.model(), operation,
                    normal, child, runtimeConfig.effectiveSystemPrompt(),
                    options.compactionObserver());
        }
        var postTool = ContextualModelProvider.capability(runtimeConfig.effectiveProvider(),
                runtimeConfig.model(), options.chatContextWindowTokens());
        var toolContinuations = new java.util.concurrent.atomic.AtomicInteger();
        return new ContextualModelProvider(provider, state, workspace, taskId.value(),
                runtimeConfig.effectiveProvider(), runtimeConfig.model(), operation,
                explicit -> explicit == OperationKind.TOOL_CONTINUATION
                        && toolContinuations.incrementAndGet()
                        >= options.constrainedToolContinuationStart()
                        ? postTool : normal, child,
                runtimeConfig.effectiveSystemPrompt(), options.compactionObserver());
    }

    private ToolRegistry tools(SafetyPolicy safety, FileSnapshotService snapshots,
            String taskId, ControlPlanePaths controlPaths) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ListFilesTool(safety));
        registry.register(new ReadFileTool(safety));
        registry.register(new GrepTool(safety));
        registry.register(new RunCommandTool(safety));
        registry.register(new GitDiffTool(safety));
        registry.register(options.writeToolFactory().create(
                safety, snapshots, taskId, controlPaths));
        return registry;
    }

    private RunResult capture(StateStore state, CanonicalId taskId, CodingTaskResult result,
            MetricsObserver metrics, long elapsedMillis) throws Exception {
        var task = state.tasks.findById(taskId.value()).orElseThrow();
        var run = state.codingRuns.findById(result.runId()).orElseThrow();
        var formal = state.userResults.find(taskId, CanonicalId.parse(result.runId()));
        List<com.paicli.state.VerificationStore.VerificationRecord> verifications =
                state.verifications.findByRun(result.runId());
        int repairCount = state.repairAttempts.findByRun(result.runId()).size();
        ProviderAttemptUsageSummary providerUsage;
        long compactCount;
        long duplicateOccurrenceIdentities;
        String terminalFailureClass;
        try (DatabaseManager database = new DatabaseManager(
                workspace.resolve(".paicli/state"))) {
            try (PreparedStatement statement = database.getConnection().prepareStatement("""
                    SELECT latency_ms,retry_count,usage_source,usage_coverage,
                           input_tokens,output_tokens
                    FROM provider_call_telemetry WHERE task_id=? ORDER BY started_at
                    """)) {
                statement.setString(1, taskId.value());
                try (ResultSet row = statement.executeQuery()) {
                    List<ProviderAttemptUsage> attempts = new ArrayList<>();
                    while (row.next()) {
                        long input = row.getLong(5);
                        Long inputTokens = row.wasNull() ? null : input;
                        long output = row.getLong(6);
                        Long outputTokens = row.wasNull() ? null : output;
                        attempts.add(new ProviderAttemptUsage(row.getLong(1), row.getLong(2),
                                row.getString(3), row.getString(4), inputTokens, outputTokens));
                    }
                    providerUsage = summarizeProviderAttemptUsage(attempts,
                            options.allowMixedAttemptUsageAuthority());
                }
            }
            try (PreparedStatement statement = database.getConnection().prepareStatement(
                    "SELECT count(*) FROM context_generations "
                            + "WHERE task_id=? AND generation_kind='COMPACTED'")) {
                statement.setString(1, taskId.value());
                try (ResultSet row = statement.executeQuery()) {
                    row.next();
                    compactCount = row.getLong(1);
                }
            }
            try (PreparedStatement statement = database.getConnection().prepareStatement("""
                    SELECT count(*) FROM (
                      SELECT logical_operation_key FROM side_effect_occurrences
                      WHERE task_id=?
                      GROUP BY logical_operation_key HAVING count(*)>1)
                    """)) {
                statement.setString(1, taskId.value());
                try (ResultSet row = statement.executeQuery()) {
                    row.next();
                    duplicateOccurrenceIdentities = row.getLong(1);
                }
            }
            try (PreparedStatement statement = database.getConnection().prepareStatement("""
                    SELECT failure_class FROM plan_step_attempts
                    WHERE task_id=? AND run_id=? AND state='FAILED'
                    ORDER BY finished_at DESC,attempt_number DESC LIMIT 1
                    """)) {
                statement.setString(1, taskId.value());
                statement.setString(2, result.runId());
                try (ResultSet row = statement.executeQuery()) {
                    terminalFailureClass = row.next() ? row.getString(1) : null;
                }
            }
        }
        return new RunResult(taskId.value(), result.runId(),
                TaskStore.STATUS_DONE.equals(task.status()), run.phase(), result.summary(),
                formal.map(com.paicli.state.UserResultStore.Result::content).orElse(null),
                formal.isPresent(), state.toolCalls.findByTask(taskId.value()).size(),
                providerUsage.providerTurns(), providerUsage.providerLatencyMillis(),
                metrics.toolLatencyMillis, elapsedMillis,
                List.copyOf(metrics.approvalDecisionFacts),
                verifications.size(), verifications.stream().findFirst()
                        .map(value -> "PASSED".equals(value.status())).orElse(null),
                repairCount, repairCount == 0 ? null : TaskStore.STATUS_DONE.equals(task.status()),
                result.finalStatus(), providerUsage.retryCount(), compactCount,
                duplicateOccurrenceIdentities, terminalFailureClass,
                providerUsage.inputTokens(), providerUsage.outputTokens(),
                providerUsage.usageSource(),
                List.copyOf(metrics.effectfulBatchTelemetry));
    }

    static ProviderAttemptUsageSummary summarizeProviderAttemptUsage(
            List<ProviderAttemptUsage> attempts, boolean allowMixedAuthority) {
        long latency = 0;
        long retries = 0;
        long authoritativeInput = 0;
        long authoritativeOutput = 0;
        long derivedInput = 0;
        long derivedOutput = 0;
        int authoritative = 0;
        int conservative = 0;
        int derived = 0;
        for (ProviderAttemptUsage attempt : attempts) {
            latency += attempt.latencyMillis();
            retries += attempt.retryCount();
            boolean complete = "COMPLETE".equals(attempt.usageCoverage())
                    && attempt.inputTokens() != null && attempt.inputTokens() >= 0
                    && attempt.outputTokens() != null && attempt.outputTokens() >= 0;
            boolean absent = "NONE".equals(attempt.usageCoverage())
                    && attempt.inputTokens() == null && attempt.outputTokens() == null;
            if ("PROVIDER_REPORTED".equals(attempt.usageSource()) && complete) {
                authoritative++;
                authoritativeInput += attempt.inputTokens();
                authoritativeOutput += attempt.outputTokens();
            } else if ("DERIVED".equals(attempt.usageSource()) && complete) {
                derived++;
                derivedInput += attempt.inputTokens();
                derivedOutput += attempt.outputTokens();
            } else if (("NOT_EXPOSED".equals(attempt.usageSource())
                    || "NOT_AVAILABLE".equals(attempt.usageSource())) && absent) {
                conservative++;
            } else {
                throw new IllegalStateException("Provider attempt usage authority is invalid");
            }
        }
        if (attempts.isEmpty() || conservative == attempts.size()) {
            return new ProviderAttemptUsageSummary(attempts.size(), latency, retries,
                    null, null, "NOT_AVAILABLE", authoritative, conservative);
        }
        if (authoritative == attempts.size()) {
            return new ProviderAttemptUsageSummary(attempts.size(), latency, retries,
                    authoritativeInput, authoritativeOutput, "PROVIDER_REPORTED",
                    authoritative, conservative);
        }
        if (derived == attempts.size()) {
            return new ProviderAttemptUsageSummary(attempts.size(), latency, retries,
                    derivedInput, derivedOutput, "ESTIMATED", authoritative, conservative);
        }
        if (allowMixedAuthority && authoritative > 0 && conservative > 0
                && authoritative + conservative == attempts.size()) {
            return new ProviderAttemptUsageSummary(attempts.size(), latency, retries,
                    authoritativeInput, authoritativeOutput,
                    "MIXED_AUTHORITATIVE_AND_CONSERVATIVE", authoritative, conservative);
        }
        throw new IllegalStateException("Provider usage provenance is mixed");
    }

    public record FollowUpRunResult(RunResult parent, RunResult child,
            String threadId, String capsuleId) { }

    public record Options(Integer chatContextWindowTokens,
            WriteToolFactory writeToolFactory,
            java.util.function.Consumer<com.paicli.context.CompactionCoordinator.Outcome>
                    compactionObserver,
            int constrainedToolContinuationStart,
            TaskContinuityPolicy continuityPolicy,
            boolean providerDisabledContinuityBudget,
            boolean allowMixedAttemptUsageAuthority) {
        public Options {
            Objects.requireNonNull(writeToolFactory);
            Objects.requireNonNull(compactionObserver);
            Objects.requireNonNull(continuityPolicy);
            if (chatContextWindowTokens != null && chatContextWindowTokens < 1024) {
                throw new IllegalArgumentException("chat context window must be at least 1024");
            }
            if (constrainedToolContinuationStart < 1) {
                throw new IllegalArgumentException(
                        "constrained Tool continuation start must be positive");
            }
        }

        public Options(Integer chatContextWindowTokens, WriteToolFactory writeToolFactory,
                java.util.function.Consumer<com.paicli.context.CompactionCoordinator.Outcome>
                        compactionObserver, int constrainedToolContinuationStart) {
            this(chatContextWindowTokens, writeToolFactory, compactionObserver,
                    constrainedToolContinuationStart, TaskContinuityPolicy.disabled(), false,
                    false);
        }

        public Options(Integer chatContextWindowTokens, WriteToolFactory writeToolFactory,
                java.util.function.Consumer<com.paicli.context.CompactionCoordinator.Outcome>
                        compactionObserver, int constrainedToolContinuationStart,
                TaskContinuityPolicy continuityPolicy,
                boolean providerDisabledContinuityBudget) {
            this(chatContextWindowTokens, writeToolFactory, compactionObserver,
                    constrainedToolContinuationStart, continuityPolicy,
                    providerDisabledContinuityBudget, false);
        }

        public static Options defaults() {
            return new Options(null,
                    (safety, snapshots, taskId, controlPaths) -> new WriteFileTool(
                            safety, snapshots, taskId, null, controlPaths),
                    ignored -> { }, 1, TaskContinuityPolicy.disabled(), false, false);
        }

        public static Options finalInternalAcceptanceV4() {
            Options defaults = defaults();
            return new Options(defaults.chatContextWindowTokens(),
                    defaults.writeToolFactory(), defaults.compactionObserver(),
                    defaults.constrainedToolContinuationStart(), defaults.continuityPolicy(),
                    defaults.providerDisabledContinuityBudget(), true);
        }
    }

    record ProviderAttemptUsage(long latencyMillis, long retryCount, String usageSource,
            String usageCoverage, Long inputTokens, Long outputTokens) { }

    record ProviderAttemptUsageSummary(long providerTurns, long providerLatencyMillis,
            long retryCount, Long inputTokens, Long outputTokens, String usageSource,
            int authoritativeAttemptCount, int conservativeAttemptCount) { }

    private static BudgetEvidence continuityBudgetEvidence(StateStore state, String taskId,
            boolean providerDisabled, long startedNanos, Path workspace) {
        try {
            int providerTurns;
            long inputTokens;
            long outputTokens;
            boolean complete;
            try (DatabaseManager database = new DatabaseManager(
                    workspace.resolve(".paicli/state"));
                 PreparedStatement statement = database.getConnection().prepareStatement("""
                    SELECT count(*),coalesce(sum(input_tokens),0),
                           coalesce(sum(output_tokens),0),
                           count(input_tokens),count(output_tokens),
                           coalesce(min(usage_source),'NOT_EXPOSED'),
                           coalesce(max(usage_source),'NOT_EXPOSED')
                    FROM provider_call_telemetry WHERE task_id=?
                    """)) {
                statement.setString(1, taskId);
                try (ResultSet row = statement.executeQuery()) {
                    row.next();
                    providerTurns = row.getInt(1);
                    inputTokens = row.getLong(2);
                    outputTokens = row.getLong(3);
                    complete = providerDisabled || (providerTurns > 0
                            && row.getInt(4) == providerTurns && row.getInt(5) == providerTurns
                            && "PROVIDER_REPORTED".equals(row.getString(6))
                            && "PROVIDER_REPORTED".equals(row.getString(7)));
                }
            }
            int toolCalls = state.toolCalls.findByTask(taskId).size();
            long elapsed = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
            return new BudgetEvidence(complete, providerTurns, toolCalls,
                    providerDisabled ? 0L : inputTokens,
                    providerDisabled ? 0L : outputTokens, elapsed,
                    complete ? List.of() : List.of(
                            "AUTHORITATIVE_PROVIDER_USAGE_INCOMPLETE"));
        } catch (Exception error) {
            return BudgetEvidence.unavailable("CONTINUITY_CALIBRATION_BUDGET_READ_FAILED");
        }
    }

    @FunctionalInterface
    public interface WriteToolFactory {
        Tool create(SafetyPolicy safety, FileSnapshotService snapshots,
                String taskId, ControlPlanePaths controlPaths);
    }

    public record RunResult(String taskId, String runId, boolean taskCompleted,
            String runPhase, String summary, String durableFormalResult,
            boolean durableFormalResultPresent, int toolCalls, long providerTurns,
            long providerLatencyMillis, long toolLatencyMillis, long alignedExecutionLatencyMillis,
            List<String> approvalDecisionFacts,
            int structuredVerificationCount, Boolean firstStructuredVerificationPassed,
            int structuredRepairCount, Boolean structuredRepairSuccess,
            String repairLifecycleState, long durableRetryCount, long compactCount,
            long duplicateOccurrenceIdentities, String terminalFailureClass,
            Long inputTokens, Long outputTokens, String usageSource,
            List<AgentLoopObserver.EffectfulBatchFact> effectfulBatchTelemetry) {
        public RunResult {
            effectfulBatchTelemetry = List.copyOf(effectfulBatchTelemetry);
        }
    }

    private static final class MetricsObserver implements AgentLoopObserver {
        private long toolLatencyMillis;
        private final java.util.ArrayList<String> approvalDecisionFacts =
                new java.util.ArrayList<>();
        private final java.util.ArrayList<EffectfulBatchFact> effectfulBatchTelemetry =
                new java.util.ArrayList<>();

        @Override
        public void toolDecision(String taskId, int iteration, ToolRequestFact request,
                com.paicli.safety.ApprovalDecision decision) {
            approvalDecisionFacts.add(iteration + ":" + request.sameRequestOrdinal() + ":"
                    + request.toolName() + ":" + decision.decision().name() + ":"
                    + decision.riskLevel().name());
        }

        @Override
        public void toolCompleted(String taskId, int iteration, ToolRequestFact request,
                ToolCompletionFact result) {
            toolLatencyMillis += result.elapsedMillis();
        }

        @Override
        public void effectfulBatchNormalized(String taskId, int iteration,
                EffectfulBatchFact fact) {
            effectfulBatchTelemetry.add(fact);
        }
    }
}
