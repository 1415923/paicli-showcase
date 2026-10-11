package com.paicli.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.state.PlanCompletionModels.CompletionRuleType;
import com.paicli.state.PlanCompletionModels.EffectClass;
import com.paicli.state.PlanCompletionModels.EvidenceKind;
import com.paicli.state.StructuredPlanModels.Revision;
import com.paicli.state.StructuredPlanModels.StepDefinition;
import com.paicli.state.StructuredPlanModels.FreshnessPolicy;
import com.paicli.state.StructuredPlanModels.ToolRequirement;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanContentBoundsTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void PLAN_BOUND_CONSTANTS_ARE_SEMANTIC_NOT_DISPLAY_LIMITS() {
        assertEquals(1, PlanContentBounds.MIN_STEPS);
        assertEquals(16, PlanContentBounds.MAX_STEPS);
        assertEquals(256, PlanContentBounds.TITLE_MAX_CODE_POINTS);
        assertEquals(1024, PlanContentBounds.TITLE_MAX_UTF8_BYTES);
        assertEquals(2048, PlanContentBounds.DEFINITION_MAX_CODE_POINTS);
        assertEquals(8192, PlanContentBounds.DEFINITION_MAX_UTF8_BYTES);
        assertEquals(2048, PlanContentBounds.GLOBAL_FIELD_MAX_CODE_POINTS);
        assertEquals(8192, PlanContentBounds.GLOBAL_FIELD_MAX_UTF8_BYTES);
        assertEquals(16384, PlanContentBounds.AGGREGATE_MAX_CODE_POINTS);
        assertEquals(65536, PlanContentBounds.AGGREGATE_MAX_UTF8_BYTES);
    }

    @Test
    void PLAN_NORMAL_MULTI_STEP_ACCEPTED_FOR_SEVEN_STEP_CHINESE_PLAN_OVER_256() {
        List<StepDefinition> steps = new ArrayList<>();
        for (int index = 0; index < 7; index++) {
            steps.add(step(index, "步骤" + (index + 1),
                    "读取目标文件，核对当前状态与约束，保留已有行为，并记录可验证的完成条件。".repeat(3)));
        }
        Revision revision = revision(steps, "验收标准完整", "不修改控制目录", "避免重复工具调用", "依据持久证据验证");

        int aggregate = steps.stream().mapToInt(step ->
                codePoints(step.title()) + codePoints(step.definition())).sum()
                + codePoints(revision.acceptanceCriteria())
                + codePoints(revision.assumptions())
                + codePoints(revision.risks())
                + codePoints(revision.verificationStrategy());
        assertTrue(aggregate > 256);
        assertTrue(PlanContentBounds.validate(revision).valid(),
                () -> PlanContentBounds.validate(revision).errors().toString());
        assertDoesNotThrow(() -> PlanContentBounds.requireValid(revision));
    }

    @Test
    void PLAN_STEP_TITLE_BOUND_SEPARATE_AND_UNICODE_CODE_POINT_BASED() {
        String exactEmojiTitle = "🧪".repeat(PlanContentBounds.TITLE_MAX_CODE_POINTS);
        Revision exact = revision(List.of(step(0, exactEmojiTitle, "definition")), "", "", "", "");
        assertEquals(PlanContentBounds.TITLE_MAX_CODE_POINTS,
                exactEmojiTitle.codePointCount(0, exactEmojiTitle.length()));
        assertEquals(PlanContentBounds.TITLE_MAX_UTF8_BYTES,
                exactEmojiTitle.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertTrue(PlanContentBounds.validate(exact).valid());

        Revision above = revision(List.of(step(0, exactEmojiTitle + "x", "definition")),
                "", "", "", "");
        assertFalse(PlanContentBounds.validate(above).valid());
        assertThrows(PlanContentBounds.Violation.class,
                () -> PlanContentBounds.requireValid(above));
    }

    @Test
    void PLAN_DESCRIPTION_BOUND_SEPARATE_AND_UTF8_BOUNDARY_ACCEPTED() {
        String exact = "🧪".repeat(PlanContentBounds.DEFINITION_MAX_CODE_POINTS);
        Revision accepted = revision(List.of(step(0, "title", exact)), "", "", "", "");
        assertEquals(PlanContentBounds.DEFINITION_MAX_UTF8_BYTES,
                exact.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertTrue(PlanContentBounds.validate(accepted).valid());

        Revision rejected = revision(List.of(step(0, "title", exact + "x")), "", "", "", "");
        assertFalse(PlanContentBounds.validate(rejected).valid());
    }

    @Test
    void PLAN_AGGREGATE_BOUND_SEPARATE_FROM_PER_FIELD_BOUNDS() {
        List<StepDefinition> exact = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            exact.add(step(index, "t", "d".repeat(2047)));
        }
        Revision atBoundary = revision(exact, "", "", "", "");
        assertTrue(PlanContentBounds.validate(atBoundary).valid(),
                () -> PlanContentBounds.validate(atBoundary).errors().toString());

        List<StepDefinition> above = new ArrayList<>(exact);
        above.set(7, step(7, "t", "d".repeat(2048)));
        Revision overBoundary = revision(above, "", "", "", "");
        assertFalse(PlanContentBounds.validate(overBoundary).valid());
    }

    @Test
    void PLAN_GLOBAL_FIELD_BOUND_IS_INDEPENDENT_OF_USER_GOAL() {
        Revision accepted = revision(List.of(step(0, "title", "definition")),
                "a".repeat(PlanContentBounds.GLOBAL_FIELD_MAX_CODE_POINTS), "", "", "");
        assertTrue(PlanContentBounds.validate(accepted).valid());

        Revision rejected = revision(List.of(step(0, "title", "definition")),
                "a".repeat(PlanContentBounds.GLOBAL_FIELD_MAX_CODE_POINTS + 1), "", "", "");
        assertFalse(PlanContentBounds.validate(rejected).valid());
    }

    @Test
    void PLAN_STEP_COUNT_ACCEPTS_ALL_SIXTEEN_AND_REJECTS_SEVENTEEN() {
        List<StepDefinition> sixteen = new ArrayList<>();
        for (int index = 0; index < 16; index++) {
            sixteen.add(step(index, "step " + (index + 1), "definition " + (index + 1)));
        }
        assertTrue(PlanContentBounds.validate(revision(sixteen, "", "", "", "")).valid());

        List<StepDefinition> seventeen = new ArrayList<>(sixteen);
        seventeen.add(step(16, "step 17", "definition 17"));
        assertFalse(PlanContentBounds.validate(revision(seventeen, "", "", "", "")).valid());
    }

    @Test
    void PLAN_V3_NORMAL_FIVE_AND_SEVEN_STEP_EXACT_PATH_REQUIREMENTS_ARE_ACCEPTED()
            throws Exception {
        for (int count : List.of(5, 7)) {
            List<StepDefinition> steps = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                var selector = PlanToolRequirementPolicy.normalize("read_file", JSON.readTree(
                        "{\"kind\":\"EXACT_PATH\",\"path\":\"fixtures/item-"
                                + String.format("%02d", index + 1) + ".txt\"}"));
                ToolRequirement requirement = new ToolRequirement("read-item-" + (index + 1),
                        0, "read_file", EffectClass.READ, selector, 1, 1,
                        FreshnessPolicy.RESUME_REUSE_ONLY);
                steps.add(v3Step(index, "Read item " + (index + 1),
                        "Read the exact planned fixture", EffectClass.READ,
                        List.of(requirement)));
            }
            Revision revision = v3Revision(steps);
            assertTrue(PlanContentBounds.validate(revision).valid(),
                    () -> PlanContentBounds.validate(revision).errors().toString());
        }
    }

    @Test
    void PLAN_V3_REQUIREMENT_MATERIAL_COUNTS_TOWARD_16K_AND_64K_AGGREGATES()
            throws Exception {
        List<ToolRequirement> requirements = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            String command = "echo " + "🧪".repeat(3_000) + index;
            var selector = PlanToolRequirementPolicy.normalize("run_command", JSON.readTree(
                    "{\"kind\":\"EXACT_COMMAND\",\"command\":"
                            + JSON.writeValueAsString(command) + "}"));
            requirements.add(new ToolRequirement("large-command-" + index, index,
                    "run_command", EffectClass.GENERAL, selector, 1, 1,
                    FreshnessPolicy.RESUME_REUSE_ONLY));
        }
        Revision oversized = v3Revision(List.of(v3Step(0, "Run checks",
                "Execute exact bounded commands", EffectClass.GENERAL, requirements)));

        PlanContentBounds.Validation validation = PlanContentBounds.validate(oversized);
        assertFalse(validation.valid());
        assertTrue(validation.errors().contains("canonical:CODE_POINT_LIMIT_EXCEEDED"));
        assertTrue(validation.errors().contains("canonical:UTF8_BYTE_LIMIT_EXCEEDED"));
        assertThrows(PlanContentBounds.Violation.class,
                () -> PlanContentBounds.requireValid(oversized));
    }

    private static StepDefinition step(int order, String title, String definition) {
        return new StepDefinition(CanonicalId.newId(EntityFamily.PLAN_STEP),
                CanonicalId.newId(EntityFamily.PLAN_STEP), order, title, definition,
                CompletionRuleType.ALL_REQUIRED_EVIDENCE, List.of(EvidenceKind.TOOL_CALL),
                EffectClass.READ, "a".repeat(64));
    }

    private static Revision revision(List<StepDefinition> steps, String acceptance,
            String assumptions, String risks, String verification) {
        CanonicalId plan = CanonicalId.newId(EntityFamily.PLAN);
        CanonicalId task = CanonicalId.newId(EntityFamily.TASK);
        CanonicalId run = CanonicalId.newId(EntityFamily.RUN);
        Revision draft = new Revision(plan, task, 1,
                "用户目标保持完整且不计入模型生成 Plan aggregate", acceptance,
                assumptions, risks, verification, "0".repeat(64), "MODEL", "test",
                run, Instant.parse("2026-08-02T04:00:00Z"), steps, List.of(), List.of());
        return new Revision(plan, task, 1, draft.goal(), draft.acceptanceCriteria(),
                draft.assumptions(), draft.risks(), draft.verificationStrategy(),
                PlanCanonicalizer.hash(draft), draft.sourceType(), draft.provenance(), run,
                draft.createdAt(), steps, List.of(), List.of());
    }

    private static StepDefinition v3Step(int order, String title, String definition,
            EffectClass effect, List<ToolRequirement> requirements) {
        return new StepDefinition(CanonicalId.newId(EntityFamily.PLAN_STEP),
                CanonicalId.newId(EntityFamily.PLAN_STEP), order, title, definition,
                CompletionRuleType.ALL_REQUIRED_EVIDENCE, List.of(EvidenceKind.TOOL_CALL),
                effect, requirements, "a".repeat(64));
    }

    private static Revision v3Revision(List<StepDefinition> steps) {
        CanonicalId plan = CanonicalId.newId(EntityFamily.PLAN);
        CanonicalId task = CanonicalId.newId(EntityFamily.TASK);
        CanonicalId run = CanonicalId.newId(EntityFamily.RUN);
        Revision draft = new Revision(plan, task, 1, "goal", "accept", "", "", "verify",
                "0".repeat(64), "MODEL", "test", run,
                Instant.parse("2026-08-02T04:00:00Z"), steps, List.of(), List.of(),
                Revision.CANONICAL_SCHEMA_V3);
        return new Revision(plan, task, 1, draft.goal(), draft.acceptanceCriteria(),
                draft.assumptions(), draft.risks(), draft.verificationStrategy(),
                PlanCanonicalizer.hash(draft), draft.sourceType(), draft.provenance(), run,
                draft.createdAt(), steps, List.of(), List.of(), Revision.CANONICAL_SCHEMA_V3);
    }

    private static int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }
}
