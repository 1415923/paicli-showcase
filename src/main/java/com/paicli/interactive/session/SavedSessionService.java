package com.paicli.interactive.session;

import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.identity.LegacyId;
import com.paicli.state.LegacyAliasStore;
import com.paicli.state.R1aDurableModels.DurableRun;
import com.paicli.state.R1aDurableModels.DurableTask;
import com.paicli.state.R1aDurableModels.OwnershipLease;
import com.paicli.state.R1aDurableModels.WorkThread;
import com.paicli.state.StateStore;
import com.paicli.state.TaskStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Read-only durable Saved Session selection and process-local binding refresh. */
public final class SavedSessionService {
    private static final Pattern SHORT_THREAD =
            Pattern.compile("^thr_[0-9a-f]{12,31}$");
    private static final Pattern CANONICAL_SHAPE =
            Pattern.compile("^[a-z]{3}_[A-Za-z0-9-]+$");
    private final StateStore stateStore;
    private final String workspaceIdentity;
    private final Clock clock;

    public SavedSessionService(StateStore stateStore, Path workspace, Clock clock) {
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.workspaceIdentity = workspaceIdentity(Objects.requireNonNull(workspace, "workspace"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public List<SavedSessionView> list() throws SQLException, IOException {
        List<SavedSessionView> result = new ArrayList<>();
        for (WorkThread thread : stateStore.r1aDurable.listWorkThreads(workspaceIdentity)) {
            view(thread).ifPresent(result::add);
        }
        result.sort(Comparator.comparing(SavedSessionView::lastActivity).reversed()
                .thenComparing(value -> value.threadId().value()));
        return List.copyOf(result);
    }

    public SelectionResult select(String selector) throws SQLException, IOException {
        if (selector == null || selector.isBlank()) {
            return rejected(SelectionStatus.INVALID_ID, "Use /resume <thread> or /resume --last.");
        }
        if ("--last".equals(selector)) {
            List<SavedSessionView> sessions = list();
            return sessions.isEmpty()
                    ? rejected(SelectionStatus.NOT_FOUND,
                            "No Saved Session exists in this workspace.")
                    : selected(sessions.getFirst());
        }
        if (selector.length() == 36) {
            try {
                CanonicalId id = CanonicalId.parse(selector);
                if (id.family() != EntityFamily.THREAD) {
                    String message = id.family() == EntityFamily.TASK
                            ? "Task recovery moved to /task recover after binding its Thread."
                            : "Saved Session Resume requires a Thread ID.";
                    return rejected(SelectionStatus.WRONG_ENTITY_FAMILY, message);
                }
                WorkThread thread = stateStore.r1aDurable.findWorkThread(id).orElse(null);
                if (thread == null) return rejected(SelectionStatus.NOT_FOUND,
                        "Saved Session was not found.");
                if (!workspaceIdentity.equals(thread.workspaceIdentity())) {
                    return rejected(SelectionStatus.WORKSPACE_MISMATCH,
                            "Saved Session belongs to another workspace.");
                }
                return view(thread).map(SavedSessionService::selected)
                        .orElseGet(() -> rejected(SelectionStatus.NOT_FOUND,
                                "Saved Session has no valid Head Task."));
            } catch (IllegalArgumentException error) {
                return rejected(SelectionStatus.INVALID_ID, "Invalid canonical identifier.");
            }
        }
        if (selector.startsWith("thr_")) {
            if (!SHORT_THREAD.matcher(selector).matches()) {
                return rejected(SelectionStatus.INVALID_ID, "Invalid typed Thread selector.");
            }
            List<SavedSessionView> matches = list().stream()
                    .filter(value -> value.threadId().value().startsWith(selector)).toList();
            if (matches.size() > 1) return rejected(SelectionStatus.AMBIGUOUS_SHORT_ID,
                    "Thread selector is ambiguous; provide more characters.");
            if (matches.size() == 1) return selected(matches.getFirst());
            if (!stateStore.r1aDurable.findWorkThreadsByPrefix(selector).isEmpty()) {
                return rejected(SelectionStatus.WORKSPACE_MISMATCH,
                        "Thread selector matches only another workspace.");
            }
            return rejected(SelectionStatus.NOT_FOUND, "Saved Session was not found.");
        }
        if (CANONICAL_SHAPE.matcher(selector).matches()) {
            return rejected(SelectionStatus.INVALID_ID, "Invalid typed identifier.");
        }
        return legacy(selector);
    }

    public SelectionResult refresh(InteractiveSession session)
            throws SQLException, IOException {
        SessionBinding current = session.snapshot().binding();
        if (current.state() == SessionBinding.State.UNBOUND) {
            return rejected(SelectionStatus.NO_BOUND_SESSION, "No Saved Session is bound.");
        }
        WorkThread thread = stateStore.r1aDurable.findWorkThread(current.threadId()).orElse(null);
        if (thread == null) return rejected(SelectionStatus.NOT_FOUND,
                "Bound Saved Session no longer exists.");
        if (!workspaceIdentity.equals(thread.workspaceIdentity())) {
            return rejected(SelectionStatus.WORKSPACE_MISMATCH,
                    "Bound Saved Session belongs to another workspace.");
        }
        SelectionResult result = view(thread).map(SavedSessionService::selected)
                .orElseGet(() -> rejected(SelectionStatus.NOT_FOUND,
                        "Bound Saved Session has no valid Head Task."));
        if (result.view() != null) session.refreshBinding(result.view().binding());
        return result;
    }

    public SelectionResult bind(InteractiveSession session, String selector)
            throws SQLException, IOException {
        SelectionResult result = select(selector);
        if (result.view() != null) session.bindThread(result.view().binding());
        return result;
    }

    private java.util.Optional<SavedSessionView> view(WorkThread thread)
            throws SQLException, IOException {
        var head = stateStore.r1aDurable.readThreadHead(thread.id()).orElse(null);
        if (head == null) return java.util.Optional.empty();
        DurableTask task = stateStore.r1aDurable.findTask(head.headTaskId()).orElse(null);
        if (task == null || !task.threadId().equals(thread.id())) {
            return java.util.Optional.empty();
        }
        TaskStore.TaskRecord runtimeTask =
                stateStore.tasks.findById(task.id().value()).orElse(null);
        String status = runtimeTask == null ? task.status() : runtimeTask.status();
        List<DurableRun> runs = stateStore.r1aDurable.findRunsByTask(task.id());
        Instant last = thread.updatedAt();
        if (runtimeTask != null) last = later(last, parseSqlTime(runtimeTask.updatedAt()));
        for (DurableRun run : runs) last = later(last, run.createdAt());
        OwnershipLease lease = stateStore.r1aDurable.readOwnership(task.id()).orElse(null);
        if (lease != null) last = later(last, lease.heartbeatAt());
        SessionBinding.State bindingState = bindingState(task, status, lease);
        boolean unresolvedOccurrence =
                stateStore.r1aDurable.hasStartedOrUnknownOccurrence(task.id());
        String origin = runs.stream().anyMatch(run -> "RESUME".equals(run.kind()))
                ? "RESUMED" : "NEW";
        return java.util.Optional.of(new SavedSessionView(thread.id(), task.id(),
                safe(thread.goal(), 96), status, last, origin,
                unresolvedOccurrence && bindingState == SessionBinding.State.BOUND_RECOVERABLE
                        ? "OCCURRENCE_REQUIRES_REVIEW"
                        : recoverability(bindingState), new SessionBinding(bindingState,
                thread.id(), task.id(), status, clock.instant())));
    }

    private SessionBinding.State bindingState(DurableTask task, String status,
            OwnershipLease lease) throws SQLException, IOException {
        if (isRecoveryTerminal(status)) return SessionBinding.State.BOUND_TERMINAL;
        Instant now = clock.instant();
        if (lease != null && "ACTIVE".equals(lease.state())
                && now.isBefore(lease.expiresAt())) {
            return SessionBinding.State.BOUND_ACTIVE;
        }
        var pendingChildStart = stateStore.followUps
                .findRecoverableAdmittedChild(task.threadId(), now)
                .filter(value -> value.child().taskId().equals(task.id()));
        if (pendingChildStart.isPresent()) {
            // R1E initial Child startup is recovered from its ADMITTED binding and
            // launch authority. It deliberately does not require or fabricate an
            // R1B execution checkpoint.
            return SessionBinding.State.BOUND_IDLE;
        }
        var checkpoint = stateStore.checkpoints.loadLatest(task.id().value()).orElse(null);
        if (task.manifestId() == null
                || stateStore.r1aDurable.readContextPointer(task.id()).isEmpty()
                || checkpoint == null
                || !("RUNNING".equals(checkpoint.status())
                        || "SEGMENT_FAILED".equals(checkpoint.status()))) {
            return SessionBinding.State.BOUND_CONTINUATION_UNAVAILABLE;
        }
        return SessionBinding.State.BOUND_RECOVERABLE;
    }

    private static boolean isRecoveryTerminal(String status) {
        return java.util.Set.of(TaskStore.STATUS_DONE,
                TaskStore.STATUS_COMPLETED_VERIFIED,
                TaskStore.STATUS_COMPLETED_UNVERIFIED,
                TaskStore.STATUS_REJECTED,
                TaskStore.STATUS_CANCELLED,
                TaskStore.STATUS_CANCELLED_UNKNOWN_OUTCOME).contains(status);
    }

    private SelectionResult legacy(String selector) throws SQLException {
        LegacyAliasStore.Resolution resolution = stateStore.legacyAliases.resolve(
                new LegacyId(EntityFamily.TASK, selector));
        if (resolution instanceof LegacyAliasStore.Resolution.Ambiguous) {
            return rejected(SelectionStatus.AMBIGUOUS_LEGACY_ID,
                    "Legacy Task identity is ambiguous; explicit migration is required.");
        }
        if (resolution instanceof LegacyAliasStore.Resolution.Invalid) {
            return rejected(SelectionStatus.INVALID_ID, "Legacy Task record is invalid.");
        }
        if (resolution instanceof LegacyAliasStore.Resolution.Mapped
                || resolution instanceof LegacyAliasStore.Resolution.Unmapped) {
            return rejected(SelectionStatus.LEGACY_ID_REQUIRES_EXPLICIT_MIGRATION,
                    "Legacy Task IDs cannot select a Thread; explicit migration is required.");
        }
        return rejected(SelectionStatus.LEGACY_ID_REQUIRES_EXPLICIT_MIGRATION,
                "Unknown legacy Task identity; continuation is unavailable.");
    }

    private static String recoverability(SessionBinding.State state) {
        return switch (state) {
            case BOUND_RECOVERABLE -> "RECOVERABLE";
            case BOUND_ACTIVE -> "ACTIVE";
            case BOUND_TERMINAL -> "TERMINAL";
            case BOUND_CONTINUATION_UNAVAILABLE -> "CONTINUATION_UNAVAILABLE";
            case BOUND_IDLE -> "IDLE";
            case UNBOUND -> throw new IllegalArgumentException("Saved Session cannot be unbound");
        };
    }

    private static SelectionResult selected(SavedSessionView value) {
        return new SelectionResult(SelectionStatus.SUCCESS, value, "Saved Session bound.");
    }

    private static SelectionResult rejected(SelectionStatus status, String message) {
        return new SelectionResult(status, null, message);
    }

    private static Instant parseSqlTime(String value) {
        if (value == null) return Instant.EPOCH;
        try {
            return Instant.parse(value);
        } catch (java.time.format.DateTimeParseException ignored) {
            return java.time.LocalDateTime.parse(value,
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .toInstant(java.time.ZoneOffset.UTC);
        }
    }

    private static Instant later(Instant left, Instant right) {
        return right.isAfter(left) ? right : left;
    }

    private static String safe(String value, int max) {
        String clean = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ')
                .replaceAll("(?i)(bearer|token|password|secret|api[_-]?key)(?:\\s*[:=]\\s*|\\s+)\\S+",
                        "$1=<redacted>");
        return clean.substring(0, Math.min(clean.length(), max));
    }

    public static String workspaceIdentity(Path workspace) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    workspace.toAbsolutePath().normalize().toString()
                            .getBytes(StandardCharsets.UTF_8));
            return "workspace-sha256-" + HexFormat.of().formatHex(digest).substring(0, 24);
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    public enum SelectionStatus {
        SUCCESS,
        NOT_FOUND,
        AMBIGUOUS_SHORT_ID,
        WRONG_ENTITY_FAMILY,
        WORKSPACE_MISMATCH,
        INVALID_ID,
        LEGACY_ID_REQUIRES_EXPLICIT_MIGRATION,
        AMBIGUOUS_LEGACY_ID,
        NO_BOUND_SESSION
    }

    public record SelectionResult(SelectionStatus status, SavedSessionView view,
                                  String message) {
        public SelectionResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(message, "message");
            if ((status == SelectionStatus.SUCCESS) != (view != null)) {
                throw new IllegalArgumentException("Selection result/view mismatch");
            }
        }
    }

    public record SavedSessionView(CanonicalId threadId, CanonicalId headTaskId,
                                   String summary, String headTaskStatus,
                                   Instant lastActivity, String origin,
                                   String recoverability, SessionBinding binding) {
        public SavedSessionView {
            Objects.requireNonNull(threadId);
            Objects.requireNonNull(headTaskId);
            Objects.requireNonNull(summary);
            Objects.requireNonNull(headTaskStatus);
            Objects.requireNonNull(lastActivity);
            Objects.requireNonNull(origin);
            Objects.requireNonNull(recoverability);
            Objects.requireNonNull(binding);
        }
    }

}
