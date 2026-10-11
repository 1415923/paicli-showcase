package com.paicli.state;

import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.state.PlanCompletionModels.CompletionRuleType;
import com.paicli.state.PlanCompletionModels.EffectClass;
import com.paicli.state.PlanCompletionModels.EvidenceKind;
import com.paicli.state.StructuredPlanModels.Revision;
import com.paicli.state.StructuredPlanModels.StepDefinition;
import com.paicli.support.R3ContextTestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanAuthorityBoundsIntegrationTest {
    @TempDir Path workspace;

    @Test
    void PLAN_ABNORMAL_EXPANSION_FAILS_CLOSED_BEFORE_APPROVAL_AUTHORITY_OR_TOOL() throws Exception {
        try (StateStore state = new StateStore(workspace)) {
            var admitted = R3ContextTestFixtures.admit(state, workspace);
            var snapshot = admitted.guard().current().snapshot();
            Revision invalid = revision(snapshot.taskId(), snapshot.runId(), List.of(
                    step(0, "x".repeat(PlanContentBounds.TITLE_MAX_CODE_POINTS + 1),
                            "definition")));

            assertEquals(PlanAuthorityStore.Status.STRUCTURE_INVALID,
                    state.planAuthorities.propose(snapshot, Instant.now(), invalid).status());
            assertEquals(0, count(state, "plan_revisions"));
            assertEquals(0, count(state, "plan_step_definitions"));
            assertEquals(0, count(state, "plan_approvals"));
            assertEquals(0, count(state, "task_plan_authorities"));
            assertEquals(0, count(state, "plan_run_bindings"));
            assertEquals(0, count(state, "plan_step_executions"));
            assertEquals(0, count(state, "plan_evidence"));
            assertEquals(0, countWhere(state, "side_effect_occurrences",
                    "operation_type='TOOL'"));
        }
    }

    @Test
    void PLAN_CANONICAL_CONTENT_PRESERVED_AND_AUTHORITY_HASH_USES_FULL_REVISION()
            throws Exception {
        try (StateStore state = new StateStore(workspace)) {
            var admitted = R3ContextTestFixtures.admit(state, workspace);
            var snapshot = admitted.guard().current().snapshot();
            List<StepDefinition> steps = new ArrayList<>();
            for (int index = 0; index < 7; index++) {
                steps.add(step(index, "步骤" + (index + 1),
                        "完整说明" + index + "：读取目标、保持约束并记录真实证据。".repeat(5)));
            }
            Revision revision = revision(snapshot.taskId(), snapshot.runId(), steps);
            String expectedHash = revision.canonicalHash();
            String expectedLastDefinition = revision.steps().getLast().definition();
            Instant now = Instant.now();

            assertEquals(PlanAuthorityStore.Status.SUCCESS,
                    state.planAuthorities.propose(snapshot, now, revision).status());
            assertEquals(PlanAuthorityStore.Status.SUCCESS,
                    state.planAuthorities.validateRevision(snapshot, now.plusSeconds(1),
                            revision.planRevisionId(), 0).status());
            assertEquals(PlanAuthorityStore.Status.SUCCESS,
                    state.planAuthorities.approveAndActivate(snapshot, now.plusSeconds(2),
                            revision.planRevisionId(), 1, 0, "approval-plan-bounds", "user",
                            "plan-bounds-test", "approved full canonical revision").status());

            var active = state.planAuthorities.readActive(snapshot.taskId());
            assertEquals(PlanAuthorityStore.Status.SUCCESS, active.status());
            assertEquals(expectedHash, active.plan().revision().canonicalHash());
            assertEquals(expectedHash, PlanCanonicalizer.hash(active.plan().revision()));
            assertEquals(expectedLastDefinition,
                    active.plan().revision().steps().getLast().definition());
            assertEquals(7, active.plan().revision().steps().size());
            assertEquals(1, count(state, "plan_approvals"));
            assertEquals(1, count(state, "task_plan_authorities"));
            assertEquals(0, countWhere(state, "side_effect_occurrences",
                    "operation_type='TOOL'"));
            assertEquals(0, count(state, "plan_evidence"));
        }
    }

    private static Revision revision(CanonicalId task, CanonicalId run,
            List<StepDefinition> steps) {
        CanonicalId plan = CanonicalId.newId(EntityFamily.PLAN);
        Instant at = Instant.parse("2026-08-02T04:00:00Z");
        Revision draft = new Revision(plan, task, 1, "保持完整的用户目标",
                "七个步骤均有持久证据", "不修改禁止项", "避免重复工具调用",
                "核对 durable evidence", "0".repeat(64), "MODEL", "test", run, at,
                steps, List.of(), List.of(), Revision.CANONICAL_SCHEMA_V3);
        return new Revision(plan, task, 1, draft.goal(), draft.acceptanceCriteria(),
                draft.assumptions(), draft.risks(), draft.verificationStrategy(),
                PlanCanonicalizer.hash(draft), draft.sourceType(), draft.provenance(), run, at,
                steps, List.of(), List.of(), Revision.CANONICAL_SCHEMA_V3);
    }

    private static StepDefinition step(int order, String title, String definition) {
        return new StepDefinition(CanonicalId.newId(EntityFamily.PLAN_STEP),
                CanonicalId.newId(EntityFamily.PLAN_STEP), order, title, definition,
                CompletionRuleType.ALL_REQUIRED_EVIDENCE, List.of(EvidenceKind.TOOL_CALL),
                EffectClass.READ, List.of(exactReadRequirement(order)), "a".repeat(64));
    }

    private static StructuredPlanModels.ToolRequirement exactReadRequirement(int order) {
        String path = "fixtures/item-" + (order + 1) + ".txt";
        String json = "{\"path\":\"" + path + "\",\"maxLines\":null,"
                + "\"includeInternal\":false}";
        var selector = new StructuredPlanModels.ToolAuthoritySelector(
                StructuredPlanModels.AuthoritySelectorKind.EXACT_PATH, json,
                PlanToolRequirementPolicy.digest(json));
        return new StructuredPlanModels.ToolRequirement("read-item-" + (order + 1), 0,
                "read_file", EffectClass.READ, selector, 1, 1,
                StructuredPlanModels.FreshnessPolicy.RESUME_REUSE_ONLY);
    }

    private static long count(StateStore state, String table) throws Exception {
        try (var statement = connection(state).createStatement();
             var rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static long countWhere(StateStore state, String table, String where)
            throws Exception {
        try (var statement = connection(state).createStatement();
             var rows = statement.executeQuery(
                     "SELECT count(*) FROM " + table + " WHERE " + where)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static java.sql.Connection connection(StateStore state) throws Exception {
        var field = StateStore.class.getDeclaredField("databaseManager");
        field.setAccessible(true);
        return ((DatabaseManager) field.get(state)).getConnection();
    }
}
