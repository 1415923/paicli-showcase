package com.paicli.session;

import com.paicli.state.LeadDecisionStore;
import com.paicli.state.StateStore;
import com.paicli.state.StructuredVerdictStore;
import com.paicli.state.SubagentRunStore;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Builds bounded report metadata without exposing subagent prompts or outputs. */
public final class SubagentReportService {

    public static final int DEFAULT_MAX_RUNS = 50;
    public static final int MAX_RUNS = 200;
    private static final int MAX_NAME_CHARS = 128;

    private final StateStore stateStore;

    public SubagentReportService(StateStore stateStore) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore is required");
    }

    public MarkdownReport.SubagentReportSummary summarizeTask(String taskId)
            throws SQLException {
        return summarizeTask(taskId, DEFAULT_MAX_RUNS);
    }

    public MarkdownReport.SubagentReportSummary summarizeTask(
            String taskId, int maxRuns) throws SQLException {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId is required");
        }
        if (maxRuns < 1 || maxRuns > MAX_RUNS) {
            throw new IllegalArgumentException(
                    "maxRuns must be between 1 and " + MAX_RUNS);
        }

        List<SubagentRunStore.SubagentRunRecord> allRuns =
                stateStore.subagentRuns.findRecentRunsByTask(taskId, maxRuns + 1);
        boolean truncated = allRuns.size() > maxRuns;
        int fromIndex = Math.max(0, allRuns.size() - maxRuns);
        List<MarkdownReport.SubagentSummary> summaries = new ArrayList<>();
        for (SubagentRunStore.SubagentRunRecord run
                : allRuns.subList(fromIndex, allRuns.size())) {
            summaries.add(toSummary(run));
        }
        return new MarkdownReport.SubagentReportSummary(summaries, truncated);
    }

    private MarkdownReport.SubagentSummary toSummary(
            SubagentRunStore.SubagentRunRecord run) throws SQLException {
        List<StructuredVerdictStore.StructuredVerdictRecord> verdicts =
                stateStore.structuredVerdicts.findVerdictsByRun(run.subagentRunId());
        StructuredVerdictStore.StructuredVerdictRecord verdict =
                verdicts.isEmpty() ? null : verdicts.getLast();
        String decision = "not_available";
        if (verdict != null) {
            decision = stateStore.leadDecisions
                    .findDecisionByVerdictId(verdict.verdictId())
                    .map(LeadDecisionStore.LeadDecisionRecord::decision)
                    .orElse("pending");
        }
        return new MarkdownReport.SubagentSummary(
                bounded(run.subagentName()),
                bounded(run.subagentKind()),
                bounded(run.status()),
                run.attemptNumber(),
                verdict == null ? "not_available" : bounded(verdict.verdict()),
                verdict == null ? "not_available" : bounded(verdict.severity()),
                verdict == null ? 0.0d : verdict.confidence(),
                verdict == null ? "not_available" : bounded(verdict.requiredAction()),
                decision,
                run.verificationStatus() == null
                        ? "not_applicable" : bounded(run.verificationStatus()));
    }

    private static String bounded(String value) {
        if (value == null) {
            return "not_available";
        }
        String singleLine = value.replace('\r', ' ').replace('\n', ' ');
        return singleLine.length() <= MAX_NAME_CHARS
                ? singleLine : singleLine.substring(0, MAX_NAME_CHARS);
    }
}
