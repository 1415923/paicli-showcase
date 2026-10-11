package com.paicli.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.model.AgentMessage;
import com.paicli.tool.ToolResult;

import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Canonical, safe Tool-fact projection shared by Provider continuation, durable observations,
 * and final-response validation.
 */
public final class ToolFactProjection {
    public static final String VISIBLE_ENTRY_COUNT = "visibleEntryCount";
    public static final String EXCLUDED_CONTROL_COUNT = "excludedControlCount";
    public static final String EXCLUDED_CONTROLS = "excludedControls";
    public static final String CONTROL_DIRECTORY_POLICY_APPLIED =
            "controlDirectoryPolicyApplied";
    public static final String CANONICAL_FACT_ID = "canonicalFactId";
    public static final String CANONICAL_FACT_PAYLOAD_DIGEST =
            "canonicalFactPayloadDigest";
    public static final String CANONICAL_FACT_TOOL_CALL_ID_HASH =
            "canonicalFactToolCallIdHash";
    public static final String CANONICAL_FACT_TRUST_CLASS = "canonicalFactTrustClass";
    private static final String MARKER = "[PAICLI_TOOL_FACTS] ";
    private static final String TRUST_CLASS = "RUNTIME_VERIFIED";
    private static final String CONTROL_DIRECTORY = ".paicli/";
    private static final int MAX_VISIBLE_ENTRIES = 256;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolFactProjection() { }

    /** Bind a runtime-produced Tool result to its provider Tool-call identity. */
    public static ToolResult bind(ToolResult result, String toolCallId, String toolName) {
        if (result == null) throw new IllegalArgumentException("Tool result is required");
        if (!"list_files".equals(toolName)) return result;
        ControlFacts facts = fromResult(result);
        if (facts == null) return result;
        if (toolCallId == null || toolCallId.isBlank()) {
            throw new FactIntegrityFailure("CONTEXT_SOURCE_ID_MISSING");
        }
        String payloadDigest = digest(encode(facts));
        String toolCallIdHash = digest(toolCallId);
        String factId = factId(toolCallIdHash, payloadDigest);
        Map<String, Object> metadata = new LinkedHashMap<>(result.metadata());
        metadata.put(CANONICAL_FACT_ID, factId);
        metadata.put(CANONICAL_FACT_PAYLOAD_DIGEST, payloadDigest);
        metadata.put(CANONICAL_FACT_TOOL_CALL_ID_HASH, toolCallIdHash);
        metadata.put(CANONICAL_FACT_TRUST_CLASS, TRUST_CLASS);
        return new ToolResult(result.success(), result.output(), result.error(), metadata);
    }

    public static String appendCanonicalFacts(String observation, ToolResult result) {
        BoundFact fact = fromBoundResult(result);
        String base = observation == null ? "" : observation;
        if (fact == null) return base;
        String encoded = encodeEnvelope(fact);
        if (base.isEmpty()) return MARKER + encoded;
        return base + (base.endsWith("\n") ? "" : "\n") + MARKER + encoded;
    }

    /**
     * Produces a user-safe replay projection only after both durable copies of the private
     * envelope authenticate against the same Tool-call identity. Marker-shaped Tool output is
     * never interpreted or removed unless a valid terminal envelope follows it.
     */
    public static SafeReplayProjection authenticatedReplayProjection(String observation,
            String toolCallId, String toolName, String durableResultOutput) {
        if (!"list_files".equals(toolName)) return null;
        String eventEnvelope = terminalEnvelope(observation);
        String durableEnvelope = terminalEnvelope(durableResultOutput);
        BoundFact eventFact = fromObservation(observation, toolCallId);
        BoundFact durableFact = fromObservation(durableResultOutput, toolCallId);
        if (eventEnvelope == null && durableEnvelope == null) return null;
        if (eventFact == null || durableFact == null || !eventFact.equals(durableFact)
                || !eventEnvelope.equals(durableEnvelope)) {
            throw new FactIntegrityFailure("CONTEXT_SOURCE_PAYLOAD_MISMATCH");
        }
        int marker = durableResultOutput.lastIndexOf(MARKER);
        String visible = durableResultOutput.substring(0, marker);
        if (visible.endsWith("\n")) visible = visible.substring(0, visible.length() - 1);
        return new SafeReplayProjection(visible, durableFact.facts());
    }

