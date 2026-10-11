package com.paicli.state;

import com.paicli.config.Config;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.interactive.command.InteractiveCompactionCommandService;
import com.paicli.interactive.command.ShellCommandResult;
import com.paicli.interactive.session.InteractiveSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;

class R3ContextSchemaMigrationTest {
    @TempDir Path temp;
    @Test void freshAndV25UpgradeCreateExactlyFiveStrictCoreTables()throws Exception{
        try(DatabaseManager fresh=new DatabaseManager(temp.resolve("fresh"));
            DatabaseManager upgraded=new DatabaseManager(temp.resolve("upgrade"))){
            new MigrationRunner(fresh.getConnection()).runMigrations();
            var runner=new MigrationRunner(upgraded.getConnection());runner.runMigrationsThrough(25);
            runner.runMigrations();runner.runMigrations();
            assertEquals(33,scalar(upgraded.getConnection(),"SELECT max(version) FROM schema_migrations"));
            assertEquals(33,scalar(fresh.getConnection(),"SELECT max(version) FROM schema_migrations"));
            assertEquals(ContextSchemaContract.tableNames(),r3Tables(upgraded.getConnection()));
            assertEquals(ContextSchemaContract.tableNames(),r3Tables(fresh.getConnection()));
            assertEquals(5,ContextSchemaContract.tableNames().size());
            for(String table:ContextSchemaContract.tableNames())
                assertEquals(1,scalar(upgraded.getConnection(),
                        "SELECT strict FROM pragma_table_list WHERE name='"+table+"'"));
            assertTrue(new SchemaIntegrityChecker(upgraded.getConnection()).check().clean());
            assertTrue(new SchemaIntegrityChecker(fresh.getConnection()).check().clean());
        }
    }
    @Test void contractAttestationRejectsChangedIndexAndNeverUsesWriteProbe()throws Exception{
        try(DatabaseManager manager=new DatabaseManager(temp.resolve("damage"))){
            Connection connection=manager.getConnection();new MigrationRunner(connection).runMigrations();
            try(Statement s=connection.createStatement()){
                s.execute("DROP INDEX idx_v26_context_identity");
                s.execute("CREATE INDEX idx_v26_context_identity ON context_generations(task_id,created_at)");
            }
            var report=new SchemaIntegrityChecker(connection).check();
            assertTrue(report.findings().stream().anyMatch(f->
                    "V26_CONTEXT_INDEX_CONTRACT_MISMATCH".equals(f.code())));
            assertTrue(report.findings().stream().noneMatch(f->
                    (f.entityLocation()+" "+f.message()+" "+f.evidenceSummary()).contains("CREATE ")));
        }
    }
    @Test void contractContainsLifecycleCandidateAndNoRawPromptTable(){
        String ddl=String.join("\n",ContextSchemaContract.statements());
        assertTrue(ddl.contains("'PREPARED'"));assertTrue(ddl.contains("'SUPERSEDED'"));
        assertFalse(ddl.contains("raw_prompt"));assertFalse(ddl.contains("compaction_candidates"));
        assertEquals(5,ddl.split("CREATE TABLE",-1).length-1);
    }
    @Test void lifecycleWeakeningChangesCanonicalTableFingerprint(){
        String canonical=ContextSchemaContract.statements().stream()
                .filter(sql->sql.contains("CREATE TABLE context_generations")).findFirst().orElseThrow();
        String weakened=canonical.replace("'PREPARING','PREPARED','READY','FAILED','SUPERSEDED'",
                "'PREPARING','READY','FAILED','SUPERSEDED'");
        assertNotEquals(ProviderTelemetrySchemaContract.fingerprint(canonical),
                ProviderTelemetrySchemaContract.fingerprint(weakened));
    }
    @Test void checkerRejectsEachCriticalLifecyclePointerAndPreparedFreezeTriggerDamage()throws Exception{
        for(String trigger:List.of("v26_context_state_transition","v26_context_frozen_immutable",
                "v26_context_source_scope","v26_context_pointer_ready_insert",
                "v26_context_pointer_ready_update","v26_context_no_supersede_active",
                "v26_context_segment_insert_immutable","v26_context_segment_update_immutable",
                "v26_context_segment_delete_immutable","v26_context_reference_insert_immutable",
                "v26_context_reference_update_immutable","v26_context_reference_delete_immutable")){
            try(DatabaseManager manager=new DatabaseManager(temp.resolve(trigger))){
                Connection connection=manager.getConnection();new MigrationRunner(connection).runMigrations();
                try(Statement statement=connection.createStatement()){
                    statement.execute("DROP TRIGGER "+trigger);
                }
                assertTrue(new SchemaIntegrityChecker(connection).check().findings().stream()
                        .anyMatch(finding->"V26_CONTEXT_TRIGGER_CONTRACT_MISMATCH".equals(finding.code())
                                &&finding.entityLocation().contains(trigger)));
            }
        }
    }
    @Test void criticalCheckWeakeningChangesFullTableFingerprint(){
        String canonical=ContextSchemaContract.statements().stream()
                .filter(sql->sql.contains("CREATE TABLE context_segments")).findFirst().orElseThrow();
        String weakened=canonical.replace("bounds BETWEEN 0 AND 1048576","bounds>=0");
        assertNotEquals(ProviderTelemetrySchemaContract.fingerprint(canonical),
                ProviderTelemetrySchemaContract.fingerprint(weakened));
    }
    @Test void checkerRejectsActualTableAndCheckContractDamage()throws Exception{
        try(DatabaseManager manager=new DatabaseManager(temp.resolve("table-check-damage"))){
            Connection connection=manager.getConnection();new MigrationRunner(connection).runMigrations();
            try(Statement statement=connection.createStatement()){
                statement.execute("PRAGMA writable_schema=ON");
                assertEquals(1,statement.executeUpdate("""
                  UPDATE sqlite_master SET sql=replace(sql,'bounds BETWEEN 0 AND 1048576','bounds>=0')
                  WHERE type='table' AND name='context_segments'
                  """));
                statement.execute("PRAGMA writable_schema=OFF");
            }
            assertTrue(new SchemaIntegrityChecker(connection).check().findings().stream()
                    .anyMatch(finding->"V26_CONTEXT_TABLE_CONTRACT_MISMATCH".equals(finding.code())
                            &&finding.entityLocation().contains("context_segments")));
        }
    }

