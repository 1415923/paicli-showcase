package com.paicli.runtime;

import com.paicli.identity.CanonicalId;
import com.paicli.state.StateStore;
import com.paicli.state.TaskContinuityStore;
import com.paicli.state.TaskStore;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.paicli.runtime.TaskContinuityModels.*;

/**
 * Classifies typed segment outcomes and admits a bounded next segment. It never calls Provider or
 * Tool code and owns no background execution lifecycle.
 */
public final class TaskContinuityCoordinator {
    private final StateStore stateStore;
    private final TaskContinuityPolicy policy;
    private final BudgetEvidenceSource budgetEvidenceSource;

    public TaskContinuityCoordinator(StateStore stateStore, TaskContinuityPolicy policy,
            BudgetEvidenceSource budgetEvidenceSource) {
        this.stateStore = Objects.requireNonNull(stateStore);
        this.policy = Objects.requireNonNull(policy);
        this.budgetEvidenceSource = Objects.requireNonNull(budgetEvidenceSource);
    }

    public TaskContinuityPolicy policy() { return policy; }

    public int beginInitialSegment(String taskId, String runId, int planningAttemptOrdinal,
            int startIteration, FencedExecutionGuard guard) throws SQLException {
        if (!policy.enabled()) return 0;
        requireBoundAuthority(taskId, runId, guard, "CONTINUITY_INITIAL_SEGMENT");
        int ordinal = stateStore.taskContinuity.nextOrdinal(taskId);
        if (ordinal != 1) throw new SQLException("CONTINUITY_INITIAL_SEGMENT_ALREADY_EXISTS");
        guard.startContinuitySegment(ordinal,planningAttemptOrdinal,"INITIAL",
                policy.policyHash(),startIteration,RecoveryDirective.NONE.name());
        return ordinal;
    }

    /** Admits work after an explicit user resolution without changing /continue semantics. */
    public int resumeAfterUserResolution(String taskId, String runId,
            SegmentTerminalReason resolvedBlocker, int planningAttemptOrdinal,
            int startIteration, FencedExecutionGuard guard) throws SQLException {
        if (!policy.enabled()) throw new SQLException("CONTINUITY_FEATURE_DISABLED");
        requireBoundAuthority(taskId, runId, guard, "CONTINUITY_USER_RESOLUTION");
        int nextOrdinal = stateStore.taskContinuity.nextOrdinal(taskId);
        var previous = stateStore.taskContinuity.find(taskId, nextOrdinal - 1).orElseThrow(() ->
                new SQLException("CONTINUITY_WAITING_SEGMENT_MISSING"));
        if (!previous.runId().equals(runId)
                || !TaskContinuityStore.WAITING_FOR_USER.equals(previous.state())
                || !resolvedBlocker.name().equals(previous.terminalReason()))
            throw new SQLException("CONTINUITY_USER_RESOLUTION_MISMATCH");
        BudgetEvidence evidence = budgetEvidenceSource.snapshot(taskId, runId);
        Request request = new Request(taskId, runId, previous.segmentOrdinal(),
                planningAttemptOrdinal, startIteration, resolvedBlocker, guard);
        List<String> failures = eligibilityFailures(request, evidence);
        if (!failures.isEmpty()) throw new SQLException(failures.getFirst());
        guard.startContinuitySegment(nextOrdinal, planningAttemptOrdinal,
                "USER_BLOCKER_RESOLVED:" + resolvedBlocker.name(), policy.policyHash(),
                startIteration, RecoveryDirective.NONE.name());
        return nextOrdinal;
    }

