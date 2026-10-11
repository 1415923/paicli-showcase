package com.paicli.demo.scenario;

import java.util.List;

public record CoreDemoScenarioContract(
        int scenarioContractSchemaVersion,
        String scenarioId,
        String fixtureVersion,
        String fixtureManifestHash,
        String fixedTaskId,
        String fixedTaskText,
        String mutableFile,
        String expectedBaselineTreeHash,
        String expectedInitialImplementationTreeHash,
        String expectedFinalTreeHash,
        VerificationExpectation expectedFirstVerification,
        VerificationExpectation expectedFinalVerification,
        int expectedRepairAttempts,
        List<String> expectedChangedFiles,
        int expectedSnapshotMinimum,
        List<String> expectedCodeIntelligenceFiles,
        List<String> expectedCodeIntelligenceSymbols,
        List<String> expectedPrimaryPhaseSequence,
        String planContractVersion,
        String providerScriptVersion,
        String verificationPolicyVersion,
        String approvalProfileVersion) {

    public record VerificationExpectation(
            int tests,
            int failures,
            int errors,
            int skipped,
            String failingTestClass,
            String failingTestName) {
    }
}