    @Test void v25StandalonePreviewWithoutAuthorityIsEffectFree()throws Exception{
        Path workspace=v25Workspace("standalone-preview-v25");
        String taskId=CanonicalId.newId(EntityFamily.TASK).value();
        String runId=CanonicalId.newId(EntityFamily.RUN).value();
        assertV25EffectFree(workspace,()->new InteractiveCompactionCommandService(workspace)
                .compactNonInteractive(List.of("preview","--task",taskId,"--run",runId)),
                "CONTEXT_OWNERSHIP_REQUIRED");
    }

    @Test void v25StandaloneApplyWithoutAuthorityIsEffectFree()throws Exception{
        Path workspace=v25Workspace("standalone-apply-v25");
        String contextId=CanonicalId.newId(EntityFamily.CONTEXT).value();
        assertV25EffectFree(workspace,()->new InteractiveCompactionCommandService(workspace)
                .compactNonInteractive(List.of("apply",contextId)),"CONTEXT_OWNERSHIP_REQUIRED");
    }

    @Test void v25InteractivePreviewWithoutSnapshotIsEffectFree()throws Exception{
        Path workspace=v25Workspace("interactive-preview-v25");
        assertV25EffectFree(workspace,()->new InteractiveCompactionCommandService(workspace)
                .compact(List.of("preview"),session(workspace)),"COMPACTION_INVALID_ARGUMENTS");
    }

    @Test void v25InteractiveApplyWithoutSnapshotIsEffectFree()throws Exception{
        Path workspace=v25Workspace("interactive-apply-v25");
        String contextId=CanonicalId.newId(EntityFamily.CONTEXT).value();
        assertV25EffectFree(workspace,()->new InteractiveCompactionCommandService(workspace)
                .compact(List.of("apply",contextId),session(workspace)),
                "COMPACTION_INVALID_ARGUMENTS");
    }

    @Test void v25StatusRemainsReadOnlyAndDoesNotMigrate()throws Exception{
        Path workspace=v25Workspace("status-v25");
        AtomicInteger authorityReads=new AtomicInteger();
        StateSnapshot before=snapshot(workspace);
        ShellCommandResult result=new InteractiveCompactionCommandService(workspace,()->{
            authorityReads.incrementAndGet();
            throw new AssertionError("status must not request mutation authority");
        }).compactNonInteractive(List.of("status"));
        assertEquals("CONTEXT_UNAVAILABLE",result.code());
        assertEquals(0,authorityReads.get());

        StateSnapshot after=snapshot(workspace);
        assertEquals(before.migrations(),after.migrations());
        assertEquals(25,after.maxMigrationVersion());
        assertEquals(before.schemaObjects(),after.schemaObjects());
        assertEquals(before.userVersion(),after.userVersion());
        assertEquals(before.files().get("paicli.db"),after.files().get("paicli.db"));
        assertEquals(withoutReadCoordinationArtifacts(before.files()),
                withoutReadCoordinationArtifacts(after.files()));
        assertNoV26Objects(after);
    }

    private Path v25Workspace(String name)throws Exception{
        Path workspace=temp.resolve(name);
        try(DatabaseManager manager=new DatabaseManager(workspace.resolve(".paicli/state"))){
            new MigrationRunner(manager.getConnection()).runMigrationsThrough(25);
            assertEquals(25,scalar(manager.getConnection(),
                    "SELECT max(version) FROM schema_migrations"));
        }
        return workspace;
    }

