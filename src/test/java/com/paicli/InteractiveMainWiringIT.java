package com.paicli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class InteractiveMainWiringIT {
    @TempDir Path temp;

    @Test void zeroArgumentsEnterRealShellWithUnconfiguredProviderLimitedMode() throws Exception {
        Path workspace = temp.resolve("workspace");
        Path home = temp.resolve("home");
        Files.createDirectories(workspace);
        Files.createDirectories(home);
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win")
                        ? "java.exe" : "java").toString();
        Process process = new ProcessBuilder(java,
                "-Duser.home=" + home,
                "-Duser.dir=" + workspace,
                "-cp", System.getProperty("java.class.path"),
                "com.paicli.PaiCLI")
                .redirectErrorStream(true)
                .start();
        process.getOutputStream().write("/exit\n".getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        assertTimeoutPreemptively(Duration.ofSeconds(15), () -> {
            assertTrue(process.waitFor(12, TimeUnit.SECONDS));
        });
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("PaiCLI 1.2.0 Interactive"), output);
        assertTrue(output.contains("No production provider configured"), output);
        assertTrue(output.contains("Session closed"), output);
        assertFalse(output.contains("MockModelProvider"), output);
        assertFalse(output.contains(home.toAbsolutePath().toString()), output);
        assertFalse(process.isAlive());
    }
}