    public Decision decide(Request request) throws SQLException {
        Objects.requireNonNull(request);
        BudgetEvidence evidence = budgetEvidenceSource.snapshot(request.taskId(), request.runId());
        RecoveryDirective directive = directive(request.reason());

        if (!policy.enabled()) return terminal(request, "CONTINUITY_FEATURE_DISABLED", evidence,
                List.of("FEATURE_DISABLED"));

        try{
            requireBoundAuthority(request.taskId(),request.runId(),request.guard(),
                    "CONTINUITY_OUTCOME_CLASSIFICATION");
        }catch(RuntimeException|SQLException error){
            return terminal(request,"EXECUTION_AUTHORITY_INVALID",evidence,
                    List.of("EXECUTION_AUTHORITY_INVALID"));
        }

        ContinuationDecision classification = classification(request.reason());
        if (classification == ContinuationDecision.WAIT_FOR_USER) {
            finishCurrent(request, TaskContinuityStore.WAITING_FOR_USER, evidence,
                    request.reason().name());
            return new Decision(classification, "USER_ACTION_REQUIRED", request.segmentOrdinal(),
                    null, request.planningAttemptOrdinal(), directive, List.of(), evidence);
        }
        if (classification == ContinuationDecision.TERMINAL_FAILURE) {
            finishCurrent(request, TaskContinuityStore.TERMINAL_FAILED, evidence,
                    request.reason().name());
            return new Decision(classification, terminalCode(request.reason()),
                    request.segmentOrdinal(), null, request.planningAttemptOrdinal(), directive,
                    List.of(), evidence);
        }

        List<String> failures = eligibilityFailures(request, evidence);
        if (!failures.isEmpty()) {
            finishCurrent(request, TaskContinuityStore.TERMINAL_FAILED, evidence,
                    failures.getFirst());
            return terminal(request, failures.getFirst(), evidence, failures);
        }

        finishCurrent(request, TaskContinuityStore.RECOVERABLE_FAILED, evidence,
                request.reason().name());
        int nextOrdinal = request.segmentOrdinal() + 1;
        int nextPlanningAttempt = request.reason() ==
                SegmentTerminalReason.STRUCTURED_PLAN_REPAIR_FAILED
                ? request.planningAttemptOrdinal() + 1 : request.planningAttemptOrdinal();
        request.guard().startContinuitySegment(nextOrdinal,nextPlanningAttempt,
                request.reason().name(),policy.policyHash(),request.endIteration(),directive.name());
        return new Decision(ContinuationDecision.START_NEXT_SEGMENT, "CONTINUATION_ADMITTED",
                request.segmentOrdinal(), nextOrdinal, nextPlanningAttempt, directive,
                List.of(), evidence);
    }

    public boolean complete(String taskId, int segmentOrdinal, int endIteration,
            FencedExecutionGuard guard) throws SQLException {
        if (!policy.enabled() || segmentOrdinal < 1) return true;
        BudgetEvidence evidence = budgetEvidenceSource.snapshot(taskId,
                stateStore.taskContinuity.find(taskId, segmentOrdinal).orElseThrow().runId());
        if(!evidence.complete()){
            guard.finishContinuitySegment(segmentOrdinal,TaskContinuityStore.TERMINAL_FAILED,
                    endIteration,evidence.providerTurns(),evidence.toolCalls(),
                    evidence.inputTokens(),evidence.outputTokens(),evidence.activeElapsedMillis(),
                    "TASK_BUDGET_EVIDENCE_INCOMPLETE");
            return false;
        }
        guard.finishContinuitySegment(segmentOrdinal,TaskContinuityStore.COMPLETED,endIteration,
                evidence.providerTurns(),evidence.toolCalls(),evidence.inputTokens(),
                evidence.outputTokens(),evidence.activeElapsedMillis(),"TASK_SEGMENT_COMPLETED");
        return true;
    }

