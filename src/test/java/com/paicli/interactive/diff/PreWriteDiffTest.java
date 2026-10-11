package com.paicli.interactive.diff;

import com.paicli.safety.SafetyPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PreWriteDiffTest {
    @TempDir Path temp;

    @Test void preparesBoundedDiffWithoutMutatingTargetOrUsingGit() throws Exception {
        Path file = temp.resolve("src/main/java/demo/A.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class A {\r\n  int value() { return 1; }\r\n}\r\n");
        byte[] before = Files.readAllBytes(file);
        PreWriteDiff diff = service().prepare("src\\main\\java\\demo\\A.java",
                "class A {\n  int value() { return 2; }\n}\n", true);
        assertEquals("src/main/java/demo/A.java", diff.relativePath());
        assertEquals(1, diff.additions());
        assertEquals(1, diff.deletions());
        assertTrue(diff.boundedLines().getFirst().startsWith("--- a/"));
        assertArrayEquals(before, Files.readAllBytes(file));
        assertFalse(Files.exists(temp.resolve(".git")));
        assertThrows(IllegalArgumentException.class,
                () -> service().prepare("../outside.txt", "x", false));
    }

    @Test void truncatesLargeDisplayWithoutTruncatingHashOrCounts() throws Exception {
        Path file = temp.resolve("src/main/java/demo/Large.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "old\n".repeat(500));
        PreWriteDiff diff = service().prepare("src/main/java/demo/Large.java",
                "new\n".repeat(500), true);
        assertTrue(diff.truncated());
        assertEquals(500, diff.additions());
        assertEquals(500, diff.deletions());
        assertTrue(diff.boundedLines().size() <= 301);
        assertEquals(64, diff.beforeHash().length());
        assertEquals(64, diff.afterHash().length());
    }

    @Test void newFileIsDetectedFromFilesystemAndReportsSameNameConflict() throws Exception {
        Path existing = temp.resolve("src/main/java/smoke/NameFormatter.java");
        Files.createDirectories(existing.getParent());
        Files.writeString(existing, "package smoke; class NameFormatter {}\n");
        PreWriteDiff diff = service().prepare(
                "src/main/java/com/example/NameFormatter.java",
                "package com.example; class NameFormatter {}\n", false);
        assertFalse(diff.existingFile());
        assertEquals("src/main/java/smoke/NameFormatter.java",
                diff.conflictingRelativePath());
        assertFalse(Files.exists(temp.resolve(
                "src/main/java/com/example/NameFormatter.java")));
    }

    private PreWriteDiffService service() throws Exception {
        return new PreWriteDiffService(new SafetyPolicy(temp, 5, 8192), null);
    }
}
