package com.paicli.runtime;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Sanitizes and bounds text before it is written to persistent runtime state. */
public final class PersistenceSanitizer {

    public static final int DEFAULT_MAX_TEXT_BYTES = 64 * 1024;

    private static final Pattern COMPLETE_HTTP_MESSAGE = Pattern.compile(
            "(?ims)(^|\\R)(?:HTTP/\\d(?:\\.\\d)?\\s+\\d{3}|"
                    + "(?:GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\\s+\\S+\\s+HTTP/\\d(?:\\.\\d)?).*$");
    private static final Pattern SENSITIVE_HEADER = Pattern.compile(
            "(?im)(\\b(?:authorization|proxy-authorization|cookie|set-cookie)\\s*[:=]\\s*)[^\\r\\n]+");
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
            "(?i)((?:[\"']?)(?:api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|"
                    + "password|secret|token)(?:[\"']?)\\s*[:=]\\s*)"
                    + "(?:\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|[^\\s,;]+)");
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)\\bBearer\\s+[^\\s,;]+" );

    public String sanitize(String value) {
        if (value == null) {
            return null;
        }
        String sanitized = value.replace('\0', ' ');
        Matcher httpMatcher = COMPLETE_HTTP_MESSAGE.matcher(sanitized);
        if (httpMatcher.find()) {
            sanitized = httpMatcher.replaceFirst("$1[redacted complete HTTP message]");
        }
        sanitized = SENSITIVE_HEADER.matcher(sanitized).replaceAll("$1<redacted>");
        sanitized = SENSITIVE_ASSIGNMENT.matcher(sanitized).replaceAll("$1\"<redacted>\"");
        return BEARER_TOKEN.matcher(sanitized).replaceAll("Bearer <redacted>");
    }

    public String sanitizeAndTruncate(String value, int maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        String sanitized = sanitize(value);
        if (sanitized == null) {
            return null;
        }
        int originalBytes = utf8Length(sanitized);
        if (originalBytes <= maxBytes) {
            return sanitized;
        }

        String marker = "\n...[truncated; original_bytes=" + originalBytes + "]";
        int markerBytes = utf8Length(marker);
        if (markerBytes >= maxBytes) {
            return truncateUtf8("[truncated]", maxBytes);
        }
        return truncateUtf8(sanitized, maxBytes - markerBytes) + marker;
    }

    private String truncateUtf8(String value, int maxBytes) {
        Objects.requireNonNull(value, "value");
        StringBuilder result = new StringBuilder();
        int usedBytes = 0;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            String character = new String(Character.toChars(codePoint));
            int characterBytes = utf8Length(character);
            if (usedBytes + characterBytes > maxBytes) {
                break;
            }
            result.append(character);
            usedBytes += characterBytes;
            offset += Character.charCount(codePoint);
        }
        return result.toString();
    }

    private int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }
}
