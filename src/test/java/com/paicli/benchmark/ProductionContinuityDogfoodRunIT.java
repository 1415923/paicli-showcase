package com.paicli.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.Config;
import com.paicli.config.ConfigLoader;
import com.paicli.provider.ModelProvider;
import com.paicli.provider.ProviderFactory;
import com.paicli.provider.telemetry.AppendOnlyProviderCallLedger;
import com.paicli.provider.telemetry.ProviderCallLedgerContext;
import com.paicli.provider.telemetry.ProviderCallLedgerModelProvider;
import com.paicli.runtime.DurableTaskBudgetEvidenceSource;
import com.paicli.runtime.PlanCompatibilityObserver;
import com.paicli.runtime.TaskContinuityPolicy;
import com.paicli.state.DatabaseManager;
import com.paicli.state.StateStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.fail;

/** One explicit production opt-in dogfood row; excluded from ordinary test discovery. */
class ProductionContinuityDogfoodRunIT {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void executeOneDogfoodRun() throws Exception {
        boolean providerDisabled = Boolean.parseBoolean(required("paicli.dogfood.providerDisabled"));
        boolean authorized = Boolean.parseBoolean(required("paicli.dogfood.authorized"));
        if (providerDisabled == authorized) fail("paid authorization must be the sole transport delta");
        Path project = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path evidenceRoot = project.resolve("target/autumn-evidence").normalize();
        Path workspace = requiredPath("paicli.dogfood.workspace", evidenceRoot);
        Path controlHome = requiredPath("paicli.dogfood.controlHome", evidenceRoot);
        Path output = requiredPath("paicli.dogfood.output", evidenceRoot);
        Path ledger = requiredPath("paicli.dogfood.providerLedger", evidenceRoot);
        Path taskPath = Path.of(required("paicli.dogfood.task")).toAbsolutePath().normalize();
        Path fixtureRoot = project.resolve("benchmarks/coding/fixtures").normalize();
        if (!taskPath.startsWith(fixtureRoot) || !"task.json".equals(taskPath.getFileName().toString()))
            fail("dogfood task must come from the frozen COMMON fixture family");
        JsonNode task = JSON.readTree(taskPath.toFile());
        String taskId = required("paicli.dogfood.taskId");
        if (!taskId.equals(task.path("taskId").asText())) fail("dogfood task identity drift");

        Config config = taskConfig(new ConfigLoader(workspace, controlHome).load(), task);
        requireProvider(config);
        if (!config.isTaskContinuityEnabled() || config.effectiveMaxIterations() != 10)
            fail("dogfood must use explicit continuity and natural max_iterations=10");
        Config.TaskContinuityConfig value = config.taskContinuity();
        TaskContinuityPolicy policy = new TaskContinuityPolicy(true,
                value.maxAdditionalSegments(), value.maxPlanAttempts(),
                value.maxTotalProviderTurns(), value.maxTotalToolCalls(),
                value.maxInputTokens(), value.maxOutputTokens(), value.maxActiveElapsedMillis());
        if (!required("paicli.dogfood.policyHash").equals(policy.policyHash()))
            fail("dogfood policy hash drift");

        String runToken = required("paicli.dogfood.processSafeRunToken");
        ModelProvider raw = providerDisabled
                ? new ProductionContinuityDogfoodReplayProvider(taskId, task)
                : new ProviderCallLedgerModelProvider(ProviderFactory.create(config),
                    new AppendOnlyProviderCallLedger(ledger, evidenceRoot),
                    new ProviderCallLedgerContext(runToken, "PAICLI",
                            config.effectiveProvider() + "/" + config.providerPreset(),
                            config.model(), required("paicli.dogfood.protocolHash")));
        String prompt = Files.readString(taskPath.getParent().resolve(task.path("promptFile").asText()));
        var run = new PaiCliBenchmarkTreatment(workspace, raw, config,
                new PredeclaredApprovalControl((iteration, call) -> true),
                com.paicli.runtime.CodingTaskObserver.NOOP,
                PlanCompatibilityObserver.NOOP).run(prompt);

