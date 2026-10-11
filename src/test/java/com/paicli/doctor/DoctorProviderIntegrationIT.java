package com.paicli.doctor;

import com.paicli.config.Config;
import com.paicli.config.ConfigLoader;
import com.paicli.interactive.command.SlashCommandParser;
import com.paicli.interactive.command.SlashCommandRegistry;
import com.paicli.interactive.command.SlashCommandRouter;
import com.paicli.interactive.history.InteractiveHistoryStore;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.provider.ProviderHealthService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class DoctorProviderIntegrationIT {
    @TempDir Path temp;
    private HttpServer server;

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test void cliAndSlashDoctorRenderTheSameProductionChecks() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/tags", exchange -> {
            byte[] bytes = "{\"models\":[{\"name\":\"qwen3:8b\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        Path workspace = Files.createDirectories(temp.resolve("workspace"));
        Config config = Config.defaults().withProvider("ollama", "qwen3:8b",
                "http://127.0.0.1:" + server.getAddress().getPort(), null);
        DoctorService service = new DoctorService(workspace,
                () -> new ConfigLoader.Resolution(config, ConfigLoader.Source.PROJECT),
                new ProviderHealthService());
        String rendered = render(service);
        assertTrue(rendered.contains("Provider ready"));
        assertTrue(rendered.contains("PASS"));
        assertTrue(rendered.contains("Config source"));

        InteractiveSession session = new InteractiveSession("session-1", workspace,
                "ollama", "qwen3:8b", Config.MODE_ON_REQUEST,
                InteractiveSession.TerminalMode.PLAIN, Instant.now());
        session.start();
        SlashCommandRegistry registry = new SlashCommandRegistry();
        var result = new SlashCommandRouter("1.1.0", registry,
                new SlashCommandParser(registry), () -> rendered.lines().toList())
                .route("/doctor", session,
                        new InteractiveHistoryStore(temp.resolve("history")));
        assertEquals(rendered.lines().toList(), result.lines());
    }

    private static String render(DoctorService service) {
        StringWriter output = new StringWriter();
        new DoctorCommand(service).execute(new PrintWriter(output, true));
        return output.toString().stripTrailing();
    }
}
