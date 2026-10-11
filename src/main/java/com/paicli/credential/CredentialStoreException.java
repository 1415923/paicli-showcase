package com.paicli.credential;

/** Secret-free typed credential failure. */
public final class CredentialStoreException extends IllegalStateException {
    public CredentialStoreException(String code) { super(code); }
}
