package com.paicli.model;

/**
 * A single message in the agent conversation.
 *
 * @param role         who produced this message
 * @param content      textual content of the message
 * @param type         semantic type for the agent loop
 * @param toolCallId   optional tool call ID (for real LLM providers, used in ACTION/OBSERVATION)
 * @param toolCallJson optional raw tool call JSON (for real LLM providers, used in ACTION)
 */
public record AgentMessage(Role role, String content, MessageType type,
                           String toolCallId, String toolCallJson) {

    /** Who produced the message. */
    public enum Role {
        USER,
        ASSISTANT
    }

    /** Semantic type used by the agent loop to decide next action. */
    public enum MessageType {
        USER, RUNTIME_STEP_PROMPT, RUNTIME_FINAL_PROMPT, ACTION, OBSERVATION, FINAL
    }

    // ── v0.1/v0.2 compatible constructor (no tool call metadata) ──

    public AgentMessage(Role role, String content, MessageType type) {
        this(role, content, type, null, null);
    }

    // ── factory helpers (v0.1/v0.2 compatible — no tool call metadata) ──

    public static AgentMessage userGoal(String goal) {
        return new AgentMessage(Role.USER, goal, MessageType.USER);
    }

    /** Durable runtime-authored prompt; Provider projection remains a user message. */
    public static AgentMessage runtimeStepPrompt(String prompt) {
        return new AgentMessage(Role.USER, prompt, MessageType.RUNTIME_STEP_PROMPT);
    }

    /** Durable final-synthesis prompt; Provider projection remains a user message. */
    public static AgentMessage runtimeFinalPrompt(String prompt) {
        return new AgentMessage(Role.USER, prompt, MessageType.RUNTIME_FINAL_PROMPT);
    }

    public boolean runtimePrompt() {
        return type == MessageType.RUNTIME_STEP_PROMPT
                || type == MessageType.RUNTIME_FINAL_PROMPT;
    }

    /** Normalize internal provenance for adapters whose wire contract only has role=user. */
    public AgentMessage providerProjection() {
        return runtimePrompt()
                ? new AgentMessage(Role.USER, content, MessageType.USER, toolCallId, toolCallJson)
                : this;
    }

    public static AgentMessage action(String content) {
        return new AgentMessage(Role.ASSISTANT, content, MessageType.ACTION);
    }

    public static AgentMessage observation(String content) {
        return new AgentMessage(Role.ASSISTANT, content, MessageType.OBSERVATION);
    }

    public static AgentMessage finalAnswer(String content) {
        return new AgentMessage(Role.ASSISTANT, content, MessageType.FINAL);
    }

    // ── v0.3.0 factory helpers with tool call metadata ──

    /** Create an ACTION message with tool call metadata for real LLM providers. */
    public static AgentMessage actionWithToolCall(String content, String toolCallId, String toolCallJson) {
        return new AgentMessage(Role.ASSISTANT, content, MessageType.ACTION, toolCallId, toolCallJson);
    }

    /** Create an OBSERVATION message with tool call ID for real LLM providers. */
    public static AgentMessage observationForToolCall(String content, String toolCallId) {
        return new AgentMessage(Role.ASSISTANT, content, MessageType.OBSERVATION, toolCallId, null);
    }

    public boolean hasToolCallMetadata() {
        return toolCallId != null;
    }
}
