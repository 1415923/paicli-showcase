package com.paicli.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.agent.SafePreflightRejectionDiagnostic;
import com.paicli.model.AgentMessage;
import com.paicli.runtime.PlanCompatibilityObserver;
import com.paicli.state.StateStore;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FinalSourceV3EvidenceProjectionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path root;

    @Test
    void acceptedWireSchemaNeverProjectsAsBarePlanAccepted() throws Exception {
        var fact = new PlanCompatibilityObserver.Fact(
                PlanCompatibilityObserver.ParseStatus.ACCEPTED, "advisory-plan-wire-v1",
                0, 0, 0, 0, "ACCEPTED", List.of("$.steps"),
                List.of(), List.of(), List.of());
        var projected = FinalSourceV3EvidenceProjection.projectPlan(fact);
        assertEquals("WIRE_SCHEMA_ACCEPTED", projected.get("wireSchemaStatus"));
        assertEquals("WIRE_SCHEMA_ACCEPTED", projected.get("terminalReason"));
        assertFalse(projected.containsKey("planParseStatus"));
        assertFalse(JSON.writeValueAsString(projected).contains("\"ACCEPTED\""));
    }

    @Test
    void safeTypedPreflightProjectionIsClosedAndPayloadFree() throws Exception {
        String taskId = "task-safe-v3";
        String sentinel = "SYNTHETIC_SAFE_PREFLIGHT_SECRET_5d93";
        try (StateStore state = new StateStore(root)) {
            state.tasks.createTask(taskId, "synthetic", "mock", "mock", "deny", 1);
            var diagnostic = new SafePreflightRejectionDiagnostic(
                    "run-safe-v3", "write_file", 2,
                    ToolRegistry.PreflightFailureKind.POLICY_DENIED,
                    SafePreflightRejectionDiagnostic.SafeReason.PROTECTED_CONTROL_PATH_REJECTED,
                    "TOOL_PREFLIGHT_REJECTED");
            state.events.append(taskId, 1,
                    AgentMessage.observation(diagnostic.durableText()));
            state.events.append(taskId, 2,
                    AgentMessage.observation("raw_tool_args=" + sentinel));
        }
        var projection = FinalSourceV3EvidenceProjection.safePreflight(root, taskId);
        assertEquals(1, projection.size());
        assertEquals("POLICY_REJECTED", projection.getFirst().get("safeReason"));
        assertEquals(java.util.Set.of("taskId", "runId", "toolName", "batchOrdinal",
                        "preflightFailureKind", "safeReason", "safePreflightCode"),
                projection.getFirst().keySet());
        String serialized = JSON.writeValueAsString(projection);
        assertFalse(serialized.contains(sentinel));
        assertFalse(serialized.matches("(?i).*(arguments|path|command|stdin|content|credential).*"));
    }

    @Test
    void planningOutcomeUsesTypedStageCodesSeparatelyFromWireAcceptance() {
        assertEquals("DOMAIN_MAPPING_FAILED",
                FinalSourceV3EvidenceProjection.planningStageOutcome(
                        "STRUCTURED_PLAN_SCHEMA_MAPPING_FAILED", 0, 0, false));
        assertEquals("AUTHORITY_PROPOSAL_REJECTED",
                FinalSourceV3EvidenceProjection.planningStageOutcome(
                        "PLAN_PROPOSAL_REJECTED", 0, 0, false));
        assertEquals("PLAN_APPROVED",
                FinalSourceV3EvidenceProjection.planningStageOutcome(
                        "", 1, 0, false));
    }
}
