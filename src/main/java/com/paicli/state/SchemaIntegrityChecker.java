package com.paicli.state;

import com.paicli.identity.CanonicalId;
import com.paicli.identity.EntityFamily;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Read-only latest-schema audit. Findings never contain protected instruction payloads. */
public final class SchemaIntegrityChecker {
    private static final Set<String> BASE_REQUIRED_TABLES = Set.of(
            "work_threads", "task_identities", "thread_heads", "protected_instruction_artifacts",
            "instruction_manifests", "instruction_entries", "durable_runs",
            "constraint_revisions", "task_constraint_activations", "task_context_generations",
            "task_context_pointers", "side_effect_occurrences", "task_ownership_leases",
            "legacy_aliases", "plan_revisions", "plan_revision_lifecycle",
            "plan_step_definitions", "plan_step_dependencies", "plan_step_lineage",
            "plan_approvals", "task_plan_authorities", "task_plan_applicability",
            "plan_run_bindings", "plan_v3_revisions", "plan_tool_requirements",
            "plan_occurrence_slot_bindings", "plan_evidence_slot_bindings",
            "plan_step_executions", "plan_step_attempts",
            "plan_evidence", "plan_verification_evidence", "plan_completion_rules",
            "plan_completion_requirements", "plan_completion_evaluations",
            "plan_completion_evaluation_evidence", "plan_completion_evaluation_steps",
            "task_completion_decisions", "task_user_results", "steering_definitions",
            "steering_classification_facts", "steering_confirmation_facts",
            "steering_queue_authority", "steering_state_facts",
            "steering_application_facts", "steering_constraint_provenance",
            "steering_approval_conflict_facts", "steering_producer_pause_facts",
            "continuation_capsules","continuation_capsule_facts","followup_candidate_definitions",
            "followup_candidate_authority","followup_intent_classification_facts","followup_confirmation_facts",
            "child_manifest_candidates","child_manifest_candidate_entries","manifest_diff_facts",
            "followup_risk_classification_facts","task_continuation_edges","capsule_child_bindings",
            "followup_admission_facts","child_initial_run_launches",
            "task_continuity_segments",
            ProviderTelemetrySchemaContract.TABLE_NAME);
    private static final Set<String> REQUIRED_TABLES = requiredTables();
    private static final Set<String> BASE_REQUIRED_INDEXES = Set.of(
            "idx_r1a_task_thread_created", "idx_r1a_runs_task_created",
            "idx_r1a_generations_task_created", "idx_r1a_constraint_task_revision",
            "idx_r1a_manifest_entries_order", "idx_r1a_occurrence_task_status",
            "idx_r1a_occurrence_run_operation", "idx_r1a_legacy_alias_lookup",
            "idx_r1a_legacy_alias_target", "idx_r1c_plan_revisions_task_number",
            "idx_r1c_plan_steps_revision_order", "idx_r1c_plan_lineage_target",
            "idx_r1c_plan_approvals_revision", "idx_r1c_plan_bindings_revision",
            "idx_r1c_step_execution_revision_state", "idx_r1c_step_attempt_history",
            "idx_r1c_step_attempt_one_running", "idx_r1c_evidence_scope",
            "idx_r1c_evidence_producer", "idx_r1c_completion_evaluation",
            "idx_plan_tool_requirements_step", "idx_plan_occurrence_slots_step",
            "idx_plan_evidence_slots_step",
            "idx_task_user_results_revision",
            "idx_r1d_steering_task_created", "idx_r1d_steering_queue_order",
            "idx_r1d_steering_queue_run","idx_r1e_plan_revision_hash_scope","idx_r1e_candidate_parent","idx_r1e_candidate_state",
            "idx_r1e_capsule_parent","idx_r1e_child_launch_state",
            "idx_task_continuity_run");
    private static final Set<String> REQUIRED_INDEXES = requiredIndexes();
    private static final Set<String> REQUIRED_TRIGGERS = Set.of(
            "r1a_constraint_revision_immutable", "r1a_manifest_immutable",
            "r1a_manifest_entry_immutable", "r1a_artifact_immutable",
            "r1a_occurrence_no_regression", "r1c_plan_revision_immutable",
            "r1c_plan_revision_no_delete", "r1c_plan_step_immutable",
            "r1c_plan_step_no_delete", "r1c_plan_dependency_immutable",
            "r1c_plan_dependency_no_delete", "r1c_plan_lineage_immutable",
            "r1c_plan_lineage_no_delete", "r1c_plan_approval_immutable",
            "r1c_plan_approval_no_delete", "r1c_plan_binding_immutable",
            "r1c_plan_binding_no_delete", "r1c_plan_applicability_immutable",
            "r1c_plan_applicability_no_delete", "r1c_step_execution_requires_binding",
            "r1c_step_attempt_requires_binding", "r1c_step_execution_transition",
            "r1c_step_execution_identity_immutable", "r1c_step_execution_no_delete",
            "r1c_step_execution_terminal_immutable",
            "r1c_step_attempt_transition", "r1c_step_attempt_identity_immutable",
            "r1c_step_attempt_terminal_immutable", "r1c_step_attempt_no_delete",
            "r1c_step_completion_profile_version",
            "r1c_evidence_producer_exists", "r1c_evidence_immutable",
            "r1c_evidence_no_delete", "r1c_verification_evidence_immutable",
            "r1c_verification_evidence_no_delete",
            "r1c_completion_rule_immutable", "r1c_completion_rule_no_delete",
            "r1c_completion_requirement_immutable", "r1c_completion_requirement_no_delete",
            "r1c_completion_evaluation_immutable", "r1c_completion_evaluation_no_delete",
            "r1c_completion_evaluation_evidence_no_delete",
            "r1c_completion_evaluation_evidence_immutable",
            "r1c_completion_evaluation_steps_no_delete",
            "r1c_completion_evaluation_steps_immutable",
            "r1c_completion_decision_immutable", "r1c_completion_decision_no_delete",
            "task_user_result_content_immutable", "task_user_result_no_delete",
            "plan_v3_revision_immutable", "plan_v3_revision_no_delete",
            "plan_tool_requirement_immutable", "plan_tool_requirement_no_delete",
            "plan_occurrence_slot_immutable", "plan_occurrence_slot_no_delete",
            "plan_occurrence_slot_authoritative", "plan_evidence_slot_immutable",
            "plan_evidence_slot_no_delete", "plan_evidence_slot_authoritative",
            "r1d_steering_definition_immutable", "r1d_steering_definition_no_delete",
            "r1d_steering_classification_immutable", "r1d_steering_confirmation_immutable",
            "r1d_steering_state_fact_immutable", "r1d_steering_application_immutable",
            "r1d_steering_classification_no_delete", "r1d_steering_confirmation_no_delete",
            "r1d_steering_state_fact_no_delete", "r1d_steering_application_no_delete",
            "r1d_steering_constraint_provenance_immutable",
            "r1d_steering_constraint_provenance_no_delete",
            "r1d_steering_approval_conflict_immutable",
            "r1d_steering_approval_conflict_no_delete",
            "r1d_steering_producer_pause_immutable",
            "r1d_steering_producer_pause_no_delete",
            "r1d_steering_terminal_immutable", "r1d_steering_queue_transition",
            "r1e_capsule_immutable","r1e_capsule_no_delete","r1e_capsule_fact_immutable","r1e_capsule_fact_no_delete",
            "r1e_followup_definition_immutable","r1e_followup_definition_no_delete","r1e_followup_intent_immutable",
            "r1e_followup_intent_no_delete","r1e_followup_confirmation_immutable","r1e_followup_confirmation_no_delete",
            "r1e_child_manifest_candidate_immutable","r1e_child_manifest_candidate_no_delete",
            "r1e_child_manifest_entry_immutable","r1e_child_manifest_entry_no_delete",
            "r1e_manifest_diff_immutable","r1e_manifest_diff_no_delete","r1e_risk_immutable","r1e_risk_no_delete",
            "r1e_task_edge_immutable","r1e_task_edge_no_delete","r1e_binding_immutable","r1e_binding_no_delete",
            "r1e_admission_fact_immutable","r1e_admission_fact_no_delete","r1e_candidate_terminal_immutable",
            "r1e_candidate_initial_shape","r1e_candidate_transition","r1e_candidate_transition_version",
            "r1e_candidate_version_requires_transition","r1e_prepared_binding_publish_once","r1e_confirmation_scope",
            "r1e_unknown_intent_requires_confirmation","r1e_ready_requires_risk_authority",
            "r1e_prepared_capsule_publish_state","r1e_prepared_manifest_publish_state",
            "r1e_candidate_terminal_requires_fact","r1e_head_conflict_fact_shape",
            "r1e_head_conflict_fact_apply","r1e_terminal_fact_apply",
            "r1e_parent_row_immutable","r1e_binding_parent_completed",
            "r1e_child_launch_no_delete","r1e_child_launch_transition","r1e_child_launch_terminal_immutable",
            "task_continuity_identity_immutable","task_continuity_no_delete",
            "verification_candidate_identity_insert",
            "verification_candidate_identity_update_valid",
            "verification_candidate_identity_immutable",
            "h5_verification_evidence_candidate_bound");
    private static final Map<String, Set<String>> REQUIRED_COLUMNS = Map.ofEntries(
            Map.entry("work_threads", Set.of("thread_id", "workspace_identity", "status",
                    "legacy_state", "created_at", "updated_at")),
            Map.entry("file_snapshots", Set.of("snapshot_id", "task_id", "status",
                    "owner_run_id")),
            Map.entry("verification_runs", Set.of("verification_id", "run_id", "task_id",
                    "status", "candidate_digest_kind", "candidate_digest")),
            Map.entry("task_identities", Set.of("task_id", "thread_id", "parent_task_id",
                    "admitted_manifest_id", "admitted_manifest_hash", "created_at")),
            Map.entry("thread_heads", Set.of("thread_id", "head_task_id", "pointer_version")),
            Map.entry("instruction_manifests", Set.of("instruction_manifest_id", "task_id",
                    "schema_version", "resolution_policy_version", "canonical_hash", "state")),
            Map.entry("instruction_entries", Set.of("entry_id", "instruction_manifest_id",
                    "status", "applied_order", "content_hash", "artifact_id", "exclusion_reason")),
            Map.entry("protected_instruction_artifacts", Set.of("artifact_id", "content_hash",
                    "protected_payload", "storage_reference", "integrity_status")),
            Map.entry("durable_runs", Set.of("run_id", "task_id", "thread_id",
                    "instruction_manifest_id", "instruction_manifest_hash")),
            Map.entry("constraint_revisions", Set.of("constraint_revision_id", "task_id",
                    "revision_number", "canonical_hash")),
            Map.entry("task_constraint_activations", Set.of("task_id", "active_revision_id",
                    "pointer_version")),
            Map.entry("task_context_generations", Set.of("generation_id", "task_id",
                    "parent_generation_id", "created_by_run_id", "instruction_manifest_id",
                    "instruction_manifest_hash", "state")),
            Map.entry("task_context_pointers", Set.of("task_id", "active_generation_id",
                    "owner_run_id", "pointer_version")),
            Map.entry("side_effect_occurrences", Set.of("occurrence_id", "thread_id", "task_id",
                    "run_id", "logical_operation_key", "status", "final_candidate_digest",
                    "started_at", "completed_at")),
            Map.entry("task_ownership_leases", Set.of("task_id", "owner_instance_id",
                    "owner_run_id", "lease_token", "fencing_token", "cas_version",
                    "acquired_at", "heartbeat_at", "expires_at")),
            Map.entry("legacy_aliases", Set.of("legacy_entity_family", "legacy_value",
                    "canonical_target_id", "classification", "mapping_status", "source_type",
                    "source_reference", "provenance", "continuation_status",
                    "migration_version")),
            Map.entry("plan_revisions", Set.of("plan_revision_id", "task_id",
                    "revision_number", "canonical_hash", "created_by_run_id",
                    "canonical_schema_version")),
            Map.entry("plan_revision_lifecycle", Set.of("plan_revision_id", "task_id",
                    "state", "cas_version", "updated_by_run_id")),
            Map.entry("plan_step_definitions", Set.of("step_revision_id", "logical_step_id",
                    "plan_revision_id", "task_id", "step_order", "canonical_hash",
                    "completion_rule", "required_evidence_kinds", "effect_class")),
            Map.entry("plan_step_dependencies", Set.of("task_id", "plan_revision_id",
                    "step_revision_id", "depends_on_step_revision_id")),
            Map.entry("plan_step_lineage", Set.of("lineage_id", "task_id",
                    "source_plan_revision_id", "source_step_revision_id",
                    "target_plan_revision_id", "target_step_revision_id", "relationship")),
            Map.entry("plan_approvals", Set.of("approval_id", "task_id",
                    "plan_revision_id", "decision", "approved_by_run_id")),
            Map.entry("task_plan_authorities", Set.of("task_id", "active_plan_revision_id",
                    "active_revision_state", "active_approval_id", "active_approval_decision",
                    "pointer_version", "activated_by_run_id")),
            Map.entry("task_plan_applicability", Set.of("task_id", "applicability",
                    "declared_by_run_id", "declared_at")),
            Map.entry("plan_run_bindings", Set.of("binding_id", "task_id", "run_id",
                    "plan_revision_id", "approval_id", "approval_decision", "plan_hash")),
            Map.entry("plan_v3_revisions", Set.of("task_id", "plan_revision_id",
                    "canonical_schema_version")),
            Map.entry("plan_tool_requirements", Set.of("task_id", "plan_revision_id",
                    "step_revision_id", "requirement_id", "requirement_order", "tool_name",
                    "effect_class", "selector_kind", "selector_canonical_json",
                    "selector_digest", "min_successes", "max_occurrences", "freshness_policy")),
            Map.entry("plan_occurrence_slot_bindings", Set.of("task_id", "plan_revision_id",
                    "step_revision_id", "requirement_id", "slot_ordinal", "run_id",
                    "attempt_number", "occurrence_id", "tool_call_id", "selector_digest",
                    "bound_at")),
            Map.entry("plan_evidence_slot_bindings", Set.of("task_id", "plan_revision_id",
                    "step_revision_id", "requirement_id", "slot_ordinal", "run_id",
                    "attempt_number", "occurrence_id", "tool_call_id", "evidence_kind",
                    "producer_kind", "selector_digest", "bound_at")),
            Map.entry("plan_step_executions", Set.of("task_id", "plan_revision_id",
                    "step_revision_id", "state", "transition_version",
                    "current_attempt_number", "initialized_by_run_id",
                    "last_updated_by_run_id", "provenance")),
            Map.entry("plan_step_attempts", Set.of("task_id", "plan_revision_id",
                    "step_revision_id", "run_id", "attempt_number", "state",
                    "transition_version", "start_iteration", "provenance")),
            Map.entry("plan_evidence", Set.of("task_id", "plan_revision_id",
                    "step_revision_id", "run_id", "attempt_number", "evidence_kind",
                    "producer_kind", "producer_identity", "observed_status",
                    "canonical_hash", "provenance", "created_at")),
            Map.entry("plan_verification_evidence", Set.of("task_id", "plan_revision_id",
                    "step_revision_id", "attempt_run_id", "attempt_number", "verification_id",
                    "verification_run_id", "verification_status")),
            Map.entry("plan_completion_rules", Set.of("task_id", "plan_revision_id",
                    "rule_order", "rule_type", "canonical_hash", "provenance")),
            Map.entry("plan_completion_requirements", Set.of("task_id", "plan_revision_id",
                    "rule_order", "requirement_order", "requirement_type", "step_revision_id")),
            Map.entry("plan_completion_evaluations", Set.of("task_id", "plan_revision_id",
                    "run_id", "evaluation_number", "result", "rules_hash", "provenance")),
            Map.entry("plan_completion_evaluation_evidence", Set.of("task_id",
                    "plan_revision_id", "run_id", "evaluation_number", "step_revision_id",
                    "attempt_run_id", "attempt_number", "evidence_kind", "producer_kind",
                    "producer_identity")),
            Map.entry("plan_completion_evaluation_steps", Set.of("task_id",
                    "plan_revision_id", "run_id", "evaluation_number", "step_revision_id",
                    "observed_state", "transition_version", "current_attempt_number")),
            Map.entry("task_completion_decisions", Set.of("task_id", "plan_revision_id",
                    "run_id", "evaluation_number", "result", "decision_hash", "provenance")),
            Map.entry("task_user_results", Set.of("task_id", "run_id", "plan_revision_id",
                    "response_mode", "content", "content_hash", "evidence_hash",
                    "validation_status", "render_status", "created_at", "rendered_at")),
            Map.entry("steering_definitions", Set.of("steering_id", "thread_id", "task_id",
                    "target_run_id", "target_plan_revision_id", "target_generation_id",
                    "target_context_pointer_version", "content_hash", "input_owner", "input_session_id")),
            Map.entry("steering_classification_facts", Set.of("steering_id", "fact_number",
                    "classification", "outcome", "classifier_version")),
            Map.entry("steering_confirmation_facts", Set.of("steering_id", "confirmation_number",
                    "target_run_id", "input_owner", "decision")),
            Map.entry("steering_queue_authority", Set.of("steering_id", "task_id", "target_run_id",
                    "state", "queue_sequence", "queue_version", "target_context_pointer_version")),
            Map.entry("steering_state_facts", Set.of("steering_id", "state_number", "state",
                    "queue_version", "target_run_id")),
            Map.entry("steering_application_facts", Set.of("steering_id", "applied_by_run_id",
                    "queue_version", "application_kind", "application_hash")),
            Map.entry("steering_constraint_provenance", Set.of("steering_id",
                    "constraint_revision_id", "parent_constraint_revision_id", "canonical_hash")),
            Map.entry("steering_approval_conflict_facts", Set.of("steering_id",
                    "conflict_number", "approval_reference", "conflict_type")),
            Map.entry("steering_producer_pause_facts", Set.of("occurrence_id",
                    "steering_id", "task_id", "run_id", "producer_kind",
                    "producer_identity", "reason_code")),
            Map.entry("continuation_capsules",Set.of("capsule_id","schema_version","thread_id","parent_task_id",
                    "parent_run_id","parent_generation_id","completion_anchor_type","canonical_hash")),
            Map.entry("followup_candidate_definitions",Set.of("capsule_id","thread_id","parent_task_id",
                    "expected_head_version","instruction_hash","input_owner","input_session_id")),
            Map.entry("followup_candidate_authority",Set.of("capsule_id","state","candidate_version",
                    "manifest_candidate_hash","risk","admitted_child_task_id","retention_disposition")),
            Map.entry("followup_confirmation_facts",Set.of("capsule_id","confirmation_kind",
                    "expected_candidate_version","input_owner","input_session_id","decision",
                    "prepared_manifest_hash","prepared_risk")),
            Map.entry("child_manifest_candidates",Set.of("capsule_id","instruction_manifest_id","canonical_hash",
                    "source_fingerprint","workspace_identity")),
            Map.entry("manifest_diff_facts",Set.of("capsule_id","diff_number","category","source_path",
                    "parent_hash","child_hash","bounded_summary","provenance")),
            Map.entry("followup_risk_classification_facts",Set.of("capsule_id","risk",
                    "classifier_version","reason_code")),
            Map.entry("task_continuation_edges",Set.of("thread_id","parent_task_id","child_task_id","capsule_id")),
            Map.entry("capsule_child_bindings",Set.of("capsule_id","parent_task_id","child_task_id","child_run_id",
                    "child_generation_id","child_manifest_id","binding_hash")),
            Map.entry("followup_admission_facts",Set.of("capsule_id","result","expected_head_version",
                    "observed_head_task_id","observed_head_version","source_state",
                    "source_candidate_version","resulting_candidate_version")),
            Map.entry("child_initial_run_launches",Set.of("capsule_id","child_task_id","child_run_id","state",
                    "launch_version","claim_attempt","claim_owner_instance_id","claim_lease_token",
                    "claim_fencing_token","claim_lease_version","failure_reason")),
            Map.entry("task_continuity_segments",Set.of("task_id","run_id","segment_ordinal",
                    "planning_attempt_ordinal","trigger","state","policy_hash","start_iteration",
                    "end_iteration","provider_turns","tool_calls","input_tokens","output_tokens",
                    "active_elapsed_ms","recovery_directive","terminal_reason","created_at","updated_at")),
            Map.entry("approval_decisions", Set.of("intent_hash")));

