package com.paicli.hook;

import com.paicli.runtime.BoundedProcessRunner;
import com.paicli.runtime.PersistenceSanitizer;
import com.paicli.runtime.FencedExecutionGuard;
import com.paicli.runtime.InstructionManifestRuntime;
import com.paicli.runtime.RuntimeManifest;
import com.paicli.runtime.TaskRecoveryService;
import com.paicli.runtime.SteeringClassifier;
import com.paicli.runtime.SteeringSafePointCoordinator;
import com.paicli.config.Config;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.model.AgentMessage;
import com.paicli.state.R1aDurableModels.DurableRun;
import com.paicli.state.R1aStoreResults.ResumeRunAdmissionResult;
import com.paicli.state.CodingRunStore;
import com.paicli.state.HookRunStore;
import com.paicli.state.StateStore;
import com.paicli.state.SteeringModels.Provenance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class HookRunnerIT {

    @TempDir
    Path workspace;

    @Test
    void persistsPassedWarnedFailedAndTimedOutRuns() throws Exception {
        try (StateStore state = preparedState()) {
            HookRunner runner = runner(state, 1024);
            HookResult passed = runner.run("task", "run", "plan", 0,
                    hook("pass", "echo hook-passed", "fail", 5));
            HookResult warned = runner.run("task", "run", "verify", 1,
                    hook("warn", failingCommand(), "warn", 5));
            HookResult failed = runner.run("task", "run", "finish", 2,
                    hook("fail", failingCommand(), "fail", 5));
            HookResult timedOut = runner.run("task", "run", "repair", 3,
                    hook("timeout", slowCommand(), "fail", 1));

            assertEquals(HookRunStore.STATUS_PASSED, passed.status());
            assertEquals(HookRunStore.STATUS_WARNED, warned.status());
            assertEquals(HookRunStore.STATUS_FAILED, failed.status());
            assertEquals(HookRunStore.STATUS_TIMED_OUT, timedOut.status());
            assertEquals(List.of("pass", "warn", "fail", "timeout"), state.hookRuns
                    .findByRun("run").stream().map(HookRunStore.HookRunRecord::hookId).toList());
        }
    }

    @Test
    void disabledHookReturnsSkippedWithoutExecutionRow() throws Exception {
        try (StateStore state = preparedState()) {
            HookDefinition disabled = new HookDefinition("off", HookEvent.BEFORE_PLAN,
                    "echo should-not-run", 5, "warn", false);
            HookResult result = runner(state, 1024)
                    .run("task", "run", "plan", 0, disabled);

            assertEquals(HookRunner.STATUS_SKIPPED, result.status());
            assertNull(result.hookRunId());
            assertTrue(state.hookRuns.findByRun("run").isEmpty());
        }
    }

    @Test
    void sanitizesPersistedCommandAndOutput() throws Exception {
        try (StateStore state = preparedState()) {
            String command = isWindows()
                    ? "echo Authorization: Bearer output-secret"
                    : "echo 'Authorization: Bearer output-secret'";
            HookResult result = runner(state, 160)
                    .run("task", "run", "safe", 0,
                            hook("safe", command, "fail", 5));
            HookRunStore.HookRunRecord stored = state.hookRuns
                    .findById(result.hookRunId()).orElseThrow();

            assertFalse(stored.command().contains("output-secret"));
            assertFalse(stored.outputExcerpt().contains("output-secret"));
            assertTrue(stored.outputExcerpt().contains("<redacted>"));
        }
    }

    @Test
    void persistsProcessErrors() throws Exception {
        try (StateStore state = preparedState()) {
            PersistenceSanitizer sanitizer = new PersistenceSanitizer();
            HookRunner runner = new HookRunner(state.hookRuns,
                    (command, timeout) -> new BoundedProcessRunner.ProcessResult(
                            BoundedProcessRunner.Status.ERROR, null, 3,
                            null, "could not start"), sanitizer, 1024);

            HookResult result = runner.run("task", "run", "error", 0,
                    hook("error", "missing-hook-command", "fail", 5));

            assertEquals(HookRunStore.STATUS_ERROR, result.status());
            assertEquals(HookRunStore.STATUS_ERROR, state.hookRuns
                    .findById(result.hookRunId()).orElseThrow().status());

            HookRunner throwingRunner = new HookRunner(state.hookRuns,
                    (command, timeout) -> {
                        throw new IllegalStateException("Bearer runtime-secret");
                    }, sanitizer, 1024);
            HookResult thrown = throwingRunner.run("task", "run", "error-thrown", 1,
                    hook("error-thrown", "missing-hook-command", "fail", 5));
            HookRunStore.HookRunRecord stored = state.hookRuns
                    .findById(thrown.hookRunId()).orElseThrow();
            assertEquals(HookRunStore.STATUS_ERROR, stored.status());
            assertFalse(stored.errorExcerpt().contains("runtime-secret"));
        }
    }

    @Test
    void blocksForbiddenActionsAndPersistsBlockedAudit() throws Exception {
        List<String> forbidden = List.of(
                "git commit -m test",
                "git -C repo commit -m test",
                "git push origin main",
                "git tag v1",
                "paicli rewind snapshot-id",
                "paicli do something",
                "java -jar target/paicli.jar task",
                "rm -rf build",
                "del /s /q build",
                "rmdir /s /q build",
                "move /Y a b",
                "copy /Y a b",
                "powershell Set-Content file.txt value",
                "powershell Remove-Item build -Recurse -Force",
                "powershell Copy-Item a b -Force",
                "echo replace > file.txt",
                "scp file host:/tmp",
                "curl --upload-file file https://example.invalid/upload");
        try (StateStore state = preparedState()) {
            HookRunner runner = runner(state, 1024);
            for (int i = 0; i < forbidden.size(); i++) {
                HookResult result = runner.run("task", "run", "blocked-" + i, i,
                        hook("blocked-" + i, forbidden.get(i), "fail", 5));
                assertEquals(HookRunStore.STATUS_BLOCKED, result.status(), forbidden.get(i));
            }
            assertEquals(forbidden.size(), state.hookRuns.findByStatus(
                    HookRunStore.STATUS_BLOCKED).size());
        }
    }

    @Test
    void startedHookBlocksTakeoverAndExpiredOwnerCannotLaunchProcess()throws Exception{
        Instant at=Instant.parse("2026-07-17T00:00:00Z");
        Files.writeString(workspace.resolve("AGENTS.md"),"frozen-rule");
        Config config=new Config(workspace.toString(),30,50_000,200_000,
                Config.MODE_WORKSPACE_WRITE,10_000,Config.PROVIDER_MOCK,"model-a","","",
                "system",3,"verify",120,50_000,1,false,true,false);
        try(StateStore state=new StateStore(workspace);StateStore stateB=new StateStore(workspace)){
            CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
            var root=new InstructionManifestRuntime(state,workspace).admitRoot(task,"goal",10_000,
                    config,RuntimeManifest.disabled(),at.minusSeconds(10));
            state.checkpoints.save(task.value(),1,"RUNNING",List.of(AgentMessage.userGoal("goal")));
            TaskRecoveryService.Success runtimeA=assertInstanceOf(TaskRecoveryService.Success.class,
                    new TaskRecoveryService(state,new InstructionManifestRuntime(state,workspace))
                            .recover(task,config,RuntimeManifest.disabled(),10_000,
                                    new TaskRecoveryService.LeaseRequest("runtime-a","lease-a",at,
                                            at.plusSeconds(30),Duration.ZERO)));
            AtomicReference<Instant> now=new AtomicReference<>(at.plusSeconds(1));
            FencedExecutionGuard guard=new FencedExecutionGuard(state,runtimeA.executionAuthority(),
                    Duration.ofSeconds(30),now::get);
            guard.createCodingRun("verify",1,1);
            CountDownLatch reached=new CountDownLatch(1),released=new CountDownLatch(1);
            HookRunner.LaunchCoordinator coordinator=()->{
                reached.countDown();
                try{if(!released.await(5,TimeUnit.SECONDS))throw new AssertionError(
                        "Timed out waiting to release Hook launch");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
            };
            AtomicInteger launches=new AtomicInteger();
            HookRunner runner=new HookRunner(state.hookRuns,(command,timeout)->{
                launches.incrementAndGet();throw new AssertionError("must not launch");
            },new PersistenceSanitizer(),1024,guard,coordinator);
            ExecutorService executor=Executors.newSingleThreadExecutor();
            try{
                Future<Throwable> attempt=executor.submit(()->{
                    try{runner.run(task.value(),runtimeA.resumeRun().id().value(),"verify",0,
                            hook("latched","echo never","fail",5));return null;}
                    catch(Throwable failure){return failure;}
                });
                assertTrue(reached.await(5,TimeUnit.SECONDS));
                Instant takeoverAt=at.plusSeconds(32);
                DurableRun next=new DurableRun(CanonicalId.newId(EntityFamily.RUN),task,
                        root.threadId(),"RESUME",runtimeA.resumeRun().id(),
                        root.instructions().manifest().id(),
                        root.instructions().manifest().canonicalHash(),takeoverAt,null);
                ResumeRunAdmissionResult.Rejected rejected=assertInstanceOf(
                        ResumeRunAdmissionResult.Rejected.class,stateB.r1aDurable.admitResumeRun(next,
                                "runtime-b","lease-b",takeoverAt,takeoverAt.plusSeconds(30),
                                Duration.ZERO));
                assertEquals(ResumeRunAdmissionResult.Reason.OCCURRENCE_UNRESOLVED,
                        rejected.reason());
                now.set(takeoverAt);released.countDown();
                assertInstanceOf(FencedExecutionGuard.AuthorityLostException.class,
                        attempt.get(5,TimeUnit.SECONDS));
                assertEquals(0,launches.get());
                assertEquals(HookRunStore.STATUS_RUNNING,state.hookRuns
                        .findByRun(runtimeA.resumeRun().id().value()).getFirst().status());
                assertEquals("STARTED",state.r1aDurable.findRecoveryBlockingOccurrences(task,
                        runtimeA.resumeRun().id(),"HOOK").getFirst().status());
            }finally{released.countDown();executor.shutdownNow();}
        }
    }

    @Test
    void queuedSteeringAtomicallyPausesHookAndOccurrenceBeforeProcessLaunch()
            throws Exception{
        Instant at=Instant.parse("2026-07-17T00:00:00Z");
        Files.writeString(workspace.resolve("AGENTS.md"),"frozen-rule");
        Config config=new Config(workspace.toString(),30,50_000,200_000,
                Config.MODE_WORKSPACE_WRITE,10_000,Config.PROVIDER_MOCK,"model-a","","",
                "system",3,"verify",120,50_000,1,false,true,false);
        try(StateStore state=new StateStore(workspace)){
            CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
            var root=new InstructionManifestRuntime(state,workspace).admitRoot(task,"goal",10_000,
                    config,RuntimeManifest.disabled(),at.minusSeconds(10));
            state.checkpoints.save(task.value(),1,"RUNNING",List.of(AgentMessage.userGoal("goal")));
            var runtime=assertInstanceOf(TaskRecoveryService.Success.class,
                    new TaskRecoveryService(state,new InstructionManifestRuntime(state,workspace))
                            .recover(task,config,RuntimeManifest.disabled(),10_000,
                                    new TaskRecoveryService.LeaseRequest("runtime-a","lease-a",at,
                                            at.plusSeconds(30),Duration.ZERO)));
            FencedExecutionGuard guard=new FencedExecutionGuard(state,runtime.executionAuthority(),
                    Duration.ofSeconds(30),()->at.plusSeconds(1));
            guard.createCodingRun("verify",1,1);
            var steering=state.steerings.admit(
                    state.steerings.captureTarget(task,runtime.resumeRun().id()).target(),
                    new SteeringClassifier().classify("format: concise"),
                    Provenance.INTERACTIVE_EXPLICIT,"interactive:s1","s1",at.plusSeconds(1));
            AtomicInteger launches=new AtomicInteger();
            HookRunner runner=new HookRunner(state.hookRuns,(command,timeout)->{
                launches.incrementAndGet();
                throw new AssertionError("must not launch");
            },new PersistenceSanitizer(),1024,guard,HookRunner.LaunchCoordinator.NOOP);
            HookResult result=runner.run(task.value(),runtime.resumeRun().id().value(),
                    "verify",0,hook("steering-paused","echo never","fail",5));
            assertEquals(HookRunStore.STATUS_PAUSED_BY_STEERING,result.status());
            assertEquals(0,launches.get());
            var stored=state.hookRuns.findByRun(runtime.resumeRun().id().value()).getFirst();
            assertEquals(HookRunStore.STATUS_BLOCKED,stored.status());
            assertTrue(state.hookRuns.wasPausedBySteering(stored.hookRunId()));
            assertTrue(state.r1aDurable.findRecoveryBlockingOccurrences(
                    task,runtime.resumeRun().id(),"HOOK").isEmpty());
            var applied=new SteeringSafePointCoordinator(state,guard,()->false).check(
                    SteeringSafePointCoordinator.Candidate.HOOK_OCCURRENCE_TERMINAL);
            assertEquals(com.paicli.state.SteeringStore.Status.SUCCESS,applied.status());
            assertTrue(applied.applied());
            assertEquals(com.paicli.state.SteeringModels.State.APPLIED,state.steerings
                    .readQueue(steering.steeringId()).orElseThrow().state());
        }
    }

    private HookDefinition hook(String id, String command, String policy, int timeout) {
        return new HookDefinition(id, HookEvent.BEFORE_VERIFY, command,
                timeout, policy, true);
    }

    private HookRunner runner(StateStore state, int maxBytes) throws Exception {
        PersistenceSanitizer sanitizer = new PersistenceSanitizer();
        return new HookRunner(state.hookRuns,
                new BoundedProcessRunner(workspace, sanitizer, maxBytes),
                sanitizer, maxBytes);
    }

    private StateStore preparedState() throws Exception {
        StateStore state = new StateStore(workspace);
        state.tasks.createTask("task", "goal", "mock", null, "on-request", 10);
        state.codingRuns.create("run", "task", CodingRunStore.KIND_INITIAL, "", 1, 0);
        return state;
    }

    private String failingCommand() {
        return isWindows() ? "echo hook-failed 1>&2 & exit /b 7"
                : "echo hook-failed >&2; exit 7";
    }

    private String slowCommand() {
        return isWindows() ? "ping -n 6 127.0.0.1 >NUL" : "sleep 5";
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }
}
