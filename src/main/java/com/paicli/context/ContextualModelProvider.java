package com.paicli.context;

import com.paicli.context.ContextModels.*;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.model.AgentMessage;
import com.paicli.model.ModelResponse;
import com.paicli.model.ToolDefinition;
import com.paicli.provider.ModelProvider;
import com.paicli.provider.OutputRequirement;
import com.paicli.runtime.FencedExecutionGuard;
import com.paicli.state.ContextSourceAssembler;
import com.paicli.state.StateStore;
import com.paicli.tool.ToolResult;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/** Shared business Provider boundary: durable sources -> budget -> READY ctx_ -> fenced effect. */
public final class ContextualModelProvider implements ModelProvider {
    private final ModelProvider delegate; private final StateStore state; private final Path workspace;
    private final String taskId,provider,model; private final OperationKind configuredOperation;
    private final Function<OperationKind,CapabilityProfile> capabilityResolver;
    private final boolean child; private final String runtimeContract;
    private final Consumer<CompactionCoordinator.Outcome> compactionEvents;
    private final DeterministicContextCompiler compiler=new DeterministicContextCompiler();

    public ContextualModelProvider(ModelProvider delegate,StateStore state,Path workspace,String taskId,
            String provider,String model,OperationKind operation,CapabilityProfile capability,boolean child){
        this(delegate,state,workspace,taskId,provider,model,operation,capability,child,
                "RUNTIME_CONTRACT_V1",outcome->{ });
    }
    public ContextualModelProvider(ModelProvider delegate,StateStore state,Path workspace,String taskId,
            String provider,String model,OperationKind operation,CapabilityProfile capability,boolean child,
            String runtimeContract){
        this(delegate,state,workspace,taskId,provider,model,operation,capability,child,runtimeContract,
                outcome->{ });
    }
    public ContextualModelProvider(ModelProvider delegate,StateStore state,Path workspace,String taskId,
            String provider,String model,OperationKind operation,CapabilityProfile capability,boolean child,
            String runtimeContract,Consumer<CompactionCoordinator.Outcome> compactionEvents){
        this(delegate,state,workspace,taskId,provider,model,operation,
                ignored->Objects.requireNonNull(capability),child,runtimeContract,compactionEvents);
    }
    /**
     * Operation-aware capability seam. Existing constructors remain fixed-capability; this overload
     * lets offline runtime-contract fixtures force a smaller window only after a Tool Result has
     * reached the TOOL_CONTINUATION boundary.
     */
    public ContextualModelProvider(ModelProvider delegate,StateStore state,Path workspace,String taskId,
            String provider,String model,OperationKind operation,
            Function<OperationKind,CapabilityProfile> capabilityResolver,boolean child,
            String runtimeContract,Consumer<CompactionCoordinator.Outcome> compactionEvents){
        this.delegate=Objects.requireNonNull(delegate);this.state=Objects.requireNonNull(state);
        this.workspace=Objects.requireNonNull(workspace).toAbsolutePath().normalize();
        this.taskId=Objects.requireNonNull(taskId);this.provider=provider==null?"unknown":provider;
        this.model=model==null?"unknown":model;this.configuredOperation=Objects.requireNonNull(operation);
        this.capabilityResolver=Objects.requireNonNull(capabilityResolver);this.child=child;
        this.runtimeContract=Objects.requireNonNull(runtimeContract);
        this.compactionEvents=Objects.requireNonNull(compactionEvents);
    }

    /** Arbitrary ModelProvider messages are compatibility data, never sufficient Context authority. */
    @Override public ModelResponse chat(List<AgentMessage> messages,List<ToolDefinition> tools){
        throw new ContextGenerationException("CONTEXT_INVOCATION_REQUIRED");
    }

