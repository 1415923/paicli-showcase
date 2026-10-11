package com.paicli.interactive.view;

import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.interactive.runtime.InteractiveRuntimeView;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.session.SavedSessionService;
import com.paicli.state.*;
import com.paicli.state.FollowUpModels.*;
import com.paicli.state.PlanCompletionModels.Evidence;
import com.paicli.state.PlanExecutionModels.*;
import com.paicli.state.R1AuthorityPeek.*;
import com.paicli.state.R1aDurableModels.*;
import com.paicli.state.SteeringModels.Queue;
import com.paicli.state.StructuredPlanModels.*;

import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.*;

/** One immutable R2 projection from one SQLite read transaction. */
public final class InteractiveSessionViewModelReader {
    private static final String RULE_VERSION = "R2_STATUS_PRECEDENCE_V1";
    private static final Duration INCOMPLETE_STALE_AFTER = Duration.ofMinutes(5);
    private static final com.paicli.runtime.PersistenceSanitizer SANITIZER =
            new com.paicli.runtime.PersistenceSanitizer();
    private final Connection connection;
    private final Path workspace;
    private final R1aDurableStore durable;
    private final R1AuthorityPeek peeks;
    private final ProviderCallTelemetryReader telemetry;

    public InteractiveSessionViewModelReader(Connection connection, Path workspace,
            R1aDurableStore durable, R1AuthorityPeek peeks) {
        this.connection=Objects.requireNonNull(connection);
        this.workspace=Objects.requireNonNull(workspace).toAbsolutePath().normalize();
        this.durable=Objects.requireNonNull(durable);
        this.peeks=Objects.requireNonNull(peeks);
        this.telemetry=new ProviderCallTelemetryReader(connection);
    }

    public InteractiveSessionViewModel read(InteractiveSession.Snapshot session,
            InteractiveRuntimeView.Snapshot preview) {
        Objects.requireNonNull(session);
        synchronized(connection) {
            try {
                if(!connection.getAutoCommit())
                    return unavailable(session,"VIEW_SNAPSHOT_NESTED_TRANSACTION");
                try(Statement statement=connection.createStatement()){
                    statement.execute("PRAGMA query_only=ON");
                }
                connection.setAutoCommit(false);
                try {
                    InteractiveSessionViewModel result=readCurrentInSnapshot(session,preview);
                    connection.commit();
                    return result;
                } catch(SQLException|RuntimeException error) {
                    connection.rollback();
                    return unavailable(session,"VIEW_SNAPSHOT_READ_FAILED");
                } finally {
                    connection.setAutoCommit(true);
                    try(Statement statement=connection.createStatement()){
                        statement.execute("PRAGMA query_only=OFF");
                    }
                }
            } catch(SQLException error) {
                return unavailable(session,"VIEW_SNAPSHOT_UNAVAILABLE");
            }
        }
    }

    public InteractiveSessionViewModel readCurrentInSnapshot(
            InteractiveSession.Snapshot session, InteractiveRuntimeView.Snapshot preview)
            throws SQLException {
        requireExistingSnapshot();
        String workspaceIdentity=SavedSessionService.workspaceIdentity(workspace);
        WorkThread thread=selectThread(session,workspaceIdentity);
        return readThreadHeadInSnapshot(session,preview,workspaceIdentity,thread,
                TaskProjectionProfile.CURRENT_INTERACTIVE);
    }

    public InteractiveSessionViewModel readThreadInSnapshot(
            InteractiveSession.Snapshot session, CanonicalId threadId) throws SQLException {
        requireExistingSnapshot();
        String workspaceIdentity=SavedSessionService.workspaceIdentity(workspace);
        WorkThread thread=durable.findWorkThread(threadId).orElse(null);
        if(thread!=null&&!workspaceIdentity.equals(thread.workspaceIdentity()))thread=null;
        return readThreadHeadInSnapshot(session,null,workspaceIdentity,thread,
                TaskProjectionProfile.TASK_DETAIL);
    }

    public InteractiveSessionViewModel readTaskInSnapshot(
            InteractiveSession.Snapshot session, CanonicalId taskId) throws SQLException {
        requireExistingSnapshot();
        SelectedTaskIdentity identity=resolveTaskIdentityInSnapshot(session,taskId,
                SelectedTaskIdentity.SelectorKind.EXPLICIT,"task-detail");
        return readTaskProjectionInSnapshot(session,
                new TaskProjectionKey(identity,TaskProjectionProfile.TASK_DETAIL),null);
    }

    private InteractiveSessionViewModel readThreadHeadInSnapshot(
            InteractiveSession.Snapshot session, InteractiveRuntimeView.Snapshot preview,
            String workspaceIdentity, WorkThread thread, TaskProjectionProfile profile)
            throws SQLException {
        ThreadHead head=thread==null?null:durable.readThreadHead(thread.id()).orElse(null);
        if(head==null)return assemble(session,preview,workspaceIdentity,thread,null,null,null,
                null,null,null);
        SelectedTaskIdentity identity=resolveTaskIdentityInSnapshot(session,head.headTaskId(),
                profile==TaskProjectionProfile.SESSION_ROW
                        ?SelectedTaskIdentity.SelectorKind.THREAD_ROW
                        :SelectedTaskIdentity.SelectorKind.CURRENT_INTERACTIVE,
                profile.name().toLowerCase(Locale.ROOT));
        return readTaskProjectionInSnapshot(session,new TaskProjectionKey(identity,profile),preview);
    }

    public SelectedTaskIdentity resolveCurrentReportIdentityInSnapshot(
            InteractiveSession.Snapshot session) throws SQLException {
        requireExistingSnapshot();
        CanonicalId selected=session.binding()==null?null:session.binding().headTaskId();
        if(selected==null)selected=parse(session.currentTaskId());
        if(selected==null)return null;
        return resolveTaskIdentityInSnapshot(session,selected,
                SelectedTaskIdentity.SelectorKind.CURRENT,"current");
    }

    public SelectedTaskIdentity resolveLatestReportIdentityInSnapshot(
            InteractiveSession.Snapshot session) throws SQLException {
        requireExistingSnapshot();
        WorkThread thread=selectThread(session,SavedSessionService.workspaceIdentity(workspace));
        ThreadHead head=thread==null?null:durable.readThreadHead(thread.id()).orElse(null);
        return head==null?null:resolveTaskIdentityInSnapshot(session,head.headTaskId(),
                SelectedTaskIdentity.SelectorKind.LATEST,"latest");
    }

    public SelectedTaskIdentity resolveTaskIdentityInSnapshot(
            InteractiveSession.Snapshot session,CanonicalId taskId,
            SelectedTaskIdentity.SelectorKind selectorKind,String selectorSource)
            throws SQLException {
        requireExistingSnapshot();
        if(taskId==null||taskId.family()!=EntityFamily.TASK)
            throw new ProjectionIntegrityException("TASK_SELECTOR_IDENTITY_INVALID");
        String workspaceIdentity=SavedSessionService.workspaceIdentity(workspace);
        DurableTask task=durable.findTask(taskId).orElseThrow(()->
                new ProjectionIntegrityException("SELECTED_TASK_NOT_FOUND"));
        WorkThread thread=durable.findWorkThread(task.threadId()).orElseThrow(()->
                new ProjectionIntegrityException("SELECTED_THREAD_NOT_FOUND"));
        if(!workspaceIdentity.equals(thread.workspaceIdentity()))
            throw new ProjectionIntegrityException("SELECTED_TASK_CROSS_WORKSPACE");
        ThreadHead head=durable.readThreadHead(thread.id()).orElse(null);
        TaskContextPointer pointer=durable.readContextPointer(task.id()).orElse(null);
        DurableRun run;
        TaskContextGeneration generation;
        SelectedTaskIdentity.RunSelectionRule runRule;
        if(pointer!=null){
            run=durable.findRun(pointer.ownerRunId()).orElseThrow(()->
                    new ProjectionIntegrityException("SELECTED_POINTER_RUN_MISSING"));
            generation=durable.findGeneration(pointer.generationId()).orElseThrow(()->
                    new ProjectionIntegrityException("SELECTED_POINTER_GENERATION_MISSING"));
            if(!run.taskId().equals(task.id())||!run.threadId().equals(thread.id())
                    ||!generation.taskId().equals(task.id()))
                throw new ProjectionIntegrityException("SELECTED_POINTER_IDENTITY_MISMATCH");
            runRule=SelectedTaskIdentity.RunSelectionRule.POINTER_OWNER;
        }else{
            run=latestRun(task);
            generation=null;
            runRule=run==null?SelectedTaskIdentity.RunSelectionRule.NONE:
                    SelectedTaskIdentity.RunSelectionRule.LATEST_TASK_RUN;
        }
        boolean boundThread=session.binding()!=null&&session.binding().threadId()!=null
                &&session.binding().threadId().equals(thread.id());
        boolean isHead=head!=null&&head.headTaskId().equals(task.id());
        SelectedTaskIdentity.Relation relation;
        if(selectorKind==SelectedTaskIdentity.SelectorKind.CURRENT){
            boolean hasBinding=session.binding()!=null&&session.binding().threadId()!=null;
            relation=hasBinding&&!boundThread
                    ?SelectedTaskIdentity.Relation.CROSS_THREAD_INVALID:
                    isHead?SelectedTaskIdentity.Relation.CURRENT_HEAD:
                            SelectedTaskIdentity.Relation.STALE_SESSION_BINDING;
        }else if(selectorKind==SelectedTaskIdentity.SelectorKind.EXPLICIT){
            relation=boundThread&&isHead?SelectedTaskIdentity.Relation.CURRENT_HEAD:
                    SelectedTaskIdentity.Relation.HISTORICAL;
        }else{
            if(!isHead)throw new ProjectionIntegrityException("SELECTED_HEAD_IDENTITY_MISMATCH");
            relation=SelectedTaskIdentity.Relation.CURRENT_HEAD;
        }
        return new SelectedTaskIdentity(workspaceIdentity,session.sessionId(),thread.id(),task.id(),
                runRule,run==null?null:run.id(),generation==null?null:generation.id(),selectorKind,
                selectorSource,UUID.randomUUID().toString(),Instant.now(),
                head==null?null:head.headTaskId(),head==null?0:head.version(),
                pointer==null?-1:pointer.version(),relation);
    }

