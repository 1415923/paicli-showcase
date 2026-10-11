package com.paicli.interactive.runtime;

import com.paicli.agent.AgentLoop;
import com.paicli.agent.AgentLoopObserver;
import com.paicli.agent.AgentSegmentRequest;
import com.paicli.config.Config;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolCall;
import com.paicli.provider.ModelProvider;
import com.paicli.safety.ApprovalDecision;
import com.paicli.safety.ApprovalPolicy;
import com.paicli.state.StateStore;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ApprovalBoundaryTest {
    @TempDir Path temp;

    @Test void existingModesRemainUnchanged() {
        assertEquals(ApprovalDecision.Decision.ALLOW,
                policy(Config.MODE_READ_ONLY).evaluate("read_file", false).decision());
        assertEquals(ApprovalDecision.Decision.DENY,
                policy(Config.MODE_READ_ONLY).evaluate("write_file", false).decision());
        assertEquals(ApprovalDecision.Decision.ALLOW,
                policy(Config.MODE_WORKSPACE_WRITE).evaluate("write_file", false).decision());
        assertEquals(ApprovalDecision.Decision.NEEDS_APPROVAL,
                policy(Config.MODE_ON_REQUEST).evaluate("run_command", false).decision());
        assertEquals(ApprovalDecision.Decision.DENY,
                policy(Config.MODE_DENY).evaluate("read_file", false).decision());
    }

    @Test void onRequestWithoutChannelRejectsBeforeExecutionAndRedactsArguments()
            throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace);
        String taskId = "approval-task";
        String sentinel = "PAICLI_SECRET_SENTINEL_91";
        try (StateStore state = new StateStore(workspace)) {
            state.tasks.createTask(taskId, "unsafe request", "mock", null,
                    Config.MODE_ON_REQUEST, 2);
            state.tasks.markRunning(taskId);
            ModelProvider provider = (messages, tools) ->
                    ModelResponse.toolCallResponse("run it",
                            new ToolCall("run_command",
                                    Map.of("command", "echo password=" + sentinel)));
            List<AgentLoopObserver.ToolRequestFact> requested = new ArrayList<>();
            List<AgentLoopObserver.ToolRequestFact> decisionFacts = new ArrayList<>();
            List<ApprovalDecision> decisions = new ArrayList<>();
            AgentLoopObserver observer = new AgentLoopObserver() {
                @Override public void toolRequested(String id, int iteration,
                        ToolRequestFact fact) {
                    requested.add(fact);
                }
                @Override public void toolDecision(String id, int iteration,
                        ToolRequestFact fact, ApprovalDecision decision) {
                    decisionFacts.add(fact);
                    decisions.add(decision);
                }
                @Override public boolean failOnApprovalRequired() {
                    return true;
                }
            };
            AgentLoop loop = new AgentLoop(provider, new ToolRegistry(), 2,
                    policy(Config.MODE_ON_REQUEST), null, state, taskId,
                    "mock", null, observer);

            assertThrows(AgentLoop.ApprovalChannelUnavailableException.class,
                    () -> loop.runSegment(new AgentSegmentRequest(
                            "unsafe request", List.of(), null, 0, 1)));
            assertEquals(1, requested.size());
            assertEquals(1, decisionFacts.size());
            assertSame(requested.getFirst(), decisionFacts.getFirst(),
                    "approval and transcript must share one request identity");
            assertEquals(1, requested.getFirst().sameRequestOrdinal(),
                    "approval inspection must not increment the repeated-call ordinal");
            assertEquals(ApprovalDecision.Decision.NEEDS_APPROVAL,
                    decisions.getFirst().decision());
            assertFalse(requested.getFirst().boundedArgumentsSummary().contains(sentinel));
            assertTrue(requested.getFirst().boundedArgumentsSummary().contains("<redacted>"));
            assertEquals("NEEDS_APPROVAL",
                    state.approvals.findByTask(taskId).getFirst().decision());
            assertTrue(state.toolCalls.findByTask(taskId).isEmpty());
        }
    }

    private ApprovalPolicy policy(String mode) {
        return new ApprovalPolicy(new Config(
                temp.toString(), 5, 4096, 4096, mode, 4096),
                "approval-task");
    }
}
