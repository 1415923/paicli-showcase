package com.paicli.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.paicli.config.Config;
import com.paicli.identity.CanonicalId;
import com.paicli.provider.ModelProvider;
import com.paicli.provider.PreparedTurnProvider;
import com.paicli.runtime.CodingTaskObserver;
import com.paicli.runtime.InstructionManifestRuntime;
import com.paicli.runtime.PlanCompatibilityObserver;
import com.paicli.runtime.RuntimeManifestHasher;
import com.paicli.runtime.TaskRecoveryService;
import com.paicli.safety.SafetyPolicy;
import com.paicli.skill.ControlPlanePaths;
import com.paicli.state.DatabaseManager;
import com.paicli.state.FileSnapshotService;
import com.paicli.state.StateStore;
import com.paicli.tool.Tool;
import com.paicli.tool.ToolAuthorityProjection;
import com.paicli.tool.ToolResult;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Executes one frozen PaiCLI-only runtime-contract scenario through production runtime seams. */
final class RuntimeContractScenarioExecutor {
    private final Path workspace;
    private final JsonNode task;
    private final ModelProvider rawProvider;
    private final Config config;
    private final PlanCompatibilityObserver planObserver;

    RuntimeContractScenarioExecutor(Path workspace, JsonNode task, ModelProvider rawProvider,
            Config config, PlanCompatibilityObserver planObserver) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.task = task;
        this.rawProvider = rawProvider;
        this.config = config;
        this.planObserver = planObserver;
    }

    Result run(String prompt, String continuation) throws Exception {
        String taskId = task.path("taskId").asText();
        String category = task.path("category").asText();
        AtomicInteger compactEvents = new AtomicInteger();
        AtomicInteger unknownWriteExecutions = new AtomicInteger();
        FirstExecution503Provider retryProvider = null;
        ModelProvider scenarioProvider = rawProvider;
        if (taskId.equals("task-029")) {
            if (!(rawProvider instanceof PreparedTurnProvider prepared)) {
                throw new IllegalStateException("runtime retry requires PreparedTurnProvider");
            }
            retryProvider = new FirstExecution503Provider(prepared);
            scenarioProvider = retryProvider;
        }
        Integer constrainedContextWindow = taskId.equals("task-027")
                ? Integer.valueOf(6000) : taskId.equals("task-028")
                ? Integer.valueOf(5750) : null;
        PaiCliBenchmarkTreatment.Options options = new PaiCliBenchmarkTreatment.Options(
                constrainedContextWindow,
                taskId.equals("task-030")
                        ? unknownWriteFactory(unknownWriteExecutions)
                        : PaiCliBenchmarkTreatment.Options.defaults().writeToolFactory(),
                ignored -> compactEvents.incrementAndGet(),
                category.equals("COMPACT") ? 6 : 1);
        Config parentConfig = taskId.equals("task-026")
                ? config.withVerification("", false) : config;
        PaiCliBenchmarkTreatment treatment = new PaiCliBenchmarkTreatment(
                workspace, scenarioProvider, parentConfig,
                new PredeclaredApprovalControl((iteration, call) -> true),
                CodingTaskObserver.NOOP, planObserver, options);
        PaiCliBenchmarkTreatment.RunResult primary;
        TaskRecoveryService.RecoveryResult recovery = null;
        try {
            primary = treatment.run(prompt);
        } catch (BenchmarkProcessInterruption interruption) {
            if (!taskId.equals("task-030")) throw interruption;
            String interruptedTaskId = latestTaskId();
            recovery = attemptRecovery(interruptedTaskId);
            primary = terminalizeInterruptedRun(interruptedTaskId);
        }
        PaiCliBenchmarkTreatment.FollowUpRunResult followUp = null;
        if (category.equals("CONTINUE")) {
            if (continuation == null || continuation.isBlank()) {
                throw new IllegalArgumentException("continuation prompt is required");
            }
            if (!followUpEligible(primary)) {
                return result(taskId, category, primary, null, recovery,
                        compactEvents.get(), retryProvider, unknownWriteExecutions.get(),
                        "FOLLOW_UP_NOT_ELIGIBLE");
            }
            followUp = treatment.continueTask(primary, continuation, config);
        }

        return result(taskId, category, primary, followUp, recovery,
                compactEvents.get(), retryProvider, unknownWriteExecutions.get(), null);
    }

    private Result result(String taskId, String category,
            PaiCliBenchmarkTreatment.RunResult primary,
            PaiCliBenchmarkTreatment.FollowUpRunResult followUp,
            TaskRecoveryService.RecoveryResult recovery, int compactEvents,
            FirstExecution503Provider retryProvider, int unknownWriteExecutions,
            String typedTerminalReason) throws Exception {
        Map<String, Boolean> assertions = assertions(taskId, primary, followUp, recovery,
                compactEvents, retryProvider, unknownWriteExecutions);
        Set<String> declared = new LinkedHashSet<>();
        task.path("oracle").path("runtimeAssertions").forEach(value ->
                declared.add(value.asText()));
        if (!assertions.keySet().equals(declared)) {
            throw new IllegalStateException("runtime assertion coverage mismatch: declared="
                    + declared + " actual=" + assertions.keySet());
        }
        PaiCliBenchmarkTreatment.RunResult terminal = followUp == null
                ? primary : followUp.child();
        Map<String, Object> metrics = metrics(taskId, primary, followUp, recovery,
                compactEvents, retryProvider, unknownWriteExecutions);
        if (typedTerminalReason != null) {
            metrics = new LinkedHashMap<>(metrics);
            metrics.put("typedDomainOutcome", typedTerminalReason);
            metrics.put("followUpEligible", false);
            metrics = java.util.Collections.unmodifiableMap(metrics);
        }
        int toolCalls = primary.toolCalls()
                + (followUp == null ? 0 : followUp.child().toolCalls());
        long providerTurns = primary.providerTurns()
                + (followUp == null ? 0 : followUp.child().providerTurns());
        long providerLatency = primary.providerLatencyMillis()
                + (followUp == null ? 0 : followUp.child().providerLatencyMillis());
        long toolLatency = primary.toolLatencyMillis()
                + (followUp == null ? 0 : followUp.child().toolLatencyMillis());
        long alignedLatency = primary.alignedExecutionLatencyMillis()
                + (followUp == null ? 0 : followUp.child().alignedExecutionLatencyMillis());
        int verificationCount = primary.structuredVerificationCount()
                + (followUp == null ? 0 : followUp.child().structuredVerificationCount());
        int repairCount = primary.structuredRepairCount()
                + (followUp == null ? 0 : followUp.child().structuredRepairCount());
        long retryCount = primary.durableRetryCount()
                + (followUp == null ? 0 : followUp.child().durableRetryCount());
        long compactCount = primary.compactCount()
                + (followUp == null ? 0 : followUp.child().compactCount());
        long duplicateOccurrences = primary.duplicateOccurrenceIdentities()
                + (followUp == null ? 0 : followUp.child().duplicateOccurrenceIdentities());
        boolean success = assertions.values().stream().allMatch(Boolean.TRUE::equals);
        String terminalReason = typedTerminalReason != null ? typedTerminalReason
                : success ? "TASK_COMPLETED"
                : category + "_ASSERTION_FAILURE";
        return new Result(taskId, category, primary, followUp, terminal,
                java.util.Collections.unmodifiableMap(new LinkedHashMap<>(assertions)),
                java.util.Collections.unmodifiableMap(new LinkedHashMap<>(metrics)),
                success, terminalReason, toolCalls,
                providerTurns, providerLatency, toolLatency, alignedLatency,
                verificationCount, repairCount, retryCount, compactCount,
                duplicateOccurrences);
    }

    private boolean followUpEligible(PaiCliBenchmarkTreatment.RunResult primary)
            throws Exception {
        try (StateStore state = new StateStore(workspace)) {
            CanonicalId parentTaskId = CanonicalId.parse(primary.taskId());
            var parent = state.r1aDurable.findTask(parentTaskId).orElseThrow();
            return state.followUps.currentEligibleParent(parent.threadId())
                    .map(anchor -> anchor.taskId().equals(parentTaskId))
                    .orElse(false);
        }
    }

    private String latestTaskId() throws Exception {
        try (StateStore state = new StateStore(workspace)) {
            return state.tasks.findLatest().orElseThrow().taskId();
        }
    }

    private PaiCliBenchmarkTreatment.RunResult terminalizeInterruptedRun(String taskId)
            throws Exception {
        try (StateStore state = new StateStore(workspace)) {
            var run = state.codingRuns.findActiveByTask(taskId).orElseThrow();
            state.codingRuns.markFailed(run.runId(),
                    "UNRESOLVED_OCCURRENCE_REQUIRES_RECONCILIATION");
            state.tasks.markFailed(taskId,
                    "UNRESOLVED_OCCURRENCE_REQUIRES_RECONCILIATION");
            return new PaiCliBenchmarkTreatment.RunResult(
                    taskId, run.runId(), false, "FAILED",
                    "Recovery stopped: unresolved write occurrence requires reconciliation",
                    null, false, 1, 0, 0, 0, 0, List.of(), 0, null, 0, null,
                    "FAILED", 0, 0, 0,
                    "UNRESOLVED_OCCURRENCE_REQUIRES_RECONCILIATION",
                    null, null, "NOT_AVAILABLE", List.of());
        }
    }

    private TaskRecoveryService.RecoveryResult attemptRecovery(String taskId) throws Exception {
        try (StateStore state = new StateStore(workspace)) {
            CanonicalId id = CanonicalId.parse(taskId);
            var ownership = state.r1aDurable.readOwnership(id).orElseThrow();
            Instant acquired = ownership.expiresAt().plusSeconds(1);
            return new TaskRecoveryService(state,
                    new InstructionManifestRuntime(state, workspace)).recover(
                    id, config, new RuntimeManifestHasher(workspace).compute(config),
                    config.maxProjectInstructionChars(),
                    new TaskRecoveryService.LeaseRequest("runtime-recovery-probe",
                            "runtime-recovery-probe", acquired,
                            acquired.plus(Duration.ofMinutes(10)), Duration.ZERO));
        }
    }

    private Map<String, Boolean> assertions(String taskId,
            PaiCliBenchmarkTreatment.RunResult primary,
            PaiCliBenchmarkTreatment.FollowUpRunResult followUp,
            TaskRecoveryService.RecoveryResult recovery, int compactEvents,
            FirstExecution503Provider retryProvider, int unknownWriteExecutions)
            throws Exception {
        Map<String, Boolean> values = new LinkedHashMap<>();
        switch (taskId) {
            case "task-025" -> {
                values.put("SAME_THREAD_CHILD_TASK", sameThreadChild(primary, followUp));
                values.put("PARENT_TOOL_REPLAY_COUNT_ZERO",
                        parentToolReplayCount(primary, followUp) == 0);
                values.put("BOUNDED_PARENT_PROJECTION", boundedParentProjection(followUp));
            }
            case "task-026" -> {
                values.put("SAME_THREAD_CHILD_TASK", sameThreadChild(primary, followUp));
                values.put("PARENT_TOOL_REPLAY_COUNT_ZERO",
                        parentToolReplayCount(primary, followUp) == 0);
                values.put("CHILD_ONLY_NECESSARY_NEW_WORK",
                        primary.toolCalls() == 2
                                && followUp != null && followUp.child().toolCalls()
                                == 1
                                && Files.readString(workspace.resolve(
                                "src/main/java/fixture/Counter.java")).contains("decrement"));
            }
            case "task-027" -> {
                values.put("COMPACT_COUNT_AT_LEAST_ONE", compactEvents >= 1
                        && primary.compactCount() >= 1);
                values.put("CONTEXT_SOURCE_CLOSURE_VALID", contextSourceClosureValid());
                values.put("TOOL_REPLAY_COUNT_ZERO",
                        primary.duplicateOccurrenceIdentities() == 0);
            }
            case "task-028" -> {
                values.put("COMPACT_COUNT_AT_LEAST_ONE", compactEvents >= 1
                        && primary.compactCount() >= 1);
                values.put("POST_COMPACT_PROVIDER_ADMISSION_VALID",
                        compactEvents >= 1 && primary.taskCompleted());
                values.put("TOOL_REPLAY_COUNT_ZERO",
                        primary.duplicateOccurrenceIdentities() == 0);
            }
            case "task-029" -> {
                List<String> generations = retryProvider == null
                        ? List.of() : retryProvider.attemptContextGenerations();
                values.put("LOGICAL_TURN_IDENTITY_STABLE", generations.size() == 2
                        && !"NONE".equals(generations.getFirst())
                        && generations.getFirst().equals(generations.getLast()));
                values.put("RETRY_WINDOW_EFFECT_COUNT_ZERO", retryProvider != null
                        && retryProvider.injectedTurnAttempts() == 2
                        && primary.toolCalls() == 1);
                values.put("FORMAL_RESULT_SINGLE", formalResultCount(primary.taskId()) == 1);
            }
            case "task-030" -> {
                String status = unresolvedWriteStatus(primary.taskId());
                values.put("OCCURRENCE_UNKNOWN_REQUIRES_RECONCILIATION",
                        Set.of("STARTED", "UNKNOWN").contains(status)
                                && recovery instanceof TaskRecoveryService.Rejected rejected
                                && rejected.status()
                                == TaskRecoveryService.RecoveryStatus.UNRESOLVED_OCCURRENCE);
                values.put("BLIND_REPLAY_COUNT_ZERO", unknownWriteExecutions == 1
                        && resumeRunCount(primary.taskId()) == 0);
                values.put("RECOVERY_FAILS_CLOSED",
                        recovery instanceof TaskRecoveryService.Rejected
                                && !primary.durableFormalResultPresent());
            }
            default -> throw new IllegalStateException("unsupported runtime fixture " + taskId);
        }
        return values;
    }

    private Map<String, Object> metrics(String taskId,
            PaiCliBenchmarkTreatment.RunResult primary,
            PaiCliBenchmarkTreatment.FollowUpRunResult followUp,
            TaskRecoveryService.RecoveryResult recovery, int compactEvents,
            FirstExecution503Provider retryProvider, int unknownWriteExecutions)
            throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("runtimeProfile", task.path("runtimeProfile").asText());
        values.put("runtimeAssertions", task.path("oracle").path("runtimeAssertions").size());
        values.put("parentTaskIdPresent", primary.taskId() != null);
        values.put("childTaskPresent", followUp != null);
        values.put("compactEvents", compactEvents);
        values.put("durableCompactCount", primary.compactCount()
                + (followUp == null ? 0 : followUp.child().compactCount()));
        values.put("duplicateOccurrenceIdentities", primary.duplicateOccurrenceIdentities()
                + (followUp == null ? 0 : followUp.child().duplicateOccurrenceIdentities()));
        values.put("retryInjected", retryProvider != null && retryProvider.injected());
        values.put("retryAttempts", retryProvider == null
                ? 0 : retryProvider.injectedTurnAttempts());
        values.put("unknownWriteExecutions", unknownWriteExecutions);
        values.put("recoveryOutcome", recovery == null ? "NOT_APPLICABLE"
                : recovery instanceof TaskRecoveryService.Rejected rejected
                ? "REJECTED_" + rejected.status().name() : "ADMITTED");
        values.put("unresolvedWriteStatus", taskId.equals("task-030")
                ? unresolvedWriteStatus(primary.taskId()) : "NOT_APPLICABLE");
        return values;
    }

    private boolean sameThreadChild(PaiCliBenchmarkTreatment.RunResult parent,
            PaiCliBenchmarkTreatment.FollowUpRunResult followUp) throws Exception {
        if (followUp == null) return false;
        try (DatabaseManager database = database();
             PreparedStatement statement = database.getConnection().prepareStatement("""
                     SELECT count(*) FROM task_identities p JOIN task_identities c
                       ON c.parent_task_id=p.task_id AND c.thread_id=p.thread_id
                     WHERE p.task_id=? AND c.task_id=?
                     """)) {
            statement.setString(1, parent.taskId());
            statement.setString(2, followUp.child().taskId());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() && row.getLong(1) == 1;
            }
        }
    }

    private int parentToolReplayCount(PaiCliBenchmarkTreatment.RunResult parent,
            PaiCliBenchmarkTreatment.FollowUpRunResult followUp) throws Exception {
        if (followUp == null) return Integer.MAX_VALUE;
        try (StateStore state = new StateStore(workspace)) {
            Set<String> parentIds = state.toolCalls.findByTask(parent.taskId()).stream()
                    .map(com.paicli.state.ToolCallStore.ToolCallRecord::toolCallId)
                    .filter(java.util.Objects::nonNull).collect(
                            java.util.stream.Collectors.toSet());
            return (int) state.toolCalls.findByTask(followUp.child().taskId()).stream()
                    .map(com.paicli.state.ToolCallStore.ToolCallRecord::toolCallId)
                    .filter(parentIds::contains).count();
        }
    }

    private boolean boundedParentProjection(PaiCliBenchmarkTreatment.FollowUpRunResult followUp)
            throws Exception {
        if (followUp == null) return false;
        long count = scalar("""
                SELECT count(*) FROM context_source_references r
                JOIN context_generations g USING(context_generation_id)
                WHERE g.task_id=? AND r.source_type='PARENT_CONTEXT_SEGMENT'
                """, followUp.child().taskId());
        return count > 0 && count <= 64;
    }

    private boolean contextSourceClosureValid() throws Exception {
        return scalar("""
                SELECT count(*) FROM context_source_references r
                LEFT JOIN context_segments s ON s.context_generation_id=r.context_generation_id
                  AND s.ordinal=r.ordinal
                WHERE s.context_generation_id IS NULL
                """) == 0;
    }

    private long formalResultCount(String taskId) throws Exception {
        return scalar("SELECT count(*) FROM task_user_results WHERE task_id=?", taskId);
    }

    private long resumeRunCount(String taskId) throws Exception {
        return scalar("SELECT count(*) FROM durable_runs WHERE task_id=? AND run_kind='RESUME'",
                taskId);
    }

    private String unresolvedWriteStatus(String taskId) throws Exception {
        try (DatabaseManager database = database();
             PreparedStatement statement = database.getConnection().prepareStatement("""
                     SELECT status FROM side_effect_occurrences
                     WHERE task_id=? AND operation_type='TOOL' AND target='write_file'
                     ORDER BY started_at DESC LIMIT 1
                     """)) {
            statement.setString(1, taskId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getString(1) : "MISSING";
            }
        }
    }

    private long scalar(String sql, String... parameters) throws Exception {
        try (DatabaseManager database = database();
             PreparedStatement statement = database.getConnection().prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setString(index + 1, parameters[index]);
            }
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalStateException("scalar query returned no row");
                return row.getLong(1);
            }
        }
    }

    private DatabaseManager database() throws Exception {
        return new DatabaseManager(workspace.resolve(".paicli/state"));
    }

    private static PaiCliBenchmarkTreatment.WriteToolFactory unknownWriteFactory(
            AtomicInteger executions) {
        return (safety, snapshots, taskId, controlPaths) -> new UnknownOutcomeWriteTool(
                new com.paicli.tool.WriteFileTool(
                        safety, snapshots, taskId, null, controlPaths), executions);
    }

    record Result(String taskId, String category,
            PaiCliBenchmarkTreatment.RunResult primary,
            PaiCliBenchmarkTreatment.FollowUpRunResult followUp,
            PaiCliBenchmarkTreatment.RunResult terminal,
            Map<String, Boolean> runtimeAssertions,
            Map<String, Object> runtimeMetrics,
            boolean runtimeContractSuccess,
            String terminalReason,
            int toolCalls, long providerTurns, long providerLatencyMillis,
            long toolLatencyMillis, long alignedExecutionLatencyMillis,
            int structuredVerificationCount, int structuredRepairCount,
            long durableRetryCount, long compactCount,
            long duplicateOccurrenceIdentities) { }

    private static final class UnknownOutcomeWriteTool implements Tool {
        private final Tool delegate;
        private final AtomicInteger executions;

        private UnknownOutcomeWriteTool(Tool delegate, AtomicInteger executions) {
            this.delegate = delegate;
            this.executions = executions;
        }

        @Override public String name() { return delegate.name(); }
        @Override public String description() { return delegate.description(); }
        @Override public ToolDefinition toDefinition() { return delegate.toDefinition(); }
        @Override public ToolResult preflightCall(ToolCall call) {
            return delegate.preflightCall(call);
        }
        @Override public Optional<ToolAuthorityProjection> authorityProjection(ToolCall call) {
            return delegate.authorityProjection(call);
        }
        @Override public ToolResult execute(ToolCall call) {
            ToolResult applied = delegate.execute(call);
            if (!applied.success()) return applied;
            executions.incrementAndGet();
            throw new BenchmarkProcessInterruption();
        }
    }

    private static final class BenchmarkProcessInterruption extends Error {
        private BenchmarkProcessInterruption() {
            super("BENCHMARK_INJECTED_PROCESS_INTERRUPTION_AFTER_WRITE");
        }
    }
}
