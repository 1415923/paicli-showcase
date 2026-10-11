package com.paicli.interactive.runtime;

import com.paicli.config.Config;
import com.paicli.interactive.task.*;
import com.paicli.provider.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class ManagedAnswerWireIT {
    @TempDir Path work;
    @Test void openAiWireAndProductionTelemetryPreserveTheExplicitAnswer() throws Exception {run("openai");}
    @Test void ollamaWireAndProductionTelemetryPreserveTheExplicitAnswer() throws Exception {run("ollama");}
    @Test void unqualifiedNativeMainTaskFailsBeforeHttpAndLeavesNoUnknownOrFinal() throws Exception {run("openai",true);}
    private void run(String kind) throws Exception {
        run(kind,false);
    }
    private void run(String kind,boolean nativeRequired) throws Exception {
        String answer="客户是 Northwind Demo，金额是 83。\n503 的服务端原因未知。";
        String encoded=EcosystemNaturalTaskIT.JSON.writeValueAsString(Map.of("userAnswer",answer));
        AtomicInteger calls=new AtomicInteger();AtomicInteger metadataCalls=new AtomicInteger();AtomicReference<String> request=new AtomicReference<>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            if(exchange.getRequestURI().getPath().equals("/api/show")) {
                metadataCalls.incrementAndGet();exchange.getRequestBody().readAllBytes();
                byte[] metadata="{\"model_info\":{\"general.architecture\":\"fixture\",\"fixture.context_length\":131072}}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200,metadata.length);try(var out=exchange.getResponseBody()){out.write(metadata);}return;
            }
            calls.incrementAndGet();request.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            var body=kind.equals("openai")?Map.of("id","fixture-response","choices",List.of(Map.of("finish_reason","stop",
                    "message",Map.of("role","assistant","content",encoded))),"usage",Map.of("prompt_tokens",100,"completion_tokens",50,"total_tokens",150))
                    :Map.of("message",Map.of("role","assistant","content",encoded),"done",true,"done_reason","stop","prompt_eval_count",100,"eval_count",50);
            byte[] bytes=EcosystemNaturalTaskIT.JSON.writeValueAsBytes(body);exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}
        });
        server.start();
        try {
            String base="http://127.0.0.1:"+server.getAddress().getPort();
            Config config=Config.defaults().merge(EcosystemNaturalTaskIT.JSON.readValue("""
                    {"provider":"%s","model":"fixture","base_url":"%s","approval_mode":"on-request",
                     "enable_plan":false,"enable_verify":false,"enable_repair":false,"enable_skills":false,
                     "enable_hooks":false,"max_iterations":3,"context_window_tokens":131072}
                    """.formatted(kind,base),Config.class));
            if(nativeRequired)config=config.merge(EcosystemNaturalTaskIT.JSON.readValue(
                    "{\"provider\":\"openai\",\"provider_preset\":\"deepseek\",\"model\":\"deepseek-v4-flash\",\"thinking\":\"disabled\"}",Config.class));
            ModelProvider provider=nativeRequired?new EcosystemD7SmokeIT.BoundedProvider(
                    new OpenAICompatibleProvider(base,"local-fixture-only",config.model(),config.effectiveSystemPrompt(),5,
                            OpenAICompatiblePreset.from(config),"disabled",null),
                    new EcosystemD7SmokeIT.Budget(work.resolve("budget.jsonl"),java.math.BigDecimal.ONE),"capability",1,1,
                    OutputRequirement.Mode.NATIVE_REQUIRED):kind.equals("openai")?new OpenAICompatibleProvider(base,"local-fixture-only","fixture",config.effectiveSystemPrompt(),30)
                    :ProviderFactory.create(config);
            assertTrue(provider.usesManagedAnswerEnvelope());
            String taskId;
            Config effective=config;
            try(var runtime=InteractiveRuntimeAdapter.open(work,new ProductionInteractiveTaskExecutor(work,()->effective,ignored->provider),event->{})) {
                var session=EcosystemNaturalTaskIT.configuredSession("wire",work,config);session.start();runtime.openSession(session);
                runtime.submit(new InteractiveTaskRequest("wire",work,"请用中文报告客户、金额与未知原因。",List.of()));
                var task=runtime.activeForegroundTask().orElseThrow();taskId=task.taskId();
                var result=task.terminal().get(15,TimeUnit.SECONDS);
                assertEquals(nativeRequired?TaskSubmissionResult.Status.FAILED:TaskSubmissionResult.Status.COMPLETED,result.status(),result.toString());
                if(nativeRequired)assertTrue(result.message().contains("OUTPUT_REQUIREMENT_REJECTED"),result.toString());
            }
            if(nativeRequired){
                assertEquals(0,calls.get());
                try(var db=DriverManager.getConnection("jdbc:sqlite:"+work.resolve(".paicli/state/paicli.db"))){
                    for(String query:List.of("SELECT count(*) FROM task_user_results", "SELECT count(*) FROM events WHERE type='FINAL'",
                            "SELECT count(*) FROM side_effect_occurrences WHERE status IN ('STARTED','UNKNOWN')"))
                        try(var q=db.createStatement();var rows=q.executeQuery(query)){assertTrue(rows.next());assertEquals(0,rows.getInt(1));}
                }
                return;
            }
            assertEquals(1,calls.get());assertTrue(request.get().contains("PAICLI_USER_ANSWER_V1"));
            assertEquals(kind.equals("ollama")?1:0,metadataCalls.get(),"Ollama metadata lookup is not a generation request");
            try(var db=DriverManager.getConnection("jdbc:sqlite:"+work.resolve(".paicli/state/paicli.db"));
                var q=db.prepareStatement("SELECT u.content,o.occurrence_id,u.content_hash,o.result_hash FROM task_user_results u JOIN side_effect_occurrences o ON u.task_id=o.task_id AND u.run_id=o.run_id AND o.final_candidate_digest IS NOT NULL WHERE u.task_id=?")) {
                q.setString(1,taskId);try(var r=q.executeQuery()) {
                    assertTrue(r.next());assertEquals(answer,r.getString(1));assertNotNull(r.getString(2));
                    assertEquals(com.paicli.skill.SkillPackageFiles.hash(answer.getBytes(StandardCharsets.UTF_8)),r.getString(3));
                    assertEquals(com.paicli.state.FencedMutationStore.providerFinalResultHash(answer),r.getString(4));assertFalse(r.next());
                }
            }
        } finally {server.stop(0);}
    }
}
