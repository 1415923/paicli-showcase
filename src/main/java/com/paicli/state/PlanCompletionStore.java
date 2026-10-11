package com.paicli.state;

import com.paicli.identity.CanonicalId;
import com.paicli.state.PlanCompletionModels.CompletionResult;
import com.paicli.state.PlanCompletionModels.Evidence;
import com.paicli.state.PlanCompletionModels.EvidenceKind;
import com.paicli.state.PlanCompletionModels.ProducerKind;
import com.paicli.state.PlanCompletionModels.CompletionRuleType;
import com.paicli.state.R1aDurableModels.ExecutionAuthoritySnapshot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Atomic R1C Evidence, finite-rule evaluation and Task completion authority. */
public final class PlanCompletionStore {
    public enum Status {
        SUCCESS, AUTHORITY_NOT_FOUND, OWNER_MISMATCH, RUN_MISMATCH, LEASE_TOKEN_MISMATCH,
        FENCING_REJECTED, VERSION_CONFLICT, LEASE_EXPIRED, BINDING_NOT_FOUND,
        RULES_UNAVAILABLE, EVIDENCE_INTEGRITY_FAILURE, TARGET_NOT_FOUND,
        COMPLETION_INTEGRITY_FAILURE, INVALID_STATE, INTEGRITY_VIOLATION
    }

    public record GateResult(Status status, CompletionResult result, long evaluationNumber,
            String boundedReason) {
        public GateResult { Objects.requireNonNull(status); }
        static GateResult rejected(Status status) { return new GateResult(status, null, 0, null); }
    }

