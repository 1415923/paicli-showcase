package com.paicli.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.safety.SafetyPolicy;
import com.paicli.skill.ControlPlanePaths;
import com.paicli.state.FileSnapshotService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Writes content to a file within the workspace.
 * Safety rules: path must be inside workspace, no overwrite by default, max 200KB content.
 */
public class WriteFileTool implements Tool {

    private static final String NAME = "write_file";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_WRITE_BYTES = 200 * 1024; // 200KB

    private final SafetyPolicy policy;
    private final FileSnapshotService snapshotService;
    private final String taskId;
    private final String toolCallId;
    private final ControlPlanePaths controlPlanePaths;

    public WriteFileTool(SafetyPolicy policy) {
        this(policy, null, null, null, null);
    }

    public WriteFileTool(SafetyPolicy policy, FileSnapshotService snapshotService, String taskId) {
        this(policy, snapshotService, taskId, null, null);
    }

    public WriteFileTool(SafetyPolicy policy, FileSnapshotService snapshotService,
                         String taskId, String toolCallId) {
        this(policy, snapshotService, taskId, toolCallId, null);
    }

    public WriteFileTool(SafetyPolicy policy, FileSnapshotService snapshotService,
                         String taskId, String toolCallId,
                         ControlPlanePaths controlPlanePaths) {
        this.policy = policy;
        this.snapshotService = snapshotService;
        this.taskId = taskId;
        this.toolCallId = toolCallId;
        this.controlPlanePaths = controlPlanePaths;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Write content to a file within the workspace. Does not overwrite by default.";
    }

