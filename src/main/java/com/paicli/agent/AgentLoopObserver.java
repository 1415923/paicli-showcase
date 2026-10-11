package com.paicli.agent;

import com.paicli.safety.ApprovalDecision;

import java.util.Map;

/**
 * Optional observation seam for persisted AgentLoop facts.
 * Implementations receive bounded facts, never raw tool arguments or output.
 */
public interface AgentLoopObserver {
    AgentLoopObserver NOOP = new AgentLoopObserver() { };

    default void toolRequested(String taskId, int iteration, ToolRequestFact fact) { }

    default void toolBatchStarted(String taskId,int iteration,ToolBatchFact fact) { }

    default void toolDecision(String taskId, int iteration, ToolRequestFact fact,
                              ApprovalDecision decision) { }

    default void toolCompleted(String taskId, int iteration, ToolRequestFact request,
                               ToolCompletionFact result) { }

    default void toolBatchCompleted(String taskId,int iteration,ToolBatchFact fact,
            int succeeded,int failed) { }

    default void toolCancelled(String taskId,int iteration,ToolRequestFact request,
            String reason) { }

    default void effectfulBatchNormalized(String taskId, int iteration,
            EffectfulBatchFact fact) { }

    default void modelMessage(String taskId, int iteration, String kind,
            String boundedContent, AgentSegmentRequest.PlanExecutionContext planExecution) { }

    default boolean failOnApprovalRequired() { return false; }

    record ToolRequestFact(String toolName, String boundedArgumentsSummary,
                           String relativePath, String toolCallId, String effectClass,
                           AgentSegmentRequest.PlanExecutionContext planExecution,
                           int sameRequestOrdinal,int batchIndex,int batchSize) {
        public ToolRequestFact(String toolName,String boundedArgumentsSummary,
                String relativePath,String toolCallId,String effectClass,
                AgentSegmentRequest.PlanExecutionContext planExecution,int sameRequestOrdinal){
            this(toolName,boundedArgumentsSummary,relativePath,toolCallId,effectClass,
                    planExecution,sameRequestOrdinal,1,1);
        }
        public ToolRequestFact(String toolName,String boundedArgumentsSummary,String relativePath) {
            this(toolName,boundedArgumentsSummary,relativePath,null,"unknown",null,1,1,1);
        }
        public ToolRequestFact {
            metadataText(toolName, "toolName", 128);
            boundedArgumentsSummary = optionalText(
                    boundedArgumentsSummary, "boundedArgumentsSummary", 2048);
            relativePath = optionalText(relativePath, "relativePath", 512);
            toolCallId = optionalText(toolCallId,"toolCallId",256);
            effectClass = optionalText(effectClass,"effectClass",64);
            if (sameRequestOrdinal < 1) throw new IllegalArgumentException(
                    "sameRequestOrdinal must be positive");
            if(batchIndex<1||batchSize<1||batchIndex>batchSize||batchSize>8)
                throw new IllegalArgumentException("invalid Tool batch position");
        }
    }

    record ToolBatchFact(String batchId,int size,String effectClass,
            AgentSegmentRequest.PlanExecutionContext planExecution){
        public ToolBatchFact{
            metadataText(batchId,"batchId",128);
            if(size<2||size>8)throw new IllegalArgumentException("invalid batch size");
            metadataText(effectClass,"effectClass",64);
        }
    }

    /** Shared Provider/Tool compatibility fact. It is not a PaiCLI repair metric. */
    record EffectfulBatchFact(boolean effectfulBatchReceived, int toolCallCount,
            boolean normalizationApplied, NormalizationOutcome normalizationOutcome) {
        public enum NormalizationOutcome {
            SERIALIZATION_CONTINUATION_ISSUED,
            TYPED_PROTOCOL_FAILURE
        }

        public EffectfulBatchFact {
            if (!effectfulBatchReceived || toolCallCount < 2 || toolCallCount > 8
                    || !normalizationApplied || normalizationOutcome == null) {
                throw new IllegalArgumentException("invalid effectful batch telemetry");
            }
        }
    }

    record ToolCompletionFact(boolean success, String boundedError,
                              Map<String, Object> metadata, String boundedOutput,
                              long elapsedMillis, int itemCount, boolean truncated) {
        public ToolCompletionFact(boolean success,String boundedError,
                Map<String,Object> metadata) {
            this(success,boundedError,metadata,null,0,-1,false);
        }
        public ToolCompletionFact {
            boundedError = optionalText(boundedError, "boundedError", 2048);
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            if (metadata.size() > 16) {
                throw new IllegalArgumentException("metadata has too many entries");
            }
            boundedOutput = optionalText(boundedOutput,"boundedOutput",4096);
            if(elapsedMillis<0 || itemCount < -1)
                throw new IllegalArgumentException("invalid completion measurements");
        }
    }

    private static String metadataText(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    private static String optionalText(String value, String field, int max) {
        if (value == null) return null;
        if (value.length() > max) throw new IllegalArgumentException(field + " is too long");
        return value;
    }
}
