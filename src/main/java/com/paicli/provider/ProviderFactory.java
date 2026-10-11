package com.paicli.provider;

import com.paicli.config.Config;
import com.paicli.credential.ProviderCredentialResolver;
import com.paicli.credential.ProviderCredentialSource;

/**
 * Creates the appropriate ModelProvider based on configuration.
 * <p>
 * Semantic rules:
 * <ul>
 *   <li>No provider set → fail closed with configuration guidance</li>
 *   <li>Explicit "mock" → rejected as development-only</li>
 *   <li>Explicit "ollama" → OllamaProvider (never falls back to mock)</li>
 *   <li>Explicit "openai" → OpenAICompatibleProvider (never falls back to mock)</li>
 *   <li>Unknown provider → IllegalArgumentException (fail-fast)</li>
 * </ul>
 * <p>
 * When a real provider cannot connect or has missing config, the provider itself
 * returns typed Provider failures — ProviderFactory does NOT mask these with mock.
 */
public class ProviderFactory {
    public static final String MOCK_UNSUPPORTED_CODE = "MOCK_PROVIDER_UNSUPPORTED";
    public static final String MOCK_UNSUPPORTED_MESSAGE =
            "Mock provider is development-only and unsupported for public runtime tasks. "
                    + "Choose Ollama or an OpenAI-compatible provider.";

    /** Enforces the provider policy shared by all public runtime entry points. */
    public static void requirePublicRuntimeProvider(String providerName) {
        if (Config.PROVIDER_MOCK.equals(providerName)) {
            throw new UnsupportedRuntimeProviderException(
                    MOCK_UNSUPPORTED_CODE, MOCK_UNSUPPORTED_MESSAGE);
        }
    }

    /**
     * Create a ModelProvider. Throws on unknown provider names.
     *
     * @param config application configuration
     * @return the configured provider
     * @throws IllegalArgumentException if provider name is unknown
     */
    public static ModelProvider create(Config config) {
        return create(config,ProviderCredentialResolver.systemDefault());
    }

    public static ModelProvider create(Config config,ProviderCredentialResolver credentials) {
        if (!config.hasConfiguredProvider()) {
            throw new IllegalStateException("No production provider is configured. "
                    + "Configure Ollama/OpenAI in .paicli/config.json, "
                    + "or run `paicli doctor` for details.");
        }
        String providerName = config.effectiveProvider();
        requirePublicRuntimeProvider(providerName);

        return switch (providerName) {
            case Config.PROVIDER_OLLAMA -> {
                String model = ProviderEndpointPolicy.validateModelId(
                        config.model() != null ? config.model() : "qwen3:14b");
                String baseUrl = ProviderEndpointPolicy.validate(
                        config.baseUrl() != null ? config.baseUrl() : "http://localhost:11434").toString();
                yield new OllamaProvider(baseUrl, model, config.effectiveSystemPrompt(),
                        config.commandTimeoutSeconds());
            }
            case Config.PROVIDER_OPENAI -> {
                OpenAICompatiblePreset preset=OpenAICompatiblePreset.from(config);
                String model = ProviderEndpointPolicy.validateModelId(
                        config.model() != null ? config.model()
                                : "deepseek".equals(config.providerPreset())
                                ?"deepseek-v4-flash":"gpt-4o-mini");
                String baseUrl = ProviderEndpointPolicy.validate(
                        config.baseUrl() != null ? config.baseUrl()
                                :"deepseek".equals(config.providerPreset())
                                ?"https://api.deepseek.com":"https://api.openai.com").toString();
                ProviderCredentialResolver.ResolutionStatus credentialStatus=
                        credentials.status(config);
                if(!credentialStatus.present()){
                    // Fail-fast without falling back to mock or retaining credentials.
                    yield new MissingApiKeyProvider();
                }
                preset.validateModel(model);
                yield new OpenAICompatibleProvider(baseUrl,
                        ProviderCredentialSource.resolved(credentials,config),model,
                        config.effectiveSystemPrompt(), config.commandTimeoutSeconds(),preset,
                        preset.thinkingMode(config),preset.reasoningEffort(config));
            }
            default -> throw new IllegalArgumentException(
                    "Unknown provider: '" + providerName
                            + "'. Supported runtime providers: ollama, openai");
        };
    }

    /** Typed admission failure suitable for CLI and interactive rendering. */
    public static final class UnsupportedRuntimeProviderException
            extends IllegalArgumentException {
        private final String code;

        UnsupportedRuntimeProviderException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /**
     * A fail-fast provider that immediately returns a typed failure when apiKey is missing.
     * Does NOT fall back to mock. Does NOT print the key.
     */
    static class MissingApiKeyProvider implements ModelProvider {
        MissingApiKeyProvider() { }

        @Override
        public com.paicli.model.ModelResponse chat(
                java.util.List<com.paicli.model.AgentMessage> messages,
                java.util.List<com.paicli.model.ToolDefinition> toolDefinitions) {
            return com.paicli.model.ModelResponse.providerError(
                    com.paicli.model.ModelResponse.ProviderErrorKind
                            .PROVIDER_CONFIGURATION_FAILURE);
        }
    }
}