    @Override
    public ToolDefinition toDefinition() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        ObjectNode pathProp = props.putObject("path");
        pathProp.put("type", "string");
        pathProp.put("description", "Relative path to write to");
        ObjectNode contentProp = props.putObject("content");
        contentProp.put("type", "string");
        contentProp.put("description", "Content to write");
        ObjectNode overwriteProp = props.putObject("overwrite");
        overwriteProp.put("type", "boolean");
        overwriteProp.put("description", "If true, overwrite existing file (default: false)");
        schema.putArray("required").add("path").add("content");
        return new ToolDefinition(NAME, description(), schema);
    }

    @Override
    public ToolResult preflightCall(ToolCall call) {
        Object pathValue=call.arguments().get("path");
        if(!(pathValue instanceof String path)||path.isBlank())
            return ToolResult.error("write_file: missing or invalid 'path' parameter");
        if(!(call.arguments().get("content") instanceof String content))
            return ToolResult.error("write_file: missing or invalid 'content' parameter");
        if(content.getBytes(StandardCharsets.UTF_8).length>MAX_WRITE_BYTES)
            return ToolResult.error("write_file: content too large");
        try{
            Path resolved=policy.resolveWorkspacePath(Path.of(path));
            if(resolved.equals(policy.getWorkspaceRoot()))
                return ToolResult.error("write_file: cannot write to workspace root directory");
            ToolResult protectedPath=WorkspaceControlDirectoryPolicy.authorize(
                    call,policy,resolved,NAME,WorkspaceControlDirectoryPolicy.Access.WRITE);
            if(!protectedPath.success())return protectedPath;
            if(controlPlanePaths!=null&&controlPlanePaths.isControlPlane(resolved))
                return ToolResult.error("write_file: protected Skills/Hooks control-plane path");
            return ToolResult.success("");
        }catch(com.paicli.safety.SafetyViolationException|IOException|RuntimeException error){
            return ToolResult.error("write_file: unsafe path");
        }
    }

    @Override
    public Optional<ToolAuthorityProjection> authorityProjection(ToolCall call) {
        if(!preflightCall(call).success())return Optional.empty();
        try{
            Path resolved=policy.resolveWorkspacePath(Path.of((String)call.arguments().get("path")));
            boolean overwrite=call.arguments().get("overwrite") instanceof Boolean value&&value;
            return Optional.of(ToolAuthorityProjection.exactPath(NAME,
                    com.paicli.state.PlanCompletionModels.EffectClass.WRITE,
                    ToolAuthorityProjection.normalizedWorkspacePath(policy,resolved),null,false,
                    overwrite));
        }catch(com.paicli.safety.SafetyViolationException|RuntimeException error){
            return Optional.empty();
        }
    }

    @Override
    public ToolResult execute(ToolCall call) {
        Map<String, Object> args = call.arguments();

        // Fail-closed: extract required "path" parameter
        Object pathValue = args.get("path");
        if (!(pathValue instanceof String pathStr) || pathStr.isBlank()) {
            return ToolResult.error("write_file: missing or invalid 'path' parameter");
        }

        // Fail-closed: extract required "content" parameter
        Object contentValue = args.get("content");
        if (!(contentValue instanceof String content)) {
            return ToolResult.error("write_file: missing or invalid 'content' parameter");
        }

        // Check content size limit
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        if (contentBytes.length > MAX_WRITE_BYTES) {
            return ToolResult.error("write_file: content too large (" + contentBytes.length
                    + " bytes, max " + MAX_WRITE_BYTES + ")");
        }

        // Extract optional "overwrite" parameter (default: false)
        boolean overwrite = false;
        Object overwriteValue = args.get("overwrite");
        if (overwriteValue instanceof Boolean b) {
            overwrite = b;
        }

        String snapshotId = null;
        try {
            Path resolved = policy.resolveWorkspacePath(Path.of(pathStr));

            // Safety: refuse to delete (path must not resolve to workspace root itself)
            if (resolved.equals(policy.getWorkspaceRoot())) {
                return ToolResult.error("write_file: cannot write to workspace root directory");
            }

            ToolResult protectedPath=WorkspaceControlDirectoryPolicy.authorize(
                    call,policy,resolved,NAME,WorkspaceControlDirectoryPolicy.Access.WRITE);
            if(!protectedPath.success())return protectedPath;

            if (controlPlanePaths != null && controlPlanePaths.isControlPlane(resolved)) {
                return ToolResult.error(
                        "write_file: protected Skills/Hooks control-plane path");
            }

            // Check if file already exists
            if (Files.exists(resolved) && !overwrite) {
                return ToolResult.error("write_file: file already exists: " + pathStr
                        + " (set overwrite=true to replace)");
            }

            LineCount previousLines = Files.isRegularFile(
                    resolved, LinkOption.NOFOLLOW_LINKS)
                    ? countLines(resolved) : new LineCount(0, false);
            LineCount replacementLines = countLines(content);

            if (snapshotService != null && taskId != null) {
                snapshotId = snapshotService.captureBeforeWrite(taskId, toolCallId, resolved);
            }

            // Create parent directories if needed
            Path parent = resolved.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            Files.writeString(resolved, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    overwrite ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.CREATE_NEW);

            if (snapshotId != null) {
                snapshotService.markAppliedAfterWrite(snapshotId, resolved);
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("bytesWritten", contentBytes.length);
            metadata.put("relativePath", pathStr.replace('\\', '/'));
            metadata.put("additions", replacementLines.lines());
            metadata.put("deletions", previousLines.lines());
            metadata.put("diffTruncated",
                    replacementLines.truncated() || previousLines.truncated());
            if (snapshotId != null) {
                metadata.put("snapshotId", snapshotId);
            }
            return ToolResult.success(
                    "Wrote " + contentBytes.length + " bytes to " + pathStr,
                    metadata);

        } catch (com.paicli.safety.SafetyViolationException e) {
            return ToolResult.error("write_file: " + e.getMessage());
        } catch (SecurityException e) {
            return ToolResult.error("write_file: protected path validation failed");
        } catch (FileSnapshotService.SnapshotException e) {
            markSnapshotFailed(snapshotId, e.getMessage());
            return ToolResult.error("write_file: snapshot error: " + e.getMessage());
        } catch (SQLException e) {
            markSnapshotFailed(snapshotId, "snapshot persistence failed");
            return ToolResult.error("write_file: snapshot persistence failed");
        } catch (FileAlreadyExistsException e) {
            markSnapshotFailed(snapshotId, "file already exists");
            return ToolResult.error("write_file: file already exists: " + pathStr
                    + " (set overwrite=true to replace)");
        } catch (IOException e) {
            markSnapshotFailed(snapshotId, "file write failed");
            return ToolResult.error("write_file: I/O error: " + e.getMessage());
        }
    }

    private static final int MAX_DIFF_COUNT_LINES = 10_000;

    private LineCount countLines(Path path) throws IOException {
        try (var lines = Files.lines(path, StandardCharsets.UTF_8)) {
            long count = lines.limit(MAX_DIFF_COUNT_LINES + 1L).count();
            return new LineCount(
                    (int) Math.min(count, MAX_DIFF_COUNT_LINES),
                    count > MAX_DIFF_COUNT_LINES);
        }
    }

    private LineCount countLines(String content) {
        if (content.isEmpty()) return new LineCount(0, false);
        int count = 1;
        boolean truncated = false;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                if (count == MAX_DIFF_COUNT_LINES) {
                    truncated = true;
                    break;
                }
                count++;
            }
        }
        return new LineCount(count, truncated);
    }

    private record LineCount(int lines, boolean truncated) { }
    private void markSnapshotFailed(String snapshotId, String reason) {
        if (snapshotId == null || snapshotService == null) {
            return;
        }
        try {
            snapshotService.markFailed(snapshotId, reason);
        } catch (SQLException ignored) {
            // Preserve the original write/snapshot error. CAPTURED snapshots are not rewindable.
        }
    }
}
