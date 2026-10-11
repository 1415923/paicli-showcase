package com.paicli.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class R1eMigrationTest {
    @TempDir Path temp;
    @Test void freshV24AndV23UpgradeExposeSameAdditiveAuthority()throws Exception{
        try(DatabaseManager fresh=new DatabaseManager(temp.resolve("fresh"));DatabaseManager upgraded=new DatabaseManager(temp.resolve("upgrade"))){
            new MigrationRunner(fresh.getConnection()).runMigrations();MigrationRunner runner=new MigrationRunner(upgraded.getConnection());
            runner.runMigrationsThrough(23);runner.runMigrations();runner.runMigrations();
            for(String table:Set.of("continuation_capsules","continuation_capsule_facts","followup_candidate_definitions",
                    "followup_candidate_authority","child_manifest_candidates","manifest_diff_facts","task_continuation_edges",
                    "capsule_child_bindings","followup_admission_facts","child_initial_run_launches")){
                assertTrue(object(fresh.getConnection(),"table",table));assertTrue(object(upgraded.getConnection(),"table",table));}
            assertEquals(33,scalar(upgraded.getConnection(),"SELECT max(version) FROM schema_migrations"));
        }
    }
    @Test void failedV24RollsBackAllCapsuleObjects()throws Exception{
        try(DatabaseManager manager=new DatabaseManager(temp.resolve("rollback"))){Connection c=manager.getConnection();
            MigrationRunner runner=new MigrationRunner(c);runner.runMigrationsThrough(23);
            try(Statement s=c.createStatement()){s.execute("CREATE TABLE capsule_child_bindings(blocker TEXT)");}
            assertThrows(SQLException.class,runner::runMigrations);assertFalse(object(c,"table","continuation_capsules"));
            assertFalse(object(c,"table","followup_candidate_definitions"));
            assertFalse(object(c,"table","child_initial_run_launches"));
            assertEquals(23,scalar(c,"SELECT max(version) FROM schema_migrations"));}
    }
    @Test void v24FreezesCapIdentityAndImmutableFactSurfaces()throws Exception{
        try(DatabaseManager manager=new DatabaseManager(temp.resolve("shape"))){new MigrationRunner(manager.getConnection()).runMigrations();
            String ddl=sql(manager.getConnection(),"table","continuation_capsules");
            assertTrue(ddl.contains("substr(capsule_id,1,4)='cap_'"));assertTrue(ddl.contains("substr(capsule_id,17,1)='4'"));
            assertTrue(object(manager.getConnection(),"trigger","r1e_capsule_immutable"));
            assertTrue(object(manager.getConnection(),"index","idx_r1e_plan_revision_hash_scope"));
            assertTrue(object(manager.getConnection(),"trigger","r1e_candidate_transition"));
            assertTrue(object(manager.getConnection(),"trigger","r1e_candidate_initial_shape"));
            assertTrue(object(manager.getConnection(),"trigger","r1e_candidate_transition_version"));
            assertTrue(object(manager.getConnection(),"trigger","r1e_candidate_version_requires_transition"));
            assertTrue(object(manager.getConnection(),"trigger","r1e_prepared_binding_publish_once"));
            assertTrue(object(manager.getConnection(),"trigger","r1e_confirmation_scope"));
            assertTrue(object(manager.getConnection(),"trigger","r1e_unknown_intent_requires_confirmation"));
            assertTrue(object(manager.getConnection(),"trigger","r1e_ready_requires_risk_authority"));
            assertTrue(sql(manager.getConnection(),"trigger","r1e_ready_requires_risk_authority")
                    .contains("NEW.risk IS NULL"));
            assertTrue(object(manager.getConnection(),"trigger","r1e_prepared_capsule_publish_state"));
            assertTrue(object(manager.getConnection(),"trigger","r1e_prepared_manifest_publish_state"));
            assertTrue(object(manager.getConnection(),"trigger","r1e_candidate_terminal_requires_fact"));
            assertTrue(object(manager.getConnection(),"trigger","r1e_head_conflict_fact_shape"));
            assertTrue(object(manager.getConnection(),"trigger","r1e_head_conflict_fact_apply"));
            assertTrue(object(manager.getConnection(),"trigger","r1e_terminal_fact_apply"));
            assertTrue(object(manager.getConnection(),"trigger","r1e_child_launch_transition"));
            String terminal=sql(manager.getConnection(),"table","followup_admission_facts");
            assertTrue(terminal.contains("observed_head_task_id"));
            assertTrue(terminal.contains("source_state"));
            assertTrue(terminal.contains("source_candidate_version"));
            assertTrue(terminal.contains("resulting_candidate_version"));
            String confirmation=sql(manager.getConnection(),"table","followup_confirmation_facts");
            assertTrue(confirmation.contains("prepared_manifest_hash"));
            assertTrue(confirmation.contains("UNIQUE(capsule_id,confirmation_kind)"));
            assertTrue(sql(manager.getConnection(),"table","manifest_diff_facts")
                    .contains("UNIQUE(capsule_id,source_path)"));
            assertTrue(object(manager.getConnection(),"trigger","r1e_parent_row_immutable"));}
    }
    private static boolean object(Connection c,String type,String name)throws SQLException{return sql(c,type,name)!=null;}
    private static String sql(Connection c,String type,String name)throws SQLException{try(PreparedStatement s=c.prepareStatement(
            "SELECT sql FROM sqlite_master WHERE type=? AND name=?")){s.setString(1,type);s.setString(2,name);try(ResultSet r=s.executeQuery()){return r.next()?r.getString(1):null;}}}
    private static int scalar(Connection c,String sql)throws SQLException{try(Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){assertTrue(r.next());return r.getInt(1);}}
}
