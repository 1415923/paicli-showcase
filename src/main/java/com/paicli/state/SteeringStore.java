package com.paicli.state;

import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.state.R1aDurableModels.ExecutionAuthoritySnapshot;
import com.paicli.state.SteeringModels.*;
import com.paicli.runtime.SteeringClassifier;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Transactional R1D Steering admission, queue and application authority. */
public final class SteeringStore {
    private static final Set<String> SAFE_POINT_CANDIDATES=Set.of(
            "PROVIDER_REQUEST_BEFORE","PROVIDER_RESPONSE_BEFORE_TOOL","TOOL_OCCURRENCE_TERMINAL",
            "HOOK_OCCURRENCE_TERMINAL","STEP_ATTEMPT_TERMINAL",
            "VERIFICATION_FACTS_COMMITTED","NEXT_STEP_ATTEMPT_BEFORE",
            "COMPLETION_GATE_BEFORE");
    public enum Status { SUCCESS, NOT_FOUND, ALREADY_EXISTS, INVALID_STATE, INVALID_SCOPE,
        NEEDS_CONFIRMATION, REJECTED, TERMINAL_TASK, VERSION_CONFLICT, OWNER_MISMATCH,
        RUN_MISMATCH, LEASE_TOKEN_MISMATCH, FENCING_REJECTED, LEASE_EXPIRED,
        POINTER_MISMATCH, PLAN_MISMATCH, UNSAFE_POINT, REBIND_REQUIRED,
        ORDER_BLOCKED, INTEGRITY_VIOLATION, LEGACY_UNAVAILABLE,
        MATERIAL_PLAN_REQUIRED }
    public record Mutation(Status status, CanonicalId steeringId, Queue queue) {
        static Mutation of(Status status) { return new Mutation(status, null, null); }
    }
    public record MaterialPlanRequest(Definition definition,
            StructuredPlanModels.Revision sourceRevision,int revisionNumber) { }
    public record AttemptPause(PlanExecutionModels.Attempt attempt,
            long expectedStepVersion,int endIteration) { }
    public record ApplicationResult(Status status, Application application,
            boolean executionPaused,MaterialPlanRequest materialPlanRequest) {
        static ApplicationResult of(Status status) {
            return new ApplicationResult(status,null,false,null);
        }
    }
    public record TargetRead(Status status, Target target) { }
    public record DraftState(String state,long version) { }
    public record Summary(CanonicalId steeringId,State state,Long sequence,long version,
            CanonicalId targetRunId) { }

    private final Connection connection;
    public SteeringStore(Connection connection) { this.connection = Objects.requireNonNull(connection); }

    static void terminalizeOpen(Connection connection,CanonicalId taskId,String terminalState,
            String reason,Instant at)throws SQLException{
        List<String> ids=new ArrayList<>();
        try(PreparedStatement s=connection.prepareStatement("SELECT steering_id FROM steering_queue_authority WHERE task_id=? AND state NOT IN ('APPLIED','REJECTED','CANCELLED') ORDER BY queue_sequence,updated_at")){
            s.setString(1,taskId.value());try(ResultSet r=s.executeQuery()){while(r.next())ids.add(r.getString(1));}}
        for(String id:ids){long version;
            try(PreparedStatement s=connection.prepareStatement("SELECT queue_version FROM steering_queue_authority WHERE steering_id=?")){
                s.setString(1,id);try(ResultSet r=s.executeQuery()){if(!r.next())continue;version=r.getLong(1);}}
            try(PreparedStatement s=connection.prepareStatement("UPDATE steering_queue_authority SET state=?,queue_version=queue_version+1,reason_code=?,updated_at=? WHERE steering_id=? AND queue_version=?")){
                s.setString(1,terminalState);s.setString(2,reason);s.setString(3,at.toString());s.setString(4,id);s.setLong(5,version);
                if(s.executeUpdate()!=1)throw new SQLException("Steering terminal race conflict");}
            try(PreparedStatement s=connection.prepareStatement("INSERT INTO steering_state_facts(steering_id,state_number,task_id,target_run_id,queue_version,state,reason_code,recorded_at) SELECT q.steering_id,COALESCE((SELECT max(f.state_number) FROM steering_state_facts f WHERE f.steering_id=q.steering_id),0)+1,q.task_id,q.target_run_id,q.queue_version,q.state,?,? FROM steering_queue_authority q WHERE q.steering_id=?")){
                s.setString(1,reason);s.setString(2,at.toString());s.setString(3,id);s.executeUpdate();}
        }
    }

