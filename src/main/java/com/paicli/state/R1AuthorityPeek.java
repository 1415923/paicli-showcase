package com.paicli.state;

import com.paicli.identity.CanonicalId;
import com.paicli.state.FollowUpModels.Candidate;
import com.paicli.state.FollowUpModels.PendingChildStart;
import com.paicli.state.PlanCompletionStore.RecoveryRead;
import com.paicli.state.PlanExecutionModels.Attempt;
import com.paicli.state.PlanExecutionModels.StepExecution;
import com.paicli.state.SteeringModels.Queue;
import com.paicli.state.StructuredPlanModels.ApprovedPlan;
import com.paicli.state.StructuredPlanModels.PlanApplicability;
import com.paicli.state.StructuredPlanModels.Revision;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * R2 side-effect-free read facade over R1 validators. Methods are safe inside an existing
 * read snapshot and never acquire authority, claim work, clean candidates, or mutate state.
 */
public final class R1AuthorityPeek {
    private static final List<String> TRUSTED_CHECKPOINT_STATES =
            List.of("RUNNING", "SEGMENT_FAILED");

    private final Connection connection;
    private final R1aDurableStore durable;
    private final PlanAuthorityStore plans;
    private final PlanExecutionStore executions;
    private final PlanCompletionStore completions;
    private final SteeringStore steerings;
    private final FollowUpStore followUps;
    private final SchemaIntegrityChecker integrity;
    private final com.paicli.runtime.RecoveryPreflightValidator recoveryPreflight;

    public R1AuthorityPeek(Connection connection, R1aDurableStore durable,
            PlanAuthorityStore plans, PlanExecutionStore executions,
            PlanCompletionStore completions, SteeringStore steerings,
            FollowUpStore followUps, SchemaIntegrityChecker integrity,
            com.paicli.runtime.RecoveryPreflightValidator recoveryPreflight) {
        this.connection = Objects.requireNonNull(connection);
        this.durable = Objects.requireNonNull(durable);
        this.plans = Objects.requireNonNull(plans);
        this.executions = Objects.requireNonNull(executions);
        this.completions = Objects.requireNonNull(completions);
        this.steerings = Objects.requireNonNull(steerings);
        this.followUps = Objects.requireNonNull(followUps);
        this.integrity = Objects.requireNonNull(integrity);
        this.recoveryPreflight=Objects.requireNonNull(recoveryPreflight);
    }

    public AuthorityPeek<SchemaIntegrityChecker.IntegrityReport> peekManifestIntegrity(
            CanonicalId taskId) {
        try {
            SchemaIntegrityChecker.IntegrityReport report = integrity.check();
            SchemaIntegrityChecker.Finding blocking = report.findings().stream()
                    .filter(finding -> finding.severity()
                            == SchemaIntegrityChecker.Severity.ERROR)
                    .filter(finding -> !finding.entityLocation().startsWith("provider_call_telemetry")
                            && !finding.entityLocation().startsWith("idx_v25_provider_call_"))
                    .findFirst().orElse(null);
            if (blocking != null) return AuthorityPeek.integrityFailure(
                    blocking.code(), report);
            if (taskId != null && durable.findManifestByTask(taskId).isEmpty()) {
                return AuthorityPeek.integrityFailure("MANIFEST_AUTHORITY_MISSING", report);
            }
            return AuthorityPeek.present(report);
        } catch (SQLException | RuntimeException error) {
            return AuthorityPeek.unavailable("MANIFEST_INTEGRITY_UNAVAILABLE");
        }
    }

