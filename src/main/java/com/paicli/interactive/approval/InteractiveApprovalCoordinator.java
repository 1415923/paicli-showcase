package com.paicli.interactive.approval;

import com.paicli.agent.AgentLoopControl;
import com.paicli.interactive.cancel.InteractiveCancellationController;
import com.paicli.interactive.diff.PreWriteDiff;
import com.paicli.interactive.diff.PreWriteDiffService;
import com.paicli.model.ToolCall;
import com.paicli.safety.ApprovalDecision;
import com.paicli.runtime.CancellationToken;
import com.paicli.runtime.PersistenceSanitizer;
import com.paicli.runtime.PlanApprovalPort;
import com.paicli.state.StructuredPlanModels.Revision;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;

public final class InteractiveApprovalCoordinator implements AgentLoopControl, PlanApprovalPort {
    private static final Duration TIMEOUT = Duration.ofMinutes(5);
    private static final String EXPIRED_OR_INVALIDATED =
            "Approval expired, was invalidated, or was replaced; input was not reused.";
    private final String sessionId;
    private final InteractiveCancellationController cancellation;
    private final Clock clock;
    private final AtomicReference<Pending> pending = new AtomicReference<>();
    private volatile TaskScope task;
    private volatile ApprovalEvents events = ApprovalEvents.NOOP;
    private volatile boolean safeEditsGranted;
    private final Map<String, Set<String>> rejectedProposalHashes = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> rejectedProposalCounts = new ConcurrentHashMap<>();

    public InteractiveApprovalCoordinator(
            String sessionId, InteractiveCancellationController cancellation, Clock clock) {
        this.sessionId = sessionId;
        this.cancellation = cancellation;
        this.clock = clock;
        cancellation.onApprovalCancellation(this::cancelPending);
    }

    public synchronized void beginTask(String taskId, String runId, String mode,
            PreWriteDiffService diffService, ApprovalEvents events) {
        beginTask(taskId, runId, mode, null, diffService, events);
    }

    public synchronized void beginTask(String taskId, String runId, String mode,
            String boundedGoal, PreWriteDiffService diffService, ApprovalEvents events) {
        if (task != null) throw new IllegalStateException("approval task already active");
        task = new TaskScope(taskId, runId, mode, diffService,
                requiresExistingFilesOnly(boundedGoal));
        this.events = events == null ? ApprovalEvents.NOOP : events;
        safeEditsGranted = false;
        rejectedProposalHashes.clear();
        rejectedProposalCounts.clear();
    }

    public synchronized void endTask(String taskId) {
        if (task != null && task.taskId().equals(taskId)) {
            cancelPending();
            task = null;
            events = ApprovalEvents.NOOP;
            safeEditsGranted = false;
            rejectedProposalHashes.clear();
            rejectedProposalCounts.clear();
        }
    }

