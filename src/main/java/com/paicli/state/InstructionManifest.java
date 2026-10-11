package com.paicli.state;

import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;

import java.time.Instant;
import java.util.List;

/** Immutable committed instruction authority. Candidates are intentionally a different type. */
public record InstructionManifest(String id, CanonicalId taskId, int schemaVersion,
                                  String resolutionPolicyVersion, String canonicalHash,
                                  Instant createdAt, List<InstructionEntry> entries) {
    public InstructionManifest {
        if (id == null || id.isBlank() || taskId == null || taskId.family() != EntityFamily.TASK
                || schemaVersion < 1 || resolutionPolicyVersion == null
                || !canonicalHash.matches("[0-9a-f]{64}") || createdAt == null) {
            throw new IllegalArgumentException("Invalid committed instruction manifest");
        }
        entries = List.copyOf(entries);
    }

    public record InstructionEntry(String id, String sourceType, String sourcePath, String scope,
                                   InstructionManifestCandidate.Status status, Integer appliedOrder,
                                   String contentHash, String artifactId, String parseResult,
                                   String exclusionReason, Instant observedAt) { }
}
