package com.paicli.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.context.ContextModels.CompactedLedgerEntry;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Fixed-order canonical JSON-lines rendering for Provider-visible compacted facts. */
public final class CompactedLedgerCodec {
    public static final String VERSION="R3_COMPACTED_LEDGER_V4";
    public static final String CHILD_COMPLETED_TOOL_VERSION=
            "R3_CHILD_COMPLETED_TOOL_HISTORY_V1";
    private static final int MAX_BOUNDED_PAYLOAD=DeterministicContextCompiler.MAX_SEGMENT_BYTES;
    private static final Set<String> SHORT_FIELDS=Set.of("entry_kind","source_kind","source_id",
            "source_revision","authority_provenance","bounded_provider_visible_payload",
            "source_content_hash");
    private static final Set<String> FULL_FIELDS=Set.of("entry_kind","source_kind","source_id",
            "source_revision","scope","authority_provenance","preserved_fact_type",
            "bounded_provider_visible_payload","preserved_decisions","unresolved_items",
            "evidence_references","tool_result_fields","artifact_references",
            "verification_references","repair_references","source_content_hash",
            "renderer_version","privacy_class","bounds");
    private static final ObjectMapper JSON=new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private CompactedLedgerCodec(){ }

    public static String encode(List<CompactedLedgerEntry> entries){
        StringBuilder out=new StringBuilder(VERSION).append('\n');
        for(CompactedLedgerEntry entry:entries)out.append(json(entry)).append('\n');
        return out.toString();
    }

    /** Strict immutable decode view. Authority and scope are validated but are
     * deliberately not exposed to projection callers. */
    public static List<DecodedEntry> decode(String encoded){
        try{
            if(encoded==null)throw malformed();
            String[] lines=encoded.split("\\R",-1);
            if(lines.length<2||!VERSION.equals(lines[0]))throw malformed();
            var decoded=new ArrayList<DecodedEntry>();
            for(int index=1;index<lines.length;index++){
                if(lines[index].isBlank()){
                    if(index==lines.length-1)continue;
                    throw malformed();
                }
                JsonNode node=JSON.readTree(lines[index]);
                if(node==null||!node.isObject())throw malformed();
                String sourceKind=text(node,"source_kind");
                Set<String> expected=Set.of("COMPLETED_TOOL_PAIR","DURABLE_RUNTIME_PROMPT")
                        .contains(sourceKind)?SHORT_FIELDS:FULL_FIELDS;
                if(!fields(node).equals(expected))throw malformed();
                ContextModels.LedgerFactKind.valueOf(text(node,"entry_kind"));
                String sourceId=text(node,"source_id");
                String revision=nullableText(node,"source_revision");
                text(node,"authority_provenance");
                String payload=text(node,"bounded_provider_visible_payload");
                if(payload.length()>MAX_BOUNDED_PAYLOAD)throw malformed();
                String hash=hash(node,"source_content_hash");
                if(expected==FULL_FIELDS)validateFull(node,payload);
                decoded.add(new DecodedEntry(sourceKind,sourceId,revision,payload,hash));
            }
            if(decoded.isEmpty())throw malformed();
            return List.copyOf(decoded);
        }catch(IllegalArgumentException invalid){
            if("COMPACTED_LEDGER_MALFORMED".equals(invalid.getMessage()))throw invalid;
            throw malformed();
        }catch(Exception invalid){throw malformed();}
    }

    /** Re-encodes only completed Tool history without parent scope, identity or
     * authority provenance. Trace remains in PARENT_CONTEXT_SEGMENT anchors. */
    public static String encodeChildSafeCompletedToolHistory(List<DecodedEntry> entries){
        StringBuilder out=new StringBuilder(CHILD_COMPLETED_TOOL_VERSION).append((char)10);
        int count=0;
        for(DecodedEntry entry:List.copyOf(entries)){
            if(!"COMPLETED_TOOL_PAIR".equals(entry.sourceKind()))continue;
            validateCompletedToolPayload(entry.boundedProviderVisiblePayload());
            ObjectNode node=JSON.createObjectNode();
            node.put("kind","COMPLETED_TOOL_PAIR");
            node.put("bounded_provider_visible_payload",entry.boundedProviderVisiblePayload());
            node.put("source_content_hash",entry.sourceContentHash());
            out.append(write(node)).append((char)10);count++;
        }
        if(count==0)throw new IllegalArgumentException("NO_CHILD_SAFE_TOOL_HISTORY");
        return out.toString();
    }

    private static void validateFull(JsonNode node,String payload){
        JsonNode scope=node.get("scope");
        if(scope==null||!scope.isObject()
                ||!fields(scope).equals(Set.of("workspace_id","thread_id","task_id","run_id")))
            throw malformed();
        text(scope,"workspace_id");text(scope,"thread_id");text(scope,"task_id");text(scope,"run_id");
        text(node,"preserved_fact_type");text(node,"renderer_version");
        ContextModels.PrivacyClass.valueOf(text(node,"privacy_class"));
        JsonNode bounds=node.get("bounds");
        if(bounds==null||!bounds.canConvertToInt()||bounds.intValue()<0
                ||payload.length()>bounds.intValue())throw malformed();
        strings(node,"preserved_decisions");strings(node,"unresolved_items");
        strings(node,"evidence_references");strings(node,"artifact_references");
        strings(node,"verification_references");strings(node,"repair_references");
        JsonNode tool=node.get("tool_result_fields");
        if(tool==null||!tool.isObject())throw malformed();
        tool.fields().forEachRemaining(field->{if(!field.getValue().isTextual())throw malformed();});
    }

