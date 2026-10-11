package com.paicli.hook;

import com.paicli.state.HookRunStore;
import com.paicli.runtime.FencedExecutionGuard;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Runs static lifecycle hooks in declaration order with occurrence idempotence. */
public final class HookRuntime {

    private final List<HookDefinition> hooks;
    private final HookRunner runner;
    private final HookRunStore store;
    private final FencedExecutionGuard executionGuard;

    public HookRuntime(List<HookDefinition> hooks, HookRunner runner,
                       HookRunStore store) {
        this(hooks,runner,store,null);
    }

    public HookRuntime(List<HookDefinition> hooks,HookRunner runner,HookRunStore store,
            FencedExecutionGuard executionGuard){
        this.hooks = List.copyOf(Objects.requireNonNull(hooks, "hooks"));
        this.runner = Objects.requireNonNull(runner, "runner");
        this.store = Objects.requireNonNull(store, "store");
        this.executionGuard=executionGuard;
    }

    public LifecycleResult run(String taskId, String runId,
                               HookEvent event, String occurrenceKey)
            throws SQLException {
        Objects.requireNonNull(event, "event");
        List<HookResult> results = new ArrayList<>();
        for (int index = 0; index < hooks.size(); index++) {
            HookDefinition hook = hooks.get(index);
            if (hook.event() != event) {
                continue;
            }
            HookResult result = runOrRecover(
                    taskId, runId, event, occurrenceKey, index, hook);
            results.add(result);
            if(HookRunStore.STATUS_PAUSED_BY_STEERING.equals(result.status())){
                return new LifecycleResult(List.copyOf(results),false,null,true);
            }
            if (isBlocking(result)) {
                return new LifecycleResult(
                        List.copyOf(results), true, failureMessage(event, result),false);
            }
        }
        return new LifecycleResult(List.copyOf(results), false, null,false);
    }

    public HookRuntime withExecutionGuard(FencedExecutionGuard guard){
        Objects.requireNonNull(guard,"guard");
        return new HookRuntime(hooks,runner.withExecutionGuard(guard),store,guard);
    }

    private HookResult runOrRecover(
            String taskId, String runId, HookEvent event,
            String occurrenceKey, int sequenceNo, HookDefinition hook)
            throws SQLException {
        HookRunStore.HookRunRecord existing = store.findByOccurrence(
                runId, event.name(), occurrenceKey, hook.id()).orElse(null);
        if (existing == null) {
            return runner.run(taskId, runId, occurrenceKey, sequenceNo, hook);
        }
        if (HookRunStore.STATUS_RUNNING.equals(existing.status())) {
            if(executionGuard!=null)executionGuard.finishHook(existing.hookRunId(),
                    HookRunStore.STATUS_INTERRUPTED,null,null,
                    "Hook execution was interrupted; not replayed",0);
            else store.markInterrupted(existing.hookRunId(),
                    "Hook execution was interrupted; not replayed", 0);
            existing = store.findById(existing.hookRunId()).orElseThrow();
        }
        HookResult result=fromRecord(existing,event);
        return store.wasPausedBySteering(existing.hookRunId())
                ?new HookResult(result.hookRunId(),result.hookId(),result.event(),
                        HookRunStore.STATUS_PAUSED_BY_STEERING,result.exitCode(),
                        result.durationMs(),result.outputExcerpt(),result.errorExcerpt(),
                        result.failurePolicy())
                :result;
    }

    private HookResult fromRecord(HookRunStore.HookRunRecord record,
                                  HookEvent event) {
        return new HookResult(
                record.hookRunId(), record.hookId(), event, record.status(),
                record.exitCode(), record.durationMs() == null ? 0 : record.durationMs(),
                record.outputExcerpt(), record.errorExcerpt(), record.failurePolicy());
    }

    private boolean isBlocking(HookResult result) {
        if (!HookRunStore.POLICY_FAIL.equals(result.failurePolicy())) {
            return false;
        }
        return !HookRunStore.STATUS_PASSED.equals(result.status())
                && !HookRunner.STATUS_SKIPPED.equals(result.status());
    }

    private String failureMessage(HookEvent event, HookResult result) {
        return "Hook failure at " + event + ": " + result.hookId()
                + " [" + result.status() + "]";
    }

    public record LifecycleResult(
            List<HookResult> results,
            boolean shouldFail,
            String failureMessage,
            boolean pausedBySteering
    ) {
        public LifecycleResult {
            results = List.copyOf(results);
        }

        public String summary() {
            if (results.isEmpty()) {
                return "no hooks";
            }
            return results.stream()
                    .map(result -> result.hookId() + "=" + result.status())
                    .reduce((left, right) -> left + ", " + right)
                    .orElse("no hooks");
        }
    }
}
