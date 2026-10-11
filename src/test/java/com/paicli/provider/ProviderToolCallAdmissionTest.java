package com.paicli.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProviderToolCallAdmissionTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void zeroAndOneCallsHaveExplicitAdmission() throws Exception {
        var zero = ProviderToolCallAdmission.inspect(JSON.readTree("[]"));
        assertEquals(ProviderResponseEnvelope.ToolCallCardinality.ZERO,
                zero.envelope().cardinality());
        assertNull(zero.admitted());

        var one = ProviderToolCallAdmission.inspect(JSON.readTree("""
                [{"id":"call-1","function":{"name":"read_file","arguments":{"path":"x"}}}]
                """));
        assertEquals(ProviderResponseEnvelope.ToolCallCardinality.ONE,
                one.envelope().cardinality());
        assertEquals("read_file", one.admitted().toolCall().name());
    }

    @Test
    void invalidCallsAreNeverPartiallyAdmittedAndValidBatchIsFullyAdmitted()
            throws Exception {
        var missing = ProviderToolCallAdmission.inspect(JSON.readTree("""
                [{"function":{"name":"read_file","arguments":{"path":"x"}}}]
                """));
        assertInvalid(missing);
        assertTrue(missing.envelope().diagnostic().missingIdentity());

        var duplicate = ProviderToolCallAdmission.inspect(JSON.readTree("""
                [{"id":"same","function":{"name":"read_file","arguments":{}}},
                 {"id":"same","function":{"name":"read_file","arguments":{}}}]
                """));
        assertInvalid(duplicate);
        assertTrue(duplicate.envelope().diagnostic().duplicate());

        var malformed = ProviderToolCallAdmission.inspect(JSON.readTree("""
                [{"id":"call-1","function":{"name":"","arguments":{}}}]
                """));
        assertInvalid(malformed);
        assertTrue(malformed.envelope().diagnostic().malformed());

        var multiple = ProviderToolCallAdmission.inspect(JSON.readTree("""
                [{"id":"call-1","function":{"name":"read_file","arguments":{}}},
                 {"id":"call-2","function":{"name":"list_files","arguments":{}}}]
                """));
        assertEquals(ProviderResponseEnvelope.ToolCallCardinality.MULTIPLE,
                multiple.envelope().cardinality());
        assertEquals(2, multiple.toolCalls().size());
        assertEquals(java.util.List.of("read_file", "list_files"), multiple.toolCalls().stream()
                .map(com.paicli.model.ToolCall::name).toList());
    }

    private static void assertInvalid(ProviderToolCallAdmission.Result result) {
        assertEquals(ProviderResponseEnvelope.ToolCallCardinality.INVALID,
                result.envelope().cardinality());
        assertNull(result.admitted());
    }
}