    /** Fail-closed terminalization when a Tool admission would exceed Task-total budget. */
    public Decision terminateToolAdmission(Request request,String admissionCode)
            throws SQLException{
        Objects.requireNonNull(request);
        if(!List.of("MAX_TOOL_CALLS_EXHAUSTED","TASK_BUDGET_EVIDENCE_INCOMPLETE")
                .contains(admissionCode))
            throw new IllegalArgumentException("Unsupported Tool budget admission code");
        BudgetEvidence evidence=budgetEvidenceSource.snapshot(request.taskId(),request.runId());
        String code=admissionCode;
        List<String> failures=new ArrayList<>();
        try{requireBoundAuthority(request.taskId(),request.runId(),request.guard(),
                "CONTINUITY_TOOL_BUDGET_TERMINAL");}
        catch(RuntimeException|SQLException error){
            code="EXECUTION_AUTHORITY_INVALID";
            failures.add(code);
        }
        if(!evidence.complete()){
            code="TASK_BUDGET_EVIDENCE_INCOMPLETE";
            failures.add(code);
        }else if("MAX_TOOL_CALLS_EXHAUSTED".equals(code)
                &&evidence.toolCalls()<policy.maxTotalToolCalls()){
            code="TASK_BUDGET_EVIDENCE_INCOMPLETE";
            failures.add(code);
        }
        if(failures.isEmpty())failures.add(code);
        finishCurrent(request,TaskContinuityStore.TERMINAL_FAILED,evidence,code);
        return terminal(request,code,evidence,List.copyOf(failures));
    }

    /** Fail-closed terminalization when a new Provider call would exceed Task-total budget. */
    public Decision terminateProviderAdmission(Request request,String admissionCode)
            throws SQLException{
        Objects.requireNonNull(request);
        if(!List.of("MAX_PROVIDER_TURNS_EXHAUSTED","MAX_INPUT_TOKENS_EXHAUSTED",
                "MAX_OUTPUT_TOKENS_EXHAUSTED","MAX_ACTIVE_ELAPSED_EXHAUSTED",
                "TASK_BUDGET_EVIDENCE_INCOMPLETE").contains(admissionCode))
            throw new IllegalArgumentException("Unsupported Provider budget admission code");
        BudgetEvidence evidence=budgetEvidenceSource.snapshot(request.taskId(),request.runId());
        String code=admissionCode;
        List<String> failures=new ArrayList<>();
        try{requireBoundAuthority(request.taskId(),request.runId(),request.guard(),
                "CONTINUITY_PROVIDER_BUDGET_TERMINAL");}
        catch(RuntimeException|SQLException error){
            code="EXECUTION_AUTHORITY_INVALID";
            failures.add(code);
        }
        if(!evidence.complete()){
            code="TASK_BUDGET_EVIDENCE_INCOMPLETE";
            failures.add(code);
        }else if(!providerLimitReached(code,evidence)){
            code="TASK_BUDGET_EVIDENCE_INCOMPLETE";
            failures.add(code);
        }
        if(failures.isEmpty())failures.add(code);
        finishCurrent(request,TaskContinuityStore.TERMINAL_FAILED,evidence,code);
        return terminal(request,code,evidence,List.copyOf(failures));
    }

    private boolean providerLimitReached(String code,BudgetEvidence evidence){
        return switch(code){
            case "MAX_PROVIDER_TURNS_EXHAUSTED" ->
                    evidence.providerTurns()>=policy.maxTotalProviderTurns();
            case "MAX_INPUT_TOKENS_EXHAUSTED" ->
                    evidence.inputTokens()>=policy.maxTotalInputTokens();
            case "MAX_OUTPUT_TOKENS_EXHAUSTED" ->
                    evidence.outputTokens()>=policy.maxTotalOutputTokens();
            case "MAX_ACTIVE_ELAPSED_EXHAUSTED" ->
                    evidence.activeElapsedMillis()>=policy.maxActiveElapsedMillis();
            case "TASK_BUDGET_EVIDENCE_INCOMPLETE" -> true;
            default -> false;
        };
    }

