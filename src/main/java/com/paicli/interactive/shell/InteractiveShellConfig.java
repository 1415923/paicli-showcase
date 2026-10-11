package com.paicli.interactive.shell;

import com.paicli.interactive.session.InteractiveSession;

import java.nio.file.Path;

public record InteractiveShellConfig(
        String applicationVersion, Path workspace, String provider, String model,
        String approvalMode, InteractiveSession.TerminalMode terminalMode,
        String providerSource, String verificationMode, String verificationPreset,
        String verificationSource, String startupCommand,
        String providerConfigurationFingerprint) {
    public InteractiveShellConfig(String applicationVersion, Path workspace, String provider,
            String model, String approvalMode, InteractiveSession.TerminalMode terminalMode,
            String providerSource, String verificationMode, String verificationPreset,
            String verificationSource, String startupCommand) {
        this(applicationVersion, workspace, provider, model, approvalMode, terminalMode,
                providerSource, verificationMode, verificationPreset, verificationSource,
                startupCommand, null);
    }
    public InteractiveShellConfig(String applicationVersion, Path workspace,
            String provider, String model, String approvalMode,
            InteractiveSession.TerminalMode terminalMode, String providerSource,
            String verificationMode, String verificationPreset,
            String verificationSource) {
        this(applicationVersion, workspace, provider, model, approvalMode, terminalMode,
                providerSource, verificationMode, verificationPreset,
                verificationSource, null, null);
    }
    public InteractiveShellConfig(String applicationVersion, Path workspace,
            String provider, String model, String approvalMode,
            InteractiveSession.TerminalMode terminalMode) {
        this(applicationVersion, workspace, provider, model, approvalMode, terminalMode,
                com.paicli.config.Config.PROVIDER_UNCONFIGURED.equals(provider)
                        ? "none" : "configured", "off", "none", "none", null);
    }
    public InteractiveShellConfig(String applicationVersion, Path workspace,
            String provider, String model, String approvalMode,
            InteractiveSession.TerminalMode terminalMode, String providerSource) {
        this(applicationVersion, workspace, provider, model, approvalMode,
                terminalMode, providerSource, "off", "none", "none", null);
    }
    public InteractiveShellConfig(String applicationVersion, Path workspace,
            String provider, String model, String approvalMode,
            InteractiveSession.TerminalMode terminalMode, String providerSource,
            String verificationMode, String verificationPreset) {
        this(applicationVersion, workspace, provider, model, approvalMode,
                terminalMode, providerSource, verificationMode,
                verificationPreset, "session", null, null);
    }
    public InteractiveShellConfig {
        if (applicationVersion == null || applicationVersion.isBlank() || workspace == null
                || provider == null || provider.isBlank() || approvalMode == null
                || approvalMode.isBlank() || terminalMode == null
                || providerSource == null || providerSource.isBlank()) {
            throw new IllegalArgumentException("invalid interactive shell configuration");
        }
        workspace = workspace.toAbsolutePath().normalize();
        if (!java.util.Set.of("off", "auto", "configured").contains(verificationMode)
                || verificationPreset == null || verificationPreset.isBlank()
                || verificationSource == null || verificationSource.isBlank()) {
            throw new IllegalArgumentException("invalid interactive verification configuration");
        }
        if (startupCommand != null
                && (startupCommand.isBlank() || startupCommand.length() > 512
                || !startupCommand.startsWith("/resume "))) {
            throw new IllegalArgumentException("invalid interactive startup command");
        }
        if (providerConfigurationFingerprint != null
                && !providerConfigurationFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid provider configuration fingerprint");
        }
    }
}
