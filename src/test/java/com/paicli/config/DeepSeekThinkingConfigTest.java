package com.paicli.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeepSeekThinkingConfigTest {
    private Config deepSeek(){
        return Config.defaults().withProvider("openai","deepseek-v4-flash",
                "https://api.deepseek.com","DEEPSEEK_API_KEY")
                .withProviderPreset("deepseek");
    }

    @Test void deepSeekDefaultsToEnabledMax(){
        assertEquals("enabled",deepSeek().effectiveThinking());
        assertEquals("max",deepSeek().effectiveReasoningEffort());
    }

    @Test void explicitDisabledAndHighAreSupported(){
        assertEquals("disabled",deepSeek().withThinking("disabled",null).effectiveThinking());
        assertNull(deepSeek().withThinking("disabled",null).effectiveReasoningEffort());
        assertEquals("high",deepSeek().withThinking("enabled","high")
                .effectiveReasoningEffort());
    }

    @Test void unsupportedThinkingConfigurationFailsClosed(){
        assertThrows(IllegalArgumentException.class,()->deepSeek().withThinking("sometimes","max"));
        assertThrows(IllegalArgumentException.class,()->deepSeek().withThinking("enabled","medium"));
        assertThrows(IllegalArgumentException.class,()->Config.defaults().withThinking("enabled","max"));
    }
}
