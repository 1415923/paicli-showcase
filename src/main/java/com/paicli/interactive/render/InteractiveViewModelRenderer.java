package com.paicli.interactive.render;

import com.paicli.interactive.view.*;

import java.util.ArrayList;
import java.util.List;
import java.time.Instant;

/** Formatting-only renderer for the already-resolved R2 ViewModel. */
public final class InteractiveViewModelRenderer {
    /** Pure formatting of the already-complete report projection. */
    public List<String> report(TaskReportView report) {
        List<String> lines=new ArrayList<>();
        SelectedTaskIdentity identity=report.key().identity();
        lines.add("Projection: "+(identity.relation()==SelectedTaskIdentity.Relation.CURRENT_HEAD
                ?"current":"historical")+" task "+reportId(identity.taskId().value()));
        lines.add("Projection relation: "+identity.relation());
        lines.add("Operational status: "+report.operationalStatus().primary());
        lines.add("Failure domain: "+fact(report.operationalStatus().failureDomain()));
        lines.add("Projection warnings: "+String.join(",",report.warnings()));
        lines.add("Provider run calls: "+providerCalls(report.providerUsage()));
        lines.add("Provider run tokens: "+providerTokens(report.providerUsage()));
        lines.add("Context: "+context(report.context()));
        lines.add("Task: "+reportId(identity.taskId().value()));
        lines.add("Status: "+taskStatus(report.task()));
        lines.add("Provider / Model: "+providerIdentity(report.legacyTask()));
        lines.add("Run: "+(identity.selectedRunId()==null?"NOT_APPLICABLE":
                reportId(identity.selectedRunId().value())));
        lines.add("Run phase: "+legacyRunPhase(report.legacyRun()));
        lines.add("Generation: "+(identity.selectedGenerationId()==null?"NOT_APPLICABLE":
                reportId(identity.selectedGenerationId().value())));
        lines.add("Manifest: "+scopedState(report.manifest()));
        lines.add("R1C Plan: "+r1cPlan(report.plan()));
        lines.add("Legacy Run Plan: "+legacyPlan(report.legacyPlan()));
        lines.add("Verification: "+verification(report.verification()));
        lines.add("Repair: "+repair(report.repair()));
        lines.add("Code Intelligence: "+code(report.codeFacts()));
        lines.add("Changes: "+changes(report.changes()));
        lines.add("Tool calls / approvals: "+scopedState(report.activity()));
        lines.add("Recovery: "+scopedState(report.recovery()));
        lines.add("Steering: "+scopedState(report.steering()));
        lines.add("Follow-up: "+scopedState(report.followUp()));
        lines.add("Allowed action hints: "+String.join(", ",
                report.operationalStatus().allowedActionHints()));
        lines.add("Prohibited action hints: "+String.join(", ",
                report.operationalStatus().prohibitedActionHints()));
        return List.copyOf(lines);
    }

    private static String context(TaskReportView.ScopedFact<ContextGenerationView> fact){
        if(!fact.isPresent())return fact.state().name();
        ContextGenerationView value=fact.value();
        return value.contextGenerationId().substring(0,Math.min(16,value.contextGenerationId().length()))
                +" "+value.lifecycle()+" "+value.budgetState();
    }

    public String sessionRow(SessionRowProjectionResult row) {
        String current=row.current()?"current":"saved";
        if(row.state()!=SessionRowProjectionResult.State.PRESENT){
            return shortId(row.threadId())+" | "+current+" | "+row.state()
                    +" | "+safe(row.diagnosticRef())+" | "+row.updatedAt();
        }
        InteractiveSessionViewModel view=row.view();
        String task=view.task().isPresent()?shortId(view.task().value().taskId()):
                view.task().state().name();
        return shortId(row.threadId())+" | "+current+" | "+task+" | "
                +view.operationalStatus().primary()+" | "
                +String.join(",",view.operationalStatus().warnings())+" | "+row.updatedAt();
    }

