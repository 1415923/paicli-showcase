package com.paicli.state;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/** Protected recoverable instruction content with verified SHA-256 integrity. */
public final class ProtectedInstructionArtifact {
    private final String id;
    private final String contentHash;
    private final byte[] protectedPayload;
    private final String storageReference;
    private final String sanitizerVersion;
    private final String retentionClass;
    private final Instant createdAt;
    private final boolean integrityVerified;

    private ProtectedInstructionArtifact(String id, String contentHash, byte[] protectedPayload,
                                         String storageReference, String sanitizerVersion,
                                         String retentionClass, Instant createdAt,
                                         boolean integrityVerified) {
        this.id = require(id);
        this.contentHash = requireHash(contentHash);
        this.protectedPayload = protectedPayload == null ? null : protectedPayload.clone();
        this.storageReference = storageReference;
        this.sanitizerVersion = require(sanitizerVersion);
        this.retentionClass = require(retentionClass);
        this.createdAt = java.util.Objects.requireNonNull(createdAt);
        this.integrityVerified = integrityVerified;
        if ((this.protectedPayload == null) == (storageReference == null)) {
            throw new IllegalArgumentException("Exactly one protected payload form is required");
        }
    }

    public static ProtectedInstructionArtifact inline(String id, byte[] payload,
                                                       String sanitizerVersion,
                                                       String retentionClass, Instant createdAt) {
        byte[] copy = java.util.Objects.requireNonNull(payload).clone();
        return new ProtectedInstructionArtifact(id, sha256(copy), copy, null, sanitizerVersion,
                retentionClass, createdAt, true);
    }

    public static ProtectedInstructionArtifact inlineUtf8(String id, String payload,
                                                           String sanitizerVersion,
                                                           String retentionClass, Instant createdAt) {
        return inline(id, payload.getBytes(StandardCharsets.UTF_8), sanitizerVersion,
                retentionClass, createdAt);
    }

    public static ProtectedInstructionArtifact referenced(String id, String reference,
                                                           String expectedHash, byte[] resolvedPayload,
                                                           String sanitizerVersion,
                                                           String retentionClass, Instant createdAt) {
        boolean verified = resolvedPayload != null && expectedHash.equals(sha256(resolvedPayload));
        return new ProtectedInstructionArtifact(id, expectedHash, null, require(reference),
                sanitizerVersion, retentionClass, createdAt, verified);
    }

    public boolean verifyIntegrity() {
        return integrityVerified && (protectedPayload == null || contentHash.equals(sha256(protectedPayload)));
    }

    public String id() { return id; }
    public String contentHash() { return contentHash; }
    public byte[] protectedPayload() { return protectedPayload == null ? null : protectedPayload.clone(); }
    public String storageReference() { return storageReference; }
    public String sanitizerVersion() { return sanitizerVersion; }
    public String retentionClass() { return retentionClass; }
    public Instant createdAt() { return createdAt; }

    @Override
    public String toString() {
        return "ProtectedInstructionArtifact[id=" + id + ", contentHash=" + contentHash
                + ", payload=REDACTED, storageReference="
                + (storageReference == null ? "none" : "REDACTED") + "]";
    }

    static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String require(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Value is required");
        return value;
    }

    private static String requireHash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Lowercase SHA-256 is required");
        }
        return value;
    }
}
