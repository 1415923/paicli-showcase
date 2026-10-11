package com.paicli.interactive.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.Config;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.task.*;
import com.paicli.mcp.R3TestSupport;
import com.paicli.mcp.config.*;
import com.paicli.mcp.interactive.InteractiveMcpService;
import com.paicli.mcp.tool.McpToolNames;
import com.paicli.model.*;
import com.paicli.provider.ModelProvider;
import com.paicli.state.StateStore;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class McpProductionFaultIT {
    @TempDir Path workspace;
    private final ObjectMapper json=new ObjectMapper();

    @ParameterizedTest
    @ValueSource(strings={"deny","invalid","unknown","arguments","config","cancel","timeout","exit"})
    void productionGateAndLifecycleFailClosed(String scenario) throws Exception {
        String mode=switch(scenario){case "blocked-cancel","blocked-timeout"->"BLOCK_STDIN";case "cancel","timeout"->"HANG_CALL";case "exit"->"CRASH";default->"RECEIVE_PROBE";};
        var defaults=McpRuntimeLimits.defaults();
        var limits=new McpRuntimeLimits(10000,scenario.endsWith("timeout")?1000:30000,200,16,1048576,65536,32,256,4194304,262144,262144);
        var server=R3TestSupport.config(workspace,mode,List.of("echo"),limits);
        Path source=workspace.resolve("import.json");json.writeValue(source.toFile(),new McpClientConfig(List.of(server)));
        var arguments=new java.util.concurrent.ConcurrentHashMap<String,Object>();
        arguments.put("value",scenario.equals("invalid")?123:"before");
        if(scenario.startsWith("blocked"))arguments.put("value","x".repeat(60000));
        String tool=McpToolNames.alias("fake",scenario.equals("unknown")?"not_allowlisted":"echo");
        AtomicInteger calls=new AtomicInteger();var observations=new CopyOnWriteArrayList<String>();
        ModelProvider model=(messages,definitions)->{
            observations.addAll(messages.stream().map(AgentMessage::content).filter(Objects::nonNull).toList());
            return calls.getAndIncrement()==0?ModelResponse.toolCallResponse("fault fixture",new ToolCall(tool,arguments)):
                    ModelResponse.finalAnswer("External failure recorded; no further call requested.");
        };
        Config config=Config.defaults().merge(json.readValue("""
                {"provider":"ollama","model":"fixture","approval_mode":"on-request",
                 "enable_plan":false,"enable_verify":false,"enable_repair":false,
                 "enable_skills":false,"enable_hooks":false,"max_iterations":3,"context_window_tokens":131072}
                """,Config.class));
        String task;int approvals=0;
        try(var runtime=InteractiveRuntimeAdapter.open(workspace,new ProductionInteractiveTaskExecutor(workspace,()->config,ignored->model),event->{})) {
            var session=new InteractiveSession("fault",workspace,"ollama","fixture","on-request",InteractiveSession.TerminalMode.PLAIN,Instant.now());
            session.start();runtime.openSession(session);
            assertEquals("MCP",runtime.mcpCommand(List.of("config","import",source.toString()),session).code());
            var preview=runtime.mcpCommand(List.of("connect","fake"),session);
            String confirmation=preview.lines().getLast().substring(preview.lines().getLast().lastIndexOf(' ')+1);
            var connected=runtime.mcpCommand(List.of("connect","fake",confirmation),session);
            assertEquals("MCP",connected.code(),connected.toString());
            runtime.submit(new InteractiveTaskRequest("fault",workspace,"Exercise dedicated fault fixture",List.of()));
            var foreground=runtime.activeForegroundTask().orElseThrow();task=foreground.taskId();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(25);Set<String> answered=new HashSet<>();boolean cancelled=false;long approvedAt=0;
            while(!foreground.terminal().isDone()&&System.nanoTime()<deadline) {
                var pending=runtime.pendingApproval();
                if(pending.isPresent()&&answered.add(pending.get().requestId())) {
                    if(scenario.equals("arguments"))arguments.put("value","changed");
                    if(scenario.equals("config"))Files.writeString(workspace.resolve(".paicli/mcp.json")," ",StandardOpenOption.APPEND);
                    assertTrue(runtime.respondApproval(pending.get().requestId(),scenario.equals("deny")?"n":"y").resolved());approvals++;approvedAt=System.nanoTime();
                }
                if(!cancelled && (scenario.equals("cancel")&&Files.exists(workspace.resolve("tools-call-received"))
                        || scenario.equals("blocked-cancel")&&approvedAt>0&&System.nanoTime()-approvedAt>TimeUnit.MILLISECONDS.toNanos(300))) {
                    runtime.requestCancellation();cancelled=true;
                }
                Thread.sleep(10);
            }
            foreground.terminal().get(1,TimeUnit.SECONDS);
            assertTrue(runtime.awaitIdle(Duration.ofSeconds(3)));
            assertEquals(Set.of("invalid","unknown").contains(scenario)?0:1,approvals);
            if(scenario.equals("cancel"))assertTrue(cancelled);
            if(Set.of("cancel","timeout","exit","blocked-cancel","blocked-timeout").contains(scenario))
                assertTrue(runtime.mcpCommand(List.of("status"),session).lines().getFirst().contains("stale"));
            else assertFalse(Files.exists(workspace.resolve("tools-call-received")),scenario+" must send zero requests; "+observations);
        }
        try(var state=new StateStore(workspace);var service=new InteractiveMcpService(workspace,state)) {
            assertTrue(state.mcpTaskBindings.find(task).isPresent());
            assertThrows(IllegalStateException.class,()->service.verifyRecovery(task),"connected Task cannot resume disconnected");
            if(Set.of("cancel","timeout","exit","blocked-cancel","blocked-timeout").contains(scenario))assertTrue(state.mcpTaskBindings.hasUncertainCalls(task));
        }
    }
}