    public List<String> status(InteractiveSessionViewModel view, List<String> arguments) {
        return status(view, arguments, StatusSnapshotFacts.defaults());
    }

    public List<String> status(InteractiveSessionViewModel view, List<String> arguments,
            StatusSnapshotFacts snapshot) {
        String detail = arguments == null || arguments.isEmpty() ? "summary"
                : arguments.getFirst();
        if ("provider".equals(detail) || "usage".equals(detail)) {
            return provider(view, true);
        }
        if ("task".equals(detail)) return task(view);
        if ("summary".equals(detail)) return summary(view, snapshot);
        return details(view);
    }

    private List<String> details(InteractiveSessionViewModel view) {
        List<String> lines = new ArrayList<>();
        lines.add("Operational status: " + view.operationalStatus().primary());
        lines.add("Observed: " + view.observedAt());
        lines.add("Workspace: " + view.workspace().label());
        lines.add("Thread: " + id(view.thread()));
        lines.add("Task: " + id(view.task()));
        lines.add("Run: " + id(view.run()));
        lines.add("Generation: " + id(view.generation()));
        lines.add("Context: "+viewContext(view.context()));
        lines.add("Plan: " + plan(view.plan()));
        lines.add("Provider configured: " + view.provider().configurationState());
        lines.add("Provider projection: " + view.provider().availabilityState());
        lines.add("Provider readiness: " + view.provider().readinessState());
        lines.add("Provider last call: " + view.provider().lastCallState());
        if (!view.operationalStatus().warnings().isEmpty()) {
            lines.add("Warnings: " + String.join(", ", view.operationalStatus().warnings()));
        }
        lines.add("Allowed action hints: "
                + String.join(", ", view.operationalStatus().allowedActionHints()));
        lines.add("Prohibited action hints: "
                + String.join(", ", view.operationalStatus().prohibitedActionHints()));
        lines.add("Next: " + view.operationalStatus().userPrompt());
        lines.addAll(task(view));
        lines.addAll(provider(view, true));
        return List.copyOf(lines);
    }

    private List<String> summary(InteractiveSessionViewModel view,
            StatusSnapshotFacts snapshot) {
        List<String> lines = new ArrayList<>();
        lines.add(InlineTranscriptLayout.titledDivider(
                "PaiCLI " + safe(snapshot.applicationVersion()) + " · Status",
                InlineTranscriptLayout.DEFAULT_WIDTH));
        lines.add(row("Session", friendly(
                view.operationalStatus().primary().name())));
        lines.add(row("Workspace", safe(view.workspace().label())));
        lines.add("");
        if (meaningful(view.provider().provider()) || meaningful(view.provider().model())) {
            lines.add(row("Provider", join(" · ",
                    providerName(view.provider().provider()),
                    view.provider().model())));
        }
        lines.add(row("Policy", "approval " + safe(snapshot.approvalMode())
                + " · verification " + safe(snapshot.verificationMode())));
        lines.add("");
        if (view.task().isPresent()) {
            lines.add(row(snapshot.activeTask() ? "Current task" : "Last task",
                    friendly(view.task().value().status())));
        }
        if (view.run().isPresent()) {
            lines.add(row(snapshot.activeTask() ? "Current run" : "Last run",
                    friendly(view.run().value().status())));
        }
        lines.add(row("Runtime", friendly(snapshot.runtimePhase())));
        if (view.context().isPresent()
                && view.context().value().contextLimit() != null
                && !"UNKNOWN_LIMIT".equals(view.context().value().limitProvenance())) {
            ContextGenerationView context = view.context().value();
            int percent = (int) Math.min(100, Math.round(
                    context.estimatedTotal() * 100.0 / context.contextLimit()));
            lines.add(row("Context", number(context.estimatedTotal()) + " / "
                    + number(context.contextLimit()) + " tokens · " + percent + "%"));
        }
        if (snapshot.lastVerification() != null
                && !snapshot.lastVerification().isBlank()) {
            lines.add(row("Verification", friendly(snapshot.lastVerification())));
        }
        String error = actionableError(snapshot.lastActionableError(),
                view.provider().lastErrorCategory());
        if (error != null) {
            lines.add("");
            lines.add("  Last error");
            lines.add("  " + InlineTranscriptLayout.fit(error,
                    InlineTranscriptLayout.DEFAULT_WIDTH - 2));
        }
        lines.add("");
        lines.add(row("Next", friendlyNext(view.operationalStatus().userPrompt())));
        lines.add(InlineTranscriptLayout.divider(
                InlineTranscriptLayout.DEFAULT_WIDTH));
        return List.copyOf(lines);
    }

