package com.paicli.support;

import com.paicli.config.Config;
import com.paicli.context.ContextModels.*;
import com.paicli.context.DeterministicContextCompiler;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.interactive.session.SavedSessionService;
import com.paicli.model.AgentMessage;
import com.paicli.runtime.FencedExecutionGuard;
import com.paicli.runtime.InstructionManifestRuntime;
import com.paicli.runtime.RuntimeManifest;
import com.paicli.runtime.TaskExecutionAuthority;
import com.paicli.state.R1aStoreResults.OwnershipResult;
import com.paicli.state.StateStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class R3ContextTestFixtures {
    private R3ContextTestFixtures(){}

    public static Admitted admit(StateStore state,Path workspace)throws Exception{
        return admit(state,workspace,"r3-test-owner","r3-test-lease");
    }

    public static Admitted admit(StateStore state,Path workspace,String ownerInstanceId,
            String leaseToken)throws Exception{
        Files.writeString(workspace.resolve("AGENTS.md"),"frozen");
        Config config=new Config(workspace.toString(),30,50_000,200_000,Config.MODE_DENY,10_000,
                Config.PROVIDER_MOCK,"model",null,null,"system",3,"",120,50_000,1,
                false,false,false);
        var root=new InstructionManifestRuntime(state,workspace).admitRoot(
                CanonicalId.newId(EntityFamily.TASK),"goal",10_000,config,
                RuntimeManifest.disabled(),Instant.now());
        Instant now=Instant.now();
        OwnershipResult result=state.r1aDurable.acquireOwnership(root.taskId(),ownerInstanceId,
                root.initialRun().id(),leaseToken,now,now.plus(Duration.ofHours(1)));
        if(result!=OwnershipResult.SUCCESS)throw new IllegalStateException("ownership fixture failed: "+result);
        var lease=state.r1aDurable.readOwnership(root.taskId()).orElseThrow();
        return new Admitted(root,new FencedExecutionGuard(state,TaskExecutionAuthority.initial(lease),
                Duration.ofHours(1),Instant::now));
    }

    public static CompiledContext compiled(Path workspace,Admitted admitted,OperationKind operation,
            int compactableCount,String marker){
        var root=admitted.root();
        Identity identity=new Identity(SavedSessionService.workspaceIdentity(workspace),
                root.threadId().value(),root.taskId().value(),root.initialRun().id().value(),
                root.generation().id().value(),CanonicalId.newId(EntityFamily.CONTEXT).value(),
                root.instructions().manifest().canonicalHash(),null,null);
        var invocation=new ContextInvocation(identity,operation,"fixture",identity.contextGenerationId()+":intent",
                operation==OperationKind.TOOL_CONTINUATION?List.of(identity.contextGenerationId()+":history:0"):List.of(),
                operation==OperationKind.REPAIR?identity.contextGenerationId()+":history:2":
                        operation==OperationKind.VERIFICATION?identity.contextGenerationId()+":history:1":null,
                null,null,"mock","model",
                new CapabilityProfile("mock",30000,LimitProvenance.PROVIDER_REPORTED_LIMIT,128,32),
                false,DeterministicContextCompiler.COMPILER_VERSION,
                DeterministicContextCompiler.POLICY_VERSION);
        var sources=new ArrayList<ContextSource>();
        TaskExecutionAuthority authority=admitted.guard().current();
        String ownershipPayload=identity.taskId()+"|"+identity.runId()+"|"
                +authority.fencingToken()+"|ACTIVE";
        String ownershipHash=DeterministicContextCompiler.hash(ownershipPayload);
        sources.add(new ContextSource(30,1,SourceClass.NON_COMPACTABLE_AUTHORITY,
                "OWNERSHIP_FENCE",identity.taskId(),Long.toString(authority.fencingToken()),
                "R1_OWNERSHIP_FENCE",Scope.RUN_SCOPED,Compactability.NON_COMPACTABLE,
                PayloadMode.REFERENCE_RENDERED,DeterministicContextCompiler.RENDERER_VERSION,
                ownershipPayload,ownershipHash,PrivacyClass.INTERNAL,
                DeterministicContextCompiler.MAX_SEGMENT_BYTES,false,null,null,null,
                List.of(new SourceReference("OWNERSHIP_LEASE",identity.taskId(),
                        Long.toString(authority.fencingToken()),ownershipHash,0))));
        sources.add(source(10,0,SourceClass.NON_COMPACTABLE_AUTHORITY,"RUNTIME_CONTRACT",
                identity.contextGenerationId()+":runtime",Compactability.NON_COMPACTABLE,
                AgentMessage.Role.USER,AgentMessage.MessageType.USER,"runtime "+marker));
        sources.add(source(90,0,SourceClass.FRESH_USER_INTENT,"CURRENT_USER_INTENT",
                identity.contextGenerationId()+":intent",Compactability.NON_COMPACTABLE,
                AgentMessage.Role.USER,AgentMessage.MessageType.USER,"goal "+marker));
        for(int i=0;i<compactableCount;i++){
            String sourceId=identity.contextGenerationId()+":history:"+i;
            String kind=i%3==0?"DURABLE_ASSISTANT_RESULT":i%3==1?"VERIFICATION_RESULT":"REPAIR_ITERATION";
            String padding="x".repeat(7500);
            String payload=i%3==0?"kind=DURABLE_ASSISTANT_RESULT;source_id="+sourceId
                    +";revision=1;fact=durable fact "+marker+i+padding
                    :i%3==1?"kind=VERIFICATION_RESULT;source_id="+sourceId
                    +";revision=1;status=PASSED;unresolved=false;detail="+padding
                    :"kind=REPAIR_ITERATION;source_id="+sourceId+";revision=1;outcome="
                    +(i==2?"FAILED":"SUCCEEDED")+";remaining_unresolved="
                    +(i==2?"fixture blocker":"")+";unresolved="+(i==2)+";detail="+padding;
            sources.add(source(80,i,SourceClass.COMPACTABLE_DURABLE_HISTORY,kind,sourceId,
                    Compactability.COMPACTABLE,AgentMessage.Role.ASSISTANT,
                    AgentMessage.MessageType.FINAL,payload));
        }
        List<ContextSource> ordered=sources.stream().sorted(ContextSourceSet.ORDER).toList();
        return new DeterministicContextCompiler().compile(new CompileRequest(invocation,
                new ContextSourceSet(ordered,DeterministicContextCompiler.sourceFingerprint(ordered)),List.of()));
    }

    public static ContextSource source(int priority,long sequence,SourceClass sourceClass,String kind,
            String id,Compactability compactability,AgentMessage.Role role,
            AgentMessage.MessageType type,String payload){
        String hash=DeterministicContextCompiler.hash(payload);
        return new ContextSource(priority,sequence,sourceClass,kind,id,"1","TEST_DURABLE_SOURCE",
                Scope.RUN_SCOPED,compactability,PayloadMode.INLINE_SANITIZED,
                DeterministicContextCompiler.RENDERER_VERSION,payload,hash,PrivacyClass.INTERNAL,
                DeterministicContextCompiler.MAX_SEGMENT_BYTES,true,role,type,null,
                List.of(new SourceReference("CONTEXT_INLINE",id,"1",hash,0)));
    }

    public record Admitted(InstructionManifestRuntime.RootAdmission root,FencedExecutionGuard guard){}
}
