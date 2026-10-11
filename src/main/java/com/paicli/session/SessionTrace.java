package com.paicli.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;

/**
 * Writes a session trace in JSONL format.
 * Each line is a JSON object describing one event in the agent loop.
 */
public class SessionTrace {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path traceFile;

    /**
     * @param sessionsDir directory to write traces (e.g. .paicli/sessions)
     * @param taskId      unique task identifier
     */
    public SessionTrace(Path sessionsDir, String taskId) throws IOException {
        Files.createDirectories(sessionsDir);
        this.traceFile = sessionsDir.resolve(taskId + ".jsonl");
    }

    /** Record the start of a session. */
    public void writeStart(String goal) throws IOException {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "start");
        node.put("goal", goal);
        node.put("timestamp", Instant.now().toString());
        appendLine(node);
    }

    /** Record a tool call (action). */
    public void writeAction(String tool, String params, int iteration) throws IOException {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "action");
        node.put("tool", tool);
        node.put("params", params);
        node.put("iteration", iteration);
        node.put("timestamp", Instant.now().toString());
        appendLine(node);
    }

    /** Record a tool result (observation). */
    public void writeObservation(String tool, String output, int iteration) throws IOException {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "observation");
        node.put("tool", tool);
        node.put("output", output);
        node.put("iteration", iteration);
        node.put("timestamp", Instant.now().toString());
        appendLine(node);
    }

    /** Record the final answer. */
    public void writeFinal(String answer, int iterations) throws IOException {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", "final");
        node.put("answer", answer);
        node.put("iterations", iterations);
        node.put("timestamp", Instant.now().toString());
        appendLine(node);
    }

    /** Get the path to the trace file. */
    public Path getTraceFile() {
        return traceFile;
    }

    private void appendLine(ObjectNode node) throws IOException {
        String json = MAPPER.writeValueAsString(node);
        Files.writeString(traceFile, json + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }
}
