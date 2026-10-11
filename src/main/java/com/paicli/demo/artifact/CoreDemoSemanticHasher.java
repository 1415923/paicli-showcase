package com.paicli.demo.artifact;

import com.fasterxml.jackson.databind.*;
import com.paicli.demo.runtime.CoreDemoWorkflowResult;
import com.paicli.demo.runtime.CoreDemoSemanticFacts;
import java.security.MessageDigest;
import java.util.*;

public final class CoreDemoSemanticHasher {
    private static final ObjectMapper JSON=new ObjectMapper()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY,true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true);
    public String hash(CoreDemoArtifactInput in) {
        try {
            TreeMap<String,Object> f=new TreeMap<>();
            f.put("schemaVersion",1);f.put("scenarioId",in.fixture().scenarioId());
            f.put("fixtureVersion",in.fixture().fixtureVersion());
            f.put("baselineStatus",in.baseline().status().name());
            f.put("baselineTests",in.baseline().tests());
            f.put("baselineTreeHash",in.baseline().sourceTreeHash());
            f.put("terminalStatus",in.terminalStatus());
            f.put("exitCodeCategory",in.exitCode().name());
            CoreDemoWorkflowResult w=in.workflow();
            if(w==null){
                f.put("completedPhase","BASELINE");f.put("planning","NOT_RUN");
                f.put("codeIntelligence","NOT_RUN");f.put("execution","NOT_RUN");
                f.put("changedRelativeFiles",List.of());f.put("verificationSequence",List.of());
                f.put("repairCount",0);f.put("resume","NOT_RUN");f.put("rewind","NOT_RUN");
            } else {
                f.put("completedPhase",w.completedPhase().name());f.put("planning",w.planning().name());
                f.put("codeIntelligence",w.codeIntelligence().name());
                f.put("codeIntelligenceQueries",w.codeIntelligenceQueries());
                f.put("codeIntelligenceContextItems",w.codeIntelligenceContextItems());
                f.put("execution",w.execution().name());f.put("changedRelativeFiles",w.changedRelativeFiles());
                f.put("snapshotCount",w.snapshotCount());
                f.put("verificationSequence",w.verificationSequence().stream()
                        .map(v->List.of(v.stage(),v.status().name(),v.category()==null?"":v.category())).toList());
                f.put("repairCount",w.repairCount());f.put("resume",w.resume().name());
                f.put("rewind",w.rewind().name());f.put("initialExecutionTreeHash",w.initialExecutionTreeHash());
                f.put("finalTreeHash",w.finalTreeHash());f.put("rewindTreeHash",w.rewindTreeHash());
                f.put("workflowTerminal",w.terminalCategory().name());
                CoreDemoSemanticFacts s=w.semanticFacts();
                f.put("planHash",s.planHash());
                f.put("codeIntelligenceHash",s.codeIntelligenceHash());
                f.put("checkpointHash",s.checkpointHash());
                f.put("toolSequence",s.toolSequence());
                f.put("rewindSequence",s.rewindSequence());
                f.put("interrupted",s.interrupted());
                f.put("duplicateSideEffectsPrevented",s.duplicateSideEffectsPrevented());
                f.put("postRewindBaselinePassed",s.postRewindBaselinePassed());
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsBytes(f)));
        } catch(Exception e){throw new IllegalStateException("Could not hash Core Demo semantic result",e);}
    }
}
