package com.paicli.state;

import com.paicli.model.AgentMessage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class EventStore {

    private final Connection connection;

    public EventStore(Connection connection) {
        this.connection = connection;
    }

    public void append(String taskId, int iteration, AgentMessage message) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO events(task_id, iteration, role, type, content, tool_call_id, tool_call_json)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, taskId);
            statement.setInt(2, iteration);
            statement.setString(3, message.role().name());
            statement.setString(4, message.type().name());
            statement.setString(5, message.content());
            statement.setString(6, message.toolCallId());
            statement.setString(7, message.toolCallJson());
            statement.executeUpdate();
        }
    }

    public List<AgentMessage> findMessages(String taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT role, type, content, tool_call_id, tool_call_json
                FROM events
                WHERE task_id = ?
                ORDER BY id
                """)) {
            statement.setString(1, taskId);
            return findMessages(statement);
        }
    }

    public List<AgentMessage> findRecentMessages(String taskId, int limit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT role, type, content, tool_call_id, tool_call_json
                FROM events
                WHERE task_id = ?
                ORDER BY id DESC
                LIMIT ?
                """)) {
            statement.setString(1, taskId);
            statement.setInt(2, limit);
            List<AgentMessage> messages = findMessages(statement);
            Collections.reverse(messages);
            return messages;
        }
    }

    public Optional<AgentMessage> findFinalAtIteration(String taskId, int iteration)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT role, type, content, tool_call_id, tool_call_json
                FROM events
                WHERE task_id = ? AND iteration = ? AND role = 'ASSISTANT'
                    AND type = 'FINAL'
                ORDER BY id
                """)) {
            statement.setString(1, taskId);
            statement.setInt(2, iteration);
            List<AgentMessage> messages = findMessages(statement);
            if (messages.size() > 1) {
                throw new SQLException("FINAL_EVENT_INTEGRITY_FAILURE");
            }
            return messages.stream().findFirst();
        }
    }

    public int countIterations(String taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(DISTINCT iteration)
                FROM events
                WHERE task_id = ?
                """)) {
            statement.setString(1, taskId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        }
    }

    private List<AgentMessage> findMessages(PreparedStatement statement) throws SQLException {
        List<AgentMessage> messages = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                messages.add(new AgentMessage(
                        AgentMessage.Role.valueOf(resultSet.getString("role")),
                        resultSet.getString("content"),
                        AgentMessage.MessageType.valueOf(resultSet.getString("type")),
                        resultSet.getString("tool_call_id"),
                        resultSet.getString("tool_call_json")
                ));
            }
        }
        return messages;
    }
}
