package com.paicli.state;

import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;
import com.paicli.state.PlanCompletionModels.EffectClass;
import com.paicli.state.StructuredPlanModels.AuthoritySelectorKind;
import com.paicli.state.StructuredPlanModels.FreshnessPolicy;
import com.paicli.state.StructuredPlanModels.ToolAuthoritySelector;
import com.paicli.state.StructuredPlanModels.ToolRequirement;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Read mapping and transaction-local write primitives for the unique V28 slot truth. */
public final class PlanRequirementStore {
    public record RequirementRecord(CanonicalId taskId, CanonicalId planRevisionId,
            CanonicalId stepRevisionId, ToolRequirement requirement) {
        public RequirementRecord {
            require(taskId, EntityFamily.TASK); require(planRevisionId, EntityFamily.PLAN);
            require(stepRevisionId, EntityFamily.PLAN_STEP);
            Objects.requireNonNull(requirement, "requirement");
        }
    }

    public record OccurrenceSlotBinding(CanonicalId taskId, CanonicalId planRevisionId,
            CanonicalId stepRevisionId, String requirementId, int slotOrdinal,
            CanonicalId runId, int attemptNumber, CanonicalId occurrenceId,
            String toolCallId, String occurrenceStatus, String selectorDigest, Instant boundAt) {
        public OccurrenceSlotBinding {
            scope(taskId, planRevisionId, stepRevisionId, requirementId, slotOrdinal,
                    runId, attemptNumber, occurrenceId, toolCallId, selectorDigest, boundAt);
            if (occurrenceStatus != null && !List.of("STARTED", "COMPLETED", "FAILED", "UNKNOWN")
                    .contains(occurrenceStatus)) throw new IllegalArgumentException("Invalid occurrence status");
        }
    }

    public record EvidenceSlotBinding(CanonicalId taskId, CanonicalId planRevisionId,
            CanonicalId stepRevisionId, String requirementId, int slotOrdinal,
            CanonicalId runId, int attemptNumber, CanonicalId occurrenceId,
            String toolCallId, String evidenceStatus, String selectorDigest, Instant boundAt) {
        public EvidenceSlotBinding {
            scope(taskId, planRevisionId, stepRevisionId, requirementId, slotOrdinal,
                    runId, attemptNumber, occurrenceId, toolCallId, selectorDigest, boundAt);
            if (!"SUCCEEDED".equals(evidenceStatus)) {
                throw new IllegalArgumentException("Evidence slot requires successful Evidence");
            }
        }
    }

    public record UnmetSlot(RequirementRecord requirement, int slotOrdinal,
            int successfulSlots, int boundOccurrences, int remainingOccurrences) {
        public UnmetSlot {
            Objects.requireNonNull(requirement, "requirement");
            if (slotOrdinal < 1 || successfulSlots < 0 || boundOccurrences < 0
                    || remainingOccurrences < 0) throw new IllegalArgumentException("Invalid unmet slot");
        }
    }

