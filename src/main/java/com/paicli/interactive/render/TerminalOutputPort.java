package com.paicli.interactive.render;

import org.jline.reader.LineReader;

import java.io.PrintWriter;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Single serialized presentation boundary for transcript and bounded asynchronous output.
 */
public interface TerminalOutputPort extends AutoCloseable {
    enum Priority { NORMAL, CRITICAL }

    void writeLine(String line, Priority priority);

    default void writeLine(String line) {
        writeLine(line, Priority.NORMAL);
    }

    default void writeLines(List<String> lines) {
        writeLines(lines, Priority.NORMAL);
    }

    void writeLines(List<String> lines, Priority priority);

    /** Opens the single foreground transcript block and writes its pure divider. */
    default void beginTaskBlock() { }

    /** Closes the foreground transcript block with a pure divider. Idempotent. */
    default void endTaskBlock() { }

    /** Writes an input-owner prompt through the same serialized output authority. */
    default void writePrompt(String prompt) { writeLine(prompt); }

    void flush();

    @Override default void close() { flush(); }

    static TerminalOutputPort jline(LineReader reader, BooleanSupplier composerActive) {
        return SerializedTerminalOutputPort.jline(reader, composerActive);
    }

    static TerminalOutputPort plain(PrintWriter output, BooleanSupplier composerActive) {
        return SerializedTerminalOutputPort.plain(output, composerActive);
    }
}
