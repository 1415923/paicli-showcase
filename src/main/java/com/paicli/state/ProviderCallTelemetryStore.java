package com.paicli.state;

import com.paicli.provider.telemetry.ProviderCallContext;
import com.paicli.provider.telemetry.ProviderCallObservation;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Independent best-effort V25 writer. Production calls use a dedicated connection so telemetry
 * never commits, rolls back, or participates in an R1 authority transaction.
 */
public class ProviderCallTelemetryStore {
    public static final int DEFAULT_MAX_ROWS = 1000;
    public static final int DEFAULT_RETENTION_DAYS = 30;
    public static final int DEFAULT_CLEANUP_BATCH = 64;
    private static final DateTimeFormatter TELEMETRY_TIMESTAMP =
            new DateTimeFormatterBuilder().appendInstant(9).toFormatter();

    private final Path stateDirectory;
    private final RetentionPolicy retention;

    public ProviderCallTelemetryStore(Path stateDirectory) {
        this(stateDirectory,RetentionPolicy.defaults());
    }

    public ProviderCallTelemetryStore(Path stateDirectory,RetentionPolicy retention) {
        this.stateDirectory = Objects.requireNonNull(stateDirectory)
                .toAbsolutePath().normalize();
        this.retention=Objects.requireNonNull(retention);
    }

    public boolean recordStarted(CallStart start) {
        Objects.requireNonNull(start, "start");
        try (DatabaseManager database = new DatabaseManager(stateDirectory);
             PreparedStatement statement = database.getConnection().prepareStatement("""
                     INSERT INTO provider_call_telemetry(
                         correlation_id,provider,model,operation_kind,session_id,task_id,run_id,
                         generation_id,lifecycle,started_at,retry_count,usage_source,usage_coverage,
                         created_at,updated_at)
                     VALUES(?,?,?,?,?,?,?,?, 'STARTED', ?,0,'NOT_EXPOSED','NONE',?,?)
                     """)) {
            int index = 1;
            statement.setString(index++, correlationIdentity(start.correlationId()));
            statement.setString(index++, providerIdentity(start.provider(),64,true));
            nullable(statement, index++, providerIdentity(start.model(),128,false));
            statement.setString(index++, start.operationKind().name());
            ProviderCallContext context = start.context() == null
                    ? ProviderCallContext.empty() : start.context();
            nullable(statement, index++, contextIdentity(context.sessionId()));
            nullable(statement, index++, contextIdentity(context.taskId()));
            nullable(statement, index++, contextIdentity(context.runId()));
            nullable(statement, index++, contextIdentity(context.generationId()));
            statement.setString(index++, timestamp(start.startedAt()));
            statement.setString(index++, timestamp(start.startedAt()));
            statement.setString(index, timestamp(start.startedAt()));
            return statement.executeUpdate() == 1;
        } catch (IOException | SQLException | RuntimeException ignored) {
            return false;
        }
    }

