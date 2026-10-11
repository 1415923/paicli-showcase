package com.paicli.state;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.StringJoiner;

import static org.junit.jupiter.api.Assertions.*;

class R2TypedProviderTelemetryReaderTest {
    @TempDir Path temp;

    @Test void textAndRealNumericsAreNeverCoercedToZero() throws Exception {
        try(DatabaseManager manager=migrated("numeric")){
            Connection connection=looseFixture(manager.getConnection());
            insert(connection,"session-text","'0'","0","0","2026-07-20T00:00:00.000000000Z",
                    "SUCCEEDED","PROVIDER_REPORTED","COMPLETE");
            insert(connection,"session-real","0.0","0","0","2026-07-20T00:00:00.000000000Z",
                    "SUCCEEDED","PROVIDER_REPORTED","COMPLETE");
            ProviderCallTelemetryReader reader=new ProviderCallTelemetryReader(connection);
            assertThrows(ProviderCallTelemetryReader.TelemetryIntegrityException.class,()->
                    reader.latest(scope("session-text")));
            assertThrows(ProviderCallTelemetryReader.TelemetryIntegrityException.class,()->
                    reader.aggregate(scope("session-real")));
        }
    }

    @Test void malformedTimeLifecycleAndUsageRelationshipsFailWholeScope() throws Exception {
        try(DatabaseManager manager=migrated("relations")){
            Connection connection=looseFixture(manager.getConnection());
            insert(connection,"bad-time","0","0","0","2026-07-20T00:00:00Z",
                    "SUCCEEDED","PROVIDER_REPORTED","COMPLETE");
            insert(connection,"bad-life","0","0","0","2026-07-20T00:00:00.000000000Z",
                    "STARTED","PROVIDER_REPORTED","COMPLETE");
            insert(connection,"bad-usage","NULL","NULL","NULL",
                    "2026-07-20T00:00:00.000000000Z","SUCCEEDED",
                    "PROVIDER_REPORTED","COMPLETE");
            ProviderCallTelemetryReader reader=new ProviderCallTelemetryReader(connection);
            for(String id:new String[]{"bad-time","bad-life","bad-usage"}){
                assertThrows(ProviderCallTelemetryReader.TelemetryIntegrityException.class,
                        ()->reader.readAll(scope(id)));
            }
        }
    }

    @Test void nullZeroPartialAndScopeIsolationRemainDistinct() throws Exception {
        try(DatabaseManager manager=migrated("coverage")){
            Connection connection=looseFixture(manager.getConnection());
            insert(connection,"session-good","0","NULL","NULL",
                    "2026-07-20T00:00:00.000000000Z","SUCCEEDED",
                    "PROVIDER_REPORTED","PARTIAL");
            insert(connection,"session-bad","'7'","0","7",
                    "2026-07-20T00:00:00.000000000Z","SUCCEEDED",
                    "PROVIDER_REPORTED","COMPLETE");
            ProviderCallTelemetryReader reader=new ProviderCallTelemetryReader(connection);
            ProviderCallTelemetryReader.UsageAggregate aggregate=reader.aggregate(
                    scope("session-good"));
            assertEquals(0L,aggregate.inputTokens());
            assertNull(aggregate.outputTokens());
            assertNull(aggregate.totalTokens());
            assertEquals("PARTIAL",aggregate.coverage());
            assertThrows(ProviderCallTelemetryReader.TelemetryIntegrityException.class,
                    ()->reader.aggregate(scope("session-bad")));
        }
    }

    @Test void checkedAggregateOverflowIsUnavailable() {
        assertThrows(ProviderCallTelemetryReader.TelemetryIntegrityException.class,
                ()->ProviderCallTelemetryReader.addNullable(Long.MAX_VALUE,1));
    }

    private DatabaseManager migrated(String name)throws Exception{
        DatabaseManager manager=new DatabaseManager(temp.resolve(name));
        new MigrationRunner(manager.getConnection()).runMigrations();
        return manager;
    }

    private static Connection looseFixture(Connection connection)throws Exception{
        try(Statement statement=connection.createStatement()){
            statement.execute("ALTER TABLE provider_call_telemetry RENAME TO provider_call_telemetry_strict");
            StringJoiner columns=new StringJoiner(",");
            try(ResultSet row=statement.executeQuery(
                    "PRAGMA table_info(provider_call_telemetry_strict)")){
                while(row.next()){
                    String name=row.getString("name");
                    columns.add("\""+name.replace("\"","\"\"")+"\"");
                }
            }
            // Omit declared types so SQLite cannot apply numeric affinity before the production
            // reader observes typeof(column). This models external/legacy storage corruption.
            statement.execute("CREATE TABLE provider_call_telemetry("+columns+")");
        }
        return connection;
    }

    private static ProviderCallTelemetryReader.Scope scope(String session){
        return ProviderCallTelemetryReader.Scope.session(session,"ollama","model");
    }

    private static void insert(Connection connection,String session,String input,String output,
            String total,String started,String lifecycle,String source,String coverage)throws Exception{
        boolean isStarted="STARTED".equals(lifecycle);
        String ended=isStarted?"NULL":"'2026-07-20T00:00:01.000000000Z'";
        String latency=isStarted?"NULL":"1000";
        String outcome=isStarted?"NULL":"'OK'";
        String updated=isStarted?"'"+started+"'":ended;
        String sql="INSERT INTO provider_call_telemetry(telemetry_id,correlation_id,provider,model,"
                +"operation_kind,session_id,lifecycle,started_at,ended_at,latency_ms,outcome,"
                +"retry_count,input_tokens,output_tokens,total_tokens,usage_source,usage_coverage,"
                +"created_at,updated_at) VALUES(1,'1234567890abcdef','ollama','model','CHAT','"
                +session+"','"+lifecycle+"','"+started+"',"+ended+","+latency+","+outcome
                +",0,"+input+","+output+","+total+",'"+source+"','"+coverage+"','"
                +started+"',"+updated+")";
        try(Statement statement=connection.createStatement()){statement.execute(sql);}
    }
}
