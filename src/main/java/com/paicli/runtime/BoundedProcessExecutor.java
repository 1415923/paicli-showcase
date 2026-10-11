package com.paicli.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Runs a prepared process while concurrently draining bounded stdout and stderr. */
public final class BoundedProcessExecutor {
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration TERMINATION_WAIT = Duration.ofSeconds(2);

    private final int maxOutputBytes;
    private final OwnedProcessRegistry processRegistry;

    public BoundedProcessExecutor(int maxOutputBytes) {
        this(maxOutputBytes, null);
    }

    public BoundedProcessExecutor(int maxOutputBytes, OwnedProcessRegistry processRegistry) {
        if (maxOutputBytes < 1) {
            throw new IllegalArgumentException("maxOutputBytes must be positive");
        }
        this.maxOutputBytes = maxOutputBytes;
        this.processRegistry = processRegistry;
    }

    public Result execute(ProcessBuilder builder, Duration timeout) {
        Objects.requireNonNull(builder, "builder");
        Objects.requireNonNull(timeout, "timeout");
        if (builder.command().isEmpty()) {
            throw new IllegalArgumentException("process command must not be empty");
        }
        if (timeout.isZero() || timeout.isNegative() || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("timeout must be between 1ms and 5 minutes");
        }

        builder.redirectErrorStream(false);
        Process process = null;
        OwnedProcessRegistry.Registration registration = null;
        com.paicli.diagnostic.StageTrace.event(
                "CHILD_PROCESS_CREATE_START", false,
                "purpose", "BOUNDED_PROCESS",
                "executable_basename",
                com.paicli.diagnostic.StageTrace.executableBasename(builder.command()),
                "argument_count", Math.max(0, builder.command().size() - 1),
                "argument_hash",
                com.paicli.diagnostic.StageTrace.hashArguments(builder.command()));
        try {
            process = builder.start();
            com.paicli.diagnostic.StageTrace.event(
                    "CHILD_PROCESS_CREATE_END", false,
                    "purpose", "BOUNDED_PROCESS",
                    "outcome", "STARTED",
                    "pid", com.paicli.diagnostic.StageTrace.processId(process));
            if (processRegistry != null) registration = processRegistry.register(process);
            process.getOutputStream().close();
        } catch (IOException | RuntimeException e) {
            if (process != null) terminate(process, new ProcessTree(process));
            if (registration != null) registration.close();
            com.paicli.diagnostic.StageTrace.event(
                    "CHILD_PROCESS_CREATE_END", false,
                    "purpose", "BOUNDED_PROCESS",
                    "outcome", "ERROR",
                    "error_type", e.getClass().getName());
            return new Result(Status.START_FAILED, null, "", safeMessage(e), false, false);
        }

        AtomicReference<Capture> stdout = new AtomicReference<>();
        AtomicReference<Capture> stderr = new AtomicReference<>();
        Thread out = capture(process.getInputStream(), stdout, "paicli-process-stdout");
        Thread err = capture(process.getErrorStream(), stderr, "paicli-process-stderr");
        ProcessTree tree = new ProcessTree(process);
        try {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (process.isAlive() && System.nanoTime() < deadline) {
                tree.observe();
                if (tree.overflow) break;
                process.waitFor(Math.min(25, Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()))), TimeUnit.MILLISECONDS);
            }
            if (process.isAlive() || tree.overflow) {
                boolean cleaned=terminate(process, tree);
                closeStreams(process);
                joinBounded(out);
                joinBounded(err);
                return result(cleaned && !tree.overflow?Status.TIMED_OUT:Status.CLEANUP_FAILED, null, stdout.get(), stderr.get());
            }
            // A helper may terminate its shell while leaving a grandchild alive. Retain observed
            // identities across parent exit, and release their inherited pipes before draining.
            if (!terminate(process, tree)) return result(Status.CLEANUP_FAILED, process.exitValue(), stdout.get(), stderr.get());
            joinBounded(out);
            joinBounded(err);
            if (out.isAlive() || err.isAlive()) {
                closeStreams(process);
                joinBounded(out);
                joinBounded(err);
                return result(Status.CAPTURE_FAILED, process.exitValue(), stdout.get(), stderr.get());
            }
            Capture outCapture = stdout.get();
            Capture errCapture = stderr.get();
            if (hasError(outCapture) || hasError(errCapture)) {
                return result(Status.CAPTURE_FAILED, process.exitValue(), outCapture, errCapture);
            }
            return result(process.exitValue() == 0 ? Status.SUCCEEDED : Status.NON_ZERO,
                    process.exitValue(), outCapture, errCapture);
        } catch (InterruptedException e) {
            boolean cleaned=terminate(process, tree);
            closeStreams(process);
            joinQuietly(out);
            joinQuietly(err);
            Thread.currentThread().interrupt();
            return result(cleaned?Status.INTERRUPTED:Status.CLEANUP_FAILED, null, stdout.get(), stderr.get());
        } finally {
            closeStreams(process);
            if (process.isAlive() || tree.hasLiveChildren()) terminate(process, tree);
            if (registration != null) registration.close();
        }
    }

    private Thread capture(InputStream stream, AtomicReference<Capture> destination, String name) {
        Thread thread = new Thread(() -> destination.set(read(stream)), name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private Capture read(InputStream stream) {
        ByteArrayOutputStream kept = new ByteArrayOutputStream(Math.min(maxOutputBytes, 8192));
        long observed = 0;
        byte[] buffer = new byte[4096];
        try (stream) {
            int count;
            while ((count = stream.read(buffer)) != -1) {
                observed += count;
                int remaining = maxOutputBytes - kept.size();
                if (remaining > 0) {
                    kept.write(buffer, 0, Math.min(remaining, count));
                }
            }
            return new Capture(kept.toByteArray(), observed, null);
        } catch (IOException e) {
            return new Capture(kept.toByteArray(), observed, e);
        }
    }

    private Result result(Status status, Integer exitCode, Capture stdout, Capture stderr) {
        return new Result(status, exitCode, text(stdout), text(stderr), truncated(stdout), truncated(stderr));
    }

    private String text(Capture capture) {
        if (capture == null || capture.bytes().length == 0) {
            return "";
        }
        String value = new String(capture.bytes(), StandardCharsets.UTF_8);
        if (truncated(capture)) {
            value += "\n...[stream truncated; observed_bytes=" + capture.observedBytes() + "]";
        }
        return value;
    }

    private boolean truncated(Capture capture) {
        return capture != null && capture.observedBytes() > capture.bytes().length;
    }

    private boolean hasError(Capture capture) {
        return capture != null && capture.error() != null;
    }

    private void joinBounded(Thread thread) throws InterruptedException {
        thread.join(TERMINATION_WAIT.toMillis());
    }

    private void joinQuietly(Thread thread) {
        try {
            thread.join(TERMINATION_WAIT.toMillis());
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean terminate(Process process, ProcessTree tree) {
        boolean interrupted = Thread.interrupted();
        try {
            tree.observe();
            tree.killChildren();
            if (process.isAlive()) process.destroy();
            long deadline = System.nanoTime() + TERMINATION_WAIT.toNanos();
            while ((process.isAlive() || tree.hasLiveChildren()) && System.nanoTime() < deadline) {
                tree.observe();
                tree.killChildren();
                if (process.isAlive()) process.destroyForcibly();
                try { Thread.sleep(10); }
                catch (InterruptedException e) { interrupted = true; }
            }
            return !process.isAlive() && !tree.hasLiveChildren() && !tree.overflow;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
    /** Bounded observations of this command's descendants, never a global process scan. */
    private static final class ProcessTree {
        private final Process root;
        private final java.util.Map<Long,ProcessHandle> children = new java.util.LinkedHashMap<>();
        private boolean overflow;
        ProcessTree(Process root) { this.root = root; }
        void observe() {
            var parents = new java.util.ArrayList<>(children.values());
            parents.add(root.toHandle());
            for (var parent : parents) {
                if (!parent.isAlive()) continue;
                try (var descendants = parent.descendants()) {
                    var iterator = descendants.iterator();
                    while (iterator.hasNext()) {
                        var child = iterator.next();
                        if (!children.containsKey(child.pid()) && children.size() >= 4096) { overflow = true; return; }
                        children.put(child.pid(), child);
                    }
                } catch (UnsupportedOperationException | SecurityException unavailable) { overflow = true; }
            }
        }
        boolean hasLiveChildren() { return children.values().stream().anyMatch(ProcessHandle::isAlive); }
        void killChildren() { children.values().forEach(child -> { if (child.isAlive()) child.destroyForcibly(); }); }
    }
    private void closeStreams(Process process) {
        try { process.getInputStream().close(); } catch (IOException ignored) { }
        try { process.getErrorStream().close(); } catch (IOException ignored) { }
        try { process.getOutputStream().close(); } catch (IOException ignored) { }
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    public enum Status { SUCCEEDED, NON_ZERO, TIMED_OUT, START_FAILED, CAPTURE_FAILED, INTERRUPTED, CLEANUP_FAILED }

    public record Result(Status status, Integer exitCode, String stdout, String stderr,
                         boolean stdoutTruncated, boolean stderrTruncated) {
        public String combinedOutput() {
            if (stdout.isBlank()) return stderr;
            if (stderr.isBlank()) return stdout;
            return stdout + "\n" + stderr;
        }
    }

    private record Capture(byte[] bytes, long observedBytes, IOException error) { }
}
