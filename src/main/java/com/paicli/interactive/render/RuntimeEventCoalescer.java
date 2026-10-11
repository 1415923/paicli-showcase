package com.paicli.interactive.render;

import com.paicli.event.AgentEvent;
import com.paicli.event.AgentEventType;
import com.paicli.event.AgentEvents;

import java.util.Objects;
import java.util.HashSet;
import java.util.Set;
import java.time.Duration;
import java.time.Instant;

final class RuntimeEventCoalescer {
    private static final int MAX_IDENTICAL = 3;
    private String lastLine;
    private AgentEventType lastType;
    private int shown;
    private int suppressed;
    private String pendingDiffLine;
    private String pendingDiffPath;
    private final RuntimeDisplayPreferences preferences;
    private final Set<String> changedFiles = new HashSet<>();
    private int snapshots;
    private int tests = -1;
    private boolean verificationPassed;
    private Instant taskStartedAt;

    RuntimeEventCoalescer() {
        this(new RuntimeDisplayPreferences());
    }

    RuntimeEventCoalescer(RuntimeDisplayPreferences preferences) {
        this.preferences = Objects.requireNonNull(preferences, "preferences");
    }

    synchronized Emission accept(AgentEvent event) {
        updateFacts(event);
        if (!preferences.verbose() && compactHidden(event)) {
            return new Emission(null, null);
        }
        String line = EventLineFormatter.format(event, facts(event));
        AgentEventType type = event.metadata().eventType();
        if (event instanceof AgentEvents.DiffProposed diff) {
            String prior = pendingDiffLine;
            pendingDiffLine = line;
            pendingDiffPath = diff.relativePath();
            return new Emission(prior, null);
        }
        String delayedDiff = pendingDiffLine;
        boolean approvalOwnsDiff = event instanceof AgentEvents.ApprovalRequested approval
                && Objects.equals(pendingDiffPath, approval.relativePath());
        pendingDiffLine = null;
        pendingDiffPath = null;
        if (approvalOwnsDiff) delayedDiff = null;
        if (coalescible(type) && type == lastType && line.equals(lastLine)) {
            if (shown < MAX_IDENTICAL) {
                shown++;
                return new Emission(delayedDiff, line);
            }
            suppressed++;
            return new Emission(delayedDiff, null);
        }
        String summary = suppressed == 0 ? null
                : "[Events] " + suppressed + " repeated event(s) omitted";
        if (delayedDiff != null) {
            summary = summary == null ? delayedDiff : delayedDiff + System.lineSeparator() + summary;
        }
        lastType = type;
        lastLine = line;
        shown = 1;
        suppressed = 0;
        return new Emission(summary, line);
    }

    private void updateFacts(AgentEvent event) {
        if (event instanceof AgentEvents.TaskStarted) {
            changedFiles.clear();
            snapshots = 0;
            tests = -1;
            verificationPassed = false;
            taskStartedAt = event.metadata().timestamp();
        } else if (event instanceof AgentEvents.FileWritten written) {
            changedFiles.add(written.relativePath());
        } else if (event instanceof AgentEvents.SnapshotCreated) {
            snapshots++;
        } else if (event instanceof AgentEvents.VerificationPassed passed) {
            tests = passed.tests();
            verificationPassed = true;
        } else if (event instanceof AgentEvents.VerificationFailed
                || event instanceof AgentEvents.VerificationSkipped) {
            verificationPassed = false;
        }
    }

    private boolean compactHidden(AgentEvent event) {
        return event instanceof AgentEvents.PhaseChanged
                || event instanceof AgentEvents.ToolApproved
                || event instanceof AgentEvents.ToolRejected
                || event instanceof AgentEvents.ApprovalResolved;
    }

    private TaskFacts facts(AgentEvent event) {
        long elapsedMillis = taskStartedAt == null ? 0L : Math.max(0L,
                Duration.between(taskStartedAt, event.metadata().timestamp()).toMillis());
        return new TaskFacts(changedFiles.size(), snapshots, tests,
                verificationPassed, elapsedMillis);
    }

    synchronized String flush() {
        String summary = suppressed == 0 ? null
                : "[Events] " + suppressed + " repeated event(s) omitted";
        if (pendingDiffLine != null) {
            summary = summary == null ? pendingDiffLine
                    : pendingDiffLine + System.lineSeparator() + summary;
        }
        pendingDiffLine = null;
        pendingDiffPath = null;
        suppressed = 0;
        return summary;
    }

    private boolean coalescible(AgentEventType type) {
        return type == AgentEventType.FILE_READ
                || type == AgentEventType.TOOL_APPROVED
                || type == AgentEventType.PHASE_CHANGED
                || type == AgentEventType.MODEL_MESSAGE
                || type == AgentEventType.PLAN_UPDATED;
    }

    record Emission(String summary, String line) { }

    record TaskFacts(int changedFiles, int snapshots, int tests,
            boolean verificationPassed, long elapsedMillis) {
        static TaskFacts empty() { return new TaskFacts(0, 0, -1, false, 0L); }
    }
}
