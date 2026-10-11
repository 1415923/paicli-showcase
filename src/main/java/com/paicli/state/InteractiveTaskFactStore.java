package com.paicli.state;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/** Bounded durable facts produced by the interactive code-intelligence stage. */
public final class InteractiveTaskFactStore {
    private final Connection connection;

    public InteractiveTaskFactStore(Connection connection) {
        this.connection = connection;
    }

    public void recordCodeIntelligence(String taskId, String runId, String status,
            int indexedJavaFiles, int contextItemCount, int queryCount) throws SQLException {
        if (!java.util.Set.of("USED", "NOT_USED", "FAILED").contains(status)) {
            throw new IllegalArgumentException("invalid code intelligence status");
        }
        if (indexedJavaFiles < 0 || contextItemCount < 0 || queryCount < 0) {
            throw new IllegalArgumentException("code intelligence counts must be non-negative");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO interactive_task_facts(
                    task_id, run_id, code_intelligence_status,
                    indexed_java_files, context_item_count, query_count
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(task_id) DO UPDATE SET
                    run_id = excluded.run_id,
                    code_intelligence_status = excluded.code_intelligence_status,
                    indexed_java_files = excluded.indexed_java_files,
                    context_item_count = excluded.context_item_count,
                    query_count = excluded.query_count,
                    updated_at = datetime('now')
                """)) {
            statement.setString(1, taskId);
            statement.setString(2, runId);
            statement.setString(3, status);
            statement.setInt(4, indexedJavaFiles);
            statement.setInt(5, contextItemCount);
            statement.setInt(6, queryCount);
            statement.executeUpdate();
        }
    }

    public Optional<Record> findByTask(String taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT task_id, run_id, code_intelligence_status,
                       indexed_java_files, context_item_count, query_count,
                       created_at, updated_at
                FROM interactive_task_facts WHERE task_id = ?
                """)) {
            statement.setString(1, taskId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(new Record(
                        result.getString("task_id"), result.getString("run_id"),
                        result.getString("code_intelligence_status"),
                        result.getInt("indexed_java_files"),
                        result.getInt("context_item_count"), result.getInt("query_count"),
                        result.getString("created_at"), result.getString("updated_at")))
                        : Optional.empty();
            }
        }
    }

    public record Record(String taskId, String runId, String codeIntelligenceStatus,
            int indexedJavaFiles, int contextItemCount, int queryCount,
            String createdAt, String updatedAt) { }
}
