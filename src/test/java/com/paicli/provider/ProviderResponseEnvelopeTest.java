package com.paicli.provider;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProviderResponseEnvelopeTest {
    @Test void capturesCardinalityWithoutRawBody() {
        assertEquals(ProviderResponseEnvelope.ToolCallCardinality.ZERO,
                ProviderResponseEnvelope.inspect(List.of(), false).cardinality());
        assertEquals(ProviderResponseEnvelope.ToolCallCardinality.ONE,
                ProviderResponseEnvelope.inspect(List.of("call-1"), false).cardinality());
        var multiple = ProviderResponseEnvelope.inspect(List.of("call-1", "call-2"), false);
        assertEquals(ProviderResponseEnvelope.ToolCallCardinality.MULTIPLE, multiple.cardinality());
        assertNull(multiple.diagnostic().code());
        var overLimit = ProviderResponseEnvelope.inspect(
                java.util.stream.IntStream.rangeClosed(0, 8)
                        .mapToObj(i -> "call-" + i).toList(), false);
        assertEquals("TOOL_CALL_BATCH_LIMIT_EXCEEDED", overLimit.diagnostic().code());
        var invalid = ProviderResponseEnvelope.inspect(List.of("call-1", "call-1"), false);
        assertEquals(ProviderResponseEnvelope.ToolCallCardinality.INVALID, invalid.cardinality());
        assertEquals("INVALID_TOOL_CALL_RESPONSE", invalid.diagnostic().code());
    }

    @Test void malformedMissingDuplicateAndUnsafeIdentitiesAreBounded() {
        assertTrue(ProviderResponseEnvelope.inspect(null, true).diagnostic().malformed());
        assertTrue(ProviderResponseEnvelope.inspect(java.util.Arrays.asList("call", null), false)
                .diagnostic().missingIdentity());
        assertTrue(ProviderResponseEnvelope.inspect(List.of("call", "call"), false)
                .diagnostic().duplicate());
        assertEquals(8, ProviderResponseEnvelope.inspect(
                java.util.stream.IntStream.range(0, 20).mapToObj(i -> "call-" + i).toList(), false)
                .safeCallIds().size());
        var unsafe = ProviderResponseEnvelope.inspect(List.of("secret bearing"), false);
        assertEquals(1, unsafe.safeCallIds().size());
        assertTrue(unsafe.safeCallIds().getFirst().startsWith("redacted-sha256-"));
        assertFalse(unsafe.toString().contains("secret bearing"));
        var syntacticallySafeButSensitive = ProviderResponseEnvelope.inspect(
                List.of("sk-secret-token"), false);
        assertTrue(syntacticallySafeButSensitive.safeCallIds().getFirst()
                .startsWith("redacted-sha256-"));
        assertFalse(syntacticallySafeButSensitive.toString().contains("sk-secret-token"));
    }
}
