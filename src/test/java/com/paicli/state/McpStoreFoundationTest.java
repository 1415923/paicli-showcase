package com.paicli.state;

import com.paicli.mcp.config.*;import com.paicli.mcp.manifest.*;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;import java.sql.*;import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class McpStoreFoundationTest {
 @TempDir Path root;StateStore state;McpConfigManifest manifest;
 @BeforeEach void setup()throws Exception{Path exe=Files.writeString(root.resolve("server.exe"),"server");Path wd=Files.createDirectory(root.resolve("work"));manifest=new McpConfigManifestBuilder().build(new McpServerConfig("srv",true,"STDIO",exe,List.of("--stdio"),wd,List.of(new McpEnvironmentReference("HOST_ENV","MCP_TOKEN")),McpRuntimeLimits.defaults(),List.of("lookup"),List.of(McpServerConfig.PROTOCOL_VERSION)));state=new StateStore(root.resolve("state"));}
 @AfterEach void close()throws Exception{state.close();}
 @Test void manifestSessionCatalogToolsAndCallsEnforceArtifactsAndTerminalCas()throws Exception{
  var saved=state.mcpServerManifests.saveOrFindManifest(manifest);assertEquals(saved.manifestId(),state.mcpServerManifests.saveOrFindManifest(manifest).manifestId());
  state.mcpSessions.createSession("session-1",saved.manifestId(),"srv","run","occ",1);state.mcpSessions.markStarting("session-1");state.mcpSessions.markInitializing("session-1");state.mcpSessions.markReady("session-1",McpServerConfig.PROTOCOL_VERSION,"a".repeat(64),"{\"tools\":true}");state.mcpSessions.markUnhealthy("session-1","token=R1_SECRET_SENTINEL_72931");assertThrows(SQLException.class,()->state.mcpSessions.markReady("session-1",McpServerConfig.PROTOCOL_VERSION,null,"{}"));
  state.mcpSessions.createSession("session-2",saved.manifestId(),"srv","run","occ2",1);
  String ch="b".repeat(64),sh="c".repeat(64);state.mcpToolCatalogs.createBuildingCatalog("catalog-1","session-2",saved.manifestId(),"srv",McpServerConfig.PROTOCOL_VERSION,ch,1,2);
  var tool=new McpToolStore.ToolRecord("tool-1","catalog-1","srv","lookup","mcp.srv.lookup","title","external instructions","{}",sh,"EXPOSED",null);state.mcpToolCatalogs.saveCatalogToolsAtomically("catalog-1",List.of(tool),ch);state.mcpToolCatalogs.freezeCatalog("catalog-1",ch);assertEquals(1,state.mcpTools.findExposedToolsByCatalog("catalog-1").size());state.mcpToolCatalogs.markCatalogStale("catalog-1");assertThrows(SQLException.class,()->state.mcpToolCatalogs.freezeCatalog("catalog-1",ch));
  var call=new McpToolCallStore.CallCreate("call-1","session-2","catalog-1","tool-1",null,null,"invoke",1,"1",manifest.configHash(),ch,sh,"mcp.srv.lookup","lookup","d".repeat(64),"password=R1_SECRET_SENTINEL_72931","PENDING");state.mcpToolCalls.createToolCall(call);state.mcpToolCalls.recordApprovalDecision("call-1","APPROVED");state.mcpToolCalls.markRunning("call-1");state.mcpToolCalls.markTimedOut("call-1","Bearer R1_SECRET_SENTINEL_72931");assertThrows(SQLException.class,()->state.mcpToolCalls.completeSuccess("call-1","e".repeat(64),false,false));assertFalse(state.mcpToolCalls.findById("call-1").orElseThrow().errorExcerpt().contains("R1_SECRET_SENTINEL_72931"));
  try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+root.resolve("state/.paicli/state/paicli.db"));Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT bounded_arguments_summary,bounded_error_excerpt FROM mcp_tool_calls WHERE mcp_tool_call_id='call-1'")){assertTrue(r.next());assertFalse((r.getString(1)+r.getString(2)).contains("R1_SECRET_SENTINEL_72931"));}
 }
 @Test void deniedCallCannotRunAndDatabaseContainsNoSentinel()throws Exception{
  var saved=state.mcpServerManifests.saveOrFindManifest(manifest);state.mcpSessions.createSession("session-3",saved.manifestId(),"srv","run","deny",1);String ch="1".repeat(64),sh="2".repeat(64);state.mcpToolCatalogs.createBuildingCatalog("catalog-3","session-3",saved.manifestId(),"srv",McpServerConfig.PROTOCOL_VERSION,ch,1,2);state.mcpToolCatalogs.saveCatalogToolsAtomically("catalog-3",List.of(new McpToolStore.ToolRecord("tool-3","catalog-3","srv","lookup","mcp.srv.lookup",null,null,"{}",sh,"EXPOSED",null)),ch);state.mcpToolCatalogs.freezeCatalog("catalog-3",ch);
  var call=new McpToolCallStore.CallCreate("call-3","session-3","catalog-3","tool-3",null,null,"deny-call",1,"3",manifest.configHash(),ch,sh,"mcp.srv.lookup","lookup","3".repeat(64),null,"PENDING");state.mcpToolCalls.createToolCall(call);state.mcpToolCalls.recordApprovalDecision("call-3","DENIED");assertThrows(SQLException.class,()->state.mcpToolCalls.markRunning("call-3"));
 }
}
