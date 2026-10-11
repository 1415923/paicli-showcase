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
import com.paicli.runtime.PlanCompatibilityObserver;
import com.paicli.runtime.TaskContinuityPolicy;
import com.paicli.state.DatabaseManager;
import com.paicli.state.StateStore;
import com.paicli.tool.WriteFileTool;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.fail;

/** One explicitly gated continuity calibration run; excluded from ordinary test discovery. */
class ContinuityCalibrationRunIT {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void executeOneContinuityCalibrationRun() throws Exception {
        boolean providerDisabled = Boolean.parseBoolean(required("paicli.continuity.providerDisabled"));
        boolean authorized = Boolean.parseBoolean(required("paicli.continuity.authorized"));
        if (providerDisabled == authorized) fail("paid authorization must be the sole launch delta");
        Path project = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path evidenceRoot = project.resolve("target/autumn-evidence").normalize();
        Path gatePath = requiredPath("paicli.continuity.gate", evidenceRoot);
        JsonNode gate = JSON.readTree(gatePath.toFile());
        String taskId = required("paicli.continuity.taskId");
        String resultVersion = required("paicli.continuity.resultVersion");
        String policyHash = required("paicli.continuity.policyHash");
        requireGate(gate, taskId, resultVersion, policyHash, providerDisabled, authorized);

        Path workspace = requiredPath("paicli.continuity.workspace", evidenceRoot);
        Path controlHome = requiredPath("paicli.continuity.controlHome", evidenceRoot);
        Path output = requiredPath("paicli.continuity.output", evidenceRoot);
        Path ledger = requiredPath("paicli.continuity.providerLedger", evidenceRoot);
        Path planLedger = requiredPath("paicli.continuity.planLedger", evidenceRoot);
        Path taskPath = Path.of(required("paicli.continuity.task"))
                .toAbsolutePath().normalize();
        Path fixtureRoot = project.resolve("benchmarks/coding/continuity/fixtures").normalize();
        if (!taskPath.startsWith(fixtureRoot) || !"task.json".equals(taskPath.getFileName().toString()))
            fail("continuity task must come from the frozen dedicated suite");
        JsonNode task = JSON.readTree(taskPath.toFile());
        if (!taskId.equals(task.path("taskId").asText())
                || !"AUTONOMOUS_TASK_CONTINUITY_CALIBRATION".equals(task.path("cohort").asText()))
            fail("continuity task identity/cohort drift");

        Config config = taskConfig(new ConfigLoader(workspace, controlHome).load(), workspace,
                task, gate);
        requireFrozenProvider(config);
        String processToken = required("paicli.continuity.processSafeRunToken");
        String protocolHash = required("paicli.continuity.protocolHash");
        ModelProvider raw = providerDisabled ? new ContinuityCalibrationReplayProvider(taskId)
                : new ProviderCallLedgerModelProvider(ProviderFactory.create(config),
                        new AppendOnlyProviderCallLedger(ledger, evidenceRoot),
                        new ProviderCallLedgerContext(processToken, "PAICLI",
                                config.effectiveProvider() + "/" + config.providerPreset(),
                                config.model(), protocolHash));
        var planEvidence = new AppendOnlyPlanCompatibilityEvidence(
                planLedger, evidenceRoot, processToken, "PAICLI");
        TaskContinuityPolicy policy = new TaskContinuityPolicy(true,
                integer(gate,"maxAdditionalSegments"), integer(gate,"maxPlanAttempts"),
                integer(gate,"maxTotalProviderTurns"), integer(gate,"maxTotalToolCalls"),
                longValue(gate,"maxInputTokens"), longValue(gate,"maxOutputTokens"),
                longValue(gate,"maxActiveElapsedMillis"));
        if (!policyHash.equals(policy.policyHash())) fail("continuity policy hash drift");
        var options = new PaiCliBenchmarkTreatment.Options(null,
                (safety, snapshots, managedTask, controlPaths) ->
                        new WriteFileTool(safety, snapshots, managedTask, null, controlPaths),
                ignored -> { }, 1, policy, providerDisabled);
        String prompt = Files.readString(taskPath.getParent().resolve(task.path("promptFile").asText()));
        var run = new PaiCliBenchmarkTreatment(workspace, raw, config,
                new PredeclaredApprovalControl((iteration, call) -> true),
                com.paicli.runtime.CodingTaskObserver.NOOP, planEvidence, options).run(prompt);

