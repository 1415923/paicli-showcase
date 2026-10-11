package com.paicli.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.state.PlanCompletionModels.EffectClass;
import com.paicli.state.StructuredPlanModels.AuthoritySelectorKind;
import com.paicli.state.StructuredPlanModels.ToolAuthoritySelector;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Shared fail-closed V3 Tool/selector policy and canonical selector normalizer. */
public final class PlanToolRequirementPolicy {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> SUPPORTED_TOOLS = Set.of(
            "read_file", "list_files", "grep", "run_command", "git_diff", "write_file");
    private static final Pattern CONTROL = Pattern.compile("[\\u0000-\\u001f\\u007f]");

    private PlanToolRequirementPolicy() { }

    public static Set<String> supportedToolNames() { return SUPPORTED_TOOLS; }

    /** Converts the typed Provider wire object into immutable canonical authority. */
    public static ToolAuthoritySelector normalize(String toolName, JsonNode selector) {
        if (!SUPPORTED_TOOLS.contains(toolName) || selector == null || !selector.isObject()) {
            throw new IllegalArgumentException("Unsupported Tool or authority selector");
        }
        AuthoritySelectorKind kind = enumValue(selector.path("kind"), AuthoritySelectorKind.class,
                "authoritySelector.kind");
        ObjectNode canonical = JSON.createObjectNode();
        switch (kind) {
            case EXACT_PATH -> exactPath(toolName, selector, canonical);
            case EXACT_GREP -> exactGrep(toolName, selector, canonical);
            case EXACT_COMMAND -> exactCommand(toolName, selector, canonical);
            case EXACT_PATH_SET -> throw new IllegalArgumentException(
                    "EXACT_PATH_SET has no currently registered production Tool");
            case EXACT_GIT_DIFF -> exactGitDiff(toolName, selector, canonical);
        }
        String canonicalJson;
        try { canonicalJson = JSON.writeValueAsString(canonical); }
        catch (Exception error) { throw new IllegalArgumentException("Selector canonicalization failed", error); }
        return new ToolAuthoritySelector(kind, canonicalJson, digest(canonicalJson));
    }

    public static void validate(String toolName, EffectClass effectClass,
            ToolAuthoritySelector selector) {
        if (!SUPPORTED_TOOLS.contains(toolName) || effectClass == null || selector == null) {
            throw new IllegalArgumentException("Unsupported Tool requirement");
        }
        AuthoritySelectorKind expectedKind;
        Set<EffectClass> effects;
        switch (toolName) {
            case "read_file", "list_files" -> {
                expectedKind = AuthoritySelectorKind.EXACT_PATH;
                effects = Set.of(EffectClass.READ);
            }
            case "grep" -> {
                expectedKind = AuthoritySelectorKind.EXACT_GREP;
                effects = Set.of(EffectClass.READ);
            }
            case "run_command" -> {
                expectedKind = AuthoritySelectorKind.EXACT_COMMAND;
                effects = Set.of(EffectClass.GENERAL);
            }
            case "git_diff" -> {
                expectedKind = AuthoritySelectorKind.EXACT_GIT_DIFF;
                effects = Set.of(EffectClass.READ);
            }
            case "write_file" -> {
                expectedKind = AuthoritySelectorKind.EXACT_PATH;
                effects = Set.of(EffectClass.WRITE);
            }
            default -> throw new IllegalArgumentException("Unsupported Tool requirement");
        }
        if (selector.kind() != expectedKind || !effects.contains(effectClass)) {
            throw new IllegalArgumentException("Tool, effect and selector kind do not match");
        }
        try {
            JsonNode value = JSON.readTree(selector.canonicalJson());
            ObjectNode wire = JSON.createObjectNode();
            wire.put("kind", selector.kind().name());
            value.fields().forEachRemaining(entry -> wire.set(entry.getKey(), entry.getValue()));
            ToolAuthoritySelector normalized = normalize(toolName, wire);
            if (!normalized.equals(selector)) {
                throw new IllegalArgumentException("Selector is not canonical");
            }
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception error) { throw new IllegalArgumentException("Selector is not canonical", error); }
    }

