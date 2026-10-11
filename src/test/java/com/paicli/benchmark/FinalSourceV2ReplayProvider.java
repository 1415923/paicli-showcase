package com.paicli.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
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

/** Direct-memory, deterministic, zero-network Provider for the task-005 pair rehearsal. */
final class FinalSourceV2ReplayProvider implements PreparedTurnProvider {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FIXED_SOURCE = "package fixture;\n"
            + "public final class Range { public static int clamp(int value, int min, int max) "
            + "{ return Math.max(min, Math.min(value, max)); } }\n";
    private final AtomicInteger prepared = new AtomicInteger();
    private final AtomicInteger chatOrdinal = new AtomicInteger();

    @Override
    public PreparedProviderTurn prepareTurn(String operation, List<AgentMessage> messages,
            List<ToolDefinition> tools) {
        int ordinal = prepared.incrementAndGet();
        String digest = sha256(operation + "\n" + ordinal + "\n" + messages.size()
                + "\n" + tools.size());
        return new PreparedProviderTurn(digest, () -> "PLAN".equals(operation)
                ? ModelResponse.finalAnswer(plan()) : chat());
    }

    private String plan() {
        ObjectNode root = JSON.createObjectNode();
        root.putArray("acceptanceCriteria").add("Range.clamp passes the frozen Maven oracle");
        root.putArray("assumptions");
        root.putArray("risks");
        root.put("verificationStrategy", "run the frozen mvn -q -o test verification");
        ObjectNode step = root.putArray("steps").addObject();
        step.put("title", "Correct Range.clamp");
        step.put("definition", "Write only the frozen production fixture file");
        step.putArray("dependsOn");
        try {
            return JSON.writeValueAsString(root);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private ModelResponse chat() {
        if (chatOrdinal.getAndIncrement() == 0) {
            return ModelResponse.toolCallResponse("apply the bounded task-005 correction",
                    new ToolCall("write_file", Map.of(
                            "path", "src/main/java/fixture/Range.java",
                            "content", FIXED_SOURCE,
                            "overwrite", true), "final-source-v2-task-005-write"));
        }
        return ModelResponse.finalAnswer(
                "Corrected Range.clamp and verified the frozen Maven tests.");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
