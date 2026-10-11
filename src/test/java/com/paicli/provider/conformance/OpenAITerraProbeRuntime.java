package com.paicli.provider.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.agent.AgentLoop;
import com.paicli.agent.AgentLoopObserver;
import com.paicli.config.Config;
import com.paicli.context.ContextModels.OperationKind;
import com.paicli.context.ContextualModelProvider;
import com.paicli.credential.ProviderCredentialResolver;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.provider.ModelProvider;
import com.paicli.provider.ProviderFactory;
import com.paicli.provider.telemetry.AppendOnlyProviderCallLedger;
import com.paicli.provider.telemetry.ExecutionProviderTelemetry;
import com.paicli.provider.telemetry.ProviderCallContext;
import com.paicli.provider.telemetry.ProviderCallLedgerContext;
import com.paicli.provider.telemetry.ProviderCallLedgerModelProvider;
import com.paicli.runtime.CandidateVerificationGate;
import com.paicli.runtime.CodingTaskObserver;
import com.paicli.runtime.CodingTaskResult;
import com.paicli.runtime.CodingTaskRunner;
import com.paicli.runtime.InstructionManifestRuntime;
import com.paicli.runtime.PersistenceSanitizer;
import com.paicli.runtime.PlanService;
import com.paicli.runtime.RuntimeManifest;
import com.paicli.runtime.VerificationService;
import com.paicli.state.PlanCompletionModels.EffectClass;
import com.paicli.state.StateStore;
import com.paicli.state.TaskStore;
import com.paicli.tool.ToolRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Executes F1-F4 through production runtime composition and emits F5 without a request. */
final class OpenAITerraProbeRuntime {
    record CampaignOutcome(Path evidenceFile, int attempts, String verdict,
            String disposition, int externalProviderCalls) { }

    private final Path project;
    private final Path evidenceRoot;
    private final String baseUrl;
    private final ProviderCredentialResolver credentials;
    private final String mode;
    private final String identityManifestSha256;
    private final boolean externalProvider;

    OpenAITerraProbeRuntime(Path project, Path evidenceRoot, String baseUrl,
            ProviderCredentialResolver credentials, String mode,
            String identityManifestSha256, boolean externalProvider) {
        this.project = project.toAbsolutePath().normalize();
        this.evidenceRoot = evidenceRoot.toAbsolutePath().normalize();
        this.baseUrl = baseUrl;
        this.credentials = credentials;
        this.mode = mode;
        this.identityManifestSha256 = identityManifestSha256;
        this.externalProvider = externalProvider;
    }

