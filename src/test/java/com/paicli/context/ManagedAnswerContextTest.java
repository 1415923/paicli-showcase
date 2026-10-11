package com.paicli.context;

import com.paicli.context.ContextModels.*;
import com.paicli.model.*;
import com.paicli.provider.ModelProvider;
import com.paicli.state.StateStore;
import com.paicli.support.R3ContextTestFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ManagedAnswerContextTest {
    @TempDir Path work;

    @Test void mainContractIsBudgetedAndPublishedBeforeTheOnlyCall() throws Exception {
        exercise(OperationKind.CHAT,false,true,true);
    }
    @Test void planContractIsNotReinterpreted() throws Exception {
        exercise(OperationKind.PLAN,false,true,false);
    }
    @Test void synthesisRetainsItsOperationWhileReusingChatContext() throws Exception {
        exercise(OperationKind.FINAL_SYNTHESIS,false,true,true);
    }
    @Test void alreadyTypedCustomProvidersKeepTheirExistingContract() throws Exception {
        exercise(OperationKind.CHAT,false,false,false);
    }
    private void exercise(OperationKind operation,boolean child,boolean enabled,boolean expected) throws Exception {
        Path dir=Files.createDirectory(work.resolve(operation+"-"+child+"-"+enabled));
        try(StateStore state=new StateStore(dir)) {
            var admitted=R3ContextTestFixtures.admit(state,dir);var root=admitted.root();
            AgentMessage goal=AgentMessage.userGoal("original task");admitted.guard().appendEvent(0,goal);
            AtomicInteger calls=new AtomicInteger();List<AgentMessage> sent=new ArrayList<>();
            ModelProvider delegate=new ModelProvider() {
                @Override public boolean usesManagedAnswerEnvelope(){return enabled;}
                @Override public void validateOutputRequirement(com.paicli.provider.OutputRequirement requirement,boolean hasTools){
                    if(requirement.nativeJson())throw new IllegalArgumentException("FIXTURE_PROMPT_ONLY");
                }
                @Override public com.paicli.model.ModelResponse chatForOperation(String operation,List<com.paicli.model.AgentMessage> messages,
                        List<com.paicli.model.ToolDefinition> tools,com.paicli.provider.OutputRequirement requirement){
                    validateOutputRequirement(requirement,!tools.isEmpty());
                    assertEquals(expected,requirement.managed());
                    return chatForOperation(operation,messages,tools);
                }
                @Override public ModelResponse chatForOperation(String explicit,List<AgentMessage> messages,List<ToolDefinition> tools){
                    assertEquals(operation.name(),explicit);return chat(messages,tools);
                }
                @Override public ModelResponse chat(List<AgentMessage> messages,List<ToolDefinition> tools) {
                    calls.incrementAndGet();sent.addAll(messages);
                    return ModelResponse.finalAnswer(expected?"{\"userAnswer\":\"原样答案\"}":"plain result");
                }
            };
            var provider=new ContextualModelProvider(delegate,state,dir,root.taskId().value(),"mock","model",
                    operation,new CapabilityProfile("fixture",131072,LimitProvenance.PROVIDER_REPORTED_LIMIT,128,32),
                    child,"runtime");
            var response=provider.chat(operation,"1",List.of(goal),List.of(),admitted.guard());
            assertEquals(expected?"原样答案":"plain result",response.content());assertEquals(1,calls.get());
            assertEquals(expected,sent.stream().anyMatch(m->m.content()!=null&&m.content().contains(ManagedAnswerContract.VERSION)));
            var pointer=state.contexts.pointer(root.taskId().value(),root.initialRun().id().value(),
                    operation==OperationKind.FINAL_SYNTHESIS?OperationKind.CHAT:operation).orElseThrow();
            var generation=state.contexts.find(pointer.contextGenerationId()).orElseThrow();
            assertEquals(Lifecycle.READY,generation.lifecycle());
            assertEquals(DeterministicContextCompiler.hash(
                    DeterministicContextCompiler.segmentSetHash(state.contexts.segments(pointer.contextGenerationId()))
                            +"|"+DeterministicContextCompiler.compiledHash(sent,List.of())),generation.compiledHash());
        }
    }

    @Test void insufficientContextBudgetPreventsRequestWithContractEnabled() throws Exception {
        try(StateStore state=new StateStore(work)) {
            var admitted=R3ContextTestFixtures.admit(state,work);AtomicInteger calls=new AtomicInteger();
            AgentMessage goal=AgentMessage.userGoal("original task");admitted.guard().appendEvent(0,goal);
            ModelProvider delegate=new ModelProvider() {
                @Override public boolean usesManagedAnswerEnvelope(){return true;}
                @Override public void validateOutputRequirement(com.paicli.provider.OutputRequirement requirement,boolean hasTools){
                    if(requirement.nativeJson())throw new IllegalArgumentException("FIXTURE_PROMPT_ONLY");
                }
                @Override public com.paicli.model.ModelResponse chatForOperation(String operation,List<com.paicli.model.AgentMessage> messages,
                        List<com.paicli.model.ToolDefinition> tools,com.paicli.provider.OutputRequirement requirement){
                    validateOutputRequirement(requirement,!tools.isEmpty());
                    return chatForOperation(operation,messages,tools);
                }
                @Override public ModelResponse chat(List<AgentMessage> m,List<ToolDefinition> t){calls.incrementAndGet();return ModelResponse.finalAnswer("unused");}
            };
            var provider=new ContextualModelProvider(delegate,state,work,admitted.root().taskId().value(),"mock","model",OperationKind.CHAT,
                    new CapabilityProfile("small",1024,LimitProvenance.PROVIDER_REPORTED_LIMIT,128,32),false,"x".repeat(12000));
            assertEquals("CONTEXT_BUDGET_EXCEEDED",assertThrows(ContextualModelProvider.ContextGenerationException.class,
                    ()->provider.chat(OperationKind.CHAT,"1",List.of(goal),List.of(),admitted.guard())).getMessage());
            assertEquals(0,calls.get());
        }
    }
}
