package com.paicli.tool;

import com.paicli.safety.SafetyPolicy;
import com.paicli.safety.SafetyViolationException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict argument policy for the read-only git_diff tool. */
final class GitDiffArgumentPolicy {
    private static final int MAX_ARGUMENTS = 32;
    private static final Set<String> BLOCKED_SUBCOMMANDS = Set.of("commit", "push", "pull", "tag", "reset", "checkout", "merge", "rebase", "stash", "clean", "restore");
    private static final int MAX_ARGUMENT_CHARS = 512;
    private static final Pattern UNSAFE_TEXT = Pattern.compile("[\\u0000-\\u001f\\u007f;&|`]|\\$\\(|%[A-Za-z_][A-Za-z0-9_]*%");
    private static final Pattern MAX_COUNT = Pattern.compile("--max-count=([1-9][0-9]?)|--max-count=100");

    private static final Set<String> DIFF_OPTIONS = Set.of(
            "--cached", "--staged", "--stat", "--name-only", "--name-status",
            "--summary", "--no-renames", "--no-color", "--color=never");
    private static final Set<String> STATUS_OPTIONS = Set.of(
            "--short", "--porcelain", "--porcelain=v1", "--porcelain=v2", "--branch",
            "--show-stash", "--no-renames", "--untracked-files=no",
            "--untracked-files=normal", "--untracked-files=all", "--ignored=no",
            "--ignored=matching", "--ignored=traditional");
    private static final Set<String> LOG_OPTIONS = Set.of(
            "--oneline", "--no-decorate", "--decorate=no", "--decorate=short",
            "--decorate=full", "--decorate=auto", "--stat", "--name-only",
            "--name-status", "--no-color", "--color=never");

    List<String> buildCommand(Map<String, Object> input, SafetyPolicy policy) throws SafetyViolationException {
        NormalizedGitDiff normalized=normalize(input,policy);
        String subcommand=normalized.subcommand();
        List<String> safe=normalized.args();

        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("--no-pager");
        command.add("-c");
        command.add("core.fsmonitor=false");
        command.add("-c");
        command.add("core.untrackedCache=false");
        command.add(subcommand);
        if ("diff".equals(subcommand) || "log".equals(subcommand)) {
            command.add("--no-ext-diff");
            command.add("--no-textconv");
        }
        command.addAll(safe);
        return List.copyOf(command);
    }

    NormalizedGitDiff normalize(Map<String,Object> input,SafetyPolicy policy)
            throws SafetyViolationException {
        if (!Set.of("subcommand", "args").containsAll(input.keySet())) {
            throw new IllegalArgumentException("unknown git_diff argument field");
        }
        String subcommand = parseSubcommand(input.get("subcommand"));
        List<String> supplied = parseArguments(input.get("args"));
        List<String> safe = validateArguments(subcommand, supplied, policy);
        return new NormalizedGitDiff(subcommand,safe);
    }

    private String parseSubcommand(Object value) {
        String subcommand = value == null ? "diff" : value instanceof String text
                ? text.trim().toLowerCase(Locale.ROOT) : "";
        if (BLOCKED_SUBCOMMANDS.contains(subcommand)) {
            throw new IllegalArgumentException("blocked write operation");
        }
        if (!Set.of("diff", "status", "log").contains(subcommand)) {
            throw new IllegalArgumentException("unsupported subcommand (allowed: diff, status, log)");
        }
        return subcommand;
    }

    private List<String> parseArguments(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> values)) {
            throw new IllegalArgumentException("'args' must be an array of argument strings");
        }
        if (values.size() > MAX_ARGUMENTS) {
            throw new IllegalArgumentException("too many Git arguments");
        }
        List<String> result = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException("every Git argument must be a non-empty string");
            }
            if (text.length() > MAX_ARGUMENT_CHARS || UNSAFE_TEXT.matcher(text).find()) {
                throw new IllegalArgumentException("Git argument contains forbidden syntax");
            }
            result.add(text);
        }
        return List.copyOf(result);
    }

    private List<String> validateArguments(String subcommand, List<String> supplied,
                                           SafetyPolicy policy) throws SafetyViolationException {
        List<String> result = new ArrayList<>();
        boolean pathMode = false;
        for (String argument : supplied) {
            if ("--".equals(argument)) {
                if (pathMode) throw new IllegalArgumentException("duplicate path separator");
                pathMode = true;
                result.add(argument);
                continue;
            }
            if (!pathMode) {
                if (!allowedOption(subcommand, argument)) {
                    throw new IllegalArgumentException("unsupported Git option for " + subcommand);
                }
                result.add(argument);
                continue;
            }
            result.add(validatePathspec(argument, policy));
        }
        return List.copyOf(result);
    }

    private boolean allowedOption(String subcommand, String argument) {
        return switch (subcommand) {
            case "diff" -> DIFF_OPTIONS.contains(argument);
            case "status" -> STATUS_OPTIONS.contains(argument);
            case "log" -> LOG_OPTIONS.contains(argument) || MAX_COUNT.matcher(argument).matches();
            default -> false;
        };
    }

    private String validatePathspec(String argument, SafetyPolicy policy) throws SafetyViolationException {
        if (argument.startsWith(":") || argument.contains("*") || argument.contains("?")
                || argument.contains("[") || argument.contains("]")) {
            throw new IllegalArgumentException("Git pathspec magic and wildcards are not allowed");
        }
        Path candidate;
        try {
            candidate = Path.of(argument);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("invalid Git path", e);
        }
        Path resolved = policy.resolveWorkspacePath(candidate);
        Path relative = policy.getWorkspaceRoot().relativize(resolved);
        String normalized = relative.toString().replace('\\', '/');
        return normalized.isEmpty() ? "." : normalized;
    }

    record NormalizedGitDiff(String subcommand,List<String> args){
        NormalizedGitDiff{args=List.copyOf(args);}
    }
}