    public InteractiveSessionViewModel readTaskProjectionInSnapshot(
            InteractiveSession.Snapshot session,TaskProjectionKey key,
            InteractiveRuntimeView.Snapshot preview)
            throws SQLException {
        requireExistingSnapshot();
        Objects.requireNonNull(key);
        SelectedTaskIdentity identity=key.identity();
        if(identity.sessionId()!=null&&!identity.sessionId().equals(session.sessionId()))
            throw new ProjectionIntegrityException("SELECTED_SESSION_IDENTITY_MISMATCH");
        DurableTask task=durable.findTask(identity.taskId()).orElseThrow(()->
                new ProjectionIntegrityException("SELECTED_TASK_NOT_FOUND"));
        WorkThread thread=durable.findWorkThread(identity.threadId()).orElseThrow(()->
                new ProjectionIntegrityException("SELECTED_THREAD_NOT_FOUND"));
        if(!task.threadId().equals(thread.id())
                ||!identity.workspaceIdentity().equals(thread.workspaceIdentity()))
            throw new ProjectionIntegrityException("SELECTED_TASK_IDENTITY_MISMATCH");
        ThreadHead head=durable.readThreadHead(thread.id()).orElse(null);
        if((head==null)!=(identity.observedHeadTaskId()==null)
                ||head!=null&&(!head.headTaskId().equals(identity.observedHeadTaskId())
                        ||head.version()!=identity.observedHeadVersion()))
            throw new ProjectionIntegrityException("SELECTOR_SNAPSHOT_IDENTITY_CHANGED");
        TaskContextPointer pointer=durable.readContextPointer(task.id()).orElse(null);
        if((pointer==null?-1:pointer.version())!=identity.selectedPointerVersion())
            throw new ProjectionIntegrityException("SELECTED_POINTER_VERSION_CHANGED");
        if(pointer!=null&&(identity.runSelectionRule()!=
                    SelectedTaskIdentity.RunSelectionRule.POINTER_OWNER
                ||!pointer.ownerRunId().equals(identity.selectedRunId())
                ||!pointer.generationId().equals(identity.selectedGenerationId())))
            throw new ProjectionIntegrityException("SELECTED_POINTER_RULE_MISMATCH");
        if(pointer==null){
            if(identity.selectedGenerationId()!=null
                    ||identity.selectedRunId()==null
                            &&identity.runSelectionRule()!=SelectedTaskIdentity.RunSelectionRule.NONE
                    ||identity.selectedRunId()!=null
                            &&identity.runSelectionRule()!=
                                    SelectedTaskIdentity.RunSelectionRule.LATEST_TASK_RUN)
                throw new ProjectionIntegrityException("SELECTED_RUN_RULE_MISMATCH");
        }
        DurableRun run=identity.selectedRunId()==null?null:
                durable.findRun(identity.selectedRunId()).orElseThrow(()->
                        new ProjectionIntegrityException("SELECTED_RUN_NOT_FOUND"));
        TaskContextGeneration generation=identity.selectedGenerationId()==null?null:
                durable.findGeneration(identity.selectedGenerationId()).orElseThrow(()->
                        new ProjectionIntegrityException("SELECTED_GENERATION_NOT_FOUND"));
        if(run!=null&&(!run.taskId().equals(task.id())||!run.threadId().equals(thread.id()))
                ||generation!=null&&(!generation.taskId().equals(task.id())
                        ||identity.selectedRunId()==null
                        ||!Objects.equals(generation.createdByRunId(),identity.selectedRunId())))
            throw new ProjectionIntegrityException("SELECTED_GRAPH_IDENTITY_MISMATCH");
        return assemble(session,preview,identity.workspaceIdentity(),thread,head,task,run,
                pointer,generation,key);
    }

    /** Builds the complete /report input without opening another connection or selecting identity again. */
    public TaskReportView readTaskReportProjectionInSnapshot(
            InteractiveSession.Snapshot session,TaskProjectionKey key)throws SQLException{
        requireExistingSnapshot();
        Objects.requireNonNull(key);
        if(key.profile()!=TaskProjectionProfile.HISTORICAL_REPORT)
            throw new ProjectionIntegrityException("REPORT_PROJECTION_PROFILE_INVALID");
        InteractiveSessionViewModel model=readTaskProjectionInSnapshot(session,key,null);
        SelectedTaskIdentity identity=key.identity();
        String taskId=identity.taskId().value();
        String runId=identity.selectedRunId()==null?null:identity.selectedRunId().value();
        TaskReportView.ScopedFact<TaskReportView.ManifestView> manifest;
        if(model.task().isPresent()&&model.task().value().manifestId()!=null
                &&model.task().value().manifestHash()!=null){
            manifest=TaskReportView.ScopedFact.present(TaskReportView.Scope.TASK_SCOPED,
                    new TaskReportView.ManifestView(model.task().value().manifestId(),
                            model.task().value().manifestHash()));
        }else manifest=TaskReportView.ScopedFact.absent(TaskReportView.Scope.TASK_SCOPED);
        TaskReportView.ScopedFact<ProviderView> provider=runId==null
                ?TaskReportView.ScopedFact.notApplicable()
                :model.provider().availabilityState()==ProviderView.AvailabilityState.AVAILABLE
                        ?TaskReportView.ScopedFact.present(TaskReportView.Scope.RUN_SCOPED,
                                model.provider())
                        :TaskReportView.ScopedFact.unavailable("PROVIDER_TELEMETRY_READ_FAILED");
        return new TaskReportView(key,
                TaskReportView.ScopedFact.present(TaskReportView.Scope.TASK_SCOPED,
                        model.workspace()),
                scoped(model.thread(),TaskReportView.Scope.TASK_SCOPED),
                scoped(model.task(),TaskReportView.Scope.TASK_SCOPED),
                runId==null?TaskReportView.ScopedFact.notApplicable():
                        scoped(model.run(),TaskReportView.Scope.RUN_SCOPED),
                identity.selectedGenerationId()==null?TaskReportView.ScopedFact.notApplicable():
                        scoped(model.generation(),TaskReportView.Scope.RUN_SCOPED),
                manifest,scoped(model.plan(),TaskReportView.Scope.TASK_SCOPED),
                scoped(model.currentStep(),TaskReportView.Scope.TASK_SCOPED),
                scoped(model.currentAttempt(),TaskReportView.Scope.TASK_SCOPED),
                scoped(model.evidence(),TaskReportView.Scope.TASK_SCOPED),
                scoped(model.completion(),TaskReportView.Scope.TASK_SCOPED),
                safeReportRead(()->legacyTask(taskId),"LEGACY_TASK_UNAVAILABLE"),
                runId==null?TaskReportView.ScopedFact.notApplicable():
                        safeReportRead(()->legacyRun(taskId,runId),"LEGACY_RUN_UNAVAILABLE"),
                runId==null?TaskReportView.ScopedFact.notApplicable():
                        safeReportRead(()->legacyPlan(taskId,runId),"LEGACY_PLAN_UNAVAILABLE"),
                runId==null?TaskReportView.ScopedFact.notApplicable():
                        safeReportRead(()->verification(taskId,runId),"VERIFICATION_UNAVAILABLE"),
                runId==null?TaskReportView.ScopedFact.notApplicable():
                        safeReportRead(()->repair(taskId,runId),"REPAIR_UNAVAILABLE"),
                runId==null?TaskReportView.ScopedFact.notApplicable():
                        safeReportRead(()->codeFacts(taskId,runId),"CODE_FACTS_UNAVAILABLE"),
                runId==null?TaskReportView.ScopedFact.notApplicable():
                        safeReportRead(()->changeFacts(taskId,runId),"CHANGE_FACTS_UNAVAILABLE"),
                TaskReportView.ScopedFact.notApplicable(),
                scoped(model.recovery(),TaskReportView.Scope.TASK_SCOPED),
                scoped(model.steering(),TaskReportView.Scope.TASK_SCOPED),
                scoped(model.followUp(),TaskReportView.Scope.TASK_SCOPED),
                runId==null?TaskReportView.ScopedFact.notApplicable():
                        scoped(model.context(),TaskReportView.Scope.RUN_SCOPED),provider,
                runId==null?TaskReportView.ScopedFact.notApplicable():
                        safeReportRead(()->providerUsage(session,taskId,runId),
                                "PROVIDER_USAGE_UNAVAILABLE"),
                model.operationalStatus(),model.warnings());
    }

    private TaskReportView.ScopedFact<TaskReportView.LegacyTaskView> legacyTask(String taskId)
            throws SQLException{
        try(PreparedStatement statement=connection.prepareStatement("""
                SELECT provider,model,iterations_completed,max_iterations FROM tasks
                WHERE task_id=?
                """)){
            statement.setString(1,taskId);
            try(ResultSet row=statement.executeQuery()){
                return row.next()?TaskReportView.ScopedFact.present(
                        TaskReportView.Scope.TASK_SCOPED,new TaskReportView.LegacyTaskView(
                                bounded(row.getString(1),64),bounded(row.getString(2),128),
                                row.getInt(3),row.getInt(4))):
                        TaskReportView.ScopedFact.absent(TaskReportView.Scope.TASK_SCOPED);
            }
        }
    }

