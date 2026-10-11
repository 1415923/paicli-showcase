package com.paicli.interactive.render;

import com.paicli.event.AgentEvent;
import com.paicli.event.AgentEvents;
import com.paicli.interactive.session.InteractiveSession;
import com.paicli.interactive.task.TaskSubmissionResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import com.fasterxml.jackson.databind.ObjectMapper;

final class EventLineFormatter {
    static final String TRUNCATION_SUFFIX = "… [truncated]";
    private static final int TRUNCATION_SUFFIX_CODE_POINTS =
            TRUNCATION_SUFFIX.codePointCount(0, TRUNCATION_SUFFIX.length());
    static final int MAX_TRANSCRIPT_LINE_CODE_POINTS = 4096;
    static final int MAX_SHELL_LINES = 100;
    static final int MAX_SHELL_FIELD_CODE_POINTS = 1024;
    static final int MAX_TASK_SUMMARY_CODE_POINTS = 2048;
    static final int MAX_REASON_CODE_POINTS = 1024;
    static final int MAX_ERROR_DETAIL_CODE_POINTS = 1024;
    static final int MAX_RELATIVE_PATH_CODE_POINTS = 512;
    static final int MAX_PATH_LIST_CODE_POINTS = 2048;
    static final int MAX_PATH_ITEMS = 8;
    static final int MAX_TOOL_NAME_CODE_POINTS = 128;
    static final int MAX_TYPED_CODE_CODE_POINTS = 64;
    static final int MAX_TASK_ID_CODE_POINTS = 128;
    static final int MAX_GUIDANCE_CODE_POINTS = 512;
    private static final Pattern ANSI =
            Pattern.compile("\\x1B\\[[0-?]*[ -/]*[@-~]");
    private static final Pattern WINDOWS_ABSOLUTE_PATH=Pattern.compile(
            "(?i)(?<![A-Za-z0-9_])(?:[A-Z]:[\\\\/]|\\\\\\\\)[^\\s\\\"'<>|]+");
    private static final Pattern UNIX_ABSOLUTE_PATH=Pattern.compile(
            "(?<![A-Za-z0-9_])/(?:[^\\s/]+/)+[^\\s,;:)]*");
    private static final ObjectMapper JSON=new ObjectMapper();

    private EventLineFormatter() { }

    static String format(AgentEvent event) {
        return format(event, RuntimeEventCoalescer.TaskFacts.empty());
    }