    private final Connection connection;

    public SchemaIntegrityChecker(Connection connection) {
        this.connection = Objects.requireNonNull(connection);
    }

    public IntegrityReport check() throws SQLException {
        List<Finding> findings = new ArrayList<>();
        checkSchema(findings);
        if (!requiredTablesPresent()) {
            return new IntegrityReport(List.copyOf(findings));
        }
        checkTypedIds(findings);
        checkThreadTaskRun(findings);
        checkGenerationPointerConstraint(findings);
        checkPlanAuthority(findings);
        checkManifest(findings);
        checkOccurrence(findings);
        checkOwnership(findings);
        checkLegacy(findings);
        return new IntegrityReport(List.copyOf(findings));
    }

    private void checkSchema(List<Finding> findings) throws SQLException {
        if (scalar("PRAGMA foreign_keys") != 1) {
            add(findings, "SCHEMA_FOREIGN_KEYS_DISABLED", Severity.ERROR, Area.SCHEMA,
                    "database", "SQLite foreign key enforcement is disabled", "PRAGMA foreign_keys != 1");
        }
        if (scalar("SELECT COALESCE(max(version),0) FROM schema_migrations") != 33) {
            add(findings, "SCHEMA_VERSION_MISMATCH", Severity.ERROR, Area.SCHEMA,
                    "schema_migrations", "Expected schema version V33", "max(version) is not 33");
        }
        for (String table : REQUIRED_TABLES) {
            if (!ProviderTelemetrySchemaContract.TABLE_NAME.equals(table))
                checkObject(findings, "table", table);
        }
        for (String index : REQUIRED_INDEXES) {
            if (!ProviderTelemetrySchemaContract.expectedIndexes().contains(index))
                checkObject(findings, "index", index);
        }
        for (String trigger : REQUIRED_TRIGGERS) checkObject(findings, "trigger", trigger);
        for (Map.Entry<String, Set<String>> expected : REQUIRED_COLUMNS.entrySet()) {
            if (!objectExists("table", expected.getKey())) continue;
            Set<String> actual = columns(expected.getKey());
            for (String column : expected.getValue()) {
                if (!actual.contains(column)) {
                    add(findings, "SCHEMA_COLUMN_MISSING", Severity.ERROR, Area.SCHEMA,
                            expected.getKey(), "Required latest-schema column is missing", column);
                }
            }
        }
        checkProviderTelemetryContract(findings);
        checkContextSchemaContract(findings);
        checkUnique(findings, "task_identities", List.of("thread_id", "task_id"));
        checkUnique(findings, "durable_runs", List.of("task_id", "run_id"));
        checkUnique(findings, "durable_runs", List.of("thread_id", "task_id", "run_id"));
        checkUnique(findings, "constraint_revisions", List.of("task_id", "constraint_revision_id"));
        checkUnique(findings, "task_context_generations", List.of("task_id", "generation_id"));
        checkUnique(findings, "instruction_manifests",
                List.of("task_id", "instruction_manifest_id", "canonical_hash"));
        checkUnique(findings, "protected_instruction_artifacts",
                List.of("artifact_id", "content_hash"));
        checkForeignKey(findings, "thread_heads", "task_identities",
                List.of("thread_id", "head_task_id"), List.of("thread_id", "task_id"));
        checkForeignKey(findings, "durable_runs", "task_identities",
                List.of("thread_id", "task_id"), List.of("thread_id", "task_id"));
        checkForeignKey(findings, "durable_runs", "instruction_manifests",
                List.of("task_id", "instruction_manifest_id", "instruction_manifest_hash"),
                List.of("task_id", "instruction_manifest_id", "canonical_hash"));
        checkForeignKey(findings, "task_context_generations", "durable_runs",
                List.of("task_id", "created_by_run_id"), List.of("task_id", "run_id"));
        checkForeignKey(findings, "task_context_generations", "instruction_manifests",
                List.of("task_id", "instruction_manifest_id", "instruction_manifest_hash"),
                List.of("task_id", "instruction_manifest_id", "canonical_hash"));
        checkForeignKey(findings, "task_context_pointers", "task_context_generations",
                List.of("task_id", "active_generation_id"), List.of("task_id", "generation_id"));
        checkForeignKey(findings, "task_context_pointers", "durable_runs",
                List.of("task_id", "owner_run_id"), List.of("task_id", "run_id"));
        checkForeignKey(findings, "task_constraint_activations", "constraint_revisions",
                List.of("task_id", "active_revision_id"),
                List.of("task_id", "constraint_revision_id"));
        checkForeignKey(findings, "task_ownership_leases", "durable_runs",
                List.of("task_id", "owner_run_id"), List.of("task_id", "run_id"));
        queryFindings(findings, "PRAGMA foreign_key_check", "SCHEMA_FOREIGN_KEY_VIOLATION",
                Area.SCHEMA, "Stored row violates a declared foreign key");
        String taskDdl = objectSql("table", "task_identities");
        if (taskDdl != null && !taskDdl.toUpperCase(java.util.Locale.ROOT)
                .contains("DEFERRABLE INITIALLY DEFERRED")) {
            add(findings, "SCHEMA_DEFERRED_FK_MISSING", Severity.ERROR, Area.SCHEMA,
                    "task_identities", "Task-to-Manifest authority FK must be deferred",
                    "DEFERRABLE INITIALLY DEFERRED absent");
        }
    }

