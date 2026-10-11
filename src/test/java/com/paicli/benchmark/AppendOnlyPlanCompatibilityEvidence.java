package com.paicli.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.runtime.PlanCompatibilityObserver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/** Benchmark evidence sink that persists only schema paths, counts and typed reason codes. */
final class AppendOnlyPlanCompatibilityEvidence implements PlanCompatibilityObserver {
    static final String SCHEMA_VERSION = "plan-compatibility-ledger-v1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_$.:/\\[\\]-]+");

    private final Path path;
    private final String logicalRunId;
    private final String runtime;
    private final List<Fact> facts = new ArrayList<>();
    private int eventOrdinal;

    AppendOnlyPlanCompatibilityEvidence(Path path, Path evidenceRoot,
            String logicalRunId, String runtime) throws Exception {
        this.path = Objects.requireNonNull(path).toAbsolutePath().normalize();
        Path root = Objects.requireNonNull(evidenceRoot).toAbsolutePath().normalize();
        if (!this.path.startsWith(root) || this.path.equals(root)) {
            throw new IllegalArgumentException("Plan compatibility ledger must be under evidence root");
        }
        this.logicalRunId = safeIdentity(logicalRunId);
        this.runtime = safeIdentity(runtime);
        if (Files.exists(this.path)) {
            for (String line : Files.readAllLines(this.path, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    JSON.readTree(line);
                    eventOrdinal++;
                }
            }
        }
    }

    @Override
    public synchronized void observed(Fact fact) {
        Objects.requireNonNull(fact);
        validate(fact);
        try {
            Files.createDirectories(path.getParent());
            Map<String, Object> row = map(fact);
            row.put("schemaVersion", SCHEMA_VERSION);
            row.put("logicalRunId", logicalRunId);
            row.put("runtime", runtime);
            row.put("eventOrdinal", ++eventOrdinal);
            Files.writeString(path, JSON.writeValueAsString(row) + System.lineSeparator(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            facts.add(fact);
        } catch (Exception error) {
            throw new IllegalStateException("Plan compatibility evidence append failed", error);
        }
    }

    synchronized List<Fact> facts() {
        return List.copyOf(facts);
    }

    static Map<String, Object> map(Fact fact) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("planParseStatus", fact.planParseStatus().name());
        value.put("planSchemaVersion", fact.schemaVersion());
        value.put("unknownFieldCount", fact.unknownFieldCount());
        value.put("missingRequiredFieldCount", fact.missingRequiredFieldCount());
        value.put("normalizationCount", fact.normalizationCount());
        value.put("repairAttemptCount", fact.repairAttemptCount());
        value.put("terminalReason", fact.terminalReason());
        value.put("requiredFields", fact.requiredFields());
        value.put("missingRequiredFields", fact.missingRequiredFields());
        value.put("unknownFields", fact.unknownFields());
        value.put("validationErrors", fact.validationErrors());
        return value;
    }

    private static void validate(Fact fact) {
        safeToken(fact.schemaVersion());
        safeToken(fact.terminalReason());
        fact.requiredFields().forEach(AppendOnlyPlanCompatibilityEvidence::safeToken);
        fact.missingRequiredFields().forEach(AppendOnlyPlanCompatibilityEvidence::safeToken);
        fact.unknownFields().forEach(AppendOnlyPlanCompatibilityEvidence::safeToken);
        fact.validationErrors().forEach(AppendOnlyPlanCompatibilityEvidence::safeToken);
    }

    private static String safeIdentity(String value) {
        if (value == null || value.isBlank() || value.length() > 256
                || !SAFE.matcher(value).matches()) {
            throw new IllegalArgumentException("unsafe Plan compatibility evidence identity");
        }
        return value;
    }

    private static void safeToken(String value) {
        if (value == null || value.isBlank() || value.length() > 512
                || !SAFE.matcher(value).matches()) {
            throw new IllegalArgumentException("unsafe Plan compatibility evidence token");
        }
    }
}
