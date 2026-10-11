package com.paicli.provider.telemetry;

import com.paicli.provider.ModelProvider;
import com.paicli.state.ProviderCallTelemetryStore;

import java.util.Objects;
import java.util.function.Supplier;

/** Shared Task-execution boundary for exactly one CHAT and one PLAN telemetry decorator. */
public final class ExecutionProviderTelemetry {
    private ExecutionProviderTelemetry() { }

    public static Providers instrument(ModelProvider chatDelegate,
            ModelProvider planDelegate, ProviderCallTelemetryStore telemetry,
            String provider, String model, Supplier<ProviderCallContext> context) {
        return instrument(chatDelegate,planDelegate,telemetry,provider,model,context,
                ProviderCallProgressSink.NOOP);
    }

    public static Providers instrument(ModelProvider chatDelegate,
            ModelProvider planDelegate, ProviderCallTelemetryStore telemetry,
            String provider, String model, Supplier<ProviderCallContext> context,
            boolean authoritativePersistence) {
        if(!authoritativePersistence)
            return instrument(chatDelegate,planDelegate,telemetry,provider,model,context);
        Objects.requireNonNull(chatDelegate); Objects.requireNonNull(planDelegate);
        Objects.requireNonNull(telemetry); Objects.requireNonNull(context);
        if(chatDelegate instanceof TelemetryModelProvider
                ||planDelegate instanceof TelemetryModelProvider)
            throw new IllegalArgumentException("Execution Provider is already instrumented");
        return new Providers(
                new TelemetryModelProvider(chatDelegate,telemetry,provider,model,
                        ProviderCallTelemetryStore.OperationKind.CHAT,context,true),
                new TelemetryModelProvider(planDelegate,telemetry,provider,model,
                        ProviderCallTelemetryStore.OperationKind.PLAN,context,true));
    }

    public static Providers instrument(ModelProvider chatDelegate,
            ModelProvider planDelegate,ProviderCallTelemetryStore telemetry,
            String provider,String model,Supplier<ProviderCallContext> context,
            ProviderCallProgressSink progressSink) {
        return instrument(chatDelegate,planDelegate,telemetry,provider,model,context,
                progressSink,false);
    }

    public static Providers instrument(ModelProvider chatDelegate,
            ModelProvider planDelegate,ProviderCallTelemetryStore telemetry,
            String provider,String model,Supplier<ProviderCallContext> context,
            ProviderCallProgressSink progressSink,boolean authoritativePersistence) {
        Objects.requireNonNull(chatDelegate, "chatDelegate");
        Objects.requireNonNull(planDelegate, "planDelegate");
        Objects.requireNonNull(telemetry, "telemetry");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(progressSink,"progressSink");
        if (chatDelegate instanceof TelemetryModelProvider
                || planDelegate instanceof TelemetryModelProvider) {
            throw new IllegalArgumentException("Execution Provider is already instrumented");
        }
        return new Providers(
                new TelemetryModelProvider(chatDelegate, telemetry, provider, model,
                        ProviderCallTelemetryStore.OperationKind.CHAT, context,progressSink,
                        authoritativePersistence),
                new TelemetryModelProvider(planDelegate, telemetry, provider, model,
                        ProviderCallTelemetryStore.OperationKind.PLAN, context,progressSink,
                        authoritativePersistence));
    }

    public record Providers(ModelProvider chat, ModelProvider plan) {
        public Providers {
            Objects.requireNonNull(chat, "chat");
            Objects.requireNonNull(plan, "plan");
        }
    }
}