    private final Connection connection;
    public PlanRequirementStore(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    public List<RequirementRecord> requirements(CanonicalId taskId, CanonicalId revisionId,
            CanonicalId stepId) {
        try { return requirements(connection, taskId, revisionId, stepId); }
        catch (SQLException error) { throw new IllegalStateException("Plan requirements read failed", error); }
    }

    public List<OccurrenceSlotBinding> bindings(CanonicalId taskId, CanonicalId revisionId) {
        return bindings(taskId, revisionId, null);
    }

    public List<OccurrenceSlotBinding> bindings(CanonicalId taskId, CanonicalId revisionId,
            CanonicalId stepId) {
        try {
            String stepClause = stepId == null ? "" : " AND binding.step_revision_id=?";
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT binding.step_revision_id,binding.requirement_id,binding.slot_ordinal,
                        binding.run_id,binding.attempt_number,binding.occurrence_id,
                        binding.tool_call_id,occurrence.status,binding.selector_digest,binding.bound_at
                    FROM plan_occurrence_slot_bindings binding
                    JOIN side_effect_occurrences occurrence
                      ON occurrence.occurrence_id=binding.occurrence_id
                    WHERE binding.task_id=? AND binding.plan_revision_id=?
                    """ + stepClause + " ORDER BY binding.step_revision_id,binding.requirement_id,binding.slot_ordinal")) {
                statement.setString(1, taskId.value()); statement.setString(2, revisionId.value());
                if (stepId != null) statement.setString(3, stepId.value());
                List<OccurrenceSlotBinding> result = new ArrayList<>();
                try (ResultSet row = statement.executeQuery()) {
                    while (row.next()) result.add(new OccurrenceSlotBinding(taskId, revisionId,
                            CanonicalId.parse(row.getString(1)), row.getString(2), row.getInt(3),
                            CanonicalId.parse(row.getString(4)), row.getInt(5),
                            CanonicalId.parse(row.getString(6)), row.getString(7), row.getString(8),
                            row.getString(9), Instant.parse(row.getString(10))));
                }
                return List.copyOf(result);
            }
        } catch (SQLException error) { throw new IllegalStateException("Plan slot bindings read failed", error); }
    }

    public List<EvidenceSlotBinding> evidenceBindings(CanonicalId taskId,
            CanonicalId revisionId, CanonicalId stepId) {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT binding.requirement_id,binding.slot_ordinal,binding.run_id,
                    binding.attempt_number,binding.occurrence_id,binding.tool_call_id,
                    evidence.observed_status,binding.selector_digest,binding.bound_at
                FROM plan_evidence_slot_bindings binding
                JOIN plan_evidence evidence ON evidence.task_id=binding.task_id
                  AND evidence.plan_revision_id=binding.plan_revision_id
                  AND evidence.step_revision_id=binding.step_revision_id
                  AND evidence.run_id=binding.run_id
                  AND evidence.attempt_number=binding.attempt_number
                  AND evidence.evidence_kind=binding.evidence_kind
                  AND evidence.producer_kind=binding.producer_kind
                  AND evidence.producer_identity=binding.occurrence_id
                WHERE binding.task_id=? AND binding.plan_revision_id=?
                  AND binding.step_revision_id=?
                ORDER BY binding.requirement_id,binding.slot_ordinal
                """)) {
            bind(statement, taskId.value(), revisionId.value(), stepId.value());
            List<EvidenceSlotBinding> result = new ArrayList<>();
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) result.add(new EvidenceSlotBinding(taskId, revisionId, stepId,
                        row.getString(1), row.getInt(2), CanonicalId.parse(row.getString(3)),
                        row.getInt(4), CanonicalId.parse(row.getString(5)), row.getString(6),
                        row.getString(7), row.getString(8), Instant.parse(row.getString(9))));
            }
            return List.copyOf(result);
        } catch (SQLException error) { throw new IllegalStateException("Plan Evidence bindings read failed", error); }
    }

    /** Required success slots not yet backed by one unique successful Evidence binding. */
    public List<UnmetSlot> unmetSlots(CanonicalId taskId, CanonicalId revisionId,
            CanonicalId stepId) {
        List<RequirementRecord> requirements = requirements(taskId, revisionId, stepId);
        List<OccurrenceSlotBinding> occurrences = bindings(taskId, revisionId, stepId);
        List<EvidenceSlotBinding> evidence = evidenceBindings(taskId, revisionId, stepId);
        List<UnmetSlot> result = new ArrayList<>();
        for (RequirementRecord record : requirements) {
            ToolRequirement requirement = record.requirement();
            int bound = (int) occurrences.stream()
                    .filter(value -> value.requirementId().equals(requirement.requirementId())).count();
            int succeeded = (int) evidence.stream()
                    .filter(value -> value.requirementId().equals(requirement.requirementId())).count();
            for (int slot = succeeded + 1; slot <= requirement.minSuccesses(); slot++) {
                result.add(new UnmetSlot(record, slot, succeeded, bound,
                        Math.max(0, requirement.maxOccurrences() - bound)));
            }
        }
        return List.copyOf(result);
    }

    static List<RequirementRecord> requirements(Connection connection, CanonicalId taskId,
            CanonicalId revisionId, CanonicalId stepId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT requirement_id,requirement_order,tool_name,effect_class,selector_kind,
                    selector_canonical_json,selector_digest,min_successes,max_occurrences,
                    freshness_policy
                FROM plan_tool_requirements
                WHERE task_id=? AND plan_revision_id=? AND step_revision_id=?
                ORDER BY requirement_order,requirement_id
                """)) {
            bind(statement, taskId.value(), revisionId.value(), stepId.value());
            List<RequirementRecord> result = new ArrayList<>();
            try (ResultSet row = statement.executeQuery()) {
                while (row.next()) {
                    ToolAuthoritySelector selector = new ToolAuthoritySelector(
                            AuthoritySelectorKind.valueOf(row.getString(5)), row.getString(6),
                            row.getString(7));
                    ToolRequirement requirement = new ToolRequirement(row.getString(1),
                            row.getInt(2), row.getString(3), EffectClass.valueOf(row.getString(4)),
                            selector, row.getInt(8), row.getInt(9),
                            FreshnessPolicy.valueOf(row.getString(10)));
                    result.add(new RequirementRecord(taskId, revisionId, stepId, requirement));
                }
            }
            return List.copyOf(result);
        }
    }

    static void bindOccurrence(Connection connection, OccurrenceSlotBinding value)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO plan_occurrence_slot_bindings(task_id,plan_revision_id,
                    step_revision_id,requirement_id,slot_ordinal,run_id,attempt_number,
                    occurrence_id,tool_call_id,selector_digest,bound_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            bind(statement, value.taskId().value(), value.planRevisionId().value(),
                    value.stepRevisionId().value(), value.requirementId(), value.slotOrdinal(),
                    value.runId().value(), value.attemptNumber(), value.occurrenceId().value(),
                    value.toolCallId(), value.selectorDigest(), value.boundAt().toString());
            statement.executeUpdate();
        }
    }

    static void bindEvidence(Connection connection, EvidenceSlotBinding value)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO plan_evidence_slot_bindings(task_id,plan_revision_id,
                    step_revision_id,requirement_id,slot_ordinal,run_id,attempt_number,
                    occurrence_id,tool_call_id,evidence_kind,producer_kind,selector_digest,bound_at)
                VALUES(?,?,?,?,?,?,?,?,?,'TOOL_CALL','OCCURRENCE',?,?)
                """)) {
            bind(statement, value.taskId().value(), value.planRevisionId().value(),
                    value.stepRevisionId().value(), value.requirementId(), value.slotOrdinal(),
                    value.runId().value(), value.attemptNumber(), value.occurrenceId().value(),
                    value.toolCallId(), value.selectorDigest(), value.boundAt().toString());
            statement.executeUpdate();
        }
    }

    private static void scope(CanonicalId taskId, CanonicalId revisionId, CanonicalId stepId,
            String requirementId, int slotOrdinal, CanonicalId runId, int attemptNumber,
            CanonicalId occurrenceId, String toolCallId, String digest, Instant at) {
        require(taskId, EntityFamily.TASK); require(revisionId, EntityFamily.PLAN);
        require(stepId, EntityFamily.PLAN_STEP); require(runId, EntityFamily.RUN);
        require(occurrenceId, EntityFamily.OCCURRENCE);
        if (requirementId == null || requirementId.isBlank() || slotOrdinal < 1
                || attemptNumber < 1 || toolCallId == null || toolCallId.isBlank()
                || digest == null || !digest.matches("[0-9a-f]{64}") || at == null) {
            throw new IllegalArgumentException("Invalid Plan slot binding");
        }
    }

    private static void require(CanonicalId id, EntityFamily family) {
        if (id == null || id.family() != family) throw new IllegalArgumentException("Expected " + family);
    }
    private static void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
    }
}