    public static ControlFacts fromConversation(List<AgentMessage> messages) {
        Map<String, String> toolNames = authenticatedToolNames(messages);
        Set<String> visibleEntries = new LinkedHashSet<>();
        int visibleCount = 0;
        int excluded = 0;
        boolean policyApplied = false;
        Set<String> controls = new LinkedHashSet<>();
        Set<String> factIds = new LinkedHashSet<>();
        for (AgentMessage message : messages == null ? List.<AgentMessage>of() : messages) {
            if (message.type() != AgentMessage.MessageType.OBSERVATION) continue;
            if (!"list_files".equals(toolNames.get(message.toolCallId()))) continue;
            BoundFact fact = fromObservation(message.content(), message.toolCallId());
            if (fact == null) continue;
            if (!factIds.add(fact.factId())) {
                throw new FactIntegrityFailure("CONTEXT_SOURCE_ID_DUPLICATE");
            }
            ControlFacts candidate = fact.facts();
            visibleCount = Math.max(visibleCount, candidate.visibleEntryCount());
            visibleEntries.addAll(candidate.visibleEntries());
            excluded = Math.max(excluded, candidate.excludedControlCount());
            policyApplied |= candidate.controlDirectoryPolicyApplied();
            controls.addAll(candidate.excludedControls());
        }
        return policyApplied || excluded > 0
                ? new ControlFacts(List.copyOf(visibleEntries), visibleCount, excluded,
                        List.copyOf(controls), policyApplied)
                : null;
    }

    private static Map<String, String> authenticatedToolNames(List<AgentMessage> messages) {
        Map<String, String> names = new LinkedHashMap<>();
        for (AgentMessage message : messages == null ? List.<AgentMessage>of() : messages) {
            if (message.type() != AgentMessage.MessageType.ACTION
                    || message.toolCallId() == null || message.toolCallJson() == null) continue;
            try {
                JsonNode call = MAPPER.readTree(message.toolCallJson());
                String name = call.path("function").path("name").asText(null);
                if (name != null && !name.isBlank()) names.put(message.toolCallId(), name);
            } catch (java.io.IOException ignored) {
                // Malformed user/provider text is not authenticated Tool identity.
            }
        }
        return Map.copyOf(names);
    }

    public static String finalSynthesisInstruction(List<AgentMessage> messages, String goal) {
        ControlFacts facts = fromConversation(messages);
        if (facts == null || facts.excludedControlCount() == 0) return "";
        return "\nCanonical safe Tool facts: " + String.join(", ", facts.excludedControls())
                + " existed in the workspace but was excluded by the internal-control-directory "
                + "policy. Describe it as present-and-excluded, never as absent. Do not expose "
                + "any internal file names or contents.\n";
    }

    public static void validateFinalResponse(String content, String goal,
            List<AgentMessage> messages) {
        ControlFacts facts = fromConversation(messages);
        if (facts == null || facts.excludedControlCount() == 0) return;
        String normalized = normalize(content);
        for (String control : facts.excludedControls()) {
            String name = normalize(control.replace("/", ""));
            if (contradictsPresence(normalized, name)) {
                throw new IllegalStateException("FINAL_RESPONSE_TOOL_FACT_CONTRADICTION");
            }
            if (normalize(goal).contains(name)
                    && (!normalized.contains(name) || !describesExclusion(normalized))) {
                throw new IllegalStateException("FINAL_RESPONSE_EXCLUSION_FACT_OMITTED");
            }
        }
    }

    private static ControlFacts fromResult(ToolResult result) {
        Map<String, Object> metadata = result.metadata();
        if (!(metadata.get(CONTROL_DIRECTORY_POLICY_APPLIED) instanceof Boolean applied)) {
            return null;
        }
        List<String> visible = result.output() == null ? List.of() : result.output().lines()
                .map(ToolFactProjection::safeVisibleEntry).filter(java.util.Objects::nonNull)
                .limit(MAX_VISIBLE_ENTRIES).toList();
        int visibleCount = nonNegativeInt(metadata.get(VISIBLE_ENTRY_COUNT));
        int excluded = nonNegativeInt(metadata.get(EXCLUDED_CONTROL_COUNT));
        List<String> controls = new ArrayList<>();
        Object value = metadata.get(EXCLUDED_CONTROLS);
        if (value instanceof String text) {
            for (String item : text.split(",")) {
                String control = safeControl(item);
                if (control != null) controls.add(control);
            }
        }
        return new ControlFacts(visible, visibleCount, excluded,
                List.copyOf(controls), applied);
    }

    private static String encode(ControlFacts facts) {
        try {
            return MAPPER.writeValueAsString(encodedNode(facts));
        } catch (java.io.IOException error) {
            throw new IllegalStateException("TOOL_FACT_PROJECTION_FAILED", error);
        }
    }

