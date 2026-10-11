package com.paicli.mcp.recovery;

import com.paicli.mcp.R3TestSupport;
import com.paicli.mcp.manifest.McpConfigManifestBuilder;
import com.paicli.mcp.runtime.McpLiveSessionRegistry;
import com.paicli.state.StateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class McpRecoveryServiceTest {
 @TempDir Path root;
 @Test void metadataResiduesFailClosedWithoutStartingRuntime()throws Exception{
  try(var state=new StateStore(root)){var config=R3TestSupport.config(root,"SINGLE_PAGE",List.of("echo"));var manifest=new McpConfigManifestBuilder().build(config);var saved=state.mcpServerManifests.saveOrFindManifest(manifest);var recovery=new McpRecoveryService(state.mcpSessions,state.mcpToolCalls,state.mcpToolCatalogs,new McpLiveSessionRegistry());
   state.mcpSessions.createSession("configured",saved.manifestId(),"fake","r","configured",1);recovery.reconcileInterruptedSession("configured",config,manifest);assertEquals("FAILED",state.mcpSessions.findById("configured").orElseThrow().status());
   state.mcpSessions.createSession("initializing",saved.manifestId(),"fake","r","initializing",1);state.mcpSessions.markStarting("initializing");state.mcpSessions.markInitializing("initializing","pid");recovery.reconcileInterruptedSession("initializing",config,manifest);assertEquals("RUNTIME_RESTART",state.mcpSessions.findById("initializing").orElseThrow().errorCategory());
   state.mcpSessions.createSession("ready",saved.manifestId(),"fake","r","ready",1);state.mcpSessions.markStarting("ready");state.mcpSessions.markInitializing("ready","pid");state.mcpSessions.markReady("ready",config.PROTOCOL_VERSION,"hash","{}");recovery.reconcileInterruptedSession("ready",config,manifest);assertEquals("LIVE_SESSION_LOST",state.mcpSessions.findById("ready").orElseThrow().errorCategory());
   state.mcpSessions.createSession("closing",saved.manifestId(),"fake","r","closing",1);state.mcpSessions.markStarting("closing");state.mcpSessions.markInitializing("closing","pid");state.mcpSessions.markReady("closing",config.PROTOCOL_VERSION,"hash","{}");state.mcpSessions.markShuttingDown("closing");recovery.reconcileInterruptedSession("closing",config,manifest);assertEquals("CLOSED",state.mcpSessions.findById("closing").orElseThrow().status());
  }
 }
}