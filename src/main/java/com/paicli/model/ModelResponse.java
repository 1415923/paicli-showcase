package com.paicli.model;

import com.paicli.provider.ProviderResponseEnvelope;

import java.util.List;

/**
 * Response from a {@code ModelProvider}.
 *
 * @param content   textual content (non-null for FINAL responses)
 * @param toolCall  tool invocation request (non-null for TOOL_CALL responses)
 * @param type      response type discriminator
 * @param providerErrorKind bounded infrastructure failure classification
 */
public record ModelResponse(String content, ToolCall toolCall, ResponseType type,
                            ProviderResponseEnvelope providerEnvelope,
                             ProviderErrorKind providerErrorKind,
                             boolean privateContinuationRequired,
                             List<ToolCall> toolCalls,
                             String providerOccurrenceId) {

    public ModelResponse(String content, ToolCall toolCall, ResponseType type,
                         ProviderResponseEnvelope providerEnvelope,
                         ProviderErrorKind providerErrorKind,
                         boolean privateContinuationRequired,
                         List<ToolCall> toolCalls) {
        this(content,toolCall,type,providerEnvelope,providerErrorKind,
                privateContinuationRequired,toolCalls,null);
    }

    public ModelResponse(String content, ToolCall toolCall, ResponseType type) {
        this(content, toolCall, type, null, null, false,
                toolCall == null ? List.of() : List.of(toolCall));
    }

    public ModelResponse(String content, ToolCall toolCall, ResponseType type,
                         ProviderResponseEnvelope providerEnvelope) {
        this(content, toolCall, type, providerEnvelope, null, false,
                toolCall == null ? List.of() : List.of(toolCall));
    }

    public ModelResponse(String content, ToolCall toolCall, ResponseType type,
                         ProviderResponseEnvelope providerEnvelope,
                         ProviderErrorKind providerErrorKind,
                         boolean privateContinuationRequired) {
        this(content, toolCall, type, providerEnvelope, providerErrorKind,
                privateContinuationRequired,
                toolCall == null ? List.of() : List.of(toolCall));
    }

    public ModelResponse {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        if(providerOccurrenceId!=null&&providerOccurrenceId.isBlank())
            throw new IllegalArgumentException("Provider occurrence ID must not be blank");
        if (type == ResponseType.PROVIDER_ERROR && providerErrorKind == null) {
            throw new IllegalArgumentException("Provider errors require a typed failure kind");
        }
        if (type != ResponseType.PROVIDER_ERROR && providerErrorKind != null) {
            throw new IllegalArgumentException("Provider failure kind requires PROVIDER_ERROR");
        }
        if (type == ResponseType.TOOL_CALL) {
            if (toolCalls.isEmpty() || toolCall == null
                    || !toolCall.equals(toolCalls.getFirst())) {
                throw new IllegalArgumentException(
                        "Tool responses require a non-empty ordered call batch");
            }
        } else if (!toolCalls.isEmpty()) {
            throw new IllegalArgumentException("Only Tool responses may carry tool calls");
        }
    }

    public ModelResponse withProviderOccurrenceId(String occurrenceId) {
        return new ModelResponse(content,toolCall,type,providerEnvelope,providerErrorKind,
                privateContinuationRequired,toolCalls,
                java.util.Objects.requireNonNull(occurrenceId));
    }

    /** Discriminator for the kind of model response. */
    public enum ResponseType {
        /** Model is done; {@code content} contains the final answer. */
        FINAL,
        /** Model requests a tool call; {@code toolCall} contains the request. */
        TOOL_CALL,
        /** Provider response failed response-integrity admission; no call is executable. */
        PROTOCOL_ERROR,
        /** Provider infrastructure failed before producing an executable model response. */
        PROVIDER_ERROR
    }

    /** Bounded provider failure classification; raw response bodies are never retained. */
    public enum ProviderErrorKind {
        PROVIDER_CONNECTION_FAILURE,
        PROVIDER_TIMEOUT,
        PROVIDER_TRANSPORT_INTERRUPTED,
        PROVIDER_TRANSPORT_RETRY_EXHAUSTED,
        PROVIDER_RETRY_ADMISSION_REJECTED,
        PROVIDER_MODEL_UNAVAILABLE,
        PROVIDER_AUTHENTICATION_FAILURE,
        PROVIDER_RATE_LIMITED,
        PROVIDER_SERVER_FAILURE,
        PROVIDER_HTTP_FAILURE,
        PROVIDER_RESPONSE_PARSE_FAILURE,
        PROVIDER_FINAL_ANSWER_CONTRACT_FAILURE,
        PROVIDER_RESPONSE_INCOMPLETE,
        PROVIDER_EMPTY_RESPONSE,
        PROVIDER_CONFIGURATION_FAILURE,
        PROVIDER_REQUEST_SERIALIZATION_FAILURE,
        PROVIDER_PRIVATE_CONTINUATION_PROTOCOL_FAILURE,
        PROVIDER_PRIVATE_CONTINUATION_HTTP_400,
        PROVIDER_PRIVATE_CONTINUATION_INCOMPLETE,
        PROVIDER_PRIVATE_CONTINUATION_INCOMPLETE_AFTER_RETRY,
        TOOL_CALL_ID_MISSING,
        TOOL_CALL_ID_DUPLICATE,
        FINISH_REASON_CONFLICT,
        TOOL_RESULT_MISSING_FOR_BATCH_CONTINUATION,
        TOOL_RESULT_BATCH_CONTINUATION_MISMATCH,
        PROVIDER_PRIVATE_CONTINUATION_UNAVAILABLE,
        PROVIDER_REQUEST_FAILURE,
        PROVIDER_LEGACY_ERROR
    }

    // ── factory helpers ──

    public static ModelResponse finalAnswer(String content) {
        return new ModelResponse(content, null, ResponseType.FINAL,
                null, null, false, List.of());
    }

    public static ModelResponse toolCallResponse(String content, ToolCall toolCall) {
        return new ModelResponse(content, toolCall, ResponseType.TOOL_CALL,
                null, null, false, List.of(toolCall));
    }

    public static ModelResponse toolCallResponse(String content, ToolCall toolCall,
                                                 ProviderResponseEnvelope envelope) {
        return new ModelResponse(content, toolCall, ResponseType.TOOL_CALL, envelope, null, false);
    }

    public static ModelResponse privateContinuationToolCall(String content,ToolCall toolCall,
            ProviderResponseEnvelope envelope) {
        return new ModelResponse(content,toolCall,ResponseType.TOOL_CALL,envelope,null,true);
    }

    public static ModelResponse toolCallBatchResponse(String content,List<ToolCall> toolCalls,
            ProviderResponseEnvelope envelope,boolean privateContinuationRequired) {
        List<ToolCall> ordered=List.copyOf(toolCalls);
        if(ordered.isEmpty())throw new IllegalArgumentException("Tool batch must not be empty");
        return new ModelResponse(content,ordered.getFirst(),ResponseType.TOOL_CALL,envelope,null,
                privateContinuationRequired,ordered);
    }

    public static ModelResponse protocolError(ProviderResponseEnvelope envelope) {
        if (envelope == null || envelope.diagnostic().code() == null) {
            throw new IllegalArgumentException("Protocol errors require a bounded diagnostic");
        }
        return new ModelResponse(null, null, ResponseType.PROTOCOL_ERROR,
                envelope, null, false, List.of());
    }

    public static ModelResponse providerError(ProviderErrorKind kind) {
        if (kind == null) {
            throw new IllegalArgumentException("Provider error kind is required");
        }
        return new ModelResponse(null, null, ResponseType.PROVIDER_ERROR,
                null, kind, false, List.of());
    }
}
