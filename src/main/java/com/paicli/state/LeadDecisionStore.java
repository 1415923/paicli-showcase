package com.paicli.state;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class LeadDecisionStore {

    public static final String DECISION_ACCEPT = "ACCEPT";
    public static final String DECISION_REJECT = "REJECT";
    public static final String DECISION_REQUEST_RECHECK = "REQUEST_RECHECK";

    public static final String DECIDED_BY_RUNTIME = "RUNTIME";
    public static final String DECIDED_BY_USER = "USER";
    public static final String DECIDED_BY_POLICY = "POLICY";

    private static final int MAX_REASON_CHARS = 4096;

    private final Connection connection;

    public LeadDecisionStore(Connection connection) {
        this.connection = connection;
    }

    public void saveLeadDecision(String decisionId, String subagentRunId,
                                 String verdictId, String decision,
                                 String reason, String recheckOccurrenceKey,
                                 String decidedBy) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO lead_decisions(
                    decision_id, subagent_run_id, verdict_id, decision, reason,
                    recheck_occurrence_key, decided_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, decisionId);
            statement.setString(2, subagentRunId);
            statement.setString(3, verdictId);
            statement.setString(4, decision);
            statement.setString(5, truncate(reason, MAX_REASON_CHARS));
            statement.setString(6, recheckOccurrenceKey);
            statement.setString(7, decidedBy);
            statement.executeUpdate();
        }
    }

    public Optional<LeadDecisionRecord> findDecisionById(String decisionId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_COLUMNS + " WHERE decision_id = ?")) {
            statement.setString(1, decisionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(toRecord(resultSet)) : Optional.empty();
            }
        }
    }

    public Optional<LeadDecisionRecord> findDecisionByVerdictId(String verdictId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_COLUMNS + " WHERE verdict_id = ?")) {
            statement.setString(1, verdictId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(toRecord(resultSet)) : Optional.empty();
            }
        }
    }

    public List<LeadDecisionRecord> findDecisionsByRun(String subagentRunId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_COLUMNS + """
                        WHERE subagent_run_id = ?
                        ORDER BY created_at, rowid
                        """)) {
            statement.setString(1, subagentRunId);
            List<LeadDecisionRecord> records = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    records.add(toRecord(resultSet));
                }
            }
            return records;
        }
    }

    private LeadDecisionRecord toRecord(ResultSet resultSet) throws SQLException {
        return new LeadDecisionRecord(
                resultSet.getString("decision_id"),
                resultSet.getString("subagent_run_id"),
                resultSet.getString("verdict_id"),
                resultSet.getString("decision"),
                resultSet.getString("reason"),
                resultSet.getString("recheck_occurrence_key"),
                resultSet.getString("decided_by"),
                resultSet.getString("created_at")
        );
    }

    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars);
    }

    private static final String SELECT_COLUMNS = """
            SELECT decision_id, subagent_run_id, verdict_id, decision, reason,
                   recheck_occurrence_key, decided_by, created_at
            FROM lead_decisions
            """;

    public record LeadDecisionRecord(
            String decisionId,
            String subagentRunId,
            String verdictId,
            String decision,
            String reason,
            String recheckOccurrenceKey,
            String decidedBy,
            String createdAt
    ) {
    }
}
