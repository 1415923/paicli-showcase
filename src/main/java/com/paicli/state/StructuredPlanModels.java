package com.paicli.state;

import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.paicli.state.PlanCompletionModels.CompletionRuleType;
import com.paicli.state.PlanCompletionModels.EffectClass;
import com.paicli.state.PlanCompletionModels.EvidenceKind;

/** Immutable R1C-A Plan definition and authority records. */
public final class StructuredPlanModels {
    private StructuredPlanModels() {}

    public enum Lifecycle { DRAFT, VALIDATED, APPROVED, REJECTED, SUPERSEDED }
    public enum LineageRelationship { ADDED, PRESERVED, REPLACED, SPLIT, MERGED, REMOVED }
    public enum PlanApplicability { PLAN_REQUIRED, PLAN_DISABLED, LEGACY_PLAN_ONLY }
    public enum AuthoritySelectorKind {
        EXACT_PATH, EXACT_GREP, EXACT_COMMAND, EXACT_PATH_SET, EXACT_GIT_DIFF
    }
    public enum FreshnessPolicy { RESUME_REUSE_ONLY, FRESH_EXECUTION_REQUIRED }

    /** Canonical authority material derived from a typed, untrusted Provider selector. */
    public record ToolAuthoritySelector(AuthoritySelectorKind kind, String canonicalJson,
            String digest) {
        public ToolAuthoritySelector {
            Objects.requireNonNull(kind, "kind");
            if (blank(canonicalJson) || !hash(digest)
                    || !PlanToolRequirementPolicy.digest(canonicalJson).equals(digest)) {
                throw new IllegalArgumentException("Invalid Tool authority selector");
            }
        }
    }

    /** Immutable per-Step Tool authority and success-cardinality requirement. */
    public record ToolRequirement(String requirementId, int requirementOrder, String toolName,
            EffectClass effectClass, ToolAuthoritySelector authoritySelector,
            int minSuccesses, int maxOccurrences, FreshnessPolicy freshnessPolicy) {
        public ToolRequirement {
            if (blank(requirementId) || requirementId.length() > 128
                    || !requirementId.matches("[A-Za-z0-9._:-]+")
                    || requirementOrder < 0 || blank(toolName) || effectClass == null
                    || authoritySelector == null || minSuccesses < 1
                    || maxOccurrences < minSuccesses || maxOccurrences > 64
                    || freshnessPolicy == null) {
                throw new IllegalArgumentException("Invalid Tool requirement");
            }
            PlanToolRequirementPolicy.validate(toolName, effectClass, authoritySelector);
        }
    }

    public record StepDefinition(CanonicalId stepRevisionId, CanonicalId logicalStepId,
            int order, String title, String definition, CompletionRuleType completionRule,
            List<EvidenceKind> requiredEvidenceKinds, EffectClass effectClass,
            List<ToolRequirement> toolRequirements, String canonicalHash) {
        public StepDefinition(CanonicalId stepRevisionId, CanonicalId logicalStepId,
                int order, String title, String definition, String canonicalHash) {
            this(stepRevisionId, logicalStepId, order, title, definition,
                    null, List.of(), null, List.of(), canonicalHash);
        }

        /** Source-compatible V2 constructor. */
        public StepDefinition(CanonicalId stepRevisionId, CanonicalId logicalStepId,
                int order, String title, String definition, CompletionRuleType completionRule,
                List<EvidenceKind> requiredEvidenceKinds, EffectClass effectClass,
                String canonicalHash) {
            this(stepRevisionId, logicalStepId, order, title, definition, completionRule,
                    requiredEvidenceKinds, effectClass, List.of(), canonicalHash);
        }

        public StepDefinition {
            requireFamily(stepRevisionId, EntityFamily.PLAN_STEP);
            requireFamily(logicalStepId, EntityFamily.PLAN_STEP);
            if (order < 0 || blank(title) || blank(definition) || !hash(canonicalHash)) {
                throw new IllegalArgumentException("Invalid immutable Plan step definition");
            }
            requiredEvidenceKinds = requiredEvidenceKinds == null
                    ? List.of() : requiredEvidenceKinds.stream()
                            .sorted(java.util.Comparator.comparing(EvidenceKind::name)).toList();
            toolRequirements = toolRequirements == null ? List.of()
                    : toolRequirements.stream().sorted(java.util.Comparator
                            .comparingInt(ToolRequirement::requirementOrder)
                            .thenComparing(ToolRequirement::requirementId)).toList();
            if (toolRequirements.size() != toolRequirements.stream()
                    .map(ToolRequirement::requirementId).distinct().count()
                    || toolRequirements.size() != toolRequirements.stream()
                            .map(ToolRequirement::requirementOrder).distinct().count()) {
                throw new IllegalArgumentException("Tool requirement identity/order must be unique");
            }
            for (int index = 0; index < toolRequirements.size(); index++) {
                if (toolRequirements.get(index).requirementOrder() != index) {
                    throw new IllegalArgumentException("Tool requirement order must be contiguous");
                }
            }
            boolean legacyProfile = completionRule == null
                    && requiredEvidenceKinds.isEmpty() && effectClass == null;
            if (!legacyProfile) {
                PlanCompletionModels.validateCompletionProfile(completionRule,
                        requiredEvidenceKinds, effectClass);
            }
        }

        public boolean hasCompletionProfile() {
            return completionRule != null && effectClass != null;
        }
    }

