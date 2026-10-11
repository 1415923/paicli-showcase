package com.paicli.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Provider-neutral compatibility policy shared by every AgentLoop composition. */
public final class SharedToolBatchPolicy {
    public static final String VERSION = "shared-effectful-batch-serialization-v2";
    public static final String TELEMETRY_VERSION = "shared-effectful-batch-telemetry-v1";
    /** Discovery guidance only; enforcement and the historical rejection contract stay unchanged. */
    public static final String SERIAL_TOOL_GUIDANCE =
            "Runtime constraint: submit this tool alone in one assistant tool-call turn. "
                    + "If a batch is rejected as NOT_EXECUTED, none of its calls ran; "
                    + "complete each still-needed call separately before concluding.";
    public static final String NOT_EXECUTED_CODE =
            "EFFECTFUL_BATCH_REQUIRES_SERIALIZATION";
    public static final String REPEATED_BATCH_FAILURE_CODE =
            "EFFECTFUL_BATCH_SERIALIZATION_REPEATED";
    public static final String SERIALIZATION_INSTRUCTION =
            "The whole Tool batch was not executed. Resubmit at most one effectful Tool call "
                    + "in the next assistant turn; do not resend an effectful multi-call batch.";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String POLICY_MATERIAL = String.join("\n",
            VERSION,
            NOT_EXECUTED_CODE,
            REPEATED_BATCH_FAILURE_CODE,
            SERIALIZATION_INSTRUCTION,
            "zero-execution",
            "one-result-per-tool-call-id",
            "no-partial-batch-execution",
            "continuation-bounded-by-agent-loop-iteration-budget");
    private static final String TELEMETRY_MATERIAL = String.join("\n",
            TELEMETRY_VERSION,
            "effectfulBatchReceived:boolean:true",
            "toolCallCount:integer:2..8",
            "normalizationApplied:boolean:true",
            "normalizationOutcome:SERIALIZATION_CONTINUATION_ISSUED|TYPED_PROTOCOL_FAILURE",
            "shared-agent-loop-observer",
            "cross-runtime-comparable",
            "not-structured-repair");

    private SharedToolBatchPolicy() { }

    public static String policyHash() {
        return sha256(POLICY_MATERIAL);
    }

    public static String telemetryHash() {
        return sha256(TELEMETRY_MATERIAL);
    }

    private static String sha256(String material) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    public static String notExecutedResult(int batchIndex, int batchSize) {
        if (batchIndex < 1 || batchIndex > batchSize || batchSize < 2) {
            throw new IllegalArgumentException("invalid Tool batch position");
        }
        ObjectNode value = JSON.createObjectNode();
        value.put("status", "NOT_EXECUTED");
        value.put("code", NOT_EXECUTED_CODE);
        value.put("batchIndex", batchIndex);
        value.put("batchSize", batchSize);
        value.put("instruction", SERIALIZATION_INSTRUCTION);
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("typed Tool non-execution result failed", error);
        }
    }
}
