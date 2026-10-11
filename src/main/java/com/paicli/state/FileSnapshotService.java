package com.paicli.state;

import com.paicli.runtime.FencedExecutionGuard;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public class FileSnapshotService {

    public static final long DEFAULT_MAX_SNAPSHOT_BYTES = 1024L * 1024L;

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]+");

    private final FileSnapshotStore snapshots;
    private final PathSecurity paths;
    private final long maxSnapshotBytes;
    private final FencedExecutionGuard executionGuard;

    public FileSnapshotService(Path workspace, FileSnapshotStore snapshots) throws IOException {
        this(workspace, snapshots, DEFAULT_MAX_SNAPSHOT_BYTES);
    }

    public FileSnapshotService(Path workspace, FileSnapshotStore snapshots, long maxSnapshotBytes)
            throws IOException {
        this(workspace,snapshots,maxSnapshotBytes,null);
    }

    public FileSnapshotService(Path workspace,FileSnapshotStore snapshots,
            FencedExecutionGuard executionGuard)throws IOException{
        this(workspace,snapshots,DEFAULT_MAX_SNAPSHOT_BYTES,executionGuard);
    }

    private FileSnapshotService(Path workspace,FileSnapshotStore snapshots,long maxSnapshotBytes,
            FencedExecutionGuard executionGuard)throws IOException{
        if (maxSnapshotBytes <= 0) {
            throw new IllegalArgumentException("maxSnapshotBytes must be positive");
        }
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.paths = new PathSecurity(workspace);
        this.maxSnapshotBytes = maxSnapshotBytes;
        this.executionGuard=executionGuard;
    }

    public String captureBeforeWrite(
            String taskId, String toolCallId, Path targetPath) throws IOException, SQLException {
        validateSafeId("taskId", taskId);
        Path target = paths.resolveTarget(targetPath, false);
        rejectSensitivePath(target);

        String snapshotId = UUID.randomUUID().toString();
        boolean originalExists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (!originalExists) {
            recordCaptured(
                    snapshotId,
                    taskId,
                    toolCallId,
                    paths.relativeTarget(target),
                    null,
                    FileSnapshotStore.OPERATION_CREATE,
                    false,
                    0,
                    null
            );
            return snapshotId;
        }

        long reportedSize = Files.size(target);
        if (reportedSize > maxSnapshotBytes) {
            throw new SnapshotException("snapshot target too large: " + reportedSize
                    + " bytes, max " + maxSnapshotBytes);
        }

        byte[] original = Files.readAllBytes(target);
        if (original.length > maxSnapshotBytes) {
            throw new SnapshotException("snapshot target too large: " + original.length
                    + " bytes, max " + maxSnapshotBytes);
        }

        Path snapshotDirectory = paths.snapshotDirectory(taskId, snapshotId);
        Path payload = snapshotDirectory.resolve("original.bin");
        boolean payloadCreated = false;
        try {
            Files.createDirectories(snapshotDirectory);
            paths.verifySnapshotDirectory(snapshotDirectory);
            writeAtomically(payload, original);
            payloadCreated = true;

            recordCaptured(
                    snapshotId,
                    taskId,
                    toolCallId,
                    paths.relativeTarget(target),
                    paths.relativeSnapshot(payload),
                    FileSnapshotStore.OPERATION_OVERWRITE,
                    true,
                    original.length,
                    sha256(original)
            );
            return snapshotId;
        } catch (IOException | SQLException | RuntimeException e) {
            if (payloadCreated) {
                deleteQuietly(payload);
            }
            deleteQuietly(snapshotDirectory);
            throw e;
        }
    }

    public void markAppliedAfterWrite(
            String snapshotId, Path targetPath) throws IOException, SQLException {
        FileSnapshotStore.FileSnapshotRecord snapshot = requireSnapshot(snapshotId);
        if (!FileSnapshotStore.STATUS_CAPTURED.equals(snapshot.status())) {
            throw new SnapshotException("Snapshot is not CAPTURED: " + snapshotId);
        }

        Path target = paths.resolveTarget(targetPath, true);
        rejectSensitivePath(target);
        if (!paths.relativeTarget(target).equals(snapshot.filePath())) {
            throw new SnapshotException("Snapshot target does not match captured file: " + snapshotId);
        }

        String after=sha256(target);
        if(executionGuard!=null)executionGuard.transitionSnapshot(snapshotId,
                FileSnapshotStore.STATUS_CAPTURED,FileSnapshotStore.STATUS_APPLIED,after);
        else snapshots.markApplied(snapshotId,after);
    }

    public void markFailed(String snapshotId, String reason) throws SQLException {
        if(executionGuard!=null)executionGuard.transitionSnapshot(snapshotId,
                FileSnapshotStore.STATUS_CAPTURED,FileSnapshotStore.STATUS_FAILED,null);
        else snapshots.markFailed(snapshotId, reason);
    }

    private void recordCaptured(String id,String taskId,String callId,String filePath,
            String snapshotPath,String operation,boolean existed,long size,String before)
            throws SQLException{
        if(executionGuard!=null)executionGuard.recordSnapshot(id,callId,filePath,snapshotPath,
                operation,existed,size,before);
        else snapshots.recordCaptured(id,taskId,callId,filePath,snapshotPath,operation,existed,
                size,before);
    }

    Path snapshotRoot() {
        return paths.snapshotRoot();
    }

    static boolean isSensitivePath(Path target) {
        Path fileNamePath = target.getFileName();
        if (fileNamePath == null) {
            return false;
        }
        String fileName = fileNamePath.toString().toLowerCase(Locale.ROOT);
        return fileName.equals(".env")
                || fileName.startsWith(".env.")
                || fileName.endsWith(".pem")
                || fileName.endsWith(".key")
                || fileName.equals("id_rsa")
                || fileName.startsWith("credentials.")
                || fileName.startsWith("secrets.");
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[8192];
        try (InputStream input = Files.newInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(byte[] content) {
        return HexFormat.of().formatHex(sha256Digest().digest(content));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private FileSnapshotStore.FileSnapshotRecord requireSnapshot(String snapshotId) throws SQLException {
        return snapshots.findById(snapshotId)
                .orElseThrow(() -> new SQLException("Snapshot not found after persistence: " + snapshotId));
    }

    private void rejectSensitivePath(Path target) throws SnapshotException {
        if (isSensitivePath(target)) {
            throw new SnapshotException("Refusing to snapshot sensitive file path: " + target.getFileName());
        }
    }

    private void validateSafeId(String field, String value) throws SnapshotException {
        if (value == null || !SAFE_ID.matcher(value).matches()
                || ".".equals(value) || "..".equals(value)) {
            throw new SnapshotException("Invalid " + field + " for snapshot storage");
        }
    }

    private void writeAtomically(Path target, byte[] content) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), "snapshot-", ".tmp");
        try {
            Files.write(temporary, content);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // A leftover orphan has no SQLite metadata and cannot be rewound.
        }
    }

    public static class SnapshotException extends IOException {
        public SnapshotException(String message) {
            super(message);
        }
    }

    static final class PathSecurity {

        private final Path workspaceRoot;
        private final Path snapshotRoot;

        PathSecurity(Path workspace) throws IOException {
            Objects.requireNonNull(workspace, "workspace");
            Path workspaceAbsolute = workspace.toAbsolutePath().normalize();
            if (!Files.isDirectory(workspaceAbsolute, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(workspaceAbsolute)) {
                throw new SnapshotException("Workspace must be an existing non-symlink directory");
            }
            this.workspaceRoot = workspaceAbsolute.toRealPath();

            Path requestedSnapshotRoot = workspaceRoot.resolve(".paicli").resolve("snapshots").normalize();
            requireLexicallyInside(requestedSnapshotRoot, workspaceRoot, "Snapshot root");
            verifyExistingChain(requestedSnapshotRoot, workspaceRoot);
            Files.createDirectories(requestedSnapshotRoot);
            verifyExistingChain(requestedSnapshotRoot, workspaceRoot);

            Path realSnapshotRoot = requestedSnapshotRoot.toRealPath();
            if (!realSnapshotRoot.startsWith(workspaceRoot)) {
                throw new SnapshotException("Snapshot root resolves outside workspace");
            }
            this.snapshotRoot = realSnapshotRoot;
        }

        Path resolveTarget(Path input, boolean requireExisting) throws IOException {
            if (input == null) {
                throw new SnapshotException("Snapshot target path must not be null");
            }
            Path candidate = input.isAbsolute()
                    ? input.toAbsolutePath().normalize()
                    : workspaceRoot.resolve(input).normalize();
            requireLexicallyInside(candidate, workspaceRoot, "Snapshot target");
            if (candidate.equals(workspaceRoot)) {
                throw new SnapshotException("Snapshot target must be a file inside workspace");
            }

            verifyExistingChain(candidate, workspaceRoot);
            boolean exists = Files.exists(candidate, LinkOption.NOFOLLOW_LINKS);
            if (!exists) {
                if (requireExisting) {
                    throw new SnapshotException("Snapshot target does not exist");
                }
                return candidate;
            }
            if (Files.isSymbolicLink(candidate)
                    || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                throw new SnapshotException("Snapshot target must be a regular non-symlink file");
            }
            Path realTarget = candidate.toRealPath();
            if (!realTarget.startsWith(workspaceRoot)) {
                throw new SnapshotException("Snapshot target resolves outside workspace");
            }
            return candidate;
        }

        Path resolveStoredTarget(String storedPath, boolean requireExisting) throws IOException {
            try {
                Path relative = Path.of(storedPath);
                if (relative.isAbsolute()) {
                    throw new SnapshotException("Stored snapshot target must be relative");
                }
                return resolveTarget(relative, requireExisting);
            } catch (InvalidPathException | NullPointerException e) {
                throw new SnapshotException("Stored snapshot target is invalid");
            }
        }

        Path resolveStoredPayload(String storedPath) throws IOException {
            try {
                Path relative = Path.of(storedPath);
                if (relative.isAbsolute()) {
                    throw new SnapshotException("Stored snapshot payload must be relative");
                }
                Path candidate = snapshotRoot.resolve(relative).normalize();
                requireLexicallyInside(candidate, snapshotRoot, "Snapshot payload");
                verifyExistingChain(candidate, snapshotRoot);
                if (Files.isSymbolicLink(candidate)
                        || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    throw new SnapshotException("Snapshot payload is missing or not a regular file");
                }
                Path realPayload = candidate.toRealPath();
                if (!realPayload.startsWith(snapshotRoot)) {
                    throw new SnapshotException("Snapshot payload resolves outside snapshot root");
                }
                return candidate;
            } catch (InvalidPathException | NullPointerException e) {
                throw new SnapshotException("Stored snapshot payload is invalid");
            }
        }

        String relativeTarget(Path target) {
            return portableRelative(workspaceRoot.relativize(target));
        }

        String relativeSnapshot(Path payload) {
            return portableRelative(snapshotRoot.relativize(payload));
        }

        private static String portableRelative(Path path) {
            return path.toString().replace('\\', '/');
        }

        Path snapshotDirectory(String taskId, String snapshotId) throws IOException {
            Path directory = snapshotRoot.resolve(taskId).resolve(snapshotId).normalize();
            requireLexicallyInside(directory, snapshotRoot, "Snapshot directory");
            return directory;
        }

        void verifySnapshotDirectory(Path directory) throws IOException {
            verifyExistingChain(directory, snapshotRoot);
            Path realDirectory = directory.toRealPath();
            if (!realDirectory.startsWith(snapshotRoot)) {
                throw new SnapshotException("Snapshot directory resolves outside snapshot root");
            }
        }

        Path snapshotRoot() {
            return snapshotRoot;
        }

        private static void requireLexicallyInside(Path candidate, Path root, String label)
                throws SnapshotException {
            if (!candidate.startsWith(root)) {
                throw new SnapshotException(label + " is outside the allowed root");
            }
        }

        private static void verifyExistingChain(Path candidate, Path root) throws IOException {
            Path relative;
            try {
                relative = root.relativize(candidate);
            } catch (IllegalArgumentException e) {
                throw new SnapshotException("Path is on a different filesystem root");
            }
            Path current = root;
            for (Path part : relative) {
                current = current.resolve(part);
                if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                    break;
                }
                if (Files.isSymbolicLink(current)) {
                    throw new SnapshotException("Symbolic links are not allowed in snapshot paths");
                }
                Path realCurrent = current.toRealPath();
                if (!realCurrent.startsWith(root)) {
                    throw new SnapshotException("Path component resolves outside the allowed root");
                }
            }
        }
    }
}
