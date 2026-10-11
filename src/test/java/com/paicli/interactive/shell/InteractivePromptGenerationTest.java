package com.paicli.interactive.shell;

import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.task.InteractiveTaskRequest;
import com.paicli.interactive.task.TaskSubmissionPort;
import com.paicli.interactive.task.TaskSubmissionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class InteractivePromptGenerationTest {
    @TempDir Path workspace;

    @Disabled("Deferred v1.3 retained composer prompt-generation contract")
    @Test
    void terminalHandoffInvalidatesRunningPromptGenerationWithoutSecondRead() throws Exception {
        GenerationReader reader = new GenerationReader();
        AtomicReference<Runnable> terminal = new AtomicReference<>(() -> { });
        TaskSubmissionPort runtime = new TaskSubmissionPort() {
            @Override public TaskSubmissionResult submit(InteractiveTaskRequest request) {
                return TaskSubmissionResult.accepted("accepted");
            }
            @Override public void onTerminal(Runnable listener) {
                terminal.set(listener);
            }
            @Override public String currentPhase() {
                return "EXECUTING";
            }
        };
        InteractiveInputCoordinator coordinator = new InteractiveInputCoordinator(reader);
        coordinator.bindRuntime(runtime);
        InteractiveSession session = session();
        session.startTask("task-1", "run-1");
        reader.duringRead = () -> {
            session.finishTask("task-1");
            terminal.get().run();
        };

        var frame=coordinator.read(session, new InteractivePromptRenderer());

        assertEquals(1, reader.readCount);
        assertEquals(1, reader.refreshPrompts.size());
        assertEquals("paicli> ", reader.refreshPrompts.getFirst());
        assertTrue(reader.refreshGenerations.getFirst() > reader.readGeneration);
        assertEquals(InteractiveInputCoordinator.State.IDLE, coordinator.state());
        assertEquals(InteractiveInputCoordinator.State.IDLE,frame.state());
        assertEquals("/status",frame.value());
        assertTrue(frame.terminalHandoffInvalidated());
    }

    @Disabled("Deferred v1.3 retained composer prompt-refresh contract")
    @Test
    void plainReaderDropsLateRunningRefreshAfterIdleGeneration() {
        StringWriter transcript = new StringWriter();
        PlainShellInputReader reader = new PlainShellInputReader(
                new StringReader("ignored\n"), new PrintWriter(transcript));

        assertEquals("ignored", reader.readLine("paicli [running: EXECUTING]> ", 1));
        reader.runtimeStateChanged("paicli> ", 2);
        reader.runtimeStateChanged("paicli [running: REPAIRING]> ", 1);

        String text = transcript.toString();
        assertEquals(1, occurrences(text, "paicli [running: EXECUTING]> "));
        assertEquals(0, occurrences(text, "paicli [running: REPAIRING]> "));
        assertEquals(1, occurrences(text, "paicli> "));
        assertTrue(text.endsWith("paicli> "));
    }

    @Disabled("Deferred v1.3 retained composer prompt identity contract")
    @Test
    void promptRefreshRequiresMatchingSessionTaskStateAndStrictlyNewGeneration() {
        StringWriter transcript = new StringWriter();
        PlainShellInputReader reader = new PlainShellInputReader(
                new StringReader("ignored\n"), new PrintWriter(transcript));
        var running = new ShellInputReader.PromptIdentity(
                "session-1", "task-1", 2, "TASK_RUNNING");
        assertEquals("ignored", reader.readLine(
                "paicli [running: EXECUTING]> ", running));

        var approval = new ShellInputReader.PromptIdentity(
                "session-1", "task-1", 3, "APPROVAL_PENDING");
        reader.runtimeStateChanged("approval [edit A.java]>\nApply? ", approval);
        var idle = new ShellInputReader.PromptIdentity(
                "session-1", null, 4, "IDLE");
        reader.runtimeStateChanged("paicli> ", idle);

        reader.runtimeStateChanged("approval [edit A.java]> stale ",
                new ShellInputReader.PromptIdentity(
                        "session-1", "task-1", 4, "APPROVAL_PENDING"));
        reader.runtimeStateChanged("paicli [running: REPAIRING]> ",
                new ShellInputReader.PromptIdentity(
                        "other-session", "task-1", 5, "TASK_RUNNING"));

        String text = transcript.toString();
        assertEquals(1, occurrences(text, "approval [edit A.java]>"));
        assertEquals(0, occurrences(text, "stale"));
        assertEquals(0, occurrences(text, "REPAIRING"));
        assertEquals(1, occurrences(text, "paicli> "));
        assertTrue(text.endsWith("paicli> "));
    }

    private InteractiveSession session() {
        InteractiveSession session = new InteractiveSession(
                "session-1", workspace, "mock", "model", "on-request",
                InteractiveSession.TerminalMode.PLAIN, Instant.EPOCH);
        session.start();
        return session;
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        for (int index = value.indexOf(needle); index >= 0;
                index = value.indexOf(needle, index + needle.length())) {
            count++;
        }
        return count;
    }

    private static final class GenerationReader implements ShellInputReader {
        private Runnable duringRead = () -> { };
        private int readCount;
        private long readGeneration;
        private final List<String> refreshPrompts = new ArrayList<>();
        private final List<Long> refreshGenerations = new ArrayList<>();
        private final List<ShellInputReader.PromptIdentity> refreshIdentities =
                new ArrayList<>();

        @Override
        public String readLine(String prompt) {
            throw new AssertionError("generation-aware read must be used");
        }

        @Override
        public String readLine(String prompt, long generation) {
            readCount++;
            readGeneration = generation;
            duringRead.run();
            return "/status";
        }

        @Override
        public void runtimeStateChanged(String prompt, long generation) {
            refreshPrompts.add(prompt);
            refreshGenerations.add(generation);
        }

        @Override
        public void runtimeStateChanged(String prompt,
                ShellInputReader.PromptIdentity identity) {
            refreshPrompts.add(prompt);
            refreshGenerations.add(identity.generation());
            refreshIdentities.add(identity);
        }

        @Override public void close() { }
    }
}
