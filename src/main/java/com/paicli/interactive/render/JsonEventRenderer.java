package com.paicli.interactive.render;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.paicli.event.AgentEvent;

import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class JsonEventRenderer implements AgentEventRenderer {
    private final ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule());
    private final PrintWriter output;
    public JsonEventRenderer(PrintWriter output){this.output=Objects.requireNonNull(output,"output");}
    @Override public synchronized void accept(AgentEvent event){
        try{
            Map<String,Object> line=new LinkedHashMap<>();
            line.put("eventType",event.metadata().eventType().name());
            line.put("sessionId",event.metadata().sessionId());
            line.put("taskId",event.metadata().taskId());
            line.put("runId",event.metadata().runId());
            line.put("sequence",event.metadata().sequence());
            line.put("timestamp",event.metadata().timestamp());
            line.put("event",event);
            String json=mapper.writeValueAsString(line);
            if(json.length()>16384)throw new IllegalArgumentException("JSON event exceeds output bound");
            output.println(json);output.flush();
        }catch(Exception error){throw new IllegalStateException("event rendering failed");}
    }
}
