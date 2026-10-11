package com.paicli.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.model.*;
import com.paicli.provider.MockModelProvider;
import com.paicli.provider.ModelProvider;
import com.paicli.provider.ProviderResponseEnvelope;
import com.paicli.safety.ApprovalPolicy;
import com.paicli.safety.SafetyPolicy;
import com.paicli.session.AuditLog;
import com.paicli.tool.*;
import com.paicli.config.Config;
import com.paicli.state.StateStore;
import com.paicli.state.R1aDurableModels.OwnershipLease;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.runtime.FencedExecutionGuard;
import com.paicli.runtime.TaskExecutionAuthority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AgentLoopTest {

    @TempDir
    Path workspace;

    SafetyPolicy policy;
    ToolRegistry registry;

    @BeforeEach
    void setUp() {
        policy = new SafetyPolicy(workspace);
        registry = new ToolRegistry();
        registry.register(new ListFilesTool(policy));
        registry.register(new ReadFileTool(policy));
    }

    @Test
    void immediateFinalResponse() {
        var mock = new MockModelProvider(List.of(
                ModelResponse.finalAnswer("No tools needed.")
        ));
        var loop = new AgentLoop(mock, registry);

        AgentResult result = loop.run("say hello");

        assertTrue(result.completed());
        assertEquals("No tools needed.", result.answer());
        assertEquals(1, result.iterations());
        assertEquals(AgentResult.Outcome.SUCCESS, result.outcome());
    }

    @Test
    void typedAndLegacyProviderFailuresNeverCrossTheSuccessfulSegmentBoundary() {
        boolean[] executed = {false};
        ToolRegistry localRegistry = new ToolRegistry();
        localRegistry.register(new Tool() {
            @Override public String name() { return "record"; }
            @Override public String description() { return "record execution"; }
            @Override public ToolDefinition toDefinition() {
                return new ToolDefinition("record", "record execution",
                        new ObjectMapper().createObjectNode());
            }
            @Override public ToolResult execute(ToolCall call) {
                executed[0] = true;
                return ToolResult.success("executed");
            }
        });
        AgentSegmentResult typed = new AgentLoop(new MockModelProvider(List.of(
                ModelResponse.providerError(
                        ModelResponse.ProviderErrorKind.PROVIDER_TIMEOUT))), localRegistry)
                .runSegment(new AgentSegmentRequest("goal", List.of(), null, 0, 1));
        assertEquals(AgentSegmentResult.Outcome.PROVIDER_ERROR, typed.outcome());
        assertEquals(ModelResponse.ProviderErrorKind.PROVIDER_TIMEOUT,
                typed.providerErrorKind());
        assertFalse(typed.completed());
        assertFalse(executed[0]);

        String secret = "secret-provider-body";
        AgentResult legacy = new AgentLoop(new MockModelProvider(List.of(
                ModelResponse.finalAnswer("[MODEL_ERROR] " + secret))), localRegistry)
                .run("goal");
        assertEquals(AgentResult.Outcome.PROVIDER_ERROR, legacy.outcome());
        assertEquals(ModelResponse.ProviderErrorKind.PROVIDER_LEGACY_ERROR,
                legacy.providerErrorKind());
        assertFalse(legacy.completed());
        assertFalse(legacy.toString().contains(secret));
        assertFalse(executed[0]);
    }

    @Test
    void executionGuardCannotBeBoundToAnotherManagedTask()throws Exception{
        CanonicalId managedTask=CanonicalId.newId(EntityFamily.TASK);
        CanonicalId otherTask=CanonicalId.newId(EntityFamily.TASK);
        CanonicalId otherRun=CanonicalId.newId(EntityFamily.RUN);
        Instant at=Instant.parse("2026-07-17T00:00:00Z");
        OwnershipLease lease=new OwnershipLease(otherTask,"owner",otherRun,"secret-token",
                1,1,"ACTIVE",at,at,at.plusSeconds(30));
        try(StateStore state=new StateStore(workspace)){
            FencedExecutionGuard guard=new FencedExecutionGuard(state,
                    TaskExecutionAuthority.recovery(lease),Duration.ofSeconds(30),()->at);
            AgentLoop loop=new AgentLoop(new MockModelProvider(List.of(
                    ModelResponse.finalAnswer("should not run"))),registry,1,null,null,
                    state,managedTask.value(),"mock","model-a");
            assertThrows(IllegalArgumentException.class,()->loop.bindExecutionGuard(guard));
            assertFalse(guard.current().toString().contains("secret-token"));
        }
    }

    @Test
    void oneToolCallThenFinal() throws IOException {
        Files.writeString(workspace.resolve("readme.txt"), "project info");

        var mock = new MockModelProvider(List.of(
                ModelResponse.toolCallResponse("Let me check",
                        new ToolCall("list_files", Map.of("path", "."))),
                ModelResponse.finalAnswer("Found readme.txt")
        ));
        var loop = new AgentLoop(mock, registry);

        AgentResult result = loop.run("what files are here?");

        assertTrue(result.completed());
        assertEquals("Found readme.txt", result.answer());
        assertEquals(2, result.iterations());
        assertTrue(result.messages().stream().anyMatch(m -> m.startsWith("ACTION:")));
        assertTrue(result.messages().stream().anyMatch(m -> m.startsWith("OBSERVATION:")));
    }

    @Test
    void privateContinuationRequirementIsPersistableWithoutPrivateReasoning() {
        String sentinel="private-reasoning-must-never-be-public";
        ProviderResponseEnvelope envelope=ProviderResponseEnvelope.inspect(
                List.of("thinking-call-1"),false);
        var mock=new MockModelProvider(List.of(
                ModelResponse.privateContinuationToolCall("inspect workspace",
                        new ToolCall("list_files",Map.of("path","."),"thinking-call-1"),
                        envelope),
                ModelResponse.finalAnswer("done")));
        AgentSegmentResult result=new AgentLoop(mock,registry,3).runSegment(
                new AgentSegmentRequest("list",List.of(),null,0,3));
        AgentMessage action=result.conversation().stream()
                .filter(message->message.type()==AgentMessage.MessageType.ACTION)
                .findFirst().orElseThrow();
        assertTrue(action.toolCallJson().contains(
                "\"x_paicli_private_continuation\":true"));
        assertFalse(result.toString().contains(sentinel));
        assertFalse(action.toString().contains(sentinel));
    }

    @Test
    void emptyPrivateArtifactLegalityRemainsOwnedByTheProvider() {
        OpaqueContinuationProvider accepting=new OpaqueContinuationProvider("",true,false);
        AgentResult accepted=new AgentLoop(accepting,registry,3).run("list files");
        assertTrue(accepted.completed());
        assertEquals(1,accepting.toolResponsesObserved.get());
        assertFalse(accepted.toString().contains("reasoning_content"));

        OpaqueContinuationProvider rejecting=new OpaqueContinuationProvider("",false,false);
        AgentSegmentResult rejected=new AgentLoop(rejecting,registry,3).runSegment(
                new AgentSegmentRequest("list files",List.of(),null,0,3));
        assertEquals(AgentSegmentResult.Outcome.PROVIDER_ERROR,rejected.outcome());
        assertEquals(ModelResponse.ProviderErrorKind.PROVIDER_PRIVATE_CONTINUATION_PROTOCOL_FAILURE,
                rejected.providerErrorKind());
        assertEquals(0,rejecting.toolResponsesObserved.get(),
                "provider rejection must precede Tool execution");
    }

    @Test
    void providerWithoutPrivateArtifactContinuesToolsNormally() {
        AtomicInteger calls=new AtomicInteger();
        AtomicReference<List<AgentMessage>> continuation=new AtomicReference<>();
        ModelProvider provider=(messages,tools)->{
            if(calls.getAndIncrement()==0)return ModelResponse.toolCallResponse("list",
                    new ToolCall("list_files",Map.of("path","."),"ordinary-call"));
            continuation.set(List.copyOf(messages));
            return ModelResponse.finalAnswer("done");
        };
        assertTrue(new AgentLoop(provider,registry,3).run("list").completed());
        AgentMessage action=continuation.get().stream().filter(message->
                message.type()==AgentMessage.MessageType.ACTION).findFirst().orElseThrow();
        assertFalse(action.toolCallJson().contains("x_paicli_private_continuation"));
        assertEquals("ordinary-call",action.toolCallId());
    }

    @Test
    void opaqueArtifactsMayBeTurnOrCallBoundWithoutBecomingToolIdentity()throws IOException{
        Files.writeString(workspace.resolve("a.txt"),"a");
        Files.writeString(workspace.resolve("b.txt"),"b");
        for(boolean callBound:List.of(false,true)){
            OpaqueContinuationProvider provider=new OpaqueContinuationProvider(
                    callBound?"call-artifact-a|call-artifact-b":"shared-turn-artifact",
                    true,callBound);
            AgentResult result=new AgentLoop(provider,registry,3).run("read two files");
            assertTrue(result.completed());
            assertEquals(List.of("opaque-call-a","opaque-call-b"),provider.observedToolIds);
            assertFalse(result.toString().contains("shared-turn-artifact"));
            assertFalse(result.toString().contains("call-artifact-a"));
            assertFalse(result.toString().contains("call-artifact-b"));
        }
    }

    @Test
    void agentLoopDoesNotParseProviderSpecificContinuationProtocols()throws IOException{
        String source=Files.readString(Path.of("src/main/java/com/paicli/agent/AgentLoop.java"));
        for(String forbidden:List.of("reasoning_content","deepseek-v","thought_signature",
                "anthropic","gemini"))
            assertFalse(source.toLowerCase().contains(forbidden.toLowerCase()),forbidden);
    }

    private static final class OpaqueContinuationProvider implements ModelProvider{
        private final String privateArtifact;
        private final boolean emptyAllowed;
        private final boolean callBound;
        private final AtomicInteger calls=new AtomicInteger();
        private final AtomicInteger toolResponsesObserved=new AtomicInteger();
        private final java.util.ArrayList<String> observedToolIds=new java.util.ArrayList<>();

        private OpaqueContinuationProvider(String privateArtifact,boolean emptyAllowed,
                boolean callBound){
            this.privateArtifact=privateArtifact;
            this.emptyAllowed=emptyAllowed;
            this.callBound=callBound;
        }

        @Override public ModelResponse chat(List<AgentMessage> messages,List<ToolDefinition> tools){
            if(calls.getAndIncrement()==0){
                if(privateArtifact.isEmpty()&&!emptyAllowed)return ModelResponse.providerError(
                        ModelResponse.ProviderErrorKind.PROVIDER_PRIVATE_CONTINUATION_PROTOCOL_FAILURE);
                List<ToolCall> batch=callBound||privateArtifact.contains("turn-artifact")
                        ?List.of(new ToolCall("read_file",Map.of("path","a.txt"),"opaque-call-a"),
                                new ToolCall("read_file",Map.of("path","b.txt"),"opaque-call-b"))
                        :List.of(new ToolCall("list_files",Map.of("path","."),"opaque-call-a"));
                return ModelResponse.toolCallBatchResponse("inspect",batch,
                        ProviderResponseEnvelope.inspect(batch.stream()
                                .map(ToolCall::providerCallId).toList(),false),true);
            }
            List<AgentMessage> actions=messages.stream().filter(message->
                    message.type()==AgentMessage.MessageType.ACTION).toList();
            assertEquals(1,actions.size(),"one assistant turn must represent the Tool batch");
            assertTrue(actions.getFirst().toolCallJson().contains(
                    "x_paicli_private_continuation"));
            if(!privateArtifact.isEmpty())
                assertFalse(actions.getFirst().toString().contains(privateArtifact));
            observedToolIds.addAll(messages.stream().filter(message->
                    message.type()==AgentMessage.MessageType.OBSERVATION)
                    .map(AgentMessage::toolCallId).toList());
            toolResponsesObserved.set(observedToolIds.size());
            return ModelResponse.finalAnswer("done");
        }
    }

    @Test
    void multipleToolCallsThenFinal() throws IOException {
        Files.writeString(workspace.resolve("data.txt"), "hello world");

        var mock = new MockModelProvider(List.of(
                ModelResponse.toolCallResponse("listing",
                        new ToolCall("list_files", Map.of("path", "."))),
                ModelResponse.toolCallResponse("reading",
                        new ToolCall("read_file", Map.of("path", "data.txt"))),
                ModelResponse.finalAnswer("The file contains: hello world")
        ));
        var loop = new AgentLoop(mock, registry);

        AgentResult result = loop.run("read data.txt");

        assertTrue(result.completed());
        assertEquals(3, result.iterations());
    }

    @Test
    void oneAssistantBatchExecutesThreeReadsInOrderAndContinuesWithAllResults()
            throws IOException {
        Files.writeString(workspace.resolve("README.md"), "readme purpose");
        Files.writeString(workspace.resolve("notes.txt"), "notes purpose");
        Files.createDirectories(workspace.resolve("docs"));
        Files.writeString(workspace.resolve("docs/guide.md"), "guide purpose");
        AtomicInteger providerCalls=new AtomicInteger();
        AtomicReference<List<AgentMessage>> continuation=new AtomicReference<>();
        ModelProvider provider=(messages,tools)->{
            if(providerCalls.getAndIncrement()==0)return ModelResponse.toolCallBatchResponse(
                    "Read the three user files",List.of(
                    new ToolCall("read_file",Map.of("path","README.md"),"call-readme"),
                    new ToolCall("read_file",Map.of("path","notes.txt"),"call-notes"),
                    new ToolCall("read_file",Map.of("path","docs/guide.md"),"call-guide")),
                    ProviderResponseEnvelope.inspect(
                            List.of("call-readme","call-notes","call-guide"),false),false);
            continuation.set(List.copyOf(messages));
            return ModelResponse.finalAnswer("README, notes, and guide were grounded");
        };
        List<String> requested=new java.util.ArrayList<>();
        List<String> batchFacts=new java.util.ArrayList<>();
        AgentLoopObserver observer=new AgentLoopObserver(){
            @Override public void toolRequested(String task,int iteration,ToolRequestFact fact){
                requested.add(fact.toolCallId()+":"+fact.batchIndex()+"/"+fact.batchSize());
            }
            @Override public void toolBatchCompleted(String task,int iteration,
                    ToolBatchFact fact,int succeeded,int failed){
                batchFacts.add(fact.size()+":"+succeeded+":"+failed);
            }
        };
        AgentResult result=new AgentLoop(provider,registry,5,null,null,null,null,null,null,
                observer).run("describe files");
        assertTrue(result.completed());
        assertEquals(List.of("call-readme:1/3","call-notes:2/3","call-guide:3/3"),requested);
        assertEquals(List.of("3:3:0"),batchFacts);
        List<AgentMessage> sent=continuation.get();
        assertEquals(1,sent.stream().filter(message->
                message.type()==AgentMessage.MessageType.ACTION).count());
        assertEquals(List.of("call-readme","call-notes","call-guide"),sent.stream()
                .filter(message->message.type()==AgentMessage.MessageType.OBSERVATION)
                .map(AgentMessage::toolCallId).toList());
        assertTrue(sent.stream().filter(message->
                message.type()==AgentMessage.MessageType.ACTION).findFirst().orElseThrow()
                .toolCallJson().stripLeading().startsWith("["));
    }

    @Test
    void failedReadInBatchStillProducesItsErrorAndExecutesOtherIndependentReads()
            throws IOException {
        Files.writeString(workspace.resolve("README.md"),"readme");
        Files.writeString(workspace.resolve("notes.txt"),"notes");
        AtomicReference<List<AgentMessage>> continuation=new AtomicReference<>();
        AtomicInteger providerCalls=new AtomicInteger();
        ModelProvider provider=(messages,tools)->{
            if(providerCalls.getAndIncrement()==0)return ModelResponse.toolCallBatchResponse(
                    "read batch",List.of(
                    new ToolCall("read_file",Map.of("path","README.md"),"call-a"),
                    new ToolCall("read_file",Map.of("path","missing.md"),"call-b"),
                    new ToolCall("read_file",Map.of("path","notes.txt"),"call-c")),
                    ProviderResponseEnvelope.inspect(List.of("call-a","call-b","call-c"),false),false);
            continuation.set(List.copyOf(messages));
            return ModelResponse.finalAnswer("remaining evidence was available");
        };
        assertTrue(new AgentLoop(provider,registry,5).run("read").completed());
        List<AgentMessage> observations=continuation.get().stream().filter(message->
                message.type()==AgentMessage.MessageType.OBSERVATION).toList();
        assertEquals(3,observations.size());
        assertTrue(observations.get(1).content().startsWith("ERROR:"));
        assertTrue(observations.get(2).content().contains("notes"));
    }

    @Test
    void policyDeniedBatchCanContinueWithLegalToolThenFinal() {
        PolicyBatchFixture fixture=policyBatchFixture();
        AtomicInteger turns=new AtomicInteger();
        AtomicReference<List<AgentMessage>> deniedContinuation=new AtomicReference<>();
        String sentinel="unsafe-policy-argument-7a41";
        ModelProvider provider=(messages,tools)->switch(turns.getAndIncrement()){
            case 0->deniedBatch(sentinel,"denied-a","denied-b");
            case 1->{deniedContinuation.set(List.copyOf(messages));
                yield ModelResponse.toolCallResponse("legal retry",
                        new ToolCall("list_files",Map.of(),"legal-call"));}
            default->ModelResponse.finalAnswer("completed after safe retry");
        };

        AgentResult result=new AgentLoop(provider,fixture.registry(),5).run("inspect safely");

        assertTrue(result.completed());
        assertEquals("completed after safe retry",result.answer());
        assertEquals(1,fixture.safeEffects().get());
        assertEquals(0,fixture.deniedEffects().get());
        List<AgentMessage> continuation=deniedContinuation.get();
        AgentMessage action=continuation.stream().filter(message->
                message.type()==AgentMessage.MessageType.ACTION).findFirst().orElseThrow();
        assertFalse(action.toolCallJson().contains(sentinel));
        List<AgentMessage> observations=continuation.stream().filter(message->
                message.type()==AgentMessage.MessageType.OBSERVATION).toList();
        assertEquals(List.of("denied-a","denied-b"),observations.stream()
                .map(AgentMessage::toolCallId).toList());
        assertTrue(observations.getFirst().content().contains(
                "RELATED_BATCH_MEMBER_NOT_EXECUTED"));
        assertTrue(observations.get(1).content().contains(
                SafePreflightRejectionDiagnostic.SCHEMA_VERSION));
        assertTrue(observations.stream().noneMatch(message->message.content().contains(sentinel)));
    }

    @Test
    void policyDeniedBatchCanContinueDirectlyToFinal() {
        PolicyBatchFixture fixture=policyBatchFixture();
        AtomicInteger turns=new AtomicInteger();
        ModelProvider provider=(messages,tools)->turns.getAndIncrement()==0
                ?deniedBatch("unsafe","denied-a","denied-b")
                :ModelResponse.finalAnswer("safe final");

        AgentResult result=new AgentLoop(provider,fixture.registry(),3).run("inspect safely");

        assertTrue(result.completed());
        assertEquals("safe final",result.answer());
        assertEquals(0,fixture.safeEffects().get());
        assertEquals(0,fixture.deniedEffects().get());
    }

    @Test
    void repeatedPolicyDeniedBatchesUseExistingIterationLimit() {
        PolicyBatchFixture fixture=policyBatchFixture();
        AtomicInteger turns=new AtomicInteger();
        ModelProvider provider=(messages,tools)->{
            int turn=turns.incrementAndGet();
            return deniedBatch("unsafe-"+turn,"safe-"+turn,"denied-"+turn);
        };

        AgentResult result=new AgentLoop(provider,fixture.registry(),2).run("inspect safely");

        assertFalse(result.completed());
        assertEquals(2,result.iterations());
        assertEquals(2,turns.get());
        assertEquals(0,fixture.safeEffects().get());
        assertEquals(0,fixture.deniedEffects().get());
    }

    @Test
    void duplicateToolBindingRemainsFatalBeforeAnyBatchEffect() {
        PolicyBatchFixture fixture=policyBatchFixture();
        ModelProvider provider=(messages,tools)->ModelResponse.toolCallBatchResponse("corrupt",
                List.of(new ToolCall("list_files",Map.of(),"duplicate-call"),
                        new ToolCall("list_files",Map.of(),"duplicate-call")),
                ProviderResponseEnvelope.inspect(
                        List.of("duplicate-call","duplicate-call"),false),false);

        AgentLoop.ProviderProtocolException failure=assertThrows(
                AgentLoop.ProviderProtocolException.class,
                ()->new AgentLoop(provider,fixture.registry(),3).run("inspect safely"));

        assertEquals("TOOL_CALL_ID_DUPLICATE",failure.code());
        assertEquals(0,fixture.safeEffects().get());
        assertEquals(0,fixture.deniedEffects().get());
    }

    @Test
    void singleToolSafetyDenialStillReturnsObservationAndContinues() {
        AtomicInteger turns=new AtomicInteger();
        ModelProvider provider=(messages,tools)->turns.getAndIncrement()==0
                ?ModelResponse.toolCallResponse("unsafe read",
                        new ToolCall("read_file",Map.of("path","../outside.txt"),
                                "single-denied"))
                :ModelResponse.finalAnswer("used safe feedback");

        AgentResult result=new AgentLoop(provider,registry,3).run("inspect safely");

        assertTrue(result.completed());
        assertEquals(2,result.iterations());
        assertTrue(result.messages().stream().anyMatch(message->
                message.startsWith("OBSERVATION:")&&message.contains("ERROR")));
    }

    @Test
    void mixedReadWriteBatchProducesTypedNonExecutionBeforeOneContinuation() {
        registry.register(new RunCommandTool(policy));
        AtomicInteger providerTurns=new AtomicInteger();
        AtomicReference<List<AgentMessage>> continuation=new AtomicReference<>();
        ModelProvider provider=(messages,tools)->{
            if(providerTurns.getAndIncrement()>0){continuation.set(List.copyOf(messages));
                return ModelResponse.finalAnswer("serialized");}
            return ModelResponse.toolCallBatchResponse("mixed",List.of(
                new ToolCall("list_files",Map.of("path","."),"call-read"),
                new ToolCall("run_command",Map.of("command","echo should-not-run"),
                        "call-write")),
                ProviderResponseEnvelope.inspect(List.of("call-read","call-write"),false),false);
        };
        AtomicInteger requested=new AtomicInteger();
        AgentLoopObserver observer=new AgentLoopObserver(){
            @Override public void toolRequested(String task,int iteration,ToolRequestFact fact){
                requested.incrementAndGet();
            }
        };
        AgentResult result=new AgentLoop(provider,registry,3,null,null,null,null,null,null,observer)
                .run("mixed");
        assertTrue(result.completed());
        assertFalse(Files.exists(workspace.resolve("created.txt")));
        assertEquals(0,requested.get(),"batch rejection must precede every Tool request");
        List<AgentMessage> observations=continuation.get().stream().filter(message->
                message.type()==AgentMessage.MessageType.OBSERVATION).toList();
        assertEquals(List.of("call-read","call-write"),observations.stream()
                .map(AgentMessage::toolCallId).toList());
        assertTrue(observations.stream().allMatch(message->message.content().contains(
                SharedToolBatchPolicy.NOT_EXECUTED_CODE)));
    }

    @Test
    void cancellationStopsRemainingBatchMembersAndMarksThemNotExecuted() {
        AtomicInteger executions=new AtomicInteger();AtomicBoolean cancelled=new AtomicBoolean();
        ToolRegistry local=new ToolRegistry();
        local.register(new Tool(){
            public String name(){return "read_file";}
            public String description(){return "read";}
            public ToolDefinition toDefinition(){return new ToolDefinition("read_file","read",
                    new ObjectMapper().createObjectNode());}
            public ToolResult execute(ToolCall call){executions.incrementAndGet();
                cancelled.set(true);return ToolResult.success("read");}
        });
        ModelProvider provider=(messages,tools)->ModelResponse.toolCallBatchResponse("batch",
                List.of(new ToolCall("read_file",Map.of("path","a"),"call-a"),
                        new ToolCall("read_file",Map.of("path","b"),"call-b"),
                        new ToolCall("read_file",Map.of("path","c"),"call-c")),
                ProviderResponseEnvelope.inspect(List.of("call-a","call-b","call-c"),false),false);
        List<String> notExecuted=new java.util.ArrayList<>();
        AgentLoopObserver observer=new AgentLoopObserver(){
            @Override public void toolCancelled(String task,int iteration,ToolRequestFact request,
                    String reason){notExecuted.add(request.toolCallId());}
        };
        AgentLoopControl control=new AgentLoopControl(){
            @Override public com.paicli.runtime.CancellationToken cancellationToken(){
                return new com.paicli.runtime.CancellationToken(){
                    public boolean cancellationRequested(){return cancelled.get();}
                    public boolean forceRequested(){return false;}
                };}
        };
        assertThrows(com.paicli.runtime.CancellationToken.TaskCancellationException.class,
                ()->new AgentLoop(provider,local,3,null,null,null,null,null,null,observer,control)
                        .run("cancel batch"));
        assertEquals(1,executions.get());
        assertEquals(List.of("call-b","call-c"),notExecuted);
    }

    @Test
    void listFilesInternalPolicyChangesTheReadEvidenceSignature() {
        AtomicInteger executions=new AtomicInteger();AtomicInteger providerCalls=new AtomicInteger();
        AtomicReference<List<AgentMessage>> finalRequest=new AtomicReference<>();
        ToolRegistry local=new ToolRegistry();
        local.register(new Tool(){
            public String name(){return "list_files";}
            public String description(){return "list";}
            public ToolDefinition toDefinition(){return new ToolDefinition("list_files","list",
                    new ObjectMapper().createObjectNode());}
            public ToolResult execute(ToolCall call){executions.incrementAndGet();return
                    ToolResult.success(Boolean.TRUE.equals(call.arguments().get("include_internal"))
                            ?"README.md\n.paicli/":"README.md");}
        });
        ModelProvider provider=(messages,tools)->switch(providerCalls.getAndIncrement()){
            case 0->ModelResponse.toolCallResponse("user files",new ToolCall("list_files",
                    Map.of("path","."),"call-hidden"));
            case 1->ModelResponse.toolCallResponse("internal files",new ToolCall("list_files",
                    Map.of("path",".","include_internal",true),"call-visible"));
            default->{finalRequest.set(List.copyOf(messages));yield ModelResponse.finalAnswer("done");}
        };
        assertTrue(new AgentLoop(provider,local,5)
                .run("inspect and list .paicli internal state").completed());
        assertEquals(2,executions.get(),"include_internal must prevent cache aliasing");
        assertTrue(finalRequest.get().stream().filter(message->
                message.type()==AgentMessage.MessageType.OBSERVATION)
                .anyMatch(message->message.content().contains(".paicli/")));
    }

    @Test
    void modelCannotOverrideUsersExplicitPaiCliExclusion() {
        for(ToolCall call:List.of(
                new ToolCall("list_files",Map.of("path",".","include_internal",true),
                        "call-list"),
                new ToolCall("read_file",Map.of("path",".paicli/state.db",
                        "include_internal",true),"call-read"),
                new ToolCall("grep",Map.of("path",".paicli","pattern","state",
                        "include_internal",true),"call-grep"))){
            ModelProvider provider=(messages,tools)->ModelResponse.toolCallResponse(
                    "internal",call);
            var result=new AgentLoop(provider,registry,3).run(
                            "list user files and ignore .paicli internal state");
            assertFalse(result.completed(),"repeated denial must exhaust the existing iteration limit");
            assertEquals(3,result.iterations());
        }
    }

    @Test
    void includeInternalWithoutExplicitUserGoalIntentIsRejected() {
        ModelProvider provider=(messages,tools)->ModelResponse.toolCallResponse(
                "internal",new ToolCall("read_file",Map.of(
                        "path",".paicli/state.db","include_internal",true),"call-read"));

        assertFalse(new AgentLoop(provider,registry,3).run("inspect the project").completed());
    }

    @Test
    void preservesProviderToolIdentityReusesIdenticalReadEvidenceAndStopsNoProgress() {
        AtomicInteger providerCalls=new AtomicInteger();
        AtomicInteger filesystemCalls=new AtomicInteger();
        AtomicReference<List<AgentMessage>> continuation=new AtomicReference<>();
        com.paicli.provider.ModelProvider provider=(messages,tools)->{
            int call=providerCalls.incrementAndGet();
            if(call==2)continuation.set(List.copyOf(messages));
            return ModelResponse.toolCallResponse("inspect workspace",
                    new ToolCall("list_files",Map.of("path","."),"provider-call-"+call));
        };
        ToolRegistry local=new ToolRegistry();
        local.register(new Tool(){
            @Override public String name(){return "list_files";}
            @Override public String description(){return "list";}
            @Override public ToolDefinition toDefinition(){return new ToolDefinition(
                    "list_files","list",new ObjectMapper().createObjectNode());}
            @Override public ToolResult execute(ToolCall call){filesystemCalls.incrementAndGet();
                return ToolResult.success("README.md\nnotes.txt");}
        });
        AgentLoop loop=new AgentLoop(provider,local,10);
        AgentLoop.AgentNoProgressException failure=assertThrows(
                AgentLoop.AgentNoProgressException.class,()->loop.run("list files"));
        assertTrue(failure.getMessage().contains("IDENTICAL_TOOL_LOOP"));
        assertEquals(1,filesystemCalls.get(),"cached replay must not touch the filesystem");
        assertEquals(3,providerCalls.get(),"one bounded recovery precedes termination");
        AgentMessage action=continuation.get().stream()
                .filter(message->message.type()==AgentMessage.MessageType.ACTION).findFirst().orElseThrow();
        AgentMessage observation=continuation.get().stream()
                .filter(message->message.type()==AgentMessage.MessageType.OBSERVATION).findFirst().orElseThrow();
        assertEquals("provider-call-1",action.toolCallId());
        assertTrue(action.toolCallJson().contains("provider-call-1"));
        assertEquals(action.toolCallId(),observation.toolCallId());
    }

    @Test
    void changedReadArgumentsAreNotReused() {
        AtomicInteger calls=new AtomicInteger();
        com.paicli.provider.ModelProvider provider=(messages,tools)->switch(calls.getAndIncrement()){
            case 0->ModelResponse.toolCallResponse("first",
                    new ToolCall("list_files",Map.of("path","."),"call-a"));
            case 1->ModelResponse.toolCallResponse("second",
                    new ToolCall("list_files",Map.of("path","docs"),"call-b"));
            default->ModelResponse.finalAnswer("done");
        };
        AtomicInteger executions=new AtomicInteger();
        ToolRegistry local=new ToolRegistry();
        local.register(new Tool(){
            @Override public String name(){return "list_files";}
            @Override public String description(){return "list";}
            @Override public ToolDefinition toDefinition(){return new ToolDefinition(
                    "list_files","list",new ObjectMapper().createObjectNode());}
            @Override public ToolResult execute(ToolCall call){executions.incrementAndGet();
                return ToolResult.success(String.valueOf(call.arguments().get("path")));}
        });
        assertTrue(new AgentLoop(provider,local,5).run("list").completed());
        assertEquals(2,executions.get());
    }

    @Test void successfulWriteInvalidatesEarlierReadEvidence(){
        AtomicReference<String> content=new AtomicReference<>("before");
        AtomicInteger reads=new AtomicInteger();
        ToolRegistry local=new ToolRegistry();
        local.register(new Tool(){
            public String name(){return "read_file";}
            public String description(){return "read";}
            public ToolDefinition toDefinition(){return new ToolDefinition(
                    "read_file","read",new ObjectMapper().createObjectNode());}
            public ToolResult execute(ToolCall call){reads.incrementAndGet();
                return ToolResult.success(content.get());}
        });
        local.register(new Tool(){
            public String name(){return "write_file";}
            public String description(){return "write";}
            public ToolDefinition toDefinition(){return new ToolDefinition(
                    "write_file","write",new ObjectMapper().createObjectNode());}
            public ToolResult execute(ToolCall call){content.set("after");
                return ToolResult.success("written");}
        });
        AtomicInteger calls=new AtomicInteger();
        ModelProvider provider=(messages,tools)->switch(calls.getAndIncrement()){
            case 0->ModelResponse.toolCallResponse("read",new ToolCall(
                    "read_file",Map.of("path","README.md"),"read-before"));
            case 1->ModelResponse.toolCallResponse("write",new ToolCall(
                    "write_file",Map.of("path","README.md","content","after"),"write"));
            case 2->ModelResponse.toolCallResponse("reread",new ToolCall(
                    "read_file",Map.of("path","README.md"),"read-after"));
            default->ModelResponse.finalAnswer("done");
        };
        assertTrue(new AgentLoop(provider,local,8).run("update then read").completed());
        assertEquals(2,reads.get(),"post-write read must not reuse pre-write evidence");
    }

    @Test void failedEffectfulToolInvalidatesEarlierReadEvidence(){
        AtomicReference<String> content=new AtomicReference<>("before");
        AtomicInteger reads=new AtomicInteger();
        ToolRegistry local=new ToolRegistry();
        local.register(new Tool(){
            public String name(){return "read_file";}
            public String description(){return "read";}
            public ToolDefinition toDefinition(){return new ToolDefinition(
                    "read_file","read",new ObjectMapper().createObjectNode());}
            public ToolResult execute(ToolCall call){reads.incrementAndGet();
                return ToolResult.success(content.get());}
        });
        local.register(new Tool(){
            public String name(){return "run_command";}
            public String description(){return "run";}
            public ToolDefinition toDefinition(){return new ToolDefinition(
                    "run_command","run",new ObjectMapper().createObjectNode());}
            public ToolResult execute(ToolCall call){content.set("changed-before-exit-1");
                return ToolResult.error("exit 1");}
        });
        AtomicInteger calls=new AtomicInteger();
        ModelProvider provider=(messages,tools)->switch(calls.getAndIncrement()){
            case 0->ModelResponse.toolCallResponse("read",new ToolCall(
                    "read_file",Map.of("path","README.md"),"read-before"));
            case 1->ModelResponse.toolCallResponse("mutate then fail",new ToolCall(
                    "run_command",Map.of("command","mutate then exit 1"),"run-failed"));
            case 2->ModelResponse.toolCallResponse("reread",new ToolCall(
                    "read_file",Map.of("path","README.md"),"read-after"));
            default->ModelResponse.finalAnswer("done");
        };
        assertTrue(new AgentLoop(provider,local,8).run("read after failed effect").completed());
        assertEquals(2,reads.get(),"a failed effectful call must invalidate prior reads");
    }

    @Test void changedReadProgressResetsTheBoundedRecoveryMarker(){
        AtomicInteger providerCalls=new AtomicInteger();
        ModelProvider provider=(messages,tools)->switch(providerCalls.getAndIncrement()){
            case 0,1,3->ModelResponse.toolCallResponse("read root",new ToolCall(
                    "list_files",Map.of("path","."),"root-"+providerCalls.get()));
            case 2->ModelResponse.toolCallResponse("read docs",new ToolCall(
                    "list_files",Map.of("path","docs"),"docs"));
            default->ModelResponse.finalAnswer("done");
        };
        AtomicInteger executions=new AtomicInteger();
        ToolRegistry local=new ToolRegistry();
        local.register(new Tool(){
            public String name(){return "list_files";}
            public String description(){return "list";}
            public ToolDefinition toDefinition(){return new ToolDefinition(
                    "list_files","list",new ObjectMapper().createObjectNode());}
            public ToolResult execute(ToolCall call){executions.incrementAndGet();
                return ToolResult.success(String.valueOf(call.arguments().get("path")));}
        });
        AgentResult result=new AgentLoop(provider,local,8).run("list");
        assertTrue(result.completed());
        assertEquals(2,executions.get(),"A and B execute; repeated A calls reuse evidence");
    }

    @Test
    void maxIterationsReached() {
        // Mock that always returns tool calls, never final
        var mock = new MockModelProvider(List.of(
                ModelResponse.toolCallResponse("loop",
                        new ToolCall("list_files", Map.of("path", "."))),
                ModelResponse.toolCallResponse("loop",
                        new ToolCall("list_files", Map.of("path", "."))),
                ModelResponse.toolCallResponse("loop",
                        new ToolCall("list_files", Map.of("path", ".")))
        ));
        var loop = new AgentLoop(mock, registry, 2);

        AgentResult result = loop.run("keep going");

        assertFalse(result.completed());
        assertNull(result.answer());
        assertEquals(2, result.iterations());
    }

    @Test
    void toolErrorBecomesObservation() {
        var mock = new MockModelProvider(List.of(
                ModelResponse.toolCallResponse("trying",
                        new ToolCall("read_file", Map.of("path", "nonexistent.txt"))),
                ModelResponse.finalAnswer("File not found")
        ));
        var loop = new AgentLoop(mock, registry);

        AgentResult result = loop.run("read nonexistent");

        assertTrue(result.completed());
        assertTrue(result.messages().stream()
                .anyMatch(m -> m.startsWith("OBSERVATION:") && m.contains("ERROR")));
    }

    @Test
    void unknownToolBecomesErrorObservation() {
        var mock = new MockModelProvider(List.of(
                ModelResponse.toolCallResponse("trying",
                        new ToolCall("nonexistent_tool", Map.of())),
                ModelResponse.finalAnswer("Tool not available")
        ));
        var loop = new AgentLoop(mock, registry);

        AgentResult result = loop.run("do something");

        assertTrue(result.completed());
        assertTrue(result.messages().stream()
                .anyMatch(m -> m.startsWith("OBSERVATION:") && m.contains("Unknown tool")));
    }

    @Test
    void emptyGoalStillWorks() {
        var mock = new MockModelProvider(List.of(
                ModelResponse.finalAnswer("Please provide a task.")
        ));
        var loop = new AgentLoop(mock, registry);

        AgentResult result = loop.run("");

        assertTrue(result.completed());
        assertEquals("Please provide a task.", result.answer());
    }


    @Test
    void providerProtocolErrorDoesNotExecuteAnyTool() {
        boolean[] executed = {false};
        ToolRegistry localRegistry = new ToolRegistry();
        localRegistry.register(new Tool() {
            @Override
            public String name() {
                return "list_files";
            }

            @Override
            public String description() {
                return "record execution";
            }

            @Override
            public ToolDefinition toDefinition() {
                return new ToolDefinition("list_files", "record execution",
                        new ObjectMapper().createObjectNode());
            }

            @Override
            public ToolResult execute(ToolCall call) {
                executed[0] = true;
                return ToolResult.success("executed");
            }
        });
        var mock = new MockModelProvider(List.of(
                ModelResponse.protocolError(ProviderResponseEnvelope.inspect(
                        java.util.stream.IntStream.rangeClosed(0,8)
                                .mapToObj(i->"call-"+i).toList(), false))
        ));
        var loop = new AgentLoop(mock, localRegistry);

        AgentResult result=loop.run("malformed provider response");
        assertTrue(result.completed());
        assertTrue(result.messages().stream().anyMatch(message->
                message.contains("PROTOCOL_CORRECTION")));
        assertFalse(executed[0]);
    }

    @Test
    void providerProtocolErrorPersistsOnlyBoundedDiagnosticAndNoToolCall() throws Exception {
        String unsafeIdentity = "secret-token value";
        var mock = new MockModelProvider(List.of(ModelResponse.protocolError(
                ProviderResponseEnvelope.inspect(List.of(unsafeIdentity, "call-2"), false))));
        try (StateStore state = new StateStore(workspace)) {
            AgentLoop loop = new AgentLoop(mock, registry, 3, null, null,
                    state, "task-protocol-001", "ollama", "model-a");
            AgentResult result=loop.run("protocol safety");
            assertTrue(result.completed());

            var task = state.tasks.findById("task-protocol-001").orElseThrow();
            assertEquals(com.paicli.state.TaskStore.STATUS_DONE, task.status());
            assertNull(task.lastError());
            assertTrue(state.toolCalls.findByTask("task-protocol-001").isEmpty());
            var checkpoint = state.checkpoints.loadLatest("task-protocol-001").orElseThrow();
            assertEquals("DONE",checkpoint.status());
            assertFalse(checkpoint.messages().toString().contains(unsafeIdentity));
        }
    }

    private PolicyBatchFixture policyBatchFixture() {
        AtomicInteger safeEffects=new AtomicInteger();
        AtomicInteger deniedEffects=new AtomicInteger();
        ToolRegistry local=new ToolRegistry();
        local.register(new Tool(){
            @Override public String name(){return "list_files";}
            @Override public String description(){return "safe synthetic read";}
            @Override public ToolDefinition toDefinition(){return new ToolDefinition(
                    name(),description(),new ObjectMapper().createObjectNode());}
            @Override public ToolResult execute(ToolCall call){
                safeEffects.incrementAndGet();return ToolResult.success("safe");}
        });
        local.register(new Tool(){
            @Override public String name(){return "read_file";}
            @Override public String description(){return "policy denied synthetic read";}
            @Override public ToolDefinition toDefinition(){return new ToolDefinition(
                    name(),description(),new ObjectMapper().createObjectNode());}
            @Override public ToolResult preflightCall(ToolCall call){
                return ToolResult.error("unsafe argument "+call.arguments().get("path"));}
            @Override public ToolResult execute(ToolCall call){
                deniedEffects.incrementAndGet();return ToolResult.success("unexpected");}
        });
        return new PolicyBatchFixture(local,safeEffects,deniedEffects);
    }

    private static ModelResponse deniedBatch(String unsafe,String safeId,String deniedId) {
        return ModelResponse.toolCallBatchResponse("policy batch",List.of(
                new ToolCall("list_files",Map.of(),safeId),
                new ToolCall("read_file",Map.of("path",unsafe),deniedId)),
                ProviderResponseEnvelope.inspect(List.of(safeId,deniedId),false),false);
    }

    private record PolicyBatchFixture(ToolRegistry registry,AtomicInteger safeEffects,
            AtomicInteger deniedEffects) { }

    // ── Pre-execution Approval Gate tests ──

    private AgentLoop loopWithApproval(String approvalMode, MockModelProvider mock) throws IOException {
        Config config = new Config(workspace.toString(), 30, 50000, 200000, approvalMode, 10000);
        ApprovalPolicy approval = new ApprovalPolicy(config, "test-task");
        AuditLog auditLog = new AuditLog(workspace.resolve(".paicli").resolve("audit"), "test-task");

        ToolRegistry reg = new ToolRegistry();
        SafetyPolicy sp = new SafetyPolicy(workspace);
        reg.register(new ListFilesTool(sp));
        reg.register(new ReadFileTool(sp));
        reg.register(new RunCommandTool(sp));
        reg.register(new WriteFileTool(sp));

        return new AgentLoop(mock, reg, 10, approval, auditLog);
    }

    @Nested
    class ApprovalGateReadOnly {

        @Test
        void allowsListFiles() throws IOException {
            var mock = new MockModelProvider(List.of(
                    ModelResponse.toolCallResponse("check",
                            new ToolCall("list_files", Map.of("path", "."))),
                    ModelResponse.finalAnswer("done")
            ));
            var loop = loopWithApproval(Config.MODE_READ_ONLY, mock);
            AgentResult result = loop.run("list files");

            assertTrue(result.completed());
            assertFalse(result.messages().stream()
                    .anyMatch(m -> m.contains("DENIED")));
        }

        @Test
        void deniesRunCommand() throws IOException {
            var mock = new MockModelProvider(List.of(
                    ModelResponse.toolCallResponse("run",
                            new ToolCall("run_command", Map.of("command", "echo hi"))),
                    ModelResponse.finalAnswer("blocked")
            ));
            var loop = loopWithApproval(Config.MODE_READ_ONLY, mock);
            AgentResult result = loop.run("run a command");

            assertTrue(result.completed());
            assertTrue(result.messages().stream()
                    .anyMatch(m -> m.contains("DENIED")));
            // Verify tool was NOT actually executed (no "hi" in output)
            assertFalse(result.messages().stream()
                    .anyMatch(m -> m.contains("hi") && m.startsWith("OBSERVATION:")));
        }

        @Test
        void deniesWriteFile() throws IOException {
            var mock = new MockModelProvider(List.of(
                    ModelResponse.toolCallResponse("write",
                            new ToolCall("write_file", Map.of("path", "x.txt", "content", "data"))),
                    ModelResponse.finalAnswer("blocked")
            ));
            var loop = loopWithApproval(Config.MODE_READ_ONLY, mock);
            AgentResult result = loop.run("write a file");

            assertTrue(result.messages().stream()
                    .anyMatch(m -> m.contains("DENIED")));
            assertFalse(Files.exists(workspace.resolve("x.txt")));
        }
    }

    @Nested
    class ApprovalGateWorkspaceWrite {

        @Test
        void allowsWriteFileNonOverwrite() throws IOException {
            var mock = new MockModelProvider(List.of(
                    ModelResponse.toolCallResponse("write",
                            new ToolCall("write_file", Map.of("path", "new.txt", "content", "hello"))),
                    ModelResponse.finalAnswer("written")
            ));
            var loop = loopWithApproval(Config.MODE_WORKSPACE_WRITE, mock);
            AgentResult result = loop.run("write file");

            assertTrue(result.completed());
            assertFalse(result.messages().stream().anyMatch(m -> m.contains("DENIED")));
        }

        @Test
        void deniesRunCommand() throws IOException {
            var mock = new MockModelProvider(List.of(
                    ModelResponse.toolCallResponse("run",
                            new ToolCall("run_command", Map.of("command", "whoami"))),
                    ModelResponse.finalAnswer("blocked")
            ));
            var loop = loopWithApproval(Config.MODE_WORKSPACE_WRITE, mock);
            AgentResult result = loop.run("run command");

            assertTrue(result.messages().stream().anyMatch(m -> m.contains("DENIED")));
        }
    }

    @Nested
    class ApprovalGateOnRequest {

        @Test
        void allowsLowRisk() throws IOException {
            var mock = new MockModelProvider(List.of(
                    ModelResponse.toolCallResponse("check",
                            new ToolCall("list_files", Map.of("path", "."))),
                    ModelResponse.finalAnswer("done")
            ));
            var loop = loopWithApproval(Config.MODE_ON_REQUEST, mock);
            AgentResult result = loop.run("list");

            assertFalse(result.messages().stream().anyMatch(m -> m.contains("DENIED") || m.contains("APPROVAL_REQUIRED")));
        }

        @Test
        void needsApprovalForHighRisk() throws IOException {
            var mock = new MockModelProvider(List.of(
                    ModelResponse.toolCallResponse("run",
                            new ToolCall("run_command", Map.of("command", "ls"))),
                    ModelResponse.finalAnswer("needs approval")
            ));
            var loop = loopWithApproval(Config.MODE_ON_REQUEST, mock);
            AgentResult result = loop.run("run command");

            assertTrue(result.messages().stream()
                    .anyMatch(m -> m.contains("APPROVAL_REQUIRED")));
            assertFalse(result.messages().stream()
                    .anyMatch(m -> m.startsWith("OBSERVATION:") && !m.contains("ERROR")));
        }
    }

    @Nested
    class ApprovalGateDeny {

        @Test
        void deniesEverything() throws IOException {
            var mock = new MockModelProvider(List.of(
                    ModelResponse.toolCallResponse("check",
                            new ToolCall("list_files", Map.of("path", "."))),
                    ModelResponse.finalAnswer("blocked")
            ));
            var loop = loopWithApproval(Config.MODE_DENY, mock);
            AgentResult result = loop.run("list files");

            assertTrue(result.messages().stream().anyMatch(m -> m.contains("DENIED")));
        }
    }

    @Nested
    class ApprovalGateAudit {

        @Test
        void denyAndNeedsApprovalWrittenToAuditLog() throws IOException {
            var mock = new MockModelProvider(List.of(
                    ModelResponse.toolCallResponse("run",
                            new ToolCall("run_command", Map.of("command", "id"))),
                    ModelResponse.toolCallResponse("write",
                            new ToolCall("write_file", Map.of("path", "f.txt", "content", "x"))),
                    ModelResponse.finalAnswer("done")
            ));
            var loop = loopWithApproval(Config.MODE_READ_ONLY, mock);
            loop.run("run and write");

            // Check audit log file exists and contains both decisions
            Path auditFile = workspace.resolve(".paicli").resolve("audit").resolve("audit.jsonl");
            assertTrue(Files.exists(auditFile));
            String content = Files.readString(auditFile);
            assertTrue(content.contains("DENY"));
            assertTrue(content.contains("run_command"));
            assertTrue(content.contains("write_file"));
        }
    }
}
