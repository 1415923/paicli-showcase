package com.paicli.mcp.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.config.Config;
import com.paicli.mcp.R3TestSupport;
import com.paicli.mcp.approval.McpApprovalChannel;
import com.paicli.safety.ApprovalPolicy;
import com.paicli.session.MarkdownReport;
import com.paicli.state.StateStore;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class McpClientRuntimeReleaseIT {
    @TempDir Path temp;
    private final ObjectMapper json = new ObjectMapper();

    @Test void productionPathStartsFreezesApprovesCallsReportsAndCloses() throws Exception {
        try (StateStore state = new StateStore(temp); McpClientRuntime runtime = new McpClientRuntime(state)) {
            var config = R3TestSupport.config(temp, "PAGINATED_RELEASE_SMOKE", List.of("echo", "sum"));
            var started = runtime.startTrustedSession(config, "release-smoke", "smoke", 1, System.getenv());
            assertEquals("FROZEN", state.mcpToolCatalogs.findById(started.catalogId()).orElseThrow().status());
            assertEquals(2, state.mcpTools.findExposedToolsByCatalog(started.catalogId()).size());

            ToolRegistry parent = new ToolRegistry();
            ToolRegistry derived = runtime.deriveToolRegistry(started.sessionId(), parent,
                    policy(Config.MODE_ON_REQUEST), request -> McpApprovalChannel.Decision.APPROVE, "smoke");
            assertEquals(0, parent.size());
            assertTrue(derived.lookup(com.paicli.mcp.tool.McpToolNames.alias("fake","echo")).isPresent());
            assertTrue(derived.lookup(com.paicli.mcp.tool.McpToolNames.alias("fake","sum")).isPresent());

            var result = runtime.executeApprovedTool(started.sessionId(), com.paicli.mcp.tool.McpToolNames.alias("fake","echo"),
                    json.readTree("{\"value\":\"hello\"}"), policy(Config.MODE_ON_REQUEST),
                    request -> McpApprovalChannel.Decision.APPROVE, "smoke-call", 1);
            assertEquals("SUCCEEDED", result.status());
            assertTrue(result.output().content().startsWith("[UNTRUSTED MCP TOOL OUTPUT"));

            runtime.executeApprovedTool(started.sessionId(), com.paicli.mcp.tool.McpToolNames.alias("fake","sum"),
                    json.readTree("{\"value\":\"2\"}"), policy(Config.MODE_ON_REQUEST),
                    request -> McpApprovalChannel.Decision.APPROVE, "smoke-call-2", 1);
            var summary = runtime.summarizeSession(started.sessionId(), 1);
            assertEquals("LIVE", summary.session().liveStatus());
            assertTrue(summary.catalog().truncated());
            assertTrue(summary.callsTruncated());
            assertEquals("SUCCEEDED", summary.calls().getFirst().status());

            MarkdownReport report = new MarkdownReport(temp.resolve("reports"), "mcp-smoke");
            report.write("hidden", Instant.now(), Instant.now(), 0, List.of(), "hidden", true,
                    new MarkdownReport.RuntimeSummary(List.of(), List.of(), 0,
                            MarkdownReport.SubagentReportSummary.empty(),
                            MarkdownReport.WorkerTeamReportSummary.empty(), summary));
            String markdown = Files.readString(report.getReportFile());
            assertTrue(markdown.contains("## MCP Client"));
            assertTrue(markdown.contains("persisted READY is not a live connection"));
            assertFalse(markdown.contains(config.executable().toString()));
            assertFalse(markdown.contains("hello"));
            assertTrue(runtime.closeSession(started.sessionId()));
            assertEquals(0, runtime.liveSessionCount());
        }
    }

    @Test void deniedAndInvalidCallsNeverReachExternalCall() throws Exception {
        Path deniedRoot = temp.resolve("denied");
        try (StateStore state = new StateStore(deniedRoot); McpClientRuntime runtime = new McpClientRuntime(state)) {
            var started = runtime.startTrustedSession(R3TestSupport.config(deniedRoot, "RECEIVE_PROBE", List.of("echo")),
                    "release-smoke", "denied", 1, System.getenv());
            assertThrows(Exception.class, () -> runtime.executeApprovedTool(started.sessionId(), com.paicli.mcp.tool.McpToolNames.alias("fake","echo"),
                    json.readTree("{}"), policy(Config.MODE_READ_ONLY),
                    request -> McpApprovalChannel.Decision.APPROVE, "denied-call", 1));
            assertFalse(Files.exists(deniedRoot.resolve("tools-call-received")));
            assertThrows(Exception.class, () -> runtime.executeApprovedTool(started.sessionId(), com.paicli.mcp.tool.McpToolNames.alias("fake","echo"),
                    json.readTree("[]"), policy(Config.MODE_ON_REQUEST),
                    request -> McpApprovalChannel.Decision.APPROVE, "invalid-call", 1));
            assertFalse(Files.exists(deniedRoot.resolve("tools-call-received")));
        }
    }

    private ApprovalPolicy policy(String mode) {
        return new ApprovalPolicy(new Config(".", 30, 50000, 200000, mode, 10000), "mcp-smoke");
    }
}
