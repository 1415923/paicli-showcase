package com.paicli.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.state.PlanCompletionModels.EffectClass;
import com.paicli.state.StructuredPlanModels.AuthoritySelectorKind;
import com.paicli.state.StructuredPlanModels.ToolAuthoritySelector;
import com.paicli.safety.SafetyPolicy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Exact authority facts emitted by a Tool's successful, side-effect-free preflight. */
public record ToolAuthorityProjection(String toolName, EffectClass effectClass,
        ToolAuthoritySelector authoritySelector) {
    private static final ObjectMapper JSON = new ObjectMapper();

    public ToolAuthorityProjection {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("Tool authority projection requires a Tool name");
        }
        Objects.requireNonNull(effectClass, "effectClass");
        Objects.requireNonNull(authoritySelector, "authoritySelector");
    }

    public static ToolAuthorityProjection exactPath(String toolName, EffectClass effectClass,
            String normalizedPath, Integer maxLines, boolean includeInternal, Boolean overwrite) {
        ObjectNode value = JSON.createObjectNode();
        value.put("path", requireText(normalizedPath, "normalizedPath"));
        if (maxLines != null) value.put("maxLines", maxLines);
        else if ("read_file".equals(toolName)) value.putNull("maxLines");
        if ("read_file".equals(toolName) || "list_files".equals(toolName)) {
            value.put("includeInternal", includeInternal);
        }
        if (overwrite != null) value.put("overwrite", overwrite);
        return projection(toolName, effectClass, AuthoritySelectorKind.EXACT_PATH, value);
    }

    public static ToolAuthorityProjection exactGrep(String toolName, String normalizedRoot,
            String pattern, boolean includeInternal) {
        ObjectNode value = JSON.createObjectNode();
        value.put("root", requireText(normalizedRoot, "normalizedRoot"));
        value.put("pattern", requireText(pattern, "pattern"));
        value.put("includeInternal", includeInternal);
        return projection(toolName, EffectClass.READ, AuthoritySelectorKind.EXACT_GREP, value);
    }

    public static ToolAuthorityProjection exactCommand(String toolName, String command) {
        ObjectNode value = JSON.createObjectNode();
        value.put("command", requireText(command, "command"));
        return projection(toolName, EffectClass.GENERAL, AuthoritySelectorKind.EXACT_COMMAND,
                value);
    }

    public static ToolAuthorityProjection exactPathSet(String toolName, EffectClass effectClass,
            List<String> normalizedPaths) {
        if (normalizedPaths == null || normalizedPaths.isEmpty()) {
            throw new IllegalArgumentException("Exact path-set authority cannot be empty");
        }
        List<String> paths = normalizedPaths.stream()
                .map(path -> requireText(path, "path"))
                .distinct().sorted().toList();
        if (paths.size() != normalizedPaths.size()) {
            throw new IllegalArgumentException("Exact path-set authority cannot contain duplicates");
        }
        ObjectNode value = JSON.createObjectNode();
        ArrayNode array = value.putArray("paths");
        paths.forEach(array::add);
        return projection(toolName, effectClass, AuthoritySelectorKind.EXACT_PATH_SET, value);
    }

    public static ToolAuthorityProjection exactGitDiff(String toolName, String subcommand,
            List<String> normalizedArguments) {
        ObjectNode value = JSON.createObjectNode();
        value.put("subcommand", requireText(subcommand, "subcommand"));
        ArrayNode arguments = value.putArray("args");
        Objects.requireNonNull(normalizedArguments, "normalizedArguments")
                .forEach(argument -> arguments.add(requireText(argument, "Git argument")));
        return projection(toolName, EffectClass.READ, AuthoritySelectorKind.EXACT_GIT_DIFF,
                value);
    }

    public static String normalizedWorkspacePath(SafetyPolicy policy,
            java.nio.file.Path resolved) {
        String normalized = policy.getWorkspaceRoot().relativize(resolved)
                .toString().replace('\\', '/');
        return normalized.isEmpty() ? "." : normalized;
    }

    private static ToolAuthorityProjection projection(String toolName, EffectClass effectClass,
            AuthoritySelectorKind kind, ObjectNode value) {
        try {
            String canonicalJson = JSON.writeValueAsString(value);
            return new ToolAuthorityProjection(toolName, effectClass,
                    new ToolAuthoritySelector(kind, canonicalJson, sha256(canonicalJson)));
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Tool authority selector cannot be canonicalized",
                    error);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}
