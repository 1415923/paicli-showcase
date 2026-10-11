package com.paicli.provider;

import com.paicli.config.Config;
import com.paicli.credential.CredentialRef;
import com.paicli.credential.CredentialStore;
import com.paicli.credential.ProviderCredentialResolver;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ProviderHealthServiceIT {
    private HttpServer server;

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test void discoversOllamaModelsAndExpiresCache() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = server("/api/tags", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, "{\"models\":[{\"name\":\"qwen3:8b\"},{\"name\":\"coder\"}]}");
        });
        MutableClock clock = new MutableClock();
        ProviderHealthService service = service(Map.of(), clock, Duration.ofSeconds(10));
        Config config = Config.defaults().withProvider(
                "ollama", "qwen3:8b", endpoint(), null);

        assertTrue(service.inspect(config, true).ready());
        assertTrue(service.inspect(config, true).ready());
        assertEquals(1, requests.get());
        clock.advance(Duration.ofSeconds(11));
        assertTrue(service.inspect(config, true).ready());
        assertEquals(2, requests.get());
    }

    @Test void openAiUsesEnvironmentSecretOnlyOnRequestAndBoundsFailures() throws Exception {
        AtomicInteger modelRequests = new AtomicInteger();
        server = server("/v1/models", exchange -> {
            modelRequests.incrementAndGet();
            assertEquals("Bearer SECRET_SENTINEL", exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"data\":[{\"id\":\"local-model\"}]}");
        });
        ProviderHealthService service = service(Map.of("LOCAL_API_KEY", "SECRET_SENTINEL"),
                new MutableClock(), Duration.ofMinutes(1));
        Config config = Config.defaults().withProvider(
                "openai", "local-model", endpoint(), "LOCAL_API_KEY");
        ProviderHealthReport report = service.inspect(config, false);
        assertTrue(report.ready());
        assertFalse(report.toString().contains("SECRET_SENTINEL"));
        assertEquals(1, modelRequests.get());

        ProviderHealthReport missing = service(Map.of(), new MutableClock(), Duration.ofMinutes(1))
                .inspect(config, false);
        assertFalse(missing.ready());
        assertEquals("PROVIDER_CREDENTIAL_MISSING", missing.errorCategory());
        assertEquals(1, modelRequests.get(),
                "missing unified credential must fail before any network request");
        assertFalse(missing.toString().contains("SECRET_SENTINEL"));
        assertTrue(missing.modelConfigured(),
                "DeepSeek's preset model must remain configured on readiness failures");
    }

    @Test void deepSeekPresetUsesVersionedModelsEndpointWithoutDuplicateV1() throws Exception {
        server=server("/v1/models",exchange->{
            assertEquals("Bearer DEEPSEEK_SENTINEL",
                    exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange,200,"{\"data\":[{\"id\":\"deepseek-v4-flash\"}]}");
        });
        ProviderHealthService service=service(Map.of("DEEPSEEK_API_KEY","DEEPSEEK_SENTINEL"),
                new MutableClock(),Duration.ofMinutes(1));
        Config config=Config.defaults().withProvider("openai",null,
                endpoint()+"/v1","DEEPSEEK_API_KEY").withProviderPreset("deepseek");
        ProviderHealthReport report=service.inspect(config,false);
        assertTrue(report.ready(),report.toString());
        assertFalse(report.toString().contains("DEEPSEEK_SENTINEL"));
    }

    @Test void credentialReferenceIsPartOfDiscoveryCacheIdentity() throws Exception {
        String secretA="CACHE_IDENTITY_SECRET_A";
        String secretB="CACHE_IDENTITY_SECRET_B";
        AtomicInteger requests=new AtomicInteger();
        AtomicInteger rejectedCredentialRequests=new AtomicInteger();
        server=server("/v1/models",exchange->{
            requests.incrementAndGet();
            String authorization=exchange.getRequestHeaders().getFirst("Authorization");
            if (("Bearer "+secretA).equals(authorization)) {
                respond(exchange,200,"{\"data\":[{\"id\":\"local-model\"}]}");
            } else {
                if (("Bearer "+secretB).equals(authorization)) {
                    rejectedCredentialRequests.incrementAndGet();
                }
                respond(exchange,401,"{\"error\":\"unauthorized\"}");
            }
        });
        InMemoryCredentialStore store=new InMemoryCredentialStore();
        String referenceA="PaiCLI/provider/cache-test/a";
        String referenceB="PaiCLI/provider/cache-test/b";
        store.put(new CredentialRef(referenceA),secretA.toCharArray());
        store.put(new CredentialRef(referenceB),secretB.toCharArray());
        ProviderHealthService service=new ProviderHealthService(HttpClient.newHttpClient(),
                new ProviderCredentialResolver(ignored->null,store),new MutableClock(),
                Duration.ofSeconds(2),Duration.ofMinutes(1));
        Config base=Config.defaults().withProvider(
                "openai","local-model",endpoint(),"SHARED_API_KEY");

        ProviderHealthReport ready=service.inspect(base.withCredentialRef(referenceA),true);
        ProviderHealthReport rejected=service.inspect(base.withCredentialRef(referenceB),true);

        assertTrue(ready.ready(),ready.toString());
        assertFalse(rejected.ready(),rejected.toString());
        assertEquals("PROVIDER_HTTP_401",rejected.errorCategory());
        assertEquals(2,requests.get(),"credential B must not reuse credential A's READY cache entry");
        assertEquals(1,rejectedCredentialRequests.get(),
                "credential B must reach the supplier and be independently authenticated");
        String publicProjection=ready+"\n"+rejected;
        assertFalse(publicProjection.contains(secretA));
        assertFalse(publicProjection.contains(secretB));
    }

    @Test void timeoutIsBoundedAndCategorized() throws Exception {
        server = server("/api/tags", exchange -> {
            try { Thread.sleep(250); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            respond(exchange, 200, "{\"models\":[]}");
        });
        ProviderHealthService service = new ProviderHealthService(
                HttpClient.newHttpClient(), ignored -> null, new MutableClock(),
                Duration.ofMillis(50), Duration.ofMinutes(1));
        ProviderHealthReport report = service.inspect(Config.defaults().withProvider(
                "ollama", "manual", endpoint(), null), false);
        assertFalse(report.ready());
        assertEquals("PROVIDER_TIMEOUT", report.errorCategory());
    }

    private ProviderHealthService service(Map<String, String> environment,
            Clock clock, Duration ttl) {
        return new ProviderHealthService(HttpClient.newHttpClient(), environment::get,
                clock, Duration.ofSeconds(2), ttl);
    }

    private HttpServer server(String path, com.sun.net.httpserver.HttpHandler handler) throws Exception {
        HttpServer value = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        value.createContext(path, handler);
        value.start();
        return value;
    }

    private String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange,
            int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        public ZoneId getZone() { return ZoneId.of("UTC"); }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }

    private static final class InMemoryCredentialStore implements CredentialStore {
        private final Map<CredentialRef,char[]> values=new HashMap<>();
        public void put(CredentialRef reference,char[] secret) {
            values.put(reference,secret.clone());
        }
        public Optional<char[]> get(CredentialRef reference) {
            char[] value=values.get(reference);
            return value==null?Optional.empty():Optional.of(value.clone());
        }
        public boolean delete(CredentialRef reference) {
            return values.remove(reference)!=null;
        }
        public Status status(CredentialRef reference) {
            return new Status(values.containsKey(reference)?State.PRESENT:State.MISSING,
                    "in-memory-test-store");
        }
    }
}
