package com.paicli.state;

import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.state.R1aDurableModels.DurableRun;
import com.paicli.state.R1aDurableModels.DurableTask;
import com.paicli.state.R1aDurableModels.ExecutionAuthoritySnapshot;
import com.paicli.state.R1aDurableModels.WorkThread;
import com.paicli.state.R1aStoreResults.CreateResult;
import com.paicli.state.R1aStoreResults.ManifestCommitResult;
import com.paicli.state.R1aStoreResults.OwnershipResult;
import com.paicli.state.StructuredPlanModels.Dependency;
import com.paicli.state.StructuredPlanModels.Revision;
import com.paicli.state.StructuredPlanModels.StepDefinition;
import com.paicli.state.StructuredPlanModels.Lineage;
import com.paicli.state.StructuredPlanModels.LineageRelationship;
import com.paicli.state.StructuredPlanModels.AuthoritySelectorKind;
import com.paicli.state.StructuredPlanModels.FreshnessPolicy;
import com.paicli.state.StructuredPlanModels.ToolAuthoritySelector;
import com.paicli.state.StructuredPlanModels.ToolRequirement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class R1cPlanAuthorityStoreTest {
    @TempDir Path tempDir;
    private static final Instant AT = Instant.parse("2026-07-18T00:00:00Z");
    private static final String HASH = "a".repeat(64);

    @Test void v20IsAdditiveAndDoesNotConvertLegacyPlans() throws Exception {
        try (DatabaseManager manager = new DatabaseManager(tempDir.resolve("migration"))) {
            Connection connection = manager.getConnection();
            MigrationRunner runner = new MigrationRunner(connection);
            runner.runMigrationsThrough(19);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO tasks(task_id,goal,status) VALUES('legacy','goal','RUNNING')");
                statement.executeUpdate("INSERT INTO coding_runs(run_id,task_id,run_kind,phase) VALUES('old-run','legacy','INITIAL','EXECUTING')");
                statement.executeUpdate("INSERT INTO task_plans(plan_id,run_id,task_id,plan_text,status) VALUES('old-plan','old-run','legacy','text','ACTIVE')");
            }
            runner.runMigrations();
            assertEquals(33, scalar(connection, "SELECT max(version) FROM schema_migrations"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM task_plans"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM plan_revisions"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM task_plan_authorities"));
            assertTrue(objectExists(connection,"task_plan_applicability"));
            assertEquals(0,scalar(connection,"SELECT count(*) FROM task_plan_applicability"));
            runner.runMigrations();
            assertEquals(1, scalar(connection, "SELECT count(*) FROM schema_migrations WHERE version=20"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM schema_migrations WHERE version=21"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM schema_migrations WHERE version=22"));
            assertTrue(objectExists(connection, "plan_step_executions"));
            assertTrue(objectExists(connection, "plan_step_attempts"));
            assertTrue(objectExists(connection, "plan_evidence"));
        }
    }

    @Test void failedV20MigrationRollsBackEveryPlanAuthorityObject() throws Exception {
        try (DatabaseManager manager = new DatabaseManager(tempDir.resolve("migration-rollback"))) {
            Connection connection = manager.getConnection();
            MigrationRunner runner = new MigrationRunner(connection);
            runner.runMigrationsThrough(19);
            execute(connection, "CREATE TABLE plan_step_dependencies(blocker TEXT)");
            assertThrows(SQLException.class, runner::runMigrations);
            assertEquals(0, scalar(connection,
                    "SELECT count(*) FROM schema_migrations WHERE version=20"));
            assertFalse(objectExists(connection, "plan_revisions"));
            assertFalse(objectExists(connection, "task_plan_authorities"));
            assertFalse(objectExists(connection,"task_plan_applicability"));
        }
    }

    @Test void definitionsAreImmutableAndUnapprovedRevisionCannotBecomeAuthority() throws Exception {
        try (Fixture fixture = fixture("immutable")) {
            Revision revision = revision(fixture.task.id(), fixture.run.id(), 1, List.of());
            assertEquals(PlanAuthorityStore.Status.SUCCESS,
                    fixture.plans.propose(fixture.authority, AT.plusSeconds(1), revision).status());
            assertEquals(PlanAuthorityStore.Status.INVALID_STATE,
                    fixture.plans.approveAndActivate(fixture.authority, AT.plusSeconds(2),
                            revision.planRevisionId(), 0, 0, "approval-1", "user", "v1", null).status());
            assertThrows(SQLException.class, () -> execute(fixture.connection,
                    "UPDATE plan_revisions SET goal='changed' WHERE plan_revision_id='"
                            + revision.planRevisionId().value() + "'"));
            assertThrows(SQLException.class, () -> execute(fixture.connection,
                    "UPDATE plan_step_definitions SET title='changed' WHERE plan_revision_id='"
                            + revision.planRevisionId().value() + "'"));
        }
    }

    @Test void verificationCapabilityIsDurableAndValidatedBeforeApprovalWrites() throws Exception {
        Path path = tempDir.resolve("verification-capability");
        CanonicalId task;
        CanonicalId revisionId;
        try (Fixture fixture = fixture("verification-capability")) {
            task = fixture.task.id();
            execute(fixture.connection, "INSERT INTO tasks(task_id,goal,status) VALUES('"
                    + task.value() + "','goal','RUNNING')");
            execute(fixture.connection, "INSERT INTO coding_runs(run_id,task_id,run_kind,phase,"
                    + "verification_command) VALUES('" + fixture.run.id().value() + "','"
                    + task.value() + "','INITIAL','EXECUTING','')");
            Revision revision = singleStepRevision(task, fixture.run.id(), 1,
                    PlanCompletionModels.CompletionRuleType.VERIFICATION_PASSED,
                    List.of(PlanCompletionModels.EvidenceKind.VERIFICATION_RESULT));
            revisionId = revision.planRevisionId();
            assertSuccess(fixture.plans.propose(
                    fixture.authority, AT.plusSeconds(1), revision));
            assertEquals(PlanAuthorityStore.Status.COMPLETION_PROFILE_UNPRODUCIBLE,
                    fixture.plans.validateRevision(fixture.authority,
                            AT.plusSeconds(2), revisionId, 0).status());
            assertEquals("DRAFT", text(fixture.connection,
                    "SELECT state FROM plan_revision_lifecycle"));
            assertEquals(0, scalar(fixture.connection, "SELECT count(*) FROM plan_approvals"));
            assertEquals(0, scalar(fixture.connection,
                    "SELECT count(*) FROM task_plan_authorities"));
            assertEquals(0, scalar(fixture.connection,
                    "SELECT count(*) FROM plan_step_executions"));
            execute(fixture.connection, "UPDATE coding_runs SET verification_command='verify' "
                    + "WHERE run_id='" + fixture.run.id().value() + "'");
            assertSuccess(fixture.plans.validateRevision(fixture.authority,
                    AT.plusSeconds(3), revisionId, 0));
            assertSuccess(fixture.plans.approveAndActivate(fixture.authority, AT.plusSeconds(4),
                    revisionId, 1, 0, "approval", "user", "v1", null));
        }
        try (DatabaseManager reopened = new DatabaseManager(path)) {
            assertEquals(PlanAuthorityStore.Status.SUCCESS,
                    new PlanAuthorityStore(reopened.getConnection()).readActive(task).status());
            assertEquals("verify", text(reopened.getConnection(),
                    "SELECT verification_command FROM coding_runs"));
            assertEquals(2, scalar(reopened.getConnection(),
                    "SELECT canonical_schema_version FROM plan_revisions"));
        }
    }

    @Test void allRequiresVerificationButAnyMayUseAnotherReachableProducer() throws Exception {
        try (Fixture fixture = fixture("capability-all")) {
            createRuntimeContract(fixture, "");
            Revision revision = singleStepRevision(fixture.task.id(), fixture.run.id(), 1,
                    PlanCompletionModels.CompletionRuleType.ALL_REQUIRED_EVIDENCE,
                    List.of(PlanCompletionModels.EvidenceKind.TOOL_CALL,
                            PlanCompletionModels.EvidenceKind.VERIFICATION_RESULT));
            assertSuccess(fixture.plans.propose(fixture.authority, AT.plusSeconds(1), revision));
            assertEquals(PlanAuthorityStore.Status.COMPLETION_PROFILE_UNPRODUCIBLE,
                    fixture.plans.validateRevision(fixture.authority, AT.plusSeconds(2),
                            revision.planRevisionId(), 0)
                            .status());
            assertEquals("DRAFT", text(fixture.connection,
                    "SELECT state FROM plan_revision_lifecycle"));
        }
        try (Fixture fixture = fixture("capability-any")) {
            createRuntimeContract(fixture, "");
            Revision revision = singleStepRevision(fixture.task.id(), fixture.run.id(), 1,
                    PlanCompletionModels.CompletionRuleType.ANY_REQUIRED_EVIDENCE,
                    List.of(PlanCompletionModels.EvidenceKind.TOOL_CALL,
                            PlanCompletionModels.EvidenceKind.VERIFICATION_RESULT));
            assertSuccess(fixture.plans.propose(fixture.authority, AT.plusSeconds(1), revision));
            assertSuccess(fixture.plans.validateRevision(fixture.authority, AT.plusSeconds(2),
                    revision.planRevisionId(), 0));
            assertSuccess(fixture.plans.approveAndActivate(fixture.authority, AT.plusSeconds(3),
                    revision.planRevisionId(), 1, 0, "approval", "user", "v1", null));
        }
    }

    @Test void canonicalEvidenceSetOrderingIsStableAndV1V2DomainsDoNotMix() {
        CanonicalId task = CanonicalId.newId(EntityFamily.TASK);
        CanonicalId run = CanonicalId.newId(EntityFamily.RUN);
        CanonicalId plan = CanonicalId.newId(EntityFamily.PLAN);
        CanonicalId step = CanonicalId.newId(EntityFamily.PLAN_STEP);
        CanonicalId logical = CanonicalId.newId(EntityFamily.PLAN_STEP);
        StepDefinition first = new StepDefinition(step, logical, 0, "one", "definition",
                PlanCompletionModels.CompletionRuleType.ALL_REQUIRED_EVIDENCE,
                List.of(PlanCompletionModels.EvidenceKind.TOOL_CALL,
                        PlanCompletionModels.EvidenceKind.SNAPSHOT),
                PlanCompletionModels.EffectClass.GENERAL, "b".repeat(64));
        StepDefinition reversed = new StepDefinition(step, logical, 0, "one", "definition",
                PlanCompletionModels.CompletionRuleType.ALL_REQUIRED_EVIDENCE,
                List.of(PlanCompletionModels.EvidenceKind.SNAPSHOT,
                        PlanCompletionModels.EvidenceKind.TOOL_CALL),
                PlanCompletionModels.EffectClass.GENERAL, "b".repeat(64));
        Revision v2a = new Revision(plan, task, 1, "goal", "accept", "", "", "verify",
                HASH, "MODEL", "test", run, AT, List.of(first), List.of(), List.of());
        Revision v2b = new Revision(plan, task, 1, "goal", "accept", "", "", "verify",
                HASH, "MODEL", "test", run, AT, List.of(reversed), List.of(), List.of());
        Revision v1 = new Revision(plan, task, 1, "goal", "accept", "", "", "verify",
                HASH, "MODEL", "test", run, AT,
                List.of(new StepDefinition(step, logical, 0, "one", "definition",
                        "b".repeat(64))),
                List.of(), List.of(), Revision.CANONICAL_SCHEMA_V1);
        assertEquals(first.requiredEvidenceKinds(), reversed.requiredEvidenceKinds());
        assertEquals(PlanCanonicalizer.hash(v2a), PlanCanonicalizer.hash(v2b));
        assertNotEquals(PlanCanonicalizer.hash(v1), PlanCanonicalizer.hash(v2a));
    }

    @Test void validatedApprovalActivatesExactlyOneRevisionWithCasAndKeepsHistory() throws Exception {
        try (Fixture fixture = fixture("approval")) {
            Revision first = revision(fixture.task.id(), fixture.run.id(), 1, List.of());
            assertSuccess(fixture.plans.propose(fixture.authority, AT.plusSeconds(1), first));
            assertSuccess(fixture.plans.validateRevision(fixture.authority, AT.plusSeconds(2), first.planRevisionId(), 0));
            assertSuccess(fixture.plans.approveAndActivate(fixture.authority, AT.plusSeconds(3),
                    first.planRevisionId(), 1, 0, "approval-1", "user", "v1", "accepted"));
            Revision second = revision(fixture.task.id(), fixture.run.id(), 2, List.of());
            assertSuccess(fixture.plans.propose(fixture.authority, AT.plusSeconds(4), second));
            assertSuccess(fixture.plans.validateRevision(fixture.authority, AT.plusSeconds(5), second.planRevisionId(), 0));
            assertEquals(PlanAuthorityStore.Status.VERSION_CONFLICT,
                    fixture.plans.approveAndActivate(fixture.authority, AT.plusSeconds(6),
                            second.planRevisionId(), 1, 0, "approval-2", "user", "v1", null).status());
            assertEquals(first.planRevisionId(),fixture.plans.readActive(fixture.task.id())
                    .plan().revision().planRevisionId());
            assertEquals("VALIDATED",text(fixture.connection,
                    "SELECT state FROM plan_revision_lifecycle WHERE plan_revision_id='"
                            +second.planRevisionId().value()+"'"));
            assertEquals(1,scalar(fixture.connection,"SELECT count(*) FROM plan_approvals"));
            assertSuccess(fixture.plans.approveAndActivate(fixture.authority, AT.plusSeconds(7),
                    second.planRevisionId(), 1, 1, "approval-2", "user", "v1", "accepted"));
            var active=fixture.plans.readActive(fixture.task.id());
            assertEquals(second.planRevisionId(),active.plan().revision().planRevisionId());
            assertEquals(2,active.plan().authority().pointerVersion());
            assertEquals("SUPERSEDED",text(fixture.connection,
                    "SELECT state FROM plan_revision_lifecycle WHERE plan_revision_id='"
                            +first.planRevisionId().value()+"'"));
            assertEquals("APPROVED",text(fixture.connection,
                    "SELECT state FROM plan_revision_lifecycle WHERE plan_revision_id='"
                            +second.planRevisionId().value()+"'"));
            assertEquals(PlanAuthorityStore.Status.SUCCESS,fixture.plans
                    .readRevisionDefinition(fixture.task.id(),first.planRevisionId()).status());
            assertEquals(2,scalar(fixture.connection,"SELECT count(*) FROM plan_approvals"));
            assertEquals(2, scalar(fixture.connection, "SELECT count(*) FROM plan_revisions"));
            assertEquals(2,scalar(fixture.connection,
                    "SELECT count(*) FROM plan_step_executions WHERE plan_revision_id='"
                            +first.planRevisionId().value()+"'"));
            assertEquals(2,scalar(fixture.connection,
                    "SELECT count(*) FROM plan_step_executions WHERE plan_revision_id='"
                            +second.planRevisionId().value()+"'"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings={"pointer","binding","supersede"})
    void activationFailureRollsBackApprovalPointerBindingAndLifecycle(String stage)throws Exception{
        try(Fixture fixture=fixture("switch-rollback-"+stage)){
            Revision first=revision(fixture.task.id(),fixture.run.id(),1,List.of());
            assertSuccess(fixture.plans.propose(fixture.authority,AT.plusSeconds(1),first));
            assertSuccess(fixture.plans.validateRevision(fixture.authority,AT.plusSeconds(2),
                    first.planRevisionId(),0));
            assertSuccess(fixture.plans.approveAndActivate(fixture.authority,AT.plusSeconds(3),
                    first.planRevisionId(),1,0,"approval-1","user","v1",null));
            Revision second=revision(fixture.task.id(),fixture.run.id(),2,List.of());
            assertSuccess(fixture.plans.propose(fixture.authority,AT.plusSeconds(4),second));
            assertSuccess(fixture.plans.validateRevision(fixture.authority,AT.plusSeconds(5),
                    second.planRevisionId(),0));
            String trigger=switch(stage){
                case "pointer" -> "CREATE TRIGGER test_fail_switch BEFORE UPDATE ON task_plan_authorities BEGIN SELECT RAISE(ABORT,'test'); END";
                case "binding" -> "CREATE TRIGGER test_fail_switch BEFORE INSERT ON plan_run_bindings WHEN NEW.plan_revision_id='"+second.planRevisionId().value()+"' BEGIN SELECT RAISE(ABORT,'test'); END";
                case "supersede" -> "CREATE TRIGGER test_fail_switch BEFORE UPDATE ON plan_revision_lifecycle WHEN NEW.state='SUPERSEDED' BEGIN SELECT RAISE(ABORT,'test'); END";
                default -> throw new IllegalArgumentException(stage);
            };
            execute(fixture.connection,trigger);
            assertEquals(PlanAuthorityStore.Status.INTEGRITY_VIOLATION,
                    fixture.plans.approveAndActivate(fixture.authority,AT.plusSeconds(6),
                            second.planRevisionId(),1,1,"approval-2","user","v1",null).status());
            assertEquals(first.planRevisionId(),fixture.plans.readActive(fixture.task.id())
                    .plan().revision().planRevisionId());
            assertEquals("APPROVED",text(fixture.connection,
                    "SELECT state FROM plan_revision_lifecycle WHERE plan_revision_id='"
                            +first.planRevisionId().value()+"'"));
            assertEquals("VALIDATED",text(fixture.connection,
                    "SELECT state FROM plan_revision_lifecycle WHERE plan_revision_id='"
                            +second.planRevisionId().value()+"'"));
            assertEquals(1,scalar(fixture.connection,"SELECT count(*) FROM plan_approvals"));
            assertEquals(1,scalar(fixture.connection,"SELECT count(*) FROM plan_run_bindings"));
        }
    }

    @Test void futureLineageBelongsOnlyToTargetRevisionCanonicalDefinition()throws Exception{
        try(Fixture fixture=fixture("lineage-owner")){
            Revision first=revision(fixture.task.id(),fixture.run.id(),1,List.of());
            assertSuccess(fixture.plans.propose(fixture.authority,AT.plusSeconds(1),first));
            assertSuccess(fixture.plans.validateRevision(fixture.authority,AT.plusSeconds(2),
                    first.planRevisionId(),0));
            assertSuccess(fixture.plans.approveAndActivate(fixture.authority,AT.plusSeconds(3),
                    first.planRevisionId(),1,0,"approval-1","user","v1",null));
            Revision original=fixture.plans.readRevisionDefinition(fixture.task.id(),
                    first.planRevisionId()).revision();
            Revision rawSecond=revision(fixture.task.id(),fixture.run.id(),2,List.of());
            Lineage lineage=new Lineage("lineage-1",first.planRevisionId(),
                    first.steps().getFirst().stepRevisionId(),rawSecond.planRevisionId(),
                    rawSecond.steps().getFirst().stepRevisionId(),LineageRelationship.PRESERVED,
                    "explicit",AT.plusSeconds(4));
            Revision candidate=new Revision(rawSecond.planRevisionId(),rawSecond.taskId(),2,
                    rawSecond.goal(),rawSecond.acceptanceCriteria(),rawSecond.assumptions(),
                    rawSecond.risks(),rawSecond.verificationStrategy(),"0".repeat(64),
                    rawSecond.sourceType(),rawSecond.provenance(),rawSecond.createdByRunId(),
                    rawSecond.createdAt(),rawSecond.steps(),rawSecond.dependencies(),List.of(lineage),
                    Revision.CANONICAL_SCHEMA_V3);
            Revision second=new Revision(candidate.planRevisionId(),candidate.taskId(),2,
                    candidate.goal(),candidate.acceptanceCriteria(),candidate.assumptions(),
                    candidate.risks(),candidate.verificationStrategy(),PlanCanonicalizer.hash(candidate),
                    candidate.sourceType(),candidate.provenance(),candidate.createdByRunId(),
                    candidate.createdAt(),candidate.steps(),candidate.dependencies(),candidate.lineage(),
                    Revision.CANONICAL_SCHEMA_V3);
            assertSuccess(fixture.plans.propose(fixture.authority,AT.plusSeconds(5),second));
            var reread=fixture.plans.readRevisionDefinition(fixture.task.id(),first.planRevisionId());
            assertEquals(PlanAuthorityStore.Status.SUCCESS,reread.status());
            assertEquals(original.canonicalHash(),reread.revision().canonicalHash());
            assertEquals(original.lineage(),reread.revision().lineage());
            var target=fixture.plans.readRevisionDefinition(fixture.task.id(),second.planRevisionId());
            assertEquals(PlanAuthorityStore.Status.SUCCESS,target.status());
            assertEquals(List.of(lineage),target.revision().lineage());
            assertEquals(first.planRevisionId(),fixture.plans.readActive(fixture.task.id())
                    .plan().revision().planRevisionId());
        }
    }

    @Test void cyclicDependencyAndStaleFenceFailClosedWithoutPartialDefinition() throws Exception {
        try (Fixture fixture = fixture("invalid")) {
            CanonicalId one = CanonicalId.newId(EntityFamily.PLAN_STEP);
            CanonicalId two = CanonicalId.newId(EntityFamily.PLAN_STEP);
            List<Dependency> cycle = List.of(new Dependency(one, two), new Dependency(two, one));
            Revision invalid = revision(fixture.task.id(), fixture.run.id(), 1, one, two, cycle);
            assertEquals(PlanAuthorityStore.Status.STRUCTURE_INVALID,
                    fixture.plans.propose(fixture.authority, AT.plusSeconds(1), invalid).status());
            assertEquals(0, scalar(fixture.connection, "SELECT count(*) FROM plan_revisions"));
            ExecutionAuthoritySnapshot stale = new ExecutionAuthoritySnapshot(fixture.task.id(),
                    fixture.run.id(), "owner", "wrong", 1, 1);
            assertEquals(PlanAuthorityStore.Status.LEASE_TOKEN_MISMATCH,
                    fixture.plans.propose(stale, AT.plusSeconds(1),
                            revision(fixture.task.id(), fixture.run.id(), 1, List.of())).status());
        }
    }

    @Test void recoveryRunBindsTheSameApprovedRevisionWithoutGeneratingOrSelectingLatest() throws Exception {
        try (Fixture fixture = fixture("recovery-binding")) {
            Revision approved = revision(fixture.task.id(), fixture.run.id(), 1, List.of());
            assertSuccess(fixture.plans.propose(fixture.authority, AT.plusSeconds(1), approved));
            assertSuccess(fixture.plans.validateRevision(fixture.authority, AT.plusSeconds(2),
                    approved.planRevisionId(), 0));
            assertSuccess(fixture.plans.approveAndActivate(fixture.authority, AT.plusSeconds(3),
                    approved.planRevisionId(), 1, 0, "approval-1", "user", "v1", null));
            Revision unapproved = revision(fixture.task.id(), fixture.run.id(), 2, List.of());
            assertSuccess(fixture.plans.propose(fixture.authority, AT.plusSeconds(4), unapproved));
            InstructionManifest manifest = fixture.durable.findManifestByTask(fixture.task.id()).orElseThrow();
            DurableRun resume = new DurableRun(CanonicalId.newId(EntityFamily.RUN), fixture.task.id(),
                    fixture.task.threadId(), "RESUME", fixture.run.id(), manifest.id(),
                    manifest.canonicalHash(), AT.plusSeconds(61), null);
            assertEquals(CreateResult.SUCCESS, fixture.durable.createRun(resume));
            assertEquals(OwnershipResult.SUCCESS, fixture.durable.takeoverOwnership(fixture.task.id(),
                    resume.id(), "owner-b", "lease-b", 1, AT.plusSeconds(61),
                    AT.plusSeconds(121), Duration.ZERO));
            ExecutionAuthoritySnapshot resumed = new ExecutionAuthoritySnapshot(fixture.task.id(),
                    resume.id(), "owner-b", "lease-b", 2, 2);
            assertSuccess(fixture.plans.bindRecoveryRun(resumed, AT.plusSeconds(62)));
            assertEquals(approved.planRevisionId(), fixture.plans.readActive(fixture.task.id())
                    .plan().revision().planRevisionId());
            assertEquals(2, scalar(fixture.connection, "SELECT count(*) FROM plan_run_bindings"));
        }
    }

    @Test void lineageCannotCrossTaskEvenWhenBothTypedStepsExist() throws Exception {
        try (Fixture fixture = fixture("cross-task-lineage")) {
            WorkThread otherThread = new WorkThread(CanonicalId.newId(EntityFamily.THREAD),
                    "workspace", "other", "OPEN", AT, AT);
            DurableTask otherTask = new DurableTask(CanonicalId.newId(EntityFamily.TASK),
                    otherThread.id(), null, "other", "CREATED", null, null, AT, null);
            assertEquals(CreateResult.SUCCESS, fixture.durable.createThread(otherThread));
            assertEquals(CreateResult.SUCCESS, fixture.durable.createTask(otherTask));
            InstructionManifest otherManifest = commitManifest(fixture.durable, otherTask.id());
            DurableRun otherRun = new DurableRun(CanonicalId.newId(EntityFamily.RUN), otherTask.id(),
                    otherThread.id(), "INITIAL", null, otherManifest.id(), otherManifest.canonicalHash(), AT, null);
            assertEquals(CreateResult.SUCCESS, fixture.durable.createRun(otherRun));
            assertEquals(OwnershipResult.SUCCESS, fixture.durable.acquireOwnership(otherTask.id(),
                    "other-owner", otherRun.id(), "other-lease", AT, AT.plusSeconds(60)));
            Revision source = revision(otherTask.id(), otherRun.id(), 1, List.of());
            assertSuccess(fixture.plans.propose(new ExecutionAuthoritySnapshot(otherTask.id(),
                    otherRun.id(), "other-owner", "other-lease", 1, 1), AT.plusSeconds(1), source));

            Revision base = revision(fixture.task.id(), fixture.run.id(), 1, List.of());
            Lineage crossTask = new Lineage("lineage-cross-task", source.planRevisionId(),
                    source.steps().getFirst().stepRevisionId(), base.planRevisionId(),
                    base.steps().getFirst().stepRevisionId(), LineageRelationship.PRESERVED,
                    "explicit-test", AT.plusSeconds(2));
            Revision candidate = new Revision(base.planRevisionId(), base.taskId(), 1, base.goal(),
                    base.acceptanceCriteria(), base.assumptions(), base.risks(),
                    base.verificationStrategy(), "0".repeat(64), base.sourceType(), base.provenance(),
                    base.createdByRunId(), base.createdAt(), base.steps(), base.dependencies(),
                    List.of(crossTask), Revision.CANONICAL_SCHEMA_V3);
            Revision target = new Revision(candidate.planRevisionId(), candidate.taskId(), 1,
                    candidate.goal(), candidate.acceptanceCriteria(), candidate.assumptions(),
                    candidate.risks(), candidate.verificationStrategy(), PlanCanonicalizer.hash(candidate),
                    candidate.sourceType(), candidate.provenance(), candidate.createdByRunId(),
                    candidate.createdAt(), candidate.steps(), candidate.dependencies(), candidate.lineage(),
                    Revision.CANONICAL_SCHEMA_V3);
            assertEquals(PlanAuthorityStore.Status.STRUCTURE_INVALID,
                    fixture.plans.propose(fixture.authority, AT.plusSeconds(3), target).status());
            assertEquals(0, scalar(fixture.connection,
                    "SELECT count(*) FROM plan_revisions WHERE task_id='" + fixture.task.id().value() + "'"));
        }
    }

    @Test void v21UpgradeIsAdditiveIdempotentAndRollsBackOnFailure() throws Exception {
        Path path=tempDir.resolve("v21-upgrade");
        try(DatabaseManager manager=new DatabaseManager(path)){
            Connection connection=manager.getConnection();
            MigrationRunner runner=new MigrationRunner(connection);
            runner.runMigrationsThrough(20);
            assertFalse(objectExists(connection,"plan_step_executions"));
            runner.runMigrations();
            assertEquals(33,scalar(connection,
                    "SELECT max(version) FROM schema_migrations"));
            assertTrue(objectExists(connection,"plan_step_executions"));
            assertTrue(objectExists(connection,"plan_step_attempts"));
            runner.runMigrations();
            assertEquals(1,scalar(connection,
                    "SELECT count(*) FROM schema_migrations WHERE version=21"));
        }
        try(DatabaseManager manager=new DatabaseManager(tempDir.resolve("v21-rollback"))){
            Connection connection=manager.getConnection();
            MigrationRunner runner=new MigrationRunner(connection);
            runner.runMigrationsThrough(20);
            execute(connection,"CREATE TABLE plan_step_attempts(blocker TEXT)");
            assertThrows(SQLException.class,runner::runMigrations);
            assertEquals(20,scalar(connection,
                    "SELECT max(version) FROM schema_migrations"));
            assertFalse(objectExists(connection,"plan_step_executions"));
            assertFalse(objectExists(connection,"r1c_step_attempt_requires_binding"));
        }
    }

    @Test void stepStateDependencyAndAttemptHistoryAreDurableAndFenced() throws Exception {
        try(Fixture fixture=fixture("step-execution")){
            CanonicalId one=CanonicalId.newId(EntityFamily.PLAN_STEP);
            CanonicalId two=CanonicalId.newId(EntityFamily.PLAN_STEP);
            Revision revision=revision(fixture.task.id(),fixture.run.id(),1,one,two,
                    List.of(new Dependency(two,one)));
            assertSuccess(fixture.plans.propose(fixture.authority,AT.plusSeconds(1),revision));
            assertSuccess(fixture.plans.validateRevision(fixture.authority,AT.plusSeconds(2),
                    revision.planRevisionId(),0));
            assertSuccess(fixture.plans.approveAndActivate(fixture.authority,AT.plusSeconds(3),
                    revision.planRevisionId(),1,0,"approval","user","v1",null));

            PlanExecutionStore executions=new PlanExecutionStore(fixture.connection);
            var initialized=executions.readStates(fixture.task.id(),revision.planRevisionId());
            assertEquals(List.of(PlanExecutionModels.StepState.READY,
                            PlanExecutionModels.StepState.PENDING),
                    initialized.stream().map(PlanExecutionModels.StepExecution::state).toList());

            var first=executions.startNextReadyAttempt(fixture.authority,AT.plusSeconds(4),
                    0,"test");
            assertEquals(PlanExecutionStore.Status.SUCCESS,first.status());
            assertEquals(one,first.value().attempt().stepRevisionId());
            assertEquals(1,first.value().attempt().attemptNumber());
            assertEquals(PlanExecutionStore.Status.PLAN_ATTEMPT_UNRESOLVED,
                    executions.startNextReadyAttempt(fixture.authority,AT.plusSeconds(5),
                            0,"test").status());
            assertEquals(PlanExecutionStore.Status.VERSION_CONFLICT,
                    executions.completeAttempt(fixture.authority,AT.plusSeconds(5),
                            first.value().attempt(),PlanExecutionModels.Outcome.SUCCEEDED,
                            1,first.value().step().transitionVersion()-1,null,null).status());
            assertEquals(PlanExecutionModels.AttemptState.RUNNING,
                    executions.readAttempts(fixture.task.id(),revision.planRevisionId())
                            .get(0).state());
            assertEquals(PlanExecutionStore.Status.SUCCESS,
                    executions.completeAttempt(fixture.authority,AT.plusSeconds(6),
                            first.value().attempt(),PlanExecutionModels.Outcome.SUCCEEDED,
                            1,first.value().step().transitionVersion(),null,null).status());

            var second=executions.startNextReadyAttempt(fixture.authority,AT.plusSeconds(7),
                    1,"test");
            assertEquals(two,second.value().attempt().stepRevisionId());
            assertEquals(PlanExecutionStore.Status.SUCCESS,
                    executions.completeAttempt(fixture.authority,AT.plusSeconds(8),
                            second.value().attempt(),
                            PlanExecutionModels.Outcome.RETRYABLE_FAILURE,2,
                            second.value().step().transitionVersion(),
                            "TRANSIENT","bounded").status());
            var retry=executions.startNextReadyAttempt(fixture.authority,AT.plusSeconds(9),
                    2,"test");
            assertEquals(2,retry.value().attempt().attemptNumber());
            assertEquals(3,executions.readAttempts(fixture.task.id(),
                    revision.planRevisionId()).size());

            ExecutionAuthoritySnapshot stale=new ExecutionAuthoritySnapshot(fixture.task.id(),
                    fixture.run.id(),"owner","wrong",1,1);
            assertEquals(PlanExecutionStore.Status.LEASE_TOKEN_MISMATCH,
                    executions.completeAttempt(stale,AT.plusSeconds(10),retry.value().attempt(),
                            PlanExecutionModels.Outcome.SUCCEEDED,3,
                            retry.value().step().transitionVersion(),null,null).status());
            assertEquals(PlanExecutionModels.AttemptState.RUNNING,
                    executions.readAttempts(fixture.task.id(),revision.planRevisionId())
                            .stream().filter(attempt->attempt.stepRevisionId().equals(
                                    retry.value().attempt().stepRevisionId())
                                    &&attempt.attemptNumber()
                                    ==retry.value().attempt().attemptNumber())
                            .findFirst().orElseThrow().state());
        }
    }

    @Test void secondConnectionReadsTheSameRevisionStateAndAttemptHistory() throws Exception {
        CanonicalId task;
        CanonicalId revisionId;
        Path path=tempDir.resolve("step-cross-process");
        Fixture fixture=fixture("step-cross-process");
        try{
            task=fixture.task.id();
            CanonicalId one=CanonicalId.newId(EntityFamily.PLAN_STEP);
            CanonicalId two=CanonicalId.newId(EntityFamily.PLAN_STEP);
            Revision revision=revision(task,fixture.run.id(),1,one,two,
                    List.of(new Dependency(two,one)));
            revisionId=revision.planRevisionId();
            assertSuccess(fixture.plans.propose(fixture.authority,AT.plusSeconds(1),revision));
            assertSuccess(fixture.plans.validateRevision(fixture.authority,AT.plusSeconds(2),
                    revisionId,0));
            assertSuccess(fixture.plans.approveAndActivate(fixture.authority,AT.plusSeconds(3),
                    revisionId,1,0,"approval","user","v1",null));
            PlanExecutionStore executions=new PlanExecutionStore(fixture.connection);
            var started=executions.startNextReadyAttempt(fixture.authority,AT.plusSeconds(4),
                    0,"runtime-a");
            assertEquals(PlanExecutionStore.Status.SUCCESS,started.status());
            assertEquals(PlanExecutionStore.Status.SUCCESS,
                    executions.completeAttempt(fixture.authority,AT.plusSeconds(5),
                            started.value().attempt(),PlanExecutionModels.Outcome.SUCCEEDED,
                            1,started.value().step().transitionVersion(),null,null).status());
        }finally{
            fixture.close();
        }
        try(DatabaseManager reopened=new DatabaseManager(path)){
            new MigrationRunner(reopened.getConnection()).runMigrations();
            PlanExecutionStore executions=new PlanExecutionStore(reopened.getConnection());
            assertEquals(List.of(PlanExecutionModels.StepState.SUCCEEDED,
                            PlanExecutionModels.StepState.READY),
                    executions.readStates(task,revisionId).stream()
                            .map(PlanExecutionModels.StepExecution::state).toList());
            assertEquals(1,executions.readAttempts(task,revisionId).size());
        }
    }

    @Test void terminalFailureBlocksDependentsAndCannotRegress() throws Exception {
        try(Fixture fixture=fixture("step-terminal")){
            CanonicalId one=CanonicalId.newId(EntityFamily.PLAN_STEP);
            CanonicalId two=CanonicalId.newId(EntityFamily.PLAN_STEP);
            Revision revision=revision(fixture.task.id(),fixture.run.id(),1,one,two,
                    List.of(new Dependency(two,one)));
            assertSuccess(fixture.plans.propose(fixture.authority,AT.plusSeconds(1),revision));
            assertSuccess(fixture.plans.validateRevision(fixture.authority,AT.plusSeconds(2),
                    revision.planRevisionId(),0));
            assertSuccess(fixture.plans.approveAndActivate(fixture.authority,AT.plusSeconds(3),
                    revision.planRevisionId(),1,0,"approval","user","v1",null));
            PlanExecutionStore executions=new PlanExecutionStore(fixture.connection);
            var started=executions.startNextReadyAttempt(fixture.authority,AT.plusSeconds(4),
                    0,"test");
            assertEquals(PlanExecutionStore.Status.SUCCESS,
                    executions.completeAttempt(fixture.authority,AT.plusSeconds(5),
                            started.value().attempt(),PlanExecutionModels.Outcome.FAILED,
                            1,started.value().step().transitionVersion(),
                            "PERMANENT","bounded").status());
            assertEquals(List.of(PlanExecutionModels.StepState.FAILED,
                            PlanExecutionModels.StepState.BLOCKED),
                    executions.readStates(fixture.task.id(),revision.planRevisionId()).stream()
                            .map(PlanExecutionModels.StepExecution::state).toList());
            assertEquals(PlanExecutionStore.Status.NO_READY_PLAN_STEP,
                    executions.startNextReadyAttempt(fixture.authority,AT.plusSeconds(6),
                            1,"test").status());
            assertThrows(SQLException.class,()->execute(fixture.connection,
                    "UPDATE plan_step_executions SET state='RUNNING' "
                            +"WHERE step_revision_id='"+one.value()+"'"));
            assertThrows(SQLException.class,()->execute(fixture.connection,
                    "UPDATE plan_step_executions SET provenance='rewritten' "
                            +"WHERE step_revision_id='"+one.value()+"'"));
            assertThrows(SQLException.class,()->execute(fixture.connection,
                    "UPDATE plan_step_executions SET bounded_reason='rewritten' "
                            +"WHERE step_revision_id='"+one.value()+"'"));
            assertThrows(SQLException.class,()->execute(fixture.connection,
                    "DELETE FROM plan_step_executions WHERE step_revision_id='"
                            +one.value()+"'"));
            assertThrows(SQLException.class,()->execute(fixture.connection,
                    "UPDATE plan_step_attempts SET state='SUCCEEDED' "
                            +"WHERE step_revision_id='"+one.value()+"'"));
            assertThrows(SQLException.class,()->execute(fixture.connection,
                    "UPDATE plan_step_attempts SET bounded_reason='rewritten' "
                            +"WHERE step_revision_id='"+one.value()+"'"));
        }
    }

    @Test void partialStepInitializationRollsBackApprovalBindingAndState() throws Exception {
        try(Fixture fixture=fixture("step-init-rollback")){
            Revision revision=revision(fixture.task.id(),fixture.run.id(),1,List.of());
            assertSuccess(fixture.plans.propose(fixture.authority,AT.plusSeconds(1),revision));
            assertSuccess(fixture.plans.validateRevision(fixture.authority,AT.plusSeconds(2),
                    revision.planRevisionId(),0));
            String blocked=revision.steps().get(1).stepRevisionId().value();
            execute(fixture.connection,"CREATE TRIGGER test_fail_step_init "
                    +"BEFORE INSERT ON plan_step_executions "
                    +"WHEN NEW.step_revision_id='"+blocked+"' "
                    +"BEGIN SELECT RAISE(ABORT,'injected step init failure'); END");
            assertEquals(PlanAuthorityStore.Status.INTEGRITY_VIOLATION,
                    fixture.plans.approveAndActivate(fixture.authority,AT.plusSeconds(3),
                            revision.planRevisionId(),1,0,"approval","user","v1",null).status());
            assertEquals("VALIDATED",text(fixture.connection,
                    "SELECT state FROM plan_revision_lifecycle"));
            assertEquals(0,scalar(fixture.connection,
                    "SELECT count(*) FROM plan_approvals"));
            assertEquals(0,scalar(fixture.connection,
                    "SELECT count(*) FROM task_plan_authorities"));
            assertEquals(0,scalar(fixture.connection,
                    "SELECT count(*) FROM plan_run_bindings"));
            assertEquals(0,scalar(fixture.connection,
                    "SELECT count(*) FROM plan_step_executions"));
        }
    }

    @Test void fencedCancellationClosesRunningAttemptAndRemainingStepState() throws Exception {
        try(Fixture fixture=fixture("step-cancellation")){
            execute(fixture.connection,"INSERT INTO tasks(task_id,goal,status) VALUES('"
                    +fixture.task.id().value()+"','goal','RUNNING')");
            execute(fixture.connection,"INSERT INTO coding_runs(run_id,task_id,run_kind,phase) "
                    +"VALUES('"+fixture.run.id().value()+"','"+fixture.task.id().value()
                    +"','INITIAL','EXECUTING')");
            Revision revision=revision(fixture.task.id(),fixture.run.id(),1,List.of());
            assertSuccess(fixture.plans.propose(fixture.authority,AT.plusSeconds(1),revision));
            assertSuccess(fixture.plans.validateRevision(fixture.authority,AT.plusSeconds(2),
                    revision.planRevisionId(),0));
            assertSuccess(fixture.plans.approveAndActivate(fixture.authority,AT.plusSeconds(3),
                    revision.planRevisionId(),1,0,"approval","user","v1",null));
            PlanExecutionStore executions=new PlanExecutionStore(fixture.connection);
            assertEquals(PlanExecutionStore.Status.SUCCESS,
                    executions.startNextReadyAttempt(fixture.authority,AT.plusSeconds(4),
                            0,"test").status());
            assertEquals(com.paicli.state.R1aStoreResults.FencedMutationResult.SUCCESS,
                    new FencedMutationStore(fixture.connection).cancel(fixture.authority,
                            AT.plusSeconds(5),revision.planRevisionId().value(),false,"cancelled"));
            assertEquals(List.of(PlanExecutionModels.StepState.CANCELLED,
                            PlanExecutionModels.StepState.CANCELLED),
                    executions.readStates(fixture.task.id(),revision.planRevisionId()).stream()
                            .map(PlanExecutionModels.StepExecution::state).toList());
            assertEquals(PlanExecutionModels.AttemptState.CANCELLED,
                    executions.readAttempts(fixture.task.id(),revision.planRevisionId())
                            .get(0).state());
        }
    }

    private Fixture fixture(String name) throws Exception {
        DatabaseManager manager = new DatabaseManager(tempDir.resolve(name));
        Connection connection = manager.getConnection(); new MigrationRunner(connection).runMigrations();
        R1aDurableStore durable = new R1aDurableStore(connection);
        WorkThread thread = new WorkThread(CanonicalId.newId(EntityFamily.THREAD), "workspace", "goal", "OPEN", AT, AT);
        DurableTask task = new DurableTask(CanonicalId.newId(EntityFamily.TASK), thread.id(), null, "goal", "CREATED", null, null, AT, null);
        assertEquals(CreateResult.SUCCESS, durable.createThread(thread)); assertEquals(CreateResult.SUCCESS, durable.createTask(task));
        InstructionManifest manifest = commitManifest(durable, task.id());
        DurableRun run = new DurableRun(CanonicalId.newId(EntityFamily.RUN), task.id(), thread.id(), "INITIAL", null,
                manifest.id(), manifest.canonicalHash(), AT, null);
        assertEquals(CreateResult.SUCCESS, durable.createRun(run));
        assertEquals(OwnershipResult.SUCCESS, durable.acquireOwnership(task.id(), "owner", run.id(), "lease", AT, AT.plusSeconds(60)));
        return new Fixture(manager, connection, durable, new PlanAuthorityStore(connection), task, run,
                new ExecutionAuthoritySnapshot(task.id(), run.id(), "owner", "lease", 1, 1));
    }

    private static Revision revision(CanonicalId task, CanonicalId run, int number, List<Dependency> dependencies) {
        CanonicalId one = CanonicalId.newId(EntityFamily.PLAN_STEP);
        CanonicalId two = CanonicalId.newId(EntityFamily.PLAN_STEP);
        return revision(task, run, number, one, two, dependencies);
    }

    private static Revision singleStepRevision(CanonicalId task, CanonicalId run, int number,
            PlanCompletionModels.CompletionRuleType rule,
            List<PlanCompletionModels.EvidenceKind> kinds) {
        CanonicalId plan = CanonicalId.newId(EntityFamily.PLAN);
        List<ToolRequirement> requirements = kinds.contains(
                PlanCompletionModels.EvidenceKind.TOOL_CALL)
                ? List.of(readRequirement("single-read", 0, "AGENTS.md")) : List.of();
        StepDefinition step = new StepDefinition(CanonicalId.newId(EntityFamily.PLAN_STEP),
                CanonicalId.newId(EntityFamily.PLAN_STEP), 0, "one", "definition", rule, kinds,
                PlanCompletionModels.EffectClass.VERIFY, requirements, "b".repeat(64));
        Revision candidate = new Revision(plan, task, number, "goal", "accept", "", "",
                "verify", HASH, "MODEL", "test", run, AT,
                List.of(step), List.of(), List.of(), Revision.CANONICAL_SCHEMA_V3);
        return new Revision(plan, task, number, candidate.goal(), candidate.acceptanceCriteria(),
                candidate.assumptions(), candidate.risks(), candidate.verificationStrategy(),
                PlanCanonicalizer.hash(candidate), candidate.sourceType(), candidate.provenance(),
                run, AT, candidate.steps(), candidate.dependencies(), candidate.lineage(),
                Revision.CANONICAL_SCHEMA_V3);
    }

    private static void createRuntimeContract(Fixture fixture, String verificationCommand)
            throws SQLException {
        execute(fixture.connection, "INSERT INTO tasks(task_id,goal,status) VALUES('"
                + fixture.task.id().value() + "','goal','RUNNING')");
        execute(fixture.connection, "INSERT INTO coding_runs(run_id,task_id,run_kind,phase,"
                + "verification_command) VALUES('" + fixture.run.id().value() + "','"
                + fixture.task.id().value() + "','INITIAL','EXECUTING','"
                + verificationCommand + "')");
    }
    private static Revision revision(CanonicalId task, CanonicalId run, int number,
            CanonicalId one, CanonicalId two, List<Dependency> dependencies) {
        CanonicalId plan = CanonicalId.newId(EntityFamily.PLAN);
        Revision candidate = new Revision(plan, task, number, "goal", "accept", "none", "none", "verify", "0".repeat(64),
                "MODEL", "test", run, AT,
                List.of(new StepDefinition(one, CanonicalId.newId(EntityFamily.PLAN_STEP), 0, "one", "first",
                                PlanCompletionModels.CompletionRuleType.ALL_REQUIRED_EVIDENCE,
                                List.of(PlanCompletionModels.EvidenceKind.TOOL_CALL),
                                PlanCompletionModels.EffectClass.READ,
                                List.of(readRequirement("read-one", 0, "AGENTS.md")),
                                "b".repeat(64)),
                        new StepDefinition(two, CanonicalId.newId(EntityFamily.PLAN_STEP), 1, "two", "second",
                                PlanCompletionModels.CompletionRuleType.ALL_REQUIRED_EVIDENCE,
                                List.of(PlanCompletionModels.EvidenceKind.TOOL_CALL),
                                PlanCompletionModels.EffectClass.READ,
                                List.of(readRequirement("read-two", 0, "README.md")),
                                "c".repeat(64))),
                dependencies, List.of(), Revision.CANONICAL_SCHEMA_V3);
        return new Revision(plan,task,number,candidate.goal(),candidate.acceptanceCriteria(),
                candidate.assumptions(),candidate.risks(),candidate.verificationStrategy(),
                PlanCanonicalizer.hash(candidate),candidate.sourceType(),candidate.provenance(),run,AT,
                candidate.steps(),candidate.dependencies(),candidate.lineage(),
                Revision.CANONICAL_SCHEMA_V3);
    }
    private static ToolRequirement readRequirement(String id, int order, String path) {
        String canonicalJson = "{\"path\":\"" + path
                + "\",\"maxLines\":null,\"includeInternal\":false}";
        ToolAuthoritySelector selector = new ToolAuthoritySelector(
                AuthoritySelectorKind.EXACT_PATH, canonicalJson,
                PlanToolRequirementPolicy.digest(canonicalJson));
        return new ToolRequirement(id, order, "read_file",
                PlanCompletionModels.EffectClass.READ, selector, 1, 1,
                FreshnessPolicy.RESUME_REUSE_ONLY);
    }
    private static InstructionManifest commitManifest(R1aDurableStore store, CanonicalId taskId) throws Exception {
        ProtectedInstructionArtifact artifact = ProtectedInstructionArtifact.inlineUtf8("artifact-" + taskId.value().substring(4), "x", "v1", "task", AT);
        var entry = new InstructionManifestCandidate.Entry("entry-" + taskId.value().substring(4), "AGENTS", "AGENTS.md", "workspace",
                InstructionManifestCandidate.Status.APPLIED, 0, artifact.contentHash(), artifact.id(), "OK", null, null);
        return ((ManifestCommitResult.Success) store.commitManifest(taskId, "manifest-" + taskId.value().substring(4),
                new InstructionManifestCandidate(1, "v1", List.of(entry)), List.of(artifact), AT)).manifest();
    }
    private static void assertSuccess(PlanAuthorityStore.Mutation result) { assertEquals(PlanAuthorityStore.Status.SUCCESS, result.status()); }
    private static long scalar(Connection c, String sql) throws SQLException { try(Statement s=c.createStatement();var r=s.executeQuery(sql)){r.next();return r.getLong(1);} }
    private static String text(Connection c,String sql)throws SQLException{try(Statement s=c.createStatement();var r=s.executeQuery(sql)){r.next();return r.getString(1);}}
    private static void execute(Connection c,String sql)throws SQLException{try(Statement s=c.createStatement()){s.executeUpdate(sql);}}
    private static boolean objectExists(Connection c,String name)throws SQLException{
        try(var s=c.prepareStatement("SELECT 1 FROM sqlite_master WHERE name=?")){s.setString(1,name);
            try(var r=s.executeQuery()){return r.next();}}
    }
    private record Fixture(DatabaseManager manager, Connection connection, R1aDurableStore durable,
            PlanAuthorityStore plans,
            DurableTask task, DurableRun run, ExecutionAuthoritySnapshot authority) implements AutoCloseable {
        @Override public void close() throws Exception { manager.close(); }
    }
}
