package com.paicli.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.context.ContextModels.*;
import com.paicli.context.ContinuationSourceAuthority;
import com.paicli.context.DeterministicContextCompiler;
import com.paicli.identity.CanonicalId;
import com.paicli.model.AgentMessage;
import com.paicli.model.ToolDefinition;
import com.paicli.runtime.PersistenceSanitizer;
import com.paicli.runtime.PlanService;
import com.paicli.runtime.TaskExecutionAuthority;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Builds the only ordered Context source closure from durable facts in the caller snapshot. */
public final class ContextSourceAssembler {
    private static final String RENDERER=DeterministicContextCompiler.RENDERER_VERSION;
    private static final int BOUND=DeterministicContextCompiler.MAX_SEGMENT_BYTES;
    private static final String TOOL_FACT_MARKER="[PAICLI_TOOL_FACTS] ";
    private static final ObjectMapper JSON=new ObjectMapper();
    private final Connection connection;
    private final ContextGenerationStore contexts;
    private final PersistenceSanitizer sanitizer=new PersistenceSanitizer();

    ContextSourceAssembler(Connection connection){
        this.connection=Objects.requireNonNull(connection);
        this.contexts=new ContextGenerationStore(connection);
    }

    public Assembled assemble(ContextInvocation invocation,String runtimeContract,
            List<AgentMessage> compatibilityMessages,List<ToolDefinition> tools,
            TaskExecutionAuthority authority)throws SQLException{
        Objects.requireNonNull(invocation);Objects.requireNonNull(authority);
        Identity id=invocation.identity();
        if(!authority.taskId().value().equals(id.taskId())||!authority.runId().value().equals(id.runId()))
            throw new ContextSourceFailure("CONTEXT_IDENTITY_MISMATCH");
        var sources=new ArrayList<ContextSource>();
        sources.add(inline(10,0,SourceClass.NON_COMPACTABLE_AUTHORITY,"RUNTIME_CONTRACT",
                id.contextGenerationId()+":runtime",null,"CONTEXT_OWNED",Scope.RUN_SCOPED,
                Compactability.NON_COMPACTABLE,true,AgentMessage.Role.USER,
                AgentMessage.MessageType.USER,clean(runtimeContract)));
        sources.add(reference(20,0,invocation.childContext()?SourceClass.CHILD_CONTEXT:
                        SourceClass.NON_COMPACTABLE_AUTHORITY,"FROZEN_MANIFEST",id.manifestHash(),
                id.manifestHash(),"INSTRUCTION_MANIFEST",invocation.childContext()?Scope.CHILD_SCOPED:
                        Scope.TASK_SCOPED,Compactability.NON_COMPACTABLE,false,AgentMessage.Role.USER,
                AgentMessage.MessageType.USER,"Frozen Instruction Manifest: "+id.manifestHash(),
                "MANIFEST",id.manifestHash()));
        String identityText="Context identity: workspace="+id.workspaceId()+" thread="+id.threadId()
                +" task="+id.taskId()+" run="+id.runId()+" gen="+id.authorityGenerationId()
                +" ctx="+id.contextGenerationId()+" operation="+invocation.operation();
        sources.add(inline(30,0,SourceClass.NON_COMPACTABLE_AUTHORITY,"IDENTITY",
                id.contextGenerationId()+":identity",null,"CONTEXT_IDENTITY",Scope.RUN_SCOPED,
                Compactability.NON_COMPACTABLE,false,AgentMessage.Role.USER,
                AgentMessage.MessageType.USER,identityText));
        sources.add(leaseSource(id,authority));
        addPlan(sources,id);
        addSteering(sources,id);
        addChildCapsule(sources,invocation);
        addParentCompaction(sources,invocation);
        Set<String> compactedSources=addActiveCompaction(sources,invocation);
        addSemanticSources(sources,id,compactedSources,
                invocation.operation()==OperationKind.PLAN?Set.of():observedSuccessfulOccurrences(id));
        if(invocation.operation()==OperationKind.PLAN){
            addPlanInlineSources(sources,invocation,compatibilityMessages);
        }else{
            addDurableEvents(sources,invocation,compatibilityMessages,compactedSources);
        }
        List<ContextSource> ordered=sources.stream().sorted(ContextSourceSet.ORDER).toList();
        ContextInvocation closed=closeInvocation(invocation,ordered);
        return new Assembled(closed,new ContextSourceSet(ordered,
                DeterministicContextCompiler.sourceFingerprint(ordered)),List.copyOf(tools));
    }