    public record RecoveryRead(Status status, List<Evidence> evidence,
            int ruleCount, int evaluationCount, boolean completed, CompletionResult result) {
        public RecoveryRead {
            Objects.requireNonNull(status);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    public enum CompletedAuthorityStatus {
        VALID_COMPLETE, NOT_COMPLETE, INTEGRITY_FAILURE, SCOPE_MISMATCH,
        LEGACY_COMPATIBLE_COMPLETE
    }

    /** Read-only, exact R1C completion authority consumed by R1E Follow-up. */
    public record CompletedAuthorityRead(CompletedAuthorityStatus status,
            CanonicalId taskId, CanonicalId runId, CanonicalId revisionId,
            String decisionHash, long evaluationNumber, String rulesHash,
            List<String> verifiedEvidenceHashes) {
        public CompletedAuthorityRead {
            Objects.requireNonNull(status);
            verifiedEvidenceHashes = verifiedEvidenceHashes == null
                    ? List.of() : List.copyOf(verifiedEvidenceHashes);
        }
        public boolean validComplete() { return status == CompletedAuthorityStatus.VALID_COMPLETE; }
    }

    private final Connection connection;

    public PlanCompletionStore(Connection connection) {
        this.connection = Objects.requireNonNull(connection);
    }

    public PlanEvidenceSemanticEvaluator.Evaluation evaluateToolEvidence(CanonicalId taskId,
            CanonicalId revisionId,CanonicalId runId){
        return new PlanEvidenceSemanticEvaluator(connection).evaluate(taskId,revisionId,runId);
    }

    boolean semanticProducerIntegrityValid(Evidence evidence){
        try{return evidence.integrityValid()&&producerIntegrityValid(evidence);}
        catch(SQLException error){throw new IllegalStateException(
                "Semantic Evidence producer validation failed",error);}
    }

    static void initializeRules(Connection connection, CanonicalId taskId,
            CanonicalId revisionId, CanonicalId runId, Instant at) throws SQLException {
        if (exists(connection, """
                SELECT 1 FROM plan_completion_rules WHERE task_id=? AND plan_revision_id=?
                """, taskId.value(), revisionId.value())) {
            throw new SQLException("Completion Rules are already defined");
        }
        try (PreparedStatement steps = connection.prepareStatement("""
                SELECT step_revision_id,step_order,completion_rule,required_evidence_kinds
                FROM plan_step_definitions
                WHERE task_id=? AND plan_revision_id=? ORDER BY step_order
                """)) {
            bind(steps, taskId.value(), revisionId.value());
            try (ResultSet rows = steps.executeQuery()) {
                int count = 0;
                while (rows.next()) {
                    String step = rows.getString(1);
                    int order = rows.getInt(2);
                    CompletionRuleType rule = CompletionRuleType.valueOf(rows.getString(3));
                    List<EvidenceKind> kinds = parseEvidenceKinds(rows.getString(4));
                    String material = ruleMaterial(taskId, revisionId, order, rule, step, kinds);
                    insert(connection, """
                            INSERT INTO plan_completion_rules(task_id,plan_revision_id,rule_order,
                                rule_type,canonical_hash,provenance,created_by_run_id,created_at)
                            VALUES(?,?,?,?,?,'R1C_APPROVED_STEP_DEFINITION',?,?)
                            """, taskId.value(), revisionId.value(), order,
                            rule.name(), PlanCompletionModels.sha256(material),
                            runId.value(), at.toString());
                    insert(connection, """
                            INSERT INTO plan_completion_requirements(task_id,plan_revision_id,
                                rule_order,requirement_order,requirement_type,step_revision_id,
                                required_evidence_kind)
                            VALUES(?,?,?,0,'STEP_SUCCEEDED',?,NULL)
                            """, taskId.value(), revisionId.value(), order, step);
                    int requirementOrder = 1;
                    for (EvidenceKind kind : kinds) {
                        String requirementType = rule == CompletionRuleType.VERIFICATION_PASSED
                                ? "VERIFICATION_PASSED" : "EVIDENCE_KIND_PRESENT";
                        insert(connection, """
                                INSERT INTO plan_completion_requirements(task_id,plan_revision_id,
                                    rule_order,requirement_order,requirement_type,step_revision_id,
                                    required_evidence_kind)
                                VALUES(?,?,?,?,?,?,?)
                                """, taskId.value(), revisionId.value(), order, requirementOrder++,
                                requirementType, step,
                                "VERIFICATION_PASSED".equals(requirementType) ? null : kind.name());
                    }
                    count++;
                }
                if (count == 0) throw new SQLException("Completion Rules require Plan Steps");
            }
        }
    }

    static void recordOccurrenceEvidence(Connection connection, ExecutionAuthoritySnapshot authority,
            Instant at, CanonicalId occurrenceId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT operation_type,target,result_hash FROM side_effect_occurrences
                WHERE occurrence_id=? AND task_id=? AND run_id=? AND status='COMPLETED'
                """)) {
            bind(statement, occurrenceId.value(), authority.taskId().value(), authority.runId().value());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next() || !"TOOL".equals(row.getString(1))) return;
                PlanRequirementStore.OccurrenceSlotBinding binding=
                        occurrenceBinding(connection,authority,occurrenceId);
                if(binding==null){
                    if(exists(connection,"""
                            SELECT 1 FROM plan_v3_revisions v3
                            JOIN plan_run_bindings run ON run.task_id=v3.task_id
                              AND run.plan_revision_id=v3.plan_revision_id
                            WHERE run.task_id=? AND run.run_id=?
                            """,authority.taskId().value(),authority.runId().value()))
                        throw new SQLException("Successful V3 Tool occurrence has no authority slot");
                    return;
                }
                AttemptScope scope = new AttemptScope(binding.taskId(),binding.planRevisionId(),
                        binding.stepRevisionId(),binding.runId(),binding.attemptNumber());
                insertEvidence(connection, scope, EvidenceKind.TOOL_CALL, ProducerKind.OCCURRENCE,
                        occurrenceId.value(), "SUCCEEDED", bounded("tool=" + row.getString(2), 1024),
                        null, "V28_BOUND_TOOL_OCCURRENCE", at);
                PlanRequirementStore.bindEvidence(connection,
                        new PlanRequirementStore.EvidenceSlotBinding(binding.taskId(),
                                binding.planRevisionId(),binding.stepRevisionId(),
                                binding.requirementId(),binding.slotOrdinal(),binding.runId(),
                                binding.attemptNumber(),binding.occurrenceId(),binding.toolCallId(),
                                "SUCCEEDED",binding.selectorDigest(),at));
            }
        }
    }

    private static PlanRequirementStore.OccurrenceSlotBinding occurrenceBinding(
            Connection connection,ExecutionAuthoritySnapshot authority,CanonicalId occurrenceId)
            throws SQLException{
        try(PreparedStatement statement=connection.prepareStatement("""
                SELECT plan_revision_id,step_revision_id,requirement_id,slot_ordinal,
                    run_id,attempt_number,tool_call_id,selector_digest,bound_at
                FROM plan_occurrence_slot_bindings
                WHERE task_id=? AND occurrence_id=? AND run_id=?
                """)){
            bind(statement,authority.taskId().value(),occurrenceId.value(),
                    authority.runId().value());
            try(ResultSet row=statement.executeQuery()){
                if(!row.next())return null;
                var result=new PlanRequirementStore.OccurrenceSlotBinding(authority.taskId(),
                        CanonicalId.parse(row.getString(1)),CanonicalId.parse(row.getString(2)),
                        row.getString(3),row.getInt(4),CanonicalId.parse(row.getString(5)),
                        row.getInt(6),occurrenceId,row.getString(7),"COMPLETED",
                        row.getString(8),Instant.parse(row.getString(9)));
                if(row.next())throw new SQLException("Occurrence has multiple authority slots");
                return result;
            }
        }
    }

    static void recordSnapshotEvidence(Connection connection, ExecutionAuthoritySnapshot authority,
            Instant at, String snapshotId) throws SQLException {
        if (!exists(connection, """
                SELECT 1 FROM file_snapshots WHERE snapshot_id=? AND task_id=?
                    AND owner_run_id=? AND status='APPLIED'
                """, snapshotId, authority.taskId().value(), authority.runId().value())) return;
        AttemptScope scope = attemptScope(connection, authority, true);
        if (scope == null) return;
        insertEvidence(connection, scope, EvidenceKind.SNAPSHOT, ProducerKind.SNAPSHOT,
                snapshotId, "SUCCEEDED", "snapshot applied", snapshotId,
                "R1C_FILE_SNAPSHOT", at);
    }

    static void recordVerificationEvidence(Connection connection,
            ExecutionAuthoritySnapshot authority, Instant at, String verificationId)
            throws SQLException {
        if (!exists(connection, """
                SELECT 1 FROM verification_runs WHERE verification_id=? AND task_id=? AND run_id=?
                    AND status='PASSED'
                    AND candidate_digest_kind='WORKSPACE_CONTENT_SHA256_V1'
                    AND length(candidate_digest)=64
                    AND candidate_digest NOT GLOB '*[^0-9a-f]*'
                """, verificationId, authority.taskId().value(), authority.runId().value())) return;
        CanonicalId revisionId = verificationEvidenceRevision(connection, authority);
        if (revisionId == null) return;
        if (new PlanAuthorityStore(connection)
                .readRevisionDefinition(authority.taskId(), revisionId).status()
                != PlanAuthorityStore.Status.SUCCESS) {
            throw new SQLException("Verification Evidence Plan authority is invalid");
        }
        PlanCompletionStore store = new PlanCompletionStore(connection);
        List<RuleRequirement> requirements = store.readRequirements(
                authority.taskId(), revisionId);
        if (requirements.isEmpty()
                || !store.rulesIntegrityValid(authority.taskId(), revisionId, requirements)) {
            throw new SQLException("Verification Evidence Rule authority is invalid");
        }
        List<AttemptScope> scopes = verificationScopes(connection, authority);
        if (scopes.isEmpty()) {
            throw new SQLException("Passed Verification has no declared Plan Attempt scope");
        }
        for (AttemptScope scope : scopes) {
            insertEvidence(connection, scope, EvidenceKind.VERIFICATION_RESULT,
                    ProducerKind.VERIFICATION, verificationId, "SUCCEEDED", "verification passed",
                    null, "R1C_VERIFICATION_RESULT", at);
            insert(connection, """
                    INSERT OR IGNORE INTO plan_verification_evidence(
                        task_id,plan_revision_id,step_revision_id,
                        attempt_run_id,attempt_number,verification_id,verification_run_id,
                        evidence_kind,producer_kind,verification_status,associated_at)
                    VALUES(?,?,?,?,?,?,?,'VERIFICATION_RESULT','VERIFICATION','PASSED',?)
                    """, scope.taskId().value(), scope.revisionId().value(), scope.stepId().value(),
                    scope.runId().value(), scope.attemptNumber(), verificationId,
                    authority.runId().value(), at.toString());
        }
    }

    public GateResult evaluateAndComplete(ExecutionAuthoritySnapshot authority, Instant at,
            CanonicalId revisionId) {
        return evaluate(authority,at,revisionId,true);
    }

    /** Evaluates immutable Completion Rules without creating a completion decision. */
    public GateResult evaluate(ExecutionAuthoritySnapshot authority,Instant at,
            CanonicalId revisionId){
        return evaluate(authority,at,revisionId,false);
    }

    private GateResult evaluate(ExecutionAuthoritySnapshot authority, Instant at,
            CanonicalId revisionId,boolean terminalize) {
        try {
            if (!connection.getAutoCommit()) throw new SQLException("Nested completion transaction");
            connection.setAutoCommit(false);
            try {
                Status locked = lockAuthority(authority, at);
                if (locked != Status.SUCCESS) {
                    connection.rollback(); return GateResult.rejected(locked);
                }
                if (exists(connection,"""
                        SELECT 1 FROM steering_queue_authority
                        WHERE task_id=? AND state IN ('NEEDS_CONFIRMATION','QUEUED','DEFERRED','APPLYING')""",
                        authority.taskId().value())) {
                    connection.commit();
                    return new GateResult(Status.SUCCESS, CompletionResult.INCOMPLETE, 0,
                            "STEERING_PENDING_OR_APPLICATION_UNRESOLVED");
                }
                if (!exists(connection, """
                        SELECT 1 FROM plan_run_bindings b
                        JOIN task_plan_authorities a ON a.task_id=b.task_id
                            AND a.active_plan_revision_id=b.plan_revision_id
                            AND a.active_approval_id=b.approval_id
                        JOIN plan_revisions p ON p.task_id=b.task_id
                            AND p.plan_revision_id=b.plan_revision_id
                            AND p.canonical_hash=b.plan_hash
                        JOIN plan_revision_lifecycle l ON l.task_id=b.task_id
                            AND l.plan_revision_id=b.plan_revision_id AND l.state='APPROVED'
                        JOIN plan_approvals approval ON approval.task_id=b.task_id
                            AND approval.plan_revision_id=b.plan_revision_id
                            AND approval.approval_id=b.approval_id
                            AND approval.decision='APPROVED'
                        WHERE b.task_id=? AND b.run_id=? AND b.plan_revision_id=?
                            AND b.approval_decision='APPROVED'
                        """, authority.taskId().value(), authority.runId().value(),
                        revisionId.value())) {
                    connection.rollback(); return GateResult.rejected(Status.BINDING_NOT_FOUND);
                }
                if (new PlanAuthorityStore(connection)
                        .readRevisionDefinition(authority.taskId(), revisionId).status()
                        != PlanAuthorityStore.Status.SUCCESS) {
                    connection.rollback();
                    return GateResult.rejected(Status.COMPLETION_INTEGRITY_FAILURE);
                }
                int canonicalVersion = canonicalVersion(authority.taskId(), revisionId);
                if (canonicalVersion == StructuredPlanModels.Revision.CANONICAL_SCHEMA_V1) {
                    if (!legacyCompletionIntegrityValid(authority.taskId(), revisionId)) {
                        connection.rollback();
                        return GateResult.rejected(Status.COMPLETION_INTEGRITY_FAILURE);
                    }
                    long evaluation = nextEvaluation(authority.taskId(), revisionId, authority.runId());
                    String rulesHash = legacyRulesHash(authority.taskId(), revisionId);
                    CompletionResult result = CompletionResult.LEGACY_COMPLETION_PROFILE_REQUIRED;
                    insert(connection, """
                            INSERT INTO plan_completion_evaluations(task_id,plan_revision_id,run_id,
                                evaluation_number,result,rules_hash,bounded_reason,evaluated_at,provenance)
                            VALUES(?,?,?,?,?,?,?,?,'R1C_V21_COMPATIBILITY_GATE')
                            """, authority.taskId().value(), revisionId.value(),
                            authority.runId().value(), evaluation, result.name(), rulesHash,
                            "V1 Revision requires explicit approval of a V2 completion profile",
                            at.toString());
                    connection.commit();
                    return new GateResult(Status.SUCCESS, result, evaluation,
                            "V1 Revision requires explicit approval of a V2 completion profile");
                }
                PlanEvidenceSemanticEvaluator.Evaluation semantic=canonicalVersion
                        ==StructuredPlanModels.Revision.CANONICAL_SCHEMA_V3
                        ?new PlanEvidenceSemanticEvaluator(connection).evaluate(
                                authority.taskId(),revisionId,authority.runId()):null;
                if(semantic!=null&&!semantic.integrityValid()){
                    connection.rollback();
                    return GateResult.rejected(Status.EVIDENCE_INTEGRITY_FAILURE);
                }
                List<RuleRequirement> requirements = readRequirements(authority.taskId(), revisionId);
                if (requirements.isEmpty()) {
                    connection.rollback(); return GateResult.rejected(Status.RULES_UNAVAILABLE);
                }
                if (!rulesIntegrityValid(authority.taskId(), revisionId, requirements)) {
                    connection.rollback();
                    return GateResult.rejected(Status.COMPLETION_INTEGRITY_FAILURE);
                }
                for (Evidence evidence : readEvidence(
                        authority.taskId(), revisionId, null, null)) {
                    if (!evidence.integrityValid() || !producerIntegrityValid(evidence)) {
                        connection.rollback();
                        return GateResult.rejected(Status.EVIDENCE_INTEGRITY_FAILURE);
                    }
                }
                if (passedVerificationAssociationMissing(authority.taskId(), revisionId)) {
                    connection.rollback();
                    return GateResult.rejected(Status.EVIDENCE_INTEGRITY_FAILURE);
                }
                CompletionResult result = CompletionResult.COMPLETE;
                String reason = null;
                List<Evidence> used = new ArrayList<>();
                List<StepFact> stepFacts = new ArrayList<>();
                Set<Integer> evaluatedAnyRules = new HashSet<>();
                if(semantic!=null&&!semantic.satisfied()){
                    result=CompletionResult.EVIDENCE_MISSING;
                    reason="required Tool authority slots are not semantically satisfied ("
                            +semantic.satisfiedSuccesses()+"/"+semantic.requiredSuccesses()+")";
                }
                for (RuleRequirement requirement : result==CompletionResult.COMPLETE
                        ?requirements:List.<RuleRequirement>of()) {
                    if ("STEP_SUCCEEDED".equals(requirement.type())) {
                        StepFact fact = readStepFact(authority.taskId(), revisionId,
                                requirement.stepId());
                        if (fact == null) {
                            result = CompletionResult.FAILED_REQUIREMENT;
                            reason = "required Step state is unavailable"; break;
                        }
                        stepFacts.add(fact);
                        if (!"SUCCEEDED".equals(fact.state())) {
                            result = CompletionResult.FAILED_REQUIREMENT;
                            reason = "required Step is not SUCCEEDED"; break;
                        }
                    } else if ("EVIDENCE_KIND_PRESENT".equals(requirement.type())) {
                        if ("ANY_REQUIRED_EVIDENCE".equals(requirement.ruleType())) {
                            if (!evaluatedAnyRules.add(requirement.ruleOrder())) continue;
                            Evidence selected = null;
                            for (RuleRequirement candidate : requirements) {
                                if (candidate.ruleOrder() != requirement.ruleOrder()
                                        || !"EVIDENCE_KIND_PRESENT".equals(candidate.type())) continue;
                                List<Evidence> matches = readEvidence(authority.taskId(), revisionId,
                                        candidate.stepId(), candidate.evidenceKind());
                                for (Evidence evidence : matches) {
                                    if (!evidence.integrityValid()
                                            || !producerIntegrityValid(evidence)) {
                                        result = CompletionResult.EVIDENCE_INTEGRITY_FAILURE;
                                        reason = "Evidence integrity validation failed";
                                        break;
                                    }
                                    if (selected == null && completionScopeValid(evidence)) {
                                        selected = evidence;
                                    }
                                }
                                if (result != CompletionResult.COMPLETE) break;
                                if (selected != null) break;
                            }
                            if (result != CompletionResult.COMPLETE) break;
                            if (selected == null) {
                                result = CompletionResult.EVIDENCE_MISSING;
                                reason = "none of the permitted Evidence kinds is present"; break;
                            }
                            used.add(selected);
                            continue;
                        }
                        List<Evidence> matches = readEvidence(authority.taskId(), revisionId,
                                requirement.stepId(), requirement.evidenceKind());
                        if (matches.isEmpty()) {
                            result = CompletionResult.EVIDENCE_MISSING;
                            reason = "required Evidence is missing"; break;
                        }
                        for (Evidence evidence : matches) {
                            if (!evidence.integrityValid() || !producerIntegrityValid(evidence)) {
                                result = CompletionResult.EVIDENCE_INTEGRITY_FAILURE;
                                reason = "Evidence integrity validation failed"; break;
                            }
                        }
                        if (result != CompletionResult.COMPLETE) break;
                        Evidence eligible = null;
                        for (Evidence evidence : matches) {
                            if (completionScopeValid(evidence)) {
                                eligible = evidence;
                                break;
                            }
                        }
                        if (eligible == null) {
                            result = CompletionResult.EVIDENCE_MISSING;
                            reason = "required Evidence has no SUCCEEDED Attempt scope";
                            break;
                        }
                        used.add(eligible);
                    } else if ("VERIFICATION_PASSED".equals(requirement.type())) {
                        List<Evidence> matches = readVerificationEvidence(authority.taskId(),
                                revisionId, requirement.stepId());
                        if (matches.isEmpty()) {
                            result = CompletionResult.VERIFICATION_REQUIRED;
                            reason = "passed Verification Evidence is required"; break;
                        }
                        for (Evidence evidence : matches) {
                            if (!evidence.integrityValid()
                                    || !producerIntegrityValid(evidence)) {
                                result = CompletionResult.EVIDENCE_INTEGRITY_FAILURE;
                                reason = "Verification Evidence integrity validation failed";
                                break;
                            }
                        }
                        if (result != CompletionResult.COMPLETE) break;
                        Evidence eligible = null;
                        for (Evidence evidence : matches) {
                            if (completionScopeValid(evidence)) {
                                eligible = evidence;
                                break;
                            }
                        }
                        if (eligible == null) {
                            result = CompletionResult.VERIFICATION_REQUIRED;
                            reason = "passed Verification has no SUCCEEDED Attempt scope";
                            break;
                        }
                        used.add(eligible);
                    }
                }
                long evaluation = nextEvaluation(authority.taskId(), revisionId, authority.runId());
                String rulesHash = rulesHash(authority.taskId(), revisionId);
                insert(connection, """
                        INSERT INTO plan_completion_evaluations(task_id,plan_revision_id,run_id,
                            evaluation_number,result,rules_hash,bounded_reason,evaluated_at,provenance)
                        VALUES(?,?,?,?,?,?,?,?, 'R1C_COMPLETION_EVALUATOR')
                        """, authority.taskId().value(), revisionId.value(), authority.runId().value(),
                        evaluation, result.name(), rulesHash, bounded(reason, 512), at.toString());
                for (Evidence evidence : used) insertEvaluationEvidence(authority, revisionId,
                        evaluation, evidence);
                for (StepFact fact : stepFacts) insertEvaluationStep(authority, revisionId,
                        evaluation, fact);
                if (result == CompletionResult.COMPLETE && terminalize) {
                    String decisionHash = PlanCompletionModels.sha256(authority.taskId().value()
                            + "\n" + revisionId.value() + "\n" + authority.runId().value()
                            + "\n" + evaluation + "\n" + rulesHash);
                    insert(connection, """
                            INSERT INTO task_completion_decisions(task_id,plan_revision_id,run_id,
                                evaluation_number,result,decision_hash,decided_at,provenance)
                            VALUES(?,?,?,?,'COMPLETE',?,?,'R1C_TASK_COMPLETION_GATE')
                            """, authority.taskId().value(), revisionId.value(),
                            authority.runId().value(), evaluation, decisionHash, at.toString());
                    requireOne(update(connection, """
                            UPDATE coding_runs SET phase='COMPLETED',last_error=NULL,
                                updated_at=?,completed_at=? WHERE task_id=? AND run_id=?
                                AND phase NOT IN ('COMPLETED','FAILED')
                            """, at.toString(), at.toString(), authority.taskId().value(),
                            authority.runId().value()), "coding Run completion");
                    requireOne(update(connection, """
                            UPDATE tasks SET status='DONE',last_error=NULL,updated_at=?,completed_at=?
                            WHERE task_id=? AND status='RUNNING'
                            """, at.toString(), at.toString(), authority.taskId().value()),
                            "runtime Task completion");
                    requireOne(update(connection, """
                            UPDATE task_identities SET status='COMPLETED',terminal_at=? WHERE task_id=?
                                AND status IN ('CREATED','RUNNING','INTERRUPTED','UNKNOWN')
                            """, at.toString(),authority.taskId().value()), "durable Task completion");
                    requireOne(update(connection, """
                            UPDATE durable_runs SET terminal_status='COMPLETED'
                            WHERE task_id=? AND run_id=? AND terminal_status IS NULL
                            """, authority.taskId().value(), authority.runId().value()),
                            "durable Run completion");
                }
                connection.commit();
                return new GateResult(Status.SUCCESS, result, evaluation, bounded(reason, 512));
            } catch (SQLException | RuntimeException error) {
                connection.rollback(); throw error;
            } finally { connection.setAutoCommit(true); }
        } catch (SQLException error) {
            throw new IllegalStateException("Plan completion persistence failed", error);
        }
    }

    public RecoveryRead readRecovery(CanonicalId taskId, CanonicalId revisionId) {
        try {
            if (new PlanAuthorityStore(connection)
                    .readRevisionDefinition(taskId, revisionId).status()
                    != PlanAuthorityStore.Status.SUCCESS) {
                return new RecoveryRead(Status.COMPLETION_INTEGRITY_FAILURE,
                        List.of(), 0, 0, false, null);
            }
            List<Evidence> evidence = readEvidence(taskId, revisionId, null, null);
            for (Evidence fact : evidence) if (!fact.integrityValid()
                    || !producerIntegrityValid(fact))
                return new RecoveryRead(Status.EVIDENCE_INTEGRITY_FAILURE,List.of(),0,0,false,
                        CompletionResult.EVIDENCE_INTEGRITY_FAILURE);
            if (canonicalVersion(taskId, revisionId)
                    == StructuredPlanModels.Revision.CANONICAL_SCHEMA_V1) {
                if (!legacyCompletionIntegrityValid(taskId, revisionId)) {
                    return new RecoveryRead(Status.COMPLETION_INTEGRITY_FAILURE,
                            List.of(), 0, 0, false, null);
                }
                int evaluations = count("""
                        SELECT count(*) FROM plan_completion_evaluations
                        WHERE task_id=? AND plan_revision_id=?
                        """, taskId.value(), revisionId.value());
                return new RecoveryRead(Status.SUCCESS, evidence, 0, evaluations, false,
                        CompletionResult.LEGACY_COMPLETION_PROFILE_REQUIRED);
            }
            int rules = count("SELECT count(*) FROM plan_completion_rules WHERE task_id=? AND plan_revision_id=?",
                    taskId.value(), revisionId.value());
            List<RuleRequirement> requirements = readRequirements(taskId, revisionId);
            if (rules == 0 || requirements.isEmpty())
                return new RecoveryRead(Status.RULES_UNAVAILABLE,evidence,0,0,false,null);
            if (passedVerificationAssociationMissing(taskId, revisionId))
                return new RecoveryRead(Status.EVIDENCE_INTEGRITY_FAILURE,List.of(),rules,0,false,
                        CompletionResult.EVIDENCE_INTEGRITY_FAILURE);
            if (!rulesIntegrityValid(taskId, revisionId, requirements)
                    || !completionEvaluationInputsIntegrityValid(taskId, revisionId, requirements)
                    || !completionDecisionsIntegrityValid(taskId, revisionId))
                return new RecoveryRead(Status.COMPLETION_INTEGRITY_FAILURE,List.of(),rules,0,false,
                        null);
            int evaluations = count("SELECT count(*) FROM plan_completion_evaluations WHERE task_id=? AND plan_revision_id=?",
                    taskId.value(), revisionId.value());
            boolean completed = exists(connection,
                    "SELECT 1 FROM task_completion_decisions WHERE task_id=? AND plan_revision_id=?",
                    taskId.value(), revisionId.value());
            return new RecoveryRead(Status.SUCCESS, evidence, rules, evaluations, completed,
                    completed ? CompletionResult.COMPLETE : null);
        } catch (SQLException error) {
            throw new IllegalStateException("Plan completion Recovery read failed", error);
        }
    }

    public CompletedAuthorityRead validateCompletedAuthority(CanonicalId taskId,
            CanonicalId expectedRunId, CanonicalId expectedRevisionId,
            String expectedDecisionHash) {
        Objects.requireNonNull(taskId); Objects.requireNonNull(expectedRunId);
        Objects.requireNonNull(expectedRevisionId); Objects.requireNonNull(expectedDecisionHash);
        RecoveryRead recovery = readRecovery(taskId, expectedRevisionId);
        if (recovery.status() != Status.SUCCESS) {
            return completedAuthority(CompletedAuthorityStatus.INTEGRITY_FAILURE,
                    taskId, expectedRunId, expectedRevisionId, expectedDecisionHash);
        }
        if (recovery.result() == CompletionResult.LEGACY_COMPLETION_PROFILE_REQUIRED) {
            return completedAuthority(CompletedAuthorityStatus.LEGACY_COMPATIBLE_COMPLETE,
                    taskId, expectedRunId, expectedRevisionId, expectedDecisionHash);
        }
        if (!recovery.completed() || recovery.result() != CompletionResult.COMPLETE) {
            return completedAuthority(CompletedAuthorityStatus.NOT_COMPLETE,
                    taskId, expectedRunId, expectedRevisionId, expectedDecisionHash);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT d.evaluation_number,e.rules_hash
                FROM task_identities t
                JOIN task_context_pointers p ON p.task_id=t.task_id
                JOIN durable_runs r ON r.task_id=t.task_id AND r.run_id=p.owner_run_id
                JOIN task_plan_authorities a ON a.task_id=t.task_id
                JOIN plan_revision_lifecycle l ON l.task_id=a.task_id
                  AND l.plan_revision_id=a.active_plan_revision_id AND l.state='APPROVED'
                JOIN plan_run_bindings b ON b.task_id=a.task_id
                  AND b.run_id=p.owner_run_id AND b.plan_revision_id=a.active_plan_revision_id
                  AND b.approval_decision='APPROVED'
                JOIN task_completion_decisions d ON d.task_id=a.task_id
                  AND d.run_id=p.owner_run_id AND d.plan_revision_id=a.active_plan_revision_id
                JOIN plan_completion_evaluations e ON e.task_id=d.task_id
                  AND e.plan_revision_id=d.plan_revision_id AND e.run_id=d.run_id
                  AND e.evaluation_number=d.evaluation_number AND e.result='COMPLETE'
                WHERE t.task_id=? AND t.status='COMPLETED'
                  AND p.owner_run_id=? AND r.terminal_status='COMPLETED'
                  AND a.active_plan_revision_id=? AND d.decision_hash=? AND d.result='COMPLETE'
                """)) {
            bind(statement, taskId.value(), expectedRunId.value(),
                    expectedRevisionId.value(), expectedDecisionHash);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    CompletedAuthorityStatus status = exists(connection, """
                            SELECT 1 FROM task_completion_decisions
                            WHERE task_id=? AND plan_revision_id=?
                            """, taskId.value(), expectedRevisionId.value())
                            ? CompletedAuthorityStatus.SCOPE_MISMATCH
                            : CompletedAuthorityStatus.NOT_COMPLETE;
                    return completedAuthority(status, taskId, expectedRunId,
                            expectedRevisionId, expectedDecisionHash);
                }
                long evaluation = row.getLong(1); String rules = row.getString(2);
                List<String> evidenceHashes = evaluationEvidenceHashes(taskId,
                        expectedRevisionId, expectedRunId, evaluation);
                return new CompletedAuthorityRead(CompletedAuthorityStatus.VALID_COMPLETE,
                        taskId, expectedRunId, expectedRevisionId, expectedDecisionHash,
                        evaluation, rules, evidenceHashes);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Completed authority validation failed", error);
        }
    }

    public List<Evidence> readEvidence(CanonicalId taskId, CanonicalId revisionId) {
        try { return readEvidence(taskId, revisionId, null, null); }
        catch (SQLException error) { throw new IllegalStateException("Evidence read failed", error); }
    }

    public boolean needsVerificationEvidence(CanonicalId taskId, CanonicalId revisionId) {
        try {
            if (passedVerificationAssociationMissing(taskId, revisionId)) {
                throw new IllegalStateException(
                        "Passed Verification has missing durable Evidence association");
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT DISTINCT q.step_revision_id
                    FROM plan_completion_rules r
                    JOIN plan_completion_requirements q ON q.task_id=r.task_id
                      AND q.plan_revision_id=r.plan_revision_id AND q.rule_order=r.rule_order
                    WHERE r.task_id=? AND r.plan_revision_id=?
                      AND (q.requirement_type='VERIFICATION_PASSED'
                        OR (q.requirement_type='EVIDENCE_KIND_PRESENT'
                          AND q.required_evidence_kind='VERIFICATION_RESULT'))
                    ORDER BY q.step_revision_id
                    """)) {
                bind(statement, taskId.value(), revisionId.value());
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        if (readVerificationEvidence(taskId, revisionId,
                                rows.getString(1)).isEmpty()) return true;
                    }
                    return false;
                }
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Verification Evidence requirement read failed", error);
        }
    }

    private List<Evidence> readEvidence(CanonicalId taskId, CanonicalId revisionId,
            String stepId, String kind) throws SQLException {
        String sql = """
                SELECT step_revision_id,run_id,attempt_number,evidence_kind,producer_kind,
                    producer_identity,observed_status,bounded_summary,protected_reference,
                    canonical_hash,provenance,created_at FROM plan_evidence
                WHERE task_id=? AND plan_revision_id=?
                """ + (stepId == null ? "" : " AND step_revision_id=?")
                + (kind == null ? "" : " AND evidence_kind=?")
                + " ORDER BY created_at,step_revision_id,attempt_number,producer_identity";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            List<Object> values = new ArrayList<>(); values.add(taskId.value()); values.add(revisionId.value());
            if (stepId != null) values.add(stepId); if (kind != null) values.add(kind);
            bind(statement, values.toArray());
            try (ResultSet rows = statement.executeQuery()) {
                List<Evidence> result = new ArrayList<>();
                while (rows.next()) result.add(new Evidence(taskId, revisionId,
                        CanonicalId.parse(rows.getString(1)), CanonicalId.parse(rows.getString(2)),
                        rows.getInt(3), EvidenceKind.valueOf(rows.getString(4)),
                        ProducerKind.valueOf(rows.getString(5)), rows.getString(6), rows.getString(7),
                        rows.getString(8), rows.getString(9), rows.getString(10), rows.getString(11),
                        Instant.parse(rows.getString(12))));
                return result;
            }
        }
    }

    private List<Evidence> readVerificationEvidence(CanonicalId taskId, CanonicalId revisionId,
            String stepId) throws SQLException {
        List<Evidence> evidence = readEvidence(taskId, revisionId, stepId, "VERIFICATION_RESULT");
        List<Evidence> result = new ArrayList<>();
        for (Evidence item : evidence) if (exists(connection, """
                SELECT 1 FROM plan_verification_evidence WHERE task_id=? AND plan_revision_id=?
                    AND step_revision_id=? AND attempt_run_id=? AND attempt_number=?
                    AND verification_id=? AND verification_status='PASSED'
                """, taskId.value(), revisionId.value(), stepId, item.runId().value(),
                item.attemptNumber(), item.producerIdentity())) result.add(item);
        return result;
    }

    private void insertEvaluationEvidence(ExecutionAuthoritySnapshot authority,
            CanonicalId revisionId, long evaluation, Evidence evidence) throws SQLException {
        insert(connection, """
                INSERT INTO plan_completion_evaluation_evidence(task_id,plan_revision_id,run_id,
                    evaluation_number,step_revision_id,attempt_run_id,attempt_number,evidence_kind,
                    producer_kind,producer_identity) VALUES(?,?,?,?,?,?,?,?,?,?)
                """, authority.taskId().value(), revisionId.value(), authority.runId().value(),
                evaluation, evidence.stepRevisionId().value(), evidence.runId().value(),
                evidence.attemptNumber(), evidence.kind().name(), evidence.producerKind().name(),
                evidence.producerIdentity());
    }

    private void insertEvaluationStep(ExecutionAuthoritySnapshot authority,
            CanonicalId revisionId, long evaluation, StepFact fact) throws SQLException {
        insert(connection, """
                INSERT INTO plan_completion_evaluation_steps(task_id,plan_revision_id,run_id,
                    evaluation_number,step_revision_id,observed_state,transition_version,
                    current_attempt_number) VALUES(?,?,?,?,?,?,?,?)
                """, authority.taskId().value(), revisionId.value(), authority.runId().value(),
                evaluation, fact.stepId(), fact.state(), fact.transitionVersion(),
                fact.currentAttemptNumber());
    }

    private StepFact readStepFact(CanonicalId taskId, CanonicalId revisionId, String stepId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT state,transition_version,current_attempt_number
                FROM plan_step_executions
                WHERE task_id=? AND plan_revision_id=? AND step_revision_id=?
                """)) {
            bind(statement, taskId.value(), revisionId.value(), stepId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? new StepFact(stepId, row.getString(1),
                        row.getLong(2), row.getInt(3)) : null;
            }
        }
    }

    private static void insertEvidence(Connection connection, AttemptScope scope,
            EvidenceKind kind, ProducerKind producerKind, String producerIdentity,
            String observedStatus, String summary, String reference, String provenance,
            Instant at) throws SQLException {
        String hash = PlanCompletionModels.evidenceHash(scope.taskId(), scope.revisionId(),
                scope.stepId(), scope.runId(), scope.attemptNumber(), kind, producerKind,
                producerIdentity, observedStatus, summary, reference, provenance, at);
        insert(connection, """
                INSERT OR IGNORE INTO plan_evidence(
                    task_id,plan_revision_id,step_revision_id,run_id,
                    attempt_number,evidence_kind,producer_kind,producer_identity,observed_status,
                    bounded_summary,protected_reference,canonical_hash,provenance,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, scope.taskId().value(), scope.revisionId().value(), scope.stepId().value(),
                scope.runId().value(), scope.attemptNumber(), kind.name(), producerKind.name(),
                producerIdentity, observedStatus, bounded(summary,1024), bounded(reference,512),
                hash, provenance, at.toString());
    }

    private static AttemptScope attemptScope(Connection connection,
            ExecutionAuthoritySnapshot authority, boolean requireRunning) throws SQLException {
        String statePredicate = requireRunning ? "a.state='RUNNING'" : "a.state='SUCCEEDED'";
        String sql = """
                SELECT a.plan_revision_id,a.step_revision_id,a.run_id,a.attempt_number
                FROM plan_step_attempts a
                JOIN plan_run_bindings b ON b.task_id=a.task_id AND b.run_id=a.run_id
                    AND b.plan_revision_id=a.plan_revision_id
                JOIN plan_step_definitions d ON d.task_id=a.task_id
                    AND d.plan_revision_id=a.plan_revision_id
                    AND d.step_revision_id=a.step_revision_id
                JOIN task_plan_authorities active ON active.task_id=a.task_id
                    AND active.active_plan_revision_id=a.plan_revision_id
                WHERE a.task_id=? AND a.run_id=? AND %s
                ORDER BY d.step_order DESC,a.attempt_number DESC LIMIT 1
                """.formatted(statePredicate);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, authority.taskId().value(), authority.runId().value());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? new AttemptScope(authority.taskId(),
                        CanonicalId.parse(row.getString(1)), CanonicalId.parse(row.getString(2)),
                        CanonicalId.parse(row.getString(3)), row.getInt(4)) : null;
            }
        }
    }

    private static List<AttemptScope> verificationScopes(Connection connection,
            ExecutionAuthoritySnapshot authority) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT a.plan_revision_id,a.step_revision_id,a.run_id,a.attempt_number
                FROM task_plan_authorities active
                JOIN plan_run_bindings current ON current.task_id=active.task_id
                    AND current.plan_revision_id=active.active_plan_revision_id
                    AND current.approval_id=active.active_approval_id
                    AND current.run_id=? AND current.approval_decision='APPROVED'
                JOIN plan_step_definitions d ON d.task_id=active.task_id
                    AND d.plan_revision_id=active.active_plan_revision_id
                JOIN plan_completion_rules r ON r.task_id=d.task_id
                    AND r.plan_revision_id=d.plan_revision_id AND r.rule_order=d.step_order
                JOIN plan_completion_requirements q ON q.task_id=r.task_id
                    AND q.plan_revision_id=r.plan_revision_id AND q.rule_order=r.rule_order
                    AND q.step_revision_id=d.step_revision_id
                    AND (q.requirement_type='VERIFICATION_PASSED'
                      OR (q.requirement_type='EVIDENCE_KIND_PRESENT'
                        AND q.required_evidence_kind='VERIFICATION_RESULT'))
                JOIN plan_step_attempts a ON a.task_id=d.task_id
                    AND a.plan_revision_id=d.plan_revision_id
                    AND a.step_revision_id=d.step_revision_id AND a.state='SUCCEEDED'
                WHERE active.task_id=?
                  AND a.attempt_number=(SELECT max(a2.attempt_number)
                    FROM plan_step_attempts a2 WHERE a2.task_id=a.task_id
                      AND a2.plan_revision_id=a.plan_revision_id
                      AND a2.step_revision_id=a.step_revision_id AND a2.state='SUCCEEDED')
                ORDER BY d.step_order
                """)) {
            bind(statement, authority.runId().value(), authority.taskId().value());
            try (ResultSet rows = statement.executeQuery()) {
                List<AttemptScope> result = new ArrayList<>();
                while (rows.next()) result.add(new AttemptScope(authority.taskId(),
                        CanonicalId.parse(rows.getString(1)), CanonicalId.parse(rows.getString(2)),
                        CanonicalId.parse(rows.getString(3)), rows.getInt(4)));
                return result;
            }
        }
    }

    private static CanonicalId verificationEvidenceRevision(Connection connection,
            ExecutionAuthoritySnapshot authority) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT active.active_plan_revision_id
                FROM task_plan_authorities active
                JOIN plan_run_bindings current ON current.task_id=active.task_id
                    AND current.plan_revision_id=active.active_plan_revision_id
                    AND current.approval_id=active.active_approval_id
                    AND current.run_id=? AND current.approval_decision='APPROVED'
                JOIN plan_completion_rules r ON r.task_id=active.task_id
                    AND r.plan_revision_id=active.active_plan_revision_id
                JOIN plan_completion_requirements q ON q.task_id=r.task_id
                    AND q.plan_revision_id=r.plan_revision_id AND q.rule_order=r.rule_order
                WHERE active.task_id=?
                  AND (q.requirement_type='VERIFICATION_PASSED'
                    OR (q.requirement_type='EVIDENCE_KIND_PRESENT'
                      AND q.required_evidence_kind='VERIFICATION_RESULT'))
                LIMIT 1
                """)) {
            bind(statement, authority.runId().value(), authority.taskId().value());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? CanonicalId.parse(row.getString(1)) : null;
            }
        }
    }

    private List<RuleRequirement> readRequirements(CanonicalId taskId,
            CanonicalId revisionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT r.rule_order,r.rule_type,q.requirement_order,q.requirement_type,
                    q.step_revision_id,q.required_evidence_kind
                FROM plan_completion_rules r JOIN plan_completion_requirements q
                  ON q.task_id=r.task_id AND q.plan_revision_id=r.plan_revision_id
                 AND q.rule_order=r.rule_order
                WHERE r.task_id=? AND r.plan_revision_id=?
                ORDER BY r.rule_order,q.requirement_order
                """)) {
            bind(statement, taskId.value(), revisionId.value());
            try (ResultSet rows = statement.executeQuery()) {
                List<RuleRequirement> result = new ArrayList<>();
                while (rows.next()) result.add(new RuleRequirement(rows.getInt(1), rows.getString(2),
                        rows.getInt(3), rows.getString(4), rows.getString(5), rows.getString(6)));
                return result;
            }
        }
    }

    private String rulesHash(CanonicalId taskId, CanonicalId revisionId) throws SQLException {
        StringBuilder material = new StringBuilder();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT canonical_hash FROM plan_completion_rules
                WHERE task_id=? AND plan_revision_id=? ORDER BY rule_order
                """)) {
            bind(statement, taskId.value(), revisionId.value());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) material.append(rows.getString(1)).append('\n');
            }
        }
        return PlanCompletionModels.sha256(material.toString());
    }

    private boolean rulesIntegrityValid(CanonicalId taskId, CanonicalId revisionId,
            List<RuleRequirement> requirements) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT rule_order,rule_type,canonical_hash FROM plan_completion_rules
                WHERE task_id=? AND plan_revision_id=? ORDER BY rule_order
                """)) {
            bind(statement, taskId.value(), revisionId.value());
            try (ResultSet rows = statement.executeQuery()) {
                int count = 0;
                int expectedRequirementCount = 0;
                while (rows.next()) {
                    int order = rows.getInt(1);
                    CompletionRuleType type = CompletionRuleType.valueOf(rows.getString(2));
                    List<RuleRequirement> owned = requirements.stream()
                            .filter(value -> value.ruleOrder() == order).toList();
                    if (owned.isEmpty() || owned.get(0).requirementOrder() != 0
                            || !"STEP_SUCCEEDED".equals(owned.get(0).type())
                            || owned.get(0).evidenceKind() != null
                            || owned.stream().anyMatch(value -> !Objects.equals(
                                    owned.get(0).stepId(), value.stepId()))) return false;
                    StepProfile profile = readStepProfile(taskId, revisionId, order);
                    if (profile == null || profile.rule() != type
                            || owned.size() != profile.kinds().size() + 1) return false;
                    for (int i = 0; i < profile.kinds().size(); i++) {
                        RuleRequirement requirement = owned.get(i + 1);
                        if (requirement.requirementOrder() != i + 1) return false;
                        String expectedType = type == CompletionRuleType.VERIFICATION_PASSED
                                ? "VERIFICATION_PASSED" : "EVIDENCE_KIND_PRESENT";
                        String expectedKind = type == CompletionRuleType.VERIFICATION_PASSED
                                ? null : profile.kinds().get(i).name();
                        if (!expectedType.equals(requirement.type())
                                || !Objects.equals(expectedKind, requirement.evidenceKind()))
                            return false;
                    }
                    String material = ruleMaterial(taskId, revisionId, order, type,
                            owned.get(0).stepId(), profile.kinds());
                    if (!PlanCompletionModels.sha256(material).equals(rows.getString(3)))
                        return false;
                    expectedRequirementCount += profile.kinds().size() + 1;
                    count++;
                }
                int profileCount = count("""
                        SELECT count(*) FROM plan_step_definitions
                        WHERE task_id=? AND plan_revision_id=?
                        """, taskId.value(), revisionId.value());
                return count > 0 && count == profileCount
                        && requirements.size() == expectedRequirementCount;
            }
        }
    }

    private int canonicalVersion(CanonicalId taskId, CanonicalId revisionId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT CASE WHEN EXISTS (
                    SELECT 1 FROM plan_v3_revisions v3
                    WHERE v3.task_id=plan_revisions.task_id
                      AND v3.plan_revision_id=plan_revisions.plan_revision_id
                      AND v3.canonical_schema_version=3)
                    THEN 3 ELSE canonical_schema_version END
                FROM plan_revisions
                WHERE task_id=? AND plan_revision_id=?
                """)) {
            bind(statement, taskId.value(), revisionId.value());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("Plan Revision is unavailable");
                int version = row.getInt(1);
                if (version != StructuredPlanModels.Revision.CANONICAL_SCHEMA_V1
                        && version != StructuredPlanModels.Revision.CANONICAL_SCHEMA_V2
                        && version != StructuredPlanModels.Revision.CANONICAL_SCHEMA_V3) {
                    throw new SQLException("Unsupported Plan canonical schema version");
                }
                return version;
            }
        }
    }

    private String legacyRulesHash(CanonicalId taskId, CanonicalId revisionId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT canonical_hash FROM plan_revisions
                WHERE task_id=? AND plan_revision_id=? AND canonical_schema_version=1
                """)) {
            bind(statement, taskId.value(), revisionId.value());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("Legacy Plan Revision is unavailable");
                return PlanCompletionModels.sha256(
                        "R1C_V21_COMPATIBILITY_GATE\n" + row.getString(1));
            }
        }
    }

    private boolean legacyCompletionIntegrityValid(CanonicalId taskId,
            CanonicalId revisionId) throws SQLException {
        if (count("""
                SELECT count(*) FROM plan_completion_rules
                WHERE task_id=? AND plan_revision_id=?
                """, taskId.value(), revisionId.value()) != 0) return false;
        if (count("""
                SELECT count(*) FROM task_completion_decisions
                WHERE task_id=? AND plan_revision_id=?
                """, taskId.value(), revisionId.value()) != 0) return false;
        String expectedHash = legacyRulesHash(taskId, revisionId);
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT result,rules_hash FROM plan_completion_evaluations
                WHERE task_id=? AND plan_revision_id=?
                """)) {
            bind(statement, taskId.value(), revisionId.value());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (!CompletionResult.LEGACY_COMPLETION_PROFILE_REQUIRED.name()
                            .equals(rows.getString(1))
                            || !expectedHash.equals(rows.getString(2))) return false;
                }
            }
        }
        return true;
    }

    private boolean passedVerificationAssociationMissing(CanonicalId taskId,
            CanonicalId revisionId) throws SQLException {
        return exists(connection, """
                SELECT 1
                FROM plan_completion_rules r
                JOIN plan_completion_requirements q ON q.task_id=r.task_id
                  AND q.plan_revision_id=r.plan_revision_id AND q.rule_order=r.rule_order
                  AND (q.requirement_type='VERIFICATION_PASSED'
                    OR (q.requirement_type='EVIDENCE_KIND_PRESENT'
                      AND q.required_evidence_kind='VERIFICATION_RESULT'))
                JOIN plan_run_bindings b ON b.task_id=r.task_id
                  AND b.plan_revision_id=r.plan_revision_id
                JOIN verification_runs v ON v.task_id=b.task_id AND v.run_id=b.run_id
                  AND v.status='PASSED'
                  AND v.candidate_digest_kind='WORKSPACE_CONTENT_SHA256_V1'
                  AND length(v.candidate_digest)=64
                  AND v.candidate_digest NOT GLOB '*[^0-9a-f]*'
                JOIN plan_step_attempts a ON a.task_id=q.task_id
                  AND a.plan_revision_id=q.plan_revision_id
                  AND a.step_revision_id=q.step_revision_id AND a.state='SUCCEEDED'
                WHERE r.task_id=? AND r.plan_revision_id=?
                  AND a.attempt_number=(SELECT max(a2.attempt_number)
                    FROM plan_step_attempts a2 WHERE a2.task_id=a.task_id
                      AND a2.plan_revision_id=a.plan_revision_id
                      AND a2.step_revision_id=a.step_revision_id AND a2.state='SUCCEEDED')
                  AND NOT EXISTS (
                    SELECT 1 FROM plan_verification_evidence e
                    WHERE e.task_id=r.task_id AND e.plan_revision_id=r.plan_revision_id
                      AND e.step_revision_id=q.step_revision_id
                      AND e.attempt_run_id=a.run_id
                      AND e.attempt_number=a.attempt_number
                      AND e.verification_id=v.verification_id
                      AND e.verification_status='PASSED')
                LIMIT 1
                """, taskId.value(), revisionId.value());
    }

    private boolean completionDecisionsIntegrityValid(CanonicalId taskId,
            CanonicalId revisionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT d.run_id,d.evaluation_number,d.decision_hash,e.rules_hash
                FROM task_completion_decisions d
                JOIN plan_completion_evaluations e ON e.task_id=d.task_id
                  AND e.plan_revision_id=d.plan_revision_id AND e.run_id=d.run_id
                  AND e.evaluation_number=d.evaluation_number
                WHERE d.task_id=? AND d.plan_revision_id=?
                """)) {
            bind(statement, taskId.value(), revisionId.value());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String material = taskId.value() + "\n" + revisionId.value() + "\n"
                            + rows.getString(1) + "\n" + rows.getLong(2) + "\n"
                            + rows.getString(4);
                    if (!PlanCompletionModels.sha256(material).equals(rows.getString(3)))
                        return false;
                }
                return true;
            }
        }
    }

    private boolean completionEvaluationInputsIntegrityValid(CanonicalId taskId,
            CanonicalId revisionId, List<RuleRequirement> requirements) throws SQLException {
        String currentRulesHash = rulesHash(taskId, revisionId);
        try (PreparedStatement evaluations = connection.prepareStatement("""
                SELECT run_id,evaluation_number,rules_hash FROM plan_completion_evaluations
                WHERE task_id=? AND plan_revision_id=? AND result='COMPLETE'
                ORDER BY run_id,evaluation_number
                """)) {
            bind(evaluations, taskId.value(), revisionId.value());
            try (ResultSet rows = evaluations.executeQuery()) {
                while (rows.next()) {
                    CanonicalId runId = CanonicalId.parse(rows.getString(1));
                    long evaluation = rows.getLong(2);
                    if (!currentRulesHash.equals(rows.getString(3))
                            || !evaluationStepsIntegrityValid(taskId, revisionId,
                                    runId, evaluation, requirements)
                            || !evaluationEvidenceIntegrityValid(taskId, revisionId,
                                    runId, evaluation, requirements)) return false;
                }
            }
        }
        return true;
    }

    private boolean evaluationStepsIntegrityValid(CanonicalId taskId,
            CanonicalId revisionId, CanonicalId runId, long evaluation,
            List<RuleRequirement> requirements) throws SQLException {
        Set<String> expected = new HashSet<>();
        for (RuleRequirement requirement : requirements)
            if ("STEP_SUCCEEDED".equals(requirement.type())) expected.add(requirement.stepId());
        Set<String> observed = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT s.step_revision_id,s.observed_state,s.transition_version,
                       s.current_attempt_number,e.state,e.transition_version,e.current_attempt_number
                FROM plan_completion_evaluation_steps s
                LEFT JOIN plan_step_executions e ON e.task_id=s.task_id
                  AND e.plan_revision_id=s.plan_revision_id
                  AND e.step_revision_id=s.step_revision_id
                WHERE s.task_id=? AND s.plan_revision_id=? AND s.run_id=?
                  AND s.evaluation_number=? ORDER BY s.step_revision_id
                """)) {
            bind(statement, taskId.value(), revisionId.value(), runId.value(), evaluation);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String step = rows.getString(1);
                    if (!observed.add(step) || !expected.contains(step)
                            || !"SUCCEEDED".equals(rows.getString(2))
                            || !Objects.equals(rows.getString(2), rows.getString(5))
                            || rows.getLong(3) != rows.getLong(6)
                            || rows.getInt(4) != rows.getInt(7)) return false;
                }
            }
        }
        return observed.equals(expected);
    }

    private boolean evaluationEvidenceIntegrityValid(CanonicalId taskId,
            CanonicalId revisionId, CanonicalId runId, long evaluation,
            List<RuleRequirement> requirements) throws SQLException {
        List<Evidence> all = readEvidence(taskId, revisionId, null, null);
        List<Evidence> observed = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT step_revision_id,attempt_run_id,attempt_number,evidence_kind,
                       producer_kind,producer_identity
                FROM plan_completion_evaluation_evidence
                WHERE task_id=? AND plan_revision_id=? AND run_id=? AND evaluation_number=?
                ORDER BY step_revision_id,evidence_kind,producer_kind,producer_identity
                """)) {
            bind(statement, taskId.value(), revisionId.value(), runId.value(), evaluation);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    Evidence match = all.stream().filter(value ->
                            value.stepRevisionId().value().equals(rowsString(rows, 1))
                            && value.runId().value().equals(rowsString(rows, 2))
                            && value.attemptNumber() == rowsInt(rows, 3)
                            && value.kind().name().equals(rowsString(rows, 4))
                            && value.producerKind().name().equals(rowsString(rows, 5))
                            && value.producerIdentity().equals(rowsString(rows, 6)))
                            .findFirst().orElse(null);
                    if (match == null || !match.integrityValid()
                            || !producerIntegrityValid(match) || !completionScopeValid(match)) return false;
                    observed.add(match);
                }
            }
        }
        for (Evidence evidence : observed) {
            boolean permitted = requirements.stream().anyMatch(requirement ->
                    requirement.stepId().equals(evidence.stepRevisionId().value())
                    && ("VERIFICATION_PASSED".equals(requirement.type())
                        && evidence.kind() == EvidenceKind.VERIFICATION_RESULT
                        || "EVIDENCE_KIND_PRESENT".equals(requirement.type())
                        && requirement.evidenceKind().equals(evidence.kind().name())));
            if (!permitted) return false;
        }
        for (RuleRequirement requirement : requirements) {
            if ("STEP_SUCCEEDED".equals(requirement.type())) continue;
            if ("ANY_REQUIRED_EVIDENCE".equals(requirement.ruleType())) {
                boolean any = requirements.stream().filter(value ->
                        value.ruleOrder() == requirement.ruleOrder()
                        && "EVIDENCE_KIND_PRESENT".equals(value.type())).anyMatch(value ->
                        observed.stream().anyMatch(evidence -> value.stepId().equals(
                                evidence.stepRevisionId().value()) && value.evidenceKind().equals(
                                evidence.kind().name())));
                if (!any) return false;
            } else {
                EvidenceKind expectedKind = "VERIFICATION_PASSED".equals(requirement.type())
                        ? EvidenceKind.VERIFICATION_RESULT
                        : EvidenceKind.valueOf(requirement.evidenceKind());
                boolean present = observed.stream().anyMatch(evidence ->
                        requirement.stepId().equals(evidence.stepRevisionId().value())
                        && expectedKind == evidence.kind());
                if (!present) return false;
            }
        }
        return true;
    }

    private List<String> evaluationEvidenceHashes(CanonicalId taskId,
            CanonicalId revisionId, CanonicalId runId, long evaluation) throws SQLException {
        List<String> hashes = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT e.canonical_hash
                FROM plan_completion_evaluation_evidence i
                JOIN plan_evidence e ON e.task_id=i.task_id
                  AND e.plan_revision_id=i.plan_revision_id
                  AND e.step_revision_id=i.step_revision_id
                  AND e.run_id=i.attempt_run_id AND e.attempt_number=i.attempt_number
                  AND e.evidence_kind=i.evidence_kind AND e.producer_kind=i.producer_kind
                  AND e.producer_identity=i.producer_identity
                WHERE i.task_id=? AND i.plan_revision_id=? AND i.run_id=?
                  AND i.evaluation_number=? ORDER BY e.canonical_hash
                """)) {
            bind(statement, taskId.value(), revisionId.value(), runId.value(), evaluation);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) hashes.add(rows.getString(1));
            }
        }
        return List.copyOf(hashes);
    }

    private static CompletedAuthorityRead completedAuthority(CompletedAuthorityStatus status,
            CanonicalId taskId, CanonicalId runId, CanonicalId revisionId, String decisionHash) {
        return new CompletedAuthorityRead(status, taskId, runId, revisionId,
                decisionHash, 0, null, List.of());
    }

    private static String rowsString(ResultSet rows, int column) {
        try { return rows.getString(column); }
        catch (SQLException error) { throw new IllegalStateException(error); }
    }

    private static int rowsInt(ResultSet rows, int column) {
        try { return rows.getInt(column); }
        catch (SQLException error) { throw new IllegalStateException(error); }
    }

    private long nextEvaluation(CanonicalId taskId, CanonicalId revisionId,
            CanonicalId runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COALESCE(max(evaluation_number),0)+1 FROM plan_completion_evaluations
                WHERE task_id=? AND plan_revision_id=? AND run_id=?
                """)) {
            bind(statement, taskId.value(), revisionId.value(), runId.value());
            try (ResultSet row = statement.executeQuery()) { row.next(); return row.getLong(1); }
        }
    }

    private Status lockAuthority(ExecutionAuthoritySnapshot authority, Instant at)
            throws SQLException {
        if (update(connection, """
                UPDATE task_ownership_leases SET heartbeat_at=heartbeat_at
                WHERE task_id=? AND owner_instance_id=? AND owner_run_id=? AND lease_token=?
                    AND fencing_token=? AND cas_version=? AND state='ACTIVE' AND expires_at>?
                """, authority.taskId().value(), authority.ownerInstanceId(),
                authority.runId().value(), authority.leaseToken(), authority.fencingToken(),
                authority.casVersion(), at.toString()) == 1) return Status.SUCCESS;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT owner_instance_id,owner_run_id,lease_token,fencing_token,cas_version,
                    state,expires_at FROM task_ownership_leases WHERE task_id=?
                """)) {
            bind(statement, authority.taskId().value());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Status.AUTHORITY_NOT_FOUND;
                if (!row.getString(1).equals(authority.ownerInstanceId())) return Status.OWNER_MISMATCH;
                if (!row.getString(2).equals(authority.runId().value())) return Status.RUN_MISMATCH;
                if (!row.getString(3).equals(authority.leaseToken())) return Status.LEASE_TOKEN_MISMATCH;
                if (row.getLong(4) != authority.fencingToken()) return Status.FENCING_REJECTED;
                if (row.getLong(5) != authority.casVersion()) return Status.VERSION_CONFLICT;
                if (!at.isBefore(Instant.parse(row.getString(7)))) return Status.LEASE_EXPIRED;
                return Status.INVALID_STATE;
            }
        }
    }

    private int count(String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values); try (ResultSet row = statement.executeQuery()) {
                row.next(); return row.getInt(1);
            }
        }
    }
    private static boolean exists(Connection connection, String sql, Object... values)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values); try (ResultSet row = statement.executeQuery()) {
                return row.next();
            }
        }
    }
    private static int update(Connection connection, String sql, Object... values)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values); return statement.executeUpdate();
        }
    }
    private static void insert(Connection connection, String sql, Object... values)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values); statement.executeUpdate();
        }
    }
    private static void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) {
            Object value = values[i];
            if (value == null) statement.setNull(i + 1, Types.VARCHAR);
            else statement.setObject(i + 1, value);
        }
    }
    private static void requireOne(int count, String target) throws SQLException {
        if (count != 1) throw new SQLException("Completion gate could not update " + target);
    }
    private static String bounded(String value, int limit) {
        return value == null || value.length() <= limit ? value : value.substring(0, limit);
    }

    private record AttemptScope(CanonicalId taskId, CanonicalId revisionId,
            CanonicalId stepId, CanonicalId runId, int attemptNumber) { }
    private boolean producerIntegrityValid(Evidence evidence) throws SQLException {
        return switch (evidence.producerKind()) {
            case OCCURRENCE -> exists(connection, """
                    SELECT 1 FROM side_effect_occurrences WHERE occurrence_id=? AND task_id=?
                        AND run_id=? AND status='COMPLETED' AND operation_type='TOOL'
                    """, evidence.producerIdentity(), evidence.taskId().value(),
                    evidence.runId().value());
            case SNAPSHOT -> exists(connection, """
                    SELECT 1 FROM file_snapshots WHERE snapshot_id=? AND task_id=?
                        AND owner_run_id=? AND status='APPLIED'
                    """, evidence.producerIdentity(), evidence.taskId().value(),
                    evidence.runId().value());
            case VERIFICATION -> exists(connection, """
                    SELECT 1 FROM plan_verification_evidence a
                    JOIN verification_runs v ON v.verification_id=a.verification_id
                        AND v.run_id=a.verification_run_id AND v.task_id=a.task_id
                    WHERE a.task_id=? AND a.plan_revision_id=? AND a.step_revision_id=?
                        AND a.attempt_run_id=? AND a.attempt_number=? AND a.verification_id=?
                        AND a.verification_status='PASSED' AND v.status='PASSED'
                        AND v.candidate_digest_kind='WORKSPACE_CONTENT_SHA256_V1'
                        AND length(v.candidate_digest)=64
                        AND v.candidate_digest NOT GLOB '*[^0-9a-f]*'
                    """, evidence.taskId().value(), evidence.planRevisionId().value(),
                    evidence.stepRevisionId().value(), evidence.runId().value(),
                    evidence.attemptNumber(), evidence.producerIdentity());
            case APPROVAL -> exists(connection, """
                    SELECT 1 FROM plan_approvals WHERE approval_id=? AND task_id=?
                        AND plan_revision_id=? AND decision='APPROVED'
                    """, evidence.producerIdentity(), evidence.taskId().value(),
                    evidence.planRevisionId().value());
        };
    }

    private boolean completionScopeValid(Evidence evidence) throws SQLException {
        return exists(connection, """
                SELECT 1 FROM plan_step_attempts
                WHERE task_id=? AND plan_revision_id=? AND step_revision_id=?
                    AND run_id=? AND attempt_number=? AND state='SUCCEEDED'
                """, evidence.taskId().value(), evidence.planRevisionId().value(),
                evidence.stepRevisionId().value(), evidence.runId().value(),
                evidence.attemptNumber());
    }

    private StepProfile readStepProfile(CanonicalId taskId, CanonicalId revisionId,
            int order) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT completion_rule,required_evidence_kinds FROM plan_step_definitions
                WHERE task_id=? AND plan_revision_id=? AND step_order=?
                """)) {
            bind(statement, taskId.value(), revisionId.value(), order);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? new StepProfile(
                        CompletionRuleType.valueOf(row.getString(1)),
                        parseEvidenceKinds(row.getString(2))) : null;
            }
        }
    }

    private static List<EvidenceKind> parseEvidenceKinds(String value) {
        return java.util.Arrays.stream(value.split(",")).map(EvidenceKind::valueOf).toList();
    }
    private static String ruleMaterial(CanonicalId taskId, CanonicalId revisionId, int order,
            CompletionRuleType rule, String stepId, List<EvidenceKind> kinds) {
        return taskId.value() + "\n" + revisionId.value() + "\n" + order + "\n"
                + rule.name() + "\n" + stepId + "\nSTEP_SUCCEEDED\n"
                + kinds.stream().map(Enum::name).collect(java.util.stream.Collectors.joining("\n"));
    }

    private record RuleRequirement(int ruleOrder, String ruleType, int requirementOrder, String type,
            String stepId, String evidenceKind) { }
    private record StepProfile(CompletionRuleType rule, List<EvidenceKind> kinds) { }
    private record StepFact(String stepId, String state, long transitionVersion,
            int currentAttemptNumber) { }
}