    public ModelResponse chat(OperationKind explicitOperation,String operationSequence,
            List<AgentMessage> compatibilityMessages,List<ToolDefinition> tools,
            FencedExecutionGuard guard){
        if(explicitOperation==null||operationSequence==null||guard==null)
            throw new ContextGenerationException("CONTEXT_INVOCATION_REQUIRED");
        if(configuredOperation==OperationKind.PLAN&&explicitOperation!=OperationKind.PLAN)
            throw new ContextGenerationException("CONTEXT_OPERATION_MISMATCH");
        OperationKind contextOperation=explicitOperation==OperationKind.FINAL_SYNTHESIS
                ?OperationKind.CHAT:explicitOperation;
        boolean managedAnswer=delegate.usesManagedAnswerEnvelope()
                &&ManagedAnswerContract.applies(explicitOperation,child);
        OutputRequirement requirement = managedAnswer
                ? OutputRequirement.managed(delegate.managedOutputMode()) : OutputRequirement.LEGACY;
        try {
            delegate.validateOutputRequirement(requirement, tools != null && !tools.isEmpty());
        } catch(IllegalArgumentException rejected) {
            throw new ContextGenerationException("OUTPUT_REQUIREMENT_REJECTED",rejected);
        }
        String turnContract=managedAnswer?runtimeContract+ManagedAnswerContract.INSTRUCTION:runtimeContract;
        CanonicalId occurrence=null;boolean effectAdmitted=false;
        try{
            guard.heartbeat("CONTEXT_PROVIDER_ADMISSION");
            CompiledContext compiled=compile(contextOperation,operationSequence,
                    compatibilityMessages,tools,guard,turnContract,requirement);
            Budget.Decision decision=compiled.budget().decision();
            if(decision==Budget.Decision.CONTEXT_BUDGET_UNKNOWN
                    ||decision==Budget.Decision.CONTEXT_BUDGET_EXCEEDED)
                throw new ContextGenerationException(decision.name());
            PublishedContext published=publish(compiled);
            CompactionCoordinator coordinator=new CompactionCoordinator(state);
            boolean hasCompactable=compiled.segments().stream()
                    .anyMatch(segment->segment.compactability()==Compactability.COMPACTABLE);
            if(coordinator.shouldAutoCompact(compiled.budget())&&hasCompactable){
                var source=new com.paicli.state.ContextGenerationStore.Pointer(
                        compiled.identity().taskId(),compiled.identity().runId(),compiled.operation(),
                        published.contextId(),published.pointerVersion());
                CompactionCoordinator.Outcome outcome=coordinator.autoCompact(source,guard);
                compactionEvents.accept(outcome);
                compiled=compile(contextOperation,operationSequence,compatibilityMessages,tools,guard,turnContract,requirement);
                decision=compiled.budget().decision();
                if(decision!=Budget.Decision.READY)
                    throw new ContextGenerationException(decision==Budget.Decision.COMPACTION_REQUIRED
                            ?"COMPACTION_NO_EFFECTIVE_REDUCTION":decision.name());
                published=publish(compiled);
            }
            if(decision==Budget.Decision.COMPACTION_REQUIRED)
                throw new ContextGenerationException(decision.name());
            String logicalKey="provider:"+compiled.identity().runId()+":"+operationSequence
                    +":"+contextOperation.name()+":"+explicitOperation.name();
            occurrence=guard.admitOperationOccurrence(operationSequence,logicalKey,"PROVIDER",
                    contextOperation.name(),FencedExecutionGuard.canonicalInputHash(
                            explicitOperation+"|"+compiled.sourceFingerprint()+"|"+compiled.compiledHash()));
            state.contexts.requireProviderAdmission(published.contextId(),published.pointerVersion(),
                    occurrence.value());
            guard.revalidateBeforeExternalEffect(occurrence,"PROVIDER",published.contextId());
            effectAdmitted=true;
            ModelResponse response;
            var turnIdentity=new ProviderContextRuntime.ProviderTurnIdentity(
                    published.contextId(),compiled.identity().taskId(),compiled.identity().runId(),
                    compiled.identity().planRevision(),compiled.sourceFingerprint(),
                    compiled.compiledHash(),occurrence.value());
            PublishedContext admittedPublished=published;
            CanonicalId admittedOccurrence=occurrence;
            try(ProviderContextRuntime.Scope ignored=ProviderContextRuntime.bind(turnIdentity,()->{
                guard.heartbeat("PROVIDER_RETRY");
                try{
                    state.contexts.requireProviderAdmission(admittedPublished.contextId(),
                            admittedPublished.pointerVersion(),admittedOccurrence.value());
                }catch(SQLException error){
                    throw new ContextGenerationException(
                            "CONTEXT_PROVIDER_RETRY_ADMISSION_REJECTED",error);
                }
                guard.revalidateBeforeExternalEffect(admittedOccurrence,"PROVIDER",
                        admittedPublished.contextId());
            })){
                response=delegate.chatForOperation(explicitOperation.name(),
                        compiled.messages(),compiled.tools(),requirement);
            }
            if(managedAnswer&&response.type()==ModelResponse.ResponseType.FINAL) {
                try {
                    response=ModelResponse.finalAnswer(ManagedAnswerContract.decode(response.content()));
                } catch(IllegalArgumentException invalid) {
                    guard.failOccurrence(occurrence,ManagedAnswerContract.FAILURE);
                    return ModelResponse.providerError(ModelResponse.ProviderErrorKind
                            .PROVIDER_FINAL_ANSWER_CONTRACT_FAILURE);
                }
            }
            if(response.type()==ModelResponse.ResponseType.FINAL
                    &&explicitOperation!=OperationKind.PLAN
                    &&explicitOperation!=OperationKind.FINAL_SYNTHESIS
                    &&explicitOperation!=OperationKind.VERIFICATION)
                return response.withProviderOccurrenceId(occurrence.value());
            if(response.type()==ModelResponse.ResponseType.PROVIDER_ERROR)
                guard.completeOccurrence(occurrence,ToolResult.error("PROVIDER_ERROR"));
            else guard.completeOccurrence(occurrence,ToolResult.success("PROVIDER_RESPONSE_"+response.type()));
            return response;
        }catch(ContextGenerationException error){
            if(occurrence!=null)
                safelyFail(guard,occurrence,error.getMessage());
            throw error;
        }catch(ContextSourceAssembler.ContextSourceFailure error){
            String detail="CONTEXT_SOURCE_INTEGRITY:"+error.getMessage();
            if(occurrence!=null)safelyFail(guard,occurrence,detail);
            throw new ContextGenerationException(detail,error);
        }catch(com.paicli.state.ContextGenerationStore.ContextFailure error){
            throw new ContextGenerationException(error.getMessage(),error);
        }catch(FencedExecutionGuard.AuthorityLostException error){throw error;}
        catch(Exception error){
            if(occurrence!=null){
                if(effectAdmitted)safelyUnknown(guard,occurrence,"PROVIDER_EFFECT_UNKNOWN");
                else safelyFail(guard,occurrence,"CONTEXT_SOURCE_INTEGRITY");
            }
            throw new ContextGenerationException(effectAdmitted?"CONTEXT_PROVIDER_FAILURE":
                    "CONTEXT_SOURCE_INTEGRITY",error);
        }
    }