    private ContextInvocation closeInvocation(ContextInvocation invocation,List<ContextSource> sources){
        String currentUser=sources.stream().filter(source->source.sourceClass()==SourceClass.FRESH_USER_INTENT)
                .max(ContextSourceSet.ORDER).map(ContextSource::stableSourceId).orElse(null);
        List<String> toolResults;
        try{toolResults=ContinuationSourceAuthority.close(invocation.operation(),sources);}
        catch(IllegalArgumentException invalid){throw new ContextSourceFailure(invalid.getMessage());}
        String verificationRepair=null;
        if(invocation.operation()==OperationKind.VERIFICATION||invocation.operation()==OperationKind.REPAIR){
            String required=invocation.operation()==OperationKind.VERIFICATION
                    ?"VERIFICATION_RESULT":"REPAIR_ITERATION";
            verificationRepair=sources.stream().filter(source->source.kind().equals(required))
                    .max(ContextSourceSet.ORDER).map(ContextSource::stableSourceId).orElse(null);
        }
        Identity id=invocation.identity();
        return new ContextInvocation(id,invocation.operation(),invocation.operationSequence(),currentUser,
                toolResults,verificationRepair,id.planRevision(),id.steeringRevision(),invocation.provider(),
                invocation.model(),invocation.capability(),invocation.childContext(),
                invocation.compilerVersion(),invocation.policyVersion());
    }

    private ContextSource leaseSource(Identity id,TaskExecutionAuthority expected)throws SQLException{
        try(var s=connection.prepareStatement("""
          SELECT owner_instance_id,owner_run_id,lease_token,fencing_token,cas_version,state,expires_at
          FROM task_ownership_leases WHERE task_id=?
          """)){
            s.setString(1,id.taskId());try(var r=s.executeQuery()){
                if(!r.next()||!r.getString(1).equals(expected.ownerInstanceId())
                        ||!r.getString(2).equals(id.runId())||!r.getString(3).equals(expected.leaseToken())
                        ||r.getLong(4)!=expected.fencingToken()||r.getLong(5)!=expected.casVersion()
                        ||!"ACTIVE".equals(r.getString(6)))
                    throw new ContextSourceFailure("CONTEXT_IDENTITY_MISMATCH");
                String revision=Long.toString(r.getLong(4));
                String material=id.taskId()+"|"+id.runId()+"|"+revision+"|"+r.getString(6);
                return reference(30,1,SourceClass.NON_COMPACTABLE_AUTHORITY,"OWNERSHIP_FENCE",
                        id.taskId(),revision,"R1_OWNERSHIP_FENCE",Scope.RUN_SCOPED,
                        Compactability.NON_COMPACTABLE,false,null,null,material,"OWNERSHIP_LEASE",id.taskId());
            }
        }
    }

    private void addPlan(List<ContextSource> out,Identity id)throws SQLException{
        if(id.planRevision()==null)return;
        var active=new PlanAuthorityStore(connection).readActive(CanonicalId.parse(id.taskId()));
        if(active.status()!=PlanAuthorityStore.Status.SUCCESS
                ||!id.planRevision().equals(active.plan().revision().planRevisionId().value()))
            throw new ContextSourceFailure("CONTEXT_STALE_SOURCE");
        String rendered=clean("Current active Plan (authoritative):\n"+PlanService.render(active.plan().revision()));
        out.add(reference(40,active.plan().revision().revisionNumber(),
                SourceClass.NON_COMPACTABLE_AUTHORITY,"ACTIVE_PLAN",id.planRevision(),
                Integer.toString(active.plan().revision().revisionNumber()),"R1C_PLAN_AUTHORITY",
                Scope.TASK_SCOPED,Compactability.NON_COMPACTABLE,true,AgentMessage.Role.USER,
                AgentMessage.MessageType.USER,rendered,"PLAN_REVISION",id.planRevision()));
    }

    private void addSteering(List<ContextSource> out,Identity id)throws SQLException{
        if(id.steeringRevision()==null)return;
        String content=new SteeringStore(connection).activeConstraintContext(CanonicalId.parse(id.taskId()));
        String rendered=clean("Current durable active Constraints (runtime authority):\n"+content);
        out.add(reference(50,0,SourceClass.NON_COMPACTABLE_AUTHORITY,"ACTIVE_STEERING",
                id.steeringRevision(),id.steeringRevision(),"R1D_STEERING_AUTHORITY",
                Scope.TASK_SCOPED,Compactability.NON_COMPACTABLE,true,AgentMessage.Role.USER,
                AgentMessage.MessageType.USER,rendered,"STEERING_REVISION",id.steeringRevision()));
    }

    private void addChildCapsule(List<ContextSource> out,ContextInvocation invocation)throws SQLException{
        if(!invocation.childContext())return;
        try(var s=connection.prepareStatement("""
          SELECT b.capsule_id,c.canonical_hash FROM capsule_child_bindings b
          JOIN continuation_capsules c ON c.capsule_id=b.capsule_id
          WHERE b.child_task_id=? AND b.child_run_id=? AND b.child_generation_id=?
          """)){
            Identity id=invocation.identity();s.setString(1,id.taskId());s.setString(2,id.runId());
            s.setString(3,id.authorityGenerationId());try(var r=s.executeQuery()){
                if(!r.next())throw new ContextSourceFailure("CONTEXT_SOURCE_INTEGRITY");
                String capsule=r.getString(1),hash=r.getString(2);
                out.add(reference(60,0,SourceClass.CHILD_CONTEXT,"CONTINUATION_CAPSULE",
                        capsule,hash,"R1E_CONTINUATION_CAPSULE",Scope.CHILD_SCOPED,
                        Compactability.NON_COMPACTABLE,true,AgentMessage.Role.USER,
                        AgentMessage.MessageType.USER,"Continuation Capsule: "+capsule+" hash="+hash,
                        "CONTINUATION_CAPSULE",capsule));
            }
        }
    }

