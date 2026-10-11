package com.paicli.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BoundedProcessExecutorIT {
    @TempDir Path workspace;

    @Test
    void drainsLargeStdoutAndStderrWhileProcessRuns() {
        ProcessBuilder builder = javaProcess("large").directory(workspace.toFile());
        var result = new BoundedProcessExecutor(4096).execute(builder, Duration.ofSeconds(15));

        assertEquals(BoundedProcessExecutor.Status.SUCCEEDED, result.status());
        assertTrue(result.stdoutTruncated());
        assertTrue(result.stderrTruncated());
        assertTrue(result.stdout().contains("stream truncated"));
        assertTrue(result.stderr().contains("stream truncated"));
        assertTrue(result.stdout().length() < 4300);
        assertTrue(result.stderr().length() < 4300);
    }

    @Test
    void timeoutTerminatesChildAndReturnsBoundedly() {
        long started = System.nanoTime();
        var result = new BoundedProcessExecutor(1024).execute(
                javaProcess("sleep").directory(workspace.toFile()), Duration.ofMillis(200));

        assertEquals(BoundedProcessExecutor.Status.TIMED_OUT, result.status());
        assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(5)) < 0);
    }

    @Test
    void registeredProcessCanBeForceCancelledAndIsUnregistered() throws Exception {
        OwnedProcessRegistry registry = new OwnedProcessRegistry();
        CompletableFuture<BoundedProcessExecutor.Result> result =
                CompletableFuture.supplyAsync(() -> new BoundedProcessExecutor(1024, registry)
                        .execute(javaProcess("sleep").directory(workspace.toFile()),
                                Duration.ofSeconds(20)));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (registry.liveCount() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(1, registry.liveCount());
        assertEquals(1, registry.forceTerminateAll());
        assertNotNull(result.get(5, TimeUnit.SECONDS));
        assertEquals(0, registry.liveCount());
    }

    @Test
    void cleansObservedGrandchildAfterItsParentExitsNormally() throws Exception {
        var result = new BoundedProcessExecutor(1024).execute(javaProcess("orphan").directory(workspace.toFile()),Duration.ofSeconds(10));
        long pid=Long.parseLong(Files.readString(workspace.resolve("child.pid")));
        var child=ProcessHandle.of(pid);
        try {
            assertEquals(BoundedProcessExecutor.Status.SUCCEEDED,result.status());
            assertTrue(child.isEmpty() || !child.get().isAlive(),"observed grandchild must not survive normal parent exit");
        } finally { child.filter(ProcessHandle::isAlive).ifPresent(ProcessHandle::destroyForcibly); }
    }

    private ProcessBuilder javaProcess(String mode) {
        String java = Path.of(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toString();
        return new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                ChildMain.class.getName(), mode);
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("windows");
    }

    public static final class ChildMain {
        public static void main(String[] args) throws Exception {
            if ("orphan".equals(args[0])) {
                String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").toLowerCase().contains("windows")?"java.exe":"java").toString();
                Process child=new ProcessBuilder(java,"-cp",System.getProperty("java.class.path"),ChildMain.class.getName(),"sleep").inheritIO().start();
                Files.writeString(Path.of("child.pid"),Long.toString(child.pid()));
                Thread.sleep(700);
                return;
            }
            if ("sleep".equals(args[0])) {
                Thread.sleep(30_000);
                return;
            }
            byte[] block = "0123456789abcdef0123456789abcdef\n".repeat(128).getBytes();
            for (int i = 0; i < 512; i++) {
                System.out.write(block);
                System.err.write(block);
            }
            System.out.flush();
            System.err.flush();
        }
    }
}
