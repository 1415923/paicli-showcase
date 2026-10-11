package com.paicli.mcp.recovery;

import com.paicli.mcp.R3TestSupport;
import com.paicli.mcp.catalog.McpToolCatalogService;
import com.paicli.mcp.manifest.McpConfigManifestBuilder;
import com.paicli.mcp.runtime.McpLiveSessionRegistry;
import com.paicli.state.McpToolCallStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class McpToolCallRecoveryIT {
 @TempDir Path root;
 @Test void pendingIsCancelledAndRunningBecomesUnknownWithoutReplay()throws Exception{
  try(var opened=R3TestSupport.open(root,"SINGLE_PAGE",List.of("echo"))){
   var catalog=new McpToolCatalogService(opened.state().mcpToolCatalogs,opened.state().mcpTools).build(opened.lifecycle()).catalog();
   var tool=opened.state().mcpTools.findExposedToolsByCatalog(catalog.catalogId()).getFirst();
   create(opened,"pending",tool,catalog);create(opened,"running",tool,catalog);opened.state().mcpToolCalls.approveAndMarkRunning("running","71");
   var recovery=new McpRecoveryService(opened.state().mcpSessions,opened.state().mcpToolCalls,opened.state().mcpToolCatalogs,new McpLiveSessionRegistry());
   var reports=recovery.reconcileInterruptedSession(opened.lifecycle().persisted().sessionId(),opened.lifecycle().config(),opened.lifecycle().manifest());
   assertEquals("CANCELLED",opened.state().mcpToolCalls.findById("pending").orElseThrow().status());
   assertEquals("UNKNOWN_OUTCOME",opened.state().mcpToolCalls.findById("running").orElseThrow().status());
   assertEquals(McpToolCallRecoveryReport.OutcomeCertainty.UNKNOWN_EXTERNAL_OUTCOME,recovery.inspectToolCall("running").outcomeCertainty());
   assertTrue(reports.stream().noneMatch(McpToolCallRecoveryReport::retryAutomatically));
   assertEquals("FAILED",opened.state().mcpSessions.findById(opened.lifecycle().persisted().sessionId()).orElseThrow().status());
  }
 }
 @Test void concurrentRunningRecoveryHasSingleTerminalWinner()throws Exception{
  try(var opened=R3TestSupport.open(root,"SINGLE_PAGE",List.of("echo"))){var catalog=new McpToolCatalogService(opened.state().mcpToolCatalogs,opened.state().mcpTools).build(opened.lifecycle()).catalog();var tool=opened.state().mcpTools.findExposedToolsByCatalog(catalog.catalogId()).getFirst();create(opened,"running",tool,catalog);opened.state().mcpToolCalls.approveAndMarkRunning("running","72");
   try(var executor=Executors.newVirtualThreadPerTaskExecutor()){var a=executor.submit(()->opened.state().mcpToolCalls.markUnknownOutcome("running"));var b=executor.submit(()->opened.state().mcpToolCalls.markUnknownOutcome("running"));assertEquals(1,(a.get()?1:0)+(b.get()?1:0));}
   assertEquals("UNKNOWN_OUTCOME",opened.state().mcpToolCalls.findById("running").orElseThrow().status());
  }
 }
 private void create(R3TestSupport.Opened o,String id,com.paicli.state.McpToolStore.ToolRecord t,com.paicli.state.McpToolCatalogStore.CatalogRecord c)throws Exception{o.state().mcpToolCalls.createToolCall(new McpToolCallStore.CallCreate(id,o.lifecycle().persisted().sessionId(),c.catalogId(),t.toolId(),null,null,id,1,null,o.lifecycle().manifest().configHash(),c.catalogHash(),t.schemaHash(),t.exposedName(),t.originalName(),"a".repeat(64),"fields=[]","PENDING"));}
}