    static boolean recoveryIntegrityValid(Connection connection,CanonicalId taskId)throws SQLException{
        try(PreparedStatement s=connection.prepareStatement("""
                SELECT d.steering_id,d.content,d.content_hash,
                q.state,q.queue_version FROM steering_definitions d LEFT JOIN steering_queue_authority q
                ON q.steering_id=d.steering_id WHERE d.task_id=?""")){
            s.setString(1,taskId.value());try(ResultSet r=s.executeQuery()){while(r.next()){
                String id=r.getString(1),state=r.getString(4);if(state==null||"APPLYING".equals(state)
                        ||!SteeringClassifier.sha(r.getString(2)).equals(r.getString(3)))return false;
                if(!staticExists(connection,"SELECT 1 FROM steering_classification_facts WHERE steering_id=?",id))return false;
                if("APPLIED".equals(state)&&!staticAppliedIntegrity(connection,id))return false;
            }}}
        try(PreparedStatement s=connection.prepareStatement("""
                SELECT 1 FROM steering_producer_pause_facts f
                LEFT JOIN steering_definitions d ON d.task_id=f.task_id
                  AND d.steering_id=f.steering_id
                LEFT JOIN side_effect_occurrences o ON o.occurrence_id=f.occurrence_id
                  AND o.task_id=f.task_id AND o.run_id=f.run_id
                LEFT JOIN verification_runs v ON f.producer_kind='VERIFICATION'
                  AND v.verification_id=f.producer_identity
                  AND v.task_id=f.task_id AND v.run_id=f.run_id
                LEFT JOIN hook_runs h ON f.producer_kind='HOOK'
                  AND h.hook_run_id=f.producer_identity
                  AND h.task_id=f.task_id AND h.run_id=f.run_id
                WHERE f.task_id=? AND (d.steering_id IS NULL OR o.occurrence_id IS NULL
                  OR o.status<>'FAILED'
                  OR o.external_outcome<>'STEERING_BLOCKED_BEFORE_LAUNCH'
                  OR (f.producer_kind='VERIFICATION'
                      AND (v.verification_id IS NULL OR v.status<>'ERROR'
                           OR v.error_excerpt<>'PAUSED_BY_STEERING'))
                  OR (f.producer_kind='HOOK'
                      AND (h.hook_run_id IS NULL OR h.status<>'BLOCKED'
                           OR h.error_excerpt<>'PAUSED_BY_STEERING')))
                LIMIT 1
                """)){
            s.setString(1,taskId.value());try(ResultSet rows=s.executeQuery()){
                if(rows.next())return false;
            }
        }
        try(PreparedStatement s=connection.prepareStatement("""
                SELECT 1 FROM verification_runs v
                WHERE v.task_id=? AND v.error_excerpt='PAUSED_BY_STEERING'
                  AND NOT EXISTS(SELECT 1 FROM steering_producer_pause_facts f
                    WHERE f.producer_kind='VERIFICATION'
                      AND f.producer_identity=v.verification_id)
                UNION ALL
                SELECT 1 FROM hook_runs h
                WHERE h.task_id=? AND h.error_excerpt='PAUSED_BY_STEERING'
                  AND NOT EXISTS(SELECT 1 FROM steering_producer_pause_facts f
                    WHERE f.producer_kind='HOOK' AND f.producer_identity=h.hook_run_id)
                LIMIT 1
                """)){
            s.setString(1,taskId.value());s.setString(2,taskId.value());
            try(ResultSet rows=s.executeQuery()){if(rows.next())return false;}
        }
        return true;
    }
    private static boolean staticExists(Connection connection,String sql,String value)throws SQLException{
        try(PreparedStatement s=connection.prepareStatement(sql)){s.setString(1,value);try(ResultSet r=s.executeQuery()){return r.next();}}
    }
    private static boolean staticAppliedIntegrity(Connection connection,String steeringId)throws SQLException{
        try(PreparedStatement s=connection.prepareStatement("""
                SELECT a.application_kind,a.queue_version,
                a.constraint_revision_id,a.plan_revision_id,a.application_hash,c.canonical_content,
                COALESCE(c.canonical_hash,p.canonical_hash),sp.canonical_hash
                FROM steering_application_facts a
                LEFT JOIN constraint_revisions c ON c.task_id=a.task_id AND c.constraint_revision_id=a.constraint_revision_id
                LEFT JOIN steering_constraint_provenance sp ON sp.task_id=a.task_id AND sp.steering_id=a.steering_id
                LEFT JOIN plan_revisions p ON p.task_id=a.task_id AND p.plan_revision_id=a.plan_revision_id
                WHERE a.steering_id=?""")){
            s.setString(1,steeringId);try(ResultSet r=s.executeQuery()){if(!r.next())return false;
                String kind=r.getString(1),artifact="PLAN_DRAFT".equals(kind)?r.getString(4):r.getString(3);
                String canonical=r.getString(7);if(artifact==null||canonical==null)return false;
                if(!"PLAN_DRAFT".equals(kind)){
                    if(r.getString(6)==null||!SteeringClassifier.sha(r.getString(6)).equals(canonical)
                            ||!canonical.equals(r.getString(8)))return false;
                }
                return SteeringClassifier.sha(steeringId+"|"+r.getLong(2)+"|"+artifact+"|"+canonical)
                        .equals(r.getString(5));
            }}
    }

    public Mutation admit(Target target, SteeringClassifier.Result classified,
            Provenance provenance, String inputOwner, String sessionId, Instant at) {
        return admit(target,classified,provenance,inputOwner,sessionId,null,at);
    }

    public Mutation admit(Target target, SteeringClassifier.Result classified,
            Provenance provenance, String inputOwner, String sessionId,
            String pendingApprovalReference, Instant at) {
        Objects.requireNonNull(target); Objects.requireNonNull(classified); Objects.requireNonNull(at);
        CanonicalId id = CanonicalId.newId(EntityFamily.STEERING);
        Definition definition = new Definition(id, target, classified.content(),
                classified.contentHash(), provenance, inputOwner, sessionId, at);
        return tx(() -> {
            update("UPDATE task_identities SET status=status WHERE task_id=?",target.taskId().value());
            Status targetStatus = validateAdmissionTarget(target);
            boolean terminal = targetStatus == Status.TERMINAL_TASK;
            if (targetStatus != Status.SUCCESS && !terminal) return Mutation.of(targetStatus);
            insertDefinition(definition);
            insertClassification(target.taskId(), id, 1, classified.classification(),
                    classified.outcome(), SteeringClassifier.VERSION, classified.reasonCode(), at);
            boolean unsupportedMaterial=target.planRevisionId()==null
                    &&classified.classification()==Classification.MATERIAL;
            State state = terminal || unsupportedMaterial || classified.classification() == Classification.REJECT
                    ? State.REJECTED : classified.classification() == Classification.UNKNOWN
                    ? State.NEEDS_CONFIRMATION : State.QUEUED;
            Long sequence = state == State.QUEUED ? nextSequence() : null;
            String reason = terminal ? "TASK_TERMINAL" : unsupportedMaterial
                    ?"PLAN_DISABLED_MATERIAL_UNAVAILABLE":classified.reasonCode();
            // Preserve the complete durable state-machine history: every admitted
            // Steering starts as CANDIDATE, then classification advances it to the
            // first authoritative state in the same transaction.
            insertQueue(definition, State.CANDIDATE, null, "ADMITTED", at);
            insertState(id, target.taskId(), target.runId(), 0, State.CANDIDATE, "ADMITTED", at);
            Queue candidate = readQueue(id).orElseThrow();
            transition(candidate, state, sequence, reason, at);
            if(state==State.QUEUED&&pendingApprovalReference!=null&&!pendingApprovalReference.isBlank())
                insert("INSERT INTO steering_approval_conflict_facts(steering_id,conflict_number,task_id,approval_reference,conflict_type,recorded_at) VALUES(?,1,?,?,'PENDING_INVALIDATED',?)",
                        id.value(),target.taskId().value(),pendingApprovalReference.substring(0,
                                Math.min(128,pendingApprovalReference.length())),at.toString());
            Queue queue = readQueue(id).orElseThrow();
            return new Mutation(terminal ? Status.TERMINAL_TASK
                    : state == State.NEEDS_CONFIRMATION ? Status.NEEDS_CONFIRMATION
                    : state == State.REJECTED ? Status.REJECTED : Status.SUCCESS, id, queue);
        });
    }

    public TargetRead captureTarget(CanonicalId taskId,CanonicalId runId){
        try{
            String thread=textValue("SELECT thread_id FROM task_identities WHERE task_id=?",taskId.value());
            if(thread==null)return new TargetRead(Status.NOT_FOUND,null);
            CanonicalId generation;long contextVersion;
            try(PreparedStatement s=connection.prepareStatement("SELECT active_generation_id,owner_run_id,pointer_version FROM task_context_pointers WHERE task_id=?")){
                s.setString(1,taskId.value());try(ResultSet r=s.executeQuery()){if(!r.next())return new TargetRead(Status.POINTER_MISMATCH,null);
                    if(!runId.value().equals(r.getString(2)))return new TargetRead(Status.REBIND_REQUIRED,null);
                    generation=CanonicalId.parse(r.getString(1));contextVersion=r.getLong(3);}}
            String applicability=textValue("SELECT applicability FROM task_plan_applicability WHERE task_id=?",taskId.value());
            if("LEGACY_PLAN_ONLY".equals(applicability))return new TargetRead(Status.LEGACY_UNAVAILABLE,null);
            CanonicalId plan=null;String planHash=null;Long planVersion=null;
            if("PLAN_REQUIRED".equals(applicability)){
                try(PreparedStatement s=connection.prepareStatement("""
                        SELECT a.active_plan_revision_id,p.canonical_hash,a.pointer_version
                        FROM task_plan_authorities a JOIN plan_revisions p ON p.task_id=a.task_id
                        AND p.plan_revision_id=a.active_plan_revision_id WHERE a.task_id=?""")){
                    s.setString(1,taskId.value());try(ResultSet r=s.executeQuery()){if(!r.next())return new TargetRead(Status.PLAN_MISMATCH,null);
                        plan=CanonicalId.parse(r.getString(1));planHash=r.getString(2);planVersion=r.getLong(3);}}
            }else if(!"PLAN_DISABLED".equals(applicability))return new TargetRead(Status.INVALID_SCOPE,null);
            return new TargetRead(Status.SUCCESS,new Target(CanonicalId.parse(thread),taskId,runId,plan,planHash,
                    planVersion,generation,contextVersion));
        }catch(SQLException error){throw new IllegalStateException("Steering target capture failed",error);}
    }

