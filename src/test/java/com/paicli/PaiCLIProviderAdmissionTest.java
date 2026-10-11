package com.paicli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PaiCLIProviderAdmissionTest {
    @TempDir Path workspace;

    @Test
    void ordinaryTaskWithoutProviderFailsBeforeStateCreation() throws Exception {
        String originalDir = System.getProperty("user.dir");
        String originalHome = System.getProperty("user.home");
        PrintStream originalErr = System.err;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream err = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setProperty("user.dir", workspace.toString());
            System.setProperty("user.home", workspace.toString());
            System.setErr(err);
            int exit = PaiCLI.run(new String[]{"inspect workspace"});
            assertEquals(1, exit);
        } finally {
            System.setErr(originalErr);
            System.setProperty("user.dir", originalDir);
            System.setProperty("user.home", originalHome);
        }
        String error = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(error.contains("No production provider is configured"));
        assertTrue(error.contains("paicli doctor"));
        assertFalse(Files.exists(workspace.resolve(".paicli")));
    }

    @Test
    void oneShotMockFailsOnceBeforeStateOrTaskArtifacts() throws Exception {
        String originalDir = System.getProperty("user.dir");
        String originalHome = System.getProperty("user.home");
        PrintStream originalErr = System.err;
        PrintStream originalOut = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
        try (PrintStream err = new PrintStream(bytes, true, StandardCharsets.UTF_8);
                PrintStream out = new PrintStream(outputBytes, true, StandardCharsets.UTF_8)) {
            System.setProperty("user.dir", workspace.toString());
            System.setProperty("user.home", workspace.toString());
            System.setErr(err);
            System.setOut(out);
            assertNotEquals(0, PaiCLI.run(new String[]{"--provider", "mock", "inspect workspace"}));
        } finally {
            System.setErr(originalErr);
            System.setOut(originalOut);
            System.setProperty("user.dir", originalDir);
            System.setProperty("user.home", originalHome);
        }
        String error = bytes.toString(StandardCharsets.UTF_8);
        assertEquals(1, error.lines().filter(line -> line.startsWith("Error:")).count(), error);
        assertTrue(error.contains("MOCK_PROVIDER_UNSUPPORTED"), error);
        assertFalse(outputBytes.toString(StandardCharsets.UTF_8).contains("started"));
        assertFalse(outputBytes.toString(StandardCharsets.UTF_8).contains("Completed"));
        assertFalse(Files.exists(workspace.resolve(".paicli")));
        assertFalse(Files.exists(workspace.resolve("reports")));
    }
}
