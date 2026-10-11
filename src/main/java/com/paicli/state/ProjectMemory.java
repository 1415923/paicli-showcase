package com.paicli.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public class ProjectMemory {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SENSITIVE_TEXT = Pattern.compile(
            "(?i)(?:api[_-]?key|access[_-]?token|refresh[_-]?token|password|secret|authorization)"
                    + "\\s*[:=]\\s*(?!<redacted>|\\[redacted]|\\*{3,})\\S+");
    private static final Pattern RAW_HTTP_MESSAGE = Pattern.compile(
            "(?is)^(?:HTTP/\\d(?:\\.\\d)?\\s+\\d{3}|"
                    + "[!#$%&'*+.^_`|~0-9A-Za-z-]+\\s+\\S+\\s+HTTP/\\d(?:\\.\\d)?).*"
    );
    private static final Set<String> SENSITIVE_JSON_FIELDS = Set.of(
            "apikey", "token", "accesstoken", "refreshtoken", "password", "secret", "authorization");
    private static final int MAX_FIELD_LENGTH = 64 * 1024;

    private final Connection connection;
    private final Path memoryDir;
    private final Path memoryFile;

    public ProjectMemory(Connection connection) {
        this(connection, Path.of(".paicli", "memory"));
    }

    public ProjectMemory(Connection connection, Path memoryDir) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.memoryDir = Objects.requireNonNull(memoryDir, "memoryDir").toAbsolutePath().normalize();
        this.memoryFile = this.memoryDir.resolve("MEMORY.md");
    }

    public void append(String category, String content) throws SQLException, IOException {
        append(null, category, content);
    }

    /**
     * Appends a memory note and refreshes the human-readable Markdown mirror.
     * SQLite remains authoritative if writing the mirror fails.
     */
    public void append(String taskId, String category, String content) throws SQLException, IOException {
        requireNonBlank(category, "category");
        requireNonBlank(content, "content");
        validatePersistedField("task ID", taskId);
        validatePersistedField("category", category);
        validatePersistedField("content", content);

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO memory_notes(task_id, category, content)
                VALUES (?, ?, ?)
                """)) {
            statement.setString(1, taskId);
            statement.setString(2, category);
            statement.setString(3, content);
            statement.executeUpdate();
        }
        syncToMarkdown();
    }

    public List<MemoryNote> findRecent(int limit) throws SQLException {
        if (limit <= 0) {
            return List.of();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, task_id, category, content, created_at
                FROM memory_notes
                ORDER BY id DESC
                LIMIT ?
                """)) {
            statement.setInt(1, limit);
            List<MemoryNote> notes = findMany(statement);
            Collections.reverse(notes);
            return notes;
        }
    }

    public List<MemoryNote> findRecent(String taskId, int limit) throws SQLException {
        if (limit <= 0) {
            return List.of();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, task_id, category, content, created_at
                FROM memory_notes
                WHERE task_id = ?
                ORDER BY id DESC
                LIMIT ?
                """)) {
            statement.setString(1, taskId);
            statement.setInt(2, limit);
            List<MemoryNote> notes = findMany(statement);
            Collections.reverse(notes);
            return notes;
        }
    }

    public String asContext(int maxChars) throws SQLException {
        if (maxChars <= 0) {
            return "";
        }
        List<MemoryNote> notes = findRecent(Math.min(maxChars, 10_000));
        Deque<String> selected = new ArrayDeque<>();
        int remaining = maxChars;
        for (int index = notes.size() - 1; index >= 0 && remaining > 0; index--) {
            String line = contextLine(notes.get(index));
            int separatorLength = selected.isEmpty() ? 0 : 1;
            if (line.length() + separatorLength <= remaining) {
                selected.addFirst(line);
                remaining -= line.length() + separatorLength;
            } else if (selected.isEmpty()) {
                selected.addFirst(line.substring(0, remaining));
                remaining = 0;
            } else {
                break;
            }
        }
        return String.join("\n", selected);
    }

    public void syncToMarkdown() throws SQLException, IOException {
        List<MemoryNote> notes;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, task_id, category, content, created_at
                FROM memory_notes
                ORDER BY id
                """)) {
            notes = findMany(statement);
        }

        Files.createDirectories(memoryDir);
        Path temporary = Files.createTempFile(memoryDir, "MEMORY-", ".tmp");
        try {
            Files.writeString(temporary, renderMarkdown(notes), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, memoryFile,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, memoryFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public Path getMemoryFile() {
        return memoryFile;
    }

    private List<MemoryNote> findMany(PreparedStatement statement) throws SQLException {
        List<MemoryNote> notes = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                notes.add(new MemoryNote(
                        resultSet.getLong("id"),
                        resultSet.getString("task_id"),
                        resultSet.getString("category"),
                        resultSet.getString("content"),
                        resultSet.getString("created_at")
                ));
            }
        }
        return notes;
    }

    private String renderMarkdown(List<MemoryNote> notes) {
        StringBuilder markdown = new StringBuilder("# PaiCLI Project Memory\n\n");
        if (notes.isEmpty()) {
            return markdown.append("(no memory notes)\n").toString();
        }
        for (MemoryNote note : notes) {
            markdown.append("- ").append(markdownInline(note.createdAt()))
                    .append(" **").append(markdownInline(note.category())).append("**");
            if (note.taskId() != null) {
                markdown.append(" (task `").append(markdownInline(note.taskId())).append("`)");
            }
            markdown.append(": ").append(markdownInline(singleLine(note.content()))).append("\n");
        }
        return markdown.toString();
    }

    private String contextLine(MemoryNote note) {
        String task = note.taskId() == null ? "" : " task=" + note.taskId();
        return "[" + note.category() + task + "] " + singleLine(note.content());
    }

    private String singleLine(String value) {
        return value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private String markdownInline(String value) {
        return value.replace("\\", "\\\\")
                .replace("`", "\\`")
                .replace("*", "\\*")
                .replace("_", "\\_");
    }

    private void requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    private void validatePersistedField(String fieldName, String value) {
        if (value == null) {
            return;
        }
        if (value.length() > MAX_FIELD_LENGTH) {
            throw new IllegalArgumentException(fieldName + " exceeds memory size limit");
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

    public record MemoryNote(long id, String taskId, String category, String content, String createdAt) {
    }
}
