package com.paicli.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads configuration from project-level and user-level JSON files.
 * <p>
 * Loading order (later overrides earlier):
 * <ol>
 *   <li>Defaults</li>
 *   <li>User-level: {@code ~/.paicli/config.json}</li>
 *   <li>Project-level: {@code <workspace>/.paicli/config.json}</li>
 * </ol>
 */
public class ConfigLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CONFIG_FILENAME = "config.json";

    private final Path projectDir;
    private final Path userHome;

    public ConfigLoader(Path projectDir) {
        this(projectDir, configuredUserHome());
    }

    public ConfigLoader(Path projectDir, Path userHome) {
        this.projectDir = projectDir.toAbsolutePath().normalize();
        this.userHome = userHome == null ? null : userHome.toAbsolutePath().normalize();
    }

    /**
     * Load configuration with project-level and user-level overrides applied to defaults.
     */
    public Config load() {
        return resolve().config();
    }

    public Resolution resolve() {
        return resolve(null);
    }

    public Resolution resolve(Config cliOverride) {
        Config config = Config.defaults();
        Source providerSource = Source.NONE;
        Source verificationSource = Source.NONE;

        // User-level config
        Path userConfig = getUserConfigPath();
        if (userConfig != null && Files.exists(userConfig)) {
            Config user = loadFile(userConfig);
            if (user != null) {
                config = config.merge(user);
                if (user.hasConfiguredProvider()) providerSource = Source.USER;
                if (hasVerificationPreference(user)) verificationSource = Source.USER;
            }
        }

        // Project-level config
        Path projectConfig = projectDir.resolve(".paicli").resolve(CONFIG_FILENAME);
        if (Files.exists(projectConfig)) {
            Config project = loadFile(projectConfig);
            if (project != null) {
                config = config.merge(project);
                if (project.hasConfiguredProvider()) providerSource = Source.PROJECT;
                if (hasVerificationPreference(project)) verificationSource = Source.PROJECT;
            }
        }
        if (cliOverride != null) {
            config = config.merge(cliOverride);
            if (cliOverride.hasConfiguredProvider()) providerSource = Source.CLI;
            if (hasVerificationPreference(cliOverride)) verificationSource = Source.CLI;
        }
        return new Resolution(config, providerSource, verificationSource);
    }

    /**
     * Load only the project-level config file. Returns null if not found.
     */
    public Config loadProjectConfig() {
        Path projectConfig = projectDir.resolve(".paicli").resolve(CONFIG_FILENAME);
        if (Files.exists(projectConfig)) {
            return loadFile(projectConfig);
        }
        return null;
    }

    private Path getUserConfigPath() {
        return userHome == null ? null : userHome.resolve(".paicli").resolve(CONFIG_FILENAME);
    }

    public Path projectConfigPath() {
        return projectDir.resolve(".paicli").resolve(CONFIG_FILENAME);
    }

    public Path userConfigPath() {
        return getUserConfigPath();
    }

    private static Path configuredUserHome() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) return null;
        try { return Path.of(home); }
        catch (RuntimeException error) { return null; }
    }

    public enum Source {
        CLI, SESSION, PROJECT, USER, NONE;

        public String label() { return name().toLowerCase(java.util.Locale.ROOT); }
    }

    public record Resolution(Config config, Source providerSource,
            Source verificationSource) {
        public Resolution(Config config, Source providerSource) {
            this(config, providerSource, Source.NONE);
        }
    }

    private static boolean hasVerificationPreference(Config config) {
        return config.enableVerify() != null
                || config.verificationCommand() != null;
    }

    private Config loadFile(Path path) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
            return MAPPER.readValue(bytes, Config.class);
        } catch (IOException e) {
            if (containsEnabledContinuity(path)) {
                throw new IllegalStateException(
                        "TASK_CONTINUITY_CONFIG_INVALID: " + path, e);
            }
            // Malformed config is treated as empty (use defaults)
            return null;
        }
    }

    private static boolean containsEnabledContinuity(Path path) {
        try {
            var root = MAPPER.readTree(Files.readAllBytes(path));
            var continuity = root == null ? null : root.get("task_continuity");
            return continuity != null && continuity.path("enabled").asBoolean(false);
        } catch (IOException ignored) {
            return false;
        }
    }
}
