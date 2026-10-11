package com.paicli.interactive.provider;

import com.paicli.config.Config;
import com.paicli.config.ConfigLoader;
import com.paicli.config.ProviderConfigStore;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.provider.ProviderHealthService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;

import static org.junit.jupiter.api.Assertions.*;

class ProviderOnboardingIT {
    @TempDir Path temp;
    private HttpServer server;

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test void ollamaOnboardingPersistsProjectSwitchesModelAndClearsProviderOnly() throws Exception {
        start("{\"models\":[{\"name\":\"qwen3:8b\"},{\"name\":\"coder\"}]}");
        Path workspace = Files.createDirectories(temp.resolve("workspace"));
        Path home = Files.createDirectories(temp.resolve("home"));
        Path configPath = Files.createDirectories(workspace.resolve(".paicli")).resolve("config.json");
        Files.writeString(configPath, "{\"future_setting\":true}\n");
        ConfigLoader loader = new ConfigLoader(workspace, home);
        InteractiveProviderState state = new InteractiveProviderState(loader.resolve());
        InteractiveSession session = session(workspace);
        ArrayDeque<String> input = new ArrayDeque<>();
        // Discovered model IDs are sorted for deterministic menus: coder, qwen3:8b.
        input.add(endpoint()); input.add("2"); input.add("3");
        InteractiveProviderCommandService service = service(loader, state, input, workspace, home);

        assertEquals("PROVIDER_READY", service.provider(
                java.util.List.of("set", "ollama"), session).code());
        assertEquals("qwen3:8b", state.config().model());
        assertEquals(ConfigLoader.Source.PROJECT, state.source());
        assertTrue(Files.readString(configPath).contains("future_setting"));

        assertEquals("PROVIDER_READY", service.model(
                java.util.List.of("set", "coder"), session).code());
        assertEquals("coder", state.config().model());

        assertEquals("PROVIDER_CLEARED", service.provider(
                java.util.List.of("clear"), session).code());
        String cleared = Files.readString(configPath);
        assertTrue(cleared.contains("future_setting"));
        assertFalse(cleared.contains("\"provider\""));
    }

    @Test void openAiStoresOnlyEnvironmentNameAndSupportsManualModelWhenCatalogEmpty() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> respond(exchange, "{\"data\":[]}"));
        server.start();
        Path workspace = Files.createDirectories(temp.resolve("workspace"));
        Path home = Files.createDirectories(temp.resolve("home"));
        ConfigLoader loader = new ConfigLoader(workspace, home);
        InteractiveProviderState state = new InteractiveProviderState(loader.resolve());
        InteractiveSession session = session(workspace);
        ArrayDeque<String> input = new ArrayDeque<>();
        input.add(endpoint()); input.add("PATH"); input.add("manual-model"); input.add("2");
        InteractiveProviderCommandService service = service(loader, state, input, workspace, home);

        assertEquals("PROVIDER_READY", service.provider(
                java.util.List.of("set", "openai"), session).code());
        String saved = Files.readString(home.resolve(".paicli/config.json"));
        assertTrue(saved.contains("\"api_key_env\" : \"PATH\""));
        String pathValue = System.getenv("PATH");
        assertNotNull(pathValue);
        assertFalse(saved.contains(pathValue));
        assertEquals("manual-model", state.config().model());
    }

    @Test void eofCancelsWithoutChangingSession() throws Exception {
        start("{\"models\":[]}");
        Path workspace = Files.createDirectories(temp.resolve("workspace"));
        Path home = Files.createDirectories(temp.resolve("home"));
        ConfigLoader loader = new ConfigLoader(workspace, home);
        InteractiveProviderState state = new InteractiveProviderState(loader.resolve());
        InteractiveProviderCommandService service = service(loader, state,
                new ArrayDeque<>(), workspace, home);
        assertEquals("PROVIDER_CANCELLED", service.provider(java.util.List.of(), session(workspace)).code());
        assertEquals(Config.PROVIDER_UNCONFIGURED, state.config().effectiveProvider());
    }

    @Test void sessionScopeDoesNotWriteUserOrProjectConfig() throws Exception {
        start("{\"models\":[{\"name\":\"session-model\"}]}");
        Path workspace = Files.createDirectories(temp.resolve("workspace"));
        Path home = Files.createDirectories(temp.resolve("home"));
        ConfigLoader loader = new ConfigLoader(workspace, home);
        InteractiveProviderState state = new InteractiveProviderState(loader.resolve());
        ArrayDeque<String> input = new ArrayDeque<>();
        input.add(endpoint()); input.add("1"); input.add("1");
        var result = service(loader, state, input, workspace, home).provider(
                java.util.List.of("set", "ollama"), session(workspace));
        assertEquals("PROVIDER_READY", result.code());
        assertEquals(ConfigLoader.Source.SESSION, state.source());
        assertFalse(Files.exists(workspace.resolve(".paicli/config.json")));
        assertFalse(Files.exists(home.resolve(".paicli/config.json")));
    }

    private InteractiveProviderCommandService service(ConfigLoader loader,
            InteractiveProviderState state, ArrayDeque<String> input,
            Path workspace, Path home) {
        return new InteractiveProviderCommandService(state, loader,
                new ProviderConfigStore(workspace, home), new ProviderHealthService(),
                ignored -> input.pollFirst());
    }

    private InteractiveSession session(Path workspace) {
        InteractiveSession value = new InteractiveSession("session-1", workspace,
                Config.PROVIDER_UNCONFIGURED, null, Config.MODE_ON_REQUEST,
                InteractiveSession.TerminalMode.PLAIN, Instant.now());
        value.start();
        return value;
    }

    private void start(String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", exchange -> respond(exchange, body));
        server.start();
    }
    private String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
