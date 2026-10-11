package com.paicli.interactive.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.Config;
import com.paicli.interactive.task.InteractiveTaskRequest;
import com.paicli.interactive.task.TaskSubmissionResult;
import com.paicli.model.ModelResponse;
import com.paicli.state.StateStore;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** A completed model answer must still run the configured independent verifier. No remote calls. */
class NaturalVerificationContractIT {
    @TempDir Path workspace;
    @ParameterizedTest @ValueSource(ints={0,1})
    void configuredSessionRunsVerifierAfterModelFinal(int exit) throws Exception {
        Config config=Config.defaults().merge(new ObjectMapper().readValue("""
                {"provider":"mock","model":"fixture","approval_mode":"on-request",
                 "enable_plan":false,"enable_verify":false,"enable_repair":false,
                 "enable_skills":false,"enable_hooks":false,"max_iterations":3,
                 "context_window_tokens":131072}
                """,Config.class)).withVerification("exit "+exit,true);
        String task,run;
        try(var runtime=InteractiveRuntimeAdapter.open(workspace,new ProductionInteractiveTaskExecutor(
                workspace,()->config,ignored->(messages,tools)->ModelResponse.finalAnswer("Task finished.")),event->{})){
            var session=EcosystemNaturalTaskIT.configuredSession("natural-verifier",workspace,config);
            session.start();runtime.openSession(session);
            runtime.submit(new InteractiveTaskRequest("natural-verifier",workspace,"Inspect the task and finish.",List.of()));
            var foreground=runtime.activeForegroundTask().orElseThrow();task=foreground.taskId();
            var terminal=foreground.terminal().get(30,TimeUnit.SECONDS);
            assertEquals(exit==0?TaskSubmissionResult.Status.COMPLETED_VERIFIED:TaskSubmissionResult.Status.FAILED,
                    terminal.status(),terminal.toString());
            assertTrue(runtime.awaitIdle(Duration.ofSeconds(3)));
            run=runtime.lastResult().orElseThrow().runId();
        }
        try(var state=new StateStore(workspace)){
            var verifications=state.verifications.findByRun(run);assertEquals(1,verifications.size());
            var verification=verifications.getFirst();assertEquals(task,verification.taskId());
            assertEquals("exit "+exit,verification.command());assertEquals(exit,verification.exitCode());
            assertEquals(exit==0?"PASSED":"FAILED",verification.status());
            assertNotNull(verification.candidateDigest());
        }
    }
}
