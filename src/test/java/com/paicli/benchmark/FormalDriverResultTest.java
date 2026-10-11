package com.paicli.benchmark;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.paicli.agent.AgentLoopControl;
import com.paicli.agent.AgentLoopObserver;
import com.paicli.agent.SharedToolBatchPolicy;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.ModelProvider;
import com.paicli.provider.ProviderResponseEnvelope;
import com.paicli.tool.Tool;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FormalDriverResultTest {

    @Test
    void everyFrozenTypedFailureContinuesWithCompleteEvidence() {
        for (FormalDriverResult.FailureCategory category
                : FormalDriverResult.FailureCategory.values()) {
            if (category == FormalDriverResult.FailureCategory
                    .HARNESS_OR_EVIDENCE_INVALID) continue;
            FormalDriverResult value = FormalDriverResult.typedTaskFailure(
                    "TYPED_" + category.name(), category);
            assertEquals(FormalDriverResult.OutcomeClass.TYPED_TASK_FAILURE,
                    value.outcomeClass());
            assertEquals(FormalDriverResult.TaskSuccess.FALSE, value.taskSuccess());
            assertTrue(value.evidenceComplete());
            assertEquals(FormalDriverResult.CampaignAction.CONTINUE,
                    value.campaignAction());
        }
    }

    @Test
    void successAndHarnessInvalidHaveDisjointMachineContracts() {
        FormalDriverResult success = FormalDriverResult.taskSucceeded();
        assertEquals(FormalDriverResult.OutcomeClass.TASK_SUCCESS,
                success.outcomeClass());
        assertEquals(FormalDriverResult.CampaignAction.CONTINUE,
                success.campaignAction());

        FormalDriverResult invalid = FormalDriverResult.harnessInvalid(
                "EVIDENCE_WRITER_FAILURE");
        assertEquals(FormalDriverResult.OutcomeClass.HARNESS_INVALID,
                invalid.outcomeClass());
        assertEquals(FormalDriverResult.TaskSuccess.NOT_APPLICABLE,
                invalid.taskSuccess());
        assertFalse(invalid.evidenceComplete());
        assertEquals(FormalDriverResult.CampaignAction.STOP,
                invalid.campaignAction());
    }

    @Test
    void invalidCrossClassCombinationsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new FormalDriverResult(
                FormalDriverResult.OutcomeClass.TYPED_TASK_FAILURE,
                FormalDriverResult.TaskSuccess.FALSE, "BAD",
                List.of(FormalDriverResult.FailureCategory
                        .HARNESS_OR_EVIDENCE_INVALID), true,
                FormalDriverResult.CampaignAction.CONTINUE));
        assertThrows(IllegalArgumentException.class, () -> new FormalDriverResult(
                FormalDriverResult.OutcomeClass.HARNESS_INVALID,
                FormalDriverResult.TaskSuccess.NOT_APPLICABLE, "BAD",
                List.of(FormalDriverResult.FailureCategory
                        .HARNESS_OR_EVIDENCE_INVALID), true,
                FormalDriverResult.CampaignAction.STOP));
    }

    @Test
    void repeatedEffectfulBatchEndsAtMaxIterationsWithoutExecution() {
        CountingTool write = new CountingTool();
        ToolRegistry tools = new ToolRegistry();
        tools.register(write);
        AtomicInteger calls = new AtomicInteger();
        ModelProvider provider = (messages, definitions) -> {
            int turn = calls.incrementAndGet();
            List<ToolCall> batch = List.of(
                    call("call-" + turn + "-a"), call("call-" + turn + "-b"));
            return ModelResponse.toolCallBatchResponse("repeat", batch,
                    ProviderResponseEnvelope.inspect(batch.stream()
                            .map(ToolCall::providerCallId).toList(), false), false);
        };
        SimpleToolLoopBaseline.RunResult result = new SimpleToolLoopBaseline(
                provider, tools, null, AgentLoopControl.NONE, 3).run("write safely");

        assertNotNull(result.agentResult());
        assertEquals(com.paicli.agent.AgentResult.Outcome.MAX_ITERATIONS,
                result.agentResult().outcome());
        assertEquals(3, calls.get());
        assertEquals(0, write.executions.get());
        assertEquals(3, result.effectfulBatchTelemetry().size());
        assertTrue(result.effectfulBatchTelemetry().stream().allMatch(event ->
                event.normalizationOutcome() == AgentLoopObserver.EffectfulBatchFact
                        .NormalizationOutcome.SERIALIZATION_CONTINUATION_ISSUED));
        FormalDriverResult boundary = result.formalDriverResult();
        assertEquals(FormalDriverResult.OutcomeClass.TYPED_TASK_FAILURE,
                boundary.outcomeClass());
        assertEquals("AGENT_MAX_ITERATIONS", boundary.terminalReason());
        assertEquals(List.of(FormalDriverResult.FailureCategory
                .INCOMPLETE_RESULT_OR_FORMAL_RESULT_MISSING), boundary.failureCategories());
        assertEquals(FormalDriverResult.CampaignAction.CONTINUE,
                boundary.campaignAction());
    }

    @Test
    void legacyRepeatedBatchFailureEvidenceRemainsDecodable() {
        FormalDriverResult legacy = FormalDriverResult.typedTaskFailure(
                SharedToolBatchPolicy.REPEATED_BATCH_FAILURE_CODE,
                FormalDriverResult.FailureCategory.PROVIDER_PROTOCOL_FAILURE);

        assertEquals(SharedToolBatchPolicy.REPEATED_BATCH_FAILURE_CODE,
                legacy.terminalReason());
        assertEquals(FormalDriverResult.OutcomeClass.TYPED_TASK_FAILURE,
                legacy.outcomeClass());
    }

    @Test
    void unknownRuntimeExceptionIsNotCaughtAsTypedTaskFailure() {
        ModelProvider defect = (messages, tools) -> {
            throw new IllegalStateException("synthetic harness defect");
        };
        assertThrows(IllegalStateException.class, () -> new SimpleToolLoopBaseline(
                defect, new ToolRegistry(), null, AgentLoopControl.NONE, 2)
                .run("must escape"));
    }

    private static ToolCall call(String id) {
        return new ToolCall("write_file",
                Map.of("path", "file.txt", "content", "x"), id);
    }

    private static final class CountingTool implements Tool {
        private final AtomicInteger executions = new AtomicInteger();
        @Override public String name() { return "write_file"; }
        @Override public String description() { return "counting write"; }
        @Override public ToolDefinition toDefinition() {
            return new ToolDefinition(name(), description(),
                    JsonNodeFactory.instance.objectNode());
        }
        @Override public ToolResult execute(ToolCall call) {
            executions.incrementAndGet();
            return ToolResult.success("executed");
        }
    }
}
