package com.paicli.credential;

import java.util.Optional;

/** Provider-neutral secret storage. Callers must clear returned mutable values. */
public interface CredentialStore {
    enum State { PRESENT, MISSING, UNSUPPORTED, ERROR }
    record Status(State state, String backend) {
        public Status {
            if (state == null || backend == null || backend.isBlank())
                throw new IllegalArgumentException("invalid credential status");
        }
    }
    void put(CredentialRef reference, char[] secret);
    Optional<char[]> get(CredentialRef reference);
    boolean delete(CredentialRef reference);
    Status status(CredentialRef reference);
}
