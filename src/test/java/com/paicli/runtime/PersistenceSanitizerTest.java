package com.paicli.runtime;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceSanitizerTest {

    private final PersistenceSanitizer sanitizer = new PersistenceSanitizer();

    @Test
    void redactsCredentialsWhileKeepingSurroundingTextReadable() {
        String input = "Plan step\napiKey=alpha\npassword: beta\n"
                + "Authorization: Bearer gamma\nCookie: session=delta\nContinue";

        String result = sanitizer.sanitize(input);

        assertTrue(result.contains("Plan step"));
        assertTrue(result.contains("Continue"));
        assertTrue(result.contains("<redacted>"));
        assertFalse(result.contains("alpha"));
        assertFalse(result.contains("beta"));
        assertFalse(result.contains("gamma"));
        assertFalse(result.contains("delta"));
    }

    @Test
    void removesCompleteHttpMessageInsteadOfPersistingItsBody() {
        String result = sanitizer.sanitize(
                "Verification failed:\nPOST /api HTTP/1.1\nHost: example.test\n\nprivate body");

        assertTrue(result.contains("Verification failed"));
        assertTrue(result.contains("[redacted complete HTTP message]"));
        assertFalse(result.contains("private body"));
    }

    @Test
    void redactsSensitiveJsonStyleAssignments() {
        String result = sanitizer.sanitize(
                "{\"apiKey\": \"alpha\", \"nested\": {\"access_token\": \"beta\"}}");

        assertFalse(result.contains("alpha"));
        assertFalse(result.contains("beta"));
        assertTrue(result.contains("\"<redacted>\""));
    }

    @Test
    void truncatesByUtf8BytesWithoutSplittingCharacters() {
        String result = sanitizer.sanitizeAndTruncate("计划".repeat(100), 80);

        assertTrue(result.getBytes(StandardCharsets.UTF_8).length <= 80);
        assertTrue(result.contains("[truncated; original_bytes="));
    }

    @Test
    void handlesNullAndRejectsInvalidLimit() {
        assertNull(sanitizer.sanitizeAndTruncate(null, 100));
        assertThrows(IllegalArgumentException.class,
                () -> sanitizer.sanitizeAndTruncate("text", 0));
    }
}