        Map<String,Object> facts = new LinkedHashMap<>();
        facts.put("schemaVersion","autonomous-continuity-calibration-driver-v1");
        facts.put("candidateIdentity",gate.path("candidateIdentity").asText());
        facts.put("resultVersion",resultVersion);
        facts.put("taskId",taskId); facts.put("runtime","PAICLI");
        facts.put("taskIdRuntime",run.taskId()); facts.put("runId",run.runId());
        facts.put("providerDisabled",providerDisabled);
        facts.put("maxIterationsPerSegment",integer(gate,"maxIterationsPerSegment"));
        facts.put("maxAdditionalSegments",integer(gate,"maxAdditionalSegments"));
        facts.put("maxTotalProviderTurns",integer(gate,"maxTotalProviderTurns"));
        facts.put("maxTotalToolCalls",integer(gate,"maxTotalToolCalls"));
        facts.put("eventualTaskSuccess",run.taskCompleted());
        facts.put("terminalTaskState",run.repairLifecycleState());
        facts.put("terminalRunState",run.runPhase());
        facts.put("toolCalls",run.toolCalls()); facts.put("totalProviderTurns",run.providerTurns());
        facts.put("inputTokens",run.inputTokens()); facts.put("outputTokens",run.outputTokens());
        facts.put("usageSource",run.usageSource());
        facts.put("providerLatencyMs",run.providerLatencyMillis());
        facts.put("toolLatencyMs",run.toolLatencyMillis());
        facts.put("alignedExecutionLatencyMs",run.alignedExecutionLatencyMillis());
        facts.put("verificationReached",run.structuredVerificationCount()>0);
        facts.put("actualUserInterventionCount",0L);
        facts.put("approvalPolicyDecisionCount",(long)run.approvalDecisionFacts().size());
        facts.put("manualApprovalInputCount",0L);
        facts.put("manualRestartCount",0L);
        facts.put("manualWorkspaceMutationCount",0L);
        facts.put("actualUserInterventionEvidenceSource",
                "NON_INTERACTIVE_CALIBRATION_DRIVER_NO_HUMAN_INPUT_CHANNEL");
        facts.put("naturalProviderRetryCount",run.durableRetryCount());
        facts.put("naturalBatchNormalizationCount",run.effectfulBatchTelemetry().stream()
                .filter(value -> value.normalizationApplied()).count());
        facts.put("planCompatibilityTelemetry",planEvidence.facts().stream()
                .map(AppendOnlyPlanCompatibilityEvidence::map).toList());
        facts.putAll(continuityFacts(workspace, run.taskId(), run.runId(), run.taskCompleted()));
        Files.createDirectories(output.getParent());
        JSON.writerWithDefaultPrettyPrinter().writeValue(output.toFile(),facts);
    }

    private static Map<String,Object> continuityFacts(Path workspace,String taskId,String runId,
            boolean taskSuccess)throws Exception{
        Map<String,Object> result=new LinkedHashMap<>();
        try(StateStore state=new StateStore(workspace)){
            var segments=state.taskContinuity.findByTask(taskId);
            List<Map<String,Object>> rows=new ArrayList<>();
            int previousTurns=0;
            for(var segment:segments){
                Map<String,Object> row=new LinkedHashMap<>();
                row.put("ordinal",segment.segmentOrdinal()); row.put("runId",segment.runId());
                row.put("state",segment.state()); row.put("trigger",segment.trigger());
                row.put("terminalReason",segment.terminalReason());
                row.put("providerTurns",segment.providerTurns()-previousTurns);
                previousTurns=segment.providerTurns();
                row.put("toolCallsCumulative",segment.toolCalls());
                row.put("inputTokensCumulative",segment.inputTokens());
                row.put("outputTokensCumulative",segment.outputTokens());
                rows.add(row);
            }
            long required=segments.stream().filter(value->
                    "RECOVERABLE_FAILED".equals(value.state())).count();
            long duplicates; long unknown;
            try(DatabaseManager database=new DatabaseManager(workspace.resolve(".paicli/state"))){
                duplicates=scalar(database,"""
                        SELECT count(*) FROM (SELECT logical_operation_key
                        FROM side_effect_occurrences WHERE task_id=?
                        GROUP BY logical_operation_key HAVING count(*)>1)
                        """,taskId);
                unknown=scalar(database,"SELECT count(*) FROM side_effect_occurrences WHERE task_id=? AND status='UNKNOWN'",taskId);
            }
            result.put("segments",rows); result.put("segmentsPerTask",segments.size());
            result.put("automaticContinuationRequired",required>0);
            result.put("automaticContinuationStarted",segments.size()>1);
            result.put("automaticContinuationSucceeded",segments.size()>1&&taskSuccess);
            result.put("continuationConvertedToTaskSuccess",required>0&&taskSuccess);
            result.put("parentCompletedEffectReplayCount",duplicates);
            result.put("duplicateOccurrenceCount",duplicates);
            result.put("unknownEffectCount",unknown);
            result.put("safetyInvariantViolationCount",duplicates+(unknown>0&&segments.size()>1?1:0));
            result.put("sameTaskLineage",segments.stream().allMatch(value->value.taskId().equals(taskId)));
            result.put("sameRunLineage",segments.stream().allMatch(value->value.runId().equals(runId)));
            result.put("waitingForUserTransitionCount",segments.stream().filter(value->
                    "WAITING_FOR_USER".equals(value.state())).count());
            result.put("naturalPlanRecoveryCount",segments.stream().filter(value->
                    "STRUCTURED_PLAN_REPAIR_FAILED".equals(value.terminalReason())).count());
            result.put("naturalInvalidToolRecoveryCount",segments.stream().filter(value->
                    "INVALID_TOOL_CALL_RESPONSE".equals(value.terminalReason())).count());
        }
        return result;
    }

