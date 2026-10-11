package com.paicli.mcp.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.mcp.config.*;
import com.paicli.state.StateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class McpSessionPersistenceIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SECRET = "R2_DB_SECRET_SENTINEL_49382";
    @TempDir Path root;

    @Test void persistsConfiguredStartingInitializingUnhealthyAndClosedWithoutReadyOrSecrets() throws Exception {
        var config = McpTransportTestSupport.config(root, "LATE_RESPONSE",
                List.of(new McpEnvironmentReference("HOST_ENV", "MCP_R2_ALLOWED")), McpTransportTestSupport.limits(1000, 200, 2, 4096, 1024));
        var manifest = McpTransportTestSupport.manifest(config);
        Map<String,String> host = new HashMap<>(System.getenv()); host.put("MCP_R2_ALLOWED", SECRET);
        Path stateRoot = root.resolve("state");
        try (StateStore state = new StateStore(stateRoot)) {
            var saved = state.mcpServerManifests.saveOrFindManifest(manifest);
            McpPersistedSession persisted = McpPersistedSession.start("r2-session", saved.manifestId(), "r2", "timeout", 1,
                    config, manifest, state.mcpSessions, host);
            assertEquals("INITIALIZING", state.mcpSessions.findById("r2-session").orElseThrow().status());
            var result = persisted.jsonRpc().sendRequest("test/slow", JSON.createObjectNode(), Duration.ofMillis(100)).get(1, TimeUnit.SECONDS);
            assertEquals("TIMED_OUT", result.status().name());
            for (int i=0;i<30 && !state.mcpSessions.findById("r2-session").orElseThrow().status().equals("UNHEALTHY");i++) Thread.sleep(10);
            assertEquals("UNHEALTHY", state.mcpSessions.findById("r2-session").orElseThrow().status());
            persisted.close(); persisted.close();
            var record = state.mcpSessions.findById("r2-session").orElseThrow();
            assertEquals("CLOSED", record.status()); assertNotNull(record.processIdentityHash()); assertNull(record.protocolVersion());
        }
        assertDatabaseDoesNotContain(stateRoot.resolve(".paicli/state/paicli.db"), SECRET);
    }

    @Test void configDriftMarksFailedAndNeverStartsChild() throws Exception {
        var config = McpTransportTestSupport.config(root, "NORMAL", List.of(), McpRuntimeLimits.defaults());
        var manifest = McpTransportTestSupport.manifest(config);
        var changed = new McpServerConfig(config.serverId(), true, "STDIO", config.executable(),
                List.of("-cp", System.getProperty("java.class.path"), "not.Real"), root, List.of(), config.limits(),
                config.allowedToolNames(), config.supportedProtocolVersions());
        try (StateStore state = new StateStore(root.resolve("drift-state"))) {
            var saved = state.mcpServerManifests.saveOrFindManifest(manifest);
            var error = assertThrows(McpTransportException.class, () -> McpPersistedSession.start("drift-session", saved.manifestId(),
                    "r2", "drift", 1, changed, manifest, state.mcpSessions, System.getenv()));
            assertEquals("ARGUMENTS_DRIFT", error.category());
            assertEquals("FAILED", state.mcpSessions.findById("drift-session").orElseThrow().status());
        }
    }

    private static void assertDatabaseDoesNotContain(Path database, String sentinel) throws SQLException {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + database); Statement tables = c.createStatement();
             ResultSet rs = tables.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
            List<String> names = new ArrayList<>(); while (rs.next()) names.add(rs.getString(1));
            for (String table : names) try (Statement columns = c.createStatement(); ResultSet info = columns.executeQuery("PRAGMA table_info(" + table + ")")) {
                List<String> text = new ArrayList<>(); while (info.next()) if (info.getString("type").toUpperCase().contains("TEXT")) text.add(info.getString("name"));
                for (String column : text) try (Statement scan = c.createStatement(); ResultSet hit = scan.executeQuery("SELECT 1 FROM " + table + " WHERE instr(COALESCE(" + column + ",''),'" + sentinel + "') > 0 LIMIT 1")) { assertFalse(hit.next(), table + "." + column); }
            }
        }
    }
}
