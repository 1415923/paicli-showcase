package com.paicli;

import com.paicli.agent.AgentLoop;
import com.paicli.agent.AgentLoopControl;
import com.paicli.agent.AgentLoopObserver;
import com.paicli.agent.AgentResult;
import com.paicli.benchmark.BenchmarkToolRegistryFactory;
import com.paicli.benchmark.PaiCliBenchmarkTreatment;
import com.paicli.benchmark.SimpleToolLoopBaseline;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolCall;
import com.paicli.provider.ModelProvider;
import com.paicli.provider.ProviderResponseEnvelope;
import com.paicli.safety.SafetyPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prevents the Simple Tool Loop from receiving a different public Tool schema/capability. */
class BenchmarkToolParityTest {
    @TempDir Path workspace;

    @Test
    void simpleLoopDefinitionsEqualPaiCliProductionDefinitions() {
        var baseline = BenchmarkToolRegistryFactory.create(workspace).allDefinitions();
        var treatment = PaiCLI.createToolRegistry(
                new SafetyPolicy(workspace), null, "benchmark-task").allDefinitions();
        assertEquals(treatment, baseline);
        assertEquals(PaiCliBenchmarkTreatment.sharedToolBatchPolicyHash(),
                SimpleToolLoopBaseline.sharedToolBatchPolicyHash());
        assertEquals(PaiCliBenchmarkTreatment.sharedToolBatchTelemetryHash(),
                SimpleToolLoopBaseline.sharedToolBatchTelemetryHash());
    }

    @Test
    void repeatedEffectfulBatchHasEquivalentBoundedSemanticsAcrossSharedLoops()
            throws Exception {
        Path simpleWorkspace=workspace.resolve("simple");
        Path paiCliWorkspace=workspace.resolve("paicli");
        Files.createDirectories(simpleWorkspace);
        Files.createDirectories(paiCliWorkspace);
        RepeatedBatchProvider simpleProvider=new RepeatedBatchProvider();
        RepeatedBatchProvider paiCliProvider=new RepeatedBatchProvider();

        SimpleToolLoopBaseline.RunResult simple=new SimpleToolLoopBaseline(simpleProvider,
                BenchmarkToolRegistryFactory.create(simpleWorkspace),null,
                AgentLoopControl.NONE,3).run("write safely");
        List<AgentLoopObserver.EffectfulBatchFact> paiCliTelemetry=new ArrayList<>();
        AgentLoopObserver observer=new AgentLoopObserver(){
            @Override public void effectfulBatchNormalized(String taskId,int iteration,
                    EffectfulBatchFact fact){paiCliTelemetry.add(fact);}
        };
        AgentResult paiCli=new AgentLoop(paiCliProvider,
                BenchmarkToolRegistryFactory.create(paiCliWorkspace),3,null,null,
                null,null,null,null,observer).run("write safely");

        assertEquals(AgentResult.Outcome.MAX_ITERATIONS,simple.agentResult().outcome());
        assertEquals(simple.agentResult().outcome(),paiCli.outcome());
        assertEquals(3,simpleProvider.calls.get());
        assertEquals(simpleProvider.calls.get(),paiCliProvider.calls.get());
        assertEquals(simple.effectfulBatchTelemetry(),paiCliTelemetry);
        assertTrue(paiCliTelemetry.stream().allMatch(event->event.normalizationOutcome()
                ==AgentLoopObserver.EffectfulBatchFact.NormalizationOutcome
                        .SERIALIZATION_CONTINUATION_ISSUED));
        for(int turn=1;turn<=3;turn++)for(String suffix:List.of("a","b")){
            assertFalse(Files.exists(simpleWorkspace.resolve("blocked-"+turn+"-"+suffix+".txt")));
            assertFalse(Files.exists(paiCliWorkspace.resolve("blocked-"+turn+"-"+suffix+".txt")));
        }
    }

    private static final class RepeatedBatchProvider implements ModelProvider {
        private final AtomicInteger calls=new AtomicInteger();

        @Override public ModelResponse chat(List<com.paicli.model.AgentMessage> messages,
                List<com.paicli.model.ToolDefinition> tools){
            int turn=calls.incrementAndGet();
            ToolCall first=write(turn,"a");
            ToolCall second=write(turn,"b");
            return ModelResponse.toolCallBatchResponse("repeat",List.of(first,second),
                    ProviderResponseEnvelope.inspect(List.of(first.providerCallId(),
                            second.providerCallId()),false),false);
        }

        private static ToolCall write(int turn,String suffix){
            String id="batch-"+turn+"-"+suffix;
            return new ToolCall("write_file",Map.of(
                    "path","blocked-"+turn+"-"+suffix+".txt","content","blocked"),id);
        }
    }
}
