package com.paicli.interactive.runtime;

import com.paicli.model.*;
import com.paicli.provider.OpenAICompatibleProvider;
import java.math.BigDecimal;
import java.util.List;

/** Diagnostic budget uses metadata from the serialized request that will actually be sent. */
final class DeliveryRecheckLimits {
    static BigDecimal requestReserve(com.paicli.provider.PreparedProviderTurn prepared) {
        var metric=prepared.metrics();
        if(metric==null)throw new IllegalStateException("RECHECK_REQUEST_BOUND_UNAVAILABLE");
        if(!"deepseek-v4-flash".equals(metric.model()) || !"disabled".equals(metric.thinkingMode())
                ||metric.maxOutputTokens()!=32768)
            throw new IllegalStateException("RECHECK_MODEL_BOUND_MISMATCH");
        long inputBound=Math.addExact(metric.utf8Bytes(),16384L+32L*metric.messageCount()+128L*metric.toolCount());
        if(inputBound>1_100_000)throw new IllegalStateException("RECHECK_INPUT_BOUND_EXCEEDED");
        return BigDecimal.valueOf(inputBound).multiply(new BigDecimal("3"))
                .add(BigDecimal.valueOf(metric.maxOutputTokens()).multiply(new BigDecimal("9"))).movePointLeft(6);
    }
    static boolean isRecheck(com.fasterxml.jackson.databind.JsonNode manifest){
        return java.util.Set.of("delivery-recheck-v1","quality-recheck-v1","af-p3-v1").contains(manifest.path("kind").asText());
    }
    static List<String> cases(com.fasterxml.jackson.databind.JsonNode manifest){
        if("af-p3-v1".equals(manifest.path("kind").asText()))return List.of("Q3","Q1","N3","N1");
        return "quality-recheck-v1".equals(manifest.path("kind").asText())?List.of("N3","N1"):List.of("task-016","N3");
    }
    static String budget(com.fasterxml.jackson.databind.JsonNode manifest){return isRecheck(manifest)?"1":"10";}
    static EcosystemD7SmokeIT.BoundedProvider provider(OpenAICompatibleProvider provider,
            EcosystemD7SmokeIT.Budget budget,String id,com.fasterxml.jackson.databind.JsonNode manifest,
            com.fasterxml.jackson.databind.JsonNode row){
        if(!isRecheck(manifest))return new EcosystemD7SmokeIT.BoundedProvider(provider,budget,id);
        int requests=row.path("maximumHttpAttempts").asInt(),tools=row.path("maximumToolCalls").asInt();
        if(requests<1||requests>24||tools<1||tools>24)throw new IllegalArgumentException("RECHECK_LIMITS_REQUIRED");
        if("af-p3-v1".equals(manifest.path("kind").asText())) {
            if(!"DIAGNOSTIC_NATIVE_CANDIDATE".equals(manifest.path("outputMode").asText()))
                throw new IllegalArgumentException("P3_OUTPUT_MODE_REQUIRED");
            return new EcosystemD7SmokeIT.BoundedProvider(provider,budget,id,requests,tools,
                    com.paicli.provider.OutputRequirement.Mode.DIAGNOSTIC_NATIVE_CANDIDATE);
        }
        return new EcosystemD7SmokeIT.BoundedProvider(provider,budget,id,requests,tools);
    }
}
