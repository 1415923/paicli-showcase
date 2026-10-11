package com.paicli.runtime;

import com.paicli.state.VerificationStore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import com.paicli.identity.CanonicalId;
import com.paicli.tool.ToolResult;

/** Executes a caller-selected verification command and persists only bounded, sanitized excerpts. */
public final class VerificationService {

    private final Path workspace;
    private final VerificationStore verificationStore;
    private final PersistenceSanitizer sanitizer;
    private final int timeoutSeconds;
    private final int maxOutputBytes;
    private final ProcessLauncher processLauncher;
    private final VerificationLifecycleObserver observer;
    private final CancellationToken cancellationToken;
    private final OwnedProcessRegistry ownedProcesses;
    private final WorkspaceCandidateDigest candidateDigest;

    public VerificationService(Path workspace, VerificationStore verificationStore,
                               PersistenceSanitizer sanitizer, int timeoutSeconds,
                               int maxOutputBytes) throws IOException {
        this(workspace, verificationStore, sanitizer, timeoutSeconds, maxOutputBytes,
                VerificationService::startShellProcess, VerificationLifecycleObserver.NOOP,
                CancellationToken.NONE, new OwnedProcessRegistry());
    }

    public VerificationService(Path workspace, VerificationStore verificationStore,
                               PersistenceSanitizer sanitizer, int timeoutSeconds,
                               int maxOutputBytes,
                               VerificationLifecycleObserver observer) throws IOException {
        this(workspace, verificationStore, sanitizer, timeoutSeconds, maxOutputBytes,
                VerificationService::startShellProcess, observer,
                CancellationToken.NONE, new OwnedProcessRegistry());
    }

    public VerificationService(Path workspace, VerificationStore verificationStore,
                               PersistenceSanitizer sanitizer, int timeoutSeconds,
                               int maxOutputBytes,
                               VerificationLifecycleObserver observer,
                               CancellationToken cancellationToken,
                               OwnedProcessRegistry ownedProcesses) throws IOException {
        this(workspace, verificationStore, sanitizer, timeoutSeconds, maxOutputBytes,
                VerificationService::startShellProcess, observer,
                cancellationToken, ownedProcesses);
    }
    VerificationService(Path workspace, VerificationStore verificationStore,
                        PersistenceSanitizer sanitizer, int timeoutSeconds,
                        int maxOutputBytes, ProcessLauncher processLauncher) throws IOException {
        this(workspace, verificationStore, sanitizer, timeoutSeconds, maxOutputBytes,
                processLauncher, VerificationLifecycleObserver.NOOP,
                CancellationToken.NONE, new OwnedProcessRegistry());
    }

    VerificationService(Path workspace, VerificationStore verificationStore,
                        PersistenceSanitizer sanitizer, int timeoutSeconds,
                        int maxOutputBytes, ProcessLauncher processLauncher,
                        VerificationLifecycleObserver observer) throws IOException {
        this(workspace, verificationStore, sanitizer, timeoutSeconds, maxOutputBytes,
                processLauncher, observer, CancellationToken.NONE,
                new OwnedProcessRegistry());
    }

