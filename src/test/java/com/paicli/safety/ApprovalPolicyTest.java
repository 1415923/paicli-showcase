package com.paicli.safety;

import com.paicli.config.Config;
import com.paicli.safety.ApprovalDecision.Decision;
import com.paicli.safety.ApprovalDecision.RiskLevel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApprovalPolicyTest {

    private static final String TASK_ID = "test-001";

    private ApprovalPolicy policy(String mode) {
        Config config = new Config(".", 30, 50000, 200000, mode, 10000);
        return new ApprovalPolicy(config, TASK_ID);
    }

    // ── Risk classification ──

    @Test
    void lowRiskTools() {
        var p = policy(Config.MODE_ON_REQUEST);
        assertEquals(RiskLevel.LOW, p.classifyRisk("list_files", false));
        assertEquals(RiskLevel.LOW, p.classifyRisk("read_file", false));
        assertEquals(RiskLevel.LOW, p.classifyRisk("grep", false));
        assertEquals(RiskLevel.LOW, p.classifyRisk("git_diff", false));
    }

    @Test
    void mediumRiskWriteFile() {
        var p = policy(Config.MODE_ON_REQUEST);
        assertEquals(RiskLevel.MEDIUM, p.classifyRisk("write_file", false));
    }

    @Test
    void highRiskWriteFileOverwrite() {
        var p = policy(Config.MODE_ON_REQUEST);
        assertEquals(RiskLevel.HIGH, p.classifyRisk("write_file", true));
    }

    @Test
    void highRiskRunCommand() {
        var p = policy(Config.MODE_ON_REQUEST);
        assertEquals(RiskLevel.HIGH, p.classifyRisk("run_command", false));
    }

    @Test
    void unknownToolDefaultsToHigh() {
        var p = policy(Config.MODE_ON_REQUEST);
        assertEquals(RiskLevel.HIGH, p.classifyRisk("unknown_tool", false));
    }

    // ── read-only mode ──

    @Test
    void readOnlyAllowsLowRisk() {
        var p = policy(Config.MODE_READ_ONLY);
        ApprovalDecision d = p.evaluate("list_files", false);
        assertEquals(Decision.ALLOW, d.decision());
        assertEquals(RiskLevel.LOW, d.riskLevel());
    }

    @Test
    void readOnlyDeniesMediumRisk() {
        var p = policy(Config.MODE_READ_ONLY);
        ApprovalDecision d = p.evaluate("write_file", false);
        assertEquals(Decision.DENY, d.decision());
    }

    @Test
    void readOnlyDeniesHighRisk() {
        var p = policy(Config.MODE_READ_ONLY);
        assertEquals(Decision.DENY, p.evaluate("run_command", false).decision());
        assertEquals(Decision.DENY, p.evaluate("write_file", true).decision());
    }

    // ── workspace-write mode ──

    @Test
    void workspaceWriteAllowsLowAndMedium() {
        var p = policy(Config.MODE_WORKSPACE_WRITE);
        assertEquals(Decision.ALLOW, p.evaluate("list_files", false).decision());
        assertEquals(Decision.ALLOW, p.evaluate("write_file", false).decision());
    }

    @Test
    void workspaceWriteDeniesHigh() {
        var p = policy(Config.MODE_WORKSPACE_WRITE);
        assertEquals(Decision.DENY, p.evaluate("run_command", false).decision());
        assertEquals(Decision.DENY, p.evaluate("write_file", true).decision());
    }

    // ── on-request mode ──

    @Test
    void onRequestAllowsLowAndMedium() {
        var p = policy(Config.MODE_ON_REQUEST);
        assertEquals(Decision.ALLOW, p.evaluate("list_files", false).decision());
        assertEquals(Decision.ALLOW, p.evaluate("write_file", false).decision());
    }

    @Test
    void onRequestNeedsApprovalForHigh() {
        var p = policy(Config.MODE_ON_REQUEST);
        ApprovalDecision d = p.evaluate("run_command", false);
        assertEquals(Decision.NEEDS_APPROVAL, d.decision());
        assertEquals(RiskLevel.HIGH, d.riskLevel());
        assertTrue(d.reason().contains("needs approval"));
    }

    // ── deny mode ──

    @Test
    void denyModeDeniesEverything() {
        var p = policy(Config.MODE_DENY);
        assertEquals(Decision.DENY, p.evaluate("list_files", false).decision());
        assertEquals(Decision.DENY, p.evaluate("write_file", false).decision());
        assertEquals(Decision.DENY, p.evaluate("run_command", false).decision());
    }

    // ── unknown mode ──

    @Test
    void unknownModeDeniesEverything() {
        var p = policy("invalid-mode");
        assertEquals(Decision.DENY, p.evaluate("list_files", false).decision());
    }

    // ── Decision record ──

    @Test
    void decisionContainsMetadata() {
        var p = policy(Config.MODE_ON_REQUEST);
        ApprovalDecision d = p.evaluate("grep", false);

        assertEquals("grep", d.toolName());
        assertEquals(TASK_ID, d.taskId());
        assertNotNull(d.timestamp());
        assertNotNull(d.reason());
    }

    @Test
    void isAllowedHelper() {
        var allow = ApprovalDecision.allow("tool", RiskLevel.LOW, "task", "ok");
        var deny = ApprovalDecision.deny("tool", RiskLevel.HIGH, "task", "no");
        var needs = ApprovalDecision.needsApproval("tool", RiskLevel.HIGH, "task", "ask");

        assertTrue(allow.isAllowed());
        assertFalse(deny.isAllowed());
        assertFalse(needs.isAllowed());
    }
}
