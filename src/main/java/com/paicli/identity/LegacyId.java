package com.paicli.identity;

/** Explicit legacy alias only; it never enters CanonicalId parsing or typed persistence. */
public record LegacyId(EntityFamily declaredFamily, String value) {
    public LegacyId { if (declaredFamily == null || value == null || value.isBlank()) throw new IllegalArgumentException("Explicit legacy ID required"); }
}
