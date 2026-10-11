package com.paicli.interactive.render;

import com.paicli.event.AgentEvent;
import org.jline.reader.LineReader;

/** Bounded runtime event renderer backed by the serialized terminal output port. */
public final class InteractiveRuntimeEventRenderer implements AgentEventRenderer {
    private final TerminalOutputPort output;
    private final RuntimeEventCoalescer coalescer;

    public InteractiveRuntimeEventRenderer(LineReader reader) {
        this(TerminalOutputPort.jline(reader, () -> false),
                new RuntimeDisplayPreferences());
    }

    public InteractiveRuntimeEventRenderer(LineReader reader,
            RuntimeDisplayPreferences preferences) {
        this(TerminalOutputPort.jline(reader, () -> false), preferences);
    }

    public InteractiveRuntimeEventRenderer(TerminalOutputPort output,
            RuntimeDisplayPreferences preferences) {
        this.output = java.util.Objects.requireNonNull(output, "output");
        this.coalescer = new RuntimeEventCoalescer(preferences);
    }

    @Override
    public synchronized void accept(AgentEvent event) {
        RuntimeEventCoalescer.Emission emission = coalescer.accept(event);
        if (emission.summary() != null) output.writeLine(emission.summary());
        if (emission.line() != null) {
            output.writeLine(emission.line(), priority(event));
        }
        if (terminal(event)) output.endTaskBlock();
    }

    public synchronized void flush() {
        String line = coalescer.flush();
        if (line != null) output.writeLine(line);
        output.flush();
    }

    static TerminalOutputPort.Priority priority(AgentEvent event) {
        return event instanceof com.paicli.event.AgentEvents.ApprovalRequested
                || event instanceof com.paicli.event.AgentEvents.TaskCompleted
                || event instanceof com.paicli.event.AgentEvents.TaskFailed
                || event instanceof com.paicli.event.AgentEvents.TaskCancelled
                || event instanceof com.paicli.event.AgentEvents.TaskInconclusive
                || event instanceof com.paicli.event.AgentEvents.CancellationRequested
                || event instanceof com.paicli.event.AgentEvents.CancellationForced
                ? TerminalOutputPort.Priority.CRITICAL
                : TerminalOutputPort.Priority.NORMAL;
    }

    static boolean terminal(AgentEvent event) {
        return event instanceof com.paicli.event.AgentEvents.TaskCompleted
                || event instanceof com.paicli.event.AgentEvents.TaskFailed
                || event instanceof com.paicli.event.AgentEvents.TaskCancelled
                || event instanceof com.paicli.event.AgentEvents.TaskInconclusive;
    }
}
