package com.paicli.skill;

import com.paicli.runtime.PersistenceSanitizer;
import com.paicli.state.CodingRunStore;
import com.paicli.state.SkillLoadStore;
import com.paicli.state.StateStore;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillLoaderTest {

    @TempDir
    Path workspace;

    private StateStore stateStore;
    private SkillLoader loader;

    @BeforeEach
    void setUp() throws Exception {
        stateStore = new StateStore(workspace);
        stateStore.tasks.createTask(
                "task-skill", "skill test", "mock", null, "on-request", 10);
        stateStore.codingRuns.create(
                "run-skill", "task-skill", CodingRunStore.KIND_INITIAL,
                null, 1, 0);
        loader = new SkillLoader(
                workspace, stateStore.skillLoads, new PersistenceSanitizer());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (stateStore != null) {
            stateStore.close();
        }
    }

    @Test
    void discoversBothDefaultDirectoriesButNotForgeKitSkills() throws Exception {
        writeSkill("skills/java/SKILL.md", "java-build",
                "Java build support", List.of("java", "maven"),
                List.of("run_command"), 2048, "Run tests carefully.");
        writeSkill(".paicli/skills/docs/SKILL.md", "docs-review",
                "Documentation review", List.of("documentation"),
                List.of(), null, "Review project docs.");
        writeSkill(".agents/skills/governance/SKILL.md", "governance",
                "ForgeKit governance", List.of("forgekit"),
                List.of(), null, "Must not be product-discovered.");

        List<SkillDefinition> skills = loader.discover(
                "task-skill", "run-skill", List.of("skills", ".paicli/skills"));

        assertEquals(List.of("docs-review", "java-build"),
                skills.stream().map(SkillDefinition::name).sorted().toList());
        assertEquals(2, stateStore.skillLoads.findByRun("run-skill").size());
        assertTrue(skills.stream().noneMatch(
                skill -> skill.relativePath().startsWith(".agents/")));
        SkillDefinition java = skills.stream()
                .filter(skill -> skill.name().equals("java-build"))
                .findFirst().orElseThrow();
        assertEquals(List.of("run_command"), java.allowedTools());
        assertEquals(2048, java.maxContextChars());
        assertFalse(java.contentSha256().isBlank());
    }

    @Test
    void rejectsInvalidMetadataDuplicateNamesAndOversizedFiles() throws Exception {
        writeRaw("skills/missing/SKILL.md", """
                ---
                name: missing-description
                triggers: [test]
                ---
                body
                """);
        writeSkill("skills/one/SKILL.md", "duplicate",
                "first", List.of("one"), List.of(), null, "first body");
        writeSkill("skills/two/SKILL.md", "DUPLICATE",
                "second", List.of("two"), List.of(), null, "second body");
        Path oversized = workspace.resolve("skills/large/SKILL.md");
        Files.createDirectories(oversized.getParent());
        Files.write(oversized, new byte[SkillLoader.MAX_SKILL_FILE_BYTES + 1]);

        List<SkillDefinition> skills = loader.discover(
                "task-skill", "run-skill", List.of("skills"));

        assertEquals(1, skills.size());
        assertEquals("duplicate", skills.getFirst().name());
        List<SkillLoadStore.SkillLoadRecord> loads =
                stateStore.skillLoads.findByRun("run-skill");
        assertEquals(4, loads.size());
        assertEquals(3, loads.stream()
                .filter(load -> SkillLoadStore.STATUS_REJECTED.equals(load.status()))
                .count());
        assertTrue(loads.stream().filter(load -> load.errorExcerpt() != null)
                .allMatch(load -> load.errorExcerpt().length() < 2048));
    }

    @Test
    void ignoresNonAuthoritativeMetadataButRejectsDuplicateYamlKeys() throws Exception {
        writeRaw("skills/unknown/SKILL.md", """
                ---
                name: unknown-field
                description: invalid
                triggers: [test]
                command: echo forbidden
                ---
                body
                """);
        writeRaw("skills/duplicate-key/SKILL.md", """
                ---
                name: duplicate-key
                name: overwritten
                description: invalid
                triggers: [test]
                ---
                body
                """);

        assertEquals(List.of("unknown-field"),loader.discover(
                "task-skill", "run-skill", List.of("skills")).stream().map(SkillDefinition::name).toList());
        assertEquals(1, stateStore.skillLoads
                .findByStatus(SkillLoadStore.STATUS_REJECTED).size());
    }

    @Test
    void rejectsConfiguredDirectoryOutsideWorkspace() {
        assertThrows(SecurityException.class, () -> loader.discover(
                "task-skill", "run-skill", List.of("../external-skills")));
    }

    @Test
    void rejectsSymlinkedSkillWhenPlatformSupportsLinks() throws Exception {
        Path realSkill = workspace.resolve("real/SKILL.md");
        Files.createDirectories(realSkill.getParent());
        Files.writeString(realSkill, """
                ---
                name: linked
                description: linked skill
                triggers: [linked]
                ---
                linked body
                """);
        Path link = workspace.resolve("skills/linked");
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, realSkill.getParent());
        } catch (IOException | UnsupportedOperationException
                 | SecurityException e) {
            Assumptions.abort("Symbolic links unavailable: " + e.getMessage());
        }

        assertTrue(loader.discover(
                "task-skill", "run-skill", List.of("skills")).isEmpty());
        assertEquals(SkillLoadStore.STATUS_REJECTED,
                stateStore.skillLoads.findByRun("run-skill")
                        .getFirst().status());
    }

    private void writeSkill(String relativePath, String name,
                            String description, List<String> triggers,
                            List<String> allowedTools, Integer maxContextChars,
                            String body) throws IOException {
        StringBuilder yaml = new StringBuilder()
                .append("---\n")
                .append("name: ").append(name).append('\n')
                .append("description: ").append(description).append('\n')
                .append("triggers:\n");
        for (String trigger : triggers) {
            yaml.append("  - ").append(trigger).append('\n');
        }
        if (!allowedTools.isEmpty()) {
            yaml.append("allowed_tools:\n");
            for (String tool : allowedTools) {
                yaml.append("  - ").append(tool).append('\n');
            }
        }
        if (maxContextChars != null) {
            yaml.append("max_context_chars: ").append(maxContextChars).append('\n');
        }
        yaml.append("---\n").append(body);
        writeRaw(relativePath, yaml.toString());
    }

    private void writeRaw(String relativePath, String content) throws IOException {
        Path path = workspace.resolve(relativePath);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
