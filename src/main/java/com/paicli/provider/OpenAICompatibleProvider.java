package com.paicli.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.model.*;
import com.paicli.credential.CredentialLease;
import com.paicli.credential.CredentialStoreException;
import com.paicli.credential.ProviderCredentialSource;
import com.paicli.provider.telemetry.ProviderCallObservation;
import com.paicli.provider.telemetry.ProviderObservationNormalizer;
import com.paicli.provider.telemetry.ProviderObservationSource;
import com.paicli.provider.telemetry.ProviderHttpStatusSource;
import com.paicli.provider.telemetry.ProviderTransportEvidence;
import com.paicli.provider.telemetry.ProviderTransportEvidenceSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.EOFException;
import java.io.IOException;
import java.net.SocketException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

/**
 * ModelProvider for OpenAI-compatible APIs (OpenAI, DeepSeek, vLLM, etc).
 * <p>
 * Reads API key from a named environment variable (never stored in config files).
 * <p>
 * Error handling: returns typed Provider failures without raw response bodies.
 */
public class OpenAICompatibleProvider implements PreparedTurnProvider, ProviderObservationSource,
        ProviderHttpStatusSource, ProviderTransportEvidenceSource,
        ProviderRetrySignalSource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final URI chatUrl;
    private final ProviderCredentialSource credentialSource;
    private final String model;
    private final String systemPrompt;

    @Override public boolean usesManagedAnswerEnvelope() { return true; }
    @Override public void validateOutputRequirement(OutputRequirement requirement, boolean hasTools) {
        preset.outputCapabilities().require(requirement, hasTools);
    }
    private final HttpClient httpClient;
    private final HttpTransport transport;
    private final Duration requestTimeout;
    private final OpenAICompatiblePreset preset;
    private final String thinkingMode;
    private final String reasoningEffort;
    private final Map<String, PrivateAssistantTurn> privateTurns = new HashMap<>();
    private final ThreadLocal<ProviderCallObservation> observations =
            ThreadLocal.withInitial(ProviderCallObservation::notExposed);
    private final ThreadLocal<Integer> httpStatuses = new ThreadLocal<>();
    private final ThreadLocal<ProviderRetrySignal> retrySignals = new ThreadLocal<>();
    private final ThreadLocal<ProviderTransportEvidence> transportEvidence =
            ThreadLocal.withInitial(() -> ProviderTransportEvidence.notStarted(
                    "NO_CURRENT_ATTEMPT"));

    public OpenAICompatibleProvider(String baseUrl, String apiKey, String model,
                                    String systemPrompt, int timeoutSeconds) {
        this(baseUrl,apiKey,model,systemPrompt,timeoutSeconds,
                OpenAICompatiblePreset.generic(),"disabled",null);
    }

    public OpenAICompatibleProvider(String baseUrl, String apiKey, String model,
            String systemPrompt, int timeoutSeconds, OpenAICompatiblePreset preset) {
        this(baseUrl,ProviderCredentialSource.fixed(apiKey),model,systemPrompt,timeoutSeconds,preset,
                preset.defaultThinkingMode(),
                "enabled".equals(preset.defaultThinkingMode())&&preset.supportsReasoningEffort()
                        ?"max":null);
    }

    public OpenAICompatibleProvider(String baseUrl, String apiKey, String model,
            String systemPrompt, int timeoutSeconds, OpenAICompatiblePreset preset,
            String thinkingMode,String reasoningEffort) {
        this(baseUrl,ProviderCredentialSource.fixed(apiKey),model,systemPrompt,timeoutSeconds,
                preset,thinkingMode,reasoningEffort);
    }

    public OpenAICompatibleProvider(String baseUrl,ProviderCredentialSource credentialSource,
            String model,String systemPrompt,int timeoutSeconds,OpenAICompatiblePreset preset,
            String thinkingMode,String reasoningEffort) {
        if(timeoutSeconds<=0)throw new IllegalArgumentException("Provider timeout must be positive");
        this.preset=java.util.Objects.requireNonNull(preset,"preset");
        this.thinkingMode=java.util.Objects.requireNonNull(thinkingMode,"thinkingMode");
        if(!java.util.Set.of("enabled","disabled").contains(thinkingMode))
            throw new IllegalArgumentException("Invalid thinking mode");
        if("enabled".equals(thinkingMode)&&!preset.supportsThinking())
            throw new IllegalArgumentException("THINKING_NOT_SUPPORTED_BY_PROVIDER_PRESET");
        if(reasoningEffort!=null&&(!preset.supportsReasoningEffort()
                ||!preset.supportedReasoningEfforts().contains(reasoningEffort)))
            throw new IllegalArgumentException("REASONING_EFFORT_NOT_SUPPORTED_BY_PROVIDER_PRESET");
        this.reasoningEffort=reasoningEffort;
        this.chatUrl = preset.endpoint(ProviderEndpointPolicy.validate(baseUrl),false);
        this.credentialSource=java.util.Objects.requireNonNull(credentialSource,"credentialSource");
        this.model = model;
        this.systemPrompt = systemPrompt;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(10,timeoutSeconds)))
                .build();
        this.transport=request->httpClient.send(request,HttpResponse.BodyHandlers.ofString());
        this.requestTimeout=Duration.ofSeconds(timeoutSeconds);
    }

    OpenAICompatibleProvider(String baseUrl,ProviderCredentialSource credentialSource,
            String model,String systemPrompt,int timeoutSeconds,OpenAICompatiblePreset preset,
            String thinkingMode,String reasoningEffort,HttpTransport transport) {
        if(timeoutSeconds<=0)throw new IllegalArgumentException("Provider timeout must be positive");
        this.preset=java.util.Objects.requireNonNull(preset,"preset");
        this.thinkingMode=java.util.Objects.requireNonNull(thinkingMode,"thinkingMode");
        if(!java.util.Set.of("enabled","disabled").contains(thinkingMode))
            throw new IllegalArgumentException("Invalid thinking mode");
        if("enabled".equals(thinkingMode)&&!preset.supportsThinking())
            throw new IllegalArgumentException("THINKING_NOT_SUPPORTED_BY_PROVIDER_PRESET");
        if(reasoningEffort!=null&&(!preset.supportsReasoningEffort()
                ||!preset.supportedReasoningEfforts().contains(reasoningEffort)))
            throw new IllegalArgumentException("REASONING_EFFORT_NOT_SUPPORTED_BY_PROVIDER_PRESET");
        this.reasoningEffort=reasoningEffort;
        this.chatUrl=preset.endpoint(ProviderEndpointPolicy.validate(baseUrl),false);
        this.credentialSource=java.util.Objects.requireNonNull(credentialSource,"credentialSource");
        this.model=model;this.systemPrompt=systemPrompt;
        this.httpClient=null;
        this.transport=java.util.Objects.requireNonNull(transport,"transport");
        this.requestTimeout=Duration.ofSeconds(timeoutSeconds);
    }

    @Override
    public PreparedProviderTurn prepareTurn(String operation,List<AgentMessage> messages,
            List<ToolDefinition> toolDefinitions) {
        return prepareTurn(operation, messages, toolDefinitions, OutputRequirement.LEGACY);
    }

    @Override
    public PreparedProviderTurn prepareTurn(String operation, List<AgentMessage> messages,
            List<ToolDefinition> toolDefinitions, OutputRequirement requirement) {
        validateOutputRequirement(requirement, toolDefinitions != null && !toolDefinitions.isEmpty());
        boolean continuation = hasToolContinuation(messages);
        if(!continuation&&(messages==null||messages.stream().noneMatch(message->
                message.type()==AgentMessage.MessageType.ACTION)))synchronized(privateTurns){
            privateTurns.clear();
        }
        try {
            ObjectNode request = buildRequest(operation,messages, toolDefinitions, requirement);
            traceRequestMetadata(operation,request);
            String jsonBody = MAPPER.writeValueAsString(request);
            byte[] body=jsonBody.getBytes(StandardCharsets.UTF_8);
            String digest=sha256(body);
            return new PreparedProviderTurn(digest,
                    new PreparedProviderTurn.RequestMetrics(body.length, request.path("messages").size(),
                            request.path("tools").size(), request.path(preset.maxTokensField()).asInt(),
                            model, thinkingMode, requirement),
                    ()->executePrepared(operation,body,continuation,requirement));
        } catch (ReasoningContinuationException e) {
            String digest=sha256(new byte[0]);
            return new PreparedProviderTurn(digest,()->preparationFailure(e));
        } catch (Exception e) {
            String digest=sha256(new byte[0]);
            return new PreparedProviderTurn(digest,()->serializationFailure());
        }
    }

    private ModelResponse executePrepared(String operation,byte[] body,boolean continuation, OutputRequirement requirement) {
        observations.set(ProviderCallObservation.notExposed());
        httpStatuses.remove();
        retrySignals.remove();
        transportEvidence.set(ProviderTransportEvidence.notStarted("PRE_TRANSPORT"));
        try {
            HttpResponse<String> response;
            try(CredentialLease credential=credentialSource.acquire()){
                String authorization="Bearer "+new String(credential.value());
                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(chatUrl)
                        .header("Content-Type", "application/json")
                        .header("Authorization", authorization)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .timeout(requestTimeout)
                        .build();
                transportEvidence.set(ProviderTransportEvidence.requestSent());
                response=transport.send(httpRequest);
            }
            httpStatuses.set(response.statusCode());
            ResponseIdentityFact responseIdentity = responseIdentity(
                    response.body(), response.headers().firstValue("x-request-id").orElse(null));
            transportEvidence.set(ProviderTransportEvidence.responseReceived(
                    response.statusCode(),
                    ProviderTransportEvidence.UsageProvenance.NOT_AVAILABLE,
                    "RESPONSE_USAGE_NOT_INTERPRETED"));

            if (response.statusCode() == 401 || response.statusCode() == 403) {
                setRetrySignal(ProviderRetrySignal.Reason.NON_RETRYABLE,false,null,
                        "HTTP_"+response.statusCode());
                observations.set(identityOnly(responseIdentity)
                        .withError("PROVIDER_AUTHENTICATION_FAILURE", false));
                return ModelResponse.providerError(
                        ModelResponse.ProviderErrorKind.PROVIDER_AUTHENTICATION_FAILURE);
            }
            if (response.statusCode() == 404) {
                setRetrySignal(ProviderRetrySignal.Reason.NON_RETRYABLE,false,null,"HTTP_404");
                observations.set(identityOnly(responseIdentity)
                        .withError("PROVIDER_MODEL_UNAVAILABLE", false));
                return ModelResponse.providerError(
                        ModelResponse.ProviderErrorKind.PROVIDER_MODEL_UNAVAILABLE);
            }
            if (response.statusCode() == 429) {
                setRetrySignal(ProviderRetrySignal.Reason.HTTP_429,true,
                        retryAfter(response),"HTTP_429");
                observations.set(identityOnly(responseIdentity)
                        .withError("PROVIDER_RATE_LIMITED", false));
                return ModelResponse.providerError(
                        ModelResponse.ProviderErrorKind.PROVIDER_RATE_LIMITED);
            }
            if (response.statusCode() == 400 && continuation
                    && "enabled".equals(thinkingMode)) {
                setRetrySignal(ProviderRetrySignal.Reason.NON_RETRYABLE,false,null,"HTTP_400");
                observations.set(identityOnly(responseIdentity)
                        .withError("PROVIDER_PRIVATE_CONTINUATION_HTTP_400", false));
                return ModelResponse.providerError(
                        ModelResponse.ProviderErrorKind.PROVIDER_PRIVATE_CONTINUATION_HTTP_400);
            }
            if (response.statusCode() >= 500) {
                boolean retryable=Set.of(502,503,504).contains(response.statusCode());
                ProviderRetrySignal.Reason reason=switch(response.statusCode()){
                    case 502->ProviderRetrySignal.Reason.HTTP_502;
                    case 503->ProviderRetrySignal.Reason.HTTP_503;
                    case 504->ProviderRetrySignal.Reason.HTTP_504;
                    default->ProviderRetrySignal.Reason.NON_RETRYABLE;
                };
                setRetrySignal(reason,retryable,retryAfter(response),
                        "HTTP_"+response.statusCode());
                observations.set(identityOnly(responseIdentity)
                        .withError("PROVIDER_SERVER_FAILURE", false));
                return ModelResponse.providerError(
                        ModelResponse.ProviderErrorKind.PROVIDER_SERVER_FAILURE);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                setRetrySignal(ProviderRetrySignal.Reason.NON_RETRYABLE,false,null,
                        "HTTP_"+response.statusCode());
                observations.set(identityOnly(responseIdentity)
                        .withError("PROVIDER_HTTP_FAILURE", false));
                return ModelResponse.providerError(
                        ModelResponse.ProviderErrorKind.PROVIDER_HTTP_FAILURE);
            }

            ModelResponse parsed = parseResponse(response.body(), responseIdentity,
                    operation,response.statusCode(),requirement);
            transportEvidence.set(settleResponseEvidence(response.statusCode(),
                    response.body(), parsed, observations.get()));
            return parsed;

        } catch (ReasoningContinuationException e) {
            transportEvidence.set(ProviderTransportEvidence.notStarted(e.kind.name()));
            observations.set(ProviderCallObservation.notExposed()
                    .withError(e.kind.name(), false));
            com.paicli.diagnostic.StageTrace.event(
                    "PROVIDER_CONTINUATION_PROTOCOL_FAILURE",true,
                    "protocol_failure_subcategory",e.kind.name());
            return ModelResponse.providerError(e.kind);
        } catch (CredentialStoreException e) {
            transportEvidence.set(ProviderTransportEvidence.notStarted(
                    "PROVIDER_CREDENTIAL_MISSING"));
            setRetrySignal(ProviderRetrySignal.Reason.NON_RETRYABLE,false,null,
                    "CREDENTIAL_MISSING");
            observations.set(ProviderCallObservation.notExposed()
                    .withError("PROVIDER_CREDENTIAL_MISSING", false));
            return ModelResponse.providerError(
                    ModelResponse.ProviderErrorKind.PROVIDER_CONFIGURATION_FAILURE);
        } catch (java.net.ConnectException e) {
            transportEvidence.set(ProviderTransportEvidence.transportFailed(
                    "TRANSPORT_CONNECT_FAILURE"));
            setRetrySignal(ProviderRetrySignal.Reason.TRANSPORT_CONNECTION_RESET,true,null,
                    "CONNECT_FAILURE");
            observations.set(ProviderCallObservation.notExposed()
                    .withError("PROVIDER_CONNECTION_FAILURE", false));
            return ModelResponse.providerError(
                    ModelResponse.ProviderErrorKind.PROVIDER_CONNECTION_FAILURE);
        } catch (java.net.http.HttpTimeoutException e) {
            transportEvidence.set(ProviderTransportEvidence.transportFailed(
                    "TRANSPORT_TIMEOUT"));
            ProviderRetrySignal.Reason reason=e.getClass().getSimpleName()
                    .contains("Connect")?ProviderRetrySignal.Reason.TRANSPORT_CONNECT_TIMEOUT:
                    ProviderRetrySignal.Reason.TRANSPORT_READ_TIMEOUT;
            setRetrySignal(reason,true,null,"TIMEOUT");
            observations.set(ProviderCallObservation.notExposed()
                    .withError("PROVIDER_TIMEOUT", false));
            return ModelResponse.providerError(
                    ModelResponse.ProviderErrorKind.PROVIDER_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            transportEvidence.set(ProviderTransportEvidence.interrupted(
                    "TRANSPORT_INTERRUPTED"));
            observations.set(ProviderCallObservation.notExposed()
                    .withError("USER_CANCELLED", true));
            return ModelResponse.providerError(
                    ModelResponse.ProviderErrorKind.PROVIDER_REQUEST_FAILURE);
        } catch (javax.net.ssl.SSLException e) {
            transportEvidence.set(ProviderTransportEvidence.transportFailed(
                    "TRANSPORT_TLS_FAILURE"));
            if(isRetryableTlsInterruption(e)){
                setRetrySignal(ProviderRetrySignal.Reason.TRANSPORT_TLS_EOF,true,null,"TLS_EOF");
                observations.set(ProviderCallObservation.notExposed()
                        .withError("PROVIDER_TRANSPORT_INTERRUPTED", false));
                return ModelResponse.providerError(
                        ModelResponse.ProviderErrorKind.PROVIDER_TRANSPORT_INTERRUPTED);
            }
            setRetrySignal(ProviderRetrySignal.Reason.NON_RETRYABLE,false,null,
                    "TLS_CONFIGURATION_FAILURE");
            observations.set(ProviderCallObservation.notExposed()
                    .withError("PROVIDER_TLS_CONFIGURATION_FAILURE",false));
            return ModelResponse.providerError(
                    ModelResponse.ProviderErrorKind.PROVIDER_CONFIGURATION_FAILURE);
        } catch (EOFException | SocketException e) {
            transportEvidence.set(ProviderTransportEvidence.transportFailed(
                    "TRANSPORT_CONNECTION_RESET"));
            setRetrySignal(ProviderRetrySignal.Reason.TRANSPORT_CONNECTION_RESET,true,null,
                    "CONNECTION_RESET");
            observations.set(ProviderCallObservation.notExposed()
                    .withError("PROVIDER_TRANSPORT_INTERRUPTED", false));
            return ModelResponse.providerError(
                    ModelResponse.ProviderErrorKind.PROVIDER_TRANSPORT_INTERRUPTED);
        } catch (IOException e) {
            transportEvidence.set(ProviderTransportEvidence.transportFailed(
                    "TRANSPORT_IO_FAILURE"));
            setRetrySignal(ProviderRetrySignal.Reason.TRANSPORT_INTERRUPTED,true,null,
                    "TRANSPORT_INTERRUPTED");
            observations.set(ProviderCallObservation.notExposed()
                    .withError("PROVIDER_TRANSPORT_INTERRUPTED", false));
            return ModelResponse.providerError(
                    ModelResponse.ProviderErrorKind.PROVIDER_TRANSPORT_INTERRUPTED);
        } catch (Exception e) {
            transportEvidence.set(ProviderTransportEvidence.unknownTerminal(
                    "PROVIDER_REQUEST_FAILURE"));
            setRetrySignal(ProviderRetrySignal.Reason.NON_RETRYABLE,false,null,
                    "REQUEST_FAILURE");
            observations.set(ProviderCallObservation.notExposed()
                    .withError("PROVIDER_REQUEST_FAILURE", false));
            return ModelResponse.providerError(
                    ModelResponse.ProviderErrorKind.PROVIDER_REQUEST_FAILURE);
        }
    }

    private ModelResponse preparationFailure(ReasoningContinuationException error) {
        transportEvidence.set(ProviderTransportEvidence.preTransportRejected(error.kind.name()));
        observations.set(ProviderCallObservation.notExposed().withError(error.kind.name(),false));
        retrySignals.remove();
        return ModelResponse.providerError(error.kind);
    }

    private ModelResponse serializationFailure() {
        transportEvidence.set(ProviderTransportEvidence.preTransportRejected(
                "PROVIDER_REQUEST_SERIALIZATION_FAILURE"));
        observations.set(ProviderCallObservation.notExposed()
                .withError("PROVIDER_REQUEST_SERIALIZATION_FAILURE",false));
        retrySignals.remove();
        return ModelResponse.providerError(
                ModelResponse.ProviderErrorKind.PROVIDER_REQUEST_SERIALIZATION_FAILURE);
    }

    private static ProviderTransportEvidence settleResponseEvidence(int status, String body,
            ModelResponse response, ProviderCallObservation observation) {
        if (observation.usageSource() == ProviderCallObservation.UsageSource.PROVIDER_REPORTED
                && observation.usageCoverage()
                == ProviderCallObservation.UsageCoverage.COMPLETE
                && observation.inputTokens() != null && observation.outputTokens() != null) {
            return ProviderTransportEvidence.responseReceived(status,
                    ProviderTransportEvidence.UsageProvenance.PROVIDER_REPORTED, null);
        }
        if (response.type() == ModelResponse.ResponseType.PROVIDER_ERROR
                && response.providerErrorKind()
                == ModelResponse.ProviderErrorKind.PROVIDER_RESPONSE_PARSE_FAILURE) {
            return ProviderTransportEvidence.responseReceived(status,
                    ProviderTransportEvidence.UsageProvenance.NOT_AVAILABLE,
                    "RESPONSE_PARSE_FAILURE");
        }
        try {
            JsonNode root = body == null || body.isBlank() ? null : MAPPER.readTree(body);
            if (root != null && root.isObject()
                    && (!root.has("usage") || root.get("usage").isNull())) {
                return ProviderTransportEvidence.responseReceived(status,
                        ProviderTransportEvidence.UsageProvenance.PROVIDER_NOT_RETURNED,
                        "PROVIDER_RESPONSE_USAGE_ABSENT");
            }
            return ProviderTransportEvidence.responseReceived(status,
                    ProviderTransportEvidence.UsageProvenance.NOT_AVAILABLE,
                    "PROVIDER_USAGE_PARTIAL_OR_MALFORMED");
        } catch (Exception ignored) {
            return ProviderTransportEvidence.responseReceived(status,
                    ProviderTransportEvidence.UsageProvenance.NOT_AVAILABLE,
                    "RESPONSE_PARSE_FAILURE");
        }
    }

    private void setRetrySignal(ProviderRetrySignal.Reason reason,boolean retryable,
            Duration retryAfter,String category){
        retrySignals.set(new ProviderRetrySignal(reason,retryable,retryAfter,category));
    }

    private static Duration retryAfter(HttpResponse<?> response){
        String value=response.headers().firstValue("Retry-After").orElse(null);
        if(value==null||value.isBlank())return null;
        try{return Duration.ofSeconds(Math.max(0,Long.parseLong(value.trim())));}
        catch(NumberFormatException ignored){
            try{
                Instant at=ZonedDateTime.parse(value.trim(),DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant();
                return Duration.ofMillis(Math.max(0,at.toEpochMilli()-Instant.now().toEpochMilli()));
            }catch(Exception invalid){return null;}
        }
    }

    private static String sha256(byte[] body){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));}
        catch(Exception impossible){throw new IllegalStateException(impossible);}
    }

    private static boolean isRetryableTlsInterruption(javax.net.ssl.SSLException error){
        for(Throwable cause=error;cause!=null;cause=cause.getCause())
            if(cause instanceof EOFException||cause instanceof SocketException)return true;
        String message=error.getMessage();
        if(message==null)return false;
        String normalized=message.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("eof")||normalized.contains("reset")
                ||normalized.contains("closed")||normalized.contains("terminated");
    }

    private ObjectNode buildRequest(String operation,List<AgentMessage> messages,
            List<ToolDefinition> toolDefinitions, OutputRequirement requirement) {
        boolean continuation=hasToolContinuation(messages);
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        root.put("stream", false);
        root.put(preset.maxTokensField(),Math.min(32_768,preset.maxOutputTokens()));
        if(preset.supportsThinking()){
            root.putObject("thinking").put("type",thinkingMode);
            if("enabled".equals(thinkingMode)&&reasoningEffort!=null)
                root.put("reasoning_effort",reasoningEffort);
        }
        if(requirement.nativeJson() || ("PLAN".equals(operation)&&preset.supportsJsonObject()))
            root.putObject("response_format").put("type","json_object");

        ArrayNode messagesArray = root.putArray("messages");
        Set<String> assistantToolCallIds=new HashSet<>();
        List<String> expectedToolCallIds=new ArrayList<>();
        Set<String> observedToolCallIds=new HashSet<>();
        List<String> orderedObservedToolCallIds=new ArrayList<>();

        // System prompt
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            ObjectNode sysMsg = messagesArray.addObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
        }

        // Convert messages
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
                    if (msg.toolCallJson() != null) {
                        boolean persistedPrivateRequirement=requiresPrivateContinuation(
                                msg.toolCallJson());
                        PrivateAssistantTurn privateTurn;
                        synchronized(privateTurns){privateTurn=privateTurns.get(msg.toolCallId());}
                        if((persistedPrivateRequirement||("enabled".equals(thinkingMode)
                                &&preset.requiresReasoningContentForToolCalls()))
                                &&privateTurn==null)
                            throw new ReasoningContinuationException(
                                    ModelResponse.ProviderErrorKind
                                            .PROVIDER_PRIVATE_CONTINUATION_UNAVAILABLE);
                        String assistantContent=privateTurn==null?msg.content():privateTurn.content;
                        if(preset.requiresAssistantContentForToolCalls()
                                &&assistantContent==null)assistantContent="";
                        if(assistantContent!=null)apiMsg.put("content",assistantContent);
                        if(privateTurn!=null)
                            apiMsg.put("reasoning_content",privateTurn.reasoningContent);
                        try {
                            JsonNode toolCallNode = MAPPER.readTree(privateTurn==null
                                    ?msg.toolCallJson():privateTurn.toolCallsJson);
                            ArrayNode toolCalls = apiMsg.putArray("tool_calls");
                            if(toolCallNode.isArray())for(JsonNode call:toolCallNode){
                                JsonNode sanitized=call.deepCopy();
                                if(sanitized.isObject())((ObjectNode)sanitized)
                                        .remove("x_paicli_private_continuation");
                                toolCalls.add(sanitized);
                                String id=sanitized.path("id").isTextual()
                                        ?sanitized.path("id").asText():null;
                                if(id==null||id.isBlank()||!assistantToolCallIds.add(id))
                                    throw new ReasoningContinuationException(
                                            ModelResponse.ProviderErrorKind
                                                    .TOOL_RESULT_BATCH_CONTINUATION_MISMATCH);
                                expectedToolCallIds.add(id);
                            }else{
                                if(toolCallNode.isObject())((ObjectNode)toolCallNode)
                                        .remove("x_paicli_private_continuation");
                                toolCalls.add(toolCallNode);
                                String id=msg.toolCallId();
                                if(id==null||id.isBlank()||!assistantToolCallIds.add(id))
                                    throw new ReasoningContinuationException(
                                            ModelResponse.ProviderErrorKind
                                                    .TOOL_RESULT_BATCH_CONTINUATION_MISMATCH);
                                expectedToolCallIds.add(id);
                            }
                        } catch (ReasoningContinuationException failure) {
                            throw failure;
                        } catch (Exception e) {
                            throw new ReasoningContinuationException(
                                    ModelResponse.ProviderErrorKind.PROVIDER_REQUEST_SERIALIZATION_FAILURE);
                        }
                    } else if (msg.content() != null) {
                        apiMsg.put("content", msg.content());
                    }
                }
                case OBSERVATION -> {
                    apiMsg.put("role", "tool");
                    if (msg.toolCallId() != null) {
                        String id=msg.toolCallId();
                        int expectedIndex=orderedObservedToolCallIds.size();
                        if(expectedIndex>=expectedToolCallIds.size()
                                ||!expectedToolCallIds.get(expectedIndex).equals(id)
                                ||!observedToolCallIds.add(id))
                            throw new ReasoningContinuationException(ModelResponse.ProviderErrorKind
                                    .TOOL_RESULT_BATCH_CONTINUATION_MISMATCH);
                        apiMsg.put("tool_call_id", id);
                        orderedObservedToolCallIds.add(id);
                    }else if(continuation){
                        throw new ReasoningContinuationException(ModelResponse.ProviderErrorKind
                                .TOOL_RESULT_BATCH_CONTINUATION_MISMATCH);
                    }
                    apiMsg.put("content", msg.content());
                }
            }
        }
        if(continuation&&orderedObservedToolCallIds.size()!=expectedToolCallIds.size())
            throw new ReasoningContinuationException(ModelResponse.ProviderErrorKind
                    .TOOL_RESULT_MISSING_FOR_BATCH_CONTINUATION);

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

    private ModelResponse parseResponse(String body, ResponseIdentityFact responseIdentity,
            String operation,int httpStatus, OutputRequirement requirement) {
        if(body==null||body.isBlank()){
            observations.set(identityOnly(responseIdentity)
                    .withError("PROVIDER_EMPTY_RESPONSE",false));
            return ModelResponse.providerError(
                    ModelResponse.ProviderErrorKind.PROVIDER_EMPTY_RESPONSE);
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                observations.set(ProviderObservationNormalizer.openAiCompatible(
                        root, null, null, null, responseIdentity.safeResponseId(),
                        responseIdentity.provenance())
                        .withError("PROVIDER_RESPONSE_PARSE_FAILURE", false));
                return ModelResponse.providerError(
                        ModelResponse.ProviderErrorKind.PROVIDER_RESPONSE_PARSE_FAILURE);
            }

            JsonNode firstChoice = choices.get(0);
            String finishReason = firstChoice.has("finish_reason")
                    && firstChoice.get("finish_reason").isTextual()
                    ? firstChoice.get("finish_reason").textValue() : null;
            JsonNode messageNode = firstChoice.get("message");
            if (messageNode == null || !messageNode.isObject()) {
                observations.set(ProviderObservationNormalizer.openAiCompatible(
                        root, null, null, finishReason, responseIdentity.safeResponseId(),
                        responseIdentity.provenance())
                        .withError("PROVIDER_RESPONSE_PARSE_FAILURE", false));
                return ModelResponse.providerError(
                        ModelResponse.ProviderErrorKind.PROVIDER_RESPONSE_PARSE_FAILURE);
            }

            boolean contentPresent=messageNode.has("content");
            boolean contentNull=contentPresent&&messageNode.get("content").isNull();
            String content = contentPresent&&!contentNull
                    ? messageNode.get("content").asText() : null;
            ReasoningContent reasoning=ReasoningContent.from(messageNode);
            String reasoningContent=reasoning.text();

            // Check for tool calls
            JsonNode toolCallsNode = messageNode.get("tool_calls");
            ProviderToolCallAdmission.Result admission =
                    ProviderToolCallAdmission.inspect(toolCallsNode);
            observations.set(ProviderObservationNormalizer.openAiCompatible(root,
                    admission.envelope().cardinality(),
                    admission.envelope().diagnostic().callCount(),
                    finishReason, responseIdentity.safeResponseId(),
                    responseIdentity.provenance()));
            boolean hasCalls = admission.envelope().cardinality()
                    != ProviderResponseEnvelope.ToolCallCardinality.ZERO;
            if (requirement.managed() && !(hasCalls
                    ? ("tool_calls".equals(finishReason)
                        || ("generic-v1".equals(preset.name()) && "stop".equals(finishReason)))
                    : "stop".equals(finishReason))) {
                observations.set(observations.get().withError("PROVIDER_RESPONSE_INCOMPLETE", false));
                return ModelResponse.providerError(ModelResponse.ProviderErrorKind.PROVIDER_RESPONSE_INCOMPLETE);
            }
            traceResponseMetadata(root,operation,httpStatus,finishReason,contentPresent,contentNull,
                    content,reasoning,toolCallsNode,
                    responseIdentity.safeResponseId(),admission);
            if (admission.envelope().cardinality()
                    != ProviderResponseEnvelope.ToolCallCardinality.ZERO) {
                if(admission.envelope().diagnostic().missingIdentity())
                    return protocolFailure(ModelResponse.ProviderErrorKind.TOOL_CALL_ID_MISSING);
                if(admission.envelope().diagnostic().duplicate())
                    return protocolFailure(ModelResponse.ProviderErrorKind.TOOL_CALL_ID_DUPLICATE);
                if("enabled".equals(thinkingMode)){
                    if(!"tool_calls".equals(finishReason))
                        return protocolFailure(
                                ModelResponse.ProviderErrorKind.FINISH_REASON_CONFLICT);
                    if(preset.requiresReasoningContentForToolCalls()
                            &&!reasoning.validForContinuation())
                        return reasoningProtocolFailure(reasoning);
                    String continuationContent=content;
                    if(preset.requiresAssistantContentForToolCalls()
                            &&continuationContent==null)continuationContent="";
                    List<ToolCall> admittedCalls=admission.toolCalls();
                    if(admittedCalls.isEmpty())return ModelResponse.protocolError(
                            admission.envelope());
                    List<String> callIds=admittedCalls.stream()
                            .map(ToolCall::providerCallId).toList();
                    PrivateAssistantTurn accepted=new PrivateAssistantTurn(
                            continuationContent,reasoningContent,
                            toolCallsNode.toString(),callIds,
                            responseIdentity.safeResponseId(),finishReason);
                    if(!registerPrivateTurn(accepted)){
                        return protocolFailure(
                                ModelResponse.ProviderErrorKind.TOOL_CALL_ID_DUPLICATE);
                    }
                    com.paicli.diagnostic.StageTrace.event(
                            "PROVIDER_PRIVATE_TURN_CREATED",false,
                            "provider_call_id_hash",safeHash(responseIdentity.safeResponseId()),
                            "tool_call_count",callIds.size(),
                            "content_length",continuationContent==null?0:continuationContent.length(),
                            "reasoning_length",reasoningContent.length(),
                            "finish_reason",finishReason,
                            "creation_result","CREATED");
                    content=continuationContent;
                }
                if("enabled".equals(thinkingMode))
                    return ModelResponse.toolCallBatchResponse(content,
                            admission.toolCalls(),admission.envelope(),true);
                return ProviderToolCallAdmission.toResponse(content, admission);
            }

            if (content != null && !content.isBlank()) {
                return ModelResponse.finalAnswer(content);
            }

            observations.set(observations.get().withError(
                    "PROVIDER_EMPTY_RESPONSE",false));
            return ModelResponse.providerError(
                    ModelResponse.ProviderErrorKind.PROVIDER_EMPTY_RESPONSE);

        } catch (Exception e) {
            observations.set(identityOnly(responseIdentity)
                    .withError("PROVIDER_RESPONSE_PARSE_FAILURE", false));
            return ModelResponse.providerError(
                    ModelResponse.ProviderErrorKind.PROVIDER_RESPONSE_PARSE_FAILURE);
        }
    }

    private static ProviderCallObservation identityOnly(ResponseIdentityFact identity) {
        return ProviderCallObservation.notExposed().withResponseIdentity(
                identity.safeResponseId(), identity.provenance());
    }

    private static ResponseIdentityFact responseIdentity(String body, String headerId) {
        if (headerId != null && !headerId.isBlank()) {
            return new ResponseIdentityFact(safeResponseId(headerId),
                    ProviderCallObservation.ResponseIdentityProvenance.PROVIDER_RETURNED);
        }
        if (body == null || body.isBlank()) {
            return new ResponseIdentityFact(null,
                    ProviderCallObservation.ResponseIdentityProvenance.PROVIDER_NOT_RETURNED);
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root != null && root.isObject()) {
                JsonNode id = root.get("id");
                if (id != null && id.isTextual() && !id.textValue().isBlank()) {
                    return new ResponseIdentityFact(safeResponseId(id.textValue()),
                            ProviderCallObservation.ResponseIdentityProvenance
                                    .PROVIDER_RETURNED);
                }
                return new ResponseIdentityFact(null,
                        ProviderCallObservation.ResponseIdentityProvenance
                                .PROVIDER_NOT_RETURNED);
            }
        } catch (Exception ignored) {
            // A malformed body cannot prove that the Provider omitted an identity.
        }
        return new ResponseIdentityFact(null,
                ProviderCallObservation.ResponseIdentityProvenance.ADAPTER_NOT_EXPOSED);
    }

    private record ResponseIdentityFact(String safeResponseId,
            ProviderCallObservation.ResponseIdentityProvenance provenance) { }

    private static String safeResponseId(String value) {
        if (value == null || value.isBlank()) return null;
        String clean = value.trim();
        return clean.length() <= 128 && clean.matches("[A-Za-z0-9._:-]+") ? clean : null;
    }

    private ModelResponse protocolFailure(ModelResponse.ProviderErrorKind kind){
        observations.set(observations.get().withError(kind.name(),false));
        com.paicli.diagnostic.StageTrace.event(
                "PROVIDER_RESPONSE_PROTOCOL_FAILURE",true,
                "protocol_failure_subcategory",kind.name());
        return ModelResponse.providerError(kind);
    }

    private ModelResponse reasoningProtocolFailure(ReasoningContent reasoning){
        String subcategory=reasoning.failureSubcategory();
        observations.set(observations.get().withError(subcategory,false));
        com.paicli.diagnostic.StageTrace.event(
                "PROVIDER_RESPONSE_PROTOCOL_FAILURE",true,
                "protocol_failure_subcategory",subcategory);
        return ModelResponse.providerError(ModelResponse.ProviderErrorKind
                .PROVIDER_PRIVATE_CONTINUATION_INCOMPLETE);
    }

    private static void traceRequestMetadata(String operation,ObjectNode request){
        JsonNode thinking=request.get("thinking");
        JsonNode tools=request.get("tools");
        JsonNode messages=request.get("messages");
        com.paicli.diagnostic.StageTrace.event(
                "PROVIDER_REQUEST_METADATA",false,
                "request_operation",operation,
                "model",request.path("model").asText(null),
                "thinking_type",thinking==null?null:thinking.path("type").asText(null),
                "reasoning_effort",request.path("reasoning_effort").asText(null),
                "tool_count",tools!=null&&tools.isArray()?tools.size():0,
                "tool_choice_present",request.has("tool_choice"),
                "message_count",messages!=null&&messages.isArray()?messages.size():0);
    }

    private static void traceResponseMetadata(JsonNode root,String operation,int httpStatus,
            String finishReason,boolean contentPresent,boolean contentNull,String content,
            ReasoningContent reasoning,JsonNode toolCallsNode,String safeRequestId,
            ProviderToolCallAdmission.Result admission){
        int toolCallCount=toolCallsNode!=null&&toolCallsNode.isArray()
                ?toolCallsNode.size():0;
        String completionId=root.path("id").isTextual()?root.path("id").asText():null;
        String fingerprint=root.path("system_fingerprint").isTextual()
                ?root.path("system_fingerprint").asText():null;
        JsonNode usage=root.path("usage");
        JsonNode reasoningTokens=usage.path("reasoning_tokens");
        if(!reasoningTokens.canConvertToInt())reasoningTokens=usage
                .path("completion_tokens_details").path("reasoning_tokens");
        com.paicli.diagnostic.StageTrace.event(
                "PROVIDER_RESPONSE_METADATA",true,
                "http_status",httpStatus,
                "request_operation",operation,
                "completion_id_present",completionId!=null,
                "completion_id_hash",safeHash(completionId),
                "finish_reason",finishReason,
                "content_present",contentPresent,
                "content_null",contentNull,
                "content_empty",content!=null&&content.isEmpty(),
                "content_length",content==null?0:content.length(),
                "reasoning_state",reasoning.state().name(),
                "reasoning_present",reasoning.state()!=ReasoningContentState.ABSENT,
                "reasoning_null",reasoning.state()==ReasoningContentState.PRESENT_NULL,
                "reasoning_empty",reasoning.state()==ReasoningContentState.PRESENT_EMPTY,
                "reasoning_length",reasoning.text()==null?0:reasoning.text().length(),
                "tool_call_count",toolCallCount,
                "provider_request_id_present",safeRequestId!=null,
                "provider_request_id_hash",safeHash(safeRequestId),
                "usage_reasoning_tokens",reasoningTokens.canConvertToInt()
                        ?reasoningTokens.intValue():null,
                "system_fingerprint_present",fingerprint!=null,
                "system_fingerprint_hash",safeHash(fingerprint),
                "duplicate_call_id",admission.envelope().diagnostic().duplicate(),
                "private_turn_creation","NOT_ATTEMPTED");
        if(toolCallsNode==null||!toolCallsNode.isArray())return;
        int limit=Math.min(toolCallsNode.size(),8);
        for(int index=0;index<limit;index++){
            JsonNode call=toolCallsNode.get(index);
            JsonNode id=call==null?null:call.get("id");
            JsonNode function=call==null?null:call.get("function");
            JsonNode arguments=function==null?null:function.get("arguments");
            String callId=id!=null&&id.isTextual()?id.asText():null;
            String functionName=function!=null&&function.path("name").isTextual()
                    ?function.path("name").asText():null;
            com.paicli.diagnostic.StageTrace.event(
                    "PROVIDER_TOOL_CALL_METADATA",false,
                    "call_index",index,
                    "id_present",callId!=null&&!callId.isBlank(),
                    "id_hash",safeHash(callId),
                    "function_name",functionName,
                    "arguments_length",arguments==null?0:
                            (arguments.isTextual()?arguments.asText().length():arguments.toString().length()));
        }
    }

    private static String safeHash(String value){
        return value==null||value.isBlank()?"<absent>":
                com.paicli.diagnostic.StageTrace.hashArguments(java.util.List.of(value));
    }

    @Override
    public ProviderCallObservation takeObservation() {
        ProviderCallObservation value = observations.get();
        observations.remove();
        return value;
    }

    @Override
    public Integer takeHttpStatus() {
        Integer value = httpStatuses.get();
        httpStatuses.remove();
        return value;
    }

    @Override
    public ProviderTransportEvidence takeTransportEvidence() {
        ProviderTransportEvidence value = transportEvidence.get();
        transportEvidence.set(ProviderTransportEvidence.notStarted("NO_CURRENT_ATTEMPT"));
        return value;
    }

    @Override
    public ProviderRetrySignal takeRetrySignal(){
        ProviderRetrySignal value=retrySignals.get();retrySignals.remove();return value;
    }

    public String thinkingMode(){return thinkingMode;}
    public String reasoningEffort(){return reasoningEffort;}

    private static boolean hasToolContinuation(List<AgentMessage> messages){
        return messages!=null&&messages.stream().anyMatch(message ->
                message.type()==AgentMessage.MessageType.OBSERVATION
                        &&message.toolCallId()!=null);
    }

    private static boolean requiresPrivateContinuation(String toolCallJson){
        if(toolCallJson==null)return false;
        try{
            JsonNode parsed=MAPPER.readTree(toolCallJson);
            if(parsed.isArray())for(JsonNode call:parsed)
                if(call.path("x_paicli_private_continuation").asBoolean(false))return true;
            return parsed.path("x_paicli_private_continuation").asBoolean(false);
        }
        catch(Exception ignored){return false;}
    }

    private boolean registerPrivateTurn(PrivateAssistantTurn turn){
        synchronized(privateTurns){
            if(turn.toolCallIds.stream().anyMatch(privateTurns::containsKey))return false;
            for(String id:turn.toolCallIds)privateTurns.put(id,turn);
            return true;
        }
    }

    private enum ReasoningContentState {
        ABSENT, PRESENT_NULL, PRESENT_EMPTY, PRESENT_TEXT, PRESENT_NON_TEXTUAL
    }

    private record ReasoningContent(ReasoningContentState state,String text){
        private ReasoningContent{
            java.util.Objects.requireNonNull(state,"state");
            if((state==ReasoningContentState.PRESENT_EMPTY
                    ||state==ReasoningContentState.PRESENT_TEXT)!=(text!=null))
                throw new IllegalArgumentException("reasoning state/text mismatch");
            if(state==ReasoningContentState.PRESENT_EMPTY&&!text.isEmpty())
                throw new IllegalArgumentException("empty reasoning state requires empty text");
            if(state==ReasoningContentState.PRESENT_TEXT&&text.isEmpty())
                throw new IllegalArgumentException("text reasoning state requires text");
        }
        private static ReasoningContent from(JsonNode message){
            if(!message.has("reasoning_content"))
                return new ReasoningContent(ReasoningContentState.ABSENT,null);
            JsonNode value=message.get("reasoning_content");
            if(value==null||value.isNull())
                return new ReasoningContent(ReasoningContentState.PRESENT_NULL,null);
            if(!value.isTextual())
                return new ReasoningContent(ReasoningContentState.PRESENT_NON_TEXTUAL,null);
            String text=value.textValue();
            return new ReasoningContent(text.isEmpty()?ReasoningContentState.PRESENT_EMPTY:
                    ReasoningContentState.PRESENT_TEXT,text);
        }
        private boolean validForContinuation(){
            return state==ReasoningContentState.PRESENT_EMPTY
                    ||state==ReasoningContentState.PRESENT_TEXT;
        }
        private String failureSubcategory(){
            return switch(state){
                case ABSENT -> "REASONING_CONTENT_ABSENT_FOR_TOOL_CONTINUATION";
                case PRESENT_NULL -> "REASONING_CONTENT_NULL_FOR_TOOL_CONTINUATION";
                case PRESENT_NON_TEXTUAL ->
                        "REASONING_CONTENT_INVALID_TYPE_FOR_TOOL_CONTINUATION";
                case PRESENT_EMPTY,PRESENT_TEXT -> throw new IllegalStateException(
                        "valid reasoning content has no failure kind");
            };
        }
    }

    /** Provider-private sensitive state. It has no public serializer and a redacted string form. */
    private static final class PrivateAssistantTurn {
        private final String content;
        private final String reasoningContent;
        private final String toolCallsJson;
        private final List<String> toolCallIds;
        @SuppressWarnings("unused") private final String providerRequestId;
        @SuppressWarnings("unused") private final String finishReason;
        private PrivateAssistantTurn(String content,String reasoningContent,String toolCallsJson,
                List<String> toolCallIds,String providerRequestId,String finishReason){
            this.content=content;
            this.reasoningContent=java.util.Objects.requireNonNull(reasoningContent,
                    "reasoningContent");
            this.toolCallsJson=toolCallsJson;this.toolCallIds=List.copyOf(toolCallIds);
            this.providerRequestId=providerRequestId;this.finishReason=finishReason;
        }
        @Override public String toString(){return "PrivateAssistantTurn[redacted]";}
    }

    private static final class ReasoningContinuationException extends RuntimeException {
        private final ModelResponse.ProviderErrorKind kind;
        private ReasoningContinuationException(ModelResponse.ProviderErrorKind kind){
            super(kind.name());this.kind=kind;
        }
    }

    @FunctionalInterface
    interface HttpTransport {
        HttpResponse<String> send(HttpRequest request) throws IOException,InterruptedException;
    }
}
