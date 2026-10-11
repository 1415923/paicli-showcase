package com.paicli.provider;

import java.util.List;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** R1A non-runtime response-integrity foundation. It deliberately retains no raw provider body. */
public record ProviderResponseEnvelope(ToolCallCardinality cardinality, List<String> safeCallIds,
                                       ProviderResponseDiagnostic diagnostic) {
    public static final int MAX_TOOL_CALLS_PER_ASSISTANT_TURN = 8;
    private static final int MAX_DIAGNOSTIC_IDS = MAX_TOOL_CALLS_PER_ASSISTANT_TURN;
    private static final int MAX_SAFE_ID_LENGTH = 128;
    public ProviderResponseEnvelope {
        safeCallIds = List.copyOf(Objects.requireNonNull(safeCallIds));
        if (safeCallIds.size() > MAX_DIAGNOSTIC_IDS || safeCallIds.stream().anyMatch(
                id -> id == null || id.length() > MAX_SAFE_ID_LENGTH
                        || id.chars().anyMatch(Character::isWhitespace)))
            throw new IllegalArgumentException("Unsafe tool call identity");
    }
    public static ProviderResponseEnvelope inspect(List<String> identities, boolean malformed) {
        List<String> ids = identities == null ? List.of() : identities;
        boolean missing = false;
        boolean invalidIdentity = false;
        boolean duplicate = false;
        Set<String> observed = new HashSet<>();
        for (String id : ids) {
            if (id == null || id.isBlank()) missing = true;
            else {
                if (id.length() > MAX_SAFE_ID_LENGTH || id.chars().anyMatch(Character::isWhitespace)) {
                    invalidIdentity = true;
                }
                if (!observed.add(id)) duplicate = true;
            }
        }
        List<String> safeIds = ids.stream().filter(Objects::nonNull).limit(MAX_DIAGNOSTIC_IDS)
                .map(ProviderResponseEnvelope::safeIdentity).toList();
        if (malformed || duplicate || missing || invalidIdentity) {
            return new ProviderResponseEnvelope(ToolCallCardinality.INVALID, safeIds,
                    ProviderResponseDiagnostic.invalid(ids.size(), malformed || invalidIdentity,
                            duplicate, missing));
        }
        ToolCallCardinality cardinality = ids.isEmpty() ? ToolCallCardinality.ZERO
                : ids.size() == 1 ? ToolCallCardinality.ONE : ToolCallCardinality.MULTIPLE;
        return new ProviderResponseEnvelope(cardinality, safeIds,
                ids.size() > MAX_TOOL_CALLS_PER_ASSISTANT_TURN
                        ? ProviderResponseDiagnostic.batchLimitExceeded(ids.size())
                        : ProviderResponseDiagnostic.none(ids.size()));
    }
    public enum ToolCallCardinality { ZERO, ONE, MULTIPLE, INVALID }
    public record ProviderResponseDiagnostic(String code, int callCount, boolean malformed, boolean duplicate, boolean missingIdentity) {
        static ProviderResponseDiagnostic none(int count) { return new ProviderResponseDiagnostic(null, count, false, false, false); }
        static ProviderResponseDiagnostic batchLimitExceeded(int count) { return new ProviderResponseDiagnostic("TOOL_CALL_BATCH_LIMIT_EXCEEDED", count, false, false, false); }
        static ProviderResponseDiagnostic invalid(int count, boolean malformed, boolean duplicate, boolean missing) { return new ProviderResponseDiagnostic("INVALID_TOOL_CALL_RESPONSE", count, malformed, duplicate, missing); }
    }

    private static String safeIdentity(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return "redacted-sha256-" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
