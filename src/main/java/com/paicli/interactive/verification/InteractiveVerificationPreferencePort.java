package com.paicli.interactive.verification;

import com.paicli.config.ProviderConfigStore;

import java.io.IOException;

public interface InteractiveVerificationPreferencePort {
    String save(InteractiveVerificationPolicy.Selection selection, String scope)
            throws IOException;

    static InteractiveVerificationPreferencePort unavailable() {
        return (selection, scope) -> {
            throw new IOException("verification preference storage is unavailable");
        };
    }

    static InteractiveVerificationPreferencePort backedBy(ProviderConfigStore store) {
        return (selection, scope) -> {
            ProviderConfigStore.Scope target = switch (scope) {
                case "project" -> ProviderConfigStore.Scope.PROJECT;
                case "user" -> ProviderConfigStore.Scope.USER;
                default -> throw new IOException("save scope must be project or user");
            };
            store.saveVerification(target, selection);
            return scope;
        };
    }
}
