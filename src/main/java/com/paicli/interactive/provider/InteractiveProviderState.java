package com.paicli.interactive.provider;

import com.paicli.config.Config;
import com.paicli.config.ConfigLoader;
import com.paicli.provider.ProviderFactory;

import java.util.Objects;

/** Session-only provider snapshot used by commands, Doctor and the next task. */
public final class InteractiveProviderState {
    private Config config;
    private ConfigLoader.Source source;

    public InteractiveProviderState(ConfigLoader.Resolution resolution) {
        this.config = Objects.requireNonNull(resolution, "resolution").config();
        this.source = resolution.providerSource();
    }

    public synchronized Config config() { return config; }
    public synchronized ConfigLoader.Source source() { return source; }

    public synchronized void install(Config candidate, ConfigLoader.Source nextSource) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(nextSource, "nextSource");
        ProviderFactory.create(candidate);
        config = candidate;
        source = nextSource;
    }

    public synchronized void clear(ConfigLoader.Resolution fallback) {
        config = Objects.requireNonNull(fallback, "fallback").config();
        source = fallback.providerSource();
    }
}
