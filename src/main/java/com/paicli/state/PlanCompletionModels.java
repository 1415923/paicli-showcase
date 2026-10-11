package com.paicli.state;

import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable composite R1C Evidence and completion facts. */
public final class PlanCompletionModels {
    private PlanCompletionModels() { }

    public enum EvidenceKind {
        READ_EVIDENCE, TOOL_CALL, APPLIED_WRITE, SNAPSHOT, VERIFICATION_RESULT,
        USER_APPROVAL, GENERATED_ARTIFACT, MANUAL_CONFIRMATION
    }
    public enum ProducerKind { OCCURRENCE, SNAPSHOT, VERIFICATION, APPROVAL }
    public enum CompletionRuleType {
        ALL_REQUIRED_EVIDENCE, ANY_REQUIRED_EVIDENCE, READ_CONFIRMED,
        WRITE_APPLIED, SNAPSHOT_CREATED, VERIFICATION_PASSED,
        ARTIFACT_RECORDED, USER_CONFIRMED, CHILD_STEPS_COMPLETED
    }
    public enum EffectClass { READ, WRITE, VERIFY, ARTIFACT, GENERAL }
    public enum CompletionResult {
        COMPLETE, INCOMPLETE, FAILED_REQUIREMENT, EVIDENCE_MISSING,
        EVIDENCE_INTEGRITY_FAILURE, VERIFICATION_REQUIRED, AUTHORITY_CONFLICT,
        LEGACY_COMPLETION_PROFILE_REQUIRED
    }
    public enum RequirementType { STEP_SUCCEEDED, EVIDENCE_KIND_PRESENT, VERIFICATION_PASSED }

    private static final Set<EvidenceKind> REACHABLE_EVIDENCE = Set.of(
            EvidenceKind.TOOL_CALL, EvidenceKind.SNAPSHOT,
            EvidenceKind.VERIFICATION_RESULT);

    public static void validateCompletionProfile(CompletionRuleType rule,
            List<EvidenceKind> evidenceKinds, EffectClass effectClass) {
        Objects.requireNonNull(rule, "completionRule");
        Objects.requireNonNull(effectClass, "effectClass");
        List<EvidenceKind> kinds = List.copyOf(evidenceKinds);
        if (kinds.isEmpty() || kinds.size() != kinds.stream().distinct().count()
                || !REACHABLE_EVIDENCE.containsAll(kinds)) {
            throw new IllegalArgumentException("Completion Evidence must be unique and production-reachable");
        }
        boolean valid = switch (rule) {
            case ALL_REQUIRED_EVIDENCE, ANY_REQUIRED_EVIDENCE -> true;
            case SNAPSHOT_CREATED -> kinds.equals(List.of(EvidenceKind.SNAPSHOT));
            case VERIFICATION_PASSED -> kinds.equals(List.of(EvidenceKind.VERIFICATION_RESULT));
            default -> false;
        };
        if (!valid) throw new IllegalArgumentException("Completion Rule is not production-reachable");
    }

    public record Evidence(CanonicalId taskId, CanonicalId planRevisionId,
            CanonicalId stepRevisionId, CanonicalId runId, int attemptNumber,
            EvidenceKind kind, ProducerKind producerKind, String producerIdentity,
            String observedStatus, String boundedSummary, String protectedReference,
            String canonicalHash, String provenance, Instant createdAt) {
        public Evidence {
            require(taskId, EntityFamily.TASK); require(planRevisionId, EntityFamily.PLAN);
            require(stepRevisionId, EntityFamily.PLAN_STEP); require(runId, EntityFamily.RUN);
            Objects.requireNonNull(kind); Objects.requireNonNull(producerKind);
            Objects.requireNonNull(createdAt);
            if (attemptNumber < 1 || blank(producerIdentity) || blank(provenance)
                    || !java.util.Set.of("SUCCEEDED","FAILED","UNKNOWN").contains(observedStatus)
                    || tooLong(boundedSummary, 1024) || tooLong(protectedReference, 512)
                    || !hash(canonicalHash)) throw new IllegalArgumentException("Invalid Plan Evidence");
        }
        public String material() {
            return String.join("\n", taskId.value(), planRevisionId.value(), stepRevisionId.value(),
                    runId.value(), Integer.toString(attemptNumber), kind.name(), producerKind.name(),
                    producerIdentity, observedStatus, nullToEmpty(boundedSummary),
                    nullToEmpty(protectedReference), provenance, createdAt.toString());
        }
        public boolean integrityValid() { return canonicalHash.equals(sha256(material())); }
    }

    public static String evidenceHash(CanonicalId taskId, CanonicalId revisionId,
            CanonicalId stepId, CanonicalId runId, int attemptNumber, EvidenceKind kind,
            ProducerKind producerKind, String producerIdentity, String observedStatus,
            String summary, String reference, String provenance, Instant at) {
        return sha256(String.join("\n", taskId.value(), revisionId.value(), stepId.value(),
                runId.value(), Integer.toString(attemptNumber), kind.name(), producerKind.name(),
                producerIdentity, observedStatus, nullToEmpty(summary), nullToEmpty(reference),
                provenance, at.toString()));
    }

    public static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException("SHA-256 unavailable", error); }
    }
    private static void require(CanonicalId id, EntityFamily family) {
        if (id == null || id.family() != family) throw new IllegalArgumentException("Expected " + family);
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static boolean tooLong(String value, int limit) { return value != null && value.length() > limit; }
    private static boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static String nullToEmpty(String value) { return value == null ? "" : value; }
}
