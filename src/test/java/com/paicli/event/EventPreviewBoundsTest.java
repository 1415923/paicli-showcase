package com.paicli.event;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class EventPreviewBoundsTest {
    @Test void validPreviewRemainsBoundedAfterRedactionExpandsIt() {
        var events=new ArrayList<AgentEvent>();var publisher=new AgentEventPublisher("preview",Clock.systemUTC());
        publisher.addSink(events::add);
        String text="/a/b ".repeat(400);assertTrue(text.length()<=2048);
        assertDoesNotThrow(()->publisher.publish(AgentEventType.TASK_COMPLETED,"task","run",
                m->new AgentEvents.TaskCompleted(m,text)));
        String preview=((AgentEvents.TaskCompleted)events.getFirst()).boundedSummary();
        assertTrue(preview.length()<=2048);assertFalse(preview.contains("/a/b"));assertTrue(preview.endsWith("[truncated]"));
    }
    @Test void originallyOversizedOrInvalidControlInputIsStillRejected() {
        var publisher=new AgentEventPublisher("preview",Clock.systemUTC());
        for(String text:new String[]{"x".repeat(2049),"/a/b ".repeat(390)+"\u0001"})
            assertThrows(IllegalArgumentException.class,()->publisher.publish(AgentEventType.TASK_COMPLETED,"task","run",
                    m->new AgentEvents.TaskCompleted(m,text)));
    }
    @Test void finalAnswerIsNeverShortenedOrPathRedactedAsAPreview() {
        var events=new ArrayList<AgentEvent>();var publisher=new AgentEventPublisher("preview",Clock.systemUTC());publisher.addSink(events::add);
        String answer="Observed /api/risk-check:503; ".repeat(100);
        String hash=com.paicli.skill.SkillPackageFiles.hash(answer.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        publisher.publish(AgentEventType.FINAL_RESPONSE,"task","run",m->new AgentEvents.FinalResponse(m,answer,"REQUIRED",hash));
        assertEquals(answer,((AgentEvents.FinalResponse)events.getFirst()).boundedContent());
        assertThrows(IllegalArgumentException.class,()->publisher.publish(AgentEventType.FINAL_RESPONSE,"task","run",
                m->new AgentEvents.FinalResponse(m,"x".repeat(65537),"REQUIRED",hash)));
    }
    @Test void previewTruncationDoesNotSplitSupplementaryCharactersOrRelaxIdentityBounds() {
        String preview=EventBounds.requiredPreview("x".repeat(2035)+"😀 /a/b",2048,"preview");
        for(int i=0;i<preview.length();i++) {
            if(Character.isHighSurrogate(preview.charAt(i))) {
                assertTrue(i+1<preview.length()&&Character.isLowSurrogate(preview.charAt(++i)));
            }else assertFalse(Character.isLowSurrogate(preview.charAt(i)));
        }
        assertThrows(IllegalArgumentException.class,()->EventBounds.required("/a/b ".repeat(50),256,"identifier"));
    }
}
