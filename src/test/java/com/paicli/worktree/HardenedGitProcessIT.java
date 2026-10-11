package com.paicli.worktree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HardenedGitProcessIT {
    @TempDir Path tempDir;

    @Test
    void commandUsesArgumentArrayWithoutShellAndDisablesHooks() {
        HardenedGitProcess process = new HardenedGitProcess("git", tempDir.resolve("hooks"),
                Duration.ofSeconds(2), 1024, builder -> new StubProcess(0, "ok", "", true));
        List<String> command = process.buildCommand(HardenedGitProcess.Operation.WORKTREE_ADD,
                List.of("paicli/worker/w1", "C:/path with spaces/w1", "a".repeat(40)));
        assertEquals("git", command.get(0));
        assertEquals("-c", command.get(1));
        assertTrue(command.get(2).startsWith("core.hooksPath="));
        assertEquals("C:/path with spaces/w1", command.get(7));
        assertFalse(command.stream().anyMatch(value -> value.equalsIgnoreCase("cmd.exe")
                || value.equals("-Command") || value.equals("-c") && command.indexOf(value) > 2));
    }

    @Test
    void untrustedGitEnvironmentIsRemovedAndSafeValuesAreAdded() {
        Map<String, String> inherited = new HashMap<>();
        inherited.put("PATH", "safe");
        inherited.put("GIT_DIR", "attacker");
        inherited.put("git_config_key_0", "core.hooksPath");
        inherited.put("GIT_SSH_COMMAND", "bad");
        Map<String, String> sanitized = HardenedGitProcess.sanitizedEnvironment(inherited);
        assertEquals("safe", sanitized.get("PATH"));
        assertFalse(sanitized.containsKey("GIT_DIR"));
        assertFalse(sanitized.containsKey("git_config_key_0"));
        assertFalse(sanitized.containsKey("GIT_SSH_COMMAND"));
        assertEquals("0", sanitized.get("GIT_TERMINAL_PROMPT"));
        assertEquals("1", sanitized.get("GIT_CONFIG_NOSYSTEM"));
    }

    @Test
    void launcherReceivesSanitizedEnvironmentAndIndependentArguments() {
        AtomicReference<ProcessBuilder> captured = new AtomicReference<>();
        HardenedGitProcess process = new HardenedGitProcess("git", tempDir.resolve("hooks"),
                Duration.ofSeconds(2), 1024, builder -> {
                    captured.set(builder);
                    return new StubProcess(0, "ok", "", true);
                });
        HardenedGitProcess.Result result = process.execute(tempDir,
                HardenedGitProcess.Operation.CHECK_BRANCH, "paicli/worker/w1");
        assertTrue(result.succeeded());
        assertEquals(tempDir.toFile(), captured.get().directory());
        assertEquals("paicli/worker/w1", captured.get().command().get(5));
        assertEquals("0", captured.get().environment().get("GIT_TERMINAL_PROMPT"));
        assertTrue(captured.get().environment().get("GIT_CONFIG_GLOBAL")
                .endsWith(".paicli-empty-gitconfig"));
    }

    @Test
    void outputIsBoundedAndNonZeroAndTimeoutAreTyped() {
        HardenedGitProcess longOutput = new HardenedGitProcess("git", tempDir,
                Duration.ofSeconds(2), 256,
                builder -> new StubProcess(0, "x".repeat(2000), "", true));
        HardenedGitProcess.Result bounded = longOutput.execute(tempDir,
                HardenedGitProcess.Operation.TOP_LEVEL);
        assertTrue(bounded.stdoutExcerpt().contains("truncated"));

        HardenedGitProcess nonZero = new HardenedGitProcess("git", tempDir,
                Duration.ofSeconds(2), 256,
                builder -> new StubProcess(7, "", "failure", true));
        assertEquals(HardenedGitProcess.Status.NON_ZERO,
                nonZero.execute(tempDir, HardenedGitProcess.Operation.TOP_LEVEL).status());

        HardenedGitProcess timeout = new HardenedGitProcess("git", tempDir,
                Duration.ofMillis(1), 256,
                builder -> new StubProcess(0, "", "", false));
        assertEquals(HardenedGitProcess.Status.TIMED_OUT,
                timeout.execute(tempDir, HardenedGitProcess.Operation.TOP_LEVEL).status());
    }

    @Test
    void operationAllowListRejectsUnexpectedValues() {
        HardenedGitProcess process = new HardenedGitProcess("git", tempDir,
                Duration.ofSeconds(2), 256, builder -> new StubProcess(0, "", "", true));
        assertThrows(IllegalArgumentException.class,
                () -> process.buildCommand(HardenedGitProcess.Operation.HEAD, List.of("status")));
        assertThrows(IllegalArgumentException.class,
                () -> process.buildCommand(HardenedGitProcess.Operation.CHECK_BRANCH,
                        List.of("safe\n--upload-pack=bad")));
    }

    @Test
    void operationAllowListDoesNotExposeWorktreePruneOrRepair() {
        assertTrue(Arrays.stream(HardenedGitProcess.Operation.values())
                .map(Enum::name)
                .noneMatch(name -> name.contains("PRUNE") || name.contains("REPAIR")));
    }

    private static final class StubProcess extends Process {
        private final int exitCode;
        private final InputStream stdout;
        private final InputStream stderr;
        private final OutputStream stdin = new ByteArrayOutputStream();
        private final boolean finishes;
        private boolean alive = true;

        private StubProcess(int exitCode, String stdout, String stderr, boolean finishes) {
            this.exitCode = exitCode;
            this.stdout = new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8));
            this.stderr = new ByteArrayInputStream(stderr.getBytes(StandardCharsets.UTF_8));
            this.finishes = finishes;
        }
        @Override public OutputStream getOutputStream() { return stdin; }
        @Override public InputStream getInputStream() { return stdout; }
        @Override public InputStream getErrorStream() { return stderr; }
        @Override public int waitFor() { alive = false; return exitCode; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) {
            if (finishes) alive = false;
            return finishes;
        }
        @Override public int exitValue() { return exitCode; }
        @Override public void destroy() { alive = false; }
        @Override public Process destroyForcibly() { alive = false; return this; }
        @Override public boolean isAlive() { return alive; }
    }
}
