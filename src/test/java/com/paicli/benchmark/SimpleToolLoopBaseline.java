package com.paicli.benchmark;

import com.paicli.agent.AgentLoop;
import com.paicli.agent.AgentLoopControl;
import com.paicli.agent.AgentLoopObserver;
import com.paicli.agent.AgentResult;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.ModelProvider;
import com.paicli.provider.telemetry.ProviderCallObservation;
import com.paicli.provider.telemetry.ProviderObservationSource;
import com.paicli.safety.ApprovalPolicy;
import com.paicli.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Benchmark-only Simple Tool Loop. It reuses the production Provider protocol and Tool loop
 * while deliberately omitting PaiCLI's durable coding-runtime control plane.
 */
public final class SimpleToolLoopBaseline {
    public static String sharedToolBatchPolicyHash() {
        return AgentLoop.sharedToolBatchPolicyHash();
    }

    public static String sharedToolBatchTelemetryHash() {
        return AgentLoop.sharedToolBatchTelemetryHash();
    }

    private final ProtocolAwareProvider provider;
    private final AgentLoop loop;
    private final MetricsObserver metrics;

    public SimpleToolLoopBaseline(ModelProvider sharedProvider, ToolRegistry tools,
            ApprovalPolicy approvalPolicy, AgentLoopControl approvalControl, int maxIterations) {
        Objects.requireNonNull(sharedProvider, "sharedProvider");
        Objects.requireNonNull(tools, "tools");
        provider = new ProtocolAwareProvider(sharedProvider);
        metrics = new MetricsObserver();
        loop = new AgentLoop(provider, tools, maxIterations, approvalPolicy, null,
                null, null, "shared-provider", "shared-model", metrics,
                Objects.requireNonNull(approvalControl, "approvalControl"));
    }

    public RunResult run(String userTask) {
        long started = System.nanoTime();
        AgentResult result = null;
        FormalDriverResult driverResult;
        try {
            result = loop.run(userTask);
            driverResult = FormalDriverResult.fromAgentResult(result);
        } catch (AgentLoop.ProviderProtocolException failure) {
            driverResult = FormalDriverResult.typedTaskFailure(failure.code(),
                    FormalDriverResult.FailureCategory.PROVIDER_PROTOCOL_FAILURE);
        }
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
        return new RunResult(result, provider.turns, metrics.toolCalls, elapsedMillis,
                provider.providerLatencyMillis, metrics.toolLatencyMillis,
                List.copyOf(provider.observations), provider.operations(),
                List.copyOf(metrics.approvalDecisionFacts),
                List.copyOf(metrics.effectfulBatchTelemetry), driverResult);
    }

    public String providerAdapterId() {
        return provider.delegate.getClass().getName();
    }

    public record RunResult(AgentResult agentResult, int providerTurns, int toolCalls,
                            long harnessLatencyMillis, long providerLatencyMillis,
                            long toolLatencyMillis,
                            List<ProviderCallObservation> providerObservations,
                            List<String> providerOperations,
                            List<String> approvalDecisionFacts,
                            List<AgentLoopObserver.EffectfulBatchFact>
                                    effectfulBatchTelemetry,
                            FormalDriverResult formalDriverResult) {
        public RunResult {
            providerObservations = List.copyOf(providerObservations);
            providerOperations = List.copyOf(providerOperations);
            approvalDecisionFacts = List.copyOf(approvalDecisionFacts);
            effectfulBatchTelemetry = List.copyOf(effectfulBatchTelemetry);
            Objects.requireNonNull(formalDriverResult);
            if (agentResult == null
                    && formalDriverResult.outcomeClass()
                    != FormalDriverResult.OutcomeClass.TYPED_TASK_FAILURE) {
                throw new IllegalArgumentException(
                        "a missing AgentResult requires a typed task failure");
            }
            if (agentResult != null
                    && formalDriverResult.outcomeClass()
                    == FormalDriverResult.OutcomeClass.HARNESS_INVALID) {
                throw new IllegalArgumentException(
                        "an AgentResult cannot be classified as harness invalid");
            }
        }
    }

    private static final class ProtocolAwareProvider implements ModelProvider {
        private final ModelProvider delegate;
        private final List<ProviderCallObservation> observations = new ArrayList<>();
        private final List<String> operations = new ArrayList<>();
        private int turns;
        private long providerLatencyMillis;

        private ProtocolAwareProvider(ModelProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public ModelResponse chat(List<AgentMessage> messages, List<ToolDefinition> tools) {
            String operation = messages.isEmpty()
                    || messages.getLast().type() != AgentMessage.MessageType.OBSERVATION
                    ? "CHAT" : "TOOL_CONTINUATION";
            turns++;
            operations.add(operation);
            long started = System.nanoTime();
            ModelResponse response = delegate.chatForOperation(operation, messages, tools);
            providerLatencyMillis += (System.nanoTime() - started) / 1_000_000L;
            if (delegate instanceof ProviderObservationSource source) {
                ProviderCallObservation observation = source.takeObservation();
                observations.add(observation == null
                        ? ProviderCallObservation.notExposed() : observation);
            }
            return response;
        }

        private List<String> operations() {
            return List.copyOf(operations);
        }
    }

    private static final class MetricsObserver implements AgentLoopObserver {
        private int toolCalls;
        private long toolLatencyMillis;
        private final List<String> approvalDecisionFacts = new ArrayList<>();
        private final List<EffectfulBatchFact> effectfulBatchTelemetry = new ArrayList<>();

        @Override
        public void toolDecision(String taskId, int iteration, ToolRequestFact request,
                com.paicli.safety.ApprovalDecision decision) {
            approvalDecisionFacts.add(iteration + ":" + request.sameRequestOrdinal() + ":"
                    + request.toolName() + ":" + decision.decision().name() + ":"
                    + decision.riskLevel().name());
        }

        @Override
        public void toolCompleted(String taskId, int iteration, ToolRequestFact request,
                ToolCompletionFact result) {
            toolCalls++;
            toolLatencyMillis += result.elapsedMillis();
        }

        @Override
        public void effectfulBatchNormalized(String taskId, int iteration,
                EffectfulBatchFact fact) {
            effectfulBatchTelemetry.add(fact);
        }
    }
}
