package com.paicli.safety;

import com.paicli.model.ToolCall;

import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates paths, commands, and tool output against workspace safety rules. */
public class SafetyPolicy {
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_MAX_OUTPUT_BYTES = 50 * 1024;
    private static final String TRUNCATION_MARKER = "\n[truncated at %dKB]";
    private static final LinkOption[] NO_FOLLOW = {LinkOption.NOFOLLOW_LINKS};
    private static final Set<OpenOption> READ_OPTIONS = Set.of(
            StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);

    private static final Map<String, List<String>> PATH_TOOLS = Map.of(
            "list_files", List.of("path"),
            "read_file", List.of("path"),
            "write_file", List.of("path")
    );

    private final Path workspaceRoot;
    private final int commandTimeoutSeconds;
    private final int maxOutputBytes;
    private final List<Pattern> dangerousPatterns;

    public SafetyPolicy(Path workspaceRoot) {
        this(workspaceRoot, DEFAULT_TIMEOUT_SECONDS, DEFAULT_MAX_OUTPUT_BYTES);
    }

    public SafetyPolicy(Path workspaceRoot, int commandTimeoutSeconds, int maxOutputBytes) {
        Path candidate = Objects.requireNonNull(workspaceRoot, "workspaceRoot")
                .toAbsolutePath().normalize();
        try {
            if (!Files.isDirectory(candidate, NO_FOLLOW) || isLinkLike(candidate)) {
                throw new IllegalArgumentException("Workspace root must be a real directory, not a link");
            }
            this.workspaceRoot = candidate.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("Workspace root could not be resolved", e);
        }
        if (commandTimeoutSeconds < 1 || commandTimeoutSeconds > 300) {
            throw new IllegalArgumentException("command timeout must be between 1 and 300 seconds");
        }
        if (maxOutputBytes < 1) {
            throw new IllegalArgumentException("max output bytes must be positive");
        }
        this.commandTimeoutSeconds = commandTimeoutSeconds;
        this.maxOutputBytes = maxOutputBytes;
        this.dangerousPatterns = defaultDangerousPatterns();
    }

    /** Validates the path without following any workspace link or reparse-like component. */
    public void validatePath(Path targetPath) throws SafetyViolationException {
        resolveWorkspacePath(targetPath);
    }

    /** Resolves a path under the real workspace root and rejects links component by component. */
    public Path resolveWorkspacePath(Path targetPath) throws SafetyViolationException {
        if (targetPath == null) {
            throw violation(SafetyViolationException.ViolationType.PATH_OUTSIDE_WORKSPACE,
                    "Path must not be null");
        }
        for (Path part : targetPath) {
            if ("..".equals(part.toString())) {
                throw violation(SafetyViolationException.ViolationType.PATH_TRAVERSAL,
                        "Path contains parent traversal");
            }
        }

        Path resolved = workspaceRoot.resolve(targetPath).normalize();
        if (!resolved.startsWith(workspaceRoot)) {
            throw violation(SafetyViolationException.ViolationType.PATH_OUTSIDE_WORKSPACE,
                    "Path resolves outside workspace");
        }

        Path current = workspaceRoot;
        Path relative = workspaceRoot.relativize(resolved);
        for (Path part : relative) {
            current = current.resolve(part);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                break;
            }
            rejectLinkLike(current);
        }

