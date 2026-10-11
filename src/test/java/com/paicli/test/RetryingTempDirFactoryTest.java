package com.paicli.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.opentest4j.TestAbortedException;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetryingTempDirFactoryTest {

    @Test
    void successfulTestWorkspaceIsDeleted() throws Exception {
        RetryingTempDirFactory factory = new RetryingTempDirFactory();
        Path directory = factory.createTempDirectory(
                null, context("[engine:junit]/[test:success]", Optional.empty()));
        Files.writeString(directory.resolve("marker.txt"), "success");

        factory.close();

        assertFalse(Files.exists(directory));
    }

    @Test
    void failedTestWorkspaceIsPreserved() throws Exception {
        RetryingTempDirFactory factory = new RetryingTempDirFactory();
        Path directory = factory.createTempDirectory(
                null, context("[engine:junit]/[test:failure]",
                        Optional.of(new AssertionError("injected test failure"))));
        try {
            Files.writeString(directory.resolve("marker.txt"), "failure");

            factory.close();

            assertTrue(Files.isDirectory(directory));
            assertTrue(Files.isRegularFile(directory.resolve("marker.txt")));
        } finally {
            Files.deleteIfExists(directory.resolve("marker.txt"));
            Files.deleteIfExists(directory);
        }
    }

    @Test
    void abortedTestWorkspaceIsDeleted() throws Exception {
        RetryingTempDirFactory factory = new RetryingTempDirFactory();
        Path directory = factory.createTempDirectory(
                null, context("[engine:junit]/[test:aborted]",
                        Optional.of(new TestAbortedException("injected skip"))));
        Files.writeString(directory.resolve("marker.txt"), "aborted");

        factory.close();

        assertFalse(Files.exists(directory));
    }

    private static ExtensionContext context(
            String uniqueId, Optional<Throwable> executionFailure) {
        return (ExtensionContext) Proxy.newProxyInstance(
                RetryingTempDirFactoryTest.class.getClassLoader(),
                new Class<?>[]{ExtensionContext.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUniqueId" -> uniqueId;
                    case "getExecutionException" -> executionFailure;
                    case "toString" -> "TestExtensionContext[" + uniqueId + "]";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
