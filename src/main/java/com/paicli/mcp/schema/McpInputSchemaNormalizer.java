package com.paicli.mcp.schema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class McpInputSchemaNormalizer {
    public static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";
    public static final String DRAFT_07 = "http://json-schema.org/draft-07/schema#";
    private static final Set<String> DIALECTS = Set.of(DIALECT, DRAFT_07);
    private static final Set<String> SUPPORTED = Set.of(
            "$schema", "type", "properties", "required", "additionalProperties", "items",
            "enum", "const", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum",
            "multipleOf", "minLength", "maxLength", "minItems", "maxItems",
            "uniqueItems", "minProperties", "maxProperties", "title", "description", "examples",
            "deprecated", "readOnly", "writeOnly", "default");
    private final ObjectMapper json = new ObjectMapper();
    private final int maxBytes;
    private final int maxDepth;
    private final int maxProperties;

    public McpInputSchemaNormalizer(int maxBytes) { this(maxBytes, 32, 512); }

    public McpInputSchemaNormalizer(int maxBytes, int maxDepth, int maxProperties) {
        if (maxBytes < 1 || maxDepth < 1 || maxProperties < 1) throw new IllegalArgumentException("schema limits must be positive");
        this.maxBytes = maxBytes;
        this.maxDepth = maxDepth;
        this.maxProperties = maxProperties;
    }

    public McpNormalizedSchema normalize(JsonNode input) throws McpSchemaException {
        if (input == null || !input.isObject()) throw new McpSchemaException("INPUT_SCHEMA_NOT_OBJECT", "inputSchema must be an object");
        String dialect = input.path("$schema").asText(DIALECT);
        if (!DIALECTS.contains(dialect)) throw new McpSchemaException("UNSUPPORTED_SCHEMA_DIALECT", "supported dialects: Draft-07 and 2020-12 (bounded keyword subset)");
        if (!input.has("type") || !isObjectType(input.get("type")))
            throw new McpSchemaException("ROOT_SCHEMA_NOT_OBJECT", "inputSchema root must describe object arguments");
        ObjectNode effective = ((ObjectNode) input).deepCopy();
        if (!effective.has("$schema")) effective.put("$schema", DIALECT);
        Counter counter = new Counter();
        JsonNode normalized = normalizeNode(effective, 1, counter);
        byte[] bytes;
        try { bytes = json.writeValueAsBytes(normalized); }
        catch (JsonProcessingException e) { throw new McpSchemaException("SCHEMA_SERIALIZATION", "unable to normalize inputSchema"); }
        if (bytes.length > maxBytes) throw new McpSchemaException("SCHEMA_TOO_LARGE", "inputSchema exceeds configured byte budget");
        return new McpNormalizedSchema(new String(bytes, StandardCharsets.UTF_8), sha256(bytes), bytes.length);
    }

    private JsonNode normalizeNode(JsonNode node, int depth, Counter counter) throws McpSchemaException {
        if (depth > maxDepth) throw new McpSchemaException("SCHEMA_TOO_DEEP", "inputSchema exceeds depth budget");
        if (!node.isObject()) throw new McpSchemaException("INVALID_SCHEMA_NODE", "schema node must be an object");
        ObjectNode result = json.createObjectNode();
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        Collections.sort(names);
        for (String name : names) {
            if (!SUPPORTED.contains(name)) throw new McpSchemaException("UNSUPPORTED_SCHEMA_KEYWORD", "unsupported schema keyword: " + name);
            JsonNode value = node.get(name);
            if (name.equals("title") || name.equals("description")) {
                if (!value.isTextual()) throw new McpSchemaException("INVALID_SCHEMA_METADATA", name + " must be a string");
                result.put(name, sanitize(value.asText(), name.equals("title") ? 256 : 2048));
            } else if (name.equals("examples")) {
                // Examples are display-only and intentionally omitted from the executable schema artifact.
            } else if (name.equals("properties")) {
                if (!value.isObject()) throw new McpSchemaException("INVALID_PROPERTIES", "properties must be an object");
                ObjectNode properties = json.createObjectNode();
                List<String> keys = new ArrayList<>(); value.fieldNames().forEachRemaining(keys::add); Collections.sort(keys);
                counter.properties += keys.size();
                if (counter.properties > maxProperties) throw new McpSchemaException("TOO_MANY_PROPERTIES", "inputSchema exceeds property budget");
                for (String key : keys) properties.set(key, normalizeNode(value.get(key), depth + 1, counter));
                result.set(name, properties);
            } else if ((name.equals("additionalProperties") || name.equals("items")) && value.isObject()) {
                result.set(name, normalizeNode(value, depth + 1, counter));
            } else {
                validateKeyword(name, value);
                result.set(name, canonical(value, depth + 1));
            }
        }
        return result;
    }

    private void validateKeyword(String name, JsonNode value) throws McpSchemaException {
        if (name.equals("$schema") && (!value.isTextual() || !DIALECTS.contains(value.asText())))
            throw new McpSchemaException("UNSUPPORTED_SCHEMA_DIALECT", "supported dialects: Draft-07 and 2020-12 (bounded keyword subset)");
        if (name.equals("type") && !(value.isTextual() || value.isArray()))
            throw new McpSchemaException("INVALID_TYPE", "type must be string or array");
        if (name.equals("type") && !validTypes(value))
            throw new McpSchemaException("INVALID_TYPE", "type contains an unsupported JSON type");
        if (name.equals("required") && (!value.isArray() || !allText(value)))
            throw new McpSchemaException("INVALID_REQUIRED", "required must be an array of strings");
        if (name.equals("required") && uniqueTextCount(value) != value.size())
            throw new McpSchemaException("INVALID_REQUIRED", "required entries must be unique");
        if (name.equals("additionalProperties") && !value.isBoolean() && !value.isObject())
            throw new McpSchemaException("INVALID_ADDITIONAL_PROPERTIES", "additionalProperties must be boolean or schema");
        if (name.equals("items") && !value.isObject()) throw new McpSchemaException("INVALID_ITEMS", "items must be a schema object");
        if (Set.of("minLength","maxLength","minItems","maxItems","minProperties","maxProperties").contains(name)
                && (!value.isIntegralNumber() || value.asLong() < 0)) throw new McpSchemaException("INVALID_CONSTRAINT", name + " must be a non-negative integer");
        if (Set.of("minimum","maximum","exclusiveMinimum","exclusiveMaximum","multipleOf").contains(name) && !value.isNumber())
            throw new McpSchemaException("INVALID_CONSTRAINT", name + " must be numeric");
        if (name.equals("multipleOf") && value.asDouble() <= 0)
            throw new McpSchemaException("INVALID_CONSTRAINT", "multipleOf must be positive");
        if (Set.of("uniqueItems","deprecated","readOnly","writeOnly").contains(name) && !value.isBoolean())
            throw new McpSchemaException("INVALID_CONSTRAINT", name + " must be boolean");
        if (name.equals("enum") && (!value.isArray() || value.isEmpty())) throw new McpSchemaException("INVALID_ENUM", "enum must be a non-empty array");
    }

    private JsonNode canonical(JsonNode value, int depth) throws McpSchemaException {
        if (depth > maxDepth) throw new McpSchemaException("SCHEMA_TOO_DEEP", "inputSchema exceeds depth budget");
        if (value.isObject()) {
            ObjectNode out = json.createObjectNode();
            List<String> keys = new ArrayList<>(); value.fieldNames().forEachRemaining(keys::add); Collections.sort(keys);
            for (String key : keys) out.set(key, canonical(value.get(key), depth + 1));
            return out;
        }
        if (value.isArray()) {
            ArrayNode out = json.createArrayNode(); for (JsonNode item : value) out.add(canonical(item, depth + 1)); return out;
        }
        return value.deepCopy();
    }

    private static boolean isObjectType(JsonNode value) {
        if (value.isTextual()) return "object".equals(value.asText());
        if (!value.isArray()) return false;
        for (JsonNode item : value) if (!item.isTextual() || !("object".equals(item.asText()) || "null".equals(item.asText()))) return false;
        return value.size() > 0;
    }
    private static boolean allText(JsonNode value) { for (JsonNode item : value) if (!item.isTextual()) return false; return true; }
    private static int uniqueTextCount(JsonNode value) { Set<String> values = new HashSet<>(); for (JsonNode item : value) values.add(item.asText()); return values.size(); }
    private static boolean validTypes(JsonNode value) {
        Set<String> allowed = Set.of("null", "boolean", "object", "array", "number", "string", "integer");
        if (value.isTextual()) return allowed.contains(value.asText());
        Set<String> seen = new HashSet<>();
        for (JsonNode item : value) if (!item.isTextual() || !allowed.contains(item.asText()) || !seen.add(item.asText())) return false;
        return !seen.isEmpty();
    }
    private static String sanitize(String text, int max) {
        String clean = text.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "");
        return clean.substring(0, Math.min(max, clean.length()));
    }
    private static String sha256(byte[] bytes) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (Exception e) { throw new IllegalStateException(e); } }
    private static final class Counter { int properties; }
}
