package com.paicli.provider;

import com.paicli.model.*;
import com.paicli.model.ModelResponse.ResponseType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MockModelProviderTest {

    @Test
    void returnsPreConfiguredFinalResponse() {
        var expected = ModelResponse.finalAnswer("All done.");
        var provider = new MockModelProvider(List.of(expected));

        ModelResponse result = provider.chat(List.of(), List.of());

        assertEquals(ResponseType.FINAL, result.type());
        assertEquals("All done.", result.content());
        assertNull(result.toolCall());
    }

    @Test
    void returnsPreConfiguredToolCallResponse() {
        var toolCall = new ToolCall("list_files", Map.of("path", "."));
        var expected = ModelResponse.toolCallResponse("Let me check", toolCall);
        var provider = new MockModelProvider(List.of(expected));

        ModelResponse result = provider.chat(List.of(), List.of());

        assertEquals(ResponseType.TOOL_CALL, result.type());
        assertEquals("Let me check", result.content());
        assertNotNull(result.toolCall());
        assertEquals("list_files", result.toolCall().name());
        assertEquals(".", result.toolCall().arguments().get("path"));
    }

    @Test
    void depletesQueueInOrder() {
        var first = ModelResponse.toolCallResponse("step 1",
                new ToolCall("list_files", Map.of("path", ".")));
        var second = ModelResponse.finalAnswer("done");
        var provider = new MockModelProvider(List.of(first, second));

        ModelResponse r1 = provider.chat(List.of(), List.of());
        assertEquals(ResponseType.TOOL_CALL, r1.type());

        ModelResponse r2 = provider.chat(List.of(), List.of());
        assertEquals(ResponseType.FINAL, r2.type());
        assertEquals("done", r2.content());
    }

    @Test
    void returnsDefaultWhenQueueExhausted() {
        var provider = new MockModelProvider(List.of());

        ModelResponse result = provider.chat(List.of(), List.of());

        assertEquals(ResponseType.FINAL, result.type());
        assertTrue(result.content().contains("no more pre-configured"));
        assertTrue(provider.isExhausted());
    }

    @Test
    void isExhaustedReflectsQueueState() {
        var provider = new MockModelProvider(List.of(
                ModelResponse.finalAnswer("one")
        ));

        assertFalse(provider.isExhausted());
        provider.chat(List.of(), List.of());
        assertTrue(provider.isExhausted());
    }

    @Test
    void acceptsAndIgnoresMessagesAndTools() {
        // MockModelProvider does not use messages or toolDefinitions,
        // but the interface should accept them without error.
        var messages = List.of(
                AgentMessage.userGoal("test goal"),
                AgentMessage.action("calling tool")
        );
        var provider = new MockModelProvider(List.of(
                ModelResponse.finalAnswer("ok")
        ));

        ModelResponse result = provider.chat(messages, List.of());
        assertEquals("ok", result.content());
    }
}