    private TaskReportView.ScopedFact<TaskReportView.LegacyRunView> legacyRun(
            String taskId,String runId)throws SQLException{
        try(PreparedStatement statement=connection.prepareStatement("""
                SELECT run_id,phase,last_error,last_iteration FROM coding_runs
                WHERE task_id=? AND run_id=?
                """)){
            statement.setString(1,taskId);statement.setString(2,runId);
            try(ResultSet row=statement.executeQuery()){
                return row.next()?TaskReportView.ScopedFact.present(
                        TaskReportView.Scope.RUN_SCOPED,new TaskReportView.LegacyRunView(
                                row.getString(1),row.getString(2),
                                bounded(SANITIZER.sanitize(row.getString(3)),256),row.getInt(4))):
                        TaskReportView.ScopedFact.unavailable("SELECTED_LEGACY_RUN_MISSING");
            }
        }
    }

    private TaskReportView.ScopedFact<TaskReportView.LegacyPlanView> legacyPlan(
            String taskId,String runId)throws SQLException{
        try(PreparedStatement statement=connection.prepareStatement("""
                SELECT plan_id,status,plan_text FROM task_plans
                WHERE task_id=? AND run_id=?
                """)){
            statement.setString(1,taskId);statement.setString(2,runId);
            try(ResultSet row=statement.executeQuery()){
                return row.next()?TaskReportView.ScopedFact.present(
                        TaskReportView.Scope.RUN_SCOPED,new TaskReportView.LegacyPlanView(
                                row.getString(1),row.getString(2),
                                bounded(SANITIZER.sanitize(row.getString(3)),2048))):
                        TaskReportView.ScopedFact.absent(TaskReportView.Scope.RUN_SCOPED);
            }
        }
    }

    private TaskReportView.ScopedFact<TaskReportView.VerificationView> verification(
            String taskId,String runId)throws SQLException{
        try(PreparedStatement statement=connection.prepareStatement("""
                SELECT verification_id,status,completed_at FROM verification_runs
                WHERE task_id=? AND run_id=? ORDER BY started_at DESC,rowid DESC LIMIT 1
                """)){
            statement.setString(1,taskId);statement.setString(2,runId);
            try(ResultSet row=statement.executeQuery()){
                return row.next()?TaskReportView.ScopedFact.present(
                        TaskReportView.Scope.RUN_SCOPED,new TaskReportView.VerificationView(
                                row.getString(1),row.getString(2),row.getString(3))):
                        TaskReportView.ScopedFact.absent(TaskReportView.Scope.RUN_SCOPED);
            }
        }
    }

    private TaskReportView.ScopedFact<TaskReportView.RepairView> repair(
            String taskId,String runId)throws SQLException{
        try(PreparedStatement statement=connection.prepareStatement("""
                SELECT count(*),sum(status='COMPLETED'),sum(status='FAILED'),sum(status='RUNNING')
                FROM repair_attempts WHERE task_id=? AND run_id=?
                """)){
            statement.setString(1,taskId);statement.setString(2,runId);
            try(ResultSet row=statement.executeQuery()){
                if(!row.next())return TaskReportView.ScopedFact.unavailable("REPAIR_READ_FAILED");
                return TaskReportView.ScopedFact.present(TaskReportView.Scope.RUN_SCOPED,
                        new TaskReportView.RepairView(row.getInt(1),row.getInt(2),
                                row.getInt(3),row.getInt(4)));
            }
        }
    }

    private TaskReportView.ScopedFact<TaskReportView.CodeFactsView> codeFacts(
            String taskId,String runId)throws SQLException{
        try(PreparedStatement statement=connection.prepareStatement("""
                SELECT code_intelligence_status,indexed_java_files,context_item_count
                FROM interactive_task_facts WHERE task_id=? AND run_id=?
                """)){
            statement.setString(1,taskId);statement.setString(2,runId);
            try(ResultSet row=statement.executeQuery()){
                if(row.next())return TaskReportView.ScopedFact.present(
                        TaskReportView.Scope.RUN_SCOPED,new TaskReportView.CodeFactsView(
                                row.getString(1),Integer.toString(row.getInt(2)),row.getInt(3)));
            }
        }
        try(PreparedStatement statement=connection.prepareStatement("""
                SELECT count(*),sum(status='SUCCEEDED'),sum(status='FAILED'),
                       sum(CASE WHEN query_type='CONTEXT' AND status='SUCCEEDED'
                                THEN result_count ELSE 0 END)
                FROM code_search_audits WHERE task_id=? AND run_id=?
                """)){
            statement.setString(1,taskId);statement.setString(2,runId);
            try(ResultSet row=statement.executeQuery()){
                if(!row.next()||row.getInt(1)==0)
                    return TaskReportView.ScopedFact.absent(TaskReportView.Scope.RUN_SCOPED);
                String status=row.getInt(2)>0?"USED":row.getInt(3)>0?"FAILED":"UNKNOWN_LEGACY";
                return TaskReportView.ScopedFact.present(TaskReportView.Scope.RUN_SCOPED,
                        new TaskReportView.CodeFactsView(status,"unknown",row.getInt(4)));
            }
        }
    }

    private TaskReportView.ScopedFact<TaskReportView.ChangeFactsView> changeFacts(
            String taskId,String runId)throws SQLException{
        int proposals,applied,rejected,cancelled,additions,deletions,snapshots,restored;
        try(PreparedStatement statement=connection.prepareStatement("""
                SELECT count(*),sum(status='APPLIED'),sum(status='REJECTED'),
                       sum(status='CANCELLED'),sum(additions),sum(deletions)
                FROM interactive_edit_proposals WHERE task_id=? AND run_id=?
                """)){
            statement.setString(1,taskId);statement.setString(2,runId);
            try(ResultSet row=statement.executeQuery()){
                if(!row.next())return TaskReportView.ScopedFact.unavailable("PROPOSAL_READ_FAILED");
                proposals=row.getInt(1);applied=row.getInt(2);rejected=row.getInt(3);
                cancelled=row.getInt(4);additions=row.getInt(5);deletions=row.getInt(6);
            }
        }
        try(PreparedStatement statement=connection.prepareStatement("""
                SELECT count(*),sum(status='RESTORED') FROM file_snapshots
                WHERE task_id=? AND owner_run_id=?
                """)){
            statement.setString(1,taskId);statement.setString(2,runId);
            try(ResultSet row=statement.executeQuery()){
                if(!row.next())return TaskReportView.ScopedFact.unavailable("SNAPSHOT_READ_FAILED");
                snapshots=row.getInt(1);restored=row.getInt(2);
            }
        }
        return TaskReportView.ScopedFact.present(TaskReportView.Scope.RUN_SCOPED,
                new TaskReportView.ChangeFactsView(proposals,applied,rejected,cancelled,
                        additions,deletions,snapshots,restored));
    }

    private TaskReportView.ScopedFact<TaskReportView.ProviderUsageView> providerUsage(
            InteractiveSession.Snapshot session,String taskId,String runId)throws SQLException{
        ProviderCallTelemetryReader.UsageAggregate value=telemetry.aggregate(
                ProviderCallTelemetryReader.Scope.taskRun(taskId,runId,
                        session.provider(),session.model()));
        return TaskReportView.ScopedFact.present(TaskReportView.Scope.RUN_SCOPED,
                new TaskReportView.ProviderUsageView(value.callCount(),value.inputTokens(),
                        value.outputTokens(),value.totalTokens(),value.coverage()));
    }

    private <T> TaskReportView.ScopedFact<T> safeReportRead(
            ReportRead<T> read,String diagnosticRef){
        try{return read.read();}
        catch(SQLException|RuntimeException error){
            return TaskReportView.ScopedFact.unavailable(diagnosticRef);
        }
    }

    private static <T> TaskReportView.ScopedFact<T> scoped(
            ViewFact<T> fact,TaskReportView.Scope scope){
        if(fact.isPresent())return TaskReportView.ScopedFact.present(scope,fact.value());
        if(fact.state()==ViewFact.State.NOT_APPLICABLE)
            return TaskReportView.ScopedFact.notApplicable();
        if(fact.state()==ViewFact.State.UNAVAILABLE)
            return TaskReportView.ScopedFact.unavailable(fact.diagnosticRef());
        return new TaskReportView.ScopedFact<>(scope,fact.state(),null,fact.diagnosticRef());
    }

    @FunctionalInterface
    private interface ReportRead<T>{TaskReportView.ScopedFact<T> read()throws SQLException;}

    public SessionRowProjectionResult readSessionRowInSnapshot(
            InteractiveSession.Snapshot session,WorkThread thread,boolean current){
        try{
            requireExistingSnapshot();
            InteractiveSessionViewModel view=readThreadHeadInSnapshot(session,null,
                    SavedSessionService.workspaceIdentity(workspace),thread,
                    TaskProjectionProfile.SESSION_ROW);
            if(!view.durableAuthorityIntegrity().isPresent())
                return new SessionRowProjectionResult(SessionRowProjectionResult.State.UNAVAILABLE,
                        thread.id().value(),thread.updatedAt(),current,view,
                        "SESSION_ROW_AUTHORITY_UNAVAILABLE");
            if(!Boolean.TRUE.equals(view.durableAuthorityIntegrity().value()))
                return new SessionRowProjectionResult(SessionRowProjectionResult.State.INTEGRITY_FAILURE,
                        thread.id().value(),thread.updatedAt(),current,view,
                        "SESSION_ROW_AUTHORITY_INTEGRITY_FAILURE");
            return new SessionRowProjectionResult(SessionRowProjectionResult.State.PRESENT,
                    thread.id().value(),thread.updatedAt(),current,view,null);
        }catch(ProjectionIntegrityException error){
            return new SessionRowProjectionResult(SessionRowProjectionResult.State.INTEGRITY_FAILURE,
                    thread.id().value(),thread.updatedAt(),current,null,"SESSION_ROW_IDENTITY_INVALID");
        }catch(DateTimeException|IllegalArgumentException error){
            return new SessionRowProjectionResult(SessionRowProjectionResult.State.INTEGRITY_FAILURE,
                    thread.id().value(),thread.updatedAt(),current,null,"SESSION_ROW_STORAGE_INVALID");
        }catch(SQLException|RuntimeException error){
            return new SessionRowProjectionResult(SessionRowProjectionResult.State.UNAVAILABLE,
                    thread.id().value(),thread.updatedAt(),current,null,"SESSION_ROW_UNAVAILABLE");
        }
    }

