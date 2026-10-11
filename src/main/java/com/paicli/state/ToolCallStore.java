package com.paicli.state;

import com.paicli.tool.ToolResult;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ToolCallStore {

    private final Connection connection;

    public ToolCallStore(Connection connection) {
        this.connection = connection;
    }

    /**
     * Records tool arguments and outputs as provided by upstream callers.
     * Callers must pass redacted/truncated JSON and output; this store does not persist secrets safely by itself.
     */
    public void record(String taskId, int iteration, String toolName, String argumentsJson, ToolResult result)
            throws SQLException {
        record(taskId, iteration, null, toolName, argumentsJson, result);
    }

    /**
     * Records tool arguments and outputs as provided by upstream callers.
     * Callers must pass redacted/truncated JSON and output; this store does not persist secrets safely by itself.
     */
    public void record(String taskId, int iteration, String toolCallId, String toolName,
                       String argumentsJson, ToolResult result)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO tool_calls(task_id, iteration, tool_call_id, tool_name, arguments,
                                       result_success, result_output, result_error)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, taskId);
            statement.setInt(2, iteration);
            statement.setString(3, toolCallId);
            statement.setString(4, toolName);
            statement.setString(5, argumentsJson);
            statement.setInt(6, result.success() ? 1 : 0);
            statement.setString(7, result.output());
            statement.setString(8, result.error());
            statement.executeUpdate();
        }
    }

    public List<ToolCallRecord> findByTask(String taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, task_id, iteration, tool_call_id, tool_name, arguments,
                       result_success, result_output, result_error, created_at
                FROM tool_calls
                WHERE task_id = ?
                ORDER BY id
                """)) {
            statement.setString(1, taskId);
            return findMany(statement);
        }
    }

    public List<ToolCallRecord> findByToolName(String toolName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, task_id, iteration, tool_call_id, tool_name, arguments,
                       result_success, result_output, result_error, created_at
                FROM tool_calls
                WHERE tool_name = ?
                ORDER BY id
                """)) {
            statement.setString(1, toolName);
            return findMany(statement);
        }
    }

    public List<ToolCallRecord> findRecentByTask(String taskId, int limit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, task_id, iteration, tool_call_id, tool_name, arguments,
                       result_success, result_output, result_error, created_at
                FROM tool_calls
                WHERE task_id = ?
                ORDER BY id DESC
                LIMIT ?
                """)) {
            statement.setString(1, taskId);
            statement.setInt(2, limit);
            List<ToolCallRecord> records = findMany(statement);
            Collections.reverse(records);
            return records;
        }
    }

    public int countSuccessfulByTaskAndToolAfterIteration(
            String taskId, String toolName, int afterIteration) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) AS matching_count
                FROM tool_calls
                WHERE task_id = ? AND tool_name = ? AND result_success = 1
                  AND iteration > ?
                """)) {
            statement.setString(1, taskId);
            statement.setString(2, toolName);
            statement.setInt(3, afterIteration);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt("matching_count") : 0;
            }
        }
    }
    private List<ToolCallRecord> findMany(PreparedStatement statement) throws SQLException {
        List<ToolCallRecord> records = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                records.add(new ToolCallRecord(
                        resultSet.getLong("id"),
                        resultSet.getString("task_id"),
                        resultSet.getInt("iteration"),
                        resultSet.getString("tool_call_id"),
                        resultSet.getString("tool_name"),
                        resultSet.getString("arguments"),
                        resultSet.getInt("result_success") == 1,
                        resultSet.getString("result_output"),
                        resultSet.getString("result_error"),
                        resultSet.getString("created_at")
                ));
            }
        }
        return records;
    }

    public record ToolCallRecord(long id, String taskId, int iteration, String toolCallId,
                                 String toolName, String argumentsJson, boolean success,
                                 String output, String error, String createdAt) {
    }
}
