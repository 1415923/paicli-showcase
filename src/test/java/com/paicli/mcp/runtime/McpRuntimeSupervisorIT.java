package com.paicli.mcp.runtime;

import com.paicli.mcp.R3TestSupport;
import com.paicli.mcp.catalog.McpToolCatalogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class McpRuntimeSupervisorIT {
 @TempDir Path root;
 @Test void lostLiveProcessFailsSessionAndCloseAllIsBounded()throws Exception{try(var opened=R3TestSupport.open(root,"SINGLE_PAGE",List.of("echo"))){new McpToolCatalogService(opened.state().mcpToolCatalogs,opened.state().mcpTools).build(opened.lifecycle());var supervisor=new McpRuntimeSupervisor(new McpLiveSessionRegistry(),opened.state().mcpSessions,opened.state().mcpToolCalls);supervisor.reconcileLostProcess(opened.lifecycle().persisted().sessionId());assertEquals("FAILED",opened.state().mcpSessions.findById(opened.lifecycle().persisted().sessionId()).orElseThrow().status());supervisor.closeAll();supervisor.closeAll();}}
}