    CampaignOutcome run() throws Exception {
        if (Files.exists(evidenceRoot)) {
            throw new IllegalArgumentException("fresh evidence root is required");
        }
        Config gateConfig = OpenAITerraProbeContract.config(
                evidenceRoot.resolve("credential-gate"), baseUrl);
        requireFrozenConfig(gateConfig);
        var credential = OpenAITerraProbeContract.credentialFact(credentials, gateConfig);
        if (!credential.credentialPresent()) {
            throw new IllegalStateException("LIVE_CREDENTIAL_NOT_READY");
        }
        JsonNode price = OpenAITerraProbeContract.load(project, "price-snapshot.json");
        var budget = new OpenAITerraProbeContract.CampaignBudget(
                new OpenAITerraProbeContract.PriceCalculator(price));
        var evidence = new OpenAITerraProbeContract.EvidenceWriter(evidenceRoot, mode,
                identityManifestSha256, credential);
        JsonNode fixtures = OpenAITerraProbeContract.load(project, "fixtures.json");
        Map<String, FixtureOutcome> outcomes = new LinkedHashMap<>();
        boolean stopped = false;
        String stopTaxonomy = null;

        for (JsonNode definition : fixtures.path("fixtures")) {
            String id = definition.path("id").asText();
            if (stopped) {
                FixtureOutcome notRun = FixtureOutcome.notRun(id, stopTaxonomy);
                outcomes.put(id, notRun);
                appendFixture(evidence, notRun);
                continue;
            }
            FixtureExecution execution = fixtureExecution(definition, budget, evidence);
            FixtureOutcome outcome;
            try {
                outcome = executeOnce(definition, execution, 0);
                boolean retryableF3Behavior = "F3".equals(id)
                        && "MODEL_BEHAVIOR_NONDETERMINISM".equals(outcome.taxonomy())
                        && execution.provider.failureTaxonomies().isEmpty();
                if (retryableF3Behavior) {
                    // The one preregistered F3 retry reuses the exact provider, prompt and schema.
                    outcome = executeOnce(definition, execution, 1);
                }
            } catch (OpenAITerraProbeContract.ProbeStopException error) {
                outcome = execution.toFailure(id, "INVALID_STOPPED");
            } catch (RuntimeException error) {
                outcome = execution.toFailure(id, "PRODUCT_RUNTIME_ERROR");
            }
            outcomes.put(id, outcome);
            appendFixture(evidence, outcome);
            if (mustStop(id, outcome)) {
                stopped = true;
                stopTaxonomy = outcome.taxonomy() == null
                        ? "INVALID_STOPPED" : outcome.taxonomy();
            }
        }

        FixtureOutcome aggregate = aggregate(outcomes);
        appendFixture(evidence, aggregate);
        String disposition = "READINESS_SYNTHETIC".equals(mode)
                || "PROVIDER_DISABLED_REHEARSAL".equals(mode)
                ? null : disposition(outcomes);
        String verdict = campaignVerdict(outcomes);
        ObjectNode campaign = OpenAITerraProbeContract.JSON.createObjectNode();
        campaign.put("record_kind", "CAMPAIGN");
        campaign.put("verdict", verdict);
        if (disposition == null) campaign.putNull("disposition");
        else campaign.put("disposition", disposition);
        campaign.put("external_provider_calls", externalProvider ? budget.attempts() : 0);
        campaign.put("settled_budget_usd", OpenAITerraProbeContract.money(budget.settled()));
        campaign.put("paid_cost_usd", externalProvider
                ? OpenAITerraProbeContract.money(budget.settled()) : "0.000000");
        evidence.append(campaign);
        return new CampaignOutcome(evidence.path(), budget.attempts(), verdict, disposition,
                externalProvider ? budget.attempts() : 0);
    }

    private FixtureExecution fixtureExecution(JsonNode definition,
            OpenAITerraProbeContract.CampaignBudget budget,
            OpenAITerraProbeContract.EvidenceWriter evidence) {
        String id = definition.path("id").asText();
        Path workspaceBase = evidenceRoot.resolve("workspaces").resolve(id);
        Config config = OpenAITerraProbeContract.config(workspaceBase, baseUrl);
        ModelProvider adapter = ProviderFactory.create(config, credentials);
        var budgeted = new OpenAITerraProbeContract.BudgetedEvidenceProvider(
                adapter, id, budget, evidence);
        Path ledger = evidenceRoot.resolve("provider-ledger.jsonl");
        ModelProvider ledged = new ProviderCallLedgerModelProvider(budgeted,
                new AppendOnlyProviderCallLedger(ledger, evidenceRoot),
                new ProviderCallLedgerContext(CAMPAIGN_RUN_PREFIX + id,
                        "PAICLI_OPENAI_TERRA_CONFORMANCE", "openai/generic-v1",
                        OpenAITerraProbeContract.MODEL, identityManifestSha256));
        return new FixtureExecution(ledged, budgeted, workspaceBase,
                definition.path("prompt").asText(), definition.path("tools"),
                new FixtureObserver(), new AtomicInteger(), new AtomicInteger(),
                new AtomicInteger());
    }