    static String format(AgentEvent event, RuntimeEventCoalescer.TaskFacts facts) {
        String block = switch (event) {
            case AgentEvents.SessionStarted e -> "[Session] "
                    + field(e.provider()) + " / " + value(e.model());
            case AgentEvents.TaskStarted e -> taskStarted(e);
            case AgentEvents.PlanCreated e -> planCreated(e);
            case AgentEvents.PlanUpdated e -> planUpdated(e);
            case AgentEvents.PhaseChanged e -> "[Phase] " + field(e.phase());
            case AgentEvents.ModelMessage e -> modelMessage(e);
            case AgentEvents.ContextLifecycle e -> e.boundedSummary().startsWith("Context compacted")
                    ?"• "+taskSummary(e.boundedSummary()):"[Context] "+taskSummary(e.boundedSummary());
            case AgentEvents.CodeContextSelected e -> codeContext(e);
            case AgentEvents.ToolBatchStarted e -> "[Tools] Running "+e.size()+" "
                    +field(e.effectClass())+" calls";
            case AgentEvents.ToolRequested e -> toolRequested(e);
            case AgentEvents.ToolApproved e -> toolLine(
                    e.toolName(), "approved", null);
            case AgentEvents.ToolRejected e -> toolLine(
                    e.toolName(), "rejected", e.boundedReason());
            case AgentEvents.ToolCancelled e -> toolCancelled(e);
            case AgentEvents.ToolCompleted e -> toolCompleted(e);
            case AgentEvents.ToolFailed e -> toolFailed(e);
            case AgentEvents.ToolBatchCompleted e -> "[Tools] Batch completed · "
                    +e.succeeded()+" succeeded · "+e.failed()+" failed";
            case AgentEvents.FinalResponse e -> finalResponse(e);
            case AgentEvents.ApprovalRequested e -> approval(e);
            case AgentEvents.ApprovalResolved e -> "[Approval] "
                    + approvalDecision(e.decision())
                    + (e.taskScoped() ? " (task-scoped)" : "");
            case AgentEvents.FileRead e -> "[Read] " + relativePath(e.relativePath());
            case AgentEvents.DiffProposed e -> "[Proposed Edit] "
                    + relativePath(e.relativePath()) + " +" + e.additions()
                    + " -" + e.deletions();
            case AgentEvents.SnapshotCreated e -> "[Snapshot] "
                    + relativePath(e.relativePath());
            case AgentEvents.FileWritten e -> "[Write] "
                    + relativePath(e.relativePath());
            case AgentEvents.VerificationStarted e -> "[Verify] started "
                    + field(e.policyLabel());
            case AgentEvents.VerificationFailed e -> "[Verify] failed "
                    + counts(e.tests(), e.failures(), e.errors(), e.skipped())
                    + " " + field(e.category()) + ": " + reason(e.boundedExcerpt());
            case AgentEvents.VerificationSkipped e -> "[Verify] skipped: "
                    + reason(e.reason());
            case AgentEvents.RepairStarted e -> "[Repair] attempt " + e.attempt();
            case AgentEvents.VerificationPassed e -> "[Verify] passed "
                    + counts(e.tests(), e.failures(), e.errors(), e.skipped());
            case AgentEvents.TaskCompleted e -> completed(e, facts);
            case AgentEvents.TaskFailed e -> failed(e, facts);
            case AgentEvents.TaskCancelled e -> cancelled(e, facts);
            case AgentEvents.TaskInconclusive e -> inconclusive(e, facts);
            case AgentEvents.CancellationRequested e ->
                    "[Cancel] Cooperative cancellation requested";
            case AgentEvents.CancellationForced e ->
                    "[Cancel] Forced owned processes: "
                    + e.terminatedOwnedProcesses()
                    + " (external outcome may be unknown)";
            case AgentEvents.SessionEnded e -> "[Session] ended " + reason(e.reason());
        };
        return transcriptBlock(block);
    }

    static List<String> transcriptLines(String value) {
        String normalized = ANSI.matcher(value == null ? "" : value).replaceAll("")
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        String[] sourceLines = normalized.split("\n", -1);
        List<String> lines = new ArrayList<>(sourceLines.length);
        for (String sourceLine : sourceLines) {
            lines.add(transcriptLine(sourceLine));
        }
        return List.copyOf(lines);
    }

    static String transcriptLine(String value) {
        return truncate(cleanLine(value), MAX_TRANSCRIPT_LINE_CODE_POINTS);
    }

    static List<String> shellLines(List<String> lines) {
        if (lines == null) return List.of();
        List<String> result = new ArrayList<>(MAX_SHELL_LINES + 1);
        long truncatedLines = 0;
        for (String line : lines) {
            for (String transcriptLine : transcriptLines(line)) {
                if (result.size() < MAX_SHELL_LINES) {
                    result.add(transcriptLine);
                } else {
                    truncatedLines++;
                }
            }
        }
        if (truncatedLines > 0) {
            result.removeLast();
            truncatedLines++;
            result.add(linesTruncatedMarker(truncatedLines));
        }
        return List.copyOf(result);
    }

    static List<String> startupLines(String applicationVersion,
            InteractiveSession.Snapshot session) {
        return List.of(
                "PaiCLI " + shellField(applicationVersion) + " Interactive",
                "workspace: " + shellField(session.workspaceLabel()),
                "terminal: " + session.terminalMode().name().toLowerCase(Locale.ROOT),
                "provider: " + shellField(session.provider()),
                "model: " + shellField(session.model()),
                "mode: " + shellField(session.approvalMode()),
                "verification: " + session.verificationMode() + " ("
                        + shellField(session.verificationSource()) + ")",
                "verification policy: " + shellField(session.verificationPreset()),
                "");
    }

