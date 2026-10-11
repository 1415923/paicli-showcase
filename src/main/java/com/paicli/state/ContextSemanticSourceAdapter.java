package com.paicli.state;

import com.paicli.context.ContextModels.*;
import com.paicli.context.DeterministicContextCompiler;
import com.paicli.identity.CanonicalId;
import com.paicli.state.PlanCompletionModels.Evidence;
import com.paicli.state.PlanExecutionModels.Attempt;
import com.paicli.state.PlanExecutionModels.StepExecution;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Typed adapters from existing R1 authority into bounded Context semantic facts. */
final class ContextSemanticSourceAdapter {
    private final Connection connection;
    ContextSemanticSourceAdapter(Connection connection){this.connection=Objects.requireNonNull(connection);}

    List<SemanticFact> read(Identity identity)throws SQLException{
        var out=new ArrayList<SemanticFact>();
        if(identity.planRevision()!=null)addPlanFacts(out,identity);
        addToolFacts(out,identity);
        addVerificationRepairFacts(out,identity);
        return out.stream().sorted(Comparator.comparingLong(SemanticFact::sequence)
                .thenComparing(SemanticFact::kind).thenComparing(SemanticFact::sourceId)).toList();
    }

    String renderReference(Owner owner,SourceReference reference)throws SQLException{
        return switch(reference.type()){
            case "PLAN_STEP"->stepFact(owner,reference.id(),reference.revision()).payload();
            case "PLAN_ATTEMPT"->attemptFact(owner,reference.id(),reference.revision()).payload();
            case "PLAN_EVIDENCE"->evidenceFact(owner,reference.id(),reference.revision()).payload();
            case "COMPLETION_GATE"->completionFact(owner,reference.id(),reference.revision()).payload();
            case "TOOL_OCCURRENCE"->occurrenceFact(owner,reference.id(),reference.revision()).payload();
            case "TOOL_RESULT_AUTHORITY"->toolResultFact(owner,reference.id(),reference.revision()).payload();
            case "ARTIFACT_AUTHORITY"->artifactFact(owner,reference.id(),reference.revision()).payload();
            case "VERIFICATION_RESULT"->verificationFact(owner,reference.id(),reference.revision()).payload();
            case "REPAIR_ITERATION"->repairFact(owner,reference.id(),reference.revision()).payload();
            default->throw new SemanticFailure("CONTEXT_SOURCE_INTEGRITY");
        };
    }

