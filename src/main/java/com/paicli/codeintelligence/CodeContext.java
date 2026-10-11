package com.paicli.codeintelligence;

import java.util.List;

public record CodeContext(
        List<CodeContextSection> sections,
        boolean truncated,
        String contextHash,
        int totalChars
) {
}