    static List<String> taskResultLines(TaskSubmissionResult result) {
        return List.of(transcriptLine("[Task] " + taskSummary(result.message())));
    }

    static String warningLine(String message) {
        return transcriptLine("[Warning] " + reason(message));
    }

    static List<String> errorLines(String code, String message) {
        return errorLines(code, message,
                "Correct the issue and retry; use /help for available commands.");
    }

    static String toolLine(String name, String state, String detail) {
        String normalizedState = cleanInline(state).toLowerCase(Locale.ROOT);
        return switch (normalizedState) {
            case "requested", "approved", "completed" -> transcriptLine(
                    "[Tool] " + toolName(name) + " " + normalizedState);
            case "rejected", "cancelled", "failed" -> transcriptLine(
                    "[Tool] " + toolName(name) + " " + normalizedState
                            + ": " + reason(detail));
            default -> throw new IllegalArgumentException("unsupported tool state");
        };
    }

    private static String toolCancelled(AgentEvents.ToolCancelled event){
        String displayName=event.batchSize()>1?event.toolName()+" · "+event.batchIndex()
                +" of "+event.batchSize():event.toolName();
        return toolLine(displayName,"cancelled",event.boundedReason());
    }

    private static String planCreated(AgentEvents.PlanCreated event){
        StringBuilder out=new StringBuilder("[Plan] ").append(event.stepCount()).append(" steps");
        if(event.planRevisionId()!=null)out.append(" · revision ")
                .append(shortReference(event.planRevisionId()));
        int number=1;for(String step:event.boundedSteps())out.append('\n').append("│ ")
                .append(number++).append(". ").append(taskSummary(step));
        int remaining=event.stepCount()-event.boundedSteps().size();
        if(remaining>0)out.append('\n').append("│ … ").append(remaining)
                .append(" more step(s) not shown");
        return out.toString();
    }

    private static String planUpdated(AgentEvents.PlanUpdated event){
        StringBuilder out=new StringBuilder("[Plan] Updated");
        for(String step:event.boundedSteps())out.append('\n').append("│ ")
                .append(taskSummary(step));
        return out.toString();
    }

    private static String modelMessage(AgentEvents.ModelMessage event){
        String content=taskSummary(event.boundedContent());
        return "CONCLUSION".equals(event.kind())?"[Update] "+content:content;
    }

    private static String toolRequested(AgentEvents.ToolRequested event){
        String action=event.sameRequestOrdinal()>1?"Running again":"Running";
        StringBuilder out=new StringBuilder("• ").append(action).append(' ')
                .append(toolName(event.toolName()));
        if(event.batchSize()>1)out.append(" · ").append(event.batchIndex())
                .append(" of ").append(event.batchSize());
        if(event.sameRequestOrdinal()>1)out.append(" · repeated call ")
                .append(event.sameRequestOrdinal());
        appendToolField(out,"Target",event.relativePath());
        appendArguments(out,event.boundedArgumentsSummary());
        appendToolField(out,"Access",event.effectClass());
        appendToolField(out,"Step",event.stepLabel());
        if(event.attempt()>1)appendToolField(out,"Attempt",Integer.toString(event.attempt()));
        return out.toString();
    }

    private static String toolCompleted(AgentEvents.ToolCompleted event){
        boolean reused=event.boundedFacts().stream()
                .anyMatch(fact->fact.equals("Evidence: cached reuse"));
        if(reused){
            StringBuilder cached=new StringBuilder("• Reused ")
                    .append(toolName(event.toolName())).append(" evidence");
            for(String fact:event.boundedFacts()){
                if(fact.equals("Evidence: cached reuse"))continue;
                cached.append('\n').append("│ ").append(taskSummary(fact));
            }
            cached.append('\n').append("└─ ")
                    .append(resultCountLabel(event.toolName(),Math.max(0,event.itemCount())))
                    .append(" · cached");
            return cached.toString();
        }
        if("run_command".equals(event.toolName()))return commandCompleted(event);
        StringBuilder out=new StringBuilder("• Ran ").append(toolName(event.toolName()));
        if(event.batchSize()>1)out.append(" · ").append(event.batchIndex())
                .append(" of ").append(event.batchSize());
        if(event.sameRequestOrdinal()>1)out.append(" · repeated call ")
                .append(event.sameRequestOrdinal());
        if(event.itemCount()>=0)out.append('\n').append("├─ ")
                .append(resultCountLabel(event.toolName(),event.itemCount()));
        int shown=0;for(String item:event.boundedItems()){
            out.append('\n').append("│  ").append(transcriptLine(item));shown++;
        }
        int remaining=event.itemCount()<0?0:event.itemCount()-shown;
        if(remaining>0)out.append('\n').append("│  … ").append(remaining).append(" more");
        for(String fact:event.boundedFacts())out.append('\n').append("├─ ")
                .append(taskSummary(fact));
        if(event.truncated())out.append('\n').append("│  Output truncated");
        out.append('\n').append("└─ Completed · ").append(duration(event.elapsedMillis()));
        return out.toString();
    }

