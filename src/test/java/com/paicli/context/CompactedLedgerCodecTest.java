package com.paicli.context;

import com.paicli.context.ContextModels.CompactedLedgerEntry;
import com.paicli.context.ContextModels.LedgerFactKind;
import com.paicli.context.ContextModels.PrivacyClass;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CompactedLedgerCodecTest {
    private static final String HASH="a".repeat(64);

    @Test void strictDecodeAndChildEncodingExposeOnlyCompletedToolHistory(){
        String encoded=CompactedLedgerCodec.encode(List.of(
                entry("COMPLETED_TOOL_PAIR","parent-event-1",
                        "kind=COMPLETED_TOOL_PAIR;tool_name=read_file;arguments_summary={};"
                                +"status=COMPLETED;result_excerpt=FIRST MIDDLE LAST PROHIBITION "
                                +"UNFINISHED;occurrence_id=occ-parent;digest="+"b".repeat(64)),
                entry("PLAN_STEP","plan-parent","PARENT_PLAN_PRIVATE"),
                entry("EVIDENCE","evidence-parent","PARENT_EVIDENCE_PRIVATE"),
                entry("VERIFICATION_RESULT","verify-parent","PARENT_VERIFICATION_PRIVATE"),
                entry("REPAIR_ITERATION","repair-parent","PARENT_REPAIR_PRIVATE"),
                entry("DURABLE_ASSISTANT_RESULT","assistant-parent","PARENT_ASSISTANT_PRIVATE")));

        List<CompactedLedgerCodec.DecodedEntry> decoded=CompactedLedgerCodec.decode(encoded);
        assertEquals(6,decoded.size());
        String child=CompactedLedgerCodec.encodeChildSafeCompletedToolHistory(decoded);
        assertTrue(child.startsWith(CompactedLedgerCodec.CHILD_COMPLETED_TOOL_VERSION));
        for(String marker:List.of("FIRST","MIDDLE","LAST","PROHIBITION","UNFINISHED",HASH))
            assertTrue(child.contains(marker),marker);
        for(String forbidden:List.of("parent-event-1","parent-workspace","parent-thread",
                "parent-task","parent-run","PARENT_AUTHORITY_PRIVATE","PARENT_PLAN_PRIVATE",
                "PARENT_EVIDENCE_PRIVATE","PARENT_VERIFICATION_PRIVATE",
                "PARENT_REPAIR_PRIVATE","PARENT_ASSISTANT_PRIVATE"))
            assertFalse(child.contains(forbidden),forbidden);
    }

    @Test void malformedVersionFieldsAndDuplicateKeysFailClosed(){
        String valid=CompactedLedgerCodec.encode(List.of(entry("COMPLETED_TOOL_PAIR","event",
                "kind=COMPLETED_TOOL_PAIR;tool_name=read_file;arguments_summary={};"
                        +"status=COMPLETED;result_excerpt=ok;occurrence_id=occ;digest="
                        +"b".repeat(64))));
        assertMalformed(valid.replace(CompactedLedgerCodec.VERSION,"R3_COMPACTED_LEDGER_V3"));
        assertMalformed("""
                R3_COMPACTED_LEDGER_V4
                {"entry_kind":"STRUCTURED_DERIVED","source_kind":"COMPLETED_TOOL_PAIR"}
                """);
        assertMalformed("""
                R3_COMPACTED_LEDGER_V4
                {"entry_kind":"STRUCTURED_DERIVED","entry_kind":"STRUCTURED_DERIVED",
                 "source_kind":"COMPLETED_TOOL_PAIR","source_id":"event","source_revision":"1",
                 "authority_provenance":"private","bounded_provider_visible_payload":"safe",
                 "source_content_hash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                """);
    }

    private static void assertMalformed(String encoded){
        IllegalArgumentException failure=assertThrows(IllegalArgumentException.class,
                ()->CompactedLedgerCodec.decode(encoded));
        assertEquals("COMPACTED_LEDGER_MALFORMED",failure.getMessage());
    }

    private static CompactedLedgerEntry entry(String kind,String sourceId,String payload){
        return new CompactedLedgerEntry(LedgerFactKind.STRUCTURED_DERIVED,kind,sourceId,"1",
                "parent-workspace","parent-thread","parent-task","parent-run",
                "PARENT_AUTHORITY_PRIVATE","PARENT_FACT_PRIVATE",payload,
                List.of("PARENT_DECISION_PRIVATE"),List.of("PARENT_UNRESOLVED_PRIVATE"),
                List.of("PARENT_EVIDENCE_REF_PRIVATE"),Map.of("private","PARENT_TOOL_PRIVATE"),
                List.of("PARENT_ARTIFACT_PRIVATE"),List.of("PARENT_VERIFY_REF_PRIVATE"),
                List.of("PARENT_REPAIR_REF_PRIVATE"),HASH,"renderer",PrivacyClass.INTERNAL,65_536);
    }
}
