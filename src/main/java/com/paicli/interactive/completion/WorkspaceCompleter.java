package com.paicli.interactive.completion;

import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class WorkspaceCompleter implements Completer {
    public static final int MAX_RESULTS = 100;
    private static final int MAX_DEPTH = 16;
    private final WorkspacePathGuard guard;

    public WorkspaceCompleter(WorkspacePathGuard guard) {
        this.guard = guard;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String word = line.word();
        int marker = word.lastIndexOf('@');
        if (marker < 0) return;
        String token = word.substring(marker + 1).replace('\\', '/');
        completeToken(token).forEach(value -> {
            String candidate = value.contains(" ") ? "@\"" + value + "\"" : "@" + value;
            candidates.add(new Candidate(candidate));
        });
    }

    public List<String> completeToken(String token) {
        if (token == null || token.length() > 1024) return List.of();
        String normalized = token.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String directory = slash < 0 ? "." : normalized.substring(0, slash);
        String prefix = slash < 0 ? normalized : normalized.substring(slash + 1);
        if (directory.split("/", -1).length > MAX_DEPTH) return List.of();

        Path parent;
        try {
            parent = guard.resolveCompletionDirectory(directory);
        } catch (IllegalArgumentException error) {
            return List.of();
        }
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) return List.of();
        List<String> results = new ArrayList<>();
        try (var stream = Files.list(parent)) {
            stream.filter(path -> path.getFileName().toString().startsWith(prefix))
                    .filter(guard::isCandidateAllowed)
                    .limit(MAX_RESULTS + 1L)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .forEach(path -> {
                        String relative = guard.relative(path);
                        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) relative += "/";
                        results.add(relative);
                    });
        } catch (IOException | RuntimeException error) {
            return List.of();
        }
        if (results.size() > MAX_RESULTS) return List.copyOf(results.subList(0, MAX_RESULTS));
        return List.copyOf(results);
    }
}
