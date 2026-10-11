package com.paicli.state;

import java.util.List;

/** Additive R1E Capsule, Follow-up and atomic Child graph schema. */
final class V24Migration {
    private V24Migration() { }

    static List<String> statements() {
        return List.of(
                "EXECUTE_IF_TABLE_EXISTS plan_revisions CREATE UNIQUE INDEX idx_r1e_plan_revision_hash_scope "
                        + "ON plan_revisions(task_id,plan_revision_id,canonical_hash)",
                """
                CREATE TABLE continuation_capsules (
                    capsule_id TEXT PRIMARY KEY CHECK(length(capsule_id)=36 AND substr(capsule_id,1,4)='cap_'
                        AND substr(capsule_id,5) NOT GLOB '*[^0-9a-f]*' AND substr(capsule_id,17,1)='4'
                        AND substr(capsule_id,21,1) IN ('8','9','a','b')),
                    schema_version INTEGER NOT NULL CHECK(schema_version=1),
                    thread_id TEXT NOT NULL, parent_task_id TEXT NOT NULL,
                    parent_run_id TEXT NOT NULL, parent_generation_id TEXT NOT NULL,
                    completion_anchor_type TEXT NOT NULL CHECK(completion_anchor_type IN ('COMPLETION_DECISION','TERMINAL_AUTHORITY')),
                    completion_anchor_id TEXT NOT NULL CHECK(length(trim(completion_anchor_id))>0),
                    parent_manifest_id TEXT NOT NULL, parent_manifest_hash TEXT NOT NULL,
                    parent_plan_revision_id TEXT, parent_plan_hash TEXT,
                    canonical_hash TEXT NOT NULL CHECK(length(canonical_hash)=64 AND canonical_hash NOT GLOB '*[^0-9a-f]*'),
                    created_at TEXT NOT NULL, provenance TEXT NOT NULL,
                    UNIQUE(thread_id,parent_task_id,capsule_id),
                    FOREIGN KEY(thread_id,parent_task_id) REFERENCES task_identities(thread_id,task_id),
                    FOREIGN KEY(parent_task_id,parent_run_id) REFERENCES durable_runs(task_id,run_id),
                    FOREIGN KEY(parent_task_id,parent_generation_id) REFERENCES task_context_generations(task_id,generation_id),
                    FOREIGN KEY(parent_task_id,parent_manifest_id,parent_manifest_hash)
                        REFERENCES instruction_manifests(task_id,instruction_manifest_id,canonical_hash),
                    FOREIGN KEY(parent_task_id,parent_plan_revision_id,parent_plan_hash)
                        REFERENCES plan_revisions(task_id,plan_revision_id,canonical_hash),
                    CHECK((parent_plan_revision_id IS NULL AND parent_plan_hash IS NULL)
                       OR (parent_plan_revision_id IS NOT NULL AND length(parent_plan_hash)=64))
                )
                """, """
                CREATE TABLE continuation_capsule_facts (
                    capsule_id TEXT NOT NULL, fact_number INTEGER NOT NULL CHECK(fact_number>=1),
                    fact_type TEXT NOT NULL CHECK(fact_type IN
                        ('PARENT_GOAL','FINAL_RESULT','COMPLETION','PLAN','STEP','EVIDENCE_REFERENCE',
                         'ARTIFACT_REFERENCE','CONSTRAINT','CONFIRMED_STEERING','MANIFEST','RISK')),
                    scope_identity TEXT NOT NULL, bounded_payload TEXT NOT NULL CHECK(length(bounded_payload)<=2048),
                    reference_identity TEXT, reference_hash TEXT, provenance TEXT NOT NULL,
                    PRIMARY KEY(capsule_id,fact_number), FOREIGN KEY(capsule_id) REFERENCES continuation_capsules(capsule_id),
                    CHECK(reference_hash IS NULL OR (length(reference_hash)=64 AND reference_hash NOT GLOB '*[^0-9a-f]*'))
                )
                """, """
                CREATE TABLE followup_candidate_definitions (
                    capsule_id TEXT PRIMARY KEY CHECK(length(capsule_id)=36 AND substr(capsule_id,1,4)='cap_'
                        AND substr(capsule_id,5) NOT GLOB '*[^0-9a-f]*' AND substr(capsule_id,17,1)='4'
                        AND substr(capsule_id,21,1) IN ('8','9','a','b')),
                    thread_id TEXT NOT NULL, parent_task_id TEXT NOT NULL,
                    expected_head_version INTEGER NOT NULL CHECK(expected_head_version>=0),
                    instruction TEXT NOT NULL CHECK(length(instruction) BETWEEN 1 AND 4096),
                    instruction_hash TEXT NOT NULL CHECK(length(instruction_hash)=64 AND instruction_hash NOT GLOB '*[^0-9a-f]*'),
                    provenance TEXT NOT NULL CHECK(provenance IN ('EXPLICIT_CONTINUE','NATURAL_TERMINAL_INPUT')),
                    input_owner TEXT NOT NULL CHECK(length(trim(input_owner)) BETWEEN 1 AND 128),
                    input_session_id TEXT NOT NULL CHECK(length(trim(input_session_id)) BETWEEN 1 AND 128),
                    created_at TEXT NOT NULL, UNIQUE(thread_id,parent_task_id,capsule_id),
                    FOREIGN KEY(thread_id,parent_task_id) REFERENCES task_identities(thread_id,task_id)
                )
                """, """
                CREATE TABLE followup_candidate_authority (
                    capsule_id TEXT PRIMARY KEY,
                    state TEXT NOT NULL CHECK(state IN ('CANDIDATE','NEEDS_INTENT_CONFIRMATION','PREPARING',
                        'NEEDS_RISK_CONFIRMATION','READY_TO_ADMIT','ADMITTED','CANCELLED','FAILED','HEAD_CONFLICT')),
                    candidate_version INTEGER NOT NULL DEFAULT 0 CHECK(candidate_version>=0),
                    manifest_candidate_hash TEXT, risk TEXT CHECK(risk IN ('LOW','HIGH','UNKNOWN','BLOCKING')),
                    admitted_child_task_id TEXT, retention_disposition TEXT NOT NULL DEFAULT 'ACTIVE'
                        CHECK(retention_disposition IN ('ACTIVE','AUTHORITY_RETAINED','PAYLOAD_TRIMMED')),
                    reason_code TEXT CHECK(reason_code IS NULL OR length(reason_code)<=64), updated_at TEXT NOT NULL,
                    FOREIGN KEY(capsule_id) REFERENCES followup_candidate_definitions(capsule_id),
                    FOREIGN KEY(admitted_child_task_id) REFERENCES task_identities(task_id),
                    CHECK((state='ADMITTED')=(admitted_child_task_id IS NOT NULL))
                )
                """, """
                CREATE TABLE followup_intent_classification_facts (
                    capsule_id TEXT PRIMARY KEY,
                    classification TEXT NOT NULL CHECK(classification IN ('CONTINUE_CURRENT_THREAD','CREATE_NEW_THREAD','UNKNOWN','REJECT')),
                    classifier_version TEXT NOT NULL, reason_code TEXT NOT NULL, classified_at TEXT NOT NULL,
                    FOREIGN KEY(capsule_id) REFERENCES followup_candidate_definitions(capsule_id)
                )
                """, """
                CREATE TABLE followup_confirmation_facts (
                    capsule_id TEXT NOT NULL, confirmation_kind TEXT NOT NULL CHECK(confirmation_kind IN ('INTENT','RISK')),
                    confirmation_number INTEGER NOT NULL CHECK(confirmation_number>=1), expected_candidate_version INTEGER NOT NULL,
                    input_owner TEXT NOT NULL, input_session_id TEXT NOT NULL,
                    decision TEXT NOT NULL CHECK(decision IN ('CONTINUE_CURRENT_THREAD','CREATE_NEW_THREAD','ACCEPT_RISK','REJECT','CANCEL')),
                    prepared_manifest_hash TEXT, prepared_risk TEXT CHECK(prepared_risk IN ('LOW','HIGH','UNKNOWN','BLOCKING')),
                    confirmed_at TEXT NOT NULL, PRIMARY KEY(capsule_id,confirmation_kind,confirmation_number),
                    UNIQUE(capsule_id,confirmation_kind),
                    FOREIGN KEY(capsule_id) REFERENCES followup_candidate_definitions(capsule_id),
                    CHECK((confirmation_kind='INTENT' AND prepared_manifest_hash IS NULL AND prepared_risk IS NULL)
                       OR (confirmation_kind='RISK' AND length(prepared_manifest_hash)=64
                           AND prepared_manifest_hash NOT GLOB '*[^0-9a-f]*' AND prepared_risk IN ('HIGH','UNKNOWN')))
                )
                """, """
                CREATE TABLE child_manifest_candidates (
                    capsule_id TEXT PRIMARY KEY, instruction_manifest_id TEXT NOT NULL,
                    schema_version INTEGER NOT NULL, resolution_policy_version TEXT NOT NULL,
                    canonical_hash TEXT NOT NULL CHECK(length(canonical_hash)=64 AND canonical_hash NOT GLOB '*[^0-9a-f]*'),
                    source_fingerprint TEXT NOT NULL CHECK(length(source_fingerprint)=64 AND source_fingerprint NOT GLOB '*[^0-9a-f]*'),
                    workspace_identity TEXT NOT NULL, prepared_at TEXT NOT NULL, provenance TEXT NOT NULL,
                    FOREIGN KEY(capsule_id) REFERENCES followup_candidate_definitions(capsule_id),
                    UNIQUE(capsule_id,instruction_manifest_id,canonical_hash)
                )
                """, """
                CREATE TABLE child_manifest_candidate_entries (
                    capsule_id TEXT NOT NULL, entry_order INTEGER NOT NULL CHECK(entry_order>=0), entry_id TEXT NOT NULL,
                    source_type TEXT NOT NULL, source_path TEXT NOT NULL, scope TEXT NOT NULL,
                    status TEXT NOT NULL CHECK(status IN ('APPLIED','IGNORED')), applied_order INTEGER,
                    content_hash TEXT, artifact_id TEXT, parse_result TEXT, exclusion_reason TEXT,
                    PRIMARY KEY(capsule_id,entry_order), UNIQUE(capsule_id,entry_id),
                    FOREIGN KEY(capsule_id) REFERENCES child_manifest_candidates(capsule_id),
                    FOREIGN KEY(artifact_id,content_hash) REFERENCES protected_instruction_artifacts(artifact_id,content_hash)
                )
                """, """
                CREATE TABLE manifest_diff_facts (
                    capsule_id TEXT NOT NULL, diff_number INTEGER NOT NULL CHECK(diff_number>=1),
                    category TEXT NOT NULL CHECK(category IN ('UNCHANGED','ADDED','REMOVED','CHANGED','REORDERED','STATUS_CHANGED')),
                    source_path TEXT NOT NULL, parent_hash TEXT, child_hash TEXT,
                    bounded_summary TEXT NOT NULL CHECK(length(bounded_summary)<=512), provenance TEXT NOT NULL,
                    PRIMARY KEY(capsule_id,diff_number), UNIQUE(capsule_id,source_path),
                    FOREIGN KEY(capsule_id) REFERENCES child_manifest_candidates(capsule_id)
                )
                """, """
                CREATE TABLE followup_risk_classification_facts (
                    capsule_id TEXT PRIMARY KEY, risk TEXT NOT NULL CHECK(risk IN ('LOW','HIGH','UNKNOWN','BLOCKING')),
                    classifier_version TEXT NOT NULL, reason_code TEXT NOT NULL, classified_at TEXT NOT NULL,
                    FOREIGN KEY(capsule_id) REFERENCES child_manifest_candidates(capsule_id)
                )
                """, """
                CREATE TABLE task_continuation_edges (
                    thread_id TEXT NOT NULL, parent_task_id TEXT NOT NULL UNIQUE, child_task_id TEXT NOT NULL UNIQUE,
                    capsule_id TEXT NOT NULL UNIQUE, edge_type TEXT NOT NULL CHECK(edge_type='CONTINUED'), created_at TEXT NOT NULL,
                    PRIMARY KEY(thread_id,parent_task_id,child_task_id),
                    FOREIGN KEY(thread_id,parent_task_id) REFERENCES task_identities(thread_id,task_id),
                    FOREIGN KEY(thread_id,child_task_id) REFERENCES task_identities(thread_id,task_id),
                    FOREIGN KEY(capsule_id) REFERENCES continuation_capsules(capsule_id)
                )
                """, """
                CREATE TABLE capsule_child_bindings (
                    capsule_id TEXT PRIMARY KEY, thread_id TEXT NOT NULL, parent_task_id TEXT NOT NULL,
                    child_task_id TEXT NOT NULL UNIQUE, child_run_id TEXT NOT NULL UNIQUE, child_generation_id TEXT NOT NULL UNIQUE,
                    child_manifest_id TEXT NOT NULL, child_manifest_hash TEXT NOT NULL,
                    binding_hash TEXT NOT NULL CHECK(length(binding_hash)=64 AND binding_hash NOT GLOB '*[^0-9a-f]*'),
                    bound_at TEXT NOT NULL, provenance TEXT NOT NULL,
                    FOREIGN KEY(capsule_id) REFERENCES continuation_capsules(capsule_id),
                    FOREIGN KEY(thread_id,parent_task_id,child_task_id)
                        REFERENCES task_continuation_edges(thread_id,parent_task_id,child_task_id),
                    FOREIGN KEY(child_task_id,child_run_id) REFERENCES durable_runs(task_id,run_id),
                    FOREIGN KEY(child_task_id,child_generation_id) REFERENCES task_context_generations(task_id,generation_id),
                    FOREIGN KEY(child_task_id,child_manifest_id,child_manifest_hash)
                        REFERENCES instruction_manifests(task_id,instruction_manifest_id,canonical_hash)
                )
                """, """
                CREATE TABLE followup_admission_facts (
                    capsule_id TEXT PRIMARY KEY, result TEXT NOT NULL CHECK(result IN ('ADMITTED','CANCELLED','FAILED','HEAD_CONFLICT')),
                    child_task_id TEXT, expected_head_version INTEGER NOT NULL, observed_head_task_id TEXT,
                    observed_head_version INTEGER,
                    source_state TEXT NOT NULL CHECK(source_state IN ('CANDIDATE','NEEDS_INTENT_CONFIRMATION','PREPARING',
                        'NEEDS_RISK_CONFIRMATION','READY_TO_ADMIT')),
                    source_candidate_version INTEGER NOT NULL CHECK(source_candidate_version>=0),
                    resulting_candidate_version INTEGER NOT NULL CHECK(resulting_candidate_version=source_candidate_version+1),
                    reason_code TEXT NOT NULL CHECK(length(trim(reason_code)) BETWEEN 1 AND 64),
                    admitted_at TEXT NOT NULL, provenance TEXT NOT NULL,
                    FOREIGN KEY(capsule_id) REFERENCES followup_candidate_definitions(capsule_id),
                    FOREIGN KEY(child_task_id) REFERENCES task_identities(task_id),
                    CHECK((result='ADMITTED' AND child_task_id IS NOT NULL AND observed_head_task_id IS NULL)
                       OR (result='HEAD_CONFLICT' AND child_task_id IS NULL AND observed_head_task_id IS NOT NULL
                           AND observed_head_version IS NOT NULL)
                       OR (result IN ('FAILED','CANCELLED') AND child_task_id IS NULL AND observed_head_task_id IS NULL))
                )
                """,
                """
                CREATE TABLE child_initial_run_launches (
                    capsule_id TEXT PRIMARY KEY, child_task_id TEXT NOT NULL UNIQUE,
                    child_run_id TEXT NOT NULL UNIQUE,
                    state TEXT NOT NULL CHECK(state IN ('START_PENDING','START_CLAIMED','STARTED','START_TERMINAL')),
                    launch_version INTEGER NOT NULL DEFAULT 0 CHECK(launch_version>=0),
                    claim_attempt INTEGER NOT NULL DEFAULT 0 CHECK(claim_attempt>=0),
                    claim_owner_instance_id TEXT, claim_lease_token TEXT,
                    claim_fencing_token INTEGER, claim_lease_version INTEGER,
                    claimed_at TEXT, started_at TEXT, terminal_at TEXT,
                    failure_reason TEXT CHECK(failure_reason IS NULL OR length(failure_reason)<=128),
                    updated_at TEXT NOT NULL,
                    FOREIGN KEY(capsule_id) REFERENCES capsule_child_bindings(capsule_id),
                    FOREIGN KEY(child_task_id,child_run_id) REFERENCES durable_runs(task_id,run_id),
                    CHECK((state='START_PENDING' AND claim_owner_instance_id IS NULL
                           AND claim_lease_token IS NULL AND claim_fencing_token IS NULL
                           AND claim_lease_version IS NULL AND claimed_at IS NULL)
                       OR (state<>'START_PENDING' AND claim_owner_instance_id IS NOT NULL
                           AND claim_lease_token IS NOT NULL AND claim_fencing_token>=1
                           AND claim_lease_version>=1 AND claimed_at IS NOT NULL)),
                    CHECK((state='STARTED' OR state='START_TERMINAL')=(started_at IS NOT NULL)),
                    CHECK((state='START_TERMINAL')=(terminal_at IS NOT NULL))
                )
                """,
                "CREATE INDEX idx_r1e_candidate_parent ON followup_candidate_definitions(thread_id,parent_task_id)",
                "CREATE INDEX idx_r1e_candidate_state ON followup_candidate_authority(state,updated_at)",
                "CREATE INDEX idx_r1e_capsule_parent ON continuation_capsules(thread_id,parent_task_id)",
                "CREATE INDEX idx_r1e_child_launch_state ON child_initial_run_launches(state,updated_at)",
                immutable("r1e_capsule", "continuation_capsules", "Continuation Capsules"),
                noDelete("r1e_capsule", "continuation_capsules", "Continuation Capsules"),
                immutable("r1e_capsule_fact", "continuation_capsule_facts", "Capsule facts"),
                noDelete("r1e_capsule_fact", "continuation_capsule_facts", "Capsule facts"),
                immutable("r1e_followup_definition", "followup_candidate_definitions", "Follow-up definitions"),
                noDelete("r1e_followup_definition", "followup_candidate_definitions", "Follow-up definitions"),
                immutable("r1e_followup_intent", "followup_intent_classification_facts", "Follow-up intent facts"),
                noDelete("r1e_followup_intent", "followup_intent_classification_facts", "Follow-up intent facts"),
                immutable("r1e_followup_confirmation", "followup_confirmation_facts", "Follow-up confirmations"),
                noDelete("r1e_followup_confirmation", "followup_confirmation_facts", "Follow-up confirmations"),
                immutable("r1e_child_manifest_candidate", "child_manifest_candidates", "Child Manifest candidates"),
                noDelete("r1e_child_manifest_candidate", "child_manifest_candidates", "Child Manifest candidates"),
                immutable("r1e_child_manifest_entry", "child_manifest_candidate_entries", "Child Manifest candidate entries"),
                noDelete("r1e_child_manifest_entry", "child_manifest_candidate_entries", "Child Manifest candidate entries"),
                immutable("r1e_manifest_diff", "manifest_diff_facts", "Manifest diff facts"),
                noDelete("r1e_manifest_diff", "manifest_diff_facts", "Manifest diff facts"),
                immutable("r1e_risk", "followup_risk_classification_facts", "Manifest risk facts"),
                noDelete("r1e_risk", "followup_risk_classification_facts", "Manifest risk facts"),
                immutable("r1e_task_edge", "task_continuation_edges", "Task continuation edges"),
                noDelete("r1e_task_edge", "task_continuation_edges", "Task continuation edges"),
                immutable("r1e_binding", "capsule_child_bindings", "Capsule Child bindings"),
                noDelete("r1e_binding", "capsule_child_bindings", "Capsule Child bindings"),
                immutable("r1e_admission_fact", "followup_admission_facts", "Follow-up admission facts"),
                noDelete("r1e_admission_fact", "followup_admission_facts", "Follow-up admission facts"),
                noDelete("r1e_child_launch", "child_initial_run_launches", "Child Initial Run launch authority"),
                """
                CREATE TRIGGER r1e_child_launch_transition BEFORE UPDATE OF state ON child_initial_run_launches
                WHEN NOT ((OLD.state='START_PENDING' AND NEW.state='START_CLAIMED')
                    OR (OLD.state='START_CLAIMED' AND NEW.state IN ('START_CLAIMED','STARTED','START_TERMINAL'))
                    OR (OLD.state='STARTED' AND NEW.state IN ('START_CLAIMED','STARTED','START_TERMINAL')))
                BEGIN SELECT RAISE(ABORT,'Invalid Child Initial Run launch transition'); END
                """, """
                CREATE TRIGGER r1e_child_launch_terminal_immutable BEFORE UPDATE ON child_initial_run_launches
                WHEN OLD.state='START_TERMINAL'
                BEGIN SELECT RAISE(ABORT,'Child Initial Run launch terminal state is immutable'); END
                """,
                """
                CREATE TRIGGER r1e_candidate_terminal_immutable BEFORE UPDATE ON followup_candidate_authority
                WHEN OLD.state IN ('ADMITTED','CANCELLED','FAILED','HEAD_CONFLICT') AND
                    (NEW.state<>OLD.state OR NEW.candidate_version<>OLD.candidate_version
                     OR NEW.manifest_candidate_hash IS NOT OLD.manifest_candidate_hash
                     OR NEW.risk IS NOT OLD.risk OR NEW.admitted_child_task_id IS NOT OLD.admitted_child_task_id)
                BEGIN SELECT RAISE(ABORT,'Follow-up terminal state is immutable'); END
                """, """
                CREATE TRIGGER r1e_candidate_initial_shape BEFORE INSERT ON followup_candidate_authority
                WHEN NEW.candidate_version<>0 OR NEW.manifest_candidate_hash IS NOT NULL OR NEW.risk IS NOT NULL
                  OR NEW.admitted_child_task_id IS NOT NULL
                  OR NOT EXISTS(SELECT 1 FROM followup_intent_classification_facts c
                      WHERE c.capsule_id=NEW.capsule_id AND
                        ((c.classification='CONTINUE_CURRENT_THREAD' AND NEW.state='PREPARING')
                         OR (c.classification='UNKNOWN' AND NEW.state='NEEDS_INTENT_CONFIRMATION')
                         OR (c.classification IN ('CREATE_NEW_THREAD','REJECT') AND NEW.state='CANDIDATE')))
                BEGIN SELECT RAISE(ABORT,'Invalid initial Follow-up Candidate authority'); END
                """, """
                CREATE TRIGGER r1e_prepared_capsule_publish_state BEFORE INSERT ON continuation_capsules
                WHEN (SELECT state FROM followup_candidate_authority WHERE capsule_id=NEW.capsule_id)<>'PREPARING'
                BEGIN SELECT RAISE(ABORT,'Prepared Capsule requires PREPARING Candidate'); END
                """, """
                CREATE TRIGGER r1e_prepared_manifest_publish_state BEFORE INSERT ON child_manifest_candidates
                WHEN (SELECT state FROM followup_candidate_authority WHERE capsule_id=NEW.capsule_id)<>'PREPARING'
                BEGIN SELECT RAISE(ABORT,'Prepared Manifest requires PREPARING Candidate'); END
                """, """
                CREATE TRIGGER r1e_candidate_transition BEFORE UPDATE OF state ON followup_candidate_authority
                WHEN NOT ((OLD.state='CANDIDATE' AND NEW.state IN ('CANCELLED','FAILED','HEAD_CONFLICT'))
                    OR (OLD.state='NEEDS_INTENT_CONFIRMATION' AND NEW.state IN ('PREPARING','CANCELLED','FAILED','HEAD_CONFLICT'))
                    OR (OLD.state='PREPARING' AND NEW.state IN ('NEEDS_RISK_CONFIRMATION','READY_TO_ADMIT','FAILED','HEAD_CONFLICT'))
                    OR (OLD.state='NEEDS_RISK_CONFIRMATION' AND NEW.state IN ('READY_TO_ADMIT','CANCELLED','FAILED','HEAD_CONFLICT'))
                    OR (OLD.state='READY_TO_ADMIT' AND NEW.state IN ('ADMITTED','FAILED','HEAD_CONFLICT')))
                BEGIN SELECT RAISE(ABORT,'Invalid Follow-up state transition'); END
                """, """
                CREATE TRIGGER r1e_candidate_transition_version BEFORE UPDATE OF state ON followup_candidate_authority
                WHEN NEW.state<>OLD.state AND NEW.candidate_version<>OLD.candidate_version+1
                BEGIN SELECT RAISE(ABORT,'Follow-up transition version must increment exactly once'); END
                """, """
                CREATE TRIGGER r1e_candidate_version_requires_transition BEFORE UPDATE OF candidate_version ON followup_candidate_authority
                WHEN NEW.candidate_version<>OLD.candidate_version AND NEW.state=OLD.state
                BEGIN SELECT RAISE(ABORT,'Follow-up version change requires state transition'); END
                """, """
                CREATE TRIGGER r1e_prepared_binding_publish_once
                BEFORE UPDATE OF manifest_candidate_hash,risk ON followup_candidate_authority
                WHEN (NEW.manifest_candidate_hash IS NOT OLD.manifest_candidate_hash OR NEW.risk IS NOT OLD.risk)
                  AND NOT (OLD.state='PREPARING' AND OLD.manifest_candidate_hash IS NULL AND OLD.risk IS NULL
                       AND NEW.manifest_candidate_hash IS NOT NULL AND NEW.risk IS NOT NULL)
                BEGIN SELECT RAISE(ABORT,'Prepared Follow-up binding can be published only once'); END
                """, """
                CREATE TRIGGER r1e_confirmation_scope BEFORE INSERT ON followup_confirmation_facts
                WHEN NEW.input_owner<>(SELECT input_owner FROM followup_candidate_definitions WHERE capsule_id=NEW.capsule_id)
                  OR NEW.input_session_id<>(SELECT input_session_id FROM followup_candidate_definitions WHERE capsule_id=NEW.capsule_id)
                  OR NEW.expected_candidate_version<>(SELECT candidate_version FROM followup_candidate_authority WHERE capsule_id=NEW.capsule_id)
                  OR (NEW.confirmation_kind='INTENT' AND
                      ((SELECT state FROM followup_candidate_authority WHERE capsule_id=NEW.capsule_id)<>'NEEDS_INTENT_CONFIRMATION'
                       OR NEW.decision NOT IN ('CONTINUE_CURRENT_THREAD','CREATE_NEW_THREAD','REJECT','CANCEL')))
                  OR (NEW.confirmation_kind='RISK' AND
                      ((SELECT state FROM followup_candidate_authority WHERE capsule_id=NEW.capsule_id)<>'NEEDS_RISK_CONFIRMATION'
                       OR NEW.decision NOT IN ('ACCEPT_RISK','REJECT','CANCEL')
                       OR NEW.prepared_manifest_hash IS NOT (SELECT manifest_candidate_hash FROM followup_candidate_authority
                           WHERE capsule_id=NEW.capsule_id)
                       OR NEW.prepared_risk IS NOT (SELECT risk FROM followup_candidate_authority
                           WHERE capsule_id=NEW.capsule_id)))
                BEGIN SELECT RAISE(ABORT,'Invalid Follow-up confirmation authority'); END
                """, """
                CREATE TRIGGER r1e_unknown_intent_requires_confirmation BEFORE UPDATE OF state ON followup_candidate_authority
                WHEN OLD.state='NEEDS_INTENT_CONFIRMATION' AND NEW.state='PREPARING' AND NOT EXISTS(
                    SELECT 1 FROM followup_confirmation_facts f
                    JOIN followup_intent_classification_facts c USING(capsule_id)
                    WHERE f.capsule_id=NEW.capsule_id AND f.confirmation_kind='INTENT'
                      AND f.decision='CONTINUE_CURRENT_THREAD' AND f.expected_candidate_version=OLD.candidate_version
                      AND c.classification='UNKNOWN')
                BEGIN SELECT RAISE(ABORT,'UNKNOWN Follow-up requires CONTINUE intent confirmation'); END
                """, """
                CREATE TRIGGER r1e_ready_requires_risk_authority BEFORE UPDATE OF state ON followup_candidate_authority
                WHEN NEW.state='READY_TO_ADMIT' AND
                    (NEW.risk IS NULL OR NEW.risk='BLOCKING' OR (NEW.risk IN ('HIGH','UNKNOWN') AND NOT EXISTS(
                        SELECT 1 FROM followup_confirmation_facts f WHERE f.capsule_id=NEW.capsule_id
                          AND f.confirmation_kind='RISK' AND f.decision='ACCEPT_RISK'
                          AND f.expected_candidate_version=OLD.candidate_version
                          AND f.prepared_manifest_hash=NEW.manifest_candidate_hash AND f.prepared_risk=NEW.risk)))
                BEGIN SELECT RAISE(ABORT,'READY Follow-up requires valid non-blocking risk authority'); END
                """, """
                CREATE TRIGGER r1e_candidate_terminal_requires_fact BEFORE UPDATE OF state ON followup_candidate_authority
                WHEN NEW.state IN ('ADMITTED','CANCELLED','FAILED','HEAD_CONFLICT') AND NOT EXISTS(
                    SELECT 1 FROM followup_admission_facts f
                    WHERE f.capsule_id=NEW.capsule_id AND f.result=NEW.state
                      AND f.source_state=OLD.state AND f.source_candidate_version=OLD.candidate_version
                      AND f.resulting_candidate_version=NEW.candidate_version)
                BEGIN SELECT RAISE(ABORT,'Follow-up terminal state requires immutable disposition fact'); END
                """, """
                CREATE TRIGGER r1e_head_conflict_fact_shape BEFORE INSERT ON followup_admission_facts
                WHEN NEW.expected_head_version<>(SELECT expected_head_version FROM followup_candidate_definitions
                        WHERE capsule_id=NEW.capsule_id)
                    OR NEW.source_state<>(SELECT state FROM followup_candidate_authority WHERE capsule_id=NEW.capsule_id)
                    OR NEW.source_candidate_version<>(SELECT candidate_version FROM followup_candidate_authority
                        WHERE capsule_id=NEW.capsule_id)
                    OR NEW.resulting_candidate_version<>NEW.source_candidate_version+1
                    OR (NEW.result='ADMITTED' AND NEW.source_state<>'READY_TO_ADMIT')
                    OR (NEW.result='CANCELLED' AND NEW.source_state NOT IN
                        ('CANDIDATE','NEEDS_INTENT_CONFIRMATION','NEEDS_RISK_CONFIRMATION'))
                    OR (NEW.result='HEAD_CONFLICT' AND
                        (NEW.observed_head_task_id IS NULL OR NEW.observed_head_version IS NULL
                         OR NEW.source_state NOT IN
                            ('CANDIDATE','NEEDS_INTENT_CONFIRMATION','PREPARING','NEEDS_RISK_CONFIRMATION','READY_TO_ADMIT')))
                BEGIN SELECT RAISE(ABORT,'Invalid Follow-up terminal disposition fact'); END
                """, """
                CREATE TRIGGER r1e_head_conflict_fact_apply AFTER INSERT ON followup_admission_facts
                WHEN NEW.result='HEAD_CONFLICT'
                BEGIN
                    UPDATE followup_candidate_authority SET state='HEAD_CONFLICT',
                        candidate_version=NEW.resulting_candidate_version,reason_code='HEAD_CONFLICT',updated_at=NEW.admitted_at
                    WHERE capsule_id=NEW.capsule_id
                      AND state=NEW.source_state AND candidate_version=NEW.source_candidate_version;
                    SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'HEAD_CONFLICT state/fact atomicity failure') END;
                END
                """, """
                CREATE TRIGGER r1e_terminal_fact_apply AFTER INSERT ON followup_admission_facts
                WHEN NEW.result IN ('ADMITTED','CANCELLED','FAILED')
                BEGIN
                    UPDATE followup_candidate_authority SET state=NEW.result,
                        candidate_version=NEW.resulting_candidate_version,
                        admitted_child_task_id=CASE WHEN NEW.result='ADMITTED' THEN NEW.child_task_id ELSE NULL END,
                        retention_disposition=CASE WHEN NEW.result='ADMITTED' THEN 'AUTHORITY_RETAINED'
                            ELSE retention_disposition END,
                        reason_code=NEW.reason_code,updated_at=NEW.admitted_at
                    WHERE capsule_id=NEW.capsule_id AND state=NEW.source_state
                      AND candidate_version=NEW.source_candidate_version;
                    SELECT CASE WHEN changes()<>1 THEN RAISE(ABORT,'Follow-up terminal state/fact atomicity failure') END;
                END
                """, """
                EXECUTE_IF_TABLE_EXISTS task_identities CREATE TRIGGER r1e_parent_row_immutable BEFORE UPDATE ON task_identities
                WHEN EXISTS(SELECT 1 FROM capsule_child_bindings b WHERE b.parent_task_id=OLD.task_id)
                BEGIN SELECT RAISE(ABORT,'Parent Task is immutable after continuation'); END
                """, """
                CREATE TRIGGER r1e_binding_parent_completed BEFORE INSERT ON capsule_child_bindings
                WHEN (SELECT status FROM task_identities WHERE task_id=NEW.parent_task_id)<>'COMPLETED'
                BEGIN SELECT RAISE(ABORT,'Follow-up Parent must be COMPLETED'); END
                """);
    }

    private static String immutable(String name, String table, String label) {
        return "CREATE TRIGGER " + name + "_immutable BEFORE UPDATE ON " + table
                + " BEGIN SELECT RAISE(ABORT,'" + label + " are immutable'); END";
    }

    private static String noDelete(String name, String table, String label) {
        return "CREATE TRIGGER " + name + "_no_delete BEFORE DELETE ON " + table
                + " BEGIN SELECT RAISE(ABORT,'" + label + " are retained'); END";
    }
}
