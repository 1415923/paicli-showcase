package com.paicli.context;

import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.model.AgentMessage;
import com.paicli.model.ToolDefinition;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** R3 immutable Context Generation, invocation, source and compaction contracts. */
public final class ContextModels {
    private ContextModels(){}
    public enum SourceClass { NON_COMPACTABLE_AUTHORITY, COMPACTABLE_DURABLE_HISTORY,
        FRESH_USER_INTENT, PREVIEW_EPHEMERAL, CHILD_CONTEXT }
    public enum OperationKind { PLAN, CHAT, TOOL_CONTINUATION, FINAL_SYNTHESIS, VERIFICATION, REPAIR }
    public enum Lifecycle { PREPARING, PREPARED, READY, FAILED, SUPERSEDED }
    public enum GenerationKind { BASE, COMPACTED }
    public enum Scope { RUN_SCOPED, TASK_SCOPED, CHILD_SCOPED }
    public enum Compactability { COMPACTABLE, NON_COMPACTABLE }
    public enum PayloadMode { REFERENCE_RENDERED, INLINE_SANITIZED }
    public enum PrivacyClass { PUBLIC, INTERNAL, SENSITIVE_REDACTED }
    public enum LimitProvenance { PROVIDER_REPORTED_LIMIT, CONFIGURED_LIMIT,
        MODEL_CATALOG_LIMIT, UNKNOWN_LIMIT }
    public enum EstimateProvenance { TOKENIZER, DERIVED_ESTIMATE }
    public enum LedgerFactKind { VERBATIM_REFERENCE, STRUCTURED_DERIVED, UNKNOWN, UNRESOLVED }

