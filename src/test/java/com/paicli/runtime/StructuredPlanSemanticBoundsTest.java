package com.paicli.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class StructuredPlanSemanticBoundsTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final StructuredPlanResponseParser parser = new StructuredPlanResponseParser();

    @Test
    void PLAN_NORMAL_MULTI_STEP_ACCEPTED_FOR_SEVEN_STEP_CHINESE_PLAN_OVER_256() {
        String plan = planJson(7, "短标题",
                "读取目标文件，核对约束，记录实际证据，并保持用户禁止项不变。".repeat(3));
        assertTrue(plan.codePointCount(0, plan.length()) > 256);
        assertEquals(7, parser.parse(plan, 64_000).path("steps").size());
    }

    @Test
    void PLAN_DEEPSEEK_FIVE_STEP_EQUIVALENT_WITH_LONG_LAST_DESCRIPTION_IS_ACCEPTED() {
        ObjectNode root = planNode(5, "Read fixture",
                "Read exactly one fixture and report its marker.");
        ((ObjectNode) root.withArray("steps").get(4))
                .put("definition", "d".repeat(282));
        JsonNode parsed = parser.parse(root.toString(), 64_000);
        assertEquals(5, parsed.path("steps").size());
        assertEquals(282, parsed.path("steps").get(4).path("definition")
                .asText().codePointCount(0, 282));
    }

    @Test
    void PLAN_UNICODE_BOUNDARY_PRESERVES_EMOJI_WITHOUT_SPLITTING_SURROGATES() {
        String title = "🧪".repeat(256);
        ObjectNode root = planNode(1, title, "definition");
        JsonNode parsed = parser.parse(root.toString(), 64_000);
        assertEquals(title, parsed.path("steps").get(0).path("title").asText());
        assertEquals(256, title.codePointCount(0, title.length()));
        assertEquals(1024, title.getBytes(StandardCharsets.UTF_8).length);

        ((ObjectNode) root.withArray("steps").get(0)).put("title", title + "x");
        assertThrows(StructuredPlanResponseParser.ContentBoundsFailure.class,
                () -> parser.parse(root.toString(), 64_000));
    }

    @Test
    void PLAN_STEP_COUNT_OVER_SIXTEEN_FAILS_SCHEMA_ADMISSION() {
        StructuredPlanResponseParser.ContentBoundsFailure failure = assertThrows(
                StructuredPlanResponseParser.ContentBoundsFailure.class,
                () -> parser.parse(planJson(17, "title", "definition"), 64_000));
        assertFalse(failure.errors().isEmpty());
    }

    @Test
    void PLAN_RAW_RESPONSE_UTF8_BYTE_BOUND_IS_EXACT_AND_BODY_IS_NOT_DISCLOSED() {
        String marker = "private-plan-response-marker";
        String plan = planJson(1, "title", "definition " + marker + " 🧪");
        int bytes = plan.getBytes(StandardCharsets.UTF_8).length;
        assertDoesNotThrow(() -> parser.parse(plan, bytes));
        StructuredPlanResponseParser.ParseFailure failure = assertThrows(
                StructuredPlanResponseParser.ParseFailure.class,
                () -> parser.parse(plan, bytes - 1));
        assertTrue(failure.getMessage().contains("OUTPUT_LIMIT_EXCEEDED"));
        assertFalse(failure.getMessage().contains(marker));
    }

    private static String planJson(int stepCount, String title, String definition) {
        return planNode(stepCount, title, definition).toString();
    }

    private static ObjectNode planNode(int stepCount, String title, String definition) {
        ObjectNode root = JSON.createObjectNode();
        root.putArray("acceptanceCriteria").add("evidence is recorded");
        root.putArray("assumptions");
        root.putArray("risks");
        root.put("verificationStrategy", "inspect durable evidence");
        ArrayNode steps = root.putArray("steps");
        for (int index = 0; index < stepCount; index++) {
            ObjectNode step = steps.addObject();
            step.put("title", title + (stepCount == 1 ? "" : " " + (index + 1)));
            step.put("definition", definition);
            ArrayNode dependencies = step.putArray("dependsOn");
            if (index > 0) dependencies.add(index - 1);
            step.put("completionRule", "ALL_REQUIRED_EVIDENCE");
            step.putArray("requiredEvidenceKinds").add("TOOL_CALL");
            step.put("effectClass", "READ");
            ObjectNode requirement = step.putArray("toolRequirements").addObject();
            requirement.put("requirementId", "read-target-" + (index + 1));
            requirement.put("requirementOrder", 0);
            requirement.put("toolName", "read_file");
            requirement.put("effectClass", "READ");
            ObjectNode selector = requirement.putObject("authoritySelector");
            selector.put("kind", "EXACT_PATH");
            selector.put("path", "fixtures/item-" + (index + 1) + ".txt");
            requirement.put("minSuccesses", 1);
            requirement.put("maxOccurrences", 1);
            requirement.put("freshnessPolicy", "RESUME_REUSE_ONLY");
        }
        return root;
    }
}
