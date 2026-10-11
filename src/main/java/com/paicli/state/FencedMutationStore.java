package com.paicli.state;

import com.paicli.identity.CanonicalId;
import com.paicli.model.AgentMessage;
import com.paicli.state.R1aDurableModels.ExecutionAuthoritySnapshot;
import com.paicli.state.R1aStoreResults.FencedMutationResult;
import com.paicli.state.R1aStoreResults.FencedValueResult;
import com.paicli.tool.ToolResult;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.List;
import java.util.ArrayList;

/**
 * Operation-specific Recovery writes. Each operation locks and validates the current ownership
 * row before mutating authority on the same SQLite Connection and transaction.
 */
public final class FencedMutationStore {
    private final Connection connection;

    public FencedMutationStore(Connection connection) {
        this.connection=Objects.requireNonNull(connection);
    }

    public FencedMutationResult prepareTaskForResume(ExecutionAuthoritySnapshot a,Instant at){
        return unchecked(a,at,()->update("""
                UPDATE tasks SET status='RUNNING',last_error=NULL,completed_at=NULL,
                    updated_at=datetime('now')
                WHERE task_id=? AND status NOT IN ('COMPLETED_VERIFIED','COMPLETED_UNVERIFIED',
                    'REJECTED','CANCELLED','CANCELLED_UNKNOWN_OUTCOME')
                """,a.taskId().value()));
    }

    public FencedMutationResult createCodingRun(ExecutionAuthoritySnapshot a,Instant at,
            String verificationCommand,int maxRepairAttempts,int startedIteration){
        return createCodingRun(a,at,CodingRunStore.PHASE_PLANNING,verificationCommand,
                maxRepairAttempts,startedIteration);
    }

    public FencedMutationResult createCodingRun(ExecutionAuthoritySnapshot a,Instant at,
            String initialPhase,String verificationCommand,int maxRepairAttempts,
            int startedIteration){
        if(!CodingRunStore.PHASE_PLANNING.equals(initialPhase)
                &&!CodingRunStore.PHASE_EXECUTING.equals(initialPhase))
            throw new IllegalArgumentException("Invalid initial coding run phase: "+initialPhase);
        return unchecked(a,at,()->insert("""
                INSERT INTO coding_runs(run_id,task_id,run_kind,phase,verification_command,
                    max_repair_attempts,started_iteration,last_iteration)
                VALUES(?,?,'RESUME',?,?,?,?,?)
                """,a.runId().value(),a.taskId().value(),initialPhase,verificationCommand,
                maxRepairAttempts,startedIteration,startedIteration));
    }

    public FencedMutationResult updateRuntimeManifest(ExecutionAuthoritySnapshot a,Instant at,
            String skillsHash,String hooksHash){
        return unchecked(a,at,()->update("""
                UPDATE coding_runs SET skills_manifest_sha256=?,hooks_manifest_sha256=?,
                    updated_at=datetime('now')
                WHERE run_id=? AND task_id=? AND phase NOT IN ('COMPLETED','FAILED')
                """,skillsHash,hooksHash,a.runId().value(),a.taskId().value()));
    }

    public FencedMutationResult markTaskRunning(ExecutionAuthoritySnapshot a,Instant at){
        return unchecked(a,at,()->update("""
                UPDATE tasks SET status='RUNNING',last_error=NULL,updated_at=datetime('now')
                WHERE task_id=? AND status NOT IN ('COMPLETED_VERIFIED','COMPLETED_UNVERIFIED',
                    'REJECTED','CANCELLED','CANCELLED_UNKNOWN_OUTCOME')
                """,a.taskId().value()));
    }

    public FencedMutationResult updateTaskIterations(ExecutionAuthoritySnapshot a,Instant at,
            int iteration){
        return unchecked(a,at,()->update("""
                UPDATE tasks SET iterations_completed=?,updated_at=datetime('now')
                WHERE task_id=? AND status='RUNNING' AND ?>=iterations_completed
                """,iteration,a.taskId().value(),iteration));
    }

    public FencedMutationResult appendEvent(ExecutionAuthoritySnapshot a,Instant at,int iteration,
            AgentMessage message){
        Objects.requireNonNull(message);
        return unchecked(a,at,()->insert("""
                INSERT INTO events(task_id,iteration,role,type,content,tool_call_id,tool_call_json)
                VALUES(?,?,?,?,?,?,?)
                """,a.taskId().value(),iteration,message.role().name(),message.type().name(),
                message.content(),message.toolCallId(),message.toolCallJson()));
    }

    public FencedMutationResult recordToolCall(ExecutionAuthoritySnapshot a,Instant at,int iteration,
            String toolCallId,String toolName,String arguments,ToolResult result){
        Objects.requireNonNull(result);
        return unchecked(a,at,()->insert("""
                INSERT INTO tool_calls(task_id,iteration,tool_call_id,tool_name,arguments,
                    result_success,result_output,result_error) VALUES(?,?,?,?,?,?,?,?)
                """,a.taskId().value(),iteration,toolCallId,toolName,arguments,
                result.success()?1:0,result.output(),result.error()));
    }

    public FencedMutationResult recordApproval(ExecutionAuthoritySnapshot a,Instant at,
            Integer iteration,String toolCallId,String toolName,String risk,String decision,
            String reason){
        return recordApproval(a,at,iteration,toolCallId,toolName,risk,decision,reason,null);
    }

    public FencedMutationResult recordApproval(ExecutionAuthoritySnapshot a,Instant at,
            Integer iteration,String toolCallId,String toolName,String risk,String decision,
            String reason,String intentHash){
        return unchecked(a,at,()->insert("""
                INSERT INTO approval_decisions(task_id,iteration,tool_call_id,tool_name,
                    risk_level,decision,reason,intent_hash) VALUES(?,?,?,?,?,?,?,?)
                """,a.taskId().value(),iteration,toolCallId,toolName,risk,decision,reason,
                intentHash));
    }

    public FencedMutationResult saveCheckpoint(ExecutionAuthoritySnapshot a,Instant at,
            int iteration,String status,String messagesJson){
        return unchecked(a,at,()->execute("""
                INSERT INTO checkpoints(task_id,iteration,status,messages_json) VALUES(?,?,?,?)
                ON CONFLICT(task_id) DO UPDATE SET iteration=excluded.iteration,
                    status=excluded.status,messages_json=excluded.messages_json,
                    created_at=datetime('now')
                """,a.taskId().value(),iteration,status,messagesJson));
    }

    public FencedMutationResult updateLastIteration(ExecutionAuthoritySnapshot a,Instant at,
            int iteration){
        return unchecked(a,at,()->update("""
                UPDATE coding_runs SET last_iteration=?,updated_at=datetime('now')
                WHERE run_id=? AND task_id=? AND ?>=last_iteration
                    AND phase NOT IN ('COMPLETED','FAILED')
                """,iteration,a.runId().value(),a.taskId().value(),iteration));
    }

    public FencedMutationResult transitionRunPhase(ExecutionAuthoritySnapshot a,Instant at,
            String expected,String next){
        return unchecked(a,at,()->update("""
                UPDATE coding_runs SET phase=?,updated_at=datetime('now')
                WHERE run_id=? AND task_id=? AND phase=?
                """,next,a.runId().value(),a.taskId().value(),expected));
    }

    public FencedMutationResult recordPlan(ExecutionAuthoritySnapshot a,Instant at,String planId,
            String planText){
        return unchecked(a,at,()->insert("""
                INSERT INTO task_plans(plan_id,run_id,task_id,plan_text,status)
                VALUES(?,?,?,?,'GENERATED')
                """,planId,a.runId().value(),a.taskId().value(),planText));
    }

