package com.paicli.subagent;

import com.paicli.runtime.PersistenceSanitizer;
import com.paicli.state.LeadDecisionStore;
import com.paicli.state.StateStore;
import com.paicli.state.StructuredVerdictStore;
import com.paicli.state.SubagentRunStore;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Runtime-owned decisions that control whether persisted verdicts may flow downstream. */
public final class LeadDecisionService {

    private static final int MAX_REASON_BYTES = 4096;

    private final StateStore stateStore;
    private final SubagentRunner subagentRunner;
    private final PersistenceSanitizer sanitizer = new PersistenceSanitizer();

    public LeadDecisionService(StateStore stateStore, SubagentRunner subagentRunner) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore is required");
        this.subagentRunner = Objects.requireNonNull(subagentRunner, "subagentRunner is required");
    }

    public DecisionResult accept(String verdictId, String decidedBy, String reason) {
        return decide(verdictId, LeadDecisionStore.DECISION_ACCEPT,
                decidedBy, reason, null);
    }

    public DecisionResult reject(String verdictId, String decidedBy, String reason) {
        requireReason(reason, LeadDecisionStore.DECISION_REJECT);
        return decide(verdictId, LeadDecisionStore.DECISION_REJECT,
                decidedBy, reason, null);
    }

    public DecisionResult requestRecheck(
            String verdictId,
            String decidedBy,
            String reason,
            SubagentRunRequest runnerRequest) {
        requireReason(reason, LeadDecisionStore.DECISION_REQUEST_RECHECK);
        Objects.requireNonNull(runnerRequest, "runnerRequest is required");
        validateDecidedBy(decidedBy);

        DecisionContext context = requireEligibleVerdict(verdictId);
        validateRecheckRequest(context, runnerRequest);
        LeadDecisionStore.LeadDecisionRecord existing = findDecision(verdictId);
        if (existing != null) {
            if (!LeadDecisionStore.DECISION_REQUEST_RECHECK.equals(existing.decision())) {
                throw new IllegalStateException(
                        "conflicting LeadDecision already exists for verdict: "
                                + existing.verdictId());
            }
            return new DecisionResult(
                    existing, runRecheck(context, runnerRequest, existing), true);
        }

        String decisionId = UUID.randomUUID().toString();
        String occurrenceKey = context.run().occurrenceKey()
                + ":recheck:" + decisionId;
        LeadDecisionStore.LeadDecisionRecord decision = saveDecision(
                decisionId, context.run().subagentRunId(), verdictId,
                LeadDecisionStore.DECISION_REQUEST_RECHECK, reason,
                occurrenceKey, decidedBy);

        return new DecisionResult(
                decision, runRecheck(context, runnerRequest, decision), false);
    }

    private SubagentRunnerResult runRecheck(
            DecisionContext context,
            SubagentRunRequest runnerRequest,
            LeadDecisionStore.LeadDecisionRecord decision) {
        if (decision.recheckOccurrenceKey() == null
                || decision.recheckOccurrenceKey().isBlank()) {
            throw new IllegalStateException(
                    "REQUEST_RECHECK decision is missing its occurrence key");
        }
        SubagentRunRequest recheckRequest = new SubagentRunRequest(
                context.run().taskId(),
                context.run().codingRunId(),
                runnerRequest.stage(),
                decision.recheckOccurrenceKey(),
                Math.max(runnerRequest.attemptNumber(), context.run().attemptNumber() + 1),
                runnerRequest.definition(),
                runnerRequest.taskInput(),
                runnerRequest.provider(),
                runnerRequest.providerName(),
                runnerRequest.extractionMode(),
                runnerRequest.codeTarget());
        return subagentRunner.run(recheckRequest);
    }

    private void validateRecheckRequest(
            DecisionContext context,
            SubagentRunRequest runnerRequest) {
        if (!context.run().taskId().equals(runnerRequest.taskId())
                || !context.run().codingRunId().equals(runnerRequest.codingRunId())) {
            throw new IllegalArgumentException(
                    "recheck request must use the original task and coding run");
        }
    }

    public DecisionViews viewsForTask(String taskId) {
        requireText(taskId, "taskId");
        List<VerdictDecisionView> accepted = new ArrayList<>();
        List<VerdictDecisionView> rejected = new ArrayList<>();
        List<VerdictDecisionView> pending = new ArrayList<>();
        List<VerdictDecisionView> recheckRequested = new ArrayList<>();
        try {
            for (SubagentRunStore.SubagentRunRecord run
                    : stateStore.subagentRuns.findRunsByTask(taskId)) {
                for (StructuredVerdictStore.StructuredVerdictRecord verdict
                        : stateStore.structuredVerdicts.findVerdictsByRun(
                                run.subagentRunId())) {
                    LeadDecisionStore.LeadDecisionRecord decision = stateStore.leadDecisions
                            .findDecisionByVerdictId(verdict.verdictId()).orElse(null);
                    VerdictDecisionView view = new VerdictDecisionView(verdict, decision);
                    if (decision == null) {
                        pending.add(view);
                    } else if (LeadDecisionStore.DECISION_ACCEPT.equals(decision.decision())) {
                        accepted.add(view);
                    } else if (LeadDecisionStore.DECISION_REJECT.equals(decision.decision())) {
                        rejected.add(view);
                    } else {
                        recheckRequested.add(view);
                    }
                }
            }
            return new DecisionViews(
                    List.copyOf(accepted), List.copyOf(rejected),
                    List.copyOf(pending), List.copyOf(recheckRequested));
        } catch (SQLException e) {
            throw new IllegalStateException("failed to build LeadDecision views", e);
        }
    }

    private DecisionResult decide(
            String verdictId,
            String decisionType,
            String decidedBy,
            String reason,
            String recheckOccurrenceKey) {
        validateDecidedBy(decidedBy);
        DecisionContext context = requireEligibleVerdict(verdictId);
        LeadDecisionStore.LeadDecisionRecord existing = findDecision(verdictId);
        if (existing != null) {
            return idempotentOrConflict(existing, decisionType);
        }
        LeadDecisionStore.LeadDecisionRecord decision = saveDecision(
                UUID.randomUUID().toString(), context.run().subagentRunId(),
                verdictId, decisionType, reason, recheckOccurrenceKey, decidedBy);
        return new DecisionResult(decision, null, false);
    }

    private DecisionContext requireEligibleVerdict(String verdictId) {
        requireText(verdictId, "verdictId");
        try {
            StructuredVerdictStore.StructuredVerdictRecord verdict = stateStore
                    .structuredVerdicts.findVerdictById(verdictId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "structured verdict not found: " + verdictId));
            SubagentRunStore.SubagentRunRecord run = stateStore.subagentRuns
                    .findSubagentRunById(verdict.subagentRunId())
                    .orElseThrow(() -> new IllegalStateException(
                            "subagent run not found for verdict: " + verdictId));
            if (!SubagentRunStore.STATUS_SUCCEEDED.equals(run.status())) {
                throw new IllegalStateException(
                        "LeadDecision requires a SUCCEEDED subagent run with a persisted verdict");
            }
            return new DecisionContext(verdict, run);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to inspect structured verdict", e);
        }
    }

    private LeadDecisionStore.LeadDecisionRecord findDecision(String verdictId) {
        try {
            return stateStore.leadDecisions.findDecisionByVerdictId(verdictId).orElse(null);
        } catch (SQLException e) {
            throw new IllegalStateException("failed to inspect LeadDecision", e);
        }
    }

    private LeadDecisionStore.LeadDecisionRecord saveDecision(
            String decisionId,
            String subagentRunId,
            String verdictId,
            String decision,
            String reason,
            String recheckOccurrenceKey,
            String decidedBy) {
        try {
            stateStore.leadDecisions.saveLeadDecision(
                    decisionId, subagentRunId, verdictId, decision,
                    sanitizer.sanitizeAndTruncate(reason, MAX_REASON_BYTES),
                    recheckOccurrenceKey, decidedBy);
            return stateStore.leadDecisions.findDecisionById(decisionId).orElseThrow();
        } catch (SQLException e) {
            throw new IllegalStateException("failed to persist LeadDecision", e);
        }
    }

    private DecisionResult idempotentOrConflict(
            LeadDecisionStore.LeadDecisionRecord existing,
            String requestedDecision) {
        if (!existing.decision().equals(requestedDecision)) {
            throw new IllegalStateException(
                    "conflicting LeadDecision already exists for verdict: "
                            + existing.verdictId());
        }
        return new DecisionResult(existing, null, true);
    }

    private static void validateDecidedBy(String decidedBy) {
        if (!LeadDecisionStore.DECIDED_BY_RUNTIME.equals(decidedBy)
                && !LeadDecisionStore.DECIDED_BY_USER.equals(decidedBy)
                && !LeadDecisionStore.DECIDED_BY_POLICY.equals(decidedBy)) {
            throw new IllegalArgumentException(
                    "decidedBy must be RUNTIME, USER, or POLICY");
        }
    }

    private static void requireReason(String reason, String decision) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(decision + " requires a non-empty reason");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    private record DecisionContext(
            StructuredVerdictStore.StructuredVerdictRecord verdict,
            SubagentRunStore.SubagentRunRecord run) {
    }

    public record DecisionResult(
            LeadDecisionStore.LeadDecisionRecord decision,
            SubagentRunnerResult recheckResult,
            boolean idempotent) {
    }

    public record VerdictDecisionView(
            StructuredVerdictStore.StructuredVerdictRecord verdict,
            LeadDecisionStore.LeadDecisionRecord decision) {
    }

    public record DecisionViews(
            List<VerdictDecisionView> accepted,
            List<VerdictDecisionView> rejected,
            List<VerdictDecisionView> pending,
            List<VerdictDecisionView> recheckRequested) {
    }
}
