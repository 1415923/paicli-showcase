package com.paicli.state;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Durable accounting for bounded same-Task execution segments; it does not schedule work. */
public final class TaskContinuityStore {
    public static final String STARTED = "STARTED";
    public static final String COMPLETED = "COMPLETED";
    public static final String RECOVERABLE_FAILED = "RECOVERABLE_FAILED";
    public static final String WAITING_FOR_USER = "WAITING_FOR_USER";
    public static final String TERMINAL_FAILED = "TERMINAL_FAILED";

    private final Connection connection;

    public TaskContinuityStore(Connection connection) {
        this.connection = Objects.requireNonNull(connection);
    }

    public int nextOrdinal(String taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COALESCE(MAX(segment_ordinal),0)+1 FROM task_continuity_segments WHERE task_id=?")) {
            statement.setString(1, taskId);
            try (ResultSet row = statement.executeQuery()) { return row.next() ? row.getInt(1) : 1; }
        }
    }

    public int count(String taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM task_continuity_segments WHERE task_id=?")) {
            statement.setString(1, taskId);
            try (ResultSet row = statement.executeQuery()) { return row.next() ? row.getInt(1) : 0; }
        }
    }

    public Optional<Segment> find(String taskId, int segmentOrdinal) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT run_id,planning_attempt_ordinal,trigger,state,policy_hash,start_iteration,
                    end_iteration,provider_turns,tool_calls,input_tokens,output_tokens,
                    active_elapsed_ms,recovery_directive,terminal_reason
                FROM task_continuity_segments WHERE task_id=? AND segment_ordinal=?
                """)) {
            bind(statement, taskId, segmentOrdinal);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                return Optional.of(read(taskId, segmentOrdinal, row));
            }
        }
    }

    public List<Segment> findByTask(String taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT segment_ordinal,run_id,planning_attempt_ordinal,trigger,state,policy_hash,
                    start_iteration,end_iteration,provider_turns,tool_calls,input_tokens,
                    output_tokens,active_elapsed_ms,recovery_directive,terminal_reason
                FROM task_continuity_segments WHERE task_id=? ORDER BY segment_ordinal
                """)) {
            statement.setString(1, taskId);
            List<Segment> result = new ArrayList<>();
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) result.add(read(taskId, row.getInt(1), row, 2));
            }
            return List.copyOf(result);
        }
    }

    private static Segment read(String taskId, int ordinal, ResultSet row) throws SQLException {
        return read(taskId, ordinal, row, 1);
    }

    private static Segment read(String taskId, int ordinal, ResultSet row, int offset)
            throws SQLException {
        return new Segment(taskId, row.getString(offset), ordinal, row.getInt(offset + 1),
                row.getString(offset + 2), row.getString(offset + 3), row.getString(offset + 4),
                row.getInt(offset + 5), row.getInt(offset + 6), row.getInt(offset + 7),
                row.getInt(offset + 8), row.getLong(offset + 9), row.getLong(offset + 10),
                row.getLong(offset + 11), row.getString(offset + 12), row.getString(offset + 13));
    }

    private static void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
    }

    public record Segment(String taskId, String runId, int segmentOrdinal,
            int planningAttemptOrdinal, String trigger, String state, String policyHash,
            int startIteration, int endIteration, int providerTurns, int toolCalls,
            long inputTokens, long outputTokens, long activeElapsedMillis,
            String recoveryDirective, String terminalReason) { }
}