    private void addPlanFacts(List<SemanticFact> out,Identity identity)throws SQLException{
        CanonicalId task=CanonicalId.parse(identity.taskId()),plan=CanonicalId.parse(identity.planRevision());
        var execution=new PlanExecutionStore(connection);
        var completion=new PlanCompletionStore(connection);
        List<Evidence> evidence=completion.readEvidence(task,plan).stream()
                .filter(item->item.runId().value().equals(identity.runId())).toList();
        long sequence=1000;
        for(StepExecution step:execution.readStates(task,plan)){
            List<SourceReference> refs=new ArrayList<>();
            String payload=stepPayload(step,evidence.stream()
                    .filter(item->item.stepRevisionId().equals(step.stepRevisionId())).toList());
            refs.add(reference("PLAN_STEP",step.stepRevisionId().value(),
                    Long.toString(step.transitionVersion()),payload,0));
            for(Evidence item:evidence.stream().filter(e->e.stepRevisionId().equals(step.stepRevisionId())).toList()){
                SemanticFact fact=evidenceSemantic(identity,item,sequence++);refs.add(reference(
                        "PLAN_EVIDENCE",item.canonicalHash(),item.createdAt().toString(),fact.payload(),refs.size()));
            }
            boolean closed=java.util.Set.of(PlanExecutionModels.StepState.SUCCEEDED,
                    PlanExecutionModels.StepState.SKIPPED).contains(step.state());
            out.add(new SemanticFact("PLAN_STEP",step.stepRevisionId().value(),
                    Long.toString(step.transitionVersion()),"R1C_PLAN_STEP_AUTHORITY",Scope.TASK_SCOPED,
                    closed?Compactability.COMPACTABLE:Compactability.NON_COMPACTABLE,
                    closed?LedgerFactKind.STRUCTURED_DERIVED:LedgerFactKind.UNRESOLVED,
                    payload,List.copyOf(refs),sequence++));
        }
        for(Attempt attempt:execution.readAttempts(task,plan))if(attempt.runId().value().equals(identity.runId())){
            String payload=attemptPayload(attempt);boolean terminal=attempt.state()!=PlanExecutionModels.AttemptState.RUNNING
                    &&attempt.state()!=PlanExecutionModels.AttemptState.UNKNOWN;
            LedgerFactKind fact=attempt.state()==PlanExecutionModels.AttemptState.UNKNOWN
                    ?LedgerFactKind.UNKNOWN:terminal?LedgerFactKind.STRUCTURED_DERIVED:LedgerFactKind.UNRESOLVED;
            out.add(new SemanticFact("ATTEMPT",attempt.safeIdentity(),
                    Long.toString(attempt.transitionVersion()),"R1C_ATTEMPT_AUTHORITY",Scope.RUN_SCOPED,
                    terminal?Compactability.COMPACTABLE:Compactability.NON_COMPACTABLE,fact,payload,
                    List.of(reference("PLAN_ATTEMPT",attempt.safeIdentity(),
                            Long.toString(attempt.transitionVersion()),payload,0)),sequence++));
        }
        for(Evidence item:evidence){
            out.add(evidenceSemantic(identity,item,sequence++));
            if(item.protectedReference()!=null&&!item.protectedReference().isBlank()){
                String sourceId=DeterministicContextCompiler.hash(item.protectedReference());
                String artifact=artifactPayload(item.producerIdentity(),sourceId,item.canonicalHash(),
                        item.protectedReference());
                out.add(new SemanticFact("ARTIFACT_REFERENCE",sourceId,item.canonicalHash(),
                        "R1C_EVIDENCE_ARTIFACT",Scope.RUN_SCOPED,Compactability.COMPACTABLE,
                        LedgerFactKind.VERBATIM_REFERENCE,artifact,List.of(reference("ARTIFACT_AUTHORITY",
                                sourceId,item.canonicalHash(),artifact,0)),sequence++));
            }
        }
        var recovery=completion.readRecovery(task,plan);
        if(recovery.status()==PlanCompletionStore.Status.EVIDENCE_INTEGRITY_FAILURE
                ||recovery.status()==PlanCompletionStore.Status.COMPLETION_INTEGRITY_FAILURE)
            throw new SemanticFailure("CONTEXT_SOURCE_INTEGRITY");
        String payload=completionPayload(identity,recovery);
        String revision=recovery.evaluationCount()+":"+recovery.completed()+":"
                +Objects.toString(recovery.result(),"UNRESOLVED");
        out.add(new SemanticFact("COMPLETION_GATE",identity.planRevision(),revision,
                "R1C_COMPLETION_AUTHORITY",Scope.TASK_SCOPED,Compactability.NON_COMPACTABLE,
                recovery.completed()?LedgerFactKind.STRUCTURED_DERIVED:LedgerFactKind.UNRESOLVED,
                payload,List.of(reference("COMPLETION_GATE",identity.planRevision(),revision,payload,0)),
                sequence));
    }

    private SemanticFact evidenceSemantic(Identity identity,Evidence evidence,long sequence)throws SQLException{
        if(!evidence.integrityValid())throw new SemanticFailure("CONTEXT_SOURCE_INTEGRITY");
        boolean verified=isVerified(evidence);String payload=evidencePayload(evidence,verified);
        LedgerFactKind fact="UNKNOWN".equals(evidence.observedStatus())
                ?LedgerFactKind.UNKNOWN:LedgerFactKind.VERBATIM_REFERENCE;
        return new SemanticFact("EVIDENCE",evidence.canonicalHash(),evidence.createdAt().toString(),
                "R1C_EVIDENCE_AUTHORITY",Scope.RUN_SCOPED,Compactability.COMPACTABLE,fact,payload,
                List.of(reference("PLAN_EVIDENCE",evidence.canonicalHash(),
                        evidence.createdAt().toString(),payload,0)),sequence);
    }

