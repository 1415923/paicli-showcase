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
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MiniMaxM27V3ProbeContractTest {
    @TempDir Path workspace;

    @Test
    void frozenConfigUsesGenericChatCompletionsWithoutOptionalSamplingOrReasoning() {
        Config config = MiniMaxM27V3ProbeContract.config(workspace,
                MiniMaxM27V3ProbeContract.BASE_URL);
        assertAll(
                () -> assertEquals(Config.PROVIDER_OPENAI, config.effectiveProvider()),
                () -> assertEquals("MiniMax-M2.7", config.model()),
                () -> assertEquals("https://api.minimaxi.com", config.baseUrl()),
                () -> assertEquals("MINIMAX_API_KEY", config.apiKeyEnv()),
                () -> assertEquals("PaiCLI/provider/minimax/m27-paygo",
                        config.credentialRef()),
                () -> assertNull(config.providerPreset()),
                () -> assertEquals("disabled", config.effectiveThinking()),
                () -> assertNull(config.effectiveReasoningEffort()),
                () -> assertEquals(204_800, config.effectiveContextWindowTokens()),
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
        JsonNode fixtures = MiniMaxM27V3ProbeContract.load(project, "fixtures.json");
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
    void paygoPricingArithmeticCoversCacheAndConservativeWriteDebit() {
        Path project = Path.of(System.getProperty("user.dir"));
        var calculator = new MiniMaxM27V3ProbeContract.PriceCalculator(
                MiniMaxM27V3ProbeContract.load(project, "price-snapshot.json"));
        assertEquals(new BigDecimal("0.000408"), calculator.settle(100, 20, 10, null));
        assertEquals(new BigDecimal("0.000372"), calculator.settle(100, 20, 10, 20));
        assertEquals(new BigDecimal("0.787508"),
                calculator.settle(300_000, 1, 0, null));
        assertEquals(new BigDecimal("0.000000"), calculator.settle(0, 0, null, null));
    }

    @Test
    void missingUsageConsumesReserveAndStopsFurtherAdmission() {
        Path project = Path.of(System.getProperty("user.dir"));
        var budget = new MiniMaxM27V3ProbeContract.CampaignBudget(
                new MiniMaxM27V3ProbeContract.PriceCalculator(
                        MiniMaxM27V3ProbeContract.load(project, "price-snapshot.json")));
        assertEquals(1, budget.admit());
        var settlement = budget.settle(ProviderCallObservation.notExposed(),
                ProviderTransportEvidence.transportFailed("TRANSPORT_TIMEOUT"));
        assertEquals(new BigDecimal("0.500000"), settlement.debit());
        assertTrue(settlement.stopAfterAttempt());
        assertThrows(MiniMaxM27V3ProbeContract.ProbeStopException.class, budget::admit);
    }

    @Test
    void admissionChecksReserveAtExactBudgetBoundary() {
        Path project = Path.of(System.getProperty("user.dir"));
        var budget = new MiniMaxM27V3ProbeContract.CampaignBudget(
                new MiniMaxM27V3ProbeContract.PriceCalculator(
                        MiniMaxM27V3ProbeContract.load(project, "price-snapshot.json")));
        ProviderCallObservation costly = new ProviderCallObservation(200_000, 250_001,
                450_001, 0, 0, 0, ProviderCallObservation.UsageSource.PROVIDER_REPORTED,
                ProviderCallObservation.UsageCoverage.COMPLETE, 0,
                ProviderResponseEnvelope.ToolCallCardinality.ZERO, "stop", "response-boundary",
                ProviderCallObservation.ResponseIdentityProvenance.PROVIDER_RETURNED,
                null, false);
        budget.admit();
        budget.settle(costly, ProviderTransportEvidence.responseReceived(200,
                ProviderTransportEvidence.UsageProvenance.PROVIDER_REPORTED, null));
        assertTrue(budget.settled().compareTo(new BigDecimal("2.50")) >= 0);
        assertThrows(MiniMaxM27V3ProbeContract.ProbeStopException.class, budget::admit);
    }

    @Test
    void credentialGateReportsOnlyThreeAllowlistedFactsForAllSources() {
        Config config = MiniMaxM27V3ProbeContract.config(workspace,
                MiniMaxM27V3ProbeContract.BASE_URL);
        var absent = MiniMaxM27V3ProbeContract.credentialFact(
                new ProviderCredentialResolver(ignored -> null,
                        new MiniMaxM27V3LiveProbeIT.FixedStatusStore(
                                CredentialStore.State.MISSING)), config);
        var environment = MiniMaxM27V3ProbeContract.credentialFact(
                new ProviderCredentialResolver(ignored -> "environment-test-value",
                        new MiniMaxM27V3LiveProbeIT.FixedStatusStore(
                                CredentialStore.State.PRESENT)), config);
        var stored = MiniMaxM27V3ProbeContract.credentialFact(
                new ProviderCredentialResolver(ignored -> null,
                        new MiniMaxM27V3LiveProbeIT.FixedStatusStore(
                                CredentialStore.State.PRESENT)), config);
        assertEquals(new MiniMaxM27V3ProbeContract.CredentialFact(false, "absent", false), absent);
        assertEquals(new MiniMaxM27V3ProbeContract.CredentialFact(
                true, "environment", false), environment);
        assertEquals(new MiniMaxM27V3ProbeContract.CredentialFact(
                true, "configured_secure_reference", false), stored);
        assertFalse(absent.toString().contains("environment-test-value"));
        assertFalse(environment.toString().contains("environment-test-value"));
    }

    @Test
    void launcherSuppliedStableLedgerPathIsTheJVMAdmissionAuthority() throws Exception {
        Path project = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path evidence = workspace.resolve("stable-ledger-regression-evidence");
        Path stableLedger = workspace.resolve("stable-project-ledger").toAbsolutePath().normalize();
        ProviderCredentialResolver resolver = new ProviderCredentialResolver(
                ignored -> MiniMaxM27V3LoopbackServer.SECRET,
                new MiniMaxM27V3LiveProbeIT.FixedStatusStore(
                        CredentialStore.State.MISSING));
        try (MiniMaxM27V3LoopbackServer server = new MiniMaxM27V3LoopbackServer(project)) {
            var outcome = new MiniMaxM27V3ProbeRuntime(project, evidence, server.baseUrl(),
                    resolver, "PAID_EXECUTION", "0".repeat(64), true,
                    "a".repeat(32), "b".repeat(64), stableLedger).run();
            assertEquals("PASS", outcome.verdict());
            assertTrue(Files.isRegularFile(stableLedger.resolve("campaign-admission.json")));
            server.assertHealthy();
        }
    }
}
