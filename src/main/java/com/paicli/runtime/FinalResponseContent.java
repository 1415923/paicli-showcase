package com.paicli.runtime;

import java.util.regex.Pattern;

/** Final answers may contain HTTP URLs and API/workspace references. */
public final class FinalResponseContent {
    private static final Pattern DRIVE_OR_HTTP_SCHEME=Pattern.compile(
            "(?i)(?<![a-z0-9+.-])https?://|[a-z]:[\\\\/]");
    private FinalResponseContent() { }

    /** Reject a raw machine ledger before it can become a durable FINAL or user result. */
    public static void requireNotExecutionLedger(String content) {
        if(content==null||content.isBlank())return;
        boolean ledgerOnly=content.lines().map(String::strip).filter(line->!line.isEmpty())
                .allMatch(line->line.matches("kind=(?:TOOL_OCCURRENCE|TOOL_RESULT);source_id=[^;]+;.*"));
        if(ledgerOnly)throw new IllegalStateException("FINAL_RESPONSE_EXECUTION_LEDGER_REJECTED");
    }

    public static void requireNoHostDrivePath(String content) {
        var paths=DRIVE_OR_HTTP_SCHEME.matcher(content);
        // Skip scheme delimiters only; a drive path elsewhere, even inside a URL, fails.
        while(paths.find())if(paths.group().length()==3)
            throw new IllegalStateException("FINAL_RESPONSE_ABSOLUTE_PATH_REJECTED");
    }
}