    private void checkProviderTelemetryContract(List<Finding> findings) throws SQLException {
        String table = ProviderTelemetrySchemaContract.TABLE_NAME;
        boolean tableExists = objectExists("table", table);
        boolean shapeMatches = tableExists
                && columns(table).equals(ProviderTelemetrySchemaContract.expectedColumns())
                && (!ProviderTelemetrySchemaContract.EXPECTED_STRICT || tableStrict(table));
        attestSql(findings, "table", table,
                ProviderTelemetrySchemaContract.tableFingerprint(),
                "V25_TELEMETRY_TABLE_CONTRACT_MISMATCH", shapeMatches);
        Map<String, String> expectedIndexes =
                ProviderTelemetrySchemaContract.indexFingerprints();
        for (String index : ProviderTelemetrySchemaContract.expectedIndexes()) {
            attestSql(findings, "index", index, expectedIndexes.get(index),
                    "V25_TELEMETRY_INDEX_CONTRACT_MISMATCH");
        }
    }

    private void checkContextSchemaContract(List<Finding> findings) throws SQLException {
        for(Map.Entry<String,String> table:ContextSchemaContract.tableFingerprints().entrySet()){
            boolean shape=columns(table.getKey()).equals(
                    ContextSchemaContract.expectedColumns().get(table.getKey()))
                    &&(!ContextSchemaContract.EXPECTED_STRICT||tableStrict(table.getKey()));
            attestSql(findings,"table",table.getKey(),table.getValue(),
                    "V26_CONTEXT_TABLE_CONTRACT_MISMATCH",shape);
        }
        attestContracts(findings, "index", ContextSchemaContract.indexFingerprints(),
                "V26_CONTEXT_INDEX_CONTRACT_MISMATCH");
        attestContracts(findings, "trigger", ContextSchemaContract.triggerFingerprints(),
                "V26_CONTEXT_TRIGGER_CONTRACT_MISMATCH");
    }

