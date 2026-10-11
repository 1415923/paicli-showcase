package com.paicli.state;

import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.identity.LegacyId;

import java.time.Instant;
import java.util.Objects;

/** Explicit, typed provenance for a legacy identity. It never enters CanonicalId parsing. */
public record LegacyAlias(
        LegacyId legacyId,
        CanonicalId canonicalTarget,
        Classification classification,
        MappingStatus mappingStatus,
        String sourceType,
        String sourceReference,
        String provenance,
        ContinuationStatus continuationStatus,
        int migrationVersion,
        Instant createdAt) {

    public LegacyAlias {
        Objects.requireNonNull(legacyId);
        Objects.requireNonNull(classification);
        Objects.requireNonNull(mappingStatus);
        Objects.requireNonNull(continuationStatus);
        Objects.requireNonNull(createdAt);
        requireText(sourceType, "sourceType");
        requireText(sourceReference, "sourceReference");
        requireText(provenance, "provenance");
        if (migrationVersion < 19) {
            throw new IllegalArgumentException("Legacy alias migration version must be at least 19");
        }
        if ((mappingStatus == MappingStatus.MAPPED) != (canonicalTarget != null)) {
            throw new IllegalArgumentException("Mapped legacy aliases require exactly one canonical target");
        }
        if (canonicalTarget != null && canonicalTarget.family() != legacyId.declaredFamily()) {
            throw new IllegalArgumentException("Legacy alias target family mismatch");
        }
    }

    public enum Classification {
        LEGACY_UNCERTAIN, CONTINUATION_UNAVAILABLE, INVALID_LEGACY_RECORD
    }

    public enum MappingStatus { UNMAPPED, MAPPED, AMBIGUOUS, INVALID }

    public enum ContinuationStatus { ELIGIBLE, CONTINUATION_UNAVAILABLE, NOT_ASSESSED }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    public static LegacyAlias unmappedTask(String legacyValue, String sourceReference,
                                           String provenance, boolean checkpointPresent,
                                           Instant createdAt) {
        return new LegacyAlias(new LegacyId(EntityFamily.TASK, legacyValue), null,
                checkpointPresent ? Classification.LEGACY_UNCERTAIN
                        : Classification.CONTINUATION_UNAVAILABLE,
                MappingStatus.UNMAPPED, "V1_TASK", sourceReference, provenance,
                checkpointPresent ? ContinuationStatus.ELIGIBLE
                        : ContinuationStatus.CONTINUATION_UNAVAILABLE,
                19, createdAt);
    }
}
