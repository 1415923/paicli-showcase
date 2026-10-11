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

class StructuredVerdictStoreTest {

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
    }

    @AfterEach
    void tearDown() throws Exception {
        if (stateStore != null) {
            stateStore.close();
        }
    }

    @Test
    void savePassFailAndInconclusiveVerdicts() throws Exception {
        stateStore.structuredVerdicts.saveStructuredVerdict(
                "verdict-pass", "sub-001",
                verdict(Verdict.PASS, Severity.NONE, 0.9, RequiredAction.NONE),
                "json", "raw-pass");
        stateStore.structuredVerdicts.saveStructuredVerdict(
                "verdict-fail", "sub-001",
                verdict(Verdict.FAIL, Severity.HIGH, 0.8, RequiredAction.FIX),
                "marker", "raw-fail");
        stateStore.structuredVerdicts.saveStructuredVerdict(
                "verdict-inc", "sub-001",
                verdict(Verdict.INCONCLUSIVE, Severity.NONE, 0.0, RequiredAction.HUMAN_REVIEW),
                "fallback", "raw-inc");

        List<StructuredVerdictStore.StructuredVerdictRecord> records =
                stateStore.structuredVerdicts.findVerdictsByRun("sub-001");

        assertEquals(3, records.size());
        assertEquals("PASS", records.getFirst().verdict());
        assertEquals("FAIL", records.get(1).verdict());
        assertEquals("INCONCLUSIVE", records.getLast().verdict());
    }

    @Test
    void evidenceJsonRoundTripsWithSourceType() throws Exception {
        stateStore.structuredVerdicts.saveStructuredVerdict(
                "verdict-001", "sub-001",
                verdict(Verdict.FAIL, Severity.MEDIUM, 0.7, RequiredAction.RECHECK),
                "json", "raw-sha");

        StructuredVerdictStore.StructuredVerdictRecord record = stateStore
                .structuredVerdicts.findVerdictById("verdict-001").orElseThrow();

        assertEquals("sub-001", record.subagentRunId());
        assertEquals("FAIL", record.verdict());
        assertEquals("MEDIUM", record.severity());
        assertEquals(0.7, record.confidence());
        assertEquals("RECHECK", record.requiredAction());
        assertEquals(1, record.evidenceCount());
        assertTrue(record.evidenceJson().contains("source_type"));
        assertTrue(record.evidenceJson().contains("REVIEWER_CODE_INTELLIGENCE"));
        assertEquals("json", record.parserStrategy());
        assertEquals("raw-sha", record.rawOutputHash());
    }

    @Test
    void rejectsEvidenceWithoutSourceTypeAndConfidenceOutOfRange() {
        StructuredVerdict missingSource = new StructuredVerdict(
                Verdict.FAIL, Severity.LOW, 0.5,
                List.of(new VerdictEvidence(null, null, null, "bad")),
                RequiredAction.FIX, "bad", true);
        StructuredVerdict badConfidence = new StructuredVerdict(
                Verdict.FAIL, Severity.LOW, 1.5,
                List.of(new VerdictEvidence(EvidenceSourceType.REVIEWER_MODEL, null, null, "bad")),
                RequiredAction.FIX, "bad", true);

        assertThrows(IllegalArgumentException.class, () -> stateStore.structuredVerdicts
                .saveStructuredVerdict("bad-source", "sub-001", missingSource, "json", null));
        assertThrows(IllegalArgumentException.class, () -> stateStore.structuredVerdicts
                .saveStructuredVerdict("bad-confidence", "sub-001", badConfidence, "json", null));
    }

    @Test
    void summaryAndEvidenceAreTruncated() throws Exception {
        String longText = "x".repeat(20_000);
        StructuredVerdict verdict = new StructuredVerdict(
                Verdict.FAIL, Severity.LOW, 0.5,
                List.of(new VerdictEvidence(EvidenceSourceType.REVIEWER_MODEL, "A.java", 1, longText)),
                RequiredAction.HUMAN_REVIEW, longText, true);

        stateStore.structuredVerdicts.saveStructuredVerdict(
                "verdict-long", "sub-001", verdict, "json", null);

        StructuredVerdictStore.StructuredVerdictRecord record = stateStore
                .structuredVerdicts.findVerdictById("verdict-long").orElseThrow();
        assertEquals(8192, record.summary().length());
        assertTrue(record.evidenceJson().length() <= 16384);
    }

    @Test
    void closeInvalidatesStoreConnection() throws Exception {
        StructuredVerdictStore verdicts = stateStore.structuredVerdicts;
        stateStore.close();
        stateStore = null;

        assertThrows(SQLException.class, () -> verdicts.findVerdictsByRun("sub-001"));
    }

    private StructuredVerdict verdict(Verdict verdict, Severity severity,
                                      double confidence, RequiredAction action) {
        return new StructuredVerdict(
                verdict, severity, confidence,
                List.of(new VerdictEvidence(
                        EvidenceSourceType.REVIEWER_CODE_INTELLIGENCE,
                        "src/main/java/App.java", 12, "bounded evidence")),
                action, "bounded summary", true);
    }
}
