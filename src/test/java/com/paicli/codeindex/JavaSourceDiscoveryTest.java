package com.paicli.codeindex;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JavaSourceDiscoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void discoversJavaFilesAsStableWorkspaceRelativePaths() throws Exception {
        write("src/main/java/demo/Zeta.java", "class Zeta {}");
        write("src/main/java/demo/Alpha.java", "class Alpha {}");
        write("src/test/java/demo/AlphaTest.java", "class AlphaTest {}");
        write("src/main/java/demo/readme.txt", "not java");

        JavaSourceDiscovery.DiscoveryResult result =
                new JavaSourceDiscovery().discover(tempDir);

        assertEquals(List.of(
                "src/main/java/demo/Alpha.java",
                "src/main/java/demo/Zeta.java",
                "src/test/java/demo/AlphaTest.java"
        ), result.files().stream()
                .map(JavaSourceDiscovery.JavaSourceFile::relativePath)
                .toList());
        assertTrue(result.issues().isEmpty());
    }

    @Test
    void excludesBuildRuntimeAndHiddenControlDirectories() throws Exception {
        write("src/main/java/demo/App.java", "class App {}");
        write("target/generated/Generated.java", "class Generated {}");
        write("build/generated/BuildGenerated.java", "class BuildGenerated {}");
        write("out/generated/OutGenerated.java", "class OutGenerated {}");
        write(".git/hooks/Hook.java", "class Hook {}");
        write(".paicli/state/Runtime.java", "class Runtime {}");
        write(".idea/Scratch.java", "class Scratch {}");
        write("node_modules/pkg/Fake.java", "class Fake {}");

        JavaSourceDiscovery.DiscoveryResult result =
                new JavaSourceDiscovery().discover(tempDir);

        assertEquals(List.of("src/main/java/demo/App.java"),
                result.files().stream()
                        .map(JavaSourceDiscovery.JavaSourceFile::relativePath)
                        .toList());
    }

    @Test
    void reportsBoundedAggregateProgressWithoutChangingDiscoveryResults()
            throws Exception {
        write("src/main/java/demo/App.java", "class App {}");
        write("src/main/resources/readme.txt", "resource");
        List<JavaSourceDiscovery.ProgressSnapshot> progress =
                new CopyOnWriteArrayList<>();

        JavaSourceDiscovery.DiscoveryResult result =
                new JavaSourceDiscovery().discover(tempDir, progress::add);

        assertEquals(1, result.files().size());
        assertFalse(progress.isEmpty());
        JavaSourceDiscovery.ProgressSnapshot terminal = progress.getLast();
        assertTrue(terminal.finalSnapshot());
        assertTrue(terminal.visitedDirectories() >= 4);
        assertEquals(2, terminal.visitedFiles());
        assertEquals(1, terminal.matchedJavaFiles());
        assertTrue(terminal.approximateBytes() > 0);
        assertFalse(terminal.crossedInitialRoot());
    }

    @Test
    void checksCancellationDuringTraversal() throws Exception {
        write("src/main/java/demo/App.java", "class App {}");
        java.util.concurrent.atomic.AtomicInteger checks =
                new java.util.concurrent.atomic.AtomicInteger();

        assertThrows(ScanCancelled.class, () ->
                new JavaSourceDiscovery().discover(tempDir, ignored -> { }, () -> {
                    if (checks.incrementAndGet() >= 2) throw new ScanCancelled();
                }));
        assertTrue(checks.get() >= 2);
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void skipsSymlinkDirectoryWithoutFollowingOutsideWorkspace()
            throws Exception {
        Path outside = Files.createTempDirectory("paicli-outside");
        try {
            Files.createDirectories(outside.resolve("src"));
            Files.writeString(outside.resolve("src/Outside.java"),
                    "class Outside {}");
            Files.createSymbolicLink(tempDir.resolve("linked"), outside);

            JavaSourceDiscovery.DiscoveryResult result =
                    new JavaSourceDiscovery().discover(tempDir);

            assertTrue(result.files().isEmpty());
            assertTrue(result.issues().stream()
                    .anyMatch(issue -> issue.reason().contains("symlink")));
        } finally {
            Files.deleteIfExists(outside.resolve("src/Outside.java"));
            Files.deleteIfExists(outside.resolve("src"));
            Files.deleteIfExists(outside);
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void skipsWindowsJunctionWithoutFollowingOutsideWorkspace() throws Exception {
        Path outside = tempDir.resolveSibling(tempDir.getFileName() + "-outside");
        Path junction = tempDir.resolve("outside-junction");
        Files.createDirectories(outside);
        writeAt(outside.resolve("Outside.java"), "class Outside {}");

        Process create = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J",
                junction.toString(), outside.toString()).redirectErrorStream(true).start();
        String output = new String(create.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, create.waitFor(), output);
        try {
            JavaSourceDiscovery.DiscoveryResult result =
                    new JavaSourceDiscovery().discover(tempDir);

            assertTrue(result.files().isEmpty());
            assertTrue(result.issues().stream().anyMatch(issue ->
                    issue.reason().contains("reparse")
                            || issue.reason().contains("special")));
        } finally {
            Files.deleteIfExists(junction);
            Files.deleteIfExists(outside.resolve("Outside.java"));
            Files.deleteIfExists(outside);
        }
    }

    private void write(String relativePath, String source) throws IOException {
        Path path = tempDir.resolve(relativePath);
        writeAt(path, source);
    }

    private static void writeAt(Path path, String source) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, source);
    }

    private static final class ScanCancelled extends RuntimeException {
    }
}
