package com.paicli.distribution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

@EnabledOnOs(OS.WINDOWS)
class WindowsLauncherProcessLifecycleIT {
    private static final Duration START_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration EXIT_TIMEOUT = Duration.ofSeconds(20);
    private static final int MAX_CAPTURE_CHARS = 512 * 1024;
    private static final String IDLE_STATUS_MARKER = "Runtime     Idle";

    @TempDir
    Path tempDir;

    @Test
    void powershellLauncherWaitsForOwnedJavaAndExitsWithoutResidualProcesses()
            throws Exception {
        Path project = Path.of("").toAbsolutePath().normalize();
        Path packagedDist = project.resolve("target/dist/paicli-1.2.0");
        Path packagedJar = packagedDist.resolve("lib/paicli-1.2.0.jar");
        Path packagedLauncher = packagedDist.resolve("bin/paicli.ps1");
        assertTrue(Files.isRegularFile(packagedJar), "current package JAR is required");
        assertTrue(Files.isRegularFile(packagedLauncher), "current PowerShell launcher is required");
        assertCurrentArtifact(project, packagedJar);

        Path candidateRoot = tempDir.resolve("current-source-candidate").toAbsolutePath();
        copyTree(packagedDist, candidateRoot);
        Path launcherScript = candidateRoot.resolve("bin/paicli.ps1");
        Path candidateJar = candidateRoot.resolve("lib/paicli-1.2.0.jar");
        assertEquals(-1L, Files.mismatch(packagedLauncher, launcherScript));
        assertEquals(-1L, Files.mismatch(packagedJar, candidateJar));

        Path powershell = windowsPowerShell();
        ProcessBuilder builder = new ProcessBuilder(powershell.toString(),
                "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                launcherScript.toString());
        builder.directory(candidateRoot.toFile());
        Path isolatedHome = tempDir.resolve("isolated-home");
        Files.createDirectories(isolatedHome);
        builder.environment().put("HOME", isolatedHome.toString());
        builder.environment().put("USERPROFILE", isolatedHome.toString());
        builder.environment().remove("_JAVA_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        builder.environment().put("JAVA_TOOL_OPTIONS", "-Duser.home=\""
                + isolatedHome + "\"");

        Instant launcherStarted = Instant.now();
        Process launcher = builder.start();
        AsyncCapture stdout = new AsyncCapture("paicli-harness-stdout",
                launcher.getInputStream());
        AsyncCapture stderr = new AsyncCapture("paicli-harness-stderr",
                launcher.getErrorStream());
        Writer stdin = new OutputStreamWriter(
                launcher.getOutputStream(), StandardCharsets.UTF_8);
        ProcessTimeline timeline = new ProcessTimeline(launcher);
        Throwable primaryFailure = null;
        try {
            Instant readinessReceived = awaitOutputWithTimeline(stdout,
                    "PaiCLI 1.2.0 Interactive", timeline,
                    ObservationPoint.READINESS, START_TIMEOUT);
            CapturedProcessRecord readinessLeaf =
                    timeline.requireLinearProcessTree(
                            ObservationPoint.READINESS);
            assertTrue(launcher.isAlive(),
                    "PowerShell launcher exited while its Java child was alive");
            assertThrows(IllegalThreadStateException.class, launcher::exitValue,
                    "live PowerShell launcher must not have returned an exit code");

            timeline.poll(ObservationPoint.OBSERVE_ONLY);
            timeline.requireCurrentLinearProcessTree("before /status");
            stdin.write("/status" + System.lineSeparator());
            stdin.flush();
            Instant statusReceived = awaitOutputWithTimeline(stdout,
                    IDLE_STATUS_MARKER, timeline,
                    ObservationPoint.STATUS_RESPONSE, START_TIMEOUT);
            CapturedProcessRecord statusLeaf =
                    timeline.requireLinearProcessTree(
                            ObservationPoint.STATUS_RESPONSE);
            assertTrue(launcher.isAlive());
            assertThrows(IllegalThreadStateException.class, launcher::exitValue,
                    "launcher returned before /exit was submitted");

            timeline.poll(ObservationPoint.PRE_EXIT);
            CapturedProcessRecord preExitLeaf =
                    timeline.requireLinearProcessTree(
                            ObservationPoint.PRE_EXIT);
            ExitOrderMonitor exitOrder = ExitOrderMonitor.capture(
                    launcher, timeline.capturedHandles());

            stdin.write("/exit" + System.lineSeparator());
            stdin.flush();
            Instant exitSubmitted = Instant.now();

            ExitTimeline exits = awaitOrderedExit(
                    launcher, timeline, exitOrder, EXIT_TIMEOUT);
            stdin.close();

            stdout.awaitClosed(EXIT_TIMEOUT);
            stderr.awaitClosed(EXIT_TIMEOUT);
            assertEquals(0, launcher.exitValue(), stderr.text());
            timeline.assertAllCapturedExited();
            timeline.captureFinalTree();
            assertFalse(ProcessHandle.of(launcher.pid())
                    .map(ProcessHandle::isAlive).orElse(false));
            String completedStdout = stdout.text();
            String completedStderr = stderr.text();
            assertTrue(completedStdout.contains(IDLE_STATUS_MARKER));
            assertTrue(completedStdout.contains("Session closed."));
            assertTrue(Files.isRegularFile(isolatedHome.resolve(
                            ".paicli/logs/paicli.log")),
                    "packaged launcher must write logs only under isolated user.home");
            assertNoStartupFailure(completedStderr);
            assertEquals(completedStdout, stdout.text(),
                    "stdout changed after its drain reached EOF");
            assertEquals(completedStderr, stderr.text(),
                    "stderr changed after its drain reached EOF");

            System.out.printf(Locale.ROOT,
                    "WINDOWS_LAUNCHER_TIMELINE launcher_started=%s java_observed=%s "
                            + "readiness_received=%s status_received=%s "
                            + "exit_submitted=%s java_exited=%s "
                            + "launcher_exited=%s launcher_pid=%d "
                            + "readiness_leaf_pid=%d status_leaf_pid=%d "
                            + "pre_exit_leaf_pid=%d%n",
                    launcherStarted, timeline.firstJavaObservedAt(),
                    readinessReceived,
                    statusReceived, exitSubmitted,
                    exits.javaExitObservedAt(), exits.launcherExitObservedAt(),
                    launcher.pid(), readinessLeaf.pid(), statusLeaf.pid(),
                    preExitLeaf.pid());
            timeline.printDiagnostics(System.out);
        } catch (Exception error) {
            primaryFailure = error;
            timeline.printDiagnostics(System.err);
            throw error;
        } catch (AssertionError error) {
            primaryFailure = error;
            timeline.printDiagnostics(System.err);
            throw error;
        } finally {
            List<Throwable> cleanupFailures = new ArrayList<>();
            try {
                CleanupResult cleanup = cleanupOwnedProcesses(
                        launcher, timeline.capturedHandles(), timeline);
                cleanupFailures.addAll(cleanup.failures());
                if (cleanup.forced()) {
                    timeline.poll(ObservationPoint.OBSERVE_ONLY);
                    timeline.printDiagnostics(System.err);
                    cleanupFailures.add(new AssertionError(
                            "FORCED_HARNESS_CLEANUP: "
                                    + String.join(", ", cleanup.actions())));
                }
            } catch (Exception | AssertionError cleanupFailure) {
                cleanupFailures.add(cleanupFailure);
            }
            for (AsyncCapture capture : List.of(stdout, stderr)) {
                try {
                    capture.awaitClosed(EXIT_TIMEOUT);
                } catch (Exception | AssertionError drainFailure) {
                    cleanupFailures.add(drainFailure);
                }
            }
            if (!cleanupFailures.isEmpty()) {
                Throwable firstCleanupFailure = cleanupFailures.getFirst();
                for (int index = 1; index < cleanupFailures.size(); index++) {
                    firstCleanupFailure.addSuppressed(cleanupFailures.get(index));
                }
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(firstCleanupFailure);
                } else if (firstCleanupFailure instanceof Exception exception) {
                    throw exception;
                } else {
                    throw (AssertionError) firstCleanupFailure;
                }
            }
        }
    }

