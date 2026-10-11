package com.paicli.state;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.model.AgentMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public class CheckpointStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<AgentMessage>> MESSAGE_LIST_TYPE = new TypeReference<>() { };
    private static final Pattern SAFE_TASK_ID = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern SENSITIVE_TEXT = Pattern.compile(
            "(?i)(?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret|authorization)"
                    + "\\s*[:=]\\s*(?!<redacted>|\\[redacted]|\\*{3,})\\S+");
    private static final Pattern RAW_HTTP_MESSAGE = Pattern.compile(
            "(?is)^(?:HTTP/\\d(?:\\.\\d)?\\s+\\d{3}|"
                    + "[!#$%&'*+.^_`|~0-9A-Za-z-]+\\s+\\S+\\s+HTTP/\\d(?:\\.\\d)?)"
                    + ".*");
    private static final Set<String> SENSITIVE_JSON_FIELDS = Set.of(
            "apikey", "token", "accesstoken", "refreshtoken", "password", "secret", "authorization");
    private static final int MAX_PERSISTED_FIELD_LENGTH = 64 * 1024;
    private static final int MARKDOWN_CONTENT_LIMIT = 500;

    private final Connection connection;
    private final Path checkpointsDir;

    public CheckpointStore(Connection connection) {
        this(connection, Path.of(".paicli", "checkpoints"));
    }

    public CheckpointStore(Connection connection, Path checkpointsDir) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.checkpointsDir = Objects.requireNonNull(checkpointsDir, "checkpointsDir").toAbsolutePath().normalize();
    }

    /**
     * Saves the latest checkpoint. Callers must provide messages that are already redacted and bounded in size.
     * SQLite is the recovery source; the Markdown file is a human-readable mirror only.
     */
    public void save(String taskId, int iteration, String status, List<AgentMessage> messages)
            throws SQLException, IOException {
        Path markdownFile = checkpointFile(taskId);
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(messages, "messages");
        validatePersistedField("status", status);
        messages.forEach(message -> Objects.requireNonNull(message, "messages must not contain null"));
        for (AgentMessage message : messages) {
            validatePersistedField("message content", message.content());
            validatePersistedField("tool call ID", message.toolCallId());
            validatePersistedField("tool call JSON", message.toolCallJson());
        }

        String messagesJson = MAPPER.writeValueAsString(messages);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO checkpoints(task_id, iteration, status, messages_json)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(task_id) DO UPDATE SET
                    iteration = excluded.iteration,
                    status = excluded.status,
                    messages_json = excluded.messages_json,
                    created_at = datetime('now')
                """)) {
            statement.setString(1, taskId);
            statement.setInt(2, iteration);
            statement.setString(3, status);
            statement.setString(4, messagesJson);
            statement.executeUpdate();
        }

        writeMarkdown(markdownFile, taskId, iteration, status, messages);
    }

    public Optional<CheckpointRecord> loadLatest(String taskId) throws SQLException, IOException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT task_id, iteration, status, messages_json, created_at
                FROM checkpoints
                WHERE task_id = ?
                """)) {
            statement.setString(1, taskId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Optional.empty();
                }
                List<AgentMessage> messages = MAPPER.readValue(
                        resultSet.getString("messages_json"), MESSAGE_LIST_TYPE);
                return Optional.of(new CheckpointRecord(
                        resultSet.getString("task_id"),
                        resultSet.getInt("iteration"),
                        resultSet.getString("status"),
                        List.copyOf(messages),
                        resultSet.getString("created_at")
                ));
            }
        }
    }

    private Path checkpointFile(String taskId) {
        if (taskId == null || !SAFE_TASK_ID.matcher(taskId).matches()
                || ".".equals(taskId) || "..".equals(taskId)) {
            throw new IllegalArgumentException("Invalid task ID for checkpoint file");
        }
        Path target = checkpointsDir.resolve(taskId + ".md").normalize();
        if (!target.startsWith(checkpointsDir)) {
            throw new IllegalArgumentException("Checkpoint file must stay inside the checkpoint directory");
        }
        return target;
    }

    private void writeMarkdown(Path target, String taskId, int iteration, String status,
                               List<AgentMessage> messages) throws IOException {
        Files.createDirectories(checkpointsDir);
        String markdown = renderMarkdown(taskId, iteration, status, messages);
        Path temporary = Files.createTempFile(checkpointsDir, taskId + "-", ".tmp");
        try {
            Files.writeString(temporary, markdown, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private String renderMarkdown(String taskId, int iteration, String status, List<AgentMessage> messages) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# PaiCLI Checkpoint\n\n");
        markdown.append("- Task ID: `").append(taskId).append("`\n");
        markdown.append("- Iteration: ").append(iteration).append("\n");
        markdown.append("- Status: `").append(markdownInline(status)).append("`\n");
        markdown.append("- Messages: ").append(messages.size()).append("\n\n");
        markdown.append("## Message Summary\n\n");
        for (int index = 0; index < messages.size(); index++) {
            AgentMessage message = messages.get(index);
            markdown.append(index + 1).append(". **")
                    .append(message.role()).append(" / ").append(message.type()).append(":** ")
                    .append(markdownInline(summarize(message.content())))
                    .append("\n");
        }
        return markdown.toString();
    }

    private String summarize(String content) {
        if (content == null || content.isBlank()) {
            return "(empty)";
        }
        String singleLine = content.replace('\r', ' ').replace('\n', ' ').trim();
        if (singleLine.length() <= MARKDOWN_CONTENT_LIMIT) {
            return singleLine;
        }
        return singleLine.substring(0, MARKDOWN_CONTENT_LIMIT) + "... (truncated)";
    }

    private String markdownInline(String value) {
        return value.replace("\\", "\\\\")
                .replace("`", "\\`")
                .replace("*", "\\*")
                .replace("_", "\\_");
    }

    private void validatePersistedField(String fieldName, String value) {
        if (value == null) {
            return;
        }
        if (value.length() > MAX_PERSISTED_FIELD_LENGTH) {
            throw new IllegalArgumentException(fieldName + " exceeds checkpoint size limit");
        }
        if (SENSITIVE_TEXT.matcher(value).find() || containsSensitiveJsonValue(value)) {
            throw new IllegalArgumentException(fieldName + " contains sensitive data");
        }
        if (RAW_HTTP_MESSAGE.matcher(value).matches()) {
            throw new IllegalArgumentException(fieldName + " contains a complete HTTP message");
        }
    }

    private boolean containsSensitiveJsonValue(String value) {
        try {
            return containsSensitiveJsonValue(MAPPER.readTree(value));
        } catch (IOException ignored) {
            return false;
        }
    }

    private boolean containsSensitiveJsonValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                String normalizedName = field.getKey().replace("_", "").replace("-", "")
                        .toLowerCase(Locale.ROOT);
                if (SENSITIVE_JSON_FIELDS.contains(normalizedName) && !isRedacted(field.getValue())) {
                    return true;
                }
                if (containsSensitiveJsonValue(field.getValue())) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                if (containsSensitiveJsonValue(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isRedacted(JsonNode value) {
        if (value == null || value.isNull()) {
            return true;
        }
        if (!value.isTextual()) {
            return false;
        }
        String text = value.asText().trim();
        return text.equalsIgnoreCase("<redacted>")
                || text.equalsIgnoreCase("[redacted]")
                || text.matches("\\*{3,}");
    }

    public record CheckpointRecord(String taskId, int iteration, String status,
                                   List<AgentMessage> messages, String createdAt) {
        public CheckpointRecord {
            messages = List.copyOf(messages);
        }
    }
}
