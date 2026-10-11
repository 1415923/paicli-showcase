package com.paicli;

import com.paicli.context.ContextModels.OperationKind;
import com.paicli.state.StateStore;
import com.paicli.support.R3ContextTestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class R3NonInteractiveCompactCommandTest {
    @TempDir Path workspace;

    @Test void statusPreviewAndApplyUseDedicatedCliSurfaceAndInvalidArgsFailClosed()throws Exception{
        String taskId,runId,sourceId,candidate;
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);
            taskId=admitted.root().taskId().value();runId=admitted.root().initialRun().id().value();
            sourceId=state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,8,"cli")).contextGenerationId();
            candidate=state.contexts.createOrReuseCandidate(sourceId,
                    com.paicli.context.DeterministicContextCompiler.COMPILER_VERSION,
                    com.paicli.context.DeterministicContextCompiler.POLICY_VERSION,
                    admitted.guard().current().snapshot()).contextId();
        }
        assertEquals(0,run("compact","status").exitCode());
        assertEquals(1,run("compact","preview").exitCode());
        CliRun unauthorizedPreview=run("compact","preview","--task",taskId,"--run",runId);
        assertEquals(1,unauthorizedPreview.exitCode());
        assertTrue(unauthorizedPreview.output().contains("CONTEXT_OWNERSHIP_REQUIRED"));
        try(StateStore state=new StateStore(workspace)){
            assertEquals(candidate,state.contexts.candidateFor(sourceId).orElseThrow().contextId());
            assertEquals(sourceId,state.contexts.pointer(taskId,runId,OperationKind.CHAT)
                    .orElseThrow().contextGenerationId());
        }
        CliRun unauthorizedApply=run("compact","apply",candidate);
        assertEquals(1,unauthorizedApply.exitCode());
        assertTrue(unauthorizedApply.output().contains("CONTEXT_OWNERSHIP_REQUIRED"));
        assertEquals(1,run("compact","apply","not-a-context").exitCode());
        assertEquals(1,run("compact","status","unexpected").exitCode());
        try(StateStore state=new StateStore(workspace)){
            assertEquals(sourceId,state.contexts.pointer(taskId,runId,OperationKind.CHAT)
                    .orElseThrow().contextGenerationId());
        }
    }

    @Test void emptyStandaloneCommandsAreEffectFreeWithoutAuthority()throws Exception{
        Path previewWorkspace=workspace.resolve("preview-empty");
        String taskId=com.paicli.identity.CanonicalId.newId(
                com.paicli.identity.EntityFamily.TASK).value();
        String runId=com.paicli.identity.CanonicalId.newId(
                com.paicli.identity.EntityFamily.RUN).value();
        CliRun preview=run(previewWorkspace,"compact","preview","--task",taskId,"--run",runId);
        assertEquals(1,preview.exitCode());
        assertTrue(preview.output().contains("CONTEXT_OWNERSHIP_REQUIRED"));
        assertNoRuntimeArtifacts(previewWorkspace);

        Path applyWorkspace=workspace.resolve("apply-empty");
        String contextId=com.paicli.identity.CanonicalId.newId(
                com.paicli.identity.EntityFamily.CONTEXT).value();
        CliRun apply=run(applyWorkspace,"compact","apply",contextId);
        assertEquals(1,apply.exitCode());
        assertTrue(apply.output().contains("CONTEXT_OWNERSHIP_REQUIRED"));
        assertNoRuntimeArtifacts(applyWorkspace);

        Path statusWorkspace=workspace.resolve("status-empty");
        CliRun status=run(statusWorkspace,"compact","status");
        assertEquals(1,status.exitCode());
        assertTrue(status.output().contains("CONTEXT_UNAVAILABLE"));
        assertNoRuntimeArtifacts(statusWorkspace);
    }

    private CliRun run(String...arguments)throws Exception{
        return run(workspace,arguments);
    }

    private CliRun run(Path commandWorkspace,String...arguments)throws Exception{
        String previous=System.getProperty("user.dir");PrintStream out=System.out,err=System.err;
        try(var bytes=new ByteArrayOutputStream();
            var sink=new PrintStream(bytes,true,StandardCharsets.UTF_8)){
            System.setProperty("user.dir",commandWorkspace.toString());System.setOut(sink);System.setErr(sink);
            int exitCode=PaiCLI.run(arguments);
            sink.flush();
            return new CliRun(exitCode,bytes.toString(StandardCharsets.UTF_8));
        }finally{System.setProperty("user.dir",previous);System.setOut(out);System.setErr(err);}
    }

    private static void assertNoRuntimeArtifacts(Path commandWorkspace){
        assertFalse(Files.exists(commandWorkspace));
        assertFalse(Files.exists(commandWorkspace.resolve(".paicli/state/paicli.db")));
        assertFalse(Files.exists(commandWorkspace.resolve(".paicli/state/paicli.db-wal")));
        assertFalse(Files.exists(commandWorkspace.resolve(".paicli/state/paicli.db-shm")));
    }

    private record CliRun(int exitCode,String output){}
}