    private CompiledContext compile(OperationKind operation,String sequence,
            List<AgentMessage> compatibilityMessages,List<ToolDefinition> tools,
            FencedExecutionGuard guard,String turnContract,OutputRequirement requirement)throws Exception{
        CapabilityProfile capability=Objects.requireNonNull(capabilityResolver.apply(operation),
                "capabilityResolver returned null");
        ContextSourceAssembler.Assembled assembled=state.readOnlySnapshot(()->{
            Identity identity=snapshotIdentity();
            if(!child && ManagedAnswerContract.applies(operation,false))
                state.contexts.requireOutputRequirement(taskId,requirement);
            ContextInvocation invocation=new ContextInvocation(identity,operation,sequence,null,List.of(),null,
                    identity.planRevision(),identity.steeringRevision(),provider,model,capability,child,
                    DeterministicContextCompiler.COMPILER_VERSION,
                    DeterministicContextCompiler.POLICY_VERSION);
            return state.contextSources.assemble(invocation,turnContract,
                    compatibilityMessages==null?List.of():compatibilityMessages,
                    tools==null?List.of():tools,guard.current());
        });
        var sources=new java.util.ArrayList<>(assembled.sources().sources());
        if(requirement.managed()) {
            String payload=requirement.identity();
            sources.add(new ContextSource(11,0,SourceClass.NON_COMPACTABLE_AUTHORITY,"OUTPUT_REQUIREMENT",
                    taskId+":output-requirement","v1","CONTEXT_OWNED",Scope.TASK_SCOPED,
                    Compactability.NON_COMPACTABLE,PayloadMode.INLINE_SANITIZED,"v1",payload,
                    DeterministicContextCompiler.hash(payload),PrivacyClass.PUBLIC,payload.length(),true,
                    AgentMessage.Role.USER,AgentMessage.MessageType.USER,null,null,
                    List.of(new SourceReference("CONTEXT_INLINE",taskId+":output-requirement","v1",
                            DeterministicContextCompiler.hash(payload),0))));
        }
        return compiler.compile(new CompileRequest(assembled.invocation(),new ContextSourceSet(sources,
                DeterministicContextCompiler.sourceFingerprint(sources)),assembled.tools()));
    }