    @Override
    public ApprovalDecision resolveApproval(
            String taskId, int iteration, ToolCall call, ApprovalDecision policyDecision) {
        cancellation.throwIfCancellationRequested();
        TaskScope scope = requireTask(taskId);
        if (policyDecision.decision() == ApprovalDecision.Decision.DENY) {
            return policyDecision;
        }
        String currentMode = events.approvalMode() == null
                ? scope.mode() : events.approvalMode();
        PreWriteDiff diff = prepareDiff(scope, call);
        ApprovalDecision preflight = com.paicli.config.Config.MODE_ON_REQUEST.equals(currentMode)
                ? preflightWriteTarget(taskId, call, policyDecision, diff) : null;
        if (preflight != null) return preflight;
        if (diff != null && !diff.existingFile() && scope.existingFilesOnly()) {
            ApprovalDecision denied = ApprovalDecision.deny(call.name(),
                    policyDecision.riskLevel(), taskId,
                    "task requires modifying existing files only");
            events.resolved(null, denied, false);
            return denied;
        }
        if (diff != null && isRejectedDuplicate(diff)) {
            return denyWithoutPrompt(taskId, call, policyDecision,
                    "DUPLICATE_PROPOSAL: identical rejected proposal");
        }
        if (diff != null && rejectBudgetExhausted(diff.relativePath())) {
            return denyWithoutPrompt(taskId, call, policyDecision,
                    "REJECT_BUDGET_EXHAUSTED: too many rejected proposals for target");
        }
        String proposalId = diff == null ? null
                : "proposal-" + UUID.randomUUID().toString().substring(0, 12);
        if (diff != null) {
            events.proposed(proposalId, taskId,
                    scope.runId() == null ? events.activeRunId() : scope.runId(), diff);
        }
        boolean observedTarget = diff == null || !diff.existingFile()
                || events.readHash(diff.relativePath()) != null;
        boolean forceInteractive = diff != null
                && (!diff.existingFile() || diff.conflictingRelativePath() != null);
        if (policyDecision.decision() != ApprovalDecision.Decision.NEEDS_APPROVAL
                && !forceInteractive) {
            return policyDecision;
        }
        if (!com.paicli.config.Config.MODE_ON_REQUEST.equals(currentMode)
                && !(forceInteractive
                    && com.paicli.config.Config.MODE_WORKSPACE_WRITE.equals(currentMode))) {
            ApprovalDecision denied = ApprovalDecision.deny(call.name(),
                    policyDecision.riskLevel(), taskId,
                    "approval mode changed while request was pending" );
            events.resolved(null, denied, false);
            return denied;
        }
        if (safeEditsGranted && observedTarget
                && (diff == null || diff.conflictingRelativePath() == null)
                && isSafeEdit(call, diff)) {
            ApprovalDecision allowed = ApprovalDecision.allow(call.name(), policyDecision.riskLevel(),
                    taskId, "interactive task-scoped safe edit grant");
            events.resolved(null, allowed, false);
            return allowed;
        }
        String requestId = proposalId == null
                ? "approval-" + UUID.randomUUID().toString().substring(0, 12)
                : proposalId;
        InteractiveApprovalRequest.Kind kind = kind(call, diff);
        String warning = warning(diff, observedTarget);
        InteractiveApprovalRequest request = new InteractiveApprovalRequest(
                requestId, sessionId, taskId,
                scope.runId() == null ? events.activeRunId() : scope.runId(),
                iteration, call.name(),
                diff == null ? null : diff.relativePath(), policyDecision.riskLevel(),
                currentMode, diff, kind, summary(call, diff), warning,
                clock.instant().plus(TIMEOUT));
        Pending entry = new Pending(request, new CompletableFuture<>(), new AtomicInteger());
        if (!pending.compareAndSet(null, entry)) {
            throw new IllegalStateException("another interactive approval is pending");
        }
        try {
            events.requested(request);
            InteractiveApprovalDecision decision = entry.decision()
                    .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            ApprovalDecision resolved = switch (decision) {
                case APPLY -> ApprovalDecision.allow(call.name(), policyDecision.riskLevel(),
                        taskId, "interactive user approved this request");
                case ALLOW_SAFE_EDITS_FOR_TASK -> {
                    if (!isSafeEdit(call, diff)) {
                        yield ApprovalDecision.deny(call.name(), policyDecision.riskLevel(),
                                taskId, "task-scoped safe-edit grant is not eligible");
                    }
                    safeEditsGranted = true;
                    yield ApprovalDecision.allow(call.name(), policyDecision.riskLevel(),
                            taskId, "interactive task-scoped safe edit grant");
                }
                case REJECT, SHOW_DIFF -> {
                    recordRejected(diff);
                    if (diff != null) events.proposalResolved(request, "REJECTED");
                    yield ApprovalDecision.deny(call.name(), policyDecision.riskLevel(),
                            taskId, "interactive user rejected current action; task may continue");
                }
                case CANCEL_TASK -> {
                    throw new CancellationToken.TaskCancellationException(false);
                }
            };
            events.resolved(request, resolved,
                    decision == InteractiveApprovalDecision.ALLOW_SAFE_EDITS_FOR_TASK);
            cancellation.throwIfCancellationRequested();
            return resolved;
        } catch (CancellationToken.TaskCancellationException error) {
            throw error;
        } catch (Exception error) {
            if (error instanceof java.util.concurrent.ExecutionException execution
                    && execution.getCause() instanceof PlanApprovalPresentationException cause) {
                throw cause;
            }
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            ApprovalDecision denied = ApprovalDecision.deny(call.name(), policyDecision.riskLevel(),
                    taskId, "interactive approval timed out or was interrupted");
            events.resolved(request, denied, false);
            return denied;
        } finally {
            pending.compareAndSet(entry, null);
        }
    }

