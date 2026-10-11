package com.paicli.interactive.command;

import com.paicli.context.CompactTargetModels.*;
import com.paicli.context.ContextModels.OperationKind;
import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.state.ContextGenerationStore;
import com.paicli.state.ContextReadOnlyStatusQuery;
import com.paicli.state.R1aDurableModels.ExecutionAuthoritySnapshot;
import com.paicli.state.StateStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/** Shared interactive/non-interactive compact status, preview and identity-closed apply service. */
public final class InteractiveCompactionCommandService implements InteractiveCompactionCommandPort {
    private final Path workspace;private final ContextReadOnlyStatusQuery readOnly;
    private final Supplier<ExecutionAuthoritySnapshot> authoritySupplier;
    private final java.util.function.BiConsumer<com.paicli.context.ContextModels.MaintenanceAuthority,
            com.paicli.context.CompactionCoordinator.Outcome> manualEvents;
    public InteractiveCompactionCommandService(Path workspace){this(workspace,()->null,(authority,outcome)->{});}
    public InteractiveCompactionCommandService(Path workspace,
            Supplier<ExecutionAuthoritySnapshot> authoritySupplier){
        this(workspace,authoritySupplier,(authority,outcome)->{});
    }
    public InteractiveCompactionCommandService(Path workspace,
            Supplier<ExecutionAuthoritySnapshot> authoritySupplier,
            java.util.function.BiConsumer<com.paicli.context.ContextModels.MaintenanceAuthority,
                    com.paicli.context.CompactionCoordinator.Outcome> manualEvents){
        this.workspace=workspace.toAbsolutePath().normalize();
        this.readOnly=new ContextReadOnlyStatusQuery(this.workspace);
        this.authoritySupplier=Objects.requireNonNull(authoritySupplier);
        this.manualEvents=Objects.requireNonNull(manualEvents);
    }

    @Override public ShellCommandResult compact(List<String> arguments,InteractiveSession.Snapshot session){
        if(arguments==null)return invalid("usage: /compact [status]");
        if(arguments.size()==1&&"status".equals(arguments.getFirst()))
            return execute(new Parsed(Action.STATUS,null,null,null,null,null),session,false);
        if(!arguments.isEmpty())return invalid("usage: /compact [status]");
        return manual(session);
    }

    private ShellCommandResult manual(InteractiveSession.Snapshot session){
        if(session==null||session.currentTaskId()!=null)return result("COMPACTION_ACTIVE_RUN_REJECTED",
                "Manual compaction requires the Ready shell with no active Run.");
        var binding=session.binding();
        if(binding==null||binding.state()!=com.paicli.interactive.session.SessionBinding.State.BOUND_TERMINAL
                ||binding.threadId()==null||binding.headTaskId()==null
                ||session.lastTaskId()==null||!session.lastTaskId().equals(binding.headTaskId().value()))
            return result("COMPACTION_NOT_APPLICABLE",
                    "Bind the terminal Thread Head completed by this shell before compacting.");
        try(StateStore state=new StateStore(workspace)){
            var authority=state.contexts.captureMaintenanceAuthority(session.sessionId(),
                    binding.threadId().value(),binding.headTaskId().value(),null);
            var outcome=new com.paicli.context.CompactionCoordinator(state).manualCompact(authority);
            boolean eventDelivered=true;
            try{manualEvents.accept(authority,outcome);}
            catch(RuntimeException presentationFailure){eventDelivered=false;}
            return result("COMPACTION_READY",eventDelivered?"Compaction committed.":
                            "• "+outcome.safeSummary(),
                    "Task: "+shortId(authority.taskId())+" Run: "+shortId(authority.runId())
                            +" Operations: "+authority.targets().stream().map(target->target.operation().name())
                                    .collect(java.util.stream.Collectors.joining(",")),
                    "Pointer version: "+outcome.pointerVersion());
        }catch(ContextGenerationStore.ContextFailure error){return result(error.getMessage(),safe(error.getMessage()));}
        catch(Exception error){return result("COMPACTION_FAILED","Compaction operation failed safely.");}
    }

    public ShellCommandResult compactNonInteractive(List<String> arguments){
        Parsed parsed=parse(arguments,true);if(parsed.error()!=null)return invalid(parsed.error());
        return execute(parsed,null,true);
    }