    private List<String> eligibilityFailures(Request request, BudgetEvidence evidence) {
        List<String> failures = new ArrayList<>();
        try {
            requireBoundAuthority(request.taskId(), request.runId(), request.guard(),
                    "CONTINUITY_NEXT_SEGMENT");
        } catch (RuntimeException | SQLException error) {
            failures.add("EXECUTION_AUTHORITY_INVALID");
            return failures;
        }
        try {
            CanonicalId taskId = CanonicalId.parse(request.taskId());
            CanonicalId runId = CanonicalId.parse(request.runId());
            var legacyTask = stateStore.tasks.findById(request.taskId());
            var task = stateStore.r1aDurable.findTask(taskId);
            var run = stateStore.r1aDurable.findRun(runId);
            var pointer = stateStore.r1aDurable.readContextPointer(taskId);
            if (legacyTask.isEmpty() || !TaskStore.STATUS_RUNNING.equals(legacyTask.get().status())
                    || task.isEmpty() || !List.of("CREATED","RUNNING").contains(task.get().status())
                    || task.get().terminalAt() != null) failures.add("TASK_TERMINAL_OR_NOT_RUNNING");
            if (run.isEmpty() || run.get().terminalStatus() != null) failures.add("RUN_TERMINAL");
            if (stateStore.userResults.find(taskId, runId).isPresent())
                failures.add("FORMAL_RESULT_ALREADY_PERSISTED");
            if (pointer.isEmpty() || !runId.equals(pointer.get().ownerRunId()))
                failures.add("CONTEXT_POINTER_OWNER_MISMATCH");
            else {
                var generation = stateStore.r1aDurable.findGeneration(pointer.get().generationId());
                if (generation.isEmpty() || !"COMMITTED".equals(generation.get().state())
                        || !generation.get().taskId().equals(taskId)
                        || run.isEmpty()
                        || !Objects.equals(generation.get().manifestId(), run.get().manifestId())
                        || !Objects.equals(generation.get().manifestHash(), run.get().manifestHash()))
                    failures.add("CONTEXT_GENERATION_OR_MANIFEST_INVALID");
            }
            var checkpoint = stateStore.checkpoints.loadLatest(request.taskId());
            if (checkpoint.isEmpty() || checkpoint.get().iteration() < request.endIteration()
                    || !List.of("RUNNING", "SEGMENT_FAILED", "PROVIDER_PROTOCOL_ERROR",
                            "SEGMENT_COMPLETE").contains(checkpoint.get().status()))
                failures.add("DURABLE_CHECKPOINT_INVALID");
            if (stateStore.r1aDurable.hasStartedOrUnknownOccurrence(taskId))
                failures.add("STARTED_OR_UNKNOWN_EFFECT_OCCURRENCE");
            if (stateStore.taskContinuity.find(request.taskId(),
                    request.segmentOrdinal() + 1).isPresent())
                failures.add("DUPLICATE_SEGMENT_OR_LOGICAL_OCCURRENCE");
        } catch (SQLException | IOException | RuntimeException error) {
            failures.add("CONTINUITY_EVIDENCE_READ_FAILED");
        }

        if (!evidence.complete()) failures.add("TASK_BUDGET_EVIDENCE_INCOMPLETE");
        if (evidence.complete()) {
            try {
                int segments = stateStore.taskContinuity.count(request.taskId());
                if (segments - 1 >= policy.maxAdditionalSegments())
                    failures.add("MAX_AUTONOMOUS_SEGMENTS_EXHAUSTED");
            } catch (SQLException error) {
                failures.add("SEGMENT_ACCOUNTING_UNAVAILABLE");
            }
            if (request.reason() == SegmentTerminalReason.STRUCTURED_PLAN_REPAIR_FAILED
                    && request.planningAttemptOrdinal() >= policy.maxPlanAttempts())
                failures.add("MAX_PLAN_ATTEMPTS_EXHAUSTED");
            if ((long) evidence.providerTurns() + 1 > policy.maxTotalProviderTurns())
                failures.add("MAX_PROVIDER_TURNS_EXHAUSTED");
            if (evidence.toolCalls() >= policy.maxTotalToolCalls())
                failures.add("MAX_TOOL_CALLS_EXHAUSTED");
            if (evidence.inputTokens() >= policy.maxTotalInputTokens())
                failures.add("MAX_INPUT_TOKENS_EXHAUSTED");
            if (evidence.outputTokens() >= policy.maxTotalOutputTokens())
                failures.add("MAX_OUTPUT_TOKENS_EXHAUSTED");
            if (evidence.activeElapsedMillis() >= policy.maxActiveElapsedMillis())
                failures.add("MAX_ACTIVE_ELAPSED_EXHAUSTED");
        }
        return List.copyOf(failures);
    }