    public FencedMutationResult transitionPlan(ExecutionAuthoritySnapshot a,Instant at,
            String planId,String expected,String next){
        return unchecked(a,at,()->update("""
                UPDATE task_plans SET status=?,updated_at=datetime('now')
                WHERE plan_id=? AND run_id=? AND task_id=? AND status=?
                """,next,planId,a.runId().value(),a.taskId().value(),expected));
    }

    public FencedMutationResult startRepair(ExecutionAuthoritySnapshot a,Instant at,String repairId,
            int attempt,String verificationId,String summary){
        return unchecked(a,at,()->insert("""
                INSERT INTO repair_attempts(repair_id,run_id,task_id,attempt_no,
                    trigger_verification_id,status,failure_summary)
                VALUES(?,?,?,?,?,'RUNNING',?)
                """,repairId,a.runId().value(),a.taskId().value(),attempt,verificationId,summary));
    }

    public FencedValueResult finishRepair(ExecutionAuthoritySnapshot a,Instant at,String repairId,
            String status){
        try{
            final long[] value={-1};
            FencedMutationResult result=mutate(a,at,()->{
                FencedMutationResult first=update("""
                        UPDATE repair_attempts SET status=?,completed_at=datetime('now')
                        WHERE repair_id=? AND run_id=? AND task_id=? AND status='RUNNING'
                        """,status,repairId,a.runId().value(),a.taskId().value());
                if(first!=FencedMutationResult.SUCCESS)return first;
                FencedMutationResult second=update("""
                        UPDATE coding_runs SET repair_attempts_completed=repair_attempts_completed+1,
                            updated_at=datetime('now')
                        WHERE run_id=? AND task_id=?
                            AND repair_attempts_completed<max_repair_attempts
                            AND phase NOT IN ('COMPLETED','FAILED')
                        """,a.runId().value(),a.taskId().value());
                if(second!=FencedMutationResult.SUCCESS)return second;
                try(PreparedStatement s=connection.prepareStatement(
                        "SELECT repair_attempts_completed FROM coding_runs WHERE run_id=? AND task_id=?")){
                    s.setString(1,a.runId().value());s.setString(2,a.taskId().value());
                    try(ResultSet rows=s.executeQuery()){if(!rows.next())return FencedMutationResult.TARGET_NOT_FOUND;
                        value[0]=rows.getLong(1);}
                }
                return FencedMutationResult.SUCCESS;
            });
            return result==FencedMutationResult.SUCCESS?new FencedValueResult.Success(value[0])
                    :new FencedValueResult.Rejected(result);
        }catch(SQLException e){throw infrastructure(e);}
    }

    public FencedMutationResult startVerification(ExecutionAuthoritySnapshot a,Instant at,
            String id,int attempt,String command,String status,String candidateDigestKind,
            String candidateDigest,String error){
        return unchecked(a,at,()->insert("""
                INSERT INTO verification_runs(verification_id,run_id,task_id,repair_attempt,
                    command,status,candidate_digest_kind,candidate_digest,error_excerpt,duration_ms,
                    completed_at)
                VALUES(?,?,?,?,?,?,?,?,?,CASE WHEN ? IN ('SKIPPED','ERROR') THEN 0 END,
                    CASE WHEN ? IN ('SKIPPED','ERROR') THEN datetime('now') END)
                """,id,a.runId().value(),a.taskId().value(),attempt,command,status,
                candidateDigestKind,candidateDigest,error,status,status));
    }

    public FencedMutationResult finishVerification(ExecutionAuthoritySnapshot a,Instant at,
            String id,String status,Integer exitCode,String output,String error,long duration){
        return unchecked(a,at,()->{
            FencedMutationResult result=update("""
                    UPDATE verification_runs SET status=?,exit_code=?,output_excerpt=?,error_excerpt=?,
                        duration_ms=?,completed_at=datetime('now')
                    WHERE verification_id=? AND run_id=? AND task_id=? AND status='RUNNING'
                    """,status,exitCode,output,error,duration,id,a.runId().value(),a.taskId().value());
            if(result==FencedMutationResult.SUCCESS
                    &&VerificationStore.STATUS_PASSED.equals(status))
                PlanCompletionStore.recordVerificationEvidence(connection,a,at,id);
            return result;
        });
    }

    public UserResultMutation persistUserResult(ExecutionAuthoritySnapshot a,Instant at,
            CanonicalId revisionId,String mode,String content){
        UserResultStore.Result[] value=new UserResultStore.Result[1];
        try{
            FencedMutationResult status=mutate(a,at,()->{
                value[0]=new UserResultStore(connection).persist(a.taskId(),a.runId(),revisionId,
                        mode,content,at);
                return FencedMutationResult.SUCCESS;
            });
            return new UserResultMutation(status,value[0]);
        }catch(SQLException error){throw infrastructure(error);}
    }

    public UserResultMutation persistPlanlessUserResult(ExecutionAuthoritySnapshot a,Instant at,
            CanonicalId providerOccurrenceId,String candidateDigest,String verificationId,
            String mode,String content){
        UserResultStore.Result[] value=new UserResultStore.Result[1];
        try{
            FencedMutationResult status=mutate(a,at,()->{
                if(candidateDigest==null||!candidateDigest.matches("[0-9a-f]{64}")
                        ||content==null||content.isBlank())return FencedMutationResult.INVALID_STATE;
                try(PreparedStatement occurrence=connection.prepareStatement("""
                        SELECT 1 FROM side_effect_occurrences
                        WHERE occurrence_id=? AND task_id=? AND run_id=?
                            AND operation_type='PROVIDER' AND status='COMPLETED'
                            AND result_hash=? AND final_candidate_digest=?
                        """)){
                    bind(occurrence,new Object[]{providerOccurrenceId.value(),a.taskId().value(),
                            a.runId().value(),providerFinalResultHash(content),candidateDigest});
                    try(ResultSet row=occurrence.executeQuery()){
                        if(!row.next())return FencedMutationResult.INVALID_STATE;
                    }
                }
                String verificationEvidence;
                if(verificationId==null){
                    try(PreparedStatement run=connection.prepareStatement("""
                            SELECT 1 FROM coding_runs WHERE run_id=? AND task_id=?
                                AND phase='VERIFYING'
                                AND length(trim(COALESCE(verification_command,'')))=0
                            """)){
                        bind(run,new Object[]{a.runId().value(),a.taskId().value()});
                        try(ResultSet row=run.executeQuery()){
                            if(!row.next())return FencedMutationResult.INVALID_STATE;
                        }
                    }
                    verificationEvidence="UNVERIFIED";
                }else{
                    try(PreparedStatement verification=connection.prepareStatement("""
                            SELECT 1 FROM verification_runs
                            WHERE verification_id=? AND task_id=? AND run_id=?
                                AND status='PASSED' AND candidate_digest=?
                            """)){
                        bind(verification,new Object[]{verificationId,a.taskId().value(),
                                a.runId().value(),candidateDigest});
                        try(ResultSet row=verification.executeQuery()){
                            if(!row.next())return FencedMutationResult.INVALID_STATE;
                        }
                    }
                    verificationEvidence="PASSED:"+verificationId;
                }
                value[0]=new UserResultStore(connection).persistPlanless(a.taskId(),a.runId(),
                        mode,content,providerOccurrenceId.value(),candidateDigest,
                        verificationEvidence,at);
                return FencedMutationResult.SUCCESS;
            });
            return new UserResultMutation(status,value[0]);
        }catch(SQLException error){throw infrastructure(error);}
    }

    public FencedMutationResult markUserResultRendered(ExecutionAuthoritySnapshot a,Instant at,
            String contentHash){
        return unchecked(a,at,()->{
            new UserResultStore(connection).markRendered(a.taskId(),a.runId(),contentHash,at);
            return FencedMutationResult.SUCCESS;
        });
    }