    private void requireExistingSnapshot() throws SQLException {
        if(connection.getAutoCommit())throw new SQLException("read snapshot is not active");
        try(Statement statement=connection.createStatement();
            ResultSet row=statement.executeQuery("PRAGMA query_only")){
            if(!row.next()||row.getInt(1)!=1)throw new SQLException("query-only is not active");
        }
    }

    private InteractiveSessionViewModel assemble(InteractiveSession.Snapshot session,
            InteractiveRuntimeView.Snapshot preview,String workspaceIdentity,
            WorkThread thread,ThreadHead head,DurableTask task,DurableRun run,
            TaskContextPointer pointer,TaskContextGeneration generation,
            TaskProjectionKey key)throws SQLException{
        SelectedTaskIdentity selectedIdentity=key==null?null:key.identity();
        TaskProjectionProfile profile=key==null?TaskProjectionProfile.CURRENT_INTERACTIVE:key.profile();
        boolean historical=selectedIdentity!=null&&selectedIdentity.relation()!=
                        SelectedTaskIdentity.Relation.CURRENT_HEAD;
        Instant at=Instant.now();
        List<String> warnings=new ArrayList<>();
        CanonicalId taskId=task==null?null:task.id();
        AuthorityPeek<SchemaIntegrityChecker.IntegrityReport> integrity=
                peeks.peekManifestIntegrity(taskId);
        ViewFact<Boolean> integrityFact=integrity.status()==PeekStatus.PRESENT
                ?ViewFact.present(Boolean.TRUE,ViewFact.Source.DURABLE,at)
                :new ViewFact<>(integrity.status()==PeekStatus.INTEGRITY_FAILURE
                        ?ViewFact.State.PRESENT:ViewFact.State.UNAVAILABLE,
                         integrity.status()==PeekStatus.INTEGRITY_FAILURE?Boolean.FALSE:null,
                         ViewFact.Source.DURABLE,at,ViewFact.Freshness.CURRENT,safeRef(integrity.code()));
        ViewFact<ThreadView> threadFact=thread==null
                ?ViewFact.absent(ViewFact.Source.DURABLE,at)
                :ViewFact.present(new ThreadView(thread.id().value(),thread.status(),
                        head==null?null:head.headTaskId().value(),head==null?0:head.version()),
                        ViewFact.Source.DURABLE,at);
        ViewFact<TaskView> taskFact=task==null
                ?ViewFact.absent(ViewFact.Source.DURABLE,at)
                :ViewFact.present(new TaskView(task.id().value(),
                        task.parentTaskId()==null?null:task.parentTaskId().value(),
                        "Task "+shortId(task.id().value()),task.status(),task.manifestId(),
                        task.manifestHash(),task.createdAt(),task.terminalAt()),
                        ViewFact.Source.DURABLE,at);
        ViewFact<RunView> runFact=run==null
                ?(task==null?ViewFact.state(ViewFact.State.NOT_APPLICABLE,
                        ViewFact.Source.DURABLE,at):ViewFact.absent(ViewFact.Source.DURABLE,at))
                :ViewFact.present(new RunView(run.id().value(),run.kind(),
                        run.terminalStatus()==null?"ACTIVE":run.terminalStatus(),
                        run.resumedFromRunId()==null?null:run.resumedFromRunId().value(),run.createdAt()),
                        ViewFact.Source.DURABLE,at);
        ViewFact<GenerationView> generationFact=generation==null
                ?(task==null?ViewFact.state(ViewFact.State.NOT_APPLICABLE,
                        ViewFact.Source.DURABLE,at):ViewFact.absent(ViewFact.Source.DURABLE,at))
                :ViewFact.present(new GenerationView(generation.id().value(),generation.state(),
                        pointer.version(),pointer.ownerRunId().value(),generation.createdAt(),
                        generation.committedAt()),ViewFact.Source.DURABLE,at);
        AuthorityPeek<PlanPeek> planPeek=task==null?null:peeks.peekPlanAuthority(task.id());
        AuthorityPeek<Optional<Revision>> latest=task==null?null:
                peeks.peekLatestPlanRevision(task.id());
        ViewFact<PlanView> planFact=plan(task,planPeek,latest,at);
        ViewFact<StepView> stepFact=currentStep(planFact,at);
        ViewFact<AttemptView> attemptFact=currentAttempt(planPeek,stepFact,at);
        ViewFact<EvidenceView> evidenceFact=evidence(planPeek,at);
        ViewFact<Boolean> completionFact=completion(planPeek,at);
        AuthorityPeek<RecoveryPeek> recoveryPeek=task==null||historical?null:
                peeks.peekRecoveryAuthority(task.id(),at);
        ViewFact<RecoveryView> recoveryFact=historical
                ?ViewFact.state(ViewFact.State.NOT_APPLICABLE,ViewFact.Source.DERIVED,at)
                :recovery(recoveryPeek,at);
        AuthorityPeek<Optional<Queue>> steeringPeek=task==null||historical?null:
                peeks.peekSteeringAuthority(task.id());
        ViewFact<SteeringView> steeringFact=historical
                ?ViewFact.state(ViewFact.State.NOT_APPLICABLE,ViewFact.Source.DERIVED,at)
                :steering(steeringPeek,at);
        AuthorityPeek<Optional<Candidate>> followPeek=thread==null||historical?null:
                peeks.peekFollowUpAuthority(thread.id());
        AuthorityPeek<Optional<PendingChildStart>> childPeek=thread==null||historical?null:
                peeks.peekChildInitialRunAuthority(thread.id(),at);
        ViewFact<FollowUpView> followFact=historical
                ?ViewFact.state(ViewFact.State.NOT_APPLICABLE,ViewFact.Source.DERIVED,at)
                :followUp(followPeek,childPeek,at);
        boolean previewMatch=preview==null||preview.activeTaskId()==null
                ||previewMatches(session,preview,workspaceIdentity,thread,task,run,
                        pointer,generation);
        if(!previewMatch)warnings.add("STALE_PREVIEW");
        if(steeringFact.isPresent()&&steeringFact.value().pending())warnings.add("STEERING_PENDING");
        if(integrity.status()==PeekStatus.INTEGRITY_FAILURE)warnings.add("AUTHORITY_INTEGRITY_FAILURE");
        else if(integrity.status()!=PeekStatus.PRESENT)warnings.add("AUTHORITY_INTEGRITY_UNAVAILABLE");
        addUnavailableWarning(warnings,"PLAN",planFact.state());
        addUnavailableWarning(warnings,"RECOVERY",recoveryFact.state());
        addUnavailableWarning(warnings,"STEERING",steeringFact.state());
        addUnavailableWarning(warnings,"FOLLOW_UP",followFact.state());
        if(historical)warnings.add("HISTORICAL_PROJECTION");
        ProviderView provider;
        ViewFact<ContextGenerationView> contextFact=context(task,run,at);
        if(contextFact.isPresent()){
            if("COMPACTION_REQUIRED".equals(contextFact.value().budgetState()))
                warnings.add("COMPACTION_REQUIRED");
            if("CONTEXT_BUDGET_EXCEEDED".equals(contextFact.value().budgetState()))
                warnings.add("CONTEXT_BUDGET_EXCEEDED");
            if(contextFact.value().candidateId()!=null
                    &&"PREPARING".equals(contextFact.value().candidateLifecycle()))
                warnings.add("COMPACTION_IN_PROGRESS");
            if(contextFact.value().candidateId()!=null
                    &&"FAILED".equals(contextFact.value().candidateLifecycle()))
                warnings.add("COMPACTION_FAILED");
        }else if(contextFact.state()==ViewFact.State.UNAVAILABLE)warnings.add("CONTEXT_UNAVAILABLE");
        try {
            provider=provider(session,task,key,at,warnings,
                    profile==TaskProjectionProfile.CURRENT_INTERACTIVE);
        } catch (SQLException|RuntimeException telemetryFailure) {
            warnings.add("PROVIDER_TELEMETRY_UNAVAILABLE");
            provider=unavailableProvider(session,"PROVIDER_TELEMETRY_READ_FAILED");
        }
        String phase=run==null?null:legacyPhase(task.id().value(),run.id().value());
        boolean healthy=!historical&&task!=null&&healthyOwner(task.id(),at);
        boolean previewConfirmationPending=previewMatch&&preview!=null
                &&preview.pendingConfirmation();
        OperationalStatusView status=operational(session,integrityFact,task,planFact,
                stepFact,attemptFact,recoveryFact,steeringFact,followFact,provider,phase,
                healthy,previewMatch,previewConfirmationPending,warnings,at,historical,key);
        return new InteractiveSessionViewModel(UUID.randomUUID().toString(),at,session.sessionId(),
                key,
                integrityFact,new WorkspaceView(workspaceIdentity,session.workspaceLabel()),
                threadFact,taskFact,runFact,generationFact,planFact,stepFact,attemptFact,
                evidenceFact,completionFact,recoveryFact,steeringFact,followFact,contextFact,
                provider,status,warnings);
    }

