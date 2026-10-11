package com.paicli.interactive.render;

import org.jline.reader.LineReader;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

final class SerializedTerminalOutputPort implements TerminalOutputPort {
    interface Sink {
        void append(String line);
        default void appendPrompt(String prompt) { append(prompt); }
        void flush();
    }

    static final int MAX_PENDING_EVENTS = 256;
    static final int MAX_PENDING_UTF8_BYTES = 262_144;
    private final BooleanSupplier composerActive;
    private final IntSupplier terminalWidth;
    private final Sink sink;
    private final ArrayDeque<PendingOutput> pending = new ArrayDeque<>();
    private int pendingUtf8Bytes;
    private long suppressedNormalCount;
    private boolean taskBlockOpen;
    private boolean closed;

    SerializedTerminalOutputPort(BooleanSupplier composerActive, Sink sink) {
        this(composerActive, () -> InlineTranscriptLayout.DEFAULT_WIDTH, sink);
    }

    SerializedTerminalOutputPort(BooleanSupplier composerActive,
            IntSupplier terminalWidth, Sink sink) {
        this.composerActive = Objects.requireNonNull(composerActive, "composerActive");
        this.terminalWidth = Objects.requireNonNull(terminalWidth, "terminalWidth");
        this.sink = Objects.requireNonNull(sink, "sink");
    }

    static TerminalOutputPort jline(LineReader reader, BooleanSupplier composerActive) {
        Objects.requireNonNull(reader, "reader");
        return new SerializedTerminalOutputPort(composerActive,
                reader.getTerminal()::getWidth, new Sink() {
            @Override public void append(String line) {
                reader.getTerminal().writer().println(
                        TerminalSemanticStyle.line(line, reader.getTerminal()));
            }
            @Override public void appendPrompt(String prompt) {
                reader.getTerminal().writer().print(
                        TerminalSemanticStyle.prompt(prompt, reader.getTerminal()));
            }
            @Override public void flush() { reader.getTerminal().flush(); }
        });
    }

    static TerminalOutputPort plain(PrintWriter output, BooleanSupplier composerActive) {
        Objects.requireNonNull(output, "output");
        return new SerializedTerminalOutputPort(composerActive,
                () -> InlineTranscriptLayout.DEFAULT_WIDTH, new Sink() {
            @Override public void append(String line) {
                synchronized (output) { output.println(line); }
            }
            @Override public void appendPrompt(String prompt) {
                synchronized (output) { output.print(prompt); }
            }
            @Override public void flush() {
                synchronized (output) { output.flush(); }
            }
        });
    }

    @Override
    public synchronized void writeLine(String value, Priority priority) {
        writeBlock(List.of(value == null ? "" : value), priority);
    }

    @Override
    public synchronized void writeLines(List<String> values) {
        writeLines(values, Priority.NORMAL);
    }

    @Override
    public synchronized void writeLines(List<String> values, Priority priority) {
        ensureOpen();
        if (values == null || values.isEmpty()) return;
        writeBlock(values, priority);
    }

    @Override
    public synchronized void writePrompt(String prompt) {
        ensureOpen();
        drain();
        sink.appendPrompt(prompt == null ? "" : prompt);
        sink.flush();
    }

    @Override
    public synchronized void beginTaskBlock() {
        ensureOpen();
        if (taskBlockOpen) return;
        writeBlock(List.of(InlineTranscriptLayout.divider(width())),
                Priority.CRITICAL);
        taskBlockOpen = true;
    }

    @Override
    public synchronized void endTaskBlock() {
        ensureOpen();
        if (!taskBlockOpen) return;
        taskBlockOpen = false;
        writeBlock(List.of(InlineTranscriptLayout.divider(width())),
                Priority.CRITICAL);
    }

