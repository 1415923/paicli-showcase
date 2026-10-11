package com.paicli.doctor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.Config;
import com.paicli.config.ConfigLoader;
import com.paicli.runtime.BoundedProcessExecutor;
import com.paicli.provider.ProviderHealthReport;
import com.paicli.provider.ProviderHealthService;
import com.paicli.provider.ProviderFactory;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Performs bounded, read-only environment diagnostics. */
public final class DoctorService {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern MAVEN_VERSION =
            Pattern.compile("Apache Maven ([^\\r\\n]+)");

    private final Path workspace;
    private final Path userHome;
    private final MavenProbe mavenProbe;
    private final Function<String, String> environment;
    private final Supplier<ConfigLoader.Resolution> configResolution;
    private final ProviderHealthService providerHealth;

    public DoctorService(Path workspace) {
        this(workspace, configuredUserHome(),
                DoctorService::probeMaven, System::getenv,
                () -> new ConfigLoader(workspace).resolve(), new ProviderHealthService());
    }

    public DoctorService(Path workspace,
            Supplier<ConfigLoader.Resolution> configResolution,
            ProviderHealthService providerHealth) {
        this(workspace, configuredUserHome(), DoctorService::probeMaven,
                System::getenv, configResolution, providerHealth);
    }

    DoctorService(Path workspace, Path userHome, MavenProbe mavenProbe,
                  Function<String, String> environment) {
        this(workspace, userHome, mavenProbe, environment,
                () -> new ConfigLoader(workspace, userHome).resolve(), null);
    }

