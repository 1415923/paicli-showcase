package com.paicli.credential;

import com.paicli.config.Config;

/** Acquires one short-lived provider credential for one HTTP request. */
@FunctionalInterface
public interface ProviderCredentialSource {
    CredentialLease acquire();

    static ProviderCredentialSource resolved(ProviderCredentialResolver resolver,Config config){
        java.util.Objects.requireNonNull(resolver);java.util.Objects.requireNonNull(config);
        return ()->resolver.resolve(config);
    }

    /** Compatibility source for direct adapter construction in tests and embedders. */
    static ProviderCredentialSource fixed(String value){
        java.util.Objects.requireNonNull(value);
        return ()->new CredentialLease(value.toCharArray(),CredentialLease.Source.ENVIRONMENT);
    }
}
