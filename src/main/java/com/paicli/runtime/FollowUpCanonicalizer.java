package com.paicli.runtime;

import com.paicli.state.FollowUpModels.CapsuleFact;
import com.paicli.state.FollowUpModels.ParentAnchor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Versioned deterministic Capsule canonical domain; identity and creation time are excluded. */
public final class FollowUpCanonicalizer {
    public static final int SCHEMA_VERSION=1;
    private static final int MAX_FACT_BYTES=2048;
    private final PersistenceSanitizer sanitizer=new PersistenceSanitizer();

    public String canonicalText(ParentAnchor anchor,List<CapsuleFact> facts){
        StringBuilder value=new StringBuilder("PAICLI_CAPSULE_V1\n");
        add(value,Integer.toString(SCHEMA_VERSION));add(value,anchor.threadId().value());
        add(value,anchor.taskId().value());add(value,anchor.status());
        add(value,anchor.terminalRunId().value());add(value,anchor.generationId().value());
        add(value,anchor.completionAnchorType());add(value,anchor.completionAnchorId());
        add(value,anchor.manifestId());add(value,anchor.manifestHash());
        add(value,anchor.planRevisionId()==null?null:anchor.planRevisionId().value());
        add(value,anchor.planHash());
        add(value,Long.toString(anchor.completionEvaluationNumber()));
        add(value,anchor.completionRulesHash());
        anchor.completionEvidenceHashes().stream().sorted().forEach(hash->add(value,hash));
        canonicalFacts(facts).forEach(f->{add(value,f.type().name());add(value,f.scopeIdentity());
            add(value,bound(f.boundedPayload()));add(value,f.referenceIdentity());
            add(value,f.referenceHash());add(value,f.provenance());});
        return value.toString();
    }
    public String hash(ParentAnchor anchor,List<CapsuleFact> facts){return sha256(canonicalText(anchor,facts));}
    public List<CapsuleFact> canonicalFacts(List<CapsuleFact> facts){
        List<CapsuleFact> normalized=facts.stream().map(f->new CapsuleFact(f.type(),normalize(f.scopeIdentity()),
                bound(f.boundedPayload()),normalize(f.referenceIdentity()),f.referenceHash(),
                normalize(f.provenance()))).toList();
        if(normalized.stream().distinct().count()!=normalized.size())
            throw new IllegalArgumentException("Duplicate Capsule Fact");
        return normalized.stream().sorted(Comparator
                .comparing((CapsuleFact f)->f.type().name()).thenComparing(f->safe(f.scopeIdentity()))
                .thenComparing(f->safe(f.referenceIdentity())).thenComparing(f->safe(f.boundedPayload()))).toList();
    }
    public String sanitizeInstruction(String value){
        String normalized=normalize(value);
        String safe=sanitizer.sanitizeAndTruncate(normalized,4096);
        if(safe==null||safe.isBlank())throw new IllegalArgumentException("Follow-up instruction is required");
        return safe;
    }
    public String sha256(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException("SHA-256 unavailable",e);}
    }
    private String bound(String value){return sanitizer.sanitizeAndTruncate(normalize(value),MAX_FACT_BYTES);}
    private static String normalize(String value){return value==null?null:Normalizer.normalize(value,Normalizer.Form.NFKC)
            .replaceAll("[\\p{Cc}&&[^\\r\\n\\t]]"," ");}
    private static String safe(String value){return value==null?"":value;}
    private static void add(StringBuilder out,String value){
        if(value==null){out.append("-1:\n");return;}out.append(value.length()).append(':').append(value).append('\n');
    }
}
