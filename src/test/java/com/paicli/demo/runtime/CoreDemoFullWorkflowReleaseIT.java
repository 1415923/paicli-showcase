package com.paicli.demo.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.demo.artifact.CoreDemoArtifactWriter;
import com.paicli.demo.baseline.CoreDemoBaselineService;
import com.paicli.demo.fixture.CoreDemoFixtureManifestLoader;
import com.paicli.demo.workflow.CoreDemoFullWorkflow;
import com.paicli.demo.workspace.*;
import com.paicli.state.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CoreDemoFullWorkflowReleaseIT {
    @TempDir Path root;

    @Test void fullHeadlessWorkflowResumesRewindsCommitsArtifactsAndIsDeterministic()
            throws Exception {
        Facts first=run("run-full-00000001","a");
        Facts second=run("run-full-00000002","b");
        assertEquals("a6f19c2e4ce5d0c9ad3a9fae2d1ef08c6e3cc93ea0a1922fb4c402fdb2d98b35", first.semanticHash());
        assertEquals(first.semanticHash(),second.semanticHash(),
                first.summary()+" != "+second.summary());
        assertEquals(0,first.exitCode());
        assertEquals("e492c0573b916f2067e7c7f7e58e1cc5ff781c9e37226e17bb12584d6da477a7",
                first.rewindHash());
        assertEquals(List.of("list_files","grep","read_file","write_file",
                "read_file","write_file"),first.tools());
        assertEquals(List.of("RESTORED","RESTORED"),first.snapshotStatuses());
        assertEquals(List.of("FAILED","PASSED"),first.verificationStatuses());
    }

    private Facts run(String runId,String suffix)throws Exception {
        Path repo=Files.createDirectory(root.resolve("repo-"+suffix));
        CoreDemoWorkspaceService workspaces=new CoreDemoWorkspaceService(
                root.resolve("runs-"+suffix),repo);
        CoreDemoPreparationService preparation=new CoreDemoPreparationService(
                new CoreDemoFixtureManifestLoader(),workspaces,
                new CoreDemoBaselineService(Duration.ofSeconds(90)));
        final CoreDemoWorkspace[] owned=new CoreDemoWorkspace[1];
        CoreDemoPreparationGateway gateway=(id,observer)->{
            var p=preparation.prepareForRun(id,observer);owned[0]=p.workspace();return p;};
        HeadlessCoreDemoRunner runner=new HeadlessCoreDemoRunner(gateway,workspaces,
                new CoreDemoFullWorkflow(),new CoreDemoArtifactWriter(),
                CoreDemoStateStoreFactory.production(),()->runId,()->{});
        CoreDemoRunResult result=runner.run(CoreDemoRunRequest.fixedHeadless());
        assertEquals(CoreDemoExitCode.COMPLETED,result.exitCode(),
                result.errorCategory()+": "+result.boundedErrorExcerpt());
        assertNotNull(owned[0]);
        assertFalse(Files.exists(owned[0].workspace()));
        Path finalArtifacts=owned[0].artifacts().resolve("final");
        assertTrue(Files.isRegularFile(finalArtifacts.resolve("artifact-manifest.json")));
        Map<?,?> manifest=new ObjectMapper().readValue(
                Files.readAllBytes(finalArtifacts.resolve("artifact-manifest.json")),Map.class);
        Map<?,?> summary=new ObjectMapper().readValue(
                Files.readAllBytes(finalArtifacts.resolve("summary.json")),Map.class);
        Map<?,?> hashes=new ObjectMapper().readValue(
                Files.readAllBytes(finalArtifacts.resolve("hashes.json")),Map.class);
        Map<?,?> verification=new ObjectMapper().readValue(
                Files.readAllBytes(finalArtifacts.resolve("verification/summary.json")),Map.class);
        String report=Files.readString(finalArtifacts.resolve("report.md"));
        assertEquals("COMPLETED",summary.get("status"));
        assertEquals("PASSED",((Map<?,?>)summary.get("baseline")).get("status"));
        assertEquals(0,summary.get("exitCode"));
        assertEquals("PASSED",summary.get("planning"));
        assertEquals("USED",((Map<?,?>)summary.get("codeIntelligence")).get("status"));
        assertEquals("COMPLETED",((Map<?,?>)summary.get("initialExecution")).get("status"));
        assertEquals("EXPECTED_FAILED",summary.get("firstVerification"));
        assertEquals("CREATED",summary.get("checkpoint"));
        assertEquals(true,summary.get("interrupted"));
        assertEquals(true,summary.get("resumed"));
        assertEquals(true,summary.get("duplicateSideEffectsPrevented"));
        assertEquals(1,summary.get("repairCount"));
        assertEquals("PASSED",summary.get("finalVerification"));
        assertEquals(true,summary.get("rewound"));
        assertEquals("PASSED",summary.get("postRewindBaseline"));
        assertEquals("PASSED",((Map<?,?>)verification.get("baseline")).get("status"));
        assertEquals("EXPECTED_FAILED",((Map<?,?>)verification.get("firstVerification")).get("status"));
        assertEquals("PASSED",((Map<?,?>)verification.get("repairVerification")).get("status"));
        assertEquals("PASSED",((Map<?,?>)verification.get("postRewindBaseline")).get("status"));
        assertEquals("e492c0573b916f2067e7c7f7e58e1cc5ff781c9e37226e17bb12584d6da477a7", hashes.get("baselineTreeHash"));
        assertEquals("94eacb50ef6873e58072009a172e27e03fcc45d18fe00e2b50f306e294f79d8f", hashes.get("initialExecutionTreeHash"));
        assertEquals("49400b5195a412415e2ad9f28f1ba562759d5c48bd1ebd9f6e19ad9d43605a47", hashes.get("finalTreeHash"));
        assertEquals(hashes.get("expectedSeedTreeHash"),hashes.get("rewindTreeHash"));
        assertTrue(report.contains("Duplicate side effects prevented: true"));
        assertTrue(report.contains("Rewind restored baseline: true"));
        assertTrue(report.contains("not the final task result"));
        assertTrue(report.contains("not Git reset"));
        assertTrue(report.contains("not a model benchmark"));
        assertTrue(report.contains("Code Intelligence context is untrusted"));
        assertTrue(report.contains("Replay remains Extended"));
        try(var stream=Files.walk(finalArtifacts)) {
            for(Path file:stream.filter(Files::isRegularFile).toList())
                assertFalse(new String(Files.readAllBytes(file),java.nio.charset.StandardCharsets.UTF_8).contains("\r"));
        }
        try(StateStore store=CoreDemoStateStoreFactory.production().open(owned[0])) {
            var run=store.coreDemoRuns.findById(runId).orElseThrow();
            assertEquals(CoreDemoRunStore.CLEANED,run.status());
            assertEquals(CoreDemoRunStore.RESUME_SUCCEEDED,run.resumeStatus());
            assertEquals(CoreDemoRunStore.REWIND_SUCCEEDED,run.rewindStatus());
            assertTrue(run.resumed());assertTrue(run.rewound());
            var coding=store.codingRuns.findByTask(CoreDemoRunContext.TASK_SPECIFICATION_ID)
                    .getFirst();
            return new Facts(result.exitCode().code(),
                    String.valueOf(manifest.get("semanticResultHash")),
                    String.valueOf(hashes.get("rewindTreeHash")),summary,
                    store.toolCalls.findByTask(CoreDemoRunContext.TASK_SPECIFICATION_ID)
                            .stream().map(ToolCallStore.ToolCallRecord::toolName).toList(),
                    store.snapshots.findByTask(CoreDemoRunContext.TASK_SPECIFICATION_ID)
                            .stream().map(FileSnapshotStore.FileSnapshotRecord::status).toList(),
                    store.verifications.findByRun(coding.runId()).stream()
                            .map(VerificationStore.VerificationRecord::status).toList());
        }
    }
    private record Facts(int exitCode,String semanticHash,String rewindHash,Map<?,?> summary,
            List<String> tools,List<String> snapshotStatuses,
            List<String> verificationStatuses){}
}