package com.paicli.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.subagent.verdict.StructuredVerdict;
import com.paicli.subagent.verdict.VerdictEvidence;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class StructuredVerdictStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_SUMMARY_CHARS = 8192;
    private static final int MAX_EVIDENCE_JSON_CHARS = 16384;
    private static final int MAX_EVIDENCE_MESSAGE_CHARS = 4096;

    private final Connection connection;

    public StructuredVerdictStore(Connection connection) {
        this.connection = connection;
    }

    public void saveStructuredVerdict(String verdictId, String subagentRunId,
                                      StructuredVerdict verdict,
                                      String parserStrategy,
                                      String rawOutputHash) throws SQLException {
        if (verdict == null) {
            throw new IllegalArgumentException("verdict is required");
        }
        if (verdict.confidence() < 0.0d || verdict.confidence() > 1.0d) {
            throw new IllegalArgumentException("confidence must be between 0.0 and 1.0");
        }
        String evidenceJson = toEvidenceJson(verdict.evidence());
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO structured_verdicts(
                    verdict_id, subagent_run_id, verdict, severity, confidence,
                    required_action, summary, evidence_json, evidence_count,
                    parser_strategy, raw_output_hash
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, verdictId);
            statement.setString(2, subagentRunId);
            statement.setString(3, verdict.verdict().name());
            statement.setString(4, verdict.severity().name());
            statement.setDouble(5, verdict.confidence());
            statement.setString(6, verdict.requiredAction().name());
            statement.setString(7, truncate(verdict.summary(), MAX_SUMMARY_CHARS));
            statement.setString(8, truncate(evidenceJson, MAX_EVIDENCE_JSON_CHARS));
            statement.setInt(9, verdict.evidence().size());
            statement.setString(10, parserStrategy);
            statement.setString(11, rawOutputHash);
            statement.executeUpdate();
        }
    }

    public Optional<StructuredVerdictRecord> findVerdictById(String verdictId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_COLUMNS + " WHERE verdict_id = ?")) {
            statement.setString(1, verdictId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? Optional.of(toRecord(resultSet)) : Optional.empty();
            }
        }
    }

    public List<StructuredVerdictRecord> findVerdictsByRun(String subagentRunId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT_COLUMNS + """
                        WHERE subagent_run_id = ?
                        ORDER BY created_at, rowid
                        """)) {
            statement.setString(1, subagentRunId);
            List<StructuredVerdictRecord> records = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    records.add(toRecord(resultSet));
                }
            }
            return records;
        }
    }

    private String toEvidenceJson(List<VerdictEvidence> evidence)
            throws SQLException {
        if (evidence == null || evidence.isEmpty()) {
            throw new IllegalArgumentException("evidence is required");
        }
        ArrayNode array = MAPPER.createArrayNode();
        for (VerdictEvidence item : evidence) {
            if (item.sourceType() == null) {
                throw new IllegalArgumentException("evidence source_type is required");
            }
            ObjectNode node = MAPPER.createObjectNode();
            node.put("source_type", item.sourceType().name());
            if (item.file() != null) {
                node.put("file", item.file());
            }
            if (item.line() != null) {
                node.put("line", item.line());
            }
            node.put("message", truncate(item.message(), MAX_EVIDENCE_MESSAGE_CHARS));
            array.add(node);
        }
        try {
            return MAPPER.writeValueAsString(array);
        } catch (Exception e) {
            throw new SQLException("Failed to serialize verdict evidence", e);
        }
    }

    private StructuredVerdictRecord toRecord(ResultSet resultSet)
            throws SQLException {
        return new StructuredVerdictRecord(
                resultSet.getString("verdict_id"),
                resultSet.getString("subagent_run_id"),
                resultSet.getString("verdict"),
                resultSet.getString("severity"),
                resultSet.getDouble("confidence"),
                resultSet.getString("required_action"),
                resultSet.getString("summary"),
                resultSet.getString("evidence_json"),
                resultSet.getInt("evidence_count"),
                resultSet.getString("parser_strategy"),
                resultSet.getString("raw_output_hash"),
                resultSet.getString("created_at")
        );
    }

    private static String truncate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars);
    }

    private static final String SELECT_COLUMNS = """
            SELECT verdict_id, subagent_run_id, verdict, severity, confidence,
                   required_action, summary, evidence_json, evidence_count,
                   parser_strategy, raw_output_hash, created_at
            FROM structured_verdicts
            """;

    public record StructuredVerdictRecord(
            String verdictId,
            String subagentRunId,
            String verdict,
            String severity,
            double confidence,
            String requiredAction,
            String summary,
            String evidenceJson,
            int evidenceCount,
            String parserStrategy,
            String rawOutputHash,
            String createdAt
    ) {
    }
}
