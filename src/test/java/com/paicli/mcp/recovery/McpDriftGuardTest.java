package com.paicli.mcp.recovery;

import com.paicli.mcp.R3TestSupport;
import com.paicli.mcp.manifest.McpConfigManifestBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class McpDriftGuardTest {
 @TempDir Path root;
 @Test void argsDriftIsClassifiedAndSecretValueIsOutsideManifest()throws Exception{var a=R3TestSupport.config(root,"SINGLE_PAGE",List.of("echo"));var bound=new McpConfigManifestBuilder().build(a);assertEquals("MATCH",new McpDriftGuard().requireManifestMatch(a,bound).name());var b=R3TestSupport.config(root,"MULTI_PAGE",List.of("echo"));assertEquals("ARGUMENTS_DRIFT",assertThrows(IllegalStateException.class,()->new McpDriftGuard().requireManifestMatch(b,bound)).getMessage());}
}