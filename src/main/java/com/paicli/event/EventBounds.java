package com.paicli.event;

import com.paicli.runtime.PersistenceSanitizer;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

final class EventBounds {
    static final int SHORT = 256;
    static final int SUMMARY = 2048;
    static final int EXCERPT = 4096;
    static final int MAX_ITEMS = 64;
    private static final PersistenceSanitizer SANITIZER = new PersistenceSanitizer();
    private static final Pattern ABSOLUTE_PATH = Pattern.compile(
            "(?<![A-Za-z0-9])(?:[A-Za-z]:\\\\[^\\s]+|/(?:[^\\s/]+/)+[^\\s]*)");

    private EventBounds() { }

    static String required(String value, int max, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return bounded(value, max, field);
    }

    static String optional(String value, int max, String field) {
        return value == null ? null : bounded(value, max, field);
    }

    static String requiredPreview(String value, int max, String field) {
        if(value==null||value.isBlank())throw new IllegalArgumentException(field+" is required");
        return optionalPreview(value,max,field);
    }

    /** Diagnostic text is a projection, unlike identifiers and the hashed final answer.
     * Producers must still supply bounded input. Redaction may expand that input, so
     * enforce the display budget after redaction without exposing the original text. */
    static String optionalPreview(String value, int max, String field) {
        if(value==null)return null;
        if(value.length()>max)throw new IllegalArgumentException(field+" exceeds "+max+" characters");
        String clean=redact(value);
        checkCharacters(clean,field); // Invalid controls must not disappear behind the truncation.
        if(clean.length()>max) {
            String marker=" [truncated]";
            if(max<marker.length())marker=marker.substring(0,max);
            int end=max-marker.length();
            if(end>0&&Character.isHighSurrogate(clean.charAt(end-1)))end--;
            clean=clean.substring(0,end)+marker;
        }
        return checkBounds(clean,max,field);
    }

    static String finalResponse(String value,int max,String field) {
        if(value==null||value.isBlank())throw new IllegalArgumentException(field+" is required");
        com.paicli.runtime.FinalResponseContent.requireNoHostDrivePath(value);
        // This is the already accepted, sanitized and hashed user result. Unlike
        // diagnostic excerpts, it must not be rewritten while reporting delivery.
        if(!value.equals(SANITIZER.sanitize(value)))
            throw new IllegalArgumentException(field+" must already be sanitized");
        return checkBounds(value,max,field);
    }

    static String relativePath(String value, String field) {
        String bounded = required(value, 512, field).replace('\\', '/');
        Path path = Path.of(bounded);
        if (path.isAbsolute() || bounded.startsWith("/") || bounded.contains(":")) {
            throw new IllegalArgumentException(field + " must be relative");
        }
        for (Path part : path) {
            if ("..".equals(part.toString())) throw new IllegalArgumentException(field + " escapes workspace");
        }
        return bounded;
    }

    static List<String> items(List<String> values, int itemMax, String field) {
        List<String> copy = values == null ? List.of() : List.copyOf(values);
        if (copy.size() > MAX_ITEMS) throw new IllegalArgumentException(field + " has too many items");
        return copy.stream().map(value -> required(value, itemMax, field)).toList();
    }

    private static String bounded(String value, int max, String field) {
        return checkBounds(redact(value),max,field);
    }

    private static String redact(String value) {
        return ABSOLUTE_PATH.matcher(SANITIZER.sanitize(value)).replaceAll("[ABSOLUTE_PATH_REDACTED]");
    }

    private static String checkBounds(String value,int max,String field) {
        if (value.length() > max) throw new IllegalArgumentException(field + " exceeds " + max + " characters");
        checkCharacters(value,field);
        return value;
    }

    private static void checkCharacters(String value,String field) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < 0x20 && c != '\n' && c != '\t') || c == 0x7f) {
                throw new IllegalArgumentException(field + " contains control characters");
            }
        }
    }
}
