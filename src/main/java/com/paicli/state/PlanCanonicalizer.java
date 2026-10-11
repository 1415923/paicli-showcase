package com.paicli.state;

import com.paicli.state.StructuredPlanModels.Dependency;
import com.paicli.state.StructuredPlanModels.Lineage;
import com.paicli.state.StructuredPlanModels.Revision;
import com.paicli.state.StructuredPlanModels.StepDefinition;
import com.paicli.state.StructuredPlanModels.ToolRequirement;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;

/** Stable, length-delimited serialization for immutable Plan definition authority. */
public final class PlanCanonicalizer {
    private PlanCanonicalizer() {}

    public static String hash(Revision revision) {
        return switch (revision.canonicalSchemaVersion()) {
            case Revision.CANONICAL_SCHEMA_V1 -> hash(revision, false);
            case Revision.CANONICAL_SCHEMA_V2 -> hash(revision, true);
            case Revision.CANONICAL_SCHEMA_V3 -> hashV3(revision);
            default -> throw new IllegalArgumentException("Unsupported Plan canonical schema version");
        };
    }

    /** Independent V3 domain; the V1/V2 byte serialization below remains unchanged. */
    private static String hashV3(Revision revision) {
        StringBuilder out = new StringBuilder("PAICLI-PLAN-TOOL-AUTHORITY-V3");
        field(out, revision.planRevisionId().value()); field(out, revision.taskId().value());
        field(out, Integer.toString(revision.revisionNumber())); field(out, revision.goal());
        field(out, revision.acceptanceCriteria()); field(out, revision.assumptions());
        field(out, revision.risks()); field(out, revision.verificationStrategy());
        field(out, revision.sourceType()); field(out, revision.provenance());
        field(out, revision.createdByRunId().value());
        revision.steps().stream().sorted(Comparator.comparingInt(StepDefinition::order)).forEach(step -> {
            field(out, "STEP"); field(out, step.stepRevisionId().value());
            field(out, step.logicalStepId().value()); field(out, Integer.toString(step.order()));
            field(out, step.title()); field(out, step.definition());
            field(out, step.completionRule().name()); field(out, step.effectClass().name());
            step.requiredEvidenceKinds().forEach(kind -> field(out, kind.name()));
            step.toolRequirements().stream().sorted(Comparator
                    .comparingInt(ToolRequirement::requirementOrder)
                    .thenComparing(ToolRequirement::requirementId)).forEach(requirement -> {
                field(out, "TOOL_REQUIREMENT"); field(out, requirement.requirementId());
                field(out, Integer.toString(requirement.requirementOrder()));
                field(out, requirement.toolName()); field(out, requirement.effectClass().name());
                field(out, requirement.authoritySelector().kind().name());
                field(out, requirement.authoritySelector().canonicalJson());
                field(out, requirement.authoritySelector().digest());
                field(out, Integer.toString(requirement.minSuccesses()));
                field(out, Integer.toString(requirement.maxOccurrences()));
                field(out, requirement.freshnessPolicy().name());
            });
            field(out, step.canonicalHash());
        });
        revision.dependencies().stream().sorted(Comparator
                .comparing((Dependency value) -> value.stepRevisionId().value())
                .thenComparing(value -> value.dependsOnStepRevisionId().value())).forEach(value -> {
            field(out, "DEPENDENCY"); field(out, value.stepRevisionId().value());
            field(out, value.dependsOnStepRevisionId().value());
        });
        revision.lineage().stream().sorted(Comparator.comparing(Lineage::lineageId)).forEach(value -> {
            field(out, "LINEAGE"); field(out, value.lineageId());
            field(out, id(value.sourcePlanRevisionId())); field(out, id(value.sourceStepRevisionId()));
            field(out, id(value.targetPlanRevisionId())); field(out, id(value.targetStepRevisionId()));
            field(out, value.relationship().name()); field(out, value.provenance());
        });
        return sha256(out);
    }

    private static String hash(Revision revision, boolean completionProfile) {
        StringBuilder out = new StringBuilder(completionProfile
                ? "R1C-A-PLAN-V2" : "R1C-A-PLAN-V1");
        field(out, revision.planRevisionId().value()); field(out, revision.taskId().value());
        field(out, Integer.toString(revision.revisionNumber())); field(out, revision.goal());
        field(out, revision.acceptanceCriteria()); field(out, revision.assumptions());
        field(out, revision.risks()); field(out, revision.verificationStrategy());
        field(out, revision.sourceType()); field(out, revision.provenance());
        field(out, revision.createdByRunId().value());
        revision.steps().stream().sorted(Comparator.comparingInt(StepDefinition::order)).forEach(step -> {
            field(out, "STEP"); field(out, step.stepRevisionId().value());
            field(out, step.logicalStepId().value()); field(out, Integer.toString(step.order()));
            field(out, step.title()); field(out, step.definition());
            if (completionProfile) {
                field(out, step.completionRule().name()); field(out, step.effectClass().name());
                step.requiredEvidenceKinds().forEach(kind -> field(out, kind.name()));
            }
            field(out, step.canonicalHash());
        });
        revision.dependencies().stream().sorted(Comparator
                .comparing((Dependency value) -> value.stepRevisionId().value())
                .thenComparing(value -> value.dependsOnStepRevisionId().value())).forEach(value -> {
            field(out, "DEPENDENCY"); field(out, value.stepRevisionId().value());
            field(out, value.dependsOnStepRevisionId().value());
        });
        revision.lineage().stream().sorted(Comparator.comparing(Lineage::lineageId)).forEach(value -> {
            field(out, "LINEAGE"); field(out, value.lineageId());
            field(out, id(value.sourcePlanRevisionId())); field(out, id(value.sourceStepRevisionId()));
            field(out, id(value.targetPlanRevisionId())); field(out, id(value.targetStepRevisionId()));
            field(out, value.relationship().name()); field(out, value.provenance());
        });
        return sha256(out);
    }

    private static String sha256(StringBuilder out) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(out.toString().getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException("SHA-256 unavailable", error); }
    }

    private static void field(StringBuilder out, String value) {
        String safe = value == null ? "" : value;
        out.append('|').append(safe.length()).append(':').append(safe);
    }
    private static String id(com.paicli.identity.CanonicalId value) {
        return value == null ? "" : value.value();
    }
}
