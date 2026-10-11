package com.paicli.hook;

import com.paicli.runtime.BoundedProcessRunner;
import com.paicli.runtime.PersistenceSanitizer;
import com.paicli.state.CodingRunStore;
import com.paicli.state.HookRunStore;
import com.paicli.state.StateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HookRuntimeTest {

    @TempDir
    Path workspace;

    @Test
    void preservesOrderAndReusesCompletedOccurrence() throws Exception {
        try (StateStore state = preparedState()) {
            HookRuntime runtime = runtime(state, List.of(
                    hook("first", HookEvent.BEFORE_PLAN, "echo first", "warn"),
                    hook("second", HookEvent.BEFORE_PLAN, "echo second", "warn")));

            HookRuntime.LifecycleResult first = runtime.run(
                    "task", "run", HookEvent.BEFORE_PLAN, "plan");
            HookRuntime.LifecycleResult replay = runtime.run(
                    "task", "run", HookEvent.BEFORE_PLAN, "plan");

            assertFalse(first.shouldFail());
            assertEquals(List.of("first", "second"), first.results().stream()
                    .map(HookResult::hookId).toList());
            assertEquals(first.results().stream().map(HookResult::hookRunId).toList(),
                    replay.results().stream().map(HookResult::hookRunId).toList());
            assertEquals(2, state.hookRuns.findByRun("run").size());
        }
    }

    @Test
    void warnContinuesButFailStopsRemainingHooks() throws Exception {
        try (StateStore state = preparedState()) {
            HookRuntime runtime = runtime(state, List.of(
                    hook("warn", HookEvent.BEFORE_EXECUTE, failingCommand(), "warn"),
                    hook("fail", HookEvent.BEFORE_EXECUTE, failingCommand(), "fail"),
                    hook("never", HookEvent.BEFORE_EXECUTE, "echo never", "warn")));

            HookRuntime.LifecycleResult result = runtime.run(
                    "task", "run", HookEvent.BEFORE_EXECUTE, "execute:0");

            assertTrue(result.shouldFail());
            assertEquals(List.of(HookRunStore.STATUS_WARNED, HookRunStore.STATUS_FAILED),
                    result.results().stream().map(HookResult::status).toList());
            assertEquals(2, state.hookRuns.findByRun("run").size());
        }
    }

    @Test
    void interruptedRunningHookIsTerminalAndNeverReplayed() throws Exception {
        try (StateStore state = preparedState()) {
            state.hookRuns.recordStarted(
                    "stale", "run", "task", "stale-hook",
                    HookEvent.BEFORE_VERIFY.name(), "verify:0", 0,
                    "echo should-not-replay", HookRunStore.POLICY_FAIL);
            HookRuntime runtime = runtime(state, List.of(
                    hook("stale-hook", HookEvent.BEFORE_VERIFY,
                            "echo should-not-replay", "fail")));

            HookRuntime.LifecycleResult result = runtime.run(
                    "task", "run", HookEvent.BEFORE_VERIFY, "verify:0");

            assertTrue(result.shouldFail());
            assertEquals(HookRunStore.STATUS_INTERRUPTED,
                    state.hookRuns.findById("stale").orElseThrow().status());
            assertEquals(1, state.hookRuns.findByRun("run").size());
        }
    }

    private HookRuntime runtime(StateStore state, List<HookDefinition> hooks)
            throws Exception {
        PersistenceSanitizer sanitizer = new PersistenceSanitizer();
        HookRunner runner = new HookRunner(
                state.hookRuns,
                new BoundedProcessRunner(workspace, sanitizer, 1024),
                sanitizer, 1024);
        return new HookRuntime(hooks, runner, state.hookRuns);
    }

    private HookDefinition hook(String id, HookEvent event,
                                String command, String policy) {
        return new HookDefinition(id, event, command, 5, policy, true);
    }

    private StateStore preparedState() throws Exception {
        StateStore state = new StateStore(workspace);
        state.tasks.createTask("task", "goal", "mock", null, "on-request", 10);
        state.codingRuns.create("run", "task", CodingRunStore.KIND_INITIAL, "", 1, 0);
        return state;
    }

    private String failingCommand() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows")
                ? "exit /b 7" : "exit 7";
    }
}