    public boolean finalizeCall(CallFinish finish) {
        Objects.requireNonNull(finish, "finish");
        try (DatabaseManager database = new DatabaseManager(stateDirectory)) {
            Connection connection = database.getConnection();
            ProviderCallObservation observation = finish.observation() == null
                    ? ProviderCallObservation.notExposed() : finish.observation();
            boolean updated;
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE provider_call_telemetry SET
                        lifecycle=?,ended_at=?,latency_ms=?,outcome=?,error_category=?,
                        retry_count=?,input_tokens=?,output_tokens=?,total_tokens=?,
                        cache_read_tokens=?,cache_write_tokens=?,reasoning_tokens=?,
                        usage_source=?,usage_coverage=?,tool_call_count=?,response_cardinality=?,
                        finish_reason=?,safe_request_id=?,structural_summary_hash=?,
                        diagnostic_ref=?,updated_at=?
                    WHERE correlation_id=? AND lifecycle='STARTED'
                    """)) {
                int index = 1;
                statement.setString(index++, finish.lifecycle().name());
                statement.setString(index++, timestamp(finish.endedAt()));
                statement.setLong(index++, Math.max(0, finish.latencyMillis()));
                statement.setString(index++, internalCode(finish.outcome(),64));
                nullable(statement, index++, bounded(observation.errorCategory(), 64, false));
                statement.setInt(index++, Math.max(0, finish.retryCount()));
                nullableInt(statement, index++, observation.inputTokens());
                nullableInt(statement, index++, observation.outputTokens());
                nullableInt(statement, index++, observation.totalTokens());
                nullableInt(statement, index++, observation.cacheReadTokens());
                nullableInt(statement, index++, observation.cacheWriteTokens());
                nullableInt(statement, index++, observation.reasoningTokens());
                statement.setString(index++, observation.usageSource().name());
                statement.setString(index++, observation.usageCoverage().name());
                nullableInt(statement, index++, observation.toolCallCount());
                nullable(statement, index++, observation.responseCardinality() == null
                        ? null : observation.responseCardinality().name());
                nullable(statement, index++, observation.finishReason());
                nullable(statement, index++, observation.safeRequestId());
                nullable(statement, index++, validHash(finish.structuralSummaryHash()));
                nullable(statement, index++, diagnosticReference(finish.diagnosticRef()));
                statement.setString(index++, timestamp(finish.endedAt()));
                statement.setString(index, correlationIdentity(finish.correlationId()));
                updated = statement.executeUpdate() == 1;
            }
            if (updated) cleanup(connection, finish.endedAt(),retention);
            return updated;
        } catch (IOException | SQLException | RuntimeException ignored) {
            return false;
        }
    }

    /** Best-effort non-authority V25 row to V26 ctx_ link. */
    public boolean linkContext(String correlationId,String contextGenerationId){
        if(contextGenerationId==null)return false;
        try(DatabaseManager database=new DatabaseManager(stateDirectory);
            PreparedStatement statement=database.getConnection().prepareStatement("""
              INSERT OR IGNORE INTO provider_context_telemetry_links(telemetry_id,context_generation_id,linked_at)
              SELECT telemetry_id,?,? FROM provider_call_telemetry WHERE correlation_id=?
              """)){
            statement.setString(1,contextGenerationId);statement.setString(2,timestamp(Instant.now()));
            statement.setString(3,correlationIdentity(correlationId));
            return statement.executeUpdate()==1;
        }catch(IOException|SQLException|RuntimeException ignored){return false;}
    }

    /** Cumulative Provider usage for one Task across its INITIAL and RESUME Run chain. */
    public TaskUsage taskUsage(String taskId) {
        try (DatabaseManager database = new DatabaseManager(stateDirectory);
             PreparedStatement statement = database.getConnection().prepareStatement("""
                     SELECT COUNT(*) AS calls,
                            SUM(CASE WHEN lifecycle='STARTED' THEN 1 ELSE 0 END) AS open_calls,
                            SUM(CASE WHEN run_id IS NULL THEN 1 ELSE 0 END) AS missing_run,
                            SUM(CASE WHEN run_id IS NOT NULL AND NOT EXISTS (
                                      SELECT 1 FROM durable_runs dr
                                      WHERE dr.task_id=provider_call_telemetry.task_id
                                        AND dr.run_id=provider_call_telemetry.run_id)
                                     THEN 1 ELSE 0 END) AS foreign_run,
                            SUM(CASE WHEN usage_source<>'PROVIDER_REPORTED'
                                      OR usage_coverage<>'COMPLETE'
                                      OR input_tokens IS NULL OR output_tokens IS NULL
                                     THEN 1 ELSE 0 END) AS incomplete_usage,
                            COALESCE(SUM(input_tokens),0) AS input_tokens,
                            COALESCE(SUM(output_tokens),0) AS output_tokens,
                            COALESCE(SUM(tool_call_count),0) AS requested_tools
                     FROM provider_call_telemetry WHERE task_id=?
                     """)) {
            statement.setString(1, contextIdentity(taskId));
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return TaskUsage.unavailable("PROVIDER_LEDGER_QUERY_EMPTY");
                int calls = row.getInt("calls");
                boolean complete = row.getInt("open_calls") == 0
                        && row.getInt("missing_run") == 0
                        && row.getInt("foreign_run") == 0
                        && row.getInt("incomplete_usage") == 0;
                return new TaskUsage(complete, calls, row.getInt("requested_tools"),
                        row.getLong("input_tokens"), row.getLong("output_tokens"),
                        complete ? null : "PROVIDER_LEDGER_INCOMPLETE");
            }
        } catch (IOException | SQLException | RuntimeException error) {
            return TaskUsage.unavailable("PROVIDER_LEDGER_READ_FAILED");
        }
    }

    private static void cleanup(Connection connection, Instant now,
            RetentionPolicy retention) {
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement old = connection.prepareStatement("""
                    DELETE FROM provider_call_telemetry WHERE telemetry_id IN (
                        SELECT telemetry_id FROM provider_call_telemetry
                        WHERE lifecycle IN ('SUCCEEDED','FAILED','CANCELLED')
                          AND (task_id IS NULL OR NOT EXISTS (
                              SELECT 1 FROM tasks WHERE tasks.task_id=provider_call_telemetry.task_id
                                AND tasks.status='RUNNING'))
                          AND started_at<? ORDER BY started_at,telemetry_id LIMIT ?)
                    """)) {
                old.setString(1, timestamp(now.minus(retention.retentionDays(),
                        ChronoUnit.DAYS)));
                old.setInt(2, retention.cleanupBatch());
                old.executeUpdate();
            }
            int excess;
            try (PreparedStatement count = connection.prepareStatement(
                    "SELECT max(count(*)-?,0) FROM provider_call_telemetry")) {
                count.setInt(1, retention.maxRows());
                try (ResultSet row = count.executeQuery()) {
                    excess = row.next() ? row.getInt(1) : 0;
                }
            }
            if (excess > 0) {
                try (PreparedStatement trim = connection.prepareStatement("""
                        DELETE FROM provider_call_telemetry WHERE telemetry_id IN (
                            SELECT telemetry_id FROM provider_call_telemetry
                            WHERE lifecycle IN ('SUCCEEDED','FAILED','CANCELLED')
                              AND (task_id IS NULL OR NOT EXISTS (
                                  SELECT 1 FROM tasks WHERE tasks.task_id=provider_call_telemetry.task_id
                                    AND tasks.status='RUNNING'))
                            ORDER BY started_at,telemetry_id LIMIT ?)
                        """)) {
                    trim.setInt(1, Math.min(excess, retention.cleanupBatch()));
                    trim.executeUpdate();
                }
            }
            connection.commit();
        } catch (SQLException error) {
            try { connection.rollback(); } catch (SQLException ignored) { }
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException ignored) { }
        }
    }

    private static void nullable(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null) statement.setNull(index, Types.VARCHAR);
        else statement.setString(index, value);
    }

    private static void nullableInt(PreparedStatement statement, int index, Integer value)
            throws SQLException {
        if (value == null) statement.setNull(index, Types.INTEGER);
        else statement.setInt(index, value);
    }

    private static String bounded(String value, int max, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) throw new IllegalArgumentException("required telemetry value");
            return null;
        }
        String clean = value.trim();
        if (clean.length() > max || clean.chars().anyMatch(ch -> ch < 0x20 || ch == 0x7f)) {
            throw new IllegalArgumentException("unsafe telemetry value");
        }
        return clean;
    }

    private static String validHash(String value) {
        return value != null && value.matches("[0-9a-f]{64}") ? value : null;
    }

    private static String timestamp(Instant value) {
        return TELEMETRY_TIMESTAMP.format(Objects.requireNonNull(value));
    }

    private static String contextIdentity(String value){
        return value!=null&&value.matches("[A-Za-z0-9._-]{1,128}")?value:null;
    }

    private static String correlationIdentity(String value){
        if(value==null||!value.matches("[A-Za-z0-9._:-]{16,64}"))
            throw new IllegalArgumentException("unsafe telemetry correlation identity");
        return value;
    }

    private static String internalCode(String value,int max){
        if(value==null||!value.matches("[A-Za-z0-9._:-]{1,"+max+"}"))
            throw new IllegalArgumentException("unsafe telemetry code");
        return value;
    }

    private static String providerIdentity(String value,int max,boolean required){
        if(value==null||!value.matches("[A-Za-z0-9._:/@-]{1,"+max+"}")){
            if(required)throw new IllegalArgumentException("unsafe provider identity");
            return null;
        }
        return value;
    }

    private static String diagnosticReference(String value){
        return value!=null&&value.matches("[A-Za-z0-9._:-]{1,128}")?value:null;
    }

    public enum OperationKind { CHAT, PLAN, HEALTH, MODEL_DISCOVERY }
    public enum Lifecycle { SUCCEEDED, FAILED, CANCELLED }

    /** Runtime policy input; defaults are R2 choices, not a persisted authority contract. */
    public record RetentionPolicy(int retentionDays,int maxRows,int cleanupBatch) {
        public RetentionPolicy {
            if(retentionDays<1||maxRows<1||cleanupBatch<1||cleanupBatch>maxRows)
                throw new IllegalArgumentException("invalid telemetry retention policy");
        }
        public static RetentionPolicy defaults(){
            return new RetentionPolicy(DEFAULT_RETENTION_DAYS,DEFAULT_MAX_ROWS,
                    DEFAULT_CLEANUP_BATCH);
        }
    }

    public record CallStart(String correlationId, String provider, String model,
            OperationKind operationKind, ProviderCallContext context, Instant startedAt) {
        public CallStart {
            Objects.requireNonNull(operationKind);
            Objects.requireNonNull(startedAt);
        }
    }

    public record CallFinish(String correlationId, Lifecycle lifecycle, Instant endedAt,
            long latencyMillis, String outcome, int retryCount,
            ProviderCallObservation observation, String structuralSummaryHash,
            String diagnosticRef) {
        public CallFinish {
            Objects.requireNonNull(lifecycle);
            Objects.requireNonNull(endedAt);
        }
    }

    public record TaskUsage(boolean complete, int providerTurns, int requestedToolCalls,
            long inputTokens, long outputTokens, String missingEvidence) {
        public static TaskUsage unavailable(String reason) {
            return new TaskUsage(false, 0, 0, 0, 0, reason);
        }
    }
}
