package com.paicli.interactive.shell;

import com.paicli.interactive.query.InteractiveQueryFacade;
import com.paicli.interactive.query.ViewModelReadResult;
import com.paicli.interactive.session.InteractiveSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class R2PromptReadOutcomeTest {
    @TempDir Path workspace;

    @Test void absentUnavailableAndIntegrityFailureNeverShareTheIdlePrompt(){
        InteractiveSession session=new InteractiveSession("prompt-r2",workspace,
                "ollama","model","on-request",InteractiveSession.TerminalMode.PLAIN,
                Instant.parse("2026-07-20T00:00:00Z"));
        session.start();
        assertEquals("paicli> ",renderer(ViewModelReadResult.absent())
                .primary(session.snapshot()));
        assertEquals("paicli [status-unavailable]> ",
                renderer(ViewModelReadResult.unavailable("VIEW_OPEN_FAILED"))
                        .primary(session.snapshot()));
        assertEquals("paicli [integrity-blocked]> ",renderer(
                new ViewModelReadResult(ViewModelReadResult.State.INTEGRITY_FAILURE,
                        null,"AUTHORITY_INTEGRITY_FAILURE")).primary(session.snapshot()));
    }

    @Test void runtimeOnlyRendererDoesNotPretendThatAnUnattemptedReadFailed(){
        InteractiveSession session=new InteractiveSession("prompt-runtime-only",workspace,
                "mock","model","on-request",InteractiveSession.TerminalMode.PLAIN,
                Instant.parse("2026-07-20T00:00:00Z"));
        session.start();
        InteractivePromptRenderer renderer=new InteractivePromptRenderer();
        assertEquals("paicli> ",renderer.primary(session.snapshot()));
        session.startTask("task-runtime-only","run-runtime-only");
        assertEquals("paicli [running: EXECUTING]> ",
                renderer.running(session.snapshot(),"EXECUTING"));
    }

    private static InteractivePromptRenderer renderer(ViewModelReadResult result){
        return new InteractivePromptRenderer(new InteractiveQueryFacade(){
            @Override public ViewModelReadResult readViewModel(
                    InteractiveSession.Snapshot session){return result;}
            @Override public List<String> status(InteractiveSession.Snapshot session){return List.of();}
            @Override public List<String> plan(InteractiveSession.Snapshot session){return List.of();}
            @Override public List<String> tasks(int limit){return List.of();}
            @Override public List<String> report(InteractiveSession.Snapshot session,String taskId){return List.of();}
        });
    }
}
