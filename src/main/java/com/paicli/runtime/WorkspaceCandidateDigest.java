package com.paicli.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Computes a Git-independent identity for verifier-visible workspace inputs. */
final class WorkspaceCandidateDigest {
    static final String KIND = "WORKSPACE_CONTENT_SHA256_V1";
    static final int MAX_FILES = 100_000;
    static final long MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024;

    private static final byte[] DOMAIN = "PAICLI_WORKSPACE_CONTENT_SHA256_V1\0"
            .getBytes(StandardCharsets.US_ASCII);
    private static final Set<String> EXCLUDED_NAMES = Set.of(
            ".git", ".paicli", "target", "build", "out", ".gradle", ".idea",
            "node_modules");

    private final Path workspace;

    WorkspaceCandidateDigest(Path workspace) throws IOException {
        Path normalized = Objects.requireNonNull(workspace, "workspace")
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(normalized)) {
            throw failure(Code.UNSAFE_WORKSPACE);
        }
        BasicFileAttributes attributes = readAttributes(normalized, Code.UNSAFE_WORKSPACE);
        if (attributes.isOther()) throw failure(Code.UNSAFE_WORKSPACE);
        this.workspace = normalized.toRealPath();
    }

    Result compute() throws Failure {
        List<Entry> entries = discover();
        entries.sort(Comparator.comparing(Entry::relativePath));
        long expectedTotal = totalBytes(entries);
        MessageDigest digest = sha256();
        digest.update(DOMAIN);
        putInt(digest, entries.size());
        long observedTotal = 0;
        for (Entry entry : entries) {
            byte[] path = entry.relativePath().getBytes(StandardCharsets.UTF_8);
            putInt(digest, path.length);
            digest.update(path);
            putLong(digest, entry.attributes().size());
            digest.update(hashFile(entry));
            observedTotal = addBytes(observedTotal, entry.attributes().size());
        }
        if (observedTotal != expectedTotal) throw failure(Code.WORKSPACE_CHANGED);
        putLong(digest, observedTotal);
        requireSameDiscovery(entries);
        return new Result(KIND, HexFormat.of().formatHex(digest.digest()), entries.size(),
                observedTotal);
    }

    private List<Entry> discover() throws Failure {
        List<Entry> entries = new ArrayList<>();
        try {
            Files.walkFileTree(workspace, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory,
                        BasicFileAttributes attributes) throws IOException {
                    if (!directory.equals(workspace) && excludedDirectory(directory)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    requireSafeDirectory(directory, attributes);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                        throws IOException {
                    if (excludedFile(file)) return FileVisitResult.CONTINUE;
                    requireSafeFile(file, attributes);
                    if (entries.size() == MAX_FILES) throw failure(Code.FILE_LIMIT_EXCEEDED);
                    entries.add(new Entry(relative(file), snapshot(attributes)));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException error)
                        throws IOException {
                    throw failure(Code.UNREADABLE_ENTRY);
                }
            });
            return entries;
        } catch (Failure error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            throw failure(Code.DISCOVERY_FAILED);
        }
    }

    private byte[] hashFile(Entry entry) throws Failure {
        Path file = workspace.resolve(entry.relativePath()).normalize();
        if (!file.startsWith(workspace)) throw failure(Code.WORKSPACE_CHANGED);
        BasicFileAttributes before = readAttributes(file, Code.WORKSPACE_CHANGED);
        requireSameFile(entry.attributes(), before);
        MessageDigest content = sha256();
        long observed = 0;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream input = Files.newInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                observed = addBytes(observed, read);
                if (observed > entry.attributes().size()) {
                    throw failure(Code.WORKSPACE_CHANGED);
                }
                content.update(buffer, 0, read);
            }
        } catch (Failure error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            throw failure(Code.READ_FAILED);
        }
        if (observed != entry.attributes().size()) throw failure(Code.WORKSPACE_CHANGED);
        BasicFileAttributes after = readAttributes(file, Code.WORKSPACE_CHANGED);
        requireSameFile(entry.attributes(), after);
        return content.digest();
    }

    private void requireSameDiscovery(List<Entry> expected) throws Failure {
        List<Entry> actual = discover();
        actual.sort(Comparator.comparing(Entry::relativePath));
        if (actual.size() != expected.size()) throw failure(Code.WORKSPACE_CHANGED);
        for (int index = 0; index < expected.size(); index++) {
            Entry left = expected.get(index);
            Entry right = actual.get(index);
            if (!left.relativePath().equals(right.relativePath())) {
                throw failure(Code.WORKSPACE_CHANGED);
            }
            requireSameFile(left.attributes(), right.attributes());
        }
    }

    private long totalBytes(List<Entry> entries) throws Failure {
        long total = 0;
        for (Entry entry : entries) total = addBytes(total, entry.attributes().size());
        return total;
    }

    private static long addBytes(long current, long added) throws Failure {
        if (added < 0 || current > MAX_TOTAL_BYTES - added) {
            throw failure(Code.BYTE_LIMIT_EXCEEDED);
        }
        return current + added;
    }

    private void requireSafeDirectory(Path directory, BasicFileAttributes attributes)
            throws Failure {
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()
                || Files.isSymbolicLink(directory) || !Files.isReadable(directory)) {
            throw failure(Code.UNSAFE_ENTRY);
        }
        requireInsideWorkspace(directory);
    }

    private void requireSafeFile(Path file, BasicFileAttributes attributes) throws Failure {
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()
                || Files.isSymbolicLink(file) || !Files.isReadable(file)) {
            throw failure(Code.UNSAFE_ENTRY);
        }
        requireInsideWorkspace(file);
    }

    private void requireInsideWorkspace(Path path) throws Failure {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            Path real = path.toRealPath();
            if (!real.startsWith(workspace) || !real.equals(normalized)) {
                throw failure(Code.UNSAFE_ENTRY);
            }
        } catch (Failure error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            throw failure(Code.UNREADABLE_ENTRY);
        }
    }

    private static BasicFileAttributes readAttributes(Path path, Code code) throws Failure {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | RuntimeException error) {
            throw failure(code);
        }
    }

    private static Attributes snapshot(BasicFileAttributes attributes) {
        return new Attributes(attributes.size(), attributes.lastModifiedTime().toMillis(),
                attributes.fileKey() == null ? null : attributes.fileKey().toString());
    }

    private static void requireSameFile(Attributes expected, BasicFileAttributes actual)
            throws Failure {
        requireSameFile(expected, snapshot(actual));
        if (!actual.isRegularFile() || actual.isSymbolicLink() || actual.isOther()) {
            throw failure(Code.WORKSPACE_CHANGED);
        }
    }

    private static void requireSameFile(Attributes expected, Attributes actual) throws Failure {
        if (expected.size() != actual.size()
                || expected.modifiedMillis() != actual.modifiedMillis()
                || !Objects.equals(expected.fileKey(), actual.fileKey())) {
            throw failure(Code.WORKSPACE_CHANGED);
        }
    }

    private boolean excludedDirectory(Path path) {
        Path name = path.getFileName();
        return name != null && EXCLUDED_NAMES.contains(
                name.toString().toLowerCase(Locale.ROOT));
    }

    private boolean excludedFile(Path path) {
        Path name = path.getFileName();
        return name != null && ".git".equalsIgnoreCase(name.toString());
    }

    private String relative(Path file) {
        return workspace.relativize(file.toAbsolutePath().normalize()).toString()
                .replace('\\', '/');
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static void putInt(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }

    private static void putLong(MessageDigest digest, long value) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }

    private static Failure failure(Code code) {
        return new Failure(code);
    }

    record Result(String kind, String digest, int fileCount, long totalBytes) { }

    enum Code {
        UNSAFE_WORKSPACE,
        UNSAFE_ENTRY,
        UNREADABLE_ENTRY,
        DISCOVERY_FAILED,
        READ_FAILED,
        WORKSPACE_CHANGED,
        FILE_LIMIT_EXCEEDED,
        BYTE_LIMIT_EXCEEDED
    }

    static final class Failure extends IOException {
        private final Code code;

        private Failure(Code code) {
            super(code.name());
            this.code = code;
        }

        Code code() {
            return code;
        }
    }

    private record Entry(String relativePath, Attributes attributes) { }
    private record Attributes(long size, long modifiedMillis, String fileKey) { }
}
