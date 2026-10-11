package com.paicli.demo.artifact;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.demo.baseline.CoreDemoBaselineResult;
import com.paicli.demo.fixture.*;
import com.paicli.demo.runtime.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class CoreDemoArtifactWriterTest {
    @TempDir Path root;
    private final ObjectMapper json=new ObjectMapper();

    @Test void completeBundleUsesVerifiedCommitMarkerAndCannotBeOverwritten() throws Exception {
        Path artifacts=root.resolve("artifacts");Files.createDirectory(artifacts);
        CoreDemoArtifactInput input=input("run-00000031");
        var writer=new CoreDemoArtifactWriter();
        var commit=writer.write(artifacts,input);
        Path fin=artifacts.resolve("final");
        assertTrue(writer.isCommitted(fin));assertEquals(4,commit.files().size());
        assertTrue(Files.isRegularFile(fin.resolve("artifact-manifest.json")));
        for(String name:List.of("summary.json","report.md","hashes.json","verification/summary.json")){
            assertTrue(Files.isRegularFile(fin.resolve(name)));
            assertFalse(Files.readString(fin.resolve(name)).contains("\r"));
        }
        Map<?,?> verification=json.readValue(Files.readAllBytes(
                fin.resolve("verification/summary.json")),Map.class);
        assertEquals("NOT_RUN",((Map<?,?>)verification.get("postResumeVerification")).get("status"));
        assertThrows(FileAlreadyExistsException.class,()->writer.write(artifacts,input));
        Files.writeString(fin.resolve("summary.json"),"tampered",StandardCharsets.UTF_8);
        assertFalse(writer.isCommitted(fin));
    }

    @Test void stagingFailureNeverCreatesCommittedFinal() throws Exception {
        Path artifacts=root.resolve("artifacts-fail");Files.createDirectory(artifacts);
        var writer=new CoreDemoArtifactWriter(point->{
            if(point==CoreDemoArtifactWriter.FailurePoint.REPORT_WRITTEN)
                throw new IOException("injected report failure");
        },true);
        assertThrows(IOException.class,()->writer.write(artifacts,input("run-00000032")));
        assertFalse(Files.exists(artifacts.resolve("final")));
        try(var paths=Files.list(artifacts)){
            Path staging=paths.filter(p->p.getFileName().toString().startsWith("staging-"))
                    .findFirst().orElseThrow();
            assertFalse(Files.exists(staging.resolve("artifact-manifest.json")));
        }
    }

    @Test void writeAndMoveFailureWindowsNeverCommitPartialFinal() throws Exception {
        for(var point:List.of(CoreDemoArtifactWriter.FailurePoint.SUMMARY_WRITTEN,
                CoreDemoArtifactWriter.FailurePoint.REPORT_WRITTEN,
                CoreDemoArtifactWriter.FailurePoint.MANIFEST_WRITTEN,
                CoreDemoArtifactWriter.FailurePoint.BEFORE_MOVE)){
            Path artifacts=root.resolve("fault-"+point);Files.createDirectory(artifacts);
            var writer=new CoreDemoArtifactWriter(current->{
                if(current==point)throw new IOException("injected "+point);
            },true);
            assertThrows(IOException.class,()->writer.write(artifacts,input("run-00000037")));
            assertFalse(Files.exists(artifacts.resolve("final")));
        }
    }

    @Test void rawBaselineExcerptAndAbsolutePathNeverEnterBundle() throws Exception {
        var base=input("run-00000036");
        var secretBaseline=new CoreDemoBaselineResult(base.baseline().status(),0,1,3,0,0,0,
                "a".repeat(64),"b".repeat(64),
                "token=unique-sentinel C:\\Users\\private\\file",false,true,true,
                base.baseline().sourceTreeHash());
        var safe=new CoreDemoArtifactInput(base.runId(),base.fixture(),base.fixtureManifestHash(),
                secretBaseline,base.workflow(),base.exitCode(),base.terminalStatus(),base.cleanupStatus());
        Path artifacts=root.resolve("safe");Files.createDirectory(artifacts);
        new CoreDemoArtifactWriter().write(artifacts,safe);
        try(var paths=Files.walk(artifacts.resolve("final"))){
            for(Path file:paths.filter(Files::isRegularFile).toList()){
                String content=Files.readString(file);
                assertFalse(content.contains("unique-sentinel"));
                assertFalse(content.contains("C:\\Users\\private"));
            }
        }
    }

    @Test void concurrentCommitHasSingleOwnerAndNeverOverwrites() throws Exception {
        Path artifacts=root.resolve("concurrent");Files.createDirectory(artifacts);
        var input=input("run-00000035");
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var start=new CountDownLatch(1);
            Callable<Boolean> call=()->{start.await();try{
                new CoreDemoArtifactWriter().write(artifacts,input);return true;
            }catch(IOException e){return false;}};
            Future<Boolean> a=executor.submit(call),b=executor.submit(call);start.countDown();
            int successes=(a.get()?1:0)+(b.get()?1:0);
            assertEquals(1,successes);
        }
        assertTrue(new CoreDemoArtifactWriter().isCommitted(artifacts.resolve("final")));
    }

    @Test void fallbackMoveCommitsAndSemanticHashIgnoresRunIdentity() throws Exception {
        var a=input("run-00000033");var b=input("run-00000034");
        assertEquals(new CoreDemoSemanticHasher().hash(a),new CoreDemoSemanticHasher().hash(b));
        Path artifacts=root.resolve("fallback");Files.createDirectory(artifacts);
        var commit=new CoreDemoArtifactWriter(point->{},false).write(artifacts,a);
        assertFalse(commit.atomicMoveUsed());
        assertTrue(new CoreDemoArtifactWriter().isCommitted(artifacts.resolve("final")));
        var changed=new CoreDemoArtifactInput(b.runId(),b.fixture(),b.fixtureManifestHash(),
                b.baseline(),new CoreDemoWorkflowResult(
                    b.workflow().runId(),b.workflow().scenarioId(),b.workflow().workspaceIdentityHash(),
                    CoreDemoWorkflowResult.TerminalCategory.VERIFICATION_REPAIR_FAILED,
                    CoreDemoWorkflowResult.Phase.VERIFY,
                    b.workflow().planning(),b.workflow().codeIntelligence(),1,1,
                    b.workflow().execution(),b.workflow().changedRelativeFiles(),1,
                    List.of(new CoreDemoWorkflowResult.VerificationFact("FIRST_VERIFICATION",
                            CoreDemoWorkflowResult.FactStatus.FAILED,"TEST_FAILED")),0,
                    CoreDemoWorkflowResult.FactStatus.NOT_RUN,
                    CoreDemoWorkflowResult.FactStatus.NOT_RUN,null,null,null,
                    "VERIFY_FAILED","bounded",
                    CoreDemoWorkflowResult.CleanupRecommendation.RETAIN_FOR_DIAGNOSTICS),
                CoreDemoExitCode.VERIFICATION_FAILED,"VERIFICATION_REPAIR_FAILED","RETAINED");
        assertNotEquals(new CoreDemoSemanticHasher().hash(a),
                new CoreDemoSemanticHasher().hash(changed));
    }

    private static CoreDemoArtifactInput input(String runId)throws Exception{
        var loaded=new CoreDemoFixtureManifestLoader().load();
        String sha=loaded.manifest().expectedBaselineTreeHash();
        var baseline=new CoreDemoBaselineResult(CoreDemoBaselineResult.Status.BASELINE_PASSED,
                0,10,3,0,0,0,"b".repeat(64),"c".repeat(64),null,
                false,true,true,sha);
        var workflow=new CoreDemoWorkflowResult(runId,loaded.manifest().scenarioId(),
                "d".repeat(64),CoreDemoWorkflowResult.TerminalCategory.COMPLETED,
                CoreDemoWorkflowResult.Phase.VERIFY,
                CoreDemoWorkflowResult.FactStatus.PASSED,
                CoreDemoWorkflowResult.FactStatus.NOT_RUN,0,0,
                CoreDemoWorkflowResult.FactStatus.NOT_RUN,List.of(),0,List.of(),0,
                CoreDemoWorkflowResult.FactStatus.NOT_RUN,
                CoreDemoWorkflowResult.FactStatus.NOT_RUN,null,sha,null,null,null,
                CoreDemoWorkflowResult.CleanupRecommendation.CLEAN_RUNTIME);
        return new CoreDemoArtifactInput(runId,loaded.manifest(),loaded.manifestHash(),
                baseline,workflow,CoreDemoExitCode.COMPLETED,"COMPLETED","PENDING_AFTER_COMMIT");
    }
}
