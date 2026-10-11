package com.paicli.state;

import com.paicli.context.ContextModels.OperationKind;
import com.paicli.state.R1aDurableModels.ExecutionAuthoritySnapshot;
import com.paicli.state.R1aStoreResults.OwnershipResult;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;

/** Shared read-only R1 authority gate for Provider admission and compaction mutation. */
public final class ContextSafePointAuthority {
    private final Connection connection;
    ContextSafePointAuthority(Connection connection){this.connection=Objects.requireNonNull(connection);}

    /** Compaction mutations must prove that this caller owns the current R1 lease. */
    public Decision evaluate(String taskId,String runId,String contextId,long pointerVersion,
            ExecutionAuthoritySnapshot authority)throws SQLException{
        if(!callerOwnsLease(taskId,runId,authority))
            return Decision.reject("CONTEXT_OWNERSHIP_UNCERTAIN");
        return evaluate(taskId,runId,contextId,pointerVersion);
    }

    /** Auto compaction may run inside a RUNNING Step only at a quiescent Provider boundary. */
    public Decision evaluateForAuto(String taskId,String runId,String contextId,long pointerVersion,
            ExecutionAuthoritySnapshot authority)throws SQLException{
        if(!callerOwnsLease(taskId,runId,authority))
            return Decision.reject("CONTEXT_OWNERSHIP_UNCERTAIN");
        if(!pointerMatches(taskId,runId,contextId,pointerVersion))
            return Decision.reject("CONTEXT_POINTER_CONFLICT");
        if(!ownershipCurrent(taskId,runId,contextId))
            return Decision.reject("CONTEXT_OWNERSHIP_UNCERTAIN");
        if(exists("SELECT 1 FROM side_effect_occurrences WHERE task_id=? AND run_id=? AND status IN ('STARTED','UNKNOWN') LIMIT 1",taskId,runId))
            return Decision.reject("CONTEXT_EXTERNAL_EFFECT_PENDING");
        if(pendingApproval(taskId))return Decision.reject("CONTEXT_APPROVAL_PENDING");
        if(exists("SELECT 1 FROM plan_step_attempts WHERE task_id=? AND run_id=? AND state='UNKNOWN' LIMIT 1",taskId,runId))
            return Decision.reject("CONTEXT_PLAN_STEP_PENDING");
        if(exists("SELECT 1 FROM verification_runs WHERE task_id=? AND run_id=? AND status='RUNNING' LIMIT 1",taskId,runId))
            return Decision.reject("CONTEXT_VERIFICATION_RUNNING");
        if(exists("SELECT 1 FROM repair_attempts WHERE task_id=? AND run_id=? AND status='RUNNING' LIMIT 1",taskId,runId))
            return Decision.reject("CONTEXT_REPAIR_RUNNING");
        if(exists("SELECT 1 FROM hook_runs WHERE task_id=? AND run_id=? AND status='RUNNING' LIMIT 1",taskId,runId))
            return Decision.reject("CONTEXT_HOOK_EFFECT_PENDING");
        if(exists("SELECT 1 FROM mcp_tool_calls WHERE task_id=? AND coding_run_id=? AND status IN ('PENDING','RUNNING','UNKNOWN_OUTCOME') LIMIT 1",taskId,runId))
            return Decision.reject("CONTEXT_TOOL_EFFECT_PENDING");
        if(exists("SELECT 1 FROM steering_queue_authority WHERE task_id=? AND target_run_id=? AND state IN ('CANDIDATE','NEEDS_CONFIRMATION','QUEUED','DEFERRED','APPLYING') LIMIT 1",taskId,runId))
            return Decision.reject("CONTEXT_STEERING_PENDING");
        if(pendingConfirmation(taskId))return Decision.reject("CONTEXT_CONFIRMATION_PENDING");
        if(exists("SELECT 1 FROM plan_revision_lifecycle l JOIN plan_revisions p ON p.plan_revision_id=l.plan_revision_id WHERE p.task_id=? AND p.created_by_run_id=? AND l.state='VALIDATED' LIMIT 1",taskId,runId))
            return Decision.reject("CONTEXT_APPROVAL_PENDING");
        return Decision.allowed();
    }

