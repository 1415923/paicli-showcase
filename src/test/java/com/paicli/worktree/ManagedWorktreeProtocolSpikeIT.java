package com.paicli.worktree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedWorktreeProtocolSpikeIT {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(15);

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanOnlyJUnitOwnedGitResources() throws Exception {
        Path main = tempDir.resolve("main");
        if (Files.isDirectory(main.resolve(".git"))) {
            CommandResult listing = git(main, "worktree", "list", "--porcelain", "-z");
            if (listing.exitCode() == 0) {
                for (PorcelainWorktree record : parsePorcelain(listing.stdout())) {
                    if (!samePath(record.path(), main)
                            && record.path().toAbsolutePath().normalize().startsWith(tempDir)) {
                        git(main, "worktree", "remove", "--force",
                                record.path().toString()).requireSuccess();
                    }
                }
            }
        }
        clearReadOnlyAttributes(tempDir);
    }

    @Test
    void nativeWorktreeIsolatesMainAndPorcelainRecoversMetadata() throws Exception {
        Path main = createRepository();
        Path managedRoot = Files.createDirectory(tempDir.resolve("managed"));
        Path worker = managedRoot.resolve("worker-1");
        String branch = "paicli/worker/task-1/worker-1-a1";
        String baseSha = git(main, "rev-parse", "HEAD").stdout().trim();
        String mainHeadBefore = git(main, "rev-parse", "HEAD").stdout().trim();

        preflight(main, managedRoot, worker, branch, List.of());
        git(main, "worktree", "add", "-b", branch,
                worker.toString(), baseSha).requireSuccess();

        Files.writeString(worker.resolve("base.txt"), "worker changed base\n");
        Files.writeString(worker.resolve("worker.txt"), "worker-only\n");
        git(worker, "add", "base.txt", "worker.txt").requireSuccess();
        git(worker, "-c", "user.name=PaiCLI R0",
                "-c", "user.email=paicli-r0@example.invalid",
                "commit", "-m", "worker candidate").requireSuccess();

        assertFalse(Files.exists(main.resolve("worker.txt")));
        assertEquals("base\n", Files.readString(main.resolve("base.txt")));
        assertEquals(mainHeadBefore, git(main, "rev-parse", "HEAD").stdout().trim());
        assertNotEquals(mainHeadBefore, git(worker, "rev-parse", "HEAD").stdout().trim());

        List<PorcelainWorktree> recovered = parsePorcelain(
                git(main, "worktree", "list", "--porcelain", "-z").stdout());
        PorcelainWorktree workerRecord = recovered.stream()
                .filter(record -> samePath(record.path(), worker))
                .findFirst()
                .orElseThrow();
        assertEquals("refs/heads/" + branch, workerRecord.branch());
        assertEquals(git(worker, "rev-parse", "HEAD").stdout().trim(), workerRecord.head());
    }

    @Test
    void pathAndBranchCollisionsAndEscapesFailClosed() throws Exception {
        Path main = createRepository();
        Path managedRoot = Files.createDirectory(tempDir.resolve("managed"));
        Path existingWorker = managedRoot.resolve("worker-existing");
        String branch = "paicli/worker/task-1/worker-existing-a1";
        String baseSha = git(main, "rev-parse", "HEAD").stdout().trim();

        preflight(main, managedRoot, existingWorker, branch, List.of());
        git(main, "worktree", "add", "-b", branch,
                existingWorker.toString(), baseSha).requireSuccess();

        assertThrows(IllegalStateException.class,
                () -> preflight(main, managedRoot, existingWorker,
                        "paicli/worker/task-1/new-a1", List.of(existingWorker)));
        assertThrows(IllegalStateException.class,
                () -> preflight(main, managedRoot, managedRoot.resolve("worker-new"),
                        branch, List.of(existingWorker)));
        assertThrows(IllegalArgumentException.class,
                () -> resolveRequestedWorkspace(managedRoot, Path.of("..", "escape")));
        assertThrows(IllegalArgumentException.class,
                () -> resolveRequestedWorkspace(managedRoot, main.toAbsolutePath()));
        Path unsafeManagedRoot = Files.createDirectory(main.resolve("managed-inside-main"));
        assertThrows(IllegalArgumentException.class,
                () -> preflight(main, unsafeManagedRoot,
                        unsafeManagedRoot.resolve("worker"),
                        "paicli/worker/task-1/inside-main-a1", List.of(existingWorker)));
        assertThrows(IllegalStateException.class,
                () -> preflight(main, managedRoot,
                        existingWorker.resolve("nested-worker"),
                        "paicli/worker/task-1/nested-a1", List.of(existingWorker)));
        assertFalse(Files.exists(tempDir.resolve("escape")));
    }

    @Test
    void dirtyCrashResidueIsDetectedAndNotAutomaticallyRemoved() throws Exception {
        Path main = createRepository();
        Path managedRoot = Files.createDirectory(tempDir.resolve("managed"));
        Path worker = managedRoot.resolve("worker-residue");
        String branch = "paicli/worker/task-1/worker-residue-a1";
        String baseSha = git(main, "rev-parse", "HEAD").stdout().trim();

        preflight(main, managedRoot, worker, branch, List.of());
        git(main, "worktree", "add", "-b", branch,
                worker.toString(), baseSha).requireSuccess();
        Files.writeString(worker.resolve("base.txt"), "valuable uncommitted work\n");

        boolean dirty = !git(worker, "status", "--porcelain=v1").stdout().isBlank();
        boolean hasDurableCandidate = false;

        assertTrue(dirty);
        assertFalse(safeForAutomaticCleanup(dirty, hasDurableCandidate));
        assertTrue(Files.exists(worker));
        assertEquals("valuable uncommitted work\n",
                Files.readString(worker.resolve("base.txt")));

        List<PorcelainWorktree> recovered = parsePorcelain(
                git(main, "worktree", "list", "--porcelain", "-z").stdout());
        assertTrue(recovered.stream().anyMatch(record -> samePath(record.path(), worker)));
    }

    private Path createRepository() throws Exception {
        Path main = Files.createDirectory(tempDir.resolve("main"));
        git(main, "init").requireSuccess();
        Files.writeString(main.resolve("base.txt"), "base\n");
        git(main, "add", "base.txt").requireSuccess();
        git(main, "-c", "user.name=PaiCLI R0",
                "-c", "user.email=paicli-r0@example.invalid",
                "commit", "-m", "base").requireSuccess();
        return main;
    }

    private void preflight(Path main, Path managedRoot, Path candidate,
                           String branch, List<Path> existingWorktrees)
            throws Exception {
        Path mainReal = main.toRealPath();
        Path managedReal = managedRoot.toRealPath();
        Path candidateAbsolute = candidate.toAbsolutePath().normalize();
        if (!candidateAbsolute.startsWith(managedReal)
                || candidateAbsolute.startsWith(mainReal)
                || mainReal.startsWith(candidateAbsolute)) {
            throw new IllegalArgumentException("candidate outside managed boundary");
        }
        if (Files.exists(candidateAbsolute)) {
            throw new IllegalStateException("worktree path collision");
        }
        for (Path existing : existingWorktrees) {
            Path existingReal = existing.toRealPath();
            if (candidateAbsolute.startsWith(existingReal)
                    || existingReal.startsWith(candidateAbsolute)) {
                throw new IllegalStateException("nested worktree collision");
            }
        }
        CommandResult branchCheck = git(main, "show-ref", "--verify", "--quiet",
                "refs/heads/" + branch);
        if (branchCheck.exitCode() == 0) {
            throw new IllegalStateException("worker branch collision");
        }
        if (branchCheck.exitCode() != 1) {
            throw new IllegalStateException("unable to check worker branch");
        }
    }

    private static Path resolveRequestedWorkspace(Path managedRoot, Path requested) {
        if (requested.isAbsolute()) {
            throw new IllegalArgumentException("absolute workspace path is forbidden");
        }
        for (Path part : requested) {
            if ("..".equals(part.toString())) {
                throw new IllegalArgumentException("parent traversal is forbidden");
            }
        }
        Path resolved = managedRoot.resolve(requested).toAbsolutePath().normalize();
        if (!resolved.startsWith(managedRoot.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("workspace escaped managed root");
        }
        return resolved;
    }

    private static boolean safeForAutomaticCleanup(
            boolean dirty, boolean hasDurableCandidate) {
        return !dirty && hasDurableCandidate;
    }

    private static boolean samePath(Path left, Path right) {
        try {
            return Files.isSameFile(left, right);
        } catch (IOException e) {
            return left.toAbsolutePath().normalize()
                    .equals(right.toAbsolutePath().normalize());
        }
    }

    private static void clearReadOnlyAttributes(Path root) throws IOException {
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

    private static List<PorcelainWorktree> parsePorcelain(String output) {
        String[] fields = output.split("\\u0000", -1);
        List<PorcelainWorktree> records = new ArrayList<>();
        Path path = null;
        String head = null;
        String branch = null;
        for (String field : fields) {
            if (field.isEmpty()) {
                if (path != null) {
                    records.add(new PorcelainWorktree(path, head, branch));
                }
                path = null;
                head = null;
                branch = null;
            } else if (field.startsWith("worktree ")) {
                path = Path.of(field.substring("worktree ".length()));
            } else if (field.startsWith("HEAD ")) {
                head = field.substring("HEAD ".length());
            } else if (field.startsWith("branch ")) {
                branch = field.substring("branch ".length());
            }
        }
        return records;
    }

    private static CommandResult git(Path directory, String... arguments)
            throws Exception {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(
                COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new IOException("git command timed out: " + command);
        }
        String output;
        try (var input = process.getInputStream()) {
            output = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        return new CommandResult(process.exitValue(), output, command);
    }

    private record PorcelainWorktree(Path path, String head, String branch) {
    }

    private record CommandResult(int exitCode, String stdout, List<String> command) {
        private void requireSuccess() {
            if (exitCode != 0) {
                throw new IllegalStateException(
                        "command failed " + command + ": " + stdout);
            }
        }
    }
}
