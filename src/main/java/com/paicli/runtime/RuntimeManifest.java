package com.paicli.runtime;

/** Stable runtime input hashes persisted on a coding run. */
public record RuntimeManifest(
        String skillsSha256,
        String hooksSha256
) {
    public static RuntimeManifest disabled() {
        return new RuntimeManifest(null, null);
    }
}
