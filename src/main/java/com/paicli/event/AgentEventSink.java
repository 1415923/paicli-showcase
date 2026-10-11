package com.paicli.event;

@FunctionalInterface
public interface AgentEventSink {
    void accept(AgentEvent event);
}
