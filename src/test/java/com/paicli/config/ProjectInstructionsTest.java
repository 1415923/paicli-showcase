package com.paicli.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProjectInstructionsTest {

    @TempDir
    Path workspace;

    @Test
    void loadReturnsEmptyWhenNoFiles() {
        ProjectInstructions pi = ProjectInstructions.load(workspace, 10000);
        assertTrue(pi.isEmpty());
        assertTrue(pi.sources().isEmpty());
    }

    @Test
    void loadPaiCLIMD() throws IOException {
        Files.writeString(workspace.resolve("PAICLI.md"), "# Rules\nDo not commit secrets.");

        ProjectInstructions pi = ProjectInstructions.load(workspace, 10000);

        assertFalse(pi.isEmpty());
        assertTrue(pi.content().contains("Do not commit secrets."));
        assertEquals(List.of("PAICLI.md"), pi.sources());
    }

    @Test
    void loadAgentsMD() throws IOException {
        Files.writeString(workspace.resolve("AGENTS.md"), "# Agent rules\nBe helpful.");

        ProjectInstructions pi = ProjectInstructions.load(workspace, 10000);

        assertTrue(pi.content().contains("Be helpful."));
        assertEquals(List.of("AGENTS.md"), pi.sources());
    }

    @Test
    void loadBothFiles() throws IOException {
        Files.writeString(workspace.resolve("PAICLI.md"), "Rule A");
        Files.writeString(workspace.resolve("AGENTS.md"), "Rule B");

        ProjectInstructions pi = ProjectInstructions.load(workspace, 10000);

        assertTrue(pi.content().contains("Rule A"));
        assertTrue(pi.content().contains("Rule B"));
        assertEquals(2, pi.sources().size());
    }

    @Test
    void truncationApplied() throws IOException {
        Files.writeString(workspace.resolve("PAICLI.md"), "A".repeat(500));

        ProjectInstructions pi = ProjectInstructions.load(workspace, 100);

        assertTrue(pi.wasTruncated());
        assertTrue(pi.content().length() <= 100 + "--- PAICLI.md ---\n".length());
        assertEquals(500 + "--- PAICLI.md ---\n".length(), pi.originalLength());
    }

    @Test
    void maxCharsZeroMeansNoLimit() throws IOException {
        Files.writeString(workspace.resolve("PAICLI.md"), "X".repeat(1000));

        ProjectInstructions pi = ProjectInstructions.load(workspace, 0);

        assertFalse(pi.wasTruncated());
        assertTrue(pi.content().contains("X".repeat(1000)));
    }

    @Test
    void missingFilesDoNotError() {
        assertDoesNotThrow(() -> ProjectInstructions.load(workspace.resolve("nonexistent"), 10000));
    }

    @Test
    void blankFileIsSkipped() throws IOException {
        Files.writeString(workspace.resolve("PAICLI.md"), "   \n  ");

        ProjectInstructions pi = ProjectInstructions.load(workspace, 10000);

        assertTrue(pi.isEmpty());
        assertTrue(pi.sources().isEmpty());
    }

    @Test
    void loadWithConfig() throws IOException {
        Files.writeString(workspace.resolve("PAICLI.md"), "Config test");
        Config config = new Config(".", 30, 50000, 200000, "on-request", 500);

        ProjectInstructions pi = ProjectInstructions.load(workspace, config);

        assertTrue(pi.content().contains("Config"));
    }
}