    public Mutation confirm(CanonicalId steeringId, ConfirmationDecision decision,
            String inputOwner, String sessionId, long expectedVersion, Instant at) {
        require(steeringId, EntityFamily.STEERING); Objects.requireNonNull(decision);
        return tx(() -> {
            Definition definition = readDefinition(steeringId).orElse(null);
            Queue queue = readQueue(steeringId).orElse(null);
            if (definition == null || queue == null) return Mutation.of(Status.NOT_FOUND);
            if (!definition.inputOwner().equals(inputOwner)
                    || !definition.inputSessionId().equals(sessionId)) return Mutation.of(Status.OWNER_MISMATCH);
            if (queue.version() != expectedVersion) return Mutation.of(Status.VERSION_CONFLICT);
            if (queue.state() != State.NEEDS_CONFIRMATION) return Mutation.of(Status.INVALID_STATE);
            if (isTerminalTask(definition.target().taskId())) return reject(queue, "TASK_TERMINAL", at);
            insert("INSERT INTO steering_confirmation_facts(steering_id,confirmation_number,task_id,target_run_id,input_owner,input_session_id,decision,confirmed_at) VALUES(?,1,?,?,?,?,?,?)",
                    steeringId.value(), queue.taskId().value(), queue.target().runId().value(),
                    inputOwner, sessionId, decision.name(), at.toString());
            if (decision == ConfirmationDecision.REJECT || decision == ConfirmationDecision.CANCEL) {
                State terminal = decision == ConfirmationDecision.REJECT ? State.REJECTED : State.CANCELLED;
                transition(queue, terminal, null, decision.name(), at);
                return new Mutation(decision == ConfirmationDecision.REJECT ? Status.REJECTED : Status.SUCCESS,
                        steeringId, readQueue(steeringId).orElseThrow());
            }
            if(decision==ConfirmationDecision.CONFIRM_MATERIAL
                    &&definition.target().planRevisionId()==null){
                transition(queue,State.REJECTED,null,"PLAN_DISABLED_MATERIAL_UNAVAILABLE",at);
                return new Mutation(Status.REJECTED,steeringId,readQueue(steeringId).orElseThrow());
            }
            Classification classification = decision == ConfirmationDecision.CONFIRM_MATERIAL
                    ? Classification.MATERIAL : Classification.NON_MATERIAL;
            ClassificationOutcome outcome = classification == Classification.MATERIAL
                    ? ClassificationOutcome.ACCEPT_MATERIAL : ClassificationOutcome.ACCEPT;
            insertClassification(queue.taskId(), steeringId, 2, classification, outcome,
                    SteeringClassifier.VERSION + "-OWNER-CONFIRMED", decision.name(), at);
            transition(queue, State.QUEUED, nextSequence(), "OWNER_CONFIRMED", at);
            return new Mutation(Status.SUCCESS, steeringId, readQueue(steeringId).orElseThrow());
        });
    }

    public Mutation cancel(CanonicalId steeringId, String inputOwner, String sessionId,
            long expectedVersion, Instant at) {
        return tx(() -> {
            Definition definition = readDefinition(steeringId).orElse(null);
            Queue queue = readQueue(steeringId).orElse(null);
            if (definition == null || queue == null) return Mutation.of(Status.NOT_FOUND);
            if (!definition.inputOwner().equals(inputOwner) || !definition.inputSessionId().equals(sessionId))
                return Mutation.of(Status.OWNER_MISMATCH);
            if (queue.version() != expectedVersion) return Mutation.of(Status.VERSION_CONFLICT);
            if (queue.terminal() || queue.state() == State.APPLYING) return Mutation.of(Status.INVALID_STATE);
            transition(queue, State.CANCELLED, queue.sequence(), "OWNER_CANCELLED", at);
            return new Mutation(Status.SUCCESS, steeringId, readQueue(steeringId).orElseThrow());
        });
    }

    public Optional<Definition> readDefinition(CanonicalId id) throws SQLException {
        try (PreparedStatement s = connection.prepareStatement("""
                SELECT thread_id,task_id,target_run_id,target_plan_revision_id,target_plan_hash,
                    target_plan_pointer_version,target_generation_id,target_context_pointer_version,
                    content,content_hash,provenance,input_owner,input_session_id,created_at
                FROM steering_definitions WHERE steering_id=?""")) {
            s.setString(1, id.value()); try (ResultSet r=s.executeQuery()) { if(!r.next()) return Optional.empty();
                Target target=target(r,1); return Optional.of(new Definition(id,target,r.getString(9),r.getString(10),
                        Provenance.valueOf(r.getString(11)),r.getString(12),r.getString(13),Instant.parse(r.getString(14)))); }
        }
    }

    public Optional<Queue> readQueue(CanonicalId id) throws SQLException {
        try (PreparedStatement s=connection.prepareStatement("""
                SELECT task_id,target_run_id,target_plan_revision_id,target_plan_hash,target_plan_pointer_version,
                    target_generation_id,target_context_pointer_version,state,queue_sequence,queue_version,reason_code,updated_at,
                    (SELECT thread_id FROM steering_definitions d WHERE d.steering_id=q.steering_id)
                FROM steering_queue_authority q WHERE steering_id=?""")) {
            s.setString(1,id.value());try(ResultSet r=s.executeQuery()){if(!r.next())return Optional.empty();
                CanonicalId task=CanonicalId.parse(r.getString(1));
                Target target=new Target(CanonicalId.parse(r.getString(13)),task,CanonicalId.parse(r.getString(2)),
                        parse(r.getString(3)),r.getString(4),nullableLong(r,5),CanonicalId.parse(r.getString(6)),r.getLong(7));
                return Optional.of(new Queue(id,task,target,State.valueOf(r.getString(8)),nullableLong(r,9),
                        r.getLong(10),r.getString(11),Instant.parse(r.getString(12))));}}
    }