    public AuthorityPeek<PlanPeek> peekPlanAuthority(CanonicalId taskId) {
        try {
            PlanApplicability applicability = applicability(taskId);
            PlanAuthorityStore.Read planRead = plans.readActive(taskId);
            if (applicability == PlanApplicability.PLAN_REQUIRED) {
                if (planRead.status() == PlanAuthorityStore.Status.NO_APPROVED_PLAN) {
                    return AuthorityPeek.present(new PlanPeek(applicability, null,
                            List.of(), List.of(), null, pointerVersion(taskId)));
                }
                if (planRead.status() != PlanAuthorityStore.Status.SUCCESS) {
                    return AuthorityPeek.integrityFailure(planRead.status().name(), null);
                }
                ApprovedPlan approved = planRead.plan();
                PlanExecutionStore.RecoveryRead execution = executions.readLive(
                        taskId, approved.revision().planRevisionId());
                if (execution.status() != PlanExecutionStore.Status.SUCCESS) {
                    return AuthorityPeek.integrityFailure(execution.status().name(), null);
                }
                RecoveryRead completion = completions.readRecovery(
                        taskId, approved.revision().planRevisionId());
                if (completion.status() != PlanCompletionStore.Status.SUCCESS) {
                    return AuthorityPeek.integrityFailure(completion.status().name(), null);
                }
                return AuthorityPeek.present(new PlanPeek(applicability, approved,
                        execution.states(), execution.attempts(), completion,
                        pointerVersion(taskId)));
            }
            if (planRead.status() == PlanAuthorityStore.Status.PLAN_REVISION_INTEGRITY_FAILURE) {
                return AuthorityPeek.integrityFailure(planRead.status().name(), null);
            }
            return AuthorityPeek.present(new PlanPeek(applicability, null,
                    List.of(), List.of(), null, pointerVersion(taskId)));
        } catch (SQLException | RuntimeException error) {
            return AuthorityPeek.unavailable("PLAN_AUTHORITY_UNAVAILABLE");
        }
    }

    public AuthorityPeek<RecoveryRead> peekCompletionAuthority(CanonicalId taskId) {
        AuthorityPeek<PlanPeek> plan = peekPlanAuthority(taskId);
        if (plan.status() == PeekStatus.INTEGRITY_FAILURE) {
            return AuthorityPeek.integrityFailure(plan.code(), null);
        }
        if (plan.status() != PeekStatus.PRESENT) {
            return AuthorityPeek.unavailable("COMPLETION_AUTHORITY_UNAVAILABLE");
        }
        return plan.value().completion() == null
                ? AuthorityPeek.notApplicable("PLAN_NOT_REQUIRED")
                : AuthorityPeek.present(plan.value().completion());
    }

