package com.paicli.demo.runtime;

import java.util.List;

public record CoreDemoSemanticFacts(
        String planHash,
        String codeIntelligenceHash,
        String checkpointHash,
        String firstVerificationEvidenceHash,
        String finalVerificationEvidenceHash,
        List<String> toolSequence,
        List<String> rewindSequence,
        boolean interrupted,
        boolean duplicateSideEffectsPrevented,
        boolean postRewindBaselinePassed) {
    public CoreDemoSemanticFacts {
        planHash=optionalSha(planHash);
        codeIntelligenceHash=optionalSha(codeIntelligenceHash);
        checkpointHash=optionalSha(checkpointHash);
        firstVerificationEvidenceHash=optionalSha(firstVerificationEvidenceHash);
        finalVerificationEvidenceHash=optionalSha(finalVerificationEvidenceHash);
        toolSequence=List.copyOf(toolSequence==null?List.of():toolSequence);
        rewindSequence=List.copyOf(rewindSequence==null?List.of():rewindSequence);
        if(toolSequence.size()>32||rewindSequence.size()>8)
            throw new IllegalArgumentException("Semantic sequence exceeds bounds");
    }
    public static CoreDemoSemanticFacts empty(){
        return new CoreDemoSemanticFacts(null,null,null,null,null,List.of(),List.of(),
                false,false,false);
    }
    private static String optionalSha(String value){
        if(value!=null&&!value.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Semantic fact is not SHA-256");
        return value;
    }
}