package com.paicli.hook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class HookConfigLoaderTest {

    @TempDir
    Path workspace;

    @Test
    void loadsValidHooksInDeclaredOrderAndKeepsDisabledDefinitions() throws Exception {
        writeConfig("""
                {"version":1,"hooks":[
                  {"id":"format","event":"BEFORE_VERIFY","command":"mvn spotless:check",
                   "timeout_seconds":12,"failure_policy":"fail","enabled":true},
                  {"id":"report","event":"AFTER_VERIFY","command":"echo report","enabled":false}
                ]}
                """);

        List<HookDefinition> hooks = loader().load();

        assertEquals(List.of("format", "report"), hooks.stream().map(HookDefinition::id).toList());
        assertEquals(HookEvent.BEFORE_VERIFY, hooks.get(0).event());
        assertEquals(12, hooks.get(0).timeoutSeconds());
        assertEquals("fail", hooks.get(0).failurePolicy());
        assertFalse(hooks.get(1).enabled());
        assertEquals(30, hooks.get(1).timeoutSeconds());
        assertEquals("warn", hooks.get(1).failurePolicy());
    }

    @Test
    void rejectsUnsupportedVersionDuplicateIdsAndInvalidFields() throws Exception {
        writeConfig("{\"version\":2,\"hooks\":[]}");
        assertThrows(IOException.class, () -> loader().load());

        writeConfig("""
                {"version":1,"hooks":[
                  {"id":"same","event":"BEFORE_PLAN","command":"echo one"},
                  {"id":"same","event":"AFTER_PLAN","command":"echo two"}
                ]}
                """);
        assertThrows(IllegalArgumentException.class, () -> loader().load());

        writeConfig("{\"version\":1,\"hooks\":[{\"id\":\"x\",\"event\":\"ANY\",\"command\":\"echo x\"}]}");
        assertThrows(IllegalArgumentException.class, () -> loader().load());

        writeConfig("{\"version\":1,\"hooks\":[{\"id\":\"x\",\"event\":\"BEFORE_PLAN\",\"command\":\"echo x\",\"failure_policy\":\"ignore\"}]}");
        assertThrows(IllegalArgumentException.class, () -> loader().load());
    }

    @Test
    void rejectsUnknownAndDuplicateJsonFields() throws Exception {
        writeConfig("{\"version\":1,\"extra\":true,\"hooks\":[]}");
        assertThrows(IOException.class, () -> loader().load());
        writeConfig("{\"version\":1,\"version\":1,\"hooks\":[]}");
        assertThrows(IOException.class, () -> loader().load());
    }

    @Test
    void rejectsWorkspaceEscapeAndSymlinkConfig() throws Exception {
        Path outside = Files.createTempFile("paicli-hooks-outside", ".json");
        Files.writeString(outside, "{\"version\":1,\"hooks\":[]}");
        try {
            assertThrows(IOException.class, () -> loader().load(outside));
        } finally {
            Files.deleteIfExists(outside);
        }

        Path real = workspace.resolve("real-hooks.json");
        Files.writeString(real, "{\"version\":1,\"hooks\":[]}");
        Path link = workspace.resolve("linked-hooks.json");
        try {
            Files.createSymbolicLink(link, real.getFileName());
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "Symbolic links are unavailable: " + e.getMessage());
        }
        assertTrue(Files.isSymbolicLink(link));
        assertThrows(IOException.class, () -> loader().load(link));
    }

    private HookConfigLoader loader() throws IOException {
        return new HookConfigLoader(workspace, 30, "warn");
    }

    private void writeConfig(String json) throws IOException {
        Path config = workspace.resolve(".paicli/hooks.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config, json);
    }
}
