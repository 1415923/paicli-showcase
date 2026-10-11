package com.paicli.subagent.verdict;

import com.paicli.state.CodingRunStore;
import com.paicli.state.StateStore;
import com.paicli.state.SubagentRunStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerdictExtractionStoreCompatibilityTest {

    @Test
    void extractionOutputCanBeSavedByStructuredVerdictStore(@TempDir Path tempDir)
            throws Exception {
        VerdictExtractionResult extraction = new VerdictExtractor().extract(
                VerdictExtractionRequest.modelOutput("""
                        [VERDICT: FAIL]
                        [SEVERITY: HIGH]
                        [CONFIDENCE: 0.9]
                        [EVIDENCE: REVIEWER_STATIC_PATTERN:A.java:9:unsafe branch]
                        [REQUIRED_ACTION: FIX]
                        [SUMMARY: deterministic marker result]
                        """));

        try (StateStore stateStore = new StateStore(tempDir)) {
            stateStore.tasks.createTask(
                    "task-extract", "review code", "mock", null, "deny", 10);
            stateStore.codingRuns.create(
                    "run-extract", "task-extract", CodingRunStore.KIND_INITIAL, null, 1, 0);
            stateStore.subagentRuns.createSubagentRun(
                    "sub-extract", "task-extract", "run-extract", "reviewer",
                    SubagentRunStore.KIND_REVIEWER, "AFTER_EXECUTE", "occ-extract",
                    1, "mock", true, null, null);
            stateStore.structuredVerdicts.saveStructuredVerdict(
                    "verdict-extract", "sub-extract", extraction.verdict(),
                    extraction.strategyName(), "raw-output-hash");

            var stored = stateStore.structuredVerdicts
                    .findVerdictById("verdict-extract").orElseThrow();
            assertEquals("FAIL", stored.verdict());
            assertEquals(extraction.strategyName(), stored.parserStrategy());
            assertTrue(stored.evidenceJson().contains("REVIEWER_STATIC_PATTERN"));
        }
    }
}
