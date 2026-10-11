package com.paicli.interactive.provider;

import com.paicli.config.Config;
import com.paicli.config.ConfigLoader;
import com.paicli.config.ProviderConfigStore;
import com.paicli.credential.CredentialRef;
import com.paicli.credential.CredentialStore;
import com.paicli.credential.ProviderCredentialResolver;
import com.paicli.interactive.command.ShellCommandResult;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.render.InteractiveViewModelRenderer;
import com.paicli.provider.ProviderEndpointPolicy;
import com.paicli.provider.ProviderHealthReport;
import com.paicli.provider.ProviderHealthService;
import com.paicli.provider.telemetry.ProviderCallContext;
import com.paicli.provider.telemetry.ProviderCallObservation;
import com.paicli.state.ProviderCallTelemetryStore;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Plain/JLine-neutral provider onboarding and model selection. */
public final class InteractiveProviderCommandService implements InteractiveProviderCommandPort {
    private static final int MAX_ATTEMPTS = 3;
    private final InteractiveProviderState state;
    private final ConfigLoader loader;
    private final ProviderConfigStore store;
    private final ProviderHealthService health;
    private final ProviderPrompt prompt;
    private final Path workspace;
    private final ProviderCredentialResolver credentials;

    public InteractiveProviderCommandService(InteractiveProviderState state,
            ConfigLoader loader, ProviderConfigStore store,
            ProviderHealthService health, ProviderPrompt prompt) {
        this(state, loader, store, health, prompt, null);
    }

    public InteractiveProviderCommandService(InteractiveProviderState state,
            ConfigLoader loader, ProviderConfigStore store,
            ProviderHealthService health, ProviderPrompt prompt, Path workspace) {
        this(state,loader,store,health,prompt,workspace,
                ProviderCredentialResolver.systemDefault());
    }

    public InteractiveProviderCommandService(InteractiveProviderState state,
            ConfigLoader loader,ProviderConfigStore store,ProviderHealthService health,
            ProviderPrompt prompt,Path workspace,ProviderCredentialResolver credentials) {
        this.state = state;
        this.loader = loader;
        this.store = store;
        this.health = health;
        this.prompt = prompt;
        this.workspace = workspace == null ? null : workspace.toAbsolutePath().normalize();
        this.credentials=java.util.Objects.requireNonNull(credentials,"credentials");
    }

