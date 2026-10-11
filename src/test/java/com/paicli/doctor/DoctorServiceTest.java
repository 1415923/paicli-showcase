package com.paicli.doctor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DoctorServiceTest {
    @TempDir Path root;

    @Test
    void absentProviderIsWarnAndDoesNotCreateControlDirectories() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        DoctorReport report = service(Map.of()).inspect();
        assertEquals(DoctorStatus.WARN, report.overall());
        assertEquals(0, report.exitCode());
        assertStatus(report, "Provider", DoctorStatus.WARN);
        assertFalse(Files.exists(root.resolve(".paicli")));
    }

    @Test
    void malformedProjectConfigFailsWithoutEchoingContent() throws Exception {
        Path config = Files.createDirectories(root.resolve(".paicli"))
                .resolve("config.json");
        Files.writeString(config, "{secret:DOCTOR_SECRET_SENTINEL");
        DoctorReport report = service(Map.of()).inspect();
        assertEquals(DoctorStatus.FAIL, report.overall());
        assertEquals(1, report.exitCode());
        assertStatus(report, "Project config", DoctorStatus.FAIL);
        assertTrue(report.checks().stream().noneMatch(
                check -> check.detail().contains("DOCTOR_SECRET_SENTINEL")));
    }

    @Test
    void explicitMockFailsAndOpenAiChecksOnlySecretPresence() throws Exception {
        writeConfig("{\"provider\":\"mock\"}");
        DoctorReport mock = service(Map.of()).inspect();
        assertStatus(mock, "Provider", DoctorStatus.FAIL);
        assertEquals(1, mock.exitCode());
        assertTrue(mock.checks().stream().anyMatch(check -> check.detail()
                .contains(com.paicli.provider.ProviderFactory.MOCK_UNSUPPORTED_CODE)));

        var health = new com.paicli.provider.ProviderHealthService().inspect(
                com.paicli.config.Config.defaults().withProvider("mock", null, null, null), false);
        assertFalse(health.reachable());
        assertFalse(health.ready());
        assertEquals(com.paicli.provider.ProviderFactory.MOCK_UNSUPPORTED_CODE,
                health.errorCategory());

        writeConfig("{\"provider\":\"openai\",\"api_key_env\":\"DEMO_KEY\"}");
        DoctorReport report = service(Map.of("DEMO_KEY", "DOCTOR_SECRET_SENTINEL"))
                .inspect();
        assertStatus(report, "Provider", DoctorStatus.PASS);
        assertStatus(report, "API key environment", DoctorStatus.PASS);
        assertTrue(report.checks().stream().noneMatch(
                check -> check.detail().contains("DOCTOR_SECRET_SENTINEL")));
    }

    @Test
    void commandOutputIsPlainBoundedAndWarnReturnsZero() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        StringWriter text = new StringWriter();
        int exit = new DoctorCommand(service(Map.of()))
                .execute(new PrintWriter(text, true));
        assertEquals(0, exit);
        assertTrue(text.toString().contains("PaiCLI Doctor"));
        assertTrue(text.toString().contains("Overall: WARN"));
        assertFalse(text.toString().contains("\u001b["));
        assertTrue(text.toString().length() < 4096);
    }

    @Test
    void probeFailureAndMissingUserHomeBecomeBoundedFailures() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        DoctorService service = new DoctorService(root, null,
                ignored -> { throw new IllegalStateException("SECRET_SENTINEL"); },
                ignored -> null);
        DoctorReport report = assertDoesNotThrow(service::inspect);
        assertEquals(DoctorStatus.FAIL, report.overall());
        assertStatus(report, "Maven", DoctorStatus.FAIL);
        assertStatus(report, "Logs", DoctorStatus.FAIL);
        assertTrue(report.checks().stream().noneMatch(
                check -> check.detail().contains("SECRET_SENTINEL")));
    }
    private DoctorService service(Map<String, String> environment) {
        return new DoctorService(root, root,
                ignored -> new DoctorService.MavenProbeResult(true, "3.9.9"),
                environment::get);
    }

    private void writeConfig(String json) throws Exception {
        Path config = Files.createDirectories(root.resolve(".paicli"))
                .resolve("config.json");
        Files.writeString(config, json);
    }

    private static void assertStatus(
            DoctorReport report, String name, DoctorStatus status) {
        assertEquals(status, report.checks().stream()
                .filter(check -> name.equals(check.name()))
                .findFirst().orElseThrow().status());
    }
}
