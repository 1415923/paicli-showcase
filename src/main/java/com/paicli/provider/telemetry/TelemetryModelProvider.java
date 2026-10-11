package com.paicli.provider.telemetry;

import com.paicli.model.AgentMessage;
import com.paicli.model.AgentMessage.MessageType;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.ModelProvider;
import com.paicli.provider.OutputRequirement;
import com.paicli.provider.PreparedProviderTurn;
import com.paicli.provider.PreparedTurnProvider;
import com.paicli.provider.ProviderRetrySignal;
import com.paicli.provider.ProviderRetrySignalSource;
import com.paicli.provider.ProviderResponseEnvelope;
import com.paicli.state.ProviderCallTelemetryStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Failure-isolated Provider telemetry decorator. */
public final class TelemetryModelProvider implements ModelProvider {
    private static final ScheduledExecutorService PROGRESS_EXECUTOR=
            Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon(true)
                    .name("provider-progress").factory());
    private static final Duration DEFAULT_PROGRESS_INTERVAL=Duration.ofSeconds(10);
    private final ModelProvider delegate;

    @Override public boolean usesManagedAnswerEnvelope() { return delegate.usesManagedAnswerEnvelope(); }
    @Override public OutputRequirement.Mode managedOutputMode() { return delegate.managedOutputMode(); }
    @Override public void validateOutputRequirement(OutputRequirement requirement, boolean hasTools) {
        delegate.validateOutputRequirement(requirement, hasTools);
    }
    private final ProviderCallTelemetryStore telemetry;
    private final String provider;
    private final String model;
    private final ProviderCallTelemetryStore.OperationKind operationKind;
    private final Supplier<ProviderCallContext> context;
    private final ObservationReader observationReader;
    private final StructuralSummary structuralSummary;
    private final ProviderCallProgressSink progressSink;
    private final Duration progressInterval;
    private final ProviderRetryPolicy retryPolicy;
    private final RetryGuard retryGuard;
    private final boolean authoritativePersistence;

    public TelemetryModelProvider(ModelProvider delegate,
            ProviderCallTelemetryStore telemetry, String provider, String model,
            ProviderCallTelemetryStore.OperationKind operationKind,
            Supplier<ProviderCallContext> context) {
        this(delegate, telemetry, provider, model, operationKind, context,
                TelemetryModelProvider::readObservation,
                TelemetryModelProvider::structuralHash,
                ProviderCallProgressSink.NOOP,DEFAULT_PROGRESS_INTERVAL,
                new ProviderRetryPolicy(),TelemetryModelProvider::requireRuntimeRetry,false);
    }

    public TelemetryModelProvider(ModelProvider delegate,
            ProviderCallTelemetryStore telemetry, String provider, String model,
            ProviderCallTelemetryStore.OperationKind operationKind,
            Supplier<ProviderCallContext> context, boolean authoritativePersistence) {
        this(delegate, telemetry, provider, model, operationKind, context,
                TelemetryModelProvider::readObservation,
                TelemetryModelProvider::structuralHash,
                ProviderCallProgressSink.NOOP, DEFAULT_PROGRESS_INTERVAL,
                new ProviderRetryPolicy(), TelemetryModelProvider::requireRuntimeRetry,
                authoritativePersistence);
    }

    TelemetryModelProvider(ModelProvider delegate,
            ProviderCallTelemetryStore telemetry,String provider,String model,
            ProviderCallTelemetryStore.OperationKind operationKind,
            Supplier<ProviderCallContext> context,ProviderCallProgressSink progressSink){
        this(delegate,telemetry,provider,model,operationKind,context,
                TelemetryModelProvider::readObservation,
                TelemetryModelProvider::structuralHash,progressSink,
                DEFAULT_PROGRESS_INTERVAL,new ProviderRetryPolicy(),
                TelemetryModelProvider::requireRuntimeRetry,false);
    }

    TelemetryModelProvider(ModelProvider delegate,
            ProviderCallTelemetryStore telemetry,String provider,String model,
            ProviderCallTelemetryStore.OperationKind operationKind,
            Supplier<ProviderCallContext> context,ProviderCallProgressSink progressSink,
            boolean authoritativePersistence){
        this(delegate,telemetry,provider,model,operationKind,context,
                TelemetryModelProvider::readObservation,
                TelemetryModelProvider::structuralHash,progressSink,
                DEFAULT_PROGRESS_INTERVAL,new ProviderRetryPolicy(),
                TelemetryModelProvider::requireRuntimeRetry,authoritativePersistence);
    }

    TelemetryModelProvider(ModelProvider delegate,
            ProviderCallTelemetryStore telemetry, String provider, String model,
            ProviderCallTelemetryStore.OperationKind operationKind,
            Supplier<ProviderCallContext> context, ObservationReader observationReader,
            StructuralSummary structuralSummary) {
        this(delegate,telemetry,provider,model,operationKind,context,observationReader,
                structuralSummary,ProviderCallProgressSink.NOOP,DEFAULT_PROGRESS_INTERVAL);
    }

    TelemetryModelProvider(ModelProvider delegate,
            ProviderCallTelemetryStore telemetry,String provider,String model,
            ProviderCallTelemetryStore.OperationKind operationKind,
            Supplier<ProviderCallContext> context,ObservationReader observationReader,
            StructuralSummary structuralSummary,ProviderCallProgressSink progressSink,
            Duration progressInterval) {
        this(delegate,telemetry,provider,model,operationKind,context,observationReader,
                structuralSummary,progressSink,progressInterval,new ProviderRetryPolicy(),
                TelemetryModelProvider::requireRuntimeRetry,false);
    }

    TelemetryModelProvider(ModelProvider delegate,
            ProviderCallTelemetryStore telemetry,String provider,String model,
            ProviderCallTelemetryStore.OperationKind operationKind,
            Supplier<ProviderCallContext> context,ObservationReader observationReader,
            StructuralSummary structuralSummary,ProviderCallProgressSink progressSink,
            Duration progressInterval,ProviderRetryPolicy retryPolicy,RetryGuard retryGuard) {
        this(delegate,telemetry,provider,model,operationKind,context,observationReader,
                structuralSummary,progressSink,progressInterval,retryPolicy,retryGuard,false);
    }

    TelemetryModelProvider(ModelProvider delegate,
            ProviderCallTelemetryStore telemetry,String provider,String model,
            ProviderCallTelemetryStore.OperationKind operationKind,
            Supplier<ProviderCallContext> context,ObservationReader observationReader,
            StructuralSummary structuralSummary,ProviderCallProgressSink progressSink,
            Duration progressInterval,ProviderRetryPolicy retryPolicy,RetryGuard retryGuard,
            boolean authoritativePersistence) {
        this.delegate = Objects.requireNonNull(delegate);
        this.telemetry = Objects.requireNonNull(telemetry);
        this.provider = Objects.requireNonNull(provider);
        this.model = model;
        this.operationKind = Objects.requireNonNull(operationKind);
        this.context = Objects.requireNonNull(context);
        this.observationReader = Objects.requireNonNull(observationReader);
        this.structuralSummary = Objects.requireNonNull(structuralSummary);
        this.progressSink=Objects.requireNonNull(progressSink);
        this.progressInterval=Objects.requireNonNull(progressInterval);
        this.retryPolicy=Objects.requireNonNull(retryPolicy);
        this.retryGuard=Objects.requireNonNull(retryGuard);
        this.authoritativePersistence=authoritativePersistence;
        if(progressInterval.isZero()||progressInterval.isNegative())
            throw new IllegalArgumentException("progressInterval must be positive");
    }

    @Override
    public ModelResponse chat(List<AgentMessage> messages, List<ToolDefinition> tools) {
        return execute(operationKind.name(),messages,tools,OutputRequirement.LEGACY);
    }

    @Override
    public ModelResponse chatForOperation(String operation,List<AgentMessage> messages,
            List<ToolDefinition> tools) {
        return chatForOperation(operation, messages, tools, OutputRequirement.LEGACY);
    }

    @Override
    public ModelResponse chatForOperation(String operation, List<AgentMessage> messages,
            List<ToolDefinition> tools, OutputRequirement requirement) {
        validateOutputRequirement(requirement, tools != null && !tools.isEmpty());
        String effective=operation==null||operation.isBlank()?operationKind.name():operation;
        return execute(effective,messages,tools,requirement);
    }

    private ModelResponse execute(String effectiveOperation,
            List<AgentMessage> messages,List<ToolDefinition> tools, OutputRequirement requirement) {
        ProviderCallContext callContext;
        try { callContext = context.get(); }
        catch (RuntimeException ignored) { callContext = ProviderCallContext.empty(); }
        var expectedTurn=com.paicli.context.ProviderContextRuntime.currentTurn();
        PreparedProviderTurn prepared=prepare(effectiveOperation,messages,tools,requirement);
        String logicalCallId=UUID.randomUUID().toString();
        for(int attempt=1;attempt<=ProviderRetryPolicy.MAX_ATTEMPTS;attempt++){
            AttemptResult result=executeAttempt(effectiveOperation,messages,tools,callContext,
                    logicalCallId,prepared,attempt);
            ProviderRetryPolicy.Decision decision=retryPolicy.decide(
                    result.response(),result.retrySignal(),attempt);
            if(!decision.retry()){
                if(result.exhausted()){
                    safeProgressExhausted(callContext,effectiveOperation,logicalCallId,
                            attempt,decisionReason(result),prepared.requestDigest());
                    com.paicli.diagnostic.StageTrace.event("PROVIDER_RETRY_EXHAUSTED",true,
                            "logical_call_id",logicalCallId,
                            "operation_kind",effectiveOperation,
                            "attempts",attempt,
                            "retry_count",attempt-1,
                            "retry_reason",decisionReason(result),
                            "request_digest_hash",prepared.requestDigest(),
                            "final_outcome",result.response().providerErrorKind().name());
                    return result.response();
                }
                if(attempt>1&&isSuccess(result.response())){
                    safeProgressRestored(callContext,effectiveOperation,logicalCallId,attempt);
                    com.paicli.diagnostic.StageTrace.event("PROVIDER_CONNECTION_RESTORED",true,
                            "logical_call_id",logicalCallId,
                            "operation_kind",effectiveOperation,
                            "successful_attempt",attempt,
                            "retry_count",attempt-1,
                            "request_digest_hash",prepared.requestDigest());
                }
                return result.response();
            }
            int nextAttempt=attempt+1;
            safeProgressRetrying(callContext,effectiveOperation,logicalCallId,nextAttempt,
                    decision.reason().name(),decision.delay(),prepared.requestDigest());
            traceRetry(logicalCallId,effectiveOperation,attempt,nextAttempt,decision,
                    prepared.requestDigest());
            try{
                retryPolicy.sleep(decision.delay());
                retryGuard.require(expectedTurn);
            }catch(InterruptedException cancelled){
                Thread.currentThread().interrupt();
                return ModelResponse.providerError(
                        ModelResponse.ProviderErrorKind.PROVIDER_REQUEST_FAILURE);
            }catch(RuntimeException rejected){
                com.paicli.diagnostic.StageTrace.event("PROVIDER_RETRY_REJECTED",true,
                        "logical_call_id",logicalCallId,
                        "operation_kind",effectiveOperation,
                        "next_attempt",nextAttempt,
                        "request_digest_hash",prepared.requestDigest(),
                        "reason","AUTHORITY_OR_CONTEXT_CHANGED");
                return ModelResponse.providerError(
                        ModelResponse.ProviderErrorKind.PROVIDER_RETRY_ADMISSION_REJECTED);
            }
        }
        throw new IllegalStateException("unreachable retry state");
    }

    private PreparedProviderTurn prepare(String operation,List<AgentMessage> messages,
            List<ToolDefinition> tools, OutputRequirement requirement){
        if(delegate instanceof PreparedTurnProvider prepared)
            return prepared.prepareTurn(operation,List.copyOf(messages),List.copyOf(tools),requirement);
        List<AgentMessage> immutableMessages=List.copyOf(messages);
        List<ToolDefinition> immutableTools=List.copyOf(tools);
        return new PreparedProviderTurn(requestHash(immutableMessages,immutableTools,operation,requirement),
                ()->delegate.chatForOperation(operation,immutableMessages,immutableTools,requirement));
    }

    private AttemptResult executeAttempt(String effectiveOperation,
            List<AgentMessage> messages,List<ToolDefinition> tools,
            ProviderCallContext callContext,String logicalCallId,PreparedProviderTurn prepared,
            int attempt) {
        String correlation = logicalCallId+":a"+attempt;
        Instant started = Instant.now();
        long startedNanos = System.nanoTime();
        try {
            boolean recorded=telemetry.recordStarted(new ProviderCallTelemetryStore.CallStart(correlation,
                    provider, model, operationKind, callContext, started));
            if(authoritativePersistence&&!recorded)
                throw new IllegalStateException("AUTHORITATIVE_PROVIDER_LEDGER_START_FAILED");
            telemetry.linkContext(correlation,
                    com.paicli.context.ProviderContextRuntime.current());
        } catch (RuntimeException error) {
            if(authoritativePersistence)throw error;
        }
        ModelResponse response;
        ScheduledFuture<?> progress=startProgress(correlation,callContext,effectiveOperation,
                startedNanos);
        try {
            com.paicli.diagnostic.StageTrace.event(
                    "PROVIDER_CALL_START", true,
                    "provider", provider,
                    "model", model,
                    "operation_kind", effectiveOperation,
                    "logical_call_id",logicalCallId,
                    "request_attempt",attempt,
                    "retry_count",attempt-1,
                    "request_digest_hash",prepared.requestDigest(),
                    "message_count", messages == null ? 0 : messages.size(),
                    "tool_count", tools == null ? 0 : tools.size(),
                    "correlation_id", correlation);
            response = prepared.execute();
        } catch (RuntimeException error) {
            boolean cancelled = Thread.currentThread().isInterrupted()
                    || error.getClass().getSimpleName().contains("Cancellation");
            ProviderCallObservation observation = ProviderCallObservation.notExposed()
                    .withError(cancelled ? "USER_CANCELLED" : "PROVIDER_REQUEST_FAILURE",
                            cancelled);
            finish(correlation, startedNanos,
                    cancelled ? ProviderCallTelemetryStore.Lifecycle.CANCELLED
                            : ProviderCallTelemetryStore.Lifecycle.FAILED,
                    cancelled ? "CANCELLED" : "EXCEPTION", observation,
                    prepared.requestDigest(),effectiveOperation,logicalCallId,attempt,attempt-1);
            throw error;
        } finally {
            if(progress!=null)progress.cancel(false);
            safeProgressFinished(correlation);
        }
        ProviderCallObservation observation = safeObservation(response);
        ProviderRetrySignal retrySignal=safeRetrySignal();
        boolean exhausted=attempt==ProviderRetryPolicy.MAX_ATTEMPTS
                &&retryPolicy.decide(response,retrySignal,attempt-1).retry();
        if(exhausted){
            response=exhausted(response);
            observation=observation.withError(response.providerErrorKind().name(),false);
        }
        ProviderCallTelemetryStore.Lifecycle lifecycle = observation.cancelled()
                ? ProviderCallTelemetryStore.Lifecycle.CANCELLED
                : response.type() == ModelResponse.ResponseType.FINAL
                || response.type() == ModelResponse.ResponseType.TOOL_CALL
                ? ProviderCallTelemetryStore.Lifecycle.SUCCEEDED
                : ProviderCallTelemetryStore.Lifecycle.FAILED;
        int durableRetryCount=retryPolicy.decide(response,retrySignal,attempt).retry()
                ?0:attempt-1;
        finish(correlation, startedNanos, lifecycle, response.type().name(),
                observation,prepared.requestDigest(),effectiveOperation,logicalCallId,attempt,
                durableRetryCount);
        return new AttemptResult(response,retrySignal,exhausted);
    }

    private void safeProgressRetrying(ProviderCallContext callContext,String operation,
            String logicalCallId,int nextAttempt,String reason,Duration delay,String digest){
        try{
            progressSink.retrying(new ProviderCallProgressSink.RetryProgress(logicalCallId,
                    callContext,operation,nextAttempt,nextAttempt-1,reason,
                    Math.max(0,delay.toMillis()),digest));
        }catch(RuntimeException ignored){ }
    }

    private void safeProgressRestored(ProviderCallContext context,String operation,
            String logicalCallId,int attempt){
        try{progressSink.restored(logicalCallId,context,operation,attempt);}
        catch(RuntimeException ignored){ }
    }

    private void safeProgressExhausted(ProviderCallContext context,String operation,
            String logicalCallId,int attempt,String reason,String digest){
        try{progressSink.exhausted(logicalCallId,context,operation,attempt,reason,digest);}
        catch(RuntimeException ignored){ }
    }

    private ScheduledFuture<?> startProgress(String correlation,ProviderCallContext callContext,
            String operation,long startedNanos){
        try{progressSink.started(correlation,callContext,operation);}
        catch(RuntimeException ignored){ }
        if(progressSink==ProviderCallProgressSink.NOOP)return null;
        long intervalMillis=Math.max(1L,progressInterval.toMillis());
        try{
            return PROGRESS_EXECUTOR.scheduleAtFixedRate(()->{
                long elapsedSeconds=Math.max(1L,
                        TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-startedNanos));
                try{progressSink.elapsed(correlation,callContext,operation,elapsedSeconds);}
                catch(RuntimeException ignored){ }
            },intervalMillis,intervalMillis,TimeUnit.MILLISECONDS);
        }catch(RuntimeException ignored){return null;}
    }

    private void safeProgressFinished(String correlation){
        try{progressSink.finished(correlation);}
        catch(RuntimeException ignored){ }
    }

    private ProviderCallObservation safeObservation(ModelResponse response) {
        try { return observationReader.read(delegate, response); }
        catch (RuntimeException ignored) { return ProviderCallObservation.notExposed(); }
    }

    private ProviderRetrySignal safeRetrySignal(){
        try{return delegate instanceof ProviderRetrySignalSource source
                ?source.takeRetrySignal():null;}
        catch(RuntimeException ignored){return null;}
    }

    private String safeSummary(List<AgentMessage> messages, List<ToolDefinition> tools,
            ModelResponse response) {
        try { return structuralSummary.hash(messages, tools, response); }
        catch (RuntimeException ignored) { return null; }
    }

    private static ProviderCallObservation readObservation(ModelProvider delegate,
            ModelResponse response) {
        ProviderCallObservation adapter = delegate instanceof ProviderObservationSource source
                ? source.takeObservation() : null;
        ProviderCallObservation base = adapter == null
                ? ProviderCallObservation.notExposed() : adapter;
        if (response.type() == ModelResponse.ResponseType.PROVIDER_ERROR) {
            return base.withError(response.providerErrorKind().name(), base.cancelled());
        }
        if (response.type() == ModelResponse.ResponseType.PROTOCOL_ERROR) {
            ProviderResponseEnvelope envelope=response.providerEnvelope();
            String code=envelope==null||envelope.diagnostic()==null
                    ?null:envelope.diagnostic().code();
            return base.withError(code!=null&&code.contains("TOOL_CALL")
                    ?"TOOL_ADMISSION":"PROVIDER_PROTOCOL", false);
        }
        ProviderResponseEnvelope envelope = response.providerEnvelope();
        if (envelope == null || base.responseCardinality() != null) return base;
        return new ProviderCallObservation(base.inputTokens(), base.outputTokens(),
                base.totalTokens(), base.cacheReadTokens(), base.cacheWriteTokens(),
                base.reasoningTokens(), base.usageSource(), base.usageCoverage(),
                envelope.diagnostic().callCount(), envelope.cardinality(),
                base.finishReason(), base.safeRequestId(), base.errorCategory(),
                base.cancelled());
    }

    private void finish(String correlation, long startedNanos,
            ProviderCallTelemetryStore.Lifecycle lifecycle, String outcome,
            ProviderCallObservation observation, String structuralHash,
            String effectiveOperation,String logicalCallId,int attempt,int durableRetryCount) {
        Instant ended = Instant.now();
        long latency = Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000L);
        try {
            boolean finalized=telemetry.finalizeCall(new ProviderCallTelemetryStore.CallFinish(correlation,
                    lifecycle, ended, latency, outcome, durableRetryCount, observation,
                    structuralHash, "ptc:" + logicalCallId));
            if(authoritativePersistence&&!finalized)
                throw new IllegalStateException("AUTHORITATIVE_PROVIDER_LEDGER_FINISH_FAILED");
        } catch (RuntimeException error) {
            if(authoritativePersistence)throw error;
        }
        com.paicli.diagnostic.StageTrace.event(
                "PROVIDER_CALL_END", true,
                "provider", provider,
                "model", model,
                "operation_kind", effectiveOperation,
                "logical_call_id",logicalCallId,
                "request_attempt",attempt,
                "retry_count",attempt-1,
                "lifecycle", lifecycle.name(),
                "outcome", outcome,
                "error_category", observation.errorCategory(),
                "latency_ms", latency,
                "correlation_id", correlation);
    }

    private static void traceRetry(String logicalCallId,String operation,int failedAttempt,
            int nextAttempt,ProviderRetryPolicy.Decision decision,String digest){
        com.paicli.diagnostic.StageTrace.event("PROVIDER_REQUEST_RETRY",true,
                "logical_call_id",logicalCallId,
                "operation_kind",operation,
                "failed_attempt",failedAttempt,
                "next_attempt",nextAttempt,
                "retry_count",nextAttempt-1,
                "retry_reason",decision.reason().name(),
                "http_category",decision.httpCategory(),
                "backoff_ms",decision.delay().toMillis(),
                "request_digest_hash",digest);
    }

    private static ModelResponse exhausted(ModelResponse response){
        return ModelResponse.providerError(response.providerErrorKind()
                ==ModelResponse.ProviderErrorKind.PROVIDER_PRIVATE_CONTINUATION_INCOMPLETE
                ?ModelResponse.ProviderErrorKind
                    .PROVIDER_PRIVATE_CONTINUATION_INCOMPLETE_AFTER_RETRY
                :ModelResponse.ProviderErrorKind.PROVIDER_TRANSPORT_RETRY_EXHAUSTED);
    }

    private static String decisionReason(AttemptResult result){
        return result.retrySignal()==null?result.response().providerErrorKind().name()
                :result.retrySignal().reason().name();
    }

    private static boolean isSuccess(ModelResponse response){
        return response.type()==ModelResponse.ResponseType.FINAL
                ||response.type()==ModelResponse.ResponseType.TOOL_CALL;
    }

    private static String requestHash(List<AgentMessage> messages,List<ToolDefinition> tools,String operation,OutputRequirement requirement){
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            digest.update((operation + "\n" + requirement.identity() + "\n").getBytes(StandardCharsets.UTF_8));
            for(AgentMessage message:messages){
                digest.update(message.type().name().getBytes(StandardCharsets.UTF_8));
                if(message.content()!=null)digest.update(message.content()
                        .getBytes(StandardCharsets.UTF_8));
                if(message.toolCallId()!=null)digest.update(message.toolCallId()
                        .getBytes(StandardCharsets.UTF_8));
                if(message.toolCallJson()!=null)digest.update(message.toolCallJson()
                        .getBytes(StandardCharsets.UTF_8));
            }
            for(ToolDefinition tool:tools)digest.update(tool.toString()
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        }catch(Exception impossible){throw new IllegalStateException(impossible);}
    }

    private static void requireRuntimeRetry(
            com.paicli.context.ProviderContextRuntime.ProviderTurnIdentity expected){
        if(expected==null)throw new IllegalStateException("PROVIDER_TURN_IDENTITY_REQUIRED");
        com.paicli.context.ProviderContextRuntime.requireRetryAdmission();
        if(!expected.equals(com.paicli.context.ProviderContextRuntime.currentTurn()))
            throw new IllegalStateException("PROVIDER_TURN_IDENTITY_CHANGED");
    }

    private record AttemptResult(ModelResponse response,ProviderRetrySignal retrySignal,
            boolean exhausted){}

    @FunctionalInterface
    interface RetryGuard{
        void require(com.paicli.context.ProviderContextRuntime.ProviderTurnIdentity expected);
    }

    private static String structuralHash(List<AgentMessage> messages,
            List<ToolDefinition> tools, ModelResponse response) {
        try {
            EnumMap<MessageType, Integer> roles = new EnumMap<>(MessageType.class);
            long chars = 0;
            long bytes = 0;
            for (AgentMessage message : messages == null ? List.<AgentMessage>of() : messages) {
                roles.merge(message.type(), 1, Integer::sum);
                String content = message.content();
                if (content != null) {
                    chars += content.length();
                    bytes += content.getBytes(StandardCharsets.UTF_8).length;
                }
            }
            String material = "roles=" + roles + ";messages="
                    + (messages == null ? 0 : messages.size())
                    + ";chars=" + bucket(chars) + ";bytes=" + bucket(bytes)
                    + ";tools=" + (tools == null ? 0 : tools.size())
                    + ";response=" + (response == null ? "EXCEPTION" : response.type().name())
                    + ";cardinality=" + (response == null
                    || response.providerEnvelope() == null ? "NOT_EXPOSED"
                    : response.providerEnvelope().cardinality().name());
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String bucket(long value) {
        if (value == 0) return "0";
        if (value <= 256) return "1-256";
        if (value <= 1024) return "257-1024";
        if (value <= 4096) return "1025-4096";
        if (value <= 16384) return "4097-16384";
        return "16385+";
    }

    @FunctionalInterface
    interface ObservationReader {
        ProviderCallObservation read(ModelProvider delegate, ModelResponse response);
    }

    @FunctionalInterface
    interface StructuralSummary {
        String hash(List<AgentMessage> messages, List<ToolDefinition> tools,
                ModelResponse response);
    }
}
