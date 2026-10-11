package com.paicli.state;

import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/** Durable, bounded user-result gate that precedes Task/Run completion. */
public final class UserResultStore {
    public static final int MAX_CONTENT_CHARS=64*1024;
    private static final String PLANLESS_RESULT_DOMAIN="PAICLI_PLANLESS_RESULT_V1";
    private final Connection connection;

    public UserResultStore(Connection connection){this.connection=Objects.requireNonNull(connection);}

    public Result persist(CanonicalId taskId,CanonicalId runId,CanonicalId revisionId,
            String mode,String content,Instant at)throws SQLException{
        require(revisionId,EntityFamily.PLAN);
        if(!authorityMatches(taskId,runId,revisionId))
            throw new SQLException("USER_RESULT_AUTHORITY_CONFLICT");
        return persistWithEvidence(taskId,runId,revisionId,mode,content,
                evidenceHash(taskId,runId,revisionId),at);
    }

    public Result persistPlanless(CanonicalId taskId,CanonicalId runId,String mode,String content,
            String providerOccurrenceId,String candidateDigest,String verificationEvidence,Instant at)
            throws SQLException{
        if(providerOccurrenceId==null||providerOccurrenceId.isBlank()
                ||candidateDigest==null||!candidateDigest.matches("[0-9a-f]{64}")
                ||verificationEvidence==null||verificationEvidence.isBlank())
            throw new IllegalArgumentException("Planless Result evidence is incomplete");
        return persistWithEvidence(taskId,runId,null,mode,content,
                planlessEvidenceHash(taskId,runId,providerOccurrenceId,candidateDigest,
                        verificationEvidence),at);
    }

    private Result persistWithEvidence(CanonicalId taskId,CanonicalId runId,
            CanonicalId revisionId,String mode,String content,String evidenceHash,Instant at)
            throws SQLException{
        require(taskId,EntityFamily.TASK);require(runId,EntityFamily.RUN);
        Objects.requireNonNull(at);
        String safeMode="MINIMAL".equals(mode)?"MINIMAL":"REQUIRED";
        if(content==null||content.isBlank()||content.length()>MAX_CONTENT_CHARS)
            throw new IllegalArgumentException("User result must be non-empty and bounded");
        String contentHash=sha256(content);
        Optional<Result> existing=find(taskId,runId);
        if(existing.isPresent()){
            Result value=existing.orElseThrow();
            String revisionValue=revisionId==null?null:revisionId.value();
            if(Objects.equals(value.planRevisionId(),revisionValue)
                    &&value.mode().equals(safeMode)&&value.content().equals(content)
                    &&value.contentHash().equals(contentHash)
                    &&value.evidenceHash().equals(evidenceHash)
                    &&"VALID".equals(value.validationStatus()))return value;
            throw new SQLException("USER_RESULT_CONFLICT");
        }
        try(PreparedStatement statement=connection.prepareStatement("""
                INSERT INTO task_user_results(task_id,run_id,plan_revision_id,response_mode,
                    content,content_hash,evidence_hash,validation_status,render_status,created_at)
                VALUES(?,?,?,?,?,?,?,'VALID','PENDING',?)
                """)){
            bind(statement,taskId.value(),runId.value(),
                    revisionId==null?null:revisionId.value(),safeMode,content,
                    contentHash,evidenceHash,at.toString());statement.executeUpdate();
        }
        return new Result(taskId.value(),runId.value(),revisionId==null?null:revisionId.value(),safeMode,content,
                contentHash,evidenceHash,"VALID","PENDING");
    }

    public void markRendered(CanonicalId taskId,CanonicalId runId,String contentHash,Instant at)
            throws SQLException{
        try(PreparedStatement statement=connection.prepareStatement("""
                UPDATE task_user_results SET render_status='RENDERED',rendered_at=?
                WHERE task_id=? AND run_id=? AND content_hash=?
                    AND validation_status='VALID' AND render_status='PENDING'
                """)){
            bind(statement,at.toString(),taskId.value(),runId.value(),contentHash);
            if(statement.executeUpdate()==1)return;
        }
        try(PreparedStatement statement=connection.prepareStatement("""
                SELECT 1 FROM task_user_results WHERE task_id=? AND run_id=? AND content_hash=?
                    AND validation_status='VALID' AND render_status='RENDERED'
                """)){
            bind(statement,taskId.value(),runId.value(),contentHash);
            try(ResultSet row=statement.executeQuery()){
                if(!row.next())throw new SQLException("USER_RESULT_RENDER_COMMIT_FAILED");
            }
        }
    }

    public boolean completionReady(CanonicalId taskId,CanonicalId runId,CanonicalId revisionId,
            String expectedContentHash)throws SQLException{
        require(revisionId,EntityFamily.PLAN);
        return completionReadyWithEvidence(taskId,runId,revisionId,expectedContentHash,
                evidenceHash(taskId,runId,revisionId));
    }

    public boolean completionReadyPlanless(CanonicalId taskId,CanonicalId runId,
            String expectedContentHash,String providerOccurrenceId,String candidateDigest,
            String verificationEvidence)throws SQLException{
        return completionReadyWithEvidence(taskId,runId,null,expectedContentHash,
                planlessEvidenceHash(taskId,runId,providerOccurrenceId,candidateDigest,
                        verificationEvidence));
    }