    public AuthorityPeek<Optional<Revision>> peekLatestPlanRevision(CanonicalId taskId) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT plan_revision_id FROM plan_revisions
                WHERE task_id=? ORDER BY revision_number DESC LIMIT 1
                """)) {
            statement.setString(1, taskId.value());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return AuthorityPeek.present(Optional.empty());
                CanonicalId revisionId = CanonicalId.parse(row.getString(1));
                PlanAuthorityStore.RevisionRead read =
                        plans.readRevisionDefinition(taskId, revisionId);
                if (read.status() == PlanAuthorityStore.Status.SUCCESS) {
                    return AuthorityPeek.present(Optional.of(read.revision()));
                }
                return AuthorityPeek.integrityFailure(read.status().name(),
                        Optional.empty());
            }
        } catch (SQLException | RuntimeException error) {
            return AuthorityPeek.unavailable("PLAN_REVISION_UNAVAILABLE");
        }
    }

    public AuthorityPeek<Optional<Queue>> peekSteeringAuthority(CanonicalId taskId) {
        try {
            return AuthorityPeek.present(steerings.oldestOpen(taskId));
        } catch (SQLException | RuntimeException error) {
            return AuthorityPeek.unavailable("STEERING_AUTHORITY_UNAVAILABLE");
        }
    }

    public AuthorityPeek<Optional<Candidate>> peekFollowUpAuthority(CanonicalId threadId) {
        try {
            return AuthorityPeek.present(followUps.peekPending(threadId));
        } catch (SQLException error) {
            return AuthorityPeek.unavailable("FOLLOW_UP_AUTHORITY_UNAVAILABLE");
        } catch (RuntimeException error) {
            return AuthorityPeek.integrityFailure("FOLLOW_UP_AUTHORITY_INTEGRITY_FAILURE",
                    Optional.empty());
        }
    }

    public AuthorityPeek<Optional<PendingChildStart>> peekChildInitialRunAuthority(
            CanonicalId threadId, Instant at) {
        try {
            return AuthorityPeek.present(
                    followUps.peekRecoverableAdmittedChild(threadId, at));
        } catch (SQLException error) {
            return AuthorityPeek.unavailable("CHILD_START_AUTHORITY_UNAVAILABLE");
        } catch (RuntimeException error) {
            return AuthorityPeek.integrityFailure("CHILD_START_AUTHORITY_INTEGRITY_FAILURE",
                    Optional.empty());
        }
    }

    public AuthorityPeek<RecoveryPeek> peekRecoveryAuthority(
            CanonicalId taskId, Instant at) {
        try {
            var preflight=recoveryPreflight.validate(taskId,at);
            if(preflight.eligibility()==com.paicli.runtime.RecoveryPreflightValidator
                    .Eligibility.INTEGRITY_FAILURE)
                return AuthorityPeek.integrityFailure(preflight.status().name(),null);
            if(preflight.eligibility()==com.paicli.runtime.RecoveryPreflightValidator
                    .Eligibility.UNAVAILABLE
                    ||preflight.eligibility()==com.paicli.runtime.RecoveryPreflightValidator
                    .Eligibility.UNKNOWN)
                return AuthorityPeek.unavailable(preflight.reasons().isEmpty()
                        ?"RECOVERY_PREFLIGHT_UNAVAILABLE":preflight.reasons().getFirst());
            if(preflight.eligibility()!=com.paicli.runtime.RecoveryPreflightValidator
                    .Eligibility.ELIGIBLE)
                return AuthorityPeek.present(new RecoveryPeek(preflight.status().name(),false,
                        preflight.reasons()));
            AuthorityPeek<PlanPeek> plan = peekPlanAuthority(taskId);
            if (plan.status() == PeekStatus.INTEGRITY_FAILURE) {
                return AuthorityPeek.integrityFailure(plan.code(), null);
            }
            if (plan.status() != PeekStatus.PRESENT) {
                return AuthorityPeek.unavailable("RECOVERY_PLAN_UNAVAILABLE");
            }
            AuthorityPeek<Optional<Queue>> steering = peekSteeringAuthority(taskId);
            if (steering.status() != PeekStatus.PRESENT) {
                return AuthorityPeek.unavailable("RECOVERY_STEERING_UNAVAILABLE");
            }
            return AuthorityPeek.present(new RecoveryPeek(
                    "ELIGIBLE", true,List.of("MUTATION_REVALIDATION_REQUIRED")));
        } catch (RuntimeException error) {
            return AuthorityPeek.unavailable("RECOVERY_AUTHORITY_UNAVAILABLE");
        }
    }

    private PlanApplicability applicability(CanonicalId taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT applicability FROM task_plan_applicability WHERE task_id=?")) {
            statement.setString(1, taskId.value());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? PlanApplicability.valueOf(row.getString(1))
                        : PlanApplicability.LEGACY_PLAN_ONLY;
            }
        }
    }

    private long pointerVersion(CanonicalId taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT pointer_version FROM task_plan_authorities WHERE task_id=?")) {
            statement.setString(1, taskId.value());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getLong(1) : 0;
            }
        }
    }

    private boolean trustedCheckpoint(CanonicalId taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT status FROM checkpoints WHERE task_id=?
                ORDER BY created_at DESC LIMIT 1
                """)) {
            statement.setString(1, taskId.value());
            try (ResultSet row = statement.executeQuery()) {
                return row.next() && TRUSTED_CHECKPOINT_STATES.contains(row.getString(1));
            }
        }
    }

    private static boolean isTerminal(String status) {
        return List.of("COMPLETED", "FAILED", "CANCELLED").contains(status);
    }

    public enum PeekStatus {
        PRESENT, ABSENT, NOT_APPLICABLE, INTEGRITY_FAILURE, UNAVAILABLE
    }

    public record AuthorityPeek<T>(PeekStatus status, T value, String code) {
        public AuthorityPeek { Objects.requireNonNull(status); }
        static <T> AuthorityPeek<T> present(T value) {
            return new AuthorityPeek<>(PeekStatus.PRESENT, value, null);
        }
        static <T> AuthorityPeek<T> absent(String code) {
            return new AuthorityPeek<>(PeekStatus.ABSENT, null, code);
        }
        static <T> AuthorityPeek<T> notApplicable(String code) {
            return new AuthorityPeek<>(PeekStatus.NOT_APPLICABLE, null, code);
        }
        static <T> AuthorityPeek<T> integrityFailure(String code, T value) {
            return new AuthorityPeek<>(PeekStatus.INTEGRITY_FAILURE, value, code);
        }
        static <T> AuthorityPeek<T> unavailable(String code) {
            return new AuthorityPeek<>(PeekStatus.UNAVAILABLE, null, code);
        }
    }

    public record PlanPeek(PlanApplicability applicability, ApprovedPlan approvedPlan,
            List<StepExecution> steps, List<Attempt> attempts,
            RecoveryRead completion, long pointerVersion) {
        public PlanPeek {
            steps = List.copyOf(steps);
            attempts = List.copyOf(attempts);
        }
    }

    public record RecoveryPeek(String status, boolean eligible, List<String> reasons) {
        public RecoveryPeek { reasons = List.copyOf(reasons); }
    }
}
