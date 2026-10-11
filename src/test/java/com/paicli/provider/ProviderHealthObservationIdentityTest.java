package com.paicli.provider;

import com.paicli.config.Config;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProviderHealthObservationIdentityTest {
    @Test void fingerprintChangesWithProviderModelEndpointOrKeyEnvironmentButNeverSecretValue(){
        Config base=Config.defaults().withProvider("openai","deepseek-v4-flash",
                "https://api.deepseek.com","DEEPSEEK_API_KEY");
        String fingerprint=ProviderHealthService.observationFingerprint(base);
        assertTrue(fingerprint.matches("[0-9a-f]{64}"));
        assertNotEquals(fingerprint,ProviderHealthService.observationFingerprint(
                base.withModel("deepseek-v4-pro")));
        assertNotEquals(fingerprint,ProviderHealthService.observationFingerprint(
                base.withProvider("openai","deepseek-v4-flash",
                        "https://gateway.example.test","DEEPSEEK_API_KEY")));
        assertNotEquals(fingerprint,ProviderHealthService.observationFingerprint(
                base.withProvider("openai","deepseek-v4-flash",
                        "https://api.deepseek.com","MANAGED_DEEPSEEK_KEY")));
        assertFalse(fingerprint.contains("DEEPSEEK_API_KEY"));
    }
}