    private boolean completionReadyWithEvidence(CanonicalId taskId,CanonicalId runId,
            CanonicalId revisionId,String expectedContentHash,String evidenceHash)throws SQLException{
        String revisionPredicate=revisionId==null?"plan_revision_id IS NULL":"plan_revision_id=?";
        try(PreparedStatement statement=connection.prepareStatement("""
                SELECT content,evidence_hash FROM task_user_results
                WHERE task_id=? AND run_id=? AND %s AND content_hash=?
                    AND validation_status='VALID' AND render_status='RENDERED'
                """.formatted(revisionPredicate))){
            if(revisionId==null)bind(statement,taskId.value(),runId.value(),expectedContentHash);
            else bind(statement,taskId.value(),runId.value(),revisionId.value(),expectedContentHash);
            try(ResultSet row=statement.executeQuery()){
                return row.next()&&!row.getString(1).isBlank()
                        &&row.getString(2).equals(evidenceHash);
            }
        }
    }

    public Optional<Result> find(CanonicalId taskId,CanonicalId runId)throws SQLException{
        try(PreparedStatement statement=connection.prepareStatement("""
                SELECT plan_revision_id,response_mode,content,content_hash,evidence_hash,
                    validation_status,render_status FROM task_user_results
                WHERE task_id=? AND run_id=?
                """)){
            bind(statement,taskId.value(),runId.value());
            try(ResultSet row=statement.executeQuery()){
                if(!row.next())return Optional.empty();
                return Optional.of(new Result(taskId.value(),runId.value(),row.getString(1),
                        row.getString(2),row.getString(3),row.getString(4),row.getString(5),
                        row.getString(6),row.getString(7)));
            }
        }
    }

    private boolean authorityMatches(CanonicalId task,CanonicalId run,CanonicalId revision)
            throws SQLException{
        try(PreparedStatement statement=connection.prepareStatement("""
                SELECT 1 FROM task_plan_authorities a
                JOIN plan_run_bindings b ON b.task_id=a.task_id
                    AND b.plan_revision_id=a.active_plan_revision_id
                JOIN plan_revision_lifecycle l ON l.task_id=a.task_id
                    AND l.plan_revision_id=a.active_plan_revision_id AND l.state='APPROVED'
                WHERE a.task_id=? AND a.active_plan_revision_id=? AND b.run_id=?
                    AND b.approval_decision='APPROVED'
                """)){
            bind(statement,task.value(),revision.value(),run.value());
            try(ResultSet row=statement.executeQuery()){if(row.next())return true;}
        }
        // Advisory Plans keep an approved immutable revision without strict Tool authority.
        // Only the Run that obtained that approval may publish its result for the revision.
        try(PreparedStatement statement=connection.prepareStatement("""
                SELECT 1 FROM plan_revision_lifecycle l
                JOIN plan_approvals p ON p.task_id=l.task_id
                    AND p.plan_revision_id=l.plan_revision_id AND p.decision='APPROVED'
                WHERE l.task_id=? AND l.plan_revision_id=? AND l.state='APPROVED'
                    AND p.approved_by_run_id=?
                    AND NOT EXISTS(SELECT 1 FROM task_plan_authorities a WHERE a.task_id=l.task_id)
                """)){
            bind(statement,task.value(),revision.value(),run.value());
            try(ResultSet row=statement.executeQuery()){return row.next();}
        }
    }

    private String planlessEvidenceHash(CanonicalId task,CanonicalId run,
            String providerOccurrenceId,String candidateDigest,String verificationEvidence){
        return sha256(String.join("\n",PLANLESS_RESULT_DOMAIN,task.value(),run.value(),
                providerOccurrenceId,candidateDigest,verificationEvidence));
    }

    private String evidenceHash(CanonicalId task,CanonicalId run,CanonicalId revision)
            throws SQLException{
        StringBuilder material=new StringBuilder(task.value()).append('\n')
                .append(run.value()).append('\n').append(revision.value());
        try(PreparedStatement statement=connection.prepareStatement("""
                SELECT tool_call_id,tool_name,arguments,result_success,result_output,result_error
                FROM tool_calls
                WHERE task_id=? AND tool_call_id LIKE ?
                ORDER BY id
                """)){
            statement.setString(1,task.value());
            statement.setString(2,run.value()+":%");
            try(ResultSet row=statement.executeQuery()){
                while(row.next())for(int i=1;i<=6;i++)material.append('\n')
                        .append(Objects.toString(row.getObject(i),""));
            }
        }
        return sha256(material.toString());
    }

    private static void require(CanonicalId id,EntityFamily family){
        if(id==null||id.family()!=family)throw new IllegalArgumentException("Expected "+family);
    }
    private static void bind(PreparedStatement statement,Object...values)throws SQLException{
        for(int i=0;i<values.length;i++)statement.setObject(i+1,values[i]);
    }
    private static String sha256(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception error){throw new IllegalStateException(error);}
    }
    public record Result(String taskId,String runId,String planRevisionId,String mode,
            String content,String contentHash,String evidenceHash,String validationStatus,
            String renderStatus){}
}