    @Override public CancellationToken cancellationToken() { return cancellation; }

    @Override
    public PlanApprovalPort.Decision requestApproval(Revision revision, String boundedSummary) {
        cancellation.throwIfCancellationRequested();
        TaskScope scope = requireTask(revision.taskId().value());
        String requestId = "plan-approval-" + UUID.randomUUID().toString().substring(0, 12);
        List<InteractiveApprovalRequest.PlanStepProjection> steps=revision.steps().stream()
                .map(step->new InteractiveApprovalRequest.PlanStepProjection(
                        step.order(),step.title(),step.definition(),List.of()))
                .toList();
        InteractiveApprovalRequest request = new InteractiveApprovalRequest(requestId, sessionId,
                revision.taskId().value(), scope.runId() == null ? events.activeRunId() : scope.runId(),
                0, "advisory_plan_confirmation", null, ApprovalDecision.RiskLevel.MEDIUM,
                scope.mode(), null, InteractiveApprovalRequest.Kind.PLAN, boundedSummary,
                CanonicalPlanPresentation.ADVISORY_WARNING,
                clock.instant().plus(TIMEOUT),revision.planRevisionId().value(),
                revision.steps().size(),revision.canonicalSchemaVersion(),steps);
        Pending entry = new Pending(request, new CompletableFuture<>(), new AtomicInteger());
        if (!pending.compareAndSet(null, entry)) {
            return new PlanApprovalPort.Decision(false, "ANOTHER_APPROVAL_IS_PENDING",
                    sessionId, "r1c-a-v1");
        }
        try {
            events.requested(request);
            InteractiveApprovalDecision answer = entry.decision()
                    .get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            boolean approved = answer == InteractiveApprovalDecision.APPLY;
            ApprovalDecision rendered = approved
                    ? ApprovalDecision.allow("advisory_plan_confirmation",
                            ApprovalDecision.RiskLevel.MEDIUM, revision.taskId().value(),
                            "interactive user approved immutable Plan revision")
                    : ApprovalDecision.deny("advisory_plan_confirmation",
                            ApprovalDecision.RiskLevel.MEDIUM, revision.taskId().value(),
                            "interactive user did not approve Plan revision");
            events.resolved(request, rendered, false);
            if (answer == InteractiveApprovalDecision.CANCEL_TASK) {
                throw new CancellationToken.TaskCancellationException(false);
            }
            return new PlanApprovalPort.Decision(approved,
                    approved ? "USER_APPROVED" : "USER_REJECTED", sessionId, "r1c-a-v1");
        } catch (CancellationToken.TaskCancellationException error) {
            throw error;
        } catch (PlanApprovalPresentationException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new PlanApprovalPresentationException(error);
        } catch (Exception error) {
            if (error instanceof java.util.concurrent.ExecutionException execution
                    && execution.getCause() instanceof PlanApprovalPresentationException cause) {
                throw cause;
            }
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return new PlanApprovalPort.Decision(false, "PLAN_APPROVAL_TIMEOUT",
                    sessionId, "r1c-a-v1");
        } finally {
            pending.compareAndSet(entry, null);
        }
    }

    public Optional<InteractiveApprovalRequest> pendingRequest() {
        Pending value = pending.get();
        return value == null ? Optional.empty() : Optional.of(value.request());
    }

    @Override
    public boolean hasPendingApproval() { return pending.get() != null; }

