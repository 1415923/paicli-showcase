package com.paicli.interactive.runtime;

import com.paicli.config.Config;
import com.paicli.interactive.task.*;
import com.paicli.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class NaturalDriverLifecycleIT {
    @TempDir Path root;
    @Test void fixtureResponderRejectsOutOfScopeCommandThroughRealApprovalGate()throws Exception {
        Path work=root.resolve("deny-w"),control=root.resolve("deny-c");Files.createDirectories(work);Files.createDirectories(control);
        Config config=Config.defaults().merge(EcosystemNaturalTaskIT.JSON.readValue("""
                {"provider":"ollama","model":"fixture","approval_mode":"on-request",
                 "enable_plan":false,"enable_verify":false,"enable_repair":false,
                 "enable_skills":false,"enable_hooks":false,"max_iterations":8,"context_window_tokens":131072}
                """,Config.class));
        var counter=new java.util.concurrent.atomic.AtomicInteger();var result=new LinkedHashMap<String,Object>();
        try(var runtime=InteractiveRuntimeAdapter.open(work,new ProductionInteractiveTaskExecutor(work,()->config,
                ignored->(messages,tools)->counter.getAndIncrement()==0
                        ?ModelResponse.toolCallResponse("Request outside fixture scope",new ToolCall("run_command",Map.of("command","echo fixture > forbidden.txt")))
                        :ModelResponse.finalAnswer("Requested operation was denied.")),event->{})) {
            var session=EcosystemNaturalTaskIT.configuredSession("deny-fixture",work,config);session.start();runtime.openSession(session);
            runtime.submit(new InteractiveTaskRequest("deny-fixture",work,"Inspect fixture only",List.of()));
            var task=runtime.activeForegroundTask().orElseThrow();
            EcosystemNaturalTaskIT.awaitTask(runtime,task,work,control,System.nanoTime()+TimeUnit.SECONDS.toNanos(10),true,result);
            assertFalse(Files.exists(work.resolve("forbidden.txt")));
            var timings=(List<?>)result.get("approvalTimings");assertEquals(1,timings.size());
            assertEquals("n",((Map<?,?>)timings.getFirst()).get("answer"));
        }
    }
    @Test void pendingApprovalDeadlineCancelsDurablyWithoutExecutingCommand()throws Exception {
        Path work=root.resolve("w"),control=root.resolve("c");Files.createDirectories(work);Files.createDirectories(control);
        Config config=Config.defaults().merge(EcosystemNaturalTaskIT.JSON.readValue("""
                {"provider":"ollama","model":"fixture","approval_mode":"on-request",
                 "enable_plan":false,"enable_verify":false,"enable_repair":false,
                 "enable_skills":false,"enable_hooks":false,"max_iterations":8,"context_window_tokens":131072}
                """,Config.class));
        var result=new LinkedHashMap<String,Object>();String taskId;
        try(var runtime=InteractiveRuntimeAdapter.open(work,new ProductionInteractiveTaskExecutor(work,()->config,
                ignored->(messages,tools)->ModelResponse.toolCallResponse("Request fixture command",
                        new ToolCall("run_command",Map.of("command","echo fixture > forbidden.txt")))),event->{})) {
            var session=EcosystemNaturalTaskIT.configuredSession("cancel-fixture",work,config);session.start();runtime.openSession(session);
            runtime.submit(new InteractiveTaskRequest("cancel-fixture",work,"Inspect fixture only",List.of()));
            var task=runtime.activeForegroundTask().orElseThrow();taskId=task.taskId();
            long bound=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(runtime.pendingApproval().isEmpty()&&!task.terminal().isDone()&&System.nanoTime()<bound)Thread.sleep(10);
            assertTrue(runtime.pendingApproval().isPresent(),()->"terminal="+task.terminal().getNow(null));
            var terminal=EcosystemNaturalTaskIT.awaitTask(runtime,task,work,control,
                    System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(150),false,result);
            assertEquals(TaskSubmissionResult.Status.CANCELLED,terminal.status());
            assertTrue(runtime.awaitIdle(Duration.ofSeconds(2)));
            assertFalse(Files.exists(work.resolve("forbidden.txt")));
            assertTrue(Files.isRegularFile(control.resolve("cancellation-requested.json")));
            assertEquals(List.of(),result.get("approvalTimings"));
        }
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+work.resolve(".paicli/state/paicli.db"));
            var q=db.prepareStatement("SELECT status FROM tasks WHERE task_id=?")) {
            q.setString(1,taskId);try(var r=q.executeQuery()){assertTrue(r.next());assertEquals("CANCELLED",r.getString(1));}
            try(var s=db.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM tool_calls WHERE tool_name='run_command' AND result_success=1")) {
                assertTrue(r.next());assertEquals(0,r.getInt(1));
            }
        }
    }
}
