package com.paicli.state;

import com.paicli.subagent.verdict.EvidenceSourceType;
import com.paicli.subagent.verdict.RequiredAction;
import com.paicli.subagent.verdict.Severity;
import com.paicli.subagent.verdict.StructuredVerdict;
import com.paicli.subagent.verdict.Verdict;
import com.paicli.subagent.verdict.VerdictEvidence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LeadDecisionStoreTest {

    @TempDir
    Path tempDir;

    private StateStore stateStore;

    @BeforeEach
    void setUp() throws Exception {
        stateStore = new StateStore(tempDir);
        stateStore.tasks.createTask("task-001", "review code", "mock", null, "deny", 10);
        stateStore.codingRuns.create("run-001", "task-001", CodingRunStore.KIND_INITIAL, null, 1, 0);
        stateStore.subagentRuns.createSubagentRun(
                "sub-001", "task-001", "run-001", "reviewer",
                SubagentRunStore.KIND_REVIEWER, "AFTER_EXECUTE",
                "occ-1", 1, "mock", true, null, null);
        stateStore.structuredVerdicts.saveStructuredVerdict(
                "verdict-001", "sub-001",
                new StructuredVerdict(
                        Verdict.FAIL, Severity.HIGH, 0.8,
                        List.of(new VerdictEvidence(
                                EvidenceSourceType.REVIEWER_MODEL,
                                "App.java", 3, "needs fix")),
                        RequiredAction.FIX, "fix required", true),
                "json", "raw-sha");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (stateStore != null) {
            stateStore.close();
        }
    }

    @Test
    void acceptRejectAndRequestRecheckRoundTrip() throws Exception {
        saveVerdict("verdict-002");
        saveVerdict("verdict-003");
        stateStore.leadDecisions.saveLeadDecision(
                "decision-accept", "sub-001", "verdict-001",
                LeadDecisionStore.DECISION_ACCEPT, "looks actionable", null,
                LeadDecisionStore.DECIDED_BY_RUNTIME);
        stateStore.leadDecisions.saveLeadDecision(
                "decision-reject", "sub-001", "verdict-002",
                LeadDecisionStore.DECISION_REJECT, "weak evidence", null,
                LeadDecisionStore.DECIDED_BY_USER);
        stateStore.leadDecisions.saveLeadDecision(
                "decision-recheck", "sub-001", "verdict-003",
                LeadDecisionStore.DECISION_REQUEST_RECHECK, "ask another reviewer",
                "occ-1-recheck", LeadDecisionStore.DECIDED_BY_POLICY);

        List<LeadDecisionStore.LeadDecisionRecord> records =
                stateStore.leadDecisions.findDecisionsByRun("sub-001");

        assertEquals(3, records.size());
        assertEquals(LeadDecisionStore.DECISION_ACCEPT, records.getFirst().decision());
        assertEquals(LeadDecisionStore.DECISION_REJECT, records.get(1).decision());
        assertEquals(LeadDecisionStore.DECISION_REQUEST_RECHECK, records.getLast().decision());
        assertEquals("occ-1-recheck", records.getLast().recheckOccurrenceKey());
    }

    @Test
    void decisionAssociatesVerdictAndRun() throws Exception {
        stateStore.leadDecisions.saveLeadDecision(
                "decision-001", "sub-001", "verdict-001",
                LeadDecisionStore.DECISION_ACCEPT, "accepted", null,
                LeadDecisionStore.DECIDED_BY_RUNTIME);

        LeadDecisionStore.LeadDecisionRecord record = stateStore.leadDecisions
                .findDecisionById("decision-001").orElseThrow();

        assertEquals("sub-001", record.subagentRunId());
        assertEquals("verdict-001", record.verdictId());
        assertEquals(LeadDecisionStore.DECIDED_BY_RUNTIME, record.decidedBy());
        assertNotNull(record.createdAt());
    }

    @Test
    void reasonIsTruncated() throws Exception {
        stateStore.leadDecisions.saveLeadDecision(
                "decision-long", "sub-001", "verdict-001",
                LeadDecisionStore.DECISION_REJECT, "reason ".repeat(1000), null,
                LeadDecisionStore.DECIDED_BY_USER);

        LeadDecisionStore.LeadDecisionRecord record = stateStore.leadDecisions
                .findDecisionById("decision-long").orElseThrow();
        assertEquals(4096, record.reason().length());
    }

    @Test
    void verdictCanHaveOnlyOneLeadDecision() throws Exception {
        stateStore.leadDecisions.saveLeadDecision(
                "decision-first", "sub-001", "verdict-001",
                LeadDecisionStore.DECISION_ACCEPT, "accepted", null,
                LeadDecisionStore.DECIDED_BY_RUNTIME);

        assertThrows(SQLException.class, () -> stateStore.leadDecisions.saveLeadDecision(
                "decision-conflict", "sub-001", "verdict-001",
                LeadDecisionStore.DECISION_REJECT, "reject", null,
                LeadDecisionStore.DECIDED_BY_USER));
        assertEquals("decision-first", stateStore.leadDecisions
                .findDecisionByVerdictId("verdict-001").orElseThrow().decisionId());
    }

    @Test
    void closeInvalidatesStoreConnection() throws Exception {
        LeadDecisionStore decisions = stateStore.leadDecisions;
        stateStore.close();
        stateStore = null;

        assertThrows(SQLException.class, () -> decisions.findDecisionsByRun("sub-001"));
    }

    private void saveVerdict(String verdictId) throws Exception {
        stateStore.structuredVerdicts.saveStructuredVerdict(
                verdictId, "sub-001",
                new StructuredVerdict(
                        Verdict.FAIL, Severity.HIGH, 0.8,
                        List.of(new VerdictEvidence(
                                EvidenceSourceType.REVIEWER_MODEL,
                                "App.java", 3, "needs fix")),
                        RequiredAction.FIX, "fix required", true),
                "json", "raw-sha");
    }
}
