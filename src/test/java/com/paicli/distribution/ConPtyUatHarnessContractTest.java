package com.paicli.distribution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConPtyUatHarnessContractTest {
    @Test
    @DisplayName("REG-01 / CMP-01 provider divergence and Compact/resume Offline UAT contracts")
    void harnessUsesDistributionConPtyLoopbackProviderAndBoundedScenarios() throws Exception {
        String powershell = Files.readString(Path.of("scripts/uat/run-paicli-uat.ps1"));
        String driver = Files.readString(Path.of("scripts/uat/paicli_uat.py"));
        assertTrue(powershell.contains("target\\dist\\paicli-1.2.0.zip"));
        assertTrue(powershell.contains("ValidateSet('Offline')"));
        assertFalse(powershell.contains("Start-Process"));
        assertTrue(driver.contains("Backend.ConPTY"));
        assertTrue(driver.contains("PtyProcess.spawn"));
        assertTrue(driver.contains("127.0.0.1"));
        assertTrue(driver.contains("readonly-single-tool"));
        assertTrue(driver.contains("approval-reject"));
        assertTrue(driver.contains("approval-cancel"));
        assertTrue(driver.contains("ctrl-c-exit"));
        assertTrue(driver.contains("manual-compact"));
        assertTrue(driver.contains("auto-long-task"));
        assertTrue(driver.contains("write-verify-rewind"));
        assertTrue(driver.contains("structured-verify-fail-closed"));
        assertTrue(driver.contains("session-resume-continue"));
        assertTrue(driver.contains("plan-tool-scope-sequential-deviation"));
        assertTrue(driver.contains("plan-tool-scope-multi-tool-batch"));
        assertTrue(driver.contains("plan-evidence-semantic-count"));
        assertTrue(driver.contains("plan-v2-unfinished-fail-closed"));
        assertTrue(driver.contains("DEFAULT_ADVISORY_PLAN"));
        assertTrue(driver.contains("eligibleBundleAdvancedEffectivePointers"));
        assertTrue(driver.contains("_task_tool_boundary_reached"));
        assertTrue(driver.contains("planRunBindings"));
        assertTrue(driver.contains("class NotReached"));
        assertTrue(driver.contains("\"status\": \"NOT_REACHED\""));
        assertTrue(driver.contains("Context compacted"));
        assertTrue(driver.contains("effectiveReductions"));
        assertTrue(driver.contains("durableEventsContiguous"));
        assertTrue(driver.contains("distinctToolCallIds"));
        assertTrue(driver.contains("repairAttempts"));
        assertTrue(driver.contains("completionDecisions"));
        assertTrue(driver.contains("residualJavaAfterCleanup"));
        assertTrue(driver.contains("fault_injector"));
        assertTrue(driver.contains("\"logicalTurn\""));
        assertTrue(driver.contains("\"actualAttempt\""));
        assertTrue(driver.contains("\"requestDigest\""));
        assertTrue(driver.contains("results.json"));
        assertTrue(driver.contains("summary.md"));
        assertTrue(driver.contains("readonly-single-tool-80x24.html"));
        assertFalse(driver.contains("api.deepseek.com"));
        assertFalse(driver.contains("shell=True"));
        assertFalse(driver.contains("UNKNOWN/STARTED write"));
        assertFalse(driver.contains("healthy-owner takeover"));
    }

    @Test void maskedInputUsesJLineNoEchoAndNoSecondReader() throws Exception {
        String input = Files.readString(Path.of(
                "src/main/java/com/paicli/interactive/shell/JLineShellInputReader.java"));
        String coordinator = Files.readString(Path.of(
                "src/main/java/com/paicli/interactive/shell/InteractiveInputCoordinator.java"));
        assertTrue(input.contains("Character.valueOf('\\0')"));
        assertTrue(input.contains("readSecret"));
        assertTrue(coordinator.contains("input.readSecret"));
        assertFalse(coordinator.contains("new JLineShellInputReader"));
    }

    @Test void providerCredentialCommandsAreDiscoverableWithoutAcceptingSecretArguments()
            throws Exception {
        String registry = Files.readString(Path.of(
                "src/main/java/com/paicli/interactive/command/SlashCommandRegistry.java"));
        String completer = Files.readString(Path.of(
                "src/main/java/com/paicli/interactive/completion/SlashCommandCompleter.java"));
        assertTrue(registry.contains("credential status|login|logout"));
        assertTrue(completer.contains("\"credential\", \"login\", \"logout\""));
        assertTrue(completer.contains("return List.of(\"status\")"));
    }
}
