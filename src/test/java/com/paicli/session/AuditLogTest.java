package com.paicli.session;

import com.paicli.safety.ApprovalDecision;
import com.paicli.safety.ApprovalDecision.Decision;
import com.paicli.safety.ApprovalDecision.RiskLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AuditLogTest {

    @TempDir
    Path tempDir;

    @Test
    void recordWritesJsonLine() throws IOException {
        var auditLog = new AuditLog(tempDir.resolve("audit"), "task-001");
        var decision = ApprovalDecision.allow("list_files", RiskLevel.LOW, "task-001", "read-only: low allowed");

        auditLog.record(decision);

        Path file = tempDir.resolve("audit").resolve("audit.jsonl");
        assertTrue(Files.exists(file));

        String content = Files.readString(file);
        assertTrue(content.contains("\"task_id\":\"task-001\""));
        assertTrue(content.contains("\"tool\":\"list_files\""));
        assertTrue(content.contains("\"risk\":\"LOW\""));
        assertTrue(content.contains("\"decision\":\"ALLOW\""));
        assertTrue(content.contains("\"reason\""));
        assertTrue(content.contains("\"timestamp\""));
    }

    @Test
    void multipleRecordsAppend() throws IOException {
        var auditLog = new AuditLog(tempDir.resolve("audit"), "task-002");

        auditLog.record(ApprovalDecision.allow("list_files", RiskLevel.LOW, "task-002", "ok"));
        auditLog.record(ApprovalDecision.deny("run_command", RiskLevel.HIGH, "task-002", "blocked"));
        auditLog.record(ApprovalDecision.needsApproval("write_file", RiskLevel.HIGH, "task-002", "ask user"));

        String[] lines = Files.readString(auditLog.getAuditFile()).trim().split("\n");
        assertEquals(3, lines.length);
        assertTrue(lines[0].contains("ALLOW"));
        assertTrue(lines[1].contains("DENY"));
        assertTrue(lines[2].contains("NEEDS_APPROVAL"));
    }

    @Test
    void createsAuditDirectoryIfMissing() throws IOException {
        Path nested = tempDir.resolve("deeply").resolve("nested").resolve("audit");
        var auditLog = new AuditLog(nested, "task-003");

        auditLog.record(ApprovalDecision.allow("grep", RiskLevel.LOW, "task-003", "ok"));

        assertTrue(Files.exists(nested.resolve("audit.jsonl")));
    }

    @Test
    void auditLogDoesNotContainSecrets() throws IOException {
        var auditLog = new AuditLog(tempDir.resolve("audit"), "task-004");
        var decision = ApprovalDecision.allow("read_file", RiskLevel.LOW, "task-004",
                "reading config file (no sensitive data logged)");

        auditLog.record(decision);

        String content = Files.readString(auditLog.getAuditFile());
        assertFalse(content.contains("password"));
        assertFalse(content.contains("token"));
        assertFalse(content.contains("secret"));
    }
}