    private void attestContracts(List<Finding> findings, String type,
            Map<String,String> expected, String code) throws SQLException {
        for (Map.Entry<String,String> object : expected.entrySet()) {
            String definition=objectSql(type,object.getKey());
            String actual=null;
            try { if(definition!=null) actual=ProviderTelemetrySchemaContract.fingerprint(definition); }
            catch(RuntimeException ignored) { actual=null; }
            boolean strict=!"table".equals(type) || (!ContextSchemaContract.EXPECTED_STRICT
                    || tableStrict(object.getKey()));
            if(strict && Objects.equals(object.getValue(),actual)) continue;
            add(findings,code,Severity.ERROR,Area.SCHEMA,object.getKey(),
                    "V26 context canonical schema contract mismatch",
                    "contract="+ContextSchemaContract.CONTRACT_VERSION
                            +" expected="+fingerprintPrefix(object.getValue())
                            +" actual="+fingerprintPrefix(actual));
        }
    }

    private void attestSql(List<Finding> findings, String type, String name,
            String expectedFingerprint, String code) throws SQLException {
        attestSql(findings, type, name, expectedFingerprint, code, true);
    }

    private void attestSql(List<Finding> findings, String type, String name,
            String expectedFingerprint, String code, boolean auxiliaryContractMatches)
            throws SQLException {
        String definition = objectSql(type, name);
        String actualFingerprint;
        try {
            actualFingerprint = definition == null ? null
                    : ProviderTelemetrySchemaContract.fingerprint(definition);
        } catch (RuntimeException invalidDefinition) {
            actualFingerprint = null;
        }
        if (auxiliaryContractMatches
                && Objects.equals(expectedFingerprint, actualFingerprint)) return;
        add(findings, code, Severity.ERROR, Area.SCHEMA, name,
                "V25 telemetry canonical schema contract mismatch",
                "contract=" + ProviderTelemetrySchemaContract.CONTRACT_VERSION
                        + " expected=" + fingerprintPrefix(expectedFingerprint)
                        + " actual=" + fingerprintPrefix(actualFingerprint));
    }

