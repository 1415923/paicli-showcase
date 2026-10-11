package com.paicli.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

class R1aMigrationTest {
    private static final Set<String> R1A_TABLES = Set.of("work_threads", "task_identities",
            "thread_heads", "durable_runs", "constraint_revisions",
            "task_constraint_activations", "protected_instruction_artifacts",
            "instruction_manifests", "instruction_entries", "task_context_generations",
            "task_context_pointers", "side_effect_occurrences", "task_ownership_leases",
            "legacy_aliases");
    @TempDir Path tempDir;

    @Test void freshDatabaseReachesV19WithForeignKeysTablesAndIndexes() throws Exception {
        try (DatabaseManager manager = new DatabaseManager(tempDir.resolve("fresh"))) {
            Connection connection = manager.getConnection();
            new MigrationRunner(connection).runMigrationsThrough(19);
            assertEquals(1, scalar(connection, "PRAGMA foreign_keys"));
            assertEquals(19, scalar(connection, "SELECT max(version) FROM schema_migrations"));
            assertEquals(R1A_TABLES, r1aTables(connection));
            for (String index : Set.of("idx_r1a_task_thread_created", "idx_r1a_runs_task_created",
                    "idx_r1a_generations_task_created", "idx_r1a_constraint_task_revision",
                    "idx_r1a_manifest_entries_order", "idx_r1a_occurrence_task_status",
                    "idx_r1a_occurrence_run_operation", "idx_r1a_legacy_alias_lookup",
                    "idx_r1a_legacy_alias_target")) assertTrue(objectExists(connection, "index", index));
            for (String trigger : Set.of("r1a_constraint_revision_immutable",
                    "r1a_manifest_immutable", "r1a_manifest_entry_immutable",
                    "r1a_artifact_immutable", "r1a_occurrence_no_regression")) {
                assertTrue(objectExists(connection, "trigger", trigger));
            }
            new MigrationRunner(connection).runMigrationsThrough(19);
            assertEquals(19, scalar(connection, "SELECT count(*) FROM schema_migrations"));
        }
    }

    @Test void migrationRunnerEnablesAndVerifiesForeignKeysOnRawConnection() throws Exception {
        Path database = tempDir.resolve("raw-connection.db");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            assertEquals(0, scalar(connection, "PRAGMA foreign_keys"));
            new MigrationRunner(connection).runMigrationsThrough(19);
            assertEquals(1, scalar(connection, "PRAGMA foreign_keys"));
            assertEquals(19, scalar(connection, "SELECT max(version) FROM schema_migrations"));
        }
    }

    @Test void v11ShapeUpgradePreservesRepresentativeDataAndDoesNotInventR1Facts() throws Exception {
        try (DatabaseManager manager = new DatabaseManager(tempDir.resolve("upgrade"))) {
            Connection connection = manager.getConnection();
            MigrationRunner runner = new MigrationRunner(connection);
            runner.runMigrationsThrough(18);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO tasks(task_id,goal,status) VALUES('legacy-task','goal','RUNNING')");
                statement.executeUpdate("INSERT INTO coding_runs(run_id,task_id,run_kind,phase) VALUES('legacy-run','legacy-task','INITIAL','EXECUTING')");
                statement.executeUpdate("INSERT INTO checkpoints(task_id,iteration,status,messages_json) VALUES('legacy-task',2,'RUNNING','[]')");
                statement.executeUpdate("INSERT INTO file_snapshots(snapshot_id,task_id,file_path,operation,original_exists,original_size,status) VALUES('legacy-snapshot','legacy-task','A.java','MODIFY',1,1,'CAPTURED')");
            }
            runner.runMigrationsThrough(19);
            assertEquals(1, scalar(connection, "SELECT count(*) FROM tasks WHERE task_id='legacy-task'"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM coding_runs WHERE run_id='legacy-run'"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM checkpoints WHERE task_id='legacy-task'"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM file_snapshots WHERE snapshot_id='legacy-snapshot'"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM work_threads"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM task_identities"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM legacy_aliases"));
            assertEquals(R1A_TABLES, r1aTables(connection));
            runner.runMigrationsThrough(19);
            assertEquals(19, scalar(connection, "SELECT count(*) FROM schema_migrations"));
        }
    }

    @Test void failedV19MigrationRollsBackAllEarlierV19Statements() throws Exception {
        try (DatabaseManager manager = new DatabaseManager(tempDir.resolve("rollback"))) {
            Connection connection = manager.getConnection();
            MigrationRunner runner = new MigrationRunner(connection);
            runner.runMigrationsThrough(18);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE task_context_generations(blocker TEXT)");
            }
            assertThrows(SQLException.class, () -> runner.runMigrationsThrough(19));
            assertFalse(objectExists(connection, "table", "work_threads"));
            assertFalse(objectExists(connection, "table", "instruction_manifests"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM schema_migrations WHERE version=19"));
        }
    }

    @Test void freshAndUpgradeProduceTheSameR1aSchemaObjects() throws Exception {
        try (DatabaseManager fresh = new DatabaseManager(tempDir.resolve("compare-fresh"));
             DatabaseManager upgrade = new DatabaseManager(tempDir.resolve("compare-upgrade"))) {
            new MigrationRunner(fresh.getConnection()).runMigrations();
            MigrationRunner runner = new MigrationRunner(upgrade.getConnection());
            runner.runMigrationsThrough(18); runner.runMigrations();
            assertEquals(r1aObjects(fresh.getConnection()), r1aObjects(upgrade.getConnection()));
        }
    }

    private static Set<String> r1aTables(Connection connection) throws SQLException {
        Set<String> result = new TreeSet<>();
        try (Statement statement=connection.createStatement(); ResultSet rows=statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
            while(rows.next()) if(R1A_TABLES.contains(rows.getString(1))) result.add(rows.getString(1));
        }
        return result;
    }
    private static Set<String> r1aObjects(Connection connection) throws SQLException {
        Set<String> result=new TreeSet<>();
        try(Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery("SELECT type||':'||name||':'||sql FROM sqlite_master WHERE name LIKE '%r1a%' OR name IN ('work_threads','task_identities','thread_heads','durable_runs','constraint_revisions','task_constraint_activations','protected_instruction_artifacts','instruction_manifests','instruction_entries','task_context_generations','task_context_pointers','side_effect_occurrences','task_ownership_leases','legacy_aliases')")){while(rows.next())result.add(rows.getString(1));}
        return result;
    }
    private static boolean objectExists(Connection connection,String type,String name)throws SQLException{try(var s=connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type=? AND name=?")){s.setString(1,type);s.setString(2,name);try(var r=s.executeQuery()){return r.next();}}}
    private static int scalar(Connection connection,String sql)throws SQLException{try(Statement s=connection.createStatement();ResultSet r=s.executeQuery(sql)){assertTrue(r.next());return r.getInt(1);}}
}
