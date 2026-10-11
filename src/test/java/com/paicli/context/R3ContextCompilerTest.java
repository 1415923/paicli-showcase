package com.paicli.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.context.ContextModels.*;
import com.paicli.model.AgentMessage;
import com.paicli.model.ToolDefinition;
import com.paicli.runtime.PersistenceSanitizer;
import com.paicli.support.R3ContextTestFixtures;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class R3ContextCompilerTest {
    private final DeterministicContextCompiler compiler=new DeterministicContextCompiler();
    @Test void ctxIdentityIsDistinctFromR1GenerationAndStrictUuidV4(){
        var id=com.paicli.identity.CanonicalId.newId(com.paicli.identity.EntityFamily.CONTEXT);
        assertTrue(id.value().matches("ctx_[0-9a-f]{32}"));assertEquals(36,id.value().length());
        assertEquals(com.paicli.identity.EntityFamily.GENERATION,
                com.paicli.identity.CanonicalId.parse(id.value().replace("ctx_","gen_")).family());
        assertNotEquals(com.paicli.identity.EntityFamily.GENERATION,id.family());
    }
    @Test void identicalTypedSourcesHaveIdenticalOrderAndHash(){
        CompileRequest request=request(List.of(AgentMessage.userGoal("goal"),
                AgentMessage.observation("durable result")),List.of());
        CompiledContext first=compiler.compile(request),second=compiler.compile(request);
        assertEquals(first.sourceFingerprint(),second.sourceFingerprint());
        assertEquals(first.compiledHash(),second.compiledHash());
        assertEquals(first.segments().stream().map(Segment::contentHash).toList(),
                second.segments().stream().map(Segment::contentHash).toList());
        assertTrue(first.segments().stream().flatMap(s->s.references().stream())
                .noneMatch(reference->reference.type().startsWith("MESSAGE_")));
    }
    @Test void toolsAreCanonicalizedAndSecretsAreRedactedBeforeHash()throws Exception{
        var mapper=new ObjectMapper();
        var a=new ToolDefinition("z","z",mapper.readTree("{\"type\":\"object\"}"));
        var b=new ToolDefinition("a","a",mapper.readTree("{\"type\":\"object\"}"));
        var compiled=compiler.compile(request(
                List.of(AgentMessage.userGoal("Authorization: Bearer secret")),List.of(a,b)));
        assertEquals(List.of("a","z"),compiled.tools().stream().map(ToolDefinition::name).toList());
        assertTrue(compiled.messages().stream().noneMatch(message ->
                message.content().contains("secret")||String.valueOf(message.toolCallJson()).contains("secret")));
        assertTrue(compiled.segments().stream().noneMatch(segment->
                String.valueOf(segment.sanitizedPayload()).contains("secret")));
    }
    @Test void jsonObjectInsertionOrderDoesNotChangeToolHash()throws Exception{
        var mapper=new ObjectMapper();
        var first=new ToolDefinition("tool","description",
                mapper.readTree("{\"type\":\"object\",\"properties\":{\"b\":{\"type\":\"string\"},\"a\":{\"type\":\"integer\"}}}"));
        var second=new ToolDefinition("tool","description",
                mapper.readTree("{\"properties\":{\"a\":{\"type\":\"integer\"},\"b\":{\"type\":\"string\"}},\"type\":\"object\"}"));
        var left=compiler.compile(request(List.of(AgentMessage.userGoal("goal")),List.of(first)));
        var right=compiler.compile(request(List.of(AgentMessage.userGoal("goal")),List.of(second)));
        assertEquals(left.compiledHash(),right.compiledHash());
        assertEquals(left.sourceFingerprint(),right.sourceFingerprint());
    }
    @Test void arbitraryMessagesCannotBeCompilerAuthority(){
        var components=CompileRequest.class.getRecordComponents();assertEquals(3,components.length);
        assertEquals(ContextInvocation.class,components[0].getType());
        assertEquals(ContextSourceSet.class,components[1].getType());
        assertEquals(List.class,components[2].getType());
    }
    @Test void budgetKnownUnknownOverflowAndCompactionAreTyped(){
        ContextBudgetResolver resolver=new ContextBudgetResolver();
        var messages=List.of(AgentMessage.userGoal("x".repeat(300)));
        assertTrue(resolver.resolve(List.of(),List.of(),
                new CapabilityProfile("known",4096,LimitProvenance.CONFIGURED_LIMIT,1,1),false)
                .inputEstimate()>=ContextBudgetResolver.PROTOCOL_OVERHEAD_TOKENS);
        assertEquals(Budget.Decision.CONTEXT_BUDGET_UNKNOWN,resolver.resolve(messages,List.of(),
                new CapabilityProfile("unknown",null,LimitProvenance.UNKNOWN_LIMIT,1,1),true).decision());
        assertEquals(Budget.Decision.COMPACTION_REQUIRED,resolver.resolve(messages,List.of(),
                new CapabilityProfile("small",10,LimitProvenance.CONFIGURED_LIMIT,1,1),true).decision());
        assertEquals(Budget.Decision.CONTEXT_BUDGET_EXCEEDED,resolver.resolve(messages,List.of(),
                new CapabilityProfile("small",10,LimitProvenance.MODEL_CATALOG_LIMIT,1,1),false).decision());
        assertThrows(ArithmeticException.class,()->new Budget(Integer.MAX_VALUE,Integer.MAX_VALUE,
                Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,LimitProvenance.CONFIGURED_LIMIT,
                EstimateProvenance.DERIVED_ESTIMATE,Budget.Decision.READY).total());
    }
    @Test void capabilityLimitPrecedenceIsReportedConfiguredCatalogThenUnknown(){
        var resolver=new ProviderContextCapabilityResolver();
        assertEquals(LimitProvenance.PROVIDER_REPORTED_LIMIT,
                resolver.resolve("provider","model",8192,4096,2048).provenance());
        assertEquals(LimitProvenance.CONFIGURED_LIMIT,
                resolver.resolve("provider","model",null,4096,2048).provenance());
        assertEquals(LimitProvenance.MODEL_CATALOG_LIMIT,
                resolver.resolve("provider","model",null,null,2048).provenance());
        assertEquals(LimitProvenance.UNKNOWN_LIMIT,
                resolver.resolve("openai-compatible","unknown",null,null,null).provenance());
    }
    private CompileRequest request(List<AgentMessage> messages,List<ToolDefinition> tools){
        var invocation=new ContextInvocation(identity(),OperationKind.CHAT,"1","evt_0",List.of(),null,
                identity().planRevision(),identity().steeringRevision(),"mock","model",
                capability(),false,DeterministicContextCompiler.COMPILER_VERSION,
                DeterministicContextCompiler.POLICY_VERSION);
        var sanitizer=new PersistenceSanitizer();var sources=new ArrayList<ContextSource>();
        for(int i=0;i<messages.size();i++){
            AgentMessage message=messages.get(i);
            String clean=sanitizer.sanitizeAndTruncate(message.content(),
                    DeterministicContextCompiler.MAX_SEGMENT_BYTES);
            sources.add(R3ContextTestFixtures.source(message.role()==AgentMessage.Role.USER?90:80,i,
                    message.role()==AgentMessage.Role.USER?SourceClass.FRESH_USER_INTENT:
                            SourceClass.COMPACTABLE_DURABLE_HISTORY,
                    "EVENT_"+message.type(),"evt_"+i,
                    message.role()==AgentMessage.Role.USER?Compactability.NON_COMPACTABLE:
                            Compactability.COMPACTABLE,message.role(),message.type(),clean));
        }
        List<ContextSource> ordered=sources.stream().sorted(ContextSourceSet.ORDER).toList();
        return new CompileRequest(invocation,new ContextSourceSet(ordered,
                DeterministicContextCompiler.sourceFingerprint(ordered)),tools);
    }
    private Identity identity(){return new Identity("workspace",
            "thr_0123456789ab4def8abc0123456789ab","tsk_0123456789ab4def8abc0123456789ab",
            "run_0123456789ab4def8abc0123456789ab","gen_0123456789ab4def8abc0123456789ab",
            "ctx_0123456789ab4def8abc0123456789ab","0".repeat(64),"pln_1","str_1");}
    private CapabilityProfile capability(){return new CapabilityProfile("mock",131072,
            LimitProvenance.PROVIDER_REPORTED_LIMIT,2048,512);}
}
