package com.paicli.provider.conformance;

import com.paicli.credential.CredentialRef;
import com.paicli.credential.CredentialStore;
import com.paicli.credential.ProviderCredentialResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Provider-disabled by default; paid mode requires all exact one-shot gates. */
public class OpenAITerraLiveProbeIT {
    @TempDir Path temporary;

    @Test
    void providerDisabledRehearsalOrExplicitPaidOneShot() throws Exception {
        Path project = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        boolean paid = Boolean.parseBoolean(System.getProperty(
                "openai.terra.executePaid", "false"));
        if (paid) {
            runPaid(project);
            return;
        }
        String rehearsalValue = System.getProperty("openai.terra.rehearsalEvidenceRoot");
        Path evidence = rehearsalValue == null
                ? temporary.resolve("provider-disabled-evidence")
                : Path.of(rehearsalValue).toAbsolutePath().normalize();
        if (rehearsalValue != null) {
            Path allowed = project.resolve("target/openai-terra-rehearsal-evidence")
                    .normalize();
            assertTrue(evidence.startsWith(allowed) && !evidence.equals(allowed),
                    "captured rehearsal evidence must be a fresh child of the test target");
            assertFalse(Files.exists(evidence), "captured rehearsal evidence must be fresh");
        }
        ProviderCredentialResolver resolver = new ProviderCredentialResolver(
                ignored -> OpenAITerraLoopbackServer.SECRET,
                new FixedStatusStore(CredentialStore.State.MISSING));
        try (OpenAITerraLoopbackServer server = new OpenAITerraLoopbackServer(project)) {
            String identity = sha256(project.resolve(OpenAITerraProbeContract.CAMPAIGN_ROOT)
                    .resolve("identity-manifest.json"));
            var outcome = new OpenAITerraProbeRuntime(project, evidence, server.baseUrl(),
                    resolver, "PROVIDER_DISABLED_REHEARSAL", identity, false).run();
            server.assertHealthy();
            assertEquals("PASS", outcome.verdict());
            assertNull(outcome.disposition());
            assertEquals(7, outcome.attempts());
            assertEquals(0, outcome.externalProviderCalls());
            String persisted = Files.readString(outcome.evidenceFile(), StandardCharsets.UTF_8);
            assertFalse(persisted.contains(OpenAITerraLoopbackServer.SECRET));
            assertFalse(persisted.toLowerCase().contains("authorization"));
            assertFalse(persisted.contains("Fixture F"));
        }
    }

    private void runPaid(Path project) throws Exception {
        assertEquals(OpenAITerraProbeContract.AUTHORIZATION,
                System.getProperty("openai.terra.authorization"),
                "exact paid authorization mismatch");
        Path campaign = project.resolve(OpenAITerraProbeContract.CAMPAIGN_ROOT);
        String identity = sha256(campaign.resolve("identity-manifest.json"));
        assertEquals(identity, System.getProperty("openai.terra.identityManifestSha256"),
                "identity-manifest drift");
        String frozenPriceSource = OpenAITerraProbeContract.JSON.readTree(
                campaign.resolve("price-snapshot.json").toFile())
                .path("settlement_authority").path("source_identity").asText();
        assertEquals(frozenPriceSource,
                System.getProperty("openai.terra.priceSourceIdentity"),
                "dedicated Pricing source must be freshly revalidated");
        String evidenceValue = System.getProperty("openai.terra.evidenceRoot");
        assertNotNull(evidenceValue, "paid evidence root required");
        Path evidence = Path.of(evidenceValue).toAbsolutePath().normalize();
        Path allowed = project.resolve("target/openai-terra-live-evidence").normalize();
        assertTrue(evidence.startsWith(allowed) && !evidence.equals(allowed),
                "paid evidence root must be a fresh child of target/openai-terra-live-evidence");
        assertFalse(Files.exists(evidence), "paid evidence root must not exist");
        ProviderCredentialResolver resolver = ProviderCredentialResolver.systemDefault();
        var credential = OpenAITerraProbeContract.credentialFact(resolver,
                OpenAITerraProbeContract.config(evidence.resolve("credential-gate"),
                        OpenAITerraProbeContract.BASE_URL));
        assertTrue(credential.credentialPresent(), "LIVE_CREDENTIAL_NOT_READY");
        var outcome = new OpenAITerraProbeRuntime(project, evidence,
                OpenAITerraProbeContract.BASE_URL, resolver, "PAID_EXECUTION",
                identity, true).run();
        assertTrue(outcome.attempts() <= OpenAITerraProbeContract.ATTEMPT_CAP);
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path)));
    }

    static final class FixedStatusStore implements CredentialStore {
        private final State state;
        FixedStatusStore(State state) { this.state = state; }
        @Override public void put(CredentialRef reference, char[] secret) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<char[]> get(CredentialRef reference) {
            return state == State.PRESENT ? Optional.of("stored-test-value".toCharArray())
                    : Optional.empty();
        }
        @Override public boolean delete(CredentialRef reference) { return false; }
        @Override public Status status(CredentialRef reference) {
            return new Status(state, "synthetic-test-store");
        }
    }
}
