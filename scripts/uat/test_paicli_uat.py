import http.client
import queue
import shutil
import sqlite3
import unittest
import uuid
from contextlib import contextmanager
from unittest import mock

import paicli_uat


@contextmanager
def target_temp_directory():
    root = paicli_uat.Path.cwd() / "target" / "uat-python-tests"
    root.mkdir(parents=True, exist_ok=True)
    directory = root / f"case-{uuid.uuid4().hex}"
    directory.mkdir()
    try:
        yield str(directory)
    finally:
        shutil.rmtree(directory)


class _StoppedProcess:
    def isalive(self):
        return False


class HarnessSemanticsTest(unittest.TestCase):
    @staticmethod
    def structured_plan_request():
        return {"messages": [{
            "role": "user",
            "content": (
                "Return only one JSON object with fields acceptanceCriteria, assumptions, risks, "
                "verificationStrategy and steps. steps must be a non-empty array whose entries "
                "declare completionRule, requiredEvidenceKinds and toolRequirements. "
                "Do not call tools or mark "
                "work complete.\n\nTask:\nInspect the bounded fixture."
            ),
        }]}

    @staticmethod
    def tool_request(messages=None):
        return {
            "messages": messages or [{"role": "user", "content": "Execute this step"}],
            "tools": [{"type": "function", "function": {"name": "read_file"}}],
        }

    def session(self, transcript):
        value = object.__new__(paicli_uat.PtySession)
        value.chunks = [transcript]
        value.events = queue.Queue()
        value.process = _StoppedProcess()
        return value

    def test_ready_before_terminal_cannot_satisfy_restoration_wait(self):
        session = self.session("paicli [ready]> premature\n[Task] Completed")
        terminal_end = session.transcript.index("[Task] Completed") + len("[Task] Completed")
        with self.assertRaises(RuntimeError):
            session.wait_for_since("paicli [ready]>", terminal_end, timeout=0.01)
        session.chunks.append("\npaicli [ready]>")
        self.assertGreater(session.wait_for_since("paicli [ready]>", terminal_end, timeout=0.01),
                           terminal_end)

    def test_terminal_marker_assertion_survives_conpty_soft_wrap(self):
        wrapped = "LONG-PLAN-DEFINITIO\x1b[24;1H\r\n  N-03"
        self.assertTrue(paicli_uat.terminal_token_present(
            wrapped, "LONG-PLAN-DEFINITION-03"))
        self.assertFalse(paicli_uat.terminal_token_present(
            wrapped, "LONG-PLAN-DEFINITION-04"))

    def test_canonical_details_gate_combines_structure_and_long_tail(self):
        titles = ("Step one", "Step two")
        canonical_hash = "a" * 64
        details = (
            f"Plan revision: pln_x\nPlan hash: {canonical_hash}\n"
            "Plan canonical schema: V3\nPlan mode: DEFAULT_ADVISORY_PLAN\nGoal: goal\n"
            "Acceptance criteria: []\nAssumptions: []\nRisks: []\n"
            "Verification strategy: inspect\nSteps (2):\n"
            "1. Step one\nDefinition: first\nAdvisory dependencies: none\n"
            "2. Step two\nDefinition: long LONG-PLAN-DEFINITIO\x1b[24;1H\n N-04\n"
            "Advisory dependencies: 1\n")
        self.assertTrue(paicli_uat.canonical_plan_details_present(
            details, titles, "LONG-PLAN-DEFINITION-04", "pln_x", canonical_hash))
        self.assertFalse(paicli_uat.canonical_plan_details_present(
            details.replace("Advisory dependencies: none\n", "", 1), titles,
            "LONG-PLAN-DEFINITION-04", "pln_x", canonical_hash))
        self.assertFalse(paicli_uat.canonical_plan_details_present(
            details.replace(canonical_hash, canonical_hash[:16]), titles,
            "LONG-PLAN-DEFINITION-04", "pln_x", canonical_hash))
        self.assertFalse(paicli_uat.canonical_plan_details_present(
            details.replace("pln_x", "pln_other"), titles,
            "LONG-PLAN-DEFINITION-04", "pln_x", canonical_hash))

    def test_failed_cim_probe_is_not_interpreted_as_empty_process_list(self):
        failed = mock.Mock(returncode=1, stdout="", stderr="Access is denied 0x80041003")
        with mock.patch.object(paicli_uat.subprocess, "run", return_value=failed):
            with self.assertRaisesRegex(
                    paicli_uat.ProcessProbeError, "RESIDUAL_JAVA_PROBE_FAILED") as raised:
                paicli_uat.residual_java(paicli_uat.Path("C:/bounded/dist"))
        self.assertEqual("ACCESS_DENIED", raised.exception.category)
        self.assertEqual(1, raised.exception.return_code)

    def test_access_denied_probe_records_unverified_absence_and_fails_closed(self):
        failed = mock.Mock(returncode=1, stdout="", stderr="Access is denied 0x80041003")
        with mock.patch.object(paicli_uat.subprocess, "run", return_value=failed):
            evidence = paicli_uat.process_cleanup_evidence(
                paicli_uat.Path("C:/bounded/dist"))
        self.assertFalse(evidence["processProbeSucceeded"])
        self.assertIsNone(evidence["residualJavaBeforeCleanup"])
        self.assertIsNone(evidence["residualJavaAfterCleanup"])
        self.assertEqual("ACCESS_DENIED", evidence["processProbeErrorCategory"])
        self.assertEqual(1, evidence["processProbeReturnCode"])
        self.assertFalse(evidence["noResidualJava"])

    def test_zero_exit_access_denied_stderr_is_not_verified_empty(self):
        failed = mock.Mock(returncode=0, stdout="", stderr="拒绝访问 0x80041003")
        with mock.patch.object(paicli_uat.subprocess, "run", return_value=failed):
            evidence = paicli_uat.process_cleanup_evidence(
                paicli_uat.Path("C:/bounded/dist"))
        self.assertFalse(evidence["processProbeSucceeded"])
        self.assertIsNone(evidence["residualJavaBeforeCleanup"])
        self.assertIsNone(evidence["residualJavaAfterCleanup"])
        self.assertEqual("ACCESS_DENIED", evidence["processProbeErrorCategory"])
        self.assertEqual(0, evidence["processProbeReturnCode"])
        self.assertFalse(evidence["noResidualJava"])

    def test_generic_zero_exit_stderr_is_error_stream(self):
        failed = mock.Mock(returncode=0, stdout="", stderr="bounded diagnostic")
        with mock.patch.object(paicli_uat.subprocess, "run", return_value=failed):
            evidence = paicli_uat.process_cleanup_evidence(
                paicli_uat.Path("C:/bounded/dist"))
        self.assertFalse(evidence["processProbeSucceeded"])
        self.assertEqual("ERROR_STREAM", evidence["processProbeErrorCategory"])
        self.assertEqual(0, evidence["processProbeReturnCode"])
        self.assertFalse(evidence["noResidualJava"])

    def test_probe_timeout_and_launch_error_are_bounded(self):
        cases = (
            (paicli_uat.subprocess.TimeoutExpired("powershell.exe", 10), "TIMEOUT"),
            (OSError("bounded launch failure"), "LAUNCH_ERROR"),
        )
        for failure, category in cases:
            with self.subTest(category=category), mock.patch.object(
                    paicli_uat.subprocess, "run", side_effect=failure):
                evidence = paicli_uat.process_cleanup_evidence(
                    paicli_uat.Path("C:/bounded/dist"))
            self.assertFalse(evidence["processProbeSucceeded"])
            self.assertEqual(category, evidence["processProbeErrorCategory"])
            self.assertIsNone(evidence["processProbeReturnCode"])
            self.assertFalse(evidence["noResidualJava"])

    def test_generic_nonzero_probe_is_bounded(self):
        failed = mock.Mock(returncode=7, stdout="", stderr="bounded diagnostic")
        with mock.patch.object(paicli_uat.subprocess, "run", return_value=failed):
            evidence = paicli_uat.process_cleanup_evidence(
                paicli_uat.Path("C:/bounded/dist"))
        self.assertFalse(evidence["processProbeSucceeded"])
        self.assertEqual("NON_ZERO_EXIT", evidence["processProbeErrorCategory"])
        self.assertEqual(7, evidence["processProbeReturnCode"])
        self.assertFalse(evidence["noResidualJava"])

    def test_successful_empty_process_probes_are_verified_no_residual_java(self):
        succeeded = mock.Mock(returncode=0, stdout="", stderr="")
        with mock.patch.object(paicli_uat.subprocess, "run", return_value=succeeded) as run:
            evidence = paicli_uat.process_cleanup_evidence(
                paicli_uat.Path("C:/bounded/dist"))
        self.assertTrue(evidence["processProbeSucceeded"])
        self.assertEqual([], evidence["residualJavaBeforeCleanup"])
        self.assertEqual([], evidence["residualJavaAfterCleanup"])
        self.assertIsNone(evidence["processProbeErrorCategory"])
        self.assertIsNone(evidence["processProbeReturnCode"])
        self.assertTrue(evidence["noResidualJava"])
        self.assertEqual(2, run.call_count)
        probe_script = run.call_args_list[0].args[0][-1]
        self.assertIn("$ErrorActionPreference='Stop'", probe_script)
        self.assertIn("Get-CimInstance", probe_script)
        self.assertIn("-ErrorAction Stop", probe_script)

    def test_nonempty_first_probe_then_empty_is_cleanup_observed_not_no_residual(self):
        first = mock.Mock(returncode=0, stdout="321\n", stderr="")
        second = mock.Mock(returncode=0, stdout="", stderr="")
        with mock.patch.object(
                paicli_uat.subprocess, "run", side_effect=[first, second]), \
                mock.patch.object(paicli_uat, "cleanup_scoped_java") as cleanup, \
                mock.patch.object(paicli_uat.time, "sleep"):
            evidence = paicli_uat.process_cleanup_evidence(
                paicli_uat.Path("C:/bounded/dist"))
        self.assertTrue(evidence["processProbeSucceeded"])
        self.assertEqual([321], evidence["residualJavaBeforeCleanup"])
        self.assertEqual([], evidence["residualJavaAfterCleanup"])
        self.assertFalse(evidence["noResidualJava"])
        cleanup.assert_called_once_with([321])

    def test_second_probe_failure_preserves_first_observation_and_fails_closed(self):
        first = mock.Mock(returncode=0, stdout="", stderr="")
        second = mock.Mock(returncode=0, stdout="", stderr="Access is denied")
        with mock.patch.object(
                paicli_uat.subprocess, "run", side_effect=[first, second]):
            evidence = paicli_uat.process_cleanup_evidence(
                paicli_uat.Path("C:/bounded/dist"))
        self.assertFalse(evidence["processProbeSucceeded"])
        self.assertEqual([], evidence["residualJavaBeforeCleanup"])
        self.assertIsNone(evidence["residualJavaAfterCleanup"])
        self.assertEqual("ACCESS_DENIED", evidence["processProbeErrorCategory"])
        self.assertEqual(0, evidence["processProbeReturnCode"])
        self.assertFalse(evidence["noResidualJava"])

    def test_structured_plan_prompt_is_not_misclassified_as_tool_execution(self):
        provider = paicli_uat.FakeProvider()
        provider.start()
        try:
            response = provider.response(self.structured_plan_request(), 1)
            choice = response["choices"][0]
            self.assertEqual("stop", choice["finish_reason"])
            self.assertIn("acceptanceCriteria", choice["message"]["content"])
            self.assertNotIn("tool_calls", choice["message"])
        finally:
            provider.close()

    def test_canonical_immutable_approval_prompt_is_the_wait_contract(self):
        session = mock.Mock()
        session.checkpoint.side_effect = [7, 11]
        approved = paicli_uat.submit_and_approve(session, "bounded task", timeout=3)
        self.assertEqual(11, approved)
        session.wait_for_since.assert_called_once_with(
            paicli_uat.IMMUTABLE_PLAN_APPROVAL_PROMPT, 7, 3)
        self.assertEqual([mock.call("bounded task"), mock.call("y")],
                         session.send_line.call_args_list)

    def test_effectful_edit_requires_a_distinct_second_approval(self):
        session = mock.Mock()
        session.checkpoint.return_value = 19
        approved = paicli_uat.approve_proposed_edit(session, 11, timeout=4)
        self.assertEqual(19, approved)
        session.wait_for_since.assert_called_once_with(
            paicli_uat.EDIT_APPROVAL_PROMPT, 11, 4)
        session.send_line.assert_called_once_with("y")

    def test_rewind_confirmation_matcher_survives_conpty_line_wrap(self):
        checkpoint = len("snapshot selected\n")
        transcript = (
            "snapshot selected\n"
            "Rewind target.txt from task tsk_123 to the state before first edit? Thi"
            "\x1b[23;80H\x1b[36m\x1b[1ms rewinds only the selected snapshot. [y/N]: "
        )
        session = self.session(transcript)
        self.assertNotIn(
            "This rewinds only the selected snapshot. [y/N]:",
            session.transcript[checkpoint:])
        self.assertGreater(
            session.wait_for_since(
                paicli_uat.REWIND_CONFIRMATION_PROMPT, checkpoint, timeout=0.01),
            checkpoint)

    def test_verification_failure_result_envelope_is_not_success_final(self):
        failed = (
            "[Verify] failed tests=unknown TEST_FAILURE: FAILED (exit 1)\n"
            "[Result]\nTask did not complete.\n"
            "Reason: Verification failed and repair is disabled\n"
            "[Error] TASK_FAILED: Verification failed and repair is disabled\n"
            "[Task] Failed"
        )
        completed = failed + "\n[Task] Completed"
        self.assertTrue(paicli_uat.verification_failure_result_envelope(failed))
        self.assertFalse(paicli_uat.verification_failure_result_envelope(completed))

    def test_all_extended_scenarios_route_current_request_to_plan(self):
        for scenario in (
                "manual-compact", "auto-long-task", "write-verify-rewind",
                "structured-verify-fail-closed", "session-resume-continue",
                "normal-long-plan-approval", "plan-tool-scope-sequential-deviation",
                "plan-tool-scope-multi-tool-batch", "plan-evidence-semantic-count",
                "plan-v2-unfinished-fail-closed"):
            with self.subTest(scenario=scenario):
                provider = paicli_uat.ScenarioProvider(scenario)
                try:
                    response = provider.response(self.structured_plan_request(), 1)
                    choice = response["choices"][0]
                    self.assertEqual("stop", choice["finish_reason"])
                    self.assertNotIn("tool_calls", choice["message"])
                    parsed = paicli_uat.json.loads(choice["message"]["content"])
                    self.assertTrue(parsed["steps"])
                    for step in parsed["steps"]:
                        if "TOOL_CALL" in step["requiredEvidenceKinds"]:
                            self.assertTrue(step["toolRequirements"])
                            self.assertEqual(
                                list(range(len(step["toolRequirements"]))),
                                [item["requirementOrder"]
                                 for item in step["toolRequirements"]])
                finally:
                    provider.server.server_close()

    def test_execution_tools_override_historical_plan_prompt(self):
        provider = paicli_uat.ScenarioProvider("auto-long-task")
        try:
            request = self.tool_request(self.structured_plan_request()["messages"] + [{
                "role": "user", "content": "Execute approved step 1",
            }])
            response = provider.response(request, 1)
            self.assertEqual("tool_calls", response["choices"][0]["finish_reason"])
        finally:
            provider.server.server_close()

    def test_unclassified_request_fails_closed(self):
        with self.assertRaisesRegex(
                RuntimeError, "UAT_PROVIDER_OPERATION_UNCLASSIFIED"):
            paicli_uat.provider_operation({
                "messages": [{"role": "user", "content": "ambiguous request"}],
            })

    def test_write_plan_requires_snapshot_and_configured_verification(self):
        provider = paicli_uat.ScenarioProvider("write-verify-rewind")
        try:
            write_step = provider.plans[0]["steps"][1]
            self.assertEqual(["TOOL_CALL", "SNAPSHOT", "VERIFICATION_RESULT"],
                             write_step["requiredEvidenceKinds"])
            self.assertEqual("WRITE", write_step["effectClass"])
        finally:
            provider.server.server_close()

    def test_verification_fixture_uses_exact_byte_comparison(self):
        with target_temp_directory() as directory:
            root = paicli_uat.Path(directory)
            passing = root / "pass"
            failing = root / "fail"
            passing.mkdir()
            failing.mkdir()
            paicli_uat.create_fixture("write-verify-rewind", passing)
            paicli_uat.create_fixture("structured-verify-fail-closed", failing)
            self.assertEqual(paicli_uat.WRITE_AFTER.encode("utf-8"),
                             (passing / "expected-verification.bin").read_bytes())
            self.assertEqual(b"never-match-uat\n",
                             (failing / "expected-verification.bin").read_bytes())
            for scenario in ("write-verify-rewind",
                             "structured-verify-fail-closed"):
                command = paicli_uat.extended_config(scenario)["verification_command"]
                self.assertEqual(
                    "fc /b target.txt expected-verification.bin >nul", command)

    def test_tool_continuation_requires_the_pending_call_identity(self):
        provider = paicli_uat.ScenarioProvider("auto-long-task")
        try:
            tool = provider.response(self.tool_request(), 1)
            call_id = tool["choices"][0]["message"]["tool_calls"][0]["id"]
            with self.assertRaisesRegex(
                    RuntimeError, "UAT_PROVIDER_PENDING_TOOL_RESULT_MISSING"):
                provider.response(self.tool_request([{
                    "role": "tool", "tool_call_id": "wrong-call", "content": "result",
                }]), 2)
            completed = provider.response(self.tool_request([{
                "role": "tool", "tool_call_id": call_id, "content": "result",
            }]), 3)
            self.assertEqual("tool_calls", completed["choices"][0]["finish_reason"])
            self.assertTrue(provider.awaiting_tool_result)
        finally:
            provider.server.server_close()

    def test_release_suite_preserves_original_and_adds_four_scope_scenarios(self):
        self.assertEqual(14, len(paicli_uat.SCENARIOS))
        for scenario in (
                "manual-compact", "auto-long-task", "write-verify-rewind",
                "structured-verify-fail-closed", "session-resume-continue",
                "normal-long-plan-approval", "plan-tool-scope-sequential-deviation",
                "plan-tool-scope-multi-tool-batch", "plan-evidence-semantic-count",
                "plan-v2-unfinished-fail-closed"):
            self.assertIn(scenario, paicli_uat.SCENARIOS)

    def test_manual_compact_history_exceeds_retained_pair_window_with_unique_calls(self):
        self.assertGreater(paicli_uat.MANUAL_CONTEXT_FILE_COUNT,
                           paicli_uat.MANUAL_POLICY_RETAINED_PAIR_COUNT)
        provider = paicli_uat.ScenarioProvider("manual-compact")
        try:
            self.assertEqual(paicli_uat.MANUAL_CONTEXT_FILE_COUNT,
                             len(provider.plans[0]["steps"]))
            self.assertEqual(paicli_uat.MANUAL_CONTEXT_FILE_COUNT + 1,
                             len(provider.tools))
            identifiers = []
            tool = provider.response(self.tool_request(), 1)
            for index in range(paicli_uat.MANUAL_CONTEXT_FILE_COUNT):
                call = tool["choices"][0]["message"]["tool_calls"][0]
                identifiers.append(call["id"])
                completed = provider.response(self.tool_request([{
                    "role": "tool", "tool_call_id": call["id"], "content": "result",
                }]), index * 2 + 2)
                if index + 1 < paicli_uat.MANUAL_CONTEXT_FILE_COUNT:
                    self.assertEqual("tool_calls", completed["choices"][0]["finish_reason"])
                    tool = completed
                else:
                    self.assertEqual("stop", completed["choices"][0]["finish_reason"])
            child = provider.response(self.tool_request(), 30)
            child_call = child["choices"][0]["message"]["tool_calls"][0]
            identifiers.append(child_call["id"])
            child_final = provider.response(self.tool_request([{
                "role": "tool", "tool_call_id": child_call["id"], "content": "result",
            }]), 31)
            self.assertEqual("stop", child_final["choices"][0]["finish_reason"])
            self.assertEqual(len(identifiers), len(set(identifiers)))
        finally:
            provider.server.server_close()

    def test_manual_compact_fixture_has_bounded_first_middle_last_distribution(self):
        with target_temp_directory() as directory:
            workspace = paicli_uat.Path(directory)
            paicli_uat.create_fixture("manual-compact", workspace)
            fixtures = sorted(workspace.glob("context-*.txt"))
            self.assertEqual(paicli_uat.MANUAL_CONTEXT_FILE_COUNT, len(fixtures))
            contents = [path.read_text(encoding="utf-8") for path in fixtures]
            for content in contents:
                self.assertGreaterEqual(len(content.encode("utf-8")), 4_000)
                self.assertLess(len(content.encode("utf-8")), 5_500)
            marker_locations = {
                marker: [index + 1 for index, content in enumerate(contents)
                         if marker in content]
                for marker in paicli_uat.MANUAL_MARKERS
            }
            self.assertEqual({
                paicli_uat.MANUAL_MARKERS[0]: [paicli_uat.MANUAL_MARKER_INDEXES[0]],
                paicli_uat.MANUAL_MARKERS[1]: [paicli_uat.MANUAL_MARKER_INDEXES[1]],
                paicli_uat.MANUAL_MARKERS[2]: [paicli_uat.MANUAL_MARKER_INDEXES[2]],
            }, marker_locations)
            self.assertIn(paicli_uat.MANUAL_PROHIBITION,
                          contents[paicli_uat.MANUAL_MARKER_INDEXES[1] - 1])
            self.assertIn(paicli_uat.MANUAL_UNFINISHED,
                          contents[paicli_uat.MANUAL_MARKER_INDEXES[2] - 1])

    def test_failure_evidence_reports_durable_tool_facts_without_masking_failure(self):
        durable = {
            "toolCounts": {"read_file": 3},
            "task": "COMPLETED", "run": "COMPLETED",
        }
        session = mock.Mock(transcript="three reads then COMPACTION_NOT_APPLICABLE")
        before = {"README.md": "before"}
        with mock.patch.object(paicli_uat, "state_summary", return_value=durable), \
                mock.patch.object(paicli_uat, "user_hashes", return_value=before), \
                mock.patch.object(paicli_uat, "private_file_hits", return_value=[]):
            evidence = paicli_uat.collect_failure_evidence(
                paicli_uat.Path("bounded-workspace"), before, session,
                paicli_uat.Path("bounded-dist"))
        self.assertEqual(3, evidence["toolExecutions"])
        self.assertEqual({"read_file": 3}, evidence["toolCounts"])
        self.assertEqual("COMPLETED", evidence["taskState"])
        self.assertEqual("COMPLETED", evidence["runState"])
        self.assertFalse(evidence["filesChanged"])
        self.assertEqual([], evidence["evidenceCollectionErrors"])

    def test_long_task_provider_is_stateful_and_uses_unique_tool_calls(self):
        provider = paicli_uat.ScenarioProvider("auto-long-task")
        try:
            plan = provider.response({"response_format": {"type": "json_object"},
                                      "messages": []}, 1)
            parsed = paicli_uat.json.loads(plan["choices"][0]["message"]["content"])
            self.assertEqual(15, len(parsed["steps"]))
            identifiers = []
            tool = provider.response(self.tool_request(), 2)
            for index in range(15):
                call = tool["choices"][0]["message"]["tool_calls"][0]
                identifiers.append(call["id"])
                completed = provider.response(self.tool_request([{
                    "role": "tool", "tool_call_id": call["id"],
                    "content": "durable result",
                }]), index * 2 + 3)
                expected = "stop" if index == 14 else "tool_calls"
                self.assertEqual(expected, completed["choices"][0]["finish_reason"])
                tool = completed
            self.assertEqual(15, len(set(identifiers)))
            self.assertEqual([], provider.rejected_candidate_ids)
            self.assertEqual("fixtures/item-15.txt",
                             provider.tools[-1]["arguments"]["path"])
        finally:
            provider.server.server_close()

    def test_v3_plan_fixture_declares_runtime_owned_exact_selector_without_digest(self):
        requirement = paicli_uat.PLAN["steps"][0]["toolRequirements"][0]
        self.assertEqual("EXACT_PATH", requirement["authoritySelector"]["kind"])
        self.assertEqual("README.md", requirement["authoritySelector"]["path"])
        self.assertNotIn("canonicalJson", requirement["authoritySelector"])
        self.assertNotIn("digest", requirement["authoritySelector"])
        self.assertEqual("RESUME_REUSE_ONLY", requirement["freshnessPolicy"])

    def test_legacy_fixture_v2_hash_matches_java_golden_vector(self):
        revision = {
            "plan_revision_id": "pln_00000000000040008000000000000001",
            "task_id": "tsk_00000000000040008000000000000002",
            "revision_number": 1, "goal": "goal", "acceptance_criteria": "accept",
            "assumptions": "", "risks": "", "verification_strategy": "verify",
            "source_type": "MODEL", "provenance": "test",
            "created_by_run_id": "run_00000000000040008000000000000003",
        }
        steps = [{
            "step_revision_id": "stp_00000000000040008000000000000004",
            "logical_step_id": "stp_00000000000040008000000000000005",
            "step_order": 0, "title": "one", "definition": "definition",
            "completion_rule": "ALL_REQUIRED_EVIDENCE", "effect_class": "GENERAL",
            "required_evidence_kinds": ["TOOL_CALL", "SNAPSHOT"],
            "canonical_hash": "b" * 64,
        }]
        self.assertEqual(
            "1d662d872d4637fda0466bf0dce1dfc998fe5ebb1235c9351a61f094000cd7aa",
            paicli_uat.canonical_v2_plan_hash(revision, steps))

    def test_sequential_deviation_runs_two_legal_steps_then_first_future_fails_closed(self):
        provider = paicli_uat.ScenarioProvider(
            "plan-tool-scope-sequential-deviation")
        try:
            planned = provider.plans[0]["steps"]
            self.assertEqual(5, len(planned))
            selectors = [step["toolRequirements"][0]["authoritySelector"]["path"]
                         for step in planned]
            self.assertEqual([
                f"scope-sequential/item-{index:02d}.txt" for index in range(1, 6)
            ], selectors)
            self.assertEqual("scope-sequential/item-02.txt",
                             provider.tools[1]["arguments"]["path"])
            self.assertNotIn("expectedAdmission", provider.tools[1])
            self.assertEqual("scope-sequential/item-04.txt",
                             provider.tools[2]["arguments"]["path"])
            self.assertNotIn("expectedAdmission", provider.tools[2])
        finally:
            provider.server.server_close()

    def test_unfinished_v2_assertions_require_bound_resume_and_typed_recover_continue(self):
        provider = paicli_uat.ScenarioProvider("plan-v2-unfinished-fail-closed")
        try:
            plan_id = "pln_00000000000040008000000000000001"
            plan_hash = "a" * 64
            planning_occurrence = {
                "operation_type": "PROVIDER", "status": "COMPLETED",
                "target": "PLAN",
            }
            state = {
                "toolCounts": {}, "tasks": [{"status": "FAILED"}],
                "runs": [{"terminal_status": None}],
                "occurrences": [planning_occurrence],
                "planEvidence": 0, "planToolRequirements": [],
                "planOccurrenceSlotBindings": [], "planEvidenceSlotBindings": [],
                "toolExecutions": 0, "durableEventsContiguous": True,
                "planRevisionRows": [{
                    "plan_revision_id": plan_id, "canonical_hash": plan_hash,
                    "lifecycle": "APPROVED",
                }],
            }
            snapshot = {
                key: state[key] for key in (
                    "runs", "occurrences", "planEvidence",
                    "planOccurrenceSlotBindings", "planEvidenceSlotBindings",
                    "toolExecutions")
            }
            flow = {
                "seeded": {"planId": plan_id, "planHash": plan_hash},
                "beforeResume": snapshot, "afterResume": dict(snapshot),
                "afterRecover": dict(snapshot), "afterContinue": dict(snapshot),
                "resumeBoundWithoutExecution": True, "recoverTypedRejection": True,
                "continueTypedRejection": True,
            }
            assertions = paicli_uat.extended_assertions(
                "plan-v2-unfinished-fail-closed", state, "", provider,
                {"README.md": "same"}, {"README.md": "same"}, {}, flow, [])
            self.assertTrue(all(assertions.values()), assertions)

            flow["afterContinue"] = dict(snapshot, toolExecutions=1)
            mutated = paicli_uat.extended_assertions(
                "plan-v2-unfinished-fail-closed", state, "", provider,
                {"README.md": "same"}, {"README.md": "same"}, {}, flow, [])
            self.assertFalse(mutated["noResumeOrContinueAuthorityMutation"])
        finally:
            provider.server.server_close()

    def test_multi_tool_batch_declares_two_exact_slots_and_emits_one_atomic_turn(self):
        provider = paicli_uat.ScenarioProvider(
            "plan-tool-scope-multi-tool-batch")
        try:
            requirements = provider.plans[0]["steps"][0]["toolRequirements"]
            self.assertEqual([0, 1], [item["requirementOrder"] for item in requirements])
            response = provider.response(self.tool_request(), 1)
            calls = response["choices"][0]["message"]["tool_calls"]
            self.assertEqual(2, len(calls))
            self.assertEqual(2, len({call["id"] for call in calls}))
            self.assertEqual(
                ["scope-batch/alpha.txt", "scope-batch/beta.txt"],
                [paicli_uat.json.loads(call["function"]["arguments"])["path"]
                 for call in calls])
            messages = [{"role": "tool", "tool_call_id": call["id"],
                         "content": "durable result"} for call in calls]
            completed = provider.response(self.tool_request(messages), 2)
            self.assertEqual("stop", completed["choices"][0]["finish_reason"])
        finally:
            provider.server.server_close()

    def test_semantic_count_fixture_keeps_two_requirements_advisory(self):
        provider = paicli_uat.ScenarioProvider("plan-evidence-semantic-count")
        try:
            requirements = provider.plans[0]["steps"][0]["toolRequirements"]
            self.assertEqual(2, len(requirements))
            self.assertTrue(all(item["minSuccesses"] == 1 for item in requirements))
            response = provider.response(self.tool_request(), 1)
            calls = response["choices"][0]["message"]["tool_calls"]
            self.assertEqual(2, len(calls))
            self.assertEqual(2, len({call["id"] for call in calls}))
            paths = [paicli_uat.json.loads(call["function"]["arguments"])["path"]
                     for call in calls]
            self.assertEqual(["semantic-count/first.txt", "semantic-count/second.txt"], paths)
        finally:
            provider.server.server_close()

    def test_normal_long_plan_exercises_preview_bounds_without_mutating_canonical(self):
        plan = paicli_uat.normal_long_plan()
        self.assertEqual(7, len(plan["steps"]))
        self.assertEqual(
            list(paicli_uat.NORMAL_LONG_PLAN_TITLES),
            [step["title"] for step in plan["steps"]])
        definitions = [step["definition"] for step in plan["steps"]]
        self.assertGreater(sum(map(len, definitions)), 256)
        self.assertGreater(max(map(len, definitions)), 256)
        self.assertNotIn(paicli_uat.NORMAL_LONG_PLAN_DEFINITION_MARKERS[3],
                         definitions[3][:256])
        for marker, definition in zip(
                paicli_uat.NORMAL_LONG_PLAN_DEFINITION_MARKERS, definitions):
            self.assertIn(marker, definition)
        for title in paicli_uat.NORMAL_LONG_PLAN_TITLES:
            self.assertTrue(any("\u4e00" <= character <= "\u9fff"
                                for character in title))

    def test_normal_long_plan_provider_and_fixture_use_seven_unique_reads(self):
        provider = paicli_uat.ScenarioProvider("normal-long-plan-approval")
        try:
            self.assertEqual(7, len(provider.tools))
            self.assertEqual(7, len({item["arguments"]["path"]
                                     for item in provider.tools}))
            identifiers = []
            tool = provider.response(self.tool_request(), 1)
            for index in range(7):
                call = tool["choices"][0]["message"]["tool_calls"][0]
                identifiers.append(call["id"])
                completed = provider.response(self.tool_request([{
                    "role": "tool", "tool_call_id": call["id"],
                    "content": paicli_uat.NORMAL_LONG_PLAN_RESULT_MARKERS[index],
                }]), index * 2 + 2)
                if index < 6:
                    tool = completed
                    self.assertEqual("tool_calls", tool["choices"][0]["finish_reason"])
                else:
                    self.assertEqual("stop", completed["choices"][0]["finish_reason"])
            self.assertEqual(7, len(set(identifiers)))
        finally:
            provider.server.server_close()

        with target_temp_directory() as directory:
            workspace = paicli_uat.Path(directory)
            paicli_uat.create_fixture("normal-long-plan-approval", workspace)
            fixtures = sorted((workspace / paicli_uat.NORMAL_LONG_PLAN_DIRECTORY)
                              .glob("evidence-*.txt"))
            self.assertEqual(7, len(fixtures))
            for marker, fixture in zip(
                    paicli_uat.NORMAL_LONG_PLAN_RESULT_MARKERS, fixtures):
                self.assertIn(marker, fixture.read_text(encoding="utf-8"))

    def test_normal_long_plan_does_not_change_auto_compact_smoke_fixture(self):
        config = paicli_uat.extended_config("normal-long-plan-approval")
        self.assertNotIn("context_window_tokens", config)
        self.assertEqual(8, config["max_iterations"])
        auto = paicli_uat.extended_config("auto-long-task")
        self.assertEqual(22000, auto["context_window_tokens"])
        self.assertEqual(20, auto["max_iterations"])
        provider = paicli_uat.ScenarioProvider("auto-long-task")
        try:
            self.assertEqual(15, len(provider.tools))
            self.assertEqual(0, sum(
                1 for item in provider.tools if item.get("expectedAdmission")))
            self.assertEqual(12, provider.network_fault_logical_turn)
        finally:
            provider.server.server_close()

    def test_structured_verification_fail_closed_disables_repair(self):
        config = paicli_uat.extended_config("structured-verify-fail-closed")
        self.assertTrue(config["enable_verify"])
        self.assertFalse(config["enable_repair"])
        self.assertEqual(1, config["max_repair_attempts"])

    def test_normal_long_plan_assertions_require_preview_details_and_durable_identity(self):
        canonical = paicli_uat.normal_long_plan()
        provider = paicli_uat.ScenarioProvider("normal-long-plan-approval")
        provider.tool_index = 7
        provider.context_checks.append({marker: True for marker in
                                        paicli_uat.NORMAL_LONG_PLAN_RESULT_MARKERS})
        durable_steps = [{
            "step_order": index,
            "title": step["title"],
            "definition": step["definition"],
            "completion_rule": "ALL_REQUIRED_EVIDENCE",
            "required_evidence_kinds": "TOOL_CALL",
            "effect_class": "READ",
        } for index, step in enumerate(canonical["steps"])]
        preview = ("7 steps\n" + "\n".join(paicli_uat.NORMAL_LONG_PLAN_TITLES)
                   + "\n读取第四份只读证据\n"
                   + "\n".join(marker for index, marker in enumerate(
                       paicli_uat.NORMAL_LONG_PLAN_DEFINITION_MARKERS)
                       if index != 3))
        detail_steps = "\n".join(
            f"{index}. {title}\nDefinition: {definition}\nAdvisory dependencies: none"
            for index, (title, definition) in enumerate(
                zip(paicli_uat.NORMAL_LONG_PLAN_TITLES,
                    (step["definition"] for step in canonical["steps"])), 1))
        canonical_hash = "b" * 64
        details = (
            f"Plan revision: pln_test\nPlan hash: {canonical_hash}\nGoal: goal\n"
            "Plan canonical schema: V3\nPlan mode: DEFAULT_ADVISORY_PLAN\n"
            "Acceptance criteria: criteria\nAssumptions: assumptions\n"
            "Risks: risks\nVerification strategy: inspect\nSteps (7):\n"
            + detail_steps)
        state = {
            "toolCounts": {"read_file": 7},
            "tasks": [{"status": "COMPLETED"}],
            "runs": [{"terminal_status": "COMPLETED"}],
            "occurrences": [], "durableEventsContiguous": True,
            "planStepDefinitions": durable_steps,
            "planRevisions": 1, "approvedPlans": 1, "planRunBindings": 0,
            "planRevisionRows": [{"lifecycle": "APPROVED",
                                   "plan_revision_id": "pln_test",
                                   "canonical_hash": canonical_hash}],
            "distinctToolCallIds": 7,
        }
        flow = {
            "approvalPreview": preview,
            "approvalDetails": details,
            "planSummary": "\n".join(paicli_uat.NORMAL_LONG_PLAN_TITLES),
            "planDetails": details,
            "groundedFinalEvidence": {
                "resultCount": 1, "allMarkersPresent": True,
                "contentHashesVerified": True, "allValidated": True,
                "allRendered": True,
            },
        }
        try:
            assertions = paicli_uat.extended_assertions(
                "normal-long-plan-approval", state, "", provider,
                {"README.md": "same"}, {"README.md": "same"}, {}, flow, [])
            self.assertTrue(all(assertions.values()), assertions)
            broken = dict(flow)
            broken["approvalDetails"] = "Steps (7): without canonical definitions"
            rejected = paicli_uat.extended_assertions(
                "normal-long-plan-approval", state, "", provider,
                {"README.md": "same"}, {"README.md": "same"}, {}, broken, [])
            self.assertFalse(rejected["approvalDetailsShowCanonicalDefinitions"])
        finally:
            provider.server.server_close()

    def test_auto_long_task_config_and_fixture_preserve_threshold_contract(self):
        config = paicli_uat.extended_config("auto-long-task")
        self.assertEqual(22000, config["context_window_tokens"])
        self.assertEqual(20, config["max_iterations"])
        with target_temp_directory() as directory:
            workspace = paicli_uat.Path(directory)
            paicli_uat.create_fixture("auto-long-task", workspace)
            fixtures = sorted((workspace / "fixtures").glob("item-*.txt"))
            self.assertEqual(15, len(fixtures))
            for index, fixture in enumerate(fixtures, 1):
                content = fixture.read_text(encoding="utf-8")
                repeated = f"deterministic-{index:02d}-payload "
                expected_repetitions = 1500 if index in (6, 12) else 260
                self.assertEqual(expected_repetitions, content.count(repeated))
                self.assertGreaterEqual(len(content.encode("utf-8")), 6000)
                self.assertLess(len(content.encode("utf-8")), 40_000)

    def test_first_successful_compact_requires_six_completed_distinct_reads(self):
        state = {
            "contextGenerations": [
                {"generation_kind": "COMPACTED", "lifecycle": "FAILED",
                 "created_at": "2026-08-02T00:00:02Z"},
                {"generation_kind": "COMPACTED", "lifecycle": "READY",
                 "created_at": "2026-08-02T00:00:08Z"},
            ],
            "occurrences": [
                {"operation_type": "TOOL", "target": "read_file",
                 "status": "COMPLETED", "tool_call_id": f"call-{index}",
                 "completed_at": f"2026-08-02T00:00:0{index}Z"}
                for index in range(1, 8)
            ],
        }
        self.assertEqual(
            7, paicli_uat.distinct_reads_before_first_successful_compact(state))
        state["contextGenerations"][1]["created_at"] = "2026-08-02T00:00:06Z"
        self.assertEqual(
            6, paicli_uat.distinct_reads_before_first_successful_compact(state))
        state["contextGenerations"][1]["lifecycle"] = "FAILED"
        self.assertEqual(
            0, paicli_uat.distinct_reads_before_first_successful_compact(state),
            "an untriggered compact must never satisfy the timing assertion")

    def test_context_markers_must_be_present_in_provider_input(self):
        provider = paicli_uat.ScenarioProvider("auto-long-task")
        try:
            missing = provider.response({"messages": [{
                "role": "user", "content":
                "Produce the final user-facing answer for the original Task now."
            }]}, 1)
            self.assertEqual("CONTEXT_MARKERS_MISSING",
                             missing["choices"][0]["message"]["content"])
            content = ("Produce the final user-facing answer for the original Task now. "
                       + " ".join(paicli_uat.LONG_MARKERS))
            grounded = provider.response(
                {"messages": [{"role": "user", "content": content}]}, 2)
            self.assertTrue(grounded["choices"][0]["message"]["content"]
                            .startswith("Grounded final:"))
            self.assertTrue(all(provider.context_checks[-1].values()))
        finally:
            provider.server.server_close()

    def test_durable_final_marker_evidence_is_hash_checked_and_content_free(self):
        with target_temp_directory() as directory:
            workspace = paicli_uat.Path(directory)
            database = workspace / ".paicli" / "state" / "paicli.db"
            database.parent.mkdir(parents=True)
            content = "Grounded final: " + "; ".join(paicli_uat.LONG_MARKERS)
            content_hash = paicli_uat.hashlib.sha256(
                content.encode("utf-8")).hexdigest()
            with paicli_uat.closing(sqlite3.connect(database)) as connection:
                connection.executescript("""
                    create table task_user_results(
                      task_id text,run_id text,content text,content_hash text,
                      validation_status text,render_status text,created_at text);
                    """)
                connection.execute("""
                    insert into task_user_results values(?,?,?,?,?,?,?)
                    """, ("task", "run", content, content_hash, "VALID", "RENDERED", "1"))
                connection.commit()

            evidence = paicli_uat.durable_user_result_marker_evidence(
                workspace, paicli_uat.LONG_MARKERS)
            self.assertEqual({
                "resultCount": 1,
                "allMarkersPresent": True,
                "contentHashesVerified": True,
                "allValidated": True,
                "allRendered": True,
            }, evidence)
            self.assertNotIn("content", evidence)
            self.assertNotIn(content, repr(evidence))

            with paicli_uat.closing(sqlite3.connect(database)) as connection:
                connection.execute("""
                    update task_user_results
                    set content=?,validation_status='INVALID'
                    """, ("Grounded final without durable markers",))
                connection.commit()
            rejected = paicli_uat.durable_user_result_marker_evidence(
                workspace, paicli_uat.LONG_MARKERS)
            self.assertFalse(rejected["allMarkersPresent"])
            self.assertFalse(rejected["contentHashesVerified"])
            self.assertFalse(rejected["allValidated"])

    def test_state_summary_proves_compaction_reduction_and_no_event_deletion(self):
        with target_temp_directory() as directory:
            workspace = paicli_uat.Path(directory)
            database = workspace / ".paicli" / "state" / "paicli.db"
            database.parent.mkdir(parents=True)
            with paicli_uat.closing(sqlite3.connect(database)) as connection:
                connection.executescript("""
                    create table events(id integer primary key, content text);
                    insert into events values(1,'a');
                    insert into events values(2,'b');
                    create table context_generations(
                      context_generation_id text, task_id text, run_id text,
                      generation_kind text, operation_kind text, lifecycle text,
                      input_estimate integer, tool_schema_estimate integer,
                      output_reserve integer, safety_margin integer,
                      source_context_generation_id text, created_at text);
                    insert into context_generations values(
                      'gen-source','task','run','BASE','CHAT','READY',
                      9000,1000,1000,500,null,'1');
                    insert into context_generations values(
                      'gen-compact','task','run','COMPACTED','CHAT','READY',
                      3000,1000,1000,500,'gen-source','2');
                    insert into context_generations values(
                      'gen-source-two','task','run','BASE','CHAT','SUPERSEDED',
                      8000,1000,1000,500,null,'3');
                    insert into context_generations values(
                      'gen-compact-two','task','run','COMPACTED','CHAT','SUPERSEDED',
                      2000,1000,1000,500,'gen-source-two','4');
                    create table context_generation_pointers(
                      task_id text,run_id text,operation_kind text,
                      active_context_generation_id text,pointer_version integer);
                    insert into context_generation_pointers values(
                      'task','run','CHAT','gen-compact',2);
                    create table provider_call_telemetry(
                      telemetry_id integer primary key,retry_count integer);
                    insert into provider_call_telemetry values(1,0);
                    insert into provider_call_telemetry values(2,1);
                    """)
            state = paicli_uat.state_summary(workspace)
            self.assertTrue(state["durableEventsContiguous"])
            self.assertEqual(2, state["effectiveReductions"])
            self.assertEqual("gen-compact",
                             state["contextPointers"][0]["active_context_generation_id"])
            self.assertEqual(2, state["providerAttemptRows"])
            self.assertEqual(1, state["providerRetryCount"])

    def test_active_base_closure_traces_to_superseded_compacted_ledger(self):
        compacted_hash = "a" * 64
        state = {
            "contextGenerations": [
                {"context_generation_id": "gen-compacted",
                 "generation_kind": "COMPACTED", "lifecycle": "SUPERSEDED"},
                {"context_generation_id": "gen-active-base",
                 "generation_kind": "BASE", "lifecycle": "READY"},
            ],
            "contextPointers": [{
                "active_context_generation_id": "gen-active-base",
                "pointer_version": 7,
            }],
            "contextSegments": [
                {"context_generation_id": "gen-compacted", "ordinal": 0,
                 "segment_kind": "COMPACTED_LEDGER", "content_hash": compacted_hash},
                {"context_generation_id": "gen-active-base", "ordinal": 0,
                 "segment_kind": "COMPACTED_LEDGER", "content_hash": "b" * 64},
                {"context_generation_id": "gen-active-base", "ordinal": 1,
                 "segment_kind": "USER_GOAL", "content_hash": "c" * 64},
            ],
            "contextSourceReferences": [
                {"context_generation_id": "gen-compacted", "ordinal": 0,
                 "source_type": "TOOL_RESULT", "source_id": "occ_fixture",
                 "source_hash": compacted_hash, "source_order": 0},
                {"context_generation_id": "gen-active-base", "ordinal": 0,
                 "source_type": "CONTEXT_SEGMENT", "source_id": "gen-compacted:0",
                 "source_hash": compacted_hash, "source_order": 0},
                {"context_generation_id": "gen-active-base", "ordinal": 1,
                 "source_type": "TASK", "source_id": "tsk_fixture",
                 "source_hash": "c" * 64, "source_order": 0},
            ],
        }
        closure = paicli_uat.active_base_compaction_closure(state)
        self.assertTrue(closure["valid"])
        self.assertEqual(["gen-active-base"], closure["activeBaseGenerations"])
        self.assertEqual(["gen-compacted"], closure["compactedSourceGenerations"])

    def test_active_base_without_complete_compacted_closure_fails(self):
        state = {
            "contextGenerations": [
                {"context_generation_id": "gen-compacted",
                 "generation_kind": "COMPACTED", "lifecycle": "SUPERSEDED"},
                {"context_generation_id": "gen-active-base",
                 "generation_kind": "BASE", "lifecycle": "READY"},
            ],
            "contextPointers": [{
                "active_context_generation_id": "gen-active-base",
                "pointer_version": 7,
            }],
            "contextSegments": [
                {"context_generation_id": "gen-compacted", "ordinal": 0,
                 "segment_kind": "COMPACTED_LEDGER", "content_hash": "a" * 64},
                {"context_generation_id": "gen-active-base", "ordinal": 0,
                 "segment_kind": "COMPACTED_LEDGER", "content_hash": "b" * 64},
            ],
            "contextSourceReferences": [],
        }
        closure = paicli_uat.active_base_compaction_closure(state)
        self.assertFalse(closure["valid"])
        self.assertEqual(["gen-active-base"], closure["invalidActiveBaseGenerations"])

    def test_private_scan_reads_utf8_and_utf16_without_echoing_contents(self):
        with target_temp_directory() as directory:
            root = paicli_uat.Path(directory)
            (root / "utf8.log").write_bytes(paicli_uat.SECRET.encode("utf-8"))
            (root / "utf16.log").write_bytes("reasoning_content".encode("utf-16-le"))
            hits = paicli_uat.private_file_hits([root])
            self.assertIn("utf8.log:" + paicli_uat.SECRET, hits)
            self.assertIn("utf16.log:reasoning_content", hits)

    def test_provider_attempt_evidence_groups_retry_without_persisting_body(self):
        provider = paicli_uat.FakeProvider()
        try:
            body = {"messages": [{"role": "user", "content": "bounded request"}]}
            first = provider.record_attempt(body)
            retry = provider.record_attempt(body)
            self.assertEqual(first["logicalTurn"], retry["logicalTurn"])
            self.assertEqual(1, first["actualAttempt"])
            self.assertEqual(2, retry["actualAttempt"])
            self.assertEqual(first["requestDigest"], retry["requestDigest"])
            self.assertEqual("STARTED", first["outcome"])
            self.assertNotIn("bounded request", paicli_uat.json.dumps(
                provider.request_attempts))
            provider.mark_attempt_completed(retry, "HTTP_200")
            self.assertEqual("HTTP_200", provider.request_attempts[-1]["outcome"])
            next_turn = provider.record_attempt(body)
            self.assertEqual(first["logicalTurn"] + 1, next_turn["logicalTurn"])
            self.assertEqual(1, next_turn["actualAttempt"])
        finally:
            provider.server.server_close()

    def test_auto_long_task_injects_one_real_http_503_then_same_digest_succeeds(self):
        provider = paicli_uat.ScenarioProvider("auto-long-task")
        provider.network_fault_logical_turn = 1
        provider.start()
        connection = http.client.HTTPConnection(
            "127.0.0.1", provider.server.server_port, timeout=5)
        body = paicli_uat.json.dumps({
            "messages": [{"role": "user", "content":
                          "Context identity: ctx=ctx_retryfixture123"}],
            "tools": [{"type": "function", "function": {"name": "read_file"}}],
        }, separators=(",", ":")).encode("utf-8")
        try:
            connection.request("POST", "/v1/chat/completions", body=body,
                               headers={"Content-Type": "application/json"})
            first = connection.getresponse()
            first.read()
            self.assertEqual(503, first.status)

            connection.request("POST", "/v1/chat/completions", body=body,
                               headers={"Content-Type": "application/json"})
            second = connection.getresponse()
            second.read()
            self.assertEqual(200, second.status)

            evidence = paicli_uat.network_retry_evidence(provider)
            self.assertTrue(evidence["schemaComplete"])
            self.assertEqual([1, 2], evidence["attempts"])
            self.assertEqual(["HTTP_503", "HTTP_200"], evidence["outcomes"])
            self.assertEqual(1, evidence["totalRetries"])
            self.assertEqual(1, len(evidence["requestDigests"]))
            self.assertEqual(["ctx_retryfixture123"],
                             evidence["contextGenerations"])
            self.assertEqual(paicli_uat.hashlib.sha256(body).hexdigest(),
                             evidence["requestDigests"][0])
            self.assertNotIn("Context identity", paicli_uat.json.dumps(
                provider.request_attempts))
        finally:
            connection.close()
            provider.close()


if __name__ == "__main__":
    unittest.main()
