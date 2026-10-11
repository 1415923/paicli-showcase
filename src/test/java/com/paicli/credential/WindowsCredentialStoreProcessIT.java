package com.paicli.credential;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Recoverable OS-store integration: the unique test credential is always deleted. */
@EnabledOnOs(OS.WINDOWS)
class WindowsCredentialStoreProcessIT {
    @Test void credentialWrittenByOneProcessIsReadableAfterProcessBoundary() throws Exception {
        WindowsCredentialStore store = new WindowsCredentialStore();
        CredentialRef ref = new CredentialRef("PaiCLI/test/process/" + UUID.randomUUID());
        char[] secret = ("process-fixture-" + UUID.randomUUID()).toCharArray();
        try {
            store.put(ref, secret);
            Path java = Path.of(System.getProperty("java.home"), "bin", "java.exe");
            Process child = new ProcessBuilder(java.toString(), "-cp",
                    System.getProperty("java.class.path"), getClass().getName(), ref.value())
                    .redirectErrorStream(true).start();
            assertTrue(child.waitFor(Duration.ofSeconds(15).toMillis(), TimeUnit.MILLISECONDS));
            assertEquals(0, child.exitValue(), "new JVM could not resolve the OS credential");
            assertEquals("", new String(child.getInputStream().readAllBytes()),
                    "credential probe must not write values or metadata");
        } finally {
            Arrays.fill(secret, '\0');
            store.delete(ref);
        }
    }

    public static void main(String[] arguments) {
        if (arguments.length != 1) System.exit(2);
        char[] value = null;
        try {
            value = new WindowsCredentialStore().get(new CredentialRef(arguments[0])).orElse(null);
            System.exit(value == null || value.length == 0 ? 1 : 0);
        } catch (RuntimeException error) {
            System.exit(3);
        } finally {
            if (value != null) Arrays.fill(value, '\0');
        }
    }
}
