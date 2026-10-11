package com.paicli.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ManagedAnswerContractTest {
    @Test void explicitAnswerPreservesUserContentWithoutKeywordFiltering() throws Exception {
        // These phrases can be legitimate user-requested quotations. Structure cannot judge semantics.
        String answer="  引用：Let me think / Now deliver.\n```java\nString s = \"完成😀\";\n```\t";
        String encoded=new ObjectMapper().writeValueAsString(Map.of("userAnswer",answer));
        assertEquals(answer,ManagedAnswerContract.decode(encoded));
        assertEquals("{\"data\":1}",ManagedAnswerContract.decode("{\"userAnswer\":\"{\\\"data\\\":1}\"}"));
    }
    @Test void ambiguousOrUnstructuredDeliveriesAreRejectedWithoutExtraction() {
        for(String value:List.of("", "null", "[]", "\"hello\"", "{}", "{\"userAnswer\":null}",
                "{\"userAnswer\":2}","{\"userAnswer\":[]}","{\"userAnswer\":\" \"}",
                "{\"userAnswer\":\"a\",\"userAnswer\":\"b\"}",
                "{\"userAnswer\":\"a\",\"notes\":\"draft\"}",
                "Let me deliver. {\"userAnswer\":\"a\"}",
                "{\"userAnswer\":\"a\"} Now deliver.","{\"userAnswer\":\"a\"} {}",
                "```json\n{\"userAnswer\":\"a\"}\n```", "{\"userAnswer\":\"\\u0000\"}",
                "{\"userAnswer\":\"\\uD800\"}","{\"userAnswer\":\"\\uDC00\"}")) {
            assertEquals(ManagedAnswerContract.FAILURE,assertThrows(IllegalArgumentException.class,
                    ()->ManagedAnswerContract.decode(value),value).getMessage());
        }
    }
    @Test void nullAndOversizedAnswerAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class,()->ManagedAnswerContract.decode(null));
        String encoded=new ObjectMapper().writeValueAsString(Map.of("userAnswer","a".repeat(65537)));
        assertThrows(IllegalArgumentException.class,()->ManagedAnswerContract.decode(encoded));
    }
    @Test void onlyMainUserAnswerOperationsParticipate() {
        for(var op:ContextModels.OperationKind.values()) {
            assertFalse(ManagedAnswerContract.applies(op,true));
            assertEquals(op!=ContextModels.OperationKind.PLAN&&op!=ContextModels.OperationKind.VERIFICATION,
                    ManagedAnswerContract.applies(op,false));
        }
    }
}
