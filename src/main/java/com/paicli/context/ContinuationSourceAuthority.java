package com.paicli.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.context.ContextModels.ContextSource;
import com.paicli.context.ContextModels.ContextSourceSet;
import com.paicli.context.ContextModels.OperationKind;
import com.paicli.model.AgentMessage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Validates the exact Provider continuation result closure for the latest durable Tool turn. */
public final class ContinuationSourceAuthority {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ContinuationSourceAuthority() { }

    /**
     * Returns the source IDs of the latest Tool turn's terminal Provider results, in call order.
     * These sources may describe execution or typed non-execution; they are not evidence that a
     * Tool effect occurred. Execution authority remains in TOOL_RESULT/TOOL_OCCURRENCE sources.
     */
    public static List<String> close(OperationKind operation, List<ContextSource> sources) {
        if (operation != OperationKind.TOOL_CONTINUATION) return List.of();
        List<ContextSource> ordered = sources.stream().sorted(ContextSourceSet.ORDER).toList();
        ContextSource action = ordered.stream()
                .filter(source -> "EVENT_ACTION".equals(source.kind()))
                .max(java.util.Comparator.comparingLong(ContextSource::sequence))
                .orElseThrow(() -> new IllegalArgumentException("TOOL_RESULT_SOURCE_MISSING"));
        List<String> expected = expectedCallIds(action);
        List<ContextSource> results = ordered.stream()
                .filter(source -> "EVENT_OBSERVATION".equals(source.kind()))
                .filter(source -> source.sequence() > action.sequence())
                .sorted(java.util.Comparator.comparingLong(ContextSource::sequence))
                .toList();
        if (results.size() != expected.size())
            throw new IllegalArgumentException("TOOL_RESULT_SOURCE_BINDING_MISSING");
        var sourceIds = new ArrayList<String>(expected.size());
        var uniqueSources = new HashSet<String>();
        var uniqueCalls = new HashSet<String>();
        for (int index = 0; index < expected.size(); index++) {
            ContextSource result = results.get(index);
            String expectedId = expected.get(index);
            if (!Objects.equals(action.revision(), result.revision())
                    || result.messageType() != AgentMessage.MessageType.OBSERVATION
                    || !Objects.equals(expectedId, result.toolCallId())
                    || !uniqueCalls.add(result.toolCallId())
                    || !uniqueSources.add(result.stableSourceId()))
                throw new IllegalArgumentException("TOOL_RESULT_SOURCE_BINDING_MISMATCH");
            sourceIds.add(result.stableSourceId());
        }
        return List.copyOf(sourceIds);
    }

    /** Compiler-side equality check prevents stale or caller-substituted source IDs. */
    public static void requireExact(OperationKind operation, List<String> claimed,
            List<ContextSource> sources) {
        List<String> expected = close(operation, sources);
        if (!expected.equals(claimed))
            throw new IllegalArgumentException("CONTEXT_SOURCE_INTEGRITY");
    }

    private static List<String> expectedCallIds(ContextSource action) {
        if (action.messageType() != AgentMessage.MessageType.ACTION
                || action.toolCallId() == null || action.toolCallId().isBlank())
            throw new IllegalArgumentException("TOOL_RESULT_SOURCE_BINDING_MISSING");
        String json = action.toolCallJson();
        if (json == null || json.isBlank())
            throw new IllegalArgumentException("TOOL_RESULT_SOURCE_BINDING_MISSING");
        try {
            JsonNode parsed = JSON.readTree(json);
            var ids = new ArrayList<String>();
            if (parsed.isArray()) {
                if (parsed.isEmpty())
                    throw new IllegalArgumentException("TOOL_RESULT_SOURCE_BINDING_MISSING");
                for (JsonNode call : parsed) ids.add(requireCallId(call));
            } else if (parsed.isObject()) {
                String stored = parsed.path("id").asText(null);
                ids.add(stored == null || stored.isBlank() ? action.toolCallId() : stored);
            } else {
                throw new IllegalArgumentException("TOOL_RESULT_SOURCE_BINDING_MISSING");
            }
            if (!action.toolCallId().equals(ids.getFirst())
                    || new HashSet<>(ids).size() != ids.size())
                throw new IllegalArgumentException("TOOL_RESULT_SOURCE_BINDING_MISMATCH");
            return List.copyOf(ids);
        } catch (IllegalArgumentException failure) {
            throw failure;
        } catch (Exception invalid) {
            // Historical single-call events may carry an opaque legacy JSON placeholder. Their
            // durable primary call ID remains sufficient only for a one-result continuation.
            return List.of(action.toolCallId());
        }
    }

    private static String requireCallId(JsonNode call) {
        String id = call != null && call.isObject() ? call.path("id").asText(null) : null;
        if (id == null || id.isBlank())
            throw new IllegalArgumentException("TOOL_RESULT_SOURCE_BINDING_MISSING");
        return id;
    }
}
