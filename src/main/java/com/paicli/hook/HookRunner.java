package com.paicli.hook;

import com.paicli.runtime.BoundedProcessRunner;
import com.paicli.runtime.PersistenceSanitizer;
import com.paicli.runtime.FencedExecutionGuard;
import com.paicli.state.HookRunStore;

import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import com.paicli.identity.CanonicalId;
import com.paicli.tool.ToolResult;

/** Executes validated hook definitions and persists bounded audit results. */
public final class HookRunner {

    public static final String STATUS_SKIPPED = "SKIPPED";

    private final HookRunStore store;
    private final HookProcessExecutor processRunner;
    private final PersistenceSanitizer sanitizer;
    private final int maxOutputBytes;
    private final ForbiddenHookCommandPolicy forbiddenPolicy = new ForbiddenHookCommandPolicy();
    private final FencedExecutionGuard executionGuard;
    private final LaunchCoordinator launchCoordinator;

    public HookRunner(HookRunStore store, BoundedProcessRunner processRunner,
                      PersistenceSanitizer sanitizer, int maxOutputBytes) {
        this(store,processRunner,sanitizer,maxOutputBytes,null);
    }

    public HookRunner(HookRunStore store,BoundedProcessRunner processRunner,
            PersistenceSanitizer sanitizer,int maxOutputBytes,
            FencedExecutionGuard executionGuard){
        this.store = Objects.requireNonNull(store, "store");
        this.processRunner = Objects.requireNonNull(processRunner, "processRunner")::execute;
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer");
        if (maxOutputBytes < 1) {
            throw new IllegalArgumentException("maxOutputBytes must be positive");
        }
        this.maxOutputBytes = maxOutputBytes;
        this.executionGuard=executionGuard;
        this.launchCoordinator=LaunchCoordinator.NOOP;
    }

    HookRunner(HookRunStore store, HookProcessExecutor processRunner,
               PersistenceSanitizer sanitizer, int maxOutputBytes) {
        this.store = Objects.requireNonNull(store, "store");
        this.processRunner = Objects.requireNonNull(processRunner, "processRunner");
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer");
        if (maxOutputBytes < 1) {
            throw new IllegalArgumentException("maxOutputBytes must be positive");
        }
        this.maxOutputBytes = maxOutputBytes;
        this.executionGuard=null;
        this.launchCoordinator=LaunchCoordinator.NOOP;
    }

    HookRunner(HookRunStore store,HookProcessExecutor processRunner,
            PersistenceSanitizer sanitizer,int maxOutputBytes,
            FencedExecutionGuard executionGuard,LaunchCoordinator launchCoordinator){
        this.store=Objects.requireNonNull(store,"store");
        this.processRunner=Objects.requireNonNull(processRunner,"processRunner");
        this.sanitizer=Objects.requireNonNull(sanitizer,"sanitizer");
        if(maxOutputBytes<1)throw new IllegalArgumentException(
                "maxOutputBytes must be positive");
        this.maxOutputBytes=maxOutputBytes;
        this.executionGuard=Objects.requireNonNull(executionGuard,"executionGuard");
        this.launchCoordinator=Objects.requireNonNull(launchCoordinator,"launchCoordinator");
    }

    HookRunner withExecutionGuard(FencedExecutionGuard guard){
        return new HookRunner(store,processRunner,sanitizer,maxOutputBytes,
                Objects.requireNonNull(guard),LaunchCoordinator.NOOP);
    }

