package com.paicli.interactive.completion;

import com.paicli.interactive.command.SlashCommandRegistry;
import org.jline.reader.Candidate;
import org.jline.reader.impl.DefaultParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CompletionTest {
    @TempDir Path temp;

    @Test void completesFrozenSlashPrefixesWithDescriptions() {
        SlashCommandCompleter completer = new SlashCommandCompleter(new SlashCommandRegistry());
        List<Candidate> candidates = new ArrayList<>();
        completer.complete(null, new DefaultParser().parse("/st", 3), candidates);
        assertEquals(List.of("/status"), candidates.stream().map(Candidate::value).toList());
        assertNotNull(candidates.getFirst().descr());
        assertFalse(candidates.stream().anyMatch(value -> value.value().equals("/mcp")));

        candidates.clear();
        completer.complete(null, new DefaultParser().parse("/q", 2), candidates);
        assertEquals(List.of("/quit"), candidates.stream().map(Candidate::value).toList());
    }

    @Test void completesOnlyStaticCommandArguments() {
        SlashCommandCompleter completer = new SlashCommandCompleter(new SlashCommandRegistry());
        List<Candidate> candidates = new ArrayList<>();
        completer.complete(null, new DefaultParser().parse("/mode re", 8), candidates);
        assertEquals(List.of("read-only"), candidates.stream().map(Candidate::value).toList());

        candidates.clear();
        completer.complete(null, new DefaultParser().parse("/history c", 10), candidates);
        assertEquals(List.of("clear"), candidates.stream().map(Candidate::value).toList());

        candidates.clear();
        completer.complete(null, new DefaultParser().parse("/model set q", 12), candidates);
        assertTrue(candidates.isEmpty(), "model ids must not be guessed dynamically");

        candidates.clear();
        completer.complete(null, new DefaultParser().parse("/provider set o", 15), candidates);
        assertEquals(List.of("ollama", "openai"),
                candidates.stream().map(Candidate::value).sorted().toList());

        candidates.clear();
        completer.complete(null, new DefaultParser().parse(
                "/verify auto --save p", 21), candidates);
        assertEquals(List.of("project"), candidates.stream().map(Candidate::value).toList());
    }

    @Test void completesOnlySafeRelativeWorkspacePaths() throws Exception {
        Files.createDirectories(temp.resolve("src/main/java"));
        Files.createDirectories(temp.resolve("target"));
        Files.createDirectories(temp.resolve(".paicli"));
        Files.createDirectories(temp.resolve("dir with spaces"));
        Files.writeString(temp.resolve("src/main/java/App.java"), "class App {}\n");
        Files.writeString(temp.resolve("target/secret.txt"), "hidden");
        Files.writeString(temp.resolve(".paicli/config.json"), "{}");
        Files.writeString(temp.resolve("private.pem"), "hidden");
        WorkspaceCompleter completer = new WorkspaceCompleter(new WorkspacePathGuard(temp));

        assertEquals(List.of("src/main/"), completer.completeToken("src\\ma"));
        assertTrue(completer.completeToken("").contains("src/"));
        assertTrue(completer.completeToken("dir").contains("dir with spaces/"));
        List<Candidate> spaced = new ArrayList<>();
        completer.complete(null, new DefaultParser().parse("explain @dir", 12), spaced);
        assertTrue(spaced.stream().anyMatch(value -> value.value().equals("@\"dir with spaces/\"")));
        assertFalse(completer.completeToken("").stream().anyMatch(value ->
                value.startsWith("target") || value.startsWith(".paicli") || value.endsWith(".pem")));
        assertEquals(List.of(), completer.completeToken("../"));
    }

    @Test void boundsLargeDirectoryResults() throws Exception {
        for (int index = 0; index < 140; index++) Files.writeString(temp.resolve("file-" + index), "x");
        WorkspaceCompleter completer = new WorkspaceCompleter(new WorkspacePathGuard(temp));
        assertEquals(WorkspaceCompleter.MAX_RESULTS, completer.completeToken("file-").size());
    }

    @Test void rejectsSymlinkCandidatesWhenPlatformSupportsLinks() throws Exception {
        Path outside = Files.createDirectory(temp.resolveSibling(temp.getFileName() + "-outside"));
        Path link = temp.resolve("linked");
        try { Files.createSymbolicLink(link, outside); }
        catch (Exception error) { Assumptions.abort("symbolic links unavailable: " + error.getClass().getSimpleName()); }
        WorkspaceCompleter completer = new WorkspaceCompleter(new WorkspacePathGuard(temp));
        assertFalse(completer.completeToken("lin").contains("linked/"));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkspacePathGuard(temp).validateReference("linked"));
    }
}