    @Override
    public ShellCommandResult provider(List<String> arguments, InteractiveSession session) {
        if (arguments.isEmpty()) {
            if (session.snapshot().currentTaskId() != null) {
                return rejected("Provider cannot be changed while a task is active.");
            }
            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                String choice = ask("Provider [1=Ollama, 2=OpenAI-compatible, Enter=cancel]: ");
                if (choice == null) return cancelled();
                if ("1".equals(choice) || "ollama".equals(choice)) {
                    return onboard(Config.PROVIDER_OLLAMA, session);
                }
                if ("2".equals(choice) || "openai".equals(choice)) {
                    return onboard(Config.PROVIDER_OPENAI, session);
                }
            }
            return rejected("Invalid provider selection.");
        }
        String operation = arguments.getFirst();
        return switch (operation) {
            case "status" -> status(session);
            case "test" -> health(false, session);
            case "clear" -> clear(session);
            case "set" -> onboard(arguments.get(1), session);
            case "credential" -> credentialStatus(session);
            case "login" -> login(session);
            case "logout" -> logout(session);
            default -> rejected("Usage: /provider [status|set ollama|set openai|test|clear|credential status|login|logout]");
        };
    }

    private ShellCommandResult credentialStatus(InteractiveSession session){
        Config config=state.config();
        if(!Config.PROVIDER_OPENAI.equals(config.effectiveProvider()))
            return rejected("Credential storage applies to the current OpenAI-compatible provider profile.");
        var status=credentials.status(config);
        return ShellCommandResult.continueWith("PROVIDER_CREDENTIAL_STATUS",List.of(
                "Credential: "+(status.present()?"present":"missing"),
                "Source: "+status.source(),"Backend: "+status.backend()));
    }

    private ShellCommandResult login(InteractiveSession session){
        if(session.snapshot().currentTaskId()!=null)return rejected("Provider login is unavailable while a task is active.");
        Config current=state.config();
        if(!Config.PROVIDER_OPENAI.equals(current.effectiveProvider()))
            return rejected("Configure an OpenAI-compatible provider before login.");
        CredentialRef reference=ProviderCredentialResolver.reference(current);
        CredentialStore.State backendState=credentials.store().status(reference).state();
        if(backendState==CredentialStore.State.UNSUPPORTED)
            return rejected("CREDENTIAL_STORE_UNSUPPORTED");
        if(backendState==CredentialStore.State.ERROR)
            return rejected("CREDENTIAL_STORE_UNAVAILABLE");
        char[] secret=prompt.readSecret("Provider API key (input hidden): ");
        if(secret==null||secret.length==0){if(secret!=null)java.util.Arrays.fill(secret,'\0');return rejected("Credential was not stored.");}
        try{
            credentials.store().put(reference,secret);
            persistCredentialReference(current.withCredentialRef(reference.value()),session);
            return ShellCommandResult.continueWith("PROVIDER_LOGIN",List.of(
                    "Credential: present","Source: os-store",
                    "Backend: windows-credential-manager"));
        }catch(RuntimeException|IOException error){
            return rejected("Credential was not stored: "+safe(error.getMessage()));
        }finally{java.util.Arrays.fill(secret,'\0');}
    }

    private ShellCommandResult logout(InteractiveSession session){
        if(session.snapshot().currentTaskId()!=null)return rejected("Provider logout is unavailable while a task is active.");
        Config current=state.config();
        if(!Config.PROVIDER_OPENAI.equals(current.effectiveProvider()))
            return rejected("No OpenAI-compatible provider credential is selected.");
        CredentialRef reference=ProviderCredentialResolver.reference(current);
        try{
            boolean deleted=credentials.store().delete(reference);
            var status=credentials.status(current);
            return ShellCommandResult.continueWith("PROVIDER_LOGOUT",List.of(
                    deleted?"Stored credential deleted.":"Stored credential was already absent.",
                    "Effective source: "+status.source()));
        }catch(RuntimeException error){return rejected("Credential was not deleted: "+safe(error.getMessage()));}
    }

    private void persistCredentialReference(Config candidate,InteractiveSession session)
            throws IOException {
        Config installed=candidate;ConfigLoader.Source source=state.source();
        if(source==ConfigLoader.Source.PROJECT||source==ConfigLoader.Source.USER){
            store.save(source==ConfigLoader.Source.USER?ProviderConfigStore.Scope.USER:
                    ProviderConfigStore.Scope.PROJECT,candidate);
            ConfigLoader.Resolution resolved=loader.resolve();installed=resolved.config();source=resolved.providerSource();
        }
        state.install(installed,source);
        session.changeProvider(installed.effectiveProvider(),installed.model(),source.label(),
                ProviderHealthService.observationFingerprint(installed));
    }

    @Override
    public ShellCommandResult model(List<String> arguments, InteractiveSession session) {
        Config current = state.config();
        if (!current.hasConfiguredProvider()) {
            return rejected("No provider configured. Use /provider first.");
        }
        if (arguments.isEmpty()) {
            return ShellCommandResult.continueWith("MODEL_STATUS", List.of(
                    "Provider: " + safe(current.effectiveProvider()),
                    "Model: " + safe(current.model())));
        }
        if ("list".equals(arguments.getFirst())) {
            try {
                List<String> models = discoverModelsObserved(current, true, session);
                return ShellCommandResult.continueWith("MODEL_LIST", modelLines(models));
            } catch (RuntimeException error) {
                return rejected("Model discovery failed: " + safe(error.getMessage()));
            }
        }
        if ("set".equals(arguments.getFirst())) {
            return setModel(arguments.get(1), session);
        }
        return rejected("Usage: /model [list|set <model-id>]");
    }

    private ShellCommandResult onboard(String provider, InteractiveSession session) {
        if (session.snapshot().currentTaskId() != null) {
            return rejected("Provider cannot be changed while a task is active.");
        }
        String endpoint = readEndpoint(provider.equals(Config.PROVIDER_OLLAMA)
                ? "Ollama endpoint (Enter=cancel): "
                : "OpenAI-compatible endpoint (Enter=cancel): ");
        if (endpoint == null) return cancelled();

        String environmentName = null;
        if (Config.PROVIDER_OPENAI.equals(provider)) {
            environmentName = readEnvironmentName();
            if (environmentName == null) return cancelled();
        }

        Config candidate = state.config().withProvider(provider, null, endpoint, environmentName);
        ProviderHealthReport probe = inspectObserved(candidate, false, session);
        if (!probe.reachable()) {
            return rejected("Provider test failed: " + safeCategory(probe.errorCategory()));
        }
        String model = chooseModel(probe.models());
        if (model == null) return cancelled();
        candidate = candidate.withModel(model);
        SaveScope scope = chooseScope();
        if (scope == null) return cancelled();
        return install(candidate, scope, session);
    }

    private ShellCommandResult setModel(String requested, InteractiveSession session) {
        if (session.snapshot().currentTaskId() != null) {
            return rejected("Model cannot be changed while a task is active.");
        }
        String model;
        try { model = ProviderEndpointPolicy.validateModelId(requested); }
        catch (IllegalArgumentException error) { return rejected(error.getMessage()); }
        Config candidate = state.config().withModel(model);
        ProviderHealthReport probe = inspectObserved(candidate, true, session);
        if (!probe.reachable()) return rejected("Provider test failed: " + safe(probe.errorCategory()));
        SaveScope scope = switch (state.source()) {
            case PROJECT -> SaveScope.PROJECT;
            case USER -> SaveScope.USER;
            default -> SaveScope.SESSION;
        };
        return install(candidate, scope, session);
    }

    private ShellCommandResult install(Config candidate, SaveScope scope,
            InteractiveSession session) {
        try {
            // Validate construction before any durable or session-visible replacement.
            com.paicli.provider.ProviderFactory.create(candidate);
            Config installed;
            ConfigLoader.Source source;
            if (scope == SaveScope.SESSION) {
                installed = candidate;
                source = ConfigLoader.Source.SESSION;
            } else {
                store.save(scope == SaveScope.USER
                        ? ProviderConfigStore.Scope.USER : ProviderConfigStore.Scope.PROJECT,
                        candidate);
                ConfigLoader.Resolution resolved = loader.resolve();
                installed = resolved.config();
                source = resolved.providerSource();
            }
            state.install(installed, source);
            session.changeProvider(installed.effectiveProvider(), installed.model(), source.label(),
                    ProviderHealthService.observationFingerprint(installed));
            return ShellCommandResult.continueWith("PROVIDER_READY", List.of(
                    "Provider: " + installed.effectiveProvider(),
                    "Model: " + installed.model(),
                    "Source: " + source.label(),
                    "Status: READY"));
        } catch (Exception error) {
            return rejected("Provider was not changed: " + safe(error.getMessage()));
        }
    }

    private ShellCommandResult clear(InteractiveSession session) {
        if (session.snapshot().currentTaskId() != null) {
            return rejected("Provider cannot be cleared while a task is active.");
        }
        try {
            switch (state.source()) {
                case PROJECT -> store.clear(ProviderConfigStore.Scope.PROJECT);
                case USER -> store.clear(ProviderConfigStore.Scope.USER);
                case SESSION, CLI, NONE -> { }
            }
            ConfigLoader.Resolution fallback = loader.resolve();
            state.clear(fallback);
            session.changeProvider(fallback.config().effectiveProvider(),
                    fallback.config().model(), fallback.providerSource().label(),
                    ProviderHealthService.observationFingerprint(fallback.config()));
            return ShellCommandResult.continueWith("PROVIDER_CLEARED", List.of(
                    "Provider fields cleared.",
                    "Effective provider: " + fallback.config().effectiveProvider(),
                    "Source: " + fallback.providerSource().label()));
        } catch (IOException error) {
            return rejected("Provider was not cleared: " + safe(error.getMessage()));
        }
    }

    private ShellCommandResult status(InteractiveSession session) {
        if (workspace != null) {
            try (com.paicli.state.StateStore persisted =
                         new com.paicli.state.StateStore(workspace)) {
                var model = persisted.viewModels.read(session.snapshot(), null);
                return ShellCommandResult.continueWith("PROVIDER_STATUS",
                        new InteractiveViewModelRenderer().provider(model, false));
            } catch (Exception error) {
                return ShellCommandResult.continueWith("PROVIDER_STATUS_UNAVAILABLE",
                        List.of("Provider status unavailable: VIEW_SNAPSHOT_UNAVAILABLE"));
            }
        }
        Config config = state.config();
        return ShellCommandResult.continueWith("PROVIDER_STATUS", List.of(
                "Provider: " + config.effectiveProvider(),
                "Model: " + safe(config.model()),
                "Source: " + state.source().label()));
    }

    private ShellCommandResult health(boolean cached, InteractiveSession session) {
        Config config = state.config();
        ProviderHealthReport report = inspectObserved(config, cached, session);
        return ShellCommandResult.continueWith("PROVIDER_TEST", List.of(
                "Observed at: " + Instant.now(),
                "Freshness: OBSERVED",
                "Provider: " + report.provider(),
                "Endpoint valid: " + yes(report.endpointValid()),
                "Reachable: " + yes(report.reachable()),
                "Model configured: " + yes(report.modelConfigured()),
                "Model discovered: " + yes(report.modelDiscovered()),
                "API key environment present: " + yes(report.environmentPresent()),
                "Status: " + (report.ready() ? "READY" : "NOT_READY"),
                "Category: " + safeCategory(report.errorCategory())));
    }

    private ProviderHealthReport inspectObserved(Config config, boolean cached,
            InteractiveSession session) {
        String correlation = UUID.randomUUID().toString();
        Instant started = Instant.now();
        ProviderCallTelemetryStore telemetry = workspace == null ? null
                : new ProviderCallTelemetryStore(workspace.resolve(".paicli").resolve("state"));
        if (telemetry != null) telemetry.recordStarted(
                new ProviderCallTelemetryStore.CallStart(correlation,
                        config.effectiveProvider(), config.model(),
                        ProviderCallTelemetryStore.OperationKind.HEALTH,
                        new ProviderCallContext(session.snapshot().sessionId(),
                                session.snapshot().currentTaskId(),
                                session.snapshot().currentRunId(), null), started));
        ProviderHealthReport report;
        try {
            report = health.inspect(config, cached);
        } catch (RuntimeException error) {
            finalizeHealth(telemetry, correlation, started, config, "HEALTH_EXCEPTION",
                    "PROVIDER_REQUEST_FAILURE");
            throw error;
        }
        finalizeHealth(telemetry, correlation, started, config,
                report.ready() ? "HEALTHY" : "NOT_READY",
                report.ready() ? null : safeCategory(report.errorCategory()));
        return report;
    }

    private List<String> discoverModelsObserved(Config config, boolean cached,
            InteractiveSession session) {
        String correlation = UUID.randomUUID().toString();
        Instant started = Instant.now();
        ProviderCallTelemetryStore telemetry = workspace == null ? null
                : new ProviderCallTelemetryStore(workspace.resolve(".paicli").resolve("state"));
        if (telemetry != null) telemetry.recordStarted(
                new ProviderCallTelemetryStore.CallStart(correlation,
                        config.effectiveProvider(), config.model(),
                        ProviderCallTelemetryStore.OperationKind.MODEL_DISCOVERY,
                        new ProviderCallContext(session.snapshot().sessionId(),
                                session.snapshot().currentTaskId(),
                                session.snapshot().currentRunId(), null), started));
        try {
            List<String> models = health.discoverModels(config, cached);
            finalizeObserved(telemetry, correlation, started,
                    ProviderCallTelemetryStore.Lifecycle.SUCCEEDED,
                    models.isEmpty() ? "NO_MODELS" : "MODELS_DISCOVERED", null,
                    "provider-models:");
            return models;
        } catch (RuntimeException error) {
            finalizeObserved(telemetry, correlation, started,
                    ProviderCallTelemetryStore.Lifecycle.FAILED,
                    "MODEL_DISCOVERY_EXCEPTION", "PROVIDER_REQUEST_FAILURE",
                    "provider-models:");
            throw error;
        }
    }

    private static void finalizeHealth(ProviderCallTelemetryStore telemetry,
            String correlation, Instant started, Config config, String outcome,
            String errorCategory) {
        finalizeObserved(telemetry, correlation, started,
                errorCategory == null ? ProviderCallTelemetryStore.Lifecycle.SUCCEEDED
                        : ProviderCallTelemetryStore.Lifecycle.FAILED,
                outcome, errorCategory, "provider-health:",
                ProviderHealthService.observationFingerprint(config));
    }

    private static void finalizeObserved(ProviderCallTelemetryStore telemetry,
            String correlation, Instant started,
            ProviderCallTelemetryStore.Lifecycle lifecycle,
            String outcome, String errorCategory, String diagnosticPrefix) {
        finalizeObserved(telemetry,correlation,started,lifecycle,outcome,errorCategory,
                diagnosticPrefix,null);
    }

    private static void finalizeObserved(ProviderCallTelemetryStore telemetry,
            String correlation, Instant started,
            ProviderCallTelemetryStore.Lifecycle lifecycle,
            String outcome, String errorCategory, String diagnosticPrefix,
            String structuralHash) {
        if (telemetry == null) return;
        Instant ended = Instant.now();
        ProviderCallObservation observation = ProviderCallObservation.notExposed();
        if (errorCategory != null && !"none".equals(errorCategory)) {
            observation = observation.withError(errorCategory, false);
        }
        telemetry.finalizeCall(new ProviderCallTelemetryStore.CallFinish(correlation,
                lifecycle,
                ended, Math.max(0, java.time.Duration.between(started, ended).toMillis()),
                outcome, 0, observation, structuralHash, diagnosticPrefix + correlation));
    }

    private String chooseModel(List<String> models) {
        if (!models.isEmpty()) {
            List<String> shown = models.stream().limit(20).toList();
            StringBuilder promptText = new StringBuilder("Model [");
            for (int i = 0; i < shown.size(); i++) {
                if (i > 0) promptText.append(", ");
                promptText.append(i + 1).append('=').append(shown.get(i));
            }
            promptText.append(", or model ID, Enter=cancel]: ");
            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                String answer = ask(promptText.toString());
                if (answer == null) return null;
                try {
                    int selected = Integer.parseInt(answer);
                    if (selected >= 1 && selected <= shown.size()) return shown.get(selected - 1);
                } catch (NumberFormatException ignored) { }
                try { return ProviderEndpointPolicy.validateModelId(answer); }
                catch (IllegalArgumentException ignored) { }
            }
            return null;
        }
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String manual = ask("No models discovered; enter model ID (Enter=cancel): ");
            if (manual == null) return null;
            try { return ProviderEndpointPolicy.validateModelId(manual); }
            catch (IllegalArgumentException ignored) { }
        }
        return null;
    }

    private SaveScope chooseScope() {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String answer = ask("Save [1=session, 2=user, 3=project, Enter=cancel]: ");
            if (answer == null) return null;
            SaveScope scope = switch (answer.toLowerCase(Locale.ROOT)) {
                case "1", "session" -> SaveScope.SESSION;
                case "2", "user" -> SaveScope.USER;
                case "3", "project" -> SaveScope.PROJECT;
                default -> null;
            };
            if (scope != null) return scope;
        }
        return null;
    }

    private String readEndpoint(String message) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String value = ask(message);
            if (value == null) return null;
            try { return ProviderEndpointPolicy.validate(value).toString(); }
            catch (IllegalArgumentException ignored) { }
        }
        return null;
    }

    private String readEnvironmentName() {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String value = ask("API key environment variable name (Enter=cancel): ");
            if (value == null) return null;
            try { return ProviderEndpointPolicy.validateEnvironmentName(value); }
            catch (IllegalArgumentException ignored) { }
        }
        return null;
    }

    private String ask(String text) {
        String answer = prompt.read(text);
        if (answer == null || answer.isBlank()) return null;
        String clean = answer.trim();
        return clean.length() <= 512
                && clean.chars().noneMatch(value -> value < 0x20 || value == 0x7f)
                ? clean : "<invalid>";
    }

    private static List<String> modelLines(List<String> models) {
        if (models.isEmpty()) return List.of("No models discovered.");
        List<String> lines = new ArrayList<>();
        lines.add("Models:");
        models.stream().limit(32).forEach(model -> lines.add("- " + model));
        if (models.size() > 32) lines.add("[truncated]");
        return lines;
    }

    private static ShellCommandResult cancelled() {
        return ShellCommandResult.continueWith("PROVIDER_CANCELLED", List.of("Provider configuration cancelled."));
    }
    private static ShellCommandResult rejected(String message) {
        return ShellCommandResult.continueWith("PROVIDER_REJECTED", List.of(safe(message)));
    }
    private static String safe(String value) {
        if (value == null || value.isBlank()) return "none";
        String clean = value.replace('\r', ' ').replace('\n', ' ')
                .replaceAll("(?i)(bearer|token|password|secret|api[_-]?key)(?:\\s*[:=]\\s*|\\s+)\\S+",
                        "$1=<redacted>");
        return clean.substring(0, Math.min(clean.length(), 192));
    }
    private static String safeCategory(String value) {
        if (value == null || value.isBlank()) return "none";
        return value != null && value.matches("[A-Z0-9_]{1,64}") ? value : "PROVIDER_DIAGNOSTIC_FAILED";
    }
    private static String yes(boolean value) { return value ? "yes" : "no"; }
    private enum SaveScope { SESSION, USER, PROJECT }
}