    public Decision evaluate(String taskId,String runId,String contextId,long pointerVersion)
            throws SQLException{
        if(!pointerMatches(taskId,runId,contextId,pointerVersion))return Decision.reject("CONTEXT_POINTER_CONFLICT");
        if(!ownershipCurrent(taskId,runId,contextId))return Decision.reject("CONTEXT_OWNERSHIP_UNCERTAIN");
        if(exists("SELECT 1 FROM side_effect_occurrences WHERE task_id=? AND run_id=? AND status IN ('STARTED','UNKNOWN') LIMIT 1",taskId,runId))
            return Decision.reject("CONTEXT_EXTERNAL_EFFECT_PENDING");
        if(pendingApproval(taskId))return Decision.reject("CONTEXT_APPROVAL_PENDING");
        if(exists("SELECT 1 FROM plan_step_attempts WHERE task_id=? AND run_id=? AND state IN ('RUNNING','UNKNOWN') LIMIT 1",taskId,runId))
            return Decision.reject("CONTEXT_PLAN_STEP_PENDING");
        if(exists("SELECT 1 FROM verification_runs WHERE task_id=? AND run_id=? AND status='RUNNING' LIMIT 1",taskId,runId))
            return Decision.reject("CONTEXT_VERIFICATION_RUNNING");
        if(exists("SELECT 1 FROM repair_attempts WHERE task_id=? AND run_id=? AND status='RUNNING' LIMIT 1",taskId,runId))
            return Decision.reject("CONTEXT_REPAIR_RUNNING");
        if(exists("SELECT 1 FROM hook_runs WHERE task_id=? AND run_id=? AND status='RUNNING' LIMIT 1",taskId,runId))
            return Decision.reject("CONTEXT_HOOK_EFFECT_PENDING");
        if(exists("SELECT 1 FROM mcp_tool_calls WHERE task_id=? AND coding_run_id=? AND status IN ('PENDING','RUNNING','UNKNOWN_OUTCOME') LIMIT 1",taskId,runId))
            return Decision.reject("CONTEXT_TOOL_EFFECT_PENDING");
        if(exists("SELECT 1 FROM steering_queue_authority WHERE task_id=? AND target_run_id=? AND state IN ('CANDIDATE','NEEDS_CONFIRMATION','QUEUED','DEFERRED','APPLYING') LIMIT 1",taskId,runId))
            return Decision.reject("CONTEXT_STEERING_PENDING");
        if(pendingConfirmation(taskId))return Decision.reject("CONTEXT_CONFIRMATION_PENDING");
        if(exists("SELECT 1 FROM plan_revision_lifecycle l JOIN plan_revisions p ON p.plan_revision_id=l.plan_revision_id WHERE p.task_id=? AND p.created_by_run_id=? AND l.state='VALIDATED' LIMIT 1",taskId,runId))
            return Decision.reject("CONTEXT_APPROVAL_PENDING");
        return Decision.allowed();
    }

