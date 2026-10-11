package com.paicli.provider.conformance;

import com.paicli.credential.CredentialRef;
import com.paicli.credential.CredentialStore;
import com.paicli.credential.ProviderCredentialResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Provider-disabled by default; paid mode requires all exact one-shot gates. */
public class MiniMaxM27V2LiveProbeIT {
    @TempDir Path temporary;

    @Test
    void providerDisabledRehearsalOrExplicitPaidOneShot() throws Exception {
        Path project = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        boolean paid = Boolean.parseBoolean(System.getProperty(
                "minimax.m27.v2.executePaid", "false"));
        if (paid) {
            runPaid(project);
            return;
        }
        String rehearsalValue = System.getProperty("minimax.m27.v2.preflightEvidenceRoot");
        Path evidence = rehearsalValue == null
                ? temporary.resolve("provider-disabled-evidence")
                : Path.of(rehearsalValue).toAbsolutePath().normalize();
        if (rehearsalValue != null) {
            Path allowed = project.resolve("target/minimax-m27-v2-preflight-evidence")
                    .normalize();
            assertTrue(evidence.startsWith(allowed) && !evidence.equals(allowed),
                    "captured rehearsal evidence must be a fresh child of the test target");
            assertFalse(Files.exists(evidence), "captured rehearsal evidence must be fresh");
        }
        ProviderCredentialResolver resolver = new ProviderCredentialResolver(
                ignored -> MiniMaxM27V2LoopbackServer.SECRET,
                new FixedStatusStore(CredentialStore.State.MISSING));
        try (MiniMaxM27V2LoopbackServer server = new MiniMaxM27V2LoopbackServer(project)) {
            String identity = sha256(project.resolve(MiniMaxM27V2ProbeContract.CAMPAIGN_ROOT)
                    .resolve("identity-manifest.json"));
            var outcome = new MiniMaxM27V2ProbeRuntime(project, evidence, server.baseUrl(),
                    resolver, "PROVIDER_DISABLED_PREFLIGHT", identity, false).run();
            server.assertHealthy();
            assertEquals("PASS", outcome.verdict());
            assertNull(outcome.disposition());
            assertEquals(7, outcome.attempts());
            assertEquals(0, outcome.externalProviderCalls());
            String persisted = Files.readString(outcome.evidenceFile(), StandardCharsets.UTF_8);
            assertFalse(persisted.contains(MiniMaxM27V2LoopbackServer.SECRET));
            assertFalse(persisted.toLowerCase().contains("authorization"));
            assertFalse(persisted.contains("Fixture F"));
            assertTrue(persisted.contains("\"tool_turn_thinking_preserved\":true"));
            assertTrue(persisted.contains("\"final_thinking_visibility\":\"PRESENT\""));
        }
        writeRuntimeEntry(evidence, "PROVIDER_DISABLED_PREFLIGHT");
    }

    private void runPaid(Path project) throws Exception {
        assertEquals(MiniMaxM27V2ProbeContract.AUTHORIZATION,
                System.getProperty("minimax.m27.v2.authorization"),
                "exact paid authorization mismatch");
        Path campaign = project.resolve(MiniMaxM27V2ProbeContract.CAMPAIGN_ROOT);
        String identity = sha256(campaign.resolve("identity-manifest.json"));
        assertEquals(identity, System.getProperty("minimax.m27.v2.identityManifestSha256"),
                "identity-manifest drift");
        String frozenPriceSource = MiniMaxM27V2ProbeContract.JSON.readTree(
                campaign.resolve("price-snapshot.json").toFile())
                .path("settlement_authority").path("source_identity").asText();
        assertEquals(frozenPriceSource,
                System.getProperty("minimax.m27.v2.priceSourceIdentity"),
                "MiniMax pay-as-you-go Pricing source must be freshly revalidated");
        String evidenceValue = System.getProperty("minimax.m27.v2.evidenceRoot");
        assertNotNull(evidenceValue, "paid evidence root required");
        Path evidence = Path.of(evidenceValue).toAbsolutePath().normalize();
        Path allowed = project.resolve("target/minimax-m27-v2-live-evidence").normalize();
        assertTrue(evidence.startsWith(allowed) && !evidence.equals(allowed),
                "paid evidence root must be a fresh child of target/minimax-m27-live-evidence");
        assertFalse(Files.exists(evidence), "paid evidence root must not exist");
        ProviderCredentialResolver resolver = ProviderCredentialResolver.systemDefault();
        var credential = MiniMaxM27V2ProbeContract.credentialFact(resolver,
                MiniMaxM27V2ProbeContract.config(evidence.resolve("credential-gate"),
                        MiniMaxM27V2ProbeContract.BASE_URL));
        assertTrue(credential.credentialPresent(), "LIVE_CREDENTIAL_NOT_READY");
        var outcome = new MiniMaxM27V2ProbeRuntime(project, evidence,
                MiniMaxM27V2ProbeContract.BASE_URL, resolver, "PAID_EXECUTION",
                identity, true).run();
        assertTrue(outcome.attempts() <= MiniMaxM27V2ProbeContract.ATTEMPT_CAP);
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path)));
    }

    private static void writeRuntimeEntry(Path evidence, String mode) throws Exception {
        Files.createDirectories(evidence);
        var marker = MiniMaxM27V2ProbeContract.JSON.createObjectNode();
        marker.put("schema_version", "minimax-m27-v2-runtime-entry-v1");
        marker.put("campaign", MiniMaxM27V2ProbeContract.CAMPAIGN);
        marker.put("mode", mode);
        marker.put("java_runtime_entered", true);
        byte[] bytes = (MiniMaxM27V2ProbeContract.JSON.writeValueAsString(marker) + "\n")
                .getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(evidence.resolve("runtime-entry.json"),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(bytes));
            channel.force(true);
        }
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
