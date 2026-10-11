package com.paicli.interactive.render;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class TerminalLifecycleTest {
    @Test
    void concurrentProducersShareOneSerializedOwnerAndCannotWriteAfterClose() {
        StringWriter transcript = new StringWriter();
        TerminalOutputPort output = TerminalOutputPort.plain(
                new PrintWriter(transcript), () -> false);
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<Void> first = CompletableFuture.runAsync(
                () -> publish(output, start, "worker"));
        CompletableFuture<Void> second = CompletableFuture.runAsync(
                () -> publish(output, start, "runtime"));

        start.countDown();
        assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> CompletableFuture.allOf(first, second).get(2, TimeUnit.SECONDS));
        output.close();

        String[] lines = transcript.toString().lines().toArray(String[]::new);
        assertEquals(2, lines.length);
        assertTrue(java.util.Set.of("worker", "runtime").contains(lines[0]));
        assertTrue(java.util.Set.of("worker", "runtime").contains(lines[1]));
        assertNotEquals(lines[0], lines[1]);
        assertThrows(IllegalStateException.class,
                () -> output.writeLine("late-producer"));
        assertFalse(transcript.toString().contains("late-producer"));
    }

    private static void publish(TerminalOutputPort output, CountDownLatch start,
            String value) {
        try {
            if (!start.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("producer start was not released");
            }
            output.writeLine(value);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("producer interrupted", error);
        }
    }
}