    private ViewFact<ContextGenerationView> context(DurableTask task,DurableRun run,Instant at){
        if(task==null||run==null)return ViewFact.state(
                ViewFact.State.NOT_APPLICABLE,ViewFact.Source.DURABLE,at);
        try(PreparedStatement statement=connection.prepareStatement("""
          SELECT p.active_context_generation_id,p.operation_kind,p.pointer_version,g.lifecycle,
                 g.input_estimate,g.tool_schema_estimate,g.output_reserve,g.safety_margin,
                 g.context_limit,g.limit_provenance,g.generation_kind,
                 c.context_generation_id,c.lifecycle,c.source_context_generation_id
          FROM context_generation_pointers p
          JOIN context_generations g ON g.context_generation_id=p.active_context_generation_id
          LEFT JOIN context_generations c ON c.source_context_generation_id=g.context_generation_id
             AND c.lifecycle IN ('PREPARING','PREPARED','FAILED')
          WHERE p.task_id=? AND p.run_id=?
          ORDER BY CASE p.operation_kind WHEN 'CHAT' THEN 0 WHEN 'TOOL_CONTINUATION' THEN 1
             WHEN 'REPAIR' THEN 2 ELSE 3 END,
             CASE c.lifecycle WHEN 'PREPARED' THEN 0 WHEN 'PREPARING' THEN 1 ELSE 2 END
          LIMIT 1
          """)){
            statement.setString(1,task.id().value());statement.setString(2,run.id().value());
            try(ResultSet row=statement.executeQuery()){
                if(!row.next())return ViewFact.absent(ViewFact.Source.DURABLE,at);
                int total=Math.addExact(Math.addExact(row.getInt(5),row.getInt(6)),
                        Math.addExact(row.getInt(7),row.getInt(8)));
                Integer limit=(Integer)row.getObject(9);
                String budget=limit==null?"CONTEXT_BUDGET_UNKNOWN":
                        total<=limit?"CONTEXT_READY":
                                "BASE".equals(row.getString(11))
                                        ?"COMPACTION_REQUIRED":"CONTEXT_BUDGET_EXCEEDED";
                return ViewFact.present(new ContextGenerationView(row.getString(1),row.getString(2),
                        row.getString(4),row.getLong(3),budget,row.getString(10),limit,total,
                        row.getString(12),row.getString(13),row.getString(14)),
                        ViewFact.Source.DURABLE,at);
            }
        }catch(SQLException|RuntimeException failure){
            return ViewFact.unavailable(ViewFact.Source.DURABLE,at,"CONTEXT_PROJECTION_FAILED");
        }
    }

    private WorkThread selectThread(InteractiveSession.Snapshot session,String workspaceIdentity)
            throws SQLException{
        if(session.binding()!=null&&session.binding().threadId()!=null){
            WorkThread bound=durable.findWorkThread(session.binding().threadId()).orElse(null);
            if(bound!=null&&workspaceIdentity.equals(bound.workspaceIdentity()))return bound;
        }
        String[] ids={session.currentTaskId(),session.lastTaskId()};
        for(String value:ids){
            CanonicalId id=parse(value);if(id==null)continue;
            DurableTask task=durable.findTask(id).orElse(null);if(task==null)continue;
            WorkThread selected=durable.findWorkThread(task.threadId()).orElse(null);
            if(selected!=null&&workspaceIdentity.equals(selected.workspaceIdentity()))return selected;
        }
        List<WorkThread> threads=durable.listWorkThreads(workspaceIdentity);
        return threads.isEmpty()?null:threads.getFirst();
    }

    private DurableRun latestRun(DurableTask task)throws SQLException{
        if(task==null)return null;
        return durable.findRunsByTask(task.id()).stream()
                .sorted(Comparator.comparing(DurableRun::createdAt).reversed()
                        .thenComparing(item->item.id().value()))
                .findFirst().orElse(null);
    }

    private ViewFact<PlanView> plan(DurableTask task,AuthorityPeek<PlanPeek> peek,
            AuthorityPeek<Optional<Revision>> latest,Instant at)throws SQLException{
        if(task==null)return ViewFact.state(ViewFact.State.NOT_APPLICABLE,ViewFact.Source.DURABLE,at);
        if(peek==null||latest==null||peek.status()==PeekStatus.UNAVAILABLE
                ||latest.status()==PeekStatus.UNAVAILABLE)
            return ViewFact.unavailable(ViewFact.Source.DURABLE,at,"PLAN_AUTHORITY_UNAVAILABLE");
        if(peek.status()==PeekStatus.INTEGRITY_FAILURE||latest.status()==PeekStatus.INTEGRITY_FAILURE)
            return ViewFact.unavailable(ViewFact.Source.DURABLE,at,"PLAN_AUTHORITY_INTEGRITY_FAILURE");
        PlanPeek value=peek.value();
        Revision revision=value.approvedPlan()==null?latest.value().orElse(null):
                value.approvedPlan().revision();
        if(revision==null)return value.applicability()==PlanApplicability.PLAN_DISABLED
                ?ViewFact.state(ViewFact.State.NOT_APPLICABLE,ViewFact.Source.DURABLE,at)
                :ViewFact.absent(ViewFact.Source.DURABLE,at);
        boolean advisory=value.applicability()!=PlanApplicability.PLAN_REQUIRED;
        PlanEvidenceSemanticEvaluator.Evaluation semantic=null;
        if(!advisory&&revision.canonicalSchemaVersion()==Revision.CANONICAL_SCHEMA_V3){
            CanonicalId evaluationRun=value.attempts().stream()
                    .max(Comparator.comparing(Attempt::startedAt)
                            .thenComparingInt(Attempt::attemptNumber))
                    .map(Attempt::runId).orElse(null);
            semantic=new PlanEvidenceSemanticEvaluator(connection).evaluate(
                    revision.taskId(),revision.planRevisionId(),evaluationRun);
        }
        List<StepView> steps=new ArrayList<>();
        for(StepDefinition definition:revision.steps()){
            StepExecution execution=value.steps().stream().filter(item->
                    item.stepRevisionId().equals(definition.stepRevisionId())).findFirst().orElse(null);
            PlanEvidenceSemanticEvaluator.StepProgress stepProgress=semantic==null?null:
                    semantic.step(definition.stepRevisionId());
            List<PlanRequirementView> requirements=advisory?List.of():definition.toolRequirements().stream()
                    .map(requirement->{
                        var projection=com.paicli.interactive.approval.CanonicalPlanPresentation
                                .requirement(requirement);
                        int satisfied=stepProgress==null?0:stepProgress.requirements().stream()
                                .filter(progress->progress.requirement().requirement()
                                        .requirementId().equals(requirement.requirementId()))
                                .mapToInt(PlanEvidenceSemanticEvaluator.RequirementProgress::
                                        satisfiedSuccesses).findFirst().orElse(0);
                        return new PlanRequirementView(projection.requirementId(),
                                projection.requirementOrder(),projection.toolName(),
                                projection.selectorKind(),projection.safeSelectorSummary(),
                                projection.requiredSlots(),satisfied);
                    }).toList();
            steps.add(new StepView(definition.stepRevisionId().value(),definition.order(),
                    bounded(SANITIZER.sanitize(definition.title()),160),
                    execution==null?(advisory?"PENDING":"NOT_LOADED"):execution.state().name(),
                    execution==null?0:execution.currentAttemptNumber(),
                    execution==null?0:execution.transitionVersion(),requirements));
        }
        String lifecycle=value.approvedPlan()==null?latestLifecycle(revision.planRevisionId().value()):
                "APPROVED";
        return ViewFact.present(new PlanView(advisory?"ADVISORY":value.applicability().name(),
                revision.planRevisionId().value(),revision.revisionNumber(),
                revision.canonicalSchemaVersion(),lifecycle,
                value.approvedPlan()!=null||"APPROVED".equals(lifecycle),
                advisory?0:value.pointerVersion(),steps),ViewFact.Source.DURABLE,at);
    }

    private ViewFact<StepView> currentStep(ViewFact<PlanView> plan,Instant at){
        if(!plan.isPresent())return ViewFact.state(plan.state()==ViewFact.State.UNAVAILABLE
                ?ViewFact.State.UNAVAILABLE:ViewFact.State.NOT_APPLICABLE,ViewFact.Source.DERIVED,at);
        StepView step=plan.value().steps().stream().filter(item->
                List.of("RUNNING","READY","FAILED","BLOCKED").contains(item.state()))
                .findFirst().orElse(null);
        return step==null?ViewFact.absent(ViewFact.Source.DERIVED,at):
                ViewFact.present(step,ViewFact.Source.DERIVED,at);
    }

    private ViewFact<AttemptView> currentAttempt(AuthorityPeek<PlanPeek> plan,
            ViewFact<StepView> step,Instant at){
        if(!step.isPresent()||plan==null||plan.status()!=PeekStatus.PRESENT)
            return ViewFact.state(step.state()==ViewFact.State.UNAVAILABLE
                    ?ViewFact.State.UNAVAILABLE:ViewFact.State.NOT_APPLICABLE,
                    ViewFact.Source.DERIVED,at);
        Attempt attempt=plan.value().attempts().stream().filter(item->
                item.stepRevisionId().value().equals(step.value().stepId()))
                .max(Comparator.comparingInt(Attempt::attemptNumber)).orElse(null);
        return attempt==null?ViewFact.absent(ViewFact.Source.DURABLE,at):
                ViewFact.present(new AttemptView(attempt.stepRevisionId().value(),
                        attempt.runId().value(),attempt.attemptNumber(),attempt.state().name(),
                        attempt.startIteration(),attempt.endIteration(),attempt.startedAt(),
                        attempt.finishedAt(),attempt.failureClass()),ViewFact.Source.DURABLE,at);
    }

    private ViewFact<EvidenceView> evidence(AuthorityPeek<PlanPeek> plan,Instant at){
        if(plan==null||plan.status()!=PeekStatus.PRESENT||plan.value().completion()==null)
            return ViewFact.state(ViewFact.State.NOT_APPLICABLE,ViewFact.Source.DURABLE,at);
        if(plan.value().approvedPlan()!=null&&plan.value().approvedPlan().revision()
                .canonicalSchemaVersion()==Revision.CANONICAL_SCHEMA_V3){
            Revision revision=plan.value().approvedPlan().revision();
            CanonicalId evaluationRun=plan.value().attempts().stream()
                    .max(Comparator.comparing(Attempt::startedAt)
                            .thenComparingInt(Attempt::attemptNumber))
                    .map(Attempt::runId).orElse(null);
            PlanEvidenceSemanticEvaluator.Evaluation semantic=
                    new PlanEvidenceSemanticEvaluator(connection).evaluate(
                            revision.taskId(),revision.planRevisionId(),evaluationRun);
            int remaining=Math.max(0,semantic.requiredSuccesses()
                    -semantic.satisfiedSuccesses());
            return ViewFact.present(new EvidenceView(semantic.requiredSuccesses(),
                    semantic.satisfiedSuccesses(),0,remaining,semantic.integrityValid(),
                    plan.value().completion().completed()),ViewFact.Source.DURABLE,at);
        }
        List<Evidence> values=plan.value().completion().evidence();
        int success=(int)values.stream().filter(v->"SUCCEEDED".equals(v.observedStatus())).count();
        int failed=(int)values.stream().filter(v->"FAILED".equals(v.observedStatus())).count();
        return ViewFact.present(new EvidenceView(values.size(),success,failed,
                values.size()-success-failed,values.stream().allMatch(Evidence::integrityValid),
                plan.value().completion().completed()),ViewFact.Source.DURABLE,at);
    }

