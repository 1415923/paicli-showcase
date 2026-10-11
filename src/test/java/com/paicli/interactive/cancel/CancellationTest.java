package com.paicli.interactive.cancel;

import com.paicli.runtime.OwnedProcessRegistry;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class CancellationTest {
    @Test void firstRequestIsCooperativeAndSecondKillsOnlyOwnedProcess() {
        InteractiveCancellationController controller = new InteractiveCancellationController();
        FakeProcess owned = new FakeProcess();
        try (OwnedProcessRegistry.Registration ignored =
                     controller.processes().register(owned)) {
            var first = controller.request();
            assertEquals(InteractiveCancellationController.Stage.COOPERATIVE, first.stage());
            assertTrue(owned.isAlive());
            var second = controller.request();
            assertEquals(InteractiveCancellationController.Stage.FORCED, second.stage());
            assertEquals(1, second.terminatedOwnedProcesses());
            assertFalse(owned.isAlive());
            assertTrue(controller.forceRequested());
        }
    }

    @Test void durableTerminalResetMakesNextTaskCancellableFromStageOne() {
        InteractiveCancellationController controller =
                new InteractiveCancellationController();
        controller.request();
        controller.resetAfterTerminal();

        assertFalse(controller.cancellationRequested());
        assertFalse(controller.forceRequested());
        assertEquals(InteractiveCancellationController.Stage.COOPERATIVE,
                controller.request().stage());
    }

    private static final class FakeProcess extends Process {
        private volatile boolean alive = true;
        public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        public int waitFor() { alive=false; return 0; }
        public boolean waitFor(long timeout, TimeUnit unit) { return !alive; }
        public int exitValue() { if(alive) throw new IllegalThreadStateException(); return 0; }
        public void destroy() { }
        public Process destroyForcibly() { alive=false; return this; }
        public boolean isAlive() { return alive; }
    }
}
