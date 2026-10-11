package com.paicli.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class R1aCoreConsistencyTest {
    private static final String THREAD_A = "thr_0123456789ab4def8abc0123456789ab";
    private static final String THREAD_B = "thr_1123456789ab4def8abc0123456789ab";
    private static final String TASK_A = "tsk_2123456789ab4def8abc0123456789ab";
    private static final String TASK_B = "tsk_3123456789ab4def8abc0123456789ab";
    private static final String RUN_A = "run_4123456789ab4def8abc0123456789ab";
    private static final String RUN_B = "run_5123456789ab4def8abc0123456789ab";
    private static final String GEN_A = "gen_6123456789ab4def8abc0123456789ab";
    private static final String OCC_A = "occ_7123456789ab4def8abc0123456789ab";
    private static final String MANIFEST_A = "manifest-a-000001";
    private static final String MANIFEST_B = "manifest-b-000001";
    private static final String MISSING_MANIFEST = "manifest-missing-1";
    private static final String CONSTRAINT_A = "constraint-a-0001";
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);
    private static final String AT = "2026-07-16T00:00:00Z";

    @TempDir Path tempDir;

    @Test void databaseUuidChecksRejectEveryNonCanonicalShape() throws Exception {
        try (DatabaseManager manager = database("uuid")) {
            Connection connection = manager.getConnection();
            for (String invalid : List.of(
                    "thr_0123456789ab4def8Abc0123456789ab",
                    "thr_0123456789ab4def8gbc0123456789ab",
                    "thr_0123456789ab3def8abc0123456789ab",
                    "thr_0123456789ab4def7abc0123456789ab",
                    "tsk_0123456789ab4def8abc0123456789ab",
                    "thr_0123456789ab4def8abc0123456789a")) {
                assertThrows(SQLException.class, () -> insertThread(connection, invalid));
            }
            insertThread(connection, THREAD_A);
            assertEquals(1, scalar(connection,
                    "SELECT count(*) FROM work_threads WHERE thread_id='" + THREAD_A + "'"));
        }
    }

    @Test void compositeForeignKeysRejectCrossThreadAndCrossTaskRelationships() throws Exception {
        try (DatabaseManager manager = database("composite")) {
            Connection connection = manager.getConnection();
            seedAuthorities(connection);
            assertThrows(SQLException.class, () -> execute(connection,
                    "INSERT INTO thread_heads(thread_id,head_task_id,pointer_version) VALUES('"
                            + THREAD_A + "','" + TASK_B + "',0)"));
            assertThrows(SQLException.class, () -> execute(connection,
                    "INSERT INTO durable_runs(run_id,task_id,thread_id,run_kind,"
                            + "instruction_manifest_id,instruction_manifest_hash,created_at) VALUES("
                            + "'run_8123456789ab4def8abc0123456789ab','" + TASK_A + "','"
                            + THREAD_A + "','INITIAL','" + MANIFEST_B + "','" + HASH_B + "','" + AT + "')"));
            execute(connection, "INSERT INTO task_context_generations(generation_id,task_id,"
                    + "created_by_run_id,instruction_manifest_id,instruction_manifest_hash,state,"
                    + "created_at,committed_at) VALUES('" + GEN_A + "','" + TASK_A + "','" + RUN_A
                    + "','" + MANIFEST_A + "','" + HASH_A + "','COMMITTED','" + AT + "','" + AT + "')");
            assertThrows(SQLException.class, () -> execute(connection,
                    "INSERT INTO task_context_pointers(task_id,active_generation_id,owner_run_id,"
                            + "pointer_version) VALUES('" + TASK_B + "','" + GEN_A + "','" + RUN_B + "',0)"));
            execute(connection, "INSERT INTO constraint_revisions(constraint_revision_id,task_id,"
                    + "revision_number,canonical_content,canonical_hash,created_at) VALUES("
                    + "'" + CONSTRAINT_A + "','" + TASK_A + "',1,'{}','" + HASH_A + "','" + AT + "')");
            assertThrows(SQLException.class, () -> execute(connection,
                    "INSERT INTO task_constraint_activations(task_id,active_revision_id,"
                            + "pointer_version,activated_at) VALUES('" + TASK_B
                            + "','" + CONSTRAINT_A + "',0,'" + AT + "')"));
            assertThrows(SQLException.class, () -> execute(connection,
                    "INSERT INTO task_ownership_leases(task_id,owner_instance_id,owner_run_id,"
                            + "lease_token,fencing_token,cas_version,state,acquired_at,heartbeat_at,"
                            + "expires_at) VALUES('" + TASK_B + "','owner','" + RUN_A
                            + "','lease',1,1,'ACTIVE','" + AT + "','" + AT
                            + "','2026-07-16T00:01:00Z')"));
        }
    }

    @Test void deferredManifestAuthoritySucceedsOnlyWhenSatisfiedAtCommit() throws Exception {
        try (DatabaseManager manager = database("deferred")) {
            Connection connection = manager.getConnection();
            insertThread(connection, THREAD_A);
            connection.setAutoCommit(false);
            execute(connection, "INSERT INTO task_identities(task_id,thread_id,goal,status,"
                    + "admitted_manifest_id,admitted_manifest_hash,created_at) VALUES('" + TASK_A
                    + "','" + THREAD_A + "','goal','CREATED','" + MANIFEST_A + "','" + HASH_A + "','" + AT + "')");
            execute(connection, "INSERT INTO instruction_manifests(instruction_manifest_id,task_id,"
                    + "schema_version,resolution_policy_version,canonical_hash,state,created_at) VALUES("
                    + "'" + MANIFEST_A + "','" + TASK_A + "',1,'v1','" + HASH_A
                    + "','COMMITTED','" + AT + "')");
            connection.commit();
            connection.setAutoCommit(true);
            assertEquals(1, scalar(connection,
                    "SELECT count(*) FROM task_identities WHERE task_id='" + TASK_A + "'"));

            connection.setAutoCommit(false);
            execute(connection, "INSERT INTO task_identities(task_id,thread_id,goal,status,"
                    + "admitted_manifest_id,admitted_manifest_hash,created_at) VALUES('" + TASK_B
                    + "','" + THREAD_A + "','goal','CREATED','" + MISSING_MANIFEST + "','" + HASH_B + "','" + AT + "')");
            assertThrows(SQLException.class, connection::commit);
            connection.rollback();
            connection.setAutoCommit(true);
            assertEquals(0, scalar(connection,
                    "SELECT count(*) FROM task_identities WHERE task_id='" + TASK_B + "'"));
        }
    }

    @Test void immutableTriggersRollbackUpdatesAndOccurrenceCannotRegress() throws Exception {
        try (DatabaseManager manager = database("trigger")) {
            Connection connection = manager.getConnection();
            seedAuthorities(connection);
            execute(connection, "INSERT INTO constraint_revisions(constraint_revision_id,task_id,"
                    + "revision_number,canonical_content,canonical_hash,created_at) VALUES("
                    + "'" + CONSTRAINT_A + "','" + TASK_A + "',1,'{}','" + HASH_A + "','" + AT + "')");
            assertThrows(SQLException.class, () -> execute(connection,
                    "UPDATE constraint_revisions SET canonical_content='changed' "
                            + "WHERE constraint_revision_id='" + CONSTRAINT_A + "'"));
            assertEquals("{}", stringScalar(connection,
                    "SELECT canonical_content FROM constraint_revisions "
                            + "WHERE constraint_revision_id='" + CONSTRAINT_A + "'"));
            execute(connection, "INSERT INTO side_effect_occurrences(occurrence_id,thread_id,task_id,"
                    + "run_id,iteration_id,logical_operation_key,operation_type,target,status,"
                    + "canonical_input_hash,result_hash,started_at,completed_at) VALUES('" + OCC_A
                    + "','" + THREAD_A + "','" + TASK_A + "','" + RUN_A
                    + "','iteration-1','operation-1','WRITE','file','COMPLETED','" + HASH_A
                    + "','" + HASH_B + "','" + AT + "','2026-07-16T00:00:01Z')");
            assertThrows(SQLException.class, () -> execute(connection,
                    "UPDATE side_effect_occurrences SET status='STARTED',completed_at=NULL "
                            + "WHERE occurrence_id='" + OCC_A + "'"));
            assertEquals("COMPLETED", stringScalar(connection,
                    "SELECT status FROM side_effect_occurrences WHERE occurrence_id='" + OCC_A + "'"));
        }
    }

    private DatabaseManager database(String name) throws Exception {
        DatabaseManager manager = new DatabaseManager(tempDir.resolve(name));
        new MigrationRunner(manager.getConnection()).runMigrations();
        assertEquals(1, scalar(manager.getConnection(), "PRAGMA foreign_keys"));
        return manager;
    }

    private static void seedAuthorities(Connection connection) throws SQLException {
        insertThread(connection, THREAD_A);
        insertThread(connection, THREAD_B);
        execute(connection, "INSERT INTO task_identities(task_id,thread_id,goal,status,created_at) "
                + "VALUES('" + TASK_A + "','" + THREAD_A + "','a','CREATED','" + AT + "')");
        execute(connection, "INSERT INTO task_identities(task_id,thread_id,goal,status,created_at) "
                + "VALUES('" + TASK_B + "','" + THREAD_B + "','b','CREATED','" + AT + "')");
        execute(connection, "INSERT INTO instruction_manifests(instruction_manifest_id,task_id,"
                + "schema_version,resolution_policy_version,canonical_hash,state,created_at) VALUES("
                + "'" + MANIFEST_A + "','" + TASK_A + "',1,'v1','" + HASH_A + "','COMMITTED','" + AT + "')");
        execute(connection, "INSERT INTO instruction_manifests(instruction_manifest_id,task_id,"
                + "schema_version,resolution_policy_version,canonical_hash,state,created_at) VALUES("
                + "'" + MANIFEST_B + "','" + TASK_B + "',1,'v1','" + HASH_B + "','COMMITTED','" + AT + "')");
        execute(connection, "UPDATE task_identities SET admitted_manifest_id='" + MANIFEST_A + "',"
                + "admitted_manifest_hash='" + HASH_A + "' WHERE task_id='" + TASK_A + "'");
        execute(connection, "UPDATE task_identities SET admitted_manifest_id='" + MANIFEST_B + "',"
                + "admitted_manifest_hash='" + HASH_B + "' WHERE task_id='" + TASK_B + "'");
        execute(connection, "INSERT INTO durable_runs(run_id,task_id,thread_id,run_kind,"
                + "instruction_manifest_id,instruction_manifest_hash,created_at) VALUES('" + RUN_A
                + "','" + TASK_A + "','" + THREAD_A + "','INITIAL','" + MANIFEST_A + "','" + HASH_A
                + "','" + AT + "')");
        execute(connection, "INSERT INTO durable_runs(run_id,task_id,thread_id,run_kind,"
                + "instruction_manifest_id,instruction_manifest_hash,created_at) VALUES('" + RUN_B
                + "','" + TASK_B + "','" + THREAD_B + "','INITIAL','" + MANIFEST_B + "','" + HASH_B
                + "','" + AT + "')");
    }

    private static void insertThread(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO work_threads(
                  thread_id,workspace_identity,goal,status,created_at,updated_at)
                VALUES(?,?,'goal','OPEN',?,?)
                """)) {
            statement.setString(1, id);
            statement.setString(2, "workspace-" + id);
            statement.setString(3, AT);
            statement.setString(4, AT);
            statement.executeUpdate();
        }
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

    private static String stringScalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }
}
