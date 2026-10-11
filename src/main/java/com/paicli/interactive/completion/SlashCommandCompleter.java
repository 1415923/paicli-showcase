package com.paicli.interactive.completion;

import com.paicli.interactive.command.SlashCommandRegistry;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.List;
import java.util.Map;

public final class SlashCommandCompleter implements Completer {
    private static final Map<com.paicli.interactive.command.SlashCommand,List<String>>
            ARGUMENTS = Map.ofEntries(
            Map.entry(com.paicli.interactive.command.SlashCommand.STATUS,
                    List.of("task", "provider", "usage", "--details")),
            Map.entry(com.paicli.interactive.command.SlashCommand.PROVIDER,
                    List.of("status", "set", "test", "clear", "credential", "login", "logout")),
            Map.entry(com.paicli.interactive.command.SlashCommand.MODEL,
                    List.of("list", "set")),
            Map.entry(com.paicli.interactive.command.SlashCommand.MODE,
                    List.of("plan", "read-only", "on-request", "workspace-write")),
            Map.entry(com.paicli.interactive.command.SlashCommand.TASKS,
                    List.of("active", "recent", "failed")),
            Map.entry(com.paicli.interactive.command.SlashCommand.RESUME,
                    List.of("--last")),
            Map.entry(com.paicli.interactive.command.SlashCommand.TASK,
                    List.of("recover")),
            Map.entry(com.paicli.interactive.command.SlashCommand.REWIND,
                    List.of("--details")),
            Map.entry(com.paicli.interactive.command.SlashCommand.REPORT,
                    List.of("latest")),
            Map.entry(com.paicli.interactive.command.SlashCommand.COMPACT,
                    List.of("status")),
            Map.entry(com.paicli.interactive.command.SlashCommand.VERIFY,
                    List.of("status", "auto", "off")),
            Map.entry(com.paicli.interactive.command.SlashCommand.VERBOSE,
                    List.of("status", "on", "off")),
            Map.entry(com.paicli.interactive.command.SlashCommand.HISTORY,
                    List.of("clear")));
    private final SlashCommandRegistry registry;

    public SlashCommandCompleter(SlashCommandRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String input = line.line().stripLeading();
        if (!input.startsWith("/")) return;
        if (line.wordIndex() == 0) {
            String word = line.word();
            registry.all().forEach(spec -> registry.tokensFor(spec.command()).stream()
                    .filter(token -> token.startsWith(word))
                    .forEach(token -> candidates.add(new Candidate(
                            token, token, null, spec.outputContract(), null, null, true))));
            return;
        }
        String token = input.split("\\s+", 2)[0].toLowerCase(java.util.Locale.ROOT);
        registry.resolveToken(token).map(command -> argumentCandidates(command, line)).stream()
                .flatMap(java.util.Collection::stream)
                .filter(value -> value.startsWith(line.word()))
                .forEach(value -> candidates.add(new Candidate(value)));
    }

    private static List<String> argumentCandidates(
            com.paicli.interactive.command.SlashCommand command, ParsedLine line) {
        int index = line.wordIndex();
        List<String> words = line.words();
        if (command == com.paicli.interactive.command.SlashCommand.PROVIDER) {
            if (index == 1) return ARGUMENTS.get(command);
            if (index == 2 && "set".equals(words.get(1))) return List.of("ollama", "openai");
            if (index == 2 && "credential".equals(words.get(1))) return List.of("status");
            return List.of();
        }
        if (command == com.paicli.interactive.command.SlashCommand.VERIFY) {
            if (index == 1) return ARGUMENTS.get(command);
            if (index == 2 && List.of("auto", "off").contains(words.get(1))) return List.of("--save");
            if (index == 3 && "--save".equals(words.get(2))) return List.of("project", "user");
            return List.of();
        }
        if (command == com.paicli.interactive.command.SlashCommand.COMPACT) {
            return index == 1 ? ARGUMENTS.get(command) : List.of();
        }
        return index == 1 ? ARGUMENTS.getOrDefault(command, List.of()) : List.of();
    }
}