    private static String commandCompleted(AgentEvents.ToolCompleted event){
        StringBuilder out=new StringBuilder("• Ran command");
        java.util.List<String> stdout=event.boundedItems().stream()
                .filter(item->item.startsWith("stdout | "))
                .map(item->item.substring("stdout | ".length())).toList();
        java.util.List<String> stderr=event.boundedItems().stream()
                .filter(item->item.startsWith("stderr | "))
                .map(item->item.substring("stderr | ".length())).toList();
        appendCommandStream(out,"stdout",stdout);
        appendCommandStream(out,"stderr",stderr);
        String exitFact=event.boundedFacts().stream()
                .filter(fact->fact.startsWith("Exit code: ")).findFirst().orElse(null);
        String statusFact=event.boundedFacts().stream()
                .filter(fact->fact.startsWith("Status: ")).findFirst().orElse(null);
        for(String fact:event.boundedFacts()){
            if(fact.equals(exitFact)||fact.equals(statusFact))continue;
            out.append('\n').append("├─ ").append(taskSummary(fact));
        }
        int exitCode=parseExitCode(exitFact);
        boolean failed=exitCode!=0||statusFact!=null&&statusFact.contains("NON_ZERO");
        out.append('\n').append("└─ ").append(failed?"Failed":"Completed");
        if(exitFact!=null)out.append(" · exit ").append(exitCode);
        out.append(" · ").append(duration(event.elapsedMillis()));
        return out.toString();
    }

    private static void appendCommandStream(StringBuilder out,String label,
            java.util.List<String> lines){
        if(lines.isEmpty())return;
        out.append('\n').append("├─ ").append(label);
        for(String line:lines)out.append('\n').append("│  ").append(transcriptLine(line));
    }

    private static int parseExitCode(String fact){
        if(fact==null)return 0;
        try{return Integer.parseInt(fact.substring("Exit code: ".length()).trim());}
        catch(RuntimeException ignored){return 0;}
    }

    private static String resultCountLabel(String toolName,int count){
        return switch(toolName){
            case "read_file"->"Read "+count+" line(s)";
            case "list_files"->"Found "+count+" entry/entries";
            case "grep"->"Found "+count+" matching line(s)";
            case "run_command"->"Captured "+count+" output line(s)";
            default->"Returned "+count+" item(s)";
        };
    }

    private static String toolFailed(AgentEvents.ToolFailed event){
        StringBuilder out=new StringBuilder("• Failed ").append(toolName(event.toolName()));
        if(event.batchSize()>1)out.append(" · ").append(event.batchIndex())
                .append(" of ").append(event.batchSize());
        if(event.sameRequestOrdinal()>1)out.append(" · repeated call ")
                .append(event.sameRequestOrdinal());
        if(event.stepLabel()!=null)out.append('\n').append("│ Step       ")
                .append(taskSummary(event.stepLabel()));
        if(event.attempt()>1)out.append('\n').append("│ Attempt    ").append(event.attempt());
        out.append('\n').append("├─ ").append(errorDetail(event.boundedError()));
        out.append('\n').append("└─ Failed · ").append(duration(event.elapsedMillis()));
        return out.toString();
    }

