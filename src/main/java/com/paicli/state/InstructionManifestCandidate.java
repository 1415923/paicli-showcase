package com.paicli.state;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Non-authoritative build-time manifest. It cannot be assigned to a Run or Generation. */
public record InstructionManifestCandidate(int schemaVersion, String resolutionPolicyVersion,
                                           List<Entry> entries) {
    public InstructionManifestCandidate {
        if (schemaVersion < 1 || resolutionPolicyVersion == null || resolutionPolicyVersion.isBlank()) throw new IllegalArgumentException("Manifest metadata required");
        entries = List.copyOf(entries);
    }
    public enum Status { DISCOVERED, PARSED, APPLIED, IGNORED, FAILED }
    public record Entry(String id, String sourceType, String sourcePath, String scope, Status status,
                        Integer appliedOrder, String contentHash, String artifactId, String parseResult,
                        String exclusionReason, String failureReason) { }
    public void validateForCommit() {
        int next = 0;
        Set<String> entryIds = new HashSet<>();
        for (Entry entry : canonicalEntries()) {
            if (blank(entry.id()) || !entryIds.add(entry.id()) || blank(entry.sourceType())
                    || blank(entry.sourcePath()) || blank(entry.scope())) {
                throw new IllegalStateException("Instruction entry identity and source metadata must be unique and complete");
            }
            if (entry.status() == Status.DISCOVERED || entry.status() == Status.PARSED) throw new IllegalStateException("Applicable instruction entry is not terminal");
            if (entry.status() == Status.FAILED) throw new IllegalStateException("FAILED instruction entry blocks manifest commit");
            if (entry.status() == Status.IGNORED && (blank(entry.exclusionReason())
                    || entry.appliedOrder() != null || !blank(entry.artifactId())
                    || !blank(entry.contentHash()) || !blank(entry.failureReason()))) {
                throw new IllegalStateException("IGNORED entry requires only an exclusion reason");
            }
            if (entry.status() == Status.APPLIED) {
                if (entry.appliedOrder() == null || entry.appliedOrder() != next++
                        || blank(entry.artifactId()) || !hash(entry.contentHash())
                        || !blank(entry.exclusionReason()) || !blank(entry.failureReason())) {
                    throw new IllegalStateException("Invalid applied instruction entry");
                }
            }
        }
    }
    public String canonicalHash() {
        validateForCommit();
        StringBuilder canonical = new StringBuilder();
        append(canonical, Integer.toString(schemaVersion));
        append(canonical, resolutionPolicyVersion);
        for (Entry entry : canonicalEntries()) {
            append(canonical, entry.id()); append(canonical, entry.sourceType());
            append(canonical, entry.sourcePath()); append(canonical, entry.scope());
            append(canonical, entry.status().name()); append(canonical, safe(entry.appliedOrder()));
            append(canonical, entry.contentHash()); append(canonical, entry.artifactId());
            append(canonical, entry.parseResult()); append(canonical, entry.exclusionReason());
        }
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    List<Entry> canonicalEntries() {
        return entries.stream().sorted(Comparator
                .comparing((Entry e) -> e.status() == Status.APPLIED ? 0 : 1)
                .thenComparing(e -> e.appliedOrder() == null ? Integer.MAX_VALUE : e.appliedOrder())
                .thenComparing(Entry::id, Comparator.nullsFirst(String::compareTo))).toList();
    }
    private static void append(StringBuilder target, String value) {
        String safe = safe(value);
        target.append(safe.length()).append(':').append(safe).append('\n');
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static String safe(Object value) { return value == null ? "" : value.toString(); }
}