    private FixtureOutcome executeOnce(JsonNode definition, FixtureExecution execution,
            int fixtureRetry) throws Exception {
        String id = definition.path("id").asText();
        int logicalBefore = execution.provider.logicalTurns();
        int callsBefore = execution.provider.observedToolCalls();
        int finalsBefore = execution.provider.finalResponses();
        int taxonomyBefore = execution.provider.failureTaxonomies().size();
        int readsBefore = execution.readExecutions.get();
        int labelsBefore = execution.labelExecutions.get();
        int effectsBefore = execution.effects.get();
        int notExecutedBefore = execution.observer.notExecuted.get();
        Path workspace = execution.workspaceBase.resolve("run-" + fixtureRetry);
        Files.createDirectories(workspace);
        Config config = OpenAITerraProbeContract.config(workspace, baseUrl);
        CanonicalId task = CanonicalId.newId(EntityFamily.TASK);
        boolean durableResult;
        CodingTaskResult result;
        try (StateStore state = new StateStore(workspace)) {
            var instrumented = ExecutionProviderTelemetry.instrument(execution.ledged,
                    execution.ledged, state.providerTelemetry,
                    config.effectiveProvider(), config.model(), () -> {
                        try {
                            var pointer = state.r1aDurable.readContextPointer(task).orElse(null);
                            return new ProviderCallContext(null, task.value(),
                                    pointer == null ? null : pointer.ownerRunId().value(),
                                    pointer == null ? null : pointer.generationId().value());
                        } catch (Exception ignored) {
                            return new ProviderCallContext(null, task.value(), null, null);
                        }
                    });
            ModelProvider chat = new ContextualModelProvider(instrumented.chat(), state,
                    workspace, task.value(), Config.PROVIDER_OPENAI,
                    OpenAITerraProbeContract.MODEL, OperationKind.CHAT,
                    ContextualModelProvider.capability(Config.PROVIDER_OPENAI,
                            OpenAITerraProbeContract.MODEL,
                            OpenAITerraProbeContract.CONTEXT_WINDOW), false,
                    OpenAITerraProbeContract.SYSTEM_PROMPT, ignored -> { });
            ToolRegistry registry = registry(execution.tools, execution.readExecutions,
                    execution.labelExecutions, execution.effects);
            PersistenceSanitizer sanitizer = new PersistenceSanitizer();
            CodingTaskRunner runner = new CodingTaskRunner(state,
                    new PlanService(instrumented.plan(), state.plans),
                    new VerificationService(workspace, state.verifications, sanitizer,
                            30, 16_384),
                    managedTask -> new AgentLoop(chat, registry,
                            config.effectiveMaxIterations(),
                            // Synthetic names are intentionally outside the production
                            // built-in approval-name table. Batch effect policy still
                            // consumes each Tool's production authority projection; a
                            // null interactive approval port lets READ fixtures execute.
                            null, null, state, managedTask,
                            Config.PROVIDER_OPENAI, OpenAITerraProbeContract.MODEL,
                            execution.observer),
                    config, sanitizer, null, null, RuntimeManifest.disabled(),
                    CodingTaskObserver.NOOP, CandidateVerificationGate.ALLOW,
                    new InstructionManifestRuntime(state, workspace));
            result = runner.run(task.value(), execution.prompt);
            // Plan is deliberately disabled for this protocol probe, so the
            // production runtime does not create a task_user_results row. The
            // durable closure is the terminal Task/CodingRun pair plus the
            // returned CodingTaskResult, all produced by CodingTaskRunner.
            durableResult = TaskStore.STATUS_DONE.equals(
                    state.tasks.findById(task.value()).orElseThrow().status())
                    && "COMPLETED".equals(state.codingRuns.findById(
                            result.runId()).orElseThrow().phase())
                    && result.summary() != null && !result.summary().isBlank();
        }
        List<String> newTaxonomies = execution.provider.failureTaxonomies().stream()
                .skip(taxonomyBefore).toList();
        String taxonomy = newTaxonomies.stream().findFirst().orElse(null);
        boolean done = TaskStore.STATUS_DONE.equals(result.finalStatus());
        int expectedCalls = switch (id) {
            case "F1" -> 0;
            case "F2" -> 1;
            case "F3", "F4" -> 2;
            default -> throw new IllegalArgumentException("unknown fixture");
        };
        int calls = execution.provider.observedToolCalls() - callsBefore;
        int logicalTurns = execution.provider.logicalTurns() - logicalBefore;
        int finalResponses = execution.provider.finalResponses() - finalsBefore;
        int reads = execution.readExecutions.get() - readsBefore;
        int labels = execution.labelExecutions.get() - labelsBefore;
        int effects = execution.effects.get() - effectsBefore;
        int notExecuted = execution.observer.notExecuted.get() - notExecutedBefore;
        boolean semanticPass = done && durableResult && taxonomy == null
                && finalResponses >= 1
                && calls == expectedCalls
                && switch (id) {
                    case "F1" -> logicalTurns == 1;
                    case "F2" -> reads == 1 && logicalTurns == 2;
                    case "F3" -> reads == 1 && labels == 1 && logicalTurns >= 2;
                    case "F4" -> effects == 0 && notExecuted == 2 && logicalTurns == 2;
                    default -> false;
                };
        if (!semanticPass && taxonomy == null) taxonomy = "MODEL_BEHAVIOR_NONDETERMINISM";
        return new FixtureOutcome(id, semanticPass ? "PASS" : "FAIL", taxonomy, calls,
                effects, notExecuted, execution.observer.uniqueIds(),
                semanticPass, semanticPass && !"F1".equals(id), durableResult);
    }

