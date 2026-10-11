package com.paicli.provider;

/** One-shot, thread-confined retry metadata source; never exposes response content. */
public interface ProviderRetrySignalSource {
    ProviderRetrySignal takeRetrySignal();
}
