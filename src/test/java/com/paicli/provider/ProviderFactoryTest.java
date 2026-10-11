package com.paicli.provider;

import com.paicli.config.Config;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProviderFactoryTest {

    private Config configWith(String provider) {
        return new Config(
                ".", 30, 50000, 200000, "on-request", 10000,
                provider, null, null, null, null, 10
        );
    }

    @Test
    void absentProviderFailsClosedWithDoctorGuidance() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> ProviderFactory.create(Config.defaults()));
        assertTrue(error.getMessage().contains("No production provider is configured"));
        assertTrue(error.getMessage().contains("paicli doctor"));
    }

    @Test
    void explicitMockIsRejectedWithStableTypedGuidance() {
        ProviderFactory.UnsupportedRuntimeProviderException error = assertThrows(
                ProviderFactory.UnsupportedRuntimeProviderException.class,
                () -> ProviderFactory.create(configWith("mock")));
        assertEquals(ProviderFactory.MOCK_UNSUPPORTED_CODE, error.code());
        assertEquals(ProviderFactory.MOCK_UNSUPPORTED_MESSAGE, error.getMessage());
        assertTrue(error.getMessage().contains("development-only"));
        assertTrue(error.getMessage().contains("Ollama"));
        assertTrue(error.getMessage().contains("OpenAI-compatible"));
    }

    @Test
    void explicitOllamaCreatesOllamaProvider() {
        ModelProvider provider = ProviderFactory.create(configWith("ollama"));
        assertTrue(provider instanceof OllamaProvider);
    }

    @Test
    void explicitOpenAIMissingKeyDoesNotFallbackToMock() {
        Config config = new Config(
                ".", 30, 50000, 200000, "on-request", 10000,
                "openai", "gpt-4o-mini", null, "OPENAI_API_KEY_NOT_SET_FOR_TESTING", null, 10
        );
        ModelProvider provider = ProviderFactory.create(config);

        assertFalse(provider instanceof MockModelProvider);
        assertTrue(provider instanceof ProviderFactory.MissingApiKeyProvider);

        var response = provider.chat(java.util.List.of(), java.util.List.of());
        assertEquals(com.paicli.model.ModelResponse.ResponseType.PROVIDER_ERROR,
                response.type());
        assertEquals(com.paicli.model.ModelResponse.ProviderErrorKind
                        .PROVIDER_CONFIGURATION_FAILURE,
                response.providerErrorKind());
        assertNull(response.content());
        assertFalse(response.toString().contains("OPENAI_API_KEY_NOT_SET_FOR_TESTING"));
    }

    @Test
    void unknownProviderThrowsIllegalArgument() {
        Config config = configWith("unknown-provider");
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> ProviderFactory.create(config)
        );
        assertTrue(ex.getMessage().contains("unknown-provider"));
        assertTrue(ex.getMessage().contains("ollama, openai"));
        assertFalse(ex.getMessage().contains("mock,"));
    }

    @Test
    void explicitOllamaNeverFallsBackToMock() {
        ModelProvider provider = ProviderFactory.create(configWith("ollama"));
        assertTrue(provider instanceof OllamaProvider);
        assertFalse(provider instanceof MockModelProvider);
    }

    @Test
    void openaiWithValidKeyCreatesOpenAIProvider() {
        Config config = new Config(
                ".", 30, 50000, 200000, "on-request", 10000,
                "openai", "gpt-4o-mini", "https://api.openai.com", "PATH", null, 10
        );
        ModelProvider provider = ProviderFactory.create(config);
        assertTrue(provider instanceof OpenAICompatibleProvider);
    }
}
