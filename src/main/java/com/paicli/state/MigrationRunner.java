package com.paicli.state;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public class MigrationRunner {

    private static final List<Migration> MIGRATIONS = List.of(
            new Migration(1, "create schema_migrations", List.of("""
                    CREATE TABLE IF NOT EXISTS schema_migrations (
                        version INTEGER PRIMARY KEY,
                        applied_at TEXT NOT NULL DEFAULT (datetime('now')),
                        description TEXT
                    )
                    """)),
            new Migration(2, "create tasks", List.of("""
                    CREATE TABLE IF NOT EXISTS tasks (
                        task_id TEXT PRIMARY KEY,
                        goal TEXT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'CREATED',
                        provider TEXT,
                        model TEXT,
                        approval_mode TEXT,
                        iterations_completed INTEGER DEFAULT 0,
                        max_iterations INTEGER DEFAULT 10,
                        last_error TEXT,
                        created_at TEXT NOT NULL DEFAULT (datetime('now')),
                        updated_at TEXT NOT NULL DEFAULT (datetime('now')),
                        completed_at TEXT
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_tasks_status ON tasks(status)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_tasks_created ON tasks(created_at)
                    """)),
            new Migration(3, "create events", List.of("""
                    CREATE TABLE IF NOT EXISTS events (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        task_id TEXT NOT NULL,
                        iteration INTEGER NOT NULL,
                        role TEXT NOT NULL,
                        type TEXT NOT NULL,
                        content TEXT,
                        tool_call_id TEXT,
                        tool_call_json TEXT,
                        created_at TEXT NOT NULL DEFAULT (datetime('now')),
                        FOREIGN KEY (task_id) REFERENCES tasks(task_id)
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_events_task ON events(task_id, iteration)
                    """)),
            new Migration(4, "create tool_calls", List.of("""
                    CREATE TABLE IF NOT EXISTS tool_calls (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        task_id TEXT NOT NULL,
                        iteration INTEGER NOT NULL,
                        tool_name TEXT NOT NULL,
                        arguments TEXT,
                        result_success INTEGER,
                        result_output TEXT,
                        result_error TEXT,
                        created_at TEXT NOT NULL DEFAULT (datetime('now')),
                        FOREIGN KEY (task_id) REFERENCES tasks(task_id)
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_tool_calls_task ON tool_calls(task_id)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_tool_calls_tool ON tool_calls(tool_name)
                    """)),
            new Migration(5, "create approval_decisions", List.of("""
                    CREATE TABLE IF NOT EXISTS approval_decisions (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        task_id TEXT NOT NULL,
                        tool_name TEXT NOT NULL,
                        risk_level TEXT NOT NULL,
                        decision TEXT NOT NULL,
                        reason TEXT,
                        created_at TEXT NOT NULL DEFAULT (datetime('now')),
                        FOREIGN KEY (task_id) REFERENCES tasks(task_id)
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_approval_task ON approval_decisions(task_id)
                    """)),
            new Migration(6, "create checkpoints", List.of("""
                    CREATE TABLE IF NOT EXISTS checkpoints (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        task_id TEXT NOT NULL UNIQUE,
                        iteration INTEGER NOT NULL,
                        status TEXT NOT NULL,
                        messages_json TEXT NOT NULL,
                        created_at TEXT NOT NULL DEFAULT (datetime('now')),
                        FOREIGN KEY (task_id) REFERENCES tasks(task_id)
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_checkpoints_task ON checkpoints(task_id)
                    """)),
            new Migration(7, "create memory_notes", List.of("""
                    CREATE TABLE IF NOT EXISTS memory_notes (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        task_id TEXT,
                        category TEXT NOT NULL DEFAULT 'general',
                        content TEXT NOT NULL,
                        created_at TEXT NOT NULL DEFAULT (datetime('now'))
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_memory_task ON memory_notes(task_id)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_memory_category ON memory_notes(category)
                    """)),
            new Migration(8, "add tool call and approval correlation fields", List.of(
                    "ADD_COLUMN_IF_MISSING tool_calls tool_call_id TEXT",
                    "ADD_COLUMN_IF_MISSING approval_decisions iteration INTEGER",
                    "ADD_COLUMN_IF_MISSING approval_decisions tool_call_id TEXT",
                    "CREATE INDEX IF NOT EXISTS idx_tool_calls_tool_call_id ON tool_calls(tool_call_id)",
                    "CREATE INDEX IF NOT EXISTS idx_approval_iteration ON approval_decisions(iteration)",
                    "CREATE INDEX IF NOT EXISTS idx_approval_tool_call_id ON approval_decisions(tool_call_id)"
            )),
            new Migration(9, "create file snapshots", List.of("""
                    CREATE TABLE IF NOT EXISTS file_snapshots (
                        snapshot_id TEXT PRIMARY KEY,
                        task_id TEXT NOT NULL,
                        tool_call_id TEXT,
                        file_path TEXT NOT NULL,
                        snapshot_path TEXT,
                        operation TEXT NOT NULL
                            CHECK(operation IN ('CREATE', 'OVERWRITE', 'MODIFY')),
                        original_exists INTEGER NOT NULL
                            CHECK(original_exists IN (0, 1)),
                        original_size INTEGER NOT NULL
                            CHECK(original_size >= 0),
                        sha256_before TEXT,
                        sha256_after TEXT,
                        status TEXT NOT NULL
                            CHECK(status IN ('CAPTURED', 'APPLIED', 'FAILED', 'RESTORED')),
                        created_at TEXT NOT NULL DEFAULT (datetime('now')),
                        restored_at TEXT,
                        FOREIGN KEY (task_id) REFERENCES tasks(task_id)
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_file_snapshots_task_created
                    ON file_snapshots(task_id, created_at)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_file_snapshots_tool_call_id
                    ON file_snapshots(tool_call_id)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_file_snapshots_status
                    ON file_snapshots(status)
                    """)),
            new Migration(10, "create coding runtime state", List.of("""
                    CREATE TABLE IF NOT EXISTS coding_runs (
                        run_id TEXT PRIMARY KEY,
                        task_id TEXT NOT NULL,
                        run_kind TEXT NOT NULL
                            CHECK(run_kind IN ('INITIAL', 'RESUME')),
                        phase TEXT NOT NULL
                            CHECK(phase IN ('PLANNING', 'EXECUTING', 'VERIFYING',
                                            'REPAIRING', 'COMPLETED', 'FAILED')),
                        verification_command TEXT,
                        max_repair_attempts INTEGER NOT NULL DEFAULT 1
                            CHECK(max_repair_attempts BETWEEN 1 AND 5),
                        repair_attempts_completed INTEGER NOT NULL DEFAULT 0
                            CHECK(repair_attempts_completed >= 0
                                  AND repair_attempts_completed <= max_repair_attempts),
                        started_iteration INTEGER NOT NULL DEFAULT 0
                            CHECK(started_iteration >= 0),
                        last_iteration INTEGER NOT NULL DEFAULT 0
                            CHECK(last_iteration >= started_iteration),
                        last_error TEXT,
                        created_at TEXT NOT NULL DEFAULT (datetime('now')),
                        updated_at TEXT NOT NULL DEFAULT (datetime('now')),
                        completed_at TEXT,
                        UNIQUE(run_id, task_id),
                        FOREIGN KEY(task_id) REFERENCES tasks(task_id)
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_coding_runs_task_created
                    ON coding_runs(task_id, created_at)
                    """, """
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_coding_runs_one_active_task
                    ON coding_runs(task_id)
                    WHERE phase IN ('PLANNING', 'EXECUTING', 'VERIFYING', 'REPAIRING')
                    """, """
                    CREATE TABLE IF NOT EXISTS task_plans (
                        plan_id TEXT PRIMARY KEY,
                        run_id TEXT NOT NULL UNIQUE,
                        task_id TEXT NOT NULL,
                        plan_text TEXT NOT NULL CHECK(length(trim(plan_text)) > 0),
                        status TEXT NOT NULL
                            CHECK(status IN ('GENERATED', 'ACTIVE', 'COMPLETED', 'FAILED')),
                        created_at TEXT NOT NULL DEFAULT (datetime('now')),
                        updated_at TEXT NOT NULL DEFAULT (datetime('now')),
                        FOREIGN KEY(run_id, task_id) REFERENCES coding_runs(run_id, task_id),
                        FOREIGN KEY(task_id) REFERENCES tasks(task_id)
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_task_plans_task
                    ON task_plans(task_id)
                    """, """
                    CREATE TABLE IF NOT EXISTS verification_runs (
                        verification_id TEXT PRIMARY KEY,
                        run_id TEXT NOT NULL,
                        task_id TEXT NOT NULL,
                        repair_attempt INTEGER NOT NULL DEFAULT 0
                            CHECK(repair_attempt >= 0),
                        command TEXT,
                        status TEXT NOT NULL
                            CHECK(status IN ('RUNNING', 'PASSED', 'FAILED',
                                             'TIMED_OUT', 'ERROR', 'SKIPPED')),
                        exit_code INTEGER,
                        output_excerpt TEXT,
                        error_excerpt TEXT,
                        duration_ms INTEGER CHECK(duration_ms IS NULL OR duration_ms >= 0),
                        started_at TEXT NOT NULL DEFAULT (datetime('now')),
                        completed_at TEXT,
                        UNIQUE(verification_id, run_id, task_id),
                        FOREIGN KEY(run_id, task_id) REFERENCES coding_runs(run_id, task_id),
                        FOREIGN KEY(task_id) REFERENCES tasks(task_id)
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_verification_runs_run_created
                    ON verification_runs(run_id, started_at)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_verification_runs_task
                    ON verification_runs(task_id)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_verification_runs_status
                    ON verification_runs(status)
                    """, """
                    CREATE TABLE IF NOT EXISTS repair_attempts (
                        repair_id TEXT PRIMARY KEY,
                        run_id TEXT NOT NULL,
                        task_id TEXT NOT NULL,
                        attempt_no INTEGER NOT NULL CHECK(attempt_no BETWEEN 1 AND 5),
                        trigger_verification_id TEXT NOT NULL,
                        status TEXT NOT NULL
                            CHECK(status IN ('RUNNING', 'COMPLETED', 'FAILED')),
                        failure_summary TEXT,
                        started_at TEXT NOT NULL DEFAULT (datetime('now')),
                        completed_at TEXT,
                        UNIQUE(run_id, attempt_no),
                        FOREIGN KEY(run_id, task_id) REFERENCES coding_runs(run_id, task_id),
                        FOREIGN KEY(task_id) REFERENCES tasks(task_id),
                        FOREIGN KEY(trigger_verification_id, run_id, task_id)
                            REFERENCES verification_runs(verification_id, run_id, task_id)
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_repair_attempts_run_attempt
                    ON repair_attempts(run_id, attempt_no)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_repair_attempts_task
                    ON repair_attempts(task_id)
                    """)),
            new Migration(11, "create skills and hooks runtime state", List.of(
                    "ADD_COLUMN_IF_MISSING coding_runs skills_manifest_sha256 TEXT",
                    "ADD_COLUMN_IF_MISSING coding_runs hooks_manifest_sha256 TEXT",
                    """
                    CREATE TABLE IF NOT EXISTS skill_loads (
                        skill_load_id TEXT PRIMARY KEY,
                        skill_id TEXT NOT NULL,
                        run_id TEXT NOT NULL,
                        task_id TEXT NOT NULL,
                        name TEXT,
                        path TEXT NOT NULL CHECK(length(trim(path)) > 0),
                        description TEXT,
                        content_sha256 TEXT,
                        status TEXT NOT NULL
                            CHECK(status IN ('LOADED', 'REJECTED', 'ERROR')),
                        error_excerpt TEXT,
                        loaded_at TEXT NOT NULL DEFAULT (datetime('now')),
                        UNIQUE(run_id, path),
                        UNIQUE(skill_load_id, run_id, task_id),
                        FOREIGN KEY(run_id, task_id) REFERENCES coding_runs(run_id, task_id),
                        FOREIGN KEY(task_id) REFERENCES tasks(task_id)
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_skill_loads_run_status
                    ON skill_loads(run_id, status, loaded_at)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_skill_loads_task
                    ON skill_loads(task_id)
                    """, """
                    CREATE TABLE IF NOT EXISTS skill_selection_runs (
                        selection_id TEXT PRIMARY KEY,
                        run_id TEXT NOT NULL,
                        task_id TEXT NOT NULL,
                        stage TEXT NOT NULL
                            CHECK(stage IN ('PLAN', 'EXECUTE', 'REPAIR')),
                        occurrence_key TEXT NOT NULL
                            CHECK(length(trim(occurrence_key)) > 0),
                        input_sha256 TEXT,
                        status TEXT NOT NULL
                            CHECK(status IN ('COMPLETED', 'ERROR')),
                        selected_count INTEGER NOT NULL DEFAULT 0
                            CHECK(selected_count >= 0),
                        error_excerpt TEXT,
                        created_at TEXT NOT NULL DEFAULT (datetime('now')),
                        UNIQUE(run_id, stage, occurrence_key),
                        UNIQUE(selection_id, run_id, task_id),
                        FOREIGN KEY(run_id, task_id) REFERENCES coding_runs(run_id, task_id),
                        FOREIGN KEY(task_id) REFERENCES tasks(task_id)
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_skill_selection_runs_run_stage
                    ON skill_selection_runs(run_id, stage, created_at)
                    """, """
                    CREATE TABLE IF NOT EXISTS selected_skills (
                        selected_skill_id TEXT PRIMARY KEY,
                        selection_id TEXT NOT NULL,
                        skill_load_id TEXT NOT NULL,
                        run_id TEXT NOT NULL,
                        task_id TEXT NOT NULL,
                        skill_name TEXT NOT NULL
                            CHECK(length(trim(skill_name)) > 0),
                        rank INTEGER NOT NULL CHECK(rank >= 1),
                        score REAL NOT NULL CHECK(score >= 0),
                        reason TEXT,
                        context_excerpt TEXT,
                        created_at TEXT NOT NULL DEFAULT (datetime('now')),
                        UNIQUE(selection_id, skill_name),
                        UNIQUE(selection_id, rank),
                        FOREIGN KEY(selection_id, run_id, task_id)
                            REFERENCES skill_selection_runs(selection_id, run_id, task_id),
                        FOREIGN KEY(skill_load_id, run_id, task_id)
                            REFERENCES skill_loads(skill_load_id, run_id, task_id),
                        FOREIGN KEY(task_id) REFERENCES tasks(task_id)
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_selected_skills_run
                    ON selected_skills(run_id, created_at)
                    """, """
                    CREATE TABLE IF NOT EXISTS hook_runs (
                        hook_run_id TEXT PRIMARY KEY,
                        run_id TEXT NOT NULL,
                        task_id TEXT NOT NULL,
                        hook_id TEXT NOT NULL CHECK(length(trim(hook_id)) > 0),
                        event TEXT NOT NULL
                            CHECK(event IN ('BEFORE_PLAN', 'AFTER_PLAN',
                                            'BEFORE_EXECUTE', 'AFTER_EXECUTE',
                                            'BEFORE_VERIFY', 'AFTER_VERIFY',
                                            'BEFORE_REPAIR', 'AFTER_REPAIR',
                                            'BEFORE_FINISH')),
                        occurrence_key TEXT NOT NULL
                            CHECK(length(trim(occurrence_key)) > 0),
                        sequence_no INTEGER NOT NULL CHECK(sequence_no >= 0),
                        command TEXT NOT NULL CHECK(length(trim(command)) > 0),
                        status TEXT NOT NULL
                            CHECK(status IN ('RUNNING', 'PASSED', 'WARNED', 'FAILED',
                                             'BLOCKED', 'TIMED_OUT', 'ERROR',
                                             'INTERRUPTED')),
                        exit_code INTEGER,
                        output_excerpt TEXT,
                        error_excerpt TEXT,
                        duration_ms INTEGER
                            CHECK(duration_ms IS NULL OR duration_ms >= 0),
                        failure_policy TEXT NOT NULL
                            CHECK(failure_policy IN ('warn', 'fail')),
                        started_at TEXT NOT NULL DEFAULT (datetime('now')),
                        completed_at TEXT,
                        UNIQUE(run_id, event, occurrence_key, hook_id),
                        FOREIGN KEY(run_id, task_id) REFERENCES coding_runs(run_id, task_id),
                        FOREIGN KEY(task_id) REFERENCES tasks(task_id)
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_hook_runs_run_event
                    ON hook_runs(run_id, event, started_at)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_hook_runs_status
                    ON hook_runs(status)
                    """))
            ,
            new Migration(12, "create Java code index state", List.of("""
                    CREATE TABLE IF NOT EXISTS code_files (
                        file_id TEXT PRIMARY KEY,
                        relative_path TEXT NOT NULL UNIQUE
                            CHECK(length(trim(relative_path)) > 0),
                        language TEXT NOT NULL DEFAULT 'java',
                        package_name TEXT,
                        imports_json TEXT,
                        sha256 TEXT,
                        mtime_millis INTEGER NOT NULL CHECK(mtime_millis >= 0),
                        size_bytes INTEGER NOT NULL CHECK(size_bytes >= 0),
                        status TEXT NOT NULL
                            CHECK(status IN ('INDEXED', 'UNCHANGED', 'ERROR',
                                             'DELETED', 'SKIPPED')),
                        error_excerpt TEXT,
                        unsupported_note TEXT,
                        indexed_at TEXT NOT NULL DEFAULT (datetime('now'))
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_code_files_status
                    ON code_files(status)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_code_files_path
                    ON code_files(relative_path)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_code_files_sha
                    ON code_files(sha256)
                    """, """
                    CREATE TABLE IF NOT EXISTS class_index (
                        class_id TEXT PRIMARY KEY,
                        file_id TEXT NOT NULL,
                        package_name TEXT,
                        class_name TEXT NOT NULL
                            CHECK(length(trim(class_name)) > 0),
                        qualified_name TEXT NOT NULL
                            CHECK(length(trim(qualified_name)) > 0),
                        kind TEXT NOT NULL
                            CHECK(kind IN ('CLASS', 'INTERFACE', 'ENUM',
                                           'ANNOTATION', 'RECORD')),
                        start_line INTEGER CHECK(start_line IS NULL OR start_line >= 1),
                        end_line INTEGER CHECK(end_line IS NULL OR end_line >= 1),
                        modifiers TEXT,
                        extends_name TEXT,
                        implements_names TEXT,
                        FOREIGN KEY(file_id) REFERENCES code_files(file_id)
                            ON DELETE CASCADE
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_class_index_file
                    ON class_index(file_id)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_class_index_qualified_name
                    ON class_index(qualified_name)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_class_index_name
                    ON class_index(class_name)
                    """, """
                    CREATE TABLE IF NOT EXISTS method_index (
                        method_id TEXT PRIMARY KEY,
                        file_id TEXT NOT NULL,
                        class_id TEXT NOT NULL,
                        method_name TEXT NOT NULL
                            CHECK(length(trim(method_name)) > 0),
                        kind TEXT NOT NULL CHECK(kind IN ('METHOD', 'CONSTRUCTOR')),
                        signature TEXT NOT NULL CHECK(length(trim(signature)) > 0),
                        return_type TEXT,
                        parameter_types TEXT,
                        start_line INTEGER CHECK(start_line IS NULL OR start_line >= 1),
                        end_line INTEGER CHECK(end_line IS NULL OR end_line >= 1),
                        chunk_text TEXT NOT NULL,
                        chunk_sha256 TEXT NOT NULL CHECK(length(trim(chunk_sha256)) > 0),
                        modifiers TEXT,
                        FOREIGN KEY(file_id) REFERENCES code_files(file_id)
                            ON DELETE CASCADE,
                        FOREIGN KEY(class_id) REFERENCES class_index(class_id)
                            ON DELETE CASCADE
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_method_index_file
                    ON method_index(file_id)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_method_index_class
                    ON method_index(class_id)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_method_index_name
                    ON method_index(method_name)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_method_index_signature
                    ON method_index(signature)
                    """, """
                    CREATE TABLE IF NOT EXISTS field_index (
                        field_id TEXT PRIMARY KEY,
                        file_id TEXT NOT NULL,
                        class_id TEXT NOT NULL,
                        field_name TEXT NOT NULL
                            CHECK(length(trim(field_name)) > 0),
                        field_type TEXT,
                        start_line INTEGER CHECK(start_line IS NULL OR start_line >= 1),
                        end_line INTEGER CHECK(end_line IS NULL OR end_line >= 1),
                        modifiers TEXT,
                        FOREIGN KEY(file_id) REFERENCES code_files(file_id)
                            ON DELETE CASCADE,
                        FOREIGN KEY(class_id) REFERENCES class_index(class_id)
                            ON DELETE CASCADE
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_field_index_file
                    ON field_index(file_id)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_field_index_class
                    ON field_index(class_id)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_field_index_name
                    ON field_index(field_name)
                    """)),
            new Migration(13, "create code intelligence query audit state", List.of("""
                    CREATE TABLE IF NOT EXISTS code_search_audits (
                        audit_id TEXT PRIMARY KEY,
                        created_at TEXT NOT NULL DEFAULT (datetime('now')),
                        completed_at TEXT,
                        query_type TEXT NOT NULL
                            CHECK(query_type IN ('SYMBOL', 'CALL_SITE', 'CONTEXT')),
                        query_input TEXT,
                        query_input_hash TEXT NOT NULL
                            CHECK(length(trim(query_input_hash)) > 0),
                        status TEXT NOT NULL
                            CHECK(status IN ('STARTED', 'SUCCEEDED', 'FAILED')),
                        result_count INTEGER NOT NULL DEFAULT 0
                            CHECK(result_count >= 0),
                        ambiguity INTEGER NOT NULL DEFAULT 0
                            CHECK(ambiguity IN (0, 1)),
                        confidence TEXT
                            CHECK(confidence IS NULL
                                  OR confidence IN ('HIGH', 'MEDIUM', 'LOW')),
                        selected_context_hash TEXT,
                        duration_millis INTEGER
                            CHECK(duration_millis IS NULL OR duration_millis >= 0),
                        error_excerpt TEXT,
                        task_id TEXT,
                        run_id TEXT,
                        FOREIGN KEY(task_id) REFERENCES tasks(task_id)
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_code_search_audits_created
                    ON code_search_audits(created_at)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_code_search_audits_type_created
                    ON code_search_audits(query_type, created_at)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_code_search_audits_task_run
                    ON code_search_audits(task_id, run_id, created_at)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_class_index_package_name
                    ON class_index(package_name, class_name)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_method_index_class_name
                    ON method_index(class_id, method_name)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_field_index_class_name
                    ON field_index(class_id, field_name)
                    """)),
            new Migration(14, "create structured read-only subagent state", List.of("""
                    CREATE TABLE IF NOT EXISTS subagent_runs (
                        subagent_run_id TEXT PRIMARY KEY,
                        task_id TEXT,
                        coding_run_id TEXT,
                        subagent_name TEXT NOT NULL
                            CHECK(length(trim(subagent_name)) > 0),
                        subagent_kind TEXT NOT NULL
                            CHECK(subagent_kind IN ('SCOUT', 'REVIEWER',
                                                    'VERIFIER', 'CUSTOM')),
                        stage TEXT NOT NULL CHECK(length(trim(stage)) > 0),
                        occurrence_key TEXT NOT NULL
                            CHECK(length(trim(occurrence_key)) > 0),
                        attempt_number INTEGER NOT NULL DEFAULT 1
                            CHECK(attempt_number >= 1),
                        status TEXT NOT NULL
                            CHECK(status IN ('RUNNING', 'SUCCEEDED', 'FAILED',
                                             'TIMED_OUT', 'INTERRUPTED')),
                        provider_name TEXT,
                        read_only INTEGER NOT NULL DEFAULT 1
                            CHECK(read_only IN (0, 1)),
                        tool_policy_summary TEXT,
                        started_at TEXT NOT NULL DEFAULT (datetime('now')),
                        completed_at TEXT,
                        input_hash TEXT,
                        context_hash TEXT,
                        output_hash TEXT,
                        verification_status TEXT
                            CHECK(verification_status IS NULL OR verification_status IN
                                  ('SKIPPED', 'PASSED', 'FAILED', 'TIMED_OUT', 'ERROR')),
                        error_excerpt TEXT,
                        FOREIGN KEY(task_id) REFERENCES tasks(task_id),
                        FOREIGN KEY(coding_run_id) REFERENCES coding_runs(run_id)
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_subagent_runs_task
                    ON subagent_runs(task_id, started_at)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_subagent_runs_coding_run
                    ON subagent_runs(coding_run_id, started_at)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_subagent_runs_occurrence
                    ON subagent_runs(coding_run_id, stage, occurrence_key,
                                     subagent_name, attempt_number)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_subagent_runs_status
                    ON subagent_runs(status)
                    """, """
                    CREATE TABLE IF NOT EXISTS structured_verdicts (
                        verdict_id TEXT PRIMARY KEY,
                        subagent_run_id TEXT NOT NULL,
                        verdict TEXT NOT NULL
                            CHECK(verdict IN ('PASS', 'FAIL', 'INCONCLUSIVE')),
                        severity TEXT NOT NULL
                            CHECK(severity IN ('NONE', 'LOW', 'MEDIUM',
                                               'HIGH', 'CRITICAL')),
                        confidence REAL NOT NULL
                            CHECK(confidence >= 0.0 AND confidence <= 1.0),
                        required_action TEXT NOT NULL
                            CHECK(required_action IN ('NONE', 'FIX', 'RECHECK',
                                                      'HUMAN_REVIEW')),
                        summary TEXT NOT NULL,
                        evidence_json TEXT NOT NULL,
                        evidence_count INTEGER NOT NULL CHECK(evidence_count >= 1),
                        parser_strategy TEXT,
                        raw_output_hash TEXT,
                        created_at TEXT NOT NULL DEFAULT (datetime('now')),
                        FOREIGN KEY(subagent_run_id)
                            REFERENCES subagent_runs(subagent_run_id)
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_structured_verdicts_run
                    ON structured_verdicts(subagent_run_id, created_at)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_structured_verdicts_verdict
                    ON structured_verdicts(verdict, severity)
                    """, """
                    CREATE TABLE IF NOT EXISTS lead_decisions (
                        decision_id TEXT PRIMARY KEY,
                        subagent_run_id TEXT NOT NULL,
                        verdict_id TEXT NOT NULL,
                        decision TEXT NOT NULL
                            CHECK(decision IN ('ACCEPT', 'REJECT',
                                               'REQUEST_RECHECK')),
                        reason TEXT,
                        recheck_occurrence_key TEXT,
                        decided_by TEXT NOT NULL
                            CHECK(decided_by IN ('RUNTIME', 'USER', 'POLICY')),
                        created_at TEXT NOT NULL DEFAULT (datetime('now')),
                        FOREIGN KEY(subagent_run_id)
                            REFERENCES subagent_runs(subagent_run_id),
                        FOREIGN KEY(verdict_id)
                            REFERENCES structured_verdicts(verdict_id)
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_lead_decisions_run
                    ON lead_decisions(subagent_run_id, created_at)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_lead_decisions_verdict
                    ON lead_decisions(verdict_id)
                    """, """
                    CREATE UNIQUE INDEX IF NOT EXISTS ux_lead_decisions_verdict
                    ON lead_decisions(verdict_id)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_lead_decisions_decision
                    ON lead_decisions(decision)
                    """)),
            new Migration(15, "create managed worktree worker coordinator state", List.of("""
                    CREATE TABLE IF NOT EXISTS managed_worktrees (
                        managed_worktree_id TEXT PRIMARY KEY
                            CHECK(length(trim(managed_worktree_id)) BETWEEN 1 AND 256),
                        backend_type TEXT NOT NULL
                            CHECK(backend_type IN ('GIT_LINKED_WORKTREE', 'DIRECTORY_EXPORT')),
                        backend_capability TEXT NOT NULL
                            CHECK(backend_capability IN ('GIT_BRANCH_COMMIT', 'EXPORT_MANIFEST')),
                        repository_identity TEXT NOT NULL
                            CHECK(length(trim(repository_identity)) BETWEEN 1 AND 512),
                        base_commit_sha TEXT NOT NULL
                            CHECK(length(base_commit_sha) IN (40, 64)),
                        worker_branch TEXT,
                        canonical_workspace_path TEXT NOT NULL UNIQUE
                            CHECK(length(trim(canonical_workspace_path)) BETWEEN 1 AND 4096),
                        canonical_state_path TEXT NOT NULL
                            CHECK(length(trim(canonical_state_path)) BETWEEN 1 AND 4096),
                        canonical_trace_path TEXT NOT NULL
                            CHECK(length(trim(canonical_trace_path)) BETWEEN 1 AND 4096),
                        status TEXT NOT NULL
                            CHECK(status IN ('ALLOCATING', 'ACTIVE', 'DIRTY', 'FREEZING',
                                             'CANDIDATE_FROZEN', 'INTEGRATED',
                                             'ABANDONED', 'LOST', 'CLEANED')),
                        dirty_state TEXT NOT NULL
                            CHECK(dirty_state IN ('CLEAN', 'DIRTY', 'UNKNOWN')),
                        owner_worker_run_id TEXT,
                        freeze_token TEXT
                            CHECK(freeze_token IS NULL OR length(freeze_token) <= 128),
                        created_at TEXT NOT NULL DEFAULT (datetime('now')),
                        updated_at TEXT NOT NULL DEFAULT (datetime('now')),
                        abandoned_at TEXT,
                        cleaned_at TEXT,
                        last_error_excerpt TEXT
                            CHECK(last_error_excerpt IS NULL OR length(last_error_excerpt) <= 4096),
                        CHECK((backend_type = 'GIT_LINKED_WORKTREE'
                               AND backend_capability = 'GIT_BRANCH_COMMIT'
                               AND worker_branch IS NOT NULL
                               AND length(trim(worker_branch)) BETWEEN 1 AND 512)
                              OR
                              (backend_type = 'DIRECTORY_EXPORT'
                               AND backend_capability = 'EXPORT_MANIFEST'
                               AND worker_branch IS NULL)),
                        FOREIGN KEY(owner_worker_run_id)
                            REFERENCES worker_runs(worker_run_id)
                    )
                    """, """
                    CREATE UNIQUE INDEX IF NOT EXISTS ux_managed_worktrees_git_branch
                    ON managed_worktrees(repository_identity, worker_branch)
                    WHERE backend_type = 'GIT_LINKED_WORKTREE'
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_managed_worktrees_status
                    ON managed_worktrees(status, updated_at)
                    """, """
                    CREATE TABLE IF NOT EXISTS worker_task_items (
                        task_item_id TEXT PRIMARY KEY
                            CHECK(length(trim(task_item_id)) BETWEEN 1 AND 256),
                        parent_task_id TEXT,
                        specification_summary TEXT NOT NULL
                            CHECK(length(trim(specification_summary)) BETWEEN 1 AND 4096),
                        specification_hash TEXT NOT NULL
                            CHECK(length(trim(specification_hash)) BETWEEN 1 AND 128),
                        status TEXT NOT NULL
                            CHECK(status IN ('PENDING', 'CLAIMED', 'COMPLETED',
                                             'FAILED', 'CANCELLED')),
                        created_at TEXT NOT NULL DEFAULT (datetime('now')),
                        completed_at TEXT,
                        FOREIGN KEY(parent_task_id) REFERENCES tasks(task_id)
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_worker_task_items_parent_status
                    ON worker_task_items(parent_task_id, status, created_at)
                    """, """
                    CREATE TABLE IF NOT EXISTS worker_runs (
                        worker_run_id TEXT PRIMARY KEY
                            CHECK(length(trim(worker_run_id)) BETWEEN 1 AND 256),
                        task_id TEXT,
                        task_item_id TEXT,
                        worktree_id TEXT NOT NULL,
                        occurrence_key TEXT NOT NULL
                            CHECK(length(trim(occurrence_key)) BETWEEN 1 AND 512),
                        attempt_number INTEGER NOT NULL CHECK(attempt_number >= 1),
                        status TEXT NOT NULL
                            CHECK(status IN ('CREATED', 'PREPARING', 'RUNNING',
                                             'COMPLETED', 'FAILED', 'TIMED_OUT',
                                             'INTERRUPTED')),
                        provider_name TEXT,
                        input_hash TEXT CHECK(input_hash IS NULL OR length(input_hash) <= 128),
                        output_hash TEXT CHECK(output_hash IS NULL OR length(output_hash) <= 128),
                        started_at TEXT NOT NULL DEFAULT (datetime('now')),
                        completed_at TEXT,
                        error_excerpt TEXT
                            CHECK(error_excerpt IS NULL OR length(error_excerpt) <= 4096),
                        UNIQUE(worktree_id, occurrence_key, attempt_number),
                        FOREIGN KEY(task_id) REFERENCES tasks(task_id),
                        FOREIGN KEY(task_item_id) REFERENCES worker_task_items(task_item_id),
                        FOREIGN KEY(worktree_id)
                            REFERENCES managed_worktrees(managed_worktree_id)
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_worker_runs_task
                    ON worker_runs(task_id, started_at)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_worker_runs_occurrence
                    ON worker_runs(worktree_id, occurrence_key, attempt_number)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_worker_runs_status
                    ON worker_runs(status, started_at)
                    """, """
                    CREATE TABLE IF NOT EXISTS task_claims (
                        task_claim_id TEXT PRIMARY KEY
                            CHECK(length(trim(task_claim_id)) BETWEEN 1 AND 256),
                        task_item_id TEXT NOT NULL,
                        worker_run_id TEXT NOT NULL,
                        occurrence_key TEXT NOT NULL
                            CHECK(length(trim(occurrence_key)) BETWEEN 1 AND 512),
                        attempt_number INTEGER NOT NULL CHECK(attempt_number >= 1),
                        status TEXT NOT NULL
                            CHECK(status IN ('ACTIVE', 'COMPLETED', 'RELEASED',
                                             'INTERRUPTED', 'ABANDONED')),
                        claimed_at TEXT NOT NULL DEFAULT (datetime('now')),
                        released_at TEXT,
                        completed_at TEXT,
                        interruption_reason TEXT
                            CHECK(interruption_reason IS NULL
                                  OR length(interruption_reason) <= 4096),
                        UNIQUE(task_item_id, occurrence_key, attempt_number),
                        FOREIGN KEY(task_item_id) REFERENCES worker_task_items(task_item_id),
                        FOREIGN KEY(worker_run_id) REFERENCES worker_runs(worker_run_id)
                    )
                    """, """
                    CREATE UNIQUE INDEX IF NOT EXISTS ux_task_claims_one_active_item
                    ON task_claims(task_item_id) WHERE status = 'ACTIVE'
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_task_claims_worker
                    ON task_claims(worker_run_id, claimed_at)
                    """, """
                    CREATE TABLE IF NOT EXISTS worker_mailbox_messages (
                        message_id TEXT PRIMARY KEY
                            CHECK(length(trim(message_id)) BETWEEN 1 AND 256),
                        sender_type TEXT NOT NULL
                            CHECK(sender_type IN ('LEAD', 'WORKER', 'RUNTIME', 'USER')),
                        sender_id TEXT NOT NULL CHECK(length(trim(sender_id)) BETWEEN 1 AND 256),
                        recipient_type TEXT NOT NULL
                            CHECK(recipient_type IN ('LEAD', 'WORKER')),
                        recipient_id TEXT NOT NULL
                            CHECK(length(trim(recipient_id)) BETWEEN 1 AND 256),
                        worker_run_id TEXT,
                        task_item_id TEXT,
                        message_kind TEXT NOT NULL
                            CHECK(message_kind IN ('STATUS', 'QUESTION', 'ANSWER',
                                                   'HANDOFF', 'BLOCKER')),
                        bounded_payload TEXT NOT NULL
                            CHECK(length(bounded_payload) BETWEEN 1 AND 4096),
                        payload_hash TEXT NOT NULL
                            CHECK(length(trim(payload_hash)) BETWEEN 1 AND 128),
                        created_at TEXT NOT NULL DEFAULT (datetime('now')),
                        acknowledged_at TEXT,
                        FOREIGN KEY(worker_run_id) REFERENCES worker_runs(worker_run_id),
                        FOREIGN KEY(task_item_id) REFERENCES worker_task_items(task_item_id)
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_worker_mailbox_recipient
                    ON worker_mailbox_messages(recipient_type, recipient_id, created_at)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_worker_mailbox_run
                    ON worker_mailbox_messages(worker_run_id, created_at)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_worker_mailbox_task_item
                    ON worker_mailbox_messages(task_item_id, created_at)
                    """, """
                    CREATE TABLE IF NOT EXISTS worker_candidates (
                        candidate_id TEXT PRIMARY KEY
                            CHECK(length(trim(candidate_id)) BETWEEN 1 AND 256),
                        worker_run_id TEXT NOT NULL UNIQUE,
                        worktree_id TEXT NOT NULL,
                        task_claim_id TEXT,
                        backend_type TEXT NOT NULL
                            CHECK(backend_type IN ('GIT_LINKED_WORKTREE', 'DIRECTORY_EXPORT')),
                        base_commit_sha TEXT NOT NULL CHECK(length(base_commit_sha) IN (40, 64)),
                        worker_branch TEXT,
                        candidate_commit_sha TEXT,
                        diff_hash TEXT NOT NULL CHECK(length(trim(diff_hash)) BETWEEN 1 AND 128),
                        changed_files_json TEXT NOT NULL
                            CHECK(length(changed_files_json) BETWEEN 2 AND 16384),
                        changed_file_count INTEGER NOT NULL CHECK(changed_file_count >= 0),
                        verification_status TEXT NOT NULL
                            CHECK(verification_status IN ('NOT_RUN', 'PASSED', 'FAILED',
                                                          'TIMED_OUT', 'ERROR', 'SKIPPED')),
                        verification_hash TEXT
                            CHECK(verification_hash IS NULL OR length(verification_hash) <= 128),
                        verification_config_hash TEXT
                            CHECK(verification_config_hash IS NULL
                                  OR length(verification_config_hash) <= 128),
                        bounded_summary TEXT NOT NULL
                            CHECK(length(bounded_summary) BETWEEN 1 AND 4096),
                        status TEXT NOT NULL
                            CHECK(status IN ('DRAFT', 'FROZEN', 'VERIFYING', 'VERIFIED',
                                             'CONFLICT_CHECKED', 'READY_FOR_DECISION',
                                             'VERIFICATION_FAILED', 'CONFLICTED',
                                             'BASE_DRIFTED', 'INVALID', 'INTEGRATED',
                                             'REJECTED', 'REWORK_REQUESTED')),
                        created_at TEXT NOT NULL DEFAULT (datetime('now')),
                        updated_at TEXT NOT NULL DEFAULT (datetime('now')),
                        CHECK((backend_type = 'GIT_LINKED_WORKTREE'
                               AND worker_branch IS NOT NULL
                               AND length(trim(worker_branch)) BETWEEN 1 AND 512)
                              OR
                              (backend_type = 'DIRECTORY_EXPORT'
                               AND worker_branch IS NULL
                               AND candidate_commit_sha IS NULL)),
                        CHECK(candidate_commit_sha IS NULL
                              OR length(candidate_commit_sha) IN (40, 64)),
                        FOREIGN KEY(worker_run_id) REFERENCES worker_runs(worker_run_id),
                        FOREIGN KEY(worktree_id)
                            REFERENCES managed_worktrees(managed_worktree_id),
                        FOREIGN KEY(task_claim_id) REFERENCES task_claims(task_claim_id)
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_worker_candidates_status
                    ON worker_candidates(status, updated_at)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_worker_candidates_claim
                    ON worker_candidates(task_claim_id)
                    """, """
                    CREATE TABLE IF NOT EXISTS worker_merge_proposals (
                        proposal_id TEXT PRIMARY KEY
                            CHECK(length(trim(proposal_id)) BETWEEN 1 AND 256),
                        proposal_key TEXT NOT NULL UNIQUE
                            CHECK(length(trim(proposal_key)) BETWEEN 1 AND 128),
                        repository_identity TEXT NOT NULL
                            CHECK(length(trim(repository_identity)) BETWEEN 1 AND 512),
                        base_commit_sha TEXT NOT NULL CHECK(length(base_commit_sha) IN (40, 64)),
                        proposal_branch TEXT NOT NULL UNIQUE
                            CHECK(length(trim(proposal_branch)) BETWEEN 1 AND 512),
                        canonical_workspace_path TEXT NOT NULL UNIQUE
                            CHECK(length(trim(canonical_workspace_path)) BETWEEN 1 AND 4096),
                        proposal_commit_sha TEXT,
                        proposal_tree_sha TEXT,
                        artifact_hash TEXT,
                        overlap_status TEXT NOT NULL
                            CHECK(overlap_status IN ('UNKNOWN', 'DISJOINT', 'OVERLAP')),
                        merge_status TEXT NOT NULL
                            CHECK(merge_status IN ('PENDING', 'CLEAN_MERGE', 'TEXT_CONFLICT',
                                                   'INVALID_CANDIDATE', 'BASE_MISMATCH',
                                                   'INTERNAL_ERROR')),
                        conflict_files_json TEXT NOT NULL DEFAULT '[]'
                            CHECK(length(conflict_files_json) BETWEEN 2 AND 16384),
                        verification_status TEXT NOT NULL
                            CHECK(verification_status IN ('NOT_RUN', 'PASSED', 'FAILED',
                                                          'TIMED_OUT', 'ERROR', 'SKIPPED')),
                        verification_config_hash TEXT,
                        verification_hash TEXT,
                        status TEXT NOT NULL
                            CHECK(status IN ('PREPARING', 'MERGED', 'VERIFYING',
                                             'READY_FOR_APPROVAL', 'CONFLICTED',
                                             'VERIFICATION_FAILED', 'INVALID',
                                             'INTEGRATED', 'CLEANED')),
                        bounded_summary TEXT NOT NULL
                            CHECK(length(bounded_summary) BETWEEN 1 AND 4096),
                        created_at TEXT NOT NULL DEFAULT (datetime('now')),
                        updated_at TEXT NOT NULL DEFAULT (datetime('now')),
                        last_error_excerpt TEXT
                            CHECK(last_error_excerpt IS NULL OR length(last_error_excerpt) <= 4096),
                        CHECK(proposal_commit_sha IS NULL OR length(proposal_commit_sha) IN (40, 64)),
                        CHECK(proposal_tree_sha IS NULL OR length(proposal_tree_sha) IN (40, 64))
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_worker_merge_proposals_status
                    ON worker_merge_proposals(status, updated_at)
                    """, """
                    CREATE TABLE IF NOT EXISTS worker_merge_proposal_candidates (
                        proposal_id TEXT NOT NULL,
                        candidate_id TEXT NOT NULL,
                        candidate_order INTEGER NOT NULL CHECK(candidate_order BETWEEN 0 AND 1),
                        candidate_commit_sha TEXT NOT NULL CHECK(length(candidate_commit_sha) IN (40, 64)),
                        PRIMARY KEY(proposal_id, candidate_order),
                        UNIQUE(proposal_id, candidate_id),
                        FOREIGN KEY(proposal_id) REFERENCES worker_merge_proposals(proposal_id),
                        FOREIGN KEY(candidate_id) REFERENCES worker_candidates(candidate_id)
                    )
                    """, """
                    CREATE TABLE IF NOT EXISTS worker_integration_decisions (
                        integration_decision_id TEXT PRIMARY KEY
                            CHECK(length(trim(integration_decision_id)) BETWEEN 1 AND 256),
                        proposal_id TEXT NOT NULL UNIQUE,
                        decision TEXT NOT NULL
                            CHECK(decision IN ('APPROVED', 'REJECTED', 'REQUEST_REWORK')),
                        decided_by TEXT NOT NULL
                            CHECK(decided_by IN ('USER', 'RUNTIME', 'POLICY')),
                        reason TEXT CHECK(reason IS NULL OR length(reason) <= 4096),
                        target_branch TEXT,
                        target_head_sha TEXT
                            CHECK(target_head_sha IS NULL OR length(target_head_sha) IN (40, 64)),
                        created_at TEXT NOT NULL DEFAULT (datetime('now')),
                        CHECK(decision <> 'APPROVED' OR decided_by = 'USER'),
                        CHECK(decision = 'APPROVED'
                              OR (reason IS NOT NULL AND length(trim(reason)) > 0)),
                        FOREIGN KEY(proposal_id) REFERENCES worker_merge_proposals(proposal_id)
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_worker_integration_decisions_decision
                    ON worker_integration_decisions(decision, created_at)
                    """, """
                    CREATE TABLE IF NOT EXISTS worker_integration_runs (
                        integration_run_id TEXT PRIMARY KEY
                            CHECK(length(trim(integration_run_id)) BETWEEN 1 AND 256),
                        proposal_id TEXT NOT NULL,
                        decision_id TEXT NOT NULL,
                        target_branch TEXT NOT NULL
                            CHECK(length(trim(target_branch)) BETWEEN 1 AND 512),
                        expected_base_sha TEXT NOT NULL CHECK(length(expected_base_sha) IN (40, 64)),
                        status TEXT NOT NULL
                            CHECK(status IN ('RUNNING', 'INTEGRATED', 'FAILED', 'INTERRUPTED',
                                             'BASE_DRIFT', 'TARGET_WORKSPACE_DIRTY')),
                        actual_head_before TEXT CHECK(actual_head_before IS NULL OR length(actual_head_before) IN (40, 64)),
                        actual_head_after TEXT CHECK(actual_head_after IS NULL OR length(actual_head_after) IN (40, 64)),
                        started_at TEXT NOT NULL DEFAULT (datetime('now')),
                        completed_at TEXT,
                        error_excerpt TEXT
                            CHECK(error_excerpt IS NULL OR length(error_excerpt) <= 4096),
                        UNIQUE(proposal_id, target_branch),
                        FOREIGN KEY(proposal_id) REFERENCES worker_merge_proposals(proposal_id),
                        FOREIGN KEY(decision_id) REFERENCES worker_integration_decisions(integration_decision_id)
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_worker_integration_runs_status
                    ON worker_integration_runs(status, started_at)
                    """, """
                    CREATE TABLE IF NOT EXISTS worker_cleanup_runs (
                        cleanup_run_id TEXT PRIMARY KEY
                            CHECK(length(trim(cleanup_run_id)) BETWEEN 1 AND 256),
                        worktree_id TEXT,
                        proposal_id TEXT,
                        target_type TEXT NOT NULL
                            CHECK(target_type IN ('WORKTREE', 'PROPOSAL')),
                        requested_by TEXT NOT NULL
                            CHECK(requested_by IN ('USER', 'RUNTIME')),
                        status TEXT NOT NULL
                            CHECK(status IN ('RUNNING', 'PARTIAL', 'COMPLETED', 'FAILED')),
                        workspace_cleanup_status TEXT NOT NULL
                            CHECK(workspace_cleanup_status IN ('PENDING', 'SUCCEEDED',
                                                               'FAILED', 'SKIPPED', 'RETAINED')),
                        state_cleanup_status TEXT NOT NULL
                            CHECK(state_cleanup_status IN ('PENDING', 'SUCCEEDED',
                                                           'FAILED', 'SKIPPED', 'RETAINED')),
                        trace_cleanup_status TEXT NOT NULL
                            CHECK(trace_cleanup_status IN ('PENDING', 'SUCCEEDED',
                                                           'FAILED', 'SKIPPED', 'RETAINED')),
                        started_at TEXT NOT NULL DEFAULT (datetime('now')),
                        completed_at TEXT,
                        error_excerpt TEXT
                            CHECK(error_excerpt IS NULL OR length(error_excerpt) <= 4096),
                        FOREIGN KEY(worktree_id)
                            REFERENCES managed_worktrees(managed_worktree_id),
                        FOREIGN KEY(proposal_id)
                            REFERENCES worker_merge_proposals(proposal_id),
                        CHECK((target_type = 'WORKTREE' AND worktree_id IS NOT NULL AND proposal_id IS NULL)
                           OR (target_type = 'PROPOSAL' AND proposal_id IS NOT NULL AND worktree_id IS NULL))
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_worker_cleanup_runs_worktree
                    ON worker_cleanup_runs(worktree_id, started_at)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_worker_cleanup_runs_status
                    ON worker_cleanup_runs(status, started_at)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_worker_cleanup_runs_proposal
                    ON worker_cleanup_runs(proposal_id, started_at)
                    """)),
            new Migration(16, "create MCP manifest and audit stores", List.of("""
                    CREATE TABLE IF NOT EXISTS mcp_server_manifests (
                        manifest_id TEXT PRIMARY KEY,
                        server_id TEXT NOT NULL,
                        manifest_format_version TEXT NOT NULL,
                        transport TEXT NOT NULL CHECK(transport = 'STDIO'),
                        protocol_versions_json TEXT NOT NULL,
                        config_hash TEXT NOT NULL,
                        executable_identity_hash TEXT NOT NULL,
                        executable_content_hash TEXT NOT NULL,
                        executable_basename TEXT NOT NULL,
                        working_directory_identity_hash TEXT NOT NULL,
                        args_hash TEXT NOT NULL,
                        environment_references_json TEXT NOT NULL,
                        tool_allowlist_json TEXT NOT NULL,
                        startup_timeout_ms INTEGER NOT NULL CHECK(startup_timeout_ms > 0),
                        request_timeout_ms INTEGER NOT NULL CHECK(request_timeout_ms > 0),
                        shutdown_timeout_ms INTEGER NOT NULL CHECK(shutdown_timeout_ms > 0),
                        max_pending_requests INTEGER NOT NULL CHECK(max_pending_requests > 0),
                        max_stdout_line_bytes INTEGER NOT NULL CHECK(max_stdout_line_bytes > 0),
                        max_stderr_bytes INTEGER NOT NULL CHECK(max_stderr_bytes > 0),
                        max_catalog_pages INTEGER NOT NULL CHECK(max_catalog_pages > 0),
                        max_tools INTEGER NOT NULL CHECK(max_tools > 0),
                        max_catalog_bytes INTEGER NOT NULL CHECK(max_catalog_bytes > 0),
                        max_single_schema_bytes INTEGER NOT NULL CHECK(max_single_schema_bytes > 0),
                        max_output_bytes INTEGER NOT NULL CHECK(max_output_bytes > 0),
                        approval_policy_version TEXT NOT NULL,
                        sanitizer_policy_version TEXT NOT NULL,
                        schema_policy_version TEXT NOT NULL,
                        created_at TEXT NOT NULL DEFAULT (datetime('now')),
                        UNIQUE(server_id, config_hash)
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_mcp_manifests_server_created
                    ON mcp_server_manifests(server_id, created_at)
                    """, """
                    CREATE TABLE IF NOT EXISTS mcp_sessions (
                        session_id TEXT PRIMARY KEY,
                        manifest_id TEXT NOT NULL,
                        server_id TEXT NOT NULL,
                        run_scope_key TEXT NOT NULL,
                        occurrence_key TEXT NOT NULL,
                        attempt_number INTEGER NOT NULL CHECK(attempt_number >= 1),
                        status TEXT NOT NULL CHECK(status IN ('CONFIGURED','STARTING','INITIALIZING','READY','UNHEALTHY','SHUTTING_DOWN','CLOSED','FAILED')),
                        negotiated_protocol_version TEXT,
                        server_info_hash TEXT,
                        capability_summary_json TEXT,
                        catalog_id TEXT,
                        process_identity_hash TEXT,
                        started_at TEXT NOT NULL DEFAULT (datetime('now')),
                        ready_at TEXT,
                        completed_at TEXT,
                        process_exit_code INTEGER,
                        stderr_hash TEXT,
                        bounded_stderr_excerpt TEXT CHECK(bounded_stderr_excerpt IS NULL OR length(bounded_stderr_excerpt) <= 4096),
                        error_category TEXT,
                        bounded_error_excerpt TEXT CHECK(bounded_error_excerpt IS NULL OR length(bounded_error_excerpt) <= 4096),
                        UNIQUE(server_id, run_scope_key, occurrence_key, attempt_number),
                        FOREIGN KEY(manifest_id) REFERENCES mcp_server_manifests(manifest_id),
                        FOREIGN KEY(catalog_id) REFERENCES mcp_tool_catalogs(catalog_id) DEFERRABLE INITIALLY DEFERRED
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_mcp_sessions_occurrence ON mcp_sessions(server_id, run_scope_key, occurrence_key, attempt_number)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_mcp_sessions_status ON mcp_sessions(status, started_at)
                    """, """
                    CREATE TABLE IF NOT EXISTS mcp_tool_catalogs (
                        catalog_id TEXT PRIMARY KEY,
                        session_id TEXT NOT NULL UNIQUE,
                        manifest_id TEXT NOT NULL,
                        server_id TEXT NOT NULL,
                        protocol_version TEXT NOT NULL,
                        catalog_hash TEXT NOT NULL,
                        status TEXT NOT NULL CHECK(status IN ('BUILDING','FROZEN','STALE','INVALID')),
                        page_count INTEGER NOT NULL CHECK(page_count >= 0),
                        tool_count INTEGER NOT NULL CHECK(tool_count >= 0),
                        total_schema_bytes INTEGER NOT NULL CHECK(total_schema_bytes >= 0),
                        created_at TEXT NOT NULL DEFAULT (datetime('now')),
                        frozen_at TEXT,
                        stale_at TEXT,
                        invalid_reason TEXT CHECK(invalid_reason IS NULL OR length(invalid_reason) <= 4096),
                        FOREIGN KEY(session_id) REFERENCES mcp_sessions(session_id),
                        FOREIGN KEY(manifest_id) REFERENCES mcp_server_manifests(manifest_id)
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_mcp_catalogs_status ON mcp_tool_catalogs(status, created_at)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_mcp_catalogs_server_hash ON mcp_tool_catalogs(server_id, catalog_hash)
                    """, """
                    CREATE TABLE IF NOT EXISTS mcp_tools (
                        mcp_tool_id TEXT PRIMARY KEY,
                        catalog_id TEXT NOT NULL,
                        server_id TEXT NOT NULL,
                        original_name TEXT NOT NULL,
                        exposed_name TEXT NOT NULL,
                        bounded_title TEXT CHECK(bounded_title IS NULL OR length(bounded_title) <= 256),
                        bounded_description TEXT CHECK(bounded_description IS NULL OR length(bounded_description) <= 2048),
                        normalized_input_schema_json TEXT NOT NULL,
                        schema_hash TEXT NOT NULL,
                        exposure_status TEXT NOT NULL CHECK(exposure_status IN ('EXPOSED','REJECTED')),
                        rejection_category TEXT,
                        created_at TEXT NOT NULL DEFAULT (datetime('now')),
                        UNIQUE(catalog_id, original_name),
                        UNIQUE(catalog_id, exposed_name),
                        FOREIGN KEY(catalog_id) REFERENCES mcp_tool_catalogs(catalog_id)
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_mcp_tools_catalog_exposure ON mcp_tools(catalog_id, exposure_status, original_name)
                    """, """
                    CREATE TABLE IF NOT EXISTS mcp_tool_calls (
                        mcp_tool_call_id TEXT PRIMARY KEY,
                        session_id TEXT NOT NULL,
                        catalog_id TEXT NOT NULL,
                        mcp_tool_id TEXT NOT NULL,
                        task_id TEXT,
                        coding_run_id TEXT,
                        occurrence_key TEXT NOT NULL,
                        attempt_number INTEGER NOT NULL CHECK(attempt_number >= 1),
                        request_id TEXT,
                        manifest_hash TEXT NOT NULL,
                        catalog_hash TEXT NOT NULL,
                        schema_hash TEXT NOT NULL,
                        exposed_tool_name TEXT NOT NULL,
                        original_tool_name TEXT NOT NULL,
                        arguments_hash TEXT NOT NULL,
                        bounded_arguments_summary TEXT CHECK(bounded_arguments_summary IS NULL OR length(bounded_arguments_summary) <= 4096),
                        approval_decision TEXT NOT NULL CHECK(approval_decision IN ('PENDING','APPROVED','DENIED','NOT_REQUESTED')),
                        status TEXT NOT NULL CHECK(status IN ('PENDING','DENIED','RUNNING','SUCCEEDED','TOOL_ERROR','TIMED_OUT','PROTOCOL_ERROR','PROCESS_EXITED','SANITIZER_REJECTED','CANCELLED','UNKNOWN_OUTCOME')),
                        started_at TEXT NOT NULL DEFAULT (datetime('now')),
                        completed_at TEXT,
                        result_hash TEXT,
                        truncated INTEGER NOT NULL DEFAULT 0 CHECK(truncated IN (0,1)),
                        redacted INTEGER NOT NULL DEFAULT 0 CHECK(redacted IN (0,1)),
                        error_category TEXT,
                        bounded_error_excerpt TEXT CHECK(bounded_error_excerpt IS NULL OR length(bounded_error_excerpt) <= 4096),
                        UNIQUE(session_id, occurrence_key, attempt_number),
                        UNIQUE(session_id, request_id),
                        FOREIGN KEY(session_id) REFERENCES mcp_sessions(session_id),
                        FOREIGN KEY(catalog_id) REFERENCES mcp_tool_catalogs(catalog_id),
                        FOREIGN KEY(mcp_tool_id) REFERENCES mcp_tools(mcp_tool_id),
                        FOREIGN KEY(task_id) REFERENCES tasks(task_id),
                        FOREIGN KEY(coding_run_id) REFERENCES coding_runs(run_id)
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_mcp_tool_calls_status ON mcp_tool_calls(status, started_at)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_mcp_tool_calls_session_request ON mcp_tool_calls(session_id, request_id)
                    """)),
            new Migration(17, "create Core Demo run state", List.of("""
                    CREATE TABLE IF NOT EXISTS core_demo_runs (
                        run_id TEXT PRIMARY KEY
                            CHECK(length(run_id) BETWEEN 8 AND 64),
                        scenario_id TEXT NOT NULL
                            CHECK(length(scenario_id) BETWEEN 1 AND 128),
                        fixture_version TEXT NOT NULL
                            CHECK(length(fixture_version) BETWEEN 1 AND 32),
                        fixture_manifest_hash TEXT NOT NULL
                            CHECK(length(fixture_manifest_hash) = 64),
                        seed_tree_hash TEXT NOT NULL
                            CHECK(length(seed_tree_hash) = 64),
                        workspace_identity_hash TEXT NOT NULL
                            CHECK(length(workspace_identity_hash) = 64),
                        headless INTEGER NOT NULL CHECK(headless = 1),
                        provider_type TEXT NOT NULL
                            CHECK(length(provider_type) BETWEEN 1 AND 64),
                        status TEXT NOT NULL CHECK(status IN (
                            'PREPARING','READY_FOR_WORKFLOW','WORKFLOW_RUNNING',
                            'WORKFLOW_SUCCEEDED','WORKFLOW_FAILED','ARTIFACTS_WRITING',
                            'COMPLETED','FAILED','CLEANUP_PENDING','CLEANED',
                            'RETAINED_FOR_DIAGNOSTICS')),
                        current_phase TEXT NOT NULL CHECK(current_phase IN (
                            'ALLOCATION','EXTRACTION','BASELINE','PLAN',
                            'CODE_INTELLIGENCE','EXECUTE','VERIFY','REPAIR',
                            'RESUME','REWIND','REPORT','CLEANUP')),
                        task_id TEXT,
                        workflow_occurrence_key TEXT,
                        baseline_status TEXT NOT NULL
                            CHECK(baseline_status IN ('NOT_RUN','PASSED','FAILED')),
                        baseline_test_count INTEGER
                            CHECK(baseline_test_count IS NULL OR baseline_test_count >= 0),
                        baseline_tree_hash TEXT
                            CHECK(baseline_tree_hash IS NULL OR length(baseline_tree_hash) = 64),
                        final_tree_hash TEXT
                            CHECK(final_tree_hash IS NULL OR length(final_tree_hash) = 64),
                        rewind_tree_hash TEXT
                            CHECK(rewind_tree_hash IS NULL OR length(rewind_tree_hash) = 64),
                        repair_count INTEGER NOT NULL DEFAULT 0
                            CHECK(repair_count BETWEEN 0 AND 5),
                        resumed INTEGER NOT NULL DEFAULT 0 CHECK(resumed IN (0,1)),
                        rewound INTEGER NOT NULL DEFAULT 0 CHECK(rewound IN (0,1)),
                        resume_status TEXT NOT NULL DEFAULT 'NOT_STARTED' CHECK(resume_status IN (
                            'NOT_STARTED','PENDING','VALIDATING','RUNNING','SUCCEEDED','FAILED')),
                        resume_occurrence_key TEXT
                            CHECK(resume_occurrence_key IS NULL OR length(resume_occurrence_key) BETWEEN 1 AND 128),
                        rewind_status TEXT NOT NULL DEFAULT 'NOT_STARTED' CHECK(rewind_status IN (
                            'NOT_STARTED','PENDING','RUNNING','SUCCEEDED','PARTIAL','FAILED')),
                        rewind_occurrence_key TEXT
                            CHECK(rewind_occurrence_key IS NULL OR length(rewind_occurrence_key) BETWEEN 1 AND 128),
                        artifact_status TEXT NOT NULL CHECK(artifact_status IN (
                            'NOT_STARTED','STAGING','COMMITTED','FAILED')),
                        exit_code INTEGER CHECK(exit_code IS NULL OR exit_code IN (0,2,3,4,5,6,7,8)),
                        error_category TEXT CHECK(error_category IS NULL OR length(error_category) <= 128),
                        bounded_error_excerpt TEXT
                            CHECK(bounded_error_excerpt IS NULL OR length(bounded_error_excerpt) <= 4096),
                        created_at TEXT NOT NULL DEFAULT (datetime('now')),
                        updated_at TEXT NOT NULL DEFAULT (datetime('now')),
                        completed_at TEXT,
                        FOREIGN KEY(task_id) REFERENCES tasks(task_id)
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_core_demo_runs_status
                    ON core_demo_runs(status, updated_at)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_core_demo_runs_scenario_created
                    ON core_demo_runs(scenario_id, created_at)
                    """, """
                    CREATE TABLE IF NOT EXISTS core_demo_checkpoints (
                        checkpoint_id TEXT PRIMARY KEY
                            CHECK(length(checkpoint_id) BETWEEN 8 AND 128),
                        demo_run_id TEXT NOT NULL UNIQUE,
                        task_id TEXT NOT NULL,
                        workflow_occurrence_key TEXT NOT NULL
                            CHECK(length(workflow_occurrence_key) BETWEEN 1 AND 128),
                        phase TEXT NOT NULL CHECK(phase = 'VERIFY_FAILED_BEFORE_REPAIR'),
                        initial_tree_hash TEXT NOT NULL CHECK(length(initial_tree_hash) = 64),
                        verification_evidence_hash TEXT NOT NULL CHECK(length(verification_evidence_hash) = 64),
                        plan_hash TEXT NOT NULL CHECK(length(plan_hash) = 64),
                        code_intelligence_hash TEXT NOT NULL CHECK(length(code_intelligence_hash) = 64),
                        changed_files_json TEXT NOT NULL CHECK(length(changed_files_json) BETWEEN 2 AND 2048),
                        snapshot_ids_json TEXT NOT NULL CHECK(length(snapshot_ids_json) BETWEEN 2 AND 4096),
                        repair_attempt INTEGER NOT NULL CHECK(repair_attempt = 0),
                        provider_script_version TEXT NOT NULL CHECK(length(provider_script_version) BETWEEN 1 AND 64),
                        approval_profile_version TEXT NOT NULL CHECK(length(approval_profile_version) BETWEEN 1 AND 64),
                        verifier_config_hash TEXT NOT NULL CHECK(length(verifier_config_hash) = 64),
                        semantic_hash TEXT NOT NULL CHECK(length(semantic_hash) = 64),
                        created_at TEXT NOT NULL DEFAULT (datetime('now')),
                        FOREIGN KEY(demo_run_id) REFERENCES core_demo_runs(run_id),
                        FOREIGN KEY(task_id) REFERENCES tasks(task_id)
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_core_demo_checkpoints_task
                    ON core_demo_checkpoints(task_id, created_at)
                    """)),
            new Migration(18, "persist bounded interactive task facts", List.of("""
                    CREATE TABLE IF NOT EXISTS interactive_task_facts (
                        task_id TEXT PRIMARY KEY,
                        run_id TEXT NOT NULL,
                        code_intelligence_status TEXT NOT NULL
                            CHECK(code_intelligence_status IN ('USED','NOT_USED','FAILED')),
                        indexed_java_files INTEGER NOT NULL DEFAULT 0 CHECK(indexed_java_files >= 0),
                        context_item_count INTEGER NOT NULL DEFAULT 0 CHECK(context_item_count >= 0),
                        query_count INTEGER NOT NULL DEFAULT 0 CHECK(query_count >= 0),
                        created_at TEXT NOT NULL DEFAULT (datetime('now')),
                        updated_at TEXT NOT NULL DEFAULT (datetime('now')),
                        FOREIGN KEY(task_id) REFERENCES tasks(task_id),
                        FOREIGN KEY(run_id) REFERENCES coding_runs(run_id)
                    )
                    """, """
                    CREATE TABLE IF NOT EXISTS interactive_edit_proposals (
                        proposal_id TEXT PRIMARY KEY CHECK(length(proposal_id) BETWEEN 8 AND 128),
                        task_id TEXT NOT NULL,
                        run_id TEXT NOT NULL,
                        relative_path TEXT NOT NULL CHECK(length(relative_path) BETWEEN 1 AND 1024),
                        after_hash TEXT NOT NULL CHECK(length(after_hash) = 64),
                        additions INTEGER NOT NULL CHECK(additions >= 0),
                        deletions INTEGER NOT NULL CHECK(deletions >= 0),
                        status TEXT NOT NULL CHECK(status IN (
                            'PROPOSED','REJECTED','CANCELLED','APPLIED','SUPERSEDED','REWOUND')),
                        snapshot_id TEXT,
                        truncated INTEGER NOT NULL DEFAULT 0 CHECK(truncated IN (0,1)),
                        redacted INTEGER NOT NULL DEFAULT 0 CHECK(redacted IN (0,1)),
                        created_at TEXT NOT NULL DEFAULT (datetime('now')),
                        updated_at TEXT NOT NULL DEFAULT (datetime('now')),
                        resolved_at TEXT,
                        FOREIGN KEY(task_id) REFERENCES tasks(task_id),
                        FOREIGN KEY(run_id) REFERENCES coding_runs(run_id),
                        FOREIGN KEY(snapshot_id) REFERENCES file_snapshots(snapshot_id)
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_interactive_proposals_task_created
                    ON interactive_edit_proposals(task_id, created_at, proposal_id)
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_interactive_proposals_snapshot
                    ON interactive_edit_proposals(snapshot_id)
                    """)),
            new Migration(19, "create R1A durable identity foundations", List.of("""
                    CREATE TABLE work_threads (
                        thread_id TEXT PRIMARY KEY CHECK(length(thread_id)=36 AND substr(thread_id,1,4)='thr_'
                            AND substr(thread_id,5) NOT GLOB '*[^0-9a-f]*' AND substr(thread_id,17,1)='4'
                            AND substr(thread_id,21,1) IN ('8','9','a','b')),
                        workspace_identity TEXT NOT NULL, goal TEXT NOT NULL,
                        status TEXT NOT NULL CHECK(status IN ('OPEN','TERMINAL','LEGACY_UNCERTAIN','CONTINUATION_UNAVAILABLE')),
                        legacy_state TEXT, created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
                        UNIQUE(workspace_identity, thread_id)
                    )
                    """, """
                    CREATE TABLE task_identities (
                        task_id TEXT PRIMARY KEY CHECK(length(task_id)=36 AND substr(task_id,1,4)='tsk_'
                            AND substr(task_id,5) NOT GLOB '*[^0-9a-f]*' AND substr(task_id,17,1)='4'
                            AND substr(task_id,21,1) IN ('8','9','a','b')),
                        thread_id TEXT NOT NULL, parent_task_id TEXT, goal TEXT NOT NULL,
                        status TEXT NOT NULL CHECK(status IN ('CREATED','RUNNING','COMPLETED','FAILED','CANCELLED','INTERRUPTED','UNKNOWN')),
                        admitted_manifest_id TEXT, admitted_manifest_hash TEXT,
                        created_at TEXT NOT NULL, terminal_at TEXT,
                        UNIQUE(thread_id,task_id), UNIQUE(parent_task_id),
                        FOREIGN KEY(thread_id) REFERENCES work_threads(thread_id),
                        FOREIGN KEY(thread_id,parent_task_id) REFERENCES task_identities(thread_id,task_id),
                        FOREIGN KEY(task_id,admitted_manifest_id,admitted_manifest_hash)
                            REFERENCES instruction_manifests(task_id,instruction_manifest_id,canonical_hash)
                            DEFERRABLE INITIALLY DEFERRED,
                        CHECK((admitted_manifest_id IS NULL)=(admitted_manifest_hash IS NULL))
                    )
                    """, """
                    CREATE TABLE thread_heads (
                        thread_id TEXT PRIMARY KEY, head_task_id TEXT NOT NULL,
                        pointer_version INTEGER NOT NULL DEFAULT 0 CHECK(pointer_version>=0),
                        FOREIGN KEY(thread_id) REFERENCES work_threads(thread_id),
                        FOREIGN KEY(thread_id,head_task_id) REFERENCES task_identities(thread_id,task_id)
                    )
                    """, """
                    CREATE TABLE protected_instruction_artifacts (
                        artifact_id TEXT PRIMARY KEY CHECK(length(artifact_id) BETWEEN 16 AND 128),
                        content_hash TEXT NOT NULL CHECK(length(content_hash)=64 AND content_hash NOT GLOB '*[^0-9a-f]*'),
                        protected_payload BLOB, storage_reference TEXT,
                        sanitizer_version TEXT NOT NULL, retention_class TEXT NOT NULL,
                        integrity_status TEXT NOT NULL CHECK(integrity_status='VERIFIED'), created_at TEXT NOT NULL,
                        UNIQUE(artifact_id,content_hash),
                        CHECK((protected_payload IS NOT NULL) <> (storage_reference IS NOT NULL))
                    )
                    """, """
                    CREATE TABLE instruction_manifests (
                        instruction_manifest_id TEXT PRIMARY KEY CHECK(length(instruction_manifest_id) BETWEEN 16 AND 128),
                        task_id TEXT NOT NULL UNIQUE, schema_version INTEGER NOT NULL CHECK(schema_version>=1),
                        resolution_policy_version TEXT NOT NULL, canonical_hash TEXT NOT NULL
                            CHECK(length(canonical_hash)=64 AND canonical_hash NOT GLOB '*[^0-9a-f]*'),
                        state TEXT NOT NULL CHECK(state='COMMITTED'), created_at TEXT NOT NULL,
                        UNIQUE(task_id,instruction_manifest_id), UNIQUE(task_id,instruction_manifest_id,canonical_hash),
                        FOREIGN KEY(task_id) REFERENCES task_identities(task_id)
                    )
                    """, """
                    CREATE TABLE instruction_entries (
                        entry_id TEXT PRIMARY KEY CHECK(length(entry_id) BETWEEN 16 AND 128),
                        instruction_manifest_id TEXT NOT NULL, source_type TEXT NOT NULL, source_path TEXT NOT NULL,
                        scope TEXT NOT NULL, status TEXT NOT NULL CHECK(status IN ('APPLIED','IGNORED')),
                        applied_order INTEGER, content_hash TEXT, artifact_id TEXT, parse_result TEXT,
                        exclusion_reason TEXT, observed_at TEXT NOT NULL,
                        UNIQUE(instruction_manifest_id,source_type,source_path,scope),
                        UNIQUE(instruction_manifest_id,applied_order),
                        FOREIGN KEY(instruction_manifest_id) REFERENCES instruction_manifests(instruction_manifest_id),
                        FOREIGN KEY(artifact_id,content_hash) REFERENCES protected_instruction_artifacts(artifact_id,content_hash),
                        CHECK((status='APPLIED' AND applied_order>=0 AND artifact_id IS NOT NULL AND content_hash IS NOT NULL
                               AND exclusion_reason IS NULL)
                           OR (status='IGNORED' AND applied_order IS NULL AND artifact_id IS NULL AND content_hash IS NULL
                               AND length(trim(exclusion_reason))>0))
                    )
                    """, """
                    CREATE TABLE durable_runs (
                        run_id TEXT PRIMARY KEY CHECK(length(run_id)=36 AND substr(run_id,1,4)='run_'
                            AND substr(run_id,5) NOT GLOB '*[^0-9a-f]*' AND substr(run_id,17,1)='4'
                            AND substr(run_id,21,1) IN ('8','9','a','b')),
                        task_id TEXT NOT NULL, thread_id TEXT NOT NULL,
                        run_kind TEXT NOT NULL CHECK(run_kind IN ('INITIAL','RESUME')), resumed_from_run_id TEXT,
                        instruction_manifest_id TEXT NOT NULL, instruction_manifest_hash TEXT NOT NULL,
                        created_at TEXT NOT NULL, terminal_status TEXT,
                        UNIQUE(task_id,run_id), UNIQUE(thread_id,task_id,run_id),
                        FOREIGN KEY(thread_id,task_id) REFERENCES task_identities(thread_id,task_id),
                        FOREIGN KEY(task_id,instruction_manifest_id,instruction_manifest_hash)
                            REFERENCES instruction_manifests(task_id,instruction_manifest_id,canonical_hash),
                        FOREIGN KEY(task_id,resumed_from_run_id) REFERENCES durable_runs(task_id,run_id)
                    )
                    """, """
                    CREATE TABLE constraint_revisions (
                        constraint_revision_id TEXT PRIMARY KEY CHECK(length(constraint_revision_id) BETWEEN 16 AND 128),
                        task_id TEXT NOT NULL, revision_number INTEGER NOT NULL CHECK(revision_number>=1),
                        canonical_content TEXT NOT NULL, canonical_hash TEXT NOT NULL
                            CHECK(length(canonical_hash)=64 AND canonical_hash NOT GLOB '*[^0-9a-f]*'),
                        created_at TEXT NOT NULL,
                        UNIQUE(task_id,constraint_revision_id), UNIQUE(task_id,revision_number), UNIQUE(task_id,canonical_hash),
                        FOREIGN KEY(task_id) REFERENCES task_identities(task_id)
                    )
                    """, """
                    CREATE TABLE task_constraint_activations (
                        task_id TEXT PRIMARY KEY, active_revision_id TEXT NOT NULL,
                        activated_by_run_id TEXT, pointer_version INTEGER NOT NULL DEFAULT 0 CHECK(pointer_version>=0),
                        activated_at TEXT NOT NULL,
                        FOREIGN KEY(task_id) REFERENCES task_identities(task_id),
                        FOREIGN KEY(task_id,active_revision_id) REFERENCES constraint_revisions(task_id,constraint_revision_id),
                        FOREIGN KEY(task_id,activated_by_run_id) REFERENCES durable_runs(task_id,run_id)
                    )
                    """, """
                    CREATE TABLE task_context_generations (
                        generation_id TEXT PRIMARY KEY CHECK(length(generation_id)=36 AND substr(generation_id,1,4)='gen_'
                            AND substr(generation_id,5) NOT GLOB '*[^0-9a-f]*' AND substr(generation_id,17,1)='4'
                            AND substr(generation_id,21,1) IN ('8','9','a','b')),
                        task_id TEXT NOT NULL, parent_generation_id TEXT, created_by_run_id TEXT NOT NULL,
                        instruction_manifest_id TEXT NOT NULL, instruction_manifest_hash TEXT NOT NULL,
                        state TEXT NOT NULL CHECK(state IN ('PREPARED','COMMITTED','SUPERSEDED','ABORTED')),
                        token_metadata TEXT, created_at TEXT NOT NULL, committed_at TEXT,
                        UNIQUE(task_id,generation_id),
                        FOREIGN KEY(task_id) REFERENCES task_identities(task_id),
                        FOREIGN KEY(task_id,parent_generation_id) REFERENCES task_context_generations(task_id,generation_id),
                        FOREIGN KEY(task_id,created_by_run_id) REFERENCES durable_runs(task_id,run_id),
                        FOREIGN KEY(task_id,instruction_manifest_id,instruction_manifest_hash)
                            REFERENCES instruction_manifests(task_id,instruction_manifest_id,canonical_hash),
                        CHECK((state='COMMITTED' AND committed_at IS NOT NULL) OR state<>'COMMITTED')
                    )
                    """, """
                    CREATE TABLE task_context_pointers (
                        task_id TEXT PRIMARY KEY, active_generation_id TEXT NOT NULL, owner_run_id TEXT NOT NULL,
                        pointer_version INTEGER NOT NULL DEFAULT 0 CHECK(pointer_version>=0),
                        FOREIGN KEY(task_id) REFERENCES task_identities(task_id),
                        FOREIGN KEY(task_id,active_generation_id) REFERENCES task_context_generations(task_id,generation_id),
                        FOREIGN KEY(task_id,owner_run_id) REFERENCES durable_runs(task_id,run_id)
                    )
                    """, """
                    CREATE TABLE side_effect_occurrences (
                        occurrence_id TEXT PRIMARY KEY CHECK(length(occurrence_id)=36 AND substr(occurrence_id,1,4)='occ_'
                            AND substr(occurrence_id,5) NOT GLOB '*[^0-9a-f]*' AND substr(occurrence_id,17,1)='4'
                            AND substr(occurrence_id,21,1) IN ('8','9','a','b')),
                        thread_id TEXT NOT NULL, task_id TEXT NOT NULL, run_id TEXT NOT NULL,
                        iteration_id TEXT NOT NULL, logical_operation_key TEXT NOT NULL, tool_call_id TEXT,
                        operation_type TEXT NOT NULL, target TEXT NOT NULL, approval_id TEXT, snapshot_id TEXT,
                        status TEXT NOT NULL CHECK(status IN ('STARTED','COMPLETED','FAILED','UNKNOWN')),
                        external_outcome TEXT, canonical_input_hash TEXT NOT NULL
                            CHECK(length(canonical_input_hash)=64 AND canonical_input_hash NOT GLOB '*[^0-9a-f]*'),
                        result_hash TEXT CHECK(result_hash IS NULL OR (length(result_hash)=64 AND result_hash NOT GLOB '*[^0-9a-f]*')),
                        started_at TEXT NOT NULL, completed_at TEXT,
                        UNIQUE(task_id,logical_operation_key), UNIQUE(task_id,run_id,operation_type,logical_operation_key),
                        FOREIGN KEY(thread_id,task_id,run_id) REFERENCES durable_runs(thread_id,task_id,run_id)
                    )
                    """, """
                    CREATE TABLE task_ownership_leases (
                        task_id TEXT PRIMARY KEY,
                        owner_instance_id TEXT NOT NULL CHECK(length(trim(owner_instance_id))>0),
                        owner_run_id TEXT NOT NULL,
                        lease_token TEXT NOT NULL UNIQUE CHECK(length(trim(lease_token))>0),
                        fencing_token INTEGER NOT NULL CHECK(fencing_token>=1),
                        cas_version INTEGER NOT NULL CHECK(cas_version>=1),
                        state TEXT NOT NULL CHECK(state IN ('ACTIVE','RENEWAL_FAILED','EXPIRED','RELEASED')),
                        acquired_at TEXT NOT NULL, heartbeat_at TEXT NOT NULL, expires_at TEXT NOT NULL,
                        recovery_metadata TEXT,
                        FOREIGN KEY(task_id) REFERENCES task_identities(task_id),
                        FOREIGN KEY(task_id,owner_run_id) REFERENCES durable_runs(task_id,run_id)
                    )
                    """, """
                    CREATE TABLE legacy_aliases (
                        legacy_entity_family TEXT NOT NULL
                            CHECK(legacy_entity_family IN ('THREAD','TASK','RUN','GENERATION','CAPSULE',
                                                           'PLAN','PLAN_STEP','STEERING','OCCURRENCE')),
                        legacy_value TEXT NOT NULL CHECK(length(trim(legacy_value))>0),
                        canonical_target_id TEXT,
                        classification TEXT NOT NULL
                            CHECK(classification IN ('LEGACY_UNCERTAIN','CONTINUATION_UNAVAILABLE',
                                                     'INVALID_LEGACY_RECORD')),
                        mapping_status TEXT NOT NULL
                            CHECK(mapping_status IN ('UNMAPPED','MAPPED','AMBIGUOUS','INVALID')),
                        source_type TEXT NOT NULL CHECK(length(trim(source_type))>0),
                        source_reference TEXT NOT NULL CHECK(length(trim(source_reference))>0),
                        provenance TEXT NOT NULL CHECK(length(trim(provenance))>0),
                        continuation_status TEXT NOT NULL
                            CHECK(continuation_status IN ('ELIGIBLE','CONTINUATION_UNAVAILABLE',
                                                          'NOT_ASSESSED')),
                        migration_version INTEGER NOT NULL CHECK(migration_version>=19),
                        created_at TEXT NOT NULL,
                        PRIMARY KEY(legacy_entity_family,legacy_value,source_type,source_reference),
                        UNIQUE(legacy_entity_family,legacy_value,canonical_target_id),
                        CHECK((mapping_status='MAPPED')=(canonical_target_id IS NOT NULL)),
                        CHECK(canonical_target_id IS NULL OR
                            (length(canonical_target_id)=36
                             AND substr(canonical_target_id,5) NOT GLOB '*[^0-9a-f]*'
                             AND substr(canonical_target_id,17,1)='4'
                             AND substr(canonical_target_id,21,1) IN ('8','9','a','b')
                             AND substr(canonical_target_id,1,4)=CASE legacy_entity_family
                                 WHEN 'THREAD' THEN 'thr_' WHEN 'TASK' THEN 'tsk_'
                                 WHEN 'RUN' THEN 'run_' WHEN 'GENERATION' THEN 'gen_'
                                 WHEN 'CAPSULE' THEN 'cap_' WHEN 'PLAN' THEN 'pln_'
                                 WHEN 'PLAN_STEP' THEN 'stp_' WHEN 'STEERING' THEN 'str_'
                                 WHEN 'OCCURRENCE' THEN 'occ_' END))
                    )
                    """, """
                    CREATE INDEX idx_r1a_task_thread_created ON task_identities(thread_id,created_at)
                    """, """
                    CREATE INDEX idx_r1a_runs_task_created ON durable_runs(task_id,created_at)
                    """, """
                    CREATE INDEX idx_r1a_generations_task_created ON task_context_generations(task_id,created_at)
                    """, """
                    CREATE INDEX idx_r1a_constraint_task_revision ON constraint_revisions(task_id,revision_number)
                    """, """
                    CREATE INDEX idx_r1a_manifest_entries_order ON instruction_entries(instruction_manifest_id,applied_order)
                    """, """
                    CREATE INDEX idx_r1a_occurrence_task_status ON side_effect_occurrences(task_id,status)
                    """, """
                    CREATE INDEX idx_r1a_occurrence_run_operation ON side_effect_occurrences(task_id,run_id,operation_type)
                    """, """
                    CREATE INDEX idx_r1a_legacy_alias_lookup
                    ON legacy_aliases(legacy_entity_family,legacy_value)
                    """, """
                    CREATE INDEX idx_r1a_legacy_alias_target
                    ON legacy_aliases(canonical_target_id)
                    """, """
                    CREATE TRIGGER r1a_constraint_revision_immutable BEFORE UPDATE ON constraint_revisions
                    BEGIN SELECT RAISE(ABORT,'constraint revisions are immutable'); END
                    """, """
                    CREATE TRIGGER r1a_manifest_immutable BEFORE UPDATE ON instruction_manifests
                    BEGIN SELECT RAISE(ABORT,'instruction manifests are immutable'); END
                    """, """
                    CREATE TRIGGER r1a_manifest_entry_immutable BEFORE UPDATE ON instruction_entries
                    BEGIN SELECT RAISE(ABORT,'instruction entries are immutable'); END
                    """, """
                    CREATE TRIGGER r1a_artifact_immutable BEFORE UPDATE ON protected_instruction_artifacts
                    BEGIN SELECT RAISE(ABORT,'instruction artifacts are immutable'); END
                    """, """
                    CREATE TRIGGER r1a_occurrence_no_regression BEFORE UPDATE OF status ON side_effect_occurrences
                    WHEN OLD.status IN ('COMPLETED','FAILED','UNKNOWN') OR
                         (OLD.status='STARTED' AND NEW.status NOT IN ('COMPLETED','FAILED','UNKNOWN'))
                    BEGIN SELECT RAISE(ABORT,'invalid occurrence transition'); END
                    """)),
            new Migration(20, "create structured plan authority", v20Statements()),
            new Migration(21, "create plan step execution state and attempts", v21Statements()),
            new Migration(22, "create plan evidence and completion authority", v22Statements()),
            new Migration(23, "create steering and safe point authority", v23Statements()),
            new Migration(24, "create continuation capsule and child admission authority",
                    V24Migration.statements()),
            new Migration(25, "create provider call telemetry", V25Migration.statements()),
            new Migration(26, "create context generation and compaction", V26Migration.statements()),
            new Migration(27, "create durable user result completion gate", List.of("""
                    CREATE TABLE IF NOT EXISTS task_user_results (
                        task_id TEXT NOT NULL,
                        run_id TEXT NOT NULL,
                        plan_revision_id TEXT NOT NULL,
                        response_mode TEXT NOT NULL CHECK(response_mode IN ('REQUIRED','MINIMAL')),
                        content TEXT NOT NULL,
                        content_hash TEXT NOT NULL,
                        evidence_hash TEXT NOT NULL,
                        validation_status TEXT NOT NULL CHECK(validation_status IN ('VALID','INVALID')),
                        render_status TEXT NOT NULL CHECK(render_status IN ('PENDING','RENDERED')),
                        created_at TEXT NOT NULL,
                        rendered_at TEXT,
                        PRIMARY KEY(task_id,run_id),
                        FOREIGN KEY(task_id) REFERENCES tasks(task_id),
                        FOREIGN KEY(run_id) REFERENCES coding_runs(run_id),
                        FOREIGN KEY(plan_revision_id) REFERENCES plan_revisions(plan_revision_id)
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_task_user_results_revision
                    ON task_user_results(task_id,plan_revision_id)
                    """, """
                    CREATE TRIGGER IF NOT EXISTS task_user_result_content_immutable
                    BEFORE UPDATE OF task_id,run_id,plan_revision_id,response_mode,content,
                        content_hash,evidence_hash,validation_status ON task_user_results
                    BEGIN SELECT RAISE(ABORT,'task user result content is immutable'); END
                    """, """
                    CREATE TRIGGER IF NOT EXISTS task_user_result_no_delete
                    BEFORE DELETE ON task_user_results
                    BEGIN SELECT RAISE(ABORT,'task user result cannot be deleted'); END
                    """)),
            new Migration(28, "create V3 Plan Tool authority and Evidence slot bindings",
                    v28Statements()),
            new Migration(29, "create bounded Task continuity segment accounting", List.of("""
                    CREATE TABLE IF NOT EXISTS task_continuity_segments (
                        task_id TEXT NOT NULL,
                        run_id TEXT NOT NULL,
                        segment_ordinal INTEGER NOT NULL CHECK(segment_ordinal>=1),
                        planning_attempt_ordinal INTEGER NOT NULL
                            CHECK(planning_attempt_ordinal>=1),
                        trigger TEXT NOT NULL,
                        state TEXT NOT NULL CHECK(state IN
                            ('STARTED','COMPLETED','RECOVERABLE_FAILED',
                             'WAITING_FOR_USER','TERMINAL_FAILED')),
                        policy_hash TEXT NOT NULL CHECK(length(policy_hash)=64
                            AND policy_hash NOT GLOB '*[^0-9a-f]*'),
                        start_iteration INTEGER NOT NULL CHECK(start_iteration>=0),
                        end_iteration INTEGER NOT NULL CHECK(end_iteration>=start_iteration),
                        provider_turns INTEGER NOT NULL CHECK(provider_turns>=0),
                        tool_calls INTEGER NOT NULL CHECK(tool_calls>=0),
                        input_tokens INTEGER NOT NULL CHECK(input_tokens>=0),
                        output_tokens INTEGER NOT NULL CHECK(output_tokens>=0),
                        active_elapsed_ms INTEGER NOT NULL CHECK(active_elapsed_ms>=0),
                        recovery_directive TEXT NOT NULL,
                        terminal_reason TEXT,
                        created_at TEXT NOT NULL,
                        updated_at TEXT NOT NULL,
                        PRIMARY KEY(task_id,segment_ordinal),
                        UNIQUE(run_id,segment_ordinal),
                        FOREIGN KEY(task_id) REFERENCES tasks(task_id),
                        FOREIGN KEY(run_id) REFERENCES coding_runs(run_id)
                    )
                    """, """
                    CREATE INDEX IF NOT EXISTS idx_task_continuity_run
                    ON task_continuity_segments(run_id,segment_ordinal)
                    """, """
                    CREATE TRIGGER IF NOT EXISTS task_continuity_identity_immutable
                    BEFORE UPDATE OF task_id,run_id,segment_ordinal,planning_attempt_ordinal,
                        trigger,policy_hash,start_iteration,recovery_directive
                    ON task_continuity_segments
                    BEGIN SELECT RAISE(ABORT,'continuity segment identity is immutable'); END
                    """, """
                    CREATE TRIGGER IF NOT EXISTS task_continuity_no_delete
                    BEFORE DELETE ON task_continuity_segments
                    BEGIN SELECT RAISE(ABORT,'continuity segment cannot be deleted'); END
                    """)),
            new Migration(30, "bind verification evidence to workspace Candidate", List.of(
                    "ADD_COLUMN_IF_TABLE_EXISTS verification_runs candidate_digest_kind TEXT",
                    "ADD_COLUMN_IF_TABLE_EXISTS verification_runs candidate_digest TEXT", """
                    EXECUTE_IF_TABLE_EXISTS verification_runs CREATE TRIGGER verification_candidate_identity_insert
                    BEFORE INSERT ON verification_runs
                    WHEN (NEW.candidate_digest_kind IS NULL)
                            != (NEW.candidate_digest IS NULL)
                      OR (NEW.candidate_digest_kind IS NOT NULL AND (
                            NEW.candidate_digest_kind!='WORKSPACE_CONTENT_SHA256_V1'
                            OR length(NEW.candidate_digest)!=64
                            OR NEW.candidate_digest GLOB '*[^0-9a-f]*'))
                      OR (NEW.status IN ('RUNNING','PASSED')
                            AND NEW.candidate_digest_kind IS NULL)
                    BEGIN SELECT RAISE(ABORT,'invalid verification Candidate identity'); END
                    """, """
                    EXECUTE_IF_TABLE_EXISTS verification_runs CREATE TRIGGER verification_candidate_identity_update_valid
                    BEFORE UPDATE ON verification_runs
                    WHEN (NEW.candidate_digest_kind IS NULL)
                            != (NEW.candidate_digest IS NULL)
                      OR (NEW.candidate_digest_kind IS NOT NULL AND (
                            NEW.candidate_digest_kind!='WORKSPACE_CONTENT_SHA256_V1'
                            OR length(NEW.candidate_digest)!=64
                            OR NEW.candidate_digest GLOB '*[^0-9a-f]*'))
                      OR (NEW.status='PASSED' AND NEW.candidate_digest_kind IS NULL)
                    BEGIN SELECT RAISE(ABORT,'invalid verification Candidate identity'); END
                    """, """
                    EXECUTE_IF_TABLE_EXISTS verification_runs CREATE TRIGGER verification_candidate_identity_immutable
                    BEFORE UPDATE OF candidate_digest_kind,candidate_digest ON verification_runs
                    WHEN NOT (NEW.candidate_digest_kind IS OLD.candidate_digest_kind
                        AND NEW.candidate_digest IS OLD.candidate_digest)
                    BEGIN SELECT RAISE(ABORT,'verification Candidate identity is immutable'); END
                    """, """
                    EXECUTE_IF_TABLE_EXISTS plan_evidence CREATE TRIGGER h5_verification_evidence_candidate_bound
                    BEFORE INSERT ON plan_evidence
                    WHEN NEW.producer_kind='VERIFICATION' AND NOT EXISTS (
                        SELECT 1 FROM verification_runs v
                        WHERE v.verification_id=NEW.producer_identity
                          AND v.task_id=NEW.task_id AND v.status='PASSED'
                          AND v.candidate_digest_kind='WORKSPACE_CONTENT_SHA256_V1'
                          AND length(v.candidate_digest)=64
                          AND v.candidate_digest NOT GLOB '*[^0-9a-f]*')
                    BEGIN SELECT RAISE(ABORT,'Verification Evidence has no Candidate identity'); END
                    """)),
            new Migration(31, "bind planless Result and Provider FINAL to Candidate", List.of("""
                    ALTER TABLE task_user_results RENAME TO task_user_results_v30
                    """, """
                    DROP TRIGGER task_user_result_content_immutable
                    """, """
                    DROP TRIGGER task_user_result_no_delete
                    """, """
                    CREATE TABLE task_user_results (
                        task_id TEXT NOT NULL,
                        run_id TEXT NOT NULL,
                        plan_revision_id TEXT,
                        response_mode TEXT NOT NULL CHECK(response_mode IN ('REQUIRED','MINIMAL')),
                        content TEXT NOT NULL,
                        content_hash TEXT NOT NULL,
                        evidence_hash TEXT NOT NULL,
                        validation_status TEXT NOT NULL CHECK(validation_status IN ('VALID','INVALID')),
                        render_status TEXT NOT NULL CHECK(render_status IN ('PENDING','RENDERED')),
                        created_at TEXT NOT NULL,
                        rendered_at TEXT,
                        PRIMARY KEY(task_id,run_id),
                        FOREIGN KEY(task_id) REFERENCES tasks(task_id),
                        FOREIGN KEY(run_id) REFERENCES coding_runs(run_id),
                        FOREIGN KEY(plan_revision_id) REFERENCES plan_revisions(plan_revision_id)
                    )
                    """, """
                    INSERT INTO task_user_results(task_id,run_id,plan_revision_id,response_mode,
                        content,content_hash,evidence_hash,validation_status,render_status,created_at,
                        rendered_at)
                    SELECT task_id,run_id,plan_revision_id,response_mode,content,content_hash,
                        evidence_hash,validation_status,render_status,created_at,rendered_at
                    FROM task_user_results_v30
                    """, """
                    DROP TABLE task_user_results_v30
                    """, """
                    CREATE INDEX idx_task_user_results_revision
                    ON task_user_results(task_id,plan_revision_id)
                    """, """
                    CREATE TRIGGER task_user_result_content_immutable
                    BEFORE UPDATE OF task_id,run_id,plan_revision_id,response_mode,content,
                        content_hash,evidence_hash,validation_status ON task_user_results
                    BEGIN SELECT RAISE(ABORT,'task user result content is immutable'); END
                    """, """
                    CREATE TRIGGER task_user_result_no_delete
                    BEFORE DELETE ON task_user_results
                    BEGIN SELECT RAISE(ABORT,'task user result cannot be deleted'); END
                    """, """
                    ALTER TABLE side_effect_occurrences ADD COLUMN final_candidate_digest TEXT
                        CHECK(final_candidate_digest IS NULL OR (
                            operation_type='PROVIDER' AND status='COMPLETED'
                            AND result_hash IS NOT NULL
                            AND length(final_candidate_digest)=64
                            AND final_candidate_digest NOT GLOB '*[^0-9a-f]*'))
                    """, """
                    DROP TRIGGER r1a_occurrence_no_regression
                    """, """
                    CREATE TRIGGER r1a_occurrence_no_regression
                    BEFORE UPDATE OF status,result_hash,final_candidate_digest
                    ON side_effect_occurrences
                    WHEN OLD.status IN ('COMPLETED','FAILED','UNKNOWN') OR
                         (OLD.status='STARTED' AND NEW.status NOT IN ('COMPLETED','FAILED','UNKNOWN'))
                    BEGIN SELECT RAISE(ABORT,'invalid occurrence transition'); END
                    """)),
            new Migration(32, "freeze MCP Task input binding", List.of("""
                    CREATE TABLE mcp_task_bindings (
                        task_id TEXT PRIMARY KEY REFERENCES tasks(task_id),
                        server_id TEXT NOT NULL,
                        manifest_hash TEXT NOT NULL,
                        catalog_hash TEXT NOT NULL
                    )
                    """, """
                    CREATE TRIGGER mcp_task_binding_immutable BEFORE UPDATE ON mcp_task_bindings
                    BEGIN SELECT RAISE(ABORT,'MCP Task binding is immutable'); END
                    """, """
                    CREATE TRIGGER mcp_task_binding_retained BEFORE DELETE ON mcp_task_bindings
                    BEGIN SELECT RAISE(ABORT,'MCP Task binding is retained'); END
                    """)),
            new Migration(33, "freeze complete Skill packages and resource provenance", V33Migration.statements())
    );

    private static List<String> v28Statements() {
        return List.of("""
                CREATE TABLE plan_v3_revisions (
                    task_id TEXT NOT NULL, plan_revision_id TEXT NOT NULL,
                    canonical_schema_version INTEGER NOT NULL CHECK(canonical_schema_version=3),
                    PRIMARY KEY(task_id,plan_revision_id),
                    FOREIGN KEY(task_id,plan_revision_id)
                        REFERENCES plan_revisions(task_id,plan_revision_id)
                )
                """, """
                CREATE TABLE plan_tool_requirements (
                    task_id TEXT NOT NULL, plan_revision_id TEXT NOT NULL,
                    step_revision_id TEXT NOT NULL,
                    requirement_id TEXT NOT NULL CHECK(length(trim(requirement_id)) BETWEEN 1 AND 128),
                    requirement_order INTEGER NOT NULL CHECK(requirement_order>=0),
                    tool_name TEXT NOT NULL CHECK(tool_name IN
                        ('read_file','list_files','grep','run_command','git_diff','write_file')),
                    effect_class TEXT NOT NULL CHECK(effect_class IN
                        ('READ','WRITE','VERIFY','ARTIFACT','GENERAL')),
                    selector_kind TEXT NOT NULL CHECK(selector_kind IN
                        ('EXACT_PATH','EXACT_GREP','EXACT_COMMAND','EXACT_PATH_SET','EXACT_GIT_DIFF')),
                    selector_canonical_json TEXT NOT NULL
                        CHECK(length(trim(selector_canonical_json)) BETWEEN 2 AND 16384),
                    selector_digest TEXT NOT NULL CHECK(length(selector_digest)=64
                        AND selector_digest NOT GLOB '*[^0-9a-f]*'),
                    min_successes INTEGER NOT NULL CHECK(min_successes BETWEEN 1 AND 64),
                    max_occurrences INTEGER NOT NULL CHECK(max_occurrences BETWEEN min_successes AND 64),
                    freshness_policy TEXT NOT NULL CHECK(freshness_policy IN
                        ('RESUME_REUSE_ONLY','FRESH_EXECUTION_REQUIRED')),
                    PRIMARY KEY(task_id,plan_revision_id,step_revision_id,requirement_id),
                    UNIQUE(task_id,plan_revision_id,requirement_id),
                    UNIQUE(task_id,plan_revision_id,step_revision_id,requirement_order),
                    UNIQUE(task_id,plan_revision_id,step_revision_id,requirement_id,selector_digest),
                    FOREIGN KEY(task_id,plan_revision_id)
                        REFERENCES plan_v3_revisions(task_id,plan_revision_id),
                    FOREIGN KEY(task_id,plan_revision_id,step_revision_id)
                        REFERENCES plan_step_definitions(task_id,plan_revision_id,step_revision_id)
                )
                """, """
                CREATE TABLE plan_occurrence_slot_bindings (
                    task_id TEXT NOT NULL, plan_revision_id TEXT NOT NULL,
                    step_revision_id TEXT NOT NULL, requirement_id TEXT NOT NULL,
                    slot_ordinal INTEGER NOT NULL CHECK(slot_ordinal>=1),
                    run_id TEXT NOT NULL, attempt_number INTEGER NOT NULL CHECK(attempt_number>=1),
                    occurrence_id TEXT NOT NULL, tool_call_id TEXT NOT NULL
                        CHECK(length(trim(tool_call_id)) BETWEEN 1 AND 256),
                    selector_digest TEXT NOT NULL CHECK(length(selector_digest)=64
                        AND selector_digest NOT GLOB '*[^0-9a-f]*'),
                    bound_at TEXT NOT NULL,
                    PRIMARY KEY(task_id,plan_revision_id,step_revision_id,requirement_id,slot_ordinal),
                    UNIQUE(task_id,occurrence_id), UNIQUE(task_id,tool_call_id),
                    UNIQUE(task_id,plan_revision_id,step_revision_id,requirement_id,slot_ordinal,
                        run_id,attempt_number,occurrence_id,tool_call_id,selector_digest),
                    FOREIGN KEY(task_id,plan_revision_id,step_revision_id,requirement_id,selector_digest)
                        REFERENCES plan_tool_requirements(task_id,plan_revision_id,
                            step_revision_id,requirement_id,selector_digest),
                    FOREIGN KEY(task_id,plan_revision_id,step_revision_id,run_id,attempt_number)
                        REFERENCES plan_step_attempts(task_id,plan_revision_id,
                            step_revision_id,run_id,attempt_number),
                    FOREIGN KEY(occurrence_id) REFERENCES side_effect_occurrences(occurrence_id)
                )
                """, """
                CREATE TABLE plan_evidence_slot_bindings (
                    task_id TEXT NOT NULL, plan_revision_id TEXT NOT NULL,
                    step_revision_id TEXT NOT NULL, requirement_id TEXT NOT NULL,
                    slot_ordinal INTEGER NOT NULL CHECK(slot_ordinal>=1),
                    run_id TEXT NOT NULL, attempt_number INTEGER NOT NULL CHECK(attempt_number>=1),
                    occurrence_id TEXT NOT NULL, tool_call_id TEXT NOT NULL,
                    evidence_kind TEXT NOT NULL CHECK(evidence_kind='TOOL_CALL'),
                    producer_kind TEXT NOT NULL CHECK(producer_kind='OCCURRENCE'),
                    selector_digest TEXT NOT NULL CHECK(length(selector_digest)=64
                        AND selector_digest NOT GLOB '*[^0-9a-f]*'),
                    bound_at TEXT NOT NULL,
                    PRIMARY KEY(task_id,plan_revision_id,step_revision_id,requirement_id,slot_ordinal),
                    UNIQUE(task_id,occurrence_id), UNIQUE(task_id,tool_call_id),
                    FOREIGN KEY(task_id,plan_revision_id,step_revision_id,requirement_id,slot_ordinal,
                        run_id,attempt_number,occurrence_id,tool_call_id,selector_digest)
                        REFERENCES plan_occurrence_slot_bindings(task_id,plan_revision_id,
                            step_revision_id,requirement_id,slot_ordinal,run_id,attempt_number,
                            occurrence_id,tool_call_id,selector_digest),
                    FOREIGN KEY(task_id,plan_revision_id,step_revision_id,run_id,attempt_number,
                        evidence_kind,producer_kind,occurrence_id)
                        REFERENCES plan_evidence(task_id,plan_revision_id,step_revision_id,run_id,
                            attempt_number,evidence_kind,producer_kind,producer_identity)
                )
                """,
                "CREATE INDEX idx_plan_tool_requirements_step ON plan_tool_requirements(task_id,plan_revision_id,step_revision_id,requirement_order)",
                "CREATE INDEX idx_plan_occurrence_slots_step ON plan_occurrence_slot_bindings(task_id,plan_revision_id,step_revision_id,requirement_id,slot_ordinal)",
                "CREATE INDEX idx_plan_evidence_slots_step ON plan_evidence_slot_bindings(task_id,plan_revision_id,step_revision_id,requirement_id,slot_ordinal)",
                """
                CREATE TRIGGER plan_v3_revision_immutable BEFORE UPDATE ON plan_v3_revisions
                BEGIN SELECT RAISE(ABORT,'V3 Plan revision marker is immutable'); END
                """, """
                CREATE TRIGGER plan_v3_revision_no_delete BEFORE DELETE ON plan_v3_revisions
                BEGIN SELECT RAISE(ABORT,'V3 Plan revision marker is retained'); END
                """, """
                CREATE TRIGGER plan_tool_requirement_immutable BEFORE UPDATE ON plan_tool_requirements
                BEGIN SELECT RAISE(ABORT,'Plan Tool requirement is immutable'); END
                """, """
                CREATE TRIGGER plan_tool_requirement_no_delete BEFORE DELETE ON plan_tool_requirements
                BEGIN SELECT RAISE(ABORT,'Plan Tool requirement is retained'); END
                """, """
                CREATE TRIGGER plan_occurrence_slot_immutable BEFORE UPDATE ON plan_occurrence_slot_bindings
                BEGIN SELECT RAISE(ABORT,'Plan occurrence slot binding is immutable'); END
                """, """
                CREATE TRIGGER plan_occurrence_slot_no_delete BEFORE DELETE ON plan_occurrence_slot_bindings
                BEGIN SELECT RAISE(ABORT,'Plan occurrence slot binding is retained'); END
                """, """
                CREATE TRIGGER plan_occurrence_slot_authoritative BEFORE INSERT ON plan_occurrence_slot_bindings
                WHEN NOT EXISTS (
                    SELECT 1 FROM plan_tool_requirements requirement
                    WHERE requirement.task_id=NEW.task_id
                      AND requirement.plan_revision_id=NEW.plan_revision_id
                      AND requirement.step_revision_id=NEW.step_revision_id
                      AND requirement.requirement_id=NEW.requirement_id
                      AND requirement.selector_digest=NEW.selector_digest
                      AND NEW.slot_ordinal<=requirement.max_occurrences)
                  OR NOT EXISTS (
                    SELECT 1 FROM side_effect_occurrences occurrence
                    WHERE occurrence.occurrence_id=NEW.occurrence_id
                      AND occurrence.task_id=NEW.task_id AND occurrence.run_id=NEW.run_id
                      AND occurrence.tool_call_id=NEW.tool_call_id
                      AND occurrence.operation_type='TOOL'
                      AND occurrence.target=(SELECT requirement.tool_name
                          FROM plan_tool_requirements requirement
                          WHERE requirement.task_id=NEW.task_id
                            AND requirement.plan_revision_id=NEW.plan_revision_id
                            AND requirement.step_revision_id=NEW.step_revision_id
                            AND requirement.requirement_id=NEW.requirement_id)
                      AND occurrence.status='STARTED')
                BEGIN SELECT RAISE(ABORT,'Occurrence slot producer is not authoritative'); END
                """, """
                CREATE TRIGGER plan_evidence_slot_immutable BEFORE UPDATE ON plan_evidence_slot_bindings
                BEGIN SELECT RAISE(ABORT,'Plan Evidence slot binding is immutable'); END
                """, """
                CREATE TRIGGER plan_evidence_slot_no_delete BEFORE DELETE ON plan_evidence_slot_bindings
                BEGIN SELECT RAISE(ABORT,'Plan Evidence slot binding is retained'); END
                """, """
                CREATE TRIGGER plan_evidence_slot_authoritative BEFORE INSERT ON plan_evidence_slot_bindings
                WHEN NOT EXISTS (
                    SELECT 1 FROM side_effect_occurrences occurrence
                    WHERE occurrence.occurrence_id=NEW.occurrence_id
                      AND occurrence.task_id=NEW.task_id AND occurrence.run_id=NEW.run_id
                      AND occurrence.tool_call_id=NEW.tool_call_id
                      AND occurrence.status='COMPLETED')
                BEGIN SELECT RAISE(ABORT,'Evidence slot producer is not successful'); END
                """);
    }

    private static List<String> v23Statements() {
        List<String> statements = new ArrayList<>();
        statements.addAll(v23Definitions());
        statements.addAll(v23MutableAuthority());
        statements.addAll(v23Integrity());
        return List.copyOf(statements);
    }

    private static List<String> v23Definitions() {
        return List.of("""
                ADD_COLUMN_IF_TABLE_EXISTS approval_decisions intent_hash TEXT
                """, """
                CREATE TABLE steering_definitions (
                    steering_id TEXT PRIMARY KEY CHECK(length(steering_id)=36 AND substr(steering_id,1,4)='str_'
                        AND substr(steering_id,5) NOT GLOB '*[^0-9a-f]*' AND substr(steering_id,17,1)='4'
                        AND substr(steering_id,21,1) IN ('8','9','a','b')),
                    thread_id TEXT NOT NULL, task_id TEXT NOT NULL, target_run_id TEXT NOT NULL,
                    target_plan_revision_id TEXT, target_plan_hash TEXT, target_plan_pointer_version INTEGER,
                    target_generation_id TEXT NOT NULL, target_context_pointer_version INTEGER NOT NULL,
                    content TEXT NOT NULL CHECK(length(content) BETWEEN 1 AND 4096),
                    content_hash TEXT NOT NULL CHECK(length(content_hash)=64 AND content_hash NOT GLOB '*[^0-9a-f]*'),
                    provenance TEXT NOT NULL CHECK(provenance IN ('INTERACTIVE_EXPLICIT','INTERACTIVE_ACTIVE_TEXT')),
                    input_owner TEXT NOT NULL CHECK(length(trim(input_owner)) BETWEEN 1 AND 128),
                    input_session_id TEXT NOT NULL CHECK(length(trim(input_session_id)) BETWEEN 1 AND 128),
                    created_at TEXT NOT NULL, UNIQUE(task_id,steering_id), UNIQUE(thread_id,task_id,steering_id),
                    FOREIGN KEY(thread_id,task_id) REFERENCES task_identities(thread_id,task_id),
                    FOREIGN KEY(task_id,target_run_id) REFERENCES durable_runs(task_id,run_id),
                    FOREIGN KEY(task_id,target_generation_id) REFERENCES task_context_generations(task_id,generation_id),
                    FOREIGN KEY(task_id,target_plan_revision_id) REFERENCES plan_revisions(task_id,plan_revision_id),
                    CHECK((target_plan_revision_id IS NULL AND target_plan_hash IS NULL AND target_plan_pointer_version IS NULL)
                       OR (target_plan_revision_id IS NOT NULL AND length(target_plan_hash)=64 AND target_plan_pointer_version>=0)))
                """, """
                CREATE TABLE steering_classification_facts (
                    steering_id TEXT NOT NULL, fact_number INTEGER NOT NULL CHECK(fact_number>=1), task_id TEXT NOT NULL,
                    classification TEXT NOT NULL CHECK(classification IN ('NON_MATERIAL','MATERIAL','UNKNOWN','REJECT')),
                    outcome TEXT NOT NULL CHECK(outcome IN ('ACCEPT','ACCEPT_MATERIAL','NEEDS_CONFIRMATION','REJECT')),
                    classifier_version TEXT NOT NULL, reason_code TEXT NOT NULL CHECK(length(reason_code) BETWEEN 1 AND 64),
                    created_at TEXT NOT NULL, PRIMARY KEY(steering_id,fact_number),
                    FOREIGN KEY(task_id,steering_id) REFERENCES steering_definitions(task_id,steering_id),
                    CHECK((classification='NON_MATERIAL' AND outcome='ACCEPT') OR (classification='MATERIAL' AND outcome='ACCEPT_MATERIAL')
                       OR (classification='UNKNOWN' AND outcome='NEEDS_CONFIRMATION') OR (classification='REJECT' AND outcome='REJECT')))
                """, """
                CREATE TABLE steering_confirmation_facts (
                    steering_id TEXT NOT NULL, confirmation_number INTEGER NOT NULL CHECK(confirmation_number>=1),
                    task_id TEXT NOT NULL, target_run_id TEXT NOT NULL, input_owner TEXT NOT NULL, input_session_id TEXT NOT NULL,
                    decision TEXT NOT NULL CHECK(decision IN ('CONFIRM_NON_MATERIAL','CONFIRM_MATERIAL','REJECT','CANCEL')),
                    confirmed_at TEXT NOT NULL, PRIMARY KEY(steering_id,confirmation_number),
                    FOREIGN KEY(task_id,steering_id) REFERENCES steering_definitions(task_id,steering_id),
                    FOREIGN KEY(task_id,target_run_id) REFERENCES durable_runs(task_id,run_id))
                """);
    }

    private static List<String> v23MutableAuthority() {
        return List.of("""
                CREATE TABLE steering_queue_authority (
                    steering_id TEXT PRIMARY KEY, task_id TEXT NOT NULL, target_run_id TEXT NOT NULL,
                    target_plan_revision_id TEXT, target_plan_hash TEXT, target_plan_pointer_version INTEGER,
                    target_generation_id TEXT NOT NULL, target_context_pointer_version INTEGER NOT NULL,
                    state TEXT NOT NULL CHECK(state IN ('CANDIDATE','NEEDS_CONFIRMATION','QUEUED','DEFERRED','APPLYING','APPLIED','REJECTED','CANCELLED')),
                    queue_sequence INTEGER UNIQUE, queue_version INTEGER NOT NULL DEFAULT 0 CHECK(queue_version>=0),
                    reason_code TEXT CHECK(reason_code IS NULL OR length(reason_code)<=64), updated_at TEXT NOT NULL,
                    FOREIGN KEY(task_id,steering_id) REFERENCES steering_definitions(task_id,steering_id),
                    FOREIGN KEY(task_id,target_run_id) REFERENCES durable_runs(task_id,run_id),
                    FOREIGN KEY(task_id,target_generation_id) REFERENCES task_context_generations(task_id,generation_id),
                    FOREIGN KEY(task_id,target_plan_revision_id) REFERENCES plan_revisions(task_id,plan_revision_id),
                    CHECK((state IN ('QUEUED','DEFERRED','APPLYING','APPLIED') AND queue_sequence IS NOT NULL)
                       OR (state IN ('CANDIDATE','NEEDS_CONFIRMATION','REJECTED','CANCELLED'))))
                """, """
                CREATE TABLE steering_state_facts (
                    steering_id TEXT NOT NULL, state_number INTEGER NOT NULL CHECK(state_number>=1), task_id TEXT NOT NULL,
                    target_run_id TEXT NOT NULL, queue_version INTEGER NOT NULL,
                    state TEXT NOT NULL CHECK(state IN ('CANDIDATE','NEEDS_CONFIRMATION','QUEUED','DEFERRED','APPLYING','APPLIED','REJECTED','CANCELLED')),
                    reason_code TEXT, recorded_at TEXT NOT NULL, PRIMARY KEY(steering_id,state_number),
                    FOREIGN KEY(task_id,steering_id) REFERENCES steering_definitions(task_id,steering_id),
                    FOREIGN KEY(task_id,target_run_id) REFERENCES durable_runs(task_id,run_id))
                """, """
                CREATE TABLE steering_application_facts (
                    steering_id TEXT PRIMARY KEY, task_id TEXT NOT NULL, applied_by_run_id TEXT NOT NULL,
                    queue_version INTEGER NOT NULL, safe_point TEXT NOT NULL,
                    application_kind TEXT NOT NULL CHECK(application_kind IN ('CONSTRAINT_REVISION','PLAN_DRAFT','PLAN_DISABLED_CONSTRAINT')),
                    constraint_revision_id TEXT, plan_revision_id TEXT,
                    application_hash TEXT NOT NULL CHECK(length(application_hash)=64 AND application_hash NOT GLOB '*[^0-9a-f]*'), applied_at TEXT NOT NULL,
                    FOREIGN KEY(task_id,steering_id) REFERENCES steering_definitions(task_id,steering_id),
                    FOREIGN KEY(task_id,applied_by_run_id) REFERENCES durable_runs(task_id,run_id),
                    FOREIGN KEY(task_id,constraint_revision_id) REFERENCES constraint_revisions(task_id,constraint_revision_id),
                    FOREIGN KEY(task_id,plan_revision_id) REFERENCES plan_revisions(task_id,plan_revision_id),
                    CHECK((application_kind IN ('CONSTRAINT_REVISION','PLAN_DISABLED_CONSTRAINT') AND constraint_revision_id IS NOT NULL AND plan_revision_id IS NULL)
                       OR (application_kind='PLAN_DRAFT' AND constraint_revision_id IS NULL AND plan_revision_id IS NOT NULL)))
                """, """
                CREATE TABLE steering_constraint_provenance (
                    steering_id TEXT PRIMARY KEY, task_id TEXT NOT NULL, constraint_revision_id TEXT NOT NULL,
                    parent_constraint_revision_id TEXT, target_plan_revision_id TEXT, canonical_hash TEXT NOT NULL, created_at TEXT NOT NULL,
                    FOREIGN KEY(task_id,steering_id) REFERENCES steering_definitions(task_id,steering_id),
                    FOREIGN KEY(task_id,constraint_revision_id) REFERENCES constraint_revisions(task_id,constraint_revision_id),
                    FOREIGN KEY(task_id,parent_constraint_revision_id) REFERENCES constraint_revisions(task_id,constraint_revision_id),
                    FOREIGN KEY(task_id,target_plan_revision_id) REFERENCES plan_revisions(task_id,plan_revision_id))
                """, """
                CREATE TABLE steering_approval_conflict_facts (
                    steering_id TEXT NOT NULL, conflict_number INTEGER NOT NULL CHECK(conflict_number>=1),
                    task_id TEXT NOT NULL, approval_reference TEXT NOT NULL CHECK(length(approval_reference) BETWEEN 1 AND 128),
                    conflict_type TEXT NOT NULL CHECK(conflict_type IN ('PENDING_INVALIDATED','APPROVED_EFFECT_BLOCKED')),
                    recorded_at TEXT NOT NULL, PRIMARY KEY(steering_id,conflict_number),
                    FOREIGN KEY(task_id,steering_id) REFERENCES steering_definitions(task_id,steering_id))
                """, """
                CREATE TABLE steering_producer_pause_facts (
                    occurrence_id TEXT PRIMARY KEY, steering_id TEXT NOT NULL,
                    task_id TEXT NOT NULL, run_id TEXT NOT NULL,
                    producer_kind TEXT NOT NULL CHECK(producer_kind IN ('HOOK','VERIFICATION')),
                    producer_identity TEXT NOT NULL CHECK(length(trim(producer_identity)) BETWEEN 1 AND 128),
                    reason_code TEXT NOT NULL CHECK(reason_code='PAUSED_BY_STEERING'),
                    recorded_at TEXT NOT NULL,
                    UNIQUE(producer_kind,producer_identity),
                    FOREIGN KEY(task_id,steering_id) REFERENCES steering_definitions(task_id,steering_id),
                    FOREIGN KEY(occurrence_id) REFERENCES side_effect_occurrences(occurrence_id),
                    FOREIGN KEY(task_id,run_id) REFERENCES durable_runs(task_id,run_id))
                """);
    }

    private static List<String> v23Integrity() {
        return List.of(
                "CREATE INDEX idx_r1d_steering_task_created ON steering_definitions(task_id,created_at)",
                "CREATE INDEX idx_r1d_steering_queue_order ON steering_queue_authority(task_id,queue_sequence)",
                "CREATE INDEX idx_r1d_steering_queue_run ON steering_queue_authority(task_id,target_run_id,state)",
                "CREATE TRIGGER r1d_steering_definition_immutable BEFORE UPDATE ON steering_definitions BEGIN SELECT RAISE(ABORT,'Steering definitions are immutable'); END",
                "CREATE TRIGGER r1d_steering_definition_no_delete BEFORE DELETE ON steering_definitions BEGIN SELECT RAISE(ABORT,'Steering definitions are retained'); END",
                "CREATE TRIGGER r1d_steering_classification_immutable BEFORE UPDATE ON steering_classification_facts BEGIN SELECT RAISE(ABORT,'Steering classification facts are immutable'); END",
                "CREATE TRIGGER r1d_steering_confirmation_immutable BEFORE UPDATE ON steering_confirmation_facts BEGIN SELECT RAISE(ABORT,'Steering confirmation facts are immutable'); END",
                "CREATE TRIGGER r1d_steering_state_fact_immutable BEFORE UPDATE ON steering_state_facts BEGIN SELECT RAISE(ABORT,'Steering state facts are immutable'); END",
                "CREATE TRIGGER r1d_steering_application_immutable BEFORE UPDATE ON steering_application_facts BEGIN SELECT RAISE(ABORT,'Steering application facts are immutable'); END",
                "CREATE TRIGGER r1d_steering_classification_no_delete BEFORE DELETE ON steering_classification_facts BEGIN SELECT RAISE(ABORT,'Steering classification facts are retained'); END",
                "CREATE TRIGGER r1d_steering_confirmation_no_delete BEFORE DELETE ON steering_confirmation_facts BEGIN SELECT RAISE(ABORT,'Steering confirmation facts are retained'); END",
                "CREATE TRIGGER r1d_steering_state_fact_no_delete BEFORE DELETE ON steering_state_facts BEGIN SELECT RAISE(ABORT,'Steering state facts are retained'); END",
                "CREATE TRIGGER r1d_steering_application_no_delete BEFORE DELETE ON steering_application_facts BEGIN SELECT RAISE(ABORT,'Steering application facts are retained'); END",
                "CREATE TRIGGER r1d_steering_constraint_provenance_immutable BEFORE UPDATE ON steering_constraint_provenance BEGIN SELECT RAISE(ABORT,'Steering Constraint provenance is immutable'); END",
                "CREATE TRIGGER r1d_steering_constraint_provenance_no_delete BEFORE DELETE ON steering_constraint_provenance BEGIN SELECT RAISE(ABORT,'Steering Constraint provenance is retained'); END",
                "CREATE TRIGGER r1d_steering_approval_conflict_immutable BEFORE UPDATE ON steering_approval_conflict_facts BEGIN SELECT RAISE(ABORT,'Steering approval conflict facts are immutable'); END",
                "CREATE TRIGGER r1d_steering_approval_conflict_no_delete BEFORE DELETE ON steering_approval_conflict_facts BEGIN SELECT RAISE(ABORT,'Steering approval conflict facts are retained'); END",
                "CREATE TRIGGER r1d_steering_producer_pause_immutable BEFORE UPDATE ON steering_producer_pause_facts BEGIN SELECT RAISE(ABORT,'Steering producer pause facts are immutable'); END",
                "CREATE TRIGGER r1d_steering_producer_pause_no_delete BEFORE DELETE ON steering_producer_pause_facts BEGIN SELECT RAISE(ABORT,'Steering producer pause facts are retained'); END",
                "CREATE TRIGGER r1d_steering_terminal_immutable BEFORE UPDATE ON steering_queue_authority WHEN OLD.state IN ('APPLIED','REJECTED','CANCELLED') BEGIN SELECT RAISE(ABORT,'Steering terminal state is immutable'); END",
                """
                CREATE TRIGGER r1d_steering_queue_transition BEFORE UPDATE OF state ON steering_queue_authority
                WHEN NOT ((OLD.state='CANDIDATE' AND NEW.state IN ('NEEDS_CONFIRMATION','QUEUED','REJECTED','CANCELLED'))
                    OR (OLD.state='NEEDS_CONFIRMATION' AND NEW.state IN ('QUEUED','REJECTED','CANCELLED'))
                    OR (OLD.state='QUEUED' AND NEW.state='QUEUED' AND NEW.target_run_id<>OLD.target_run_id)
                    OR (OLD.state='QUEUED' AND NEW.state IN ('DEFERRED','APPLYING','REJECTED','CANCELLED'))
                    OR (OLD.state='DEFERRED' AND NEW.state IN ('QUEUED','REJECTED','CANCELLED'))
                    OR (OLD.state='APPLYING' AND NEW.state IN ('APPLIED','QUEUED','DEFERRED','REJECTED')))
                BEGIN SELECT RAISE(ABORT,'Invalid Steering state transition'); END
                """);
    }

    private static List<String> v20Statements() {
        return List.of("""
                CREATE TABLE plan_revisions (
                    plan_revision_id TEXT PRIMARY KEY CHECK(length(plan_revision_id)=36 AND substr(plan_revision_id,1,4)='pln_'
                        AND substr(plan_revision_id,5) NOT GLOB '*[^0-9a-f]*' AND substr(plan_revision_id,17,1)='4'
                        AND substr(plan_revision_id,21,1) IN ('8','9','a','b')),
                    task_id TEXT NOT NULL, revision_number INTEGER NOT NULL CHECK(revision_number>=1),
                    goal TEXT NOT NULL CHECK(length(trim(goal))>0), acceptance_criteria TEXT NOT NULL,
                    assumptions TEXT NOT NULL, risks TEXT NOT NULL, verification_strategy TEXT NOT NULL,
                    canonical_hash TEXT NOT NULL CHECK(length(canonical_hash)=64 AND canonical_hash NOT GLOB '*[^0-9a-f]*'),
                    source_type TEXT NOT NULL CHECK(length(trim(source_type))>0),
                    provenance TEXT NOT NULL CHECK(length(trim(provenance))>0),
                    created_by_run_id TEXT NOT NULL, created_at TEXT NOT NULL,
                    UNIQUE(task_id,plan_revision_id), UNIQUE(task_id,revision_number), UNIQUE(task_id,canonical_hash),
                    FOREIGN KEY(task_id) REFERENCES task_identities(task_id),
                    FOREIGN KEY(task_id,created_by_run_id) REFERENCES durable_runs(task_id,run_id)
                )
                """, """
                CREATE TABLE plan_revision_lifecycle (
                    plan_revision_id TEXT PRIMARY KEY, task_id TEXT NOT NULL,
                    state TEXT NOT NULL CHECK(state IN ('DRAFT','VALIDATED','APPROVED','REJECTED','SUPERSEDED')),
                    cas_version INTEGER NOT NULL DEFAULT 0 CHECK(cas_version>=0),
                    updated_by_run_id TEXT NOT NULL, updated_at TEXT NOT NULL,
                    UNIQUE(task_id,plan_revision_id,state),
                    FOREIGN KEY(task_id,plan_revision_id) REFERENCES plan_revisions(task_id,plan_revision_id),
                    FOREIGN KEY(task_id,updated_by_run_id) REFERENCES durable_runs(task_id,run_id)
                )
                """, """
                CREATE TABLE plan_step_definitions (
                    step_revision_id TEXT PRIMARY KEY CHECK(length(step_revision_id)=36 AND substr(step_revision_id,1,4)='stp_'
                        AND substr(step_revision_id,5) NOT GLOB '*[^0-9a-f]*' AND substr(step_revision_id,17,1)='4'
                        AND substr(step_revision_id,21,1) IN ('8','9','a','b')),
                    logical_step_id TEXT NOT NULL CHECK(length(logical_step_id)=36 AND substr(logical_step_id,1,4)='stp_'
                        AND substr(logical_step_id,5) NOT GLOB '*[^0-9a-f]*' AND substr(logical_step_id,17,1)='4'
                        AND substr(logical_step_id,21,1) IN ('8','9','a','b')),
                    plan_revision_id TEXT NOT NULL, task_id TEXT NOT NULL,
                    step_order INTEGER NOT NULL CHECK(step_order>=0), title TEXT NOT NULL CHECK(length(trim(title))>0),
                    definition TEXT NOT NULL CHECK(length(trim(definition))>0),
                    canonical_hash TEXT NOT NULL CHECK(length(canonical_hash)=64 AND canonical_hash NOT GLOB '*[^0-9a-f]*'),
                    UNIQUE(task_id,plan_revision_id,step_revision_id), UNIQUE(plan_revision_id,step_order),
                    UNIQUE(plan_revision_id,logical_step_id),
                    FOREIGN KEY(task_id,plan_revision_id) REFERENCES plan_revisions(task_id,plan_revision_id)
                )
                """, """
                CREATE TABLE plan_step_dependencies (
                    task_id TEXT NOT NULL, plan_revision_id TEXT NOT NULL,
                    step_revision_id TEXT NOT NULL, depends_on_step_revision_id TEXT NOT NULL,
                    PRIMARY KEY(plan_revision_id,step_revision_id,depends_on_step_revision_id),
                    FOREIGN KEY(task_id,plan_revision_id,step_revision_id)
                        REFERENCES plan_step_definitions(task_id,plan_revision_id,step_revision_id),
                    FOREIGN KEY(task_id,plan_revision_id,depends_on_step_revision_id)
                        REFERENCES plan_step_definitions(task_id,plan_revision_id,step_revision_id),
                    CHECK(step_revision_id<>depends_on_step_revision_id)
                )
                """, """
                CREATE TABLE plan_step_lineage (
                    lineage_id TEXT PRIMARY KEY, task_id TEXT NOT NULL,
                    source_plan_revision_id TEXT, source_step_revision_id TEXT,
                    target_plan_revision_id TEXT NOT NULL, target_step_revision_id TEXT,
                    relationship TEXT NOT NULL CHECK(relationship IN ('ADDED','PRESERVED','REPLACED','SPLIT','MERGED','REMOVED')),
                    provenance TEXT NOT NULL CHECK(length(trim(provenance))>0),
                    created_by_run_id TEXT NOT NULL, created_at TEXT NOT NULL,
                    FOREIGN KEY(task_id,source_plan_revision_id,source_step_revision_id)
                        REFERENCES plan_step_definitions(task_id,plan_revision_id,step_revision_id),
                    FOREIGN KEY(task_id,target_plan_revision_id,target_step_revision_id)
                        REFERENCES plan_step_definitions(task_id,plan_revision_id,step_revision_id),
                    FOREIGN KEY(task_id,target_plan_revision_id)
                        REFERENCES plan_revisions(task_id,plan_revision_id),
                    FOREIGN KEY(task_id,created_by_run_id) REFERENCES durable_runs(task_id,run_id),
                    CHECK(source_step_revision_id IS NOT target_step_revision_id),
                    CHECK((relationship='ADDED' AND source_step_revision_id IS NULL AND target_step_revision_id IS NOT NULL)
                       OR (relationship='REMOVED' AND source_step_revision_id IS NOT NULL AND target_step_revision_id IS NULL)
                       OR (relationship IN ('PRESERVED','REPLACED','SPLIT','MERGED')
                           AND source_step_revision_id IS NOT NULL AND target_step_revision_id IS NOT NULL))
                )
                """, """
                CREATE TABLE plan_approvals (
                    approval_id TEXT PRIMARY KEY, task_id TEXT NOT NULL, plan_revision_id TEXT NOT NULL,
                    decision TEXT NOT NULL CHECK(decision IN ('APPROVED','REJECTED')),
                    approved_by TEXT NOT NULL CHECK(length(trim(approved_by))>0), approved_by_run_id TEXT NOT NULL,
                    policy_version TEXT NOT NULL CHECK(length(trim(policy_version))>0), reason TEXT, decided_at TEXT NOT NULL,
                    UNIQUE(task_id,plan_revision_id,approval_id),
                    UNIQUE(task_id,plan_revision_id,approval_id,decision),
                    FOREIGN KEY(task_id,plan_revision_id) REFERENCES plan_revisions(task_id,plan_revision_id),
                    FOREIGN KEY(task_id,approved_by_run_id) REFERENCES durable_runs(task_id,run_id)
                )
                """, """
                CREATE TABLE task_plan_authorities (
                    task_id TEXT PRIMARY KEY, active_plan_revision_id TEXT NOT NULL,
                    active_revision_state TEXT NOT NULL CHECK(active_revision_state='APPROVED'),
                    active_approval_id TEXT NOT NULL,
                    active_approval_decision TEXT NOT NULL CHECK(active_approval_decision='APPROVED'),
                    activated_by_run_id TEXT NOT NULL, pointer_version INTEGER NOT NULL DEFAULT 0 CHECK(pointer_version>=0),
                    activated_at TEXT NOT NULL,
                    FOREIGN KEY(task_id) REFERENCES task_identities(task_id),
                    FOREIGN KEY(task_id,active_plan_revision_id) REFERENCES plan_revisions(task_id,plan_revision_id),
                    FOREIGN KEY(task_id,active_plan_revision_id,active_revision_state)
                        REFERENCES plan_revision_lifecycle(task_id,plan_revision_id,state),
                    FOREIGN KEY(task_id,active_plan_revision_id,active_approval_id,active_approval_decision)
                        REFERENCES plan_approvals(task_id,plan_revision_id,approval_id,decision),
                    FOREIGN KEY(task_id,activated_by_run_id) REFERENCES durable_runs(task_id,run_id)
                )
                """, """
                CREATE TABLE task_plan_applicability (
                    task_id TEXT PRIMARY KEY,
                    applicability TEXT NOT NULL CHECK(applicability IN
                        ('PLAN_REQUIRED','PLAN_DISABLED','LEGACY_PLAN_ONLY')),
                    declared_by_run_id TEXT NOT NULL, declared_at TEXT NOT NULL,
                    FOREIGN KEY(task_id) REFERENCES task_identities(task_id),
                    FOREIGN KEY(task_id,declared_by_run_id) REFERENCES durable_runs(task_id,run_id)
                )
                """, """
                CREATE TABLE plan_run_bindings (
                    binding_id TEXT PRIMARY KEY, task_id TEXT NOT NULL, run_id TEXT NOT NULL,
                    plan_revision_id TEXT NOT NULL, approval_id TEXT NOT NULL,
                    approval_decision TEXT NOT NULL CHECK(approval_decision='APPROVED'),
                    plan_hash TEXT NOT NULL CHECK(length(plan_hash)=64 AND plan_hash NOT GLOB '*[^0-9a-f]*'),
                    bound_at TEXT NOT NULL,
                    FOREIGN KEY(task_id,run_id) REFERENCES durable_runs(task_id,run_id),
                    FOREIGN KEY(task_id,plan_revision_id) REFERENCES plan_revisions(task_id,plan_revision_id),
                    FOREIGN KEY(task_id,plan_revision_id,approval_id,approval_decision)
                        REFERENCES plan_approvals(task_id,plan_revision_id,approval_id,decision)
                    ,UNIQUE(binding_id,task_id,run_id)
                )
                """, "CREATE INDEX idx_r1c_plan_revisions_task_number ON plan_revisions(task_id,revision_number)",
                "CREATE INDEX idx_r1c_plan_steps_revision_order ON plan_step_definitions(plan_revision_id,step_order)",
                "CREATE INDEX idx_r1c_plan_lineage_target ON plan_step_lineage(task_id,target_plan_revision_id,target_step_revision_id)",
                "CREATE INDEX idx_r1c_plan_approvals_revision ON plan_approvals(task_id,plan_revision_id,decided_at)",
                "CREATE INDEX idx_r1c_plan_bindings_revision ON plan_run_bindings(task_id,plan_revision_id)", """
                CREATE TRIGGER r1c_plan_revision_immutable BEFORE UPDATE ON plan_revisions
                BEGIN SELECT RAISE(ABORT,'plan revisions are immutable'); END
                """, """
                CREATE TRIGGER r1c_plan_revision_no_delete BEFORE DELETE ON plan_revisions
                BEGIN SELECT RAISE(ABORT,'plan revisions are retained'); END
                """, """
                CREATE TRIGGER r1c_plan_step_immutable BEFORE UPDATE ON plan_step_definitions
                BEGIN SELECT RAISE(ABORT,'plan step definitions are immutable'); END
                """, """
                CREATE TRIGGER r1c_plan_step_no_delete BEFORE DELETE ON plan_step_definitions
                BEGIN SELECT RAISE(ABORT,'plan step definitions are retained'); END
                """, """
                CREATE TRIGGER r1c_plan_dependency_immutable BEFORE UPDATE ON plan_step_dependencies
                BEGIN SELECT RAISE(ABORT,'plan dependencies are immutable'); END
                """, """
                CREATE TRIGGER r1c_plan_dependency_no_delete BEFORE DELETE ON plan_step_dependencies
                BEGIN SELECT RAISE(ABORT,'plan dependencies are retained'); END
                """, """
                CREATE TRIGGER r1c_plan_lineage_immutable BEFORE UPDATE ON plan_step_lineage
                BEGIN SELECT RAISE(ABORT,'plan lineage is immutable'); END
                """, """
                CREATE TRIGGER r1c_plan_lineage_no_delete BEFORE DELETE ON plan_step_lineage
                BEGIN SELECT RAISE(ABORT,'plan lineage is retained'); END
                """, """
                CREATE TRIGGER r1c_plan_approval_immutable BEFORE UPDATE ON plan_approvals
                BEGIN SELECT RAISE(ABORT,'plan approvals are immutable'); END
                """, """
                CREATE TRIGGER r1c_plan_approval_no_delete BEFORE DELETE ON plan_approvals
                BEGIN SELECT RAISE(ABORT,'plan approvals are retained'); END
                """, """
                CREATE TRIGGER r1c_plan_binding_immutable BEFORE UPDATE ON plan_run_bindings
                BEGIN SELECT RAISE(ABORT,'plan run bindings are immutable'); END
                """, """
                CREATE TRIGGER r1c_plan_binding_no_delete BEFORE DELETE ON plan_run_bindings
                BEGIN SELECT RAISE(ABORT,'plan run bindings are retained'); END
                """, """
                CREATE TRIGGER r1c_plan_applicability_immutable
                BEFORE UPDATE ON task_plan_applicability
                BEGIN SELECT RAISE(ABORT,'task plan applicability is immutable'); END
                """, """
                CREATE TRIGGER r1c_plan_applicability_no_delete
                BEFORE DELETE ON task_plan_applicability
                BEGIN SELECT RAISE(ABORT,'task plan applicability is retained'); END
                """);
    }

    private static List<String> v21Statements() {
        return List.of(
                """
                CREATE TABLE plan_step_executions (
                    task_id TEXT NOT NULL, plan_revision_id TEXT NOT NULL,
                    step_revision_id TEXT NOT NULL,
                    state TEXT NOT NULL CHECK(state IN
                        ('PENDING','READY','RUNNING','SUCCEEDED','FAILED','BLOCKED','CANCELLED','SKIPPED')),
                    transition_version INTEGER NOT NULL DEFAULT 0 CHECK(transition_version>=0),
                    current_attempt_number INTEGER NOT NULL DEFAULT 0 CHECK(current_attempt_number>=0),
                    initialized_by_run_id TEXT NOT NULL, last_updated_by_run_id TEXT NOT NULL,
                    initialized_at TEXT NOT NULL, updated_at TEXT NOT NULL,
                    started_at TEXT, finished_at TEXT,
                    bounded_reason TEXT CHECK(bounded_reason IS NULL OR length(bounded_reason)<=512),
                    provenance TEXT NOT NULL CHECK(length(trim(provenance))>0),
                    PRIMARY KEY(task_id,plan_revision_id,step_revision_id),
                    FOREIGN KEY(task_id,plan_revision_id,step_revision_id)
                        REFERENCES plan_step_definitions(task_id,plan_revision_id,step_revision_id),
                    FOREIGN KEY(task_id,initialized_by_run_id) REFERENCES durable_runs(task_id,run_id),
                    FOREIGN KEY(task_id,last_updated_by_run_id) REFERENCES durable_runs(task_id,run_id)
                )
                """, """
                CREATE TABLE plan_step_attempts (
                    task_id TEXT NOT NULL, plan_revision_id TEXT NOT NULL,
                    step_revision_id TEXT NOT NULL, run_id TEXT NOT NULL,
                    attempt_number INTEGER NOT NULL CHECK(attempt_number>=1),
                    state TEXT NOT NULL CHECK(state IN
                        ('RUNNING','SUCCEEDED','FAILED','BLOCKED','CANCELLED','UNKNOWN')),
                    transition_version INTEGER NOT NULL DEFAULT 0 CHECK(transition_version>=0),
                    start_iteration INTEGER NOT NULL CHECK(start_iteration>=0),
                    end_iteration INTEGER CHECK(end_iteration IS NULL OR end_iteration>=start_iteration),
                    started_at TEXT NOT NULL, finished_at TEXT,
                    failure_class TEXT CHECK(failure_class IS NULL OR length(failure_class)<=64),
                    bounded_reason TEXT CHECK(bounded_reason IS NULL OR length(bounded_reason)<=512),
                    provenance TEXT NOT NULL CHECK(length(trim(provenance))>0),
                    PRIMARY KEY(task_id,plan_revision_id,step_revision_id,run_id,attempt_number),
                    UNIQUE(task_id,plan_revision_id,step_revision_id,attempt_number),
                    FOREIGN KEY(task_id,plan_revision_id,step_revision_id)
                        REFERENCES plan_step_definitions(task_id,plan_revision_id,step_revision_id),
                    FOREIGN KEY(task_id,run_id) REFERENCES durable_runs(task_id,run_id)
                )
                """,
                "CREATE INDEX idx_r1c_step_execution_revision_state "
                        + "ON plan_step_executions(task_id,plan_revision_id,state)",
                "CREATE INDEX idx_r1c_step_attempt_history "
                        + "ON plan_step_attempts(task_id,plan_revision_id,step_revision_id,attempt_number)",
                "CREATE UNIQUE INDEX idx_r1c_step_attempt_one_running "
                        + "ON plan_step_attempts(task_id,plan_revision_id,step_revision_id) "
                        + "WHERE state='RUNNING'",
                """
                CREATE TRIGGER r1c_step_execution_requires_binding
                BEFORE INSERT ON plan_step_executions
                WHEN NOT EXISTS (
                    SELECT 1 FROM plan_run_bindings b
                    WHERE b.task_id=NEW.task_id
                      AND b.run_id=NEW.initialized_by_run_id
                      AND b.plan_revision_id=NEW.plan_revision_id
                      AND b.approval_decision='APPROVED')
                BEGIN SELECT RAISE(ABORT,'approved Plan Run binding is required'); END
                """, """
                CREATE TRIGGER r1c_step_attempt_requires_binding
                BEFORE INSERT ON plan_step_attempts
                WHEN NOT EXISTS (
                    SELECT 1 FROM plan_run_bindings b
                    WHERE b.task_id=NEW.task_id AND b.run_id=NEW.run_id
                      AND b.plan_revision_id=NEW.plan_revision_id
                      AND b.approval_decision='APPROVED')
                BEGIN SELECT RAISE(ABORT,'approved Plan Run binding is required'); END
                """,
                v21StepTransitionTrigger(), """
                CREATE TRIGGER r1c_step_execution_identity_immutable
                BEFORE UPDATE ON plan_step_executions
                WHEN NEW.task_id<>OLD.task_id
                  OR NEW.plan_revision_id<>OLD.plan_revision_id
                  OR NEW.step_revision_id<>OLD.step_revision_id
                  OR NEW.initialized_by_run_id<>OLD.initialized_by_run_id
                  OR NEW.initialized_at<>OLD.initialized_at
                  OR NEW.provenance<>OLD.provenance
                BEGIN SELECT RAISE(ABORT,'plan step execution identity is immutable'); END
                """, """
                CREATE TRIGGER r1c_step_execution_no_delete
                BEFORE DELETE ON plan_step_executions
                BEGIN SELECT RAISE(ABORT,'plan step execution state is retained'); END
                """, """
                CREATE TRIGGER r1c_step_execution_terminal_immutable
                BEFORE UPDATE ON plan_step_executions
                WHEN OLD.state IN ('SUCCEEDED','FAILED','BLOCKED','CANCELLED','SKIPPED')
                BEGIN SELECT RAISE(ABORT,'terminal plan step execution state is immutable'); END
                """, v21AttemptTransitionTrigger(), """
                CREATE TRIGGER r1c_step_attempt_identity_immutable
                BEFORE UPDATE ON plan_step_attempts
                WHEN NEW.task_id<>OLD.task_id
                  OR NEW.plan_revision_id<>OLD.plan_revision_id
                  OR NEW.step_revision_id<>OLD.step_revision_id
                  OR NEW.run_id<>OLD.run_id
                  OR NEW.attempt_number<>OLD.attempt_number
                  OR NEW.start_iteration<>OLD.start_iteration
                  OR NEW.started_at<>OLD.started_at
                  OR NEW.provenance<>OLD.provenance
                BEGIN SELECT RAISE(ABORT,'plan step attempt identity is immutable'); END
                """, """
                CREATE TRIGGER r1c_step_attempt_terminal_immutable
                BEFORE UPDATE ON plan_step_attempts
                WHEN OLD.state<>'RUNNING'
                BEGIN SELECT RAISE(ABORT,'terminal plan step attempts are immutable'); END
                """, """
                CREATE TRIGGER r1c_step_attempt_no_delete BEFORE DELETE ON plan_step_attempts
                BEGIN SELECT RAISE(ABORT,'plan step attempts are retained'); END
                """);
    }

    private static String v21StepTransitionTrigger() {
        return """
                CREATE TRIGGER r1c_step_execution_transition
                BEFORE UPDATE OF state ON plan_step_executions
                WHEN NOT (
                    (OLD.state='PENDING' AND NEW.state IN ('READY','BLOCKED','CANCELLED','SKIPPED'))
                    OR (OLD.state='READY' AND NEW.state IN ('RUNNING','BLOCKED','CANCELLED','SKIPPED'))
                    OR (OLD.state='RUNNING' AND NEW.state IN
                        ('READY','SUCCEEDED','FAILED','BLOCKED','CANCELLED'))
                )
                BEGIN SELECT RAISE(ABORT,'invalid plan step execution transition'); END
                """;
    }

    private static String v21AttemptTransitionTrigger() {
        return """
                CREATE TRIGGER r1c_step_attempt_transition BEFORE UPDATE OF state ON plan_step_attempts
                WHEN OLD.state<>'RUNNING' OR NEW.state NOT IN
                    ('SUCCEEDED','FAILED','BLOCKED','CANCELLED','UNKNOWN')
                BEGIN SELECT RAISE(ABORT,'invalid plan step attempt transition'); END
                """;
    }

    private static List<String> v22Statements() {
        return List.of(
                "ADD_COLUMN_IF_TABLE_EXISTS plan_revisions canonical_schema_version INTEGER NOT NULL DEFAULT 1 CHECK(canonical_schema_version IN (1,2))",
                "ADD_COLUMN_IF_TABLE_EXISTS plan_step_definitions completion_rule TEXT",
                "ADD_COLUMN_IF_TABLE_EXISTS plan_step_definitions required_evidence_kinds TEXT",
                "ADD_COLUMN_IF_TABLE_EXISTS plan_step_definitions effect_class TEXT",
                "ADD_COLUMN_IF_TABLE_EXISTS file_snapshots owner_run_id TEXT",
                """
                CREATE TABLE plan_evidence (
                    task_id TEXT NOT NULL, plan_revision_id TEXT NOT NULL,
                    step_revision_id TEXT NOT NULL, run_id TEXT NOT NULL,
                    attempt_number INTEGER NOT NULL CHECK(attempt_number>=1),
                    evidence_kind TEXT NOT NULL CHECK(evidence_kind IN
                        ('READ_EVIDENCE','TOOL_CALL','APPLIED_WRITE','SNAPSHOT',
                         'VERIFICATION_RESULT','USER_APPROVAL','GENERATED_ARTIFACT',
                         'MANUAL_CONFIRMATION')),
                    producer_kind TEXT NOT NULL CHECK(producer_kind IN
                        ('OCCURRENCE','SNAPSHOT','VERIFICATION','APPROVAL')),
                    producer_identity TEXT NOT NULL CHECK(length(trim(producer_identity))>0),
                    observed_status TEXT NOT NULL CHECK(observed_status IN
                        ('SUCCEEDED','FAILED','UNKNOWN')),
                    bounded_summary TEXT CHECK(bounded_summary IS NULL OR length(bounded_summary)<=1024),
                    protected_reference TEXT CHECK(protected_reference IS NULL OR length(protected_reference)<=512),
                    canonical_hash TEXT NOT NULL CHECK(length(canonical_hash)=64
                        AND canonical_hash NOT GLOB '*[^0-9a-f]*'),
                    provenance TEXT NOT NULL CHECK(length(trim(provenance))>0),
                    created_at TEXT NOT NULL,
                    PRIMARY KEY(task_id,plan_revision_id,step_revision_id,run_id,
                        attempt_number,evidence_kind,producer_kind,producer_identity),
                    FOREIGN KEY(task_id,plan_revision_id,step_revision_id,run_id,attempt_number)
                        REFERENCES plan_step_attempts(task_id,plan_revision_id,
                            step_revision_id,run_id,attempt_number)
                )
                """, """
                CREATE TABLE plan_verification_evidence (
                    task_id TEXT NOT NULL, plan_revision_id TEXT NOT NULL,
                    step_revision_id TEXT NOT NULL, attempt_run_id TEXT NOT NULL,
                    attempt_number INTEGER NOT NULL, verification_id TEXT NOT NULL,
                    verification_run_id TEXT NOT NULL,
                    evidence_kind TEXT NOT NULL CHECK(evidence_kind='VERIFICATION_RESULT'),
                    producer_kind TEXT NOT NULL CHECK(producer_kind='VERIFICATION'),
                    verification_status TEXT NOT NULL CHECK(verification_status='PASSED'),
                    associated_at TEXT NOT NULL,
                    PRIMARY KEY(task_id,plan_revision_id,step_revision_id,attempt_run_id,
                        attempt_number,verification_id),
                    FOREIGN KEY(task_id,plan_revision_id,step_revision_id,attempt_run_id,attempt_number,
                        evidence_kind,producer_kind,verification_id)
                        REFERENCES plan_evidence(task_id,plan_revision_id,step_revision_id,run_id,
                            attempt_number,evidence_kind,producer_kind,producer_identity),
                    FOREIGN KEY(verification_id,verification_run_id,task_id)
                        REFERENCES verification_runs(verification_id,run_id,task_id)
                )
                """, """
                CREATE TABLE plan_completion_rules (
                    task_id TEXT NOT NULL, plan_revision_id TEXT NOT NULL,
                    rule_order INTEGER NOT NULL CHECK(rule_order>=0),
                    rule_type TEXT NOT NULL CHECK(rule_type IN
                        ('ALL_REQUIRED_EVIDENCE','ANY_REQUIRED_EVIDENCE','READ_CONFIRMED',
                         'WRITE_APPLIED','SNAPSHOT_CREATED','VERIFICATION_PASSED',
                         'ARTIFACT_RECORDED','USER_CONFIRMED','CHILD_STEPS_COMPLETED')),
                    canonical_hash TEXT NOT NULL CHECK(length(canonical_hash)=64
                        AND canonical_hash NOT GLOB '*[^0-9a-f]*'),
                    provenance TEXT NOT NULL CHECK(length(trim(provenance))>0),
                    created_by_run_id TEXT NOT NULL, created_at TEXT NOT NULL,
                    PRIMARY KEY(task_id,plan_revision_id,rule_order),
                    FOREIGN KEY(task_id,plan_revision_id)
                        REFERENCES plan_revisions(task_id,plan_revision_id),
                    FOREIGN KEY(task_id,created_by_run_id) REFERENCES durable_runs(task_id,run_id)
                )
                """, """
                CREATE TABLE plan_completion_requirements (
                    task_id TEXT NOT NULL, plan_revision_id TEXT NOT NULL,
                    rule_order INTEGER NOT NULL, requirement_order INTEGER NOT NULL CHECK(requirement_order>=0),
                    requirement_type TEXT NOT NULL CHECK(requirement_type IN
                        ('STEP_SUCCEEDED','EVIDENCE_KIND_PRESENT','VERIFICATION_PASSED')),
                    step_revision_id TEXT NOT NULL, required_evidence_kind TEXT,
                    PRIMARY KEY(task_id,plan_revision_id,rule_order,requirement_order),
                    FOREIGN KEY(task_id,plan_revision_id,rule_order)
                        REFERENCES plan_completion_rules(task_id,plan_revision_id,rule_order),
                    FOREIGN KEY(task_id,plan_revision_id,step_revision_id)
                        REFERENCES plan_step_definitions(task_id,plan_revision_id,step_revision_id),
                    CHECK((requirement_type='EVIDENCE_KIND_PRESENT'
                            AND required_evidence_kind IN ('READ_EVIDENCE','TOOL_CALL','APPLIED_WRITE',
                                'SNAPSHOT','VERIFICATION_RESULT','USER_APPROVAL',
                                'GENERATED_ARTIFACT','MANUAL_CONFIRMATION'))
                       OR (requirement_type<>'EVIDENCE_KIND_PRESENT'
                            AND required_evidence_kind IS NULL))
                )
                """, """
                CREATE TABLE plan_completion_evaluations (
                    task_id TEXT NOT NULL, plan_revision_id TEXT NOT NULL, run_id TEXT NOT NULL,
                    evaluation_number INTEGER NOT NULL CHECK(evaluation_number>=1),
                    result TEXT NOT NULL CHECK(result IN
                        ('COMPLETE','INCOMPLETE','FAILED_REQUIREMENT','EVIDENCE_MISSING',
                         'EVIDENCE_INTEGRITY_FAILURE','VERIFICATION_REQUIRED','AUTHORITY_CONFLICT',
                         'LEGACY_COMPLETION_PROFILE_REQUIRED')),
                    rules_hash TEXT NOT NULL CHECK(length(rules_hash)=64
                        AND rules_hash NOT GLOB '*[^0-9a-f]*'),
                    bounded_reason TEXT CHECK(bounded_reason IS NULL OR length(bounded_reason)<=512),
                    evaluated_at TEXT NOT NULL, provenance TEXT NOT NULL CHECK(length(trim(provenance))>0),
                    PRIMARY KEY(task_id,plan_revision_id,run_id,evaluation_number),
                    FOREIGN KEY(task_id,run_id) REFERENCES durable_runs(task_id,run_id),
                    FOREIGN KEY(task_id,plan_revision_id)
                        REFERENCES plan_revisions(task_id,plan_revision_id)
                )
                """, """
                CREATE TABLE plan_completion_evaluation_evidence (
                    task_id TEXT NOT NULL, plan_revision_id TEXT NOT NULL, run_id TEXT NOT NULL,
                    evaluation_number INTEGER NOT NULL, step_revision_id TEXT NOT NULL,
                    attempt_run_id TEXT NOT NULL, attempt_number INTEGER NOT NULL,
                    evidence_kind TEXT NOT NULL, producer_kind TEXT NOT NULL,
                    producer_identity TEXT NOT NULL,
                    PRIMARY KEY(task_id,plan_revision_id,run_id,evaluation_number,
                        step_revision_id,attempt_run_id,attempt_number,evidence_kind,
                        producer_kind,producer_identity),
                    FOREIGN KEY(task_id,plan_revision_id,run_id,evaluation_number)
                        REFERENCES plan_completion_evaluations(task_id,plan_revision_id,
                            run_id,evaluation_number),
                    FOREIGN KEY(task_id,plan_revision_id,step_revision_id,attempt_run_id,
                        attempt_number,evidence_kind,producer_kind,producer_identity)
                        REFERENCES plan_evidence(task_id,plan_revision_id,step_revision_id,
                            run_id,attempt_number,evidence_kind,producer_kind,producer_identity)
                )
                """, """
                CREATE TABLE plan_completion_evaluation_steps (
                    task_id TEXT NOT NULL, plan_revision_id TEXT NOT NULL, run_id TEXT NOT NULL,
                    evaluation_number INTEGER NOT NULL, step_revision_id TEXT NOT NULL,
                    observed_state TEXT NOT NULL, transition_version INTEGER NOT NULL,
                    current_attempt_number INTEGER NOT NULL,
                    PRIMARY KEY(task_id,plan_revision_id,run_id,evaluation_number,step_revision_id),
                    FOREIGN KEY(task_id,plan_revision_id,run_id,evaluation_number)
                        REFERENCES plan_completion_evaluations(task_id,plan_revision_id,
                            run_id,evaluation_number),
                    FOREIGN KEY(task_id,plan_revision_id,step_revision_id)
                        REFERENCES plan_step_executions(task_id,plan_revision_id,step_revision_id)
                )
                """, """
                CREATE TABLE task_completion_decisions (
                    task_id TEXT PRIMARY KEY, plan_revision_id TEXT NOT NULL, run_id TEXT NOT NULL,
                    evaluation_number INTEGER NOT NULL, result TEXT NOT NULL CHECK(result='COMPLETE'),
                    decision_hash TEXT NOT NULL CHECK(length(decision_hash)=64
                        AND decision_hash NOT GLOB '*[^0-9a-f]*'),
                    decided_at TEXT NOT NULL, provenance TEXT NOT NULL CHECK(length(trim(provenance))>0),
                    FOREIGN KEY(task_id,plan_revision_id,run_id,evaluation_number)
                        REFERENCES plan_completion_evaluations(task_id,plan_revision_id,
                            run_id,evaluation_number)
                )
                """,
                "CREATE INDEX idx_r1c_evidence_scope ON plan_evidence(task_id,plan_revision_id,step_revision_id,evidence_kind)",
                "CREATE INDEX idx_r1c_evidence_producer ON plan_evidence(producer_kind,producer_identity)",
                "CREATE INDEX idx_r1c_completion_evaluation ON plan_completion_evaluations(task_id,plan_revision_id,evaluated_at)",
                """
                CREATE TRIGGER r1c_step_completion_profile_version
                BEFORE INSERT ON plan_step_definitions
                WHEN (
                    (SELECT canonical_schema_version FROM plan_revisions
                     WHERE task_id=NEW.task_id AND plan_revision_id=NEW.plan_revision_id)=2
                    AND (NEW.completion_rule IS NULL
                         OR NEW.required_evidence_kinds IS NULL OR trim(NEW.required_evidence_kinds)=''
                         OR NEW.effect_class IS NULL)
                ) OR (
                    (SELECT canonical_schema_version FROM plan_revisions
                     WHERE task_id=NEW.task_id AND plan_revision_id=NEW.plan_revision_id)=1
                    AND (NEW.completion_rule IS NOT NULL
                         OR NEW.required_evidence_kinds IS NOT NULL
                         OR NEW.effect_class IS NOT NULL)
                )
                BEGIN SELECT RAISE(ABORT,'Plan completion profile does not match canonical version'); END
                """,
                """
                CREATE TRIGGER r1c_evidence_producer_exists BEFORE INSERT ON plan_evidence
                WHEN (NEW.producer_kind='OCCURRENCE' AND NOT EXISTS (
                        SELECT 1 FROM side_effect_occurrences o WHERE o.occurrence_id=NEW.producer_identity
                          AND o.task_id=NEW.task_id AND o.run_id=NEW.run_id AND o.status='COMPLETED'))
                  OR (NEW.producer_kind='SNAPSHOT' AND NOT EXISTS (
                        SELECT 1 FROM file_snapshots s WHERE s.snapshot_id=NEW.producer_identity
                          AND s.task_id=NEW.task_id AND s.owner_run_id=NEW.run_id
                          AND s.status='APPLIED'))
                  OR (NEW.producer_kind='VERIFICATION' AND NOT EXISTS (
                        SELECT 1 FROM verification_runs v WHERE v.verification_id=NEW.producer_identity
                          AND v.task_id=NEW.task_id AND v.status='PASSED'))
                  OR (NEW.producer_kind='APPROVAL' AND NOT EXISTS (
                        SELECT 1 FROM plan_approvals a WHERE a.approval_id=NEW.producer_identity
                          AND a.task_id=NEW.task_id AND a.plan_revision_id=NEW.plan_revision_id
                          AND a.decision='APPROVED'))
                BEGIN SELECT RAISE(ABORT,'Evidence producer is not authoritative'); END
                """, """
                CREATE TRIGGER r1c_evidence_immutable BEFORE UPDATE ON plan_evidence
                BEGIN SELECT RAISE(ABORT,'plan Evidence is immutable'); END
                """, """
                CREATE TRIGGER r1c_evidence_no_delete BEFORE DELETE ON plan_evidence
                BEGIN SELECT RAISE(ABORT,'plan Evidence is retained'); END
                """, """
                CREATE TRIGGER r1c_verification_evidence_immutable BEFORE UPDATE ON plan_verification_evidence
                BEGIN SELECT RAISE(ABORT,'Verification Evidence association is immutable'); END
                """, """
                CREATE TRIGGER r1c_verification_evidence_no_delete BEFORE DELETE ON plan_verification_evidence
                BEGIN SELECT RAISE(ABORT,'Verification Evidence association is retained'); END
                """, """
                CREATE TRIGGER r1c_completion_rule_immutable BEFORE UPDATE ON plan_completion_rules
                BEGIN SELECT RAISE(ABORT,'Completion Rules are immutable'); END
                """, """
                CREATE TRIGGER r1c_completion_rule_no_delete BEFORE DELETE ON plan_completion_rules
                BEGIN SELECT RAISE(ABORT,'Completion Rules are retained'); END
                """, """
                CREATE TRIGGER r1c_completion_requirement_immutable BEFORE UPDATE ON plan_completion_requirements
                BEGIN SELECT RAISE(ABORT,'Completion requirements are immutable'); END
                """, """
                CREATE TRIGGER r1c_completion_requirement_no_delete BEFORE DELETE ON plan_completion_requirements
                BEGIN SELECT RAISE(ABORT,'Completion requirements are retained'); END
                """, """
                CREATE TRIGGER r1c_completion_evaluation_immutable BEFORE UPDATE ON plan_completion_evaluations
                BEGIN SELECT RAISE(ABORT,'Completion evaluations are immutable'); END
                """, """
                CREATE TRIGGER r1c_completion_evaluation_no_delete BEFORE DELETE ON plan_completion_evaluations
                BEGIN SELECT RAISE(ABORT,'Completion evaluations are retained'); END
                """, """
                CREATE TRIGGER r1c_completion_evaluation_evidence_no_delete
                BEFORE DELETE ON plan_completion_evaluation_evidence
                BEGIN SELECT RAISE(ABORT,'Completion evaluation Evidence is retained'); END
                """, """
                CREATE TRIGGER r1c_completion_evaluation_evidence_immutable
                BEFORE UPDATE ON plan_completion_evaluation_evidence
                BEGIN SELECT RAISE(ABORT,'Completion evaluation Evidence is immutable'); END
                """, """
                CREATE TRIGGER r1c_completion_evaluation_steps_no_delete
                BEFORE DELETE ON plan_completion_evaluation_steps
                BEGIN SELECT RAISE(ABORT,'Completion evaluation Step facts are retained'); END
                """, """
                CREATE TRIGGER r1c_completion_evaluation_steps_immutable
                BEFORE UPDATE ON plan_completion_evaluation_steps
                BEGIN SELECT RAISE(ABORT,'Completion evaluation Step facts are immutable'); END
                """, """
                CREATE TRIGGER r1c_completion_decision_immutable BEFORE UPDATE ON task_completion_decisions
                BEGIN SELECT RAISE(ABORT,'Task completion decisions are immutable'); END
                """, """
                CREATE TRIGGER r1c_completion_decision_no_delete BEFORE DELETE ON task_completion_decisions
                BEGIN SELECT RAISE(ABORT,'Task completion decisions are retained'); END
                """);
    }

    private final Connection connection;

    public MigrationRunner(Connection connection) {
        this.connection = connection;
    }

    public void runMigrations() throws SQLException {
        runMigrationsThrough(Integer.MAX_VALUE);
    }

    void runMigrationsThrough(int targetVersion) throws SQLException {
        enableAndVerifyForeignKeys();
        ensureMigrationTable();
        for (Migration migration : MIGRATIONS) {
            if (migration.version() <= targetVersion && !isApplied(migration.version())) {
                apply(migration);
            }
        }
    }

    private void enableAndVerifyForeignKeys() throws SQLException {
        if (!connection.getAutoCommit()) {
            throw new SQLException("Migrations require an autocommit connection before starting");
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            try (ResultSet result = statement.executeQuery("PRAGMA foreign_keys")) {
                if (!result.next() || result.getInt(1) != 1) {
                    throw new SQLException("SQLite foreign key enforcement is not enabled");
                }
            }
        }
    }

    private void ensureMigrationTable() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS schema_migrations (
                        version INTEGER PRIMARY KEY,
                        applied_at TEXT NOT NULL DEFAULT (datetime('now')),
                        description TEXT
                    )
                    """);
        }
    }

    private boolean isApplied(int version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM schema_migrations WHERE version = ?")) {
            statement.setInt(1, version);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private void apply(Migration migration) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (Statement statement = connection.createStatement()) {
                for (String sql : migration.statements()) {
                    executeMigrationStatement(statement, sql);
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO schema_migrations(version, description) VALUES (?, ?)")) {
                statement.setInt(1, migration.version());
                statement.setString(2, migration.description());
                statement.executeUpdate();
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private void executeMigrationStatement(Statement statement, String sql) throws SQLException {
        if (sql.startsWith("EXECUTE_IF_TABLE_EXISTS ")) {
            String[] parts = sql.split(" ", 3);
            if (parts.length != 3) throw new SQLException("Invalid migration directive: " + sql);
            if (tableExists(parts[1])) statement.execute(parts[2]);
            return;
        }
        if (sql.startsWith("ADD_COLUMN_IF_TABLE_EXISTS ")) {
            String[] parts = sql.split(" ", 4);
            if (parts.length != 4) throw new SQLException("Invalid migration directive: " + sql);
            if (tableExists(parts[1]) && !columnExists(parts[1], parts[2])) {
                statement.execute("ALTER TABLE " + parts[1] + " ADD COLUMN "
                        + parts[2] + " " + parts[3]);
            }
            return;
        }
        if (sql.startsWith("ADD_COLUMN_IF_MISSING ")) {
            String[] parts = sql.split(" ", 4);
            if (parts.length != 4) {
                throw new SQLException("Invalid migration directive: " + sql);
            }
            String tableName = parts[1];
            String columnName = parts[2];
            String columnDefinition = parts[3];
            if (!columnExists(tableName, columnName)) {
                statement.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + columnDefinition);
            }
            return;
        }
        statement.execute(sql);
    }

    private boolean tableExists(String tableName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            statement.setString(1, tableName);
            try (ResultSet row = statement.executeQuery()) { return row.next(); }
        }
    }

    private boolean columnExists(String tableName, String columnName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("PRAGMA table_info(" + tableName + ")");
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                if (columnName.equals(resultSet.getString("name"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private record Migration(int version, String description, List<String> statements) {
    }
}