    private static void appendArguments(StringBuilder out,String json){
        if(json==null||json.isBlank())return;
        try{
            var node=JSON.readTree(json);
            if(node.isObject())node.fields().forEachRemaining(entry->{
                if("path".equals(entry.getKey()))return;
                appendToolField(out,friendlyKey(entry.getKey()),entry.getValue().isValueNode()
                        ?entry.getValue().asText():entry.getValue().toString());
            });
        }catch(Exception ignored){appendToolField(out,"Arguments",taskSummary(json));}
    }

    private static void appendToolField(StringBuilder out,String label,String value){
        if(value==null||value.isBlank())return;
        out.append('\n').append("│ ").append(String.format("%-11s",label))
                .append(taskSummary(value));
    }

    private static String friendlyKey(String key){
        if(key==null||key.isBlank())return "Argument";
        String value=key.replace('_',' ');return Character.toUpperCase(value.charAt(0))+value.substring(1);
    }

    private static String finalResponse(AgentEvents.FinalResponse event){
        return "[Result]\n\n"+event.boundedContent().strip();
    }

    private static String duration(long millis){
        if(millis<1000)return millis+" ms";
        return String.format(Locale.ROOT,"%.1f s",millis/1000.0);
    }

    private static List<String> errorLines(String code, String message,
            String nextStep) {
        return List.of(
                transcriptLine("[Error] " + typedCode(code) + ": "
                        + errorDetail(message)),
                transcriptLine("Next: " + guidance(nextStep)));
    }

    static String relativePath(String value) {
        return truncate(cleanInline(value), MAX_RELATIVE_PATH_CODE_POINTS);
    }

