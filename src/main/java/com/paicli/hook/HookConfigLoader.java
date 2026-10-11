package com.paicli.hook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Loads a bounded, workspace-local hooks.json without interpolation. */
public final class HookConfigLoader {

    public static final String DEFAULT_CONFIG_PATH = ".paicli/hooks.json";
    public static final int MAX_CONFIG_BYTES = 256 * 1024;

    private final Path workspace;
    private final int defaultTimeoutSeconds;
    private final String defaultFailurePolicy;
    private final ObjectMapper mapper;

    public HookConfigLoader(Path workspace, int defaultTimeoutSeconds,
                            String defaultFailurePolicy) throws IOException {
        Path candidate = Objects.requireNonNull(workspace, "workspace")
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(candidate)) {
            throw new IOException("Hook workspace is not a directory");
        }
        this.workspace = candidate.toRealPath();
        if (defaultTimeoutSeconds < 1 || defaultTimeoutSeconds > 300) {
            throw new IllegalArgumentException("defaultTimeoutSeconds must be between 1 and 300");
        }
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
        this.defaultFailurePolicy = normalizePolicy(defaultFailurePolicy);
        this.mapper = new ObjectMapper()
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public List<HookDefinition> load() throws IOException {
        return load(Path.of(DEFAULT_CONFIG_PATH));
    }

    public List<HookDefinition> load(Path configuredPath) throws IOException {
        Path config = resolveConfig(configuredPath);
        long size = Files.size(config);
        if (size > MAX_CONFIG_BYTES) {
            throw new IOException("Hooks config exceeds " + MAX_CONFIG_BYTES + " bytes");
        }
        HookConfig document = mapper.readValue(config.toFile(), HookConfig.class);
        if (document.version() != 1) {
            throw new IOException("Unsupported hooks config version: " + document.version());
        }
        if (document.hooks() == null) {
            throw new IOException("hooks must be an array");
        }

        Set<String> ids = new HashSet<>();
        List<HookDefinition> definitions = document.hooks().stream().map(raw -> {
            if (raw == null) {
                throw new IllegalArgumentException("Hook definition must not be null");
            }
            String id = raw.id() == null ? null : raw.id().trim();
            if (id != null && !ids.add(id)) {
                throw new IllegalArgumentException("Duplicate hook id: " + id);
            }
            HookEvent event;
            try {
                event = HookEvent.valueOf(Objects.requireNonNull(raw.event(),
                        "Hook event must not be null").trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new IllegalArgumentException("Invalid hook event: " + raw.event(), e);
            }
            int timeout = raw.timeoutSeconds() == null
                    ? defaultTimeoutSeconds : raw.timeoutSeconds();
            String policy = raw.failurePolicy() == null
                    ? defaultFailurePolicy : normalizePolicy(raw.failurePolicy());
            return new HookDefinition(id, event, raw.command(), timeout, policy,
                    raw.enabled() == null || raw.enabled());
        }).toList();
        return List.copyOf(definitions);
    }

    private Path resolveConfig(Path configuredPath) throws IOException {
        Objects.requireNonNull(configuredPath, "configuredPath");
        Path normalized = configuredPath.isAbsolute()
                ? configuredPath.toAbsolutePath().normalize()
                : workspace.resolve(configuredPath).normalize();
        if (!normalized.startsWith(workspace)) {
            throw new IOException("Hooks config must be inside the workspace");
        }
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Hooks config is not a regular file");
        }
        if (Files.isSymbolicLink(normalized)) {
            throw new IOException("Hooks config must not be a symbolic link");
        }
        Path real = normalized.toRealPath();
        if (!real.startsWith(workspace)) {
            throw new IOException("Hooks config resolves outside the workspace");
        }
        return real;
    }

    private static String normalizePolicy(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Hook failure policy must not be null");
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!"warn".equals(normalized) && !"fail".equals(normalized)) {
            throw new IllegalArgumentException("Hook failure policy must be warn or fail");
        }
        return normalized;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    private record HookConfig(int version, List<RawHook> hooks) {
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    private record RawHook(
            String id,
            String event,
            String command,
            @JsonProperty("timeout_seconds") Integer timeoutSeconds,
            @JsonProperty("failure_policy") String failurePolicy,
            Boolean enabled
    ) {
    }
}
