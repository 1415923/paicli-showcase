package com.paicli.state;

import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.state.R1aDurableModels.*;
import com.paicli.state.R1aStoreResults.*;
import com.paicli.state.StructuredPlanModels.ApprovedPlan;
import com.paicli.state.StructuredPlanModels.PlanApplicability;

import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** R1A-only transactional persistence boundary. No v1.1 runtime route calls this store. */
public final class R1aDurableStore {
    private final Connection connection;
    public R1aDurableStore(Connection connection) throws SQLException {
        this.connection = Objects.requireNonNull(connection);
        R1aSqliteSupport.requireForeignKeys(connection);
    }

    public CreateResult createThread(WorkThread value) throws SQLException {
        return tx(() -> {
            if (findWorkThread(value.id()).isPresent()) return CreateResult.ALREADY_EXISTS;
            try (PreparedStatement s = connection.prepareStatement("INSERT INTO work_threads(thread_id,workspace_identity,goal,status,created_at,updated_at) VALUES(?,?,?,?,?,?)")) {
                s.setString(1,value.id().value()); s.setString(2,value.workspaceIdentity()); s.setString(3,value.goal());
                s.setString(4,value.status()); s.setString(5,text(value.createdAt())); s.setString(6,text(value.updatedAt())); s.executeUpdate();
            }
            return CreateResult.SUCCESS;
        });
    }

    public RootAdmissionTransaction.Result admitRootTaskAtomically(
            RootAdmissionTransaction.Request request) {
        Objects.requireNonNull(request);
        InstructionManifestCandidate candidate=request.candidate();
        try { candidate.validateForCommit(); }
        catch (IllegalStateException invalid) {
            return new RootAdmissionTransaction.Rejected(
                    RootAdmissionTransaction.Reason.MANIFEST_REJECTED);
        }
        Map<String,ProtectedInstructionArtifact> artifacts=new HashMap<>();
        for(ProtectedInstructionArtifact artifact:request.artifacts()) {
            if(artifact==null||artifacts.put(artifact.id(),artifact)!=null
                    ||!artifact.verifyIntegrity())
                return new RootAdmissionTransaction.Rejected(
                        RootAdmissionTransaction.Reason.ARTIFACT_FAILURE);
        }
        for(InstructionManifestCandidate.Entry entry:candidate.entries()) {
            if(entry.status()==InstructionManifestCandidate.Status.APPLIED) {
                ProtectedInstructionArtifact artifact=artifacts.get(entry.artifactId());
                if(artifact==null||!artifact.contentHash().equals(entry.contentHash()))
                    return new RootAdmissionTransaction.Rejected(
                            RootAdmissionTransaction.Reason.ARTIFACT_FAILURE);
            }
        }
        if(!rootRequestConsistent(request,candidate.canonicalHash()))
            return new RootAdmissionTransaction.Rejected(
                    RootAdmissionTransaction.Reason.INTEGRITY_VIOLATION);
        try {
            return rootWriteTx(()->persistRootAdmission(request,candidate));
        } catch(RootAdmissionRollback rejected) {
            return new RootAdmissionTransaction.Rejected(rejected.reason);
        } catch(SQLException infrastructureFailure) {
            return new RootAdmissionTransaction.Rejected(
                    RootAdmissionTransaction.Reason.PERSISTENCE_FAILURE);
        }
    }

    private RootAdmissionTransaction.Result persistRootAdmission(
            RootAdmissionTransaction.Request request,
            InstructionManifestCandidate candidate)throws SQLException{
        WorkThread thread=request.thread();DurableTask task=request.task();
        DurableRun run=request.initialRun();TaskContextGeneration generation=request.generation();
        TaskContextPointer pointer=request.pointer();String manifestId=request.manifestId();
        RootAdmissionTransaction.RuntimeTask runtimeTask=request.runtimeTask();
        RootAdmissionTransaction.RuntimeRun runtimeRun=request.runtimeRun();
        if(findWorkThread(thread.id()).isPresent()||findTask(task.id()).isPresent()
                ||findRun(run.id()).isPresent()||findGeneration(generation.id()).isPresent()
                ||findManifestByTask(task.id()).isPresent())
            throw new RootAdmissionRollback(RootAdmissionTransaction.Reason.ALREADY_EXISTS);
        try(PreparedStatement s=connection.prepareStatement(
                "INSERT INTO work_threads(thread_id,workspace_identity,goal,status,created_at,updated_at) VALUES(?,?,?,?,?,?)")){
            s.setString(1,thread.id().value());s.setString(2,thread.workspaceIdentity());
            s.setString(3,thread.goal());s.setString(4,thread.status());
            s.setString(5,text(thread.createdAt()));s.setString(6,text(thread.updatedAt()));s.executeUpdate();
        }
        try(PreparedStatement s=connection.prepareStatement(
                "INSERT INTO task_identities(task_id,thread_id,parent_task_id,goal,status,created_at,terminal_at) VALUES(?,?,?,?,?,?,?)")){
            s.setString(1,task.id().value());s.setString(2,thread.id().value());
            nullable(s,3,task.parentTaskId());s.setString(4,task.goal());s.setString(5,task.status());
            s.setString(6,text(task.createdAt()));nullable(s,7,task.terminalAt());s.executeUpdate();
        }
        for(ProtectedInstructionArtifact artifact:request.artifacts())persistArtifact(artifact);
        String hash=candidate.canonicalHash();
        try(PreparedStatement s=connection.prepareStatement(
                "INSERT INTO instruction_manifests(instruction_manifest_id,task_id,schema_version,resolution_policy_version,canonical_hash,state,created_at) VALUES(?,?,?,?,?,'COMMITTED',?)")){
            s.setString(1,manifestId);s.setString(2,task.id().value());
            s.setInt(3,candidate.schemaVersion());s.setString(4,candidate.resolutionPolicyVersion());
            s.setString(5,hash);s.setString(6,text(request.committedAt()));s.executeUpdate();
        }
        List<InstructionManifest.InstructionEntry> entries=new ArrayList<>();
        for(InstructionManifestCandidate.Entry entry:candidate.canonicalEntries()){
            try(PreparedStatement s=connection.prepareStatement(
                    "INSERT INTO instruction_entries(entry_id,instruction_manifest_id,source_type,source_path,scope,status,applied_order,content_hash,artifact_id,parse_result,exclusion_reason,observed_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")){
                s.setString(1,entry.id());s.setString(2,manifestId);s.setString(3,entry.sourceType());
                s.setString(4,entry.sourcePath());s.setString(5,entry.scope());s.setString(6,entry.status().name());
                nullable(s,7,entry.appliedOrder());nullable(s,8,entry.contentHash());nullable(s,9,entry.artifactId());
                nullable(s,10,entry.parseResult());nullable(s,11,entry.exclusionReason());
                s.setString(12,text(request.committedAt()));s.executeUpdate();
            }
            entries.add(new InstructionManifest.InstructionEntry(entry.id(),entry.sourceType(),
                    entry.sourcePath(),entry.scope(),entry.status(),entry.appliedOrder(),
                    entry.contentHash(),entry.artifactId(),entry.parseResult(),
                    entry.exclusionReason(),request.committedAt()));
        }
        try(PreparedStatement s=connection.prepareStatement(
                "UPDATE task_identities SET admitted_manifest_id=?,admitted_manifest_hash=? WHERE task_id=? AND admitted_manifest_id IS NULL")){
            s.setString(1,manifestId);s.setString(2,hash);s.setString(3,task.id().value());
            if(s.executeUpdate()!=1)throw new RootAdmissionRollback(
                    RootAdmissionTransaction.Reason.INTEGRITY_VIOLATION);
        }
        try(PreparedStatement s=connection.prepareStatement(
                "INSERT INTO durable_runs(run_id,task_id,thread_id,run_kind,resumed_from_run_id,instruction_manifest_id,instruction_manifest_hash,created_at,terminal_status) VALUES(?,?,?,?,?,?,?,?,?)")){
            s.setString(1,run.id().value());s.setString(2,task.id().value());s.setString(3,thread.id().value());
            s.setString(4,run.kind());nullable(s,5,run.resumedFromRunId());s.setString(6,manifestId);
            s.setString(7,hash);s.setString(8,text(run.createdAt()));nullable(s,9,run.terminalStatus());s.executeUpdate();
        }
        try(PreparedStatement s=connection.prepareStatement("""
                INSERT INTO task_plan_applicability(task_id,applicability,
                    declared_by_run_id,declared_at) VALUES(?,?,?,?)
                """)){
            s.setString(1,task.id().value());
            s.setString(2,request.planApplicability().name());
            s.setString(3,run.id().value());s.setString(4,text(request.committedAt()));
            s.executeUpdate();
        }
        try(PreparedStatement s=connection.prepareStatement(
                "INSERT INTO task_context_generations(generation_id,task_id,parent_generation_id,created_by_run_id,instruction_manifest_id,instruction_manifest_hash,state,token_metadata,created_at,committed_at) VALUES(?,?,?,?,?,?,?,?,?,?)")){
            s.setString(1,generation.id().value());s.setString(2,task.id().value());
            nullable(s,3,generation.parentGenerationId());s.setString(4,run.id().value());
            s.setString(5,manifestId);s.setString(6,hash);s.setString(7,generation.state());
            nullable(s,8,generation.tokenMetadata());s.setString(9,text(generation.createdAt()));
            nullable(s,10,generation.committedAt());s.executeUpdate();
        }
        try(PreparedStatement s=connection.prepareStatement(
                "INSERT INTO task_context_pointers(task_id,active_generation_id,owner_run_id,pointer_version) VALUES(?,?,?,?)")){
            s.setString(1,task.id().value());s.setString(2,generation.id().value());
            s.setString(3,run.id().value());s.setLong(4,pointer.version());s.executeUpdate();
        }
        try(PreparedStatement s=connection.prepareStatement(
                "INSERT INTO thread_heads(thread_id,head_task_id,pointer_version) VALUES(?,?,0)")){
            s.setString(1,thread.id().value());s.setString(2,task.id().value());s.executeUpdate();
        }
        try(PreparedStatement s=connection.prepareStatement("""
                INSERT INTO tasks(task_id,goal,status,provider,model,approval_mode,
                    iterations_completed,max_iterations)
                VALUES(?,?,'CREATED',?,?,?,?,?)
                """)){
            s.setString(1,runtimeTask.taskId());s.setString(2,runtimeTask.goal());
            nullable(s,3,runtimeTask.provider());nullable(s,4,runtimeTask.model());
            nullable(s,5,runtimeTask.approvalMode());s.setInt(6,0);
            s.setInt(7,runtimeTask.maxIterations());s.executeUpdate();
        }
        try(PreparedStatement s=connection.prepareStatement("""
                INSERT INTO coding_runs(run_id,task_id,run_kind,phase,verification_command,
                    max_repair_attempts,started_iteration,last_iteration,
                    skills_manifest_sha256,hooks_manifest_sha256)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """)){
            s.setString(1,runtimeRun.runId());s.setString(2,runtimeRun.taskId());
            s.setString(3,runtimeRun.runKind());s.setString(4,runtimeRun.phase());
            nullable(s,5,runtimeRun.verificationCommand());
            s.setInt(6,runtimeRun.maxRepairAttempts());
            s.setInt(7,runtimeRun.startedIteration());
            s.setInt(8,runtimeRun.startedIteration());
            nullable(s,9,runtimeRun.skillsManifestHash());
            nullable(s,10,runtimeRun.hooksManifestHash());s.executeUpdate();
        }
        try(PreparedStatement s=connection.prepareStatement("""
                UPDATE tasks SET status='RUNNING',last_error=NULL,completed_at=NULL,
                    updated_at=datetime('now') WHERE task_id=? AND status='CREATED'
                """)){
            s.setString(1,runtimeTask.taskId());
            if(s.executeUpdate()!=1)throw new RootAdmissionRollback(
                    RootAdmissionTransaction.Reason.INTEGRITY_VIOLATION);
        }
        validateCompleteRootAuthority(request,hash);
        InstructionManifest manifest=new InstructionManifest(manifestId,task.id(),
                candidate.schemaVersion(),candidate.resolutionPolicyVersion(),hash,
                request.committedAt(),entries);
        DurableTask admitted=new DurableTask(task.id(),thread.id(),task.parentTaskId(),task.goal(),
                task.status(),manifestId,hash,task.createdAt(),task.terminalAt());
        return new RootAdmissionTransaction.Success(thread,admitted,manifest,run,generation,
                pointer,new ThreadHead(thread.id(),task.id(),0),runtimeTask,runtimeRun);
    }