    public FencedMutationResult terminalize(ExecutionAuthoritySnapshot a,Instant at,String planId,
            boolean success,String message){
        try{return mutate(a,at,()->{
            if(success&&structuredCompletionRequired(a))
                return FencedMutationResult.INVALID_STATE;
            if(success){
                boolean advisory=advisoryResultRequired(a,planId);
                if(!renderedUserResultExists(a,advisory?planId:null))
                    return FencedMutationResult.INVALID_STATE;
            }
            if(success){
                try(PreparedStatement steering=connection.prepareStatement("""
                        SELECT 1
                        FROM steering_queue_authority WHERE task_id=?
                        AND state IN ('NEEDS_CONFIRMATION','QUEUED','DEFERRED','APPLYING') LIMIT 1""")){
                    steering.setString(1,a.taskId().value());
                    try(ResultSet row=steering.executeQuery()){
                        if(row.next())return FencedMutationResult.INVALID_STATE;
                    }
                }
            }
            if(planId!=null&&!planId.startsWith("pln_")){
                FencedMutationResult plan=update("""
                        UPDATE task_plans SET status=?,updated_at=datetime('now')
                        WHERE plan_id=? AND run_id=? AND task_id=?
                            AND status IN ('GENERATED','ACTIVE')
                        """,success?"COMPLETED":"FAILED",planId,a.runId().value(),a.taskId().value());
                if(plan!=FencedMutationResult.SUCCESS)return plan;
            }
            FencedMutationResult run=update("""
                    UPDATE coding_runs SET phase=?,last_error=?,updated_at=datetime('now'),
                        completed_at=datetime('now')
                    WHERE run_id=? AND task_id=? AND phase NOT IN ('COMPLETED','FAILED')
                    """,success?"COMPLETED":"FAILED",success?null:message,
                    a.runId().value(),a.taskId().value());
            if(run!=FencedMutationResult.SUCCESS)return run;
            FencedMutationResult durableRun=update("""
                    UPDATE durable_runs SET terminal_status=?
                    WHERE run_id=? AND task_id=? AND terminal_status IS NULL
                    """,success?"COMPLETED":"FAILED",a.runId().value(),a.taskId().value());
            if(durableRun!=FencedMutationResult.SUCCESS)return durableRun;
            FencedMutationResult durableTask=update("""
                    UPDATE task_identities SET status=?,terminal_at=?
                    WHERE task_id=? AND status NOT IN ('COMPLETED','FAILED','CANCELLED','INTERRUPTED','UNKNOWN')""",
                    success?"COMPLETED":"FAILED",at.toString(),a.taskId().value());
            if(durableTask!=FencedMutationResult.SUCCESS)return durableTask;
            SteeringStore.terminalizeOpen(connection,a.taskId(),"REJECTED",
                    success?"TASK_COMPLETED":"TASK_FAILED",at);
            return update("""
                    UPDATE tasks SET status=?,last_error=?,updated_at=datetime('now'),
                        completed_at=datetime('now') WHERE task_id=? AND status='RUNNING'
                    """,success?"DONE":"FAILED",success?null:message,a.taskId().value());
        });}catch(SQLException e){throw infrastructure(e);}
    }

    public FencedMutationResult refineInteractiveTerminal(ExecutionAuthoritySnapshot a,Instant at,
            String status){
        return unchecked(a,at,()->update("""
                UPDATE tasks SET status=?,updated_at=datetime('now'),completed_at=datetime('now')
                WHERE task_id=? AND status='DONE'
                """,status,a.taskId().value()));
    }

    public FencedMutationResult cancel(ExecutionAuthoritySnapshot a,Instant at,String planId,
            boolean unknown,String reason){
        try{return mutate(a,at,()->{
            if(planId!=null&&!planId.startsWith("pln_")){
                FencedMutationResult plan=update("""
                        UPDATE task_plans SET status='FAILED',updated_at=datetime('now')
                        WHERE plan_id=? AND run_id=? AND task_id=?
                            AND status IN ('GENERATED','ACTIVE')
                        """,planId,a.runId().value(),a.taskId().value());
                if(plan!=FencedMutationResult.SUCCESS)return plan;
            }
            execute("""
                    UPDATE plan_step_executions
                    SET state=CASE WHEN state='RUNNING' AND ? THEN 'BLOCKED' ELSE 'CANCELLED' END,
                        transition_version=transition_version+1,last_updated_by_run_id=?,
                        updated_at=?,finished_at=?,bounded_reason=substr(?,1,512)
                    WHERE task_id=? AND plan_revision_id=(
                        SELECT b.plan_revision_id FROM plan_run_bindings b
                        JOIN task_plan_authorities active ON active.task_id=b.task_id
                          AND active.active_plan_revision_id=b.plan_revision_id
                        WHERE b.task_id=? AND b.run_id=? AND b.approval_decision='APPROVED')
                      AND state IN ('PENDING','READY','RUNNING')
                    """,unknown?1:0,a.runId().value(),at.toString(),at.toString(),reason,
                    a.taskId().value(),a.taskId().value(),a.runId().value());
            execute("""
                    UPDATE plan_step_attempts
                    SET state=?,transition_version=transition_version+1,end_iteration=NULL,
                        finished_at=?,failure_class=?,bounded_reason=substr(?,1,512)
                    WHERE task_id=? AND run_id=? AND state='RUNNING'
                    """,unknown?"UNKNOWN":"CANCELLED",at.toString(),
                    unknown?"CANCELLED_UNKNOWN_OUTCOME":"CANCELLED",reason,
                    a.taskId().value(),a.runId().value());
            FencedMutationResult run=update("""
                    UPDATE coding_runs SET phase='FAILED',last_error=?,updated_at=datetime('now'),
                        completed_at=datetime('now')
                    WHERE run_id=? AND task_id=? AND phase NOT IN ('COMPLETED','FAILED')
                    """,unknown?"CANCELLED_UNKNOWN_OUTCOME: user requested":"CANCELLED: user requested",
                    a.runId().value(),a.taskId().value());
            if(run!=FencedMutationResult.SUCCESS)return run;
            FencedMutationResult durableTask=update("""
                    UPDATE task_identities SET status=?,terminal_at=?
                    WHERE task_id=? AND status NOT IN ('COMPLETED','FAILED','CANCELLED','INTERRUPTED','UNKNOWN')""",
                    unknown?"UNKNOWN":"CANCELLED",at.toString(),a.taskId().value());
            if(durableTask!=FencedMutationResult.SUCCESS)return durableTask;
            SteeringStore.terminalizeOpen(connection,a.taskId(),"CANCELLED","TASK_CANCELLED",at);
            return update("""
                    UPDATE tasks SET status=?,last_error=?,updated_at=datetime('now'),
                        completed_at=datetime('now') WHERE task_id=? AND status IN ('CREATED','RUNNING')
                    """,unknown?"CANCELLED_UNKNOWN_OUTCOME":"CANCELLED",reason,a.taskId().value());
        });}catch(SQLException e){throw infrastructure(e);}
    }

