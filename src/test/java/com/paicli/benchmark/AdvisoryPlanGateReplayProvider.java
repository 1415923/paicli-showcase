package com.paicli.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.PreparedProviderTurn;
import com.paicli.provider.PreparedTurnProvider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Network-free Provider for the explicit Advisory Plan calibration launch capsule. */
final class AdvisoryPlanGateReplayProvider implements PreparedTurnProvider {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String taskId;
    private final JsonNode task;
    private final AtomicInteger prepared = new AtomicInteger();
    private final AtomicInteger chatOrdinal = new AtomicInteger();

    AdvisoryPlanGateReplayProvider(String taskId, JsonNode task) {
        this.taskId = taskId;
        this.task = task;
    }

    @Override
    public PreparedProviderTurn prepareTurn(String operation, List<AgentMessage> messages,
            List<ToolDefinition> tools) {
        int ordinal = prepared.incrementAndGet();
        String digest = sha256(taskId + "\n" + operation + "\n" + ordinal + "\n"
                + messages.size() + "\n" + tools.size());
        return new PreparedProviderTurn(digest, () -> "PLAN".equals(operation)
                ? ModelResponse.finalAnswer(plan()) : chat());
    }

    private ModelResponse chat() {
        int ordinal = chatOrdinal.getAndIncrement();
        if (ordinal % 2 == 0) {
            return ModelResponse.toolCallResponse("inspect frozen workspace",
                    new ToolCall("list_files", Map.of("path", "."),
                            "advisory-calibration-" + taskId + "-" + ordinal));
        }
        List<String> requiredFacts = JSON.convertValue(
                task.path("oracle").path("requiredAnswerFacts"),
                JSON.getTypeFactory().constructCollectionType(List.class, String.class));
        String facts = String.join(" ", requiredFacts);
        return ModelResponse.finalAnswer("offline launcher readiness " + facts);
    }

    private String plan() {
        ObjectNode root = JSON.createObjectNode();
        root.putArray("acceptanceCriteria").add("exercise one frozen Tool path");
        root.putArray("assumptions");
        root.putArray("risks");
        root.put("verificationStrategy", "use the frozen task verification contract");
        ArrayNode steps = root.putArray("steps");
        ObjectNode step = steps.addObject();
        step.put("title", "Inspect the frozen workspace");
        step.put("definition", "Use one read-only Tool before producing a result");
        step.putArray("dependsOn");
        try {
            return JSON.writeValueAsString(root);
        } catch (Exception error) {
            throw new IllegalStateException("offline Advisory Plan serialization failed", error);
        }
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
