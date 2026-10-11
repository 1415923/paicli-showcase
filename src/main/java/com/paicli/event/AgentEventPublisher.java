package com.paicli.event;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

public final class AgentEventPublisher {
    private final String sessionId;
    private final Clock clock;
    private final List<AgentEventSink> sinks = new CopyOnWriteArrayList<>();
    private long sequence;
    private long sinkFailureCount;

    public AgentEventPublisher(String sessionId, Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        new AgentEventMetadata(AgentEventType.SESSION_STARTED, sessionId, null, null, 1, this.clock.instant());
        this.sessionId = sessionId;
    }

    public void addSink(AgentEventSink sink) { sinks.add(Objects.requireNonNull(sink, "sink")); }
    public void removeSink(AgentEventSink sink) { sinks.remove(sink); }

    public synchronized AgentEvent publish(AgentEventType type, String taskId, String runId,
            Function<AgentEventMetadata, ? extends AgentEvent> factory) {
        AgentEventMetadata metadata = new AgentEventMetadata(type, sessionId, taskId, runId,
                ++sequence, clock.instant());
        AgentEvent event = Objects.requireNonNull(factory.apply(metadata), "event");
        if (!metadata.equals(event.metadata())) throw new IllegalArgumentException("factory changed event metadata");
        for (AgentEventSink sink : sinks) {
            try { sink.accept(event); } catch (RuntimeException ignored) { sinkFailureCount++; }
        }
        return event;
    }

    /** Publishes only when no event has appeared since the caller's quiet-window snapshot. */
    public synchronized AgentEvent publishIfLastSequence(long expectedSequence,
            AgentEventType type,String taskId,String runId,
            Function<AgentEventMetadata,? extends AgentEvent> factory){
        if(sequence!=expectedSequence)return null;
        return publish(type,taskId,runId,factory);
    }

    public synchronized long lastSequence() { return sequence; }
    public synchronized long sinkFailureCount() { return sinkFailureCount; }
}
