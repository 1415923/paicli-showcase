package com.paicli.distribution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DistributionReleaseIT {
    private static final String SEMANTIC_HASH =
            "a6f19c2e4ce5d0c9ad3a9fae2d1ef08c6e3cc93ea0a1922fb4c402fdb2d98b35";

    @TempDir
    Path tempDir;

    @Test
    void packagedJarAndPlatformLaunchersRunTheProductionPath() throws Exception {
        Path project = Path.of("").toAbsolutePath().normalize();
        Path jar = project.resolve("target/paicli-1.2.0.jar");
        Path dist = project.resolve("target/dist/paicli-1.2.0");
        assertTrue(Files.isRegularFile(jar), "packaged JAR must exist before Failsafe runs");
        assertTrue(Files.isRegularFile(project.resolve("target/dist/paicli-1.2.0.zip")));

        String java = Path.of(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toString();
        Result version = run(List.of(java, "-Duser.home=" + tempDir, "-jar", jar.toString(), "--version"),
                project, Duration.ofSeconds(20));
        assertEquals(0, version.exitCode());
        assertTrue(version.output().contains("PaiCLI 1.2.0"));
        assertNoSlf4jFallbackWarning(version.output());

        Result help = run(List.of(java, "-Duser.home=" + tempDir, "-jar", jar.toString(), "--help"),
                project, Duration.ofSeconds(20));
        assertEquals(0, help.exitCode());
        assertTrue(help.output().contains("paicli doctor"));
        assertTrue(help.output().contains("paicli demo core --headless"));
        assertNoSlf4jFallbackWarning(help.output());

        Result doctor = run(List.of(java, "-Duser.home=" + tempDir, "-jar", jar.toString(), "doctor"),
                project, Duration.ofSeconds(30));
        assertEquals(0, doctor.exitCode(), doctor.output());
        assertTrue(doctor.output().contains("PaiCLI Doctor"));
        assertNoSlf4jFallbackWarning(doctor.output());

        Result demo = run(List.of(java, "-Duser.home=" + tempDir, "-jar", jar.toString(),
                        "demo", "core", "--headless"), project, Duration.ofMinutes(3));
        assertEquals(0, demo.exitCode(), demo.output());
        assertTrue(demo.output().contains("status: COMPLETED"), demo.output());
        assertNoSlf4jFallbackWarning(demo.output());
        try (var files = Files.walk(tempDir)) {
            assertTrue(files.filter(path -> path.getFileName().toString().equals("summary.json"))
                    .map(this::readString)
                    .anyMatch(text -> text.contains(SEMANTIC_HASH)), "committed summary must contain frozen hash");
        }

        Path spacedDist = tempDir.resolve("distribution with spaces");
        copyTree(dist, spacedDist);
        if (isWindows()) {
            Result ps = run(List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                            spacedDist.resolve("bin/paicli.ps1").toString(), "--version"), project, Duration.ofSeconds(30));
            assertEquals(0, ps.exitCode(), ps.output());
            assertTrue(ps.output().contains("PaiCLI 1.2.0"));
            Result cmd = run(List.of("cmd.exe", "/d", "/s", "/c",
                            "\"" + spacedDist.resolve("bin/paicli.cmd") + "\" --help"), project, Duration.ofSeconds(30));
            assertEquals(0, cmd.exitCode(), cmd.output());
            assertTrue(cmd.output().contains("paicli doctor"));
            Result propagated = run(List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                            spacedDist.resolve("bin/paicli.ps1").toString(), "doctor", "--invalid"),
                    project, Duration.ofSeconds(30));
            assertEquals(2, propagated.exitCode(), propagated.output());
        } else {
            Result sh = run(List.of("sh", spacedDist.resolve("bin/paicli").toString(), "--version"),
                    project, Duration.ofSeconds(30));
            assertEquals(0, sh.exitCode(), sh.output());
            assertTrue(sh.output().contains("PaiCLI 1.2.0"));
            Result propagated = run(List.of("sh", spacedDist.resolve("bin/paicli").toString(),
                            "doctor", "--invalid"), project, Duration.ofSeconds(30));
            assertEquals(2, propagated.exitCode(), propagated.output());
        }
    }

    private void copyTree(Path source, Path target) throws Exception {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else Files.copy(path, destination);
            }
        }
    }
    private Result run(List<String> command, Path directory, Duration timeout) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command));
        builder.directory(directory.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> copyBounded(process.getInputStream(), output));
        boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!exited) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
        reader.join(10_000);
        assertTrue(exited, "process timed out: " + command);
        return new Result(process.exitValue(), output.toString(StandardCharsets.UTF_8));
    }

    private void copyBounded(InputStream input, ByteArrayOutputStream output) {
        byte[] buffer = new byte[4096];
        int total = 0;
        try (input) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                int accepted = Math.min(read, 256 * 1024 - total);
                if (accepted > 0) {
                    output.write(buffer, 0, accepted);
                    total += accepted;
                }
            }
        } catch (Exception ignored) {
            // The process result carries the useful release-smoke failure.
        }
    }

    private String readString(Path path) {
        try {
            return Files.readString(path);
        } catch (Exception exception) {
            return "";
        }
    }

    private void assertNoSlf4jFallbackWarning(String output) {
        assertFalse(output.contains("StaticLoggerBinder"), output);
        assertFalse(output.contains("NOP logger"), output);
        assertFalse(output.contains("Failed to load class \"org.slf4j.impl.StaticLoggerBinder\""), output);
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private record Result(int exitCode, String output) {
    }
}
