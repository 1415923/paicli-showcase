package com.paicli.interactive.render;

import com.paicli.event.AgentEvent;
import org.jline.reader.LineReader;

import java.util.Objects;

public final class InteractiveEventRenderer implements AgentEventRenderer {
    private final TerminalOutputPort output;
    public InteractiveEventRenderer(LineReader reader) {
        this(TerminalOutputPort.jline(Objects.requireNonNull(reader, "reader"), () -> false));
    }
    public InteractiveEventRenderer(TerminalOutputPort output) {
        this.output = Objects.requireNonNull(output, "output");
    }
    @Override public synchronized void accept(AgentEvent event) {
        output.writeLine(EventLineFormatter.format(event),
                InteractiveRuntimeEventRenderer.priority(event));
        if (InteractiveRuntimeEventRenderer.terminal(event)) output.endTaskBlock();
    }
}
