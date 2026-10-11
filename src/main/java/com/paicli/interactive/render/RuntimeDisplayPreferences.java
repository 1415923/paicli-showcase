package com.paicli.interactive.render;

import java.util.concurrent.atomic.AtomicBoolean;

/** Session-local display preferences; runtime facts and policy are unaffected. */
public final class RuntimeDisplayPreferences {
    private final AtomicBoolean verbose = new AtomicBoolean();

    public boolean verbose() {
        return verbose.get();
    }

    public boolean setVerbose(boolean enabled) {
        verbose.set(enabled);
        return enabled;
    }
}
