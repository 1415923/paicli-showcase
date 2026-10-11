package com.paicli.interactive.verification;

import com.paicli.config.Config;
import com.paicli.config.ConfigLoader;
import com.paicli.config.ProviderConfigStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class InteractiveVerificationCommandTest {
    @TempDir Path temp;

    @Test void autoUsesOnlyTheFrozenMavenPreset() throws Exception {
        Files.writeString(temp.resolve("pom.xml"), "<project/>\n");
        Config effective = InteractiveVerificationPolicy.apply(
                Config.defaults(), temp, InteractiveVerificationPolicy.AUTO);
        assertTrue(effective.isVerifyEnabled());
        assertEquals(InteractiveVerificationPolicy.MAVEN_COMMAND,
                effective.effectiveVerificationCommand());
        assertEquals(InteractiveVerificationPolicy.MAVEN_PRESET,
                InteractiveVerificationPolicy.auto(temp).preset());
    }

    @Test void autoRejectsNonMavenWorkspaceAndOffDisablesStructuredVerification() {
        assertThrows(IllegalStateException.class,
                () -> InteractiveVerificationPolicy.auto(temp));
        Config off = InteractiveVerificationPolicy.apply(
                Config.defaults(), temp, InteractiveVerificationPolicy.OFF);
        assertFalse(off.isVerifyEnabled());
        assertEquals("", off.effectiveVerificationCommand());
    }

    @Test void savedUserAndProjectPreferencesLoadIntoLaterSessionsByPrecedence()
            throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("workspace"));
        Path home = Files.createDirectories(temp.resolve("home"));
        Files.writeString(workspace.resolve("pom.xml"), "<project/>\n");
        ProviderConfigStore store = new ProviderConfigStore(workspace, home);
        store.saveVerification(ProviderConfigStore.Scope.USER,
                new InteractiveVerificationPolicy.Selection(
                        InteractiveVerificationPolicy.AUTO,
                        InteractiveVerificationPolicy.MAVEN_PRESET));

        ConfigLoader.Resolution user = new ConfigLoader(workspace, home).resolve();
        assertEquals(ConfigLoader.Source.USER, user.verificationSource());
        assertEquals(InteractiveVerificationPolicy.AUTO,
                InteractiveVerificationPolicy.fromConfig(user.config()).mode());

        store.saveVerification(ProviderConfigStore.Scope.PROJECT,
                new InteractiveVerificationPolicy.Selection(
                        InteractiveVerificationPolicy.OFF, "none"));
        ConfigLoader.Resolution project = new ConfigLoader(workspace, home).resolve();
        assertEquals(ConfigLoader.Source.PROJECT, project.verificationSource());
        assertEquals(InteractiveVerificationPolicy.OFF,
                InteractiveVerificationPolicy.fromConfig(project.config()).mode());
    }
}
