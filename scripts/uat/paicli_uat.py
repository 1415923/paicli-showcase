#!/usr/bin/env python3
"""Bounded Windows ConPTY distribution UAT with a loopback fake Provider."""

from __future__ import annotations

import argparse
import ctypes
from ctypes import wintypes
from datetime import datetime
import hashlib
import html
import json
import os
from pathlib import Path
import queue
import re
import shutil
import sqlite3
import subprocess
import sys
import threading
import time
import traceback
from contextlib import closing
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from zipfile import ZipFile

try:
    from winpty import Backend, PtyProcess
except Exception as exc:  # pragma: no cover - environment gate
    raise SystemExit(f"PYWINPTY_UNAVAILABLE: {type(exc).__name__}")

SCENARIOS = (
    "readonly-single-tool", "approval-reject", "approval-cancel", "ctrl-c-exit",
    "manual-compact", "auto-long-task", "write-verify-rewind",
    "structured-verify-fail-closed", "session-resume-continue",
    "normal-long-plan-approval", "plan-tool-scope-sequential-deviation",
    "plan-tool-scope-multi-tool-batch", "plan-evidence-semantic-count",
    "plan-v2-unfinished-fail-closed",
)
ANSI = re.compile(r"\x1b(?:\[[0-?]*[ -/]*[@-~]|\][^\x07]*(?:\x07|\x1b\\))")
SECRET = "paicli-uat-synthetic-credential"
IMMUTABLE_PLAN_APPROVAL_PROMPT = (
    "Approve this immutable Plan revision? "
    "(y=yes, n=reject, d=details, c=cancel):"
)
V2_RESTART_PROMPTS = ("paicli [ready]>", "paicli [integrity-blocked]>")
EDIT_APPROVAL_PROMPT = "Apply? (y=yes, n=reject, d=diff, a=all-safe, c=cancel):"
REWIND_CONFIRMATION_PROMPT = "rewinds only the selected snapshot. [y/N]:"
STRUCTURED_PLAN_PROMPT_MARKERS = (
    "Return only one JSON object with fields acceptanceCriteria, assumptions, risks, ",
    "verificationStrategy and steps.",
    "requiredEvidenceKinds",
    "toolRequirements",
    "Do not call tools or mark work complete.",
)
LEGACY_PLAN_PROMPT_MARKERS = (
    "Create a concise, actionable implementation plan for the coding task below.",
    "Do not call tools and do not claim that work has already been completed.",
)
FINAL_SYNTHESIS_PROMPT_MARKERS = (
    "Produce the final user-facing answer for the original Task now.",
    "Produce the required minimal user result for this Task.",
)
PLAN = {
    "acceptanceCriteria": ["README.md was inspected without mutation"],
    "assumptions": [],
    "risks": [],
    "verificationStrategy": "Use trusted read_file evidence",
    "steps": [{
        "title": "Inspect README",
        "definition": "Read README.md and report its observed purpose without changing files",
        "dependsOn": [],
        "completionRule": "ALL_REQUIRED_EVIDENCE",
        "requiredEvidenceKinds": ["TOOL_CALL"],
        "effectClass": "READ",
        "toolRequirements": [{
            "requirementId": "read-readme",
            "requirementOrder": 0,
            "toolName": "read_file",
            "effectClass": "READ",
            "authoritySelector": {"kind": "EXACT_PATH", "path": "README.md"},
            "minSuccesses": 1,
            "maxOccurrences": 1,
            "freshnessPolicy": "RESUME_REUSE_ONLY",
        }],
    }],
}

PRIVATE_MARKERS = (
    SECRET, "reasoning_content", "payloadDigest", "[PAICLI_TOOL_FACTS]",
    "canonicalFactPayloadDigest", "canonicalFactToolCallIdHash",
    "canonicalFactTrustClass", "Authorization:", "Bearer ",
)
MANUAL_MARKERS = ("FIRST-CONTEXT-MARKER-7A", "MIDDLE-CONTEXT-MARKER-4D",
                  "LAST-CONTEXT-MARKER-9F")
MANUAL_CONTEXT_FILE_COUNT = 10
MANUAL_POLICY_RETAINED_PAIR_COUNT = 4
MANUAL_MARKER_INDEXES = (1, 5, MANUAL_CONTEXT_FILE_COUNT)
MANUAL_PROHIBITION = "PROHIBITION-NO-WORKSPACE-MUTATION"
MANUAL_UNFINISHED = "UNFINISHED-GOAL-REPORT-ALL-PRIOR-FACTS"
LONG_MARKERS = ("LONG-FIRST-MARKER-A1", "LONG-MIDDLE-MARKER-H8",
                "LONG-LAST-MARKER-O15")
NORMAL_LONG_PLAN_TITLES = (
    "核对入口说明", "核对执行边界", "核对审批约束", "核对长定义完整性",
    "核对证据投影", "核对只读结果", "汇总七项证据",
)
NORMAL_LONG_PLAN_DEFINITION_MARKERS = tuple(
    f"LONG-PLAN-DEFINITION-{index:02d}" for index in range(1, 8))
NORMAL_LONG_PLAN_RESULT_MARKERS = tuple(
    f"LONG-PLAN-RESULT-{index:02d}" for index in range(1, 8))
NORMAL_LONG_PLAN_DIRECTORY = "normal-long-plan"
WRITE_BEFORE = "before-uat\n"
WRITE_AFTER = "after-uat\n"


def terminal_token_present(text: str, token: str) -> bool:
    """Match a safe assertion token across ConPTY soft-wrap whitespace."""
    visible = ANSI.sub("", text)
    compact_visible = re.sub(r"\s+", "", visible)
    compact_token = re.sub(r"\s+", "", token)
    return bool(compact_token) and compact_token in compact_visible


def canonical_plan_details_present(text: str, titles: tuple[str, ...],
                                   long_definition_tail: str,
                                   expected_revision_id: str,
                                   expected_canonical_hash: str) -> bool:
    """Match public canonical identity and structure to the exact durable revision."""
    visible = ANSI.sub("", text)
    required_fields = (
        "Goal:", "Acceptance criteria:",
        "Assumptions:", "Risks:", "Verification strategy:",
        f"Steps ({len(titles)}):",
    )
    return (bool(expected_revision_id) and bool(expected_canonical_hash)
            and terminal_token_present(
                visible, f"Plan revision: {expected_revision_id}")
            and terminal_token_present(
                visible, f"Plan hash: {expected_canonical_hash}")
            and all(field in visible for field in required_fields)
            and all(title in visible for title in titles)
            and terminal_token_present(visible, "Plan canonical schema: V3")
            and terminal_token_present(visible, "Plan mode: DEFAULT_ADVISORY_PLAN")
            and visible.count("Definition:") == len(titles)
            and visible.count("Advisory dependencies:") == len(titles)
            and "Scope:" not in visible
            and terminal_token_present(visible, long_definition_tail))


class NotReached(RuntimeError):
    """A required product stage was not observed; never convert this to PASS."""


class ProcessProbeError(RuntimeError):
    """Bounded process-probe failure without retaining command output."""

    def __init__(self, category: str, return_code: int | None = None) -> None:
        super().__init__("RESIDUAL_JAVA_PROBE_FAILED")
        self.category = category
        self.return_code = return_code


def provider_operation(request: dict) -> str:
    """Classify the current provider operation from stable request contracts."""
    messages = request.get("messages", [])
    if not isinstance(messages, list):
        raise RuntimeError("UAT_PROVIDER_MESSAGES_INVALID")
    tools = request.get("tools", [])
    if tools:
        if not isinstance(tools, list):
            raise RuntimeError("UAT_PROVIDER_TOOLS_INVALID")
        return "EXECUTE"

    user_text = "\n".join(
        str(message.get("content", ""))
        for message in messages
        if isinstance(message, dict) and message.get("role") == "user"
    )
    if any(marker in user_text for marker in FINAL_SYNTHESIS_PROMPT_MARKERS):
        return "FINAL_SYNTHESIS"
    response_format = request.get("response_format")
    json_plan = (isinstance(response_format, dict)
                 and response_format.get("type") == "json_object")
    structured_plan = all(marker in user_text
                          for marker in STRUCTURED_PLAN_PROMPT_MARKERS)
    legacy_plan = all(marker in user_text for marker in LEGACY_PLAN_PROMPT_MARKERS)
    if json_plan or structured_plan or legacy_plan:
        return "PLAN"
    raise RuntimeError("UAT_PROVIDER_OPERATION_UNCLASSIFIED")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def canonical_v2_plan_hash(revision: dict, steps: list[dict],
                           dependencies: list[dict] | None = None,
                           lineage: list[dict] | None = None) -> str:
    """Mirror the frozen V2 domain for deterministic legacy recovery fixtures."""
    value = "R1C-A-PLAN-V2"

    def field(item) -> None:
        nonlocal value
        text_value = "" if item is None else str(item)
        java_length = len(text_value.encode("utf-16-le")) // 2
        value += f"|{java_length}:{text_value}"

    for name in ("plan_revision_id", "task_id", "revision_number", "goal",
                 "acceptance_criteria", "assumptions", "risks",
                 "verification_strategy", "source_type", "provenance",
                 "created_by_run_id"):
        field(revision[name])
    for step in sorted(steps, key=lambda item: item["step_order"]):
        for item in ("STEP", step["step_revision_id"], step["logical_step_id"],
                     step["step_order"], step["title"], step["definition"],
                     step["completion_rule"], step["effect_class"]):
            field(item)
        kinds = step["required_evidence_kinds"]
        if isinstance(kinds, str):
            kinds = kinds.split(",")
        for kind in sorted(kinds):
            field(kind)
        field(step["canonical_hash"])
    for dependency in sorted(dependencies or [], key=lambda item: (
            item["step_revision_id"], item["depends_on_step_revision_id"])):
        field("DEPENDENCY")
        field(dependency["step_revision_id"])
        field(dependency["depends_on_step_revision_id"])
    for edge in sorted(lineage or [], key=lambda item: item["lineage_id"]):
        for item in ("LINEAGE", edge["lineage_id"],
                     edge.get("source_plan_revision_id", ""),
                     edge.get("source_step_revision_id", ""),
                     edge.get("target_plan_revision_id", ""),
                     edge.get("target_step_revision_id", ""),
                     edge["relationship"], edge["provenance"]):
            field(item)
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def seed_unfinished_v2_plan(workspace: Path) -> dict:
    """Attach a deterministic approved V2 Plan to the live unfinished UAT Task."""
    database = workspace / ".paicli" / "state" / "paicli.db"
    if not database.exists():
        raise NotReached("V2 seed database was not initialized")
    plan_id = "pln_00000000000040008000000000000021"
    step_id = "stp_00000000000040008000000000000022"
    logical_step_id = "stp_00000000000040008000000000000023"
    approval_id = "legacy-v2-uat-approval"
    binding_id = "legacy-v2-uat-binding"
    at = "2026-08-02T00:00:03Z"
    with closing(sqlite3.connect(database, timeout=30)) as connection:
        connection.execute("pragma foreign_keys=on")
        if connection.execute("pragma foreign_keys").fetchone()[0] != 1:
            raise NotReached("V2 seed foreign keys were not enabled")
        connection.execute("begin immediate")
        task = connection.execute("""
            select task_id from task_identities
            where terminal_at is null order by created_at desc,task_id desc limit 1
            """).fetchone()
        if task is None:
            raise NotReached("V2 seed found no unfinished durable Task")
        task_id = task[0]
        run = connection.execute("""
            select run_id from durable_runs
            where task_id=? and terminal_status is null
            order by created_at desc,run_id desc limit 1
            """, (task_id,)).fetchone()
        if run is None:
            raise NotReached("V2 seed found no unfinished durable Run")
        run_id = run[0]
        if connection.execute(
                "select count(*) from task_plan_authorities where task_id=?",
                (task_id,)).fetchone()[0] != 0:
            raise NotReached("V2 seed Task already had active structured authority")
        revision_number = connection.execute(
            "select coalesce(max(revision_number),0)+1 from plan_revisions where task_id=?",
            (task_id,)).fetchone()[0]
        step = {
            "step_revision_id": step_id, "logical_step_id": logical_step_id,
            "step_order": 0, "title": "Legacy unfinished read",
            "definition": "Read README.md using legacy V2 completion evidence",
            "completion_rule": "ALL_REQUIRED_EVIDENCE",
            "required_evidence_kinds": ["TOOL_CALL"], "effect_class": "READ",
            "canonical_hash": "b" * 64,
        }
        revision = {
            "plan_revision_id": plan_id, "task_id": task_id,
            "revision_number": revision_number, "goal": "legacy unfinished recovery",
            "acceptance_criteria": "README.md was read", "assumptions": "",
            "risks": "", "verification_strategy": "durable Tool Evidence",
            "source_type": "USER", "provenance": "OFFLINE_UAT_LEGACY_V2",
            "created_by_run_id": run_id,
        }
        plan_hash = canonical_v2_plan_hash(revision, [step])
        connection.execute("""
            insert into plan_revisions(plan_revision_id,task_id,revision_number,goal,
                acceptance_criteria,assumptions,risks,verification_strategy,canonical_hash,
                source_type,provenance,created_by_run_id,created_at,canonical_schema_version)
            values(?,?,?,?,?,?,?,?,?,?,?,?,?,2)
            """, (plan_id, task_id, revision_number, revision["goal"],
                  revision["acceptance_criteria"], revision["assumptions"],
                  revision["risks"], revision["verification_strategy"], plan_hash,
                  revision["source_type"], revision["provenance"], run_id, at))
        connection.execute("""
            insert into plan_step_definitions(step_revision_id,logical_step_id,
                plan_revision_id,task_id,step_order,title,definition,canonical_hash,
                completion_rule,required_evidence_kinds,effect_class)
            values(?,?,?,?,?,?,?,?,?,?,?)
            """, (step_id, logical_step_id, plan_id, task_id, 0, step["title"],
                  step["definition"], step["canonical_hash"], step["completion_rule"],
                  "TOOL_CALL", step["effect_class"]))
        connection.execute("""
            insert into plan_revision_lifecycle(plan_revision_id,task_id,state,cas_version,
                updated_by_run_id,updated_at) values(?,?,'APPROVED',2,?,?)
            """, (plan_id, task_id, run_id, at))
        connection.execute("""
            insert into plan_approvals(approval_id,task_id,plan_revision_id,decision,
                approved_by,approved_by_run_id,policy_version,decided_at)
            values(?,?,?,'APPROVED','offline-uat',?,'legacy-v2',?)
            """, (approval_id, task_id, plan_id, run_id, at))
        connection.execute("""
            insert into task_plan_authorities(task_id,active_plan_revision_id,
                active_revision_state,active_approval_id,active_approval_decision,
                activated_by_run_id,pointer_version,activated_at)
            values(?,?,'APPROVED',?,'APPROVED',?,1,?)
            """, (task_id, plan_id, approval_id, run_id, at))
        connection.execute("""
            insert into plan_run_bindings(binding_id,task_id,run_id,plan_revision_id,
                approval_id,approval_decision,plan_hash,bound_at)
            values(?,?,?,?,?,'APPROVED',?,?)
            """, (binding_id, task_id, run_id, plan_id, approval_id, plan_hash, at))
        connection.execute("""
            insert into plan_step_executions(task_id,plan_revision_id,step_revision_id,
                state,transition_version,current_attempt_number,initialized_by_run_id,
                last_updated_by_run_id,initialized_at,updated_at,provenance)
            values(?,?,?,'READY',0,0,?,?,?,?,?)
            """, (task_id, plan_id, step_id, run_id, run_id, at, at,
                  "OFFLINE_UAT_LEGACY_V2"))
        rule_material = (f"{task_id}\n{plan_id}\n0\nALL_REQUIRED_EVIDENCE\n"
                         f"{step_id}\nSTEP_SUCCEEDED\nTOOL_CALL")
        rule_hash = hashlib.sha256(rule_material.encode("utf-8")).hexdigest()
        connection.execute("""
            insert into plan_completion_rules(task_id,plan_revision_id,rule_order,
                rule_type,canonical_hash,provenance,created_by_run_id,created_at)
            values(?,?,0,'ALL_REQUIRED_EVIDENCE',?,'OFFLINE_UAT_LEGACY_V2',?,?)
            """, (task_id, plan_id, rule_hash, run_id, at))
        connection.execute("""
            insert into plan_completion_requirements(task_id,plan_revision_id,rule_order,
                requirement_order,requirement_type,step_revision_id,required_evidence_kind)
            values(?,?,0,0,'STEP_SUCCEEDED',?,null)
            """, (task_id, plan_id, step_id))
        connection.execute("""
            insert into plan_completion_requirements(task_id,plan_revision_id,rule_order,
                requirement_order,requirement_type,step_revision_id,required_evidence_kind)
            values(?,?,0,1,'EVIDENCE_KIND_PRESENT',?,'TOOL_CALL')
            """, (task_id, plan_id, step_id))
        checkpoint = json.dumps([{
            "role": "USER", "content": "legacy unfinished recovery",
            "type": "USER", "toolCallId": None, "toolCallJson": None,
        }], separators=(",", ":"))
        connection.execute("""
            insert into checkpoints(task_id,iteration,status,messages_json)
            values(?,1,'RUNNING',?)
            on conflict(task_id) do update set iteration=1,status='RUNNING',
                messages_json=excluded.messages_json,created_at=datetime('now')
            """, (task_id, checkpoint))
        connection.execute("""
            update task_ownership_leases set expires_at='2000-01-01T00:00:00Z'
            where task_id=? and owner_run_id=? and state='ACTIVE'
            """, (task_id, run_id))
        connection.commit()
        foreign_key_errors = connection.execute("pragma foreign_key_check").fetchall()
        if foreign_key_errors:
            raise NotReached(f"V2 seed foreign-key violations: {len(foreign_key_errors)}")
    return {"taskId": task_id, "runId": run_id, "planId": plan_id,
            "stepId": step_id, "planHash": plan_hash}