    private DoctorService(Path workspace, Path userHome, MavenProbe mavenProbe,
            Function<String, String> environment,
            Supplier<ConfigLoader.Resolution> configResolution,
            ProviderHealthService providerHealth) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.userHome = userHome == null ? null : userHome.toAbsolutePath().normalize();
        this.mavenProbe = mavenProbe;
        this.environment = environment;
        this.configResolution = configResolution;
        this.providerHealth = providerHealth;
    }

    public DoctorReport inspect() {
        List<DoctorCheck> checks = new ArrayList<>();
        int feature = Runtime.version().feature();
        checks.add(check("Java 21",
                feature == 21 ? DoctorStatus.PASS : DoctorStatus.FAIL,
                Integer.toString(feature)));

        MavenProbeResult maven;
        try {
            maven = mavenProbe.run(workspace);
        } catch (RuntimeException error) {
            maven = new MavenProbeResult(false, "diagnostic failed");
        }
        checks.add(check("Maven",
                maven.available() ? DoctorStatus.PASS : DoctorStatus.FAIL,
                maven.summary()));

        boolean workspaceOk = Files.isDirectory(workspace)
                && Files.isReadable(workspace) && Files.isWritable(workspace);
        checks.add(check("Workspace",
                workspaceOk ? DoctorStatus.PASS : DoctorStatus.FAIL,
                workspaceOk ? "accessible" : "not accessible"));

        boolean project = Files.isRegularFile(workspace.resolve("pom.xml"))
                || Files.isDirectory(workspace.resolve("src/main/java"));
        checks.add(check("Project",
                project ? DoctorStatus.PASS : DoctorStatus.WARN,
                project ? "Java/Maven markers found" : "no Java/Maven markers"));

        Path projectConfig = workspace.resolve(".paicli/config.json");
        ConfigLoader.Resolution resolution = configResolution.get();
        Config config = resolution.config();
        if (!Files.exists(projectConfig)) {
            checks.add(check("Project config", DoctorStatus.WARN, "not found"));
        } else {
            try {
                MAPPER.readValue(Files.readAllBytes(projectConfig), Config.class);
                checks.add(check("Project config",
                        DoctorStatus.PASS, "valid JSON"));
            } catch (Exception error) {
                checks.add(check("Project config",
                        DoctorStatus.FAIL, "invalid JSON"));
            }
        }

        checks.add(check("Config source", DoctorStatus.PASS,
                resolution.providerSource().label()));
        if (providerHealth == null) addProviderChecks(checks, config);
        else addProviderHealthChecks(checks, config, providerHealth.inspect(config, false));
        checks.add(check("Approval mode",
                config.isValidApprovalMode() ? DoctorStatus.PASS : DoctorStatus.FAIL,
                safeLabel(config.approvalMode())));
        checks.add(directoryCheck("State directory",
                workspace.resolve(".paicli/state")));
        checks.add(userHome == null
                ? check("Logs", DoctorStatus.FAIL, "user home unavailable")
                : directoryCheck("Logs", userHome.resolve(".paicli/logs")));

        DoctorStatus overall = checks.stream()
                .anyMatch(value -> value.status() == DoctorStatus.FAIL)
                ? DoctorStatus.FAIL
                : checks.stream().anyMatch(value -> value.status() == DoctorStatus.WARN)
                ? DoctorStatus.WARN : DoctorStatus.PASS;
        return new DoctorReport(checks, overall);
    }

    private static Path configuredUserHome() {
        String value = System.getProperty("user.home");
        if (value == null || value.isBlank()) return null;
        try {
            return Path.of(value);
        } catch (RuntimeException error) {
            return null;
        }
    }
    private void addProviderChecks(List<DoctorCheck> checks, Config config) {
        if (!config.hasConfiguredProvider()) {
            checks.add(check("Provider", DoctorStatus.WARN,
                    "No production provider configured"));
            return;
        }
        switch (config.effectiveProvider()) {
            case Config.PROVIDER_MOCK -> checks.add(check(
                    "Provider", DoctorStatus.FAIL,
                    ProviderFactory.MOCK_UNSUPPORTED_CODE
                            + ": choose Ollama or OpenAI-compatible"));
            case Config.PROVIDER_OLLAMA -> {
                checks.add(check("Provider", DoctorStatus.PASS, "ollama"));
                String endpoint = config.baseUrl() == null
                        ? "http://localhost:11434" : config.baseUrl();
                boolean valid = validHttpEndpoint(endpoint);
                checks.add(check("Ollama endpoint",
                        valid ? DoctorStatus.PASS : DoctorStatus.FAIL,
                        valid ? "valid configuration" : "invalid configuration"));
            }
            case Config.PROVIDER_OPENAI -> {
                String endpoint = config.baseUrl() == null
                        ? "https://api.openai.com" : config.baseUrl();
                boolean valid = validHttpEndpoint(endpoint);
                checks.add(check("Provider",
                        valid ? DoctorStatus.PASS : DoctorStatus.FAIL,
                        "openai-compatible"));
                String envName = config.apiKeyEnv() == null
                        ? "OPENAI_API_KEY" : config.apiKeyEnv();
                String secret = safeEnvironmentName(envName)
                        ? environment.apply(envName) : null;
                boolean present = secret != null && !secret.isBlank();
                checks.add(check("API key environment",
                        present ? DoctorStatus.PASS : DoctorStatus.FAIL,
                        present ? "configured" : "missing"));
            }
            default -> checks.add(check(
                    "Provider", DoctorStatus.FAIL, "unknown provider"));
        }
    }

    private static void addProviderHealthChecks(List<DoctorCheck> checks,
            Config config, ProviderHealthReport health) {
        if (!config.hasConfiguredProvider()) {
            checks.add(check("Provider", DoctorStatus.WARN,
                    "No production provider configured"));
            return;
        }
        if (Config.PROVIDER_MOCK.equals(config.effectiveProvider())) {
            checks.add(check("Provider", DoctorStatus.FAIL,
                    ProviderFactory.MOCK_UNSUPPORTED_CODE
                            + ": choose Ollama or OpenAI-compatible"));
            return;
        }
        checks.add(check("Provider", health.ready() ? DoctorStatus.PASS : DoctorStatus.FAIL,
                config.effectiveProvider()));
        checks.add(check("Provider endpoint",
                health.endpointValid() ? DoctorStatus.PASS : DoctorStatus.FAIL,
                health.endpointLabel()));
        checks.add(check("Provider reachable",
                health.reachable() ? DoctorStatus.PASS : DoctorStatus.FAIL,
                health.reachable() ? "reachable" : health.errorCategory()));
        checks.add(check("Provider model",
                health.modelConfigured() ? DoctorStatus.PASS : DoctorStatus.FAIL,
                health.modelConfigured() ? safeModelLabel(config.model()) : "not configured"));
        checks.add(check("Model discovery",
                health.modelDiscovered() ? DoctorStatus.PASS : DoctorStatus.WARN,
                health.modelDiscovered() ? "discovered" : "not confirmed"));
        if (Config.PROVIDER_OPENAI.equals(config.effectiveProvider())) {
            checks.add(check("API key environment",
                    health.environmentPresent() ? DoctorStatus.PASS : DoctorStatus.FAIL,
                    health.environmentPresent() ? "configured" : "missing"));
        }
        checks.add(check("Provider ready",
                health.ready() ? DoctorStatus.PASS : DoctorStatus.FAIL,
                health.ready() ? "READY" : health.errorCategory()));
    }

    private DoctorCheck directoryCheck(String name, Path target) {
        Path current = target;
        while (current != null && !Files.exists(current)) {
            current = current.getParent();
        }
        boolean writable = current != null
                && Files.isDirectory(current) && Files.isWritable(current);
        return check(name, writable ? DoctorStatus.PASS : DoctorStatus.FAIL,
                writable ? "available" : "not writable");
    }

    private static boolean validHttpEndpoint(String value) {
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static boolean safeEnvironmentName(String name) {
        return name != null
                && name.matches("[A-Za-z_][A-Za-z0-9_]{0,127}");
    }

    private static String safeLabel(String value) {
        return value != null && value.matches("[a-z0-9-]{1,32}")
                ? value : "invalid";
    }

    private static String safeModelLabel(String value) {
        if (value == null) return "invalid";
        String clean = value.replaceAll("[\\p{Cntrl}]", "?");
        return clean.substring(0, Math.min(clean.length(), 96));
    }

    private static DoctorCheck check(
            String name, DoctorStatus status, String detail) {
        String bounded = detail == null ? ""
                : detail.replace('\r', ' ').replace('\n', ' ');
        if (bounded.length() > 96) bounded = bounded.substring(0, 96);
        return new DoctorCheck(name, status, bounded);
    }

    private static MavenProbeResult probeMaven(Path workspace) {
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).contains("windows");
        ProcessBuilder builder = windows
                ? new ProcessBuilder(
                System.getenv().getOrDefault("ComSpec", "cmd.exe"),
                "/d", "/s", "/c", "mvn.cmd", "-version")
                : new ProcessBuilder("mvn", "-version");
        builder.directory(workspace.toFile());
        BoundedProcessExecutor.Result result =
                new BoundedProcessExecutor(8192)
                        .execute(builder, Duration.ofSeconds(10));
        if (result.status() != BoundedProcessExecutor.Status.SUCCEEDED) {
            return new MavenProbeResult(false, "not available");
        }
        Matcher matcher = MAVEN_VERSION.matcher(result.combinedOutput());
        return matcher.find()
                ? new MavenProbeResult(true, matcher.group(1).trim())
                : new MavenProbeResult(false,
                "unrecognized version output");
    }

    @FunctionalInterface
    interface MavenProbe {
        MavenProbeResult run(Path workspace);
    }

    record MavenProbeResult(boolean available, String summary) { }
}
