package com.paicli.interactive.render;

import com.paicli.event.AgentEvent;

import java.io.PrintWriter;
import java.util.Objects;

public class PlainEventRenderer implements AgentEventRenderer {
    private final PrintWriter output;
    public PlainEventRenderer(PrintWriter output){this.output=Objects.requireNonNull(output,"output");}
    @Override public synchronized void accept(AgentEvent event){output.println(EventLineFormatter.format(event));output.flush();}
}
