package com.paicli.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedProcessRunnerIT {

    @TempDir
    Path workspace;

    @Test
    void capturesParallelStreamsWithSanitizationAndBounds() throws Exception {
        BoundedProcessRunner runner = runner(160);
        String command = isWindows()
                ? "echo Authorization: Bearer stdout-secret & echo apiKey=stderr-secret 1>&2 & echo " + "x".repeat(1000)
                : "echo 'Authorization: Bearer stdout-secret'; echo 'apiKey=stderr-secret' >&2; printf '%s' '" + "x".repeat(1000) + "'";

        BoundedProcessRunner.ProcessResult result = runner.execute(command, 5);

        assertEquals(BoundedProcessRunner.Status.PASSED, result.status());
        assertFalse(result.outputExcerpt().contains("stdout-secret"));
        assertFalse(result.errorExcerpt().contains("stderr-secret"));
        assertTrue(result.outputExcerpt().getBytes(StandardCharsets.UTF_8).length <= 160);
        assertTrue(result.errorExcerpt().getBytes(StandardCharsets.UTF_8).length <= 160);
    }

    @Test
    void reportsFailedTimedOutAndLaunchError() throws Exception {
        BoundedProcessRunner runner = runner(1024);
        BoundedProcessRunner.ProcessResult failed = runner.execute(
                isWindows() ? "exit /b 7" : "exit 7", 5);
        assertEquals(BoundedProcessRunner.Status.FAILED, failed.status());
        assertEquals(7, failed.exitCode());

        BoundedProcessRunner.ProcessResult timedOut = runner.execute(
                isWindows() ? "ping -n 6 127.0.0.1 >NUL" : "sleep 5", 1);
        assertEquals(BoundedProcessRunner.Status.TIMED_OUT, timedOut.status());
        assertNull(timedOut.exitCode());

        BoundedProcessRunner launchFailure = new BoundedProcessRunner(
                workspace, new PersistenceSanitizer(), 1024,
                (path, command) -> { throw new IOException("Bearer launch-secret"); });
        BoundedProcessRunner.ProcessResult error = launchFailure.execute("echo x", 5);
        assertEquals(BoundedProcessRunner.Status.ERROR, error.status());
        assertFalse(error.errorExcerpt().contains("launch-secret"));
    }

    private BoundedProcessRunner runner(int maxBytes) throws IOException {
        return new BoundedProcessRunner(workspace, new PersistenceSanitizer(), maxBytes);
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }
}
