package com.paicli.interactive.render;

import java.util.ArrayList;
import java.util.List;

/** Width-bounded, scrollback-preserving layout helpers for inline transcript blocks. */
final class InlineTranscriptLayout {
    static final int MIN_WIDTH = 24;
    static final int MAX_WIDTH = 80;
    static final int DEFAULT_WIDTH = 56;
    private static final String INDENT = "  ";

    private InlineTranscriptLayout() { }

    static int boundedWidth(int reportedWidth) {
        if (reportedWidth <= 0) return DEFAULT_WIDTH;
        return Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, reportedWidth));
    }

    static String divider(int reportedWidth) {
        return "─".repeat(boundedWidth(reportedWidth));
    }

    static String titledDivider(String title, int reportedWidth) {
        int width = boundedWidth(reportedWidth);
        String safe = title == null ? "" : title.replace('\r', ' ')
                .replace('\n', ' ').strip();
        int titleWidth = displayWidth(safe);
        if (titleWidth + 4 > width) {
            safe = fit(safe, Math.max(1, width - 5));
            titleWidth = displayWidth(safe);
        }
        int remaining = Math.max(2, width - titleWidth - 2);
        int left = Math.max(1, remaining / 2);
        int right = Math.max(1, remaining - left);
        return "─".repeat(left) + " " + safe + " " + "─".repeat(right);
    }

    static List<String> taskLines(List<String> lines, int reportedWidth) {
        if (lines == null || lines.isEmpty()) return List.of();
        int width = boundedWidth(reportedWidth);
        List<String> result = new ArrayList<>();
        for (String line : lines) result.addAll(wrap(line == null ? "" : line, width));
        return List.copyOf(result);
    }

    static String fit(String value, int maxColumns) {
        String text = value == null ? "" : value;
        if (displayWidth(text) <= maxColumns) return text;
        int budget = Math.max(0, maxColumns - 1);
        StringBuilder result = new StringBuilder();
        int used = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            int columns = columns(codePoint);
            if (used + columns > budget) break;
            result.appendCodePoint(codePoint);
            used += columns;
            offset += Character.charCount(codePoint);
        }
        return result.append('…').toString();
    }

    static int displayWidth(String value) {
        int width = 0;
        String text = value == null ? "" : value;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            width += columns(codePoint);
            offset += Character.charCount(codePoint);
        }
        return width;
    }

    private static List<String> wrap(String value, int width) {
        if (value.isEmpty()) return List.of(INDENT);
        int labelEnd = labelEnd(value);
        String continuation = labelEnd <= 0 ? INDENT
                : INDENT + " ".repeat(displayWidth(value.substring(0, labelEnd)));
        List<String> result = new ArrayList<>();
        int offset = 0;
        boolean first = true;
        while (offset < value.length()) {
            String prefix = first ? INDENT : continuation;
            int available = Math.max(1, width - displayWidth(prefix));
            int end = fittingEnd(value, offset, available);
            result.add(prefix + value.substring(offset, end));
            offset = end;
            first = false;
        }
        return result;
    }

    private static int fittingEnd(String value, int start, int columns) {
        int used = 0;
        int offset = start;
        while (offset < value.length()) {
            int codePoint = value.codePointAt(offset);
            int width = columns(codePoint);
            if (used + width > columns) break;
            used += width;
            offset += Character.charCount(codePoint);
        }
        if (offset == start) {
            return start + Character.charCount(value.codePointAt(start));
        }
        return offset;
    }

    private static int labelEnd(String value) {
        if (!value.startsWith("[")) return -1;
        int closing = value.indexOf("] ");
        return closing < 0 ? -1 : closing + 2;
    }

    private static int columns(int codePoint) {
        if (codePoint == 0 || Character.getType(codePoint) == Character.NON_SPACING_MARK
                || Character.getType(codePoint) == Character.ENCLOSING_MARK
                || Character.getType(codePoint) == Character.FORMAT) {
            return 0;
        }
        return isWide(codePoint) ? 2 : 1;
    }

    private static boolean isWide(int codePoint) {
        return codePoint >= 0x1100 && (
                codePoint <= 0x115f
                || codePoint == 0x2329 || codePoint == 0x232a
                || codePoint >= 0x2e80 && codePoint <= 0xa4cf
                        && codePoint != 0x303f
                || codePoint >= 0xac00 && codePoint <= 0xd7a3
                || codePoint >= 0xf900 && codePoint <= 0xfaff
                || codePoint >= 0xfe10 && codePoint <= 0xfe19
                || codePoint >= 0xfe30 && codePoint <= 0xfe6f
                || codePoint >= 0xff00 && codePoint <= 0xff60
                || codePoint >= 0xffe0 && codePoint <= 0xffe6
                || codePoint >= 0x1f300 && codePoint <= 0x1faff
                || codePoint >= 0x20000 && codePoint <= 0x3fffd);
    }
}
