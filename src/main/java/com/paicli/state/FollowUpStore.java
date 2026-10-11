package com.paicli.state;

import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.state.FollowUpModels.*;
import com.paicli.state.InstructionManifestCandidate.Entry;
import com.paicli.state.InstructionManifestCandidate.Status;
import com.paicli.state.StructuredPlanModels.PlanApplicability;
import com.paicli.runtime.FollowUpCanonicalizer;
import com.paicli.runtime.ManifestDiffRiskClassifier;

import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** R1E persistence boundary. Child graph creation and Thread-head movement share one transaction. */
public final class FollowUpStore {
    private final Connection connection;
    private final TransactionProbe transactionProbe;

    enum AuthorityPrecedence {
        PROCEED,
        ALREADY_ADMITTED,
        ALREADY_TERMINAL,
        HEAD_CONFLICT_COMMITTED,
        STALE_VERSION,
        INTEGRITY_FAILURE,
        ILLEGAL_TRANSITION
    }

    record AuthorityResolution(AuthorityPrecedence precedence,Candidate candidate,String code){ }

    enum CandidateCoreStatus { VALID_NON_TERMINAL, VALID_TERMINAL, ADMITTED, INTEGRITY_FAILURE, ILLEGAL_STATE }
    record CandidateCoreValidation(CandidateCoreStatus status,Candidate candidate,String code){ }
    enum PreparedProfile { UNPREPARED, PREPARING_RECOVERY, RISK_CONFIRMATION, READY_TO_ADMIT,
        ADMISSION, ADMITTED_INTEGRITY, TERMINAL_AUDIT }
    enum PreparedStatus { VALID, ABSENT_VALID, INTEGRITY_FAILURE, STALE_SOURCE }
    record PreparedValidation(PreparedStatus status,PreparedRow prepared,String code){ }

    @FunctionalInterface
    interface TransactionProbe {
        void hit(String point,Candidate candidate)throws Exception;
    }

    public FollowUpStore(Connection connection)throws SQLException{
        this(connection,(point,candidate)->{ });
    }

    FollowUpStore(Connection connection,TransactionProbe transactionProbe)throws SQLException{
        this.connection=Objects.requireNonNull(connection);
        this.transactionProbe=Objects.requireNonNull(transactionProbe);
        R1aSqliteSupport.requireForeignKeys(connection);
    }

    public Optional<ParentAnchor> currentEligibleParent(CanonicalId threadId)throws SQLException{
        require(threadId,EntityFamily.THREAD);
        boolean completedHead;
        try(PreparedStatement head=connection.prepareStatement("""
                SELECT 1 FROM thread_heads h
                JOIN task_identities t ON t.task_id=h.head_task_id
                WHERE h.thread_id=? AND t.status='COMPLETED'
                """)){
            head.setString(1,threadId.value());
            try(ResultSet row=head.executeQuery()){completedHead=row.next();}
        }
        try(PreparedStatement s=connection.prepareStatement("""
                SELECT h.head_task_id,h.pointer_version,t.goal,t.status,p.owner_run_id,
                       p.active_generation_id,t.admitted_manifest_id,t.admitted_manifest_hash,
                       d.decision_hash,a.active_plan_revision_id,r.canonical_hash,t.terminal_at,
                       pa.applicability,dr.terminal_status
                FROM thread_heads h JOIN task_identities t ON t.task_id=h.head_task_id
                JOIN task_context_pointers p ON p.task_id=t.task_id
                JOIN durable_runs dr ON dr.task_id=t.task_id AND dr.run_id=p.owner_run_id
                JOIN task_plan_applicability pa ON pa.task_id=t.task_id
                LEFT JOIN task_plan_authorities a ON a.task_id=t.task_id
                LEFT JOIN task_completion_decisions d ON d.task_id=t.task_id AND d.run_id=p.owner_run_id
                    AND d.plan_revision_id=a.active_plan_revision_id
                LEFT JOIN plan_revisions r ON r.task_id=a.task_id AND r.plan_revision_id=a.active_plan_revision_id
                WHERE h.thread_id=? AND t.status='COMPLETED'
                    AND pa.applicability<>'LEGACY_PLAN_ONLY'
                """)){
            s.setString(1,threadId.value());try(ResultSet r=s.executeQuery()){
                if(!r.next()){
                    if(completedHead)throw new Conflict("COMPLETION_INTEGRITY_FAILURE");
                    return Optional.empty();
                }
                if(!"COMPLETED".equals(r.getString("status")))return Optional.empty();
                CanonicalId task=CanonicalId.parse(r.getString("head_task_id"));String decision=r.getString("decision_hash");
                if(!"COMPLETED".equals(r.getString("terminal_status")))throw new Conflict("COMPLETION_INTEGRITY_FAILURE");
                String completionAnchor=decision==null?task.value()+"|COMPLETED|"+r.getString("terminal_at"):decision;
                String plan=r.getString("active_plan_revision_id");
                PlanCompletionStore.CompletedAuthorityRead completed=null;
                if("PLAN_REQUIRED".equals(r.getString("applicability"))){
                    if(plan==null||decision==null)throw new Conflict("COMPLETION_INTEGRITY_FAILURE");
                    completed=new PlanCompletionStore(connection).validateCompletedAuthority(task,
                            CanonicalId.parse(r.getString("owner_run_id")),CanonicalId.parse(plan),decision);
                    if(!completed.validComplete())throw new Conflict("COMPLETION_INTEGRITY_FAILURE");
                }else if(r.getString("terminal_at")==null){
                    throw new Conflict("COMPLETION_INTEGRITY_FAILURE");
                }
                ParentAnchor parent=new ParentAnchor(threadId,task,r.getLong("pointer_version"),r.getString("goal"),
                        r.getString("status"),CanonicalId.parse(r.getString("owner_run_id")),
                        CanonicalId.parse(r.getString("active_generation_id")),decision==null?"TERMINAL_AUTHORITY":"COMPLETION_DECISION",
                        completionAnchor,r.getString("admitted_manifest_id"),r.getString("admitted_manifest_hash"),
                        plan==null?null:CanonicalId.parse(plan),r.getString("canonical_hash"),
                        completed==null?0:completed.evaluationNumber(),completed==null?null:completed.rulesHash(),
                        completed==null?List.of():completed.verifiedEvidenceHashes());
                if(!childOriginIntegrityValidLocked(task))
                    throw new Conflict("CAPSULE_INTEGRITY_FAILURE");
                return Optional.of(parent);
            }
        }
    }

    public Optional<String> currentHeadToken(CanonicalId threadId)throws SQLException{
        require(threadId,EntityFamily.THREAD);
        try(PreparedStatement s=connection.prepareStatement(
                "SELECT head_task_id,pointer_version FROM thread_heads WHERE thread_id=?")){
            s.setString(1,threadId.value());try(ResultSet r=s.executeQuery()){return r.next()
                    ?Optional.of(r.getString(1)+"|"+r.getLong(2)):Optional.empty();}}
    }

    public Candidate createCandidate(ParentAnchor parent,String instruction,String instructionHash,
            String provenance,String inputOwner,String session,IntentClassification classification,Instant at)throws SQLException{
        CanonicalId capsule=CanonicalId.newId(EntityFamily.CAPSULE);
        CandidateState state=switch(classification.intent()){
            case CONTINUE_CURRENT_THREAD->CandidateState.PREPARING;
            case UNKNOWN->CandidateState.NEEDS_INTENT_CONFIRMATION;
            case CREATE_NEW_THREAD,REJECT->CandidateState.CANDIDATE;
        };
        return tx(()->{
            ParentAnchor current=currentEligibleParent(parent.threadId()).orElseThrow(()->new Conflict("FOLLOW_UP_NOT_ELIGIBLE"));
            HeadObservation observed=lockAndObserveHead(parent.threadId());
            if(observed.taskId()==null||!current.taskId().equals(parent.taskId())||current.headVersion()!=parent.headVersion()
                    ||!parent.taskId().value().equals(observed.taskId())||parent.headVersion()!=observed.version())
                throw new Conflict("STALE_HEAD");
            execute("""
                    INSERT INTO followup_candidate_definitions(capsule_id,thread_id,parent_task_id,expected_head_version,
                    instruction,instruction_hash,provenance,input_owner,input_session_id,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)""",
                    capsule.value(),parent.threadId().value(),parent.taskId().value(),parent.headVersion(),instruction,instructionHash,
                    provenance,inputOwner,session,text(at));
            execute("""
                    INSERT INTO followup_intent_classification_facts(capsule_id,classification,classifier_version,
                    reason_code,classified_at) VALUES(?,?,?,?,?)""",capsule.value(),classification.intent().name(),
                    classification.version(),classification.reasonCode(),text(at));
            execute("""
                    INSERT INTO followup_candidate_authority(capsule_id,state,candidate_version,updated_at,reason_code)
                    VALUES(?,?,0,?,?)""",capsule.value(),state.name(),text(at),classification.reasonCode());
            Candidate created=readCandidateRawLocked(capsule).orElseThrow();
            if(classification.intent()==Intent.CREATE_NEW_THREAD||classification.intent()==Intent.REJECT)
                transitionCancellationLocked(created,classification.intent().name(),"R1E_DETERMINISTIC_INTENT",at);
            Candidate result=readCandidateRawLocked(capsule).orElseThrow();
            requireValidCoreLocked(result);
            return result;
        });
    }

    public Candidate confirm(CanonicalId id,ConfirmationKind kind,long version,String owner,String session,
            ConfirmationDecision decision,Instant at)throws SQLException{
        return tx(()->{
            CandidateState required=kind==ConfirmationKind.INTENT?CandidateState.NEEDS_INTENT_CONFIRMATION:CandidateState.NEEDS_RISK_CONFIRMATION;
            AuthorityResolution resolution=resolveAuthorityPrecedenceLocked(id,version,Set.of(required),true,at);
            if(resolution.precedence()!=AuthorityPrecedence.PROCEED){
                if(resolution.candidate()!=null){
                    ConfirmationDecision already=existingConfirmation(id,kind,version,owner,session);
                    if(already!=null){if(already!=decision)throw new Conflict("CONFIRMATION_DECISION_CONFLICT");
                        if(kind==ConfirmationKind.RISK&&resolution.candidate().state()==CandidateState.READY_TO_ADMIT)
                            requirePreparedLocked(validatePreparedFollowUpAuthorityLocked(
                                    id,PreparedProfile.READY_TO_ADMIT,null));
                        if(kind==ConfirmationKind.RISK&&resolution.candidate().state()==CandidateState.ADMITTED)
                            requirePreparedLocked(validatePreparedFollowUpAuthorityLocked(
                                    id,PreparedProfile.ADMITTED_INTEGRITY,null));
                        return resolution.candidate();}
                }
                return resolvedCandidateOrThrow(resolution);
            }
            Candidate before=resolution.candidate();
            ConfirmationDecision existing=existingConfirmation(id,kind,version,owner,session);
            if(existing!=null){if(existing!=decision)throw new Conflict("CONFIRMATION_DECISION_CONFLICT");return before;}
            boolean decisionMatches=kind==ConfirmationKind.INTENT
                    ?Set.of(ConfirmationDecision.CONTINUE_CURRENT_THREAD,ConfirmationDecision.CREATE_NEW_THREAD,
                            ConfirmationDecision.REJECT,ConfirmationDecision.CANCEL).contains(decision)
                    :Set.of(ConfirmationDecision.ACCEPT_RISK,ConfirmationDecision.REJECT,
                            ConfirmationDecision.CANCEL).contains(decision);
            if(!decisionMatches)throw new Conflict("CONFIRMATION_KIND_DECISION_MISMATCH");
            if(before.version()!=version||!before.inputOwner().equals(owner)||!before.inputSessionId().equals(session))
                throw new Conflict("CONFIRMATION_STALE_OR_WRONG_OWNER");
            PreparedValidation riskAuthority=null;
            if(kind==ConfirmationKind.RISK){
                riskAuthority=validatePreparedFollowUpAuthorityLocked(id,PreparedProfile.RISK_CONFIRMATION,null);
                if(riskAuthority.status()!=PreparedStatus.VALID)throw new Conflict(riskAuthority.code());
            }
            boolean accepted=kind==ConfirmationKind.INTENT&&decision==ConfirmationDecision.CONTINUE_CURRENT_THREAD
                    ||kind==ConfirmationKind.RISK&&decision==ConfirmationDecision.ACCEPT_RISK;
            int number=nextConfirmation(id,kind);
            execute("""
                    INSERT INTO followup_confirmation_facts(capsule_id,confirmation_kind,confirmation_number,
                    expected_candidate_version,input_owner,input_session_id,decision,prepared_manifest_hash,
                    prepared_risk,confirmed_at) VALUES(?,?,?,?,?,?,?,?,?,?)""",
                    id.value(),kind.name(),number,version,owner,session,decision.name(),
                    kind==ConfirmationKind.RISK?before.manifestCandidateHash():null,
                    kind==ConfirmationKind.RISK?before.risk().name():null,text(at));
            if(accepted)casState(id,required,kind==ConfirmationKind.INTENT?CandidateState.PREPARING:
                    CandidateState.READY_TO_ADMIT,version,null,null,decision.name(),at);
            else transitionCancellationLocked(before,decision.name(),"R1E_USER_CONFIRMATION",at);
            Candidate result=readCandidateRawLocked(id).orElseThrow();
            requireValidCoreLocked(result);
            if(result.state()==CandidateState.READY_TO_ADMIT)requirePreparedLocked(
                    validatePreparedFollowUpAuthorityLocked(id,PreparedProfile.READY_TO_ADMIT,null));
            return result;
        });
    }

