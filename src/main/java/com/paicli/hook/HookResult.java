package com.paicli.hook;

public record HookResult(
        String hookRunId,
        String hookId,
        HookEvent event,
        String status,
        Integer exitCode,
        long durationMs,
        String outputExcerpt,
        String errorExcerpt,
        String failurePolicy
) {
}