    public HookResult run(String taskId, String runId, String occurrenceKey,
                          int sequenceNo, HookDefinition hook) throws SQLException {
        requireText(taskId, "taskId");
        requireText(runId, "runId");
        requireText(occurrenceKey, "occurrenceKey");
        if (sequenceNo < 0) throw new IllegalArgumentException("sequenceNo must not be negative");
        Objects.requireNonNull(hook, "hook");
        if (!hook.enabled()) {
            return result(null, hook, STATUS_SKIPPED, null, 0, null,
                    "Hook is disabled");
        }

        String hookRunId = UUID.randomUUID().toString();
        String persistedCommand = sanitizer.sanitizeAndTruncate(hook.command(), maxOutputBytes);
        CanonicalId fencedOccurrence=executionGuard==null?null:
                executionGuard.admitOperationOccurrence("hook:"+sequenceNo,
                        "recovery-hook:"+runId+":"+hook.event()+":"+occurrenceKey+":"+hook.id(),
                        "HOOK",hook.id(),FencedExecutionGuard.canonicalInputHash(
                                hook.event()+"\n"+hook.command()));
        if(executionGuard!=null)executionGuard.startHook(hookRunId,hook.id(),hook.event().name(),
                occurrenceKey,sequenceNo,persistedCommand,hook.failurePolicy());
        else store.recordStarted(hookRunId, runId, taskId, hook.id(), hook.event().name(),
                occurrenceKey, sequenceNo, persistedCommand, hook.failurePolicy());

        Optional<String> rejection = forbiddenPolicy.rejectionReason(hook.command());
        if (rejection.isPresent()) {
            String error = sanitizer.sanitizeAndTruncate(rejection.get(), maxOutputBytes);
            finish(hookRunId,HookRunStore.STATUS_BLOCKED,null,null,error,0);
            if(fencedOccurrence!=null)executionGuard.failOccurrence(fencedOccurrence,error);
            return result(hookRunId, hook, HookRunStore.STATUS_BLOCKED,
                    null, 0, null, error);
        }

        BoundedProcessRunner.ProcessResult process;
        launchCoordinator.beforeLaunch();
        try {
            if(executionGuard!=null)executionGuard.revalidateBeforeExternalEffect(
                    fencedOccurrence,"HOOK",hookRunId);
        } catch(FencedExecutionGuard.SteeringEffectBlockedException blocked) {
            return result(hookRunId,hook,HookRunStore.STATUS_PAUSED_BY_STEERING,
                    null,0,null,"PAUSED_BY_STEERING");
        }
        try {
            process = Objects.requireNonNull(
                    processRunner.execute(hook.command(), hook.timeoutSeconds()),
                    "hook process executor returned null");
        } catch (RuntimeException e) {
            String message = e.getMessage() == null || e.getMessage().isBlank()
                    ? e.getClass().getSimpleName() : e.getMessage();
            String error = sanitizer.sanitizeAndTruncate(
                    "Hook command execution failed: " + message, maxOutputBytes);
            finish(hookRunId,HookRunStore.STATUS_ERROR,null,null,error,0);
            if(fencedOccurrence!=null)executionGuard.failOccurrence(fencedOccurrence,error);
            return result(hookRunId, hook, HookRunStore.STATUS_ERROR,
                    null, 0, null, error);
        }
        String status;
        switch (process.status()) {
            case PASSED -> {
                status = HookRunStore.STATUS_PASSED;
                finish(hookRunId,status,process.exitCode(),process.outputExcerpt(),
                        process.errorExcerpt(),process.durationMs());
            }
            case FAILED -> {
                status = "warn".equals(hook.failurePolicy())
                        ? HookRunStore.STATUS_WARNED : HookRunStore.STATUS_FAILED;
                if (HookRunStore.STATUS_WARNED.equals(status)) {
                    finish(hookRunId,status,process.exitCode(),process.outputExcerpt(),
                            process.errorExcerpt(),process.durationMs());
                } else {
                    finish(hookRunId,status,process.exitCode(),process.outputExcerpt(),
                            process.errorExcerpt(),process.durationMs());
                }
            }
            case TIMED_OUT -> {
                status = HookRunStore.STATUS_TIMED_OUT;
                finish(hookRunId,status,null,process.outputExcerpt(),process.errorExcerpt(),
                        process.durationMs());
            }
            case ERROR -> {
                status = HookRunStore.STATUS_ERROR;
                finish(hookRunId,status,null,process.outputExcerpt(),process.errorExcerpt(),
                        process.durationMs());
            }
            default -> throw new IllegalStateException("Unsupported process status");
        }
        if(fencedOccurrence!=null)executionGuard.completeOccurrence(fencedOccurrence,
                HookRunStore.STATUS_PASSED.equals(status)?ToolResult.success("hook passed"):
                        ToolResult.error("hook "+status));
        return result(hookRunId, hook, status, process.exitCode(), process.durationMs(),
                process.outputExcerpt(), process.errorExcerpt());
    }

    private HookResult result(String hookRunId, HookDefinition hook, String status,
                              Integer exitCode, long durationMs,
                              String output, String error) {
        return new HookResult(hookRunId, hook.id(), hook.event(), status, exitCode,
                durationMs, output, error, hook.failurePolicy());
    }

    private void finish(String id,String status,Integer exit,String output,String error,long duration)
            throws SQLException{
        if(executionGuard!=null){executionGuard.finishHook(id,status,exit,output,error,duration);return;}
        switch(status){
            case HookRunStore.STATUS_PASSED -> store.markPassed(id,exit,output,error,duration);
            case HookRunStore.STATUS_WARNED -> store.markWarned(id,exit,output,error,duration);
            case HookRunStore.STATUS_FAILED -> store.markFailed(id,exit,output,error,duration);
            case HookRunStore.STATUS_BLOCKED -> store.markBlocked(id,error,duration);
            case HookRunStore.STATUS_TIMED_OUT -> store.markTimedOut(id,output,error,duration);
            default -> store.markError(id,output,error,duration);
        }
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    @FunctionalInterface
    interface HookProcessExecutor {
        BoundedProcessRunner.ProcessResult execute(String command, int timeoutSeconds);
    }

    @FunctionalInterface
    interface LaunchCoordinator{
        LaunchCoordinator NOOP=()->{};
        void beforeLaunch();
    }
}