    public ApprovalInteractionResult respond(String requestId, String input) {
        Pending value = pending.get();
        if (value == null || !value.request().requestId().equals(requestId)) {
            return new ApprovalInteractionResult(false, false,
                    List.of(EXPIRED_OR_INVALIDATED));
        }
        if (clock.instant().isAfter(value.request().expiresAt())) {
            if (!resolvePending(value, InteractiveApprovalDecision.REJECT)) {
                return new ApprovalInteractionResult(false, false,
                        List.of(EXPIRED_OR_INVALIDATED));
            }
            return new ApprovalInteractionResult(true, false,
                    List.of("Approval expired and was rejected."));
        }
        String normalized = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("d") || normalized.equals("diff") || normalized.equals("/diff")) {
            PreWriteDiff diff = value.request().diff();
            if (diff != null) return new ApprovalInteractionResult(
                    false, false, diff.boundedLines());
            if (value.request().kind() == InteractiveApprovalRequest.Kind.PLAN) {
                try {
                    return new ApprovalInteractionResult(false, false,
                            events.planDetails(value.request()));
                } catch (RuntimeException error) {
                    if (pending.compareAndSet(value, null)) {
                        value.decision().completeExceptionally(
                                new PlanApprovalPresentationException(error));
                    }
                    return new ApprovalInteractionResult(true, false,
                            List.of("PLAN_APPROVAL_PRESENTATION_FAILED"));
                }
            }
            List<String> details = new java.util.ArrayList<>();
            if (value.request().boundedSummary() != null) {
                details.add(value.request().boundedSummary());
            }
            if (value.request().warning() != null) details.add(value.request().warning());
            if (details.isEmpty()) details.add("No additional bounded details are available.");
            return new ApprovalInteractionResult(false, false, details);
        }
        InteractiveApprovalDecision decision = switch (normalized) {
            case "y", "yes", "apply" -> InteractiveApprovalDecision.APPLY;
            case "a", "all-safe", "allow-safe" ->
                    InteractiveApprovalDecision.ALLOW_SAFE_EDITS_FOR_TASK;
            case "c", "cancel" -> InteractiveApprovalDecision.CANCEL_TASK;
            case "n", "no", "reject" -> InteractiveApprovalDecision.REJECT;
            default -> null;
        };
        if (decision == null) {
            value.invalidAttempts().incrementAndGet();
            return new ApprovalInteractionResult(false, false,
                    List.of(normalized.isEmpty()
                            ? "Enter y, n, d, or c; an empty response does not approve."
                            : "Choose y, n, d, or c."));
        }
        if (decision == InteractiveApprovalDecision.ALLOW_SAFE_EDITS_FOR_TASK
                && value.request().kind() != InteractiveApprovalRequest.Kind.EDIT) {
            if (!resolvePending(value, InteractiveApprovalDecision.REJECT)) {
                return new ApprovalInteractionResult(false, false,
                        List.of(EXPIRED_OR_INVALIDATED));
            }
            return new ApprovalInteractionResult(true, false,
                    List.of("Task-scoped safe-edit approval is not eligible; action rejected."));
        }
        if (decision == InteractiveApprovalDecision.CANCEL_TASK) {
            if (!pending.compareAndSet(value, null)) {
                return new ApprovalInteractionResult(false, false,
                        List.of(EXPIRED_OR_INVALIDATED));
            }
            RuntimeException auditFailure = null;
            try {
                if (value.request().diff() != null) {
                    events.proposalResolved(value.request(), "CANCELLED");
                }
                ApprovalDecision cancelled = ApprovalDecision.deny(
                        value.request().toolName(), value.request().risk(),
                        value.request().taskId(), "interactive user cancelled task");
                events.resolved(value.request(), cancelled, false);
            } catch (RuntimeException error) {
                auditFailure = error;
            }
            try {
                cancellation.requestFromApprovalDecision(stage ->
                        events.cancellationRequested(
                                stage == InteractiveCancellationController.Stage.FORCED));
            } finally {
                value.decision().complete(InteractiveApprovalDecision.CANCEL_TASK);
            }
            return new ApprovalInteractionResult(true, true,
                    List.of(auditFailure == null
                            ? "Task cancellation requested."
                            : "Task cancellation requested; approval audit was incomplete."));
        }
        if (!resolvePending(value, decision)) {
            return new ApprovalInteractionResult(false, false,
                    List.of(EXPIRED_OR_INVALIDATED));
        }
        return new ApprovalInteractionResult(true,
                decision == InteractiveApprovalDecision.CANCEL_TASK,
                List.of(decision == InteractiveApprovalDecision.APPLY
                        ? approvedMessage(value.request()) : decision == InteractiveApprovalDecision.ALLOW_SAFE_EDITS_FOR_TASK
                        ? "Safe edits approved for this task." : decision == InteractiveApprovalDecision.CANCEL_TASK
                        ? "Task cancellation requested." : rejectedMessage(value.request())));
    }

    public void cancelPending() {
        Pending value = pending.getAndSet(null);
        if (value == null) return;
        try {
            if (value.request().diff() != null) {
                events.proposalResolved(value.request(), "CANCELLED");
            }
            ApprovalDecision cancelled = ApprovalDecision.deny(
                    value.request().toolName(), value.request().risk(),
                    value.request().taskId(), "interactive user cancelled task");
            events.resolved(value.request(), cancelled, false);
        } catch (RuntimeException ignored) {
            // Cancellation must still release the waiting worker; terminal persistence is separate.
        } finally {
            value.decision().complete(InteractiveApprovalDecision.CANCEL_TASK);
        }
    }

    /** Invalidates an unexecuted intent when durable Steering wins the input race. */
    public boolean invalidatePendingForSteering(){
        Pending value=pending.get();
        if(value==null||!pending.compareAndSet(value,null))return false;
        try{
            if(value.request().diff()!=null)events.proposalResolved(value.request(),"REJECTED");
            events.resolved(value.request(),ApprovalDecision.deny(value.request().toolName(),
                    value.request().risk(),value.request().taskId(),"invalidated by durable Steering"),false);
        }catch(RuntimeException ignored){
            // The decision is still fail-closed even when optional rendering/audit fails.
        }finally{value.decision().complete(InteractiveApprovalDecision.REJECT);}
        return true;
    }

    private boolean resolvePending(Pending value, InteractiveApprovalDecision decision) {
        if (!pending.compareAndSet(value, null)) return false;
        value.decision().complete(decision);
        return true;
    }

    private TaskScope requireTask(String taskId) {
        TaskScope value = task;
        if (value == null || !value.taskId().equals(taskId)) {
            throw new IllegalStateException("approval task identity mismatch");
        }
        return value;
    }

    private PreWriteDiff prepareDiff(TaskScope scope, ToolCall call) {
        if (!"write_file".equals(call.name())) return null;
        Object path = call.arguments().get("path");
        Object content = call.arguments().get("content");
        Object overwrite = call.arguments().get("overwrite");
        if (!(path instanceof String pathText) || !(content instanceof String text)) {
            throw new IllegalArgumentException("write_file approval requires path and content");
        }
        return scope.diffService().prepare(pathText, text, Boolean.TRUE.equals(overwrite));
    }

    private ApprovalDecision preflightWriteTarget(String taskId, ToolCall call,
            ApprovalDecision policyDecision, PreWriteDiff diff) {
        if (diff == null || !diff.existingFile()) return null;
        String readHash = events.readHash(diff.relativePath());
        if (readHash == null) {
            return denyWithoutPrompt(taskId, call, policyDecision,
                    "WRITE_TARGET_NOT_READ: successful read_file is required before edit approval");
        }
        if (!readHash.equals(diff.beforeHash())) {
            return denyWithoutPrompt(taskId, call, policyDecision,
                    "WRITE_TARGET_CHANGED_AFTER_READ: target content changed after read_file");
        }
        return null;
    }

    private ApprovalDecision denyWithoutPrompt(String taskId, ToolCall call,
            ApprovalDecision policyDecision, String reason) {
        ApprovalDecision denied = ApprovalDecision.deny(call.name(),
                policyDecision.riskLevel(), taskId, reason);
        events.resolved(null, denied, false);
        return denied;
    }

    private boolean isRejectedDuplicate(PreWriteDiff diff) {
        return rejectedProposalHashes.getOrDefault(diff.relativePath(), Set.of())
                .contains(diff.afterHash());
    }

    private boolean rejectBudgetExhausted(String relativePath) {
        AtomicInteger count = rejectedProposalCounts.get(relativePath);
        return count != null && count.get() >= 2;
    }

    private void recordRejected(PreWriteDiff diff) {
        if (diff == null) return;
        rejectedProposalHashes.computeIfAbsent(diff.relativePath(), ignored ->
                ConcurrentHashMap.newKeySet()).add(diff.afterHash());
        rejectedProposalCounts.computeIfAbsent(diff.relativePath(), ignored ->
                new AtomicInteger()).incrementAndGet();
    }

    private static boolean isSafeEdit(ToolCall call, PreWriteDiff diff) {
        if (!"write_file".equals(call.name()) || diff == null || !diff.existingFile()) return false;
        String path = diff.relativePath().toLowerCase(Locale.ROOT);
        return path.startsWith("src/main/") && path.endsWith(".java")
                && !path.contains("/test/") && !path.contains("/.paicli/")
                && !path.endsWith("module-info.java");
    }

    private static InteractiveApprovalRequest.Kind kind(ToolCall call, PreWriteDiff diff) {
        if (diff != null) return diff.existingFile()
                ? InteractiveApprovalRequest.Kind.EDIT
                : InteractiveApprovalRequest.Kind.NEW_FILE;
        return switch (call.name()) {
            case "run_command" -> InteractiveApprovalRequest.Kind.COMMAND;
            case "git_diff" -> InteractiveApprovalRequest.Kind.GIT_READ;
            default -> InteractiveApprovalRequest.Kind.OTHER;
        };
    }

    private static String summary(ToolCall call, PreWriteDiff diff) {
        if (diff != null) return null;
        if ("run_command".equals(call.name())) {
            Object value = call.arguments().get("command");
            String command = value instanceof String text ? text : "command unavailable";
            return "Command: " + new PersistenceSanitizer().sanitizeAndTruncate(command, 192);
        }
        if ("git_diff".equals(call.name())) {
            Object subcommand = call.arguments().get("subcommand");
            return "Git read: " + (subcommand instanceof String text ? text : "diff");
        }
        return "Tool: " + call.name();
    }

    private static String warning(PreWriteDiff diff, boolean observedTarget) {
        if (diff == null) return "External side effects may not be reversible.";
        if (!diff.existingFile() && diff.conflictingRelativePath() != null) {
            return "Path conflict: a file with the same name already exists at "
                    + diff.conflictingRelativePath() + ". Default is reject.";
        }
        if (!diff.existingFile()) return "NEW FILE: task-scoped safe-edit approval does not apply.";
        if (!observedTarget) return "Target has no current read_file evidence.";
        return null;
    }

    private static boolean requiresExistingFilesOnly(String goal) {
        if (goal == null) return false;
        String normalized = goal.toLowerCase(Locale.ROOT);
        return normalized.contains("only modify existing")
                || normalized.contains("do not create")
                || normalized.contains("只修改现有")
                || normalized.contains("不要创建")
                || normalized.contains("不创建新文件");
    }

    private static String approvedMessage(InteractiveApprovalRequest request) {
        return switch (request.kind()) {
            case PLAN -> "Advisory Plan confirmed; Tool authority remains governed by runtime safety and approval policy.";
            case EDIT -> "Edit approved.";
            case NEW_FILE -> "New file approved.";
            case COMMAND -> "Command approved.";
            case GIT_READ -> "Git read approved.";
            case OTHER -> "Tool action approved.";
        };
    }

    private static String rejectedMessage(InteractiveApprovalRequest request) {
        return switch (request.kind()) {
            case PLAN -> "Plan revision rejected.";
            case EDIT -> "Edit rejected.";
            case NEW_FILE -> "New file rejected.";
            case COMMAND -> "Command rejected.";
            case GIT_READ -> "Git read rejected.";
            case OTHER -> "Tool action rejected.";
        };
    }

    private record Pending(InteractiveApprovalRequest request,
                           CompletableFuture<InteractiveApprovalDecision> decision,
                           AtomicInteger invalidAttempts) { }
    private record TaskScope(String taskId, String runId, String mode,
                             PreWriteDiffService diffService,
                             boolean existingFilesOnly) { }

    public interface ApprovalEvents {
        ApprovalEvents NOOP = new ApprovalEvents() { };
        default String activeRunId() { return null; }
        default String approvalMode() { return null; }
        default String readHash(String relativePath) { return null; }
        default void cancellationRequested(boolean forced) { }
        default void proposed(String proposalId, String taskId, String runId,
                              PreWriteDiff diff) { }
        default void proposalResolved(InteractiveApprovalRequest request, String status) { }
        default void requested(InteractiveApprovalRequest request) { }
        default List<String> planDetails(InteractiveApprovalRequest request) {
            throw new PlanApprovalPresentationException(
                    new IllegalStateException("durable Plan details are unavailable"));
        }
        default void resolved(InteractiveApprovalRequest request,
                              ApprovalDecision decision, boolean taskScoped) { }
    }

    public static final class PlanApprovalPresentationException extends RuntimeException {
        public static final String CODE = "PLAN_APPROVAL_PRESENTATION_FAILED";
        public PlanApprovalPresentationException(Throwable cause) { super(CODE, cause); }
    }
}
