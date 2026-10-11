package com.paicli.interactive.input;

import com.paicli.interactive.command.SlashCommandParser;
import com.paicli.interactive.completion.WorkspacePathGuard;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class InteractiveInputParser {
    private static final Pattern FILE_REFERENCE =
            Pattern.compile("(?<!\\S)@(?:\"([^\"]+)\"|'([^']+)'|([^\\s]+))");

    private final WorkspacePathGuard pathGuard;

    public InteractiveInputParser(WorkspacePathGuard pathGuard) {
        this.pathGuard = pathGuard;
    }

    public InteractiveInput parse(String input) {
        if (input == null) throw new IllegalArgumentException("input is required");
        if (input.length() > SlashCommandParser.MAX_INPUT_CHARS) {
            throw new IllegalArgumentException("input exceeds 8192 characters");
        }
        String trimmed = input.trim();
        if (trimmed.isEmpty()) return new InteractiveInput(InteractiveInput.Kind.EMPTY, "", List.of());
        if (trimmed.charAt(0) == '/') {
            return new InteractiveInput(InteractiveInput.Kind.SLASH_COMMAND, trimmed, List.of());
        }

        Set<String> references = new LinkedHashSet<>();
        Matcher matcher = FILE_REFERENCE.matcher(input);
        while (matcher.find()) {
            String value = firstNonNull(matcher.group(1), matcher.group(2), matcher.group(3));
            references.add(pathGuard.validateReference(value));
            if (references.size() > 32) throw new IllegalArgumentException("too many file references");
        }
        return new InteractiveInput(InteractiveInput.Kind.TASK, input, new ArrayList<>(references));
    }

    private static String firstNonNull(String... values) {
        for (String value : values) if (value != null) return value;
        throw new IllegalArgumentException("invalid file reference");
    }
}
