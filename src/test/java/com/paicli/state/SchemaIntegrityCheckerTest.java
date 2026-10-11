package com.paicli.state;

import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.state.R1aDurableModels.DurableRun;
import com.paicli.state.R1aDurableModels.DurableTask;
import com.paicli.state.R1aDurableModels.TaskContextGeneration;
import com.paicli.state.R1aDurableModels.WorkThread;
import com.paicli.state.R1aStoreResults.ManifestCommitResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class SchemaIntegrityCheckerTest {
    private static final Instant AT = Instant.parse("2026-07-16T00:00:00Z");
    private static final String HASH = "a".repeat(64);

    @TempDir Path tempDir;

    @Test void cleanDatabaseHasNoFindingAndCheckerPerformsNoWrites() throws Exception {
        try (DatabaseManager manager = database("clean")) {
            Connection connection = manager.getConnection();
            long before = scalarLong(connection, "SELECT total_changes()");
            SchemaIntegrityChecker.IntegrityReport report =
                    new SchemaIntegrityChecker(connection).check();
            long after = scalarLong(connection, "SELECT total_changes()");
            assertTrue(report.clean(),report.findings().toString());
            assertEquals(List.of(), report.findings());
            assertEquals(before, after);
        }
    }

    @Test void detectsArtifactAndManifestHashTamperingWithoutExposingPayload() throws Exception {
        try (DatabaseManager manager = database("manifest")) {
            Connection connection = manager.getConnection();
            R1aDurableStore store = new R1aDurableStore(connection);
            Admitted admitted = admitted(store, "one");
            String secret = "secret-bearing-instruction";
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP TRIGGER r1a_artifact_immutable");
                statement.execute("DROP TRIGGER r1a_manifest_immutable");
                statement.execute("PRAGMA foreign_keys=OFF");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE protected_instruction_artifacts SET protected_payload=? "
                            + "WHERE artifact_id=?")) {
                statement.setBytes(1, secret.getBytes(StandardCharsets.UTF_8));
                statement.setString(2, admitted.artifactId());
                statement.executeUpdate();
            }
            execute(connection, "UPDATE instruction_manifests SET canonical_hash='"
                    + "f".repeat(64) + "' WHERE instruction_manifest_id='"
                    + admitted.manifest().id() + "'");
            execute(connection, "PRAGMA foreign_keys=ON");
            assertEquals(1, scalarLong(connection, "PRAGMA foreign_keys"));

            SchemaIntegrityChecker.IntegrityReport report =
                    new SchemaIntegrityChecker(connection).check();
            Set<String> codes = codes(report);
            assertTrue(codes.contains("ARTIFACT_HASH_MISMATCH"));
            assertTrue(codes.contains("MANIFEST_CANONICAL_HASH_MISMATCH"));
            assertTrue(codes.contains("SCHEMA_OBJECT_MISSING"));
            assertFalse(report.toString().contains(secret));
            assertTrue(store.findInlineArtifact(admitted.artifactId()).isEmpty());
        }
    }

    @Test void detectsCrossEntityOccurrenceOwnershipAndLegacyCorruptionReadOnly() throws Exception {
        try (DatabaseManager manager = database("relations")) {
            Connection connection = manager.getConnection();
            R1aDurableStore store = new R1aDurableStore(connection);
            Admitted first = admitted(store, "first");
            Admitted second = admitted(store, "second");
            TaskContextGeneration firstGeneration = generation(store, first);
            TaskContextGeneration secondGeneration = generation(store, second);
            store.appendConstraintRevision(new R1aDurableModels.ConstraintRevision(
                    "constraint-first", first.task().id(), 1, "{}", HASH, AT));

            execute(connection, "PRAGMA foreign_keys=OFF");
            assertEquals(0, scalarLong(connection, "PRAGMA foreign_keys"));
            execute(connection, "PRAGMA ignore_check_constraints=ON");
            execute(connection, "INSERT INTO work_threads(thread_id,workspace_identity,goal,status,"
                    + "created_at,updated_at) VALUES('thr_short','workspace-invalid','goal','OPEN','"
                    + AT + "','" + AT + "')");
            execute(connection, "UPDATE thread_heads SET head_task_id='"
                    + second.task().id().value() + "' WHERE thread_id='"
                    + first.thread().id().value() + "'");
            execute(connection, "INSERT INTO task_context_pointers(task_id,active_generation_id,"
                    + "owner_run_id,pointer_version) VALUES('" + first.task().id().value() + "','"
                    + secondGeneration.id().value() + "','" + first.run().id().value() + "',0)");
            execute(connection, "INSERT INTO task_constraint_activations(task_id,active_revision_id,"
                    + "pointer_version,activated_at) VALUES('" + second.task().id().value()
                    + "','constraint-first',0,'" + AT + "')");
            execute(connection, "INSERT INTO task_ownership_leases(task_id,owner_instance_id,"
                    + "owner_run_id,lease_token,fencing_token,cas_version,state,acquired_at,"
                    + "heartbeat_at,expires_at) VALUES('" + first.task().id().value()
                    + "','owner','" + second.run().id().value()
                    + "','lease',1,1,'ACTIVE','2026-07-16T00:00:02Z',"
                    + "'2026-07-16T00:00:01Z','2026-07-16T00:00:00Z')");
            execute(connection, "INSERT INTO side_effect_occurrences(occurrence_id,thread_id,"
                    + "task_id,run_id,iteration_id,logical_operation_key,operation_type,target,"
                    + "status,canonical_input_hash,started_at) VALUES("
                    + "'occ_9123456789ab4def8abc0123456789ab','"
                    + first.thread().id().value() + "','" + first.task().id().value() + "','"
                    + second.run().id().value()
                    + "','iteration','operation','WRITE','file','UNKNOWN','" + HASH + "','" + AT + "')");
            execute(connection, "INSERT INTO legacy_aliases(legacy_entity_family,legacy_value,"
                    + "canonical_target_id,classification,mapping_status,source_type,"
                    + "source_reference,provenance,continuation_status,migration_version,created_at)"
                    + " VALUES('TASK','legacy-no-checkpoint','" + first.task().id().value()
                    + "','LEGACY_UNCERTAIN','MAPPED','V1_TASK','tasks:legacy','v1.1 task',"
                    + "'ELIGIBLE',19,'" + AT + "')");
            execute(connection, "INSERT INTO legacy_aliases(legacy_entity_family,legacy_value,"
                    + "canonical_target_id,classification,mapping_status,source_type,"
                    + "source_reference,provenance,continuation_status,migration_version,created_at)"
                    + " VALUES('TASK','legacy-no-checkpoint','" + second.task().id().value()
                    + "','LEGACY_UNCERTAIN','MAPPED','V1_IMPORT','import:legacy','imported task',"
                    + "'NOT_ASSESSED',19,'" + AT + "')");
            execute(connection, "INSERT INTO legacy_aliases(legacy_entity_family,legacy_value,"
                    + "classification,mapping_status,source_type,source_reference,provenance,"
                    + "continuation_status,migration_version,created_at) VALUES("
                    + "'','untyped','LEGACY_UNCERTAIN','UNMAPPED','V1_IMPORT','import:untyped',"
                    + "'imported task','NOT_ASSESSED',19,'" + AT + "')");
            execute(connection, "INSERT INTO instruction_entries(entry_id,instruction_manifest_id,"
                    + "source_type,source_path,scope,status,observed_at) VALUES("
                    + "'entry-failed-000001','" + first.manifest().id()
                    + "','PAICLI','PAICLI.md','workspace','FAILED','" + AT + "')");
            execute(connection, "PRAGMA ignore_check_constraints=OFF");
            execute(connection, "PRAGMA foreign_keys=ON");
            assertEquals(1, scalarLong(connection, "PRAGMA foreign_keys"));
            long before = scalarLong(connection, "SELECT total_changes()");

            SchemaIntegrityChecker.IntegrityReport report =
                    new SchemaIntegrityChecker(connection).check();
            Set<String> codes = codes(report);
            assertTrue(codes.contains("THREAD_HEAD_CROSS_THREAD"));
            assertTrue(codes.contains("TYPED_ID_INVALID"));
            assertTrue(codes.contains("CONTEXT_POINTER_INVALID"));
            assertTrue(codes.contains("CONSTRAINT_ACTIVATION_INVALID"));
            assertTrue(codes.contains("OCCURRENCE_RUN_MISMATCH"));
            assertTrue(codes.contains("OCCURRENCE_STATE_EVIDENCE_INVALID"));
            assertTrue(codes.contains("OCCURRENCE_RECOVERY_REVIEW_REQUIRED"));
            assertTrue(codes.contains("OWNERSHIP_RUN_TASK_MISMATCH"));
            assertTrue(codes.contains("OWNERSHIP_TIME_INVALID"));
            assertTrue(codes.contains("LEGACY_RELATION_FABRICATED"));
            assertTrue(codes.contains("LEGACY_CONTINUATION_WITHOUT_CHECKPOINT"));
            assertTrue(codes.contains("LEGACY_ALIAS_AMBIGUOUS"));
            assertTrue(codes.contains("LEGACY_ALIAS_TYPE_MISSING"));
            assertTrue(codes.contains("MANIFEST_ENTRY_INVALID"));
            assertEquals(before, scalarLong(connection, "SELECT total_changes()"));
            assertNotNull(firstGeneration);
        }
    }

    @Test void missingSchemaIsFindingButInfrastructureSqlFailureIsExplicit() throws Exception {
        try (DatabaseManager manager = database("failure")) {
            Connection connection = manager.getConnection();
            execute(connection, "DROP TABLE task_context_pointers");
            SchemaIntegrityChecker.IntegrityReport report =
                    new SchemaIntegrityChecker(connection).check();
            assertFalse(report.clean());
            assertTrue(codes(report).contains("SCHEMA_OBJECT_MISSING"));
            execute(connection, "DROP TABLE schema_migrations");
            assertThrows(SQLException.class,
                    () -> new SchemaIntegrityChecker(connection).check());
        }
    }

    @Test void canonicalProfileVersionTriggerIsRequiredSchemaAuthority() throws Exception {
        try (DatabaseManager manager = database("canonical-trigger")) {
            Connection connection = manager.getConnection();
            execute(connection, "DROP TRIGGER r1c_step_completion_profile_version");
            SchemaIntegrityChecker.IntegrityReport report =
                    new SchemaIntegrityChecker(connection).check();
            assertTrue(codes(report).contains("SCHEMA_OBJECT_MISSING"));
        }
    }

    @Test void r1eCapsuleAndParentImmutabilityTriggersAreRequiredSchemaAuthority()throws Exception{
        try(DatabaseManager manager=database("r1e-triggers")){Connection connection=manager.getConnection();
            execute(connection,"DROP TRIGGER r1e_capsule_immutable");
            execute(connection,"DROP TRIGGER r1e_parent_row_immutable");
            SchemaIntegrityChecker.IntegrityReport report=new SchemaIntegrityChecker(connection).check();
            assertTrue(codes(report).contains("SCHEMA_OBJECT_MISSING"));
        }
    }

    @Test void r1eChildInitialRunLaunchAuthorityIsRequiredSchema()throws Exception{
        try(DatabaseManager manager=database("r1e-child-launch")){Connection connection=manager.getConnection();
            execute(connection,"DROP TRIGGER r1e_child_launch_transition");
            SchemaIntegrityChecker.IntegrityReport report=new SchemaIntegrityChecker(connection).check();
            assertTrue(codes(report).contains("SCHEMA_OBJECT_MISSING"));
        }
    }

    private Admitted admitted(R1aDurableStore store, String suffix) throws Exception {
        WorkThread thread = new WorkThread(CanonicalId.newId(EntityFamily.THREAD),
                "workspace-" + suffix, "goal", "OPEN", AT, AT);
        DurableTask task = new DurableTask(CanonicalId.newId(EntityFamily.TASK), thread.id(),
                null, "goal", "CREATED", null, null, AT, null);
        assertEquals(R1aStoreResults.CreateResult.SUCCESS, store.createThread(thread));
        assertEquals(R1aStoreResults.CreateResult.SUCCESS, store.createTask(task));
        String artifactId = "artifact-" + suffix + "-000000";
        ProtectedInstructionArtifact artifact = ProtectedInstructionArtifact.inlineUtf8(
                artifactId, "content-" + suffix, "v1", "task", AT);
        var entry = new InstructionManifestCandidate.Entry("entry-" + suffix + "-000000",
                "AGENTS", "AGENTS.md", "workspace",
                InstructionManifestCandidate.Status.APPLIED, 0, artifact.contentHash(),
                artifact.id(), "OK", null, null);
        ManifestCommitResult result = store.commitManifest(task.id(),
                "manifest-" + suffix + "-000000",
                new InstructionManifestCandidate(1, "v1", List.of(entry)), List.of(artifact), AT);
        InstructionManifest manifest = ((ManifestCommitResult.Success) result).manifest();
        DurableRun run = new DurableRun(CanonicalId.newId(EntityFamily.RUN), task.id(),
                thread.id(), "INITIAL", null, manifest.id(), manifest.canonicalHash(), AT, null);
        assertEquals(R1aStoreResults.CreateResult.SUCCESS, store.createRun(run));
        return new Admitted(thread, task, run, manifest, artifactId);
    }

    private static TaskContextGeneration generation(R1aDurableStore store, Admitted admitted)
            throws Exception {
        TaskContextGeneration generation = new TaskContextGeneration(
                CanonicalId.newId(EntityFamily.GENERATION), admitted.task().id(), null,
                admitted.run().id(), admitted.manifest().id(),
                admitted.manifest().canonicalHash(), "COMMITTED", "{}", AT, AT);
        assertEquals(R1aStoreResults.CreateResult.SUCCESS, store.createGeneration(generation));
        return generation;
    }

    private DatabaseManager database(String name) throws Exception {
        DatabaseManager manager = new DatabaseManager(tempDir.resolve(name));
        new MigrationRunner(manager.getConnection()).runMigrations();
        assertEquals(1, scalarLong(manager.getConnection(), "PRAGMA foreign_keys"));
        return manager;
    }

    private static Set<String> codes(SchemaIntegrityChecker.IntegrityReport report) {
        return report.findings().stream().map(SchemaIntegrityChecker.Finding::code)
                .collect(Collectors.toSet());
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long scalarLong(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private record Admitted(WorkThread thread, DurableTask task, DurableRun run,
                            InstructionManifest manifest, String artifactId) { }
}
