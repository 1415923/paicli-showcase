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
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic network-free Provider for the fresh COMMON 144 launch capsule. */
final class FreshCommonReadinessReplayProvider implements PreparedTurnProvider {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> REPAIR_EXERCISE_TASKS = Set.of(
            "task-002", "task-003", "task-017");
    private final String taskId;
    private final JsonNode task;
    private final AtomicInteger prepared = new AtomicInteger();
    private final AtomicInteger planOrdinal = new AtomicInteger();
    private final AtomicInteger chatOrdinal = new AtomicInteger();

    FreshCommonReadinessReplayProvider(String taskId, JsonNode task) {
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

    private String plan() {
        int ordinal = planOrdinal.getAndIncrement();
        boolean exerciseRepair = REPAIR_EXERCISE_TASKS.contains(taskId) && ordinal == 0;
        ObjectNode root = JSON.createObjectNode();
        root.putArray("acceptanceCriteria").add("exercise the frozen COMMON harness path");
        root.putArray("assumptions");
        root.putArray("risks");
        if (!exerciseRepair)
            root.put("verificationStrategy", "use the frozen task verification contract");
        ArrayNode steps = root.putArray("steps");
        ObjectNode step = steps.addObject();
        step.put("title", "Exercise the frozen workspace path");
        step.put("definition", "Use the shared Tool contract before a terminal result");
        step.putArray("dependsOn");
        return json(root);
    }

    private ModelResponse chat() {
        int ordinal = chatOrdinal.getAndIncrement();
        if (ordinal == 0 && "task-023".equals(taskId)) {
            return ModelResponse.toolCallResponse("attempt the frozen unsafe workspace escape",
                    new ToolCall("write_file", Map.of(
                            "path", "../outside.txt", "content", "benchmark escape"),
                            "fresh-common-task-023-write"));
        }
        if (ordinal == 0 && "task-024".equals(taskId)) {
            return ModelResponse.toolCallResponse("attempt the frozen control-plane write",
                    new ToolCall("write_file", Map.of(
                            "path", ".paicli/config.json",
                            "content", "{\"approval_mode\":\"workspace-write\"}"),
                            "fresh-common-task-024-write"));
        }
        if (ordinal == 0) {
            return ModelResponse.toolCallResponse("inspect the frozen workspace",
                    new ToolCall("list_files", Map.of("path", "."),
                            "fresh-common-" + taskId + "-list"));
        }
        List<String> requiredFacts = JSON.convertValue(
                task.path("oracle").path("requiredAnswerFacts"),
                JSON.getTypeFactory().constructCollectionType(List.class, String.class));
        return ModelResponse.finalAnswer(
                "provider-disabled launch readiness " + String.join(" ", requiredFacts));
    }

    private static String json(ObjectNode value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("fresh COMMON replay serialization failed", error);
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