    VerificationService(Path workspace, VerificationStore verificationStore,
                        PersistenceSanitizer sanitizer, int timeoutSeconds,
                        int maxOutputBytes, ProcessLauncher processLauncher,
                        VerificationLifecycleObserver observer,
                        CancellationToken cancellationToken,
                        OwnedProcessRegistry ownedProcesses) throws IOException {
        Path normalizedWorkspace = Objects.requireNonNull(workspace, "workspace")
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(normalizedWorkspace)) {
            throw new IOException("Verification workspace is not a directory");
        }
        this.workspace = normalizedWorkspace.toRealPath();
        this.candidateDigest = new WorkspaceCandidateDigest(this.workspace);
        this.verificationStore = Objects.requireNonNull(verificationStore, "verificationStore");
        this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer");
        if (timeoutSeconds <= 0) {
            throw new IllegalArgumentException("timeoutSeconds must be positive");
        }
        if (maxOutputBytes <= 0) {
            throw new IllegalArgumentException("maxOutputBytes must be positive");
        }
        this.timeoutSeconds = timeoutSeconds;
        this.maxOutputBytes = maxOutputBytes;
        this.processLauncher = Objects.requireNonNull(processLauncher, "processLauncher");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken");
        this.ownedProcesses = Objects.requireNonNull(ownedProcesses, "ownedProcesses");
    }

    public VerificationResult verify(String verificationId, String runId, String taskId,
                                     int repairAttempt, String command) throws SQLException {
        return verify(verificationId,runId,taskId,repairAttempt,command,null);
    }

    public VerificationResult verify(String verificationId, String runId, String taskId,
                                     int repairAttempt, String command,
                                     FencedExecutionGuard executionGuard) throws SQLException {
        cancellationToken.throwIfCancellationRequested();
        requireText(verificationId, "verificationId");
        requireText(runId, "runId");
        requireText(taskId, "taskId");
        if (repairAttempt < 0) {
            throw new IllegalArgumentException("repairAttempt must not be negative");
        }
        if (command == null || command.isBlank()) {
            if(executionGuard!=null)executionGuard.startVerification(verificationId,
                    repairAttempt,null,VerificationStore.STATUS_SKIPPED,null,null,null);
            else verificationStore.recordSkipped(verificationId, runId, taskId, repairAttempt);
            notifyObserver(() -> observer.started(
                    verificationId, runId, taskId, repairAttempt, "verification disabled"));
            return result(verificationId, runId, taskId, repairAttempt, null,
                    VerificationStore.STATUS_SKIPPED, null, 0, null, null, null, null);
        }

        String persistedCommand = sanitizer.sanitizeAndTruncate(command, maxOutputBytes);
        heartbeat(executionGuard,"VERIFICATION_START");
        WorkspaceCandidateDigest.Result candidate;
        try {
            candidate = candidateDigest.compute();
        } catch (WorkspaceCandidateDigest.Failure failure) {
            String errorExcerpt = candidateDigestFailure(failure);
            if (executionGuard != null) executionGuard.startVerification(verificationId,
                    repairAttempt, persistedCommand, VerificationStore.STATUS_ERROR,
                    null, null, errorExcerpt);
            else verificationStore.recordPreflightError(verificationId, runId, taskId,
                    repairAttempt, persistedCommand, errorExcerpt);
            notifyObserver(() -> observer.started(verificationId, runId, taskId,
                    repairAttempt, "Candidate digest unavailable"));
            return result(verificationId, runId, taskId, repairAttempt, persistedCommand,
                    VerificationStore.STATUS_ERROR, null, 0, null, errorExcerpt, null, null);
        }
        if(executionGuard!=null)executionGuard.startVerification(verificationId,repairAttempt,
                persistedCommand,VerificationStore.STATUS_RUNNING,candidate.kind(),
                candidate.digest(),null);
        else verificationStore.recordStarted(
                verificationId, runId, taskId, repairAttempt, persistedCommand,
                candidate.kind(), candidate.digest());
        CanonicalId fencedOccurrence=executionGuard==null?null:
                executionGuard.admitOperationOccurrence("verification:"+repairAttempt,
                        "recovery-verification:"+runId+":"+verificationId,"VERIFICATION",
                        "configured-verifier",FencedExecutionGuard.canonicalInputHash(
                                persistedCommand));
        notifyObserver(() -> observer.started(
                verificationId, runId, taskId, repairAttempt, "configured verifier"));
        long startedAt = System.nanoTime();
        Process process;
        try {
            if(executionGuard!=null)executionGuard.revalidateBeforeExternalEffect(
                    fencedOccurrence,"VERIFICATION",verificationId);
        } catch(FencedExecutionGuard.SteeringEffectBlockedException blocked) {
            String paused="PAUSED_BY_STEERING";
            return result(verificationId,runId,taskId,repairAttempt,persistedCommand,
                    VerificationStore.STATUS_PAUSED_BY_STEERING,null,
                    elapsedMillis(startedAt),null,paused,candidate.kind(),candidate.digest());
        }
        String shellExecutable = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT).contains("windows")
                ? System.getenv().getOrDefault("ComSpec", "cmd.exe") : "/bin/sh";
        java.util.List<String> diagnosticCommand = java.util.List.of(
                shellExecutable, "<shell-command-hash-input>", command);
        com.paicli.diagnostic.StageTrace.event(
                "CHILD_PROCESS_CREATE_START", false,
                "purpose", "VERIFICATION_COMMAND",
                "executable_basename",
                com.paicli.diagnostic.StageTrace.executableBasename(diagnosticCommand),
                "argument_count", 1,
                "argument_hash",
                com.paicli.diagnostic.StageTrace.hashArguments(diagnosticCommand));
        try {
            process = Objects.requireNonNull(
                    processLauncher.start(workspace, command), "processLauncher returned null");
            com.paicli.diagnostic.StageTrace.event(
                    "CHILD_PROCESS_CREATE_END", false,
                    "purpose", "VERIFICATION_COMMAND",
                    "outcome", "STARTED",
                    "pid", com.paicli.diagnostic.StageTrace.processId(process));
        } catch (IOException | RuntimeException e) {
            com.paicli.diagnostic.StageTrace.event(
                    "CHILD_PROCESS_CREATE_END", false,
                    "purpose", "VERIFICATION_COMMAND",
                    "outcome", "ERROR",
                    "error_type", e.getClass().getName());
            long durationMs = elapsedMillis(startedAt);
            String errorExcerpt = sanitizeText("Verification command could not start: " + safeMessage(e));
            finish(executionGuard,verificationId,VerificationStore.STATUS_ERROR,null,null,
                    errorExcerpt,durationMs);
            failOccurrence(executionGuard,fencedOccurrence,errorExcerpt);
            return result(verificationId, runId, taskId, repairAttempt, persistedCommand,
                    VerificationStore.STATUS_ERROR, null, durationMs, null, errorExcerpt,
                    candidate.kind(), candidate.digest());
        }

        OwnedProcessRegistry.Registration processRegistration = ownedProcesses.register(process);
        AtomicReference<StreamCapture> stdout = new AtomicReference<>();
        AtomicReference<StreamCapture> stderr = new AtomicReference<>();
        Thread stdoutReader = startCaptureThread(process.getInputStream(), stdout, "verification-stdout");
        Thread stderrReader = startCaptureThread(process.getErrorStream(), stderr, "verification-stderr");

        try {
            boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!completed) {
                terminateProcessTree(process);
                process.waitFor(5, TimeUnit.SECONDS);
                closeProcessOutputStreams(process);
                stdoutReader.join(1000);
                stderrReader.join(1000);
            } else {
                stdoutReader.join();
                stderrReader.join();
            }

            long durationMs = elapsedMillis(startedAt);
            String outputExcerpt = sanitizeCapture(stdout.get());
            String errorExcerpt = sanitizeCapture(stderr.get());
            heartbeat(executionGuard,"VERIFICATION_PROCESS_RESULT");
            if (!completed) {
                finish(executionGuard,verificationId,VerificationStore.STATUS_TIMED_OUT,null,
                        outputExcerpt,errorExcerpt,durationMs);
                failOccurrence(executionGuard,fencedOccurrence,"verification timed out");
                return result(verificationId, runId, taskId, repairAttempt, persistedCommand,
                        VerificationStore.STATUS_TIMED_OUT, null, durationMs,
                        outputExcerpt, errorExcerpt,candidate.kind(),candidate.digest());
            }
            IOException captureError = firstCaptureError(stdout.get(), stderr.get());
            if (captureError != null) {
                errorExcerpt = mergeErrors(errorExcerpt,
                        sanitizeText("Verification output could not be read: " + safeMessage(captureError)));
                finish(executionGuard,verificationId,VerificationStore.STATUS_ERROR,null,
                        outputExcerpt,errorExcerpt,durationMs);
                failOccurrence(executionGuard,fencedOccurrence,errorExcerpt);
                return result(verificationId, runId, taskId, repairAttempt, persistedCommand,
                        VerificationStore.STATUS_ERROR, null, durationMs,
                        outputExcerpt, errorExcerpt,candidate.kind(),candidate.digest());
            }
            int exitCode = process.exitValue();
            if (exitCode == 0) {
                WorkspaceCandidateDigest.Result current;
                try {
                    current = candidateDigest.compute();
                } catch (WorkspaceCandidateDigest.Failure failure) {
                    errorExcerpt = mergeErrors(errorExcerpt, candidateDigestFailure(failure));
                    finish(executionGuard,verificationId,VerificationStore.STATUS_ERROR,
                            exitCode,outputExcerpt,errorExcerpt,durationMs);
                    failOccurrence(executionGuard,fencedOccurrence,errorExcerpt);
                    return result(verificationId,runId,taskId,repairAttempt,persistedCommand,
                            VerificationStore.STATUS_ERROR,exitCode,durationMs,outputExcerpt,
                            errorExcerpt,candidate.kind(),candidate.digest());
                }
                if (!candidate.kind().equals(current.kind())
                        || !candidate.digest().equals(current.digest())) {
                    errorExcerpt = mergeErrors(errorExcerpt,
                            sanitizeText("CANDIDATE_CHANGED_DURING_VERIFICATION"));
                    finish(executionGuard,verificationId,VerificationStore.STATUS_ERROR,
                            exitCode,outputExcerpt,errorExcerpt,durationMs);
                    failOccurrence(executionGuard,fencedOccurrence,
                            "CANDIDATE_CHANGED_DURING_VERIFICATION");
                    return result(verificationId,runId,taskId,repairAttempt,persistedCommand,
                            VerificationStore.STATUS_ERROR,exitCode,durationMs,outputExcerpt,
                            errorExcerpt,candidate.kind(),candidate.digest());
                }
                finish(executionGuard,verificationId,VerificationStore.STATUS_PASSED,exitCode,
                        outputExcerpt,errorExcerpt,durationMs);
                completeOccurrence(executionGuard,fencedOccurrence,true);
                return result(verificationId, runId, taskId, repairAttempt, persistedCommand,
                        VerificationStore.STATUS_PASSED, exitCode, durationMs,
                        outputExcerpt, errorExcerpt,candidate.kind(),candidate.digest());
            }
            finish(executionGuard,verificationId,VerificationStore.STATUS_FAILED,exitCode,
                    outputExcerpt,errorExcerpt,durationMs);
            completeOccurrence(executionGuard,fencedOccurrence,false);
            return result(verificationId, runId, taskId, repairAttempt, persistedCommand,
                    VerificationStore.STATUS_FAILED, exitCode, durationMs,
                    outputExcerpt, errorExcerpt,candidate.kind(),candidate.digest());
        } catch (InterruptedException e) {
            terminateProcessTree(process);
            closeProcessOutputStreams(process);
            long durationMs = elapsedMillis(startedAt);
            String errorExcerpt = sanitizeText("Verification command was interrupted");
            finish(executionGuard,verificationId,VerificationStore.STATUS_ERROR,null,null,
                    errorExcerpt,durationMs);
            failOccurrence(executionGuard,fencedOccurrence,errorExcerpt);
            Thread.currentThread().interrupt();
            return result(verificationId, runId, taskId, repairAttempt, persistedCommand,
                    VerificationStore.STATUS_ERROR, null, durationMs, null, errorExcerpt,
                    candidate.kind(),candidate.digest());
        } finally {
            processRegistration.close();
        }
    }

    private static void heartbeat(FencedExecutionGuard guard,String boundary){
        if(guard!=null)guard.heartbeat(boundary);
    }

    private static void authorize(FencedExecutionGuard guard,String boundary){
        if(guard!=null)guard.validate(boundary);
    }

    private void finish(FencedExecutionGuard guard,String id,String status,Integer exit,
            String output,String error,long duration)throws SQLException{
        if(guard!=null)guard.finishVerification(id,status,exit,output,error,duration);
        else switch(status){
            case VerificationStore.STATUS_PASSED -> verificationStore.markPassed(
                    id,exit,output,error,duration);
            case VerificationStore.STATUS_FAILED -> verificationStore.markFailed(
                    id,exit,output,error,duration);
            case VerificationStore.STATUS_TIMED_OUT -> verificationStore.markTimedOut(
                    id,output,error,duration);
            default -> verificationStore.markError(id,output,error,duration);
        }
    }

    private static void completeOccurrence(FencedExecutionGuard guard,CanonicalId occurrence,
            boolean success){
        if(guard!=null)guard.completeOccurrence(occurrence,success?ToolResult.success(
                "verification passed"):ToolResult.error("verification failed"));
    }

    private static void failOccurrence(FencedExecutionGuard guard,CanonicalId occurrence,
            String error){if(guard!=null)guard.failOccurrence(occurrence,error);}

    /** Hashes the trusted verification configuration without exposing it to model input. */
    public String configurationHash(String command) {
        String material = "workspace=" + workspace
                + "\ntimeout_seconds=" + timeoutSeconds
                + "\nmax_output_bytes=" + maxOutputBytes
                + "\ncommand=" + (command == null ? "" : command);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    WorkspaceCandidateDigest.Result currentCandidateDigest()
            throws WorkspaceCandidateDigest.Failure {
        return candidateDigest.compute();
    }

    private Thread startCaptureThread(InputStream stream,
                                      AtomicReference<StreamCapture> destination,
                                      String name) {
        return Thread.ofVirtual().name(name).start(() -> destination.set(capture(stream)));
    }

    private StreamCapture capture(InputStream stream) {
        ByteArrayOutputStream kept = new ByteArrayOutputStream(Math.min(maxOutputBytes, 8192));
        byte[] buffer = new byte[8192];
        long observedBytes = 0;
        try (stream) {
            int read;
            while ((read = stream.read(buffer)) != -1) {
                observedBytes += read;
                int remaining = maxOutputBytes - kept.size();
                if (remaining > 0) {
                    kept.write(buffer, 0, Math.min(read, remaining));
                }
            }
            return new StreamCapture(kept.toByteArray(), observedBytes, null);
        } catch (IOException e) {
            return new StreamCapture(kept.toByteArray(), observedBytes, e);
        }
    }

    private String sanitizeCapture(StreamCapture capture) {
        if (capture == null || (capture.bytes().length == 0 && capture.observedBytes() == 0)) {
            return null;
        }
        String text = new String(capture.bytes(), StandardCharsets.UTF_8);
        if (capture.observedBytes() > capture.bytes().length) {
            text += "\n...[stream truncated; observed_bytes=" + capture.observedBytes() + "]";
        }
        return sanitizer.sanitizeAndTruncate(text, maxOutputBytes);
    }

    private String sanitizeText(String text) {
        return sanitizer.sanitizeAndTruncate(text, maxOutputBytes);
    }

    private IOException firstCaptureError(StreamCapture stdout, StreamCapture stderr) {
        if (stdout != null && stdout.error() != null) {
            return stdout.error();
        }
        return stderr != null ? stderr.error() : null;
    }

    private String mergeErrors(String first, String second) {
        if (first == null || first.isBlank()) {
            return second;
        }
        return sanitizer.sanitizeAndTruncate(first + "\n" + second, maxOutputBytes);
    }

    private String candidateDigestFailure(WorkspaceCandidateDigest.Failure failure) {
        return sanitizeText("CANDIDATE_DIGEST_UNAVAILABLE:" + failure.code().name());
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName() : message;
    }

    private long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private void terminateProcessTree(Process process) {
        process.descendants().forEach(handle -> {
            if (handle.isAlive()) {
                handle.destroyForcibly();
            }
        });
        process.destroyForcibly();
    }

    private void closeProcessOutputStreams(Process process) {
        try {
            process.getInputStream().close();
        } catch (IOException ignored) {
            // Best-effort unblock of the stdout reader after forced termination.
        }
        try {
            process.getErrorStream().close();
        } catch (IOException ignored) {
            // Best-effort unblock of the stderr reader after forced termination.
        }
    }

    private VerificationResult result(String verificationId, String runId, String taskId,
                                      int repairAttempt, String command, String status,
                                      Integer exitCode, long durationMs,
                                      String outputExcerpt, String errorExcerpt,
                                      String candidateDigestKind, String candidateDigestValue) {
        VerificationResult result = new VerificationResult(
                verificationId, runId, taskId, repairAttempt, command, status,
                exitCode, durationMs, outputExcerpt, errorExcerpt,
                candidateDigestKind, candidateDigestValue);
        notifyObserver(() -> observer.completed(result));
        return result;
    }

    private void notifyObserver(Runnable notification) {
        try {
            notification.run();
        } catch (RuntimeException ignored) {
            // Observability must not alter verification or its persisted result.
        }
    }

    private void requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }

    private static Process startShellProcess(Path workspace, String command) throws IOException {
        ProcessBuilder processBuilder;
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
            String commandShell = System.getenv().getOrDefault("ComSpec", "cmd.exe");
            processBuilder = new ProcessBuilder(commandShell, "/d", "/s", "/c", command);
        } else {
            processBuilder = new ProcessBuilder("/bin/sh", "-c", command);
        }
        return processBuilder.directory(workspace.toFile()).start();
    }

    @FunctionalInterface
    interface ProcessLauncher {
        Process start(Path workspace, String command) throws IOException;
    }

    private record StreamCapture(byte[] bytes, long observedBytes, IOException error) {
    }
}
