package com.paicli.state;

import com.paicli.identity.CanonicalId;
import com.paicli.state.R1aDurableModels.ExecutionAuthoritySnapshot;
import com.paicli.state.StructuredPlanModels.ApprovedPlan;
import com.paicli.state.StructuredPlanModels.Authority;
import com.paicli.state.StructuredPlanModels.Dependency;
import com.paicli.state.StructuredPlanModels.Lifecycle;
import com.paicli.state.StructuredPlanModels.Lineage;
import com.paicli.state.StructuredPlanModels.Revision;
import com.paicli.state.StructuredPlanModels.StepDefinition;
import com.paicli.state.StructuredPlanModels.ToolRequirement;
import com.paicli.state.PlanCompletionModels.CompletionRuleType;
import com.paicli.state.PlanCompletionModels.EffectClass;
import com.paicli.state.PlanCompletionModels.EvidenceKind;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Atomic, fenced R1C-A Plan definition and active-authority persistence. */
public final class PlanAuthorityStore {
    public enum Status {
        SUCCESS, AUTHORITY_NOT_FOUND, OWNER_MISMATCH, RUN_MISMATCH, LEASE_TOKEN_MISMATCH,
        FENCING_REJECTED, VERSION_CONFLICT, LEASE_EXPIRED, NOT_FOUND, ALREADY_EXISTS,
        INVALID_STATE, STRUCTURE_INVALID, INTEGRITY_VIOLATION, NO_APPROVED_PLAN,
        PLAN_REVISION_INTEGRITY_FAILURE, LEGACY_PLAN_CONTINUATION_UNAVAILABLE,
        COMPLETION_PROFILE_UNPRODUCIBLE, LEGACY_COMPLETION_PROFILE_REQUIRED
    }
    public record Mutation(Status status, Authority authority) {
        public Mutation { Objects.requireNonNull(status); }
        static Mutation of(Status status) { return new Mutation(status, null); }
    }
    public record Read(Status status, ApprovedPlan plan) {
        public Read { Objects.requireNonNull(status); }
        static Read of(Status status) { return new Read(status, null); }
    }
    public record RevisionRead(Status status, Revision revision) {
        public RevisionRead { Objects.requireNonNull(status); }
        static RevisionRead of(Status status) { return new RevisionRead(status, null); }
    }

    private final Connection connection;
    public PlanAuthorityStore(Connection connection) { this.connection = Objects.requireNonNull(connection); }

    public Mutation propose(ExecutionAuthoritySnapshot execution, Instant at, Revision revision) {
        if(!proposable(execution,revision,Revision.CANONICAL_SCHEMA_V3))
            return Mutation.of(Status.STRUCTURE_INVALID);
        return write(execution,at,()->proposeLocked(execution,at,revision,true));
    }

    /** Persists an immutable Advisory revision without creating Strict Tool authority material. */
    public Mutation proposeAdvisory(ExecutionAuthoritySnapshot execution, Instant at,
            Revision revision) {
        if(!proposable(execution,revision,Revision.CANONICAL_SCHEMA_V2)
                ||revision.steps().stream().anyMatch(step->!step.toolRequirements().isEmpty()))
            return Mutation.of(Status.STRUCTURE_INVALID);
        return write(execution,at,()->proposeLocked(execution,at,revision,false));
    }

    static Mutation proposeInTransaction(Connection connection,
            ExecutionAuthoritySnapshot execution,Instant at,Revision revision)
            throws SQLException{
        if(!proposable(execution,revision,Revision.CANONICAL_SCHEMA_V3))
            return Mutation.of(Status.STRUCTURE_INVALID);
        return new PlanAuthorityStore(connection).proposeLocked(execution,at,revision,true);
    }

    private static boolean proposable(ExecutionAuthoritySnapshot execution,Revision revision,
            int expectedSchema){
        Objects.requireNonNull(revision);
        if (!PlanContentBounds.validate(revision).valid()) return false;
        return revision.taskId().equals(execution.taskId())
                &&revision.createdByRunId().equals(execution.runId())
                &&revision.canonicalSchemaVersion()==expectedSchema
                &&revision.canonicalHash().equals(PlanCanonicalizer.hash(revision))
                &&valid(revision);
    }