    private ToolRegistry registry(JsonNode names, AtomicInteger reads, AtomicInteger labels,
            AtomicInteger effects) {
        JsonNode schemas = OpenAITerraProbeContract.load(project, "fixtures.json")
                .path("tool_schemas");
        ToolRegistry registry = new ToolRegistry();
        for (JsonNode nameNode : names) {
            String name = nameNode.asText();
            AtomicInteger counter = switch (name) {
                case "lookup_fixture_fact" -> reads;
                case "lookup_fixture_label" -> labels;
                case "effect_counter" -> effects;
                default -> throw new IllegalArgumentException("unknown fixture Tool");
            };
            EffectClass effect = "effect_counter".equals(name)
                    ? EffectClass.WRITE : EffectClass.READ;
            registry.register(new OpenAITerraProbeContract.SyntheticTool(name,
                    "Public deterministic synthetic conformance Tool",
                    schemas.path(name), effect, counter));
        }
        return registry;
    }

    private static boolean mustStop(String fixture, FixtureOutcome outcome) {
        if ("PASS".equals(outcome.verdict())) return false;
        if ("F3".equals(fixture) || "F4".equals(fixture)) {
            return !"MODEL_BEHAVIOR_NONDETERMINISM".equals(outcome.taxonomy());
        }
        return true;
    }

    private static FixtureOutcome aggregate(Map<String, FixtureOutcome> values) {
        int calls = values.values().stream().mapToInt(FixtureOutcome::toolCalls).sum();
        int effects = values.values().stream().mapToInt(FixtureOutcome::effects).sum();
        int notExecuted = values.values().stream().mapToInt(FixtureOutcome::notExecuted).sum();
        boolean pass = values.size() == 4 && values.values().stream()
                .allMatch(value -> "PASS".equals(value.verdict()));
        String taxonomy = values.values().stream().map(FixtureOutcome::taxonomy)
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        return new FixtureOutcome("F5", pass ? "PASS" : "FAIL", taxonomy,
                calls, effects, notExecuted,
                values.values().stream().allMatch(FixtureOutcome::uniqueIds),
                values.values().stream().allMatch(FixtureOutcome::bindingValid),
                values.values().stream().allMatch(value -> "F1".equals(value.id())
                        || value.continuationAccepted()),
                values.values().stream().allMatch(FixtureOutcome::durableResult));
    }

    private static String campaignVerdict(Map<String, FixtureOutcome> outcomes) {
        Set<String> taxonomies = new HashSet<>();
        outcomes.values().stream().map(FixtureOutcome::taxonomy)
                .filter(java.util.Objects::nonNull).forEach(taxonomies::add);
        if (taxonomies.contains("HARNESS_ERROR") || taxonomies.contains("INVALID_STOPPED")) {
            return "INVALID_STOPPED";
        }
        if (taxonomies.contains("AUTH_ACCOUNT_MODEL_ACCESS")
                || taxonomies.contains("RATE_LIMIT_TRANSIENT_TRANSPORT")) return "BLOCKED_EXTERNAL";
        if (taxonomies.stream().anyMatch(value -> Set.of("REQUEST_SCHEMA_INCOMPATIBLE",
                "TOOL_SCHEMA_REJECTED", "TOOL_RESULT_CONTINUATION_REJECTED",
                "TOOL_RESPONSE_PARSE_INCOMPATIBLE", "PRODUCT_RUNTIME_ERROR").contains(value))) {
            return "REQUIRES_PRODUCT_CHANGE";
        }
        return outcomes.values().stream().allMatch(value -> "PASS".equals(value.verdict()))
                ? "PASS" : "PARTIAL";
    }