    public record Dependency(CanonicalId stepRevisionId, CanonicalId dependsOnStepRevisionId) {
        public Dependency {
            requireFamily(stepRevisionId, EntityFamily.PLAN_STEP);
            requireFamily(dependsOnStepRevisionId, EntityFamily.PLAN_STEP);
            if (stepRevisionId.equals(dependsOnStepRevisionId)) {
                throw new IllegalArgumentException("A Plan step cannot depend on itself");
            }
        }
    }

    public record Lineage(String lineageId, CanonicalId sourcePlanRevisionId,
            CanonicalId sourceStepRevisionId, CanonicalId targetPlanRevisionId,
            CanonicalId targetStepRevisionId, LineageRelationship relationship,
            String provenance, Instant createdAt) {
        public Lineage {
            if (blank(lineageId) || relationship == null || blank(provenance) || createdAt == null) {
                throw new IllegalArgumentException("Invalid Plan step lineage");
            }
            optionalFamily(sourcePlanRevisionId, EntityFamily.PLAN);
            requireFamily(targetPlanRevisionId, EntityFamily.PLAN);
            optionalFamily(sourceStepRevisionId, EntityFamily.PLAN_STEP);
            optionalFamily(targetStepRevisionId, EntityFamily.PLAN_STEP);
            if ((sourcePlanRevisionId == null) != (sourceStepRevisionId == null)) {
                throw new IllegalArgumentException("Plan lineage source endpoint must be complete");
            }
            boolean validEndpoints=switch(relationship){
                case ADDED -> sourceStepRevisionId==null&&targetStepRevisionId!=null;
                case REMOVED -> sourceStepRevisionId!=null&&targetStepRevisionId==null;
                case PRESERVED,REPLACED,SPLIT,MERGED ->
                        sourceStepRevisionId!=null&&targetStepRevisionId!=null;
            };
            if(!validEndpoints){
                throw new IllegalArgumentException("Plan lineage endpoints do not match relationship");
            }
            if (Objects.equals(sourceStepRevisionId, targetStepRevisionId)) {
                throw new IllegalArgumentException("Plan lineage cannot be a self-loop");
            }
        }
    }

