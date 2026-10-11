package com.paicli.state;

import com.paicli.runtime.PersistenceSanitizer;
import java.util.regex.Pattern;

final class McpStoreSupport {
    static final int EXCERPT = 4096;
    static final PersistenceSanitizer SANITIZER = new PersistenceSanitizer();
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}");
    private static final Pattern HASH = Pattern.compile("[a-fA-F0-9]{64}");
    private McpStoreSupport() {}
    static String id(String v, String f) { if(v==null||!ID.matcher(v).matches()) throw new IllegalArgumentException("invalid "+f); return v; }
    static String text(String v, String f, int max) { if(v==null||v.isBlank()) throw new IllegalArgumentException(f+" required"); return SANITIZER.sanitizeAndTruncate(v,max); }
    static String optional(String v, int max) { return v==null?null:SANITIZER.sanitizeAndTruncate(v,max); }
    static String hash(String v, String f) { if(v==null||!HASH.matcher(v).matches()) throw new IllegalArgumentException("invalid "+f); return v.toLowerCase(); }
}
