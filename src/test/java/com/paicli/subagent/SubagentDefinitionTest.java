package com.paicli.subagent;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SubagentDefinitionTest {

    @Test
    void acceptsBoundedReadOnlyDefinition() {
        SubagentDefinition definition = new SubagentDefinition(
                "reviewer-1", SubagentKind.REVIEWER, "Review code without modifying it.",
                config(List.of("read_file", "grep"), SubagentPermissionMode.READ_ONLY,
                        8, 60, 16_384, true));

        assertEquals("reviewer-1", definition.name());
        assertEquals(SubagentKind.REVIEWER, definition.kind());
        assertEquals(List.of("read_file", "grep"), definition.config().allowedTools());
    }

    @Test
    void rejectsInvalidNameInstructionAndMissingConfig() {
        SubagentConfig config = config(List.of(), SubagentPermissionMode.DENY,
                1, 1, 1, true);

        assertThrows(IllegalArgumentException.class, () -> new SubagentDefinition(
                "bad name", SubagentKind.SCOUT, "read", config));
        assertThrows(IllegalArgumentException.class, () -> new SubagentDefinition(
                "scout", SubagentKind.SCOUT, " ", config));
        assertThrows(IllegalArgumentException.class, () -> new SubagentDefinition(
                "scout", SubagentKind.SCOUT,
                "x".repeat(SubagentDefinition.MAX_INSTRUCTION_CHARS + 1), config));
        assertThrows(IllegalArgumentException.class, () -> new SubagentDefinition(
                "scout", SubagentKind.SCOUT, "read", null));
    }

    @Test
    void rejectsDuplicateOrBlankAllowedTools() {
        assertThrows(IllegalArgumentException.class, () -> config(
                List.of("grep", "grep"), SubagentPermissionMode.READ_ONLY,
                1, 1, 1, true));
        assertThrows(IllegalArgumentException.class, () -> config(
                List.of(" "), SubagentPermissionMode.READ_ONLY,
                1, 1, 1, true));
    }

    @Test
    void rejectsRuntimeLimitsOutsideBounds() {
        assertThrows(IllegalArgumentException.class, () -> config(
                List.of(), SubagentPermissionMode.DENY, 0, 1, 1, true));
        assertThrows(IllegalArgumentException.class, () -> config(
                List.of(), SubagentPermissionMode.DENY,
                SubagentConfig.MAX_TURNS + 1, 1, 1, true));
        assertThrows(IllegalArgumentException.class, () -> config(
                List.of(), SubagentPermissionMode.DENY, 1, 0, 1, true));
        assertThrows(IllegalArgumentException.class, () -> config(
                List.of(), SubagentPermissionMode.DENY, 1,
                SubagentConfig.MAX_TIMEOUT_SECONDS + 1, 1, true));
        assertThrows(IllegalArgumentException.class, () -> config(
                List.of(), SubagentPermissionMode.DENY, 1, 1, 0, true));
        assertThrows(IllegalArgumentException.class, () -> config(
                List.of(), SubagentPermissionMode.DENY, 1, 1,
                SubagentConfig.MAX_OUTPUT_CHARS + 1, true));
    }

    @Test
    void verifierCapabilityIsMarkerWithoutCommandString() {
        SubagentConfig config = new SubagentConfig(
                List.of("read_file"),
                Set.of(SubagentCapability.CONFIGURED_VERIFICATION),
                SubagentPermissionMode.READ_ONLY,
                8, 60, 16_384, true);
        SubagentDefinition definition = new SubagentDefinition(
                "verifier", SubagentKind.VERIFIER, "Verify using configured evidence.", config);

        assertEquals(Set.of(SubagentCapability.CONFIGURED_VERIFICATION),
                definition.config().capabilities());
        assertTrue(Arrays.stream(SubagentConfig.class.getRecordComponents())
                .noneMatch(component -> component.getName().toLowerCase().contains("command")));
        assertFalse(definition.config().allowedTools().contains("run_command"));
    }

    @Test
    void configuredVerificationCapabilityIsReservedForVerifier() {
        for (SubagentKind kind : List.of(
                SubagentKind.SCOUT, SubagentKind.REVIEWER, SubagentKind.CUSTOM)) {
            SubagentConfig config = new SubagentConfig(
                    List.of(), Set.of(SubagentCapability.CONFIGURED_VERIFICATION),
                    SubagentPermissionMode.READ_ONLY, 8, 60, 16_384, true);
            assertThrows(SecurityException.class, () -> new SubagentDefinition(
                    kind.name().toLowerCase(), kind, "Read only.", config));
        }
    }

    private SubagentConfig config(
            List<String> tools,
            SubagentPermissionMode permission,
            int turns,
            int timeout,
            int outputChars,
            boolean enabled) {
        return new SubagentConfig(tools, permission, turns, timeout, outputChars, enabled);
    }
}
