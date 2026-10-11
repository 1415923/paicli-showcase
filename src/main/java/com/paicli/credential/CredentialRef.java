package com.paicli.credential;

import java.util.Objects;

/** Stable, non-secret operating-system credential identity. */
public record CredentialRef(String value) {
    public CredentialRef {
        Objects.requireNonNull(value, "value");
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._/-]{2,191}")) {
            throw new IllegalArgumentException("invalid credential reference");
        }
    }
}
