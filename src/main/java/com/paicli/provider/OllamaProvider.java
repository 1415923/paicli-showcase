package com.paicli.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.model.*;
import com.paicli.provider.telemetry.ProviderCallObservation;
import com.paicli.provider.telemetry.ProviderObservationNormalizer;
import com.paicli.provider.telemetry.ProviderObservationSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * ModelProvider backed by a local Ollama instance.
 * <p>
 * Default: http://localhost:11434, model qwen3:14b, stream=false.
 * <p>
 * Error handling: HTTP errors, timeouts, and JSON parse failures return typed failures.
 */
public class OllamaProvider implements ModelProvider, ProviderObservationSource {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_MODEL_METADATA_BYTES = 65_536;

    private final String chatUrl;
    private final String showUrl;
    private final String model;
    private final String systemPrompt;

    @Override public boolean usesManagedAnswerEnvelope() { return true; }
    @Override public void validateOutputRequirement(OutputRequirement requirement, boolean hasTools) {
        OutputCapabilities.UNKNOWN.require(requirement, hasTools);
    }
    private final HttpClient httpClient;
    private final Duration requestTimeout;
    private final ThreadLocal<ProviderCallObservation> observations =
            ThreadLocal.withInitial(ProviderCallObservation::notExposed);

    public OllamaProvider(String baseUrl, String model, String systemPrompt, int timeoutSeconds) {
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("Ollama timeout must be positive");
        }
        this.chatUrl = baseUrl + "/api/chat";
        this.showUrl = baseUrl + "/api/show";
        this.model = model;
        this.systemPrompt = systemPrompt;
        // command_timeout_seconds is also passed here by the legacy factory, but
        // a local planning model routinely needs more than the 30-second command
        // default. Preserve the existing provider floor while still allowing a
        // larger explicitly configured bound.
        this.requestTimeout = Duration.ofSeconds(Math.max(120, timeoutSeconds));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(10, timeoutSeconds)))
                .build();
    }

    OllamaProvider(String baseUrl, String model, String systemPrompt,
            HttpClient httpClient, Duration requestTimeout) {
        this.chatUrl = baseUrl + "/api/chat";
        this.showUrl = baseUrl + "/api/show";
        this.model = model;
        this.systemPrompt = systemPrompt;
        this.httpClient = java.util.Objects.requireNonNull(httpClient, "httpClient");
        this.requestTimeout = java.util.Objects.requireNonNull(
                requestTimeout, "requestTimeout");
        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("Ollama timeout must be positive");
        }
    }

    /**
     * Reads the model-owned context limit without assuming a model catalog value.
     * A missing, rejected, or malformed metadata response remains unknown so the
     * frozen context gate can fail closed or use a separately configured limit.
     */
    public Integer reportedContextWindowTokens() {
        try {
            ObjectNode request = MAPPER.createObjectNode();
            request.put("model", model);
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(showUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            MAPPER.writeValueAsString(request)))
                    .timeout(requestTimeout)
                    .build();
            HttpResponse<java.io.InputStream> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300
                    || response.body() == null) {
                return null;
            }
            byte[] metadata;
            try (java.io.InputStream body = response.body()) {
                metadata = body.readNBytes(MAX_MODEL_METADATA_BYTES + 1);
            }
            if (metadata.length == 0 || metadata.length > MAX_MODEL_METADATA_BYTES) {
                return null;
            }
            JsonNode root = MAPPER.readTree(metadata);
            JsonNode modelInfo = root.path("model_info");
            String architecture = modelInfo.path("general.architecture").asText("");
            if (architecture.isBlank()
                    || !architecture.matches("[A-Za-z0-9._-]{1,64}")) {
                return null;
            }
            JsonNode contextLength = modelInfo.path(architecture + ".context_length");
            if (!contextLength.canConvertToInt()) return null;
            int limit = contextLength.intValue();
            return limit >= 1024 ? limit : null;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception error) {
            return null;
        }
    }

    @Override
    public ModelResponse chat(List<AgentMessage> messages, List<ToolDefinition> toolDefinitions) {
        return chatForOperation("CHAT", messages, toolDefinitions, OutputRequirement.LEGACY);
    }

    @Override
    public ModelResponse chatForOperation(String operation, List<AgentMessage> messages,
            List<ToolDefinition> toolDefinitions, OutputRequirement requirement) {
        validateOutputRequirement(requirement, toolDefinitions != null && !toolDefinitions.isEmpty());
        observations.set(ProviderCallObservation.notExposed());
        final String jsonBody;
        try {
            ObjectNode request = buildRequest(messages, toolDefinitions);
            jsonBody = MAPPER.writeValueAsString(request);
        } catch (Exception error) {
            observations.set(ProviderCallObservation.notExposed()
                    .withError("PROVIDER_REQUEST_SERIALIZATION_FAILURE", false));
            return ModelResponse.providerError(
                    ModelResponse.ProviderErrorKind.PROVIDER_REQUEST_SERIALIZATION_FAILURE);
        }

        try {
            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(chatUrl))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(requestTimeout)
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 404) {
                observations.set(ProviderCallObservation.notExposed()
                        .withError("PROVIDER_MODEL_UNAVAILABLE", false));
                return ModelResponse.providerError(
                        ModelResponse.ProviderErrorKind.PROVIDER_MODEL_UNAVAILABLE);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                observations.set(ProviderCallObservation.notExposed()
                        .withError("PROVIDER_HTTP_FAILURE", false));
                return ModelResponse.providerError(
                        ModelResponse.ProviderErrorKind.PROVIDER_HTTP_FAILURE);
            }

            return parseResponse(response.body(),
                    response.headers().firstValue("x-request-id").orElse(null), requirement);

        } catch (java.net.ConnectException e) {
            observations.set(ProviderCallObservation.notExposed()
                    .withError("PROVIDER_CONNECTION_FAILURE", false));
            return ModelResponse.providerError(
                    ModelResponse.ProviderErrorKind.PROVIDER_CONNECTION_FAILURE);
        } catch (java.net.http.HttpTimeoutException e) {
            observations.set(ProviderCallObservation.notExposed()
                    .withError("PROVIDER_TIMEOUT", false));
            return ModelResponse.providerError(
                    ModelResponse.ProviderErrorKind.PROVIDER_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            observations.set(ProviderCallObservation.notExposed()
                    .withError("USER_CANCELLED", true));
            return ModelResponse.providerError(
                    ModelResponse.ProviderErrorKind.PROVIDER_REQUEST_FAILURE);
        } catch (Exception e) {
            observations.set(ProviderCallObservation.notExposed()
                    .withError("PROVIDER_REQUEST_FAILURE", false));
            return ModelResponse.providerError(
                    ModelResponse.ProviderErrorKind.PROVIDER_REQUEST_FAILURE);
        }
    }

    private ObjectNode buildRequest(List<AgentMessage> messages, List<ToolDefinition> toolDefinitions) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        root.put("stream", false);

        // Messages
        ArrayNode messagesArray = root.putArray("messages");

        // System prompt
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode sysMsg = messagesArray.addObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
        }

        // Convert AgentMessages to API format
        for (AgentMessage msg : messages) {
            ObjectNode apiMsg = messagesArray.addObject();
            switch (msg.type()) {
                case USER -> {
                    apiMsg.put("role", "user");
                    apiMsg.put("content", msg.content());
                }
                case FINAL -> {
                    apiMsg.put("role", "assistant");
                    apiMsg.put("content", msg.content());
                }
                case ACTION -> {
                    apiMsg.put("role", "assistant");
                    if (msg.content() != null) {
                        apiMsg.put("content", msg.content());
                    }
                    // Reconstruct tool_calls from stored JSON
                    if (msg.toolCallJson() != null) {
                        try {
                            JsonNode toolCallNode = MAPPER.readTree(msg.toolCallJson());
                            ArrayNode toolCalls = apiMsg.putArray("tool_calls");
                            if (toolCallNode.isArray()) {
                                toolCallNode.forEach(toolCalls::add);
                            } else {
                                toolCalls.add(toolCallNode);
                            }
                        } catch (Exception e) {
                            apiMsg.put("content", msg.content());
                        }
                    }
                }
                case OBSERVATION -> {
                    apiMsg.put("role", "tool");
                    if (msg.toolCallId() != null) {
                        apiMsg.put("tool_call_id", msg.toolCallId());
                    }
                    apiMsg.put("content", msg.content());
                }
            }
        }

        // Tools
        if (toolDefinitions != null && !toolDefinitions.isEmpty()) {
            ArrayNode toolsArray = root.putArray("tools");
            for (ToolDefinition td : toolDefinitions) {
                ObjectNode toolNode = toolsArray.addObject();
                toolNode.put("type", "function");
                ObjectNode function = toolNode.putObject("function");
                function.put("name", td.name());
                function.put("description", td.description());
                function.set("parameters", td.parameterSchema());
            }
        }

        return root;
    }

    private ModelResponse parseResponse(String body, String safeRequestId, OutputRequirement requirement) {
        if (body == null || body.isBlank()) {
            observations.set(ProviderCallObservation.notExposed()
                    .withError("PROVIDER_EMPTY_RESPONSE", false));
            return ModelResponse.providerError(
                    ModelResponse.ProviderErrorKind.PROVIDER_EMPTY_RESPONSE);
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode messageNode = root.get("message");
            if (messageNode == null) {
                observations.set(ProviderObservationNormalizer.ollama(
                        root, null, null, safeRequestId)
                        .withError("PROVIDER_RESPONSE_PARSE_FAILURE", false));
                return ModelResponse.providerError(
                        ModelResponse.ProviderErrorKind.PROVIDER_RESPONSE_PARSE_FAILURE);
            }

            String content = messageNode.has("content") ? messageNode.get("content").asText() : null;

            // Check for tool calls
            JsonNode toolCallsNode = messageNode.get("tool_calls");
            ProviderToolCallAdmission.Result admission =
                    ProviderToolCallAdmission.inspect(toolCallsNode);
            observations.set(ProviderObservationNormalizer.ollama(root,
                    admission.envelope().cardinality(),
                    admission.envelope().diagnostic().callCount(), safeRequestId));
            if (requirement.managed() && !(root.path("done").isBoolean()
                    && root.path("done").booleanValue()
                    && "stop".equals(root.path("done_reason").asText()))) {
                observations.set(observations.get().withError("PROVIDER_RESPONSE_INCOMPLETE", false));
                return ModelResponse.providerError(ModelResponse.ProviderErrorKind.PROVIDER_RESPONSE_INCOMPLETE);
            }
            if (admission.envelope().cardinality()
                    != ProviderResponseEnvelope.ToolCallCardinality.ZERO) {
                return ProviderToolCallAdmission.toResponse(content, admission);
            }

            // No tool calls — treat as final answer
            if (content != null && !content.isBlank()) {
                return ModelResponse.finalAnswer(content);
            }

            observations.set(ProviderObservationNormalizer.ollama(
                    root, ProviderResponseEnvelope.ToolCallCardinality.ZERO,
                    0, safeRequestId)
                    .withError("PROVIDER_EMPTY_RESPONSE", false));
            return ModelResponse.providerError(
                    ModelResponse.ProviderErrorKind.PROVIDER_EMPTY_RESPONSE);

        } catch (Exception e) {
            observations.set(ProviderCallObservation.notExposed()
                    .withError("PROVIDER_RESPONSE_PARSE_FAILURE", false));
            return ModelResponse.providerError(
                    ModelResponse.ProviderErrorKind.PROVIDER_RESPONSE_PARSE_FAILURE);
        }
    }

    @Override
    public ProviderCallObservation takeObservation() {
        ProviderCallObservation value = observations.get();
        observations.remove();
        return value;
    }
}