    static String relativePathList(List<String> paths) {
        if (paths == null || paths.isEmpty()) return "none";
        int displayedItems = Math.min(paths.size(), MAX_PATH_ITEMS);
        int truncatedItems = paths.size() - displayedItems;
        String itemMarker = truncatedItems == 0
                ? null : itemsTruncatedMarker(truncatedItems);
        int separatorCodePoints = Math.max(0, displayedItems - 1) * 2;
        if (itemMarker != null) separatorCodePoints += 2;
        int markerCodePoints = itemMarker == null ? 0
                : itemMarker.codePointCount(0, itemMarker.length());
        int itemCodePointBudget = Math.min(MAX_RELATIVE_PATH_CODE_POINTS,
                (MAX_PATH_LIST_CODE_POINTS - separatorCodePoints
                        - markerCodePoints) / displayedItems);
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < displayedItems; index++) {
            if (index > 0) result.append(", ");
            result.append(truncate(cleanInline(paths.get(index)), itemCodePointBudget));
        }
        if (itemMarker != null) result.append(", ").append(itemMarker);
        return result.toString();
    }

    private static String codeContext(AgentEvents.CodeContextSelected event) {
        String line = "[Explore] indexed " + event.indexedJavaFiles()
                + " Java file(s), " + event.queryCount() + " query(s), "
                + event.contextItemCount() + " untrusted context item(s)";
        return event.relativeFiles().isEmpty() ? line
                : line + " | files: " + relativePathList(event.relativeFiles());
    }

    private static String taskStarted(AgentEvents.TaskStarted event) {
        String started = switch (event.runKind()) {
            case "INITIAL" -> "[Task] Started: "
                    + taskSummary(event.boundedTaskSummary());
            case "RESUME" -> "[Run] Resumed: "
                    + taskSummary(event.boundedTaskSummary());
            default -> "[Run] Started: "
                    + taskSummary(event.boundedTaskSummary());
        };
        return "[Task] Task accepted.\n" + started;
    }

    private static String counts(int tests, int failures, int errors, int skipped) {
        if (tests < 0) return "tests=unknown";
        return tests + " test(s), " + failures + " failed, "
                + errors + " error(s), " + skipped + " skipped";
    }

    private static String approval(AgentEvents.ApprovalRequested event) {
        if("PLAN".equals(event.actionKind())){
            StringBuilder plan=new StringBuilder("[Advisory Plan Confirmation] · revision ")
                    .append(shortReference(event.planRevisionId()))
                    .append(" · schema V").append(event.planSchemaVersion())
                    .append(" · ").append(event.planStepCount()).append(" steps");
            for(var step:event.boundedPlanSteps()){
                plan.append('\n').append("│ ").append(step.order()+1).append(". ")
                        .append(taskSummary(step.boundedTitle()));
                plan.append('\n').append("│    ")
                        .append(taskSummary(step.boundedDescription()));
                plan.append('\n').append("│    Requirements: ")
                        .append(step.requirements().size());
                for(var requirement:step.requirements()){
                    plan.append('\n').append("│      ")
                            .append(field(requirement.toolName())).append(" · ")
                            .append(field(requirement.selectorKind())).append(" · ")
                            .append(taskSummary(requirement.safeSelectorSummary()))
                            .append(" · slots 0/").append(requirement.requiredSlots());
                }
            }
            if(event.warning()!=null)plan.append('\n').append(warningLine(event.warning()));
            return plan.toString();
        }
        String heading = switch (event.actionKind()) {
            case "EDIT" -> "[Proposed Edit]";
            case "NEW_FILE" -> "[Proposed New File] NEW FILE";
            case "COMMAND" -> "[Proposed Command]";
            case "GIT_READ" -> "[Proposed Git Read]";
            default -> "[Proposed Tool Action]";
        };
        StringBuilder line = new StringBuilder(heading);
        if (event.relativePath() != null) {
            line.append(' ').append(relativePath(event.relativePath()));
        }
        if ("EDIT".equals(event.actionKind())
                || "NEW_FILE".equals(event.actionKind())) {
            line.append(" +").append(event.additions())
                    .append(" -").append(event.deletions());
        }
        if (event.boundedSummary() != null
                && !("EDIT".equals(event.actionKind())
                    || "NEW_FILE".equals(event.actionKind()))) {
            line.append(" | ").append(taskSummary(event.boundedSummary()));
        }
        if (event.warning() != null) {
            line.append('\n').append(warningLine(event.warning()));
        }
        return line.toString();
    }

    private static String shortReference(String value){
        String clean=cleanInline(value);
        return clean.length()<=16?clean:clean.substring(0,16);
    }

    private static String completed(AgentEvents.TaskCompleted event,
            RuntimeEventCoalescer.TaskFacts facts) {
        StringBuilder result = new StringBuilder("Files changed: ")
                .append(facts.changedFiles())
                .append("\nSnapshots: ").append(facts.snapshots());
        if (facts.verificationPassed()) {
            result.append("\nTests: ").append(facts.tests() < 0
                    ? "passed" : facts.tests() + " passed");
        } else {
            result.append("\nVerification: ").append(field(event.verificationStatus()));
        }
        result.append("\n[Task] Completed · ")
                .append(elapsed(facts.elapsedMillis()));
        return result.toString();
    }

    private static String failed(AgentEvents.TaskFailed event,
            RuntimeEventCoalescer.TaskFacts facts) {
        String detail = event.boundedExcerpt() == null
                ? field(event.category()) : errorDetail(event.boundedExcerpt());
        return "[Result]\n\nTask did not complete.\nReason: "+detail
                +"\nNo further operations were started after the failure.\n\n"
                +String.join("\n", errorLines(event.category(), detail,
                        "Review the failure details, correct the issue, and retry."))
                + "\n[Task] Failed · " + elapsed(facts.elapsedMillis());
    }

    private static String cancelled(AgentEvents.TaskCancelled event,
            RuntimeEventCoalescer.TaskFacts facts) {
        String result = "[Result]\n\nTask cancelled. Further operations were stopped.";
        if (event.externalOutcomeUnknown()) {
            result += "\n"+warningLine("External outcome is unknown.")
                    + "\nNext: " + guidance(
                            "Confirm external state before retrying the task.");
        }
        return result + "\n[Task] Cancelled · " + elapsed(facts.elapsedMillis());
    }

    private static String inconclusive(AgentEvents.TaskInconclusive event,
            RuntimeEventCoalescer.TaskFacts facts) {
        String result = "[Result]\n\nTask stopped without a conclusive result.\n";
        if (event.boundedSummary() != null && !event.boundedSummary().isBlank()) {
            result += "[Warning] " + reason(event.boundedSummary()) + "\n";
        }
        return result + "[Task] Incomplete · " + elapsed(facts.elapsedMillis());
    }

    private static String elapsed(long millis) {
        if (millis < 60_000L) {
            return String.format(Locale.ROOT, "%.1fs", millis / 1000.0);
        }
        long seconds = millis / 1000L;
        return (seconds / 60L) + "m " + String.format(Locale.ROOT, "%02ds",
                seconds % 60L);
    }

    private static String approvalDecision(String value) {
        return switch (cleanInline(value).toUpperCase(Locale.ROOT)) {
            case "ALLOW", "APPLY", "APPROVE", "APPROVED" -> "approved";
            case "CANCEL", "CANCELLED", "CANCELED" -> "cancelled";
            case "DENY", "REJECT", "REJECTED" -> "rejected";
            default -> "rejected";
        };
    }

    private static String transcriptBlock(String value) {
        return String.join("\n", transcriptLines(value));
    }

    private static String taskId(AgentEvent event) {
        return truncate(cleanInline(event.metadata().taskId()),
                MAX_TASK_ID_CODE_POINTS);
    }

    private static String taskSummary(String value) {
        return truncate(cleanInline(value), MAX_TASK_SUMMARY_CODE_POINTS);
    }

    private static String reason(String value) {
        return truncate(cleanInline(value), MAX_REASON_CODE_POINTS);
    }

    private static String errorDetail(String value) {
        return truncate(cleanInline(value), MAX_ERROR_DETAIL_CODE_POINTS);
    }

    private static String guidance(String value) {
        return truncate(cleanInline(value), MAX_GUIDANCE_CODE_POINTS);
    }

    private static String toolName(String value) {
        return truncate(cleanInline(value), MAX_TOOL_NAME_CODE_POINTS);
    }

    private static String typedCode(String value) {
        return truncate(cleanInline(value), MAX_TYPED_CODE_CODE_POINTS);
    }

    private static String shellField(String value) {
        return truncate(cleanInline(value), MAX_SHELL_FIELD_CODE_POINTS);
    }

    private static String field(String value) {
        return shellField(value);
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "none" : field(value);
    }

    private static String cleanInline(String value) {
        return cleanLine(value).replace('\r', ' ').replace('\n', ' ');
    }

    private static String cleanLine(String value) {
        String clean = ANSI.matcher(value == null ? "" : value).replaceAll("");
        StringBuilder result = new StringBuilder(clean.length());
        for (int offset = 0; offset < clean.length(); offset++) {
            char character = clean.charAt(offset);
            result.append(Character.isISOControl(character) && character != '\t'
                    && character != '\n' && character != '\r' ? '?' : character);
        }
        String bounded=result.toString();
        bounded=WINDOWS_ABSOLUTE_PATH.matcher(bounded)
                .replaceAll("<redacted-absolute-path>");
        return UNIX_ABSOLUTE_PATH.matcher(bounded)
                .replaceAll("<redacted-absolute-path>");
    }

    private static String linesTruncatedMarker(long count) {
        return "… [" + count + " more lines truncated]";
    }

    private static String itemsTruncatedMarker(int count) {
        return "… [" + count + " more items truncated]";
    }

    private static String truncate(String value, int maxCodePoints) {
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= maxCodePoints) return value;
        if (maxCodePoints < TRUNCATION_SUFFIX_CODE_POINTS) {
            throw new IllegalArgumentException("truncation limit is smaller than marker");
        }
        int retainedCodePoints = maxCodePoints - TRUNCATION_SUFFIX_CODE_POINTS;
        StringBuilder result = new StringBuilder(maxCodePoints);
        int offset = 0;
        for (int copied = 0; copied < retainedCodePoints; copied++) {
            int codePoint = value.codePointAt(offset);
            result.appendCodePoint(codePoint);
            offset += Character.charCount(codePoint);
        }
        return result.append(TRUNCATION_SUFFIX).toString();
    }
}