def user_hashes(workspace: Path) -> dict[str, str]:
    return {str(path.relative_to(workspace)).replace("\\", "/"): sha256(path)
            for path in sorted(workspace.rglob("*"))
            if path.is_file() and ".paicli" not in path.relative_to(workspace).parts}


class FakeProvider:
    def __init__(self, fault_injector=None) -> None:
        self.requests: list[dict] = []
        self.request_attempts: list[dict] = []
        self.fault_injector = fault_injector
        self._attempt_lock = threading.Lock()
        self._active_digest: str | None = None
        self._active_logical_turn = 0
        self._actual_attempt = 0
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_args) -> None:
                return

            def do_GET(self) -> None:
                if self.path.endswith("/models"):
                    self._send({"object": "list", "data": [{"id": "paicli-uat-model"}]})
                else:
                    self.send_error(404)

            def do_POST(self) -> None:
                length = int(self.headers.get("Content-Length", "0"))
                encoded = self.rfile.read(length)
                body = json.loads(encoded.decode("utf-8"))
                outer.requests.append(body)
                attempt = outer.record_attempt(body, encoded)
                if outer.fault_injector is not None and outer.fault_injector(self, attempt):
                    return
                self._send(outer.response(body, len(outer.requests)))
                outer.mark_attempt_completed(attempt, "HTTP_200")

            def _send(self, body: dict, status: int = 200,
                      headers: dict[str, str] | None = None) -> None:
                encoded = json.dumps(body, separators=(",", ":")).encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(encoded)))
                for key, value in (headers or {}).items():
                    self.send_header(key, value)
                self.end_headers()
                self.wfile.write(encoded)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)

    @property
    def base_url(self) -> str:
        return f"http://127.0.0.1:{self.server.server_port}"

    def start(self) -> None:
        self.thread.start()

    def close(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=2)

    def record_attempt(self, body: dict, encoded: bytes | None = None) -> dict:
        if encoded is None:
            encoded = json.dumps(
                body, ensure_ascii=False, sort_keys=True,
                separators=(",", ":")).encode("utf-8")
        digest = hashlib.sha256(encoded).hexdigest()
        flat = json.dumps(body.get("messages", []), ensure_ascii=False)
        context = re.search(r"\bctx=(ctx_[A-Za-z0-9]+)\b", flat)
        with self._attempt_lock:
            if digest != self._active_digest:
                self._active_logical_turn += 1
                self._actual_attempt = 0
                self._active_digest = digest
            self._actual_attempt += 1
            record = {
                "logicalTurn": self._active_logical_turn,
                "actualAttempt": self._actual_attempt,
                "requestDigest": digest,
                "contextGeneration": None if context is None else context.group(1),
                "outcome": "STARTED",
            }
            self.request_attempts.append(record)
            return dict(record)

    def annotate_attempt(self, attempt: dict, outcome: str) -> None:
        with self._attempt_lock:
            for record in reversed(self.request_attempts):
                if (record["logicalTurn"] == attempt.get("logicalTurn")
                        and record["actualAttempt"] == attempt.get("actualAttempt")):
                    record["outcome"] = outcome
                    return
            raise RuntimeError("provider attempt evidence was not found")

    def mark_attempt_completed(self, attempt: dict, outcome: str = "HTTP_200") -> None:
        self.annotate_attempt(attempt, outcome)
        with self._attempt_lock:
            if (self._active_digest == attempt.get("requestDigest")
                    and self._active_logical_turn == attempt.get("logicalTurn")):
                self._active_digest = None
                self._actual_attempt = 0

    def response(self, request: dict, index: int) -> dict:
        operation = provider_operation(request)
        if operation == "PLAN":
            message = {"role": "assistant", "content": json.dumps(PLAN, separators=(",", ":"))}
            finish = "stop"
        elif operation == "FINAL_SYNTHESIS":
            message = {"role": "assistant", "content":
                       "README.md is the offline UAT fixture describing this workspace. "
                       "No files were created, modified, or deleted."}
            finish = "stop"
        elif any(message.get("role") == "tool" for message in request.get("messages", [])):
            message = {"role": "assistant", "content":
                       "README.md was read successfully and identifies the offline UAT workspace."}
            finish = "stop"
        else:
            message = {"role": "assistant", "content": "",
                       "tool_calls": [{"id": "call_readme_1", "type": "function",
                                       "function": {"name": "read_file",
                                                    "arguments": "{\"path\":\"README.md\"}"}}]}
            finish = "tool_calls"
        return {"id": f"uat-completion-{index}", "object": "chat.completion",
                "choices": [{"index": 0, "finish_reason": finish, "message": message}],
                "usage": {"prompt_tokens": 32, "completion_tokens": 16, "total_tokens": 48}}


def exact_path_requirement(requirement_id: str, requirement_order: int,
                           tool_name: str, path: str,
                           min_successes: int = 1,
                           max_occurrences: int = 1,
                           freshness_policy: str = "RESUME_REUSE_ONLY") -> dict:
    """Provider-shaped V3 requirement; canonical JSON/digest remain runtime-owned."""
    selector = {"kind": "EXACT_PATH", "path": path}
    if tool_name == "write_file":
        selector["overwrite"] = True
    return {
        "requirementId": requirement_id,
        "requirementOrder": requirement_order,
        "toolName": tool_name,
        "effectClass": "WRITE" if tool_name == "write_file" else "READ",
        "authoritySelector": selector,
        "minSuccesses": min_successes,
        "maxOccurrences": max_occurrences,
        "freshnessPolicy": freshness_policy,
    }


def plan_for(title: str, tools: list[tuple[str, str]],
             verification_required: bool = False) -> dict:
    steps = []
    for index, (tool_name, path) in enumerate(tools, 1):
        write = tool_name == "write_file"
        required_evidence = (["TOOL_CALL", "SNAPSHOT", "VERIFICATION_RESULT"]
                             if write and verification_required
                             else ["TOOL_CALL", "SNAPSHOT"] if write else ["TOOL_CALL"])
        steps.append({
            "title": f"{title} {index}",
            "definition": f"Use {tool_name} on {path} and retain durable evidence",
            "dependsOn": [] if index == 1 else [index - 2],
            "completionRule": "ALL_REQUIRED_EVIDENCE",
            "requiredEvidenceKinds": required_evidence,
            "effectClass": "WRITE" if write else "READ",
            "toolRequirements": [exact_path_requirement(
                f"step-{index:02d}-{tool_name}", 0, tool_name, path)],
        })
    return {
        "acceptanceCriteria": [f"{title} completed from durable Tool evidence"],
        "assumptions": [], "risks": [],
        "verificationStrategy": "Run the configured verifier after applied writes",
        "steps": steps,
    }


def normal_long_plan() -> dict:
    """Seven-step canonical Plan fixture with one definition beyond preview bounds."""
    definitions = [
        f"读取第一份只读证据，确认项目入口说明。{NORMAL_LONG_PLAN_DEFINITION_MARKERS[0]}",
        f"读取第二份只读证据，确认执行边界保持不变。{NORMAL_LONG_PLAN_DEFINITION_MARKERS[1]}",
        f"读取第三份只读证据，确认审批只绑定当前不可变计划修订。{NORMAL_LONG_PLAN_DEFINITION_MARKERS[2]}",
        ("读取第四份只读证据，并验证批准前的可见预览可以有界显示，同时 durable canonical "
         "definition 不得截断、替换或丢失。" +
         "这是一段用于跨越二百五十六个 Unicode 码点边界的确定性中文内容；" * 12 +
         NORMAL_LONG_PLAN_DEFINITION_MARKERS[3]),
        f"读取第五份只读证据，确认工具事实只通过安全投影进入结果。{NORMAL_LONG_PLAN_DEFINITION_MARKERS[4]}",
        f"读取第六份只读证据，确认全过程不修改工作区文件。{NORMAL_LONG_PLAN_DEFINITION_MARKERS[5]}",
        f"读取第七份只读证据，并依据七项 durable Tool evidence 完成汇总。{NORMAL_LONG_PLAN_DEFINITION_MARKERS[6]}",
    ]
    steps = []
    for index, (title, definition) in enumerate(
            zip(NORMAL_LONG_PLAN_TITLES, definitions), 1):
        steps.append({
            "title": title,
            "definition": definition,
            "dependsOn": [] if index == 1 else [index - 2],
            "completionRule": "ALL_REQUIRED_EVIDENCE",
            "requiredEvidenceKinds": ["TOOL_CALL"],
            "effectClass": "READ",
            "toolRequirements": [exact_path_requirement(
                f"normal-long-{index:02d}", 0, "read_file",
                f"{NORMAL_LONG_PLAN_DIRECTORY}/evidence-{index:02d}.txt")],
        })
    return {
        "acceptanceCriteria": ["七项只读证据均由唯一 Tool occurrence 支撑且文件哈希不变"],
        "assumptions": ["七个 fixture 文件均位于当前 workspace"],
        "risks": ["有界 preview 不得被误认为 durable definition 被截断"],
        "verificationStrategy": "比对七项 Tool evidence、canonical Plan 与用户文件哈希",
        "steps": steps,
    }


