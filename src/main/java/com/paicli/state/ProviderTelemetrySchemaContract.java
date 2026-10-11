package com.paicli.state;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Canonical V25 Provider telemetry schema shared by migration and attestation. */
final class ProviderTelemetrySchemaContract {
    static final String CONTRACT_VERSION = "V25_PROVIDER_TELEMETRY_CONTRACT_V1";
    static final String TABLE_NAME = "provider_call_telemetry";
    static final boolean EXPECTED_STRICT = true;

    private static final String CREATE_TABLE_SQL = """
            CREATE TABLE provider_call_telemetry (
                telemetry_id INTEGER PRIMARY KEY AUTOINCREMENT,
                correlation_id TEXT NOT NULL UNIQUE CHECK(length(correlation_id) BETWEEN 16 AND 64),
                provider TEXT NOT NULL CHECK(length(provider) BETWEEN 1 AND 64),
                model TEXT CHECK(model IS NULL OR length(model) BETWEEN 1 AND 128),
                operation_kind TEXT NOT NULL CHECK(operation_kind IN ('CHAT','PLAN','HEALTH','MODEL_DISCOVERY')),
                session_id TEXT CHECK(session_id IS NULL OR length(session_id) BETWEEN 1 AND 128),
                task_id TEXT CHECK(task_id IS NULL OR length(task_id) BETWEEN 1 AND 128),
                run_id TEXT CHECK(run_id IS NULL OR length(run_id) BETWEEN 1 AND 128),
                generation_id TEXT CHECK(generation_id IS NULL OR length(generation_id) BETWEEN 1 AND 128),
                lifecycle TEXT NOT NULL CHECK(lifecycle IN ('STARTED','SUCCEEDED','FAILED','CANCELLED')),
                started_at TEXT NOT NULL CHECK(typeof(started_at)='text'
                    AND length(started_at)=30 AND substr(started_at,30,1)='Z'),
                ended_at TEXT CHECK(ended_at IS NULL OR (typeof(ended_at)='text'
                    AND length(ended_at)=30 AND substr(ended_at,30,1)='Z')),
                latency_ms INTEGER CHECK(latency_ms IS NULL OR
                    (typeof(latency_ms)='integer' AND latency_ms>=0)),
                outcome TEXT CHECK(outcome IS NULL OR length(outcome) BETWEEN 1 AND 64),
                error_category TEXT CHECK(error_category IS NULL OR length(error_category) BETWEEN 1 AND 64),
                retry_count INTEGER NOT NULL DEFAULT 0 CHECK(typeof(retry_count)='integer'
                    AND retry_count BETWEEN 0 AND 2147483647),
                input_tokens INTEGER CHECK(input_tokens IS NULL OR
                    (typeof(input_tokens)='integer' AND input_tokens BETWEEN 0 AND 2147483647)),
                output_tokens INTEGER CHECK(output_tokens IS NULL OR
                    (typeof(output_tokens)='integer' AND output_tokens BETWEEN 0 AND 2147483647)),
                total_tokens INTEGER CHECK(total_tokens IS NULL OR
                    (typeof(total_tokens)='integer' AND total_tokens BETWEEN 0 AND 2147483647)),
                cache_read_tokens INTEGER CHECK(cache_read_tokens IS NULL OR
                    (typeof(cache_read_tokens)='integer' AND cache_read_tokens BETWEEN 0 AND 2147483647)),
                cache_write_tokens INTEGER CHECK(cache_write_tokens IS NULL OR
                    (typeof(cache_write_tokens)='integer' AND cache_write_tokens BETWEEN 0 AND 2147483647)),
                reasoning_tokens INTEGER CHECK(reasoning_tokens IS NULL OR
                    (typeof(reasoning_tokens)='integer' AND reasoning_tokens BETWEEN 0 AND 2147483647)),
                usage_source TEXT NOT NULL CHECK(usage_source IN ('NOT_EXPOSED','PROVIDER_REPORTED','DERIVED')),
                usage_coverage TEXT NOT NULL CHECK(usage_coverage IN ('NONE','PARTIAL','COMPLETE')),
                tool_call_count INTEGER CHECK(tool_call_count IS NULL OR
                    (typeof(tool_call_count)='integer' AND tool_call_count BETWEEN 0 AND 2147483647)),
                response_cardinality TEXT CHECK(response_cardinality IS NULL OR
                    response_cardinality IN ('ZERO','ONE','MULTIPLE','INVALID')),
                finish_reason TEXT CHECK(finish_reason IS NULL OR length(finish_reason) BETWEEN 1 AND 64),
                safe_request_id TEXT CHECK(safe_request_id IS NULL OR length(safe_request_id) BETWEEN 1 AND 128),
                structural_summary_hash TEXT CHECK(structural_summary_hash IS NULL OR
                    (length(structural_summary_hash)=64 AND structural_summary_hash NOT GLOB '*[^0-9a-f]*')),
                diagnostic_ref TEXT CHECK(diagnostic_ref IS NULL OR length(diagnostic_ref) BETWEEN 1 AND 128),
                created_at TEXT NOT NULL CHECK(typeof(created_at)='text'
                    AND length(created_at)=30 AND substr(created_at,30,1)='Z'),
                updated_at TEXT NOT NULL CHECK(typeof(updated_at)='text'
                    AND length(updated_at)=30 AND substr(updated_at,30,1)='Z'),
                CHECK(julianday(started_at) IS NOT NULL),
                CHECK(ended_at IS NULL OR (julianday(ended_at) IS NOT NULL
                    AND julianday(ended_at)>=julianday(started_at))),
                CHECK((lifecycle='STARTED' AND ended_at IS NULL AND latency_ms IS NULL
                        AND outcome IS NULL AND error_category IS NULL
                        AND tool_call_count IS NULL AND response_cardinality IS NULL
                        AND finish_reason IS NULL AND safe_request_id IS NULL
                        AND structural_summary_hash IS NULL AND diagnostic_ref IS NULL
                        AND usage_source='NOT_EXPOSED' AND usage_coverage='NONE'
                        AND updated_at=started_at)
                   OR (lifecycle<>'STARTED' AND ended_at IS NOT NULL AND latency_ms IS NOT NULL
                        AND outcome IS NOT NULL AND updated_at=ended_at)),
                CHECK(created_at=started_at),
                CHECK((usage_source='NOT_EXPOSED' AND usage_coverage='NONE'
                        AND input_tokens IS NULL AND output_tokens IS NULL
                        AND total_tokens IS NULL AND cache_read_tokens IS NULL
                        AND cache_write_tokens IS NULL AND reasoning_tokens IS NULL)
                   OR (usage_source='PROVIDER_REPORTED' AND usage_coverage='PARTIAL'
                        AND (input_tokens IS NOT NULL OR output_tokens IS NOT NULL
                             OR total_tokens IS NOT NULL)
                        AND NOT (input_tokens IS NOT NULL AND output_tokens IS NOT NULL
                                 AND total_tokens IS NOT NULL))
                   OR (usage_source='PROVIDER_REPORTED' AND usage_coverage='COMPLETE'
                        AND input_tokens IS NOT NULL AND output_tokens IS NOT NULL
                        AND total_tokens IS NOT NULL)
                   OR (usage_source='DERIVED' AND usage_coverage='COMPLETE'
                        AND input_tokens IS NOT NULL AND output_tokens IS NOT NULL
                        AND total_tokens IS NOT NULL AND total_tokens=input_tokens+output_tokens))
            ) STRICT
            """;

