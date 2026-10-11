package com.paicli.subagent.verdict;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class StructuredVerdictParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_SUMMARY_CHARS = 1_000;
    private static final int MAX_EVIDENCE_MESSAGE_CHARS = 1_000;
    private static final int MAX_ERROR_CHARS = 240;

    public StructuredVerdict parseJson(String text) {
        if (text == null || text.isBlank()) {
            return inconclusive("parse_error: empty verdict");
        }
        try {
            JsonNode root = MAPPER.readTree(text);
            Verdict verdict = enumField(root, "verdict", Verdict.class);
            Severity severity = enumField(root, "severity", Severity.class);
            RequiredAction action = enumField(root, "required_action", RequiredAction.class);
            if (action == null) {
                action = enumField(root, "requiredAction", RequiredAction.class);
            }
            Double confidence = doubleField(root, "confidence");
            String summary = textField(root, "summary");
            List<VerdictEvidence> evidence = parseEvidence(root.get("evidence"));
            return validate(verdict, severity, confidence, evidence, action, summary);
        } catch (Exception e) {
            return inconclusive("parse_error: malformed JSON verdict");
        }
    }

    public StructuredVerdict parseMarker(String text) {
        if (text == null || text.isBlank()) {
            return inconclusive("parse_error: empty marker verdict");
        }

        Verdict verdict = null;
        Severity severity = null;
        RequiredAction action = null;
        Double confidence = null;
        String summary = null;
        List<VerdictEvidence> evidence = new ArrayList<>();

        try {
            for (String rawLine : text.split("\\R")) {
                String line = rawLine.trim();
                if (!line.startsWith("[") || !line.endsWith("]") || !line.contains(":")) {
                    continue;
                }
                int colon = line.indexOf(':');
                String key = line.substring(1, colon).trim().toUpperCase(Locale.ROOT);
                String value = line.substring(colon + 1, line.length() - 1).trim();
                switch (key) {
                    case "VERDICT" -> verdict = enumValue(value, Verdict.class);
                    case "SEVERITY" -> severity = enumValue(value, Severity.class);
                    case "CONFIDENCE" -> confidence = Double.parseDouble(value);
                    case "REQUIRED_ACTION" -> action = enumValue(value, RequiredAction.class);
                    case "SUMMARY" -> summary = value;
                    case "EVIDENCE" -> evidence.add(parseMarkerEvidence(value));
                    default -> {
                    }
                }
            }
            return validate(verdict, severity, confidence, evidence, action, summary);
        } catch (Exception e) {
            return inconclusive("parse_error: malformed marker verdict");
        }
    }

    private StructuredVerdict validate(Verdict verdict, Severity severity, Double confidence,
                                       List<VerdictEvidence> evidence, RequiredAction action, String summary) {
        if (verdict == null || severity == null || confidence == null || action == null || summary == null) {
            return inconclusive("parse_error: missing required verdict fields");
        }
        if (confidence < 0.0d || confidence > 1.0d) {
            return inconclusive("parse_error: confidence out of range");
        }
        if (evidence == null || evidence.isEmpty()) {
            return inconclusive("parse_error: evidence is required");
        }
        for (VerdictEvidence item : evidence) {
            if (item.sourceType() == null) {
                return inconclusive("parse_error: evidence source_type is required");
            }
        }
        if (verdict == Verdict.PASS && action != RequiredAction.NONE) {
            return inconclusive("parse_error: PASS verdict conflicts with required action");
        }
        if (verdict == Verdict.FAIL && action == RequiredAction.NONE) {
            return inconclusive("parse_error: FAIL verdict requires action");
        }
        return new StructuredVerdict(
                verdict,
                severity,
                confidence,
                truncateEvidence(evidence),
                action,
                truncate(summary, MAX_SUMMARY_CHARS),
                true
        );
    }

    private List<VerdictEvidence> parseEvidence(JsonNode evidenceNode) {
        if (evidenceNode == null || !evidenceNode.isArray()) {
            return List.of();
        }
        List<VerdictEvidence> evidence = new ArrayList<>();
        for (JsonNode item : evidenceNode) {
            EvidenceSourceType sourceType = enumField(item, "source_type", EvidenceSourceType.class);
            if (sourceType == null) {
                sourceType = enumField(item, "sourceType", EvidenceSourceType.class);
            }
            String file = textField(item, "file");
            Integer line = item.has("line") && item.get("line").canConvertToInt() ? item.get("line").asInt() : null;
            String message = textField(item, "message");
            evidence.add(new VerdictEvidence(sourceType, file, line, message));
        }
        return evidence;
    }

    private VerdictEvidence parseMarkerEvidence(String value) {
        String[] parts = value.split(":", 4);
        if (parts.length < 4) {
            throw new IllegalArgumentException("marker evidence must include source_type:file:line:message");
        }
        EvidenceSourceType sourceType = enumValue(parts[0], EvidenceSourceType.class);
        String file = parts[1].isBlank() ? null : parts[1].trim();
        Integer line = parts[2].isBlank() ? null : Integer.parseInt(parts[2].trim());
        return new VerdictEvidence(sourceType, file, line, parts[3].trim());
    }

    private List<VerdictEvidence> truncateEvidence(List<VerdictEvidence> evidence) {
        return evidence.stream()
                .map(item -> new VerdictEvidence(
                        item.sourceType(),
                        item.file(),
                        item.line(),
                        truncate(item.message(), MAX_EVIDENCE_MESSAGE_CHARS)))
                .toList();
    }

    private StructuredVerdict inconclusive(String reason) {
        return StructuredVerdict.inconclusive(truncate(reason, MAX_ERROR_CHARS));
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars) + "...[truncated]";
    }

    private String textField(JsonNode node, String name) {
        if (node == null || !node.has(name) || node.get(name).isNull()) {
            return null;
        }
        return node.get(name).asText();
    }

    private Double doubleField(JsonNode node, String name) {
        if (node == null || !node.has(name) || !node.get(name).isNumber()) {
            return null;
        }
        return node.get(name).asDouble();
    }

    private <T extends Enum<T>> T enumField(JsonNode node, String name, Class<T> enumType) {
        if (node == null || !node.has(name) || node.get(name).isNull()) {
            return null;
        }
        return enumValue(node.get(name).asText(), enumType);
    }

    private <T extends Enum<T>> T enumValue(String value, Class<T> enumType) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Enum.valueOf(enumType, value.trim().toUpperCase(Locale.ROOT));
    }
}
