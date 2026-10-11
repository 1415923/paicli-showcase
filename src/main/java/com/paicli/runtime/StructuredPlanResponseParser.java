package com.paicli.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.state.PlanCompletionModels.CompletionRuleType;
import com.paicli.state.PlanCompletionModels.EffectClass;
import com.paicli.state.PlanCompletionModels.EvidenceKind;
import com.paicli.state.PlanContentBounds;
import com.paicli.state.PlanToolRequirementPolicy;
import com.paicli.state.StructuredPlanModels.FreshnessPolicy;
import com.paicli.state.StructuredPlanModels.ToolRequirement;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Mode-aware, bounded admission for model-produced Plans. Response bodies are never logged. */
final class StructuredPlanResponseParser {
    static final String WIRE_SCHEMA_VERSION = "structured-plan-wire-v1";
    static final String ADVISORY_WIRE_SCHEMA_VERSION = "advisory-plan-wire-v1";
    static final List<String> REQUIRED_SCHEMA_FIELDS = List.of(
            "$.acceptanceCriteria", "$.assumptions", "$.risks",
            "$.verificationStrategy", "$.steps",
            "$.steps[].title", "$.steps[].definition", "$.steps[].dependsOn",
            "$.steps[].completionRule", "$.steps[].requiredEvidenceKinds",
            "$.steps[].effectClass", "$.steps[].toolRequirements",
            "$.steps[].toolRequirements[].requirementId",
            "$.steps[].toolRequirements[].requirementOrder",
            "$.steps[].toolRequirements[].toolName",
            "$.steps[].toolRequirements[].effectClass",
            "$.steps[].toolRequirements[].authoritySelector",
            "$.steps[].toolRequirements[].minSuccesses",
            "$.steps[].toolRequirements[].maxOccurrences",
            "$.steps[].toolRequirements[].freshnessPolicy");
    static final List<String> ADVISORY_REQUIRED_SCHEMA_FIELDS = List.of(
            "$.acceptanceCriteria", "$.assumptions", "$.risks",
            "$.verificationStrategy", "$.steps",
            "$.steps[].title", "$.steps[].definition", "$.steps[].dependsOn");
    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_TRAILING_TOKENS, true);
    private static final Pattern JSON_FENCE = Pattern.compile(
            "```(?:json)?\\s*([\\s\\S]*?)\\s*```",
            Pattern.CASE_INSENSITIVE);
    private static final int MAX_ENVELOPE_CODE_POINTS = 256;
    private static final int MAX_SCHEMA_ERRORS = 8;
    private final PlanRuntimeMode mode;

    /** Direct parser use remains the frozen Strict/internal contract. */
    StructuredPlanResponseParser() {
        this(PlanRuntimeMode.STRICT_INTERNAL);
    }

    StructuredPlanResponseParser(PlanRuntimeMode mode) {
        this.mode = java.util.Objects.requireNonNull(mode, "mode");
    }

    String wireSchemaVersion() {
        return mode == PlanRuntimeMode.DEFAULT_ADVISORY
                ? ADVISORY_WIRE_SCHEMA_VERSION : WIRE_SCHEMA_VERSION;
    }

    List<String> requiredSchemaFields() {
        return mode == PlanRuntimeMode.DEFAULT_ADVISORY
                ? ADVISORY_REQUIRED_SCHEMA_FIELDS : REQUIRED_SCHEMA_FIELDS;
    }

    JsonNode parse(String content, int maxBytes) {
        if (content == null || content.isBlank()) {
            throw parseFailure("EMPTY_CONTENT", content, 0, 0, false);
        }
        int bytes = content.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maxBytes) {
            throw parseFailure("OUTPUT_LIMIT_EXCEEDED", content, 0, 0, true);
        }

        String candidate = content.strip();
        JsonNode strict = read(candidate);
        if (strict != null) return validate(strict, content, 1, 0, false);

        List<String> fenced = fencedCandidates(content);
        if (!fenced.isEmpty() || content.contains("```")) {
            if (fenced.size() != 1) {
                throw parseFailure("MULTIPLE_OR_UNCLOSED_JSON_FENCE",
                        content, 0, fenced.size(), false);
            }
            JsonNode fencedRoot = read(fenced.getFirst().strip());
            if (fencedRoot == null) {
                throw parseFailure("FENCED_JSON_SYNTAX_ERROR",
                        content, 1, 1, structurallyTruncated(fenced.getFirst()));
            }
            return validate(fencedRoot, content, 1, 1, false);
        }

        Scan scan = scanObjects(content);
        if (scan.unclosed()) {
            throw parseFailure("TRUNCATED_JSON_OBJECT",
                    content, scan.ranges().size(), 0, true);
        }
        if (scan.ranges().size() != 1) {
            throw parseFailure(scan.ranges().isEmpty()
                            ? "JSON_OBJECT_NOT_FOUND" : "MULTIPLE_JSON_OBJECTS",
                    content, scan.ranges().size(), 0, false);
        }
        Range range = scan.ranges().getFirst();
        String prefix = content.substring(0, range.start()).strip();
        String suffix = content.substring(range.end()).strip();
        if (codePoints(prefix) > MAX_ENVELOPE_CODE_POINTS
                || codePoints(suffix) > MAX_ENVELOPE_CODE_POINTS) {
            throw parseFailure("UNBOUNDED_JSON_ENVELOPE",
                    content, 1, 0, false);
        }
        JsonNode extracted = read(content.substring(range.start(), range.end()));
        if (extracted == null) {
            throw parseFailure("JSON_SYNTAX_ERROR", content, 1, 0, false);
        }
        return validate(extracted, content, 1, 0, false);
    }

    SchemaSummary schemaSummary(String content) {
        JsonNode root = rootForSummary(content);
        if (root == null || !root.isObject()) {
            return new SchemaSummary(requiredSchemaFields(), List.of("$"), List.of());
        }
        LinkedHashSet<String> missing = new LinkedHashSet<>();
        LinkedHashSet<String> unknown = new LinkedHashSet<>();
        requiredDocument(root, "acceptanceCriteria", missing);
        requiredDocument(root, "assumptions", missing);
        requiredDocument(root, "risks", missing);
        missingText(root, "verificationStrategy", "$", missing);
        JsonNode steps = root.path("steps");
        if (!steps.isArray() || steps.isEmpty()) {
            missing.add("$.steps");
        } else {
            for (int stepIndex = 0; stepIndex < steps.size(); stepIndex++) {
                JsonNode step = steps.get(stepIndex);
                String stepPath = "$.steps[" + stepIndex + "]";
                if (!step.isObject()) {
                    missing.add(stepPath);
                    continue;
                }
                unknownFields(step, Set.of("title", "definition", "dependsOn",
                        "completionRule", "requiredEvidenceKinds", "effectClass",
                        "toolRequirements"), stepPath, unknown);
                missingText(step, "title", stepPath, missing);
                missingText(step, "definition", stepPath, missing);
                missingValue(step, "dependsOn", JsonNode::isArray, stepPath, missing);
                if (mode == PlanRuntimeMode.DEFAULT_ADVISORY) {
                    continue;
                }
                missingText(step, "completionRule", stepPath, missing);
                missingValue(step, "requiredEvidenceKinds", JsonNode::isArray,
                        stepPath, missing);
                missingText(step, "effectClass", stepPath, missing);
                JsonNode requirements = step.path("toolRequirements");
                if (!requirements.isArray()) {
                    missing.add(stepPath + ".toolRequirements");
                    continue;
                }
                for (int requirementIndex = 0;
                        requirementIndex < requirements.size(); requirementIndex++) {
                    JsonNode requirement = requirements.get(requirementIndex);
                    String requirementPath = stepPath + ".toolRequirements["
                            + requirementIndex + "]";
                    if (!requirement.isObject()) {
                        missing.add(requirementPath);
                        continue;
                    }
                    Set<String> fields = Set.of("requirementId", "requirementOrder",
                            "toolName", "effectClass", "authoritySelector", "minSuccesses",
                            "maxOccurrences", "freshnessPolicy");
                    unknownFields(requirement, fields, requirementPath, unknown);
                    missingText(requirement, "requirementId", requirementPath, missing);
                    missingValue(requirement, "requirementOrder", JsonNode::isIntegralNumber,
                            requirementPath, missing);
                    missingText(requirement, "toolName", requirementPath, missing);
                    missingText(requirement, "effectClass", requirementPath, missing);
                    missingValue(requirement, "authoritySelector", JsonNode::isObject,
                            requirementPath, missing);
                    missingValue(requirement, "minSuccesses", JsonNode::isIntegralNumber,
                            requirementPath, missing);
                    missingValue(requirement, "maxOccurrences", JsonNode::isIntegralNumber,
                            requirementPath, missing);
                    missingText(requirement, "freshnessPolicy", requirementPath, missing);
                    selectorSummary(requirement.path("toolName").asText(),
                            requirement.path("authoritySelector"), requirementPath
                                    + ".authoritySelector", missing, unknown);
                }
            }
        }
        return new SchemaSummary(requiredSchemaFields(),
                List.copyOf(missing), List.copyOf(unknown));
    }

    int normalizationCount(String content) {
        if (content == null) return 0;
        String candidate = content.strip();
        if (read(candidate) != null) return 0;
        List<String> fenced = fencedCandidates(content);
        if (fenced.size() == 1 && read(fenced.getFirst().strip()) != null) return 1;
        Scan scan = scanObjects(content);
        return !scan.unclosed() && scan.ranges().size() == 1 ? 1 : 0;
    }

    private static JsonNode rootForSummary(String content) {
        if (content == null || content.isBlank()) return null;
        JsonNode strict = read(content.strip());
        if (strict != null) return strict;
        List<String> fenced = fencedCandidates(content);
        if (fenced.size() == 1) {
            JsonNode value = read(fenced.getFirst().strip());
            if (value != null) return value;
        }
        Scan scan = scanObjects(content);
        if (scan.unclosed() || scan.ranges().size() != 1) return null;
        Range range = scan.ranges().getFirst();
        return read(content.substring(range.start(), range.end()));
    }

    private static void requiredDocument(JsonNode root, String field,
            Set<String> missing) {
        JsonNode value = root.path(field);
        if (value.isMissingNode() || value.isNull()
                || (!value.isTextual() && !value.isArray())) missing.add("$." + field);
    }

    private static void missingText(JsonNode node, String field, String path,
            Set<String> missing) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) missing.add(path + "." + field);
    }

    private static void missingValue(JsonNode node, String field,
            java.util.function.Predicate<JsonNode> valid, String path, Set<String> missing) {
        if (!valid.test(node.path(field))) missing.add(path + "." + field);
    }

    private static void unknownFields(JsonNode node, Set<String> allowed, String path,
            Set<String> unknown) {
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) {
                unknown.add(path + ".__UNKNOWN_FIELD_" + (unknown.size() + 1));
            }
        });
    }

    private static void selectorSummary(String toolName, JsonNode selector, String path,
            Set<String> missing, Set<String> unknown) {
        if (!selector.isObject()) return;
        missingText(selector, "kind", path, missing);
        Set<String> allowed = switch (toolName) {
            case "read_file" -> Set.of("kind", "path", "maxLines", "includeInternal");
            case "list_files" -> Set.of("kind", "path", "includeInternal");
            case "write_file" -> Set.of("kind", "path", "overwrite");
            case "grep" -> Set.of("kind", "root", "pattern", "includeInternal");
            case "run_command" -> Set.of("kind", "command");
            case "git_diff" -> Set.of("kind", "subcommand", "args");
            default -> Set.of("kind");
        };
        unknownFields(selector, allowed, path, unknown);
        switch (toolName) {
            case "read_file", "list_files", "write_file" ->
                    missingText(selector, "path", path, missing);
            case "grep" -> missingText(selector, "pattern", path, missing);
            case "run_command" -> missingText(selector, "command", path, missing);
            default -> { }
        }
    }

    record SchemaSummary(List<String> requiredFields,
            List<String> missingRequiredFields, List<String> unknownFields) {
        SchemaSummary {
            requiredFields = List.copyOf(requiredFields);
            missingRequiredFields = List.copyOf(missingRequiredFields);
            unknownFields = List.copyOf(unknownFields);
        }
    }

    private JsonNode validate(JsonNode root, String content, int candidates,
            int fences, boolean truncated) {
        List<String> errors = schemaErrors(root);
        if (!errors.isEmpty()) {
            throw new SchemaFailure(diagnostic("SCHEMA_VALIDATION_FAILED",
                    content, candidates, fences, truncated), errors);
        }
        List<String> bounds = contentBoundErrors(root);
        if (!bounds.isEmpty()) {
            throw new ContentBoundsFailure(diagnostic("CONTENT_BOUNDS_FAILED",
                    content, candidates, fences, truncated), bounds);
        }
        return root;
    }

    private List<String> schemaErrors(JsonNode root) {
        if (mode == PlanRuntimeMode.DEFAULT_ADVISORY) {
            return advisorySchemaErrors(root);
        }
        return strictSchemaErrors(root);
    }

    private static List<String> advisorySchemaErrors(JsonNode root) {
        List<String> errors = new ArrayList<>();
        if (!root.isObject()) {
            return List.of("$:TOP_LEVEL_OBJECT_REQUIRED");
        }
        requiredDocumentField(root, "acceptanceCriteria", errors);
        requiredDocumentField(root, "assumptions", errors);
        requiredDocumentField(root, "risks", errors);
        requiredText(root, "verificationStrategy", "$", errors);
        JsonNode steps = root.path("steps");
        if (!steps.isArray() || steps.isEmpty()) {
            add(errors, "$.steps:NON_EMPTY_ARRAY_REQUIRED");
            return List.copyOf(errors);
        }
        for (int index = 0; index < steps.size(); index++) {
            JsonNode step = steps.get(index);
            String path = "$.steps[" + index + "]";
            if (!step.isObject()) {
                add(errors, path + ":OBJECT_REQUIRED");
                continue;
            }
            exactFields(step, Set.of("title", "definition", "dependsOn",
                    "completionRule", "requiredEvidenceKinds", "effectClass",
                    "toolRequirements"), path, errors);
            requiredText(step, "title", path, errors);
            requiredText(step, "definition", path, errors);
            JsonNode dependencies = step.path("dependsOn");
            if (!dependencies.isArray()) {
                add(errors, path + ".dependsOn:ARRAY_REQUIRED");
            } else {
                for (int dependency = 0; dependency < dependencies.size(); dependency++) {
                    JsonNode value = dependencies.get(dependency);
                    if (!value.isIntegralNumber() || value.asInt(-1) < 0
                            || value.asInt(-1) >= steps.size()) {
                        add(errors, path + ".dependsOn[" + dependency
                                + "]:INVALID_STEP_INDEX");
                    }
                }
            }
        }
        return List.copyOf(errors);
    }

    private static List<String> strictSchemaErrors(JsonNode root) {
        List<String> errors = new ArrayList<>();
        if (!root.isObject()) {
            return List.of("$:TOP_LEVEL_OBJECT_REQUIRED");
        }
        requiredDocumentField(root, "acceptanceCriteria", errors);
        requiredDocumentField(root, "assumptions", errors);
        requiredDocumentField(root, "risks", errors);
        requiredText(root, "verificationStrategy", "$", errors);
        JsonNode steps = root.path("steps");
        if (!steps.isArray() || steps.isEmpty()) {
            add(errors, "$.steps:NON_EMPTY_ARRAY_REQUIRED");
            return List.copyOf(errors);
        }
        java.util.Set<String> revisionRequirementIds = new java.util.HashSet<>();
        for (int index = 0; index < steps.size(); index++) {
            JsonNode step = steps.get(index);
            String path = "$.steps[" + index + "]";
            if (!step.isObject()) {
                add(errors, path + ":OBJECT_REQUIRED");
                continue;
            }
            requiredText(step, "title", path, errors);
            requiredText(step, "definition", path, errors);
            enumField(step, "completionRule", path, CompletionRuleType.class, errors);
            enumField(step, "effectClass", path, EffectClass.class, errors);
            enumArray(step, "requiredEvidenceKinds", path, EvidenceKind.class, errors);
            toolRequirements(step, path, revisionRequirementIds, errors);
            JsonNode dependencies = step.path("dependsOn");
            if (!dependencies.isArray()) {
                add(errors, path + ".dependsOn:ARRAY_REQUIRED");
            } else {
                for (int dependency = 0; dependency < dependencies.size(); dependency++) {
                    JsonNode value = dependencies.get(dependency);
                    if (!value.isIntegralNumber()
                            || value.asInt(-1) < 0
                            || value.asInt(-1) >= steps.size()) {
                        add(errors, path + ".dependsOn[" + dependency
                                + "]:INVALID_STEP_INDEX");
                    }
                }
            }
        }
        return List.copyOf(errors);
    }

    private static void toolRequirements(JsonNode step, String path,
            java.util.Set<String> revisionRequirementIds, List<String> errors) {
        JsonNode requirements = step.path("toolRequirements");
        boolean toolEvidence = false;
        for (JsonNode kind : step.path("requiredEvidenceKinds")) {
            if (kind.isTextual() && "TOOL_CALL".equals(kind.asText())) toolEvidence = true;
        }
        if (!requirements.isArray()) {
            add(errors, path + ".toolRequirements:ARRAY_REQUIRED");
            return;
        }
        if (toolEvidence != !requirements.isEmpty()) {
            add(errors, path + ".toolRequirements:TOOL_CALL_SCOPE_MISMATCH");
        }
        java.util.Set<String> ids = new java.util.HashSet<>();
        java.util.Set<Integer> orders = new java.util.HashSet<>();
        for (int index = 0; index < requirements.size(); index++) {
            JsonNode requirement = requirements.get(index);
            String requirementPath = path + ".toolRequirements[" + index + "]";
            if (!requirement.isObject()) {
                add(errors, requirementPath + ":OBJECT_REQUIRED");
                continue;
            }
            exactFields(requirement, java.util.Set.of("requirementId", "requirementOrder",
                    "toolName", "effectClass", "authoritySelector", "minSuccesses",
                    "maxOccurrences", "freshnessPolicy"), requirementPath, errors);
            String requirementId = textual(requirement.path("requirementId"));
            if (requirementId == null || !ids.add(requirementId)
                    || !revisionRequirementIds.add(requirementId)) {
                add(errors, requirementPath + ".requirementId:REQUIRED_UNIQUE_TEXT");
            }
            JsonNode order = requirement.path("requirementOrder");
            if (!order.isIntegralNumber() || order.asInt(-1) != index || !orders.add(order.asInt())) {
                add(errors, requirementPath + ".requirementOrder:CONTIGUOUS_UNIQUE_ORDER_REQUIRED");
            }
            String toolName = textual(requirement.path("toolName"));
            EffectClass effect = parsedEnum(requirement.path("effectClass"), EffectClass.class);
            int minimum = integral(requirement.path("minSuccesses"));
            int maximum = integral(requirement.path("maxOccurrences"));
            FreshnessPolicy freshness = parsedEnum(requirement.path("freshnessPolicy"),
                    FreshnessPolicy.class);
            if (minimum < 1 || maximum < minimum || maximum > 64) {
                add(errors, requirementPath + ":INVALID_CARDINALITY");
            }
            if (toolName == null || effect == null || freshness == null) {
                add(errors, requirementPath + ":INVALID_TOOL_EFFECT_OR_FRESHNESS");
                continue;
            }
            try {
                var selector = PlanToolRequirementPolicy.normalize(toolName,
                        requirement.path("authoritySelector"));
                new ToolRequirement(requirementId, index, toolName, effect, selector,
                        minimum, maximum, freshness);
            } catch (RuntimeException error) {
                add(errors, requirementPath + ".authoritySelector:UNSUPPORTED_OR_INVALID");
            }
        }
    }

    private static void exactFields(JsonNode node, java.util.Set<String> allowed,
            String path, List<String> errors) {
        boolean[] unknown = {false};
        node.fieldNames().forEachRemaining(field -> {
            if (!allowed.contains(field)) unknown[0] = true;
        });
        if (unknown[0]) add(errors, path + ":UNKNOWN_FIELD");
    }

    private static String textual(JsonNode value) {
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private static int integral(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt() ? value.asInt() : -1;
    }

    private static <E extends Enum<E>> E parsedEnum(JsonNode value, Class<E> type) {
        if (!value.isTextual()) return null;
        try { return Enum.valueOf(type, value.asText()); }
        catch (IllegalArgumentException error) { return null; }
    }

    private List<String> contentBoundErrors(JsonNode root) {
        List<PlanContentBounds.StepText> boundedSteps = new ArrayList<>();
        for (JsonNode step : root.path("steps")) {
            boundedSteps.add(new PlanContentBounds.StepText(
                    step.path("title").asText(), step.path("definition").asText(),
                    mode == PlanRuntimeMode.DEFAULT_ADVISORY ? ""
                            : requirementMaterial(step)));
        }
        return PlanContentBounds.validateFields(
                canonicalText(root.path("acceptanceCriteria")),
                canonicalText(root.path("assumptions")), canonicalText(root.path("risks")),
                canonicalText(root.path("verificationStrategy")), boundedSteps).errors();
    }

    private static String requirementMaterial(JsonNode step) {
        List<String> material = new ArrayList<>();
        for (JsonNode value : step.path("toolRequirements")) {
            String toolName = value.path("toolName").asText();
            var selector = PlanToolRequirementPolicy.normalize(toolName,
                    value.path("authoritySelector"));
            material.add(String.join("\n", value.path("requirementId").asText(),
                    value.path("requirementOrder").asText(), toolName,
                    value.path("effectClass").asText(), selector.kind().name(),
                    selector.canonicalJson(), selector.digest(),
                    value.path("minSuccesses").asText(), value.path("maxOccurrences").asText(),
                    value.path("freshnessPolicy").asText()));
        }
        return String.join("\n--\n", material);
    }

    private static String canonicalText(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) return "";
        if (value.isTextual()) return value.asText();
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalArgumentException("Structured Plan field could not be normalized", error);
        }
    }

    private static void requiredDocumentField(JsonNode node, String name,
            List<String> errors) {
        JsonNode value = node.path(name);
        if (value.isMissingNode() || value.isNull()
                || (!value.isTextual() && !value.isArray())) {
            add(errors, "$." + name + ":TEXT_OR_ARRAY_REQUIRED");
        }
    }

    private static void requiredText(JsonNode node, String name, String path,
            List<String> errors) {
        JsonNode value = node.path(name);
        if (!value.isTextual() || value.asText().isBlank()) {
            add(errors, path + "." + name + ":NON_EMPTY_TEXT_REQUIRED");
        }
    }

    private static <E extends Enum<E>> void enumField(JsonNode node, String name,
            String path, Class<E> type, List<String> errors) {
        JsonNode value = node.path(name);
        if (!value.isTextual()) {
            add(errors, path + "." + name + ":ENUM_REQUIRED");
            return;
        }
        try {
            Enum.valueOf(type, value.asText());
        } catch (IllegalArgumentException error) {
            add(errors, path + "." + name + ":ENUM_MISMATCH");
        }
    }

    private static <E extends Enum<E>> void enumArray(JsonNode node, String name,
            String path, Class<E> type, List<String> errors) {
        JsonNode values = node.path(name);
        if (!values.isArray() || values.isEmpty()) {
            add(errors, path + "." + name + ":NON_EMPTY_ENUM_ARRAY_REQUIRED");
            return;
        }
        for (int index = 0; index < values.size(); index++) {
            JsonNode value = values.get(index);
            if (!value.isTextual()) {
                add(errors, path + "." + name + "[" + index + "]:ENUM_REQUIRED");
                continue;
            }
            try {
                Enum.valueOf(type, value.asText());
            } catch (IllegalArgumentException error) {
                add(errors, path + "." + name + "[" + index + "]:ENUM_MISMATCH");
            }
        }
    }

    private static void add(List<String> errors, String value) {
        if (errors.size() < MAX_SCHEMA_ERRORS) errors.add(value);
    }

    private static JsonNode read(String value) {
        try {
            return JSON.readTree(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static List<String> fencedCandidates(String content) {
        List<String> result = new ArrayList<>();
        Matcher matcher = JSON_FENCE.matcher(content);
        while (matcher.find()) result.add(matcher.group(1));
        return List.copyOf(result);
    }

    private static Scan scanObjects(String content) {
        List<Range> ranges = new ArrayList<>();
        boolean quoted = false;
        boolean escaped = false;
        int depth = 0;
        int start = -1;
        for (int index = 0; index < content.length(); index++) {
            char character = content.charAt(index);
            if (quoted) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    quoted = false;
                }
                continue;
            }
            if (character == '"') {
                quoted = true;
            } else if (character == '{') {
                if (depth == 0) start = index;
                depth++;
            } else if (character == '}') {
                if (depth == 0) {
                    return new Scan(List.copyOf(ranges), true);
                }
                depth--;
                if (depth == 0) {
                    ranges.add(new Range(start, index + 1));
                    start = -1;
                }
            }
        }
        return new Scan(List.copyOf(ranges), depth != 0 || quoted);
    }

    private static boolean structurallyTruncated(String content) {
        return scanObjects(content).unclosed();
    }

    private static ParseFailure parseFailure(String category, String content,
            int candidates, int fences, boolean truncated) {
        return new ParseFailure(diagnostic(category, content,
                candidates, fences, truncated));
    }

    private static String diagnostic(String category, String content,
            int candidates, int fences, boolean truncated) {
        String value = content == null ? "" : content;
        String stripped = value.stripLeading();
        String first = stripped.isEmpty() ? "none"
                : "U+" + Integer.toHexString(stripped.codePointAt(0))
                        .toUpperCase(Locale.ROOT);
        return "STRUCTURED_PLAN_" + category
                + " content_length=" + value.length()
                + " first=" + first
                + " code_fences=" + fences
                + " json_candidates=" + candidates
                + " truncated=" + truncated;
    }

    private static int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }

    static class ParseFailure extends RuntimeException {
        ParseFailure(String message) {
            super(message);
        }
    }

    static final class SchemaFailure extends ParseFailure {
        private final List<String> errors;

        SchemaFailure(String message, List<String> errors) {
            super(message);
            this.errors = List.copyOf(errors);
        }

        List<String> errors() {
            return errors;
        }
    }

    static final class ContentBoundsFailure extends ParseFailure {
        private final List<String> errors;
        ContentBoundsFailure(String message, List<String> errors) {
            super(message + " errors=" + String.join(",", errors.stream().limit(8).toList()));
            this.errors = List.copyOf(errors);
        }
        List<String> errors() { return errors; }
    }

    private record Range(int start, int end) { }
    private record Scan(List<Range> ranges, boolean unclosed) { }
}
