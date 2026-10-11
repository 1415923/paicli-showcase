package com.paicli.interactive.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.identity.CanonicalId;
import com.paicli.agent.ToolFactProjection;
import com.paicli.model.AgentMessage;
import com.paicli.interactive.runtime.InteractiveRuntimeView;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.session.SavedSessionService;
import com.paicli.interactive.render.InteractiveViewModelRenderer;
import com.paicli.interactive.view.InteractiveSessionViewModel;
import com.paicli.interactive.view.SelectedTaskIdentity;
import com.paicli.interactive.view.TaskProjectionKey;
import com.paicli.interactive.view.TaskProjectionProfile;
import com.paicli.interactive.view.TaskReportView;
import com.paicli.interactive.task.RewindEligibilityService;
import com.paicli.runtime.PersistenceSanitizer;
import com.paicli.safety.SafetyPolicy;
import com.paicli.state.ApprovalStore;
import com.paicli.state.CodingRunStore;
import com.paicli.state.CodeSearchAuditStore;
import com.paicli.state.FileSnapshotStore;
import com.paicli.state.InteractiveProposalStore;
import com.paicli.state.InteractiveTaskFactStore;
import com.paicli.state.RepairAttemptStore;
import com.paicli.state.StateStore;
import com.paicli.state.TaskPlanStore;
import com.paicli.state.TaskStore;
import com.paicli.state.ToolCallStore;
import com.paicli.state.VerificationStore;
import com.paicli.state.R1AuthorityPeek;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.time.Instant;