    public Optional<Queue> oldestOpen(CanonicalId taskId) throws SQLException {
        Queue eligible=null;
        try(PreparedStatement s=connection.prepareStatement("""
                SELECT steering_id FROM steering_queue_authority
                WHERE task_id=? AND state IN ('QUEUED','DEFERRED','APPLYING')
                ORDER BY queue_sequence LIMIT 1""")){
            s.setString(1,taskId.value());try(ResultSet r=s.executeQuery()){
                if(r.next())eligible=readQueue(CanonicalId.parse(r.getString(1))).orElseThrow();}}
        Queue waiting=null;
        try(PreparedStatement s=connection.prepareStatement("""
                SELECT q.steering_id,d.created_at
                FROM steering_queue_authority q JOIN steering_definitions d ON d.steering_id=q.steering_id
                WHERE q.task_id=? AND q.state='NEEDS_CONFIRMATION' ORDER BY d.created_at LIMIT 1""")){
            s.setString(1,taskId.value());try(ResultSet r=s.executeQuery()){if(r.next()){
                waiting=readQueue(CanonicalId.parse(r.getString(1))).orElseThrow();}}}
        if(waiting!=null&&(eligible==null||definitionPrecedes(waiting.steeringId(),eligible.steeringId())))
            return Optional.of(waiting);
        return Optional.ofNullable(eligible);
    }

    public List<Summary> list(CanonicalId taskId,int limit){
        if(limit<1||limit>50)throw new IllegalArgumentException("Steering history limit must be 1..50");
        try(PreparedStatement s=connection.prepareStatement("""
                SELECT steering_id,state,queue_sequence,
                queue_version,target_run_id FROM steering_queue_authority WHERE task_id=?
                ORDER BY updated_at DESC,steering_id DESC LIMIT ?""")){
            s.setString(1,taskId.value());s.setInt(2,limit);List<Summary> result=new ArrayList<>();
            try(ResultSet r=s.executeQuery()){while(r.next())result.add(new Summary(CanonicalId.parse(r.getString(1)),
                    State.valueOf(r.getString(2)),nullableLong(r,3),r.getLong(4),CanonicalId.parse(r.getString(5))));}
            return List.copyOf(result);
        }catch(SQLException error){throw new IllegalStateException("Steering history read failed",error);}
    }

    public boolean blocksCompletion(CanonicalId taskId) {
        try { return exists("""
                SELECT 1 FROM steering_queue_authority WHERE task_id=?
                AND state IN ('NEEDS_CONFIRMATION','QUEUED','DEFERRED','APPLYING')""", taskId.value()); }
        catch(SQLException error){throw new IllegalStateException("Steering completion barrier read failed",error);}
    }

    public Optional<CanonicalId> latestAppliedPlanDraft(CanonicalId taskId,CanonicalId runId){
        try(PreparedStatement s=connection.prepareStatement("""
                SELECT a.plan_revision_id FROM steering_application_facts a
                JOIN plan_revision_lifecycle l ON l.task_id=a.task_id AND l.plan_revision_id=a.plan_revision_id
                WHERE a.task_id=? AND a.application_kind='PLAN_DRAFT' AND l.state IN ('DRAFT','VALIDATED','APPROVED')
                ORDER BY a.applied_at DESC LIMIT 1""")){s.setString(1,taskId.value());
            try(ResultSet r=s.executeQuery()){return r.next()?Optional.of(CanonicalId.parse(r.getString(1))):Optional.empty();}}
        catch(SQLException error){throw new IllegalStateException("Steering Plan draft read failed",error);}
    }
    public Optional<DraftState> planDraftState(CanonicalId taskId,CanonicalId planId){
        try(PreparedStatement s=connection.prepareStatement("SELECT state,cas_version FROM plan_revision_lifecycle WHERE task_id=? AND plan_revision_id=?")){
            s.setString(1,taskId.value());s.setString(2,planId.value());try(ResultSet r=s.executeQuery()){
                return r.next()?Optional.of(new DraftState(r.getString(1),r.getLong(2))):Optional.empty();}}
        catch(SQLException error){throw new IllegalStateException("Steering Plan lifecycle read failed",error);}
    }

    public Mutation retarget(CanonicalId steeringId, Target next, String inputOwner,
            String sessionId, long expectedVersion, Instant at) {
        return tx(() -> {
            Definition definition=readDefinition(steeringId).orElse(null);
            Queue queue=readQueue(steeringId).orElse(null);
            if(definition==null||queue==null)return Mutation.of(Status.NOT_FOUND);
            if(inputOwner==null||inputOwner.isBlank()||sessionId==null||sessionId.isBlank())
                return Mutation.of(Status.OWNER_MISMATCH);
            if(queue.version()!=expectedVersion)return Mutation.of(Status.VERSION_CONFLICT);
            if(queue.terminal()||queue.state()==State.APPLYING)return Mutation.of(Status.INVALID_STATE);
            Status valid=validateAdmissionTarget(next);if(valid!=Status.SUCCESS)return Mutation.of(valid);
            if(!next.threadId().equals(definition.target().threadId())
                    ||!next.taskId().equals(definition.target().taskId()))return Mutation.of(Status.INVALID_SCOPE);
            insert("INSERT INTO steering_confirmation_facts(steering_id,confirmation_number,task_id,target_run_id,input_owner,input_session_id,decision,confirmed_at) VALUES(?,?,?,?,?,?,?,?)",
                    steeringId.value(),nextFact("steering_confirmation_facts","confirmation_number",steeringId),
                    next.taskId().value(),next.runId().value(),inputOwner,sessionId,
                    latestClassification(steeringId)==Classification.MATERIAL
                            ?ConfirmationDecision.CONFIRM_MATERIAL.name():ConfirmationDecision.CONFIRM_NON_MATERIAL.name(),at.toString());
            int changed=update("""
                    UPDATE steering_queue_authority SET target_run_id=?,target_plan_revision_id=?,target_plan_hash=?,
                        target_plan_pointer_version=?,target_generation_id=?,target_context_pointer_version=?,
                        state='QUEUED',queue_sequence=?,queue_version=queue_version+1,reason_code='OWNER_REBOUND',updated_at=?
                    WHERE steering_id=? AND queue_version=?""", next.runId().value(),value(next.planRevisionId()),
                    next.planHash(),next.planPointerVersion(),next.generationId().value(),next.contextPointerVersion(),
                    nextSequence(),at.toString(),steeringId.value(),expectedVersion);
            if(changed!=1)return Mutation.of(Status.VERSION_CONFLICT);
            Queue after=readQueue(steeringId).orElseThrow();insertState(steeringId,after.taskId(),after.target().runId(),
                    after.version(),State.QUEUED,"OWNER_REBOUND",at);
            return new Mutation(Status.SUCCESS,steeringId,after);
        });
    }

    public ApplicationResult applyOldest(ExecutionAuthoritySnapshot execution, Instant at,
            String safePoint, boolean approvalPending) {
        return applyOldest(execution,at,safePoint,approvalPending,null,null);
    }

