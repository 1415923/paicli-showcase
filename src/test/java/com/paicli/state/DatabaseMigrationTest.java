package com.paicli.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseMigrationTest {

    @TempDir
    Path tempDir;

    @Test
    void databaseFileCreatedAndMigrationsAreIdempotent() throws Exception {
        Path stateDir = tempDir.resolve(".paicli").resolve("state");

        try (DatabaseManager databaseManager = new DatabaseManager(stateDir)) {
            Connection connection = databaseManager.getConnection();
            var runner = new MigrationRunner(connection);

            runner.runMigrations();
            assertTrue(Files.exists(stateDir.resolve("paicli.db")));
            assertExpectedTablesExist(connection);
            assertExpectedCorrelationColumnsExist(connection);
            assertExpectedCorrelationIndexesExist(connection);
            assertExpectedSnapshotIndexesExist(connection);
            assertExpectedCodingRuntimeIndexesExist(connection);
            assertExpectedSkillHookIndexesExist(connection);
            assertExpectedCodeIndexTablesExist(connection);
            assertExpectedCodeIndexIndexesExist(connection);
            assertExpectedCodeSearchAuditTableExists(connection);
            assertExpectedCodeSearchAuditIndexesExist(connection);
            assertExpectedSubagentTablesExist(connection);
            assertExpectedSubagentIndexesExist(connection);
            assertExpectedWorkerCoordinatorTablesExist(connection);
            assertExpectedWorkerCoordinatorIndexesExist(connection);
            assertExpectedVerificationExcerptColumns(connection);
            assertExpectedRuntimeManifestColumns(connection);
            assertTrue(columnExists(connection, "plan_revisions",
                    "canonical_schema_version"));
            assertTrue(objectExists(connection, "trigger",
                    "r1c_step_completion_profile_version"));
            assertTrue(objectExists(connection,"table","task_user_results"));
            assertTrue(columnExists(connection,"task_user_results","evidence_hash"));
            assertTrue(columnExists(connection,"task_user_results","render_status"));
            assertTrue(objectExists(connection,"index","idx_task_user_results_revision"));
            assertTrue(objectExists(connection,"trigger","task_user_result_content_immutable"));
            assertTrue(objectExists(connection,"trigger","task_user_result_no_delete"));
            assertExpectedMcpTablesExist(connection);
            assertExpectedCoreDemoTableExists(connection);
            assertTrue(columnExists(connection,"verification_runs","candidate_digest_kind"));
            assertTrue(columnExists(connection,"verification_runs","candidate_digest"));
            assertTrue(columnExists(connection,"side_effect_occurrences",
                    "final_candidate_digest"));
            assertTrue(objectExists(connection,"trigger","verification_candidate_identity_insert"));
            assertTrue(objectExists(connection,"trigger","verification_candidate_identity_update_valid"));
            assertTrue(objectExists(connection,"trigger","verification_candidate_identity_immutable"));
            assertTrue(objectExists(connection,"trigger","h5_verification_evidence_candidate_bound"));
            assertEquals(33, countRows(connection, "schema_migrations"));

            runner.runMigrations();
            assertEquals(33, countRows(connection, "schema_migrations"));
        }
    }

    @Test
    void migrationV30PreservesHistoricalPassAndEnforcesNewCandidateIdentity() throws Exception {
        Path stateDir=tempDir.resolve("v30").resolve(".paicli").resolve("state");
        try(DatabaseManager manager=new DatabaseManager(stateDir)){
            Connection connection=manager.getConnection();
            MigrationRunner runner=new MigrationRunner(connection);
            runner.runMigrationsThrough(29);
            try(Statement statement=connection.createStatement()){
                statement.execute("INSERT INTO tasks(task_id,goal,status) VALUES('task-v30','goal','RUNNING')");
                statement.execute("""
                        INSERT INTO coding_runs(run_id,task_id,run_kind,phase,max_repair_attempts)
                        VALUES('run-v30','task-v30','INITIAL','VERIFYING',1)
                        """);
                statement.execute("""
                        INSERT INTO verification_runs(verification_id,run_id,task_id,repair_attempt,
                            command,status,exit_code,duration_ms,completed_at)
                        VALUES('historical-pass','run-v30','task-v30',0,'verify','PASSED',0,1,
                            datetime('now'))
                        """);
            }

            runner.runMigrations();
            try(Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery(
                    "SELECT candidate_digest_kind,candidate_digest FROM verification_runs "
                            +"WHERE verification_id='historical-pass'")){
                assertTrue(rows.next());assertNull(rows.getString(1));assertNull(rows.getString(2));
            }
            try(Statement statement=connection.createStatement()){
                assertThrows(SQLException.class,()->statement.execute("""
                        INSERT INTO verification_runs(verification_id,run_id,task_id,repair_attempt,
                            command,status) VALUES('missing','run-v30','task-v30',0,'verify','RUNNING')
                        """));
                assertThrows(SQLException.class,()->statement.execute("""
                        INSERT INTO verification_runs(verification_id,run_id,task_id,repair_attempt,
                            command,status,candidate_digest_kind,candidate_digest)
                        VALUES('malformed','run-v30','task-v30',0,'verify','ERROR',
                            'WORKSPACE_CONTENT_SHA256_V1','ABC')
                        """));
                statement.execute("""
                        INSERT INTO verification_runs(verification_id,run_id,task_id,repair_attempt,
                            command,status,candidate_digest_kind,candidate_digest)
                        VALUES('current','run-v30','task-v30',0,'verify','RUNNING',
                            'WORKSPACE_CONTENT_SHA256_V1','aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa')
                        """);
                statement.execute("UPDATE verification_runs SET status='PASSED',exit_code=0 "
                        +"WHERE verification_id='current'");
                assertThrows(SQLException.class,()->statement.execute("UPDATE verification_runs SET "
                        +"candidate_digest='bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb' "
                        +"WHERE verification_id='current'"));
            }
        }
    }

    @Test
    void migrationV31PreservesLegacyResultAndAllowsOnlyNullablePlanBinding() throws Exception {
        Path stateDir=tempDir.resolve("v31").resolve(".paicli").resolve("state");
        try(DatabaseManager manager=new DatabaseManager(stateDir)){
            Connection connection=manager.getConnection();
            MigrationRunner runner=new MigrationRunner(connection);
            runner.runMigrationsThrough(30);
            String thread="thr_00000000000040008000000000000001";
            String task="tsk_00000000000040008000000000000001";
            String run="run_00000000000040008000000000000001";
            String plan="pln_00000000000040008000000000000001";
            String hash="a".repeat(64);
            try(PreparedStatement statement=connection.prepareStatement("""
                    INSERT INTO work_threads(thread_id,workspace_identity,goal,status,created_at,updated_at)
                    VALUES(?,?,?,'OPEN',datetime('now'),datetime('now'))
                    """)){statement.setString(1,thread);statement.setString(2,"workspace");
                statement.setString(3,"goal");statement.executeUpdate();}
            try(Statement statement=connection.createStatement()){
                statement.execute("INSERT INTO task_identities(task_id,thread_id,goal,status,created_at) VALUES('"
                        +task+"','"+thread+"','goal','RUNNING',datetime('now'))");
                statement.execute("INSERT INTO instruction_manifests(instruction_manifest_id,task_id,schema_version,resolution_policy_version,canonical_hash,state,created_at) VALUES('manifest-v31-0001','"
                        +task+"',1,'test','"+hash+"','COMMITTED',datetime('now'))");
                statement.execute("UPDATE task_identities SET admitted_manifest_id='manifest-v31-0001',admitted_manifest_hash='"
                        +hash+"' WHERE task_id='"+task+"'");
                statement.execute("INSERT INTO durable_runs(run_id,task_id,thread_id,run_kind,instruction_manifest_id,instruction_manifest_hash,created_at) VALUES('"
                        +run+"','"+task+"','"+thread+"','INITIAL','manifest-v31-0001','"+hash+"',datetime('now'))");
                statement.execute("INSERT INTO tasks(task_id,goal,status) VALUES('"+task+"','goal','RUNNING')");
                statement.execute("INSERT INTO coding_runs(run_id,task_id,run_kind,phase,max_repair_attempts) VALUES('"
                        +run+"','"+task+"','INITIAL','VERIFYING',1)");
                statement.execute("INSERT INTO plan_revisions(plan_revision_id,task_id,revision_number,goal,acceptance_criteria,assumptions,risks,verification_strategy,canonical_hash,source_type,provenance,created_by_run_id,created_at) VALUES('"
                        +plan+"','"+task+"',1,'goal','[]','[]','[]','verify','"+hash
                        +"','TEST','TEST','"+run+"',datetime('now'))");
                statement.execute("INSERT INTO task_user_results(task_id,run_id,plan_revision_id,response_mode,content,content_hash,evidence_hash,validation_status,render_status,created_at) VALUES('"
                        +task+"','"+run+"','"+plan+"','REQUIRED','legacy','"+hash+"','"
                        +"b".repeat(64)+"','VALID','RENDERED',datetime('now'))");
            }

            runner.runMigrations();

            try(Statement statement=connection.createStatement();ResultSet row=statement.executeQuery(
                    "SELECT plan_revision_id,content,content_hash,evidence_hash FROM task_user_results")){
                assertTrue(row.next());assertEquals(plan,row.getString(1));
                assertEquals("legacy",row.getString(2));assertEquals(hash,row.getString(3));
                assertEquals("b".repeat(64),row.getString(4));assertFalse(row.next());
            }
            assertTrue(objectExists(connection,"index","idx_task_user_results_revision"));
            assertTrue(objectExists(connection,"trigger","task_user_result_content_immutable"));
            assertTrue(objectExists(connection,"trigger","task_user_result_no_delete"));
            try(Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery(
                    "PRAGMA foreign_key_check")){assertFalse(rows.next());}
            try(Statement statement=connection.createStatement()){
                statement.execute("INSERT INTO tasks(task_id,goal,status) VALUES('task-planless','goal','RUNNING')");
                statement.execute("INSERT INTO coding_runs(run_id,task_id,run_kind,phase,max_repair_attempts) VALUES('run-planless','task-planless','INITIAL','VERIFYING',1)");
                statement.execute("INSERT INTO task_user_results(task_id,run_id,plan_revision_id,response_mode,content,content_hash,evidence_hash,validation_status,render_status,created_at) VALUES('task-planless','run-planless',NULL,'REQUIRED','ok','"
                        +hash+"','"+hash+"','VALID','PENDING',datetime('now'))");
                assertThrows(SQLException.class,()->statement.execute(
                        "INSERT INTO task_user_results(task_id,run_id,plan_revision_id,response_mode,content,content_hash,evidence_hash,validation_status,render_status,created_at) VALUES('missing-task','missing-run','pln_00000000000040008000000000000002','REQUIRED','bad','"
                                +hash+"','"+hash+"','VALID','PENDING',datetime('now'))"));
            }
        }
    }

    @Test
    void migrationV31RollsBackWholeRebuildWhenLateStepFails() throws Exception {
        Path stateDir=tempDir.resolve("v31-rollback").resolve(".paicli").resolve("state");
        try(DatabaseManager manager=new DatabaseManager(stateDir)){
            Connection connection=manager.getConnection();
            MigrationRunner runner=new MigrationRunner(connection);
            runner.runMigrationsThrough(30);
            try(Statement statement=connection.createStatement()){
                statement.execute("DROP INDEX idx_task_user_results_revision");
                statement.execute("CREATE TABLE migration_collision(value TEXT)");
                statement.execute("CREATE INDEX idx_task_user_results_revision ON migration_collision(value)");
            }
            assertThrows(SQLException.class,runner::runMigrations);
            assertFalse(columnExists(connection,"side_effect_occurrences","final_candidate_digest"));
            assertFalse(tableExists(connection,"task_user_results_v30"));
            assertTrue(columnNotNull(connection,"task_user_results","plan_revision_id"));
            assertEquals(30,countRows(connection,"schema_migrations"));
            assertTrue(objectExists(connection,"trigger","task_user_result_content_immutable"));
            assertTrue(objectExists(connection,"trigger","task_user_result_no_delete"));
        }
    }

    @Test
    void migrationV18CanApplyAfterExistingV17SchemaRecord() throws Exception {
        Path stateDir = tempDir.resolve("v17").resolve(".paicli").resolve("state");
        try (DatabaseManager databaseManager = new DatabaseManager(stateDir)) {
            Connection connection = databaseManager.getConnection();
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY, applied_at TEXT DEFAULT (datetime('now')), description TEXT)");
                for (int version = 1; version <= 17; version++)
                    statement.executeUpdate("INSERT INTO schema_migrations(version,description) VALUES("+version+",'existing')");
                statement.execute("CREATE TABLE tasks(task_id TEXT PRIMARY KEY)");
                statement.execute("CREATE TABLE coding_runs(run_id TEXT PRIMARY KEY)");
                statement.execute("CREATE TABLE file_snapshots(snapshot_id TEXT PRIMARY KEY)");
            }
            new MigrationRunner(connection).runMigrationsThrough(18);
            assertTrue(tableExists(connection, "interactive_task_facts"));
            assertTrue(tableExists(connection, "interactive_edit_proposals"));
            assertEquals(18, countRows(connection, "schema_migrations"));
            new MigrationRunner(connection).runMigrationsThrough(18);
            assertEquals(18, countRows(connection, "schema_migrations"));
        }
    }
    @Test
    void migrationV17CanApplyAfterExistingV16SchemaRecord() throws Exception {
        Path stateDir = tempDir.resolve("v16").resolve(".paicli").resolve("state");
        try (DatabaseManager databaseManager = new DatabaseManager(stateDir)) {
            Connection connection = databaseManager.getConnection();
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY, applied_at TEXT DEFAULT (datetime('now')), description TEXT)");
                for (int version = 1; version <= 16; version++)
                    statement.executeUpdate("INSERT INTO schema_migrations(version,description) VALUES("+version+",'existing')");
                statement.execute("CREATE TABLE tasks(task_id TEXT PRIMARY KEY)");
            }
            new MigrationRunner(connection).runMigrationsThrough(17);
            assertExpectedCoreDemoTableExists(connection);
            assertEquals(17, countRows(connection, "schema_migrations"));
        }
    }

    @Test
    void migrationV16CanApplyAfterExistingV15SchemaRecord() throws Exception {
        Path stateDir = tempDir.resolve("v15").resolve(".paicli").resolve("state");
        try (DatabaseManager databaseManager = new DatabaseManager(stateDir)) {
            Connection connection = databaseManager.getConnection();
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE schema_migrations(version INTEGER PRIMARY KEY, applied_at TEXT DEFAULT (datetime('now')), description TEXT)");
                for (int version = 1; version <= 15; version++) statement.executeUpdate("INSERT INTO schema_migrations(version,description) VALUES("+version+",'existing')");
                statement.execute("CREATE TABLE tasks(task_id TEXT PRIMARY KEY)");
                statement.execute("CREATE TABLE coding_runs(run_id TEXT PRIMARY KEY)");
            }
            new MigrationRunner(connection).runMigrationsThrough(16);
            assertExpectedMcpTablesExist(connection);
            assertEquals(16, countRows(connection, "schema_migrations"));
        }
    }

    @Test
    void migrationV15CanApplyAfterExistingV14SchemaRecord() throws Exception {
        Path stateDir = tempDir.resolve("v14").resolve(".paicli").resolve("state");
        try (DatabaseManager databaseManager = new DatabaseManager(stateDir)) {
            Connection connection = databaseManager.getConnection();
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE schema_migrations (
                            version INTEGER PRIMARY KEY,
                            applied_at TEXT NOT NULL DEFAULT (datetime('now')),
                            description TEXT
                        )
                        """);
                for (int version = 1; version <= 14; version++) {
                    statement.executeUpdate("""
                            INSERT INTO schema_migrations(version, description)
                            VALUES (%d, 'pre-existing')
                            """.formatted(version));
                }
                statement.execute("CREATE TABLE tasks(task_id TEXT PRIMARY KEY)");
            }

            new MigrationRunner(connection).runMigrationsThrough(15);

            assertExpectedWorkerCoordinatorTablesExist(connection);
            assertExpectedWorkerCoordinatorIndexesExist(connection);
            assertEquals(15, countRows(connection, "schema_migrations"));
        }
    }

    @Test
    void migrationV14CanApplyAfterExistingV13SchemaRecord() throws Exception {
        Path stateDir = tempDir.resolve("v13").resolve(".paicli").resolve("state");

        try (DatabaseManager databaseManager = new DatabaseManager(stateDir)) {
            Connection connection = databaseManager.getConnection();
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS schema_migrations (
                            version INTEGER PRIMARY KEY,
                            applied_at TEXT NOT NULL DEFAULT (datetime('now')),
                            description TEXT
                        )
                        """);
                for (int version = 1; version <= 13; version++) {
                    statement.executeUpdate("""
                            INSERT INTO schema_migrations(version, description)
                            VALUES (%d, 'pre-existing')
                            """.formatted(version));
                }
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS tasks (
                            task_id TEXT PRIMARY KEY,
                            goal TEXT NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS coding_runs (
                            run_id TEXT PRIMARY KEY,
                            task_id TEXT NOT NULL
                        )
                        """);
            }

            new MigrationRunner(connection).runMigrationsThrough(14);

            assertTrue(tableExists(connection, "subagent_runs"));
            assertTrue(tableExists(connection, "structured_verdicts"));
            assertTrue(tableExists(connection, "lead_decisions"));
            assertTrue(indexExists(connection, "idx_subagent_runs_occurrence"));
            assertEquals(14, countRows(connection, "schema_migrations"));
        }
    }

    @Test
    void migrationV13CanApplyAfterExistingV12SchemaRecord() throws Exception {
        Path stateDir = tempDir.resolve("v12").resolve(".paicli").resolve("state");

        try (DatabaseManager databaseManager = new DatabaseManager(stateDir)) {
            Connection connection = databaseManager.getConnection();
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS schema_migrations (
                            version INTEGER PRIMARY KEY,
                            applied_at TEXT NOT NULL DEFAULT (datetime('now')),
                            description TEXT
                        )
                        """);
                for (int version = 1; version <= 12; version++) {
                    statement.executeUpdate("""
                            INSERT INTO schema_migrations(version, description)
                            VALUES (%d, 'pre-existing')
                            """.formatted(version));
                }
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS code_files (
                            file_id TEXT PRIMARY KEY,
                            relative_path TEXT NOT NULL UNIQUE,
                            language TEXT NOT NULL DEFAULT 'java',
                            package_name TEXT,
                            imports_json TEXT,
                            sha256 TEXT,
                            mtime_millis INTEGER NOT NULL,
                            size_bytes INTEGER NOT NULL,
                            status TEXT NOT NULL,
                            error_excerpt TEXT,
                            unsupported_note TEXT,
                            indexed_at TEXT NOT NULL DEFAULT (datetime('now'))
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS class_index (
                            class_id TEXT PRIMARY KEY,
                            file_id TEXT NOT NULL,
                            package_name TEXT,
                            class_name TEXT NOT NULL,
                            qualified_name TEXT NOT NULL,
                            kind TEXT NOT NULL,
                            start_line INTEGER,
                            end_line INTEGER,
                            modifiers TEXT,
                            extends_name TEXT,
                            implements_names TEXT
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS method_index (
                            method_id TEXT PRIMARY KEY,
                            file_id TEXT NOT NULL,
                            class_id TEXT NOT NULL,
                            method_name TEXT NOT NULL,
                            kind TEXT NOT NULL,
                            signature TEXT NOT NULL,
                            return_type TEXT,
                            parameter_types TEXT,
                            start_line INTEGER,
                            end_line INTEGER,
                            chunk_text TEXT NOT NULL,
                            chunk_sha256 TEXT NOT NULL,
                            modifiers TEXT
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS field_index (
                            field_id TEXT PRIMARY KEY,
                            file_id TEXT NOT NULL,
                            class_id TEXT NOT NULL,
                            field_name TEXT NOT NULL,
                            field_type TEXT,
                            start_line INTEGER,
                            end_line INTEGER,
                            modifiers TEXT
                        )
                        """);
            }

            new MigrationRunner(connection).runMigrationsThrough(13);

            assertTrue(tableExists(connection, "code_search_audits"));
            assertTrue(indexExists(connection, "idx_class_index_package_name"));
            assertTrue(indexExists(connection, "idx_method_index_class_name"));
            assertTrue(indexExists(connection, "idx_field_index_class_name"));
            assertEquals(13, countRows(connection, "schema_migrations"));
        }
    }

    @Test
    void migrationV12AndV13CanApplyAfterExistingV11SchemaRecord() throws Exception {
        Path stateDir = tempDir.resolve("v11").resolve(".paicli").resolve("state");

        try (DatabaseManager databaseManager = new DatabaseManager(stateDir)) {
            Connection connection = databaseManager.getConnection();
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS schema_migrations (
                            version INTEGER PRIMARY KEY,
                            applied_at TEXT NOT NULL DEFAULT (datetime('now')),
                            description TEXT
                        )
                        """);
                for (int version = 1; version <= 11; version++) {
                    statement.executeUpdate("""
                            INSERT INTO schema_migrations(version, description)
                            VALUES (%d, 'pre-existing')
                            """.formatted(version));
                }
            }

            new MigrationRunner(connection).runMigrationsThrough(13);

            assertTrue(tableExists(connection, "code_files"));
            assertTrue(tableExists(connection, "class_index"));
            assertTrue(tableExists(connection, "method_index"));
            assertTrue(tableExists(connection, "field_index"));
            assertTrue(tableExists(connection, "code_search_audits"));
            assertEquals(13, countRows(connection, "schema_migrations"));
        }
    }

    @Test
    void foreignKeysAreEnabled() throws Exception {
        try (DatabaseManager databaseManager = new DatabaseManager(tempDir.resolve(".paicli").resolve("state"))) {
            try (Statement statement = databaseManager.getConnection().createStatement();
                 ResultSet resultSet = statement.executeQuery("PRAGMA foreign_keys")) {
                assertTrue(resultSet.next());
                assertEquals(1, resultSet.getInt(1));
            }
        }
    }

    @Test
    void checkpointsKeepOneRowPerTask() throws Exception {
        try (DatabaseManager databaseManager = new DatabaseManager(tempDir.resolve(".paicli").resolve("state"))) {
            Connection connection = databaseManager.getConnection();
            new MigrationRunner(connection).runMigrations();

            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO tasks(task_id, goal) VALUES ('task-001', 'goal')");
                statement.executeUpdate("""
                        INSERT INTO checkpoints(task_id, iteration, status, messages_json)
                        VALUES ('task-001', 1, 'RUNNING', '[]')
                        """);
            }

            try (Statement statement = connection.createStatement()) {
                assertThrows(SQLException.class, () -> statement.executeUpdate("""
                        INSERT INTO checkpoints(task_id, iteration, status, messages_json)
                        VALUES ('task-001', 2, 'RUNNING', '[]')
                        """));
            }
        }
    }


    private void assertExpectedTablesExist(Connection connection) throws SQLException {
        Set<String> expected = Set.of(
                "schema_migrations",
                "tasks",
                "events",
                "tool_calls",
                "approval_decisions",
                "checkpoints",
                "memory_notes",
                "file_snapshots",
                "coding_runs",
                "task_plans",
                "verification_runs",
                "repair_attempts",
                "skill_loads",
                "skill_selection_runs",
                "selected_skills",
                "hook_runs",
                "code_files",
                "class_index",
                "method_index",
                "field_index",
                "code_search_audits",
                "subagent_runs",
                "structured_verdicts",
                "lead_decisions",
                "managed_worktrees",
                "worker_runs",
                "worker_task_items",
                "task_claims",
                "worker_mailbox_messages",
                "worker_candidates",
                "worker_merge_proposals",
                "worker_merge_proposal_candidates",
                "worker_integration_decisions",
                "worker_integration_runs",
                "worker_cleanup_runs"
                ,"mcp_server_manifests", "mcp_sessions", "mcp_tool_catalogs", "mcp_tools", "mcp_tool_calls",
                "core_demo_runs", "core_demo_checkpoints",
                "interactive_task_facts", "interactive_edit_proposals",
                "work_threads", "task_identities", "instruction_manifests",
                "plan_revisions", "plan_step_executions", "plan_step_attempts",
                "plan_evidence", "plan_verification_evidence", "plan_completion_rules",
                "plan_completion_requirements", "plan_completion_evaluations",
                "plan_completion_evaluation_evidence", "plan_completion_evaluation_steps",
                "task_completion_decisions"
        );

        for (String table : expected) {
            assertTrue(tableExists(connection, table), "Missing table: " + table);
        }
    }

    private boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (ResultSet resultSet = connection.getMetaData().getTables(null, null, tableName, new String[]{"TABLE"})) {
            return resultSet.next();
        }
    }

    private void assertExpectedCorrelationColumnsExist(Connection connection) throws SQLException {
        assertTrue(columnExists(connection, "tool_calls", "tool_call_id"));
        assertTrue(columnExists(connection, "approval_decisions", "iteration"));
        assertTrue(columnExists(connection, "approval_decisions", "tool_call_id"));
    }

    private boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (resultSet.next()) {
                if (columnName.equals(resultSet.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private boolean objectExists(Connection connection, String type, String name)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type=? AND name=?")) {
            statement.setString(1, type);
            statement.setString(2, name);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private void assertExpectedCorrelationIndexesExist(Connection connection) throws SQLException {
        assertTrue(indexExists(connection, "idx_tool_calls_tool_call_id"));
        assertTrue(indexExists(connection, "idx_approval_iteration"));
        assertTrue(indexExists(connection, "idx_approval_tool_call_id"));
    }

    private void assertExpectedSnapshotIndexesExist(Connection connection) throws SQLException {
        assertTrue(indexExists(connection, "idx_file_snapshots_task_created"));
        assertTrue(indexExists(connection, "idx_file_snapshots_tool_call_id"));
        assertTrue(indexExists(connection, "idx_file_snapshots_status"));
    }

    private void assertExpectedCodingRuntimeIndexesExist(Connection connection) throws SQLException {
        assertTrue(indexExists(connection, "idx_coding_runs_task_created"));
        assertTrue(indexExists(connection, "idx_coding_runs_one_active_task"));
        assertTrue(indexExists(connection, "idx_task_plans_task"));
        assertTrue(indexExists(connection, "idx_verification_runs_run_created"));
        assertTrue(indexExists(connection, "idx_verification_runs_task"));
        assertTrue(indexExists(connection, "idx_verification_runs_status"));
        assertTrue(indexExists(connection, "idx_repair_attempts_run_attempt"));
        assertTrue(indexExists(connection, "idx_repair_attempts_task"));
    }

    private void assertExpectedSkillHookIndexesExist(Connection connection)
            throws SQLException {
        assertTrue(indexExists(connection, "idx_skill_loads_run_status"));
        assertTrue(indexExists(connection, "idx_skill_loads_task"));
        assertTrue(indexExists(connection, "idx_skill_selection_runs_run_stage"));
        assertTrue(indexExists(connection, "idx_selected_skills_run"));
        assertTrue(indexExists(connection, "idx_hook_runs_run_event"));
        assertTrue(indexExists(connection, "idx_hook_runs_status"));
    }

    private void assertExpectedRuntimeManifestColumns(Connection connection)
            throws SQLException {
        assertTrue(columnExists(
                connection, "coding_runs", "skills_manifest_sha256"));
        assertTrue(columnExists(
                connection, "coding_runs", "hooks_manifest_sha256"));
    }

    private void assertExpectedCodeIndexTablesExist(Connection connection)
            throws SQLException {
        assertTrue(columnExists(connection, "code_files", "package_name"));
        assertTrue(columnExists(connection, "code_files", "imports_json"));
        assertTrue(columnExists(connection, "code_files", "status"));
        assertTrue(columnExists(connection, "code_files", "error_excerpt"));
        assertTrue(columnExists(connection, "method_index", "chunk_text"));
        assertTrue(columnExists(connection, "method_index", "chunk_sha256"));
        assertTrue(columnExists(connection, "method_index", "kind"));
    }

    private void assertExpectedCodeIndexIndexesExist(Connection connection)
            throws SQLException {
        assertTrue(indexExists(connection, "idx_code_files_status"));
        assertTrue(indexExists(connection, "idx_code_files_path"));
        assertTrue(indexExists(connection, "idx_code_files_sha"));
        assertTrue(indexExists(connection, "idx_class_index_file"));
        assertTrue(indexExists(connection, "idx_class_index_qualified_name"));
        assertTrue(indexExists(connection, "idx_class_index_name"));
        assertTrue(indexExists(connection, "idx_method_index_file"));
        assertTrue(indexExists(connection, "idx_method_index_class"));
        assertTrue(indexExists(connection, "idx_method_index_name"));
        assertTrue(indexExists(connection, "idx_method_index_signature"));
        assertTrue(indexExists(connection, "idx_field_index_file"));
        assertTrue(indexExists(connection, "idx_field_index_class"));
        assertTrue(indexExists(connection, "idx_field_index_name"));
        assertTrue(indexExists(connection, "idx_class_index_package_name"));
        assertTrue(indexExists(connection, "idx_method_index_class_name"));
        assertTrue(indexExists(connection, "idx_field_index_class_name"));
    }

    private void assertExpectedCodeSearchAuditTableExists(Connection connection)
            throws SQLException {
        assertTrue(columnExists(connection, "code_search_audits", "audit_id"));
        assertTrue(columnExists(connection, "code_search_audits", "created_at"));
        assertTrue(columnExists(connection, "code_search_audits", "completed_at"));
        assertTrue(columnExists(connection, "code_search_audits", "query_type"));
        assertTrue(columnExists(connection, "code_search_audits", "query_input"));
        assertTrue(columnExists(connection, "code_search_audits", "query_input_hash"));
        assertTrue(columnExists(connection, "code_search_audits", "status"));
        assertTrue(columnExists(connection, "code_search_audits", "result_count"));
        assertTrue(columnExists(connection, "code_search_audits", "ambiguity"));
        assertTrue(columnExists(connection, "code_search_audits", "confidence"));
        assertTrue(columnExists(connection, "code_search_audits", "selected_context_hash"));
        assertTrue(columnExists(connection, "code_search_audits", "duration_millis"));
        assertTrue(columnExists(connection, "code_search_audits", "error_excerpt"));
        assertTrue(columnExists(connection, "code_search_audits", "task_id"));
        assertTrue(columnExists(connection, "code_search_audits", "run_id"));
    }

    private void assertExpectedCodeSearchAuditIndexesExist(Connection connection)
            throws SQLException {
        assertTrue(indexExists(connection, "idx_code_search_audits_created"));
        assertTrue(indexExists(connection, "idx_code_search_audits_type_created"));
        assertTrue(indexExists(connection, "idx_code_search_audits_task_run"));
    }


    private void assertExpectedSubagentTablesExist(Connection connection)
            throws SQLException {
        assertTrue(columnExists(connection, "subagent_runs", "subagent_run_id"));
        assertTrue(columnExists(connection, "subagent_runs", "task_id"));
        assertTrue(columnExists(connection, "subagent_runs", "coding_run_id"));
        assertTrue(columnExists(connection, "subagent_runs", "occurrence_key"));
        assertTrue(columnExists(connection, "subagent_runs", "read_only"));
        assertTrue(columnExists(connection, "subagent_runs", "context_hash"));
        assertTrue(columnExists(connection, "subagent_runs", "verification_status"));
        assertTrue(columnExists(connection, "structured_verdicts", "verdict_id"));
        assertTrue(columnExists(connection, "structured_verdicts", "evidence_json"));
        assertTrue(columnExists(connection, "structured_verdicts", "evidence_count"));
        assertTrue(columnExists(connection, "lead_decisions", "decision_id"));
        assertTrue(columnExists(connection, "lead_decisions", "decision"));
        assertTrue(columnExists(connection, "lead_decisions", "decided_by"));
    }

    private void assertExpectedSubagentIndexesExist(Connection connection)
            throws SQLException {
        assertTrue(indexExists(connection, "idx_subagent_runs_task"));
        assertTrue(indexExists(connection, "idx_subagent_runs_coding_run"));
        assertTrue(indexExists(connection, "idx_subagent_runs_occurrence"));
        assertTrue(indexExists(connection, "idx_subagent_runs_status"));
        assertTrue(indexExists(connection, "idx_structured_verdicts_run"));
        assertTrue(indexExists(connection, "idx_structured_verdicts_verdict"));
        assertTrue(indexExists(connection, "idx_lead_decisions_run"));
        assertTrue(indexExists(connection, "idx_lead_decisions_verdict"));
        assertTrue(indexExists(connection, "idx_lead_decisions_decision"));
        assertTrue(indexExists(connection, "ux_lead_decisions_verdict"));
    }

    private void assertExpectedCoreDemoTableExists(Connection connection) throws SQLException {
        assertTrue(tableExists(connection, "core_demo_runs"));
        assertTrue(indexExists(connection, "idx_core_demo_runs_status"));
        assertTrue(indexExists(connection, "idx_core_demo_runs_scenario_created"));
        assertTrue(tableExists(connection, "core_demo_checkpoints"));
        assertTrue(indexExists(connection, "idx_core_demo_checkpoints_task"));
        assertTrue(hasForeignKey(connection, "core_demo_checkpoints", "core_demo_runs"));
        assertTrue(hasForeignKey(connection, "core_demo_checkpoints", "tasks"));
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='core_demo_runs'");
             ResultSet rows = statement.executeQuery()) {
            assertTrue(rows.next());
            String sql = rows.getString(1);
            assertTrue(sql.contains("READY_FOR_WORKFLOW"));
            assertTrue(sql.contains("ARTIFACTS_WRITING"));
            assertTrue(sql.contains("RETAINED_FOR_DIAGNOSTICS"));
            assertTrue(sql.contains("resume_status"));
            assertTrue(sql.contains("rewind_status"));
            assertTrue(sql.contains("PARTIAL"));
        }
    }

    private void assertExpectedMcpTablesExist(Connection connection) throws SQLException {
        for (String table : Set.of("mcp_server_manifests","mcp_sessions","mcp_tool_catalogs","mcp_tools","mcp_tool_calls")) assertTrue(tableExists(connection, table));
        for (String index : Set.of("idx_mcp_manifests_server_created","idx_mcp_sessions_occurrence","idx_mcp_sessions_status","idx_mcp_catalogs_status","idx_mcp_catalogs_server_hash","idx_mcp_tools_catalog_exposure","idx_mcp_tool_calls_status","idx_mcp_tool_calls_session_request")) assertTrue(indexExists(connection,index));
        assertTrue(hasForeignKey(connection,"mcp_sessions","mcp_server_manifests"));
        assertTrue(hasForeignKey(connection,"mcp_tools","mcp_tool_catalogs"));
        assertTrue(hasForeignKey(connection,"mcp_tool_calls","mcp_sessions"));
        try (PreparedStatement statement = connection.prepareStatement("SELECT sql FROM sqlite_master WHERE type='table' AND name='mcp_tool_calls'"); ResultSet rows = statement.executeQuery()) { assertTrue(rows.next()); assertTrue(rows.getString(1).contains("UNKNOWN_OUTCOME")); }
    }

    private void assertExpectedWorkerCoordinatorTablesExist(Connection connection)
            throws SQLException {
        for (String table : Set.of(
                "managed_worktrees", "worker_runs", "worker_task_items",
                "task_claims", "worker_mailbox_messages", "worker_candidates",
                "worker_merge_proposals", "worker_merge_proposal_candidates",
                "worker_integration_decisions", "worker_integration_runs",
                "worker_cleanup_runs")) {
            assertTrue(tableExists(connection, table), "Missing V15 table: " + table);
        }
        assertTrue(columnExists(connection, "managed_worktrees", "backend_capability"));
        assertTrue(columnExists(connection, "worker_candidates", "candidate_commit_sha"));
        assertTrue(columnExists(connection, "worker_candidates", "verification_config_hash"));
        assertTrue(columnExists(connection, "managed_worktrees", "freeze_token"));
        assertTrue(columnExists(connection, "worker_cleanup_runs", "trace_cleanup_status"));
        assertTrue(columnExists(connection, "worker_cleanup_runs", "target_type"));
        assertTrue(columnExists(connection, "worker_integration_decisions", "proposal_id"));
        assertTrue(columnExists(connection, "worker_integration_runs", "actual_head_after"));
        assertTrue(hasForeignKey(connection, "worker_runs", "managed_worktrees"));
        assertTrue(hasForeignKey(connection, "task_claims", "worker_task_items"));
        assertTrue(hasForeignKey(connection, "worker_candidates", "task_claims"));
    }

    private void assertExpectedWorkerCoordinatorIndexesExist(Connection connection)
            throws SQLException {
        for (String index : Set.of(
                "ux_managed_worktrees_git_branch",
                "idx_managed_worktrees_status",
                "idx_worker_runs_occurrence",
                "idx_worker_runs_status",
                "idx_worker_task_items_parent_status",
                "ux_task_claims_one_active_item",
                "idx_task_claims_worker",
                "idx_worker_mailbox_recipient",
                "idx_worker_mailbox_run",
                "idx_worker_mailbox_task_item",
                "idx_worker_candidates_status",
                "idx_worker_candidates_claim",
                "idx_worker_merge_proposals_status",
                "idx_worker_integration_decisions_decision",
                "idx_worker_integration_runs_status",
                "idx_worker_cleanup_runs_worktree",
                "idx_worker_cleanup_runs_status",
                "idx_worker_cleanup_runs_proposal")) {
            assertTrue(indexExists(connection, index), "Missing V15 index: " + index);
        }
    }

    private boolean hasForeignKey(Connection connection, String table, String target)
            throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA foreign_key_list(" + table + ")")) {
            while (resultSet.next()) {
                if (target.equals(resultSet.getString("table"))) return true;
            }
            return false;
        }
    }

    private void assertExpectedVerificationExcerptColumns(Connection connection) throws SQLException {
        assertTrue(columnExists(connection, "verification_runs", "output_excerpt"));
        assertTrue(columnExists(connection, "verification_runs", "error_excerpt"));
        assertFalse(columnExists(connection, "verification_runs", "output"));
        assertFalse(columnExists(connection, "verification_runs", "error"));
    }

    private boolean indexExists(Connection connection, String indexName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ?")) {
            statement.setString(1, indexName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private boolean columnNotNull(Connection connection,String table,String column)
            throws SQLException{
        try(Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery(
                "PRAGMA table_info("+table+")")){
            while(rows.next())if(column.equals(rows.getString("name")))
                return rows.getInt("notnull")==1;
            throw new SQLException("Missing column "+table+"."+column);
        }
    }

    private int countRows(Connection connection, String tableName) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }
}
