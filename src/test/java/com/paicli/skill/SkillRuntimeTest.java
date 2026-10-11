package com.paicli.skill;

import com.paicli.config.Config;
import com.paicli.runtime.PersistenceSanitizer;
import com.paicli.state.CodingRunStore;
import com.paicli.state.SkillSelectionStore;
import com.paicli.state.StateStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillRuntimeTest {

    @TempDir
    Path workspace;

    @Test
    void selectionIsBoundedSanitizedAuditedAndIdempotentAcrossRuntimeInstances()
            throws Exception {
        writeSkill("skills/alpha/SKILL.md", "alpha", "Java alpha support",
                "java", "run_command", "Authorization: Bearer skill-secret\n" + "x".repeat(2000));
        writeSkill("skills/beta/SKILL.md", "beta", "Java beta support",
                "java", "write_file", "beta body");
        try (StateStore state = preparedState()) {
            Config config = config(1, 1024);
            SkillRuntime first = runtime(state, config);
            SkillRuntime.SkillContext initial = first.select(
                    "task", "run", SkillSelectionStore.STAGE_PLAN, "plan",
                    "implement java with $alpha", null, null);
            SkillRuntime resumed = runtime(state, config);
            SkillRuntime.SkillContext recovered = resumed.select(
                    "task", "run", SkillSelectionStore.STAGE_PLAN, "plan",
                    "implement java with $alpha", null, null);

            assertEquals(initial.selectionId(), recovered.selectionId());
            assertEquals(List.of("alpha"), initial.selectedSkillNames());
            assertTrue(initial.context().length() <= 1024);
            assertFalse(initial.context().contains("skill-secret"));
            assertTrue(initial.context().contains("<redacted>"));
            assertTrue(initial.context().contains("hint only (not authorization)"));
            assertEquals(1, state.skillSelections.findSelectionsByRun("run").size());
            assertEquals(1, state.skillSelections
                    .findSelectedByRun("run").size());
            assertEquals(2, state.skillLoads.findByRun("run").size());
        }
    }

    @Test
    void zeroSelectionStillWritesAudit() throws Exception {
        writeSkill("skills/docs/SKILL.md", "docs", "Documentation only",
                "documentation", "", "review prose");
        try (StateStore state = preparedState()) {
            SkillRuntime.SkillContext context = runtime(state, config(3, 1024)).select(
                    "task", "run", SkillSelectionStore.STAGE_EXECUTE, "execute:0",
                    "compile java bytecode", "maven build", null);

            assertTrue(context.selectedSkillNames().isEmpty());
            assertTrue(context.context().contains("docs: Documentation only"));
            assertFalse(context.context().contains("review prose"));
            SkillSelectionStore.SkillSelectionRecord audit = state.skillSelections
                    .findSelectionsByRun("run").getFirst();
            assertEquals(0, audit.selectedCount());
            assertEquals(SkillSelectionStore.STATUS_COMPLETED, audit.status());
        }
    }

    private SkillRuntime runtime(StateStore state, Config config) throws Exception {
        PersistenceSanitizer sanitizer = new PersistenceSanitizer();
        return new SkillRuntime(
                new SkillLoader(workspace, state.skillLoads, sanitizer),
                new SkillSelector(state.skillSelections, sanitizer),
                new SkillContextBuilder(sanitizer),
                state.skillSelections,
                config);
    }

    private StateStore preparedState() throws Exception {
        StateStore state = new StateStore(workspace);
        state.tasks.createTask("task", "goal", "mock", null, "on-request", 10);
        state.codingRuns.create("run", "task", CodingRunStore.KIND_INITIAL, "", 1, 0);
        return state;
    }

    private Config config(int maxSelected, int maxContext) {
        return new Config(
                workspace.toString(), 30, 50_000, 200_000, "on-request", 10_000,
                "mock", null, null, null, null, 3,
                "", 5, 4096, 1, true, true, true,
                true, List.of("skills", ".paicli/skills"), maxContext, maxSelected);
    }

    private void writeSkill(String relativePath, String name, String description,
                            String trigger, String allowedTool, String body) throws Exception {
        Path path = workspace.resolve(relativePath);
        Files.createDirectories(path.getParent());
        String allowed = allowedTool.isBlank() ? "" : "allowed_tools: [" + allowedTool + "]\n";
        Files.writeString(path, "---\nname: " + name + "\ndescription: " + description
                + "\ntriggers: [" + trigger + "]\n" + allowed + "---\n" + body);
    }
}
