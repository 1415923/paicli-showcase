package com.paicli.interactive.session;

import com.paicli.config.Config;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.identity.LegacyId;
import com.paicli.model.AgentMessage;
import com.paicli.runtime.InstructionManifestRuntime;
import com.paicli.runtime.RuntimeManifest;
import com.paicli.state.R1aDurableModels.DurableTask;
import com.paicli.state.R1aDurableModels.WorkThread;
import com.paicli.state.LegacyAlias;
import com.paicli.state.StateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SavedSessionServiceTest {
    private static final Instant AT = Instant.parse("2026-07-17T00:00:00Z");
    @TempDir Path workspace;

    @Test
    void listsOnlyWorkspaceThreadsInStableOrderAndDoesNotWrite() throws Exception {
        try (StateStore state = new StateStore(workspace)) {
            Seed first = seed(state, "first", true);
            Seed second = seed(state, "second", false);
            CanonicalId otherThread = CanonicalId.newId(EntityFamily.THREAD);
            CanonicalId otherTask = CanonicalId.newId(EntityFamily.TASK);
            assertEquals(com.paicli.state.R1aStoreResults.CreateResult.SUCCESS,
                    state.r1aDurable.createThread(new WorkThread(otherThread,
                            "workspace-sha256-other", "other", "OPEN", AT, AT)));
            assertEquals(com.paicli.state.R1aStoreResults.CreateResult.SUCCESS,
                    state.r1aDurable.createTask(new DurableTask(otherTask, otherThread,
                            null, "other", "CREATED", null, null, AT, null)));

            SavedSessionService service = service(state);
            List<SavedSessionService.SavedSessionView> before = service.list();
            List<SavedSessionService.SavedSessionView> again = service.list();

            assertEquals(before, again);
            assertEquals(2, before.size());
            assertTrue(before.stream().allMatch(view -> !view.threadId().equals(otherThread)));
            assertTrue(before.stream().anyMatch(view -> view.threadId().equals(first.thread())));
            assertTrue(before.stream().anyMatch(view -> view.threadId().equals(second.thread())));
            assertEquals(2, state.r1aDurable
                    .listWorkThreads(SavedSessionService.workspaceIdentity(workspace)).size());
            assertEquals(SavedSessionService.SelectionStatus.WORKSPACE_MISMATCH,
                    service.select(otherThread.value()).status());
        }
    }

    @Test
    void resolvesFullAndShortThreadAndRejectsWrongFamilyLegacyAndAmbiguity()
            throws Exception {
        try (StateStore state = new StateStore(workspace)) {
            Seed seed = seed(state, "recoverable", true);
            SavedSessionService service = service(state);

            assertEquals(SavedSessionService.SelectionStatus.SUCCESS,
                    service.select(seed.thread().value()).status());
            assertEquals(SavedSessionService.SelectionStatus.SUCCESS,
                    service.select(seed.thread().shortDisplay()).status());
            assertEquals(SavedSessionService.SelectionStatus.WRONG_ENTITY_FAMILY,
                    service.select(seed.task().value()).status());
            assertEquals(SavedSessionService.SelectionStatus
                            .LEGACY_ID_REQUIRES_EXPLICIT_MIGRATION,
                    service.select("legacy-task-1").status());
            assertEquals(SavedSessionService.SelectionStatus.INVALID_ID,
                    service.select(seed.thread().value().toUpperCase()).status());

            CanonicalId threadA = CanonicalId.parse(
                    "thr_123456789abc4def8000000000000001");
            CanonicalId threadB = CanonicalId.parse(
                    "thr_123456789abc4def8000000000000002");
            CanonicalId taskA = CanonicalId.newId(EntityFamily.TASK);
            CanonicalId taskB = CanonicalId.newId(EntityFamily.TASK);
            String identity = SavedSessionService.workspaceIdentity(workspace);
            state.r1aDurable.createThread(new WorkThread(
                    threadA, identity, "a", "OPEN", AT, AT));
            state.r1aDurable.createThread(new WorkThread(
                    threadB, identity, "b", "OPEN", AT, AT));
            state.r1aDurable.createTask(new DurableTask(
                    taskA, threadA, null, "a", "CREATED", null, null, AT, null));
            state.r1aDurable.createTask(new DurableTask(
                    taskB, threadB, null, "b", "CREATED", null, null, AT, null));

            assertEquals(SavedSessionService.SelectionStatus.AMBIGUOUS_SHORT_ID,
                    service.select("thr_123456789abc").status());
        }
    }

    @Test
    void bindingRefreshesDurableTerminalStateWithoutChangingThreadHead()
            throws Exception {
        try (StateStore state = new StateStore(workspace)) {
            Seed seed = seed(state, "refresh", true);
            InteractiveSession session = session();
            SavedSessionService service = service(state);
            assertEquals(SavedSessionService.SelectionStatus.SUCCESS,
                    service.bind(session, seed.thread().value()).status());
            assertEquals(SessionBinding.State.BOUND_RECOVERABLE,
                    session.snapshot().binding().state());
            CanonicalId originalHead = state.r1aDurable
                    .readThreadHead(seed.thread()).orElseThrow().headTaskId();

            state.tasks.markDone(seed.task().value());
            assertEquals(SavedSessionService.SelectionStatus.SUCCESS,
                    service.refresh(session).status());

            assertEquals(SessionBinding.State.BOUND_TERMINAL,
                    session.snapshot().binding().state());
            assertEquals(originalHead, state.r1aDurable
                    .readThreadHead(seed.thread()).orElseThrow().headTaskId());
        }
    }

    @Test
    void untrustedCheckpointDoesNotAdvertiseTaskAsRecoverable() throws Exception {
        try (StateStore state = new StateStore(workspace)) {
            Seed seed = seed(state, "untrusted", false);
            state.checkpoints.save(seed.task().value(), 1, "DONE",
                    List.of(AgentMessage.userGoal("untrusted")));

            SavedSessionService.SavedSessionView view = service(state).list().stream()
                    .filter(candidate -> candidate.headTaskId().equals(seed.task()))
                    .findFirst().orElseThrow();

            assertEquals(SessionBinding.State.BOUND_CONTINUATION_UNAVAILABLE,
                    view.binding().state());
            assertEquals("CONTINUATION_UNAVAILABLE", view.recoverability());
        }
    }

    @Test
    void ambiguousLegacyAliasFailsClosedWithoutFabricatingThread() throws Exception {
        try (StateStore state = new StateStore(workspace)) {
            LegacyAlias alias = new LegacyAlias(
                    new LegacyId(EntityFamily.TASK, "legacy-ambiguous"), null,
                    LegacyAlias.Classification.LEGACY_UNCERTAIN,
                    LegacyAlias.MappingStatus.AMBIGUOUS, "V1_TASK", "row-1",
                    "conflicting historical evidence",
                    LegacyAlias.ContinuationStatus.NOT_ASSESSED, 19, AT);
            assertEquals(com.paicli.state.LegacyAliasStore.AddResult.SUCCESS,
                    state.legacyAliases.add(alias));
            int threads = state.r1aDurable
                    .listWorkThreads(SavedSessionService.workspaceIdentity(workspace)).size();

            assertEquals(SavedSessionService.SelectionStatus.AMBIGUOUS_LEGACY_ID,
                    service(state).select("legacy-ambiguous").status());
            assertEquals(threads, state.r1aDurable
                    .listWorkThreads(SavedSessionService.workspaceIdentity(workspace)).size());
        }
    }

    private Seed seed(StateStore state, String goal, boolean checkpoint) throws Exception {
        CanonicalId task = CanonicalId.newId(EntityFamily.TASK);
        var admission = new InstructionManifestRuntime(state, workspace).admitRoot(
                task, goal, 10_000, config(), RuntimeManifest.disabled(), AT);
        if (checkpoint) state.checkpoints.save(task.value(), 1, "RUNNING",
                List.of(AgentMessage.userGoal(goal)));
        return new Seed(task, admission.threadId());
    }

    private Config config() {
        return new Config(workspace.toString(), 30, 50_000, 200_000,
                Config.MODE_WORKSPACE_WRITE, 10_000, Config.PROVIDER_MOCK,
                "model-a", "", "", "system", 10,
                "verify", 120, 50_000, 1, false, true, false);
    }

    private SavedSessionService service(StateStore state) {
        return new SavedSessionService(state, workspace,
                Clock.fixed(AT.plusSeconds(1), ZoneOffset.UTC));
    }

    private InteractiveSession session() {
        InteractiveSession session = new InteractiveSession("session-1", workspace,
                Config.PROVIDER_MOCK, "model-a", Config.MODE_WORKSPACE_WRITE,
                InteractiveSession.TerminalMode.PLAIN, AT);
        session.start();
        return session;
    }

    private record Seed(CanonicalId task, CanonicalId thread) { }
}