    private static final Set<String> EXPECTED_COLUMNS = Set.of(
            "telemetry_id", "correlation_id", "provider", "model", "operation_kind",
            "session_id", "task_id", "run_id", "generation_id", "lifecycle",
            "started_at", "ended_at", "latency_ms", "outcome", "error_category",
            "retry_count", "input_tokens", "output_tokens", "total_tokens",
            "cache_read_tokens", "cache_write_tokens", "reasoning_tokens", "usage_source",
            "usage_coverage", "tool_call_count", "response_cardinality", "finish_reason",
            "safe_request_id", "structural_summary_hash", "diagnostic_ref", "created_at",
            "updated_at");

    private static final Map<String, String> INDEX_SQL = indexes();

    private ProviderTelemetrySchemaContract() { }

    static List<String> statements() {
        List<String> result = new ArrayList<>();
        result.add(CREATE_TABLE_SQL);
        result.addAll(INDEX_SQL.values());
        return List.copyOf(result);
    }

    static String createTableSql() { return CREATE_TABLE_SQL; }
    static Set<String> expectedColumns() { return EXPECTED_COLUMNS; }
    static Set<String> expectedIndexes() { return INDEX_SQL.keySet(); }
    static Map<String, String> indexSql() { return INDEX_SQL; }
    static String normalizedTableDdl() { return normalize(CREATE_TABLE_SQL); }
    static Map<String, String> normalizedIndexDdl() {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        INDEX_SQL.forEach((name, sql) -> result.put(name, normalize(sql)));
        return java.util.Collections.unmodifiableMap(result);
    }
    static String tableFingerprint() { return fingerprint(CREATE_TABLE_SQL); }

