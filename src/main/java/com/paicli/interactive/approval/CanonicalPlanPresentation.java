package com.paicli.interactive.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.runtime.PersistenceSanitizer;
import com.paicli.state.PlanCanonicalizer;
import com.paicli.state.StructuredPlanModels.Dependency;
import com.paicli.state.StructuredPlanModels.Revision;
import com.paicli.state.StructuredPlanModels.StepDefinition;
import com.paicli.state.StructuredPlanModels.ToolRequirement;

import java.util.ArrayList;
import java.util.List;

/** Public, non-authoritative rendering of a verified immutable Plan revision. */
public final class CanonicalPlanPresentation {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final PersistenceSanitizer SANITIZER = new PersistenceSanitizer();
    private static final int SUMMARY_CODE_POINTS = 256;
    public static final String ADVISORY_WARNING =
            "Plan is advisory; Tool execution remains governed by runtime safety and approval policy.";

    private CanonicalPlanPresentation() { }

    public record RequirementProjection(String requirementId, int requirementOrder,
            String toolName, String effectClass, String selectorKind,
            String safeSelectorSummary, int minSuccesses, int maxOccurrences,
            String freshnessPolicy, int requiredSlots) { }

    public static List<String> details(Revision revision) {
        requireVerified(revision);
        List<String> lines = new ArrayList<>();
        lines.add("Plan revision: " + revision.planRevisionId().value());
        lines.add("Plan hash: " + revision.canonicalHash());
        lines.add("Plan canonical schema: V" + revision.canonicalSchemaVersion());
        appendField(lines,"Goal",revision.goal());
        appendField(lines,"Acceptance criteria",revision.acceptanceCriteria());
        appendField(lines,"Assumptions",revision.assumptions());
        appendField(lines,"Risks",revision.risks());
        appendField(lines,"Verification strategy",revision.verificationStrategy());
        lines.add("Steps (" + revision.steps().size() + "):");
        for (StepDefinition step : revision.steps()) {
            lines.add((step.order() + 1) + ". " + step.title());
            lines.add("   Definition: " + step.definition());
            List<Integer> dependencies = revision.dependencies().stream()
                    .filter(dependency -> dependency.stepRevisionId()
                            .equals(step.stepRevisionId()))
                    .map(dependency -> orderOf(revision, dependency))
                    .filter(order -> order >= 0).map(order -> order + 1).toList();
            String dependencySummary = dependencies.isEmpty() ? "none" : String.join(",",
                    dependencies.stream().map(String::valueOf).toList());
            String evidenceSummary = step.requiredEvidenceKinds().isEmpty() ? "none"
                    : String.join(",", step.requiredEvidenceKinds().stream()
                    .map(Enum::name).toList());
            lines.add("   Scope: dependencies=" + dependencySummary
                    + "; completion=" + value(step.completionRule())
                    + "; evidence=" + evidenceSummary
                    + "; effect=" + value(step.effectClass())
                    + "; requirements=" + step.toolRequirements().size());
            if (revision.canonicalSchemaVersion() < Revision.CANONICAL_SCHEMA_V3) {
                lines.add("   Structured requirements: unavailable in V"
                        + revision.canonicalSchemaVersion() + " (not fabricated)");
                continue;
            }
            for (ToolRequirement requirement : step.toolRequirements()) {
                RequirementProjection projection = requirement(requirement);
                lines.add("   Requirement " + (projection.requirementOrder() + 1)
                        + ": requirementId=" + projection.requirementId()
                        + "; requirementOrder=" + projection.requirementOrder()
                        + "; toolName=" + projection.toolName()
                        + "; effectClass=" + projection.effectClass()
                        + "; selectorKind=" + projection.selectorKind()
                        + "; " + projection.safeSelectorSummary()
                        + "; minSuccesses=" + projection.minSuccesses()
                        + "; maxOccurrences=" + projection.maxOccurrences()
                        + "; freshnessPolicy=" + projection.freshnessPolicy()
                        + "; requiredSlots=" + projection.requiredSlots());
            }
        }
        return List.copyOf(lines);
    }

    /** Public v1.2 projection: immutable intent, not Strict Tool authority. */
    public static List<String> advisoryDetails(Revision revision) {
        requireVerified(revision);
        List<String> lines=new ArrayList<>();
        lines.add("Plan revision: "+revision.planRevisionId().value());
        lines.add("Plan hash: "+revision.canonicalHash());
        lines.add("Plan canonical schema: V"+revision.canonicalSchemaVersion());
        lines.add("Plan mode: DEFAULT_ADVISORY_PLAN");
        lines.add(ADVISORY_WARNING);
        appendField(lines,"Goal",revision.goal());
        appendField(lines,"Acceptance criteria",revision.acceptanceCriteria());
        appendField(lines,"Assumptions",revision.assumptions());
        appendField(lines,"Risks",revision.risks());
        appendField(lines,"Verification strategy",revision.verificationStrategy());
        lines.add("Steps ("+revision.steps().size()+"):");
        for(StepDefinition step:revision.steps()){
            lines.add((step.order()+1)+". "+step.title());
            lines.add("   Definition: "+step.definition());
            List<Integer> dependencies=revision.dependencies().stream()
                    .filter(dependency->dependency.stepRevisionId().equals(step.stepRevisionId()))
                    .map(dependency->orderOf(revision,dependency)).filter(order->order>=0)
                    .map(order->order+1).toList();
            lines.add("   Advisory dependencies: "+(dependencies.isEmpty()?"none":
                    String.join(",",dependencies.stream().map(String::valueOf).toList())));
        }
        return List.copyOf(lines);
    }

