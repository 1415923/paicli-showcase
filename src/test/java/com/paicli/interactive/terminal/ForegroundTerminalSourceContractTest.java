package com.paicli.interactive.terminal;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForegroundTerminalSourceContractTest {
    @Test
    void productionDoesNotSuppressIsigOrBindControlCToSendBreak() throws Exception {
        String guard = source(
                "interactive/terminal/TerminalAttributesGuard.java");
        String reader = source(
                "interactive/shell/JLineShellInputReader.java");

        assertFalse(guard.contains("LocalFlag.ISIG, false"));
        assertTrue(guard.contains("LocalFlag.ISIG, true"));
        assertFalse(reader.contains("LineReader.SEND_BREAK"));
        assertFalse(reader.contains(".raise(Terminal.Signal.INT)"));
    }

    @Test
    void foregroundApprovalUsesTheExistingJLineOwnerAndEditableLineBuffer()
            throws Exception {
        String reader = source(
                "interactive/shell/JLineShellInputReader.java");
        String coordinator = source(
                "interactive/shell/InteractiveInputCoordinator.java");

        assertTrue(reader.contains("reader.readLine(TerminalSemanticStyle.prompt("));
        assertTrue(reader.contains("ownershipValid.getAsBoolean()"));
        assertFalse(reader.contains("terminalInput.read(50L)"));
        assertFalse(reader.contains("new BufferedReader"));
        assertFalse(reader.contains("new Thread"));
        assertTrue(coordinator.contains("input.readForegroundChoice("));
    }

    @Test
    void durableAdmissionPrecedesOwnershipBoundTaskStartedSignal() throws Exception {
        String runner = source("runtime/CodingTaskRunner.java");

        int admission = runner.indexOf("instructionManifestRuntime.admitRoot(");
        int ownership = runner.indexOf("stateStore.r1aDurable.acquireOwnership(",
                admission);
        int bound = runner.indexOf("observer.executionAuthorityBound(guard)",
                ownership);
        int started = runner.indexOf("observer.taskStarted(taskId", bound);

        assertTrue(admission >= 0);
        assertTrue(ownership > admission);
        assertTrue(bound > ownership);
        assertTrue(started > bound);
    }

    private static String source(String relative) throws Exception {
        return Files.readString(Path.of("src/main/java/com/paicli")
                .resolve(relative));
    }
}
