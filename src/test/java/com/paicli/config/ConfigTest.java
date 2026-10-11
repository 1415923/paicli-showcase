package com.paicli.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path workspace;

    @Test
    void defaultsAreSane() {
        Config config = Config.defaults();
        assertNotNull(config.workspace());
        assertEquals(30, config.commandTimeoutSeconds());
        assertEquals(50 * 1024, config.maxOutputBytes());
        assertEquals(200 * 1024, config.maxWriteFileBytes());
        assertEquals("on-request", config.approvalMode());
        assertEquals(10 * 1024, config.maxProjectInstructionChars());
        assertEquals("", config.effectiveVerificationCommand());
        assertEquals(Config.DEFAULT_VERIFICATION_TIMEOUT_SECONDS,
                config.effectiveVerificationTimeoutSeconds());
        assertEquals(Config.DEFAULT_MAX_VERIFICATION_OUTPUT_BYTES,
                config.effectiveMaxVerificationOutputBytes());
        assertEquals(1, config.effectiveMaxRepairAttempts());
        assertTrue(config.isSkillsEnabled());
        assertEquals(List.of("skills", ".paicli/skills"),
                config.effectiveSkillsDirs());
        assertEquals(16 * 1024, config.effectiveMaxSkillContextChars());
        assertEquals(3, config.effectiveMaxSelectedSkills());
        assertFalse(config.isPlanEnabled());
        assertTrue(config.isVerifyEnabled());
        assertTrue(config.isRepairEnabled());
    }

    @Test
    void allApprovalModesAreValid() {
        for (String mode : new String[]{"read-only", "workspace-write", "on-request", "deny"}) {
            Config c = new Config(".", 30, 50000, 200000, mode, 10000);
            assertTrue(c.isValidApprovalMode(), "Expected valid: " + mode);
        }
    }

    @Test
    void invalidApprovalModeDetected() {
        Config c = new Config(".", 30, 50000, 200000, "unknown", 10000);
        assertFalse(c.isValidApprovalMode());
    }

    @Test
    void mergeOverridesNonNullValues() {
        Config base = Config.defaults();
        Config override = new Config(null, 60, -1, -1, "deny", -1);

        Config merged = base.merge(override);

        assertEquals(60, merged.commandTimeoutSeconds());
        assertEquals("deny", merged.approvalMode());
        // Non-overridden values stay from base
        assertEquals(base.maxOutputBytes(), merged.maxOutputBytes());
        assertEquals(base.workspace(), merged.workspace());
    }

    @Test
    void mergeWithNullOverrideReturnsBase() {
        Config base = Config.defaults();
        assertEquals(base, base.merge(null));
    }

    // ── ConfigLoader tests ──

    @Test
    void loadReturnsDefaultsWhenNoConfigFiles() {
        // This compatibility case has no user-level configuration. Injecting a
        // null home keeps the test independent from the developer machine.
        var loader = new ConfigLoader(workspace, null);
        Config config = loader.load();

        assertEquals(30, config.commandTimeoutSeconds());
        assertEquals("on-request", config.approvalMode());
    }

    @Test
    void loadProjectConfigOverridesDefaults() throws IOException {
        Path configDir = Files.createDirectories(workspace.resolve(".paicli"));
        Files.writeString(configDir.resolve("config.json"),
                MAPPER.writeValueAsString(Map.of(
                        "command_timeout_seconds", 120,
                        "approval_mode", "read-only"
                )));

        var loader = new ConfigLoader(workspace);
        Config config = loader.load();

        assertEquals(120, config.commandTimeoutSeconds());
        assertEquals("read-only", config.approvalMode());
        // Other values remain default
        assertEquals(Config.defaults().maxOutputBytes(), config.maxOutputBytes());
    }

    @Test
    void missingProjectConfigDoesNotError() {
        var loader = new ConfigLoader(workspace.resolve("nonexistent"));
        assertDoesNotThrow(loader::load);
    }

    @Test
    void malformedConfigFallsBackToDefaults() throws IOException {
        Path configDir = Files.createDirectories(workspace.resolve(".paicli"));
        Files.writeString(configDir.resolve("config.json"), "{invalid json!!!");

        var loader = new ConfigLoader(workspace);
        Config config = loader.load();

        // Falls back to defaults
        assertEquals(30, config.commandTimeoutSeconds());
    }

    @Test
    void loadProjectConfigReturnsNullWhenMissing() {
        var loader = new ConfigLoader(workspace);
        assertNull(loader.loadProjectConfig());
    }

    @Test
    void loadProjectConfigReturnsConfigWhenPresent() throws IOException {
        Path configDir = Files.createDirectories(workspace.resolve(".paicli"));
        Files.writeString(configDir.resolve("config.json"),
                MAPPER.writeValueAsString(Map.of("approval_mode", "deny")));

        var loader = new ConfigLoader(workspace);
        Config config = loader.loadProjectConfig();

        assertNotNull(config);
        assertEquals("deny", config.approvalMode());
    }

    @Test
    void partialConfigMergesWithDefaults() throws IOException {
        Path configDir = Files.createDirectories(workspace.resolve(".paicli"));
        // Only set one field
        Files.writeString(configDir.resolve("config.json"),
                MAPPER.writeValueAsString(Map.of("command_timeout_seconds", 90)));

        var loader = new ConfigLoader(workspace);
        Config config = loader.load();

        assertEquals(90, config.commandTimeoutSeconds());
        // Other defaults preserved
        assertEquals(Config.defaults().maxOutputBytes(), config.maxOutputBytes());
        assertEquals(Config.defaults().approvalMode(), config.approvalMode());
    }

    // ── v0.3.0 Provider config tests ──

    @Test
    void defaultProviderIsUnconfigured() {
        Config config = Config.defaults();
        assertEquals("unconfigured", config.effectiveProvider());
        assertNull(config.model());
        assertNull(config.baseUrl());
        assertNull(config.apiKeyEnv());
        assertEquals(10, config.effectiveMaxIterations());
    }

    @Test
    void sixArgConstructorLeavesProviderUnconfigured() {
        Config config = new Config(".", 30, 50000, 200000, "on-request", 10000);
        assertEquals("unconfigured", config.effectiveProvider());
    }

    @Test
    void providerConfigLoadedFromJson() throws IOException {
        Path configDir = Files.createDirectories(workspace.resolve(".paicli"));
        Files.writeString(configDir.resolve("config.json"),
                MAPPER.writeValueAsString(Map.of(
                        "provider", "ollama",
                        "model", "qwen3:14b",
                        "base_url", "http://localhost:11434"
                )));

        var loader = new ConfigLoader(workspace);
        Config config = loader.load();

        assertEquals("ollama", config.effectiveProvider());
        assertEquals("qwen3:14b", config.model());
        assertEquals("http://localhost:11434", config.baseUrl());
    }

    @Test
    void oldConfigWithoutProviderFieldsRequiresProviderConfiguration() throws IOException {
        // Simulate a v0.2 config.json that has no provider fields
        Path configDir = Files.createDirectories(workspace.resolve(".paicli"));
        Files.writeString(configDir.resolve("config.json"),
                MAPPER.writeValueAsString(Map.of(
                        "command_timeout_seconds", 60,
                        "approval_mode", "read-only"
                )));

        var loader = new ConfigLoader(workspace, null);
        Config config = loader.load();

        assertEquals(60, config.commandTimeoutSeconds());
        assertEquals("read-only", config.approvalMode());
        assertEquals("unconfigured", config.effectiveProvider());
        assertNull(config.model());
    }

    @Test
    void unknownFieldsIgnoredInJson() throws IOException {
        Path configDir = Files.createDirectories(workspace.resolve(".paicli"));
        Files.writeString(configDir.resolve("config.json"),
                MAPPER.writeValueAsString(Map.of(
                        "provider", "mock",
                        "future_field", "some value",
                        "another_unknown", 42
                )));

        var loader = new ConfigLoader(workspace);
        Config config = loader.load();

        assertEquals("mock", config.effectiveProvider());
    }

    @Test
    void mergeProviderFields() {
        Config base = Config.defaults();
        Config override = new Config(
                null, -1, -1, -1, null, -1,
                "ollama", "qwen3:14b", "http://localhost:11434", null, null, 20);

        Config merged = base.merge(override);

        assertEquals("ollama", merged.effectiveProvider());
        assertEquals("qwen3:14b", merged.model());
        assertEquals(20, merged.effectiveMaxIterations());
        // Base values preserved
        assertEquals(base.workspace(), merged.workspace());
        assertEquals(base.commandTimeoutSeconds(), merged.commandTimeoutSeconds());
    }

    @Test
    void effectiveSystemPromptReturnsDefault() {
        Config config = Config.defaults();
        assertTrue(config.effectiveSystemPrompt().contains("coding agent"));
    }

    @Test
    void effectiveSystemPromptReturnsCustom() {
        Config config = new Config(
                ".", 30, 50000, 200000, "on-request", 10000,
                "mock", null, null, null, "Custom prompt here", 10);
        assertEquals("Custom prompt here", config.effectiveSystemPrompt());
    }

    @Test
    void runtimeConfigLoadedFromJson() throws IOException {
        Path configDir = Files.createDirectories(workspace.resolve(".paicli"));
        Files.writeString(configDir.resolve("config.json"),
                MAPPER.writeValueAsString(Map.of(
                        "verification_command", "mvn test",
                        "verification_timeout_seconds", 45,
                        "max_verification_output_bytes", 4096,
                        "max_repair_attempts", 3,
                        "enable_plan", false,
                        "enable_verify", false,
                        "enable_repair", false
                )));

        Config config = new ConfigLoader(workspace).load();

        assertEquals("mvn test", config.effectiveVerificationCommand());
        assertEquals(45, config.effectiveVerificationTimeoutSeconds());
        assertEquals(4096, config.effectiveMaxVerificationOutputBytes());
        assertEquals(3, config.effectiveMaxRepairAttempts());
        assertFalse(config.isPlanEnabled());
        assertFalse(config.isVerifyEnabled());
        assertFalse(config.isRepairEnabled());
    }

    @Test
    void runtimeConfigMergePreservesExplicitEmptyCommandAndDisabledFlags() {
        Config base = Config.defaults();
        Config override = new Config(
                null, 0, 0, 0, null, 0,
                null, null, null, null, null, 0,
                "", 60, 2048, 5, false, false, false);

        Config merged = base.merge(override);

        assertEquals("", merged.effectiveVerificationCommand());
        assertEquals(60, merged.effectiveVerificationTimeoutSeconds());
        assertEquals(2048, merged.effectiveMaxVerificationOutputBytes());
        assertEquals(5, merged.effectiveMaxRepairAttempts());
        assertFalse(merged.isPlanEnabled());
        assertFalse(merged.isVerifyEnabled());
        assertFalse(merged.isRepairEnabled());
    }

    @Test
    void maxRepairAttemptsAboveFiveIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new Config(
                ".", 30, 50000, 200000, "on-request", 10000,
                "mock", null, null, null, null, 10,
                "", 120, 50000, 6, true, true, true));
        assertThrows(IllegalArgumentException.class, () -> new Config(
                ".", 30, 50000, 200000, "on-request", 10000,
                "mock", null, null, null, null, 10,
                "", 120, 50000, 0, true, true, true));
    }

    @Test
    void skillsConfigLoadsAndMerges() throws IOException {
        Path configDir = Files.createDirectories(workspace.resolve(".paicli"));
        Files.writeString(configDir.resolve("config.json"),
                MAPPER.writeValueAsString(Map.of(
                        "enable_skills", false,
                        "skills_dirs", List.of("team-skills"),
                        "max_skill_context_chars", 8192,
                        "max_selected_skills", 5
                )));

        Config config = new ConfigLoader(workspace).load();

        assertFalse(config.isSkillsEnabled());
        assertEquals(List.of("team-skills"), config.effectiveSkillsDirs());
        assertEquals(8192, config.effectiveMaxSkillContextChars());
        assertEquals(5, config.effectiveMaxSelectedSkills());
    }

    @Test
    void skillsConfigRangeAndDirectoryValidationIsStrict() {
        assertThrows(IllegalArgumentException.class, () -> skillsConfig(
                true, List.of("skills"), Config.MAX_SKILL_CONTEXT_CHARS + 1, 3));
        assertThrows(IllegalArgumentException.class, () -> skillsConfig(
                true, List.of("skills"), Config.MIN_SKILL_CONTEXT_CHARS - 1, 3));
        assertThrows(IllegalArgumentException.class, () -> skillsConfig(
                true, List.of("skills"), 1024, 0));
        assertThrows(IllegalArgumentException.class, () -> skillsConfig(
                true, List.of(" "), 1024, 1));
    }

    @Test
    void hooksConfigLoadsDefaultsMergesAndValidatesRanges() throws IOException {
        Path configDir = Files.createDirectories(workspace.resolve(".paicli"));
        Files.writeString(configDir.resolve("config.json"),
                MAPPER.writeValueAsString(Map.of(
                        "enable_hooks", true,
                        "hooks_config_path", ".paicli/team-hooks.json",
                        "hook_timeout_seconds", 45,
                        "max_hook_output_bytes", 4096,
                        "hook_failure_policy", "fail"
                )));

        Config config = new ConfigLoader(workspace).load();
        assertTrue(config.isHooksEnabled());
        assertEquals(".paicli/team-hooks.json", config.effectiveHooksConfigPath());
        assertEquals(45, config.effectiveHookTimeoutSeconds());
        assertEquals(4096, config.effectiveMaxHookOutputBytes());
        assertEquals("fail", config.effectiveHookFailurePolicy());

        Config defaults = Config.defaults();
        assertFalse(defaults.isHooksEnabled());
        assertEquals(".paicli/hooks.json", defaults.effectiveHooksConfigPath());
        assertEquals(30, defaults.effectiveHookTimeoutSeconds());
        assertEquals(32768, defaults.effectiveMaxHookOutputBytes());
        assertEquals("warn", defaults.effectiveHookFailurePolicy());

        assertThrows(IllegalArgumentException.class, () -> hooksConfig(301, 1024, "warn"));
        assertThrows(IllegalArgumentException.class, () -> hooksConfig(30, 262145, "warn"));
        assertThrows(IllegalArgumentException.class, () -> hooksConfig(30, 1024, "ignore"));
    }

    @Test
    void contextWindowIsExplicitConfiguredBudgetAndMergePreservesIt()throws Exception{
        Path configDir=Files.createDirectories(workspace.resolve(".paicli"));
        Files.writeString(configDir.resolve("config.json"),
                "{\"provider\":\"openai\",\"model\":\"compatible\","
                        +"\"context_window_tokens\":32768}");
        Config configured=new ConfigLoader(workspace).load();
        assertEquals(32768,configured.effectiveContextWindowTokens());
        assertEquals(32768,Config.defaults().merge(configured).effectiveContextWindowTokens());
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,()->MAPPER.readValue(
                "{\"context_window_tokens\":100}",Config.class));
    }

    @Test void providerReplacementDoesNotLeakDeepSeekPresetAcrossProviderBundles(){
        Config deepSeek=Config.defaults().withProvider("openai","deepseek-v4-flash",
                "https://api.deepseek.com","DEEPSEEK_API_KEY")
                .withProviderPreset("deepseek");
        Config ollama=Config.defaults().withProvider("ollama","qwen3:14b",
                "http://localhost:11434",null);
        assertNull(deepSeek.withProvider("ollama","qwen3:14b",
                "http://localhost:11434",null).providerPreset());
        assertNull(deepSeek.merge(ollama).providerPreset());
        assertThrows(com.fasterxml.jackson.databind.JsonMappingException.class,()->
                MAPPER.readValue("{\"provider\":\"ollama\","
                        +"\"provider_preset\":\"deepseek\"}",Config.class));
    }

    private Config skillsConfig(Boolean enableSkills, List<String> skillsDirs,
                                Integer maxContextChars,
                                Integer maxSelectedSkills) {
        return new Config(
                ".", 30, 50000, 200000, "on-request", 10000,
                "mock", null, null, null, null, 10,
                "", 120, 50000, 1, true, true, true,
                enableSkills, skillsDirs, maxContextChars, maxSelectedSkills);
    }

    private Config hooksConfig(Integer timeout, Integer maxOutput, String policy) {
        return new Config(
                ".", 30, 50000, 200000, "on-request", 10000,
                "mock", null, null, null, null, 10,
                "", 120, 50000, 1, true, true, true,
                true, List.of("skills"), 1024, 1,
                true, ".paicli/hooks.json", timeout, maxOutput, policy);
    }
}
