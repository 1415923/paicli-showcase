package com.paicli.distribution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
class WindowsLauncherWorkingDirectoryIT {
    @TempDir
    Path temp;

    @Test
    void powershellProviderLocationBecomesJavaAndInteractiveWorkspace()
            throws Exception {
        Path project = Path.of("").toAbsolutePath().normalize();
        Path packaged = project.resolve("target/dist/paicli-1.2.0");
        assertTrue(Files.isRegularFile(packaged.resolve("lib/paicli-1.2.0.jar")));
        Path distribution = temp.resolve("distribution copy");
        copyTree(packaged, distribution);
        Path launcher = distribution.resolve("bin/paicli.ps1");

        Path launchWorkspace = Files.createDirectories(
                temp.resolve("非发行目录 工作区")).toRealPath();
        Path hostWorkingDirectory = Files.createDirectories(
                temp.resolve("powershell-host-cwd")).toRealPath();
        assertFalse(launchWorkspace.startsWith(distribution));

        String invocation = "Set-Location -LiteralPath "
                + psQuote(launchWorkspace.toString()) + "; & "
                + psQuote(launcher.toString());
        ProcessBuilder builder = new ProcessBuilder(
                windowsPowerShell().toString(), "-NoProfile", "-Command", invocation);
        builder.directory(hostWorkingDirectory.toFile());
        Path isolatedHome = Files.createDirectories(temp.resolve("isolated-home"));
        builder.environment().put("HOME", isolatedHome.toString());
        builder.environment().put("USERPROFILE", isolatedHome.toString());

        Process process = builder.start();
        CompletableFuture<String> stdout = read(process.getInputStream());
        CompletableFuture<String> stderr = read(process.getErrorStream());
        try (var input = new OutputStreamWriter(
                process.getOutputStream(), StandardCharsets.UTF_8)) {
            input.write("/exit" + System.lineSeparator());
            input.flush();
        }
        boolean exited = process.waitFor(Duration.ofSeconds(30).toMillis(),
                TimeUnit.MILLISECONDS);
        if (!exited) process.destroyForcibly();
        assertTrue(exited, "launcher timed out: " + stderr.getNow(""));
        assertEquals(0, process.exitValue(), stderr.get(5, TimeUnit.SECONDS));

        String output = stdout.get(5, TimeUnit.SECONDS);
        String trace = Files.readString(
                distribution.resolve("diagnostics/paicli-stage-trace.log"));
        assertTrue(output.contains("workspace: " + launchWorkspace.getFileName()), output);
        assertTrue(trace.contains("stage=PROCESS_START"));
        assertTrue(trace.contains("cwd=" + launchWorkspace));
        assertTrue(trace.contains("stage=WORKSPACE_SELECTED"));
        assertTrue(trace.contains("selected=" + launchWorkspace));
        assertFalse(trace.contains("selected=" + hostWorkingDirectory));
    }

    @Test
    void nativeInvocationPreservesSpacedPathsArgumentsStreamsAndExitCode()
            throws Exception {
        Path project = Path.of("").toAbsolutePath().normalize();
        Path packaged = project.resolve("target/dist/paicli-1.2.0");
        Path distribution = temp.resolve("distribution with spaces");
        copyTree(packaged, distribution);
        Path launcher = distribution.resolve("bin/paicli.ps1");
        Path workspace = Files.createDirectories(
                temp.resolve("参数 流 工作区")).toRealPath();
        Path shimDirectory = Files.createDirectories(
                temp.resolve("java executable with spaces"));
        Path evidence = temp.resolve("native-invocation-evidence.txt");
        Path javaShim = shimDirectory.resolve("java.cmd");
        Files.writeString(javaShim, "@echo off\r\n"
                + "chcp 65001 >nul\r\n"
                + "> \"%PAICLI_SHIM_EVIDENCE%\" (\r\n"
                + "  echo CWD=%CD%\r\n"
                + "  echo ARG1=%~1\r\n"
                + "  echo ARG2=%~2\r\n"
                + "  echo ARG3=%~3\r\n"
                + "  echo ARG4=%~4\r\n"
                + ")\r\n"
                + "set /p PAICLI_SHIM_INPUT=\r\n"
                + "echo SHIM_STDOUT:%PAICLI_SHIM_INPUT%\r\n"
                + "1>&2 echo SHIM_STDERR\r\n"
                + "exit /b 37\r\n", StandardCharsets.US_ASCII);

        ProcessBuilder builder = new ProcessBuilder(
                windowsPowerShell().toString(), "-NoProfile", "-File",
                launcher.toString(), "argument with spaces", "plain");
        builder.directory(workspace.toFile());
        builder.environment().put("PAICLI_SHIM_EVIDENCE", evidence.toString());
        builder.environment().put("PATH", shimDirectory + ";"
                + builder.environment().getOrDefault("PATH", ""));

        Process process = builder.start();
        CompletableFuture<String> stdout = read(process.getInputStream());
        CompletableFuture<String> stderr = read(process.getErrorStream());
        try (var input = new OutputStreamWriter(
                process.getOutputStream(), StandardCharsets.UTF_8)) {
            input.write("inherited-input" + System.lineSeparator());
            input.flush();
        }
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        assertEquals(37, process.exitValue());

        String output = stdout.get(5, TimeUnit.SECONDS);
        String error = stderr.get(5, TimeUnit.SECONDS);
        String invocation = Files.readString(evidence);
        assertTrue(output.contains("SHIM_STDOUT:inherited-input"), output);
        assertTrue(error.contains("SHIM_STDERR"), error);
        assertTrue(invocation.contains("CWD=" + workspace), invocation);
        assertTrue(invocation.contains("ARG1=-jar"), invocation);
        assertTrue(invocation.contains("ARG2="
                + distribution.resolve("lib/paicli-1.2.0.jar")), invocation);
        assertTrue(invocation.contains("ARG3=argument with spaces"), invocation);
        assertTrue(invocation.contains("ARG4=plain"), invocation);
    }

    private static CompletableFuture<String> read(java.io.InputStream input) {
        return CompletableFuture.supplyAsync(() -> {
            try (input) {
                return new String(input.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
    }

    private static String psQuote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static Path windowsPowerShell() {
        return Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell",
                "v1.0", "powershell.exe");
    }

    private static void copyTree(Path source, Path target) throws Exception {
        try (var paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }
}
