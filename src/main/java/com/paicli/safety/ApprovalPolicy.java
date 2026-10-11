package com.paicli.safety;

import com.paicli.config.Config;
import com.paicli.safety.ApprovalDecision.RiskLevel;

import java.util.Map;

/**
 * Makes tool approval decisions based on the configured approval mode and tool risk level.
 * <p>
 * Risk levels:
 * <ul>
 *   <li>LOW: list_files, read_file, grep, hardened git_diff, mailbox_post</li>
 *   <li>MEDIUM: write_file (non-overwrite)</li>
 *   <li>HIGH: run_command, write_file with overwrite=true</li>
 * </ul>
 * <p>
 * Decision matrix:
 * <pre>
 *                | LOW     | MEDIUM           | HIGH
 * read-only      | ALLOW   | DENY             | DENY
 * workspace-write| ALLOW   | ALLOW            | DENY
 * on-request     | ALLOW   | ALLOW            | NEEDS_APPROVAL
 * deny           | DENY    | DENY             | DENY
 * </pre>
 * <p>
 * v0.2.0: NEEDS_APPROVAL is reported as a structured decision.
 * Real interactive approval is deferred to a future version.
 */
public class ApprovalPolicy {

    private static final Map<String, RiskLevel> TOOL_RISK_LEVELS = Map.of(
            "list_files", RiskLevel.LOW,
            "read_file", RiskLevel.LOW,
            "grep", RiskLevel.LOW,
            "git_diff", RiskLevel.LOW,
            "mailbox_post", RiskLevel.LOW,
            "load_skill", RiskLevel.LOW,
            "read_skill_resource", RiskLevel.LOW
    );

    private final java.util.function.Supplier<String> approvalMode;
    private final String taskId;

    public ApprovalPolicy(Config config, String taskId) {
        this(() -> config.approvalMode(), taskId);
    }

    public ApprovalPolicy(java.util.function.Supplier<String> approvalMode, String taskId) {
        this.approvalMode = java.util.Objects.requireNonNull(approvalMode, "approvalMode");
        this.taskId = taskId;
    }

    /**
     * Evaluate a tool call for approval.
     *
     * @param toolName  name of the tool
     * @param overwrite whether write_file is in overwrite mode (only relevant for write_file)
     * @return the approval decision
     */
    public ApprovalDecision evaluate(String toolName, boolean overwrite) {
        RiskLevel risk = classifyRisk(toolName, overwrite);
        String mode = currentApprovalMode();

        return switch (mode) {
            case Config.MODE_PLAN, Config.MODE_READ_ONLY -> switch (risk) {
                case LOW -> ApprovalDecision.allow(toolName, risk, taskId, "read-only mode: low risk allowed");
                case MEDIUM, HIGH -> ApprovalDecision.deny(toolName, risk, taskId,
                        mode + " mode: " + risk + " risk denied");
            };
            case Config.MODE_WORKSPACE_WRITE -> switch (risk) {
                case LOW, MEDIUM -> ApprovalDecision.allow(toolName, risk, taskId,
                        "workspace-write mode: " + risk + " risk allowed");
                case HIGH -> ApprovalDecision.deny(toolName, risk, taskId,
                        "workspace-write mode: high risk denied");
            };
            case Config.MODE_ON_REQUEST -> switch (risk) {
                case LOW, MEDIUM -> ApprovalDecision.allow(toolName, risk, taskId,
                        "on-request mode: " + risk + " risk allowed");
                case HIGH -> ApprovalDecision.needsApproval(toolName, risk, taskId,
                        "on-request mode: high risk needs approval");
            };
            case Config.MODE_DENY -> ApprovalDecision.deny(toolName, risk, taskId,
                    "deny mode: all operations denied");
            default -> ApprovalDecision.deny(toolName, risk, taskId,
                    "unknown approval mode: " + mode);
        };
    }

    /**
     * Classify the risk level of a tool call.
     */
    public RiskLevel classifyRisk(String toolName, boolean overwrite) {
        // write_file with overwrite is HIGH
        if ("write_file".equals(toolName) && overwrite) {
            return RiskLevel.HIGH;
        }
        // write_file without overwrite is MEDIUM
        if ("write_file".equals(toolName)) {
            return RiskLevel.MEDIUM;
        }
        // Arbitrary shell commands are always HIGH.
        if ("run_command".equals(toolName)) {
            return RiskLevel.HIGH;
        }
        // Known low-risk tools
        RiskLevel known = TOOL_RISK_LEVELS.get(toolName);
        if (known != null) {
            return known;
        }
        // Unknown tools default to HIGH (fail-closed)
        return RiskLevel.HIGH;
    }

    public String getApprovalMode() {
        return currentApprovalMode();
    }

    private String currentApprovalMode() {
        String value = approvalMode.get();
        return value == null ? Config.MODE_DENY : value;
    }
}
