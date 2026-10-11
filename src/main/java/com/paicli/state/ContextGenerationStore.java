package com.paicli.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.context.ContextModels.*;
import com.paicli.context.CompactTargetModels.*;
import com.paicli.context.DeterministicContextCompiler;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.state.R1aDurableModels.ExecutionAuthoritySnapshot;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** V26 transactional Context Generation, pointer CAS, and provider-free compaction store. */
public final class ContextGenerationStore {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final Connection connection;
    private boolean immediateTransactionActive;
    private final ContextSafePointAuthority safePoints;
    private final com.paicli.runtime.PersistenceSanitizer sanitizer=new com.paicli.runtime.PersistenceSanitizer();
    public ContextGenerationStore(Connection connection){this.connection=Objects.requireNonNull(connection);
        this.safePoints=new ContextSafePointAuthority(connection);}

    public Published publishBase(CompiledContext compiled) throws SQLException {
        if(compiled.budget().decision()!=Budget.Decision.READY
                &&compiled.budget().decision()!=Budget.Decision.COMPACTION_REQUIRED)
            throw new ContextFailure(compiled.budget().decision().name());
        synchronized(connection){return transaction(()->{
            validateSourceClosure(compiled);
            Pointer old=pointer(compiled.identity().taskId(),compiled.identity().runId(),compiled.operation()).orElse(null);
            insertGeneration(compiled,GenerationKind.BASE,null,old==null?0:old.version(),Lifecycle.PREPARING);
            insertSegments(compiled.identity().contextGenerationId(),compiled.segments());
            if(!storedSourceClosureValid(compiled.identity().contextGenerationId()))
                throw new ContextFailure("CONTEXT_SOURCE_INTEGRITY");
            transitionPrepared(compiled.identity().contextGenerationId(),Lifecycle.PREPARING,Lifecycle.READY,
                    compiled.compiledHash(),compiled.budget(),null);
            casPointer(compiled.identity().taskId(),compiled.identity().runId(),compiled.operation(),
                    old,compiled.identity().contextGenerationId());
            if(old!=null&&!old.contextGenerationId().equals(compiled.identity().contextGenerationId()))
                supersede(old.contextGenerationId());
            return new Published(compiled.identity().contextGenerationId(),Lifecycle.READY,
                    old==null?1:old.version()+1,compiled.budget());
        });}
    }

    private void validateSourceClosure(CompiledContext compiled)throws SQLException{
        try(var statement=connection.prepareStatement("""
          SELECT w.workspace_identity,t.admitted_manifest_hash,p.active_generation_id,p.owner_run_id,
                 a.active_plan_revision_id,c.active_revision_id
          FROM task_identities t
          JOIN work_threads w ON w.thread_id=t.thread_id
          JOIN task_context_pointers p ON p.task_id=t.task_id
          JOIN durable_runs r ON r.task_id=t.task_id AND r.run_id=p.owner_run_id
             AND r.thread_id=t.thread_id
          LEFT JOIN task_plan_authorities a ON a.task_id=t.task_id
          LEFT JOIN task_constraint_activations c ON c.task_id=t.task_id
          WHERE t.task_id=? AND t.thread_id=? AND p.owner_run_id=?
          """)){
            var identity=compiled.identity();statement.setString(1,identity.taskId());
            statement.setString(2,identity.threadId());statement.setString(3,identity.runId());
            try(var row=statement.executeQuery()){
                if(!row.next()||!Objects.equals(row.getString(1),identity.workspaceId())
                        ||!Objects.equals(row.getString(2),identity.manifestHash())
                        ||!Objects.equals(row.getString(3),identity.authorityGenerationId())
                        ||!Objects.equals(row.getString(4),identity.runId())
                        ||!Objects.equals(row.getString(5),identity.planRevision())
                        ||!Objects.equals(row.getString(6),identity.steeringRevision()))
                    throw new ContextFailure("CONTEXT_STALE_SOURCE");
            }
        }
    }

    private boolean storedAuthorityCurrent(String contextId)throws SQLException{
        try(var statement=connection.prepareStatement("""
          SELECT count(*) FROM context_generations g
          JOIN task_identities t ON t.task_id=g.task_id AND t.thread_id=g.thread_id
          JOIN task_context_pointers p ON p.task_id=g.task_id
          WHERE g.context_generation_id=? AND p.owner_run_id=g.run_id
            AND p.active_generation_id=g.source_authority_generation_id
            AND t.admitted_manifest_hash=g.manifest_hash
            AND g.plan_revision IS (SELECT active_plan_revision_id FROM task_plan_authorities
                                    WHERE task_id=g.task_id)
            AND g.steering_revision IS (SELECT active_revision_id FROM task_constraint_activations
                                        WHERE task_id=g.task_id)
          """)){
            statement.setString(1,contextId);try(var row=statement.executeQuery()){
                return row.next()&&row.getLong(1)==1;}
        }
    }

    public Optional<Pointer> pointer(String taskId,String runId,OperationKind operation)throws SQLException{
        try(var s=connection.prepareStatement("""
            SELECT active_context_generation_id,pointer_version FROM context_generation_pointers
            WHERE task_id=? AND run_id=? AND operation_kind=?
            """)){s.setString(1,taskId);s.setString(2,runId);s.setString(3,operation.name());
            try(var r=s.executeQuery()){return r.next()?Optional.of(new Pointer(taskId,runId,operation,
                    r.getString(1),r.getLong(2))):Optional.empty();}}
    }

    /** Resolves one complete mutation target in the caller's snapshot; no latest fallback exists. */
    public CompactTargetResolution resolveTarget(String taskId,String runId,OperationKind operation,
            String sessionId,TargetRelation relation)throws SQLException{
        if(taskId==null||runId==null)return CompactTargetResolution.failure(ResolutionStatus.NOT_APPLICABLE);
        if(relation==TargetRelation.CURRENT_SESSION_RUN&&!sessionRunCurrent(taskId,runId))
            return CompactTargetResolution.failure(ResolutionStatus.STALE_SESSION_BINDING);
        String sql="""
          SELECT p.operation_kind,p.active_context_generation_id,p.pointer_version,p.updated_at,
                 g.workspace_id,g.thread_id,g.task_id,g.run_id,g.source_authority_generation_id,
                 g.operation_kind,g.provider,g.model,g.capability_profile,g.lifecycle,
                 g.input_estimate,g.tool_schema_estimate,g.output_reserve,g.safety_margin,g.context_limit,
                 EXISTS(SELECT 1 FROM context_segments s WHERE s.context_generation_id=g.context_generation_id
                    AND s.compactability='COMPACTABLE'),
                 w.workspace_identity,ag.task_id
          FROM context_generation_pointers p
          LEFT JOIN context_generations g ON g.context_generation_id=p.active_context_generation_id
          LEFT JOIN task_identities t ON t.task_id=p.task_id
          LEFT JOIN work_threads w ON w.thread_id=t.thread_id
          LEFT JOIN task_context_generations ag ON ag.generation_id=g.source_authority_generation_id
          WHERE p.task_id=? AND p.run_id=?
          """+(operation==null?" ORDER BY p.operation_kind":" AND p.operation_kind=? ORDER BY p.operation_kind");
        List<CompactTargetIdentity> legal=new ArrayList<>();
        boolean saw=false;boolean notApplicable=false;
        try(var statement=connection.prepareStatement(sql)){
            statement.setString(1,taskId);statement.setString(2,runId);
            if(operation!=null)statement.setString(3,operation.name());
            try(var row=statement.executeQuery()){while(row.next()){
                saw=true;
                String source=row.getString(2),workspace=row.getString(5),thread=row.getString(6);
                String generationTask=row.getString(7),generationRun=row.getString(8),sourceGen=row.getString(9);
                OperationKind pointerOperation;
                try{pointerOperation=OperationKind.valueOf(row.getString(1));}
                catch(RuntimeException invalid){return CompactTargetResolution.failure(ResolutionStatus.INTEGRITY_FAILURE);}
                if(source==null||workspace==null||thread==null||generationTask==null||generationRun==null
                        ||sourceGen==null||row.getString(10)==null||row.getString(11)==null
                        ||row.getString(12)==null||row.getString(13)==null||row.getString(14)==null
                        ||row.getString(21)==null||row.getString(22)==null
                        ||!taskId.equals(generationTask)||!runId.equals(generationRun)
                        ||!pointerOperation.name().equals(row.getString(10))
                        ||!workspace.equals(row.getString(21))||!taskId.equals(row.getString(22))
                        ||row.getLong(3)<1||!storedSourceClosureValid(source))
                    return CompactTargetResolution.failure(ResolutionStatus.INTEGRITY_FAILURE);
                long input=row.getLong(15),tools=row.getLong(16),reserve=row.getLong(17),margin=row.getLong(18);
                Object limitValue=row.getObject(19);boolean compactable=row.getInt(20)!=0;
                long total;
                try{total=Math.addExact(Math.addExact(input,tools),Math.addExact(reserve,margin));}
                catch(ArithmeticException overflow){return CompactTargetResolution.failure(
                        ResolutionStatus.INTEGRITY_FAILURE);}
                boolean sourceAllowed="READY".equals(row.getString(14))&&limitValue!=null&&compactable
                        &&(total>=0);
                if(!sourceAllowed){notApplicable=true;continue;}
                String snapshot=com.paicli.context.DeterministicContextCompiler.hash(taskId+'|'+runId+'|'
                        +pointerOperation+'|'+source+'|'+row.getLong(3)+'|'+row.getString(4));
                legal.add(new CompactTargetIdentity(workspace,sessionId,thread,taskId,runId,
                        pointerOperation,source,row.getLong(3),sourceGen,row.getString(11),
                        row.getString(12),row.getString(13),snapshot,relation));
            }}
        }
        if(!saw)return CompactTargetResolution.failure(ResolutionStatus.NOT_FOUND);
        if(legal.isEmpty())return CompactTargetResolution.failure(notApplicable
                ?ResolutionStatus.NOT_APPLICABLE:ResolutionStatus.NOT_FOUND);
        if(legal.size()>1)return CompactTargetResolution.ambiguous(legal.stream()
                .map(x->new CandidateSummary(x.taskId(),x.runId(),x.operation(),
                        x.sourceContextGenerationId())).toList());
        return CompactTargetResolution.present(legal.getFirst());
    }

    /** Resolves an explicit Candidate without rebinding it to a latest Task, Run or operation. */
    public CompactTargetResolution resolveCandidateTarget(String candidateId,String expectedTask,
            String expectedRun,String sessionId,TargetRelation relation)throws SQLException{
        Generation candidate=find(candidateId).orElse(null);
        if(candidate==null)return CompactTargetResolution.failure(ResolutionStatus.NOT_FOUND);
        if(expectedTask!=null&&!expectedTask.equals(candidate.taskId()))
            return CompactTargetResolution.failure(ResolutionStatus.CROSS_TASK_INVALID);
        if(expectedRun!=null&&!expectedRun.equals(candidate.runId()))
            return CompactTargetResolution.failure(ResolutionStatus.CROSS_RUN_INVALID);
        if(relation==TargetRelation.CURRENT_SESSION_RUN&&!sessionRunCurrent(candidate.taskId(),candidate.runId()))
            return CompactTargetResolution.failure(ResolutionStatus.STALE_SESSION_BINDING);
        if(candidate.kind()!=GenerationKind.COMPACTED||candidate.sourceContextId()==null
                ||(candidate.lifecycle()!=Lifecycle.PREPARED&&candidate.lifecycle()!=Lifecycle.READY))
            return CompactTargetResolution.failure(ResolutionStatus.NOT_APPLICABLE);
        Generation source=find(candidate.sourceContextId()).orElse(null);
        Pointer pointer=pointer(candidate.taskId(),candidate.runId(),candidate.operation()).orElse(null);
        if(source==null||pointer==null||!source.taskId().equals(candidate.taskId())
                ||!source.runId().equals(candidate.runId())||source.operation()!=candidate.operation()
                ||!Objects.equals(source.sourceFingerprint(),candidate.sourceFingerprint())
                ||!Objects.equals(source.provider(),candidate.provider())
                ||!Objects.equals(source.model(),candidate.model())
                ||!Objects.equals(source.capabilityProfile(),candidate.capabilityProfile())
                ||!Objects.equals(source.manifestHash(),candidate.manifestHash())
                ||!Objects.equals(source.planRevision(),candidate.planRevision())
                ||!Objects.equals(source.steeringRevision(),candidate.steeringRevision())
                ||!storedSourceClosureValid(source.contextId()))
            return CompactTargetResolution.failure(ResolutionStatus.INTEGRITY_FAILURE);
        boolean pointerMatches=candidate.lifecycle()==Lifecycle.PREPARED
                ?pointer.contextGenerationId().equals(source.contextId())
                    &&Objects.equals(candidate.sourcePointerVersion(),pointer.version())
                :pointer.contextGenerationId().equals(candidate.contextId())
                    &&candidate.sourcePointerVersion()!=null
                    &&pointer.version()==candidate.sourcePointerVersion()+1;
        if(!pointerMatches)return CompactTargetResolution.failure(ResolutionStatus.INTEGRITY_FAILURE);
        try(var statement=connection.prepareStatement("""
          SELECT g.workspace_id,g.thread_id,g.source_authority_generation_id,g.provider,g.model,
                 g.capability_profile,p.updated_at,w.workspace_identity,ag.task_id
          FROM context_generations g
          JOIN context_generations source ON source.context_generation_id=g.source_context_generation_id
             AND source.source_authority_generation_id=g.source_authority_generation_id
             AND source.provider=g.provider AND source.model=g.model
             AND source.capability_profile=g.capability_profile
          JOIN task_identities t ON t.task_id=g.task_id
          JOIN work_threads w ON w.thread_id=t.thread_id
          JOIN task_context_generations ag ON ag.generation_id=g.source_authority_generation_id
          JOIN context_generation_pointers p ON p.task_id=g.task_id AND p.run_id=g.run_id
             AND p.operation_kind=g.operation_kind
          WHERE g.context_generation_id=?
          """)){
            statement.setString(1,candidateId);try(var row=statement.executeQuery()){
                if(!row.next()||!Objects.equals(row.getString(1),row.getString(8))
                        ||!candidate.taskId().equals(row.getString(9)))
                    return CompactTargetResolution.failure(ResolutionStatus.INTEGRITY_FAILURE);
                long sourceVersion=Objects.requireNonNull(candidate.sourcePointerVersion());
                String snapshot=com.paicli.context.DeterministicContextCompiler.hash(candidate.taskId()+'|'
                        +candidate.runId()+'|'+candidate.operation()+'|'+source.contextId()+'|'
                        +sourceVersion+'|'+row.getString(7));
                return CompactTargetResolution.present(new CompactTargetIdentity(row.getString(1),
                        sessionId,row.getString(2),candidate.taskId(),candidate.runId(),
                        candidate.operation(),source.contextId(),sourceVersion,row.getString(3),
                        row.getString(4),row.getString(5),row.getString(6),snapshot,relation));
            }
        }
    }

    /** Durable R1 safe point. V25 telemetry is deliberately absent from this authority. */
    public ContextSafePointAuthority.Decision safePoint(Pointer pointer,
            ExecutionAuthoritySnapshot authority)throws SQLException{
        return safePoints.evaluate(pointer.taskId(),pointer.runId(),pointer.contextGenerationId(),
                pointer.version(),authority);
    }

    public ContextSafePointAuthority.Decision autoSafePoint(Pointer pointer,
            ExecutionAuthoritySnapshot authority)throws SQLException{
        return safePoints.evaluateForAuto(pointer.taskId(),pointer.runId(),pointer.contextGenerationId(),
                pointer.version(),authority);
    }

    /** Final Provider gate after READY publication and before the external effect. */
    public void requireProviderAdmission(String contextId,long pointerVersion,String occurrenceId)
            throws SQLException{
        Generation generation=find(contextId).orElseThrow(()->new ContextFailure("CONTEXT_NOT_FOUND"));
        Pointer pointer=pointer(generation.taskId(),generation.runId(),generation.operation()).orElseThrow();
        if(pointer.version()!=pointerVersion||!pointer.contextGenerationId().equals(contextId)
                ||generation.lifecycle()!=Lifecycle.READY||readBudget(contextId).decision()!=Budget.Decision.READY
                ||!storedAuthorityCurrent(contextId)||!storedSourceClosureValid(contextId))
            throw new ContextFailure("CONTEXT_PROVIDER_ADMISSION_REJECTED");
        var decision=safePoints.evaluateForProvider(generation.taskId(),generation.runId(),contextId,
                pointerVersion,occurrenceId,generation.operation());
        if(!decision.safe())throw new ContextFailure(decision.code());
    }