        if (Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
            rejectLinkLike(resolved);
            try {
                Path real = resolved.toRealPath();
                if (!real.startsWith(workspaceRoot)) {
                    throw violation(SafetyViolationException.ViolationType.PATH_OUTSIDE_WORKSPACE,
                            "Existing path resolves outside workspace");
                }
            } catch (IOException e) {
                throw violation(SafetyViolationException.ViolationType.PATH_OUTSIDE_WORKSPACE,
                        "Existing path could not be resolved safely");
            }
        }
        return resolved;
    }

    /** Opens an existing regular file without following a final symbolic link. */
    public SeekableByteChannel openFileForRead(Path targetPath)
            throws SafetyViolationException, IOException {
        Path resolved = resolveWorkspacePath(targetPath);
        BasicFileAttributes attributes = Files.readAttributes(
                resolved, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new IOException("Target is not a regular file");
        }
        return Files.newByteChannel(resolved, READ_OPTIONS);
    }

    /** Returns true only for an existing, safe, no-follow regular file. */
    public boolean isSafeRegularFile(Path targetPath) {
        try {
            Path resolved = resolveWorkspacePath(targetPath);
            return Files.readAttributes(resolved, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).isRegularFile();
        } catch (IOException | SafetyViolationException e) {
            return false;
        }
    }

    private void rejectLinkLike(Path path) throws SafetyViolationException {
        try {
            if (isLinkLike(path)) {
                throw violation(SafetyViolationException.ViolationType.PATH_OUTSIDE_WORKSPACE,
                        "Symbolic links and reparse-like paths are not allowed");
            }
        } catch (IOException e) {
            throw violation(SafetyViolationException.ViolationType.PATH_OUTSIDE_WORKSPACE,
                    "Path attributes could not be validated");
        }
    }

    private static boolean isLinkLike(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) return true;
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return attributes.isOther();
    }

    public void validateCommand(String command) throws SafetyViolationException {
        if (command == null) {
            throw violation(SafetyViolationException.ViolationType.DANGEROUS_COMMAND,
                    "Command must not be null");
        }
        String normalized = command.trim().replaceAll("\\s+", " ");
        for (Pattern pattern : dangerousPatterns) {
            if (pattern.matcher(normalized).find()) {
                throw violation(SafetyViolationException.ViolationType.DANGEROUS_COMMAND,
                        "Blocked dangerous command");
            }
        }
    }

    public void validateToolCall(ToolCall call) throws SafetyViolationException {
        if (call == null) return;
        String toolName = call.name();
        Map<String, Object> args = call.arguments();

        if (PATH_TOOLS.containsKey(toolName)) {
            for (String pathParam : PATH_TOOLS.get(toolName)) {
                Object value = args.get(pathParam);
                if (value instanceof String pathStr) {
                    validatePath(Path.of(pathStr));
                } else {
                    throw violation(SafetyViolationException.ViolationType.PATH_OUTSIDE_WORKSPACE,
                            "Tool is missing a required path argument");
                }
            }
        }
        if ("grep".equals(toolName)) {
            Object value = args.getOrDefault("path", ".");
            if (value instanceof String pathStr) validatePath(Path.of(pathStr));
            else throw violation(SafetyViolationException.ViolationType.PATH_OUTSIDE_WORKSPACE,
                    "grep path must be a string");
        }
        if ("run_command".equals(toolName)) {
            Object cmd = args.get("command");
            if (cmd instanceof String cmdStr) validateCommand(cmdStr);
            else throw violation(SafetyViolationException.ViolationType.DANGEROUS_COMMAND,
                    "run_command is missing its command argument");
        }
    }

    public String truncateOutput(String output) {
        if (output == null) return "";
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxOutputBytes) return output;
        String truncated = new String(bytes, 0, maxOutputBytes, StandardCharsets.UTF_8);
        return truncated + String.format(TRUNCATION_MARKER, maxOutputBytes / 1024);
    }

    public int getTimeoutSeconds() { return commandTimeoutSeconds; }
    public int getMaxOutputBytes() { return maxOutputBytes; }
    public Path getWorkspaceRoot() { return workspaceRoot; }

    private SafetyViolationException violation(
            SafetyViolationException.ViolationType type, String message) {
        return new SafetyViolationException(type, message);
    }

    private static List<Pattern> defaultDangerousPatterns() {
        return List.of(
                Pattern.compile("\\brm\\s+(-[a-zA-Z]*r[a-zA-Z]*f|--recursive\\s+--force)\\b", Pattern.CASE_INSENSITIVE),
                Pattern.compile("\\brm\\s+(-[a-zA-Z]*f[a-zA-Z]*r|--force\\s+--recursive)\\b", Pattern.CASE_INSENSITIVE),
                Pattern.compile("\\bdel\\s+/[sS]", Pattern.CASE_INSENSITIVE),
                Pattern.compile("\\bformat\\s+[A-Za-z]:", Pattern.CASE_INSENSITIVE),
                Pattern.compile("\\bshutdown\\s+[-/](?![?i])", Pattern.CASE_INSENSITIVE),
                Pattern.compile("\\bmkfs\\b", Pattern.CASE_INSENSITIVE),
                Pattern.compile("\\bdd\\s+if=", Pattern.CASE_INSENSITIVE)
        );
    }
}
