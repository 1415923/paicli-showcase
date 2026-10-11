package com.paicli.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderConfigResolutionTest {
    @TempDir Path temp;

    @Test void appliesNoneUserProjectAndCliPrecedence() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("workspace"));
        Path home = Files.createDirectories(temp.resolve("home"));
        ConfigLoader loader = new ConfigLoader(workspace, home);
        assertEquals(ConfigLoader.Source.NONE, loader.resolve().providerSource());

        write(home.resolve(".paicli/config.json"),
                "{\"provider\":\"ollama\",\"model\":\"user-model\"}");
        assertEquals(ConfigLoader.Source.USER, loader.resolve().providerSource());

        write(workspace.resolve(".paicli/config.json"),
                "{\"provider\":\"openai\",\"model\":\"project-model\"}");
        assertEquals(ConfigLoader.Source.PROJECT, loader.resolve().providerSource());
        assertEquals("project-model", loader.resolve().config().model());

        Config cli = Config.defaults().withProvider(
                "ollama", "cli-model", "http://localhost:11434", null);
        assertEquals(ConfigLoader.Source.CLI, loader.resolve(cli).providerSource());
        assertEquals("cli-model", loader.resolve(cli).config().model());
    }

    @Test void appliesVerificationNoneUserProjectAndCliPrecedence() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("verify-workspace"));
        Path home = Files.createDirectories(temp.resolve("verify-home"));
        ConfigLoader loader = new ConfigLoader(workspace, home);
        assertEquals(ConfigLoader.Source.NONE, loader.resolve().verificationSource());

        write(home.resolve(".paicli/config.json"),
                "{\"enable_verify\":false}");
        assertEquals(ConfigLoader.Source.USER, loader.resolve().verificationSource());
        assertEquals(false, loader.resolve().config().isVerifyEnabled());

        write(workspace.resolve(".paicli/config.json"),
                "{\"enable_verify\":true,\"verification_command\":\"mvn test\"}");
        assertEquals(ConfigLoader.Source.PROJECT, loader.resolve().verificationSource());
        assertEquals("mvn test", loader.resolve().config().effectiveVerificationCommand());

        Config cli = Config.defaults().withVerification("", false);
        assertEquals(ConfigLoader.Source.CLI, loader.resolve(cli).verificationSource());
        assertEquals(false, loader.resolve(cli).config().isVerifyEnabled());
    }

    private static void write(Path file, String value) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, value);
    }
}
