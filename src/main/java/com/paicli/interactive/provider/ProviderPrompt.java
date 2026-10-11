package com.paicli.interactive.provider;

@FunctionalInterface
public interface ProviderPrompt {
    /** Returns null on EOF, Ctrl+C, cancellation, or unavailable input. */
    String read(String prompt);
    /** No-echo secret read; unsupported/plain implementations return null. */
    default char[] readSecret(String prompt) { return null; }
}
