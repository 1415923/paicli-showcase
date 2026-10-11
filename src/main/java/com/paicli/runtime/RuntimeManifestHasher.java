package com.paicli.runtime;

import com.paicli.config.Config;
import com.paicli.hook.HookConfigLoader;
import com.paicli.skill.SkillLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Computes deterministic hashes for workspace-local Skills and Hooks inputs. */
public final class RuntimeManifestHasher {

    private final Path workspace;

    public RuntimeManifestHasher(Path workspace) throws IOException {
        Path candidate = Objects.requireNonNull(workspace, "workspace")
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(candidate)) {
            throw new IOException("Runtime workspace is not a directory");
        }
        this.workspace = candidate.toRealPath();
    }

    public RuntimeManifest compute(Config config) throws IOException {
        Objects.requireNonNull(config, "config");
        return new RuntimeManifest(
                config.isSkillsEnabled()
                        ? skillsHash(config.effectiveSkillsDirs())
                        : disabledHash("skills"),
                config.isHooksEnabled()
                        ? hooksHash(config.effectiveHooksConfigPath())
                        : disabledHash("hooks"));
    }

    private String skillsHash(List<String> configuredDirs) throws IOException {
        List<String> entries = new ArrayList<>();
        int packageCount = 0;
        for (String configured : configuredDirs) {
            Path root = resolveInside(configured, "Skills directory");
            entries.add("dir:" + relative(root));
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            rejectLink(root, "Skills directory");
            com.paicli.skill.SkillPackageFiles.plain(root);
            Path realRoot = root.toRealPath();
            requireInside(realRoot, "Skills directory");
            if (!Files.isDirectory(realRoot, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Skills path is not a directory");
            }
            try (var children = Files.list(realRoot)) {
                var bounded = children.limit(257).toList();
                if(bounded.size()>256)throw new IOException("Skill discovery exceeds 256 directory entries");
                for (Path child : bounded) {
                    rejectLink(child, "Skill path");
                    if (!Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    Path skill = child.resolve("SKILL.md");
                    if (!Files.exists(skill, LinkOption.NOFOLLOW_LINKS)) {
                        continue;
                    }
                    if(++packageCount>64)throw new IOException("Skill discovery exceeds 64 packages");
                    rejectLink(skill, "Skill file");
                    Path realSkill = skill.toRealPath();
                    requireInside(realSkill, "Skill file");
                    if (!Files.isRegularFile(realSkill, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("SKILL.md is not a regular file");
                    }
                    var snapshot=com.paicli.skill.SkillPackageFiles.snapshot(child);
                    for(var resource:snapshot.hashes().entrySet()) {
                        entries.add("file:"+relative(child.resolve(resource.getKey()))+":"+resource.getValue());
                    }
                    for(String directory:snapshot.directories())entries.add("resource-dir:"+relative(child.resolve(directory)));
                }
            }
        }
        entries.sort(String::compareTo);
        return sha256(String.join("\n", entries).getBytes(StandardCharsets.UTF_8));
    }

    private String hooksHash(String configuredPath) throws IOException {
        Path hooks = resolveInside(configuredPath, "Hooks config");
        if (!Files.exists(hooks, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(hooks, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Hooks config is not a regular file");
        }
        rejectLink(hooks, "Hooks config");
        Path real = hooks.toRealPath();
        requireInside(real, "Hooks config");
        byte[] bytes = readBounded(
                real, HookConfigLoader.MAX_CONFIG_BYTES, "Hooks config");
        String canonical = "path:" + relative(hooks) + "\nsha256:" + sha256(bytes);
        return sha256(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private Path resolveInside(String configured, String label) throws IOException {
        if (configured == null || configured.isBlank()) {
            throw new IOException(label + " path must not be blank");
        }
        Path raw = Path.of(configured);
        Path resolved = raw.isAbsolute()
                ? raw.toAbsolutePath().normalize()
                : workspace.resolve(raw).normalize();
        requireInside(resolved, label);
        return resolved;
    }

    private byte[] readBounded(Path file, long maxBytes, String label) throws IOException {
        if (Files.size(file) > maxBytes) {
            throw new IOException(label + " exceeds size limit");
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > maxBytes) {
            throw new IOException(label + " exceeds size limit");
        }
        return bytes;
    }

    private void rejectLink(Path path, String label) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException(label + " must not be a symbolic link");
        }
    }

    private void requireInside(Path path, String label) throws IOException {
        if (!path.startsWith(workspace)) {
            throw new IOException(label + " must be inside the workspace");
        }
    }

    private String relative(Path path) {
        return workspace.relativize(path.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }

    private String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private String disabledHash(String runtime) {
        return sha256((runtime + ":disabled").getBytes(StandardCharsets.UTF_8));
    }
}