    private Mutation proposeLocked(ExecutionAuthoritySnapshot execution,Instant at,
            Revision revision,boolean strictV3)throws SQLException{
            insertRevision(revision);
            if(strictV3)insertV3Marker(revision);
            for (StepDefinition step : revision.steps()) insertStep(revision, step);
            for (StepDefinition step : revision.steps()) {
                for (ToolRequirement requirement : step.toolRequirements()) {
                    insertRequirement(revision, step, requirement);
                }
            }
            for (Dependency dependency : revision.dependencies()) insertDependency(revision, dependency);
            for (Lineage lineage : revision.lineage()) {
                if (!validLineage(revision, lineage)) return Mutation.of(Status.STRUCTURE_INVALID);
                insertLineage(revision, lineage);
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO plan_revision_lifecycle(plan_revision_id,task_id,state,cas_version,
                        updated_by_run_id,updated_at) VALUES(?,?,'DRAFT',0,?,?)
                    """)) {
                bind(statement, revision.planRevisionId().value(), revision.taskId().value(),
                        execution.runId().value(), at.toString());
                statement.executeUpdate();
            }
            return Mutation.of(Status.SUCCESS);
    }

    public Mutation validateRevision(ExecutionAuthoritySnapshot execution, Instant at,
            CanonicalId planRevisionId, long expectedVersion) {
        return write(execution, at, () -> {
            Revision definition = readRevision(execution.taskId(), planRevisionId).orElse(null);
            if (definition == null) return Mutation.of(Status.NOT_FOUND);
            if (!definition.canonicalHash().equals(PlanCanonicalizer.hash(definition))) {
                return Mutation.of(Status.PLAN_REVISION_INTEGRITY_FAILURE);
            }
            Status producible = completionProfileProducible(execution, definition);
            if (producible != Status.SUCCESS) return Mutation.of(producible);
            return update("""
                    UPDATE plan_revision_lifecycle SET state='VALIDATED',
                        cas_version=cas_version+1,updated_by_run_id=?,updated_at=?
                    WHERE task_id=? AND plan_revision_id=? AND state='DRAFT' AND cas_version=?
                    """, execution.runId().value(), at.toString(), execution.taskId().value(),
                    planRevisionId.value(), expectedVersion) == 1
                    ? Mutation.of(Status.SUCCESS)
                    : Mutation.of(classifyLifecycle(execution.taskId(), planRevisionId,
                            expectedVersion, "DRAFT"));
        });
    }

    /** Validates immutable Plan content without asserting Strict execution producibility. */
    public Mutation validateRevisionAdvisory(ExecutionAuthoritySnapshot execution, Instant at,
            CanonicalId planRevisionId, long expectedVersion) {
        return write(execution, at, () -> {
            Revision definition = readRevision(execution.taskId(), planRevisionId).orElse(null);
            if (definition == null) return Mutation.of(Status.NOT_FOUND);
            if (!definition.canonicalHash().equals(PlanCanonicalizer.hash(definition))) {
                return Mutation.of(Status.PLAN_REVISION_INTEGRITY_FAILURE);
            }
            if (!PlanContentBounds.validate(definition).valid() || !valid(definition)) {
                return Mutation.of(Status.STRUCTURE_INVALID);
            }
            return update("""
                    UPDATE plan_revision_lifecycle SET state='VALIDATED',
                        cas_version=cas_version+1,updated_by_run_id=?,updated_at=?
                    WHERE task_id=? AND plan_revision_id=? AND state='DRAFT' AND cas_version=?
                    """, execution.runId().value(), at.toString(), execution.taskId().value(),
                    planRevisionId.value(), expectedVersion) == 1
                    ? Mutation.of(Status.SUCCESS)
                    : Mutation.of(classifyLifecycle(execution.taskId(), planRevisionId,
                            expectedVersion, "DRAFT"));
        });
    }

    public Mutation approveAndActivate(ExecutionAuthoritySnapshot execution, Instant at,
            CanonicalId planRevisionId, long expectedLifecycleVersion, long expectedPointerVersion,
            String approvalId, String approvedBy, String policyVersion, String reason) {
        return write(execution, at, () -> {
            Revision definition = readRevision(execution.taskId(), planRevisionId).orElse(null);
            if (definition == null
                    || !definition.canonicalHash().equals(PlanCanonicalizer.hash(definition))) {
                return Mutation.of(Status.PLAN_REVISION_INTEGRITY_FAILURE);
            }
            Status producible = completionProfileProducible(execution, definition);
            if (producible != Status.SUCCESS) return Mutation.of(producible);
            if (update("""
                    UPDATE plan_revision_lifecycle SET state='APPROVED',cas_version=cas_version+1,
                        updated_by_run_id=?,updated_at=?
                    WHERE task_id=? AND plan_revision_id=? AND state='VALIDATED' AND cas_version=?
                    """, execution.runId().value(), at.toString(), execution.taskId().value(),
                    planRevisionId.value(), expectedLifecycleVersion) != 1) {
                return Mutation.of(classifyLifecycle(execution.taskId(), planRevisionId,
                        expectedLifecycleVersion, "VALIDATED"));
            }
            insert("""
                    INSERT INTO plan_approvals(approval_id,task_id,plan_revision_id,decision,
                        approved_by,approved_by_run_id,policy_version,reason,decided_at)
                    VALUES(?,?,?,'APPROVED',?,?,?,?,?)
                    """, approvalId, execution.taskId().value(), planRevisionId.value(), approvedBy,
                    execution.runId().value(), policyVersion, reason, at.toString());
            long next;
            String previous = null;
            if (expectedPointerVersion == 0 && !pointerExists(execution.taskId())) {
                insert("""
                        INSERT INTO task_plan_authorities(task_id,active_plan_revision_id,
                            active_revision_state,active_approval_id,active_approval_decision,
                            activated_by_run_id,pointer_version,activated_at)
                        VALUES(?,?,'APPROVED',?,'APPROVED',?,1,?)
                        """, execution.taskId().value(), planRevisionId.value(), approvalId,
                        execution.runId().value(), at.toString());
                next = 1;
            } else {
                try (PreparedStatement current = connection.prepareStatement("""
                        SELECT active_plan_revision_id FROM task_plan_authorities
                        WHERE task_id=? AND pointer_version=?
                        """)) {
                    bind(current, execution.taskId().value(), expectedPointerVersion);
                    try (ResultSet row = current.executeQuery()) {
                        if (!row.next()) return Mutation.of(Status.VERSION_CONFLICT);
                        previous = row.getString(1);
                    }
                }
                if (update("""
                        UPDATE task_plan_authorities SET active_plan_revision_id=?,
                            active_revision_state='APPROVED',active_approval_id=?,
                            active_approval_decision='APPROVED',activated_by_run_id=?,
                            pointer_version=pointer_version+1,activated_at=?
                        WHERE task_id=? AND pointer_version=?
                        """, planRevisionId.value(), approvalId, execution.runId().value(), at.toString(),
                        execution.taskId().value(), expectedPointerVersion) != 1) {
                    return Mutation.of(Status.VERSION_CONFLICT);
                }
                next = expectedPointerVersion + 1;
            }
            String hash = definition.canonicalHash();
            insert("""
                    INSERT INTO plan_run_bindings(binding_id,task_id,run_id,plan_revision_id,
                        approval_id,approval_decision,plan_hash,bound_at)
                    VALUES(?,?,?,?,?,'APPROVED',?,?)
                    """, "bind-" + java.util.UUID.randomUUID(), execution.taskId().value(),
                    execution.runId().value(), planRevisionId.value(), approvalId, hash, at.toString());
            PlanExecutionStore.initializeForApprovedBinding(connection, execution.taskId(),
                    planRevisionId, execution.runId(), at, "R1C_B1_APPROVED_BINDING");
            PlanCompletionStore.initializeRules(connection, execution.taskId(),
                    planRevisionId, execution.runId(), at);
            if (previous != null && !previous.equals(planRevisionId.value())
                    && update("""
                            UPDATE plan_revision_lifecycle SET state='SUPERSEDED',
                                cas_version=cas_version+1,updated_by_run_id=?,updated_at=?
                            WHERE task_id=? AND plan_revision_id=? AND state='APPROVED'
                            """, execution.runId().value(), at.toString(),
                            execution.taskId().value(), previous) != 1) {
                return Mutation.of(Status.INTEGRITY_VIOLATION);
            }
            Authority active=readAuthority(execution.taskId()).orElse(null);
            if(active==null||!active.planRevisionId().equals(planRevisionId)
                    ||!active.approvalId().equals(approvalId)||active.pointerVersion()!=next
                    ||!exists("""
                            SELECT 1 FROM plan_run_bindings
                            WHERE task_id=? AND run_id=? AND plan_revision_id=?
                                AND approval_id=? AND plan_hash=?
                            """,execution.taskId().value(),execution.runId().value(),
                            planRevisionId.value(),approvalId,hash)){
                return Mutation.of(Status.INTEGRITY_VIOLATION);
            }
            return new Mutation(Status.SUCCESS, new Authority(execution.taskId(), planRevisionId,
                    approvalId, next, execution.runId(), at));
        });
    }

    /**
     * Records approval of an advisory Plan without creating execution authority, a Run
     * binding, Step state, or completion rules. The immutable revision remains auditable,
     * while Tool authorization continues to be owned by the runtime safety pipeline.
     */
    public Mutation approveAdvisory(ExecutionAuthoritySnapshot execution, Instant at,
            CanonicalId planRevisionId, long expectedLifecycleVersion, String approvalId,
            String approvedBy, String policyVersion, String reason) {
        return write(execution, at, () -> {
            Revision definition = readRevision(execution.taskId(), planRevisionId).orElse(null);
            if (definition == null
                    || !definition.canonicalHash().equals(PlanCanonicalizer.hash(definition))) {
                return Mutation.of(Status.PLAN_REVISION_INTEGRITY_FAILURE);
            }
            if (!PlanContentBounds.validate(definition).valid() || !valid(definition)) {
                return Mutation.of(Status.STRUCTURE_INVALID);
            }
            if (update("""
                    UPDATE plan_revision_lifecycle SET state='APPROVED',cas_version=cas_version+1,
                        updated_by_run_id=?,updated_at=?
                    WHERE task_id=? AND plan_revision_id=? AND state='VALIDATED' AND cas_version=?
                    """, execution.runId().value(), at.toString(), execution.taskId().value(),
                    planRevisionId.value(), expectedLifecycleVersion) != 1) {
                return Mutation.of(classifyLifecycle(execution.taskId(), planRevisionId,
                        expectedLifecycleVersion, "VALIDATED"));
            }
            insert("""
                    INSERT INTO plan_approvals(approval_id,task_id,plan_revision_id,decision,
                        approved_by,approved_by_run_id,policy_version,reason,decided_at)
                    VALUES(?,?,?,'APPROVED',?,?,?,?,?)
                    """, approvalId, execution.taskId().value(), planRevisionId.value(), approvedBy,
                    execution.runId().value(), policyVersion, reason, at.toString());
            if (!exists("""
                    SELECT 1 FROM plan_revision_lifecycle l
                    JOIN plan_approvals p ON p.task_id=l.task_id
                        AND p.plan_revision_id=l.plan_revision_id AND p.decision='APPROVED'
                    WHERE l.task_id=? AND l.plan_revision_id=? AND l.state='APPROVED'
                        AND p.approval_id=?
                    """, execution.taskId().value(), planRevisionId.value(), approvalId)) {
                return Mutation.of(Status.INTEGRITY_VIOLATION);
            }
            return Mutation.of(Status.SUCCESS);
        });
    }

    public Mutation reject(ExecutionAuthoritySnapshot execution, Instant at,
            CanonicalId planRevisionId, long expectedLifecycleVersion, String approvalId,
            String rejectedBy, String policyVersion, String reason) {
        return write(execution, at, () -> {
            if (update("""
                    UPDATE plan_revision_lifecycle SET state='REJECTED',cas_version=cas_version+1,
                        updated_by_run_id=?,updated_at=?
                    WHERE task_id=? AND plan_revision_id=? AND state='VALIDATED' AND cas_version=?
                    """, execution.runId().value(), at.toString(), execution.taskId().value(),
                    planRevisionId.value(), expectedLifecycleVersion) != 1) {
                return Mutation.of(classifyLifecycle(execution.taskId(), planRevisionId,
                        expectedLifecycleVersion, "VALIDATED"));
            }
            insert("""
                    INSERT INTO plan_approvals(approval_id,task_id,plan_revision_id,decision,
                        approved_by,approved_by_run_id,policy_version,reason,decided_at)
                    VALUES(?,?,?,'REJECTED',?,?,?,?,?)
                    """, approvalId, execution.taskId().value(), planRevisionId.value(), rejectedBy,
                    execution.runId().value(), policyVersion, reason, at.toString());
            return Mutation.of(Status.SUCCESS);
        });
    }

    public Mutation bindRecoveryRun(ExecutionAuthoritySnapshot execution, Instant at) {
        return write(execution, at, () -> {
            Authority authority = readAuthority(execution.taskId()).orElse(null);
            if (authority == null) return Mutation.of(Status.NO_APPROVED_PLAN);
            String hash = revisionHash(execution.taskId(), authority.planRevisionId()).orElse(null);
            if (hash == null) return Mutation.of(Status.PLAN_REVISION_INTEGRITY_FAILURE);
            insert("""
                    INSERT INTO plan_run_bindings(binding_id,task_id,run_id,plan_revision_id,
                        approval_id,approval_decision,plan_hash,bound_at)
                    VALUES(?,?,?,?,?,'APPROVED',?,?)
                    """, "bind-" + java.util.UUID.randomUUID(), execution.taskId().value(),
                    execution.runId().value(), authority.planRevisionId().value(), authority.approvalId(),
                    hash, at.toString());
            return new Mutation(Status.SUCCESS, authority);
        });
    }

    public Read readActive(CanonicalId taskId) {
        try {
            Authority authority = readAuthority(taskId).orElse(null);
            if (authority == null) {
                if (pointerExists(taskId)) return Read.of(Status.PLAN_REVISION_INTEGRITY_FAILURE);
                return Read.of(legacyExists(taskId)
                        ? Status.LEGACY_PLAN_CONTINUATION_UNAVAILABLE : Status.NO_APPROVED_PLAN);
            }
            Revision revision = readRevision(taskId, authority.planRevisionId()).orElse(null);
            if (revision == null || !revision.canonicalHash().equals(PlanCanonicalizer.hash(revision))) {
                return Read.of(Status.PLAN_REVISION_INTEGRITY_FAILURE);
            }
            return new Read(Status.SUCCESS, new ApprovedPlan(revision, authority));
        } catch (SQLException e) { throw new IllegalStateException("Plan authority read failed", e); }
    }

    public RevisionRead readRevisionDefinition(CanonicalId taskId,CanonicalId planRevisionId){
        try{
            Revision revision=readRevision(taskId,planRevisionId).orElse(null);
            if(revision==null)return RevisionRead.of(Status.NOT_FOUND);
            if(!revision.canonicalHash().equals(PlanCanonicalizer.hash(revision)))
                return RevisionRead.of(Status.PLAN_REVISION_INTEGRITY_FAILURE);
            return new RevisionRead(Status.SUCCESS,revision);
        }catch(SQLException e){throw new IllegalStateException("Plan revision read failed",e);}
    }

    /** Reads the newest immutable advisory approval without treating it as Tool authority. */
    public RevisionRead readLatestApprovedRevision(CanonicalId taskId){
        try(PreparedStatement statement=connection.prepareStatement("""
                SELECT l.plan_revision_id
                FROM plan_revision_lifecycle l
                JOIN plan_revisions r ON r.task_id=l.task_id
                    AND r.plan_revision_id=l.plan_revision_id
                JOIN plan_approvals p ON p.task_id=l.task_id
                    AND p.plan_revision_id=l.plan_revision_id AND p.decision='APPROVED'
                WHERE l.task_id=? AND l.state='APPROVED'
                ORDER BY r.revision_number DESC,p.decided_at DESC,l.plan_revision_id DESC
                LIMIT 1
                """)){
            statement.setString(1,taskId.value());
            try(ResultSet row=statement.executeQuery()){
                if(!row.next())return RevisionRead.of(Status.NOT_FOUND);
                Revision revision=readRevision(taskId,CanonicalId.parse(row.getString(1)))
                        .orElse(null);
                if(revision==null||!revision.canonicalHash().equals(PlanCanonicalizer.hash(revision)))
                    return RevisionRead.of(Status.PLAN_REVISION_INTEGRITY_FAILURE);
                return new RevisionRead(Status.SUCCESS,revision);
            }
        }catch(SQLException e){throw new IllegalStateException("Advisory Plan read failed",e);}
    }

    private Mutation lifecycle(ExecutionAuthoritySnapshot execution, Instant at, CanonicalId id,
            long expectedVersion, String expected, String next) {
        return write(execution, at, () -> update("""
                UPDATE plan_revision_lifecycle SET state=?,cas_version=cas_version+1,
                    updated_by_run_id=?,updated_at=?
                WHERE task_id=? AND plan_revision_id=? AND state=? AND cas_version=?
                """, next, execution.runId().value(), at.toString(), execution.taskId().value(),
                id.value(), expected, expectedVersion) == 1 ? Mutation.of(Status.SUCCESS)
                : Mutation.of(classifyLifecycle(execution.taskId(), id, expectedVersion, expected)));
    }

    private Mutation write(ExecutionAuthoritySnapshot execution, Instant at, SqlWork work) {
        Objects.requireNonNull(execution); Objects.requireNonNull(at);
        try {
            if (!connection.getAutoCommit()) throw new SQLException("Nested Plan transaction is unsupported");
            connection.setAutoCommit(false);
            try {
                Status authority = lockAuthority(execution, at);
                if (authority != Status.SUCCESS) { connection.rollback(); return Mutation.of(authority); }
                Mutation result = work.run();
                if (result.status() == Status.SUCCESS) connection.commit(); else connection.rollback();
                return result;
            } catch (SQLException e) {
                connection.rollback();
                if (e.getErrorCode() == 19) return Mutation.of(Status.INTEGRITY_VIOLATION);
                throw e;
            } catch (RuntimeException e) { connection.rollback(); throw e; }
            finally { connection.setAutoCommit(true); }
        } catch (SQLException e) { throw new IllegalStateException("Plan authority persistence failed", e); }
    }

    private Status lockAuthority(ExecutionAuthoritySnapshot a, Instant at) throws SQLException {
        if (update("""
                UPDATE task_ownership_leases SET heartbeat_at=heartbeat_at
                WHERE task_id=? AND owner_instance_id=? AND owner_run_id=? AND lease_token=?
                    AND fencing_token=? AND cas_version=? AND state='ACTIVE' AND expires_at>?
                """, a.taskId().value(), a.ownerInstanceId(), a.runId().value(), a.leaseToken(),
                a.fencingToken(), a.casVersion(), at.toString()) == 1) return Status.SUCCESS;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT owner_instance_id,owner_run_id,lease_token,fencing_token,cas_version,state,expires_at
                FROM task_ownership_leases WHERE task_id=?
                """)) {
            statement.setString(1, a.taskId().value());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Status.AUTHORITY_NOT_FOUND;
                if (!row.getString(1).equals(a.ownerInstanceId())) return Status.OWNER_MISMATCH;
                if (!row.getString(2).equals(a.runId().value())) return Status.RUN_MISMATCH;
                if (!row.getString(3).equals(a.leaseToken())) return Status.LEASE_TOKEN_MISMATCH;
                if (row.getLong(4) != a.fencingToken()) return Status.FENCING_REJECTED;
                if (row.getLong(5) != a.casVersion()) return Status.VERSION_CONFLICT;
                if (!at.isBefore(Instant.parse(row.getString(7)))) return Status.LEASE_EXPIRED;
                return Status.INVALID_STATE;
            }
        }
    }

    private void insertRevision(Revision r) throws SQLException { insert("""
            INSERT INTO plan_revisions(plan_revision_id,task_id,revision_number,goal,
                acceptance_criteria,assumptions,risks,verification_strategy,canonical_hash,
                source_type,provenance,created_by_run_id,created_at,canonical_schema_version)
            VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """, r.planRevisionId().value(), r.taskId().value(), r.revisionNumber(), r.goal(),
            r.acceptanceCriteria(), r.assumptions(), r.risks(), r.verificationStrategy(),
            r.canonicalHash(), r.sourceType(), r.provenance(), r.createdByRunId().value(),
            r.createdAt(), Revision.CANONICAL_SCHEMA_V2); }
    /** V22's base-column CHECK is (1,2); this additive marker is the effective V3 version. */
    private void insertV3Marker(Revision r) throws SQLException { insert("""
            INSERT INTO plan_v3_revisions(task_id,plan_revision_id,canonical_schema_version)
            VALUES(?,?,3)
            """, r.taskId().value(), r.planRevisionId().value()); }
    private void insertStep(Revision r, StepDefinition s) throws SQLException { insert("""
            INSERT INTO plan_step_definitions(step_revision_id,logical_step_id,plan_revision_id,
                task_id,step_order,title,definition,canonical_hash,completion_rule,
                required_evidence_kinds,effect_class) VALUES(?,?,?,?,?,?,?,?,?,?,?)
            """, s.stepRevisionId().value(), s.logicalStepId().value(), r.planRevisionId().value(),
            r.taskId().value(), s.order(), s.title(), s.definition(), s.canonicalHash(),
            s.completionRule().name(), evidenceKinds(s.requiredEvidenceKinds()),
            s.effectClass().name()); }
    private void insertRequirement(Revision r, StepDefinition step, ToolRequirement value)
            throws SQLException { insert("""
            INSERT INTO plan_tool_requirements(task_id,plan_revision_id,step_revision_id,
                requirement_id,requirement_order,tool_name,effect_class,selector_kind,
                selector_canonical_json,selector_digest,min_successes,max_occurrences,
                freshness_policy) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
            """, r.taskId().value(), r.planRevisionId().value(), step.stepRevisionId().value(),
            value.requirementId(), value.requirementOrder(), value.toolName(),
            value.effectClass().name(), value.authoritySelector().kind().name(),
            value.authoritySelector().canonicalJson(), value.authoritySelector().digest(),
            value.minSuccesses(), value.maxOccurrences(), value.freshnessPolicy().name()); }
    private void insertDependency(Revision r, Dependency d) throws SQLException { insert("""
            INSERT INTO plan_step_dependencies(task_id,plan_revision_id,step_revision_id,
                depends_on_step_revision_id) VALUES(?,?,?,?)
            """, r.taskId().value(), r.planRevisionId().value(), d.stepRevisionId().value(),
            d.dependsOnStepRevisionId().value()); }
    private void insertLineage(Revision r, Lineage l) throws SQLException { insert("""
            INSERT INTO plan_step_lineage(lineage_id,task_id,source_plan_revision_id,
                source_step_revision_id,target_plan_revision_id,target_step_revision_id,
                relationship,provenance,created_by_run_id,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)
            """, l.lineageId(), r.taskId().value(), value(l.sourcePlanRevisionId()),
            value(l.sourceStepRevisionId()), value(l.targetPlanRevisionId()),
            value(l.targetStepRevisionId()), l.relationship().name(), l.provenance(),
            r.createdByRunId().value(), l.createdAt()); }

    private Optional<Authority> readAuthority(CanonicalId taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT a.active_plan_revision_id,a.active_approval_id,a.pointer_version,
                    a.activated_by_run_id,a.activated_at
                FROM task_plan_authorities a
                JOIN plan_revision_lifecycle l ON l.task_id=a.task_id
                    AND l.plan_revision_id=a.active_plan_revision_id AND l.state='APPROVED'
                JOIN plan_approvals p ON p.task_id=a.task_id
                    AND p.plan_revision_id=a.active_plan_revision_id
                    AND p.approval_id=a.active_approval_id AND p.decision='APPROVED'
                WHERE a.task_id=?
                """)) {
            statement.setString(1, taskId.value());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(new Authority(taskId, CanonicalId.parse(row.getString(1)),
                        row.getString(2), row.getLong(3), CanonicalId.parse(row.getString(4)),
                        Instant.parse(row.getString(5)))) : Optional.empty();
            }
        }
    }
    private Optional<Revision> readRevision(CanonicalId taskId, CanonicalId planId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT revision_number,goal,acceptance_criteria,assumptions,risks,
                    verification_strategy,canonical_hash,source_type,provenance,created_by_run_id,
                    created_at,CASE WHEN EXISTS (
                        SELECT 1 FROM plan_v3_revisions v3
                        WHERE v3.task_id=plan_revisions.task_id
                          AND v3.plan_revision_id=plan_revisions.plan_revision_id
                          AND v3.canonical_schema_version=3)
                        THEN 3 ELSE canonical_schema_version END
                FROM plan_revisions WHERE task_id=? AND plan_revision_id=?
                """)) {
            bind(statement, taskId.value(), planId.value());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return Optional.empty();
                return Optional.of(new Revision(planId, taskId, row.getInt(1), row.getString(2),
                        row.getString(3), row.getString(4), row.getString(5), row.getString(6),
                        row.getString(7), row.getString(8), row.getString(9), CanonicalId.parse(row.getString(10)),
                        Instant.parse(row.getString(11)), readSteps(taskId, planId, row.getInt(12)),
                        readDependencies(taskId, planId), readLineage(taskId, planId), row.getInt(12)));
            }
        }
    }
    private List<StepDefinition> readSteps(CanonicalId taskId, CanonicalId planId,
            int canonicalSchemaVersion) throws SQLException {
        List<StepDefinition> result = new ArrayList<>();
        try (PreparedStatement s = connection.prepareStatement("""
                SELECT step_revision_id,logical_step_id,step_order,title,definition,canonical_hash,
                    completion_rule,required_evidence_kinds,effect_class
                FROM plan_step_definitions WHERE task_id=? AND plan_revision_id=? ORDER BY step_order
                """)) { bind(s, taskId.value(), planId.value()); try (ResultSet r=s.executeQuery()) {
            while(r.next()) {
                if (canonicalSchemaVersion == Revision.CANONICAL_SCHEMA_V1) {
                    if (r.getString(7) != null || r.getString(8) != null
                            || r.getString(9) != null) {
                        throw new SQLException(
                                "V1 Plan Revision contains a V2 completion profile");
                    }
                    result.add(new StepDefinition(CanonicalId.parse(r.getString(1)),
                            CanonicalId.parse(r.getString(2)),r.getInt(3),r.getString(4),
                            r.getString(5),r.getString(6)));
                } else {
                    if (r.getString(7) == null || r.getString(8) == null
                            || r.getString(8).isBlank() || r.getString(9) == null) {
                        throw new SQLException(
                                "V2 Plan Revision has no complete completion profile");
                    }
                    CanonicalId stepId = CanonicalId.parse(r.getString(1));
                    List<ToolRequirement> requirements = canonicalSchemaVersion
                            == Revision.CANONICAL_SCHEMA_V3
                            ? PlanRequirementStore.requirements(connection, taskId, planId, stepId)
                                    .stream().map(PlanRequirementStore.RequirementRecord::requirement)
                                    .toList()
                            : List.of();
                    result.add(new StepDefinition(stepId,
                            CanonicalId.parse(r.getString(2)),r.getInt(3),r.getString(4),r.getString(5),
                            CompletionRuleType.valueOf(r.getString(7)), parseEvidenceKinds(r.getString(8)),
                            EffectClass.valueOf(r.getString(9)), requirements, r.getString(6)));
                }
            } }}
        return result;
    }

    private Status completionProfileProducible(ExecutionAuthoritySnapshot execution,
            Revision revision) throws SQLException {
        if (revision.canonicalSchemaVersion() == Revision.CANONICAL_SCHEMA_V1) {
            return Status.LEGACY_COMPLETION_PROFILE_REQUIRED;
        }
        boolean verificationAvailable = exists("""
                SELECT 1 FROM coding_runs WHERE task_id=? AND run_id=?
                    AND verification_command IS NOT NULL
                    AND length(trim(verification_command))>0
                """, execution.taskId().value(), execution.runId().value());
        return PlanCompletionProfilePolicy.validate(revision,
                new PlanCompletionProfilePolicy.Capabilities(verificationAvailable)).producible()
                ? Status.SUCCESS : Status.COMPLETION_PROFILE_UNPRODUCIBLE;
    }
    private List<Dependency> readDependencies(CanonicalId taskId, CanonicalId planId) throws SQLException {
        List<Dependency> result = new ArrayList<>();
        try (PreparedStatement s = connection.prepareStatement("""
                SELECT step_revision_id,depends_on_step_revision_id FROM plan_step_dependencies
                WHERE task_id=? AND plan_revision_id=? ORDER BY step_revision_id,depends_on_step_revision_id
                """)) { bind(s, taskId.value(), planId.value()); try (ResultSet r=s.executeQuery()) {
            while(r.next()) result.add(new Dependency(CanonicalId.parse(r.getString(1)),CanonicalId.parse(r.getString(2)))); }}
        return result;
    }
    private List<Lineage> readLineage(CanonicalId taskId, CanonicalId planId) throws SQLException {
        List<Lineage> result = new ArrayList<>();
        try (PreparedStatement s = connection.prepareStatement("""
                SELECT lineage_id,source_plan_revision_id,source_step_revision_id,target_plan_revision_id,
                    target_step_revision_id,relationship,provenance,created_at FROM plan_step_lineage
                WHERE task_id=? AND target_plan_revision_id=? ORDER BY lineage_id
                """)) { bind(s,taskId.value(),planId.value()); try(ResultSet r=s.executeQuery()) {
            while(r.next()) result.add(new Lineage(r.getString(1),parse(r.getString(2)),parse(r.getString(3)),
                    parse(r.getString(4)),parse(r.getString(5)),
                    StructuredPlanModels.LineageRelationship.valueOf(r.getString(6)),r.getString(7),Instant.parse(r.getString(8)))); }}
        return result;
    }

    private Optional<String> revisionHash(CanonicalId taskId, CanonicalId planId) throws SQLException {
        try(PreparedStatement s=connection.prepareStatement("SELECT canonical_hash FROM plan_revisions WHERE task_id=? AND plan_revision_id=?")){
            bind(s,taskId.value(),planId.value());try(ResultSet r=s.executeQuery()){return r.next()?Optional.of(r.getString(1)):Optional.empty();}}
    }
    private boolean pointerExists(CanonicalId taskId) throws SQLException { return exists("SELECT 1 FROM task_plan_authorities WHERE task_id=?",taskId.value()); }
    private boolean legacyExists(CanonicalId taskId) throws SQLException { return exists("SELECT 1 FROM task_plans WHERE task_id=?",taskId.value()); }
    private boolean exists(String sql,Object... values)throws SQLException{try(PreparedStatement s=connection.prepareStatement(sql)){bind(s,values);try(ResultSet r=s.executeQuery()){return r.next();}}}
    private Status classifyLifecycle(CanonicalId taskId, CanonicalId id,long version,String state)throws SQLException{
        try(PreparedStatement s=connection.prepareStatement("SELECT state,cas_version FROM plan_revision_lifecycle WHERE task_id=? AND plan_revision_id=?")){
            bind(s,taskId.value(),id.value());try(ResultSet r=s.executeQuery()){if(!r.next())return Status.NOT_FOUND;
                if(r.getLong(2)!=version)return Status.VERSION_CONFLICT;return r.getString(1).equals(state)?Status.INVALID_STATE:Status.INVALID_STATE;}}
    }
    private boolean validLineage(Revision revision, Lineage lineage) throws SQLException {
        if (!lineage.targetPlanRevisionId().equals(revision.planRevisionId())) return false;
        if (lineage.sourcePlanRevisionId() == null) return true;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT revision_number FROM plan_revisions
                WHERE task_id=? AND plan_revision_id=?
                """)) {
            bind(statement, revision.taskId().value(), lineage.sourcePlanRevisionId().value());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() && row.getInt(1) < revision.revisionNumber();
            }
        }
    }
    private static boolean valid(Revision r){
        if(r.steps().size()!=r.steps().stream().map(StepDefinition::order).distinct().count())return false;
        for(int i=0;i<r.steps().size();i++){
            int expectedOrder=i;
            if(r.steps().stream().noneMatch(s->s.order()==expectedOrder))return false;
        }
        var ids=r.steps().stream().map(StepDefinition::stepRevisionId).collect(java.util.stream.Collectors.toSet());
        if(!r.dependencies().stream().allMatch(d->ids.contains(d.stepRevisionId())&&ids.contains(d.dependsOnStepRevisionId())))return false;
        java.util.Map<CanonicalId,java.util.Set<CanonicalId>> graph=new java.util.HashMap<>();
        for(CanonicalId id:ids)graph.put(id,new java.util.HashSet<>());
        for(Dependency d:r.dependencies())graph.get(d.stepRevisionId()).add(d.dependsOnStepRevisionId());
        java.util.Set<CanonicalId> visiting=new java.util.HashSet<>(),done=new java.util.HashSet<>();
        for(CanonicalId id:ids)if(cyclic(id,graph,visiting,done))return false;
        if (r.canonicalSchemaVersion() == Revision.CANONICAL_SCHEMA_V3) {
            for (StepDefinition step : r.steps()) {
                if (step.toolRequirements().size() != step.toolRequirements().stream()
                        .map(ToolRequirement::requirementId).distinct().count()) return false;
                for (int order = 0; order < step.toolRequirements().size(); order++) {
                    if (step.toolRequirements().get(order).requirementOrder() != order) return false;
                }
            }
        }
        return true;
    }
    private static boolean cyclic(CanonicalId id,java.util.Map<CanonicalId,java.util.Set<CanonicalId>> graph,
            java.util.Set<CanonicalId> visiting,java.util.Set<CanonicalId> done){
        if(done.contains(id))return false;if(!visiting.add(id))return true;
        for(CanonicalId dependency:graph.get(id))if(cyclic(dependency,graph,visiting,done))return true;
        visiting.remove(id);done.add(id);return false;
    }
    private int update(String sql,Object...values)throws SQLException{try(PreparedStatement s=connection.prepareStatement(sql)){bind(s,values);return s.executeUpdate();}}
    private void insert(String sql,Object...values)throws SQLException{try(PreparedStatement s=connection.prepareStatement(sql)){bind(s,values);s.executeUpdate();}}
    private static void bind(PreparedStatement s,Object...values)throws SQLException{for(int i=0;i<values.length;i++)s.setObject(i+1,values[i]);}
    private static String value(CanonicalId id){return id==null?null:id.value();}
    private static String evidenceKinds(List<EvidenceKind> kinds) {
        return kinds.stream().map(Enum::name).collect(java.util.stream.Collectors.joining(","));
    }
    private static List<EvidenceKind> parseEvidenceKinds(String value) {
        return java.util.Arrays.stream(value.split(",")).map(EvidenceKind::valueOf).toList();
    }
    private static CanonicalId parse(String value){return value==null?null:CanonicalId.parse(value);}
    @FunctionalInterface private interface SqlWork { Mutation run() throws SQLException; }
}