class ScenarioProvider(FakeProvider):
    """Stateful deterministic Provider; it never contacts a non-loopback endpoint."""

    def __init__(self, scenario: str) -> None:
        self.scenario = scenario
        self.plan_index = 0
        self.tool_index = 0
        self.issued_tool_call_count = 0
        self.rejected_candidate_ids: list[str] = []
        self.awaiting_tool_result = False
        self.pending_tool_call_id: str | None = None
        self.pending_tool_call_ids: tuple[str, ...] = ()
        self.synthesis_index = 0
        self.context_checks: list[dict[str, bool]] = []
        self.network_fault_logical_turn = 12 if scenario == "auto-long-task" else None
        self.network_fault_injected = False
        self.plans, self.tools = self._script(scenario)
        super().__init__(self._inject_network_fault)

    def _inject_network_fault(self, handler: BaseHTTPRequestHandler,
                              attempt: dict) -> bool:
        if (self.network_fault_logical_turn is None
                or self.network_fault_injected
                or attempt["logicalTurn"] != self.network_fault_logical_turn
                or attempt["actualAttempt"] != 1):
            return False
        self.network_fault_injected = True
        self.annotate_attempt(attempt, "HTTP_503")
        handler._send(  # type: ignore[attr-defined]  # bounded loopback test seam
            {"error": {"message": "deterministic loopback interruption"}},
            status=503, headers={"Retry-After": "0"})
        return True

    @staticmethod
    def _script(scenario: str) -> tuple[list[dict], list[dict]]:
        def read(path: str) -> dict:
            return {"name": "read_file", "arguments": {"path": path}}

        if scenario == "manual-compact":
            parent = [read(f"context-{index:02d}.txt")
                      for index in range(1, MANUAL_CONTEXT_FILE_COUNT + 1)]
            child = [read("continue-check.txt")]
            return ([plan_for("Capture context", [(item["name"], item["arguments"]["path"])
                                                   for item in parent]),
                     plan_for("Continue context", [("read_file", "continue-check.txt")])],
                    parent + child)
        if scenario == "auto-long-task":
            tools = [read(f"fixtures/item-{index:02d}.txt") for index in range(1, 16)]
            return ([plan_for("Inspect fixture", [(item["name"], item["arguments"]["path"])
                                                  for item in tools])], tools)
        if scenario == "normal-long-plan-approval":
            tools = [read(f"{NORMAL_LONG_PLAN_DIRECTORY}/evidence-{index:02d}.txt")
                     for index in range(1, 8)]
            return ([normal_long_plan()], tools)
        if scenario in ("write-verify-rewind", "structured-verify-fail-closed"):
            tools = [read("target.txt"), {"name": "write_file", "arguments": {
                "path": "target.txt", "content": WRITE_AFTER, "overwrite": True}}]
            return ([plan_for("Verified edit", [("read_file", "target.txt"),
                                                ("write_file", "target.txt")],
                              verification_required=True)], tools)
        if scenario == "session-resume-continue":
            tools = [read("README.md"), read("CHILD.md")]
            return ([plan_for("Parent read", [("read_file", "README.md")]),
                     plan_for("Child read", [("read_file", "CHILD.md")])], tools)
        if scenario == "plan-tool-scope-sequential-deviation":
            planned = [read(f"scope-sequential/item-{index:02d}.txt")
                       for index in range(1, 6)]
            future = read("scope-sequential/item-04.txt")
            return ([plan_for("Sequential exact scope", [
                (item["name"], item["arguments"]["path"]) for item in planned])],
                    [planned[0], planned[1], future])
        if scenario == "plan-tool-scope-multi-tool-batch":
            calls = [read("scope-batch/alpha.txt"), read("scope-batch/beta.txt")]
            step = {
                "title": "Read exact batch",
                "definition": "Read alpha.txt and beta.txt in one exact atomic Tool batch",
                "dependsOn": [], "completionRule": "ALL_REQUIRED_EVIDENCE",
                "requiredEvidenceKinds": ["TOOL_CALL"], "effectClass": "READ",
                "toolRequirements": [
                    exact_path_requirement("batch-alpha", 0, "read_file",
                                           "scope-batch/alpha.txt"),
                    exact_path_requirement("batch-beta", 1, "read_file",
                                           "scope-batch/beta.txt"),
                ],
            }
            plan = {"acceptanceCriteria": ["Both exact batch reads succeed"],
                    "assumptions": [], "risks": [],
                    "verificationStrategy": "Count two distinct bound success slots",
                    "steps": [step]}
            return [plan], [{"batch": calls}]
        if scenario == "plan-evidence-semantic-count":
            paths = ("semantic-count/first.txt", "semantic-count/second.txt")
            step = {
                "title": "Read two advisory evidence files",
                "definition": "Read both safe files while Plan evidence remains non-authoritative",
                "dependsOn": [], "completionRule": "ALL_REQUIRED_EVIDENCE",
                "requiredEvidenceKinds": ["TOOL_CALL"], "effectClass": "READ",
                "toolRequirements": [
                    exact_path_requirement("semantic-first", 0, "read_file", paths[0]),
                    exact_path_requirement("semantic-second", 1, "read_file", paths[1]),
                ],
            }
            plan = {"acceptanceCriteria": ["Task completion does not depend on Plan evidence"],
                    "assumptions": [], "risks": [],
                    "verificationStrategy": "Confirm zero Strict evidence rows",
                    "steps": [step]}
            return [plan], [{"batch": [read(paths[0]), read(paths[1])]}]
        if scenario == "plan-v2-unfinished-fail-closed":
            return [plan_for("Legacy unfinished", [("read_file", "README.md")])], []
        raise ValueError(f"unsupported scripted scenario: {scenario}")

    @staticmethod
    def _completion(message: dict, finish: str, index: int) -> dict:
        return {"id": f"uat-scripted-{index}", "object": "chat.completion",
                "choices": [{"index": 0, "finish_reason": finish, "message": message}],
                "usage": {"prompt_tokens": 64, "completion_tokens": 24, "total_tokens": 88}}

    def _final_content(self, flat_messages: str) -> str:
        expected: tuple[str, ...] = ()
        if self.scenario == "manual-compact":
            expected = (MANUAL_MARKERS + (MANUAL_PROHIBITION, MANUAL_UNFINISHED)
                        if self.plan_index >= 2 else ())
        elif self.scenario == "auto-long-task":
            expected = LONG_MARKERS
        elif self.scenario == "normal-long-plan-approval":
            expected = NORMAL_LONG_PLAN_RESULT_MARKERS
        if expected:
            present = {marker: marker in flat_messages for marker in expected}
            self.context_checks.append(present)
            return ("Grounded final: " + "; ".join(expected)
                    if all(present.values()) else "CONTEXT_MARKERS_MISSING")
        return "The requested deterministic UAT work completed from Tool evidence."

    def _next_tool_completion(self, index: int) -> dict:
        if self.tool_index >= len(self.tools):
            raise RuntimeError("UAT_PROVIDER_TOOL_SCRIPT_EXHAUSTED")
        spec = self.tools[self.tool_index]
        self.tool_index += 1
        self.awaiting_tool_result = True
        calls = spec.get("batch", [spec])
        tool_calls = []
        call_ids = []
        for call in calls:
            self.issued_tool_call_count += 1
            call_id = (f"call_{self.scenario.replace('-', '_')}_"
                       f"{self.issued_tool_call_count:02d}")
            call_ids.append(call_id)
            if call.get("expectedAdmission", "").startswith("REJECTED_"):
                self.rejected_candidate_ids.append(call_id)
            tool_calls.append({
                "id": call_id, "type": "function", "function": {
                    "name": call["name"],
                    "arguments": json.dumps(call["arguments"], separators=(",", ":")),
                }})
        self.pending_tool_call_ids = tuple(call_ids)
        self.pending_tool_call_id = call_ids[0] if len(call_ids) == 1 else None
        return self._completion(
            {"role": "assistant", "content": "", "tool_calls": tool_calls},
            "tool_calls", index)

    def _task_tool_boundary_reached(self) -> bool:
        return ((self.scenario == "manual-compact"
                 and self.tool_index == MANUAL_CONTEXT_FILE_COUNT)
                or (self.scenario == "session-resume-continue"
                    and self.tool_index == 1))

    def response(self, request: dict, index: int) -> dict:
        operation = provider_operation(request)
        flat = json.dumps(request.get("messages", []), ensure_ascii=False)
        if operation == "PLAN":
            selected = self.plans[min(self.plan_index, len(self.plans) - 1)]
            self.plan_index += 1
            return self._completion({"role": "assistant", "content":
                                     json.dumps(selected, separators=(",", ":"))}, "stop", index)
        if operation == "FINAL_SYNTHESIS":
            self.synthesis_index += 1
            expected: tuple[str, ...] = ()
            if self.scenario == "manual-compact" and self.synthesis_index >= 2:
                expected = MANUAL_MARKERS + (MANUAL_PROHIBITION, MANUAL_UNFINISHED)
            elif self.scenario == "auto-long-task":
                expected = LONG_MARKERS
            elif self.scenario == "normal-long-plan-approval":
                expected = NORMAL_LONG_PLAN_RESULT_MARKERS
            present = {marker: marker in flat for marker in expected}
            if expected:
                self.context_checks.append(present)
            content = ("Grounded final: " + "; ".join(expected)
                       if all(present.values()) else "CONTEXT_MARKERS_MISSING")
            if not expected:
                content = "The requested deterministic UAT work completed from Tool evidence."
            return self._completion({"role": "assistant", "content": content}, "stop", index)
        if self.awaiting_tool_result:
            matching_results = {
                message.get("tool_call_id")
                for message in request.get("messages", [])
                if isinstance(message, dict) and message.get("role") == "tool"
            }
            matching_result = all(
                call_id in matching_results for call_id in self.pending_tool_call_ids
            ) and bool(self.pending_tool_call_ids)
            if not matching_result:
                raise RuntimeError("UAT_PROVIDER_PENDING_TOOL_RESULT_MISSING")
            self.awaiting_tool_result = False
            self.pending_tool_call_id = None
            self.pending_tool_call_ids = ()
            if self._task_tool_boundary_reached():
                return self._completion(
                    {"role": "assistant", "content": self._final_content(flat)},
                    "stop", index)
            if self.tool_index < len(self.tools):
                return self._next_tool_completion(index)
            return self._completion(
                {"role": "assistant", "content": self._final_content(flat)},
                "stop", index)
        if self.tool_index >= len(self.tools):
            return self._completion({"role": "assistant", "content":
                                     "No additional Tool call is authorized."}, "stop", index)
        return self._next_tool_completion(index)


def network_retry_evidence(provider: ScenarioProvider) -> dict:
    groups: dict[int, list[dict]] = {}
    for attempt in provider.request_attempts:
        groups.setdefault(attempt["logicalTurn"], []).append(attempt)
    retry_groups = [rows for rows in groups.values() if len(rows) > 1]
    injected = [rows for rows in retry_groups
                if any(row.get("outcome") == "HTTP_503" for row in rows)]
    selected = injected[0] if len(injected) == 1 else []
    attempts = [row["actualAttempt"] for row in selected]
    digests = {row["requestDigest"] for row in selected}
    generations = {row.get("contextGeneration") for row in selected
                   if row.get("contextGeneration") is not None}
    outcomes = [row.get("outcome") for row in selected]
    schema_complete = (
        provider.network_fault_injected
        and len(injected) == 1
        and len(retry_groups) == 1
        and attempts == [1, 2]
        and len(digests) == 1
        and len(generations) == 1
        and outcomes == ["HTTP_503", "HTTP_200"]
    )
    return {
        "schemaComplete": schema_complete,
        "faultInjected": provider.network_fault_injected,
        "logicalTurns": len(groups),
        "actualRequests": len(provider.request_attempts),
        "totalRetries": sum(max(0, len(rows) - 1) for rows in groups.values()),
        "retryLogicalTurn": selected[0]["logicalTurn"] if selected else None,
        "attempts": attempts,
        "requestDigests": sorted(digests),
        "contextGenerations": sorted(generations),
        "outcomes": outcomes,
    }


class PtySession:
    def __init__(self, argv: list[str], cwd: Path, env: dict[str, str]) -> None:
        env = dict(env)
        env["PYWINPTY_BACKEND"] = str(int(Backend.ConPTY))
        self.process = PtyProcess.spawn(argv, cwd=str(cwd), env=env,
                                        dimensions=(24, 80), backend=Backend.ConPTY)
        self.chunks: list[str] = []
        self.events: queue.Queue[str] = queue.Queue()
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()
        self.launched_java_pids: list[int] = []

    def _read(self) -> None:
        try:
            while True:
                text = self.process.read(4096)
                self.chunks.append(text)
                self.events.put(text)
        except (EOFError, OSError):
            return

    @property
    def transcript(self) -> str:
        return "".join(self.chunks)

    def checkpoint(self) -> int:
        return len(self.transcript)

    def wait_for(self, needle: str, timeout: float = 40.0) -> None:
        deadline = time.monotonic() + timeout
        while needle not in self.transcript:
            if time.monotonic() >= deadline:
                raise TimeoutError(f"wait-for-text timeout: {needle}")
            try:
                self.events.get(timeout=0.2)
            except queue.Empty:
                if not self.process.isalive():
                    raise RuntimeError(f"process exited before text: {needle}")

    def wait_for_since(self, needle: str, checkpoint: int, timeout: float = 40.0) -> int:
        deadline = time.monotonic() + timeout
        while needle not in self.transcript[checkpoint:]:
            if time.monotonic() >= deadline:
                raise TimeoutError(f"wait-for-text checkpoint timeout: {needle}")
            try:
                self.events.get(timeout=0.2)
            except queue.Empty:
                if not self.process.isalive():
                    raise RuntimeError(f"process exited before checkpointed text: {needle}")
        return self.transcript.index(needle, checkpoint) + len(needle)

    def wait_for_any_since(self, needles: tuple[str, ...], checkpoint: int,
                           timeout: float = 40.0) -> tuple[str, int]:
        deadline = time.monotonic() + timeout
        while True:
            transcript = self.transcript
            matches = [(transcript.find(needle, checkpoint), needle) for needle in needles]
            matches = [(index, needle) for index, needle in matches if index >= 0]
            if matches:
                index, needle = min(matches)
                return needle, index + len(needle)
            if time.monotonic() >= deadline:
                raise TimeoutError(f"wait-for-terminal timeout: {needles}")
            try:
                self.events.get(timeout=0.2)
            except queue.Empty:
                if not self.process.isalive():
                    raise RuntimeError(f"process exited before terminal marker: {needles}")

    def send_line(self, text: str) -> None:
        self.process.write(text + "\r")

    def ctrl_c(self) -> None:
        self.process.sendintr()

    def wait_exit(self, timeout: float = 20.0) -> int | None:
        deadline = time.monotonic() + timeout
        while self.process.isalive() and time.monotonic() < deadline:
            time.sleep(0.1)
        if self.process.isalive():
            raise TimeoutError("process exit timeout")
        return self.process.exitstatus

    def close(self) -> None:
        if self.process.isalive():
            self.process.close(force=True)

    def capture_java_children(self, dist: Path) -> None:
        """Capture only Java processes launched synchronously by this PTY shell."""
        observed = scoped_java_children(self.process.pid, dist)
        if not observed:
            raise ProcessProbeError("LAUNCHED_JAVA_CHILD_NOT_OBSERVED")
        self.launched_java_pids.extend(
            pid for pid in observed if pid not in self.launched_java_pids)


def write_config(workspace: Path, base_url: str, overrides: dict | None = None) -> None:
    state = workspace / ".paicli"
    state.mkdir(parents=True, exist_ok=True)
    config = {
        "provider": "openai", "model": "paicli-uat-model", "base_url": base_url,
        "api_key_env": "PAICLI_UAT_API_KEY", "context_window_tokens": 40960,
        "approval_mode": "on-request", "max_iterations": 6,
        "enable_plan": True, "enable_verify": False, "enable_repair": True,
        "enable_skills": False, "enable_hooks": False,
    }
    if overrides:
        config.update(overrides)
    (state / "config.json").write_text(json.dumps(config, indent=2), encoding="utf-8")


