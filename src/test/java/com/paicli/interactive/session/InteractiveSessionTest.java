package com.paicli.interactive.session;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class InteractiveSessionTest {
    @Test void ownsMultipleSequentialTasksWithoutDeletingHistory(){
        InteractiveSession session=new InteractiveSession("session-1",Path.of("project"),"ollama","qwen",
                "on-request",InteractiveSession.TerminalMode.INTERACTIVE,Instant.EPOCH);
        session.start();session.startTask("task-1","run-1");session.finishTask();
        assertEquals("task-1",session.snapshot().lastTaskId());
        assertEquals(2,session.clearContext());
        session.startTask("task-2","run-2");session.finishTask();session.close(Instant.EPOCH.plusSeconds(2));
        assertEquals(InteractiveSession.Status.CLOSED,session.snapshot().status());
        assertEquals("task-2",session.snapshot().lastTaskId());
        assertFalse(session.toString().contains(session.workspace().toAbsolutePath().toString()));
    }

    @Test void cancellationAndActiveTaskGatesAreExplicit(){
        InteractiveSession session=new InteractiveSession("session-1",Path.of("project"),"unconfigured",null,
                "on-request",InteractiveSession.TerminalMode.PLAIN,Instant.EPOCH);
        session.start();session.startTask("task-1","run-1");
        assertEquals(InteractiveSession.CancellationState.REQUESTED,session.requestCancellation());
        assertEquals(InteractiveSession.CancellationState.FORCE_REQUESTED,session.requestCancellation());
        assertThrows(IllegalStateException.class,session::clearContext);
        assertThrows(IllegalStateException.class,()->session.close(Instant.now()));
    }

    @Test void workspaceLabelIsBounded() {
        InteractiveSession session = new InteractiveSession("session-1", Path.of("x".repeat(200)),
                "ollama", "qwen", "on-request", InteractiveSession.TerminalMode.PLAIN, Instant.EPOCH);
        assertEquals(128, session.snapshot().workspaceLabel().length());
    }

    @Test void cancellationControllerUsesTwoStageSemantics(){
        TaskCancellationController controller=new TaskCancellationController();
        assertEquals(TaskCancellationController.Action.REQUEST_COOPERATIVE,controller.request());
        assertEquals(TaskCancellationController.Action.FORCE_OWNED_PROCESSES,controller.request());
        assertEquals(TaskCancellationController.Action.ALREADY_FORCE_REQUESTED,controller.request());
        assertTrue(controller.cancellationRequested());assertTrue(controller.forceRequested());
    }
}