    public record Revision(CanonicalId planRevisionId, CanonicalId taskId, int revisionNumber,
            String goal, String acceptanceCriteria, String assumptions, String risks,
            String verificationStrategy, String canonicalHash, String sourceType,
            String provenance, CanonicalId createdByRunId, Instant createdAt,
            List<StepDefinition> steps, List<Dependency> dependencies, List<Lineage> lineage,
            int canonicalSchemaVersion) {
        public static final int CANONICAL_SCHEMA_V1 = 1;
        public static final int CANONICAL_SCHEMA_V2 = 2;
        public static final int CANONICAL_SCHEMA_V3 = 3;

        public Revision(CanonicalId planRevisionId, CanonicalId taskId, int revisionNumber,
                String goal, String acceptanceCriteria, String assumptions, String risks,
                String verificationStrategy, String canonicalHash, String sourceType,
                String provenance, CanonicalId createdByRunId, Instant createdAt,
                List<StepDefinition> steps, List<Dependency> dependencies, List<Lineage> lineage) {
            this(planRevisionId, taskId, revisionNumber, goal, acceptanceCriteria, assumptions,
                    risks, verificationStrategy, canonicalHash, sourceType, provenance,
                    createdByRunId, createdAt, steps, dependencies, lineage, CANONICAL_SCHEMA_V2);
        }

        public Revision {
            requireFamily(planRevisionId, EntityFamily.PLAN);
            requireFamily(taskId, EntityFamily.TASK);
            requireFamily(createdByRunId, EntityFamily.RUN);
            if (revisionNumber < 1 || blank(goal) || acceptanceCriteria == null
                    || assumptions == null || risks == null || verificationStrategy == null
                    || !hash(canonicalHash) || blank(sourceType) || blank(provenance)
                    || createdAt == null) {
                throw new IllegalArgumentException("Invalid immutable Plan revision");
            }
            steps = List.copyOf(steps);
            dependencies = List.copyOf(dependencies);
            lineage = List.copyOf(lineage);
            if (steps.isEmpty()) throw new IllegalArgumentException("A Plan revision requires steps");
            if (canonicalSchemaVersion != CANONICAL_SCHEMA_V1
                    && canonicalSchemaVersion != CANONICAL_SCHEMA_V2
                    && canonicalSchemaVersion != CANONICAL_SCHEMA_V3) {
                throw new IllegalArgumentException("Unsupported Plan canonical schema version");
            }
            boolean expectedProfile = canonicalSchemaVersion >= CANONICAL_SCHEMA_V2;
            if (steps.stream().anyMatch(step -> step.hasCompletionProfile() != expectedProfile)) {
                throw new IllegalArgumentException(
                        "Plan step completion profile does not match canonical schema version");
            }
            if (canonicalSchemaVersion < CANONICAL_SCHEMA_V3
                    && steps.stream().anyMatch(step -> !step.toolRequirements().isEmpty())) {
                throw new IllegalArgumentException("Legacy Plan cannot contain Tool requirements");
            }
            if (canonicalSchemaVersion == CANONICAL_SCHEMA_V3) {
                long requirementCount = steps.stream().mapToLong(
                        step -> step.toolRequirements().size()).sum();
                long uniqueRequirementIds = steps.stream().flatMap(
                        step -> step.toolRequirements().stream())
                        .map(ToolRequirement::requirementId).distinct().count();
                if (requirementCount != uniqueRequirementIds) {
                    throw new IllegalArgumentException(
                            "Tool requirement identity must be unique within a Plan revision");
                }
                for (StepDefinition step : steps) {
                    boolean toolEvidence = step.requiredEvidenceKinds().contains(EvidenceKind.TOOL_CALL);
                    if (toolEvidence != !step.toolRequirements().isEmpty()) {
                        throw new IllegalArgumentException(
                                "TOOL_CALL Evidence requires non-empty structured Tool requirements");
                    }
                }
            }
        }
    }

    public record Authority(CanonicalId taskId, CanonicalId planRevisionId, String approvalId,
            long pointerVersion, CanonicalId activatedByRunId, Instant activatedAt) {
        public Authority {
            requireFamily(taskId, EntityFamily.TASK);
            requireFamily(planRevisionId, EntityFamily.PLAN);
            requireFamily(activatedByRunId, EntityFamily.RUN);
            if (blank(approvalId) || pointerVersion < 0 || activatedAt == null) {
                throw new IllegalArgumentException("Invalid Plan authority pointer");
            }
        }
    }

    public record ApprovedPlan(Revision revision, Authority authority) {
        public ApprovedPlan {
            Objects.requireNonNull(revision); Objects.requireNonNull(authority);
            if (!revision.taskId().equals(authority.taskId())
                    || !revision.planRevisionId().equals(authority.planRevisionId())) {
                throw new IllegalArgumentException("Plan authority does not match its revision");
            }
        }
    }

    private static void requireFamily(CanonicalId id, EntityFamily family) {
        if (id == null || id.family() != family) throw new IllegalArgumentException("Expected " + family);
    }
    private static void optionalFamily(CanonicalId id, EntityFamily family) {
        if (id != null) requireFamily(id, family);
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static boolean hash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
}