    /** Command-local authority for one terminal shell-bound Run; never an execution lease. */
    public record MaintenanceTarget(OperationKind operation,String sourceContextGenerationId,
            long sourcePointerVersion){
        public MaintenanceTarget{Objects.requireNonNull(operation);Objects.requireNonNull(sourceContextGenerationId);
            requireIdFamily(sourceContextGenerationId,EntityFamily.CONTEXT);
            if(sourcePointerVersion<1)throw new IllegalArgumentException("invalid maintenance target");}
    }
    public record MaintenanceAuthority(String sessionId,String threadId,String taskId,String runId,
            List<MaintenanceTarget> targets,String snapshotToken) {
        public MaintenanceAuthority {
            Objects.requireNonNull(sessionId);Objects.requireNonNull(threadId);
            Objects.requireNonNull(taskId);Objects.requireNonNull(runId);Objects.requireNonNull(snapshotToken);
            targets=List.copyOf(targets);if(targets.isEmpty())throw new IllegalArgumentException("empty maintenance bundle");
            requireIdFamily(threadId,EntityFamily.THREAD);requireIdFamily(taskId,EntityFamily.TASK);
            requireIdFamily(runId,EntityFamily.RUN);
            if(!snapshotToken.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("invalid maintenance authority");
        }
    }

    public record Identity(String workspaceId,String threadId,String taskId,String runId,
            String authorityGenerationId,String contextGenerationId,String manifestHash,
            String planRevision,String steeringRevision) {
        public Identity { Objects.requireNonNull(workspaceId);Objects.requireNonNull(threadId);
            Objects.requireNonNull(taskId);Objects.requireNonNull(runId);
            Objects.requireNonNull(authorityGenerationId);Objects.requireNonNull(contextGenerationId);
            Objects.requireNonNull(manifestHash);
            requireFamily(threadId,EntityFamily.THREAD);requireFamily(taskId,EntityFamily.TASK);
            requireFamily(runId,EntityFamily.RUN);requireFamily(authorityGenerationId,EntityFamily.GENERATION);
            requireFamily(contextGenerationId,EntityFamily.CONTEXT);
            if(!manifestHash.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("invalid manifest hash");
        }
        private static void requireFamily(String value,EntityFamily family){
            if(CanonicalId.parse(value).family()!=family)
                throw new IllegalArgumentException("context identity family mismatch");
        }
    }

    /**
     * Explicit operation envelope; operation is never inferred from message shape.
     * toolResultSourceIds names the exact latest Provider-continuation terminal result sources.
     * A result may be an executed Tool result or a typed non-execution observation; the field is
     * continuation authority and never, by itself, evidence that a Tool effect occurred.
     */
    public record ContextInvocation(Identity identity,OperationKind operation,String operationSequence,
            String currentUserIntentSourceId,List<String> toolResultSourceIds,
            String verificationRepairSourceId,String expectedPlanRevision,String expectedSteeringRevision,
            String provider,String model,CapabilityProfile capability,boolean childContext,
            String compilerVersion,String policyVersion) {
        public ContextInvocation {
            Objects.requireNonNull(identity);Objects.requireNonNull(operation);
            Objects.requireNonNull(operationSequence);toolResultSourceIds=List.copyOf(toolResultSourceIds);
            Objects.requireNonNull(capability);
            Objects.requireNonNull(compilerVersion);Objects.requireNonNull(policyVersion);
            provider=bounded(provider,"unknown",64);model=bounded(model,"unknown",128);
        }
    }

    public record SourceReference(String type,String id,String revision,String hash,int order){
        public SourceReference { Objects.requireNonNull(type);Objects.requireNonNull(id);
            Objects.requireNonNull(hash);if(order<0)throw new IllegalArgumentException("negative source order");}
    }

    /** In-memory sanitized rendering of one durable or Context-owned immutable source. */
    public record ContextSource(int priority,long sequence,SourceClass sourceClass,String kind,
            String stableSourceId,String revision,String authorityKind,Scope scope,
            Compactability compactability,PayloadMode payloadMode,String rendererVersion,
            String renderedPayload,String contentHash,PrivacyClass privacyClass,int bounds,
            boolean providerVisible,AgentMessage.Role role,AgentMessage.MessageType messageType,
            String toolCallId,String toolCallJson,List<SourceReference> references) {
        public ContextSource(int priority,long sequence,SourceClass sourceClass,String kind,
                String stableSourceId,String revision,String authorityKind,Scope scope,
                Compactability compactability,PayloadMode payloadMode,String rendererVersion,
                String renderedPayload,String contentHash,PrivacyClass privacyClass,int bounds,
                boolean providerVisible,AgentMessage.Role role,AgentMessage.MessageType messageType,
                String toolCallId,List<SourceReference> references) {
            this(priority,sequence,sourceClass,kind,stableSourceId,revision,authorityKind,scope,
                    compactability,payloadMode,rendererVersion,renderedPayload,contentHash,
                    privacyClass,bounds,providerVisible,role,messageType,toolCallId,null,references);
        }
        public ContextSource {
            Objects.requireNonNull(sourceClass);Objects.requireNonNull(kind);
            Objects.requireNonNull(stableSourceId);Objects.requireNonNull(authorityKind);
            Objects.requireNonNull(scope);Objects.requireNonNull(compactability);
            Objects.requireNonNull(payloadMode);Objects.requireNonNull(rendererVersion);
            Objects.requireNonNull(renderedPayload);Objects.requireNonNull(contentHash);
            Objects.requireNonNull(privacyClass);references=List.copyOf(references);
            if(priority<0||sequence<0||bounds<0||renderedPayload.length()>bounds)
                throw new IllegalArgumentException("invalid Context source bounds");
            if(sourceClass==SourceClass.PREVIEW_EPHEMERAL)
                throw new IllegalArgumentException("preview source cannot enter durable closure");
            if(providerVisible&&(role==null||messageType==null))
                throw new IllegalArgumentException("provider-visible source requires explicit role and type");
            if(toolCallJson!=null&&(messageType!=AgentMessage.MessageType.ACTION||toolCallId==null))
                throw new IllegalArgumentException("tool call JSON requires an ACTION source and call ID");
        }
    }

    public record ContextSourceSet(List<ContextSource> sources,String closureFingerprint) {
        public ContextSourceSet {
            sources=List.copyOf(sources);Objects.requireNonNull(closureFingerprint);
            if(!closureFingerprint.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("invalid source fingerprint");
        }
        public static final Comparator<ContextSource> ORDER=Comparator
                .comparingInt(ContextSource::priority).thenComparingLong(ContextSource::sequence)
                .thenComparing(ContextSource::revision,Comparator.nullsFirst(String::compareTo))
                .thenComparing(ContextSource::stableSourceId);
    }

    public record Segment(int ordinal,SourceClass sourceClass,String kind,Scope scope,
            Compactability compactability,PayloadMode payloadMode,String sanitizedPayload,
            String contentHash,int estimatedTokens,PrivacyClass privacyClass,int bounds,
            List<SourceReference> references) implements DeterministicContextCompiler.ClosureSegmentView {
        public Segment { references=List.copyOf(references);if(ordinal<0||estimatedTokens<0||bounds<0)
            throw new IllegalArgumentException("invalid segment bounds");}
    }
    /** Versioned Provider-visible semantic unit emitted by deterministic compaction. */
    public record CompactedLedgerEntry(LedgerFactKind entryKind,String sourceKind,String sourceId,
            String sourceRevision,String workspaceId,String threadId,String taskId,String runId,
            String authorityProvenance,String preservedFactType,String boundedProviderVisiblePayload,
            List<String> preservedDecisions,List<String> unresolvedItems,List<String> evidenceReferences,
            Map<String,String> toolResultFields,List<String> artifactReferences,
            List<String> verificationReferences,List<String> repairReferences,String sourceContentHash,
            String rendererVersion,PrivacyClass privacyClass,int bounds) {
        public CompactedLedgerEntry {
            Objects.requireNonNull(entryKind);Objects.requireNonNull(sourceKind);
            Objects.requireNonNull(sourceId);Objects.requireNonNull(workspaceId);
            Objects.requireNonNull(threadId);Objects.requireNonNull(taskId);Objects.requireNonNull(runId);
            Objects.requireNonNull(authorityProvenance);Objects.requireNonNull(preservedFactType);
            Objects.requireNonNull(boundedProviderVisiblePayload);Objects.requireNonNull(sourceContentHash);
            Objects.requireNonNull(rendererVersion);Objects.requireNonNull(privacyClass);
            preservedDecisions=List.copyOf(preservedDecisions);unresolvedItems=List.copyOf(unresolvedItems);
            evidenceReferences=List.copyOf(evidenceReferences);
            toolResultFields=Map.copyOf(toolResultFields);artifactReferences=List.copyOf(artifactReferences);
            verificationReferences=List.copyOf(verificationReferences);
            repairReferences=List.copyOf(repairReferences);
            if(bounds<0||boundedProviderVisiblePayload.length()>bounds)
                throw new IllegalArgumentException("invalid compacted ledger bounds");
        }
    }
    public record CapabilityProfile(String name,Integer contextLimit,LimitProvenance provenance,
            int outputReserve,int safetyMargin) {
        public CapabilityProfile { Objects.requireNonNull(name);Objects.requireNonNull(provenance);
            if(contextLimit!=null&&contextLimit<=0)throw new IllegalArgumentException("invalid limit");
            if(outputReserve<0||safetyMargin<0)throw new IllegalArgumentException("invalid reserve");}
    }
    public record Budget(int inputEstimate,int toolSchemaEstimate,int outputReserve,int safetyMargin,
            Integer contextLimit,LimitProvenance limitProvenance,
            EstimateProvenance estimateProvenance,Decision decision) {
        public enum Decision { READY, CONTEXT_BUDGET_UNKNOWN, COMPACTION_REQUIRED,
            CONTEXT_BUDGET_EXCEEDED }
        public int total(){return Math.addExact(Math.addExact(inputEstimate,toolSchemaEstimate),
                Math.addExact(outputReserve,safetyMargin));}
    }
    public record CompileRequest(ContextInvocation invocation,ContextSourceSet sourceSet,
            List<ToolDefinition> tools) {
        public CompileRequest { Objects.requireNonNull(invocation);Objects.requireNonNull(sourceSet);
            tools=List.copyOf(tools); }
    }
    public record CompiledContext(Identity identity,OperationKind operation,String provider,String model,
            CapabilityProfile capability,String compilerVersion,String policyVersion,List<AgentMessage> messages,
            List<ToolDefinition> tools,List<Segment> segments,String sourceFingerprint,
            String compiledHash,Budget budget) {
        public CompiledContext { messages=List.copyOf(messages);tools=List.copyOf(tools);
            segments=List.copyOf(segments); }
    }

    private static String bounded(String value,String fallback,int max){
        String clean=value==null||value.isBlank()?fallback:value.trim();
        return clean.substring(0,Math.min(max,clean.length()));
    }
    private static void requireIdFamily(String value,EntityFamily family){
        if(CanonicalId.parse(value).family()!=family)
            throw new IllegalArgumentException("context identity family mismatch");
    }
}
