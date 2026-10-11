package com.paicli.context;

/**
 * Immutable logical Provider-turn seam. Context compilation, optional compaction, publication,
 * and Provider occurrence admission happen once before this snapshot is bound. Transport-layer
 * attempts may reuse it but must never re-enter ContextualModelProvider.
 */
public final class ProviderContextRuntime {
    private static final ThreadLocal<BoundTurn> CURRENT=new ThreadLocal<>();
    private ProviderContextRuntime(){}
    public record ProviderTurnIdentity(String contextGenerationId,String taskId,String runId,
            String planRevision,String sourceSnapshotHash,String compiledSnapshotHash,
            String providerOccurrenceId){
        public ProviderTurnIdentity{
            java.util.Objects.requireNonNull(contextGenerationId);
            java.util.Objects.requireNonNull(taskId);java.util.Objects.requireNonNull(runId);
            java.util.Objects.requireNonNull(sourceSnapshotHash);
            java.util.Objects.requireNonNull(compiledSnapshotHash);
            java.util.Objects.requireNonNull(providerOccurrenceId);
        }
    }
    @FunctionalInterface public interface RetryAdmission { void require(); }
    private record BoundTurn(ProviderTurnIdentity identity,RetryAdmission retryAdmission){}
    /** Backward-compatible context link for telemetry storage. */
    public static String current(){
        BoundTurn value=CURRENT.get();
        return value==null?null:value.identity().contextGenerationId();
    }
    public static ProviderTurnIdentity currentTurn(){
        BoundTurn value=CURRENT.get();return value==null?null:value.identity();
    }
    /** Must be called by an inner transport before every retry attempt. */
    public static void requireRetryAdmission(){
        BoundTurn value=CURRENT.get();
        if(value==null)throw new IllegalStateException("PROVIDER_TURN_IDENTITY_REQUIRED");
        value.retryAdmission().require();
    }
    static Scope bind(ProviderTurnIdentity identity,RetryAdmission retryAdmission){
        BoundTurn previous=CURRENT.get();CURRENT.set(new BoundTurn(
                java.util.Objects.requireNonNull(identity),
                java.util.Objects.requireNonNull(retryAdmission)));return ()->{
        if(previous==null)CURRENT.remove();else CURRENT.set(previous);};}
    @FunctionalInterface interface Scope extends AutoCloseable{void close();}
}
