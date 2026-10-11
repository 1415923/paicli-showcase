package com.paicli.interactive.command;

import com.paicli.config.Config;
import com.paicli.context.ContextModels.*;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.state.DatabaseManager;
import com.paicli.state.ContextReadOnlyStatusQuery;
import com.paicli.state.R1aDurableModels.DurableRun;
import com.paicli.state.R1aDurableModels.ExecutionAuthoritySnapshot;
import com.paicli.state.R1aStoreResults.CreateResult;
import com.paicli.state.StateStore;
import com.paicli.support.R3ContextTestFixtures;
import com.paicli.runtime.FencedExecutionGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class R3CompactCommandServiceTest {
    @TempDir Path workspace;

    @Test void statusIsQueryOnlyAndPreviewDoesNotMovePointer()throws Exception{
        String taskId,runId,baseId;ExecutionAuthoritySnapshot authority;
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);var root=admitted.root();
            taskId=root.taskId().value();runId=root.initialRun().id().value();
            baseId=state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,8,"command")).contextGenerationId();
            authority=admitted.guard().current().snapshot();
        }
        var session=new InteractiveSession("session-r3",workspace,"mock","model",Config.MODE_DENY,
                InteractiveSession.TerminalMode.PLAIN,Instant.parse("2026-07-21T00:00:00Z"));
        session.start();session.startTask(taskId,runId);
        var service=new InteractiveCompactionCommandService(workspace,()->authority);
        try(DatabaseManager observer=new DatabaseManager(workspace.resolve(".paicli").resolve("state"))){
            long before=scalar(observer,"PRAGMA data_version");
            assertEquals("COMPACTION_STATUS",service.compact(List.of("status"),session.snapshot()).code());
            assertEquals(before,scalar(observer,"PRAGMA data_version"));
        }
        assertEquals("COMPACTION_PREPARED",service.compactNonInteractive(
                List.of("preview","--task",taskId,"--run",runId)).code());
        assertEquals("COMPACTION_STATUS",service.compactNonInteractive(List.of("status")).code());
        var standalone=new InteractiveCompactionCommandService(workspace);
        assertEquals("CONTEXT_OWNERSHIP_REQUIRED",standalone.compactNonInteractive(
                List.of("preview","--task",taskId,"--run",runId)).code());
        String candidateId;
        try(StateStore state=new StateStore(workspace)){
            assertEquals(baseId,state.contexts.pointer(taskId,runId,OperationKind.CHAT)
                    .orElseThrow().contextGenerationId());
            candidateId=state.contexts.candidateFor(baseId).orElseThrow().contextId();
        }
        assertEquals("CONTEXT_OWNERSHIP_REQUIRED",standalone.compactNonInteractive(
                List.of("apply",candidateId)).code());
        assertEquals("COMPACTION_READY",service.compactNonInteractive(
                List.of("apply",candidateId)).code());
        assertEquals("COMPACTION_INVALID_ARGUMENTS",service.compactNonInteractive(
                List.of("apply","not-a-context")).code());
        try(StateStore state=new StateStore(workspace)){
            assertEquals(candidateId,state.contexts.pointer(taskId,runId,OperationKind.CHAT)
                    .orElseThrow().contextGenerationId());
        }
    }

    @Test void statusOnMissingSchemaDoesNotCreateOrMigrateDatabase(){
        Path empty=workspace.resolve("empty");
        AtomicInteger authorityReads=new AtomicInteger();
        var result=new InteractiveCompactionCommandService(empty,()->{
            authorityReads.incrementAndGet();throw new AssertionError("status requested authority");
        })
                .compactNonInteractive(List.of("status"));
        assertEquals("CONTEXT_UNAVAILABLE",result.code());
        assertEquals(0,authorityReads.get());
        assertNoRuntimeArtifacts(empty);
    }

    @Test void emptyStandalonePreviewWithoutAuthorityIsEffectFree(){
        Path empty=workspace.resolve("standalone-preview-empty");
        AtomicInteger authorityReads=new AtomicInteger();
        String taskId=CanonicalId.newId(EntityFamily.TASK).value();
        String runId=CanonicalId.newId(EntityFamily.RUN).value();
        var result=new InteractiveCompactionCommandService(empty,()->{
            authorityReads.incrementAndGet();return null;
        }).compactNonInteractive(List.of("preview","--task",taskId,"--run",runId));
        assertEquals("CONTEXT_OWNERSHIP_REQUIRED",result.code());
        assertEquals(1,authorityReads.get());
        assertNoRuntimeArtifacts(empty);
    }

    @Test void emptyStandaloneApplyWithoutAuthorityIsEffectFree(){
        Path empty=workspace.resolve("standalone-apply-empty");
        AtomicInteger authorityReads=new AtomicInteger();
        String contextId=CanonicalId.newId(EntityFamily.CONTEXT).value();
        var result=new InteractiveCompactionCommandService(empty,()->{
            authorityReads.incrementAndGet();return null;
        }).compactNonInteractive(List.of("apply",contextId));
        assertEquals("CONTEXT_OWNERSHIP_REQUIRED",result.code());
        assertEquals(1,authorityReads.get());
        assertNoRuntimeArtifacts(empty);
    }

    @Test void emptyInteractivePreviewWithoutSnapshotIsEffectFree(){
        Path empty=workspace.resolve("interactive-preview-empty");
        AtomicInteger authorityReads=new AtomicInteger();
        var result=new InteractiveCompactionCommandService(empty,()->{
            authorityReads.incrementAndGet();return null;
        }).compact(List.of("preview"),session(empty));
        assertEquals("COMPACTION_INVALID_ARGUMENTS",result.code());
        assertEquals(0,authorityReads.get());
        assertNoRuntimeArtifacts(empty);
    }

    @Test void emptyInteractiveApplyWithoutSnapshotIsEffectFree(){
        Path empty=workspace.resolve("interactive-apply-empty");
        AtomicInteger authorityReads=new AtomicInteger();
        String contextId=CanonicalId.newId(EntityFamily.CONTEXT).value();
        var result=new InteractiveCompactionCommandService(empty,()->{
            authorityReads.incrementAndGet();return null;
        }).compact(List.of("apply",contextId),session(empty));
        assertEquals("COMPACTION_INVALID_ARGUMENTS",result.code());
        assertEquals(0,authorityReads.get());
        assertNoRuntimeArtifacts(empty);
    }

    @Test void emptyPreviewSupplierFailureIsEffectFree(){
        Path empty=workspace.resolve("preview-supplier-failure");
        AtomicInteger authorityReads=new AtomicInteger();
        var result=new InteractiveCompactionCommandService(empty,()->{
            authorityReads.incrementAndGet();throw new IllegalStateException("supplier-secret-preview");
        }).compact(List.of("preview"),session(empty));
        assertEquals("COMPACTION_INVALID_ARGUMENTS",result.code());
        assertEquals(0,authorityReads.get());
        assertFalse(result.toString().contains("supplier-secret-preview"));
        assertNoRuntimeArtifacts(empty);
    }

    @Test void emptyApplySupplierFailureIsEffectFree(){
        Path empty=workspace.resolve("apply-supplier-failure");
        AtomicInteger authorityReads=new AtomicInteger();
        String contextId=CanonicalId.newId(EntityFamily.CONTEXT).value();
        var result=new InteractiveCompactionCommandService(empty,()->{
            authorityReads.incrementAndGet();throw new IllegalStateException("supplier-secret-apply");
        }).compact(List.of("apply",contextId),session(empty));
        assertEquals("COMPACTION_INVALID_ARGUMENTS",result.code());
        assertEquals(0,authorityReads.get());
        assertFalse(result.toString().contains("supplier-secret-apply"));
        assertNoRuntimeArtifacts(empty);
    }

    @Test void statusNeverRequestsCallerAuthority()throws Exception{
        try(StateStore state=new StateStore(workspace)){
            R3ContextTestFixtures.admit(state,workspace);
        }
        AtomicInteger authorityReads=new AtomicInteger();
        var service=new InteractiveCompactionCommandService(workspace,()->{
            authorityReads.incrementAndGet();throw new AssertionError("status requested authority");
        });
        assertEquals("COMPACTION_STATUS",service.compactNonInteractive(List.of("status")).code());
        assertEquals(0,authorityReads.get());
    }

    @Test void interactiveMutationsWithoutAuthorityFailBeforeWriting()throws Exception{
        String taskId,runId,sourceId,candidateId;
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);var root=admitted.root();
            taskId=root.taskId().value();runId=root.initialRun().id().value();
            sourceId=state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,8,"missing-command-authority"))
                    .contextGenerationId();
            candidateId=state.contexts.createOrReuseCandidate(sourceId,
                    com.paicli.context.DeterministicContextCompiler.COMPILER_VERSION,
                    com.paicli.context.DeterministicContextCompiler.POLICY_VERSION,
                    admitted.guard().current().snapshot()).contextId();
        }
        var session=new InteractiveSession("session-no-authority",workspace,"mock","model",
                Config.MODE_DENY,InteractiveSession.TerminalMode.PLAIN,Instant.now());
        session.start();session.startTask(taskId,runId);
        var service=new InteractiveCompactionCommandService(workspace);
        long before=scalar(workspace,"SELECT count(*) FROM context_generations");
        assertEquals("COMPACTION_INVALID_ARGUMENTS",
                service.compact(List.of("preview"),session.snapshot()).code());
        assertEquals("COMPACTION_INVALID_ARGUMENTS",
                service.compact(List.of("apply",candidateId),session.snapshot()).code());
        assertEquals(before,scalar(workspace,"SELECT count(*) FROM context_generations"));
        try(StateStore state=new StateStore(workspace)){
            assertEquals(sourceId,state.contexts.pointer(taskId,runId,OperationKind.CHAT)
                    .orElseThrow().contextGenerationId());
        }
    }

    @Test void heartbeatRequiresRefreshedCommandSnapshotAndUnauthorizedAttemptWritesNothing()
            throws Exception{
        String taskId,runId;AtomicReference<ExecutionAuthoritySnapshot> supplied=new AtomicReference<>();
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);var root=admitted.root();
            taskId=root.taskId().value();runId=root.initialRun().id().value();
            state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,8,"heartbeat-command"));
            supplied.set(admitted.guard().current().snapshot());
            admitted.guard().heartbeat("R3_COMPACTION_COMMAND");
            var session=new InteractiveSession("session-heartbeat",workspace,"mock","model",
                    Config.MODE_DENY,InteractiveSession.TerminalMode.PLAIN,Instant.now());
            session.start();session.startTask(taskId,runId);
            var service=new InteractiveCompactionCommandService(workspace,supplied::get);
            long before=scalar(workspace,"SELECT count(*) FROM context_generations "+
                    "WHERE generation_kind='COMPACTED'");
            assertEquals("CONTEXT_OWNERSHIP_UNCERTAIN",
                    service.compactNonInteractive(
                            List.of("preview","--task",taskId,"--run",runId)).code());
            assertEquals(before,scalar(workspace,"SELECT count(*) FROM context_generations "+
                    "WHERE generation_kind='COMPACTED'"));
            supplied.set(admitted.guard().current().snapshot());
            assertEquals("COMPACTION_PREPARED",
                    service.compactNonInteractive(
                            List.of("preview","--task",taskId,"--run",runId)).code());
        }
    }

    @Test void leaseTokenNeverAppearsInCompactionPersistenceOrCommandRendering()throws Exception{
        String secret="lease-token-DO-NOT-RENDER-9381";String taskId,runId;
        ExecutionAuthoritySnapshot authority;
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace,"privacy-owner",secret);
            var root=admitted.root();taskId=root.taskId().value();runId=root.initialRun().id().value();
            state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,8,"lease-privacy"));
            authority=admitted.guard().current().snapshot();
        }
        var session=new InteractiveSession("session-privacy",workspace,"mock","model",Config.MODE_DENY,
                InteractiveSession.TerminalMode.PLAIN,Instant.now());
        session.start();session.startTask(taskId,runId);
        var rendered=new InteractiveCompactionCommandService(workspace,()->authority)
                .compactNonInteractive(List.of(
                        "preview","--task",taskId,"--run",runId));
        assertFalse(rendered.toString().contains(secret));
        assertFalse(authority.toString().contains(secret));
        try(DatabaseManager database=new DatabaseManager(workspace.resolve(".paicli/state"))){
            for(String table:List.of("context_generations","context_segments",
                    "context_source_references","context_generation_pointers",
                    "provider_context_telemetry_links")){
                try(var statement=database.getConnection().createStatement();
                    var rows=statement.executeQuery("SELECT * FROM "+table)){
                    int columns=rows.getMetaData().getColumnCount();
                    while(rows.next())for(int column=1;column<=columns;column++){
                        String value=rows.getString(column);
                        assertFalse(value!=null&&value.contains(secret),table+" leaked lease token");
                    }
                }
            }
        }
    }

    @Test void runningTaskStatusStaysReadableWhilePreviewAndApplyUseDurableEffectGate()throws Exception{
        String taskId,runId,candidateId;ExecutionAuthoritySnapshot authority;
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);var root=admitted.root();
            taskId=root.taskId().value();runId=root.initialRun().id().value();
            String source=state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,8,"command-gate")).contextGenerationId();
            candidateId=state.contexts.createOrReuseCandidate(source,
                    com.paicli.context.DeterministicContextCompiler.COMPILER_VERSION,
                    com.paicli.context.DeterministicContextCompiler.POLICY_VERSION,
                    admitted.guard().current().snapshot()).contextId();
            authority=admitted.guard().current().snapshot();
            admitted.guard().admitOperationOccurrence("19","command-running-provider","PROVIDER",
                    "CHAT",FencedExecutionGuard.canonicalInputHash("input"));
            var session=new InteractiveSession("session-running",workspace,"mock","model",Config.MODE_DENY,
                    InteractiveSession.TerminalMode.PLAIN,Instant.parse("2026-07-21T00:00:00Z"));
            session.start();session.startTask(taskId,runId);
            var service=new InteractiveCompactionCommandService(workspace,()->authority);
            assertEquals("COMPACTION_STATUS",service.compact(List.of("status"),session.snapshot()).code());
            assertEquals("CONTEXT_EXTERNAL_EFFECT_PENDING",
                    service.compactNonInteractive(
                            List.of("preview","--task",taskId,"--run",runId)).code());
            assertEquals("CONTEXT_EXTERNAL_EFFECT_PENDING",
                    service.compactNonInteractive(List.of("apply",candidateId)).code());
            assertEquals(source,state.contexts.pointer(taskId,runId,OperationKind.CHAT)
                    .orElseThrow().contextGenerationId());
        }
    }

    @Test void omittedOperationIsAmbiguousAndExplicitOperationFreezesOnePointer()throws Exception{
        String taskId,runId;long before;ExecutionAuthoritySnapshot authority;
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);var root=admitted.root();
            taskId=root.taskId().value();runId=root.initialRun().id().value();
            state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,8,"chat-target"));
            state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.REPAIR,8,"repair-target"));
            authority=admitted.guard().current().snapshot();
            before=scalar(workspace,"SELECT count(*) FROM context_generations WHERE generation_kind='COMPACTED'");
        }
        var session=new InteractiveSession("session-ambiguity",workspace,"mock","model",Config.MODE_DENY,
                InteractiveSession.TerminalMode.PLAIN,Instant.parse("2026-07-21T00:00:00Z"));
        session.start();session.startTask(taskId,runId);
        var service=new InteractiveCompactionCommandService(workspace,()->authority);
        var ambiguous=service.compactNonInteractive(
                List.of("preview","--task",taskId,"--run",runId));
        assertEquals("COMPACT_TARGET_AMBIGUOUS",ambiguous.code());
        assertTrue(ambiguous.lines().stream().anyMatch(line->line.contains("operation=CHAT")));
        assertTrue(ambiguous.lines().stream().anyMatch(line->line.contains("operation=REPAIR")));
        try(StateStore state=new StateStore(workspace)){
            assertEquals(before,scalar(workspace,"SELECT count(*) FROM context_generations WHERE generation_kind='COMPACTED'"));
        }
        assertEquals("COMPACTION_PREPARED",service.compactNonInteractive(
                List.of("preview","--task",taskId,"--run",runId,
                        "--operation","CHAT")).code());
        try(StateStore state=new StateStore(workspace)){
            String chat=state.contexts.pointer(taskId,runId,OperationKind.CHAT).orElseThrow()
                    .contextGenerationId();
            assertEquals(OperationKind.CHAT,state.contexts.find(
                    state.contexts.candidateFor(chat).orElseThrow().contextId()).orElseThrow().operation());
        }
    }

    @Test void interactiveApplyRejectsAnotherSessionRunAndStatusKeepsCompleteTuples()throws Exception{
        String taskId,runA,runB,candidateId,chat;ExecutionAuthoritySnapshot authority;
        try(StateStore state=new StateStore(workspace)){
            var admitted=R3ContextTestFixtures.admit(state,workspace);var root=admitted.root();
            taskId=root.taskId().value();runA=root.initialRun().id().value();
            chat=state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.CHAT,8,"run-a-chat")).contextGenerationId();
            state.contexts.publishBase(R3ContextTestFixtures.compiled(
                    workspace,admitted,OperationKind.REPAIR,8,"run-a-repair"));
            candidateId=state.contexts.createOrReuseCandidate(chat,
                    com.paicli.context.DeterministicContextCompiler.COMPILER_VERSION,
                    com.paicli.context.DeterministicContextCompiler.POLICY_VERSION,
                    admitted.guard().current().snapshot()).contextId();
            authority=admitted.guard().current().snapshot();
            CanonicalId second=CanonicalId.newId(EntityFamily.RUN);runB=second.value();
            assertEquals(CreateResult.SUCCESS,state.r1aDurable.createRun(new DurableRun(second,
                    root.taskId(),root.threadId(),"RESUME",root.initialRun().id(),
                    root.initialRun().manifestId(),root.initialRun().manifestHash(),Instant.now(),null)));
        }
        cloneReadyContextForRun(workspace,chat,runB);
        var session=new InteractiveSession("session-run-b",workspace,"mock","model",Config.MODE_DENY,
                InteractiveSession.TerminalMode.PLAIN,Instant.parse("2026-07-21T00:00:00Z"));
        session.start();session.startTask(taskId,runB);
        var service=new InteractiveCompactionCommandService(workspace,()->authority);
        assertEquals("COMPACTION_INVALID_ARGUMENTS",service.compact(
                List.of("apply",candidateId),session.snapshot()).code());
        var status=new ContextReadOnlyStatusQuery(workspace).read(taskId,null,taskId,runB);
        assertTrue(status.available());assertEquals(3,status.rows().size());
        assertEquals(java.util.Set.of(runA+":CHAT",runA+":REPAIR",runB+":CHAT"),status.rows().stream()
                .map(row->row.runId()+":"+row.operation()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(status.rows().stream().allMatch(row->row.taskId().equals(taskId)
                &&row.activeContextGenerationId()!=null&&row.pointerVersion()>0));
        assertEquals("CURRENT_SESSION_RUN",status.rows().stream().filter(row->runB.equals(row.runId()))
                .findFirst().orElseThrow().currentSessionRelation());
    }

    private static void cloneReadyContextForRun(Path workspace,String source,String runId)throws Exception{
        String clone=CanonicalId.newId(EntityFamily.CONTEXT).value();
        try(DatabaseManager manager=new DatabaseManager(workspace.resolve(".paicli/state"));
            var statement=manager.getConnection().prepareStatement("""
          INSERT INTO context_generations(context_generation_id,workspace_id,thread_id,task_id,run_id,
            source_authority_generation_id,generation_kind,operation_kind,provider,model,capability_profile,
            compiler_version,policy_version,manifest_hash,plan_revision,steering_revision,source_fingerprint,
            compiled_hash,input_estimate,tool_schema_estimate,output_reserve,safety_margin,context_limit,
            limit_provenance,estimate_provenance,source_context_generation_id,source_pointer_version,
            lifecycle,state_version,failure_code,preview_summary,created_at,updated_at)
          SELECT ?,workspace_id,thread_id,task_id,?,source_authority_generation_id,generation_kind,
            operation_kind,provider,model,capability_profile,compiler_version,policy_version,manifest_hash,
            plan_revision,steering_revision,source_fingerprint,NULL,input_estimate,
            tool_schema_estimate,output_reserve,safety_margin,context_limit,limit_provenance,
            estimate_provenance,NULL,NULL,'PREPARING',0,NULL,NULL,created_at,updated_at
          FROM context_generations WHERE context_generation_id=?
          """)){
            statement.setString(1,clone);statement.setString(2,runId);statement.setString(3,source);
            assertEquals(1,statement.executeUpdate());
        }
        try(DatabaseManager manager=new DatabaseManager(workspace.resolve(".paicli/state"));
            var statement=manager.getConnection().prepareStatement("""
          INSERT INTO context_segments SELECT ?,ordinal,source_class,segment_kind,scope,compactability,
            payload_mode,sanitized_payload,content_hash,estimated_tokens,privacy_class,bounds
          FROM context_segments WHERE context_generation_id=?
          """)){statement.setString(1,clone);statement.setString(2,source);statement.executeUpdate();}
        try(DatabaseManager manager=new DatabaseManager(workspace.resolve(".paicli/state"));
            var statement=manager.getConnection().prepareStatement("""
          INSERT INTO context_source_references SELECT ?,ordinal,source_type,source_id,source_revision,
            source_hash,source_order FROM context_source_references WHERE context_generation_id=?
          """)){statement.setString(1,clone);statement.setString(2,source);statement.executeUpdate();}
        try(DatabaseManager manager=new DatabaseManager(workspace.resolve(".paicli/state"));
            var statement=manager.getConnection().prepareStatement("""
          UPDATE context_generations SET compiled_hash=(SELECT compiled_hash FROM context_generations
            WHERE context_generation_id=?),lifecycle='READY',state_version=1
          WHERE context_generation_id=? AND lifecycle='PREPARING'
          """)){statement.setString(1,source);statement.setString(2,clone);
            assertEquals(1,statement.executeUpdate());}
        try(DatabaseManager manager=new DatabaseManager(workspace.resolve(".paicli/state"));
            var statement=manager.getConnection().prepareStatement("""
          INSERT INTO context_generation_pointers(task_id,run_id,operation_kind,
            active_context_generation_id,pointer_version,updated_at)
          SELECT task_id,?,'CHAT',?,1,updated_at FROM context_generations WHERE context_generation_id=?
          """)){statement.setString(1,runId);statement.setString(2,clone);statement.setString(3,clone);
            assertEquals(1,statement.executeUpdate());}
    }

    private static long scalar(DatabaseManager database,String sql)throws Exception{
        try(var statement=database.getConnection().createStatement();var row=statement.executeQuery(sql)){
            return row.next()?row.getLong(1):-1;
        }
    }
    private static long scalar(Path workspace,String sql)throws Exception{
        try(DatabaseManager manager=new DatabaseManager(workspace.resolve(".paicli/state"));
            var statement=manager.getConnection().createStatement();var row=statement.executeQuery(sql)){
            return row.next()?row.getLong(1):-1;
        }
    }

    private static InteractiveSession.Snapshot session(Path workspace){
        var session=new InteractiveSession("session-effect-free",workspace,"mock","model",
                Config.MODE_DENY,InteractiveSession.TerminalMode.PLAIN,
                Instant.parse("2026-07-22T00:00:00Z"));
        session.start();session.startTask(CanonicalId.newId(EntityFamily.TASK).value(),
                CanonicalId.newId(EntityFamily.RUN).value());
        return session.snapshot();
    }

    private static void assertNoRuntimeArtifacts(Path workspace){
        assertFalse(Files.exists(workspace),"workspace directory was created");
        assertFalse(Files.exists(workspace.resolve(".paicli")),".paicli was created");
        assertFalse(Files.exists(workspace.resolve(".paicli/state")),"state directory was created");
        assertFalse(Files.exists(workspace.resolve(".paicli/state/paicli.db")),"database was created");
        assertFalse(Files.exists(workspace.resolve(".paicli/state/paicli.db-wal")),"WAL was created");
        assertFalse(Files.exists(workspace.resolve(".paicli/state/paicli.db-shm")),"SHM was created");
    }
}