    private static boolean rootRequestConsistent(RootAdmissionTransaction.Request r,String hash){
        return r.task().threadId().equals(r.thread().id())&&r.task().parentTaskId()==null
                &&r.task().manifestId()==null&&r.task().manifestHash()==null
                &&r.initialRun().taskId().equals(r.task().id())
                &&r.initialRun().threadId().equals(r.thread().id())
                &&"INITIAL".equals(r.initialRun().kind())&&r.initialRun().resumedFromRunId()==null
                &&r.manifestId().equals(r.initialRun().manifestId())
                &&hash.equals(r.initialRun().manifestHash())
                &&r.generation().taskId().equals(r.task().id())
                &&r.generation().createdByRunId().equals(r.initialRun().id())
                &&r.manifestId().equals(r.generation().manifestId())
                &&hash.equals(r.generation().manifestHash())
                &&"COMMITTED".equals(r.generation().state())
                &&r.pointer().taskId().equals(r.task().id())
                &&r.pointer().generationId().equals(r.generation().id())
                &&r.pointer().ownerRunId().equals(r.initialRun().id())
                &&r.pointer().version()==0
                &&r.runtimeTask().taskId().equals(r.task().id().value())
                &&"RUNNING".equals(r.runtimeTask().status())
                &&r.runtimeRun().runId().equals(r.initialRun().id().value())
                &&r.runtimeRun().taskId().equals(r.task().id().value())
                &&"INITIAL".equals(r.runtimeRun().runKind())
                &&(r.planApplicability()==PlanApplicability.PLAN_REQUIRED
                    ?"PLANNING".equals(r.runtimeRun().phase())
                    :r.planApplicability()==PlanApplicability.PLAN_DISABLED
                        &&("PLANNING".equals(r.runtimeRun().phase())
                            ||"EXECUTING".equals(r.runtimeRun().phase())));
    }

    private void validateCompleteRootAuthority(RootAdmissionTransaction.Request r,String hash)
            throws SQLException{
        DurableTask task=findTask(r.task().id()).orElseThrow(()->new SQLException("Root Task missing"));
        DurableRun run=findRun(r.initialRun().id()).orElseThrow(()->new SQLException("Root Run missing"));
        TaskContextGeneration generation=findGeneration(r.generation().id())
                .orElseThrow(()->new SQLException("Root Generation missing"));
        TaskContextPointer pointer=readContextPointer(r.task().id())
                .orElseThrow(()->new SQLException("Root Pointer missing"));
        ThreadHead head=readThreadHead(r.thread().id())
                .orElseThrow(()->new SQLException("Root Head missing"));
        InstructionManifest manifest=findManifestByTask(r.task().id())
                .orElseThrow(()->new SQLException("Root Manifest missing"));
        if(!r.manifestId().equals(task.manifestId())||!hash.equals(task.manifestHash())
                ||!r.manifestId().equals(manifest.id())||!run.taskId().equals(task.id())
                ||!generation.taskId().equals(task.id())||!pointer.generationId().equals(generation.id())
                ||!pointer.ownerRunId().equals(run.id())||!head.headTaskId().equals(task.id()))
            throw new RootAdmissionRollback(RootAdmissionTransaction.Reason.INTEGRITY_VIOLATION);
        try(PreparedStatement s=connection.prepareStatement("""
                SELECT applicability,declared_by_run_id FROM task_plan_applicability
                WHERE task_id=?
                """)){
            s.setString(1,r.task().id().value());try(ResultSet row=s.executeQuery()){
                if(!row.next()||!r.planApplicability().name().equals(row.getString(1))
                        ||!r.initialRun().id().value().equals(row.getString(2)))
                    throw new RootAdmissionRollback(
                            RootAdmissionTransaction.Reason.INTEGRITY_VIOLATION);
            }
        }
        try(PreparedStatement s=connection.prepareStatement("""
                SELECT status,goal,provider,model,approval_mode,max_iterations
                FROM tasks WHERE task_id=?
                """)){
            s.setString(1,r.runtimeTask().taskId());try(ResultSet row=s.executeQuery()){
                if(!row.next()||!r.runtimeTask().status().equals(row.getString(1))
                        ||!r.runtimeTask().goal().equals(row.getString(2))
                        ||!Objects.equals(r.runtimeTask().provider(),row.getString(3))
                        ||!Objects.equals(r.runtimeTask().model(),row.getString(4))
                        ||!Objects.equals(r.runtimeTask().approvalMode(),row.getString(5))
                        ||r.runtimeTask().maxIterations()!=row.getInt(6))
                    throw new RootAdmissionRollback(
                            RootAdmissionTransaction.Reason.INTEGRITY_VIOLATION);
            }
        }
        try(PreparedStatement s=connection.prepareStatement("""
                SELECT task_id,run_kind,phase,verification_command,max_repair_attempts,
                    started_iteration,last_iteration,skills_manifest_sha256,hooks_manifest_sha256
                FROM coding_runs WHERE run_id=?
                """)){
            RootAdmissionTransaction.RuntimeRun expected=r.runtimeRun();
            s.setString(1,expected.runId());try(ResultSet row=s.executeQuery()){
                if(!row.next()||!expected.taskId().equals(row.getString(1))
                        ||!expected.runKind().equals(row.getString(2))
                        ||!expected.phase().equals(row.getString(3))
                        ||!Objects.equals(expected.verificationCommand(),row.getString(4))
                        ||expected.maxRepairAttempts()!=row.getInt(5)
                        ||expected.startedIteration()!=row.getInt(6)
                        ||expected.startedIteration()!=row.getInt(7)
                        ||!Objects.equals(expected.skillsManifestHash(),row.getString(8))
                        ||!Objects.equals(expected.hooksManifestHash(),row.getString(9)))
                    throw new RootAdmissionRollback(
                            RootAdmissionTransaction.Reason.INTEGRITY_VIOLATION);
            }
        }
    }

    public Optional<WorkThread> findWorkThread(CanonicalId id) throws SQLException {
        require(id,EntityFamily.THREAD);
        try (PreparedStatement s=connection.prepareStatement("SELECT workspace_identity,goal,status,created_at,updated_at FROM work_threads WHERE thread_id=?")) {
            s.setString(1,id.value()); try(ResultSet r=s.executeQuery()) {
                return r.next()?Optional.of(new WorkThread(id,r.getString(1),r.getString(2),r.getString(3),instant(r.getString(4)),instant(r.getString(5)))):Optional.empty();
            }
        }
    }