    private static void assertCurrentArtifact(Path project, Path packagedJar)
            throws Exception {
        Path currentClass = project.resolve(
                "target/classes/com/paicli/interactive/shell/JLineShellInputReader.class");
        assertTrue(Files.isRegularFile(currentClass), "compiled Batch 1 class is required");
        try (ZipFile jar = new ZipFile(packagedJar.toFile())) {
            var entry = jar.getEntry(
                    "com/paicli/interactive/shell/JLineShellInputReader.class");
            assertNotNull(entry, "packaged JAR must contain the Batch 1 input reader");
            try (InputStream packagedClass = jar.getInputStream(entry)) {
                assertArrayEquals(Files.readAllBytes(currentClass), packagedClass.readAllBytes(),
                        "distribution JAR is not from the current compiled source");
            }
        }
    }

    private static void copyTree(Path source, Path target) throws Exception {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static Path windowsPowerShell() {
        String systemRoot = System.getenv("SystemRoot");
        assertNotNull(systemRoot, "SystemRoot is required on Windows");
        Path powershell = Path.of(systemRoot, "System32", "WindowsPowerShell",
                "v1.0", "powershell.exe");
        assertTrue(Files.isRegularFile(powershell), "Windows PowerShell is required");
        return powershell;
    }

    private static Instant awaitOutputWithTimeline(AsyncCapture output,
            String expected, ProcessTimeline timeline,
            ObservationPoint observationPoint, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            timeline.poll(ObservationPoint.OBSERVE_ONLY);
            if (output.contains(expected)) {
                Instant observedAt = Instant.now();
                timeline.poll(observationPoint);
                return observedAt;
            }
            output.assertWaitingCanContinue(expected);
            Thread.sleep(25);
        }
        fail("timed out waiting for output: " + expected
                + System.lineSeparator() + output.text());
        throw new AssertionError("unreachable");
    }