    private static void assertV25EffectFree(Path workspace,Supplier<ShellCommandResult> action,
            String expectedCode)throws Exception{
        StateSnapshot before=snapshot(workspace);
        ShellCommandResult result=action.get();
        assertEquals(expectedCode,result.code());
        StateSnapshot after=snapshot(workspace);
        assertEquals(before,after);
        assertEquals(25,after.maxMigrationVersion());
        assertNoV26Objects(after);
    }

    private static void assertNoV26Objects(StateSnapshot snapshot){
        for(String table:ContextSchemaContract.tableNames())
            assertFalse(snapshot.schemaObjects().stream().anyMatch(row->row.startsWith("table|"+table+"|")),
                    "V26 table was created: "+table);
        assertTrue(snapshot.schemaObjects().stream().noneMatch(row->row.startsWith("index|idx_v26_")));
        assertTrue(snapshot.schemaObjects().stream().noneMatch(row->row.startsWith("trigger|v26_")));
    }

    private static Map<String,String> withoutReadCoordinationArtifacts(Map<String,String> files){
        var durableFiles=new TreeMap<>(files);
        durableFiles.remove("paicli.db-wal");
        durableFiles.remove("paicli.db-shm");
        return Map.copyOf(durableFiles);
    }

    private static StateSnapshot snapshot(Path workspace)throws Exception{
        Path stateDirectory=workspace.resolve(".paicli/state");
        Path database=stateDirectory.resolve("paicli.db");
        String url="jdbc:sqlite:file:"+database.toString().replace('\\','/')+"?mode=ro&immutable=1";
        var migrations=new ArrayList<String>();var objects=new ArrayList<String>();int userVersion;
        try(Connection connection=DriverManager.getConnection(url)){
            try(Statement statement=connection.createStatement()){
                statement.execute("PRAGMA query_only=ON");
                try(ResultSet rows=statement.executeQuery(
                        "SELECT version,applied_at,description FROM schema_migrations ORDER BY version")){
                    while(rows.next())migrations.add(rows.getInt(1)+"|"+rows.getString(2)+"|"
                            +String.valueOf(rows.getString(3)));
                }
                try(ResultSet rows=statement.executeQuery(
                        "SELECT type,name,sql FROM sqlite_master ORDER BY type,name")){
                    while(rows.next())objects.add(rows.getString(1)+"|"+rows.getString(2)+"|"
                            +String.valueOf(rows.getString(3)));
                }
                try(ResultSet row=statement.executeQuery("PRAGMA user_version")){
                    userVersion=row.next()?row.getInt(1):0;
                }
            }
        }
        int max=migrations.stream().mapToInt(row->Integer.parseInt(row.substring(0,row.indexOf('|'))))
                .max().orElse(0);
        return new StateSnapshot(List.copyOf(migrations),max,List.copyOf(objects),userVersion,
                fileHashes(stateDirectory));
    }

    private static Map<String,String> fileHashes(Path directory)throws Exception{
        var hashes=new TreeMap<String,String>();
        try(var files=Files.list(directory)){
            for(Path file:files.sorted().toList())if(Files.isRegularFile(file))
                hashes.put(file.getFileName().toString(),sha256(file));
        }
        return Map.copyOf(hashes);
    }

    private static String sha256(Path file)throws Exception{
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(var input=Files.newInputStream(file)){
            byte[] buffer=new byte[8192];int read;
            while((read=input.read(buffer))>=0)if(read>0)digest.update(buffer,0,read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static InteractiveSession.Snapshot session(Path workspace){
        var session=new InteractiveSession("session-v25-effect-free",workspace,"mock","model",
                Config.MODE_DENY,InteractiveSession.TerminalMode.PLAIN,
                Instant.parse("2026-07-22T00:00:00Z"));
        session.start();session.startTask(CanonicalId.newId(EntityFamily.TASK).value(),
                CanonicalId.newId(EntityFamily.RUN).value());
        return session.snapshot();
    }

    private record StateSnapshot(List<String> migrations,int maxMigrationVersion,
            List<String> schemaObjects,int userVersion,Map<String,String> files){}

    private static Set<String> r3Tables(Connection c)throws Exception{
        var out=new java.util.HashSet<String>();try(Statement s=c.createStatement();
            ResultSet r=s.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND (name LIKE 'context_%' OR name='provider_context_telemetry_links')")){
            while(r.next())out.add(r.getString(1));}return Set.copyOf(out);}
    private static int scalar(Connection c,String sql)throws Exception{try(Statement s=c.createStatement();
        ResultSet r=s.executeQuery(sql)){return r.next()?r.getInt(1):0;}}
}
