package com.paicli.state;

import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class TaskContinuityV29PopulatedMigrationTest {
    @TempDir Path temp;

    @Test void populatedV28HistoryRemainsReadableAndV29IsAdditiveAndIdempotent()
            throws Exception {
        try(DatabaseManager manager=new DatabaseManager(temp.resolve("state"))){
            Connection c=manager.getConnection();
            MigrationRunner migrations=new MigrationRunner(c);
            migrations.runMigrationsThrough(28);
            Fixture fixture=populate(c);
            String before=snapshot(c,fixture);

            migrations.runMigrations();
            assertEquals(before,snapshot(c,fixture));
            assertEquals(0,count(c,"task_continuity_segments"));
            assertEquals(1,count(c,"schema_migrations","version=29"));

            migrations.runMigrations();
            assertEquals(before,snapshot(c,fixture));
            assertEquals(0,count(c,"task_continuity_segments"));
            assertEquals(1,count(c,"schema_migrations","version=29"));
        }
    }

    private static Fixture populate(Connection c)throws Exception{
        String thread=CanonicalId.newId(EntityFamily.THREAD).value();
        String task=CanonicalId.newId(EntityFamily.TASK).value();
        String run=CanonicalId.newId(EntityFamily.RUN).value();
        String generation=CanonicalId.newId(EntityFamily.GENERATION).value();
        String occurrence=CanonicalId.newId(EntityFamily.OCCURRENCE).value();
        String plan=CanonicalId.newId(EntityFamily.PLAN).value();
        String manifest="manifest-populated-v28";
        String hash="a".repeat(64),now=Instant.now().toString();
        execute(c,"INSERT INTO work_threads(thread_id,workspace_identity,goal,status,created_at,updated_at) VALUES(?,?,?,'OPEN',?,?)",thread,"workspace","goal",now,now);
        execute(c,"INSERT INTO task_identities(task_id,thread_id,goal,status,created_at) VALUES(?,?,?,'RUNNING',?)",task,thread,"goal",now);
        execute(c,"INSERT INTO instruction_manifests(instruction_manifest_id,task_id,schema_version,resolution_policy_version,canonical_hash,state,created_at) VALUES(?,?,1,'v1',?,'COMMITTED',?)",manifest,task,hash,now);
        execute(c,"UPDATE task_identities SET admitted_manifest_id=?,admitted_manifest_hash=? WHERE task_id=?",manifest,hash,task);
        execute(c,"INSERT INTO thread_heads(thread_id,head_task_id,pointer_version) VALUES(?,?,0)",thread,task);
        execute(c,"INSERT INTO durable_runs(run_id,task_id,thread_id,run_kind,instruction_manifest_id,instruction_manifest_hash,created_at) VALUES(?,?,?,'INITIAL',?,?,?)",run,task,thread,manifest,hash,now);
        execute(c,"INSERT INTO task_context_generations(generation_id,task_id,created_by_run_id,instruction_manifest_id,instruction_manifest_hash,state,created_at,committed_at) VALUES(?,?,?,?,?,'COMMITTED',?,?)",generation,task,run,manifest,hash,now,now);
        execute(c,"INSERT INTO task_context_pointers(task_id,active_generation_id,owner_run_id,pointer_version) VALUES(?,?,?,0)",task,generation,run);
        execute(c,"INSERT INTO side_effect_occurrences(occurrence_id,thread_id,task_id,run_id,iteration_id,logical_operation_key,tool_call_id,operation_type,target,status,canonical_input_hash,result_hash,external_outcome,started_at,completed_at) VALUES(?,?,?,?,?,'op-1','tool-1','READ','src/Main.java','COMPLETED',?,?, 'KNOWN',?,?)",occurrence,thread,task,run,"1",hash,"b".repeat(64),now,now);
        execute(c,"INSERT INTO tasks(task_id,goal,status,provider,model,approval_mode,iterations_completed,max_iterations,created_at,updated_at) VALUES(?,?,'DONE','mock','model','on-request',1,10,?,?)",task,"goal",now,now);
        execute(c,"INSERT INTO coding_runs(run_id,task_id,run_kind,phase,verification_command,max_repair_attempts,started_iteration,last_iteration,created_at,updated_at,completed_at) VALUES(?,?,'INITIAL','COMPLETED','',1,0,1,?,?,?)",run,task,now,now,now);
        execute(c,"INSERT INTO plan_revisions(plan_revision_id,task_id,revision_number,goal,acceptance_criteria,assumptions,risks,verification_strategy,canonical_hash,source_type,provenance,created_by_run_id,created_at) VALUES(?,?,1,'goal','[]','[]','[]','test',?,'MODEL','fixture',?,?)",plan,task,"c".repeat(64),run,now);
        execute(c,"INSERT INTO task_user_results(task_id,run_id,plan_revision_id,response_mode,content,content_hash,evidence_hash,validation_status,render_status,created_at,rendered_at) VALUES(?,?,?,'REQUIRED','done',?,?,'VALID','RENDERED',?,?)",task,run,plan,"d".repeat(64),"e".repeat(64),now,now);
        return new Fixture(thread,task,run,generation,occurrence,plan);
    }

    private static void execute(Connection c,String sql,Object... values)throws Exception{
        try(PreparedStatement p=c.prepareStatement(sql)){
            for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]);
            assertEquals(1,p.executeUpdate(),sql);
        }
    }

    private static String snapshot(Connection c,Fixture value)throws Exception{
        return String.join("|",value.thread,value.task,value.run,value.generation,
                value.occurrence,value.plan,
                Integer.toString(count(c,"work_threads")),
                Integer.toString(count(c,"task_identities")),
                Integer.toString(count(c,"durable_runs")),
                Integer.toString(count(c,"side_effect_occurrences")),
                Integer.toString(count(c,"task_context_generations")),
                Integer.toString(count(c,"task_user_results")));
    }

    private static int count(Connection c,String table)throws Exception{
        return count(c,table,"1=1");
    }
    private static int count(Connection c,String table,String where)throws Exception{
        try(ResultSet r=c.createStatement().executeQuery(
                "SELECT COUNT(*) FROM "+table+" WHERE "+where)){return r.next()?r.getInt(1):0;}
    }
    private record Fixture(String thread,String task,String run,String generation,
            String occurrence,String plan){}
}
