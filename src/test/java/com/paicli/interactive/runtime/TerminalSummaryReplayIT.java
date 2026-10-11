package com.paicli.interactive.runtime;

import com.paicli.config.Config;
import com.paicli.interactive.task.*;
import com.paicli.model.ModelResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class TerminalSummaryReplayIT {
    @TempDir Path work;
    @Test void longApiReferencesCannotTurnCompletedTaskIntoInteractiveFailure()throws Exception {
        replay("Observed service endpoint /api/risk-check returned 503. ".repeat(50));
    }
    @Test @EnabledIfSystemProperty(named="paicli.replay.answer",matches=".+")
    void savedN1PublicAnswerReplaysWithoutChangingItsBytes()throws Exception {
        // JSON preserves the exact public content; a Markdown export can have host CRLF endings.
        replay(EcosystemNaturalTaskIT.JSON.readTree(Files.readString(
                Path.of(System.getProperty("paicli.replay.answer")))).path("content").asText());
    }
    private void replay(String answer)throws Exception {
        Config config=Config.defaults().merge(EcosystemNaturalTaskIT.JSON.readValue("""
                {"provider":"ollama","model":"fixture","approval_mode":"on-request",
                 "enable_plan":false,"enable_verify":false,"enable_repair":false,
                 "enable_skills":false,"enable_hooks":false,"max_iterations":4,"context_window_tokens":131072}
                """,Config.class));
        String taskId;var calls=new java.util.concurrent.atomic.AtomicInteger();
        try(var runtime=InteractiveRuntimeAdapter.open(work,new ProductionInteractiveTaskExecutor(work,()->config,
                ignored->(messages,tools)->{calls.incrementAndGet();return ModelResponse.finalAnswer(answer);}),event->{})) {
            var session=EcosystemNaturalTaskIT.configuredSession("replay",work,config);session.start();runtime.openSession(session);
            runtime.submit(new InteractiveTaskRequest("replay",work,"Replay the provided answer to test delivery only",List.of()));
            var task=runtime.activeForegroundTask().orElseThrow();taskId=task.taskId();
            var result=task.terminal().get(10,TimeUnit.SECONDS);
            assertEquals(TaskSubmissionResult.Status.COMPLETED,result.status(),result.toString());
        }
        assertEquals(1,calls.get(),"Exactly one local scripted response; no model request");
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+work.resolve(".paicli/state/paicli.db"));
            var q=db.prepareStatement("SELECT content,validation_status,render_status,content_hash FROM task_user_results WHERE task_id=?")) {
            q.setString(1,taskId);try(var r=q.executeQuery()) {
                assertTrue(r.next());assertEquals(answer,r.getString(1));assertEquals("VALID",r.getString(2));assertEquals("RENDERED",r.getString(3));
                assertEquals(com.paicli.skill.SkillPackageFiles.hash(answer.getBytes(java.nio.charset.StandardCharsets.UTF_8)),r.getString(4));
            }
            try(var s=db.prepareStatement("SELECT status FROM tasks WHERE task_id=?")) {
                s.setString(1,taskId);try(var r=s.executeQuery()){assertTrue(r.next());assertEquals("DONE",r.getString(1));}
            }
        }
    }
}