    private ShellCommandResult execute(Parsed parsed,InteractiveSession.Snapshot session,
            boolean nonInteractive){
        if(parsed.action()==Action.STATUS){
            String currentTask=session==null?null:session.currentTaskId();
            String currentRun=session==null?null:session.currentRunId();
            String taskFilter=nonInteractive?parsed.taskId():currentTask;
            var status=readOnly.read(taskFilter,parsed.runId(),currentTask,currentRun);
            return ShellCommandResult.continueWith(status.available()?"COMPACTION_STATUS":"CONTEXT_UNAVAILABLE",
                    status.lines());
        }
        ExecutionAuthoritySnapshot authority;
        try{
            authority=authoritySupplier.get();
        }catch(RuntimeException unavailable){
            return result("CONTEXT_OWNERSHIP_UNCERTAIN",
                    "Compaction mutation ownership is unavailable.");
        }
        if(authority==null)return result("CONTEXT_OWNERSHIP_REQUIRED",
                "Compaction mutation requires current execution ownership.");
        String taskId=nonInteractive?parsed.taskId():session.currentTaskId();
        String runId=nonInteractive?parsed.runId():session.currentRunId();
        String sessionId=nonInteractive?null:session.sessionId();
        TargetRelation relation=nonInteractive?TargetRelation.EXPLICIT_TARGET:
                TargetRelation.CURRENT_SESSION_RUN;
        try(StateStore state=new StateStore(workspace)){
            if(parsed.action()==Action.APPLY){
                if(!nonInteractive&&(taskId==null||runId==null))return result("COMPACTION_NOT_APPLICABLE",
                        "A current Task and Run binding is required.");
                CompactTargetResolution resolved=state.readOnlySnapshot(()->state.contexts
                        .resolveCandidateTarget(parsed.contextId(),taskId,runId,sessionId,relation));
                if(resolved.status()!=ResolutionStatus.PRESENT)return resolutionResult(resolved);
                CompactTargetIdentity target=resolved.identity();
                var pointer=state.contexts.pointer(target.taskId(),target.runId(),target.operation())
                        .orElseThrow(()->new ContextGenerationStore.ContextFailure("COMPACTION_STALE_SOURCE"));
                var safe=state.contexts.safePoint(pointer,authority);
                if(!safe.safe())return result(safe.code(),safe.code());
                var applied=state.contexts.applyCandidate(parsed.contextId(),target,authority);
                return result("COMPACTION_READY","Active context: "+shortId(applied.contextGenerationId()),
                        "Task: "+shortId(target.taskId())+" Run: "+shortId(target.runId())
                                +" Operation: "+target.operation(),
                        "Pointer version: "+applied.pointerVersion());
            }
            if(taskId==null||runId==null)return result("COMPACTION_NOT_APPLICABLE",
                    "A current Task and Run binding is required.");
            CompactTargetResolution resolved=state.readOnlySnapshot(()->state.contexts.resolveTarget(
                    taskId,runId,parsed.operation(),sessionId,relation));
            if(resolved.status()!=ResolutionStatus.PRESENT)return resolutionResult(resolved);
            CompactTargetIdentity target=resolved.identity();
            var pointer=new ContextGenerationStore.Pointer(target.taskId(),target.runId(),target.operation(),
                    target.sourceContextGenerationId(),target.sourcePointerVersion());
            var safe=state.contexts.safePoint(pointer,authority);
            if(!safe.safe())return result(safe.code(),safe.code());
            var candidate=state.contexts.createOrReuseCandidate(target,
                    com.paicli.context.DeterministicContextCompiler.COMPILER_VERSION,
                    com.paicli.context.DeterministicContextCompiler.POLICY_VERSION,authority);
            if(candidate.lifecycle()==com.paicli.context.ContextModels.Lifecycle.PREPARING)
                return result("COMPACTION_IN_PROGRESS","Candidate: "+shortId(candidate.contextId()));
            return result("COMPACTION_PREPARED","Candidate: "+shortId(candidate.contextId()),
                    "Task: "+shortId(target.taskId())+" Run: "+shortId(target.runId())
                            +" Operation: "+target.operation(),
                    "Source: "+shortId(candidate.sourceContextId()),
                    candidate.previewSummary()==null?"Preview ready.":candidate.previewSummary());
        }catch(ContextGenerationStore.ContextFailure error){return result(error.getMessage(),safe(error.getMessage()));}
        catch(Exception error){return result("COMPACTION_FAILED","Compaction operation failed safely.");}
    }

