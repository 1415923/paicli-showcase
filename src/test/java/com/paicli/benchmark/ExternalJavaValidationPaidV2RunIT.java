package com.paicli.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.Config;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.ModelProvider;
import com.paicli.provider.ProviderFactory;
import com.paicli.provider.ProviderResponseEnvelope;
import com.paicli.provider.telemetry.AppendOnlyProviderCallLedger;
import com.paicli.provider.telemetry.ProviderCallLedgerContext;
import com.paicli.provider.telemetry.ProviderCallLedgerModelProvider;
import com.paicli.provider.telemetry.ProviderCallObservation;
import com.paicli.provider.telemetry.ProviderObservationSource;
import com.paicli.runtime.CodingTaskObserver;
import com.paicli.runtime.DurableTaskBudgetEvidenceSource;
import com.paicli.runtime.PlanCompatibilityObserver;
import com.paicli.runtime.TaskContinuityPolicy;
import com.paicli.state.DatabaseManager;
import com.paicli.state.StateStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.fail;

/** V2 paid/stub driver that deliberately reuses production continuity and budget wiring. */
class ExternalJavaValidationPaidV2RunIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AUTHORIZATION = "PUBLIC_EXTERNAL_JAVA_VALIDATION_PAID_V2";
    private static final String PLAN = """
            {"acceptanceCriteria":["candidate-visible task was inspected"],
             "assumptions":[],"risks":[],"verificationStrategy":"official evaluator is authoritative",
             "steps":[{"title":"Inspect repository","definition":"Read pom.xml",
             "dependsOn":[],"completionRule":"ALL_REQUIRED_EVIDENCE",
             "requiredEvidenceKinds":["TOOL_CALL"],"effectClass":"READ",
             "toolRequirements":[{"requirementId":"read-project","requirementOrder":0,
             "toolName":"read_file","effectClass":"READ",
             "authoritySelector":{"kind":"EXACT_PATH","path":"pom.xml"},
             "minSuccesses":1,"maxOccurrences":1,"freshnessPolicy":"RESUME_REUSE_ONLY"}]}]}
            """;

    @Test void executeOneFrozenV2InstanceOrPaidPathRehearsal() throws Exception {
        if (!AUTHORIZATION.equals(required("paicli.external.v2.authorization"))) {
            fail("exact V2 authorization mismatch");
        }
        boolean stub = Boolean.parseBoolean(required("paicli.external.v2.providerStub"));
        Path project = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path executionRoot = project.resolve(stub
                ? "target/external-java-validation-v2-zero-to-one-rehearsal"
                : "target/external-java-validation-paid-v2").normalize();
        Path evidenceRoot = project.resolve("docs/evidence/benchmark/external-java-validation").normalize();
        Path workspace = requiredPath("paicli.external.v2.workspace", executionRoot);
        Path input = requiredPath("paicli.external.v2.candidateInput", evidenceRoot);
        Path output = requiredPath("paicli.external.v2.driverOutput", evidenceRoot);
        Path ledger = requiredPath("paicli.external.v2.providerLedger", evidenceRoot);
        if (Files.exists(output) || Files.exists(ledger)) fail("V2 evidence path is not fresh");

        JsonNode candidate = JSON.readTree(input.toFile());
        if (candidate.size() != 4 || !candidate.has("instance_id") || !candidate.has("repo")
                || !candidate.has("base_commit") || !candidate.has("problem_statement")
                || candidate.has("patch") || candidate.has("test_patch")) {
            fail("candidate-visible input contract drift or oracle leakage");
        }
        String instanceId = required("paicli.external.v2.instanceId");
        if (!instanceId.equals(candidate.path("instance_id").asText())) {
            fail("candidate instance identity drift");
        }

        Config config = frozenConfig(workspace);
        requireFrozenProvider(config);
        ModelProvider transport = stub ? new DeterministicPaidPathStub() : ProviderFactory.create(config);
        String runToken = required("paicli.external.v2.runToken");
        ModelProvider provider = new ProviderCallLedgerModelProvider(
                transport, new AppendOnlyProviderCallLedger(ledger, evidenceRoot),
                new ProviderCallLedgerContext(runToken, "PAICLI",
                        config.effectiveProvider() + "/" + config.providerPreset(),
                        config.model(), required("paicli.external.v2.protocolHash")));
        String promptTemplate = Files.readString(project.resolve(
                "benchmarks/external/swebench-multilingual-java-v1/task-prompt-template.txt"));
        String prompt = promptTemplate.replace("{{problem_statement}}",
                candidate.path("problem_statement").asText());

        // Critical V2 fix: Options does not override continuity evidence. Because Config has
        // explicit continuity enabled, PaiCliBenchmarkTreatment delegates to
        // ProductionTaskContinuityWiring and DurableTaskBudgetEvidenceSource.
        var options = new PaiCliBenchmarkTreatment.Options(null,
                (safety, snapshots, managedTask, controlPaths) ->
                        new com.paicli.tool.WriteFileTool(
                                safety, snapshots, managedTask, null, controlPaths),
                ignored -> { }, 1, TaskContinuityPolicy.disabled(), false);

        Instant started = Instant.now();
        var run = new PaiCliBenchmarkTreatment(workspace, provider, config,
                new PredeclaredApprovalControl((iteration, call) -> true),
                CodingTaskObserver.NOOP, PlanCompatibilityObserver.NOOP, options).run(prompt);
        var budget = readBudget(workspace, run.taskId(), run.runId());

        Map<String,Object> facts = new LinkedHashMap<>();
        facts.put("schemaVersion", "paicli-public-external-java-v2-driver-v1");
        facts.put("mode", stub ? "ZERO_TO_ONE_PAID_PATH_REHEARSAL" : "PAID_EXECUTION");
        facts.put("instanceId", instanceId);
        facts.put("taskStartedAt", started.toString());
        facts.put("taskEndedAt", Instant.now().toString());
        facts.put("taskId", run.taskId());
        facts.put("runId", run.runId());
        facts.put("taskCompleted", run.taskCompleted());
        facts.put("runPhase", run.runPhase());
        facts.put("terminalFailureClass", run.terminalFailureClass());
        facts.put("durableFormalResultPresent", run.durableFormalResultPresent());
        facts.put("providerTurns", run.providerTurns());
        facts.put("toolCalls", run.toolCalls());
        facts.put("inputTokens", run.inputTokens());
        facts.put("outputTokens", run.outputTokens());
        facts.put("usageSource", run.usageSource());
        facts.put("latencyMillis", run.alignedExecutionLatencyMillis());
        facts.put("providerLatencyMillis", run.providerLatencyMillis());
        facts.put("toolLatencyMillis", run.toolLatencyMillis());
        facts.put("durableRetryCount", run.durableRetryCount());
        facts.put("compactCount", run.compactCount());
        facts.put("duplicateOccurrenceIdentities", run.duplicateOccurrenceIdentities());
        facts.put("unknownOccurrenceCount", scalar(workspace,
                "SELECT count(*) FROM side_effect_occurrences WHERE task_id=? AND status='UNKNOWN'",
                run.taskId()));
        facts.put("safetyInvariantViolationCount", run.duplicateOccurrenceIdentities());
        facts.put("approvalDecisionFacts", run.approvalDecisionFacts());
        facts.put("provider", config.effectiveProvider());
        facts.put("providerPreset", config.providerPreset());
        facts.put("model", config.model());
        facts.put("thinking", config.effectiveThinking());
        facts.put("reasoningEffort", config.effectiveReasoningEffort());
        facts.put("contextWindowTokens", config.effectiveContextWindowTokens());
        facts.put("approvalMode", config.approvalMode());
        facts.put("continuityPolicyHash", productionPolicy(config).policyHash());
        facts.put("budgetAuthority", DurableTaskBudgetEvidenceSource.class.getName());
        facts.put("budgetEvidenceComplete", budget.complete());
        facts.put("budgetEvidenceMissing", budget.missingEvidence());
        facts.put("budgetProviderTurns", budget.providerTurns());
        facts.put("budgetInputTokens", budget.inputTokens());
        facts.put("budgetOutputTokens", budget.outputTokens());
        facts.put("externalNetworkCalls", stub ? 0 : -1);
        Files.createDirectories(output.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), facts);
    }

    private static com.paicli.runtime.TaskContinuityModels.BudgetEvidence readBudget(
            Path workspace,String taskId,String runId)throws Exception{
        try(StateStore state=new StateStore(workspace)){
            return new DurableTaskBudgetEvidenceSource(state).snapshot(taskId,runId);
        }
    }

    private static long scalar(Path workspace,String sql,String taskId)throws Exception{
        try(DatabaseManager database=new DatabaseManager(workspace.resolve(".paicli/state"));
            PreparedStatement statement=database.getConnection().prepareStatement(sql)){
            statement.setString(1,taskId);
            try(var row=statement.executeQuery()){return row.next()?row.getLong(1):0L;}
        }
    }

    private static TaskContinuityPolicy productionPolicy(Config config){
        Config.TaskContinuityConfig value=config.taskContinuity();
        return new TaskContinuityPolicy(true,value.maxAdditionalSegments(),value.maxPlanAttempts(),
                value.maxTotalProviderTurns(),value.maxTotalToolCalls(),value.maxInputTokens(),
                value.maxOutputTokens(),value.maxActiveElapsedMillis());
    }

    private static Config frozenConfig(Path workspace) {
        var continuity = new Config.TaskContinuityConfig(
                true, 3, 3, 40, 80, 600_000L, 64_000L, 1_200_000L);
        return new Config(workspace.toString(), 30, 50_000, 200_000,
                Config.MODE_WORKSPACE_WRITE, 10_000,
                Config.PROVIDER_OPENAI, "deepseek-v4-flash", "https://api.deepseek.com",
                "DEEPSEEK_API_KEY", Config.DEFAULT_SYSTEM_PROMPT, 10,
                "", 120, 50_000, 1, true, false, false,
                false, Config.DEFAULT_SKILLS_DIRS, Config.DEFAULT_MAX_SKILL_CONTEXT_CHARS,
                Config.DEFAULT_MAX_SELECTED_SKILLS, false, Config.DEFAULT_HOOKS_CONFIG_PATH,
                Config.DEFAULT_HOOK_TIMEOUT_SECONDS, Config.DEFAULT_MAX_HOOK_OUTPUT_BYTES,
                Config.DEFAULT_HOOK_FAILURE_POLICY, 65_536, "deepseek", "enabled", "max",
                null, continuity);
    }

    private static void requireFrozenProvider(Config config) {
        if (!Config.PROVIDER_OPENAI.equals(config.effectiveProvider())
                || !"deepseek".equals(config.providerPreset())
                || !"deepseek-v4-flash".equals(config.model())
                || !"https://api.deepseek.com".equals(config.baseUrl())
                || !"enabled".equals(config.effectiveThinking())
                || !"max".equals(config.effectiveReasoningEffort())
                || !Integer.valueOf(65_536).equals(config.effectiveContextWindowTokens())
                || config.isVerifyEnabled()) {
            fail("frozen Provider or task policy drift");
        }
    }

    private static String required(String property) {
        String value=System.getProperty(property);
        if(value==null||value.isBlank())fail("missing system property: "+property);
        return value;
    }

    private static Path requiredPath(String property,Path root){
        Path value=Path.of(required(property)).toAbsolutePath().normalize();
        if(!value.startsWith(root)||value.equals(root))fail(property+" is outside its frozen root");
        return value;
    }

    private static final class DeterministicPaidPathStub
            implements ModelProvider, ProviderObservationSource {
        private ProviderCallObservation observation=ProviderCallObservation.notExposed();
        private boolean toolRequested;

        @Override public ModelResponse chat(List<AgentMessage> messages,List<ToolDefinition> tools){
            return chatForOperation("CHAT",messages,tools);
        }

        @Override public ModelResponse chatForOperation(String operation,List<AgentMessage> messages,
                List<ToolDefinition> tools){
            if("PLAN".equals(operation)||tools.isEmpty()){
                observation=complete(10,5,0,ProviderResponseEnvelope.ToolCallCardinality.ZERO,"stub-plan");
                return ModelResponse.finalAnswer(PLAN);
            }
            boolean observationSeen=messages.stream().anyMatch(message->
                    message.type()==AgentMessage.MessageType.OBSERVATION);
            if(!toolRequested&&!observationSeen){
                toolRequested=true;
                observation=complete(12,4,1,ProviderResponseEnvelope.ToolCallCardinality.ONE,"stub-tool");
                return ModelResponse.toolCallResponse("inspect repository",
                        new ToolCall("read_file",Map.of("path","pom.xml"),"v2-zero-to-one-read"));
            }
            observation=complete(9,3,0,ProviderResponseEnvelope.ToolCallCardinality.ZERO,"stub-final");
            return ModelResponse.finalAnswer("Deterministic paid-path rehearsal completed.");
        }

        @Override public ProviderCallObservation takeObservation(){
            ProviderCallObservation value=observation;
            observation=ProviderCallObservation.notExposed();
            return value;
        }

        private static ProviderCallObservation complete(int input,int output,int tools,
                ProviderResponseEnvelope.ToolCallCardinality cardinality,String requestId){
            return new ProviderCallObservation(input,output,input+output,0,0,0,
                    ProviderCallObservation.UsageSource.PROVIDER_REPORTED,
                    ProviderCallObservation.UsageCoverage.COMPLETE,tools,cardinality,"stop",
                    requestId,ProviderCallObservation.ResponseIdentityProvenance.PROVIDER_RETURNED,
                    null,false);
        }
    }
}
