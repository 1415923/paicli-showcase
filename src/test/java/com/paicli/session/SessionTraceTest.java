package com.paicli.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SessionTraceTest {

    @TempDir
    Path tempDir;

    @Test
    void writeStartCreatesTraceFile() throws IOException {
        var trace = new SessionTrace(tempDir.resolve("sessions"), "task-001");
        trace.writeStart("list all files");

        Path file = tempDir.resolve("sessions").resolve("task-001.jsonl");
        assertTrue(Files.exists(file));

        String content = Files.readString(file);
        assertTrue(content.contains("\"type\":\"start\""));
        assertTrue(content.contains("\"goal\":\"list all files\""));
    }

    @Test
    void multipleEventsAppendToSameFile() throws IOException {
        var trace = new SessionTrace(tempDir.resolve("sessions"), "task-002");
        trace.writeStart("read file");
        trace.writeAction("read_file", "{\"path\":\"a.txt\"}", 1);
        trace.writeObservation("read_file", "content of a.txt", 1);
        trace.writeFinal("The file contains content", 2);

        String content = Files.readString(trace.getTraceFile());
        String[] lines = content.trim().split("\n");
        assertEquals(4, lines.length);
        assertTrue(lines[0].contains("\"type\":\"start\""));
        assertTrue(lines[1].contains("\"type\":\"action\""));
        assertTrue(lines[2].contains("\"type\":\"observation\""));
        assertTrue(lines[3].contains("\"type\":\"final\""));
    }

    @Test
    void createsSessionsDirectoryIfMissing() throws IOException {
        Path nested = tempDir.resolve("deeply").resolve("nested").resolve("sessions");
        var trace = new SessionTrace(nested, "task-003");
        trace.writeStart("test");
        assertTrue(Files.exists(nested.resolve("task-003.jsonl")));
    }
}
