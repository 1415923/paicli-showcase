package com.paicli.interactive.runtime;

import com.paicli.benchmark.*;
import com.paicli.config.Config;
import com.paicli.credential.ProviderCredentialResolver;
import com.paicli.model.*;
import com.paicli.provider.*;
import com.paicli.runtime.CodingTaskObserver;
import com.paicli.safety.ApprovalPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigDecimal;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.paicli.interactive.runtime.EcosystemNaturalTaskIT.*;
import static org.junit.jupiter.api.Assertions.*;

/** Current-source diagnostic adapter; reuses both H0 drivers and the natural-task request ledger. */
@EnabledIfSystemProperty(named="paicli.natural.mode",matches="preflight|paid")
class InterviewCodingTaskIT {
    @Test void executeOneFrozenRow() throws Exception {
        Path manifestPath=Path.of(System.getProperty("paicli.natural.manifest"));
        var manifest=JSON.readTree(Files.readString(manifestPath));
        assertTrue(Set.of("interview-validation-v1","delivery-recheck-v1").contains(manifest.path("kind").asText()));
        int count=authorizedTaskCount(manifest);
        for(var entry:manifest.path("frozenFiles"))
            assertEquals(entry.path("sha256").asText(),hash(Path.of(entry.path("path").asText())));
        String id=System.getProperty("paicli.natural.row");
        var row=java.util.stream.StreamSupport.stream(manifest.path("rows").spliterator(),false)
                .filter(r->id.equals(r.path("id").asText())).findFirst().orElseThrow();
        assertEquals("coding",row.path("family").asText());
        boolean paid="paid".equals(System.getProperty("paicli.natural.mode"));
        String mode=paid?"paid":"preflight";
        Path work=Path.of(row.path(mode+"Workspace").asText());
        Path control=Path.of(row.path(mode+"Control").asText());
        Path campaign=Path.of(manifest.path("paidCampaign").asText());
        var task=JSON.readTree(Files.readString(Path.of(row.path("taskPath").asText())));
        Config config=Config.defaults().merge(JSON.treeToValue(row.path("config"),Config.class));
        assertFalse(Files.exists(control.resolve("started.json")),"No row replacement");
        if(paid){
            var authorization=JSON.readTree(Files.readString(Path.of(System.getProperty("paicli.natural.authorization"))));
            assertEquals(hash(manifestPath),authorization.path("manifestSha256").asText());
            assertEquals(count,authorization.path("maximumTasks").asInt());
            assertEquals(DeliveryRecheckLimits.budget(manifest),authorization.path("budgetCny").asText());
            assertTrue(ProviderCredentialResolver.systemDefault().status(config).present(),"Credential required");
        }else assertEquals(DeliveryRecheckLimits.isRecheck(manifest)?"task-016":"task-001",row.path("case").asText());
        Files.createDirectories(campaign);Files.createDirectories(control);
        try(var channel=FileChannel.open(campaign.resolve("campaign.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            var lock=channel.tryLock()){
            assertNotNull(lock,"Another row is active");
            write(control.resolve("started.json"),Map.of("id",id,"mode",mode,"manifestSha256",hash(manifestPath),"startedAt",Instant.now().toString()));
            var result=new LinkedHashMap<String,Object>();
            result.put("id",id);result.put("mode",mode);result.put("intent","DIAGNOSTIC");result.put("status","INTERRUPTED");
            EcosystemNaturalTaskIT.CapturingProvider capturing=null;
            try{
                ModelProvider provider;
                if(paid){
                    var budget=new EcosystemD7SmokeIT.Budget(campaign.resolve("budget-attempts.jsonl"),new BigDecimal(DeliveryRecheckLimits.budget(manifest)));
                    capturing=new CapturingProvider(DeliveryRecheckLimits.provider(
                            (OpenAICompatibleProvider)ProviderFactory.create(config),budget,id,manifest,row),control,"after");
                    provider=capturing;
                }else provider=readOnlyPreflight(control,DeliveryRecheckLimits.isRecheck(manifest));
                var approval=new PredeclaredApprovalControl((iteration,call)->
                        "workspace-write-v1".equals(task.path("approvalProfile").asText()));
                String prompt=Files.readString(Path.of(row.path("promptPath").asText()));
                if("PAICLI".equals(row.path("runtime").asText())){
                    var run=new PaiCliBenchmarkTreatment(work,provider,config,approval,CodingTaskObserver.NOOP).run(prompt);
                    result.put("run",run);result.put("status",run.taskCompleted()?"COMPLETED":"FAILED");
                    result.put("answer",Objects.toString(run.durableFormalResult(),""));
                    result.put("runState",run.runPhase());result.put("formalResultDurable",run.durableFormalResultPresent());
                    result.put("toolCalls",run.toolCalls());result.put("providerTurns",run.providerTurns());
                }else{
                    assertEquals("SIMPLE_TOOL_LOOP",row.path("runtime").asText());
                    var run=new SimpleToolLoopBaseline(provider,BenchmarkToolRegistryFactory.create(work),
                            new ApprovalPolicy(config,"interview-simple"),approval,config.maxIterations()).run(prompt);
                    result.put("run",run);
                    boolean completed=run.agentResult()!=null&&run.agentResult().completed();
                    result.put("status",completed?"COMPLETED":"FAILED");result.put("runState",completed?"COMPLETED":"FAILED");
                    result.put("answer",run.agentResult()==null?"":Objects.toString(run.agentResult().answer(),""));
                    result.put("formalResultDurable",null);result.put("toolCalls",run.toolCalls());result.put("providerTurns",run.providerTurns());
                }
                if(!paid){assertEquals("COMPLETED",result.get("status"));assertEquals(1,((Number)result.get("toolCalls")).intValue());}
            }finally{
                result.put("finishedAt",Instant.now().toString());result.put("httpAttempts",capturing==null?0:capturing.delegate.attempts);
                write(control.resolve("result.json"),result);
            }
        }
    }

    private static PreparedTurnProvider readOnlyPreflight(Path control,boolean pagination){
        var ordinal=new AtomicInteger();var chat=new AtomicInteger();
        return (operation,messages,definitions)->{
            int n=ordinal.incrementAndGet();
            writeUnchecked(control.resolve("preflight-input-"+n+".json"),Map.of("operation",operation,"messages",messages,"definitions",definitions));
            return new PreparedProviderTurn(String.format("%064d",n),()->{
                if(pagination&&"PLAN".equals(operation))return ModelResponse.finalAnswer("""
                    {"acceptanceCriteria":["Fix page boundaries without changing tests"],"assumptions":[],"risks":[],
                     "verificationStrategy":"Run existing Maven tests", "steps":[{"title":"Fix pagination",
                     "definition":"Correct Pagination.pageCount and verify unchanged tests","dependsOn":[]}]}
                    """);
                if("PLAN".equals(operation))return ModelResponse.finalAnswer("""
                    {"acceptanceCriteria":["Locate port configuration without edits"],"assumptions":[],"risks":[],
                     "verificationStrategy":"Read AppConfig", "steps":[{"title":"Read configuration",
                     "definition":"Read AppConfig.java and report default and environment variable","dependsOn":[]}]}
                    """);
                if(chat.getAndIncrement()==0&&pagination)return ModelResponse.toolCallResponse("Fix scripted fixture",
                        new ToolCall("write_file",Map.of("path","src/main/java/fixture/Pagination.java",
                            "overwrite",true,
                            "content","package fixture; public final class Pagination { public int pageCount(int items,int pageSize){ return items/pageSize+(items%pageSize==0?0:1); } }"),"recheck-fix"));
                if(chat.get()==1&&!pagination)return ModelResponse.toolCallResponse("Inspect configuration",
                        new ToolCall("read_file",Map.of("path","src/main/java/fixture/AppConfig.java"),"iv-preflight-read"));
                assertTrue(messages.stream().anyMatch(m->m.type()==AgentMessage.MessageType.OBSERVATION));
                if(pagination)return ModelResponse.finalAnswer("Scripted pagination fixture corrected and verified; zero model HTTP calls.");
                return ModelResponse.finalAnswer("AppConfig defaults to port 8080; PAICLI_PORT overrides it. Scripted entry check only.");
            });
        };
    }
}
