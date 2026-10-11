package com.paicli.worktree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkerWorkspacePathGuardTest {
    @TempDir Path tempDir;

    @Test
    void resolvesNormalRelativePathDeterministically() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("worker"));
        Path gitCommon = Files.createDirectories(tempDir.resolve("repo.git"));
        Path state = Files.createDirectories(tempDir.resolve("state"));
        WorkerWorkspacePathGuard guard = new WorkerWorkspacePathGuard(
                workspace, gitCommon, List.of(state));
        Path first = guard.resolve("src/main/App.java");
        Path second = guard.resolve("src/main/App.java");
        assertEquals(workspace.resolve("src/main/App.java"), first);
        assertEquals(first, second);
    }

    @Test
    void rejectsTraversalAbsoluteGitAndControlPlanePaths() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("worker"));
        Path gitCommon = Files.createDirectories(tempDir.resolve("repo.git"));
        Path runtime = Files.createDirectories(workspace.resolve("runtime-state"));
        WorkerWorkspacePathGuard guard = new WorkerWorkspacePathGuard(
                workspace, gitCommon, List.of(runtime));
        assertThrows(IOException.class, () -> guard.resolve("../main/secret.txt"));
        assertThrows(IOException.class, () -> guard.resolve(tempDir.resolve("outside.txt").toString()));
        assertThrows(IOException.class, () -> guard.resolve(".git/config"));
        assertThrows(IOException.class, () -> guard.resolve("src/.git/HEAD"));
        assertThrows(IOException.class, () -> guard.resolve(".paicli/state/paicli.db"));
        assertThrows(IOException.class, () -> guard.resolve("runtime-state/paicli.db"));
    }

    @Test
    void rejectsSymlinkEscapeWhenPlatformSupportsSymlinks() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("worker"));
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Path link = workspace.resolve("linked");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assumptions.assumeTrue(false, "symlink creation unavailable: " + e.getMessage());
        }
        WorkerWorkspacePathGuard guard = new WorkerWorkspacePathGuard(workspace,
                Files.createDirectories(tempDir.resolve("repo.git")), List.of());
        assertThrows(IOException.class, () -> guard.resolve("linked/secret.txt"));
    }
}
