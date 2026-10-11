package com.paicli.event;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AgentEventPublisherTest {
    private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-07-14T00:00:00Z"),ZoneOffset.UTC);

    @Test void ordersEventsAndIsolatesFailingSinks(){
        AgentEventPublisher publisher=new AgentEventPublisher("session-1",CLOCK);
        List<AgentEvent> received=new ArrayList<>();
        publisher.addSink(event->{throw new IllegalStateException("renderer failed");});
        publisher.addSink(received::add);
        publisher.publish(AgentEventType.TASK_STARTED,"task-1","run-1",m->new AgentEvents.TaskStarted(m,"bounded task"));
        publisher.publish(AgentEventType.PHASE_CHANGED,"task-1","run-1",m->new AgentEvents.PhaseChanged(m,"PLANNING"));
        assertEquals(List.of(1L,2L),received.stream().map(e->e.metadata().sequence()).toList());
        assertEquals(2,publisher.sinkFailureCount());
        assertEquals(2,publisher.lastSequence());
    }

    @Test void validatesTypesBoundsAndRelativePaths(){
        AgentEventPublisher publisher=new AgentEventPublisher("session-1",CLOCK);
        assertThrows(IllegalArgumentException.class,()->publisher.publish(AgentEventType.FILE_READ,"task-1","run-1",
                m->new AgentEvents.FileRead(m,"C:/secret.txt",4)));
        assertThrows(IllegalArgumentException.class,()->publisher.publish(AgentEventType.PLAN_CREATED,"task-1","run-1",
                m->new AgentEvents.PlanCreated(m,"x".repeat(2049))));
        assertThrows(IllegalArgumentException.class,()->new AgentEvents.TaskStarted(
                new AgentEventMetadata(AgentEventType.TASK_FAILED,"session-1","task-1","run-1",1,CLOCK.instant()),"task"));
    }

    @Test void redactsSecretsAndAbsolutePathsAtTheTypedBoundary(){
        AgentEventPublisher publisher=new AgentEventPublisher("session-1",CLOCK);
        List<AgentEvent> events=new ArrayList<>();publisher.addSink(events::add);
        publisher.publish(AgentEventType.TASK_FAILED,"task-1","run-1",
                m->new AgentEvents.TaskFailed(m,"FAILED",
                        "password=hunter2 at C:\\Users\\name\\secret.txt and /home/name/token.txt"));
        AgentEvents.TaskFailed event=(AgentEvents.TaskFailed)events.getFirst();
        assertFalse(event.boundedExcerpt().contains("hunter2"));
        assertFalse(event.boundedExcerpt().contains("C:\\Users"));
        assertFalse(event.boundedExcerpt().contains("/home/name"));
        assertTrue(event.boundedExcerpt().contains("[ABSOLUTE_PATH_REDACTED]"));
    }
    @Test void supportsRepresentativeRuntimeSequence(){
        AgentEventPublisher publisher=new AgentEventPublisher("session-1",CLOCK);
        List<AgentEventType> types=new ArrayList<>();publisher.addSink(e->types.add(e.metadata().eventType()));
        publisher.publish(AgentEventType.SESSION_STARTED,null,null,m->new AgentEvents.SessionStarted(m,"project","ollama","model","on-request"));
        publisher.publish(AgentEventType.TASK_STARTED,"task-1","run-1",m->new AgentEvents.TaskStarted(m,"fix rounding"));
        publisher.publish(AgentEventType.PLAN_CREATED,"task-1","run-1",m->new AgentEvents.PlanCreated(m,"inspect, edit, verify"));
        publisher.publish(AgentEventType.DIFF_PROPOSED,"task-1","run-1",m->new AgentEvents.DiffProposed(m,"src/A.java",2,1,"bounded diff",false));
        publisher.publish(AgentEventType.VERIFICATION_FAILED,"task-1","run-1",m->new AgentEvents.VerificationFailed(m,"TEST_FAILURE","expected 1"));
        publisher.publish(AgentEventType.REPAIR_STARTED,"task-1","run-1",m->new AgentEvents.RepairStarted(m,1));
        publisher.publish(AgentEventType.VERIFICATION_PASSED,"task-1","run-1",m->new AgentEvents.VerificationPassed(m,"3 tests"));
        publisher.publish(AgentEventType.TASK_COMPLETED,"task-1","run-1",m->new AgentEvents.TaskCompleted(m,"done"));
        assertEquals(List.of(AgentEventType.SESSION_STARTED,AgentEventType.TASK_STARTED,AgentEventType.PLAN_CREATED,
                AgentEventType.DIFF_PROPOSED,AgentEventType.VERIFICATION_FAILED,AgentEventType.REPAIR_STARTED,
                AgentEventType.VERIFICATION_PASSED,AgentEventType.TASK_COMPLETED),types);
    }
}
