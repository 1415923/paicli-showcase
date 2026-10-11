package com.paicli.interactive.completion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Set;

public final class WorkspacePathGuard {
    private static final Set<String> EXCLUDED_SEGMENTS =
            Set.of(".paicli", ".git", "target", "build", ".gradle");
    private static final Set<String> SECRET_NAMES = Set.of(
            ".env", ".npmrc", ".pypirc", "credentials", "credentials.json",
            "id_rsa", "id_ed25519", "known_hosts");

    private final Path workspace;
    private final Path realWorkspace;

    public WorkspacePathGuard(Path workspace) {
        try {
            this.workspace = workspace.toAbsolutePath().normalize();
            this.realWorkspace = this.workspace.toRealPath(LinkOption.NOFOLLOW_LINKS);
            rejectLink(this.realWorkspace);
        } catch (IOException error) {
            throw new IllegalArgumentException("workspace is not accessible", error);
        }
    }

    public String validateReference(String raw) {
        Path target = resolveSafe(raw, true);
        return normalizeRelative(workspace.relativize(target));
    }

    Path resolveCompletionDirectory(String rawDirectory) {
        return resolveSafe(rawDirectory == null || rawDirectory.isBlank() ? "." : rawDirectory, true);
    }

    boolean isCandidateAllowed(Path target) {
        try {
            Path normalized = target.toAbsolutePath().normalize();
            if (!normalized.startsWith(workspace) || isExcluded(workspace.relativize(normalized))) return false;
            rejectPathLinks(normalized);
            return Files.exists(normalized, LinkOption.NOFOLLOW_LINKS);
        } catch (RuntimeException | IOException error) {
            return false;
        }
    }

    String relative(Path target) {
        return normalizeRelative(workspace.relativize(target.toAbsolutePath().normalize()));
    }

    private Path resolveSafe(String raw, boolean mustExist) {
        if (raw == null || raw.isBlank() || raw.length() > 1024 || hasControl(raw)) {
            throw new IllegalArgumentException("invalid workspace path");
        }
        String portable = raw.replace('\\', '/');
        if (portable.startsWith("/") || portable.startsWith("//")
                || portable.matches("^[A-Za-z]:.*") || portable.contains(":")) {
            throw new IllegalArgumentException("absolute or special paths are not allowed");
        }
        Path relative = Path.of(portable).normalize();
        if (relative.isAbsolute() || relative.startsWith("..") || isExcluded(relative)) {
            throw new IllegalArgumentException("path is outside the interactive workspace boundary");
        }
        Path target = workspace.resolve(relative).normalize();
        if (!target.startsWith(workspace)) throw new IllegalArgumentException("path escapes workspace");
        try {
            rejectPathLinks(target);
            if (mustExist && !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("referenced path does not exist");
            }
            if (mustExist) {
                Path real = target.toRealPath(LinkOption.NOFOLLOW_LINKS);
                if (!real.startsWith(realWorkspace)) throw new IllegalArgumentException("path escapes workspace");
            }
            return target;
        } catch (IOException error) {
            throw new IllegalArgumentException("workspace path cannot be validated", error);
        }
    }

    private void rejectPathLinks(Path target) throws IOException {
        Path current = workspace;
        Path relative = workspace.relativize(target);
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) rejectLink(current);
        }
    }

    private static void rejectLink(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isSymbolicLink() || attributes.isOther()) {
            throw new IllegalArgumentException("symbolic links and reparse points are not allowed");
        }
    }

    private static boolean isExcluded(Path relative) {
        for (Path segment : relative) {
            String value = segment.toString().toLowerCase(Locale.ROOT);
            if (EXCLUDED_SEGMENTS.contains(value) || SECRET_NAMES.contains(value) || value.startsWith(".env")
                    || value.endsWith(".pem") || value.endsWith(".key")
                    || value.endsWith(".p12") || value.endsWith(".pfx")) return true;
        }
        return false;
    }

    private static String normalizeRelative(Path path) {
        String value = path.toString().replace('\\', '/');
        return value.isBlank() ? "." : value;
    }

    private static boolean hasControl(String value) {
        return value.chars().anyMatch(character -> character < 0x20 || character == 0x7f);
    }
}
