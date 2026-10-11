package com.paicli.context;

import com.paicli.context.ContextModels.*;
import com.paicli.model.*;
import com.paicli.provider.*;
import com.paicli.runtime.*;
import com.paicli.state.StateStore;
import com.paicli.support.R3ContextTestFixtures;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class OutputRequirementRecoveryTest {
    @TempDir Path work;
    @ParameterizedTest @ValueSource(booleans={false,true})
    void reopenedUnacceptedTaskRejectsLegacyOrDifferentModeBeforeAnotherProviderCall(boolean previousManaged)throws Exception{
        AtomicInteger calls=new AtomicInteger();String task;TaskExecutionAuthority authority;
        try(var state=new StateStore(work)){
            var admitted=R3ContextTestFixtures.admit(state,work);task=admitted.root().taskId().value();
            admitted.guard().appendEvent(0,AgentMessage.userGoal("original task"));
            var first=context(provider(previousManaged,OutputRequirement.Mode.PROMPT_ONLY,calls),state,task);
            assertEquals(ModelResponse.ResponseType.PROVIDER_ERROR,
                    first.chat(OperationKind.CHAT,"1",List.of(AgentMessage.userGoal("original task")),List.of(),admitted.guard()).type());
            authority=admitted.guard().current();
        }
        try(var state=new StateStore(work)){
            var guard=new FencedExecutionGuard(state,authority,Duration.ofHours(1),Instant::now);
            var next=context(provider(true,OutputRequirement.Mode.DIAGNOSTIC_NATIVE_CANDIDATE,calls),state,task);
            var failure=assertThrows(ContextualModelProvider.ContextGenerationException.class,
                    ()->next.chat(OperationKind.REPAIR,"2",List.of(),List.of(),guard));
            assertEquals("OUTPUT_REQUIREMENT_TASK_DRIFT",failure.getMessage());
            assertEquals(1,calls.get(),"new instance must not silently adopt a different policy");
            assertFalse(state.r1aDurable.hasStartedOrUnknownOccurrence(com.paicli.identity.CanonicalId.parse(task)));
        }
    }
    private ContextualModelProvider context(ModelProvider provider,StateStore state,String task){
        return new ContextualModelProvider(provider,state,work,task,"mock","fixture",OperationKind.CHAT,
                new CapabilityProfile("fixture",131072,LimitProvenance.PROVIDER_REPORTED_LIMIT,128,32),false);
    }
    private ModelProvider provider(boolean managed,OutputRequirement.Mode mode,AtomicInteger calls){
        return new ModelProvider(){
            public boolean usesManagedAnswerEnvelope(){return managed;}
            public OutputRequirement.Mode managedOutputMode(){return mode;}
            public void validateOutputRequirement(OutputRequirement requirement,boolean hasTools){}
            public ModelResponse chatForOperation(String o,List<AgentMessage> m,List<ToolDefinition> t,OutputRequirement r){return chat(m,t);}
            public ModelResponse chat(List<AgentMessage> m,List<ToolDefinition> t){
                calls.incrementAndGet();return ModelResponse.providerError(ModelResponse.ProviderErrorKind.PROVIDER_EMPTY_RESPONSE);
            }
        };
    }
}
