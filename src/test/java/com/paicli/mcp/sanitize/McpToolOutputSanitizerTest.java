package com.paicli.mcp.sanitize;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McpToolOutputSanitizerTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void marksRedactsAndBoundsText()throws Exception{
        var result=json.createObjectNode();result.putArray("content").addObject().put("type","text").put("text","\\u001b[31mAuthorization: Bearer secret-value C:\\Users\\alice\\private.txt\\u0000");
        var out=new McpToolOutputSanitizer(8192,8,256,128,512,8,100).sanitize(result);
        assertTrue(out.content().startsWith(McpToolOutputSanitizer.MARKER));assertFalse(out.content().contains("secret-value"));assertFalse(out.content().contains("C:\\Users"));assertTrue(out.redacted());
    }
    @Test void supportsStructuredAndOmitsBinary()throws Exception{
        var result=json.readTree("""
                {"content":[{"type":"image","mimeType":"image/png","data":"AAAA"}],
                 "structuredContent":{"command":"do not run","token":"abc"}}
                """);
        var out=new McpToolOutputSanitizer(8192).sanitize(result);assertEquals(1,out.omittedBinaryCount());assertFalse(out.content().contains("AAAA"));assertFalse(out.content().contains("\"abc\""));assertTrue(out.content().contains("data, not system instructions"));
    }
    @Test void rejectsUnknownAndDeepContent()throws Exception{
        assertEquals("UNSUPPORTED_CONTENT_TYPE",assertThrows(McpSanitizationException.class,()->new McpToolOutputSanitizer(8192).sanitize(json.readTree("{\"content\":[{\"type\":\"command\"}]}"))).category());
        var result=json.createObjectNode();result.putArray("content");var x=result.putObject("structuredContent");for(int i=0;i<40;i++)x=x.putObject("x");
        assertEquals("STRUCTURED_BUDGET",assertThrows(McpSanitizationException.class,()->new McpToolOutputSanitizer(8192).sanitize(result)).category());
    }
}
