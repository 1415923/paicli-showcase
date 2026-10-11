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
import java.io.IOException;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CoreDemoPrimaryWorkflowReleaseIT {
    @TempDir Path root;

    @Test
    void productionPrimaryWorkflowIsRealAndDeterministicAcrossTwoRuns() throws Exception {
        RunFacts first=run("run-primary-0001");
        RunFacts second=run("run-primary-0002");

        assertEquals(first.semantic(),second.semantic());
        assertEquals("94eacb50ef6873e58072009a172e27e03fcc45d18fe00e2b50f306e294f79d8f",
                first.initialHash());
        assertEquals("49400b5195a412415e2ad9f28f1ba562759d5c48bd1ebd9f6e19ad9d43605a47",
                first.finalHash());
        assertEquals(List.of("list_files","grep","read_file","write_file","read_file","write_file"),
                first.tools());
        assertEquals(2,first.snapshots().size());
        assertEquals(2,first.verifications().size());
        assertEquals("FAILED",first.verifications().get(0));
        assertEquals("PASSED",first.verifications().get(1));
    }

    @Test
    void checkpointHookInterruptsBeforeRepairAndLeavesInitialTree() throws Exception {
        Path repo=Files.createDirectory(root.resolve("repo-fault"));
        CoreDemoWorkspaceService workspaces=new CoreDemoWorkspaceService(
                root.resolve("runs-fault"),repo);
        var preparation=prepare(workspaces,"run-primary-fault1");
        try(StateStore store=CoreDemoStateStoreFactory.production().open(preparation.workspace())){
            CoreDemoRunContext context=context(preparation,store);
            seedRun(store,context);
            var workflow=new CoreDemoPrimaryWorkflow(new CoreDemoScenarioContractLoader(),
                    new com.paicli.demo.verification.CoreDemoVerificationClassifier(),
                    checkpoint->{throw new com.paicli.demo.checkpoint.CoreDemoCheckpointInterruptedException("test checkpoint");});
            assertThrows(com.paicli.demo.checkpoint.CoreDemoCheckpointInterruptedException.class,()->workflow.execute(context));
            assertEquals("94eacb50ef6873e58072009a172e27e03fcc45d18fe00e2b50f306e294f79d8f",
                    CoreDemoTreeHasher.hashWorkspace(preparation.workspace().workspace(),
                            preparation.manifest().manifest()).aggregateHash());
            assertEquals(1,store.snapshots.findByTask(
                    CoreDemoRunContext.TASK_SPECIFICATION_ID).size());
            assertTrue(store.coreDemoCheckpoints.findByRun(context.runId()).isPresent());
            assertTrue(store.repairAttempts.findByRun(
                    store.codingRuns.findByTask(context.taskSpecificationId()).getFirst().runId())
                    .isEmpty());
        } finally {
            cleanup(workspaces,preparation.workspace());
        }
    }

    private RunFacts run(String runId)throws Exception{
        Path repo=Files.createDirectory(root.resolve("repo-"+runId));
        CoreDemoWorkspaceService workspaces=new CoreDemoWorkspaceService(
                root.resolve("runs-"+runId),repo);
        var preparation=prepare(workspaces,runId);
        try(StateStore store=CoreDemoStateStoreFactory.production().open(preparation.workspace())){
            CoreDemoRunContext context=context(preparation,store);seedRun(store,context);
            CoreDemoWorkflowResult result=new CoreDemoPrimaryWorkflow().execute(context);
            assertEquals(CoreDemoWorkflowResult.TerminalCategory.PRIMARY_WORKFLOW_SUCCEEDED,
                    result.terminalCategory());
            assertFalse(result.successful(),"primary workflow is not the full Core Demo");
            assertEquals(CoreDemoWorkflowResult.FactStatus.NOT_RUN,result.resume());
            assertEquals(CoreDemoWorkflowResult.FactStatus.NOT_RUN,result.rewind());
            var checkpoint=store.coreDemoCheckpoints.findByRun(runId).orElseThrow();
            var snapshotRows=store.snapshots.findByTask(context.taskSpecificationId());
            assertTrue(snapshotRows.stream().allMatch(s->FileSnapshotStore.STATUS_APPLIED.equals(s.status())));
            assertEquals(preparation.manifest().manifest().files().stream()
                    .filter(f->f.relativePath().equals(preparation.manifest().manifest()
                            .allowedMutablePaths().getFirst())).findFirst().orElseThrow().sha256(),
                    snapshotRows.getFirst().sha256Before());
            String initialFileHash=com.paicli.demo.scenario.CoreDemoContractHash.sha256(
                    com.paicli.demo.provider.CoreDemoDeterministicProvider.INITIAL_SOURCE);
            String finalFileHash=com.paicli.demo.scenario.CoreDemoContractHash.sha256(
                    com.paicli.demo.provider.CoreDemoDeterministicProvider.FINAL_SOURCE);
            assertEquals(initialFileHash,snapshotRows.getFirst().sha256After());
            assertEquals(initialFileHash,snapshotRows.get(1).sha256Before());
            assertEquals(finalFileHash,snapshotRows.get(1).sha256After());
            assertTrue(store.codeSearchAudits.findRecentAudits(32).stream()
                    .filter(a->CodeSearchAuditStore.STATUS_SUCCEEDED.equals(a.status())
                            &&a.selectedContextHash()!=null).count()>=1);

            List<String> tools=store.toolCalls.findByTask(context.taskSpecificationId()).stream()
                    .map(ToolCallStore.ToolCallRecord::toolName).toList();
            assertTrue(store.toolCalls.findByTask(context.taskSpecificationId()).stream()
                    .filter(call->call.toolName().equals("write_file"))
                    .allMatch(call->!call.argumentsJson().contains(
                            "line.rawTotal().multiply(MEMBER_RATE)")
                            &&call.argumentsJson().contains("sha256=")));
            List<String> verification=store.verifications.findByRun(
                    store.codingRuns.findByTask(context.taskSpecificationId()).getFirst().runId())
                    .stream().map(VerificationStore.VerificationRecord::status).toList();
            String semantic=String.join("|",checkpoint.planHash(),checkpoint.codeIntelligenceHash(),
                    checkpoint.semanticHash(),result.initialExecutionTreeHash(),result.finalTreeHash(),
                    String.join(",",tools),String.join(",",verification),Integer.toString(result.repairCount()));
            assertEquals("WORKFLOW_SUCCEEDED",store.coreDemoRuns.findById(runId).orElseThrow().status());
            assertEquals("DONE",store.tasks.findById(context.taskSpecificationId()).orElseThrow().status());
            assertEquals(1,store.repairAttempts.findByRun(
                    store.codingRuns.findByTask(context.taskSpecificationId()).getFirst().runId()).size());
            return new RunFacts(semantic,result.initialExecutionTreeHash(),result.finalTreeHash(),
                    tools,snapshotRows,verification);
        }finally{cleanup(workspaces,preparation.workspace());}
    }

    private CoreDemoPreparationService.CoreDemoPreparation prepare(
            CoreDemoWorkspaceService workspaces,String runId)throws Exception{
        var loader=new CoreDemoFixtureManifestLoader();
        return new CoreDemoPreparationService(loader,workspaces,
                new CoreDemoBaselineService(Duration.ofSeconds(90)))
                .prepareForRun(runId,CoreDemoPreparationService.PreparationObserver.NONE);
    }
    private CoreDemoRunContext context(CoreDemoPreparationService.CoreDemoPreparation p,
            StateStore store)throws Exception{
        var profile=CoreDemoApprovalProfile.derive(p.workspace(),p.manifest().manifest());
        return new CoreDemoRunContext(p.workspace().runId(),p.manifest().manifest().scenarioId(),
                p.manifest().manifestHash(),p.manifest().manifest().expectedBaselineTreeHash(),
                p.workspace(),p.workspace().workspaceIdentityHash(),store,
                CoreDemoRunContext.TASK_SPECIFICATION_ID,profile,CoreDemoBudgets.fixed(),
                p.baselineResult(),p.manifest().manifest(),
                new CoreDemoArtifactScope(p.workspace().runId(),p.manifest().manifestHash()),true);
    }
    private void seedRun(StateStore store,CoreDemoRunContext c)throws Exception{
        store.coreDemoRuns.create(new CoreDemoRunStore.CreateRun(c.runId(),c.scenarioId(),
                c.fixtureManifest().fixtureVersion(),c.fixtureManifestHash(),c.seedTreeHash(),
                c.workspaceIdentityHash(),"core-demo-deterministic"));
        store.coreDemoRuns.recordBaselinePassed(c.runId(),3,c.seedTreeHash());
    }
    private static void cleanup(CoreDemoWorkspaceService service,CoreDemoWorkspace workspace)
            throws Exception {
        try {
            service.cleanup(workspace);
        } catch (IOException productionCleanupFailure) {
            // Test teardown is intentionally separate from production ownership cleanup semantics.
            if (Files.exists(workspace.runRoot())) {
                try (var paths=Files.walk(workspace.runRoot())) {
                    paths.sorted(Comparator.reverseOrder()).forEach(path->{
                        try { Files.deleteIfExists(path); }
                        catch (IOException error) { throw new java.io.UncheckedIOException(error); }
                    });
                }
            }
        }
    }
    private record RunFacts(String semantic,String initialHash,String finalHash,List<String> tools,
            List<FileSnapshotStore.FileSnapshotRecord> snapshots,List<String> verifications){}
    private static final class InterruptedAtCheckpoint extends RuntimeException{}
}