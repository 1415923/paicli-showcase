package com.paicli.runtime;

import com.paicli.state.FollowUpModels.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ManifestDiffRiskClassifierTest {
    private final ManifestDiffRiskClassifier classifier=new ManifestDiffRiskClassifier();
    @Test void lowHighUnknownAndBlockingAreDeterministic(){
        assertEquals(Risk.LOW,classifier.classify(List.of(diff(DiffCategory.UNCHANGED,"AGENTS.md","a","a"))).risk());
        assertEquals(Risk.HIGH,classifier.classify(List.of(diff(DiffCategory.CHANGED,"AGENTS.md","a","b"))).risk());
        assertEquals(Risk.UNKNOWN,classifier.classify(List.of(new ManifestDiff(DiffCategory.CHANGED,"AGENTS.md",null,
                "b".repeat(64),"changed","test"))).risk());
        assertEquals(Risk.BLOCKING,classifier.classify(List.of(diff(DiffCategory.ADDED,"../outside","a","b"))).risk());
    }
    private static ManifestDiff diff(DiffCategory category,String path,String before,String after){return new ManifestDiff(category,path,
            before.repeat(64),after.repeat(64),category.name(),"test");}
}