    /** Child-only bounded history from the capsule-bound parent's captured conversational generation. */
    private void addParentCompaction(List<ContextSource> out,ContextInvocation invocation)throws SQLException{
        if(!invocation.childContext())return;Identity id=invocation.identity();
        var verified=contexts.verifiedParentHistory(id.taskId(),id.runId(),
                id.authorityGenerationId());
        if(verified.isEmpty())return;
        String parentOperation=verified.get().target().pointer().operation().name();
        long sequence=0;Set<String> emitted=new HashSet<>();
        for(ContextGenerationStore.ParentHistoryProjection projection:
                verified.get().projections()){
            if(!emitted.add(projection.projectionKey()))continue;
            var references=new ArrayList<SourceReference>();
            for(ContextGenerationStore.ParentSegmentAnchor anchor:projection.anchors())
                references.add(new SourceReference("PARENT_CONTEXT_SEGMENT",
                        anchor.contextGenerationId()+":"+anchor.ordinal(),parentOperation,
                        projection.contentHash(),references.size()));
            if(references.isEmpty())throw new ContextSourceFailure("COMPACTION_INTEGRITY");
            out.add(new ContextSource(65,sequence++,SourceClass.CHILD_CONTEXT,projection.kind(),
                    "parent-history:"+DeterministicContextCompiler.hash(projection.projectionKey()),
                    null,"NON_AUTHORITY_PARENT_HISTORY",Scope.CHILD_SCOPED,
                    Compactability.NON_COMPACTABLE,PayloadMode.INLINE_SANITIZED,RENDERER,
                    projection.payload(),projection.contentHash(),projection.privacyClass(),
                    projection.bounds(),true,AgentMessage.Role.USER,AgentMessage.MessageType.USER,
                    null,null,List.copyOf(references)));
        }
    }

    private Set<String> addActiveCompaction(List<ContextSource> out,ContextInvocation invocation)throws SQLException{
        Set<String> compactedSources=new HashSet<>();Identity id=invocation.identity();
        var active=contexts.verifiedActiveLedger(id.taskId(),id.runId(),invocation.operation());
        if(active.isEmpty())return compactedSources;
        String contextId=active.get().pointer().contextGenerationId();
        for(ContextGenerationStore.VerifiedLedgerSegment verified:active.get().segments()){
            ContextGenerationStore.StoredSegment segment=verified.segment();
            String sourceId=contextId+":"+segment.ordinal();
            out.add(new ContextSource(70,segment.ordinal(),SourceClass.COMPACTABLE_DURABLE_HISTORY,
                    "COMPACTED_LEDGER",sourceId,null,"V26_COMPACTED_CONTEXT",Scope.RUN_SCOPED,
                    Compactability.NON_COMPACTABLE,PayloadMode.INLINE_SANITIZED,RENDERER,
                    segment.sanitizedPayload(),segment.contentHash(),segment.privacyClass(),
                    segment.bounds(),true,AgentMessage.Role.USER,AgentMessage.MessageType.USER,
                    null,null,List.of(new SourceReference("CONTEXT_SEGMENT",sourceId,null,
                            segment.contentHash(),0))));
            for(ContextGenerationStore.StoredReference reference:verified.references()){
                if(!Set.of("SOURCE_SEGMENT","CONTEXT_SEGMENT","CONTEXT_INLINE")
                        .contains(reference.type()))
                    compactedSources.add(sourceKey(reference.type(),reference.id(),
                            reference.revision(),reference.hash()));
            }
        }
        return compactedSources;
    }