    @Override
    public synchronized void flush() {
        ensureOpen();
        if (!composerActive.getAsBoolean()) drain();
        sink.flush();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        if (composerActive.getAsBoolean()) {
            pending.clear();
            pendingUtf8Bytes = 0;
            suppressedNormalCount = 0;
        } else {
            drain();
        }
        sink.flush();
        closed = true;
    }

    private void writeBlock(List<String> values, Priority priority) {
        ensureOpen();
        Priority actualPriority = Objects.requireNonNull(priority, "priority");
        List<String> lines = sanitize(values);
        if (taskBlockOpen) {
            lines = InlineTranscriptLayout.taskLines(lines, width());
        }
        PendingOutput output = new PendingOutput(lines, utf8Bytes(lines),
                actualPriority);
        if (composerActive.getAsBoolean()) {
            enqueue(output);
        } else {
            drain();
            append(output);
        }
        sink.flush();
    }

    private void enqueue(PendingOutput output) {
        if (fits(output)) {
            add(output);
            return;
        }
        if (output.priority() == Priority.NORMAL) {
            suppressedNormalCount++;
            return;
        }
        ensureCriticalCanFit(output);
        while (!fits(output)) {
            removeOldestNormal();
        }
        add(output);
    }

    private void ensureCriticalCanFit(PendingOutput output) {
        int criticalCount = 0;
        int criticalBytes = 0;
        for (PendingOutput candidate : pending) {
            if (candidate.priority() == Priority.CRITICAL) {
                criticalCount++;
                criticalBytes += candidate.utf8Bytes();
            }
        }
        if (criticalCount >= MAX_PENDING_EVENTS
                || output.utf8Bytes()
                > MAX_PENDING_UTF8_BYTES - criticalBytes) {
            throw new IllegalStateException(
                    "terminal output failure: critical output exceeds pending capacity");
        }
    }

    private boolean fits(PendingOutput output) {
        return pending.size() < MAX_PENDING_EVENTS
                && output.utf8Bytes() <= MAX_PENDING_UTF8_BYTES - pendingUtf8Bytes;
    }

    private void add(PendingOutput output) {
        pending.addLast(output);
        pendingUtf8Bytes += output.utf8Bytes();
    }

    private boolean removeOldestNormal() {
        Iterator<PendingOutput> iterator = pending.iterator();
        while (iterator.hasNext()) {
            PendingOutput candidate = iterator.next();
            if (candidate.priority() == Priority.NORMAL) {
                iterator.remove();
                pendingUtf8Bytes -= candidate.utf8Bytes();
                suppressedNormalCount++;
                return true;
            }
        }
        return false;
    }

    private void drain() {
        PendingOutput output;
        while ((output = pending.pollFirst()) != null) {
            pendingUtf8Bytes -= output.utf8Bytes();
            append(output);
        }
        if (suppressedNormalCount > 0) {
            sink.append("[Output] " + suppressedNormalCount
                    + " background updates were suppressed");
            suppressedNormalCount = 0;
        }
    }

    private void append(PendingOutput output) {
        output.lines().forEach(sink::append);
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("terminal output is closed");
    }

    private int width() {
        try {
            return InlineTranscriptLayout.boundedWidth(terminalWidth.getAsInt());
        } catch (RuntimeException ignored) {
            return InlineTranscriptLayout.DEFAULT_WIDTH;
        }
    }

    private static int utf8Bytes(List<String> lines) {
        long bytes = 0;
        for (String line : lines) {
            bytes += line.getBytes(StandardCharsets.UTF_8).length + 1L;
            if (bytes > MAX_PENDING_UTF8_BYTES) {
                return MAX_PENDING_UTF8_BYTES + 1;
            }
        }
        return (int) bytes;
    }

    private static List<String> sanitize(List<String> values) {
        List<String> lines = new ArrayList<>();
        for (String value : values) {
            lines.addAll(EventLineFormatter.transcriptLines(value));
        }
        return List.copyOf(lines);
    }

    private record PendingOutput(List<String> lines, int utf8Bytes,
            Priority priority) { }
}
