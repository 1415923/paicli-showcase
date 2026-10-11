package com.paicli.state;

import java.sql.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.*;

/** Typed, read-only access to non-authority V25 Provider telemetry. */
public final class ProviderCallTelemetryReader {
    private static final DateTimeFormatter T9 = new DateTimeFormatterBuilder().appendInstant(9).toFormatter();
    private static final int MAX_INT = Integer.MAX_VALUE;
    private static final List<String> COLUMNS = List.of(
            "telemetry_id", "correlation_id", "provider", "model", "operation_kind",
            "session_id", "task_id", "run_id", "generation_id", "lifecycle",
            "started_at", "ended_at", "latency_ms", "outcome", "error_category",
            "retry_count", "input_tokens", "output_tokens", "total_tokens",
            "cache_read_tokens", "cache_write_tokens", "reasoning_tokens", "usage_source",
            "usage_coverage", "tool_call_count", "response_cardinality", "finish_reason",
            "safe_request_id", "structural_summary_hash", "diagnostic_ref", "created_at",
            "updated_at");

    private final Connection connection;

    public ProviderCallTelemetryReader(Connection connection) {
        this.connection = Objects.requireNonNull(connection);
    }

    public Optional<ValidatedCall> latest(Scope scope) throws SQLException {
        List<ValidatedCall> calls = readAll(scope);
        return calls.isEmpty() ? Optional.empty() : Optional.of(calls.getFirst());
    }

    public Optional<ValidatedCall> latest(Scope scope, String operationKind)
            throws SQLException {
        if (!Set.of("CHAT", "PLAN", "HEALTH", "MODEL_DISCOVERY")
                .contains(operationKind)) {
            throw new IllegalArgumentException("invalid Provider operation kind");
        }
        return readAll(scope).stream()
                .filter(call -> operationKind.equals(call.operationKind())).findFirst();
    }

    public UsageAggregate aggregate(Scope scope) throws SQLException {
        List<ValidatedCall> calls = readAll(scope);
        Long input = null, output = null, total = null;
        boolean partial = false;
        for (ValidatedCall call : calls) {
            input = addNullable(input, call.inputTokens());
            output = addNullable(output, call.outputTokens());
            total = addNullable(total, call.totalTokens());
            partial |= !"COMPLETE".equals(call.usageCoverage());
        }
        return new UsageAggregate(calls.size(), input, output, total,
                calls.isEmpty() ? "NONE" : partial ? "PARTIAL" : "COMPLETE");
    }

