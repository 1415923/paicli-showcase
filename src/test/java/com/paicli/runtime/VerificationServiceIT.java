package com.paicli.runtime;

import com.paicli.state.CodingRunStore;
import com.paicli.state.StateStore;
import com.paicli.state.VerificationStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerificationServiceIT {

    @TempDir
    Path workspace;

    @Test
    void blankCommandRecordsSkipped() throws Exception {
        try (StateStore stateStore = preparedStateStore("task-skip", "run-skip")) {
            VerificationService service = service(stateStore, "task-skip", 5, 1024);

            VerificationResult result = service.verify(
                    "verify-skip", "run-skip", "task-skip", 0, "   ");

            assertEquals(VerificationStore.STATUS_SKIPPED, result.status());
            assertNull(result.command());
            assertNull(result.exitCode());
            assertEquals(0, result.durationMs());
            VerificationStore.VerificationRecord stored = stateStore.verifications
                    .findById("verify-skip").orElseThrow();
            assertEquals(VerificationStore.STATUS_SKIPPED, stored.status());
            assertNull(stored.command());
            assertNull(stored.outputExcerpt());
            assertNull(stored.errorExcerpt());
        }
    }

    @Test
    void successfulCommandRecordsPassedAndOutput() throws Exception {
        try (StateStore stateStore = preparedStateStore("task-pass", "run-pass")) {
            VerificationService service = service(stateStore, "task-pass", 5, 1024);

            VerificationResult result = service.verify(
                    "verify-pass", "run-pass", "task-pass", 0, "echo verification-passed");

            assertEquals(VerificationStore.STATUS_PASSED, result.status());
            assertEquals(0, result.exitCode());
            assertTrue(result.outputExcerpt().contains("verification-passed"));
            VerificationStore.VerificationRecord stored = stateStore.verifications
                    .findById("verify-pass").orElseThrow();
            assertEquals(VerificationStore.STATUS_PASSED, stored.status());
            assertEquals(0, stored.exitCode());
            assertEquals(result.outputExcerpt(), stored.outputExcerpt());
            assertEquals(WorkspaceCandidateDigest.KIND, stored.candidateDigestKind());
            assertTrue(stored.candidateDigest().matches("[0-9a-f]{64}"));
        }
    }

    @Test
    void verifierMutationOfExistingSourceRejectsPass() throws Exception {
        try (StateStore stateStore = preparedStateStore("task-stale-source", "run-stale-source")) {
            Path taskWorkspace = workspace.resolve("task-stale-source");
            Path source = taskWorkspace.resolve("src/main/java/App.java");
            Files.createDirectories(source.getParent());
            Files.writeString(source, "class App {}\n");
            VerificationService service = service(stateStore, "task-stale-source", 5, 1024);

            VerificationResult result = service.verify("verify-stale-source",
                    "run-stale-source", "task-stale-source", 0,
                    writeCommand("src/main/java/App.java", "changed-source"));

            assertTrue(Files.readString(source).contains("changed-source"));
            assertEquals(VerificationStore.STATUS_ERROR, result.status());
            assertTrue(result.errorExcerpt().contains("CANDIDATE_CHANGED_DURING_VERIFICATION"));
            assertEquals(VerificationStore.STATUS_ERROR, stateStore.verifications
                    .findById("verify-stale-source").orElseThrow().status());
        }
    }

    @Test
    void verifierCreationOfUntrackedSourceRejectsPass() throws Exception {
        try (StateStore stateStore = preparedStateStore("task-stale-new", "run-stale-new")) {
            VerificationService service = service(stateStore, "task-stale-new", 5, 1024);

            VerificationResult result = service.verify("verify-stale-new", "run-stale-new",
                    "task-stale-new", 0,
                    writeCommand("src/test/java/NewTest.java", "class NewTest {}"));

            assertEquals(VerificationStore.STATUS_ERROR, result.status());
            assertTrue(result.errorExcerpt().contains("CANDIDATE_CHANGED_DURING_VERIFICATION"));
        }
    }

    @Test
    void verifierBuildOutputMutationDoesNotCreateFalseStaleFailure() throws Exception {
        try (StateStore stateStore = preparedStateStore("task-build-output", "run-build-output")) {
            VerificationService service = service(stateStore, "task-build-output", 5, 1024);

            VerificationResult result = service.verify("verify-build-output",
                    "run-build-output", "task-build-output", 0,
                    writeCommand("target/classes/App.class", "generated"));

            assertEquals(VerificationStore.STATUS_PASSED, result.status());
            assertTrue(Files.exists(workspace.resolve(
                    "task-build-output/target/classes/App.class")));
        }
    }

    @Test
    void failingCommandRecordsFailedExitCodeAndStderr() throws Exception {
        try (StateStore stateStore = preparedStateStore("task-fail", "run-fail")) {
            VerificationService service = service(stateStore, "task-fail", 5, 1024);

            VerificationResult result = service.verify(
                    "verify-fail", "run-fail", "task-fail", 0, failingCommand());

            assertEquals(VerificationStore.STATUS_FAILED, result.status());
            assertEquals(7, result.exitCode());
            assertTrue(result.errorExcerpt().contains("verification-failed"));
            VerificationStore.VerificationRecord stored = stateStore.verifications
                    .findById("verify-fail").orElseThrow();
            assertEquals(7, stored.exitCode());
            assertEquals(result.errorExcerpt(), stored.errorExcerpt());
        }
    }

    @Test
    void timeoutRecordsTimedOutAndStopsProcess() throws Exception {
        try (StateStore stateStore = preparedStateStore("task-timeout", "run-timeout")) {
            VerificationService service = service(stateStore, "task-timeout", 1, 1024);

            VerificationResult result = service.verify(
                    "verify-timeout", "run-timeout", "task-timeout", 0, slowCommand());

            assertEquals(VerificationStore.STATUS_TIMED_OUT, result.status());
            assertNull(result.exitCode());
            assertTrue(result.durationMs() >= 900);
            assertTrue(result.durationMs() < 4000);
            assertEquals(VerificationStore.STATUS_TIMED_OUT, stateStore.verifications
                    .findById("verify-timeout").orElseThrow().status());
        }
    }

    @Test
    void launchFailureRecordsSanitizedError() throws Exception {
        try (StateStore stateStore = preparedStateStore("task-error", "run-error")) {
            VerificationService service = new VerificationService(
                    workspace.resolve("task-error"), stateStore.verifications,
                    new PersistenceSanitizer(), 5, 1024,
                    (path, command) -> {
                        throw new IOException("cannot start Authorization: Bearer launch-secret");
                    });

            VerificationResult result = service.verify(
                    "verify-error", "run-error", "task-error", 0, "missing-command");

            assertEquals(VerificationStore.STATUS_ERROR, result.status());
            assertNull(result.exitCode());
            assertFalse(result.errorExcerpt().contains("launch-secret"));
            assertTrue(result.errorExcerpt().contains("<redacted>"));
            VerificationStore.VerificationRecord stored = stateStore.verifications
                    .findById("verify-error").orElseThrow();
            assertEquals(VerificationStore.STATUS_ERROR, stored.status());
            assertEquals(result.errorExcerpt(), stored.errorExcerpt());
        }
    }

    @Test
    void stdoutStderrAndCommandAreSanitizedAndTruncated() throws Exception {
        try (StateStore stateStore = preparedStateStore("task-safe", "run-safe")) {
            VerificationService service = service(stateStore, "task-safe", 5, 160);
            String command = "echo Authorization: Bearer stdout-secret"
                    + " && echo apiKey=stderr-secret 1>&2"
                    + " && echo " + "x".repeat(2000);

            VerificationResult result = service.verify(
                    "verify-safe", "run-safe", "task-safe", 0, command);

            assertEquals(VerificationStore.STATUS_PASSED, result.status());
            assertFalse(result.command().contains("stdout-secret"));
            assertFalse(result.command().contains("stderr-secret"));
            assertFalse(result.outputExcerpt().contains("stdout-secret"));
            assertFalse(result.errorExcerpt().contains("stderr-secret"));
            assertTrue(result.outputExcerpt().contains("<redacted>"));
            assertTrue(result.errorExcerpt().contains("<redacted>"));
            assertTrue(result.outputExcerpt().getBytes(StandardCharsets.UTF_8).length <= 160);
            assertTrue(result.errorExcerpt().getBytes(StandardCharsets.UTF_8).length <= 160);
            VerificationStore.VerificationRecord stored = stateStore.verifications
                    .findById("verify-safe").orElseThrow();
            assertEquals(result.command(), stored.command());
            assertEquals(result.outputExcerpt(), stored.outputExcerpt());
            assertEquals(result.errorExcerpt(), stored.errorExcerpt());
        }
    }

    @Test
    void commandRunsWithWorkspaceAsWorkingDirectory() throws Exception {
        try (StateStore stateStore = preparedStateStore("task-workspace", "run-workspace")) {
            VerificationService service = service(stateStore, "task-workspace", 5, 1024);

            VerificationResult result = service.verify(
                    "verify-workspace", "run-workspace", "task-workspace", 0,
                    isWindows() ? "cd" : "pwd");

            Path reportedWorkspace = Path.of(result.outputExcerpt().trim()).toRealPath();
            assertEquals(workspace.resolve("task-workspace").toRealPath(), reportedWorkspace);
        }
    }

    @Test
    void configurationHashIsStableAndDetectsCommandOrLimitDrift() throws Exception {
        try (StateStore stateStore = preparedStateStore("task-hash", "run-hash")) {
            VerificationService first = service(stateStore, "task-hash", 5, 1024);
            VerificationService changedTimeout = service(
                    stateStore, "task-hash", 6, 1024);

            assertEquals(first.configurationHash("mvn test"),
                    first.configurationHash("mvn test"));
            assertNotEquals(first.configurationHash("mvn test"),
                    first.configurationHash("mvn verify"));
            assertNotEquals(first.configurationHash("mvn test"),
                    changedTimeout.configurationHash("mvn test"));
        }
    }

    private VerificationService service(StateStore stateStore, String taskId,
                                        int timeoutSeconds, int maxOutputBytes) throws IOException {
        return new VerificationService(workspace.resolve(taskId), stateStore.verifications,
                new PersistenceSanitizer(), timeoutSeconds, maxOutputBytes);
    }

    private StateStore preparedStateStore(String taskId, String runId) throws Exception {
        StateStore stateStore = new StateStore(workspace.resolve(taskId));
        stateStore.tasks.createTask(taskId, "goal", "mock", null, "on-request", 10);
        stateStore.codingRuns.create(runId, taskId, CodingRunStore.KIND_INITIAL,
                "", 1, 0);
        return stateStore;
    }

    private String failingCommand() {
        return isWindows()
                ? "echo verification-failed 1>&2 & exit /b 7"
                : "echo verification-failed >&2; exit 7";
    }

    private String slowCommand() {
        return isWindows()
                ? "ping -n 6 127.0.0.1 >NUL"
                : "sleep 5";
    }

    private String writeCommand(String relativePath, String content) {
        String normalized = relativePath.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String directory = normalized.substring(0, slash);
        if (isWindows()) {
            String windowsDirectory = directory.replace('/', '\\');
            String windowsPath = normalized.replace('/', '\\');
            return "mkdir \"" + windowsDirectory + "\" 2>NUL & >\"" + windowsPath
                    + "\" echo " + content;
        }
        return "mkdir -p '" + directory + "'; printf '%s\\n' '" + content
                + "' > '" + normalized + "'";
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }
}