    public record StatusSnapshotFacts(String applicationVersion, String sessionStatus,
            String approvalMode, String verificationMode, String verificationSource,
            String runtimePhase, String lastVerification, String lastActionableError,
            boolean activeTask) {
        public static StatusSnapshotFacts defaults() {
            return new StatusSnapshotFacts("1.2.0", "active", "not configured",
                    "off", "none", "idle", null, null, false);
        }
    }

    private static String friendly(String value) {
        if (value == null || value.isBlank()) return "not available";
        String normalized = value.replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private static String locality(String provider) {
        return "ollama".equalsIgnoreCase(provider) ? "local" : "remote";
    }

    private static String readiness(ProviderView.ReadinessState readiness) {
        return switch (readiness) {
            case HEALTHY -> "ready";
            case STALE -> "stale";
            case DEGRADED -> "degraded";
            case UNAVAILABLE -> "unavailable";
            case READINESS_UNKNOWN -> "not checked";
        };
    }

    private static String provenance(String value) {
        return switch (value) {
            case "PROVIDER_REPORTED_LIMIT" -> "provider reported";
            case "CONFIGURED_LIMIT" -> "configured";
            case "MODEL_CATALOG_LIMIT" -> "model catalog";
            default -> "verified source";
        };
    }

    private static String actionableError(String persisted, String providerCategory) {
        String value = persisted == null || persisted.isBlank() ? providerCategory : persisted;
        if (value == null || value.isBlank()) return null;
        if (value.contains("CONTEXT_BUDGET_UNKNOWN")) {
            return "Context budget unavailable; verify model metadata or configure a trusted limit.";
        }
        if (value.contains("PROVIDER_CONNECTION_FAILURE")) return "Provider connection failed.";
        if (value.contains("PROVIDER_TIMEOUT")) return "Provider request timed out.";
        if (value.contains("PROVIDER_MODEL_UNAVAILABLE")) return "Configured model is unavailable.";
        if (value.contains("PROVIDER_MALFORMED_RESPONSE")) return "Provider response was malformed.";
        if (value.contains("STRUCTURED_PLAN")) return "Structured plan could not be parsed.";
        return "Task failed; use /status --details or /report for diagnostics.";
    }

    private static String viewContext(ViewFact<ContextGenerationView> fact){
        if(!fact.isPresent())return fact.state().name();
        ContextGenerationView value=fact.value();
        return value.lifecycle()+" / "+value.budgetState();
    }

    public List<String> plan(InteractiveSessionViewModel view) {
        if (!view.plan().isPresent()) {
            return List.of("Plan: " + view.plan().state());
        }
        PlanView plan = view.plan().value();
        List<String> lines = new ArrayList<>();
        lines.add("Plan revision: " + plan.revisionId());
        lines.add("Plan canonical schema: V" + plan.canonicalSchemaVersion());
        lines.add("Plan lifecycle: " + plan.lifecycle());
        lines.add("Plan applicability: " + plan.applicability());
        lines.add("Plan approved: " + (plan.approved() ? "yes" : "no"));
        boolean advisory="ADVISORY".equals(plan.applicability());
        if(advisory){
            lines.add(com.paicli.interactive.approval.CanonicalPlanPresentation.ADVISORY_WARNING);
        }
        for (StepView step : plan.steps()) {
            lines.add((step.order() + 1) + ". " + step.state() + " | " + step.title()
                    + " | attempt=" + step.currentAttemptNumber());
            if(advisory){
                lines.add("   Progress: advisory / best effort");
                continue;
            }
            if(plan.canonicalSchemaVersion()<3){
                lines.add("   Structured requirements: unavailable in V"
                        +plan.canonicalSchemaVersion()+" (not fabricated)");
                continue;
            }
            int required=step.requirements().stream()
                    .mapToInt(com.paicli.interactive.view.PlanRequirementView::requiredSlots).sum();
            int satisfied=step.requirements().stream()
                    .mapToInt(com.paicli.interactive.view.PlanRequirementView::satisfiedSlots).sum();
            lines.add("   Requirements: "+step.requirements().size()
                    +" | required slots: "+satisfied+"/"+required);
            for(var requirement:step.requirements()){
                lines.add("   - "+requirement.toolName()+" | "+requirement.selectorKind()
                        +" | "+requirement.safeSelectorSummary()+" | slots "
                        +requirement.satisfiedSlots()+"/"+requirement.requiredSlots());
            }
        }
        if(advisory){
            lines.add("Task completion is determined by runtime Tool durability and the final Provider result.");
        }else{
            lines.add("Evidence: " + evidence(view.evidence()));
            lines.add("Completion: " + fact(view.completion()));
        }
        return List.copyOf(lines);
    }

    public List<String> provider(InteractiveSessionViewModel view, boolean usageDetails) {
        if (!usageDetails) return providerSummary(view);
        ProviderView provider = view.provider();
        List<String> lines = new ArrayList<>();
        lines.add("Provider: " + safe(provider.provider()));
        lines.add("Model: " + safe(provider.model()));
        lines.add("Source: " + safe(provider.source()));
        lines.add("Projection: " + provider.availabilityState());
        lines.add("Configuration: " + provider.configurationState());
        lines.add("Readiness: " + provider.readinessState());
        lines.add("Last call: " + provider.lastCallState());
        lines.add("Last operation: " + safe(provider.lastOperationKind()));
        lines.add("Last outcome: " + safe(provider.lastOutcome()));
        lines.add("Last error category: " + safe(provider.lastErrorCategory()));
        lines.add("Last latency ms: " + nullable(provider.lastLatencyMs()));
        lines.add("Last observed: " + safe(provider.lastObservedAt()));
        lines.add("Diagnostic reference: " + safe(provider.diagnosticRef()));
        lines.add("Freshness: " + (provider.lastObservedAt() == null ? "UNKNOWN" : "OBSERVED"));
        if (usageDetails) {
            lines.add("Usage: " + provider.usageState());
            lines.add("Input tokens: " + nullable(provider.inputTokens()));
            lines.add("Output tokens: " + nullable(provider.outputTokens()));
            lines.add("Total tokens: " + nullable(provider.totalTokens()));
            lines.add("Session calls: " + provider.sessionCallCount());
            lines.add("Session input tokens: " + nullable(provider.sessionInputTokens()));
            lines.add("Session output tokens: " + nullable(provider.sessionOutputTokens()));
            lines.add("Session total tokens: " + nullable(provider.sessionTotalTokens()));
            lines.add("Task calls: " + provider.taskCallCount());
            lines.add("Task input tokens: " + nullable(provider.taskInputTokens()));
            lines.add("Task output tokens: " + nullable(provider.taskOutputTokens()));
            lines.add("Task total tokens: " + nullable(provider.taskTotalTokens()));
        }
        return List.copyOf(lines);
    }

    private List<String> providerSummary(InteractiveSessionViewModel view) {
        ProviderView provider = view.provider();
        List<String> lines = new ArrayList<>();
        lines.add(InlineTranscriptLayout.titledDivider("Provider Status",
                InlineTranscriptLayout.DEFAULT_WIDTH));
        if (meaningful(provider.provider())) {
            lines.add(row("Provider", providerName(provider.provider())));
        }
        if (meaningful(provider.model())) lines.add(row("Model", provider.model()));
        if (meaningful(provider.thinkingMode()))
            lines.add(row("Thinking", friendly(provider.thinkingMode())));
        if (meaningful(provider.reasoningEffort()))
            lines.add(row("Effort", provider.reasoningEffort().toLowerCase(java.util.Locale.ROOT)));
        if (meaningful(provider.provider())) {
            lines.add(row("Endpoint", locality(provider.provider()).equals("local")
                    ? "Local" : "Remote"));
        }
        lines.add(row("Health", friendly(readiness(provider.readinessState()))));
        if(provider.healthObservedAt()!=null){
            lines.add(row("Checked",relativeAge(view.observedAt(),provider.healthObservedAt())));
            lines.add(row("Freshness",provider.readinessState()==ProviderView.ReadinessState.STALE
                    ?"Stale":"Fresh"));
        }
        if (provider.lastCallState() != ProviderView.LastCallState.UNKNOWN) {
            lines.add("");
            String operation = meaningful(provider.lastOperationKind())
                    ? friendly(provider.lastOperationKind()) : null;
            String call = join(" · ", operation,
                    callOutcome(provider.lastCallState()), duration(provider.lastLatencyMs()));
            lines.add(row("Last call", call));
        }
        if (provider.inputTokens() != null || provider.outputTokens() != null
                || provider.totalTokens() != null) {
            lines.add(row("Usage", tokenUsage(provider)));
        }
        if (provider.sessionCallCount() > 0) {
            lines.add(row("Calls", provider.sessionCallCount() + " this session"));
        }
        String error = actionableError(null, provider.lastErrorCategory());
        if (error != null) {
            lines.add("");
            lines.add("  Last error");
            lines.add("  " + InlineTranscriptLayout.fit(error,
                    InlineTranscriptLayout.DEFAULT_WIDTH - 2));
        }
        lines.add(InlineTranscriptLayout.divider(
                InlineTranscriptLayout.DEFAULT_WIDTH));
        return List.copyOf(lines);
    }

    private static String row(String label, String value) {
        String cleanLabel = label == null ? "" : label.replace('\r', ' ')
                .replace('\n', ' ');
        String cleanValue = value == null ? "" : value.replace('\r', ' ')
                .replace('\n', ' ');
        String line = "  " + String.format(java.util.Locale.ROOT,
                "%-12s", cleanLabel) + cleanValue;
        return InlineTranscriptLayout.fit(line,
                InlineTranscriptLayout.DEFAULT_WIDTH);
    }

    private static String join(String separator, String... values) {
        return java.util.Arrays.stream(values).filter(InteractiveViewModelRenderer::meaningful)
                .collect(java.util.stream.Collectors.joining(separator));
    }

    private static boolean meaningful(String value) {
        return value != null && !value.isBlank()
                && !java.util.Set.of("none", "unknown", "not exposed")
                        .contains(value.toLowerCase(java.util.Locale.ROOT));
    }

    private static String providerName(String value) {
        if (!meaningful(value)) return null;
        return "ollama".equalsIgnoreCase(value) ? "Ollama" : friendly(value);
    }

    private static String callOutcome(ProviderView.LastCallState state) {
        return switch (state) {
            case LAST_CALL_SUCCEEDED -> "succeeded";
            case LAST_CALL_FAILED -> "failed";
            case LAST_CALL_CANCELLED -> "cancelled";
            case LAST_CALL_INCOMPLETE -> "incomplete";
            case UNKNOWN -> null;
        };
    }

    private static String duration(Long millis) {
        if (millis == null) return null;
        return String.format(java.util.Locale.ROOT, "%.1fs", millis / 1000.0);
    }

    private static String relativeAge(Instant capturedAt,Instant observedAt){
        long seconds=Math.max(0,java.time.Duration.between(observedAt,capturedAt).toSeconds());
        if(seconds<60)return seconds+"s ago";
        long minutes=seconds/60;
        if(minutes<60)return minutes+"m ago";
        return (minutes/60)+"h ago";
    }

    private static String tokenUsage(ProviderView provider) {
        List<String> parts = new ArrayList<>();
        if (provider.inputTokens() != null) parts.add(number(provider.inputTokens()) + " input");
        if (provider.outputTokens() != null) parts.add(number(provider.outputTokens()) + " output");
        if (provider.totalTokens() != null) parts.add(number(provider.totalTokens()) + " total");
        return String.join(" · ", parts);
    }

    private static String number(Number value) {
        return String.format(java.util.Locale.ROOT, "%,d", value.longValue());
    }

    private static String friendlyNext(String value) {
        if (!meaningful(value)) return "Use /help for available actions.";
        String clean = value.strip();
        return clean.endsWith(".") ? clean : clean + ".";
    }

    public String prompt(InteractiveSessionViewModel view) {
        return "paicli [" + view.operationalStatus().primary().name().toLowerCase()
                .replace('_', '-') + "]> ";
    }

    private List<String> task(InteractiveSessionViewModel view) {
        List<String> lines = new ArrayList<>();
        lines.add("Task fact: " + taskFact(view.task()));
        lines.add("Run fact: " + runFact(view.run()));
        lines.add("Generation fact: " + generationFact(view.generation()));
        lines.add("Recovery: " + recoveryFact(view.recovery()));
        lines.add("Steering: " + steeringFact(view.steering()));
        lines.add("Follow-up: " + followUpFact(view.followUp()));
        lines.add("Failure domain: " + fact(view.operationalStatus().failureDomain()));
        return List.copyOf(lines);
    }

    private static String id(ViewFact<?> fact) {
        if (!fact.isPresent()) return fact.state().name();
        Object value = fact.value();
        if (value instanceof ThreadView v) return shortId(v.threadId()) + "@" + v.headVersion();
        if (value instanceof TaskView v) return shortId(v.taskId()) + " (" + v.status() + ")";
        if (value instanceof RunView v) return shortId(v.runId()) + " (" + v.status() + ")";
        if (value instanceof GenerationView v) return shortId(v.generationId()) + "@" + v.pointerVersion();
        return "PRESENT";
    }

    private static String plan(ViewFact<PlanView> fact) {
        return fact.isPresent() ? shortId(fact.value().revisionId()) + " (" + fact.value().lifecycle() + ")"
                : fact.state().name();
    }
    private static String evidence(ViewFact<EvidenceView> fact) {
        return fact.isPresent() ? fact.value().succeeded() + "/" + fact.value().total()
                + " succeeded" : fact.state().name();
    }
    private static String fact(ViewFact<?> fact) {
        return fact.isPresent() ? String.valueOf(fact.value()) : fact.state().name();
    }
    private static String taskFact(ViewFact<TaskView> fact) {
        return fact.isPresent() ? shortId(fact.value().taskId()) + " ("
                + fact.value().status() + ") manifest="
                + (fact.value().manifestId() == null ? "absent" : "bound")
                : fact.state().name();
    }
    private static String runFact(ViewFact<RunView> fact) {
        return fact.isPresent() ? shortId(fact.value().runId()) + " ("
                + fact.value().status() + ")" : fact.state().name();
    }
    private static String generationFact(ViewFact<GenerationView> fact) {
        return fact.isPresent() ? shortId(fact.value().generationId()) + "@"
                + fact.value().pointerVersion() + " (" + fact.value().state() + ")"
                : fact.state().name();
    }
    private static String recoveryFact(ViewFact<RecoveryView> fact) {
        return fact.isPresent() ? fact.value().status() + " eligible="
                + (fact.value().eligible() ? "yes" : "no") : fact.state().name();
    }
    private static String steeringFact(ViewFact<SteeringView> fact) {
        return fact.isPresent() ? shortId(fact.value().steeringId()) + " ("
                + fact.value().state() + ")" : fact.state().name();
    }
    private static String followUpFact(ViewFact<FollowUpView> fact) {
        return fact.isPresent() ? shortId(fact.value().capsuleId()) + " ("
                + fact.value().candidateState() + ") child-start="
                + (fact.value().childInitialRunPending() ? "pending" : "no")
                : fact.state().name();
    }
    private static String providerCalls(TaskReportView.ScopedFact<
            TaskReportView.ProviderUsageView> fact){
        return fact.isPresent()?Integer.toString(fact.value().callCount()):scopedState(fact);
    }
    private static String providerTokens(TaskReportView.ScopedFact<
            TaskReportView.ProviderUsageView> fact){
        if(!fact.isPresent())return scopedState(fact);
        return "input="+nullable(fact.value().inputTokens())+" output="
                +nullable(fact.value().outputTokens())+" total="
                +nullable(fact.value().totalTokens())+" coverage="+fact.value().coverage();
    }
    private static String taskStatus(TaskReportView.ScopedFact<TaskView> fact){
        return fact.isPresent()?fact.value().status():scopedState(fact);
    }
    private static String providerIdentity(TaskReportView.ScopedFact<
            TaskReportView.LegacyTaskView> fact){
        return fact.isPresent()?safe(fact.value().provider())+" / "+safe(fact.value().model()):
                scopedState(fact);
    }
    private static String legacyRunPhase(TaskReportView.ScopedFact<
            TaskReportView.LegacyRunView> fact){
        return fact.isPresent()?safe(fact.value().phase()):scopedState(fact);
    }
    private static String r1cPlan(TaskReportView.ScopedFact<PlanView> fact){
        return fact.isPresent()?shortId(fact.value().revisionId())+" ("
                +fact.value().lifecycle()+") ["+fact.scope()+"]":scopedState(fact);
    }
    private static String legacyPlan(TaskReportView.ScopedFact<
            TaskReportView.LegacyPlanView> fact){
        return fact.isPresent()?fact.value().status()+" ["+fact.scope()+"]":scopedState(fact);
    }
    private static String verification(TaskReportView.ScopedFact<
            TaskReportView.VerificationView> fact){
        return fact.isPresent()?fact.value().status()+" ["+fact.scope()+"]":scopedState(fact);
    }
    private static String repair(TaskReportView.ScopedFact<TaskReportView.RepairView> fact){
        if(!fact.isPresent())return scopedState(fact);
        TaskReportView.RepairView value=fact.value();
        return "started="+value.started()+" completed="+value.completed()+" failed="
                +value.failed()+" running="+value.running()+" ["+fact.scope()+"]";
    }
    private static String code(TaskReportView.ScopedFact<TaskReportView.CodeFactsView> fact){
        if(!fact.isPresent())return scopedState(fact);
        return fact.value().status()+" indexed="+fact.value().indexedJavaFiles()
                +" contexts="+fact.value().contextItems()+" ["+fact.scope()+"]";
    }
    private static String changes(TaskReportView.ScopedFact<
            TaskReportView.ChangeFactsView> fact){
        if(!fact.isPresent())return scopedState(fact);
        TaskReportView.ChangeFactsView value=fact.value();
        return "proposals="+value.proposals()+" applied="+value.applied()+" rejected="
                +value.rejected()+" cancelled="+value.cancelled()+" diff=+"
                +value.additions()+"/-"+value.deletions()+" snapshots="+value.snapshots()
                +" restored="+value.restoredSnapshots()+" ["+fact.scope()+"]";
    }
    private static String scopedState(TaskReportView.ScopedFact<?> fact){
        return fact.scope()+"/"+fact.state();
    }
    private static String reportId(String value){
        if(value==null)return "none";
        return value.length()<=16?value:value.substring(0,7)+"…"+value.substring(value.length()-8);
    }
    private static String shortId(String value) {
        return value == null ? "none" : value.substring(0, Math.min(16, value.length()));
    }
    private static String nullable(Object value) { return value == null ? "NOT_EXPOSED" : value.toString(); }
    private static String safe(Object value) { return value == null ? "none" : value.toString(); }
}
