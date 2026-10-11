package com.paicli.mcp.tool;

import com.paicli.config.Config;
import com.paicli.mcp.R3TestSupport;
import com.paicli.mcp.approval.*;
import com.paicli.mcp.catalog.McpToolCatalogService;
import com.paicli.mcp.runtime.*;
import com.paicli.safety.ApprovalPolicy;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class McpToolRegistryAdapterIT {
 @TempDir Path temp;
 @Test void derivesAtomicSnapshotWithoutMutatingParent()throws Exception{
  try(var opened=R3TestSupport.open(temp,"SINGLE_PAGE",List.of("echo"))){
   var built=new McpToolCatalogService(opened.state().mcpToolCatalogs,opened.state().mcpTools).build(opened.lifecycle());
   var policy=new ApprovalPolicy(new Config(".",30,50000,200000,Config.MODE_ON_REQUEST,10000),"task");
   var service=new McpToolCallService(opened.lifecycle(),opened.state().mcpToolCatalogs,opened.state().mcpTools,opened.state().mcpToolCalls,new McpApprovalGate(policy,r->McpApprovalChannel.Decision.DENY));
   var session=opened.state().mcpSessions.findById(opened.lifecycle().persisted().sessionId()).orElseThrow();var live=new McpLiveSessionRegistry();live.bind(new McpRuntimeSessionHandle(session.sessionId(),session.processIdentityHash(),opened.lifecycle().manifest().configHash(),built.catalog().catalogId(),built.catalog().catalogHash(),opened.lifecycle()),session);
   ToolRegistry parent=new ToolRegistry();ToolRegistry derived=new McpToolRegistryAdapter(opened.state().mcpToolCatalogs,opened.state().mcpTools,live).derive(parent,opened.lifecycle(),service,"registry");
   assertEquals(0,parent.size());assertEquals(1,derived.size());var definition=derived.allDefinitions().getFirst();assertEquals(com.paicli.mcp.tool.McpToolNames.alias("fake","echo"),definition.name());assertTrue(definition.description().contains("UNTRUSTED"));assertTrue(derived.lookup(com.paicli.mcp.tool.McpToolNames.alias("fake","echo")).orElseThrow() instanceof McpRegisteredTool);
  }
 }
}