    private void addSemanticSources(List<ContextSource> out,Identity identity,
            Set<String> compactedSources,Set<String> observedOccurrences)throws SQLException{
        ContextSemanticSourceAdapter adapter=new ContextSemanticSourceAdapter(connection);
        for(ContextSemanticSourceAdapter.SemanticFact fact:adapter.read(identity)){
            boolean alreadyCompacted=fact.references().stream()
                    .anyMatch(reference->compactedSources.contains(sourceKey(reference.type(),
                            reference.id(),reference.revision(),reference.hash())));
            if(alreadyCompacted)continue;
            String clean=clean(fact.payload()),hash=DeterministicContextCompiler.hash(clean);
            boolean executionMetadata=Set.of("TOOL_OCCURRENCE","TOOL_RESULT").contains(fact.kind());
            boolean redundant=executionMetadata&&observedOccurrences.contains(fact.sourceId());
            Compactability compactability=redundant?Compactability.NON_COMPACTABLE:fact.compactability();
            SourceClass sourceClass=compactability==Compactability.COMPACTABLE
                    ?SourceClass.COMPACTABLE_DURABLE_HISTORY:SourceClass.NON_COMPACTABLE_AUTHORITY;
            // Keep provenance in the signed source closure without repeatedly presenting the
            // latest execution IDs as assistant dialogue before the stable task/history prefix.
            // Actual observations retain their exact ACTION binding and canonical fact envelope.
            // Runtime facts are input evidence, never an assistant's previous final answer.
            // Place them before the transcript so the current Tool turn stays the dialogue tail.
            out.add(new ContextSource(79,fact.sequence(),sourceClass,fact.kind(),fact.sourceId(),
                    fact.revision(),fact.authority(),fact.scope(),compactability,
                    PayloadMode.INLINE_SANITIZED,RENDERER,clean,hash,
                    PrivacyClass.SENSITIVE_REDACTED,BOUND,!redundant,AgentMessage.Role.USER,
                    AgentMessage.MessageType.USER,null,fact.references()));
        }
    }

    /** Only hide successful execution metadata when a durable call-bound dialogue represents it.
     * PLAN has no durable transcript; orphan, unresolved and unproven batch facts stay visible. */
    private Set<String> observedSuccessfulOccurrences(Identity identity)throws SQLException{
        var result=new HashSet<String>();
        try(var statement=connection.prepareStatement("""
          SELECT DISTINCT o.occurrence_id,observation.tool_call_id,action.tool_call_id,
                 action.tool_call_json FROM side_effect_occurrences o
          JOIN tool_calls t ON t.task_id=o.task_id AND (
            (o.tool_call_id IS NOT NULL AND t.tool_call_id=o.tool_call_id) OR
            (o.tool_call_id IS NULL AND CAST(t.iteration AS TEXT)=o.iteration_id AND t.tool_name=o.target))
          JOIN events observation ON observation.task_id=t.task_id
            AND observation.tool_call_id=t.tool_call_id AND observation.type='OBSERVATION'
            AND observation.iteration=t.iteration AND CAST(t.iteration AS TEXT)=o.iteration_id
          JOIN events action ON action.task_id=observation.task_id
            AND action.type='ACTION'
            AND action.iteration=observation.iteration AND action.id<observation.id
          WHERE o.task_id=? AND o.run_id=? AND o.operation_type='TOOL'
            AND o.status='COMPLETED' AND t.result_success=1
          """)){
            statement.setString(1,identity.taskId());statement.setString(2,identity.runId());
            try(var rows=statement.executeQuery()){while(rows.next()){
                String callId=rows.getString(2);
                if(actionContainsCall(rows.getString(3),rows.getString(4),callId))
                    result.add(rows.getString(1));
            }}
        }
        return result;
    }

    private boolean actionContainsCall(String primaryId,String json,String callId){
        if(callId==null)return false;
        // Preserve the legacy single-call binding; batch members require the exact JSON ID.
        if(callId.equals(primaryId))return true;
        if(json==null)return false;
        try{
            var calls=JSON.readTree(json);
            if(!calls.isArray())return false;
            int matches=0;
            for(var call:calls)if(callId.equals(call.path("id").asText(null)))matches++;
            return matches==1;
        }catch(java.io.IOException invalid){return false;}
    }

    private void addPlanInlineSources(List<ContextSource> out,ContextInvocation invocation,
            List<AgentMessage> messages){
        if(messages==null||messages.isEmpty())throw new ContextSourceFailure("CONTEXT_SOURCE_INTEGRITY");
        long sequence=0;
        for(AgentMessage message:messages){
            if(message==null||message.role()!=AgentMessage.Role.USER
                    ||message.type()!=AgentMessage.MessageType.USER)
                throw new ContextSourceFailure("CONTEXT_SOURCE_INTEGRITY");
            String payload=clean(message.content());String id=invocation.identity().contextGenerationId()
                    +":PLAN_REQUEST:"+sequence;
            out.add(inline(90,sequence++,SourceClass.FRESH_USER_INTENT,"PLAN_REQUEST",id,
                    invocation.operationSequence(),"CONTEXT_OWNED_DURABLE_INLINE",Scope.RUN_SCOPED,
                    Compactability.NON_COMPACTABLE,true,AgentMessage.Role.USER,
                    AgentMessage.MessageType.USER,payload));
        }
    }

