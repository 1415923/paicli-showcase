package com.paicli.provider;

import com.paicli.config.Config;

import java.net.URI;
import java.util.Set;

/** Versioned capability and endpoint policy for the existing OpenAI-compatible adapter. */
public record OpenAICompatiblePreset(String name, String chatPath, String modelsPath,
        int contextWindowTokens, int maxOutputTokens, boolean supportsTools,
        boolean supportsJsonObject, boolean supportsThinking, String defaultThinkingMode,
        boolean supportsReasoningEffort, Set<String> supportedReasoningEfforts,
        boolean requiresReasoningContentForToolCalls,
        boolean requiresAssistantContentForToolCalls,
        boolean supportsToolChoiceInThinkingMode, String maxTokensField,
        boolean supportsDeveloperRole, Set<String> allowedModels) {
    private static final OpenAICompatiblePreset GENERIC = new OpenAICompatiblePreset(
            "generic-v1", "/v1/chat/completions", "/v1/models", 0, 16_384,
            true, false, false, "disabled", false, Set.of(),
            false, false, true, "max_tokens", false, Set.of());
    private static final OpenAICompatiblePreset DEEPSEEK = new OpenAICompatiblePreset(
            "deepseek-v1", "/chat/completions", "/models", 1_000_000, 384_000,
            true, true, true, "enabled", true, Set.of("high", "max"),
            true, true, false, "max_tokens", false,
            Set.of("deepseek-v4-flash", "deepseek-v4-pro"));

    public OpenAICompatiblePreset {
        allowedModels = Set.copyOf(allowedModels);
        supportedReasoningEfforts = Set.copyOf(supportedReasoningEfforts);
    }

    public static OpenAICompatiblePreset from(Config config) {
        return "deepseek".equals(config.providerPreset()) ? DEEPSEEK : GENERIC;
    }

    public static OpenAICompatiblePreset generic() { return GENERIC; }

    public OutputCapabilities outputCapabilities() {
        return new OutputCapabilities(supportsJsonObject ? OutputCapabilities.Support.SUPPORTED
                : OutputCapabilities.Support.UNKNOWN, supportsTools ? OutputCapabilities.Support.SUPPORTED
                : OutputCapabilities.Support.UNSUPPORTED, OutputCapabilities.Support.UNKNOWN);
    }

    public String thinkingMode(Config config) {
        String value=config==null?null:config.thinking();
        String effective=value==null?defaultThinkingMode:value;
        if(!supportsThinking&&"enabled".equals(effective))
            throw new IllegalArgumentException("THINKING_NOT_SUPPORTED_BY_PROVIDER_PRESET");
        return effective;
    }

    public String reasoningEffort(Config config) {
        if(!"enabled".equals(thinkingMode(config)))return null;
        String value=config==null?null:config.reasoningEffort();
        String effective=value==null&&supportsReasoningEffort?"max":value;
        if(effective!=null&&(!supportsReasoningEffort
                ||!supportedReasoningEfforts.contains(effective)))
            throw new IllegalArgumentException("REASONING_EFFORT_NOT_SUPPORTED_BY_PROVIDER_PRESET");
        return effective;
    }

    public void validateModel(String model) {
        if (!allowedModels.isEmpty() && !allowedModels.contains(model)) {
            throw new IllegalArgumentException("MODEL_NOT_SUPPORTED_BY_PROVIDER_PRESET");
        }
    }

    public URI endpoint(URI base, boolean models) {
        String suffix = models ? modelsPath : chatPath;
        String value = base.toString().replaceAll("/+$", "");
        if (value.endsWith(suffix)) return URI.create(value);
        if (suffix.startsWith("/v1/") && value.endsWith("/v1")) {
            return URI.create(value + suffix.substring(3));
        }
        if (suffix.startsWith("/") && value.endsWith("/v1")
                && suffix.startsWith("/v1/")) {
            return URI.create(value + suffix.substring(3));
        }
        return URI.create(value + suffix);
    }
}
