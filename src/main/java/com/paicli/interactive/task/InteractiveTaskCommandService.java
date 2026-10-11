package com.paicli.interactive.task;

import com.paicli.identity.CanonicalId;
import com.paicli.interactive.command.ShellCommandResult;
import com.paicli.interactive.provider.ProviderPrompt;
import com.paicli.interactive.query.InteractiveQueryFacade;
import com.paicli.interactive.query.InteractiveSnapshotView;
import com.paicli.interactive.query.InteractiveTaskView;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.session.SavedSessionService;
import com.paicli.interactive.session.SessionBinding;
import com.paicli.interactive.render.InteractiveViewModelRenderer;
import com.paicli.interactive.view.SessionRowProjectionResult;
import com.paicli.state.RewindService;
import com.paicli.state.StateStore;
import com.paicli.state.R1aDurableModels.WorkThread;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/** Selection and confirmation only; production runtime and RewindService own side effects. */
public final class InteractiveTaskCommandService implements InteractiveTaskCommandPort {
    private static final int MAX_CHOICES = 20;
    private final Path workspace;
    private final InteractiveQueryFacade queries;
    private final TaskSubmissionPort runtime;
    private final ProviderPrompt prompt;
    private final Clock clock;

    public InteractiveTaskCommandService(Path workspace, InteractiveQueryFacade queries,
            TaskSubmissionPort runtime, ProviderPrompt prompt) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.queries = queries;
        this.runtime = runtime;
        this.prompt = prompt;
        this.clock = Clock.systemUTC();
    }

    InteractiveTaskCommandService(Path workspace, InteractiveQueryFacade queries,
            TaskSubmissionPort runtime, ProviderPrompt prompt, Clock clock) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.queries = queries;
        this.runtime = runtime;
        this.prompt = prompt;
        this.clock = clock;
    }

    @Override
    public ShellCommandResult sessions(InteractiveSession session) {
        InteractiveSession.Snapshot frozenSession=session.snapshot();
        try (StateStore state = new StateStore(workspace)) {
            List<String> lines=state.readOnlySnapshot(()->{
                List<String> snapshotLines=new ArrayList<>();
                List<WorkThread> threads=new ArrayList<>(state.r1aDurable.listWorkThreads(
                        SavedSessionService.workspaceIdentity(workspace)));
                for(WorkThread thread:threads){
                    if(thread.id()==null||thread.updatedAt()==null
                            ||!SavedSessionService.workspaceIdentity(workspace)
                                    .equals(thread.workspaceIdentity()))
                        throw new java.sql.SQLException("SESSION_LIST_IDENTITY_INVALID");
                }
                threads.sort(java.util.Comparator.comparing(WorkThread::updatedAt).reversed()
                        .thenComparing(value->value.id().value()));
                if(threads.isEmpty()){
                    snapshotLines.add("No Saved Sessions in this workspace.");
                    return List.copyOf(snapshotLines);
                }
                snapshotLines.add("Saved Sessions:");
                InteractiveViewModelRenderer renderer=new InteractiveViewModelRenderer();
                for(WorkThread thread:threads){
                    boolean current=frozenSession.binding()!=null
                            &&thread.id().equals(frozenSession.binding().threadId());
                    SessionRowProjectionResult row=state.viewModels
                            .readSessionRowInSnapshot(frozenSession,thread,current);
                    snapshotLines.add(renderer.sessionRow(row));
                }
                return List.copyOf(snapshotLines);
            });
            return ShellCommandResult.continueWith(
                    lines.stream().anyMatch(value->value.startsWith("No Saved Sessions"))
                            ?"SESSIONS_EMPTY":"SESSIONS",lines);
        } catch (Exception error) {
            return rejected("SESSIONS_FAILED", "Saved Session list failed.");
        }
    }

    @Override
    public ShellCommandResult resume(List<String> arguments, InteractiveSession session) {
        if (session.snapshot().currentTaskId() != null) return rejected(
                "RESUME_TASK_ACTIVE", "Saved Session binding is unavailable while a Task is active.");
        if (arguments.isEmpty()) return rejected("RESUME_SELECTOR_REQUIRED",
                "Use /resume --last or /sessions.");
        if (arguments.size() != 1) return rejected("RESUME_SELECTOR_INVALID",
                "Use exactly one Thread selector or --last.");
        try (StateStore state = new StateStore(workspace)) {
            SavedSessionService.SelectionResult result =
                    new SavedSessionService(state, workspace, clock)
                            .bind(session, arguments.getFirst());
            if (result.view() == null) {
                return rejected(result.status().name(), result.message());
            }
            return bindingResult("RESUME_SUCCESS", result.view());
        } catch (Exception error) {
            return rejected("RESUME_FAILED", "Saved Session binding failed.");
        }
    }

    @Override
    public ShellCommandResult newSession(InteractiveSession session) {
        if (session.snapshot().currentTaskId() != null) return rejected(
                "NEW_TASK_ACTIVE", "Use /new only after the active Task stops.");
        session.clearThreadBinding(clock.instant());
        long generation = session.clearContext();
        return ShellCommandResult.continueWith("NEW_UNBOUND", List.of(
                "Saved Session binding cleared.",
                "The next ordinary input will create an independent Thread.",
                "Context generation: " + generation));
    }

    @Override
    public ShellCommandResult recover(List<String> arguments, InteractiveSession session) {
        if (arguments.isEmpty() || arguments.size() > 2
                || !"recover".equals(arguments.getFirst())) {
            return rejected("TASK_RECOVERY_USAGE",
                    "Use /task recover [canonical-task-id].");
        }
        if (session.snapshot().currentTaskId() != null) return rejected(
                "TASK_RECOVERY_TASK_ACTIVE", "This process already owns an active Task.");
        try (StateStore state = new StateStore(workspace)) {
            SavedSessionService.SelectionResult refreshed =
                    new SavedSessionService(state, workspace, clock).refresh(session);
            if (refreshed.view() == null) {
                String code = refreshed.status()
                        == SavedSessionService.SelectionStatus.NO_BOUND_SESSION
                        ? "NO_BOUND_SESSION" : refreshed.status().name();
                return rejected(code, refreshed.message());
            }
            SavedSessionService.SavedSessionView view = refreshed.view();
            if (arguments.size() == 2) {
                CanonicalId selected;
                try {
                    selected = CanonicalId.parse(arguments.get(1));
                } catch (IllegalArgumentException error) {
                    return rejected("TASK_NOT_FOUND",
                            "Explicit recovery requires a canonical Task ID.");
                }
                if (selected.family() != com.paicli.identity.EntityFamily.TASK) {
                    return rejected("TASK_NOT_FOUND",
                            "Explicit recovery requires a canonical Task ID.");
                }
                if (!selected.equals(view.headTaskId())) {
                    return rejected("TASK_NOT_IN_BOUND_THREAD",
                            "Task is not the bound Thread Head.");
                }
            }
            SessionBinding.State binding = view.binding().state();
            if (binding == SessionBinding.State.BOUND_TERMINAL) {
                return rejected("TASK_NOT_RECOVERABLE", "The Head Task is terminal.");
            }
            if (binding == SessionBinding.State.BOUND_ACTIVE) {
                return rejected("OWNERSHIP_CONFLICT",
                        "The Head Task has a healthy owner.");
            }
            if (binding == SessionBinding.State.BOUND_CONTINUATION_UNAVAILABLE
                    || binding == SessionBinding.State.BOUND_IDLE) {
                return rejected("CONTINUATION_UNAVAILABLE",
                        "Trusted recovery material is unavailable.");
            }
            TaskSubmissionResult result = runtime.recoverTask(
                    view.headTaskId().value());
            return ShellCommandResult.continueWith(result.code(), List.of(result.message()));
        } catch (Exception error) {
            return rejected("TASK_RECOVERY_FAILED", "Task Recovery admission failed.");
        }
    }

    @Override
    public ShellCommandResult continueTask(List<String> arguments,InteractiveSession session){
        if(arguments.isEmpty())return rejected("FOLLOW_UP_INSTRUCTION_REQUIRED","Use /continue <instruction>.");
        SessionBinding binding=session.snapshot().binding();
        if(binding.state()==SessionBinding.State.BOUND_RECOVERABLE){
            try(StateStore state=new StateStore(workspace)){
                var active=state.planAuthorities.readActive(binding.headTaskId());
                if(active.status()==com.paicli.state.PlanAuthorityStore.Status.SUCCESS
                        &&active.plan().revision().canonicalSchemaVersion()
                        ==com.paicli.state.StructuredPlanModels.Revision.CANONICAL_SCHEMA_V2)
                    return rejected("PLAN_TOOL_SCOPE_UNAVAILABLE",
                            "PLAN_TOOL_SCOPE_UNAVAILABLE");
                return rejected("TASK_RECOVERY_REQUIRED",
                        "This Task requires /task recover before continuation.");
            }catch(Exception error){
                return rejected("FOLLOW_UP_SCOPE_CHECK_FAILED",
                        "Follow-up scope could not be validated.");
            }
        }
        String token=runtime.currentFollowUpHeadToken().orElse(null);
        if(token==null)return rejected("FROZEN_HEAD_REQUIRED","Follow-up Parent/head could not be frozen.");
        TaskSubmissionResult result=runtime.submitFollowUp(String.join(" ",arguments),true,token);
        return ShellCommandResult.continueWith(result.code(),List.of(result.message()));
    }

    private static ShellCommandResult bindingResult(String code,
            SavedSessionService.SavedSessionView view) {
        String guidance = switch (view.binding().state()) {
            case BOUND_RECOVERABLE -> "Run /task recover to resume the Head Task.";
            case BOUND_ACTIVE -> "The Head Task is owned by another healthy runtime.";
            case BOUND_TERMINAL -> "Use /continue <instruction> or enter an eligible natural Follow-up.";
            case BOUND_CONTINUATION_UNAVAILABLE ->
                    "Continuation is unavailable; use /new for an independent Thread.";
            case BOUND_IDLE -> "The Saved Session is bound and idle.";
            case UNBOUND -> throw new IllegalStateException("Bound view cannot be unbound");
        };
        return ShellCommandResult.continueWith(code, List.of(
                "Thread: " + view.threadId().value(),
                "Head Task: " + view.headTaskId().shortDisplay()
                        + " (" + view.headTaskStatus() + ")",
                guidance));
    }

    @Override
    public ShellCommandResult rewind(List<String> arguments, InteractiveSession session) {
        InteractiveSession.Snapshot current = session.snapshot();
        if (current.currentTaskId() != null) return rejected("REWIND_TASK_ACTIVE", "Rewind is unavailable while a task is active.");
        boolean details = arguments.contains("--details");
        List<String> selectors = arguments.stream()
                .filter(value -> !"--details".equals(value)).toList();
        if (selectors.size() > 1) {
            return rejected("REWIND_SELECTOR_INVALID",
                    "Use /rewind [--details] [snapshotId].");
        }
        String taskId = current.lastTaskId();
        InteractiveSnapshotView selected;
        if (selectors.isEmpty()) {
            if (taskId == null) {
                InteractiveTaskView persisted = queries.latestTaskWithSnapshots().orElse(null);
                taskId = persisted == null ? null : persisted.taskId();
            }
            if (taskId == null) return rejected("REWIND_NONE", "No recent task has snapshots.");
            List<InteractiveSnapshotView> candidates = queries.snapshotViews(taskId, MAX_CHOICES);
            selected = chooseSnapshot(candidates, details);
        } else {
            selected = queries.snapshotView(selectors.getFirst()).orElse(null);
            if (selected == null) return rejected("REWIND_NOT_FOUND", "Snapshot was not found.");
        }
        if (selected == null) return cancelled("Rewind cancelled.");
        if (!selected.currentHashMatches()) {
            return rejected("REWIND_HASH_GUARD",
                    "Snapshot is not safely rewindable: current file hash does not match.");
        }
        if (!selected.rewindable()
                && !RewindEligibilityService.ALREADY_REWOUND.equals(selected.eligibilityCode())) {
            return rejected(selected.eligibilityCode(),
                    "Snapshot is not safely rewindable: " + selected.eligibilityCode());
        }
        if (RewindEligibilityService.ALREADY_REWOUND.equals(selected.eligibilityCode())) {
            try (StateStore state = new StateStore(workspace)) {
                RewindEligibilityService.Eligibility eligibility =
                        new RewindEligibilityService(workspace)
                                .evaluate(state, selected.snapshotId());
                if (!eligibility.alreadyRewound()) {
                    return rejected("REWIND_STATE_DRIFT",
                            "Snapshot state changed before reconciliation.");
                }
                new RewindEligibilityService(workspace).ensureProposalLink(state,
                        eligibility, com.paicli.state.InteractiveProposalStore.REWOUND);
            } catch (Exception error) {
                return rejected("REWIND_FAILED", "Rewind reconciliation failed: "
                        + safe(error.getMessage()));
            }
            queries.rewindRecorded(selected.taskId(), selected.relativePath(),
                    selected.snapshotId());
            return ShellCommandResult.continueWith("REWIND_COMPLETED", List.of(
                    "Rewind was already completed; persisted metadata was reconciled.",
                    "Snapshot: " + selected.safeSnapshotId(),
                    "External side effects, if any, are not undone."));
        }
        String restoreLabel = details ? selected.restoresTo()
                : "the state " + selected.reason();
        String confirmation = ask("Rewind " + selected.relativePath() + " from task "
                + safeId(selected.taskId()) + " to " + restoreLabel
                + "? This rewinds only the selected snapshot. [y/N]: ");
        if (!"y".equalsIgnoreCase(confirmation) && !"yes".equalsIgnoreCase(confirmation)) {
            return cancelled("Rewind cancelled.");
        }
        try {
            RewindService.RewindResult result;
            try (StateStore state = new StateStore(workspace)) {
                RewindEligibilityService eligibilityService =
                        new RewindEligibilityService(workspace);
                RewindEligibilityService.Eligibility eligibility =
                        eligibilityService.evaluate(state, selected.snapshotId());
                if (!eligibility.rewindable()) {
                    return rejected(publicRewindRejectionCode(eligibility.code()),
                            "Snapshot eligibility changed: " + eligibility.code());
                }
                eligibilityService.ensureProposalLink(state, eligibility,
                        com.paicli.state.InteractiveProposalStore.APPLIED);
                result = new RewindService(workspace, state.snapshots)
                        .rewind(selected.snapshotId());
                state.interactiveProposals.markRewound(selected.taskId(),
                        selected.relativePath(), selected.snapshotId());
            }
            // Persistence is complete; the query facade only refreshes its bounded view.
            queries.rewindRecorded(selected.taskId(), selected.relativePath(),
                    selected.snapshotId());
            return ShellCommandResult.continueWith("REWIND_COMPLETED", List.of(
                    "Rewind completed: " + safe(result.filePath()),
                    "Operation: " + safe(result.operation()),
                    "Snapshot: " + selected.safeSnapshotId(),
                    "External side effects, if any, are not undone."));
        } catch (Exception error) {
            return rejected("REWIND_FAILED", "Rewind failed: " + safe(error.getMessage()));
        }
    }

    private InteractiveSnapshotView chooseSnapshot(List<InteractiveSnapshotView> choices,
            boolean details) {
        if (choices.isEmpty()) return null;
        List<String> lines = new ArrayList<>();
        lines.add("Snapshots:");
        for (int i = 0; i < choices.size(); i++) {
            InteractiveSnapshotView item = choices.get(i);
            boolean safe = item.rewindable()
                    || RewindEligibilityService.ALREADY_REWOUND.equals(
                            item.eligibilityCode());
            String line = (i + 1) + "=" + item.safeSnapshotId() + " "
                    + item.relativePath() + " " + item.createdAt() + " "
                    + item.reason() + " safe=" + (safe ? "yes" : "no");
            if (details) {
                line += " task=" + safeId(item.taskId())
                        + " restores=" + item.restoresTo()
                        + " current-hash="
                        + (item.currentHashMatches() ? "match" : "mismatch")
                        + " eligibility=" + item.eligibilityCode();
            }
            lines.add(line);
        }
        String answer = ask(String.join(System.lineSeparator(), lines)
                + System.lineSeparator() + "Select snapshot number or ID (Enter=cancel): ");
        return selectSnapshot(choices, answer);
    }

    private static InteractiveSnapshotView selectSnapshot(List<InteractiveSnapshotView> choices, String answer) {
        if (answer == null) return null;
        try {
            int index = Integer.parseInt(answer);
            return index >= 1 && index <= choices.size() ? choices.get(index - 1) : null;
        } catch (NumberFormatException ignored) {
            return resolveSnapshot(choices, answer);
        }
    }

    private static InteractiveSnapshotView resolveSnapshot(List<InteractiveSnapshotView> choices, String selector) {
        List<InteractiveSnapshotView> matches = choices.stream()
                .filter(item -> item.snapshotId().equals(selector) || item.safeSnapshotId().equals(selector))
                .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private String ask(String message) {
        String value = prompt.read(message);
        if (value == null || value.isBlank()) return null;
        String clean = value.trim();
        return clean.length() <= 128 && clean.chars().noneMatch(c -> c < 0x20 || c == 0x7f)
                ? clean : null;
    }

    private static ShellCommandResult rejected(String code, String message) {
        return ShellCommandResult.continueWith(code, List.of(safe(message)));
    }

    private static ShellCommandResult cancelled(String message) {
        return ShellCommandResult.continueWith("TASK_MANAGEMENT_CANCELLED", List.of(message));
    }

    private static String publicRewindRejectionCode(String eligibilityCode) {
        return RewindEligibilityService.CURRENT_HASH_MISMATCH.equals(eligibilityCode)
                ? "REWIND_HASH_GUARD" : eligibilityCode;
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) return "operation failed";
        String clean = value.replace('\r', ' ').replace('\n', ' ')
                .replaceAll("(?i)(bearer|token|password|secret|api[_-]?key)(?:\\s*[:=]\\s*|\\s+)\\S+", "$1=<redacted>");
        return clean.substring(0, Math.min(clean.length(), 256));
    }

    private static String safeId(String value) {
        if (value == null) return "unknown";
        return humanId(value);
    }

    private static String humanId(String value) {
        return value.length() <= 16 ? value
                : value.substring(0, 7) + "…" + value.substring(value.length() - 8);
    }
}