    private ViewFact<Boolean> completion(AuthorityPeek<PlanPeek> plan,Instant at){
        if(plan==null||plan.status()!=PeekStatus.PRESENT||plan.value().completion()==null)
            return ViewFact.state(ViewFact.State.NOT_APPLICABLE,ViewFact.Source.DURABLE,at);
        return ViewFact.present(plan.value().completion().completed(),ViewFact.Source.DURABLE,at);
    }

    private ViewFact<RecoveryView> recovery(AuthorityPeek<RecoveryPeek> peek,Instant at){
        if(peek==null||peek.status()==PeekStatus.NOT_APPLICABLE)
            return ViewFact.state(ViewFact.State.NOT_APPLICABLE,ViewFact.Source.DURABLE,at);
        if(peek.status()==PeekStatus.UNAVAILABLE||peek.status()==PeekStatus.INTEGRITY_FAILURE)
            return ViewFact.unavailable(ViewFact.Source.DURABLE,at,safeRef(peek.code()));
        if(peek.status()==PeekStatus.ABSENT)return ViewFact.absent(ViewFact.Source.DURABLE,at);
        return ViewFact.present(new RecoveryView(peek.value().status(),peek.value().eligible(),
                peek.value().reasons()),ViewFact.Source.DURABLE,at);
    }

    private ViewFact<SteeringView> steering(AuthorityPeek<Optional<Queue>> peek,Instant at){
        if(peek==null)return ViewFact.state(ViewFact.State.NOT_APPLICABLE,ViewFact.Source.DURABLE,at);
        if(peek.status()!=PeekStatus.PRESENT)
            return ViewFact.unavailable(ViewFact.Source.DURABLE,at,safeRef(peek.code()));
        Queue queue=peek.value().orElse(null);
        return queue==null?ViewFact.absent(ViewFact.Source.DURABLE,at):
                ViewFact.present(new SteeringView(queue.steeringId().value(),queue.state().name(),
                        true,"NEEDS_CONFIRMATION".equals(queue.state().name())),
                        ViewFact.Source.DURABLE,at);
    }

    private ViewFact<FollowUpView> followUp(AuthorityPeek<Optional<Candidate>> follow,
            AuthorityPeek<Optional<PendingChildStart>> child,Instant at){
        if(follow==null||child==null)return ViewFact.state(ViewFact.State.NOT_APPLICABLE,
                ViewFact.Source.DURABLE,at);
        if(follow.status()!=PeekStatus.PRESENT||child.status()!=PeekStatus.PRESENT)
            return ViewFact.unavailable(ViewFact.Source.DURABLE,at,"FOLLOW_UP_AUTHORITY_UNAVAILABLE");
        PendingChildStart pending=child.value().orElse(null);
        Candidate candidate=pending==null?follow.value().orElse(null):pending.candidate();
        if(candidate==null)return ViewFact.absent(ViewFact.Source.DURABLE,at);
        return ViewFact.present(new FollowUpView(candidate.capsuleId().value(),
                candidate.state().name(),List.of("NEEDS_INTENT_CONFIRMATION",
                "NEEDS_RISK_CONFIRMATION").contains(candidate.state().name()),pending!=null,
                pending==null?null:pending.child().taskId().value(),
                pending==null?null:pending.child().runId().value()),ViewFact.Source.DURABLE,at);
    }

    private ProviderView provider(InteractiveSession.Snapshot session,DurableTask task,
            TaskProjectionKey key,Instant at,List<String> warnings,
            boolean includeSessionUsage)throws SQLException{
        boolean report=key!=null&&key.profile()==TaskProjectionProfile.HISTORICAL_REPORT;
        CanonicalId reportRun=report?key.identity().selectedRunId():null;
        boolean configured=session.provider()!=null&&!"unconfigured".equals(session.provider());
        ProviderCallTelemetryReader.ValidatedCall latest=
                latestTelemetry(session,task,key,includeSessionUsage);
        ProviderCallTelemetryReader.ValidatedCall health=telemetry.latest(
                ProviderCallTelemetryReader.Scope.session(session.sessionId(),
                        session.provider(),session.model()),"HEALTH").orElse(null);
        String expectedHealthFingerprint=providerHealthFingerprint(session);
        if(health!=null&&(expectedHealthFingerprint==null
                ||!expectedHealthFingerprint.equals(health.structuralSummaryHash())))health=null;
        ProviderCallTelemetryReader.UsageAggregate sessionUsage=includeSessionUsage
                ?telemetry.aggregate(ProviderCallTelemetryReader.Scope.session(
                        session.sessionId(),session.provider(),session.model()))
                :emptyUsage();
        ProviderCallTelemetryReader.UsageAggregate taskUsage=task==null?emptyUsage():
                report?(reportRun==null?emptyUsage():telemetry.aggregate(
                        ProviderCallTelemetryReader.Scope.taskRun(task.id().value(),
                                reportRun.value(),session.provider(),session.model()))):
                        telemetry.aggregate(ProviderCallTelemetryReader.Scope.task(
                                task.id().value(),session.provider(),session.model()));
        ProviderView.LastCallState last=latest==null?ProviderView.LastCallState.UNKNOWN:
                switch(latest.lifecycle()){
                    case "SUCCEEDED"->ProviderView.LastCallState.LAST_CALL_SUCCEEDED;
                    case "FAILED"->ProviderView.LastCallState.LAST_CALL_FAILED;
                    case "CANCELLED"->ProviderView.LastCallState.LAST_CALL_CANCELLED;
                    default->ProviderView.LastCallState.LAST_CALL_INCOMPLETE;
                };
        if(latest!=null&&"STARTED".equals(latest.lifecycle())
                &&latest.startedAt().plus(INCOMPLETE_STALE_AFTER).isBefore(at))
            warnings.add("TELEMETRY_STALE");
        Instant healthObservedAt=health==null?null:
                health.endedAt()==null?health.startedAt():health.endedAt();
        boolean staleHealth=healthObservedAt!=null
                &&!healthObservedAt.plus(INCOMPLETE_STALE_AFTER).isAfter(at);
        ProviderView.ReadinessState readiness=!configured?ProviderView.ReadinessState.UNAVAILABLE:
                health==null?ProviderView.ReadinessState.READINESS_UNKNOWN:
                        staleHealth?ProviderView.ReadinessState.STALE:
                                "SUCCEEDED".equals(health.lifecycle())
                                        &&"HEALTHY".equals(health.outcome())
                                ?ProviderView.ReadinessState.HEALTHY:
                                ProviderView.ReadinessState.DEGRADED;
        if(readiness==ProviderView.ReadinessState.READINESS_UNKNOWN
                &&(task==null||"CREATED".equals(task.status())))
            warnings.add("PROVIDER_READINESS_UNKNOWN");
        ProviderView.UsageState usage=latest==null||"NONE".equals(latest.usageCoverage())
                ?ProviderView.UsageState.USAGE_NOT_EXPOSED:
                "COMPLETE".equals(latest.usageCoverage())?ProviderView.UsageState.REPORTED:
                        ProviderView.UsageState.PARTIAL;
        String[] thinking=providerThinking();
        return new ProviderView(session.provider(),session.model(),session.providerSource(),
                ProviderView.AvailabilityState.AVAILABLE,
                configured?ProviderView.ConfigurationState.CONFIGURED:
                        ProviderView.ConfigurationState.NOT_CONFIGURED,
                readiness,last,usage,latest==null?null:latest.operationKind(),
                latest==null?null:latest.outcome(),
                latest==null?null:latest.errorCategory(),latest==null?null:latest.latencyMs(),
                latest==null?null:latest.inputTokens(),latest==null?null:latest.outputTokens(),
                latest==null?null:latest.totalTokens(),sessionUsage.callCount(),taskUsage.callCount(),
                sessionUsage.inputTokens(),sessionUsage.outputTokens(),sessionUsage.totalTokens(),
                taskUsage.inputTokens(),taskUsage.outputTokens(),taskUsage.totalTokens(),
                latest==null?null:latest.endedAt()==null?latest.startedAt():latest.endedAt(),
                latest==null?null:latest.diagnosticRef(),thinking[0],thinking[1],
                healthObservedAt);
    }

    private String providerHealthFingerprint(InteractiveSession.Snapshot session){
        return session.providerConfigurationFingerprint();
    }

    private String[] providerThinking(){
        try{
            com.paicli.config.Config config=new com.paicli.config.ConfigLoader(workspace).load();
            if(!"deepseek".equals(config.providerPreset()))return new String[]{null,null};
            return new String[]{config.effectiveThinking(),config.effectiveReasoningEffort()};
        }catch(RuntimeException ignored){return new String[]{null,null};}
    }

