package com.paicli.interactive.render;

import org.jline.utils.AttributedStyle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class InlineTranscriptLayoutTest {

    @Test
    void foregroundBlockUsesPureDividersTwoSpaceIndentAndHangingWrap() {
        CaptureSink sink = new CaptureSink();
        TerminalOutputPort output = new SerializedTerminalOutputPort(
                new AtomicBoolean(false)::get, () -> 32, sink);

        output.beginTaskBlock();
        output.writeLine("[Task] Preparing...");
        output.writeLine("[Error] 结构化计划响应包含一段需要换行的中文诊断文本。");
        output.writeLine("[Task] Failed · 20.4s");
        output.endTaskBlock();

        assertEquals("─".repeat(32), sink.lines.getFirst());
        assertEquals("─".repeat(32), sink.lines.getLast());
        assertTrue(sink.lines.subList(1, sink.lines.size() - 1).stream()
                .allMatch(line -> line.startsWith("  ")), sink.lines.toString());
        assertTrue(sink.lines.stream().allMatch(line ->
                InlineTranscriptLayout.displayWidth(line) <= 32), sink.lines.toString());
        List<String> wrappedError = sink.lines.stream()
                .filter(line -> line.contains("结构化") || line.contains("中文诊断"))
                .toList();
        assertTrue(wrappedError.size() >= 2, sink.lines.toString());
        assertTrue(wrappedError.get(1).startsWith("          "),
                wrappedError.toString());
        assertFalse(String.join("\n", sink.lines).contains("Task · Planning"));
        assertFalse(String.join("\n", sink.lines).contains("─ Failed"));
    }

    @Test
    void beginAndEndAreIdempotentAndPlainLayoutMatchesAnsiLayoutStructure() {
        CaptureSink sink = new CaptureSink();
        TerminalOutputPort output = new SerializedTerminalOutputPort(
                () -> false, () -> 56, sink);

        output.beginTaskBlock();
        output.beginTaskBlock();
        output.writeLine("[Task] Task accepted.");
        output.endTaskBlock();
        output.endTaskBlock();

        assertEquals(3, sink.lines.size());
        assertEquals("─".repeat(56), sink.lines.get(0));
        assertEquals("  [Task] Task accepted.", sink.lines.get(1));
        assertEquals("─".repeat(56), sink.lines.get(2));
    }

    @Test
    void semanticColorsRecognizeIndentedRuntimeLines() {
        assertEquals(AttributedStyle.DEFAULT.foreground(AttributedStyle.RED).bold(),
                TerminalSemanticStyle.styleFor("  [Error] failed"));
        assertEquals(AttributedStyle.DEFAULT.foreground(AttributedStyle.BLUE).bold(),
                TerminalSemanticStyle.styleFor("  [Task] accepted"));
        assertEquals(AttributedStyle.DEFAULT.foreground(AttributedStyle.MAGENTA),
                TerminalSemanticStyle.styleFor("  [Explore] files"));
        assertEquals(AttributedStyle.DEFAULT.foreground(AttributedStyle.MAGENTA),
                TerminalSemanticStyle.styleFor("  [Model] Planning · 10s elapsed"));
    }

    private static final class CaptureSink
            implements SerializedTerminalOutputPort.Sink {
        private final List<String> lines = new ArrayList<>();
        @Override public void append(String line) { lines.add(line); }
        @Override public void flush() { }
    }
}
