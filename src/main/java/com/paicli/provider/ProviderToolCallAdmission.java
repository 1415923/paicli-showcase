package com.paicli.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.paicli.model.ModelResponse;

import java.util.ArrayList;
import java.util.List;

/** Complete response-level tool-call admission shared by real Provider adapters. */
final class ProviderToolCallAdmission {
    private ProviderToolCallAdmission() { }

    static Result inspect(JsonNode toolCalls) {
        if (toolCalls == null || toolCalls.isNull()) {
            return new Result(ProviderResponseEnvelope.inspect(List.of(), false), null);
        }
        if (!toolCalls.isArray()) {
            return new Result(ProviderResponseEnvelope.inspect(List.of(), true), null);
        }

        List<String> identities = new ArrayList<>();
        List<ToolCallParser.ToolCallResult> parsed = new ArrayList<>();
        boolean malformed = false;
        for (JsonNode node : toolCalls) {
            String identity = null;
            if (node != null && node.isObject() && node.has("id")) {
                if (node.get("id").isTextual()) identity = node.get("id").asText();
                else malformed = true;
            }
            identities.add(identity);
            ToolCallParser.ToolCallResult call = ToolCallParser.parse(node);
            if (call == null) malformed = true;
            else parsed.add(call);
        }

        ProviderResponseEnvelope envelope =
                ProviderResponseEnvelope.inspect(identities, malformed);
        List<ToolCallParser.ToolCallResult> admitted =
                envelope.cardinality() != ProviderResponseEnvelope.ToolCallCardinality.INVALID
                        && envelope.diagnostic().code() == null
                        && parsed.size() == identities.size() ? List.copyOf(parsed) : List.of();
        return new Result(envelope, admitted);
    }

    static ModelResponse toResponse(String content, Result result) {
        return switch (result.envelope().cardinality()) {
            case ONE -> ModelResponse.toolCallResponse(
                    content, result.admitted().toolCall(), result.envelope());
            case MULTIPLE -> result.envelope().diagnostic().code() == null
                    ? ModelResponse.toolCallBatchResponse(content,result.toolCalls(),
                            result.envelope(),false)
                    : ModelResponse.protocolError(result.envelope());
            case INVALID -> ModelResponse.protocolError(result.envelope());
            case ZERO -> throw new IllegalArgumentException("ZERO has no tool response");
        };
    }

    record Result(ProviderResponseEnvelope envelope,
                  List<ToolCallParser.ToolCallResult> admittedCalls) {
        ToolCallParser.ToolCallResult admitted() {
            return admittedCalls.isEmpty() ? null : admittedCalls.getFirst();
        }
        List<com.paicli.model.ToolCall> toolCalls() {
            return admittedCalls.stream().map(ToolCallParser.ToolCallResult::toolCall).toList();
        }
    }
}