    private static ShellCommandResult resolutionResult(CompactTargetResolution resolution){
        String code=switch(resolution.status()){
            case AMBIGUOUS->"COMPACT_TARGET_AMBIGUOUS";
            case NOT_FOUND->"COMPACT_TARGET_NOT_FOUND";
            case NOT_APPLICABLE->"COMPACTION_NOT_APPLICABLE";
            default->resolution.status().name();
        };
        var lines=new ArrayList<String>();lines.add(code);
        for(CandidateSummary candidate:resolution.candidates())lines.add("candidate task="
                +candidate.taskId()+" run="+candidate.runId()+" operation="+candidate.operation()
                +" source="+candidate.sourceContextGenerationId());
        return ShellCommandResult.continueWith(code,List.copyOf(lines));
    }

    private static Parsed parse(List<String> arguments,boolean nonInteractive){
        if(arguments==null)return Parsed.error("invalid compact arguments");
        if(arguments.isEmpty())return nonInteractive?Parsed.error(nonInteractiveUsage()):
                new Parsed(Action.PREVIEW,null,null,null,null,null);
        String action=arguments.getFirst();
        if("apply".equals(action)){
            if(arguments.size()!=2||!contextId(arguments.get(1)))return Parsed.error(
                    nonInteractive?nonInteractiveUsage():"usage: /compact apply <ctx_id>");
            return new Parsed(Action.APPLY,null,null,null,arguments.get(1),null);
        }
        if("status".equals(action)){
            if(!nonInteractive)return arguments.size()==1?new Parsed(Action.STATUS,null,null,null,null,null):
                    Parsed.error("usage: /compact status");
            return parseOptions(arguments,Action.STATUS,false);
        }
        if(!"preview".equals(action))return Parsed.error(nonInteractive?nonInteractiveUsage():interactiveUsage());
        if(nonInteractive)return parseOptions(arguments,Action.PREVIEW,true);
        if(arguments.size()==1)return new Parsed(Action.PREVIEW,null,null,null,null,null);
        if(arguments.size()==3&&"--operation".equals(arguments.get(1))){
            OperationKind operation=operation(arguments.get(2));
            return operation==null?Parsed.error(interactiveUsage()):
                    new Parsed(Action.PREVIEW,null,null,operation,null,null);
        }
        return Parsed.error(interactiveUsage());
    }

    private static Parsed parseOptions(List<String> arguments,Action action,boolean requireScope){
        String task=null,run=null;OperationKind operation=null;
        for(int i=1;i<arguments.size();i+=2){
            if(i+1>=arguments.size())return Parsed.error(nonInteractiveUsage());
            String flag=arguments.get(i),value=arguments.get(i+1);
            if("--task".equals(flag)&&task==null&&typed(value,EntityFamily.TASK))task=value;
            else if("--run".equals(flag)&&run==null&&typed(value,EntityFamily.RUN))run=value;
            else if(action==Action.PREVIEW&&"--operation".equals(flag)&&operation==null)
                operation=operation(value);
            else return Parsed.error(nonInteractiveUsage());
            if("--operation".equals(flag)&&operation==null)return Parsed.error(nonInteractiveUsage());
        }
        if(requireScope&&(task==null||run==null))return Parsed.error(nonInteractiveUsage());
        return new Parsed(action,task,run,operation,null,null);
    }

    private static OperationKind operation(String value){try{return OperationKind.valueOf(value.toUpperCase(Locale.ROOT));}
        catch(RuntimeException invalid){return null;}}
    private static boolean typed(String value,EntityFamily family){try{return CanonicalId.parse(value).family()==family;}
        catch(RuntimeException invalid){return false;}}
    private static boolean contextId(String value){return typed(value,EntityFamily.CONTEXT);}
    private static ShellCommandResult invalid(String usage){return result("COMPACTION_INVALID_ARGUMENTS",usage);}
    private static String interactiveUsage(){return "usage: /compact preview [--operation <OPERATION>]";}
    private static String nonInteractiveUsage(){return "usage: compact status [--task <task_id>] [--run <run_id>] | compact preview --task <task_id> --run <run_id> [--operation <OPERATION>] | compact apply <ctx_id>";}
    private static ShellCommandResult result(String code,String...lines){return ShellCommandResult.continueWith(code,List.of(lines));}
    private static String shortId(String id){return id==null?"none":id.substring(0,Math.min(16,id.length()));}
    private static String safe(String code){return code==null?"Compaction failed safely.":code.replaceAll("[^A-Z0-9_]","");}
    private enum Action { STATUS, PREVIEW, APPLY }
    private record Parsed(Action action,String taskId,String runId,OperationKind operation,
            String contextId,String error){
        static Parsed error(String error){return new Parsed(null,null,null,null,null,error);}
    }
}
