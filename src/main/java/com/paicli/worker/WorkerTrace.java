package com.paicli.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.runtime.PersistenceSanitizer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** Bounded JSONL lifecycle metadata stored outside the assigned workspace. */
final class WorkerTrace {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_FIELD_BYTES = 4_096;
    private final Path file;
    private final PersistenceSanitizer sanitizer = new PersistenceSanitizer();

    WorkerTrace(Path traceDirectory) throws IOException {
        Files.createDirectories(traceDirectory);
        this.file = traceDirectory.resolve("worker.jsonl");
    }

    synchronized void record(String type, String workerRunId, String detail) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("type", type);
        node.put("worker_run_id", workerRunId);
        node.put("detail", sanitizer.sanitizeAndTruncate(
                detail == null ? "" : detail, MAX_FIELD_BYTES));
        node.put("timestamp", Instant.now().toString());
        try {
            Files.writeString(file, MAPPER.writeValueAsString(node) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("Worker trace write failed", e);
        }
    }

    Path file() {
        return file;
    }
}
