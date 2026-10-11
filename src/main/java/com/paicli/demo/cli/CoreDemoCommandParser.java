package com.paicli.demo.cli;

import com.paicli.demo.runtime.CoreDemoRunRequest;

import java.util.Arrays;

public final class CoreDemoCommandParser {
    public CoreDemoRunRequest parse(String[] args) {
        String[] copy = args == null ? new String[0] : args.clone();
        if (!Arrays.equals(copy, new String[]{"demo", "core", "--headless"})) {
            throw new CoreDemoUsageException(
                    "Usage: paicli demo core --headless");
        }
        return CoreDemoRunRequest.fixedHeadless();
    }

    public static final class CoreDemoUsageException extends IllegalArgumentException {
        public CoreDemoUsageException(String message) { super(message); }
        public int exitCode() { return 2; }
    }
}
