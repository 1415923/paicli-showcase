package com.paicli.state;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class WorkerMailboxStore {
    private static final Set<String> SENDERS = Set.of("LEAD", "WORKER", "RUNTIME", "USER");
    private static final Set<String> RECIPIENTS = Set.of("LEAD", "WORKER");
    private static final Set<String> KINDS = Set.of("STATUS", "QUESTION", "ANSWER", "HANDOFF", "BLOCKER");
    private final Connection connection;

    public WorkerMailboxStore(Connection connection) { this.connection = connection; }

    public void append(String messageId, String senderType, String senderId,
                       String recipientType, String recipientId, String workerRunId,
                       String taskItemId, String messageKind, String payload,
                       String payloadHash) throws SQLException {
        WorkerStoreSupport.oneOf(senderType, "senderType", SENDERS);
        WorkerStoreSupport.oneOf(recipientType, "recipientType", RECIPIENTS);
        WorkerStoreSupport.oneOf(messageKind, "messageKind", KINDS);
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO worker_mailbox_messages(
                    message_id, sender_type, sender_id, recipient_type, recipient_id,
                    worker_run_id, task_item_id, message_kind, bounded_payload, payload_hash
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, WorkerStoreSupport.id(messageId, "messageId"));
            statement.setString(2, senderType);
            statement.setString(3, WorkerStoreSupport.id(senderId, "senderId"));
            statement.setString(4, recipientType);
            statement.setString(5, WorkerStoreSupport.id(recipientId, "recipientId"));
            statement.setString(6, workerRunId);
            statement.setString(7, taskItemId);
            statement.setString(8, messageKind);
            statement.setString(9, WorkerStoreSupport.required(payload, "payload", WorkerStoreSupport.MAX_EXCERPT_CHARS));
            statement.setString(10, WorkerStoreSupport.hash(payloadHash, "payloadHash"));
            statement.executeUpdate();
        }
    }

    public void acknowledge(String messageId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE worker_mailbox_messages SET acknowledged_at = datetime('now')
                WHERE message_id = ? AND acknowledged_at IS NULL
                """)) {
            statement.setString(1, messageId);
            WorkerStoreSupport.requireUpdated(statement,
                    "Mailbox message not found or already acknowledged: " + messageId);
        }
    }

    public List<MailboxMessageRecord> findByRecipient(String type, String id) throws SQLException {
        return find("recipient_type = ? AND recipient_id = ?", type, id);
    }

    public List<MailboxMessageRecord> findByWorkerRun(String id) throws SQLException {
        return find("worker_run_id = ?", id);
    }

    public List<MailboxMessageRecord> findByTaskItem(String id) throws SQLException {
        return find("task_item_id = ?", id);
    }

    private List<MailboxMessageRecord> find(String where, String... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                SELECT + " WHERE " + where + " ORDER BY created_at, rowid")) {
            for (int i = 0; i < values.length; i++) statement.setString(i + 1, values[i]);
            List<MailboxMessageRecord> records = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) records.add(map(rs));
            }
            return records;
        }
    }

    private MailboxMessageRecord map(ResultSet rs) throws SQLException {
        return new MailboxMessageRecord(rs.getString("message_id"), rs.getString("sender_type"),
                rs.getString("sender_id"), rs.getString("recipient_type"), rs.getString("recipient_id"),
                rs.getString("worker_run_id"), rs.getString("task_item_id"), rs.getString("message_kind"),
                rs.getString("bounded_payload"), rs.getString("payload_hash"), rs.getString("created_at"),
                rs.getString("acknowledged_at"));
    }

    private static final String SELECT = """
            SELECT message_id, sender_type, sender_id, recipient_type, recipient_id,
                   worker_run_id, task_item_id, message_kind, bounded_payload,
                   payload_hash, created_at, acknowledged_at FROM worker_mailbox_messages
            """;

    public record MailboxMessageRecord(String messageId, String senderType, String senderId,
            String recipientType, String recipientId, String workerRunId, String taskItemId,
            String messageKind, String boundedPayload, String payloadHash, String createdAt,
            String acknowledgedAt) { }
}
