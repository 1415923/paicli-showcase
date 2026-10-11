package com.paicli.interactive.runtime;

import com.paicli.interactive.diff.PreWriteDiff;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;

public final class InteractiveRuntimeView {
    private String workspaceIdentity;
    private String threadId;
    private String generationId;
    private String activeTaskId;
    private String activeRunId;
    private String lastTaskId;
    private String lastRunId;
    private String phase = "IDLE";
    private String lastStatus = "none";
    private boolean pendingConfirmation;
    private String planSummary;
    private List<String> planSteps = List.of();
    private int indexedJavaFiles;
    private int codeContextItems;
    private String codeIntelligenceStatus = "NOT_RUN";
    private final List<DiffFact> activeDiffs = new ArrayList<>();
    private List<DiffFact> lastDiffs = List.of();
    private final Set<String> knownPaths = new LinkedHashSet<>();
    private final Map<String, String> readHashes = new LinkedHashMap<>();

    public synchronized void taskStarted(String taskId, String runId) {
        taskStarted(null, null, taskId, runId, null);
    }
    public synchronized void taskStarted(String workspaceIdentity, String threadId,
            String taskId, String runId, String generationId) {
        this.workspaceIdentity = workspaceIdentity;
        this.threadId = threadId;
        this.generationId = generationId;
        activeTaskId = taskId; activeRunId = runId; phase = "PLANNING";
        lastStatus = "RUNNING"; pendingConfirmation=false;
        activeDiffs.clear(); knownPaths.clear(); readHashes.clear();
    }
    public synchronized void phase(String value) { phase = value; }
    public synchronized void confirmationPending(boolean value){pendingConfirmation=value;}
    public synchronized void plan(String summary, List<String> steps) {
        planSummary = summary; planSteps = List.copyOf(steps);
    }
    public synchronized void codeContext(int indexedFiles, int contextItems) {
        indexedJavaFiles = indexedFiles; codeContextItems = contextItems;
        codeIntelligenceStatus = "USED";
    }
    public synchronized void knownPaths(List<String> paths) {
        paths.stream().filter(java.util.Objects::nonNull).limit(64).forEach(knownPaths::add);
    }
    public synchronized void fileRead(String relativePath, String contentHash) {
        knownPaths.add(relativePath);
        readHashes.put(relativePath, contentHash);
    }
    public synchronized boolean isKnownPath(String relativePath) { return knownPaths.contains(relativePath); }
    public synchronized String readHash(String relativePath) { return readHashes.get(relativePath); }
    public synchronized void proposal(PreWriteDiff diff) {
        for (int index = 0; index < activeDiffs.size(); index++) {
            DiffFact item = activeDiffs.get(index);
            if ("PROPOSED".equals(item.status())
                    && item.relativePath().equals(diff.relativePath())
                    && !item.afterHash().equals(diff.afterHash())) {
                activeDiffs.set(index, item.withStatus("SUPERSEDED", null));
            }
        }
        activeDiffs.add(new DiffFact(diff.relativePath(), diff.additions(),
                diff.deletions(), diff.boundedLines(), "PROPOSED",
                diff.truncated(), diff.redacted(), null, diff.afterHash()));
    }
    public synchronized void proposalResolved(
            String relativePath, String afterHash, String status) {
        for (int index = activeDiffs.size() - 1; index >= 0; index--) {
            DiffFact item = activeDiffs.get(index);
            if ("PROPOSED".equals(item.status())
                    && item.relativePath().equals(relativePath)
                    && item.afterHash().equals(afterHash)) {
                activeDiffs.set(index, item.withStatus(status, item.snapshotReference()));
                return;
            }
        }
    }
    public synchronized void applied(String relativePath, String snapshotReference) {
        for (int index = activeDiffs.size() - 1; index >= 0; index--) {
            DiffFact item = activeDiffs.get(index);
            if ("PROPOSED".equals(item.status()) && item.relativePath().equals(relativePath)) {
                activeDiffs.set(index, item.withStatus("APPLIED", snapshotReference));
                return;
            }
        }
    }
    public synchronized void taskFinished(String taskId, String runId, String status) {
        lastTaskId = taskId; lastRunId = runId; lastStatus = status;
        lastDiffs = activeDiffs.stream().map(item -> "PROPOSED".equals(item.status())
                ? item.withStatus("SUPERSEDED", item.snapshotReference())
                : item).toList();
        activeDiffs.clear();
        workspaceIdentity = null; threadId = null; generationId = null;
        pendingConfirmation = false;
        activeTaskId = null; activeRunId = null; phase = "IDLE";
    }
    public synchronized void snapshotRewound(String taskId, String relativePath) {
        if (taskId != null && taskId.equals(activeTaskId)) {
            for (int index = 0; index < activeDiffs.size(); index++) {
                DiffFact item = activeDiffs.get(index);
                if (item.relativePath().equals(relativePath) && item.applied()) {
                    activeDiffs.set(index,
                            item.withStatus("REWOUND", item.snapshotReference()));
                }
            }
        }
        if (taskId != null && taskId.equals(lastTaskId)) {
            lastDiffs = lastDiffs.stream()
                    .map(item -> item.relativePath().equals(relativePath) && item.applied()
                            ? item.withStatus("REWOUND", item.snapshotReference())
                            : item)
                    .toList();
        }
    }
    public synchronized Snapshot snapshot() {
        return new Snapshot(workspaceIdentity, threadId, generationId,
                activeTaskId, activeRunId, lastTaskId, lastRunId,
                phase, lastStatus, pendingConfirmation, planSummary, planSteps, indexedJavaFiles,
                codeContextItems, codeIntelligenceStatus,
                List.copyOf(activeDiffs), lastDiffs);
    }
    public record DiffFact(String relativePath, int additions, int deletions,
            List<String> boundedLines, String status, boolean truncated,
            boolean redacted, String snapshotReference, String afterHash) {
        public DiffFact {
            boundedLines = List.copyOf(boundedLines);
            if (!Set.of("PROPOSED", "REJECTED", "CANCELLED", "APPLIED",
                    "REWOUND", "SUPERSEDED").contains(status)) {
                throw new IllegalArgumentException("invalid diff status");
            }
        }
        DiffFact withStatus(String next, String snapshot) {
            return new DiffFact(relativePath, additions, deletions, boundedLines,
                    next, truncated, redacted, snapshot, afterHash);
        }
        public boolean applied() { return "APPLIED".equals(status); }
        public boolean current() { return "APPLIED".equals(status); }
    }
    public record Snapshot(String workspaceIdentity, String threadId, String generationId,
            String activeTaskId, String activeRunId,
            String lastTaskId, String lastRunId, String phase, String lastStatus,
            boolean pendingConfirmation, String planSummary, List<String> planSteps, int indexedJavaFiles,
            int codeContextItems, String codeIntelligenceStatus,
            List<DiffFact> activeDiffs, List<DiffFact> lastDiffs) {
        public Snapshot {
            planSteps = List.copyOf(planSteps);
            activeDiffs = List.copyOf(activeDiffs);
            lastDiffs = List.copyOf(lastDiffs);
        }
    }
}
