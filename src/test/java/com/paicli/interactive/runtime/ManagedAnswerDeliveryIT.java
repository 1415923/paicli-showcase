package com.paicli.interactive.runtime;

import com.paicli.config.Config;
import com.paicli.interactive.task.*;
import com.paicli.model.*;
import com.paicli.provider.ModelProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the production task, context, telemetry, occurrence and user-result path; no model. */
class ManagedAnswerDeliveryIT {
    @TempDir Path work;

    @Test void oneEncodedResponseDeliversOnlyTheExplicitAnswer() throws Exception {
        String answer="客户是 Northwind Demo，金额为 83。风险检查返回 503，服务端原因未知。";
        run(EcosystemNaturalTaskIT.JSON.writeValueAsString(Map.of("userAnswer",answer)),answer);
    }

    @Test void freeTextCannotBecomeAnAcceptedFinalOrTriggerAnotherCall() throws Exception {
        run("Let me compile the final report. 客户是 Northwind Demo。",null);
    }

    @Test void toolCommentaryRemainsToolCommentaryBeforeStructuredDelivery() throws Exception {
        Files.writeString(work.resolve("invoice.txt"),"Northwind Demo | 83");
        run("{\"userAnswer\":\"文件显示客户 Northwind Demo，金额 83。\"}","文件显示客户 Northwind Demo，金额 83。",true);
    }

    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="paicli.answer.archive",matches=".+")
    void savedN1PublicTextIsRejectedRatherThanRewritten() throws Exception {
        run(EcosystemNaturalTaskIT.JSON.readTree(Files.readString(Path.of(System.getProperty("paicli.answer.archive"))
                .resolve("N1-quality/decoded-0018.json"))).path("content").asText(),null);
    }
    @Test @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="paicli.answer.archive",matches=".+")
    void savedN3PublicTextIsRejectedRatherThanRewritten() throws Exception {
        run(EcosystemNaturalTaskIT.JSON.readTree(Files.readString(Path.of(System.getProperty("paicli.answer.archive"))
                .resolve("N3-quality/decoded-0013.json"))).path("content").asText(),null);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"N3-quality/decoded-0007.json","N1-quality/decoded-0014.json"})
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="paicli.answer.archive.qr3",matches=".+")
    void latestPaidFailuresRemainNegativeFixtures(String relative)throws Exception {
        run(EcosystemNaturalTaskIT.JSON.readTree(Files.readString(Path.of(System.getProperty("paicli.answer.archive.qr3"))
                .resolve(relative))).path("content").asText(),null);
    }

    private void run(String encoded,String expected) throws Exception {
        run(encoded,expected,false);
    }
    private void run(String encoded,String expected,boolean readTool) throws Exception {
        Config config=Config.defaults().merge(EcosystemNaturalTaskIT.JSON.readValue("""
                {"provider":"ollama","model":"fixture","approval_mode":"on-request",
                 "enable_plan":false,"enable_verify":false,"enable_repair":false,
                 "enable_skills":false,"enable_hooks":false,"max_iterations":4,"context_window_tokens":131072}
                """,Config.class));
        AtomicInteger calls=new AtomicInteger();List<AgentMessage> input=new ArrayList<>();
        ModelProvider fixture=new ModelProvider() {
            @Override public boolean usesManagedAnswerEnvelope(){return true;}
                @Override public void validateOutputRequirement(com.paicli.provider.OutputRequirement requirement,boolean hasTools){
                    if(requirement.nativeJson())throw new IllegalArgumentException("FIXTURE_PROMPT_ONLY");
                }
                @Override public com.paicli.model.ModelResponse chatForOperation(String operation,List<com.paicli.model.AgentMessage> messages,
                        List<com.paicli.model.ToolDefinition> tools,com.paicli.provider.OutputRequirement requirement){
                    validateOutputRequirement(requirement,!tools.isEmpty());
                    return chatForOperation(operation,messages,tools);
                }
            @Override public ModelResponse chat(List<AgentMessage> messages,List<ToolDefinition> tools) {
                int call=calls.incrementAndGet();input.addAll(messages);
                if(readTool&&call==1)return ModelResponse.toolCallResponse("Let me read the file.",
                        new ToolCall("read_file",Map.of("path","invoice.txt"),"read-invoice"));
                return ModelResponse.finalAnswer(encoded);
            }
        };
        String taskId;
        try(var runtime=InteractiveRuntimeAdapter.open(work,new ProductionInteractiveTaskExecutor(work,()->config,
                ignored->fixture),event->{})) {
            var session=EcosystemNaturalTaskIT.configuredSession("answer",work,config);session.start();runtime.openSession(session);
            runtime.submit(new InteractiveTaskRequest("answer",work,"请用中文报告客户和金额，说明未知原因。",List.of()));
            var task=runtime.activeForegroundTask().orElseThrow();taskId=task.taskId();
            var result=task.terminal().get(10,TimeUnit.SECONDS);
            assertEquals(expected==null?TaskSubmissionResult.Status.FAILED:TaskSubmissionResult.Status.COMPLETED,
                    result.status(),result.toString());
        }
        assertEquals(readTool?2:1,calls.get(),"Only scripted turns; no correction, synthesis, retry or paid request");
        assertTrue(input.stream().anyMatch(m->m.content()!=null&&m.content().contains("PAICLI_USER_ANSWER_V1")),
                "The compiled input must contain the contract, including through production telemetry");
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+work.resolve(".paicli/state/paicli.db"))) {
            try(var q=db.prepareStatement("SELECT content,content_hash,validation_status,render_status FROM task_user_results WHERE task_id=?")) {
                q.setString(1,taskId);try(var r=q.executeQuery()) {
                    if(expected==null) assertFalse(r.next());
                    else {assertTrue(r.next());assertEquals(expected,r.getString(1));
                        assertEquals(com.paicli.skill.SkillPackageFiles.hash(expected.getBytes(java.nio.charset.StandardCharsets.UTF_8)),r.getString(2));
                        assertEquals("VALID",r.getString(3));assertEquals("RENDERED",r.getString(4));}
                }
            }
            try(var q=db.prepareStatement("SELECT status,final_candidate_digest,result_hash FROM side_effect_occurrences WHERE task_id=? AND operation_type='PROVIDER' AND (final_candidate_digest IS NOT NULL OR status='FAILED')")) {
                q.setString(1,taskId);try(var r=q.executeQuery()) {
                    assertTrue(r.next());assertEquals(expected==null?"FAILED":"COMPLETED",r.getString(1));
                    if(expected==null) assertNull(r.getString(2));
                    else {assertNotNull(r.getString(2));assertEquals(com.paicli.state.FencedMutationStore.providerFinalResultHash(expected),r.getString(3));}
                    assertFalse(r.next());
                }
            }
            try(var q=db.prepareStatement("SELECT count(*) FROM side_effect_occurrences WHERE task_id=? AND status IN ('STARTED','UNKNOWN')")) {
                q.setString(1,taskId);try(var r=q.executeQuery()){assertTrue(r.next());assertEquals(0,r.getInt(1));}
            }
            try(var q=db.prepareStatement("SELECT count(*) FROM events WHERE task_id=? AND type='FINAL'")) {
                q.setString(1,taskId);try(var r=q.executeQuery()){assertTrue(r.next());assertEquals(expected==null?0:1,r.getInt(1));}
            }
        }
    }
}