    private static void validateCompletedToolPayload(String payload){
        if(payload==null||!payload.startsWith("kind=COMPLETED_TOOL_PAIR;")
                ||!payload.contains(";status=COMPLETED;")||!payload.contains(";digest="))
            throw malformed();
    }

    private static Set<String> fields(JsonNode node){
        var fields=new LinkedHashSet<String>();node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }
    private static String text(JsonNode node,String field){
        JsonNode value=node.get(field);
        if(value==null||!value.isTextual()||value.textValue().isBlank())throw malformed();
        return value.textValue();
    }
    private static String nullableText(JsonNode node,String field){
        JsonNode value=node.get(field);
        if(value==null||(!value.isNull()&&!value.isTextual())
                ||value.isTextual()&&value.textValue().isBlank())throw malformed();
        return value.isNull()?null:value.textValue();
    }
    private static String hash(JsonNode node,String field){
        String value=text(node,field);
        if(!value.matches("[0-9a-f]{64}"))throw malformed();
        return value;
    }
    private static void strings(JsonNode node,String field){
        JsonNode value=node.get(field);if(value==null||!value.isArray())throw malformed();
        for(JsonNode item:value)if(!item.isTextual())throw malformed();
    }
    private static IllegalArgumentException malformed(){
        return new IllegalArgumentException("COMPACTED_LEDGER_MALFORMED");
    }

    private static String json(CompactedLedgerEntry value){
        if("COMPLETED_TOOL_PAIR".equals(value.sourceKind())
                ||"DURABLE_RUNTIME_PROMPT".equals(value.sourceKind())){
            ObjectNode out=JSON.createObjectNode();
            out.put("entry_kind",value.entryKind().name());
            out.put("source_kind",value.sourceKind());
            out.put("source_id",value.sourceId());
            putNullable(out,"source_revision",value.sourceRevision());
            out.put("authority_provenance",value.authorityProvenance());
            out.put("bounded_provider_visible_payload",
                    value.boundedProviderVisiblePayload());
            out.put("source_content_hash",value.sourceContentHash());
            return write(out);
        }
        ObjectNode out=JSON.createObjectNode();
        out.put("entry_kind",value.entryKind().name());out.put("source_kind",value.sourceKind());
        out.put("source_id",value.sourceId());putNullable(out,"source_revision",value.sourceRevision());
        ObjectNode scope=out.putObject("scope");scope.put("workspace_id",value.workspaceId());
        scope.put("thread_id",value.threadId());scope.put("task_id",value.taskId());
        scope.put("run_id",value.runId());out.put("authority_provenance",value.authorityProvenance());
        out.put("preserved_fact_type",value.preservedFactType());
        out.put("bounded_provider_visible_payload",value.boundedProviderVisiblePayload());
        array(out,"preserved_decisions",value.preservedDecisions());
        array(out,"unresolved_items",value.unresolvedItems());
        array(out,"evidence_references",value.evidenceReferences());
        ObjectNode tool=out.putObject("tool_result_fields");
        new TreeMap<>(value.toolResultFields()).forEach(tool::put);
        array(out,"artifact_references",value.artifactReferences());
        array(out,"verification_references",value.verificationReferences());
        array(out,"repair_references",value.repairReferences());
        out.put("source_content_hash",value.sourceContentHash());
        out.put("renderer_version",value.rendererVersion());
        out.put("privacy_class",value.privacyClass().name());out.put("bounds",value.bounds());
        return write(out);
    }
    private static String write(ObjectNode value){
        try{return JSON.writeValueAsString(value);}catch(JsonProcessingException impossible){
            throw new IllegalStateException("canonical ledger rendering failed",impossible);}
    }
    private static void array(ObjectNode owner,String name,List<String> values){ArrayNode out=owner.putArray(name);
        values.forEach(out::add);}
    private static void putNullable(ObjectNode owner,String name,String value){if(value==null)owner.putNull(name);
        else owner.put(name,value);}

    public record DecodedEntry(String sourceKind,String sourceId,String sourceRevision,
            String boundedProviderVisiblePayload,String sourceContentHash){
        public DecodedEntry{
            Objects.requireNonNull(sourceKind);Objects.requireNonNull(sourceId);
            Objects.requireNonNull(boundedProviderVisiblePayload);Objects.requireNonNull(sourceContentHash);
            if(sourceKind.isBlank()||sourceId.isBlank()
                    ||sourceRevision!=null&&sourceRevision.isBlank()
                    ||boundedProviderVisiblePayload.isBlank()
                    ||boundedProviderVisiblePayload.length()>MAX_BOUNDED_PAYLOAD
                    ||!sourceContentHash.matches("[0-9a-f]{64}"))throw malformed();
        }
    }
}
