package com.paicli.mcp.approval;

import com.paicli.safety.ApprovalDecision;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** One-use transfer of an already resolved AgentLoop approval; never a second approval prompt. */
public final class McpApprovalGrant {
    public record Binding(String manifest, String catalog, String schema, String tool, String arguments) { }
    private final Binding binding;
    private final String task, run, occurrence;
    private final AtomicBoolean consumed = new AtomicBoolean();
    private McpApprovalGrant(Binding binding, String task, String run, String occurrence) {
        this.binding = Objects.requireNonNull(binding);
        this.task = required(task); this.run = required(run); this.occurrence = required(occurrence);
    }
    public static McpApprovalGrant fromAgentLoop(Binding beforePrompt, String task, String run,
            String occurrence, ApprovalDecision requested, ApprovalDecision resolved) {
        if (requested == null || resolved == null
                || requested.decision() != ApprovalDecision.Decision.NEEDS_APPROVAL || !resolved.isAllowed()
                || !Objects.equals(task, requested.taskId()) || !Objects.equals(task, resolved.taskId())
                || !Objects.equals(beforePrompt.tool(), requested.toolName())
                || !Objects.equals(beforePrompt.tool(), resolved.toolName())
                || requested.riskLevel() != resolved.riskLevel())
            throw new IllegalStateException("MCP requires an exact explicit AgentLoop approval");
        return new McpApprovalGrant(beforePrompt, task, run, occurrence);
    }
    public boolean consume(Binding current, String task, String run, String occurrence) {
        return binding.equals(current) && this.task.equals(task) && this.run.equals(run)
                && this.occurrence.equals(occurrence) && consumed.compareAndSet(false, true);
    }
    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("MCP execution identity is required");
        return value;
    }
}
