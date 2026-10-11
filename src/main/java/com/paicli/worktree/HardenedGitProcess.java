package com.paicli.worktree;

import com.paicli.runtime.PersistenceSanitizer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Executes an allow-listed Git operation without a command shell. */
public final class HardenedGitProcess implements GitProcess {
    private static final int MAX_TIMEOUT_SECONDS = 120;
    private final String executable;
    private final Path hooksDirectory;
    private final Duration timeout;
    private final int maxOutputBytes;
    private final PersistenceSanitizer sanitizer;
    private final Launcher launcher;

    public HardenedGitProcess(Path hooksDirectory, Duration timeout, int maxOutputBytes) {
        this("git", hooksDirectory, timeout, maxOutputBytes, ProcessBuilder::start);
    }

    HardenedGitProcess(String executable, Path hooksDirectory, Duration timeout,
                       int maxOutputBytes, Launcher launcher) {
        this.executable = Objects.requireNonNull(executable, "executable");
        this.hooksDirectory = Objects.requireNonNull(hooksDirectory, "hooksDirectory")
                .toAbsolutePath().normalize();
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(Duration.ofSeconds(MAX_TIMEOUT_SECONDS)) > 0) {
            throw new IllegalArgumentException("Git timeout must be between 1 and 120 seconds");
        }
        if (maxOutputBytes < 256 || maxOutputBytes > 262_144) {
            throw new IllegalArgumentException("Git output limit must be between 256 and 262144 bytes");
        }
        this.maxOutputBytes = maxOutputBytes;
        this.sanitizer = new PersistenceSanitizer();
        this.launcher = Objects.requireNonNull(launcher, "launcher");
    }

    @Override
    public Result execute(Path workingDirectory, Operation operation, String... values) {
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(operation, "operation");
        List<String> command = buildCommand(operation, List.of(values));
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(workingDirectory.toAbsolutePath().normalize().toFile());
        replaceEnvironment(builder.environment());
        long started = System.nanoTime();
        Process process;
        com.paicli.diagnostic.StageTrace.event(
                "CHILD_PROCESS_CREATE_START", false,
                "purpose", "HARDENED_GIT",
                "executable_basename",
                com.paicli.diagnostic.StageTrace.executableBasename(command),
                "argument_count", Math.max(0, command.size() - 1),
                "argument_hash",
                com.paicli.diagnostic.StageTrace.hashArguments(command));
        try {
            process = launcher.start(builder);
            com.paicli.diagnostic.StageTrace.event(
                    "CHILD_PROCESS_CREATE_END", false,
                    "purpose", "HARDENED_GIT",
                    "outcome", "STARTED",
                    "pid", com.paicli.diagnostic.StageTrace.processId(process));
            process.getOutputStream().close();
        } catch (IOException | RuntimeException e) {
            com.paicli.diagnostic.StageTrace.event(
                    "CHILD_PROCESS_CREATE_END", false,
                    "purpose", "HARDENED_GIT",
                    "outcome", "ERROR",
                    "error_type", e.getClass().getName());
            return result(Status.ERROR, null, started, null, safeMessage(e), command);
        }

        AtomicReference<Capture> stdout = new AtomicReference<>();
        AtomicReference<Capture> stderr = new AtomicReference<>();
        Thread out = capture(process.getInputStream(), stdout, "paicli-git-stdout");
        Thread err = capture(process.getErrorStream(), stderr, "paicli-git-stderr");
        boolean machineOutput = operation == Operation.STATUS
                || operation == Operation.WORKTREE_LIST || operation == Operation.TREE_LIST
                || operation == Operation.CACHED_NAMES || operation == Operation.CACHED_RAW
                || operation == Operation.UNMERGED;
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                terminate(process);
                closeStreams(process);
                join(out);
                join(err);
                return result(Status.TIMED_OUT, null, started, excerpt(stdout.get(), machineOutput),
                        merge(excerpt(stderr.get()), "Git operation timed out"), command);
            }
            join(out);
            join(err);
            IOException captureError = firstError(stdout.get(), stderr.get());
            if (captureError != null) {
                return result(Status.ERROR, process.exitValue(), started, excerpt(stdout.get(), machineOutput),
                        merge(excerpt(stderr.get()), "Git output capture failed"), command);
            }
            return result(process.exitValue() == 0 ? Status.SUCCEEDED : Status.NON_ZERO,
                    process.exitValue(), started, excerpt(stdout.get(), machineOutput),
                    excerpt(stderr.get()), command);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            terminate(process);
            return result(Status.ERROR, null, started, excerpt(stdout.get(), machineOutput),
                    merge(excerpt(stderr.get()), "Git operation interrupted"), command);
        } finally {
            closeStreams(process);
            if (process.isAlive()) terminate(process);
        }
    }

    List<String> buildCommand(Operation operation, List<String> values) {
        operation.validate(values);
        List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("-c");
        command.add("core.hooksPath=" + hooksDirectory);
        command.addAll(operation.arguments(values));
        return List.copyOf(command);
    }

    static Map<String, String> sanitizedEnvironment(Map<String, String> inherited) {
        Map<String, String> sanitized = new HashMap<>();
        inherited.forEach((key, value) -> {
            if (!key.toUpperCase(java.util.Locale.ROOT).startsWith("GIT_")) {
                sanitized.put(key, value);
            }
        });
        sanitized.put("GIT_TERMINAL_PROMPT", "0");
        sanitized.put("GIT_CONFIG_NOSYSTEM", "1");
        sanitized.put("GIT_AUTHOR_NAME", "PaiCLI Runtime");
        sanitized.put("GIT_AUTHOR_EMAIL", "runtime@paicli.local");
        sanitized.put("GIT_COMMITTER_NAME", "PaiCLI Runtime");
        sanitized.put("GIT_COMMITTER_EMAIL", "runtime@paicli.local");
        sanitized.put("GIT_EDITOR", "true");
        return sanitized;
    }

    private void replaceEnvironment(Map<String, String> environment) {
        Map<String, String> safe = sanitizedEnvironment(environment);
        safe.put("GIT_CONFIG_GLOBAL",
                hooksDirectory.resolve(".paicli-empty-gitconfig").toString());
        environment.clear();
        environment.putAll(safe);
    }

    private Thread capture(InputStream stream, AtomicReference<Capture> destination, String name) {
        Thread thread = new Thread(() -> destination.set(read(stream)), name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private Capture read(InputStream stream) {
        ByteArrayOutputStream kept = new ByteArrayOutputStream();
        long observed = 0;
        byte[] buffer = new byte[4096];
        try (stream) {
            int count;
            while ((count = stream.read(buffer)) != -1) {
                observed += count;
                int remaining = maxOutputBytes - kept.size();
                if (remaining > 0) kept.write(buffer, 0, Math.min(remaining, count));
            }
            return new Capture(kept.toByteArray(), observed, null);
        } catch (IOException e) {
            return new Capture(kept.toByteArray(), observed, e);
        }
    }

    private String excerpt(Capture capture) {
        return excerpt(capture, false);
    }

    private String excerpt(Capture capture, boolean preserveNul) {
        if (capture == null) return null;
        String text = new String(capture.bytes(), StandardCharsets.UTF_8);
        if (capture.observedBytes() > capture.bytes().length) {
            text += "\n...[stream truncated; observed_bytes=" + capture.observedBytes() + "]";
        }
        if (text.isEmpty()) return null;
        if (!preserveNul) return sanitizer.sanitizeAndTruncate(text, maxOutputBytes);
        String[] fields = text.split("\\u0000", -1);
        for (int i = 0; i < fields.length; i++) fields[i] = sanitizer.sanitize(fields[i]);
        return String.join("\0", fields);
    }

    private Result result(Status status, Integer exitCode, long started, String stdout,
                          String stderr, List<String> command) {
        return new Result(status, exitCode,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), stdout, stderr,
                command.get(3));
    }

    private String merge(String first, String second) {
        if (first == null || first.isBlank()) return second;
        return sanitizer.sanitizeAndTruncate(first + "\n" + second, maxOutputBytes);
    }

    private IOException firstError(Capture first, Capture second) {
        if (first != null && first.error() != null) return first.error();
        return second == null ? null : second.error();
    }

    private void join(Thread thread) throws InterruptedException {
        thread.join();
    }

    private void terminate(Process process) {
        try {
            process.descendants().forEach(handle -> {
                if (handle.isAlive()) handle.destroyForcibly();
            });
        } catch (UnsupportedOperationException ignored) {
            // Some injected/test Process implementations have no ProcessHandle.
        }
        process.destroyForcibly();
        try {
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeStreams(Process process) {
        try { process.getInputStream().close(); } catch (IOException ignored) { }
        try { process.getErrorStream().close(); } catch (IOException ignored) { }
        try { process.getOutputStream().close(); } catch (IOException ignored) { }
    }

    private String safeMessage(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
    }

    public enum Status { SUCCEEDED, NON_ZERO, TIMED_OUT, ERROR }

    public enum Operation {
        TOP_LEVEL(0, values -> List.of("rev-parse", "--show-toplevel")),
        COMMON_DIR(0, values -> List.of("rev-parse", "--git-common-dir")),
        GIT_DIR(0, values -> List.of("rev-parse", "--git-dir")),
        HEAD(0, values -> List.of("rev-parse", "--verify", "HEAD")),
        VERIFY_COMMIT(1, values -> List.of("cat-file", "-e", values.get(0) + "^{commit}")),
        STATUS(0, values -> List.of("status", "--porcelain=v1", "-z", "--untracked-files=normal")),
        WORKTREE_DIFF(0, values -> List.of("diff", "--no-ext-diff", "--")),
        STAGE_ALL(0, values -> List.of("add", "-A", "--")),
        CACHED_NAMES(0, values -> List.of("diff", "--cached", "--name-only", "-z", "--")),
        CACHED_RAW(0, values -> List.of("diff", "--cached", "--raw", "-z", "--")),
        WRITE_TREE(0, values -> List.of("write-tree")),
        COMMIT_TREE(3, values -> List.of("-c", "commit.gpgsign=false", "commit-tree",
                values.get(0), "-p", values.get(1), "-m", values.get(2))),
        COMMIT_TREE_TWO_PARENTS(4, values -> List.of("-c", "commit.gpgsign=false",
                "commit-tree", values.get(0), "-p", values.get(1), "-p", values.get(2),
                "-m", values.get(3))),
        UPDATE_REF(3, values -> List.of("update-ref", "refs/heads/" + values.get(0),
                values.get(1), values.get(2))),
        TREE_SHA(1, values -> List.of("rev-parse", "--verify", values.get(0) + "^{tree}")),
        COMMIT_PARENTS(1, values -> List.of("rev-list", "--parents", "-n", "1", values.get(0))),
        UNMERGED(0, values -> List.of("diff", "--name-only", "--diff-filter=U", "-z")),
        CHECK_BRANCH(1, values -> List.of("check-ref-format", "--branch", values.get(0))),
        REF_EXISTS(1, values -> List.of("show-ref", "--verify", "--quiet", "refs/heads/" + values.get(0))),
        REF_SHA(1, values -> List.of("rev-parse", "--verify", "refs/heads/" + values.get(0))),
        WORKTREE_LIST(0, values -> List.of("worktree", "list", "--porcelain", "-z")),
        WORKTREE_ADD(3, values -> List.of("worktree", "add", "-b", values.get(0), values.get(1), values.get(2))),
        MERGE_NO_COMMIT(1, values -> List.of("-c", "commit.gpgsign=false", "merge",
                "--no-ff", "--no-commit", "--no-edit", values.get(0))),
        FAST_FORWARD_ONLY(1, values -> List.of("merge", "--ff-only", "--no-edit", values.get(0))),
        CURRENT_BRANCH(0, values -> List.of("symbolic-ref", "--short", "HEAD")),
        DELETE_REF_SAFE(2, values -> List.of("update-ref", "-d",
                "refs/heads/" + values.get(0), values.get(1))),
        WORKTREE_REMOVE(1, values -> List.of("worktree", "remove", values.get(0))),
        BRANCH_DELETE(1, values -> List.of("branch", "-D", values.get(0))),
        LOCAL_FILTER_CONFIG(0, values -> List.of("config", "--get-regexp",
                "^filter\\..*\\.(smudge|process)$")),
        LOCAL_SPARSE_CONFIG(0, values -> List.of("config", "--get-regexp",
                "^(core\\.sparsecheckout|extensions\\.worktreeconfig)$")),
        TREE_LIST(1, values -> List.of("ls-tree", "-r", "-z", values.get(0))),
        SHOW_FILE(2, values -> List.of("show", values.get(0) + ":" + values.get(1)));

        private final int valueCount;
        private final Arguments arguments;

        Operation(int valueCount, Arguments arguments) {
            this.valueCount = valueCount;
            this.arguments = arguments;
        }

        void validate(List<String> values) {
            if (values.size() != valueCount || values.stream().anyMatch(value -> value == null
                    || value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)) {
                throw new IllegalArgumentException("Invalid values for Git operation " + name());
            }
        }

        List<String> arguments(List<String> values) {
            return arguments.build(values);
        }
    }

    public record Result(Status status, Integer exitCode, long durationMillis,
                         String stdoutExcerpt, String stderrExcerpt, String operation) {
        public boolean succeeded() { return status == Status.SUCCEEDED; }
    }

    @FunctionalInterface
    interface Launcher { Process start(ProcessBuilder builder) throws IOException; }

    @FunctionalInterface
    private interface Arguments { List<String> build(List<String> values); }

    private record Capture(byte[] bytes, long observedBytes, IOException error) { }
}