    public static String digest(String canonicalJson) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) { throw new IllegalStateException("SHA-256 unavailable", error); }
    }

    private static void exactPath(String toolName, JsonNode source, ObjectNode target) {
        Set<String> allowed = switch (toolName) {
            case "read_file" -> Set.of("kind", "path", "maxLines", "includeInternal");
            case "list_files" -> Set.of("kind", "path", "includeInternal");
            case "write_file" -> Set.of("kind", "path", "overwrite");
            default -> throw new IllegalArgumentException("EXACT_PATH is unsupported for Tool");
        };
        exactFields(source, allowed);
        target.put("path", normalizedPath(text(source, "path")));
        if ("read_file".equals(toolName)) {
            JsonNode maxLines = source.path("maxLines");
            if (maxLines.isMissingNode() || maxLines.isNull()) target.putNull("maxLines");
            else if (!maxLines.isIntegralNumber() || !maxLines.canConvertToInt()) {
                throw new IllegalArgumentException("maxLines must be an integer");
            } else if (maxLines.asInt() <= 0) target.putNull("maxLines");
            else target.put("maxLines", maxLines.asInt());
        }
        if ("read_file".equals(toolName) || "list_files".equals(toolName)) {
            target.put("includeInternal", optionalBoolean(source, "includeInternal", false));
        }
        if ("write_file".equals(toolName)) {
            target.put("overwrite", optionalBoolean(source, "overwrite", false));
        }
    }

    private static void exactGrep(String toolName, JsonNode source, ObjectNode target) {
        if (!"grep".equals(toolName)) throw new IllegalArgumentException("EXACT_GREP Tool mismatch");
        exactFields(source, Set.of("kind", "root", "pattern", "includeInternal"));
        target.put("root", normalizedPath(source.path("root").isMissingNode()
                ? "." : text(source, "root")));
        String pattern = text(source, "pattern");
        if (pattern.length() > 4096) throw new IllegalArgumentException("grep pattern is too long");
        try { Pattern.compile(pattern); }
        catch (RuntimeException error) { throw new IllegalArgumentException("grep pattern is invalid"); }
        target.put("pattern", pattern);
        target.put("includeInternal", optionalBoolean(source, "includeInternal", false));
    }

    private static void exactCommand(String toolName, JsonNode source, ObjectNode target) {
        if (!"run_command".equals(toolName)) throw new IllegalArgumentException("EXACT_COMMAND Tool mismatch");
        exactFields(source, Set.of("kind", "command"));
        String command = text(source, "command")
                .replace("\r\n", "\n").replace('\r', '\n').strip();
        if (command.isEmpty() || command.length() > 8192 || command.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("command is invalid");
        }
        target.put("command", command);
    }

    private static void exactGitDiff(String toolName, JsonNode source, ObjectNode target) {
        if (!"git_diff".equals(toolName)) throw new IllegalArgumentException("EXACT_GIT_DIFF Tool mismatch");
        exactFields(source, Set.of("kind", "subcommand", "args"));
        String subcommand = source.path("subcommand").isMissingNode()
                ? "diff" : text(source, "subcommand").strip().toLowerCase(java.util.Locale.ROOT);
        if (!Set.of("diff", "status", "log").contains(subcommand)) {
            throw new IllegalArgumentException("Unsupported git_diff subcommand");
        }
        target.put("subcommand", subcommand);
        JsonNode args = source.path("args");
        if (!args.isMissingNode() && !args.isArray()) {
            throw new IllegalArgumentException("git_diff args must be an array");
        }
        ArrayNode canonicalArgs = target.putArray("args");
        if (args.isArray()) {
            if (args.size() > 32) throw new IllegalArgumentException("Too many git_diff args");
            for (JsonNode argument : args) {
                if (!argument.isTextual() || argument.asText().isBlank()
                        || argument.asText().length() > 512 || CONTROL.matcher(argument.asText()).find()) {
                    throw new IllegalArgumentException("Invalid git_diff argument");
                }
                canonicalArgs.add(argument.asText());
            }
        }
    }

    private static String normalizedPath(String value) {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0 || value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Workspace path must be normalized and relative");
        }
        final Path path;
        try { path = Path.of(value); }
        catch (RuntimeException error) { throw new IllegalArgumentException("Workspace path is invalid"); }
        if (path.isAbsolute()) throw new IllegalArgumentException("Workspace path must be relative");
        String normalized = path.normalize().toString().replace('\\', '/');
        if (normalized.isBlank()) normalized = ".";
        if (normalized.equals("..") || normalized.startsWith("../") || !normalized.equals(value)) {
            throw new IllegalArgumentException("Workspace path must already be normalized");
        }
        return normalized;
    }

    private static void exactFields(JsonNode node, Set<String> allowed) {
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!allowed.containsAll(actual)) {
            throw new IllegalArgumentException("Unknown authority selector field");
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank text");
        }
        return value.asText();
    }

    private static boolean optionalBoolean(JsonNode node, String field, boolean defaultValue) {
        JsonNode value = node.path(field);
        if (value.isMissingNode()) return defaultValue;
        if (!value.isBoolean()) throw new IllegalArgumentException(field + " must be boolean");
        return value.asBoolean();
    }

    private static <E extends Enum<E>> E enumValue(JsonNode value, Class<E> type, String field) {
        if (!value.isTextual()) throw new IllegalArgumentException(field + " must be text");
        try { return Enum.valueOf(type, value.asText()); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("Unsupported " + field); }
    }
}