    /** Provider uses the same authority with its own STARTED occurrence excluded. */
    public Decision evaluateForProvider(String taskId,String runId,String contextId,long pointerVersion,
            String providerOccurrenceId,OperationKind operation)throws SQLException{
        if(!pointerMatches(taskId,runId,contextId,pointerVersion))return Decision.reject("CONTEXT_POINTER_CONFLICT");
        if(!ownershipCurrent(taskId,runId,contextId))return Decision.reject("CONTEXT_OWNERSHIP_UNCERTAIN");
        if(exists("SELECT 1 FROM side_effect_occurrences WHERE task_id=? AND run_id=? AND status IN ('STARTED','UNKNOWN') AND occurrence_id<>? LIMIT 1",taskId,runId,providerOccurrenceId))
            return Decision.reject("CONTEXT_EXTERNAL_EFFECT_PENDING");
        if(pendingApproval(taskId))return Decision.reject("CONTEXT_APPROVAL_PENDING");
        if(exists("SELECT 1 FROM plan_step_attempts WHERE task_id=? AND run_id=? AND state IN ('UNKNOWN') LIMIT 1",taskId,runId))
            return Decision.reject("CONTEXT_PLAN_STEP_PENDING");
        if(operation!=OperationKind.VERIFICATION
                &&exists("SELECT 1 FROM verification_runs WHERE task_id=? AND run_id=? AND status='RUNNING' LIMIT 1",taskId,runId))
            return Decision.reject("CONTEXT_VERIFICATION_RUNNING");
        Decision repair=repairProviderAuthority(taskId,runId,contextId,pointerVersion,operation);if(!repair.safe())return repair;
        if(exists("SELECT 1 FROM hook_runs WHERE task_id=? AND run_id=? AND status='RUNNING' LIMIT 1",taskId,runId))
            return Decision.reject("CONTEXT_HOOK_EFFECT_PENDING");
        if(exists("SELECT 1 FROM mcp_tool_calls WHERE task_id=? AND coding_run_id=? AND status IN ('PENDING','RUNNING','UNKNOWN_OUTCOME') LIMIT 1",taskId,runId))
            return Decision.reject("CONTEXT_TOOL_EFFECT_PENDING");
        if(exists("SELECT 1 FROM steering_queue_authority WHERE task_id=? AND target_run_id=? AND state IN ('CANDIDATE','NEEDS_CONFIRMATION','QUEUED','DEFERRED','APPLYING') LIMIT 1",taskId,runId))
            return Decision.reject("CONTEXT_STEERING_PENDING");
        if(pendingConfirmation(taskId))return Decision.reject("CONTEXT_CONFIRMATION_PENDING");
        if(exists("SELECT 1 FROM plan_revision_lifecycle l JOIN plan_revisions p ON p.plan_revision_id=l.plan_revision_id WHERE p.task_id=? AND p.created_by_run_id=? AND l.state='VALIDATED' LIMIT 1",taskId,runId))
            return Decision.reject("CONTEXT_APPROVAL_PENDING");
        return Decision.allowed();
    }

    /** One snapshot binds TOOL_CONTINUATION to the unique RUNNING Repair in this READY context. */
    private Decision repairProviderAuthority(String taskId,String runId,String contextId,
            long pointerVersion,OperationKind operation)throws SQLException{
        try(var statement=connection.prepareStatement("""
          WITH running_repair AS (
            SELECT repair_id,status FROM repair_attempts
            WHERE task_id=? AND run_id=? AND status=?
          )
          SELECT
            (SELECT count(*) FROM running_repair),
            (SELECT count(*) FROM running_repair r
             JOIN context_generations g ON g.context_generation_id=?
               AND g.task_id=? AND g.run_id=? AND g.operation_kind=? AND g.lifecycle='READY'
             JOIN context_generation_pointers p ON p.task_id=g.task_id AND p.run_id=g.run_id
               AND p.operation_kind=g.operation_kind
               AND p.active_context_generation_id=g.context_generation_id
               AND p.pointer_version=?
             JOIN context_source_references source
               ON source.context_generation_id=g.context_generation_id
               AND source.source_type='REPAIR_ITERATION'
               AND source.source_id=r.repair_id AND source.source_revision=r.status)
          """)){
            statement.setString(1,taskId);statement.setString(2,runId);statement.setString(3,RepairAttemptStore.STATUS_RUNNING);
            statement.setString(4,contextId);statement.setString(5,taskId);statement.setString(6,runId);
            statement.setString(7,operation.name());
            statement.setLong(8,pointerVersion);
            try(var row=statement.executeQuery()){
                if(!row.next())return Decision.reject("CONTEXT_REPAIR_RUNNING");
                long running=row.getLong(1),bound=row.getLong(2);
                if(running==0||operation==OperationKind.REPAIR)return Decision.allowed();
                if(operation==OperationKind.TOOL_CONTINUATION&&running==1&&bound==1)return Decision.allowed();
                return Decision.reject("CONTEXT_REPAIR_RUNNING");
            }
        }
    }