    private void addToolFacts(List<SemanticFact> out,Identity identity)throws SQLException{
        try(var statement=connection.prepareStatement("""
          SELECT occurrence_id,tool_call_id,iteration_id,operation_type,target,status,result_hash,
                 external_outcome,started_at,completed_at,snapshot_id
          FROM side_effect_occurrences WHERE task_id=? AND run_id=? AND operation_type='TOOL'
          ORDER BY started_at,occurrence_id
          """)){
            statement.setString(1,identity.taskId());statement.setString(2,identity.runId());
            try(var row=statement.executeQuery()){long sequence=3000;while(row.next()){
                String occurrence=row.getString(1),revision=occurrenceRevision(row.getString(6),
                        row.getString(9),row.getString(10),row.getString(7));
                SemanticFact occurrenceFact=occurrenceFact(new Owner(identity.taskId(),identity.runId(),
                        identity.planRevision()),occurrence,revision);
                out.add(new SemanticFact("TOOL_OCCURRENCE",occurrence,revision,
                        "R1A_SIDE_EFFECT_OCCURRENCE",Scope.RUN_SCOPED,
                        terminal(row.getString(6))?Compactability.COMPACTABLE:Compactability.NON_COMPACTABLE,
                        "UNKNOWN".equals(row.getString(6))?LedgerFactKind.UNKNOWN:
                            terminal(row.getString(6))?LedgerFactKind.STRUCTURED_DERIVED:
                                    LedgerFactKind.UNRESOLVED,
                        occurrenceFact.payload(),occurrenceFact.references(),sequence++));
                SemanticFact result=toolResultFact(new Owner(identity.taskId(),identity.runId(),
                        identity.planRevision()),occurrence,revision);
                if(result!=null)out.add(new SemanticFact("TOOL_RESULT",occurrence,revision,
                        "R1A_OCCURRENCE_AND_TOOL_RESULT",Scope.RUN_SCOPED,
                        terminal(row.getString(6))?Compactability.COMPACTABLE:Compactability.NON_COMPACTABLE,
                        "UNKNOWN".equals(row.getString(6))?LedgerFactKind.UNKNOWN:
                            terminal(row.getString(6))?LedgerFactKind.STRUCTURED_DERIVED:
                                    LedgerFactKind.UNRESOLVED,
                        result.payload(),result.references(),sequence++));
                if(row.getString(11)!=null){
                    String artifactId=row.getString(11),artifactRevision=revision;
                    SemanticFact artifact=artifactFact(new Owner(identity.taskId(),identity.runId(),
                            identity.planRevision()),artifactId,artifactRevision);
                    out.add(new SemanticFact("ARTIFACT_REFERENCE",artifactId,artifactRevision,
                            "R1A_OCCURRENCE_ARTIFACT",Scope.RUN_SCOPED,Compactability.COMPACTABLE,
                            LedgerFactKind.VERBATIM_REFERENCE,artifact.payload(),artifact.references(),sequence++));
                }
            }}
        }
    }

    private void addVerificationRepairFacts(List<SemanticFact> out,Identity identity)throws SQLException{
        long sequence=4000;
        for(var record:new VerificationStore(connection).findByRun(identity.runId())){
            String revision=Objects.toString(record.completedAt(),record.status());
            SemanticFact fact=verificationFact(new Owner(identity.taskId(),identity.runId(),
                    identity.planRevision()),record.verificationId(),revision);
            boolean terminal=!"RUNNING".equals(record.status());
            out.add(new SemanticFact("VERIFICATION_RESULT",record.verificationId(),revision,
                    "DURABLE_VERIFICATION",Scope.RUN_SCOPED,
                    terminal?Compactability.COMPACTABLE:Compactability.NON_COMPACTABLE,
                    terminal?LedgerFactKind.STRUCTURED_DERIVED:LedgerFactKind.UNRESOLVED,
                    fact.payload(),fact.references(),sequence++));
        }
        for(var record:new RepairAttemptStore(connection).findByRun(identity.runId())){
            String revision=Objects.toString(record.completedAt(),record.status());
            SemanticFact fact=repairFact(new Owner(identity.taskId(),identity.runId(),
                    identity.planRevision()),record.repairId(),revision);
            boolean closed=RepairAttemptStore.STATUS_COMPLETED.equals(record.status());
            out.add(new SemanticFact("REPAIR_ITERATION",record.repairId(),revision,
                    "DURABLE_REPAIR",Scope.RUN_SCOPED,
                    closed?Compactability.COMPACTABLE:Compactability.NON_COMPACTABLE,
                    closed?LedgerFactKind.STRUCTURED_DERIVED:LedgerFactKind.UNRESOLVED,
                    fact.payload(),fact.references(),sequence++));
        }
    }

