package com.paicli.tool;

import com.paicli.model.ToolCall;
import com.paicli.safety.SafetyPolicy;
import com.paicli.skill.ControlPlanePaths;
import com.paicli.state.FileSnapshotService;
import com.paicli.state.FileSnapshotStore;
import com.paicli.state.StateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WriteFileToolTest {

    @TempDir
    Path workspace;

    private StateStore stateStore;
    private SafetyPolicy policy;
    private FileSnapshotService snapshotService;

    @BeforeEach
    void setUp() throws Exception {
        stateStore = new StateStore(workspace);
        policy = new SafetyPolicy(workspace);
        snapshotService = new FileSnapshotService(workspace, stateStore.snapshots);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (stateStore != null) {
            stateStore.close();
        }
    }

    @Test
    void legacyConstructorWritesWithoutSnapshot() throws Exception {
        WriteFileTool tool = new WriteFileTool(policy);

        ToolResult result = tool.execute(call("legacy.txt", "legacy", false));

        assertTrue(result.success());
        assertEquals("legacy", Files.readString(workspace.resolve("legacy.txt")));
        assertTrue(stateStore.snapshots.findByStatus(FileSnapshotStore.STATUS_APPLIED).isEmpty());
    }

    @Test
    void overwriteCreatesAppliedSnapshotWithOriginalPayload() throws Exception {
        String taskId = "write-overwrite";
        createTask(taskId);
        Path target = workspace.resolve("existing.txt");
        Files.writeString(target, "original");
        WriteFileTool tool = new WriteFileTool(policy, snapshotService, taskId);

        ToolResult result = tool.execute(call("existing.txt", "replacement", true));

        assertTrue(result.success());
        assertEquals("replacement", Files.readString(target));
        FileSnapshotStore.FileSnapshotRecord snapshot = stateStore.snapshots.findByTask(taskId).getFirst();
        assertEquals(FileSnapshotStore.OPERATION_OVERWRITE, snapshot.operation());
        assertEquals(FileSnapshotStore.STATUS_APPLIED, snapshot.status());
        assertEquals(sha256(target), snapshot.sha256After());
        Path payload = workspace.resolve(".paicli/snapshots").resolve(snapshot.snapshotPath());
        assertEquals("original", Files.readString(payload));
        assertEquals(sha256(payload), snapshot.sha256Before());
    }

    @Test
    void createProducesAppliedSnapshotWithoutPayload() throws Exception {
        String taskId = "write-create";
        createTask(taskId);
        WriteFileTool tool = new WriteFileTool(policy, snapshotService, taskId);

        ToolResult result = tool.execute(call("created.txt", "created", false));

        assertTrue(result.success());
        FileSnapshotStore.FileSnapshotRecord snapshot = stateStore.snapshots.findByTask(taskId).getFirst();
        assertEquals(FileSnapshotStore.OPERATION_CREATE, snapshot.operation());
        assertEquals(FileSnapshotStore.STATUS_APPLIED, snapshot.status());
        assertFalse(snapshot.originalExists());
        assertNull(snapshot.snapshotPath());
        assertNotNull(snapshot.sha256After());
    }

    @Test
    void validationAndNoOverwriteFailuresDoNotCreateSnapshots() throws Exception {
        String taskId = "write-validation";
        createTask(taskId);
        Files.writeString(workspace.resolve("existing.txt"), "original");
        WriteFileTool tool = new WriteFileTool(policy, snapshotService, taskId);

        assertFalse(tool.execute(new ToolCall("write_file", Map.of("content", "missing path"))).success());
        assertFalse(tool.execute(call("existing.txt", "replacement", false)).success());

        assertTrue(stateStore.snapshots.findByTask(taskId).isEmpty());
        assertEquals("original", Files.readString(workspace.resolve("existing.txt")));
    }

    @Test
    void protectedPathsAreDeniedBeforeSnapshotOrFilesystemEffect() throws Exception {
        String taskId="write-protected";
        createTask(taskId);
        WriteFileTool tool=new WriteFileTool(policy,snapshotService,taskId);

        assertFalse(tool.preflightCall(call(".env","secret",false)).success());
        assertFalse(tool.execute(call(".env","secret",false)).success());
        assertFalse(tool.preflightCall(call(".git/config","secret",false)).success());
        assertFalse(tool.execute(call(".git/config","secret",false)).success());
        assertFalse(tool.preflightCall(call(".paicli/model-state.txt","secret",false))
                .success());
        assertFalse(tool.execute(call(".paicli/model-state.txt","secret",false)).success());

        assertFalse(Files.exists(workspace.resolve(".env")));
        assertFalse(Files.exists(workspace.resolve(".git/config")));
        assertFalse(Files.exists(workspace.resolve(".paicli/model-state.txt")));
        assertTrue(stateStore.snapshots.findByTask(taskId).isEmpty());
    }

    @Test
    void documentedEnvironmentTemplateRemainsWritable() throws Exception {
        WriteFileTool tool=new WriteFileTool(policy);

        ToolResult result=tool.execute(call(".env.example","KEY=documented",false));

        assertTrue(result.success(),result.error());
        assertEquals("KEY=documented",Files.readString(workspace.resolve(".env.example")));
    }

    @Test
    void runtimeStateStoreStillCreatesAndUpdatesPaiCliState() throws Exception {
        String taskId="runtime-state-access";
        createTask(taskId);

        stateStore.tasks.updateIterations(taskId,2);

        assertEquals(2,stateStore.tasks.findById(taskId).orElseThrow().iterationsCompleted());
        assertTrue(Files.exists(workspace.resolve(".paicli/state/paicli.db")));
    }

    @Test
    void writeFailureMarksCapturedSnapshotFailed() throws Exception {
        String taskId = "write-failure";
        createTask(taskId);
        Files.writeString(workspace.resolve("not-a-directory"), "blocking file");
        WriteFileTool tool = new WriteFileTool(policy, snapshotService, taskId);

        ToolResult result = tool.execute(call("not-a-directory/child.txt", "content", false));

        assertFalse(result.success());
        FileSnapshotStore.FileSnapshotRecord snapshot = stateStore.snapshots.findByTask(taskId).getFirst();
        assertEquals(FileSnapshotStore.STATUS_FAILED, snapshot.status());
        assertFalse(Files.exists(workspace.resolve("not-a-directory/child.txt")));
    }

    @Test
    void controlPlaneProtectionRejectsSkillsAndHookConfigsBeforeSnapshot() throws Exception {
        String taskId = "write-control-plane";
        createTask(taskId);
        ControlPlanePaths protectedPaths = new ControlPlanePaths(
                workspace,
                java.util.List.of("skills", ".paicli/skills"),
                java.util.List.of(".paicli/hooks.json", "config/team-hooks.json"));
        WriteFileTool tool = new WriteFileTool(
                policy, snapshotService, taskId, null, protectedPaths);

        assertFalse(tool.execute(call("skills/java/SKILL.md", "blocked", false)).success());
        assertFalse(tool.execute(call(".paicli/skills/test/SKILL.md", "blocked", false)).success());
        assertFalse(tool.execute(call(".paicli/hooks.json", "{}", false)).success());
        assertFalse(tool.execute(call("config/team-hooks.json", "{}", false)).success());
        assertFalse(tool.execute(call("nested/../../skills/java/SKILL.md", "blocked", false)).success());

        assertFalse(Files.exists(workspace.resolve("skills/java/SKILL.md")));
        assertFalse(Files.exists(workspace.resolve(".paicli/hooks.json")));
        assertTrue(stateStore.snapshots.findByTask(taskId).isEmpty());
    }

    @Test
    void protectedWriterStillWritesBusinessFileWithAppliedSnapshot() throws Exception {
        String taskId = "write-business";
        createTask(taskId);
        ControlPlanePaths protectedPaths = new ControlPlanePaths(
                workspace, java.util.List.of("skills", ".paicli/skills"),
                java.util.List.of(".paicli/hooks.json"));
        WriteFileTool tool = new WriteFileTool(
                policy, snapshotService, taskId, null, protectedPaths);

        ToolResult result = tool.execute(call("src/business.txt", "allowed", false));

        assertTrue(result.success());
        assertEquals("allowed", Files.readString(workspace.resolve("src/business.txt")));
        assertEquals(FileSnapshotStore.STATUS_APPLIED,
                stateStore.snapshots.findByTask(taskId).getFirst().status());
    }

    @Test
    void controlPlaneProtectionRejectsSymlinkTraversalWhenSupported() throws Exception {
        String taskId = "write-control-link";
        createTask(taskId);
        Path outside = Files.createTempDirectory("paicli-control-outside");
        Path link = workspace.resolve("skills/linked");
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, outside);
        } catch (Exception e) {
            Files.deleteIfExists(outside);
            Assumptions.abort("Symbolic links unavailable: " + e.getMessage());
        }
        try {
            ControlPlanePaths protectedPaths = new ControlPlanePaths(
                    workspace, java.util.List.of("skills", ".paicli/skills"),
                    java.util.List.of(".paicli/hooks.json"));
            WriteFileTool tool = new WriteFileTool(
                    policy, snapshotService, taskId, null, protectedPaths);

            ToolResult result = tool.execute(call("skills/linked/SKILL.md", "blocked", false));

            assertFalse(result.success());
            assertFalse(Files.exists(outside.resolve("SKILL.md")));
            assertTrue(stateStore.snapshots.findByTask(taskId).isEmpty());
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(outside);
        }
    }

    private ToolCall call(String path, String content, boolean overwrite) {
        return new ToolCall("write_file", Map.of(
                "path", path,
                "content", content,
                "overwrite", overwrite
        ));
    }

    private void createTask(String taskId) throws Exception {
        stateStore.tasks.createTask(taskId, "write snapshot test", "mock", null, "on-request", 10);
    }

    private String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
        );
    }
}
