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
public class MiniMaxM27LiveProbeIT {
    @TempDir Path temporary;

    @Test
    void providerDisabledRehearsalOrExplicitPaidOneShot() throws Exception {
        Path project = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        boolean paid = Boolean.parseBoolean(System.getProperty(
                "minimax.m27.executePaid", "false"));
        if (paid) {
            runPaid(project);
            return;
        }
        String rehearsalValue = System.getProperty("minimax.m27.rehearsalEvidenceRoot");
        Path evidence = rehearsalValue == null
                ? temporary.resolve("provider-disabled-evidence")
                : Path.of(rehearsalValue).toAbsolutePath().normalize();
        if (rehearsalValue != null) {
            Path allowed = project.resolve("target/minimax-m27-rehearsal-evidence")
                    .normalize();
            assertTrue(evidence.startsWith(allowed) && !evidence.equals(allowed),
                    "captured rehearsal evidence must be a fresh child of the test target");
            assertFalse(Files.exists(evidence), "captured rehearsal evidence must be fresh");
        }
        ProviderCredentialResolver resolver = new ProviderCredentialResolver(
                ignored -> MiniMaxM27LoopbackServer.SECRET,
                new FixedStatusStore(CredentialStore.State.MISSING));
        try (MiniMaxM27LoopbackServer server = new MiniMaxM27LoopbackServer(project)) {
            String identity = sha256(project.resolve(MiniMaxM27ProbeContract.CAMPAIGN_ROOT)
                    .resolve("identity-manifest.json"));
            var outcome = new MiniMaxM27ProbeRuntime(project, evidence, server.baseUrl(),
                    resolver, "PROVIDER_DISABLED_REHEARSAL", identity, false).run();
            server.assertHealthy();
            assertEquals("PASS", outcome.verdict());
            assertNull(outcome.disposition());
            assertEquals(7, outcome.attempts());
            assertEquals(0, outcome.externalProviderCalls());
            String persisted = Files.readString(outcome.evidenceFile(), StandardCharsets.UTF_8);
            assertFalse(persisted.contains(MiniMaxM27LoopbackServer.SECRET));
            assertFalse(persisted.toLowerCase().contains("authorization"));
            assertFalse(persisted.contains("Fixture F"));
            assertTrue(persisted.contains("\"tool_turn_thinking_preserved\":true"));
            assertTrue(persisted.contains("\"final_thinking_visibility\":\"PRESENT\""));
        }
    }

    private void runPaid(Path project) throws Exception {
        assertEquals(MiniMaxM27ProbeContract.AUTHORIZATION,
                System.getProperty("minimax.m27.authorization"),
                "exact paid authorization mismatch");
        Path campaign = project.resolve(MiniMaxM27ProbeContract.CAMPAIGN_ROOT);
        String identity = sha256(campaign.resolve("identity-manifest.json"));
        assertEquals(identity, System.getProperty("minimax.m27.identityManifestSha256"),
                "identity-manifest drift");
        String frozenPriceSource = MiniMaxM27ProbeContract.JSON.readTree(
                campaign.resolve("price-snapshot.json").toFile())
                .path("settlement_authority").path("source_identity").asText();
        assertEquals(frozenPriceSource,
                System.getProperty("minimax.m27.priceSourceIdentity"),
                "MiniMax pay-as-you-go Pricing source must be freshly revalidated");
        String evidenceValue = System.getProperty("minimax.m27.evidenceRoot");
        assertNotNull(evidenceValue, "paid evidence root required");
        Path evidence = Path.of(evidenceValue).toAbsolutePath().normalize();
        Path allowed = project.resolve("target/minimax-m27-live-evidence").normalize();
        assertTrue(evidence.startsWith(allowed) && !evidence.equals(allowed),
                "paid evidence root must be a fresh child of target/minimax-m27-live-evidence");
        assertFalse(Files.exists(evidence), "paid evidence root must not exist");
        ProviderCredentialResolver resolver = ProviderCredentialResolver.systemDefault();
        var credential = MiniMaxM27ProbeContract.credentialFact(resolver,
                MiniMaxM27ProbeContract.config(evidence.resolve("credential-gate"),
                        MiniMaxM27ProbeContract.BASE_URL));
        assertTrue(credential.credentialPresent(), "LIVE_CREDENTIAL_NOT_READY");
        var outcome = new MiniMaxM27ProbeRuntime(project, evidence,
                MiniMaxM27ProbeContract.BASE_URL, resolver, "PAID_EXECUTION",
                identity, true).run();
        assertTrue(outcome.attempts() <= MiniMaxM27ProbeContract.ATTEMPT_CAP);
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
