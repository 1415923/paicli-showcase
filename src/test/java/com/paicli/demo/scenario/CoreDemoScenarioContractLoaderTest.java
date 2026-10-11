package com.paicli.demo.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.demo.fixture.CoreDemoFixtureException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CoreDemoScenarioContractLoaderTest {
    @Test
    void loadsFrozenProductionContract() throws Exception {
        var loaded = new CoreDemoScenarioContractLoader().load();
        var contract = loaded.contract();

        assertEquals("core-java-member-discount-v1", contract.scenarioId());
        assertEquals("e492c0573b916f2067e7c7f7e58e1cc5ff781c9e37226e17bb12584d6da477a7",
                contract.expectedBaselineTreeHash());
        assertEquals("94eacb50ef6873e58072009a172e27e03fcc45d18fe00e2b50f306e294f79d8f",
                contract.expectedInitialImplementationTreeHash());
        assertEquals("49400b5195a412415e2ad9f28f1ba562759d5c48bd1ebd9f6e19ad9d43605a47",
                contract.expectedFinalTreeHash());
        assertEquals(3, contract.expectedFirstVerification().tests());
        assertEquals(1, contract.expectedFirstVerification().failures());
        assertEquals("memberDiscountUsesRoundedSubtotal",
                contract.expectedFirstVerification().failingTestName());
        assertEquals(3, contract.expectedFinalVerification().tests());
        assertEquals(0, contract.expectedFinalVerification().failures());
        assertEquals(1, contract.expectedChangedFiles().size());
        assertEquals(contract.mutableFile(), contract.expectedChangedFiles().getFirst());
        assertEquals(64, loaded.contractHash().length());
    }

    @Test
    void rejectsContractHashOrTaskDrift() throws Exception {
        byte[] original = getClass().getClassLoader()
                .getResourceAsStream(CoreDemoScenarioContractLoader.RESOURCE)
                .readAllBytes();
        ObjectNode root = (ObjectNode) new ObjectMapper().readTree(original);
        root.put("fixedTaskId", "attacker-task");
        ClassLoader resources = overlay(Map.of(
                CoreDemoScenarioContractLoader.RESOURCE,
                new ObjectMapper().writeValueAsBytes(root)));

        assertThrows(CoreDemoFixtureException.class,
                () -> new CoreDemoScenarioContractLoader(resources).load());
    }

    private ClassLoader overlay(Map<String, byte[]> replacements) {
        ClassLoader parent = getClass().getClassLoader();
        return new ClassLoader(parent) {
            @Override
            public java.io.InputStream getResourceAsStream(String name) {
                byte[] replacement = replacements.get(name);
                return replacement == null ? super.getResourceAsStream(name)
                        : new ByteArrayInputStream(replacement);
            }
        };
    }
}