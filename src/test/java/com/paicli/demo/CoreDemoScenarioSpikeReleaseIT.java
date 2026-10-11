package com.paicli.demo;

import com.paicli.demo.fixture.CoreDemoFixtureExtractor;
import com.paicli.demo.fixture.CoreDemoFixtureManifestLoader;
import com.paicli.demo.fixture.CoreDemoTreeHasher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Test-only scenario spike; it does not claim AgentLoop or headless runner integration. */
class CoreDemoScenarioSpikeReleaseIT {
    private static final String EXPECTED_SEED_HASH = "e492c0573b916f2067e7c7f7e58e1cc5ff781c9e37226e17bb12584d6da477a7";
    private static final String EXPECTED_INITIAL_HASH = "94eacb50ef6873e58072009a172e27e03fcc45d18fe00e2b50f306e294f79d8f";
    private static final String EXPECTED_FINAL_HASH = "49400b5195a412415e2ad9f28f1ba562759d5c48bd1ebd9f6e19ad9d43605a47";
    @TempDir Path temporaryRoot;

    @Test void baselineFailureRepairAndHashesAreRepeatable() throws Exception {
        String seedBefore = new CoreDemoFixtureManifestLoader().load().manifest().expectedBaselineTreeHash();
        ScenarioResult first = runScenario("run-one");
        ScenarioResult second = runScenario("run two with spaces");
        assertEquals(EXPECTED_SEED_HASH, seedBefore);
        assertEquals(new TestFacts(3, 0, 0, 0, List.of()), first.baseline.facts);
        assertTrue(first.baseline.success, first.baseline.output);
        assertEquals(new TestFacts(3, 1, 0, 0, List.of("memberDiscountUsesRoundedSubtotal")), first.initial.facts);
        assertFalse(first.initial.success, "incomplete implementation must fail real tests");
        assertEquals(new TestFacts(3, 0, 0, 0, List.of()), first.repaired.facts);
        assertTrue(first.repaired.success, first.repaired.output);
        assertEquals(EXPECTED_INITIAL_HASH, first.initialHash);
        assertEquals(EXPECTED_FINAL_HASH, first.finalHash);
        assertEquals(first.initialHash, second.initialHash);
        assertEquals(first.finalHash, second.finalHash);
        assertEquals(seedBefore, new CoreDemoFixtureManifestLoader().load().manifest().expectedBaselineTreeHash(), "fixture seed must remain unchanged");
    }

    @Test void sameNameWithWrongSignatureFailsInsteadOfUsingBaselineCompatibility() throws Exception {
        Path workspace = temporaryRoot.resolve("wrong-signature");
        var loader = new CoreDemoFixtureManifestLoader();
        new CoreDemoFixtureExtractor(loader).extract(workspace, loader.load());
        Path service = workspace.resolve("src/main/java/com/paicli/demo/order/PricingService.java");
        String source = Files.readString(service).replace("\n}\n", """
                    public BigDecimal memberTotal() { return BigDecimal.ZERO; }
                }
                """);
        Files.writeString(service, source, StandardCharsets.UTF_8);
        MavenResult result = runMaven(workspace);
        assertFalse(result.success);
        assertEquals(new TestFacts(3, 1, 0, 0,
                List.of("memberDiscountUsesRoundedSubtotal")), result.facts);
    }
    private ScenarioResult runScenario(String name) throws Exception {
        Path workspace = temporaryRoot.resolve(name);
        var loader = new CoreDemoFixtureManifestLoader();
        var loaded = loader.load();
        new CoreDemoFixtureExtractor(loader).extract(workspace, loaded);
        Map<String, String> immutableBefore = immutableHashes(workspace, loaded);
        MavenResult baseline = runMaven(workspace);
        assertTrue(baseline.success, baseline.output);
        applyIncompleteChange(workspace);
        assertEquals(immutableBefore, immutableHashes(workspace, loaded));
        String initialHash = CoreDemoTreeHasher.hashWorkspace(workspace, loaded.manifest()).aggregateHash();
        MavenResult initial = runMaven(workspace);
        applyRepair(workspace);
        assertEquals(immutableBefore, immutableHashes(workspace, loaded));
        String finalHash = CoreDemoTreeHasher.hashWorkspace(workspace, loaded.manifest()).aggregateHash();
        return new ScenarioResult(baseline, initial, runMaven(workspace), initialHash, finalHash);
    }

