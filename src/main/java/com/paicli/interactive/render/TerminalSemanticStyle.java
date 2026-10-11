package com.paicli.interactive.render;

import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;

/** ANSI/JLine styling applied only after transcript sanitization. */
public final class TerminalSemanticStyle {
    private TerminalSemanticStyle() { }

    public static String line(String value, Terminal terminal) {
        String text = value == null ? "" : value;
        if (!enabled(terminal) || text.isEmpty()) return text;
        return attributed(text, styleFor(text)).toAnsi(terminal);
    }

    public static String prompt(String value, Terminal terminal) {
        String text = value == null ? "" : value;
        if (!enabled(terminal) || text.isEmpty()) return text;
        return attributed(text, AttributedStyle.DEFAULT
                .foreground(AttributedStyle.CYAN).bold()).toAnsi(terminal);
    }

    public static AttributedString input(String value, Terminal terminal) {
        String text = value == null ? "" : value;
        if (!enabled(terminal) || text.isEmpty()) return new AttributedString(text);
        return attributed(text,
                AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN).bold());
    }

    static AttributedStyle styleFor(String line) {
        String semantic = line == null ? "" : line.stripLeading();
        String upper = semantic.toUpperCase(java.util.Locale.ROOT);
        if (semantic.startsWith("[Error]") || upper.contains(" FAILED")) {
            return AttributedStyle.DEFAULT.foreground(AttributedStyle.RED).bold();
        }
        if (semantic.startsWith("[Warning]") || upper.contains(" CANCELLED")
                || upper.contains(" INCONCLUSIVE")) {
            return AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW).bold();
        }
        if (semantic.startsWith("[Hint]") || semantic.startsWith("Next:")) {
            return AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN);
        }
        if (upper.contains(" COMPLETED") || upper.contains(" SUCCEEDED")
                || semantic.startsWith("[Verify] passed")) {
            return AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN).bold();
        }
        if (semantic.startsWith("[Approval]") || semantic.startsWith("[Proposed")) {
            return AttributedStyle.DEFAULT.foreground(AttributedStyle.YELLOW);
        }
        if (semantic.startsWith("[Task]") || semantic.startsWith("[Run]")) {
            return AttributedStyle.DEFAULT.foreground(AttributedStyle.BLUE).bold();
        }
        if (semantic.startsWith("[Result]")) {
            return AttributedStyle.DEFAULT.foreground(AttributedStyle.GREEN);
        }
        if (semantic.startsWith("• ") || semantic.startsWith("│")
                || semantic.startsWith("├") || semantic.startsWith("└")
                || semantic.startsWith("[Model]")
                || semantic.startsWith("[Update]")
                || semantic.startsWith("[Explore]") || semantic.startsWith("[Tool]")
                || semantic.startsWith("[Plan]") || semantic.startsWith("[Phase]")
                || semantic.startsWith("[Read]") || semantic.startsWith("[Write]")
                || semantic.startsWith("[Verify]") || semantic.startsWith("[Repair]")) {
            return AttributedStyle.DEFAULT.foreground(AttributedStyle.MAGENTA);
        }
        if (semantic.startsWith("PaiCLI")) {
            return AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN).bold();
        }
        return AttributedStyle.DEFAULT.faint();
    }

    private static AttributedString attributed(String value, AttributedStyle style) {
        return new AttributedStringBuilder().style(style).append(value)
                .style(AttributedStyle.DEFAULT).toAttributedString();
    }

    private static boolean enabled(Terminal terminal) {
        return terminal != null
                && !Terminal.TYPE_DUMB.equalsIgnoreCase(terminal.getType())
                && System.getenv("NO_COLOR") == null;
    }
}
