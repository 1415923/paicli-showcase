package com.paicli.runtime;

import com.paicli.agent.AgentLoop;
import com.paicli.config.Config;
import com.paicli.context.ContextModels.CapabilityProfile;
import com.paicli.context.ContextModels.LimitProvenance;
import com.paicli.context.ContextModels.OperationKind;
import com.paicli.context.ContextualModelProvider;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.hook.HookDefinition;
import com.paicli.hook.HookEvent;
import com.paicli.hook.HookRunner;
import com.paicli.hook.HookRuntime;
import com.paicli.interactive.query.StateStoreInteractiveQueryFacade;
import com.paicli.interactive.runtime.InteractiveRuntimeView;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.ModelProvider;
import com.paicli.safety.ApprovalPolicy;
import com.paicli.safety.SafetyPolicy;
import com.paicli.state.DatabaseManager;
import com.paicli.state.StateStore;
import com.paicli.state.TaskStore;
import com.paicli.state.VerificationStore;
import com.paicli.tool.ReadFileTool;
import com.paicli.tool.RunCommandTool;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.WriteFileTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AdvisoryPlanRuntimeTest {
    private static final String ONE_STEP_PLAN="""
            {"acceptanceCriteria":["task intent completed"],"assumptions":[],"risks":[],
             "verificationStrategy":"runtime safety and final result","steps":[
               {"title":"Inspect intent","definition":"Inspect the relevant workspace material",
                "dependsOn":[],"completionRule":"ALL_REQUIRED_EVIDENCE",
                "requiredEvidenceKinds":["TOOL_CALL"],"effectClass":"READ",
                "toolRequirements":[{"requirementId":"advisory-read-01","requirementOrder":0,
                "toolName":"read_file","effectClass":"READ",
                "authoritySelector":{"kind":"EXACT_PATH","path":"fixtures/item-01.txt"},
                "minSuccesses":1,"maxOccurrences":1,
                "freshnessPolicy":"RESUME_REUSE_ONLY"}]}]}
            """;
    private static final String MINIMAL_ADVISORY_PLAN="""
            {"acceptanceCriteria":["task intent completed"],"assumptions":[],"risks":[],
             "verificationStrategy":"runtime safety and final result","steps":[
             {"title":"Inspect intent","definition":"Inspect workspace material only",
              "dependsOn":[]}]}
            """;

    @TempDir Path workspace;

    @Test void DEFAULT_ADVISORY_WIRE_PERSISTS_CONFIRMABLE_V2_WITHOUT_STRICT_AUTHORITY()
            throws Exception {
        Config config=config(true,Config.MODE_ON_REQUEST,3);
        CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
        try(StateStore state=new StateStore(workspace)){
            CodingTaskResult result=runner(state,task,config,MINIMAL_ADVISORY_PLAN,
                    new SequenceProvider(List.of(ModelResponse.finalAnswer("done"))),
                    new ToolRegistry(),(taskId,runId)->false).run(task.value(),"Answer safely.");
            assertEquals(TaskStore.STATUS_DONE,result.finalStatus(),result.summary());
            assertEquals(1,count("plan_revisions"));
            assertEquals(1,count("plan_approvals"));
            assertEquals("APPROVED",scalar("SELECT state FROM plan_revision_lifecycle"));
            assertEquals(2,Integer.parseInt(scalar(
                    "SELECT canonical_schema_version FROM plan_revisions")));
            assertEquals(0,count("plan_v3_revisions"));
            assertEquals(0,count("plan_tool_requirements"));
            assertEquals(0,count("task_plan_authorities"));
            assertEquals(0,count("plan_run_bindings"));
            assertEquals(0,count("plan_step_executions"));
            assertEquals(0,count("verification_runs"));
        }
    }

    @Test void EXACT_CANDIDATE_PASS_ALLOWS_ORDINARY_COMPLETION() throws Exception {
        Path source=workspace.resolve("src/main/java/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source,"class App {}\n");
        Config config=verificationConfig();
        CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
        try(StateStore state=new StateStore(workspace)){
            CodingTaskResult result=runner(state,task,config,MINIMAL_ADVISORY_PLAN,
                    new SequenceProvider(List.of(ModelResponse.finalAnswer("done"))),
                    new ToolRegistry(),CandidateVerificationGate.ALLOW)
                    .run(task.value(),"Complete the source task.");

            assertEquals(TaskStore.STATUS_DONE,result.finalStatus(),result.summary());
            assertEquals(VerificationStore.STATUS_PASSED,result.verificationStatus());
            var verification=state.verifications.findByRun(result.runId()).getFirst();
            assertEquals(WorkspaceCandidateDigest.KIND,verification.candidateDigestKind());
            assertTrue(verification.candidateDigest().matches("[0-9a-f]{64}"));
            assertEquals(1,count("task_user_results"));
        }
    }

    @Test void MALFORMED_OR_FORBIDDEN_ADVISORY_SELECTOR_HINT_CANNOT_GRANT_TOOL_AUTHORITY()
            throws Exception {
        String hinted=ONE_STEP_PLAN.replace(
                "\"kind\":\"EXACT_PATH\",\"path\":\"fixtures/item-01.txt\"",
                "\"kind\":\"EXACT_PATH\",\"path\":\"../outside.txt\",\"recursive\":true");
        Path target=workspace.resolve("protected.txt");
        Files.writeString(target,"original");
        Config config=config(true,Config.MODE_ON_REQUEST,5);
        ToolRegistry tools=new ToolRegistry();
        tools.register(new WriteFileTool(new SafetyPolicy(workspace)));
        ModelProvider execution=new SequenceProvider(List.of(
                ModelResponse.toolCallResponse("",new ToolCall("write_file",Map.of(
                        "path","protected.txt","content","changed","overwrite",true),
                        "advisory-hint-write")),
                ModelResponse.finalAnswer("write remained unauthorized")));
        CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
        try(StateStore state=new StateStore(workspace)){
            CodingTaskResult result=runner(state,task,config,hinted,execution,tools,
                    (taskId,runId)->false).run(task.value(),"Inspect only.");
            assertEquals(TaskStore.STATUS_DONE,result.finalStatus(),result.summary());
            assertEquals("original",Files.readString(target));
            assertEquals(0,count("plan_tool_requirements"));
            assertEquals(0,count("task_plan_authorities"));
            assertTrue(state.approvals.findByTask(task.value()).stream().anyMatch(record->
                    "write_file".equals(record.toolName())
                            &&"NEEDS_APPROVAL".equals(record.decision())));
        }
    }

    @Test void DEFAULT_EIGHT_READ_CHAIN_COMPLETES() throws Exception {
        Path fixtures=workspace.resolve("fixtures");
        Files.createDirectories(fixtures);
        List<String> paths=new ArrayList<>();
        for(int index=1;index<=8;index++){
            String path="fixtures/item-%02d.txt".formatted(index);
            paths.add(path);
            Files.writeString(workspace.resolve(path),"marker-%02d".formatted(index));
        }
        Config config=config(true,Config.MODE_ON_REQUEST,20);
        ContinuousReadProvider execution=new ContinuousReadProvider(paths);
        CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
        try(StateStore state=new StateStore(workspace)){
            CodingTaskResult result=runner(state,task,config,eightStepPlan(),execution,
                    readTools(),(taskId,runId)->false).run(task.value(),
                    "Read the eight fixture files and summarize their markers without changes.");

            assertEquals(TaskStore.STATUS_DONE,result.finalStatus(),result.summary());
            assertEquals(9,execution.calls);
            assertEquals(8,execution.callIds.stream().distinct().count());
            assertEquals(paths,execution.paths);
            assertEquals(8,state.toolCalls.findByTask(task.value()).size());
            assertEquals("COMPLETED",state.codingRuns.findById(result.runId()).orElseThrow().phase());
            assertEquals("COMPLETED",state.r1aDurable.findRun(
                    CanonicalId.parse(result.runId())).orElseThrow().terminalStatus());
            assertEquals(1,count("plan_approvals"));
            assertEquals("APPROVED",scalar("SELECT state FROM plan_revision_lifecycle"));
            assertEquals(0,count("task_plan_authorities"));
            assertEquals(0,count("plan_run_bindings"));
            assertEquals(0,count("plan_step_executions"));
            assertEquals(0,count("plan_evidence"));
            var userResult=state.userResults.find(task,
                    CanonicalId.parse(result.runId())).orElseThrow();
            assertEquals("All requested workspace reads completed.",userResult.content());
            assertEquals("RENDERED",userResult.renderStatus());
            assertEquals(1,count("task_user_results"));
            assertEquals("PLAN_DISABLED",scalar("SELECT applicability FROM task_plan_applicability"));
            for(int index=1;index<=8;index++){
                assertEquals("marker-%02d".formatted(index),
                        Files.readString(workspace.resolve(paths.get(index-1))));
            }
        }
    }

    @Test void DEFAULT_DIRECT_THREE_TOOL_CHAIN_HAS_ONE_RESULT_AND_REPORT_USES_IT()
            throws Exception {
        Path fixtures=workspace.resolve("fixtures");Files.createDirectories(fixtures);
        List<String> paths=List.of("fixtures/item-01.txt","fixtures/item-02.txt",
                "fixtures/item-03.txt");
        for(int index=0;index<paths.size();index++)
            Files.writeString(workspace.resolve(paths.get(index)),"direct-"+(index+1));
        Config config=config(true,Config.MODE_ON_REQUEST,8);
        ModelProvider execution=new SequenceProvider(List.of(
                ModelResponse.toolCallResponse("intermediate update",new ToolCall("read_file",
                        Map.of("path",paths.get(0)),"direct-1")),
                ModelResponse.toolCallResponse("",new ToolCall("read_file",
                        Map.of("path",paths.get(1)),"direct-2")),
                ModelResponse.toolCallResponse("",new ToolCall("read_file",
                        Map.of("path",paths.get(2)),"direct-3")),
                ModelResponse.finalAnswer("durable final result")));
        CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
        RecordingObserver observer=new RecordingObserver();
        try(StateStore state=new StateStore(workspace)){
            CodingTaskResult result=runner(state,task,config,eightStepPlan(),execution,
                    readTools(),(taskId,runId)->false,observer).run(task.value(),
                    "Read three files and return the final result.");
            assertEquals(TaskStore.STATUS_DONE,result.finalStatus(),result.summary());
            assertEquals(3,state.toolCalls.findByTask(task.value()).size());
            assertEquals(1,count("task_user_results"));
            var persisted=state.userResults.find(task,CanonicalId.parse(result.runId()))
                    .orElseThrow();
            assertEquals("durable final result",persisted.content());
            assertEquals(List.of("durable final result"),observer.results);
            assertTrue(observer.resultObservedBeforeCompletion);
            assertFalse(persisted.content().contains("intermediate update"));

            InteractiveSession session=new InteractiveSession("result-session",workspace,"mock",
                    "model",Config.MODE_ON_REQUEST,InteractiveSession.TerminalMode.PLAIN,
                    Instant.now());
            session.start();session.startTask(task.value(),result.runId());session.finishTask(task.value());
            String report=String.join("\n",new StateStoreInteractiveQueryFacade(workspace,
                    new InteractiveRuntimeView()).report(session.snapshot(),null));
            assertTrue(report.contains("[Result]"),report);
            assertTrue(report.contains("durable final result"),report);
        }
    }

    @Test void FINAL_RESULT_PERSISTENCE_FAILURE_FAILS_CLOSED() throws Exception {
        Files.createDirectories(workspace.resolve("fixtures"));
        Files.writeString(workspace.resolve("fixtures/item-01.txt"),"marker");
        Config config=config(true,Config.MODE_ON_REQUEST,4);
        CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
        try(StateStore state=new StateStore(workspace)){
            try(DatabaseManager manager=new DatabaseManager(workspace.resolve(".paicli/state"));
                    var statement=manager.getConnection().createStatement()){
                statement.execute("CREATE TRIGGER fail_advisory_result BEFORE INSERT ON task_user_results "
                        +"BEGIN SELECT RAISE(ABORT,'injected result failure'); END");
            }
            CodingTaskResult result=runner(state,task,config,ONE_STEP_PLAN,
                    new ContinuousReadProvider(List.of("fixtures/item-01.txt")),readTools(),
                    (taskId,runId)->false).run(task.value(),"Read the fixture.");
            assertEquals(TaskStore.STATUS_FAILED,result.finalStatus(),result.summary());
            assertEquals(0,count("task_user_results"));
            assertEquals("FAILED",state.codingRuns.findById(result.runId()).orElseThrow().phase());
            assertEquals("FAILED",state.r1aDurable.findRun(CanonicalId.parse(result.runId()))
                    .orElseThrow().terminalStatus());
        }
    }

    @Test void PASS_THEN_EXTERNAL_EQUIVALENT_SOURCE_CHANGE_FAILS_AT_GATE_A() throws Exception {
        Path source=workspace.resolve("src/main/java/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source,"class App {}\n");
        Config config=verificationConfig();
        CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
        PostPassMutationObserver observer=new PostPassMutationObserver(source);
        try(StateStore state=new StateStore(workspace)){
            CodingTaskResult result=runner(state,task,config,MINIMAL_ADVISORY_PLAN,
                    new SequenceProvider(List.of(ModelResponse.finalAnswer("done"))),
                    new ToolRegistry(),CandidateVerificationGate.ALLOW,observer)
                    .run(task.value(),"Complete the source task.");

            assertEquals(TaskStore.STATUS_FAILED,result.finalStatus(),result.summary());
            assertTrue(result.summary().contains("STALE_CANDIDATE_BEFORE_RESULT"),result.summary());
            assertEquals("changed-after-pass",Files.readString(source));
            assertEquals("PASSED",scalar("SELECT status FROM verification_runs"));
            assertEquals(0,count("task_user_results"));
            assertEquals("FAILED",state.codingRuns.findById(result.runId()).orElseThrow().phase());
            assertEquals("FAILED",state.r1aDurable.findRun(CanonicalId.parse(result.runId()))
                    .orElseThrow().terminalStatus());
        }
    }

    @Test void RESULT_CALLBACK_SOURCE_CHANGE_FAILS_AT_GATE_B_WITH_DURABLE_RESULT() throws Exception {
        Path source=workspace.resolve("src/main/java/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source,"class App {}\n");
        Config config=verificationConfig();
        CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
        ResultMutationObserver observer=new ResultMutationObserver(source);
        try(StateStore state=new StateStore(workspace)){
            CodingTaskResult result=runner(state,task,config,MINIMAL_ADVISORY_PLAN,
                    new SequenceProvider(List.of(ModelResponse.finalAnswer("done"))),
                    new ToolRegistry(),CandidateVerificationGate.ALLOW,observer)
                    .run(task.value(),"Complete the source task.");

            assertEquals(TaskStore.STATUS_FAILED,result.finalStatus(),result.summary());
            assertTrue(result.summary().contains("STALE_CANDIDATE_BEFORE_TERMINALIZATION"),
                    result.summary());
            assertEquals("changed-during-result",Files.readString(source));
            assertEquals("PASSED",scalar("SELECT status FROM verification_runs"));
            assertEquals(1,count("task_user_results"));
            assertEquals("RENDERED",scalar("SELECT render_status FROM task_user_results"));
            assertEquals("FAILED",state.codingRuns.findById(result.runId()).orElseThrow().phase());
            assertEquals("FAILED",state.r1aDurable.findRun(CanonicalId.parse(result.runId()))
                    .orElseThrow().terminalStatus());
        }
    }

    @Test void BEFORE_FINISH_HOOK_SOURCE_CHANGE_FAILS_CLOSED_AT_GATE_A() throws Exception {
        Path source=workspace.resolve("src/main/java/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source,"class App {}\n");
        Config config=verificationConfig();
        CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
        try(StateStore state=new StateStore(workspace)){
            PersistenceSanitizer sanitizer=new PersistenceSanitizer();
            HookRunner hookRunner=new HookRunner(state.hookRuns,
                    new BoundedProcessRunner(workspace,sanitizer,4096),sanitizer,4096);
            HookRuntime hooks=new HookRuntime(List.of(new HookDefinition("h5-before-finish",
                    HookEvent.BEFORE_FINISH,writeCommand("src/main/java/App.java",
                    "changed-by-before-finish"),5,"fail",true)),hookRunner,state.hookRuns);
            CodingTaskResult result=runner(state,task,config,MINIMAL_ADVISORY_PLAN,
                    new SequenceProvider(List.of(ModelResponse.finalAnswer("done"))),
                    new ToolRegistry(),CandidateVerificationGate.ALLOW,
                    CodingTaskObserver.NOOP,hooks).run(task.value(),"Complete the source task.");

            assertEquals(TaskStore.STATUS_FAILED,result.finalStatus(),result.summary());
            assertTrue(result.summary().contains("STALE_CANDIDATE_BEFORE_RESULT"),result.summary());
            assertEquals("changed-by-before-finish"+System.lineSeparator(),
                    Files.readString(source));
            assertEquals("PASSED",scalar("SELECT status FROM verification_runs"));
            assertEquals("PASSED",scalar("SELECT status FROM hook_runs WHERE event='BEFORE_FINISH'"));
            assertEquals("FAILED",state.codingRuns.findById(result.runId()).orElseThrow().phase());
        }
    }

    @Test void DUPLICATE_FINAL_CALLBACK_DOES_NOT_CREATE_TWO_RESULTS() throws Exception {
        Files.createDirectories(workspace.resolve("fixtures"));
        Files.writeString(workspace.resolve("fixtures/item-01.txt"),"marker");
        Config config=config(true,Config.MODE_ON_REQUEST,4);
        CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
        try(StateStore state=new StateStore(workspace)){
            CodingTaskResult result=runner(state,task,config,ONE_STEP_PLAN,
                    new ContinuousReadProvider(List.of("fixtures/item-01.txt")),readTools(),
                    (taskId,runId)->false).run(task.value(),"Read the fixture.");
            var original=state.userResults.find(task,CanonicalId.parse(result.runId())).orElseThrow();
            var repeated=state.userResults.persist(task,CanonicalId.parse(result.runId()),
                    CanonicalId.parse(original.planRevisionId()),original.mode(),original.content(),
                    Instant.now());
            state.userResults.markRendered(task,CanonicalId.parse(result.runId()),
                    repeated.contentHash(),Instant.now());
            assertEquals(original.contentHash(),repeated.contentHash());
            assertEquals(1,count("task_user_results"));
        }
    }

    @Test void DEFAULT_PLAN_ORDER_MISMATCH_AND_EXTRA_SAFE_READ_DO_NOT_FAIL_TASK()
            throws Exception {
        Files.createDirectories(workspace.resolve("fixtures"));
        Files.writeString(workspace.resolve("fixtures/item-01.txt"),"planned");
        Files.writeString(workspace.resolve("fixtures/extra.txt"),"safe-extra");
        Config config=config(true,Config.MODE_ON_REQUEST,6);
        ContinuousReadProvider execution=new ContinuousReadProvider(List.of("fixtures/extra.txt"));
        CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
        try(StateStore state=new StateStore(workspace)){
            CodingTaskResult result=runner(state,task,config,ONE_STEP_PLAN,execution,
                    readTools(),(taskId,runId)->false).run(task.value(),"Inspect safely.");
            assertEquals(TaskStore.STATUS_DONE,result.finalStatus(),result.summary());
            assertEquals(List.of("fixtures/extra.txt"),execution.paths);
            assertEquals(1,state.toolCalls.findByTask(task.value()).size());
            assertEquals(0,count("plan_evidence"));
            assertEquals(0,count("plan_run_bindings"));
        }
    }

    @Test void WRITE_STILL_REQUIRES_APPROVAL() throws Exception {
        Files.createDirectories(workspace.resolve("fixtures"));
        Path target=workspace.resolve("fixtures/item-01.txt");
        Files.writeString(target,"original");
        Config config=config(true,Config.MODE_ON_REQUEST,5);
        ModelProvider execution=new SequenceProvider(List.of(
                ModelResponse.toolCallResponse("",new ToolCall("write_file",Map.of(
                        "path","fixtures/item-01.txt","content","changed","overwrite",true),
                        "advisory-write-overwrite")),
                ModelResponse.finalAnswer("write was not authorized")));
        ToolRegistry tools=new ToolRegistry();
        tools.register(new WriteFileTool(new SafetyPolicy(workspace)));
        CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
        try(StateStore state=new StateStore(workspace)){
            CodingTaskResult result=runner(state,task,config,ONE_STEP_PLAN,execution,tools,
                    (taskId,runId)->false).run(task.value(),"Inspect and then overwrite if allowed.");
            assertEquals(TaskStore.STATUS_DONE,result.finalStatus(),result.summary());
            assertEquals("original",Files.readString(target));
            assertTrue(state.approvals.findByTask(task.value()).stream().anyMatch(record->
                    "write_file".equals(record.toolName())
                            &&"HIGH".equals(record.riskLevel())
                            &&"NEEDS_APPROVAL".equals(record.decision())));
            assertEquals(0,count("plan_run_bindings"));
        }
    }

    @Test void OUTSIDE_WORKSPACE_STILL_REJECTED() throws Exception {
        Files.createDirectories(workspace.resolve("fixtures"));
        Files.writeString(workspace.resolve("fixtures/item-01.txt"),"planned");
        Path outside=workspace.getParent().resolve("advisory-outside.txt");
        Files.writeString(outside,"outside-secret-marker");
        Config config=config(true,Config.MODE_READ_ONLY,5);
        SequenceProvider execution=new SequenceProvider(List.of(
                ModelResponse.toolCallResponse("",new ToolCall("read_file",
                        Map.of("path","../advisory-outside.txt"),"outside-read")),
                ModelResponse.finalAnswer("outside path was rejected")));
        CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
        try(StateStore state=new StateStore(workspace)){
            CodingTaskResult result=runner(state,task,config,ONE_STEP_PLAN,execution,
                    readTools(),(taskId,runId)->false).run(task.value(),"Inspect safely.");
            assertEquals(TaskStore.STATUS_DONE,result.finalStatus(),result.summary());
            assertEquals("outside-secret-marker",Files.readString(outside));
            assertEquals(1,state.toolCalls.findByTask(task.value()).size());
            assertFalse(state.toolCalls.findByTask(task.value()).getFirst().success());
            assertEquals(0,count("plan_run_bindings"));
        }
    }

    @Test void HIGH_RISK_EXECUTE_STILL_REQUIRES_POLICY() {
        Config config=config(true,Config.MODE_ON_REQUEST,5);
        ToolRegistry tools=new ToolRegistry();
        tools.register(new RunCommandTool(new SafetyPolicy(workspace)));
        ToolCall call=new ToolCall("run_command",Map.of("command","git status --short"),
                "advisory-command");

        assertTrue(tools.preflight(call).accepted());
        var decision=new ApprovalPolicy(config,"advisory-task")
                .evaluate(call.name(),false);
        assertEquals(com.paicli.safety.ApprovalDecision.Decision.NEEDS_APPROVAL,
                decision.decision());
        assertEquals(com.paicli.safety.ApprovalDecision.RiskLevel.HIGH,
                decision.riskLevel());
    }

    @Test void STRICT_NOT_PUBLICLY_CONFIGURABLE() {
        assertTrue(java.util.Arrays.stream(Config.class.getRecordComponents()).noneMatch(component->{
            String name=component.getName().toLowerCase(java.util.Locale.ROOT);
            return name.contains("strict")||name.contains("planauthority")
                    ||name.contains("plan_authority");
        }));
        assertFalse(java.lang.reflect.Modifier.isPublic(PlanRuntimeMode.class.getModifiers()));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={800,12000})
    void AUTO_COMPACT_REACHABLE_IN_DEFAULT_DIRECT_CHAIN(int bodyChars) throws Exception {
        Path fixtures=workspace.resolve("fixtures");
        Files.createDirectories(fixtures);
        List<String> paths=new ArrayList<>();
        for(int index=1;index<=15;index++){
            String path="fixtures/item-%02d.txt".formatted(index);
            paths.add(path);
            Files.writeString(workspace.resolve(path),"marker-%02d ".formatted(index)
                    +"x".repeat(bodyChars));
        }
        Config config=config(true,Config.MODE_ON_REQUEST,24);
        ContinuousReadProvider raw=new ContinuousReadProvider(paths);
        AtomicInteger compacted=new AtomicInteger();
        CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
        try(StateStore state=new StateStore(workspace)){
            ContextualModelProvider execution=new ContextualModelProvider(raw,state,workspace,
                    task.value(),"mock","model",OperationKind.CHAT,
                    new CapabilityProfile("configured",bodyChars==800?6_000:12_000,
                            LimitProvenance.CONFIGURED_LIMIT,256,128),false,
                    "advisory-compact-test",ignored->compacted.incrementAndGet());
            CodingTaskResult result=runner(state,task,config,ONE_STEP_PLAN,execution,
                    readTools(),(taskId,runId)->false).run(task.value(),
                    "Read the fixture files continuously and retain their markers.");
            if(bodyChars==12000){
                // Protected long reads cannot fit by silently discarding their contents.
                assertEquals(TaskStore.STATUS_FAILED,result.finalStatus());
                assertTrue(result.summary().contains("CONTEXT_BUDGET_EXCEEDED"),result.summary());
                assertTrue(state.toolCalls.findByTask(task.value()).size()<15);
                return;
            }
            assertEquals(TaskStore.STATUS_DONE,result.finalStatus(),result.summary());
            assertTrue(compacted.get()>=1,"default advisory direct Tool chain must reach compact");
            assertEquals(15,state.toolCalls.findByTask(task.value()).size());
            assertEquals(0,count("plan_run_bindings"));
            assertEquals(0,count("plan_evidence"));
        }
    }

    @Test void NO_PLAN_MODE_UNCHANGED() throws Exception {
        Config config=config(false,Config.MODE_READ_ONLY,3);
        ModelProvider planning=(messages,tools)->{
            throw new AssertionError("planning Provider must not be called");
        };
        SequenceProvider execution=new SequenceProvider(List.of(ModelResponse.finalAnswer("done")));
        CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
        try(StateStore state=new StateStore(workspace)){
            ModelProvider contextual=new ContextualModelProvider(execution,state,workspace,
                    task.value(),"mock","model",OperationKind.CHAT,
                    new CapabilityProfile("test",30_000,
                            LimitProvenance.CONFIGURED_LIMIT,256,64),false);
            CodingTaskRunner runner=new CodingTaskRunner(state,new PlanService(planning,state.plans),
                    new VerificationService(workspace,state.verifications,
                            new PersistenceSanitizer(),120,50_000),
                    ignored->new AgentLoop(contextual,new ToolRegistry(),3,
                            new ApprovalPolicy(config,task.value()),null,state,task.value(),
                            "mock","model"),config,new PersistenceSanitizer(),null,null,
                    RuntimeManifest.disabled(),CodingTaskObserver.NOOP,(taskId,runId)->false,
                    new InstructionManifestRuntime(state,workspace),PlanApprovalPort.DENY);
            CodingTaskResult result=runner.run(task.value(),"Answer without a Plan.");
            assertEquals(TaskStore.STATUS_DONE,result.finalStatus(),result.summary());
            assertNull(result.planId());
            assertEquals(1,execution.calls);
            assertEquals(0,count("plan_revisions"));
        }
    }

    private CodingTaskRunner runner(StateStore state,CanonicalId task,Config config,String planJson,
            ModelProvider execution,ToolRegistry tools,CandidateVerificationGate candidates)
            throws Exception {
        return runner(state,task,config,planJson,execution,tools,candidates,CodingTaskObserver.NOOP);
    }

    private CodingTaskRunner runner(StateStore state,CanonicalId task,Config config,String planJson,
            ModelProvider execution,ToolRegistry tools,CandidateVerificationGate candidates,
            CodingTaskObserver observer)throws Exception {
        return runner(state,task,config,planJson,execution,tools,candidates,observer,null);
    }

    private CodingTaskRunner runner(StateStore state,CanonicalId task,Config config,String planJson,
            ModelProvider execution,ToolRegistry tools,CandidateVerificationGate candidates,
            CodingTaskObserver observer,HookRuntime hooks)throws Exception {
        ModelProvider planning=(messages,definitions)->ModelResponse.finalAnswer(planJson);
        VerificationLifecycleObserver verificationObserver=observer
                instanceof VerificationLifecycleObserver lifecycle
                ?lifecycle:VerificationLifecycleObserver.NOOP;
        return new CodingTaskRunner(state,new PlanService(planning,state.plans),
                new VerificationService(workspace,state.verifications,
                        new PersistenceSanitizer(),120,50_000,verificationObserver),
                ignored->new AgentLoop(execution,tools,config.effectiveMaxIterations(),
                        new ApprovalPolicy(config,task.value()),null,state,task.value(),
                        "mock","model"),config,new PersistenceSanitizer(),null,
                hooks,RuntimeManifest.disabled(),observer,candidates,
                new InstructionManifestRuntime(state,workspace),
                (revision,summary)->new PlanApprovalPort.Decision(true,"USER_CONFIRMED",
                        "advisory-test-user","advisory-v1"));
    }

    private ToolRegistry readTools() throws Exception {
        ToolRegistry tools=new ToolRegistry();
        tools.register(new ReadFileTool(new SafetyPolicy(workspace)));
        return tools;
    }

    private Config config(boolean planEnabled,String approvalMode,int maxIterations){
        return new Config(workspace.toString(),30,50_000,200_000,approvalMode,10_000,
                Config.PROVIDER_MOCK,"model","","","system",maxIterations,"",120,
                50_000,1,planEnabled,false,false);
    }

    private Config verificationConfig(){
        return new Config(workspace.toString(),30,50_000,200_000,Config.MODE_ON_REQUEST,10_000,
                Config.PROVIDER_MOCK,"model","","","system",3,"echo h5-pass",120,
                50_000,1,true,true,false);
    }

    private String writeCommand(String relativePath,String content){
        String normalized=relativePath.replace('\\','/');
        int slash=normalized.lastIndexOf('/');
        String directory=normalized.substring(0,slash);
        if(System.getProperty("os.name","").toLowerCase(java.util.Locale.ROOT)
                .contains("windows")){
            return "mkdir \""+directory.replace('/','\\')+"\" 2>NUL & >\""
                    +normalized.replace('/','\\')+"\" echo "+content;
        }
        return "mkdir -p '"+directory+"'; printf '%s\\n' '"+content
                +"' > '"+normalized+"'";
    }

    private long count(String table) throws Exception {
        try(DatabaseManager manager=new DatabaseManager(workspace.resolve(".paicli/state"));
                var statement=manager.getConnection().createStatement();
                var result=statement.executeQuery("SELECT count(*) FROM "+table)){
            result.next();
            return result.getLong(1);
        }
    }

    private String scalar(String sql) throws Exception {
        try(DatabaseManager manager=new DatabaseManager(workspace.resolve(".paicli/state"));
                var statement=manager.getConnection().createStatement();
                var result=statement.executeQuery(sql)){
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static String eightStepPlan(){
        StringBuilder steps=new StringBuilder();
        for(int index=1;index<=8;index++){
            if(index>1)steps.append(',');
            String path="fixtures/item-%02d.txt".formatted(index);
            steps.append("""
                    {"title":"Read item %02d","definition":"Read %s and retain its marker",
                     "dependsOn":%s,"completionRule":"ALL_REQUIRED_EVIDENCE",
                     "requiredEvidenceKinds":["TOOL_CALL"],"effectClass":"READ",
                     "toolRequirements":[{"requirementId":"advisory-read-%02d",
                     "requirementOrder":0,"toolName":"read_file","effectClass":"READ",
                     "authoritySelector":{"kind":"EXACT_PATH","path":"%s"},
                     "minSuccesses":1,"maxOccurrences":1,
                     "freshnessPolicy":"RESUME_REUSE_ONLY"}]}
                    """.formatted(index,path,index==1?"[]":"["+(index-2)+"]",index,path));
        }
        return "{\"acceptanceCriteria\":[\"all markers summarized\"],\"assumptions\":[],"
                +"\"risks\":[],\"verificationStrategy\":\"read only\",\"steps\":["
                +steps+"]}";
    }

    private static final class ContinuousReadProvider implements ModelProvider {
        private final List<String> requested;
        private final List<String> paths=new ArrayList<>();
        private final List<String> callIds=new ArrayList<>();
        private int calls;
        private ContinuousReadProvider(List<String> requested){this.requested=List.copyOf(requested);}
        @Override public ModelResponse chat(List<AgentMessage> messages,List<ToolDefinition> tools){
            calls++;
            if(paths.size()<requested.size()){
                String path=requested.get(paths.size());
                String id="advisory-call-%02d".formatted(paths.size()+1);
                paths.add(path);callIds.add(id);
                return ModelResponse.toolCallResponse("",new ToolCall("read_file",
                        Map.of("path",path),id));
            }
            return ModelResponse.finalAnswer("All requested workspace reads completed.");
        }
    }

    private static final class SequenceProvider implements ModelProvider {
        private final List<ModelResponse> responses;
        private int calls;
        private SequenceProvider(List<ModelResponse> responses){this.responses=List.copyOf(responses);}
        @Override public ModelResponse chat(List<AgentMessage> messages,List<ToolDefinition> tools){
            return responses.get(calls++);
        }
    }

    private final class RecordingObserver implements CodingTaskObserver {
        private final List<String> results=new ArrayList<>();
        private boolean resultObservedBeforeCompletion;
        @Override public boolean finalResponse(String taskId,String runId,String mode,String content,
                String contentHash){
            results.add(content);
            try{
                resultObservedBeforeCompletion=count("task_user_results")==1
                        &&!"COMPLETED".equals(scalar("SELECT phase FROM coding_runs WHERE run_id='"
                        +runId+"'"));
            }catch(Exception error){throw new AssertionError(error);}
            return true;
        }
    }

    private static final class PostPassMutationObserver
            implements CodingTaskObserver,VerificationLifecycleObserver {
        private final Path source;
        private PostPassMutationObserver(Path source){this.source=source;}
        @Override public void completed(VerificationResult result){
            if(!"PASSED".equals(result.status()))return;
            try{Files.writeString(source,"changed-after-pass");}
            catch(Exception error){throw new AssertionError(error);}
        }
    }

    private static final class ResultMutationObserver implements CodingTaskObserver {
        private final Path source;
        private ResultMutationObserver(Path source){this.source=source;}
        @Override public boolean finalResponse(String taskId,String runId,String mode,
                String content,String contentHash){
            try{Files.writeString(source,"changed-during-result");}
            catch(Exception error){throw new AssertionError(error);}
            return true;
        }
    }
}
