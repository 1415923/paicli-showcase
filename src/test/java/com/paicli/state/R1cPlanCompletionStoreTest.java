package com.paicli.state;

import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.state.PlanCompletionModels.CompletionResult;
import com.paicli.state.PlanExecutionModels.Outcome;
import com.paicli.state.R1aDurableModels.DurableRun;
import com.paicli.state.R1aDurableModels.DurableTask;
import com.paicli.state.R1aDurableModels.ExecutionAuthoritySnapshot;
import com.paicli.state.R1aDurableModels.SideEffectOccurrence;
import com.paicli.state.R1aDurableModels.TaskContextGeneration;
import com.paicli.state.R1aDurableModels.TaskContextPointer;
import com.paicli.state.R1aDurableModels.WorkThread;
import com.paicli.state.R1aStoreResults.CreateResult;
import com.paicli.state.R1aStoreResults.FencedMutationResult;
import com.paicli.state.R1aStoreResults.ManifestCommitResult;
import com.paicli.state.R1aStoreResults.OwnershipResult;
import com.paicli.state.StructuredPlanModels.Revision;
import com.paicli.state.StructuredPlanModels.StepDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class R1cPlanCompletionStoreTest {
    @TempDir Path tempDir;
    private static final Instant AT = Instant.parse("2026-07-19T00:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Test void v22IsAdditiveIdempotentRollsBackAndDoesNotConvertLegacy() throws Exception {
        try (DatabaseManager manager = new DatabaseManager(tempDir.resolve("v22-upgrade"))) {
            Connection connection = manager.getConnection();
            MigrationRunner runner = new MigrationRunner(connection);
            runner.runMigrationsThrough(21);
            execute(connection, "INSERT INTO tasks(task_id,goal,status) VALUES('legacy','goal','RUNNING')");
            execute(connection, "INSERT INTO coding_runs(run_id,task_id,run_kind,phase) "
                    + "VALUES('legacy-run','legacy','INITIAL','EXECUTING')");
            execute(connection, "INSERT INTO task_plans(plan_id,run_id,task_id,plan_text,status) "
                    + "VALUES('legacy-plan','legacy-run','legacy','text','ACTIVE')");
            assertFalse(objectExists(connection, "plan_evidence"));
            runner.runMigrations();
            assertEquals(33, scalar(connection, "SELECT max(version) FROM schema_migrations"));
            assertTrue(objectExists(connection, "plan_evidence"));
            assertTrue(objectExists(connection, "plan_completion_rules"));
            assertTrue(objectExists(connection, "task_completion_decisions"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM task_plans"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM plan_evidence"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM plan_completion_rules"));
            runner.runMigrations();
            assertEquals(1, scalar(connection,
                    "SELECT count(*) FROM schema_migrations WHERE version=22"));
        }
        try (DatabaseManager manager = new DatabaseManager(tempDir.resolve("v22-rollback"))) {
            Connection connection = manager.getConnection();
            MigrationRunner runner = new MigrationRunner(connection);
            runner.runMigrationsThrough(21);
            execute(connection, "CREATE TABLE plan_completion_rules(blocker TEXT)");
            assertThrows(SQLException.class, runner::runMigrations);
            assertEquals(21, scalar(connection, "SELECT max(version) FROM schema_migrations"));
            assertFalse(objectExists(connection, "plan_evidence"));
            assertFalse(objectExists(connection, "task_completion_decisions"));
            assertFalse(columnExists(connection, "plan_revisions",
                    "canonical_schema_version"));
        }
    }

    @Test void v21RevisionKeepsV1HashAndGetsExplicitLegacyCompletionResult() throws Exception {
        Path path = tempDir.resolve("v21-canonical-upgrade");
        CanonicalId taskId;
        CanonicalId revisionId;
        String originalHash;
        try (DatabaseManager manager = new DatabaseManager(path)) {
            Connection connection = manager.getConnection();
            MigrationRunner runner = new MigrationRunner(connection);
            runner.runMigrationsThrough(21);
            R1aDurableStore durable = new R1aDurableStore(connection);
            WorkThread thread = new WorkThread(CanonicalId.newId(EntityFamily.THREAD),
                    "workspace", "goal", "OPEN", AT, AT);
            DurableTask task = new DurableTask(CanonicalId.newId(EntityFamily.TASK), thread.id(),
                    null, "goal", "CREATED", null, null, AT, null);
            assertEquals(CreateResult.SUCCESS, durable.createThread(thread));
            assertEquals(CreateResult.SUCCESS, durable.createTask(task));
            InstructionManifest manifest = commitManifest(durable, task.id());
            DurableRun run = new DurableRun(CanonicalId.newId(EntityFamily.RUN), task.id(),
                    thread.id(), "INITIAL", null, manifest.id(), manifest.canonicalHash(), AT, null);
            assertEquals(CreateResult.SUCCESS, durable.createRun(run));
            assertEquals(OwnershipResult.SUCCESS, durable.acquireOwnership(task.id(), "owner",
                    run.id(), "lease", AT, AT.plusSeconds(120)));
            taskId = task.id();
            revisionId = CanonicalId.newId(EntityFamily.PLAN);
            CanonicalId stepId = CanonicalId.newId(EntityFamily.PLAN_STEP);
            StepDefinition legacyStep = new StepDefinition(stepId,
                    CanonicalId.newId(EntityFamily.PLAN_STEP), 0, "legacy", "definition",
                    "b".repeat(64));
            Revision legacy = new Revision(revisionId, taskId, 1, "goal", "accept", "", "",
                    "verify", HASH, "MODEL", "V21", run.id(), AT,
                    List.of(legacyStep), List.of(), List.of(), Revision.CANONICAL_SCHEMA_V1);
            originalHash = PlanCanonicalizer.hash(legacy);
            execute(connection, """
                    INSERT INTO plan_revisions(plan_revision_id,task_id,revision_number,goal,
                        acceptance_criteria,assumptions,risks,verification_strategy,canonical_hash,
                        source_type,provenance,created_by_run_id,created_at)
                    VALUES('%s','%s',1,'goal','accept','','','verify','%s','MODEL','V21','%s','%s')
                    """.formatted(revisionId.value(), taskId.value(), originalHash,
                    run.id().value(), AT));
            execute(connection, """
                    INSERT INTO plan_step_definitions(step_revision_id,logical_step_id,
                        plan_revision_id,task_id,step_order,title,definition,canonical_hash)
                    VALUES('%s','%s','%s','%s',0,'legacy','definition','%s')
                    """.formatted(stepId.value(), legacyStep.logicalStepId().value(),
                    revisionId.value(), taskId.value(), legacyStep.canonicalHash()));
            execute(connection, """
                    INSERT INTO plan_revision_lifecycle(plan_revision_id,task_id,state,cas_version,
                        updated_by_run_id,updated_at)
                    VALUES('%s','%s','APPROVED',2,'%s','%s')
                    """.formatted(revisionId.value(), taskId.value(), run.id().value(), AT));
            execute(connection, """
                    INSERT INTO plan_approvals(approval_id,task_id,plan_revision_id,decision,
                        approved_by,approved_by_run_id,policy_version,decided_at)
                    VALUES('approval-v21','%s','%s','APPROVED','user','%s','v21','%s')
                    """.formatted(taskId.value(), revisionId.value(), run.id().value(), AT));
            execute(connection, """
                    INSERT INTO task_plan_authorities(task_id,active_plan_revision_id,
                        active_revision_state,active_approval_id,active_approval_decision,
                        activated_by_run_id,pointer_version,activated_at)
                    VALUES('%s','%s','APPROVED','approval-v21','APPROVED','%s',1,'%s')
                    """.formatted(taskId.value(), revisionId.value(), run.id().value(), AT));
            execute(connection, """
                    INSERT INTO plan_run_bindings(binding_id,task_id,run_id,plan_revision_id,
                        approval_id,approval_decision,plan_hash,bound_at)
                    VALUES('binding-v21','%s','%s','%s','approval-v21','APPROVED','%s','%s')
                    """.formatted(taskId.value(), run.id().value(), revisionId.value(),
                    originalHash, AT));
            execute(connection, """
                    INSERT INTO plan_step_executions(task_id,plan_revision_id,step_revision_id,
                        state,transition_version,current_attempt_number,initialized_by_run_id,
                        last_updated_by_run_id,initialized_at,updated_at,provenance)
                    VALUES('%s','%s','%s','READY',0,0,'%s','%s','%s','%s','V21')
                    """.formatted(taskId.value(), revisionId.value(), stepId.value(),
                    run.id().value(), run.id().value(), AT, AT));

            runner.runMigrations();
            runner.runMigrations();
            assertEquals(1, scalar(connection,
                    "SELECT canonical_schema_version FROM plan_revisions"));
            assertEquals(originalHash, text(connection,
                    "SELECT canonical_hash FROM plan_revisions"));
            assertEquals(3, scalar(connection, """
                    SELECT (completion_rule IS NULL)
                         + (required_evidence_kinds IS NULL)
                         + (effect_class IS NULL)
                    FROM plan_step_definitions
                    """));
            var active = new PlanAuthorityStore(connection).readActive(taskId);
            assertEquals(PlanAuthorityStore.Status.SUCCESS, active.status());
            assertEquals(Revision.CANONICAL_SCHEMA_V1,
                    active.plan().revision().canonicalSchemaVersion());
            assertEquals(PlanExecutionStore.Status.SUCCESS,
                    new PlanExecutionStore(connection).readRecovery(taskId, revisionId).status());
            PlanCompletionStore completions = new PlanCompletionStore(connection);
            assertEquals(CompletionResult.LEGACY_COMPLETION_PROFILE_REQUIRED,
                    completions.readRecovery(taskId, revisionId).result());
            ExecutionAuthoritySnapshot authority = new ExecutionAuthoritySnapshot(
                    taskId, run.id(), "owner", "lease", 1, 1);
            var gate = completions.evaluateAndComplete(
                    authority, AT.plusSeconds(1), revisionId);
            assertEquals(PlanCompletionStore.Status.SUCCESS, gate.status());
            assertEquals(CompletionResult.LEGACY_COMPLETION_PROFILE_REQUIRED, gate.result());
            assertEquals(0, scalar(connection,
                    "SELECT count(*) FROM task_completion_decisions"));
        }
        try (DatabaseManager reopened = new DatabaseManager(path)) {
            assertEquals(PlanAuthorityStore.Status.SUCCESS,
                    new PlanAuthorityStore(reopened.getConnection()).readActive(taskId).status());
            var recovery = new PlanCompletionStore(reopened.getConnection())
                    .readRecovery(taskId, revisionId);
            assertEquals(PlanCompletionStore.Status.SUCCESS, recovery.status());
            assertEquals(CompletionResult.LEGACY_COMPLETION_PROFILE_REQUIRED, recovery.result());
            assertEquals(originalHash, text(reopened.getConnection(),
                    "SELECT canonical_hash FROM plan_revisions"));
        }
    }

    @Test void toolClaimCreatesImmutableEvidenceAndCompletionGateIsAtomic() throws Exception {
        try (Fixture f = fixture("tool-gate")) {
            var started = start(f);
            CanonicalId occurrenceId = completedToolOccurrence(f, "tool-once", 5);
            assertEquals(1, f.completions.readEvidence(
                    f.task.id(), f.revision.planRevisionId()).size());
            assertTrue(f.completions.readEvidence(f.task.id(),
                    f.revision.planRevisionId()).getFirst().integrityValid());
            assertThrows(SQLException.class, () -> execute(f.connection,
                    "UPDATE plan_evidence SET bounded_summary='changed'"));
            assertThrows(SQLException.class, () -> execute(f.connection,
                    "UPDATE plan_completion_rules SET provenance='changed'"));
            assertEquals(PlanExecutionStore.Status.SUCCESS, f.executions.completeAttempt(
                    f.authority, AT.plusSeconds(7), started.value().attempt(), Outcome.SUCCEEDED,
                    2, started.value().step().transitionVersion(), null, "done").status());
            var completed = f.completions.evaluateAndComplete(f.authority, AT.plusSeconds(8),
                    f.revision.planRevisionId());
            assertEquals(PlanCompletionStore.Status.SUCCESS, completed.status());
            assertEquals(CompletionResult.COMPLETE, completed.result());
            assertEquals("DONE", text(f.connection, "SELECT status FROM tasks"));
            assertEquals("COMPLETED", text(f.connection,
                    "SELECT terminal_status FROM durable_runs"));
            assertEquals(AT.plusSeconds(8).toString(),text(f.connection,
                    "SELECT terminal_at FROM task_identities"));
            assertEquals(1, scalar(f.connection, "SELECT count(*) FROM task_completion_decisions"));
            assertEquals(1, scalar(f.connection,
                    "SELECT count(*) FROM plan_completion_evaluation_evidence"));
            assertEquals(1, scalar(f.connection,
                    "SELECT count(*) FROM plan_completion_evaluation_steps"));
            assertEquals(occurrenceId.value(), text(f.connection,
                    "SELECT producer_identity FROM plan_evidence"));
            String decision=text(f.connection,"SELECT decision_hash FROM task_completion_decisions");
            var authority=f.completions.validateCompletedAuthority(f.task.id(),f.run.id(),
                    f.revision.planRevisionId(),decision);
            assertEquals(PlanCompletionStore.CompletedAuthorityStatus.VALID_COMPLETE,authority.status());
            assertEquals(1,authority.verifiedEvidenceHashes().size());
            assertEquals(PlanCompletionStore.CompletedAuthorityStatus.SCOPE_MISMATCH,
                    f.completions.validateCompletedAuthority(f.task.id(),CanonicalId.newId(EntityFamily.RUN),
                            f.revision.planRevisionId(),decision).status());
            assertEquals(PlanCompletionStore.CompletedAuthorityStatus.INTEGRITY_FAILURE,
                    f.completions.validateCompletedAuthority(f.task.id(),f.run.id(),
                            CanonicalId.newId(EntityFamily.PLAN),decision).status());
        }
    }

    @Test void completedAuthorityFailsClosedForEvidenceEvaluationAndDecisionCorruption()throws Exception{
        try(Fixture f=fixture("completed-authority-evidence")){
            complete(f);String decision=text(f.connection,"SELECT decision_hash FROM task_completion_decisions");
            execute(f.connection,"DROP TRIGGER r1c_evidence_immutable");
            execute(f.connection,"UPDATE plan_evidence SET canonical_hash='"+"0".repeat(64)+"'");
            assertEquals(PlanCompletionStore.CompletedAuthorityStatus.INTEGRITY_FAILURE,
                    f.completions.validateCompletedAuthority(f.task.id(),f.run.id(),f.revision.planRevisionId(),decision).status());
        }
        try(Fixture f=fixture("completed-authority-evaluation")){
            complete(f);String decision=text(f.connection,"SELECT decision_hash FROM task_completion_decisions");
            execute(f.connection,"DROP TRIGGER r1c_completion_evaluation_steps_immutable");
            execute(f.connection,"UPDATE plan_completion_evaluation_steps SET transition_version=transition_version+1");
            assertEquals(PlanCompletionStore.CompletedAuthorityStatus.INTEGRITY_FAILURE,
                    f.completions.validateCompletedAuthority(f.task.id(),f.run.id(),f.revision.planRevisionId(),decision).status());
        }
        try(Fixture f=fixture("completed-authority-decision")){
            complete(f);execute(f.connection,"DROP TRIGGER r1c_completion_decision_immutable");
            execute(f.connection,"UPDATE task_completion_decisions SET decision_hash='"+"f".repeat(64)+"'");
            assertEquals(PlanCompletionStore.CompletedAuthorityStatus.INTEGRITY_FAILURE,
                    f.completions.validateCompletedAuthority(f.task.id(),f.run.id(),f.revision.planRevisionId(),"f".repeat(64)).status());
        }
    }

    @Test void missingEvidenceAndFailedStepCannotCompleteTask() throws Exception {
        try (Fixture f = fixture("missing-evidence")) {
            var started = start(f);
            assertEquals(PlanExecutionStore.Status.SUCCESS, f.executions.completeAttempt(
                    f.authority, AT.plusSeconds(5), started.value().attempt(), Outcome.SUCCEEDED,
                    2, started.value().step().transitionVersion(), null, "done").status());
            var result = f.completions.evaluateAndComplete(f.authority, AT.plusSeconds(6),
                    f.revision.planRevisionId());
            assertEquals(CompletionResult.EVIDENCE_MISSING, result.result());
            assertEquals("RUNNING", text(f.connection, "SELECT status FROM tasks"));
            assertEquals(0, scalar(f.connection, "SELECT count(*) FROM task_completion_decisions"));
        }
        try (Fixture f = fixture("failed-step")) {
            var started = start(f);
            assertEquals(PlanExecutionStore.Status.SUCCESS, f.executions.completeAttempt(
                    f.authority, AT.plusSeconds(5), started.value().attempt(), Outcome.FAILED,
                    2, started.value().step().transitionVersion(), "PROVIDER_ERROR", "bounded")
                    .status());
            var result = f.completions.evaluateAndComplete(f.authority, AT.plusSeconds(6),
                    f.revision.planRevisionId());
            assertEquals(CompletionResult.FAILED_REQUIREMENT, result.result());
            assertEquals("RUNNING", text(f.connection, "SELECT status FROM tasks"));
        }
    }

    @Test void evidenceFromFailedAttemptCannotSatisfyLaterSuccessfulAttempt() throws Exception {
        try (Fixture f = fixture("failed-attempt-evidence")) {
            var first = start(f);
            completedToolOccurrence(f, "failed-attempt-tool", 5);
            assertEquals(PlanExecutionStore.Status.SUCCESS, f.executions.completeAttempt(
                    f.authority, AT.plusSeconds(7), first.value().attempt(),
                    Outcome.RETRYABLE_FAILURE, 2,
                    first.value().step().transitionVersion(), "RETRYABLE", "retry").status());
            var second = f.executions.startNextReadyAttempt(
                    f.authority, AT.plusSeconds(8), 3, "test");
            assertEquals(PlanExecutionStore.Status.SUCCESS, second.status());
            assertEquals(PlanExecutionStore.Status.SUCCESS, f.executions.completeAttempt(
                    f.authority, AT.plusSeconds(9), second.value().attempt(), Outcome.SUCCEEDED,
                    4, second.value().step().transitionVersion(), null, "done").status());
            var gate = f.completions.evaluateAndComplete(
                    f.authority, AT.plusSeconds(10), f.revision.planRevisionId());
            assertEquals(CompletionResult.EVIDENCE_MISSING, gate.result());
            assertEquals(0, scalar(f.connection,
                    "SELECT count(*) FROM task_completion_decisions"));
        }
    }

    @Test void verificationEvidenceRequiresPassedResultAndMatchingScope() throws Exception {
        try (Fixture f = fixture("verification",
                PlanCompletionModels.CompletionRuleType.VERIFICATION_PASSED,
                List.of(PlanCompletionModels.EvidenceKind.VERIFICATION_RESULT),
                PlanCompletionModels.EffectClass.VERIFY)) {
            var started = start(f);
            assertEquals(PlanExecutionStore.Status.SUCCESS, f.executions.completeAttempt(
                    f.authority, AT.plusSeconds(5), started.value().attempt(), Outcome.SUCCEEDED,
                    2, started.value().step().transitionVersion(), null, "done").status());
            assertEquals(FencedMutationResult.SUCCESS, f.mutations.startVerification(
                    f.authority, AT.plusSeconds(6), "verify-failed", 0, "verify", "RUNNING",
                    "WORKSPACE_CONTENT_SHA256_V1","a".repeat(64),null));
            assertEquals(FencedMutationResult.SUCCESS, f.mutations.finishVerification(
                    f.authority, AT.plusSeconds(7), "verify-failed", "FAILED", 1,
                    "", "failed", 1));
            assertEquals(0, scalar(f.connection,
                    "SELECT count(*) FROM plan_verification_evidence"));
            assertEquals(FencedMutationResult.SUCCESS, f.mutations.startVerification(
                    f.authority, AT.plusSeconds(8), "verify-unknown", 0, "verify", "RUNNING",
                    "WORKSPACE_CONTENT_SHA256_V1","b".repeat(64),null));
            assertThrows(IllegalStateException.class, () -> f.mutations.finishVerification(
                    f.authority, AT.plusSeconds(9), "verify-unknown", "UNKNOWN", null,
                    "", "unknown", 1));
            assertEquals(0, scalar(f.connection,
                    "SELECT count(*) FROM plan_verification_evidence"));
            assertEquals(FencedMutationResult.SUCCESS, f.mutations.startVerification(
                    f.authority, AT.plusSeconds(10), "verify-passed", 0, "verify", "RUNNING",
                    "WORKSPACE_CONTENT_SHA256_V1","c".repeat(64),null));
            assertEquals(FencedMutationResult.SUCCESS, f.mutations.finishVerification(
                    f.authority, AT.plusSeconds(11), "verify-passed", "PASSED", 0,
                    "ok", "", 1));
            assertEquals(1, scalar(f.connection,
                    "SELECT count(*) FROM plan_verification_evidence"));
            assertEquals(1, scalar(f.connection,
                    "SELECT count(*) FROM plan_evidence WHERE evidence_kind='VERIFICATION_RESULT'"));
            assertFalse(f.completions.needsVerificationEvidence(
                    f.task.id(), f.revision.planRevisionId()));
        }
    }

    @Test void genericRulesConsumeVerificationEvidenceIndependentOfRuleType() throws Exception {
        for (var profile : List.of(
                new Object[] {PlanCompletionModels.CompletionRuleType.ALL_REQUIRED_EVIDENCE,
                        List.of(PlanCompletionModels.EvidenceKind.VERIFICATION_RESULT)},
                new Object[] {PlanCompletionModels.CompletionRuleType.ANY_REQUIRED_EVIDENCE,
                        List.of(PlanCompletionModels.EvidenceKind.VERIFICATION_RESULT,
                                PlanCompletionModels.EvidenceKind.TOOL_CALL)})) {
            var rule = (PlanCompletionModels.CompletionRuleType) profile[0];
            @SuppressWarnings("unchecked")
            var kinds = (List<PlanCompletionModels.EvidenceKind>) profile[1];
            try (Fixture f = fixture("generic-verification-" + rule, rule, kinds,
                    PlanCompletionModels.EffectClass.VERIFY)) {
                var started = start(f);
                assertEquals(PlanExecutionStore.Status.SUCCESS, f.executions.completeAttempt(
                        f.authority, AT.plusSeconds(5), started.value().attempt(), Outcome.SUCCEEDED,
                        2, started.value().step().transitionVersion(), null, "done").status());
                assertEquals(FencedMutationResult.SUCCESS, f.mutations.startVerification(
                        f.authority, AT.plusSeconds(6), "verify-" + rule, 0, "verify", "RUNNING",
                        "WORKSPACE_CONTENT_SHA256_V1","d".repeat(64),null));
                assertEquals(FencedMutationResult.SUCCESS, f.mutations.finishVerification(
                        f.authority, AT.plusSeconds(7), "verify-" + rule, "PASSED", 0,
                        "ok", "", 1));
                assertEquals(1, scalar(f.connection,
                        "SELECT count(*) FROM plan_verification_evidence"));
                assertEquals(CompletionResult.COMPLETE, f.completions.evaluateAndComplete(
                        f.authority, AT.plusSeconds(8), f.revision.planRevisionId()).result());
            }
        }
    }

    @Test void corruptedPassedVerificationEvidenceFailsClosedWithoutProducerRetry()
            throws Exception {
        try (Fixture f = fixture("corrupt-verification-evidence",
                PlanCompletionModels.CompletionRuleType.VERIFICATION_PASSED,
                List.of(PlanCompletionModels.EvidenceKind.VERIFICATION_RESULT),
                PlanCompletionModels.EffectClass.VERIFY)) {
            var started = start(f);
            assertEquals(PlanExecutionStore.Status.SUCCESS, f.executions.completeAttempt(
                    f.authority, AT.plusSeconds(5), started.value().attempt(), Outcome.SUCCEEDED,
                    2, started.value().step().transitionVersion(), null, "done").status());
            assertEquals(FencedMutationResult.SUCCESS, f.mutations.startVerification(
                    f.authority, AT.plusSeconds(6), "verify-corrupt", 0, "verify", "RUNNING",
                    "WORKSPACE_CONTENT_SHA256_V1","e".repeat(64),null));
            assertEquals(FencedMutationResult.SUCCESS, f.mutations.finishVerification(
                    f.authority, AT.plusSeconds(7), "verify-corrupt", "PASSED", 0,
                    "ok", "", 1));
            execute(f.connection, "DROP TRIGGER r1c_evidence_immutable");
            execute(f.connection, "UPDATE plan_evidence SET canonical_hash='" + "f".repeat(64)
                    + "' WHERE producer_identity='verify-corrupt'");
            assertEquals(PlanCompletionStore.Status.EVIDENCE_INTEGRITY_FAILURE,
                    f.completions.readRecovery(f.task.id(), f.revision.planRevisionId()).status());
            var gate = f.completions.evaluateAndComplete(
                    f.authority, AT.plusSeconds(8), f.revision.planRevisionId());
            assertEquals(PlanCompletionStore.Status.EVIDENCE_INTEGRITY_FAILURE, gate.status());
            assertNull(gate.result());
            assertEquals(1, scalar(f.connection, "SELECT count(*) FROM verification_runs"));
        }
    }

    @Test void multiEvidenceAllAndAnyHaveReachableExactRequirements() throws Exception {
        List<PlanCompletionModels.EvidenceKind> kinds = List.of(
                PlanCompletionModels.EvidenceKind.TOOL_CALL,
                PlanCompletionModels.EvidenceKind.SNAPSHOT);
        try (Fixture f = fixture("multi-all",
                PlanCompletionModels.CompletionRuleType.ALL_REQUIRED_EVIDENCE, kinds,
                PlanCompletionModels.EffectClass.WRITE)) {
            var started = start(f);
            completedToolOccurrence(f, "multi-all-tool", 5);
            assertEquals(FencedMutationResult.SUCCESS, f.mutations.recordSnapshot(
                    f.authority, AT.plusSeconds(7), "multi-all-snapshot", null, "file.txt",
                    "snapshot/file.txt", "CREATE", false, 0, HASH));
            assertEquals(FencedMutationResult.SUCCESS, f.mutations.transitionSnapshot(
                    f.authority, AT.plusSeconds(8), "multi-all-snapshot",
                    "CAPTURED", "APPLIED", HASH));
            assertEquals(PlanExecutionStore.Status.SUCCESS, f.executions.completeAttempt(
                    f.authority, AT.plusSeconds(9), started.value().attempt(), Outcome.SUCCEEDED,
                    2, started.value().step().transitionVersion(), null, "done").status());
            assertEquals(3, scalar(f.connection,
                    "SELECT count(*) FROM plan_completion_requirements"));
            assertEquals(CompletionResult.COMPLETE, f.completions.evaluateAndComplete(
                    f.authority, AT.plusSeconds(10), f.revision.planRevisionId()).result());
        }
        try (Fixture f = fixture("multi-any",
                PlanCompletionModels.CompletionRuleType.ANY_REQUIRED_EVIDENCE, kinds,
                PlanCompletionModels.EffectClass.GENERAL)) {
            var started = start(f);
            completedToolOccurrence(f, "multi-any-tool", 5);
            assertEquals(PlanExecutionStore.Status.SUCCESS, f.executions.completeAttempt(
                    f.authority, AT.plusSeconds(7), started.value().attempt(), Outcome.SUCCEEDED,
                    2, started.value().step().transitionVersion(), null, "done").status());
            assertEquals(3, scalar(f.connection,
                    "SELECT count(*) FROM plan_completion_requirements"));
            assertEquals(CompletionResult.COMPLETE, f.completions.evaluateAndComplete(
                    f.authority, AT.plusSeconds(8), f.revision.planRevisionId()).result());
        }
    }

    @Test void exactRequirementIntegrityRejectsMissingExtraDuplicateAndReplacedKinds()
            throws Exception {
        List<PlanCompletionModels.EvidenceKind> kinds = List.of(
                PlanCompletionModels.EvidenceKind.TOOL_CALL,
                PlanCompletionModels.EvidenceKind.SNAPSHOT);
        try (Fixture f = fixture("requirement-missing",
                PlanCompletionModels.CompletionRuleType.ALL_REQUIRED_EVIDENCE, kinds,
                PlanCompletionModels.EffectClass.WRITE)) {
            execute(f.connection, "DROP TRIGGER r1c_completion_requirement_no_delete");
            execute(f.connection, "DELETE FROM plan_completion_requirements "
                    + "WHERE requirement_order=2");
            assertEquals(PlanCompletionStore.Status.COMPLETION_INTEGRITY_FAILURE,
                    f.completions.readRecovery(f.task.id(), f.revision.planRevisionId()).status());
        }
        try (Fixture f = fixture("requirement-extra",
                PlanCompletionModels.CompletionRuleType.ALL_REQUIRED_EVIDENCE, kinds,
                PlanCompletionModels.EffectClass.WRITE)) {
            execute(f.connection, """
                    INSERT INTO plan_completion_requirements(task_id,plan_revision_id,rule_order,
                        requirement_order,requirement_type,step_revision_id,required_evidence_kind)
                    SELECT task_id,plan_revision_id,rule_order,3,'EVIDENCE_KIND_PRESENT',
                        step_revision_id,'SNAPSHOT'
                    FROM plan_completion_requirements WHERE requirement_order=0
                    """);
            assertEquals(PlanCompletionStore.Status.COMPLETION_INTEGRITY_FAILURE,
                    f.completions.readRecovery(f.task.id(), f.revision.planRevisionId()).status());
        }
        try (Fixture f = fixture("requirement-replaced",
                PlanCompletionModels.CompletionRuleType.ALL_REQUIRED_EVIDENCE, kinds,
                PlanCompletionModels.EffectClass.WRITE)) {
            execute(f.connection, "DROP TRIGGER r1c_completion_requirement_immutable");
            execute(f.connection, "UPDATE plan_completion_requirements "
                    + "SET required_evidence_kind='VERIFICATION_RESULT' WHERE requirement_order=1");
            assertEquals(PlanCompletionStore.Status.COMPLETION_INTEGRITY_FAILURE,
                    f.completions.readRecovery(f.task.id(), f.revision.planRevisionId()).status());
        }
        try (Fixture f = fixture("requirement-duplicate",
                PlanCompletionModels.CompletionRuleType.ALL_REQUIRED_EVIDENCE, kinds,
                PlanCompletionModels.EffectClass.WRITE)) {
            execute(f.connection, "DROP TRIGGER r1c_completion_requirement_immutable");
            execute(f.connection, "UPDATE plan_completion_requirements "
                    + "SET required_evidence_kind='SNAPSHOT' WHERE requirement_order=2");
            assertEquals(PlanCompletionStore.Status.COMPLETION_INTEGRITY_FAILURE,
                    f.completions.readRecovery(f.task.id(), f.revision.planRevisionId()).status());
        }
    }

    @Test void snapshotSuccessCreatesEvidenceAndFailedOccurrenceDoesNot() throws Exception {
        try (Fixture f = fixture("snapshot-and-failure")) {
            start(f);
            assertThrows(SQLException.class, () -> execute(f.connection, """
                    INSERT INTO plan_evidence(task_id,plan_revision_id,step_revision_id,run_id,
                        attempt_number,evidence_kind,producer_kind,producer_identity,
                        observed_status,canonical_hash,provenance,created_at)
                    SELECT task_id,plan_revision_id,step_revision_id,run_id,attempt_number,
                        'TOOL_CALL','OCCURRENCE','missing-occurrence','SUCCEEDED',
                        'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                        'test','2026-07-19T00:00:05Z'
                    FROM plan_step_attempts LIMIT 1
                    """));
            CanonicalId failedId = CanonicalId.newId(EntityFamily.OCCURRENCE);
            SideEffectOccurrence failed = occurrence(f, failedId, "failed-tool", 5);
            assertEquals(FencedMutationResult.SUCCESS, f.mutations.admitOccurrence(
                    f.authority, AT.plusSeconds(5), failed));
            assertEquals(FencedMutationResult.SUCCESS, f.mutations.transitionOccurrence(
                    f.authority, AT.plusSeconds(6), failedId, "FAILED", HASH,
                    "KNOWN_FAILURE"));
            assertTrue(f.completions.readEvidence(
                    f.task.id(), f.revision.planRevisionId()).isEmpty());
            assertEquals(FencedMutationResult.SUCCESS, f.mutations.recordSnapshot(
                    f.authority, AT.plusSeconds(7), "snapshot-1", null, "file.txt",
                    "snapshot/file.txt", "CREATE", false, 0, HASH));
            assertEquals(FencedMutationResult.SUCCESS, f.mutations.transitionSnapshot(
                    f.authority, AT.plusSeconds(8), "snapshot-1", "CAPTURED", "APPLIED", HASH));
            var evidence = f.completions.readEvidence(
                    f.task.id(), f.revision.planRevisionId());
            assertEquals(1, evidence.size());
            assertEquals(PlanCompletionModels.EvidenceKind.SNAPSHOT,
                    evidence.getFirst().kind());
        }
    }

    @Test void snapshotDeclaredRuleHasReachableCompletionAndRejectsAnotherRunSnapshot()
            throws Exception {
        try (Fixture f = fixture("snapshot-rule",
                PlanCompletionModels.CompletionRuleType.SNAPSHOT_CREATED,
                List.of(PlanCompletionModels.EvidenceKind.SNAPSHOT),
                PlanCompletionModels.EffectClass.WRITE)) {
            var started = start(f);
            execute(f.connection, """
                    INSERT INTO file_snapshots(snapshot_id,task_id,file_path,operation,
                        original_exists,original_size,status,owner_run_id)
                    VALUES('other-run-snapshot','%s','other.txt','CREATE',0,0,'CAPTURED','%s')
                    """.formatted(f.task.id().value(),
                    CanonicalId.newId(EntityFamily.RUN).value()));
            assertEquals(FencedMutationResult.TARGET_NOT_FOUND, f.mutations.transitionSnapshot(
                    f.authority, AT.plusSeconds(5), "other-run-snapshot",
                    "CAPTURED", "APPLIED", HASH));
            assertEquals(FencedMutationResult.SUCCESS, f.mutations.recordSnapshot(
                    f.authority, AT.plusSeconds(6), "owned-snapshot", null, "file.txt",
                    "snapshot/file.txt", "CREATE", false, 0, HASH));
            assertEquals(FencedMutationResult.SUCCESS, f.mutations.transitionSnapshot(
                    f.authority, AT.plusSeconds(7), "owned-snapshot",
                    "CAPTURED", "APPLIED", HASH));
            assertEquals(PlanExecutionStore.Status.SUCCESS, f.executions.completeAttempt(
                    f.authority, AT.plusSeconds(8), started.value().attempt(), Outcome.SUCCEEDED,
                    2, started.value().step().transitionVersion(), null, "done").status());
            assertEquals(CompletionResult.COMPLETE, f.completions.evaluateAndComplete(
                    f.authority, AT.plusSeconds(9), f.revision.planRevisionId()).result());
            assertEquals(1, scalar(f.connection,
                    "SELECT count(*) FROM task_completion_decisions"));
        }
    }

    @Test void staleAuthorityIsRejectedAndCompletionFailureRollsBackAllFacts() throws Exception {
        try (Fixture f = fixture("fencing-rollback")) {
            var started = start(f);
            ExecutionAuthoritySnapshot stale = new ExecutionAuthoritySnapshot(f.task.id(),
                    f.run.id(), "owner", "wrong-lease", 1, 1);
            CanonicalId occurrenceId = CanonicalId.newId(EntityFamily.OCCURRENCE);
            SideEffectOccurrence occurrence = occurrence(f, occurrenceId, "stale", 5);
            assertEquals(FencedMutationResult.LEASE_TOKEN_MISMATCH,
                    f.mutations.admitOccurrence(stale, AT.plusSeconds(5), occurrence));
            assertEquals(0, scalar(f.connection, "SELECT count(*) FROM plan_evidence"));
            assertEquals(FencedMutationResult.INVALID_STATE, f.mutations.terminalize(
                    f.authority, AT.plusSeconds(5), f.revision.planRevisionId().value(),
                    true, null));
            assertEquals(FencedMutationResult.INVALID_STATE, f.mutations.terminalize(
                    f.authority, AT.plusSeconds(5), null, true, null));
            assertEquals(FencedMutationResult.INVALID_STATE, f.mutations.terminalize(
                    f.authority, AT.plusSeconds(5), "legacy-plan", true, null));
            assertEquals("RUNNING", text(f.connection, "SELECT status FROM tasks"));
            completedToolOccurrence(f, occurrenceId, "valid", 6);
            assertEquals(PlanExecutionStore.Status.SUCCESS, f.executions.completeAttempt(
                    f.authority, AT.plusSeconds(8), started.value().attempt(), Outcome.SUCCEEDED,
                    2, started.value().step().transitionVersion(), null, "done").status());
            execute(f.connection, "CREATE TRIGGER test_completion_rollback BEFORE UPDATE ON tasks "
                    + "BEGIN SELECT RAISE(ABORT,'injected completion failure'); END");
            assertThrows(IllegalStateException.class, () -> f.completions.evaluateAndComplete(
                    f.authority, AT.plusSeconds(9), f.revision.planRevisionId()));
            assertEquals(0, scalar(f.connection,
                    "SELECT count(*) FROM plan_completion_evaluations"));
            assertEquals(0, scalar(f.connection,
                    "SELECT count(*) FROM task_completion_decisions"));
            assertEquals("RUNNING", text(f.connection, "SELECT status FROM tasks"));
            assertEquals("EXECUTING", text(f.connection, "SELECT phase FROM coding_runs"));
        }
    }

    @Test void recoveryAndGateRejectTamperedEvidenceOrCompletionRules() throws Exception {
        try (Fixture f = fixture("evidence-integrity")) {
            start(f);
            CanonicalId producer = completedToolOccurrence(f, "integrity", 5);
            execute(f.connection, "DROP TRIGGER r1c_evidence_immutable");
            execute(f.connection, "UPDATE plan_evidence SET canonical_hash='" + "f".repeat(64) + "'");
            assertEquals(PlanCompletionStore.Status.EVIDENCE_INTEGRITY_FAILURE,
                    f.completions.readRecovery(f.task.id(), f.revision.planRevisionId()).status());
        }
        try (Fixture f = fixture("producer-integrity")) {
            start(f);
            CanonicalId producer = completedToolOccurrence(f, "producer", 5);
            execute(f.connection, "DELETE FROM side_effect_occurrences WHERE occurrence_id='"
                    + producer.value() + "'");
            assertEquals(PlanCompletionStore.Status.EVIDENCE_INTEGRITY_FAILURE,
                    f.completions.readRecovery(f.task.id(), f.revision.planRevisionId()).status());
        }
        try (Fixture f = fixture("rule-integrity")) {
            execute(f.connection, "DROP TRIGGER r1c_completion_rule_immutable");
            execute(f.connection, "UPDATE plan_completion_rules SET canonical_hash='"
                    + "f".repeat(64) + "'");
            assertEquals(PlanCompletionStore.Status.COMPLETION_INTEGRITY_FAILURE,
                    f.completions.readRecovery(f.task.id(), f.revision.planRevisionId()).status());
            assertEquals(PlanCompletionStore.Status.COMPLETION_INTEGRITY_FAILURE,
                    f.completions.evaluateAndComplete(f.authority, AT.plusSeconds(4),
                            f.revision.planRevisionId()).status());
        }
    }

    @Test void evidenceRulesAndEvaluationsSurviveASeparateStoreProcess() throws Exception {
        Path path = tempDir.resolve("cross-process");
        CanonicalId task;
        CanonicalId revision;
        try (Fixture f = fixture(path)) {
            task = f.task.id();
            revision = f.revision.planRevisionId();
            var started = start(f);
            completedToolOccurrence(f, "cross-process-tool", 5);
            assertEquals(PlanExecutionStore.Status.SUCCESS, f.executions.completeAttempt(
                    f.authority, AT.plusSeconds(7), started.value().attempt(), Outcome.SUCCEEDED,
                    2, started.value().step().transitionVersion(), null, "done").status());
            assertEquals(CompletionResult.COMPLETE, f.completions.evaluateAndComplete(
                    f.authority, AT.plusSeconds(8), revision).result());
        }
        try (DatabaseManager reopened = new DatabaseManager(path)) {
            var read = new PlanCompletionStore(reopened.getConnection())
                    .readRecovery(task, revision);
            assertEquals(PlanCompletionStore.Status.SUCCESS, read.status());
            assertEquals(1, read.evidence().size());
            assertEquals(1, read.ruleCount());
            assertEquals(1, read.evaluationCount());
            assertTrue(read.completed());
        }
    }

    private PlanExecutionStore.StartResult start(Fixture f) {
        var started = f.executions.startNextReadyAttempt(f.authority, AT.plusSeconds(4),
                1, "test");
        assertEquals(PlanExecutionStore.Status.SUCCESS, started.status());
        return started;
    }

    private void complete(Fixture f){
        var started=start(f);completedToolOccurrence(f,"complete-authority",5);
        assertEquals(PlanExecutionStore.Status.SUCCESS,f.executions.completeAttempt(f.authority,AT.plusSeconds(7),
                started.value().attempt(),Outcome.SUCCEEDED,2,started.value().step().transitionVersion(),null,"done").status());
        assertEquals(CompletionResult.COMPLETE,f.completions.evaluateAndComplete(f.authority,AT.plusSeconds(8),
                f.revision.planRevisionId()).result());
    }

    private CanonicalId completedToolOccurrence(Fixture f, String key, int second) {
        return completedToolOccurrence(f, CanonicalId.newId(EntityFamily.OCCURRENCE), key, second);
    }

    private CanonicalId completedToolOccurrence(Fixture f, CanonicalId id, String key, int second) {
        SideEffectOccurrence occurrence = occurrence(f, id, key, second);
        assertEquals(FencedMutationResult.SUCCESS, f.mutations.admitOccurrence(
                f.authority, AT.plusSeconds(second), occurrence));
        assertEquals(FencedMutationResult.SUCCESS, f.mutations.transitionOccurrence(
                f.authority, AT.plusSeconds(second + 1), id, "COMPLETED", HASH,
                "KNOWN_SUCCESS"));
        seedHistoricalV2ToolEvidence(f, id, AT.plusSeconds(second + 1));
        return id;
    }

    private void seedHistoricalV2ToolEvidence(Fixture f, CanonicalId occurrenceId, Instant at) {
        try {
            StepDefinition step = f.revision.steps().getFirst();
            int attemptNumber = (int) scalar(f.connection, """
                    SELECT current_attempt_number FROM plan_step_executions
                    WHERE task_id='%s' AND plan_revision_id='%s' AND step_revision_id='%s'
                    """.formatted(f.task.id().value(), f.revision.planRevisionId().value(),
                    step.stepRevisionId().value()));
            String summary = "tool=record";
            String provenance = "TEST_HISTORICAL_V2_TOOL_OCCURRENCE";
            String evidenceHash = PlanCompletionModels.evidenceHash(f.task.id(),
                    f.revision.planRevisionId(), step.stepRevisionId(), f.run.id(),
                    attemptNumber, PlanCompletionModels.EvidenceKind.TOOL_CALL,
                    PlanCompletionModels.ProducerKind.OCCURRENCE, occurrenceId.value(),
                    "SUCCEEDED", summary, null, provenance, at);
            execute(f.connection, """
                    INSERT INTO plan_evidence(task_id,plan_revision_id,step_revision_id,run_id,
                        attempt_number,evidence_kind,producer_kind,producer_identity,
                        observed_status,bounded_summary,protected_reference,canonical_hash,
                        provenance,created_at)
                    VALUES('%s','%s','%s','%s',%d,'TOOL_CALL','OCCURRENCE','%s',
                        'SUCCEEDED','%s',NULL,'%s','%s','%s')
                    """.formatted(f.task.id().value(), f.revision.planRevisionId().value(),
                    step.stepRevisionId().value(), f.run.id().value(), attemptNumber,
                    occurrenceId.value(), summary, evidenceHash, provenance, at));
        } catch (SQLException error) {
            throw new AssertionError("Historical V2 Tool Evidence seed failed", error);
        }
    }

    private SideEffectOccurrence occurrence(Fixture f, CanonicalId id, String key, int second) {
        return new SideEffectOccurrence(id, f.thread.id(), f.task.id(), f.run.id(), "1", key,
                null, "TOOL", "record", "STARTED", HASH, null, null,
                AT.plusSeconds(second), null);
    }

    private Fixture fixture(String name) throws Exception { return fixture(tempDir.resolve(name)); }

    private Fixture fixture(String name, PlanCompletionModels.CompletionRuleType rule,
            List<PlanCompletionModels.EvidenceKind> kinds,
            PlanCompletionModels.EffectClass effect) throws Exception {
        return fixture(tempDir.resolve(name), rule, kinds, effect);
    }

    private Fixture fixture(Path path) throws Exception {
        return fixture(path, PlanCompletionModels.CompletionRuleType.ALL_REQUIRED_EVIDENCE,
                List.of(PlanCompletionModels.EvidenceKind.TOOL_CALL),
                PlanCompletionModels.EffectClass.GENERAL);
    }

    private Fixture fixture(Path path, PlanCompletionModels.CompletionRuleType rule,
            List<PlanCompletionModels.EvidenceKind> kinds,
            PlanCompletionModels.EffectClass effect) throws Exception {
        DatabaseManager manager = new DatabaseManager(path);
        Connection connection = manager.getConnection();
        new MigrationRunner(connection).runMigrations();
        R1aDurableStore durable = new R1aDurableStore(connection);
        WorkThread thread = new WorkThread(CanonicalId.newId(EntityFamily.THREAD), "workspace",
                "goal", "OPEN", AT, AT);
        DurableTask task = new DurableTask(CanonicalId.newId(EntityFamily.TASK), thread.id(), null,
                "goal", "CREATED", null, null, AT, null);
        assertEquals(CreateResult.SUCCESS, durable.createThread(thread));
        assertEquals(CreateResult.SUCCESS, durable.createTask(task));
        InstructionManifest manifest = commitManifest(durable, task.id());
        DurableRun run = new DurableRun(CanonicalId.newId(EntityFamily.RUN), task.id(), thread.id(),
                "INITIAL", null, manifest.id(), manifest.canonicalHash(), AT, null);
        assertEquals(CreateResult.SUCCESS, durable.createRun(run));
        CanonicalId generationId=CanonicalId.newId(EntityFamily.GENERATION);
        assertEquals(CreateResult.SUCCESS,durable.createGeneration(new TaskContextGeneration(generationId,task.id(),null,
                run.id(),manifest.id(),manifest.canonicalHash(),"COMMITTED","{}",AT,AT)));
        assertEquals(CreateResult.SUCCESS,durable.createContextPointer(new TaskContextPointer(task.id(),generationId,run.id(),0)));
        assertEquals(OwnershipResult.SUCCESS, durable.acquireOwnership(task.id(), "owner", run.id(),
                "lease", AT, AT.plusSeconds(120)));
        TaskStore tasks = new TaskStore(connection);
        tasks.createTask(task.id().value(), "goal", "mock", "model", "DENY", 10);
        tasks.markRunning(task.id().value());
        CodingRunStore runs = new CodingRunStore(connection);
        String verificationCommand = kinds.contains(
                PlanCompletionModels.EvidenceKind.VERIFICATION_RESULT) ? "verify" : "";
        runs.create(run.id().value(), task.id().value(), CodingRunStore.KIND_INITIAL,
                verificationCommand, 1, 0);
        runs.transitionPhase(run.id().value(), CodingRunStore.PHASE_PLANNING,
                CodingRunStore.PHASE_EXECUTING);
        ExecutionAuthoritySnapshot authority = new ExecutionAuthoritySnapshot(task.id(), run.id(),
                "owner", "lease", 1, 1);
        Revision revision = revision(task.id(), run.id(), rule, kinds, effect);
        seedApprovedV2(connection, revision);
        var active = new PlanAuthorityStore(connection).readActive(task.id());
        assertEquals(PlanAuthorityStore.Status.SUCCESS, active.status());
        assertEquals(Revision.CANONICAL_SCHEMA_V2,
                active.plan().revision().canonicalSchemaVersion());
        assertEquals(0, scalar(connection, "SELECT count(*) FROM plan_v3_revisions"));
        return new Fixture(manager, connection, thread, task, run, authority, revision,
                new PlanExecutionStore(connection), new FencedMutationStore(connection),
                new PlanCompletionStore(connection));
    }

    /** Historical V2 completion fixtures bypass the V3-only production proposal boundary. */
    private static void seedApprovedV2(Connection connection, Revision revision)
            throws SQLException {
        if (revision.canonicalSchemaVersion() != Revision.CANONICAL_SCHEMA_V2) {
            throw new IllegalArgumentException("Historical completion fixture must remain V2");
        }
        boolean originalAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            execute(connection, """
                    INSERT INTO plan_revisions(plan_revision_id,task_id,revision_number,goal,
                        acceptance_criteria,assumptions,risks,verification_strategy,canonical_hash,
                        source_type,provenance,created_by_run_id,created_at,
                        canonical_schema_version)
                    VALUES('%s','%s',%d,'goal','accept','','','verify','%s','USER','test',
                        '%s','%s',2)
                    """.formatted(revision.planRevisionId().value(), revision.taskId().value(),
                    revision.revisionNumber(), revision.canonicalHash(),
                    revision.createdByRunId().value(), revision.createdAt()));
            for (StepDefinition step : revision.steps()) {
                execute(connection, """
                        INSERT INTO plan_step_definitions(step_revision_id,logical_step_id,
                            plan_revision_id,task_id,step_order,title,definition,canonical_hash,
                            completion_rule,required_evidence_kinds,effect_class)
                        VALUES('%s','%s','%s','%s',%d,'%s','%s','%s','%s','%s','%s')
                        """.formatted(step.stepRevisionId().value(),
                        step.logicalStepId().value(), revision.planRevisionId().value(),
                        revision.taskId().value(), step.order(), step.title(), step.definition(),
                        step.canonicalHash(), step.completionRule().name(),
                        step.requiredEvidenceKinds().stream().map(Enum::name)
                                .collect(java.util.stream.Collectors.joining(",")),
                        step.effectClass().name()));
            }
            execute(connection, """
                    INSERT INTO plan_revision_lifecycle(plan_revision_id,task_id,state,cas_version,
                        updated_by_run_id,updated_at)
                    VALUES('%s','%s','APPROVED',2,'%s','%s')
                    """.formatted(revision.planRevisionId().value(), revision.taskId().value(),
                    revision.createdByRunId().value(), AT.plusSeconds(3)));
            execute(connection, """
                    INSERT INTO plan_approvals(approval_id,task_id,plan_revision_id,decision,
                        approved_by,approved_by_run_id,policy_version,decided_at)
                    VALUES('approval','%s','%s','APPROVED','user','%s','v2-test','%s')
                    """.formatted(revision.taskId().value(), revision.planRevisionId().value(),
                    revision.createdByRunId().value(), AT.plusSeconds(3)));
            execute(connection, """
                    INSERT INTO task_plan_authorities(task_id,active_plan_revision_id,
                        active_revision_state,active_approval_id,active_approval_decision,
                        activated_by_run_id,pointer_version,activated_at)
                    VALUES('%s','%s','APPROVED','approval','APPROVED','%s',1,'%s')
                    """.formatted(revision.taskId().value(), revision.planRevisionId().value(),
                    revision.createdByRunId().value(), AT.plusSeconds(3)));
            execute(connection, """
                    INSERT INTO plan_run_bindings(binding_id,task_id,run_id,plan_revision_id,
                        approval_id,approval_decision,plan_hash,bound_at)
                    VALUES('binding-v2','%s','%s','%s','approval','APPROVED','%s','%s')
                    """.formatted(revision.taskId().value(), revision.createdByRunId().value(),
                    revision.planRevisionId().value(), revision.canonicalHash(),
                    AT.plusSeconds(3)));
            PlanExecutionStore.initializeForApprovedBinding(connection, revision.taskId(),
                    revision.planRevisionId(), revision.createdByRunId(), AT.plusSeconds(3),
                    "TEST_HISTORICAL_V2_APPROVED_BINDING");
            PlanCompletionStore.initializeRules(connection, revision.taskId(),
                    revision.planRevisionId(), revision.createdByRunId(), AT.plusSeconds(3));
            connection.commit();
        } catch (SQLException | RuntimeException error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    private static Revision revision(CanonicalId task, CanonicalId run) {
        return revision(task, run, PlanCompletionModels.CompletionRuleType.ALL_REQUIRED_EVIDENCE,
                List.of(PlanCompletionModels.EvidenceKind.TOOL_CALL),
                PlanCompletionModels.EffectClass.GENERAL);
    }

    private static Revision revision(CanonicalId task, CanonicalId run,
            PlanCompletionModels.CompletionRuleType rule,
            List<PlanCompletionModels.EvidenceKind> kinds,
            PlanCompletionModels.EffectClass effect) {
        CanonicalId plan = CanonicalId.newId(EntityFamily.PLAN);
        StepDefinition step = new StepDefinition(CanonicalId.newId(EntityFamily.PLAN_STEP),
                CanonicalId.newId(EntityFamily.PLAN_STEP), 0, "one", "definition",
                rule, kinds, effect, "b".repeat(64));
        Revision candidate = new Revision(plan, task, 1, "goal", "accept", "", "", "verify",
                "0".repeat(64), "USER", "test", run, AT, List.of(step), List.of(), List.of());
        return new Revision(plan, task, 1, candidate.goal(), candidate.acceptanceCriteria(),
                candidate.assumptions(), candidate.risks(), candidate.verificationStrategy(),
                PlanCanonicalizer.hash(candidate), candidate.sourceType(), candidate.provenance(),
                run, AT, candidate.steps(), candidate.dependencies(), candidate.lineage());
    }

    private static InstructionManifest commitManifest(R1aDurableStore store, CanonicalId taskId)
            throws Exception {
        ProtectedInstructionArtifact artifact = ProtectedInstructionArtifact.inlineUtf8(
                "artifact-" + taskId.value().substring(4), "x", "v1", "task", AT);
        var entry = new InstructionManifestCandidate.Entry("entry-" + taskId.value().substring(4),
                "AGENTS", "AGENTS.md", "workspace", InstructionManifestCandidate.Status.APPLIED,
                0, artifact.contentHash(), artifact.id(), "OK", null, null);
        return ((ManifestCommitResult.Success) store.commitManifest(taskId,
                "manifest-" + taskId.value().substring(4),
                new InstructionManifestCandidate(1, "v1", List.of(entry)), List.of(artifact), AT))
                .manifest();
    }

    private static long scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             var result = statement.executeQuery(sql)) { result.next(); return result.getLong(1); }
    }
    private static String text(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             var result = statement.executeQuery(sql)) { result.next(); return result.getString(1); }
    }
    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.executeUpdate(sql); }
    }
    private static boolean objectExists(Connection connection, String name) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE name=?")) {
            statement.setString(1, name);
            try (var result = statement.executeQuery()) { return result.next(); }
        }
    }

    private static boolean columnExists(Connection connection, String table, String column)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             var rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rows.next()) if (column.equals(rows.getString("name"))) return true;
            return false;
        }
    }

    private record Fixture(DatabaseManager manager, Connection connection, WorkThread thread,
            DurableTask task, DurableRun run, ExecutionAuthoritySnapshot authority,
            Revision revision, PlanExecutionStore executions, FencedMutationStore mutations,
            PlanCompletionStore completions) implements AutoCloseable {
        @Override public void close() throws Exception { manager.close(); }
    }
}
