package com.paicli.interactive.terminal;

import com.paicli.interactive.render.TerminalSemanticStyle;
import org.jline.reader.Highlighter;
import org.jline.reader.LineReader;
import org.jline.utils.AttributedString;

import java.util.regex.Pattern;

/** Stable foreground emphasis for the user-owned composer buffer. */
final class InteractiveInputHighlighter implements Highlighter {
    @Override
    public AttributedString highlight(LineReader reader, String buffer) {
        return TerminalSemanticStyle.input(buffer,
                reader == null ? null : reader.getTerminal());
    }

    @Override
    public void setErrorPattern(Pattern errorPattern) { }

    @Override
    public void setErrorIndex(int errorIndex) { }
}
