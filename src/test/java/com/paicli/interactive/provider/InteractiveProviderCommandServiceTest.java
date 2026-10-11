package com.paicli.interactive.provider;

import com.paicli.config.Config;
import com.paicli.config.ConfigLoader;
import com.paicli.config.ProviderConfigStore;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.provider.ProviderHealthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class InteractiveProviderCommandServiceTest {
    @TempDir Path temp;

    @Test void statusCancelAndMissingOpenAiEnvironmentAreFailClosed() throws Exception {
        Fixture fixture = fixture(new ArrayDeque<>());
        assertTrue(fixture.service.provider(java.util.List.of("status"), fixture.session)
                .lines().stream().anyMatch(line -> line.contains("unconfigured")));
        assertTrue(fixture.service.provider(java.util.List.of(), fixture.session)
                .lines().stream().anyMatch(line -> line.contains("cancelled")));

        ArrayDeque<String> answers = new ArrayDeque<>();
        answers.add("http://localhost:9");
        answers.add("MISSING_TEST_KEY");
        fixture = fixture(answers);
        var result = fixture.service.provider(java.util.List.of("set", "openai"), fixture.session);
        assertTrue(result.lines().stream().anyMatch(line -> line.contains("PROVIDER_CREDENTIAL_MISSING")),
                () -> "unexpected bounded result: " + result.lines());
        assertEquals(Config.PROVIDER_UNCONFIGURED, fixture.state.config().effectiveProvider());
    }

    @Test void providerStatusUsesUnifiedSnapshotWithoutHealthOrModelNetworkCall()
            throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("status-workspace"));
        Path home = Files.createDirectories(temp.resolve("status-home"));
        ConfigLoader loader = new ConfigLoader(workspace, home);
        InteractiveProviderState state = new InteractiveProviderState(new ConfigLoader.Resolution(
                Config.defaults().withProvider("ollama", "model",
                        "http://127.0.0.1:9", null), ConfigLoader.Source.SESSION));
        AtomicInteger requests = new AtomicInteger();
        ProviderHealthService health = new ProviderHealthService(
                new CountingHttpClient(requests), ignored -> null, Clock.systemUTC(),
                Duration.ofMillis(100), Duration.ofMinutes(1));
        InteractiveProviderCommandService service = new InteractiveProviderCommandService(
                state, loader, new ProviderConfigStore(workspace, home), health,
                ignored -> null, workspace);
        InteractiveSession session = new InteractiveSession("session-status", workspace,
                "ollama", "model", Config.MODE_ON_REQUEST,
                InteractiveSession.TerminalMode.PLAIN, Instant.now());
        session.start();

        var result = service.provider(java.util.List.of("status"), session);

        assertEquals("PROVIDER_STATUS", result.code());
        assertEquals(0, requests.get());
        String rendered = String.join("\n", result.lines());
        assertTrue(result.lines().getFirst().contains("Provider Status"), rendered);
        assertTrue(rendered.contains("  Provider"), rendered);
        assertTrue(rendered.contains("  Health"), rendered);
        assertFalse(rendered.contains("Projection:"), rendered);
        assertFalse(rendered.contains("Diagnostic reference:"), rendered);
        assertFalse(rendered.contains("Session input tokens:"), rendered);
        assertTrue(result.lines().stream().allMatch(line ->
                line.isEmpty() || line.startsWith("  ") || line.matches("─+.*")), rendered);
    }

    @Test void activeTaskBlocksSwitchAndFailedCandidateKeepsOldState() throws Exception {
        ArrayDeque<String> answers = new ArrayDeque<>();
        answers.add("http://127.0.0.1:9");
        ConfigLoader loader = loader();
        InteractiveProviderState state = new InteractiveProviderState(new ConfigLoader.Resolution(
                Config.defaults().withProvider("ollama", "old-model", "http://127.0.0.1:9", null),
                ConfigLoader.Source.SESSION));
        InteractiveSession session = session("ollama", "old-model");
        InteractiveProviderCommandService service = service(loader, state, answers);

        session.startTask("task-1");
        assertTrue(service.provider(java.util.List.of("clear"), session).lines().getFirst()
                .contains("cannot be cleared"));
        session.finishTask("task-1");
        service.provider(java.util.List.of("set", "ollama"), session);
        assertEquals("old-model", state.config().model());
    }

    @ParameterizedTest
    @ValueSource(strings = {Config.PROVIDER_OLLAMA, Config.PROVIDER_OPENAI})
    void activeTaskRejectsProviderSetBeforePromptConstructionDiscoveryOrPersistence(String provider)
            throws Exception {
        ActiveFixture fixture = activeFixture();
        Config beforeConfig = fixture.state.config();
        var beforeSession = fixture.session.snapshot();

        var result = fixture.service.provider(java.util.List.of("set", provider), fixture.session);

        assertEquals("PROVIDER_REJECTED", result.code());
        assertTrue(result.lines().stream().anyMatch(line -> line.contains("cannot be changed")));
        assertEquals(beforeConfig, fixture.state.config());
        assertEquals("ollama", fixture.state.config().effectiveProvider());
        assertEquals("old-model", fixture.state.config().model());
        assertEquals(beforeSession, fixture.session.snapshot());
        assertEquals("task-1", fixture.session.snapshot().currentTaskId());
        assertNoProviderSideEffects(fixture);
    }

    @Test void activeTaskRejectsModelSetBeforeConstructionDiscoveryOrPersistence() throws Exception {
        ActiveFixture fixture = activeFixture();
        Config beforeConfig = fixture.state.config();
        var beforeSession = fixture.session.snapshot();

        var result = fixture.service.model(java.util.List.of("set", "valid-model-id"), fixture.session);

        assertEquals("PROVIDER_REJECTED", result.code());
        assertTrue(result.lines().stream().anyMatch(line -> line.contains("cannot be changed")));
        assertEquals(beforeConfig, fixture.state.config());
        assertEquals("ollama", fixture.state.config().effectiveProvider());
        assertEquals("old-model", fixture.state.config().model());
        assertEquals(beforeSession, fixture.session.snapshot());
        assertEquals("task-1", fixture.session.snapshot().currentTaskId());
        assertNoProviderSideEffects(fixture);
    }

    private Fixture fixture(ArrayDeque<String> answers) throws Exception {
        ConfigLoader loader = loader();
        InteractiveProviderState state = new InteractiveProviderState(loader.resolve());
        InteractiveSession session = session(Config.PROVIDER_UNCONFIGURED, null);
        return new Fixture(state, session, service(loader, state, answers));
    }

    private InteractiveProviderCommandService service(ConfigLoader loader,
            InteractiveProviderState state, ArrayDeque<String> answers) {
        ProviderHealthService health = new ProviderHealthService(
                HttpClient.newHttpClient(), ignored -> null, Clock.systemUTC(),
                Duration.ofMillis(100), Duration.ofMinutes(1));
        return new InteractiveProviderCommandService(state, loader,
                new ProviderConfigStore(loader.projectConfigPath().getParent().getParent(), temp.resolve("home")),
                health, ignored -> answers.pollFirst());
    }

    private ActiveFixture activeFixture() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("active-workspace-" + System.nanoTime()));
        Path home = Files.createDirectories(temp.resolve("active-home-" + System.nanoTime()));
        ConfigLoader loader = new ConfigLoader(workspace, home);
        InteractiveProviderState state = new InteractiveProviderState(new ConfigLoader.Resolution(
                Config.defaults().withProvider("ollama", "old-model", "http://127.0.0.1:9", null),
                ConfigLoader.Source.SESSION));
        InteractiveSession session = new InteractiveSession("session-active", workspace,
                "ollama", "old-model", Config.MODE_ON_REQUEST,
                InteractiveSession.TerminalMode.PLAIN, Instant.now());
        session.start();
        session.startTask("task-1");
        AtomicInteger prompts = new AtomicInteger();
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger environmentLookups = new AtomicInteger();
        ProviderHealthService health = new ProviderHealthService(
                new CountingHttpClient(requests), ignored -> {
                    environmentLookups.incrementAndGet();
                    return "SECRET_SENTINEL";
                }, Clock.systemUTC(), Duration.ofMillis(100), Duration.ofMinutes(1));
        InteractiveProviderCommandService service = new InteractiveProviderCommandService(
                state, loader, new ProviderConfigStore(workspace, home), health, ignored -> {
                    prompts.incrementAndGet();
                    return "unexpected";
                });
        return new ActiveFixture(state, session, service, workspace, home,
                prompts, requests, environmentLookups);
    }

    private static void assertNoProviderSideEffects(ActiveFixture fixture) {
        assertEquals(0, fixture.prompts.get(), "active-task rejection must not prompt");
        assertEquals(0, fixture.requests.get(), "active-task rejection must not issue HTTP");
        assertEquals(0, fixture.environmentLookups.get(),
                "active-task rejection must not resolve an API key environment variable");
        assertFalse(Files.exists(fixture.workspace.resolve(".paicli/config.json")));
        assertFalse(Files.exists(fixture.home.resolve(".paicli/config.json")));
    }

    private ConfigLoader loader() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("workspace-" + System.nanoTime()));
        Path home = Files.createDirectories(temp.resolve("home"));
        return new ConfigLoader(workspace, home);
    }

    private InteractiveSession session(String provider, String model) {
        InteractiveSession session = new InteractiveSession("session-1", temp,
                provider, model, Config.MODE_ON_REQUEST,
                InteractiveSession.TerminalMode.PLAIN, Instant.now());
        session.start();
        return session;
    }

    private record Fixture(InteractiveProviderState state, InteractiveSession session,
            InteractiveProviderCommandService service) { }

    private record ActiveFixture(InteractiveProviderState state, InteractiveSession session,
            InteractiveProviderCommandService service, Path workspace, Path home,
            AtomicInteger prompts, AtomicInteger requests, AtomicInteger environmentLookups) { }

    private static final class CountingHttpClient extends HttpClient {
        private final AtomicInteger requests;

        private CountingHttpClient(AtomicInteger requests) { this.requests = requests; }

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { return null; }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) throws IOException {
            requests.incrementAndGet();
            throw new AssertionError("active-task rejection issued an HTTP request");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler) {
            requests.incrementAndGet();
            return CompletableFuture.failedFuture(
                    new AssertionError("active-task rejection issued an HTTP request"));
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> responseBodyHandler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, responseBodyHandler);
        }
    }
}
