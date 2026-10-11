package com.paicli.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.mcp.manifest.*;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class McpConfigManifestTest {
 @TempDir Path root;
 @Test void trustedConfigAndManifestAreDeterministicAndSecretFree()throws Exception{
  Path exe=Files.writeString(root.resolve("server.exe"),"binary-one");Path wd=Files.createDirectory(root.resolve("work"));String secret="R1_SECRET_SENTINEL_72931";System.setProperty("mcp.test.secret",secret);
  McpServerConfig a=config(exe,wd,List.of("--alpha","x"),List.of("zeta","alpha"),"MCP_TEST_SECRET");
  McpConfigManifestBuilder b=new McpConfigManifestBuilder();var m1=b.build(a);var m2=b.build(config(exe,wd,List.of("--alpha","x"),List.of("alpha","zeta"),"MCP_TEST_SECRET"));
  assertEquals(m1.configHash(),m2.configHash());assertFalse(m1.canonicalJson().contains(secret));assertFalse(a.toString().contains(secret));assertFalse(m1.canonicalJson().contains("--alpha"));
  assertEquals(McpManifestDrift.ARGUMENTS_DRIFT,new McpManifestComparator().compare(m1,b.build(config(exe,wd,List.of("x","--alpha"),List.of("alpha","zeta"),"MCP_TEST_SECRET"))));
  assertEquals(McpManifestDrift.ENVIRONMENT_REFERENCE_DRIFT,new McpManifestComparator().compare(m1,b.build(config(exe,wd,List.of("--alpha","x"),List.of("alpha","zeta"),"OTHER_SECRET"))));
  Files.writeString(exe,"binary-two");assertEquals(McpManifestDrift.EXECUTABLE_DRIFT,new McpManifestComparator().compare(m1,b.build(a)));
 }
 @Test void strictLoaderRejectsUnknownApprovalAndShellWrapper()throws Exception{
  Files.writeString(root.resolve("server.exe"),"x");Files.createDirectory(root.resolve("work"));
  String base="{\"servers\":[{\"server_id\":\"safe\",\"enabled\":true,\"transport\":\"STDIO\",\"executable\":\"server.exe\",\"args\":[\"x\"],\"working_directory\":\"work\",\"allowed_tool_names\":[\"lookup\"]%s}]}";
  Path good=Files.writeString(root.resolve("good.json"),base.formatted(""));assertEquals(1,new McpConfigLoader(root).load(good).servers().size());
  Path bad=Files.writeString(root.resolve("bad.json"),base.formatted(",\"autoApprove\":true"));assertThrows(Exception.class,()->new McpConfigLoader(root).load(bad));
  assertThrows(IllegalArgumentException.class,()->config(Path.of("cmd.exe"),root,List.of("/c","echo"),List.of("x"),"A"));
  assertThrows(IllegalArgumentException.class,()->config(root.resolve("x"),root,List.of("x"),List.of("*"),"A"));
 }
 private McpServerConfig config(Path exe,Path wd,List<String>args,List<String>tools,String env){return new McpServerConfig("safe",true,"STDIO",exe,args,wd,List.of(new McpEnvironmentReference("HOST_ENV",env)),McpRuntimeLimits.defaults(),tools,List.of(McpServerConfig.PROTOCOL_VERSION));}
}