    public static RequirementProjection requirement(ToolRequirement requirement) {
        if (requirement == null) {
            throw new IllegalStateException("Plan requirement projection is unavailable");
        }
        List<String> fields = selectorFields(requirement);
        String selector = fields.isEmpty() ? displayKind(requirement) : String.join("; ", fields);
        return new RequirementProjection(requirement.requirementId(),
                requirement.requirementOrder(), requirement.toolName(),
                requirement.effectClass().name(), displayKind(requirement),
                bounded(selector, SUMMARY_CODE_POINTS), requirement.minSuccesses(),
                requirement.maxOccurrences(), requirement.freshnessPolicy().name(),
                requirement.minSuccesses());
    }

    private static List<String> selectorFields(ToolRequirement requirement) {
        try {
            JsonNode selector = JSON.readTree(requirement.authoritySelector().canonicalJson());
            List<String> fields = new ArrayList<>();
            switch (requirement.authoritySelector().kind()) {
                case EXACT_PATH -> {
                    fields.add("path=" + safe(selector.path("path").asText()));
                    appendNullableInt(fields, selector, "maxLines");
                    appendBoolean(fields, selector, "includeInternal");
                    appendBoolean(fields, selector, "overwrite");
                }
                case EXACT_GREP -> {
                    fields.add("root=" + safe(selector.path("root").asText()));
                    fields.add("pattern=" + safe(selector.path("pattern").asText()));
                    appendBoolean(fields, selector, "includeInternal");
                }
                case EXACT_COMMAND -> fields.add("command="
                        + safe(selector.path("command").asText()));
                case EXACT_GIT_DIFF -> {
                    fields.add("subcommand=" + safe(selector.path("subcommand").asText()));
                    if (selector.path("args").isArray()) {
                        List<String> arguments = new ArrayList<>();
                        selector.path("args").forEach(value -> arguments.add(safe(value.asText())));
                        fields.add("args=[" + String.join(", ", arguments) + "]");
                    }
                }
                case EXACT_PATH_SET -> {
                    if (!selector.path("paths").isArray()) {
                        throw new IllegalStateException("Plan requirement selector is unavailable");
                    }
                    List<String> paths = new ArrayList<>();
                    selector.path("paths").forEach(value -> paths.add(safe(value.asText())));
                    fields.add("paths=[" + String.join(", ", paths) + "]");
                }
            }
            if (fields.isEmpty()) {
                throw new IllegalStateException("Plan requirement selector is unavailable");
            }
            return List.copyOf(fields);
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Plan requirement selector is unavailable", error);
        }
    }

    private static String displayKind(ToolRequirement requirement) {
        return switch (requirement.authoritySelector().kind()) {
            case EXACT_PATH -> "EXACT_WORKSPACE_PATH";
            case EXACT_PATH_SET -> "EXACT_WORKSPACE_PATH_SET";
            case EXACT_GREP -> "EXACT_GREP";
            case EXACT_COMMAND -> "EXACT_COMMAND";
            case EXACT_GIT_DIFF -> "EXACT_GIT_DIFF";
        };
    }

    private static void appendNullableInt(List<String> fields, JsonNode selector, String name) {
        if (!selector.has(name)) return;
        fields.add(name + "=" + (selector.path(name).isNull() ? "none"
                : selector.path(name).asInt()));
    }

    private static void appendBoolean(List<String> fields, JsonNode selector, String name) {
        if (selector.has(name)) fields.add(name + "=" + selector.path(name).asBoolean());
    }

    private static String safe(String value) {
        return bounded(SANITIZER.sanitize(value == null ? "" : value)
                .replace("\r", "\\r").replace("\n", "\\n"), SUMMARY_CODE_POINTS);
    }

    private static String bounded(String value, int maxCodePoints) {
        int count = value.codePointCount(0, value.length());
        if (count <= maxCodePoints) return value;
        int end = value.offsetByCodePoints(0, Math.max(0, maxCodePoints - 1));
        return value.substring(0, end) + "…";
    }

    private static void requireVerified(Revision revision) {
        if (revision == null || !revision.canonicalHash().equals(PlanCanonicalizer.hash(revision))) {
            throw new IllegalStateException("Plan revision presentation integrity failure");
        }
    }

    private static int orderOf(Revision revision, Dependency dependency) {
        return revision.steps().stream()
                .filter(step -> step.stepRevisionId()
                        .equals(dependency.dependsOnStepRevisionId()))
                .mapToInt(StepDefinition::order).findFirst().orElse(-1);
    }

    private static String value(Enum<?> value) {
        return value == null ? "not specified" : value.name();
    }

    private static void appendField(List<String> lines,String label,String value){
        String safe=value==null?"":value;
        int offset=0;
        int part=0;
        while(offset<safe.length()){
            int remaining=safe.codePointCount(offset,safe.length());
            int count=Math.min(4000,remaining);
            int end=safe.offsetByCodePoints(offset,count);
            lines.add((part==0?label+": ":label+" (continued): ")
                    +safe.substring(offset,end));
            offset=end;part++;
        }
        if(part==0)lines.add(label+":");
    }

}
