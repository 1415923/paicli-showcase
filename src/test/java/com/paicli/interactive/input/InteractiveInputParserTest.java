package com.paicli.interactive.input;

import com.paicli.interactive.completion.WorkspacePathGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InteractiveInputParserTest {
    @TempDir Path temp;

    @Test void distinguishesCommandsTasksEmptyAndQuotedReferences() throws Exception {
        Files.createDirectories(temp.resolve("dir with spaces"));
        Files.writeString(temp.resolve("dir with spaces/File.java"), "class File {}\n");
        InteractiveInputParser parser = new InteractiveInputParser(new WorkspacePathGuard(temp));
        assertEquals(InteractiveInput.Kind.EMPTY, parser.parse("  ").kind());
        assertEquals(InteractiveInput.Kind.SLASH_COMMAND, parser.parse(" /status ").kind());
        InteractiveInput task = parser.parse("explain division / rounding @\"dir with spaces/File.java\"");
        assertEquals(InteractiveInput.Kind.TASK, task.kind());
        assertEquals(List.of("dir with spaces/File.java"), task.fileReferences());
        assertEquals(InteractiveInput.Kind.TASK, parser.parse("explain src/main/java").kind());
    }

    @Test void rejectsEscapesMissingReferencesAndOversizedPaste() {
        InteractiveInputParser parser = new InteractiveInputParser(new WorkspacePathGuard(temp));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("inspect @../outside"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("inspect @missing.txt"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("x".repeat(8193)));
    }
}