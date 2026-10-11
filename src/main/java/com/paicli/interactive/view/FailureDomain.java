package com.paicli.interactive.view;

/** Stable display-only failure classification. */
public enum FailureDomain {
    AUTHORITY_INTEGRITY,
    PROVIDER_INFRASTRUCTURE,
    PROVIDER_PROTOCOL,
    PROVIDER_RESPONSE_PARSE,
    TOOL_ADMISSION,
    TOOL_EXECUTION,
    VERIFICATION,
    RECOVERY,
    MANIFEST,
    USER_CANCELLED,
    INTERNAL
}