def state_summary(workspace: Path) -> dict:
    database = workspace / ".paicli" / "state" / "paicli.db"
    result = {
        "task": None, "run": None, "toolExecutions": 0, "toolCounts": {},
        "distinctToolCallIds": 0, "tasks": [], "runs": [], "threads": [],
        "planRevisions": 0, "approvedPlans": 0, "planRunBindings": 0,
        "planRevisionRows": [], "planStepDefinitions": [],
        "planToolRequirements": [], "planOccurrenceSlotBindings": [],
        "planEvidenceSlotBindings": [],
        "planEvidence": 0, "completionDecisions": 0, "userResults": 0,
        "snapshots": [], "proposals": [], "verifications": [],
        "repairAttempts": 0, "occurrences": [], "continuationEdges": [],
        "eventCount": 0, "eventMaxId": 0, "durableEventsContiguous": False,
        "contextGenerations": [], "contextSegments": [],
        "contextSourceReferences": [], "contextPointers": [],
        "eligibleContextPointers": [],
        "effectiveReductions": 0, "providerAttemptRows": 0, "providerRetryCount": 0,
    }
    if not database.exists():
        return result
    with closing(sqlite3.connect(database)) as connection:
        connection.row_factory = sqlite3.Row
        tables = {row[0] for row in connection.execute("select name from sqlite_master where type='table'")}
        if "task_identities" in tables:
            rows = connection.execute("""
                select task_id,thread_id,parent_task_id,status
                from task_identities order by created_at,task_id
                """).fetchall()
            result["tasks"] = [dict(row) for row in rows]
            result["task"] = rows[-1]["status"] if rows else None
        if "durable_runs" in tables:
            rows = connection.execute("""
                select run_id,task_id,thread_id,run_kind,resumed_from_run_id,terminal_status
                from durable_runs order by created_at,run_id
                """).fetchall()
            result["runs"] = [dict(row) for row in rows]
            result["run"] = rows[-1]["terminal_status"] if rows else None
        if "work_threads" in tables:
            result["threads"] = [dict(row) for row in connection.execute(
                "select thread_id,status from work_threads order by created_at,thread_id")]
        if "tool_calls" in tables:
            grouped = connection.execute(
                "select tool_name,count(*) as n from tool_calls group by tool_name").fetchall()
            result["toolCounts"] = {row["tool_name"]: row["n"] for row in grouped}
            result["toolExecutions"] = result["toolCounts"].get("read_file", 0)
            result["distinctToolCallIds"] = connection.execute(
                "select count(distinct tool_call_id) from tool_calls").fetchone()[0]
        scalar_tables = {
            "planRevisions": "plan_revisions",
            "approvedPlans": "plan_approvals",
            "planRunBindings": "plan_run_bindings",
            "planEvidence": "plan_evidence",
            "completionDecisions": "task_completion_decisions",
            "userResults": "task_user_results",
            "repairAttempts": "repair_attempts",
        }
        for field, table in scalar_tables.items():
            if table in tables:
                sql = f"select count(*) from {table}"
                if table == "plan_approvals":
                    sql += " where decision='APPROVED'"
                result[field] = connection.execute(sql).fetchone()[0]
        if "plan_revisions" in tables:
            result["planRevisionRows"] = [dict(row) for row in connection.execute("""
                select r.task_id,r.plan_revision_id,r.revision_number,r.goal,
                       r.canonical_hash,l.state as lifecycle
                from plan_revisions r
                left join plan_revision_lifecycle l
                  on l.task_id=r.task_id and l.plan_revision_id=r.plan_revision_id
                order by r.created_at,r.plan_revision_id
                """)]
        if "plan_step_definitions" in tables:
            result["planStepDefinitions"] = [dict(row) for row in connection.execute("""
                select task_id,plan_revision_id,step_revision_id,logical_step_id,
                       step_order,title,definition,canonical_hash,completion_rule,
                       required_evidence_kinds,effect_class
                from plan_step_definitions
                order by plan_revision_id,step_order
                """)]
        if "plan_tool_requirements" in tables:
            result["planToolRequirements"] = [dict(row) for row in connection.execute("""
                select task_id,plan_revision_id,step_revision_id,requirement_id,
                       requirement_order,tool_name,effect_class,selector_kind,
                       selector_digest,min_successes,max_occurrences,freshness_policy
                from plan_tool_requirements
                order by plan_revision_id,step_revision_id,requirement_order
                """)]
        if "plan_occurrence_slot_bindings" in tables:
            result["planOccurrenceSlotBindings"] = [dict(row) for row in connection.execute("""
                select task_id,plan_revision_id,step_revision_id,requirement_id,
                       slot_ordinal,run_id,attempt_number,occurrence_id,tool_call_id,
                       selector_digest
                from plan_occurrence_slot_bindings
                order by plan_revision_id,step_revision_id,requirement_id,slot_ordinal
                """)]
        if "plan_evidence_slot_bindings" in tables:
            result["planEvidenceSlotBindings"] = [dict(row) for row in connection.execute("""
                select task_id,plan_revision_id,step_revision_id,requirement_id,
                       slot_ordinal,run_id,attempt_number,occurrence_id,tool_call_id,
                       evidence_kind,producer_kind,selector_digest
                from plan_evidence_slot_bindings
                order by plan_revision_id,step_revision_id,requirement_id,slot_ordinal
                """)]
        if "file_snapshots" in tables:
            result["snapshots"] = [dict(row) for row in connection.execute("""
                select snapshot_id,task_id,owner_run_id,file_path,operation,status,
                       sha256_before,sha256_after
                from file_snapshots order by created_at,snapshot_id
                """)]
        if "interactive_edit_proposals" in tables:
            result["proposals"] = [dict(row) for row in connection.execute("""
                select proposal_id,task_id,run_id,relative_path,status,snapshot_id
                from interactive_edit_proposals order by created_at,proposal_id
                """)]
        if "verification_runs" in tables:
            result["verifications"] = [dict(row) for row in connection.execute("""
                select verification_id,run_id,task_id,repair_attempt,status,exit_code
                from verification_runs order by started_at,verification_id
                """)]
        if "side_effect_occurrences" in tables:
            result["occurrences"] = [dict(row) for row in connection.execute("""
                select occurrence_id,task_id,run_id,tool_call_id,operation_type,status,
                       target,external_outcome,started_at,completed_at
                from side_effect_occurrences order by started_at,occurrence_id
                """)]
        if "provider_call_telemetry" in tables:
            telemetry = connection.execute("""
                select count(*) as attempts,coalesce(sum(retry_count),0) as retries
                from provider_call_telemetry
                """).fetchone()
            result["providerAttemptRows"] = telemetry["attempts"]
            result["providerRetryCount"] = telemetry["retries"]
        if "task_continuation_edges" in tables:
            result["continuationEdges"] = [dict(row) for row in connection.execute("""
                select thread_id,parent_task_id,child_task_id,edge_type
                from task_continuation_edges order by created_at,child_task_id
                """)]
        if "events" in tables:
            row = connection.execute(
                "select count(*) as n,coalesce(max(id),0) as max_id from events").fetchone()
            result["eventCount"], result["eventMaxId"] = row["n"], row["max_id"]
            result["durableEventsContiguous"] = row["n"] == row["max_id"]
        if "context_generations" in tables:
            result["contextGenerations"] = [dict(row) for row in connection.execute("""
                select context_generation_id,task_id,run_id,generation_kind,operation_kind,
                       lifecycle,input_estimate,tool_schema_estimate,output_reserve,safety_margin,
                       source_context_generation_id,created_at
                from context_generations order by created_at,context_generation_id
                """)]
            result["effectiveReductions"] = connection.execute("""
                select count(*) from context_generations g
                join context_generations source
                  on source.context_generation_id=g.source_context_generation_id
                where g.generation_kind='COMPACTED'
                  and g.lifecycle in ('READY','SUPERSEDED')
                  and (g.input_estimate+g.tool_schema_estimate+g.output_reserve+g.safety_margin)
                    < (source.input_estimate+source.tool_schema_estimate
                       +source.output_reserve+source.safety_margin)
                """).fetchone()[0]
        if "context_segments" in tables:
            result["contextSegments"] = [dict(row) for row in connection.execute("""
                select context_generation_id,ordinal,segment_kind,compactability,content_hash
                from context_segments order by context_generation_id,ordinal
                """)]
        if "context_source_references" in tables:
            result["contextSourceReferences"] = [dict(row) for row in connection.execute("""
                select context_generation_id,ordinal,source_type,source_id,source_hash,source_order
                from context_source_references
                order by context_generation_id,ordinal,source_order
                """)]
        if "context_generation_pointers" in tables:
            result["contextPointers"] = [dict(row) for row in connection.execute("""
                select task_id,run_id,operation_kind,active_context_generation_id,pointer_version
                from context_generation_pointers order by task_id,run_id,operation_kind
                """)]
            if "context_segments" in tables:
                result["eligibleContextPointers"] = [dict(row) for row in connection.execute("""
                    select p.task_id,p.run_id,p.operation_kind,
                           p.active_context_generation_id,p.pointer_version
                    from context_generation_pointers p
                    where exists (
                      select 1 from context_segments s
                      where s.context_generation_id=p.active_context_generation_id
                        and s.compactability='COMPACTABLE')
                    order by p.task_id,p.run_id,p.operation_kind
                    """)]
    return result


def durable_user_result_marker_evidence(workspace: Path,
                                        markers: tuple[str, ...]) -> dict:
    """Verify final-result markers from the durable completion gate without disclosing content."""
    database = workspace / ".paicli" / "state" / "paicli.db"
    evidence = {
        "resultCount": 0,
        "allMarkersPresent": False,
        "contentHashesVerified": False,
        "allValidated": False,
        "allRendered": False,
    }
    if not database.exists():
        return evidence
    uri = database.resolve().as_uri() + "?mode=ro"
    with closing(sqlite3.connect(uri, uri=True)) as connection:
        rows = connection.execute("""
            select content,content_hash,validation_status,render_status
            from task_user_results order by created_at,task_id,run_id
            """).fetchall()
    evidence["resultCount"] = len(rows)
    if len(rows) != 1:
        return evidence
    content, content_hash, validation_status, render_status = rows[0]
    evidence["allMarkersPresent"] = all(marker in content for marker in markers)
    evidence["contentHashesVerified"] = (
        hashlib.sha256(content.encode("utf-8")).hexdigest() == content_hash)
    evidence["allValidated"] = validation_status == "VALID"
    evidence["allRendered"] = render_status == "RENDERED"
    return evidence


def verification_failure_result_envelope(transcript: str) -> bool:
    return ("[Task] Completed" not in transcript
            and "[Verify] failed" in transcript
            and "[Result]" in transcript
            and "Task did not complete." in transcript
            and "Reason: Verification failed and repair is disabled" in transcript
            and "[Error] TASK_FAILED: Verification failed and repair is disabled" in transcript
            and "[Task] Failed" in transcript)


def active_base_compaction_closure(state: dict) -> dict:
    generations = {row["context_generation_id"]: row
                   for row in state.get("contextGenerations", [])}
    segments_by_generation: dict[str, list[dict]] = {}
    segment_by_identity: dict[tuple[str, int], dict] = {}
    for row in state.get("contextSegments", []):
        generation_id = row["context_generation_id"]
        segments_by_generation.setdefault(generation_id, []).append(row)
        segment_by_identity[(generation_id, row["ordinal"])] = row
    refs_by_segment: dict[tuple[str, int], list[dict]] = {}
    for row in state.get("contextSourceReferences", []):
        refs_by_segment.setdefault(
            (row["context_generation_id"], row["ordinal"]), []).append(row)

    def locator(reference: dict) -> tuple[str, int] | None:
        source_id, separator, ordinal_text = reference["source_id"].rpartition(":")
        if not separator or not ordinal_text.isdigit():
            return None
        return source_id, int(ordinal_text)

    compacted_sources: set[str] = set()
    validated: set[tuple[str, int]] = set()

    def validate_segment(generation_id: str, ordinal: int,
                         expected_hash: str | None,
                         visiting: set[tuple[str, int]]) -> bool:
        identity = (generation_id, ordinal)
        segment = segment_by_identity.get(identity)
        if segment is None or (expected_hash is not None
                               and segment.get("content_hash") != expected_hash):
            return False
        if identity in validated:
            return True
        if identity in visiting:
            return False
        generation = generations.get(generation_id)
        refs = refs_by_segment.get(identity, [])
        if (generation is None or not refs
                or generation.get("lifecycle") not in ("READY", "SUPERSEDED")):
            return False
        if generation.get("generation_kind") == "COMPACTED":
            compacted_sources.add(generation_id)
        visiting.add(identity)
        try:
            for reference in refs:
                if reference.get("source_type") not in (
                        "CONTEXT_SEGMENT", "SOURCE_SEGMENT", "PARENT_CONTEXT_SEGMENT"):
                    continue
                target = locator(reference)
                if target is None or not validate_segment(
                        target[0], target[1], reference.get("source_hash"), visiting):
                    return False
        finally:
            visiting.remove(identity)
        validated.add(identity)
        return True

    active_bases: list[str] = []
    invalid_bases: list[str] = []
    for pointer in state.get("contextPointers", []):
        active_id = pointer["active_context_generation_id"]
        active = generations.get(active_id)
        if (active is None or active.get("generation_kind") != "BASE"
                or active.get("lifecycle") != "READY"):
            continue
        segments = segments_by_generation.get(active_id, [])
        if not segments:
            continue
        active_bases.append(active_id)
        if not all(validate_segment(active_id, row["ordinal"],
                                    row.get("content_hash"), set())
                   for row in segments):
            invalid_bases.append(active_id)
    valid = bool(active_bases) and not invalid_bases and bool(compacted_sources)
    return {
        "valid": valid,
        "activeBaseGenerations": sorted(active_bases),
        "compactedSourceGenerations": sorted(compacted_sources),
        "invalidActiveBaseGenerations": sorted(invalid_bases),
    }


def distinct_reads_before_first_successful_compact(state: dict) -> int:
    """Count durable completed reads before the first observable successful compact."""
    successful = [row for row in state.get("contextGenerations", [])
                  if row.get("generation_kind") == "COMPACTED"
                  and row.get("lifecycle") in ("READY", "SUPERSEDED")
                  and row.get("created_at")]
    if not successful:
        return 0
    first_compact = min(
        datetime.fromisoformat(row["created_at"].replace("Z", "+00:00"))
        for row in successful)
    completed = set()
    for row in state.get("occurrences", []):
        completed_at = row.get("completed_at")
        if (row.get("operation_type") != "TOOL"
                or row.get("target") != "read_file"
                or row.get("status") != "COMPLETED"
                or not row.get("tool_call_id")
                or not completed_at):
            continue
        if datetime.fromisoformat(completed_at.replace("Z", "+00:00")) <= first_compact:
            completed.add(row["tool_call_id"])
    return len(completed)


