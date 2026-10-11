package com.paicli.demo.scenario;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.demo.fixture.CoreDemoFixtureException;
import com.paicli.demo.fixture.CoreDemoFixtureManifestLoader;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class CoreDemoScenarioContractLoader {
    public static final String RESOURCE =
            CoreDemoFixtureManifestLoader.ROOT + "core-demo-scenario.json";
    private static final String TASK_ID = "core-java-member-discount-task-v1";
    private static final String MUTABLE =
            "src/main/java/com/paicli/demo/order/PricingService.java";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern VERSION = Pattern.compile("[a-z0-9][a-z0-9.-]{0,63}");
    private final ClassLoader resources;
    private final ObjectMapper json = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public CoreDemoScenarioContractLoader() {
        this(CoreDemoScenarioContractLoader.class.getClassLoader());
    }

    public CoreDemoScenarioContractLoader(ClassLoader resources) {
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    public LoadedContract load() throws CoreDemoFixtureException {
        byte[] bytes;
        try (InputStream input = resources.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new CoreDemoFixtureException("Core Demo scenario contract is missing");
            }
            bytes = input.readAllBytes();
        } catch (CoreDemoFixtureException error) {
            throw error;
        } catch (Exception error) {
            throw new CoreDemoFixtureException("Core Demo scenario contract could not be read", error);
        }
        try {
            CoreDemoScenarioContract contract =
                    json.readValue(bytes, CoreDemoScenarioContract.class);
            validate(contract);
            var fixture = new CoreDemoFixtureManifestLoader(resources).load();
            if (!contract.scenarioId().equals(fixture.manifest().scenarioId())
                    || !contract.fixtureVersion().equals(fixture.manifest().fixtureVersion())
                    || !contract.fixtureManifestHash().equals(fixture.manifestHash())
                    || !contract.expectedBaselineTreeHash()
                    .equals(fixture.manifest().expectedBaselineTreeHash())
                    || !fixture.manifest().allowedMutablePaths().equals(List.of(MUTABLE))) {
                throw new CoreDemoFixtureException(
                        "Core Demo scenario contract does not match fixture manifest");
            }
            return new LoadedContract(contract,
                    CoreDemoContractHash.sha256(bytes), bytes);
        } catch (CoreDemoFixtureException error) {
            throw error;
        } catch (Exception error) {
            throw new CoreDemoFixtureException("Core Demo scenario contract is invalid", error);
        }
    }

    static void validate(CoreDemoScenarioContract contract)
            throws CoreDemoFixtureException {
        if (contract == null || contract.scenarioContractSchemaVersion() != 1) {
            throw new CoreDemoFixtureException("Unsupported Core Demo scenario schema");
        }
        text(contract.scenarioId(), 128, "scenarioId");
        text(contract.fixtureVersion(), 32, "fixtureVersion");
        sha(contract.fixtureManifestHash(), "fixtureManifestHash");
        if (!TASK_ID.equals(contract.fixedTaskId())
                || contract.fixedTaskText() == null
                || contract.fixedTaskText().isBlank()
                || contract.fixedTaskText().length() > 512
                || !MUTABLE.equals(contract.mutableFile())) {
            throw new CoreDemoFixtureException("Invalid fixed Core Demo task contract");
        }
        sha(contract.expectedBaselineTreeHash(), "expectedBaselineTreeHash");
        sha(contract.expectedInitialImplementationTreeHash(),
                "expectedInitialImplementationTreeHash");
        sha(contract.expectedFinalTreeHash(), "expectedFinalTreeHash");
        verify(contract.expectedFirstVerification(), 3, 1,
                "com.paicli.demo.order.PricingServiceTest",
                "memberDiscountUsesRoundedSubtotal");
        verify(contract.expectedFinalVerification(), 3, 0, null, null);
        if (contract.expectedRepairAttempts() != 1
                || contract.expectedSnapshotMinimum() != 2
                || !List.of(MUTABLE).equals(contract.expectedChangedFiles())) {
            throw new CoreDemoFixtureException("Invalid Core Demo mutation contract");
        }
        exactUnique(contract.expectedCodeIntelligenceFiles(), Set.of(
                "src/main/java/com/paicli/demo/order/MoneyRules.java",
                MUTABLE,
                "src/test/java/com/paicli/demo/order/PricingServiceTest.java"),
                "code intelligence files");
        exactUnique(contract.expectedCodeIntelligenceSymbols(),
                Set.of("MoneyRules", "PricingService", "PricingServiceTest"),
                "code intelligence symbols");
        if (!List.of("PLAN", "CODE_INTELLIGENCE", "EXECUTE", "VERIFY",
                "CHECKPOINT", "REPAIR", "VERIFY")
                .equals(contract.expectedPrimaryPhaseSequence())) {
            throw new CoreDemoFixtureException("Invalid primary phase sequence");
        }
        version(contract.planContractVersion(), "planContractVersion");
        version(contract.providerScriptVersion(), "providerScriptVersion");
        version(contract.verificationPolicyVersion(), "verificationPolicyVersion");
        version(contract.approvalProfileVersion(), "approvalProfileVersion");
    }

    private static void verify(
            CoreDemoScenarioContract.VerificationExpectation expected,
            int tests, int failures, String failureClass, String failureName)
            throws CoreDemoFixtureException {
        if (expected == null || expected.tests() != tests
                || expected.failures() != failures || expected.errors() != 0
                || expected.skipped() != 0
                || !Objects.equals(failureClass, expected.failingTestClass())
                || !Objects.equals(failureName, expected.failingTestName())) {
            throw new CoreDemoFixtureException("Invalid verification expectation");
        }
    }

    private static void exactUnique(List<String> values, Set<String> expected,
                                    String field) throws CoreDemoFixtureException {
        if (values == null || values.size() != expected.size()
                || !new HashSet<>(values).equals(expected)) {
            throw new CoreDemoFixtureException("Invalid " + field);
        }
        for (String value : values) {
            if (value.contains("/")) {
                CoreDemoFixtureManifestLoader.validateRelativePath(value);
            } else {
                text(value, 128, field);
            }
        }
    }

    private static void text(String value, int max, String field)
            throws CoreDemoFixtureException {
        if (value == null || value.isBlank() || value.length() > max
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new CoreDemoFixtureException("Invalid " + field);
        }
    }

    private static void sha(String value, String field)
            throws CoreDemoFixtureException {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new CoreDemoFixtureException("Invalid " + field);
        }
    }

    private static void version(String value, String field)
            throws CoreDemoFixtureException {
        if (value == null || !VERSION.matcher(value).matches()) {
            throw new CoreDemoFixtureException("Invalid " + field);
        }
    }

    public record LoadedContract(
            CoreDemoScenarioContract contract,
            String contractHash,
            byte[] contractBytes) {
        public LoadedContract {
            contractBytes = contractBytes.clone();
        }

        @Override
        public byte[] contractBytes() {
            return contractBytes.clone();
        }
    }
}