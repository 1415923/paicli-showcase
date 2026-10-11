package com.paicli.provider.telemetry;

import com.paicli.context.ProviderRetryTestScope;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolCall;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.PreparedProviderTurn;
import com.paicli.provider.PreparedTurnProvider;
import com.paicli.provider.ProviderRetrySignal;
import com.paicli.provider.ProviderRetrySignalSource;
import com.paicli.state.ProviderCallTelemetryStore;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ProviderTransportResilienceTest {
    private static final String DIGEST="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test void TRANSPORT_TLS_EOF_RETRY_PASS(){
        ScriptedProvider raw=script(failure(ModelResponse.ProviderErrorKind
                .PROVIDER_TRANSPORT_INTERRUPTED,ProviderRetrySignal.Reason.TRANSPORT_TLS_EOF),
                success("restored"));
        assertEquals("restored",provider(raw).chat(List.of(),List.of()).content());
        assertEquals(2,raw.executions.get(),"PASS: TLS EOF consumes one shared retry");
    }

    @Test void TRANSPORT_CONNECT_TIMEOUT_RETRY_PASS(){
        ScriptedProvider raw=script(failure(ModelResponse.ProviderErrorKind.PROVIDER_TIMEOUT,
                ProviderRetrySignal.Reason.TRANSPORT_CONNECT_TIMEOUT),success("restored"));
        assertEquals(ModelResponse.ResponseType.FINAL,provider(raw).chat(List.of(),List.of()).type());
        assertEquals(2,raw.executions.get(),"PASS: connect timeout retries once");
    }

    @Test void TRANSPORT_PARTIAL_STREAM_DISCARDED(){
        String partial="partial-secret-must-not-surface";
        ScriptedProvider raw=script(failure(ModelResponse.ProviderErrorKind
                .PROVIDER_TRANSPORT_INTERRUPTED,ProviderRetrySignal.Reason.TRANSPORT_INTERRUPTED),
                success("complete"));
        ModelResponse response=provider(raw).chat(List.of(AgentMessage.userGoal("goal")),List.of());
        assertEquals("complete",response.content());
        assertFalse(response.toString().contains(partial),"PASS: partial bytes are not a response");
    }

    @Test void TRANSPORT_429_RETRY_AFTER_PASS(){
        ArrayList<Duration> sleeps=new ArrayList<>();
        ScriptedProvider raw=script(new Attempt(ModelResponse.providerError(
                ModelResponse.ProviderErrorKind.PROVIDER_RATE_LIMITED),new ProviderRetrySignal(
                ProviderRetrySignal.Reason.HTTP_429,true,Duration.ofSeconds(7),"HTTP_429")),
                success("ok"));
        assertEquals("ok",provider(raw,sleeps,(expected)->{}).chat(List.of(),List.of()).content());
        assertEquals(List.of(Duration.ofSeconds(7)),sleeps,"PASS: bounded Retry-After wins");
    }

    @Test void TRANSPORT_401_NO_RETRY(){
        ScriptedProvider raw=script(new Attempt(ModelResponse.providerError(
                ModelResponse.ProviderErrorKind.PROVIDER_AUTHENTICATION_FAILURE),
                new ProviderRetrySignal(ProviderRetrySignal.Reason.NON_RETRYABLE,false,null,
                        "HTTP_401")));
        assertEquals(ModelResponse.ProviderErrorKind.PROVIDER_AUTHENTICATION_FAILURE,
                provider(raw).chat(List.of(),List.of()).providerErrorKind());
        assertEquals(1,raw.executions.get(),"PASS: 401 is never retried");
    }

    @Test void TRANSPORT_MAX_THREE_ATTEMPTS(){
        ScriptedProvider raw=script(timeout(),timeout(),timeout(),success("must-not-run"));
        ModelResponse response=provider(raw).chat(List.of(),List.of());
        assertEquals(ModelResponse.ProviderErrorKind.PROVIDER_TRANSPORT_RETRY_EXHAUSTED,
                response.providerErrorKind());
        assertEquals(3,raw.executions.get(),"PASS: initial plus two retries only");
    }

    @Test void PROTOCOL_AND_TRANSPORT_SHARED_BUDGET(){
        ScriptedProvider raw=script(privateIncomplete(),timeout(),privateIncomplete(),success("no"));
        ModelResponse response=provider(raw).chat(List.of(),List.of());
        assertEquals(ModelResponse.ProviderErrorKind
                .PROVIDER_PRIVATE_CONTINUATION_INCOMPLETE_AFTER_RETRY,
                response.providerErrorKind());
        assertEquals(3,raw.executions.get(),"PASS: causes cannot multiply retry budgets");
    }

    @Test void RETRY_REQUEST_DIGEST_STABLE(){
        CapturingStore store=new CapturingStore();
        ScriptedProvider raw=script(timeout(),timeout(),success("ok"));
        TelemetryModelProvider provider=provider(raw,store,new ArrayList<>(),expected->{});
        assertEquals("ok",provider.chat(List.of(AgentMessage.userGoal("same")),List.of()).content());
        assertEquals(1,raw.prepares.get());
        assertEquals(List.of(DIGEST,DIGEST,DIGEST),store.hashes,
                "PASS: all attempts retain exact prepared digest");
    }

    @Test void RETRY_NO_TOOL_OCCURRENCE_BEFORE_COMPLETE_RESPONSE_UNIT_SEAM_PASS(){
        ScriptedProvider raw=script(timeout(),new Attempt(ModelResponse.toolCallResponse("",
                new ToolCall("read_file",java.util.Map.of("path","README.md"),"call-1")),null));
        ModelResponse response=provider(raw).chat(List.of(),List.of());
        assertEquals(ModelResponse.ResponseType.TOOL_CALL,response.type());
        assertEquals(2,raw.executions.get(),"PASS: only complete Tool call is admitted upstream");
    }

    @Test void RETRY_DOES_NOT_REEXECUTE_COMPLETED_TOOL_UNIT_SEAM_PASS(){
        List<AgentMessage> continuation=List.of(AgentMessage.userGoal("read"),
                AgentMessage.actionWithToolCall("read","call-1","{}"),
                AgentMessage.observationForToolCall("durable evidence","call-1"));
        ScriptedProvider raw=script(timeout(),success("grounded"));
        assertEquals("grounded",provider(raw).chat(continuation,List.of()).content());
        assertEquals(List.of(continuation,continuation),raw.seenMessages,
                "PASS: retry resends continuation snapshot, not the Tool");
    }

    @Test void RETRY_BLOCKED_FOR_UNKNOWN_EFFECT_UNIT_SEAM_PASS(){
        ScriptedProvider raw=script(timeout(),success("must-not-run"));
        TelemetryModelProvider provider=provider(raw,new ArrayList<>(),expected->{
            throw new IllegalStateException("UNKNOWN_EFFECT");
        });
        ModelResponse response=provider.chat(List.of(),List.of());
        assertEquals(ModelResponse.ProviderErrorKind.PROVIDER_RETRY_ADMISSION_REJECTED,
                response.providerErrorKind());
        assertEquals(1,raw.executions.get(),"PASS: rejected authority blocks second request");
    }

    @Test void RETRY_COMPACT_MUTUAL_EXCLUSION_UNIT_SEAM_PASS()throws Exception{
        ScriptedProvider raw=script(new Attempt(ModelResponse.providerError(
                ModelResponse.ProviderErrorKind.PROVIDER_TIMEOUT),new ProviderRetrySignal(
                ProviderRetrySignal.Reason.TRANSPORT_READ_TIMEOUT,true,Duration.ZERO,"TIMEOUT")),
                success("same generation"));
        try(AutoCloseable ignored=ProviderRetryTestScope.open()){
            assertEquals("same generation",providerWithRuntimeGuard(raw).chat(List.of(),List.of())
                    .content());
        }
        assertEquals(1,raw.prepares.get(),"PASS: retry cannot re-enter context/compact prepare");
    }

    @Test void RETRY_EXHAUSTED_TELEMETRY_PRESERVED_UNIT_PASS(){
        CapturingStore store=new CapturingStore();
        ScriptedProvider raw=script(timeout(),timeout(),timeout());
        TelemetryModelProvider provider=provider(raw,store,new ArrayList<>(),expected->{});
        ModelResponse response=provider.chat(List.of(AgentMessage.userGoal("durable")),List.of());
        assertEquals(ModelResponse.ProviderErrorKind.PROVIDER_TRANSPORT_RETRY_EXHAUSTED,
                response.providerErrorKind());
        assertEquals(3,store.finished.get());
        assertEquals(List.of(0,0,2),store.retryCounts,
                "PASS: final row owns the logical retry count without multiplicative sums");
    }

    @Test void RETRY_PRIVATE_METADATA_NON_DISCLOSURE_UNIT_PASS(){
        RecordingSink sink=new RecordingSink();
        ScriptedProvider raw=script(privateIncomplete(),success("ok"));
        TelemetryModelProvider provider=provider(raw,new CapturingStore(),new ArrayList<>(),
                expected->{},sink);
        assertEquals("ok",provider.chat(List.of(AgentMessage.userGoal("public")),List.of())
                .content());
        String publicProjection=sink.progress.toString()+raw.preparedToString;
        assertFalse(publicProjection.contains("reasoning_content"));
        assertFalse(publicProjection.contains("Authorization"));
        assertTrue(publicProjection.contains("<redacted>"),"PASS: prepared body stays redacted");
    }

    private static TelemetryModelProvider provider(ScriptedProvider raw){
        return provider(raw,new ArrayList<>(),expected->{});
    }
    private static TelemetryModelProvider provider(ScriptedProvider raw,List<Duration> sleeps,
            TelemetryModelProvider.RetryGuard guard){
        return provider(raw,new CapturingStore(),sleeps,guard);
    }
    private static TelemetryModelProvider provider(ScriptedProvider raw,CapturingStore store,
            List<Duration> sleeps,TelemetryModelProvider.RetryGuard guard){
        return provider(raw,store,sleeps,guard,ProviderCallProgressSink.NOOP);
    }
    private static TelemetryModelProvider provider(ScriptedProvider raw,CapturingStore store,
            List<Duration> sleeps,TelemetryModelProvider.RetryGuard guard,
            ProviderCallProgressSink sink){
        ProviderRetryPolicy policy=new ProviderRetryPolicy(sleeps::add,upper->0);
        return new TelemetryModelProvider(raw,store,"openai","model",
                ProviderCallTelemetryStore.OperationKind.CHAT,
                ()->new ProviderCallContext("session","task","run","generation"),
                (ignored,response)->ProviderCallObservation.notExposed(),
                (messages,tools,response)->null,sink,Duration.ofSeconds(10),policy,guard);
    }
    private static TelemetryModelProvider providerWithRuntimeGuard(ScriptedProvider raw){
        ProviderRetryPolicy policy=new ProviderRetryPolicy(duration->{},upper->0);
        return new TelemetryModelProvider(raw,new CapturingStore(),"openai","model",
                ProviderCallTelemetryStore.OperationKind.CHAT,
                ()->new ProviderCallContext("session","task","run","ctx-test"),
                (ignored,response)->ProviderCallObservation.notExposed(),
                (messages,tools,response)->null,ProviderCallProgressSink.NOOP,
                Duration.ofSeconds(10),policy,(expected)->{
                    assertNotNull(expected);
                    com.paicli.context.ProviderContextRuntime.requireRetryAdmission();
                    assertEquals(expected,com.paicli.context.ProviderContextRuntime.currentTurn());
                });
    }

    private static ScriptedProvider script(Attempt...attempts){return new ScriptedProvider(attempts);}
    private static Attempt success(String text){return new Attempt(ModelResponse.finalAnswer(text),null);}
    private static Attempt timeout(){return failure(ModelResponse.ProviderErrorKind.PROVIDER_TIMEOUT,
            ProviderRetrySignal.Reason.TRANSPORT_READ_TIMEOUT);}
    private static Attempt privateIncomplete(){return failure(ModelResponse.ProviderErrorKind
            .PROVIDER_PRIVATE_CONTINUATION_INCOMPLETE,
            ProviderRetrySignal.Reason.PRIVATE_CONTINUATION_INCOMPLETE);}
    private static Attempt failure(ModelResponse.ProviderErrorKind kind,
            ProviderRetrySignal.Reason reason){
        return new Attempt(ModelResponse.providerError(kind),new ProviderRetrySignal(reason,true,
                Duration.ZERO,null));
    }
    private record Attempt(ModelResponse response,ProviderRetrySignal signal){}

    private static final class ScriptedProvider implements PreparedTurnProvider,
            ProviderRetrySignalSource{
        private final ArrayDeque<Attempt> attempts;
        private final AtomicInteger prepares=new AtomicInteger();
        private final AtomicInteger executions=new AtomicInteger();
        private final ArrayList<List<AgentMessage>> seenMessages=new ArrayList<>();
        private ProviderRetrySignal currentSignal;
        private String preparedToString;
        private ScriptedProvider(Attempt...values){attempts=new ArrayDeque<>(List.of(values));}
        @Override public PreparedProviderTurn prepareTurn(String operation,
                List<AgentMessage> messages,List<ToolDefinition> tools){
            prepares.incrementAndGet();List<AgentMessage> snapshot=List.copyOf(messages);
            PreparedProviderTurn prepared=new PreparedProviderTurn(DIGEST,()->{
                executions.incrementAndGet();seenMessages.add(snapshot);
                Attempt value=attempts.removeFirst();currentSignal=value.signal();
                return value.response();
            });preparedToString=prepared.toString();return prepared;
        }
        @Override public ProviderRetrySignal takeRetrySignal(){
            ProviderRetrySignal value=currentSignal;currentSignal=null;return value;
        }
    }

    private static final class CapturingStore extends ProviderCallTelemetryStore{
        private final AtomicInteger finished=new AtomicInteger();
        private final ArrayList<String> hashes=new ArrayList<>();
        private final ArrayList<Integer> retryCounts=new ArrayList<>();
        private CapturingStore(){super(Path.of("."));}
        @Override public boolean recordStarted(CallStart start){return true;}
        @Override public boolean linkContext(String correlation,String generation){return true;}
        @Override public boolean finalizeCall(CallFinish finish){
            finished.incrementAndGet();hashes.add(finish.structuralSummaryHash());
            retryCounts.add(finish.retryCount());return true;
        }
    }
    private static final class RecordingSink implements ProviderCallProgressSink{
        private final ArrayList<RetryProgress> progress=new ArrayList<>();
        @Override public void retrying(RetryProgress value){progress.add(value);}
    }
}
