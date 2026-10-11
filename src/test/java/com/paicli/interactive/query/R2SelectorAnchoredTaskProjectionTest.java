package com.paicli.interactive.query;

import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.interactive.runtime.InteractiveRuntimeView;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.session.SavedSessionService;
import com.paicli.interactive.session.SessionBinding;
import com.paicli.interactive.view.InteractiveSessionViewModel;
import com.paicli.interactive.view.SelectedTaskIdentity;
import com.paicli.interactive.view.TaskProjectionKey;
import com.paicli.interactive.view.TaskProjectionProfile;
import com.paicli.provider.telemetry.ProviderCallContext;
import com.paicli.provider.telemetry.ProviderCallObservation;
import com.paicli.state.*;
import com.paicli.state.R1aDurableModels.*;
import com.paicli.state.R1aStoreResults.*;
import com.paicli.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class R2SelectorAnchoredTaskProjectionTest {
    @TempDir Path temp;

    @Test void staleSessionBindingAndLatestNeverMixSelectedTaskFacts()throws Exception{
        Path workspace=temp.resolve("workspace");Files.createDirectories(workspace);
        RootAdmissionTransaction.Request root=rootRequest(workspace);
        CanonicalId taskB=CanonicalId.newId(EntityFamily.TASK);
        CanonicalId runB=CanonicalId.newId(EntityFamily.RUN);
        Instant later=root.committedAt().plusSeconds(1);
        try(StateStore state=new StateStore(workspace)){
            assertInstanceOf(RootAdmissionTransaction.Success.class,
                    state.r1aDurable.admitRootTaskAtomically(root));
            DurableTask second=new DurableTask(taskB,root.thread().id(),root.task().id(),
                    "second", "RUNNING",null,null,later,null);
            assertEquals(CreateResult.SUCCESS,state.r1aDurable.createTask(second));
            String manifestB="manifest-"+UUID.randomUUID();
            ProtectedInstructionArtifact artifactB=ProtectedInstructionArtifact.inlineUtf8(
                    "artifact-"+UUID.randomUUID(),"frozen-b","v1","TASK_AUTHORITY",later);
            var entryB=new InstructionManifestCandidate.Entry("entry-"+UUID.randomUUID(),
                    "PROJECT_FILE","AGENTS.md","WORKSPACE",
                    InstructionManifestCandidate.Status.APPLIED,0,artifactB.contentHash(),
                    artifactB.id(),"UTF8",null,null);
            var candidateB=new InstructionManifestCandidate(1,"root-paicli-agents-v1",
                    List.of(entryB));
            assertInstanceOf(ManifestCommitResult.Success.class,state.r1aDurable.commitManifest(
                    taskB,manifestB,candidateB,List.of(artifactB),later));
            assertEquals(CreateResult.SUCCESS,state.r1aDurable.createRun(
                    new DurableRun(runB,taskB,root.thread().id(),"INITIAL",null,manifestB,
                            candidateB.canonicalHash(),later,null)));
            state.tasks.createTask(taskB.value(),"second","mock","model","deny",3);
            state.tasks.markRunning(taskB.value());
            state.codingRuns.create(runB.value(),taskB.value(),"INITIAL",null,1,0);
            assertEquals(PointerCasResult.SUCCESS,
                    state.r1aDurable.compareAndSetThreadHead(root.thread().id(),
                            root.task().id(),0,taskB));
            recordCalls(state,root.task().id(),root.initialRun().id(),1);
            recordCalls(state,taskB,runB,2);
        }

        InteractiveSession session=new InteractiveSession("selector-session",workspace,
                "mock","model","deny",InteractiveSession.TerminalMode.PLAIN,
                root.committedAt());
        session.start();
        session.bindThread(new SessionBinding(SessionBinding.State.BOUND_TERMINAL,
                root.thread().id(),root.task().id(),"COMPLETED",root.committedAt()));
        StateStoreInteractiveQueryFacade query=new StateStoreInteractiveQueryFacade(
                workspace,new InteractiveRuntimeView());

        List<String> current=query.report(session.snapshot(),"current");
        assertTrue(current.contains("Projection relation: STALE_SESSION_BINDING"));
        assertTrue(current.stream().anyMatch(line->line.contains(display(root.task().id()))));
        assertTrue(current.contains("Provider run calls: 1"));
        assertFalse(current.stream().anyMatch(line->line.contains(display(taskB))));

        List<String> latest=query.report(session.snapshot(),"latest");
        assertTrue(latest.contains("  second"));
        assertFalse(latest.stream().anyMatch(line->line.contains("Provider run calls")));
        assertFalse(latest.stream().anyMatch(line->line.contains(taskB.value())));
        assertFalse(latest.stream().anyMatch(line->line.contains(root.task().id().value())));
        assertEquals(List.of("Report selector unsupported: previous."),
                query.report(session.snapshot(),"previous"));
        assertEquals(List.of("Report selector invalid."),
                query.report(session.snapshot(),"tsk_invalid"));
        assertEquals(List.of("Report selector not found."),query.report(session.snapshot(),
                CanonicalId.newId(EntityFamily.TASK).value()));
    }

    @Test void headCommitAfterSelectorResolutionCannotChangeFrozenProjection()throws Exception{
        Path workspace=temp.resolve("concurrent");Files.createDirectories(workspace);
        RootAdmissionTransaction.Request root=rootRequest(workspace);
        CanonicalId taskB=CanonicalId.newId(EntityFamily.TASK);
        try(StateStore state=new StateStore(workspace)){
            assertInstanceOf(RootAdmissionTransaction.Success.class,
                    state.r1aDurable.admitRootTaskAtomically(root));
            assertEquals(CreateResult.SUCCESS,state.r1aDurable.createTask(new DurableTask(taskB,
                    root.thread().id(),root.task().id(),"next","RUNNING",null,null,
                    root.committedAt().plusSeconds(1),null)));
        }
        InteractiveSession session=new InteractiveSession("snapshot-selector",workspace,
                "mock","model","deny",InteractiveSession.TerminalMode.PLAIN,root.committedAt());
        session.start();
        session.bindThread(new SessionBinding(SessionBinding.State.BOUND_ACTIVE,root.thread().id(),
                root.task().id(),"RUNNING",root.committedAt()));
        try(StateStore reader=new StateStore(workspace)){
            InteractiveSessionViewModel frozen=reader.readOnlySnapshot(()->{
                SelectedTaskIdentity identity=reader.viewModels
                        .resolveCurrentReportIdentityInSnapshot(session.snapshot());
                try(StateStore writer=new StateStore(workspace)){
                    assertEquals(PointerCasResult.SUCCESS,writer.r1aDurable.compareAndSetThreadHead(
                            root.thread().id(),root.task().id(),0,taskB));
                }
                return reader.viewModels.readTaskProjectionInSnapshot(session.snapshot(),
                        new TaskProjectionKey(identity,TaskProjectionProfile.HISTORICAL_REPORT),
                        null);
            });
            assertEquals(root.task().id().value(),frozen.task().value().taskId());
            SelectedTaskIdentity after=reader.readOnlySnapshot(()->reader.viewModels
                    .resolveLatestReportIdentityInSnapshot(session.snapshot()));
            assertEquals(taskB,after.taskId());
        }
    }

    @Test void reportUsesOnlyPointerRunWhenSameTaskHasNewerLegacyRun()throws Exception{
        Path workspace=temp.resolve("same-task-runs");Files.createDirectories(workspace);
        RootAdmissionTransaction.Request root=rootRequest(workspace);
        CanonicalId runA=root.initialRun().id();
        CanonicalId runB=CanonicalId.newId(EntityFamily.RUN);
        try(StateStore state=new StateStore(workspace)){
            assertInstanceOf(RootAdmissionTransaction.Success.class,
                    state.r1aDurable.admitRootTaskAtomically(root));
            state.plans.recordGenerated("plan-a",runA.value(),root.task().id().value(),
                    "selected plan A");
            state.plans.markActive("plan-a");
            state.plans.markCompleted("plan-a");
            state.verifications.recordStarted("verify-a",runA.value(),
                    root.task().id().value(),0,"STATIC_A","WORKSPACE_CONTENT_SHA256_V1",
                    "a".repeat(64));
            state.verifications.markPassed("verify-a",0,"selected A",1);
            state.repairAttempts.recordStarted("repair-a",runA.value(),
                    root.task().id().value(),1,"verify-a","selected A");
            state.repairAttempts.markCompleted("repair-a");
            state.codingRuns.markCompleted(runA.value());

            state.codingRuns.create(runB.value(),root.task().id().value(),
                    CodingRunStore.KIND_RESUME,null,2,1);
            state.plans.recordGenerated("plan-b",runB.value(),root.task().id().value(),
                    "newer plan B");
            state.verifications.recordStarted("verify-b",runB.value(),
                    root.task().id().value(),1,"STATIC_B","WORKSPACE_CONTENT_SHA256_V1",
                    "b".repeat(64));
            state.verifications.markFailed("verify-b",7,"newer B failed",null,1);
            state.repairAttempts.recordStarted("repair-b",runB.value(),
                    root.task().id().value(),2,"verify-b","newer B failure");
            state.toolCalls.record(root.task().id().value(),99,"shell","{}",
                    ToolResult.error("newer B tool failure"));
            state.codingRuns.markFailed(runB.value(),"newer B run error");
            recordCalls(state,root.task().id(),runA,1);
            recordCalls(state,root.task().id(),runB,2);
        }
        InteractiveSession session=boundSession("same-task-session",workspace,root,
                SessionBinding.State.BOUND_ACTIVE);
        List<String> report=new StateStoreInteractiveQueryFacade(workspace,
                new InteractiveRuntimeView()).report(session.snapshot(),"current");
        assertTrue(report.contains("Run: "+display(runA)));
        assertTrue(report.contains("Run phase: COMPLETED"));
        assertTrue(report.contains("Legacy Run Plan: COMPLETED [RUN_SCOPED]"));
        assertTrue(report.contains("Verification: PASSED [RUN_SCOPED]"));
        assertTrue(report.contains(
                "Repair: started=1 completed=1 failed=0 running=0 [RUN_SCOPED]"));
        assertTrue(report.contains("Provider run calls: 1"));
        assertFalse(report.stream().anyMatch(line->line.contains(display(runB))));
        assertFalse(report.contains("Verification: FAILED [RUN_SCOPED]"));
        assertFalse(report.contains("Legacy Run Plan: GENERATED [RUN_SCOPED]"));
        assertFalse(report.contains("Failure domain: TOOL_EXECUTION"));
        assertFalse(report.contains("Failure domain: VERIFICATION"));
    }

    @Test void reportWithNoSelectedRunNeverFallsBackToLegacyRun()throws Exception{
        Path workspace=temp.resolve("no-selected-run");Files.createDirectories(workspace);
        RootAdmissionTransaction.Request root=rootRequest(workspace);
        CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
        CanonicalId legacyRun=CanonicalId.newId(EntityFamily.RUN);
        try(StateStore state=new StateStore(workspace)){
            assertInstanceOf(RootAdmissionTransaction.Success.class,
                    state.r1aDurable.admitRootTaskAtomically(root));
            Instant later=root.committedAt().plusSeconds(2);
            assertEquals(CreateResult.SUCCESS,state.r1aDurable.createTask(new DurableTask(task,
                    root.thread().id(),root.task().id(),"no durable run","CREATED",null,null,
                    later,null)));
            assertEquals(PointerCasResult.SUCCESS,state.r1aDurable.compareAndSetThreadHead(
                    root.thread().id(),root.task().id(),0,task));
            state.tasks.createTask(task.value(),"legacy only run","mock","model","deny",3);
            state.tasks.markRunning(task.value());
            state.codingRuns.create(legacyRun.value(),task.value(),CodingRunStore.KIND_INITIAL,
                    null,1,0);
            state.plans.recordGenerated("legacy-plan",legacyRun.value(),task.value(),
                    "must not be selected");
            recordCalls(state,task,legacyRun,2);
        }
        InteractiveSession session=new InteractiveSession("no-run-session",workspace,
                "mock","model","deny",InteractiveSession.TerminalMode.PLAIN,
                root.committedAt());
        session.start();
        session.bindThread(new SessionBinding(SessionBinding.State.BOUND_ACTIVE,
                root.thread().id(),task,"CREATED",root.committedAt().plusSeconds(2)));
        List<String> report=new StateStoreInteractiveQueryFacade(workspace,
                new InteractiveRuntimeView()).report(session.snapshot(),"current");
        assertTrue(report.contains("Run: NOT_APPLICABLE"));
        assertTrue(report.contains("Run phase: NOT_APPLICABLE/NOT_APPLICABLE"));
        assertTrue(report.contains("Legacy Run Plan: NOT_APPLICABLE/NOT_APPLICABLE"));
        assertTrue(report.contains("Verification: NOT_APPLICABLE/NOT_APPLICABLE"));
        assertTrue(report.contains("Repair: NOT_APPLICABLE/NOT_APPLICABLE"));
        assertTrue(report.contains("Provider run calls: NOT_APPLICABLE/NOT_APPLICABLE"));
        assertFalse(report.stream().anyMatch(line->line.contains(display(legacyRun))));
    }

    private static void recordCalls(StateStore state,CanonicalId task,CanonicalId run,int count){
        for(int index=0;index<count;index++){
            String correlation=UUID.randomUUID().toString();
            Instant started=Instant.parse("2026-07-20T00:00:0"+index+"Z");
            assertTrue(state.providerTelemetry.recordStarted(new ProviderCallTelemetryStore.CallStart(
                    correlation,"mock","model",ProviderCallTelemetryStore.OperationKind.CHAT,
                    new ProviderCallContext(null,task.value(),run.value(),null),started)));
            ProviderCallObservation observation=new ProviderCallObservation(1,1,2,null,null,null,
                    ProviderCallObservation.UsageSource.PROVIDER_REPORTED,
                    ProviderCallObservation.UsageCoverage.COMPLETE,null,null,"stop",null,null,false);
            assertTrue(state.providerTelemetry.finalizeCall(new ProviderCallTelemetryStore.CallFinish(
                    correlation,ProviderCallTelemetryStore.Lifecycle.SUCCEEDED,
                    started.plusMillis(1),1,"FINAL",0,observation,null,null)));
        }
    }

    private static String display(CanonicalId id){
        String value=id.value();return value.substring(0,7)+"…"+value.substring(value.length()-8);
    }

    private static InteractiveSession boundSession(String id,Path workspace,
            RootAdmissionTransaction.Request root,SessionBinding.State state){
        InteractiveSession session=new InteractiveSession(id,workspace,"mock","model","deny",
                InteractiveSession.TerminalMode.PLAIN,root.committedAt());
        session.start();
        session.bindThread(new SessionBinding(state,root.thread().id(),root.task().id(),
                "RUNNING",root.committedAt()));
        return session;
    }

    private static RootAdmissionTransaction.Request rootRequest(Path workspace){
        Instant at=Instant.parse("2026-07-20T00:00:00Z");
        CanonicalId thread=CanonicalId.newId(EntityFamily.THREAD);
        CanonicalId task=CanonicalId.newId(EntityFamily.TASK);
        CanonicalId run=CanonicalId.newId(EntityFamily.RUN);
        CanonicalId generation=CanonicalId.newId(EntityFamily.GENERATION);
        String manifest="manifest-"+UUID.randomUUID();
        ProtectedInstructionArtifact artifact=ProtectedInstructionArtifact.inlineUtf8(
                "artifact-"+UUID.randomUUID(),"frozen","v1","TASK_AUTHORITY",at);
        var entry=new InstructionManifestCandidate.Entry("entry-"+UUID.randomUUID(),
                "PROJECT_FILE","AGENTS.md","WORKSPACE",InstructionManifestCandidate.Status.APPLIED,
                0,artifact.contentHash(),artifact.id(),"UTF8",null,null);
        var candidate=new InstructionManifestCandidate(1,"root-paicli-agents-v1",List.of(entry));
        String hash=candidate.canonicalHash();
        return new RootAdmissionTransaction.Request(
                new WorkThread(thread,SavedSessionService.workspaceIdentity(workspace),"root","OPEN",at,at),
                new DurableTask(task,thread,null,"root","CREATED",null,null,at,null),
                manifest,candidate,List.of(artifact),
                new DurableRun(run,task,thread,"INITIAL",null,manifest,hash,at,null),
                new TaskContextGeneration(generation,task,null,run,manifest,hash,"COMMITTED","{}",at,at),
                new TaskContextPointer(task,generation,run,0),
                new RootAdmissionTransaction.RuntimeTask(task.value(),"root","mock","model","deny",3,"RUNNING"),
                new RootAdmissionTransaction.RuntimeRun(run.value(),task.value(),"INITIAL","PLANNING","",1,0,
                        "skills-disabled","hooks-disabled"),
                StructuredPlanModels.PlanApplicability.PLAN_DISABLED,at);
    }
}
