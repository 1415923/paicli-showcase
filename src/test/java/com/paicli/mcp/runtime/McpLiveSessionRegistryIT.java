package com.paicli.mcp.runtime;

import com.paicli.mcp.R3TestSupport;
import com.paicli.mcp.catalog.McpToolCatalogService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class McpLiveSessionRegistryIT {
 @TempDir Path root;
 @Test void bindingIsUniqueIdentityCheckedAndCloseAllIsIdempotent()throws Exception{
  try(var opened=R3TestSupport.open(root,"SINGLE_PAGE",List.of("echo"))){var catalog=new McpToolCatalogService(opened.state().mcpToolCatalogs,opened.state().mcpTools).build(opened.lifecycle()).catalog();var session=opened.state().mcpSessions.findById(opened.lifecycle().persisted().sessionId()).orElseThrow();var registry=new McpLiveSessionRegistry();var handle=new McpRuntimeSessionHandle(session.sessionId(),session.processIdentityHash(),opened.lifecycle().manifest().configHash(),catalog.catalogId(),catalog.catalogHash(),opened.lifecycle());registry.bind(handle,session);assertSame(handle,registry.requireUsable(session));assertThrows(IllegalStateException.class,()->registry.bind(handle,session));registry.closeAll();registry.closeAll();assertEquals(0,registry.size());}
 }
 @Test void missingHandleFailsClosed()throws Exception{try(var opened=R3TestSupport.open(root,"SINGLE_PAGE",List.of("echo"))){var session=opened.state().mcpSessions.findById(opened.lifecycle().persisted().sessionId()).orElseThrow();assertEquals("LIVE_SESSION_MISSING",assertThrows(IllegalStateException.class,()->new McpLiveSessionRegistry().requireUsable(session)).getMessage());}}
}