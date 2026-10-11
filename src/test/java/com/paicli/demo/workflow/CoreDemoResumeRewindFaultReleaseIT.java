package com.paicli.demo.workflow;

import com.paicli.demo.approval.CoreDemoApprovalProfile;
import com.paicli.demo.artifact.CoreDemoArtifactScope;
import com.paicli.demo.baseline.CoreDemoBaselineService;
import com.paicli.demo.fixture.*;
import com.paicli.demo.runtime.*;
import com.paicli.demo.scenario.CoreDemoScenarioContractLoader;
import com.paicli.demo.workspace.*;
import com.paicli.state.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CoreDemoResumeRewindFaultReleaseIT {
    @TempDir Path root;

    @Test void currentTreeDriftBlocksResumeWithoutRepeatingSideEffects() throws Exception {
        Prepared p=checkpoint("run-resume-drift1","drift");
        Path mutable=p.workspace.workspace().resolve(
                p.loaded.manifest().allowedMutablePaths().getFirst());
        Files.writeString(mutable,Files.readString(mutable)+"\n// drift\n");
        try(StateStore store=CoreDemoStateStoreFactory.production().open(p.workspace)) {
            CoreDemoRunContext context=context(p,store);
            int tools=store.toolCalls.findByTask(context.taskSpecificationId()).size();
            int verifies=store.verifications.findByRun(
                    store.codingRuns.findByTask(context.taskSpecificationId())
                            .getFirst().runId()).size();
            assertThrows(CoreDemoScenarioDriftException.class,
                    ()->new CoreDemoResumeService().resume(context));
            assertEquals(tools,store.toolCalls.findByTask(context.taskSpecificationId()).size());
            assertEquals(verifies,store.verifications.findByRun(
                    store.codingRuns.findByTask(context.taskSpecificationId())
                            .getFirst().runId()).size());
            assertEquals(CoreDemoRunStore.RESUME_FAILED,
                    store.coreDemoRuns.findById(context.runId()).orElseThrow().resumeStatus());
        } finally { cleanup(p); }
    }

    @Test void payloadDriftAfterFirstRewindProducesPartialAndInitialTree() throws Exception {
        Prepared p=checkpoint("run-rewind-part01","partial");
        try(StateStore store=CoreDemoStateStoreFactory.production().open(p.workspace)) {
            CoreDemoRunContext context=context(p,store);
            CoreDemoResumeResult resumed=new CoreDemoResumeService().resume(context);
            var snapshots=store.snapshots.findByTask(context.taskSpecificationId());
            Path firstPayload=p.workspace.workspace().resolve(".paicli/snapshots")
                    .resolve(snapshots.getFirst().snapshotPath());
            Files.writeString(firstPayload,"tampered");
            assertThrows(Exception.class,
                    ()->new CoreDemoRewindService().rewind(context,resumed));
            assertEquals("94eacb50ef6873e58072009a172e27e03fcc45d18fe00e2b50f306e294f79d8f",
                    CoreDemoTreeHasher.hashWorkspace(p.workspace.workspace(),
                            p.loaded.manifest()).aggregateHash());
            assertEquals(CoreDemoRunStore.REWIND_PARTIAL,
                    store.coreDemoRuns.findById(context.runId()).orElseThrow().rewindStatus());
            assertEquals(FileSnapshotStore.STATUS_RESTORED,
                    store.snapshots.findById(snapshots.get(1).snapshotId()).orElseThrow().status());
            assertEquals(FileSnapshotStore.STATUS_APPLIED,
                    store.snapshots.findById(snapshots.get(0).snapshotId()).orElseThrow().status());
        } finally { cleanup(p); }
    }

    private Prepared checkpoint(String runId,String suffix)throws Exception {
        Path repo=Files.createDirectory(root.resolve("repo-"+suffix));
        CoreDemoWorkspaceService workspaces=new CoreDemoWorkspaceService(
                root.resolve("runs-"+suffix),repo);
        var loader=new CoreDemoFixtureManifestLoader();
        var prepared=new CoreDemoPreparationService(loader,workspaces,
                new CoreDemoBaselineService(Duration.ofSeconds(90)))
                .prepareForRun(runId,CoreDemoPreparationService.PreparationObserver.NONE);
        try(StateStore store=CoreDemoStateStoreFactory.production().open(prepared.workspace())) {
            CoreDemoRunContext context=context(
                    new Prepared(workspaces,prepared.workspace(),prepared.manifest(),
                            prepared.baselineResult()),store);
            store.coreDemoRuns.create(new CoreDemoRunStore.CreateRun(context.runId(),
                    context.scenarioId(),context.fixtureManifest().fixtureVersion(),
                    context.fixtureManifestHash(),context.seedTreeHash(),
                    context.workspaceIdentityHash(),"core-demo-deterministic"));
            store.coreDemoRuns.recordBaselinePassed(context.runId(),3,context.seedTreeHash());
            var primary=new CoreDemoPrimaryWorkflow(new CoreDemoScenarioContractLoader(),
                    new com.paicli.demo.verification.CoreDemoVerificationClassifier(),
                    checkpoint->{throw new com.paicli.demo.checkpoint
                            .CoreDemoCheckpointInterruptedException("controlled");});
            assertThrows(com.paicli.demo.checkpoint.CoreDemoCheckpointInterruptedException.class,
                    ()->primary.execute(context));
        }
        return new Prepared(workspaces,prepared.workspace(),prepared.manifest(),
                prepared.baselineResult());
    }

    private CoreDemoRunContext context(Prepared p,StateStore store)throws Exception {
        return new CoreDemoRunContext(p.workspace.runId(),p.loaded.manifest().scenarioId(),
                p.loaded.manifestHash(),p.loaded.manifest().expectedBaselineTreeHash(),
                p.workspace,p.workspace.workspaceIdentityHash(),store,
                CoreDemoRunContext.TASK_SPECIFICATION_ID,
                CoreDemoApprovalProfile.derive(p.workspace,p.loaded.manifest()),
                CoreDemoBudgets.fixed(),p.baseline,p.loaded.manifest(),
                new CoreDemoArtifactScope(p.workspace.runId(),p.loaded.manifestHash()),true);
    }
    private void cleanup(Prepared p)throws Exception {
        if(Files.exists(p.workspace.runRoot()))p.workspaces.cleanup(p.workspace);
    }
    private record Prepared(CoreDemoWorkspaceService workspaces,
            CoreDemoWorkspace workspace,CoreDemoFixtureManifestLoader.LoadedManifest loaded,
            com.paicli.demo.baseline.CoreDemoBaselineResult baseline){}
}
