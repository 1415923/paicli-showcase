package com.paicli.demo.runtime;

import java.nio.file.Path;

/** Resolves the fixed Core Demo CLI root; no model, task, or CLI path is accepted. */
public final class CoreDemoRootResolver {
    private CoreDemoRootResolver() { }
    public static Path defaultRoot() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            throw new IllegalStateException("user.home is unavailable");
        }
        return Path.of(home).resolve(".paicli").resolve("demo-runs");
    }
}
