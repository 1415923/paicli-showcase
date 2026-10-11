package com.paicli.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.UUID;
import com.paicli.interactive.verification.InteractiveVerificationPolicy;

/** Safely updates provider or verification fields in user/project configuration. */
public final class ProviderConfigStore {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Path workspace;
    private final Path userHome;

    public ProviderConfigStore(Path workspace, Path userHome) {
        this.workspace = absolute(workspace, "workspace");
        this.userHome = userHome == null ? null : absolute(userHome, "userHome");
    }

    public Config save(Scope scope, Config config) throws IOException {
        Objects.requireNonNull(config, "config");
        Path target = target(scope);
        ObjectNode root = readObject(target);
        putOrRemove(root, "provider", config.provider());
        putOrRemove(root, "model", config.model());
        putOrRemove(root, "base_url", config.baseUrl());
        putOrRemove(root, "api_key_env", config.apiKeyEnv());
        putOrRemove(root, "credential_ref", config.credentialRef());
        putOrRemove(root, "provider_preset", config.providerPreset());
        putOrRemove(root, "thinking", config.thinking());
        putOrRemove(root, "reasoning_effort", config.reasoningEffort());
        writeVerified(target, root, parsed -> verifyProviderFields(parsed, config));
        return readConfig(target);
    }

    public Config clear(Scope scope) throws IOException {
        return save(scope, Config.defaults().withoutProvider());
    }

    public Config saveVerification(Scope scope,
            InteractiveVerificationPolicy.Selection selection) throws IOException {
        Objects.requireNonNull(selection, "selection");
        if (!java.util.Set.of(InteractiveVerificationPolicy.AUTO,
                InteractiveVerificationPolicy.OFF).contains(selection.mode())) {
            throw new IOException("only auto or off verification preferences can be saved");
        }
        Path target = target(scope);
        ObjectNode root = readObject(target);
        boolean enabled = InteractiveVerificationPolicy.AUTO.equals(selection.mode());
        root.put("enable_verify", enabled);
        if (enabled) root.put("verification_command",
                InteractiveVerificationPolicy.MAVEN_COMMAND);
        else root.remove("verification_command");
        writeVerified(target, root, parsed -> {
            InteractiveVerificationPolicy.Selection actual =
                    InteractiveVerificationPolicy.fromConfig(parsed);
            if (!actual.mode().equals(selection.mode())) {
                throw new IOException("verification configuration verification failed");
            }
        });
        return readConfig(target);
    }

    private Path target(Scope scope) throws IOException {
        Path root = switch (Objects.requireNonNull(scope, "scope")) {
            case PROJECT -> workspace;
            case USER -> {
                if (userHome == null) throw new IOException("user home is unavailable");
                yield userHome;
            }
        };
        verifyOwnedRoot(root);
        Path control = root.resolve(".paicli");
        if (Files.exists(control, LinkOption.NOFOLLOW_LINKS)) rejectLink(control);
        else Files.createDirectory(control);
        rejectLink(control);
        Path target = control.resolve("config.json");
        if (!target.normalize().startsWith(root)) throw new IOException("config path escaped its root");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) rejectLink(target);
        return target;
    }

    private static void writeVerified(Path target, ObjectNode root, ConfigVerifier verifier)
            throws IOException {
        String rendered = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root)
                .replace("\r\n", "\n").replace('\r', '\n');
        byte[] json = (rendered + "\n").getBytes(StandardCharsets.UTF_8);
        Path temp = target.resolveSibling(".config-" + UUID.randomUUID() + ".tmp");
        try {
            Files.write(temp, json, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            rejectLink(temp);
            Config parsed = readConfig(temp);
            verifier.verify(parsed);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            rejectLink(target);
            verifier.verify(readConfig(target));
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static ObjectNode readObject(Path target) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return MAPPER.createObjectNode();
        rejectLink(target);
        JsonNode parsed = MAPPER.readTree(Files.readAllBytes(target));
        if (!(parsed instanceof ObjectNode object)) {
            throw new IOException("config root must be a JSON object");
        }
        return object.deepCopy();
    }

    private static Config readConfig(Path target) throws IOException {
        return MAPPER.readValue(Files.readAllBytes(target), Config.class);
    }

    private static void verifyProviderFields(Config actual, Config expected) throws IOException {
        if (!Objects.equals(actual.provider(), expected.provider())
                || !Objects.equals(actual.model(), expected.model())
                || !Objects.equals(actual.baseUrl(), expected.baseUrl())
                || !Objects.equals(actual.apiKeyEnv(), expected.apiKeyEnv())
                || !Objects.equals(actual.credentialRef(), expected.credentialRef())
                || !Objects.equals(actual.providerPreset(), expected.providerPreset())
                || !Objects.equals(actual.thinking(), expected.thinking())
                || !Objects.equals(actual.reasoningEffort(), expected.reasoningEffort())) {
            throw new IOException("provider configuration verification failed");
        }
    }

    private static void putOrRemove(ObjectNode root, String name, String value) {
        if (value == null || value.isBlank()) root.remove(name);
        else root.put(name, value);
    }

    private static void verifyOwnedRoot(Path root) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("configuration root is unavailable");
        }
        rejectLink(root);
        if (!root.toRealPath(LinkOption.NOFOLLOW_LINKS).equals(root)) {
            throw new IOException("configuration root identity mismatch");
        }
    }

    private static void rejectLink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("configuration links are not allowed");
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attributes.isOther()) throw new IOException("configuration reparse points are not allowed");
    }

    private static Path absolute(Path value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " is required");
        return value.toAbsolutePath().normalize();
    }

    @FunctionalInterface
    private interface ConfigVerifier {
        void verify(Config config) throws IOException;
    }

    public enum Scope { USER, PROJECT }
}
