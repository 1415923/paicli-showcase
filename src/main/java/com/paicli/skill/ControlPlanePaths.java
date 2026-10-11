package com.paicli.skill;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Identifies workspace files that configure Skills or Hooks.
 * Enforcement remains opt-in and is applied by WriteFileTool when this detector is injected.
 */
public final class ControlPlanePaths {

    public static final String DEFAULT_HOOKS_CONFIG = ".paicli/hooks.json";

    private final Path workspace;
    private final List<Path> skillRoots;
    private final Set<Path> hookConfigs;

    public ControlPlanePaths(Path workspace, List<String> skillDirs) throws IOException {
        this(workspace, skillDirs, List.of(DEFAULT_HOOKS_CONFIG));
    }

    public ControlPlanePaths(Path workspace, List<String> skillDirs,
                             List<String> hooksConfigPaths) throws IOException {
        Objects.requireNonNull(workspace, "workspace");
        Objects.requireNonNull(skillDirs, "skillDirs");
        Objects.requireNonNull(hooksConfigPaths, "hooksConfigPaths");
        this.workspace = workspace.toRealPath();
        this.skillRoots = normalizeConfiguredPaths(skillDirs);
        this.hookConfigs = Set.copyOf(normalizeConfiguredPaths(hooksConfigPaths));
    }

    public boolean isControlPlane(Path target) throws IOException {
        Path normalized = validateTarget(target);
        if (hookConfigs.contains(normalized) || normalized.equals(workspace.resolve(".paicli/mcp.json"))) {
            return true;
        }
        return skillRoots.stream().anyMatch(normalized::startsWith);
    }

    private List<Path> normalizeConfiguredPaths(List<String> configuredPaths) {
        Set<Path> normalized = new LinkedHashSet<>();
        for (String configured : configuredPaths) {
            if (configured == null || configured.isBlank()) {
                throw new IllegalArgumentException(
                        "Control-plane paths must be non-blank");
            }
            Path path = workspace.resolve(configured).normalize();
            requireInsideWorkspace(path);
            normalized.add(path);
        }
        return List.copyOf(normalized);
    }

    private Path validateTarget(Path target) throws IOException {
        Objects.requireNonNull(target, "target");
        Path normalized = target.isAbsolute()
                ? target.normalize() : workspace.resolve(target).normalize();
        requireInsideWorkspace(normalized);
        rejectSymbolicLinks(normalized);

        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            Path real = normalized.toRealPath();
            requireInsideWorkspace(real);
            return normalized;
        }

        Path ancestor = normalized.getParent();
        while (ancestor != null
                && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            ancestor = ancestor.getParent();
        }
        if (ancestor == null) {
            throw new SecurityException("Target has no existing workspace parent");
        }
        Path realAncestor = ancestor.toRealPath();
        requireInsideWorkspace(realAncestor);
        return normalized;
    }

    private void rejectSymbolicLinks(Path target) {
        List<Path> segments = new ArrayList<>();
        Path current = target;
        while (current != null && current.startsWith(workspace)
                && !current.equals(workspace)) {
            segments.add(current);
            current = current.getParent();
        }
        for (Path segment : segments) {
            if (Files.isSymbolicLink(segment)) {
                throw new SecurityException(
                        "Control-plane target traverses a symbolic link");
            }
        }
    }

    private boolean isSkillFile(Path path) {
        Path fileName = path.getFileName();
        return fileName != null && "SKILL.md".equalsIgnoreCase(fileName.toString());
    }

    private void requireInsideWorkspace(Path path) {
        if (!path.startsWith(workspace)) {
            throw new SecurityException("Path is outside workspace");
        }
    }
}
