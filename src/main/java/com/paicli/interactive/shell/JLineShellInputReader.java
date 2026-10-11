package com.paicli.interactive.shell;

import com.paicli.interactive.render.TerminalOutputPort;
import com.paicli.interactive.render.TerminalSemanticStyle;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.utils.NonBlockingReader;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public final class JLineShellInputReader implements ShellInputReader {
    private final LineReader reader;
    private final AtomicBoolean reading = new AtomicBoolean(false);
    private final AtomicBoolean foregroundLineRead = new AtomicBoolean(false);
    private final AtomicBoolean foregroundCancel = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private TerminalOutputPort output;

    public JLineShellInputReader(LineReader reader) {
        this.reader = reader;
        reader.getWidgets().put("paicli-owned-escape", () -> {
            reader.getBuffer().clear();
            if (foregroundLineRead.get()) {
                foregroundCancel.set(true);
                reader.callWidget(LineReader.ACCEPT_LINE);
            } else {
                reader.callWidget(LineReader.REDISPLAY);
            }
            return true;
        });
        reader.getKeyMaps().get(LineReader.MAIN).bind(
                new Reference("paicli-owned-escape"), "\033");
    }

    public void bindOutput(TerminalOutputPort output) {
        if (this.output != null) throw new IllegalStateException("JLine output already bound");
        this.output = java.util.Objects.requireNonNull(output, "output");
    }

    @Override
    public String readLine(String prompt) throws InputInterruptedException {
        return readLine(prompt, new PromptIdentity(
                "legacy-session", null, 0, "IDLE"));
    }

    @Override
    public String readLine(String prompt, long generation) throws InputInterruptedException {
        return readLine(prompt, new PromptIdentity(
                "legacy-session", null, generation, "IDLE"));
    }

    @Override
    public String readLine(String prompt, PromptIdentity identity)
            throws InputInterruptedException {
        reading.set(true);
        try {
            if (closed.get()) return null;
            return reader.readLine(TerminalSemanticStyle.prompt(
                    prompt, reader.getTerminal()));
        } catch (EndOfFileException error) {
            return null;
        } finally {
            reading.set(false);
        }
    }

    @Override
    public char[] readSecret(String prompt,PromptIdentity identity)
            throws InputInterruptedException {
        reading.set(true);
        try {
            if(closed.get())return null;
            String value=reader.readLine(TerminalSemanticStyle.prompt(prompt,reader.getTerminal()),
                    Character.valueOf('\0'));
            reader.getBuffer().clear();
            return value==null?null:value.toCharArray();
        } catch(EndOfFileException error){return null;}
        finally{reading.set(false);}
    }

    @Override
    public ForegroundReadResult readForegroundChoice(String prompt,
            PromptIdentity identity, BooleanSupplier ownershipValid)
            throws InputInterruptedException {
        java.util.Objects.requireNonNull(identity, "identity");
        java.util.Objects.requireNonNull(ownershipValid, "ownershipValid");
        if (output == null) {
            throw new IllegalStateException("JLine foreground output is not bound");
        }
        reading.set(true);
        foregroundLineRead.set(true);
        foregroundCancel.set(false);
        try {
            if (closed.get()) return ForegroundReadResult.eof();
            String value = reader.readLine(TerminalSemanticStyle.prompt(
                    prompt, reader.getTerminal()));
            if (foregroundCancel.get()) return ForegroundReadResult.cancelTask();
            if (!ownershipValid.getAsBoolean()) return ForegroundReadResult.invalidated();
            return ForegroundReadResult.value(value);
        } catch (EndOfFileException error) {
            return ForegroundReadResult.eof();
        } finally {
            foregroundCancel.set(false);
            foregroundLineRead.set(false);
            reading.set(false);
        }
    }

    @Override
    public ForegroundReadResult pollForegroundAction(PromptIdentity identity,
            BooleanSupplier ownershipValid) {
        java.util.Objects.requireNonNull(identity, "identity");
        java.util.Objects.requireNonNull(ownershipValid, "ownershipValid");
        if (closed.get()) return ForegroundReadResult.eof();
        if (!ownershipValid.getAsBoolean()) return ForegroundReadResult.invalidated();
        reading.set(true);
        try {
            int next;
            try {
                next = reader.getTerminal().reader().read(50L);
            } catch (IOException error) {
                return ForegroundReadResult.eof();
            }
            if (next == NonBlockingReader.READ_EXPIRED) {
                return ForegroundReadResult.none();
            }
            if (next == NonBlockingReader.EOF) return ForegroundReadResult.eof();
            if (!ownershipValid.getAsBoolean()) {
                return ForegroundReadResult.invalidated();
            }
            return next == 0x1b ? ForegroundReadResult.cancelTask()
                    : ForegroundReadResult.none();
        } finally {
            reading.set(false);
        }
    }

    @Override public boolean isReading() { return reading.get(); }
    @Override public void close() {
        closed.compareAndSet(false, true);
    }
    @Override public boolean clearNavigationHistory() {
        try {
            reader.getHistory().purge();
            return true;
        } catch (java.io.IOException | RuntimeException error) {
            return false;
        }
    }

}
