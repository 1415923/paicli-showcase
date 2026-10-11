package com.paicli.runtime;

import com.paicli.state.SteeringModels.Classification;
import com.paicli.state.SteeringModels.ClassificationOutcome;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SteeringClassifierTest {
    private final SteeringClassifier classifier=new SteeringClassifier();

    @Test void deterministicNonMaterialAndMaterialRulesDoNotUseAProvider(){
        var nonMaterial=classifier.classify("format: keep diagnostics concise");
        assertEquals(Classification.NON_MATERIAL,nonMaterial.classification());
        assertEquals(ClassificationOutcome.ACCEPT,nonMaterial.outcome());
        var material=classifier.classify("change the file scope and delete obsolete output");
        assertEquals(Classification.MATERIAL,material.classification());
        assertEquals(ClassificationOutcome.ACCEPT_MATERIAL,material.outcome());
    }

    @Test void uncertainTextNeverAutoAccepts(){
        var result=classifier.classify("Could you make this a little better?");
        assertEquals(Classification.UNKNOWN,result.classification());
        assertEquals(ClassificationOutcome.NEEDS_CONFIRMATION,result.outcome());
    }

    @Test void contentIsBoundedNormalizedAndSecretsAreRedacted(){
        String raw="Ｔｏｋｅｎ=abc123\u0000 password=pass api_key=key "
                +"Authorization: Bearer xyz789 "+"x".repeat(5000);
        var result=classifier.classify(raw);
        assertTrue(result.content().length()<=SteeringClassifier.MAX_CONTENT_CHARS);
        assertFalse(result.content().contains("abc123"));
        assertFalse(result.content().contains("pass"));
        assertFalse(result.content().contains("key"));
        assertFalse(result.content().contains("xyz789"));
        assertFalse(result.content().contains("\u0000"));
        assertEquals(result.content(),classifier.sanitize(result.content()));
        assertEquals(SteeringClassifier.sha(result.content()),result.contentHash());
    }
}