    private static String disposition(Map<String, FixtureOutcome> outcomes) {
        return switch (campaignVerdict(outcomes)) {
            case "PASS" -> "REAL_OPENAI_TERRA_CHAT_CONFORMANCE_PASS";
            case "PARTIAL" -> "REAL_OPENAI_TERRA_CHAT_CONFORMANCE_PARTIAL";
            case "BLOCKED_EXTERNAL" -> "REAL_OPENAI_TERRA_CHAT_CONFORMANCE_BLOCKED_EXTERNAL";
            case "REQUIRES_PRODUCT_CHANGE" ->
                    "REAL_OPENAI_TERRA_CHAT_CONFORMANCE_REQUIRES_PRODUCT_CHANGE";
            default -> "INVALID_STOPPED";
        };
    }

    private static void appendFixture(OpenAITerraProbeContract.EvidenceWriter evidence,
            FixtureOutcome outcome) {
        ObjectNode row = OpenAITerraProbeContract.JSON.createObjectNode();
        row.put("record_kind", "FIXTURE");
        row.put("fixture", outcome.id());
        row.put("tool_call_count", outcome.toolCalls());
        row.put("tool_effect_count", outcome.effects());
        row.put("not_executed_count", outcome.notExecuted());
        row.put("tool_call_ids_unique", outcome.uniqueIds());
        row.put("tool_result_binding_valid", outcome.bindingValid());
        row.put("continuation_accepted", outcome.continuationAccepted());
        row.put("durable_result_present", outcome.durableResult());
        row.put("verdict", outcome.verdict());
        if (outcome.taxonomy() == null) row.putNull("failure_taxonomy");
        else row.put("failure_taxonomy", outcome.taxonomy());
        evidence.append(row);
    }

    private static void requireFrozenConfig(Config config) {
        if (!Config.PROVIDER_OPENAI.equals(config.effectiveProvider())
                || !OpenAITerraProbeContract.MODEL.equals(config.model())
                || config.providerPreset() != null
                || !Integer.valueOf(OpenAITerraProbeContract.CONTEXT_WINDOW).equals(
                        config.effectiveContextWindowTokens())
                || !OpenAITerraProbeContract.SYSTEM_PROMPT.equals(
                        config.effectiveSystemPrompt())
                || !"disabled".equals(config.effectiveThinking())
                || config.effectiveReasoningEffort() != null
                || config.isPlanEnabled() || config.isVerifyEnabled()
                || config.isRepairEnabled() || config.isSkillsEnabled()
                || config.isHooksEnabled() || config.isTaskContinuityEnabled()) {
            throw new IllegalStateException("frozen Terra runtime configuration drift");
        }
    }

    private static final String CAMPAIGN_RUN_PREFIX = "terra-conformance-v1-";

    private record FixtureExecution(ModelProvider ledged,
            OpenAITerraProbeContract.BudgetedEvidenceProvider provider,
            Path workspaceBase, String prompt, JsonNode tools, FixtureObserver observer,
            AtomicInteger readExecutions, AtomicInteger labelExecutions,
            AtomicInteger effects) {
        FixtureOutcome toFailure(String id, String taxonomy) {
            String observed = provider.failureTaxonomies().stream().findFirst().orElse(taxonomy);
            return new FixtureOutcome(id, "FAIL", observed, provider.observedToolCalls(),
                    effects.get(), observer.notExecuted.get(), observer.uniqueIds(),
                    false, false, false);
        }
    }

    private record FixtureOutcome(String id, String verdict, String taxonomy, int toolCalls,
            int effects, int notExecuted, boolean uniqueIds, boolean bindingValid,
            boolean continuationAccepted, boolean durableResult) {
        static FixtureOutcome notRun(String id, String taxonomy) {
            return new FixtureOutcome(id, "NOT_RUN", taxonomy, 0, 0, 0,
                    true, false, false, false);
        }
    }

    private static final class FixtureObserver implements AgentLoopObserver {
        private final Set<String> ids = new HashSet<>();
        private final AtomicInteger duplicateIds = new AtomicInteger();
        private final AtomicInteger notExecuted = new AtomicInteger();

        @Override public void toolRequested(String taskId, int iteration, ToolRequestFact fact) {
            if (fact.toolCallId() == null || !ids.add(fact.toolCallId())) {
                duplicateIds.incrementAndGet();
            }
        }
        @Override public void effectfulBatchNormalized(String taskId, int iteration,
                EffectfulBatchFact fact) {
            notExecuted.addAndGet(fact.toolCallCount());
        }
        boolean uniqueIds() { return duplicateIds.get() == 0; }
    }
}
