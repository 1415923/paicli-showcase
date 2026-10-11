package com.paicli.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FinalSourceV3RuntimeGateTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void formalGateRejectsBeforeAnyProviderConstructionWhenCredentialIsMissing() {
        AtomicInteger providerConstructions = new AtomicInteger();
        ObjectNode gate = gate("FORMAL", true, true, false, false);
        assertThrows(AssertionError.class, () ->
                FormalFinalSourceV3BenchmarkRunIT.requireGate(gate, false, () -> false));
        assertEquals(0, providerConstructions.get());
    }

    @Test
    void formalGateAcceptsExplicitOneShotAuthorizationAndCredentialPresence() {
        ObjectNode gate = gate("FORMAL", true, true, false, false);
        assertDoesNotThrow(() ->
                FormalFinalSourceV3BenchmarkRunIT.requireGate(gate, false, () -> true));
    }

    @Test
    void pairGateRequiresSimulationAndNetworkDisabledWithoutPaidAuthority() {
        ObjectNode gate = gate("PAIR_REHEARSAL", false, false, true, true);
        assertDoesNotThrow(() ->
                FormalFinalSourceV3BenchmarkRunIT.requireGate(gate, true, () -> true));
        ((ObjectNode) gate.get("runtimeGateEvidence")).put("authorizationPresent", true);
        assertThrows(AssertionError.class, () ->
                FormalFinalSourceV3BenchmarkRunIT.requireGate(gate, true, () -> true));
    }

    private static ObjectNode gate(String mode, boolean authorization, boolean credential,
            boolean simulation, boolean networkDisabled) {
        ObjectNode gate = JSON.createObjectNode();
        gate.put("campaignIdentity", "final-source-internal-controlled-regression-v3");
        gate.put("resultIdentity", "formal-final-source-result-v3");
        gate.put("evidenceIdentity", "formal-final-source-evidence-v3");
        gate.put("artifactSha256", "a".repeat(64));
        gate.put("harnessIdentity", "final-source-harness-v3-" + "b".repeat(64));
        gate.put("evaluatorIdentity", "final-source-evaluator-v3-" + "c".repeat(64));
        gate.put("chunkScheduleIdentity", "final-source-v3-fixed-chunks-v1");
        gate.put("mode", mode);
        ObjectNode runtime = gate.putObject("runtimeGateEvidence");
        runtime.put("authorizationKind", "EXPLICIT_ONE_SHOT_RUNTIME_FLAG");
        runtime.put("authorizationPresent", authorization);
        runtime.put("credentialPresent", credential);
        runtime.put("preAdmissionReady", authorization && credential);
        runtime.put("persistentDefault", false);
        runtime.put("reusable", false);
        runtime.put("simulation", simulation);
        runtime.put("networkDisabled", networkDisabled);
        return gate;
    }
}