    private void finishCurrent(Request request, String state, BudgetEvidence evidence,
            String terminalReason)
            throws SQLException {
        request.guard().finishContinuitySegment(request.segmentOrdinal(),state,
                request.endIteration(),evidence.providerTurns(),evidence.toolCalls(),
                evidence.inputTokens(),evidence.outputTokens(),evidence.activeElapsedMillis(),
                terminalReason);
    }

    private Decision terminal(Request request, String code, BudgetEvidence evidence,
            List<String> failures) {
        return new Decision(ContinuationDecision.TERMINAL_FAILURE, code,
                request.segmentOrdinal(), null, request.planningAttemptOrdinal(),
                directive(request.reason()), failures, evidence);
    }

    private static ContinuationDecision classification(SegmentTerminalReason reason) {
        return switch (reason) {
            case STRUCTURED_PLAN_REPAIR_FAILED,
                    EFFECTFUL_BATCH_SERIALIZATION_REPEATED,
                    INVALID_TOOL_CALL_RESPONSE,
                    TOOL_PREFLIGHT_MODEL_CORRECTABLE,
                    ITERATION_EXHAUSTED,
                    VERIFICATION_REPAIR_EXHAUSTED -> ContinuationDecision.START_NEXT_SEGMENT;
            case TOOL_PREFLIGHT_APPROVAL_REQUIRED -> ContinuationDecision.WAIT_FOR_USER;
            case TOOL_PREFLIGHT_POLICY_DENIED, TOOL_PREFLIGHT_INTEGRITY_FAILURE,
                    UNKNOWN_EFFECT, OTHER_TYPED_TERMINAL -> ContinuationDecision.TERMINAL_FAILURE;
        };
    }

    private static RecoveryDirective directive(SegmentTerminalReason reason) {
        return switch (reason) {
            case STRUCTURED_PLAN_REPAIR_FAILED ->
                    RecoveryDirective.REPLAN_WITH_REQUIRED_ADVISORY_FIELDS;
            case EFFECTFUL_BATCH_SERIALIZATION_REPEATED ->
                    RecoveryDirective.ONE_EFFECTFUL_TOOL_CALL_PER_TURN;
            case INVALID_TOOL_CALL_RESPONSE, TOOL_PREFLIGHT_MODEL_CORRECTABLE ->
                    RecoveryDirective.CORRECT_INVALID_TOOL_RESPONSE;
            case ITERATION_EXHAUSTED -> RecoveryDirective.CONTINUE_FROM_DURABLE_CHECKPOINT;
            case VERIFICATION_REPAIR_EXHAUSTED ->
                    RecoveryDirective.ADDRESS_VERIFICATION_EVIDENCE;
            default -> RecoveryDirective.NONE;
        };
    }

    private static String terminalCode(SegmentTerminalReason reason) {
        return reason == SegmentTerminalReason.UNKNOWN_EFFECT
                ? "NO_BLIND_REPLAY" : reason.name();
    }

    private static void requireBoundAuthority(String taskId, String runId,
            FencedExecutionGuard guard, String boundary) throws SQLException {
        if (guard == null || !guard.current().taskId().value().equals(taskId)
                || !guard.current().runId().value().equals(runId))
            throw new SQLException("CONTINUITY_EXECUTION_AUTHORITY_MISSING");
        guard.validate(boundary);
    }

    public record Request(String taskId, String runId, int segmentOrdinal,
            int planningAttemptOrdinal, int endIteration, SegmentTerminalReason reason,
            FencedExecutionGuard guard) {
        public Request {
            Objects.requireNonNull(taskId); Objects.requireNonNull(runId);
            Objects.requireNonNull(reason);
            if (segmentOrdinal < 1 || planningAttemptOrdinal < 1 || endIteration < 0)
                throw new IllegalArgumentException("Valid continuity request identity is required");
        }
    }
}
