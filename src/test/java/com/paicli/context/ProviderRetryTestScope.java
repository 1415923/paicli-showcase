package com.paicli.context;

/** Test-only helper for exercising the production retry admission boundary. */
public final class ProviderRetryTestScope {
    private ProviderRetryTestScope() { }

    public static AutoCloseable open() {
        ProviderContextRuntime.Scope scope=ProviderContextRuntime.bind(
                new ProviderContextRuntime.ProviderTurnIdentity(
                        "ctx-test","task-test","run-test","plan-test",
                        "source-test","compiled-test","occ-test"),()->{});
        return scope::close;
    }
}
