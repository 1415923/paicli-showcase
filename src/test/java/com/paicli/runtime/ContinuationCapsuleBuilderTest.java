package com.paicli.runtime;

import com.paicli.identity.*;
import com.paicli.state.FollowUpModels.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ContinuationCapsuleBuilderTest {
    @Test void canonicalHashIgnoresCapsuleIdentityAndTimeAndOrdersFacts(){
        FollowUpCanonicalizer c=new FollowUpCanonicalizer();ParentAnchor anchor=anchor();
        CapsuleFact a=new CapsuleFact(FactType.MANIFEST,"task","manifest","m","a".repeat(64),"durable");
        CapsuleFact b=new CapsuleFact(FactType.PARENT_GOAL,"task","goal",null,null,"durable");
        assertEquals(c.hash(anchor,List.of(a,b)),c.hash(anchor,List.of(b,a)));
        assertNotEquals(c.hash(anchor,List.of(a,b)),c.hash(anchor,List.of(a)));
        assertNotEquals(c.hash(anchor,List.of(a,b)),c.hash(anchor,List.of(a,b,
                new CapsuleFact(FactType.RISK,"task","extra",null,null,"durable"))));
        assertThrows(IllegalArgumentException.class,()->c.hash(anchor,List.of(a,b,b)));
        assertNotEquals(c.hash(anchor,List.of(a,b)),c.hash(anchor,List.of(a,
                new CapsuleFact(FactType.PARENT_GOAL,"task","replaced",null,null,"durable"))));
        assertNotEquals(c.hash(anchor,List.of(a,b)),c.hash(new ParentAnchor(anchor.threadId(),anchor.taskId(),0,
                "goal","COMPLETED",anchor.terminalRunId(),anchor.generationId(),"COMPLETION_DECISION","b".repeat(64),
                "manifest","a".repeat(64),null,null,0,null,List.of()),List.of(a,b)));
        assertNotEquals(c.hash(anchor,List.of(a,b)),c.hash(new ParentAnchor(anchor.threadId(),anchor.taskId(),0,
                "goal","COMPLETED",anchor.terminalRunId(),anchor.generationId(),"COMPLETION_DECISION","a".repeat(64),
                "manifest","a".repeat(64),null,null,2,"b".repeat(64),List.of("c".repeat(64))),List.of(a,b)));
    }
    @Test void instructionAndFactSanitizationIsBoundedAndSecretSafe(){
        FollowUpCanonicalizer c=new FollowUpCanonicalizer();String safe=c.sanitizeInstruction("token=raw-secret\u0000"+"x".repeat(5000));
        assertFalse(safe.contains("raw-secret"));assertFalse(safe.contains("\u0000"));
        assertTrue(safe.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=4096);
    }
    private static ParentAnchor anchor(){return new ParentAnchor(CanonicalId.newId(EntityFamily.THREAD),
            CanonicalId.newId(EntityFamily.TASK),0,"goal","COMPLETED",CanonicalId.newId(EntityFamily.RUN),
            CanonicalId.newId(EntityFamily.GENERATION),"COMPLETION_DECISION","a".repeat(64),"manifest","a".repeat(64),
            null,null,1,"b".repeat(64),List.of("c".repeat(64)));}
}