    public FencedMutationResult admitOccurrence(ExecutionAuthoritySnapshot a,Instant at,
            R1aDurableModels.SideEffectOccurrence occurrence){
        Objects.requireNonNull(occurrence);
        if(!occurrence.taskId().equals(a.taskId())||!occurrence.runId().equals(a.runId()))
            return FencedMutationResult.INTEGRITY_VIOLATION;
        return unchecked(a,at,()->{
            try(PreparedStatement existing=connection.prepareStatement("""
                    SELECT 1 FROM side_effect_occurrences
                    WHERE task_id=? AND logical_operation_key=? AND canonical_input_hash=?
                    """)){
                existing.setString(1,a.taskId().value());
                existing.setString(2,occurrence.logicalOperationKey());
                existing.setString(3,occurrence.canonicalInputHash());
                try(ResultSet rows=existing.executeQuery()){
                    if(rows.next())return FencedMutationResult.ALREADY_EXISTS;
                }
            }
            return insert("""
                INSERT INTO side_effect_occurrences(occurrence_id,thread_id,task_id,run_id,
                    iteration_id,logical_operation_key,tool_call_id,operation_type,target,status,
                    canonical_input_hash,result_hash,external_outcome,started_at,completed_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,occurrence.id().value(),occurrence.threadId().value(),a.taskId().value(),
                a.runId().value(),occurrence.iterationId(),occurrence.logicalOperationKey(),
                occurrence.toolCallId(),occurrence.operationType(),occurrence.target(),"STARTED",
                occurrence.canonicalInputHash(),null,null,occurrence.startedAt().toString(),null);
        });
    }

    public record PlanToolOccurrenceRequest(String toolCallId,String toolName,
            String effectClass,String selectorDigest,String requirementId,int slotOrdinal,
            String iterationId,String logicalOperationKey,String canonicalInputHash){
        public PlanToolOccurrenceRequest{
            if(toolCallId==null||toolCallId.isBlank()||toolName==null||toolName.isBlank()
                    ||effectClass==null||effectClass.isBlank()||selectorDigest==null
                    ||!selectorDigest.matches("[0-9a-f]{64}")||requirementId==null
                    ||requirementId.isBlank()||slotOrdinal<1||iterationId==null
                    ||iterationId.isBlank()||logicalOperationKey==null
                    ||logicalOperationKey.isBlank()||canonicalInputHash==null
                    ||!canonicalInputHash.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid Plan Tool occurrence request");
        }
    }

    public record PlanToolBatchReservation(FencedMutationResult result,
            List<CanonicalId> occurrenceIds){
        public PlanToolBatchReservation{
            Objects.requireNonNull(result);
            occurrenceIds=occurrenceIds==null?List.of():List.copyOf(occurrenceIds);
        }
    }

    /** Atomically reserves a validated V3 batch and binds every STARTED occurrence to one slot. */
    public PlanToolBatchReservation reservePlanToolBatch(ExecutionAuthoritySnapshot a,Instant at,
            CanonicalId revisionId,CanonicalId stepId,int attemptNumber,
            List<PlanToolOccurrenceRequest> requests){
        Objects.requireNonNull(revisionId);Objects.requireNonNull(stepId);
        List<PlanToolOccurrenceRequest> batch=List.copyOf(requests);
        if(batch.isEmpty())return new PlanToolBatchReservation(
                FencedMutationResult.INTEGRITY_VIOLATION,List.of());
        List<CanonicalId> occurrenceIds=new ArrayList<>();
        try{
            FencedMutationResult result=mutate(a,at,()->{
                if(count("""
                        SELECT count(*) FROM plan_step_attempts attempt
                        JOIN plan_step_executions execution
                          ON execution.task_id=attempt.task_id
                         AND execution.plan_revision_id=attempt.plan_revision_id
                         AND execution.step_revision_id=attempt.step_revision_id
                        JOIN plan_run_bindings binding
                          ON binding.task_id=attempt.task_id AND binding.run_id=attempt.run_id
                         AND binding.plan_revision_id=attempt.plan_revision_id
                         AND binding.approval_decision='APPROVED'
                        JOIN plan_revisions revision
                          ON revision.task_id=binding.task_id
                         AND revision.plan_revision_id=binding.plan_revision_id
                         AND revision.canonical_hash=binding.plan_hash
                        JOIN task_plan_authorities authority
                          ON authority.task_id=binding.task_id
                         AND authority.active_plan_revision_id=binding.plan_revision_id
                         AND authority.active_approval_id=binding.approval_id
                        JOIN plan_v3_revisions v3 ON v3.task_id=attempt.task_id
                         AND v3.plan_revision_id=attempt.plan_revision_id
                        WHERE attempt.task_id=? AND attempt.plan_revision_id=?
                          AND attempt.step_revision_id=? AND attempt.run_id=?
                          AND attempt.attempt_number=? AND attempt.state='RUNNING'
                          AND execution.state='RUNNING'
                          AND execution.current_attempt_number=attempt.attempt_number
                        """,a.taskId().value(),revisionId.value(),stepId.value(),
                        a.runId().value(),attemptNumber)!=1)
                    return FencedMutationResult.INVALID_STATE;
                if(count("""
                        SELECT count(*) FROM plan_step_attempts
                        WHERE task_id=? AND plan_revision_id=? AND run_id=? AND state='RUNNING'
                        """,a.taskId().value(),revisionId.value(),a.runId().value())!=1)
                    return FencedMutationResult.INVALID_STATE;
                java.util.Set<String> callIds=new java.util.HashSet<>();
                for(PlanToolOccurrenceRequest request:batch){
                    if(!callIds.add(request.toolCallId()))
                        return FencedMutationResult.ALREADY_EXISTS;
                    if(!exists("""
                            SELECT 1 FROM plan_tool_requirements
                            WHERE task_id=? AND plan_revision_id=? AND step_revision_id=?
                              AND requirement_id=? AND tool_name=? AND effect_class=?
                              AND selector_digest=? AND ?>=1 AND ?<=max_occurrences
                            """,a.taskId().value(),revisionId.value(),stepId.value(),
                            request.requirementId(),request.toolName(),request.effectClass(),
                            request.selectorDigest(),request.slotOrdinal(),request.slotOrdinal()))
                        return FencedMutationResult.INVALID_STATE;
                    if(exists("""
                            SELECT 1 FROM plan_occurrence_slot_bindings
                            WHERE task_id=? AND plan_revision_id=? AND step_revision_id=?
                              AND requirement_id=? AND slot_ordinal=?
                            """,a.taskId().value(),revisionId.value(),stepId.value(),
                            request.requirementId(),request.slotOrdinal())
                            ||exists("SELECT 1 FROM plan_occurrence_slot_bindings WHERE task_id=? AND tool_call_id=?",
                            a.taskId().value(),request.toolCallId()))
                        return FencedMutationResult.ALREADY_EXISTS;
                }
                CanonicalId threadId;
                try(PreparedStatement statement=connection.prepareStatement(
                        "SELECT thread_id FROM task_identities WHERE task_id=?")){
                    statement.setString(1,a.taskId().value());
                    try(ResultSet row=statement.executeQuery()){
                        if(!row.next())return FencedMutationResult.TARGET_NOT_FOUND;
                        threadId=CanonicalId.parse(row.getString(1));
                    }
                }
                for(PlanToolOccurrenceRequest request:batch){
                    CanonicalId occurrenceId=CanonicalId.newId(
                            com.paicli.identity.EntityFamily.OCCURRENCE);
                    FencedMutationResult inserted=insert("""
                            INSERT INTO side_effect_occurrences(occurrence_id,thread_id,task_id,
                                run_id,iteration_id,logical_operation_key,tool_call_id,
                                operation_type,target,status,canonical_input_hash,result_hash,
                                external_outcome,started_at,completed_at)
                            VALUES(?,?,?,?,?,?,?,'TOOL',?,'STARTED',?,NULL,NULL,?,NULL)
                            """,occurrenceId.value(),threadId.value(),a.taskId().value(),
                            a.runId().value(),request.iterationId(),request.logicalOperationKey(),
                            request.toolCallId(),request.toolName(),request.canonicalInputHash(),
                            at.toString());
                    if(inserted!=FencedMutationResult.SUCCESS)return inserted;
                    PlanRequirementStore.bindOccurrence(connection,
                            new PlanRequirementStore.OccurrenceSlotBinding(a.taskId(),revisionId,
                                    stepId,request.requirementId(),request.slotOrdinal(),a.runId(),
                                    attemptNumber,occurrenceId,request.toolCallId(),"STARTED",
                                    request.selectorDigest(),at));
                    occurrenceIds.add(occurrenceId);
                }
                return FencedMutationResult.SUCCESS;
            });
            if(result!=FencedMutationResult.SUCCESS)occurrenceIds.clear();
            return new PlanToolBatchReservation(result,occurrenceIds);
        }catch(SQLException error){throw infrastructure(error);}
    }

    public FencedMutationResult validateEffectLaunch(ExecutionAuthoritySnapshot a,Instant at,
            CanonicalId occurrenceId,String effectType){
        return validateEffectLaunch(a,at,occurrenceId,effectType,null);
    }

    public FencedMutationResult validateEffectLaunch(ExecutionAuthoritySnapshot a,Instant at,
            CanonicalId occurrenceId,String effectType,String producerIdentity){
        Objects.requireNonNull(occurrenceId);
        if(occurrenceId.family()!=com.paicli.identity.EntityFamily.OCCURRENCE
                ||effectType==null||effectType.isBlank())
            return FencedMutationResult.INTEGRITY_VIOLATION;
        return unchecked(a,at,()->{
            String occurrenceStatus,occurrenceIteration,occurrenceTarget,occurrenceInputHash;
            try(PreparedStatement s=connection.prepareStatement("""
                    SELECT task_id,run_id,status,operation_type,iteration_id,target,
                        canonical_input_hash
                    FROM side_effect_occurrences WHERE occurrence_id=?
                    """)){
                s.setString(1,occurrenceId.value());try(ResultSet row=s.executeQuery()){
                    if(!row.next())return FencedMutationResult.TARGET_NOT_FOUND;
                    if(!a.taskId().value().equals(row.getString(1))
                            ||!a.runId().value().equals(row.getString(2))
                            ||!effectType.equals(row.getString(4)))
                        return FencedMutationResult.INTEGRITY_VIOLATION;
                    occurrenceStatus=row.getString(3);
                    occurrenceIteration=row.getString(5);
                    occurrenceTarget=row.getString(6);
                    occurrenceInputHash=row.getString(7);
                }
            }
            if(!"STARTED".equals(occurrenceStatus))return FencedMutationResult.INVALID_STATE;
            if(!exists("""
                    SELECT 1 FROM task_identities t
                    JOIN task_context_pointers p ON p.task_id=t.task_id
                    WHERE t.task_id=? AND t.status IN ('CREATED','RUNNING')
                      AND p.owner_run_id=?
                    """,a.taskId().value(),a.runId().value()))
                return FencedMutationResult.INVALID_STATE;
            if(exists("SELECT 1 FROM task_plan_authorities WHERE task_id=?",
                    a.taskId().value())&&!exists("""
                    SELECT 1 FROM plan_run_bindings b
                    JOIN task_plan_authorities p ON p.task_id=b.task_id
                      AND p.active_plan_revision_id=b.plan_revision_id
                    WHERE b.task_id=? AND b.run_id=? AND b.approval_decision='APPROVED'
                    """,a.taskId().value(),a.runId().value()))
                return FencedMutationResult.INVALID_STATE;
            try(PreparedStatement blocked=connection.prepareStatement("""
                    SELECT steering_id FROM steering_queue_authority WHERE task_id=? AND target_run_id=?
                    AND state IN ('QUEUED','DEFERRED','APPLYING') ORDER BY queue_sequence LIMIT 1""")){
                blocked.setString(1,a.taskId().value());blocked.setString(2,a.runId().value());
                try(ResultSet row=blocked.executeQuery()){
                    if(row.next()){
                        String steeringId=row.getString(1);
                        insert("""
                                INSERT INTO steering_approval_conflict_facts(steering_id,conflict_number,
                                task_id,approval_reference,conflict_type,recorded_at)
                                SELECT ?,COALESCE(max(conflict_number),0)+1,?,?,'APPROVED_EFFECT_BLOCKED',?
                                FROM steering_approval_conflict_facts WHERE steering_id=?""",steeringId,
                                a.taskId().value(),occurrenceId.value(),at.toString(),steeringId);
                        if(update("""
                                UPDATE side_effect_occurrences SET status='FAILED',
                                external_outcome='STEERING_BLOCKED_BEFORE_LAUNCH',completed_at=?
                                WHERE occurrence_id=? AND task_id=? AND run_id=? AND status='STARTED'""",
                                at.toString(),occurrenceId.value(),a.taskId().value(),a.runId().value())
                                !=FencedMutationResult.SUCCESS)
                            return FencedMutationResult.INVALID_STATE;
                        if(producerIdentity!=null){
                            FencedMutationResult producer;
                            if("VERIFICATION".equals(effectType))producer=update("""
                                    UPDATE verification_runs SET status='ERROR',
                                      error_excerpt='PAUSED_BY_STEERING',duration_ms=0,
                                      completed_at=?
                                    WHERE verification_id=? AND task_id=? AND run_id=?
                                      AND status='RUNNING'
                                    """,at.toString(),producerIdentity,a.taskId().value(),a.runId().value());
                            else if("HOOK".equals(effectType))producer=update("""
                                    UPDATE hook_runs SET status='BLOCKED',
                                      error_excerpt='PAUSED_BY_STEERING',duration_ms=0,
                                      completed_at=?
                                    WHERE hook_run_id=? AND task_id=? AND run_id=?
                                      AND status='RUNNING'
                                    """,at.toString(),producerIdentity,a.taskId().value(),a.runId().value());
                            else return FencedMutationResult.INTEGRITY_VIOLATION;
                            if(producer!=FencedMutationResult.SUCCESS)
                                return FencedMutationResult.INVALID_STATE;
                            FencedMutationResult fact=insert("""
                                    INSERT INTO steering_producer_pause_facts(
                                      occurrence_id,steering_id,task_id,run_id,producer_kind,
                                      producer_identity,reason_code,recorded_at)
                                    VALUES(?,?,?,?,?,?,'PAUSED_BY_STEERING',?)
                                    """,occurrenceId.value(),steeringId,a.taskId().value(),
                                    a.runId().value(),effectType,producerIdentity,at.toString());
                            if(fact!=FencedMutationResult.SUCCESS)return fact;
                        }
                        return FencedMutationResult.STEERING_BLOCKED;
                    }
                }
            }
            if("TOOL".equals(effectType)){
                try(PreparedStatement approval=connection.prepareStatement("""
                        SELECT decision,intent_hash FROM approval_decisions
                        WHERE task_id=? AND iteration=? AND tool_name=?
                        ORDER BY rowid DESC LIMIT 1
                        """)){
                    approval.setString(1,a.taskId().value());
                    try{approval.setInt(2,Integer.parseInt(occurrenceIteration));}
                    catch(NumberFormatException invalid){
                        return FencedMutationResult.INTEGRITY_VIOLATION;
                    }
                    approval.setString(3,occurrenceTarget);
                    try(ResultSet row=approval.executeQuery()){
                        if(row.next()&&(!"ALLOW".equals(row.getString(1))
                                ||row.getString(2)==null
                                ||!row.getString(2).equals(occurrenceInputHash)))
                            return FencedMutationResult.INVALID_STATE;
                    }
                }
            }
            return FencedMutationResult.SUCCESS;
        });
    }

    public FencedMutationResult transitionOccurrence(ExecutionAuthoritySnapshot a,Instant at,
            CanonicalId occurrenceId,String status,String resultHash,String outcome){
        return unchecked(a,at,()->{
            FencedMutationResult result=update("""
                    UPDATE side_effect_occurrences SET status=?,result_hash=?,external_outcome=?,
                        completed_at=? WHERE occurrence_id=? AND task_id=? AND run_id=? AND status='STARTED'
                    """,status,resultHash,outcome,at.toString(),occurrenceId.value(),
                    a.taskId().value(),a.runId().value());
            if(result==FencedMutationResult.SUCCESS&&"COMPLETED".equals(status))
                PlanCompletionStore.recordOccurrenceEvidence(connection,a,at,occurrenceId);
            return result;
        });
    }

    /** V31 fixes final_candidate_digest to WorkspaceCandidateDigest H5 V1. */
    public FencedMutationResult acceptProviderFinal(ExecutionAuthoritySnapshot a,Instant at,
            CanonicalId occurrenceId,int iteration,String expectedPhase,String content,
            String finalCandidateDigest){
        if(occurrenceId==null||iteration<1||content==null||content.isBlank()
                ||finalCandidateDigest==null||!finalCandidateDigest.matches("[0-9a-f]{64}")
                ||!(CodingRunStore.PHASE_EXECUTING.equals(expectedPhase)
                    ||CodingRunStore.PHASE_REPAIRING.equals(expectedPhase)))
            return FencedMutationResult.INVALID_STATE;
        return unchecked(a,at,()->{
            try(PreparedStatement occurrence=connection.prepareStatement("""
                    SELECT iteration_id,operation_type,status,final_candidate_digest
                    FROM side_effect_occurrences
                    WHERE occurrence_id=? AND task_id=? AND run_id=?
                    """)){
                occurrence.setString(1,occurrenceId.value());
                occurrence.setString(2,a.taskId().value());
                occurrence.setString(3,a.runId().value());
                try(ResultSet row=occurrence.executeQuery()){
                    if(!row.next()||!Integer.toString(iteration).equals(row.getString(1))
                            ||!"PROVIDER".equals(row.getString(2))
                            ||!"STARTED".equals(row.getString(3))||row.getString(4)!=null)
                        return FencedMutationResult.INVALID_STATE;
                }
            }
            try(PreparedStatement duplicate=connection.prepareStatement("""
                    SELECT 1 FROM side_effect_occurrences
                    WHERE task_id=? AND run_id=? AND iteration_id=?
                        AND operation_type='PROVIDER' AND final_candidate_digest IS NOT NULL
                        AND occurrence_id<>? LIMIT 1
                    """)){
                duplicate.setString(1,a.taskId().value());
                duplicate.setString(2,a.runId().value());
                duplicate.setString(3,Integer.toString(iteration));
                duplicate.setString(4,occurrenceId.value());
                try(ResultSet row=duplicate.executeQuery()){
                    if(row.next())return FencedMutationResult.INTEGRITY_VIOLATION;
                }
            }
            String resultHash=providerFinalResultHash(content);
            FencedMutationResult event=insert("""
                    INSERT INTO events(task_id,iteration,role,type,content,tool_call_id,tool_call_json)
                    VALUES(?,?,'ASSISTANT','FINAL',?,NULL,NULL)
                    """,a.taskId().value(),iteration,content);
            if(event!=FencedMutationResult.SUCCESS)return event;
            FencedMutationResult occurrence=update("""
                    UPDATE side_effect_occurrences
                    SET status='COMPLETED',result_hash=?,external_outcome='KNOWN_SUCCESS',
                        final_candidate_digest=?,completed_at=?
                    WHERE occurrence_id=? AND task_id=? AND run_id=? AND status='STARTED'
                    """,resultHash,finalCandidateDigest,at.toString(),occurrenceId.value(),
                    a.taskId().value(),a.runId().value());
            if(occurrence!=FencedMutationResult.SUCCESS)return occurrence;
            FencedMutationResult task=update("""
                    UPDATE tasks SET iterations_completed=?,updated_at=datetime('now')
                    WHERE task_id=? AND status='RUNNING' AND ?>=iterations_completed
                    """,iteration,a.taskId().value(),iteration);
            if(task!=FencedMutationResult.SUCCESS)return task;
            return update("""
                    UPDATE coding_runs SET phase='VERIFYING',last_iteration=?,
                        updated_at=datetime('now')
                    WHERE run_id=? AND task_id=? AND phase=? AND ?>=last_iteration
                    """,iteration,a.runId().value(),a.taskId().value(),expectedPhase,iteration);
        });
    }

    public static String providerFinalResultHash(String content){
        Objects.requireNonNull(content);
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            digest.update("PAICLI_PROVIDER_FINAL_V1\0".getBytes(StandardCharsets.US_ASCII));
            digest.update(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        }catch(Exception impossible){throw new IllegalStateException(impossible);}
    }

    public FencedMutationResult recordSnapshot(ExecutionAuthoritySnapshot a,Instant at,
            String id,String toolCallId,String path,String snapshotPath,String operation,
            boolean existed,long size,String beforeHash){
        return unchecked(a,at,()->insert("""
                INSERT INTO file_snapshots(snapshot_id,task_id,tool_call_id,file_path,
                    snapshot_path,operation,original_exists,original_size,sha256_before,status,
                    owner_run_id)
                VALUES(?,?,?,?,?,?,?,?,?,'CAPTURED',?)
                """,id,a.taskId().value(),toolCallId,path,snapshotPath,operation,
                existed?1:0,size,beforeHash,a.runId().value()));
    }

    public FencedMutationResult transitionSnapshot(ExecutionAuthoritySnapshot a,Instant at,
            String id,String expected,String status,String afterHash){
        return unchecked(a,at,()->{
            FencedMutationResult result=update("""
                    UPDATE file_snapshots SET status=?,sha256_after=COALESCE(?,sha256_after)
                    WHERE snapshot_id=? AND task_id=? AND owner_run_id=? AND status=?
                    """,status,afterHash,id,a.taskId().value(),a.runId().value(),expected);
            if(result==FencedMutationResult.SUCCESS
                    &&FileSnapshotStore.STATUS_APPLIED.equals(status))
                PlanCompletionStore.recordSnapshotEvidence(connection,a,at,id);
            return result;
        });
    }

    private boolean structuredCompletionRequired(ExecutionAuthoritySnapshot authority)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM task_plan_authorities WHERE task_id=?
                """)) {
            statement.setString(1, authority.taskId().value());
            try (ResultSet row = statement.executeQuery()) { return row.next(); }
        }
    }

    private boolean advisoryResultRequired(ExecutionAuthoritySnapshot authority,String planId)
            throws SQLException{
        if(planId==null||!planId.startsWith("pln_"))return false;
        try(PreparedStatement statement=connection.prepareStatement("""
                SELECT 1 FROM plan_revision_lifecycle l
                JOIN plan_approvals p ON p.task_id=l.task_id
                    AND p.plan_revision_id=l.plan_revision_id AND p.decision='APPROVED'
                WHERE l.task_id=? AND l.plan_revision_id=? AND l.state='APPROVED'
                    AND p.approved_by_run_id=?
                    AND NOT EXISTS(SELECT 1 FROM task_plan_authorities a WHERE a.task_id=l.task_id)
                """)){
            bind(statement,new Object[]{authority.taskId().value(),planId,
                    authority.runId().value()});
            try(ResultSet row=statement.executeQuery()){return row.next();}
        }
    }

    private boolean renderedUserResultExists(ExecutionAuthoritySnapshot authority,String planId)
            throws SQLException{
        String revisionPredicate=planId==null?"plan_revision_id IS NULL":"plan_revision_id=?";
        try(PreparedStatement statement=connection.prepareStatement("""
                SELECT 1 FROM task_user_results WHERE task_id=? AND run_id=?
                    AND %s AND validation_status='VALID'
                    AND render_status='RENDERED' AND length(trim(content))>0
                """.formatted(revisionPredicate))){
            if(planId==null)bind(statement,new Object[]{authority.taskId().value(),
                    authority.runId().value()});
            else bind(statement,new Object[]{authority.taskId().value(),
                    authority.runId().value(),planId});
            try(ResultSet row=statement.executeQuery()){return row.next();}
        }
    }

    public record UserResultMutation(FencedMutationResult status,UserResultStore.Result result){}

    public FencedMutationResult startHook(ExecutionAuthoritySnapshot a,Instant at,String id,
            String hookId,String event,String occurrence,int sequence,String command,String policy){
        return unchecked(a,at,()->insert("""
                INSERT INTO hook_runs(hook_run_id,run_id,task_id,hook_id,event,occurrence_key,
                    sequence_no,command,status,failure_policy)
                VALUES(?,?,?,?,?,?,?,?,'RUNNING',?)
                """,id,a.runId().value(),a.taskId().value(),hookId,event,occurrence,sequence,
                command,policy));
    }

    public FencedMutationResult finishHook(ExecutionAuthoritySnapshot a,Instant at,String id,
            String status,Integer exit,String output,String error,long duration){
        return unchecked(a,at,()->update("""
                UPDATE hook_runs SET status=?,exit_code=?,output_excerpt=?,error_excerpt=?,
                    duration_ms=?,completed_at=datetime('now')
                WHERE hook_run_id=? AND run_id=? AND task_id=? AND status='RUNNING'
                """,status,exit,output,error,duration,id,a.runId().value(),a.taskId().value()));
    }

    public FencedMutationResult recordSkillLoad(ExecutionAuthoritySnapshot a,Instant at,String id,
            String skillId,String name,String path,String description,String hash,String status,
            String error){
        return unchecked(a,at,()->insert("""
                INSERT INTO skill_loads(skill_load_id,skill_id,run_id,task_id,name,path,
                    description,content_sha256,status,error_excerpt) VALUES(?,?,?,?,?,?,?,?,?,?)
                """,id,skillId,a.runId().value(),a.taskId().value(),name,path,description,hash,
                status,error));
    }

    public FencedMutationResult bindSkillPackage(ExecutionAuthoritySnapshot a,Instant at,
            String loadId,String hash,String manifest){
        return unchecked(a,at,()->insert("""
                INSERT INTO skill_package_bindings(skill_load_id,package_sha256,manifest_json)
                SELECT skill_load_id,?,? FROM skill_loads WHERE skill_load_id=? AND task_id=? AND run_id=?
                """,hash,manifest,loadId,a.taskId().value(),a.runId().value()));
    }

    public FencedMutationResult recordSkillAccess(ExecutionAuthoritySnapshot a,Instant at,
            SkillLoadStore.Access fact){
        return unchecked(a,at,()->insert("""
                INSERT INTO skill_accesses(access_id,skill_load_id,kind,tool_call_id,occurrence_id,
                    resource_path,content_sha256,offset_chars,returned_chars,status)
                SELECT ?,skill_load_id,?,?,?,?,?,?,?,? FROM skill_loads
                WHERE skill_load_id=? AND task_id=? AND run_id=?
                """,fact.id(),fact.kind(),fact.toolCallId(),fact.occurrenceId(),fact.resourcePath(),
                fact.hash(),fact.offset(),fact.chars(),fact.status(),fact.loadId(),
                a.taskId().value(),a.runId().value()));
    }

    public FencedMutationResult recordSkillSelection(ExecutionAuthoritySnapshot a,Instant at,
            String id,String stage,String occurrence,String inputHash,
            List<SkillSelectionStore.SelectedSkillInput> selected){
        Objects.requireNonNull(selected);
        return unchecked(a,at,()->{
            FencedMutationResult header=insert("""
                    INSERT INTO skill_selection_runs(selection_id,run_id,task_id,stage,
                        occurrence_key,input_sha256,status,selected_count)
                    VALUES(?,?,?,?,?,?,'COMPLETED',?)
                    """,id,a.runId().value(),a.taskId().value(),stage,occurrence,inputHash,
                    selected.size());
            if(header!=FencedMutationResult.SUCCESS)return header;
            for(var item:selected){
                FencedMutationResult row=insert("""
                        INSERT INTO selected_skills(selected_skill_id,selection_id,skill_load_id,
                            run_id,task_id,skill_name,rank,score,reason,context_excerpt)
                        VALUES(?,?,?,?,?,?,?,?,?,?)
                        """,item.selectedSkillId(),id,item.skillLoadId(),a.runId().value(),
                        a.taskId().value(),item.skillName(),item.rank(),item.score(),item.reason(),
                        item.contextExcerpt());
                if(row!=FencedMutationResult.SUCCESS)return row;
            }
            return FencedMutationResult.SUCCESS;
        });
    }

    public FencedMutationResult recordProposal(ExecutionAuthoritySnapshot a,Instant at,String id,
            String path,String afterHash,int additions,int deletions,boolean truncated,
            boolean redacted){
        String portable=path.replace('\\','/');
        return unchecked(a,at,()->{
            execute("""
                    UPDATE interactive_edit_proposals SET status='SUPERSEDED',
                        updated_at=datetime('now'),resolved_at=datetime('now')
                    WHERE task_id=? AND replace(relative_path,char(92),'/')=?
                        AND status='PROPOSED' AND after_hash<>?
                    """,a.taskId().value(),portable,afterHash);
            return insert("""
                    INSERT INTO interactive_edit_proposals(proposal_id,task_id,run_id,
                        relative_path,after_hash,additions,deletions,status,truncated,redacted)
                    VALUES(?,?,?,?,?,?,?,'PROPOSED',?,?)
                    """,id,a.taskId().value(),a.runId().value(),portable,afterHash,additions,
                    deletions,truncated?1:0,redacted?1:0);
        });
    }

    public FencedMutationResult transitionProposal(ExecutionAuthoritySnapshot a,Instant at,
            String id,String expected,String status){
        return unchecked(a,at,()->update("""
                UPDATE interactive_edit_proposals SET status=?,updated_at=datetime('now'),
                    resolved_at=datetime('now')
                WHERE proposal_id=? AND task_id=? AND run_id=? AND status=?
                """,status,id,a.taskId().value(),a.runId().value(),expected));
    }

    public FencedMutationResult applyLatestProposal(ExecutionAuthoritySnapshot a,Instant at,
            String path,String snapshotId){
        return unchecked(a,at,()->update("""
                UPDATE interactive_edit_proposals SET status='APPLIED',snapshot_id=?,
                    updated_at=datetime('now'),resolved_at=datetime('now')
                WHERE proposal_id=(SELECT proposal_id FROM interactive_edit_proposals
                    WHERE task_id=? AND run_id=? AND replace(relative_path,char(92),'/')=?
                        AND status='PROPOSED' ORDER BY created_at DESC,rowid DESC LIMIT 1)
                """,snapshotId,a.taskId().value(),a.runId().value(),path.replace('\\','/')));
    }

    public FencedMutationResult supersedeProposals(ExecutionAuthoritySnapshot a,Instant at){
        return unchecked(a,at,()->{execute("""
                UPDATE interactive_edit_proposals SET status='SUPERSEDED',
                    updated_at=datetime('now'),resolved_at=datetime('now')
                WHERE task_id=? AND run_id=? AND status='PROPOSED'
                """,a.taskId().value(),a.runId().value());return FencedMutationResult.SUCCESS;});
    }

    public FencedMutationResult recordInteractiveFacts(ExecutionAuthoritySnapshot a,Instant at,
            String status,int files,int contexts,int queries){
        return unchecked(a,at,()->execute("""
                INSERT INTO interactive_task_facts(task_id,run_id,code_intelligence_status,
                    indexed_java_files,context_item_count,query_count) VALUES(?,?,?,?,?,?)
                ON CONFLICT(task_id) DO UPDATE SET run_id=excluded.run_id,
                    code_intelligence_status=excluded.code_intelligence_status,
                    indexed_java_files=excluded.indexed_java_files,
                    context_item_count=excluded.context_item_count,query_count=excluded.query_count,
                    updated_at=datetime('now')
                """,a.taskId().value(),a.runId().value(),status,files,contexts,queries));
    }

    /** Fenced admission of one bounded continuity segment; this grants no Tool authority. */
    public FencedMutationResult startContinuitySegment(ExecutionAuthoritySnapshot a,Instant at,
            int segmentOrdinal,int planningAttemptOrdinal,String trigger,String policyHash,
            int startIteration,String directive){
        return unchecked(a,at,()->insert("""
                INSERT INTO task_continuity_segments(
                    task_id,run_id,segment_ordinal,planning_attempt_ordinal,trigger,state,
                    policy_hash,start_iteration,end_iteration,provider_turns,tool_calls,
                    input_tokens,output_tokens,active_elapsed_ms,recovery_directive,
                    created_at,updated_at)
                VALUES(?,?,?,?,?,'STARTED',?,?,?,0,0,0,0,0,?,?,?)
                """,a.taskId().value(),a.runId().value(),segmentOrdinal,
                planningAttemptOrdinal,trigger,policyHash,startIteration,startIteration,
                directive,at.toString(),at.toString()));
    }

    /** Fenced terminal accounting for one segment. */
    public FencedMutationResult finishContinuitySegment(ExecutionAuthoritySnapshot a,Instant at,
            int segmentOrdinal,String state,int endIteration,int providerTurns,int toolCalls,
            long inputTokens,long outputTokens,long activeElapsedMillis,String terminalReason){
        return unchecked(a,at,()->update("""
                UPDATE task_continuity_segments SET state=?,end_iteration=?,provider_turns=?,
                    tool_calls=?,input_tokens=?,output_tokens=?,active_elapsed_ms=?,
                    terminal_reason=?,updated_at=?
                WHERE task_id=? AND run_id=? AND segment_ordinal=? AND state='STARTED'
                """,state,endIteration,providerTurns,toolCalls,inputTokens,outputTokens,
                activeElapsedMillis,terminalReason,at.toString(),a.taskId().value(),
                a.runId().value(),segmentOrdinal));
    }

    private FencedMutationResult unchecked(ExecutionAuthoritySnapshot a,Instant at,SqlMutation work){
        try{return mutate(a,at,work);}catch(SQLException e){throw infrastructure(e);}
    }

    private FencedMutationResult mutate(ExecutionAuthoritySnapshot a,Instant at,SqlMutation work)
            throws SQLException{
        Objects.requireNonNull(a);Objects.requireNonNull(at);
        if(!connection.getAutoCommit())throw new SQLException("Nested fenced transaction is not supported");
        connection.setAutoCommit(false);
        try{
            FencedMutationResult authority=lockAuthority(a,at);
            if(authority!=FencedMutationResult.SUCCESS){connection.rollback();return authority;}
            FencedMutationResult result=work.run();
            if(result==FencedMutationResult.SUCCESS||result==FencedMutationResult.STEERING_BLOCKED)
                connection.commit();else connection.rollback();
            return result;
        }catch(SQLException|RuntimeException e){connection.rollback();throw e;}
        finally{connection.setAutoCommit(true);}
    }

    private FencedMutationResult lockAuthority(ExecutionAuthoritySnapshot a,Instant at)
            throws SQLException{
        try(PreparedStatement s=connection.prepareStatement("""
                UPDATE task_ownership_leases SET heartbeat_at=heartbeat_at
                WHERE task_id=? AND owner_instance_id=? AND owner_run_id=? AND lease_token=?
                    AND fencing_token=? AND cas_version=? AND state='ACTIVE'
                """)){
            s.setString(1,a.taskId().value());s.setString(2,a.ownerInstanceId());
            s.setString(3,a.runId().value());s.setString(4,a.leaseToken());
            s.setLong(5,a.fencingToken());s.setLong(6,a.casVersion());
            if(s.executeUpdate()==1){
                try(PreparedStatement expiry=connection.prepareStatement(
                        "SELECT expires_at FROM task_ownership_leases WHERE task_id=?")){
                    expiry.setString(1,a.taskId().value());try(ResultSet row=expiry.executeQuery()){
                        if(!row.next())return FencedMutationResult.AUTHORITY_NOT_FOUND;
                        return at.isBefore(Instant.parse(row.getString(1)))
                                ?FencedMutationResult.SUCCESS:FencedMutationResult.LEASE_EXPIRED;
                    }
                }
            }
        }
        try(PreparedStatement s=connection.prepareStatement("""
                SELECT owner_instance_id,owner_run_id,lease_token,fencing_token,cas_version,
                    state,expires_at FROM task_ownership_leases WHERE task_id=?
                """)){
            s.setString(1,a.taskId().value());try(ResultSet r=s.executeQuery()){
                if(!r.next())return FencedMutationResult.AUTHORITY_NOT_FOUND;
                if(!r.getString(1).equals(a.ownerInstanceId()))return FencedMutationResult.OWNER_MISMATCH;
                if(!r.getString(2).equals(a.runId().value()))return FencedMutationResult.RUN_MISMATCH;
                if(!r.getString(3).equals(a.leaseToken()))return FencedMutationResult.LEASE_TOKEN_MISMATCH;
                if(r.getLong(4)!=a.fencingToken())return FencedMutationResult.FENCING_REJECTED;
                if(r.getLong(5)!=a.casVersion())return FencedMutationResult.VERSION_CONFLICT;
                if(!"ACTIVE".equals(r.getString(6)))return FencedMutationResult.INVALID_STATE;
                if(!at.isBefore(Instant.parse(r.getString(7))))return FencedMutationResult.LEASE_EXPIRED;
                return FencedMutationResult.INVALID_STATE;
            }
        }
    }

    private FencedMutationResult insert(String sql,Object...values)throws SQLException{
        try{return execute(sql,values);}catch(SQLException e){
            if(e.getErrorCode()==19)return FencedMutationResult.INTEGRITY_VIOLATION;throw e;
        }
    }
    private FencedMutationResult update(String sql,Object...values)throws SQLException{
        try(PreparedStatement s=connection.prepareStatement(sql)){bind(s,values);
            return s.executeUpdate()==1?FencedMutationResult.SUCCESS:FencedMutationResult.TARGET_NOT_FOUND;}
    }
    private FencedMutationResult execute(String sql,Object...values)throws SQLException{
        try(PreparedStatement s=connection.prepareStatement(sql)){bind(s,values);s.executeUpdate();
            return FencedMutationResult.SUCCESS;}
    }
    private boolean exists(String sql,Object...values)throws SQLException{
        try(PreparedStatement s=connection.prepareStatement(sql)){
            bind(s,values);try(ResultSet rows=s.executeQuery()){return rows.next();}
        }
    }
    private int count(String sql,Object...values)throws SQLException{
        try(PreparedStatement s=connection.prepareStatement(sql)){
            bind(s,values);try(ResultSet rows=s.executeQuery()){
                return rows.next()?rows.getInt(1):0;
            }
        }
    }
    private static void bind(PreparedStatement s,Object[] values)throws SQLException{
        for(int i=0;i<values.length;i++){
            Object v=values[i];int index=i+1;
            if(v==null)s.setNull(index,Types.VARCHAR);
            else if(v instanceof Integer n)s.setInt(index,n);
            else if(v instanceof Long n)s.setLong(index,n);
            else if(v instanceof Double n)s.setDouble(index,n);
            else s.setString(index,v.toString());
        }
    }
    private static IllegalStateException infrastructure(SQLException e){
        return new IllegalStateException("Fenced mutation persistence failure",e);
    }
    @FunctionalInterface private interface SqlMutation{
        FencedMutationResult run()throws SQLException;
    }
}
