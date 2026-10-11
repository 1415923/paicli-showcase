package com.paicli.session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Generates a Markdown report for a completed agent run.
 */
public class MarkdownReport {

    private final Path reportFile;

    /**
     * @param reportsDir directory to write reports (e.g. reports/runs)
     * @param taskId     unique task identifier
     */
    public MarkdownReport(Path reportsDir, String taskId) throws IOException {
        Files.createDirectories(reportsDir);
        this.reportFile = reportsDir.resolve(taskId + ".md");
    }

    /**
     * Write the full report.
     *
     * @param goal          user's goal
     * @param startedAt     when the run started
     * @param completedAt   when the run completed
     * @param iterations    number of iterations
     * @param messages      log lines from AgentLoop
     * @param answer        final answer (null if max iterations reached)
     * @param completed     whether the loop completed naturally
     */
    public void write(String goal, Instant startedAt, Instant completedAt,
                      int iterations, List<String> messages,
                      String answer, boolean completed) throws IOException {
        write(goal, startedAt, completedAt, iterations, messages,
                answer, completed, RuntimeSummary.empty());
    }

    public void write(String goal, Instant startedAt, Instant completedAt,
                      int iterations, List<String> messages,
                      String answer, boolean completed,
                      RuntimeSummary runtimeSummary) throws IOException {

        boolean workerTeamReport = runtimeSummary.workerTeam() != null
                && (!runtimeSummary.workerTeam().workers().isEmpty()
                || !"not_available".equals(runtimeSummary.workerTeam()
                        .coordinatorOccurrence()));
        StringBuilder sb = new StringBuilder();
        sb.append("# PaiCLI Run Report\n\n");
        sb.append("**Task ID:** ").append(reportFile.getFileName().toString().replace(".md", "")).append("\n");
        sb.append("**Goal:** ").append(workerTeamReport
                ? "(bounded Worker Team task; details omitted)" : goal).append("\n");
        sb.append("**Started:** ").append(startedAt).append("\n");
        sb.append("**Completed:** ").append(completedAt).append("\n");
        sb.append("**Iterations:** ").append(iterations).append("\n");
        sb.append("**Status:** ").append(completed ? "Completed" : "Max iterations reached").append("\n\n");

        sb.append("## Execution Steps\n\n");
        if (workerTeamReport) {
            sb.append("- (Worker/model execution details omitted)\n");
        } else {
            for (String msg : messages) {
                sb.append("- ").append(msg).append("\n");
            }
        }
        sb.append("\n");

        sb.append("## Final Answer\n\n");
        if (workerTeamReport) {
            sb.append("(Worker/model output omitted; see bounded audit summary)\n\n");
        } else if (answer != null && !answer.isBlank()) {
            sb.append(answer).append("\n\n");
        } else {
            sb.append("(no final answer — max iterations reached)\n\n");
        }

        sb.append("## Skills / Hooks\n\n");
        sb.append("- Selected skills: ")
                .append(runtimeSummary.selectedSkills().isEmpty()
                        ? "none" : String.join(", ", runtimeSummary.selectedSkills()))
                .append("\n");
        sb.append("- Hook runs: ")
                .append(runtimeSummary.hookRuns().size()).append("\n");
        sb.append("- Hook warnings/failures: ")
                .append(runtimeSummary.hookWarningsOrFailures()).append("\n");
        for (String hook : runtimeSummary.hookRuns()) {
            sb.append("  - ").append(hook).append("\n");
        }
        sb.append("\n");

        sb.append("## Subagents\n\n");
        sb.append("- Runs shown: ")
                .append(runtimeSummary.subagents().runs().size()).append("\n");
        sb.append("- Summary truncated: ")
                .append(runtimeSummary.subagents().truncated()).append("\n");
        for (SubagentSummary subagent : runtimeSummary.subagents().runs()) {
            sb.append("  - ").append(subagent.name())
                    .append(" [").append(subagent.kind()).append("]")
                    .append(": run=").append(subagent.runStatus())
                    .append(", attempt=").append(subagent.attempt())
                    .append(", verdict=").append(subagent.verdict())
                    .append(", severity=").append(subagent.severity())
                    .append(", confidence=").append(subagent.confidence())
                    .append(", required_action=").append(subagent.requiredAction())
                    .append(", lead_decision=").append(decisionLabel(subagent.leadDecision()))
                    .append(", verification_status=").append(subagent.verificationStatus())
                    .append("\n");
        }
        sb.append("- Audit scope: run/verdict/decision level; not complete tool-call-level audit.\n\n");

        appendWorkerTeamSummary(sb, runtimeSummary.workerTeam());
        appendMcpSummary(sb, runtimeSummary.mcp());

        sb.append("## Known Limitations\n\n");
        sb.append("- Uses MockModelProvider (no real LLM)\n");
        sb.append("- Command execution limited to workspace\n");
        sb.append("- Output truncated at SafetyPolicy limits\n");

        Files.writeString(reportFile, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /** Get the path to the report file. */
    public Path getReportFile() {
        return reportFile;
    }

    public record RuntimeSummary(
            List<String> selectedSkills,
            List<String> hookRuns,
            long hookWarningsOrFailures,
            SubagentReportSummary subagents,
            WorkerTeamReportSummary workerTeam,
            McpReportService.McpReportSummary mcp
    ) {
        public RuntimeSummary(
                List<String> selectedSkills,
                List<String> hookRuns,
                long hookWarningsOrFailures) {
            this(selectedSkills, hookRuns, hookWarningsOrFailures,
                    SubagentReportSummary.empty(), WorkerTeamReportSummary.empty(),
                    McpReportService.McpReportSummary.empty());
        }

        public RuntimeSummary(List<String> selectedSkills, List<String> hookRuns,
                              long hookWarningsOrFailures,
                              SubagentReportSummary subagents) {
            this(selectedSkills, hookRuns, hookWarningsOrFailures, subagents,
                    WorkerTeamReportSummary.empty(), McpReportService.McpReportSummary.empty());
        }

        public RuntimeSummary(List<String> selectedSkills, List<String> hookRuns,
                              long hookWarningsOrFailures, SubagentReportSummary subagents,
                              WorkerTeamReportSummary workerTeam) {
            this(selectedSkills, hookRuns, hookWarningsOrFailures, subagents, workerTeam,
                    McpReportService.McpReportSummary.empty());
        }

        public RuntimeSummary {
            selectedSkills = List.copyOf(selectedSkills);
            hookRuns = List.copyOf(hookRuns);
            subagents = subagents == null ? SubagentReportSummary.empty() : subagents;
            workerTeam = workerTeam == null ? WorkerTeamReportSummary.empty() : workerTeam;
            mcp = mcp == null ? McpReportService.McpReportSummary.empty() : mcp;
            if (hookWarningsOrFailures < 0) {
                throw new IllegalArgumentException(
                        "hookWarningsOrFailures must not be negative");
            }
        }

        public static RuntimeSummary empty() {
            return new RuntimeSummary(List.of(), List.of(), 0);
        }
    }

    public record SubagentReportSummary(
            List<SubagentSummary> runs,
            boolean truncated
    ) {
        public SubagentReportSummary {
            runs = List.copyOf(runs);
        }

        public static SubagentReportSummary empty() {
            return new SubagentReportSummary(List.of(), false);
        }
    }

    public record SubagentSummary(
            String name,
            String kind,
            String runStatus,
            int attempt,
            String verdict,
            String severity,
            double confidence,
            String requiredAction,
            String leadDecision,
            String verificationStatus
    ) {
    }

    public record WorkerTeamReportSummary(
            String coordinatorOccurrence,
            String repositoryIdentitySummary,
            String baseSha,
            String overallStatus,
            List<WorkerSummary> workers,
            ProposalSummary proposal,
            IntegrationSummary integration,
            List<CleanupSummary> cleanups,
            boolean truncated
    ) {
        public WorkerTeamReportSummary {
            workers = List.copyOf(workers);
            cleanups = List.copyOf(cleanups);
        }

        public static WorkerTeamReportSummary empty() {
            return new WorkerTeamReportSummary("not_available", "not_available",
                    "not_available", "not_available", List.of(), null, null,
                    List.of(), false);
        }
    }

    public record WorkerSummary(String name, String slot, String runStatus, int attempt,
            String claimStatus, String backend, String worktreeStatus,
            int changedFileCount, String candidateStatus,
            String candidateVerificationStatus, int mailboxMessageCount,
            int blockerCount) { }

    public record ProposalSummary(String proposalIdSummary, int candidateCount,
            String overlapStatus, String mergeStatus, String verificationStatus,
            String proposalStatus) { }

    public record IntegrationSummary(String decision, String decidedBy,
            String integrationRunStatus, String resultCategory, boolean integrated) { }

    public record CleanupSummary(String targetType, String status,
            String workspaceStatus, String stateStatus, String traceStatus) { }

    private static void appendWorkerTeamSummary(StringBuilder sb,
            WorkerTeamReportSummary team) {
        sb.append("## Worker Team\n\n");
        sb.append("- Coordinator occurrence: ").append(team.coordinatorOccurrence()).append("\n");
        sb.append("- Repository identity: ").append(team.repositoryIdentitySummary()).append("\n");
        sb.append("- Base SHA: ").append(team.baseSha()).append("\n");
        sb.append("- Task/Worker count shown: ").append(team.workers().size()).append("\n");
        sb.append("- Overall status: ").append(team.overallStatus()).append("\n");
        sb.append("- Summary truncated: ").append(team.truncated()).append("\n");
        for (WorkerSummary worker : team.workers()) {
            sb.append("  - ").append(worker.name()).append(" [slot=")
                    .append(worker.slot()).append("]: run=").append(worker.runStatus())
                    .append(", attempt=").append(worker.attempt())
                    .append(", claim=").append(worker.claimStatus())
                    .append(", worktree=").append(worker.backend()).append('/')
                    .append(worker.worktreeStatus())
                    .append(", changed_files=").append(worker.changedFileCount())
                    .append(", candidate=").append(worker.candidateStatus())
                    .append(", verification=").append(worker.candidateVerificationStatus())
                    .append(", mailbox_messages=").append(worker.mailboxMessageCount())
                    .append(", blockers=").append(worker.blockerCount()).append("\n");
        }
        if (team.proposal() != null) {
            ProposalSummary proposal = team.proposal();
            sb.append("- Proposal ").append(proposal.proposalIdSummary())
                    .append(": candidates=").append(proposal.candidateCount())
                    .append(", overlap=").append(proposal.overlapStatus())
                    .append(", merge=").append(proposal.mergeStatus())
                    .append(", verification=").append(proposal.verificationStatus())
                    .append(", status=").append(proposal.proposalStatus()).append("\n");
        }
        if (team.integration() != null) {
            IntegrationSummary integration = team.integration();
            sb.append("- Decision/Integration: decision=").append(integration.decision())
                    .append(", decided_by=").append(integration.decidedBy())
                    .append(", run=").append(integration.integrationRunStatus())
                    .append(", result=").append(integration.resultCategory())
                    .append(", integrated=").append(integration.integrated()).append("\n");
        }
        for (CleanupSummary cleanup : team.cleanups()) {
            sb.append("- Cleanup ").append(cleanup.targetType()).append(": status=")
                    .append(cleanup.status()).append(", workspace=")
                    .append(cleanup.workspaceStatus()).append(", state=")
                    .append(cleanup.stateStatus()).append(", trace=")
                    .append(cleanup.traceStatus()).append("\n");
        }
        sb.append("- Semantics: Candidate READY is not accepted; Proposal READY_FOR_APPROVAL is not approved; APPROVED is not INTEGRATED.\n");
        sb.append("- Gates: DISJOINT is not semantic safety; CLEAN_MERGE is not business correctness; PASSED covers only configured verification.\n");
        sb.append("- REQUEST_REWORK is audit-only in v0.9.0 and does not start a Worker.\n\n");
    }


    private static void appendMcpSummary(StringBuilder sb, McpReportService.McpReportSummary mcp) {
        sb.append("## MCP Client\n\n");
        if (mcp.manifest() == null || mcp.session() == null) {
            sb.append("- No MCP session audit is attached to this report.\n\n");
            return;
        }
        var manifest = mcp.manifest();
        var session = mcp.session();
        var catalog = mcp.catalog();
        sb.append("- Server: ").append(manifest.serverId()).append(", manifest=")
                .append(manifest.manifestHash()).append(", transport=").append(manifest.transport())
                .append(", protocol_policy=").append(manifest.protocolPolicy())
                .append(", config=").append(manifest.driftCategory())
                .append(", executable=").append(manifest.executableBasename()).append("\n");
        sb.append("- Session ").append(session.sessionId()).append(": occurrence=")
                .append(session.occurrence()).append(", attempt=").append(session.attempt())
                .append(", persisted=").append(session.persistedStatus())
                .append(", live=").append(session.liveStatus())
                .append(", protocol=").append(session.protocolVersion())
                .append(", tools_capability=").append(session.toolsCapability())
                .append(", process_alive=").append(session.processAlive())
                .append(", catalog=").append(session.catalogId()).append('/').append(session.catalogStatus())
                .append(", error=").append(session.errorCategory()).append("\n");
        sb.append("- Catalog ").append(catalog.catalogId()).append('/').append(catalog.catalogHash())
                .append(": status=").append(catalog.status()).append(", pages=").append(catalog.pageCount())
                .append(", tools=").append(catalog.toolCount()).append(", exposed=").append(catalog.exposedCount())
                .append(", rejected=").append(catalog.rejectedCount()).append(", schema_policy=")
                .append(catalog.schemaPolicyVersion()).append(", list_changed=").append(catalog.listChanged())
                .append(", truncated=").append(catalog.truncated()).append("\n");
        for (var tool : catalog.tools()) {
            sb.append("  - Tool ").append(tool.exposedName()).append(" (original=")
                    .append(tool.originalName()).append("): exposure=").append(tool.exposureStatus())
                    .append(", schema=").append(tool.schemaHash()).append(", rejection=")
                    .append(tool.rejectionCategory()).append(", description=").append(tool.description()).append("\n");
        }
        for (var call : mcp.calls()) {
            sb.append("  - Call ").append(call.callId()).append(' ').append(call.exposedToolName())
                    .append(": status=").append(call.status()).append(", approval=").append(call.approval())
                    .append(", request_id=").append(call.requestIdFact()).append(", artifacts=")
                    .append(call.artifactHashMatch()).append(", arguments_hash=").append(call.argumentsHashFact())
                    .append(", result_hash=").append(call.resultHash()).append(", truncated=")
                    .append(call.truncated()).append(", redacted=").append(call.redacted())
                    .append(", outcome=").append(call.outcomeCertainty()).append(", error=")
                    .append(call.errorCategory()).append("\n");
        }
        sb.append("- Calls truncated: ").append(mcp.callsTruncated()).append("\n");
        sb.append("- Recovery: ").append(mcp.recoveryRecommendation())
                .append(", automatic_refresh=").append(mcp.automaticRefresh())
                .append(", automatic_retry=").append(mcp.automaticRetry())
                .append(", automatic_approval_reuse=").append(mcp.automaticApprovalReuse()).append("\n");
        sb.append("- Semantics: persisted READY is not a live connection; FROZEN is not server liveness; EXPOSED is not approval.\n");
        sb.append("- APPROVED is not success; UNKNOWN_OUTCOME may have external side effects; TIMED_OUT does not prove rollback.\n");
        sb.append("- STALE catalogs block new calls; refresh is explicit and does not hot-replace a Registry.\n");
        sb.append("- MCP metadata and tool output are untrusted data; schema validation does not prove business safety.\n\n");
    }
    private static String decisionLabel(String decision) {
        return switch (decision) {
            case "ACCEPT" -> "ACCEPT (accepted view)";
            case "REJECT" -> "REJECT (audit only; not accepted)";
            case "REQUEST_RECHECK" -> "REQUEST_RECHECK (not accepted)";
            case "pending" -> "pending (not accepted)";
            default -> decision;
        };
    }
}
