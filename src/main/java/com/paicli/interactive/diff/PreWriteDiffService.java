package com.paicli.interactive.diff;

import com.paicli.runtime.PersistenceSanitizer;
import com.paicli.safety.SafetyPolicy;
import com.paicli.safety.SafetyViolationException;
import com.paicli.skill.ControlPlanePaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class PreWriteDiffService {
    private static final int MAX_CONTENT_BYTES = 200 * 1024;
    private static final int MAX_DISPLAY_LINES = 300;
    private static final int MAX_DISPLAY_CHARS = 64 * 1024;
    private final SafetyPolicy policy;
    private final ControlPlanePaths controlPlanePaths;
    private final PersistenceSanitizer sanitizer = new PersistenceSanitizer();

    public PreWriteDiffService(SafetyPolicy policy, ControlPlanePaths controlPlanePaths) {
        this.policy = policy;
        this.controlPlanePaths = controlPlanePaths;
    }

    public PreWriteDiff prepare(String pathText, String proposedContent, boolean overwrite) {
        if (pathText == null || pathText.isBlank() || proposedContent == null) {
            throw new IllegalArgumentException("write proposal requires path and content");
        }
        byte[] after = proposedContent.getBytes(StandardCharsets.UTF_8);
        if (after.length > MAX_CONTENT_BYTES) {
            throw new IllegalArgumentException("write proposal content exceeds 204800 bytes");
        }
        Path relative = Path.of(pathText);
        try { policy.validatePath(relative); }
        catch (SafetyViolationException error) { throw new IllegalArgumentException(error.getMessage()); }
        Path target = policy.getWorkspaceRoot().resolve(relative).normalize();
        if (target.equals(policy.getWorkspaceRoot())) {
            throw new IllegalArgumentException("workspace root is not a writable file");
        }
        try {
            if (controlPlanePaths != null && controlPlanePaths.isControlPlane(target)) {
                throw new IllegalArgumentException("protected control-plane path");
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("control-plane path validation failed");
        }
        boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (exists && !overwrite) {
            throw new IllegalArgumentException("existing file requires overwrite=true");
        }
        if (!exists && overwrite) {
            throw new IllegalArgumentException("overwrite target does not exist");
        }
        byte[] before = new byte[0];
        try {
            if (exists) {
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalArgumentException("write target is not a regular file");
                }
                before = Files.readAllBytes(target);
                if (before.length > MAX_CONTENT_BYTES) {
                    throw new IllegalArgumentException("existing file exceeds diff budget");
                }
            }
        } catch (IOException error) {
            throw new IllegalStateException("unable to read write proposal target", error);
        }
        String normalizedPath = policy.getWorkspaceRoot().relativize(target)
                .toString().replace('\\', '/');
        return render(normalizedPath, before, after, exists,
                exists ? null : findSameNameConflict(target));
    }

    private PreWriteDiff render(String path, byte[] beforeBytes, byte[] afterBytes,
            boolean exists, String conflict) {
        String before = new String(beforeBytes, StandardCharsets.UTF_8);
        String after = new String(afterBytes, StandardCharsets.UTF_8);
        List<String> oldLines = lines(before);
        List<String> newLines = lines(after);
        int prefix = commonPrefix(oldLines, newLines);
        int suffix = commonSuffix(oldLines, newLines, prefix);
        int deletions = oldLines.size() - prefix - suffix;
        int additions = newLines.size() - prefix - suffix;
        List<String> display = new ArrayList<>();
        display.add("--- a/" + path);
        display.add("+++ b/" + path);
        display.add("@@ -" + (prefix + 1) + "," + deletions
                + " +" + (prefix + 1) + "," + additions + " @@");
        boolean[] redacted = {false};
        int chars = display.stream().mapToInt(String::length).sum();
        boolean truncated = false;
        for (int i = prefix; i < oldLines.size() - suffix; i++) {
            String line = safeLine("-" + oldLines.get(i), redacted);
            if (display.size() >= MAX_DISPLAY_LINES || chars + line.length() > MAX_DISPLAY_CHARS) {
                truncated = true; break;
            }
            display.add(line); chars += line.length();
        }
        if (!truncated) {
            for (int i = prefix; i < newLines.size() - suffix; i++) {
                String line = safeLine("+" + newLines.get(i), redacted);
                if (display.size() >= MAX_DISPLAY_LINES || chars + line.length() > MAX_DISPLAY_CHARS) {
                    truncated = true; break;
                }
                display.add(line); chars += line.length();
            }
        }
        if (truncated) display.add("...[diff truncated]");
        return new PreWriteDiff(path, sha256(beforeBytes), sha256(afterBytes),
                additions, deletions, display, truncated, redacted[0], exists, conflict);
    }

    private String findSameNameConflict(Path proposed) {
        Path fileName = proposed.getFileName();
        if (fileName == null) return null;
        Path root = policy.getWorkspaceRoot();
        try (var paths = Files.walk(root, 12)) {
            return paths.limit(4096)
                    .filter(path -> !path.equals(proposed))
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> fileName.equals(path.getFileName()))
                    .limit(1)
                    .map(root::relativize)
                    .map(Path::toString)
                    .map(value -> value.replace('\\', '/'))
                    .findFirst().orElse(null);
        } catch (IOException | SecurityException error) {
            throw new IllegalStateException("unable to inspect new-file name conflicts", error);
        }
    }

    private String safeLine(String line, boolean[] redacted) {
        String sanitized = sanitizer.sanitize(line);
        if (!sanitized.equals(line)) redacted[0] = true;
        sanitized = sanitized.replaceAll("[\\p{Cntrl}&&[^\\t]]", "?");
        if (sanitized.length() > 512) {
            sanitized = sanitized.substring(0, 497) + " [truncated]";
        }
        return sanitized;
    }

    private static List<String> lines(String text) {
        if (text.isEmpty()) return List.of();
        return List.of(text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
    }
    private static int commonPrefix(List<String> a, List<String> b) {
        int i = 0; while (i < a.size() && i < b.size() && a.get(i).equals(b.get(i))) i++; return i;
    }
    private static int commonSuffix(List<String> a, List<String> b, int prefix) {
        int i = 0;
        while (i < a.size() - prefix && i < b.size() - prefix
                && a.get(a.size() - 1 - i).equals(b.get(b.size() - 1 - i))) i++;
        return i;
    }
    private static String sha256(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
