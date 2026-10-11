package com.paicli.codeindex;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class JavaSourceDiscovery {

    private static final long PROGRESS_INTERVAL_NANOS = 2_000_000_000L;

    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            ".git", ".paicli", "target", "build", "out", ".idea",
            "node_modules"
    );

    public DiscoveryResult discover(Path workspaceRoot) {
        return discover(workspaceRoot, ProgressListener.NOOP);
    }

    public DiscoveryResult discover(Path workspaceRoot, ProgressListener progressListener) {
        return discover(workspaceRoot, progressListener, CancellationProbe.NOOP);
    }

    public DiscoveryResult discover(Path workspaceRoot, ProgressListener progressListener,
            CancellationProbe cancellationProbe) {
        Path absoluteRoot = workspaceRoot.toAbsolutePath().normalize();
        List<JavaSourceFile> files = new ArrayList<>();
        List<DiscoveryIssue> issues = new ArrayList<>();
        ProgressCounter progress = new ProgressCounter(
                progressListener == null ? ProgressListener.NOOP : progressListener,
                absoluteRoot);
        CancellationProbe cancellation = cancellationProbe == null
                ? CancellationProbe.NOOP : cancellationProbe;
        cancellation.check();

        Path realRoot;
        try {
            realRoot = absoluteRoot.toRealPath();
        } catch (IOException e) {
            progress.finish();
            return new DiscoveryResult(List.of(), List.of(new DiscoveryIssue(
                    "", "workspace root is not readable: " + e.getMessage())));
        }
        progress.realRoot(realRoot);

        try {
            Files.walkFileTree(realRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(
                        Path dir, BasicFileAttributes attrs) {
                    cancellation.check();
                    progress.directory(dir, attrs);
                    if (!dir.equals(realRoot) && shouldSkipDirectory(
                            realRoot, dir, attrs, issues)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file,
                                                 BasicFileAttributes attrs) {
                    cancellation.check();
                    progress.file(file, attrs);
                    if (!shouldSkipFile(realRoot, file, attrs, issues)
                            && file.getFileName().toString().endsWith(".java")) {
                        files.add(new JavaSourceFile(toRelativePath(realRoot, file)));
                        progress.javaFile();
                    }
                    progress.checkpoint();
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file,
                                                       IOException exc) {
                    cancellation.check();
                    issues.add(new DiscoveryIssue(
                            toRelativePath(realRoot, file),
                            "cannot read path: " + exc.getMessage()));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            issues.add(new DiscoveryIssue("", "discovery failed: " + e.getMessage()));
        } finally {
            progress.finish();
        }

        files.sort(Comparator.comparing(JavaSourceFile::relativePath));
        issues.sort(Comparator.comparing(DiscoveryIssue::relativePath)
                .thenComparing(DiscoveryIssue::reason));
        return new DiscoveryResult(List.copyOf(files), List.copyOf(issues));
    }

    private boolean shouldSkipDirectory(Path realRoot, Path dir,
                                        BasicFileAttributes attrs,
                                        List<DiscoveryIssue> issues) {
        String name = dir.getFileName().toString().toLowerCase(Locale.ROOT);
        if (EXCLUDED_DIRECTORIES.contains(name)) {
            return true;
        }
        if (Files.isSymbolicLink(dir)) {
            issues.add(new DiscoveryIssue(
                    toRelativePath(realRoot, dir), "skipped symlink directory"));
            return true;
        }
        if (attrs != null && attrs.isOther()) {
            issues.add(new DiscoveryIssue(
                    toRelativePath(realRoot, dir), "skipped reparse or special directory"));
            return true;
        }
        if (!Files.isReadable(dir)) {
            issues.add(new DiscoveryIssue(
                    toRelativePath(realRoot, dir), "skipped unreadable directory"));
            return true;
        }
        try {
            if (!dir.toRealPath().startsWith(realRoot)) {
                issues.add(new DiscoveryIssue(
                        toRelativePath(realRoot, dir),
                        "skipped directory outside workspace"));
                return true;
            }
        } catch (IOException e) {
            issues.add(new DiscoveryIssue(
                    toRelativePath(realRoot, dir),
                    "skipped unreadable directory: " + e.getMessage()));
            return true;
        }
        return false;
    }

    private boolean shouldSkipFile(Path realRoot, Path file,
                                   BasicFileAttributes attrs,
                                   List<DiscoveryIssue> issues) {
        if (!attrs.isRegularFile() || Files.isSymbolicLink(file)) {
            if (Files.isSymbolicLink(file)) {
                issues.add(new DiscoveryIssue(
                        toRelativePath(realRoot, file), "skipped symlink file"));
            } else if (attrs.isOther()) {
                issues.add(new DiscoveryIssue(
                        toRelativePath(realRoot, file), "skipped reparse or special file"));
            }
            return true;
        }
        if (!Files.isReadable(file)) {
            issues.add(new DiscoveryIssue(
                    toRelativePath(realRoot, file), "skipped unreadable file"));
            return true;
        }
        try {
            if (!file.toRealPath().startsWith(realRoot)) {
                issues.add(new DiscoveryIssue(
                        toRelativePath(realRoot, file),
                        "skipped file outside workspace"));
                return true;
            }
        } catch (IOException e) {
            issues.add(new DiscoveryIssue(
                    toRelativePath(realRoot, file),
                    "skipped unreadable file: " + e.getMessage()));
            return true;
        }
        return false;
    }

    private String toRelativePath(Path realRoot, Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        if (absolute.startsWith(realRoot)) {
            return realRoot.relativize(absolute).toString().replace('\\', '/');
        }
        return absolute.toString().replace('\\', '/');
    }

    public record DiscoveryResult(
            List<JavaSourceFile> files,
            List<DiscoveryIssue> issues
    ) {
    }

    public record JavaSourceFile(String relativePath) {
    }

    public record DiscoveryIssue(String relativePath, String reason) {
    }

    @FunctionalInterface
    public interface ProgressListener {
        ProgressListener NOOP = snapshot -> { };

        void onProgress(ProgressSnapshot snapshot);
    }

    @FunctionalInterface
    public interface CancellationProbe {
        CancellationProbe NOOP = () -> { };

        void check();
    }

    public record ProgressSnapshot(
            long visitedDirectories,
            long visitedFiles,
            long matchedJavaFiles,
            long approximateBytes,
            long elapsedMillis,
            int currentDepth,
            int maxDepth,
            boolean crossedInitialRoot,
            long reparseOrLinkCount,
            boolean finalSnapshot) {
    }

    private static final class ProgressCounter {
        private final ProgressListener listener;
        private final Path initialRoot;
        private final long startedNanos = System.nanoTime();
        private Path realRoot;
        private long visitedDirectories;
        private long visitedFiles;
        private long matchedJavaFiles;
        private long approximateBytes;
        private long reparseOrLinkCount;
        private long lastReportedNanos = startedNanos;
        private int currentDepth;
        private int maxDepth;
        private boolean crossedInitialRoot;
        private boolean finished;

        private ProgressCounter(ProgressListener listener, Path initialRoot) {
            this.listener = listener;
            this.initialRoot = initialRoot;
            this.realRoot = initialRoot;
        }

        void realRoot(Path value) {
            realRoot = value;
        }

        void directory(Path path, BasicFileAttributes attributes) {
            visitedDirectories++;
            observe(path, attributes);
            report(false);
        }

        void file(Path path, BasicFileAttributes attributes) {
            visitedFiles++;
            if (attributes != null && attributes.size() > 0) {
                approximateBytes += attributes.size();
            }
            observe(path, attributes);
        }

        void javaFile() {
            matchedJavaFiles++;
        }

        void checkpoint() {
            report(false);
        }

        void finish() {
            if (finished) return;
            finished = true;
            report(true);
        }

        private void observe(Path path, BasicFileAttributes attributes) {
            Path normalized = path.toAbsolutePath().normalize();
            currentDepth = depth(normalized);
            maxDepth = Math.max(maxDepth, currentDepth);
            if (attributes != null && (attributes.isSymbolicLink() || attributes.isOther())
                    || Files.isSymbolicLink(path)) {
                reparseOrLinkCount++;
            }
            try {
                if (!path.toRealPath().startsWith(realRoot)) crossedInitialRoot = true;
            } catch (IOException | RuntimeException ignored) {
                if (!normalized.startsWith(initialRoot)) crossedInitialRoot = true;
            }
        }

        private int depth(Path normalized) {
            try {
                if (normalized.equals(realRoot)) return 0;
                return normalized.startsWith(realRoot)
                        ? realRoot.relativize(normalized).getNameCount() : -1;
            } catch (RuntimeException ignored) {
                return -1;
            }
        }

        private void report(boolean finalSnapshot) {
            long now = System.nanoTime();
            if (!finalSnapshot && now - lastReportedNanos < PROGRESS_INTERVAL_NANOS) return;
            lastReportedNanos = now;
            ProgressSnapshot snapshot = new ProgressSnapshot(
                    visitedDirectories, visitedFiles, matchedJavaFiles,
                    approximateBytes, Math.max(0L, (now - startedNanos) / 1_000_000L),
                    currentDepth, maxDepth, crossedInitialRoot, reparseOrLinkCount,
                    finalSnapshot);
            try {
                listener.onProgress(snapshot);
            } catch (RuntimeException ignored) {
                // Diagnostics must never change discovery behavior.
            }
        }
    }
}
