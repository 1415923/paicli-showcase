package com.paicli.runtime;

import com.paicli.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RuntimeManifestHasherTest {

    @TempDir
    Path workspace;

    @Test
    void hashesAreStableAndChangeWithSkillOrHookInputs() throws Exception {
        Path skill = workspace.resolve("skills/java/SKILL.md");
        Files.createDirectories(skill.getParent());
        Files.writeString(skill, "skill-one");
        Path hooks = workspace.resolve("custom/hooks.json");
        Files.createDirectories(hooks.getParent());
        Files.writeString(hooks, "{\"version\":1,\"hooks\":[]}");
        Config config = config(true, true, "custom/hooks.json");
        RuntimeManifestHasher hasher = new RuntimeManifestHasher(workspace);

        RuntimeManifest first = hasher.compute(config);
        assertEquals(first, hasher.compute(config));
        Files.writeString(skill, "skill-two");
        RuntimeManifest skillChanged = hasher.compute(config);
        assertNotEquals(first.skillsSha256(), skillChanged.skillsSha256());
        assertEquals(first.hooksSha256(), skillChanged.hooksSha256());
        Files.writeString(hooks, "{\"version\":1,\"hooks\":[],\"note\":true}");
        RuntimeManifest hookChanged = hasher.compute(config);
        assertNotEquals(skillChanged.hooksSha256(), hookChanged.hooksSha256());
    }

    @Test
    void disabledRuntimesUseStableSentinelHashesWithoutReadingFiles() throws Exception {
        RuntimeManifest manifest = new RuntimeManifestHasher(workspace)
                .compute(config(false, false, "missing/hooks.json"));
        assertNotNull(manifest.skillsSha256());
        assertNotNull(manifest.hooksSha256());
        assertEquals(manifest, new RuntimeManifestHasher(workspace)
                .compute(config(false, false, "missing/hooks.json")));
    }

    private Config config(boolean skills, boolean hooks, String hooksPath) {
        return new Config(
                workspace.toString(), 30, 50_000, 200_000,
                "on-request", 10_000, "mock", null, null, null, null, 3,
                "", 5, 4096, 1, true, true, true,
                skills, List.of("skills", ".paicli/skills"), 1024, 3,
                hooks, hooksPath, 30, 4096, "warn");
    }
}
