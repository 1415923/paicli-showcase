package com.paicli.benchmark;

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

/** Deterministic fake Provider for the exact-shape continuity calibration rehearsal. */
final class ContinuityCalibrationReplayProvider implements PreparedTurnProvider {
    private final String taskId;
    private final AtomicInteger prepared = new AtomicInteger();
    private final AtomicInteger chatOrdinal = new AtomicInteger();

    ContinuityCalibrationReplayProvider(String taskId) { this.taskId = taskId; }

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
        return switch (ordinal) {
            case 0 -> ModelResponse.toolCallResponse("inspect the production file",
                    new ToolCall("read_file", Map.of("path", productionPath()),
                            taskId + "-read"));
            case 1 -> ModelResponse.toolCallResponse("apply the bounded fix",
                    new ToolCall("write_file", Map.of("path", productionPath(),
                            "content", solution(), "overwrite", true), taskId + "-write"));
            case 2 -> ModelResponse.toolCallResponse("verify the fixture",
                    new ToolCall("run_command", Map.of("command", "mvn -q -o test"),
                            taskId + "-verify"));
            case 3 -> ModelResponse.finalAnswer("The requested fix is implemented and verified.");
            default -> throw new IllegalStateException("unexpected continuity replay turn");
        };
    }

    private String productionPath() {
        return switch (taskId) {
            case "continuity-001" -> "src/main/java/continuityfixture/Multiplier.java";
            case "continuity-002" -> "src/main/java/continuityfixture/NameFormatter.java";
            case "continuity-003" -> "src/main/java/continuityfixture/Range.java";
            case "continuity-004" -> "src/main/java/continuityfixture/Counter.java";
            case "continuity-005" -> "src/main/java/continuityfixture/PathJoiner.java";
            default -> throw new IllegalArgumentException("unknown continuity fixture");
        };
    }

    private String solution() {
        return switch (taskId) {
            case "continuity-001" -> """
                    package continuityfixture;

                    public final class Multiplier {
                        private Multiplier() { }
                        public static int doubleValue(int value) { return value * 2; }
                    }
                    """;
            case "continuity-002" -> """
                    package continuityfixture;

                    public final class NameFormatter {
                        private NameFormatter() { }
                        public static String initials(String name) {
                            String clean = name.trim();
                            if (clean.isEmpty()) return "";
                            StringBuilder result = new StringBuilder();
                            for (String part : clean.split("\\\\s+"))
                                if (!part.isEmpty()) result.append(Character.toUpperCase(part.charAt(0)));
                            return result.toString();
                        }
                    }
                    """;
            case "continuity-003" -> """
                    package continuityfixture;

                    public final class Range {
                        private Range() { }
                        public static boolean containsInclusive(int minimum, int maximum, int value) {
                            return value >= minimum && value <= maximum;
                        }
                    }
                    """;
            case "continuity-004" -> """
                    package continuityfixture;

                    public final class Counter {
                        private int value;
                        public Counter(int initialValue) { value = initialValue; }
                        public int next() { return ++value; }
                    }
                    """;
            case "continuity-005" -> """
                    package continuityfixture;

                    public final class PathJoiner {
                        private PathJoiner() { }
                        public static String join(String left, String right) {
                            return left.replaceAll("/+$", "") + "/" + right.replaceAll("^/+", "");
                        }
                    }
                    """;
            default -> throw new IllegalArgumentException("unknown continuity fixture");
        };
    }

    private String plan() {
        return """
                {"acceptanceCriteria":["production fix passes Maven tests"],
                 "assumptions":[],"risks":[],
                 "verificationStrategy":"run the frozen Maven oracle",
                 "steps":[{"title":"Implement and verify","definition":"Inspect, edit, and verify the single production file","dependsOn":[]}]}
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
