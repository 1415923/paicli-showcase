package com.paicli.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.PreparedProviderTurn;
import com.paicli.provider.PreparedTurnProvider;
import com.paicli.provider.ProviderResponseEnvelope;
import com.paicli.provider.telemetry.ProviderCallObservation;
import com.paicli.provider.telemetry.ProviderObservationSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Network-free Provider for the production opt-in dogfood launch rehearsal. */
final class ProductionContinuityDogfoodReplayProvider
        implements PreparedTurnProvider, ProviderObservationSource {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String taskId;
    private final JsonNode task;
    private final AtomicInteger ordinal = new AtomicInteger();
    private final AtomicInteger chatOrdinal = new AtomicInteger();
    private final AtomicReference<ProviderCallObservation> observation = new AtomicReference<>();

    ProductionContinuityDogfoodReplayProvider(String taskId, JsonNode task) {
        this.taskId = taskId;
        this.task = task;
    }

    @Override
    public PreparedProviderTurn prepareTurn(String operation, List<AgentMessage> messages,
            List<ToolDefinition> tools) {
        int current = ordinal.incrementAndGet();
        String digest = sha256(taskId + "\n" + operation + "\n" + current + "\n"
                + messages.size() + "\n" + tools.size());
        return new PreparedProviderTurn(digest, () -> {
            ModelResponse response = "PLAN".equals(operation)
                    ? ModelResponse.finalAnswer(plan()) : chat();
            int toolCalls = response.type() == ModelResponse.ResponseType.TOOL_CALL
                    ? response.toolCalls().size() : 0;
            observation.set(new ProviderCallObservation(32, 8, 40, 0, 0, 0,
                    ProviderCallObservation.UsageSource.PROVIDER_REPORTED,
                    ProviderCallObservation.UsageCoverage.COMPLETE, toolCalls,
                    toolCalls == 0 ? ProviderResponseEnvelope.ToolCallCardinality.ZERO
                            : ProviderResponseEnvelope.ToolCallCardinality.ONE,
                    toolCalls == 0 ? "stop" : "tool_calls", "dogfood-replay-" + current,
                    null, false));
            return response;
        });
    }

    @Override
    public ProviderCallObservation takeObservation() {
        ProviderCallObservation value = observation.getAndSet(null);
        return value == null ? ProviderCallObservation.notExposed() : value;
    }

    private ModelResponse chat() {
        if (chatOrdinal.getAndIncrement() == 0) {
            return ModelResponse.toolCallResponse("inspect the production-like workspace",
                    new ToolCall("list_files", Map.of("path", "."),
                            "dogfood-replay-" + taskId + "-list"));
        }
        List<String> requiredFacts = JSON.convertValue(
                task.path("oracle").path("requiredAnswerFacts"),
                JSON.getTypeFactory().constructCollectionType(List.class, String.class));
        return ModelResponse.finalAnswer("provider-disabled production opt-in rehearsal "
                + String.join(" ", requiredFacts));
    }

    private static String plan() {
        return """
                {"acceptanceCriteria":["exercise the production opt-in execution path"],
                 "assumptions":[],"risks":[],
                 "verificationStrategy":"use the frozen task verification contract",
                 "steps":[{"title":"Inspect and report","definition":"Inspect the workspace and report through the ordinary production treatment","dependsOn":[]}]}
                """;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}