        Map<String,Object> facts = new LinkedHashMap<>();
        facts.put("schemaVersion", "production-continuity-dogfood-driver-v1");
        facts.put("candidateIdentity", required("paicli.dogfood.candidateIdentity"));
        facts.put("resultVersion", required("paicli.dogfood.resultVersion"));
        facts.put("taskId", taskId);
        facts.put("taskIdentity", run.taskId());
        facts.put("runIdentity", run.runId());
        facts.put("taskSuccess", run.taskCompleted());
        facts.put("providerTurns", run.providerTurns());
        facts.put("executedToolCalls", run.toolCalls());
        facts.put("inputTokens", run.inputTokens());
        facts.put("outputTokens", run.outputTokens());
        facts.put("activeElapsedMillis", run.alignedExecutionLatencyMillis());
        facts.put("actualUserInterventionCount", 0);
        facts.put("actualUserInterventionEvidenceSource", "NON_INTERACTIVE_DRIVER_NO_HUMAN_INPUT_CHANNEL");
        facts.put("approvalPolicyDecisionCount", run.approvalDecisionFacts().size());
        facts.put("globalContinuityDefault", "DISABLED");
        facts.put("continuity", "ENABLED_EXPLICITLY");
        facts.put("policyIdentity", policy.policyHash());
        facts.putAll(durableFacts(workspace, run.taskId(), run.runId(), policy));
        Files.createDirectories(output.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), facts);
    }

    private static Map<String,Object> durableFacts(Path workspace, String taskId, String runId,
            TaskContinuityPolicy policy) throws Exception {
        Map<String,Object> facts = new LinkedHashMap<>();
        try (StateStore state = new StateStore(workspace)) {
            var budget = new DurableTaskBudgetEvidenceSource(state).snapshot(taskId, runId);
            var segments = state.taskContinuity.findByTask(taskId);
            Map<String,Integer> reasons = new LinkedHashMap<>();
            for (var segment : segments) reasons.merge(segment.trigger(), 1, Integer::sum);
            String terminalReason = segments.isEmpty() ? null
                    : segments.getLast().terminalReason();
            boolean hardExhausted = Set.of("MAX_PROVIDER_TURNS_EXHAUSTED",
                    "MAX_TOOL_CALLS_EXHAUSTED", "MAX_INPUT_TOKENS_EXHAUSTED",
                    "MAX_OUTPUT_TOKENS_EXHAUSTED", "MAX_ACTIVE_ELAPSED_EXHAUSTED",
                    "MAX_ADDITIONAL_SEGMENTS_EXHAUSTED").contains(terminalReason);
            long duplicate = scalar(workspace, """
                    SELECT count(*) FROM (SELECT logical_operation_key
                    FROM side_effect_occurrences WHERE task_id=?
                    GROUP BY logical_operation_key HAVING count(*)>1)
                    """, taskId);
            long unknown = scalar(workspace,
                    "SELECT count(*) FROM side_effect_occurrences WHERE task_id=? AND status='UNKNOWN'",
                    taskId);
            facts.put("continuityEvents", Math.max(0, segments.size() - 1));
            facts.put("continuityReasonDistribution", reasons);
            facts.put("segmentsPerTask", segments.size());
            facts.put("requestedToolCalls", budget.requestedToolCalls());
            facts.put("admittedToolCalls", budget.admittedToolCalls());
            facts.put("providerLedgerComplete", budget.complete());
            facts.put("budgetEvidenceFailures", budget.missingEvidence());
            facts.put("budgetUtilization", Map.of(
                    "providerTurns", ratio(budget.providerTurns(), policy.maxTotalProviderTurns()),
                    "executedToolCalls", ratio(budget.toolCalls(), policy.maxTotalToolCalls()),
                    "inputTokens", ratio(budget.inputTokens(), policy.maxTotalInputTokens()),
                    "outputTokens", ratio(budget.outputTokens(), policy.maxTotalOutputTokens()),
                    "activeElapsed", ratio(budget.activeElapsedMillis(), policy.maxActiveElapsedMillis())));
            facts.put("hardBudgetExhausted", hardExhausted);
            facts.put("potentialCensoring", hardExhausted);
            facts.put("waitingForUserCount", segments.stream()
                    .filter(item -> "WAITING_FOR_USER".equals(item.state())).count());
            facts.put("parentCompletedEffectReplayCount", duplicate);
            facts.put("duplicateOccurrenceCount", duplicate);
            facts.put("unknownBlindReplayCount", unknown > 0 && segments.size() > 1 ? unknown : 0);
            facts.put("safetyInvariantViolationCount", duplicate
                    + (unknown > 0 && segments.size() > 1 ? unknown : 0));
        }
        return facts;
    }

    private static double ratio(long value, long maximum) {
        return maximum == 0 ? 0.0 : (double) value / maximum;
    }

    private static long scalar(Path workspace, String sql, String taskId) throws Exception {
        try (DatabaseManager database = new DatabaseManager(workspace.resolve(".paicli/state"));
             PreparedStatement statement = database.getConnection().prepareStatement(sql)) {
            statement.setString(1, taskId);
            try (var row = statement.executeQuery()) { return row.next() ? row.getLong(1) : 0; }
        }
    }

    private static Config taskConfig(Config base, JsonNode task) {
        List<String> command = JSON.convertValue(task.path("oracle").path("verificationCommand"),
                JSON.getTypeFactory().constructCollectionType(List.class, String.class));
        String approval = "workspace-write-v1".equals(task.path("approvalProfile").asText())
                ? Config.MODE_ON_REQUEST : Config.MODE_READ_ONLY;
        return base.withApprovalMode(approval).withVerification(String.join(" ", command), true);
    }

    private static void requireProvider(Config config) {
        if (!Config.PROVIDER_OPENAI.equals(config.effectiveProvider())
                || !"deepseek".equals(config.providerPreset())
                || !"deepseek-v4-flash".equals(config.model())
                || !"enabled".equals(config.effectiveThinking())
                || !"max".equals(config.effectiveReasoningEffort())) fail("Provider config drift");
    }

    private static String required(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) fail("missing property: " + name);
        return value;
    }

    private static Path requiredPath(String name, Path root) {
        Path value = Path.of(required(name)).toAbsolutePath().normalize();
        if (!value.startsWith(root)) fail(name + " must remain under evidence root");
        return value;
    }
}
