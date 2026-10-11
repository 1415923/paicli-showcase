package com.paicli.credential;

import java.util.Optional;

/** Fail-closed backend for platforms without an approved OS credential store. */
public final class UnsupportedCredentialStore implements CredentialStore {
    public static final String CODE = "CREDENTIAL_STORE_UNSUPPORTED";
    @Override public void put(CredentialRef reference, char[] secret) { throw new CredentialStoreException(CODE); }
    @Override public Optional<char[]> get(CredentialRef reference) { return Optional.empty(); }
    @Override public boolean delete(CredentialRef reference) { throw new CredentialStoreException(CODE); }
    @Override public Status status(CredentialRef reference) { return new Status(State.UNSUPPORTED, "unsupported"); }
}
