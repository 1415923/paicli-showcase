package com.paicli.benchmark;

import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.PreparedProviderTurn;
import com.paicli.provider.PreparedTurnProvider;
import com.paicli.provider.ProviderRetrySignal;
import com.paicli.provider.ProviderRetrySignalSource;
import com.paicli.context.ProviderContextRuntime;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Benchmark-only deterministic 503 injection before the first execution request is sent. */
final class FirstExecution503Provider
        implements PreparedTurnProvider, ProviderRetrySignalSource {
    private final PreparedTurnProvider delegate;
    private final ProviderRetrySignalSource retrySignals;
    private final AtomicBoolean injected = new AtomicBoolean();
    private final AtomicInteger injectedTurnAttempts = new AtomicInteger();
    private final List<String> attemptContextGenerations = new ArrayList<>();
    private final ThreadLocal<ProviderRetrySignal> localSignal = new ThreadLocal<>();

    FirstExecution503Provider(PreparedTurnProvider delegate) {
        this.delegate = Objects.requireNonNull(delegate);
        this.retrySignals = delegate instanceof ProviderRetrySignalSource source ? source : null;
    }

    @Override
    public PreparedProviderTurn prepareTurn(String operation, List<AgentMessage> messages,
            List<ToolDefinition> tools) {
        PreparedProviderTurn prepared = delegate.prepareTurn(operation, messages, tools);
        boolean injectThisTurn = !"PLAN".equals(operation)
                && injected.compareAndSet(false, true);
        if (!injectThisTurn) return prepared;
        AtomicBoolean firstAttempt = new AtomicBoolean(true);
        return new PreparedProviderTurn(prepared.requestDigest(), () -> {
            injectedTurnAttempts.incrementAndGet();
            ProviderContextRuntime.ProviderTurnIdentity identity =
                    ProviderContextRuntime.currentTurn();
            attemptContextGenerations.add(identity == null
                    ? "NONE" : identity.contextGenerationId());
            if (firstAttempt.compareAndSet(true, false)) {
                localSignal.set(new ProviderRetrySignal(ProviderRetrySignal.Reason.HTTP_503,
                        true, Duration.ZERO, "HTTP_503"));
                return ModelResponse.providerError(
                        ModelResponse.ProviderErrorKind.PROVIDER_SERVER_FAILURE);
            }
            return prepared.execute();
        });
    }

    @Override
    public ProviderRetrySignal takeRetrySignal() {
        ProviderRetrySignal local = localSignal.get();
        localSignal.remove();
        if (local != null) return local;
        return retrySignals == null ? null : retrySignals.takeRetrySignal();
    }

    boolean injected() {
        return injected.get();
    }

    int injectedTurnAttempts() {
        return injectedTurnAttempts.get();
    }

    List<String> attemptContextGenerations() {
        return List.copyOf(attemptContextGenerations);
    }
}
