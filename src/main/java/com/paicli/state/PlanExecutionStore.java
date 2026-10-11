package com.paicli.state;

import com.paicli.identity.CanonicalId;
import com.paicli.state.PlanExecutionModels.Attempt;
import com.paicli.state.PlanExecutionModels.AttemptStart;
import com.paicli.state.PlanExecutionModels.AttemptState;
import com.paicli.state.PlanExecutionModels.Outcome;
import com.paicli.state.PlanExecutionModels.StepExecution;
import com.paicli.state.PlanExecutionModels.StepState;
import com.paicli.state.R1aDurableModels.ExecutionAuthoritySnapshot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Operation-specific atomic fencing for R1C-B1 Step state and Attempts. */
public final class PlanExecutionStore {
    public enum Status {
        SUCCESS, AUTHORITY_NOT_FOUND, OWNER_MISMATCH, RUN_MISMATCH, LEASE_TOKEN_MISMATCH,
        FENCING_REJECTED, VERSION_CONFLICT, LEASE_EXPIRED, BINDING_NOT_FOUND,
        PLAN_STEP_STATE_UNAVAILABLE, PLAN_STEP_STATE_INTEGRITY_FAILURE,
        PLAN_ATTEMPT_UNRESOLVED, NO_READY_PLAN_STEP, TARGET_NOT_FOUND,
        INVALID_STATE, ALREADY_INITIALIZED, INTEGRITY_VIOLATION
    }

    public record Mutation(Status status) {
        public Mutation { Objects.requireNonNull(status); }
    }

    public record StartResult(Status status, AttemptStart value) {
        public StartResult { Objects.requireNonNull(status); }
        static StartResult rejected(Status status) { return new StartResult(status, null); }
    }

    public record RecoveryRead(Status status, List<StepExecution> states,
            List<Attempt> attempts) {
        public RecoveryRead {
            Objects.requireNonNull(status);
            states = states == null ? List.of() : List.copyOf(states);
            attempts = attempts == null ? List.of() : List.copyOf(attempts);
        }
    }

    private final Connection connection;

    public PlanExecutionStore(Connection connection) {
        this.connection = Objects.requireNonNull(connection);
    }

