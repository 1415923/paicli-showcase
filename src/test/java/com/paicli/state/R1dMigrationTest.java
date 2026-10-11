package com.paicli.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.*;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class R1dMigrationTest {
    @TempDir Path tempDir;

    @Test void freshV23AndV22UpgradeProduceTheSameAdditiveObjects() throws Exception {
        try(DatabaseManager fresh=new DatabaseManager(tempDir.resolve("fresh"));
            DatabaseManager upgraded=new DatabaseManager(tempDir.resolve("upgrade"))){
            new MigrationRunner(fresh.getConnection()).runMigrations();
            MigrationRunner runner=new MigrationRunner(upgraded.getConnection());
            runner.runMigrationsThrough(22);runner.runMigrations();runner.runMigrations();
            for(String table:Set.of("steering_definitions","steering_classification_facts",
                    "steering_confirmation_facts","steering_queue_authority","steering_state_facts",
                    "steering_application_facts","steering_constraint_provenance",
                    "steering_producer_pause_facts")){
                assertTrue(object(fresh.getConnection(),"table",table));
                assertTrue(object(upgraded.getConnection(),"table",table));
            }
            assertTrue(column(fresh.getConnection(),"approval_decisions","intent_hash"));
            assertTrue(column(upgraded.getConnection(),"approval_decisions","intent_hash"));
            assertEquals(33,scalar(upgraded.getConnection(),"SELECT max(version) FROM schema_migrations"));
        }
    }

    @Test void failedV23RollsBackEverySteeringObject() throws Exception {
        try(DatabaseManager manager=new DatabaseManager(tempDir.resolve("rollback"))){
            Connection connection=manager.getConnection();MigrationRunner runner=new MigrationRunner(connection);
            runner.runMigrationsThrough(22);
            try(Statement s=connection.createStatement()){s.execute("CREATE TABLE steering_state_facts(blocker TEXT)");}
            assertThrows(SQLException.class,runner::runMigrations);
            assertFalse(object(connection,"table","steering_definitions"));
            assertFalse(object(connection,"table","steering_queue_authority"));
            assertFalse(object(connection,"table","steering_producer_pause_facts"));
            assertFalse(column(connection,"approval_decisions","intent_hash"));
            assertEquals(22,scalar(connection,"SELECT max(version) FROM schema_migrations"));
        }
    }

    @Test void V23HasTypedSteeringIdAndImmutableFactTriggers() throws Exception {
        try(DatabaseManager manager=new DatabaseManager(tempDir.resolve("shape"))){
            new MigrationRunner(manager.getConnection()).runMigrations();
            String ddl=sql(manager.getConnection(),"table","steering_definitions");
            assertTrue(ddl.contains("substr(steering_id,1,4)='str_'"));
            assertTrue(ddl.contains("substr(steering_id,17,1)='4'"));
            assertTrue(object(manager.getConnection(),"trigger","r1d_steering_definition_immutable"));
            assertTrue(object(manager.getConnection(),"trigger","r1d_steering_terminal_immutable"));
            assertTrue(object(manager.getConnection(),"trigger",
                    "r1d_steering_producer_pause_immutable"));
            assertTrue(object(manager.getConnection(),"trigger",
                    "r1d_steering_producer_pause_no_delete"));
        }
    }

    @Test void V23SkipsOptionalLegacyApprovalColumnWhenRecordedHistoryHasNoTable()
            throws Exception{
        try(DatabaseManager manager=new DatabaseManager(tempDir.resolve("partial-history"))){
            Connection connection=manager.getConnection();
            try(Statement statement=connection.createStatement()){
                statement.execute("""
                        CREATE TABLE schema_migrations(
                          version INTEGER PRIMARY KEY,
                          applied_at TEXT DEFAULT (datetime('now')),
                          description TEXT)
                        """);
                for(int version=1;version<=22;version++)statement.executeUpdate(
                        "INSERT INTO schema_migrations(version,description) VALUES("+version+",'existing')");
            }
            new MigrationRunner(connection).runMigrationsThrough(23);
            assertFalse(object(connection,"table","approval_decisions"));
            assertTrue(object(connection,"table","steering_definitions"));
            assertEquals(23,scalar(connection,"SELECT max(version) FROM schema_migrations"));
        }
    }

    private static boolean object(Connection c,String type,String name)throws SQLException{return sql(c,type,name)!=null;}
    private static boolean column(Connection c,String table,String name)throws SQLException{
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery("PRAGMA table_info("+table+")")){
            while(r.next())if(name.equals(r.getString("name")))return true;return false;}
    }
    private static String sql(Connection c,String type,String name)throws SQLException{
        try(PreparedStatement s=c.prepareStatement("SELECT sql FROM sqlite_master WHERE type=? AND name=?")){
            s.setString(1,type);s.setString(2,name);try(ResultSet r=s.executeQuery()){return r.next()?r.getString(1):null;}}
    }
    private static int scalar(Connection c,String sql)throws SQLException{
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){assertTrue(r.next());return r.getInt(1);}
    }
}
