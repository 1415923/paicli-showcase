package com.paicli.state;

import com.paicli.runtime.PersistenceSanitizer;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Set;
import java.util.regex.Pattern;

final class WorkerStoreSupport {

    static final int MAX_EXCERPT_CHARS = 4096;
    static final int MAX_CHANGED_FILES_CHARS = 16384;
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}");
    private static final Pattern HASH = Pattern.compile("[A-Fa-f0-9]{1,128}");
    private static final Pattern COMMIT_SHA = Pattern.compile("(?:[A-Fa-f0-9]{40}|[A-Fa-f0-9]{64})");
    private static final PersistenceSanitizer SANITIZER = new PersistenceSanitizer();

    private WorkerStoreSupport() {
    }

    static String id(String value, String field) {
        if (value == null || !SAFE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a bounded safe identifier");
        }
        return value;
    }

    static String required(String value, String field, int maxChars) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String sanitized = SANITIZER.sanitizeAndTruncate(value, maxChars);
        if (sanitized.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return sanitized;
    }

    static String optional(String value, int maxChars) {
        return value == null ? null : SANITIZER.sanitizeAndTruncate(value, maxChars);
    }

    static String hash(String value, String field) {
        if (value == null || !HASH.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a bounded hexadecimal hash");
        }
        return value.toLowerCase();
    }

    static String optionalHash(String value, String field) {
        return value == null ? null : hash(value, field);
    }

    static String commitSha(String value, String field) {
        if (value == null || !COMMIT_SHA.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a 40 or 64 character hexadecimal SHA");
        }
        return value.toLowerCase();
    }

    static String optionalCommitSha(String value, String field) {
        return value == null ? null : commitSha(value, field);
    }

    static String oneOf(String value, String field, Set<String> allowed) {
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException("invalid " + field + ": " + value);
        }
        return value;
    }

    static void requireUpdated(PreparedStatement statement, String message) throws SQLException {
        if (statement.executeUpdate() == 0) {
            throw new SQLException(message);
        }
    }
}