    private static ProcessInfoSnapshot snapshot(ProcessHandle.Info info) {
        Optional<List<String>> arguments = info.arguments()
                .map(values -> List.copyOf(java.util.Arrays.asList(values)));
        return new ProcessInfoSnapshot(info.command(), arguments,
                info.commandLine(), info.startInstant());
    }

    private static boolean isJavaCandidate(ProcessInfoSnapshot info) {
        return info.command()
                .filter(WindowsLauncherProcessLifecycleIT::isJavaCommand)
                .isPresent();
    }

    private static boolean isJavaCommand(String command) {
        String normalized = command.replace('\\', '/');
        String basename = normalized.substring(normalized.lastIndexOf('/') + 1);
        return "java.exe".equalsIgnoreCase(basename)
                || "java".equalsIgnoreCase(basename);
    }

    private static ExitTimeline awaitOrderedExit(Process launcher,
            ProcessTimeline timeline, ExitOrderMonitor exitOrder,
            Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            timeline.poll(ObservationPoint.OBSERVE_ONLY);
            exitOrder.register(timeline.capturedHandles());
            List<CapturedProcessRecord> aliveRelated =
                    timeline.aliveCapturedProcesses();
            boolean launcherAlive = launcher.isAlive();
            if (!launcherAlive && !aliveRelated.isEmpty()) {
                fail("LAUNCHER_EXITED_WHILE_RELATED_JAVA_REMAINED_ALIVE: "
                        + aliveRelated.stream().map(record ->
                                Long.toString(record.pid())).toList());
            }
            if (aliveRelated.isEmpty() && !launcherAlive
                    && exitOrder.completionEvidenceReady()) {
                ExitOrderEvidence evidence =
                        exitOrder.requireAllRelatedBeforeLauncher();
                return new ExitTimeline(timeline.lastJavaExitObservedAt(),
                        evidence.launcher().observedAt());
            }
            Thread.sleep(25);
        }
        fail("timed out waiting for exact PaiCLI Java and launcher processes to exit");
        throw new AssertionError("unreachable");
    }

    private static CleanupResult cleanupOwnedProcesses(Process launcher,
            List<ProcessHandle> capturedHandles, ProcessTimeline timeline) {
        List<String> actions = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        AtomicBoolean forced = new AtomicBoolean(false);
        try {
            launcher.getOutputStream().close();
        } catch (Exception ignored) {
            // The launcher may already have closed its inherited stdin.
        }

        java.util.Map<Long, ProcessHandle> ownedDescendants =
                new java.util.LinkedHashMap<>();
        for (ProcessHandle handle : capturedHandles) {
            ownedDescendants.put(handle.pid(), handle);
        }
        if (launcher.isAlive()) {
            try (var descendants = launcher.toHandle().descendants()) {
                descendants.forEach(handle ->
                        ownedDescendants.putIfAbsent(handle.pid(), handle));
            }
        }

        for (ProcessHandle handle : ownedDescendants.values()) {
            try {
                awaitHandle(handle, Duration.ofSeconds(1));
            } catch (Exception cleanupFailure) {
                failures.add(cleanupFailure);
            }
        }

        for (ProcessHandle handle : ownedDescendants.values()) {
            long pid = handle.pid();
            if (!handle.isAlive()) continue;
            actions.add("graceful-descendant-destroy:" + pid + ":"
                    + timeline.classificationLabel(pid));
            try {
                handle.destroy();
                awaitHandle(handle, Duration.ofSeconds(2));
                if (handle.isAlive()) {
                    actions.add("forced-descendant-destroy:" + pid + ":"
                            + timeline.classificationLabel(pid));
                    forced.set(true);
                    handle.destroyForcibly();
                    awaitHandle(handle, Duration.ofSeconds(5));
                }
            } catch (Exception | AssertionError cleanupFailure) {
                failures.add(cleanupFailure);
            }
        }

        if (launcher.isAlive()) {
            try {
                launcher.waitFor(1, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                failures.add(error);
            }
        }
        if (launcher.isAlive()) {
            actions.add("graceful-launcher-destroy:" + launcher.pid());
            try {
                launcher.destroy();
                launcher.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                failures.add(error);
            }
        }
        if (launcher.isAlive()) {
            actions.add("forced-launcher-destroy:" + launcher.pid());
            forced.set(true);
            try {
                launcher.destroyForcibly();
                launcher.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                failures.add(error);
            }
        }
        if (!actions.isEmpty()) {
            System.err.println("WINDOWS_HARNESS_CLEANUP " + String.join(", ", actions));
        }
        return new CleanupResult(forced.get(), List.copyOf(actions),
                List.copyOf(failures));
    }

    private static void awaitHandle(ProcessHandle handle, Duration timeout)
            throws Exception {
        if (handle.isAlive()) {
            try {
                handle.onExit().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException ignored) {
                // The caller decides whether the next exact-PID step must be forced.
            }
        }
    }

    private static final class AsyncCapture {
        private final Object monitor = new Object();
        private final StringBuilder content = new StringBuilder();
        private final Thread reader;
        private boolean closed;
        private Throwable failure;

        private AsyncCapture(String name, InputStream input) {
            reader = Thread.ofVirtual().name(name).start(() -> drain(input));
        }

        private void drain(InputStream input) {
            try (InputStreamReader source = new InputStreamReader(
                    input, StandardCharsets.UTF_8)) {
                char[] buffer = new char[2048];
                int read;
                while ((read = source.read(buffer)) >= 0) {
                    synchronized (monitor) {
                        int accepted = Math.min(read,
                                Math.max(0, MAX_CAPTURE_CHARS - content.length()));
                        if (accepted > 0) content.append(buffer, 0, accepted);
                        monitor.notifyAll();
                    }
                }
            } catch (Throwable error) {
                synchronized (monitor) {
                    failure = error;
                    monitor.notifyAll();
                }
            } finally {
                synchronized (monitor) {
                    closed = true;
                    monitor.notifyAll();
                }
            }
        }

        private void awaitContains(String expected, Duration timeout)
                throws Exception {
            long deadline = System.nanoTime() + timeout.toNanos();
            synchronized (monitor) {
                while (content.indexOf(expected) < 0) {
                    if (failure != null) {
                        throw new AssertionError("output drain failed", failure);
                    }
                    if (closed) {
                        fail("process output closed before containing: " + expected
                                + System.lineSeparator() + content);
                    }
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        fail("timed out waiting for output: " + expected
                                + System.lineSeparator() + content);
                    }
                    TimeUnit.NANOSECONDS.timedWait(monitor,
                            Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)));
                }
            }
        }

        private void awaitClosed(Duration timeout) throws Exception {
            reader.join(timeout.toMillis());
            assertFalse(reader.isAlive(), "output drain did not reach EOF");
            synchronized (monitor) {
                if (failure != null) {
                    throw new AssertionError("output drain failed", failure);
                }
                assertTrue(closed);
            }
        }

        private boolean contains(String expected) {
            synchronized (monitor) {
                return content.indexOf(expected) >= 0;
            }
        }

        private void assertWaitingCanContinue(String expected) {
            synchronized (monitor) {
                if (failure != null) {
                    throw new AssertionError("output drain failed", failure);
                }
                if (closed && content.indexOf(expected) < 0) {
                    fail("process output closed before containing: " + expected
                            + System.lineSeparator() + content);
                }
            }
        }

        private String text() {
            synchronized (monitor) {
                return content.toString();
            }
        }
    }

    private enum ObservationPoint {
        OBSERVE_ONLY,
        READINESS,
        STATUS_RESPONSE,
        PRE_EXIT
    }

    private enum PhaseState {
        NOT_REACHED,
        NOT_CAPTURED,
        ALIVE,
        NOT_ALIVE
    }

    private record ProcessInfoSnapshot(Optional<String> command,
            Optional<List<String>> arguments, Optional<String> commandLine,
            Optional<Instant> startInstant) {
        private ProcessInfoSnapshot merge(ProcessInfoSnapshot newer) {
            return new ProcessInfoSnapshot(
                    newer.command().or(() -> command),
                    newer.arguments().or(() -> arguments),
                    newer.commandLine().or(() -> commandLine),
                    newer.startInstant().or(() -> startInstant));
        }
    }

    private static final class CapturedProcessRecord {
        private final ProcessHandle handle;
        private final long pid;
        private final long parentPid;
        private final int depthFromLauncher;
        private final boolean directChildOfLauncher;
        private final List<Long> ancestorPids;
        private ProcessInfoSnapshot info;
        private final Instant firstSeenAt;
        private Instant lastSeenAt;
        private PhaseState aliveAtStartupPrompt = PhaseState.NOT_REACHED;
        private PhaseState aliveAtStatusResponse = PhaseState.NOT_REACHED;
        private PhaseState aliveImmediatelyBeforeExit = PhaseState.NOT_REACHED;
        private Instant exitObservedAt;

        private CapturedProcessRecord(ProcessHandle handle,
                List<Long> ancestorPids, ProcessInfoSnapshot info,
                Instant firstSeenAt, long launcherPid,
                boolean readinessReached, boolean statusReached,
                boolean preExitReached) {
            this.handle = handle;
            this.pid = handle.pid();
            this.ancestorPids = List.copyOf(ancestorPids);
            this.parentPid = ancestorPids.getFirst();
            this.depthFromLauncher = ancestorPids.indexOf(launcherPid) + 1;
            this.directChildOfLauncher = parentPid == launcherPid;
            this.info = info;
            this.firstSeenAt = firstSeenAt;
            this.lastSeenAt = firstSeenAt;
            if (readinessReached) {
                aliveAtStartupPrompt = PhaseState.NOT_CAPTURED;
            }
            if (statusReached) {
                aliveAtStatusResponse = PhaseState.NOT_CAPTURED;
            }
            if (preExitReached) {
                aliveImmediatelyBeforeExit = PhaseState.NOT_CAPTURED;
            }
        }

        private void observe(ProcessInfoSnapshot observedInfo,
                Instant observedAt) {
            info = info.merge(observedInfo);
            lastSeenAt = observedAt;
        }

        private void markPhase(ObservationPoint point) {
            PhaseState state = handle.isAlive()
                    ? PhaseState.ALIVE : PhaseState.NOT_ALIVE;
            if (point == ObservationPoint.READINESS) {
                aliveAtStartupPrompt = state;
            } else if (point == ObservationPoint.STATUS_RESPONSE) {
                aliveAtStatusResponse = state;
            } else if (point == ObservationPoint.PRE_EXIT) {
                aliveImmediatelyBeforeExit = state;
            }
        }

        private void observeExit(Instant observedAt) {
            if (exitObservedAt == null) exitObservedAt = observedAt;
        }

        private boolean isAncestorOf(CapturedProcessRecord descendant) {
            return descendant.ancestorPids.contains(pid);
        }

        private String parentChainClassification() {
            return directChildOfLauncher
                    ? "DIRECT_JAVA_CHILD_OF_LAUNCHER"
                    : "LINEAR_JAVA_DESCENDANT_DEPTH_" + depthFromLauncher;
        }

        private String diagnostic() {
            return String.format(Locale.ROOT,
                    "WINDOWS_PROCESS_RECORD pid=%d ppid=%d depth=%d "
                            + "direct_child=%s ancestors=%s "
                            + "command=%s arguments=%s command_line=%s "
                            + "start_instant=%s first_seen=%s last_seen=%s "
                            + "readiness_alive=%s status_alive=%s "
                            + "pre_exit_alive=%s exit_observed=%s "
                            + "parent_chain_classification=%s",
                    pid, parentPid, depthFromLauncher, directChildOfLauncher,
                    ancestorPids,
                    printable(info.command()), printableArguments(info.arguments()),
                    printable(info.commandLine()), printableInstant(info.startInstant()),
                    firstSeenAt, lastSeenAt, aliveAtStartupPrompt,
                    aliveAtStatusResponse, aliveImmediatelyBeforeExit,
                    exitObservedAt == null ? "UNOBSERVED" : exitObservedAt,
                    parentChainClassification());
        }

        private static String printable(Optional<String> value) {
            return value.map(text -> text.replace('\r', ' ')
                    .replace('\n', ' ')).orElse("UNAVAILABLE");
        }

        private static String printableArguments(
                Optional<List<String>> arguments) {
            return arguments.map(values -> values.stream()
                    .map(value -> value.replace('\r', ' ').replace('\n', ' '))
                    .toList().toString()).orElse("UNAVAILABLE");
        }

        private static String printableInstant(Optional<Instant> instant) {
            return instant.map(Instant::toString).orElse("UNAVAILABLE");
        }

        private long pid() { return pid; }
        private ProcessHandle handle() { return handle; }
        private Instant exitObservedAt() { return exitObservedAt; }
        private int depthFromLauncher() { return depthFromLauncher; }
    }

    private static final class ProcessTimeline {
        private final Process launcher;
        private final java.util.Map<Long, CapturedProcessRecord> records =
                new java.util.LinkedHashMap<>();
        private boolean readinessReached;
        private boolean statusReached;
        private boolean preExitReached;
        private String readinessTree = "NOT_REACHED";
        private String statusTree = "NOT_REACHED";
        private String preExitTree = "NOT_REACHED";
        private String finalTree = "NOT_REACHED";

        private ProcessTimeline(Process launcher) {
            this.launcher = launcher;
        }

        private void poll(ObservationPoint point) {
            Instant observedAt = Instant.now();
            if (launcher.isAlive()) {
                try (var descendants = launcher.toHandle().descendants()) {
                    for (ProcessHandle handle : descendants
                            .filter(ProcessHandle::isAlive).toList()) {
                        ProcessInfoSnapshot observedInfo = snapshot(handle.info());
                        CapturedProcessRecord existing = records.get(handle.pid());
                        if (existing == null && !isJavaCandidate(observedInfo)) {
                            continue;
                        }
                        CapturedProcessRecord record = records.computeIfAbsent(
                                handle.pid(), ignored -> new CapturedProcessRecord(
                                        handle, captureAncestry(handle), observedInfo,
                                        observedAt, launcher.pid(), readinessReached,
                                        statusReached, preExitReached));
                        record.observe(observedInfo, observedAt);
                    }
                }
            }
            for (CapturedProcessRecord record : records.values()) {
                if (!record.handle().isAlive()) {
                    record.observeExit(observedAt);
                }
            }
            List<CapturedProcessRecord> alive = aliveCapturedProcesses();
            if (point == ObservationPoint.OBSERVE_ONLY && alive.size() > 1) {
                assertLinearAncestry(alive, "continuous observation");
            }
            if (point != ObservationPoint.OBSERVE_ONLY) {
                records.values().forEach(record -> record.markPhase(point));
                if (point == ObservationPoint.READINESS) {
                    readinessReached = true;
                } else if (point == ObservationPoint.STATUS_RESPONSE) {
                    statusReached = true;
                } else if (point == ObservationPoint.PRE_EXIT) {
                    preExitReached = true;
                }
            }
        }

        private List<CapturedProcessRecord> aliveCapturedProcesses() {
            return records.values().stream()
                    .filter(record -> record.handle().isAlive()).toList();
        }

        private List<ProcessHandle> capturedHandles() {
            return records.values().stream()
                    .map(CapturedProcessRecord::handle).toList();
        }

        private Instant lastJavaExitObservedAt() {
            return records.values().stream()
                    .map(CapturedProcessRecord::exitObservedAt)
                    .filter(java.util.Objects::nonNull)
                    .max(Instant::compareTo).orElse(null);
        }

        private Instant firstJavaObservedAt() {
            return records.values().stream()
                    .map(record -> record.firstSeenAt)
                    .min(Instant::compareTo).orElse(null);
        }

        private void assertAllCapturedExited() {
            for (CapturedProcessRecord record : records.values()) {
                assertFalse(record.handle().isAlive(),
                        "captured process remained alive: " + record.pid());
                assertFalse(ProcessHandle.of(record.pid())
                        .map(ProcessHandle::isAlive).orElse(false),
                        "captured PID remained alive: " + record.pid());
            }
        }

        private CapturedProcessRecord requireLinearProcessTree(
                ObservationPoint point) {
            String label = switch (point) {
                case READINESS -> "READINESS";
                case STATUS_RESPONSE -> "STATUS";
                case PRE_EXIT -> "PRE_EXIT";
                case OBSERVE_ONLY -> throw new IllegalArgumentException(
                        "a named phase is required");
            };
            List<CapturedProcessRecord> aliveAtPhase = phaseAliveProcesses(point);
            String tree = formatPhaseTree(aliveAtPhase);
            if (point == ObservationPoint.READINESS) {
                readinessTree = tree;
            } else if (point == ObservationPoint.STATUS_RESPONSE) {
                statusTree = tree;
            } else {
                preExitTree = tree;
            }
            assertTrue(launcher.isAlive(),
                    "launcher was not alive at " + label);
            if (aliveAtPhase.isEmpty()) {
                fail(noAliveMessage(label));
            }
            assertLinearAncestry(aliveAtPhase, label);
            return deepest(aliveAtPhase);
        }

        private CapturedProcessRecord requireCurrentLinearProcessTree(
                String phase) {
            assertTrue(launcher.isAlive(),
                    "launcher was not alive at " + phase);
            List<CapturedProcessRecord> alive = aliveCapturedProcesses();
            if (alive.isEmpty()) {
                fail(noAliveMessage(phase));
            }
            assertLinearAncestry(alive, phase);
            return deepest(alive);
        }

        private String noAliveMessage(String phase) {
            if ("READINESS".equals(phase)) {
                return "NO_RELATED_JAVA_ALIVE_AT_READINESS";
            }
            if ("STATUS".equals(phase)) {
                return "NO_RELATED_JAVA_ALIVE_AT_STATUS_RESPONSE";
            }
            if ("PRE_EXIT".equals(phase)) {
                return "NO_RELATED_JAVA_ALIVE_BEFORE_EXIT";
            }
            return "NO_RELATED_JAVA_ALIVE_AT_"
                    + phase.toUpperCase(Locale.ROOT)
                    .replace(' ', '_').replace('/', '_');
        }

        private CapturedProcessRecord deepest(
                List<CapturedProcessRecord> recordsAtPoint) {
            return recordsAtPoint.stream().max(
                    java.util.Comparator.comparingInt(
                            CapturedProcessRecord::depthFromLauncher))
                    .orElseThrow();
        }

        private void assertLinearAncestry(List<CapturedProcessRecord> alive,
                String phase) {
            for (int left = 0; left < alive.size(); left++) {
                for (int right = left + 1; right < alive.size(); right++) {
                    CapturedProcessRecord first = alive.get(left);
                    CapturedProcessRecord second = alive.get(right);
                    if (!first.isAncestorOf(second)
                            && !second.isAncestorOf(first)) {
                        fail("DUPLICATE_OR_BRANCHED_PAICLI_PROCESS_TREE at "
                                + phase + ": pid=" + first.pid()
                                + " and pid=" + second.pid()
                                + " are simultaneous non-ancestor branches");
                    }
                }
            }
        }

        private boolean hasLinearAncestry(List<CapturedProcessRecord> alive) {
            for (int left = 0; left < alive.size(); left++) {
                for (int right = left + 1; right < alive.size(); right++) {
                    CapturedProcessRecord first = alive.get(left);
                    CapturedProcessRecord second = alive.get(right);
                    if (!first.isAncestorOf(second)
                            && !second.isAncestorOf(first)) {
                        return false;
                    }
                }
            }
            return true;
        }

        private List<Long> captureAncestry(ProcessHandle handle) {
            List<Long> ancestors = new ArrayList<>();
            Optional<ProcessHandle> parent = handle.parent();
            for (int depth = 0; depth < 64 && parent.isPresent(); depth++) {
                long parentPid = parent.orElseThrow().pid();
                ancestors.add(parentPid);
                if (parentPid == launcher.pid()) {
                    return List.copyOf(ancestors);
                }
                parent = parent.orElseThrow().parent();
            }
            throw new AssertionError(
                    "PROCESS_IDENTITY_EVIDENCE_INSUFFICIENT: Java PID "
                            + handle.pid() + " ancestry no longer reaches exact "
                            + "launcher PID " + launcher.pid());
        }

        private List<CapturedProcessRecord> phaseAliveProcesses(
                ObservationPoint point) {
            return records.values().stream()
                    .filter(record -> switch (point) {
                        case READINESS -> record.aliveAtStartupPrompt
                                == PhaseState.ALIVE;
                        case STATUS_RESPONSE -> record.aliveAtStatusResponse
                                == PhaseState.ALIVE;
                        case PRE_EXIT -> record.aliveImmediatelyBeforeExit
                                == PhaseState.ALIVE;
                        case OBSERVE_ONLY -> false;
                    })
                    .sorted(java.util.Comparator.comparingInt(
                            CapturedProcessRecord::depthFromLauncher))
                    .toList();
        }

        private String formatPhaseTree(
                List<CapturedProcessRecord> aliveAtPhase) {
            if (!hasLinearAncestry(aliveAtPhase)) {
                return "Launcher PID=" + launcher.pid() + " -> BRANCHED "
                        + aliveAtPhase.stream().map(record -> record.pid()
                                + "(ppid=" + record.parentPid + ")")
                        .collect(java.util.stream.Collectors.joining(", "));
            }
            return "Launcher PID=" + launcher.pid() + " -> "
                    + (aliveAtPhase.isEmpty() ? "NONE_ALIVE"
                    : aliveAtPhase.stream().map(record ->
                            Long.toString(record.pid()))
                    .collect(java.util.stream.Collectors.joining(" -> ")));
        }

        private void captureFinalTree() {
            poll(ObservationPoint.OBSERVE_ONLY);
            finalTree = "Launcher PID=" + launcher.pid() + " alive="
                    + launcher.isAlive() + "; Java=" + records.values().stream()
                    .sorted(java.util.Comparator.comparingInt(
                            CapturedProcessRecord::depthFromLauncher))
                    .map(record -> record.pid() + ":alive="
                            + record.handle().isAlive())
                    .collect(java.util.stream.Collectors.joining(", "));
        }

        private String classificationLabel(long pid) {
            CapturedProcessRecord record = records.get(pid);
            return record == null ? "EXACT_LAUNCHER_DESCENDANT"
                    : record.parentChainClassification();
        }

        private void printDiagnostics(java.io.PrintStream output) {
            output.println("Launcher PID=" + launcher.pid());
            output.println("READINESS_PROCESS_TREE=" + readinessTree);
            output.println("STATUS_PROCESS_TREE=" + statusTree);
            output.println("PRE_EXIT_PROCESS_TREE=" + preExitTree);
            output.println("FINAL_PROCESS_TREE=" + finalTree);
            if (records.isEmpty()) {
                output.println("WINDOWS_PROCESS_RECORDS=NONE_CAPTURED");
            }
            records.values().forEach(record -> output.println(
                    record.diagnostic()));
        }
    }

    private static final class ExitOrderMonitor {
        private final AtomicLong completionSequence = new AtomicLong();
        private final java.util.Map<Long, CompletableFuture<ExitCompletion>>
                relatedCompletions = new java.util.LinkedHashMap<>();
        private final CompletableFuture<ExitCompletion> launcherCompletion;

        private ExitOrderMonitor(Process launcher) {
            this.launcherCompletion = launcher.onExit().thenApply(ignored ->
                    new ExitCompletion("launcher", Instant.now(),
                            completionSequence.incrementAndGet()));
        }

        private static ExitOrderMonitor capture(Process launcher,
                List<ProcessHandle> capturedHandles) {
            assertFalse(capturedHandles.isEmpty(),
                    "exit ordering requires at least one captured process");
            ExitOrderMonitor monitor = new ExitOrderMonitor(launcher);
            monitor.register(capturedHandles);
            return monitor;
        }

        private void register(List<ProcessHandle> capturedHandles) {
            for (ProcessHandle handle : capturedHandles) {
                relatedCompletions.computeIfAbsent(handle.pid(), pid ->
                        handle.onExit().thenApply(ignored ->
                                new ExitCompletion("java:" + pid,
                                        Instant.now(), completionSequence
                                        .incrementAndGet())));
            }
        }

        private void failIfLauncherCompletedFirst() {
            if (!launcherCompletion.isDone()) return;
            ExitCompletion launcher = launcherCompletion.join();
            List<Long> pending = relatedCompletions.entrySet().stream()
                    .filter(entry -> !entry.getValue().isDone())
                    .map(java.util.Map.Entry::getKey).toList();
            List<Long> completedAfterLauncher = relatedCompletions.entrySet()
                    .stream().filter(entry -> entry.getValue().isDone())
                    .filter(entry -> entry.getValue().join().sequence()
                            >= launcher.sequence())
                    .map(java.util.Map.Entry::getKey).toList();
            if (!pending.isEmpty() || !completedAfterLauncher.isEmpty()) {
                fail("LAUNCHER_EXITED_WHILE_RELATED_JAVA_REMAINED_ALIVE: "
                        + "launcher sequence=" + launcher.sequence()
                        + ", pending PIDs=" + pending
                        + ", later-completing PIDs="
                        + completedAfterLauncher);
            }
        }

        private boolean completionEvidenceReady() {
            return launcherCompletion.isDone()
                    && relatedCompletions.values().stream()
                    .allMatch(CompletableFuture::isDone);
        }

        private ExitOrderEvidence requireAllRelatedBeforeLauncher() {
            assertFalse(relatedCompletions.isEmpty(),
                    "related process completion evidence is empty");
            List<Long> incomplete = relatedCompletions.entrySet().stream()
                    .filter(entry -> !entry.getValue().isDone())
                    .map(java.util.Map.Entry::getKey).toList();
            assertTrue(incomplete.isEmpty(),
                    "captured process completion evidence is incomplete: "
                            + incomplete);
            assertTrue(launcherCompletion.isDone(),
                    "launcher completion evidence is incomplete");
            failIfLauncherCompletedFirst();
            ExitCompletion related = relatedCompletions.values().stream()
                    .map(CompletableFuture::join)
                    .max(java.util.Comparator.comparingLong(
                            ExitCompletion::sequence)).orElseThrow();
            ExitCompletion launcher = launcherCompletion.join();
            assertTrue(related.sequence() < launcher.sequence(),
                    "PROCESS_EXIT_ORDER_EVIDENCE_AMBIGUOUS: captured sequence="
                            + related.sequence() + ", launcher sequence="
                            + launcher.sequence());
            assertFalse(launcher.observedAt().isBefore(related.observedAt()),
                    "launcher completion timestamp preceded captured processes");
            return new ExitOrderEvidence(related, launcher);
        }
    }

    private record ExitCompletion(String owner, Instant observedAt,
            long sequence) { }

    private record ExitOrderEvidence(ExitCompletion related,
            ExitCompletion launcher) { }

    private record ExitTimeline(Instant javaExitObservedAt,
            Instant launcherExitObservedAt) { }

    private record CleanupResult(boolean forced, List<String> actions,
            List<Throwable> failures) { }

    private static void assertNoStartupFailure(String stderr) {
        assertFalse(stderr.contains("PaiCLI launcher error"), stderr);
        assertFalse(stderr.contains("interactive shell could not start"), stderr);
        assertFalse(stderr.contains("Exception"), stderr);
        assertFalse(stderr.contains("\tat "), stderr);
    }
}
