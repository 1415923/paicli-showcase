package com.paicli.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownReportTest {

    @TempDir
    Path tempDir;

    @Test
    void writeCreatesReportFile() throws IOException {
        var report = new MarkdownReport(tempDir.resolve("runs"), "run-001");

        Instant start = Instant.parse("2026-06-25T10:00:00Z");
        Instant end = Instant.parse("2026-06-25T10:00:02Z");

        report.write("list files", start, end, 2,
                List.of("USER: list files", "ACTION: list_files", "FINAL: done"),
                "done", true);

        Path file = tempDir.resolve("runs").resolve("run-001.md");
        assertTrue(Files.exists(file));

        String content = Files.readString(file);
        assertTrue(content.contains("# PaiCLI Run Report"));
        assertTrue(content.contains("**Goal:** list files"));
        assertTrue(content.contains("**Status:** Completed"));
        assertTrue(content.contains("## Final Answer"));
        assertTrue(content.contains("done"));
    }

    @Test
    void reportShowsMaxIterationsReached() throws IOException {
        var report = new MarkdownReport(tempDir.resolve("runs"), "run-002");

        report.write("complex task", Instant.now(), Instant.now(), 10,
                List.of("USER: complex task", "ACTION: tool1"),
                null, false);

        String content = Files.readString(report.getReportFile());
        assertTrue(content.contains("**Status:** Max iterations reached"));
        assertTrue(content.contains("no final answer"));
    }

    @Test
    void createsRunsDirectoryIfMissing() throws IOException {
        Path nested = tempDir.resolve("reports").resolve("runs");
        var report = new MarkdownReport(nested, "run-003");
        report.write("test", Instant.now(), Instant.now(), 1, List.of(), "ok", true);
        assertTrue(Files.exists(nested.resolve("run-003.md")));
    }

    @Test
    void reportIncludesBoundedSkillsAndHookStatusSummaryOnly() throws IOException {
        var report = new MarkdownReport(tempDir.resolve("runs"), "run-runtime");
        report.write("task", Instant.now(), Instant.now(), 1,
                List.of(), "done", true,
                new MarkdownReport.RuntimeSummary(
                        List.of("java-build"),
                        List.of("format=PASSED", "audit=WARNED"), 1));

        String content = Files.readString(report.getReportFile());
        assertTrue(content.contains("Selected skills: java-build"));
        assertTrue(content.contains("Hook runs: 2"));
        assertTrue(content.contains("Hook warnings/failures: 1"));
        assertTrue(content.contains("audit=WARNED"));
    }

    @Test
    void reportShowsBoundedSubagentDecisionSemanticsWithoutPayloads()
            throws IOException {
        var report = new MarkdownReport(tempDir.resolve("runs"), "run-subagent");
        var subagents = new MarkdownReport.SubagentReportSummary(List.of(
                new MarkdownReport.SubagentSummary(
                        "reviewer", "REVIEWER", "SUCCEEDED", 1,
                        "INCONCLUSIVE", "MEDIUM", 0.6d, "HUMAN_REVIEW",
                        "ACCEPT", "not_applicable"),
                new MarkdownReport.SubagentSummary(
                        "verifier", "VERIFIER", "SUCCEEDED", 2,
                        "FAIL", "HIGH", 1.0d, "FIX",
                        "REJECT", "FAILED"),
                new MarkdownReport.SubagentSummary(
                        "scout", "SCOUT", "SUCCEEDED", 1,
                        "PASS", "NONE", 0.8d, "NONE",
                        "pending", "not_applicable")), false);

        report.write("task", Instant.now(), Instant.now(), 1,
                List.of(), "done", true,
                new MarkdownReport.RuntimeSummary(
                        List.of(), List.of(), 0, subagents));

        String content = Files.readString(report.getReportFile());
        assertTrue(content.contains("verdict=INCONCLUSIVE"));
        assertTrue(content.contains("ACCEPT (accepted view)"));
        assertTrue(content.contains("REJECT (audit only; not accepted)"));
        assertTrue(content.contains("pending (not accepted)"));
        assertTrue(content.contains("verification_status=FAILED"));
        assertTrue(content.contains("run/verdict/decision level"));
        assertFalse(content.contains("raw-provider-output"));
        assertFalse(content.contains("untrusted-code-context"));
        assertFalse(content.contains("full-verification-output"));
    }

    @Test
    void reportShowsBoundedWorkerTeamSemanticsWithoutSensitivePayloads()
            throws IOException {
        var report = new MarkdownReport(tempDir.resolve("runs"), "run-workers");
        var team = new MarkdownReport.WorkerTeamReportSummary(
                "team-occurrence", "repo:8a1f", "abc1234", "PARTIAL",
                List.of(new MarkdownReport.WorkerSummary(
                        "worker-a", "A", "COMPLETED", 1, "COMPLETED",
                        "GIT_LINKED_WORKTREE", "CANDIDATE_FROZEN", 2,
                        "READY_FOR_DECISION", "PASSED", 3, 1)),
                new MarkdownReport.ProposalSummary(
                        "proposal:91ef", 1, "DISJOINT", "CLEAN_MERGE",
                        "PASSED", "READY_FOR_APPROVAL"),
                new MarkdownReport.IntegrationSummary(
                        "APPROVED", "USER", "not_started", "pending", false),
                List.of(new MarkdownReport.CleanupSummary(
                        "WORKTREE", "PARTIAL", "SUCCEEDED", "FAILED", "PENDING")),
                true);

        report.write("sensitive task specification", Instant.now(), Instant.now(), 1,
                List.of(), "done", true,
                new MarkdownReport.RuntimeSummary(
                        List.of(), List.of(), 0,
                        MarkdownReport.SubagentReportSummary.empty(), team));

        String content = Files.readString(report.getReportFile());
        assertTrue(content.contains("## Worker Team"));
        assertTrue(content.contains("overall status".replace('o', 'O')));
        assertTrue(content.contains("APPROVED is not INTEGRATED"));
        assertTrue(content.contains("DISJOINT is not semantic safety"));
        assertTrue(content.contains("REQUEST_REWORK is audit-only"));
        assertTrue(content.contains("Summary truncated: true"));
        assertFalse(content.contains("sensitive task specification"));
        assertFalse(content.contains("C:\\Users\\secret"));
        assertFalse(content.contains("full mailbox payload"));
        assertFalse(content.contains("decision reason text"));
    }
}
