package com.paicli.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.paicli.context.ContextModels.*;
import com.paicli.model.AgentMessage;
import com.paicli.model.ToolDefinition;
import com.paicli.runtime.PersistenceSanitizer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/** Deterministic compiler whose authority input is typed sources, never message ordinals. */
public final class DeterministicContextCompiler {
    public static final String COMPILER_VERSION="R3_CONTEXT_COMPILER_V4";
    public static final String RENDERER_VERSION="R3_RENDERER_V4";
    public static final String PRIVACY_VERSION="R3_PRIVACY_V3";
    public static final String BUDGET_ESTIMATOR_VERSION="R3_BUDGET_UTF8_V2";
    public static final String POLICY_VERSION=DeterministicCompactionPolicy.VERSION+":"
            +RENDERER_VERSION+":"+PRIVACY_VERSION+":"+BUDGET_ESTIMATOR_VERSION;
    public static final int MAX_SEGMENT_BYTES=64*1024;
    private final PersistenceSanitizer sanitizer=new PersistenceSanitizer();
    private final ContextBudgetResolver budgets=new ContextBudgetResolver();

    public CompiledContext compile(CompileRequest request){
        ContextInvocation invocation=request.invocation();
        if(!COMPILER_VERSION.equals(invocation.compilerVersion())
                ||!POLICY_VERSION.equals(invocation.policyVersion()))
            throw new IllegalArgumentException("CONTEXT_COMPILER_POLICY_MISMATCH");
        if(!java.util.Objects.equals(invocation.expectedPlanRevision(),invocation.identity().planRevision())
                ||!java.util.Objects.equals(invocation.expectedSteeringRevision(),
                        invocation.identity().steeringRevision()))
            throw new IllegalArgumentException("CONTEXT_IDENTITY_MISMATCH");
        if(invocation.operation()!=OperationKind.PLAN&&invocation.currentUserIntentSourceId()==null)
            throw new IllegalArgumentException("CONTEXT_SOURCE_INTEGRITY");
        if(invocation.operation()==OperationKind.TOOL_CONTINUATION
                &&invocation.toolResultSourceIds().isEmpty())
            throw new IllegalArgumentException("CONTEXT_SOURCE_INTEGRITY");
        if((invocation.operation()==OperationKind.REPAIR
                ||invocation.operation()==OperationKind.VERIFICATION)
                &&invocation.verificationRepairSourceId()==null)
            throw new IllegalArgumentException("CONTEXT_SOURCE_INTEGRITY");
        var ordered=request.sourceSet().sources().stream().sorted(ContextSourceSet.ORDER).toList();
        var sourceIds=ordered.stream().map(ContextSource::stableSourceId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if((invocation.currentUserIntentSourceId()!=null
                    &&!sourceIds.contains(invocation.currentUserIntentSourceId()))
                ||!sourceIds.containsAll(invocation.toolResultSourceIds())
                ||(invocation.verificationRepairSourceId()!=null
                    &&!sourceIds.contains(invocation.verificationRepairSourceId())))
            throw new IllegalArgumentException("CONTEXT_SOURCE_INTEGRITY");
        try{ContinuationSourceAuthority.requireExact(invocation.operation(),
                invocation.toolResultSourceIds(),ordered);}
        catch(IllegalArgumentException invalid){throw new IllegalArgumentException(
                "CONTEXT_SOURCE_INTEGRITY",invalid);}
        var messages=new ArrayList<AgentMessage>();
        var segments=new ArrayList<Segment>();
        int ordinal=0;
        for(ContextSource source:ordered){
            String clean=sanitizer.sanitizeAndTruncate(source.renderedPayload(),source.bounds());
            if(!clean.equals(source.renderedPayload())||!hash(clean).equals(source.contentHash()))
                throw new IllegalArgumentException("CONTEXT_SOURCE_INTEGRITY");
            String stored=source.payloadMode()==PayloadMode.INLINE_SANITIZED?clean:null;
            segments.add(new Segment(ordinal++,source.sourceClass(),source.kind(),source.scope(),
                    source.compactability(),source.payloadMode(),stored,source.contentHash(),
                    budgets.estimate(clean),source.privacyClass(),source.bounds(),source.references()));
            if(source.providerVisible())messages.add(new AgentMessage(source.role(),clean,
                    source.messageType(),source.toolCallId(),source.toolCallJson()));
        }
        var tools=request.tools().stream().map(this::canonicalTool)
                .sorted(Comparator.comparing(ToolDefinition::name)).toList();
        for(ToolDefinition tool:tools){
            String rendered=tool.name()+"|"+tool.description()+"|"+tool.parameterSchema();
            String clean=sanitizer.sanitizeAndTruncate(rendered,MAX_SEGMENT_BYTES);
            String sourceId="TOOL_SCHEMA:"+tool.name()+":"+hash(clean);
            segments.add(new Segment(ordinal++,SourceClass.NON_COMPACTABLE_AUTHORITY,
                    "TOOL_SCHEMA",Scope.RUN_SCOPED,Compactability.NON_COMPACTABLE,
                    PayloadMode.INLINE_SANITIZED,clean,hash(clean),budgets.estimate(clean),
                    PrivacyClass.SENSITIVE_REDACTED,MAX_SEGMENT_BYTES,
                    List.of(new SourceReference("CONTEXT_INLINE",sourceId,RENDERER_VERSION,hash(clean),0))));
        }
        String assembledFingerprint=sourceFingerprint(ordered);
        if(!assembledFingerprint.equals(request.sourceSet().closureFingerprint()))
            throw new IllegalArgumentException("CONTEXT_SOURCE_INTEGRITY");
        String actualFingerprint=sourceClosureFingerprint(segments);
        String compiledHash=hash(segmentSetHash(segments)+"|"+compiledHash(messages,tools));
        boolean compactable=segments.stream().anyMatch(s->s.compactability()==Compactability.COMPACTABLE);
        Budget budget=budgets.resolve(messages,tools,invocation.capability(),compactable);
        return new CompiledContext(invocation.identity(),invocation.operation(),invocation.provider(),
                invocation.model(),invocation.capability(),COMPILER_VERSION,POLICY_VERSION,messages,
                tools,segments,actualFingerprint,compiledHash,budget);
    }

    public static String sourceFingerprint(List<ContextSource> ordered){
        StringBuilder material=new StringBuilder();
        ordered.stream().sorted(ContextSourceSet.ORDER).forEach(s->material.append(s.priority()).append('|')
                .append(s.sequence()).append('|').append(s.sourceClass()).append('|').append(s.kind())
                .append('|').append(s.stableSourceId()).append('|').append(nullSafe(s.revision()))
                .append('|').append(s.authorityKind()).append('|').append(s.scope()).append('|')
                .append(s.compactability()).append('|').append(s.payloadMode()).append('|')
                .append(s.rendererVersion()).append('|').append(s.contentHash()).append('\n'));
        ordered.stream().sorted(ContextSourceSet.ORDER)
                .filter(s->s.toolCallJson()!=null)
                .forEach(s->material.append("TOOL_CALL_JSON|").append(s.stableSourceId()).append('|')
                        .append(hash(s.toolCallJson())).append('\n'));
        return hash(material.toString());
    }

    public static String segmentSetHash(List<? extends SegmentView> segments){
        StringBuilder material=new StringBuilder();
        for(SegmentView s:segments)material.append(s.ordinal()).append('|').append(s.sourceClass())
                .append('|').append(s.kind()).append('|').append(s.scope()).append('|')
                .append(s.compactability()).append('|').append(s.payloadMode()).append('|')
                .append(s.contentHash()).append('|').append(s.estimatedTokens()).append('|')
                .append(s.privacyClass()).append('|').append(s.bounds()).append('\n');
        return hash(material.toString());
    }

    public static String sourceClosureFingerprint(List<? extends ClosureSegmentView> segments){
        StringBuilder material=new StringBuilder();
        for(ClosureSegmentView s:segments){
            material.append(s.ordinal()).append('|').append(s.sourceClass()).append('|')
                    .append(s.kind()).append('|').append(s.scope()).append('|')
                    .append(s.compactability()).append('|').append(s.payloadMode()).append('|')
                    .append(s.contentHash()).append('\n');
            s.references().stream().sorted(Comparator.comparingInt(SourceReference::order))
                    .forEach(r->material.append("REF|").append(r.order()).append('|')
                            .append(r.type()).append('|').append(r.id()).append('|')
                            .append(nullSafe(r.revision())).append('|').append(r.hash()).append('\n'));
        }
        return hash(material.toString());
    }

    public interface SegmentView {
        int ordinal(); SourceClass sourceClass(); String kind(); Scope scope();
        Compactability compactability(); PayloadMode payloadMode(); String contentHash();
        int estimatedTokens(); PrivacyClass privacyClass(); int bounds();
    }
    public interface ClosureSegmentView extends SegmentView { List<SourceReference> references(); }

    private ToolDefinition canonicalTool(ToolDefinition tool){
        String name=sanitizer.sanitizeAndTruncate(tool.name(),128);
        String description=sanitizer.sanitizeAndTruncate(tool.description(),4096);
        return new ToolDefinition(name,description,canonicalJson(tool.parameterSchema()));
    }
    private JsonNode canonicalJson(JsonNode node){
        if(node==null)return JsonNodeFactory.instance.nullNode();
        if(node.isObject()){
            var result=JsonNodeFactory.instance.objectNode();var names=new ArrayList<String>();
            node.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name->result.set(name,canonicalJson(node.get(name))));return result;
        }
        if(node.isArray()){var result=JsonNodeFactory.instance.arrayNode();
            node.forEach(value->result.add(canonicalJson(value)));return result;}
        if(node.isTextual())return JsonNodeFactory.instance.textNode(
                sanitizer.sanitizeAndTruncate(node.textValue(),MAX_SEGMENT_BYTES));
        return node.deepCopy();
    }
    public static String compiledHash(List<AgentMessage> messages,List<ToolDefinition> tools){
        StringBuilder material=new StringBuilder();
        messages.forEach(m->material.append(m.role()).append('|').append(m.type()).append('|')
                .append(nullSafe(m.content())).append('|').append(nullSafe(m.toolCallId())).append('|')
                .append(m.toolCallJson()==null?"":hash(m.toolCallJson())).append('\n'));
        tools.forEach(t->material.append("TOOL|").append(t.name()).append('|')
                .append(t.description()).append('|').append(t.parameterSchema()).append('\n'));
        return hash(material.toString());
    }
    public static String hash(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception impossible){throw new IllegalStateException("SHA-256 unavailable",impossible);}
    }
    private static String nullSafe(String value){return value==null?"":value;}
}
