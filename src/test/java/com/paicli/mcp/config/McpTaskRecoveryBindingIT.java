package com.paicli.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.Config;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.mcp.R3TestSupport;
import com.paicli.mcp.interactive.InteractiveMcpService;
import com.paicli.safety.ApprovalPolicy;
import com.paicli.state.*;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class McpTaskRecoveryBindingIT {
    @TempDir Path workspace;
    @Test void offeredCatalogSurvivesReconnectAndCannotBeReboundEvenWithoutAnyCalls() throws Exception {
        String task="tsk_0123456789ab4def8123456789abcdef";
        var session=new InteractiveSession("recovery",workspace,"ollama","fixture","on-request",InteractiveSession.TerminalMode.PLAIN,Instant.now());session.start();
        try(var state=new StateStore(workspace);var service=new InteractiveMcpService(workspace,state)) {
            state.tasks.createTask(task,"fixture","ollama","fixture","on-request",3);
            var config=R3TestSupport.config(workspace,"SINGLE_PAGE",List.of("echo"));
            Path source=workspace.resolve("source.json");new ObjectMapper().writeValue(source.toFile(),new McpClientConfig(List.of(config)));
            assertEquals("MCP",service.command(List.of("config","import",source.toString()),session).code());
            connect(service,session);
            try(var scope=service.acquireTask(task,false)) {
                assertEquals(1,service.derive(task,new ToolRegistry(),new ApprovalPolicy(Config.defaults().withApprovalMode("on-request"),task)).size());
            }
            var binding=state.mcpTaskBindings.find(task).orElseThrow();
            assertFalse(state.mcpTaskBindings.hasUncertainCalls(task));service.verifyRecovery(task);
            assertThrows(IllegalStateException.class,()->state.mcpTaskBindings.bind(new McpTaskBindingStore.Binding(task,"none","none","none")));
            assertEquals("MCP",service.command(List.of("disconnect"),session).code());
            assertThrows(IllegalStateException.class,()->service.verifyRecovery(task));
            connect(service,session);service.verifyRecovery(task);
            assertEquals(binding,state.mcpTaskBindings.find(task).orElseThrow());
            Files.writeString(workspace.resolve(".paicli/mcp.json")," ",StandardOpenOption.APPEND);
            assertThrows(IllegalStateException.class,()->service.verifyRecovery(task));
        }
    }
    private static void connect(InteractiveMcpService service,InteractiveSession session) {
        var preview=service.command(List.of("connect","fake"),session);
        String hash=preview.lines().getLast().substring(preview.lines().getLast().lastIndexOf(' ')+1);
        var result=service.command(List.of("connect","fake",hash),session);
        assertEquals("MCP",result.code(),result.toString());
    }
}
