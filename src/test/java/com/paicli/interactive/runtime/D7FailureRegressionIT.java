package com.paicli.interactive.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.Config;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.task.InteractiveTaskRequest;
import com.paicli.interactive.task.TaskSubmissionResult;
import com.paicli.model.*;
import com.paicli.provider.ModelProvider;
import com.paicli.state.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Offline regression: production task/context/completion, deterministic model only. */
class D7FailureRegressionIT {
    @TempDir Path work;
    private final List<com.paicli.event.AgentEvent> events=new java.util.concurrent.CopyOnWriteArrayList<>();
    @ParameterizedTest @ValueSource(strings={"http://localhost:18797/", "https://example.test/a",
            "[page](HTTPS://localhost:8080/a?q=1)", "HTTP://127.0.0.1:18797/", "E1", "E5"})
    void httpReportsReachPersistedRenderedCompletion(String content)throws Exception {
        if(content.equals("E1")||content.equals("E5")){
            try(var input=getClass().getResourceAsStream("/d7/"+content+"-final.txt")){
                assertNotNull(input);content=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        String answer=content;
        String task=run((messages,tools)->ModelResponse.finalAnswer(answer),true);
        try(var db=new DatabaseManager(work.resolve(".paicli/state"));var sql=db.getConnection().createStatement();
                var rows=sql.executeQuery("SELECT content,render_status FROM task_user_results")){
            assertTrue(rows.next());assertEquals(answer,rows.getString(1));assertEquals("RENDERED",rows.getString(2));
        }
        var rendered=events.stream().filter(e->e instanceof com.paicli.event.AgentEvents.FinalResponse)
                .map(e->(com.paicli.event.AgentEvents.FinalResponse)e).findFirst().orElseThrow();
        assertEquals(answer,rendered.boundedContent());
        assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(answer.getBytes(java.nio.charset.StandardCharsets.UTF_8))),rendered.contentHash());
    }

    @ParameterizedTest @ValueSource(strings={"C:\\Users\\private", "D:/private/file", "c:/", "D:\\",
            "```\nC:/private\n```", "http://localhost:18797/ and C:/private",
            "https://example.test/C:/private", "https://example.test/?path=D:/private",
            "file:///C:/private", "prefixhttp://localhost"})
    void drivePathsStillPreventCompletion(String answer)throws Exception {
        run((messages,tools)->ModelResponse.finalAnswer(answer),false);
        try(var state=new StateStore(work)){
            assertTrue(state.tasks.findAll(5).getFirst().lastError().contains("FINAL_RESPONSE_ABSOLUTE_PATH_REJECTED"));
        }
    }

    @ParameterizedTest @ValueSource(booleans={false,true})
    void jsonReadContinuesThroughRealContextAndPreservesFacts(boolean batch)throws Exception {
        Files.writeString(work.resolve("skills.json"),"[\r\n  \"原始资源/规则\",\r\n  {\"to\\u006ben\":\"D7_PRIVATE_CANARY\",\"nested\":{\"password\":\"D7_PASSWORD_CANARY\"}}\r\n]\r\n");
        Files.writeString(work.resolve("other.txt"),"other evidence");
        var calls=new AtomicInteger();var seen=new ArrayList<String>();
        String task=run((messages,tools)->{
            int index=calls.getAndIncrement();
            if(index==0){
                var read=new ToolCall("read_file",Map.of("path","skills.json"),"json-read");
                return batch?ModelResponse.toolCallBatchResponse("Read inputs",List.of(read,
                        new ToolCall("read_file",Map.of("path","other.txt"),"other-read")),null,false):
                        ModelResponse.toolCallResponse("Read input",read);
            }
            for(var message:messages)if(message.type()==AgentMessage.MessageType.OBSERVATION){
                assertFalse(message.content().contains("D7_PRIVATE_CANARY"));
                assertFalse(message.content().contains("D7_PASSWORD_CANARY"));
                if("json-read".equals(message.toolCallId()))seen.add(message.content());
            }
            return ModelResponse.finalAnswer("JSON input and runtime facts inspected.");
        },true);
        assertFalse(seen.isEmpty());String observation=seen.getFirst();
        assertTrue(observation.contains("[tool fact: bytesRead="),observation);
        assertTrue(observation.contains("原始资源/规则"));
        try(var state=new StateStore(work)){
            var event=state.events.findMessages(task).stream().filter(m->"json-read".equals(m.toolCallId())
                    &&m.type()==AgentMessage.MessageType.OBSERVATION).findFirst().orElseThrow();
            assertEquals(observation,event.content());
            assertEquals(observation,state.toolCalls.findByTask(task).stream()
                    .filter(t->"json-read".equals(t.toolCallId())).findFirst().orElseThrow().output());
        }
    }

    private String run(ModelProvider provider,boolean success)throws Exception {
        Config config=Config.defaults().merge(new ObjectMapper().readValue("""
                {"provider":"ollama","model":"offline-d7","approval_mode":"on-request",
                "enable_plan":false,"enable_verify":false,"enable_repair":false,
                "enable_skills":false,"enable_hooks":false,"max_iterations":4,
                "context_window_tokens":131072}
                """,Config.class));
        try(var runtime=InteractiveRuntimeAdapter.open(work,
                new ProductionInteractiveTaskExecutor(work,()->config,ignored->provider),events::add)){
            var session=new InteractiveSession("offline-d7",work,"ollama","offline-d7","on-request",
                    InteractiveSession.TerminalMode.PLAIN,Instant.now());session.start();runtime.openSession(session);
            runtime.submit(new InteractiveTaskRequest("offline-d7",work,"Inspect the dedicated fixture and report the observed evidence.",List.of()));
            var foreground=runtime.activeForegroundTask().orElseThrow();
            var terminal=foreground.terminal().get(30,TimeUnit.SECONDS);
            assertEquals(success?TaskSubmissionResult.Status.COMPLETED:TaskSubmissionResult.Status.FAILED,
                    terminal.status(),terminal.toString());
            assertTrue(runtime.awaitIdle(Duration.ofSeconds(5)));return foreground.taskId();
        }
    }
}
