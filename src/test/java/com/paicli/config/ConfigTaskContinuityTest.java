package com.paicli.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigTaskContinuityTest {
    @TempDir Path workspace;

    @Test void defaultIsDisabledAndCompleteExplicitPolicyHasNoImplicitNumbers() throws Exception {
        assertFalse(Config.defaults().isTaskContinuityEnabled());
        Config parsed=new ObjectMapper().readValue("""
                {"max_iterations":10,"task_continuity":{"enabled":true,
                 "max_additional_segments":3,"max_plan_attempts":3,
                 "max_total_provider_turns":35,"max_total_tool_calls":40,
                 "max_input_tokens":300000,"max_output_tokens":30000,
                 "max_active_elapsed_ms":300000}}
                """,Config.class);
        assertTrue(parsed.isTaskContinuityEnabled());
        assertEquals(10,parsed.effectiveMaxIterations());
        assertEquals(40,parsed.taskContinuity().maxTotalToolCalls());
    }

    @Test void enabledButIncompleteFailsClosedInsteadOfFallingBackToDefaults() throws Exception {
        Path config=workspace.resolve(".paicli/config.json");
        Files.createDirectories(config.getParent());
        Files.writeString(config,"""
                {"task_continuity":{"enabled":true,"max_additional_segments":2}}
                """);
        IllegalStateException failure=assertThrows(IllegalStateException.class,
                ()->new ConfigLoader(workspace,workspace.resolve("home")).load());
        assertTrue(failure.getMessage().startsWith("TASK_CONTINUITY_CONFIG_INVALID:"));
    }

    @Test void disabledObjectDoesNotRequireOrInventBounds() throws Exception {
        Config parsed=new ObjectMapper().readValue(
                "{\"task_continuity\":{\"enabled\":false}}",Config.class);
        assertFalse(parsed.isTaskContinuityEnabled());
        assertNull(parsed.taskContinuity().maxTotalToolCalls());
    }
}
