package com.paicli.interactive.runtime;

import com.paicli.provider.*;
import com.paicli.provider.telemetry.ProviderCallObservation;
import com.paicli.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DeliveryRecheckBudgetTest {
    @TempDir Path work;
    private ProviderCallObservation usage(){return new ProviderCallObservation(1000,100,1100,600,null,null,
            ProviderCallObservation.UsageSource.PROVIDER_REPORTED,ProviderCallObservation.UsageCoverage.COMPLETE,
            null,null,null,null,null,false);}
    @Test void requestCapCountsAcrossPhasesRetriesAndReloads()throws Exception{
        var budget=new EcosystemD7SmokeIT.Budget(work.resolve("ledger"),BigDecimal.ONE);
        for(int i=0;i<2;i++)budget.settle(budget.reserve("task","same-retry-digest",new BigDecimal("0.4"),2),usage());
        var reloaded=new EcosystemD7SmokeIT.Budget(work.resolve("ledger"),BigDecimal.ONE);
        assertEquals("RECHECK_HTTP_LIMIT",assertThrows(IllegalStateException.class,()->
                reloaded.reserve("task","next-phase",new BigDecimal("0.4"),2)).getMessage());
        reloaded.settle(reloaded.reserve("second-task","next",new BigDecimal("0.4"),2),usage());
        assertEquals(0,reloaded.spent.compareTo(new BigDecimal("0.006480")));
    }
    @Test void qualityScopeCannotExpandOrReplaceOldRecheckCases()throws Exception{
        var properties=List.of("jdk.httpclient.disableRetryConnect","jdk.httpclient.enableAllMethodRetry","jdk.httpclient.redirects.retrylimit");
        var before=new java.util.HashMap<String,String>();properties.forEach(p->before.put(p,System.getProperty(p)));
        try{
            System.setProperty(properties.get(0),"true");System.setProperty(properties.get(1),"false");System.setProperty(properties.get(2),"1");
            var manifest=EcosystemNaturalTaskIT.JSON.readTree("{\"kind\":\"quality-recheck-v1\",\"budgetCny\":\"1\",\"maximumTasks\":2,\"rows\":[{\"case\":\"N3\"},{\"case\":\"N1\"}]}");
            assertEquals(2,EcosystemNaturalTaskIT.authorizedTaskCount(manifest));
            assertEquals("1",DeliveryRecheckLimits.budget(manifest));
            ((com.fasterxml.jackson.databind.node.ObjectNode)manifest.path("rows").get(0)).put("case","task-016");
            assertThrows(AssertionError.class,()->EcosystemNaturalTaskIT.authorizedTaskCount(manifest));
            ((com.fasterxml.jackson.databind.node.ObjectNode)manifest).put("kind","delivery-recheck-v1");
            ((com.fasterxml.jackson.databind.node.ObjectNode)manifest.path("rows").get(1)).put("case","N3");
            assertEquals(2,EcosystemNaturalTaskIT.authorizedTaskCount(manifest));
            ((com.fasterxml.jackson.databind.node.ObjectNode)manifest).put("maximumTasks",3);
            assertThrows(AssertionError.class,()->EcosystemNaturalTaskIT.authorizedTaskCount(manifest));
        }finally{before.forEach((key,value)->{if(value==null)System.clearProperty(key);else System.setProperty(key,value);});}
    }
    @Test void outstandingOrUnknownUsageStopsTheWholeCampaign()throws Exception{
        var budget=new EcosystemD7SmokeIT.Budget(work.resolve("ledger"),BigDecimal.ONE);
        String id=budget.reserve("first","digest",new BigDecimal("0.4"),22);
        var recovered=new EcosystemD7SmokeIT.Budget(work.resolve("ledger"),BigDecimal.ONE);
        assertThrows(IllegalStateException.class,()->recovered.reserve("second","digest",new BigDecimal("0.4"),24));
        budget.settle(id,ProviderCallObservation.notExposed());
        assertThrows(IllegalStateException.class,()->budget.reserve("second","digest",new BigDecimal("0.4"),24));
    }
    @Test void reservationCannotExceedRemainingBudgetOrBeSettledTwice()throws Exception{
        var budget=new EcosystemD7SmokeIT.Budget(work.resolve("ledger"),BigDecimal.ONE);
        String id=budget.reserve("first","digest",new BigDecimal("0.7"),22);
        assertThrows(IllegalStateException.class,()->budget.reserve("first","digest",new BigDecimal("0.4"),22));
        budget.settle(id,usage());
        assertThrows(IllegalStateException.class,()->budget.settle(id,usage()));
    }
    @Test void inspectionBindsActualProductionRequestAndRejectsOtherBytes()throws Exception{
        var preset=OpenAICompatiblePreset.from(EcosystemNaturalTaskIT.JSON.readValue(
                "{\"provider\":\"openai\",\"provider_preset\":\"deepseek\"}",com.paicli.config.Config.class));
        var provider=new OpenAICompatibleProvider("http://localhost:1","unused","deepseek-v4-flash",
                "scripted system",1,preset,"disabled",null);
        var messages=List.of(AgentMessage.userGoal("Inspect 中文 and punctuation"));
        var prepared=provider.prepareTurn("CHAT",messages,List.of());
        var reserve=DeliveryRecheckLimits.requestReserve(prepared);
        assertTrue(reserve.compareTo(BigDecimal.ONE)<0);assertTrue(reserve.compareTo(new BigDecimal("0.294912"))>0);
        assertThrows(IllegalStateException.class,()->DeliveryRecheckLimits.requestReserve(new com.paicli.provider.PreparedProviderTurn(prepared.requestDigest(), ()->com.paicli.model.ModelResponse.finalAnswer("unused"))));
    }

    @Test void samePreparedRequestCannotSendBeyondHttpCap()throws Exception{
        var hits=new java.util.concurrent.atomic.AtomicInteger();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/chat/completions",exchange->{
            hits.incrementAndGet();exchange.getRequestBody().readAllBytes();
            byte[] response="{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"done\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1000,\"completion_tokens\":100,\"total_tokens\":1100}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });server.start();
        try{
            var preset=OpenAICompatiblePreset.from(EcosystemNaturalTaskIT.JSON.readValue(
                    "{\"provider\":\"openai\",\"provider_preset\":\"deepseek\"}",com.paicli.config.Config.class));
            var raw=new OpenAICompatibleProvider("http://127.0.0.1:"+server.getAddress().getPort(),"unused",
                    "deepseek-v4-flash","system",5,preset,"disabled",null);
            var budget=new EcosystemD7SmokeIT.Budget(work.resolve("http-ledger"),BigDecimal.ONE);
            var bounded=new EcosystemD7SmokeIT.BoundedProvider(raw,budget,"task",2,22);
            var turn=bounded.prepareTurn("PLAN",List.of(AgentMessage.userGoal("inspect")),List.of());
            turn.execute();turn.execute();
            assertThrows(IllegalStateException.class,turn::execute);
            assertThrows(IllegalStateException.class,()->bounded.prepareTurn("REPAIR",List.of(AgentMessage.userGoal("repair")),List.of()).execute());
            assertEquals(2,hits.get());assertEquals(2,bounded.attempts);
        }finally{server.stop(0);}
    }
}
