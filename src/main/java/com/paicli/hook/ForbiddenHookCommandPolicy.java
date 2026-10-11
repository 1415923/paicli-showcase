package com.paicli.hook;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Blocks obvious destructive, recursive, release, and upload actions in hooks. */
final class ForbiddenHookCommandPolicy {

    private static final List<Rule> RULES = List.of(
            rule("git commit", "\\bgit\\b[^;&|\\r\\n]{0,256}\\bcommit\\b"),
            rule("git push", "\\bgit\\b[^;&|\\r\\n]{0,256}\\bpush\\b"),
            rule("git tag", "\\bgit\\b[^;&|\\r\\n]{0,256}\\btag\\b"),
            rule("recursive PaiCLI invocation", "(?:^|[;&|()\\s])paicli(?:\\.bat|\\.cmd|\\.exe)?(?:\\s|$)|"
                    + "\\bjava\\s+[^;&|\\r\\n]*-jar\\s+[^;&|\\r\\n]*paicli[^;&|\\r\\n]*"),
            rule("PaiCLI rewind", "\\bpaicli(?:\\.bat|\\.cmd|\\.exe)?\\s+rewind\\b"),
            rule("recursive deletion", "(?:^|[;&|()\\s])rm\\s+[^;&|\\r\\n]*(?:-rf|-fr)\\b|"
                    + "(?:^|[;&|()\\s])del\\s+[^;&|\\r\\n]*/s\\b|"
                    + "(?:^|[;&|()\\s])rmdir\\s+[^;&|\\r\\n]*/s\\b"),
            rule("overwriting move", "(?:^|[;&|()\\s])move\\s+[^;&|\\r\\n]*/y\\b|"
                    + "(?:^|[;&|()\\s])mv\\s+[^;&|\\r\\n]*(?:-f|--force)\\b"),
            rule("overwriting copy", "(?:^|[;&|()\\s])copy\\s+[^;&|\\r\\n]*/y\\b|"
                    + "(?:^|[;&|()\\s])cp\\s+[^;&|\\r\\n]*(?:-f|--force)\\b"),
            rule("PowerShell Set-Content", "\\bset-content\\b|\\bsc\\s+-path\\b"),
            rule("PowerShell destructive file operation", "\\bremove-item\\b[^\\r\\n]*(?:-recurse|-force)|"
                    + "\\b(?:copy-item|move-item)\\b[^\\r\\n]*-force|\\bout-file\\b"),
            rule("shell output redirection", "(?:^|[;&|()\\s])echo\\b[^\\r\\n]*>\\s*[^&]"),
            rule("file upload", "(?:^|[;&|()\\s])scp(?:\\.exe)?\\s|"
                    + "(?:^|[;&|()\\s])curl(?:\\.exe)?\\s+[^;&|\\r\\n]*(?:--upload-file|-t)\\b")
    );

    Optional<String> rejectionReason(String command) {
        if (command == null || command.isBlank()) {
            return Optional.of("hook command is blank");
        }
        String normalized = command.toLowerCase(Locale.ROOT);
        return RULES.stream()
                .filter(rule -> rule.pattern().matcher(normalized).find())
                .map(rule -> "hook command blocked: " + rule.description())
                .findFirst();
    }

    private static Rule rule(String description, String regex) {
        return new Rule(description, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
    }

    private record Rule(String description, Pattern pattern) {
    }
}