    private OperationalStatusView operational(InteractiveSession.Snapshot session,
            ViewFact<Boolean> integrity,DurableTask task,ViewFact<PlanView> plan,
            ViewFact<StepView> step,ViewFact<AttemptView> attempt,
            ViewFact<RecoveryView> recovery,ViewFact<SteeringView> steering,
            ViewFact<FollowUpView> followUp,ProviderView provider,String legacyPhase,
            boolean healthyOwner,boolean previewMatch,boolean previewConfirmationPending,
            List<String> warnings,Instant at,boolean historical,TaskProjectionKey key)
            throws SQLException{
        boolean activeTask=session.currentTaskId()!=null;
        boolean sessionRow=key!=null&&key.profile()==TaskProjectionProfile.SESSION_ROW;
        boolean providerDegraded=provider.readinessState()==ProviderView.ReadinessState.UNAVAILABLE
                ||provider.readinessState()==ProviderView.ReadinessState.DEGRADED;
        boolean historicalExecuting=historical&&List.of("PLANNING","EXECUTING")
                .contains(legacyPhase==null?"":legacyPhase.toUpperCase(Locale.ROOT));
        OperationalStatusView.Status primary=OperationalStatusPrecedence.select(
                new OperationalStatusPrecedence.Facts(
                        integrity.isPresent()&&!integrity.value(),
                        confirmationRequired(followUp)||previewConfirmationPending,
                        (activeTask||sessionRow)&&recovery.isPresent()
                                &&recovery.value().eligible(),
                        task!=null&&continuityWaiting(task.id().value()),
                        followUp.isPresent()&&followUp.value().childInitialRunPending(),
                        historical?historicalExecuting:activeTask&&(healthyOwner||executing(task,step,attempt)),
                        !historical&&activeTask&&task!=null&&"RUNNING".equals(task.status())
                                &&plan.isPresent()
                                &&"PLAN_REQUIRED".equals(plan.value().applicability())
                                &&!plan.value().approved(),
                        "VERIFYING".equalsIgnoreCase(legacyPhase),
                        "REPAIRING".equalsIgnoreCase(legacyPhase),
                        activeTask&&followUp.isPresent(),historical?"COMPLETED".equalsIgnoreCase(legacyPhase):
                                activeTask&&task!=null&&"COMPLETED".equals(task.status()),
                        providerDegraded&&(task==null||"CREATED".equals(task.status())),
                        !historical&&session.status()==InteractiveSession.Status.ACTIVE
                                &&!activeTask,task==null));
        FailureDomain domain=null;
        if(primary==OperationalStatusView.Status.BLOCKED_INTEGRITY){
            domain=integrity.diagnosticRef()!=null
                    &&integrity.diagnosticRef().contains("MANIFEST")
                    ?FailureDomain.MANIFEST:FailureDomain.AUTHORITY_INTEGRITY;
        }else if(primary==OperationalStatusView.Status.RECOVERY_REQUIRED){
            domain=FailureDomain.RECOVERY;
        }else if(primary==OperationalStatusView.Status.PROVIDER_DEGRADED
                ||primary==OperationalStatusView.Status.FAILED){
            domain=primary==OperationalStatusView.Status.PROVIDER_DEGRADED
                    &&provider.lastErrorCategory()==null
                    ?FailureDomain.PROVIDER_INFRASTRUCTURE
                    :failureDomain(task,key,provider.lastErrorCategory());
        }
        List<String> allowed=allowed(primary);
        if(historical)allowed=allowed.stream().filter(value->
                List.of("/status","/report","/doctor","/exit").contains(value)).toList();
        boolean authorityUnavailable=warnings.stream().anyMatch(value->
                value.equals("AUTHORITY_INTEGRITY_UNAVAILABLE")
                        ||value.startsWith("PLAN_UNAVAILABLE")
                        ||value.startsWith("RECOVERY_UNAVAILABLE")
                        ||value.startsWith("STEERING_UNAVAILABLE")
                        ||value.startsWith("FOLLOW_UP_UNAVAILABLE")
                        ||value.equals("PROVIDER_TELEMETRY_UNAVAILABLE")
                        ||value.endsWith("_STALE")&&!value.equals("TELEMETRY_STALE"));
        if(!previewMatch||authorityUnavailable)
            allowed=allowed.stream().filter(value->
                    List.of("/status","/doctor","/exit").contains(value)).toList();
        else if(warnings.contains("PROVIDER_READINESS_UNKNOWN")
                ||warnings.contains("TELEMETRY_STALE"))
            allowed=allowed.stream().filter(value->!"submit task".equals(value)).toList();
        ViewFact<FailureDomain> failure=domain==null
                ?ViewFact.absent(ViewFact.Source.DERIVED,at):
                ViewFact.present(domain,ViewFact.Source.DERIVED,at);
        return new OperationalStatusView(primary,warnings,allowed,prohibited(primary),
                failure,prompt(primary),RULE_VERSION);
    }

    private boolean previewMatches(InteractiveSession.Snapshot session,
            InteractiveRuntimeView.Snapshot preview,String workspaceIdentity,
            WorkThread thread,DurableTask task,DurableRun run,
            TaskContextPointer pointer,TaskContextGeneration generation){
        return thread!=null&&task!=null&&run!=null&&pointer!=null
                &&generation!=null
                &&session.binding()!=null&&session.binding().threadId()!=null
                &&Objects.equals(preview.workspaceIdentity(),workspaceIdentity)
                &&Objects.equals(preview.threadId(),thread.id().value())
                &&session.binding().threadId().equals(thread.id())
                &&Objects.equals(preview.activeTaskId(),task.id().value())
                &&Objects.equals(preview.activeRunId(),run.id().value())
                &&Objects.equals(preview.generationId(),generation.id().value())
                &&Objects.equals(pointer.generationId(),generation.id())
                &&Objects.equals(pointer.ownerRunId(),run.id());
    }

    private boolean healthyOwner(CanonicalId taskId,Instant at)throws SQLException{
        OwnershipLease lease=durable.readOwnership(taskId).orElse(null);
        return lease!=null&&"ACTIVE".equals(lease.state())&&at.isBefore(lease.expiresAt());
    }

    private boolean continuityWaiting(String taskId)throws SQLException{
        try(PreparedStatement statement=connection.prepareStatement("""
                SELECT state FROM task_continuity_segments
                WHERE task_id=? ORDER BY segment_ordinal DESC LIMIT 1
                """)){
            statement.setString(1,taskId);
            try(ResultSet row=statement.executeQuery()){
                return row.next()&&"WAITING_FOR_USER".equals(row.getString(1));
            }
        }
    }

    private static boolean executing(DurableTask task,ViewFact<StepView> step,
            ViewFact<AttemptView> attempt){
        return task!=null&&"RUNNING".equals(task.status())
                &&(step.isPresent()&&"RUNNING".equals(step.value().state())
                ||attempt.isPresent()&&"RUNNING".equals(attempt.value().state()));
    }

    private static boolean confirmationRequired(ViewFact<FollowUpView> followUp){
        return followUp.isPresent()&&followUp.value().confirmationRequired();
    }

    private static List<String> allowed(OperationalStatusView.Status status){
        return switch(status){
            case BLOCKED_INTEGRITY->List.of("/status","/doctor","/exit");
            case WAITING_FOR_CONFIRMATION->List.of("confirm","cancel","/status","/exit");
            case RECOVERY_REQUIRED->List.of("/task recover","/new","/status","/doctor","/exit");
            case WAITING_FOR_USER->List.of("resolve required action","/status","/exit");
            case CHILD_INITIAL_RUN_PENDING->List.of("resume child start","/status","/exit");
            case EXECUTING->List.of("/status","/plan","/report","/steer","cancel","/exit");
            case WAITING_FOR_APPROVAL->List.of("approve","reject","/plan","/status","/exit");
            case VERIFYING,REPAIRING->List.of("/status","/report","cancel","/exit");
            case FOLLOW_UP_PENDING->List.of("confirm follow-up","cancel","/status","/exit");
            case COMPLETED->List.of("/continue","/new","/report","/status","/exit");
            case PROVIDER_DEGRADED->List.of("/provider status","/provider test","/provider set","/status","/exit");
            case READY->List.of("submit task","/status","/provider status","/exit");
            case IDLE->List.of("/new","/sessions","/status","/exit");
            case FAILED->List.of("/report","/new","/status","/doctor","/exit");
        };
    }

    private static List<String> prohibited(OperationalStatusView.Status status){
        return switch(status){
            case BLOCKED_INTEGRITY->List.of("execute","recover","approve","follow-up");
            case WAITING_FOR_CONFIRMATION->List.of("execute without confirmation");
            case RECOVERY_REQUIRED->List.of("execute without recovery admission");
            case WAITING_FOR_USER->List.of("automatic continuation","bypass required action");
            case CHILD_INITIAL_RUN_PENDING->List.of("claim child outside Child-start validator");
            case EXECUTING->List.of("change provider","change model","start competing run");
            case WAITING_FOR_APPROVAL->List.of("execute unapproved plan");
            case VERIFYING,REPAIRING->List.of("start competing verification");
            case FOLLOW_UP_PENDING->List.of("admit child without confirmation");
            case COMPLETED->List.of("mutate completed Task");
            case PROVIDER_DEGRADED->List.of("provider-required execution");
            case READY,IDLE->List.of("bypass command authority validators");
            case FAILED->List.of("resume without Recovery validator");
        };
    }

    private static String prompt(OperationalStatusView.Status status){
        return bounded(switch(status){
            case BLOCKED_INTEGRITY->"Authority integrity is blocked; inspect status or doctor.";
            case WAITING_FOR_CONFIRMATION->"Confirmation is required before work can continue.";
            case RECOVERY_REQUIRED->"Validated recovery is available; run /task recover.";
            case WAITING_FOR_USER->"User action is required before execution can continue.";
            case CHILD_INITIAL_RUN_PENDING->"A Child initial run is pending validated startup.";
            case EXECUTING->"Task execution is active.";
            case WAITING_FOR_APPROVAL->"The current Plan revision requires approval.";
            case VERIFYING->"Verification is in progress.";
            case REPAIRING->"Repair is in progress.";
            case FOLLOW_UP_PENDING->"A Follow-up candidate is pending.";
            case COMPLETED->"Task is complete; a Follow-up or new Task may be started.";
            case PROVIDER_DEGRADED->"Provider is unavailable or degraded.";
            case READY->"Ready for a new legal action.";
            case IDLE->"No current Task is selected.";
            case FAILED->"The current Task failed; inspect report and recovery.";
        },256);
    }

