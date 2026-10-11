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
import com.paicli.provider.ProviderRetrySignal;
import com.paicli.provider.ProviderRetrySignalSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic, network-free Provider replay for the six frozen runtime-contract fixtures. */
final class RuntimeContractReplayProvider
        implements PreparedTurnProvider, ProviderRetrySignalSource {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final String taskId;
    private final JsonNode task;
    private final String plan;
    private final AtomicInteger chatOrdinal = new AtomicInteger();
    private final AtomicInteger preparedTurns = new AtomicInteger();

    RuntimeContractReplayProvider(String taskId, JsonNode task, Path workspace) {
        this.taskId = taskId;
        this.task = task;
        this.plan = plan(taskId, task);
    }

    @Override
    public PreparedProviderTurn prepareTurn(String operation, List<AgentMessage> messages,
            List<ToolDefinition> tools) {
        int prepared = preparedTurns.incrementAndGet();
        String digest = sha256(taskId + "\n" + operation + "\n" + prepared + "\n"
                + messages.size() + "\n" + tools.size());
        return new PreparedProviderTurn(digest, () -> "PLAN".equals(operation)
                ? ModelResponse.finalAnswer(plan) : nextChat());
    }

    @Override
    public ProviderRetrySignal takeRetrySignal() {
        return null;
    }

    int preparedTurns() {
        return preparedTurns.get();
    }

    private ModelResponse nextChat() {
        int ordinal = chatOrdinal.getAndIncrement();
        return switch (taskId) {
            case "task-025" -> switch (ordinal) {
                case 0 -> read("facts/parent-a.txt", "rt-025-parent-a");
                case 1 -> read("facts/parent-b.txt", "rt-025-parent-b");
                case 2 -> ModelResponse.finalAnswer("alpha=17 beta=29");
                case 3 -> read("facts/child.txt", "rt-025-child");
                default -> ModelResponse.finalAnswer("alpha=17 beta=29 gamma=43");
            };
            case "task-026" -> switch (ordinal) {
                case 0 -> write("src/main/java/fixture/Counter.java", """
                        package fixture;
                        public record Counter(int value) {
                            public Counter increment(){ return new Counter(value + 1); }
                        }
                        """, "rt-026-parent-write");
                case 1 -> ModelResponse.toolCallResponse("compile parent increment",
                        new ToolCall("run_command", Map.of(
                                "command", "mvn -q -o -DskipTests compile"),
                                "rt-026-parent-compile"));
                case 2 -> ModelResponse.finalAnswer("increment implemented; continuation pending");
                case 3 -> write("src/main/java/fixture/Counter.java", """
                        package fixture;
                        public record Counter(int value) {
                            public Counter increment(){ return new Counter(value + 1); }
                            public Counter decrement(){ return new Counter(value - 1); }
                        }
                        """, "rt-026-child-write");
                default -> ModelResponse.finalAnswer("increment and decrement implemented");
            };
            case "task-027" -> switch (ordinal) {
                case 0 -> read("facts/catalog.txt", "rt-027-catalog");
                case 1 -> read("facts/catalog.txt", 14, "rt-027-catalog-bounded");
                case 2 -> read("facts/catalog.txt", 8, "rt-027-catalog-eight");
                case 3 -> read("facts/catalog.txt", 4, "rt-027-catalog-four");
                case 4 -> read("facts/catalog.txt", 1, "rt-027-catalog-one");
                case 5 -> read("facts/catalog.txt", 2, "rt-027-catalog-two");
                default -> ModelResponse.finalAnswer("item-01=amber item-02=blue item-03=cyan "
                        + "item-04=green item-05=indigo item-06=orange item-07=violet");
            };
            case "task-028" -> switch (ordinal) {
                case 0 -> read("facts/part-1.txt", "rt-028-north");
                case 1 -> read("facts/part-2.txt", "rt-028-south");
                case 2 -> read("facts/part-3.txt", "rt-028-west");
                case 3 -> read("facts/part-1.txt", 1, "rt-028-north-bounded");
                case 4 -> read("facts/part-2.txt", 1, "rt-028-south-bounded");
                case 5 -> read("facts/part-3.txt", 1, "rt-028-west-bounded");
                default -> ModelResponse.finalAnswer("north=11 south=22 west=33");
            };
            case "task-029" -> switch (ordinal) {
                case 0 -> read("facts/recover.txt", "rt-029-read");
                default -> ModelResponse.finalAnswer("recovered-value=71");
            };
            case "task-030" -> switch (ordinal) {
                case 0 -> read("input/status-source.txt", "rt-030-read");
                case 1 -> write("output/status.txt", "status=ready-for-reconciliation\n",
                        "rt-030-write");
                default -> ModelResponse.finalAnswer("must not reach final after unknown write");
            };
            default -> throw new IllegalStateException("unsupported runtime fixture: " + taskId);
        };
    }

    private static ModelResponse read(String path, String id) {
        return ModelResponse.toolCallResponse("read frozen fixture",
                new ToolCall("read_file", Map.of("path", path), id));
    }

    private static ModelResponse read(String path, int maxLines, String id) {
        return ModelResponse.toolCallResponse("read bounded frozen fixture",
                new ToolCall("read_file", Map.of(
                        "path", path, "max_lines", maxLines), id));
    }

    private static ModelResponse write(String path, String content, String id) {
        return ModelResponse.toolCallResponse("write frozen fixture",
                new ToolCall("write_file", Map.of(
                        "path", path, "content", content, "overwrite", true), id));
    }

    private static String plan(String taskId, JsonNode task) {
        ObjectNode root = JSON.createObjectNode();
        root.putArray("acceptanceCriteria").add("frozen runtime-contract oracle passes");
        root.putArray("assumptions");
        root.putArray("risks");
        root.put("verificationStrategy", "use frozen runtime-contract evidence");
        ArrayNode steps = root.putArray("steps");
        List<String> reads = switch (taskId) {
            case "task-025" -> List.of("facts/parent-a.txt", "facts/parent-b.txt");
            case "task-027" -> List.of("facts/catalog.txt");
            case "task-028" -> List.of("facts/part-1.txt", "facts/part-2.txt", "facts/part-3.txt");
            case "task-029" -> List.of("facts/recover.txt");
            case "task-030" -> List.of("input/status-source.txt");
            default -> List.of();
        };
        if (!reads.isEmpty()) addStep(steps, "Read frozen inputs", "READ", reads, false, null);
        if (taskId.equals("task-027")) {
            ObjectNode requirement = (ObjectNode) steps.get(0)
                    .path("toolRequirements").get(0);
            requirement.put("minSuccesses", 6);
            requirement.put("maxOccurrences", 6);
        }
        if (taskId.equals("task-028")) {
            ArrayNode requirements = (ArrayNode) steps.get(0).path("toolRequirements");
            for (int index = 0; index < 3; index++) {
                ObjectNode requirement = (ObjectNode) requirements.get(index);
                requirement.put("minSuccesses", 2);
                requirement.put("maxOccurrences", 2);
            }
        }
        if (taskId.equals("task-026")) {
            addStep(steps, "Apply parent increment", "WRITE",
                    List.of("src/main/java/fixture/Counter.java"), true,
                    steps.isEmpty() ? null : 0);
        } else if (taskId.equals("task-030")) {
            addStep(steps, "Copy status", "WRITE", List.of("output/status.txt"), true, 0);
        }
        try {
            return JSON.writeValueAsString(root);
        } catch (Exception error) {
            throw new IllegalStateException("offline Plan serialization failed", error);
        }
    }

    private static void addStep(ArrayNode steps, String title, String effect,
            List<String> paths, boolean overwrite, Integer dependency) {
        int stepIndex = steps.size();
        ObjectNode step = steps.addObject();
        step.put("title", title);
        step.put("definition", title + " using only the declared fixture paths");
        ArrayNode dependencies = step.putArray("dependsOn");
        if (dependency != null) dependencies.add(dependency);
        step.put("completionRule", "ALL_REQUIRED_EVIDENCE");
        step.putArray("requiredEvidenceKinds").add("TOOL_CALL");
        step.put("effectClass", effect);
        ArrayNode requirements = step.putArray("toolRequirements");
        int order = 0;
        for (String path : paths) {
            ObjectNode requirement = requirements.addObject();
            requirement.put("requirementId", "runtime-" + stepIndex + "-" + order);
            requirement.put("requirementOrder", order++);
            requirement.put("toolName", effect.equals("WRITE") ? "write_file" : "read_file");
            requirement.put("effectClass", effect);
            ObjectNode selector = requirement.putObject("authoritySelector");
            selector.put("kind", "EXACT_PATH");
            selector.put("path", path);
            if (effect.equals("WRITE")) selector.put("overwrite", overwrite);
            requirement.put("minSuccesses", 1);
            requirement.put("maxOccurrences", 1);
            requirement.put("freshnessPolicy", "RESUME_REUSE_ONLY");
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
