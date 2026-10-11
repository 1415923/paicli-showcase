package com.paicli.interactive.render;

import com.paicli.event.*;
import org.junit.jupiter.api.Test;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class EventRendererTest {
    @Test void plainAndJsonRenderBoundedFactsWithoutInternalPayloads(){
        AgentEvent event=new AgentEvents.DiffProposed(new AgentEventMetadata(AgentEventType.DIFF_PROPOSED,
                "session-1","task-1","run-1",1,Instant.EPOCH),"src/Pricing.java",12,4,"summary",false);
        StringWriter plain=new StringWriter();new PlainEventRenderer(new PrintWriter(plain)).accept(event);
        assertEquals("[Proposed Edit] src/Pricing.java +12 -4",plain.toString().trim());
        StringWriter json=new StringWriter();new JsonEventRenderer(new PrintWriter(json)).accept(event);
        assertTrue(json.toString().contains("\"eventType\":\"DIFF_PROPOSED\""));
        assertTrue(json.toString().contains("\"sequence\":1"));
    }

    @Test void redactsKnownSecretAssignmentsBeforeRendering(){
        AgentEvent event=new AgentEvents.TaskFailed(new AgentEventMetadata(AgentEventType.TASK_FAILED,
                "session-1","task-1","run-1",1,Instant.EPOCH),"MODEL_ERROR","api_key=sentinel-value");
        assertFalse(((AgentEvents.TaskFailed)event).boundedExcerpt().contains("sentinel-value"));
        StringWriter json=new StringWriter();new JsonEventRenderer(new PrintWriter(json)).accept(event);
        assertFalse(json.toString().contains("sentinel-value"));assertTrue(json.toString().contains("redacted"));
    }
}
