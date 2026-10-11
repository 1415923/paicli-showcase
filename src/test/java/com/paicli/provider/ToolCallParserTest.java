package com.paicli.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolCallParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parseOllamaToolCall() throws Exception {
        JsonNode node = MAPPER.readTree("""
                {
                    "function": {
                        "name": "list_files",
                        "arguments": {"path": "."}
                    }
                }
                """);

        var result = ToolCallParser.parse(node);

        assertNotNull(result);
        assertEquals("list_files", result.toolCall().name());
        assertEquals(".", result.toolCall().arguments().get("path"));
    }

    @Test
    void parseOpenAIToolCall() throws Exception {
        JsonNode node = MAPPER.readTree("""
                {
                    "id": "call_abc123",
                    "type": "function",
                    "function": {
                        "name": "read_file",
                        "arguments": "{\\"path\\": \\"README.md\\"}"
                    }
                }
                """);

        var result = ToolCallParser.parse(node);

        assertNotNull(result);
        assertEquals("read_file", result.toolCall().name());
        assertEquals("README.md", result.toolCall().arguments().get("path"));
        assertEquals("call_abc123", result.id());
    }

    @Test
    void parseOllamaTopLevelFormat() throws Exception {
        // Some Ollama versions put name/arguments at top level
        JsonNode node = MAPPER.readTree("""
                {
                    "name": "grep",
                    "arguments": {"pattern": "test", "path": "."}
                }
                """);

        var result = ToolCallParser.parse(node);

        assertNotNull(result);
        assertEquals("grep", result.toolCall().name());
        assertEquals("test", result.toolCall().arguments().get("pattern"));
    }

    @Test
    void parseFirstFromOllamaArray() throws Exception {
        JsonNode array = MAPPER.readTree("""
                [
                    {
                        "function": {
                            "name": "list_files",
                            "arguments": {"path": "src"}
                        }
                    }
                ]
                """);

        var result = ToolCallParser.parseFirst(array);

        assertNotNull(result);
        assertEquals("list_files", result.toolCall().name());
    }

    @Test
    void parseFirstReturnsNullForEmptyArray() {
        ArrayNode empty = MAPPER.createArrayNode();
        assertNull(ToolCallParser.parseFirst(empty));
    }

    @Test
    void parseFirstReturnsNullForNull() {
        assertNull(ToolCallParser.parseFirst(null));
    }

    @Test
    void parseReturnsNullForMissingName() throws Exception {
        JsonNode node = MAPPER.readTree("""
                {
                    "function": {
                        "arguments": {"path": "."}
                    }
                }
                """);

        assertNull(ToolCallParser.parse(node));
    }

    @Test
    void parseHandlesEmptyArguments() throws Exception {
        JsonNode node = MAPPER.readTree("""
                {
                    "function": {
                        "name": "list_files",
                        "arguments": {}
                    }
                }
                """);

        var result = ToolCallParser.parse(node);

        assertNotNull(result);
        assertTrue(result.toolCall().arguments().isEmpty());
    }

    @Test
    void parseHandlesNullArguments() throws Exception {
        JsonNode node = MAPPER.readTree("""
                {
                    "function": {
                        "name": "list_files"
                    }
                }
                """);

        var result = ToolCallParser.parse(node);

        assertNotNull(result);
        assertTrue(result.toolCall().arguments().isEmpty());
    }

    @Test
    void parseAllMultipleToolCalls() throws Exception {
        JsonNode array = MAPPER.readTree("""
                [
                    {"function": {"name": "list_files", "arguments": {"path": "."}}},
                    {"function": {"name": "read_file", "arguments": {"path": "README.md"}}}
                ]
                """);

        var results = ToolCallParser.parseAll(array);

        assertEquals(2, results.size());
        assertEquals("list_files", results.get(0).toolCall().name());
        assertEquals("read_file", results.get(1).toolCall().name());
    }

    @Test
    void parseAllReturnsEmptyForNull() {
        assertTrue(ToolCallParser.parseAll(null).isEmpty());
    }
    @Test
    void parseRejectsMalformedStringArguments() throws Exception {
        JsonNode node = MAPPER.readTree("""
                {
                    "function": {
                        "name": "read_file",
                        "arguments": "{not-json"
                    }
                }
                """);

        assertNull(ToolCallParser.parse(node));
    }

    @Test
    void parseRejectsNonObjectArguments() throws Exception {
        JsonNode node = MAPPER.readTree("""
                {
                    "function": {
                        "name": "read_file",
                        "arguments": ["README.md"]
                    }
                }
                """);

        assertNull(ToolCallParser.parse(node));
    }

    @Test
    void parseRejectsEmptyToolNameAndUnknownEnvelope() throws Exception {
        JsonNode emptyName = MAPPER.readTree("""
                {
                    "function": {
                        "name": "",
                        "arguments": {}
                    }
                }
                """);
        JsonNode unknownEnvelope = MAPPER.readTree("""
                {
                    "tool": "read_file",
                    "args": {"path": "README.md"}
                }
                """);

        assertNull(ToolCallParser.parse(emptyName));
        assertNull(ToolCallParser.parse(unknownEnvelope));
    }
}