    public ApplicationResult applyOldest(ExecutionAuthoritySnapshot execution, Instant at,
            String safePoint, boolean approvalPending,
            StructuredPlanModels.Revision materialDraft,AttemptPause attemptPause) {
        if(safePoint==null||!SAFE_POINT_CANDIDATES.contains(safePoint))
            return ApplicationResult.of(Status.INVALID_SCOPE);
        return txApplication(() -> {
            Status authority=lockAuthority(execution,at);if(authority!=Status.SUCCESS)return ApplicationResult.of(authority);
            Queue queue=oldestOpen(execution.taskId()).orElse(null);
            if(queue==null)return ApplicationResult.of(Status.NOT_FOUND);
            if(queue.state()!=State.QUEUED)return ApplicationResult.of(Status.ORDER_BLOCKED);
            if(!queue.target().runId().equals(execution.runId()))return ApplicationResult.of(Status.REBIND_REQUIRED);
            if(approvalPending||unsafeDurableActivity(execution.taskId(),execution.runId()))
                return ApplicationResult.of(Status.UNSAFE_POINT);
            Status target=validateApplicationTarget(queue);if(target!=Status.SUCCESS)return ApplicationResult.of(target);
            Definition definition=readDefinition(queue.steeringId()).orElseThrow();
            if(!definition.contentHash().equals(SteeringClassifier.sha(definition.content())))
                return ApplicationResult.of(Status.INTEGRITY_VIOLATION);
            Classification classification=latestClassification(queue.steeringId());
            if(classification==Classification.UNKNOWN)return ApplicationResult.of(Status.NEEDS_CONFIRMATION);
            MaterialPlanRequest materialRequest=null;
            boolean attemptPaused=false;
            if(classification==Classification.MATERIAL){
                materialRequest=materialRequest(definition,queue);
                if(materialDraft==null)return new ApplicationResult(
                        Status.MATERIAL_PLAN_REQUIRED,null,true,materialRequest);
                Status draftStatus=validateMaterialDraft(materialRequest,materialDraft,execution);
                if(draftStatus!=Status.SUCCESS)return ApplicationResult.of(draftStatus);
                if(attemptPause!=null){
                    PlanExecutionStore.Status pauseStatus=PlanExecutionStore
                            .completeAttemptInTransaction(connection,execution,at,
                                    attemptPause.attempt(),PlanExecutionModels.Outcome.BLOCKED,
                                    attemptPause.endIteration(),attemptPause.expectedStepVersion(),
                                    "MATERIAL_STEERING",
                                    "Paused by durable material Steering");
                    if(pauseStatus!=PlanExecutionStore.Status.SUCCESS)
                        return ApplicationResult.of(pauseStatus==PlanExecutionStore.Status.VERSION_CONFLICT
                                ?Status.VERSION_CONFLICT:Status.INVALID_STATE);
                    attemptPaused=true;
                }else if(exists("""
                        SELECT 1 FROM plan_step_attempts
                        WHERE task_id=? AND plan_revision_id=? AND run_id=? AND state='RUNNING'
                        """,queue.taskId().value(),queue.target().planRevisionId().value(),
                        execution.runId().value()))return ApplicationResult.of(Status.INVALID_STATE);
            }
            if(update("UPDATE steering_queue_authority SET state='APPLYING',queue_version=queue_version+1,reason_code='SAFE_POINT_CLAIM',updated_at=? WHERE steering_id=? AND state='QUEUED' AND queue_version=?",
                    at.toString(),queue.steeringId().value(),queue.version())!=1)
                if(attemptPaused)throw new SQLException(
                        "Steering claim CAS conflict after Attempt pause");
                else return ApplicationResult.of(Status.VERSION_CONFLICT);
            Queue claimed=readQueue(queue.steeringId()).orElseThrow();
            insertState(queue.steeringId(),queue.taskId(),queue.target().runId(),claimed.version(),State.APPLYING,"SAFE_POINT_CLAIM",at);
            Application application=classification==Classification.MATERIAL
                    ?createMaterialDraft(definition,claimed,execution,at,safePoint,materialDraft)
                    :createConstraint(definition,claimed,execution,at,safePoint);
            if(update("UPDATE steering_queue_authority SET state='APPLIED',queue_version=queue_version+1,reason_code='APPLICATION_COMMITTED',updated_at=? WHERE steering_id=? AND state='APPLYING' AND queue_version=?",
                    at.toString(),queue.steeringId().value(),claimed.version())!=1)
                throw new SQLException("Steering application terminal CAS conflict");
            Queue applied=readQueue(queue.steeringId()).orElseThrow();
            insertState(queue.steeringId(),queue.taskId(),queue.target().runId(),applied.version(),State.APPLIED,"APPLICATION_COMMITTED",at);
            return new ApplicationResult(Status.SUCCESS,application,
                    classification==Classification.MATERIAL,null);
        });
    }

    public String activeConstraintContext(CanonicalId taskId) {
        try(PreparedStatement s=connection.prepareStatement("""
                SELECT c.canonical_content,c.canonical_hash FROM task_constraint_activations a
                JOIN constraint_revisions c ON c.task_id=a.task_id AND c.constraint_revision_id=a.active_revision_id
                WHERE a.task_id=?""")){s.setString(1,taskId.value());try(ResultSet r=s.executeQuery()){
            if(!r.next())return "";String content=r.getString(1);
            if(!SteeringClassifier.sha(content).equals(r.getString(2)))throw new IllegalStateException("Constraint integrity failure");
            return content;}}
        catch(SQLException error){throw new IllegalStateException("Constraint authority read failed",error);}
    }

