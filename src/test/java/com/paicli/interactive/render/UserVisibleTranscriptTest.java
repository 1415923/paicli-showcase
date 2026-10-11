package com.paicli.interactive.render;

import com.paicli.event.AgentEventPublisher;
import com.paicli.event.AgentEventType;
import com.paicli.event.AgentEvents;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UserVisibleTranscriptTest {
    @Test void rendersOrderedPlanModelToolAndFinalResultWithoutPrivateIdentifiers(){
        StringWriter buffer=new StringWriter();
        PlainRuntimeEventRenderer renderer=new PlainRuntimeEventRenderer(new PrintWriter(buffer));
        AgentEventPublisher publisher=new AgentEventPublisher("session-1",Clock.systemUTC());
        publisher.addSink(renderer);
        publisher.publish(AgentEventType.TASK_STARTED,"task-1","run-1",
                m->new AgentEvents.TaskStarted(m,"list files"));
        publisher.publish(AgentEventType.PLAN_CREATED,"task-1","run-1",
                m->new AgentEvents.PlanCreated(m,"inspect",2,List.of(
                        "Inspect the directory","Summarize file purposes")));
        publisher.publish(AgentEventType.MODEL_MESSAGE,"task-1","run-1",
                m->new AgentEvents.ModelMessage(m,"PURPOSE",
                        "I confirmed the workspace boundary and will list its files."));
        publisher.publish(AgentEventType.TOOL_REQUESTED,"task-1","run-1",
                m->new AgentEvents.ToolRequested(m,"list_files","{\"path\":\".\"}",
                        ".","read-only","1 of 2 · Inspect the directory",1,1,
                        "run-1:1:hash"));
        publisher.publish(AgentEventType.TOOL_COMPLETED,"task-1","run-1",
                m->new AgentEvents.ToolCompleted(m,"list_files",
                        "README.md\nnotes.txt\ndocs/guide.md\n",List.of(
                                "README.md","notes.txt","docs/guide.md"),3,
                        List.of("Bytes read: 35"),42,false,
                        "1 of 2 · Inspect the directory",1,1,"run-1:1:hash"));
        publisher.publish(AgentEventType.PLAN_UPDATED,"task-1","run-1",
                m->new AgentEvents.PlanUpdated(m,2,List.of(
                        "✓ Inspect the directory","→ Summarize file purposes")));
        publisher.publish(AgentEventType.FINAL_RESPONSE,"task-1","run-1",
                m->new AgentEvents.FinalResponse(m,"Current files:\n- README.md — guide\n"
                        +"- notes.txt — notes\n- docs/guide.md — test guide\n"
                        +"No files were changed.","REQUIRED","a".repeat(64)));
        publisher.publish(AgentEventType.TASK_COMPLETED,"task-1","run-1",
                m->new AgentEvents.TaskCompleted(m,"COMPLETED","NOT_RUN","done"));
        renderer.flush();String text=buffer.toString();
        assertTrue(text.indexOf("[Plan] 2 steps")<text.indexOf("• Running list_files"));
        assertTrue(text.indexOf("• Running list_files")<text.indexOf("• Ran list_files"));
        assertTrue(text.indexOf("[Result]")<text.indexOf("[Task] Completed"));
        assertTrue(text.contains("│ Target"));assertTrue(text.contains("│ Access"));
        assertTrue(text.contains("├─ Found 3 entry/entries"));
        assertTrue(text.contains("└─ Completed · 42 ms"));
        assertTrue(text.contains("README.md"));
        assertEquals(1,text.lines().filter(line->line.contains("Current files:")).count());
        assertFalse(text.contains("run-1:1:hash"));
        assertFalse(text.contains("C:\\Users"));assertFalse(text.contains("secret-value"));
    }

    @Test void repeatedCallsAreDistinguishedAndLongResultsAreBounded(){
        StringWriter buffer=new StringWriter();
        PlainRuntimeEventRenderer renderer=new PlainRuntimeEventRenderer(new PrintWriter(buffer));
        AgentEventPublisher publisher=new AgentEventPublisher("session-1",Clock.systemUTC());
        publisher.addSink(renderer);
        publisher.publish(AgentEventType.TOOL_REQUESTED,"task-1","run-1",
                m->new AgentEvents.ToolRequested(m,"list_files","{\"path\":\".\"}",
                        ".","read-only","2 of 2 · Summarize",1,2,"call-2"));
        publisher.publish(AgentEventType.TOOL_COMPLETED,"task-1","run-1",
                m->new AgentEvents.ToolCompleted(m,"list_files","bounded",List.of(
                        "a","b","c","d","e","f","g","h"),12,List.of(),1200,true,
                        "2 of 2 · Summarize",1,2,"call-2"));
        renderer.flush();String text=buffer.toString();
        assertTrue(text.contains("repeated call 2"));
        assertTrue(text.contains("… 4 more"));
        assertTrue(text.contains("Output truncated"));
        assertTrue(text.contains("1.2 s"));
    }

    @Test void readOnlyBatchKeepsEachCallAuditableInPlainTranscript(){
        StringWriter buffer=new StringWriter();
        PlainRuntimeEventRenderer renderer=new PlainRuntimeEventRenderer(new PrintWriter(buffer));
        AgentEventPublisher publisher=new AgentEventPublisher("session-1",Clock.systemUTC());
        publisher.addSink(renderer);
        publisher.publish(AgentEventType.TOOL_BATCH_STARTED,"task-1","run-1",
                m->new AgentEvents.ToolBatchStarted(m,"batch-safe",3,"read-only"));
        List<String> paths=List.of("README.md","notes.txt","docs/guide.md");
        for(int index=0;index<paths.size();index++){
            int position=index+1;String path=paths.get(index);
            publisher.publish(AgentEventType.TOOL_REQUESTED,"task-1","run-1",
                    m->new AgentEvents.ToolRequested(m,"read_file",
                            "{\"path\":\""+path+"\"}",path,"read-only",
                            "2 of 3 · Read files",1,1,"call-"+position,position,3));
            publisher.publish(AgentEventType.TOOL_COMPLETED,"task-1","run-1",
                    m->new AgentEvents.ToolCompleted(m,"read_file",null,List.of(),1,
                            List.of("Bytes read: "+(70+position)),position,false,
                            "2 of 3 · Read files",1,1,"call-"+position,position,3));
        }
        publisher.publish(AgentEventType.TOOL_BATCH_COMPLETED,"task-1","run-1",
                m->new AgentEvents.ToolBatchCompleted(m,"batch-safe",3,3,0));
        renderer.flush();String text=buffer.toString();
        assertTrue(text.contains("[Tools] Running 3 read-only calls"));
        assertTrue(text.contains("Running read_file · 1 of 3"));
        assertTrue(text.contains("Running read_file · 2 of 3"));
        assertTrue(text.contains("Running read_file · 3 of 3"));
        assertTrue(text.indexOf("README.md")<text.indexOf("notes.txt"));
        assertTrue(text.indexOf("notes.txt")<text.indexOf("docs/guide.md"));
        assertTrue(text.contains("[Tools] Batch completed · 3 succeeded · 0 failed"));
        assertFalse(text.contains("\u001B["),"plain/NO_COLOR transcript must not add ANSI");
    }

    @Test void commandTranscriptSeparatesStreamsExitCodeAndOutcome(){
        StringWriter buffer=new StringWriter();
        PlainRuntimeEventRenderer renderer=new PlainRuntimeEventRenderer(new PrintWriter(buffer));
        AgentEventPublisher publisher=new AgentEventPublisher("session-1",Clock.systemUTC());
        publisher.addSink(renderer);
        publisher.publish(AgentEventType.TOOL_COMPLETED,"task-1","run-1",
                m->new AgentEvents.ToolCompleted(m,"run_command",null,List.of(
                        "stdout | first line","stdout | second line","stderr | warning"),3,
                        List.of("Exit code: 7","Status: NON_ZERO"),1250,false,
                        "1 of 2 · Inspect",1,1,"command-1"));
        renderer.flush();String text=buffer.toString();
        assertTrue(text.contains("• Ran command"));
        assertTrue(text.contains("├─ stdout"));
        assertTrue(text.contains("│  first line"));
        assertTrue(text.contains("├─ stderr"));
        assertTrue(text.contains("│  warning"));
        assertTrue(text.contains("└─ Failed · exit 7 · 1.3 s"));
        assertFalse(text.contains("Status: NON_ZERO"));
    }

    @Test void failureAndCancellationAlwaysExposeUserTerminalResults(){
        StringWriter buffer=new StringWriter();
        PlainRuntimeEventRenderer renderer=new PlainRuntimeEventRenderer(new PrintWriter(buffer));
        AgentEventPublisher publisher=new AgentEventPublisher("session-1",Clock.systemUTC());
        publisher.addSink(renderer);
        publisher.publish(AgentEventType.TASK_FAILED,"task-f","run-f",
                m->new AgentEvents.TaskFailed(m,"FINAL_RESPONSE_FAILURE",
                        "Final response could not be produced."));
        publisher.publish(AgentEventType.TASK_CANCELLED,"task-c","run-c",
                m->new AgentEvents.TaskCancelled(m,"CANCELLED",false));
        renderer.flush();String text=buffer.toString();
        assertEquals(2,text.lines().map(String::strip).filter("[Result]"::equals).count());
        assertTrue(text.contains("Task did not complete."));
        assertTrue(text.contains("Task cancelled."));
        assertTrue(text.contains("[Task] Failed"));
        assertTrue(text.contains("[Task] Cancelled"));
    }

    @Test void readFileContentAndAbsolutePathsAreNotProjected(){
        StringWriter buffer=new StringWriter();
        PlainRuntimeEventRenderer renderer=new PlainRuntimeEventRenderer(new PrintWriter(buffer));
        AgentEventPublisher publisher=new AgentEventPublisher("session-1",Clock.systemUTC());
        publisher.addSink(renderer);
        publisher.publish(AgentEventType.TOOL_COMPLETED,"task-r","run-r",
                m->new AgentEvents.ToolCompleted(m,"read_file",
                        "secret-value\nC:\\Users\\person\\private.txt",List.of(),2,
                        List.of("Bytes read: 42"),10,false,"1 of 1 · Read",1,1,"call"));
        publisher.publish(AgentEventType.TOOL_COMPLETED,"task-r","run-r",
                m->new AgentEvents.ToolCompleted(m,"list_files",null,
                        List.of("C:\\Users\\person\\private.txt"),1,List.of(),11,false,
                        "1 of 1 · List",1,1,"call-2"));
        renderer.flush();String text=buffer.toString();
        assertTrue(text.contains("Read 2 line(s)"));
        assertTrue(text.contains("Bytes read: 42"));
        assertFalse(text.contains("secret-value"));
        assertFalse(text.contains("C:\\Users"));
        assertTrue(text.contains("[ABSOLUTE_PATH_REDACTED]"),text);
    }
}
