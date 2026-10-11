package com.paicli.worker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WorkerStateLocationTest {
    @TempDir Path tempDir;

    @Test void derivesStableStateAndTraceOutsideAssignedWorkspace() {
        Path managed = tempDir.resolve("managed");
        Path workspace = managed.resolve("workspaces").resolve("worker-1");
        WorkerStateLocation locator = new WorkerStateLocation(managed, workspace);
        var first = locator.locate("worker-1");
        var second = locator.locate("worker-1");
        assertEquals(first, second);
        assertTrue(first.stateDatabase().startsWith(managed.resolve("state")));
        assertTrue(first.traceDirectory().startsWith(managed.resolve("traces")));
        assertFalse(first.stateDatabase().startsWith(workspace));
        assertFalse(first.traceDirectory().startsWith(workspace));
    }

    @Test void rejectsTraversalAbsoluteAndInjectedWorkerIds() {
        WorkerStateLocation locator = new WorkerStateLocation(
                tempDir.resolve("managed"), tempDir.resolve("worker-workspace"));
        assertThrows(IllegalArgumentException.class, () -> locator.locate("../escape"));
        assertThrows(IllegalArgumentException.class, () -> locator.locate("C:/escape"));
        assertThrows(IllegalArgumentException.class, () -> locator.locate("worker/child"));
        assertThrows(IllegalArgumentException.class, () -> locator.locate(".."));
    }

    @Test void rejectsManagedRootInsideAssignedWorkspace() {
        Path workspace = tempDir.resolve("workspace");
        assertThrows(IllegalArgumentException.class, () -> new WorkerStateLocation(
                workspace.resolve(".state"), workspace));
    }
}
