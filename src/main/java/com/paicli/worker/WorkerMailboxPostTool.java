package com.paicli.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.runtime.PersistenceSanitizer;
import com.paicli.state.WorkerMailboxStore;
import com.paicli.tool.Tool;
import com.paicli.tool.ToolResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Runtime-bound Worker-to-Lead mailbox adapter. Payload is data and is never executed. */
final class WorkerMailboxPostTool implements Tool {
    static final int MAX_PAYLOAD_CHARS = 4_096;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> KINDS =
            Set.of("PROGRESS", "BLOCKER", "FACT", "RESULT_SUMMARY");
    private static final List<String> FORBIDDEN =
            List.of("credential", "password", "token", "permission", "tool_grant",
                    "config_override", "worker_spawn", "shell", "command");
    private final WorkerMailboxStore store;
    private final String workerRunId;
    private final String taskItemId;
    private final PersistenceSanitizer sanitizer = new PersistenceSanitizer();

    WorkerMailboxPostTool(WorkerMailboxStore store, String workerRunId, String taskItemId) {
        this.store = store;
        this.workerRunId = workerRunId;
        this.taskItemId = taskItemId;
    }

    @Override public String name() { return "mailbox_post"; }
    @Override public String description() {
        return "Post bounded structured progress data to the runtime Lead.";
    }
    @Override public ToolDefinition toDefinition() {
        return new ToolDefinition(name(), description(), JSON.valueToTree(Map.of(
                "type", "object",
                "properties", Map.of(
                        "kind", Map.of("type", "string"),
                        "payload", Map.of("type", "string")),
                "required", List.of("kind", "payload"),
                "additionalProperties", false)));
    }

    @Override
    public ToolResult execute(ToolCall call) {
        try {
            Object rawKind = call.arguments().get("kind");
            Object rawPayload = call.arguments().get("payload");
            String kind = rawKind instanceof String value ? value.trim().toUpperCase() : "";
            String payload = rawPayload instanceof String value ? value : "";
            if (!KINDS.contains(kind)) return ToolResult.error("Mailbox message kind is forbidden");
            String lower = payload.toLowerCase();
            if (FORBIDDEN.stream().anyMatch(lower::contains)) {
                return ToolResult.error("Mailbox payload contains a forbidden control category");
            }
            String bounded = sanitizer.sanitizeAndTruncate(payload, MAX_PAYLOAD_CHARS);
            if (bounded.isBlank()) return ToolResult.error("Mailbox payload is required");
            String storedKind = switch (kind) {
                case "PROGRESS" -> "STATUS";
                case "FACT" -> "ANSWER";
                case "RESULT_SUMMARY" -> "HANDOFF";
                default -> kind;
            };
            store.append(UUID.randomUUID().toString(), "WORKER", workerRunId,
                    "LEAD", "lead", workerRunId, taskItemId, storedKind,
                    bounded, sha256(bounded));
            return ToolResult.success("Mailbox message recorded for Lead");
        } catch (Exception e) {
            return ToolResult.error("Mailbox persistence failed");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}