    /** The task's first main-turn policy is immutable across operations, compaction and resumed runs. */
    public void requireOutputRequirement(String taskId, com.paicli.provider.OutputRequirement requirement)
            throws SQLException {
        try(var query=connection.prepareStatement("""
                SELECT s.sanitized_payload,s.content_hash
                FROM context_generations g LEFT JOIN context_segments s
                  ON s.context_generation_id=g.context_generation_id AND s.segment_kind='OUTPUT_REQUIREMENT'
                WHERE g.task_id=? AND g.operation_kind IN ('CHAT','TOOL_CONTINUATION','REPAIR','FINAL_SYNTHESIS')
                """)) {
            query.setString(1,taskId);
            try(var rows=query.executeQuery()) {
                while(rows.next()) {
                    String payload=rows.getString(1);
                    if(payload==null && !requirement.managed())continue; // declared legacy-to-legacy only
                    if(!requirement.identity().equals(payload)
                            ||!DeterministicContextCompiler.hash(payload).equals(rows.getString(2)))
                        throw new ContextFailure("OUTPUT_REQUIREMENT_TASK_DRIFT");
                }
            }
        }
    }

    public Optional<Generation> find(String contextId)throws SQLException{
        try(var s=connection.prepareStatement("""
            SELECT generation_kind,operation_kind,task_id,run_id,source_context_generation_id,
                   source_fingerprint,compiled_hash,lifecycle,state_version,failure_code,
                   source_pointer_version,preview_summary,compiler_version,policy_version,
                   provider,model,capability_profile,manifest_hash,plan_revision,steering_revision
            FROM context_generations WHERE context_generation_id=?
            """)){s.setString(1,contextId);try(var r=s.executeQuery()){return r.next()?Optional.of(new Generation(
                contextId,GenerationKind.valueOf(r.getString(1)),OperationKind.valueOf(r.getString(2)),
                r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),
                Lifecycle.valueOf(r.getString(8)),r.getLong(9),r.getString(10),
                nullableLong(r,11),r.getString(12),r.getString(13),r.getString(14),r.getString(15),
                r.getString(16),r.getString(17),r.getString(18),r.getString(19),r.getString(20))):Optional.empty();}}
    }

    public List<StoredSegment> segments(String contextId)throws SQLException{
        var out=new ArrayList<StoredSegment>();
        try(var s=connection.prepareStatement("""
            SELECT ordinal,source_class,segment_kind,scope,compactability,payload_mode,
                   sanitized_payload,content_hash,estimated_tokens,privacy_class,bounds
            FROM context_segments WHERE context_generation_id=? ORDER BY ordinal
            """)){s.setString(1,contextId);try(var r=s.executeQuery()){while(r.next())out.add(new StoredSegment(
                r.getInt(1),SourceClass.valueOf(r.getString(2)),r.getString(3),Scope.valueOf(r.getString(4)),
                Compactability.valueOf(r.getString(5)),PayloadMode.valueOf(r.getString(6)),r.getString(7),
                r.getString(8),r.getInt(9),PrivacyClass.valueOf(r.getString(10)),r.getInt(11)));}}
        return List.copyOf(out);
    }

    public List<StoredReference> references(String contextId)throws SQLException{
        var out=new ArrayList<StoredReference>();
        try(var s=connection.prepareStatement("""
          SELECT ordinal,source_type,source_id,source_revision,source_hash,source_order
          FROM context_source_references WHERE context_generation_id=? ORDER BY ordinal,source_order
          """)){
            s.setString(1,contextId);try(var r=s.executeQuery()){while(r.next())out.add(new StoredReference(
                    r.getInt(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getInt(6)));}}
        return List.copyOf(out);
    }

    public Optional<Generation> candidateFor(String sourceId)throws SQLException{
        try(var s=connection.prepareStatement("""
          SELECT context_generation_id FROM context_generations
          WHERE source_context_generation_id=?
          ORDER BY CASE lifecycle WHEN 'PREPARED' THEN 0 WHEN 'PREPARING' THEN 1
             WHEN 'FAILED' THEN 2 ELSE 3 END,created_at DESC LIMIT 1
          """)){
            s.setString(1,sourceId);try(var row=s.executeQuery()){
                return row.next()?find(row.getString(1)):Optional.empty();}
        }
    }

    public Budget budget(String contextId)throws SQLException{return readBudget(contextId);}

    public Optional<String> activeCompactedLedger(String taskId,String runId,
            OperationKind operation)throws SQLException{
        Optional<VerifiedActiveLedger> active=verifiedActiveLedger(taskId,runId,operation);
        if(active.isEmpty())return Optional.empty();
        Generation generation=find(active.get().pointer().contextGenerationId()).orElse(null);
        if(generation==null||generation.kind()!=GenerationKind.COMPACTED)return Optional.empty();
        return active.get().segments().stream().map(value->value.segment().sanitizedPayload())
                .findFirst();
    }

    /**
     * Returns an active ledger only after validating the complete immutable
     * generation closure and rechecking the pointer CAS identity.  Callers must
     * not reconstruct this view with ad-hoc SQL.
     */
    public Optional<VerifiedActiveLedger> verifiedActiveLedger(String taskId,String runId,
            OperationKind operation)throws SQLException{
        Pointer before=pointer(taskId,runId,operation).orElse(null);
        if(before==null)return Optional.empty();
        Generation generation=find(before.contextGenerationId()).orElseThrow(()->
                new ContextFailure("COMPACTION_INTEGRITY"));
        if(generation.lifecycle()!=Lifecycle.READY
                ||!Objects.equals(taskId,generation.taskId())
                ||!Objects.equals(runId,generation.runId())
                ||operation!=generation.operation())
            throw new ContextFailure("COMPACTION_INTEGRITY");
        List<StoredSegment> stored=segments(generation.contextId());
        List<StoredSegment> ledgers=stored.stream().filter(segment->
                "COMPACTED_LEDGER".equals(segment.kind())).toList();
        if(ledgers.isEmpty())return Optional.empty();
        boolean closureValid;
        try{closureValid=generationClosureValid(generation,new LinkedHashSet<>());}
        catch(ContextFailure corrupt){closureValid=false;}
        if(!closureValid)throw new ContextFailure("COMPACTION_INTEGRITY");
        var verified=new ArrayList<VerifiedLedgerSegment>();
        var flattenedMemo=new HashMap<String,List<StoredReference>>();
        for(StoredSegment segment:ledgers){
            if(segment.payloadMode()!=PayloadMode.INLINE_SANITIZED
                    ||segment.sanitizedPayload()==null
                    ||!DeterministicContextCompiler.hash(segment.sanitizedPayload())
                            .equals(segment.contentHash()))
                throw new ContextFailure("COMPACTION_INTEGRITY");
            verified.add(new VerifiedLedgerSegment(segment,
                    flattenedLedgerReferences(generation.contextId(),segment.ordinal(),flattenedMemo)));
        }
        Pointer after=pointer(taskId,runId,operation).orElse(null);
        if(!before.equals(after))throw new ContextFailure("COMPACTION_STALE_SOURCE");
        return Optional.of(new VerifiedActiveLedger(before,List.copyOf(verified)));
    }

    /** Captures the capsule-bound parent's newest active conversational pointer.
     * Direct Tool chains advance TOOL_CONTINUATION without refreshing CHAT, so
     * the newest READY generation is the canonical parent-history snapshot. */
    public Optional<ParentHistoryTarget> captureParentHistoryTarget(String childTaskId,
            String childRunId,String childGenerationId)throws SQLException{
        ParentBinding binding=parentBinding(childTaskId,childRunId,childGenerationId);
        Pointer pointer=null;
        try(var statement=connection.prepareStatement("""
          SELECT p.operation_kind,p.active_context_generation_id,p.pointer_version
          FROM context_generation_pointers p
          JOIN context_generations g ON g.context_generation_id=p.active_context_generation_id
          WHERE p.task_id=? AND p.run_id=? AND p.operation_kind IN ('CHAT','TOOL_CONTINUATION')
            AND g.lifecycle='READY'
          ORDER BY g.created_at DESC,
                   CASE p.operation_kind WHEN 'TOOL_CONTINUATION' THEN 0 ELSE 1 END
          LIMIT 1
          """)){
            statement.setString(1,binding.parentTaskId());statement.setString(2,binding.parentRunId());
            try(var row=statement.executeQuery()){if(row.next())pointer=new Pointer(
                    binding.parentTaskId(),binding.parentRunId(),OperationKind.valueOf(row.getString(1)),
                    row.getString(2),row.getLong(3));}
        }
        return pointer==null?Optional.empty():Optional.of(new ParentHistoryTarget(childTaskId,
                childRunId,childGenerationId,binding.parentTaskId(),binding.parentRunId(),pointer));
    }

    public Optional<VerifiedParentHistory> verifiedParentHistory(String childTaskId,
            String childRunId,String childGenerationId)throws SQLException{
        Optional<ParentHistoryTarget> target=captureParentHistoryTarget(childTaskId,childRunId,
                childGenerationId);
        return target.isEmpty()?Optional.empty():Optional.of(verifiedParentHistory(target.get()));
    }

    /** Store-owned fail-closed parent projection. Callers receive only bounded,
     * non-authority facts and exact PARENT_CONTEXT_SEGMENT anchors. */
    public VerifiedParentHistory verifiedParentHistory(ParentHistoryTarget target)throws SQLException{
        Objects.requireNonNull(target);
        ParentBinding binding=parentBinding(target.childTaskId(),target.childRunId(),
                target.childGenerationId());
        if(!Objects.equals(binding.parentTaskId(),target.parentTaskId())
                ||!Objects.equals(binding.parentRunId(),target.parentRunId()))
            throw new ContextFailure("CONTEXT_IDENTITY_MISMATCH");
        OperationKind operation=target.pointer().operation();
        if(operation!=OperationKind.CHAT&&operation!=OperationKind.TOOL_CONTINUATION)
            throw new ContextFailure("CONTEXT_IDENTITY_MISMATCH");
        Pointer before=pointer(target.parentTaskId(),target.parentRunId(),operation)
                .orElse(null);
        if(!target.pointer().equals(before))throw new ContextFailure("COMPACTION_STALE_SOURCE");
        Generation generation=find(before.contextGenerationId()).orElseThrow(()->
                new ContextFailure("COMPACTION_INTEGRITY"));
        if(generation.lifecycle()!=Lifecycle.READY||generation.operation()!=operation
                ||!Objects.equals(generation.taskId(),target.parentTaskId())
                ||!Objects.equals(generation.runId(),target.parentRunId()))
            throw new ContextFailure("COMPACTION_INTEGRITY");
        boolean closureValid;
        try{closureValid=generationClosureValid(generation,new LinkedHashSet<>());}
        catch(ContextFailure corrupt){closureValid=false;}
        if(!closureValid)throw new ContextFailure("COMPACTION_INTEGRITY");
        List<ParentHistoryProjection> projections=parentHistoryProjections(generation);
        Pointer after=pointer(target.parentTaskId(),target.parentRunId(),operation)
                .orElse(null);
        if(!before.equals(after))throw new ContextFailure("COMPACTION_STALE_SOURCE");
        return new VerifiedParentHistory(target,List.copyOf(projections));
    }

    private ParentBinding parentBinding(String childTaskId,String childRunId,
            String childGenerationId)throws SQLException{
        try(var statement=connection.prepareStatement("""
          SELECT b.parent_task_id,c.parent_run_id
          FROM capsule_child_bindings b JOIN continuation_capsules c USING(capsule_id)
          WHERE b.child_task_id=? AND b.child_run_id=? AND b.child_generation_id=?
            AND b.parent_task_id=c.parent_task_id
          """)){
            statement.setString(1,childTaskId);statement.setString(2,childRunId);
            statement.setString(3,childGenerationId);
            try(var row=statement.executeQuery()){
                if(!row.next())throw new ContextFailure("CONTEXT_SOURCE_INTEGRITY");
                ParentBinding binding=new ParentBinding(row.getString(1),row.getString(2));
                if(row.next())throw new ContextFailure("CONTEXT_SOURCE_INTEGRITY");
                return binding;
            }
        }
    }

    private List<ParentHistoryProjection> parentHistoryProjections(Generation generation)
            throws SQLException{
        List<StoredSegment> stored=segments(generation.contextId());
        Map<Integer,List<StoredReference>> byOrdinal=references(generation.contextId()).stream()
                .collect(java.util.stream.Collectors.groupingBy(StoredReference::ordinal,
                        LinkedHashMap::new,java.util.stream.Collectors.toList()));
        var projected=new ArrayList<ParentHistoryProjection>();
        var claimedSources=new LinkedHashSet<String>();
        var projectionKeys=new LinkedHashSet<String>();
        var flattenedMemo=new HashMap<String,List<StoredReference>>();
        for(StoredSegment segment:stored){
            if(!"COMPACTED_LEDGER".equals(segment.kind()))continue;
            List<com.paicli.context.CompactedLedgerCodec.DecodedEntry> safeEntries=
                    decodedLedger(storedSnapshotPayload(segment)).stream().filter(entry->
                            "COMPLETED_TOOL_PAIR".equals(entry.sourceKind())).toList();
            if(safeEntries.isEmpty())continue;
            String payload=com.paicli.context.CompactedLedgerCodec
                    .encodeChildSafeCompletedToolHistory(safeEntries);
            String hash=DeterministicContextCompiler.hash(payload);
            Set<String> sourceKeys=safeEntries.stream().map(entry->entry.sourceKind()+'|'
                    +entry.sourceId()+'|'+Objects.toString(entry.sourceRevision(),"")+'|'
                    +entry.sourceContentHash()).collect(java.util.stream.Collectors
                            .toCollection(LinkedHashSet::new));
            String key="TOOL_LEDGER|"+hash;
            if(!projectionKeys.add(key))continue;
            claimedSources.addAll(sourceKeys);
            projected.add(new ParentHistoryProjection("PARENT_COMPLETED_TOOL_HISTORY",key,
                    payload,hash,PrivacyClass.SENSITIVE_REDACTED,
                    DeterministicContextCompiler.MAX_SEGMENT_BYTES,List.of(
                    new ParentSegmentAnchor(generation.contextId(),segment.ordinal()))));
        }
        for(StoredSegment segment:stored){
            if(!"COMPLETED_TOOL_PAIR".equals(segment.kind()))continue;
            List<StoredReference> leaves=flattenedLedgerReferences(generation.contextId(),
                    segment.ordinal(),flattenedMemo);
            Set<String> sourceKeys=sourceKeys(leaves);
            if(!Collections.disjoint(claimedSources,sourceKeys))continue;
            String payload=storedSnapshotPayload(segment);
            String key="PAIR|"+String.join("|",sourceKeys)+"|"+segment.contentHash();
            if(!projectionKeys.add(key))continue;
            claimedSources.addAll(sourceKeys);
            projected.add(parentProjection(generation.contextId(),"PARENT_COMPLETED_TOOL_PAIR",
                    key,payload,segment,List.of(segment.ordinal())));
        }
        for(int index=stored.size()-2;index>=0;index--){
            StoredSegment action=stored.get(index),observation=stored.get(index+1);
            if(!"EVENT_ACTION".equals(action.kind())||!"EVENT_OBSERVATION".equals(observation.kind()))
                continue;
            ParentPair pair=parentCompletedPair(generation,action,observation,
                    byOrdinal.getOrDefault(action.ordinal(),List.of()),
                    byOrdinal.getOrDefault(observation.ordinal(),List.of()));
            if(pair==null||!Collections.disjoint(claimedSources,pair.sourceKeys()))continue;
            String key="PAIR|"+String.join("|",pair.sourceKeys())+'|'+pair.contentHash();
            if(!projectionKeys.add(key))break;
            claimedSources.addAll(pair.sourceKeys());
            projected.add(new ParentHistoryProjection("PARENT_COMPLETED_TOOL_PAIR",key,
                    pair.payload(),pair.contentHash(),PrivacyClass.SENSITIVE_REDACTED,
                    DeterministicContextCompiler.MAX_SEGMENT_BYTES,List.of(
                    new ParentSegmentAnchor(generation.contextId(),action.ordinal()),
                    new ParentSegmentAnchor(generation.contextId(),observation.ordinal()))));
            break;
        }
        return List.copyOf(projected);
    }

    private ParentHistoryProjection parentProjection(String contextId,String kind,String key,
            String payload,StoredSegment segment,List<Integer> ordinals){
        return new ParentHistoryProjection(kind,key,payload,segment.contentHash(),
                segment.privacyClass(),segment.bounds(),ordinals.stream().map(ordinal->
                new ParentSegmentAnchor(contextId,ordinal)).toList());
    }

    private static Set<String> sourceKeys(List<StoredReference> references){
        return references.stream().map(reference->reference.type()+'|'+reference.id()+'|'
                +Objects.toString(reference.revision(),"")+'|'+reference.hash())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static List<com.paicli.context.CompactedLedgerCodec.DecodedEntry> decodedLedger(
            String payload){
        try{return com.paicli.context.CompactedLedgerCodec.decode(payload);}
        catch(IllegalArgumentException malformed){throw new ContextFailure("COMPACTION_INTEGRITY");}
    }

    private ParentPair parentCompletedPair(Generation generation,StoredSegment action,
            StoredSegment observation,List<StoredReference> actionReferences,
            List<StoredReference> observationReferences)throws SQLException{
        StoredReference actionEvent=singleEventReference(actionReferences);
        StoredReference observationEvent=singleEventReference(observationReferences);
        if(actionEvent==null||observationEvent==null)return null;
        ParentEvent actionRow=parentEvent(generation.taskId(),actionEvent,"ACTION");
        ParentEvent observationRow=parentEvent(generation.taskId(),observationEvent,"OBSERVATION");
        if(actionRow.toolCallId()==null
                ||!Objects.equals(actionRow.toolCallId(),observationRow.toolCallId()))return null;
        try(var statement=connection.prepareStatement("""
          SELECT t.tool_name,t.arguments,t.result_output,o.occurrence_id,o.status,o.result_hash,
                 o.external_outcome,o.started_at,o.completed_at
          FROM tool_calls t JOIN side_effect_occurrences o
            ON o.task_id=t.task_id AND o.run_id=? AND o.tool_call_id=t.tool_call_id
          WHERE t.task_id=? AND t.tool_call_id=? AND t.result_success=1 AND o.status='COMPLETED'
          """)){
            statement.setString(1,generation.runId());statement.setString(2,generation.taskId());
            statement.setString(3,actionRow.toolCallId());
            try(var row=statement.executeQuery()){
                if(!row.next())return null;
                String tool=parentClean(row.getString(1));
                String arguments=parentCleanNullable(row.getString(2));
                String result=parentCleanNullable(row.getString(3));
                String occurrence=row.getString(4);
                String digest=DeterministicContextCompiler.hash(tool+'|'
                        +Objects.toString(arguments,"")+'|'+Objects.toString(result,"")+'|'
                        +Objects.toString(row.getString(6),""));
                String payload="kind=COMPLETED_TOOL_PAIR;tool_name="+escapeParentFact(tool)
                        +";arguments_summary="+escapeParentFact(parentSafeArgumentSummary(tool,arguments))
                        +";status=COMPLETED;result_excerpt="
                        +escapeParentFact(parentBoundedResultExcerpt(result))
                        +";occurrence_id="+escapeParentFact(occurrence)+";digest="+digest;
                payload=parentClean(payload);
                if(row.next())throw new ContextFailure("COMPACTION_INTEGRITY");
                var keys=new LinkedHashSet<String>();
                keys.add(sourceKey(actionEvent));keys.add(sourceKey(observationEvent));
                return new ParentPair(payload,DeterministicContextCompiler.hash(payload),
                        keys);
            }
        }
    }

    private StoredReference singleEventReference(List<StoredReference> references){
        List<StoredReference> events=references.stream()
                .filter(reference->"EVENT".equals(reference.type())).toList();
        return events.size()==1?events.getFirst():null;
    }

    private ParentEvent parentEvent(String taskId,StoredReference reference,String expectedType)
            throws SQLException{
        try(var statement=connection.prepareStatement("""
          SELECT task_id,iteration,type,tool_call_id,content FROM events WHERE id=?
          """)){
            statement.setLong(1,Long.parseLong(reference.id()));
            try(var row=statement.executeQuery()){
                if(!row.next()||!Objects.equals(taskId,row.getString(1))
                        ||row.getInt(2)!=Integer.parseInt(reference.revision())
                        ||!Objects.equals(expectedType,row.getString(3)))
                    throw new ContextFailure("COMPACTION_INTEGRITY");
                String content=parentClean(row.getString(5));
                if(!DeterministicContextCompiler.hash(content).equals(reference.hash()))
                    throw new ContextFailure("COMPACTION_INTEGRITY");
                ParentEvent event=new ParentEvent(row.getString(4));
                if(row.next())throw new ContextFailure("COMPACTION_INTEGRITY");
                return event;
            }
        }catch(NumberFormatException invalid){throw new ContextFailure("COMPACTION_INTEGRITY");}
    }

    private String parentSafeArgumentSummary(String tool,String arguments){
        if(arguments==null)return "{}";
        if(!Set.of("read_file","list_files","grep","git_diff").contains(tool))
            return "{digest="+DeterministicContextCompiler.hash(arguments)+"}";
        try{
            String rendered=parentClean(JSON.writeValueAsString(JSON.readTree(arguments)));
            return rendered.substring(0,Math.min(512,rendered.length()));
        }catch(Exception invalid){
            return "{digest="+DeterministicContextCompiler.hash(arguments)+"}";
        }
    }

    private String parentBoundedResultExcerpt(String value){
        if(value==null)return "";
        int marker=value.indexOf("[PAICLI_TOOL_FACTS] ");
        String rendered=parentClean(marker<0?value:value.substring(0,marker));
        return rendered.substring(0,Math.min(1024,rendered.length()));
    }

    private String parentClean(String value){
        return sanitizer.sanitizeAndTruncate(Objects.toString(value,""),
                DeterministicContextCompiler.MAX_SEGMENT_BYTES);
    }
    private String parentCleanNullable(String value){return value==null?null:parentClean(value);}
    private static String escapeParentFact(String value){
        StringBuilder escaped=new StringBuilder();
        for(char character:Objects.toString(value,"").toCharArray()){
            if(character==92||character==';'||character=='=')escaped.append((char)92);
            if(character==13){escaped.append((char)92).append('r');continue;}
            if(character==10){escaped.append((char)92).append('n');continue;}
            escaped.append(character);
        }
        return escaped.toString();
    }
    private static String sourceKey(StoredReference reference){
        return reference.type()+'|'+reference.id()+'|'
                +Objects.toString(reference.revision(),"")+'|'+reference.hash();
    }

    /** Candidate fingerprints identify their source closure, so candidates use
     * deterministic rebuild validation while BASE generations validate their
     * own closure. Context-segment ancestry is then checked recursively. */
    private boolean generationClosureValid(Generation generation,Set<String> visiting)throws SQLException{
        return generationClosureValid(generation,visiting,new HashSet<>());
    }

    private boolean generationClosureValid(Generation generation,Set<String> visiting,
            Set<String> validated)throws SQLException{
        if(validated.contains(generation.contextId()))return true;
        if(!visiting.add(generation.contextId()))return false;
        try{
            boolean own;
            if(generation.kind()==GenerationKind.COMPACTED){
                Generation source=find(generation.sourceContextId()).orElse(null);
                own=source!=null&&Objects.equals(generation.sourceFingerprint(),
                        source.sourceFingerprint())&&storedCandidateSnapshotValid(generation);
            }else own=storedSnapshotClosureValid(generation.contextId(),generation.sourceFingerprint());
            if(!own)return false;
            for(StoredReference reference:references(generation.contextId())){
                if(!isContextSegmentReference(reference.type()))continue;
                SegmentLocator locator=segmentLocator(reference);
                Generation source=find(locator.contextId()).orElse(null);
                if(source==null||!generationClosureValid(source,visiting,validated))return false;
            }
            validated.add(generation.contextId());
            return true;
        }finally{visiting.remove(generation.contextId());}
    }

    /** Validates immutable stored rows only. Historical authority is not
     * re-rendered from later mutable business state. */
    private boolean storedSnapshotClosureValid(String contextId,String expectedFingerprint)
            throws SQLException{
        List<StoredSegment> stored=segments(contextId);if(stored.isEmpty())return false;
        List<StoredReference> allRefs=references(contextId);
        Map<Integer,List<StoredReference>> byOrdinal=allRefs.stream()
                .collect(java.util.stream.Collectors.groupingBy(StoredReference::ordinal,
                        LinkedHashMap::new,java.util.stream.Collectors.toList()));
        if(byOrdinal.keySet().stream().anyMatch(ordinal->ordinal<0||ordinal>=stored.size()))return false;
        var closure=new ArrayList<IntegritySegment>();
        for(int i=0;i<stored.size();i++){
            StoredSegment segment=stored.get(i);if(segment.ordinal()!=i)return false;
            if(segment.contentHash()==null||!segment.contentHash().matches("[0-9a-f]{64}"))return false;
            if(segment.payloadMode()==PayloadMode.INLINE_SANITIZED){
                try{storedSnapshotPayload(segment);}catch(ContextFailure corrupt){return false;}
            }else if(segment.sanitizedPayload()!=null)return false;
            List<StoredReference> refs=byOrdinal.getOrDefault(i,List.of());if(refs.isEmpty())return false;
            for(int order=0;order<refs.size();order++){
                StoredReference reference=refs.get(order);
                if(reference.order()!=order||reference.type()==null||reference.type().isBlank()
                        ||reference.id()==null||reference.id().isBlank()
                        ||reference.hash()==null||!reference.hash().matches("[0-9a-f]{64}")
                        ||reference.type().startsWith("MESSAGE_"))return false;
            }
            List<SourceReference> converted=refs.stream().map(reference->new SourceReference(
                    reference.type(),reference.id(),reference.revision(),reference.hash(),
                    reference.order())).toList();
            closure.add(new IntegritySegment(segment,converted));
        }
        return expectedFingerprint==null||DeterministicContextCompiler
                .sourceClosureFingerprint(closure).equals(expectedFingerprint);
    }

    private boolean storedCandidateSnapshotValid(Generation candidate)throws SQLException{
        if(candidate.sourceContextId()==null
                ||!storedSnapshotClosureValid(candidate.contextId(),null))return false;
        List<StoredSegment> actual=segments(candidate.contextId());
        if(!Objects.equals(DeterministicContextCompiler.segmentSetHash(actual),
                candidate.compiledHash()))return false;
        List<StoredSegment> source=segments(candidate.sourceContextId());if(source.isEmpty())return false;
        Map<Integer,StoredSegment> sourceSegments=source.stream().collect(
                java.util.stream.Collectors.toMap(StoredSegment::ordinal,value->value));
        Map<Integer,List<StoredReference>> sourceRefs=references(candidate.sourceContextId()).stream()
                .collect(java.util.stream.Collectors.groupingBy(StoredReference::ordinal,
                        LinkedHashMap::new,java.util.stream.Collectors.toList()));
        Map<Integer,List<StoredReference>> candidateRefs=references(candidate.contextId()).stream()
                .collect(java.util.stream.Collectors.groupingBy(StoredReference::ordinal,
                        LinkedHashMap::new,java.util.stream.Collectors.toList()));
        Set<Integer> preserved=new LinkedHashSet<>();
        var newLedgers=new ArrayList<StoredSegment>();
        for(StoredSegment segment:actual){
            List<StoredReference> refs=candidateRefs.getOrDefault(segment.ordinal(),List.of());
            StoredReference first=refs.isEmpty()?null:refs.getFirst();
            if(first!=null&&"SOURCE_SEGMENT".equals(first.type())){
                SegmentLocator locator=segmentLocator(first);
                StoredSegment original=sourceSegments.get(locator.ordinal());
                if(candidate.sourceContextId().equals(locator.contextId())&&original!=null
                        &&sameStoredSegmentIgnoringOrdinal(segment,original)
                        &&refs.equals(copiedReferences(candidate.sourceContextId(),segment.ordinal(),
                                original,sourceRefs.getOrDefault(original.ordinal(),List.of())))){
                    preserved.add(original.ordinal());continue;
                }
            }
            if(!"COMPACTED_LEDGER".equals(segment.kind()))return false;
            newLedgers.add(segment);
        }
        if(newLedgers.isEmpty())return false;
        Set<Integer> projected=new LinkedHashSet<>();
        for(StoredSegment ledger:newLedgers){
            List<StoredReference> expected=new ArrayList<>();
            for(LedgerSource entry:ledgerSources(ledger.sanitizedPayload())){
                List<Integer> matches=source.stream().filter(original->
                                ledgerSourceMatches(entry,original,
                                        sourceRefs.getOrDefault(original.ordinal(),List.of())))
                        .map(StoredSegment::ordinal).toList();
                if(matches.size()!=1)return false;
                int sourceOrdinal=matches.getFirst();
                if(preserved.contains(sourceOrdinal)||!projected.add(sourceOrdinal))return false;
                StoredSegment original=sourceSegments.get(sourceOrdinal);
                expected.add(sourceAnchor(candidate.sourceContextId(),ledger.ordinal(),original,
                        expected.size()));
                for(StoredReference reference:sourceRefs.getOrDefault(sourceOrdinal,List.of()))
                    expected.add(copyReference(ledger.ordinal(),reference,expected.size()));
            }
            List<StoredReference> actualRefs=candidateRefs.getOrDefault(ledger.ordinal(),List.of());
            for(int index=expected.size();index<actualRefs.size();index++){
                StoredReference reference=actualRefs.get(index);
                if(!"SOURCE_SEGMENT".equals(reference.type()))return false;
                SegmentLocator locator=segmentLocator(reference);
                StoredSegment original=sourceSegments.get(locator.ordinal());
                if(!candidate.sourceContextId().equals(locator.contextId())||original==null
                        ||!Set.of("EVENT_ACTION","EVENT_OBSERVATION").contains(original.kind())
                        ||preserved.contains(original.ordinal())||!projected.add(original.ordinal())
                        ||!reference.equals(sourceAnchor(candidate.sourceContextId(),ledger.ordinal(),
                                original,expected.size())))return false;
                expected.add(reference);
            }
            if(!actualRefs.equals(expected))return false;
        }
        if(source.size()!=preserved.size()+projected.size())return false;
        return Collections.disjoint(preserved,projected);
    }

    private static List<StoredReference> copiedReferences(String sourceContext,int candidateOrdinal,
            StoredSegment source,List<StoredReference> refs){
        var expected=new ArrayList<StoredReference>();
        expected.add(sourceAnchor(sourceContext,candidateOrdinal,source,0));
        for(StoredReference reference:refs)expected.add(copyReference(candidateOrdinal,reference,
                expected.size()));
        return List.copyOf(expected);
    }

    private static StoredReference sourceAnchor(String sourceContext,int candidateOrdinal,
            StoredSegment source,int order){
        return new StoredReference(candidateOrdinal,"SOURCE_SEGMENT",
                sourceContext+":"+source.ordinal(),null,source.contentHash(),order);
    }

    private static StoredReference copyReference(int candidateOrdinal,StoredReference source,int order){
        return new StoredReference(candidateOrdinal,source.type(),source.id(),source.revision(),
                source.hash(),order);
    }

    private static List<LedgerSource> ledgerSources(String payload){
        return decodedLedger(payload).stream().map(entry->new LedgerSource(entry.sourceKind(),
                entry.sourceId(),entry.sourceRevision(),entry.sourceContentHash())).toList();
    }

    private static boolean ledgerSourceMatches(LedgerSource entry,StoredSegment segment,
            List<StoredReference> references){
        StoredReference primary=references.stream().filter(reference->
                !isContextSegmentReference(reference.type())).findFirst().orElse(null);
        return primary!=null&&Objects.equals(entry.kind(),segment.kind())
                &&Objects.equals(entry.id(),primary.id())
                &&Objects.equals(entry.revision(),primary.revision())
                &&Objects.equals(entry.contentHash(),segment.contentHash());
    }

    private static boolean sameStoredSegmentIgnoringOrdinal(StoredSegment left,StoredSegment right){
        return left.sourceClass()==right.sourceClass()&&Objects.equals(left.kind(),right.kind())
                &&left.scope()==right.scope()&&left.compactability()==right.compactability()
                &&left.payloadMode()==right.payloadMode()
                &&Objects.equals(left.sanitizedPayload(),right.sanitizedPayload())
                &&Objects.equals(left.contentHash(),right.contentHash())
                &&left.estimatedTokens()==right.estimatedTokens()
                &&left.privacyClass()==right.privacyClass()&&left.bounds()==right.bounds();
    }

    /** Resolve through BASE-over-COMPACTED chains to the leaf durable facts used
     * by source suppression. Every traversed segment was already closure-checked. */
    private List<StoredReference> flattenedLedgerReferences(String contextId,int ordinal)throws SQLException{
        return flattenedLedgerReferences(contextId,ordinal,new HashMap<>());
    }

    private List<StoredReference> flattenedLedgerReferences(String contextId,int ordinal,
            Map<String,List<StoredReference>> memo)throws SQLException{
        var leaves=new LinkedHashMap<String,StoredReference>();
        collectLedgerReferences(contextId,ordinal,new LinkedHashSet<>(),leaves,memo);
        return List.copyOf(leaves.values());
    }

    private void collectLedgerReferences(String contextId,int ordinal,Set<String> visiting,
            Map<String,StoredReference> leaves,Map<String,List<StoredReference>> memo)throws SQLException{
        String location=contextId+":"+ordinal;
        List<StoredReference> cached=memo.get(location);
        if(cached!=null){mergeLeaves(leaves,cached);return;}
        if(!visiting.add(location))throw new ContextFailure("COMPACTION_INTEGRITY");
        var resolved=new LinkedHashMap<String,StoredReference>();
        try{
            List<StoredReference> refs=references(contextId).stream()
                    .filter(value->value.ordinal()==ordinal).toList();
            List<StoredReference> ancestry=refs.stream().filter(reference->
                    isContextSegmentReference(reference.type())).toList();
            if(ancestry.isEmpty()){
                for(StoredReference reference:refs){
                    String key=reference.type()+'|'+reference.id()+'|'
                            +Objects.toString(reference.revision(),"")+'|'+reference.hash();
                    resolved.putIfAbsent(key,reference);
                }
            }else for(StoredReference reference:ancestry){
                    SegmentLocator locator=segmentLocator(reference);
                    StoredSegment source=segments(locator.contextId()).stream()
                            .filter(value->value.ordinal()==locator.ordinal()).findFirst()
                            .orElseThrow(()->new ContextFailure("COMPACTION_INTEGRITY"));
                    if(!Objects.equals(source.contentHash(),reference.hash()))
                        throw new ContextFailure("COMPACTION_INTEGRITY");
                    collectLedgerReferences(locator.contextId(),locator.ordinal(),visiting,resolved,memo);
            }
        }finally{visiting.remove(location);}
        List<StoredReference> complete=List.copyOf(resolved.values());
        memo.put(location,complete);
        mergeLeaves(leaves,complete);
    }

    private static void mergeLeaves(Map<String,StoredReference> leaves,
            List<StoredReference> references){
        for(StoredReference reference:references){
            String key=reference.type()+'|'+reference.id()+'|'
                    +Objects.toString(reference.revision(),"")+'|'+reference.hash();
            leaves.putIfAbsent(key,reference);
        }
    }

    private static boolean isContextSegmentReference(String type){
        return Set.of("CONTEXT_SEGMENT","SOURCE_SEGMENT","PARENT_CONTEXT_SEGMENT").contains(type);
    }

    private static SegmentLocator segmentLocator(StoredReference reference){
        return segmentLocator(reference.id());
    }

    private static SegmentLocator segmentLocator(SourceReference reference){
        return segmentLocator(reference.id());
    }

    private static SegmentLocator segmentLocator(String id){
        int split=id.lastIndexOf(':');
        if(split<1)throw new ContextFailure("COMPACTION_INTEGRITY");
        try{return new SegmentLocator(id.substring(0,split),
                Integer.parseInt(id.substring(split+1)));}
        catch(NumberFormatException invalid){throw new ContextFailure("COMPACTION_INTEGRITY");}
    }

    private Optional<Generation> activeCandidate(String sourceId,String fingerprint,
            String compiler,String policy)throws SQLException{
        try(var statement=connection.prepareStatement("""
          SELECT context_generation_id FROM context_generations
          WHERE source_context_generation_id=? AND source_fingerprint=?
            AND compiler_version=? AND policy_version=?
            AND lifecycle IN ('PREPARING','PREPARED') LIMIT 1
          """)){
            statement.setString(1,sourceId);statement.setString(2,fingerprint);
            statement.setString(3,compiler);statement.setString(4,policy);
            try(var row=statement.executeQuery()){
                return row.next()?find(row.getString(1)):Optional.empty();}
        }
    }

    private boolean preparingExpired(String contextId)throws SQLException{
        try(var statement=connection.prepareStatement(
                "SELECT created_at<? FROM context_generations WHERE context_generation_id=?")){
            statement.setString(1,Instant.now().minusSeconds(300).toString());
            statement.setString(2,contextId);try(var row=statement.executeQuery()){
                return row.next()&&row.getInt(1)==1;}
        }
    }

    public Generation createOrReuseCandidate(String sourceId,String compilerVersion,
            String policyVersion,ExecutionAuthoritySnapshot authority) throws SQLException {
        return createOrReuseCandidate(sourceId,compilerVersion,policyVersion,
                pointer->safePoint(pointer,authority));
    }

    public Generation createOrReuseAutoCandidate(Pointer expected,String compilerVersion,
            String policyVersion,ExecutionAuthoritySnapshot authority)throws SQLException{
        Pointer current=pointer(expected.taskId(),expected.runId(),expected.operation()).orElseThrow();
        if(!current.equals(expected))throw new ContextFailure("COMPACTION_STALE_SOURCE");
        return createOrReuseCandidate(expected.contextGenerationId(),compilerVersion,policyVersion,
                pointer->autoSafePoint(pointer,authority));
    }

    public Generation createOrReuseMaintenanceCandidate(MaintenanceAuthority authority,
            MaintenanceTarget target,String compilerVersion,String policyVersion)throws SQLException{
        return createOrReuseCandidate(target.sourceContextGenerationId(),compilerVersion,policyVersion,
                pointer->maintenanceDecision(authority,pointer));
    }

    private Generation createOrReuseCandidate(String sourceId,String compilerVersion,
            String policyVersion,SafePointCheck gate) throws SQLException {
        Generation source=find(sourceId).orElseThrow(()->new ContextFailure("CONTEXT_NOT_FOUND"));
        if(source.lifecycle()!=Lifecycle.READY)throw new ContextFailure("COMPACTION_STALE_SOURCE");
        Pointer sourcePointer=pointer(source.taskId(),source.runId(),source.operation()).orElseThrow();
        var initialSafe=gate.evaluate(sourcePointer);
        if(!initialSafe.safe())throw new ContextFailure(initialSafe.code());
        try(var q=connection.prepareStatement("""
            SELECT context_generation_id FROM context_generations
            WHERE source_context_generation_id=? AND source_fingerprint=? AND compiler_version=?
              AND policy_version=? AND lifecycle IN ('PREPARING','PREPARED') LIMIT 1
            """)){q.setString(1,sourceId);q.setString(2,source.sourceFingerprint());q.setString(3,compilerVersion);
            q.setString(4,policyVersion);try(var r=q.executeQuery()){if(r.next()){
                Generation existing=find(r.getString(1)).orElseThrow();
                if(existing.lifecycle()==Lifecycle.PREPARED)return existing;
                if(!preparingExpired(existing.contextId()))return existing;
                synchronized(connection){transaction(()->{fail(existing.contextId(),
                        Lifecycle.PREPARING,"COMPACTION_INTERRUPTED");return null;});}
            }}}
        List<StoredSegment> sourceSegments=segments(sourceId);
        List<StoredSegment> compactable=sourceSegments.stream()
                .filter(s->s.compactability()==Compactability.COMPACTABLE).toList();
        if(compactable.isEmpty())throw new ContextFailure("COMPACTION_NOT_APPLICABLE");
        String candidate=CanonicalId.newId(EntityFamily.CONTEXT).value();
        try{synchronized(connection){transaction(()->{
                Pointer active=pointer(source.taskId(),source.runId(),source.operation()).orElseThrow();
                var writeSafe=gate.evaluate(active);
                if(!writeSafe.safe())throw new ContextFailure(writeSafe.code());
                if(!active.contextGenerationId().equals(sourceId)
                        ||active.version()!=sourcePointer.version())
                    throw new ContextFailure("COMPACTION_STALE_SOURCE");
                copyCandidateHeader(candidate,source,compilerVersion,policyVersion);
                return null;
            });}
        }catch(SQLException conflict){
            Optional<Generation> winner=activeCandidate(sourceId,source.sourceFingerprint(),
                    compilerVersion,policyVersion);
            if(winner.isPresent())return winner.get();
            throw conflict;
        }
        List<Segment> prepared;
        String hash;
        Budget budget;
        try{
            prepared=buildCandidateSegments(sourceId,sourceSegments,compactable);
            hash=com.paicli.context.DeterministicContextCompiler.segmentSetHash(prepared);
            budget=candidateBudget(sourceId,prepared);
            if(budget.decision()!=Budget.Decision.READY)
                throw new ContextFailure(budget.decision().name());
        }catch(SQLException|RuntimeException failure){
            synchronized(connection){transaction(()->{
                Generation row=find(candidate).orElse(null);
                if(row!=null&&row.lifecycle()==Lifecycle.PREPARING)
                    fail(candidate,Lifecycle.PREPARING,failure instanceof ContextFailure
                            ?failure.getMessage():"COMPACTION_FAILED");
                return null;
            });}
            throw failure;
        }
        try{
            synchronized(connection){return transaction(()->{
                Generation frozen=find(candidate).orElseThrow();
                Pointer active=pointer(source.taskId(),source.runId(),source.operation()).orElseThrow();
                Generation current=find(sourceId).orElseThrow();
                var preparedSafe=gate.evaluate(active);
                if(!preparedSafe.safe())throw new ContextFailure(preparedSafe.code());
                if(frozen.lifecycle()!=Lifecycle.PREPARING||current.lifecycle()!=Lifecycle.READY
                        ||!active.contextGenerationId().equals(sourceId)
                        ||!Objects.equals(frozen.sourcePointerVersion(),active.version())
                        ||!Objects.equals(frozen.sourceFingerprint(),current.sourceFingerprint()))
                    throw new ContextFailure("COMPACTION_STALE_SOURCE");
                insertSegments(candidate,prepared);
                transitionPrepared(candidate,Lifecycle.PREPARING,Lifecycle.PREPARED,hash,budget,
                        "provider-free structured ledger; policy="
                                +com.paicli.context.DeterministicContextCompiler.POLICY_VERSION
                                +"; source_segments="+compactable.size());
                return find(candidate).orElseThrow();
            });}
        }catch(SQLException|RuntimeException failure){
            synchronized(connection){transaction(()->{
                Generation row=find(candidate).orElse(null);
                if(row!=null&&row.lifecycle()==Lifecycle.PREPARING)
                    fail(candidate,Lifecycle.PREPARING,failure instanceof ContextFailure
                            ?failure.getMessage():"COMPACTION_FAILED");
                return null;
            });}
            throw failure;
        }
    }

    public Generation createOrReuseCandidate(CompactTargetIdentity target,String compilerVersion,
            String policyVersion,ExecutionAuthoritySnapshot authority)throws SQLException{
        Objects.requireNonNull(target);
        CompactTargetResolution current=resolveTarget(target.taskId(),target.runId(),target.operation(),
                target.sessionId(),target.relation());
        if(current.status()!=ResolutionStatus.PRESENT||!sameTarget(target,current.identity()))
            throw new ContextFailure("COMPACTION_STALE_SOURCE");
        return createOrReuseCandidate(target.sourceContextGenerationId(),compilerVersion,policyVersion,
                authority);
    }

    public Published applyCandidate(String candidateId,ExecutionAuthoritySnapshot authority)
            throws SQLException{
        return applyCandidate(candidateId,pointer->safePoint(pointer,authority),true);
    }

    public Published applyAutoCandidate(String candidateId,Pointer expected,
            ExecutionAuthoritySnapshot authority)throws SQLException{
        Pointer current=pointer(expected.taskId(),expected.runId(),expected.operation()).orElseThrow();
        if(!current.equals(expected))throw new ContextFailure("COMPACTION_STALE_SOURCE");
        return applyCandidate(candidateId,pointer->autoSafePoint(pointer,authority),true);
    }

    public Published applyMaintenanceCandidate(String candidateId,MaintenanceAuthority authority)
            throws SQLException{
        return applyCandidate(candidateId,pointer->maintenanceDecision(authority,pointer),false);
    }

    private Published applyCandidate(String candidateId,SafePointCheck gate,
            boolean requireCurrentExecutionAuthority)
            throws SQLException{
        String[] failureCode=new String[1];
        synchronized(connection){Published result=immediateTransaction(()->{
            Generation candidate=find(candidateId).orElseThrow(()->new ContextFailure("CONTEXT_NOT_FOUND"));
            if(candidate.lifecycle()==Lifecycle.READY){
                Pointer p=pointer(candidate.taskId(),candidate.runId(),candidate.operation()).orElseThrow();
                var repeatedSafe=gate.evaluate(p);
                if(!repeatedSafe.safe())throw new ContextFailure(repeatedSafe.code());
                return new Published(candidateId,Lifecycle.READY,p.version(),readBudget(candidateId));
            }
            if(candidate.lifecycle()!=Lifecycle.PREPARED)throw new ContextFailure("COMPACTION_NOT_PREPARED");
            Pointer pointer=pointer(candidate.taskId(),candidate.runId(),candidate.operation()).orElseThrow();
            var applySafe=gate.evaluate(pointer);
            if(!applySafe.safe())throw new ContextFailure(applySafe.code());
            Generation source=find(candidate.sourceContextId()).orElseThrow();
            if(source.lifecycle()!=Lifecycle.READY||!pointer.contextGenerationId().equals(source.contextId())
                    ||!Objects.equals(candidate.sourcePointerVersion(),pointer.version())
                    ||!Objects.equals(candidate.sourceFingerprint(),source.sourceFingerprint())
                    ||requireCurrentExecutionAuthority&&!storedAuthorityCurrent(candidateId)){
                fail(candidateId,Lifecycle.PREPARED,"COMPACTION_STALE_SOURCE");
                failureCode[0]="COMPACTION_STALE_SOURCE";
                return null;
            }
            boolean candidateValid;
            try{candidateValid=requireCurrentExecutionAuthority
                    ?candidateVersionCurrent(candidate)
                        &&storedSourceClosureValid(source.contextId())&&candidateContentValid(candidate)
                    :immutableMaintenanceCandidateValid(candidate);}
            catch(SQLException|RuntimeException corrupt){candidateValid=false;}
            if(!candidateValid){
                fail(candidateId,Lifecycle.PREPARED,"COMPACTION_INTEGRITY");
                failureCode[0]="COMPACTION_INTEGRITY";
                return null;
            }
            Budget budget=readBudget(candidateId);
            if(budget.decision()!=Budget.Decision.READY)throw new ContextFailure(budget.decision().name());
            transitionOnly(candidateId,Lifecycle.PREPARED,Lifecycle.READY);
            casPointer(candidate.taskId(),candidate.runId(),candidate.operation(),pointer,candidateId);
            supersede(source.contextId());
            return new Published(candidateId,Lifecycle.READY,pointer.version()+1,budget);
        });if(result==null)throw new ContextFailure(failureCode[0]);return result;}
    }

    public Published applyCandidate(String candidateId,CompactTargetIdentity target,
            ExecutionAuthoritySnapshot authority)throws SQLException{
        Objects.requireNonNull(target);
        CompactTargetResolution current=resolveCandidateTarget(candidateId,target.taskId(),target.runId(),
                target.sessionId(),target.relation());
        if(current.status()!=ResolutionStatus.PRESENT||!sameTarget(target,current.identity()))
            throw new ContextFailure(current.status()==ResolutionStatus.CROSS_RUN_INVALID
                    ?"CROSS_RUN_INVALID":"COMPACTION_STALE_SOURCE");
        return applyCandidate(candidateId,authority);
    }

    private static boolean sameTarget(CompactTargetIdentity left,CompactTargetIdentity right){
        return right!=null&&Objects.equals(left.workspaceId(),right.workspaceId())
                &&Objects.equals(left.sessionId(),right.sessionId())
                &&Objects.equals(left.threadId(),right.threadId())
                &&Objects.equals(left.taskId(),right.taskId())
                &&Objects.equals(left.runId(),right.runId())&&left.operation()==right.operation()
                &&Objects.equals(left.sourceContextGenerationId(),right.sourceContextGenerationId())
                &&left.sourcePointerVersion()==right.sourcePointerVersion()
                &&Objects.equals(left.sourceAuthorityGenerationId(),right.sourceAuthorityGenerationId())
                &&Objects.equals(left.provider(),right.provider())&&Objects.equals(left.model(),right.model())
                &&Objects.equals(left.capabilityProfile(),right.capabilityProfile())
                &&Objects.equals(left.snapshotToken(),right.snapshotToken())&&left.relation()==right.relation();
    }

    /** Capture one exact terminal shell target. No latest-Task fallback is permitted. */
    public MaintenanceAuthority captureMaintenanceAuthority(String sessionId,String threadId,
            String taskId,OperationKind operation)throws SQLException{
        if(sessionId==null||threadId==null||taskId==null)
            throw new ContextFailure("COMPACTION_NOT_APPLICABLE");
        List<Pointer> candidates=new ArrayList<>();
        try(var statement=connection.prepareStatement("""
          SELECT p.task_id,p.run_id,p.operation_kind,p.active_context_generation_id,p.pointer_version
          FROM context_generation_pointers p
          JOIN task_identities t ON t.task_id=p.task_id AND t.thread_id=? AND t.task_id=?
          JOIN durable_runs r ON r.task_id=p.task_id AND r.run_id=p.run_id AND r.thread_id=t.thread_id
          JOIN context_generations g ON g.context_generation_id=p.active_context_generation_id
          WHERE t.status='COMPLETED' AND r.terminal_status='COMPLETED' AND g.lifecycle='READY'
            AND EXISTS(SELECT 1 FROM context_segments s WHERE s.context_generation_id=g.context_generation_id
                       AND s.compactability='COMPACTABLE')
          """+(operation==null?" ORDER BY p.operation_kind":" AND p.operation_kind=? ORDER BY p.operation_kind"))){
            statement.setString(1,threadId);statement.setString(2,taskId);
            if(operation!=null)statement.setString(3,operation.name());
            try(var row=statement.executeQuery()){while(row.next())candidates.add(new Pointer(
                    row.getString(1),row.getString(2),OperationKind.valueOf(row.getString(3)),
                    row.getString(4),row.getLong(5)));}
        }
        if(candidates.isEmpty())throw new ContextFailure("COMPACTION_NOT_APPLICABLE");
        String runId=candidates.getFirst().runId();
        if(candidates.stream().anyMatch(pointer->!runId.equals(pointer.runId())))
            throw new ContextFailure("COMPACT_TARGET_AMBIGUOUS");
        List<MaintenanceTarget> targets=candidates.stream().sorted(Comparator.comparing(
                        pointer->pointer.operation().name()))
                .map(pointer->new MaintenanceTarget(pointer.operation(),pointer.contextGenerationId(),
                        pointer.version())).toList();
        String token=maintenanceToken(sessionId,threadId,taskId,runId,targets);
        return new MaintenanceAuthority(sessionId,threadId,taskId,runId,targets,token);
    }

    private ContextSafePointAuthority.Decision maintenanceDecision(MaintenanceAuthority authority,
            Pointer pointer)throws SQLException{
        MaintenanceTarget target=authority.targets().stream().filter(value->value.operation()==pointer.operation())
                .findFirst().orElse(null);
        if(target==null||!pointer.taskId().equals(authority.taskId())||!pointer.runId().equals(authority.runId())
                ||!pointer.contextGenerationId().equals(target.sourceContextGenerationId())
                ||pointer.version()!=target.sourcePointerVersion()
                ||!maintenanceToken(authority.sessionId(),authority.threadId(),authority.taskId(),
                        authority.runId(),authority.targets())
                        .equals(authority.snapshotToken()))
            return ContextSafePointAuthority.Decision.reject("COMPACTION_STALE_SOURCE");
        try(var statement=connection.prepareStatement("""
          SELECT 1 FROM task_identities t
          JOIN durable_runs r ON r.task_id=t.task_id AND r.run_id=? AND r.thread_id=t.thread_id
          JOIN context_generation_pointers p ON p.task_id=t.task_id AND p.run_id=r.run_id
             AND p.operation_kind=? AND p.active_context_generation_id=? AND p.pointer_version=?
          WHERE t.task_id=? AND t.thread_id=? AND t.status='COMPLETED'
            AND r.terminal_status='COMPLETED'
            AND NOT EXISTS(SELECT 1 FROM durable_runs active WHERE active.task_id=t.task_id
                           AND active.terminal_status IS NULL)
          """)){
            statement.setString(1,authority.runId());statement.setString(2,pointer.operation().name());
            statement.setString(3,target.sourceContextGenerationId());statement.setLong(4,target.sourcePointerVersion());
            statement.setString(5,authority.taskId());statement.setString(6,authority.threadId());
            try(var row=statement.executeQuery()){return row.next()?ContextSafePointAuthority.Decision.allowed()
                    :ContextSafePointAuthority.Decision.reject("CONTEXT_MAINTENANCE_AUTHORITY_STALE");}
        }
    }

    public List<Published> compactMaintenanceBundle(MaintenanceAuthority authority,
            String compilerVersion,String policyVersion)throws SQLException{
        synchronized(connection){return immediateTransaction(()->{
            var candidates=new ArrayList<Generation>();
            for(MaintenanceTarget target:authority.targets()){
                Pointer pointer=pointer(authority.taskId(),authority.runId(),target.operation()).orElseThrow();
                var safe=maintenanceDecision(authority,pointer);if(!safe.safe())throw new ContextFailure(safe.code());
                if(!maintenanceCandidateEffective(target.sourceContextGenerationId()))continue;
                Generation candidate=createOrReuseMaintenanceCandidate(authority,target,compilerVersion,policyVersion);
                if(readBudget(candidate.contextId()).inputEstimate()
                        >=readBudget(target.sourceContextGenerationId()).inputEstimate())
                    throw new ContextFailure("COMPACTION_NO_EFFECTIVE_REDUCTION");
                candidates.add(candidate);
            }
            if(candidates.isEmpty())throw new ContextFailure("COMPACTION_NO_EFFECTIVE_REDUCTION");
            var results=new ArrayList<Published>();
            for(Generation candidate:candidates)results.add(applyMaintenanceCandidate(candidate.contextId(),authority));
            return List.copyOf(results);
        });}
    }

    private boolean maintenanceCandidateEffective(String sourceId)throws SQLException{
        List<StoredSegment> sourceSegments=segments(sourceId);
        List<StoredSegment> compactable=sourceSegments.stream()
                .filter(segment->segment.compactability()==Compactability.COMPACTABLE).toList();
        if(compactable.isEmpty())return false;
        try{
            List<Segment> prepared=buildCandidateSegments(sourceId,sourceSegments,compactable);
            return candidateBudget(sourceId,prepared).inputEstimate()<readBudget(sourceId).inputEstimate();
        }catch(ContextFailure failure){
            if("COMPACTION_NOT_APPLICABLE".equals(failure.getMessage())
                    ||"COMPACTION_NO_EFFECTIVE_REDUCTION".equals(failure.getMessage()))return false;
            throw failure;
        }
    }

    private static String maintenanceToken(String session,String thread,String task,String run,
            List<MaintenanceTarget> targets){
        StringBuilder material=new StringBuilder(session).append('|').append(thread).append('|')
                .append(task).append('|').append(run);
        targets.stream().sorted(Comparator.comparing(value->value.operation().name()))
                .forEach(value->material.append('|').append(value.operation()).append('|')
                        .append(value.sourceContextGenerationId()).append('|').append(value.sourcePointerVersion()));
        return com.paicli.context.DeterministicContextCompiler.hash(material.toString());
    }

    private boolean sessionRunCurrent(String taskId,String runId)throws SQLException{
        try(var statement=connection.prepareStatement(
                "SELECT owner_run_id FROM task_context_pointers WHERE task_id=?")){
            statement.setString(1,taskId);try(var row=statement.executeQuery()){
                return row.next()&&Objects.equals(runId,row.getString(1));
            }
        }
    }

    private boolean candidateContentValid(Generation candidate)throws SQLException{
        List<StoredSegment> stored=segments(candidate.contextId());
        if(stored.isEmpty())return false;
        if(candidate.sourceContextId()==null)return false;
        List<StoredSegment> sourceSegments=segments(candidate.sourceContextId());
        List<StoredSegment> compactable=sourceSegments.stream()
                .filter(segment->segment.compactability()==Compactability.COMPACTABLE).toList();
        List<Segment> rebuilt=buildCandidateSegments(candidate.sourceContextId(),sourceSegments,compactable);
        if(!matchesRebuiltCandidate(candidate.contextId(),stored,rebuilt))return false;
        for(int i=0;i<stored.size();i++)if(stored.get(i).ordinal()!=i)return false;
        Map<Integer,List<StoredReference>> byOrdinal=references(candidate.contextId()).stream()
                .collect(java.util.stream.Collectors.groupingBy(StoredReference::ordinal,
                        LinkedHashMap::new,java.util.stream.Collectors.toList()));
        for(StoredSegment segment:stored){
            List<StoredReference> refs=byOrdinal.getOrDefault(segment.ordinal(),List.of());
            if(refs.isEmpty())return false;
            String rendered=renderStoredSegment(candidate.contextId(),segment,refs);
            if(!Objects.equals(com.paicli.context.DeterministicContextCompiler.hash(rendered),
                    segment.contentHash()))return false;
            if(segment.bounds()<0||rendered.length()>segment.bounds()
                    ||!Objects.equals(rendered,sanitizer.sanitizeAndTruncate(rendered,segment.bounds())))
                return false;
            for(StoredReference ref:refs){
                if("CONTEXT_INLINE".equals(ref.type()))continue;
                String sourceRendered=renderReference(candidate.contextId(),ref);
                if(!Objects.equals(com.paicli.context.DeterministicContextCompiler.hash(sourceRendered),ref.hash()))
                    return false;
            }
        }
        String actual=com.paicli.context.DeterministicContextCompiler.segmentSetHash(stored);
        if(!Objects.equals(actual,candidate.compiledHash()))return false;
        try(var statement=connection.prepareStatement("""
          SELECT count(DISTINCT s.ordinal)
          FROM context_segments s JOIN context_source_references r
            ON r.context_generation_id=s.context_generation_id AND r.ordinal=s.ordinal
          WHERE s.context_generation_id=?
          """)){
            statement.setString(1,candidate.contextId());try(var row=statement.executeQuery()){
                if(!row.next()||row.getLong(1)!=stored.size())return false;
            }
        }
        Map<String,String> expected=new LinkedHashMap<>();
        for(StoredSegment source:segments(candidate.sourceContextId()))
            expected.put(candidate.sourceContextId()+":"+source.ordinal(),source.contentHash());
        Map<String,String> referenced=new LinkedHashMap<>();
        try(var statement=connection.prepareStatement("""
          SELECT source_id,source_hash FROM context_source_references
          WHERE context_generation_id=? AND source_type='SOURCE_SEGMENT'
          ORDER BY ordinal,source_order
          """)){
            statement.setString(1,candidate.contextId());try(var row=statement.executeQuery()){
                while(row.next())if(referenced.put(row.getString(1),row.getString(2))!=null)return false;
            }
        }
        return expected.equals(referenced);
    }

    /**
     * Terminal maintenance compacts the immutable Provider-admission snapshot. Later normal
     * step/attempt/completion transitions must not be re-rendered into that historical source.
     * Current terminal/no-active-run authority and pointer CAS are checked by the caller.
     */
    private boolean immutableMaintenanceCandidateValid(Generation candidate)throws SQLException{
        if(!candidateVersionCurrent(candidate)
                ||!generationClosureValid(candidate,new LinkedHashSet<>()))return false;
        List<StoredSegment> source=segments(candidate.sourceContextId());
        List<StoredSegment> compactable=source.stream()
                .filter(segment->segment.compactability()==Compactability.COMPACTABLE).toList();
        List<Segment> rebuilt=buildCandidateSegments(candidate.sourceContextId(),source,compactable);
        List<StoredSegment> stored=segments(candidate.contextId());
        if(!matchesRebuiltCandidate(candidate.contextId(),stored,rebuilt))return false;
        Budget expected=candidateBudget(candidate.sourceContextId(),rebuilt);
        return expected.equals(readBudget(candidate.contextId()));
    }

    private boolean matchesRebuiltCandidate(String candidateId,List<StoredSegment> stored,
            List<Segment> rebuilt)throws SQLException{
        if(stored.size()!=rebuilt.size())return false;
        for(int i=0;i<stored.size();i++){
            StoredSegment actual=stored.get(i);Segment expected=rebuilt.get(i);
            if(actual.ordinal()!=expected.ordinal()||actual.sourceClass()!=expected.sourceClass()
                    ||!Objects.equals(actual.kind(),expected.kind())||actual.scope()!=expected.scope()
                    ||actual.compactability()!=expected.compactability()
                    ||actual.payloadMode()!=expected.payloadMode()
                    ||!Objects.equals(actual.sanitizedPayload(),expected.sanitizedPayload())
                    ||!Objects.equals(actual.contentHash(),expected.contentHash())
                    ||actual.estimatedTokens()!=expected.estimatedTokens()
                    ||actual.privacyClass()!=expected.privacyClass()||actual.bounds()!=expected.bounds())
                return false;
        }
        List<StoredReference> actualRefs=references(candidateId);
        var expectedRefs=new ArrayList<StoredReference>();
        for(Segment segment:rebuilt)for(SourceReference reference:segment.references())
            expectedRefs.add(new StoredReference(segment.ordinal(),reference.type(),reference.id(),
                    reference.revision(),reference.hash(),reference.order()));
        return actualRefs.equals(expectedRefs);
    }

    private boolean candidateVersionCurrent(Generation candidate){
        return Objects.equals(candidate.compilerVersion(),
                        com.paicli.context.DeterministicContextCompiler.COMPILER_VERSION)
                &&Objects.equals(candidate.policyVersion(),
                        com.paicli.context.DeterministicContextCompiler.POLICY_VERSION)
                &&candidate.capabilityProfile()!=null&&!candidate.capabilityProfile().isBlank();
    }

    private boolean storedSourceClosureValid(String contextId)throws SQLException{
        Generation generation=find(contextId).orElse(null);if(generation==null)return false;
        List<StoredSegment> stored=segments(contextId);if(stored.isEmpty())return false;
        Map<Integer,List<StoredReference>> byOrdinal=references(contextId).stream()
                .collect(java.util.stream.Collectors.groupingBy(StoredReference::ordinal,
                        LinkedHashMap::new,java.util.stream.Collectors.toList()));
        var closure=new ArrayList<IntegritySegment>();
        for(int i=0;i<stored.size();i++){
            StoredSegment segment=stored.get(i);if(segment.ordinal()!=i)return false;
            List<StoredReference> refs=byOrdinal.getOrDefault(i,List.of());if(refs.isEmpty())return false;
            if(refs.stream().anyMatch(r->r.type().startsWith("MESSAGE_")))return false;
            String rendered=renderStoredSegment(contextId,segment,refs);
            if(rendered.length()>segment.bounds()||!rendered.equals(
                    sanitizer.sanitizeAndTruncate(rendered,segment.bounds()))
                    ||!com.paicli.context.DeterministicContextCompiler.hash(rendered)
                            .equals(segment.contentHash()))return false;
            for(StoredReference ref:refs){
                if("CONTEXT_INLINE".equals(ref.type()))continue;
                String sourceRendered=renderReference(contextId,ref);
                if(!com.paicli.context.DeterministicContextCompiler.hash(sourceRendered)
                        .equals(ref.hash()))return false;
            }
            List<SourceReference> converted=refs.stream().map(r->new SourceReference(r.type(),r.id(),
                    r.revision(),r.hash(),r.order())).toList();
            closure.add(new IntegritySegment(segment,converted));
        }
        return com.paicli.context.DeterministicContextCompiler.sourceClosureFingerprint(closure)
                .equals(generation.sourceFingerprint());
    }

    private String renderStoredSegment(String contextId,StoredSegment segment,
            List<StoredReference> refs)throws SQLException{
        if(segment.payloadMode()==PayloadMode.INLINE_SANITIZED){
            if(segment.sanitizedPayload()==null)throw new ContextFailure("COMPACTION_INTEGRITY");
            return segment.sanitizedPayload();
        }
        StoredReference direct=refs.stream().filter(r->!"SOURCE_SEGMENT".equals(r.type())).findFirst()
                .orElseGet(()->refs.stream().findFirst().orElse(null));
        if(direct==null)throw new ContextFailure("CONTEXT_SOURCE_INTEGRITY");
        String value=renderReference(contextId,direct);
        if(!com.paicli.context.DeterministicContextCompiler.hash(value).equals(direct.hash()))
            throw new ContextFailure("CONTEXT_STALE_SOURCE");
        return value;
    }

    private String renderReference(String ownerContextId,StoredReference ref)throws SQLException{
        Generation owner=find(ownerContextId).orElseThrow(()->new ContextFailure("CONTEXT_NOT_FOUND"));
        return switch(ref.type()){
            case "MANIFEST"->{if(!ref.id().equals(owner.manifestHash())
                        ||!Objects.equals(ref.revision(),ref.id()))throw new ContextFailure("CONTEXT_STALE_SOURCE");
                yield "Frozen Instruction Manifest: "+ref.id();}
            case "PLAN_REVISION"->{
                try(var s=connection.prepareStatement("SELECT task_id FROM plan_revisions WHERE plan_revision_id=?")){
                    s.setString(1,ref.id());try(var r=s.executeQuery()){if(!r.next())throw new ContextFailure("CONTEXT_STALE_SOURCE");
                        if(!owner.taskId().equals(r.getString(1)))throw new ContextFailure("CONTEXT_IDENTITY_MISMATCH");
                        var active=new PlanAuthorityStore(connection).readActive(CanonicalId.parse(r.getString(1)));
                        if(active.plan()==null||!active.plan().revision().planRevisionId().value().equals(ref.id()))
                            throw new ContextFailure("CONTEXT_STALE_SOURCE");
                        if(!Objects.equals(ref.revision(),Integer.toString(
                                active.plan().revision().revisionNumber())))
                            throw new ContextFailure("CONTEXT_STALE_SOURCE");
                        yield sanitizer.sanitizeAndTruncate("Current active Plan (authoritative):\n"
                                +com.paicli.runtime.PlanService.render(active.plan().revision()),65536);}}
            }
            case "STEERING_REVISION"->{
                if(!Objects.equals(ref.revision(),ref.id()))throw new ContextFailure("CONTEXT_STALE_SOURCE");
                try(var s=connection.prepareStatement("SELECT task_id,canonical_content FROM constraint_revisions WHERE constraint_revision_id=?")){
                    s.setString(1,ref.id());try(var r=s.executeQuery()){if(!r.next())throw new ContextFailure("CONTEXT_STALE_SOURCE");
                        if(!owner.taskId().equals(r.getString(1)))throw new ContextFailure("CONTEXT_IDENTITY_MISMATCH");
                        yield sanitizer.sanitizeAndTruncate("Current durable active Constraints (runtime authority):\n"
                                +r.getString(2),65536);}}
            }
            case "OWNERSHIP_LEASE"->{
                try(var s=connection.prepareStatement("SELECT owner_instance_id,owner_run_id,lease_token,fencing_token,cas_version,state,expires_at FROM task_ownership_leases WHERE task_id=?")){
                    s.setString(1,ref.id());try(var r=s.executeQuery()){if(!r.next())throw new ContextFailure("CONTEXT_STALE_SOURCE");
                        if(!owner.taskId().equals(ref.id())||!owner.runId().equals(r.getString(2)))
                            throw new ContextFailure("CONTEXT_IDENTITY_MISMATCH");
                        if(!Objects.equals(ref.revision(),Long.toString(r.getLong(4))))
                            throw new ContextFailure("CONTEXT_STALE_SOURCE");
                        // This reference is the immutable authority fact captured at admission.
                        // Live ownership/expiry is enforced separately at execution gates.
                        yield ref.id()+"|"+r.getString(2)+"|"+r.getLong(4)+"|ACTIVE";}}
            }
            case "EVENT"->{
                try(var s=connection.prepareStatement("SELECT task_id,content,iteration FROM events WHERE id=?")){
                    s.setLong(1,Long.parseLong(ref.id()));try(var r=s.executeQuery()){if(!r.next())throw new ContextFailure("CONTEXT_STALE_SOURCE");
                        if(!owner.taskId().equals(r.getString(1)))throw new ContextFailure("CONTEXT_IDENTITY_MISMATCH");
                        if(!Objects.equals(ref.revision(),Integer.toString(r.getInt(3))))
                            throw new ContextFailure("CONTEXT_STALE_SOURCE");
                        yield sanitizer.sanitizeAndTruncate(Objects.toString(r.getString(2),""),65536);}}
            }
            case "TOOL_RESULT"->{
                try(var s=connection.prepareStatement("SELECT task_id,tool_name,result_success,result_output IS NOT NULL,result_error IS NOT NULL,created_at FROM tool_calls WHERE id=?")){
                    s.setLong(1,Long.parseLong(ref.id()));try(var r=s.executeQuery()){if(!r.next())throw new ContextFailure("CONTEXT_STALE_SOURCE");
                        if(!owner.taskId().equals(r.getString(1)))throw new ContextFailure("CONTEXT_IDENTITY_MISMATCH");
                        if(!Objects.equals(ref.revision(),r.getString(6)))
                            throw new ContextFailure("CONTEXT_STALE_SOURCE");
                        yield "Tool result ledger: id="+ref.id()+" tool="+sanitizer.sanitizeAndTruncate(r.getString(2),65536)
                                +" success="+r.getInt(3)+" output_present="+r.getInt(4)
                                +" error_present="+r.getInt(5)+" artifact=durable-tool-call:"+ref.id();}}
            }
            case "VERIFICATION"->{
                try(var s=connection.prepareStatement("SELECT task_id,run_id,repair_attempt,status,exit_code,completed_at FROM verification_runs WHERE verification_id=?")){
                    s.setString(1,ref.id());try(var r=s.executeQuery()){if(!r.next())throw new ContextFailure("CONTEXT_STALE_SOURCE");
                        if(!owner.taskId().equals(r.getString(1))||!owner.runId().equals(r.getString(2)))
                            throw new ContextFailure("CONTEXT_IDENTITY_MISMATCH");
                        if(!Objects.equals(ref.revision(),Objects.toString(r.getString(6),r.getString(4))))
                            throw new ContextFailure("CONTEXT_STALE_SOURCE");
                        yield "Verification: id="+ref.id()+" repair="+r.getInt(3)+" status="+r.getString(4)
                                +" exit="+Objects.toString(r.getObject(5),"unknown");}}
            }
            case "REPAIR"->{
                try(var s=connection.prepareStatement("SELECT task_id,run_id,attempt_no,trigger_verification_id,status,failure_summary,completed_at FROM repair_attempts WHERE repair_id=?")){
                    s.setString(1,ref.id());try(var r=s.executeQuery()){if(!r.next())throw new ContextFailure("CONTEXT_STALE_SOURCE");
                        if(!owner.taskId().equals(r.getString(1))||!owner.runId().equals(r.getString(2)))
                            throw new ContextFailure("CONTEXT_IDENTITY_MISMATCH");
                        if(!Objects.equals(ref.revision(),Objects.toString(r.getString(7),r.getString(5))))
                            throw new ContextFailure("CONTEXT_STALE_SOURCE");
                        String status=r.getString(5);yield "Repair: id="+ref.id()+" attempt="+r.getInt(3)
                                +" verification="+r.getString(4)+" status="+status+" unresolved="
                                +"RUNNING".equals(status)+" result="+sanitizer.sanitizeAndTruncate(
                                        Objects.toString(r.getString(6),""),65536);}}
            }
            case "CONTINUATION_CAPSULE"->{
                try(var s=connection.prepareStatement("SELECT c.canonical_hash,b.child_task_id,b.child_run_id,b.child_generation_id FROM continuation_capsules c JOIN capsule_child_bindings b ON b.capsule_id=c.capsule_id WHERE c.capsule_id=?")){
                    s.setString(1,ref.id());try(var r=s.executeQuery()){if(!r.next())throw new ContextFailure("CONTEXT_STALE_SOURCE");
                        if(!owner.taskId().equals(r.getString(2))||!owner.runId().equals(r.getString(3)))
                            throw new ContextFailure("CONTEXT_IDENTITY_MISMATCH");
                        if(!Objects.equals(ref.revision(),r.getString(1)))
                            throw new ContextFailure("CONTEXT_STALE_SOURCE");
                        yield "Continuation Capsule: "+ref.id()+" hash="+r.getString(1);}}
            }
            case "PLAN_STEP","PLAN_ATTEMPT","PLAN_EVIDENCE","COMPLETION_GATE",
                    "TOOL_OCCURRENCE","TOOL_RESULT_AUTHORITY","ARTIFACT_AUTHORITY",
                    "VERIFICATION_RESULT","REPAIR_ITERATION"->{
                var adapter=new ContextSemanticSourceAdapter(connection);
                yield adapter.renderReference(new ContextSemanticSourceAdapter.Owner(owner.taskId(),
                        owner.runId(),owner.planRevision()),new SourceReference(ref.type(),ref.id(),
                                ref.revision(),ref.hash(),ref.order()));
            }
            case "CONTEXT_SEGMENT","SOURCE_SEGMENT"->{
                int split=ref.id().lastIndexOf(':');if(split<1)throw new ContextFailure("COMPACTION_INTEGRITY");
                String sourceContext=ref.id().substring(0,split);int ordinal=Integer.parseInt(ref.id().substring(split+1));
                Generation sourceGeneration=find(sourceContext).orElseThrow(()->new ContextFailure("COMPACTION_INTEGRITY"));
                if(!owner.taskId().equals(sourceGeneration.taskId())||!owner.runId().equals(sourceGeneration.runId()))
                    throw new ContextFailure("CONTEXT_IDENTITY_MISMATCH");
                StoredSegment source=segments(sourceContext).stream().filter(s->s.ordinal()==ordinal).findFirst()
                        .orElseThrow(()->new ContextFailure("COMPACTION_INTEGRITY"));
                List<StoredReference> sourceRefs=references(sourceContext).stream()
                        .filter(r->r.ordinal()==ordinal).toList();
                yield renderStoredSegment(sourceContext,source,sourceRefs);
            }
            case "PARENT_CONTEXT_SEGMENT"->{
                int split=ref.id().lastIndexOf(':');if(split<1)throw new ContextFailure("COMPACTION_INTEGRITY");
                String sourceContext=ref.id().substring(0,split);int ordinal=Integer.parseInt(ref.id().substring(split+1));
                Generation sourceGeneration=find(sourceContext).orElseThrow(()->new ContextFailure("COMPACTION_INTEGRITY"));
                try(var statement=connection.prepareStatement("""
                  SELECT 1 FROM capsule_child_bindings b JOIN continuation_capsules c USING(capsule_id)
                  WHERE b.child_task_id=? AND b.child_run_id=? AND b.child_generation_id=?
                    AND b.parent_task_id=? AND c.parent_run_id=?
                  """)){
                    statement.setString(1,owner.taskId());statement.setString(2,owner.runId());
                    statement.setString(3,sourceAuthorityGenerationId(owner.contextId()));
                    statement.setString(4,sourceGeneration.taskId());statement.setString(5,sourceGeneration.runId());
                    try(var row=statement.executeQuery()){if(!row.next())
                        throw new ContextFailure("CONTEXT_IDENTITY_MISMATCH");}
                }
                OperationKind parentOperation;
                try{parentOperation=OperationKind.valueOf(ref.revision());}
                catch(IllegalArgumentException|NullPointerException invalid){
                    throw new ContextFailure("CONTEXT_SOURCE_INTEGRITY");
                }
                if((parentOperation!=OperationKind.CHAT
                        &&parentOperation!=OperationKind.TOOL_CONTINUATION)
                        ||sourceGeneration.operation()!=parentOperation)
                    throw new ContextFailure("CONTEXT_IDENTITY_MISMATCH");
                Pointer canonical=pointer(sourceGeneration.taskId(),sourceGeneration.runId(),
                        parentOperation).orElse(null);
                if(canonical==null||!Objects.equals(sourceContext,canonical.contextGenerationId()))
                    throw new ContextFailure("COMPACTION_STALE_SOURCE");
                yield renderParentProjectionAt(sourceGeneration,sourceContext,ordinal);
            }
            case "CONTEXT_INLINE"->throw new ContextFailure("CONTEXT_SOURCE_INTEGRITY");
            default->throw new ContextFailure("CONTEXT_SOURCE_INTEGRITY");
        };
    }

    private String renderParentProjectionAt(Generation parent,String contextId,int ordinal)
            throws SQLException{
        List<StoredSegment> stored=segments(contextId);
        StoredSegment source=stored.stream().filter(segment->segment.ordinal()==ordinal).findFirst()
                .orElseThrow(()->new ContextFailure("COMPACTION_INTEGRITY"));
        if("COMPACTED_LEDGER".equals(source.kind())){
            List<com.paicli.context.CompactedLedgerCodec.DecodedEntry> safe=decodedLedger(
                    storedSnapshotPayload(source)).stream().filter(entry->
                    "COMPLETED_TOOL_PAIR".equals(entry.sourceKind())).toList();
            if(safe.isEmpty())throw new ContextFailure("COMPACTION_INTEGRITY");
            return com.paicli.context.CompactedLedgerCodec
                    .encodeChildSafeCompletedToolHistory(safe);
        }
        if("COMPLETED_TOOL_PAIR".equals(source.kind()))return storedSnapshotPayload(source);
        int actionOrdinal,observationOrdinal;
        if("EVENT_ACTION".equals(source.kind())){
            actionOrdinal=ordinal;observationOrdinal=ordinal+1;
        }else if("EVENT_OBSERVATION".equals(source.kind())){
            actionOrdinal=ordinal-1;observationOrdinal=ordinal;
        }else throw new ContextFailure("COMPACTION_INTEGRITY");
        StoredSegment action=stored.stream().filter(segment->segment.ordinal()==actionOrdinal
                &&"EVENT_ACTION".equals(segment.kind())).findFirst()
                .orElseThrow(()->new ContextFailure("COMPACTION_INTEGRITY"));
        StoredSegment observation=stored.stream().filter(segment->segment.ordinal()==observationOrdinal
                &&"EVENT_OBSERVATION".equals(segment.kind())).findFirst()
                .orElseThrow(()->new ContextFailure("COMPACTION_INTEGRITY"));
        Map<Integer,List<StoredReference>> refs=references(contextId).stream()
                .collect(java.util.stream.Collectors.groupingBy(StoredReference::ordinal,
                        LinkedHashMap::new,java.util.stream.Collectors.toList()));
        ParentPair pair=parentCompletedPair(parent,action,observation,
                refs.getOrDefault(actionOrdinal,List.of()),
                refs.getOrDefault(observationOrdinal,List.of()));
        if(pair==null)throw new ContextFailure("COMPACTION_INTEGRITY");
        return pair.payload();
    }

    private String sourceAuthorityGenerationId(String contextId)throws SQLException{
        try(var statement=connection.prepareStatement("""
          SELECT source_authority_generation_id FROM context_generations
          WHERE context_generation_id=?
          """)){
            statement.setString(1,contextId);try(var row=statement.executeQuery()){
                if(!row.next())throw new ContextFailure("COMPACTION_INTEGRITY");
                String generationId=row.getString(1);
                if(generationId==null||generationId.isBlank()||row.next())
                    throw new ContextFailure("COMPACTION_INTEGRITY");
                return generationId;
            }
        }
    }

    private void insertGeneration(CompiledContext c,GenerationKind kind,String source,long sourcePointer,
            Lifecycle lifecycle)throws SQLException{
        try(var s=connection.prepareStatement("""
          INSERT INTO context_generations(context_generation_id,workspace_id,thread_id,task_id,run_id,
            source_authority_generation_id,generation_kind,operation_kind,provider,model,capability_profile,
            compiler_version,policy_version,manifest_hash,plan_revision,steering_revision,source_fingerprint,
            compiled_hash,input_estimate,tool_schema_estimate,output_reserve,safety_margin,context_limit,
            limit_provenance,estimate_provenance,source_context_generation_id,source_pointer_version,
            lifecycle,state_version,created_at,updated_at)
          VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NULL,NULL,NULL,NULL,NULL,NULL,?,?,?,? ,?,0,?,?)
          """)){
            int i=1;String now=Instant.now().toString();
            s.setString(i++,c.identity().contextGenerationId());s.setString(i++,c.identity().workspaceId());
            s.setString(i++,c.identity().threadId());s.setString(i++,c.identity().taskId());s.setString(i++,c.identity().runId());
            s.setString(i++,c.identity().authorityGenerationId());s.setString(i++,kind.name());s.setString(i++,c.operation().name());
            s.setString(i++,safe(c.provider(),64));s.setString(i++,safe(c.model(),128));s.setString(i++,safe(c.capability().name(),128));
            s.setString(i++,c.compilerVersion());s.setString(i++,c.policyVersion());s.setString(i++,c.identity().manifestHash());
            nullable(s,i++,c.identity().planRevision());nullable(s,i++,c.identity().steeringRevision());s.setString(i++,c.sourceFingerprint());
            s.setString(i++,c.budget().limitProvenance().name());s.setString(i++,c.budget().estimateProvenance().name());
            nullable(s,i++,source);if(source==null)s.setNull(i++,Types.INTEGER);else s.setLong(i++,sourcePointer);
            s.setString(i++,lifecycle.name());s.setString(i++,now);s.setString(i,now);s.executeUpdate();}
    }
    private void insertSegments(String contextId,List<Segment> segments)throws SQLException{
        for(Segment x:segments){
            try(var s=connection.prepareStatement("""
              INSERT INTO context_segments VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
              """)){int i=1;s.setString(i++,contextId);s.setInt(i++,x.ordinal());s.setString(i++,x.sourceClass().name());
                s.setString(i++,x.kind());s.setString(i++,x.scope().name());s.setString(i++,x.compactability().name());
                s.setString(i++,x.payloadMode().name());nullable(s,i++,x.sanitizedPayload());s.setString(i++,x.contentHash());
                s.setInt(i++,x.estimatedTokens());s.setString(i++,x.privacyClass().name());s.setInt(i,x.bounds());s.executeUpdate();}
            for(SourceReference ref:x.references())try(var s=connection.prepareStatement(
                    "INSERT INTO context_source_references VALUES(?,?,?,?,?,?,?)")){
                int i=1;s.setString(i++,contextId);s.setInt(i++,x.ordinal());s.setString(i++,ref.type());
                s.setString(i++,ref.id());nullable(s,i++,ref.revision());s.setString(i++,ref.hash());
                s.setInt(i,ref.order());s.executeUpdate();}
        }
    }
    private void transitionPrepared(String id,Lifecycle from,Lifecycle to,String hash,Budget b,String preview)throws SQLException{
        try(var s=connection.prepareStatement("""
          UPDATE context_generations SET lifecycle=?,state_version=state_version+1,compiled_hash=?,
            input_estimate=?,tool_schema_estimate=?,output_reserve=?,safety_margin=?,context_limit=?,
            limit_provenance=?,estimate_provenance=?,preview_summary=?,updated_at=?
          WHERE context_generation_id=? AND lifecycle=? AND state_version=0
          """)){int i=1;s.setString(i++,to.name());s.setString(i++,hash);s.setInt(i++,b.inputEstimate());
            s.setInt(i++,b.toolSchemaEstimate());s.setInt(i++,b.outputReserve());s.setInt(i++,b.safetyMargin());
            if(b.contextLimit()==null)s.setNull(i++,Types.INTEGER);else s.setInt(i++,b.contextLimit());
            s.setString(i++,b.limitProvenance().name());s.setString(i++,b.estimateProvenance().name());
            nullable(s,i++,preview);s.setString(i++,Instant.now().toString());s.setString(i++,id);s.setString(i,from.name());
            if(s.executeUpdate()!=1)throw new ContextFailure("CONTEXT_STATE_CONFLICT");}
    }
    private void copyCandidateHeader(String id,Generation source,String compiler,String policy)throws SQLException{
        try(var s=connection.prepareStatement("""
          INSERT INTO context_generations(context_generation_id,workspace_id,thread_id,task_id,run_id,
            source_authority_generation_id,generation_kind,operation_kind,provider,model,capability_profile,
            compiler_version,policy_version,manifest_hash,plan_revision,steering_revision,source_fingerprint,
            limit_provenance,estimate_provenance,source_context_generation_id,source_pointer_version,
            lifecycle,state_version,created_at,updated_at)
          SELECT ?,workspace_id,thread_id,task_id,run_id,source_authority_generation_id,'COMPACTED',
            operation_kind,provider,model,capability_profile,?,?,manifest_hash,plan_revision,steering_revision,
            source_fingerprint,limit_provenance,estimate_provenance,context_generation_id,?,
            'PREPARING',0,?,? FROM context_generations WHERE context_generation_id=? AND lifecycle='READY'
          """)){String now=Instant.now().toString();s.setString(1,id);s.setString(2,compiler);s.setString(3,policy);
            s.setLong(4,pointer(source.taskId(),source.runId(),source.operation()).orElseThrow().version());
            s.setString(5,now);s.setString(6,now);s.setString(7,source.contextId());
            if(s.executeUpdate()!=1)throw new ContextFailure("COMPACTION_STALE_SOURCE");}
    }
    private List<Segment> buildCandidateSegments(String sourceId,List<StoredSegment> all,
            List<StoredSegment> ignoredCompactable)throws SQLException{
        Map<Integer,List<StoredReference>> refs=references(sourceId).stream()
                .collect(java.util.stream.Collectors.groupingBy(StoredReference::ordinal,
                        LinkedHashMap::new,java.util.stream.Collectors.toList()));
        Map<Integer,String> payloads=new LinkedHashMap<>();
        Map<Integer,LedgerFactKind> factKinds=new LinkedHashMap<>();
        for(StoredSegment segment:all){String payload=renderStoredSegment(sourceId,segment,
                    refs.getOrDefault(segment.ordinal(),List.of()));
            payloads.put(segment.ordinal(),payload);factKinds.put(segment.ordinal(),factKind(segment,payload));}
        var policyInput=all.stream().map(segment->new com.paicli.context.DeterministicCompactionPolicy.HistorySegment(
                segment.ordinal(),segment.kind(),segment.compactability(),segment.estimatedTokens(),
                factKinds.get(segment.ordinal()))).toList();
        List<com.paicli.context.DeterministicCompactionPolicy.Selection> selections;
        Budget sourceBudget=readBudget(sourceId);
        try{selections=new com.paicli.context.DeterministicCompactionPolicy()
                    .candidateSelections(policyInput,sourceBudget);}
        catch(com.paicli.context.DeterministicCompactionPolicy.PolicyFailure|ArithmeticException failure){
            throw new ContextFailure(failure instanceof ArithmeticException?"CONTEXT_BUDGET_EXCEEDED":failure.getMessage());}
        if(selections.isEmpty())throw new ContextFailure("COMPACTION_NOT_APPLICABLE");
        var positive=new ArrayList<Set<Integer>>();
        for(var selection:selections){
            Set<Integer> selected=new LinkedHashSet<>(selection.compactOrdinals());
            CandidateProjection rendered=renderCandidateSegments(sourceId,all,refs,payloads,
                    factKinds,List.of(selected),sourceBudget);
            if(rendered.inputEstimate()<sourceBudget.inputEstimate())positive.add(Set.copyOf(selected));
        }
        if(positive.isEmpty())throw new ContextFailure("COMPACTION_NO_EFFECTIVE_REDUCTION");
        CandidateProjection combined=renderCandidateSegments(sourceId,all,refs,payloads,factKinds,
                List.copyOf(positive),sourceBudget);
        if(combined.inputEstimate()>=sourceBudget.inputEstimate())
            throw new ContextFailure("COMPACTION_NO_EFFECTIVE_REDUCTION");
        return combined.segments();
    }

    private CandidateProjection renderCandidateSegments(String sourceId,List<StoredSegment> all,
            Map<Integer,List<StoredReference>> refs,Map<Integer,String> payloads,
            Map<Integer,LedgerFactKind> factKinds,List<Set<Integer>> selectedGroups,
            Budget sourceBudget)throws SQLException{
        var projections=new ArrayList<LedgerProjection>();
        Set<Integer> globallyRemoved=new LinkedHashSet<>();
        for(Set<Integer> selected:selectedGroups){
            if(selected.isEmpty())throw new ContextFailure("COMPACTION_NOT_APPLICABLE");
            LedgerProjection projection=renderLedgerProjection(sourceId,all,refs,payloads,
                    factKinds,selected);
            if(!Collections.disjoint(globallyRemoved,projection.removedOrdinals()))
                throw new ContextFailure("COMPACTION_INTEGRITY");
            globallyRemoved.addAll(projection.removedOrdinals());projections.add(projection);
        }
        projections.sort(Comparator.comparingInt(LedgerProjection::insertionOrdinal));
        var byInsertion=new LinkedHashMap<Integer,LedgerProjection>();
        for(LedgerProjection projection:projections)
            if(byInsertion.put(projection.insertionOrdinal(),projection)!=null)
                throw new ContextFailure("COMPACTION_INTEGRITY");
        var out=new ArrayList<Segment>();int next=0;
        for(StoredSegment source:all){
            LedgerProjection projection=byInsertion.get(source.ordinal());
            if(projection!=null){Segment ledger=projection.ledger();out.add(new Segment(next++,
                    ledger.sourceClass(),ledger.kind(),ledger.scope(),ledger.compactability(),
                    ledger.payloadMode(),ledger.sanitizedPayload(),ledger.contentHash(),
                    ledger.estimatedTokens(),ledger.privacyClass(),ledger.bounds(),ledger.references()));}
            if(globallyRemoved.contains(source.ordinal()))continue;
            List<SourceReference> copied=new ArrayList<>();
            copied.add(new SourceReference("SOURCE_SEGMENT",sourceId+":"+source.ordinal(),null,
                    source.contentHash(),0));
            for(StoredReference ref:refs.getOrDefault(source.ordinal(),List.of()))copied.add(
                    new SourceReference(ref.type(),ref.id(),ref.revision(),ref.hash(),copied.size()));
            out.add(new Segment(next++,source.sourceClass(),source.kind(),source.scope(),
                    source.compactability(),source.payloadMode(),source.sanitizedPayload(),
                    source.contentHash(),source.estimatedTokens(),source.privacyClass(),source.bounds(),
                    List.copyOf(copied)));
        }
        int input=candidateInput(sourceId,sourceBudget,all,payloads,List.copyOf(out));
        return new CandidateProjection(List.copyOf(out),input);
    }

    private LedgerProjection renderLedgerProjection(String sourceId,List<StoredSegment> all,
            Map<Integer,List<StoredReference>> refs,Map<Integer,String> payloads,
            Map<Integer,LedgerFactKind> factKinds,Set<Integer> selected)throws SQLException{
        Set<String> selectedPairEvents=selected.stream().filter(ordinal->
                        "COMPLETED_TOOL_PAIR".equals(all.get(ordinal).kind()))
                .flatMap(ordinal->refs.getOrDefault(ordinal,List.of()).stream())
                .filter(reference->"EVENT".equals(reference.type())).map(StoredReference::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<Integer> pairEventOrdinals=all.stream().filter(segment->
                        Set.of("EVENT_ACTION","EVENT_OBSERVATION").contains(segment.kind()))
                .filter(segment->refs.getOrDefault(segment.ordinal(),List.of()).stream()
                        .anyMatch(reference->"EVENT".equals(reference.type())
                                &&selectedPairEvents.contains(reference.id())))
                .map(StoredSegment::ordinal)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<Integer> removed=new LinkedHashSet<>(selected);removed.addAll(pairEventOrdinals);
        List<Integer> orderedRemoved=removed.stream().sorted().toList();
        for(int index=1;index<orderedRemoved.size();index++)
            if(orderedRemoved.get(index)!=orderedRemoved.get(index-1)+1)
                throw new ContextFailure("COMPACTION_UNSUPPORTED_SOURCE");
        GenerationScope generationScope=generationScope(sourceId);
        var entries=new ArrayList<CompactedLedgerEntry>();
        List<SourceReference> ledgerRefs=new ArrayList<>();
        for(int ordinal:selected.stream().sorted().toList()){
            StoredSegment s=all.get(ordinal);List<StoredReference> sourceRefs=refs.getOrDefault(ordinal,List.of());
            String payload=payloads.get(ordinal);StoredReference primary=sourceRefs.stream()
                    .filter(reference->!Set.of("SOURCE_SEGMENT","CONTEXT_SEGMENT")
                            .contains(reference.type())).findFirst()
                    .orElseThrow(()->new ContextFailure("COMPACTION_UNSUPPORTED_SOURCE"));
            Map<String,String> fields=parseFact(payload);LedgerFactKind factKind=factKinds.get(ordinal);
            List<String> unresolved=factKind==LedgerFactKind.UNRESOLVED
                    ?unresolved(fields,s.kind()):List.of();
            Map<String,String> toolFields="TOOL_RESULT".equals(s.kind())?allowlistedToolFields(fields):Map.of();
            entries.add(new CompactedLedgerEntry(factKind,s.kind(),primary.id(),primary.revision(),
                    generationScope.workspaceId(),generationScope.threadId(),generationScope.taskId(),
                    generationScope.runId(),authority(primary.type()),s.kind(),
                    boundedLedgerPayload(payload,s.kind()),
                    preservedDecisions(fields),unresolved,
                    referenceIds(sourceRefs,"PLAN_EVIDENCE"),toolFields,
                    referenceIds(sourceRefs,"ARTIFACT_AUTHORITY"),
                    referenceIds(sourceRefs,"VERIFICATION_RESULT"),
                    referenceIds(sourceRefs,"REPAIR_ITERATION"),s.contentHash(),
                    com.paicli.context.DeterministicContextCompiler.RENDERER_VERSION,
                    s.privacyClass(),s.bounds()));
            ledgerRefs.add(new SourceReference("SOURCE_SEGMENT",
                    sourceId+":"+ordinal,null,s.contentHash(),ledgerRefs.size()));
            for(StoredReference ref:refs.getOrDefault(ordinal,List.of()))ledgerRefs.add(
                    new SourceReference(ref.type(),ref.id(),ref.revision(),ref.hash(),ledgerRefs.size()));
        }
        for(int ordinal:pairEventOrdinals){StoredSegment event=all.get(ordinal);
            ledgerRefs.add(new SourceReference("SOURCE_SEGMENT",sourceId+":"+ordinal,null,
                    event.contentHash(),ledgerRefs.size()));}
        String encoded=com.paicli.context.CompactedLedgerCodec.encode(entries);
        String ledgerPayload=sanitizer.sanitizeAndTruncate(encoded,65536);
        if(!ledgerPayload.equals(encoded))throw new ContextFailure("COMPACTION_BOUNDS");
        String ledgerHash=com.paicli.context.DeterministicContextCompiler.hash(ledgerPayload);
        Segment ledger=new Segment(0,SourceClass.COMPACTABLE_DURABLE_HISTORY,
                "COMPACTED_LEDGER",Scope.RUN_SCOPED,Compactability.NON_COMPACTABLE,
                PayloadMode.INLINE_SANITIZED,ledgerPayload,ledgerHash,
                new com.paicli.context.ContextBudgetResolver().estimate(ledgerPayload),
                PrivacyClass.SENSITIVE_REDACTED,65536,List.copyOf(ledgerRefs));
        return new LedgerProjection(Set.copyOf(removed),Collections.min(removed),ledger);
    }

    private int candidateInput(String sourceId,Budget sourceBudget,List<StoredSegment> source,
            Map<Integer,String> payloads,List<Segment> candidate){
        int input=sourceBudget.inputEstimate();
        Map<Integer,StoredSegment> byOrdinal=source.stream().collect(
                java.util.stream.Collectors.toMap(StoredSegment::ordinal,value->value));
        Set<Integer> removed=new LinkedHashSet<>();
        for(Segment segment:candidate)if("COMPACTED_LEDGER".equals(segment.kind())){
            for(SourceReference reference:segment.references()){
                if(!"SOURCE_SEGMENT".equals(reference.type()))continue;
                SegmentLocator locator=segmentLocator(reference);
                if(!sourceId.equals(locator.contextId()))continue;
                StoredSegment original=byOrdinal.get(locator.ordinal());
                if(original==null||!removed.add(original.ordinal()))
                    throw new ContextFailure("COMPACTION_INTEGRITY");
                input=Math.subtractExact(input,providerMessageCost(original.kind(),
                        payloads.get(original.ordinal())));
            }
            input=Math.addExact(input,providerMessageCost("COMPACTED_LEDGER",
                    Objects.requireNonNull(segment.sanitizedPayload())));
        }
        return input;
    }

    private static int providerMessageCost(String kind,String payload){
        com.paicli.model.AgentMessage.Role role;
        com.paicli.model.AgentMessage.MessageType type;
        switch(kind){
            case "COMPLETED_TOOL_PAIR","DURABLE_RUNTIME_PROMPT","COMPACTED_LEDGER"->{
                role=com.paicli.model.AgentMessage.Role.USER;
                type=com.paicli.model.AgentMessage.MessageType.USER;
            }
            case "EVENT_ACTION"->{role=com.paicli.model.AgentMessage.Role.ASSISTANT;
                type=com.paicli.model.AgentMessage.MessageType.ACTION;}
            case "EVENT_OBSERVATION"->{role=com.paicli.model.AgentMessage.Role.ASSISTANT;
                type=com.paicli.model.AgentMessage.MessageType.OBSERVATION;}
            case "PLAN_STEP","ATTEMPT","EVIDENCE","TOOL_OCCURRENCE","TOOL_RESULT",
                    "ARTIFACT_REFERENCE","VERIFICATION_RESULT","REPAIR_ITERATION",
                    "DURABLE_ASSISTANT_RESULT","EVENT_FINAL"->{
                role=com.paicli.model.AgentMessage.Role.ASSISTANT;
                type=com.paicli.model.AgentMessage.MessageType.FINAL;
            }
            default->throw new ContextFailure("COMPACTION_UNSUPPORTED_SOURCE");
        }
        return new com.paicli.context.ContextBudgetResolver().estimate(
                role+"\n"+type+"\n"+Objects.toString(payload,""));
    }

    private String storedSnapshotPayload(StoredSegment segment){
        String payload=segment.sanitizedPayload();
        if(payload==null||!DeterministicContextCompiler.hash(payload).equals(segment.contentHash())
                ||segment.bounds()<0||payload.length()>segment.bounds()
                ||!payload.equals(sanitizer.sanitizeAndTruncate(payload,segment.bounds())))
            throw new ContextFailure("COMPACTION_INTEGRITY");
        return payload;
    }

    private String boundedLedgerPayload(String payload,String kind){
        if("COMPLETED_TOOL_PAIR".equals(kind)){
            Map<String,String> fields=parseFact(payload);
            return "kind=COMPLETED_TOOL_PAIR;tool_name="
                    +boundedFact(fields.get("tool_name"),64)
                    +";arguments_summary="+boundedFact(fields.get("arguments_summary"),128)
                    +";status="+boundedFact(fields.get("status"),32)
                    +";occurrence_id="+boundedFact(fields.get("occurrence_id"),96)
                    +";digest="+boundedFact(fields.get("digest"),96)
                    +";result_excerpt="+boundedFact(fields.get("result_excerpt"),128);
        }
        if("DURABLE_RUNTIME_PROMPT".equals(kind))
            return "kind=DURABLE_RUNTIME_PROMPT;historical=true;content_digest="
                    +com.paicli.context.DeterministicContextCompiler.hash(payload)
                    +";authority=durable_plan_and_attempt_sources";
        String sanitized=sanitizer.sanitizeAndTruncate(payload,2048);
        return payload.length()<=2048?sanitized
                :sanitized+" [source payload bounded; verify with durable source reference]";
    }

    private String boundedFact(String value,int max){
        String clean=sanitizer.sanitizeAndTruncate(Objects.toString(value,""),max);
        return clean.replace("\\","\\\\").replace(";","\\;").replace("=","\\=")
                .replace("\r","\\r").replace("\n","\\n");
    }

    private GenerationScope generationScope(String contextId)throws SQLException{
        try(var statement=connection.prepareStatement("""
          SELECT workspace_id,thread_id,task_id,run_id FROM context_generations
          WHERE context_generation_id=?
          """)){statement.setString(1,contextId);try(var row=statement.executeQuery()){
            if(!row.next())throw new ContextFailure("CONTEXT_NOT_FOUND");
            return new GenerationScope(row.getString(1),row.getString(2),row.getString(3),row.getString(4));}}
    }
    private static LedgerFactKind factKind(StoredSegment segment,String payload){
        Map<String,String> fields=parseFact(payload);
        if("UNKNOWN".equals(fields.get("status"))||"UNKNOWN".equals(fields.get("state")))
            return LedgerFactKind.UNKNOWN;
        if(java.util.stream.Stream.of(fields.get("status"),fields.get("state"),fields.get("outcome"))
                .filter(Objects::nonNull).anyMatch(value->Set.of("PENDING","RUNNING","STARTED",
                        "FAILED","TIMED_OUT","ERRORED","CANCELLED").contains(value)))
            return LedgerFactKind.UNRESOLVED;
        if("true".equals(fields.get("unresolved"))||!blank(fields.get("remaining_unresolved"))
                ||(!blank(fields.get("blocker_code"))&&!"PASSED".equals(fields.get("status"))))
            return LedgerFactKind.UNRESOLVED;
        if("EVIDENCE".equals(segment.kind())||"ARTIFACT_REFERENCE".equals(segment.kind())
                ||"DURABLE_ASSISTANT_RESULT".equals(segment.kind())||"EVENT_FINAL".equals(segment.kind()))
            return LedgerFactKind.VERBATIM_REFERENCE;
        return LedgerFactKind.STRUCTURED_DERIVED;
    }
    private static Map<String,String> parseFact(String payload){
        var values=new LinkedHashMap<String,String>();StringBuilder token=new StringBuilder();
        var tokens=new ArrayList<String>();boolean escaped=false;
        for(int i=0;i<payload.length();i++){char value=payload.charAt(i);
            if(escaped){token.append(value);escaped=false;}else if(value=='\\')escaped=true;
            else if(value==';'){tokens.add(token.toString());token.setLength(0);}else token.append(value);}
        tokens.add(token.toString());
        for(String part:tokens){int split=part.indexOf('=');if(split>0)values.put(part.substring(0,split),
                part.substring(split+1));}
        return Map.copyOf(values);
    }
    private static List<String> unresolved(Map<String,String> fields,String kind){
        var values=new ArrayList<String>();
        for(String key:List.of("remaining_unresolved","unmet_items","blocker_code","reason"))if(!blank(fields.get(key)))
            values.add(key+"="+fields.get(key));
        if(values.isEmpty())values.add("unresolved "+kind);
        return List.copyOf(values);
    }
    private static Map<String,String> allowlistedToolFields(Map<String,String> fields){
        var values=new TreeMap<String,String>();for(String key:List.of("tool_row","tool_name","status",
                "success","output_present","error_present","external_effect"))
            if(fields.containsKey(key))values.put(key,fields.get(key));return Map.copyOf(values);
    }
    private static List<String> preservedDecisions(Map<String,String> fields){
        var values=new ArrayList<String>();
        for(String key:List.of("state","status","outcome","result","verified","completed"))
            if(!blank(fields.get(key)))values.add(key+"="+fields.get(key));
        return List.copyOf(values);
    }
    private static List<String> referenceIds(List<StoredReference> references,String type){return references.stream()
            .filter(reference->type.equals(reference.type())).map(StoredReference::id).distinct().sorted().toList();}
    private static String authority(String type){return switch(type){
        case "PLAN_STEP","PLAN_ATTEMPT","PLAN_EVIDENCE","COMPLETION_GATE"->"R1C_AUTHORITY";
        case "TOOL_OCCURRENCE","TOOL_RESULT_AUTHORITY","ARTIFACT_AUTHORITY"->"R1A_EFFECT_AUTHORITY";
        case "VERIFICATION_RESULT","REPAIR_ITERATION"->"RUNTIME_DURABLE_AUTHORITY";
        case "CONTEXT_INLINE"->"CONTEXT_OWNED_SANITIZED_SOURCE";
        case "EVENT"->"DURABLE_EVENT";default->"DURABLE_TYPED_SOURCE";};}
    private static boolean blank(String value){return value==null||value.isBlank();}
    private record CandidateProjection(List<Segment> segments,int inputEstimate){
        private CandidateProjection{segments=List.copyOf(segments);}
    }
    private record LedgerProjection(Set<Integer> removedOrdinals,int insertionOrdinal,Segment ledger){
        private LedgerProjection{removedOrdinals=Set.copyOf(removedOrdinals);}
    }
    private record GenerationScope(String workspaceId,String threadId,String taskId,String runId){ }
    private Budget readBudget(String id)throws SQLException{try(var s=connection.prepareStatement("""
      SELECT input_estimate,tool_schema_estimate,output_reserve,safety_margin,context_limit,
             limit_provenance,estimate_provenance,generation_kind,
             EXISTS(SELECT 1 FROM context_segments s WHERE s.context_generation_id=context_generations.context_generation_id
                    AND s.compactability='COMPACTABLE')
      FROM context_generations WHERE context_generation_id=?
      """)){s.setString(1,id);try(var r=s.executeQuery()){if(!r.next())throw new ContextFailure("CONTEXT_NOT_FOUND");
        int input=r.getInt(1),tools=r.getInt(2),reserve=r.getInt(3),margin=r.getInt(4);Integer limit=(Integer)r.getObject(5);
        var provenance=LimitProvenance.valueOf(r.getString(6));long total=(long)input+tools+reserve+margin;
        Budget.Decision d=limit==null?Budget.Decision.CONTEXT_BUDGET_UNKNOWN:
                total<=limit?Budget.Decision.READY:
                "BASE".equals(r.getString(8))&&r.getInt(9)!=0
                        ?Budget.Decision.COMPACTION_REQUIRED:Budget.Decision.CONTEXT_BUDGET_EXCEEDED;
        return new Budget(input,tools,reserve,margin,limit,provenance,
                EstimateProvenance.valueOf(r.getString(7)),d);}}}
    private Budget candidateBudget(String sourceId,List<Segment> candidateSegments)throws SQLException{
        Budget source=readBudget(sourceId);
        List<StoredSegment> sourceSegments=segments(sourceId);
        Map<Integer,List<StoredReference>> refs=references(sourceId).stream()
                .collect(java.util.stream.Collectors.groupingBy(StoredReference::ordinal,
                        LinkedHashMap::new,java.util.stream.Collectors.toList()));
        Map<Integer,String> payloads=new LinkedHashMap<>();
        for(StoredSegment segment:sourceSegments)payloads.put(segment.ordinal(),
                renderStoredSegment(sourceId,segment,refs.getOrDefault(segment.ordinal(),List.of())));
        int input=candidateInput(sourceId,source,sourceSegments,payloads,candidateSegments);
        long total=(long)input+source.toolSchemaEstimate()+source.outputReserve()+source.safetyMargin();
        Budget.Decision decision=source.contextLimit()==null?Budget.Decision.CONTEXT_BUDGET_UNKNOWN:
                total<=source.contextLimit()?Budget.Decision.READY:Budget.Decision.CONTEXT_BUDGET_EXCEEDED;
        return new Budget(input,source.toolSchemaEstimate(),source.outputReserve(),source.safetyMargin(),
                source.contextLimit(),source.limitProvenance(),source.estimateProvenance(),decision);
    }
    private void casPointer(String task,String run,OperationKind op,Pointer old,String next)throws SQLException{
        if(old==null)try(var s=connection.prepareStatement("INSERT INTO context_generation_pointers VALUES(?,?,?, ?,1,?)")){
            s.setString(1,task);s.setString(2,run);s.setString(3,op.name());s.setString(4,next);s.setString(5,Instant.now().toString());
            if(s.executeUpdate()!=1)throw new ContextFailure("CONTEXT_POINTER_CONFLICT");}
        else try(var s=connection.prepareStatement("""
          UPDATE context_generation_pointers SET active_context_generation_id=?,pointer_version=pointer_version+1,updated_at=?
          WHERE task_id=? AND run_id=? AND operation_kind=? AND pointer_version=? AND active_context_generation_id=?
          """)){s.setString(1,next);s.setString(2,Instant.now().toString());s.setString(3,task);s.setString(4,run);
            s.setString(5,op.name());s.setLong(6,old.version());s.setString(7,old.contextGenerationId());
            if(s.executeUpdate()!=1)throw new ContextFailure("CONTEXT_POINTER_CONFLICT");}
    }
    private void supersede(String id)throws SQLException{transitionOnly(id,Lifecycle.READY,Lifecycle.SUPERSEDED);}
    private void transitionOnly(String id,Lifecycle from,Lifecycle to)throws SQLException{
        long version=find(id).orElseThrow().stateVersion();
        try(var s=connection.prepareStatement(
            "UPDATE context_generations SET lifecycle=?,state_version=state_version+1,updated_at=? WHERE context_generation_id=? AND lifecycle=? AND state_version=?")){
        s.setString(1,to.name());s.setString(2,Instant.now().toString());s.setString(3,id);s.setString(4,from.name());
        s.setLong(5,version);
        if(s.executeUpdate()!=1)throw new ContextFailure("CONTEXT_STATE_CONFLICT");}}
    private void fail(String id,Lifecycle from,String code)throws SQLException{
        long version=find(id).orElseThrow().stateVersion();
        try(var s=connection.prepareStatement(
            "UPDATE context_generations SET lifecycle='FAILED',failure_code=?,state_version=state_version+1,updated_at=? WHERE context_generation_id=? AND lifecycle=? AND state_version=?")){
        s.setString(1,code);s.setString(2,Instant.now().toString());s.setString(3,id);s.setString(4,from.name());
        s.setLong(5,version);
        if(s.executeUpdate()!=1)throw new ContextFailure("CONTEXT_STATE_CONFLICT");}}
    private <T>T transaction(SqlWork<T> work)throws SQLException{
        if(immediateTransactionActive)return work.run();
        boolean auto=connection.getAutoCommit();if(auto)connection.setAutoCommit(false);
        try{T out=work.run();if(auto)connection.commit();return out;}catch(SQLException|RuntimeException e){if(auto)connection.rollback();throw e;}
        finally{if(auto)connection.setAutoCommit(true);}}
    /**
     * Apply must acquire SQLite's single-writer reservation before reading the Candidate.
     * A deferred transaction lets two processes read PREPARED and then race while upgrading,
     * which surfaces SQLITE_BUSY instead of the intended idempotent READY result.
     */
    private <T>T immediateTransaction(SqlWork<T> work)throws SQLException{
        if(immediateTransactionActive)return work.run();
        if(!connection.getAutoCommit())return transaction(work);
        try(var statement=connection.createStatement()){statement.execute("BEGIN IMMEDIATE");}
        immediateTransactionActive=true;
        try{
            T out=work.run();
            try(var statement=connection.createStatement()){statement.execute("COMMIT");}
            return out;
        }catch(SQLException|RuntimeException failure){
            try(var statement=connection.createStatement()){statement.execute("ROLLBACK");}
            catch(SQLException rollback){failure.addSuppressed(rollback);}
            throw failure;
        }finally{
            immediateTransactionActive=false;
        }
    }
    private static Long nullableLong(ResultSet r,int i)throws SQLException{long v=r.getLong(i);return r.wasNull()?null:v;}
    private static void nullable(PreparedStatement s,int i,String v)throws SQLException{if(v==null)s.setNull(i,Types.VARCHAR);else s.setString(i,v);}
    private static String safe(String v,int max){if(v==null||v.isBlank())return "unknown";String x=v.trim();return x.substring(0,Math.min(max,x.length()));}
    @FunctionalInterface private interface SqlWork<T>{T run()throws SQLException;}
    @FunctionalInterface private interface SafePointCheck{
        ContextSafePointAuthority.Decision evaluate(Pointer pointer)throws SQLException;
    }
    public record Pointer(String taskId,String runId,OperationKind operation,String contextGenerationId,long version){}
    public record Published(String contextGenerationId,Lifecycle lifecycle,long pointerVersion,Budget budget){}
    public record Generation(String contextId,GenerationKind kind,OperationKind operation,String taskId,String runId,
            String sourceContextId,String sourceFingerprint,String compiledHash,Lifecycle lifecycle,long stateVersion,
            String failureCode,Long sourcePointerVersion,String previewSummary,String compilerVersion,
            String policyVersion,String provider,String model,String capabilityProfile,String manifestHash,
            String planRevision,String steeringRevision){}
    public record StoredSegment(int ordinal,SourceClass sourceClass,String kind,Scope scope,
            Compactability compactability,PayloadMode payloadMode,String sanitizedPayload,String contentHash,
            int estimatedTokens,PrivacyClass privacyClass,int bounds)
            implements com.paicli.context.DeterministicContextCompiler.SegmentView{}
    public record StoredReference(int ordinal,String type,String id,String revision,String hash,int order){}
    public record VerifiedLedgerSegment(StoredSegment segment,List<StoredReference> references){
        public VerifiedLedgerSegment{references=List.copyOf(references);}
    }
    public record VerifiedActiveLedger(Pointer pointer,List<VerifiedLedgerSegment> segments){
        public VerifiedActiveLedger{segments=List.copyOf(segments);}
    }
    public record ParentHistoryTarget(String childTaskId,String childRunId,String childGenerationId,
            String parentTaskId,String parentRunId,Pointer pointer){}
    public record ParentSegmentAnchor(String contextGenerationId,int ordinal){}
    public record ParentHistoryProjection(String kind,String projectionKey,String payload,
            String contentHash,PrivacyClass privacyClass,int bounds,List<ParentSegmentAnchor> anchors){
        public ParentHistoryProjection{anchors=List.copyOf(anchors);}
    }
    public record VerifiedParentHistory(ParentHistoryTarget target,
            List<ParentHistoryProjection> projections){
        public VerifiedParentHistory{projections=List.copyOf(projections);}
    }
    private record ParentBinding(String parentTaskId,String parentRunId){}
    private record ParentEvent(String toolCallId){}
    private record ParentPair(String payload,String contentHash,Set<String> sourceKeys){
        private ParentPair{sourceKeys=Collections.unmodifiableSet(new LinkedHashSet<>(sourceKeys));}
    }
    private record SegmentLocator(String contextId,int ordinal){}
    private record LedgerSource(String kind,String id,String revision,String contentHash){}
    private record IntegritySegment(StoredSegment segment,List<SourceReference> references)
            implements com.paicli.context.DeterministicContextCompiler.ClosureSegmentView{
        @Override public int ordinal(){return segment.ordinal();}
        @Override public SourceClass sourceClass(){return segment.sourceClass();}
        @Override public String kind(){return segment.kind();}
        @Override public Scope scope(){return segment.scope();}
        @Override public Compactability compactability(){return segment.compactability();}
        @Override public PayloadMode payloadMode(){return segment.payloadMode();}
        @Override public String contentHash(){return segment.contentHash();}
        @Override public int estimatedTokens(){return segment.estimatedTokens();}
        @Override public PrivacyClass privacyClass(){return segment.privacyClass();}
        @Override public int bounds(){return segment.bounds();}
    }
    public static final class ContextFailure extends RuntimeException{public ContextFailure(String code){super(code);}}
}
