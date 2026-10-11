package com.paicli.credential;

import java.util.Optional;

/** Fail-closed backend when an approved OS store exists but cannot be initialized. */
final class UnavailableCredentialStore implements CredentialStore {
    static final String CODE = "CREDENTIAL_STORE_UNAVAILABLE";
    @Override public void put(CredentialRef reference, char[] secret) { throw new CredentialStoreException(CODE); }
    @Override public Optional<char[]> get(CredentialRef reference) { throw new CredentialStoreException(CODE); }
    @Override public boolean delete(CredentialRef reference) { throw new CredentialStoreException(CODE); }
    @Override public Status status(CredentialRef reference) { return new Status(State.ERROR,"windows-credential-manager"); }
}