    private static long scalar(DatabaseManager database,String sql,String taskId)throws Exception{
        try(PreparedStatement statement=database.getConnection().prepareStatement(sql)){
            statement.setString(1,taskId);try(var row=statement.executeQuery()){
                return row.next()?row.getLong(1):0;
            }
        }
    }

    private static Config taskConfig(Config base,Path workspace,JsonNode task,JsonNode gate){
        List<String> verificationCommand=JSON.convertValue(
                task.path("oracle").path("verificationCommand"),
                JSON.getTypeFactory().constructCollectionType(List.class,String.class));
        String verification=String.join(" ",verificationCommand);
        return new Config(workspace.toString(),base.commandTimeoutSeconds(),base.maxOutputBytes(),
                base.maxWriteFileBytes(),Config.MODE_ON_REQUEST,base.maxProjectInstructionChars(),
                base.provider(),base.model(),base.baseUrl(),base.apiKeyEnv(),base.systemPrompt(),
                integer(gate,"maxIterationsPerSegment"),verification,
                base.verificationTimeoutSeconds(),base.maxVerificationOutputBytes(),
                base.maxRepairAttempts(),true,true,true,false,base.skillsDirs(),
                base.maxSkillContextChars(),base.maxSelectedSkills(),false,base.hooksConfigPath(),
                base.hookTimeoutSeconds(),base.maxHookOutputBytes(),base.hookFailurePolicy(),
                base.contextWindowTokens(),base.providerPreset(),base.thinking(),
                base.reasoningEffort(),base.credentialRef());
    }

    private static void requireFrozenProvider(Config config){
        if(!Config.PROVIDER_OPENAI.equals(config.effectiveProvider())
                ||!"deepseek".equals(config.providerPreset())
                ||!"deepseek-v4-flash".equals(config.model())
                ||!"enabled".equals(config.effectiveThinking())
                ||!"max".equals(config.effectiveReasoningEffort()))fail("Provider config drift");
    }

    private static void requireGate(JsonNode gate,String taskId,String resultVersion,
            String policyHash,boolean disabled,boolean authorized){
        boolean selected=false;for(JsonNode value:gate.path("selectedTaskIds"))
            selected|=taskId.equals(value.asText());
        if(!"autonomous-continuity-calibration-gate-v1".equals(gate.path("schemaVersion").asText())
                ||gate.path("paidProviderAuthorized").asBoolean()!=authorized
                ||gate.path("providerDisabled").asBoolean()!=disabled
                ||!resultVersion.equals(gate.path("resultVersion").asText())
                ||!policyHash.equals(gate.path("policyHash").asText())||!selected)
            fail("continuity calibration gate drift");
    }

    private static int integer(JsonNode node,String field){return node.path("policy").path(field).asInt(-1);}
    private static long longValue(JsonNode node,String field){return node.path("policy").path(field).asLong(-1);}
    private static String required(String property){String value=System.getProperty(property);
        if(value==null||value.isBlank())fail("missing property: "+property);return value;}
    private static Path requiredPath(String property,Path root){Path path=Path.of(required(property)).toAbsolutePath().normalize();
        if(!path.startsWith(root))fail(property+" must remain under evidence root");return path;}
}
