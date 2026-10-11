package com.paicli.interactive.shell;

import com.paicli.interactive.render.TerminalOutputPort;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

public final class PlainShellInputReader implements ShellInputReader {
    private final BufferedReader input;
    private TerminalOutputPort output;
    private final AtomicBoolean reading = new AtomicBoolean(false);
    private final AtomicLong displayedGeneration = new AtomicLong(-1);
    private volatile PromptIdentity displayedIdentity;

    public PlainShellInputReader(Reader input, PrintWriter output) {
        this.input = input instanceof BufferedReader buffered ? buffered : new BufferedReader(input);
        this.output = TerminalOutputPort.plain(output, () -> false);
    }

    PlainShellInputReader(Reader input) {
        this.input = input instanceof BufferedReader buffered ? buffered : new BufferedReader(input);
    }

    void bindOutput(TerminalOutputPort output) {
        if (this.output != null) throw new IllegalStateException("plain output already bound");
        this.output = java.util.Objects.requireNonNull(output, "output");
    }

    @Override
    public String readLine(String prompt) {
        return readLine(prompt, displayedGeneration.get() + 1);
    }

    @Override
    public String readLine(String prompt, long generation) {
        return readLine(prompt, new PromptIdentity(
                "legacy-session", null, generation, "IDLE"));
    }

    @Override
    public String readLine(String prompt, PromptIdentity identity) {
        reading.set(true);
        try {
        long generation = identity.generation();
        long previous = displayedGeneration.getAndAccumulate(generation, Math::max);
        if (generation > previous) {
            displayedIdentity = identity;
            output().writePrompt(prompt);
        }
        try {
            return input.readLine();
        } catch (IOException error) {
            return null;
        }
        } finally {
            reading.set(false);
        }
    }

    @Override public char[] readSecret(String prompt,PromptIdentity identity){return null;}

    @Override
    public ForegroundReadResult readForegroundChoice(String prompt,
            PromptIdentity identity, BooleanSupplier ownershipValid) {
        java.util.Objects.requireNonNull(ownershipValid, "ownershipValid");
        output().writePrompt(prompt);
        reading.set(true);
        try {
            while (ownershipValid.getAsBoolean()) {
                try {
                    if (input.ready()) {
                        String value = input.readLine();
                        if (!ownershipValid.getAsBoolean()) {
                            return ForegroundReadResult.invalidated();
                        }
                        return value == null ? ForegroundReadResult.eof()
                                : ForegroundReadResult.value(value);
                    }
                    Thread.sleep(50L);
                } catch (IOException error) {
                    return ForegroundReadResult.eof();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return ForegroundReadResult.invalidated();
                }
            }
            return ForegroundReadResult.invalidated();
        } finally {
            reading.set(false);
        }
    }

    @Override public boolean isReading() { return reading.get(); }

    @Override
    public void runtimeStateChanged(String prompt) {
        runtimeStateChanged(prompt, displayedGeneration.get() + 1);
    }

    @Override
    public void runtimeStateChanged(String prompt, long generation) {
        runtimeStateChanged(prompt, new PromptIdentity(
                "legacy-session", null, generation, "IDLE"));
    }

    @Override
    public void runtimeStateChanged(String prompt, PromptIdentity identity) {
        PromptIdentity displayed = displayedIdentity;
        if (displayed != null && !validNewer(identity, displayed)) return;
        long generation = identity.generation();
        while (true) {
            long current = displayedGeneration.get();
            if (generation <= current) return;
            if (displayedGeneration.compareAndSet(current, generation)) break;
        }
        displayedIdentity = identity;
        output().writePrompt(System.lineSeparator()
                + (prompt == null ? "paicli> " : prompt));
    }

    private TerminalOutputPort output() {
        TerminalOutputPort current = output;
        if (current == null) throw new IllegalStateException("plain output is not bound");
        return current;
    }

    private static boolean validNewer(PromptIdentity candidate,
            PromptIdentity existing) {
        if (!candidate.sessionId().equals(existing.sessionId())
                || candidate.generation() <= existing.generation()) return false;
        if (existing.taskId() != null && candidate.taskId() != null
                && !existing.taskId().equals(candidate.taskId())) return false;
        if ("APPROVAL_PENDING".equals(candidate.inputState())) {
            return candidate.taskId() != null
                    && candidate.taskId().equals(existing.taskId());
        }
        return !"IDLE".equals(candidate.inputState()) || candidate.taskId() == null;
    }
}