def create_fixture(name: str, workspace: Path) -> dict:
    metadata: dict = {}
    if name == "manual-compact":
        marker_by_index = dict(zip(MANUAL_MARKER_INDEXES, MANUAL_MARKERS))
        for index in range(1, MANUAL_CONTEXT_FILE_COUNT + 1):
            filename = f"context-{index:02d}.txt"
            marker = marker_by_index.get(index, f"MANUAL-CONTEXT-{index:02d}")
            protected = (MANUAL_PROHIBITION if index == MANUAL_MARKER_INDEXES[1]
                         else MANUAL_UNFINISHED
                         if index == MANUAL_MARKER_INDEXES[2] else "")
            (workspace / filename).write_text(
                marker + "\n" + protected + "\n"
                + ("bounded context material " * 180) + "\n",
                encoding="utf-8")
        (workspace / "continue-check.txt").write_text(
            "CONTINUATION-GATE-ONLY; recover prior facts from committed context.\n",
            encoding="utf-8")
    elif name == "auto-long-task":
        fixtures = workspace / "fixtures"
        fixtures.mkdir()
        marker_by_index = {1: LONG_MARKERS[0], 8: LONG_MARKERS[1], 15: LONG_MARKERS[2]}
        for index in range(1, 16):
            marker = marker_by_index.get(index, f"LONG-FIXTURE-{index:02d}")
            repetitions = 1500 if index in (6, 12) else 260
            (fixtures / f"item-{index:02d}.txt").write_text(
                marker + "\n" + ((f"deterministic-{index:02d}-payload "
                                   * repetitions) + "\n"),
                encoding="utf-8")
    elif name == "normal-long-plan-approval":
        fixtures = workspace / NORMAL_LONG_PLAN_DIRECTORY
        fixtures.mkdir()
        for index, marker in enumerate(NORMAL_LONG_PLAN_RESULT_MARKERS, 1):
            (fixtures / f"evidence-{index:02d}.txt").write_text(
                f"{marker}\n这是第 {index} 项确定性只读 Plan 证据。\n",
                encoding="utf-8")
    elif name in ("write-verify-rewind", "structured-verify-fail-closed"):
        target = workspace / "target.txt"
        target.write_text(WRITE_BEFORE, encoding="utf-8")
        expected = (WRITE_AFTER if name == "write-verify-rewind"
                    else "never-match-uat\n")
        (workspace / "expected-verification.bin").write_bytes(
            expected.encode("utf-8"))
        metadata["target"] = str(target)
        metadata["h0"] = sha256(target)
    elif name == "session-resume-continue":
        (workspace / "CHILD.md").write_text(
            "CHILD-CONTINUATION-MARKER documents the child follow-up.\n", encoding="utf-8")
    elif name == "plan-tool-scope-sequential-deviation":
        fixtures = workspace / "scope-sequential"
        fixtures.mkdir()
        for index in range(1, 6):
            (fixtures / f"item-{index:02d}.txt").write_text(
                f"SEQUENTIAL-EXACT-{index:02d}\n", encoding="utf-8")
    elif name == "plan-tool-scope-multi-tool-batch":
        fixtures = workspace / "scope-batch"
        fixtures.mkdir()
        (fixtures / "alpha.txt").write_text("BATCH-ALPHA\n", encoding="utf-8")
        (fixtures / "beta.txt").write_text("BATCH-BETA\n", encoding="utf-8")
    elif name == "plan-evidence-semantic-count":
        fixtures = workspace / "semantic-count"
        fixtures.mkdir()
        (fixtures / "first.txt").write_text("ADVISORY-FIRST\n", encoding="utf-8")
        (fixtures / "second.txt").write_text("ADVISORY-SECOND\n", encoding="utf-8")
    return metadata


def private_file_hits(paths: list[Path]) -> list[str]:
    hits: list[str] = []
    for root in paths:
        candidates = [root] if root.is_file() else (
            [path for path in root.rglob("*") if path.is_file()] if root.exists() else [])
        for path in candidates:
            try:
                data = path.read_bytes()
            except OSError:
                continue
            for marker in PRIVATE_MARKERS:
                if (marker.encode("utf-8") in data
                        or marker.encode("utf-16-le") in data):
                    hits.append(f"{path.name}:{marker}")
    return sorted(set(hits))


def collect_failure_evidence(workspace: Path, before: dict[str, str],
                             session, dist: Path) -> dict:
    """Best-effort durable evidence for a failed flow; never changes its status."""
    errors: list[str] = []
    transcript = "" if session is None else session.transcript
    trace = dist / "diagnostics" / "paicli-stage-trace.log"
    try:
        state = state_summary(workspace)
    except Exception as error:
        state = None
        errors.append(f"STATE:{type(error).__name__}")
    try:
        after = user_hashes(workspace)
    except Exception as error:
        after = None
        errors.append(f"FILES:{type(error).__name__}")
    transcript_hits = [marker for marker in PRIVATE_MARKERS if marker in transcript]
    try:
        disk_hits = private_file_hits([workspace / ".paicli", trace])
    except Exception as error:
        disk_hits = []
        errors.append(f"PRIVATE_SCAN:{type(error).__name__}")
    tool_counts = {} if state is None else state["toolCounts"]
    return {
        "toolExecutions": sum(tool_counts.values()),
        "toolCounts": tool_counts,
        "filesChanged": None if after is None else before != after,
        "taskState": None if state is None else state["task"],
        "runState": None if state is None else state["run"],
        "privateMetadataHits": sorted(set(transcript_hits + disk_hits)),
        "stateEvidence": state,
        "transcript": transcript,
        "trace": str(trace),
        "evidenceCollectionErrors": errors,
    }


def residual_java(dist: Path) -> list[int]:
    escaped = str(dist).replace("'", "''")
    script = ("$ErrorActionPreference='Stop'; "
              "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" -ErrorAction Stop | "
              f"Where-Object {{ $_.CommandLine -like '*{escaped}*' }} | "
              "ForEach-Object { $_.ProcessId }")
    try:
        probe = subprocess.run(["powershell.exe", "-NoProfile", "-Command", script],
                               capture_output=True, text=True, timeout=10, check=False)
    except subprocess.TimeoutExpired as error:
        raise ProcessProbeError("TIMEOUT") from error
    except OSError as error:
        raise ProcessProbeError("LAUNCH_ERROR") from error
    diagnostic = (probe.stderr or "").strip()
    if probe.returncode != 0 or diagnostic:
        normalized = diagnostic.lower()
        access_denied = ("access is denied" in normalized
                         or "拒绝访问" in normalized
                         or "0x80041003" in normalized)
        raise ProcessProbeError(
            "ACCESS_DENIED" if access_denied else
            "ERROR_STREAM" if probe.returncode == 0 else "NON_ZERO_EXIT",
            probe.returncode)
    return [int(value) for value in probe.stdout.split() if value.isdigit()]


class _ProcessEntry32W(ctypes.Structure):
    _fields_ = [
        ("dwSize", wintypes.DWORD), ("cntUsage", wintypes.DWORD),
        ("th32ProcessID", wintypes.DWORD), ("th32DefaultHeapID", ctypes.c_size_t),
        ("th32ModuleID", wintypes.DWORD), ("cntThreads", wintypes.DWORD),
        ("th32ParentProcessID", wintypes.DWORD), ("pcPriClassBase", wintypes.LONG),
        ("dwFlags", wintypes.DWORD), ("szExeFile", wintypes.WCHAR * 260),
    ]


def process_snapshot() -> list[tuple[int, int, str]]:
    snapshot = ctypes.windll.kernel32.CreateToolhelp32Snapshot(0x00000002, 0)
    invalid_handle = ctypes.c_void_p(-1).value
    if snapshot == invalid_handle:
        raise ProcessProbeError("TOOLHELP_SNAPSHOT_FAILED")
    entry = _ProcessEntry32W()
    entry.dwSize = ctypes.sizeof(entry)
    rows: list[tuple[int, int, str]] = []
    try:
        available = ctypes.windll.kernel32.Process32FirstW(
            snapshot, ctypes.byref(entry))
        while available:
            rows.append((int(entry.th32ProcessID),
                         int(entry.th32ParentProcessID), entry.szExeFile))
            available = ctypes.windll.kernel32.Process32NextW(
                snapshot, ctypes.byref(entry))
    finally:
        ctypes.windll.kernel32.CloseHandle(snapshot)
    return rows


def scoped_java_children(parent_pid: int, dist: Path) -> list[int]:
    del dist  # Exact parent ownership is the scope; no foreign command lines are read.
    return [pid for pid, observed_parent, name in process_snapshot()
            if observed_parent == parent_pid and name.lower() == "java.exe"]


def running_process_ids(process_ids: list[int]) -> list[int]:
    expected = set(process_ids)
    return sorted(pid for pid, _, _ in process_snapshot() if pid in expected)


def cleanup_scoped_java(process_ids: list[int]) -> None:
    for process_id in process_ids:
        subprocess.run(["powershell.exe", "-NoProfile", "-Command",
                        f"Stop-Process -Id {process_id} -Force -ErrorAction SilentlyContinue"],
                       capture_output=True, text=True, timeout=10, check=False)


def process_cleanup_evidence(dist: Path,
                             launched_java_pids: list[int] | None = None) -> dict:
    """Probe, clean only scoped Java, and preserve whether absence was observable."""
    before_cleanup = None
    after_cleanup = None
    error_category = None
    return_code = None
    try:
        captured = sorted(set(launched_java_pids or []))
        before_cleanup = (running_process_ids(captured) if captured
                          else residual_java(dist))
        if before_cleanup:
            cleanup_scoped_java(before_cleanup)
            time.sleep(0.5)
        after_cleanup = (running_process_ids(captured) if captured
                         else residual_java(dist))
    except ProcessProbeError as error:
        error_category = error.category
        return_code = error.return_code
    except Exception:
        error_category = "UNEXPECTED_ERROR"
    succeeded = error_category is None
    return {
        "processProbeSucceeded": succeeded,
        "capturedLaunchedJavaPids": sorted(set(launched_java_pids or [])),
        "residualJavaBeforeCleanup": before_cleanup,
        "residualJavaAfterCleanup": after_cleanup,
        "processProbeErrorCategory": error_category,
        "processProbeReturnCode": return_code,
        "noResidualJava": (succeeded
                           and before_cleanup == []
                           and after_cleanup == []),
    }


def wait_for_task_terminal(session: PtySession, checkpoint: int, expected: str,
                           timeout: float = 120.0) -> int:
    try:
        terminal_end = session.wait_for_since(expected, checkpoint, timeout)
        return session.wait_for_since("paicli [ready]>", terminal_end, 30)
    except (TimeoutError, RuntimeError) as error:
        raise NotReached(f"required terminal stage was not reached: {expected}") from error


def submit_and_approve(session: PtySession, instruction: str,
                       timeout: float = 60.0) -> int:
    checkpoint = session.checkpoint()
    session.send_line(instruction)
    try:
        session.wait_for_since(IMMUTABLE_PLAN_APPROVAL_PROMPT, checkpoint, timeout)
    except (TimeoutError, RuntimeError) as error:
        raise NotReached("structured Plan approval was not reached") from error
    approved = session.checkpoint()
    session.send_line("y")
    return approved


def run_v2_unfinished_flow(session: PtySession, workspace: Path,
                           argv: list[str], env: dict[str, str]) -> tuple[dict, PtySession]:
    """Crash an unfinished seeded V2 Task, then prove both continuation paths fail closed."""
    checkpoint = session.checkpoint()
    session.send_line(
        "Prepare an exact read-only Plan for README.md and wait for explicit approval.")
    try:
        session.wait_for_since(IMMUTABLE_PLAN_APPROVAL_PROMPT, checkpoint, 60)
    except (TimeoutError, RuntimeError) as error:
        raise NotReached("V2 seed source Task did not reach Plan approval") from error
    seeded = seed_unfinished_v2_plan(workspace)
    seeded_state = state_summary(workspace)
    first_transcript = session.transcript
    session.close()
    if not session.launched_java_pids:
        raise NotReached("V2 seed source Java child was not captured")
    remaining = running_process_ids(session.launched_java_pids)
    if remaining:
        cleanup_scoped_java(remaining)
        remaining = running_process_ids(session.launched_java_pids)
    if remaining:
        raise NotReached("V2 seed source process tree did not terminate before restart")

    restarted = PtySession(argv, workspace, env)
    try:
        restarted.wait_for_any_since(V2_RESTART_PROMPTS, 0, 30)
        restarted.capture_java_children(Path(argv[-1]).resolve().parent.parent)
        before_resume = state_summary(workspace)
        resume_checkpoint = restarted.checkpoint()
        restarted.send_line("/resume --last")
        try:
            resume_end = restarted.wait_for_since(
                "Run /task recover to resume the Head Task.", resume_checkpoint, 45)
            restarted.wait_for_any_since(V2_RESTART_PROMPTS, resume_end, 30)
        except (TimeoutError, RuntimeError) as error:
            raise NotReached("/resume --last did not bind the unfinished V2 Saved Session") from error
        after_resume = state_summary(workspace)

        continue_checkpoint = restarted.checkpoint()
        restarted.send_line(
            "/continue Continue the unfinished legacy V2 read without widening its scope.")
        try:
            continue_code_end = restarted.wait_for_since(
                "PLAN_TOOL_SCOPE_UNAVAILABLE", continue_checkpoint, 45)
            restarted.wait_for_any_since(V2_RESTART_PROMPTS, continue_code_end, 30)
        except (TimeoutError, RuntimeError) as error:
            raise NotReached("/continue did not expose PLAN_TOOL_SCOPE_UNAVAILABLE") from error
        after_continue = state_summary(workspace)

        recover_checkpoint = restarted.checkpoint()
        restarted.send_line("/task recover")
        try:
            recover_code_end = restarted.wait_for_since(
                "PLAN_TOOL_SCOPE_UNAVAILABLE", recover_checkpoint, 45)
            restarted.wait_for_any_since(V2_RESTART_PROMPTS, recover_code_end, 30)
        except (TimeoutError, RuntimeError) as error:
            raise NotReached("/task recover did not expose PLAN_TOOL_SCOPE_UNAVAILABLE") from error
        after_recover = state_summary(workspace)
        restarted.send_line("/exit")
        exit_code = restarted.wait_exit()
        return ({
            "seeded": seeded, "seededState": seeded_state,
            "beforeResume": before_resume, "afterResume": after_resume,
            "afterRecover": after_recover, "afterContinue": after_continue,
            "firstTranscript": first_transcript,
            "resumeBoundWithoutExecution": True, "recoverTypedRejection": True,
            "continueTypedRejection": True,
            "exitCode": exit_code,
        }, restarted)
    except Exception:
        session.chunks.append("\n--- V2 RESTART TRANSCRIPT ---\n")
        session.chunks.append(restarted.transcript)
        restarted.close()
        raise