    static void initializeForApprovedBinding(Connection connection, CanonicalId taskId,
            CanonicalId revisionId, CanonicalId runId, Instant at, String provenance)
            throws SQLException {
        if (!exists(connection, """
                SELECT 1 FROM plan_run_bindings
                WHERE task_id=? AND run_id=? AND plan_revision_id=?
                """, taskId.value(), runId.value(), revisionId.value())) {
            throw new SQLException("Approved Plan Run binding is required before state initialization");
        }
        if (count(connection, """
                SELECT count(*) FROM plan_step_executions
                WHERE task_id=? AND plan_revision_id=?
                """, taskId.value(), revisionId.value()) != 0) {
            throw new SQLException("Plan Step state is already initialized");
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO plan_step_executions(task_id,plan_revision_id,step_revision_id,
                    state,transition_version,current_attempt_number,initialized_by_run_id,
                    last_updated_by_run_id,initialized_at,updated_at,provenance)
                SELECT task_id,plan_revision_id,step_revision_id,'PENDING',0,0,?,?,?, ?,?
                FROM plan_step_definitions
                WHERE task_id=? AND plan_revision_id=? ORDER BY step_order
                """)) {
            bind(statement, runId.value(), runId.value(), at.toString(), at.toString(),
                    provenance, taskId.value(), revisionId.value());
            int inserted = statement.executeUpdate();
            int expected = count(connection, """
                    SELECT count(*) FROM plan_step_definitions
                    WHERE task_id=? AND plan_revision_id=?
                    """, taskId.value(), revisionId.value());
            if (inserted == 0 || inserted != expected) {
                throw new SQLException("Incomplete Plan Step state initialization");
            }
        }
        refreshReady(connection, taskId, revisionId, runId, at);
    }

    static Status inspectRecoveryState(Connection connection, CanonicalId taskId,
            CanonicalId revisionId) throws SQLException {
        int definitions = count(connection, """
                SELECT count(*) FROM plan_step_definitions
                WHERE task_id=? AND plan_revision_id=?
                """, taskId.value(), revisionId.value());
        int states = count(connection, """
                SELECT count(*) FROM plan_step_executions
                WHERE task_id=? AND plan_revision_id=?
                """, taskId.value(), revisionId.value());
        if (definitions == 0 || states == 0) return Status.PLAN_STEP_STATE_UNAVAILABLE;
        if (definitions != states) return Status.PLAN_STEP_STATE_INTEGRITY_FAILURE;
        if (exists(connection, """
                SELECT 1 FROM plan_step_executions e
                WHERE e.task_id=? AND e.plan_revision_id=?
                  AND e.current_attempt_number>0
                  AND NOT EXISTS (
                    SELECT 1 FROM plan_step_attempts a
                    WHERE a.task_id=e.task_id AND a.plan_revision_id=e.plan_revision_id
                      AND a.step_revision_id=e.step_revision_id
                      AND a.attempt_number=e.current_attempt_number)
                """, taskId.value(), revisionId.value())) {
            return Status.PLAN_STEP_STATE_INTEGRITY_FAILURE;
        }
        if (exists(connection, """
                SELECT 1 FROM plan_step_executions e
                WHERE e.task_id=? AND e.plan_revision_id=? AND e.state='RUNNING'
                  AND NOT EXISTS (
                    SELECT 1 FROM plan_step_attempts a
                    WHERE a.task_id=e.task_id AND a.plan_revision_id=e.plan_revision_id
                      AND a.step_revision_id=e.step_revision_id
                      AND a.attempt_number=e.current_attempt_number AND a.state='RUNNING')
                """, taskId.value(), revisionId.value())) {
            return Status.PLAN_STEP_STATE_INTEGRITY_FAILURE;
        }
        if (exists(connection, """
                SELECT 1 FROM plan_step_attempts
                WHERE task_id=? AND plan_revision_id=? AND state IN ('RUNNING','UNKNOWN')
                """, taskId.value(), revisionId.value())) {
            return Status.PLAN_ATTEMPT_UNRESOLVED;
        }
        return Status.SUCCESS;
    }

    public RecoveryRead readRecovery(CanonicalId taskId, CanonicalId revisionId) {
        try {
            Status status = inspectRecoveryState(connection, taskId, revisionId);
            if (status != Status.SUCCESS) return new RecoveryRead(status, List.of(), List.of());
            return new RecoveryRead(Status.SUCCESS, readStates(taskId, revisionId),
                    readAttempts(taskId, revisionId));
        } catch (SQLException e) {
            throw new IllegalStateException("Plan execution Recovery read failed", e);
        }
    }

    /** Read-only live projection that accepts a structurally valid RUNNING Attempt. */
    public RecoveryRead readLive(CanonicalId taskId, CanonicalId revisionId) {
        try {
            Status status = inspectRecoveryState(connection, taskId, revisionId);
            if (status == Status.PLAN_ATTEMPT_UNRESOLVED) {
                boolean unknown = exists(connection, """
                        SELECT 1 FROM plan_step_attempts
                        WHERE task_id=? AND plan_revision_id=? AND state='UNKNOWN'
                        """, taskId.value(), revisionId.value());
                boolean orphanRunning = exists(connection, """
                        SELECT 1 FROM plan_step_attempts a
                        WHERE a.task_id=? AND a.plan_revision_id=? AND a.state='RUNNING'
                          AND NOT EXISTS (
                            SELECT 1 FROM plan_step_executions e
                            WHERE e.task_id=a.task_id
                              AND e.plan_revision_id=a.plan_revision_id
                              AND e.step_revision_id=a.step_revision_id
                              AND e.state='RUNNING'
                              AND e.current_attempt_number=a.attempt_number)
                        """, taskId.value(), revisionId.value());
                status = unknown ? Status.PLAN_ATTEMPT_UNRESOLVED
                        : orphanRunning ? Status.PLAN_STEP_STATE_INTEGRITY_FAILURE
                        : Status.SUCCESS;
            }
            if (status != Status.SUCCESS) return new RecoveryRead(status, List.of(), List.of());
            return new RecoveryRead(Status.SUCCESS, readStates(taskId, revisionId),
                    readAttempts(taskId, revisionId));
        } catch (SQLException e) {
            throw new IllegalStateException("Live Plan execution read failed", e);
        }
    }

    public List<StepExecution> readStates(CanonicalId taskId, CanonicalId revisionId) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT step_revision_id,state,transition_version,current_attempt_number,
                    initialized_by_run_id,last_updated_by_run_id,initialized_at,updated_at,
                    started_at,finished_at,bounded_reason,provenance
                FROM plan_step_executions WHERE task_id=? AND plan_revision_id=?
                ORDER BY (SELECT step_order FROM plan_step_definitions d
                    WHERE d.task_id=plan_step_executions.task_id
                      AND d.plan_revision_id=plan_step_executions.plan_revision_id
                      AND d.step_revision_id=plan_step_executions.step_revision_id)
                """)) {
            bind(statement, taskId.value(), revisionId.value());
            List<StepExecution> result = new ArrayList<>();
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) result.add(step(taskId, revisionId, row));
            }
            return List.copyOf(result);
        } catch (SQLException e) {
            throw new IllegalStateException("Plan Step state read failed", e);
        }
    }

    public List<Attempt> readAttempts(CanonicalId taskId, CanonicalId revisionId) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT step_revision_id,run_id,attempt_number,state,transition_version,
                    start_iteration,end_iteration,started_at,finished_at,failure_class,
                    bounded_reason,provenance
                FROM plan_step_attempts WHERE task_id=? AND plan_revision_id=?
                ORDER BY step_revision_id,attempt_number
                """)) {
            bind(statement, taskId.value(), revisionId.value());
            List<Attempt> result = new ArrayList<>();
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) result.add(attempt(taskId, revisionId, row));
            }
            return List.copyOf(result);
        } catch (SQLException e) {
            throw new IllegalStateException("Plan Step Attempt read failed", e);
        }
    }

    public Status validateRunningAttempt(ExecutionAuthoritySnapshot authority,
            CanonicalId revisionId, CanonicalId stepId, int attemptNumber) {
        try {
            CanonicalId bound=boundRevision(authority.taskId(),authority.runId());
            if(bound==null || !bound.equals(revisionId)) return Status.BINDING_NOT_FOUND;
            return exists(connection,"""
                    SELECT 1 FROM plan_step_attempts
                    WHERE task_id=? AND plan_revision_id=? AND step_revision_id=?
                      AND run_id=? AND attempt_number=? AND state='RUNNING'
                    """,authority.taskId().value(),revisionId.value(),stepId.value(),
                    authority.runId().value(),attemptNumber)
                    ?Status.SUCCESS:Status.PLAN_ATTEMPT_UNRESOLVED;
        } catch(SQLException e) {
            throw new IllegalStateException("Plan execution context validation failed",e);
        }
    }

    public StartResult startNextReadyAttempt(ExecutionAuthoritySnapshot authority,
            Instant at, int startIteration, String provenance) {
        return writeStart(authority, at, () -> {
            CanonicalId revisionId = boundRevision(authority.taskId(), authority.runId());
            if (revisionId == null) return StartResult.rejected(Status.BINDING_NOT_FOUND);
            Status integrity = inspectRecoveryState(connection, authority.taskId(), revisionId);
            if (integrity != Status.SUCCESS) return StartResult.rejected(integrity);
            refreshReady(connection, authority.taskId(), revisionId, authority.runId(), at);
            CanonicalId stepId = null;
            long expectedVersion = -1;
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT e.step_revision_id,e.transition_version
                    FROM plan_step_executions e
                    JOIN plan_step_definitions d
                      ON d.task_id=e.task_id AND d.plan_revision_id=e.plan_revision_id
                     AND d.step_revision_id=e.step_revision_id
                    WHERE e.task_id=? AND e.plan_revision_id=? AND e.state='READY'
                    ORDER BY d.step_order LIMIT 1
                    """)) {
                bind(statement, authority.taskId().value(), revisionId.value());
                try (ResultSet row = statement.executeQuery()) {
                    if (row.next()) {
                        stepId = CanonicalId.parse(row.getString(1));
                        expectedVersion = row.getLong(2);
                    }
                }
            }
            if (stepId == null) return StartResult.rejected(Status.NO_READY_PLAN_STEP);
            int attemptNumber = count(connection, """
                    SELECT COALESCE(max(attempt_number),0)+1 FROM plan_step_attempts
                    WHERE task_id=? AND plan_revision_id=? AND step_revision_id=?
                    """, authority.taskId().value(), revisionId.value(), stepId.value());
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO plan_step_attempts(task_id,plan_revision_id,step_revision_id,
                        run_id,attempt_number,state,transition_version,start_iteration,
                        started_at,provenance)
                    VALUES(?,?,?,?,?,'RUNNING',0,?,?,?)
                    """)) {
                bind(statement, authority.taskId().value(), revisionId.value(), stepId.value(),
                        authority.runId().value(), attemptNumber, startIteration, at.toString(),
                        provenance);
                statement.executeUpdate();
            }
            if (update("""
                    UPDATE plan_step_executions SET state='RUNNING',
                        transition_version=transition_version+1,
                        current_attempt_number=?,last_updated_by_run_id=?,updated_at=?,
                        started_at=COALESCE(started_at,?),finished_at=NULL,bounded_reason=NULL
                    WHERE task_id=? AND plan_revision_id=? AND step_revision_id=?
                        AND state='READY' AND transition_version=?
                    """, attemptNumber, authority.runId().value(), at.toString(), at.toString(),
                    authority.taskId().value(), revisionId.value(), stepId.value(),
                    expectedVersion) != 1) {
                return StartResult.rejected(Status.VERSION_CONFLICT);
            }
            StepExecution state = readStep(authority.taskId(), revisionId, stepId);
            Attempt attempt = readAttempt(authority.taskId(), revisionId, stepId,
                    authority.runId(), attemptNumber);
            return new StartResult(Status.SUCCESS, new AttemptStart(state, attempt));
        });
    }

    public Mutation completeAttempt(ExecutionAuthoritySnapshot authority, Instant at,
            Attempt attempt, Outcome outcome, int endIteration, long expectedStepVersion,
            String failureClass, String boundedReason) {
        Objects.requireNonNull(attempt);
        Objects.requireNonNull(outcome);
        return write(authority, at, () -> new Mutation(completeAttemptInTransaction(
                connection,authority,at,attempt,outcome,endIteration,expectedStepVersion,
                failureClass,boundedReason)));
    }

    static Status completeAttemptInTransaction(Connection connection,
            ExecutionAuthoritySnapshot authority,Instant at,Attempt attempt,Outcome outcome,
            int endIteration,long expectedStepVersion,String failureClass,String boundedReason)
            throws SQLException {
        return new PlanExecutionStore(connection).completeAttemptLocked(authority,at,attempt,
                outcome,endIteration,expectedStepVersion,failureClass,boundedReason);
    }

    private Status completeAttemptLocked(ExecutionAuthoritySnapshot authority, Instant at,
            Attempt attempt, Outcome outcome, int endIteration, long expectedStepVersion,
            String failureClass, String boundedReason) throws SQLException {
            if (!attempt.taskId().equals(authority.taskId())
                    || !attempt.runId().equals(authority.runId())) {
                return Status.RUN_MISMATCH;
            }
            CanonicalId revisionId = boundRevision(authority.taskId(), authority.runId());
            if (revisionId == null || !revisionId.equals(attempt.planRevisionId())) {
                return Status.BINDING_NOT_FOUND;
            }
            Status attemptCas=validateAttemptCompletion(attempt);
            if(attemptCas!=Status.SUCCESS)return attemptCas;
            Status stepCas=validateStepCompletion(attempt,expectedStepVersion);
            if(stepCas!=Status.SUCCESS)return stepCas;
            String attemptState = switch (outcome) {
                case SUCCEEDED -> "SUCCEEDED";
                case FAILED, RETRYABLE_FAILURE -> "FAILED";
                case BLOCKED -> "BLOCKED";
                case CANCELLED -> "CANCELLED";
                case UNKNOWN -> "UNKNOWN";
            };
            if (update("""
                    UPDATE plan_step_attempts SET state=?,transition_version=transition_version+1,
                        end_iteration=?,finished_at=?,failure_class=?,bounded_reason=?
                    WHERE task_id=? AND plan_revision_id=? AND step_revision_id=? AND run_id=?
                        AND attempt_number=? AND state='RUNNING' AND transition_version=?
                    """, attemptState, endIteration, at.toString(), bounded(failureClass, 64),
                    bounded(boundedReason, 512), authority.taskId().value(), revisionId.value(),
                    attempt.stepRevisionId().value(), authority.runId().value(),
                    attempt.attemptNumber(), attempt.transitionVersion()) != 1) {
                return Status.INTEGRITY_VIOLATION;
            }
            String stepState = switch (outcome) {
                case SUCCEEDED -> "SUCCEEDED";
                case FAILED -> "FAILED";
                case RETRYABLE_FAILURE -> "READY";
                case BLOCKED, UNKNOWN -> "BLOCKED";
                case CANCELLED -> "CANCELLED";
            };
            if (update("""
                    UPDATE plan_step_executions SET state=?,
                        transition_version=transition_version+1,last_updated_by_run_id=?,
                        updated_at=?,finished_at=?,bounded_reason=?
                    WHERE task_id=? AND plan_revision_id=? AND step_revision_id=?
                        AND state='RUNNING' AND current_attempt_number=?
                        AND transition_version=?
                    """, stepState, authority.runId().value(), at.toString(), at.toString(),
                    bounded(boundedReason, 512), authority.taskId().value(), revisionId.value(),
                        attempt.stepRevisionId().value(), attempt.attemptNumber(),
                        expectedStepVersion) != 1) {
                return Status.INTEGRITY_VIOLATION;
            }
            refreshReady(connection, authority.taskId(), revisionId, authority.runId(), at);
            return Status.SUCCESS;
    }

    public boolean allSucceeded(CanonicalId taskId, CanonicalId revisionId) {
        try {
            int total = count(connection, """
                    SELECT count(*) FROM plan_step_executions
                    WHERE task_id=? AND plan_revision_id=?
                    """, taskId.value(), revisionId.value());
            int succeeded = count(connection, """
                    SELECT count(*) FROM plan_step_executions
                    WHERE task_id=? AND plan_revision_id=? AND state='SUCCEEDED'
                    """, taskId.value(), revisionId.value());
            return total > 0 && total == succeeded;
        } catch (SQLException e) {
            throw new IllegalStateException("Plan completion-state read failed", e);
        }
    }

    private StartResult writeStart(ExecutionAuthoritySnapshot authority, Instant at,
            StartWork work) {
        try {
            if (!connection.getAutoCommit()) throw new SQLException("Nested Step transaction");
            connection.setAutoCommit(false);
            try {
                Status locked = lockAuthority(authority, at);
                if (locked != Status.SUCCESS) {
                    connection.rollback();
                    return StartResult.rejected(locked);
                }
                StartResult result = work.run();
                if (result.status() == Status.SUCCESS) connection.commit();
                else connection.rollback();
                return result;
            } catch (SQLException e) {
                connection.rollback();
                if (e.getErrorCode() == 19) return StartResult.rejected(Status.INTEGRITY_VIOLATION);
                throw e;
            } catch (RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Plan Attempt persistence failed", e);
        }
    }

    private Mutation write(ExecutionAuthoritySnapshot authority, Instant at, MutationWork work) {
        try {
            if (!connection.getAutoCommit()) throw new SQLException("Nested Step transaction");
            connection.setAutoCommit(false);
            try {
                Status locked = lockAuthority(authority, at);
                if (locked != Status.SUCCESS) {
                    connection.rollback();
                    return new Mutation(locked);
                }
                Mutation result = work.run();
                if (result.status() == Status.SUCCESS) connection.commit();
                else connection.rollback();
                return result;
            } catch (SQLException e) {
                connection.rollback();
                if (e.getErrorCode() == 19) return new Mutation(Status.INTEGRITY_VIOLATION);
                throw e;
            } catch (RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Plan Step mutation failed", e);
        }
    }

    private Status lockAuthority(ExecutionAuthoritySnapshot authority, Instant at)
            throws SQLException {
        if (update("""
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

    private CanonicalId boundRevision(CanonicalId taskId, CanonicalId runId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT b.plan_revision_id FROM plan_run_bindings b
                JOIN task_plan_authorities active ON active.task_id=b.task_id
                    AND active.active_plan_revision_id=b.plan_revision_id
                JOIN plan_approvals a ON a.task_id=b.task_id
                    AND a.plan_revision_id=b.plan_revision_id AND a.approval_id=b.approval_id
                WHERE b.task_id=? AND b.run_id=? AND b.approval_decision='APPROVED'
                    AND a.decision='APPROVED'
                """)) {
            bind(statement, taskId.value(), runId.value());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                CanonicalId value = CanonicalId.parse(row.getString(1));
                if (row.next()) throw new SQLException("Run has multiple Plan bindings");
                return value;
            }
        }
    }

    private static void refreshReady(Connection connection, CanonicalId taskId,
            CanonicalId revisionId, CanonicalId runId, Instant at) throws SQLException {
        try (PreparedStatement blocked = connection.prepareStatement("""
                UPDATE plan_step_executions AS e
                SET state='BLOCKED',transition_version=transition_version+1,
                    last_updated_by_run_id=?,updated_at=?,finished_at=?,
                    bounded_reason='DEPENDENCY_TERMINAL_WITHOUT_SUCCESS'
                WHERE e.task_id=? AND e.plan_revision_id=? AND e.state='PENDING'
                  AND EXISTS (
                    SELECT 1 FROM plan_step_dependencies d
                    JOIN plan_step_executions predecessor
                      ON predecessor.task_id=d.task_id
                     AND predecessor.plan_revision_id=d.plan_revision_id
                     AND predecessor.step_revision_id=d.depends_on_step_revision_id
                    WHERE d.task_id=e.task_id AND d.plan_revision_id=e.plan_revision_id
                      AND d.step_revision_id=e.step_revision_id
                      AND predecessor.state IN
                        ('FAILED','BLOCKED','CANCELLED','SKIPPED'))
                """)) {
            bind(blocked, runId.value(), at.toString(), at.toString(),
                    taskId.value(), revisionId.value());
            blocked.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE plan_step_executions AS e
                SET state='READY',transition_version=transition_version+1,
                    last_updated_by_run_id=?,updated_at=?
                WHERE e.task_id=? AND e.plan_revision_id=? AND e.state='PENDING'
                  AND NOT EXISTS (
                    SELECT 1 FROM plan_step_dependencies d
                    JOIN plan_step_executions predecessor
                      ON predecessor.task_id=d.task_id
                     AND predecessor.plan_revision_id=d.plan_revision_id
                     AND predecessor.step_revision_id=d.depends_on_step_revision_id
                    WHERE d.task_id=e.task_id AND d.plan_revision_id=e.plan_revision_id
                      AND d.step_revision_id=e.step_revision_id
                      AND predecessor.state<>'SUCCEEDED')
                """)) {
            bind(statement, runId.value(), at.toString(), taskId.value(), revisionId.value());
            statement.executeUpdate();
        }
    }

    private StepExecution readStep(CanonicalId taskId, CanonicalId revisionId,
            CanonicalId stepId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT step_revision_id,state,transition_version,current_attempt_number,
                    initialized_by_run_id,last_updated_by_run_id,initialized_at,updated_at,
                    started_at,finished_at,bounded_reason,provenance
                FROM plan_step_executions
                WHERE task_id=? AND plan_revision_id=? AND step_revision_id=?
                """)) {
            bind(statement, taskId.value(), revisionId.value(), stepId.value());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("Plan Step state disappeared");
                return step(taskId, revisionId, row);
            }
        }
    }

    private Attempt readAttempt(CanonicalId taskId, CanonicalId revisionId,
            CanonicalId stepId, CanonicalId runId, int number) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT step_revision_id,run_id,attempt_number,state,transition_version,
                    start_iteration,end_iteration,started_at,finished_at,failure_class,
                    bounded_reason,provenance FROM plan_step_attempts
                WHERE task_id=? AND plan_revision_id=? AND step_revision_id=?
                    AND run_id=? AND attempt_number=?
                """)) {
            bind(statement, taskId.value(), revisionId.value(), stepId.value(),
                    runId.value(), number);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("Plan Attempt disappeared");
                return attempt(taskId, revisionId, row);
            }
        }
    }

    private Status classifyAttempt(Attempt attempt) throws SQLException {
        return exists(connection, """
                SELECT 1 FROM plan_step_attempts WHERE task_id=? AND plan_revision_id=?
                    AND step_revision_id=? AND run_id=? AND attempt_number=?
                """, attempt.taskId().value(), attempt.planRevisionId().value(),
                attempt.stepRevisionId().value(), attempt.runId().value(),
                attempt.attemptNumber()) ? Status.INVALID_STATE : Status.TARGET_NOT_FOUND;
    }

    private Status validateAttemptCompletion(Attempt attempt)throws SQLException{
        try(PreparedStatement statement=connection.prepareStatement("""
                SELECT state,transition_version FROM plan_step_attempts
                WHERE task_id=? AND plan_revision_id=? AND step_revision_id=?
                  AND run_id=? AND attempt_number=?
                """)){
            bind(statement,attempt.taskId().value(),attempt.planRevisionId().value(),
                    attempt.stepRevisionId().value(),attempt.runId().value(),
                    attempt.attemptNumber());
            try(ResultSet row=statement.executeQuery()){
                if(!row.next())return Status.TARGET_NOT_FOUND;
                if(row.getLong(2)!=attempt.transitionVersion())
                    return Status.VERSION_CONFLICT;
                return "RUNNING".equals(row.getString(1))
                        ?Status.SUCCESS:Status.INVALID_STATE;
            }
        }
    }

    private Status validateStepCompletion(Attempt attempt,long expectedVersion)
            throws SQLException{
        try(PreparedStatement statement=connection.prepareStatement("""
                SELECT state,current_attempt_number,transition_version
                FROM plan_step_executions
                WHERE task_id=? AND plan_revision_id=? AND step_revision_id=?
                """)){
            bind(statement,attempt.taskId().value(),attempt.planRevisionId().value(),
                    attempt.stepRevisionId().value());
            try(ResultSet row=statement.executeQuery()){
                if(!row.next())return Status.TARGET_NOT_FOUND;
                if(row.getLong(3)!=expectedVersion)return Status.VERSION_CONFLICT;
                return "RUNNING".equals(row.getString(1))
                        &&row.getInt(2)==attempt.attemptNumber()
                        ?Status.SUCCESS:Status.INVALID_STATE;
            }
        }
    }

    private Status classifyStep(Attempt attempt,long expectedVersion) throws SQLException {
        try(PreparedStatement statement=connection.prepareStatement("""
                SELECT state,current_attempt_number,transition_version
                FROM plan_step_executions
                WHERE task_id=? AND plan_revision_id=? AND step_revision_id=?
                """)){
            bind(statement,attempt.taskId().value(),attempt.planRevisionId().value(),
                    attempt.stepRevisionId().value());
            try(ResultSet row=statement.executeQuery()){
                if(!row.next())return Status.TARGET_NOT_FOUND;
                if(row.getLong(3)!=expectedVersion)return Status.VERSION_CONFLICT;
                if(!"RUNNING".equals(row.getString(1))
                        ||row.getInt(2)!=attempt.attemptNumber())return Status.INVALID_STATE;
                return Status.INTEGRITY_VIOLATION;
            }
        }
    }

    private static StepExecution step(CanonicalId taskId, CanonicalId revisionId,
            ResultSet row) throws SQLException {
        return new StepExecution(taskId, revisionId, CanonicalId.parse(row.getString(1)),
                StepState.valueOf(row.getString(2)), row.getLong(3), row.getInt(4),
                CanonicalId.parse(row.getString(5)), CanonicalId.parse(row.getString(6)),
                Instant.parse(row.getString(7)), Instant.parse(row.getString(8)),
                instant(row.getString(9)), instant(row.getString(10)), row.getString(11),
                row.getString(12));
    }

    private static Attempt attempt(CanonicalId taskId, CanonicalId revisionId,
            ResultSet row) throws SQLException {
        return new Attempt(taskId, revisionId, CanonicalId.parse(row.getString(1)),
                CanonicalId.parse(row.getString(2)), row.getInt(3),
                AttemptState.valueOf(row.getString(4)), row.getLong(5), row.getInt(6),
                row.getObject(7) == null ? null : row.getInt(7),
                Instant.parse(row.getString(8)), instant(row.getString(9)),
                row.getString(10), row.getString(11), row.getString(12));
    }

    private int update(String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            return statement.executeUpdate();
        }
    }

    private static int count(Connection connection, String sql, Object... values)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("Expected aggregate result");
                return row.getInt(1);
            }
        }
    }

    private static boolean exists(Connection connection, String sql, Object... values)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet row = statement.executeQuery()) {
                return row.next();
            }
        }
    }

    private static void bind(PreparedStatement statement, Object... values)
            throws SQLException {
        for (int index = 0; index < values.length; index++) {
            statement.setObject(index + 1, values[index]);
        }
    }

    private static Instant instant(String value) {
        return value == null ? null : Instant.parse(value);
    }

    private static String bounded(String value, int limit) {
        if (value == null) return null;
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    @FunctionalInterface
    private interface MutationWork {
        Mutation run() throws SQLException;
    }

    @FunctionalInterface
    private interface StartWork {
        StartResult run() throws SQLException;
    }
}
