package com.paicli.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.Config;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.tool.Tool;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SubagentToolRegistryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void everyProfileRejectsWriteFile() {
        ToolRegistry parent = parent("read_file", "write_file");

        for (SubagentKind kind : SubagentKind.values()) {
            SubagentDefinition definition = definition(
                    kind, SubagentPermissionMode.READ_ONLY,
                    List.of("read_file", "write_file"), true);
            assertThrows(SecurityException.class, () -> SubagentToolRegistry.derive(
                    parent, Config.MODE_ON_REQUEST, definition));
        }
    }

    @Test
    void verifierCannotObtainRawRunCommand() {
        ToolRegistry parent = parent("run_command", "write_file", "read_file");
        assertThrows(SecurityException.class, () -> SubagentToolRegistry.derive(
                parent, Config.MODE_ON_REQUEST,
                definition(SubagentKind.VERIFIER, SubagentPermissionMode.ON_REQUEST,
                        List.of("run_command"), true)));
    }

    @Test
    void parentMissingToolCannotBeConfiguredIntoEffectiveRegistry() {
        ToolRegistry parent = parent("grep");
        SubagentToolRegistry effective = SubagentToolRegistry.derive(
                parent, Config.MODE_READ_ONLY,
                definition(SubagentKind.SCOUT, SubagentPermissionMode.READ_ONLY,
                        List.of("grep", "read_file"), true));

        assertEquals(List.of("grep"), effective.toolNames());
        assertFalse(effective.contains("read_file"));
    }

    @Test
    void unknownToolFailsClosed() {
        ToolRegistry parent = parent("read_file");
        SubagentDefinition definition = definition(
                SubagentKind.REVIEWER, SubagentPermissionMode.READ_ONLY,
                List.of("made_up_tool"), true);

        assertThrows(IllegalArgumentException.class, () -> SubagentToolRegistry.derive(
                parent, Config.MODE_READ_ONLY, definition));
    }

    @Test
    void allowListOnlyNarrowsParentRegistry() {
        ToolRegistry parent = parent("read_file", "grep", "list_files", "git_diff");
        SubagentToolRegistry effective = SubagentToolRegistry.derive(
                parent, Config.MODE_READ_ONLY,
                definition(SubagentKind.REVIEWER, SubagentPermissionMode.READ_ONLY,
                        List.of("grep", "read_file"), true));

        assertEquals(List.of("grep", "read_file"), effective.toolNames());
        assertEquals(2, effective.size());
    }

    @Test
    void scoutReviewerAndCustomRetainOnlyFixedReadOnlyTools() {
        List<String> readOnly = List.of("grep", "list_files", "read_file");
        ToolRegistry parent = parent(
                "write_file", "run_command", "read_file", "grep", "list_files", "git_diff");

        for (SubagentKind kind : List.of(
                SubagentKind.SCOUT, SubagentKind.REVIEWER, SubagentKind.CUSTOM)) {
            SubagentToolRegistry effective = SubagentToolRegistry.derive(
                    parent, Config.MODE_READ_ONLY,
                    definition(kind, SubagentPermissionMode.READ_ONLY, readOnly, true));
            assertEquals(readOnly, effective.toolNames());
            assertFalse(effective.contains("write_file"));
            assertFalse(effective.contains("run_command"));
        }
    }

    @Test
    void childPermissionCannotBeBroaderThanParent() {
        ToolRegistry parent = parent("run_command");

        assertThrows(SecurityException.class, () -> SubagentToolRegistry.derive(
                parent, Config.MODE_READ_ONLY,
                definition(SubagentKind.VERIFIER, SubagentPermissionMode.ON_REQUEST,
                        List.of("run_command"), true)));
        assertThrows(SecurityException.class, () -> SubagentToolRegistry.derive(
                parent, Config.MODE_DENY,
                definition(SubagentKind.SCOUT, SubagentPermissionMode.READ_ONLY,
                        List.of("read_file"), true)));
    }

    @Test
    void readOnlyProfilesRejectGitDiffProcessTool() {
        ToolRegistry parent = parent("git_diff");
        assertThrows(SecurityException.class, () -> SubagentToolRegistry.derive(
                parent, Config.MODE_ON_REQUEST,
                definition(SubagentKind.REVIEWER, SubagentPermissionMode.READ_ONLY,
                        List.of("git_diff"), true)));
    }
    @Test
    void everyProfileRejectsRawRunCommand() {
        ToolRegistry parent = parent("run_command");

        for (SubagentKind kind : SubagentKind.values()) {
            assertThrows(SecurityException.class, () -> SubagentToolRegistry.derive(
                    parent, Config.MODE_ON_REQUEST,
                    definition(kind, SubagentPermissionMode.ON_REQUEST,
                            List.of("run_command"), true)));
        }
    }

    @Test
    void disabledDefinitionCannotProduceEffectiveRegistry() {
        ToolRegistry parent = parent("read_file");
        SubagentDefinition definition = definition(
                SubagentKind.SCOUT, SubagentPermissionMode.READ_ONLY,
                List.of("read_file"), false);

        assertThrows(IllegalStateException.class, () -> SubagentToolRegistry.derive(
                parent, Config.MODE_READ_ONLY, definition));
    }

    @Test
    void toolOrderIsStableAndAlphabetical() {
        ToolRegistry parent = parent("read_file", "list_files", "grep");
        SubagentToolRegistry effective = SubagentToolRegistry.derive(
                parent, Config.MODE_READ_ONLY,
                definition(SubagentKind.SCOUT, SubagentPermissionMode.READ_ONLY,
                        List.of("read_file", "list_files", "grep"), true));

        assertEquals(List.of("grep", "list_files", "read_file"), effective.toolNames());
        assertEquals(effective.toolNames(), effective.allDefinitions().stream()
                .map(ToolDefinition::name).toList());
    }

    @Test
    void derivingRegistryDoesNotModifyParentRegistry() {
        ToolRegistry parent = parent("write_file", "read_file", "grep");
        List<String> original = parent.allDefinitions().stream().map(ToolDefinition::name).toList();

        SubagentToolRegistry.derive(
                parent, Config.MODE_READ_ONLY,
                definition(SubagentKind.REVIEWER, SubagentPermissionMode.READ_ONLY,
                        List.of("read_file"), true));

        assertEquals(3, parent.size());
        assertEquals(original,
                parent.allDefinitions().stream().map(ToolDefinition::name).toList());
        assertTrue(parent.lookup("write_file").isPresent());
    }

    private SubagentDefinition definition(
            SubagentKind kind,
            SubagentPermissionMode permission,
            List<String> tools,
            boolean enabled) {
        return new SubagentDefinition(
                kind.name().toLowerCase() + "-test",
                kind,
                "Read and report only.",
                new SubagentConfig(tools, permission, 8, 60, 16_384, enabled));
    }

    private ToolRegistry parent(String... names) {
        ToolRegistry registry = new ToolRegistry();
        for (String name : names) {
            registry.register(new StubTool(name));
        }
        return registry;
    }

    private record StubTool(String name) implements Tool {
        @Override
        public String description() {
            return "test tool";
        }

        @Override
        public ToolDefinition toDefinition() {
            return new ToolDefinition(name, description(), MAPPER.createObjectNode());
        }

        @Override
        public ToolResult execute(ToolCall call) {
            throw new AssertionError("R3 boundary tests must not execute tools");
        }
    }
}