    static Map<String, String> indexFingerprints() {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        INDEX_SQL.forEach((name, sql) -> result.put(name, fingerprint(sql)));
        return Map.copyOf(result);
    }

    static String normalize(String sql) {
        Objects.requireNonNull(sql, "sql");
        List<String> tokens = new ArrayList<>();
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (Character.isWhitespace(current)) { index++; continue; }
            if (current == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-') {
                index += 2;
                while (index < sql.length() && sql.charAt(index) != '\n'
                        && sql.charAt(index) != '\r') index++;
                continue;
            }
            if (current == '/' && index + 1 < sql.length() && sql.charAt(index + 1) == '*') {
                int end = sql.indexOf("*/", index + 2);
                if (end < 0) throw new IllegalArgumentException("unterminated SQL comment");
                index = end + 2;
                continue;
            }
            if (current == '\'' || current == '"' || current == '`' || current == '[') {
                int start = index;
                char close = current == '[' ? ']' : current;
                index++;
                boolean closed = false;
                while (index < sql.length()) {
                    char value = sql.charAt(index++);
                    if (value != close) continue;
                    if (close != ']' && index < sql.length() && sql.charAt(index) == close) {
                        index++;
                        continue;
                    }
                    closed = true;
                    break;
                }
                if (!closed) throw new IllegalArgumentException("unterminated SQL quoted token");
                tokens.add(sql.substring(start, index));
                continue;
            }
            if (Character.isLetterOrDigit(current) || current == '_' || current == '$') {
                int start = index++;
                while (index < sql.length()) {
                    char value = sql.charAt(index);
                    if (!Character.isLetterOrDigit(value) && value != '_' && value != '$') break;
                    index++;
                }
                tokens.add(sql.substring(start, index).toUpperCase(Locale.ROOT));
                continue;
            }
            String pair = index + 1 < sql.length() ? sql.substring(index, index + 2) : "";
            if (Set.of("<=", ">=", "<>", "!=", "==", "||", "->").contains(pair)) {
                if ("->".equals(pair) && index + 2 < sql.length() && sql.charAt(index + 2) == '>') {
                    tokens.add("->>"); index += 3;
                } else {
                    tokens.add(pair); index += 2;
                }
            } else {
                tokens.add(Character.toString(current));
                index++;
            }
        }
        while (!tokens.isEmpty() && ";".equals(tokens.getLast())) tokens.removeLast();
        return String.join(" ", tokens);
    }

    static String fingerprint(String sql) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalize(sql).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static Map<String, String> indexes() {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        result.put("idx_v25_provider_call_task_run_started",
                "CREATE INDEX idx_v25_provider_call_task_run_started "
                        + "ON provider_call_telemetry(task_id,run_id,started_at)");
        result.put("idx_v25_provider_call_session_started",
                "CREATE INDEX idx_v25_provider_call_session_started "
                        + "ON provider_call_telemetry(session_id,started_at)");
        result.put("idx_v25_provider_call_outcome_started",
                "CREATE INDEX idx_v25_provider_call_outcome_started "
                        + "ON provider_call_telemetry(outcome,started_at)");
        result.put("idx_v25_provider_call_provider_model_started",
                "CREATE INDEX idx_v25_provider_call_provider_model_started "
                        + "ON provider_call_telemetry(provider,model,started_at)");
        return java.util.Collections.unmodifiableMap(result);
    }
}