    public List<ValidatedCall> readAll(Scope scope) throws SQLException {
        Objects.requireNonNull(scope);
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < COLUMNS.size(); i++) {
            if (i > 0) sql.append(',');
            String column = COLUMNS.get(i);
            sql.append(column).append(",typeof(").append(column).append(") AS type_").append(column);
        }
        sql.append(" FROM provider_call_telemetry WHERE ").append(scope.column()).append("=?");
        if (scope.secondaryColumn() != null) {
            sql.append(" AND ").append(scope.secondaryColumn()).append("=?");
        }
        sql.append(" AND provider=? AND ");
        if (scope.model() == null) sql.append("model IS NULL");
        else sql.append("model=?");
        sql.append(" ORDER BY started_at DESC, telemetry_id DESC");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int parameter = 1;
            statement.setString(parameter++, scope.identity());
            if (scope.secondaryColumn() != null) {
                statement.setString(parameter++, scope.secondaryIdentity());
            }
            statement.setString(parameter++, scope.provider());
            if (scope.model() != null) statement.setString(parameter, scope.model());
            try (ResultSet row = statement.executeQuery()) {
                List<ValidatedCall> calls = new ArrayList<>();
                while (row.next()) calls.add(validate(row));
                return List.copyOf(calls);
            }
        } catch (TelemetryIntegrityException error) {
            throw error;
        } catch (SQLException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new TelemetryIntegrityException("PROVIDER_TELEMETRY_STORAGE_INVALID");
        }
    }

    private static ValidatedCall validate(ResultSet row) throws SQLException {
        long id = requiredLong(row, "telemetry_id", 1, Long.MAX_VALUE);
        String correlation = boundedText(row, "correlation_id", false, 16, 64);
        String provider = boundedText(row, "provider", false, 1, 64);
        String model = boundedText(row, "model", true, 1, 128);
        String operation = enumText(row, "operation_kind", false,
                Set.of("CHAT", "PLAN", "HEALTH", "MODEL_DISCOVERY"));
        String session = boundedText(row, "session_id", true, 1, 128);
        String task = boundedText(row, "task_id", true, 1, 128);
        String run = boundedText(row, "run_id", true, 1, 128);
        String generation = boundedText(row, "generation_id", true, 1, 128);
        String lifecycle = enumText(row, "lifecycle", false,
                Set.of("STARTED", "SUCCEEDED", "FAILED", "CANCELLED"));
        Instant started = requiredT9(row, "started_at");
        Instant ended = nullableT9(row, "ended_at");
        Long latency = nullableLong(row, "latency_ms");
        String outcome = boundedText(row, "outcome", true, 1, 64);
        String errorCategory = boundedText(row, "error_category", true, 1, 64);
        int retry = requiredInt(row, "retry_count");
        Integer input = nullableInt(row, "input_tokens");
        Integer output = nullableInt(row, "output_tokens");
        Integer total = nullableInt(row, "total_tokens");
        Integer cacheRead = nullableInt(row, "cache_read_tokens");
        Integer cacheWrite = nullableInt(row, "cache_write_tokens");
        Integer reasoning = nullableInt(row, "reasoning_tokens");
        String source = enumText(row, "usage_source", false,
                Set.of("NOT_EXPOSED", "PROVIDER_REPORTED", "DERIVED"));
        String coverage = enumText(row, "usage_coverage", false,
                Set.of("NONE", "PARTIAL", "COMPLETE"));
        Integer toolCalls = nullableInt(row, "tool_call_count");
        String cardinality = enumText(row, "response_cardinality", true,
                Set.of("ZERO", "ONE", "MULTIPLE", "INVALID"));
        String finishReason = boundedText(row, "finish_reason", true, 1, 64);
        String safeRequestId = boundedText(row, "safe_request_id", true, 1, 128);
        String summaryHash = boundedText(row, "structural_summary_hash", true, 64, 64);
        String diagnostic = boundedText(row, "diagnostic_ref", true, 1, 128);
        Instant created = requiredT9(row, "created_at");
        Instant updated = requiredT9(row, "updated_at");

        boolean startedLifecycle = "STARTED".equals(lifecycle);
        if (startedLifecycle != (ended == null && latency == null && outcome == null)) invalid();
        if(startedLifecycle&&(errorCategory!=null||toolCalls!=null||cardinality!=null
                ||finishReason!=null||safeRequestId!=null||summaryHash!=null||diagnostic!=null
                ||!"NOT_EXPOSED".equals(source)||!"NONE".equals(coverage))) invalid();
        if (!startedLifecycle && (ended == null || latency == null || outcome == null)) invalid();
        if (ended != null && ended.isBefore(started)) invalid();
        if (!created.equals(started)) invalid();
        if (startedLifecycle ? !updated.equals(started) : !updated.equals(ended)) invalid();
        validateUsage(source, coverage, input, output, total, cacheRead, cacheWrite, reasoning);
        if (summaryHash != null && !summaryHash.matches("[0-9a-f]{64}")) invalid();
        return new ValidatedCall(id, correlation, provider, model, operation, session, task, run,
                generation, lifecycle, started, ended, latency, outcome, errorCategory, retry,
                input, output, total, cacheRead, cacheWrite, reasoning, source, coverage,
                toolCalls, cardinality, finishReason, safeRequestId, summaryHash, diagnostic,
                created, updated);
    }

    private static void validateUsage(String source, String coverage, Integer input,
            Integer output, Integer total, Integer cacheRead, Integer cacheWrite,
            Integer reasoning) throws TelemetryIntegrityException {
        boolean coreAny = input != null || output != null || total != null;
        boolean coreAll = input != null && output != null && total != null;
        boolean allNull = !coreAny && cacheRead == null && cacheWrite == null && reasoning == null;
        boolean valid = "NOT_EXPOSED".equals(source) && "NONE".equals(coverage) && allNull
                || "PROVIDER_REPORTED".equals(source) && "PARTIAL".equals(coverage)
                    && coreAny && !coreAll
                || "PROVIDER_REPORTED".equals(source) && "COMPLETE".equals(coverage) && coreAll
                || "DERIVED".equals(source) && "COMPLETE".equals(coverage) && coreAll
                    && (long) total == (long) input + output;
        if (!valid) invalid();
    }

    private static String storage(ResultSet row, String column) throws SQLException {
        String value = row.getString("type_" + column);
        if (value == null) invalid();
        return value;
    }

    private static String boundedText(ResultSet row, String column, boolean nullable,
            int min, int max) throws SQLException {
        String type = storage(row, column);
        if ("null".equals(type)) {
            if (nullable) return null;
            invalid();
        }
        if (!"text".equals(type)) invalid();
        String value = row.getString(column);
        if (value == null || value.length() < min || value.length() > max) invalid();
        return value;
    }

    private static String enumText(ResultSet row, String column, boolean nullable,
            Set<String> values) throws SQLException {
        String value = boundedText(row, column, nullable, 1, 128);
        if (value != null && !values.contains(value)) invalid();
        return value;
    }

    private static long requiredLong(ResultSet row, String column, long min, long max)
            throws SQLException {
        if (!"integer".equals(storage(row, column))) invalid();
        long value = row.getLong(column);
        if (value < min || value > max) invalid();
        return value;
    }

    private static int requiredInt(ResultSet row, String column) throws SQLException {
        return Math.toIntExact(requiredLong(row, column, 0, MAX_INT));
    }

    private static Integer nullableInt(ResultSet row, String column) throws SQLException {
        String type = storage(row, column);
        if ("null".equals(type)) return null;
        if (!"integer".equals(type)) invalid();
        long value = row.getLong(column);
        if (value < 0 || value > MAX_INT) invalid();
        return (int) value;
    }

    private static Long nullableLong(ResultSet row,String column)throws SQLException{
        String type=storage(row,column);
        if("null".equals(type))return null;
        if(!"integer".equals(type))invalid();
        long value=row.getLong(column);
        if(value<0)invalid();
        return value;
    }

    private static Instant requiredT9(ResultSet row, String column) throws SQLException {
        String value = boundedText(row, column, false, 30, 30);
        return parseT9(value);
    }

    private static Instant nullableT9(ResultSet row, String column) throws SQLException {
        String value = boundedText(row, column, true, 30, 30);
        return value == null ? null : parseT9(value);
    }

    private static Instant parseT9(String value) throws TelemetryIntegrityException {
        try {
            Instant parsed = Instant.parse(value);
            if (!T9.format(parsed).equals(value)) invalid();
            return parsed;
        } catch (RuntimeException error) {
            throw new TelemetryIntegrityException("PROVIDER_TELEMETRY_STORAGE_INVALID");
        }
    }

    static Long addNullable(Long aggregate, Integer value)
            throws TelemetryIntegrityException {
        if (value == null) return aggregate;
        try {
            return Math.addExact(aggregate == null ? 0L : aggregate, value.longValue());
        } catch (ArithmeticException error) {
            throw new TelemetryIntegrityException("PROVIDER_TELEMETRY_AGGREGATE_OVERFLOW");
        }
    }

    private static void invalid() throws TelemetryIntegrityException {
        throw new TelemetryIntegrityException("PROVIDER_TELEMETRY_STORAGE_INVALID");
    }

    public record Scope(String column, String identity, String secondaryColumn,
            String secondaryIdentity, String provider, String model) {
        public Scope {
            if (!Set.of("session_id", "task_id", "run_id").contains(column)
                    || identity == null || identity.isBlank() || provider == null
                    || provider.isBlank()
                    || (secondaryColumn == null) != (secondaryIdentity == null)
                    || secondaryColumn != null
                            && (!Set.of("task_id", "run_id").contains(secondaryColumn)
                            || secondaryIdentity.isBlank() || secondaryColumn.equals(column))) {
                throw new IllegalArgumentException("invalid telemetry scope");
            }
        }
        public Scope(String column, String identity, String provider, String model) {
            this(column, identity, null, null, provider, model);
        }
        public static Scope session(String id, String provider, String model) {
            return new Scope("session_id", id, provider, model);
        }
        public static Scope task(String id, String provider, String model) {
            return new Scope("task_id", id, provider, model);
        }
        public static Scope run(String id, String provider, String model) {
            return new Scope("run_id", id, provider, model);
        }
        public static Scope taskRun(String taskId, String runId,
                String provider, String model) {
            return new Scope("task_id", taskId, "run_id", runId, provider, model);
        }
    }

    public record UsageAggregate(int callCount, Long inputTokens, Long outputTokens,
            Long totalTokens, String coverage) { }

    public record ValidatedCall(long telemetryId, String correlationId, String provider,
            String model, String operationKind, String sessionId, String taskId, String runId,
            String generationId, String lifecycle, Instant startedAt, Instant endedAt,
            Long latencyMs, String outcome, String errorCategory, int retryCount,
            Integer inputTokens, Integer outputTokens, Integer totalTokens,
            Integer cacheReadTokens, Integer cacheWriteTokens, Integer reasoningTokens,
            String usageSource, String usageCoverage, Integer toolCallCount,
            String responseCardinality, String finishReason, String safeRequestId,
            String structuralSummaryHash, String diagnosticRef, Instant createdAt,
            Instant updatedAt) { }

    public static final class TelemetryIntegrityException extends SQLException {
        public TelemetryIntegrityException(String diagnosticRef) { super(diagnosticRef); }
    }
}