    private PublishedContext publish(CompiledContext compiled)throws SQLException{
        var published=state.contexts.publishBase(compiled);
        if(published.lifecycle()!=Lifecycle.READY)
            throw new ContextGenerationException("CONTEXT_NOT_READY");
        var pointer=state.contexts.pointer(compiled.identity().taskId(),compiled.identity().runId(),
                compiled.operation()).orElseThrow(()->new ContextGenerationException("CONTEXT_POINTER_CONFLICT"));
        if(!pointer.contextGenerationId().equals(published.contextGenerationId()))
            throw new ContextGenerationException("CONTEXT_POINTER_CONFLICT");
        return new PublishedContext(published.contextGenerationId(),pointer.version());
    }

    private void safelyFail(FencedExecutionGuard guard,CanonicalId id,String detail){
        try{guard.failOccurrence(id,detail==null?"CONTEXT_FAILURE":detail);}catch(RuntimeException ignored){}
    }
    private void safelyUnknown(FencedExecutionGuard guard,CanonicalId id,String detail){
        try{guard.unknownOccurrence(id,detail);}catch(RuntimeException ignored){}
    }

    private Identity snapshotIdentity()throws SQLException{
        CanonicalId task=CanonicalId.parse(taskId);
        if(task.family()!=EntityFamily.TASK)throw new ContextGenerationException("CONTEXT_IDENTITY_MISMATCH");
        var durable=state.r1aDurable.findTask(task).orElseThrow(()->new ContextGenerationException("CONTEXT_IDENTITY_MISMATCH"));
        var pointer=state.r1aDurable.readContextPointer(task).orElseThrow(()->new ContextGenerationException("CONTEXT_IDENTITY_MISMATCH"));
        var run=state.r1aDurable.findRun(pointer.ownerRunId()).orElseThrow(()->new ContextGenerationException("CONTEXT_IDENTITY_MISMATCH"));
        if(!run.taskId().equals(task)||!run.threadId().equals(durable.threadId()))
            throw new ContextGenerationException("CONTEXT_IDENTITY_MISMATCH");
        var thread=state.r1aDurable.findWorkThread(durable.threadId()).orElseThrow();
        String plan=null;var active=state.planAuthorities.readActive(task);
        if(active.plan()!=null)plan=active.plan().revision().planRevisionId().value();
        String steering=null;var constraint=state.r1aDurable.readConstraintActivation(task).orElse(null);
        if(constraint!=null)steering=constraint.revisionId();
        return new Identity(thread.workspaceIdentity(),durable.threadId().value(),taskId,
                run.id().value(),pointer.generationId().value(),
                CanonicalId.newId(EntityFamily.CONTEXT).value(),durable.manifestHash(),plan,steering);
    }
    public static CapabilityProfile capability(String provider){return capability(provider,null,null);}
    public static CapabilityProfile capability(String provider,Integer configured){return capability(provider,null,configured);}
    public static CapabilityProfile capability(String provider,String model,Integer configured){
        return capability(provider,model,"mock".equals(provider)?131072:null,configured);
    }
    public static CapabilityProfile capability(String provider,String model,Integer providerReported,
            Integer configured){
        return new ProviderContextCapabilityResolver().resolve(
                provider,model,providerReported,configured,null);
    }
    public static final class ContextGenerationException extends RuntimeException
            implements com.paicli.runtime.ObservedIterationFailure {
        private final int observedIteration;
        public ContextGenerationException(String code){this(code,null,-1);}
        public ContextGenerationException(String code,Throwable cause){this(code,cause,-1);}
        private ContextGenerationException(String code,Throwable cause,int observedIteration){
            super(code,cause);
            if(observedIteration < -1)throw new IllegalArgumentException(
                    "Observed iteration must be non-negative when present");
            this.observedIteration=observedIteration;
        }
        @Override public java.util.OptionalInt observedIteration(){
            return observedIteration<0?java.util.OptionalInt.empty():
                    java.util.OptionalInt.of(observedIteration);
        }
        public ContextGenerationException observedAt(int iteration){
            if(iteration<0)throw new IllegalArgumentException("Observed iteration cannot be negative");
            if(observedIteration>=iteration)return this;
            return new ContextGenerationException(getMessage(),this,iteration);
        }
    }
    private record PublishedContext(String contextId,long pointerVersion){}
}
