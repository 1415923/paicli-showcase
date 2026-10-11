package com.paicli.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import com.paicli.interactive.verification.InteractiveVerificationPolicy;

class ProviderConfigStoreTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @TempDir Path temp;

    @Test void writesUtf8LfPreservesUnrelatedFieldsAndClearsOnlyProvider() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("workspace"));
        Path home = Files.createDirectories(temp.resolve("home"));
        Path config = Files.createDirectories(workspace.resolve(".paicli")).resolve("config.json");
        Files.writeString(config, "{\"approval_mode\":\"read-only\",\"future\":42}\n");
        ProviderConfigStore store = new ProviderConfigStore(workspace, home);
        Config candidate = Config.defaults().withProvider(
                "ollama", "qwen3:8b", "http://localhost:11434", null);

        store.save(ProviderConfigStore.Scope.PROJECT, candidate);
        byte[] bytes = Files.readAllBytes(config);
        assertFalse(new String(bytes, StandardCharsets.UTF_8).contains("\r"));
        assertEquals('\n', bytes[bytes.length - 1]);
        JsonNode saved = MAPPER.readTree(bytes);
        assertEquals(42, saved.path("future").asInt());
        assertEquals("read-only", saved.path("approval_mode").asText());
        assertEquals("ollama", saved.path("provider").asText());

        store.clear(ProviderConfigStore.Scope.PROJECT);
        JsonNode cleared = MAPPER.readTree(Files.readAllBytes(config));
        assertFalse(cleared.has("provider"));
        assertFalse(cleared.has("model"));
        assertEquals(42, cleared.path("future").asInt());
    }

    @Test void userAndProjectFilesAreSeparate() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("workspace"));
        Path home = Files.createDirectories(temp.resolve("home"));
        ProviderConfigStore store = new ProviderConfigStore(workspace, home);
        store.save(ProviderConfigStore.Scope.USER, Config.defaults().withProvider(
                "ollama", "user", "http://localhost:11434", null));
        store.save(ProviderConfigStore.Scope.PROJECT, Config.defaults().withProvider(
                "ollama", "project", "http://localhost:11434", null));
        assertEquals("user", new ConfigLoader(temp.resolve("absent"), home)
                .resolve().config().model());
        assertEquals("project", new ConfigLoader(workspace, home).resolve().config().model());
    }

    @Test void persistsDeepSeekPresetWithoutPersistingApiKeyValue() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("deepseek-workspace"));
        Path home = Files.createDirectories(temp.resolve("deepseek-home"));
        ProviderConfigStore store = new ProviderConfigStore(workspace, home);
        Config candidate = Config.defaults().withProvider(
                        Config.PROVIDER_OPENAI, "deepseek-v4-flash",
                        "https://api.deepseek.com", "DEEPSEEK_API_KEY")
                .withProviderPreset("deepseek");
        candidate=candidate.withThinking("enabled","high");

        store.save(ProviderConfigStore.Scope.PROJECT, candidate);

        Path config = workspace.resolve(".paicli/config.json");
        String text = Files.readString(config);
        Config loaded = new ConfigLoader(workspace, home).load();
        assertEquals("deepseek", loaded.providerPreset());
        assertEquals("DEEPSEEK_API_KEY", loaded.apiKeyEnv());
        assertEquals("enabled",loaded.effectiveThinking());
        assertEquals("high",loaded.effectiveReasoningEffort());
        assertTrue(text.contains("\"thinking\""));
        assertTrue(text.contains("\"reasoning_effort\""));
        assertEquals(1_000_000, loaded.effectiveContextWindowTokens());
        assertFalse(text.contains("test-secret"));
        assertFalse(text.contains("Bearer "));
    }

    @Test void rejectsLinkedControlDirectoryWhenPlatformSupportsLinks() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("workspace"));
        Path home = Files.createDirectories(temp.resolve("home"));
        Path outside = Files.createDirectories(temp.resolve("outside"));
        try { Files.createSymbolicLink(workspace.resolve(".paicli"), outside); }
        catch (Exception error) { assumeTrue(false, "link creation unavailable: " + error.getClass().getSimpleName()); }
        ProviderConfigStore store = new ProviderConfigStore(workspace, home);
        assertThrows(Exception.class, () -> store.save(ProviderConfigStore.Scope.PROJECT,
                Config.defaults().withProvider("ollama", "m", "http://localhost:11434", null)));
    }

    @Test void savesVerificationPreferenceWithoutChangingProviderOrUnknownFields()
            throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("verify-workspace"));
        Path home = Files.createDirectories(temp.resolve("verify-home"));
        Path config = Files.createDirectories(workspace.resolve(".paicli"))
                .resolve("config.json");
        Files.writeString(config,
                "{\"provider\":\"ollama\",\"model\":\"coder\",\"future\":42}\n");
        ProviderConfigStore store = new ProviderConfigStore(workspace, home);

        store.saveVerification(ProviderConfigStore.Scope.PROJECT,
                new InteractiveVerificationPolicy.Selection(
                        InteractiveVerificationPolicy.AUTO,
                        InteractiveVerificationPolicy.MAVEN_PRESET));
        JsonNode enabled = MAPPER.readTree(Files.readAllBytes(config));
        assertTrue(enabled.path("enable_verify").asBoolean());
        assertEquals("mvn test", enabled.path("verification_command").asText());
        assertEquals("ollama", enabled.path("provider").asText());
        assertEquals(42, enabled.path("future").asInt());

        store.saveVerification(ProviderConfigStore.Scope.PROJECT,
                new InteractiveVerificationPolicy.Selection(
                        InteractiveVerificationPolicy.OFF, "none"));
        JsonNode disabled = MAPPER.readTree(Files.readAllBytes(config));
        assertFalse(disabled.path("enable_verify").asBoolean());
        assertFalse(disabled.has("verification_command"));
        assertEquals("coder", disabled.path("model").asText());
        assertEquals(42, disabled.path("future").asInt());
    }
}
