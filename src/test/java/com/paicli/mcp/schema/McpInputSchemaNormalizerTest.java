package com.paicli.mcp.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McpInputSchemaNormalizerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final McpInputSchemaNormalizer normalizer = new McpInputSchemaNormalizer(16_384, 8, 10);

    @Test void preservesExecutableSemanticsAndCanonicalizes() throws Exception {
        var first = normalizer.normalize(json.readTree("""
                {"required":["name"],"properties":{"tags":{"items":{"type":"string"},"type":"array"},"name":{"minLength":1,"type":"string"}},"additionalProperties":false,"type":"object"}
                """));
        var second = normalizer.normalize(json.readTree("""
                {"type":"object","additionalProperties":false,"properties":{"name":{"type":"string","minLength":1},"tags":{"type":"array","items":{"type":"string"}}},"required":["name"]}
                """));
        assertEquals(first.schemaHash(), second.schemaHash());
        assertTrue(first.canonicalJson().contains("\"additionalProperties\":false"));
        assertTrue(first.canonicalJson().contains("\"required\":[\"name\"]"));
        assertTrue(first.canonicalJson().contains(McpInputSchemaNormalizer.DIALECT));
    }

    @Test void preservesEnumConstDefaultsAndConstraintsWithoutCoercion() throws Exception {
        var normalized = normalizer.normalize(json.readTree("""
                {"type":"object","properties":{"enabled":{"type":"boolean","const":true,"default":false},"mode":{"type":"string","enum":["a","b"],"maxLength":4}}}
                """));
        assertTrue(normalized.canonicalJson().contains("\"const\":true"));
        assertTrue(normalized.canonicalJson().contains("\"default\":false"));
        assertFalse(normalized.canonicalJson().contains("\"true\""));
    }

    @Test void trimsOnlyDisplayMetadata() throws Exception {
        var node = json.createObjectNode().put("type", "object").put("description", "x".repeat(3000));
        node.putArray("examples").addObject().put("ignored", true);
        var normalized = normalizer.normalize(node);
        assertFalse(normalized.canonicalJson().contains("examples"));
        assertTrue(normalized.canonicalJson().contains("description"));
        assertTrue(normalized.canonicalJson().length() < 2300);
    }

    @Test void rejectsUnsupportedOrUnsafeShapes() throws Exception {
        assertEquals("INPUT_SCHEMA_NOT_OBJECT", assertThrows(McpSchemaException.class, () -> normalizer.normalize(json.readTree("[]"))).category());
        assertEquals("UNSUPPORTED_SCHEMA_DIALECT", assertThrows(McpSchemaException.class, () -> normalizer.normalize(json.readTree("{\"$schema\":\"draft-07\",\"type\":\"object\"}"))).category());
        for (String keyword : new String[]{"$ref", "$defs", "oneOf", "anyOf", "allOf", "if", "patternProperties", "format", "pattern"}) {
            var node = json.createObjectNode().put("type", "object"); node.put(keyword, "bad");
            assertEquals("UNSUPPORTED_SCHEMA_KEYWORD", assertThrows(McpSchemaException.class, () -> normalizer.normalize(node)).category(), keyword);
        }
    }

    @Test void rejectsBudgetsInsteadOfTruncatingSemantics() throws Exception {
        var many = json.createObjectNode().put("type", "object");
        var properties = many.putObject("properties");
        for (int i=0;i<11;i++) properties.putObject("p"+i).put("type","string");
        assertEquals("TOO_MANY_PROPERTIES", assertThrows(McpSchemaException.class, () -> normalizer.normalize(many)).category());
        var huge = json.createObjectNode().put("type","object");
        huge.putObject("properties").putObject("value").put("type","string").putArray("enum").add("x".repeat(20_000));
        assertEquals("SCHEMA_TOO_LARGE", assertThrows(McpSchemaException.class, () -> normalizer.normalize(huge)).category());
    }
}
