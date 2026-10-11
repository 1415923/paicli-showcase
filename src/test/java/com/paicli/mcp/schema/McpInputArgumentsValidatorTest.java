package com.paicli.mcp.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McpInputArgumentsValidatorTest {
    private final ObjectMapper json=new ObjectMapper();
    private final McpInputArgumentsValidator validator=new McpInputArgumentsValidator(4096,8,100,20,100);
    private final String schema="""
      {"type":"object","required":["name","count"],"additionalProperties":false,"properties":{
       "name":{"type":"string","minLength":2,"maxLength":8},
       "count":{"type":"integer","minimum":1,"maximum":10,"multipleOf":1},
       "tags":{"type":"array","minItems":1,"uniqueItems":true,"items":{"type":"string"}},
       "mode":{"type":"string","enum":["a","b"]},"fixed":{"type":"boolean","const":true}}}
      """;
    @Test void validatesSupportedSubset()throws Exception{validator.validate(json.readTree("{\"name\":\"ok\",\"count\":2,\"tags\":[\"x\"],\"mode\":\"a\",\"fixed\":true}"),schema);}
    @Test void rejectsRequiredAdditionalAndNoCoercion()throws Exception{
        assertEquals("REQUIRED_MISSING",assertThrows(McpArgumentValidationException.class,()->validator.validate(json.readTree("{\"name\":\"ok\"}"),schema)).category());
        assertEquals("ADDITIONAL_PROPERTY",assertThrows(McpArgumentValidationException.class,()->validator.validate(json.readTree("{\"name\":\"ok\",\"count\":2,\"extra\":1}"),schema)).category());
        assertEquals("TYPE_MISMATCH",assertThrows(McpArgumentValidationException.class,()->validator.validate(json.readTree("{\"name\":\"ok\",\"count\":\"2\"}"),schema)).category());
    }
    @Test void enforcesConstraintsAndBudgets()throws Exception{
        assertEquals("UNIQUE_ITEMS",assertThrows(McpArgumentValidationException.class,()->validator.validate(json.readTree("{\"name\":\"ok\",\"count\":2,\"tags\":[\"x\",\"x\"]}"),schema)).category());
        var deep=json.createObjectNode();var cursor=deep;for(int i=0;i<12;i++)cursor=cursor.putObject("x");
        assertThrows(McpArgumentValidationException.class,()->validator.validate(deep,"{\"type\":\"object\",\"additionalProperties\":{\"type\":\"object\",\"additionalProperties\":true}}"));
    }
    @Test void patternIsRejectedAtNormalizationSoValidatorNeverExecutesExternalRegex()throws Exception{
        var node=json.readTree("{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\",\"pattern\":\"(a+)+$\"}}}");
        assertEquals("UNSUPPORTED_SCHEMA_KEYWORD",assertThrows(McpSchemaException.class,()->new McpInputSchemaNormalizer(4096).normalize(node)).category());
    }
}
