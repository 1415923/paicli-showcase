package com.paicli.demo.runtime;

import com.paicli.demo.artifact.*;
import com.paicli.demo.baseline.CoreDemoBaselineResult;
import com.paicli.demo.workspace.*;
import com.paicli.state.StateStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class HeadlessCoreDemoRunnerTest {
    @TempDir Path root;

    @Test void baselineWorkflowArtifactAndCleanupFlowIsDurable() throws Exception {
        Setup setup=setup(CoreDemoBaselineResult.Status.BASELINE_PASSED);
        AtomicInteger calls=new AtomicInteger();
        CoreDemoWorkflow workflow=context->{calls.incrementAndGet();
            assertTrue(context.headless());assertEquals(3,context.baselineEvidence().tests());
            assertTrue(Files.exists(context.workspace().state().resolve("paicli.db")));
            assertFalse(Files.exists(context.workspace().workspace().resolve(".paicli")));
            return success(context);};
        var runner=runner(setup,workflow,new CoreDemoArtifactWriter(),
                CoreDemoStateStoreFactory.production(),"run-00000041");
        var result=runner.run(CoreDemoRunRequest.fixedHeadless());
        assertEquals(CoreDemoExitCode.COMPLETED,result.exitCode());assertEquals(1,calls.get());
        assertFalse(Files.exists(setup.stub.lastWorkspace.workspace()));
        assertTrue(Files.exists(setup.stub.lastWorkspace.state().resolve("paicli.db")));
        assertTrue(Files.exists(setup.stub.lastWorkspace.artifacts().resolve("final/artifact-manifest.json")));
        try(StateStore state=CoreDemoStateStoreFactory.production().open(setup.stub.lastWorkspace)){
            assertEquals("CLEANED",state.coreDemoRuns.findById("run-00000041").orElseThrow().status());
        }
    }

    @Test void baselineFailureDoesNotCallWorkflowAndReturnsThree() throws Exception {
        Setup setup=setup(CoreDemoBaselineResult.Status.DEMO_BASELINE_INVALID);
        AtomicInteger calls=new AtomicInteger();
        var runner=runner(setup,c->{calls.incrementAndGet();return success(c);},
                new CoreDemoArtifactWriter(),CoreDemoStateStoreFactory.production(),"run-00000042");
        var result=runner.run(CoreDemoRunRequest.fixedHeadless());
        assertEquals(CoreDemoExitCode.BASELINE_INVALID,result.exitCode());assertEquals(0,calls.get());
        assertTrue(Files.exists(setup.stub.lastWorkspace.workspace()));
        assertTrue(Files.exists(setup.stub.lastWorkspace.artifacts().resolve("final")));
        try(StateStore state=CoreDemoStateStoreFactory.production().open(setup.stub.lastWorkspace)){
            assertEquals("RETAINED_FOR_DIAGNOSTICS",
                    state.coreDemoRuns.findById("run-00000042").orElseThrow().status());
        }
    }

    @Test void identityMismatchAndWorkflowExceptionFailClosed() throws Exception {
        Setup setup=setup(CoreDemoBaselineResult.Status.BASELINE_PASSED);
        var runner=runner(setup,c->new CoreDemoWorkflowResult(
                "run-wrong00",c.scenarioId(),c.workspaceIdentityHash(),
                CoreDemoWorkflowResult.TerminalCategory.COMPLETED,
                CoreDemoWorkflowResult.Phase.VERIFY,
                CoreDemoWorkflowResult.FactStatus.PASSED,
                CoreDemoWorkflowResult.FactStatus.PASSED,1,1,
                CoreDemoWorkflowResult.FactStatus.PASSED,List.of(),1,List.of(),1,
                CoreDemoWorkflowResult.FactStatus.NOT_RUN,
                CoreDemoWorkflowResult.FactStatus.NOT_RUN,null,c.seedTreeHash(),null,
                null,null,CoreDemoWorkflowResult.CleanupRecommendation.CLEAN_RUNTIME),
                new CoreDemoArtifactWriter(),CoreDemoStateStoreFactory.production(),"run-00000043");
        var result=runner.run(CoreDemoRunRequest.fixedHeadless());
        assertEquals(CoreDemoExitCode.INTERNAL_ERROR,result.exitCode());
        assertTrue(result.diagnosticsRetained());
        assertTrue(Files.exists(setup.stub.lastWorkspace.workspace()));
    }

    @Test void stateCreationFailurePreventsExtractionAndWorkflow() throws Exception {
        Setup setup=setup(CoreDemoBaselineResult.Status.BASELINE_PASSED);
        AtomicInteger calls=new AtomicInteger();
        CoreDemoStateStoreFactory failing=workspace->{throw new IOException("state unavailable");};
        var runner=runner(setup,c->{calls.incrementAndGet();return success(c);},
                new CoreDemoArtifactWriter(),failing,"run-00000044");
        var result=runner.run(CoreDemoRunRequest.fixedHeadless());
        assertEquals(CoreDemoExitCode.INTERNAL_ERROR,result.exitCode());assertEquals(0,calls.get());
        assertNotNull(setup.stub.lastWorkspace);
        assertFalse(Files.exists(setup.stub.lastWorkspace.workspace()));
        assertTrue(result.diagnosticsRetained());
    }

    @Test void artifactFailureOverridesWorkflowSuccessAndRetainsWorkspace() throws Exception {
        Setup setup=setup(CoreDemoBaselineResult.Status.BASELINE_PASSED);
        CoreDemoArtifactSink failing=(path,input)->{throw new IOException("summary write failed");};
        var result=runner(setup,HeadlessCoreDemoRunnerTest::success,failing,
                CoreDemoStateStoreFactory.production(),"run-00000045")
                .run(CoreDemoRunRequest.fixedHeadless());
        assertEquals(CoreDemoExitCode.ARTIFACT_FAILED,result.exitCode());
        assertTrue(Files.exists(setup.stub.lastWorkspace.workspace()));
        assertFalse(Files.exists(setup.stub.lastWorkspace.artifacts().resolve("final")));
        try(StateStore state=CoreDemoStateStoreFactory.production().open(setup.stub.lastWorkspace)){
            var row=state.coreDemoRuns.findById("run-00000045").orElseThrow();
            assertEquals("RETAINED_FOR_DIAGNOSTICS",row.status());
            assertEquals("FAILED",row.artifactStatus());
        }
    }

    @Test void cleanupOwnershipFailureOverridesSuccessAndPreservesCommittedBundle() throws Exception {
        Setup setup=setup(CoreDemoBaselineResult.Status.BASELINE_PASSED);
        CoreDemoWorkflow tampering=context->{
            Files.writeString(context.workspace().runRoot().resolve("ownership.json"),"{}");
            return success(context);
        };
        var result=runner(setup,tampering,new CoreDemoArtifactWriter(),
                CoreDemoStateStoreFactory.production(),"run-00000048")
                .run(CoreDemoRunRequest.fixedHeadless());
        assertEquals(CoreDemoExitCode.INTERNAL_ERROR,result.exitCode());
        assertEquals("CLEANUP_PENDING",result.status());
        assertTrue(result.diagnosticsRetained());
        assertTrue(Files.exists(setup.stub.lastWorkspace.artifacts().resolve("final")));
        assertTrue(Files.exists(setup.stub.lastWorkspace.workspace()));
    }

    @Test void workflowCloseFailureCannotBeReportedAsSuccess() throws Exception {
        Setup setup=setup(CoreDemoBaselineResult.Status.BASELINE_PASSED);
        CoreDemoWorkflow workflow=new CoreDemoWorkflow(){
            @Override public CoreDemoWorkflowResult execute(CoreDemoRunContext context){
                return success(context);
            }
            @Override public void close() throws Exception {
                throw new IOException("workflow close failed");
            }
        };
        var result=runner(setup,workflow,new CoreDemoArtifactWriter(),
                CoreDemoStateStoreFactory.production(),"run-00000049")
                .run(CoreDemoRunRequest.fixedHeadless());
        assertEquals(CoreDemoExitCode.INTERNAL_ERROR,result.exitCode());
        assertTrue(result.diagnosticsRetained());
        assertTrue(Files.exists(setup.stub.lastWorkspace.workspace()));
        assertTrue(Files.exists(setup.stub.lastWorkspace.artifacts()
                .resolve("final/artifact-manifest.json")));
    }
    @Test void workflowTerminalCategoriesMapToStableExitCodes() throws Exception {
        var cases=List.of(
                new Object[]{CoreDemoWorkflowResult.TerminalCategory.PLAN_CODE_INTELLIGENCE_EXECUTION_FAILED,CoreDemoExitCode.EXECUTION_FAILED},
                new Object[]{CoreDemoWorkflowResult.TerminalCategory.VERIFICATION_REPAIR_FAILED,CoreDemoExitCode.VERIFICATION_FAILED},
                new Object[]{CoreDemoWorkflowResult.TerminalCategory.RESUME_REWIND_FAILED,CoreDemoExitCode.RESUME_REWIND_FAILED});
        int index=50;
        for(Object[] item:cases){
            Setup setup=setup(CoreDemoBaselineResult.Status.BASELINE_PASSED);
            var category=(CoreDemoWorkflowResult.TerminalCategory)item[0];
            var expected=(CoreDemoExitCode)item[1];
            String runId="run-000000"+index++;
            var result=runner(setup,c->terminal(c,category),new CoreDemoArtifactWriter(),
                    CoreDemoStateStoreFactory.production(),runId)
                    .run(CoreDemoRunRequest.fixedHeadless());
            assertEquals(expected,result.exitCode());
            assertTrue(result.diagnosticsRetained());
            assertTrue(Files.exists(setup.stub.lastWorkspace.workspace()));
        }
    }

    @Test void committedBundleAndFailedTerminalPersistenceLeavesRecoverableResidue() throws Exception {
        Setup setup=setup(CoreDemoBaselineResult.Status.BASELINE_PASSED);
        var runner=new HeadlessCoreDemoRunner(setup.stub,setup.workspaces,
                HeadlessCoreDemoRunnerTest::success,new CoreDemoArtifactWriter(),
                CoreDemoStateStoreFactory.production(),()->"run-00000047",
                ()->{throw new IOException("injected DB failure");});
        var result=runner.run(CoreDemoRunRequest.fixedHeadless());
        assertEquals(CoreDemoExitCode.INTERNAL_ERROR,result.exitCode());
        assertEquals("ARTIFACTS_COMMITTED_METADATA_RESIDUE",result.status());
        assertTrue(Files.exists(setup.stub.lastWorkspace.artifacts()
                .resolve("final/artifact-manifest.json")));
        assertTrue(Files.exists(setup.stub.lastWorkspace.workspace()));
        try(StateStore state=CoreDemoStateStoreFactory.production().open(setup.stub.lastWorkspace)){
            var row=state.coreDemoRuns.findById("run-00000047").orElseThrow();
            assertEquals("ARTIFACTS_WRITING",row.status());
            assertEquals("STAGING",row.artifactStatus());
        }
    }

    @Test void duplicateRuntimeOwnedRunDoesNotOverwriteFirstArtifacts() throws Exception {
        Setup setup=setup(CoreDemoBaselineResult.Status.BASELINE_PASSED);
        var runner=runner(setup,HeadlessCoreDemoRunnerTest::success,
                new CoreDemoArtifactWriter(),CoreDemoStateStoreFactory.production(),"run-00000046");
        assertEquals(0,runner.run(CoreDemoRunRequest.fixedHeadless()).exitCode().code());
        var second=runner.run(CoreDemoRunRequest.fixedHeadless());
        assertEquals(CoreDemoExitCode.INTERNAL_ERROR,second.exitCode());
        assertTrue(Files.exists(setup.stub.lastWorkspace.artifacts().resolve("final")));
    }

    private Setup setup(CoreDemoBaselineResult.Status status)throws Exception{
        Path repo=root.resolve("repo-"+UUID.randomUUID());Files.createDirectory(repo);
        var workspaces=new CoreDemoWorkspaceService(root.resolve("runs-"+UUID.randomUUID()),repo);
        return new Setup(workspaces,new CoreDemoPreparationStub(workspaces,status));
    }
    private static HeadlessCoreDemoRunner runner(Setup s,CoreDemoWorkflow workflow,
            CoreDemoArtifactSink sink,CoreDemoStateStoreFactory stores,String runId){
        return new HeadlessCoreDemoRunner(s.stub,s.workspaces,workflow,sink,stores,()->runId,()->{});
    }
    private static CoreDemoWorkflowResult success(CoreDemoRunContext c){
        return new CoreDemoWorkflowResult(c.runId(),c.scenarioId(),c.workspaceIdentityHash(),
                CoreDemoWorkflowResult.TerminalCategory.COMPLETED,
                CoreDemoWorkflowResult.Phase.VERIFY,
                CoreDemoWorkflowResult.FactStatus.PASSED,
                CoreDemoWorkflowResult.FactStatus.NOT_RUN,0,0,
                CoreDemoWorkflowResult.FactStatus.NOT_RUN,List.of(),0,List.of(),0,
                CoreDemoWorkflowResult.FactStatus.NOT_RUN,
                CoreDemoWorkflowResult.FactStatus.NOT_RUN,null,c.seedTreeHash(),null,
                null,null,CoreDemoWorkflowResult.CleanupRecommendation.CLEAN_RUNTIME);
    }
    private static CoreDemoWorkflowResult terminal(CoreDemoRunContext c,
            CoreDemoWorkflowResult.TerminalCategory category){
        CoreDemoWorkflowResult.Phase phase=category==
                CoreDemoWorkflowResult.TerminalCategory.RESUME_REWIND_FAILED
                ?CoreDemoWorkflowResult.Phase.RESUME:CoreDemoWorkflowResult.Phase.VERIFY;
        return new CoreDemoWorkflowResult(c.runId(),c.scenarioId(),c.workspaceIdentityHash(),
                category,phase,CoreDemoWorkflowResult.FactStatus.PASSED,
                CoreDemoWorkflowResult.FactStatus.NOT_RUN,0,0,
                CoreDemoWorkflowResult.FactStatus.FAILED,List.of(),0,List.of(),0,
                CoreDemoWorkflowResult.FactStatus.NOT_RUN,
                CoreDemoWorkflowResult.FactStatus.NOT_RUN,null,null,null,
                category.name(),"bounded failure",
                CoreDemoWorkflowResult.CleanupRecommendation.RETAIN_FOR_DIAGNOSTICS);
    }
    private record Setup(CoreDemoWorkspaceService workspaces,CoreDemoPreparationStub stub){}
}
