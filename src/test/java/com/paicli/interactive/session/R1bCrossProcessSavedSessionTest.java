package com.paicli.interactive.session;

import com.paicli.config.Config;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.model.AgentMessage;
import com.paicli.runtime.InstructionManifestRuntime;
import com.paicli.runtime.RuntimeManifest;
import com.paicli.runtime.TaskRecoveryService;
import com.paicli.state.StateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class R1bCrossProcessSavedSessionTest {
    private static final Instant AT = Instant.parse("2026-07-17T00:00:00Z");
    @TempDir Path workspace;

    @Test
    void secondProcessListsBindsThenExplicitRecoveryCreatesOnlyResumeRun()
            throws Exception {
        CanonicalId task;
        CanonicalId thread;
        String manifest;
        int originalRuns;
        try (StateStore processA = new StateStore(workspace)) {
            task = CanonicalId.newId(EntityFamily.TASK);
            var root = new InstructionManifestRuntime(processA, workspace).admitRoot(
                    task, "cross process", 10_000, config(),
                    RuntimeManifest.disabled(), AT);
            thread = root.threadId();
            manifest = root.instructions().manifest().id();
            processA.checkpoints.save(task.value(), 2, "RUNNING",
                    List.of(AgentMessage.userGoal("cross process")));
            originalRuns = processA.r1aDurable.findRunsByTask(task).size();
        }

        try (StateStore processB = new StateStore(workspace)) {
            SavedSessionService sessions = new SavedSessionService(
                    processB, workspace, Clock.fixed(AT.plusSeconds(1), ZoneOffset.UTC));
            InteractiveSession terminal = session();

            assertTrue(sessions.list().stream()
                    .anyMatch(view -> view.threadId().equals(thread)));
            assertEquals(SavedSessionService.SelectionStatus.SUCCESS,
                    sessions.bind(terminal, thread.shortDisplay()).status());
            assertEquals(originalRuns,
                    processB.r1aDurable.findRunsByTask(task).size());
            assertTrue(processB.r1aDurable.readOwnership(task).isEmpty());

            TaskRecoveryService.RecoveryResult recovered =
                    new TaskRecoveryService(processB,
                            new InstructionManifestRuntime(processB, workspace)).recover(
                            task, config(), RuntimeManifest.disabled(), 10_000,
                            new TaskRecoveryService.LeaseRequest(
                                    "process-b", "lease-b", AT.plusSeconds(2),
                                    AT.plusSeconds(32), Duration.ZERO));
            TaskRecoveryService.Success success =
                    assertInstanceOf(TaskRecoveryService.Success.class, recovered);

            assertEquals(task, success.taskId());
            assertEquals("RESUME", success.resumeRun().kind());
            assertEquals(success.resumeRun().id(),success.executionAuthority().runId());
            assertEquals(originalRuns + 1,
                    processB.r1aDurable.findRunsByTask(task).size());
            assertEquals(manifest, success.instructions().manifest().id());
            assertEquals(thread, processB.r1aDurable
                    .findTask(task).orElseThrow().threadId());
            assertEquals(task, processB.r1aDurable
                    .readThreadHead(thread).orElseThrow().headTaskId());
        }
    }

    private Config config() {
        return new Config(workspace.toString(), 30, 50_000, 200_000,
                Config.MODE_WORKSPACE_WRITE, 10_000, Config.PROVIDER_MOCK,
                "model-a", "", "", "system", 10,
                "verify", 120, 50_000, 1, false, true, false);
    }

    private InteractiveSession session() {
        InteractiveSession session = new InteractiveSession("process-b-session", workspace,
                Config.PROVIDER_MOCK, "model-a", Config.MODE_WORKSPACE_WRITE,
                InteractiveSession.TerminalMode.PLAIN, AT.plusSeconds(1));
        session.start();
        return session;
    }
}