    public List<WorkThread> listWorkThreads(String workspaceIdentity) throws SQLException {
        if (blank(workspaceIdentity)) throw new IllegalArgumentException(
                "workspace identity is required");
        List<WorkThread> result = new ArrayList<>();
        try (PreparedStatement s = connection.prepareStatement("""
                SELECT thread_id,goal,status,created_at,updated_at
                FROM work_threads
                WHERE workspace_identity=?
                ORDER BY thread_id
                """)) {
            s.setString(1, workspaceIdentity);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    result.add(new WorkThread(CanonicalId.parse(r.getString(1)),
                            workspaceIdentity, r.getString(2), r.getString(3),
                            instant(r.getString(4)), instant(r.getString(5))));
                }
            }
        }
        return List.copyOf(result);
    }

    public List<WorkThread> findWorkThreadsByPrefix(String typedPrefix)
            throws SQLException {
        if (typedPrefix == null || !typedPrefix.matches("thr_[0-9a-f]{12,31}")) {
            throw new IllegalArgumentException("Typed Short Thread ID required");
        }
        List<WorkThread> result = new ArrayList<>();
        try (PreparedStatement s = connection.prepareStatement("""
                SELECT thread_id,workspace_identity,goal,status,created_at,updated_at
                FROM work_threads
                WHERE thread_id LIKE ? ESCAPE '\\'
                ORDER BY thread_id
                """)) {
            s.setString(1, typedPrefix + "%");
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    result.add(new WorkThread(CanonicalId.parse(r.getString(1)),
                            r.getString(2), r.getString(3), r.getString(4),
                            instant(r.getString(5)), instant(r.getString(6))));
                }
            }
        }
        return List.copyOf(result);
    }

    public CreateResult createTask(DurableTask value) throws SQLException {
        return tx(() -> {
            if (findTask(value.id()).isPresent()) return CreateResult.ALREADY_EXISTS;
            if (findWorkThread(value.threadId()).isEmpty()) return CreateResult.NOT_FOUND;
            if (value.parentTaskId()!=null) {
                Optional<DurableTask> parent=findTask(value.parentTaskId());
                if (parent.isEmpty()) return CreateResult.NOT_FOUND;
                if (!parent.get().threadId().equals(value.threadId())) return CreateResult.INTEGRITY_VIOLATION;
            }
            try(PreparedStatement s=connection.prepareStatement("INSERT INTO task_identities(task_id,thread_id,parent_task_id,goal,status,created_at,terminal_at) VALUES(?,?,?,?,?,?,?)")) {
                s.setString(1,value.id().value()); s.setString(2,value.threadId().value()); nullable(s,3,value.parentTaskId());
                s.setString(4,value.goal()); s.setString(5,value.status()); s.setString(6,text(value.createdAt())); nullable(s,7,value.terminalAt()); s.executeUpdate();
            }
            if(readThreadHead(value.threadId()).isEmpty()) try(PreparedStatement s=connection.prepareStatement("INSERT INTO thread_heads(thread_id,head_task_id,pointer_version) VALUES(?,?,0)")) {
                s.setString(1,value.threadId().value()); s.setString(2,value.id().value()); s.executeUpdate();
            }
            return CreateResult.SUCCESS;
        });
    }

    public Optional<DurableTask> findTask(CanonicalId id) throws SQLException {
        require(id,EntityFamily.TASK);
        try(PreparedStatement s=connection.prepareStatement("SELECT thread_id,parent_task_id,goal,status,admitted_manifest_id,admitted_manifest_hash,created_at,terminal_at FROM task_identities WHERE task_id=?")) {
            s.setString(1,id.value()); try(ResultSet r=s.executeQuery()) {
                return r.next()?Optional.of(new DurableTask(id,CanonicalId.parse(r.getString(1)),canonical(r.getString(2)),r.getString(3),r.getString(4),r.getString(5),r.getString(6),instant(r.getString(7)),nullableInstant(r.getString(8)))):Optional.empty();
            }
        }
    }

    public Optional<ThreadHead> readThreadHead(CanonicalId id) throws SQLException {
        require(id,EntityFamily.THREAD);
        try(PreparedStatement s=connection.prepareStatement("SELECT head_task_id,pointer_version FROM thread_heads WHERE thread_id=?")) {
            s.setString(1,id.value()); try(ResultSet r=s.executeQuery()) { return r.next()?Optional.of(new ThreadHead(id,CanonicalId.parse(r.getString(1)),r.getLong(2))):Optional.empty(); }
        }
    }

    public PointerCasResult compareAndSetThreadHead(CanonicalId threadId,CanonicalId expected,long version,CanonicalId next) throws SQLException {
        require(threadId,EntityFamily.THREAD); require(expected,EntityFamily.TASK); require(next,EntityFamily.TASK);
        return tx(() -> {
            Optional<ThreadHead> current=readThreadHead(threadId); if(current.isEmpty()) return PointerCasResult.NOT_FOUND;
            Optional<DurableTask> target=findTask(next); if(target.isEmpty()) return PointerCasResult.NOT_FOUND;
            if(!target.get().threadId().equals(threadId)) return PointerCasResult.INTEGRITY_VIOLATION;
            if(current.get().version()!=version) return PointerCasResult.VERSION_CONFLICT;
            if(!current.get().headTaskId().equals(expected)) return PointerCasResult.EXPECTATION_MISMATCH;
            try(PreparedStatement s=connection.prepareStatement("UPDATE thread_heads SET head_task_id=?,pointer_version=pointer_version+1 WHERE thread_id=? AND head_task_id=? AND pointer_version=?")) {
                s.setString(1,next.value()); s.setString(2,threadId.value()); s.setString(3,expected.value()); s.setLong(4,version);
                if(s.executeUpdate()==1) return PointerCasResult.SUCCESS;
                Optional<ThreadHead> after=readThreadHead(threadId);
                if(after.isEmpty()) return PointerCasResult.NOT_FOUND;
                return after.get().version()!=version ? PointerCasResult.VERSION_CONFLICT
                        : PointerCasResult.EXPECTATION_MISMATCH;
            }
        });
    }

    public CreateResult createRun(DurableRun value) throws SQLException {
        return tx(() -> {
            if(findRun(value.id()).isPresent()) return CreateResult.ALREADY_EXISTS;
            Optional<DurableTask> task=findTask(value.taskId()); if(task.isEmpty()) return CreateResult.NOT_FOUND;
            if(!task.get().threadId().equals(value.threadId())||!Objects.equals(task.get().manifestId(),value.manifestId())||!Objects.equals(task.get().manifestHash(),value.manifestHash())) return CreateResult.INTEGRITY_VIOLATION;
            try(PreparedStatement s=connection.prepareStatement("INSERT INTO durable_runs(run_id,task_id,thread_id,run_kind,resumed_from_run_id,instruction_manifest_id,instruction_manifest_hash,created_at,terminal_status) VALUES(?,?,?,?,?,?,?,?,?)")) {
                s.setString(1,value.id().value()); s.setString(2,value.taskId().value()); s.setString(3,value.threadId().value()); s.setString(4,value.kind()); nullable(s,5,value.resumedFromRunId());
                s.setString(6,value.manifestId()); s.setString(7,value.manifestHash()); s.setString(8,text(value.createdAt())); nullable(s,9,value.terminalStatus()); s.executeUpdate();
            }
            return CreateResult.SUCCESS;
        });
    }

    public Optional<DurableRun> findRun(CanonicalId id) throws SQLException {
        require(id,EntityFamily.RUN);
        try(PreparedStatement s=connection.prepareStatement("SELECT task_id,thread_id,run_kind,resumed_from_run_id,instruction_manifest_id,instruction_manifest_hash,created_at,terminal_status FROM durable_runs WHERE run_id=?")) {
            s.setString(1,id.value()); try(ResultSet r=s.executeQuery()) {
                return r.next()?Optional.of(new DurableRun(id,CanonicalId.parse(r.getString(1)),CanonicalId.parse(r.getString(2)),r.getString(3),canonical(r.getString(4)),r.getString(5),r.getString(6),instant(r.getString(7)),r.getString(8))):Optional.empty();
            }
        }
    }

    public List<DurableRun> findRunsByTask(CanonicalId taskId) throws SQLException {
        require(taskId, EntityFamily.TASK);
        List<DurableRun> result = new ArrayList<>();
        try (PreparedStatement s = connection.prepareStatement("""
                SELECT run_id,thread_id,run_kind,resumed_from_run_id,
                       instruction_manifest_id,instruction_manifest_hash,
                       created_at,terminal_status
                FROM durable_runs
                WHERE task_id=?
                ORDER BY created_at,run_id
                """)) {
            s.setString(1, taskId.value());
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    result.add(new DurableRun(CanonicalId.parse(r.getString(1)), taskId,
                            CanonicalId.parse(r.getString(2)), r.getString(3),
                            canonical(r.getString(4)), r.getString(5), r.getString(6),
                            instant(r.getString(7)), r.getString(8)));
                }
            }
        }
        return List.copyOf(result);
    }

    public CreateResult createGeneration(TaskContextGeneration value) throws SQLException {
        return tx(() -> {
            if(findGeneration(value.id()).isPresent()) return CreateResult.ALREADY_EXISTS;
            Optional<DurableRun> run=findRun(value.createdByRunId()); if(run.isEmpty()) return CreateResult.NOT_FOUND;
            if(!run.get().taskId().equals(value.taskId())||!run.get().manifestId().equals(value.manifestId())||!run.get().manifestHash().equals(value.manifestHash())) return CreateResult.INTEGRITY_VIOLATION;
            if(value.parentGenerationId()!=null) { Optional<TaskContextGeneration> parent=findGeneration(value.parentGenerationId()); if(parent.isEmpty()) return CreateResult.NOT_FOUND; if(!parent.get().taskId().equals(value.taskId())) return CreateResult.INTEGRITY_VIOLATION; }
            try(PreparedStatement s=connection.prepareStatement("INSERT INTO task_context_generations(generation_id,task_id,parent_generation_id,created_by_run_id,instruction_manifest_id,instruction_manifest_hash,state,token_metadata,created_at,committed_at) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                s.setString(1,value.id().value()); s.setString(2,value.taskId().value()); nullable(s,3,value.parentGenerationId()); s.setString(4,value.createdByRunId().value());
                s.setString(5,value.manifestId()); s.setString(6,value.manifestHash()); s.setString(7,value.state()); nullable(s,8,value.tokenMetadata()); s.setString(9,text(value.createdAt())); nullable(s,10,value.committedAt()); s.executeUpdate();
            }
            return CreateResult.SUCCESS;
        });
    }

    public Optional<TaskContextGeneration> findGeneration(CanonicalId id) throws SQLException {
        require(id,EntityFamily.GENERATION);
        try(PreparedStatement s=connection.prepareStatement("SELECT task_id,parent_generation_id,created_by_run_id,instruction_manifest_id,instruction_manifest_hash,state,token_metadata,created_at,committed_at FROM task_context_generations WHERE generation_id=?")) {
            s.setString(1,id.value()); try(ResultSet r=s.executeQuery()) {
                return r.next()?Optional.of(new TaskContextGeneration(id,CanonicalId.parse(r.getString(1)),canonical(r.getString(2)),CanonicalId.parse(r.getString(3)),r.getString(4),r.getString(5),r.getString(6),r.getString(7),instant(r.getString(8)),nullableInstant(r.getString(9)))):Optional.empty();
            }
        }
    }

    public CreateResult createContextPointer(TaskContextPointer value) throws SQLException {
        return tx(() -> {
            if(readContextPointer(value.taskId()).isPresent()) return CreateResult.ALREADY_EXISTS;
            Optional<TaskContextGeneration> generation=findGeneration(value.generationId()); Optional<DurableRun> run=findRun(value.ownerRunId());
            if(generation.isEmpty()||run.isEmpty()) return CreateResult.NOT_FOUND;
            if(!generation.get().taskId().equals(value.taskId())||!run.get().taskId().equals(value.taskId())||!"COMMITTED".equals(generation.get().state())) return CreateResult.INTEGRITY_VIOLATION;
            try(PreparedStatement s=connection.prepareStatement("INSERT INTO task_context_pointers(task_id,active_generation_id,owner_run_id,pointer_version) VALUES(?,?,?,?)")) {
                s.setString(1,value.taskId().value()); s.setString(2,value.generationId().value()); s.setString(3,value.ownerRunId().value()); s.setLong(4,value.version()); s.executeUpdate();
            }
            return CreateResult.SUCCESS;
        });
    }

    public Optional<TaskContextPointer> readContextPointer(CanonicalId taskId) throws SQLException {
        require(taskId,EntityFamily.TASK);
        try(PreparedStatement s=connection.prepareStatement("SELECT active_generation_id,owner_run_id,pointer_version FROM task_context_pointers WHERE task_id=?")) {
            s.setString(1,taskId.value()); try(ResultSet r=s.executeQuery()) { return r.next()?Optional.of(new TaskContextPointer(taskId,CanonicalId.parse(r.getString(1)),CanonicalId.parse(r.getString(2)),r.getLong(3))):Optional.empty(); }
        }
    }

    public PointerCasResult compareAndSetContextPointer(CanonicalId taskId,CanonicalId expectedGeneration,CanonicalId expectedOwner,long version,CanonicalId nextGeneration,CanonicalId nextOwner) throws SQLException {
        require(taskId,EntityFamily.TASK); require(expectedGeneration,EntityFamily.GENERATION); require(expectedOwner,EntityFamily.RUN); require(nextGeneration,EntityFamily.GENERATION); require(nextOwner,EntityFamily.RUN);
        return tx(() -> {
            Optional<TaskContextPointer> current=readContextPointer(taskId); if(current.isEmpty()) return PointerCasResult.NOT_FOUND;
            Optional<TaskContextGeneration> generation=findGeneration(nextGeneration); Optional<DurableRun> run=findRun(nextOwner);
            if(generation.isEmpty()||run.isEmpty()) return PointerCasResult.NOT_FOUND;
            if(!generation.get().taskId().equals(taskId)||!run.get().taskId().equals(taskId)||!"COMMITTED".equals(generation.get().state())) return PointerCasResult.INTEGRITY_VIOLATION;
            if(current.get().version()!=version) return PointerCasResult.VERSION_CONFLICT;
            if(!current.get().generationId().equals(expectedGeneration)
                    || !current.get().ownerRunId().equals(expectedOwner)) {
                return PointerCasResult.EXPECTATION_MISMATCH;
            }
            try(PreparedStatement s=connection.prepareStatement("UPDATE task_context_pointers SET active_generation_id=?,owner_run_id=?,pointer_version=pointer_version+1 WHERE task_id=? AND active_generation_id=? AND owner_run_id=? AND pointer_version=?")) {
                s.setString(1,nextGeneration.value()); s.setString(2,nextOwner.value()); s.setString(3,taskId.value()); s.setString(4,expectedGeneration.value()); s.setString(5,expectedOwner.value()); s.setLong(6,version);
                if(s.executeUpdate()==1) return PointerCasResult.SUCCESS;
                Optional<TaskContextPointer> after=readContextPointer(taskId);
                if(after.isEmpty()) return PointerCasResult.NOT_FOUND;
                return after.get().version()!=version ? PointerCasResult.VERSION_CONFLICT
                        : PointerCasResult.EXPECTATION_MISMATCH;
            }
        });
    }

    public CreateResult appendConstraintRevision(ConstraintRevision value) throws SQLException {
        return tx(() -> {
            if(findConstraintRevision(value.id()).isPresent()) return CreateResult.ALREADY_EXISTS;
            if(findTask(value.taskId()).isEmpty()) return CreateResult.NOT_FOUND;
            try(PreparedStatement s=connection.prepareStatement("INSERT INTO constraint_revisions(constraint_revision_id,task_id,revision_number,canonical_content,canonical_hash,created_at) VALUES(?,?,?,?,?,?)")) {
                s.setString(1,value.id()); s.setString(2,value.taskId().value()); s.setLong(3,value.revisionNumber()); s.setString(4,value.canonicalContent()); s.setString(5,value.canonicalHash()); s.setString(6,text(value.createdAt())); s.executeUpdate();
            }
            return CreateResult.SUCCESS;
        });
    }

    public Optional<ConstraintRevision> findConstraintRevision(String id) throws SQLException {
        try(PreparedStatement s=connection.prepareStatement("SELECT task_id,revision_number,canonical_content,canonical_hash,created_at FROM constraint_revisions WHERE constraint_revision_id=?")) {
            s.setString(1,id); try(ResultSet r=s.executeQuery()) { return r.next()?Optional.of(new ConstraintRevision(id,CanonicalId.parse(r.getString(1)),r.getLong(2),r.getString(3),r.getString(4),instant(r.getString(5)))):Optional.empty(); }
        }
    }

    public CreateResult createConstraintActivation(ConstraintActivation value) throws SQLException {
        return tx(() -> {
            if(readConstraintActivation(value.taskId()).isPresent()) return CreateResult.ALREADY_EXISTS;
            Optional<ConstraintRevision> revision=findConstraintRevision(value.revisionId()); if(revision.isEmpty()) return CreateResult.NOT_FOUND;
            if(!revision.get().taskId().equals(value.taskId())) return CreateResult.INTEGRITY_VIOLATION;
            try(PreparedStatement s=connection.prepareStatement("INSERT INTO task_constraint_activations(task_id,active_revision_id,activated_by_run_id,pointer_version,activated_at) VALUES(?,?,?,?,?)")) {
                s.setString(1,value.taskId().value()); s.setString(2,value.revisionId()); nullable(s,3,value.activatedByRunId()); s.setLong(4,value.version()); s.setString(5,text(value.activatedAt())); s.executeUpdate();
            }
            return CreateResult.SUCCESS;
        });
    }

    public Optional<ConstraintActivation> readConstraintActivation(CanonicalId taskId) throws SQLException {
        require(taskId,EntityFamily.TASK);
        try(PreparedStatement s=connection.prepareStatement("SELECT active_revision_id,activated_by_run_id,pointer_version,activated_at FROM task_constraint_activations WHERE task_id=?")) {
            s.setString(1,taskId.value()); try(ResultSet r=s.executeQuery()) { return r.next()?Optional.of(new ConstraintActivation(taskId,r.getString(1),canonical(r.getString(2)),r.getLong(3),instant(r.getString(4)))):Optional.empty(); }
        }
    }

    public PointerCasResult compareAndSetConstraintActivation(CanonicalId taskId,String expected,long version,String next,CanonicalId runId,Instant at) throws SQLException {
        require(taskId,EntityFamily.TASK); if(runId!=null) require(runId,EntityFamily.RUN);
        return tx(() -> {
            Optional<ConstraintActivation> current=readConstraintActivation(taskId); if(current.isEmpty()) return PointerCasResult.NOT_FOUND;
            Optional<ConstraintRevision> revision=findConstraintRevision(next); if(revision.isEmpty()) return PointerCasResult.NOT_FOUND;
            if(!revision.get().taskId().equals(taskId)) return PointerCasResult.INTEGRITY_VIOLATION;
            if(current.get().version()!=version) return PointerCasResult.VERSION_CONFLICT;
            if(!current.get().revisionId().equals(expected)) return PointerCasResult.EXPECTATION_MISMATCH;
            try(PreparedStatement s=connection.prepareStatement("UPDATE task_constraint_activations SET active_revision_id=?,activated_by_run_id=?,pointer_version=pointer_version+1,activated_at=? WHERE task_id=? AND active_revision_id=? AND pointer_version=?")) {
                s.setString(1,next); nullable(s,2,runId); s.setString(3,text(at)); s.setString(4,taskId.value()); s.setString(5,expected); s.setLong(6,version);
                if(s.executeUpdate()==1) return PointerCasResult.SUCCESS;
                Optional<ConstraintActivation> after=readConstraintActivation(taskId);
                if(after.isEmpty()) return PointerCasResult.NOT_FOUND;
                return after.get().version()!=version ? PointerCasResult.VERSION_CONFLICT
                        : PointerCasResult.EXPECTATION_MISMATCH;
            }
        });
    }

    public ManifestCommitResult commitManifest(CanonicalId taskId,String manifestId,InstructionManifestCandidate candidate,List<ProtectedInstructionArtifact> artifacts,Instant committedAt) throws SQLException {
        require(taskId,EntityFamily.TASK);
        try { candidate.validateForCommit(); } catch(IllegalStateException exception) { return new ManifestCommitResult.Rejected(ManifestCommitResult.Reason.INVALID_STATE); }
        Map<String,ProtectedInstructionArtifact> byId=new HashMap<>();
        for(ProtectedInstructionArtifact artifact:artifacts) if(byId.put(artifact.id(),artifact)!=null||!artifact.verifyIntegrity()) return new ManifestCommitResult.Rejected(ManifestCommitResult.Reason.INTEGRITY_VIOLATION);
        for(InstructionManifestCandidate.Entry entry:candidate.entries()) if(entry.status()==InstructionManifestCandidate.Status.APPLIED) {
            ProtectedInstructionArtifact artifact=byId.get(entry.artifactId());
            if(artifact==null||!artifact.contentHash().equals(entry.contentHash())) return new ManifestCommitResult.Rejected(ManifestCommitResult.Reason.INTEGRITY_VIOLATION);
        }
        return tx(() -> {
            Optional<DurableTask> task=findTask(taskId); if(task.isEmpty()) return new ManifestCommitResult.Rejected(ManifestCommitResult.Reason.NOT_FOUND);
            if(task.get().manifestId()!=null||findManifestByTask(taskId).isPresent()) return new ManifestCommitResult.Rejected(ManifestCommitResult.Reason.ALREADY_EXISTS);
            for(ProtectedInstructionArtifact artifact:artifacts) persistArtifact(artifact);
            String hash=candidate.canonicalHash();
            try(PreparedStatement s=connection.prepareStatement("INSERT INTO instruction_manifests(instruction_manifest_id,task_id,schema_version,resolution_policy_version,canonical_hash,state,created_at) VALUES(?,?,?,?,?,'COMMITTED',?)")) {
                s.setString(1,manifestId); s.setString(2,taskId.value()); s.setInt(3,candidate.schemaVersion()); s.setString(4,candidate.resolutionPolicyVersion()); s.setString(5,hash); s.setString(6,text(committedAt)); s.executeUpdate();
            }
            List<InstructionManifest.InstructionEntry> entries=new ArrayList<>();
            for(InstructionManifestCandidate.Entry entry:candidate.canonicalEntries()) {
                try(PreparedStatement s=connection.prepareStatement("INSERT INTO instruction_entries(entry_id,instruction_manifest_id,source_type,source_path,scope,status,applied_order,content_hash,artifact_id,parse_result,exclusion_reason,observed_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    s.setString(1,entry.id()); s.setString(2,manifestId); s.setString(3,entry.sourceType()); s.setString(4,entry.sourcePath()); s.setString(5,entry.scope()); s.setString(6,entry.status().name());
                    nullable(s,7,entry.appliedOrder()); nullable(s,8,entry.contentHash()); nullable(s,9,entry.artifactId()); nullable(s,10,entry.parseResult()); nullable(s,11,entry.exclusionReason()); s.setString(12,text(committedAt)); s.executeUpdate();
                }
                entries.add(new InstructionManifest.InstructionEntry(entry.id(),entry.sourceType(),entry.sourcePath(),entry.scope(),entry.status(),entry.appliedOrder(),entry.contentHash(),entry.artifactId(),entry.parseResult(),entry.exclusionReason(),committedAt));
            }
            try(PreparedStatement s=connection.prepareStatement("UPDATE task_identities SET admitted_manifest_id=?,admitted_manifest_hash=? WHERE task_id=? AND admitted_manifest_id IS NULL")) {
                s.setString(1,manifestId); s.setString(2,hash); s.setString(3,taskId.value()); if(s.executeUpdate()!=1) throw new SQLException("Manifest authority update failed");
            }
            return new ManifestCommitResult.Success(new InstructionManifest(manifestId,taskId,candidate.schemaVersion(),candidate.resolutionPolicyVersion(),hash,committedAt,entries));
        });
    }

    public Optional<InstructionManifest> findManifestByTask(CanonicalId taskId) throws SQLException {
        require(taskId,EntityFamily.TASK);
        try(PreparedStatement s=connection.prepareStatement("SELECT instruction_manifest_id,schema_version,resolution_policy_version,canonical_hash,created_at FROM instruction_manifests WHERE task_id=? AND state='COMMITTED'")) {
            s.setString(1,taskId.value()); try(ResultSet r=s.executeQuery()) {
                if(!r.next()) return Optional.empty(); String id=r.getString(1);
                return Optional.of(new InstructionManifest(id,taskId,r.getInt(2),r.getString(3),r.getString(4),instant(r.getString(5)),readManifestEntries(id)));
            }
        }
    }

    public Optional<ProtectedInstructionArtifact> findInlineArtifact(String id) throws SQLException {
        return findArtifact(id, reference -> null);
    }

    public R1aStoreResults.ArtifactReadResult inspectInlineArtifact(String id)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT protected_payload FROM protected_instruction_artifacts WHERE artifact_id=?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return new R1aStoreResults.ArtifactReadResult.Unavailable(
                            R1aStoreResults.ArtifactReadResult.Reason.NOT_FOUND);
                }
                if (result.getBytes(1) == null) {
                    return new R1aStoreResults.ArtifactReadResult.Unavailable(
                            R1aStoreResults.ArtifactReadResult.Reason.EXTERNAL_REFERENCE_UNAVAILABLE);
                }
            }
        }
        Optional<ProtectedInstructionArtifact> artifact = findInlineArtifact(id);
        return artifact.<R1aStoreResults.ArtifactReadResult>map(
                R1aStoreResults.ArtifactReadResult.Available::new).orElseGet(
                () -> new R1aStoreResults.ArtifactReadResult.Unavailable(
                        R1aStoreResults.ArtifactReadResult.Reason.INTEGRITY_FAILURE));
    }

    public Optional<ProtectedInstructionArtifact> findArtifact(String id,ArtifactPayloadResolver resolver) throws SQLException {
        try(PreparedStatement s=connection.prepareStatement("SELECT content_hash,protected_payload,sanitizer_version,retention_class,created_at FROM protected_instruction_artifacts WHERE artifact_id=?")) {
            s.setString(1,id); try(ResultSet r=s.executeQuery()) {
                if(!r.next()) return Optional.empty(); byte[] payload=r.getBytes(2);
                if(payload==null) return readReferencedArtifact(id,r.getString(1),resolver,r.getString(3),r.getString(4),instant(r.getString(5)));
                ProtectedInstructionArtifact artifact=ProtectedInstructionArtifact.inline(id,payload,r.getString(3),r.getString(4),instant(r.getString(5)));
                return artifact.contentHash().equals(r.getString(1))&&artifact.verifyIntegrity()?Optional.of(artifact):Optional.empty();
            }
        }
    }

    private Optional<ProtectedInstructionArtifact> readReferencedArtifact(String id,String hash,
            ArtifactPayloadResolver resolver,String sanitizer,String retention,Instant createdAt)throws SQLException{
        try(PreparedStatement s=connection.prepareStatement("SELECT storage_reference FROM protected_instruction_artifacts WHERE artifact_id=?")){s.setString(1,id);try(ResultSet r=s.executeQuery()){if(!r.next())return Optional.empty();String reference=r.getString(1);byte[] resolved=resolver.resolve(reference);ProtectedInstructionArtifact artifact=ProtectedInstructionArtifact.referenced(id,reference,hash,resolved,sanitizer,retention,createdAt);return artifact.verifyIntegrity()?Optional.of(artifact):Optional.empty();}}
    }

    private void persistArtifact(ProtectedInstructionArtifact value) throws SQLException {
        try(PreparedStatement s=connection.prepareStatement("""
                SELECT content_hash,protected_payload,storage_reference,sanitizer_version,
                       retention_class,created_at
                FROM protected_instruction_artifacts WHERE artifact_id=?
                """)) {
            s.setString(1,value.id());
            try(ResultSet r=s.executeQuery()) {
                if(r.next()) {
                    boolean same = r.getString(1).equals(value.contentHash())
                            && Arrays.equals(r.getBytes(2), value.protectedPayload())
                            && Objects.equals(r.getString(3), value.storageReference())
                            && r.getString(4).equals(value.sanitizerVersion())
                            && r.getString(5).equals(value.retentionClass())
                            && instant(r.getString(6)).equals(value.createdAt());
                    if(!same) throw new SQLException("Artifact identity collision");
                    return;
                }
            }
        }
        try(PreparedStatement s=connection.prepareStatement("INSERT INTO protected_instruction_artifacts(artifact_id,content_hash,protected_payload,storage_reference,sanitizer_version,retention_class,integrity_status,created_at) VALUES(?,?,?,?,?,?,'VERIFIED',?)")) {
            s.setString(1,value.id()); s.setString(2,value.contentHash()); if(value.protectedPayload()==null)s.setNull(3,Types.BLOB);else s.setBytes(3,value.protectedPayload()); nullable(s,4,value.storageReference());
            s.setString(5,value.sanitizerVersion()); s.setString(6,value.retentionClass()); s.setString(7,text(value.createdAt())); s.executeUpdate();
        }
    }

    private List<InstructionManifest.InstructionEntry> readManifestEntries(String manifestId) throws SQLException {
        List<InstructionManifest.InstructionEntry> result=new ArrayList<>();
        try(PreparedStatement s=connection.prepareStatement("SELECT entry_id,source_type,source_path,scope,status,applied_order,content_hash,artifact_id,parse_result,exclusion_reason,observed_at FROM instruction_entries WHERE instruction_manifest_id=? ORDER BY CASE status WHEN 'APPLIED' THEN 0 ELSE 1 END,applied_order,entry_id")) {
            s.setString(1,manifestId); try(ResultSet r=s.executeQuery()) { while(r.next()) { int order=r.getInt(6); boolean noOrder=r.wasNull(); result.add(new InstructionManifest.InstructionEntry(r.getString(1),r.getString(2),r.getString(3),r.getString(4),InstructionManifestCandidate.Status.valueOf(r.getString(5)),noOrder?null:order,r.getString(7),r.getString(8),r.getString(9),r.getString(10),instant(r.getString(11)))); } }
        }
        return List.copyOf(result);
    }

    public OccurrenceAdmissionResult admitOccurrence(SideEffectOccurrence value) throws SQLException {
        if(!"STARTED".equals(value.status())||value.completedAt()!=null) return OccurrenceAdmissionResult.INVALID_STATE;
        return tx(() -> {
            if(findOccurrence(value.id()).isPresent()||findOccurrenceByIdentity(value.taskId(),value.logicalOperationKey()).isPresent()) return OccurrenceAdmissionResult.ALREADY_EXISTS;
            Optional<DurableRun> run=findRun(value.runId()); if(run.isEmpty()) return OccurrenceAdmissionResult.NOT_FOUND;
            if(!run.get().taskId().equals(value.taskId())||!run.get().threadId().equals(value.threadId())) return OccurrenceAdmissionResult.INTEGRITY_VIOLATION;
            try(PreparedStatement s=connection.prepareStatement("INSERT INTO side_effect_occurrences(occurrence_id,thread_id,task_id,run_id,iteration_id,logical_operation_key,tool_call_id,operation_type,target,status,canonical_input_hash,result_hash,external_outcome,final_candidate_digest,started_at,completed_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) { bindOccurrence(s,value); s.executeUpdate(); }
            return OccurrenceAdmissionResult.SUCCESS;
        });
    }

    public OccurrenceTransitionResult completeOccurrence(CanonicalId id,String status,String resultHash,String outcome,Instant completedAt) throws SQLException {
        require(id,EntityFamily.OCCURRENCE); if(!List.of("COMPLETED","FAILED","UNKNOWN").contains(status)) return OccurrenceTransitionResult.INVALID_STATE;
        return tx(() -> {
            Optional<SideEffectOccurrence> value=findOccurrence(id); if(value.isEmpty()) return OccurrenceTransitionResult.NOT_FOUND;
            if(!"STARTED".equals(value.get().status())) return OccurrenceTransitionResult.INVALID_STATE;
            try(PreparedStatement s=connection.prepareStatement("UPDATE side_effect_occurrences SET status=?,result_hash=?,external_outcome=?,completed_at=? WHERE occurrence_id=? AND status='STARTED'")) {
                s.setString(1,status); nullable(s,2,resultHash); nullable(s,3,outcome); s.setString(4,text(completedAt)); s.setString(5,id.value());
                return s.executeUpdate()==1?OccurrenceTransitionResult.SUCCESS:OccurrenceTransitionResult.INVALID_STATE;
            }
        });
    }

    public Optional<SideEffectOccurrence> findOccurrence(CanonicalId id) throws SQLException {
        require(id,EntityFamily.OCCURRENCE);
        try(PreparedStatement s=connection.prepareStatement(occurrenceSelect()+" WHERE occurrence_id=?")) { s.setString(1,id.value()); try(ResultSet r=s.executeQuery()){ return readOccurrence(r); } }
    }

    public Optional<SideEffectOccurrence> findOccurrenceByIdentity(CanonicalId taskId,String operationKey) throws SQLException {
        require(taskId,EntityFamily.TASK);
        try(PreparedStatement s=connection.prepareStatement(occurrenceSelect()+" WHERE task_id=? AND logical_operation_key=?")) { s.setString(1,taskId.value()); s.setString(2,operationKey); try(ResultSet r=s.executeQuery()){ return readOccurrence(r); } }
    }

    public Optional<SideEffectOccurrence> findAcceptedProviderFinal(CanonicalId taskId,
            CanonicalId runId,int iteration)throws SQLException{
        require(taskId,EntityFamily.TASK);require(runId,EntityFamily.RUN);
        if(iteration<1)return Optional.empty();
        try(PreparedStatement statement=connection.prepareStatement(occurrenceSelect()+"""
                 WHERE task_id=? AND run_id=? AND iteration_id=?
                    AND operation_type='PROVIDER' AND status='COMPLETED'
                    AND result_hash IS NOT NULL AND final_candidate_digest IS NOT NULL
                ORDER BY occurrence_id
                """)){
            statement.setString(1,taskId.value());statement.setString(2,runId.value());
            statement.setString(3,Integer.toString(iteration));
            try(ResultSet rows=statement.executeQuery()){
                if(!rows.next())return Optional.empty();
                SideEffectOccurrence result=mapOccurrence(rows);
                if(rows.next())throw new SQLException("PROVIDER_FINAL_OCCURRENCE_NOT_UNIQUE");
                return Optional.of(result);
            }
        }
    }

    public List<SideEffectOccurrence> findRecoveryBlockingOccurrences(CanonicalId taskId,CanonicalId runId,String operationType) throws SQLException {
        require(taskId,EntityFamily.TASK); require(runId,EntityFamily.RUN); List<SideEffectOccurrence> result=new ArrayList<>();
        try(PreparedStatement s=connection.prepareStatement(occurrenceSelect()+" WHERE task_id=? AND run_id=? AND operation_type=? AND status IN ('STARTED','UNKNOWN') ORDER BY started_at,occurrence_id")) {
            s.setString(1,taskId.value()); s.setString(2,runId.value()); s.setString(3,operationType); try(ResultSet r=s.executeQuery()){ while(r.next()) result.add(mapOccurrence(r)); }
        }
        return List.copyOf(result);
    }

    public boolean hasStartedOrUnknownOccurrence(CanonicalId taskId) throws SQLException {
        require(taskId,EntityFamily.TASK);
        try(PreparedStatement s=connection.prepareStatement("SELECT 1 FROM side_effect_occurrences WHERE task_id=? AND status IN ('STARTED','UNKNOWN') LIMIT 1")) { s.setString(1,taskId.value()); try(ResultSet r=s.executeQuery()){ return r.next(); } }
    }


    /** Durable Tool admissions only; Provider-operation occurrences are a separate authority. */
    public int countToolOccurrencesByTask(CanonicalId taskId) throws SQLException {
        require(taskId,EntityFamily.TASK);
        try(PreparedStatement s=connection.prepareStatement(
                "SELECT COUNT(*) FROM side_effect_occurrences WHERE task_id=? AND operation_type='TOOL'")) {
            s.setString(1,taskId.value());
            try(ResultSet r=s.executeQuery()){ return r.next()?r.getInt(1):0; }
        }
    }

    private static String occurrenceSelect(){ return "SELECT occurrence_id,thread_id,task_id,run_id,iteration_id,logical_operation_key,tool_call_id,operation_type,target,status,canonical_input_hash,result_hash,external_outcome,final_candidate_digest,started_at,completed_at FROM side_effect_occurrences"; }
    private static Optional<SideEffectOccurrence> readOccurrence(ResultSet r)throws SQLException{return r.next()?Optional.of(mapOccurrence(r)):Optional.empty();}
    private static SideEffectOccurrence mapOccurrence(ResultSet r)throws SQLException{return new SideEffectOccurrence(CanonicalId.parse(r.getString(1)),CanonicalId.parse(r.getString(2)),CanonicalId.parse(r.getString(3)),CanonicalId.parse(r.getString(4)),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getString(9),r.getString(10),r.getString(11),r.getString(12),r.getString(13),r.getString(14),instant(r.getString(15)),nullableInstant(r.getString(16)));}
    private static void bindOccurrence(PreparedStatement s,SideEffectOccurrence v)throws SQLException{s.setString(1,v.id().value());s.setString(2,v.threadId().value());s.setString(3,v.taskId().value());s.setString(4,v.runId().value());s.setString(5,v.iterationId());s.setString(6,v.logicalOperationKey());nullable(s,7,v.toolCallId());s.setString(8,v.operationType());s.setString(9,v.target());s.setString(10,v.status());s.setString(11,v.canonicalInputHash());nullable(s,12,v.resultHash());nullable(s,13,v.externalOutcome());nullable(s,14,v.finalCandidateDigest());s.setString(15,text(v.startedAt()));nullable(s,16,v.completedAt());}

    public OwnershipResult acquireOwnership(CanonicalId taskId,String instanceId,CanonicalId runId,String leaseToken,Instant acquiredAt,Instant expiresAt)throws SQLException{
        require(taskId,EntityFamily.TASK);require(runId,EntityFamily.RUN);if(blank(instanceId)||blank(leaseToken)||!expiresAt.isAfter(acquiredAt))return OwnershipResult.INVALID_STATE;
        return tx(()->acquireOwnershipLocked(taskId,instanceId,runId,leaseToken,acquiredAt,expiresAt));
    }

    OwnershipResult acquireOwnershipInTransaction(CanonicalId taskId,String instanceId,CanonicalId runId,
            String leaseToken,Instant acquiredAt,Instant expiresAt)throws SQLException{
        requireActiveCallerTransaction();
        require(taskId,EntityFamily.TASK);require(runId,EntityFamily.RUN);
        if(blank(instanceId)||blank(leaseToken)||!expiresAt.isAfter(acquiredAt))return OwnershipResult.INVALID_STATE;
        return acquireOwnershipLocked(taskId,instanceId,runId,leaseToken,acquiredAt,expiresAt);
    }

    private OwnershipResult acquireOwnershipLocked(CanonicalId taskId,String instanceId,CanonicalId runId,
            String leaseToken,Instant acquiredAt,Instant expiresAt)throws SQLException{
        Optional<DurableRun> run=findRun(runId);if(run.isEmpty())return OwnershipResult.NOT_FOUND;
        if(!run.get().taskId().equals(taskId))return OwnershipResult.INTEGRITY_VIOLATION;
        Optional<OwnershipLease> existing=readOwnership(taskId);
        if(existing.isPresent())return existing.get().expiresAt().isAfter(acquiredAt)&&"ACTIVE".equals(existing.get().state())
                ?OwnershipResult.OWNERSHIP_CONFLICT:OwnershipResult.EXPIRED;
        try(PreparedStatement s=connection.prepareStatement("INSERT INTO task_ownership_leases(task_id,owner_instance_id,owner_run_id,lease_token,fencing_token,cas_version,state,acquired_at,heartbeat_at,expires_at) VALUES(?,?,?,?,1,1,'ACTIVE',?,?,?)")){
            s.setString(1,taskId.value());s.setString(2,instanceId);s.setString(3,runId.value());s.setString(4,leaseToken);
            s.setString(5,text(acquiredAt));s.setString(6,text(acquiredAt));s.setString(7,text(expiresAt));s.executeUpdate();}
        return OwnershipResult.SUCCESS;
    }

    public OwnershipResult heartbeatOwnership(CanonicalId taskId,String instanceId,CanonicalId runId,String leaseToken,long version,Instant heartbeatAt,Instant expiresAt)throws SQLException{
        require(taskId,EntityFamily.TASK);require(runId,EntityFamily.RUN);if(blank(instanceId)||blank(leaseToken)||!expiresAt.isAfter(heartbeatAt))return OwnershipResult.INVALID_STATE;
        return tx(()->{Optional<OwnershipLease> lease=readOwnership(taskId);if(lease.isEmpty())return OwnershipResult.NOT_FOUND;if(lease.get().casVersion()!=version)return OwnershipResult.VERSION_CONFLICT;
            if(!lease.get().ownerInstanceId().equals(instanceId)||!lease.get().ownerRunId().equals(runId)||!lease.get().leaseToken().equals(leaseToken))return OwnershipResult.OWNERSHIP_CONFLICT;
            if(!"ACTIVE".equals(lease.get().state()))return OwnershipResult.INVALID_STATE;if(heartbeatAt.isBefore(lease.get().heartbeatAt()))return OwnershipResult.INVALID_STATE;if(!heartbeatAt.isBefore(lease.get().expiresAt()))return OwnershipResult.EXPIRED;
            try(PreparedStatement s=connection.prepareStatement("UPDATE task_ownership_leases SET heartbeat_at=?,expires_at=?,cas_version=cas_version+1 WHERE task_id=? AND owner_instance_id=? AND owner_run_id=? AND lease_token=? AND cas_version=? AND state='ACTIVE'")){s.setString(1,text(heartbeatAt));s.setString(2,text(expiresAt));s.setString(3,taskId.value());s.setString(4,instanceId);s.setString(5,runId.value());s.setString(6,leaseToken);s.setLong(7,version);if(s.executeUpdate()==1)return OwnershipResult.SUCCESS;Optional<OwnershipLease> after=readOwnership(taskId);if(after.isEmpty())return OwnershipResult.NOT_FOUND;if(after.get().casVersion()!=version)return OwnershipResult.VERSION_CONFLICT;if(!after.get().ownerInstanceId().equals(instanceId)||!after.get().ownerRunId().equals(runId)||!after.get().leaseToken().equals(leaseToken))return OwnershipResult.OWNERSHIP_CONFLICT;return OwnershipResult.INVALID_STATE;}});
    }

    public OwnershipResult takeoverOwnership(CanonicalId taskId,CanonicalId runId,String instanceId,String leaseToken,long version,Instant takeoverAt,Instant expiresAt,Duration grace)throws SQLException{
        require(taskId,EntityFamily.TASK);require(runId,EntityFamily.RUN);if(blank(instanceId)||blank(leaseToken)||grace==null||grace.isNegative()||!expiresAt.isAfter(takeoverAt))return OwnershipResult.INVALID_STATE;
        return tx(()->{if(readContextPointer(taskId).isPresent())return OwnershipResult.INVALID_STATE;Optional<OwnershipLease> lease=readOwnership(taskId);if(lease.isEmpty())return OwnershipResult.NOT_FOUND;if(lease.get().casVersion()!=version)return OwnershipResult.VERSION_CONFLICT;if(takeoverAt.isBefore(lease.get().expiresAt().plus(grace)))return OwnershipResult.OWNERSHIP_CONFLICT;
            Optional<DurableRun> run=findRun(runId);if(run.isEmpty())return OwnershipResult.NOT_FOUND;if(!run.get().taskId().equals(taskId))return OwnershipResult.INTEGRITY_VIOLATION;if(hasStartedOrUnknownOccurrence(taskId))return OwnershipResult.INVALID_STATE;
            try(PreparedStatement s=connection.prepareStatement("UPDATE task_ownership_leases SET owner_instance_id=?,owner_run_id=?,lease_token=?,fencing_token=fencing_token+1,cas_version=cas_version+1,state='ACTIVE',acquired_at=?,heartbeat_at=?,expires_at=? WHERE task_id=? AND cas_version=?")){s.setString(1,instanceId);s.setString(2,runId.value());s.setString(3,leaseToken);s.setString(4,text(takeoverAt));s.setString(5,text(takeoverAt));s.setString(6,text(expiresAt));s.setString(7,taskId.value());s.setLong(8,version);return s.executeUpdate()==1?OwnershipResult.SUCCESS:OwnershipResult.VERSION_CONFLICT;}});
    }

    public OwnershipResult takeoverOwnershipAndContextPointer(CanonicalId taskId,CanonicalId runId,
            String instanceId,String leaseToken,long leaseVersion,CanonicalId expectedGeneration,
            CanonicalId expectedPointerOwner,long pointerVersion,Instant takeoverAt,
            Instant expiresAt,Duration grace)throws SQLException{
        require(taskId,EntityFamily.TASK);require(runId,EntityFamily.RUN);
        require(expectedGeneration,EntityFamily.GENERATION);require(expectedPointerOwner,EntityFamily.RUN);
        if(blank(instanceId)||blank(leaseToken)||grace==null||grace.isNegative()||!expiresAt.isAfter(takeoverAt))return OwnershipResult.INVALID_STATE;
        return tx(()->takeoverOwnershipAndContextPointerLocked(taskId,runId,instanceId,leaseToken,leaseVersion,
                expectedGeneration,expectedPointerOwner,pointerVersion,takeoverAt,expiresAt,grace));
    }

    OwnershipResult takeoverOwnershipAndContextPointerInTransaction(CanonicalId taskId,CanonicalId runId,
            String instanceId,String leaseToken,long leaseVersion,CanonicalId expectedGeneration,
            CanonicalId expectedPointerOwner,long pointerVersion,Instant takeoverAt,
            Instant expiresAt,Duration grace)throws SQLException{
        requireActiveCallerTransaction();
        require(taskId,EntityFamily.TASK);require(runId,EntityFamily.RUN);
        require(expectedGeneration,EntityFamily.GENERATION);require(expectedPointerOwner,EntityFamily.RUN);
        if(blank(instanceId)||blank(leaseToken)||grace==null||grace.isNegative()||!expiresAt.isAfter(takeoverAt))return OwnershipResult.INVALID_STATE;
        return takeoverOwnershipAndContextPointerLocked(taskId,runId,instanceId,leaseToken,leaseVersion,
                expectedGeneration,expectedPointerOwner,pointerVersion,takeoverAt,expiresAt,grace);
    }

    private OwnershipResult takeoverOwnershipAndContextPointerLocked(CanonicalId taskId,CanonicalId runId,
            String instanceId,String leaseToken,long leaseVersion,CanonicalId expectedGeneration,
            CanonicalId expectedPointerOwner,long pointerVersion,Instant takeoverAt,
            Instant expiresAt,Duration grace)throws SQLException{
        Optional<OwnershipLease> lease=readOwnership(taskId);Optional<TaskContextPointer> pointer=readContextPointer(taskId);
        if(lease.isEmpty()||pointer.isEmpty())return OwnershipResult.NOT_FOUND;
        if(lease.get().casVersion()!=leaseVersion||pointer.get().version()!=pointerVersion)return OwnershipResult.VERSION_CONFLICT;
        if(!pointer.get().generationId().equals(expectedGeneration)||!pointer.get().ownerRunId().equals(expectedPointerOwner))return OwnershipResult.VERSION_CONFLICT;
        if(takeoverAt.isBefore(lease.get().expiresAt().plus(grace)))return OwnershipResult.OWNERSHIP_CONFLICT;
        Optional<DurableRun> run=findRun(runId);if(run.isEmpty())return OwnershipResult.NOT_FOUND;
        if(!run.get().taskId().equals(taskId))return OwnershipResult.INTEGRITY_VIOLATION;
        if(hasStartedOrUnknownOccurrence(taskId))return OwnershipResult.INVALID_STATE;
        try(PreparedStatement s=connection.prepareStatement("UPDATE task_ownership_leases SET owner_instance_id=?,owner_run_id=?,lease_token=?,fencing_token=fencing_token+1,cas_version=cas_version+1,state='ACTIVE',acquired_at=?,heartbeat_at=?,expires_at=? WHERE task_id=? AND cas_version=?")){
            s.setString(1,instanceId);s.setString(2,runId.value());s.setString(3,leaseToken);s.setString(4,text(takeoverAt));
            s.setString(5,text(takeoverAt));s.setString(6,text(expiresAt));s.setString(7,taskId.value());s.setLong(8,leaseVersion);
            if(s.executeUpdate()!=1)return OwnershipResult.VERSION_CONFLICT;
        }
        try(PreparedStatement s=connection.prepareStatement("UPDATE task_context_pointers SET owner_run_id=?,pointer_version=pointer_version+1 WHERE task_id=? AND active_generation_id=? AND owner_run_id=? AND pointer_version=?")){
            s.setString(1,runId.value());s.setString(2,taskId.value());s.setString(3,expectedGeneration.value());
            s.setString(4,expectedPointerOwner.value());s.setLong(5,pointerVersion);
            if(s.executeUpdate()!=1)throw new SQLException("Context pointer takeover CAS failed");
        }
        return OwnershipResult.SUCCESS;
    }

    public OwnershipResult validateFencing(CanonicalId taskId,String leaseToken,long fencingToken,Instant at)throws SQLException{
        require(taskId,EntityFamily.TASK);Optional<OwnershipLease> lease=readOwnership(taskId);if(lease.isEmpty())return OwnershipResult.NOT_FOUND;if(!"ACTIVE".equals(lease.get().state()))return OwnershipResult.INVALID_STATE;if(!at.isBefore(lease.get().expiresAt()))return OwnershipResult.EXPIRED;if(!lease.get().leaseToken().equals(leaseToken)||lease.get().fencingToken()!=fencingToken)return OwnershipResult.FENCING_REJECTED;return OwnershipResult.SUCCESS;
    }

    public OwnershipResult validateExecutionAuthority(CanonicalId taskId,String instanceId,
            CanonicalId runId,String leaseToken,long fencingToken,long casVersion,Instant at)
            throws SQLException{
        require(taskId,EntityFamily.TASK);require(runId,EntityFamily.RUN);
        if(blank(instanceId)||blank(leaseToken)||at==null||fencingToken<1||casVersion<1)
            return OwnershipResult.INVALID_STATE;
        Optional<OwnershipLease> current=readOwnership(taskId);
        if(current.isEmpty())return OwnershipResult.NOT_FOUND;
        OwnershipLease lease=current.get();
        if(!"ACTIVE".equals(lease.state()))return OwnershipResult.INVALID_STATE;
        if(!at.isBefore(lease.expiresAt()))return OwnershipResult.EXPIRED;
        if(!lease.ownerInstanceId().equals(instanceId)||!lease.ownerRunId().equals(runId))
            return OwnershipResult.OWNERSHIP_CONFLICT;
        if(!lease.leaseToken().equals(leaseToken)||lease.fencingToken()!=fencingToken)
            return OwnershipResult.FENCING_REJECTED;
        if(lease.casVersion()!=casVersion)return OwnershipResult.VERSION_CONFLICT;
        return OwnershipResult.SUCCESS;
    }

    public ResumeRunAdmissionResult admitResumeRun(DurableRun resume,String instanceId,
            String leaseToken,Instant acquiredAt,Instant expiresAt,Duration grace)throws SQLException{
        return admitResumeRun(resume,instanceId,leaseToken,acquiredAt,expiresAt,grace,false);
    }

    public ResumeRunAdmissionResult admitResumeRunForRecovery(DurableRun resume,String instanceId,
            String leaseToken,Instant acquiredAt,Instant expiresAt,Duration grace)throws SQLException{
        return admitResumeRun(resume,instanceId,leaseToken,acquiredAt,expiresAt,grace,true);
    }

    private ResumeRunAdmissionResult admitResumeRun(DurableRun resume,String instanceId,
            String leaseToken,Instant acquiredAt,Instant expiresAt,Duration grace,
            boolean enforcePlanApplicability)throws SQLException{
        Objects.requireNonNull(resume);require(resume.id(),EntityFamily.RUN);
        require(resume.taskId(),EntityFamily.TASK);require(resume.threadId(),EntityFamily.THREAD);
        if(!"RESUME".equals(resume.kind())||resume.resumedFromRunId()==null
                ||blank(instanceId)||blank(leaseToken)||grace==null||grace.isNegative()
                ||!expiresAt.isAfter(acquiredAt)){
            return new ResumeRunAdmissionResult.Rejected(
                    ResumeRunAdmissionResult.Reason.INVALID_STATE);
        }
        try{return tx(()->{
            acquireWriteLock();
            if(findRun(resume.id()).isPresent())return new ResumeRunAdmissionResult.Rejected(
                    ResumeRunAdmissionResult.Reason.ALREADY_EXISTS);
            Optional<DurableTask> task=findTask(resume.taskId());
            Optional<DurableRun> previous=findRun(resume.resumedFromRunId());
            Optional<TaskContextPointer> pointer=readContextPointer(resume.taskId());
            if(task.isEmpty()||previous.isEmpty()||pointer.isEmpty())
                return new ResumeRunAdmissionResult.Rejected(
                        ResumeRunAdmissionResult.Reason.NOT_FOUND);
            if(enforcePlanApplicability&&task.get().isTerminal())
                return new ResumeRunAdmissionResult.Rejected(
                        ResumeRunAdmissionResult.Reason.TASK_TERMINAL);
            if(!task.get().threadId().equals(resume.threadId())
                    ||!previous.get().taskId().equals(resume.taskId())
                    ||!pointer.get().ownerRunId().equals(previous.get().id())
                    ||!Objects.equals(task.get().manifestId(),resume.manifestId())
                    ||!Objects.equals(task.get().manifestHash(),resume.manifestHash()))
                return new ResumeRunAdmissionResult.Rejected(
                        ResumeRunAdmissionResult.Reason.INTEGRITY_VIOLATION);
            if(hasStartedOrUnknownOccurrence(resume.taskId()))
                return new ResumeRunAdmissionResult.Rejected(
                        ResumeRunAdmissionResult.Reason.OCCURRENCE_UNRESOLVED);
            if(!SteeringStore.recoveryIntegrityValid(connection,resume.taskId()))
                throw new ResumeAdmissionRollback(
                        ResumeRunAdmissionResult.Reason.STEERING_AUTHORITY_INTEGRITY_FAILURE);

            PlanApplicability planApplicability=enforcePlanApplicability
                    ?readPlanApplicability(resume.taskId()):PlanApplicability.PLAN_DISABLED;
            if(planApplicability==PlanApplicability.LEGACY_PLAN_ONLY)
                throw new ResumeAdmissionRollback(
                        ResumeRunAdmissionResult.Reason.LEGACY_PLAN_CONTINUATION_UNAVAILABLE);
            ApprovedPlan approvedPlan=null;
            if(planApplicability==PlanApplicability.PLAN_REQUIRED){
                PlanAuthorityStore.Read planRead=new PlanAuthorityStore(connection)
                        .readActive(resume.taskId());
                if(planRead.status()!=PlanAuthorityStore.Status.SUCCESS)
                    throw new ResumeAdmissionRollback(mapPlanAdmission(planRead.status()));
                approvedPlan=planRead.plan();
                PlanExecutionStore.Status executionState=PlanExecutionStore.inspectRecoveryState(
                        connection,resume.taskId(),approvedPlan.revision().planRevisionId());
                if(executionState!=PlanExecutionStore.Status.SUCCESS)
                    throw new ResumeAdmissionRollback(mapPlanExecutionAdmission(executionState));
                PlanCompletionStore.RecoveryRead completion = new PlanCompletionStore(connection)
                        .readRecovery(resume.taskId(), approvedPlan.revision().planRevisionId());
                if (completion.status() == PlanCompletionStore.Status.EVIDENCE_INTEGRITY_FAILURE)
                    throw new ResumeAdmissionRollback(
                            ResumeRunAdmissionResult.Reason.PLAN_EVIDENCE_INTEGRITY_FAILURE);
                if (completion.status() == PlanCompletionStore.Status.COMPLETION_INTEGRITY_FAILURE)
                    throw new ResumeAdmissionRollback(
                            ResumeRunAdmissionResult.Reason.COMPLETION_AUTHORITY_INTEGRITY_FAILURE);
                if (completion.status() != PlanCompletionStore.Status.SUCCESS)
                    throw new ResumeAdmissionRollback(
                            ResumeRunAdmissionResult.Reason.COMPLETION_RULES_UNAVAILABLE);
            }

            Optional<OwnershipLease> current=readOwnership(resume.taskId());
            if(current.isPresent()&&acquiredAt.isBefore(current.get().expiresAt().plus(grace)))
                return new ResumeRunAdmissionResult.Rejected(
                        ResumeRunAdmissionResult.Reason.OWNERSHIP_CONFLICT);

            try(PreparedStatement s=connection.prepareStatement("""
                    UPDATE coding_runs SET phase='FAILED',
                        last_error='EXECUTION_AUTHORITY_SUPERSEDED',
                        updated_at=datetime('now'),completed_at=datetime('now')
                    WHERE task_id=? AND phase IN ('PLANNING','EXECUTING','VERIFYING','REPAIRING')
                    """)){
                s.setString(1,resume.taskId().value());s.executeUpdate();
            }

            try(PreparedStatement s=connection.prepareStatement(
                    "INSERT INTO durable_runs(run_id,task_id,thread_id,run_kind,"
                            +"resumed_from_run_id,instruction_manifest_id,"
                            +"instruction_manifest_hash,created_at,terminal_status)"
                            +" VALUES(?,?,?,?,?,?,?,?,NULL)")){
                s.setString(1,resume.id().value());s.setString(2,resume.taskId().value());
                s.setString(3,resume.threadId().value());s.setString(4,resume.kind());
                s.setString(5,resume.resumedFromRunId().value());s.setString(6,resume.manifestId());
                s.setString(7,resume.manifestHash());s.setString(8,text(resume.createdAt()));
                s.executeUpdate();
            }

            if(current.isEmpty()){
                try(PreparedStatement s=connection.prepareStatement(
                        "INSERT INTO task_ownership_leases(task_id,owner_instance_id,"
                                +"owner_run_id,lease_token,fencing_token,cas_version,state,"
                                +"acquired_at,heartbeat_at,expires_at)"
                                +" VALUES(?,?,?,?,1,1,'ACTIVE',?,?,?)")){
                    s.setString(1,resume.taskId().value());s.setString(2,instanceId);
                    s.setString(3,resume.id().value());s.setString(4,leaseToken);
                    s.setString(5,text(acquiredAt));s.setString(6,text(acquiredAt));
                    s.setString(7,text(expiresAt));s.executeUpdate();
                }
            }else{
                try(PreparedStatement s=connection.prepareStatement(
                        "UPDATE task_ownership_leases SET owner_instance_id=?,owner_run_id=?,"
                                +"lease_token=?,fencing_token=fencing_token+1,"
                                +"cas_version=cas_version+1,state='ACTIVE',acquired_at=?,"
                                +"heartbeat_at=?,expires_at=? WHERE task_id=? AND cas_version=?")){
                    s.setString(1,instanceId);s.setString(2,resume.id().value());
                    s.setString(3,leaseToken);s.setString(4,text(acquiredAt));
                    s.setString(5,text(acquiredAt));s.setString(6,text(expiresAt));
                    s.setString(7,resume.taskId().value());
                    s.setLong(8,current.get().casVersion());
                    if(s.executeUpdate()!=1)throw new ResumeAdmissionRollback(
                            ResumeRunAdmissionResult.Reason.VERSION_CONFLICT);
                }
            }

            try(PreparedStatement s=connection.prepareStatement(
                    "UPDATE task_context_pointers SET owner_run_id=?,"
                            +"pointer_version=pointer_version+1 WHERE task_id=?"
                            +" AND active_generation_id=? AND owner_run_id=?"
                            +" AND pointer_version=?")){
                s.setString(1,resume.id().value());s.setString(2,resume.taskId().value());
                s.setString(3,pointer.get().generationId().value());
                s.setString(4,pointer.get().ownerRunId().value());
                s.setLong(5,pointer.get().version());
                if(s.executeUpdate()!=1)throw new ResumeAdmissionRollback(
                        ResumeRunAdmissionResult.Reason.VERSION_CONFLICT);
            }
            if(approvedPlan!=null){
                try(PreparedStatement s=connection.prepareStatement("""
                        INSERT INTO plan_run_bindings(binding_id,task_id,run_id,plan_revision_id,
                            approval_id,approval_decision,plan_hash,bound_at)
                        VALUES(?,?,?,?,?,'APPROVED',?,?)
                        """)){
                    s.setString(1,"bind-"+java.util.UUID.randomUUID());
                    s.setString(2,resume.taskId().value());s.setString(3,resume.id().value());
                    s.setString(4,approvedPlan.revision().planRevisionId().value());
                    s.setString(5,approvedPlan.authority().approvalId());
                    s.setString(6,approvedPlan.revision().canonicalHash());
                    s.setString(7,text(acquiredAt));s.executeUpdate();
                }
            }
            OwnershipLease ownership=readOwnership(resume.taskId()).orElseThrow();
            TaskContextPointer updated=readContextPointer(resume.taskId()).orElseThrow();
            return new ResumeRunAdmissionResult.Success(resume,ownership,updated,
                    planApplicability,approvedPlan);
        });}catch(ResumeAdmissionRollback rollback){
            return new ResumeRunAdmissionResult.Rejected(rollback.reason);
        }
    }

    public PlanApplicability readPlanApplicability(CanonicalId taskId)throws SQLException{
        try(PreparedStatement s=connection.prepareStatement(
                "SELECT applicability FROM task_plan_applicability WHERE task_id=?")){
            s.setString(1,taskId.value());try(ResultSet row=s.executeQuery()){
                if(row.next())return PlanApplicability.valueOf(row.getString(1));
            }
        }
        try(PreparedStatement s=connection.prepareStatement(
                "SELECT 1 FROM task_plans WHERE task_id=? LIMIT 1")){
            s.setString(1,taskId.value());try(ResultSet row=s.executeQuery()){
                return row.next()?PlanApplicability.LEGACY_PLAN_ONLY
                        :PlanApplicability.PLAN_DISABLED;
            }
        }
    }

    private static ResumeRunAdmissionResult.Reason mapPlanAdmission(
            PlanAuthorityStore.Status status){
        return switch(status){
            case NO_APPROVED_PLAN -> ResumeRunAdmissionResult.Reason.NO_APPROVED_PLAN;
            case LEGACY_PLAN_CONTINUATION_UNAVAILABLE ->
                    ResumeRunAdmissionResult.Reason.LEGACY_PLAN_CONTINUATION_UNAVAILABLE;
            case PLAN_REVISION_INTEGRITY_FAILURE,INTEGRITY_VIOLATION ->
                    ResumeRunAdmissionResult.Reason.PLAN_REVISION_INTEGRITY_FAILURE;
            default -> ResumeRunAdmissionResult.Reason.PLAN_AUTHORITY_UNAVAILABLE;
        };
    }

    private static ResumeRunAdmissionResult.Reason mapPlanExecutionAdmission(
            PlanExecutionStore.Status status){
        return switch(status){
            case PLAN_STEP_STATE_UNAVAILABLE ->
                    ResumeRunAdmissionResult.Reason.PLAN_STEP_STATE_UNAVAILABLE;
            case PLAN_STEP_STATE_INTEGRITY_FAILURE ->
                    ResumeRunAdmissionResult.Reason.PLAN_STEP_STATE_INTEGRITY_FAILURE;
            case PLAN_ATTEMPT_UNRESOLVED ->
                    ResumeRunAdmissionResult.Reason.PLAN_ATTEMPT_UNRESOLVED;
            default -> ResumeRunAdmissionResult.Reason.PLAN_AUTHORITY_UNAVAILABLE;
        };
    }

    public Optional<OwnershipLease> readOwnership(CanonicalId taskId)throws SQLException{
        require(taskId,EntityFamily.TASK);try(PreparedStatement s=connection.prepareStatement("SELECT owner_instance_id,owner_run_id,lease_token,fencing_token,cas_version,state,acquired_at,heartbeat_at,expires_at FROM task_ownership_leases WHERE task_id=?")){s.setString(1,taskId.value());try(ResultSet r=s.executeQuery()){return r.next()?Optional.of(new OwnershipLease(taskId,r.getString(1),CanonicalId.parse(r.getString(2)),r.getString(3),r.getLong(4),r.getLong(5),r.getString(6),instant(r.getString(7)),instant(r.getString(8)),instant(r.getString(9)))):Optional.empty();}}
    }

    private void acquireWriteLock()throws SQLException{
        try(PreparedStatement lock=connection.prepareStatement("""
                UPDATE schema_migrations SET applied_at=applied_at
                WHERE version=(SELECT MAX(version) FROM schema_migrations)
                """)){
            if(lock.executeUpdate()!=1)throw new SQLException(
                    "Schema version row unavailable for SQLite write lock");
        }
    }

    private void requireActiveCallerTransaction()throws SQLException{
        if(connection.getAutoCommit())throw new SQLException("Active caller transaction is required");
    }

    private <T>T tx(SqlSupplier<T> work)throws SQLException{if(!connection.getAutoCommit())throw new SQLException("Nested R1A transaction is not supported");connection.setAutoCommit(false);try{T result=work.get();connection.commit();return result;}catch(SQLException|RuntimeException exception){connection.rollback();throw exception;}finally{connection.setAutoCommit(true);}}
    private <T>T rootWriteTx(SqlSupplier<T> work)throws SQLException{
        if(!connection.getAutoCommit())throw new SQLException(
                "Nested Root admission transaction is not supported");
        connection.setAutoCommit(false);
        try{
            acquireWriteLock();
            T result=work.get();connection.commit();return result;
        }catch(SQLException|RuntimeException exception){connection.rollback();throw exception;}
        finally{connection.setAutoCommit(true);}
    }
    private static void require(CanonicalId id,EntityFamily family){if(id==null||id.family()!=family)throw new IllegalArgumentException("Expected "+family+" canonical ID");}
    private static boolean blank(String value){return value==null||value.isBlank();}
    private static CanonicalId canonical(String value){return value==null?null:CanonicalId.parse(value);}
    private static String text(Instant value){return Objects.requireNonNull(value).toString();}
    private static Instant instant(String value){return Instant.parse(value);}
    private static Instant nullableInstant(String value){return value==null?null:instant(value);}
    private static void nullable(PreparedStatement s,int index,Object value)throws SQLException{if(value==null)s.setNull(index,Types.VARCHAR);else if(value instanceof CanonicalId id)s.setString(index,id.value());else if(value instanceof Instant at)s.setString(index,text(at));else if(value instanceof Integer number)s.setInt(index,number);else s.setString(index,value.toString());}
    @FunctionalInterface public interface ArtifactPayloadResolver{byte[] resolve(String protectedReference)throws SQLException;}
    @FunctionalInterface private interface SqlSupplier<T>{T get()throws SQLException;}
    private static final class ResumeAdmissionRollback extends RuntimeException{
        private final ResumeRunAdmissionResult.Reason reason;
        private ResumeAdmissionRollback(ResumeRunAdmissionResult.Reason reason){
            this.reason=reason;
        }
    }
    private static final class RootAdmissionRollback extends RuntimeException{
        private final RootAdmissionTransaction.Reason reason;
        private RootAdmissionRollback(RootAdmissionTransaction.Reason reason){this.reason=reason;}
    }
}
