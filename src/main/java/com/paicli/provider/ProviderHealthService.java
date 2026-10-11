package com.paicli.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.Config;
import com.paicli.credential.CredentialLease;
import com.paicli.credential.CredentialStoreException;
import com.paicli.credential.ProviderCredentialResolver;
import com.paicli.credential.UnsupportedCredentialStore;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Bounded non-generation provider diagnostics and model discovery. */
public final class ProviderHealthService {
    static final int MAX_RESPONSE_BYTES = 256 * 1024;
    static final int MAX_MODELS = 256;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient client;
    private final ProviderCredentialResolver credentials;
    private final Clock clock;
    private final Duration timeout;
    private final Duration cacheTtl;
    private final Map<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();

    public ProviderHealthService() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),
                ProviderCredentialResolver.systemDefault(),Clock.systemUTC(),
                Duration.ofSeconds(5),Duration.ofMinutes(5));
    }

    public ProviderHealthService(HttpClient client, Function<String, String> environment,
            Clock clock, Duration timeout, Duration cacheTtl) {
        this(client,new ProviderCredentialResolver(environment,
                new UnsupportedCredentialStore()),clock,timeout,cacheTtl);
    }

    public ProviderHealthService(HttpClient client,ProviderCredentialResolver credentials,
            Clock clock,Duration timeout,Duration cacheTtl) {
        this.client = client;
        this.credentials=credentials;
        this.clock = clock;
        this.timeout = timeout;
        this.cacheTtl = cacheTtl;
    }

    public ProviderHealthReport inspect(Config config, boolean useCache) {
        if (!config.hasConfiguredProvider()) {
            return new ProviderHealthReport(Config.PROVIDER_UNCONFIGURED, "none", true,
                    false, false, false, true, false, List.of(), "PROVIDER_NOT_CONFIGURED");
        }
        String provider = config.effectiveProvider();
        if (Config.PROVIDER_MOCK.equals(provider)) {
            return new ProviderHealthReport(provider, "unsupported", true, false,
                    false, false, false, false, List.of(),
                    ProviderFactory.MOCK_UNSUPPORTED_CODE);
        }
        if (!Config.PROVIDER_OLLAMA.equals(provider)
                && !Config.PROVIDER_OPENAI.equals(provider)) {
            return failed(provider, "invalid", false, "PROVIDER_UNKNOWN");
        }
        String configuredModel=effectiveConfiguredModel(config);
        boolean modelConfigured=configuredModel!=null&&!configuredModel.isBlank();
        String endpoint = endpoint(config);
        URI base;
        try { base = ProviderEndpointPolicy.validate(endpoint); }
        catch (IllegalArgumentException error) {
            return failed(provider, "invalid", false, "ENDPOINT_INVALID");
        }
        boolean envPresent = true;
        String environmentName = "";
        if (Config.PROVIDER_OPENAI.equals(provider)) {
            environmentName=ProviderCredentialResolver.environmentName(config);
            try { ProviderEndpointPolicy.validateEnvironmentName(environmentName); }
            catch (IllegalArgumentException error) {
                return new ProviderHealthReport(provider, safeEndpoint(base), true, false,
                        modelConfigured, false, false, false, List.of(), "ENVIRONMENT_NAME_INVALID");
            }
            try { envPresent=credentials.status(config).present(); }
            catch (RuntimeException error) {
                return new ProviderHealthReport(provider, safeEndpoint(base), true, false,
                        modelConfigured,false,false,false,List.of(),"CREDENTIAL_LOOKUP_FAILED");
            }
            if (!envPresent) {
                return new ProviderHealthReport(provider, safeEndpoint(base), true, false,
                        modelConfigured,false,false,false,List.of(),"PROVIDER_CREDENTIAL_MISSING");
            }
        }
        OpenAICompatiblePreset preset=Config.PROVIDER_OPENAI.equals(provider)
                ?OpenAICompatiblePreset.from(config):null;
        String credentialIdentity=Config.PROVIDER_OPENAI.equals(provider)
                ?ProviderCredentialResolver.reference(config).value():"none";
        CacheKey key = new CacheKey(provider, base.toString(), environmentName,
                credentialIdentity,preset==null?"none":preset.name());
        List<String> models;
        try {
            if(Config.PROVIDER_OPENAI.equals(provider)){
                try(CredentialLease credential=credentials.resolve(config)){
                    String transientSecret=new String(credential.value());
                    models=useCache?cached(key,()->discover(provider,base,transientSecret,preset))
                            :discover(provider,base,transientSecret,preset);
                }
            }else models=useCache?cached(key,()->discover(provider,base,null,preset))
                    :discover(provider,base,null,preset);
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return new ProviderHealthReport(provider, safeEndpoint(base), true, false,
                    modelConfigured, false, envPresent, false, List.of(), category(error));
        }
        boolean discovered = modelConfigured && models.contains(configuredModel);
        return new ProviderHealthReport(provider, safeEndpoint(base), true, true,
                modelConfigured, discovered, envPresent, modelConfigured, models,
                discovered ? "" : modelConfigured ? "MODEL_NOT_DISCOVERED" : "MODEL_NOT_CONFIGURED");
    }

    public List<String> discoverModels(Config config, boolean useCache) {
        ProviderHealthReport report = inspect(config, useCache);
        if (!report.reachable()) throw new IllegalStateException(report.errorCategory());
        return report.models();
    }

    /** Secret-free identity for retaining a health observation only for the checked config. */
    public static String observationFingerprint(Config config) {
        String environmentName = config.apiKeyEnv() == null
                ? ("deepseek".equals(config.providerPreset())
                ? "DEEPSEEK_API_KEY" : "OPENAI_API_KEY") : config.apiKeyEnv();
        String canonical = String.join("\n",
                config.effectiveProvider(),
                effectiveConfiguredModel(config) == null ? "" : effectiveConfiguredModel(config),
                endpoint(config),
                Config.PROVIDER_OPENAI.equals(config.effectiveProvider())
                        ? environmentName : "",
                config.credentialRef()==null?"":config.credentialRef());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private List<String> discover(String provider, URI base, String secret,
            OpenAICompatiblePreset preset) throws Exception {
        URI target = Config.PROVIDER_OLLAMA.equals(provider)
                ?URI.create(base.toString().replaceAll("/+$","")+"/api/tags")
                :preset.endpoint(base,true);
        HttpRequest.Builder request = HttpRequest.newBuilder(target).GET().timeout(timeout)
                .header("Accept", "application/json");
        if (Config.PROVIDER_OPENAI.equals(provider)) {
            request.header("Authorization", "Bearer " + secret);
        }
        HttpResponse<InputStream> response = client.send(
                request.build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) throw new IOException("PROVIDER_RESPONSE_TOO_LARGE");
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("PROVIDER_HTTP_" + response.statusCode());
            }
            JsonNode root = MAPPER.readTree(bytes);
            JsonNode array = Config.PROVIDER_OLLAMA.equals(provider)
                    ? root.path("models") : root.path("data");
            if (!array.isArray()) throw new IOException("MODEL_CATALOG_MALFORMED");
            LinkedHashSet<String> unique = new LinkedHashSet<>();
            for (JsonNode item : array) {
                String model = Config.PROVIDER_OLLAMA.equals(provider)
                        ? item.path("name").asText(null) : item.path("id").asText(null);
                if (model == null) continue;
                try { unique.add(ProviderEndpointPolicy.validateModelId(model)); }
                catch (IllegalArgumentException ignored) { }
                if (unique.size() >= MAX_MODELS) break;
            }
            return unique.stream().sorted().toList();
        }
    }

    private List<String> cached(CacheKey key, CheckedSupplier supplier) throws Exception {
        Instant now = clock.instant();
        CacheEntry current = cache.get(key);
        if (current != null && now.isBefore(current.expiresAt())) return current.models();
        List<String> models = List.copyOf(supplier.get());
        cache.put(key, new CacheEntry(models, now.plus(cacheTtl)));
        return models;
    }

    private static String endpoint(Config config) {
        if (config.baseUrl() != null) return config.baseUrl();
        return Config.PROVIDER_OLLAMA.equals(config.effectiveProvider())
                ? "http://localhost:11434" : "deepseek".equals(config.providerPreset())
                ?"https://api.deepseek.com":"https://api.openai.com";
    }

    private static String effectiveConfiguredModel(Config config){
        if(config.model()!=null&&!config.model().isBlank())return config.model();
        return Config.PROVIDER_OPENAI.equals(config.effectiveProvider())
                &&"deepseek".equals(config.providerPreset())
                ?"deepseek-v4-flash":null;
    }

    private static ProviderHealthReport failed(String provider, String endpoint,
            boolean valid, String category) {
        return new ProviderHealthReport(provider, endpoint, valid, false,
                false, false, false, false, List.of(), category);
    }

    private static String safeEndpoint(URI uri) {
        int port = uri.getPort();
        return uri.getScheme().toLowerCase() + "://" + uri.getHost()
                + (port < 0 ? "" : ":" + port);
    }

    private static String category(Exception error) {
        if (error instanceof java.net.http.HttpTimeoutException) return "PROVIDER_TIMEOUT";
        if (error instanceof java.net.ConnectException) return "PROVIDER_UNREACHABLE";
        String value = error.getMessage();
        if (value != null && value.matches("[A-Z0-9_]{1,64}")) return value;
        return "PROVIDER_DIAGNOSTIC_FAILED";
    }

    private record CacheKey(String provider, String endpoint, String environmentName,
                            String credentialIdentity,String preset) { }
    private record CacheEntry(List<String> models, Instant expiresAt) { }
    @FunctionalInterface private interface CheckedSupplier { List<String> get() throws Exception; }
}