    private Application createConstraint(Definition definition, Queue queue,
            ExecutionAuthoritySnapshot execution, Instant at, String safePoint) throws SQLException {
        String parent=null,parentContent="";long expectedPointer=0;
        try(PreparedStatement s=connection.prepareStatement("""
                SELECT a.active_revision_id,a.pointer_version,c.canonical_content
                FROM task_constraint_activations a JOIN constraint_revisions c
                  ON c.task_id=a.task_id AND c.constraint_revision_id=a.active_revision_id
                WHERE a.task_id=?""")){s.setString(1,queue.taskId().value());try(ResultSet r=s.executeQuery()){
            if(r.next()){parent=r.getString(1);expectedPointer=r.getLong(2);parentContent=r.getString(3);}}}
        String content=(parentContent.isBlank()?"":parentContent+"\n")
                +"[Steering "+definition.steeringId().value()+"]\n"+definition.content();
        if(content.length()>8192)content=content.substring(content.length()-8192);
        String hash=SteeringClassifier.sha(content);
        String revisionId="constraint-r1d-"+definition.steeringId().value().substring(4);
        long revisionNumber=longValue("SELECT COALESCE(max(revision_number),0)+1 FROM constraint_revisions WHERE task_id=?",queue.taskId().value());
        insert("INSERT INTO constraint_revisions(constraint_revision_id,task_id,revision_number,canonical_content,canonical_hash,created_at) VALUES(?,?,?,?,?,?)",
                revisionId,queue.taskId().value(),revisionNumber,content,hash,at.toString());
        if(parent==null){
            insert("INSERT INTO task_constraint_activations(task_id,active_revision_id,activated_by_run_id,pointer_version,activated_at) VALUES(?,?,?,?,?)",
                    queue.taskId().value(),revisionId,execution.runId().value(),1,at.toString());
        }else if(update("UPDATE task_constraint_activations SET active_revision_id=?,activated_by_run_id=?,pointer_version=pointer_version+1,activated_at=? WHERE task_id=? AND active_revision_id=? AND pointer_version=?",
                revisionId,execution.runId().value(),at.toString(),queue.taskId().value(),parent,expectedPointer)!=1){
            throw new SQLException("Constraint activation CAS conflict");
        }
        insert("INSERT INTO steering_constraint_provenance(steering_id,task_id,constraint_revision_id,parent_constraint_revision_id,target_plan_revision_id,canonical_hash,created_at) VALUES(?,?,?,?,?,?,?)",
                definition.steeringId().value(),queue.taskId().value(),revisionId,parent,
                value(queue.target().planRevisionId()),hash,at.toString());
        ApplicationKind kind=queue.target().planRevisionId()==null
                ?ApplicationKind.PLAN_DISABLED_CONSTRAINT:ApplicationKind.CONSTRAINT_REVISION;
        String appHash=SteeringClassifier.sha(definition.steeringId().value()+"|"+queue.version()+"|"+revisionId+"|"+hash);
        insert("INSERT INTO steering_application_facts(steering_id,task_id,applied_by_run_id,queue_version,safe_point,application_kind,constraint_revision_id,plan_revision_id,application_hash,applied_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                definition.steeringId().value(),queue.taskId().value(),execution.runId().value(),queue.version(),safePoint,
                kind.name(),revisionId,null,appHash,at.toString());
        return new Application(definition.steeringId(),queue.taskId(),execution.runId(),queue.version(),safePoint,
                kind,revisionId,null,appHash,at);
    }

    private MaterialPlanRequest materialRequest(Definition definition,Queue queue)
            throws SQLException {
        if(queue.target().planRevisionId()==null) {
            throw new SQLException("Material Steering requires structured Plan authority");
        }
        PlanAuthorityStore.RevisionRead read=new PlanAuthorityStore(connection)
                .readRevisionDefinition(queue.taskId(),queue.target().planRevisionId());
        if(read.status()!=PlanAuthorityStore.Status.SUCCESS)throw new SQLException("Source Plan integrity failure");
        int number=(int)longValue("SELECT COALESCE(max(revision_number),0)+1 FROM plan_revisions WHERE task_id=?",queue.taskId().value());
        return new MaterialPlanRequest(definition,read.revision(),number);
    }

    private Status validateMaterialDraft(MaterialPlanRequest request,
            StructuredPlanModels.Revision draft,ExecutionAuthoritySnapshot execution){
        var source=request.sourceRevision();
        String provenance="R1D_STEERING:"+request.definition().steeringId().value();
        if(!draft.taskId().equals(source.taskId())
                ||!draft.createdByRunId().equals(execution.runId())
                ||draft.revisionNumber()!=request.revisionNumber()
                ||!"STEERING".equals(draft.sourceType())
                ||!provenance.equals(draft.provenance())
                ||!draft.goal().contains(request.definition().content())
                ||!draft.canonicalHash().equals(PlanCanonicalizer.hash(draft)))
            return Status.INTEGRITY_VIOLATION;
        Set<CanonicalId> oldIds=source.steps().stream()
                .flatMap(step->java.util.stream.Stream.of(
                        step.stepRevisionId(),step.logicalStepId()))
                .collect(java.util.stream.Collectors.toSet());
        if(draft.steps().stream().anyMatch(step->oldIds.contains(step.stepRevisionId())
                ||oldIds.contains(step.logicalStepId())))return Status.INTEGRITY_VIOLATION;
        boolean copied=draft.steps().size()==source.steps().size();
        if(copied)for(int index=0;index<draft.steps().size();index++){
            var before=source.steps().get(index);var after=draft.steps().get(index);
            if(!before.title().equals(after.title())
                    ||!before.definition().equals(after.definition())
                    ||before.completionRule()!=after.completionRule()
                    ||!before.requiredEvidenceKinds().equals(after.requiredEvidenceKinds())
                    ||before.effectClass()!=after.effectClass()){copied=false;break;}
        }
        if(copied)return Status.INTEGRITY_VIOLATION;
        long added=draft.lineage().stream().filter(lineage->
                lineage.relationship()==StructuredPlanModels.LineageRelationship.ADDED
                &&lineage.sourcePlanRevisionId()==null
                &&draft.planRevisionId().equals(lineage.targetPlanRevisionId())).count();
        long removed=draft.lineage().stream().filter(lineage->
                lineage.relationship()==StructuredPlanModels.LineageRelationship.REMOVED
                &&source.planRevisionId().equals(lineage.sourcePlanRevisionId())
                &&draft.planRevisionId().equals(lineage.targetPlanRevisionId())).count();
        return added==draft.steps().size()&&removed==source.steps().size()
                ?Status.SUCCESS:Status.INTEGRITY_VIOLATION;
    }

    private Application createMaterialDraft(Definition definition, Queue queue,
            ExecutionAuthoritySnapshot execution, Instant at, String safePoint,
            StructuredPlanModels.Revision draft) throws SQLException {
        CanonicalId planId=draft.planRevisionId();
        PlanAuthorityStore.Mutation proposed=PlanAuthorityStore.proposeInTransaction(
                connection,execution,at,draft);
        if(proposed.status()!=PlanAuthorityStore.Status.SUCCESS)
            throw new SQLException("Material Steering Plan proposal rejected: "
                    +proposed.status());
        String appHash=SteeringClassifier.sha(definition.steeringId().value()+"|"+queue.version()+"|"+planId.value()+"|"+draft.canonicalHash());
        insert("INSERT INTO steering_application_facts(steering_id,task_id,applied_by_run_id,queue_version,safe_point,application_kind,constraint_revision_id,plan_revision_id,application_hash,applied_at) VALUES(?,?,?,?,?,'PLAN_DRAFT',NULL,?,?,?)",
                definition.steeringId().value(),queue.taskId().value(),execution.runId().value(),queue.version(),safePoint,
                planId.value(),appHash,at.toString());
        return new Application(definition.steeringId(),queue.taskId(),execution.runId(),queue.version(),safePoint,
                ApplicationKind.PLAN_DRAFT,null,planId,appHash,at);
    }

