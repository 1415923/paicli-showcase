package com.paicli.mcp.diagnostic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.mcp.catalog.McpCatalogException;
import com.paicli.mcp.catalog.McpToolCatalogService;
import com.paicli.mcp.config.*;
import com.paicli.mcp.jsonrpc.McpSessionResult;
import com.paicli.mcp.lifecycle.McpLifecycleService;
import com.paicli.mcp.manifest.McpConfigManifestBuilder;
import com.paicli.mcp.schema.*;
import com.paicli.runtime.PersistenceSanitizer;
import com.paicli.skill.SkillLoader;
import com.paicli.state.CodingRunStore;
import com.paicli.state.StateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in diagnostics; a successful probe is NOT a third-party compatibility pass. */
@EnabledIfSystemProperty(named = "paicli.d1.evidence", matches = ".+")
class McpEcosystemD1DiagnosticIT {
    private final ObjectMapper json = new ObjectMapper();
    @TempDir Path workspace;
    private Path evidence() { return Path.of(System.getProperty("paicli.d1.evidence")).toAbsolutePath().normalize(); }
    private void record(String name, JsonNode value) throws Exception {
        Path out = evidence().resolve(System.getProperty("paicli.d1.attempt", "attempt-1")).normalize();
        assertTrue(out.startsWith(evidence()));
        Files.createDirectories(out);
        Files.writeString(out.resolve(name), json.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n",
                StandardOpenOption.CREATE_NEW);
    }

