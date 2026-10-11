package com.paicli.credential;

import com.paicli.config.Config;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ProviderCredentialResolverTest {
    @Test void environmentOverridesStoredCredentialAndLeaseClearsOnClose() {
        FakeStore store = new FakeStore();
        Config config = Config.defaults().withProvider("openai", "model",
                "http://127.0.0.1:9", "TEST_PROVIDER_KEY")
                .withCredentialRef("PaiCLI/provider/deepseek/default");
        store.put(ProviderCredentialResolver.reference(config), "stored-secret".toCharArray());
        ProviderCredentialResolver resolver = new ProviderCredentialResolver(
                name -> "TEST_PROVIDER_KEY".equals(name) ? "environment-secret" : null, store);

        char[] borrowed;
        try (CredentialLease lease = resolver.resolve(config)) {
            assertEquals(CredentialLease.Source.ENVIRONMENT, lease.source());
            borrowed = lease.value();
            assertArrayEquals("environment-secret".toCharArray(), borrowed);
        }
        assertTrue(allZero(borrowed));
        assertEquals("environment", resolver.status(config).source());
    }

    @Test void storedCredentialSurvivesResolverRecreationAndDeleteRemovesIt() {
        FakeStore store = new FakeStore();
        Config config = Config.defaults().withProvider("openai", "model",
                "http://127.0.0.1:9", "MISSING_TEST_KEY")
                .withCredentialRef("PaiCLI/provider/deepseek/default");
        CredentialRef ref = ProviderCredentialResolver.reference(config);
        char[] original = "persisted-secret".toCharArray();
        store.put(ref, original);
        Arrays.fill(original, '\0');

        ProviderCredentialResolver restarted = new ProviderCredentialResolver(name -> null, store);
        try (CredentialLease lease = restarted.resolve(config)) {
            assertEquals(CredentialLease.Source.OS_STORE, lease.source());
            assertArrayEquals("persisted-secret".toCharArray(), lease.value());
        }
        assertTrue(store.delete(ref));
        assertThrows(CredentialStoreException.class, () -> restarted.resolve(config));
    }

    @Test void unsupportedPlatformFailsClosedWithoutPlaintextFallback() {
        Config config = Config.defaults().withProvider("openai", "model",
                "http://127.0.0.1:9", "MISSING_TEST_KEY");
        ProviderCredentialResolver resolver = new ProviderCredentialResolver(name -> null,
                new UnsupportedCredentialStore());
        assertEquals(CredentialStore.State.UNSUPPORTED,
                resolver.store().status(ProviderCredentialResolver.reference(config)).state());
        assertThrows(CredentialStoreException.class, () -> resolver.resolve(config));
    }

    @Test void windowsNativeLoadFailureIsCredentialLocalAndFailClosed() {
        CredentialStore store = CredentialStores.systemDefault("Windows 11", () -> {
            throw new UnsatisfiedLinkError("native fixture unavailable");
        });
        assertEquals(CredentialStore.State.ERROR,
                store.status(new CredentialRef("PaiCLI/provider/deepseek/default")).state());
        assertThrows(CredentialStoreException.class,
                () -> store.get(new CredentialRef("PaiCLI/provider/deepseek/default")));

        Config openAi = Config.defaults().withProvider("openai", "model",
                "http://127.0.0.1:9", "ENV_ONLY_KEY");
        ProviderCredentialResolver environmentFirst = new ProviderCredentialResolver(
                name -> "ENV_ONLY_KEY".equals(name) ? "environment-only-secret" : null, store);
        try (CredentialLease lease = environmentFirst.resolve(openAi)) {
            assertEquals(CredentialLease.Source.ENVIRONMENT, lease.source());
        }

        Config ollama = Config.defaults().withProvider("ollama", "qwen3:14b",
                "http://127.0.0.1:11434", null);
        assertInstanceOf(com.paicli.provider.OllamaProvider.class,
                com.paicli.provider.ProviderFactory.create(ollama, environmentFirst));

        ProviderCredentialResolver unavailable = new ProviderCredentialResolver(name -> null, store);
        assertEquals("error", unavailable.status(openAi).source());
    }

    private static boolean allZero(char[] value) {
        for (char character : value) if (character != '\0') return false;
        return true;
    }

    static final class FakeStore implements CredentialStore {
        private final Map<String,char[]> values = new HashMap<>();
        @Override public void put(CredentialRef reference, char[] secret) {
            values.put(reference.value(), secret.clone());
        }
        @Override public Optional<char[]> get(CredentialRef reference) {
            char[] value = values.get(reference.value());
            return value == null ? Optional.empty() : Optional.of(value.clone());
        }
        @Override public boolean delete(CredentialRef reference) {
            char[] removed = values.remove(reference.value());
            if (removed != null) Arrays.fill(removed, '\0');
            return removed != null;
        }
        @Override public Status status(CredentialRef reference) {
            return new Status(values.containsKey(reference.value()) ? State.PRESENT : State.MISSING,
                    "fake-os-store");
        }
    }
}
