package com.paicli.provider.telemetry;

import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.ModelProvider;
import com.paicli.state.ProviderCallTelemetryStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class R2ExecutionProviderTelemetryTest {
    @Test void authoritativeOptInFailsClosedOnLedgerStartOrFinishFailure(){
        ModelProvider delegate=(messages,tools)->ModelResponse.finalAnswer("ok");
        CapturingStore start=new CapturingStore();start.throwOnStart=true;
        var startProvider=new TelemetryModelProvider(delegate,start,"ollama","model",
                ProviderCallTelemetryStore.OperationKind.CHAT,ProviderCallContext::empty,true);
        assertEquals("telemetry-start-only",assertThrows(IllegalStateException.class,
                ()->startProvider.chat(List.of(),List.of())).getMessage());
        assertEquals(0,start.finished.get());

        CapturingStore finish=new CapturingStore();finish.throwOnFinalize=true;
        var finishProvider=new TelemetryModelProvider(delegate,finish,"ollama","model",
                ProviderCallTelemetryStore.OperationKind.CHAT,ProviderCallContext::empty,true);
        assertEquals("telemetry-only",assertThrows(IllegalStateException.class,
                ()->finishProvider.chat(List.of(),List.of())).getMessage());
        assertEquals(1,finish.started.get());
        assertEquals(1,finish.finished.get());
    }

    @Test void sharedBoundaryRecordsExactlyOneChatAndOnePlanAndRejectsNesting(){
        CapturingStore store=new CapturingStore();
        ModelProvider delegate=(messages,tools)->ModelResponse.finalAnswer("ok");
        var providers=ExecutionProviderTelemetry.instrument(delegate,delegate,store,
                "ollama","model",ProviderCallContext::empty);
        providers.chat().chat(List.of(),List.of());
        providers.plan().chat(List.of(),List.of());
        assertEquals(2,store.started.get());
        assertEquals(2,store.finished.get());
        assertEquals(List.of(ProviderCallTelemetryStore.OperationKind.CHAT,
                ProviderCallTelemetryStore.OperationKind.PLAN),store.kinds);
        assertThrows(IllegalArgumentException.class,()->ExecutionProviderTelemetry.instrument(
                providers.chat(),delegate,store,"ollama","model",ProviderCallContext::empty));
    }

    @Test void successfulDelegateIdentitySurvivesObservationSummaryAndFinalizeFailures(){
        AtomicInteger calls=new AtomicInteger();
        ModelResponse expected=ModelResponse.finalAnswer("same-response");
        ModelProvider delegate=(messages,tools)->{calls.incrementAndGet();return expected;};
        CapturingStore store=new CapturingStore();store.throwOnFinalize=true;
        TelemetryModelProvider provider=new TelemetryModelProvider(delegate,store,
                "ollama","model",ProviderCallTelemetryStore.OperationKind.CHAT,
                ProviderCallContext::empty,(ignored,response)->{throw new IllegalStateException();},
                (messages,tools,response)->{throw new IllegalArgumentException();});
        assertSame(expected,provider.chat(List.of(AgentMessage.userGoal("secret")),List.of()));
        assertEquals(1,calls.get());
    }

    @Test void originalFailureAndCancellationSurviveFinalizeFailureWithOneInvocation(){
        CapturingStore store=new CapturingStore();store.throwOnFinalize=true;
        AtomicInteger failures=new AtomicInteger();
        IllegalStateException expected=new IllegalStateException("provider-original");
        ModelProvider failed=(messages,tools)->{failures.incrementAndGet();throw expected;};
        var provider=new TelemetryModelProvider(failed,store,"ollama","model",
                ProviderCallTelemetryStore.OperationKind.CHAT,ProviderCallContext::empty);
        assertSame(expected,assertThrows(IllegalStateException.class,
                ()->provider.chat(List.of(),List.of())));
        assertEquals(1,failures.get());

        AtomicInteger cancellations=new AtomicInteger();
        CancellationException cancelled=new CancellationException("cancelled");
        ModelProvider cancel=(messages,tools)->{cancellations.incrementAndGet();throw cancelled;};
        var cancelling=new TelemetryModelProvider(cancel,store,"ollama","model",
                ProviderCallTelemetryStore.OperationKind.CHAT,ProviderCallContext::empty,
                (delegate,response)->ProviderCallObservation.notExposed(),
                (messages,tools,response)->{throw new IllegalStateException("summary-only");});
        assertSame(cancelled,assertThrows(CancellationException.class,
                ()->cancelling.chat(List.of(),List.of())));
        assertEquals(1,cancellations.get());
    }

    @Test void startedWriteFailureStillInvokesDelegateExactlyOnce(){
        CapturingStore store=new CapturingStore();store.throwOnStart=true;
        AtomicInteger calls=new AtomicInteger();
        ModelResponse expected=ModelResponse.finalAnswer("unchanged");
        ModelProvider delegate=(messages,tools)->{calls.incrementAndGet();return expected;};
        var provider=new TelemetryModelProvider(delegate,store,"ollama","model",
                ProviderCallTelemetryStore.OperationKind.CHAT,ProviderCallContext::empty);
        assertSame(expected,provider.chat(List.of(),List.of()));
        assertEquals(1,calls.get());
    }

    @Test void planningFailureRecordsPlanOperationAndTypedFailureCategory(){
        CapturingStore store=new CapturingStore();
        ModelProvider delegate=(messages,tools)->ModelResponse.providerError(
                ModelResponse.ProviderErrorKind.PROVIDER_TIMEOUT);
        var provider=new TelemetryModelProvider(delegate,store,"ollama","qwen3:14b",
                ProviderCallTelemetryStore.OperationKind.PLAN,
                ()->new ProviderCallContext("session","task","run",null),
                (ignored,response)->ProviderCallObservation.notExposed(),
                (messages,tools,response)->null,ProviderCallProgressSink.NOOP,
                Duration.ofSeconds(10),new ProviderRetryPolicy(duration->{},upper->0),
                expected->{});
        ModelResponse response=provider.chat(List.of(AgentMessage.userGoal("bounded")),List.of());
        assertEquals(ModelResponse.ProviderErrorKind.PROVIDER_TRANSPORT_RETRY_EXHAUSTED,
                response.providerErrorKind());
        assertEquals(List.of(ProviderCallTelemetryStore.OperationKind.PLAN,
                ProviderCallTelemetryStore.OperationKind.PLAN,
                ProviderCallTelemetryStore.OperationKind.PLAN),store.kinds);
        assertNotNull(store.lastFinish);
        assertEquals(ProviderCallTelemetryStore.Lifecycle.FAILED,
                store.lastFinish.lifecycle());
        assertEquals("PROVIDER_TRANSPORT_RETRY_EXHAUSTED",
                store.lastFinish.observation().errorCategory());
    }

    @Test void slowProviderEmitsBoundedProgressAndStopsAfterReturn()throws Exception{
        CapturingStore store=new CapturingStore();
        RecordingProgressSink progress=new RecordingProgressSink();
        ModelProvider delegate=(messages,tools)->{
            try{Thread.sleep(75);}catch(InterruptedException error){
                Thread.currentThread().interrupt();throw new AssertionError(error);
            }
            return ModelResponse.finalAnswer("done");
        };
        TelemetryModelProvider provider=new TelemetryModelProvider(delegate,store,
                "openai","deepseek-v4-flash",ProviderCallTelemetryStore.OperationKind.CHAT,
                ()->new ProviderCallContext("session","task","run",null),
                (ignored,response)->ProviderCallObservation.notExposed(),
                (messages,tools,response)->null,progress,Duration.ofMillis(20));
        assertEquals(ModelResponse.ResponseType.FINAL,provider.chatForOperation(
                "FINAL_SYNTHESIS",List.of(AgentMessage.userGoal("finish")),List.of()).type());
        int afterReturn=progress.elapsed.get();
        Thread.sleep(50);
        assertEquals(1,progress.started.get());
        assertTrue(afterReturn>=2,"slow request should emit throttled progress");
        assertEquals(afterReturn,progress.elapsed.get(),"progress must stop after return");
        assertEquals(1,progress.finished.get());
        assertEquals(List.of("FINAL_SYNTHESIS"),progress.operations);
    }

    @Test void missingThinkingContinuationRetriesOnceWithSameRequestAndSeparateTelemetry(){
        CapturingStore store=new CapturingStore();
        RecordingProgressSink progress=new RecordingProgressSink();
        AtomicInteger calls=new AtomicInteger();
        AtomicInteger contextReads=new AtomicInteger();
        List<List<AgentMessage>> seenMessages=new java.util.ArrayList<>();
        List<List<ToolDefinition>> seenTools=new java.util.ArrayList<>();
        ModelProvider delegate=(messages,tools)->{
            seenMessages.add(messages);seenTools.add(tools);
            if(calls.incrementAndGet()==1)return ModelResponse.providerError(
                    ModelResponse.ProviderErrorKind
                            .PROVIDER_PRIVATE_CONTINUATION_INCOMPLETE);
            return ModelResponse.toolCallResponse("",new com.paicli.model.ToolCall(
                    "read_file",java.util.Map.of("path","README.md"),"call-valid"));
        };
        TelemetryModelProvider provider=new TelemetryModelProvider(delegate,store,
                "openai","deepseek-v4-flash",ProviderCallTelemetryStore.OperationKind.CHAT,
                ()->{contextReads.incrementAndGet();return new ProviderCallContext(
                        "session","task","run",null);},
                (ignored,response)->ProviderCallObservation.notExposed(),
                (messages,tools,response)->null,progress,Duration.ofSeconds(10),
                new ProviderRetryPolicy(duration->{},upper->0),expected->{});
        List<AgentMessage> messages=List.of(AgentMessage.userGoal("bounded request"));
        List<ToolDefinition> tools=List.of(new ToolDefinition("read_file","read",
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()));

        ModelResponse response=provider.chatForOperation("CHAT",messages,tools);

        assertEquals(ModelResponse.ResponseType.TOOL_CALL,response.type());
        assertEquals(2,calls.get());
        assertEquals(1,contextReads.get(),"retry must retain the same call context");
        assertEquals(List.of(messages,messages),seenMessages);
        assertEquals(List.of(tools,tools),seenTools);
        assertEquals(2,store.started.get());
        assertEquals(2,store.finished.get());
        assertEquals(2,progress.started.get());
        assertEquals(2,progress.finished.get());
        assertEquals(1,progress.retrying.get());
        assertEquals(List.of("PRIVATE_CONTINUATION_INCOMPLETE"),
                progress.retryReasons);
        assertEquals(List.of("CHAT","CHAT"),progress.operations);
    }

    @Test void secondMissingThinkingContinuationFailsWithTerminalRetryCategory(){
        CapturingStore store=new CapturingStore();
        RecordingProgressSink progress=new RecordingProgressSink();
        AtomicInteger calls=new AtomicInteger();
        ModelProvider delegate=(messages,tools)->{
            calls.incrementAndGet();
            return ModelResponse.providerError(ModelResponse.ProviderErrorKind
                    .PROVIDER_PRIVATE_CONTINUATION_INCOMPLETE);
        };
        TelemetryModelProvider provider=new TelemetryModelProvider(delegate,store,
                "openai","deepseek-v4-flash",ProviderCallTelemetryStore.OperationKind.CHAT,
                ProviderCallContext::empty,
                (ignored,response)->ProviderCallObservation.notExposed(),
                (messages,tools,response)->null,progress,Duration.ofSeconds(10),
                new ProviderRetryPolicy(duration->{},upper->0),expected->{});

        ModelResponse response=provider.chat(List.of(),List.of());

        assertEquals(3,calls.get());
        assertEquals(ModelResponse.ProviderErrorKind
                .PROVIDER_PRIVATE_CONTINUATION_INCOMPLETE_AFTER_RETRY,
                response.providerErrorKind());
        assertEquals(3,store.started.get());
        assertEquals(3,store.finished.get());
        assertEquals("PROVIDER_PRIVATE_CONTINUATION_INCOMPLETE_AFTER_RETRY",
                store.lastFinish.observation().errorCategory());
        assertEquals(2,progress.retrying.get());
        assertEquals(List.of("PRIVATE_CONTINUATION_INCOMPLETE",
                        "PRIVATE_CONTINUATION_INCOMPLETE"),
                progress.retryReasons);
    }

    private static final class CapturingStore extends ProviderCallTelemetryStore{
        private final AtomicInteger started=new AtomicInteger();
        private final AtomicInteger finished=new AtomicInteger();
        private final java.util.ArrayList<OperationKind> kinds=new java.util.ArrayList<>();
        private boolean throwOnFinalize;
        private boolean throwOnStart;
        private CallFinish lastFinish;
        private CapturingStore(){super(Path.of("."));}
        @Override public boolean recordStarted(CallStart start){
            if(throwOnStart)throw new IllegalStateException("telemetry-start-only");
            started.incrementAndGet();kinds.add(start.operationKind());return true;
        }
        @Override public boolean finalizeCall(CallFinish finish){
            finished.incrementAndGet();
            lastFinish=finish;
            if(throwOnFinalize)throw new IllegalStateException("telemetry-only");
            return true;
        }
    }

    private static final class RecordingProgressSink implements ProviderCallProgressSink{
        private final AtomicInteger started=new AtomicInteger();
        private final AtomicInteger elapsed=new AtomicInteger();
        private final AtomicInteger finished=new AtomicInteger();
        private final AtomicInteger retrying=new AtomicInteger();
        private final java.util.concurrent.CopyOnWriteArrayList<String> retryReasons=
                new java.util.concurrent.CopyOnWriteArrayList<>();
        private final java.util.concurrent.CopyOnWriteArrayList<String> operations=
                new java.util.concurrent.CopyOnWriteArrayList<>();
        @Override public void started(String correlationId,ProviderCallContext context,
                String operation){started.incrementAndGet();operations.add(operation);}
        @Override public void elapsed(String correlationId,ProviderCallContext context,
                String operation,long seconds){assertTrue(seconds>=1);elapsed.incrementAndGet();}
        @Override public void retrying(ProviderCallContext context,String operation,int attempt,
                String reason){
            assertTrue(attempt==2||attempt==3);
            assertEquals("PRIVATE_CONTINUATION_INCOMPLETE",reason);
            retryReasons.add(reason);
            retrying.incrementAndGet();
        }
        @Override public void finished(String correlationId){finished.incrementAndGet();}
    }
}