    private void addDurableEvents(List<ContextSource> out,ContextInvocation invocation,
            List<AgentMessage> compatibility,Set<String> compactedEvents)throws SQLException{
        var rows=new ArrayList<EventRow>();
        try(var s=connection.prepareStatement("""
          SELECT id,iteration,role,type,content,tool_call_id,tool_call_json,created_at FROM events
          WHERE task_id=? ORDER BY id
          """)){
            s.setString(1,invocation.identity().taskId());try(var r=s.executeQuery()){while(r.next())
                rows.add(new EventRow(r.getLong(1),r.getInt(2),AgentMessage.Role.valueOf(r.getString(3)),
                        AgentMessage.MessageType.valueOf(r.getString(4)),clean(r.getString(5)),
                        r.getString(6),cleanNullable(r.getString(7)),r.getString(8)));}
        }
        List<AgentMessage> supplied=compatibility==null?List.of():compatibility;
        if(rows.size()!=supplied.size())throw new ContextSourceFailure("EVENT_COUNT_MISMATCH");
        for(int i=0;i<rows.size();i++){
            EventRow row=rows.get(i);AgentMessage message=supplied.get(i);
            if(message==null||message.role()!=row.role()||message.type()!=row.type()
                    ||!Objects.equals(message.toolCallId(),row.toolCallId())
                    ||!Objects.equals(cleanNullable(message.toolCallJson()),row.toolCallJson()))
                throw new ContextSourceFailure("EVENT_SOURCE_IDENTITY_MISMATCH");
            if(row.type()!=AgentMessage.MessageType.ACTION
                    &&!clean(message.content()).equals(row.content()))
                throw new ContextSourceFailure("CONTEXT_SOURCE_PAYLOAD_MISMATCH");
            if(row.type()==AgentMessage.MessageType.OBSERVATION
                    &&row.toolCallJson()!=null)
                throw new ContextSourceFailure("OBSERVATION_PRIVATE_METADATA_MISPLACED");
            validateCanonicalToolFact(invocation.identity().taskId(),row);
        }
        if(invocation.operation()==OperationKind.TOOL_CONTINUATION
                &&(rows.isEmpty()||rows.getLast().type()!=AgentMessage.MessageType.OBSERVATION))
            throw new ContextSourceFailure("TOOL_RESULT_SOURCE_MISSING");
        if(invocation.operation()==OperationKind.REPAIR&&!runningRepair(invocation.identity()))
            throw new ContextSourceFailure("CONTEXT_SOURCE_INTEGRITY");
        ProjectionCoverage projectedPairs=addCompletedToolPairProjections(
                out,invocation.identity(),rows,compactedEvents);
        out.removeIf(source->projectedPairs.occurrenceIds().contains(source.stableSourceId())
                &&Set.of("TOOL_OCCURRENCE","TOOL_RESULT").contains(source.kind()));
        for(int i=0;i<out.size();i++){
            ContextSource source=out.get(i);
            if(Set.of("TOOL_OCCURRENCE","TOOL_RESULT").contains(source.kind())
                    &&source.compactability()==Compactability.COMPACTABLE)
                out.set(i,preserveVerbatimToolSource(source));
        }
        for(EventRow row:rows){
            String sourceId=Long.toString(row.id());
            if(compactedEvents.contains(sourceKey("EVENT",sourceId,
                            Integer.toString(row.iteration()),
                            DeterministicContextCompiler.hash(row.content())))
                    ||projectedPairs.eventIds().contains(sourceId))continue;
            boolean durableUserIntent=row.role()==AgentMessage.Role.USER
                    &&row.type()==AgentMessage.MessageType.USER;
            boolean runtimePrompt=row.role()==AgentMessage.Role.USER
                    &&(row.type()==AgentMessage.MessageType.RUNTIME_STEP_PROMPT
                    ||row.type()==AgentMessage.MessageType.RUNTIME_FINAL_PROMPT);
            if(row.role()==AgentMessage.Role.USER&&!durableUserIntent&&!runtimePrompt)
                throw new ContextSourceFailure("CONTEXT_SOURCE_USER_ORIGIN_INVALID");
            SourceClass source=durableUserIntent?SourceClass.FRESH_USER_INTENT:
                    SourceClass.COMPACTABLE_DURABLE_HISTORY;
            boolean durableToolObservation=row.type()==AgentMessage.MessageType.OBSERVATION
                    &&row.toolCallId()!=null;
            if(invocation.operation()==OperationKind.TOOL_CONTINUATION&&row.id()==rows.getLast().id()
                    &&!durableToolObservation)
                throw new ContextSourceFailure("TOOL_RESULT_SOURCE_BINDING_MISSING");
            Compactability compact=durableUserIntent||row.type()==AgentMessage.MessageType.ACTION
                    ||row.type()==AgentMessage.MessageType.OBSERVATION
                    ?Compactability.NON_COMPACTABLE:Compactability.COMPACTABLE;
            String payload=row.content();
            String hash=DeterministicContextCompiler.hash(payload);
            var refs=new ArrayList<SourceReference>();
            refs.add(new SourceReference("EVENT",sourceId,Integer.toString(row.iteration()),
                    DeterministicContextCompiler.hash(row.content()),0));
            String kind=durableUserIntent?"DURABLE_USER_INTENT":
                    runtimePrompt?"DURABLE_RUNTIME_PROMPT":
                    row.type()==AgentMessage.MessageType.FINAL?"DURABLE_ASSISTANT_RESULT":
                            "EVENT_"+row.type();
            // Stable user intent and runtime guidance precede growing history. Do not change
            // their roles or promote imported instructions/observations to system authority.
            out.add(new ContextSource(durableUserIntent?55:runtimePrompt?60:80,row.id(),source,
                    kind,sourceId,Integer.toString(row.iteration()),"DURABLE_EVENT",
                    Scope.TASK_SCOPED,compact,PayloadMode.REFERENCE_RENDERED,RENDERER,payload,hash,
                    PrivacyClass.SENSITIVE_REDACTED,BOUND,true,row.role(),
                    runtimePrompt?AgentMessage.MessageType.USER:row.type(),
                    row.toolCallId(),row.toolCallJson(),refs));
        }
    }

