package com.paicli.demo.runtime;

import com.paicli.demo.fixture.CoreDemoFixtureManifestLoader;

import java.util.*;

public record CoreDemoWorkflowResult(
        String runId,
        String scenarioId,
        String workspaceIdentityHash,
        TerminalCategory terminalCategory,
        Phase completedPhase,
        FactStatus planning,
        FactStatus codeIntelligence,
        int codeIntelligenceQueries,
        int codeIntelligenceContextItems,
        FactStatus execution,
        List<String> changedRelativeFiles,
        int snapshotCount,
        List<VerificationFact> verificationSequence,
        int repairCount,
        FactStatus resume,
        FactStatus rewind,
        String initialExecutionTreeHash,
        String finalTreeHash,
        String rewindTreeHash,
        String boundedErrorCategory,
        String boundedErrorExcerpt,
        CleanupRecommendation cleanupRecommendation,
        CoreDemoSemanticFacts semanticFacts) {

    public CoreDemoWorkflowResult {
        requireText(runId, 64, "runId");
        requireText(scenarioId, 128, "scenarioId");
        requireSha(workspaceIdentityHash, "workspaceIdentityHash");
        Objects.requireNonNull(terminalCategory, "terminalCategory");
        Objects.requireNonNull(completedPhase, "completedPhase");
        Objects.requireNonNull(planning, "planning");
        Objects.requireNonNull(codeIntelligence, "codeIntelligence");
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(resume, "resume");
        Objects.requireNonNull(rewind, "rewind");
        Objects.requireNonNull(cleanupRecommendation, "cleanupRecommendation");
        semanticFacts = semanticFacts == null ? CoreDemoSemanticFacts.empty() : semanticFacts;
        if (codeIntelligenceQueries < 0 || codeIntelligenceContextItems < 0
                || snapshotCount < 0 || repairCount < 0 || repairCount > 1) {
            throw new IllegalArgumentException("Negative or excessive workflow count");
        }
        changedRelativeFiles = normalizePaths(changedRelativeFiles);
        verificationSequence = List.copyOf(
                verificationSequence == null ? List.of() : verificationSequence);
        for (VerificationFact fact : verificationSequence) Objects.requireNonNull(fact);
        initialExecutionTreeHash = optionalSha(initialExecutionTreeHash, "initialExecutionTreeHash");
        finalTreeHash = optionalSha(finalTreeHash, "finalTreeHash");
        rewindTreeHash = optionalSha(rewindTreeHash, "rewindTreeHash");
        boundedErrorCategory = bound(boundedErrorCategory, 128);
        boundedErrorExcerpt = bound(boundedErrorExcerpt, 4096);
    }

    public CoreDemoWorkflowResult(
            String runId,String scenarioId,String workspaceIdentityHash,
            TerminalCategory terminalCategory,Phase completedPhase,FactStatus planning,
            FactStatus codeIntelligence,int codeIntelligenceQueries,
            int codeIntelligenceContextItems,FactStatus execution,
            List<String> changedRelativeFiles,int snapshotCount,
            List<VerificationFact> verificationSequence,int repairCount,
            FactStatus resume,FactStatus rewind,String initialExecutionTreeHash,
            String finalTreeHash,String rewindTreeHash,String boundedErrorCategory,
            String boundedErrorExcerpt,CleanupRecommendation cleanupRecommendation) {
        this(runId,scenarioId,workspaceIdentityHash,terminalCategory,completedPhase,planning,
                codeIntelligence,codeIntelligenceQueries,codeIntelligenceContextItems,
                execution,changedRelativeFiles,snapshotCount,verificationSequence,repairCount,
                resume,rewind,initialExecutionTreeHash,finalTreeHash,rewindTreeHash,
                boundedErrorCategory,boundedErrorExcerpt,cleanupRecommendation,
                CoreDemoSemanticFacts.empty());
    }

    public boolean successful() { return terminalCategory == TerminalCategory.COMPLETED; }

    private static List<String> normalizePaths(List<String> paths) {
        if (paths == null) return List.of();
        if (paths.size() > 32) throw new IllegalArgumentException("Too many changed files");
        TreeSet<String> normalized = new TreeSet<>();
        for (String path : paths) {
            try {
                CoreDemoFixtureManifestLoader.validateRelativePath(path);
            } catch (Exception e) {
                throw new IllegalArgumentException("Unsafe changed path", e);
            }
            if (!normalized.add(path)) throw new IllegalArgumentException("Duplicate changed path");
        }
        return List.copyOf(normalized);
    }

    private static void requireText(String value, int max, String name) {
        if (value == null || value.isBlank() || value.length() > max
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    private static void requireSha(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    private static String optionalSha(String value, String name) {
        if (value == null) return null;
        requireSha(value, name);
        return value;
    }

    private static String bound(String value, int max) {
        if (value == null) return null;
        String cleaned = value.replace('\r', ' ').replace('\n', ' ').replace('\0', ' ');
        return cleaned.substring(0, Math.min(max, cleaned.length()));
    }

    public enum TerminalCategory {
        COMPLETED,
        PRIMARY_WORKFLOW_SUCCEEDED,
        PLAN_CODE_INTELLIGENCE_EXECUTION_FAILED,
        VERIFICATION_REPAIR_FAILED,
        DEMO_SCENARIO_DRIFT,
        RESUME_REWIND_FAILED,
        INTERNAL_ERROR
    }

    public enum Phase {
        PLAN, CODE_INTELLIGENCE, EXECUTE, VERIFY, REPAIR, RESUME, REWIND
    }

    public enum FactStatus { NOT_RUN, PASSED, FAILED }

    public enum CleanupRecommendation { CLEAN_RUNTIME, RETAIN_FOR_DIAGNOSTICS }

    public record VerificationFact(String stage, FactStatus status, String category) {
        public VerificationFact {
            requireText(stage, 64, "verification stage");
            Objects.requireNonNull(status, "status");
            category = bound(category, 128);
        }
    }
}
