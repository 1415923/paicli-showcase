package com.paicli.mcp.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.Config;
import com.paicli.mcp.R3TestSupport;
import com.paicli.mcp.approval.*;
import com.paicli.mcp.catalog.McpToolCatalogService;
import com.paicli.safety.ApprovalPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import com.paicli.mcp.config.McpRuntimeLimits;

import static org.junit.jupiter.api.Assertions.*;

class McpToolCallServiceIT {
    @TempDir Path temp;
    private ApprovalPolicy policy(String mode){return new ApprovalPolicy(new Config(".",30,50000,200000,mode,10000),"task");}
    @Test void approvedCallUsesOriginalNameSanitizesAndAudits()throws Exception{
        try(var opened=R3TestSupport.open(temp,"SINGLE_PAGE",List.of("echo"))){
            var built=new McpToolCatalogService(opened.state().mcpToolCatalogs,opened.state().mcpTools).build(opened.lifecycle());var tool=built.tools().getFirst();
            var service=new McpToolCallService(opened.lifecycle(),opened.state().mcpToolCatalogs,opened.state().mcpTools,opened.state().mcpToolCalls,new McpApprovalGate(policy(Config.MODE_ON_REQUEST),r->McpApprovalChannel.Decision.APPROVE));
            var result=service.execute(tool,new ObjectMapper().readTree("{\"value\":\"hello\"}"),"call-ok",1,null,null);
            assertEquals("SUCCEEDED",result.status());assertTrue(result.output().content().startsWith("[UNTRUSTED MCP TOOL OUTPUT"));
            var row=opened.state().mcpToolCalls.findById(result.callId()).orElseThrow();assertEquals("APPROVED",row.approval());assertEquals("SUCCEEDED",row.status());assertNotNull(row.requestId());
        }
    }
    @Test void deniedAndInvalidArgumentsNeverRun()throws Exception{
        try(var opened=R3TestSupport.open(temp,"RECEIVE_PROBE",List.of("echo"))){
            var tool=new McpToolCatalogService(opened.state().mcpToolCatalogs,opened.state().mcpTools).build(opened.lifecycle()).tools().getFirst();
            var denied=new McpToolCallService(opened.lifecycle(),opened.state().mcpToolCatalogs,opened.state().mcpTools,opened.state().mcpToolCalls,new McpApprovalGate(policy(Config.MODE_READ_ONLY),r->McpApprovalChannel.Decision.APPROVE));
            assertEquals("APPROVAL_DENIED",assertThrows(McpToolCallException.class,()->denied.execute(tool,new ObjectMapper().readTree("{}"),"denied",1,null,null)).category());
            assertFalse(java.nio.file.Files.exists(temp.resolve("tools-call-received")));
            var approved=new McpToolCallService(opened.lifecycle(),opened.state().mcpToolCatalogs,opened.state().mcpTools,opened.state().mcpToolCalls,new McpApprovalGate(policy(Config.MODE_ON_REQUEST),r->McpApprovalChannel.Decision.APPROVE));
            assertEquals("ARGUMENTS_NOT_OBJECT",assertThrows(McpToolCallException.class,()->approved.execute(tool,new ObjectMapper().readTree("[]"),"invalid",1,null,null)).category());
            assertTrue(opened.state().mcpToolCalls.findByOccurrence(opened.lifecycle().persisted().sessionId(),"invalid").stream().allMatch(r->r.status().equals("SANITIZER_REJECTED")));
        }
    }
    @Test void blockedPipeHasDeadlineAndCancellationBeforeSendReturns() throws Exception {
        for(boolean child:List.of(false,true)) for(boolean cancel:List.of(false,true)) {
            Path root=temp.resolve((cancel?"cancel-pipe":"timeout-pipe")+(child?"-child":""));
            var limits=new McpRuntimeLimits(10000,1000,200,16,1048576,65536,32,256,4194304,262144,262144);
            try(var opened=R3TestSupport.open(root,child?"BLOCK_STDIN_CHILD":"BLOCK_STDIN",List.of("echo"),limits)) {
                var tool=new McpToolCatalogService(opened.state().mcpToolCatalogs,opened.state().mcpTools).build(opened.lifecycle()).tools().getFirst();
                ProcessHandle inherited=child?ProcessHandle.of(Long.parseLong(java.nio.file.Files.readString(root.resolve("pipe-holder.pid")))).orElseThrow():null;
                var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
                var token=new com.paicli.runtime.CancellationToken(){public boolean cancellationRequested(){return cancelled.get();}public boolean forceRequested(){return false;}};
                try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                    var call=pool.submit(()->approved(opened).execute(tool,new ObjectMapper().createObjectNode().put("value","x".repeat(60000)),
                            "blocked",1,null,null,null,()->{},token));
                    if(cancel){Thread.sleep(250);cancelled.set(true);}
                    var failure=assertThrows(java.util.concurrent.ExecutionException.class,()->call.get(5,java.util.concurrent.TimeUnit.SECONDS));
                    assertTrue(failure.getCause() instanceof McpToolCallException || failure.getCause() instanceof com.paicli.runtime.CancellationToken.TaskCancellationException);
                    assertFalse(opened.lifecycle().persisted().isProcessAlive(),"blocked writer must release its owned process");
                    if(inherited!=null)assertFalse(inherited.isAlive(),"inherited-stdin child must not survive or be reparented");
                    assertTrue(opened.state().mcpToolCalls.findByOccurrence(opened.lifecycle().persisted().sessionId(),"blocked").stream()
                            .allMatch(row->java.util.Set.of("UNKNOWN_OUTCOME","TIMED_OUT").contains(row.status())));
                }
            }
        }
    }

    @Test void cancellationClosesProcessEvenWhenAuditDatabaseIsClosed() throws Exception {
        var opened=R3TestSupport.open(temp,"HANG_CALL",List.of("echo"),
                new McpRuntimeLimits(10000,30000,200,16,1048576,65536,32,256,4194304,262144,262144));
        try {
            var tool=new McpToolCatalogService(opened.state().mcpToolCatalogs,opened.state().mcpTools).build(opened.lifecycle()).tools().getFirst();
            var cancelled=new java.util.concurrent.atomic.AtomicBoolean();
            var token=new com.paicli.runtime.CancellationToken(){public boolean cancellationRequested(){return cancelled.get();}public boolean forceRequested(){return false;}};
            try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var call=pool.submit(()->approved(opened).execute(tool,new ObjectMapper().createObjectNode(),"audit-failure",1,null,null,null,()->{},token));
                long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                while(!java.nio.file.Files.exists(temp.resolve("tools-call-received"))&&System.nanoTime()<deadline)Thread.sleep(10);
                assertTrue(java.nio.file.Files.exists(temp.resolve("tools-call-received")));
                opened.state().close();cancelled.set(true);
                assertThrows(java.util.concurrent.ExecutionException.class,()->call.get(5,java.util.concurrent.TimeUnit.SECONDS));
                assertFalse(opened.lifecycle().persisted().isProcessAlive(),"audit failure cannot retain process authority");
            }
        } finally { try{opened.close();}catch(Exception ignored){} }
    }

    @Test void distinguishesToolErrorJsonRpcErrorMalformedTimeoutAndExit()throws Exception{
        assertStatus("TOOL_ERROR","MCP_TOOL_ERROR");
        assertStatus("JSON_RPC_ERROR","JSON_RPC_ERROR");
        assertStatus("MALFORMED_CALL","MALFORMED_CALL_RESULT");
        assertStatus("CRASH","PROCESS_EXITED");
        McpRuntimeLimits d=McpRuntimeLimits.defaults();var shortTimeout=new McpRuntimeLimits(d.startupTimeoutMs(),100,d.shutdownTimeoutMs(),d.maxPendingRequests(),d.maxStdoutLineBytes(),d.maxStderrBytes(),d.maxCatalogPages(),d.maxTools(),d.maxCatalogBytes(),d.maxSingleSchemaBytes(),d.maxOutputBytes());
        try(var opened=R3TestSupport.open(temp.resolve("late"),"LATE",List.of("echo"),shortTimeout)){var tool=new McpToolCatalogService(opened.state().mcpToolCatalogs,opened.state().mcpTools).build(opened.lifecycle()).tools().stream().filter(t->t.originalName().equals("echo")).findFirst().orElseThrow();var service=approved(opened);assertEquals("TIMED_OUT",assertThrows(McpToolCallException.class,()->service.execute(tool,new ObjectMapper().readTree("{}"),"late",1,null,null)).category());}
    }
    private void assertStatus(String mode,String expected)throws Exception{Path root=temp.resolve(mode.toLowerCase());try(var opened=R3TestSupport.open(root,mode,List.of("echo"))){var tool=new McpToolCatalogService(opened.state().mcpToolCatalogs,opened.state().mcpTools).build(opened.lifecycle()).tools().stream().filter(t->t.originalName().equals("echo")).findFirst().orElseThrow();var service=approved(opened);if(mode.equals("TOOL_ERROR")){var result=service.execute(tool,new ObjectMapper().readTree("{}"),mode,1,null,null);assertEquals("TOOL_ERROR",result.status());}else assertEquals(expected,assertThrows(McpToolCallException.class,()->service.execute(tool,new ObjectMapper().readTree("{}"),mode,1,null,null)).category());}}
    private McpToolCallService approved(R3TestSupport.Opened opened){return new McpToolCallService(opened.lifecycle(),opened.state().mcpToolCatalogs,opened.state().mcpTools,opened.state().mcpToolCalls,new McpApprovalGate(policy(Config.MODE_ON_REQUEST),r->McpApprovalChannel.Decision.APPROVE));}
}
