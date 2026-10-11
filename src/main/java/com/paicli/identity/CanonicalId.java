package com.paicli.identity;

import java.util.UUID;
import java.util.regex.Pattern;

/** Strict 36-character persisted R1 identifier. Invalid input is never normalized. */
public record CanonicalId(EntityFamily family, String value) {
    private static final Pattern FORMAT = Pattern.compile("^(thr|tsk|run|gen|ctx|cap|pln|stp|str|occ)_[0-9a-f]{32}$");
    public CanonicalId {
        if (family == null || value == null || !FORMAT.matcher(value).matches() || !isUuidV4(value.substring(4))
                || !value.startsWith(family.prefix())) {
            throw new IllegalArgumentException("Invalid canonical ID");
        }
    }
    public static CanonicalId newId(EntityFamily family) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        return new CanonicalId(family, family.prefix() + suffix);
    }
    public static CanonicalId parse(String input) {
        if (input == null || !FORMAT.matcher(input).matches()) throw new IllegalArgumentException("Invalid canonical ID");
        return new CanonicalId(EntityFamily.fromPrefix(input.substring(0, 4)), input);
    }
    public String shortDisplay() { return value.substring(0, 16); }
    public String shortDisplay(int suffixLength) {
        if (suffixLength < 12 || suffixLength > 31) throw new IllegalArgumentException("Short suffix must be 12..31");
        return value.substring(0, 4 + suffixLength);
    }
    private static boolean isUuidV4(String suffix) {
        return suffix.charAt(12) == '4' && "89ab".indexOf(suffix.charAt(16)) >= 0;
    }
}
