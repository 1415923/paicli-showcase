package com.paicli.state;

import com.paicli.safety.ApprovalDecision;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ApprovalStore {

    private final Connection connection;

    public ApprovalStore(Connection connection) {
        this.connection = connection;
    }

    public void record(String taskId, String toolName, String riskLevel, String decision, String reason)
            throws SQLException {
        record(taskId, null, null, toolName, riskLevel, decision, reason);
    }

    public void record(String taskId, Integer iteration, String toolCallId, String toolName,
                       String riskLevel, String decision, String reason) throws SQLException {
        record(taskId,iteration,toolCallId,toolName,riskLevel,decision,reason,null);
    }

    public void record(String taskId, Integer iteration, String toolCallId, String toolName,
                       String riskLevel, String decision, String reason,String intentHash)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO approval_decisions(task_id, iteration, tool_call_id, tool_name,
                    risk_level, decision, reason,intent_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, taskId);
            if (iteration == null) {
                statement.setNull(2, java.sql.Types.INTEGER);
            } else {
                statement.setInt(2, iteration);
            }
            statement.setString(3, toolCallId);
            statement.setString(4, toolName);
            statement.setString(5, riskLevel);
            statement.setString(6, decision);
            statement.setString(7, reason);
            statement.setString(8, intentHash);
            statement.executeUpdate();
        }
    }

    public void record(ApprovalDecision approvalDecision) throws SQLException {
        record(
                approvalDecision.taskId(),
                approvalDecision.toolName(),
                approvalDecision.riskLevel().name(),
                approvalDecision.decision().name(),
                approvalDecision.reason()
        );
    }

    public List<ApprovalRecord> findByTask(String taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, task_id, iteration, tool_call_id, tool_name, risk_level, decision, reason, created_at
                FROM approval_decisions
                WHERE task_id = ?
                ORDER BY id
                """)) {
            statement.setString(1, taskId);
            return findMany(statement);
        }
    }

    public List<ApprovalRecord> findByDecision(String decision) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, task_id, iteration, tool_call_id, tool_name, risk_level, decision, reason, created_at
                FROM approval_decisions
                WHERE decision = ?
                ORDER BY id
                """)) {
            statement.setString(1, decision);
            return findMany(statement);
        }
    }

    public List<ApprovalRecord> findRecentByTask(String taskId, int limit) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id, task_id, iteration, tool_call_id, tool_name, risk_level, decision, reason, created_at
                FROM approval_decisions
                WHERE task_id = ?
                ORDER BY id DESC
                LIMIT ?
                """)) {
            statement.setString(1, taskId);
            statement.setInt(2, limit);
            List<ApprovalRecord> records = findMany(statement);
            Collections.reverse(records);
            return records;
        }
    }

    private List<ApprovalRecord> findMany(PreparedStatement statement) throws SQLException {
        List<ApprovalRecord> records = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                records.add(new ApprovalRecord(
                        resultSet.getLong("id"),
                        resultSet.getString("task_id"),
                        (Integer) resultSet.getObject("iteration"),
                        resultSet.getString("tool_call_id"),
                        resultSet.getString("tool_name"),
                        resultSet.getString("risk_level"),
                        resultSet.getString("decision"),
                        resultSet.getString("reason"),
                        resultSet.getString("created_at")
                ));
            }
        }
        return records;
    }

    public record ApprovalRecord(long id, String taskId, Integer iteration, String toolCallId,
                                 String toolName, String riskLevel, String decision,
                                 String reason, String createdAt) {
    }
}