    /** Parse only the terminal runtime envelope; Tool-controlled marker text is never authority. */
    public static BoundFact fromObservation(String observation, String toolCallId) {
        if (observation == null) return null;
        int marker = observation.lastIndexOf(MARKER);
        if (marker < 0) return null;
        String encoded = observation.substring(marker + MARKER.length()).trim();
        try {
            JsonNode root = MAPPER.readTree(encoded);
            if (root == null || !root.isObject() || root.size() != 5
                    || !encoded.equals(MAPPER.writeValueAsString(root))) return null;
            String trust = root.path("trustClass").asText(null);
            if (!TRUST_CLASS.equals(trust)) {
                throw new FactIntegrityFailure("CONTEXT_SOURCE_TRUST_CLASS_MISMATCH");
            }
            if (toolCallId == null || toolCallId.isBlank()) {
                throw new FactIntegrityFailure("CONTEXT_SOURCE_ID_MISSING");
            }
            ControlFacts facts = decodePayload(root.path("payload"));
            if (facts == null) return null;
            String payloadDigest = digest(encode(facts));
            if (!payloadDigest.equals(root.path("payloadDigest").asText())) {
                throw new FactIntegrityFailure("CONTEXT_SOURCE_PAYLOAD_MISMATCH");
            }
            String toolCallIdHash = digest(toolCallId);
            if (!toolCallIdHash.equals(root.path("toolCallIdHash").asText())) {
                throw new FactIntegrityFailure("CONTEXT_SOURCE_TOOL_CALL_BINDING_MISMATCH");
            }
            String factId = factId(toolCallIdHash, payloadDigest);
            if (!factId.equals(root.path("factId").asText())) {
                throw new FactIntegrityFailure("CONTEXT_SOURCE_ID_MISMATCH");
            }
            return new BoundFact(factId, payloadDigest, toolCallIdHash, trust, facts);
        } catch (FactIntegrityFailure failure) {
            throw failure;
        } catch (RuntimeException | java.io.IOException ignored) {
            return null;
        }
    }

    private static BoundFact fromBoundResult(ToolResult result) {
        ControlFacts facts = fromResult(result);
        if (facts == null) return null;
        String factId = stringMetadata(result, CANONICAL_FACT_ID);
        String payloadDigest = stringMetadata(result, CANONICAL_FACT_PAYLOAD_DIGEST);
        String toolCallIdHash = stringMetadata(result, CANONICAL_FACT_TOOL_CALL_ID_HASH);
        String trust = stringMetadata(result, CANONICAL_FACT_TRUST_CLASS);
        if (factId == null && payloadDigest == null && toolCallIdHash == null && trust == null) {
            return null;
        }
        if (!TRUST_CLASS.equals(trust)) {
            throw new FactIntegrityFailure("CONTEXT_SOURCE_TRUST_CLASS_MISMATCH");
        }
        String expectedDigest = digest(encode(facts));
        if (!expectedDigest.equals(payloadDigest)) {
            throw new FactIntegrityFailure("CONTEXT_SOURCE_PAYLOAD_MISMATCH");
        }
        if (toolCallIdHash == null || !factId(toolCallIdHash, payloadDigest).equals(factId)) {
            throw new FactIntegrityFailure("CONTEXT_SOURCE_ID_MISMATCH");
        }
        return new BoundFact(factId, payloadDigest, toolCallIdHash, trust, facts);
    }

