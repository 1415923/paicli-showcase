package com.paicli.worker;

import com.paicli.model.ToolCall;
import com.paicli.worktree.WorkerWorkspacePathGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class WorktreeWorkerToolRegistryTest {
    @TempDir Path tempDir;

    @Test
    void fixedCapabilitiesExposeNoShellOrRawGitAndGuardWrites() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path gitCommon = Files.createDirectories(tempDir.resolve("git-common"));
        Path state = Files.createDirectories(tempDir.resolve("state"));
        Path traces = Files.createDirectories(tempDir.resolve("traces"));
        WorkerWorkspacePathGuard guard = new WorkerWorkspacePathGuard(
                workspace, gitCommon, List.of(state, traces));
        WorktreeWorkerToolRegistry registry = WorktreeWorkerToolRegistry.create(
                guard, traces.resolve("run"), 30, 4_096, new AtomicBoolean());

        assertEquals(WorktreeWorkerConfig.CAPABILITIES, registry.toolNames());
        assertFalse(registry.contains("run_command"));
        assertFalse(registry.contains("shell"));
        assertFalse(registry.contains("git"));
        assertTrue(registry.invoke(new ToolCall("unknown", Map.of())).error()
                .contains("Unknown tool"));
        assertTrue(registry.invoke(new ToolCall("write_file", Map.of(
                "path", "inside.txt", "content", "ok"))).success());
        assertEquals("ok", Files.readString(workspace.resolve("inside.txt")));
        assertFalse(registry.invoke(new ToolCall("write_file", Map.of(
                "path", "../outside.txt", "content", "bad"))).success());
        assertFalse(Files.exists(tempDir.resolve("outside.txt")));
    }

    @Test
    void definitionAndLimitsAreBoundedAndDisabledWorkersFail() {
        assertThrows(IllegalArgumentException.class, () -> new WorktreeWorkerConfig(
                "bad/name", "instruction", 1, 1, 1, true));
        assertThrows(IllegalArgumentException.class, () -> new WorktreeWorkerConfig(
                "worker", "instruction", 0, 1, 1, true));
        WorktreeWorkerConfig disabled = new WorktreeWorkerConfig(
                "worker", "instruction", 1, 1, 1, false);
        assertThrows(IllegalStateException.class, () -> new WorktreeWorkerRunRequest(
                "worktree", "occurrence", "task", null,
                (messages, tools) -> null, "mock", disabled));
    }
}
