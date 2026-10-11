package com.paicli.skill;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Parsed, non-executable skill content loaded from a workspace SKILL.md. */
public record SkillDefinition(
        String skillLoadId,
        String skillId,
        String name,
        String description,
        List<String> triggers,
        List<String> allowedTools,
        Integer maxContextChars,
        String content,
        Path path,
        String relativePath,
        String contentSha256
) {
    public SkillDefinition {
        Objects.requireNonNull(skillLoadId, "skillLoadId");
        Objects.requireNonNull(skillId, "skillId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(triggers, "triggers");
        Objects.requireNonNull(allowedTools, "allowedTools");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(relativePath, "relativePath");
        Objects.requireNonNull(contentSha256, "contentSha256");
        triggers = List.copyOf(triggers);
        allowedTools = List.copyOf(allowedTools);
    }
}
