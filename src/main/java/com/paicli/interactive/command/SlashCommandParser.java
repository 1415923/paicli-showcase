package com.paicli.interactive.command;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class SlashCommandParser {
    public static final int MAX_INPUT_CHARS = 8192;
    private final SlashCommandRegistry registry;
    public SlashCommandParser(SlashCommandRegistry registry) { this.registry = registry; }

    public ParsedSlashCommand parse(String input, boolean taskRunning) {
        if (input == null || input.isBlank() || input.length() > MAX_INPUT_CHARS) {
            throw new IllegalArgumentException("invalid slash command input");
        }
        String trimmed = input.trim();
        if (!trimmed.startsWith("/")) throw new IllegalArgumentException("not a slash command");
        String[] parts = trimmed.split("\\s+");
        String token = parts[0].toLowerCase(Locale.ROOT);
        SlashCommand command = registry.resolveToken(token)
                .orElseThrow(() -> new IllegalArgumentException(unknownMessage(token)));
        SlashCommandSpec spec = registry.find(command).orElseThrow();
        List<String> args = command == SlashCommand.MCP || command == SlashCommand.SKILLS
                ? quotedArguments(trimmed.substring(parts[0].length()))
                : List.copyOf(Arrays.asList(parts).subList(1, parts.length));
        if (args.size() < spec.minArguments() || args.size() > spec.maxArguments()) {
            throw new IllegalArgumentException("usage: " + spec.syntax());
        }
        if (taskRunning && command!=SlashCommand.COMPACT && !spec.allowedWhileTaskRunning()) {
            throw new IllegalStateException(command.token() + " is unavailable while a task is running");
        }
        for (String arg : args) {
            if (arg.length() > 256 || arg.chars().anyMatch(value -> value < 0x20 || value == 0x7f)) {
                throw new IllegalArgumentException("invalid command argument");
            }
        }
        validateValues(command, args);
        return new ParsedSlashCommand(command, args, spec);
    }

    private static List<String> quotedArguments(String input) {
        var result = new java.util.ArrayList<String>();
        var word = new StringBuilder();
        boolean quoted = false, started = false;
        for (char c : input.toCharArray()) {
            if (c == '"') { quoted = !quoted; started = true; }
            else if (Character.isWhitespace(c) && !quoted) {
                if (started) { result.add(word.toString()); word.setLength(0); started = false; }
            } else { word.append(c); started = true; }
        }
        if (quoted) throw new IllegalArgumentException("unterminated quoted path");
        if (started) result.add(word.toString());
        return List.copyOf(result);
    }

    private String unknownMessage(String token) {
        String suggestion = registry.all().stream()
                .flatMap(spec -> registry.tokensFor(spec.command()).stream())
                .min((left, right) -> Integer.compare(distance(token, left), distance(token, right)))
                .filter(value -> distance(token, value) <= 3).orElse(null);
        return suggestion == null ? "unknown slash command"
                : "unknown slash command; did you mean " + suggestion + "?";
    }

    private static int distance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1]; current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            previous = current;
        }
        return previous[right.length()];
    }

    private static void validateValues(SlashCommand command, List<String> args) {
        if (args.isEmpty()) return;
        switch (command) {
            case PROVIDER -> validateProvider(args);
            case MODEL -> validateModel(args);
            default -> validateLegacy(command, args);
        }
    }

    private static void validateLegacy(SlashCommand command, List<String> args) {
        String value = args.getFirst();
        switch (command) {
            case STATUS -> requireOneOf(value,"status view",
                    "task","provider","usage","--details");
            case MODE -> requireOneOf(value, "mode", "plan", "read-only", "on-request", "workspace-write");
            case VERIFY -> requireOneOf(value, "verification mode", "status", "auto", "off");
            case PLAN -> {
                requireOneOf(value, "plan view", "--details");
                if (args.size() != 1) throw new IllegalArgumentException(
                        "usage: /plan [--details]");
            }
            case HISTORY -> {
                if (!"clear".equals(value)) {
                    try {
                        int limit = Integer.parseInt(value);
                        if (limit < 1 || limit > 100) {
                            throw new IllegalArgumentException("history limit must be 1..100");
                        }
                    } catch (NumberFormatException error) {
                        throw new IllegalArgumentException("history selector must be clear or a limit");
                    }
                }
            }
            case TASKS -> {
                if (!value.matches("[A-Za-z0-9._-]{1,128}") || ".".equals(value) || "..".equals(value)) {
                    throw new IllegalArgumentException("invalid tasks selector");
                }
                if (value.matches("\\d+")
                        && (Integer.parseInt(value) < 1 || Integer.parseInt(value) > 100)) {
                    throw new IllegalArgumentException("tasks limit must be 1..100");
                }
            }
            case RESUME, REWIND, REPORT -> {
                if (!value.matches("[A-Za-z0-9._-]{1,128}") || ".".equals(value) || "..".equals(value)) {
                    throw new IllegalArgumentException("invalid selector");
                }
            }
            case COMPACT -> {
                requireOneOf(value,"compact operation","status");
                if(args.size()!=1)throw new IllegalArgumentException("usage: /compact [status]");
            }
            case TASK -> {
                requireOneOf(value, "task operation", "recover");
                if (args.size() == 2 && (!args.get(1).matches("[A-Za-z0-9._-]{1,128}")
                        || ".".equals(args.get(1)) || "..".equals(args.get(1)))) {
                    throw new IllegalArgumentException("invalid Task selector");
                }
            }
            default -> { }
        }
    }

    private static void validateProvider(List<String> args) {
        String operation = args.getFirst();
        requireOneOf(operation,"provider operation","status","set","test","clear",
                "credential","login","logout");
        if ("set".equals(operation)) {
            if (args.size() != 2) throw new IllegalArgumentException("usage: /provider set <ollama|openai>");
            requireOneOf(args.get(1), "provider", "ollama", "openai");
        } else if("credential".equals(operation)){
            if(args.size()!=2||!"status".equals(args.get(1)))
                throw new IllegalArgumentException("usage: /provider credential status");
        } else if (args.size() != 1) {
            throw new IllegalArgumentException("usage: /provider [status|set ollama|set openai|test|clear|credential status|login|logout]");
        }
    }

    private static void validateModel(List<String> args) {
        String operation = args.getFirst();
        requireOneOf(operation, "model operation", "list", "set");
        if ("set".equals(operation)) {
            if (args.size() != 2) throw new IllegalArgumentException("usage: /model set <model-id>");
        } else if (args.size() != 1) {
            throw new IllegalArgumentException("usage: /model list");
        }
    }

    private static void requireOneOf(String value, String field, String... allowed) {
        for (String candidate : allowed) if (candidate.equals(value)) return;
        throw new IllegalArgumentException("unsupported " + field);
    }

    public record ParsedSlashCommand(SlashCommand command, List<String> arguments, SlashCommandSpec spec) {
        public ParsedSlashCommand { arguments = List.copyOf(arguments); }
    }
}
