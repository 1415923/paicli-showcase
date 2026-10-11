package com.paicli.worktree;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.Comparator;
import java.util.stream.Stream;

public final class GitTestSupport {
    private GitTestSupport() { }

    public static Path initialize(Path root) throws Exception {
        Files.createDirectories(root);
        Result init = git(root, "init", "-b", "main");
        if (init.exitCode() != 0) {
            require(git(root, "init"));
            require(git(root, "checkout", "-b", "main"));
        }
        require(git(root, "config", "user.email", "paicli-test@example.invalid"));
        require(git(root, "config", "user.name", "PaiCLI Test"));
        Files.writeString(root.resolve("base.txt"), "base\n", StandardCharsets.UTF_8);
        require(git(root, "add", "base.txt"));
        require(git(root, "commit", "-m", "base"));
        return root;
    }

    public static Result git(Path directory, String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(directory.toFile())
                .redirectErrorStream(true).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new IOException("test git command timed out");
        }
        String output;
        try (var input = process.getInputStream()) {
            output = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        return new Result(process.exitValue(), output);
    }

    public static String gitText(Path directory, String... arguments) throws Exception {
        Result result = git(directory, arguments);
        require(result);
        return result.output().trim();
    }

    public static void require(Result result) {
        assertEquals(0, result.exitCode(), result.output());
    }

    public static void cleanup(Path repository, Path tempRoot) throws Exception {
        if (!Files.isDirectory(repository.resolve(".git"))) return;
        Result listing = git(repository, "worktree", "list", "--porcelain", "-z");
        if (listing.exitCode() != 0) return;
        try {
            for (WorktreePorcelainParser.Entry entry : new WorktreePorcelainParser().parse(listing.output())) {
                Path path = entry.path().toAbsolutePath().normalize();
                if (!path.equals(repository.toAbsolutePath().normalize())
                        && path.startsWith(tempRoot.toAbsolutePath().normalize())) {
                    git(repository, "worktree", "remove", "--force", path.toString());
                    if (entry.branch() != null && entry.branch().startsWith("refs/heads/")) {
                        git(repository, "branch", "-D",
                                entry.branch().substring("refs/heads/".length()));
                    }
                }
            }
        } catch (ManagedWorktreeException ignored) { }
        clearReadOnlyAttributes(tempRoot);
    }

    private static void clearReadOnlyAttributes(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                path.toFile().setWritable(true, false);
                try {
                    Files.setAttribute(path, "dos:readonly", false);
                } catch (UnsupportedOperationException ignored) {
                    // Non-Windows filesystems do not expose DOS attributes.
                }
            }
        }
    }

    public record Result(int exitCode, String output) { }
}