    /**
     * A remaining semantic Tool source belongs to the most recent or otherwise
     * non-projectable pair.  It is part of the verbatim safety boundary and must
     * not be selected independently from its matching raw ACTION/OBSERVATION.
     */
    private ContextSource preserveVerbatimToolSource(ContextSource source){
        return new ContextSource(source.priority(),source.sequence(),
                SourceClass.NON_COMPACTABLE_AUTHORITY,source.kind(),source.stableSourceId(),
                source.revision(),source.authorityKind(),source.scope(),
                Compactability.NON_COMPACTABLE,source.payloadMode(),source.rendererVersion(),
                source.renderedPayload(),source.contentHash(),source.privacyClass(),source.bounds(),
                source.providerVisible(),source.role(),source.messageType(),source.toolCallId(),
                source.toolCallJson(),source.references());
    }

    /** Replaces only complete, interpretable old results; success alone does not make evidence disposable. */
    private ProjectionCoverage addCompletedToolPairProjections(List<ContextSource> out,Identity identity,
            List<EventRow> rows,Set<String> compactedEvents)throws SQLException{
        var pairs=new ArrayList<ToolPair>();
        for(int i=0;i+1<rows.size();i++){
            EventRow action=rows.get(i),observation=rows.get(i+1);
            if(action.type()!=AgentMessage.MessageType.ACTION
                    ||!isSingleToolCallAction(action)
                    ||observation.type()!=AgentMessage.MessageType.OBSERVATION
                    ||action.toolCallId()==null||!action.toolCallId().equals(observation.toolCallId()))continue;
            if(compactedEvents.contains(sourceKey("EVENT",Long.toString(action.id()),
                            Integer.toString(action.iteration()),
                            DeterministicContextCompiler.hash(action.content())))
                    ||compactedEvents.contains(sourceKey("EVENT",Long.toString(observation.id()),
                            Integer.toString(observation.iteration()),
                            DeterministicContextCompiler.hash(observation.content()))))continue;
            ToolPair pair=readSuccessfulPair(identity,action,observation);if(pair!=null)pairs.add(pair);
        }
        if(pairs.size()<2)return ProjectionCoverage.EMPTY;
        var projectedEvents=new HashSet<String>();
        var projectedOccurrences=new HashSet<String>();
        for(ToolPair pair:pairs.subList(0,pairs.size()-1)){
            if(!pair.projectable())continue;
            String payload=clean(pair.payload()),hash=DeterministicContextCompiler.hash(payload);
            out.add(new ContextSource(78,pair.action().id(),SourceClass.COMPACTABLE_DURABLE_HISTORY,
                    "COMPLETED_TOOL_PAIR","tool-pair:"+pair.action().toolCallId(),pair.revision(),
                    "R1A_COMPLETED_TOOL_PAIR",Scope.RUN_SCOPED,Compactability.COMPACTABLE,
                    PayloadMode.INLINE_SANITIZED,RENDERER,payload,hash,
                    PrivacyClass.SENSITIVE_REDACTED,BOUND,true,AgentMessage.Role.USER,
                    AgentMessage.MessageType.USER,null,null,pair.references()));
            projectedEvents.add(Long.toString(pair.action().id()));
            projectedEvents.add(Long.toString(pair.observation().id()));
            projectedOccurrences.add(pair.occurrenceId());
        }
        return new ProjectionCoverage(Set.copyOf(projectedEvents),
                Set.copyOf(projectedOccurrences));
    }

    /**
     * A Provider assistant turn containing multiple Tool calls is one protocol unit. Projecting
     * only its first adjacent ACTION/OBSERVATION member would leave the remaining Tool results
     * orphaned and make a later continuation structurally invalid. Batch turns therefore stay
     * verbatim until an atomic batch projection exists.
     */
    private boolean isSingleToolCallAction(EventRow action){
        if(action.toolCallJson()==null||action.toolCallJson().isBlank())return false;
        try{
            var parsed=JSON.readTree(action.toolCallJson());
            if(parsed.isArray())return parsed.size()==1
                    &&action.toolCallId().equals(parsed.get(0).path("id").asText(null));
            return parsed.isObject()
                    &&action.toolCallId().equals(parsed.path("id").asText(null));
        }catch(Exception invalid){return false;}
    }

