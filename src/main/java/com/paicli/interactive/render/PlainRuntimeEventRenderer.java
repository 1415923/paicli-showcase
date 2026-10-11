package com.paicli.interactive.render;

import com.paicli.event.AgentEvent;

import java.io.PrintWriter;
import java.util.Objects;

/** ANSI-free line renderer for redirected and dumb terminals. */
public final class PlainRuntimeEventRenderer implements AgentEventRenderer {
    private final TerminalOutputPort output;
    private final RuntimeEventCoalescer coalescer;

    public PlainRuntimeEventRenderer(PrintWriter output) {
        this(TerminalOutputPort.plain(output, () -> false),
                new RuntimeDisplayPreferences());
    }

    public PlainRuntimeEventRenderer(PrintWriter output,
            RuntimeDisplayPreferences preferences) {
        this(TerminalOutputPort.plain(output, () -> false), preferences);
    }

    public PlainRuntimeEventRenderer(TerminalOutputPort output,
            RuntimeDisplayPreferences preferences) {
        this.output = Objects.requireNonNull(output, "output");
        this.coalescer = new RuntimeEventCoalescer(preferences);
    }

    @Override
    public synchronized void accept(AgentEvent event) {
        RuntimeEventCoalescer.Emission emission = coalescer.accept(event);
        if (emission.summary() != null) output.writeLine(emission.summary());
        if (emission.line() != null) {
            output.writeLine(emission.line(),
                    InteractiveRuntimeEventRenderer.priority(event));
        }
        if (InteractiveRuntimeEventRenderer.terminal(event)) output.endTaskBlock();
    }

    public synchronized void flush() {
        String line = coalescer.flush();
        if (line != null) output.writeLine(line);
        output.flush();
    }
}
