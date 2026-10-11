package com.paicli.skill;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlPlanePathsTest {

    @TempDir
    Path workspace;

    @Test
    void identifiesDefaultAndCustomControlPlaneFiles() throws Exception {
        ControlPlanePaths paths = new ControlPlanePaths(
                workspace,
                List.of("skills", ".paicli/skills"),
                List.of(".paicli/hooks.json", "config/team-hooks.json"));

        assertTrue(paths.isControlPlane(Path.of("skills/java/SKILL.md")));
        assertTrue(paths.isControlPlane(
                Path.of(".paicli/skills/testing/SKILL.md")));
        assertTrue(paths.isControlPlane(Path.of(".paicli/hooks.json")));
        assertTrue(paths.isControlPlane(Path.of("config/team-hooks.json")));
        assertFalse(paths.isControlPlane(Path.of("src/main/App.java")));
        assertFalse(paths.isControlPlane(
                Path.of(".agents/skills/governance/SKILL.md")));
    }

    @Test
    void normalizationCannotBypassDetectionOrWorkspaceGuard() throws Exception {
        ControlPlanePaths paths = new ControlPlanePaths(
                workspace, List.of("skills", ".paicli/skills"));

        assertTrue(paths.isControlPlane(
                Path.of("docs/../skills/java/SKILL.md")));
        assertThrows(SecurityException.class, () ->
                paths.isControlPlane(Path.of("../../outside/SKILL.md")));
        assertThrows(SecurityException.class, () ->
                new ControlPlanePaths(
                        workspace, List.of("../outside-skills")));
    }

    @Test
    void rejectsSymbolicLinkTraversalWhenPlatformSupportsLinks() throws Exception {
        Path real = Files.createDirectories(workspace.resolve("real-skill"));
        Files.writeString(real.resolve("SKILL.md"), "content");
        Path link = workspace.resolve("skills/link");
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, real);
        } catch (IOException | UnsupportedOperationException
                 | SecurityException e) {
            Assumptions.abort("Symbolic links unavailable: " + e.getMessage());
        }
        ControlPlanePaths paths = new ControlPlanePaths(
                workspace, List.of("skills", ".paicli/skills"));

        assertThrows(SecurityException.class, () ->
                paths.isControlPlane(Path.of("skills/link/SKILL.md")));
    }
}
