package com.paicli.worker;

import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.safety.SafetyPolicy;
import com.paicli.skill.ControlPlanePaths;
import com.paicli.tool.GrepTool;
import com.paicli.tool.ListFilesTool;
import com.paicli.tool.ReadFileTool;
import com.paicli.tool.Tool;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolResult;
import com.paicli.tool.WriteFileTool;
import com.paicli.worktree.WorkerWorkspacePathGuard;
import com.paicli.worktree.WorktreeGitDiffTool;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Fixed Worker tools, reconstructed for one assigned workspace. */
public final class WorktreeWorkerToolRegistry {
    private final ToolRegistry registry;
    private final List<String> toolNames;
    private final AtomicReference<String> toolFailure;

    private WorktreeWorkerToolRegistry(ToolRegistry registry,
                                       AtomicReference<String> toolFailure) {
        this.registry = registry;
        this.toolNames = registry.allDefinitions().stream()
                .map(ToolDefinition::name).sorted().toList();
        this.toolFailure = toolFailure;
    }

    public static WorktreeWorkerToolRegistry create(
            WorkerWorkspacePathGuard guard,
            Path traceDirectory,
            int timeoutSeconds,
            int maxOutputChars,
            AtomicBoolean cancelled) throws IOException {
        return create(guard, traceDirectory, timeoutSeconds, maxOutputChars, cancelled, null);
    }

    static WorktreeWorkerToolRegistry create(
            WorkerWorkspacePathGuard guard,
            Path traceDirectory,
            int timeoutSeconds,
            int maxOutputChars,
            AtomicBoolean cancelled,
            Tool mailboxTool) throws IOException {
        Objects.requireNonNull(guard, "guard");
        Objects.requireNonNull(cancelled, "cancelled");
        Path workspace = guard.workspaceRoot();
        SafetyPolicy safety = new SafetyPolicy(workspace, timeoutSeconds,
                Math.max(256, Math.min(maxOutputChars, 262_144)));
        ControlPlanePaths controlPlane = new ControlPlanePaths(
                workspace, List.of("skills", ".paicli/skills"));
        AtomicReference<String> failure = new AtomicReference<>();
        ToolRegistry registry = new ToolRegistry();
        registry.register(guarded(new ReadFileTool(safety), guard, "path", false,
                false, cancelled, failure));
        registry.register(guarded(new ListFilesTool(safety), guard, "path", true,
                false, cancelled, failure));
        registry.register(guarded(new GrepTool(safety), guard, "path", true,
                true, cancelled, failure));
        registry.register(guarded(new WriteFileTool(
                        safety, null, null, null, controlPlane),
                guard, "path", false, false, cancelled, failure));
        Path hooks = traceDirectory.resolve("empty-git-hooks");
        Files.createDirectories(hooks);
        registry.register(guarded(new WorktreeGitDiffTool(
                        workspace, hooks, Duration.ofSeconds(Math.min(timeoutSeconds, 120)),
                        Math.max(256, Math.min(maxOutputChars, 262_144))),
                guard, null, false, false, cancelled, failure));
        if (mailboxTool != null) {
            registry.register(guarded(mailboxTool, guard, null, false,
                    false, cancelled, failure));
        }
        return new WorktreeWorkerToolRegistry(registry, failure);
    }

    public List<String> toolNames() {
        return toolNames;
    }

    public boolean contains(String toolName) {
        return toolNames.contains(toolName);
    }

    public String toolFailure() {
        return toolFailure.get();
    }

    public List<ToolDefinition> allDefinitions() {
        return registry.allDefinitions();
    }

    /** Executes only the already-guarded runtime tool view. */
    public ToolResult invoke(ToolCall call) {
        return registry.invoke(call);
    }

    ToolRegistry runtimeRegistry() {
        return registry;
    }

    private static Tool guarded(Tool delegate, WorkerWorkspacePathGuard guard,
                                String pathArgument, boolean allowRoot,
                                boolean preflightTree, AtomicBoolean cancelled,
                                AtomicReference<String> failure) {
        return new Tool() {
            @Override public String name() { return delegate.name(); }
            @Override public String description() { return delegate.description(); }
            @Override public ToolDefinition toDefinition() { return delegate.toDefinition(); }

            @Override
            public ToolResult execute(ToolCall call) {
                if (cancelled.get()) return failed("Worker execution is cancelled");
                try {
                    if (pathArgument != null) {
                        Object raw = call.arguments().get(pathArgument);
                        String path = raw instanceof String value ? value : null;
                        if (allowRoot && (path == null || ".".equals(path))) {
                            if (preflightTree) preflight(guard, guard.workspaceRoot());
                        } else {
                            Path resolved = guard.resolve(path);
                            if (preflightTree && Files.isDirectory(
                                    resolved, LinkOption.NOFOLLOW_LINKS)) {
                                preflight(guard, resolved);
                            }
                        }
                    }
                    if (cancelled.get()) return failed("Worker execution is cancelled");
                    ToolResult result = delegate.execute(call);
                    if (!result.success()) failure.compareAndSet(null, result.error());
                    return result;
                } catch (IOException | RuntimeException e) {
                    return failed("Worker tool path validation failed: " + safeMessage(e));
                }
            }

            private ToolResult failed(String message) {
                failure.compareAndSet(null, message);
                return ToolResult.error(message);
            }
        };
    }

    private static void preflight(WorkerWorkspacePathGuard guard, Path directory)
            throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
            for (Path entry : entries) {
                String relative = guard.workspaceRoot().relativize(entry).toString();
                Path safe = guard.resolve(relative);
                if (Files.isDirectory(safe, LinkOption.NOFOLLOW_LINKS)) {
                    preflight(guard, safe);
                }
            }
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName() : message;
    }
}


