package com.paicli.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.mcp.approval.McpApprovalGrant;
import com.paicli.mcp.manifest.McpConfigManifestBuilder;
import com.paicli.mcp.schema.*;
import com.paicli.mcp.tool.McpToolNames;
import com.paicli.safety.ApprovalDecision;
import com.paicli.interactive.command.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class McpEcosystemBoundaryTest {
    @TempDir Path root;
    private final ObjectMapper json=new ObjectMapper();

    @Test void grantRequiresExplicitDecisionExactIdentityAndSingleConsumption() {
        var binding=new McpApprovalGrant.Binding("manifest","catalog","schema","tool","arguments");
        var requested=ApprovalDecision.needsApproval("tool",ApprovalDecision.RiskLevel.HIGH,"task","ask");
        var approved=ApprovalDecision.allow("tool",ApprovalDecision.RiskLevel.HIGH,"task","yes");
        assertThrows(IllegalStateException.class,()->McpApprovalGrant.fromAgentLoop(binding,"task","run","occ",approved,approved));
        assertThrows(IllegalArgumentException.class,()->McpApprovalGrant.fromAgentLoop(binding,"task",null,"occ",requested,approved));
        var grant=McpApprovalGrant.fromAgentLoop(binding,"task","run","occ",requested,approved);
        assertFalse(grant.consume(new McpApprovalGrant.Binding("manifest","catalog","schema","tool","changed"),"task","run","occ"));
        assertFalse(grant.consume(binding,"task","other-run","occ"));
        assertFalse(grant.consume(binding,"task","run","other-occ"));
        assertFalse(grant.consume(new McpApprovalGrant.Binding("changed","catalog","schema","tool","arguments"),"task","run","occ"));
        assertTrue(grant.consume(binding,"task","run","occ"));
        assertFalse(grant.consume(binding,"task","run","occ"));
    }

    @Test void draft07PreservesAndEnforcesRealServiceConstraints() throws Exception {
        var schema=json.readTree("""
                {"$schema":"http://json-schema.org/draft-07/schema#","type":"object",
                 "properties":{"n":{"type":"number","exclusiveMinimum":0,"maximum":10},
                   "items":{"type":"array","minItems":1,"items":{"type":"string","enum":["a","b"]}}},
                 "required":["n","items"],"additionalProperties":false}
                """);
        var normalized=new McpInputSchemaNormalizer(65536).normalize(schema);
        assertEquals(McpInputSchemaNormalizer.DRAFT_07,json.readTree(normalized.canonicalJson()).path("$schema").asText());
        var validator=new McpInputArgumentsValidator(65536);
        validator.validate(json.readTree("{\"n\":1,\"items\":[\"a\"]}"),normalized.canonicalJson());
        for(String invalid:List.of("{\"n\":0,\"items\":[\"a\"]}","{\"n\":11,\"items\":[\"a\"]}",
                "{\"n\":1,\"items\":[]}","{\"n\":1,\"items\":[\"c\"]}","{\"n\":1}",
                "{\"n\":1,\"items\":[\"a\"],\"extra\":true}"))
            assertThrows(McpArgumentValidationException.class,()->validator.validate(json.readTree(invalid),normalized.canonicalJson()),invalid);
        ((com.fasterxml.jackson.databind.node.ObjectNode)schema).putArray("anyOf").addObject().put("type","object");
        assertThrows(McpSchemaException.class,()->new McpInputSchemaNormalizer(65536).normalize(schema));
    }

    @Test void aliasesSeparateOriginalNamesAndSanitizationCollisions() {
        assertNotEquals("read_file",McpToolNames.alias("fs","read_file"));
        assertNotEquals(McpToolNames.alias("a.b","c"),McpToolNames.alias("a_b","c"));
        assertNotEquals(McpToolNames.alias("a","b_c"),McpToolNames.alias("a_b","c"));
        assertTrue(McpToolNames.alias("s".repeat(64),"t".repeat(128)).matches("[A-Za-z0-9_-]{1,64}"));
    }

    @Test void nodeEntryAndActualDependencyBytesAreFrozenWithoutExecution() throws Exception {
        Path workspace=Files.createDirectory(root.resolve("project"));
        Path executable=Files.writeString(root.resolve("node.exe"),"not an executable");
        Path installed=Files.createDirectory(root.resolve("installed"));
        Path modules=Files.createDirectories(installed.resolve("node_modules/package"));
        Path entry=Files.writeString(modules.resolve("main.js"),"import './dependency.js';");
        Path dependency=Files.writeString(modules.resolve("dependency.js"),"export const value=1;");
        Files.writeString(installed.resolve("package-lock.json"),"{\"lockfileVersion\":3}");
        var config=new McpServerConfig("fs",true,"STDIO",executable,List.of(entry.toString()),workspace,
                List.of(),null,List.of("read_file"),null,List.of(installed));
        Path source=workspace.resolve("import.json");json.writeValue(source.toFile(),new McpClientConfig(List.of(config)));
        assertThrows(Exception.class,()->new McpConfigLoader(workspace).load(source));
        var loaded=new McpConfigLoader(workspace,true).load(source).servers().getFirst();
        String before=new McpConfigManifestBuilder().build(loaded).configHash();
        Files.writeString(dependency,"export const value=2;");
        assertNotEquals(before,new McpConfigManifestBuilder().build(loaded).configHash());
        var entryOnly=new McpServerConfig("fs",true,"STDIO",executable,List.of(entry.toString()),workspace,
                List.of(),null,List.of(),null,List.of(entry,installed.resolve("package-lock.json")));
        json.writeValue(source.toFile(),new McpClientConfig(List.of(entryOnly)));
        assertThrows(Exception.class,()->new McpConfigLoader(workspace,true).load(source));
        var unbound=new McpServerConfig("fs",true,"STDIO",executable,List.of(entry.toString()),workspace,List.of(),null,List.of(),null);
        json.writeValue(source.toFile(),new McpClientConfig(List.of(unbound)));
        assertThrows(Exception.class,()->new McpConfigLoader(workspace,true).load(source));
    }

    @Test void quotedWindowsPathsKeepBackslashesAndRejectBrokenQuotes() {
        var parser=new SlashCommandParser(new SlashCommandRegistry());
        assertEquals(List.of("config","import","D:\\a b\\mcp.json"),
                parser.parse("/mcp config import \"D:\\a b\\mcp.json\"",false).arguments());
        assertThrows(IllegalArgumentException.class,()->parser.parse("/mcp config import \"missing",false));
    }
}
