package com.paicli.subagent;

import com.paicli.config.Config;
import com.paicli.runtime.VerificationResult;
import com.paicli.runtime.VerificationService;

import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/** Trusted static verification binding; no command is exposed to requests or models. */
public final class ConfiguredVerifier {

    private final VerificationExecutor executor;
    private final String command;
    private final String configHash;

    public ConfiguredVerifier(VerificationService verificationService, Config config) {
        this(
                Objects.requireNonNull(
                        verificationService, "verificationService is required")::verify,
                Objects.requireNonNull(config, "config is required").verificationCommand(),
                verificationService.configurationHash(config.verificationCommand()));
    }

    ConfiguredVerifier(
            VerificationExecutor executor, String command, String configHash) {
        this.executor = Objects.requireNonNull(executor, "executor is required");
        this.command = command == null ? "" : command;
        if (configHash == null || configHash.isBlank()) {
            throw new IllegalArgumentException("configHash is required");
        }
        this.configHash = configHash;
    }

    public String configHash() {
        return configHash;
    }

    VerificationResult execute(String codingRunId, String taskId) throws SQLException {
        return executor.verify(
                UUID.randomUUID().toString(), codingRunId, taskId, 0, command);
    }

    @FunctionalInterface
    interface VerificationExecutor {
        VerificationResult verify(
                String verificationId,
                String codingRunId,
                String taskId,
                int repairAttempt,
                String command) throws SQLException;
    }
}
