package com.paicli.interactive.provider;

import com.paicli.config.Config;
import com.paicli.config.ConfigLoader;
import com.paicli.config.ProviderConfigStore;
import com.paicli.credential.CredentialRef;
import com.paicli.credential.CredentialStore;
import com.paicli.credential.ProviderCredentialResolver;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.provider.ProviderHealthService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ProviderCredentialCommandTest {
    @TempDir Path temp;

    @Test void loginStatusLogoutUseMaskedPromptAndPersistOnlyReference() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("workspace"));
        Path home = Files.createDirectories(temp.resolve("home"));
        Config config = Config.defaults().withProvider("openai", "deepseek-v4-flash",
                "http://127.0.0.1:9", "DEEPSEEK_API_KEY");
        ConfigLoader loader = new ConfigLoader(workspace, home);
        InteractiveProviderState state = new InteractiveProviderState(
                new ConfigLoader.Resolution(config, ConfigLoader.Source.PROJECT));
        FakeStore credentials = new FakeStore();
        char[] entered = "credential-command-sentinel".toCharArray();
        ProviderPrompt prompt = new ProviderPrompt() {
            @Override public String read(String ignored) { fail("login must not use visible input"); return null; }
            @Override public char[] readSecret(String ignored) { return entered; }
        };
        ProviderCredentialResolver resolver = new ProviderCredentialResolver(name -> null, credentials);
        ProviderHealthService health = new ProviderHealthService(HttpClient.newHttpClient(),
                resolver, Clock.systemUTC(), Duration.ofMillis(100), Duration.ofMinutes(1));
        InteractiveProviderCommandService service = new InteractiveProviderCommandService(state,
                loader, new ProviderConfigStore(workspace, home), health, prompt, workspace, resolver);
        InteractiveSession session = new InteractiveSession("session", workspace, "openai",
                config.model(), Config.MODE_ON_REQUEST, InteractiveSession.TerminalMode.PLAIN, Instant.now());
        session.start();

        var login = service.provider(java.util.List.of("login"), session);
        assertEquals("PROVIDER_LOGIN", login.code());
        assertTrue(allZero(entered), "caller secret buffer must be cleared");
        assertEquals(CredentialStore.State.PRESENT,
                credentials.status(ProviderCredentialResolver.reference(state.config())).state());
        String projectConfig = Files.readString(workspace.resolve(".paicli/config.json"));
        assertTrue(projectConfig.contains("credential_ref"));
        assertFalse(projectConfig.contains("credential-command-sentinel"));

        var status = service.provider(java.util.List.of("credential", "status"), session);
        assertTrue(String.join("\n", status.lines()).contains("Source: os-store"));
        assertFalse(String.join("\n", status.lines()).contains("sentinel"));

        var logout = service.provider(java.util.List.of("logout"), session);
        assertEquals("PROVIDER_LOGOUT", logout.code());
        assertEquals(CredentialStore.State.MISSING,
                credentials.status(ProviderCredentialResolver.reference(state.config())).state());
    }

    @Test void unavailableBackendRejectsBeforeMaskedPrompt() throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("unavailable-workspace"));
        Path home = Files.createDirectories(temp.resolve("unavailable-home"));
        Config config = Config.defaults().withProvider("openai", "model",
                "http://127.0.0.1:9", "MISSING_KEY");
        ConfigLoader loader = new ConfigLoader(workspace, home);
        InteractiveProviderState state = new InteractiveProviderState(
                new ConfigLoader.Resolution(config, ConfigLoader.Source.SESSION));
        java.util.concurrent.atomic.AtomicInteger prompts = new java.util.concurrent.atomic.AtomicInteger();
        CredentialStore unavailable = new CredentialStore() {
            @Override public void put(CredentialRef ref,char[] value){throw new AssertionError();}
            @Override public Optional<char[]> get(CredentialRef ref){return Optional.empty();}
            @Override public boolean delete(CredentialRef ref){return false;}
            @Override public Status status(CredentialRef ref){return new Status(State.ERROR,"windows-credential-manager");}
        };
        ProviderCredentialResolver resolver = new ProviderCredentialResolver(name -> null, unavailable);
        ProviderHealthService health = new ProviderHealthService(HttpClient.newHttpClient(),
                resolver, Clock.systemUTC(), Duration.ofMillis(100), Duration.ofMinutes(1));
        ProviderPrompt prompt = new ProviderPrompt() {
            @Override public String read(String ignored){return null;}
            @Override public char[] readSecret(String ignored){prompts.incrementAndGet();return null;}
        };
        InteractiveProviderCommandService service = new InteractiveProviderCommandService(state,
                loader,new ProviderConfigStore(workspace,home),health,prompt,workspace,resolver);
        InteractiveSession session = new InteractiveSession("session",workspace,"openai","model",
                Config.MODE_ON_REQUEST,InteractiveSession.TerminalMode.PLAIN,Instant.now());
        session.start();
        var result=service.provider(java.util.List.of("login"),session);
        assertTrue(String.join("\n",result.lines()).contains("CREDENTIAL_STORE_UNAVAILABLE"));
        assertEquals(0,prompts.get());
    }

    private static boolean allZero(char[] value) {
        for (char character : value) if (character != '\0') return false;
        return true;
    }

    private static final class FakeStore implements CredentialStore {
        private final Map<String,char[]> values = new HashMap<>();
        @Override public void put(CredentialRef reference, char[] secret) { values.put(reference.value(), secret.clone()); }
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
                    "fake-windows-credential-manager");
        }
    }
}
