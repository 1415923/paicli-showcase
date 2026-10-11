package com.paicli.event;

import java.util.List;

public final class AgentEvents {
    private AgentEvents() { }

    private static void validateTestCounts(
            int tests,int failures,int errors,int skipped) {
        if(tests < -1 || failures < -1 || errors < -1 || skipped < -1
                || tests > 1_000_000 || failures > 1_000_000
                || errors > 1_000_000 || skipped > 1_000_000) {
            throw new IllegalArgumentException("invalid verification counts");
        }
    }
    private static AgentEventMetadata type(AgentEventMetadata metadata, AgentEventType expected) {
        if (metadata.eventType() != expected) throw new IllegalArgumentException("event type mismatch");
        return metadata;
    }

    public record SessionStarted(AgentEventMetadata metadata, String workspaceLabel,
            String provider, String model, String approvalMode) implements AgentEvent {
        public SessionStarted { metadata=type(metadata,AgentEventType.SESSION_STARTED); workspaceLabel=EventBounds.required(workspaceLabel,EventBounds.SHORT,"workspaceLabel"); provider=EventBounds.required(provider,EventBounds.SHORT,"provider"); model=EventBounds.optional(model,EventBounds.SHORT,"model"); approvalMode=EventBounds.required(approvalMode,64,"approvalMode"); }
    }
    public record TaskStarted(AgentEventMetadata metadata, String boundedTaskSummary,
            String runKind) implements AgentEvent {
        public TaskStarted(AgentEventMetadata metadata, String boundedTaskSummary) {
            this(metadata, boundedTaskSummary, "INITIAL");
        }
        public TaskStarted {
            metadata=type(metadata,AgentEventType.TASK_STARTED);
            boundedTaskSummary=EventBounds.requiredPreview(
                    boundedTaskSummary,EventBounds.SUMMARY,"boundedTaskSummary");
            runKind=EventBounds.required(runKind,64,"runKind");
        }
    }
    public record PlanCreated(AgentEventMetadata metadata, String boundedPlanSummary,
            int stepCount, List<String> boundedSteps,String planRevisionId) implements AgentEvent {
        public PlanCreated(AgentEventMetadata metadata,String boundedPlanSummary,
                int stepCount,List<String> boundedSteps){
            this(metadata,boundedPlanSummary,stepCount,boundedSteps,null);
        }
        public PlanCreated {
            metadata=type(metadata,AgentEventType.PLAN_CREATED);
            boundedPlanSummary=EventBounds.requiredPreview(
                    boundedPlanSummary,EventBounds.SUMMARY,"boundedPlanSummary");
            if(stepCount<0||stepCount>128)throw new IllegalArgumentException("invalid stepCount");
            boundedSteps=EventBounds.items(boundedSteps,EventBounds.SHORT,"boundedSteps");
            if(boundedSteps.size()>stepCount)throw new IllegalArgumentException("steps exceed stepCount");
            planRevisionId=EventBounds.optional(planRevisionId,128,"planRevisionId");
        }
        public PlanCreated(AgentEventMetadata metadata,String summary){
            this(metadata,summary,0,List.of(),null);
        }
    }
    public record PlanUpdated(AgentEventMetadata metadata,int stepCount,
            List<String> boundedSteps) implements AgentEvent {
        public PlanUpdated {
            metadata=type(metadata,AgentEventType.PLAN_UPDATED);
            if(stepCount<0||stepCount>128)throw new IllegalArgumentException("invalid stepCount");
            boundedSteps=EventBounds.items(boundedSteps,EventBounds.SHORT,"boundedSteps");
        }
    }
    public record ModelMessage(AgentEventMetadata metadata,String kind,
            String boundedContent) implements AgentEvent {
        public ModelMessage {
            metadata=type(metadata,AgentEventType.MODEL_MESSAGE);
            kind=EventBounds.required(kind,32,"kind");
            boundedContent=EventBounds.requiredPreview(boundedContent,EventBounds.SUMMARY,"boundedContent");
        }
    }
    public record ContextLifecycle(AgentEventMetadata metadata,String boundedSummary)
            implements AgentEvent {
        public ContextLifecycle {
            metadata=type(metadata,AgentEventType.CONTEXT_LIFECYCLE);
            boundedSummary=EventBounds.requiredPreview(boundedSummary,EventBounds.SUMMARY,"boundedSummary");
        }
    }
    public record PhaseChanged(AgentEventMetadata metadata, String phase) implements AgentEvent {
        public PhaseChanged { metadata=type(metadata,AgentEventType.PHASE_CHANGED); phase=EventBounds.required(phase,64,"phase"); }
    }
    public record CodeContextSelected(AgentEventMetadata metadata, List<String> relativeFiles,
            List<String> symbols, int contextItemCount, int indexedJavaFiles,
            int queryCount, boolean untrusted) implements AgentEvent {
        public CodeContextSelected {
            metadata=type(metadata,AgentEventType.CODE_CONTEXT_SELECTED);
            relativeFiles=EventBounds.items(relativeFiles,512,"relativeFiles").stream()
                    .map(v->EventBounds.relativePath(v,"relativeFiles")).toList();
            symbols=EventBounds.items(symbols,EventBounds.SHORT,"symbols");
            if(contextItemCount<0||contextItemCount>4096
                    ||indexedJavaFiles<0||indexedJavaFiles>1_000_000
                    ||queryCount<0||queryCount>4096)
                throw new IllegalArgumentException("invalid code context counts");
        }
        public CodeContextSelected(AgentEventMetadata metadata,List<String> files,
                List<String> symbols,int itemCount,boolean untrusted){
            this(metadata,files,symbols,itemCount,0,0,untrusted);
        }
    }
    public record ToolBatchStarted(AgentEventMetadata metadata,String batchId,int size,
            String effectClass) implements AgentEvent {
        public ToolBatchStarted{
            metadata=type(metadata,AgentEventType.TOOL_BATCH_STARTED);
            batchId=EventBounds.required(batchId,128,"batchId");
            if(size<2||size>8)throw new IllegalArgumentException("invalid Tool batch size");
            effectClass=EventBounds.required(effectClass,64,"effectClass");
        }
    }
    public record ToolRequested(AgentEventMetadata metadata, String toolName,
            String boundedArgumentsSummary,String relativePath,String effectClass,
            String stepLabel,int attempt,int sameRequestOrdinal,String toolCallId,
            int batchIndex,int batchSize) implements AgentEvent {
        public ToolRequested(AgentEventMetadata metadata,String toolName,
                String boundedArgumentsSummary,String relativePath,String effectClass,
                String stepLabel,int attempt,int sameRequestOrdinal,String toolCallId){
            this(metadata,toolName,boundedArgumentsSummary,relativePath,effectClass,stepLabel,
                    attempt,sameRequestOrdinal,toolCallId,1,1);
        }
        public ToolRequested(AgentEventMetadata metadata,String toolName,String arguments){
            this(metadata,toolName,arguments,null,"unknown",null,1,1,null,1,1);
        }
        public ToolRequested { metadata=type(metadata,AgentEventType.TOOL_REQUESTED); toolName=EventBounds.required(toolName,128,"toolName"); boundedArgumentsSummary=EventBounds.optionalPreview(boundedArgumentsSummary,EventBounds.SUMMARY,"boundedArgumentsSummary"); relativePath=EventBounds.optional(relativePath,512,"relativePath"); effectClass=EventBounds.required(effectClass,64,"effectClass"); stepLabel=EventBounds.optional(stepLabel,EventBounds.SHORT,"stepLabel"); if(attempt<1||sameRequestOrdinal<1||batchIndex<1||batchSize<1||batchIndex>batchSize||batchSize>8)throw new IllegalArgumentException("invalid tool attempt"); toolCallId=EventBounds.optional(toolCallId,256,"toolCallId"); }
    }
    public record ToolApproved(AgentEventMetadata metadata, String toolName, String decisionSource) implements AgentEvent {
        public ToolApproved { metadata=type(metadata,AgentEventType.TOOL_APPROVED); toolName=EventBounds.required(toolName,128,"toolName"); decisionSource=EventBounds.required(decisionSource,128,"decisionSource"); }
    }
    public record ToolRejected(AgentEventMetadata metadata, String toolName, String boundedReason) implements AgentEvent {
        public ToolRejected { metadata=type(metadata,AgentEventType.TOOL_REJECTED); toolName=EventBounds.required(toolName,128,"toolName"); boundedReason=EventBounds.requiredPreview(boundedReason,EventBounds.SUMMARY,"boundedReason"); }
    }
    public record ToolCancelled(AgentEventMetadata metadata, String toolName,
            String boundedReason,String toolCallId,int batchIndex,int batchSize)
            implements AgentEvent {
        public ToolCancelled(AgentEventMetadata metadata,String toolName,String boundedReason){
            this(metadata,toolName,boundedReason,null,1,1);
        }
        public ToolCancelled {
            metadata=type(metadata,AgentEventType.TOOL_CANCELLED);
            toolName=EventBounds.required(toolName,128,"toolName");
            boundedReason=EventBounds.requiredPreview(
                    boundedReason,EventBounds.SUMMARY,"boundedReason");
            toolCallId=EventBounds.optional(toolCallId,256,"toolCallId");
            if(batchIndex<1||batchSize<1||batchIndex>batchSize||batchSize>8)
                throw new IllegalArgumentException("invalid Tool batch cancellation position");
        }
    }
    public record ToolCompleted(AgentEventMetadata metadata,
            String toolName,String boundedOutput,List<String> boundedItems,int itemCount,
            List<String> boundedFacts,long elapsedMillis,boolean truncated,String stepLabel,int attempt,
            int sameRequestOrdinal,String toolCallId,int batchIndex,int batchSize) implements AgentEvent {
        public ToolCompleted(AgentEventMetadata metadata,String toolName,String boundedOutput,
                List<String> boundedItems,int itemCount,List<String> boundedFacts,long elapsedMillis,
                boolean truncated,String stepLabel,int attempt,int sameRequestOrdinal,String toolCallId){
            this(metadata,toolName,boundedOutput,boundedItems,itemCount,boundedFacts,elapsedMillis,
                    truncated,stepLabel,attempt,sameRequestOrdinal,toolCallId,1,1);
        }
        public ToolCompleted(AgentEventMetadata metadata,String toolName){
            this(metadata,toolName,null,List.of(),-1,List.of(),0,false,null,1,1,null,1,1);
        }
        public ToolCompleted {
            metadata=type(metadata,AgentEventType.TOOL_COMPLETED);
            toolName=EventBounds.required(toolName,128,"toolName");
            boundedOutput=EventBounds.optionalPreview(boundedOutput,EventBounds.EXCERPT,"boundedOutput");
            boundedItems=EventBounds.items(boundedItems,512,"boundedItems");
            boundedFacts=EventBounds.items(boundedFacts,256,"boundedFacts");
            if(itemCount < -1||elapsedMillis<0||attempt<1||sameRequestOrdinal<1
                    ||batchIndex<1||batchSize<1||batchIndex>batchSize||batchSize>8)
                throw new IllegalArgumentException("invalid tool completion measurements");
            stepLabel=EventBounds.optional(stepLabel,EventBounds.SHORT,"stepLabel");
            toolCallId=EventBounds.optional(toolCallId,256,"toolCallId");
        }
    }
    public record ToolFailed(AgentEventMetadata metadata, String toolName,
            String boundedError,long elapsedMillis,String stepLabel,int attempt,
            int sameRequestOrdinal,int batchIndex,int batchSize) implements AgentEvent {
        public ToolFailed(AgentEventMetadata metadata,String toolName,String boundedError,
                long elapsedMillis,String stepLabel,int attempt,int sameRequestOrdinal){
            this(metadata,toolName,boundedError,elapsedMillis,stepLabel,attempt,
                    sameRequestOrdinal,1,1);
        }
        public ToolFailed(AgentEventMetadata metadata,String toolName,String boundedError){
            this(metadata,toolName,boundedError,0,null,1,1,1,1);
        }
        public ToolFailed {
            metadata=type(metadata,AgentEventType.TOOL_FAILED);
            toolName=EventBounds.required(toolName,128,"toolName");
            boundedError=EventBounds.requiredPreview(
                    boundedError,EventBounds.SUMMARY,"boundedError");
            if(elapsedMillis<0||attempt<1||sameRequestOrdinal<1
                    ||batchIndex<1||batchSize<1||batchIndex>batchSize||batchSize>8)
                throw new IllegalArgumentException("invalid tool failure measurements");
            stepLabel=EventBounds.optional(stepLabel,EventBounds.SHORT,"stepLabel");
        }
    }
    public record ToolBatchCompleted(AgentEventMetadata metadata,String batchId,int size,
            int succeeded,int failed) implements AgentEvent {
        public ToolBatchCompleted{
            metadata=type(metadata,AgentEventType.TOOL_BATCH_COMPLETED);
            batchId=EventBounds.required(batchId,128,"batchId");
            if(size<2||size>8||succeeded<0||failed<0||succeeded+failed!=size)
                throw new IllegalArgumentException("invalid Tool batch completion");
        }
    }
    public record FinalResponse(AgentEventMetadata metadata,String boundedContent,
            String responseMode,String contentHash) implements AgentEvent {
        public FinalResponse {
            metadata=type(metadata,AgentEventType.FINAL_RESPONSE);
            boundedContent=EventBounds.finalResponse(boundedContent,64*1024,"boundedContent");
            responseMode=EventBounds.required(responseMode,32,"responseMode");
            contentHash=EventBounds.required(contentHash,64,"contentHash");
        }
    }
    public record ApprovalRequested(AgentEventMetadata metadata, String toolName,
            String relativePath, int additions, int deletions, boolean truncated,
            String actionKind, String boundedSummary, String warning,String planRevisionId,
            int planStepCount,int planSchemaVersion,
            List<PlanStepPreview> boundedPlanSteps) implements AgentEvent {
        public record RequirementPreview(String requirementId,int requirementOrder,
                String toolName,String selectorKind,String safeSelectorSummary,
                int requiredSlots){
            public RequirementPreview{
                requirementId=EventBounds.required(requirementId,128,"requirementId");
                if(requirementOrder<0||requiredSlots<1)
                    throw new IllegalArgumentException("invalid requirement preview");
                toolName=EventBounds.required(toolName,128,"toolName");
                selectorKind=EventBounds.required(selectorKind,64,"selectorKind");
                safeSelectorSummary=EventBounds.required(
                        safeSelectorSummary,512,"safeSelectorSummary");
            }
        }
        public record PlanStepPreview(int order,String boundedTitle,
                String boundedDescription,List<RequirementPreview> requirements){
            public PlanStepPreview(int order,String boundedTitle,String boundedDescription){
                this(order,boundedTitle,boundedDescription,List.of());
            }
            public PlanStepPreview{
                if(order<0)throw new IllegalArgumentException("invalid Plan preview order");
                boundedTitle=EventBounds.required(boundedTitle,512,"boundedTitle");
                boundedDescription=EventBounds.required(
                        boundedDescription,1024,"boundedDescription");
                if(com.paicli.state.PlanContentBounds.codePoints(boundedTitle)>120
                        ||com.paicli.state.PlanContentBounds.codePoints(boundedDescription)>256)
                    throw new IllegalArgumentException("invalid Plan preview bounds");
                requirements=List.copyOf(requirements==null?List.of():requirements);
            }
        }
        public ApprovalRequested(AgentEventMetadata metadata, String toolName,
                String relativePath, int additions, int deletions, boolean truncated,
                String actionKind, String boundedSummary, String warning) {
            this(metadata,toolName,relativePath,additions,deletions,truncated,actionKind,
                    boundedSummary,warning,null,0,0,List.of());
        }
        public ApprovalRequested(AgentEventMetadata metadata, String toolName,
                String relativePath, int additions, int deletions, boolean truncated) {
            this(metadata, toolName, relativePath, additions, deletions, truncated,
                    "OTHER", null, null,null,0,0,List.of());
        }
        public ApprovalRequested {
            metadata=type(metadata,AgentEventType.APPROVAL_REQUESTED);
            toolName=EventBounds.required(toolName,128,"toolName");
            relativePath=EventBounds.optional(relativePath,512,"relativePath");
            if(relativePath!=null) relativePath=EventBounds.relativePath(relativePath,"relativePath");
            if(additions<0||deletions<0)throw new IllegalArgumentException("invalid diff counts");
            actionKind=EventBounds.required(actionKind,64,"actionKind");
            boundedSummary=EventBounds.optionalPreview(boundedSummary,EventBounds.SUMMARY,"boundedSummary");
            warning=EventBounds.optional(warning,EventBounds.SUMMARY,"warning");
            planRevisionId=EventBounds.optional(planRevisionId,128,"planRevisionId");
            boundedPlanSteps=List.copyOf(boundedPlanSteps==null?List.of():boundedPlanSteps);
            if("PLAN".equals(actionKind)){
                if(planRevisionId==null||planStepCount<1||boundedPlanSteps.isEmpty()
                        ||planSchemaVersion<1||planSchemaVersion>3
                        ||planStepCount>com.paicli.state.PlanContentBounds.MAX_STEPS
                        ||boundedPlanSteps.size()!=planStepCount)
                    throw new IllegalArgumentException("invalid Plan approval event");
            }else if(planRevisionId!=null||planStepCount!=0||planSchemaVersion!=0
                    ||!boundedPlanSteps.isEmpty())
                throw new IllegalArgumentException("unexpected Plan approval fields");
        }
    }
    public record ApprovalResolved(AgentEventMetadata metadata, String toolName,
            String decision, boolean taskScoped) implements AgentEvent {
        public ApprovalResolved {
            metadata=type(metadata,AgentEventType.APPROVAL_RESOLVED);
            toolName=EventBounds.required(toolName,128,"toolName");
            decision=EventBounds.required(decision,64,"decision");
        }
    }    public record FileRead(AgentEventMetadata metadata, String relativePath, long bytesRead) implements AgentEvent {
        public FileRead { metadata=type(metadata,AgentEventType.FILE_READ); relativePath=EventBounds.relativePath(relativePath,"relativePath"); if(bytesRead<0)throw new IllegalArgumentException("bytesRead must not be negative"); }
    }
    public record DiffProposed(AgentEventMetadata metadata, String relativePath, int additions,
            int deletions, String boundedSummary, boolean truncated) implements AgentEvent {
        public DiffProposed { metadata=type(metadata,AgentEventType.DIFF_PROPOSED); relativePath=EventBounds.relativePath(relativePath,"relativePath"); if(additions<0||deletions<0)throw new IllegalArgumentException("diff counts must not be negative"); boundedSummary=EventBounds.requiredPreview(boundedSummary,EventBounds.EXCERPT,"boundedSummary"); }
    }
    public record SnapshotCreated(AgentEventMetadata metadata, String relativePath, String snapshotReference) implements AgentEvent {
        public SnapshotCreated { metadata=type(metadata,AgentEventType.SNAPSHOT_CREATED); relativePath=EventBounds.relativePath(relativePath,"relativePath"); snapshotReference=EventBounds.required(snapshotReference,128,"snapshotReference"); }
    }
    public record FileWritten(AgentEventMetadata metadata, String relativePath, long bytesWritten) implements AgentEvent {
        public FileWritten { metadata=type(metadata,AgentEventType.FILE_WRITTEN); relativePath=EventBounds.relativePath(relativePath,"relativePath"); if(bytesWritten<0)throw new IllegalArgumentException("bytesWritten must not be negative"); }
    }
    public record VerificationStarted(AgentEventMetadata metadata, String policyLabel) implements AgentEvent {
        public VerificationStarted { metadata=type(metadata,AgentEventType.VERIFICATION_STARTED); policyLabel=EventBounds.required(policyLabel,EventBounds.SHORT,"policyLabel"); }
    }
    public record VerificationFailed(AgentEventMetadata metadata, String category,
            int tests, int failures, int errors, int skipped, boolean timedOut,
            boolean processCleanupComplete, String boundedExcerpt) implements AgentEvent {
        public VerificationFailed {
            metadata=type(metadata,AgentEventType.VERIFICATION_FAILED);
            category=EventBounds.required(category,128,"category");
            validateTestCounts(tests,failures,errors,skipped);
            boundedExcerpt=EventBounds.requiredPreview(
                    boundedExcerpt,EventBounds.EXCERPT,"boundedExcerpt");
        }
        public VerificationFailed(AgentEventMetadata metadata,String category,String excerpt){
            this(metadata,category,-1,-1,-1,-1,false,true,excerpt);
        }
    }
    public record VerificationSkipped(AgentEventMetadata metadata,
            String reason) implements AgentEvent {
        public VerificationSkipped {
            metadata=type(metadata,AgentEventType.VERIFICATION_SKIPPED);
            reason=EventBounds.required(reason,EventBounds.SUMMARY,"reason");
        }
    }
    public record RepairStarted(AgentEventMetadata metadata, int attempt) implements AgentEvent {
        public RepairStarted { metadata=type(metadata,AgentEventType.REPAIR_STARTED); if(attempt<1||attempt>32)throw new IllegalArgumentException("invalid repair attempt"); }
    }
    public record VerificationPassed(AgentEventMetadata metadata, int tests,
            int failures, int errors, int skipped, boolean processCleanupComplete,
            String boundedSummary) implements AgentEvent {
        public VerificationPassed {
            metadata=type(metadata,AgentEventType.VERIFICATION_PASSED);
            validateTestCounts(tests,failures,errors,skipped);
            boundedSummary=EventBounds.requiredPreview(
                    boundedSummary,EventBounds.SUMMARY,"boundedSummary");
        }
        public VerificationPassed(AgentEventMetadata metadata,String summary){
            this(metadata,-1,-1,-1,-1,true,summary);
        }
    }
    public record TaskCompleted(AgentEventMetadata metadata, String terminalStatus,
            String verificationStatus, String boundedSummary) implements AgentEvent {
        public TaskCompleted(AgentEventMetadata metadata, String boundedSummary) {
            this(metadata, "COMPLETED", "not available", boundedSummary);
        }
        public TaskCompleted {
            metadata=type(metadata,AgentEventType.TASK_COMPLETED);
            terminalStatus=EventBounds.required(terminalStatus,128,"terminalStatus");
            verificationStatus=EventBounds.required(verificationStatus,128,"verificationStatus");
            boundedSummary=EventBounds.optionalPreview(boundedSummary,EventBounds.SUMMARY,"boundedSummary");
        }
    }
    public record TaskFailed(AgentEventMetadata metadata, String category, String boundedExcerpt) implements AgentEvent {
        public TaskFailed { metadata=type(metadata,AgentEventType.TASK_FAILED); category=EventBounds.required(category,128,"category"); boundedExcerpt=EventBounds.optionalPreview(boundedExcerpt,EventBounds.EXCERPT,"boundedExcerpt"); }
    }
    public record TaskCancelled(AgentEventMetadata metadata, String category, boolean externalOutcomeUnknown) implements AgentEvent {
        public TaskCancelled { metadata=type(metadata,AgentEventType.TASK_CANCELLED); category=EventBounds.required(category,128,"category"); }
    }
    public record TaskInconclusive(AgentEventMetadata metadata, String category,
            String boundedSummary) implements AgentEvent {
        public TaskInconclusive {
            metadata=type(metadata,AgentEventType.TASK_INCONCLUSIVE);
            category=EventBounds.required(category,128,"category");
            boundedSummary=EventBounds.optionalPreview(
                    boundedSummary,EventBounds.SUMMARY,"boundedSummary");
        }
    }
    public record CancellationRequested(AgentEventMetadata metadata,
            String category) implements AgentEvent {
        public CancellationRequested {
            metadata=type(metadata,AgentEventType.CANCELLATION_REQUESTED);
            category=EventBounds.required(category,128,"category");
        }
    }
    public record CancellationForced(AgentEventMetadata metadata,
            int terminatedOwnedProcesses, boolean externalOutcomeUnknown) implements AgentEvent {
        public CancellationForced {
            metadata=type(metadata,AgentEventType.CANCELLATION_FORCED);
            if(terminatedOwnedProcesses<0||terminatedOwnedProcesses>1024)
                throw new IllegalArgumentException("invalid terminated process count");
        }
    }    public record SessionEnded(AgentEventMetadata metadata, String reason) implements AgentEvent {
        public SessionEnded { metadata=type(metadata,AgentEventType.SESSION_ENDED); reason=EventBounds.required(reason,EventBounds.SHORT,"reason"); }
    }
}