    @Test void originalSkillsThroughProductionLoader() throws Exception {
        JsonNode lock = json.readTree(evidence().resolve("source-lock.json").toFile());
        int count = 0;
        for (JsonNode item : lock.path("objects")) {
            if (!"skill".equals(item.path("kind").asText())) continue;
            Path source = evidence().resolve(item.path("sourcePath").asText())
                    .resolve("skills").resolve(item.path("skill").asText());
            Path target = workspace.resolve("skills").resolve(item.path("skill").asText());
            try (var paths = Files.walk(source)) {
                for (Path file : paths.toList()) {
                    assertFalse(Files.isSymbolicLink(file));
                    Path copy = target.resolve(source.relativize(file));
                    if (Files.isDirectory(file)) Files.createDirectories(copy);
                    else Files.copy(file, copy);
                }
            }
            assertArrayEquals(Files.readAllBytes(source.resolve("SKILL.md")), Files.readAllBytes(target.resolve("SKILL.md")));
            count++;
        }
        assertEquals(3, count);
        try (StateStore state = new StateStore(workspace)) {
            state.tasks.createTask("d1-task", "D1 original package loading", "mock", null, "on-request", 10);
            state.codingRuns.create("d1-run", "d1-task", CodingRunStore.KIND_INITIAL, null, 1, 0);
            var loaded = new SkillLoader(workspace, state.skillLoads, new PersistenceSanitizer())
                    .discover("d1-task", "d1-run", List.of("skills"));
            var rows = state.skillLoads.findByRun("d1-run");
            assertEquals(3, rows.size(), "each unmodified source must have a diagnostic outcome");
            ObjectNode report = json.createObjectNode().put("intent", "DIAGNOSTIC")
                    .put("productionAcceptance", "NOT_TESTED").put("loadedCount", loaded.size());
            report.set("loaderOutcomes", json.valueToTree(rows));
            record("skills-loader.json", report);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "paicli.d1.external", matches = "true")
    void fixedServersThroughProductionLifecycleAndCatalog() throws Exception {
        Path node = Path.of(System.getProperty("paicli.d1.node")).toRealPath();
        assertEquals("node.exe", node.getFileName().toString().toLowerCase(Locale.ROOT));
        for (String server : List.of("chrome-devtools-mcp", "@modelcontextprotocol/server-filesystem")) {
            String slug = server.substring(server.lastIndexOf('/') + 1);
            Path packageRoot = evidence().resolve("runtime/node_modules").resolve(server);
            JsonNode metadata = json.readTree(packageRoot.resolve("package.json").toFile());
            assertEquals(server, metadata.path("name").asText());
            assertEquals(server.startsWith("@") ? "2026.8.31" : "1.8.0", metadata.path("version").asText());
            Path fixture = Files.createDirectories(workspace.resolve(slug));
            List<String> args = new ArrayList<>();
            args.add(packageRoot.resolve(server.startsWith("@") ? "dist/index.js" : "build/src/bin/chrome-devtools-mcp.js")
                    .toAbsolutePath().toString());
            if (server.startsWith("@")) args.add(fixture.toAbsolutePath().toString());
            else args.addAll(List.of("--headless", "--isolated", "--no-usage-statistics", "--no-performance-crux"));
            McpServerConfig config = new McpServerConfig(slug, true, "STDIO", node, args,
                    fixture, List.of(), McpRuntimeLimits.defaults(), List.of(), null);
            ObjectNode report = json.createObjectNode().put("intent", "DIAGNOSTIC")
                    .put("server", server).put("version", metadata.path("version").asText())
                    .put("productionAcceptance", "NOT_TESTED").put("toolsCallCount", 0);
            Path configPath = fixture.resolve("mcp.json");
            json.writeValue(configPath.toFile(), new McpClientConfig(List.of(config)));
            try {
                new McpConfigLoader(fixture).load(configPath);
                report.put("configLoader", "ACCEPTED");
            } catch (Exception rejected) {
                report.put("configLoader", "REJECTED").put("configLoaderReason", rejected.getMessage());
            }
            // Explicit low-level trusted config is diagnostic, not proof of CLI connection.
            try (StateStore state = new StateStore(fixture)) {
                var manifest = new McpConfigManifestBuilder().build(config);
                var stored = state.mcpServerManifests.saveOrFindManifest(manifest);
                String sessionId = UUID.randomUUID().toString();
                try (var lifecycle = new McpLifecycleService().initialize(sessionId, stored.manifestId(),
                        "d1", "d1-" + slug, 1, config, manifest, state.mcpSessions, System.getenv())) {
                    report.put("lifecycle", "READY");
                    report.put("protocol", state.mcpSessions.findById(sessionId).orElseThrow().protocolVersion());
                    ArrayNode descriptors = json.createArrayNode();
                    String cursor = null;
                    Set<String> seen = new HashSet<>();
                    int pages = 0;
                    do {
                        assertTrue(++pages <= config.limits().maxCatalogPages());
                        ObjectNode params = json.createObjectNode();
                        if (cursor != null) params.put("cursor", cursor);
                        var listed = lifecycle.persisted().jsonRpc().sendToolsList(params, Duration.ofSeconds(30))
                                .get(31, TimeUnit.SECONDS);
                        assertEquals(McpSessionResult.Status.SUCCEEDED, listed.status());
                        assertTrue(listed.result().path("tools").isArray());
                        listed.result().path("tools").forEach(descriptors::add);
                        cursor = listed.result().path("nextCursor").asText(null);
                        if (cursor != null) assertTrue(seen.add(cursor));
                    } while (cursor != null);
                    record(slug + "-tools-list.json", json.createObjectNode().set("tools", descriptors));
                    report.put("toolCount", descriptors.size());
                    ArrayNode schemas = report.putArray("schemaOutcomes");
                    for (JsonNode tool : descriptors) {
                        ObjectNode result = schemas.addObject().put("tool", tool.path("name").asText())
                                .put("declaredDialect", tool.path("inputSchema").path("$schema").asText("unspecified"));
                        try {
                            new McpInputSchemaNormalizer(config.limits().maxSingleSchemaBytes()).normalize(tool.path("inputSchema"));
                            result.put("status", "ACCEPTED");
                        } catch (McpSchemaException rejected) {
                            result.put("status", "REJECTED").put("category", rejected.category()).put("reason", rejected.getMessage());
                        }
                    }
                    try {
                        var built = new McpToolCatalogService(state.mcpToolCatalogs, state.mcpTools).build(lifecycle);
                        report.put("catalog", "FROZEN").put("catalogId", built.catalog().catalogId());
                    } catch (McpCatalogException rejected) {
                        report.put("catalog", "REJECTED").put("catalogCategory", rejected.category())
                                .put("catalogReason", rejected.getMessage());
                    }
                } catch (Exception failure) {
                    report.put("lifecycleFailure", failure.getClass().getSimpleName()).put("reason", failure.getMessage());
                    throw failure;
                } finally {
                    var session = state.mcpSessions.findById(sessionId);
                    if (session.isPresent()) report.put("sessionAfterClose", session.get().status());
                    record(slug + "-diagnostic.json", report);
                }
            }
        }
    }
}
