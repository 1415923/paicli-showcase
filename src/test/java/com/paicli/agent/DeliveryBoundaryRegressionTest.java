package com.paicli.agent;

import com.paicli.config.Config;
import com.paicli.model.*;
import com.paicli.provider.*;
import com.paicli.state.StateStore;
import com.paicli.tool.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class DeliveryBoundaryRegressionTest {
    @TempDir Path workspace;

    @Test void deniedSingleAndBatchCallsPersistClosureAndPermitGroundedFinal() throws Exception {
        for(boolean batch:List.of(false,true))try(var state=new StateStore(workspace.resolve("case-"+batch))){
            String task="denial-"+batch;
            var calls=new ArrayList<ToolCall>();
            if(batch)calls.add(new ToolCall("read_file",Map.of("path","safe.txt"),"safe-call"));
            calls.add(new ToolCall("read_file",Map.of("path",".paicli/PRIVATE_CANARY",
                    "include_internal",true),"denied-call"));
            var registry=new ToolRegistry();
            registry.register(new Tool(){
                public String name(){return "read_file";}
                public String description(){return "read";}
                public ToolDefinition toDefinition(){return new ToolDefinition(name(),description(),
                        new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode());}
                public ToolResult execute(ToolCall call){fail("no batch member may execute");return null;}
            });
            AtomicInteger turns=new AtomicInteger();
            ModelProvider provider=(messages,tools)->{
                if(turns.getAndIncrement()==0)return batch
                        ?ModelResponse.toolCallBatchResponse("inspect",calls,
                            ProviderResponseEnvelope.inspect(List.of("safe-call","denied-call"),false),false)
                        :ModelResponse.toolCallResponse("inspect",calls.getFirst());
                var observations=messages.stream().filter(m->m.type()==AgentMessage.MessageType.OBSERVATION).toList();
                assertEquals(calls.stream().map(ToolCall::providerCallId).toList(),
                        observations.stream().map(AgentMessage::toolCallId).toList());
                assertTrue(observations.getLast().content().contains("CONTROL_DIRECTORY_ACCESS_NOT_USER_AUTHORIZED"));
                assertFalse(messages.toString().contains("PRIVATE_CANARY"));
                return ModelResponse.finalAnswer("The internal directory was unavailable; the review is incomplete.");
            };
            var result=new AgentLoop(provider,registry,3,null,null,state,task,"mock","model")
                    .run("Review the project");
            assertTrue(result.completed());assertEquals(2,turns.get());
            assertTrue(state.toolCalls.findByTask(task).isEmpty());
            assertTrue(state.approvals.findByTask(task).isEmpty());
            assertEquals(calls.size(),state.events.findMessages(task).stream()
                    .filter(m->m.type()==AgentMessage.MessageType.OBSERVATION).count());
        }
    }

    @Test void rawExecutionLedgerCannotBePersistedAsFinal() throws Exception {
        try(var state=new StateStore(workspace)){
            String task="ledger-final";
            ModelProvider provider=(messages,tools)->ModelResponse.finalAnswer(
                    "kind=TOOL_OCCURRENCE;source_id=occ_fixture;revision=hash;tool_row=10;tool_name=read_file;status=COMPLETED;success=1;output_present=1;error_present=0;external_effect=KNOWN_SUCCESS;artifact_reference=");
            var failure=assertThrows(AgentLoop.ProviderProtocolException.class,()->new AgentLoop(provider,
                    new ToolRegistry(),1,null,null,state,task,"mock","model").run("Fix pagination"));
            assertEquals("FINAL_RESPONSE_EXECUTION_LEDGER_REJECTED",failure.getMessage());
            assertTrue(state.events.findMessages(task).stream().noneMatch(m->m.type()==AgentMessage.MessageType.FINAL));
        }
        assertDoesNotThrow(()->com.paicli.runtime.FinalResponseContent.requireNotExecutionLedger(
                "The failure was caused by TOOL_OCCURRENCE metadata. Tests passed."));
    }
}