    private static String encodeEnvelope(BoundFact fact) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("factId", fact.factId());
            root.put("payloadDigest", fact.payloadDigest());
            root.put("toolCallIdHash", fact.toolCallIdHash());
            root.put("trustClass", fact.trustClass());
            root.set("payload", encodedNode(fact.facts()));
            return MAPPER.writeValueAsString(root);
        } catch (java.io.IOException error) {
            throw new IllegalStateException("TOOL_FACT_PROJECTION_FAILED", error);
        }
    }

    private static ControlFacts decodePayload(JsonNode root) {
        if (!root.isObject() || root.size() != 6
                || !"list_files".equals(root.path("kind").asText())) return null;
        int visibleCount = Math.max(0, root.path("visibleEntryCount").asInt(0));
        Set<String> visibleEntries = new LinkedHashSet<>();
        JsonNode visible = root.path("visibleEntries");
        if (!visible.isArray()) return null;
        for (JsonNode value : visible) {
            String entry = safeVisibleEntry(value.asText(null));
            if (entry == null || visibleEntries.size() >= MAX_VISIBLE_ENTRIES) return null;
            visibleEntries.add(entry);
        }
        int excluded = Math.max(0, root.path("excludedControlCount").asInt(0));
        boolean applied = root.path("controlDirectoryPolicyApplied").asBoolean(false);
        Set<String> controls = new LinkedHashSet<>();
        JsonNode controlValues = root.path("excludedControls");
        if (!controlValues.isArray()) return null;
        for (JsonNode value : controlValues) {
            String control = safeControl(value.asText(null));
            if (control == null) return null;
            controls.add(control);
        }
        if (excluded != controls.size() || (excluded > 0
                && !controls.contains(CONTROL_DIRECTORY))) return null;
        ControlFacts facts = new ControlFacts(List.copyOf(visibleEntries), visibleCount,
                excluded, List.copyOf(controls), applied);
        return encodedNode(facts).equals(root) ? facts : null;
    }

    private static String stringMetadata(ToolResult result, String key) {
        Object value = result.metadata().get(key);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static String factId(String toolCallIdHash, String payloadDigest) {
        return "fact_" + digest(TRUST_CLASS + "|" + toolCallIdHash + "|" + payloadDigest);
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static ObjectNode encodedNode(ControlFacts facts) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("kind", "list_files");
        root.put("visibleEntryCount", facts.visibleEntryCount());
        ArrayNode visible = root.putArray("visibleEntries");
        facts.visibleEntries().forEach(visible::add);
        root.put("excludedControlCount", facts.excludedControlCount());
        ArrayNode controls = root.putArray("excludedControls");
        facts.excludedControls().forEach(controls::add);
        root.put("controlDirectoryPolicyApplied",
                facts.controlDirectoryPolicyApplied());
        return root;
    }

    private static int nonNegativeInt(Object value) {
        return value instanceof Number number ? Math.max(0, number.intValue()) : 0;
    }

    private static String safeControl(String value) {
        if (value == null) return null;
        String clean = value.trim().replace('\\', '/');
        if (".paicli".equals(clean)) clean = CONTROL_DIRECTORY;
        return CONTROL_DIRECTORY.equals(clean) ? clean : null;
    }

    private static String safeVisibleEntry(String value) {
        if (value == null) return null;
        String clean = value.trim().replace('\\', '/');
        if (clean.isEmpty() || clean.startsWith("[") || clean.length() > 512
                || clean.startsWith("/") || clean.matches("[A-Za-z]:/.*")
                || clean.contains("../") || clean.equals("..")) return null;
        return clean;
    }

    private static String terminalEnvelope(String value) {
        if (value == null) return null;
        int marker = value.lastIndexOf(MARKER);
        return marker < 0 ? null : value.substring(marker + MARKER.length()).trim();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replace('’', '\'').replaceAll("\\s+", " ");
    }

    private static boolean contradictsPresence(String content, String control) {
        if (content.contains("no internal control directory exists")
                || content.contains("no internal control directories exist")
                || content.contains("did not find any internal control directory")
                || content.contains("did not find any internal control directories")
                || content.contains("没有内部控制目录")
                || content.contains("不存在内部控制目录")
                || content.contains("未发现内部控制目录")
                || content.contains("未找到内部控制目录")) return true;
        for(String clause:content.split("[\\n!?;。！？；]|\\.(?:\\s|$)")){
            if(!clause.contains(control))continue;
            if(clause.contains(control + " does not exist")
                    || clause.contains(control + " doesn't exist")
                    || clause.contains(control + " is absent")
                    || clause.contains(control + " was not found")
                    || clause.contains(control + " wasn't found")
                    || clause.contains(control + " not found")
                    || clause.contains(control + " does not appear")
                    || clause.contains(control + " doesn't appear")
                    || clause.contains(control + " 未发现")
                    || clause.contains(control + " 未找到")
                    || clause.contains("no " + control)
                    || (clause.contains("not found") && clause.indexOf("not found")
                            < clause.indexOf(control))
                    || (clause.contains("does not appear") && clause.indexOf("does not appear")
                            < clause.indexOf(control))
                    || (clause.contains("未发现") && clause.indexOf("未发现")
                            < clause.indexOf(control))
                    || (clause.contains("未找到") && clause.indexOf("未找到")
                            < clause.indexOf(control))
                    || clause.contains(control + " 不存在")
                    || (clause.contains("不存在") && clause.indexOf("不存在")
                            < clause.indexOf(control)))return true;
        }
        return false;
    }

    private static boolean describesExclusion(String content) {
        return List.of("excluded", "ignored", "filtered", "omitted",
                        "排除", "忽略", "未纳入", "不纳入")
                .stream().anyMatch(content::contains);
    }

    public record ControlFacts(List<String> visibleEntries, int visibleEntryCount,
            int excludedControlCount, List<String> excludedControls,
            boolean controlDirectoryPolicyApplied) {
        public ControlFacts {
            visibleEntries = List.copyOf(visibleEntries);
            excludedControls = List.copyOf(excludedControls);
        }
    }

    public record BoundFact(String factId, String payloadDigest, String toolCallIdHash,
            String trustClass, ControlFacts facts) { }

    public record SafeReplayProjection(String visibleContent, ControlFacts facts) { }

    public static final class FactIntegrityFailure extends IllegalStateException {
        public FactIntegrityFailure(String code) { super(code); }
    }
}
