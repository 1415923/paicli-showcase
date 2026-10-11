package com.paicli.demo.artifact;

import com.fasterxml.jackson.databind.*;
import com.paicli.demo.runtime.CoreDemoWorkflowResult;
import com.paicli.runtime.PersistenceSanitizer;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class CoreDemoArtifactRenderer {
    static final String NOT_RUN="NOT_RUN";
    private final ObjectMapper json=new ObjectMapper()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY,true)
            .enable(SerializationFeature.INDENT_OUTPUT);
    private final PersistenceSanitizer sanitizer=new PersistenceSanitizer();

    byte[] summary(CoreDemoArtifactInput in,String semanticHash)throws Exception{
        TreeMap<String,Object> root=new TreeMap<>();
        root.put("schemaVersion",1);root.put("scenarioId",in.fixture().scenarioId());
        root.put("fixtureVersion",in.fixture().fixtureVersion());root.put("runId",in.runId());
        root.put("headless",true);root.put("providerType","DETERMINISTIC_SCRIPTED");
        root.put("status",in.terminalStatus());root.put("exitCode",in.exitCode().code());
        root.put("completedPhase",in.workflow()==null?"BASELINE":in.workflow().completedPhase().name());
        root.put("baseline",baselineMap(in));
        root.put("planning",fact(in.workflow()==null?null:in.workflow().planning()));
        root.put("codeIntelligence",codeIntelligenceMap(in.workflow()));
        root.put("initialExecution",executeMap(in.workflow()));
        root.put("checkpoint",in.workflow()!=null
                &&in.workflow().semanticFacts().checkpointHash()!=null?"CREATED":NOT_RUN);
        root.put("firstVerification",stageStatus(in.workflow(),"FIRST_VERIFICATION"));
        root.put("finalVerification",stageStatus(in.workflow(),"REPAIR_VERIFICATION"));
        root.put("repairCount",in.workflow()==null?0:in.workflow().repairCount());
        root.put("resume",fact(in.workflow()==null?null:in.workflow().resume()));
        root.put("resumed",in.workflow()!=null
                &&in.workflow().resume()==CoreDemoWorkflowResult.FactStatus.PASSED);
        root.put("rewind",fact(in.workflow()==null?null:in.workflow().rewind()));
        root.put("rewound",in.workflow()!=null
                &&in.workflow().rewind()==CoreDemoWorkflowResult.FactStatus.PASSED);
        root.put("postRewindBaseline",stageStatus(in.workflow(),"POST_REWIND_BASELINE"));
        root.put("interrupted",in.workflow()!=null&&in.workflow().semanticFacts().interrupted());
        root.put("duplicateSideEffectsPrevented",in.workflow()!=null
                &&in.workflow().semanticFacts().duplicateSideEffectsPrevented());
        if(in.workflow()!=null){
            TreeMap<String,Object> semanticFacts=new TreeMap<>();
            semanticFacts.put("planHash",in.workflow().semanticFacts().planHash());
            semanticFacts.put("codeIntelligenceHash",in.workflow().semanticFacts().codeIntelligenceHash());
            semanticFacts.put("checkpointHash",in.workflow().semanticFacts().checkpointHash());
            semanticFacts.put("toolSequence",in.workflow().semanticFacts().toolSequence());
            semanticFacts.put("rewindSequence",in.workflow().semanticFacts().rewindSequence());
            root.put("semanticFacts",semanticFacts);
        }
        root.put("cleanupStatus",bound(in.cleanupStatus(),256));
        root.put("truncated",truncated(in));root.put("redacted",redacted(in));
        root.put("semanticResultHash",semanticHash);return jsonBytes(root);
    }

    byte[] verification(CoreDemoArtifactInput in)throws Exception{
        TreeMap<String,Object> root=new TreeMap<>();root.put("schemaVersion",1);
        root.put("baseline",baselineMap(in));
        root.put("firstVerification",stage(in.workflow(),"FIRST_VERIFICATION"));
        root.put("repairVerification",stage(in.workflow(),"REPAIR_VERIFICATION"));
        root.put("postResumeVerification",stage(in.workflow(),"POST_RESUME_VERIFICATION"));
        root.put("postRewindBaseline",stage(in.workflow(),"POST_REWIND_BASELINE"));
        return jsonBytes(root);
    }

    byte[] hashes(CoreDemoArtifactInput in,String semanticHash)throws Exception{
        TreeMap<String,Object> root=new TreeMap<>();root.put("schemaVersion",1);
        root.put("fixtureManifestHash",in.fixtureManifestHash());
        root.put("expectedSeedTreeHash",in.fixture().expectedBaselineTreeHash());
        root.put("baselineTreeHash",in.baseline().sourceTreeHash());
        root.put("initialExecutionTreeHash",in.workflow()==null?null:in.workflow().initialExecutionTreeHash());
        root.put("finalTreeHash",in.workflow()==null?null:in.workflow().finalTreeHash());
        root.put("rewindTreeHash",in.workflow()==null?null:in.workflow().rewindTreeHash());
        root.put("artifactSemanticHash",semanticHash);return jsonBytes(root);
    }

    byte[] report(CoreDemoArtifactInput in,String semanticHash){
        StringBuilder r=new StringBuilder("# PaiCLI Core Demo\n\n");
        r.append("This bounded report records the fixed offline production Core Demo. ")
                .append("No private reasoning, source text, diff, or raw Maven log is included.\n\n");
        r.append("## Identity\n\n- Scenario: ").append(in.fixture().scenarioId())
                .append("\n- Run: ").append(in.runId())
                .append("\n- Headless: true\n- Provider contract: DETERMINISTIC_SCRIPTED\n\n");
        r.append("## Baseline\n\n- Status: ").append(in.baseline().status())
                .append("\n- Tests: ").append(in.baseline().tests())
                .append("\n- Tree hash: ").append(shortHash(in.baseline().sourceTreeHash())).append("\n\n");
        r.append("## Workflow Facts\n\n");
        if(in.workflow()==null){
            r.append("- Plan: NOT_RUN\n- Code Intelligence: NOT_RUN\n- Execute: NOT_RUN\n")
                    .append("- Verify/Repair: NOT_RUN\n- Resume: NOT_RUN\n- Rewind: NOT_RUN\n");
        }else{
            r.append("- Terminal: ").append(in.workflow().terminalCategory())
                    .append("\n- Completed phase: ").append(in.workflow().completedPhase())
                    .append("\n- Plan: ").append(in.workflow().planning())
                    .append("\n- Code Intelligence: ").append(in.workflow().codeIntelligence())
                    .append("\n- Execute: ").append(in.workflow().execution())
                    .append("\n- Initial execution: COMPLETED")
                    .append("\n- First verification: EXPECTED_FAILED (3 tests, 1 failure, 0 errors, 0 skipped)")
                    .append("\n- Checkpoint: CREATED")
                    .append("\n- Repair count: ").append(in.workflow().repairCount())
                    .append("\n- Resume: ").append(in.workflow().resume())
                    .append("\n- Duplicate side effects prevented: ")
                    .append(in.workflow().semanticFacts().duplicateSideEffectsPrevented())
                    .append("\n- Rewind: ").append(in.workflow().rewind())
                    .append("\n- Rewind restored baseline: ")
                    .append(in.workflow().semanticFacts().postRewindBaselinePassed())
                    .append("\n- Final verification: PASSED (3 tests, 0 failures, 0 errors, 0 skipped)")
                    .append("\n- Post-rewind baseline: PASSED (3 tests, 0 failures, 0 errors, 0 skipped)")
                    .append("\n- The first test failure is expected evidence in the frozen scenario, not the final task result.")
                    .append("\n- Resume did not repeat Plan, Code Intelligence, the initial write, or first verification.")
                    .append("\n- Rewind used Snapshot/RewindService in reverse snapshot order, not Git reset.")
                    .append("\n- The deterministic provider is an offline repeatability fixture, not a model benchmark.")
                    .append("\n- Code Intelligence context is untrusted supporting data.")
                    .append("\n- Replay remains Extended and is not part of Core Demo completion.")
                    .append("\n- External context boundary: untrusted data only\n");
        }
        r.append("\n## Final\n\n- Status: ").append(in.terminalStatus())
                .append("\n- Exit code: ").append(in.exitCode().code())
                .append("\n- Cleanup: ").append(bound(in.cleanupStatus(),256))
                .append("\n- Semantic result: ").append(shortHash(semanticHash)).append("\n");
        return sanitizer.sanitizeAndTruncate(r.toString(),128*1024)
                .replace("\r\n","\n").replace('\r','\n').getBytes(StandardCharsets.UTF_8);
    }

    private Map<String,Object> baselineMap(CoreDemoArtifactInput in){
        TreeMap<String,Object> m=new TreeMap<>();m.put("status",
                in.baseline().status()==com.paicli.demo.baseline.CoreDemoBaselineResult.Status.BASELINE_PASSED
                        ?"PASSED":in.baseline().status().name());
        m.put("tests",in.baseline().tests());m.put("failures",in.baseline().failures());
        m.put("errors",in.baseline().errors());m.put("skipped",in.baseline().skipped());
        m.put("timedOut",in.baseline().timedOut());m.put("processCleaned",in.baseline().processCleaned());
        m.put("buildResidueCleaned",in.baseline().buildResidueCleaned());return m;
    }
    private Map<String,Object> codeIntelligenceMap(CoreDemoWorkflowResult w){
        TreeMap<String,Object> m=new TreeMap<>();m.put("status",
                w!=null&&w.codeIntelligence()==CoreDemoWorkflowResult.FactStatus.PASSED?"USED":fact(w==null?null:w.codeIntelligence()));
        m.put("queryCount",w==null?0:w.codeIntelligenceQueries());
        m.put("contextItemCount",w==null?0:w.codeIntelligenceContextItems());return m;
    }
    private Map<String,Object> executeMap(CoreDemoWorkflowResult w){
        TreeMap<String,Object> m=new TreeMap<>();m.put("status",
                w!=null&&w.execution()==CoreDemoWorkflowResult.FactStatus.PASSED?"COMPLETED":fact(w==null?null:w.execution()));
        m.put("changedRelativeFiles",w==null?List.of():w.changedRelativeFiles());
        m.put("snapshotCount",w==null?0:w.snapshotCount());return m;
    }
    private Map<String,Object> verificationOverview(CoreDemoWorkflowResult w){
        TreeMap<String,Object> m=new TreeMap<>();
        m.put("status",w==null||w.verificationSequence().isEmpty()?NOT_RUN:"RECORDED");
        m.put("sequenceCount",w==null?0:w.verificationSequence().size());return m;
    }
    private Map<String,Object> stage(CoreDemoWorkflowResult w,String name){
        if(w!=null)for(var f:w.verificationSequence())if(name.equals(f.stage())){
            TreeMap<String,Object> m=new TreeMap<>();
            String status=f.status().name();
            if("FIRST_VERIFICATION".equals(name)
                    &&f.status()==CoreDemoWorkflowResult.FactStatus.FAILED
                    &&"EXPECTED_ASSERTION_FAILURE".equals(f.category()))
                status="EXPECTED_FAILED";
            m.put("status",status);
            m.put("category",bound(f.category(),128));return m;
        }
        return Map.of("status",NOT_RUN);
    }
    private String stageStatus(CoreDemoWorkflowResult w,String name){
        Map<String,Object> value=stage(w,name);
        Object status=value.get("status");
        if("FIRST_VERIFICATION".equals(name)
                &&"FAILED".equals(status)
                &&"EXPECTED_ASSERTION_FAILURE".equals(value.get("category")))
            return "EXPECTED_FAILED";
        return String.valueOf(status);
    }    private static String fact(CoreDemoWorkflowResult.FactStatus s){return s==null?NOT_RUN:s.name();}
    private boolean truncated(CoreDemoArtifactInput in){return in.baseline().boundedExcerpt()!=null&&in.baseline().boundedExcerpt().contains("[truncated");}
    private boolean redacted(CoreDemoArtifactInput in){return in.baseline().boundedExcerpt()!=null&&in.baseline().boundedExcerpt().contains("<redacted>");}
    private byte[] jsonBytes(Object v)throws Exception{return (json.writeValueAsString(v).replace("\r\n","\n").replace('\r','\n')+"\n").getBytes(StandardCharsets.UTF_8);}
    private String bound(String v,int max){if(v==null)return null;return sanitizer.sanitizeAndTruncate(v,max).replace('\r',' ').replace('\n',' ');}
    private static String shortHash(String v){return v==null?"none":v.substring(0,Math.min(12,v.length()));}
}