    private Status validateAdmissionTarget(Target target) throws SQLException {
        boolean terminal;
        try(PreparedStatement s=connection.prepareStatement("SELECT status,thread_id FROM task_identities WHERE task_id=?")){
            s.setString(1,target.taskId().value());try(ResultSet r=s.executeQuery()){if(!r.next())return Status.NOT_FOUND;
                if(!target.threadId().value().equals(r.getString(2)))return Status.INVALID_SCOPE;
                terminal=!Set.of("CREATED","RUNNING").contains(r.getString(1));}}
        if(!exists("SELECT 1 FROM durable_runs WHERE task_id=? AND thread_id=? AND run_id=?",
                target.taskId().value(),target.threadId().value(),target.runId().value()))return Status.RUN_MISMATCH;
        if(!exists("SELECT 1 FROM task_context_pointers WHERE task_id=? AND active_generation_id=? AND owner_run_id=? AND pointer_version=?",
                target.taskId().value(),target.generationId().value(),target.runId().value(),target.contextPointerVersion()))return Status.POINTER_MISMATCH;
        String applicability=textValue("SELECT applicability FROM task_plan_applicability WHERE task_id=?",target.taskId().value());
        if("LEGACY_PLAN_ONLY".equals(applicability))return Status.LEGACY_UNAVAILABLE;
        if("PLAN_REQUIRED".equals(applicability)){
            if(target.planRevisionId()==null)return Status.PLAN_MISMATCH;
            if(!exists("""
                    SELECT 1 FROM task_plan_authorities a JOIN plan_revisions p
                    ON p.task_id=a.task_id AND p.plan_revision_id=a.active_plan_revision_id
                    WHERE a.task_id=? AND a.active_plan_revision_id=? AND p.canonical_hash=? AND a.pointer_version=?""",
                    target.taskId().value(),target.planRevisionId().value(),target.planHash(),target.planPointerVersion()))
                return Status.PLAN_MISMATCH;
        }else if("PLAN_DISABLED".equals(applicability)){
            if(target.planRevisionId()!=null)return Status.PLAN_MISMATCH;
        }else return Status.INVALID_SCOPE;
        return terminal?Status.TERMINAL_TASK:Status.SUCCESS;
    }

    private Status validateApplicationTarget(Queue queue) throws SQLException {
        if(isTerminalTask(queue.taskId()))return Status.TERMINAL_TASK;
        if(!exists("SELECT 1 FROM task_context_pointers WHERE task_id=? AND active_generation_id=? AND owner_run_id=? AND pointer_version=?",
                queue.taskId().value(),queue.target().generationId().value(),queue.target().runId().value(),
                queue.target().contextPointerVersion()))return Status.POINTER_MISMATCH;
        if(queue.target().planRevisionId()!=null&&!exists("""
                SELECT 1 FROM task_plan_authorities a JOIN plan_revisions p
                ON p.task_id=a.task_id AND p.plan_revision_id=a.active_plan_revision_id
                WHERE a.task_id=? AND a.active_plan_revision_id=? AND p.canonical_hash=? AND a.pointer_version=?""",
                queue.taskId().value(),queue.target().planRevisionId().value(),queue.target().planHash(),
                queue.target().planPointerVersion()))return Status.PLAN_MISMATCH;
        return Status.SUCCESS;
    }

    private boolean unsafeDurableActivity(CanonicalId taskId,CanonicalId runId)throws SQLException{
        return exists("SELECT 1 FROM side_effect_occurrences WHERE task_id=? AND status IN ('STARTED','UNKNOWN')",taskId.value())
                ||exists("SELECT 1 FROM mcp_tool_calls WHERE task_id=? AND coding_run_id=? AND (status='RUNNING' OR (status='PENDING' AND approval_decision='PENDING'))",taskId.value(),runId.value())
                ||exists("SELECT 1 FROM verification_runs WHERE task_id=? AND run_id=? AND status='RUNNING'",taskId.value(),runId.value())
                ||exists("SELECT 1 FROM repair_attempts WHERE task_id=? AND run_id=? AND status='RUNNING'",taskId.value(),runId.value())
                ||exists("SELECT 1 FROM hook_runs WHERE task_id=? AND run_id=? AND status='RUNNING'",taskId.value(),runId.value())
                ||exists("SELECT 1 FROM file_snapshots WHERE task_id=? AND owner_run_id=? AND status='CAPTURED'",taskId.value(),runId.value());
    }

    private Status lockAuthority(ExecutionAuthoritySnapshot a,Instant at)throws SQLException{
        if(update("""
                UPDATE task_ownership_leases SET heartbeat_at=heartbeat_at WHERE task_id=?
                AND owner_instance_id=? AND owner_run_id=? AND lease_token=? AND fencing_token=?
                AND cas_version=? AND state='ACTIVE' AND expires_at>?""",a.taskId().value(),a.ownerInstanceId(),
                a.runId().value(),a.leaseToken(),a.fencingToken(),a.casVersion(),at.toString())==1)return Status.SUCCESS;
        try(PreparedStatement s=connection.prepareStatement("SELECT owner_instance_id,owner_run_id,lease_token,fencing_token,cas_version,state,expires_at FROM task_ownership_leases WHERE task_id=?")){
            s.setString(1,a.taskId().value());try(ResultSet r=s.executeQuery()){if(!r.next())return Status.NOT_FOUND;
                if(!a.ownerInstanceId().equals(r.getString(1)))return Status.OWNER_MISMATCH;
                if(!a.runId().value().equals(r.getString(2)))return Status.RUN_MISMATCH;
                if(!a.leaseToken().equals(r.getString(3)))return Status.LEASE_TOKEN_MISMATCH;
                if(a.fencingToken()!=r.getLong(4))return Status.FENCING_REJECTED;
                if(a.casVersion()!=r.getLong(5))return Status.VERSION_CONFLICT;
                if(!at.isBefore(Instant.parse(r.getString(7))))return Status.LEASE_EXPIRED;
                return Status.INVALID_STATE;}}
    }

