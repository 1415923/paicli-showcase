package com.paicli.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.state.PlanContentBounds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** v1.2 content bounds are local fail-closed admission, never schema regeneration. */
class StructuredPlanNoSemanticRegenerationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final StructuredPlanResponseParser parser = new StructuredPlanResponseParser();

    @Test
    void PLAN_ABNORMAL_EXPANSION_IS_TYPED_CONTENT_FAILURE_NOT_SCHEMA_REPAIR() {
        ObjectNode root = oneStepPlan();
        ((ObjectNode) root.withArray("steps").get(0)).put("title",
                "x".repeat(PlanContentBounds.TITLE_MAX_CODE_POINTS + 1));

        StructuredPlanResponseParser.ContentBoundsFailure failure = assertThrows(
                StructuredPlanResponseParser.ContentBoundsFailure.class,
                () -> parser.parse(root.toString(), 65_536));

        assertFalse(StructuredPlanResponseParser.SchemaFailure.class.isInstance(failure));
        assertTrue(failure.getMessage().contains("STRUCTURED_PLAN_CONTENT_BOUNDS_FAILED"));
        assertTrue(failure.errors().stream().anyMatch(
                error -> error.contains("steps[0].title:CODE_POINT_LIMIT_EXCEEDED")));
    }

    @Test
    void PLAN_STEP_COUNT_OVER_SIXTEEN_IS_CONTENT_FAILURE_NOT_SCHEMA_REPAIR() {
        ObjectNode root = oneStepPlan();
        var template = root.withArray("steps").get(0).deepCopy();
        while (root.withArray("steps").size() <= PlanContentBounds.MAX_STEPS) {
            ObjectNode step = template.deepCopy();
            int ordinal = root.withArray("steps").size() + 1;
            ObjectNode requirement = (ObjectNode) step.withArray("toolRequirements").get(0);
            requirement.put("requirementId", "read-target-" + ordinal);
            ((ObjectNode) requirement.path("authoritySelector"))
                    .put("path", "fixtures/item-" + ordinal + ".txt");
            root.withArray("steps").add(step);
        }

        StructuredPlanResponseParser.ContentBoundsFailure failure = assertThrows(
                StructuredPlanResponseParser.ContentBoundsFailure.class,
                () -> parser.parse(root.toString(), 65_536));

        assertTrue(failure.errors().stream().anyMatch(
                error -> error.contains("steps:COUNT_OUT_OF_RANGE")));
    }

    private static ObjectNode oneStepPlan() {
        ObjectNode root = JSON.createObjectNode();
        root.putArray("acceptanceCriteria").add("evidence is recorded");
        root.putArray("assumptions");
        root.putArray("risks");
        root.put("verificationStrategy", "inspect durable evidence");
        ObjectNode step = root.putArray("steps").addObject();
        step.put("title", "Inspect");
        step.put("definition", "Inspect one target");
        step.putArray("dependsOn");
        step.put("completionRule", "ALL_REQUIRED_EVIDENCE");
        step.putArray("requiredEvidenceKinds").add("TOOL_CALL");
        step.put("effectClass", "READ");
        ObjectNode requirement = step.putArray("toolRequirements").addObject();
        requirement.put("requirementId", "read-target-1");
        requirement.put("requirementOrder", 0);
        requirement.put("toolName", "read_file");
        requirement.put("effectClass", "READ");
        ObjectNode selector = requirement.putObject("authoritySelector");
        selector.put("kind", "EXACT_PATH");
        selector.put("path", "fixtures/item-1.txt");
        requirement.put("minSuccesses", 1);
        requirement.put("maxOccurrences", 1);
        requirement.put("freshnessPolicy", "RESUME_REUSE_ONLY");
        return root;
    }
}