    public Candidate persistPrepared(CanonicalId id,long version,CapsuleDefinition capsule,
            PreparedManifest prepared,Instant at)throws SQLException{
        return tx(()->{
            AuthorityResolution resolution=resolveAuthorityPrecedenceLocked(id,version,Set.of(CandidateState.PREPARING),true,at);
            if(resolution.precedence()!=AuthorityPrecedence.PROCEED)return resolvedCandidateOrThrow(resolution);
            Candidate c=resolution.candidate();
            requirePreparedLocked(validatePreparedFollowUpAuthorityLocked(id,PreparedProfile.PREPARING_RECOVERY,null));
            ParentAnchor parent=currentEligibleParent(c.threadId())
                    .orElseThrow(()->new Conflict("COMPLETION_INTEGRITY_FAILURE"));
            validatePreparedInputLocked(c,parent,capsule,prepared);
            insertCapsule(capsule);for(ProtectedInstructionArtifact artifact:prepared.artifacts())persistArtifact(artifact);
            execute("""
                    INSERT INTO child_manifest_candidates(capsule_id,instruction_manifest_id,schema_version,
                    resolution_policy_version,canonical_hash,source_fingerprint,workspace_identity,prepared_at,provenance)
                    VALUES(?,?,?,?,?,?,?,?,?)""",id.value(),prepared.manifestId(),prepared.candidate().schemaVersion(),
                    prepared.candidate().resolutionPolicyVersion(),prepared.candidate().canonicalHash(),prepared.sourceFingerprint(),
                    prepared.workspaceIdentity(),text(prepared.preparedAt()),"R1E_FRESH_MANIFEST");
            int order=0;for(Entry e:prepared.candidate().canonicalEntries())execute("""
                    INSERT INTO child_manifest_candidate_entries(capsule_id,entry_order,entry_id,source_type,source_path,scope,
                        status,applied_order,content_hash,artifact_id,parse_result,exclusion_reason) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",
                    id.value(),order++,e.id(),e.sourceType(),e.sourcePath(),e.scope(),e.status().name(),e.appliedOrder(),
                    e.contentHash(),e.artifactId(),e.parseResult(),e.exclusionReason());
            int n=1;for(ManifestDiff d:prepared.diffs())execute("""
                    INSERT INTO manifest_diff_facts(capsule_id,diff_number,
                    category,source_path,parent_hash,child_hash,bounded_summary,provenance) VALUES(?,?,?,?,?,?,?,?)""",
                    id.value(),n++,d.category().name(),d.sourcePath(),d.parentHash(),d.childHash(),d.boundedSummary(),d.provenance());
            execute("""
                    INSERT INTO followup_risk_classification_facts(capsule_id,risk,classifier_version,reason_code,classified_at)
                    VALUES(?,?,?,?,?)""",id.value(),prepared.risk().name(),"r1e-manifest-risk-v1",prepared.riskReason(),text(at));
            CandidateState next=switch(prepared.risk()){case LOW->CandidateState.READY_TO_ADMIT;
                case HIGH,UNKNOWN->CandidateState.NEEDS_RISK_CONFIRMATION;case BLOCKING->CandidateState.FAILED;};
            if(next==CandidateState.FAILED){
                setPreparedMetadataLocked(c,prepared.candidate().canonicalHash(),prepared.risk(),prepared.riskReason(),at);
                transitionFailureLocked(c,"MANIFEST_DIFF_BLOCKING","R1E_MANIFEST_RISK",at,
                        prepared.candidate().canonicalHash(),prepared.risk());
            }else{
                casState(id,CandidateState.PREPARING,next,version,prepared.candidate().canonicalHash(),
                        prepared.risk(),prepared.riskReason(),at);
                requirePreparedLocked(validatePreparedFollowUpAuthorityLocked(id,
                        next==CandidateState.NEEDS_RISK_CONFIRMATION?PreparedProfile.RISK_CONFIRMATION:
                                PreparedProfile.READY_TO_ADMIT,null));
            }
            Candidate result=readCandidateRawLocked(id).orElseThrow();
            requireValidCoreLocked(result);
            return result;
        });
    }

    public AdmissionResult admitChild(CanonicalId id,long version,String currentSourceFingerprint,
            ChildRuntimeSpec runtime,Instant at){
        try{return tx(()->admitLocked(id,version,currentSourceFingerprint,runtime,at));}
        catch(Conflict conflict){return new Rejected(conflict.code);}
        catch(SQLException e){return new Rejected("CHILD_ADMISSION_PERSISTENCE_FAILURE");}
    }

    private AdmissionResult admitLocked(CanonicalId id,long version,String source,ChildRuntimeSpec runtime,Instant at)throws SQLException{
        AuthorityResolution resolution=resolveAuthorityPrecedenceLocked(id,version,Set.of(CandidateState.READY_TO_ADMIT),true,at);
        if(resolution.precedence()==AuthorityPrecedence.ALREADY_ADMITTED){
            requirePreparedLocked(validatePreparedFollowUpAuthorityLocked(id,PreparedProfile.ADMITTED_INTEGRITY,null));
            return existing(id);
        }
        if(resolution.precedence()!=AuthorityPrecedence.PROCEED)return new Rejected(resolution.code());
        Candidate c=resolution.candidate();
        PreparedValidation authority=validatePreparedFollowUpAuthorityLocked(id,PreparedProfile.ADMISSION,source);
        if(authority.status()==PreparedStatus.STALE_SOURCE){
            transactionProbe("BEFORE_LOCAL_DISPOSITION",c);
            transitionFailureLocked(c,"MANIFEST_CANDIDATE_STALE","R1E_MANIFEST_REVALIDATION",at,
                    c.manifestCandidateHash(),c.risk());return new Rejected("MANIFEST_CANDIDATE_STALE");}
        if(authority.status()!=PreparedStatus.VALID){
            transitionFailureLocked(c,authority.code(),"R1E_CHILD_ADMISSION",at,c.manifestCandidateHash(),c.risk());
            return new Rejected(authority.code());
        }
        PreparedRow prepared=authority.prepared();
        CanonicalId child=CanonicalId.newId(EntityFamily.TASK),run=CanonicalId.newId(EntityFamily.RUN),
                generation=CanonicalId.newId(EntityFamily.GENERATION);
        insertChildCore(c,child,run,generation,prepared,runtime,at);
            execute("""
                    INSERT INTO task_continuation_edges(thread_id,parent_task_id,child_task_id,capsule_id,edge_type,created_at)
                VALUES(?,?,?,?,'CONTINUED',?)""",c.threadId().value(),c.parentTaskId().value(),child.value(),id.value(),text(at));
        String bindingHash=FollowUpSql.sha256(id.value()+"|"+child.value()+"|"+run.value()+"|"+generation.value()
                +"|"+prepared.manifestId+"|"+prepared.manifestHash);
            execute("""
                    INSERT INTO capsule_child_bindings(capsule_id,thread_id,parent_task_id,child_task_id,child_run_id,
                child_generation_id,child_manifest_id,child_manifest_hash,binding_hash,bound_at,provenance)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)""",id.value(),c.threadId().value(),c.parentTaskId().value(),child.value(),run.value(),
                generation.value(),prepared.manifestId,prepared.manifestHash,bindingHash,text(at),"R1E_ATOMIC_CHILD_ADMISSION");
            execute("""
                    INSERT INTO child_initial_run_launches(capsule_id,child_task_id,child_run_id,state,
                        launch_version,claim_attempt,updated_at)
                    VALUES(?,?,?,'START_PENDING',0,0,?)""",
                    id.value(),child.value(),run.value(),text(at));
            try(PreparedStatement s=connection.prepareStatement("""
                    UPDATE thread_heads SET head_task_id=?,pointer_version=pointer_version+1
                WHERE thread_id=? AND head_task_id=? AND pointer_version=?""")){
            bind(s,child.value(),c.threadId().value(),c.parentTaskId().value(),c.expectedHeadVersion());
            if(s.executeUpdate()!=1)throw new Conflict("THREAD_HEAD_SERIALIZATION_FAILURE");}
            execute("""
                    INSERT INTO followup_admission_facts(capsule_id,result,child_task_id,expected_head_version,
                observed_head_version,source_state,source_candidate_version,resulting_candidate_version,
                reason_code,admitted_at,provenance) VALUES(?,'ADMITTED',?,?,?,?,?,?,?,?,?)""",
                id.value(),child.value(),c.expectedHeadVersion(),c.expectedHeadVersion()+1,c.state().name(),c.version(),
                c.version()+1,"ADMITTED",text(at),"R1E_ATOMIC_CHILD_ADMISSION");
        Candidate admitted=readCandidateRawLocked(id).orElseThrow();
        requireValidCoreLocked(admitted);
        requirePreparedLocked(validatePreparedFollowUpAuthorityLocked(id,PreparedProfile.ADMITTED_INTEGRITY,null));
        return new Admitted(new ChildAuthority(child,run,generation,prepared.manifestId,prepared.manifestHash,
                c.expectedHeadVersion()+1),false);
    }

    public Optional<Candidate> findCandidate(CanonicalId id)throws SQLException{
        return tx(()->{
            if(readCandidateRawLocked(id).isEmpty())return Optional.empty();
            validateAuthorityReadLocked(id);
            return readCandidateRawLocked(id);
        });
    }

    private Optional<Candidate> readCandidateRawLocked(CanonicalId id)throws SQLException{
        require(id,EntityFamily.CAPSULE);
        try(PreparedStatement s=connection.prepareStatement("""
                SELECT d.*,a.state,a.candidate_version,a.manifest_candidate_hash,
                a.risk,a.admitted_child_task_id FROM followup_candidate_definitions d
                JOIN followup_candidate_authority a USING(capsule_id) WHERE d.capsule_id=?""")){
            s.setString(1,id.value());try(ResultSet r=s.executeQuery()){if(!r.next())return Optional.empty();
                String child=r.getString("admitted_child_task_id"),risk=r.getString("risk");
                String instruction=r.getString("instruction"),instructionHash=r.getString("instruction_hash");
                if(!instructionHash.equals(new FollowUpCanonicalizer().sha256(instruction)))
                    throw new Conflict("FOLLOW_UP_DEFINITION_INTEGRITY_FAILURE");
                return Optional.of(new Candidate(id,CanonicalId.parse(r.getString("thread_id")),CanonicalId.parse(r.getString("parent_task_id")),
                        r.getLong("expected_head_version"),instruction,instructionHash,r.getString("provenance"),
                        r.getString("input_owner"),r.getString("input_session_id"),CandidateState.valueOf(r.getString("state")),
                        r.getLong("candidate_version"),r.getString("manifest_candidate_hash"),risk==null?null:Risk.valueOf(risk),
                        child==null?null:CanonicalId.parse(child),Instant.parse(r.getString("created_at"))));}}
    }

    public Optional<ChildAuthority> findAdmittedChild(CanonicalId id)throws SQLException{
        return tx(()->findAdmittedChildLocked(id));
    }

    private Optional<ChildAuthority> findAdmittedChildLocked(CanonicalId id)throws SQLException{
        CandidateCoreValidation core=validateCandidateCoreAuthorityLocked(id);
        if(core.status()==CandidateCoreStatus.INTEGRITY_FAILURE||core.status()==CandidateCoreStatus.ILLEGAL_STATE)
            throw new Conflict(core.code());
        Candidate candidate=core.candidate();
        if(candidate==null||core.status()!=CandidateCoreStatus.ADMITTED)return Optional.empty();
        requirePreparedLocked(validatePreparedFollowUpAuthorityLocked(id,PreparedProfile.ADMITTED_INTEGRITY,null));
        return Optional.of(((Admitted)existing(id)).child());
    }

    public boolean admittedChildInitialExecutionUnclaimed(CanonicalId child)throws SQLException{
        return tx(()->admittedChildInitialExecutionUnclaimedLocked(child));
    }

    private boolean admittedChildInitialExecutionUnclaimedLocked(CanonicalId child)throws SQLException{
        require(child,EntityFamily.TASK);
        CanonicalId id=capsuleForChildLocked(child);
        if(id==null)return false;
        try{requireAdmittedAuthorityLocked(id,child);}catch(Conflict invalid){return false;}
        try(PreparedStatement s=connection.prepareStatement("""
                SELECT t.status,r.terminal_status,l.state
                FROM task_identities t JOIN capsule_child_bindings b ON b.child_task_id=t.task_id
                JOIN durable_runs r ON r.run_id=b.child_run_id AND r.task_id=t.task_id
                JOIN child_initial_run_launches l ON l.capsule_id=b.capsule_id WHERE t.task_id=?""")){
            s.setString(1,child.value());try(ResultSet r=s.executeQuery()){return r.next()
                    &&"CREATED".equals(r.getString("status"))&&r.getString("terminal_status")==null
                    &&"START_PENDING".equals(r.getString("state"));}}
    }

    public Optional<PendingChildStart> findRecoverableAdmittedChild(CanonicalId thread,Instant at)throws SQLException{
        return tx(()->findRecoverableAdmittedChildLocked(thread,at));
    }

    private Optional<PendingChildStart> findRecoverableAdmittedChildLocked(CanonicalId thread,Instant at)throws SQLException{
        require(thread,EntityFamily.THREAD);
        try(PreparedStatement s=connection.prepareStatement("""
                SELECT d.capsule_id
                FROM followup_candidate_definitions d
                JOIN followup_candidate_authority a USING(capsule_id)
                JOIN capsule_child_bindings b USING(capsule_id)
                JOIN child_initial_run_launches l USING(capsule_id)
                JOIN task_identities t ON t.task_id=b.child_task_id
                JOIN durable_runs r ON r.task_id=b.child_task_id AND r.run_id=b.child_run_id
                WHERE d.thread_id=? AND a.state='ADMITTED' AND t.status='CREATED'
                  AND r.terminal_status IS NULL AND l.state<>'START_TERMINAL'
                ORDER BY d.created_at,d.capsule_id""")){
            s.setString(1,thread.value());try(ResultSet r=s.executeQuery()){while(r.next()){
                CanonicalId id=CanonicalId.parse(r.getString("capsule_id"));
                CandidateCoreValidation core=validateCandidateCoreAuthorityLocked(id);
                if(core.status()!=CandidateCoreStatus.ADMITTED)throw new Conflict("CHILD_AUTHORITY_INTEGRITY_FAILURE");
                requirePreparedLocked(validatePreparedFollowUpAuthorityLocked(id,PreparedProfile.ADMITTED_INTEGRITY,null));
                Candidate candidate=core.candidate();
                ChildAuthority child=((Admitted)existing(id)).child();
                if(!initialChildAuthorityValid(child))throw new Conflict("CHILD_START_AUTHORITY_INVALID");
                return Optional.of(new PendingChildStart(candidate,child));
            }return Optional.empty();}}
    }

    public ChildStartResult claimInitialChildStart(ChildAuthority child,String owner,String leaseToken,
            Instant at,Instant expiresAt,Duration grace)throws SQLException{
        require(child.taskId(),EntityFamily.TASK);require(child.runId(),EntityFamily.RUN);
        if(owner==null||owner.isBlank()||leaseToken==null||leaseToken.isBlank()
                ||!expiresAt.isAfter(at)||grace==null||grace.isNegative())
            return new ChildStartResult(ChildStartStatus.CONFLICT,null);
        return tx(()->{
            if(!initialChildAuthorityValid(child))return new ChildStartResult(ChildStartStatus.AUTHORITY_INVALID,null);
            LaunchRow launch=launch(child.taskId());
            if("START_TERMINAL".equals(launch.state))return new ChildStartResult(ChildStartStatus.TERMINAL,null);
            R1aDurableStore durable=new R1aDurableStore(connection);
            R1aDurableModels.OwnershipLease lease=durable.readOwnership(child.taskId()).orElse(null);
            R1aStoreResults.OwnershipResult result;
            if("START_PENDING".equals(launch.state)){
                if(lease!=null)return new ChildStartResult(ChildStartStatus.AUTHORITY_INVALID,null);
                result=durable.acquireOwnershipInTransaction(child.taskId(),owner,child.runId(),leaseToken,at,expiresAt);
            }else{
                if(lease==null)return new ChildStartResult(ChildStartStatus.AUTHORITY_INVALID,null);
                if(!Objects.equals(launch.claimOwner,lease.ownerInstanceId())
                        ||!Objects.equals(launch.claimLeaseToken,lease.leaseToken())
                        ||!Objects.equals(launch.claimFencingToken,lease.fencingToken()))
                    return new ChildStartResult(ChildStartStatus.AUTHORITY_INVALID,null);
                if(at.isBefore(lease.expiresAt().plus(grace)))return new ChildStartResult(
                        "STARTED".equals(launch.state)?ChildStartStatus.ALREADY_STARTED:ChildStartStatus.ALREADY_CLAIMED,null);
                R1aDurableModels.TaskContextPointer pointer=durable.readContextPointer(child.taskId()).orElse(null);
                if(pointer==null)return new ChildStartResult(ChildStartStatus.AUTHORITY_INVALID,null);
                result=durable.takeoverOwnershipAndContextPointerInTransaction(child.taskId(),child.runId(),owner,leaseToken,
                        lease.casVersion(),pointer.generationId(),pointer.ownerRunId(),pointer.version(),at,expiresAt,grace);
            }
            if(result!=R1aStoreResults.OwnershipResult.SUCCESS)
                return new ChildStartResult(ChildStartStatus.CONFLICT,null);
            R1aDurableModels.OwnershipLease claimed=durable.readOwnership(child.taskId()).orElseThrow();
            String required="START_PENDING".equals(launch.state)?"START_PENDING":launch.state;
            try(PreparedStatement update=connection.prepareStatement("""
                    UPDATE child_initial_run_launches SET state='START_CLAIMED',launch_version=launch_version+1,
                        claim_attempt=claim_attempt+1,claim_owner_instance_id=?,claim_lease_token=?,
                        claim_fencing_token=?,claim_lease_version=?,claimed_at=?,started_at=NULL,
                        terminal_at=NULL,failure_reason=NULL,updated_at=?
                    WHERE child_task_id=? AND state=? AND launch_version=?""")){
                bind(update,claimed.ownerInstanceId(),claimed.leaseToken(),claimed.fencingToken(),claimed.casVersion(),
                        text(at),text(at),child.taskId().value(),required,launch.version);
                if(update.executeUpdate()!=1)throw new Conflict("CHILD_START_CLAIM_CAS_CONFLICT");}
            return new ChildStartResult(ChildStartStatus.CLAIMED,
                    new ChildStartClaim(child,claimed,launch.version+1));
        });
    }

    public boolean markInitialChildStarted(ChildStartClaim claim,Instant at)throws SQLException{
        return tx(()->{
            if(!initialChildAuthorityValid(claim.child()))return false;
            R1aDurableModels.OwnershipLease lease=claim.lease();
            if(new R1aDurableStore(connection).validateExecutionAuthority(lease.taskId(),lease.ownerInstanceId(),
                    lease.ownerRunId(),lease.leaseToken(),lease.fencingToken(),lease.casVersion(),at)
                    !=R1aStoreResults.OwnershipResult.SUCCESS)return false;
            try(PreparedStatement update=connection.prepareStatement("""
                    UPDATE child_initial_run_launches SET state='STARTED',launch_version=launch_version+1,
                        started_at=?,updated_at=? WHERE child_task_id=? AND state='START_CLAIMED'
                        AND launch_version=? AND claim_owner_instance_id=? AND claim_lease_token=?
                        AND claim_fencing_token=? AND claim_lease_version=?""")){
                bind(update,text(at),text(at),claim.child().taskId().value(),claim.launchVersion(),
                        lease.ownerInstanceId(),lease.leaseToken(),lease.fencingToken(),lease.casVersion());
                return update.executeUpdate()==1;}
        });
    }

    public boolean releaseInitialChildStartClaim(ChildStartClaim claim,String reason,Instant at)throws SQLException{
        return tx(()->{
            if(!initialChildAuthorityValid(claim.child()))return false;
            R1aDurableModels.OwnershipLease lease=claim.lease();
            try(PreparedStatement update=connection.prepareStatement("""
                    UPDATE task_ownership_leases SET expires_at=?,heartbeat_at=?,cas_version=cas_version+1
                    WHERE task_id=? AND owner_instance_id=? AND owner_run_id=? AND lease_token=?
                      AND fencing_token=? AND cas_version=? AND state='ACTIVE'""")){
                bind(update,text(at),text(at),lease.taskId().value(),lease.ownerInstanceId(),lease.ownerRunId().value(),
                        lease.leaseToken(),lease.fencingToken(),lease.casVersion());
                if(update.executeUpdate()!=1)return false;}
            try(PreparedStatement update=connection.prepareStatement("""
                    UPDATE child_initial_run_launches SET launch_version=launch_version+1,
                        claim_lease_version=claim_lease_version+1,failure_reason=?,updated_at=?
                    WHERE child_task_id=? AND state='START_CLAIMED' AND launch_version=?
                      AND claim_owner_instance_id=? AND claim_lease_token=?
                      AND claim_fencing_token=? AND claim_lease_version=?""")){
                bind(update,new com.paicli.runtime.PersistenceSanitizer().sanitizeAndTruncate(reason,128),text(at),
                        claim.child().taskId().value(),claim.launchVersion(),lease.ownerInstanceId(),lease.leaseToken(),
                        lease.fencingToken(),lease.casVersion());
                if(update.executeUpdate()!=1)throw new Conflict("CHILD_START_RELEASE_CAS_CONFLICT");}
            return true;
        });
    }

    public List<InstructionManifest.InstructionEntry> parentManifestEntries(CanonicalId parent)throws SQLException{
        try(PreparedStatement s=connection.prepareStatement("""
                SELECT e.* FROM task_identities t JOIN instruction_manifests m
                ON m.task_id=t.task_id AND m.instruction_manifest_id=t.admitted_manifest_id
                AND m.canonical_hash=t.admitted_manifest_hash
                JOIN instruction_entries e ON e.instruction_manifest_id=m.instruction_manifest_id
                WHERE t.task_id=?
                ORDER BY CASE WHEN e.applied_order IS NULL THEN 2147483647 ELSE e.applied_order END,e.entry_id""")){
            s.setString(1,parent.value());try(ResultSet r=s.executeQuery()){List<InstructionManifest.InstructionEntry> out=new ArrayList<>();
                while(r.next())out.add(new InstructionManifest.InstructionEntry(r.getString("entry_id"),r.getString("source_type"),
                        r.getString("source_path"),r.getString("scope"),Status.valueOf(r.getString("status")),nullableInt(r,"applied_order"),
                        r.getString("content_hash"),r.getString("artifact_id"),r.getString("parse_result"),r.getString("exclusion_reason"),
                        Instant.parse(r.getString("observed_at"))));return out;}}
    }

    public List<CapsuleFact> parentReferenceFacts(CanonicalId parent,
            List<String> verifiedEvidenceHashes)throws SQLException{
        List<CapsuleFact> facts=new ArrayList<>();
        Set<String> verified=new HashSet<>(verifiedEvidenceHashes);
        try(PreparedStatement s=connection.prepareStatement("""
                SELECT plan_revision_id,step_revision_id,evidence_kind,
                producer_identity,canonical_hash FROM plan_evidence WHERE task_id=? AND observed_status='SUCCEEDED'
                ORDER BY plan_revision_id,step_revision_id,evidence_kind,producer_identity""")){
            s.setString(1,parent.value());try(ResultSet r=s.executeQuery()){while(r.next()){
                if(!verified.isEmpty()&&!verified.contains(r.getString("canonical_hash")))continue;
                facts.add(new CapsuleFact(
                    FactType.EVIDENCE_REFERENCE,r.getString("step_revision_id"),r.getString("evidence_kind"),
                    r.getString("producer_identity"),r.getString("canonical_hash"),"PARENT_EVIDENCE_REFERENCE_ONLY"));}}
        }
        return List.copyOf(facts);
    }

    public Optional<Candidate> findPending(CanonicalId threadId)throws SQLException{
        require(threadId,EntityFamily.THREAD);
        List<CanonicalId> ids=snapshotPendingIds(threadId);
        Instant at=Instant.now();
        for(CanonicalId id:ids){
            Optional<Candidate> candidate=tx(()->inspectPendingCandidateLocked(threadId,id,at));
            if(candidate.isPresent())return candidate;
        }
        return Optional.empty();
    }

    /**
     * Validated, non-mutating pending Candidate read for R2 status projection.
     * Unlike findPending this method performs no cleanup, CAS, retention update, or state transition.
     */
    public Optional<Candidate> peekPending(CanonicalId threadId)throws SQLException{
        require(threadId,EntityFamily.THREAD);
        for(CanonicalId id:snapshotPendingIds(threadId)){
            CandidateCoreValidation core=validateCandidateCoreAuthorityLocked(id);
            if(core.status()==CandidateCoreStatus.INTEGRITY_FAILURE
                    ||core.status()==CandidateCoreStatus.ILLEGAL_STATE)
                throw new Conflict(core.code());
            Candidate candidate=core.candidate();
            if(candidate==null||!candidate.threadId().equals(threadId))
                throw new Conflict("FOLLOW_UP_CORE_INTEGRITY_FAILURE");
            PreparedProfile profile=switch(candidate.state()){
                case CANDIDATE,NEEDS_INTENT_CONFIRMATION->PreparedProfile.UNPREPARED;
                case PREPARING->PreparedProfile.PREPARING_RECOVERY;
                case NEEDS_RISK_CONFIRMATION->PreparedProfile.RISK_CONFIRMATION;
                case READY_TO_ADMIT->PreparedProfile.READY_TO_ADMIT;
                default->throw new Conflict("ILLEGAL_CANDIDATE_TRANSITION");
            };
            requirePreparedLocked(validatePreparedFollowUpAuthorityLocked(id,profile,null));
            ParentAnchor parent=currentEligibleParent(threadId)
                    .orElseThrow(()->new Conflict("COMPLETION_INTEGRITY_FAILURE"));
            if(!parent.taskId().equals(candidate.parentTaskId())
                    ||parent.headVersion()!=candidate.expectedHeadVersion())
                throw new Conflict("STALE_HEAD");
            return Optional.of(candidate);
        }
        return Optional.empty();
    }

    /** Validated, non-mutating Child Initial Run read for R2 status projection. */
    public Optional<PendingChildStart> peekRecoverableAdmittedChild(
            CanonicalId thread,Instant at)throws SQLException{
        return findRecoverableAdmittedChildLocked(thread,at);
    }

    private List<CanonicalId> snapshotPendingIds(CanonicalId threadId)throws SQLException{
        synchronized(connection){
            List<CanonicalId> ids=new ArrayList<>();try(PreparedStatement s=connection.prepareStatement("""
                SELECT d.capsule_id FROM followup_candidate_definitions d
                JOIN followup_candidate_authority a USING(capsule_id) WHERE d.thread_id=?
                AND a.state IN ('CANDIDATE','NEEDS_INTENT_CONFIRMATION','PREPARING','NEEDS_RISK_CONFIRMATION','READY_TO_ADMIT')
                ORDER BY d.created_at,d.capsule_id""")){
                s.setString(1,threadId.value());try(ResultSet r=s.executeQuery()){
                    while(r.next())ids.add(CanonicalId.parse(r.getString(1)));}}
            return List.copyOf(ids);
        }
    }

    private Optional<Candidate> inspectPendingCandidateLocked(CanonicalId threadId,CanonicalId id,Instant at)throws SQLException{
        Candidate snapshot=readCandidateRawLocked(id).orElseThrow(()->new Conflict("FOLLOW_UP_CORE_INTEGRITY_FAILURE"));
        if(!snapshot.threadId().equals(threadId))throw new Conflict("FOLLOW_UP_CORE_INTEGRITY_FAILURE");
        AuthorityResolution resolution=resolveAuthorityPrecedenceLocked(id,snapshot.version(),Set.of(snapshot.state()),true,at);
        if(resolution.precedence()==AuthorityPrecedence.HEAD_CONFLICT_COMMITTED
                ||resolution.precedence()==AuthorityPrecedence.ALREADY_TERMINAL
                ||resolution.precedence()==AuthorityPrecedence.ALREADY_ADMITTED)return Optional.empty();
        if(resolution.precedence()!=AuthorityPrecedence.PROCEED)throw new Conflict(resolution.code());
        Candidate candidate=resolution.candidate();
        PreparedProfile profile=switch(candidate.state()){
            case CANDIDATE,NEEDS_INTENT_CONFIRMATION->PreparedProfile.UNPREPARED;
            case PREPARING->PreparedProfile.PREPARING_RECOVERY;
            case NEEDS_RISK_CONFIRMATION->PreparedProfile.RISK_CONFIRMATION;
            case READY_TO_ADMIT->PreparedProfile.READY_TO_ADMIT;
            default->throw new Conflict("ILLEGAL_CANDIDATE_TRANSITION");
        };
        PreparedValidation preparedAuthority=validatePreparedFollowUpAuthorityLocked(id,profile,null);
        if(preparedAuthority.status()==PreparedStatus.INTEGRITY_FAILURE
                ||preparedAuthority.status()==PreparedStatus.STALE_SOURCE){
            transitionFailureLocked(candidate,preparedAuthority.code(),"R1E_PENDING_INTEGRITY",at,
                    candidate.manifestCandidateHash(),candidate.risk());
            return Optional.empty();
        }
        requirePreparedLocked(preparedAuthority);
        return Optional.of(candidate);
    }

    public String manifestDiffPreview(CanonicalId id)throws SQLException{
        return tx(()->{
            validateAuthorityReadLocked(id);
            try(PreparedStatement s=connection.prepareStatement("""
                    SELECT category,source_path FROM manifest_diff_facts
                    WHERE capsule_id=? AND category<>'UNCHANGED' ORDER BY diff_number LIMIT 8""")){
                s.setString(1,id.value());try(ResultSet r=s.executeQuery()){StringBuilder out=new StringBuilder();
                    while(r.next()){if(!out.isEmpty())out.append(", ");out.append(r.getString(1)).append(':').append(r.getString(2));}
                    String value=out.isEmpty()?"No authority-changing Manifest diff.":out.toString();
                    return value.length()<=512?value:value.substring(0,512);}}
        });
    }

    private boolean capsuleIntegrityValid(CanonicalId id)throws SQLException{
        try(PreparedStatement s=connection.prepareStatement("""
                SELECT c.*,t.goal,t.status,t.terminal_at FROM continuation_capsules c
                JOIN task_identities t ON t.task_id=c.parent_task_id WHERE c.capsule_id=?""")){
            s.setString(1,id.value());try(ResultSet r=s.executeQuery()){if(!r.next())return false;
                String plan=r.getString("parent_plan_revision_id");
                PlanCompletionStore.CompletedAuthorityRead completed=null;
                if("COMPLETION_DECISION".equals(r.getString("completion_anchor_type"))){
                    if(plan==null)return false;
                    completed=new PlanCompletionStore(connection).validateCompletedAuthority(
                            CanonicalId.parse(r.getString("parent_task_id")),
                            CanonicalId.parse(r.getString("parent_run_id")),CanonicalId.parse(plan),
                            r.getString("completion_anchor_id"));
                    if(!completed.validComplete())return false;
                }
                ParentAnchor anchor=new ParentAnchor(
                        CanonicalId.parse(r.getString("thread_id")),CanonicalId.parse(r.getString("parent_task_id")),0,
                        r.getString("goal"),r.getString("status"),CanonicalId.parse(r.getString("parent_run_id")),
                        CanonicalId.parse(r.getString("parent_generation_id")),r.getString("completion_anchor_type"),
                        r.getString("completion_anchor_id"),r.getString("parent_manifest_id"),r.getString("parent_manifest_hash"),
                        plan==null?null:CanonicalId.parse(plan),r.getString("parent_plan_hash"),
                        completed==null?0:completed.evaluationNumber(),completed==null?null:completed.rulesHash(),
                        completed==null?List.of():completed.verifiedEvidenceHashes());
                List<CapsuleFact> facts=new ArrayList<>();try(PreparedStatement f=connection.prepareStatement("""
                        SELECT * FROM continuation_capsule_facts WHERE capsule_id=? ORDER BY fact_number""")){
                    f.setString(1,id.value());try(ResultSet x=f.executeQuery()){while(x.next())facts.add(new CapsuleFact(
                            FactType.valueOf(x.getString("fact_type")),x.getString("scope_identity"),x.getString("bounded_payload"),
                            x.getString("reference_identity"),x.getString("reference_hash"),x.getString("provenance")));}}
                try{return r.getInt("schema_version")==FollowUpCanonicalizer.SCHEMA_VERSION
                        &&(completed!=null||completionAnchorValid(r))
                        &&r.getString("canonical_hash").equals(new FollowUpCanonicalizer().hash(anchor,facts));}
                catch(RuntimeException invalid){return false;}}}
    }

    private boolean downstreamCapsuleIntegrityValid(CanonicalId id)throws SQLException{
        try{return capsuleIntegrityValid(id);}
        catch(RuntimeException corrupt){return false;}
    }

    private boolean completionAnchorValid(ResultSet capsule)throws SQLException{
        String task=capsule.getString("parent_task_id"),anchor=capsule.getString("completion_anchor_id");
        if("COMPLETION_DECISION".equals(capsule.getString("completion_anchor_type")))return false;
        String terminalAt=capsule.getString("terminal_at");if(terminalAt==null||!"COMPLETED".equals(capsule.getString("status")))return false;
        try(PreparedStatement s=connection.prepareStatement(
                "SELECT 1 FROM durable_runs WHERE task_id=? AND run_id=? AND terminal_status='COMPLETED'")){
            bind(s,task,capsule.getString("parent_run_id"));try(ResultSet r=s.executeQuery()){
                return r.next()&&(task+"|COMPLETED|"+terminalAt).equals(anchor);}}
    }

    /** Retention changes disposition only; admitted Capsule/edge/binding/facts remain authoritative. */
    public void markPayloadRetained(CanonicalId id)throws SQLException{
        tx(()->{
            CandidateCoreValidation core=validateCandidateCoreAuthorityLocked(id);
            if(core.status()!=CandidateCoreStatus.VALID_TERMINAL)
                throw new Conflict("RETENTION_AUTHORITY_REQUIRED");
            requirePreparedLocked(validatePreparedFollowUpAuthorityLocked(id,PreparedProfile.TERMINAL_AUDIT,null));
            execute("""
                    UPDATE followup_candidate_authority SET retention_disposition='PAYLOAD_TRIMMED',
                    updated_at=updated_at WHERE capsule_id=? AND state=? AND candidate_version=?""",
                    id.value(),core.candidate().state().name(),core.candidate().version());
            return null;
        });
    }

    public Candidate failStaleManifest(CanonicalId id,long version,Instant at)throws SQLException{
        return tx(()->{
            AuthorityResolution resolution=resolveAuthorityPrecedenceLocked(id,version,
                    Set.of(CandidateState.READY_TO_ADMIT),true,at);
            if(resolution.precedence()!=AuthorityPrecedence.PROCEED)return resolvedCandidateOrThrow(resolution);
            Candidate c=resolution.candidate();
            requirePreparedLocked(validatePreparedFollowUpAuthorityLocked(id,PreparedProfile.READY_TO_ADMIT,null));
            transactionProbe("BEFORE_LOCAL_DISPOSITION",c);
            transitionFailureLocked(c,"MANIFEST_CANDIDATE_STALE","R1E_MANIFEST_REVALIDATION",at,
                    c.manifestCandidateHash(),c.risk());
            return findCandidate(id).orElseThrow();
        });
    }

    public String childCapsuleContext(CanonicalId child)throws SQLException{
        return tx(()->{
            CanonicalId id=capsuleForChildLocked(child);
            if(id==null)return "";
            requireAdmittedAuthorityLocked(id,child);
            try(PreparedStatement s=connection.prepareStatement("""
                    SELECT f.fact_type,f.bounded_payload,f.reference_identity,
                    f.reference_hash FROM capsule_child_bindings b JOIN continuation_capsule_facts f USING(capsule_id)
                    WHERE b.child_task_id=? ORDER BY f.fact_number LIMIT 32""")){
                s.setString(1,child.value());try(ResultSet r=s.executeQuery()){StringBuilder out=new StringBuilder();
                    while(r.next()&&out.length()<16_384){out.append("- ").append(r.getString(1)).append(": ").append(r.getString(2));
                        String reference=r.getString(3);if(reference!=null)out.append(" [ref=").append(reference).append(']');
                        String hash=r.getString(4);if(hash!=null)out.append(" [sha256=").append(hash).append(']');out.append('\n');}
                    return out.length()>16_384?out.substring(0,16_384):out.toString();}}
        });
    }

    public boolean childOriginIntegrityValid(CanonicalId child)throws SQLException{
        return tx(()->childOriginIntegrityValidLocked(child));
    }

    /** Non-mutating scoped integrity read; safe inside an existing read snapshot. */
    public boolean peekChildOriginIntegrity(CanonicalId child)throws SQLException{
        return childOriginIntegrityValidLocked(child);
    }

    private boolean childOriginIntegrityValidLocked(CanonicalId child)throws SQLException{
        CanonicalId id=capsuleForChildLocked(child);
        if(id==null)return rootOriginWithoutContinuationBindingLocked(child);
        try{requireAdmittedAuthorityLocked(id,child);return true;}
        catch(Conflict invalid){return false;}
    }

    private boolean rootOriginWithoutContinuationBindingLocked(CanonicalId task)throws SQLException{
        try(PreparedStatement s=connection.prepareStatement(
                "SELECT parent_task_id FROM task_identities WHERE task_id=?")){
            s.setString(1,task.value());try(ResultSet r=s.executeQuery()){
                if(!r.next()||r.getString(1)!=null)return false;
                return !r.next();
            }
        }
    }

    private boolean admittedGraphIntegrityValidLocked(CanonicalId child)throws SQLException{
        try(PreparedStatement s=connection.prepareStatement("""
                SELECT b.*,t.thread_id AS task_thread_id,
                t.parent_task_id AS task_parent_task_id,
                t.admitted_manifest_id,t.admitted_manifest_hash,p.active_generation_id,p.owner_run_id
                FROM capsule_child_bindings b JOIN task_identities t ON t.task_id=b.child_task_id
                JOIN task_context_pointers p ON p.task_id=t.task_id WHERE b.child_task_id=?""")){
            s.setString(1,child.value());try(ResultSet r=s.executeQuery()){if(!r.next())return false;
                String expected=FollowUpSql.sha256(r.getString("capsule_id")+"|"+child.value()+"|"+r.getString("child_run_id")
                        +"|"+r.getString("child_generation_id")+"|"+r.getString("child_manifest_id")+"|"+r.getString("child_manifest_hash"));
                return expected.equals(r.getString("binding_hash"))
                        &&Objects.equals(r.getString("thread_id"),r.getString("task_thread_id"))
                        &&Objects.equals(r.getString("parent_task_id"),r.getString("task_parent_task_id"))
                        &&Objects.equals(r.getString("admitted_manifest_id"),r.getString("child_manifest_id"))
                        &&Objects.equals(r.getString("admitted_manifest_hash"),r.getString("child_manifest_hash"))
                        &&Objects.equals(r.getString("active_generation_id"),r.getString("child_generation_id"))
                        &&Objects.equals(r.getString("owner_run_id"),r.getString("child_run_id"));}}
    }

    public record ChildRuntimeSpec(String goal,String provider,String model,String approvalMode,int maxIterations,
            String verificationCommand,int maxRepairAttempts,String skillsHash,String hooksHash,String runtimeContractJson,
            PlanApplicability planApplicability){ }

    private void insertChildCore(Candidate c,CanonicalId child,CanonicalId run,CanonicalId generation,
            PreparedRow p,ChildRuntimeSpec runtime,Instant at)throws SQLException{
        execute("""
                INSERT INTO task_identities(task_id,thread_id,parent_task_id,goal,status,created_at)
                VALUES(?,?,?,?,'CREATED',?)""",child.value(),c.threadId().value(),c.parentTaskId().value(),c.instruction(),text(at));
        execute("""
                INSERT INTO instruction_manifests(instruction_manifest_id,task_id,schema_version,resolution_policy_version,
                canonical_hash,state,created_at) VALUES(?,?,?,?,?,'COMMITTED',?)""",p.manifestId,child.value(),p.schemaVersion,p.policy,p.manifestHash,text(at));
        for(EntryRow e:p.entries)execute("""
                INSERT INTO instruction_entries(entry_id,instruction_manifest_id,source_type,source_path,
                scope,status,applied_order,content_hash,artifact_id,parse_result,exclusion_reason,observed_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",e.id,p.manifestId,e.sourceType,e.sourcePath,e.scope,e.status,e.appliedOrder,
                e.contentHash,e.artifactId,e.parseResult,e.exclusionReason,text(at));
        execute("UPDATE task_identities SET admitted_manifest_id=?,admitted_manifest_hash=? WHERE task_id=?",
                p.manifestId,p.manifestHash,child.value());
        execute("""
                INSERT INTO durable_runs(run_id,task_id,thread_id,run_kind,instruction_manifest_id,instruction_manifest_hash,created_at)
                VALUES(?,?,?,'INITIAL',?,?,?)""",run.value(),child.value(),c.threadId().value(),p.manifestId,p.manifestHash,text(at));
        execute("INSERT INTO task_plan_applicability(task_id,applicability,declared_by_run_id,declared_at) VALUES(?,?,?,?)",
                child.value(),runtime.planApplicability().name(),run.value(),text(at));
        execute("""
                INSERT INTO task_context_generations(generation_id,task_id,created_by_run_id,instruction_manifest_id,
                instruction_manifest_hash,state,token_metadata,created_at,committed_at) VALUES(?,?,?,?,?,'COMMITTED',?,?,?)""",
                generation.value(),child.value(),run.value(),p.manifestId,p.manifestHash,runtime.runtimeContractJson(),text(at),text(at));
        execute("INSERT INTO task_context_pointers(task_id,active_generation_id,owner_run_id,pointer_version) VALUES(?,?,?,0)",
                child.value(),generation.value(),run.value());
        execute("""
                INSERT INTO tasks(task_id,goal,status,provider,model,approval_mode,iterations_completed,max_iterations)
                VALUES(?,?,'RUNNING',?,?,?,?,?)""",child.value(),runtime.goal(),runtime.provider(),runtime.model(),runtime.approvalMode(),0,runtime.maxIterations());
        execute("""
                INSERT INTO coding_runs(run_id,task_id,run_kind,phase,verification_command,max_repair_attempts,
                started_iteration,last_iteration,skills_manifest_sha256,hooks_manifest_sha256)
                VALUES(?,?,'INITIAL','PLANNING',?,?,0,0,?,?)""",run.value(),child.value(),runtime.verificationCommand(),
                runtime.maxRepairAttempts(),runtime.skillsHash(),runtime.hooksHash());
    }

    private void insertCapsule(CapsuleDefinition c)throws SQLException{
        execute("""
                INSERT INTO continuation_capsules(capsule_id,schema_version,thread_id,parent_task_id,parent_run_id,
                parent_generation_id,completion_anchor_type,completion_anchor_id,parent_manifest_id,parent_manifest_hash,
                parent_plan_revision_id,parent_plan_hash,canonical_hash,created_at,provenance) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                c.id().value(),c.schemaVersion(),c.threadId().value(),c.parentTaskId().value(),c.parentRunId().value(),
                c.parentGenerationId().value(),c.completionAnchorType(),c.completionAnchorId(),c.parentManifestId(),
                c.parentManifestHash(),c.parentPlanRevisionId()==null?null:c.parentPlanRevisionId().value(),c.parentPlanHash(),
                c.canonicalHash(),text(c.createdAt()),"R1E_DURABLE_TRUTH");
        int n=1;for(CapsuleFact f:c.facts())execute("""
                INSERT INTO continuation_capsule_facts(capsule_id,fact_number,
                fact_type,scope_identity,bounded_payload,reference_identity,reference_hash,provenance) VALUES(?,?,?,?,?,?,?,?)""",
                c.id().value(),n++,f.type().name(),f.scopeIdentity(),f.boundedPayload(),f.referenceIdentity(),f.referenceHash(),f.provenance());
    }

    private PreparedRow prepared(CanonicalId id)throws SQLException{
        try(PreparedStatement s=connection.prepareStatement("SELECT * FROM child_manifest_candidates WHERE capsule_id=?")){
            s.setString(1,id.value());try(ResultSet r=s.executeQuery()){if(!r.next())throw new Conflict("MANIFEST_CANDIDATE_MISSING");
                List<EntryRow> entries=new ArrayList<>();try(PreparedStatement e=connection.prepareStatement(
                        "SELECT * FROM child_manifest_candidate_entries WHERE capsule_id=? ORDER BY entry_order")){
                    e.setString(1,id.value());try(ResultSet x=e.executeQuery()){while(x.next())entries.add(new EntryRow(x.getString("entry_id"),
                            x.getString("source_type"),x.getString("source_path"),x.getString("scope"),x.getString("status"),
                            nullableInt(x,"applied_order"),x.getString("content_hash"),x.getString("artifact_id"),
                            x.getString("parse_result"),x.getString("exclusion_reason")));}}
                return new PreparedRow(r.getString("instruction_manifest_id"),r.getInt("schema_version"),
                        r.getString("resolution_policy_version"),r.getString("canonical_hash"),r.getString("source_fingerprint"),
                        r.getString("workspace_identity"),entries);}}
    }

    private boolean manifestCandidateIntegrityValid(PreparedRow prepared){
        try{List<Entry> entries=prepared.entries.stream().map(e->new Entry(e.id,e.sourceType,e.sourcePath,e.scope,
                    Status.valueOf(e.status),e.appliedOrder,e.contentHash,e.artifactId,e.parseResult,e.exclusionReason,null)).toList();
            InstructionManifestCandidate candidate=new InstructionManifestCandidate(prepared.schemaVersion,prepared.policy,entries);
            candidate.validateForCommit();
            List<Entry> canonical=candidate.canonicalEntries();
            if(canonical.size()!=prepared.entries.size())return false;
            for(int i=0;i<canonical.size();i++)if(!canonical.get(i).id().equals(prepared.entries.get(i).id))return false;
            return prepared.manifestHash.equals(candidate.canonicalHash());}
        catch(RuntimeException invalid){return false;}
    }
    private boolean preparedArtifactsIntegrityValid(PreparedRow prepared)throws SQLException{
        for(EntryRow entry:prepared.entries){if(!"APPLIED".equals(entry.status))continue;
        try(PreparedStatement s=connection.prepareStatement("""
                SELECT content_hash,protected_payload,storage_reference,
                    integrity_status FROM protected_instruction_artifacts WHERE artifact_id=?""")){
                s.setString(1,entry.artifactId);try(ResultSet r=s.executeQuery()){if(!r.next()
                        ||!entry.contentHash.equals(r.getString("content_hash"))
                        ||!"VERIFIED".equals(r.getString("integrity_status")))return false;
                    byte[] payload=r.getBytes("protected_payload");
                    if(payload==null||r.getString("storage_reference")!=null
                            ||!entry.contentHash.equals(ProtectedInstructionArtifact.sha256(payload)))return false;}}
        }return true;
    }

    private PreparedValidation validatePreparedFollowUpAuthorityLocked(CanonicalId id,PreparedProfile profile,
            String freshSourceFingerprint)throws SQLException{
        try{return validatePreparedFollowUpAuthorityUncheckedLocked(id,profile,freshSourceFingerprint);}
        catch(RuntimeException corrupt){return invalidPrepared("PREPARED_AUTHORITY_INTEGRITY_FAILURE");}
    }

    private PreparedValidation validatePreparedFollowUpAuthorityUncheckedLocked(CanonicalId id,PreparedProfile profile,
            String freshSourceFingerprint)throws SQLException{
        CandidateCoreValidation core=validateCandidateCoreAuthorityLocked(id);
        if(core.status()==CandidateCoreStatus.INTEGRITY_FAILURE||core.status()==CandidateCoreStatus.ILLEGAL_STATE)
            return invalidPrepared("FOLLOW_UP_CORE_INTEGRITY_FAILURE");
        Candidate candidate=core.candidate();
        if(profile==PreparedProfile.TERMINAL_AUDIT){
            return isTerminal(candidate.state())?new PreparedValidation(PreparedStatus.VALID,null,"VALID"):
                    invalidPrepared("TERMINAL_AUTHORITY_REQUIRED");
        }
        if(profile==PreparedProfile.UNPREPARED||profile==PreparedProfile.PREPARING_RECOVERY){
            boolean stateValid=profile==PreparedProfile.UNPREPARED
                    ?Set.of(CandidateState.CANDIDATE,CandidateState.NEEDS_INTENT_CONFIRMATION).contains(candidate.state())
                    :candidate.state()==CandidateState.PREPARING;
            return stateValid&&preparedAuthorityRowCount(id)==0
                    ?new PreparedValidation(PreparedStatus.ABSENT_VALID,null,"ABSENT_VALID")
                    :invalidPrepared("PREPARED_AUTHORITY_STATE_MISMATCH");
        }
        CandidateState required=profile==PreparedProfile.RISK_CONFIRMATION?CandidateState.NEEDS_RISK_CONFIRMATION
                :profile==PreparedProfile.ADMITTED_INTEGRITY?CandidateState.ADMITTED:CandidateState.READY_TO_ADMIT;
        if(candidate.state()!=required)return invalidPrepared("PREPARED_AUTHORITY_STATE_MISMATCH");
        if(!downstreamCapsuleIntegrityValid(id))return invalidPrepared("CAPSULE_INTEGRITY_FAILURE");
        PreparedRow prepared;
        try{prepared=prepared(id);}catch(Conflict missing){return invalidPrepared(missing.code);}
        if(!manifestCandidateIntegrityValid(prepared)||!preparedArtifactsIntegrityValid(prepared))
            return invalidPrepared("MANIFEST_CANDIDATE_INTEGRITY_FAILURE");
        if(!Objects.equals(candidate.manifestCandidateHash(),prepared.manifestHash)
                ||candidate.risk()==null)return invalidPrepared("PREPARED_AUTHORITY_BINDING_FAILURE");
        List<ManifestDiff> persisted=manifestDiffs(id);
        List<Entry> childEntries=prepared.entries.stream().map(e->new Entry(e.id,e.sourceType,e.sourcePath,e.scope,
                Status.valueOf(e.status),e.appliedOrder,e.contentHash,e.artifactId,e.parseResult,e.exclusionReason,null)).toList();
        ManifestDiffRiskClassifier classifier=new ManifestDiffRiskClassifier();
        List<ManifestDiff> expected=classifier.diff(parentManifestEntries(candidate.parentTaskId()),childEntries);
        if(!persisted.equals(expected))return invalidPrepared("MANIFEST_DIFF_INTEGRITY_FAILURE");
        RiskFact riskFact=riskFact(id);
        ManifestDiffRiskClassifier.Assessment assessment=classifier.classify(expected);
        if(riskFact==null||riskFact.risk!=assessment.risk()
                ||!Objects.equals(riskFact.reason,assessment.reason())
                ||!"r1e-manifest-risk-v1".equals(riskFact.classifierVersion)
                ||candidate.risk()!=assessment.risk())return invalidPrepared("MANIFEST_RISK_INTEGRITY_FAILURE");
        String expectedFingerprint=FollowUpSql.sha256(prepared.workspaceIdentity+"\n"+prepared.manifestHash);
        if(!expectedFingerprint.equals(prepared.sourceFingerprint))
            return invalidPrepared("MANIFEST_SOURCE_FINGERPRINT_INTEGRITY_FAILURE");
        List<ConfirmationRow> riskConfirmations=confirmationRows(id,ConfirmationKind.RISK);
        if(profile==PreparedProfile.RISK_CONFIRMATION){
            if((candidate.risk()!=Risk.HIGH&&candidate.risk()!=Risk.UNKNOWN)||!riskConfirmations.isEmpty())
                return invalidPrepared("RISK_CONFIRMATION_AUTHORITY_MISMATCH");
        }else if(candidate.risk()==Risk.LOW){
            if(!riskConfirmations.isEmpty())return invalidPrepared("RISK_CONFIRMATION_AUTHORITY_MISMATCH");
        }else if(candidate.risk()==Risk.HIGH||candidate.risk()==Risk.UNKNOWN){
            long expectedVersion=profile==PreparedProfile.ADMITTED_INTEGRITY?candidate.version()-2:candidate.version()-1;
            if(riskConfirmations.size()!=1||!riskConfirmationValid(candidate,riskConfirmations.get(0),
                    expectedVersion,prepared.manifestHash,candidate.risk()))
                return invalidPrepared("RISK_CONFIRMATION_AUTHORITY_MISMATCH");
        }else return invalidPrepared("MANIFEST_DIFF_BLOCKING");
        if((profile==PreparedProfile.ADMISSION)&&!Objects.equals(freshSourceFingerprint,prepared.sourceFingerprint))
            return new PreparedValidation(PreparedStatus.STALE_SOURCE,prepared,"MANIFEST_CANDIDATE_STALE");
        if(profile==PreparedProfile.ADMITTED_INTEGRITY){
            if(candidate.childTaskId()==null||!admittedGraphIntegrityValidLocked(candidate.childTaskId())
                    ||!admittedPreparedBindingValidLocked(candidate,prepared))
                return invalidPrepared("CHILD_AUTHORITY_INTEGRITY_FAILURE");
        }
        return new PreparedValidation(PreparedStatus.VALID,prepared,"VALID");
    }

    private void validatePreparedInputLocked(Candidate candidate,ParentAnchor parent,CapsuleDefinition capsule,
            PreparedManifest prepared)throws SQLException{
        if(!capsule.id().equals(candidate.capsuleId())||!capsule.threadId().equals(candidate.threadId())
                ||!capsule.parentTaskId().equals(candidate.parentTaskId())
                ||!capsule.parentRunId().equals(parent.terminalRunId())
                ||!capsule.parentGenerationId().equals(parent.generationId())
                ||!Objects.equals(capsule.completionAnchorType(),parent.completionAnchorType())
                ||!Objects.equals(capsule.completionAnchorId(),parent.completionAnchorId())
                ||!Objects.equals(capsule.parentManifestId(),parent.manifestId())
                ||!Objects.equals(capsule.parentManifestHash(),parent.manifestHash())
                ||!Objects.equals(capsule.parentPlanRevisionId(),parent.planRevisionId())
                ||!Objects.equals(capsule.parentPlanHash(),parent.planHash()))
            throw new Conflict("CAPSULE_SCOPE_MISMATCH");
        FollowUpCanonicalizer canonicalizer=new FollowUpCanonicalizer();
        List<CapsuleFact> canonicalFacts=canonicalizer.canonicalFacts(capsule.facts());
        if(!canonicalFacts.equals(capsule.facts())
                ||!canonicalizer.hash(parent,canonicalFacts).equals(capsule.canonicalHash()))
            throw new Conflict("CAPSULE_INTEGRITY_FAILURE");
        prepared.candidate().validateForCommit();
        String manifestHash=prepared.candidate().canonicalHash();
        if(!prepared.manifestId().equals("manifest-"+candidate.capsuleId().value())
                ||!manifestHash.matches("[0-9a-f]{64}")
                ||!FollowUpSql.sha256(prepared.workspaceIdentity()+"\n"+manifestHash)
                    .equals(prepared.sourceFingerprint()))
            throw new Conflict("MANIFEST_CANDIDATE_INTEGRITY_FAILURE");
        Map<String,ProtectedInstructionArtifact> artifacts=new HashMap<>();
        for(ProtectedInstructionArtifact artifact:prepared.artifacts()){
            if(artifacts.put(artifact.id(),artifact)!=null||!artifact.verifyIntegrity())
                throw new Conflict("ARTIFACT_INTEGRITY_FAILURE");
        }
        Set<String> requiredArtifacts=new HashSet<>();
        for(Entry entry:prepared.candidate().entries())if(entry.status()==Status.APPLIED){
            ProtectedInstructionArtifact artifact=artifacts.get(entry.artifactId());
            if(artifact==null||!requiredArtifacts.add(entry.artifactId())
                    ||!entry.contentHash().equals(artifact.contentHash()))
                throw new Conflict("ARTIFACT_CLOSURE_INTEGRITY_FAILURE");
        }
        if(requiredArtifacts.size()!=artifacts.size())throw new Conflict("ARTIFACT_CLOSURE_INTEGRITY_FAILURE");
        ManifestDiffRiskClassifier classifier=new ManifestDiffRiskClassifier();
        List<ManifestDiff> expected=classifier.diff(parentManifestEntries(candidate.parentTaskId()),
                prepared.candidate().entries());
        ManifestDiffRiskClassifier.Assessment assessment=classifier.classify(expected);
        if(!expected.equals(prepared.diffs())||assessment.risk()!=prepared.risk()
                ||!Objects.equals(assessment.reason(),prepared.riskReason()))
            throw new Conflict("MANIFEST_DIFF_RISK_INTEGRITY_FAILURE");
    }

    private List<ManifestDiff> manifestDiffs(CanonicalId id)throws SQLException{
        List<ManifestDiff> diffs=new ArrayList<>();
        try(PreparedStatement s=connection.prepareStatement("""
                SELECT category,source_path,parent_hash,child_hash,bounded_summary,provenance
                FROM manifest_diff_facts WHERE capsule_id=? ORDER BY diff_number""")){
            s.setString(1,id.value());try(ResultSet r=s.executeQuery()){while(r.next())diffs.add(new ManifestDiff(
                    DiffCategory.valueOf(r.getString(1)),r.getString(2),r.getString(3),r.getString(4),
                    r.getString(5),r.getString(6)));}}
        return List.copyOf(diffs);
    }

    private RiskFact riskFact(CanonicalId id)throws SQLException{
        try(PreparedStatement s=connection.prepareStatement("""
                SELECT risk,classifier_version,reason_code FROM followup_risk_classification_facts WHERE capsule_id=?""")){
            s.setString(1,id.value());try(ResultSet r=s.executeQuery()){return r.next()
                    ?new RiskFact(Risk.valueOf(r.getString(1)),r.getString(2),r.getString(3)):null;}}
    }

    private int preparedAuthorityRowCount(CanonicalId id)throws SQLException{
        return count("""
                SELECT (SELECT count(*) FROM continuation_capsules WHERE capsule_id=?)
                     + (SELECT count(*) FROM continuation_capsule_facts WHERE capsule_id=?)
                     + (SELECT count(*) FROM child_manifest_candidates WHERE capsule_id=?)
                     + (SELECT count(*) FROM child_manifest_candidate_entries WHERE capsule_id=?)
                     + (SELECT count(*) FROM manifest_diff_facts WHERE capsule_id=?)
                     + (SELECT count(*) FROM followup_risk_classification_facts WHERE capsule_id=?)
                     + (SELECT count(*) FROM followup_confirmation_facts
                        WHERE capsule_id=? AND confirmation_kind='RISK')""",
                id.value(),id.value(),id.value(),id.value(),id.value(),id.value(),id.value());
    }

    private boolean riskConfirmationValid(Candidate candidate,ConfirmationRow confirmation,long expectedVersion,
            String manifestHash,Risk risk){
        return confirmation.decision==ConfirmationDecision.ACCEPT_RISK
                &&confirmation.expectedVersion==expectedVersion
                &&candidate.inputOwner().equals(confirmation.owner)
                &&candidate.inputSessionId().equals(confirmation.session)
                &&Objects.equals(manifestHash,confirmation.preparedManifestHash)
                &&risk==confirmation.preparedRisk;
    }

    private PreparedValidation invalidPrepared(String code){
        return new PreparedValidation(PreparedStatus.INTEGRITY_FAILURE,null,code);
    }
    private void requirePreparedLocked(PreparedValidation validation){
        if(validation.status()!=PreparedStatus.VALID&&validation.status()!=PreparedStatus.ABSENT_VALID)
            throw new Conflict(validation.code());
    }

    private void validateAuthorityReadLocked(CanonicalId id)throws SQLException{
        CandidateCoreValidation core=validateCandidateCoreAuthorityLocked(id);
        if(core.status()==CandidateCoreStatus.INTEGRITY_FAILURE||core.status()==CandidateCoreStatus.ILLEGAL_STATE)
            throw new Conflict(core.code());
        Candidate candidate=core.candidate();
        if(core.status()==CandidateCoreStatus.ADMITTED){
            requirePreparedLocked(validatePreparedFollowUpAuthorityLocked(id,PreparedProfile.ADMITTED_INTEGRITY,null));
            return;
        }
        if(core.status()==CandidateCoreStatus.VALID_TERMINAL){
            requirePreparedLocked(validatePreparedFollowUpAuthorityLocked(id,PreparedProfile.TERMINAL_AUDIT,null));
            return;
        }
        AuthorityResolution resolution=resolveAuthorityPrecedenceLocked(id,candidate.version(),Set.of(candidate.state()),true,
                Instant.now());
        if(resolution.precedence()!=AuthorityPrecedence.PROCEED){
            resolvedCandidateOrThrow(resolution);
            return;
        }
        PreparedProfile profile=switch(candidate.state()){
            case CANDIDATE,NEEDS_INTENT_CONFIRMATION->PreparedProfile.UNPREPARED;
            case PREPARING->PreparedProfile.PREPARING_RECOVERY;
            case NEEDS_RISK_CONFIRMATION->PreparedProfile.RISK_CONFIRMATION;
            case READY_TO_ADMIT->PreparedProfile.READY_TO_ADMIT;
            default->throw new Conflict("ILLEGAL_CANDIDATE_TRANSITION");
        };
        requirePreparedLocked(validatePreparedFollowUpAuthorityLocked(id,profile,null));
    }

    private void requireAdmittedAuthorityLocked(CanonicalId id,CanonicalId child)throws SQLException{
        CandidateCoreValidation core=validateCandidateCoreAuthorityLocked(id);
        if(core.status()!=CandidateCoreStatus.ADMITTED||core.candidate().childTaskId()==null
                ||!core.candidate().childTaskId().equals(child))throw new Conflict("CHILD_AUTHORITY_INTEGRITY_FAILURE");
        requirePreparedLocked(validatePreparedFollowUpAuthorityLocked(id,PreparedProfile.ADMITTED_INTEGRITY,null));
    }

    private CanonicalId capsuleForChildLocked(CanonicalId child)throws SQLException{
        require(child,EntityFamily.TASK);
        try(PreparedStatement s=connection.prepareStatement(
                "SELECT capsule_id FROM capsule_child_bindings WHERE child_task_id=?")){
            s.setString(1,child.value());try(ResultSet r=s.executeQuery()){
                if(!r.next())return null;
                CanonicalId id=CanonicalId.parse(r.getString(1));
                if(r.next())throw new Conflict("CHILD_AUTHORITY_INTEGRITY_FAILURE");
                return id;
            }
        }
    }

    private boolean admittedPreparedBindingValidLocked(Candidate candidate,PreparedRow prepared)throws SQLException{
        try(PreparedStatement s=connection.prepareStatement("""
                SELECT b.child_manifest_id,b.child_manifest_hash,m.schema_version,m.resolution_policy_version,
                    m.canonical_hash
                FROM capsule_child_bindings b
                JOIN instruction_manifests m ON m.task_id=b.child_task_id
                    AND m.instruction_manifest_id=b.child_manifest_id AND m.canonical_hash=b.child_manifest_hash
                WHERE b.capsule_id=? AND b.child_task_id=?""")){
            bind(s,candidate.capsuleId().value(),candidate.childTaskId().value());
            try(ResultSet r=s.executeQuery()){if(!r.next())return false;
                boolean metadata=Objects.equals(prepared.manifestId,r.getString("child_manifest_id"))
                        &&Objects.equals(prepared.manifestHash,r.getString("child_manifest_hash"))
                        &&prepared.schemaVersion==r.getInt("schema_version")
                        &&Objects.equals(prepared.policy,r.getString("resolution_policy_version"))&&!r.next();
                return metadata&&committedManifestEntriesMatchLocked(prepared);}}
    }

    private boolean committedManifestEntriesMatchLocked(PreparedRow prepared)throws SQLException{
        List<EntryRow> committed=new ArrayList<>();
        try(PreparedStatement s=connection.prepareStatement("""
                SELECT entry_id,source_type,source_path,scope,status,applied_order,content_hash,artifact_id,
                    parse_result,exclusion_reason FROM instruction_entries WHERE instruction_manifest_id=?
                ORDER BY CASE WHEN applied_order IS NULL THEN 2147483647 ELSE applied_order END,entry_id""")){
            s.setString(1,prepared.manifestId);try(ResultSet r=s.executeQuery()){while(r.next())committed.add(new EntryRow(
                    r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),
                    nullableInt(r,"applied_order"),r.getString(7),r.getString(8),r.getString(9),r.getString(10)));}}
        return committed.equals(prepared.entries);
    }

    private boolean initialChildAuthorityValid(ChildAuthority child)throws SQLException{
        CanonicalId id=capsuleForChildLocked(child.taskId());
        if(id==null)return false;
        try{requireAdmittedAuthorityLocked(id,child.taskId());}
        catch(Conflict invalid){return false;}
        try(PreparedStatement s=connection.prepareStatement("""
                SELECT b.child_run_id,b.child_generation_id,b.child_manifest_id,b.child_manifest_hash,
                       h.head_task_id,h.pointer_version,a.state,t.status,r.terminal_status,
                       p.active_generation_id,p.owner_run_id,l.capsule_id
                FROM capsule_child_bindings b
                JOIN followup_candidate_authority a ON a.capsule_id=b.capsule_id
                JOIN thread_heads h ON h.thread_id=b.thread_id
                JOIN task_identities t ON t.task_id=b.child_task_id
                JOIN durable_runs r ON r.task_id=b.child_task_id AND r.run_id=b.child_run_id
                JOIN task_context_pointers p ON p.task_id=b.child_task_id
                JOIN child_initial_run_launches l ON l.capsule_id=b.capsule_id
                WHERE b.child_task_id=?""")){
            s.setString(1,child.taskId().value());try(ResultSet r=s.executeQuery()){return r.next()
                    &&child.runId().value().equals(r.getString("child_run_id"))
                    &&child.generationId().value().equals(r.getString("child_generation_id"))
                    &&Objects.equals(child.manifestId(),r.getString("child_manifest_id"))
                    &&Objects.equals(child.manifestHash(),r.getString("child_manifest_hash"))
                    &&child.taskId().value().equals(r.getString("head_task_id"))
                    &&child.threadHeadVersion()==r.getLong("pointer_version")
                    &&"ADMITTED".equals(r.getString("state"))
                    &&"CREATED".equals(r.getString("status"))&&r.getString("terminal_status")==null
                    &&child.generationId().value().equals(r.getString("active_generation_id"))
                    &&child.runId().value().equals(r.getString("owner_run_id"));}}
    }
    private LaunchRow launch(CanonicalId child)throws SQLException{
        try(PreparedStatement s=connection.prepareStatement("""
                SELECT state,launch_version,claim_owner_instance_id,claim_lease_token,
                       claim_fencing_token,claim_lease_version
                FROM child_initial_run_launches WHERE child_task_id=?""")){
            s.setString(1,child.value());try(ResultSet r=s.executeQuery()){if(!r.next())throw new Conflict("CHILD_START_AUTHORITY_MISSING");
                long fencing=r.getLong(5);Long fencingValue=r.wasNull()?null:fencing;
                long leaseVersion=r.getLong(6);Long leaseValue=r.wasNull()?null:leaseVersion;
                return new LaunchRow(r.getString(1),r.getLong(2),r.getString(3),r.getString(4),
                        fencingValue,leaseValue);}}
    }
    private AdmissionResult existing(CanonicalId id)throws SQLException{
        try(PreparedStatement s=connection.prepareStatement("""
                SELECT b.*,f.observed_head_version AS pointer_version FROM capsule_child_bindings b
                JOIN followup_admission_facts f USING(capsule_id)
                WHERE b.capsule_id=? AND f.result='ADMITTED'""")){
            s.setString(1,id.value());try(ResultSet r=s.executeQuery()){if(!r.next())throw new Conflict("ADMITTED_BINDING_MISSING");
                return new Admitted(new ChildAuthority(CanonicalId.parse(r.getString("child_task_id")),CanonicalId.parse(r.getString("child_run_id")),
                        CanonicalId.parse(r.getString("child_generation_id")),r.getString("child_manifest_id"),
                        r.getString("child_manifest_hash"),r.getLong("pointer_version")),true);}}
    }
    private void casState(CanonicalId id,CandidateState before,CandidateState after,long version,String manifestHash,
            Risk risk,String reason,Instant at)throws SQLException{
        try(PreparedStatement s=connection.prepareStatement("""
                UPDATE followup_candidate_authority SET state=?,
                candidate_version=candidate_version+1,manifest_candidate_hash=COALESCE(?,manifest_candidate_hash),
                risk=COALESCE(?,risk),reason_code=?,updated_at=? WHERE capsule_id=? AND state=? AND candidate_version=?""")){
            bind(s,after.name(),manifestHash,risk==null?null:risk.name(),reason,text(at),id.value(),before.name(),version);
            if(s.executeUpdate()!=1)throw new Conflict("CANDIDATE_CAS_CONFLICT");}
    }
    private AuthorityResolution resolveAuthorityPrecedenceLocked(CanonicalId id,long expectedVersion,
            Set<CandidateState> allowedStates,boolean validateParent,Instant at)throws SQLException{
        transactionProbe("BEFORE_AUTHORITY_LOCK",null);
        try(PreparedStatement lock=connection.prepareStatement(
                "UPDATE followup_candidate_authority SET updated_at=updated_at WHERE capsule_id=?")){
            lock.setString(1,id.value());if(lock.executeUpdate()!=1)return new AuthorityResolution(
                    AuthorityPrecedence.INTEGRITY_FAILURE,null,"FOLLOW_UP_CORE_INTEGRITY_FAILURE");}
        CandidateCoreValidation core=validateCandidateCoreAuthorityLocked(id);
        Candidate candidate=core.candidate();
        if(core.status()==CandidateCoreStatus.INTEGRITY_FAILURE||core.status()==CandidateCoreStatus.ILLEGAL_STATE)
            return new AuthorityResolution(AuthorityPrecedence.INTEGRITY_FAILURE,candidate,core.code());
        if(core.status()==CandidateCoreStatus.ADMITTED)return new AuthorityResolution(
                AuthorityPrecedence.ALREADY_ADMITTED,candidate,"ADMITTED");
        if(core.status()==CandidateCoreStatus.VALID_TERMINAL)return new AuthorityResolution(
                AuthorityPrecedence.ALREADY_TERMINAL,candidate,candidate.state().name());

        transactionProbe("BEFORE_HEAD_LOCK",candidate);
        HeadObservation observed=lockAndObserveHead(candidate.threadId());
        if(observed.taskId()==null)return new AuthorityResolution(AuthorityPrecedence.INTEGRITY_FAILURE,candidate,
                "THREAD_HEAD_INTEGRITY_FAILURE");
        if(!candidate.parentTaskId().value().equals(observed.taskId())
                ||candidate.expectedHeadVersion()!=observed.version()){
            transitionHeadConflictLocked(candidate,observed,at);
            Candidate terminal=findCandidate(id).orElseThrow();
            return new AuthorityResolution(AuthorityPrecedence.HEAD_CONFLICT_COMMITTED,terminal,"HEAD_CONFLICT");
        }
        transactionProbe("AFTER_HEAD_LOCK",candidate);
        if(candidate.version()!=expectedVersion)return new AuthorityResolution(AuthorityPrecedence.STALE_VERSION,candidate,
                "STALE_VERSION");
        if(!allowedStates.contains(candidate.state()))return new AuthorityResolution(
                AuthorityPrecedence.ILLEGAL_TRANSITION,candidate,"ILLEGAL_CANDIDATE_TRANSITION");
        if(validateParent){
            ParentAnchor parent;
            try{parent=currentEligibleParent(candidate.threadId()).orElse(null);}
            catch(RuntimeException invalid){return new AuthorityResolution(AuthorityPrecedence.INTEGRITY_FAILURE,candidate,
                    "COMPLETION_INTEGRITY_FAILURE");}
            if(parent==null||!parent.taskId().equals(candidate.parentTaskId())
                    ||parent.headVersion()!=candidate.expectedHeadVersion())return new AuthorityResolution(
                    AuthorityPrecedence.INTEGRITY_FAILURE,candidate,"COMPLETION_INTEGRITY_FAILURE");
        }
        return new AuthorityResolution(AuthorityPrecedence.PROCEED,candidate,"PROCEED");
    }

    private Candidate resolvedCandidateOrThrow(AuthorityResolution resolution){
        if(resolution.precedence()==AuthorityPrecedence.ALREADY_ADMITTED
                ||resolution.precedence()==AuthorityPrecedence.ALREADY_TERMINAL
                ||resolution.precedence()==AuthorityPrecedence.HEAD_CONFLICT_COMMITTED)return resolution.candidate();
        throw new Conflict(resolution.code());
    }

    /** Narrow Candidate core only; prepared Capsule/Manifest/diff and Parent Completion are deliberately excluded. */
    private CandidateCoreValidation validateCandidateCoreAuthorityLocked(CanonicalId id)throws SQLException{
        Candidate candidate;
        try{candidate=readCandidateRawLocked(id).orElse(null);}
        catch(RuntimeException corrupt){return invalidCore(null);}
        if(candidate==null||candidate.expectedHeadVersion()<0||candidate.version()<0
                ||candidate.inputOwner()==null||candidate.inputOwner().isBlank()
                ||candidate.inputSessionId()==null||candidate.inputSessionId().isBlank())return invalidCore(candidate);
        Intent intent;
        try(PreparedStatement s=connection.prepareStatement("""
                SELECT classification FROM followup_intent_classification_facts WHERE capsule_id=?""")){
            s.setString(1,id.value());try(ResultSet r=s.executeQuery()){
                if(!r.next())return invalidCore(candidate);
                try{intent=Intent.valueOf(r.getString(1));}catch(RuntimeException invalid){return invalidCore(candidate);}
                if(r.next())return invalidCore(candidate);}}
        try{
            List<ConfirmationRow> intents=confirmationRows(id,ConfirmationKind.INTENT);
            TerminalFact terminal=terminalFact(id);
            boolean valid;
            if(isTerminal(candidate.state()))valid=terminalCoreValid(candidate,intent,intents,terminal);
            else valid=terminal==null&&nonTerminalCoreValid(candidate,intent,intents);
            if(!valid)return invalidCore(candidate);
            if(candidate.state()==CandidateState.ADMITTED)return new CandidateCoreValidation(
                    CandidateCoreStatus.ADMITTED,candidate,"ADMITTED");
            if(isTerminal(candidate.state()))return new CandidateCoreValidation(
                    CandidateCoreStatus.VALID_TERMINAL,candidate,candidate.state().name());
            return new CandidateCoreValidation(CandidateCoreStatus.VALID_NON_TERMINAL,candidate,"VALID");
        }catch(RuntimeException corrupt){return invalidCore(candidate);}
    }

    private boolean nonTerminalCoreValid(Candidate candidate,Intent intent,List<ConfirmationRow> intents){
        return switch(candidate.state()){
            case CANDIDATE->candidate.version()==0&&intents.isEmpty();
            case NEEDS_INTENT_CONFIRMATION->intent==Intent.UNKNOWN&&candidate.version()==0&&intents.isEmpty();
            case PREPARING->preparingIntentProof(candidate,intent,intents,candidate.version());
            case NEEDS_RISK_CONFIRMATION->preparingIntentProof(candidate,intent,intents,candidate.version()-1);
            case READY_TO_ADMIT->preparingIntentProof(candidate,intent,intents,candidate.version()-1)
                    ||preparingIntentProof(candidate,intent,intents,candidate.version()-2);
            default->false;
        };
    }

    private boolean terminalCoreValid(Candidate candidate,Intent intent,List<ConfirmationRow> intents,TerminalFact fact)
            throws SQLException{
        if(fact==null||!fact.result.equals(candidate.state().name())
                ||fact.expectedHeadVersion!=candidate.expectedHeadVersion()
                ||fact.sourceVersion+1!=fact.resultingVersion||fact.resultingVersion!=candidate.version())return false;
        CandidateState source;
        try{source=CandidateState.valueOf(fact.sourceState);}catch(RuntimeException invalid){return false;}
        boolean sourceProof;
        if(candidate.state()==CandidateState.CANCELLED&&source==CandidateState.NEEDS_INTENT_CONFIRMATION){
            sourceProof=intent==Intent.UNKNOWN&&fact.sourceVersion==0&&intents.size()==1
                    &&intentConfirmationValid(candidate,intents.get(0),false);
        }else sourceProof=coreLineageAtSource(candidate,intent,intents,source,fact.sourceVersion);
        if(!sourceProof)return false;
        if(candidate.state()==CandidateState.CANCELLED&&source==CandidateState.CANDIDATE
                &&intent!=Intent.CREATE_NEW_THREAD&&intent!=Intent.REJECT)return false;
        if(candidate.state()==CandidateState.ADMITTED){
            if(source!=CandidateState.READY_TO_ADMIT||fact.childTaskId==null||candidate.childTaskId()==null
                    ||!fact.childTaskId.equals(candidate.childTaskId().value()))return false;
            return count("SELECT count(*) FROM capsule_child_bindings WHERE capsule_id=? AND child_task_id=?",
                    candidate.capsuleId().value(),fact.childTaskId)==1;
        }
        if(candidate.childTaskId()!=null||fact.childTaskId!=null)return false;
        if(candidate.state()==CandidateState.HEAD_CONFLICT)
            return fact.observedHeadTaskId!=null&&fact.observedHeadVersion!=null;
        return fact.observedHeadTaskId==null&&fact.observedHeadVersion==null;
    }

    private boolean coreLineageAtSource(Candidate candidate,Intent intent,List<ConfirmationRow> intents,
            CandidateState source,long sourceVersion){
        return switch(source){
            case CANDIDATE->sourceVersion==0&&intents.isEmpty();
            case NEEDS_INTENT_CONFIRMATION->intent==Intent.UNKNOWN&&sourceVersion==0&&intents.isEmpty();
            case PREPARING->preparingIntentProof(candidate,intent,intents,sourceVersion);
            case NEEDS_RISK_CONFIRMATION->preparingIntentProof(candidate,intent,intents,sourceVersion-1);
            case READY_TO_ADMIT->preparingIntentProof(candidate,intent,intents,sourceVersion-1)
                    ||preparingIntentProof(candidate,intent,intents,sourceVersion-2);
            default->false;
        };
    }

    private boolean preparingIntentProof(Candidate candidate,Intent intent,List<ConfirmationRow> confirmations,
            long preparingVersion){
        if(intent==Intent.CONTINUE_CURRENT_THREAD)return preparingVersion==0&&confirmations.isEmpty();
        return intent==Intent.UNKNOWN&&preparingVersion==1&&confirmations.size()==1
                &&intentConfirmationValid(candidate,confirmations.get(0),true);
    }

    private boolean intentConfirmationValid(Candidate candidate,ConfirmationRow confirmation,boolean accepted){
        boolean decision=accepted?confirmation.decision==ConfirmationDecision.CONTINUE_CURRENT_THREAD
                :Set.of(ConfirmationDecision.CREATE_NEW_THREAD,ConfirmationDecision.REJECT,
                        ConfirmationDecision.CANCEL).contains(confirmation.decision);
        return decision&&confirmation.expectedVersion==0
                &&candidate.inputOwner().equals(confirmation.owner)
                &&candidate.inputSessionId().equals(confirmation.session);
    }

    private CandidateCoreValidation invalidCore(Candidate candidate){
        return new CandidateCoreValidation(CandidateCoreStatus.INTEGRITY_FAILURE,candidate,
                "FOLLOW_UP_CORE_INTEGRITY_FAILURE");
    }
    private void requireValidCoreLocked(Candidate candidate)throws SQLException{
        CandidateCoreValidation core=validateCandidateCoreAuthorityLocked(candidate.capsuleId());
        if(core.status()==CandidateCoreStatus.INTEGRITY_FAILURE||core.status()==CandidateCoreStatus.ILLEGAL_STATE)
            throw new Conflict(core.code());
    }

    private HeadObservation lockAndObserveHead(CanonicalId thread)throws SQLException{
        try(PreparedStatement lock=connection.prepareStatement(
                "UPDATE thread_heads SET pointer_version=pointer_version WHERE thread_id=?")){
            lock.setString(1,thread.value());if(lock.executeUpdate()!=1)return new HeadObservation(null,-1);}
        return observeHead(thread);
    }

    private void transitionHeadConflictLocked(Candidate c,HeadObservation observed,Instant at)throws SQLException{
        if(isTerminal(c.state()))return;
        execute("""
                INSERT INTO followup_admission_facts(capsule_id,result,expected_head_version,
                    observed_head_task_id,observed_head_version,source_state,source_candidate_version,
                    resulting_candidate_version,reason_code,admitted_at,provenance)
                VALUES(?,'HEAD_CONFLICT',?,?,?,?,?,?,?,?,'R1E_HEAD_CAS')""",
                c.capsuleId().value(),c.expectedHeadVersion(),observed.taskId(),observed.version(),
                c.state().name(),c.version(),c.version()+1,"HEAD_CONFLICT",text(at));
        Candidate after=readCandidateRawLocked(c.capsuleId()).orElseThrow();
        if(after.state()!=CandidateState.HEAD_CONFLICT)throw new Conflict("HEAD_CONFLICT_STATE_FACT_MISMATCH");
    }
    private void transitionFailureLocked(Candidate c,String reason,String provenance,Instant at,
            String manifestHash,Risk risk)throws SQLException{
        if(manifestHash!=null||risk!=null)setPreparedMetadataLocked(c,manifestHash,risk,reason,at);
        execute("""
                INSERT INTO followup_admission_facts(capsule_id,result,expected_head_version,
                    source_state,source_candidate_version,resulting_candidate_version,
                    reason_code,admitted_at,provenance) VALUES(?,'FAILED',?,?,?,?,?,?,?)""",
                c.capsuleId().value(),c.expectedHeadVersion(),c.state().name(),c.version(),c.version()+1,
                reason,text(at),provenance);
    }
    private void transitionCancellationLocked(Candidate c,String reason,String provenance,Instant at)throws SQLException{
        execute("""
                INSERT INTO followup_admission_facts(capsule_id,result,expected_head_version,
                    source_state,source_candidate_version,resulting_candidate_version,
                    reason_code,admitted_at,provenance) VALUES(?,'CANCELLED',?,?,?,?,?,?,?)""",
                c.capsuleId().value(),c.expectedHeadVersion(),c.state().name(),c.version(),c.version()+1,
                reason,text(at),provenance);
    }
    private void setPreparedMetadataLocked(Candidate c,String manifestHash,Risk risk,String reason,Instant at)throws SQLException{
        try(PreparedStatement update=connection.prepareStatement("""
                UPDATE followup_candidate_authority SET manifest_candidate_hash=COALESCE(?,manifest_candidate_hash),
                    risk=COALESCE(?,risk),reason_code=?,updated_at=?
                WHERE capsule_id=? AND state=? AND candidate_version=?""")){
            bind(update,manifestHash,risk==null?null:risk.name(),reason,text(at),c.capsuleId().value(),
                    c.state().name(),c.version());
            if(update.executeUpdate()!=1)throw new Conflict("CANDIDATE_CAS_CONFLICT");
        }
    }
    private void transactionProbe(String point,Candidate candidate)throws SQLException{
        try{transactionProbe.hit(point,candidate);}
        catch(SQLException sql){throw sql;}
        catch(Exception failure){throw new SQLException("Follow-up transaction probe failed at "+point,failure);}
    }
    private HeadObservation observeHead(CanonicalId thread)throws SQLException{
        try(PreparedStatement s=connection.prepareStatement(
                "SELECT head_task_id,pointer_version FROM thread_heads WHERE thread_id=?")){
            s.setString(1,thread.value());try(ResultSet r=s.executeQuery()){return r.next()
                    ?new HeadObservation(r.getString(1),r.getLong(2)):new HeadObservation(null,-1);}}
    }
    private static boolean isTerminal(CandidateState state){return state==CandidateState.ADMITTED
            ||state==CandidateState.CANCELLED||state==CandidateState.FAILED||state==CandidateState.HEAD_CONFLICT;
    }
    private void persistArtifact(ProtectedInstructionArtifact a)throws SQLException{
        if(!a.verifyIntegrity())throw new Conflict("ARTIFACT_INTEGRITY_FAILURE");
        execute("""
                INSERT OR IGNORE INTO protected_instruction_artifacts(artifact_id,content_hash,protected_payload,
                storage_reference,sanitizer_version,retention_class,integrity_status,created_at)
                VALUES(?,?,?,?,?,?,'VERIFIED',?)""",a.id(),a.contentHash(),a.protectedPayload(),a.storageReference(),
                a.sanitizerVersion(),a.retentionClass(),text(a.createdAt()));
        try(PreparedStatement s=connection.prepareStatement("SELECT content_hash FROM protected_instruction_artifacts WHERE artifact_id=?")){
            s.setString(1,a.id());try(ResultSet r=s.executeQuery()){if(!r.next()||!a.contentHash().equals(r.getString(1)))
                throw new Conflict("ARTIFACT_HASH_CONFLICT");}}
    }
    private int nextConfirmation(CanonicalId id,ConfirmationKind kind)throws SQLException{
        try(PreparedStatement s=connection.prepareStatement("""
                SELECT COALESCE(MAX(confirmation_number),0)+1
                FROM followup_confirmation_facts WHERE capsule_id=? AND confirmation_kind=?""")){
            bind(s,id.value(),kind.name());try(ResultSet r=s.executeQuery()){return r.next()?r.getInt(1):1;}}
    }
    private ConfirmationDecision existingConfirmation(CanonicalId id,ConfirmationKind kind,long version,
            String owner,String session)throws SQLException{
        try(PreparedStatement s=connection.prepareStatement("""
                SELECT decision,input_owner,input_session_id
                FROM followup_confirmation_facts WHERE capsule_id=? AND confirmation_kind=?
                AND expected_candidate_version=? ORDER BY confirmation_number LIMIT 1""")){
            bind(s,id.value(),kind.name(),version);try(ResultSet r=s.executeQuery()){if(!r.next())return null;
                if(!owner.equals(r.getString("input_owner"))||!session.equals(r.getString("input_session_id")))
                    throw new Conflict("CONFIRMATION_STALE_OR_WRONG_OWNER");
                return ConfirmationDecision.valueOf(r.getString("decision"));}}
    }
    private List<ConfirmationRow> confirmationRows(CanonicalId id,ConfirmationKind kind)throws SQLException{
        List<ConfirmationRow> rows=new ArrayList<>();
        try(PreparedStatement s=connection.prepareStatement("""
                SELECT expected_candidate_version,input_owner,input_session_id,decision,
                    prepared_manifest_hash,prepared_risk
                FROM followup_confirmation_facts WHERE capsule_id=? AND confirmation_kind=?
                ORDER BY confirmation_number""")){
            bind(s,id.value(),kind.name());try(ResultSet r=s.executeQuery()){while(r.next()){
                String risk=r.getString("prepared_risk");
                rows.add(new ConfirmationRow(r.getLong("expected_candidate_version"),r.getString("input_owner"),
                        r.getString("input_session_id"),ConfirmationDecision.valueOf(r.getString("decision")),
                        r.getString("prepared_manifest_hash"),risk==null?null:Risk.valueOf(risk)));}}
        }catch(RuntimeException corrupt){throw new Conflict("FOLLOW_UP_CORE_INTEGRITY_FAILURE");}
        return List.copyOf(rows);
    }
    private TerminalFact terminalFact(CanonicalId id)throws SQLException{
        try(PreparedStatement s=connection.prepareStatement("""
                SELECT result,child_task_id,expected_head_version,observed_head_task_id,observed_head_version,
                    source_state,source_candidate_version,resulting_candidate_version
                FROM followup_admission_facts WHERE capsule_id=?""")){
            s.setString(1,id.value());try(ResultSet r=s.executeQuery()){if(!r.next())return null;
                long observed=r.getLong("observed_head_version");Long observedVersion=r.wasNull()?null:observed;
                TerminalFact fact=new TerminalFact(r.getString("result"),r.getString("child_task_id"),
                        r.getLong("expected_head_version"),r.getString("observed_head_task_id"),observedVersion,
                        r.getString("source_state"),r.getLong("source_candidate_version"),
                        r.getLong("resulting_candidate_version"));
                return r.next()?null:fact;}}
    }
    private int count(String sql,Object...values)throws SQLException{
        try(PreparedStatement s=connection.prepareStatement(sql)){bind(s,values);
            try(ResultSet r=s.executeQuery()){if(!r.next())throw new Conflict("FOLLOW_UP_INTEGRITY_QUERY_FAILURE");
                return r.getInt(1);}}
    }
    private void execute(String sql,Object...values)throws SQLException{
        try(PreparedStatement s=connection.prepareStatement(sql)){bind(s,values);s.executeUpdate();}
    }
    private <T>T tx(SqlWork<T> work)throws SQLException{
        synchronized(connection){boolean auto=connection.getAutoCommit();if(!auto){try{return work.run();}catch(SQLException e){throw e;}
            catch(RuntimeException e){throw e;}catch(Exception e){throw new SQLException(e);}}
            connection.setAutoCommit(false);try{T value=work.run();connection.commit();return value;}
            catch(Exception e){connection.rollback();if(e instanceof SQLException sql)throw sql;if(e instanceof RuntimeException runtime)throw runtime;
                throw new SQLException(e);}finally{connection.setAutoCommit(true);}}
    }
    private static void bind(PreparedStatement s,Object...values)throws SQLException{
        for(int i=0;i<values.length;i++){Object v=values[i];if(v==null)s.setObject(i+1,null);else if(v instanceof byte[] b)s.setBytes(i+1,b);
            else if(v instanceof Integer n)s.setInt(i+1,n);else if(v instanceof Long n)s.setLong(i+1,n);else s.setString(i+1,v.toString());}
    }
    private static Integer nullableInt(ResultSet r,String column)throws SQLException{int v=r.getInt(column);return r.wasNull()?null:v;}
    private static String text(Instant value){return value.toString();}
    private static void require(CanonicalId id,EntityFamily family){if(id==null||id.family()!=family)throw new IllegalArgumentException("Expected "+family);}
    private interface SqlWork<T>{T run()throws Exception;}
    private static final class Conflict extends RuntimeException{final String code;Conflict(String code){super(code);this.code=code;}}
    private record PreparedRow(String manifestId,int schemaVersion,String policy,String manifestHash,String sourceFingerprint,
                               String workspaceIdentity,List<EntryRow> entries){ }
    private record EntryRow(String id,String sourceType,String sourcePath,String scope,String status,Integer appliedOrder,
                             String contentHash,String artifactId,String parseResult,String exclusionReason){ }
    private record HeadObservation(String taskId,long version){ }
    private record LaunchRow(String state,long version,String claimOwner,String claimLeaseToken,
                             Long claimFencingToken,Long claimLeaseVersion){ }
    private record ConfirmationRow(long expectedVersion,String owner,String session,ConfirmationDecision decision,
                                   String preparedManifestHash,Risk preparedRisk){ }
    private record TerminalFact(String result,String childTaskId,long expectedHeadVersion,String observedHeadTaskId,
                                Long observedHeadVersion,String sourceState,long sourceVersion,long resultingVersion){ }
    private record RiskFact(Risk risk,String classifierVersion,String reason){ }
    private static final class FollowUpSql{static String sha256(String value){try{return java.util.HexFormat.of().formatHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }catch(Exception e){throw new IllegalStateException(e);}}}
}
