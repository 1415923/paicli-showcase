package com.paicli.state;

import java.sql.*;
import java.util.*;

public final class CoreDemoCheckpointStore {
    private final Connection connection;
    public CoreDemoCheckpointStore(Connection connection){this.connection=Objects.requireNonNull(connection);}

    public CheckpointRecord saveOrFind(CreateCheckpoint value)throws SQLException{
        Objects.requireNonNull(value);validate(value);
        try(PreparedStatement s=connection.prepareStatement("""
                INSERT OR IGNORE INTO core_demo_checkpoints(
                  checkpoint_id,demo_run_id,task_id,workflow_occurrence_key,phase,
                  initial_tree_hash,verification_evidence_hash,plan_hash,
                  code_intelligence_hash,changed_files_json,snapshot_ids_json,
                  repair_attempt,provider_script_version,approval_profile_version,
                  verifier_config_hash,semantic_hash)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)){
            int i=1;s.setString(i++,value.checkpointId());s.setString(i++,value.demoRunId());
            s.setString(i++,value.taskId());s.setString(i++,value.workflowOccurrenceKey());
            s.setString(i++,"VERIFY_FAILED_BEFORE_REPAIR");s.setString(i++,value.initialTreeHash());
            s.setString(i++,value.verificationEvidenceHash());s.setString(i++,value.planHash());
            s.setString(i++,value.codeIntelligenceHash());s.setString(i++,json(value.changedFiles()));
            s.setString(i++,json(value.snapshotIds()));s.setInt(i++,0);
            s.setString(i++,value.providerScriptVersion());s.setString(i++,value.approvalProfileVersion());
            s.setString(i++,value.verifierConfigHash());s.setString(i,value.semanticHash());
            s.executeUpdate();
        }
        CheckpointRecord found=findByRun(value.demoRunId()).orElseThrow();
        if(!found.matches(value))throw new IllegalStateException("DEMO_CHECKPOINT_FAILURE: checkpoint drift");
        return found;
    }

    public Optional<CheckpointRecord> findByRun(String runId)throws SQLException{
        try(PreparedStatement s=connection.prepareStatement(SELECT+" WHERE demo_run_id=?")){
            s.setString(1,runId);try(ResultSet r=s.executeQuery()){
                return r.next()?Optional.of(map(r)):Optional.empty();
            }
        }
    }

    private static void validate(CreateCheckpoint v){
        id(v.checkpointId());id(v.demoRunId());id(v.taskId());text(v.workflowOccurrenceKey(),128);
        sha(v.initialTreeHash());sha(v.verificationEvidenceHash());sha(v.planHash());
        sha(v.codeIntelligenceHash());sha(v.verifierConfigHash());sha(v.semanticHash());
        text(v.providerScriptVersion(),64);text(v.approvalProfileVersion(),64);
        if(v.changedFiles()==null||v.changedFiles().size()!=1||v.snapshotIds()==null
                ||v.snapshotIds().size()<1||v.snapshotIds().size()>8)
            throw new IllegalArgumentException("Invalid checkpoint collections");
        v.changedFiles().forEach(x->text(x,240));v.snapshotIds().forEach(CoreDemoCheckpointStore::id);
    }
    private static String json(List<String> values){
        return values.stream().map(v->"\""+v.replace("\\","\\\\").replace("\"","\\\"")+"\"")
                .collect(java.util.stream.Collectors.joining(",","[","]"));
    }
    private static List<String> list(String json)throws SQLException{
        try{
            var node=new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            if(!node.isArray()||node.size()>8)throw new SQLException("Invalid checkpoint JSON");
            List<String> out=new ArrayList<>();
            for(var item:node){
                if(!item.isTextual())throw new SQLException("Invalid checkpoint JSON item");
                out.add(item.textValue());
            }
            return List.copyOf(out);
        }catch(java.io.IOException e){throw new SQLException("Invalid checkpoint JSON",e);}
    }
    private static void id(String v){if(v==null||!v.matches("[A-Za-z0-9][A-Za-z0-9._-]{7,127}"))throw new IllegalArgumentException("Invalid id");}
    private static void sha(String v){if(v==null||!v.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid SHA");}
    private static void text(String v,int max){if(v==null||v.isBlank()||v.length()>max||v.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid text");}
    private static CheckpointRecord map(ResultSet r)throws SQLException{
        return new CheckpointRecord(r.getString("checkpoint_id"),r.getString("demo_run_id"),
                r.getString("task_id"),r.getString("workflow_occurrence_key"),r.getString("phase"),
                r.getString("initial_tree_hash"),r.getString("verification_evidence_hash"),
                r.getString("plan_hash"),r.getString("code_intelligence_hash"),
                list(r.getString("changed_files_json")),list(r.getString("snapshot_ids_json")),
                r.getInt("repair_attempt"),r.getString("provider_script_version"),
                r.getString("approval_profile_version"),r.getString("verifier_config_hash"),
                r.getString("semantic_hash"),r.getString("created_at"));
    }
    private static final String SELECT="""
            SELECT checkpoint_id,demo_run_id,task_id,workflow_occurrence_key,phase,
              initial_tree_hash,verification_evidence_hash,plan_hash,code_intelligence_hash,
              changed_files_json,snapshot_ids_json,repair_attempt,provider_script_version,
              approval_profile_version,verifier_config_hash,semantic_hash,created_at
            FROM core_demo_checkpoints
            """;
    public record CreateCheckpoint(String checkpointId,String demoRunId,String taskId,
            String workflowOccurrenceKey,String initialTreeHash,String verificationEvidenceHash,
            String planHash,String codeIntelligenceHash,List<String> changedFiles,
            List<String> snapshotIds,String providerScriptVersion,String approvalProfileVersion,
            String verifierConfigHash,String semanticHash){}
    public record CheckpointRecord(String checkpointId,String demoRunId,String taskId,
            String workflowOccurrenceKey,String phase,String initialTreeHash,
            String verificationEvidenceHash,String planHash,String codeIntelligenceHash,
            List<String> changedFiles,List<String> snapshotIds,int repairAttempt,
            String providerScriptVersion,String approvalProfileVersion,String verifierConfigHash,
            String semanticHash,String createdAt){
        boolean matches(CreateCheckpoint v){return demoRunId.equals(v.demoRunId())
                &&taskId.equals(v.taskId())&&workflowOccurrenceKey.equals(v.workflowOccurrenceKey())
                &&initialTreeHash.equals(v.initialTreeHash())&&verificationEvidenceHash.equals(v.verificationEvidenceHash())
                &&planHash.equals(v.planHash())&&codeIntelligenceHash.equals(v.codeIntelligenceHash())
                &&changedFiles.equals(v.changedFiles())&&snapshotIds.equals(v.snapshotIds())
                &&providerScriptVersion.equals(v.providerScriptVersion())
                &&approvalProfileVersion.equals(v.approvalProfileVersion())
                &&verifierConfigHash.equals(v.verifierConfigHash())&&semanticHash.equals(v.semanticHash());}
    }
}