package com.paicli.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProviderEndpointPolicyTest {
    @Test void acceptsHttpAndHttpsAndNormalizesTrailingSlash() {
        assertEquals("http://localhost:11434",
                ProviderEndpointPolicy.validate("http://localhost:11434/").toString());
        assertEquals("https", ProviderEndpointPolicy.validate("https://example.test").getScheme());
    }

    @Test void rejectsDangerousProtocolsCredentialsQueryFragmentAndControls() {
        for (String value : new String[]{"file:///tmp/key", "jar:file:/x", "ftp://example.test",
                "https://user:pass@example.test", "https://example.test?q=x",
                "https://example.test/#x", "https://example.test/\n"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> ProviderEndpointPolicy.validate(value), value);
        }
    }

    @Test void validatesModelAndEnvironmentNames() {
        assertEquals("qwen3:8b", ProviderEndpointPolicy.validateModelId("qwen3:8b"));
        assertEquals("OPENAI_API_KEY",
                ProviderEndpointPolicy.validateEnvironmentName("OPENAI_API_KEY"));
        assertThrows(IllegalArgumentException.class,
                () -> ProviderEndpointPolicy.validateModelId("x\n"));
        assertThrows(IllegalArgumentException.class,
                () -> ProviderEndpointPolicy.validateEnvironmentName("KEY=value"));
    }
}
