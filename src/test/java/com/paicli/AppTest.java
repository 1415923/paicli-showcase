package com.paicli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AppTest {

    @TempDir
    Path workspace;

    @Test
    void mainClassLoads() {
        assertDoesNotThrow(() -> PaiCLI.main(new String[]{"--help"}));
    }

    @Test
    void versionFlag() {
        assertDoesNotThrow(() -> PaiCLI.main(new String[]{"--version"}));
    }

    @Test
    void noArgsInteractiveEntryIsPresent() {
        assertDoesNotThrow(() -> PaiCLI.class.getDeclaredMethod("executeInteractive"));
    }

    @Test
    void mockRunIsRejectedWithoutRuntimeArtifacts() throws IOException {
        String originalDir = System.getProperty("user.dir");
        String originalHome = System.getProperty("user.home");
        try {
            System.setProperty("user.dir", workspace.toString());
            System.setProperty("user.home", workspace.toString());
            assertNotEquals(0, PaiCLI.run(new String[]{"--provider", "mock", "list all files"}));
            assertFalse(Files.exists(workspace.resolve(".paicli")));
            assertFalse(Files.exists(workspace.resolve("reports")));
        } finally {
            System.setProperty("user.dir", originalDir);
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void providerFlagAccepted() {
        String originalDir = System.getProperty("user.dir");
        String originalHome = System.getProperty("user.home");
        try {
            System.setProperty("user.dir", workspace.toString());
            System.setProperty("user.home", workspace.toString());
            assertNotEquals(0, PaiCLI.run(new String[]{
                    "--provider", "mock", "test task"}));
            assertFalse(Files.exists(workspace.resolve(".paicli")));
        } finally {
            System.setProperty("user.dir", originalDir);
            System.setProperty("user.home", originalHome);
        }
    }

    @Test
    void unknownProviderFailsFastThroughCliBoundary() {
        assertEquals(1, PaiCLI.run(new String[]{"--provider", "unknown", "test task"}));
    }
}
