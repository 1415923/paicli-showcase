package com.paicli.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.safety.ApprovalDecision;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * Writes tool approval decisions to an audit log in JSONL format.
 * Does not log secrets, tokens, or file contents.
 */
public class AuditLog {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path auditFile;

    /**
     * @param auditDir directory for audit logs (e.g. .paicli/audit)
     * @param taskId   current task identifier
     */
    public AuditLog(Path auditDir, String taskId) throws IOException {
        Files.createDirectories(auditDir);
        this.auditFile = auditDir.resolve("audit.jsonl");
        Files.writeString(auditFile, "", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /**
     * Record an approval decision.
     */
    public void record(ApprovalDecision decision) throws IOException {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("task_id", decision.taskId());
        node.put("tool", decision.toolName());
        node.put("risk", decision.riskLevel().name());
        node.put("decision", decision.decision().name());
        node.put("reason", decision.reason());
        node.put("timestamp", decision.timestamp().toString());

        String json = MAPPER.writeValueAsString(node);
        Files.writeString(auditFile, json + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    /** Get the path to the audit file. */
    public Path getAuditFile() {
        return auditFile;
    }
}