    private boolean tableStrict(String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA table_list")) {
            while (rows.next()) {
                if (table.equals(rows.getString("name"))
                        && "table".equals(rows.getString("type"))) {
                    return rows.getInt("strict") == 1;
                }
            }
            return false;
        }
    }

    private static String fingerprintPrefix(String value) {
        return value == null ? "unavailable" : value.substring(0, Math.min(12, value.length()));
    }

    private static Set<String> requiredIndexes() {
        java.util.LinkedHashSet<String> result =
                new java.util.LinkedHashSet<>(BASE_REQUIRED_INDEXES);
        result.addAll(ProviderTelemetrySchemaContract.expectedIndexes());
        result.addAll(ContextSchemaContract.indexNames());
        return Set.copyOf(result);
    }

    private static Set<String> requiredTables() {
        java.util.LinkedHashSet<String> result =
                new java.util.LinkedHashSet<>(BASE_REQUIRED_TABLES);
        result.addAll(ContextSchemaContract.tableNames());
        return Set.copyOf(result);
    }

    private void checkTypedIds(List<Finding> findings) throws SQLException {
        auditIds(findings, "work_threads", "thread_id", EntityFamily.THREAD);
        auditIds(findings, "task_identities", "task_id", EntityFamily.TASK);
        auditIds(findings, "task_identities", "thread_id", EntityFamily.THREAD);
        auditIds(findings, "task_identities", "parent_task_id", EntityFamily.TASK);
        auditIds(findings, "thread_heads", "thread_id", EntityFamily.THREAD);
        auditIds(findings, "thread_heads", "head_task_id", EntityFamily.TASK);
        auditIds(findings, "durable_runs", "run_id", EntityFamily.RUN);
        auditIds(findings, "durable_runs", "task_id", EntityFamily.TASK);
        auditIds(findings, "durable_runs", "thread_id", EntityFamily.THREAD);
        auditIds(findings, "durable_runs", "resumed_from_run_id", EntityFamily.RUN);
        auditIds(findings, "instruction_manifests", "task_id", EntityFamily.TASK);
        auditIds(findings, "constraint_revisions", "task_id", EntityFamily.TASK);
        auditIds(findings, "task_constraint_activations", "task_id", EntityFamily.TASK);
        auditIds(findings, "task_constraint_activations", "activated_by_run_id", EntityFamily.RUN);
        auditIds(findings, "task_context_generations", "generation_id", EntityFamily.GENERATION);
        auditIds(findings, "task_context_generations", "task_id", EntityFamily.TASK);
        auditIds(findings, "task_context_generations", "parent_generation_id",
                EntityFamily.GENERATION);
        auditIds(findings, "task_context_generations", "created_by_run_id", EntityFamily.RUN);
        auditIds(findings, "task_context_pointers", "task_id", EntityFamily.TASK);
        auditIds(findings, "task_context_pointers", "active_generation_id",
                EntityFamily.GENERATION);
        auditIds(findings, "task_context_pointers", "owner_run_id", EntityFamily.RUN);
        auditIds(findings, "context_generations", "context_generation_id", EntityFamily.CONTEXT);
        auditIds(findings, "context_generations", "thread_id", EntityFamily.THREAD);
        auditIds(findings, "context_generations", "task_id", EntityFamily.TASK);
        auditIds(findings, "context_generations", "run_id", EntityFamily.RUN);
        auditIds(findings, "context_generations", "source_authority_generation_id",
                EntityFamily.GENERATION);
        auditIds(findings, "context_generations", "source_context_generation_id",
                EntityFamily.CONTEXT);
        auditIds(findings, "side_effect_occurrences", "occurrence_id", EntityFamily.OCCURRENCE);
        auditIds(findings, "side_effect_occurrences", "thread_id", EntityFamily.THREAD);
        auditIds(findings, "side_effect_occurrences", "task_id", EntityFamily.TASK);
        auditIds(findings, "side_effect_occurrences", "run_id", EntityFamily.RUN);
        auditIds(findings, "task_ownership_leases", "task_id", EntityFamily.TASK);
        auditIds(findings, "task_ownership_leases", "owner_run_id", EntityFamily.RUN);
        auditIds(findings, "plan_revisions", "plan_revision_id", EntityFamily.PLAN);
        auditIds(findings, "plan_revisions", "task_id", EntityFamily.TASK);
        auditIds(findings, "plan_revisions", "created_by_run_id", EntityFamily.RUN);
        auditIds(findings, "plan_step_definitions", "step_revision_id", EntityFamily.PLAN_STEP);
        auditIds(findings, "steering_definitions", "steering_id", EntityFamily.STEERING);
        auditIds(findings, "steering_definitions", "thread_id", EntityFamily.THREAD);
        auditIds(findings, "steering_definitions", "task_id", EntityFamily.TASK);
        auditIds(findings, "steering_definitions", "target_run_id", EntityFamily.RUN);
        auditIds(findings, "steering_definitions", "target_plan_revision_id", EntityFamily.PLAN);
        auditIds(findings, "steering_definitions", "target_generation_id", EntityFamily.GENERATION);
        auditIds(findings, "plan_step_definitions", "logical_step_id", EntityFamily.PLAN_STEP);
        auditIds(findings, "plan_step_definitions", "plan_revision_id", EntityFamily.PLAN);
        auditIds(findings, "task_plan_authorities", "active_plan_revision_id", EntityFamily.PLAN);
        auditIds(findings, "task_plan_authorities", "activated_by_run_id", EntityFamily.RUN);
        auditIds(findings, "plan_run_bindings", "plan_revision_id", EntityFamily.PLAN);
        auditIds(findings, "plan_run_bindings", "run_id", EntityFamily.RUN);
        auditIds(findings, "plan_step_executions", "task_id", EntityFamily.TASK);
        auditIds(findings, "plan_step_executions", "plan_revision_id", EntityFamily.PLAN);
        auditIds(findings, "plan_step_executions", "step_revision_id", EntityFamily.PLAN_STEP);
        auditIds(findings, "plan_step_attempts", "task_id", EntityFamily.TASK);
        auditIds(findings, "plan_step_attempts", "plan_revision_id", EntityFamily.PLAN);
        auditIds(findings, "plan_step_attempts", "step_revision_id", EntityFamily.PLAN_STEP);
        auditIds(findings, "plan_step_attempts", "run_id", EntityFamily.RUN);
        auditIds(findings, "plan_v3_revisions", "task_id", EntityFamily.TASK);
        auditIds(findings, "plan_v3_revisions", "plan_revision_id", EntityFamily.PLAN);
        auditIds(findings, "plan_tool_requirements", "task_id", EntityFamily.TASK);
        auditIds(findings, "plan_tool_requirements", "plan_revision_id", EntityFamily.PLAN);
        auditIds(findings, "plan_tool_requirements", "step_revision_id", EntityFamily.PLAN_STEP);
        auditIds(findings, "plan_occurrence_slot_bindings", "occurrence_id", EntityFamily.OCCURRENCE);
        auditIds(findings, "plan_evidence_slot_bindings", "occurrence_id", EntityFamily.OCCURRENCE);
        auditIds(findings, "plan_evidence", "task_id", EntityFamily.TASK);
        auditIds(findings, "plan_evidence", "plan_revision_id", EntityFamily.PLAN);
        auditIds(findings, "plan_evidence", "step_revision_id", EntityFamily.PLAN_STEP);
        auditIds(findings, "plan_evidence", "run_id", EntityFamily.RUN);
        auditIds(findings, "plan_completion_evaluations", "task_id", EntityFamily.TASK);
        auditIds(findings, "plan_completion_evaluations", "plan_revision_id", EntityFamily.PLAN);
        auditIds(findings, "plan_completion_evaluations", "run_id", EntityFamily.RUN);
    }

    private void checkPlanAuthority(List<Finding> findings) throws SQLException {
        queryFindings(findings, """
                SELECT a.task_id FROM task_plan_authorities a
                LEFT JOIN plan_revision_lifecycle l ON l.task_id=a.task_id
                    AND l.plan_revision_id=a.active_plan_revision_id
                LEFT JOIN plan_approvals p ON p.task_id=a.task_id
                    AND p.plan_revision_id=a.active_plan_revision_id
                    AND p.approval_id=a.active_approval_id
                WHERE l.state IS NULL OR l.state<>'APPROVED'
                   OR p.decision IS NULL OR p.decision<>'APPROVED' OR a.pointer_version<1
                """, "PLAN_ACTIVE_AUTHORITY_INVALID", Area.PLAN,
                "Active Plan authority is not backed by one approved immutable revision");
        queryFindings(findings, """
                SELECT b.binding_id FROM plan_run_bindings b
                LEFT JOIN task_plan_authorities a ON a.task_id=b.task_id
                LEFT JOIN plan_revisions p ON p.task_id=b.task_id
                    AND p.plan_revision_id=b.plan_revision_id
                WHERE p.plan_revision_id IS NULL OR p.canonical_hash<>b.plan_hash
                """, "PLAN_RUN_BINDING_INVALID", Area.PLAN,
                "Plan Run binding references missing or hash-mismatched authority");
        queryFindings(findings, """
                SELECT plan_revision_id FROM plan_step_definitions
                GROUP BY plan_revision_id HAVING min(step_order)<>0
                    OR max(step_order)<>count(*)-1 OR count(DISTINCT step_order)<>count(*)
                """, "PLAN_STEP_ORDER_NOT_DENSE", Area.PLAN,
                "Immutable Plan Step order is not dense and deterministic");
        queryFindings(findings, """
                SELECT p.plan_revision_id FROM plan_revisions p
                JOIN plan_step_definitions d ON d.task_id=p.task_id
                    AND d.plan_revision_id=p.plan_revision_id
                WHERE (p.canonical_schema_version=1
                        AND (d.completion_rule IS NOT NULL
                          OR d.required_evidence_kinds IS NOT NULL
                          OR d.effect_class IS NOT NULL))
                   OR (p.canonical_schema_version=2
                        AND (d.completion_rule IS NULL
                          OR d.required_evidence_kinds IS NULL
                          OR trim(d.required_evidence_kinds)=''
                          OR d.effect_class IS NULL))
                """, "PLAN_CANONICAL_PROFILE_VERSION_MISMATCH", Area.PLAN,
                "Plan Step completion profile does not match its persisted canonical version");
        queryFindings(findings, """
                SELECT marker.plan_revision_id FROM plan_v3_revisions marker
                JOIN plan_revisions revision ON revision.task_id=marker.task_id
                    AND revision.plan_revision_id=marker.plan_revision_id
                WHERE marker.canonical_schema_version<>3
                   OR revision.canonical_schema_version<>2
                """, "PLAN_V3_EFFECTIVE_VERSION_INVALID", Area.PLAN,
                "V3 effective-version marker is inconsistent with the additive V22 base encoding");
        queryFindings(findings, """
                SELECT step.step_revision_id FROM plan_v3_revisions marker
                JOIN plan_step_definitions step ON step.task_id=marker.task_id
                    AND step.plan_revision_id=marker.plan_revision_id
                WHERE (((','||step.required_evidence_kinds||',') LIKE '%,TOOL_CALL,%')
                        <> EXISTS (SELECT 1 FROM plan_tool_requirements requirement
                            WHERE requirement.task_id=step.task_id
                              AND requirement.plan_revision_id=step.plan_revision_id
                              AND requirement.step_revision_id=step.step_revision_id))
                """, "PLAN_V3_TOOL_SCOPE_MISSING", Area.PLAN,
                "V3 TOOL_CALL Evidence and immutable structured Tool requirements do not match");
        queryFindings(findings, """
                SELECT step_revision_id FROM plan_tool_requirements
                GROUP BY task_id,plan_revision_id,step_revision_id
                HAVING min(requirement_order)<>0 OR max(requirement_order)<>count(*)-1
                    OR count(DISTINCT requirement_order)<>count(*)
                """, "PLAN_V3_REQUIREMENT_ORDER_NOT_DENSE", Area.PLAN,
                "V3 Tool requirement order is not dense and deterministic");
        queryFindings(findings, """
                SELECT e.step_revision_id FROM plan_step_executions e
                LEFT JOIN plan_step_definitions d
                  ON d.task_id=e.task_id AND d.plan_revision_id=e.plan_revision_id
                 AND d.step_revision_id=e.step_revision_id
                WHERE d.step_revision_id IS NULL
                """, "PLAN_STEP_STATE_DEFINITION_MISMATCH", Area.PLAN,
                "Plan Step execution state has no matching immutable definition");
        queryFindings(findings, """
                SELECT l.plan_revision_id FROM plan_revision_lifecycle l
                WHERE l.state='APPROVED'
                  AND EXISTS (
                    SELECT 1 FROM task_plan_authorities authority
                    WHERE authority.task_id=l.task_id
                      AND authority.active_plan_revision_id=l.plan_revision_id)
                  AND
                  (SELECT count(*) FROM plan_step_definitions d
                   WHERE d.task_id=l.task_id AND d.plan_revision_id=l.plan_revision_id)<>
                  (SELECT count(*) FROM plan_step_executions e
                   WHERE e.task_id=l.task_id AND e.plan_revision_id=l.plan_revision_id)
                """, "PLAN_STEP_STATE_INCOMPLETE", Area.PLAN,
                "Approved Plan Revision has incomplete Step execution state");
        queryFindings(findings, """
                SELECT a.step_revision_id FROM plan_step_attempts a
                LEFT JOIN plan_run_bindings b
                  ON b.task_id=a.task_id AND b.run_id=a.run_id
                 AND b.plan_revision_id=a.plan_revision_id
                 AND b.approval_decision='APPROVED'
                WHERE b.binding_id IS NULL
                """, "PLAN_ATTEMPT_BINDING_INVALID", Area.PLAN,
                "Plan Step Attempt is not bound to its approved Task/Run/Revision");
        queryFindings(findings, """
                SELECT e.step_revision_id FROM plan_step_executions e
                WHERE e.state='RUNNING' AND NOT EXISTS(
                  SELECT 1 FROM plan_step_attempts a
                  WHERE a.task_id=e.task_id AND a.plan_revision_id=e.plan_revision_id
                    AND a.step_revision_id=e.step_revision_id
                    AND a.attempt_number=e.current_attempt_number AND a.state='RUNNING')
                """, "PLAN_RUNNING_ATTEMPT_MISSING", Area.PLAN,
                "RUNNING Plan Step has no matching RUNNING Attempt");
        queryFindings(findings, """
                SELECT e.producer_identity FROM plan_evidence e
                LEFT JOIN plan_step_attempts a
                  ON a.task_id=e.task_id AND a.plan_revision_id=e.plan_revision_id
                 AND a.step_revision_id=e.step_revision_id AND a.run_id=e.run_id
                 AND a.attempt_number=e.attempt_number
                WHERE a.task_id IS NULL
                """, "PLAN_EVIDENCE_SCOPE_INVALID", Area.PLAN,
                "Durable Evidence is not bound to its Plan Step Attempt");
        queryFindings(findings, """
                SELECT d.task_id FROM task_completion_decisions d
                LEFT JOIN task_identities t ON t.task_id=d.task_id
                LEFT JOIN durable_runs r ON r.task_id=d.task_id AND r.run_id=d.run_id
                WHERE t.task_id IS NULL OR r.run_id IS NULL
                   OR t.status<>'COMPLETED' OR r.terminal_status<>'COMPLETED'
                """, "PLAN_COMPLETION_DECISION_NOT_TERMINAL", Area.PLAN,
                "Completion decision is inconsistent with durable Task/Run terminal authority");
        queryFindings(findings, """
                SELECT t.task_id FROM task_identities t
                JOIN task_plan_applicability p ON p.task_id=t.task_id
                  AND p.applicability='PLAN_REQUIRED'
                LEFT JOIN task_completion_decisions d ON d.task_id=t.task_id
                WHERE t.status='COMPLETED' AND d.task_id IS NULL
                """, "PLAN_TASK_COMPLETED_WITHOUT_DECISION", Area.PLAN,
                "Structured Plan Task completed without the Completion Gate decision");
    }

    private void checkThreadTaskRun(List<Finding> findings) throws SQLException {
        queryFindings(findings, """
                SELECT t.task_id FROM task_identities t
                LEFT JOIN work_threads w ON w.thread_id=t.thread_id
                WHERE w.thread_id IS NULL
                """, "TASK_THREAD_MISSING", Area.TASK, "Task references a missing Thread");
        queryFindings(findings, """
                SELECT h.thread_id||':'||h.head_task_id
                FROM thread_heads h LEFT JOIN task_identities t ON t.task_id=h.head_task_id
                WHERE t.task_id IS NULL OR t.thread_id<>h.thread_id
                """, "THREAD_HEAD_CROSS_THREAD", Area.THREAD, "Thread Head does not belong to its Thread");
        queryFindings(findings, """
                SELECT parent_task_id FROM task_identities
                WHERE parent_task_id IS NOT NULL GROUP BY parent_task_id HAVING count(*)>1
                """, "PARENT_MULTIPLE_CHILDREN", Area.TASK, "One Parent has multiple R1 children");
        queryFindings(findings, """
                SELECT r.run_id FROM durable_runs r
                LEFT JOIN task_identities t ON t.task_id=r.task_id
                WHERE t.task_id IS NULL OR t.thread_id<>r.thread_id
                """, "RUN_TASK_THREAD_MISMATCH", Area.RUN, "Run references an invalid Task or Thread");
        queryFindings(findings, """
                SELECT r.run_id FROM durable_runs r
                LEFT JOIN instruction_manifests m
                  ON m.task_id=r.task_id AND m.instruction_manifest_id=r.instruction_manifest_id
                 AND m.canonical_hash=r.instruction_manifest_hash
                WHERE m.instruction_manifest_id IS NULL
                """, "RUN_MANIFEST_MISMATCH", Area.RUN,
                "Run does not reference its Task committed Manifest");
    }

    private void checkGenerationPointerConstraint(List<Finding> findings) throws SQLException {
        queryFindings(findings, """
                SELECT p.task_id FROM task_context_pointers p
                LEFT JOIN task_context_generations g ON g.generation_id=p.active_generation_id
                LEFT JOIN durable_runs r ON r.run_id=p.owner_run_id
                WHERE g.generation_id IS NULL OR r.run_id IS NULL
                   OR g.task_id<>p.task_id OR r.task_id<>p.task_id OR p.pointer_version<0
                """, "CONTEXT_POINTER_INVALID", Area.GENERATION,
                "TaskContextPointer crosses Task or has invalid version");
        queryFindings(findings, """
                SELECT g.generation_id FROM task_context_generations g
                LEFT JOIN instruction_manifests m
                  ON m.task_id=g.task_id AND m.instruction_manifest_id=g.instruction_manifest_id
                 AND m.canonical_hash=g.instruction_manifest_hash
                WHERE m.instruction_manifest_id IS NULL
                """, "GENERATION_MANIFEST_MISMATCH", Area.GENERATION,
                "Generation does not reference its Task committed Manifest");
        queryFindings(findings, """
                SELECT g.generation_id FROM task_context_generations g
                LEFT JOIN task_context_generations p ON p.generation_id=g.parent_generation_id
                LEFT JOIN durable_runs r ON r.run_id=g.created_by_run_id
                WHERE (g.parent_generation_id IS NOT NULL
                       AND (p.generation_id IS NULL OR p.task_id<>g.task_id))
                   OR r.run_id IS NULL OR r.task_id<>g.task_id
                """, "GENERATION_TASK_RELATION_INVALID", Area.GENERATION,
                "Generation Parent or creating Run crosses Task");
        queryFindings(findings, """
                SELECT a.task_id FROM task_constraint_activations a
                LEFT JOIN constraint_revisions r
                  ON r.task_id=a.task_id AND r.constraint_revision_id=a.active_revision_id
                LEFT JOIN durable_runs run ON run.run_id=a.activated_by_run_id
                WHERE r.constraint_revision_id IS NULL OR a.pointer_version<0
                   OR (a.activated_by_run_id IS NOT NULL
                       AND (run.run_id IS NULL OR run.task_id<>a.task_id))
                """, "CONSTRAINT_ACTIVATION_INVALID", Area.CONSTRAINT,
                "Constraint activation is cross-Task, missing, or has invalid version");
    }

    private void checkManifest(List<Finding> findings) throws SQLException {
        queryFindings(findings, """
                SELECT m.instruction_manifest_id FROM instruction_manifests m
                LEFT JOIN task_identities t ON t.task_id=m.task_id WHERE t.task_id IS NULL
                """, "MANIFEST_TASK_MISSING", Area.MANIFEST, "Committed Manifest has no Task");
        queryFindings(findings, """
                SELECT e.entry_id FROM instruction_entries e
                LEFT JOIN instruction_manifests m
                  ON m.instruction_manifest_id=e.instruction_manifest_id
                WHERE m.instruction_manifest_id IS NULL
                """, "MANIFEST_ENTRY_ORPHAN", Area.MANIFEST, "Manifest Entry has no Manifest");
        queryFindings(findings, """
                SELECT e.entry_id FROM instruction_entries e
                LEFT JOIN protected_instruction_artifacts a
                  ON a.artifact_id=e.artifact_id AND a.content_hash=e.content_hash
                WHERE e.status='APPLIED' AND a.artifact_id IS NULL
                """, "MANIFEST_ARTIFACT_MISSING", Area.ARTIFACT,
                "APPLIED Entry has no matching protected Artifact");
        queryFindings(findings, """
                SELECT entry_id FROM instruction_entries
                WHERE status IN ('FAILED','DISCOVERED','PARSED')
                   OR (status='APPLIED' AND (artifact_id IS NULL OR content_hash IS NULL))
                   OR (status='IGNORED' AND length(trim(COALESCE(exclusion_reason,'')))=0)
                """, "MANIFEST_ENTRY_INVALID", Area.MANIFEST,
                "Committed Entry violates terminal closure");
        checkArtifactHashes(findings);
        checkManifestHashesAndOrder(findings);
    }

    private void checkArtifactHashes(List<Finding> findings) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT artifact_id,content_hash,protected_payload
                     FROM protected_instruction_artifacts
                     WHERE protected_payload IS NOT NULL
                     """)) {
            while (rows.next()) {
                String actual = ProtectedInstructionArtifact.sha256(rows.getBytes(3));
                if (!actual.equals(rows.getString(2))) {
                    add(findings, "ARTIFACT_HASH_MISMATCH", Severity.ERROR, Area.ARTIFACT,
                            rows.getString(1), "Protected Artifact hash mismatch",
                            "stored hash differs from recomputed SHA-256");
                }
            }
        }
    }

    private void checkManifestHashesAndOrder(List<Finding> findings) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet manifests = statement.executeQuery("""
                     SELECT instruction_manifest_id,schema_version,resolution_policy_version,
                            canonical_hash FROM instruction_manifests ORDER BY instruction_manifest_id
                     """)) {
            while (manifests.next()) {
                String manifestId = manifests.getString(1);
                List<InstructionManifestCandidate.Entry> entries = new ArrayList<>();
                boolean invalidStatus = false;
                try (PreparedStatement entryQuery = connection.prepareStatement("""
                        SELECT entry_id,source_type,source_path,scope,status,applied_order,
                               content_hash,artifact_id,parse_result,exclusion_reason
                        FROM instruction_entries WHERE instruction_manifest_id=?
                        ORDER BY entry_id
                        """)) {
                    entryQuery.setString(1, manifestId);
                    try (ResultSet rows = entryQuery.executeQuery()) {
                        while (rows.next()) {
                            InstructionManifestCandidate.Status status;
                            try {
                                status = InstructionManifestCandidate.Status.valueOf(rows.getString(5));
                            } catch (RuntimeException exception) {
                                invalidStatus = true;
                                continue;
                            }
                            int rawOrder = rows.getInt(6);
                            Integer order = rows.wasNull() ? null : rawOrder;
                            entries.add(new InstructionManifestCandidate.Entry(rows.getString(1),
                                    rows.getString(2), rows.getString(3), rows.getString(4), status,
                                    order, rows.getString(7), rows.getString(8), rows.getString(9),
                                    rows.getString(10), null));
                        }
                    }
                }
                if (invalidStatus) continue;
                try {
                    InstructionManifestCandidate candidate = new InstructionManifestCandidate(
                            manifests.getInt(2), manifests.getString(3), entries);
                    String actual = candidate.canonicalHash();
                    if (!actual.equals(manifests.getString(4))) {
                        add(findings, "MANIFEST_CANONICAL_HASH_MISMATCH", Severity.ERROR,
                                Area.MANIFEST, manifestId, "Manifest canonical hash mismatch",
                                "stored hash differs from canonical Entry serialization");
                    }
                } catch (IllegalStateException exception) {
                    add(findings, "MANIFEST_APPLIED_ORDER_INVALID", Severity.ERROR, Area.MANIFEST,
                            manifestId, "Applied order is duplicate, sparse, or Entry closure is invalid",
                            "candidate validation failed");
                }
            }
        }
    }

    private void checkOccurrence(List<Finding> findings) throws SQLException {
        queryFindings(findings, """
                SELECT task_id||':'||logical_operation_key FROM side_effect_occurrences
                GROUP BY task_id,logical_operation_key HAVING count(*)>1
                """, "OCCURRENCE_IDEMPOTENCY_DUPLICATE", Area.OCCURRENCE,
                "Occurrence idempotency identity is duplicated");
        queryFindings(findings, """
                SELECT o.occurrence_id FROM side_effect_occurrences o
                LEFT JOIN durable_runs r ON r.run_id=o.run_id
                WHERE r.run_id IS NULL OR r.task_id<>o.task_id OR r.thread_id<>o.thread_id
                """, "OCCURRENCE_RUN_MISMATCH", Area.OCCURRENCE,
                "Occurrence Task/Run/Thread relation is invalid");
        queryFindings(findings, """
                SELECT occurrence_id FROM side_effect_occurrences
                WHERE (status='STARTED' AND completed_at IS NOT NULL)
                   OR (status IN ('COMPLETED','FAILED','UNKNOWN') AND completed_at IS NULL)
                """, "OCCURRENCE_STATE_EVIDENCE_INVALID", Area.OCCURRENCE,
                "Occurrence timestamps indicate an invalid state transition");
        queryFindings(findings, """
                SELECT occurrence_id FROM side_effect_occurrences WHERE status IN ('STARTED','UNKNOWN')
                """, "OCCURRENCE_RECOVERY_REVIEW_REQUIRED", Severity.INFO, Area.OCCURRENCE,
                "Occurrence requires recovery review");
    }

    private void checkOwnership(List<Finding> findings) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT l.task_id,l.owner_instance_id,l.owner_run_id,l.lease_token,
                            l.fencing_token,l.cas_version,l.acquired_at,l.heartbeat_at,l.expires_at,
                            r.task_id
                     FROM task_ownership_leases l
                     LEFT JOIN durable_runs r ON r.run_id=l.owner_run_id
                     """)) {
            while (rows.next()) {
                String taskId = rows.getString(1);
                if (blank(rows.getString(2)) || blank(rows.getString(4))
                        || rows.getLong(5) < 1 || rows.getLong(6) < 1) {
                    add(findings, "OWNERSHIP_KEY_VALUE_INVALID", Severity.ERROR, Area.OWNERSHIP,
                            taskId, "Ownership lease lacks required token/version values",
                            "owner/token/fencing/cas validation failed");
                }
                if (rows.getString(10) == null || !taskId.equals(rows.getString(10))) {
                    add(findings, "OWNERSHIP_RUN_TASK_MISMATCH", Severity.ERROR, Area.OWNERSHIP,
                            taskId, "Ownership Run does not belong to Task", rows.getString(3));
                }
                try {
                    Instant acquired = Instant.parse(rows.getString(7));
                    Instant heartbeat = Instant.parse(rows.getString(8));
                    Instant expires = Instant.parse(rows.getString(9));
                    if (heartbeat.isBefore(acquired) || !expires.isAfter(heartbeat)) {
                        add(findings, "OWNERSHIP_TIME_INVALID", Severity.ERROR, Area.OWNERSHIP,
                                taskId, "Ownership lease times are not monotonic",
                                "expected acquired <= heartbeat < expires");
                    }
                } catch (DateTimeParseException | NullPointerException exception) {
                    add(findings, "OWNERSHIP_TIME_INVALID", Severity.ERROR, Area.OWNERSHIP,
                            taskId, "Ownership lease time is invalid", "timestamp parse failed");
                }
            }
        }
    }

    private void checkLegacy(List<Finding> findings) throws SQLException {
        queryFindings(findings, """
                SELECT legacy_value FROM legacy_aliases
                WHERE legacy_entity_family IS NULL OR length(trim(legacy_entity_family))=0
                """, "LEGACY_ALIAS_TYPE_MISSING", Area.LEGACY, "Legacy alias has no explicit type");
        queryFindings(findings, """
                SELECT legacy_entity_family||':'||legacy_value FROM legacy_aliases
                WHERE canonical_target_id IS NOT NULL
                GROUP BY legacy_entity_family,legacy_value
                HAVING count(DISTINCT canonical_target_id)>1
                """, "LEGACY_ALIAS_AMBIGUOUS", Area.LEGACY,
                "Legacy identity maps to multiple canonical targets");
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT legacy_entity_family,legacy_value,canonical_target_id
                     FROM legacy_aliases WHERE canonical_target_id IS NOT NULL
                     """)) {
            while (rows.next()) {
                try {
                    EntityFamily declared = EntityFamily.valueOf(rows.getString(1));
                    CanonicalId target = CanonicalId.parse(rows.getString(3));
                    if (target.family() != declared) throw new IllegalArgumentException();
                } catch (RuntimeException exception) {
                    add(findings, "LEGACY_ALIAS_TARGET_TYPE_MISMATCH", Severity.ERROR,
                            Area.LEGACY, safe(rows.getString(2)),
                            "Legacy alias canonical target does not match explicit type",
                            safe(rows.getString(3)));
                }
            }
        }
        queryFindings(findings, """
                SELECT a.canonical_target_id FROM legacy_aliases a
                JOIN task_identities t ON t.task_id=a.canonical_target_id
                WHERE a.legacy_entity_family='TASK' AND a.classification='LEGACY_UNCERTAIN'
                  AND (t.parent_task_id IS NOT NULL OR EXISTS(
                      SELECT 1 FROM task_context_generations g WHERE g.task_id=t.task_id))
                """, "LEGACY_RELATION_FABRICATED", Area.LEGACY,
                "Legacy uncertain Task has fabricated Parent/Child or Generation state");
        queryFindings(findings, """
                SELECT legacy_value FROM legacy_aliases a
                WHERE legacy_entity_family='TASK' AND continuation_status='ELIGIBLE'
                  AND NOT EXISTS(SELECT 1 FROM checkpoints c WHERE c.task_id=a.legacy_value)
                """, "LEGACY_CONTINUATION_WITHOUT_CHECKPOINT", Area.LEGACY,
                "Legacy Task is marked eligible without a checkpoint");
    }

    private void auditIds(List<Finding> findings, String table, String column,
                          EntityFamily family) throws SQLException {
        if (!objectExists("table", table) || !columns(table).contains(column)) return;
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT " + column + " FROM " + table
                     + " WHERE " + column + " IS NOT NULL")) {
            while (rows.next()) {
                String value = rows.getString(1);
                try {
                    CanonicalId parsed = CanonicalId.parse(value);
                    if (parsed.family() != family) throw new IllegalArgumentException();
                } catch (IllegalArgumentException exception) {
                    add(findings, "TYPED_ID_INVALID", Severity.ERROR, Area.TYPED_ID,
                            safe(value), "Authoritative ID has invalid format or family",
                            table + "." + column + " expected " + family);
                }
            }
        }
    }

    private void checkObject(List<Finding> findings, String type, String name) throws SQLException {
        if (!objectExists(type, name)) {
            add(findings, "SCHEMA_OBJECT_MISSING", Severity.ERROR, Area.SCHEMA, name,
                    "Required V19 " + type + " is missing", type + ":" + name);
        }
    }

    private void checkUnique(List<Finding> findings, String table,
                             List<String> columns) throws SQLException {
        if (!objectExists("table", table)) return;
        boolean found = false;
        try (Statement statement = connection.createStatement();
             ResultSet indexes = statement.executeQuery("PRAGMA index_list(" + table + ")")) {
            while (indexes.next()) {
                if (indexes.getInt("unique") != 1) continue;
                String name = indexes.getString("name");
                List<String> actual = new ArrayList<>();
                try (Statement detail = connection.createStatement();
                     ResultSet parts = detail.executeQuery("PRAGMA index_info(" + name + ")")) {
                    while (parts.next()) actual.add(parts.getString("name"));
                }
                if (actual.equals(columns)) {
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            add(findings, "SCHEMA_PARENT_UNIQUE_MISSING", Severity.ERROR, Area.SCHEMA,
                    table, "Composite FK parent key lacks matching UNIQUE order",
                    String.join(",", columns));
        }
    }

    private void checkForeignKey(List<Finding> findings, String table, String parent,
                                 List<String> fromColumns, List<String> toColumns)
            throws SQLException {
        if (!objectExists("table", table)) return;
        Map<Integer, ForeignKeyShape> shapes = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA foreign_key_list(" + table + ")")) {
            while (rows.next()) {
                int id = rows.getInt("id");
                ForeignKeyShape shape = shapes.computeIfAbsent(id,
                        ignored -> new ForeignKeyShape(rowsValue(rows, "table"),
                                new ArrayList<>(), new ArrayList<>()));
                shape.fromColumns().add(rows.getString("from"));
                shape.toColumns().add(rows.getString("to"));
            }
        }
        boolean found = shapes.values().stream().anyMatch(shape -> shape.parent().equals(parent)
                && shape.fromColumns().equals(fromColumns) && shape.toColumns().equals(toColumns));
        if (!found) {
            add(findings, "SCHEMA_COMPOSITE_FK_MISSING", Severity.ERROR, Area.SCHEMA,
                    table, "Required composite foreign key is missing or reordered",
                    String.join(",", fromColumns) + " -> " + parent + "("
                            + String.join(",", toColumns) + ")");
        }
    }

    private static String rowsValue(ResultSet rows, String column) {
        try {
            return rows.getString(column);
        } catch (SQLException exception) {
            throw new IllegalStateException("Unable to read PRAGMA foreign_key_list", exception);
        }
    }

    private void queryFindings(List<Finding> findings, String sql, String code, Area area,
                               String message) throws SQLException {
        queryFindings(findings, sql, code, Severity.ERROR, area, message);
    }

    private void queryFindings(List<Finding> findings, String sql, String code, Severity severity,
                               Area area, String message) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                add(findings, code, severity, area, safe(rows.getString(1)), message,
                        "query invariant matched");
            }
        }
    }

    private boolean requiredTablesPresent() throws SQLException {
        for (String table : REQUIRED_TABLES) {
            if (!objectExists("table", table)) return false;
        }
        return true;
    }

    private boolean objectExists(String type, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type=? AND name=?")) {
            statement.setString(1, type);
            statement.setString(2, name);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    private String objectSql(String type, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT sql FROM sqlite_master WHERE type=? AND name=?")) {
            statement.setString(1, type);
            statement.setString(2, name);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private Set<String> columns(String table) throws SQLException {
        java.util.LinkedHashSet<String> result = new java.util.LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rows.next()) result.add(rows.getString("name"));
        }
        return Set.copyOf(result);
    }

    private int scalar(String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw new SQLException("Integrity scalar query returned no row");
            return rows.getInt(1);
        }
    }

    private static void add(List<Finding> findings, String code, Severity severity, Area area,
                            String location, String message, String evidence) {
        findings.add(new Finding(code, severity, area, safe(location), message, safe(evidence)));
    }

    private static String safe(String value) {
        if (value == null) return "unknown";
        String normalized = value.replaceAll("[\\r\\n\\t]", " ");
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 157) + "...";
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public enum Severity { INFO, WARNING, ERROR }

    public enum Area {
        SCHEMA, TYPED_ID, THREAD, TASK, RUN, GENERATION, CONSTRAINT,
        MANIFEST, ARTIFACT, OCCURRENCE, OWNERSHIP, LEGACY, PLAN
    }

    public record Finding(String code, Severity severity, Area entityFamily,
                          String entityLocation, String message, String evidenceSummary) {
        public Finding {
            Objects.requireNonNull(code);
            Objects.requireNonNull(severity);
            Objects.requireNonNull(entityFamily);
            Objects.requireNonNull(entityLocation);
            Objects.requireNonNull(message);
            Objects.requireNonNull(evidenceSummary);
        }
    }

    public record IntegrityReport(List<Finding> findings) {
        public IntegrityReport { findings = List.copyOf(findings); }
        public boolean clean() { return findings.isEmpty(); }
    }

    private record ForeignKeyShape(String parent, List<String> fromColumns,
                                   List<String> toColumns) { }
}
