package com.paicli.skill;

import com.paicli.runtime.PersistenceSanitizer;
import com.paicli.state.CodingRunStore;
import com.paicli.state.SkillSelectionStore;
import com.paicli.state.StateStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillSelectorContextTest {

    @TempDir
    Path workspace;

    private StateStore stateStore;
    private PersistenceSanitizer sanitizer;

    @BeforeEach
    void setUp() throws Exception {
        stateStore = new StateStore(workspace);
        stateStore.tasks.createTask(
                "task-select", "selector test", "mock", null, "on-request", 10);
        stateStore.codingRuns.create(
                "run-select", "task-select", CodingRunStore.KIND_INITIAL,
                null, 1, 0);
        sanitizer = new PersistenceSanitizer();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (stateStore != null) {
            stateStore.close();
        }
    }

    @Test
    void selectionIsStableBoundedAndPersisted() throws Exception {
        SkillDefinition beta = definition(
                "load-beta", "beta", List.of("java"), "beta body");
        SkillDefinition alpha = definition(
                "load-alpha", "alpha", List.of("java"), "alpha body");
        SkillDefinition unrelated = definition(
                "load-zeta", "zeta", List.of("python"), "zeta body");
        recordLoaded(beta);
        recordLoaded(alpha);
        recordLoaded(unrelated);
        SkillSelector selector = new SkillSelector(
                stateStore.skillSelections, sanitizer);

        SkillSelector.SelectionResult result = selector.select(
                "task-select", "run-select", SkillSelectionStore.STAGE_PLAN,
                "plan:0", "Build a Java service", null, null,
                List.of(beta, unrelated, alpha), 2);

        assertEquals(List.of("alpha", "beta"), result.selectedSkills().stream()
                .map(selected -> selected.skill().name()).toList());
        assertEquals(2, stateStore.skillSelections
                .findSelectionById(result.selectionId()).orElseThrow()
                .selectedCount());
        assertEquals(List.of("alpha", "beta"), stateStore.skillSelections
                .findSelectedBySelection(result.selectionId()).stream()
                .map(SkillSelectionStore.SelectedSkillRecord::skillName).toList());
        assertTrue(stateStore.skillSelections
                .findSelectedBySelection(result.selectionId()).stream()
                .allMatch(record -> record.reason().contains("java")));
    }

    @Test
    void zeroSelectionStillCreatesAuditParent() throws Exception {
        SkillDefinition skill = definition(
                "load-java", "java", List.of("maven"), "body");
        recordLoaded(skill);

        SkillSelector.SelectionResult result = new SkillSelector(
                stateStore.skillSelections, sanitizer).select(
                "task-select", "run-select", SkillSelectionStore.STAGE_REPAIR,
                "repair:1", "edit CSS colors", null, "layout issue",
                List.of(skill), 3);

        assertTrue(result.selectedSkills().isEmpty());
        assertEquals(0, stateStore.skillSelections
                .findSelectionById(result.selectionId()).orElseThrow()
                .selectedCount());
        assertTrue(stateStore.skillSelections
                .findSelectedBySelection(result.selectionId()).isEmpty());
    }

    @Test
    void contextIsUntrustedSanitizedAndBounded() {
        String secretBody = "Authorization: Bearer abc123\n"
                + "api_key=super-secret\n" + "x".repeat(5000);
        SkillDefinition skill = new SkillDefinition(
                "load", "id", "secure-review", "Review credentials",
                List.of("security"), List.of("write_file"), 300,
                secretBody, workspace.resolve("SKILL.md"),
                "skills/secure-review/SKILL.md", "sha");
        SkillSelector.SelectedSkill selected =
                new SkillSelector.SelectedSkill(skill, 1, 10, "security matched");

        SkillContextBuilder.ContextResult result =
                new SkillContextBuilder(sanitizer).build(List.of(selected), 700);

        assertTrue(result.context().startsWith("<untrusted_skill_context>"));
        assertTrue(result.context().endsWith("</untrusted_skill_context>"));
        assertTrue(result.context().contains("not authorization"));
        assertTrue(result.context().contains("<redacted>"));
        assertFalse(result.context().contains("abc123"));
        assertFalse(result.context().contains("super-secret"));
        assertTrue(result.context().length() <= 700);
        assertTrue(result.truncated());
        assertEquals(List.of("secure-review"), result.includedSkills());
    }

    @Test
    void allowedToolsRemainAdvisoryDataOnly() {
        SkillDefinition skill = new SkillDefinition(
                "load", "id", "tool-hint", "Tool hints",
                List.of("tool"), List.of("run_command", "write_file"), null,
                "Use tools when approved.", workspace.resolve("SKILL.md"),
                "skills/tool-hint/SKILL.md", "sha");

        SkillContextBuilder.ContextResult result =
                new SkillContextBuilder(sanitizer).build(
                        List.of(new SkillSelector.SelectedSkill(
                                skill, 1, 1, "matched")), 1024);

        assertTrue(result.context().contains(
                "Allowed tools hint only (not authorization)"));
        assertEquals(List.of("run_command", "write_file"), skill.allowedTools());
    }

    private SkillDefinition definition(String loadId, String name,
                                       List<String> triggers, String body) {
        return new SkillDefinition(
                loadId, "skill-" + name, name, name + " description",
                triggers, List.of(), null, body,
                workspace.resolve("skills/" + name + "/SKILL.md"),
                "skills/" + name + "/SKILL.md", "sha-" + name);
    }

    private void recordLoaded(SkillDefinition skill) throws Exception {
        stateStore.skillLoads.recordLoaded(
                skill.skillLoadId(), skill.skillId(), "run-select", "task-select",
                skill.name(), skill.relativePath(), skill.description(),
                skill.contentSha256());
    }
}