    private boolean ownershipCurrent(String task,String run,String context)throws SQLException{
        try(var s=connection.prepareStatement("""
          SELECT l.state,l.expires_at FROM task_ownership_leases l
          JOIN context_generations g ON g.context_generation_id=?
            AND g.task_id=l.task_id AND g.run_id=l.owner_run_id
          JOIN context_source_references r ON r.context_generation_id=g.context_generation_id
            AND r.source_type='OWNERSHIP_LEASE' AND r.source_id=l.task_id
            AND r.source_revision=CAST(l.fencing_token AS TEXT)
          WHERE l.task_id=? AND l.owner_run_id=? AND l.fencing_token>=1 AND l.cas_version>=1
          """)){
            s.setString(1,context);s.setString(2,task);s.setString(3,run);try(var r=s.executeQuery()){
                if(!r.next()||!"ACTIVE".equals(r.getString(1)))return false;
                try{return !Instant.parse(r.getString(2)).isBefore(Instant.now());}
                catch(RuntimeException invalid){return false;}
            }
        }
    }
    private boolean callerOwnsLease(String task,String run,ExecutionAuthoritySnapshot authority)
            throws SQLException{
        if(authority==null||!task.equals(authority.taskId().value())
                ||!run.equals(authority.runId().value()))return false;
        OwnershipResult result=new R1aDurableStore(connection).validateExecutionAuthority(
                authority.taskId(),authority.ownerInstanceId(),authority.runId(),
                authority.leaseToken(),authority.fencingToken(),authority.casVersion(),Instant.now());
        return result==OwnershipResult.SUCCESS;
    }
    private boolean pointerMatches(String task,String run,String context,long version)throws SQLException{
        try(var s=connection.prepareStatement("""
          SELECT 1 FROM context_generation_pointers p JOIN context_generations g
            ON g.context_generation_id=p.active_context_generation_id
          WHERE p.task_id=? AND p.run_id=? AND p.active_context_generation_id=?
            AND p.pointer_version=? AND g.lifecycle='READY'
          """)){
            s.setString(1,task);s.setString(2,run);s.setString(3,context);s.setLong(4,version);
            try(var r=s.executeQuery()){return r.next();}
        }
    }
    private boolean pendingApproval(String task)throws SQLException{
        try(var s=connection.prepareStatement("""
          SELECT 1 FROM approval_decisions a WHERE a.task_id=? AND a.decision='NEEDS_APPROVAL'
            AND a.id=(SELECT max(x.id) FROM approval_decisions x
                      WHERE x.task_id=a.task_id AND x.tool_call_id IS a.tool_call_id)
          LIMIT 1
          """)){s.setString(1,task);try(var r=s.executeQuery()){return r.next();}}
    }
    private boolean pendingConfirmation(String task)throws SQLException{
        try(var s=connection.prepareStatement("""
          SELECT 1 FROM followup_candidate_authority a
          JOIN followup_candidate_definitions d ON d.capsule_id=a.capsule_id
          WHERE d.parent_task_id=? AND a.state IN ('NEEDS_INTENT_CONFIRMATION','NEEDS_RISK_CONFIRMATION')
          LIMIT 1
          """)){s.setString(1,task);try(var r=s.executeQuery()){return r.next();}}
    }
    private boolean exists(String sql,Object...values)throws SQLException{
        try(PreparedStatement s=connection.prepareStatement(sql)){
            for(int i=0;i<values.length;i++)s.setObject(i+1,values[i]);
            try(ResultSet r=s.executeQuery()){return r.next();}
        }
    }
    public record Decision(boolean safe,String code){
        static Decision allowed(){return new Decision(true,"SAFE");}
        static Decision reject(String code){return new Decision(false,code);}
    }
}