/** Bounded workspace-scoped queries over durable runtime facts. */
public final class StateStoreInteractiveQueryFacade implements InteractiveQueryFacade {
    private static final int MAX_TASKS = 20;
    private static final int MAX_PROPOSALS = 100;
    private static final PersistenceSanitizer SANITIZER = new PersistenceSanitizer();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_])(?:[A-Z]:[\\\\/]|\\\\\\\\)[^\\s\\\"'<>|]+" );
    private static final Pattern UNIX_ABSOLUTE_PATH = Pattern.compile(
            "(?<![A-Za-z0-9_])/(?:[^\\s/]+/)+[^\\s,;:)]*" );

    private final Path workspace;
    private final InteractiveRuntimeView view;
    private final PersistedRecentTaskResolver recent;
    private final RewindEligibilityService rewindEligibility;
    private final InteractiveViewModelRenderer viewRenderer =
            new InteractiveViewModelRenderer();

    public StateStoreInteractiveQueryFacade(Path workspace, InteractiveRuntimeView view) {
        this.recent = new PersistedRecentTaskResolver(workspace);
        this.workspace = recent.workspaceIdentity();
        this.view = view;
        this.rewindEligibility = new RewindEligibilityService(this.workspace);
    }

    @Override
    public List<String> status(InteractiveSession.Snapshot session) {
        return status(session, List.of());
    }

    @Override
    public List<String> status(InteractiveSession.Snapshot session,
            List<String> arguments) {
        InteractiveRuntimeView.Snapshot live = view.snapshot();
        try (StateStore state = new StateStore(workspace)) {
            InteractiveSessionViewModel model =
                    state.viewModels.read(session, live);
            TaskStore.TaskRecord persisted = recent.latest(state).orElse(null);
            VerificationStore.VerificationRecord verification = persisted == null ? null
                    : latestVerification(state, persisted.taskId());
            var facts = new InteractiveViewModelRenderer.StatusSnapshotFacts(
                    applicationVersion(), session.status().name(), session.approvalMode(),
                    session.verificationMode(), session.verificationSource(), live.phase(),
                    verification == null ? null : currentWorkspaceVerification(
                            state, persisted.taskId(), verification),
                    persisted == null ? null : persisted.lastError(),
                    session.currentTaskId() != null);
            List<String> lines = new ArrayList<>(
                    viewRenderer.status(model, arguments, facts));
            appendRuntimeStatus(lines, live, arguments);
            if (isDetailsStatus(arguments)) appendPersistedStatus(lines, state);
            return List.copyOf(lines);
        } catch (Exception error) {
            List<String> lines = new ArrayList<>(List.of(
                    "Operational status: BLOCKED_INTEGRITY",
                    "Status query unavailable: VIEW_SNAPSHOT_UNAVAILABLE"));
            appendRuntimeStatus(lines, live, arguments);
            return List.copyOf(lines);
        }
    }

    @Override
    public ViewModelReadResult readViewModel(
            InteractiveSession.Snapshot session) {
        if (session == null) return ViewModelReadResult.absent();
        try (StateStore state = new StateStore(workspace)) {
            InteractiveSessionViewModel model = state.viewModels.read(session, view.snapshot());
            if (model.operationalStatus().primary()
                    == com.paicli.interactive.view.OperationalStatusView.Status.BLOCKED_INTEGRITY) {
                if(model.durableAuthorityIntegrity().isPresent()
                        &&Boolean.FALSE.equals(model.durableAuthorityIntegrity().value())){
                    return new ViewModelReadResult(ViewModelReadResult.State.INTEGRITY_FAILURE,
                            null,"VIEW_AUTHORITY_INTEGRITY_BLOCKED");
                }
                return ViewModelReadResult.unavailable("VIEW_SNAPSHOT_UNAVAILABLE");
            }
            return ViewModelReadResult.present(model);
        } catch (Exception error) {
            return ViewModelReadResult.unavailable("VIEW_MODEL_OPEN_FAILED");
        }
    }

    @Override
    public List<String> providerStatus(InteractiveSession.Snapshot session) {
        ViewModelReadResult result = readViewModel(session);
        return result.state() == ViewModelReadResult.State.PRESENT
                ? viewRenderer.provider(result.value(), false)
                : List.of("Provider status: " + result.state());
    }

    @Override
    public List<String> plan(InteractiveSession.Snapshot session) {
        return plan(session, false);
    }

    @Override
    public List<String> plan(InteractiveSession.Snapshot session, boolean details) {
        try (StateStore state = new StateStore(workspace)) {
            String selectedTaskId = session.currentTaskId() == null
                    ? session.lastTaskId() : session.currentTaskId();
            if (selectedTaskId != null && !isCanonicalId(selectedTaskId)) {
                TaskStore.TaskRecord legacy =
                        recent.resolve(state, session, null).orElse(null);
                if (legacy == null) return List.of("No current or recent task plan.");
                return legacyPlan(state, legacy);
            }
            InteractiveSessionViewModel model=state.viewModels.read(session,view.snapshot());
            if(!details)return viewRenderer.plan(model);
            if(!model.plan().isPresent())return List.of("Plan: "+model.plan().state());
            var projected=model.plan().value();
            CanonicalId taskId=CanonicalId.parse(selectedTaskId);
            CanonicalId revisionId=CanonicalId.parse(projected.revisionId());
            var read=state.planAuthorities.readRevisionDefinition(taskId,revisionId);
            if(read.status()!=com.paicli.state.PlanAuthorityStore.Status.SUCCESS
                    ||read.revision()==null)return List.of("Plan: INTEGRITY_FAILURE");
            List<String> lines=new ArrayList<>();
            lines.addAll(com.paicli.interactive.approval.CanonicalPlanPresentation
                    .advisoryDetails(read.revision()));
            lines.add(2,"Plan lifecycle: "+projected.lifecycle());
            lines.add(3,"Plan approved: "+(projected.approved()?"yes":"no"));
            return List.copyOf(lines);
        } catch (Exception error) {
            return List.of("Plan: UNAVAILABLE");
        }
    }

    @Override
    public List<String> tasks(int limit) {
        int bounded = Math.max(1, Math.min(limit, MAX_TASKS));
        return renderTasks(taskViews("recent", bounded + 1), bounded);
    }

    @Override
    public List<String> tasks(List<String> arguments) {
        String filter = arguments.isEmpty() ? "recent" : arguments.getFirst();
        if (filter.matches("\\d+")) return tasks(Integer.parseInt(filter));
        return renderTasks(taskViews(filter, MAX_TASKS + 1), MAX_TASKS);
    }

    @Override
    public List<InteractiveTaskView> taskViews(String filter, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, MAX_TASKS + 1));
        try (StateStore state = new StateStore(workspace)) {
            List<TaskStore.TaskRecord> records = switch (filter) {
                case "active" -> state.tasks.findByStatus(TaskStore.STATUS_RUNNING, boundedLimit + 1);
                case "failed" -> state.tasks.findByStatus(TaskStore.STATUS_FAILED, boundedLimit + 1);
                case "recent", "recoverable" -> state.tasks.findAll(boundedLimit + 1);
                default -> state.tasks.findById(filter).map(List::of).orElseGet(List::of);
            };
            List<InteractiveTaskView> result = new ArrayList<>();
            for (TaskStore.TaskRecord task : records) {
                InteractiveTaskView item = taskView(state, task);
                if (!"recoverable".equals(filter) || item.resumable()) result.add(item);
                if (result.size() == boundedLimit) break;
            }
            return List.copyOf(result);
        } catch (Exception error) {
            return List.of();
        }
    }

    @Override
    public Optional<InteractiveTaskView> latestTask() {
        try (StateStore state = new StateStore(workspace)) {
            TaskStore.TaskRecord task = recent.latest(state).orElse(null);
            return task == null ? Optional.empty() : Optional.of(taskView(state, task));
        } catch (Exception error) {
            return Optional.empty();
        }
    }
    @Override
    public Optional<InteractiveTaskView> latestTaskWithSnapshots() {
        try (StateStore state = new StateStore(workspace)) {
            for (TaskStore.TaskRecord task : state.tasks.findAll(50)) {
                if (!state.snapshots.findRecentByTask(task.taskId(), 1).isEmpty()) {
                    return Optional.of(taskView(state, task));
                }
            }
            return Optional.empty();
        } catch (Exception error) {
            return Optional.empty();
        }
    }

    @Override
    public List<InteractiveSnapshotView> snapshotViews(String taskId, int limit) {
        if (taskId == null || taskId.isBlank()) return List.of();
        int bounded = Math.max(1, Math.min(limit, 50));
        try (StateStore state = new StateStore(workspace)) {
            if (state.tasks.findById(taskId).isEmpty()) return List.of();
            List<InteractiveSnapshotView> result = new ArrayList<>();
            List<FileSnapshotStore.FileSnapshotRecord> snapshots =
                    state.snapshots.findRecentByTask(taskId, bounded);
            for (int index = 0; index < snapshots.size(); index++) {
                FileSnapshotStore.FileSnapshotRecord snapshot = snapshots.get(index);
                RewindEligibilityService.Eligibility eligibility =
                        rewindEligibility.evaluate(state, snapshot.snapshotId());
                long older = snapshots.subList(index + 1, snapshots.size()).stream()
                        .filter(item -> item.filePath().equals(snapshot.filePath())).count();
                result.add(snapshotView(snapshot, eligibility, older));
            }
            return List.copyOf(result);
        } catch (Exception error) {
            return List.of();
        }
    }

    @Override
    public Optional<InteractiveSnapshotView> snapshotView(String snapshotId) {
        if (snapshotId == null || !snapshotId.matches("[A-Za-z0-9._-]{1,128}")) {
            return Optional.empty();
        }
        try (StateStore state = new StateStore(workspace)) {
            FileSnapshotStore.FileSnapshotRecord snapshot = state.snapshots.findById(snapshotId)
                    .orElse(null);
            if (snapshot == null) return Optional.empty();
            return Optional.of(snapshotView(snapshot,
                    rewindEligibility.evaluate(state, snapshotId), -1));
        } catch (Exception error) {
            return Optional.empty();
        }
    }

    @Override
    public void rewindRecorded(String taskId, String relativePath, String snapshotId) {
        view.snapshotRewound(taskId, relativePath);
    }

    @Override
    public List<String> diff(InteractiveSession.Snapshot session) {
        if (session.currentTaskId() != null) {
            return renderLiveDiffs(view.snapshot().activeDiffs());
        }
        try (StateStore state = new StateStore(workspace)) {
            TaskStore.TaskRecord task = recent.latest(state).orElse(null);
            if (task == null) return List.of("No proposed or applied changes.");
            List<PersistedDiff> facts = persistedDiffs(state, task.taskId());
            return renderPersistedDiffs(facts);
        } catch (Exception error) {
            return List.of("Diff query failed: " + safe(error));
        }
    }

    @Override
    public List<String> report(InteractiveSession.Snapshot session, String selector) {
        String normalizedSelector=selector==null?"":selector.trim();
        boolean transcriptReplay=normalizedSelector.isEmpty()
                ||"latest".equals(normalizedSelector);
        try (StateStore state = new StateStore(workspace)) {
            ReportProjection projection=state.readOnlySnapshot(()->{
                ReportSelection selection=resolveReportSelectionInSnapshot(state,session,selector);
                if(selection.outcome()!=null)return new ReportProjection(null,selection.outcome());
                TaskProjectionKey key=new TaskProjectionKey(selection.identity(),
                        TaskProjectionProfile.HISTORICAL_REPORT);
                return new ReportProjection(state.viewModels
                        .readTaskReportProjectionInSnapshot(session,key),null);
            });
            if(projection.outcome()!=null){
                if(transcriptReplay){
                    TaskStore.TaskRecord legacy=recent.latest(state).orElse(null);
                    if(legacy!=null)return legacyTranscriptReplay(state,legacy);
                }
                return List.of(projection.outcome());
            }
            if(!transcriptReplay){
                List<String> lines=new ArrayList<>(viewRenderer.report(projection.view()));
                appendContinuityStatus(lines,state,
                        projection.view().key().identity().taskId().value());
                var identity=projection.view().key().identity();
                lines.addAll(state.mcpToolCalls.reportForTaskRun(identity.taskId().value(),
                        identity.selectedRunId()==null?null:identity.selectedRunId().value()));
                lines.addAll(state.skillLoads.reportForTaskRun(identity.taskId().value(),
                        identity.selectedRunId()==null?null:identity.selectedRunId().value()));
                return List.copyOf(lines);
            }
            List<String> lines=new ArrayList<>();
            SelectedTaskIdentity identity=projection.view().key().identity();
            if(identity!=null){
                var result=identity.selectedRunId()==null
                        ?java.util.Optional.<com.paicli.state.UserResultStore.Result>empty()
                        :state.userResults.find(identity.taskId(),identity.selectedRunId());
                appendTranscriptReplay(lines,state,identity,
                        result.map(com.paicli.state.UserResultStore.Result::content).orElse(null));
                lines.addAll(state.mcpToolCalls.reportForTaskRun(identity.taskId().value(),
                        identity.selectedRunId()==null?null:identity.selectedRunId().value()));
                lines.addAll(state.skillLoads.reportForTaskRun(identity.taskId().value(),
                        identity.selectedRunId()==null?null:identity.selectedRunId().value()));
            }
            return List.copyOf(lines);
        } catch (Exception error) {
            return List.of("Report query failed: REPORT_PROJECTION_UNAVAILABLE");
        }
    }

    private static List<String> legacyTranscriptReplay(StateStore state,
            TaskStore.TaskRecord task)throws Exception{
        List<String> lines=new ArrayList<>();
        lines.add("──────────────── PaiCLI Report ─────────────────────────");
        lines.add("");lines.add("  Task input");
        for(String line:task.goal().lines().limit(8).toList())lines.add("  "+bound(line,512));
        for(AgentMessage message:state.events.findMessages(task.taskId())){
            if(message.type()==AgentMessage.MessageType.FINAL&&message.content()!=null
                    &&!message.content().isBlank()){
                lines.add("");lines.add("  [Update] "+bound(message.content(),512));
            }
        }
        lines.add("");lines.add("  [Task] "+friendlyStatus(task.status()));
        lines.add("");lines.add("────────────────────────────────────────────────────────");
        return List.copyOf(lines);
    }

    private static void appendTranscriptReplay(List<String> lines,StateStore state,
            SelectedTaskIdentity identity,String finalContent)throws Exception{
        TaskStore.TaskRecord task=state.tasks.findById(identity.taskId().value()).orElse(null);
        lines.add("──────────────── PaiCLI Report ─────────────────────────");
        if(task!=null){
            lines.add("");lines.add("  Task input");
            for(String line:task.goal().lines().limit(8).toList())
                lines.add("  "+bound(line,512));
        }
        var active=state.planAuthorities.readActive(identity.taskId());
        if(active.status()==com.paicli.state.PlanAuthorityStore.Status.SUCCESS){
            lines.add("");lines.add("  [Plan] "+active.plan().revision().steps().size()+" steps");
            int number=1;
            for(var step:active.plan().revision().steps())
                lines.add("  │ "+number++ + ". "+bound(step.title(),256));
        }
        boolean approved=state.approvals.findByTask(identity.taskId().value()).stream()
                .anyMatch(item->("advisory_plan_confirmation".equals(item.toolName())
                                ||"structured_plan_approval".equals(item.toolName()))
                        &&"ALLOW".equals(item.decision()));
        if(approved)lines.add("  [Approval] Advisory Plan confirmed; Tool authority remains governed by runtime safety.");
        java.util.Map<String,ToolCallStore.ToolCallRecord> toolCalls=new java.util.LinkedHashMap<>();
        for(var call:state.toolCalls.findByTask(identity.taskId().value()))
            if(call.toolCallId()!=null)toolCalls.put(call.toolCallId(),call);
        java.util.Set<String> renderedCalls=new java.util.HashSet<>();
        for(AgentMessage message:state.events.findMessages(identity.taskId().value())){
            if(message.type()!=AgentMessage.MessageType.ACTION
                    &&message.type()!=AgentMessage.MessageType.OBSERVATION
                    &&message.type()!=AgentMessage.MessageType.FINAL)continue;
            if(message.content()==null||message.content().isBlank())continue;
            if(message.type()==AgentMessage.MessageType.FINAL
                    &&java.util.Objects.equals(message.content(),finalContent))continue;
            if(message.type()==AgentMessage.MessageType.ACTION&&message.toolCallJson()!=null){
                JsonNode call=JSON.readTree(message.toolCallJson());
                String name=call.path("function").path("name").asText("tool");
                lines.add("");lines.add("  • Running "+bound(name,128));
                JsonNode args=JSON.readTree(call.path("function").path("arguments").asText("{}"));
                if(args.has("path"))lines.add("    │ Target      "+bound(args.path("path").asText(),256));
                lines.add("    │ Arguments   "+bound(args.toString(),384));
                continue;
            }
            if(message.type()==AgentMessage.MessageType.OBSERVATION){
                if(message.toolCallId()==null||!renderedCalls.add(message.toolCallId()))continue;
                ToolCallStore.ToolCallRecord call=toolCalls.get(message.toolCallId());
                if(call==null){lines.add("    └─ Tool result recorded");continue;}
                appendToolResultReplay(lines,message,call);
                continue;
            }
            String visiblePrefix=message.type()==AgentMessage.MessageType.FINAL
                    ?"  [Update] ":"  • ";
            List<String> contentLines=message.content().lines().limit(8).toList();
            for(int index=0;index<contentLines.size();index++)
                lines.add((index==0?visiblePrefix:"      ")+bound(contentLines.get(index),512));
            long remaining=Math.max(0,message.content().lines().count()-contentLines.size());
            if(remaining>0)lines.add("      … "+remaining+" more line(s)");
        }
        if(finalContent!=null&&!finalContent.isBlank()){
            lines.add("");lines.add("  [Result]");lines.add("");
            for(String line:finalContent.lines().limit(80).toList())
                lines.add("  "+bound(line,512));
            if(finalContent.lines().count()>80)lines.add("  … result truncated");
        }
        if(task!=null){
            lines.add("");lines.add("  [Task] "+friendlyStatus(task.status()));
        }
        lines.add("");lines.add("────────────────────────────────────────────────────────");
    }

    static void appendToolResultReplay(List<String> lines,AgentMessage message,
            ToolCallStore.ToolCallRecord call){
        ToolFactProjection.SafeReplayProjection safe=
                ToolFactProjection.authenticatedReplayProjection(message.content(),
                        message.toolCallId(),call.toolName(),call.output());
        String visibleOutput=safe==null?call.output():safe.visibleContent();
        List<String> output=visibleOutput==null?List.of():visibleOutput.lines()
                .filter(line->!line.isBlank()).limit(8).toList();
        int visibleCount=safe==null?output.size():safe.facts().visibleEntryCount();
        lines.add("    ├─ "+(call.success()?"Succeeded":"Failed")+" · "
                +visibleCount+" visible line(s)");
        for(String item:output)lines.add("    │  "+bound(item,512));
        long remaining=visibleOutput==null?0:Math.max(0,
                visibleOutput.lines().filter(line->!line.isBlank()).count()-output.size());
        if(remaining>0)lines.add("    │  … "+remaining+" more");
        if(safe!=null&&safe.facts().excludedControlCount()>0){
            lines.add("    ├─ Excluded controls: "+safe.facts().excludedControlCount());
            for(String control:safe.facts().excludedControls())
                lines.add("    │  "+bound(control,128));
        }
        lines.add("    └─ Result persisted");
    }

    private static String friendlyStatus(String status){
        if(status==null)return "Status unavailable";
        return switch(status){
            case TaskStore.STATUS_DONE->"Completed";
            case TaskStore.STATUS_FAILED->"Failed";
            case TaskStore.STATUS_CANCELLED,TaskStore.STATUS_CANCELLED_UNKNOWN_OUTCOME->"Cancelled";
            default->status.toLowerCase(java.util.Locale.ROOT).replace('_',' ');
        };
    }

    private ReportSelection resolveReportSelectionInSnapshot(StateStore state,
            InteractiveSession.Snapshot session,String selector)throws Exception{
        String normalized=selector==null?"":selector.trim();
        if("previous".equals(normalized))
            return new ReportSelection(null,"Report selector unsupported: previous.");
        boolean current="current".equals(normalized)||normalized.isEmpty()
                &&(session.currentTaskId()!=null||session.binding()!=null
                        &&session.binding().headTaskId()!=null);
        SelectedTaskIdentity identity=null;
        if(current){
            identity=state.viewModels.resolveCurrentReportIdentityInSnapshot(session);
            if(identity==null)return new ReportSelection(null,"No active task report.");
        }else if("latest".equals(normalized)||normalized.isEmpty()){
            identity=state.viewModels.resolveLatestReportIdentityInSnapshot(session);
            if(identity==null)return new ReportSelection(null,
                    "No current or recent task report.");
        }else{
            CanonicalId explicit;
            try{explicit=CanonicalId.parse(normalized);}
            catch(IllegalArgumentException invalid){
                return new ReportSelection(null,"Report selector invalid.");
            }
            if(explicit.family()!=com.paicli.identity.EntityFamily.TASK)
                return new ReportSelection(null,"Report selector invalid.");
            var durableTask=state.r1aDurable.findTask(explicit).orElse(null);
            if(durableTask==null)return new ReportSelection(null,"Report selector not found.");
            var thread=state.r1aDurable.findWorkThread(durableTask.threadId()).orElse(null);
            if(thread==null)return new ReportSelection(null,
                    "Report authority unavailable.");
            if(!SavedSessionService.workspaceIdentity(workspace).equals(thread.workspaceIdentity()))
                return new ReportSelection(null,"Report selector crosses workspace boundary.");
            identity=state.viewModels.resolveTaskIdentityInSnapshot(session,explicit,
                    SelectedTaskIdentity.SelectorKind.EXPLICIT,"explicit");
        }
        if(identity.relation()==SelectedTaskIdentity.Relation.CROSS_THREAD_INVALID)
            return new ReportSelection(identity,"Report selector scope is invalid.");
        return new ReportSelection(identity,null);
    }

    private List<PersistedDiff> persistedDiffs(StateStore state, String taskId)
            throws Exception {
        List<InteractiveProposalStore.Record> proposals =
                state.interactiveProposals.findByTask(taskId, MAX_PROPOSALS);
        List<PersistedDiff> result = new ArrayList<>();
        for (InteractiveProposalStore.Record proposal : proposals) {
            FileSnapshotStore.FileSnapshotRecord snapshot = proposal.snapshotId() == null
                    ? null : state.snapshots.findById(proposal.snapshotId()).orElse(null);
            String effectiveStatus = snapshot != null
                    && FileSnapshotStore.STATUS_RESTORED.equals(snapshot.status())
                    ? InteractiveProposalStore.REWOUND : proposal.status();
            boolean current = InteractiveProposalStore.APPLIED.equals(effectiveStatus)
                    && snapshot != null
                    && FileSnapshotStore.STATUS_APPLIED.equals(snapshot.status())
                    && currentHashMatches(snapshot);
            result.add(new PersistedDiff(proposal.relativePath(), proposal.additions(),
                    proposal.deletions(), effectiveStatus, proposal.snapshotId(),
                    proposal.truncated(), proposal.redacted(), current));
        }
        if (!result.isEmpty()) return List.copyOf(result);

        // Upgrade compatibility for tasks completed before bounded proposal persistence existed.
        for (FileSnapshotStore.FileSnapshotRecord snapshot :
                state.snapshots.findRecentByTask(taskId, 50)) {
            RewindEligibilityService.Eligibility eligibility =
                    rewindEligibility.evaluate(state, snapshot.snapshotId());
            String status = eligibility.alreadyRewound()
                    ? InteractiveProposalStore.REWOUND
                    : eligibility.rewindable() ? InteractiveProposalStore.APPLIED
                    : eligibility.code();
            boolean current = InteractiveProposalStore.APPLIED.equals(status)
                    && eligibility.currentHashMatches();
            result.add(new PersistedDiff(snapshot.filePath(), 0, 0, status,
                    snapshot.snapshotId(), false, false, current));
        }
        return List.copyOf(result);
    }

    private HistoricalCodeFacts historicalCodeFacts(StateStore state,
            TaskStore.TaskRecord task, CodingRunStore.CodingRunRecord run) throws Exception {
        InteractiveTaskFactStore.Record exact = state.interactiveTaskFacts
                .findByTask(task.taskId()).orElse(null);
        if (exact != null && run != null && run.runId().equals(exact.runId())
                && task.taskId().equals(exact.taskId())) {
            return new HistoricalCodeFacts(exact.codeIntelligenceStatus(),
                    Integer.toString(exact.indexedJavaFiles()),
                    Integer.toString(exact.contextItemCount()));
        }
        if (run == null) return HistoricalCodeFacts.unknownLegacy();
        List<CodeSearchAuditStore.CodeSearchAuditRecord> audits =
                state.codeSearchAudits.findByTaskAndRun(task.taskId(), run.runId(), 100);
        if (audits.isEmpty()) return HistoricalCodeFacts.unknownLegacy();
        boolean succeeded = audits.stream().anyMatch(item ->
                CodeSearchAuditStore.STATUS_SUCCEEDED.equals(item.status()));
        boolean failed = audits.stream().anyMatch(item ->
                CodeSearchAuditStore.STATUS_FAILED.equals(item.status()));
        int contexts = audits.stream()
                .filter(item -> CodeSearchAuditStore.TYPE_CONTEXT.equals(item.queryType()))
                .filter(item -> CodeSearchAuditStore.STATUS_SUCCEEDED.equals(item.status()))
                .mapToInt(CodeSearchAuditStore.CodeSearchAuditRecord::resultCount).sum();
        return new HistoricalCodeFacts(succeeded ? "USED" : failed ? "FAILED" : "UNKNOWN_LEGACY",
                "unknown", Integer.toString(contexts));
    }

    private List<String> renderPersistedDiffs(List<PersistedDiff> facts) {
        if (facts.isEmpty()) return List.of("No proposed or applied changes.");
        List<String> lines = new ArrayList<>();
        List<PersistedDiff> current = facts.stream().filter(PersistedDiff::current).toList();
        if (current.isEmpty()) lines.add("No current effective changes.");
        current.forEach(fact -> {
            lines.add(fact.status() + " " + fact.relativePath()
                    + stats(fact.additions(), fact.deletions()));
            if (fact.snapshotId() != null) lines.add("Snapshot: present");
        });
        List<PersistedDiff> pending = facts.stream()
                .filter(fact -> InteractiveProposalStore.PROPOSED.equals(fact.status())).toList();
        if (!pending.isEmpty()) {
            lines.add("Pending proposals:");
            pending.stream().limit(50).forEach(fact -> lines.add(
                    fact.status() + " " + fact.relativePath()
                            + stats(fact.additions(), fact.deletions())));
        }
        List<PersistedDiff> historical = facts.stream()
                .filter(fact -> !fact.current()
                        && !InteractiveProposalStore.PROPOSED.equals(fact.status())).toList();
        if (!historical.isEmpty()) {
            lines.add("Historical changes:");
            historical.stream().limit(50).forEach(fact -> lines.add(
                    fact.status() + " (historical) " + fact.relativePath()
                            + stats(fact.additions(), fact.deletions())));
        }
        if (facts.size() > 50) lines.add("[output truncated]");
        return List.copyOf(lines);
    }

    private List<String> renderLiveDiffs(List<InteractiveRuntimeView.DiffFact> facts) {
        if (facts.isEmpty()) return List.of("No proposed or applied changes.");
        List<String> lines = new ArrayList<>();
        List<InteractiveRuntimeView.DiffFact> current =
                facts.stream().filter(InteractiveRuntimeView.DiffFact::current).toList();
        if (current.isEmpty()) lines.add("No current effective changes.");
        for (InteractiveRuntimeView.DiffFact fact : current) {
            lines.add(fact.status() + " " + fact.relativePath()
                    + stats(fact.additions(), fact.deletions()));
            fact.boundedLines().stream().limit(80).forEach(line -> {
                if (lines.size() < 200) lines.add(bound(line, 512));
            });
            if (fact.truncated()) lines.add("[diff truncated]");
            if (fact.redacted()) lines.add("[diff redacted]");
            if (fact.snapshotReference() != null) lines.add("Snapshot: present");
        }
        List<InteractiveRuntimeView.DiffFact> pending = facts.stream()
                .filter(fact -> InteractiveProposalStore.PROPOSED.equals(fact.status())).toList();
        if (!pending.isEmpty()) {
            lines.add("Pending proposals:");
            pending.stream().limit(50).forEach(fact -> lines.add(
                    fact.status() + " " + fact.relativePath()
                            + stats(fact.additions(), fact.deletions())));
        }
        List<InteractiveRuntimeView.DiffFact> historical = facts.stream()
                .filter(fact -> !fact.current()
                        && !InteractiveProposalStore.PROPOSED.equals(fact.status())).toList();
        if (!historical.isEmpty()) {
            lines.add("Historical changes:");
            historical.stream().limit(50).forEach(fact -> lines.add(
                    fact.status() + " (historical) " + fact.relativePath()
                            + stats(fact.additions(), fact.deletions())));
        }
        return List.copyOf(lines);
    }

    private InteractiveTaskView taskView(StateStore state, TaskStore.TaskRecord task)
            throws Exception {
        List<CodingRunStore.CodingRunRecord> runs =
                state.codingRuns.findRecentByTask(task.taskId(), 1);
        CodingRunStore.CodingRunRecord run = runs.isEmpty() ? null : runs.getFirst();
        VerificationStore.VerificationRecord verification =
                latestVerification(state, task.taskId());
        boolean resumable = false;
        try {
            R1AuthorityPeek.AuthorityPeek<R1AuthorityPeek.RecoveryPeek> recovery =
                    state.authorityPeeks.peekRecoveryAuthority(
                            CanonicalId.parse(task.taskId()), Instant.now());
            resumable = recovery.status() == R1AuthorityPeek.PeekStatus.PRESENT
                    && recovery.value().eligible();
        } catch (IllegalArgumentException ignored) {
            resumable = legacyResumable(state, task);
        }
        return new InteractiveTaskView(task.taskId(), shortId(task.taskId()), task.updatedAt(),
                bound(task.goal(), 120), bound(task.provider(), 64), bound(task.model(), 96),
                task.status(), run == null ? "not available" : run.phase(), resumable,
                state.snapshots.countByTask(task.taskId()),
                verification == null ? "NOT_RUN" : verification.status());
    }

    private List<String> legacyPlan(StateStore state, TaskStore.TaskRecord task)
            throws Exception {
        List<CodingRunStore.CodingRunRecord> runs =
                state.codingRuns.findRecentByTask(task.taskId(), 1);
        if (runs.isEmpty()) return List.of("No persisted plan.");
        TaskPlanStore.TaskPlanRecord plan =
                state.plans.findByRun(runs.getFirst().runId()).orElse(null);
        if (plan == null) return List.of("No persisted plan.");
        List<String> lines = new ArrayList<>();
        lines.add("Plan status: " + plan.status());
        for (String line : plan.planText().split("\\R")) {
            String clean = bound(line, 256);
            if (!clean.isBlank()) lines.add(clean);
            if (lines.size() == 13) break;
        }
        return List.copyOf(lines);
    }

    private boolean legacyResumable(StateStore state, TaskStore.TaskRecord task)
            throws Exception {
        boolean hasResumeEvidence = state.checkpoints.loadLatest(task.taskId()).isPresent()
                || state.events.countIterations(task.taskId()) > 0;
        boolean resumableStatus = !TaskStore.isTerminal(task.status())
                || TaskStore.STATUS_FAILED.equals(task.status());
        return resumableStatus && hasResumeEvidence;
    }

    private static boolean isCanonicalId(String value) {
        try {
            CanonicalId.parse(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static void appendRuntimeStatus(List<String> lines,
            InteractiveRuntimeView.Snapshot live, List<String> arguments) {
        if (!isDetailsStatus(arguments)) return;
        lines.add("Current session runtime phase: " + live.phase());
        lines.add("Current session Code Intelligence: "
                + live.codeIntelligenceStatus());
        lines.add("Current session indexed Java files: " + live.indexedJavaFiles());
        lines.add("Current session context items: " + live.codeContextItems());
    }

    private void appendPersistedStatus(List<String> lines, StateStore state)
            throws Exception {
        TaskStore.TaskRecord task = recent.latest(state).orElse(null);
        if (task == null) {
            lines.add("Latest persisted task: none");
            return;
        }
        InteractiveTaskView summary = taskView(state, task);
        lines.add("Latest persisted task: " + summary.safeTaskId());
        lines.add("Latest persisted status: " + summary.status());
        lines.add("Latest persisted Provider / Model: "
                + summary.provider() + " / " + summary.model());
        lines.add("Latest persisted verification: " + summary.verificationStatus());
        VerificationStore.VerificationRecord verification =
                latestVerification(state, task.taskId());
        lines.add("Current workspace verification: "
                + currentWorkspaceVerification(state, task.taskId(), verification));
        lines.add("Latest persisted snapshots: " + summary.snapshotCount());
        lines.add("Latest persisted updated: " + summary.updatedAt());
        List<CodingRunStore.CodingRunRecord> runs =
                state.codingRuns.findRecentByTask(task.taskId(), 1);
        HistoricalCodeFacts code = historicalCodeFacts(state, task,
                runs.isEmpty() ? null : runs.getFirst());
        lines.add("Latest persisted Code Intelligence: " + code.status());
        appendContinuityStatus(lines,state,task.taskId());
    }

    private static void appendContinuityStatus(List<String> lines, StateStore state,
            String taskId) throws Exception {
        var segments=state.taskContinuity.findByTask(taskId);
        if(segments.isEmpty()){
            lines.add("Continuity: DISABLED");
            return;
        }
        lines.add("Continuity: ENABLED_EXPLICITLY");
        lines.add("Continuity policy identity: "+segments.getFirst().policyHash());
        lines.add("Continuity segments used: "+segments.size());
        lines.add("Automatic continuations: "+Math.max(0,segments.size()-1));
    }

    private static boolean isDetailsStatus(List<String> arguments) {
        String detail = arguments == null || arguments.isEmpty()
                ? "summary" : arguments.getFirst();
        return "--details".equals(detail) || "details".equals(detail);
    }

    private static String applicationVersion() {
        String value = StateStoreInteractiveQueryFacade.class.getPackage()
                .getImplementationVersion();
        return value == null || value.isBlank() ? "1.2.0" : value;
    }

    private VerificationStore.VerificationRecord latestVerification(
            StateStore state, String taskId) throws Exception {
        return state.verifications.findLatestByTask(taskId).orElse(null);
    }

    private String currentWorkspaceVerification(StateStore state, String taskId,
            VerificationStore.VerificationRecord verification) throws Exception {
        String completedAt = verification == null ? null : verification.completedAt();
        boolean stale = state.snapshots.findRecentByTask(taskId, 50).stream()
                .filter(item -> FileSnapshotStore.STATUS_RESTORED.equals(item.status()))
                .map(FileSnapshotStore.FileSnapshotRecord::restoredAt)
                .filter(value -> value != null && !value.isBlank())
                .anyMatch(restoredAt -> completedAt == null
                        || restoredAt.compareTo(completedAt) >= 0);
        if (stale) return "STALE_AFTER_REWIND";
        return verification == null ? "NOT_RUN" : verification.status();
    }

    private List<String> renderTasks(List<InteractiveTaskView> tasks, int displayLimit) {
        if (tasks.isEmpty()) return List.of("No matching persisted tasks.");
        List<String> lines = new ArrayList<>();
        int index = 1;
        for (InteractiveTaskView task : tasks.stream().limit(displayLimit).toList()) {
            lines.add(index++ + ". " + task.safeTaskId() + "  " + task.status()
                    + "/" + task.phase() + "  " + task.provider() + "/"
                    + (task.model() == null || task.model().isBlank() ? "none" : task.model()));
            lines.add("   " + task.updatedAt() + "  " + task.summary());
            lines.add("   resume=" + (task.resumable() ? "yes" : "no")
                    + " snapshots=" + task.snapshotCount()
                    + " verification=" + task.verificationStatus());
        }
        if (tasks.size() > displayLimit) lines.add("[truncated]");
        return List.copyOf(lines);
    }

    private InteractiveSnapshotView snapshotView(FileSnapshotStore.FileSnapshotRecord snapshot,
            RewindEligibilityService.Eligibility eligibility, long older) {
        return new InteractiveSnapshotView(
                snapshot.snapshotId(), shortId(snapshot.snapshotId()), snapshot.taskId(),
                snapshot.createdAt(), bound(snapshot.filePath(), 240), reason(snapshot, older),
                eligibility.currentHashMatches(), eligibility.rewindable(), snapshot.status(),
                restoresTo(snapshot), eligibility.code());
    }

    private boolean currentHashMatches(FileSnapshotStore.FileSnapshotRecord snapshot) {
        if (!FileSnapshotStore.STATUS_APPLIED.equals(snapshot.status())
                || snapshot.sha256After() == null) return false;
        try {
            Path target = workspace.resolve(snapshot.filePath().replace('\\', '/')).normalize();
            if (!target.startsWith(workspace)) return false;
            new SafetyPolicy(workspace, 1, 1024).validatePath(target);
            return Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    && snapshot.sha256After().equals(sha256(target));
        } catch (Exception error) {
            return false;
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            try (InputStream input = Files.newInputStream(file)) {
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String reason(FileSnapshotStore.FileSnapshotRecord snapshot, long older) {
        if (FileSnapshotStore.OPERATION_CREATE.equals(snapshot.operation())) {
            return "before file creation (ABSENT)";
        }
        if (older == 0) return "before first edit";
        if (older > 0) return "before later edit #" + (older + 1);
        return "before edit";
    }

    private static String restoresTo(FileSnapshotStore.FileSnapshotRecord snapshot) {
        if (FileSnapshotStore.OPERATION_CREATE.equals(snapshot.operation())) {
            return "ABSENT (owned new file will be deleted)";
        }
        String hash = snapshot.sha256Before();
        return hash == null ? "previous content" : "content hash " + hash.substring(0, 12);
    }

    private static long proposalCount(List<InteractiveProposalStore.Record> proposals,
            List<ApprovalStore.ApprovalRecord> approvals) {
        if (!proposals.isEmpty()) return proposals.size();
        return approvals.stream().filter(item -> item.reason() != null
                && "write_file".equals(item.toolName())
                && item.reason().startsWith("interactive user")).count();
    }

    private static long statusCount(List<InteractiveProposalStore.Record> proposals,
            String status) {
        return proposals.stream().filter(item -> status.equals(item.status())).count();
    }

    private static String cancellationFact(TaskStore.TaskRecord task) {
        if (TaskStore.STATUS_CANCELLED.equals(task.status())) return "user requested";
        if (TaskStore.STATUS_CANCELLED_UNKNOWN_OUTCOME.equals(task.status())) {
            return "user requested; external outcome unknown";
        }
        return "none";
    }

    private static boolean isCancellation(String status) {
        return TaskStore.STATUS_CANCELLED.equals(status)
                || TaskStore.STATUS_CANCELLED_UNKNOWN_OUTCOME.equals(status);
    }

    private static String stats(int additions, int deletions) {
        return additions == 0 && deletions == 0 ? "" : " +" + additions + " -" + deletions;
    }

    private static String shortId(String value) {
        return value.length() <= 16 ? value
                : value.substring(0, 7) + "…" + value.substring(value.length() - 8);
    }

    private static String safe(Throwable error) {
        return bound(error.getMessage() == null ? "query failed" : error.getMessage(), 256);
    }

    private static String bound(String value, int max) {
        if (value == null) return "none";
        String clean = SANITIZER.sanitize(value);
        if (clean == null) return "none";
        clean = WINDOWS_ABSOLUTE_PATH.matcher(clean)
                .replaceAll("<redacted-absolute-path>");
        clean = UNIX_ABSOLUTE_PATH.matcher(clean)
                .replaceAll("<redacted-absolute-path>");
        clean = clean.replaceAll(
                "(?s)\\.\\.\\.\\[truncated; original_bytes=\\d+\\]", "…");
        return clean.length() <= max ? clean
                : clean.substring(0, Math.max(0, max - 2)) + " …";
    }

    private record PersistedDiff(String relativePath, int additions, int deletions,
            String status, String snapshotId, boolean truncated, boolean redacted,
            boolean current) { }

    private record HistoricalCodeFacts(String status, String indexedJavaFiles,
            String contextItems) {
        private static HistoricalCodeFacts unknownLegacy() {
            return new HistoricalCodeFacts("UNKNOWN_LEGACY", "unknown", "unknown");
        }
    }
    private record ReportSelection(SelectedTaskIdentity identity,String outcome){ }
    private record ReportProjection(TaskReportView view,String outcome){ }
}