    private SemanticFact stepFact(Owner owner,String id,String revision){
        requirePlan(owner);for(StepExecution step:new PlanExecutionStore(connection).readStates(
                CanonicalId.parse(owner.taskId()),CanonicalId.parse(owner.planRevision())))
            if(step.stepRevisionId().value().equals(id)&&Long.toString(step.transitionVersion()).equals(revision)){
                List<Evidence> evidence=new PlanCompletionStore(connection).readEvidence(
                        CanonicalId.parse(owner.taskId()),CanonicalId.parse(owner.planRevision())).stream()
                        .filter(e->e.runId().value().equals(owner.runId())&&e.stepRevisionId().value().equals(id)).toList();
                return bare(stepPayload(step,evidence));}
        throw new SemanticFailure("CONTEXT_STALE_SOURCE");
    }
    private SemanticFact attemptFact(Owner owner,String id,String revision){
        requirePlan(owner);for(Attempt attempt:new PlanExecutionStore(connection).readAttempts(
                CanonicalId.parse(owner.taskId()),CanonicalId.parse(owner.planRevision())))
            if(attempt.safeIdentity().equals(id)&&attempt.runId().value().equals(owner.runId())
                    &&Long.toString(attempt.transitionVersion()).equals(revision))return bare(attemptPayload(attempt));
        throw new SemanticFailure("CONTEXT_STALE_SOURCE");
    }
    private SemanticFact evidenceFact(Owner owner,String id,String revision){
        requirePlan(owner);for(Evidence evidence:new PlanCompletionStore(connection).readEvidence(
                CanonicalId.parse(owner.taskId()),CanonicalId.parse(owner.planRevision())))
            if(evidence.canonicalHash().equals(id)&&evidence.runId().value().equals(owner.runId())
                    &&evidence.createdAt().toString().equals(revision))
                try{return bare(evidencePayload(evidence,isVerified(evidence)));}
                catch(SQLException error){throw new SemanticFailure("CONTEXT_SOURCE_INTEGRITY",error);}
        throw new SemanticFailure("CONTEXT_STALE_SOURCE");
    }
    private SemanticFact completionFact(Owner owner,String id,String revision){
        requirePlan(owner);if(!owner.planRevision().equals(id))throw new SemanticFailure("CONTEXT_STALE_SOURCE");
        var recovery=new PlanCompletionStore(connection).readRecovery(CanonicalId.parse(owner.taskId()),
                CanonicalId.parse(owner.planRevision()));
        String actual=recovery.evaluationCount()+":"+recovery.completed()+":"
                +Objects.toString(recovery.result(),"UNRESOLVED");
        if(!actual.equals(revision))throw new SemanticFailure("CONTEXT_STALE_SOURCE");
        return bare(completionPayload(owner,recovery));
    }
    private SemanticFact occurrenceFact(Owner owner,String id,String revision)throws SQLException{
        try(var statement=connection.prepareStatement("""
          SELECT task_id,run_id,tool_call_id,iteration_id,operation_type,target,status,result_hash,
                 external_outcome,started_at,completed_at,snapshot_id
          FROM side_effect_occurrences WHERE occurrence_id=?
          """)){statement.setString(1,id);try(var row=statement.executeQuery()){
            if(!row.next()||!owner.taskId().equals(row.getString(1))||!owner.runId().equals(row.getString(2)))
                throw new SemanticFailure("CONTEXT_IDENTITY_MISMATCH");
            String actual=occurrenceRevision(row.getString(7),row.getString(10),row.getString(11),row.getString(8));
            if(!actual.equals(revision))throw new SemanticFailure("CONTEXT_STALE_SOURCE");
            String payload=fact("TOOL_OCCURRENCE","source_id",id,"revision",revision,
                    "tool_call_id",Objects.toString(row.getString(3),"iteration:"+row.getString(4)),
                    "tool_kind",row.getString(6),"operation_type",row.getString(5),"status",row.getString(7),
                    "external_effect",Objects.toString(row.getString(9),"UNKNOWN"),
                    "artifact_reference",row.getString(12),"unresolved",Boolean.toString(!terminal(row.getString(7))));
            return new SemanticFact("TOOL_OCCURRENCE",id,revision,"R1A_SIDE_EFFECT_OCCURRENCE",
                    Scope.RUN_SCOPED,Compactability.COMPACTABLE,LedgerFactKind.STRUCTURED_DERIVED,payload,
                    List.of(reference("TOOL_OCCURRENCE",id,revision,payload,0)),0);
        }}
    }
    private SemanticFact toolResultFact(Owner owner,String occurrenceId,String revision)throws SQLException{
        occurrenceFact(owner,occurrenceId,revision);
        try(var statement=connection.prepareStatement("""
          SELECT o.task_id,o.run_id,o.tool_call_id,o.status,o.result_hash,o.external_outcome,
                 t.id,t.tool_name,t.result_success,t.result_output IS NOT NULL,t.result_error IS NOT NULL,t.created_at,
                 o.snapshot_id
          FROM side_effect_occurrences o LEFT JOIN tool_calls t ON t.task_id=o.task_id AND (
            (o.tool_call_id IS NOT NULL AND t.tool_call_id=o.tool_call_id) OR
            (o.tool_call_id IS NULL AND CAST(t.iteration AS TEXT)=o.iteration_id AND t.tool_name=o.target))
          WHERE o.occurrence_id=? ORDER BY t.id
          """)){statement.setString(1,occurrenceId);try(var row=statement.executeQuery()){
            if(!row.next()||!owner.taskId().equals(row.getString(1))||!owner.runId().equals(row.getString(2)))
                throw new SemanticFailure("CONTEXT_IDENTITY_MISMATCH");
            String toolRow=row.getString(7);if(toolRow==null)return null;
            String toolId=toolRow;if(row.next())throw new SemanticFailure("CONTEXT_SOURCE_INTEGRITY");
            String payload=fact("TOOL_RESULT","source_id",occurrenceId,"revision",revision,
                    "tool_row",toolId,"tool_name",rowValue(connection,occurrenceId,"tool_name"),
                    "status",rowValue(connection,occurrenceId,"status"),
                    "success",rowValue(connection,occurrenceId,"result_success"),
                    "output_present",rowValue(connection,occurrenceId,"output_present"),
                    "error_present",rowValue(connection,occurrenceId,"error_present"),
                    "external_effect",rowValue(connection,occurrenceId,"external_outcome"),
                    "artifact_reference",rowValue(connection,occurrenceId,"snapshot_id"));
            var refs=new ArrayList<SourceReference>();refs.add(reference("TOOL_RESULT_AUTHORITY",
                    occurrenceId,revision,payload,0));
            String artifact=rowValue(connection,occurrenceId,"snapshot_id");
            if(artifact!=null&&!artifact.isBlank()){
                String artifactPayload=artifactPayload(occurrenceId,artifact,revision);
                refs.add(reference("ARTIFACT_AUTHORITY",artifact,revision,artifactPayload,1));
            }
            return new SemanticFact("TOOL_RESULT",occurrenceId,revision,"R1A_OCCURRENCE_AND_TOOL_RESULT",
                    Scope.RUN_SCOPED,Compactability.COMPACTABLE,LedgerFactKind.STRUCTURED_DERIVED,
                    payload,List.copyOf(refs),0);
        }}
    }
    private SemanticFact artifactFact(Owner owner,String id,String revision)throws SQLException{
        try(var statement=connection.prepareStatement("""
          SELECT occurrence_id,status,started_at,completed_at,result_hash FROM side_effect_occurrences
          WHERE task_id=? AND run_id=? AND snapshot_id=?
          ORDER BY occurrence_id LIMIT 1
          """)){statement.setString(1,owner.taskId());statement.setString(2,owner.runId());
            statement.setString(3,id);try(var row=statement.executeQuery()){
                if(row.next()){
                    String actual=occurrenceRevision(row.getString(2),row.getString(3),row.getString(4),
                            row.getString(5));if(!actual.equals(revision))
                        throw new SemanticFailure("CONTEXT_STALE_SOURCE");
                    String payload=artifactPayload(row.getString(1),id,revision,id);
                    return new SemanticFact("ARTIFACT_REFERENCE",id,revision,"R1A_OCCURRENCE_ARTIFACT",
                            Scope.RUN_SCOPED,Compactability.COMPACTABLE,LedgerFactKind.VERBATIM_REFERENCE,
                            payload,List.of(reference("ARTIFACT_AUTHORITY",id,revision,payload,0)),0);
                }
                if(owner.planRevision()!=null)for(Evidence evidence:new PlanCompletionStore(connection)
                        .readEvidence(CanonicalId.parse(owner.taskId()),CanonicalId.parse(owner.planRevision())))
                    if(evidence.runId().value().equals(owner.runId())&&evidence.canonicalHash().equals(revision)
                            &&evidence.protectedReference()!=null
                            &&DeterministicContextCompiler.hash(evidence.protectedReference()).equals(id)){
                        String payload=artifactPayload(evidence.producerIdentity(),id,revision,
                                evidence.protectedReference());
                        return new SemanticFact("ARTIFACT_REFERENCE",id,revision,"R1C_EVIDENCE_ARTIFACT",
                                Scope.RUN_SCOPED,Compactability.COMPACTABLE,LedgerFactKind.VERBATIM_REFERENCE,
                                payload,List.of(reference("ARTIFACT_AUTHORITY",id,revision,payload,0)),0);
                    }
                throw new SemanticFailure("CONTEXT_STALE_SOURCE");
            }}
    }
    private SemanticFact verificationFact(Owner owner,String id,String revision)throws SQLException{
        var record=new VerificationStore(connection).findById(id).orElseThrow(
                ()->new SemanticFailure("CONTEXT_STALE_SOURCE"));
        if(!owner.taskId().equals(record.taskId())||!owner.runId().equals(record.runId())
                ||!Objects.toString(record.completedAt(),record.status()).equals(revision))
            throw new SemanticFailure("CONTEXT_IDENTITY_MISMATCH");
        List<String> evidence=verificationEvidenceHashes(owner,id);
        String payload=fact("VERIFICATION_RESULT","source_id",id,"revision",revision,
                "run_id",record.runId(),"iteration",Integer.toString(record.repairAttempt()),
                "status",record.status(),"exit_code",Objects.toString(record.exitCode(),"UNKNOWN"),
                "evidence_references",String.join(",",evidence),"blocker_code",
                "PASSED".equals(record.status())?null:"VERIFICATION_"+record.status(),
                "unresolved",Boolean.toString(!"PASSED".equals(record.status())));
        var refs=new ArrayList<SourceReference>();refs.add(reference("VERIFICATION_RESULT",id,revision,payload,0));
        int order=1;for(String hash:evidence){Evidence durable=evidenceByHash(owner,hash);
            String evidenceRevision=durable.createdAt().toString();SemanticFact item=evidenceFact(
                    owner,hash,evidenceRevision);
            refs.add(reference("PLAN_EVIDENCE",hash,evidenceRevision,item.payload(),order++));
            if(durable.protectedReference()!=null&&!durable.protectedReference().isBlank()){
                String artifactId=DeterministicContextCompiler.hash(durable.protectedReference());
                String artifactPayload=artifactPayload(durable.producerIdentity(),artifactId,
                        durable.canonicalHash(),durable.protectedReference());
                refs.add(reference("ARTIFACT_AUTHORITY",artifactId,durable.canonicalHash(),
                        artifactPayload,order++));
            }
        }
        return new SemanticFact("VERIFICATION_RESULT",id,revision,"DURABLE_VERIFICATION",
                Scope.RUN_SCOPED,Compactability.COMPACTABLE,LedgerFactKind.STRUCTURED_DERIVED,payload,
                List.copyOf(refs),0);
    }
    private SemanticFact repairFact(Owner owner,String id,String revision)throws SQLException{
        var record=new RepairAttemptStore(connection).findById(id).orElseThrow(
                ()->new SemanticFailure("CONTEXT_STALE_SOURCE"));
        if(!owner.taskId().equals(record.taskId())||!owner.runId().equals(record.runId())
                ||!Objects.toString(record.completedAt(),record.status()).equals(revision))
            throw new SemanticFailure("CONTEXT_IDENTITY_MISMATCH");
        String payload=fact("REPAIR_ITERATION","source_id",id,"revision",revision,
                "run_id",record.runId(),"iteration",Integer.toString(record.attemptNo()),
                "source_verification",record.triggerVerificationId(),"outcome",record.status(),
                "remaining_unresolved",Objects.toString(record.failureSummary(),
                        RepairAttemptStore.STATUS_COMPLETED.equals(record.status())?"":"UNKNOWN"),
                "unresolved",Boolean.toString(!RepairAttemptStore.STATUS_COMPLETED.equals(record.status())));
        var refs=new ArrayList<SourceReference>();refs.add(reference("REPAIR_ITERATION",id,revision,payload,0));
        if(record.triggerVerificationId()!=null&&!record.triggerVerificationId().isBlank()){
            var verification=new VerificationStore(connection).findById(record.triggerVerificationId())
                    .orElseThrow(()->new SemanticFailure("CONTEXT_STALE_SOURCE"));
            String verificationRevision=Objects.toString(verification.completedAt(),verification.status());
            SemanticFact source=verificationFact(owner,verification.verificationId(),verificationRevision);
            refs.add(reference("VERIFICATION_RESULT",verification.verificationId(),verificationRevision,
                    source.payload(),refs.size()));
            for(SourceReference nested:source.references())if(!"VERIFICATION_RESULT".equals(nested.type()))
                refs.add(new SourceReference(nested.type(),nested.id(),nested.revision(),nested.hash(),refs.size()));
        }
        return new SemanticFact("REPAIR_ITERATION",id,revision,"DURABLE_REPAIR",Scope.RUN_SCOPED,
                Compactability.COMPACTABLE,RepairAttemptStore.STATUS_COMPLETED.equals(record.status())
                        ?LedgerFactKind.STRUCTURED_DERIVED:LedgerFactKind.UNRESOLVED,payload,
                List.copyOf(refs),0);
    }

