package com.paicli.subagent.verdict;

import java.util.ArrayList;
import java.util.List;

public final class JsonModePromptStrategy implements VerdictExtractionStrategy {

    public static final String NAME = "json-mode-prompt";

    private final StructuredVerdictParser parser;

    public JsonModePromptStrategy() {
        this(new StructuredVerdictParser());
    }

    JsonModePromptStrategy(StructuredVerdictParser parser) {
        this.parser = parser;
    }

    @Override
    public String strategyName() {
        return NAME;
    }

    @Override
    public boolean supports(VerdictExtractionRequest request) {
        return request.mode() == VerdictExtractionMode.AUTO
                || request.mode() == VerdictExtractionMode.JSON;
    }

    @Override
    public VerdictExtractionResult extract(VerdictExtractionRequest request) {
        if (request.rawOutput() == null || request.rawOutput().isBlank()) {
            return VerdictExtractionResult.from(
                    NAME, StructuredVerdict.inconclusive("parse_error: empty JSON verdict output"));
        }

        StructuredVerdict lastFailure = null;
        for (String candidate : jsonObjects(request.rawOutput())) {
            StructuredVerdict verdict = parser.parseJson(candidate);
            if (verdict.parsed()) {
                return VerdictExtractionResult.from(NAME, verdict);
            }
            lastFailure = verdict;
        }
        if (lastFailure == null) {
            lastFailure = StructuredVerdict.inconclusive(
                    "parse_error: no complete JSON verdict object found");
        }
        return VerdictExtractionResult.from(NAME, lastFailure);
    }

    private List<String> jsonObjects(String text) {
        List<String> candidates = new ArrayList<>();
        for (int start = text.indexOf('{'); start >= 0; start = text.indexOf('{', start + 1)) {
            int end = matchingObjectEnd(text, start);
            if (end >= 0) {
                candidates.add(text.substring(start, end + 1));
            }
        }
        return candidates;
    }

    private int matchingObjectEnd(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = start; index < text.length(); index++) {
            char character = text.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    inString = false;
                }
                continue;
            }
            if (character == '"') {
                inString = true;
            } else if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }
}
