package com.paicli.test;

import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDirFactory;
import org.opentest4j.TestAbortedException;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.locks.LockSupport;

/**
 * Owns JUnit temp-directory cleanup so transient Windows filesystem races are retried.
 */
public final class RetryingTempDirFactory implements TempDirFactory {

    private static final int MAX_DELETE_ATTEMPTS = 20;
    private static final long MAX_BACKOFF_MILLIS = 50;

    private Path directory;
    private ExtensionContext extensionContext;
    private String testId;

    @Override
    public Path createTempDirectory(AnnotatedElementContext elementContext,
                                    ExtensionContext extensionContext)
            throws IOException {
        if (directory != null) {
            throw new IOException("Temp directory was already created");
        }
        this.extensionContext = extensionContext;
        this.testId = shortTestId(extensionContext.getUniqueId());
        directory = Files.createTempDirectory("junit-" + testId + "-");
        return directory;
    }

    @Override
    public void close() throws IOException {
        if (directory == null || Files.notExists(directory)) {
            return;
        }
        if (shouldPreserveFailure()) {
            System.err.println("PAICLI_TEST_FAILURE_WORKSPACE="
                    + directory.toAbsolutePath().normalize()
                    + " testId=" + testId);
            return;
        }

        IOException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_DELETE_ATTEMPTS; attempt++) {
            try {
                deleteRecursively(directory);
                return;
            } catch (IOException failure) {
                lastFailure = failure;
                if (attempt < MAX_DELETE_ATTEMPTS) {
                    long backoffMillis = Math.min(
                            MAX_BACKOFF_MILLIS, 5L * attempt);
                    LockSupport.parkNanos(backoffMillis * 1_000_000L);
                }
            }
        }

        throw new IOException(
                "Failed to delete JUnit temp directory after "
                        + MAX_DELETE_ATTEMPTS + " attempts: " + directory,
                lastFailure);
    }

    private static String shortTestId(String uniqueId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(uniqueId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private boolean shouldPreserveFailure() {
        if (extensionContext == null) {
            return false;
        }
        return extensionContext.getExecutionException()
                .filter(failure -> !(failure instanceof TestAbortedException))
                .isPresent();
    }

    private void deleteRecursively(Path root) throws IOException {
        if (Files.notExists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(
                    Path file, BasicFileAttributes attributes)
                    throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(
                    Path dir, IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