def approve_proposed_edit(session: PtySession, checkpoint: int,
                          timeout: float = 60.0) -> int:
    """Approve the distinct effectful edit gate after immutable Plan approval."""
    try:
        session.wait_for_since(EDIT_APPROVAL_PROMPT, checkpoint, timeout)
    except (TimeoutError, RuntimeError) as error:
        raise NotReached("proposed edit approval was not reached") from error
    approved = session.checkpoint()
    session.send_line("y")
    return approved


def extended_config(name: str) -> dict:
    common = {"max_iterations": 8, "enable_repair": True}
    if name == "manual-compact":
        common.update({"max_iterations": MANUAL_CONTEXT_FILE_COUNT + 2})
    if name == "auto-long-task":
        common.update({"max_iterations": 20, "context_window_tokens": 22000})
    if name == "write-verify-rewind":
        common.update({
            "verification_command":
                "fc /b target.txt expected-verification.bin >nul",
            "verification_timeout_seconds": 20, "enable_verify": True,
        })
    if name == "structured-verify-fail-closed":
        common.update({
            "verification_command":
                "fc /b target.txt expected-verification.bin >nul",
            "verification_timeout_seconds": 20, "enable_verify": True,
            "max_repair_attempts": 1, "enable_repair": False,
        })
    return common


def run_extended_flow(name: str, session: PtySession, workspace: Path,
                      provider: ScenarioProvider, fixture: dict) -> dict:
    metadata: dict = {}
    if name == "manual-compact":
        approved = submit_and_approve(
            session,
            f"Inspect all {MANUAL_CONTEXT_FILE_COUNT} context fixture files in numeric order. "
            "Preserve the observed facts, "
            "the no-mutation prohibition, and the unfinished follow-up goal. Do not modify files.")
        wait_for_task_terminal(session, approved, "[Task] Completed", 150)
        metadata["beforeCompact"] = state_summary(workspace)
        compact_checkpoint = session.checkpoint()
        session.send_line("/compact")
        try:
            compact_end = session.wait_for_since("Context compacted", compact_checkpoint, 60)
            session.wait_for_since("paicli [ready]>", compact_end, 30)
        except (TimeoutError, RuntimeError) as error:
            raise NotReached("manual compact did not emit Context compacted") from error
        metadata["afterCompact"] = state_summary(workspace)
        child_approval = submit_and_approve(
            session,
            "/continue Using committed context, report the first, middle, and last observed "
            "markers, the prohibition, and the unfinished goal. Do not modify files.")
        wait_for_task_terminal(session, child_approval, "[Task] Completed", 120)
    elif name == "auto-long-task":
        metadata["beforeTask"] = state_summary(workspace)
        approved = submit_and_approve(
            session,
            "Inspect all 15 files under fixtures in numeric order and produce one grounded "
            "summary after every planned step completes. Do not modify any file.", 90)
        wait_for_task_terminal(session, approved, "[Task] Completed", 300)
    elif name == "normal-long-plan-approval":
        task_checkpoint = session.checkpoint()
        session.send_line(
            "按顺序读取 normal-long-plan 下的七个证据文件，完整执行七步中文 Plan，"
            "依据每项 Tool evidence 汇总结果；不要修改任何文件。")
        try:
            preview_end = session.wait_for_since(
                IMMUTABLE_PLAN_APPROVAL_PROMPT, task_checkpoint, 90)
        except (TimeoutError, RuntimeError) as error:
            raise NotReached("seven-step approval preview was not reached") from error
        metadata["approvalPreview"] = session.transcript[task_checkpoint:preview_end]

        detail_checkpoint = session.checkpoint()
        session.send_line("d")
        try:
            detail_end = session.wait_for_since(
                IMMUTABLE_PLAN_APPROVAL_PROMPT, detail_checkpoint, 60)
        except (TimeoutError, RuntimeError) as error:
            raise NotReached("canonical approval details were not reached") from error
        metadata["approvalDetails"] = session.transcript[detail_checkpoint:detail_end]
        approved = session.checkpoint()
        session.send_line("y")
        wait_for_task_terminal(session, approved, "[Task] Completed", 180)

        summary_checkpoint = session.checkpoint()
        session.send_line("/plan")
        try:
            summary_marker = session.wait_for_since(
                "Plan revision:", summary_checkpoint, 30)
            summary_end = session.wait_for_since(
                "paicli [ready]>", summary_marker, 30)
        except (TimeoutError, RuntimeError) as error:
            raise NotReached("bounded /plan summary was not reached") from error
        metadata["planSummary"] = session.transcript[summary_checkpoint:summary_end]

        details_checkpoint = session.checkpoint()
        session.send_line("/plan --details")
        try:
            details_marker = session.wait_for_since(
                "Steps (7):", details_checkpoint, 30)
            details_end = session.wait_for_since(
                "paicli [ready]>", details_marker, 30)
        except (TimeoutError, RuntimeError) as error:
            raise NotReached("durable /plan --details output was not reached") from error
        metadata["planDetails"] = session.transcript[details_checkpoint:details_end]
    elif name == "write-verify-rewind":
        approved = submit_and_approve(
            session,
            "Read target.txt, replace its exact content with the approved UAT value, run the "
            "configured verification, and report the verified edit.", 60)
        edit_approved = approve_proposed_edit(session, approved, 90)
        wait_for_task_terminal(session, edit_approved, "[Task] Completed", 150)
        target = Path(fixture["target"])
        metadata["h1"] = sha256(target)
        rewind_checkpoint = session.checkpoint()
        session.send_line("/rewind")
        try:
            selection = session.wait_for_since(
                "Select snapshot number or ID (Enter=cancel):", rewind_checkpoint, 30)
            session.send_line("1")
            confirmation = session.wait_for_since(
                REWIND_CONFIRMATION_PROMPT, selection, 30)
            session.send_line("y")
            rewind_end = session.wait_for_since("Rewind completed:", confirmation, 30)
            session.wait_for_since("paicli [ready]>", rewind_end, 30)
        except (TimeoutError, RuntimeError) as error:
            raise NotReached("snapshot rewind interaction was not reached") from error
        metadata["hAfterRewind"] = sha256(target)
    elif name == "structured-verify-fail-closed":
        approved = submit_and_approve(
            session,
            "Read target.txt, apply the approved UAT edit, and obey the configured structured "
            "verification result. Do not claim success if verification fails.", 60)
        edit_approved = approve_proposed_edit(session, approved, 90)
        wait_for_task_terminal(session, edit_approved, "[Task] Failed", 150)
    elif name == "session-resume-continue":
        approved = submit_and_approve(
            session, "Read README.md and report its actual content without changing files.")
        wait_for_task_terminal(session, approved, "[Task] Completed", 120)
        metadata["parentState"] = state_summary(workspace)
        command = session.checkpoint()
        session.send_line("/sessions")
        sessions_end = session.wait_for_since("Saved Sessions:", command, 30)
        session.wait_for_since("paicli [ready]>", sessions_end, 30)
        command = session.checkpoint()
        session.send_line("/new")
        new_end = session.wait_for_since("Saved Session binding cleared.", command, 30)
        session.wait_for_since("paicli [ready]>", new_end, 30)
        command = session.checkpoint()
        session.send_line("/resume --last")
        resume_end = session.wait_for_since("Thread:", command, 30)
        session.wait_for_since("paicli [ready]>", resume_end, 30)
        metadata["afterResume"] = state_summary(workspace)
        child_approval = submit_and_approve(
            session, "/continue Read CHILD.md once and report the child continuation marker.")
        wait_for_task_terminal(session, child_approval, "[Task] Completed", 120)
    elif name == "plan-tool-scope-sequential-deviation":
        approved = submit_and_approve(
            session,
            "Read the five scope-sequential files in exact numeric Plan order. "
            "Fail closed on a duplicate or future-step Tool candidate.")
        wait_for_task_terminal(session, approved, "[Task] Completed", 150)
    elif name == "plan-tool-scope-multi-tool-batch":
        approved = submit_and_approve(
            session,
            "Read scope-batch/alpha.txt and scope-batch/beta.txt in one exact Tool batch.")
        wait_for_task_terminal(session, approved, "[Task] Completed", 120)
    elif name == "plan-evidence-semantic-count":
        approved = submit_and_approve(
            session,
            "Read both semantic-count files in one safe Tool batch; the advisory Plan must "
            "not create Strict Evidence or gate completion.")
        wait_for_task_terminal(session, approved, "[Task] Completed", 120)
    elif name == "plan-v2-unfinished-fail-closed":
        raise ValueError("V2 unfinished flow requires the dedicated restart path")
    else:
        raise ValueError(f"unsupported extended flow: {name}")
    session.send_line("/exit")
    metadata["exitCode"] = session.wait_exit()
    return metadata