    private ToolPair readSuccessfulPair(Identity identity,EventRow action,EventRow observation)
            throws SQLException{
        try(var statement=connection.prepareStatement("""
          SELECT t.tool_name,t.arguments,t.result_output,o.occurrence_id,o.status,o.result_hash,
                 o.external_outcome,o.started_at,o.completed_at
          FROM tool_calls t JOIN side_effect_occurrences o
            ON o.task_id=t.task_id AND o.run_id=? AND o.tool_call_id=t.tool_call_id
          WHERE t.task_id=? AND t.tool_call_id=? AND t.result_success=1 AND o.status='COMPLETED'
          """)){
            statement.setString(1,identity.runId());statement.setString(2,identity.taskId());
            statement.setString(3,action.toolCallId());
            try(var row=statement.executeQuery()){
                if(!row.next())return null;
                String tool=clean(row.getString(1)),arguments=cleanNullable(row.getString(2));
                String result=cleanNullable(row.getString(3)),occurrence=row.getString(4);
                String argumentSummary=completeArgumentSummary(tool,arguments);
                // This is a representation change, not a semantic summary. Truncation here
                // used to discard loaded instructions, resource paths and evidence even when
                // the Context budget was ample. Keep the existing verbatim protocol pair if
                // its query/result cannot be represented completely by this bounded format.
                // A canonical execution envelope has a separate authority/scope contract;
                // never copy it into the public completed-pair payload inherited by children.
                boolean projectable=argumentSummary!=null&&(result==null||result.length()<=1024
                        &&!result.contains(TOOL_FACT_MARKER));
                String digest=DeterministicContextCompiler.hash(tool+'|'+Objects.toString(arguments,"")
                        +'|'+Objects.toString(result,"")+'|'+Objects.toString(row.getString(6),""));
                String payload=projectable?"kind=COMPLETED_TOOL_PAIR;tool_name="+escapeFact(tool)
                        +";arguments_summary="+escapeFact(argumentSummary)
                        +";status=COMPLETED;result_excerpt="+escapeFact(result)
                        +";occurrence_id="+escapeFact(occurrence)+";digest="+digest:"";
                var refs=new ArrayList<SourceReference>();
                refs.add(new SourceReference("EVENT",Long.toString(action.id()),
                        Integer.toString(action.iteration()),DeterministicContextCompiler.hash(action.content()),0));
                refs.add(new SourceReference("EVENT",Long.toString(observation.id()),
                        Integer.toString(observation.iteration()),
                        DeterministicContextCompiler.hash(observation.content()),1));
                for(ContextSemanticSourceAdapter.SemanticFact fact:
                        new ContextSemanticSourceAdapter(connection).read(identity)){
                    if(!occurrence.equals(fact.sourceId()))continue;
                    for(SourceReference ref:fact.references())refs.add(new SourceReference(ref.type(),ref.id(),
                            ref.revision(),ref.hash(),refs.size()));
                }
                String revision=DeterministicContextCompiler.hash(Objects.toString(row.getString(5),"")+'|'
                        +Objects.toString(row.getString(6),"")+'|'+Objects.toString(row.getString(7),"")+'|'
                        +Objects.toString(row.getString(8),"")+'|'+Objects.toString(row.getString(9),""));
                return new ToolPair(action,observation,occurrence,revision,payload,List.copyOf(refs),projectable);
            }
        }
    }

    private String completeArgumentSummary(String tool,String arguments){
        // Only these existing query shapes have a public projection contract. Other tools
        // (including Skill activation/resources) retain their sanitized tool dialogue.
        if(!Set.of("read_file","list_files","grep","git_diff").contains(tool))
            return null;
        if(arguments==null)return null;
        try{
            var parsed=JSON.readTree(arguments);
            if(parsed==null||!parsed.isObject())return null;
            String rendered=clean(JSON.writeValueAsString(parsed));
            return rendered.length()<=512?rendered:null;
        }catch(Exception invalid){return null;}
    }
    private static String escapeFact(String value){return Objects.toString(value,"").replace("\\","\\\\")
            .replace(";","\\;").replace("=","\\=").replace("\r","\\r").replace("\n","\\n");}

    private static String sourceKey(String type,String id,String revision,String hash){
        return type+'|'+id+'|'+Objects.toString(revision,"")+'|'+hash;
    }