    private ProviderCallTelemetryReader.ValidatedCall latestTelemetry(
            InteractiveSession.Snapshot session,DurableTask task,
            TaskProjectionKey key,boolean includeSession)
            throws SQLException{
        boolean report=key!=null&&key.profile()==TaskProjectionProfile.HISTORICAL_REPORT;
        CanonicalId reportRun=report?key.identity().selectedRunId():null;
        ProviderCallTelemetryReader.ValidatedCall sessionRow=includeSession
                ?telemetry.latest(ProviderCallTelemetryReader.Scope.session(
                        session.sessionId(),session.provider(),session.model())).orElse(null):null;
        ProviderCallTelemetryReader.ValidatedCall taskRow=task==null||report&&reportRun==null?null:
                telemetry.latest(report
                        ?ProviderCallTelemetryReader.Scope.taskRun(task.id().value(),
                                reportRun.value(),session.provider(),session.model())
                        :ProviderCallTelemetryReader.Scope.task(task.id().value(),
                                session.provider(),session.model())).orElse(null);
        if(sessionRow==null)return taskRow;
        if(taskRow==null)return sessionRow;
        return sessionRow.startedAt().isAfter(taskRow.startedAt())?sessionRow:taskRow;
    }

    private static ProviderCallTelemetryReader.UsageAggregate emptyUsage(){
        return new ProviderCallTelemetryReader.UsageAggregate(0,null,null,null,"NONE");
    }

    private String latestLifecycle(String revisionId)throws SQLException{
        try(PreparedStatement statement=connection.prepareStatement(
                "SELECT state FROM plan_revision_lifecycle WHERE plan_revision_id=?")){
            statement.setString(1,revisionId);
            try(ResultSet row=statement.executeQuery()){return row.next()?row.getString(1):"UNKNOWN";}
        }
    }

    private String legacyPhase(String taskId,String runId)throws SQLException{
        try(PreparedStatement statement=connection.prepareStatement(
                "SELECT phase FROM coding_runs WHERE task_id=? AND run_id=?")){
            statement.setString(1,taskId);statement.setString(2,runId);
            try(ResultSet row=statement.executeQuery()){return row.next()?row.getString(1):null;}
        }
    }

    private FailureDomain failureDomain(DurableTask task,TaskProjectionKey key,
            String providerCategory)
            throws SQLException{
        FailureDomain provider=failureCategory(providerCategory);
        if(providerCategory!=null)return provider;
        if(task==null)return FailureDomain.INTERNAL;
        boolean report=key!=null&&key.profile()==TaskProjectionProfile.HISTORICAL_REPORT;
        CanonicalId selectedRun=report?key.identity().selectedRunId():null;
        if(report&&selectedRun==null)return FailureDomain.INTERNAL;
        try(PreparedStatement statement=connection.prepareStatement(report
                ?"SELECT status FROM verification_runs WHERE task_id=? AND run_id=? "
                        +"ORDER BY started_at DESC,rowid DESC LIMIT 1"
                :"SELECT status FROM verification_runs WHERE task_id=? "
                        +"ORDER BY started_at DESC,rowid DESC LIMIT 1")){
            statement.setString(1,task.id().value());
            if(report)statement.setString(2,selectedRun.value());
            try(ResultSet row=statement.executeQuery()){
                if(row.next()&&List.of("FAILED","TIMED_OUT","ERROR")
                        .contains(row.getString(1)))return FailureDomain.VERIFICATION;}
        }
        if(!report)try(PreparedStatement statement=connection.prepareStatement(
                "SELECT result_success FROM tool_calls WHERE task_id=? ORDER BY id DESC LIMIT 1")){
            statement.setString(1,task.id().value());
            try(ResultSet row=statement.executeQuery()){
                if(row.next()&&row.getInt(1)==0)return FailureDomain.TOOL_EXECUTION;}
        }
        try(PreparedStatement statement=connection.prepareStatement(report
                ?"SELECT last_error FROM coding_runs WHERE task_id=? AND run_id=?"
                :"SELECT last_error FROM tasks WHERE task_id=?")){
            statement.setString(1,task.id().value());
            if(report)statement.setString(2,selectedRun.value());
            try(ResultSet row=statement.executeQuery()){
                if(row.next())return failureCategory(row.getString(1));}
        }
        return FailureDomain.INTERNAL;
    }

    private static FailureDomain failureCategory(String category){
        if(category==null)return FailureDomain.INTERNAL;
        String value=category.toUpperCase(Locale.ROOT);
        if(value.contains("MANIFEST"))return FailureDomain.MANIFEST;
        if(value.contains("INTEGRITY")||value.contains("AUTHORITY"))
            return FailureDomain.AUTHORITY_INTEGRITY;
        if(value.contains("RECOVER"))return FailureDomain.RECOVERY;
        if(value.contains("MULTI_TOOL")||value.contains("TOOL_ADMISSION"))
            return FailureDomain.TOOL_ADMISSION;
        if(value.contains("TOOL"))return FailureDomain.TOOL_EXECUTION;
        if(value.contains("VERIFY"))return FailureDomain.VERIFICATION;
        if(value.contains("PARSE"))return FailureDomain.PROVIDER_RESPONSE_PARSE;
        if(value.contains("PROTOCOL"))return FailureDomain.PROVIDER_PROTOCOL;
        if(value.contains("CANCEL"))return FailureDomain.USER_CANCELLED;
        if(value.startsWith("PROVIDER_"))return FailureDomain.PROVIDER_INFRASTRUCTURE;
        return FailureDomain.INTERNAL;
    }

    private InteractiveSessionViewModel unavailable(InteractiveSession.Snapshot session,String ref){
        Instant at=Instant.now();
        ViewFact<Boolean> integrity=ViewFact.unavailable(ViewFact.Source.DURABLE,at,ref);
        OperationalStatusView status=new OperationalStatusView(
                OperationalStatusView.Status.BLOCKED_INTEGRITY,
                List.of("AUTHORITY_INTEGRITY_UNAVAILABLE"),
                List.of("/status","/doctor","/exit"),
                List.of("execute","recover","approve","follow-up"),
                ViewFact.present(FailureDomain.AUTHORITY_INTEGRITY,ViewFact.Source.DERIVED,at),
                "Durable authority is unavailable; inspect status or doctor.",RULE_VERSION);
        ProviderView provider=new ProviderView(session.provider(),session.model(),
                session.providerSource(),ProviderView.AvailabilityState.UNAVAILABLE,
                "unconfigured".equals(session.provider())
                ?ProviderView.ConfigurationState.NOT_CONFIGURED:
                ProviderView.ConfigurationState.CONFIGURED,
                ProviderView.ReadinessState.READINESS_UNKNOWN,
                ProviderView.LastCallState.UNKNOWN,ProviderView.UsageState.USAGE_NOT_EXPOSED,
                null,null,null,null,null,null,null,0,0,
                null,null,null,null,null,null,null,ref);
        ViewFact.State u=ViewFact.State.UNAVAILABLE;
        ViewFact.Source d=ViewFact.Source.DURABLE;
        return new InteractiveSessionViewModel(UUID.randomUUID().toString(),at,session.sessionId(),
                null,
                integrity,new WorkspaceView(SavedSessionService.workspaceIdentity(workspace),
                session.workspaceLabel()),ViewFact.state(u,d,at),ViewFact.state(u,d,at),
                ViewFact.state(u,d,at),ViewFact.state(u,d,at),ViewFact.state(u,d,at),
                ViewFact.state(u,ViewFact.Source.DERIVED,at),
                ViewFact.state(u,ViewFact.Source.DERIVED,at),ViewFact.state(u,d,at),
                ViewFact.state(u,d,at),ViewFact.state(u,d,at),ViewFact.state(u,d,at),
                ViewFact.state(u,d,at),ViewFact.state(u,d,at),provider,status,status.warnings());
    }

    private static ProviderView unavailableProvider(InteractiveSession.Snapshot session,
            String diagnosticRef) {
        return new ProviderView(session.provider(),session.model(),session.providerSource(),
                ProviderView.AvailabilityState.UNAVAILABLE,
                "unconfigured".equals(session.provider())
                        ?ProviderView.ConfigurationState.NOT_CONFIGURED:
                        ProviderView.ConfigurationState.CONFIGURED,
                ProviderView.ReadinessState.READINESS_UNKNOWN,
                ProviderView.LastCallState.UNKNOWN,
                ProviderView.UsageState.USAGE_NOT_EXPOSED,
                null,null,null,null,null,null,null,0,0,
                null,null,null,null,null,null,null,diagnosticRef);
    }

    private static void addUnavailableWarning(List<String> warnings,String name,ViewFact.State state){
        if(state==ViewFact.State.UNAVAILABLE||state==ViewFact.State.UNKNOWN)
            warnings.add(name+"_UNAVAILABLE");
        else if(state==ViewFact.State.STALE)warnings.add(name+"_STALE");
    }
    private static CanonicalId parse(String value){
        if(value==null)return null;
        try{return CanonicalId.parse(value);}catch(IllegalArgumentException error){return null;}
    }
    private static String safeRef(String value){
        return value!=null&&value.matches("[A-Za-z0-9._:-]{1,128}")
                ?value:"VIEW_DIAGNOSTIC_UNAVAILABLE";
    }
    private static String bounded(String value,int limit){
        if(value==null)return null;
        String clean=value.replaceAll("[\\r\\n\\t]"," ");
        int points=clean.codePointCount(0,clean.length());
        return points<=limit?clean:clean.substring(0,clean.offsetByCodePoints(0,limit));
    }
    private static String shortId(String value){
        return value==null?"none":value.substring(0,Math.min(16,value.length()));
    }
    private static final class ProjectionIntegrityException extends SQLException{
        private ProjectionIntegrityException(String code){super(code);}
    }
}