def extended_assertions(name: str, state: dict, transcript: str, provider: ScenarioProvider,
                        before: dict[str, str], after: dict[str, str],
                        fixture: dict, flow: dict, private_hits: list[str]) -> dict:
    tool_counts = state["toolCounts"]
    terminal_tasks = [row["status"] for row in state["tasks"]]
    terminal_runs = [row["terminal_status"] for row in state["runs"]]
    assertions: dict[str, bool] = {
        "noPrivateLeak": not private_hits,
        "allOccurrencesTerminal": all(row["status"] not in ("STARTED", "UNKNOWN")
                                      for row in state["occurrences"]),
        "durableEventsPreserved": state["durableEventsContiguous"],
    }
    if name == "manual-compact":
        before_eligible = flow["beforeCompact"]["eligibleContextPointers"]
        after_by_scope = {
            (row["task_id"], row["run_id"], row["operation_kind"]): row
            for row in flow["afterCompact"]["contextPointers"]
        }
        compacted_by_source = {
            row["source_context_generation_id"]: row
            for row in flow["afterCompact"]["contextGenerations"]
            if row["generation_kind"] == "COMPACTED" and row["lifecycle"] == "READY"
        }
        advanced_sources = {
            row["active_context_generation_id"] for row in before_eligible
            if row["active_context_generation_id"] in compacted_by_source
        }
        bundle_advanced = bool(advanced_sources) and all(
            (after_by_scope[(row["task_id"], row["run_id"], row["operation_kind"])]
                 ["active_context_generation_id"]
             == compacted_by_source[row["active_context_generation_id"]]
                 ["context_generation_id"])
            if row["active_context_generation_id"] in advanced_sources else
            (after_by_scope[(row["task_id"], row["run_id"], row["operation_kind"])]
                 ["active_context_generation_id"] == row["active_context_generation_id"])
            for row in before_eligible)
        assertions.update({
            "manualCompactEventOnce": transcript.count("Context compacted") == 1,
            "noAutoCompactBeforeManual": (
                flow["beforeCompact"]["effectiveReductions"] == 0),
            "eligibleBundleAdvancedEffectivePointers": bundle_advanced,
            "eligibleBundleReduced": (flow["afterCompact"]["effectiveReductions"]
                                      - flow["beforeCompact"]["effectiveReductions"]
                                      == len(advanced_sources)),
            "continuationInheritedContext": (bool(provider.context_checks)
                                             and all(provider.context_checks[-1].values())),
            "parentAndChildCompleted": terminal_tasks == ["COMPLETED", "COMPLETED"]
                                       and terminal_runs == ["COMPLETED", "COMPLETED"],
            "sameThreadChildLineage": (len(state["threads"]) == 1
                                       and len(state["continuationEdges"]) == 1
                                       and state["tasks"][1]["parent_task_id"]
                                           == state["tasks"][0]["task_id"]),
            "readCountExact": (tool_counts.get("read_file", 0)
                               == MANUAL_CONTEXT_FILE_COUNT + 1),
            "distinctToolCalls": (state["distinctToolCallIds"]
                                  == MANUAL_CONTEXT_FILE_COUNT + 1),
            "filesUnchanged": before == after,
        })
    elif name == "auto-long-task":
        occurrence_scope = {(row["task_id"], row["run_id"]) for row in state["occurrences"]
                            if row["operation_type"] == "TOOL"
                            and row["target"] == "read_file"}
        occurrence_call_ids = {row["tool_call_id"] for row in state["occurrences"]
                               if row["operation_type"] == "TOOL"}
        retry = flow["networkRetryEvidence"]
        before_task = flow["beforeTask"]
        closure = active_base_compaction_closure(state)
        reads_before_first_compact = distinct_reads_before_first_successful_compact(state)
        assertions.update({
            "autoCompactAtLeastTwice": transcript.count("Context compacted") >= 2,
            "firstAutoCompactAfterSixReads": reads_before_first_compact >= 6,
            "providerTurnsBounded": 16 <= retry["logicalTurns"] <= 20,
            "oneNetworkRetry": (retry["faultInjected"]
                                and retry["attempts"] == [1, 2]
                                and retry["totalRetries"] == 1
                                and retry["actualRequests"] == retry["logicalTurns"] + 1
                                and retry["outcomes"] == ["HTTP_503", "HTTP_200"]),
            "retryRequestDigestStable": len(retry["requestDigests"]) == 1,
            "retryCompactMutualExclusion": len(retry["contextGenerations"]) == 1,
            "durableRetryCountExact": state["providerRetryCount"] == 1,
            "durableAttemptRowsExact": (state["providerAttemptRows"]
                                        == retry["actualRequests"]),
            "retryLifecycleVisible": ("Retrying provider request 1/2" in transcript
                                      and "Provider connection restored" in transcript),
            "readCountExact": tool_counts.get("read_file", 0) == 15,
            "distinctToolCalls": state["distinctToolCallIds"] == 15,
            "allLongTaskCandidatesLegal": (
                provider.issued_tool_call_count == 15
                and not provider.rejected_candidate_ids),
            "semanticSlotsExact": (
                len(state["planToolRequirements"]) == 15
                and len(state["planOccurrenceSlotBindings"]) == 0
                and len(state["planEvidenceSlotBindings"]) == 0
                and state["planEvidence"] == 0),
            "durableEventVolume": state["eventCount"] >= 30,
            "singlePlanApproval": state["planRevisions"] == 1
                                  and state["approvedPlans"] == 1
                                  and state["planRunBindings"] == 0,
            "sameTaskRun": len(state["tasks"]) == 1 and len(state["runs"]) == 1
                           and len(occurrence_scope) == 1,
            "activeBaseOverCompactedClosure": closure["valid"],
            "activeGenerationChangedAfterCompact": (
                closure["valid"]
                and set(closure["activeBaseGenerations"]).isdisjoint(
                    closure["compactedSourceGenerations"])),
            "effectiveReductionObserved": state["effectiveReductions"] >= 2,
            "effectiveReductionDeltaAtLeastTwo": (
                state["effectiveReductions"]
                - before_task["effectiveReductions"] >= 2),
            "durableEventsNeverDecreased": (
                state["eventCount"] >= before_task["eventCount"]),
            "groundedFinalMarkers": (bool(provider.context_checks)
                                     and all(provider.context_checks[-1].values())
                                     and flow["groundedFinalEvidence"]["resultCount"] == 1
                                     and flow["groundedFinalEvidence"]
                                         ["allMarkersPresent"]
                                     and flow["groundedFinalEvidence"]
                                         ["contentHashesVerified"]
                                     and flow["groundedFinalEvidence"]["allValidated"]
                                     and flow["groundedFinalEvidence"]["allRendered"]),
            "completed": terminal_tasks == ["COMPLETED"]
                         and terminal_runs == ["COMPLETED"],
            "filesUnchanged": before == after,
        })
    elif name == "normal-long-plan-approval":
        canonical = normal_long_plan()
        expected_definitions = [step["definition"] for step in canonical["steps"]]
        durable_steps = state["planStepDefinitions"]
        durable_revision = (state["planRevisionRows"][0]
                            if len(state["planRevisionRows"]) == 1 else {})
        preview = ANSI.sub("", flow["approvalPreview"])
        approval_details = ANSI.sub("", flow["approvalDetails"])
        plan_summary = ANSI.sub("", flow["planSummary"])
        plan_details = ANSI.sub("", flow["planDetails"])
        durable_matches = (
            len(durable_steps) == 7
            and [row["step_order"] for row in durable_steps] == list(range(7))
            and [row["title"] for row in durable_steps]
                == list(NORMAL_LONG_PLAN_TITLES)
            and [row["definition"] for row in durable_steps]
                == expected_definitions
            and all(row["completion_rule"] == "ALL_REQUIRED_EVIDENCE"
                    and row["required_evidence_kinds"] == "TOOL_CALL"
                    and row["effect_class"] == "READ"
                    for row in durable_steps))
        assertions.update({
            "approvalPreviewShowsAllSevenSteps": (
                all(title in preview for title in NORMAL_LONG_PLAN_TITLES)
                and "7 steps" in preview),
            "approvalPreviewShowsEveryBoundedDefinition": (
                "读取第四份只读证据" in preview
                and all(terminal_token_present(preview, marker)
                        for index, marker in enumerate(
                            NORMAL_LONG_PLAN_DEFINITION_MARKERS)
                        if index != 3)),
            "approvalPreviewRemainsBounded": (
                not terminal_token_present(
                    preview, NORMAL_LONG_PLAN_DEFINITION_MARKERS[3])),
            "approvalDetailsShowCanonicalDefinitions": canonical_plan_details_present(
                approval_details, NORMAL_LONG_PLAN_TITLES,
                NORMAL_LONG_PLAN_DEFINITION_MARKERS[3],
                durable_revision.get("plan_revision_id", ""),
                durable_revision.get("canonical_hash", "")),
            "planSummaryShowsAllSevenSteps": (
                all(title in plan_summary for title in NORMAL_LONG_PLAN_TITLES)),
            "planDetailsShowCanonicalDefinitions": canonical_plan_details_present(
                plan_details, NORMAL_LONG_PLAN_TITLES,
                NORMAL_LONG_PLAN_DEFINITION_MARKERS[3],
                durable_revision.get("plan_revision_id", ""),
                durable_revision.get("canonical_hash", "")),
            "durableCanonicalPlanExact": durable_matches,
            "definitionBoundsExercised": (
                sum(len(value) for value in expected_definitions) > 256
                and max(len(value) for value in expected_definitions) > 256),
            "singleImmutableApproval": (
                state["planRevisions"] == 1
                and state["approvedPlans"] == 1
                and state["planRunBindings"] == 0
                and len(state["planRevisionRows"]) == 1
                and state["planRevisionRows"][0]["lifecycle"] == "APPROVED"),
            "readCountExact": tool_counts.get("read_file", 0) == 7,
            "distinctToolCalls": state["distinctToolCallIds"] == 7,
            "providerIssuedSevenUniqueReads": (
                provider.tool_index == 7
                and len({item["arguments"]["path"] for item in provider.tools}) == 7),
            "groundedFinalMarkers": (
                bool(provider.context_checks)
                and all(provider.context_checks[-1].values())
                and flow["groundedFinalEvidence"]["resultCount"] == 1
                and flow["groundedFinalEvidence"]["allMarkersPresent"]
                and flow["groundedFinalEvidence"]["contentHashesVerified"]
                and flow["groundedFinalEvidence"]["allValidated"]
                and flow["groundedFinalEvidence"]["allRendered"]),
            "completed": terminal_tasks == ["COMPLETED"]
                         and terminal_runs == ["COMPLETED"],
            "filesUnchanged": before == after,
        })
    elif name == "write-verify-rewind":
        snapshot = state["snapshots"][0] if len(state["snapshots"]) == 1 else {}
        verification = state["verifications"][0] if len(state["verifications"]) == 1 else {}
        proposal = state["proposals"][0] if len(state["proposals"]) == 1 else {}
        assertions.update({
            "readOnceWriteOnce": tool_counts.get("read_file", 0) == 1
                                 and tool_counts.get("write_file", 0) == 1,
            "preWriteDiffRendered": "[Proposed Edit]" in transcript,
            "verificationPassed": verification.get("status") == "PASSED",
            "snapshotRestored": snapshot.get("status") == "RESTORED",
            "proposalRewound": proposal.get("status") == "REWOUND",
            "hashTransitionAndRestore": (flow.get("h1") == snapshot.get("sha256_after")
                                         and flow.get("h1") != fixture.get("h0")
                                         and flow.get("hAfterRewind") == fixture.get("h0")),
            "advisoryPlanNotCompletionGate": state["planEvidence"] == 0
                                             and state["completionDecisions"] == 0,
            "completed": terminal_tasks == ["COMPLETED"]
                         and terminal_runs == ["COMPLETED"],
        })
    elif name == "structured-verify-fail-closed":
        verification = state["verifications"][0] if len(state["verifications"]) == 1 else {}
        assertions.update({
            "readOnceWriteOnce": tool_counts.get("read_file", 0) == 1
                                 and tool_counts.get("write_file", 0) == 1,
            "verificationFailed": verification.get("status") == "FAILED",
            "taskRunFailed": terminal_tasks == ["FAILED"] and terminal_runs == ["FAILED"],
            "zeroRepairAttempts": state["repairAttempts"] == 0,
            "zeroCompletionDecision": state["completionDecisions"] == 0,
            "zeroUserResults": state["userResults"] == 0,
            "failureResultEnvelope": verification_failure_result_envelope(transcript),
        })
    elif name == "session-resume-continue":
        parent = flow["parentState"]
        resumed = flow["afterResume"]
        assertions.update({
            "sessionsListed": "Saved Sessions:" in transcript,
            "resumeCreatedNoRun": len(parent["runs"]) == len(resumed["runs"]),
            "twoTasksTwoRuns": len(state["tasks"]) == 2 and len(state["runs"]) == 2,
            "sameThreadLineage": (len(state["threads"]) == 1
                                  and len(state["continuationEdges"]) == 1
                                  and state["tasks"][1]["parent_task_id"]
                                      == state["tasks"][0]["task_id"]),
            "parentTerminalUnchanged": (parent["tasks"][0]["status"] == "COMPLETED"
                                        and state["tasks"][0]["status"] == "COMPLETED"
                                        and parent["runs"][0]["terminal_status"] == "COMPLETED"
                                        and state["runs"][0]["terminal_status"] == "COMPLETED"),
            "childReadOnce": tool_counts.get("read_file", 0) == 2,
            "twoPlanApprovals": state["planRevisions"] == 2
                                and state["approvedPlans"] == 2
                                and state["planRunBindings"] == 0,
            "filesUnchanged": before == after,
        })
    elif name == "plan-tool-scope-sequential-deviation":
        tool_occurrences = [row for row in state["occurrences"]
                            if row["operation_type"] == "TOOL"]
        occurrence_call_ids = {row["tool_call_id"] for row in tool_occurrences}
        assertions.update({
            "advisoryOrderDeviationRemainsSafe": (
                len(tool_occurrences) == 3
                and all(row["target"] == "read_file"
                        and row["status"] == "COMPLETED" for row in tool_occurrences)),
            "noStrictCandidateRejection": (
                not provider.rejected_candidate_ids
                and len(occurrence_call_ids) == 3),
            "noStrictRuntimeBindings": (
                len(state["planToolRequirements"]) == 5
                and len(state["planOccurrenceSlotBindings"]) == 0
                and len(state["planEvidenceSlotBindings"]) == 0
                and state["planEvidence"] == 0),
            "providerStoppedAtFirstDeterministicDeviation": (
                provider.issued_tool_call_count == 3),
            "completed": terminal_tasks == ["COMPLETED"]
                         and terminal_runs == ["COMPLETED"],
            "zeroCompletionDecision": state["completionDecisions"] == 0,
            "filesUnchanged": before == after,
        })
    elif name == "plan-tool-scope-multi-tool-batch":
        tool_occurrences = [row for row in state["occurrences"]
                            if row["operation_type"] == "TOOL"]
        assertions.update({
            "singleProviderBatchWithTwoCalls": (
                provider.tool_index == 1 and provider.issued_tool_call_count == 2),
            "twoAtomicTerminalOccurrences": (
                len(tool_occurrences) == 2
                and len({row["tool_call_id"] for row in tool_occurrences}) == 2
                and all(row["status"] == "COMPLETED" for row in tool_occurrences)),
            "twoRequirementsRemainAdvisory": (
                len(state["planToolRequirements"]) == 2
                and len(state["planOccurrenceSlotBindings"]) == 0
                and len(state["planEvidenceSlotBindings"]) == 0
                and state["planEvidence"] == 0),
            "completed": terminal_tasks == ["COMPLETED"]
                         and terminal_runs == ["COMPLETED"],
            "filesUnchanged": before == after,
        })
    elif name == "plan-evidence-semantic-count":
        tool_occurrences = [row for row in state["occurrences"]
                            if row["operation_type"] == "TOOL"]
        assertions.update({
            "twoSafeDistinctCalls": (
                len(tool_occurrences) == 2
                and len({row["tool_call_id"] for row in tool_occurrences}) == 2
                and all(row["target"] == "read_file" for row in tool_occurrences)),
            "singleProviderBatchWithTwoCalls": (
                provider.tool_index == 1 and provider.issued_tool_call_count == 2),
            "advisoryEvidenceNotCompletionGate": (
                len(state["planToolRequirements"]) == 2
                and len(state["planOccurrenceSlotBindings"]) == 0
                and len(state["planEvidenceSlotBindings"]) == 0
                and state["planEvidence"] == 0
                and state["completionDecisions"] == 0),
            "completed": terminal_tasks == ["COMPLETED"]
                         and terminal_runs == ["COMPLETED"],
            "filesUnchanged": before == after,
        })
    elif name == "plan-v2-unfinished-fail-closed":
        seeded = flow["seeded"]
        before_resume = flow["beforeResume"]
        after_resume = flow["afterResume"]
        after_recover = flow["afterRecover"]
        after_continue = flow["afterContinue"]

        def authority_counts(value: dict) -> tuple:
            return (len(value["runs"]), len(value["occurrences"]),
                    value["planEvidence"], len(value["planOccurrenceSlotBindings"]),
                    len(value["planEvidenceSlotBindings"]), value["toolExecutions"])

        seeded_rows = [row for row in state["planRevisionRows"]
                       if row["plan_revision_id"] == seeded["planId"]]
        assertions.update({
            "resumeBindsWithoutExecution": flow["resumeBoundWithoutExecution"],
            "recoverTypedScopeRejection": flow["recoverTypedRejection"],
            "continueTypedScopeRejection": flow["continueTypedRejection"],
            "approvedV2HasNoV3Requirements": (
                len(seeded_rows) == 1
                and seeded_rows[0]["canonical_hash"] == seeded["planHash"]
                and seeded_rows[0]["lifecycle"] == "APPROVED"
                and not [row for row in state["planToolRequirements"]
                         if row["plan_revision_id"] == seeded["planId"]]),
            "noResumeOrContinueAuthorityMutation": (
                authority_counts(before_resume) == authority_counts(after_resume)
                == authority_counts(after_recover)
                == authority_counts(after_continue)),
            "noNewRunOccurrenceEvidence": (
                authority_counts(before_resume) == authority_counts(state)),
            "filesUnchanged": before == after,
        })
    return assertions


