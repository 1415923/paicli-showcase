package com.paicli.provider.telemetry;

import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.ModelProvider;
import com.paicli.provider.OutputRequirement;
import com.paicli.provider.PreparedProviderTurn;
import com.paicli.provider.PreparedTurnProvider;
import com.paicli.provider.ProviderRetrySignal;
import com.paicli.provider.ProviderRetrySignalSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared outer transport decorator that settles Provider call evidence before interpretation. */
public final class ProviderCallLedgerModelProvider
        implements PreparedTurnProvider, ProviderObservationSource, ProviderRetrySignalSource {
    private final ModelProvider delegate;

    @Override public boolean usesManagedAnswerEnvelope() { return delegate.usesManagedAnswerEnvelope(); }
    @Override public OutputRequirement.Mode managedOutputMode() { return delegate.managedOutputMode(); }
    @Override public void validateOutputRequirement(OutputRequirement requirement, boolean hasTools) {
        delegate.validateOutputRequirement(requirement, hasTools);
    }
    private final AppendOnlyProviderCallLedger ledger;
    private final ProviderCallLedgerContext context;
    private final AtomicInteger ordinals = new AtomicInteger();
    private final ThreadLocal<ProviderCallObservation> observations =
            ThreadLocal.withInitial(ProviderCallObservation::notExposed);

    public ProviderCallLedgerModelProvider(ModelProvider delegate,
            AppendOnlyProviderCallLedger ledger, ProviderCallLedgerContext context) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public ModelResponse chat(List<AgentMessage> messages, List<ToolDefinition> tools) {
        return invoke("CHAT", () -> delegate.chat(messages, tools));
    }

    @Override
    public ModelResponse chatForOperation(String operation, List<AgentMessage> messages,
            List<ToolDefinition> tools) {
        return prepareTurn(operation, messages, tools).execute();
    }

    @Override
    public PreparedProviderTurn prepareTurn(String operation, List<AgentMessage> messages,
            List<ToolDefinition> tools) {
        return prepareTurn(operation, messages, tools, OutputRequirement.LEGACY);
    }

    @Override
    public PreparedProviderTurn prepareTurn(String operation, List<AgentMessage> messages,
            List<ToolDefinition> tools, OutputRequirement requirement) {
        validateOutputRequirement(requirement, tools != null && !tools.isEmpty());
        messages = List.copyOf(messages); tools = List.copyOf(tools);
        final var immutableMessages = messages; final var immutableTools = tools;
        PreparedProviderTurn prepared = delegate instanceof PreparedTurnProvider source
                ? source.prepareTurn(operation, messages, tools, requirement)
                : new PreparedProviderTurn(structuralDigest(operation + "\n" + requirement.identity(), messages, tools),
                        () -> delegate.chatForOperation(operation, immutableMessages, immutableTools, requirement));
        return new PreparedProviderTurn(prepared.requestDigest(), prepared.metrics(),
                () -> invoke(operation, prepared::execute));
    }

    @Override
    public ProviderCallObservation takeObservation() {
        ProviderCallObservation value = observations.get();
        observations.set(ProviderCallObservation.notExposed());
        return value;
    }

    @Override
    public ProviderRetrySignal takeRetrySignal() {
        return delegate instanceof ProviderRetrySignalSource source
                ? source.takeRetrySignal() : null;
    }

    private ModelResponse invoke(String operation, java.util.function.Supplier<ModelResponse> call) {
        int ordinal = ordinals.incrementAndGet();
        String providerCallId = callId(context, ordinal);
        Instant requested = Instant.now();
        ledger.requestAdmitted(providerCallId, context, ordinal, operation, requested);
        try {
            ModelResponse response = call.get();
            ProviderCallObservation observation = readObservation();
            observations.set(observation);
            Integer httpStatus = delegate instanceof ProviderHttpStatusSource source
                    ? source.takeHttpStatus()
                    : response.type() == ModelResponse.ResponseType.PROVIDER_ERROR ? null : 200;
            ProviderTransportEvidence transport = readTransportEvidence(
                    response, observation, httpStatus);
            String errorCategory = response.type() == ModelResponse.ResponseType.PROVIDER_ERROR
                    ? response.providerErrorKind().name() : null;
            ledger.attemptTerminated(providerCallId, context, ordinal, operation, requested,
                    Instant.now(), transport, observation, errorCategory);
            return response;
        } catch (RuntimeException error) {
            ProviderCallObservation observation = ProviderCallObservation.notExposed();
            observations.set(observation);
            ProviderTransportEvidence transport = readTransportEvidence(
                    null, observation, null);
            ledger.attemptTerminated(providerCallId, context, ordinal, operation, requested,
                    Instant.now(), transport, observation, error.getClass().getSimpleName());
            throw error;
        }
    }

    private ProviderCallObservation readObservation() {
        if (delegate instanceof ProviderObservationSource source) {
            ProviderCallObservation value = source.takeObservation();
            return value == null ? ProviderCallObservation.notExposed() : value;
        }
        return ProviderCallObservation.notExposed();
    }

    private ProviderTransportEvidence readTransportEvidence(ModelResponse response,
            ProviderCallObservation observation, Integer httpStatus) {
        if (delegate instanceof ProviderTransportEvidenceSource source) {
            ProviderTransportEvidence value = source.takeTransportEvidence();
            if (value != null) return value;
        }
        if (httpStatus != null) {
            ProviderTransportEvidence.UsageProvenance provenance =
                    observation.usageSource()
                    == ProviderCallObservation.UsageSource.PROVIDER_REPORTED
                    && observation.usageCoverage()
                    == ProviderCallObservation.UsageCoverage.COMPLETE
                    ? ProviderTransportEvidence.UsageProvenance.PROVIDER_REPORTED
                    : ProviderTransportEvidence.UsageProvenance.NOT_AVAILABLE;
            return ProviderTransportEvidence.responseReceived(httpStatus, provenance,
                    provenance == ProviderTransportEvidence.UsageProvenance.PROVIDER_REPORTED
                            ? null : "ADAPTER_USAGE_EVIDENCE_UNAVAILABLE");
        }
        String reason = response != null
                && response.type() == ModelResponse.ResponseType.PROVIDER_ERROR
                ? "PRE_TRANSPORT_OR_UNKNOWN_PROVIDER_FAILURE"
                : "TRANSPORT_EVIDENCE_SOURCE_UNAVAILABLE";
        return ProviderTransportEvidence.unknownTerminal(reason);
    }

    private static String callId(ProviderCallLedgerContext context, int ordinal) {
        try {
            String material = context.runId() + "\n" + context.runtime() + "\n"
                    + ordinal + "\n" + context.protocolHash();
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return "pcall-" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (Exception error) {
            throw new IllegalStateException("Provider call identity cannot be generated", error);
        }
    }

    private static String structuralDigest(String operation, List<AgentMessage> messages,
            List<ToolDefinition> tools) {
        try {
            String material = operation + "\n" + messages + "\n" + tools;
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("Provider request digest cannot be generated", error);
        }
    }
}
