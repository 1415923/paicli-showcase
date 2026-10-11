package com.paicli.runtime;

/** Persisted output of a successful planning phase. */
public record PlanResult(String planId, String runId, String taskId, String planText) {
}