def run_extended_scenario(name: str, project: Path, zip_path: Path, root: Path) -> dict:
    scenario_root = root / "work" / name
    extract_root = scenario_root / "distribution"
    workspace = scenario_root / "workspace with 空格"
    extract_root.mkdir(parents=True, exist_ok=True)
    workspace.mkdir(parents=True, exist_ok=True)
    with ZipFile(zip_path) as archive:
        archive.extractall(extract_root)
    candidates = list(extract_root.rglob("bin/paicli.ps1"))
    if len(candidates) != 1:
        raise RuntimeError(f"packaged launcher count was {len(candidates)}")
    launcher = candidates[0]
    dist = launcher.parent.parent
    (workspace / "README.md").write_text(
        "This README describes the isolated PaiCLI extended offline UAT workspace.\n",
        encoding="utf-8")
    fixture = create_fixture(name, workspace)
    before = user_hashes(workspace)
    provider = ScenarioProvider(name)
    provider.start()
    write_config(workspace, provider.base_url, extended_config(name))
    sensitive_name = re.compile(r"(?i)(api[_-]?key|token|secret|password|credential)")
    env = {key: value for key, value in os.environ.items()
           if not sensitive_name.search(key)}
    env["PAICLI_UAT_API_KEY"] = SECRET
    env.pop("NO_COLOR", None)
    argv = ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
            "-File", str(launcher)]
    session = None
    outcome = None
    phase = "START"
    try:
        session = PtySession(argv, workspace, env)
        phase = "READY_PROMPT"
        session.wait_for("paicli [ready]>", 30)
        session.capture_java_children(dist)
        phase = "SCENARIO_FLOW"
        if name == "plan-v2-unfinished-fail-closed":
            flow, session = run_v2_unfinished_flow(session, workspace, argv, env)
        else:
            flow = run_extended_flow(name, session, workspace, provider, fixture)
        time.sleep(0.5)
        after = user_hashes(workspace)
        state = state_summary(workspace)
        transcript = session.transcript
        trace = dist / "diagnostics" / "paicli-stage-trace.log"
        transcript_hits = [marker for marker in PRIVATE_MARKERS if marker in transcript]
        disk_hits = private_file_hits([workspace / ".paicli", trace])
        private_hits = sorted(set(transcript_hits + disk_hits))
        if name == "auto-long-task":
            flow["networkRetryEvidence"] = network_retry_evidence(provider)
            if not flow["networkRetryEvidence"]["schemaComplete"]:
                raise NotReached(
                    "auto-long-task Provider retry attempt evidence was incomplete")
            flow["groundedFinalEvidence"] = durable_user_result_marker_evidence(
                workspace, LONG_MARKERS)
        elif name == "normal-long-plan-approval":
            flow["groundedFinalEvidence"] = durable_user_result_marker_evidence(
                workspace, NORMAL_LONG_PLAN_RESULT_MARKERS)
        assertions = extended_assertions(
            name, state, transcript, provider, before, after, fixture, flow, private_hits)
        passed = all(assertions.values())
        outcome = {
            "scenario": name, "status": "PASS" if passed else "FAIL",
            "failureStage": None if passed else "ASSERTIONS",
            "exitCode": flow.get("exitCode"), "providerRequests": len(provider.requests),
            "providerAttempts": provider.request_attempts,
            "toolExecutions": sum(state["toolCounts"].values()),
            "toolCounts": state["toolCounts"], "filesChanged": before != after,
            "taskState": state["task"], "runState": state["run"],
            "privateMetadataHits": private_hits, "assertions": assertions,
            "networkRetryEvidence": flow.get("networkRetryEvidence"),
            "groundedFinalEvidence": flow.get("groundedFinalEvidence"),
            "stateEvidence": state, "transcript": transcript, "trace": str(trace),
        }
    except NotReached as exc:
        evidence = collect_failure_evidence(workspace, before, session, dist)
        outcome = {
            "scenario": name, "status": "NOT_REACHED", "failureStage": phase,
            "error": f"{type(exc).__name__}: {exc}", "providerRequests": len(provider.requests),
            "providerAttempts": provider.request_attempts,
            "networkRetryEvidence": (network_retry_evidence(provider)
                                     if name == "auto-long-task" else None),
            "assertions": {}, **evidence,
        }
    except Exception as exc:
        evidence = collect_failure_evidence(workspace, before, session, dist)
        outcome = {
            "scenario": name, "status": "FAIL", "failureStage": phase,
            "error": f"{type(exc).__name__}: {exc}", "providerRequests": len(provider.requests),
            "providerAttempts": provider.request_attempts,
            "networkRetryEvidence": (network_retry_evidence(provider)
                                     if name == "auto-long-task" else None),
            "assertions": {}, **evidence,
        }
    finally:
        cleanup_errors = []
        if session is not None:
            try:
                session.close()
            except Exception as exc:
                cleanup_errors.append(f"PTY_CLOSE:{type(exc).__name__}")
        try:
            provider.close()
        except Exception as exc:
            cleanup_errors.append(f"PROVIDER_CLOSE:{type(exc).__name__}")
        process_evidence = process_cleanup_evidence(
            dist, [] if session is None else session.launched_java_pids)
        if not process_evidence["processProbeSucceeded"]:
            probe_category = process_evidence["processProbeErrorCategory"]
            cleanup_errors.append(f"PROCESS_PROBE:{probe_category}")
        if outcome is not None:
            outcome.update({key: value for key, value in process_evidence.items()
                            if key != "noResidualJava"})
            outcome["cleanupErrors"] = cleanup_errors
            outcome.setdefault("assertions", {})["noResidualJava"] = (
                process_evidence["noResidualJava"])
            if not process_evidence["noResidualJava"] or cleanup_errors:
                outcome["status"] = "FAIL"
                outcome["failureStage"] = outcome.get("failureStage") or "PROCESS_CLEANUP"
    return outcome


def run_scenario(name: str, project: Path, zip_path: Path, root: Path) -> dict:
    if name not in ("readonly-single-tool", "approval-reject",
                    "approval-cancel", "ctrl-c-exit"):
        return run_extended_scenario(name, project, zip_path, root)
    scenario_root = root / "work" / name
    extract_root = scenario_root / "distribution"
    workspace = scenario_root / "workspace with 空格"
    extract_root.mkdir(parents=True, exist_ok=True)
    workspace.mkdir(parents=True, exist_ok=True)
    with ZipFile(zip_path) as archive:
        archive.extractall(extract_root)
    candidates = list(extract_root.rglob("bin/paicli.ps1"))
    if len(candidates) != 1:
        raise RuntimeError(f"packaged launcher count was {len(candidates)}")
    launcher = candidates[0]
    dist = launcher.parent.parent
    (workspace / "README.md").write_text(
        "This README describes the isolated PaiCLI offline ConPTY UAT workspace.\n", encoding="utf-8")
    before = user_hashes(workspace)
    provider = FakeProvider()
    provider.start()
    write_config(workspace, provider.base_url)
    sensitive_name = re.compile(r"(?i)(api[_-]?key|token|secret|password|credential)")
    env = {key: value for key, value in os.environ.items()
           if not sensitive_name.search(key)}
    env["PAICLI_UAT_API_KEY"] = SECRET
    env.pop("NO_COLOR", None)
    argv = ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", str(launcher)]
    session = None
    outcome = None
    phase = "START"
    try:
        session = PtySession(argv, workspace, env)
        phase = "READY_PROMPT"
        session.wait_for("paicli [ready]>", 30)
        session.capture_java_children(dist)
        task_checkpoint = session.checkpoint()
        if name == "ctrl-c-exit":
            phase = "CTRL_C"
            session.ctrl_c()
            exit_code = session.wait_exit()
        else:
            phase = "TASK_SUBMIT"
            session.send_line("Read README.md and explain its actual purpose. Do not modify files.")
            phase = "APPROVAL"
            session.wait_for_since(IMMUTABLE_PLAN_APPROVAL_PROMPT, task_checkpoint, 45)
            decision = {"readonly-single-tool": "y", "approval-reject": "n",
                        "approval-cancel": "c"}[name]
            decision_checkpoint = session.checkpoint()
            session.send_line(decision)
            if name == "readonly-single-tool":
                phase = "TOOL_AND_FINAL"
                terminal_end = session.wait_for_since("[Task] Completed", decision_checkpoint, 60)
                session.wait_for_since("paicli [ready]>", terminal_end, 20)
            else:
                phase = "DECISION_TERMINAL"
                markers = (("[Task] Incomplete", "[Task] Failed")
                           if name == "approval-reject"
                           else ("[Task] Cancelled", "[Task] Failed"))
                _, terminal_end = session.wait_for_any_since(markers, decision_checkpoint, 30)
                session.wait_for_since("paicli [ready]>", terminal_end, 20)
            session.send_line("/exit")
            exit_code = session.wait_exit()
        time.sleep(0.5)
        after = user_hashes(workspace)
        state = state_summary(workspace)
        transcript = session.transcript
        private_hits = [token for token in (SECRET, "reasoning_content", "payloadDigest",
                                             "[PAICLI_TOOL_FACTS]") if token in transcript]
        tool_lines = len(re.findall(r"Running read_file", transcript))
        if name == "readonly-single-tool":
            assertions = {
                "toolExecutedOnce": state["toolExecutions"] == 1 and tool_lines == 1,
                "groundedResult": "offline UAT" in transcript,
                "taskCompleted": state["task"] in ("DONE", "COMPLETED"),
                "runCompleted": state["run"] in ("DONE", "COMPLETED"),
                "promptCountExact": transcript.count("paicli [ready]>") == 2,
                "filesUnchanged": before == after,
            }
        elif name == "approval-reject":
            assertions = {"zeroTools": state["toolExecutions"] == 0 and tool_lines == 0,
                          "terminalTask": state["task"] in ("FAILED", "CANCELLED", "REJECTED", "INCOMPLETE"),
                          "terminalRun": state["run"] in ("FAILED", "CANCELLED", "REJECTED", "INCOMPLETE"),
                          "readyAfterDecision": "paicli [ready]>" in transcript[decision_checkpoint:],
                          "promptCountExact": transcript.count("paicli [ready]>") == 2,
                          "filesUnchanged": before == after}
        elif name == "approval-cancel":
            assertions = {"zeroTools": state["toolExecutions"] == 0 and tool_lines == 0,
                          "terminalTask": state["task"] in ("FAILED", "CANCELLED", "REJECTED", "INCOMPLETE"),
                          "readyAfterCancel": "paicli [ready]>" in transcript[decision_checkpoint:],
                          "promptCountExact": transcript.count("paicli [ready]>") == 2,
                          "filesUnchanged": before == after}
        else:
            assertions = {"processExited": exit_code is not None,
                          "promptCountExact": transcript.count("paicli [ready]>") == 1}
        assertions["noPrivateLeak"] = not private_hits
        outcome = {"scenario": name, "status": "PASS" if all(assertions.values()) else "FAIL",
                "failureStage": None if all(assertions.values()) else "ASSERTIONS",
                "exitCode": exit_code, "providerRequests": len(provider.requests),
                "providerAttempts": provider.request_attempts,
                "toolExecutions": state["toolExecutions"], "filesChanged": before != after,
                "taskState": state["task"], "runState": state["run"],
                "privateMetadataHits": private_hits, "assertions": assertions,
                "transcript": transcript, "trace": str(dist / "diagnostics" / "paicli-stage-trace.log")}
    except Exception as exc:
        outcome = {"scenario": name, "status": "FAIL", "failureStage": phase,
                "error": f"{type(exc).__name__}: {exc}", "providerRequests": len(provider.requests),
                "providerAttempts": provider.request_attempts,
                "toolExecutions": 0, "filesChanged": None, "taskState": None, "runState": None,
                "privateMetadataHits": [], "assertions": {},
                "transcript": "" if session is None else session.transcript,
                "trace": str(dist / "diagnostics" / "paicli-stage-trace.log")}
    finally:
        cleanup_errors = []
        if session is not None:
            try: session.close()
            except Exception as exc: cleanup_errors.append(f"PTY_CLOSE:{type(exc).__name__}")
        try: provider.close()
        except Exception as exc: cleanup_errors.append(f"PROVIDER_CLOSE:{type(exc).__name__}")
        process_evidence = process_cleanup_evidence(
            dist, [] if session is None else session.launched_java_pids)
        if not process_evidence["processProbeSucceeded"]:
            probe_category = process_evidence["processProbeErrorCategory"]
            cleanup_errors.append(f"PROCESS_PROBE:{probe_category}")
        if outcome is not None:
            outcome.update({key: value for key, value in process_evidence.items()
                            if key != "noResidualJava"})
            outcome["cleanupErrors"] = cleanup_errors
            outcome.setdefault("assertions", {})["noResidualJava"] = (
                process_evidence["noResidualJava"])
            if not process_evidence["noResidualJava"] or cleanup_errors:
                outcome["status"] = "FAIL"
                outcome["failureStage"] = outcome.get("failureStage") or "PROCESS_CLEANUP"
    return outcome


def snapshot(transcript: str, output: Path) -> None:
    plain = ANSI.sub("", transcript).replace("\r", "")
    content = "\n".join(plain.splitlines()[-24:])
    document = ("<!doctype html><meta charset='utf-8'><title>PaiCLI readonly-single-tool 80x24</title>"
                "<style>body{margin:0;background:#101418;color:#d7e0e7}pre{box-sizing:border-box;"
                "width:80ch;min-height:24em;margin:0;padding:1em;font:14px/1.35 Consolas,monospace;"
                "white-space:pre-wrap;overflow-wrap:anywhere}</style><pre>" + html.escape(content) + "</pre>")
    output.write_text(document, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project-root", type=Path, required=True)
    parser.add_argument("--suite", choices=["Offline"], required=True)
    parser.add_argument("--scenario", choices=SCENARIOS)
    args = parser.parse_args()
    project = args.project_root.resolve()
    zip_path = project / "target" / "dist" / "paicli-1.2.0.zip"
    jar_path = project / "target" / "paicli-1.2.0.jar"
    latest = project / "target" / "uat" / "latest"
    allowed_parent = (project / "target" / "uat").resolve()
    if latest.resolve().parent != allowed_parent:
        raise SystemExit("unsafe UAT output root")
    if latest.exists():
        shutil.rmtree(latest)
    (latest / "transcripts").mkdir(parents=True)
    (latest / "traces").mkdir(parents=True)
    selected = [args.scenario] if args.scenario else list(SCENARIOS)
    results = []
    for name in SCENARIOS:
        if name not in selected:
            results.append({"scenario": name, "status": "NOT_RUN"})
            continue
        result = run_scenario(name, project, zip_path, latest)
        transcript = result.pop("transcript", "")
        (latest / "transcripts" / f"{name}.ansi.txt").write_text(transcript, encoding="utf-8")
        trace_path = Path(result.pop("trace", ""))
        if trace_path.is_file():
            shutil.copy2(trace_path, latest / "traces" / f"{name}.log")
        if name == "readonly-single-tool" and result["status"] == "PASS":
            snapshot(transcript, latest / "readonly-single-tool-80x24.html")
        results.append(result)
    payload = {"suite": "Offline", "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
               "artifact": {"jar": str(jar_path), "jarSha256": sha256(jar_path),
                            "zip": str(zip_path), "zipSha256": sha256(zip_path)},
               "ptyBackend": "Windows ConPTY (pywinpty)", "results": results}
    (latest / "results.json").write_text(json.dumps(payload, indent=2), encoding="utf-8")
    lines = ["# PaiCLI Offline Distribution ConPTY UAT", "", f"- JAR SHA-256: `{payload['artifact']['jarSha256']}`",
             f"- ZIP SHA-256: `{payload['artifact']['zipSha256']}`", "- PTY: Windows ConPTY via pywinpty", ""]
    for result in results:
        scan_status = ("NOT_REACHED" if result["status"] in ("NOT_RUN", "NOT_REACHED")
                       else "PASS" if not result.get("privateMetadataHits") else "FAIL")
        lines.extend([f"## {result['scenario']}: {result['status']}",
                      f"- Failure stage: {result.get('failureStage') or 'none'}",
                      f"- Fake Provider requests: {result.get('providerRequests', 0)}",
                      f"- Tool executions: {result.get('toolExecutions', 0)}",
                      f"- Files changed: {result.get('filesChanged', 'not run')}",
                      f"- Task/Run: {result.get('taskState', 'n/a')} / {result.get('runState', 'n/a')}",
                      f"- Secret/private metadata scan: {scan_status}", ""])
    (latest / "summary.md").write_text("\n".join(lines), encoding="utf-8")
    passed = all(result["status"] in ("PASS", "NOT_RUN") for result in results)
    print(f"PAICLI_UAT_SUMMARY={latest / 'summary.md'}")
    print(f"PAICLI_UAT_RESULT={'PASS' if passed else 'FAIL'}")
    return 0 if passed else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except SystemExit:
        raise
    except Exception:
        traceback.print_exc()
        raise SystemExit(2)
