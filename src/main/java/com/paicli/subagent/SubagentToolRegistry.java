package com.paicli.subagent;

import com.paicli.config.Config;
import com.paicli.model.ToolDefinition;
import com.paicli.tool.ToolRegistry;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Immutable, narrowed view of parent tools for a validated subagent definition. */
public final class SubagentToolRegistry {

    private static final Set<String> KNOWN_TOOLS = Set.of(
            "git_diff", "grep", "list_files", "read_file", "run_command", "write_file");
    private static final Set<String> READ_ONLY_TOOLS = Set.of(
            "grep", "list_files", "read_file");

    private final ToolRegistry effectiveRegistry;
    private final List<String> toolNames;

    private SubagentToolRegistry(ToolRegistry effectiveRegistry, List<String> toolNames) {
        this.effectiveRegistry = effectiveRegistry;
        this.toolNames = List.copyOf(toolNames);
    }

    public static SubagentToolRegistry derive(
            ToolRegistry parentRegistry,
            String parentApprovalMode,
            SubagentDefinition definition) {
        Objects.requireNonNull(parentRegistry, "parentRegistry is required");
        Objects.requireNonNull(definition, "definition is required");
        if (!definition.config().enabled()) {
            throw new IllegalStateException("subagent definition is disabled: " + definition.name());
        }
        validatePermissionBoundary(parentApprovalMode, definition.config().permissionMode());

        TreeSet<String> effectiveNames = new TreeSet<>();
        for (String requested : definition.config().allowedTools()) {
            if (!KNOWN_TOOLS.contains(requested)) {
                throw new IllegalArgumentException("unknown subagent tool: " + requested);
            }
            if ("write_file".equals(requested)) {
                throw new SecurityException("write_file is forbidden for read-only subagents");
            }
            if (!READ_ONLY_TOOLS.contains(requested)) {
                throw new SecurityException(
                        requested + " exceeds the " + definition.kind() + " tool profile");
            }
            if (definition.config().permissionMode() == SubagentPermissionMode.DENY) {
                continue;
            }
            if (parentRegistry.lookup(requested).isPresent()) {
                effectiveNames.add(requested);
            }
        }

        ToolRegistry derived = new ToolRegistry();
        for (String name : effectiveNames) {
            derived.register(parentRegistry.lookup(name).orElseThrow());
        }
        return new SubagentToolRegistry(derived, List.copyOf(effectiveNames));
    }

    public List<String> toolNames() {
        return toolNames;
    }

    public List<ToolDefinition> allDefinitions() {
        return effectiveRegistry.allDefinitions();
    }

    public boolean contains(String name) {
        return toolNames.contains(name);
    }

    public int size() {
        return effectiveRegistry.size();
    }

    /** Package-private execution view; only SubagentRunner may bridge it to AgentLoop. */
    ToolRegistry runtimeRegistry() {
        return effectiveRegistry;
    }

    private static void validatePermissionBoundary(
            String parentApprovalMode,
            SubagentPermissionMode childMode) {
        boolean valid = switch (parentApprovalMode) {
            case Config.MODE_DENY -> childMode == SubagentPermissionMode.DENY;
            case Config.MODE_READ_ONLY, Config.MODE_WORKSPACE_WRITE ->
                    childMode == SubagentPermissionMode.DENY
                            || childMode == SubagentPermissionMode.READ_ONLY;
            case Config.MODE_ON_REQUEST -> true;
            default -> false;
        };
        if (!valid) {
            throw new SecurityException(
                    "subagent permission " + childMode
                            + " is broader than parent approval mode " + parentApprovalMode);
        }
    }
}
