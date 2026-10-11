package com.paicli.worktree;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.tool.Tool;
import com.paicli.tool.ToolResult;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** Fixed, read-only Git status/diff view for an assigned linked worktree. */
public final class WorktreeGitDiffTool implements Tool {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Path workspace;
    private final HardenedGitProcess git;

    public WorktreeGitDiffTool(Path workspace, Path hooksDirectory,
                               Duration timeout, int maxOutputBytes) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.git = new HardenedGitProcess(hooksDirectory, timeout, maxOutputBytes);
    }

    @Override
    public String name() {
        return "git_diff";
    }

    @Override
    public String description() {
        return "Show the assigned worktree diff or status using fixed read-only Git operations.";
    }

    @Override
    public ToolDefinition toDefinition() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        ObjectNode mode = schema.putObject("properties").putObject("mode");
        mode.put("type", "string");
        mode.putArray("enum").add("diff").add("status");
        mode.put("description", "Fixed read-only view; default is diff");
        return new ToolDefinition(name(), description(), schema);
    }

    @Override
    public ToolResult execute(ToolCall call) {
        Object supplied = call.arguments().get("mode");
        String mode = supplied == null ? "diff" : supplied.toString();
        HardenedGitProcess.Operation operation;
        if ("diff".equals(mode)) {
            operation = HardenedGitProcess.Operation.WORKTREE_DIFF;
        } else if ("status".equals(mode)) {
            operation = HardenedGitProcess.Operation.STATUS;
        } else {
            return ToolResult.error("git_diff: mode must be 'diff' or 'status'");
        }
        if (call.arguments().keySet().stream().anyMatch(key -> !"mode".equals(key))) {
            return ToolResult.error("git_diff: arbitrary Git arguments are forbidden");
        }
        HardenedGitProcess.Result result = git.execute(workspace, operation);
        if (!result.succeeded()) {
            return ToolResult.error("git_diff: read-only Git query failed: " + result.status());
        }
        String output = result.stdoutExcerpt() == null ? "" : result.stdoutExcerpt();
        return ToolResult.success(output, Map.of("mode", mode));
    }
}
