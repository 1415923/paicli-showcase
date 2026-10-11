package com.paicli.interactive.terminal;

import com.paicli.interactive.completion.InteractiveCompleter;
import com.paicli.interactive.completion.SlashCommandCompleter;
import com.paicli.interactive.completion.WorkspaceCompleter;
import com.paicli.interactive.completion.WorkspacePathGuard;
import com.paicli.interactive.command.SlashCommandRegistry;
import com.paicli.interactive.history.InteractiveHistoryStore;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.shell.JLineShellInputReader;
import com.paicli.interactive.shell.PlainShellInputReader;
import org.jline.reader.LineReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import com.paicli.interactive.render.TerminalOutputPort;
import com.paicli.interactive.shell.InputInterruptedException;
import com.paicli.interactive.shell.ShellInputReader;

import static org.junit.jupiter.api.Assertions.*;

class TerminalFallbackTest {
    @TempDir Path temp;

    @Test void createsDumbTerminalAndConfiguredReader() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var context = new InteractiveTerminalFactory().create(
                new ByteArrayInputStream(new byte[0]), output, true)) {
            assertEquals(InteractiveSession.TerminalMode.PLAIN, context.mode());
            var completer = new InteractiveCompleter(
                    new SlashCommandCompleter(new SlashCommandRegistry()),
                    new WorkspaceCompleter(new WorkspacePathGuard(temp)));
            LineReader reader = new InteractiveLineReaderFactory().create(
                    context.terminal(), completer, new InteractiveHistoryStore(temp.resolve("history")));
            assertTrue(reader.isSet(LineReader.Option.DISABLE_EVENT_EXPANSION));
            assertTrue(reader.isSet(LineReader.Option.HISTORY_IGNORE_DUPS));
        }
    }

    @Test void systemTerminalFailureFallsBackToPlain() throws Exception {
        InteractiveTerminalFactory factory = new InteractiveTerminalFactory(
                () -> { throw new IOException("terminal unavailable"); });
        try (var context = factory.createSystemOrPlain(
                new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())) {
            assertEquals(InteractiveSession.TerminalMode.PLAIN, context.mode());
        }
    }

    @Test void headlessRuntimeUsesProvidedStreamsWithoutOpeningSystemTerminal()
            throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.console() == null);
        AtomicBoolean systemOpened = new AtomicBoolean();
        InteractiveTerminalFactory factory = new InteractiveTerminalFactory(() -> {
            systemOpened.set(true);
            throw new AssertionError("headless runtime must not open the system terminal");
        });

        try (var context = factory.createSystemOrPlain(
                new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream())) {
            assertEquals(InteractiveSession.TerminalMode.PLAIN, context.mode());
            assertFalse(systemOpened.get());
        }
    }

    @Test void plainReaderUsesNoAnsiAndReturnsEof() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintWriter output = new PrintWriter(new OutputStreamWriter(bytes, StandardCharsets.UTF_8), true);
        PlainShellInputReader reader = new PlainShellInputReader(new StringReader("/status\n"), output);
        assertEquals("/status", reader.readLine("paicli> "));
        assertNull(reader.readLine("paicli> "));
        assertFalse(bytes.toString(StandardCharsets.UTF_8).contains("\u001b"));
    }

    @Test void jlineSecretInputIsNotEchoedOrAddedToHistory() throws Exception {
        String secret = "masked-history-sentinel";
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var terminal = org.jline.terminal.TerminalBuilder.builder().system(false)
                .streams(new ByteArrayInputStream((secret + "\n").getBytes(StandardCharsets.UTF_8)),
                        output).type("xterm-256color").build()) {
            Path history = temp.resolve("masked-history");
            LineReader lineReader = new InteractiveLineReaderFactory().create(
                    terminal, (reader, line, candidates) -> { },
                    new InteractiveHistoryStore(history));
            JLineShellInputReader input = new JLineShellInputReader(lineReader);

            char[] value = input.readSecret("Provider API key: ",
                    new ShellInputReader.PromptIdentity("session", null, 1, "IDLE"));

            assertArrayEquals(secret.toCharArray(), value);
            java.util.Arrays.fill(value, '\0');
            org.jline.reader.MaskingCallback noEcho =
                    new org.jline.reader.impl.SimpleMaskingCallback(Character.valueOf('\0'));
            assertEquals("", noEcho.display(secret));
            assertNull(noEcho.history(secret));
            assertFalse(lineReader.getHistory().iterator().hasNext());
            if (java.nio.file.Files.exists(history)) {
                assertFalse(java.nio.file.Files.readString(history).contains(secret));
            }
        }
    }

    @Test void plainReaderReturnsLiteralEscapeAsInputData() throws Exception {
        String literal = "before\u001bafter";
        PlainShellInputReader reader = new PlainShellInputReader(
                new StringReader(literal + "\n"), new PrintWriter(new StringWriter()));
        assertEquals(literal, reader.readLine("paicli> "));
    }

    @Test void jlineEscapeClearsBufferWithoutSubmittingOrEndingOwnedRead() throws Exception {
        PipedInputStream input = new PipedInputStream();
        try (PipedOutputStream producer = new PipedOutputStream(input);
             ByteArrayOutputStream output = new ByteArrayOutputStream();
             var context = new InteractiveTerminalFactory().create(input, output, true)) {
            LineReader lineReader = new InteractiveLineReaderFactory().create(
                    context.terminal(), new SlashCommandCompleter(new SlashCommandRegistry()),
                    new InteractiveHistoryStore(temp.resolve("escape-history")));
            JLineShellInputReader reader = new JLineShellInputReader(lineReader);
            var escapeWidget = lineReader.getWidgets().get("paicli-owned-escape");
            assertNotNull(escapeWidget);
            CountDownLatch escapeHandled = new CountDownLatch(1);
            lineReader.getWidgets().put("paicli-owned-escape", () -> {
                try {
                    return escapeWidget.apply();
                } finally {
                    escapeHandled.countDown();
                }
            });
            CompletableFuture<String> result = new CompletableFuture<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread owner = Thread.ofPlatform().start(() -> {
                try {
                    result.complete(reader.readLine("paicli> "));
                } catch (Throwable error) {
                    failure.set(error);
                    result.completeExceptionally(error);
                }
            });
            try {
                awaitOutput(output, "paicli> ");
                producer.write("discard-me".getBytes(StandardCharsets.UTF_8));
                producer.flush();
                producer.write("\u001b".getBytes(StandardCharsets.UTF_8));
                producer.flush();

                assertTrue(escapeHandled.await(2, TimeUnit.SECONDS),
                        "Esc widget was not handled");
                assertTrue(owner.isAlive(), "Esc must not submit or end the owned read");
                assertFalse(result.isDone(), "Esc must not complete the owned read");
                assertNull(failure.get());
                assertEquals("", lineReader.getBuffer().toString());

                producer.write("/exit\n".getBytes(StandardCharsets.UTF_8));
                producer.flush();
                assertEquals("/exit", result.get(2, TimeUnit.SECONDS));
            } finally {
                if (owner.isAlive()) reader.close();
                owner.join(2000);
            }
            assertFalse(owner.isAlive());
            assertNull(failure.get());
            assertTrue(result.isDone());
        }
    }

    @Test void foregroundEscIsAcooperativeCancelActionOwnedByTheSingleReader() throws Exception {
        PipedInputStream input = new PipedInputStream();
        try (PipedOutputStream producer = new PipedOutputStream(input);
             ByteArrayOutputStream output = new ByteArrayOutputStream();
             var context = new InteractiveTerminalFactory().create(input, output, true)) {
            LineReader lineReader = new InteractiveLineReaderFactory().create(
                    context.terminal(), new SlashCommandCompleter(new SlashCommandRegistry()),
                    new InteractiveHistoryStore(temp.resolve("active-escape-history")));
            JLineShellInputReader reader = new JLineShellInputReader(lineReader);
            AtomicBoolean valid = new AtomicBoolean(true);
            AtomicReference<ShellInputReader.ForegroundReadResult> result =
                    new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            try (TerminalOutputPort terminalOutput = TerminalOutputPort.jline(
                    lineReader, reader::isReading)) {
                reader.bindOutput(terminalOutput);
                Thread owner = Thread.ofPlatform().start(() -> {
                    try {
                        result.set(reader.readForegroundChoice(
                                "approval [command]> choose: ",
                                new ShellInputReader.PromptIdentity(
                                        "session", "task", 1, "APPROVAL_PENDING"),
                                valid::get));
                    } catch (Throwable error) {
                        failure.set(error);
                    }
                });
                awaitOutput(output, "approval [command]");
                producer.write("\u001b".getBytes(StandardCharsets.UTF_8));
                producer.flush();
                owner.join(2000);
                assertFalse(owner.isAlive(),
                        "Esc must complete the sole foreground read as an application action");
                assertNull(failure.get());
                assertEquals(ShellInputReader.ForegroundReadStatus.CANCEL_TASK,
                        result.get().status());
            } finally {
                reader.close();
            }
        }
    }

    @Test void foregroundApprovalWaitsForEnterAndUsesTheJLineBuffer() throws Exception {
        PipedInputStream input = new PipedInputStream();
        try (PipedOutputStream producer = new PipedOutputStream(input);
             ByteArrayOutputStream output = new ByteArrayOutputStream();
             var context = new InteractiveTerminalFactory().create(input, output, true)) {
            LineReader lineReader = new InteractiveLineReaderFactory().create(
                    context.terminal(), new SlashCommandCompleter(new SlashCommandRegistry()),
                    new InteractiveHistoryStore(temp.resolve("approval-line-history")));
            JLineShellInputReader reader = new JLineShellInputReader(lineReader);
            AtomicReference<ShellInputReader.ForegroundReadResult> result = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            try (TerminalOutputPort terminalOutput = TerminalOutputPort.jline(
                    lineReader, reader::isReading)) {
                reader.bindOutput(terminalOutput);
                Thread owner = Thread.ofPlatform().start(() -> {
                    try { result.set(reader.readForegroundChoice(
                            "approval [structured plan]> ",
                            new ShellInputReader.PromptIdentity(
                                    "session", "task", 1, "APPROVAL_PENDING"),
                            () -> true)); }
                    catch(Throwable error){failure.set(error);}
                });
                awaitOutput(output, "approval [structured plan]");
                producer.write('y');producer.flush();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(lineReader.getBuffer().length()==0&&System.nanoTime()<deadline)
                    Thread.onSpinWait();
                assertEquals("y",lineReader.getBuffer().toString());
                assertTrue(owner.isAlive(),"a choice without Enter must not activate authority");
                assertNull(result.get());
                producer.write('\n');producer.flush();
                owner.join(2000);
                assertFalse(owner.isAlive());
                assertNull(failure.get());
                assertEquals("y",result.get().value());
            } finally { reader.close(); }
        }
    }

    @Test void foregroundApprovalRetainsJLineEditingBeforeEnter() throws Exception {
        PipedInputStream input=new PipedInputStream();
        try(PipedOutputStream producer=new PipedOutputStream(input);
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            var context=new InteractiveTerminalFactory().create(input,output,true)){
            LineReader lineReader=new InteractiveLineReaderFactory().create(context.terminal(),
                    new SlashCommandCompleter(new SlashCommandRegistry()),
                    new InteractiveHistoryStore(temp.resolve("approval-edit-history")));
            JLineShellInputReader reader=new JLineShellInputReader(lineReader);
            AtomicReference<ShellInputReader.ForegroundReadResult> result=new AtomicReference<>();
            AtomicReference<Throwable> failure=new AtomicReference<>();
            try(TerminalOutputPort terminalOutput=TerminalOutputPort.jline(
                    lineReader,reader::isReading)){
                reader.bindOutput(terminalOutput);
                Thread owner=Thread.ofPlatform().start(()->{
                    try{result.set(reader.readForegroundChoice(
                            "approval [structured plan]> ",new ShellInputReader.PromptIdentity(
                                    "session","task",1,"APPROVAL_PENDING"),()->true));}
                    catch(Throwable error){failure.set(error);}
                });
                awaitOutput(output,"approval [structured plan]");
                producer.write("yx".getBytes(StandardCharsets.UTF_8));producer.flush();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
                while(!"yx".equals(lineReader.getBuffer().toString())
                        &&System.nanoTime()<deadline)Thread.onSpinWait();
                assertEquals("yx",lineReader.getBuffer().toString());
                assertTrue(lineReader.getBuffer().backspace(),
                        "the active JLine buffer must retain editable content");
                assertEquals("y",lineReader.getBuffer().toString());
                assertTrue(owner.isAlive());
                producer.write('\n');producer.flush();owner.join(2000);
                assertNull(failure.get());
                assertEquals("y",result.get().value());
            }finally{reader.close();}
        }
    }

    @Test void closedOutputPortRejectsLateProducerWrites() {
        StringWriter transcript = new StringWriter();
        TerminalOutputPort output = TerminalOutputPort.plain(
                new PrintWriter(transcript), () -> false);
        output.writeLine("before-close");
        output.close();
        String closedTranscript = transcript.toString();
        assertThrows(IllegalStateException.class,
                () -> output.writeLine("after-close"));
        assertEquals(closedTranscript, transcript.toString());
    }

    @Test void idleJlineReadIgnoresLegacySyntheticPromptRefresh() throws Exception {
        PipedInputStream input = new PipedInputStream();
        try (PipedOutputStream producer = new PipedOutputStream(input);
             ByteArrayOutputStream output = new ByteArrayOutputStream();
             var context = new InteractiveTerminalFactory().create(input, output, true)) {
            var completer = new InteractiveCompleter(
                    new SlashCommandCompleter(new SlashCommandRegistry()),
                    new WorkspaceCompleter(new WorkspacePathGuard(temp)));
            LineReader lineReader = new InteractiveLineReaderFactory().create(
                    context.terminal(), completer,
                    new InteractiveHistoryStore(temp.resolve("refresh-history")));
            JLineShellInputReader reader = new JLineShellInputReader(lineReader);
            AtomicReference<String> result = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread owner = Thread.ofPlatform().start(() -> {
                try {
                    result.set(reader.readLine("paicli> "));
                } catch (Throwable error) {
                    failure.set(error);
                }
            });
            awaitOutput(output, "paicli> ");

            reader.runtimeStateChanged("legacy-refresh-must-not-render> ");
            assertFalse(output.toString(StandardCharsets.UTF_8)
                    .contains("legacy-refresh-must-not-render"));
            producer.write("/exit\n".getBytes(StandardCharsets.UTF_8));
            producer.flush();
            owner.join(2000);

            assertFalse(owner.isAlive());
            assertNull(failure.get());
            assertEquals("/exit", result.get());
        }
    }

    private static void awaitOutput(ByteArrayOutputStream output, String expected)
            throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (output.toString(StandardCharsets.UTF_8).contains(expected)) return;
            Thread.sleep(10);
        }
        fail("terminal output did not contain: " + expected);
    }

}