    private void applyIncompleteChange(Path workspace) throws Exception {
        Path service = workspace.resolve("src/main/java/com/paicli/demo/order/PricingService.java");
        Files.writeString(service, """
                package com.paicli.demo.order;
                import java.math.BigDecimal;
                public class PricingService {
                    private static final BigDecimal MEMBER_RATE = new BigDecimal("0.90");
                    public BigDecimal subtotal(Order order) {
                        BigDecimal raw = order.lines().stream().map(OrderLine::rawTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
                        return MoneyRules.money(raw);
                    }
                    public BigDecimal memberTotal(Order order) {
                        return order.lines().stream().map(line -> MoneyRules.money(line.rawTotal().multiply(MEMBER_RATE))).reduce(BigDecimal.ZERO, BigDecimal::add);
                    }
                }
                """, StandardCharsets.UTF_8);
    }

    private void applyRepair(Path workspace) throws Exception {
        Path service = workspace.resolve("src/main/java/com/paicli/demo/order/PricingService.java");
        String source = Files.readString(service);
        Files.writeString(service, source.replace(
                "return order.lines().stream().map(line -> MoneyRules.money(line.rawTotal().multiply(MEMBER_RATE))).reduce(BigDecimal.ZERO, BigDecimal::add);",
                "return MoneyRules.money(subtotal(order).multiply(MEMBER_RATE));"), StandardCharsets.UTF_8);
    }

    private MavenResult runMaven(Path workspace) throws Exception {
        String mvn = System.getProperty("os.name").toLowerCase().contains("win") ? "mvn.cmd" : "mvn";
        Path outputFile = workspace.resolve(".maven-output.log");
        Process process = new ProcessBuilder(mvn, "-o", "-q", "test")
                .directory(workspace.toFile()).redirectErrorStream(true)
                .redirectOutput(outputFile.toFile()).start();
        if (!process.waitFor(Duration.ofSeconds(90).toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly(); fail("fixture Maven process timed out");
        }
        byte[] bounded;
        try (var input = Files.newInputStream(outputFile)) {
            bounded = input.readNBytes(8193);
        }
        String output = new String(bounded, 0, Math.min(8192, bounded.length), StandardCharsets.UTF_8)
                .replace(temporaryRoot.toString(), "<demo-workspace>");
return new MavenResult(process.exitValue() == 0, output, readSurefireFacts(workspace));
    }


    private static TestFacts readSurefireFacts(Path workspace) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        int tests = 0, failures = 0, errors = 0, skipped = 0;
        List<String> failed = new java.util.ArrayList<>();
        try (var reports = Files.list(workspace.resolve("target/surefire-reports"))) {
            for (Path report : reports.filter(path -> path.getFileName().toString().startsWith("TEST-"))
                    .filter(path -> path.getFileName().toString().endsWith(".xml")).toList()) {
                Element suite = factory.newDocumentBuilder().parse(report.toFile()).getDocumentElement();
                tests += Integer.parseInt(suite.getAttribute("tests"));
                failures += Integer.parseInt(suite.getAttribute("failures"));
                errors += Integer.parseInt(suite.getAttribute("errors"));
                skipped += Integer.parseInt(suite.getAttribute("skipped"));
                var cases = suite.getElementsByTagName("testcase");
                for (int i = 0; i < cases.getLength(); i++) {
                    Element test = (Element) cases.item(i);
                    if (test.getElementsByTagName("failure").getLength() > 0
                            || test.getElementsByTagName("error").getLength() > 0) {
                        failed.add(test.getAttribute("name"));
                    }
                }
            }
        }
        return new TestFacts(tests, failures, errors, skipped, List.copyOf(failed));
    }
    private static Map<String, String> immutableHashes(Path workspace,
            CoreDemoFixtureManifestLoader.LoadedManifest loaded) {
        return loaded.manifest().files().stream()
                .filter(file -> !loaded.manifest().allowedMutablePaths().contains(file.relativePath()))
                .collect(Collectors.toUnmodifiableMap(
                        file -> file.relativePath(), file -> sha256(workspace.resolve(file.relativePath()))));
    }

    private static String sha256(Path file) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
    private record MavenResult(boolean success, String output, TestFacts facts) {}
    private record TestFacts(int tests, int failures, int errors, int skipped, List<String> failedTests) {}
    private record ScenarioResult(MavenResult baseline, MavenResult initial, MavenResult repaired, String initialHash, String finalHash) {}
}
