package com.paicli.state;

import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.state.R1aDurableModels.DurableRun;
import com.paicli.state.R1aDurableModels.DurableTask;
import com.paicli.state.R1aDurableModels.TaskContextGeneration;
import com.paicli.state.R1aDurableModels.TaskContextPointer;
import com.paicli.state.R1aDurableModels.WorkThread;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class RootAdmissionAtomicityTest {
    @TempDir Path tempDir;
    private static final Instant AT=Instant.parse("2026-07-17T00:00:00Z");

    @ParameterizedTest(name="rollback after {0}")
    @MethodSource("failureStages")
    void everyRootAdmissionStageRollsBackAllAuthority(String stage,String triggerSql)
            throws Exception{
        try(DatabaseManager manager=new DatabaseManager(tempDir.resolve(stage))){
            Connection connection=manager.getConnection();new MigrationRunner(connection).runMigrations();
            try(var statement=connection.createStatement()){statement.execute(triggerSql);}
            R1aDurableStore store=new R1aDurableStore(connection);
            RootAdmissionTransaction.Request request=request();

            assertInstanceOf(RootAdmissionTransaction.Rejected.class,
                    store.admitRootTaskAtomically(request));
            assertAbsent(connection,request);
        }
    }

    @Test
    void successfulAdmissionCommitsOneCompleteAuthorityGraph()throws Exception{
        try(DatabaseManager manager=new DatabaseManager(tempDir.resolve("success"))){
            Connection connection=manager.getConnection();new MigrationRunner(connection).runMigrations();
            R1aDurableStore store=new R1aDurableStore(connection);
            RootAdmissionTransaction.Request request=request();

            RootAdmissionTransaction.Result result=store.admitRootTaskAtomically(request);
            assertTrue(result instanceof RootAdmissionTransaction.Success,
                    ()->"Expected complete Root authority but admission returned "+result);
            var success=(RootAdmissionTransaction.Success)result;
            assertEquals(request.thread().id(),success.threadHead().threadId());
            assertEquals(request.task().id(),success.threadHead().headTaskId());
            assertEquals(request.initialRun().id(),success.pointer().ownerRunId());
            assertEquals(request.manifestId(),success.manifest().id());
            assertEquals("RUNNING",success.runtimeTask().status());
            assertEquals("PLANNING",success.runtimeRun().phase());
            assertEquals(1,count(connection,"work_threads","thread_id",request.thread().id().value()));
            assertEquals(1,count(connection,"task_context_pointers","task_id",request.task().id().value()));
            assertEquals(1,count(connection,"tasks","task_id",request.task().id().value()));
            assertEquals(1,count(connection,"coding_runs","run_id",request.initialRun().id().value()));
            assertEquals(1,count(connection,"task_plan_applicability","task_id",
                    request.task().id().value()));
        }
    }

    private static Stream<Arguments> failureStages(){
        return Stream.of(
                Arguments.of("thread","CREATE TRIGGER root_fail AFTER INSERT ON work_threads BEGIN SELECT RAISE(ABORT,'root stage failure'); END"),
                Arguments.of("task","CREATE TRIGGER root_fail AFTER INSERT ON task_identities BEGIN SELECT RAISE(ABORT,'root stage failure'); END"),
                Arguments.of("artifact","CREATE TRIGGER root_fail AFTER INSERT ON protected_instruction_artifacts BEGIN SELECT RAISE(ABORT,'root stage failure'); END"),
                Arguments.of("manifest-entry","CREATE TRIGGER root_fail AFTER INSERT ON instruction_entries BEGIN SELECT RAISE(ABORT,'root stage failure'); END"),
                Arguments.of("initial-run","CREATE TRIGGER root_fail AFTER INSERT ON durable_runs BEGIN SELECT RAISE(ABORT,'root stage failure'); END"),
                Arguments.of("plan-applicability","CREATE TRIGGER root_fail AFTER INSERT ON task_plan_applicability BEGIN SELECT RAISE(ABORT,'root stage failure'); END"),
                Arguments.of("generation","CREATE TRIGGER root_fail AFTER INSERT ON task_context_generations BEGIN SELECT RAISE(ABORT,'root stage failure'); END"),
                Arguments.of("pointer","CREATE TRIGGER root_fail AFTER INSERT ON task_context_pointers BEGIN SELECT RAISE(ABORT,'root stage failure'); END"),
                Arguments.of("thread-head","CREATE TRIGGER root_fail AFTER INSERT ON thread_heads BEGIN SELECT RAISE(ABORT,'root stage failure'); END"),
                Arguments.of("runtime-task-before","CREATE TRIGGER root_fail BEFORE INSERT ON tasks BEGIN SELECT RAISE(ABORT,'root stage failure'); END"),
                Arguments.of("runtime-task-after","CREATE TRIGGER root_fail AFTER INSERT ON tasks BEGIN SELECT RAISE(ABORT,'root stage failure'); END"),
                Arguments.of("coding-run-before","CREATE TRIGGER root_fail BEFORE INSERT ON coding_runs BEGIN SELECT RAISE(ABORT,'root stage failure'); END"),
                Arguments.of("coding-run-after","CREATE TRIGGER root_fail AFTER INSERT ON coding_runs BEGIN SELECT RAISE(ABORT,'root stage failure'); END"),
                Arguments.of("runtime-task-running","CREATE TRIGGER root_fail AFTER UPDATE OF status ON tasks WHEN NEW.status='RUNNING' BEGIN SELECT RAISE(ABORT,'root stage failure'); END"),
                Arguments.of("runtime-run-phase","CREATE TRIGGER root_fail AFTER INSERT ON coding_runs WHEN NEW.phase='PLANNING' BEGIN SELECT RAISE(ABORT,'root stage failure'); END"),
                Arguments.of("cross-model-link","CREATE TRIGGER root_fail AFTER UPDATE OF status ON tasks WHEN NEW.status='RUNNING' BEGIN DELETE FROM coding_runs WHERE task_id=NEW.task_id; END"),
                Arguments.of("final-validation","CREATE TRIGGER root_fail AFTER UPDATE OF status ON tasks WHEN NEW.status='RUNNING' BEGIN DELETE FROM task_context_pointers WHERE task_id=NEW.task_id; END")
        );
    }

    private static RootAdmissionTransaction.Request request(){
        CanonicalId threadId=CanonicalId.newId(EntityFamily.THREAD);
        CanonicalId taskId=CanonicalId.newId(EntityFamily.TASK);
        CanonicalId runId=CanonicalId.newId(EntityFamily.RUN);
        CanonicalId generationId=CanonicalId.newId(EntityFamily.GENERATION);
        String manifestId="manifest-"+java.util.UUID.randomUUID();
        ProtectedInstructionArtifact artifact=ProtectedInstructionArtifact.inlineUtf8(
                "artifact-"+java.util.UUID.randomUUID(),"frozen","v1","TASK_AUTHORITY",AT);
        var entry=new InstructionManifestCandidate.Entry("entry-"+java.util.UUID.randomUUID(),
                "PROJECT_FILE","AGENTS.md","WORKSPACE",
                InstructionManifestCandidate.Status.APPLIED,0,artifact.contentHash(),
                artifact.id(),"UTF8",null,null);
        var candidate=new InstructionManifestCandidate(1,"root-paicli-agents-v1",List.of(entry));
        String hash=candidate.canonicalHash();
        WorkThread thread=new WorkThread(threadId,"workspace-test","goal","OPEN",AT,AT);
        DurableTask task=new DurableTask(taskId,threadId,null,"goal","CREATED",null,null,AT,null);
        DurableRun run=new DurableRun(runId,taskId,threadId,"INITIAL",null,manifestId,hash,AT,null);
        TaskContextGeneration generation=new TaskContextGeneration(generationId,taskId,null,
                runId,manifestId,hash,"COMMITTED","{}",AT,AT);
        TaskContextPointer pointer=new TaskContextPointer(taskId,generationId,runId,0);
        var runtimeTask=new RootAdmissionTransaction.RuntimeTask(taskId.value(),"goal","mock",
                "model-a","deny",3,"RUNNING");
        var runtimeRun=new RootAdmissionTransaction.RuntimeRun(runId.value(),taskId.value(),
                "INITIAL","PLANNING","",1,0,"skills-disabled","hooks-disabled");
        return new RootAdmissionTransaction.Request(thread,task,manifestId,candidate,
                List.of(artifact),run,generation,pointer,runtimeTask,runtimeRun,
                StructuredPlanModels.PlanApplicability.PLAN_DISABLED,AT);
    }

    private static void assertAbsent(Connection connection,RootAdmissionTransaction.Request r)
            throws Exception{
        assertEquals(0,count(connection,"work_threads","thread_id",r.thread().id().value()));
        assertEquals(0,count(connection,"task_identities","task_id",r.task().id().value()));
        assertEquals(0,count(connection,"protected_instruction_artifacts","artifact_id",r.artifacts().getFirst().id()));
        assertEquals(0,count(connection,"instruction_manifests","instruction_manifest_id",r.manifestId()));
        assertEquals(0,count(connection,"instruction_entries","instruction_manifest_id",r.manifestId()));
        assertEquals(0,count(connection,"durable_runs","run_id",r.initialRun().id().value()));
        assertEquals(0,count(connection,"task_context_generations","generation_id",r.generation().id().value()));
        assertEquals(0,count(connection,"task_context_pointers","task_id",r.task().id().value()));
        assertEquals(0,count(connection,"thread_heads","thread_id",r.thread().id().value()));
        assertEquals(0,count(connection,"tasks","task_id",r.task().id().value()));
        assertEquals(0,count(connection,"coding_runs","run_id",r.initialRun().id().value()));
        assertEquals(0,count(connection,"task_plan_applicability","task_id",
                r.task().id().value()));
        assertEquals(0,count(connection,"events","task_id",r.task().id().value()));
        assertEquals(0,count(connection,"checkpoints","task_id",r.task().id().value()));
        assertEquals(0,count(connection,"task_plans","task_id",r.task().id().value()));
        assertEquals(0,count(connection,"verification_runs","task_id",r.task().id().value()));
        assertEquals(0,count(connection,"hook_runs","task_id",r.task().id().value()));
    }

    private static int count(Connection connection,String table,String column,String value)
            throws Exception{
        try(var statement=connection.prepareStatement(
                "SELECT COUNT(*) FROM "+table+" WHERE "+column+"=?")){
            statement.setString(1,value);try(var result=statement.executeQuery()){
                assertTrue(result.next());return result.getInt(1);
            }
        }
    }
}
