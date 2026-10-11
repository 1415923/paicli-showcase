package com.paicli.state;

import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;

import java.time.Instant;
import java.util.List;

/** Typed R1E facts and authority records. */
public final class FollowUpModels {
    private FollowUpModels() { }

    public enum CandidateState { CANDIDATE, NEEDS_INTENT_CONFIRMATION, PREPARING,
        NEEDS_RISK_CONFIRMATION, READY_TO_ADMIT, ADMITTED, CANCELLED, FAILED, HEAD_CONFLICT }
    public enum Intent { CONTINUE_CURRENT_THREAD, CREATE_NEW_THREAD, UNKNOWN, REJECT }
    public enum ConfirmationKind { INTENT, RISK }
    public enum ConfirmationDecision { CONTINUE_CURRENT_THREAD, CREATE_NEW_THREAD,
        ACCEPT_RISK, REJECT, CANCEL }
    public enum Risk { LOW, HIGH, UNKNOWN, BLOCKING }
    public enum DiffCategory { UNCHANGED, ADDED, REMOVED, CHANGED, REORDERED, STATUS_CHANGED }
    public enum ChildStartStatus { CLAIMED, ALREADY_CLAIMED, ALREADY_STARTED,
        TERMINAL, AUTHORITY_INVALID, CONFLICT }
    public enum FactType { PARENT_GOAL, FINAL_RESULT, COMPLETION, PLAN, STEP,
        EVIDENCE_REFERENCE, ARTIFACT_REFERENCE, CONSTRAINT, CONFIRMED_STEERING, MANIFEST, RISK }

    public record CapsuleFact(FactType type, String scopeIdentity, String boundedPayload,
                              String referenceIdentity, String referenceHash, String provenance) { }
    public record CapsuleDefinition(CanonicalId id, int schemaVersion, CanonicalId threadId,
            CanonicalId parentTaskId, CanonicalId parentRunId, CanonicalId parentGenerationId,
            String completionAnchorType, String completionAnchorId, String parentManifestId,
            String parentManifestHash, CanonicalId parentPlanRevisionId, String parentPlanHash,
            String canonicalHash, Instant createdAt, List<CapsuleFact> facts) {
        public CapsuleDefinition {
            require(id, EntityFamily.CAPSULE); require(threadId, EntityFamily.THREAD);
            require(parentTaskId, EntityFamily.TASK); require(parentRunId, EntityFamily.RUN);
            require(parentGenerationId, EntityFamily.GENERATION); facts=List.copyOf(facts);
        }
    }
    public record Candidate(CanonicalId capsuleId, CanonicalId threadId, CanonicalId parentTaskId,
            long expectedHeadVersion, String instruction, String instructionHash, String provenance,
            String inputOwner, String inputSessionId, CandidateState state, long version,
            String manifestCandidateHash, Risk risk, CanonicalId childTaskId, Instant createdAt) { }
    public record IntentClassification(Intent intent, String version, String reasonCode) { }
    public record ManifestDiff(DiffCategory category, String sourcePath, String parentHash,
                               String childHash, String boundedSummary, String provenance) { }
    public record PreparedManifest(String manifestId, InstructionManifestCandidate candidate,
            List<ProtectedInstructionArtifact> artifacts, String sourceFingerprint,
            String workspaceIdentity, List<ManifestDiff> diffs, Risk risk, String riskReason,
            Instant preparedAt) {
        public PreparedManifest { artifacts=List.copyOf(artifacts);diffs=List.copyOf(diffs); }
    }
    public record ParentAnchor(CanonicalId threadId, CanonicalId taskId, long headVersion,
            String goal, String status, CanonicalId terminalRunId, CanonicalId generationId,
            String completionAnchorType, String completionAnchorId, String manifestId,
            String manifestHash, CanonicalId planRevisionId, String planHash,
            long completionEvaluationNumber, String completionRulesHash,
            List<String> completionEvidenceHashes) {
        public ParentAnchor {
            completionEvidenceHashes = completionEvidenceHashes == null
                    ? List.of() : List.copyOf(completionEvidenceHashes);
        }
    }
    public record ChildAuthority(CanonicalId taskId, CanonicalId runId, CanonicalId generationId,
            String manifestId, String manifestHash, long threadHeadVersion) { }
    public record PendingChildStart(Candidate candidate, ChildAuthority child) { }
    public record ChildStartClaim(ChildAuthority child, R1aDurableModels.OwnershipLease lease,
            long launchVersion) { }
    public record ChildStartResult(ChildStartStatus status, ChildStartClaim claim) { }
    public sealed interface AdmissionResult permits Admitted, Rejected { }
    public record Admitted(ChildAuthority child, boolean existing) implements AdmissionResult { }
    public record Rejected(String code) implements AdmissionResult { }

    private static void require(CanonicalId id, EntityFamily family) {
        if(id==null||id.family()!=family)throw new IllegalArgumentException("Expected "+family);
    }
}
