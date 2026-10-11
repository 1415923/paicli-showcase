package com.paicli.worktree;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Resolves Worker file paths while excluding Git and runtime control planes. */
public final class WorkerWorkspacePathGuard {
    private final Path workspace;
    private final List<Path> deniedRoots;

    public WorkerWorkspacePathGuard(Path assignedWorkspace, Path gitCommonDirectory,
                                    List<Path> runtimeControlRoots) throws IOException {
        this.workspace = realDirectory(assignedWorkspace, "assigned workspace");
        List<Path> denied = new ArrayList<>();
        denied.add(canonicalExistingOrNormalized(gitCommonDirectory));
        for (Path path : Objects.requireNonNull(runtimeControlRoots, "runtimeControlRoots")) {
            denied.add(canonicalExistingOrNormalized(path));
        }
        this.deniedRoots = List.copyOf(denied);
    }

    public Path resolve(String relativePath) throws IOException {
        if (relativePath == null || relativePath.isBlank() || relativePath.indexOf('\0') >= 0) {
            throw new IOException("Worker path must be a non-blank relative path");
        }
        Path relative;
        try {
            relative = Path.of(relativePath);
        } catch (RuntimeException e) {
            throw new IOException("Worker path is invalid", e);
        }
        if (relative.isAbsolute()) throw new IOException("Absolute Worker path is forbidden");
        for (Path part : relative) {
            String segment = part.toString();
            if (segment.equals("..") || segment.equals(".")) {
                throw new IOException("Worker path traversal is forbidden");
            }
            if (segment.equalsIgnoreCase(".git") || segment.equalsIgnoreCase(".paicli")) {
                throw new IOException("Worker control-plane path is forbidden");
            }
        }
        Path candidate = workspace.resolve(relative).normalize();
        if (!inside(candidate, workspace)) throw new IOException("Worker path escapes assigned workspace");
        verifyExistingAncestors(candidate);
        Path canonical = canonicalExistingOrNormalized(candidate);
        if (!inside(canonical, workspace)) throw new IOException("Worker path escapes assigned workspace");
        for (Path denied : deniedRoots) {
            if (overlaps(canonical, denied)) throw new IOException("Worker runtime path is forbidden");
        }
        return candidate;
    }

    /** Returns the already-canonical assigned workspace for root-only operations. */
    public Path workspaceRoot() {
        return workspace;
    }

    private void verifyExistingAncestors(Path candidate) throws IOException {
        Path current = workspace;
        Path relative = workspace.relativize(candidate);
        for (Path part : relative) {
            current = current.resolve(part);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) break;
            if (Files.isSymbolicLink(current)) throw new IOException("Worker symlink path is forbidden");
            Path real = current.toRealPath();
            if (!inside(real, workspace)) throw new IOException("Worker junction escapes assigned workspace");
        }
    }

    private static Path realDirectory(Path path, String label) throws IOException {
        Path normalized = Objects.requireNonNull(path, label).toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " is not a normal directory");
        }
        if (Files.isSymbolicLink(normalized)) throw new IOException(label + " cannot be a symlink");
        return normalized.toRealPath();
    }

    private static Path canonicalExistingOrNormalized(Path path) throws IOException {
        Path normalized = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) return normalized.toRealPath();
        Path ancestor = normalized;
        while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            ancestor = ancestor.getParent();
        }
        if (ancestor == null) throw new IOException("Path has no existing ancestor");
        Path realAncestor = ancestor.toRealPath();
        return realAncestor.resolve(ancestor.relativize(normalized)).normalize();
    }

    static boolean overlaps(Path left, Path right) {
        return inside(left, right) || inside(right, left);
    }

    static boolean inside(Path child, Path parent) {
        if (isWindows()) {
            String childText = child.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
            String parentText = parent.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
            return childText.equals(parentText)
                    || childText.startsWith(parentText + java.io.File.separator.toLowerCase(Locale.ROOT));
        }
        return child.toAbsolutePath().normalize().startsWith(parent.toAbsolutePath().normalize());
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }
}
