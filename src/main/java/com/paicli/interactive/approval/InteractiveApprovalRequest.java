package com.paicli.interactive.approval;

import com.paicli.interactive.diff.PreWriteDiff;
import com.paicli.safety.ApprovalDecision;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import com.paicli.state.PlanContentBounds;

public record InteractiveApprovalRequest(
        String requestId,
        String sessionId,
        String taskId,
        String runId,
        int iteration,
        String toolName,
        String relativePath,
        ApprovalDecision.RiskLevel risk,
        String approvalMode,
        PreWriteDiff diff,
        Kind kind,
        String boundedSummary,
        String warning,
        Instant expiresAt,
        String planRevisionId,
        int planStepCount,
        int planSchemaVersion,
        List<PlanStepProjection> boundedPlanSteps) {
    public enum Kind { PLAN, EDIT, NEW_FILE, COMMAND, GIT_READ, OTHER }

    /** Immutable, bounded presentation of a step from the revision being approved. */
    public static final int PREVIEW_TITLE_CODE_POINTS = 120;
    public static final int PREVIEW_DESCRIPTION_CODE_POINTS = 256;

    public record PlanStepProjection(int order, String boundedTitle,
            String boundedDescription,
            List<CanonicalPlanPresentation.RequirementProjection> requirements) {
        public PlanStepProjection(int order, String boundedTitle, String boundedDescription) {
            this(order, boundedTitle, boundedDescription, List.of());
        }
        public PlanStepProjection {
            if (order < 0) throw new IllegalArgumentException("invalid Plan step order");
            boundedTitle = preview(boundedTitle, PREVIEW_TITLE_CODE_POINTS);
            boundedDescription = preview(boundedDescription,
                    PREVIEW_DESCRIPTION_CODE_POINTS);
            Objects.requireNonNull(boundedTitle);
            Objects.requireNonNull(boundedDescription);
            requirements = List.copyOf(requirements == null ? List.of() : requirements);
        }

        /** Compatibility accessor; the value remains a non-authoritative bounded preview. */
        public String title() { return boundedTitle; }
        /** Compatibility accessor; the value remains a non-authoritative bounded preview. */
        public String definition() { return boundedDescription; }
    }

    public InteractiveApprovalRequest(String requestId, String sessionId,
            String taskId, String runId, int iteration, String toolName,
            String relativePath, ApprovalDecision.RiskLevel risk,
            String approvalMode, PreWriteDiff diff, Kind kind,
            String boundedSummary, String warning, Instant expiresAt) {
        this(requestId, sessionId, taskId, runId, iteration, toolName, relativePath,
                risk, approvalMode, diff, kind, boundedSummary, warning, expiresAt,
                null, 0, 0, List.of());
    }

    public InteractiveApprovalRequest(String requestId, String sessionId,
            String taskId, String runId, int iteration, String toolName,
            String relativePath, ApprovalDecision.RiskLevel risk,
            String approvalMode, PreWriteDiff diff, Instant expiresAt) {
        this(requestId, sessionId, taskId, runId, iteration, toolName,
                relativePath, risk, approvalMode, diff,
                diff != null && !diff.existingFile() ? Kind.NEW_FILE
                        : diff != null ? Kind.EDIT : Kind.OTHER,
                null, null, expiresAt, null, 0, 0, List.of());
    }

    public InteractiveApprovalRequest(String requestId, String sessionId,
            String taskId, String runId, int iteration, String toolName,
            String relativePath, ApprovalDecision.RiskLevel risk,
            String approvalMode, PreWriteDiff diff, Kind kind,
            String boundedSummary, String warning, Instant expiresAt,
            String planRevisionId, int planStepCount,
            List<PlanStepProjection> boundedPlanSteps) {
        this(requestId, sessionId, taskId, runId, iteration, toolName, relativePath,
                risk, approvalMode, diff, kind, boundedSummary, warning, expiresAt,
                planRevisionId, planStepCount, kind == Kind.PLAN ? 2 : 0, boundedPlanSteps);
    }
    public InteractiveApprovalRequest {
        Objects.requireNonNull(requestId); Objects.requireNonNull(sessionId);
        Objects.requireNonNull(taskId); Objects.requireNonNull(toolName);
        Objects.requireNonNull(risk); Objects.requireNonNull(approvalMode);
        Objects.requireNonNull(kind);
        boundedSummary = bound(boundedSummary, 256);
        warning = bound(warning, 256);
        Objects.requireNonNull(expiresAt);
        boundedPlanSteps = List.copyOf(boundedPlanSteps == null ? List.of() : boundedPlanSteps);
        if (kind == Kind.PLAN) {
            if (planRevisionId == null || planRevisionId.isBlank() || planStepCount < 1
                    || planStepCount > PlanContentBounds.MAX_STEPS
                    || planSchemaVersion < 1 || planSchemaVersion > 3
                    || boundedPlanSteps.size() != planStepCount) {
                throw new IllegalArgumentException("invalid canonical Plan approval projection");
            }
            planRevisionId = bound(planRevisionId, 128);
        } else if (planRevisionId != null || planStepCount != 0 || planSchemaVersion != 0
                || !boundedPlanSteps.isEmpty()) {
            throw new IllegalArgumentException("Plan projection is only valid for Plan approval");
        }
    }

    public String heading() {
        return switch (kind) {
            case PLAN -> "[Advisory Plan Confirmation]";
            case EDIT -> "[Proposed Edit]";
            case NEW_FILE -> "[Proposed New File] NEW FILE";
            case COMMAND -> "[Proposed Command]";
            case GIT_READ -> "[Proposed Git Read]";
            case OTHER -> "[Proposed Tool Action]";
        };
    }

    public String promptLabel() {
        return switch (kind) {
            case PLAN -> "Confirm this advisory Plan revision?";
            case EDIT -> "Apply edit?";
            case NEW_FILE -> "Create file?";
            case COMMAND -> "Run command?";
            case GIT_READ -> "Run Git read?";
            case OTHER -> "Allow tool action?";
        };
    }

    private static String bound(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String clean = value.replace('\r', ' ').replace('\n', ' ')
                .replaceAll("[\\p{Cntrl}&&[^\\t]]", "?");
        return preview(clean, max);
    }

    private static String preview(String value, int maxCodePoints) {
        if (value == null || value.isBlank()) return null;
        String clean = value.replace('\r', ' ').replace('\n', ' ')
                .replaceAll("[\\p{Cntrl}&&[^\\t]]", "?");
        int count = clean.codePointCount(0, clean.length());
        if (count <= maxCodePoints) return clean;
        int prefix = Math.max(0, maxCodePoints - 1);
        return clean.substring(0, clean.offsetByCodePoints(0, prefix)) + "…";
    }
}