    private boolean isVerified(Evidence evidence)throws SQLException{
        if(!evidence.integrityValid()||evidence.kind()!=PlanCompletionModels.EvidenceKind.VERIFICATION_RESULT)
            return false;
        try(var statement=connection.prepareStatement("""
          SELECT 1 FROM plan_verification_evidence WHERE task_id=? AND plan_revision_id=?
            AND step_revision_id=? AND attempt_run_id=? AND attempt_number=?
            AND verification_id=? AND verification_status='PASSED'
          """)){statement.setString(1,evidence.taskId().value());statement.setString(2,evidence.planRevisionId().value());
            statement.setString(3,evidence.stepRevisionId().value());statement.setString(4,evidence.runId().value());
            statement.setInt(5,evidence.attemptNumber());statement.setString(6,evidence.producerIdentity());
            try(var row=statement.executeQuery()){return row.next();}}
    }
    private List<String> verificationEvidenceHashes(Owner owner,String verification)throws SQLException{
        if(owner.planRevision()==null)return List.of();var out=new ArrayList<String>();
        for(Evidence evidence:new PlanCompletionStore(connection).readEvidence(CanonicalId.parse(owner.taskId()),
                CanonicalId.parse(owner.planRevision())))if(evidence.runId().value().equals(owner.runId())
                &&evidence.producerIdentity().equals(verification)&&isVerified(evidence))out.add(evidence.canonicalHash());
        return out.stream().sorted().toList();
    }
    private String evidenceRevision(Owner owner,String hash){
        return evidenceByHash(owner,hash).createdAt().toString();
    }
    private Evidence evidenceByHash(Owner owner,String hash){
        for(Evidence evidence:new PlanCompletionStore(connection).readEvidence(CanonicalId.parse(owner.taskId()),
                CanonicalId.parse(owner.planRevision())))if(evidence.runId().value().equals(owner.runId())
                        &&evidence.canonicalHash().equals(hash))return evidence;
        throw new SemanticFailure("CONTEXT_STALE_SOURCE");
    }
    private String rowValue(Connection ignored,String occurrenceId,String field)throws SQLException{
        String expression=switch(field){
            case "tool_name"->"t.tool_name";case "status"->"o.status";
            case "result_success"->"CASE WHEN t.result_success IS NULL THEN 'UNKNOWN' ELSE CAST(t.result_success AS TEXT) END";
            case "output_present"->"CAST(t.result_output IS NOT NULL AS TEXT)";
            case "error_present"->"CAST(t.result_error IS NOT NULL AS TEXT)";
            case "external_outcome"->"COALESCE(o.external_outcome,'UNKNOWN')";
            case "snapshot_id"->"o.snapshot_id";default->throw new IllegalArgumentException("invalid allowlist field");};
        try(var statement=connection.prepareStatement("SELECT "+expression+" FROM side_effect_occurrences o JOIN tool_calls t ON t.task_id=o.task_id AND ((o.tool_call_id IS NOT NULL AND t.tool_call_id=o.tool_call_id) OR (o.tool_call_id IS NULL AND CAST(t.iteration AS TEXT)=o.iteration_id AND t.tool_name=o.target)) WHERE o.occurrence_id=?")){
            statement.setString(1,occurrenceId);try(var row=statement.executeQuery()){
                if(!row.next())throw new SemanticFailure("CONTEXT_STALE_SOURCE");return row.getString(1);}}
    }
    private static String stepPayload(StepExecution step,List<Evidence> evidence){return fact("PLAN_STEP",
            "source_id",step.stepRevisionId().value(),"revision",Long.toString(step.transitionVersion()),
            "state",step.state().name(),"attempt",Integer.toString(step.currentAttemptNumber()),
            "last_run",step.lastUpdatedByRunId().value(),"reason",step.boundedReason(),
            "provenance",step.provenance(),"evidence_references",evidence.stream()
                    .map(Evidence::canonicalHash).sorted().collect(java.util.stream.Collectors.joining(",")),
            "unresolved",Boolean.toString(!java.util.Set.of(PlanExecutionModels.StepState.SUCCEEDED,
                    PlanExecutionModels.StepState.SKIPPED).contains(step.state())));}
    private static String attemptPayload(Attempt attempt){return fact("ATTEMPT","source_id",attempt.safeIdentity(),
            "revision",Long.toString(attempt.transitionVersion()),"state",attempt.state().name(),
            "outcome",attempt.state().name(),"failure_class",attempt.failureClass(),
            "reason",attempt.boundedReason(),"provenance",attempt.provenance(),
            "unresolved",Boolean.toString(attempt.state()==PlanExecutionModels.AttemptState.RUNNING
                    ||attempt.state()==PlanExecutionModels.AttemptState.UNKNOWN));}
    private String evidencePayload(Evidence evidence,boolean verified){return fact("EVIDENCE",
            "source_id",evidence.canonicalHash(),"revision",evidence.createdAt().toString(),
            "step_id",evidence.stepRevisionId().value(),"attempt",Integer.toString(evidence.attemptNumber()),
            "type",evidence.kind().name(),"producer_kind",evidence.producerKind().name(),
            "producer_identity",evidence.producerIdentity(),"status",evidence.observedStatus(),
            "verified",Boolean.toString(verified),"summary",evidence.boundedSummary(),
            "protected_reference",evidence.protectedReference(),"provenance",evidence.provenance());}
    private static String completionPayload(Identity identity,PlanCompletionStore.RecoveryRead recovery){
        return completionPayload(new Owner(identity.taskId(),identity.runId(),identity.planRevision()),recovery);}
    private static String completionPayload(Owner owner,PlanCompletionStore.RecoveryRead recovery){return fact(
            "COMPLETION_GATE","source_id",owner.planRevision(),"revision",recovery.evaluationCount()+":"
                    +recovery.completed()+":"+Objects.toString(recovery.result(),"UNRESOLVED"),
            "task_id",owner.taskId(),"run_id",owner.runId(),"completed",Boolean.toString(recovery.completed()),
            "result",Objects.toString(recovery.result(),"UNRESOLVED"),"rule_count",Integer.toString(recovery.ruleCount()),
            "evaluation_count",Integer.toString(recovery.evaluationCount()),
            "unmet_items",recovery.completed()?"":Objects.toString(recovery.result(),"UNRESOLVED")
                    +":rules="+recovery.ruleCount()+":evidence="+recovery.evidence().size(),
            "unresolved",Boolean.toString(!recovery.completed()));}
    private static String artifactPayload(String producer,String artifact,String revision){return artifactPayload(
            producer,artifact,revision,artifact);}
    private static String artifactPayload(String producer,String artifact,String revision,String protectedReference){return fact(
            "ARTIFACT_REFERENCE","source_id",artifact,"revision",revision,
            "producer_identity",producer,"protected_reference",protectedReference);}
    private static String occurrenceRevision(String status,String started,String completed,String resultHash){
        return DeterministicContextCompiler.hash(status+'|'+Objects.toString(started,"")+'|'
                +Objects.toString(completed,"")+'|'+Objects.toString(resultHash,""));}
    private static boolean terminal(String status){return java.util.Set.of("COMPLETED","FAILED").contains(status);}
    private static SourceReference reference(String type,String id,String revision,String payload,int order){
        return new SourceReference(type,id,revision,DeterministicContextCompiler.hash(payload),order);}
    private static SemanticFact bare(String payload){return new SemanticFact("","","","",Scope.RUN_SCOPED,
            Compactability.NON_COMPACTABLE,LedgerFactKind.STRUCTURED_DERIVED,payload,List.of(),0);}
    private static void requirePlan(Owner owner){if(owner.planRevision()==null)
        throw new SemanticFailure("CONTEXT_STALE_SOURCE");}
    private static String fact(String kind,String...pairs){
        StringBuilder out=new StringBuilder("kind=").append(escape(kind));
        for(int i=0;i<pairs.length;i+=2)out.append(';').append(escape(pairs[i])).append('=')
                .append(escape(Objects.toString(pairs[i+1],"")));
        return out.toString();
    }
    private static String escape(String value){return Objects.toString(value,"").replace("\\","\\\\")
            .replace(";","\\;").replace("=","\\=").replace("\r","\\r").replace("\n","\\n");}

    record Owner(String taskId,String runId,String planRevision){ }
    record SemanticFact(String kind,String sourceId,String revision,String authority,Scope scope,
            Compactability compactability,LedgerFactKind factKind,String payload,
            List<SourceReference> references,long sequence){
        SemanticFact{references=List.copyOf(references);}
    }
    static final class SemanticFailure extends RuntimeException{
        SemanticFailure(String code){super(code);}SemanticFailure(String code,Throwable cause){super(code,cause);}
    }
}
