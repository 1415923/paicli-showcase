package com.paicli.demo.runtime;

/** Fixed request contract. It intentionally carries no path, command, provider or task text. */
public record CoreDemoRunRequest(boolean headless) {
    public CoreDemoRunRequest {
        if (!headless) throw new IllegalArgumentException("Core Demo requires headless=true");
    }
    public static CoreDemoRunRequest fixedHeadless() { return new CoreDemoRunRequest(true); }
}
