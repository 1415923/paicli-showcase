package com.paicli.interactive.verification;

import com.paicli.config.Config;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Session-scoped verification modes; commands are runtime-owned presets. */
public final class InteractiveVerificationPolicy {
    public static final String OFF = "off";
    public static final String AUTO = "auto";
    public static final String CONFIGURED = "configured";
    public static final String MAVEN_PRESET = "MAVEN_OFFLINE_TEST_V1";
    public static final String MAVEN_COMMAND = "mvn test";

    private InteractiveVerificationPolicy() { }

    public static Selection fromConfig(Config config) {
        if (config.isVerifyEnabled()
                && MAVEN_COMMAND.equals(config.effectiveVerificationCommand())) {
            return new Selection(AUTO, MAVEN_PRESET);
        }
        if (config.isVerifyEnabled() && !config.effectiveVerificationCommand().isBlank()) {
            return new Selection(CONFIGURED, "configured verifier");
        }
        return new Selection(OFF, "none");
    }

    public static Selection auto(Path workspace) {
        Path pom = workspace.toAbsolutePath().normalize().resolve("pom.xml");
        if (!Files.isRegularFile(pom, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(
                    "/verify auto requires a Maven pom.xml in the workspace root");
        }
        return new Selection(AUTO, MAVEN_PRESET);
    }

    public static Config apply(Config config, Path workspace, String mode) {
        return switch (mode) {
            case AUTO -> {
                auto(workspace);
                yield config.withVerification(MAVEN_COMMAND, true);
            }
            case OFF -> config.withVerification("", false);
            case CONFIGURED -> config;
            default -> throw new IllegalStateException(
                    "unsupported interactive verification mode");
        };
    }

    public record Selection(String mode, String preset) { }
}
