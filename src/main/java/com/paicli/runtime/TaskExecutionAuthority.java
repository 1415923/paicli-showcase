package com.paicli.runtime;

import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.state.R1aDurableModels.OwnershipLease;
import com.paicli.state.R1aDurableModels.ExecutionAuthoritySnapshot;

import java.time.Instant;
import java.util.Objects;

/** Immutable lease and fencing authority for one Task/Run execution owner. */
public final class TaskExecutionAuthority {
    public enum Kind { INITIAL, RECOVERY }

    private final CanonicalId taskId;
    private final CanonicalId runId;
    private final String ownerInstanceId;
    private final String leaseToken;
    private final long fencingToken;
    private final long casVersion;
    private final Instant acquiredAt;
    private final Instant heartbeatAt;
    private final Instant expiresAt;
    private final Kind kind;

    private TaskExecutionAuthority(CanonicalId taskId, CanonicalId runId,
            String ownerInstanceId, String leaseToken, long fencingToken, long casVersion,
            Instant acquiredAt, Instant heartbeatAt, Instant expiresAt, Kind kind) {
        this.taskId=require(taskId,EntityFamily.TASK);this.runId=require(runId,EntityFamily.RUN);
        this.ownerInstanceId=requireText(ownerInstanceId);this.leaseToken=requireText(leaseToken);
        if(fencingToken<1||casVersion<1)throw new IllegalArgumentException(
                "Positive fencing and CAS versions are required");
        this.fencingToken=fencingToken;this.casVersion=casVersion;
        this.acquiredAt=Objects.requireNonNull(acquiredAt);
        this.heartbeatAt=Objects.requireNonNull(heartbeatAt);
        this.expiresAt=Objects.requireNonNull(expiresAt);this.kind=Objects.requireNonNull(kind);
        if(heartbeatAt.isBefore(acquiredAt)||!expiresAt.isAfter(heartbeatAt))
            throw new IllegalArgumentException("Valid lease timestamps are required");
    }

    public static TaskExecutionAuthority recovery(OwnershipLease lease) {
        return fromLease(lease, Kind.RECOVERY);
    }

    public static TaskExecutionAuthority initial(OwnershipLease lease) {
        return fromLease(lease, Kind.INITIAL);
    }

    static TaskExecutionAuthority fromLease(OwnershipLease lease, Kind kind) {
        Objects.requireNonNull(lease);
        return new TaskExecutionAuthority(lease.taskId(),lease.ownerRunId(),
                lease.ownerInstanceId(),lease.leaseToken(),lease.fencingToken(),
                lease.casVersion(),lease.acquiredAt(),lease.heartbeatAt(),lease.expiresAt(),
                kind);
    }

    public CanonicalId taskId(){return taskId;}
    public CanonicalId runId(){return runId;}
    public String ownerInstanceId(){return ownerInstanceId;}
    public String leaseToken(){return leaseToken;}
    public long fencingToken(){return fencingToken;}
    public long casVersion(){return casVersion;}
    public Instant acquiredAt(){return acquiredAt;}
    public Instant heartbeatAt(){return heartbeatAt;}
    public Instant expiresAt(){return expiresAt;}
    public Kind kind(){return kind;}

    public ExecutionAuthoritySnapshot snapshot(){
        return new ExecutionAuthoritySnapshot(taskId,runId,ownerInstanceId,leaseToken,
                fencingToken,casVersion);
    }

    @Override public String toString(){
        return "TaskExecutionAuthority[taskId="+taskId+", runId="+runId
                +", ownerInstanceId="+ownerInstanceId+", leaseToken=REDACTED, fencingToken="
                +fencingToken+", casVersion="+casVersion+", expiresAt="+expiresAt
                +", kind="+kind+"]";
    }

    private static CanonicalId require(CanonicalId id,EntityFamily family){
        if(id==null||id.family()!=family)throw new IllegalArgumentException(
                "Expected "+family+" canonical ID");return id;
    }
    private static String requireText(String value){
        if(value==null||value.isBlank())throw new IllegalArgumentException("Value is required");
        return value;
    }
}
