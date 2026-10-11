package com.paicli.interactive.runtime;

import com.paicli.config.Config;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.session.SavedSessionService;
import com.paicli.model.AgentMessage;
import com.paicli.runtime.InstructionManifestRuntime;
import com.paicli.runtime.RuntimeManifest;
import com.paicli.state.StateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class R1bSavedSessionInputRoutingTest {
    private static final Instant AT = Instant.parse("2026-07-17T00:00:00Z");
    @TempDir Path workspace;

    @Test
    void boundRecoverableAndTerminalHeadsRejectOrdinaryInputWithoutExecution()
            throws Exception {
        try (StateStore state = new StateStore(workspace)) {
            Seed recoverable = seed(state, "recoverable");
            Seed terminal = seed(state, "terminal");
            state.tasks.markDone(terminal.task().value());
        }

        StateStore runtimeState = new StateStore(workspace);
        InteractiveRuntimeAdapter adapter = new InteractiveRuntimeAdapter(
                workspace, (taskId, request, state, bridge) -> {
                    throw new AssertionError("ordinary input must not execute");
                }, ignored -> { }, new InteractiveRuntimeView(), runtimeState);
        try {
            InteractiveSession session = session();
            adapter.openSession(session);
            SavedSessionService service = new SavedSessionService(runtimeState, workspace,
                    Clock.fixed(AT.plusSeconds(1), ZoneOffset.UTC));
            CanonicalId recoverable = service.list().stream()
                    .filter(view -> "recoverable".equals(view.summary()))
                    .findFirst().orElseThrow().threadId();
            CanonicalId terminal = service.list().stream()
                    .filter(view -> "terminal".equals(view.summary()))
                    .findFirst().orElseThrow().threadId();

            service.bind(session, recoverable.value());
            assertEquals("TASK_RECOVERY_REQUIRED",
                    adapter.admitOrdinaryInput().code());
            session.clearThreadBinding(AT.plusSeconds(2));
            service.bind(session, terminal.value());
            assertEquals("FOLLOW_UP_INPUT_REQUIRED",
                    adapter.admitOrdinaryInput().code());
            assertEquals(0, adapter.activeHandle().stream().count());
        } finally {
            adapter.close();
        }
    }

    @Test
    void healthyOwnerRejectsBindingInputWithoutTakeover() throws Exception {
        Seed seed;
        StateStore runtimeState = new StateStore(workspace);
        try {
            seed = seed(runtimeState, "owned");
            Instant leaseAt = Instant.now();
            assertEquals(com.paicli.state.R1aStoreResults.OwnershipResult.SUCCESS,
                    runtimeState.r1aDurable.acquireOwnership(seed.task(), "other-process",
                            seed.run(), "lease-token", leaseAt,
                            leaseAt.plusSeconds(300)));
            InteractiveRuntimeAdapter adapter = new InteractiveRuntimeAdapter(
                    workspace, (taskId, request, state, bridge) -> {
                        throw new AssertionError("owned Task must not execute");
                    }, ignored -> { }, new InteractiveRuntimeView(), runtimeState);
            InteractiveSession session = session();
            adapter.openSession(session);
            new SavedSessionService(runtimeState, workspace,
                    Clock.systemUTC())
                    .bind(session, seed.thread().value());

            assertEquals("OWNERSHIP_CONFLICT", adapter.admitOrdinaryInput().code());
            adapter.close();
        } finally {
            try {
                runtimeState.close();
            } catch (Exception ignored) {
                // Adapter may already own and close this Store.
            }
        }
    }

    private Seed seed(StateStore state, String goal) throws Exception {
        CanonicalId task = CanonicalId.newId(EntityFamily.TASK);
        Config config = config();
        var admission = new InstructionManifestRuntime(state, workspace).admitRoot(
                task, goal, 10_000, config, RuntimeManifest.disabled(), AT);
        state.checkpoints.save(task.value(), 1, "RUNNING",
                List.of(AgentMessage.userGoal(goal)));
        return new Seed(task, admission.threadId(), admission.initialRun().id());
    }

    private Config config() {
        return new Config(workspace.toString(), 30, 50_000, 200_000,
                Config.MODE_WORKSPACE_WRITE, 10_000, Config.PROVIDER_MOCK,
                "model-a", "", "", "system", 10,
                "verify", 120, 50_000, 1, false, true, false);
    }

    private InteractiveSession session() {
        InteractiveSession session = new InteractiveSession("session-1", workspace,
                Config.PROVIDER_MOCK, "model-a", Config.MODE_WORKSPACE_WRITE,
                InteractiveSession.TerminalMode.PLAIN, AT);
        session.start();
        return session;
    }

    private record Seed(CanonicalId task, CanonicalId thread, CanonicalId run) { }
}
