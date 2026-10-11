package com.paicli.provider.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.paicli.config.Config;
import com.paicli.credential.CredentialStore;
import com.paicli.credential.ProviderCredentialResolver;
import com.paicli.provider.ProviderResponseEnvelope;
import com.paicli.provider.telemetry.ProviderCallObservation;
import com.paicli.provider.telemetry.ProviderTransportEvidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class OpenAITerraProbeContractTest {
    @TempDir Path workspace;

    @Test
    void frozenConfigUsesGenericChatCompletionsWithoutOptionalSamplingOrReasoning() {
        Config config = OpenAITerraProbeContract.config(workspace,
                OpenAITerraProbeContract.BASE_URL);
        assertAll(
                () -> assertEquals(Config.PROVIDER_OPENAI, config.effectiveProvider()),
                () -> assertEquals("gpt-5.6-terra", config.model()),
                () -> assertEquals("https://api.openai.com", config.baseUrl()),
                () -> assertNull(config.providerPreset()),
                () -> assertEquals("disabled", config.effectiveThinking()),
                () -> assertNull(config.effectiveReasoningEffort()),
                () -> assertEquals(1_050_000, config.effectiveContextWindowTokens()),
                () -> assertEquals(Config.DEFAULT_SYSTEM_PROMPT,
                        config.effectiveSystemPrompt()),
                () -> assertFalse(config.isPlanEnabled()),
                () -> assertFalse(config.isVerifyEnabled()),
                () -> assertFalse(config.isRepairEnabled()),
                () -> assertFalse(config.isSkillsEnabled()),
                () -> assertFalse(config.isHooksEnabled()),
                () -> assertFalse(config.isTaskContinuityEnabled()));
    }

    @Test
    void strictPublicToolSchemasStaySmallAndClosed() {
        Path project = Path.of(System.getProperty("user.dir"));
        JsonNode fixtures = OpenAITerraProbeContract.load(project, "fixtures.json");
        for (String name : new String[]{"lookup_fixture_fact", "lookup_fixture_label",
                "effect_counter"}) {
            JsonNode schema = fixtures.path("tool_schemas").path(name);
            assertEquals("object", schema.path("type").asText());
            assertFalse(schema.path("additionalProperties").asBoolean(true));
            assertEquals(1, schema.path("required").size());
            assertEquals(1, schema.path("properties").size());
        }
    }

    @Test
    void dedicatedPricingArithmeticCoversShortLongCacheAndConservativeWriteDebit() {
        Path project = Path.of(System.getProperty("user.dir"));
        var calculator = new OpenAITerraProbeContract.PriceCalculator(
                OpenAITerraProbeContract.load(project, "price-snapshot.json"));
        assertEquals(new BigDecimal("0.000467"), calculator.settle(100, 20, 10, null));
        assertEquals(new BigDecimal("0.000432"), calculator.settle(100, 20, 10, 20));
        assertEquals(new BigDecimal("1.500018"),
                calculator.settle(300_000, 1, 0, null));
        assertEquals(new BigDecimal("0.000000"), calculator.settle(0, 0, null, null));
    }

    @Test
    void missingUsageConsumesReserveAndStopsFurtherAdmission() {
        Path project = Path.of(System.getProperty("user.dir"));
        var budget = new OpenAITerraProbeContract.CampaignBudget(
                new OpenAITerraProbeContract.PriceCalculator(
                        OpenAITerraProbeContract.load(project, "price-snapshot.json")));
        assertEquals(1, budget.admit());
        var settlement = budget.settle(ProviderCallObservation.notExposed(),
                ProviderTransportEvidence.transportFailed("TRANSPORT_TIMEOUT"));
        assertEquals(new BigDecimal("0.300000"), settlement.debit());
        assertTrue(settlement.stopAfterAttempt());
        assertThrows(OpenAITerraProbeContract.ProbeStopException.class, budget::admit);
    }

    @Test
    void admissionChecksReserveAtExactBudgetBoundary() {
        Path project = Path.of(System.getProperty("user.dir"));
        var budget = new OpenAITerraProbeContract.CampaignBudget(
                new OpenAITerraProbeContract.PriceCalculator(
                        OpenAITerraProbeContract.load(project, "price-snapshot.json")));
        ProviderCallObservation costly = new ProviderCallObservation(200_000, 25_001,
                225_001, 0, 0, 0, ProviderCallObservation.UsageSource.PROVIDER_REPORTED,
                ProviderCallObservation.UsageCoverage.COMPLETE, 0,
                ProviderResponseEnvelope.ToolCallCardinality.ZERO, "stop", "response-boundary",
                ProviderCallObservation.ResponseIdentityProvenance.PROVIDER_RETURNED,
                null, false);
        budget.admit();
        budget.settle(costly, ProviderTransportEvidence.responseReceived(200,
                ProviderTransportEvidence.UsageProvenance.PROVIDER_REPORTED, null));
        assertTrue(budget.settled().compareTo(new BigDecimal("0.70")) >= 0);
        assertThrows(OpenAITerraProbeContract.ProbeStopException.class, budget::admit);
    }

    @Test
    void credentialGateReportsOnlyThreeAllowlistedFactsForAllSources() {
        Config config = OpenAITerraProbeContract.config(workspace,
                OpenAITerraProbeContract.BASE_URL);
        var absent = OpenAITerraProbeContract.credentialFact(
                new ProviderCredentialResolver(ignored -> null,
                        new OpenAITerraLiveProbeIT.FixedStatusStore(
                                CredentialStore.State.MISSING)), config);
        var environment = OpenAITerraProbeContract.credentialFact(
                new ProviderCredentialResolver(ignored -> "environment-test-value",
                        new OpenAITerraLiveProbeIT.FixedStatusStore(
                                CredentialStore.State.PRESENT)), config);
        var stored = OpenAITerraProbeContract.credentialFact(
                new ProviderCredentialResolver(ignored -> null,
                        new OpenAITerraLiveProbeIT.FixedStatusStore(
                                CredentialStore.State.PRESENT)), config);
        assertEquals(new OpenAITerraProbeContract.CredentialFact(false, "absent", false), absent);
        assertEquals(new OpenAITerraProbeContract.CredentialFact(
                true, "environment", false), environment);
        assertEquals(new OpenAITerraProbeContract.CredentialFact(
                true, "configured_secure_reference", false), stored);
        assertFalse(absent.toString().contains("environment-test-value"));
        assertFalse(environment.toString().contains("environment-test-value"));
    }
}
