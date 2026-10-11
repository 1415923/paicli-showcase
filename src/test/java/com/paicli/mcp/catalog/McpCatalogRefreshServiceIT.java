package com.paicli.mcp.catalog;

import com.paicli.mcp.R3TestSupport;
import com.paicli.mcp.lifecycle.McpLifecycleService;
import com.paicli.mcp.runtime.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class McpCatalogRefreshServiceIT {
 @TempDir Path root;
 @Test void staleCatalogRequiresExplicitNewSessionAndNeverHotReplacesOldArtifact()throws Exception{
  try(var opened=R3TestSupport.open(root,"SINGLE_PAGE",List.of("echo"))){
   var catalogService=new McpToolCatalogService(opened.state().mcpToolCatalogs,opened.state().mcpTools);
   var old=catalogService.build(opened.lifecycle()).catalog();opened.state().mcpToolCatalogs.markCatalogStale(old.catalogId());
   var oldSession=opened.state().mcpSessions.findById(opened.lifecycle().persisted().sessionId()).orElseThrow();var registry=new McpLiveSessionRegistry();registry.bind(new McpRuntimeSessionHandle(oldSession.sessionId(),oldSession.processIdentityHash(),opened.lifecycle().manifest().configHash(),old.catalogId(),old.catalogHash(),opened.lifecycle()),oldSession);
   var refresh=new McpCatalogRefreshService(new McpLifecycleService(),catalogService,opened.state().mcpServerManifests,opened.state().mcpSessions,opened.state().mcpToolCatalogs,registry);
   var result=refresh.refreshCatalogExplicitly(old.catalogId(),R3TestSupport.config(root,"SINGLE_PAGE",List.of("echo")),"refresh","refresh-1",1,System.getenv());
   assertNotEquals(oldSession.sessionId(),result.newSessionId());assertNotEquals(old.catalogId(),result.newCatalogId());assertEquals(result,refresh.refreshCatalogExplicitly(old.catalogId(),R3TestSupport.config(root,"SINGLE_PAGE",List.of("echo")),"refresh","refresh-1",1,System.getenv()));assertEquals("STALE",opened.state().mcpToolCatalogs.findById(old.catalogId()).orElseThrow().status());assertTrue(registry.find(result.newSessionId()).orElseThrow().isAliveAndHealthy());assertFalse(result.automaticRegistryReplacement());registry.closeAll();
  }
 }
 @Test void nonStaleCatalogCannotRefresh()throws Exception{try(var opened=R3TestSupport.open(root,"SINGLE_PAGE",List.of("echo"))){var service=new McpToolCatalogService(opened.state().mcpToolCatalogs,opened.state().mcpTools);var old=service.build(opened.lifecycle()).catalog();var refresh=new McpCatalogRefreshService(new McpLifecycleService(),service,opened.state().mcpServerManifests,opened.state().mcpSessions,opened.state().mcpToolCatalogs,new McpLiveSessionRegistry());assertEquals("EXPLICIT_REFRESH_REQUIRES_STALE_CATALOG",assertThrows(IllegalStateException.class,()->refresh.refreshCatalogExplicitly(old.catalogId(),opened.lifecycle().config(),"refresh","x",1,System.getenv())).getMessage());}}
}