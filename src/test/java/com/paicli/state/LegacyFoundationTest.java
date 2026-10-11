package com.paicli.state;

import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.identity.LegacyId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LegacyFoundationTest {
    @TempDir Path tempDir;

    @Test void dryRunIsStableReadOnlyAndNeverInfersContinuity() throws Exception {
        try (DatabaseManager manager = database("dry-run")) {
            Connection connection = manager.getConnection();
            execute(connection, "INSERT INTO tasks(task_id,goal,status) "
                    + "VALUES('legacy-with-checkpoint','goal-a','RUNNING')");
            execute(connection, "INSERT INTO tasks(task_id,goal,status) "
                    + "VALUES('legacy-without-checkpoint','goal-b','COMPLETED')");
            execute(connection, "INSERT INTO coding_runs(run_id,task_id,run_kind,phase) "
                    + "VALUES('legacy-run','legacy-with-checkpoint','INITIAL','EXECUTING')");
            execute(connection, "INSERT INTO checkpoints(task_id,iteration,status,messages_json) "
                    + "VALUES('legacy-with-checkpoint',2,'RUNNING','[]')");
            Map<String, Integer> before = authoritativeCounts(connection);

            LegacyBackfillPlanner planner = new LegacyBackfillPlanner(connection);
            LegacyBackfillPlanner.DryRunPlan first = planner.dryRun();
            LegacyBackfillPlanner.DryRunPlan second = planner.dryRun();

            assertEquals(first, second);
            assertEquals(before, authoritativeCounts(connection));
            assertEquals(2, first.items().size());
            var withCheckpoint = first.items().stream()
                    .filter(item -> item.legacyTaskId().equals("legacy-with-checkpoint"))
                    .findFirst().orElseThrow();
            assertEquals(LegacyBackfillPlanner.Disposition.ELIGIBLE,
                    withCheckpoint.disposition());
            assertEquals(LegacyBackfillPlanner.ThreadClassification.LEGACY_UNCERTAIN,
                    withCheckpoint.threadClassification());
            assertFalse(withCheckpoint.inferredParentChild());
            assertFalse(withCheckpoint.createsGeneration());

            var withoutCheckpoint = first.items().stream()
                    .filter(item -> item.legacyTaskId().equals("legacy-without-checkpoint"))
                    .findFirst().orElseThrow();
            assertEquals(LegacyBackfillPlanner.Disposition.CONTINUATION_UNAVAILABLE,
                    withoutCheckpoint.disposition());
            assertEquals(LegacyBackfillPlanner.ContinuationAssessment.CONTINUATION_UNAVAILABLE,
                    withoutCheckpoint.continuationAssessment());
            assertEquals(0, scalar(connection, "SELECT count(*) FROM work_threads"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM task_identities"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM task_context_generations"));
        }
    }

    @Test void typedAliasMappingIsExplicitIdempotentAndAmbiguityFailsClosed() throws Exception {
        try (DatabaseManager manager = database("alias")) {
            LegacyAliasStore store = new LegacyAliasStore(manager.getConnection());
            Instant at = Instant.parse("2026-07-16T00:00:00Z");
            LegacyId legacy = new LegacyId(EntityFamily.TASK, "legacy-task");
            CanonicalId firstTarget = CanonicalId.parse(
                    "tsk_0123456789ab4def8abc0123456789ab");
            CanonicalId secondTarget = CanonicalId.parse(
                    "tsk_1123456789ab4def8abc0123456789ab");
            LegacyAlias first = new LegacyAlias(legacy, firstTarget,
                    LegacyAlias.Classification.LEGACY_UNCERTAIN,
                    LegacyAlias.MappingStatus.MAPPED, "V1_TASK", "tasks:legacy-task",
                    "v1.1 tasks.task_id", LegacyAlias.ContinuationStatus.NOT_ASSESSED, 19, at);
            assertEquals(LegacyAliasStore.AddResult.SUCCESS, store.add(first));
            assertEquals(LegacyAliasStore.AddResult.ALREADY_EXISTS, store.add(first));
            assertInstanceOf(LegacyAliasStore.Resolution.Mapped.class, store.resolve(legacy));

            LegacyAlias conflicting = new LegacyAlias(legacy, secondTarget,
                    LegacyAlias.Classification.LEGACY_UNCERTAIN,
                    LegacyAlias.MappingStatus.MAPPED, "V1_IMPORT", "import:legacy-task",
                    "independent import", LegacyAlias.ContinuationStatus.NOT_ASSESSED, 19, at);
            assertEquals(LegacyAliasStore.AddResult.AMBIGUOUS_LEGACY_ID,
                    store.add(conflicting));
            assertEquals(1, scalar(manager.getConnection(),
                    "SELECT count(*) FROM legacy_aliases"));
            assertThrows(IllegalArgumentException.class,
                    () -> new LegacyAlias(new LegacyId(EntityFamily.TASK, "legacy"),
                            CanonicalId.newId(EntityFamily.RUN),
                            LegacyAlias.Classification.LEGACY_UNCERTAIN,
                            LegacyAlias.MappingStatus.MAPPED, "source", "reference", "provenance",
                            LegacyAlias.ContinuationStatus.NOT_ASSESSED, 19, at));
        }
    }

    @Test void aliasSchemaRejectsUntypedOrMismatchedCanonicalTargets() throws Exception {
        try (DatabaseManager manager = database("alias-schema")) {
            Connection connection = manager.getConnection();
            assertThrows(SQLException.class, () -> execute(connection, """
                    INSERT INTO legacy_aliases(
                      legacy_entity_family,legacy_value,canonical_target_id,classification,
                      mapping_status,source_type,source_reference,provenance,
                      continuation_status,migration_version,created_at)
                    VALUES('','legacy',NULL,'LEGACY_UNCERTAIN','UNMAPPED','source','ref',
                           'provenance','NOT_ASSESSED',19,'2026-07-16T00:00:00Z')
                    """));
            assertThrows(SQLException.class, () -> execute(connection, """
                    INSERT INTO legacy_aliases(
                      legacy_entity_family,legacy_value,canonical_target_id,classification,
                      mapping_status,source_type,source_reference,provenance,
                      continuation_status,migration_version,created_at)
                    VALUES('TASK','legacy','run_0123456789ab4def8abc0123456789ab',
                           'LEGACY_UNCERTAIN','MAPPED','source','ref','provenance',
                           'NOT_ASSESSED',19,'2026-07-16T00:00:00Z')
                    """));
        }
    }

    private DatabaseManager database(String name) throws Exception {
        DatabaseManager manager = new DatabaseManager(tempDir.resolve(name));
        new MigrationRunner(manager.getConnection()).runMigrations();
        assertEquals(1, scalar(manager.getConnection(), "PRAGMA foreign_keys"));
        return manager;
    }

    private static Map<String, Integer> authoritativeCounts(Connection connection)
            throws SQLException {
        return Map.of(
                "threads", scalar(connection, "SELECT count(*) FROM work_threads"),
                "tasks", scalar(connection, "SELECT count(*) FROM task_identities"),
                "generations", scalar(connection, "SELECT count(*) FROM task_context_generations"),
                "aliases", scalar(connection, "SELECT count(*) FROM legacy_aliases"));
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static int scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getInt(1);
        }
    }
}
