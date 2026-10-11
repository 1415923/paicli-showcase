package com.paicli.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class R2ProviderTelemetryMigrationTest {
    @TempDir Path temp;

    @Test void freshAndV24UpgradeHaveTheSameV25TelemetryShapeAndAreIdempotent()
            throws Exception {
        try (DatabaseManager fresh = new DatabaseManager(temp.resolve("fresh"));
             DatabaseManager upgraded = new DatabaseManager(temp.resolve("upgraded"))) {
            new MigrationRunner(fresh.getConnection()).runMigrations();
            MigrationRunner runner = new MigrationRunner(upgraded.getConnection());
            runner.runMigrationsThrough(24);
            assertFalse(object(upgraded.getConnection(), "table", "provider_call_telemetry"));
            runner.runMigrations();
            runner.runMigrations();
            assertEquals(33, scalar(upgraded.getConnection(),
                    "SELECT max(version) FROM schema_migrations"));
            assertEquals(columns(fresh.getConnection()), columns(upgraded.getConnection()));
            assertTrue(columns(fresh.getConnection()).containsAll(Set.of(
                    "correlation_id", "provider", "model", "operation_kind", "session_id",
                    "task_id", "run_id", "generation_id", "lifecycle", "started_at",
                    "ended_at", "latency_ms", "outcome", "error_category", "retry_count",
                    "input_tokens", "output_tokens", "total_tokens", "cache_read_tokens",
                    "cache_write_tokens", "reasoning_tokens", "usage_source",
                    "usage_coverage", "tool_call_count", "response_cardinality",
                    "finish_reason", "safe_request_id", "structural_summary_hash",
                    "diagnostic_ref")));
            for (String index : Set.of("idx_v25_provider_call_task_run_started",
                    "idx_v25_provider_call_session_started",
                    "idx_v25_provider_call_outcome_started",
                    "idx_v25_provider_call_provider_model_started")) {
                assertTrue(object(upgraded.getConnection(), "index", index));
            }
            assertFalse(object(upgraded.getConnection(), "table", "provider_session_usage"));
            assertTrue(tableSql(upgraded.getConnection()).toUpperCase().endsWith("STRICT"));
            assertTrue(new SchemaIntegrityChecker(upgraded.getConnection()).check().clean());
        }
    }

    @Test void failedV25RollsBackItsIndexesAndVersionRecord() throws Exception {
        try (DatabaseManager manager = new DatabaseManager(temp.resolve("rollback"))) {
            Connection connection = manager.getConnection();
            MigrationRunner runner = new MigrationRunner(connection);
            runner.runMigrationsThrough(24);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE provider_call_telemetry(blocker TEXT)");
            }
            assertThrows(SQLException.class, runner::runMigrations);
            assertEquals(24, scalar(connection,
                    "SELECT max(version) FROM schema_migrations"));
            assertFalse(object(connection, "index",
                    "idx_v25_provider_call_task_run_started"));
        }
    }

    @Test void lifecycleAndNonNegativeConstraintsPreserveNullVersusZero() throws Exception {
        try (DatabaseManager manager = new DatabaseManager(temp.resolve("constraints"))) {
            Connection connection = manager.getConnection();
            new MigrationRunner(connection).runMigrations();
            execute(connection, row("good-null", "STARTED", null, null, "NULL"));
            execute(connection, row("good-zero", "SUCCEEDED",
                    "2026-07-20T00:00:01.000000000Z", "OK", "0"));
            execute(connection, row("good-fraction", "SUCCEEDED",
                    "2026-07-20T00:00:00.012000000Z", "OK", "1"));
            assertEquals(1, scalar(connection,
                    "SELECT count(*) FROM provider_call_telemetry WHERE input_tokens IS NULL"));
            assertEquals(1, scalar(connection,
                    "SELECT count(*) FROM provider_call_telemetry WHERE input_tokens=0"));
            assertThrows(SQLException.class, () -> execute(connection,
                    row("bad-time", "SUCCEEDED",
                            "2026-07-19T23:59:59.000000000Z", "OK", "1")));
            assertThrows(SQLException.class, () -> execute(connection,
                    row("bad-t9", "SUCCEEDED", "2026-07-20T00:00:01Z", "OK", "1")));
            assertThrows(SQLException.class, () -> execute(connection,
                    row("bad-lifecycle", "BOGUS",
                            "2026-07-20T00:00:01.000000000Z", "OK", "1")));
            assertThrows(SQLException.class, () -> execute(connection,
                    row("bad-token", "SUCCEEDED",
                            "2026-07-20T00:00:01.000000000Z", "OK", "-1")));
            assertThrows(SQLException.class, () -> execute(connection,
                    row("bad-usage", "SUCCEEDED",
                            "2026-07-20T00:00:01.000000000Z", "OK", "1")
                            .replace("'PROVIDER_REPORTED','COMPLETE'",
                                    "'NOT_EXPOSED','NONE'")));
        }
    }

    @Test void canonicalNormalizerIgnoresOnlyLexicalNoise() {
        String canonical=ProviderTelemetrySchemaContract.createTableSql();
        String lexicalVariant=canonical
                .replaceFirst("CREATE TABLE","create /* harmless */ table")
                .replace(") STRICT",") strict;");
        assertEquals(ProviderTelemetrySchemaContract.normalize(canonical),
                ProviderTelemetrySchemaContract.normalize(lexicalVariant));
        assertEquals(ProviderTelemetrySchemaContract.fingerprint(canonical),
                ProviderTelemetrySchemaContract.fingerprint(lexicalVariant));
        assertNotEquals(ProviderTelemetrySchemaContract.fingerprint(canonical),
                ProviderTelemetrySchemaContract.fingerprint(
                        canonical.replaceFirst("'STARTED'","'started'")));
        assertNotEquals(ProviderTelemetrySchemaContract.normalize("CHECK(X=1)"),
                ProviderTelemetrySchemaContract.normalize("CHECK((X=1))"));
        assertNotEquals(ProviderTelemetrySchemaContract.normalize("CHECK(X>=0)"),
                ProviderTelemetrySchemaContract.normalize("CHECK(X>0)"));
    }

    @Test void checkerRejectsDeletedOrWeakenedV25ChecksWithShapeStillPresent()
            throws Exception {
        assertContractMismatch(temp.resolve("missing-lifecycle"),sql->sql.replace(
                " CHECK(lifecycle IN ('STARTED','SUCCEEDED','FAILED','CANCELLED'))",""));
        assertContractMismatch(temp.resolve("missing-usage"),sql->removeCheckContaining(sql,
                "CHECK((usage_source='NOT_EXPOSED'"));
        assertContractMismatch(temp.resolve("weak-numeric"),sql->sql.replace(
                "AND retry_count BETWEEN 0 AND 2147483647","AND retry_count>=0"));
        assertContractMismatch(temp.resolve("missing-lifecycle-relations"),sql->
                removeCheckContaining(sql,"CHECK((lifecycle='STARTED'"));
    }

    @Test void damagedLifecycleContractIsReportedBeforeTypedReadRejectsBadRow()
            throws Exception {
        try(DatabaseManager manager=new DatabaseManager(temp.resolve("typed-after-contract"))){
            Connection connection=manager.getConnection();
            new MigrationRunner(connection).runMigrations();
            rebuildTelemetry(connection,ProviderTelemetrySchemaContract.createTableSql().replace(
                    " CHECK(lifecycle IN ('STARTED','SUCCEEDED','FAILED','CANCELLED'))",""));
            execute(connection,"""
                    INSERT INTO provider_call_telemetry(
                        correlation_id,provider,model,operation_kind,task_id,run_id,lifecycle,
                        started_at,ended_at,latency_ms,outcome,retry_count,usage_source,
                        usage_coverage,created_at,updated_at)
                    VALUES('bad-lifecycle-000000000000','ollama','model','CHAT','task','run',
                        'BOGUS','2026-07-20T00:00:00.000000000Z',
                        '2026-07-20T00:00:01.000000000Z',1,'BAD',0,'NOT_EXPOSED','NONE',
                        '2026-07-20T00:00:00.000000000Z',
                        '2026-07-20T00:00:01.000000000Z')
                    """);
            SchemaIntegrityChecker.IntegrityReport report=
                    new SchemaIntegrityChecker(connection).check();
            assertTrue(codes(report).contains("V25_TELEMETRY_TABLE_CONTRACT_MISMATCH"));
            assertThrows(ProviderCallTelemetryReader.TelemetryIntegrityException.class,()->
                    new ProviderCallTelemetryReader(connection).readAll(
                            ProviderCallTelemetryReader.Scope.taskRun(
                                    "task","run","ollama","model")));
        }
    }

    private static void assertContractMismatch(Path path,UnaryOperator<String> mutation)
            throws Exception{
        try(DatabaseManager manager=new DatabaseManager(path)){
            Connection connection=manager.getConnection();
            new MigrationRunner(connection).runMigrations();
            String mutated=mutation.apply(ProviderTelemetrySchemaContract.createTableSql());
            assertNotEquals(ProviderTelemetrySchemaContract.createTableSql(),mutated);
            rebuildTelemetry(connection,mutated);
            assertEquals(ProviderTelemetrySchemaContract.expectedColumns(),columns(connection));
            assertTrue(tableSql(connection).toUpperCase().endsWith("STRICT"));
            for(String index:ProviderTelemetrySchemaContract.expectedIndexes())
                assertTrue(object(connection,"index",index));
            SchemaIntegrityChecker.IntegrityReport report=
                    new SchemaIntegrityChecker(connection).check();
            assertTrue(codes(report).contains("V25_TELEMETRY_TABLE_CONTRACT_MISMATCH"));
            SchemaIntegrityChecker.Finding mismatch=report.findings().stream()
                    .filter(item->"V25_TELEMETRY_TABLE_CONTRACT_MISMATCH".equals(item.code()))
                    .findFirst().orElseThrow();
            assertTrue(mismatch.evidenceSummary().contains(
                    ProviderTelemetrySchemaContract.CONTRACT_VERSION));
            assertTrue(mismatch.evidenceSummary().contains("expected="));
            assertTrue(mismatch.evidenceSummary().contains("actual="));
            assertFalse(mismatch.evidenceSummary().contains("CREATE TABLE"));
            assertFalse(mismatch.evidenceSummary().contains(mutated));
        }
    }

    private static void rebuildTelemetry(Connection connection,String tableSql)throws SQLException{
        try(Statement statement=connection.createStatement()){
            statement.execute("DROP TABLE provider_call_telemetry");
            statement.execute(tableSql);
            for(String indexSql:ProviderTelemetrySchemaContract.indexSql().values())
                statement.execute(indexSql);
        }
    }

    private static String removeCheckContaining(String sql,String marker){
        int start=sql.indexOf(marker);
        if(start<0)throw new IllegalArgumentException("constraint marker absent");
        int open=sql.indexOf('(',start);
        int depth=0;
        boolean literal=false;
        for(int index=open;index<sql.length();index++){
            char value=sql.charAt(index);
            if(value=='\''){
                if(literal&&index+1<sql.length()&&sql.charAt(index+1)=='\''){index++;continue;}
                literal=!literal;
            }else if(!literal&&value=='(')depth++;
            else if(!literal&&value==')'&&--depth==0){
                int end=index+1;
                int removalStart=start;
                int before=start-1;
                while(before>=0&&Character.isWhitespace(sql.charAt(before)))before--;
                if(before>=0&&sql.charAt(before)==',')removalStart=before;
                else{
                    while(end<sql.length()&&Character.isWhitespace(sql.charAt(end)))end++;
                    if(end<sql.length()&&sql.charAt(end)==',')end++;
                }
                return sql.substring(0,removalStart)+sql.substring(end);
            }
        }
        throw new IllegalArgumentException("unterminated constraint");
    }

    private static Set<String> codes(SchemaIntegrityChecker.IntegrityReport report){
        return report.findings().stream().map(SchemaIntegrityChecker.Finding::code)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static String row(String id, String lifecycle, String ended,
            String outcome, String tokens) {
        boolean started="STARTED".equals(lifecycle);
        String source=started?"NOT_EXPOSED":"PROVIDER_REPORTED";
        String coverage=started?"NONE":"COMPLETE";
        String terminalTokens=started?"NULL":tokens;
        return "INSERT INTO provider_call_telemetry(correlation_id,provider,operation_kind,"
                + "lifecycle,started_at,ended_at,latency_ms,outcome,retry_count,input_tokens,"
                + "output_tokens,total_tokens,usage_source,usage_coverage,created_at,updated_at) "
                + "VALUES('" + id + "-0000000000000000','ollama','CHAT','" + lifecycle
                + "','2026-07-20T00:00:00.000000000Z',"
                + (ended == null ? "NULL" : "'" + ended + "'") + ","
                + (started?"NULL":"1") + ","
                + (outcome == null ? "NULL" : "'" + outcome + "'") + ",0,"+terminalTokens
                +","+terminalTokens+","+terminalTokens+",'"+source+"','"+coverage
                +"','2026-07-20T00:00:00.000000000Z',"
                +(started?"'2026-07-20T00:00:00.000000000Z'":"'"+ended+"'")+")";
    }

    private static String tableSql(Connection connection)throws SQLException{
        try(PreparedStatement statement=connection.prepareStatement(
                "SELECT sql FROM sqlite_master WHERE type='table' AND name='provider_call_telemetry'");
            ResultSet row=statement.executeQuery()){
            assertTrue(row.next());return row.getString(1).trim();
        }
    }

    private static Set<String> columns(Connection connection) throws SQLException {
        Set<String> result = new java.util.HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "PRAGMA table_info(provider_call_telemetry)")) {
            while (rows.next()) result.add(rows.getString("name"));
        }
        return Set.copyOf(result);
    }
    private static boolean object(Connection connection, String type, String name)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type=? AND name=?")) {
            statement.setString(1, type); statement.setString(2, name);
            try (ResultSet row = statement.executeQuery()) { return row.next(); }
        }
    }
    private static int scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            assertTrue(row.next()); return row.getInt(1);
        }
    }
    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.execute(sql); }
    }
}
