package com.paicli.state;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeSearchAuditStoreTest {

    @TempDir
    Path tempDir;

    private StateStore stateStore;

    @BeforeEach
    void setUp() throws Exception {
        stateStore = new StateStore(tempDir);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (stateStore != null) {
            stateStore.close();
        }
    }

    @Test
    void startAndCompleteAuditRoundTrip() throws Exception {
        stateStore.tasks.createTask(
                "task-001", "search symbols", "mock", null, "on-request", 10);
        stateStore.codeSearchAudits.startAudit(
                "audit-001", CodeSearchAuditStore.TYPE_SYMBOL,
                "{\"kind\":\"class\",\"name\":\"Service\"}",
                "task-001", "run-001");

        stateStore.codeSearchAudits.completeAudit(
                "audit-001", 2, true,
                CodeSearchAuditStore.CONFIDENCE_MEDIUM,
                "context-sha", 42L);

        CodeSearchAuditStore.CodeSearchAuditRecord record = stateStore
                .codeSearchAudits.findAuditById("audit-001").orElseThrow();

        assertEquals(CodeSearchAuditStore.TYPE_SYMBOL, record.queryType());
        assertEquals(CodeSearchAuditStore.STATUS_SUCCEEDED, record.status());
        assertEquals(2, record.resultCount());
        assertTrue(record.ambiguous());
        assertEquals(CodeSearchAuditStore.CONFIDENCE_MEDIUM, record.confidence());
        assertEquals("context-sha", record.selectedContextHash());
        assertEquals(42L, record.durationMillis());
        assertEquals("task-001", record.taskId());
        assertEquals("run-001", record.runId());
        assertNotNull(record.createdAt());
        assertNotNull(record.completedAt());
        assertEquals(CodeSearchAuditStore.sha256(record.queryInput()),
                record.queryInputHash());
    }

    @Test
    void failedAuditTruncatesErrorExcerptAndKeepsQueryHash() throws Exception {
        String query = "x".repeat(5000);
        String error = "parse failure ".repeat(500);

        stateStore.codeSearchAudits.startAudit(
                "audit-002", CodeSearchAuditStore.TYPE_CALL_SITE, query);
        stateStore.codeSearchAudits.failAudit("audit-002", error, 5L);

        CodeSearchAuditStore.CodeSearchAuditRecord record = stateStore
                .codeSearchAudits.findAuditById("audit-002").orElseThrow();

        assertEquals(CodeSearchAuditStore.STATUS_FAILED, record.status());
        assertEquals(CodeSearchAuditStore.sha256(query), record.queryInputHash());
        assertEquals(4096, record.queryInput().length());
        assertEquals(4096, record.errorExcerpt().length());
        assertNotEquals(CodeSearchAuditStore.sha256(record.queryInput()),
                record.queryInputHash());
        assertEquals(5L, record.durationMillis());
    }

    @Test
    void findRecentAuditsReturnsNewestFirst() throws Exception {
        stateStore.codeSearchAudits.startAudit(
                "audit-old", CodeSearchAuditStore.TYPE_SYMBOL, "old");
        stateStore.codeSearchAudits.completeAudit(
                "audit-old", 1, false,
                CodeSearchAuditStore.CONFIDENCE_HIGH, "ctx-old", 1L);
        stateStore.codeSearchAudits.startAudit(
                "audit-new", CodeSearchAuditStore.TYPE_CONTEXT, "new");
        stateStore.codeSearchAudits.completeAudit(
                "audit-new", 0, false, null, null, 2L);

        List<CodeSearchAuditStore.CodeSearchAuditRecord> recent =
                stateStore.codeSearchAudits.findRecentAudits(2);

        assertEquals(2, recent.size());
        assertEquals("audit-new", recent.getFirst().auditId());
        assertEquals("audit-old", recent.getLast().auditId());
    }

    @Test
    void completingMissingOrAlreadyCompletedAuditFailsClearly() throws Exception {
        assertThrows(SQLException.class, () -> stateStore.codeSearchAudits
                .completeAudit("missing", 0, false, null, null, 1L));

        stateStore.codeSearchAudits.startAudit(
                "audit-once", CodeSearchAuditStore.TYPE_SYMBOL, "query");
        stateStore.codeSearchAudits.completeAudit(
                "audit-once", 1, false,
                CodeSearchAuditStore.CONFIDENCE_HIGH, "ctx", 1L);

        assertThrows(SQLException.class, () -> stateStore.codeSearchAudits
                .failAudit("audit-once", "late failure", 2L));
    }

    @Test
    void stateStoreFacadeInitializesAndClosesAuditStore() throws Exception {
        assertNotNull(stateStore.codeSearchAudits);
        CodeSearchAuditStore audits = stateStore.codeSearchAudits;
        stateStore.close();
        stateStore = null;

        assertThrows(SQLException.class, () -> audits.findRecentAudits(1));
    }
}
