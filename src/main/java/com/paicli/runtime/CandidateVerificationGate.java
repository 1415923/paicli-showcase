package com.paicli.runtime;

import java.sql.SQLException;

/** Decides whether an execution segment produced a durable candidate worth verifying. */
@FunctionalInterface
public interface CandidateVerificationGate {
    CandidateVerificationGate ALLOW = (taskId, runId) -> true;

    boolean hasAppliedCandidate(String taskId, String runId) throws SQLException;
}
