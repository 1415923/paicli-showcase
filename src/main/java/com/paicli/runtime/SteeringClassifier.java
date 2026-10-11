package com.paicli.runtime;

import com.paicli.state.SteeringModels.Classification;
import com.paicli.state.SteeringModels.ClassificationOutcome;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/** Conservative provider-free R1D classifier. Unproved input remains UNKNOWN. */
public final class SteeringClassifier {
    public static final String VERSION = "R1D-DETERMINISTIC-V1";
    public static final int MAX_CONTENT_CHARS = 4096;
    private static final Pattern MATERIAL = Pattern.compile(
            "\\b(goal|acceptance|scope|file|create|delete|remove|permission|approval|verify|verification|plan|step|tool|effect|replan)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern NON_MATERIAL = Pattern.compile(
            "^(?:priority|ordering|format|wording|diagnostic|explanation|verbosity|continue|proceed)\\s*[:：-].+",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern AUTHORIZATION_BEARER = Pattern.compile(
            "(?i)\\bauthorization\\s*:\\s*bearer\\s+\\S+");
    private static final Pattern BARE_BEARER = Pattern.compile(
            "(?i)\\bbearer\\s+\\S+");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)\\b(api[_-]?key|token|password|secret|authorization)(?:\\s*[:=]\\s*|\\s+)\\S+");

    public Result classify(String raw) {
        String content = sanitize(raw);
        if (content.isBlank()) return new Result(content, sha(content), Classification.REJECT,
                ClassificationOutcome.REJECT, "EMPTY_OR_CONTROL_ONLY");
        if (MATERIAL.matcher(content).find()) return new Result(content, sha(content),
                Classification.MATERIAL, ClassificationOutcome.ACCEPT_MATERIAL,
                "MATERIAL_DIMENSION_CHANGED");
        if (NON_MATERIAL.matcher(content).matches()) return new Result(content, sha(content),
                Classification.NON_MATERIAL, ClassificationOutcome.ACCEPT,
                "EXPLICIT_NON_MATERIAL_DIMENSION");
        return new Result(content, sha(content), Classification.UNKNOWN,
                ClassificationOutcome.NEEDS_CONFIRMATION, "IMPACT_NOT_DETERMINISTIC");
    }

    public String sanitize(String raw) {
        if (raw == null) return "";
        String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC)
                .replaceAll("[\\p{Cc}&&[^\\n\\t]]", " ")
                .replace('\r', '\n').replaceAll("\\n{3,}", "\\n\\n").strip();
        normalized = AUTHORIZATION_BEARER.matcher(normalized)
                .replaceAll("<redacted>");
        normalized = BARE_BEARER.matcher(normalized).replaceAll("<redacted>");
        normalized = SECRET.matcher(normalized).replaceAll("<redacted>");
        if (normalized.length() <= MAX_CONTENT_CHARS) return normalized;
        int end = MAX_CONTENT_CHARS;
        if (Character.isHighSurrogate(normalized.charAt(end - 1))) end--;
        return normalized.substring(0, end);
    }

    public static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException("SHA-256 unavailable", error); }
    }

    public record Result(String content, String contentHash, Classification classification,
            ClassificationOutcome outcome, String reasonCode) {
        public String diagnostic() {
            return classification.name().toLowerCase(Locale.ROOT) + ":" + reasonCode;
        }
    }
}