    private void insertDefinition(Definition d)throws SQLException{
        Target t=d.target();insert("""
                INSERT INTO steering_definitions(steering_id,thread_id,task_id,target_run_id,
                target_plan_revision_id,target_plan_hash,target_plan_pointer_version,target_generation_id,
                target_context_pointer_version,content,content_hash,provenance,input_owner,input_session_id,created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",d.steeringId().value(),t.threadId().value(),t.taskId().value(),
                t.runId().value(),value(t.planRevisionId()),t.planHash(),t.planPointerVersion(),t.generationId().value(),
                t.contextPointerVersion(),d.content(),d.contentHash(),d.provenance().name(),d.inputOwner(),
                d.inputSessionId(),d.createdAt().toString());
    }
    private void insertClassification(CanonicalId task,CanonicalId steering,int number,
            Classification classification,ClassificationOutcome outcome,String version,String reason,Instant at)throws SQLException{
        insert("INSERT INTO steering_classification_facts(steering_id,fact_number,task_id,classification,outcome,classifier_version,reason_code,created_at) VALUES(?,?,?,?,?,?,?,?)",
                steering.value(),number,task.value(),classification.name(),outcome.name(),version,reason,at.toString());
    }
    private void insertQueue(Definition d,State state,Long sequence,String reason,Instant at)throws SQLException{
        Target t=d.target();insert("""
                INSERT INTO steering_queue_authority(steering_id,task_id,target_run_id,
                target_plan_revision_id,target_plan_hash,target_plan_pointer_version,target_generation_id,
                target_context_pointer_version,state,queue_sequence,queue_version,reason_code,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,0,?,?)""",d.steeringId().value(),t.taskId().value(),t.runId().value(),
                value(t.planRevisionId()),t.planHash(),t.planPointerVersion(),t.generationId().value(),
                t.contextPointerVersion(),state.name(),sequence,reason,at.toString());
    }
    private void insertState(CanonicalId steering,CanonicalId task,CanonicalId run,long version,
            State state,String reason,Instant at)throws SQLException{
        insert("INSERT INTO steering_state_facts(steering_id,state_number,task_id,target_run_id,queue_version,state,reason_code,recorded_at) VALUES(?,?,?,?,?,?,?,?)",
                steering.value(),nextFact("steering_state_facts","state_number",steering),task.value(),run.value(),version,state.name(),reason,at.toString());
    }
    private void transition(Queue queue,State state,Long sequence,String reason,Instant at)throws SQLException{
        if(update("""
                UPDATE steering_queue_authority SET state=?,queue_sequence=COALESCE(?,queue_sequence),
                queue_version=queue_version+1,reason_code=?,updated_at=? WHERE steering_id=? AND queue_version=?""",
                state.name(),sequence,reason,at.toString(),queue.steeringId().value(),queue.version())!=1)
            throw new SQLException("Steering queue CAS conflict");
        Queue after=readQueue(queue.steeringId()).orElseThrow();
        insertState(after.steeringId(),after.taskId(),after.target().runId(),after.version(),state,reason,at);
    }
    private Mutation reject(Queue queue,String reason,Instant at)throws SQLException{
        transition(queue,State.REJECTED,queue.sequence(),reason,at);
        return new Mutation(Status.TERMINAL_TASK,queue.steeringId(),readQueue(queue.steeringId()).orElseThrow());
    }
    private Classification latestClassification(CanonicalId steering)throws SQLException{
        try(PreparedStatement s=connection.prepareStatement("SELECT classification FROM steering_classification_facts WHERE steering_id=? ORDER BY fact_number DESC LIMIT 1")){
            s.setString(1,steering.value());try(ResultSet r=s.executeQuery()){if(!r.next())throw new SQLException("Missing classification fact");
                return Classification.valueOf(r.getString(1));}}
    }
    private boolean definitionPrecedes(CanonicalId first,CanonicalId second)throws SQLException{
        try(PreparedStatement s=connection.prepareStatement("""
                SELECT steering_id FROM steering_definitions
                WHERE steering_id IN (?,?) ORDER BY created_at,steering_id LIMIT 1""")){
            s.setString(1,first.value());s.setString(2,second.value());try(ResultSet r=s.executeQuery()){
                return r.next()&&first.value().equals(r.getString(1));}}
    }
    private Status validateApplicationOrder(CanonicalId steering,CanonicalId task)throws SQLException{
        try(PreparedStatement s=connection.prepareStatement("SELECT steering_id FROM steering_queue_authority WHERE task_id=? AND state NOT IN ('APPLIED','REJECTED','CANCELLED') ORDER BY CASE WHEN queue_sequence IS NULL THEN 0 ELSE 1 END,queue_sequence,updated_at LIMIT 1")){
            s.setString(1,task.value());try(ResultSet r=s.executeQuery()){
                return r.next()&&steering.value().equals(r.getString(1))?Status.SUCCESS:Status.ORDER_BLOCKED;}}
    }
    private boolean isTerminalTask(CanonicalId task)throws SQLException{
        String status=textValue("SELECT status FROM task_identities WHERE task_id=?",task.value());
        return status!=null&&!Set.of("CREATED","RUNNING").contains(status);
    }
    private long nextSequence()throws SQLException{return longValue("SELECT COALESCE(max(queue_sequence),0)+1 FROM steering_queue_authority");}
    private int nextFact(String table,String column,CanonicalId steering)throws SQLException{
        String allowed=switch(table){case "steering_state_facts"->"steering_state_facts";case "steering_confirmation_facts"->"steering_confirmation_facts";default->throw new SQLException("Invalid fact table");};
        String allowedColumn=switch(column){case "state_number"->"state_number";case "confirmation_number"->"confirmation_number";default->throw new SQLException("Invalid fact column");};
        return (int)longValue("SELECT COALESCE(max("+allowedColumn+"),0)+1 FROM "+allowed+" WHERE steering_id=?",steering.value());
    }
    private Target target(ResultSet r,int offset)throws SQLException{
        return new Target(CanonicalId.parse(r.getString(offset)),CanonicalId.parse(r.getString(offset+1)),
                CanonicalId.parse(r.getString(offset+2)),parse(r.getString(offset+3)),r.getString(offset+4),
                nullableLong(r,offset+5),CanonicalId.parse(r.getString(offset+6)),r.getLong(offset+7));
    }
    private static Long nullableLong(ResultSet r,int column)throws SQLException{long value=r.getLong(column);return r.wasNull()?null:value;}
    private static CanonicalId parse(String value){return value==null?null:CanonicalId.parse(value);}
    private static String value(CanonicalId value){return value==null?null:value.value();}
    private static void require(CanonicalId id,EntityFamily family){if(id==null||id.family()!=family)throw new IllegalArgumentException("Expected "+family);}

    private boolean exists(String sql,Object...values)throws SQLException{
        try(PreparedStatement s=connection.prepareStatement(sql)){bind(s,values);try(ResultSet r=s.executeQuery()){return r.next();}}
    }
    private String textValue(String sql,Object...values)throws SQLException{
        try(PreparedStatement s=connection.prepareStatement(sql)){bind(s,values);try(ResultSet r=s.executeQuery()){return r.next()?r.getString(1):null;}}
    }
    private long longValue(String sql,Object...values)throws SQLException{
        try(PreparedStatement s=connection.prepareStatement(sql)){bind(s,values);try(ResultSet r=s.executeQuery()){if(!r.next())throw new SQLException("Missing scalar");return r.getLong(1);}}
    }
    private int update(String sql,Object...values)throws SQLException{try(PreparedStatement s=connection.prepareStatement(sql)){bind(s,values);return s.executeUpdate();}}
    private void insert(String sql,Object...values)throws SQLException{try(PreparedStatement s=connection.prepareStatement(sql)){bind(s,values);s.executeUpdate();}}
    private static void bind(PreparedStatement s,Object...values)throws SQLException{for(int i=0;i<values.length;i++)s.setObject(i+1,values[i]);}

    private <T> T tx(SqlWork<T> work){return transaction(work,"Steering persistence failed");}
    private ApplicationResult txApplication(SqlWork<ApplicationResult> work){return transaction(work,"Steering application failed");}
    private <T>T transaction(SqlWork<T> work,String message){
        try{if(!connection.getAutoCommit())throw new SQLException("Nested Steering transaction is unsupported");
            connection.setAutoCommit(false);try{T value=work.run();connection.commit();return value;}
            catch(SQLException|RuntimeException error){connection.rollback();throw error;}
            finally{connection.setAutoCommit(true);}}
        catch(SQLException error){throw new IllegalStateException(message,error);}
    }
    @FunctionalInterface private interface SqlWork<T>{T run()throws SQLException;}
}