    private void validateCanonicalToolFact(String taskId,EventRow row)throws SQLException{
        String eventEnvelope=terminalToolFactEnvelope(row.content());
        if(eventEnvelope==null)return;
        if(row.type()!=AgentMessage.MessageType.OBSERVATION
                ||row.toolCallId()==null||row.toolCallId().isBlank())
            throw new ContextSourceFailure("CONTEXT_SOURCE_ID_MISSING");
        String toolName=null,resultOutput=null;int count=0;
        try(var s=connection.prepareStatement("""
          SELECT tool_name,result_output FROM tool_calls
          WHERE task_id=? AND tool_call_id=? ORDER BY id
          """)){
            s.setString(1,taskId);s.setString(2,row.toolCallId());
            try(var r=s.executeQuery()){while(r.next()){
                count++;toolName=r.getString(1);resultOutput=clean(r.getString(2));
            }}
        }
        if(count==0)throw new ContextSourceFailure(
                "CONTEXT_SOURCE_OBSERVATION_BINDING_MISSING");
        if(count>1)throw new ContextSourceFailure("CONTEXT_SOURCE_ID_DUPLICATE");
        if(!"list_files".equals(toolName))throw new ContextSourceFailure(
                "CONTEXT_SOURCE_TOOL_KIND_MISMATCH");
        String durableEnvelope=terminalToolFactEnvelope(resultOutput);
        if(durableEnvelope==null||!eventEnvelope.equals(durableEnvelope))
            throw new ContextSourceFailure("CONTEXT_SOURCE_PAYLOAD_MISMATCH");
    }

    private static String terminalToolFactEnvelope(String value){
        if(value==null)return null;
        int marker=value.lastIndexOf(TOOL_FACT_MARKER);if(marker<0)return null;
        String envelope=value.substring(marker+TOOL_FACT_MARKER.length()).trim();
        return envelope.isEmpty()?null:envelope;
    }

    private boolean runningRepair(Identity id)throws SQLException{
        try(var s=connection.prepareStatement("SELECT 1 FROM repair_attempts WHERE task_id=? AND run_id=? AND status='RUNNING' LIMIT 1")){
            s.setString(1,id.taskId());s.setString(2,id.runId());try(var r=s.executeQuery()){return r.next();}}
    }

    private ContextSource inline(int priority,long sequence,SourceClass sourceClass,String kind,
            String sourceId,String revision,String authority,Scope scope,Compactability compact,
            boolean visible,AgentMessage.Role role,AgentMessage.MessageType type,String payload){
        String clean=clean(payload),hash=DeterministicContextCompiler.hash(clean);
        return new ContextSource(priority,sequence,sourceClass,kind,sourceId,revision,authority,scope,
                compact,PayloadMode.INLINE_SANITIZED,RENDERER,clean,hash,
                PrivacyClass.SENSITIVE_REDACTED,BOUND,visible,role,type,null,
                List.of(new SourceReference("CONTEXT_INLINE",sourceId,revision,hash,0)));
    }
    private ContextSource reference(int priority,long sequence,SourceClass sourceClass,String kind,
            String sourceId,String revision,String authority,Scope scope,Compactability compact,
            boolean visible,AgentMessage.Role role,AgentMessage.MessageType type,String payload,
            String refType,String refId){
        return reference(priority,sequence,sourceClass,kind,sourceId,revision,authority,scope,compact,
                visible,role,type,payload,refType,refId,null);
    }
    private ContextSource reference(int priority,long sequence,SourceClass sourceClass,String kind,
            String sourceId,String revision,String authority,Scope scope,Compactability compact,
            boolean visible,AgentMessage.Role role,AgentMessage.MessageType type,String payload,
            String refType,String refId,String toolCallId){
        String clean=clean(payload),hash=DeterministicContextCompiler.hash(clean);
        return new ContextSource(priority,sequence,sourceClass,kind,sourceId,revision,authority,scope,
                compact,PayloadMode.REFERENCE_RENDERED,RENDERER,clean,hash,
                PrivacyClass.SENSITIVE_REDACTED,BOUND,visible,role,type,toolCallId,
                List.of(new SourceReference(refType,refId,revision,hash,0)));
    }
    private String clean(String value){return sanitizer.sanitizeAndTruncate(
            value==null?"":value,BOUND);}
    private String cleanNullable(String value){return value==null?null:clean(value);}

    public record Assembled(ContextInvocation invocation,ContextSourceSet sources,List<ToolDefinition> tools){
        public Assembled{tools=List.copyOf(tools);}
    }
    private record EventRow(long id,int iteration,AgentMessage.Role role,AgentMessage.MessageType type,
            String content,String toolCallId,String toolCallJson,String createdAt){}
    private record ToolPair(EventRow action,EventRow observation,String occurrenceId,String revision,String payload,
            List<SourceReference> references,boolean projectable){ToolPair{references=List.copyOf(references);}}
    private record ProjectionCoverage(Set<String> eventIds,Set<String> occurrenceIds){
        private static final ProjectionCoverage EMPTY=new ProjectionCoverage(Set.of(),Set.of());
        private ProjectionCoverage{eventIds=Set.copyOf(eventIds);occurrenceIds=Set.copyOf(occurrenceIds);}
    }
    public static final class ContextSourceFailure extends RuntimeException{
        public ContextSourceFailure(String code){super(code);}
    }
}
